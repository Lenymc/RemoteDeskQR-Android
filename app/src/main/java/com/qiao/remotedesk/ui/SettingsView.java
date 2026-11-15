package com.qiao.remotedesk.ui;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;

import com.qiao.remotedesk.R;
import com.qiao.remotedesk.model.ProxyType;
import com.qiao.remotedesk.model.UiState;

/** 全局设置标签页：全局代理、悬浮气泡。改动即时生效并落盘。 */
public class SettingsView extends LinearLayout {

    public interface Callback {
        UiState uiState();

        void onSave(UiState s);

        /** 保存完成后关闭全局设置标签并回到主页。 */
        void onCloseSettings();

        void onStatus(String text, int color);
    }

    private static final String[] PROXY_TYPES = {"直连", "HTTP 代理（CONNECT）", "SOCKS5 代理", "SOCKS4 / 4a 代理"};
    private static final String[] BUBBLE_SIZES = {"小 30", "中 36", "大 42"};
    private static final int[] BUBBLE_VALUES = {30, 36, 42};
    /** 下标即 SessionView.execute() 的动作码，末项由 UiState.ACTION_TOGGLE_KEYBOARD 对应。 */
    private static final String[] ACTIONS = {"无动作", "最小化 / 恢复", "切换主机", "打开面板",
            "全屏", "恢复常态", "打开/关闭虚拟键盘"};
    private static final String[] BORDER_COLORS = {"默认白", "浅蓝", "浅绿", "琥珀", "玫红", "浅灰"};
    private static final int[] BORDER_VALUES = {0xFFFFFFFF, 0xFF93C5FD, 0xFF86EFAC,
            0xFFFCD34D, 0xFFF9A8D4, 0xFFCBD5E1};

    private final Callback cb;
    private final UiState ui;
    private final LinearLayout form;
    private final java.util.Map<String, View> fields = new java.util.HashMap<String, View>();

    public SettingsView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        this.ui = cb.uiState();
        setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dimen(c, R.dimen.content_pad);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(Theme.CONTENT);

        LinearLayout head = Theme.horizontal(c);
        android.widget.TextView title = Theme.title(c, "全局设置");
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        android.widget.Button save = Theme.button(c, "保存", Theme.BTN_PRIMARY);
        save.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                collect();
                cb.onSave(ui);
                cb.onCloseSettings();
            }
        });
        head.addView(save);
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        form = Theme.vertical(c);
        form.setBackground(Theme.rounded(Theme.SURFACE, 6, c));
        form.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(c);
        sv.addView(form, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Theme.dp(c, 10);
        addView(sv, lp);
        build();
    }

    private void section(String text) {
        android.widget.TextView t = Theme.title(getContext(), text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(getContext(), 14);
        lp.bottomMargin = Theme.dp(getContext(), 4);
        t.setLayoutParams(lp);
        form.addView(t);
    }

    private void note(String text) {
        android.widget.TextView t = Theme.hint(getContext(), text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(getContext(), 4);
        t.setLayoutParams(lp);
        form.addView(t);
    }

    private void text(String key, String label, String value, boolean pwd) {
        EditText e = Theme.input(getContext(), label, pwd);
        e.setText(value == null ? "" : value);
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

    /** 多选框放在文字描述之后：左侧沿用表单标签列，右侧只留无文字的复选框。 */
    private void check(String key, String label, boolean value) {
        final CheckBox box = new CheckBox(getContext());
        box.setText("");
        box.setChecked(value);
        LinearLayout row = Theme.formRow(getContext(), label, box);
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                box.toggle();
            }
        });
        form.addView(row);
        fields.put(key, box);
    }

    private void build() {
        Context c = getContext();
        section("全局代理");
        spinner("ptype", "代理类型", PROXY_TYPES, ui.globalProxy.type.ordinal());
        text("phost", "代理服务器", ui.globalProxy.host, false);
        number("pport", "代理端口", ui.globalProxy.port);
        text("puser", "账号", ui.globalProxy.userName, false);
        text("ppass", "密码", ui.globalProxy.password, true);
        check("remoteDns", "由代理解析目标域名", ui.globalProxy.remoteDns);
        number("ptimeout", "建链超时（秒）", ui.globalProxy.connectTimeoutSeconds);
        note("主机配置里把「代理来源」选成「使用全局代理」即共用这份配置。");

        section("悬浮气泡");
        spinner("size", "直径", BUBBLE_SIZES, sizeIndex(ui.bubbleDiameter));
        spinner("border", "边框颜色", BORDER_COLORS, borderIndex(ui.bubbleBorderColorHex));
        android.widget.SeekBar opacityBar = Theme.seekBar(c, 90,
                Math.round(UiState.clampTransparency(ui.bubbleOpacity) * 100));
        form.addView(Theme.formRow(c, "气泡透明度（0~90%）", opacityBar));
        fields.put("opacity", opacityBar);
        spinner("click", "单击", ACTIONS, clamp(ui.bubbleClickAction, 0, ACTIONS.length - 1));
        spinner("double", "双击", ACTIONS, clamp(ui.bubbleDoubleClickAction, 0, ACTIONS.length - 1));
        spinner("right", "长按 / 右键", ACTIONS, clamp(ui.bubbleRightClickAction, 0, ACTIONS.length - 1));
        number("hover", "悬停弹面板延时（ms）", ui.bubbleHoverDelayMs);
        note("0% 为完全不透明，数值越大越透明；上限 90%，始终保留 10% 底色便于定位气泡。气泡菜单「气泡 → 透明度」与此处共用一份配置。");
        note("单击 / 双击 / 长按动作均按上面的配置执行；选择「打开/关闭虚拟键盘」可直接切换屏幕键盘。");

        android.widget.Button reset = Theme.button(c, "恢复默认", Theme.BTN_LIGHT);
        reset.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onSave(new UiState());
                cb.onStatus("全局设置已恢复默认，重新进入本页查看", Theme.OK);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Theme.dp(c, 14);
        reset.setLayoutParams(rlp);
        form.addView(reset);
    }

    private static int sizeIndex(int v) {
        for (int i = 0; i < BUBBLE_VALUES.length; i++) {
            if (BUBBLE_VALUES[i] == v) {
                return i;
            }
        }
        return 1;
    }

    private static int borderIndex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return 0;
        }
        for (int i = 1; i < BORDER_VALUES.length; i++) {
            if (String.format("#%06X", BORDER_VALUES[i] & 0xFFFFFF).equalsIgnoreCase(hex)) {
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

    private void collect() {
        ui.globalProxy.applyType(ProxyType.values()[clamp(sel("ptype"), 0, 3)]);
        ui.globalProxy.host = str("phost").trim();
        ui.globalProxy.port = clamp(num("pport", 1080), 1, 65535);
        ui.globalProxy.userName = str("puser");
        ui.globalProxy.password = str("ppass");
        ui.globalProxy.remoteDns = bool("remoteDns", true);
        ui.globalProxy.connectTimeoutSeconds = clamp(num("ptimeout", 15), 3, 120);

        ui.bubbleDiameter = BUBBLE_VALUES[clamp(sel("size"), 0, 2)];
        int border = sel("border");
        ui.bubbleBorderColorHex = border == 0 ? ""
                : String.format("#%06X", BORDER_VALUES[border] & 0xFFFFFF);
        View bar = fields.get("opacity");
        if (bar instanceof android.widget.SeekBar) {
            ui.bubbleOpacity = UiState.clampTransparency(
                    ((android.widget.SeekBar) bar).getProgress() / 100f);
        }
        ui.bubbleClickAction = clamp(sel("click"), 0, ACTIONS.length - 1);
        ui.bubbleDoubleClickAction = clamp(sel("double"), 0, ACTIONS.length - 1);
        ui.bubbleRightClickAction = clamp(sel("right"), 0, ACTIONS.length - 1);
        ui.bubbleHoverDelayMs = clamp(num("hover", 1500), 0, 10000);
    }
}
