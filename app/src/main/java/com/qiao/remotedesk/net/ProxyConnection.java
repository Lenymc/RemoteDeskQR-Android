package com.qiao.remotedesk.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** 一条已打通到目标的隧道连接，持有底层 Socket，关闭时一并释放。 */
public class ProxyConnection implements Closeable {

    public final InputStream in;
    public final OutputStream out;

    private final Socket socket;
    private boolean closed;

    public ProxyConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (Throwable ignored) {
            // 关闭阶段异常一律忽略
        }
    }

    /** 对端（下一跳）地址描述，用于日志。 */
    public String remoteName() {
        try {
            return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        } catch (Throwable t) {
            return "未知";
        }
    }
}
