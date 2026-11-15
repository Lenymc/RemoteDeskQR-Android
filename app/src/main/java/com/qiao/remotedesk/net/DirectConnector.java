package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.ProxySettings;

import java.io.IOException;
import java.net.Socket;

/** 直连（不走代理）。 */
public class DirectConnector implements ProxyConnector {

    private final int timeoutSeconds;

    public DirectConnector(ProxySettings p) {
        this.timeoutSeconds = p == null ? 15 : ProxyFactory.clampTimeout(p.connectTimeoutSeconds);
    }

    @Override
    public String displayName() {
        return "直连";
    }

    @Override
    public ProxyConnection connect(String targetHost, int targetPort) throws IOException {
        Socket s = ProxyFactory.openSocket(targetHost, targetPort, timeoutSeconds, "直连");
        try {
            ProxyConnection conn = new ProxyConnection(s);
            ProxyFactory.clearReadTimeout(s);
            return conn;
        } catch (IOException e) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }
}
