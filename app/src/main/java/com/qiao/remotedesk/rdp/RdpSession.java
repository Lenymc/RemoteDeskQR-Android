package com.qiao.remotedesk.rdp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 纯 Java RDP 客户端会话。协议栈顺序：
 * X.224 → TLS → CredSSP(NLA) → MCS/GCC → Client Info → 能力交换 → 位图/指针更新。
 * 仅依赖 java.*，可直接在桌面 JDK 上运行验证。
 */
public class RdpSession {

    public interface Listener {
        void onConnected(int width, int height);

        void onFramebufferUpdated(int left, int top, int right, int bottom);

        void onPointerUpdated(int x, int y);

        void onLog(String message);

        void onError(String message);

        void onClosed();
    }

    public interface SocketFactory {
        Socket open(String host, int port) throws IOException;
    }

    // 协议常量
    private static final int PROTOCOL_RDP = 0;
    private static final int PROTOCOL_SSL = 1;
    private static final int PROTOCOL_HYBRID = 2;
    private static final int PROTOCOL_HYBRID_EX = 8;

    private static final int PDU_TYPE_DEMAND_ACTIVE = 1;
    private static final int PDU_TYPE_CONFIRM_ACTIVE = 3;
    private static final int PDU_TYPE_DEACTIVATE_ALL = 6;
    private static final int PDU_TYPE_DATA = 7;

    private static final int DATA_TYPE_UPDATE = 0x02;
    private static final int DATA_TYPE_CONTROL = 0x14;
    private static final int DATA_TYPE_POINTER = 0x17;
    private static final int DATA_TYPE_SYNCHRONIZE = 0x1F;
    private static final int DATA_TYPE_FONT_LIST = 0x27;
    private static final int DATA_TYPE_FONT_MAP = 0x28;
    private static final int DATA_TYPE_PERSISTENT_KEY_LIST = 0x2B;
    private static final int DATA_TYPE_SET_ERROR_INFO = 0x2F;
    private static final int DATA_TYPE_STATUS_INFO = 0x36;
    private static final int DATA_TYPE_LICENSE = 0x03;

    private static final int SEC_INFO_PKT = 0x0040;

    private final SocketFactory factory;
    private final String host;
    private final int port;
    private final String domain;
    private final String user;
    private final String password;
    private final int reqWidth;
    private final int reqHeight;

    private volatile Listener listener;
    private volatile boolean connected;
    private volatile boolean running;
    private volatile boolean stopped;
    private Thread thread;

    private Socket socket;
    private SSLSocket sslSocket;
    private PushbackInputStream in;
    private OutputStream out;
    private McS mcs;

    private volatile int[] pixels = new int[0];
    private volatile int fbWidth;
    private volatile int fbHeight;
    private final Object fbLock = new Object();

    private int desktopWidth;
    /** 服务器 Demand Active 里声明的 preferredBitsPerPixel，用于确认包与解码对齐。 */
    private int serverBpp;
    private int desktopHeight;
    private int shareId;
    private int serverChannelId;
    /** 服务器在 Demand Active 里下发的能力集类型序列，确认包按同样顺序回应。 */
    private final java.util.ArrayList<Integer> serverCaps =
            new java.util.ArrayList<Integer>();
    private final java.util.ArrayList<Integer> serverCapLens =
            new java.util.ArrayList<Integer>();
    private boolean activated;
    private boolean firstFrameLogged;

    // 光标
    private volatile int pointerX = -1;
    private volatile int pointerY = -1;
    private volatile int[] pointerShape = new int[0];
    private volatile int pointerW;
    private volatile int pointerH;
    private volatile int pointerHotX;
    private volatile int pointerHotY;

    // 统计
    private long bytesIn;
    private long bytesOut;
    private long frames;
    private long startTime;
    private long fpsWindowStart;
    private int fpsFrames;
    private int fps;
    private long lastFpsValueTime;
    private int lastFpsValue;

    public RdpSession(SocketFactory factory, String host, int port,
                      String domain, String user, String password,
                      int reqWidth, int reqHeight) {
        this.factory = factory;
        this.host = host;
        this.port = port;
        this.domain = domain == null ? "" : domain;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.reqWidth = reqWidth > 0 ? reqWidth : 1280;
        this.reqHeight = reqHeight > 0 ? reqHeight : 800;
        this.desktopWidth = this.reqWidth;
        this.desktopHeight = this.reqHeight;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        stopped = false;
        startTime = System.currentTimeMillis();
        fpsWindowStart = startTime;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runSession();
            }
        }, "rdp-session");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        running = false;
        closeQuietly();
    }

    public boolean isConnected() {
        return connected;
    }

    public int[] pixels() {
        return pixels;
    }

    public int width() {
        return fbWidth;
    }

    public int height() {
        return fbHeight;
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private void runSession() {
        try {
            openSocket();
            int selected = x224Connect();
            currentSelectedProtocol = selected;
            wrapTls(selected);
            if (selected == PROTOCOL_HYBRID || selected == PROTOCOL_HYBRID_EX) {
                doCredSsp();
            } else if (selected == PROTOCOL_RDP) {
                throw new RdpException("服务器要求标准 RDP 加密（RC4），暂不支持。"
                        + "请在服务器上启用 TLS 或 NLA（系统属性 → 远程 → 仅允许运行使用网络级别身份验证的远程桌面的计算机连接）。");
            } else {
                log("[3/7] 跳过 CredSSP（selectedProtocol=" + selected + " 仅 TLS）");
            }
            if (System.getProperty("rdp.probe1") != null) {
                sslSocket.setSoTimeout(2000);
                log("调试：CredSSP 后探测服务器是否有数据/关闭");
                try {
                    int b = in.read();
                    log("调试：读到 " + (b < 0 ? "EOF(服务器已关闭)" : ("0x" + Integer.toHexString(b))));
                } catch (java.net.SocketTimeoutException e) {
                    log("调试：2 秒内无数据（连接仍然有效）");
                }
                throw new RdpException("调试结束");
            }
            if (System.getProperty("rdp.nomcs") != null) {
                log("调试：跳过 MCS，等待服务器反应");
                try {
                    Thread.sleep(8000);
                    log("调试：8 秒内连接未被重置");
                } catch (InterruptedException ignored) {
                    // 忽略
                }
                throw new RdpException("调试结束");
            }
            doMcs();
            if (System.getProperty("rdp.noclientinfo") != null) {
                log("调试：MCS 完成后不发送 Client Info，观察 6 秒");
                sslSocket.setSoTimeout(6000);
                try {
                    int b = in.read();
                    log("调试：收到 " + (b < 0 ? "EOF" : "0x" + Integer.toHexString(b)));
                } catch (java.net.SocketTimeoutException e) {
                    log("调试：6 秒内无数据且连接未断");
                }
                throw new RdpException("调试结束");
            }
            sendClientInfo();
            log("[5/7] Client Info 已发送，等待能力交换");
            readLoop();
        } catch (RdpException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            if (!stopped) {
                fail("网络错误：" + e.getMessage());
            }
        } catch (Throwable t) {
            fail("内部错误：" + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            cleanup();
        }
    }

    private void fail(String msg) {
        connected = false;
        running = false;
        log("错误：" + msg);
        Listener l = listener;
        if (l != null && !stopped) {
            l.onError(msg);
        }
    }

    private void cleanup() {
        connected = false;
        running = false;
        closeQuietly();
        Listener l = listener;
        if (l != null) {
            l.onClosed();
        }
    }

    private void closeQuietly() {
        try {
            if (sslSocket != null) {
                sslSocket.close();
            }
        } catch (IOException ignored) {
            // 关闭阶段的异常无需上抛
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------------
    // 阶段 1：X.224 + RDP 协商
    // ------------------------------------------------------------------

    private void openSocket() throws IOException, RdpException {
        if (factory != null) {
            socket = factory.open(host, port);
            if (socket == null) {
                throw new RdpException("SocketFactory 返回了 null");
            }
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10000);
        }
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(30000);
        in = new PushbackInputStream(socket.getInputStream(), 16384);
        out = socket.getOutputStream();
        log("TCP 已连接 " + host + ":" + port);
    }

    private int x224Connect() throws IOException, RdpException {
        byte[] cr = new byte[]{
                0x03, 0x00, 0x00, 0x13,
                0x0E, (byte) 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x08, 0x00,
                0x03, 0x00, 0x00, 0x00};
        out.write(cr);
        out.flush();

        byte[] head = Bytes.readFully(in, 11);
        bytesIn += 11;
        if (head[0] != 0x03) {
            throw new RdpException("X.224 响应无效（首字节 0x" + Integer.toHexString(head[0] & 0xFF) + "）");
        }
        int len = Bytes.u16be(head, 2);
        if (len < 11 || len > 4096) {
            throw new RdpException("X.224 响应长度非法：" + len);
        }
        byte[] body = len > 11 ? Bytes.readFully(in, len - 11) : new byte[0];
        bytesIn += body.length;

        int selected = PROTOCOL_RDP;
        if (body.length >= 8 && (body[0] & 0xFF) == 0x02) {
            selected = Bytes.s32(body, 4);
            log("[1/7] X.224 ok, selectedProtocol=" + protocolName(selected));
        } else if (body.length >= 8 && (body[0] & 0xFF) == 0x03) {
            int failCode = Bytes.s32(body, 4);
            throw new RdpException("服务器拒绝 RDP 协商（failureCode=0x"
                    + Integer.toHexString(failCode) + "）：" + negotiationFailure(failCode));
        } else {
            log("[1/7] X.224 ok, selectedProtocol=0（标准 RDP 安全，无协商响应）");
        }
        return selected;
    }

    private static String protocolName(int p) {
        switch (p) {
            case PROTOCOL_RDP:
                return "0(标准RDP加密)";
            case PROTOCOL_SSL:
                return "1(SSL/TLS)";
            case PROTOCOL_HYBRID:
                return "2(HYBRID/NLA)";
            case PROTOCOL_HYBRID_EX:
                return "8(HYBRID_EX)";
            default:
                return String.valueOf(p);
        }
    }

    private static String negotiationFailure(int code) {
        switch (code) {
            case 1:
                return "SSL_REQUIRED_BY_SERVER（服务器要求 SSL）";
            case 2:
                return "SSL_NOT_ALLOWED_BY_SERVER（服务器不允许 SSL）";
            case 3:
                return "SSL_CERT_NOT_ON_SERVER（服务器缺少证书）";
            case 4:
                return "INCONSISTENT_FLAGS（协议标志冲突）";
            case 5:
                return "HYBRID_REQUIRED_BY_SERVER（服务器要求 NLA）";
            case 6:
                return "SSL_WITH_HYBRID_REQUIRED_BY_SERVER";
            default:
                return "未知";
        }
    }

    // ------------------------------------------------------------------
    // 阶段 2：TLS
    // ------------------------------------------------------------------

    private void wrapTls(int selected) throws RdpException, IOException {
        if (selected == PROTOCOL_RDP) {
            return;
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            SSLSocket s = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(socket, host, port, true);
            s.setUseClientMode(true);
            List<String> protos = new ArrayList<String>();
            for (String p : s.getSupportedProtocols()) {
                if ("TLSv1.2".equals(p) || "TLSv1.3".equals(p)) {
                    protos.add(p);
                }
            }
            if (!protos.isEmpty()) {
                s.setEnabledProtocols(protos.toArray(new String[0]));
            }
            if (!isIpAddress(host)) {
                try {
                    javax.net.ssl.SSLParameters params = s.getSSLParameters();
                    params.setServerNames(java.util.Collections.singletonList(
                            new javax.net.ssl.SNIHostName(host)));
                    s.setSSLParameters(params);
                } catch (RuntimeException ignored) {
                    // SNI 失败不影响连接
                }
            }
            s.setSoTimeout(30000);
            s.startHandshake();
            sslSocket = s;
            in = new PushbackInputStream(s.getInputStream(), 16384);
            out = s.getOutputStream();

            String cipher = s.getSession().getCipherSuite();
            log("[2/7] TLS ok " + s.getSession().getProtocol() + " / " + cipher);
        } catch (IOException e) {
            throw new RdpException("TLS 握手失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RdpException("TLS 初始化失败：" + e.getMessage(), e);
        }
    }

    private static boolean isIpAddress(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.' && c != ':') {
                return false;
            }
        }
        return true;
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 自签证书也必须放行（RDP 服务器的证书通常是自签的）
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 同上
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    // ------------------------------------------------------------------
    // 阶段 3：CredSSP
    // ------------------------------------------------------------------

    private void doCredSsp() throws RdpException, IOException {
        X509Certificate cert = null;
        try {
            java.security.cert.Certificate[] chain = sslSocket.getSession().getPeerCertificates();
            if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                cert = (X509Certificate) chain[0];
            }
        } catch (Exception e) {
            throw new RdpException("无法读取服务器证书：" + e.getMessage(), e);
        }
        CredSsp credssp = new CredSsp(sslSocket, in, in, out, cert, new Log() {
            @Override
            public void log(String message) {
                RdpSession.this.log(message);
            }
        });
        credssp.authenticate(domain, user, password);
        sslSocket.setSoTimeout(30000);
        log("[3/7] CredSSP ok（用户 " + (domain.isEmpty() ? "" : domain + "\\") + user + "）");
    }

    // ------------------------------------------------------------------
    // 阶段 4：MCS / GCC
    // ------------------------------------------------------------------

    private void doMcs() throws RdpException, IOException {
        mcs = new McS(in, out, new Log() {
            @Override
            public void log(String message) {
                RdpSession.this.log("  " + message);
            }
        });
        byte[] gcc = mcs.connect(reqWidth, reqHeight, 24, currentSelectedProtocol,
                clientHostName());

        byte[] scCore = Gcc.findServerBlock(gcc, Gcc.SC_CORE);
        if (scCore != null && scCore.length >= 4) {
            log("  服务器版本 0x" + Long.toHexString(Bytes.u32(scCore, 0)));
        }
        byte[] scSecurity = Gcc.findServerBlock(gcc, Gcc.SC_SECURITY);
        if (scSecurity != null && scSecurity.length >= 8) {
            log("  服务器加密：method=" + Bytes.u32(scSecurity, 0)
                    + " level=" + Bytes.u32(scSecurity, 4));
        }
        List<Integer> vcIds = new ArrayList<Integer>();
        byte[] scNet = Gcc.findServerBlock(gcc, Gcc.SC_NET);
        if (scNet != null && scNet.length >= 4) {
            // TS_UD_SC_NET: MCSChannelId(2) + channelCount(2) + channelId[count]
            int ioChannelId = Bytes.u16(scNet, 0);
            int count = Bytes.u16(scNet, 2);
            for (int i = 0; i < count && 4 + i * 2 + 2 <= scNet.length; i++) {
                vcIds.add(Bytes.u16(scNet, 4 + i * 2));
            }
            log("  服务器 IO 通道=" + ioChannelId + "，虚拟通道 " + count + " 个");
        }
        int[] ids = new int[vcIds.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = vcIds.get(i);
        }

        mcs.erectDomain();
        mcs.attachUser();
        mcs.joinChannels(ids);
        connected = true;
        log("[4/7] MCS ok, userId=" + mcs.userId + "，虚拟通道 " + ids.length + " 个");
    }

    private int currentSelectedProtocol;

    private String clientHostName() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isEmpty()) {
            h = "ANDROID";
        }
        return h.length() > 15 ? h.substring(0, 15) : h;
    }

    // ------------------------------------------------------------------
    // 阶段 5：Client Info
    // ------------------------------------------------------------------

    private void sendClientInfo() throws IOException {
        String local = "127.0.0.1";
        try {
            if (socket.getLocalAddress() != null) {
                local = socket.getLocalAddress().getHostAddress();
            }
        } catch (RuntimeException ignored) {
            // 拿不到就退回回环地址
        }
        byte[] info = Gcc.clientInfo(domain, user, password, local,
                desktopWidth, desktopHeight);
        // MCS Send Data 的 userData 直接是 securityHeader + TS_INFO_PACKET，不再套 TPKT/X.224
        Out frame = new Out(info.length + 8);
        frame.u16(SEC_INFO_PKT).u16(0);     // securityHeader: flags = SEC_INFO_PKT
        frame.bytes(info);
        byte[] data = frame.toArray();
        mcs.sendData(data);
        bytesOut += data.length;
        log("  Client Info 载荷 " + data.length + "B hex="
                + Bytes.hex(data, 0, Math.min(data.length, 96)));
    }

    // ------------------------------------------------------------------
    // 接收循环
    // ------------------------------------------------------------------

    private void readLoop() throws IOException, RdpException {
        while (running && !stopped) {
            int b0;
            try {
                b0 = in.read();
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (b0 < 0) {
                if (!stopped) {
                    log("服务器关闭了连接");
                }
                return;
            }
            if (b0 == 0x03) {
                readSlowPdu();
            } else {
                if (System.getProperty("rdp.rawlog") != null) {
                    log("收到 FastPath，首字节=0x" + Integer.toHexString(b0));
                }
                readFastPathPdu(b0);
            }
        }
    }

    private void readSlowPdu() throws IOException, RdpException {
        byte[] lenB = Bytes.readFully(in, 3);
        int len = Bytes.u16be(lenB, 1);
        if (len < 11 || len > 65535) {
            throw new RdpException("慢路径 PDU 长度非法：" + len);
        }
        byte[] frame = new byte[len];
        frame[0] = 0x03;
        frame[1] = lenB[0];
        frame[2] = lenB[1];
        frame[3] = lenB[2];
        byte[] rest = Bytes.readFully(in, len - 4);
        System.arraycopy(rest, 0, frame, 4, rest.length);
        bytesIn += len;
        if (System.getProperty("rdp.rawlog") != null) {
            log("收到慢路径帧 " + len + "B hex="
                    + Bytes.hex(frame, 0, Math.min(40, frame.length)));
        }
        handleSlowPdu(frame);
    }

    private void handleSlowPdu(byte[] f) throws RdpException, IOException {
        if (f.length < 8 || f[4] != 0x02 || f[5] != (byte) 0xF0 || f[6] != (byte) 0x80) {
            return;
        }
        int p = 7;
        int opcode = f[p++] & 0xFF;
        int appid = opcode >> 2;
        if (appid == 8) {
            log("收到 MCS Disconnect Provider Ultimatum");
            return;
        }
        if (appid != 26) {           // MCS Send Data Indication
            return;
        }
        if (p + 6 > f.length) {
            return;
        }
        p += 2;                      // initiator (userId - 1001)
        int channelId = Bytes.u16be(f, p);
        p += 2;
        p += 1;                      // dataPriority / segmentation
        int userDataLen = f[p++] & 0xFF;
        if ((userDataLen & 0x80) != 0) {
            if (p >= f.length) {
                return;
            }
            userDataLen = ((userDataLen & 0x7F) << 8) | (f[p++] & 0xFF);
        }
        if (p + 8 > f.length) {
            return;
        }
        // TLS 下只有许可证 PDU 还带 securityHeader（flags = SEC_LICENSE_PKT 0x0080），
        // 其余慢路径 PDU 直接从 shareControlHeader 开始。
        if (Bytes.u16(f, p) == 0x0080) {
            byte[] body = Bytes.slice(f, p + 4, f.length - p - 4);
            handleLicenseBody(body);
            return;
        }
        int pduType = Bytes.u16(f, p + 2) & 0x0F;   // shareControlHeader.pduType
        int pduSource = Bytes.u16(f, p + 4);
        if ((System.getProperty("rdp.dump") != null)) {
            log("  慢路径 PDU " + f.length + "B type=" + pduType + " hex="
                    + Bytes.hex(f, 0, Math.min(40, f.length)));
        }
        p += 6;                                     // 跳过 shareControlHeader
        if (pduType == PDU_TYPE_DATA) {
            if (p + 12 > f.length) {
                return;
            }
            int pduType2 = f[p + 8] & 0xFF;          // shareDataHeader.pduType2
            int payloadOff = p + 12;
            handleDataPdu(pduType2, f, payloadOff, f.length - payloadOff);
        } else if (pduType == PDU_TYPE_DEMAND_ACTIVE) {
            handleDemandActive(f, p, pduSource);
        } else if (pduType == PDU_TYPE_DEACTIVATE_ALL) {
            log("收到 Deactivate All（通道 " + channelId + "）");
        }
    }

    /** MS-RDPBCGR 2.2.5.1.1 的 ERRINFO 常量，便于把日志翻译成人话。 */
    private static String describeErrInfo(int code) {
        switch (code) {
            case 0x0000: return "（无错误）";
            case 0x0001: return "（RPC 发起断开）";
            case 0x0002: return "（RPC 发起注销）";
            case 0x0003: return "（空闲超时）";
            case 0x0004: return "（登录超时）";
            case 0x0005: return "（被其他连接抢占）";
            case 0x0006: return "（服务器内存不足）";
            case 0x0007: return "（服务器拒绝连接）";
            case 0x0009: return "（权限不足）";
            case 0x000A: return "（需要重新输入凭据）";
            case 0x000B: return "（用户主动断开）";
            case 0x000C: return "（用户注销）";
            case 0x000D: return "（驱动栈异常关闭）";
            case 0x000E: return "（解密失败）";
            case 0x000F: return "（被服务器断开）";
            case 0x0010: return "（会话被接管）";
            case 0x0011: return "（会话被其他连接接管）";
            default: return "（未识别）";
        }
    }

    private void handleDataPdu(int type, byte[] f, int off, int len) throws RdpException, IOException {        switch (type) {
            case DATA_TYPE_UPDATE:
                if (len >= 2) {
                    int updateType = Bytes.u16(f, off);
                    if (updateType == 0x0001) {
                        FastPath.process(FastPath.UPDATETYPE_BITMAP, f, off, len, fpHost, new Log() {
                            @Override
                            public void log(String m) {
                                RdpSession.this.log(m);
                            }
                        });
                    } else if (updateType == 0x0003) {
                        log("收到慢路径 Synchronize 更新");
                    }
                }
                break;
            case DATA_TYPE_LICENSE:
                handleLicenseBody(Bytes.slice(f, off, len));
                break;
            case DATA_TYPE_SYNCHRONIZE:
                log("收到服务器 Synchronize");
                if (System.getProperty("rdp.passive") != null) {
                    try {
                        sendSynchronize();
                    } catch (IOException e) {
                        log("回 Synchronize 失败：" + e.getMessage());
                    }
                }
                break;
            case DATA_TYPE_CONTROL: {
                int action = len >= 2 ? Bytes.u16(f, off) : -1;
                log("收到 Control PDU action=" + action
                        + "（1=申请控权 2=已授权 4=协作）");
                try {
                    if (action == 4 && !fontSent
                            && System.getProperty("rdp.freely") != null) {
                        fontSent = true;
                        sendFontList();
                    } else if (action == 4 && !ctrlReqSent) {
                        ctrlReqSent = true;
                        int clen = Integer.parseInt(
                                System.getProperty("rdp.ctllen", "8"));
                        if (!"0".equals(System.getProperty("rdp.echocop", "1"))) {
                            sendControlPdu(4, clen);     // 回 COOPERATE
                        }
                        sendControlPdu(1, clen);         // 再请求控制权
                        log("已回 COOPERATE + REQUEST_CONTROL");
                    } else if (action == 2 && !fontSent) {
                        fontSent = true;
                        sendFontList();
                    }
                } catch (IOException e) {
                    log("激活应答发送失败：" + e.getMessage());
                }
                break;
            }
            case DATA_TYPE_SET_ERROR_INFO:
                if (len >= 4) {
                    int code = Bytes.u16(f, off);
                    int state = Bytes.u16(f, off + 2);
                    log("服务器错误信息 PDU：code=0x" + Integer.toHexString(code)
                            + "(" + code + ") state=0x" + Integer.toHexString(state)
                            + "(" + state + ")" + describeErrInfo(code));
                }
                break;
            case DATA_TYPE_STATUS_INFO:
                if (len >= 4) {
                    log("服务器状态信息 PDU：code=" + Bytes.u32(f, off));
                }
                break;
            default:
                break;
        }
    }

    private void handleDemandActive(byte[] f, int off, int source) throws IOException, RdpException {
        // off 指向 Confirm Active / Demand Active 的 PDU 体：
        // shareId(4) + lengthSourceDescriptor(2) + lengthCombinedCapabilities(2) + ...
        if (off + 8 > f.length) {
            return;
        }
        int sid = (int) Bytes.u32(f, off);
        shareId = sid;
        serverChannelId = source;
        int srcDescLen = Bytes.u16(f, off + 4);
        int capsLen = Bytes.u16(f, off + 6);
        int p = off + 8 + srcDescLen;
        if (p + 4 > f.length) {
            return;
        }
        int capCount = Bytes.u16(f, p);
        p += 4;
        log("  Demand Active 头部 hex=" + Bytes.hex(f, off, Math.min(48, f.length - off))
                + " srcDescLen=" + srcDescLen + " capsLen=" + capsLen);
        log("  caps hex=" + Bytes.hex(f, p, Math.min(capsLen, f.length - p)));

        int width = desktopWidth;
        int height = desktopHeight;
        serverCaps.clear();
        serverCapLens.clear();
        for (int i = 0; i < capCount && p + 4 <= f.length; i++) {
            int capType = Bytes.u16(f, p);
            int capLen = Bytes.u16(f, p + 2);
            if (capLen < 4 || p + capLen > f.length) {
                log("  能力集#" + i + " 越界：type=" + capType + " len=" + capLen);
                break;
            }
            log("  能力集#" + i + " type=" + capType + " len=" + capLen);
            serverCaps.add(capType);
            serverCapLens.add(capLen);
            if (capType == 2 && capLen >= 16) {
                // TS_BITMAP_CAPABILITYSET（CAPSETTYPE_BITMAP = 2）：
                // type(2) len(2) preferredBPP(2) recv1BPP(2) recv4BPP(2) recv8BPP(2)
                // desktopWidth(2) desktopHeight(2)
                width = Bytes.u16(f, p + 12);
                height = Bytes.u16(f, p + 14);
                serverBpp = Bytes.u16(f, p + 4);
            }
            p += capLen;
        }
        log("收到 Demand Active：shareId=0x" + Integer.toHexString(sid)
                + " 通道=" + source + " 桌面=" + width + "×" + height
                + " 能力集=" + capCount + " (" + capsLen + "B)");

        if (width > 0 && height > 0) {
            desktopWidth = width;
            desktopHeight = height;
            allocateFramebuffer(width, height);
        }
        if (System.getProperty("rdp.noconfirm") != null) {
            log("诊断：收到 Demand Active 后不发确认包，静默观察");
            return;
        }
        sendConfirmActive();
        if (System.getProperty("rdp.noact") == null) {
            sendActivationSequence();
        }
        Listener l = listener;
        if (l != null) {
            l.onConnected(desktopWidth, desktopHeight);
        }
    }

    /** 解析 TS_LICENSE_PDU 主体（securityHeader 之后）。 */
    private void handleLicenseBody(byte[] body) throws IOException {
        if (body.length < 4) {
            return;
        }
        int msgType = body[0] & 0xFF;
        int flags = body[1] & 0xFF;
        int msgSize = Bytes.u16(body, 2);
        log("收到许可证 PDU：msgType=0x" + Integer.toHexString(msgType)
                + " flags=0x" + Integer.toHexString(flags) + " size=" + msgSize);
        if (msgType == 0xFF) {                    // ERROR_ALERT
            if (body.length >= 8) {
                long code = Bytes.u32(body, 4);
                log("  许可证错误码=0x" + Long.toHexString(code)
                        + (code == 0x07 ? "（STATUS_VALID_CLIENT，无需许可证）" : ""));
            }
        }
        // ERROR_ALERT / STATUS_VALID_CLIENT 时服务器会直接继续，不需要回复
    }

    // ------------------------------------------------------------------
    // 能力交换 / 激活
    // ------------------------------------------------------------------

    private void sendConfirmActive() throws IOException {
        int[] types = new int[serverCaps.size()];
        int[] lens = new int[serverCaps.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = serverCaps.get(i);
            lens[i] = serverCapLens.get(i);
        }
        int source = mcs.userId;
        // originatorId 必须是「服务器通道 ID」（Demand Active 的 pduSource，通常 1002 = 0x03EA），
        // 填成客户端自己的用户通道会被服务器判为 ERRINFO_CONFIRM_ACTIVE_HAS_WRONG_ORIGINATOR(0x10D5)。
        int originatorId = serverChannelId != 0 ? serverChannelId : 1002;
        String ps = System.getProperty("rdp.psrc");
        String og = System.getProperty("rdp.origin");
        if (ps != null) {
            source = Integer.parseInt(ps);
        }
        if (og != null) {
            originatorId = Integer.parseInt(og);
        }
        byte[] payload = Gcc.confirmActive(shareId, originatorId,
                desktopWidth, desktopHeight, types, lens);
        // 标准做法：TLS 下慢路径 PDU 不带 securityHeader（Client Info 是唯一例外）
        Out o = new Out(payload.length + 20);
        o.u16(6 + payload.length);        // shareControlHeader.length
        o.u16(PDU_TYPE_CONFIRM_ACTIVE | 0x0010);   // version 1 | CONFIRM_ACTIVE(3)
        o.u16(source);                    // pduSource
        o.bytes(payload);
        byte[] data = o.toArray();
        mcs.sendData(data);
        bytesOut += data.length;
        log("已发送 Confirm Active（" + data.length + "B）hex="
                + Bytes.hex(data, 0, Math.min(data.length, 64)));
    }

    private volatile boolean ctrlSent;
    private volatile boolean fontSent;
    private volatile boolean ctrlReqSent;

    private void sendActivationSequence() throws IOException {
        // [MS-RDPBCGR] 1.3.1.1 - 8：激活阶段的客户端 PDU 之间没有依赖关系，可以一次性
        // 批量发出。FreeRDP 的 rdp_client_connect_finalize 即连发四帧：
        //   Synchronize → Control(COOPERATE) → Control(REQUEST_CONTROL) → Font List
        // 分步「等一个回一个」会让双方互等，状态机停在 COOPERATE 之后再无进展。
        int clen = Integer.parseInt(System.getProperty("rdp.ctllen", "8"));
        if (System.getProperty("rdp.passive") != null) {
            log("被动模式：等待服务器 Synchronize，逐帧应答");
            return;
        }
        int gap = Integer.parseInt(System.getProperty("rdp.gap", "300"));
        sendSynchronize();
        pause(gap);
        sendControlPdu(4, clen);            // COOPERATE
        pause(gap);
        sendControlPdu(1, clen);            // REQUEST_CONTROL
        pause(gap);
        // 声明了 BitmapCacheV2 就必须发 Persistent Key List（MS-RDPBCGR 2.2.1.17），
        // 否则服务器会停在激活阶段等这一帧。
        if (System.getProperty("rdp.nokeylist") == null) {
            Out keyList = new Out(8);
            keyList.u16(0);                 // numEntries
            keyList.u16(0);                 // pad2Octets
            sendDataPdu(DATA_TYPE_PERSISTENT_KEY_LIST, keyList.toArray());
        }
        pause(gap);
        sendFontList();                     // FONTLIST_FIRST | FONTLIST_LAST
        ctrlSent = true;
        ctrlReqSent = true;
        fontSent = true;
        log("已逐帧发送 Synchronize / Cooperate / RequestControl / FontList，间隔=" + gap + "ms");
    }

    private static void pause(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 回应服务器的 Synchronize：messageType = SYNCMSGTYPE_SYNC(1)，targetUser = 0。 */
    private void sendSynchronize() throws IOException {
        Out sync = new Out(8);
        sync.u16(1);        // messageType = SYNCMSGTYPE_SYNC
        sync.u16(0);        // targetUser
        sendDataPdu(DATA_TYPE_SYNCHRONIZE, sync.toArray());
        log("已回复 Synchronize");
    }

    /** 服务器回 Control(COOPERATE) 后：回 Cooperate + 申请控制权。 */
    private void sendControlCooperate() throws IOException {
        int clen = Integer.parseInt(System.getProperty("rdp.ctllen", "6"));
        boolean cop = !"0".equals(System.getProperty("rdp.ctlcop", "1"));
        if (cop) {
            sendControlPdu(4, clen);
        }
        if (!"0".equals(System.getProperty("rdp.ctlreq", "0"))) {
            sendControlPdu(1, clen);
        }
        log("已回复 Cooperate + Request Control");
        // 部分服务器不单独回 Granted Control，直接进入字体映射阶段；
        // 这里紧跟一条 Font List，两边都不落空。
    }

    private void sendControlPdu(int action, int clen) throws IOException {
        Out c = new Out(clen + 4);
        c.u16(action);
        c.u16(0);                // grantId
        if (clen >= 8) {
            c.u32(0);            // controlId（4 字节版本）
        } else {
            c.u16(0);            // controlId（2 字节版本）
        }
        sendDataPdu(DATA_TYPE_CONTROL, c.toArray());
    }

    /** 服务器授予控制权后：发字体列表。TS_FONT_LIST_PDU 固定 8 字节。 */
    private void sendFontList() throws IOException {
        Out fonts = new Out(16);
        fonts.u16(0);            // numberFonts
        fonts.u16(0);            // totalNumFonts
        fonts.u16(0x0003);       // listFlags = FONTLIST_FIRST | FONTLIST_LAST
        fonts.u16(50);           // entrySize（协议规定值）
        sendDataPdu(DATA_TYPE_FONT_LIST, fonts.toArray());
        log("已发送 Font List，等待 Font Map 与画面");
    }

    /** 发送一个 shareDataHeader 型慢路径 PDU（TLS 下无 securityHeader）。 */
    private void sendDataPdu(int pduType2, byte[] payload) throws IOException {
        boolean sec = !"0".equals(System.getProperty("rdp.sechdr", "1"));
        int extra = sec ? 4 : 0;
        Out o = new Out(payload.length + 40 + extra);
        int totalLen = 6 + extra + 12 + payload.length;
        o.u16(totalLen);                  // shareControlHeader.length
        o.u16(PDU_TYPE_DATA | 0x0010);    // version 1 | DATA(7)
        o.u16(Integer.getInteger("rdp.datasrc", mcs.userId));  // pduSource
        if (sec) {
            o.u32(0);                     // securityHeader：flags=0, flagsHi=0
        }
        // shareDataHeader（12B）：shareId(4) pad1(1) streamId(1) uncompressedLength(2)
        //                        pduType2(1) compressedType(1) compressedLength(2)
        o.u32(shareId);
        o.u8(0);                          // pad1
        o.u8(1);                          // streamId = STREAM_LOW
        // 服务器实测：uncompressedLength 填的是「整条 PDU 的总长度」（含 shareControlHeader）
        o.u16(totalLen + Integer.parseInt(System.getProperty("rdp.uncomp", "0")));
        o.u8(pduType2);
        o.u8(0);                          // compressedType
        o.u16(0);                         // compressedLength
        o.bytes(payload);
        byte[] data = o.toArray();
        mcs.sendData(data);
        bytesOut += data.length;
    }

    private static final int MCS_GLOBAL_CHANNEL_ID = 1003;

    // ------------------------------------------------------------------
    // 阶段 6/7：FastPath
    // ------------------------------------------------------------------

    private void readFastPathPdu(int header) throws IOException, RdpException {
        int len1 = in.read();
        if (len1 < 0) {
            return;
        }
        int total;
        int consumed;
        if ((len1 & 0x80) != 0) {
            int len2 = in.read();
            if (len2 < 0) {
                return;
            }
            total = ((len1 & 0x7F) << 8) | len2;
            consumed = 3;
        } else {
            total = len1;
            consumed = 2;
        }
        int remain = total - consumed;
        if (remain < 3) {
            return;
        }
        byte[] body = Bytes.readFully(in, remain);
        bytesIn += total;

        int updateHeader = body[0] & 0xFF;
        int updateCode = updateHeader & 0x0F;
        int fragmentation = (updateHeader >> 4) & 0x03;
        int compressionBits = (updateHeader >> 6) & 0x03;
        int p = 1;
        if (compressionBits != 0) {
            if (p >= body.length) {
                return;
            }
            p++;                            // compressionFlags
        }
        if (p + 2 > body.length) {
            return;
        }
        int size = Bytes.u16(body, p);
        p += 2;
        int avail = body.length - p;
        int n = size > 0 && size <= avail ? size : avail;
        byte[] data = Bytes.slice(body, p, n);

        if ((System.getProperty("rdp.dump") != null)) {
            log("  FastPath code=" + updateCode + " frag=" + fragmentation
                    + " comp=" + compressionBits + " total=" + total + " n=" + n
                    + " hex=" + Bytes.hex(body, 0, Math.min(40, body.length)));
        }

        if (compressionBits != 0) {
            log("FastPath 更新使用 RDP 压缩（compression=" + compressionBits
                    + "，code=" + updateCode + "，" + n + "B），暂未解码");
            return;
        }

        if (fragmentation == 1) {           // FIRST
            fragmentBuffer.reset();
            appendFragment(data);
            return;
        }
        if (fragmentation == 2) {           // NEXT
            appendFragment(data);
            return;
        }
        if (fragmentation == 3) {           // LAST
            appendFragment(data);
            data = fragmentBuffer.toByteArray();
            fragmentBuffer.reset();
        }
        FastPath.process(updateCode, data, 0, data.length, fpHost, new Log() {
            @Override
            public void log(String m) {
                RdpSession.this.log(m);
            }
        });
    }

    private final java.io.ByteArrayOutputStream fragmentBuffer = new java.io.ByteArrayOutputStream();

    private void appendFragment(byte[] data) {
        fragmentBuffer.write(data, 0, data.length);
    }

    private final FastPath.Host fpHost = new FastPath.Host() {
        @Override
        public void onBitmap(int left, int top, int width, int height, int[] argb) {
            blit(left, top, width, height, argb);
        }

        @Override
        public void onPointerShape(int w, int h, int hotX, int hotY, int[] argb) {
            pointerShape = argb;
            pointerW = w;
            pointerH = h;
            pointerHotX = hotX;
            pointerHotY = hotY;
        }

        @Override
        public void onPointerPosition(int x, int y) {
            pointerX = x;
            pointerY = y;
            Listener l = listener;
            if (l != null) {
                l.onPointerUpdated(x, y);
            }
        }

        @Override
        public void onPointerHide() {
            pointerShape = new int[0];
        }

        @Override
        public void onSync() {
            // 无操作
        }
    };

    private void allocateFramebuffer(int w, int h) {
        synchronized (fbLock) {
            if (fbWidth == w && fbHeight == h && pixels.length == w * h) {
                return;
            }
            fbWidth = w;
            fbHeight = h;
            pixels = new int[w * h];
        }
    }

    private void blit(int left, int top, int w, int h, int[] argb) {
        synchronized (fbLock) {
            int fw = fbWidth;
            int fh = fbHeight;
            if (fw <= 0 || fh <= 0) {
                allocateFramebuffer(Math.max(desktopWidth, left + w),
                        Math.max(desktopHeight, top + h));
                fw = fbWidth;
                fh = fbHeight;
            }
            int[] fb = pixels;
            for (int y = 0; y < h; y++) {
                int dy = top + y;
                if (dy < 0 || dy >= fh) {
                    continue;
                }
                int srcRow = y * w;
                int dstRow = dy * fw;
                int copy = Math.min(w, fw - left);
                if (copy <= 0) {
                    continue;
                }
                System.arraycopy(argb, srcRow, fb, dstRow + left, copy);
            }
        }
        frames++;
        fpsFrames++;
        updateFps();
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            log("[6/7] 收到首帧 " + w + "×" + h + " @(" + left + "," + top + ")");
        }
        Listener l = listener;
        if (l != null) {
            l.onFramebufferUpdated(left, top, left + w - 1, top + h - 1);
        }
    }

    private void updateFps() {
        long now = System.currentTimeMillis();
        if (now - fpsWindowStart >= 1000) {
            fps = fpsFrames;
            fpsFrames = 0;
            fpsWindowStart = now;
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    public void sendMouseMove(int x, int y) {
        sendFastInput(FastPath.mouse(clamp(x), clamp(y), FastPath.PTRFLAGS_MOVE));
        pointerX = x;
        pointerY = y;
    }

    public void sendMouseButton(int x, int y, int button, boolean down) {
        int flags = FastPath.PTRFLAGS_MOVE;
        int btn;
        if (button == 2) {
            btn = FastPath.PTRFLAGS_BUTTON2;
        } else if (button == 3) {
            btn = FastPath.PTRFLAGS_BUTTON3;
        } else {
            btn = FastPath.PTRFLAGS_BUTTON1;
        }
        flags |= btn;
        if (down) {
            flags |= FastPath.PTRFLAGS_DOWN;
        }
        sendFastInput(FastPath.mouse(clamp(x), clamp(y), flags));
    }

    public void sendWheel(int x, int y, int delta) {
        int steps = Math.max(1, Math.abs(delta) / 120);
        int ptrFlags = FastPath.PTRFLAGS_MOVE | FastPath.PTRFLAGS_WHEEL;
        int amount = Math.min(0xFF, steps * 120);
        if (delta < 0) {
            ptrFlags |= FastPath.PTRFLAGS_WHEEL_NEGATIVE | amount;
        } else {
            ptrFlags |= amount;
        }
        sendFastInput(FastPath.mouse(clamp(x), clamp(y), ptrFlags));
    }

    public void sendKey(int scancode, boolean extended, boolean down) {
        sendFastInput(FastPath.key(scancode, extended, down));
    }

    public void sendUnicode(int codeUnit, boolean down) {
        sendFastInput(FastPath.unicode(codeUnit, down));
    }

    private int clamp(int v) {
        int max = Math.max(0, (fbWidth > 0 ? fbWidth : desktopWidth) - 1);
        return Math.max(0, Math.min(v, max));
    }

    private void sendFastInput(byte[] event) {
        if (out == null || !connected) {
            return;
        }
        try {
            synchronized (out) {
                out.write(event);
                out.flush();
            }
            bytesOut += event.length;
        } catch (IOException e) {
            fail("发送输入事件失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    public String statsText() {
        long now = System.currentTimeMillis();
        if (now - lastFpsValueTime < 900) {
            // 避免过于频繁地刷新
        } else {
            lastFpsValue = fps;
            lastFpsValueTime = now;
        }
        double mb = bytesIn / (1024.0 * 1024.0);
        int w = fbWidth > 0 ? fbWidth : desktopWidth;
        int h = fbHeight > 0 ? fbHeight : desktopHeight;
        return w + "×" + h + " · " + String.format(java.util.Locale.US, "%.1f MB", mb)
                + " · " + lastFpsValue + " 帧/秒";
    }

    private void log(String msg) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onLog(msg);
            } catch (RuntimeException ignored) {
                // 监听器异常不应影响协议栈
            }
        }
    }
}
