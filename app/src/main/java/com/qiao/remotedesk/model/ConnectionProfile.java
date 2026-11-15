package com.qiao.remotedesk.model;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 一个远程桌面连接配置：目标主机、凭据、代理、显示与重定向选项。
 * 字段全部 public。password 仅内存持有，落盘用 protectedPassword 密文。
 */
public class ConnectionProfile {

    /** 92 是反斜杠，写字面量会造成转义歧义，故用码点表示。 */
    private static final char BACKSLASH = (char) 92;

    public String id = UUID.randomUUID().toString().replace("-", "");
    public String name = "新建连接";
    public String host = "";
    public String userName = "";
    public String domain = "";
    public String password = "";
    public String note = "";
    public String loopbackAddress = "";
    public String cardColor = "";
    public int port = 3389;
    public boolean savePasswordToCredentialStore = false;
    public boolean useGlobalProxy = false;
    public ProxySettings proxy = new ProxySettings();
    public RdpOptions options = new RdpOptions();

    /** 无 host 返回「未设置目标」，否则 host:port。 */
    public String targetEndpoint() {
        return isBlank(host) ? "未设置目标" : host + ":" + port;
    }

    public String displayTitle() {
        return isBlank(name) ? targetEndpoint() : name;
    }

    public boolean isConfigured() {
        return !isBlank(host);
    }

    /** 使用全局代理时前缀「全局 」，否则用本条代理的摘要。 */
    public String proxySummary(ProxySettings global) {
        if (useGlobalProxy) {
            ProxySettings g = global != null ? global : new ProxySettings();
            return "全局 " + g.summary();
        }
        return proxy != null ? proxy.summary() : new ProxySettings().summary();
    }

    /** 实际用于建链的代理：勾了「使用全局代理」就取全局配置，否则取本条的。 */
    public ProxySettings effectiveProxy(ProxySettings global) {
        if (useGlobalProxy && global != null) {
            return global;
        }
        return proxy != null ? proxy : new ProxySettings();
    }

    /** 域账户组合成 CORP-user 形式（中间是反斜杠）；已带域或 UPN 时原样返回。 */
    public String credentialUserName() {
        String user = userName == null ? "" : userName.trim();
        if (user.isEmpty() || user.indexOf(BACKSLASH) >= 0 || user.indexOf('@') >= 0) {
            return user;
        }
        String d = domain == null ? "" : domain.trim();
        return d.isEmpty() ? user : d + BACKSLASH + user;
    }

    /** 传给 RDP 引擎的域；用户名本身已带域/UPN 时返回空，避免两处域拼接。 */
    public String effectiveDomain() {
        String user = userName == null ? "" : userName.trim();
        if (user.isEmpty() || user.indexOf(BACKSLASH) >= 0 || user.indexOf('@') >= 0) {
            return "";
        }
        return domain == null ? "" : domain.trim();
    }

    public String cardColorHex() {
        return CardColors.resolve(cardColor, id);
    }

    /** 复制成一条新配置：新 Id、新名字；回环地址刻意不复制。 */
    public ConnectionProfile cloneAsNew(String newName) {
        ConnectionProfile c = new ConnectionProfile();
        c.name = newName;
        c.host = host;
        c.port = port;
        c.userName = userName;
        c.domain = domain;
        c.password = password;
        c.note = note;
        c.savePasswordToCredentialStore = savePasswordToCredentialStore;
        c.cardColor = cardColor;
        c.useGlobalProxy = useGlobalProxy;
        c.proxy = proxy != null ? proxy.clone() : new ProxySettings();
        c.options = options != null ? options.clone() : new RdpOptions();
        // c.loopbackAddress 保持空串，副本要拿自己的回环地址
        return c;
    }

    public static ConnectionProfile fromJson(JSONObject o) {
        ConnectionProfile p = new ConnectionProfile();
        if (o == null) {
            return p;
        }
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", "新建连接");
        p.host = o.optString("host", "");
        p.port = o.optInt("port", 3389);
        p.userName = o.optString("userName", "");
        p.domain = o.optString("domain", "");
        p.note = o.optString("note", "");
        p.loopbackAddress = o.optString("loopbackAddress", "");
        p.cardColor = o.optString("cardColor", "");
        p.savePasswordToCredentialStore = o.optBoolean("savePasswordToCredentialStore", false);
        p.useGlobalProxy = o.optBoolean("useGlobalProxy", false);
        JSONObject po = o.optJSONObject("proxy");
        p.proxy = po != null ? ProxySettings.fromJson(po) : new ProxySettings();
        JSONObject oo = o.optJSONObject("options");
        p.options = oo != null ? RdpOptions.fromJson(oo) : new RdpOptions();
        // 只从密文还原密码，绝不读取明文
        try {
            p.password = Crypto.decrypt(o.optString("protectedPassword", ""));
        } catch (Throwable t) {
            p.password = "";
        }
        p.afterLoad();
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id == null ? "" : id);
            o.put("name", name == null ? "" : name);
            o.put("host", host == null ? "" : host);
            o.put("port", port);
            o.put("userName", userName == null ? "" : userName);
            o.put("domain", domain == null ? "" : domain);
            o.put("note", note == null ? "" : note);
            o.put("loopbackAddress", loopbackAddress == null ? "" : loopbackAddress);
            o.put("cardColor", cardColor == null ? "" : cardColor);
            o.put("savePasswordToCredentialStore", savePasswordToCredentialStore);
            o.put("useGlobalProxy", useGlobalProxy);
            o.put("proxy", proxy != null ? proxy.toJson() : new ProxySettings().toJson());
            o.put("options", options != null ? options.toJson() : new RdpOptions().toJson());
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
            // 序列化不因 JSONObject / Crypto 异常而崩溃
        }
        return o;
    }

    /** 反序列化后调用：补全集合内默认值，缺失 Id 时补一个。 */
    public void afterLoad() {
        if (proxy == null) {
            proxy = new ProxySettings();
        }
        if (options == null) {
            options = new RdpOptions();
        }
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
