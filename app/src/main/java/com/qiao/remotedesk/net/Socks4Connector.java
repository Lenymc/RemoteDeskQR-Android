package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;

/** SOCKS4 / SOCKS4a，仅支持 IPv4 与 USERID；目标为域名时用 4a 扩展由代理解析。 */
public class Socks4Connector implements ProxyConnector {

    private static final byte CMD_CONNECT = 0x01;
    private static final int REPLY_GRANTED = 0x5A;

    private final String proxyHost;
    private final int proxyPort;
    private final String userId;
    private final boolean remoteDns;
    private final int timeoutSeconds;

    public Socks4Connector(ProxySettings p) {
        ProxySettings s = p == null ? new ProxySettings() : p;
        this.proxyHost = ProxyFactory.stripBrackets(s.host);
        this.proxyPort = s.port;
        this.userId = s.userName == null ? "" : s.userName;
        this.remoteDns = s.remoteDns;
        this.timeoutSeconds = ProxyFactory.clampTimeout(s.connectTimeoutSeconds);
    }

    @Override
    public String displayName() {
        return "SOCKS4 " + proxyHost + ":" + proxyPort;
    }

    @Override
    public ProxyConnection connect(String targetHost, int targetPort) throws IOException {
        ProxyFactory.ensureTarget(targetHost, targetPort);
        String host = ProxyFactory.stripBrackets(targetHost);
        Socket socket = ProxyFactory.openSocket(proxyHost, proxyPort, timeoutSeconds, "SOCKS4 代理");
        boolean ok = false;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            InetAddress literal = ProxyFactory.parseIpLiteral(host);
            byte[] ipBytes;
            byte[] hostBytes = new byte[0];
            if (literal != null) {
                if (!(literal instanceof Inet4Address)) {
                    throw new ProxyException("SOCKS4 完全不支持 IPv6 目标，请改用 SOCKS5 或 HTTP 代理");
                }
                ipBytes = literal.getAddress();
            } else if (remoteDns) {
                // SOCKS4a：0.0.0.1 表示地址以域名形式跟在后面
                ipBytes = new byte[] { 0, 0, 0, 1 };
                hostBytes = host.getBytes("US-ASCII");
            } else {
                InetAddress[] all;
                try {
                    all = InetAddress.getAllByName(host);
                } catch (Exception e) {
                    throw new ProxyException("本地无法解析 " + host + "：" + e.getMessage());
                }
                InetAddress v4 = null;
                for (InetAddress a : all) {
                    if (a instanceof Inet4Address) {
                        v4 = a;
                        break;
                    }
                }
                if (v4 == null) {
                    throw new ProxyException("本地解析 " + host + " 没有得到 IPv4 地址，SOCKS4 不支持 IPv6");
                }
                ipBytes = v4.getAddress();
            }

            byte[] userIdBytes = userId.getBytes("UTF-8");
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(0x04);
            packet.write(CMD_CONNECT);
            packet.write((targetPort >> 8) & 0xFF);
            packet.write(targetPort & 0xFF);
            packet.write(ipBytes, 0, 4);
            packet.write(userIdBytes, 0, userIdBytes.length);
            packet.write(0x00);
            if (hostBytes.length > 0) {
                packet.write(hostBytes, 0, hostBytes.length);
                packet.write(0x00);
            }
            ProxyFactory.writeAll(out, packet.toByteArray());

            byte[] resp = ProxyFactory.readBytes(in, 8);
            int reply = resp[1] & 0xFF;
            if (reply != REPLY_GRANTED) {
                throw new ProxyException(ProxyException.describeSocks4(reply));
            }

            ok = true;
            ProxyConnection conn = new ProxyConnection(socket);
            ProxyFactory.clearReadTimeout(socket);
            return conn;
        } catch (IOException e) {
            if (e instanceof ProxyException) {
                throw e;
            }
            throw new ProxyException("SOCKS4 与代理 " + proxyHost + ":" + proxyPort
                    + " 的握手被中断：" + e.getMessage(), e);
        } finally {
            if (!ok) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
