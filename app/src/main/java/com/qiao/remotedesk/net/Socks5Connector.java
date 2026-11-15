package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.ProxySettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;

/** SOCKS5（RFC 1928），支持无认证与 RFC1929 用户名/密码认证，可按 RemoteDns 决定域名解析方。 */
public class Socks5Connector implements ProxyConnector {

    private static final byte CMD_CONNECT = 0x01;
    private static final byte AUTH_NONE = 0x00;
    private static final byte AUTH_USER_PASS = 0x02;

    private final String proxyHost;
    private final int proxyPort;
    private final String userName;
    private final String password;
    private final boolean remoteDns;
    private final int timeoutSeconds;

    public Socks5Connector(ProxySettings p) {
        ProxySettings s = p == null ? new ProxySettings() : p;
        this.proxyHost = ProxyFactory.stripBrackets(s.host);
        this.proxyPort = s.port;
        this.userName = s.userName == null ? "" : s.userName;
        this.password = s.password == null ? "" : s.password;
        this.remoteDns = s.remoteDns;
        this.timeoutSeconds = ProxyFactory.clampTimeout(s.connectTimeoutSeconds);
    }

    @Override
    public String displayName() {
        return "SOCKS5 " + proxyHost + ":" + proxyPort;
    }

    @Override
    public ProxyConnection connect(String targetHost, int targetPort) throws IOException {
        ProxyFactory.ensureTarget(targetHost, targetPort);
        Socket socket = ProxyFactory.openSocket(proxyHost, proxyPort, timeoutSeconds, "SOCKS5 代理");
        boolean ok = false;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            negotiate(in, out);
            sendConnect(in, out, ProxyFactory.stripBrackets(targetHost), targetPort);
            ok = true;
            ProxyConnection conn = new ProxyConnection(socket);
            ProxyFactory.clearReadTimeout(socket);
            return conn;
        } catch (IOException e) {
            if (e instanceof ProxyException) {
                throw e;
            }
            throw new ProxyException("SOCKS5 与代理 " + proxyHost + ":" + proxyPort
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

    /** 方法协商 + 可选的 RFC1929 口令认证。 */
    private void negotiate(InputStream in, OutputStream out) throws IOException {
        boolean hasCred = !userName.isEmpty();
        byte[] greeting = hasCred
                ? new byte[] { 0x05, 0x02, AUTH_NONE, AUTH_USER_PASS }
                : new byte[] { 0x05, 0x01, AUTH_NONE };
        ProxyFactory.writeAll(out, greeting);

        byte[] head = ProxyFactory.readBytes(in, 2);
        if (head[0] != 0x05) {
            throw new ProxyException("代理返回的 SOCKS 版本号是 0x" + ProxyFactory.hex2(head[0])
                    + "，不是 SOCKS5；若该端口是 SOCKS4 服务，请把代理类型改成 SOCKS4");
        }
        int method = head[1] & 0xFF;
        if (method == 0x00) {
            return;
        }
        if (method == 0x02) {
            if (!hasCred) {
                throw new ProxyException("代理要求用户名/密码认证，但配置里没有填写代理账号");
            }
            authenticate(in, out);
            return;
        }
        if (method == 0xFF) {
            throw new ProxyException("代理不接受任何可用的认证方式（0xFF）。若代理要求 GSSAPI/NTLM，本项目不支持");
        }
        throw new ProxyException("代理选择了未预期的认证方式 0x" + ProxyFactory.hex2(method));
    }

    private void authenticate(InputStream in, OutputStream out) throws IOException {
        byte[] user = userName.getBytes("UTF-8");
        byte[] pass = password.getBytes("UTF-8");
        if (user.length == 0 || user.length > 255 || pass.length > 255) {
            throw new ProxyException("代理用户名或密码长度超出 SOCKS5 允许的 1~255 字节");
        }
        // 01 ULEN UNAME PLEN PASSWD
        byte[] packet = new byte[3 + user.length + pass.length];
        packet[0] = 0x01;
        packet[1] = (byte) user.length;
        System.arraycopy(user, 0, packet, 2, user.length);
        packet[2 + user.length] = (byte) pass.length;
        System.arraycopy(pass, 0, packet, 3 + user.length, pass.length);
        ProxyFactory.writeAll(out, packet);

        byte[] resp = ProxyFactory.readBytes(in, 2);
        if (resp[0] != 0x01) {
            throw new ProxyException("SOCKS5 认证响应版本异常：0x" + ProxyFactory.hex2(resp[0]));
        }
        if (resp[1] != 0x00) {
            throw new ProxyException("SOCKS5 用户名/密码认证被代理拒绝（请核对代理账号）");
        }
    }

    private void sendConnect(InputStream in, OutputStream out, String host, int port) throws IOException {
        byte[] addrField = buildAddressField(host);
        byte[] packet = new byte[3 + addrField.length + 2];
        packet[0] = 0x05;
        packet[1] = CMD_CONNECT;
        packet[2] = 0x00;
        System.arraycopy(addrField, 0, packet, 3, addrField.length);
        packet[3 + addrField.length] = (byte) (port >> 8);
        packet[4 + addrField.length] = (byte) (port & 0xFF);
        ProxyFactory.writeAll(out, packet);

        byte[] head = ProxyFactory.readBytes(in, 4); // VER REP RSV ATYP
        if (head[0] != 0x05) {
            throw new ProxyException("SOCKS5 应答版本异常：0x" + ProxyFactory.hex2(head[0]));
        }
        if (head[1] != 0x00) {
            throw new ProxyException(ProxyException.describeSocks5(head[1] & 0xFF));
        }

        // 把 BND.ADDR + BND.PORT 读掉，否则残留字节会污染后续隧道数据
        int atyp = head[3] & 0xFF;
        int skip;
        if (atyp == 0x01) {
            skip = 4 + 2;
        } else if (atyp == 0x04) {
            skip = 16 + 2;
        } else if (atyp == 0x03) {
            skip = ProxyFactory.readByte(in) + 2;
        } else {
            throw new ProxyException("SOCKS5 应答中的地址类型未知：0x" + ProxyFactory.hex2(atyp));
        }
        ProxyFactory.readBytes(in, skip);
    }

    /** IP 字面量直接用 ATYP 1/4；域名按 remoteDns 交给代理(0x03)或本地解析成 IP。 */
    private byte[] buildAddressField(String host) throws IOException {
        InetAddress literal = ProxyFactory.parseIpLiteral(host);
        if (literal != null) {
            return withAtyp(literal);
        }
        if (!remoteDns) {
            InetAddress[] all;
            try {
                all = InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new ProxyException("本地无法解析 " + host + "：" + e.getMessage()
                        + "。可勾选「由代理解析域名」改用远端 DNS");
            }
            InetAddress chosen = null;
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) {
                    chosen = a;
                    break;
                }
            }
            if (chosen == null && all.length > 0) {
                chosen = all[0];
            }
            if (chosen == null) {
                throw new ProxyException("本地解析 " + host + " 没有得到任何地址");
            }
            return withAtyp(chosen);
        }
        byte[] name = host.getBytes("US-ASCII");
        if (name.length == 0 || name.length > 255) {
            throw new ProxyException("域名长度 " + name.length + " 超出 SOCKS5 允许范围（1~255）");
        }
        byte[] field = new byte[2 + name.length];
        field[0] = 0x03;
        field[1] = (byte) name.length;
        System.arraycopy(name, 0, field, 2, name.length);
        return field;
    }

    private static byte[] withAtyp(InetAddress address) {
        byte[] ip = address.getAddress();
        byte[] field = new byte[1 + ip.length];
        field[0] = (byte) (address instanceof Inet4Address ? 0x01 : 0x04);
        System.arraycopy(ip, 0, field, 1, ip.length);
        return field;
    }
}
