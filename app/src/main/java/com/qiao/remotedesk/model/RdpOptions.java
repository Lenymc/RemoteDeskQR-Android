package com.qiao.remotedesk.model;

import org.json.JSONObject;

/**
 * 显示、重定向与高级选项。字段全部 public，最终映射为内嵌 FreeRDP 的建链参数。
 */
public class RdpOptions {

    public int desktopWidth = 1920;
    public int desktopHeight = 1080;
    public int colorDepth = 32;
    /** 远端 Windows 显示缩放百分比（RDP 建链时声明的 desktopScaleFactor，100~500）。 */
    public int desktopScale = 100;
    public int connectionType = 7;
    public int audio = 0; // 0=客户端播放 1=远端播放 2=不播放

    public boolean fullScreen = false;
    public boolean useMultiMon = false;
    public boolean dynamicResolution = true;
    public boolean redirectClipboard = true;
    public boolean redirectPrinters = false;
    public boolean redirectSmartCards = false;
    public boolean redirectComPorts = false;
    public boolean redirectPosDevices = false;
    public boolean redirectDrives = false;
    public boolean audioCapture = false;
    public boolean ignoreServerCertificate = true;
    public boolean enableNla = true;
    public boolean autoReconnect = true;
    public boolean desktopComposition = true;
    public boolean fontSmoothing = true;
    public boolean wallpaper = true;
    public boolean showFullWindowDrag = true;
    public boolean menuAnimations = true;
    public boolean themes = true;

    public String selectedMonitors = "";
    public String driveList = "";
    public String alternateShell = "";
    public String shellWorkingDirectory = "";

    public RdpOptions clone() {
        RdpOptions c = new RdpOptions();
        c.desktopWidth = desktopWidth;
        c.desktopHeight = desktopHeight;
        c.colorDepth = colorDepth;
        c.desktopScale = desktopScale;
        c.connectionType = connectionType;
        c.audio = audio;
        c.fullScreen = fullScreen;
        c.useMultiMon = useMultiMon;
        c.dynamicResolution = dynamicResolution;
        c.redirectClipboard = redirectClipboard;
        c.redirectPrinters = redirectPrinters;
        c.redirectSmartCards = redirectSmartCards;
        c.redirectComPorts = redirectComPorts;
        c.redirectPosDevices = redirectPosDevices;
        c.redirectDrives = redirectDrives;
        c.audioCapture = audioCapture;
        c.ignoreServerCertificate = ignoreServerCertificate;
        c.enableNla = enableNla;
        c.autoReconnect = autoReconnect;
        c.desktopComposition = desktopComposition;
        c.fontSmoothing = fontSmoothing;
        c.wallpaper = wallpaper;
        c.showFullWindowDrag = showFullWindowDrag;
        c.menuAnimations = menuAnimations;
        c.themes = themes;
        c.selectedMonitors = selectedMonitors;
        c.driveList = driveList;
        c.alternateShell = alternateShell;
        c.shellWorkingDirectory = shellWorkingDirectory;
        return c;
    }

    /** 形如 “1920 × 1080”。 */
    public String resolutionText() {
        return desktopWidth + " × " + desktopHeight;
    }

    /**
     * 「桌面体验」六项打包成位掩码，交给 JNI 写入 FreeRDP 的 PerformanceFlags。
     * 位：0 壁纸 / 1 字体平滑 / 2 桌面组合 / 3 显示窗口拖动内容 / 4 菜单动画 / 5 主题。
     */
    public int performanceMask() {
        int m = 0;
        if (wallpaper) m |= 1;
        if (fontSmoothing) m |= 2;
        if (desktopComposition) m |= 4;
        if (showFullWindowDrag) m |= 8;
        if (menuAnimations) m |= 16;
        if (themes) m |= 32;
        return m;
    }

    public static RdpOptions fromJson(JSONObject o) {
        RdpOptions r = new RdpOptions();
        if (o == null) {
            return r;
        }
        r.desktopWidth = o.optInt("desktopWidth", 1920);
        r.desktopHeight = o.optInt("desktopHeight", 1080);
        r.colorDepth = o.optInt("colorDepth", 32);
        r.desktopScale = o.optInt("desktopScale", 100);
        r.connectionType = o.optInt("connectionType", 7);
        r.audio = o.optInt("audio", 0);
        r.fullScreen = o.optBoolean("fullScreen", false);
        r.useMultiMon = o.optBoolean("useMultiMon", false);
        r.dynamicResolution = o.optBoolean("dynamicResolution", true);
        r.redirectClipboard = o.optBoolean("redirectClipboard", true);
        r.redirectPrinters = o.optBoolean("redirectPrinters", false);
        r.redirectSmartCards = o.optBoolean("redirectSmartCards", false);
        r.redirectComPorts = o.optBoolean("redirectComPorts", false);
        r.redirectPosDevices = o.optBoolean("redirectPosDevices", false);
        r.redirectDrives = o.optBoolean("redirectDrives", false);
        r.audioCapture = o.optBoolean("audioCapture", false);
        r.ignoreServerCertificate = o.optBoolean("ignoreServerCertificate", true);
        r.enableNla = o.optBoolean("enableNla", true);
        r.autoReconnect = o.optBoolean("autoReconnect", true);
        r.desktopComposition = o.optBoolean("desktopComposition", true);
        r.fontSmoothing = o.optBoolean("fontSmoothing", true);
        r.wallpaper = o.optBoolean("wallpaper", true);
        r.showFullWindowDrag = o.optBoolean("showFullWindowDrag", true);
        r.menuAnimations = o.optBoolean("menuAnimations", true);
        r.themes = o.optBoolean("themes", true);
        r.selectedMonitors = o.optString("selectedMonitors", "");
        r.driveList = o.optString("driveList", "");
        r.alternateShell = o.optString("alternateShell", "");
        r.shellWorkingDirectory = o.optString("shellWorkingDirectory", "");
        return r;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("desktopWidth", desktopWidth);
            o.put("desktopHeight", desktopHeight);
            o.put("colorDepth", colorDepth);
            o.put("desktopScale", desktopScale);
            o.put("connectionType", connectionType);
            o.put("audio", audio);
            o.put("fullScreen", fullScreen);
            o.put("useMultiMon", useMultiMon);
            o.put("dynamicResolution", dynamicResolution);
            o.put("redirectClipboard", redirectClipboard);
            o.put("redirectPrinters", redirectPrinters);
            o.put("redirectSmartCards", redirectSmartCards);
            o.put("redirectComPorts", redirectComPorts);
            o.put("redirectPosDevices", redirectPosDevices);
            o.put("redirectDrives", redirectDrives);
            o.put("audioCapture", audioCapture);
            o.put("ignoreServerCertificate", ignoreServerCertificate);
            o.put("enableNla", enableNla);
            o.put("autoReconnect", autoReconnect);
            o.put("desktopComposition", desktopComposition);
            o.put("fontSmoothing", fontSmoothing);
            o.put("wallpaper", wallpaper);
            o.put("showFullWindowDrag", showFullWindowDrag);
            o.put("menuAnimations", menuAnimations);
            o.put("themes", themes);
            o.put("selectedMonitors", selectedMonitors == null ? "" : selectedMonitors);
            o.put("driveList", driveList == null ? "" : driveList);
            o.put("alternateShell", alternateShell == null ? "" : alternateShell);
            o.put("shellWorkingDirectory", shellWorkingDirectory == null ? "" : shellWorkingDirectory);
        } catch (Throwable t) {
            // 序列化不因 JSONObject 异常而崩溃
        }
        return o;
    }
}
