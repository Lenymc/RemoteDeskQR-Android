package com.qiao.remotedesk.net;

import java.io.IOException;

/**
 * 代理连接器：把「目标 host:port」通过某种通道打通，交回一条裸字节流。
 * 隧道层不关心底下是直连、HTTP CONNECT 还是 SOCKS。
 */
public interface ProxyConnector {

    ProxyConnection connect(String targetHost, int targetPort) throws IOException;

    /** 用于日志展示，例如 “SOCKS5 10.0.0.1:1080”。 */
    String displayName();
}
