package com.qiao.remotedesk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 设备端 TLS 能力探测：验证 Android(Conscrypt) 能否与旧版 Windows(证书 SHA1 签名、
 * ServerKeyExchange 用 rsa_pkcs1_sha1) 完成 RDP 所需的 TLS 握手。
 * 结果写入 filesDir/tlsprobe.txt，便于 adb 取回。
 */
public final class TlsProbe {

    private TlsProbe() {
    }

    private static final class TrustAll implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] c, String a) {
        }

        public void checkServerTrusted(X509Certificate[] c, String a) {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public static String probe(String host, int port) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conscrypt TLS probe -> ").append(host).append(':').append(port).append('\n');
        sb.append("provider=").append(java.security.Security.getProvider("Conscrypt") != null
                ? "Conscrypt" : "unknown").append('\n');
        attempt(sb, host, port, "A 默认(TLS1.2)", new String[]{"TLSv1.2"}, null);
        attempt(sb, host, port, "B TLS1.0/1.1/1.2", new String[]{"TLSv1", "TLSv1.1", "TLSv1.2"}, null);
        attempt(sb, host, port, "C 静态RSA套件", new String[]{"TLSv1.2"},
                new String[]{"TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"});
        attempt(sb, host, port, "D ECDHE-SHA1 套件", new String[]{"TLSv1.2"},
                new String[]{"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"});
        return sb.toString();
    }

    private static void attempt(StringBuilder sb, String host, int port, String label,
                                String[] protos, String[] suites) {
        Socket raw = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 8000);
            raw.setSoTimeout(9000);
            raw.setTcpNoDelay(true);
            OutputStream o = raw.getOutputStream();
            // X.224 连接请求，requestedProtocols = SSL|HYBRID
            o.write(new byte[]{3, 0, 0, 0x13, 0x0E, (byte) 0xE0, 0, 0, 0, 0, 0, 1, 0, 8, 0, 3, 0, 0, 0});
            o.flush();
            byte[] h = new byte[4];
            readFully(raw.getInputStream(), h);
            int len = ((h[2] & 0xFF) << 8 | (h[3] & 0xFF)) - 4;
            byte[] body = new byte[len];
            readFully(raw.getInputStream(), body);
            int sel = ((body[len - 4] & 0xFF));

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new TrustAll()}, new java.security.SecureRandom());
            SSLSocket ss = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            ss.setUseClientMode(true);
            try {
                ss.setEnabledProtocols(protos);
            } catch (Throwable t) {
                sb.append(label).append(": 无法启用协议 ").append(t).append('\n');
            }
            if (suites != null) {
                try {
                    ss.setEnabledCipherSuites(suites);
                } catch (Throwable t) {
                    sb.append(label).append(": 无法启用套件 ").append(t).append('\n');
                }
            }
            ss.startHandshake();
            sb.append(label).append(": OK proto=").append(ss.getSession().getProtocol())
                    .append(" cipher=").append(ss.getSession().getCipherSuite())
                    .append(" serverSel=").append(sel).append('\n');
            ss.close();
        } catch (Throwable t) {
            sb.append(label).append(": FAIL ").append(t.getClass().getSimpleName())
                    .append(": ").append(String.valueOf(t.getMessage())).append('\n');
            if (raw != null) {
                try {
                    raw.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) {
            b.append(s).append(' ');
        }
        return b.toString();
    }

    private static void readFully(InputStream in, byte[] b) throws java.io.IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                throw new java.io.EOFException();
            }
            off += n;
        }
    }

    /** 在后台线程跑探测并落盘，返回文件路径。 */
    public static void runToFile(final File dir, final String host, final int port) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = probe(host, port);
                try {
                    File f = new File(dir, "tlsprobe.txt");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(text.getBytes("UTF-8"));
                    fo.close();
                    android.util.Log.e("RD", text);
                } catch (Throwable t) {
                    android.util.Log.e("RD", "写探测结果失败 " + t);
                }
            }
        }, "tls-probe").start();
    }
}
