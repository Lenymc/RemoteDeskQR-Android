import com.qiao.remotedesk.model.ProxySettings;
import com.qiao.remotedesk.model.ProxyType;
import com.qiao.remotedesk.net.ConnectivityTester;
import com.qiao.remotedesk.net.DirectConnector;
import com.qiao.remotedesk.net.HttpConnectConnector;
import com.qiao.remotedesk.net.ProxyConnection;
import com.qiao.remotedesk.net.ProxyException;
import com.qiao.remotedesk.net.ProxyFactory;
import com.qiao.remotedesk.net.Socks4Connector;
import com.qiao.remotedesk.net.Socks5Connector;
import com.qiao.remotedesk.net.TunnelServer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** 纯 JVM 协议自测：mock 回显服务器 + HTTP / SOCKS5 / SOCKS4a 代理，验证握手字节与隧道转发。 */
public class NetSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        EchoServer echo = new EchoServer();
        echo.start();
        HttpProxy http = new HttpProxy(echo.port);
        Socks5Proxy socks5 = new Socks5Proxy(echo.port);
        Socks4Proxy socks4 = new Socks4Proxy(echo.port);
        http.start();
        socks5.start();
        socks4.start();

        try {
            ProxySettings direct = settings(ProxyType.NONE, "127.0.0.1", 1);
            ProxySettings hp = settings(ProxyType.HTTP_CONNECT, "127.0.0.1", http.port);
            hp.userName = "alice";
            hp.password = "s3cret";
            ProxySettings s5 = settings(ProxyType.SOCKS5, "127.0.0.1", socks5.port);
            ProxySettings s4 = settings(ProxyType.SOCKS4, "127.0.0.1", socks4.port);

            check("直连通道", new Case() {
                public void run() throws Exception {
                    ProxyConnection c = new DirectConnector(settings(ProxyType.NONE, "", 0))
                            .connect("127.0.0.1", echo.port);
                    assertEcho(c, "PING");
                    c.close();
                }
            });

            check("HTTP CONNECT 请求行 + Basic 认证", new Case() {
                public void run() throws Exception {
                    ProxyConnection c = new HttpConnectConnector(hp)
                            .connect("example.internal", echo.port);
                    if (!http.lastRequest.startsWith("CONNECT example.internal:" + echo.port)) {
                        throw new AssertionError("CONNECT 行不对：" + http.lastRequest);
                    }
                    String expected = "Basic " + java.util.Base64.getEncoder()
                            .encodeToString("alice:s3cret".getBytes("UTF-8"));
                    if (!http.lastRequest.contains(expected)) {
                        throw new AssertionError("缺 Basic 认证头：" + http.lastRequest);
                    }
                    assertEcho(c, "HELLO");
                    c.close();
                }
            });

            check("HTTP 407 中文诊断", new Case() {
                public void run() throws Exception {
                    http.code = 407;
                    try {
                        ProxyConnection c = new HttpConnectConnector(hp)
                                .connect("example.internal", echo.port);
                        c.close();
                        throw new AssertionError("407 应当失败");
                    } catch (ProxyException e) {
                        String m = String.valueOf(e.getMessage());
                        if (!m.contains("407") && !m.contains("认证")) {
                            throw new AssertionError("诊断不含 407/认证：" + m);
                        }
                    } finally {
                        http.code = 200;
                    }
                }
            });

            check("SOCKS5 无认证 + 远端域名解析 (ATYP=0x03)", new Case() {
                public void run() throws Exception {
                    socks5.requireAuth = false;
                    ProxyConnection c = new Socks5Connector(s5)
                            .connect("example.internal", 3389);
                    if (socks5.lastAtyp != 3 || !"example.internal".equals(socks5.lastDomain)) {
                        throw new AssertionError("ATYP/域名不对：atyp=" + socks5.lastAtyp
                                + " domain=" + socks5.lastDomain);
                    }
                    assertEcho(c, "S5");
                    c.close();
                }
            });

            check("SOCKS5 RFC1929 用户名密码子协商", new Case() {
                public void run() throws Exception {
                    socks5.requireAuth = true;
                    s5.userName = "bob";
                    s5.password = "pw";
                    try {
                        ProxyConnection c = new Socks5Connector(s5)
                                .connect("10.20.30.40", 3389);
                        if (!"bob:pw".equals(socks5.lastAuth)) {
                            throw new AssertionError("认证子协商不对：" + socks5.lastAuth);
                        }
                        assertEcho(c, "AUTH");
                        c.close();
                    } finally {
                        socks5.requireAuth = false;
                        s5.userName = "";
                        s5.password = "";
                    }
                }
            });

            check("SOCKS4a 域名 (0.0.0.1 + 域名)", new Case() {
                public void run() throws Exception {
                    ProxyConnection c = new Socks4Connector(s4)
                            .connect("example.internal", 3389);
                    if (!"example.internal".equals(socks4.lastDomain)) {
                        throw new AssertionError("4a 域名不对：" + socks4.lastRequest
                                + " / " + socks4.lastDomain);
                    }
                    assertEcho(c, "S4");
                    c.close();
                }
            });

            check("本地隧道全双工转发 + 字节统计", new Case() {
                public void run() throws Exception {
                    TunnelServer t = TunnelServer.start(ProxyFactory.create(hp),
                            "example.internal", echo.port, "127.0.0.1");
                    try {
                        Socket client = new Socket("127.0.0.1", t.localPort());
                        try {
                            client.getOutputStream().write("TUNNEL".getBytes("UTF-8"));
                            client.getOutputStream().flush();
                            byte[] buf = new byte[6];
                            readFully(client.getInputStream(), buf);
                            if (!"TUNNEL".equals(new String(buf, "UTF-8"))) {
                                throw new AssertionError("隧道回显不对");
                            }
                        } finally {
                            client.close();
                        }
                        Thread.sleep(250);
                        if (t.bytesToTarget() < 6 || t.bytesFromTarget() < 6
                                || t.totalConnections() < 1) {
                            throw new AssertionError("统计不对：to=" + t.bytesToTarget()
                                    + " from=" + t.bytesFromTarget()
                                    + " conns=" + t.totalConnections());
                        }
                    } finally {
                        t.stop();
                    }
                }
            });

            check("连通性测试分段诊断", new Case() {
                public void run() throws Exception {
                    ConnectivityTester.Result r = ConnectivityTester.test(hp,
                            "example.internal", echo.port);
                    if (!r.success || r.steps.size() < 2 || r.connectMs <= 0) {
                        throw new AssertionError("探测结果不对：" + r.message
                                + " steps=" + r.steps.size() + " ms=" + r.connectMs);
                    }
                }
            });
        } finally {
            http.stopServer();
            socks5.stopServer();
            socks4.stopServer();
            echo.stopServer();
        }
        System.out.println("SELFTEST_RESULT passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void check(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + " -> " + t);
        }
    }

    private static ProxySettings settings(ProxyType type, String host, int port) {
        ProxySettings p = new ProxySettings();
        p.type = type;
        p.host = host;
        p.port = port;
        p.connectTimeoutSeconds = 5;
        p.remoteDns = true;
        return p;
    }

    private static void assertEcho(ProxyConnection c, String payload) throws Exception {
        c.out.write(payload.getBytes("UTF-8"));
        c.out.flush();
        byte[] buf = new byte[payload.getBytes("UTF-8").length];
        readFully(c.in, buf);
        if (!payload.equals(new String(buf, "UTF-8"))) {
            throw new AssertionError("回显不一致，收到：" + new String(buf, "UTF-8"));
        }
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("流提前结束");
            }
            off += n;
        }
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int matched = 0;
        final byte[] end = {'\r', '\n', '\r', '\n'};
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            bos.write(b);
            if (b == end[matched]) {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        return new String(bos.toByteArray(), "ISO-8859-1");
    }

    static Thread pump(final Socket from, final Socket to) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        to.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        t.setDaemon(true);
        return t;
    }

    static void bridge(Socket a, Socket b) {
        pump(a, b).start();
        pump(b, a).start();
    }

    static class EchoServer extends Thread {
        final ServerSocket ss;
        final int port;

        EchoServer() throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            port = ss.getLocalPort();
            setDaemon(true);
        }

        public void run() {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    Thread t = new Thread(new Runnable() {
                        public void run() {
                            try {
                                InputStream in = s.getInputStream();
                                OutputStream out = s.getOutputStream();
                                byte[] buf = new byte[512];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    out.write(buf, 0, n);
                                    out.flush();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                } catch (Exception e) {
                    return;
                }
            }
        }

        void stopServer() {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }
    }

    abstract static class MockProxy extends Thread {
        final ServerSocket ss;
        final int port;
        final int echoPort;
        volatile String lastRequest = "";
        volatile String lastAuth = "";
        volatile String lastDomain = "";
        volatile int lastAtyp = -1;

        MockProxy(int echoPort) throws IOException {
            this.echoPort = echoPort;
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            port = ss.getLocalPort();
            setDaemon(true);
        }

        public void run() {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    Thread t = new Thread(new Runnable() {
                        public void run() {
                            try {
                                handle(s);
                            } catch (Exception ignored) {
                                try {
                                    s.close();
                                } catch (Exception ignored2) {
                                }
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                } catch (Exception e) {
                    return;
                }
            }
        }

        protected abstract void handle(Socket s) throws Exception;

        void stopServer() {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }

        Socket target() throws IOException {
            return new Socket("127.0.0.1", echoPort);
        }
    }

    static class HttpProxy extends MockProxy {
        volatile int code = 200;

        HttpProxy(int echoPort) throws IOException {
            super(echoPort);
        }

        protected void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHttpHeader(in);
            if (head == null) {
                s.close();
                return;
            }
            lastRequest = head;
            int idx = head.indexOf("Proxy-Authorization: Basic ");
            if (idx >= 0) {
                String rest = head.substring(idx + "Proxy-Authorization: Basic ".length());
                lastAuth = rest.split("\r\n")[0].trim();
            }
            if (code != 200) {
                out.write(("HTTP/1.1 " + code + " Proxy Error\r\nContent-Length: 0\r\n\r\n")
                        .getBytes("ISO-8859-1"));
                out.flush();
                s.close();
                return;
            }
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("ISO-8859-1"));
            out.flush();
            bridge(s, target());
        }
    }

    static class Socks5Proxy extends MockProxy {
        volatile boolean requireAuth;

        Socks5Proxy(int echoPort) throws IOException {
            super(echoPort);
        }

        protected void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int ver = in.read();
            if (ver < 0) {
                s.close();
                return;
            }
            int n = in.read();
            byte[] methods = new byte[n];
            readFully(in, methods);
            if (requireAuth) {
                out.write(new byte[]{5, 2});
                out.flush();
                int av = in.read();
                if (av < 0) {
                    s.close();
                    return;
                }
                int ulen = in.read();
                byte[] u = new byte[ulen];
                readFully(in, u);
                int plen = in.read();
                byte[] p = new byte[plen];
                readFully(in, p);
                lastAuth = new String(u, "UTF-8") + ":" + new String(p, "UTF-8");
                out.write(new byte[]{1, 0});
                out.flush();
            } else {
                out.write(new byte[]{5, 0});
                out.flush();
            }
            if (in.read() < 0 || in.read() < 0 || in.read() < 0) {
                s.close();
                return;
            }
            int atyp = in.read();
            if (atyp < 0) {
                s.close();
                return;
            }
            lastAtyp = atyp;
            if (atyp == 1) {
                readFully(in, new byte[4]);
            } else if (atyp == 3) {
                int len = in.read();
                byte[] d = new byte[len];
                readFully(in, d);
                lastDomain = new String(d, "UTF-8");
            } else if (atyp == 4) {
                readFully(in, new byte[16]);
            }
            byte[] portBytes = new byte[2];
            readFully(in, portBytes);
            lastRequest = "atyp=" + atyp + " domain=" + lastDomain;
            // BND.ADDR/BND.PORT 必须完整给出，否则残留字节会污染后续 RDP 流
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
            out.flush();
            bridge(s, target());
        }
    }

    static class Socks4Proxy extends MockProxy {
        Socks4Proxy(int echoPort) throws IOException {
            super(echoPort);
        }

        protected void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            if (in.read() < 0 || in.read() < 0) {
                s.close();
                return;
            }
            byte[] portBytes = new byte[2];
            readFully(in, portBytes);
            byte[] ip = new byte[4];
            readFully(in, ip);
            ByteArrayOutputStream uid = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) > 0) {
                uid.write(b);
            }
            String ipStr = (ip[0] & 255) + "." + (ip[1] & 255) + "." + (ip[2] & 255)
                    + "." + (ip[3] & 255);
            if ((ip[0] & 255) == 0 && (ip[1] & 255) == 0 && (ip[2] & 255) == 0
                    && (ip[3] & 255) != 0) {
                ByteArrayOutputStream dom = new ByteArrayOutputStream();
                while ((b = in.read()) > 0) {
                    dom.write(b);
                }
                lastDomain = new String(dom.toByteArray(), "UTF-8");
            }
            lastRequest = ipStr + " uid=" + new String(uid.toByteArray(), "UTF-8");
            out.write(new byte[]{0, 0x5A, 0, 0, 0, 0, 0, 0});
            out.flush();
            bridge(s, target());
        }
    }
}
