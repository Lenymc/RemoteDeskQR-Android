package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.LoopbackPool;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 TCP 隧道：在 127.0.0.x 上监听随机端口，每来一条本地连接就通过代理拨一条到目标的通道，
 * 然后全双工转发。对外只暴露一个普通的回环地址端口。
 */
public final class TunnelServer {

    public interface LogListener {
        void onLog(String message);
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    private final ServerSocket serverSocket;
    private final ProxyConnector connector;
    private final String targetHost;
    private final int targetPort;
    private final String bindAddress;
    private final int localPort;

    private final AtomicLong bytesToTarget = new AtomicLong(0);
    private final AtomicLong bytesFromTarget = new AtomicLong(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile boolean running = true;
    private volatile LogListener logListener;
    private final List<String> pendingLogs = new ArrayList<String>();
    private final List<Closeable> openResources =
            Collections.synchronizedList(new ArrayList<Closeable>());
    private final Thread acceptThread;

    private TunnelServer(ServerSocket serverSocket, ProxyConnector connector,
                         String targetHost, int targetPort) {
        this.serverSocket = serverSocket;
        this.connector = connector;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.bindAddress = serverSocket.getInetAddress().getHostAddress();
        this.localPort = serverSocket.getLocalPort();
        this.acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        this.acceptThread.setDaemon(true);
        this.acceptThread.setName("remotedesk-tunnel-accept-" + localPort);
    }

    /** 端口固定为 0（由系统分配）；bindAddress 非法时退回 127.0.0.1。 */
    public static TunnelServer start(ProxyConnector connector, String targetHost,
                                     int targetPort, String bindAddress) {
        if (connector == null) {
            throw new IllegalArgumentException("连接器不能为空");
        }
        if (targetHost == null || targetHost.trim().isEmpty()) {
            throw new IllegalArgumentException("目标主机不能为空");
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("目标端口 " + targetPort + " 非法");
        }
        String chosen = LoopbackPool.isValid(bindAddress) ? bindAddress.trim() : "127.0.0.1";
        ServerSocket ss;
        String fallbackNote = null;
        try {
            ss = createServer(chosen);
        } catch (IOException e) {
            fallbackNote = "绑定 " + chosen + " 失败（" + e.getMessage() + "），已回退到 127.0.0.1";
            try {
                ss = createServer("127.0.0.1");
            } catch (IOException e2) {
                // 不向调用方抛受检异常：契约里 start 的签名没有 throws
                throw new IllegalStateException("启动隧道失败：" + e2.getMessage(), e2);
            }
        }
        TunnelServer server = new TunnelServer(ss, connector, targetHost, targetPort);
        server.acceptThread.start();
        if (fallbackNote != null) {
            server.emit(fallbackNote);
        }
        server.emit("隧道已就绪：" + server.bindAddress + ":" + server.localPort
                + " → " + targetHost + ":" + targetPort + "（经 " + connector.displayName() + "）");
        return server;
    }

    private static ServerSocket createServer(String address) throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName(address), 0), 50);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    emit("接受本地连接失败：" + e.getMessage());
                }
                break;
            }
            if (!running) {
                closeQuietly(client);
                break;
            }
            try {
                client.setTcpNoDelay(true);
            } catch (Throwable ignored) {
            }
            activeConnections.incrementAndGet();
            final int index = totalConnections.incrementAndGet();
            final Socket incoming = client;
            openResources.add(incoming);
            Thread handler = new Thread(new Runnable() {
                @Override
                public void run() {
                    handleClient(incoming, index);
                }
            });
            handler.setDaemon(true);
            handler.setName("remotedesk-tunnel-client-" + index);
            handler.start();
        }
    }

    /** 一条连接：本线程跑上行，另起一个 daemon 线程跑下行，任一方向 EOF 即整条关闭。 */
    private void handleClient(Socket client, int index) {
        ProxyConnection channel = null;
        try {
            emit("#" + index + " 本地连接接入（" + describe(client) + "），正在通过代理建立通道…");
            channel = connector.connect(targetHost, targetPort);
            openResources.add(channel);
            emit("#" + index + " 通道已建立，开始转发数据");

            final ProxyConnection ch = channel;
            final InputStream clientIn = client.getInputStream();
            final OutputStream clientOut = client.getOutputStream();
            final InputStream proxyIn = ch.in;
            final OutputStream proxyOut = ch.out;

            final AtomicBoolean closed = new AtomicBoolean(false);
            final Runnable closer = new Runnable() {
                @Override
                public void run() {
                    if (closed.compareAndSet(false, true)) {
                        closeQuietly(ch);
                        closeQuietly(client);
                    }
                }
            };

            Thread down = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        pump(proxyIn, clientOut, false);
                    } catch (Throwable ignored) {
                    } finally {
                        closer.run();
                    }
                }
            });
            down.setDaemon(true);
            down.setName("remotedesk-tunnel-down-" + index);
            down.start();

            try {
                pump(clientIn, proxyOut, true);
            } catch (Throwable ignored) {
            } finally {
                closer.run();
            }
            try {
                down.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            emit("#" + index + " 通道关闭，累计上行 " + formatSize(bytesToTarget.get())
                    + " / 下行 " + formatSize(bytesFromTarget.get()));
        } catch (Throwable t) {
            emit("#" + index + " 建立通道失败：" + t.getMessage());
        } finally {
            closeQuietly(channel);
            closeQuietly(client);
            if (channel != null) {
                openResources.remove(channel);
            }
            openResources.remove(client);
            activeConnections.decrementAndGet();
        }
    }

    private void pump(InputStream from, OutputStream to, boolean upstream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (running) {
            int read;
            try {
                read = from.read(buffer);
            } catch (IOException e) {
                break; // 连接被重置或正在关闭
            }
            if (read <= 0) {
                break; // EOF：任一方向结束即视为会话结束
            }
            to.write(buffer, 0, read);
            to.flush();
            if (upstream) {
                bytesToTarget.addAndGet(read);
            } else {
                bytesFromTarget.addAndGet(read);
            }
        }
    }

    public String bindAddress() {
        return bindAddress;
    }

    public int localPort() {
        return localPort;
    }

    public long bytesToTarget() {
        return bytesToTarget.get();
    }

    public long bytesFromTarget() {
        return bytesFromTarget.get();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public int totalConnections() {
        return totalConnections.get();
    }

    public String proxyName() {
        return connector.displayName();
    }

    public void setLogListener(LogListener l) {
        this.logListener = l;
        if (l != null) {
            synchronized (pendingLogs) {
                for (String m : pendingLogs) {
                    try {
                        l.onLog(m);
                    } catch (Throwable ignored) {
                    }
                }
                pendingLogs.clear();
            }
        }
    }

    /** 关闭监听与所有活动连接，accept 循环与 pump 线程都是 daemon，能干净退出。 */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running = false;
        emit("正在关闭隧道…");
        try {
            serverSocket.close();
        } catch (Throwable ignored) {
        }
        synchronized (openResources) {
            for (Closeable c : openResources) {
                closeQuietly(c);
            }
            openResources.clear();
        }
        try {
            acceptThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        emit("隧道已关闭");
    }

    private void emit(String message) {
        LogListener l = logListener;
        if (l == null) {
            synchronized (pendingLogs) {
                if (pendingLogs.size() < 50) {
                    pendingLogs.add(message);
                }
            }
            return;
        }
        try {
            l.onLog(message);
        } catch (Throwable ignored) {
            // UI 回调异常不能影响隧道线程
        }
    }

    private static String describe(Socket s) {
        try {
            return s.getRemoteSocketAddress() == null ? "未知" : s.getRemoteSocketAddress().toString();
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
