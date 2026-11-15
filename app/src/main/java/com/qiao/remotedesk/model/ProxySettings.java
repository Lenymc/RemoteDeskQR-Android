package com.qiao.remotedesk.model;

import org.json.JSONObject;

/**
 * HTTP / SOCKS 代理配置。密码仅在内存中保留明文，落盘时经 Crypto 加密。
 * 字段全部 public，供 UI 直接读写。
 */
public class ProxySettings {

    public ProxyType type = ProxyType.NONE;
    public String host = "";
    public int port = 1080;
    public String userName = "";
    public String password = "";
    public boolean remoteDns = true;
    public int connectTimeoutSeconds = 15;

    public ProxySettings clone() {
        ProxySettings p = new ProxySettings();
        p.type = type;
        p.host = host;
        p.port = port;
        p.userName = userName;
        p.password = password;
        p.remoteDns = remoteDns;
        p.connectTimeoutSeconds = connectTimeoutSeconds;
        return p;
    }

    /** 一行式摘要：直连 / HTTP host:port / SOCKS5 host:port / SOCKS4 host:port。 */
    public String summary() {
        if (type == null) {
            return "直连";
        }
        switch (type) {
            case HTTP_CONNECT:
                return "HTTP " + host + ":" + port;
            case SOCKS5:
                return "SOCKS5 " + host + ":" + port;
            case SOCKS4:
                return "SOCKS4 " + host + ":" + port;
            case NONE:
            default:
                return "直连";
        }
    }

    public boolean isProxyEnabled() {
        return type != null && type != ProxyType.NONE;
    }

    /** SOCKS4 只用 USERID，不需要密码；HTTP / SOCKS5 支持密码认证。 */
    public boolean requiresPassword() {
        return type == ProxyType.HTTP_CONNECT || type == ProxyType.SOCKS5;
    }

    /**
     * 改变类型并按源码 ProxySettings.Type 的规则纠正端口：只有端口仍停在
     * 「未改过的默认值」(0/1080/8080/3128/10800) 时才自动改，
     * SOCKS5/SOCKS4 归 1080、HTTP 归 8080，避免覆盖用户手填的端口。
     * 字段是 public、UI 直接赋值时无法拦截，故额外提供此入口供主动调用。
     */
    public void applyType(ProxyType newType) {
        if (newType == null) {
            return;
        }
        boolean defaultish = port == 0 || port == 1080 || port == 8080
                || port == 3128 || port == 10800;
        if (defaultish) {
            if (newType == ProxyType.SOCKS5 || newType == ProxyType.SOCKS4) {
                port = 1080;
            } else if (newType == ProxyType.HTTP_CONNECT) {
                port = 8080;
            }
        }
        type = newType;
    }

    /** 各类型的默认端口，反序列化缺少 port 键时使用。 */
    private static int defaultPortFor(ProxyType t) {
        return t == ProxyType.HTTP_CONNECT ? 8080 : 1080;
    }

    public static ProxySettings fromJson(JSONObject o) {
        ProxySettings p = new ProxySettings();
        if (o == null) {
            return p;
        }
        p.type = parseType(o.optString("type", "NONE"));
        p.host = o.optString("host", "");
        p.port = o.optInt("port", defaultPortFor(p.type));
        p.userName = o.optString("userName", "");
        p.remoteDns = o.optBoolean("remoteDns", true);
        p.connectTimeoutSeconds = clampTimeout(o.optInt("connectTimeoutSeconds", 15));
        // 只从密文还原密码，绝不读取明文
        try {
            p.password = Crypto.decrypt(o.optString("protectedPassword", ""));
        } catch (Throwable t) {
            p.password = "";
        }
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type == null ? "NONE" : type.name());
            o.put("host", host == null ? "" : host);
            o.put("port", port);
            o.put("userName", userName == null ? "" : userName);
            o.put("remoteDns", remoteDns);
            o.put("connectTimeoutSeconds", clampTimeout(connectTimeoutSeconds));
            // 明文 password 不落盘；加密失败则干脆不写该键
            String enc = "";
            try {
                enc = Crypto.encrypt(password);
            } catch (Throwable t) {
                enc = "";
            }
            if (!enc.isEmpty()) {
                o.put("protectedPassword", enc);
            }
        } catch (Throwable t) {
            // 保证序列化绝不因 JSONObject / Crypto 异常而崩溃
        }
        return o;
    }

    private static ProxyType parseType(String s) {
        if (s == null) {
            return ProxyType.NONE;
        }
        try {
            return ProxyType.valueOf(s.trim());
        } catch (Throwable t) {
            return ProxyType.NONE;
        }
    }

    private static int clampTimeout(int v) {
        return v < 3 ? 3 : (v > 120 ? 120 : v);
    }
}
