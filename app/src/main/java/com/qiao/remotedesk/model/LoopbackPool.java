package com.qiao.remotedesk.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 回环地址池：为每个连接分配独立的 127.0.0.x，避免多个会话的隧道端口互相冲突。
 */
public final class LoopbackPool {

    public static final String PRIMARY = "127.0.0.1";

    private LoopbackPool() {
    }

    /** 在 inUse 之外取一个 127.0.0.2 ~ 127.0.0.254 的空闲地址；用尽退回 127.0.0.1。 */
    public static String allocate(Collection<String> inUse) {
        Set<String> used = new HashSet<String>();
        if (inUse != null) {
            for (String item : inUse) {
                if (item != null && !item.trim().isEmpty()) {
                    used.add(item.trim());
                }
            }
        }
        for (int i = 2; i <= 254; i++) {
            String candidate = "127.0.0." + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return PRIMARY;
    }

    /** 是否为合法的 127.0.0.0/8 IPv4 点分十进制地址（不做 DNS 查询）。 */
    public static boolean isValid(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        String[] parts = t.split("[.]", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                return false;
            }
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            try {
                v[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v[i] < 0 || v[i] > 255) {
                return false;
            }
        }
        return v[0] == 127;
    }
}
