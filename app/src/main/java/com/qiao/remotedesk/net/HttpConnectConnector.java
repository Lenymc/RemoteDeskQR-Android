package com.qiao.remotedesk.net;

import android.util.Base64;

import com.qiao.remotedesk.model.ProxySettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** HTTP 代理（CONNECT 隧道），认证方式为 Basic（Proxy-Authorization）。 */
public class HttpConnectConnector implements ProxyConnector {

    private final String proxyHost;
    private final int proxyPort;
    private final String userName;
    private final String password;
    private final int timeoutSeconds;

    public HttpConnectConnector(ProxySettings p) {
        ProxySettings s = p == null ? new ProxySettings() : p;
        this.proxyHost = ProxyFactory.stripBrackets(s.host);
        this.proxyPort = s.port;
        this.userName = s.userName == null ? "" : s.userName;
        this.password = s.password == null ? "" : s.password;
        this.timeoutSeconds = ProxyFactory.clampTimeout(s.connectTimeoutSeconds);
    }

    @Override
    public String displayName() {
        return "HTTP 代理 " + proxyHost + ":" + proxyPort;
    }

    @Override
    public ProxyConnection connect(String targetHost, int targetPort) throws IOException {
        ProxyFactory.ensureTarget(targetHost, targetPort);
        Socket socket = ProxyFactory.openSocket(proxyHost, proxyPort, timeoutSeconds, "HTTP 代理");
        boolean ok = false;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String authority = ProxyFactory.toAuthority(targetHost, targetPort);
            ProxyFactory.writeAll(out, buildRequest(authority).getBytes("US-ASCII"));

            // 响应头必须逐字节读到 CRLF CRLF，不能用 BufferedReader 预读吞掉隧道首批数据
            String head = ProxyFactory.readHttpHead(in);
            int cr = head.indexOf(13);
            String statusLine = cr >= 0 ? head.substring(0, cr) : head;
            int code = parseStatusCode(statusLine);
            if (code != 200) {
                throw new ProxyException(ProxyException.describeHttp(code));
            }

            ok = true;
            ProxyConnection conn = new ProxyConnection(socket);
            ProxyFactory.clearReadTimeout(socket);
            return conn;
        } catch (IOException e) {
            if (e instanceof ProxyException) {
                throw e;
            }
            throw new ProxyException("HTTP 与代理 " + proxyHost + ":" + proxyPort
                    + " 的 CONNECT 握手被中断：" + e.getMessage(), e);
        } finally {
            if (!ok) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String buildRequest(String authority) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("CONNECT ").append(authority).append(" HTTP/1.1").append(ProxyFactory.CRLF);
        sb.append("Host: ").append(authority).append(ProxyFactory.CRLF);
        sb.append("Proxy-Connection: Keep-Alive").append(ProxyFactory.CRLF);
        sb.append("User-Agent: RemoteDesk/1.0").append(ProxyFactory.CRLF);
        if (!userName.isEmpty()) {
            String raw = userName + ":" + password;
            String token = Base64.encodeToString(raw.getBytes("UTF-8"), Base64.NO_WRAP);
            sb.append("Proxy-Authorization: Basic ").append(token).append(ProxyFactory.CRLF);
        }
        sb.append(ProxyFactory.CRLF);
        return sb.toString();
    }

    private static int parseStatusCode(String statusLine) throws ProxyException {
        String[] parts = statusLine.trim().split(" +");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        throw new ProxyException("无法解析代理响应：" + statusLine);
    }
}
