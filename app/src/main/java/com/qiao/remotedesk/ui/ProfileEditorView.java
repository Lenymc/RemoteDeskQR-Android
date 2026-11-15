package com.qiao.remotedesk.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.qiao.remotedesk.R;
import com.qiao.remotedesk.model.ConnectionProfile;
import com.qiao.remotedesk.model.ProxyType;
import com.qiao.remotedesk.model.UiState;

import java.util.HashMap;
import java.util.Map;

/** 连接配置页：目标 / 代理 / 显示 / 重定向 / 高级 五个子标签，字段与原 Windows 版对齐。 */
public class ProfileEditorView extends LinearLayout {

    public interface Callback {
        void onSave(ConnectionProfile p);

        void onClose();

        UiState uiState();

        void onStatus(String text, int color);
    }

    private static final String[] PAGES = {"目标", "代理", "显示", "重定向", "高级"};
    private static final String[] PROXY_TYPES = {"直连", "HTTP 代理（CONNECT）", "SOCKS5 代理", "SOCKS4 / 4a 代理"};
    private static final String[] DEPTHS = {"15 位", "16 位", "24 位", "32 位"};
    private static final int[] DEPTH_VALUES = {15, 16, 24, 32};
    /** 与会话气泡菜单「视图」共用 RdpOptions.desktopScale 的四档缩放。 */
    private static final String[] SCALES = {"100%", "125%", "150%", "175%"};
    private static final int[] SCALE_VALUES = {100, 125, 150, 175};
    private static final String[] RESOLUTIONS = {"自适应（跟随窗口）", "1280 × 720", "1600 × 900",
            "1920 × 1080", "2560 × 1440"};
    private static final int[][] RES_VALUES = {{0, 0}, {1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}};
    private static final String[] AUDIO = {"在客户端播放", "在远端播放", "不播放音频"};
    private static final String[] NETWORK = {"调制解调器", "低速宽带", "卫星", "高速宽带", "WAN", "LAN", "自动检测"};

    private final ConnectionProfile p;
    private final Callback cb;
    private final Map<String, View> fields = new HashMap<String, View>();
    private final LinearLayout pageStrip;
    private final LinearLayout form;
    private final TextView heading;
    private int page = 0;

    public ProfileEditorView(Context c, ConnectionProfile p, Callback cb) {
        super(c);
        this.p = p;
        this.cb = cb;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dimen(c, R.dimen.content_pad);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(Theme.CONTENT);

        LinearLayout head = Theme.horizontal(c);
        heading = Theme.title(c, "配置 · " + p.displayTitle());
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(heading);
        android.widget.Button save = Theme.button(c, "保存", Theme.BTN_PRIMARY);
        save.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                collect();
                cb.onSave(p);
            }
        });
        head.addView(save);
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        pageStrip = Theme.horizontal(c);
        pageStrip.setBackgroundColor(Theme.TOP);
        for (int i = 0; i < PAGES.length; i++) {
            final int idx = i;
            TextView tab = Theme.label(c, PAGES[i], Theme.TEXT_LIGHT, R.dimen.text_body);
            tab.setPadding(Theme.dp(c, 14), Theme.dp(c, 8), Theme.dp(c, 14), Theme.dp(c, 8));
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    collect();
                    page = idx;
                    build();
                }
            });
            pageStrip.addView(tab);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        plp.topMargin = Theme.dp(c, 10);
        addView(pageStrip, plp);

        form = Theme.vertical(c);
        form.setBackground(Theme.rounded(Theme.SURFACE, 6, c));
        form.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(c);
        sv.addView(form, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        flp.topMargin = Theme.dp(c, 10);
        addView(sv, flp);
        build();
    }

    private void build() {
        Context c = getContext();
        for (int i = 0; i < pageStrip.getChildCount(); i++) {
            TextView t = (TextView) pageStrip.getChildAt(i);
            t.setTextColor(i == page ? 0xFFFFFFFF : 0xFF9AA5B8);
            t.setBackgroundColor(i == page ? Theme.ACCENT : Theme.TOP);
        }
        heading.setText("配置 · " + p.displayTitle());
        fields.clear();
        form.removeAllViews();
        switch (page) {
            case 0:
                buildTarget();
                break;
            case 1:
                buildProxy();
                break;
            case 2:
                buildDisplay();
                break;
            case 3:
                buildRedirect();
                break;
            default:
                buildAdvanced();
                break;
        }
    }

    private void section(String text) {
        TextView t = Theme.title(getContext(), text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(getContext(), 12);
        lp.bottomMargin = Theme.dp(getContext(), 4);
        t.setLayoutParams(lp);
        form.addView(t);
    }

    private void note(String text) {
        TextView t = Theme.hint(getContext(), text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(getContext(), 4);
        t.setLayoutParams(lp);
        form.addView(t);
    }

    private void text(String key, String label, String value, boolean password) {
        EditText e = Theme.input(getContext(), label, password);
        e.setText(value == null ? "" : value);
        e.setSelection(e.getText().length());
        form.addView(Theme.formRow(getContext(), label, e));
        fields.put(key, e);
    }

    private void number(String key, String label, int value) {
        EditText e = Theme.input(getContext(), label, false);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        form.addView(Theme.formRow(getContext(), label, e));
        fields.put(key, e);
    }

    private void spinner(String key, String label, String[] items, int index) {
        Spinner s = Theme.spinner(getContext(), items);
        s.setSelection(index < 0 || index >= items.length ? 0 : index);
        form.addView(Theme.formRow(getContext(), label, s));
        fields.put(key, s);
    }

    private void check(String key, String label, boolean value) {
        CheckBox box = Theme.check(getContext(), label, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(getContext(), 4);
        box.setLayoutParams(lp);
        form.addView(box);
        fields.put(key, box);
    }

    private void buildTarget() {
        section("目标主机");
        text("name", "连接名称", p.name, false);
        text("host", "目标主机 / 端口中的主机", p.host, false);
        number("port", "端口", p.port);
        text("user", "用户名 / 域", p.userName, false);
        text("domain", "域（可留空）", p.domain, false);
        text("password", "密码", p.password, true);
        text("note", "备注", p.note, false);
        note("目标填内网 IP 或域名；勾选「由代理解析域名」时域名交给代理解析，本机不需要能解析。");
        note("密码经 Android Keystore (AES/GCM) 加密后落盘，不写明文、不进日志。");
        note("本连接隧道入口：" + (p.loopbackAddress.isEmpty() ? "连接时自动分配" : p.loopbackAddress));
    }

    private void buildProxy() {
        section("代理来源");
        check("useGlobal", "使用全局代理（在「设置」里配置）", p.useGlobalProxy);
        UiState ui = cb.uiState();
        note("当前全局代理：" + (ui == null || ui.globalProxy == null ? "未配置"
                : ui.globalProxy.summary()));
        section("自定义代理配置");
        spinner("ptype", "代理类型", PROXY_TYPES, p.proxy.type.ordinal());
        text("phost", "代理服务器", p.proxy.host, false);
        number("pport", "代理端口", p.proxy.port);
        text("puser", "账号", p.proxy.userName, false);
        text("ppass", "密码", p.proxy.password, true);
        check("remoteDns", "由代理解析目标域名", p.proxy.remoteDns);
        number("ptimeout", "建链超时（秒）", p.proxy.connectTimeoutSeconds);
        note("HTTP 代理仅支持 Basic 认证；SOCKS5 支持无认证 / 用户名密码；SOCKS4 只有 USERID。");
        note("CONNECT 到 3389 被企业代理策略拦截时返回 403 / 502，需要在代理侧放行，客户端无法绕过。");
    }

    private void buildDisplay() {
        section("显示");
        spinner("preset", "分辨率预设", RESOLUTIONS, presetIndex());
        spinner("scale", "缩放比例", SCALES, scaleIndex());
        spinner("depth", "颜色深度", DEPTHS, depthIndex());
        check("dynamic", "动态分辨率（跟随画面区域）", p.options.dynamicResolution);
        check("fullscreen", "以全屏启动", p.options.fullScreen);
        check("multimon", "使用多显示器", p.options.useMultiMon);
        text("monitors", "显示器编号（如 0,1）", p.options.selectedMonitors, false);
        note("缩放比例通过缩小请求桌面尺寸实现（放大时清晰度下降）；缩放、颜色深度与「高级 → 桌面体验」均在 RDP 建链时协商，保存后需重新连接会话；会话内可用气泡菜单「视图」切换缩放并自动重连。");
    }

    private void buildRedirect() {
        section("重定向");
        check("clipboard", "剪贴板", p.options.redirectClipboard);
        check("drives", "驱动器", p.options.redirectDrives);
        text("driveList", "盘符（如 C,D，留空为全部）", p.options.driveList, false);
        check("printers", "打印机", p.options.redirectPrinters);
        check("smartcards", "智能卡", p.options.redirectSmartCards);
        check("comports", "串口", p.options.redirectComPorts);
        check("pos", "POS 设备", p.options.redirectPosDevices);
        spinner("audio", "音频输出", AUDIO, clamp(p.options.audio, 0, 2));
        check("audiocapture", "麦克风录制重定向", p.options.audioCapture);
        note("重定向能力由内嵌 FreeRDP 实现；本页保存的是本次会话使用的参数。");
    }

    private void buildAdvanced() {
        section("安全与连接");
        check("ignorecert", "忽略服务器证书不匹配", p.options.ignoreServerCertificate);
        check("nla", "网络级别身份验证（NLA）", p.options.enableNla);
        spinner("network", "连接类型", NETWORK, clamp(p.options.connectionType - 1, 0, 6));
        check("autoreconnect", "断线自动重连", p.options.autoReconnect);
        section("桌面体验");
        check("wallpaper", "壁纸", p.options.wallpaper);
        check("fontsmooth", "字体平滑", p.options.fontSmoothing);
        check("composition", "桌面组合", p.options.desktopComposition);
        check("drag", "显示窗口拖动内容", p.options.showFullWindowDrag);
        check("menuanim", "菜单动画", p.options.menuAnimations);
        check("themes", "主题", p.options.themes);
        section("RemoteApp");
        text("shell", "程序路径（留空为完整桌面）", p.options.alternateShell, false);
        text("workdir", "工作目录", p.options.shellWorkingDirectory, false);
    }

    private int presetIndex() {
        for (int i = 1; i < RES_VALUES.length; i++) {
            if (RES_VALUES[i][0] == p.options.desktopWidth
                    && RES_VALUES[i][1] == p.options.desktopHeight) {
                return i;
            }
        }
        return 0;
    }

    private int depthIndex() {
        for (int i = 0; i < DEPTH_VALUES.length; i++) {
            if (DEPTH_VALUES[i] == p.options.colorDepth) {
                return i;
            }
        }
        return 3;
    }

    private int scaleIndex() {
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            if (SCALE_VALUES[i] == p.options.desktopScale) {
                return i;
            }
        }
        return 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private String str(String key) {
        View v = fields.get(key);
        return v instanceof EditText ? ((EditText) v).getText().toString() : "";
    }

    private int num(String key, int fallback) {
        try {
            return Integer.parseInt(str(key).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int sel(String key) {
        View v = fields.get(key);
        return v instanceof Spinner ? ((Spinner) v).getSelectedItemPosition() : 0;
    }

    private boolean bool(String key, boolean fallback) {
        View v = fields.get(key);
        return v instanceof CheckBox ? ((CheckBox) v).isChecked() : fallback;
    }

    /** 把当前页控件的值写回 profile。切换子标签和保存前都要调用。 */
    private void collect() {
        switch (page) {
            case 0:
                p.name = str("name");
                p.host = str("host").trim();
                p.port = clamp(num("port", 3389), 1, 65535);
                p.userName = str("user");
                p.domain = str("domain");
                p.password = str("password");
                p.note = str("note");
                break;
            case 1:
                p.useGlobalProxy = bool("useGlobal", p.useGlobalProxy);
                p.proxy.applyType(ProxyType.values()[clamp(sel("ptype"), 0, 3)]);
                p.proxy.host = str("phost").trim();
                p.proxy.port = clamp(num("pport", 1080), 1, 65535);
                p.proxy.userName = str("puser");
                p.proxy.password = str("ppass");
                p.proxy.remoteDns = bool("remoteDns", true);
                p.proxy.connectTimeoutSeconds = clamp(num("ptimeout", 15), 3, 120);
                break;
            case 2:
                int preset = sel("preset");
                // 宽高不再手工输入：仅由分辨率预设改写，自适应档保留原值
                if (preset > 0) {
                    p.options.desktopWidth = RES_VALUES[preset][0];
                    p.options.desktopHeight = RES_VALUES[preset][1];
                }
                p.options.desktopScale = SCALE_VALUES[clamp(sel("scale"), 0, 3)];
                p.options.colorDepth = DEPTH_VALUES[clamp(sel("depth"), 0, 3)];
                p.options.dynamicResolution = preset == 0 || bool("dynamic", true);
                p.options.fullScreen = bool("fullscreen", p.options.fullScreen);
                p.options.useMultiMon = bool("multimon", p.options.useMultiMon);
                p.options.selectedMonitors = str("monitors");
                break;
            case 3:
                p.options.redirectClipboard = bool("clipboard", true);
                p.options.redirectDrives = bool("drives", p.options.redirectDrives);
                p.options.driveList = str("driveList");
                p.options.redirectPrinters = bool("printers", p.options.redirectPrinters);
                p.options.redirectSmartCards = bool("smartcards", p.options.redirectSmartCards);
                p.options.redirectComPorts = bool("comports", p.options.redirectComPorts);
                p.options.redirectPosDevices = bool("pos", p.options.redirectPosDevices);
                p.options.audio = sel("audio");
                p.options.audioCapture = bool("audiocapture", p.options.audioCapture);
                break;
            default:
                p.options.ignoreServerCertificate = bool("ignorecert", true);
                p.options.enableNla = bool("nla", true);
                p.options.connectionType = clamp(sel("network") + 1, 1, 7);
                p.options.autoReconnect = bool("autoreconnect", true);
                p.options.wallpaper = bool("wallpaper", p.options.wallpaper);
                p.options.fontSmoothing = bool("fontsmooth", p.options.fontSmoothing);
                p.options.desktopComposition = bool("composition", p.options.desktopComposition);
                p.options.showFullWindowDrag = bool("drag", p.options.showFullWindowDrag);
                p.options.menuAnimations = bool("menuanim", p.options.menuAnimations);
                p.options.themes = bool("themes", p.options.themes);
                p.options.alternateShell = str("shell");
                p.options.shellWorkingDirectory = str("workdir");
                break;
        }
    }
}
