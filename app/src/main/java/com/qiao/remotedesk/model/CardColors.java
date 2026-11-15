package com.qiao.remotedesk.model;

import android.graphics.Color;

import java.util.Locale;

/**
 * 卡片调色板与「按 Id 稳定取色」。用 FNV-1a 而不是 String.hashCode，保证同一 Id 永远同色。
 */
public final class CardColors {

    /** 12 个中深色，卡片文字统一用白色。 */
    public static final String[] PALETTE = {
            "#1D4ED8", // 蓝
            "#4338CA", // 靛
            "#6D28D9", // 紫
            "#A21CAF", // 品红
            "#BE185D", // 玫红
            "#B91C1C", // 红
            "#C2410C", // 橙
            "#B45309", // 琥珀
            "#15803D", // 绿
            "#0F766E", // 青绿
            "#0E7490", // 青
            "#334155"  // 石板灰
    };

    private CardColors() {
    }

    /** 自填的合法色优先，否则按 Id 取一个稳定颜色。 */
    public static String resolve(String custom, String id) {
        String[] out = new String[1];
        if (tryNormalize(custom, out)) {
            return out[0];
        }
        String s = id == null ? "" : id;
        // FNV-1a：uint 语义用 int 自然溢出，取模前再按无符号解释
        int hash = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 16777619;
        }
        int index = (int) ((hash & 0xFFFFFFFFL) % PALETTE.length);
        return PALETTE[index];
    }

    /** 校验并规范成 #RRGGBB，支持 #RGB 简写；结果放在 outHex[0]。 */
    public static boolean tryNormalize(String text, String[] outHex) {
        if (outHex != null && outHex.length > 0) {
            outHex[0] = "";
        }
        if (text == null) {
            return false;
        }
        String raw = text.trim();
        if (raw.isEmpty()) {
            return false;
        }
        if (raw.charAt(0) == '#') {
            raw = raw.substring(1);
        }
        if (raw.length() == 3) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                char c = raw.charAt(i);
                sb.append(c).append(c);
            }
            raw = sb.toString();
        }
        if (raw.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            char c = raw.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        if (outHex != null && outHex.length > 0) {
            outHex[0] = "#" + raw.toUpperCase(Locale.ROOT);
        }
        return true;
    }

    /** 直接返回 android.graphics.Color 的 int 值。 */
    public static int parseColor(String custom, String id) {
        String hex = resolve(custom, id);
        try {
            return Color.parseColor(hex);
        } catch (Throwable t) {
            return Color.parseColor(PALETTE[0]);
        }
    }
}
