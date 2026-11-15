package com.qiao.remotedesk.rdp;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

/**
 * 基于 FreeRDP 原生库（libqrdp.so）的 RDP 会话。
 *
 * <p>协议、TLS、NLA、能力集协商全部交给 FreeRDP，本类只负责：
 * 创建原生会话 → 绑定一块 Bitmap 作为绘制目标 → 轮询原生状态位与帧序号 →
 * 把输入事件转发给原生层。
 *
 * <p>原生线程直接往 Bitmap 的像素区写，Java 侧只做「帧序号变化就重绘」。
 */
public class FreeRdpSession {

    public interface Listener {
        /** 连接建立，参数为服务器实际桌面尺寸。 */
        void onConnected(int width, int height);

        /** 有新的一帧可以绘制。 */
        void onFramebufferUpdated();

        void onLog(String message);

        void onError(String message);

        void onClosed();
    }

    private static final int ST_INIT = 0;
    private static final int ST_CONNECTING = 1;
    private static final int ST_CONNECTED = 2;
    private static final int ST_CLOSED = 3;
    private static final int ST_ERROR = 4;

    private static final int SEC_NLA = 1;
    private static final int SEC_RDP = 0;

    static {
        System.loadLibrary("qrdp2");
    }

    /**
     * 应用私有目录。FreeRDP 初始化时会通过 HOME / TMPDIR 推导 HOME、配置与缓存路径，
     * 而 Android 应用进程默认没有这两个环境变量，缺失会让 freerdp_settings_new 直接失败，
     * 所以启动会话前必须先告诉原生层一个可写目录。
     */
    public static void setWorkDir(String dir) {
        nativeSetWorkDir(dir);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String host;
    private final int port;
    private final String user;
    private final String domain;
    private final String password;
    private final int reqWidth;
    private final int reqHeight;
    private final int bpp;
    private final int desktopScale;
    /** 「桌面体验」六项位掩码，交给 JNI 写入 FreeRDP PerformanceFlags。 */
    private final int perfMask;
    private int security = SEC_RDP;

    private volatile long handle;
    private Bitmap bitmap;
    private Listener listener;
    private boolean started;
    private volatile boolean finished;

    /**
     * 所有 RDP 输入的唯一串行出口：UI 线程只投递事件，工作线程负责合并 MOVE、
     * 保序地调用 JNI。release 之前必须先 shutdown，避免工作线程碰到已销毁的句柄。
     */
    private final RdpInputDispatcher dispatcher = new RdpInputDispatcher(
            new RdpInputDispatcher.Sink() {
                @Override
                public boolean active() {
                    return handle != 0 && !finished;
                }

                @Override
                public void sendMouse(int x, int y, int flags) {
                    nativeMouse(handle, x, y, flags);
                }

                @Override
                public void sendWheel(int x, int y, int delta) {
                    nativeWheel(handle, x, y, delta);
                }

                @Override
                public void sendKey(int scancode, boolean extended, boolean down) {
                    nativeKey(handle, scancode, extended, down);
                }

                @Override
                public void sendUnicode(int codeUnit, boolean down) {
                    nativeUnicode(handle, codeUnit, down);
                }
            });
    private int lastSeq;
    private int lastState = ST_INIT;
    private int desktopWidth;
    private int desktopHeight;
    private long startAt;
    private long connectMs;
    private int frames;
    private long framesAt;
    private int fps;

    public FreeRdpSession(String host, int port, String domain, String user, String password,
                          int reqWidth, int reqHeight, int bpp, int desktopScale, int perfMask) {
        this.host = host;
        this.port = port;
        this.domain = domain == null ? "" : domain;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.reqWidth = reqWidth;
        this.reqHeight = reqHeight;
        this.bpp = bpp;
        // TS_UD_CS_CORE.desktopScaleFactor 允许 100~500，越界会被 FreeRDP 拒绝解析
        this.desktopScale = Math.max(100, Math.min(500, desktopScale));
        this.perfMask = perfMask;
    }

    /**
     * true = 不加 /sec:rdp，让 FreeRDP 与服务端协商 NLA / TLS / RDP。
     * 经代理或内网穿透的目标常常不回应「不带协商请求的纯 X.224 CR」，
     * 强制 /sec:rdp 会让握手卡住并报 ERRCONNECT_CONNECT_TRANSPORT_FAILED。
     */
    public void setNegotiateSecurity(boolean on) {
        security = on ? SEC_NLA : SEC_RDP;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isConnected() {
        return lastState == ST_CONNECTED;
    }

    public int desktopWidth() {
        return desktopWidth > 0 ? desktopWidth : reqWidth;
    }

    public int desktopHeight() {
        return desktopHeight > 0 ? desktopHeight : reqHeight;
    }

    public long connectMillis() {
        return connectMs;
    }

    public int fps() {
        return fps;
    }

    public Bitmap bitmap() {
        return bitmap;
    }

    public void start() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                doStart();
            }
        });
    }

    private void doStart() {
        if (started) {
            return;
        }
        started = true;
        finished = false;
        startAt = System.currentTimeMillis();
        dispatcher.start();

        try {
            handle = nativeCreate(null);
        } catch (Throwable t) {
            fail("加载原生库失败：" + t.getMessage());
            return;
        }
        if (handle == 0) {
            fail("初始化原生会话失败");
            return;
        }

        int w = Math.max(640, reqWidth & ~1);
        int h = Math.max(480, reqHeight & ~1);
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            fail("显存不足，无法创建 " + w + "×" + h + " 画面");
            return;
        }

        int rc = nativeInit(handle, host, port, user, domain, password, w, h, bpp,
                desktopScale, perfMask, security);
        if (rc != 0) {
            String msg = nativeLastError(handle);
            fail(msg == null || msg.isEmpty() ? "初始化连接参数失败" : msg);
            return;
        }

        nativeSetBitmap(handle, bitmap);
        if (nativeStart(handle) != 0) {
            String msg = nativeLastError(handle);
            fail(msg == null || msg.isEmpty() ? "启动连接线程失败" : msg);
            return;
        }
        log("正在连接 " + host + ":" + port + " …");
        ui.postDelayed(poll, 40);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started || finished) {
                return;
            }
            int st = nativeState(handle);

            if (st == ST_CONNECTED && lastState != ST_CONNECTED) {
                desktopWidth = nativeDesktopWidth(handle);
                desktopHeight = nativeDesktopHeight(handle);
                connectMs = System.currentTimeMillis() - startAt;
                ensureBitmapSize();
                log("已连接，桌面 " + desktopWidth + "×" + desktopHeight
                        + "，建链 " + connectMs + " ms");
                if (listener != null) {
                    listener.onConnected(desktopWidth, desktopHeight);
                }
            }
            lastState = st;

            if (st == ST_CONNECTED) {
                int seq = nativeFrameSeq(handle);
                if (seq != lastSeq) {
                    lastSeq = seq;
                    frames++;
                    long now = System.currentTimeMillis();
                    if (now - framesAt >= 1000) {
                        fps = (int) (frames * 1000 / Math.max(1, now - framesAt));
                        frames = 0;
                        framesAt = now;
                    }
                    if (listener != null) {
                        listener.onFramebufferUpdated();
                    }
                }
            } else if (st == ST_ERROR) {
                String msg = nativeLastError(handle);
                fail(msg == null || msg.isEmpty() ? "连接失败" : msg);
                return;
            } else if (st == ST_CLOSED) {
                finished = true;
                log("会话已结束");
                if (listener != null) {
                    listener.onClosed();
                }
                release();
                return;
            }

            ui.postDelayed(this, st == ST_CONNECTED ? 16 : 120);
        }
    };

    /** 服务器分辨率与请求不一致时，重建 Bitmap 并重新绑定。 */
    private void ensureBitmapSize() {
        if (desktopWidth <= 0 || desktopHeight <= 0) {
            return;
        }
        if (bitmap != null && bitmap.getWidth() == desktopWidth
                && bitmap.getHeight() == desktopHeight) {
            return;
        }
        try {
            bitmap = Bitmap.createBitmap(desktopWidth, desktopHeight, Bitmap.Config.ARGB_8888);
            nativeSetBitmap(handle, bitmap);
        } catch (OutOfMemoryError ignored) {
        }
    }

    private void fail(String message) {
        if (finished) {
            return;
        }
        finished = true;
        log("失败：" + message);
        if (listener != null) {
            listener.onError(message);
        }
        release();
    }

    private void release() {
        // 先停输入线程并等当前 JNI 调用返回，再销毁原生句柄
        dispatcher.shutdown();
        if (handle != 0) {
            final long h = handle;
            handle = 0;
            // 原生销毁会等待连接线程退出，放到后台线程避免卡住 UI。
            new Thread(new Runnable() {
                @Override
                public void run() {
                    nativeDestroy(h);
                }
            }, "qrdp-release").start();
        }
        bitmap = null;
    }

    public void stop() {
        if (finished) {
            return;
        }
        finished = true;
        ui.removeCallbacks(poll);
        release();
        if (listener != null) {
            listener.onClosed();
        }
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    public static final int PTR_MOVE = 0x0800;
    public static final int PTR_BUTTON1 = 0x1000;
    public static final int PTR_BUTTON2 = 0x2000;
    public static final int PTR_BUTTON3 = 0x4000;
    public static final int PTR_DOWN = 0x8000;

    /**
     * 纯 MOVE 进单槽合并；按钮事件作为立即消息发送，工作线程会先 flush 最新坐标。
     * MOVE 与按钮位始终分开发送，避免 Windows 忽略按下转换。
     */
    public void mouse(int x, int y, int flags) {
        if (flags == PTR_MOVE) {
            dispatcher.postMove(x, y, false);
        } else {
            dispatcher.postMouse(x, y, flags);
        }
    }

    /** 外接鼠标 hover：必须持续报坐标才能触发 Windows 右键菜单项悬停高亮。 */
    public void hover(int x, int y) {
        dispatcher.postMove(x, y, true);
    }

    public void wheel(int x, int y, int delta) {
        dispatcher.postWheel(x, y, delta);
    }

    public void key(int scancode, boolean extended, boolean down) {
        dispatcher.postKey(scancode, extended, down);
    }

    public void unicode(int codeUnit, boolean down) {
        dispatcher.postUnicode(codeUnit, down);
    }

    /** 直连 16ms（60Hz），经代理 / 隧道 33ms（约 30Hz）。会话启动前设置。 */
    public void setInputIntervalMs(int ms) {
        dispatcher.setIntervalMs(ms);
    }

    // ------------------------------------------------------------------
    // native
    // ------------------------------------------------------------------

    private static native long nativeCreate(Object unused);

    private static native void nativeSetBitmap(long h, Bitmap bitmap);

    private static native int nativeInit(long h, String host, int port, String user, String domain,
                                         String password, int width, int height, int bpp,
                                         int scale, int perf, int security);

    private static native int nativeStart(long h);

    private static native int nativeState(long h);

    private static native int nativeFrameSeq(long h);

    private static native int nativeDesktopWidth(long h);

    private static native int nativeDesktopHeight(long h);

    private static native String nativeLastError(long h);

    private static native void nativeDestroy(long h);

    private static native void nativeSetWorkDir(String dir);

    private static native void nativeMouse(long h, int x, int y, int flags);

    private static native void nativeWheel(long h, int x, int y, int delta);

    private static native void nativeKey(long h, int scancode, boolean extended, boolean down);

    private static native void nativeUnicode(long h, int codeUnit, boolean down);
}
