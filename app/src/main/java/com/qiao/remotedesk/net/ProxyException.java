package com.qiao.remotedesk.net;

import java.io.IOException;

/** 代理连接失败（认证失败、代理拒绝 CONNECT、目标不可达等），带中文可读诊断。 */
public class ProxyException extends IOException {

    public ProxyException(String message) {
        super(message);
    }

    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }

    /** HTTP CONNECT 非 2xx 状态码的中文诊断。 */
    public static String describeHttp(int code) {
        switch (code) {
            case 407:
                return "代理要求身份认证（407）。请填写正确的代理用户名/密码；若已填写仍失败，"
                        + "可能是代理使用了 NTLM/Negotiate 认证，本项目仅支持 Basic。";
            case 403:
                return "代理拒绝建立隧道（403）。企业代理常按目标端口放行，请确认策略允许访问该 RDP 端口。";
            case 502:
            case 503:
            case 504:
                return "代理无法连到目标主机（网关错误）。目标地址/端口可能不可达，或被代理策略拦截。";
            case 405:
                return "代理不支持 CONNECT 方法（405），请确认使用的是 HTTP 正向代理而非反向代理。";
            default:
                return "代理返回异常状态：" + code;
        }
    }

    /** SOCKS5 CONNECT 应答 REP 值的中文诊断。 */
    public static String describeSocks5(int rep) {
        switch (rep) {
            case 0x01:
                return "SOCKS5 代理返回「一般性失败」";
            case 0x02:
                return "SOCKS5 代理返回「规则不允许」（代理策略拦截了该目标）";
            case 0x03:
                return "SOCKS5 代理返回「网络不可达」";
            case 0x04:
                return "SOCKS5 代理返回「主机不可达」（目标地址在代理侧解析不到）";
            case 0x05:
                return "SOCKS5 代理返回「连接被拒绝」（目标端口未开放，或代理不允许该端口）";
            case 0x06:
                return "SOCKS5 代理返回「TTL 过期」";
            case 0x07:
                return "SOCKS5 代理不支持 CONNECT 命令";
            case 0x08:
                return "SOCKS5 代理不支持该地址类型";
            default:
                return "SOCKS5 代理返回未知错误码 0x" + hex2(rep);
        }
    }

    /** SOCKS4 应答状态码的中文诊断。 */
    public static String describeSocks4(int rep) {
        switch (rep) {
            case 0x5B:
                return "SOCKS4 代理返回「请求被拒绝或失败」（目标不可达，或代理策略不允许）";
            case 0x5C:
                return "SOCKS4 代理返回「无法连接 identd」";
            case 0x5D:
                return "SOCKS4 代理返回「USERID 与 identd 不匹配」";
            default:
                return "SOCKS4 代理返回未知状态码 0x" + hex2(rep);
        }
    }

    /** 两位大写十六进制。 */
    static String hex2(int b) {
        String s = Integer.toHexString(b & 0xFF).toUpperCase(java.util.Locale.ROOT);
        return s.length() == 1 ? "0" + s : s;
    }
}
