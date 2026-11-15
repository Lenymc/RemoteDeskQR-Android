package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.ProxySettings;
import com.qiao.remotedesk.model.ProxyType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/** 按配置创建连接器；同时集中放置各连接器共用的字节级 I/O 辅助（包内可见）。 */
public final class ProxyFactory {

    /** HTTP 头换行，用码点拼出，避免源码里出现转义字面量。 */
    static final String CRLF = new String(new char[] { 13, 10 });

    private ProxyFactory() {
    }

    public static ProxyConnector create(ProxySettings p) {
        ProxySettings s = p != null ? p : new ProxySettings();
        ProxyType t = s.type != null ? s.type : ProxyType.NONE;
        switch (t) {
            case HTTP_CONNECT:
                return new HttpConnectConnector(s);
            case SOCKS5:
                return new Socks5Connector(s);
            case SOCKS4:
                return new Socks4Connector(s);
            case NONE:
            default:
                return new DirectConnector(s);
        }
    }

    /** 返回中文错误说明，配置合法时返回 null。 */
    public static String validate(ProxySettings p) {
        if (p == null || p.type == null || p.type == ProxyType.NONE) {
            return null;
        }
        if (p.host == null || p.host.trim().isEmpty()) {
            return "请填写代理服务器地址";
        }
        if (p.port < 1 || p.port > 65535) {
            return "代理端口必须在 1~65535 之间";
        }
        return null;
    }

    // ---- 以下为各连接器共用的底层辅助，包内可见，不进公共 API ----

    static int clampTimeout(int seconds) {
        return seconds < 3 ? 3 : (seconds > 120 ? 120 : seconds);
    }

    static void ensureTarget(String host, int port) throws IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new ProxyException("目标主机不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new ProxyException("目标端口 " + port + " 非法");
        }
    }

    static String stripBrackets(String host) {
        String h = host == null ? "" : host.trim();
        if (h.length() > 1 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    /** IPv6 字面量要加方括号，其余直接 host:port。 */
    static String toAuthority(String host, int port) {
        String bare = stripBrackets(host);
        return bare.indexOf(':') >= 0 ? "[" + bare + "]:" + port : bare + ":" + port;
    }

    /** 建立到下一跳的 TCP：connect 超时、读超时、TcpNoDelay 一次设好。 */
    static Socket openSocket(String host, int port, int timeoutSeconds, String role) throws IOException {
        String bare = stripBrackets(host);
        ensureTarget(bare, port);
        int ms = clampTimeout(timeoutSeconds) * 1000;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(bare, port), ms);
            s.setSoTimeout(ms);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            if (e instanceof ProxyException) {
                throw e;
            }
            throw new ProxyException(role + " " + bare + ":" + port + " 连接失败：" + e.getMessage(), e);
        }
    }

    /** 握手成功后清掉读超时：RDP 是长连接，不能让空闲读超时把隧道掐断。 */
    static void clearReadTimeout(Socket s) {
        try {
            s.setSoTimeout(0);
        } catch (Throwable ignored) {
        }
    }

    /** 单字节读取，绝不多读一个字节（防止吞掉隧道首批数据）。 */
    static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new ProxyException("代理在对端返回完整数据前关闭了连接");
        }
        return b;
    }

    static void readExactly(InputStream in, byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            buf[off + i] = (byte) readByte(in);
        }
    }

    static byte[] readBytes(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        readExactly(in, b, 0, len);
        return b;
    }

    static void writeAll(OutputStream out, byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    /** 逐字节读到响应头的 CRLF CRLF 为止，不用任何缓冲预读。 */
    static String readHttpHead(InputStream in) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int count = 0;
        while (true) {
            int b = readByte(in);
            if (count >= buf.length) {
                throw new ProxyException("代理响应头超过 64 KB，已中止（不像是合法的 HTTP 代理）");
            }
            buf[count++] = (byte) b;
            if (count >= 4
                    && buf[count - 4] == 13 && buf[count - 3] == 10
                    && buf[count - 2] == 13 && buf[count - 1] == 10) {
                return new String(buf, 0, count, "ISO-8859-1");
            }
        }
    }

    /** 只识别 IP 字面量并返回地址；域名一律返回 null 且不做 DNS 查询。 */
    static InetAddress parseIpLiteral(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim();
        if (h.isEmpty()) {
            return null;
        }
        String[] parts = h.split("[.]", -1);
        if (parts.length == 4) {
            boolean numeric = true;
            for (String p : parts) {
                if (p.isEmpty()) {
                    numeric = false;
                    break;
                }
                int v = 0;
                for (int i = 0; i < p.length(); i++) {
                    char c = p.charAt(i);
                    if (c < '0' || c > '9') {
                        numeric = false;
                        break;
                    }
                    v = v * 10 + (c - '0');
                    if (v > 255) {
                        numeric = false;
                        break;
                    }
                }
                if (!numeric) {
                    break;
                }
            }
            if (numeric) {
                try {
                    return InetAddress.getByName(h);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        if (h.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(h);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static String hex2(int b) {
        String s = Integer.toHexString(b & 0xFF).toUpperCase(Locale.ROOT);
        return s.length() == 1 ? "0" + s : s;
    }
}
