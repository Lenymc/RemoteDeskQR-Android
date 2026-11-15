package com.qiao.remotedesk.rdp;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * RDP 输入「合并 + 单线程串行」发送器。
 *
 * <p>改前：{@code RemoteView} 的每一个 ACTION_MOVE 都在 UI 线程同步调 JNI →
 * freerdp_input_send_mouse_event（约 60 次/秒），经 SOCKS5 代理时 socket 写会占用 UI
 * 线程，服务端还要按顺序消化积压的 MOVE，手指停下后指针仍在追尾。
 *
 * <p>现在：
 * <ul>
 *   <li>UI 线程只投递事件，不再进 JNI；本地虚拟光标的 invalidate 仍是即时的。</li>
 *   <li>所有 RDP 输入（MOVE / 按钮 / 滚轮 / 按键）由唯一工作线程按序发出，
 *       FreeRDP 的 input 句柄只被这一个线程触碰。</li>
 *   <li>MOVE 用「单槽」：连续到达的坐标互相覆盖，每 16ms（代理链路 33ms）只发最新坐标。
 *       这等价于把 60Hz 的过期 MOVE 降采样，而不是简单地降低采样频率、徒增等待。</li>
 *   <li>按钮 / 滚轮事件前强制 flush 槽里的最新坐标，保证点击落点用的是最新位置；
 *       MOVE 与按钮位仍严格分开发送（混发会让 Windows 忽略按下转换）。</li>
 *   <li>静止或抬手后由 trailing tick 在 ≤33ms 内补发最终坐标，避免指针停不准，
 *       也保住 Windows 右键菜单的悬停高亮。</li>
 * </ul>
 *
 * <p>统计每秒输出一行 {@code QRDP_INPUT_STATS}：recv 就是改前会发出的 MOVE PDU 数，
 * sent 是改后实发数，saved = recv - sent 即节省的 PDU，可直接量化降采样效果。
 */
public final class RdpInputDispatcher {

    public static final String TAG = "QRDP_INPUT";
    /** 直连：60Hz 上限。 */
    public static final int DIRECT_INTERVAL_MS = 16;
    /** 经代理 / 内网穿透：约 30Hz，减少积压。 */
    public static final int PROXY_INTERVAL_MS = 33;
    /** 位移去抖（远端像素）；hover 等重要 MOVE 不受限制，且最终坐标一定会补发。 */
    private static final int MIN_MOVE_PX = 2;

    /** 真正调 JNI 的底层出口，只会在工作线程被调用。 */
    public interface Sink {
        boolean active();

        void sendMouse(int x, int y, int flags);

        void sendWheel(int x, int y, int delta);

        void sendKey(int scancode, boolean extended, boolean down);

        void sendUnicode(int codeUnit, boolean down);
    }

    private static final int MSG_MOVE = 1;
    private static final int MSG_MOVE_IMPORTANT = 2;
    private static final int MSG_MOUSE = 3;
    private static final int MSG_WHEEL = 4;
    private static final int MSG_KEY = 5;
    private static final int MSG_UNICODE = 6;
    private static final int MSG_TICK = 7;
    private static final int MSG_TRAILING = 8;
    private static final int MSG_STATS = 9;

    /** 投递时携带 3 个 int 参数与投递时刻，便于统计跨线程排队耗时。 */
    private static final class Ev {
        int a;
        int b;
        int c;
        long at;
    }

    private final Sink sink;
    private volatile boolean shutdown = true;
    private volatile Handler worker;
    /** 基准间隔，由会话按「是否经代理」设置。 */
    private volatile int baseMs = DIRECT_INTERVAL_MS;
    private HandlerThread thread;

    // ---------------- 以下字段只在工作线程访问 ----------------
    private boolean pendingMove;
    private int pendingX;
    private int pendingY;
    private long pendingAt;
    private boolean pendingImportant;
    private boolean haveSent;
    private int lastSentX;
    private int lastSentY;
    private long lastMoveSentAt;
    private boolean tickScheduled;
    private boolean trailingScheduled;
    private volatile int effectiveMs = DIRECT_INTERVAL_MS;
    private int slowStreak;
    private int fastStreak;

    private volatile long statWindowStart;
    private int statReceived;
    private int statSent;
    private int statMerged;
    private int statDeferred;
    private int statEvents;
    private long statQueueSum;
    private long statQueueMax;
    private int statQueueN;
    private long statCoalesceSum;
    private long statCoalesceMax;
    private long statJniUs;

    public RdpInputDispatcher(Sink sink) {
        this.sink = sink;
    }

    /** 会话启动后调用；重复 start 会重建线程。 */
    public void start() {
        shutdown();
        shutdown = false;
        thread = new HandlerThread("qrdp-input", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        worker = new Handler(thread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                onWorkerMessage(msg);
                return true;
            }
        });
        statWindowStart = SystemClock.uptimeMillis();
        worker.sendEmptyMessageDelayed(MSG_STATS, 1000);
    }

    /**
     * 会话结束前调用：先拒绝新事件、清队列，等当前 JNI 调用返回，再退出 Looper。
     * 必须早于 nativeDestroy，否则工作线程可能拿到已释放的句柄。
     */
    public void shutdown() {
        shutdown = true;
        Handler h = worker;
        HandlerThread t = thread;
        worker = null;
        thread = null;
        if (h == null) {
            return;
        }
        h.removeCallbacksAndMessages(null);
        final CountDownLatch latch = new CountDownLatch(1);
        h.post(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        try {
            latch.await(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (t != null) {
            t.quitSafely();
        }
    }

    /** 直连传 DIRECT_INTERVAL_MS，经代理 / 隧道传 PROXY_INTERVAL_MS。 */
    public void setIntervalMs(int ms) {
        baseMs = Math.max(8, Math.min(100, ms));
        effectiveMs = baseMs;
    }

    // ------------------------------------------------------------------
    // UI 线程：只投递，不进 JNI
    // ------------------------------------------------------------------

    /** important = hover 等必须持续报坐标的场景，绕过位移去抖。 */
    public void postMove(int x, int y, boolean important) {
        post(important ? MSG_MOVE_IMPORTANT : MSG_MOVE, x, y, 0);
    }

    public void postMouse(int x, int y, int flags) {
        post(MSG_MOUSE, x, y, flags);
    }

    public void postWheel(int x, int y, int delta) {
        post(MSG_WHEEL, x, y, delta);
    }

    public void postKey(int scancode, boolean extended, boolean down) {
        post(MSG_KEY, scancode, extended ? 1 : 0, down ? 1 : 0);
    }

    public void postUnicode(int codeUnit, boolean down) {
        post(MSG_UNICODE, codeUnit, down ? 1 : 0, 0);
    }

    private void post(int what, int a, int b, int c) {
        Handler h = worker;
        if (shutdown || h == null) {
            return;
        }
        Ev ev = new Ev();
        ev.a = a;
        ev.b = b;
        ev.c = c;
        ev.at = SystemClock.uptimeMillis();
        Message m = h.obtainMessage(what, ev);
        m.sendToTarget();
    }

    // ------------------------------------------------------------------
    // 工作线程：唯一的 RDP 输入出口
    // ------------------------------------------------------------------

    private void onWorkerMessage(Message msg) {
        // shutdown 之后即使有残留消息也绝不碰 JNI
        if (shutdown) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        Ev ev = msg.obj instanceof Ev ? (Ev) msg.obj : null;
        if (ev != null && msg.what != MSG_STATS) {
            long q = Math.max(0, now - ev.at);
            statQueueSum += q;
            statQueueMax = Math.max(statQueueMax, q);
            statQueueN++;
        }
        switch (msg.what) {
            case MSG_MOVE:
            case MSG_MOVE_IMPORTANT: {
                if (ev == null) {
                    return;
                }
                statReceived++;
                if (pendingMove) {
                    // 槽里已有未发坐标被覆盖 —— 这就是被「降采样」掉的过期 MOVE
                    statMerged++;
                }
                pendingMove = true;
                pendingX = ev.a;
                pendingY = ev.b;
                pendingAt = now;
                pendingImportant |= msg.what == MSG_MOVE_IMPORTANT;
                if (!tickScheduled) {
                    long elapsed = now - lastMoveSentAt;
                    scheduleTick(elapsed >= interval() ? 0 : interval() - elapsed);
                }
                break;
            }
            case MSG_TICK:
                tickScheduled = false;
                flushMove(false);
                break;
            case MSG_TRAILING:
                trailingScheduled = false;
                // 静止 / 抬手后的最终坐标，必须发出去
                flushMove(true);
                break;
            case MSG_MOUSE:
            case MSG_WHEEL: {
                if (ev == null) {
                    return;
                }
                // 保序关键：先把最新坐标发出去，再发按钮 / 滚轮，点击落点才准
                flushMove(true);
                statEvents++;
                long t0 = System.nanoTime();
                if (msg.what == MSG_MOUSE) {
                    if (sink.active()) {
                        sink.sendMouse(ev.a, ev.b, ev.c);
                    }
                } else {
                    if (sink.active()) {
                        sink.sendWheel(ev.a, ev.b, ev.c);
                    }
                }
                adapt((System.nanoTime() - t0) / 1000);
                break;
            }
            case MSG_KEY: {
                if (ev == null) {
                    return;
                }
                statEvents++;
                if (sink.active()) {
                    sink.sendKey(ev.a, ev.b != 0, ev.c != 0);
                }
                break;
            }
            case MSG_UNICODE: {
                if (ev == null) {
                    return;
                }
                statEvents++;
                if (sink.active()) {
                    sink.sendUnicode(ev.a, ev.b != 0);
                }
                break;
            }
            case MSG_STATS:
                dumpStats(now);
                Handler h = worker;
                if (!shutdown && h != null) {
                    h.sendEmptyMessageDelayed(MSG_STATS, 1000);
                }
                break;
            default:
                break;
        }
    }

    /** 发送槽里的最新坐标；force = 按钮前 / trailing 补发，忽略去抖阈值。 */
    private void flushMove(boolean force) {
        if (!pendingMove) {
            return;
        }
        int dx = Math.abs(pendingX - lastSentX);
        int dy = Math.abs(pendingY - lastSentY);
        if (!force && !pendingImportant && haveSent && dx < MIN_MOVE_PX && dy < MIN_MOVE_PX) {
            // 静止抖动先压住，但安排 trailing 保证最终坐标一定会到远端
            statDeferred++;
            scheduleTrailing();
            return;
        }
        pendingMove = false;
        pendingImportant = false;
        Handler h = worker;
        if (h != null) {
            h.removeMessages(MSG_TRAILING);
        }
        trailingScheduled = false;
        long sentAt = SystemClock.uptimeMillis();
        long wait = Math.max(0, sentAt - pendingAt);
        statCoalesceSum += wait;
        statCoalesceMax = Math.max(statCoalesceMax, wait);
        if (sink.active()) {
            long t0 = System.nanoTime();
            sink.sendMouse(pendingX, pendingY, FreeRdpSession.PTR_MOVE);
            adapt((System.nanoTime() - t0) / 1000);
            statSent++;
        }
        lastSentX = pendingX;
        lastSentY = pendingY;
        lastMoveSentAt = sentAt;
        haveSent = true;
    }

    private void scheduleTick(long delay) {
        Handler h = worker;
        if (tickScheduled || h == null) {
            return;
        }
        tickScheduled = true;
        h.sendEmptyMessageDelayed(MSG_TICK, Math.max(0, delay));
    }

    private void scheduleTrailing() {
        Handler h = worker;
        if (trailingScheduled || h == null) {
            return;
        }
        trailingScheduled = true;
        h.sendEmptyMessageDelayed(MSG_TRAILING, Math.max(interval(), 32));
    }

    private int interval() {
        return effectiveMs;
    }

    /** 若 JNI 调用变慢（socket 写阻塞），临时把发送节奏降到 30Hz；恢复后回到基准。 */
    private void adapt(long costUs) {
        statJniUs += costUs;
        if (costUs > 5000) {
            slowStreak++;
            fastStreak = 0;
            if (slowStreak >= 3) {
                effectiveMs = Math.max(effectiveMs, PROXY_INTERVAL_MS);
            }
        } else if (costUs < 2000) {
            fastStreak++;
            slowStreak = 0;
            if (fastStreak >= 30) {
                effectiveMs = baseMs;
                fastStreak = 0;
            }
        } else {
            slowStreak = 0;
            fastStreak = 0;
        }
    }

    /** 每秒一行，便于 adb logcat 抓对比数据。 */
    private void dumpStats(long now) {
        long window = Math.max(1, now - statWindowStart);
        int recv = statReceived;
        int saved = Math.max(0, recv - statSent);
        double mergePct = recv > 0 ? 100.0 * statMerged / recv : 0;
        double qAvg = statQueueN > 0 ? (double) statQueueSum / statQueueN : 0;
        double cAvg = statSent > 0 ? (double) statCoalesceSum / statSent : 0;
        // 设备 persist.log.tag=W 会整体丢弃 INFO，统计用 WARN 级别保证 adb logcat 可抓
        Log.w(TAG, String.format(Locale.ROOT,
                "STATS win=%dms recv=%d sent=%d merged=%d merge=%.1f%% saved=%d "
                        + "deferred=%d events=%d interval=%dms "
                        + "qwait_avg=%.2f qwait_max=%d cwait_avg=%.2f cwait_max=%d jni_us=%d",
                window, recv, statSent, statMerged, mergePct, saved, statDeferred, statEvents,
                interval(), qAvg, statQueueMax, cAvg, statCoalesceMax, statJniUs));
        statWindowStart = now;
        statReceived = 0;
        statSent = 0;
        statMerged = 0;
        statDeferred = 0;
        statEvents = 0;
        statQueueSum = 0;
        statQueueMax = 0;
        statQueueN = 0;
        statCoalesceSum = 0;
        statCoalesceMax = 0;
        statJniUs = 0;
    }
}
