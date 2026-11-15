package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.ProxySettings;
import com.qiao.remotedesk.model.ProxyType;

import java.io.Closeable;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 代理 + 目标可达性探测。阶段一单独探代理端口，阶段二走完整连接器握手到目标，
 * 断在哪一段一眼可见。只验证 TCP 与代理认证，不验证 RDP 凭据。
 */
public final class ConnectivityTester {

    private static final String NL = new String(new char[] { 10 });

    public static class Result {
        public boolean success;
        public String message;
        public List<String> steps;
        public long elapsedMs;
        public long connectMs;
    }

    private ConnectivityTester() {
    }

    public static Result test(ProxySettings proxy, String host, int port) {
        long sw = System.nanoTime();
        List<String> steps = new ArrayList<String>();
        ProxySettings p = proxy != null ? proxy : new ProxySettings();
        boolean proxied = p.type != null && p.type != ProxyType.NONE;

        try {
            if (proxied) {
                // 阶段一：只连代理端口，把「本机 → 代理」单独拎出来
                long stage = System.nanoTime();
                Socket probe = null;
                try {
                    probe = ProxyFactory.openSocket(p.host, p.port, p.connectTimeoutSeconds, "代理");
                } catch (Exception ex) {
                    steps.add("① 代理端口 " + p.host + ":" + p.port + " 不可达");
                    return failure(sw, steps, "卡在第一步「本机 → 代理」：" + NL + ex.getMessage());
                } finally {
                    closeQuietly(probe);
                }
                long ms = (System.nanoTime() - stage) / 1000000L;
                steps.add("① 代理端口 " + p.host + ":" + p.port + " 可达（" + ms + " ms）");
            }

            // 阶段二：完整握手 + 到达目标
            ProxyConnector connector = ProxyFactory.create(p);
            long handshake = System.nanoTime();
            ProxyConnection conn = null;
            try {
                conn = connector.connect(host, port);
            } finally {
                closeQuietly(conn);
            }
            long connectMs = (System.nanoTime() - handshake) / 1000000L;

            if (proxied) {
                steps.add("② " + connector.displayName() + " 握手完成，已打通到 "
                        + host + ":" + port + "（" + connectMs + " ms）");
            } else {
                steps.add("① 目标 " + host + ":" + port + " TCP 可达（" + connectMs + " ms）");
            }

            String via = proxied ? "经 " + connector.displayName() : "直连";
            Result r = new Result();
            r.success = true;
            r.message = "成功：" + via + " → " + host + ":" + port + " 通道可用" + NL + join(steps);
            r.steps = steps;
            r.connectMs = connectMs;
            r.elapsedMs = (System.nanoTime() - sw) / 1000000L;
            return r;
        } catch (ProxyException ex) {
            steps.add(proxied ? "② 代理握手失败" : "② 目标失败");
            String scope = proxied
                    ? "代理端口是通的，卡在「代理 → 目标 " + host + ":" + port + "」或代理握手："
                    : "卡在「本机 → 目标 " + host + ":" + port + "」：";
            return failure(sw, steps, scope + NL + ex.getMessage());
        } catch (Throwable t) {
            steps.add("探测异常：" + t.getClass().getSimpleName());
            return failure(sw, steps, "探测失败：" + t.getMessage());
        }
    }

    private static Result failure(long sw, List<String> steps, String message) {
        Result r = new Result();
        r.success = false;
        r.message = message + NL + NL + join(steps);
        r.steps = steps;
        r.connectMs = 0;
        r.elapsedMs = (System.nanoTime() - sw) / 1000000L;
        return r;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(NL);
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
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
