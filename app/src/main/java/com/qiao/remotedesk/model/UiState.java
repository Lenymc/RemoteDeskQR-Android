package com.qiao.remotedesk.model;

import org.json.JSONObject;

/**
 * 界面状态：悬浮气泡、侧栏、全局代理。
 * 与连接配置分开存（ui.json），读写失败一律静默降级。
 */
public class UiState {

    /** 全局设置「气泡动作」下拉的第 7 项；下标与 SettingsView.ACTIONS 一致。 */
    public static final int ACTION_TOGGLE_KEYBOARD = 6;

    /** 气泡动作配置版本：1 起单击 / 长按默认「打开面板」，用于迁移旧 ui.json。 */
    private static final int BUBBLE_ACTIONS_VERSION = 1;

    /** 透明度语义版本：1 起字段存「透明程度」而非「不透明度 alpha」，用于迁移旧 ui.json。 */
    private static final int BUBBLE_OPACITY_VERSION = 1;

    public ProxySettings globalProxy = new ProxySettings();
    public int bubbleDiameter = 36;
    public String bubbleBorderColorHex = "";
    /** 悬浮气泡透明度：0 = 完全不透明，0.9 = 最透明（仍留 10% 底色便于定位）。 */
    public float bubbleOpacity = 0.5f;
    public int bubbleOpacityVersion = BUBBLE_OPACITY_VERSION;
    public int bubbleClickAction = 3;
    public int bubbleDoubleClickAction = 1;
    public int bubbleRightClickAction = 3;
    public int bubbleActionsVersion = BUBBLE_ACTIONS_VERSION;
    public int bubbleHoverDelayMs = 1500;
    public int sidebarWidthDp = 240;

    public static UiState fromJson(JSONObject o) {
        UiState s = new UiState();
        if (o == null) {
            return s;
        }
        JSONObject gp = o.optJSONObject("globalProxy");
        s.globalProxy = gp != null ? ProxySettings.fromJson(gp) : new ProxySettings();
        s.bubbleDiameter = o.optInt("bubbleDiameter", 36);
        s.bubbleBorderColorHex = o.optString("bubbleBorderColorHex", "");
        float rawOpacity = (float) o.optDouble("bubbleOpacity", 0.5);
        if (o.optInt("bubbleOpacityVersion", 0) < BUBBLE_OPACITY_VERSION) {
            // 旧版存的是「不透明度 alpha」，方向与「透明度」相反；反转后视觉观感保持不变。
            rawOpacity = 1f - rawOpacity;
        }
        s.bubbleOpacity = clampTransparency(rawOpacity);
        s.bubbleOpacityVersion = BUBBLE_OPACITY_VERSION;
        s.bubbleClickAction = o.optInt("bubbleClickAction", 3);
        s.bubbleDoubleClickAction = o.optInt("bubbleDoubleClickAction", 1);
        s.bubbleRightClickAction = o.optInt("bubbleRightClickAction", 3);
        s.bubbleHoverDelayMs = clampHover(o.optInt("bubbleHoverDelayMs", 1500));
        s.sidebarWidthDp = o.optInt("sidebarWidthDp", 240);
        if (o.optInt("bubbleActionsVersion", 0) < BUBBLE_ACTIONS_VERSION) {
            // 旧版单击 0「无动作」、长按 2「切换主机」实际上都只弹出工具栏。
            // 迁移成 3「打开面板」，升级后单击 / 长按的手感保持不变。
            if (s.bubbleClickAction == 0) {
                s.bubbleClickAction = 3;
            }
            if (s.bubbleRightClickAction == 2) {
                s.bubbleRightClickAction = 3;
            }
        }
        s.bubbleActionsVersion = BUBBLE_ACTIONS_VERSION;
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("globalProxy", globalProxy != null ? globalProxy.toJson() : new ProxySettings().toJson());
            o.put("bubbleDiameter", bubbleDiameter);
            o.put("bubbleBorderColorHex", bubbleBorderColorHex == null ? "" : bubbleBorderColorHex);
            o.put("bubbleOpacity", (double) clampTransparency(bubbleOpacity));
            o.put("bubbleOpacityVersion", BUBBLE_OPACITY_VERSION);
            o.put("bubbleClickAction", bubbleClickAction);
            o.put("bubbleDoubleClickAction", bubbleDoubleClickAction);
            o.put("bubbleRightClickAction", bubbleRightClickAction);
            o.put("bubbleActionsVersion", BUBBLE_ACTIONS_VERSION);
            o.put("bubbleHoverDelayMs", clampHover(bubbleHoverDelayMs));
            o.put("sidebarWidthDp", sidebarWidthDp);
        } catch (Throwable t) {
            // 序列化不因 JSONObject 异常而崩溃
        }
        return o;
    }

    /** 透明度上限 90%：完全透明会让气泡找不着，保留至少 10% 底色。 */
    public static float clampTransparency(float v) {
        return v < 0f ? 0f : (v > 0.9f ? 0.9f : v);
    }

    /** 手改过界的值收敛一下，避免计时器拿到负数或超大数。 */
    private static int clampHover(int v) {
        return v < 0 ? 0 : (v > 10000 ? 10000 : v);
    }
}
