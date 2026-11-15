package com.qiao.remotedesk.model;

/** 代理类型。 */
public enum ProxyType {
    /** 直连目标，不走代理。 */
    NONE,
    /** HTTP 代理（CONNECT 隧道）。 */
    HTTP_CONNECT,
    /** SOCKS5（RFC 1928）。 */
    SOCKS5,
    /** SOCKS4 / SOCKS4a。 */
    SOCKS4
}
