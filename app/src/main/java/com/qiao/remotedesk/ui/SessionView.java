package com.qiao.remotedesk.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qiao.remotedesk.R;
import com.qiao.remotedesk.model.CardColors;
import com.qiao.remotedesk.model.ConnectionProfile;
import com.qiao.remotedesk.model.UiState;
import com.qiao.remotedesk.net.ConnectivityTester;
import com.qiao.remotedesk.net.TunnelServer;
import com.qiao.remotedesk.rdp.FreeRdpSession;
import com.qiao.remotedesk.rdp.RdpInputDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 会话页：隧道端点、实时字节统计、建链耗时指示灯、悬浮气泡与功能面板。
 * 远端位图由内嵌 FreeRDP 直接渲染，本视图负责会话的生命周期、诊断与交互。
 */
public class SessionView extends FrameLayout {

    public interface Callback {
        void onDisconnect(ConnectionProfile p);

        void onReconnect(ConnectionProfile p);

        void onFullscreen(boolean on);

        void onRestore();

        void onMinimize();

        void onSwitchHost();

        void onCopy(String text);

        void onStatus(String text, int color);

        UiState uiState();

        void onSaveUiState(UiState s);

        /** 把会话内改动过的连接配置（分辨率 / 色深）落盘。 */
        void onSaveProfile(ConnectionProfile p);

        void onLog(String line);
    }

    private static final String PREF_NAME = "remotedesk_prefs";
    private static final String KEY_INPUT_MODE = "input_mode";

    private static final String[] RESOLUTIONS = {"自适应", "1280 × 720", "1600 × 900", "1920 × 1080"};
    private static final int[][] RES_VALUES = {{0, 0}, {1280, 720}, {1600, 900}, {1920, 1080}};
    private static final String[] DEPTHS = {"16 位", "24 位", "32 位"};
    private static final int[] DEPTH_VALUES = {16, 24, 32};

    /** 竖状工具栏：一列功能图标，点任一项在左侧展开二级列表；「键盘」是单击开关。 */
    private static final String[] TOOL_NAMES = {
            "显示", "窗口", "缩放", "模式", "气泡", "键盘", "会话"};
    private static final int TOOL_DISPLAY = 0;
    private static final int TOOL_WINDOW = 1;
    private static final int TOOL_VIEW = 2;
    private static final int TOOL_MODE = 3;
    private static final int TOOL_BUBBLE = 4;
    private static final int TOOL_KEYBOARD = 5;
    private static final int TOOL_SESSION = 6;
    /** 一级顺序与自绘图标素材编号解耦；图标素材按 显示/模式/窗口/气泡/缩放/会话/键盘 编号。 */
    private static final int[] TOOL_ICONS = {0, 2, 4, 1, 3, 6, 5};
    /** 透明程度档位：0% 完全不透明，90% 最透明；不再提供 100%（会完全看不见气泡）。 */
    private static final int[] OPACITY_VALUES = {0, 20, 40, 60, 80, 90};
    /** 远端 Windows 显示缩放档位，对应 RDP TS_UD_CS_CORE.desktopScaleFactor。 */
    private static final int[] SCALE_VALUES = {100, 125, 150, 175};
    private static final String[] SCALE_NAMES = {
            "远程缩放 · 100%", "远程缩放 · 125%", "远程缩放 · 150%", "远程缩放 · 175%"};

    private final ConnectionProfile p;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Long> samples = new ArrayList<Long>();
    private TunnelServer tunnel;
    private BubbleView bubble;
    private LinearLayout toolbar;
    private LinearLayout submenu;
    private LinearLayout submenuItems;
    private TextView submenuTitle;
    private final View[] toolRows = new View[TOOL_NAMES.length];
    private int activeTool = -1;
    private OnScreenKeyboard keyboard;
    private boolean keyboardVisible;
    private TextView keyboardToolLabel;
    private LinearLayout infoPanel;
    private View centerPane;
    private RemoteView remote;
    private FreeRdpSession rdpSession;
    /** 本次建链请求给 FreeRDP 的桌面尺寸；服务端可能回退到别的值。 */
    private int requestedW;
    private int requestedH;
    private TextView statusLine;
    private TextView endpointLine;
    private TextView statsLine;
    private TextView latencyLine;
    private TextView diagLine;
    private boolean destroyed;

    public SessionView(Context c, ConnectionProfile p, TunnelServer tunnel, Callback cb) {
        super(c);
        this.p = p;
        this.tunnel = tunnel;
        this.cb = cb;
        setBackgroundColor(0xFF20262E);
        build();
        ui.post(statsTask);
        ui.postDelayed(sampleTask, 1500);
        // 构造时隧道一般已就绪；若稍后由 setTunnel 补上，那边会再次触发（内部有去重）。
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                startSession();
            }
        }, 400);
    }

    /** 点击工具栏 / 二级列表 / 气泡以外的任意位置，整套菜单收起。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN
                && toolbar != null && toolbar.getVisibility() == VISIBLE) {
            float x = ev.getRawX();
            float y = ev.getRawY();
            if (!hit(toolbar, x, y) && !hit(submenu, x, y) && !hit(bubble, x, y)) {
                closeToolbar();
                // 拖键盘顶部把手时，收起菜单的同时把按下事件交给把手，保证拖动连续
                if (keyboard != null && keyboardVisible && keyboard.isResizeHandleAt(x, y)) {
                    return super.dispatchTouchEvent(ev);
                }
                return true; /* 消费掉，不传给下层 */
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private static boolean hit(View v, float x, float y) {
        if (v == null || v.getVisibility() != VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + v.getWidth()
                && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    private void toggleToolbar() {
        if (toolbar == null) {
            return;
        }
        if (toolbar.getVisibility() == VISIBLE) {
            closeToolbar();
            return;
        }
        activeTool = -1;
        for (View row : toolRows) {
            if (row != null) {
                row.setBackgroundColor(0x00000000);
            }
        }
        submenu.setVisibility(GONE);
        toolbar.setVisibility(VISIBLE);
        syncMenuPosition();
    }

    /** 气泡上下拖动后，菜单与二级列表共用它的垂直偏移，始终与气泡同高。 */
    private void syncMenuPosition() {
        if (bubble == null) {
            return;
        }
        float ty = bubble.getTranslationY();
        if (toolbar != null) {
            toolbar.setTranslationY(ty);
        }
        if (submenu != null) {
            submenu.setTranslationY(ty);
        }
    }

    private void closeToolbar() {
        if (toolbar != null) {
            toolbar.setVisibility(GONE);
        }
        if (submenu != null) {
            submenu.setVisibility(GONE);
        }
    }

    public void setTunnel(TunnelServer t) {
        tunnel = t;
        updateInfo();
        if (t != null) {
            startSession();
        }
    }

    public String endpoint() {
        return tunnel == null ? "（隧道未就绪）" : tunnel.bindAddress() + ":" + tunnel.localPort();
    }

    private void build() {
        Context c = getContext();
        UiState state = cb.uiState();
        int pad = Theme.dp(c, 16);

        LinearLayout main = Theme.vertical(c);
        main.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout info = Theme.vertical(c);
        info.setBackgroundColor(0xFF2A3240);
        info.setPadding(pad, Theme.dp(c, 10), pad, Theme.dp(c, 10));
        statusLine = Theme.label(c, "", Color.WHITE, R.dimen.text_title);
        statusLine.getPaint().setFakeBoldText(true);
        info.addView(statusLine);
        endpointLine = Theme.label(c, "", 0xFFBFC9DA, R.dimen.text_body);
        endpointLine.setPadding(0, Theme.dp(c, 4), 0, 0);
        info.addView(endpointLine);
        statsLine = Theme.label(c, "", 0xFF9AA8BC, R.dimen.text_small);
        statsLine.setPadding(0, Theme.dp(c, 2), 0, 0);
        info.addView(statsLine);
        // 顶部信息条（标题 / 隧道入口 / 流量统计）不再挂到界面上：
        // 保留对象只是为了复用状态更新逻辑，画面区直接从顶栏开始，最大化可视面积。
        info.setVisibility(GONE);
        infoPanel = info;

        LinearLayout center = Theme.vertical(c);
        center.setGravity(Gravity.CENTER);
        center.setPadding(pad, pad, pad, pad);
        center.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView head = Theme.label(c, "远程桌面会话", 0xFFE7EBF3, R.dimen.text_title);
        head.getPaint().setFakeBoldText(true);
        head.setGravity(Gravity.CENTER);
        center.addView(head);
        TextView desc = Theme.label(c,
                "本应用已建立到目标主机的代理隧道，远端画面由内嵌 FreeRDP 在应用内渲染。\n"
                        + "下面是本次会话使用的回环隧道地址；连接状态与诊断信息见下方。",
                0xFF9AA8BC, R.dimen.text_body);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, Theme.dp(c, 14), 0, Theme.dp(c, 14));
        center.addView(desc);
        TextView addr = Theme.label(c, endpoint(), 0xFF7DE2A8, R.dimen.text_title);
        addr.getPaint().setFakeBoldText(true);
        addr.setBackgroundColor(0xFF333F50);
        addr.setPadding(Theme.dp(c, 14), Theme.dp(c, 8), Theme.dp(c, 14), Theme.dp(c, 8));
        center.addView(addr);
        endpointLine.setTag(addr);

        LinearLayout buttons = Theme.horizontal(c);
        buttons.setGravity(Gravity.CENTER);
        android.widget.Button copy = Theme.button(c, "复制隧道地址", Theme.BTN_DARK);
        copy.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onCopy(endpoint());
            }
        });
        buttons.addView(copy);
        center.addView(buttons);

        LinearLayout bottomButtons = Theme.horizontal(c);
        bottomButtons.setGravity(Gravity.CENTER);
        android.widget.Button reconnect = Theme.button(c, "重新连接", Theme.BTN_DARK);
        reconnect.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onReconnect(p);
            }
        });
        bottomButtons.addView(reconnect);
        android.widget.Button disconnect = Theme.button(c, "断开", Theme.BTN_DARK);
        disconnect.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onDisconnect(p);
            }
        });
        bottomButtons.addView(disconnect);
        center.addView(bottomButtons);

        latencyLine = Theme.label(c, "建链 — ms", 0xFF9AA8BC, R.dimen.text_small);
        latencyLine.setPadding(0, Theme.dp(c, 12), 0, 0);
        center.addView(latencyLine);
        diagLine = Theme.label(c, "", 0xFF8A93A5, R.dimen.text_small);
        diagLine.setPadding(0, Theme.dp(c, 6), 0, 0);
        center.addView(diagLine);
        // 远端画面与诊断面板叠在同一个内容区：连接成功后画面接管显示。
        FrameLayout contentBox = new FrameLayout(c);
        contentBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        contentBox.addView(center, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        remote = new RemoteView(c);
        remote.setInputMode(loadInputMode());
        remote.setVisibility(GONE);
        contentBox.addView(remote, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        main.addView(contentBox);
        centerPane = center;
        addView(main);

        // 屏幕键盘：叠在会话内容区底部，由气泡菜单「键盘」开关控制
        keyboard = new OnScreenKeyboard(c, new OnScreenKeyboard.Sink() {
            @Override
            public void tapKey(int keyCode) {
                remote.tapKey(OnScreenKeyboard.normalize(keyCode));
            }

            @Override
            public void holdKey(int keyCode, boolean down) {
                remote.holdKey(OnScreenKeyboard.normalize(keyCode), down);
            }

        });
        // 高度由用户拖动上边缘决定，读取上次保存值；底边固定，变高时向上扩展
        LayoutParams klp = new LayoutParams(LayoutParams.MATCH_PARENT,
                keyboard.savedHeight(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        klp.leftMargin = Theme.dp(c, 12);
        klp.rightMargin = Theme.dp(c, 12);
        klp.bottomMargin = Theme.dp(c, 8);
        keyboard.setVisibility(GONE);
        addView(keyboard, klp);

        bubble = new BubbleView(c, new BubbleView.Callback() {
            @Override
            public void onAction(int action) {
                // 单击 / 双击 / 长按各自执行全局设置里配置的动作
                UiState s = cb.uiState();
                int code;
                if (action == BubbleView.ACT_SWITCH) {
                    code = s.bubbleDoubleClickAction;
                } else if (action == BubbleView.ACT_MENU) {
                    code = s.bubbleRightClickAction;
                } else {
                    code = s.bubbleClickAction;
                }
                execute(code);
            }

            @Override
            public void onMove(float translationY) {
                syncMenuPosition();
            }
        }, state.bubbleDiameter, CardColors.parseColor(p.cardColor, p.id));
        bubble.setOpacity(state.bubbleOpacity);
        if (state.bubbleBorderColorHex != null && !state.bubbleBorderColorHex.isEmpty()) {
            try {
                bubble.setBorderColor(Color.parseColor(state.bubbleBorderColorHex));
            } catch (Exception ignored) {
            }
        }
        LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        addView(bubble, blp);

        // 竖状一级图标栏贴气泡左侧；右边缘距屏幕右边正好半个气泡（= 气泡半径）
        int gap = Theme.dp(c, state.bubbleDiameter / 2);
        int toolbarWidth = Theme.dp(c, 116);
        toolbar = buildToolbar();
        LayoutParams tlp = new LayoutParams(toolbarWidth,
                LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        tlp.rightMargin = gap;
        toolbar.setVisibility(GONE);
        addView(toolbar, tlp);

        submenu = buildSubmenu();
        LayoutParams slp = new LayoutParams(Theme.dp(c, 188),
                LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        slp.rightMargin = gap + toolbarWidth + Theme.dp(c, 8);
        submenu.setVisibility(GONE);
        addView(submenu, slp);

        // 键盘与右侧工具栏允许重叠；显式保证气泡 / 工具栏 / 二级列表始终在键盘之上可点
        bubble.bringToFront();
        toolbar.bringToFront();
        submenu.bringToFront();

        updateInfo();
    }

    private int loadInputMode() {
        return getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_INPUT_MODE, RemoteView.MODE_TOUCH);
    }

    private void saveInputMode(int mode) {
        getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INPUT_MODE, mode).apply();
    }

    private static String modeLabel(int mode) {
        return mode == RemoteView.MODE_MOUSE ? "鼠标模式" : "触控模式";
    }

    private void setKeyboardVisible(boolean show) {
        keyboardVisible = show;
        if (keyboard != null) {
            keyboard.setVisibility(show ? VISIBLE : GONE);
            if (!show) {
                // 隐藏时松开还按着的修饰键，避免远端一直保持 Ctrl/Shift 按下
                keyboard.releaseAllModifiers();
            }
        }
    }

    /** 一级：竖状单列，每行一个自绘线性图标 + 短文字。 */
    private LinearLayout buildToolbar() {
        Context c = getContext();
        LinearLayout box = Theme.vertical(c);
        box.setBackground(Theme.rounded(0xFFF8F9FC, 12, c));
        int pad = Theme.dp(c, 6);
        box.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            final int idx = i;
            LinearLayout row = Theme.horizontal(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Theme.dp(c, 8), Theme.dp(c, 4), Theme.dp(c, 6), Theme.dp(c, 4));
            ToolIcon icon = new ToolIcon(c, TOOL_ICONS[i]);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Theme.dp(c, 24), Theme.dp(c, 24));
            ilp.rightMargin = Theme.dp(c, 12);
            row.addView(icon, ilp);
            TextView label = Theme.label(c, TOOL_NAMES[i], Theme.TEXT_DARK, R.dimen.text_body);
            row.addView(label);
            if (i == TOOL_KEYBOARD) {
                keyboardToolLabel = label;
            }
            row.setBackgroundColor(0x00000000);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == TOOL_KEYBOARD) {
                        toggleKeyboard();
                    } else {
                        openSubmenu(idx);
                    }
                }
            });
            toolRows[i] = row;
            box.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, 44)));
        }
        return box;
    }

    /** 二级：在一级栏左侧级联展开的选项列表，内容随图标切换。 */
    private LinearLayout buildSubmenu() {
        Context c = getContext();
        LinearLayout box = Theme.vertical(c);
        box.setBackground(Theme.rounded(0xFFF8F9FC, 12, c));
        int pad = Theme.dp(c, 10);
        box.setPadding(pad, pad, pad, pad);
        submenuTitle = Theme.label(c, "", 0xFF1A2332, R.dimen.text_body);
        submenuTitle.getPaint().setFakeBoldText(true);
        box.addView(submenuTitle);
        submenuItems = Theme.vertical(c);
        box.addView(submenuItems, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void openSubmenu(int idx) {
        activeTool = idx;
        for (int i = 0; i < toolRows.length; i++) {
            if (toolRows[i] != null) {
                toolRows[i].setBackgroundColor(i == idx ? 0xFFE3EBFB : 0x00000000);
            }
        }
        submenuTitle.setText(TOOL_NAMES[idx]);
        submenuItems.removeAllViews();
        switch (idx) {
            case TOOL_DISPLAY:
                fillDisplay();
                break;
            case TOOL_WINDOW:
                fillWindow();
                break;
            case TOOL_VIEW:
                fillView();
                break;
            case TOOL_MODE:
                fillMode();
                break;
            case TOOL_BUBBLE:
                fillBubble();
                break;
            case TOOL_SESSION:
            default:
                fillSession();
                break;
        }
        submenu.setVisibility(VISIBLE);
    }

    private void addItem(String text, boolean selected, OnClickListener l) {
        Context c = getContext();
        TextView tv = Theme.label(c, (selected ? "● " : "○  ") + text,
                selected ? Theme.ACCENT : Theme.TEXT_DARK, R.dimen.text_body);
        tv.setPadding(Theme.dp(c, 6), Theme.dp(c, 8), Theme.dp(c, 6), Theme.dp(c, 8));
        if (selected) {
            tv.getPaint().setFakeBoldText(true);
        }
        tv.setOnClickListener(l);
        submenuItems.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void fillDisplay() {
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            final int idx = i;
            final boolean selected = i == 0 ? p.options.dynamicResolution
                    : (!p.options.dynamicResolution
                            && p.options.desktopWidth == RES_VALUES[i][0]
                            && p.options.desktopHeight == RES_VALUES[i][1]);
            addItem("分辨率 · " + RESOLUTIONS[i], selected, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeToolbar();
                    if (selected) {
                        return;
                    }
                    if (idx == 0) {
                        p.options.dynamicResolution = true;
                    } else {
                        p.options.dynamicResolution = false;
                        p.options.desktopWidth = RES_VALUES[idx][0];
                        p.options.desktopHeight = RES_VALUES[idx][1];
                    }
                    cb.onSaveProfile(p);
                    applyDisplayChange("分辨率 " + RESOLUTIONS[idx]);
                }
            });
        }
        for (int i = 0; i < DEPTHS.length; i++) {
            final int idx = i;
            final boolean selected = p.options.colorDepth == DEPTH_VALUES[i];
            addItem("色深 · " + DEPTHS[i], selected, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeToolbar();
                    if (selected) {
                        return;
                    }
                    p.options.colorDepth = DEPTH_VALUES[idx];
                    cb.onSaveProfile(p);
                    applyDisplayChange("颜色深度 " + DEPTHS[idx]);
                }
            });
        }
    }

    /**
     * 分辨率 / 色深只在 RDP 建链时协商，连接建立后无法热切换。
     *
     * <p>旧实现只改 options 并提示「重新连接后生效」，但 MainActivity 的重连会复用
     * 同一个 SessionView（openSessionTab 命中已有 tab 就直接返回），旧会话从未重建，
     * 所以设置永远不生效，改动也没落盘。这里改为：保存配置 → 停旧会话 → 复用现有
     * 隧道立即重建 RDP 连接，选中项当场生效。
     */
    private void applyDisplayChange(String label) {
        if (rdpSession != null) {
            // 抑制 onClosed 回调，别让 stop() 把界面切回「会话已断开」诊断面板
            rdpSession.setListener(null);
        }
        stopSession();
        cb.onStatus(label + " · 正在重新连接…", Theme.WARN);
        if (remote != null) {
            remote.setVisibility(VISIBLE);
            remote.setStatus("正在按 " + label + " 重新连接…", true);
        }
        if (centerPane != null) {
            centerPane.setVisibility(GONE);
        }
        // 等旧会话的原生句柄销毁完再建新会话，避免 FreeRDP 复用未释放的状态
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    startSession();
                }
            }
        }, 200);
    }

    private void fillMode() {
        addItem(modeLabel(RemoteView.MODE_TOUCH),
                remote.getInputMode() == RemoteView.MODE_TOUCH, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        remote.setInputMode(RemoteView.MODE_TOUCH);
                        saveInputMode(RemoteView.MODE_TOUCH);
                        cb.onStatus("触控模式：直接点击远端 · 长按放开右键", Theme.TEXT_DARK);
                        closeToolbar();
                    }
                });
        addItem(modeLabel(RemoteView.MODE_MOUSE),
                remote.getInputMode() == RemoteView.MODE_MOUSE, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        remote.setInputMode(RemoteView.MODE_MOUSE);
                        saveInputMode(RemoteView.MODE_MOUSE);
                        cb.onStatus("模拟鼠标模式：滑动移动光标 · 轻点左键 · 长按放开右键",
                                Theme.TEXT_DARK);
                        closeToolbar();
                    }
                });
    }

    private void fillWindow() {
        addItem("全屏", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onFullscreen(true);
                closeToolbar();
            }
        });
        addItem("恢复 / 退出全屏", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onRestore();
                closeToolbar();
            }
        });
        addItem("最小化", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onMinimize();
                closeToolbar();
            }
        });
    }

    private void fillBubble() {
        // 数值语义是「透明程度」：越大越淡，BubbleView 内部换算成不透明度绘制。
        int cur = Math.round(UiState.clampTransparency(cb.uiState().bubbleOpacity) * 100);
        for (final int v : OPACITY_VALUES) {
            addItem("透明度 " + v + "%",
                    Math.abs(cur - v) <= 5, new OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            float t = UiState.clampTransparency(v / 100f);
                            bubble.setOpacity(t);
                            UiState s = cb.uiState();
                            s.bubbleOpacity = t;
                            cb.onSaveUiState(s);
                            closeToolbar();
                        }
                    });
        }
    }

    /**
     * 「缩放」菜单调整远端画面的显示缩放，而不是本地拉伸视图矩阵。
     *
     * <p>RDP 的 desktopScaleFactor 属于建链期 GCC Client Core Data，服务端（Win8.1 /
     * Server 2012 R2 及以上）据此设置会话 DPI，连接建立后无法热切换。因此选中档位后
     * 复用 {@link #applyDisplayChange(String)}：落盘配置 → 停旧会话 → 自动重连。
     * 双指缩放手势仍是本地视图矩阵，两者不叠加（选中档位时把本地 zoom 复位）。
     */
    private void fillView() {
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            final int idx = i;
            final boolean selected = p.options.desktopScale == SCALE_VALUES[i];
            addItem(SCALE_NAMES[i], selected, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeToolbar();
                    if (selected) {
                        return;
                    }
                    p.options.desktopScale = SCALE_VALUES[idx];
                    // 避免旧的本地放大与远端 DPI 缩放叠加
                    remote.setZoom(1f);
                    cb.onSaveProfile(p);
                    applyDisplayChange("远程缩放 " + SCALE_VALUES[idx] + "%");
                }
            });
        }
    }

    private void fillSession() {
        addItem("重新连接", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onReconnect(p);
                closeToolbar();
            }
        });
        addItem("断开", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onDisconnect(p);
                closeToolbar();
            }
        });
        addItem("复制隧道地址", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onCopy(endpoint());
                closeToolbar();
            }
        });
    }

    /** 气泡菜单「键盘」是单击开关：不展开二级列表，显示时本项文字蓝色高亮。 */
    private void toggleKeyboard() {
        setKeyboardVisible(!keyboardVisible);
        if (keyboardToolLabel != null) {
            keyboardToolLabel.setTextColor(keyboardVisible ? Theme.ACCENT : Theme.TEXT_DARK);
            keyboardToolLabel.getPaint().setFakeBoldText(keyboardVisible);
            keyboardToolLabel.invalidate();
        }
        // 点「键盘」后立即收起气泡菜单（工具栏 + 二级列表），避免遮挡键盘与远程画面
        activeTool = -1;
        for (View row : toolRows) {
            if (row != null) {
                row.setBackgroundColor(0x00000000);
            }
        }
        closeToolbar();
        cb.onStatus(keyboardVisible ? "屏幕键盘已显示" : "屏幕键盘已隐藏",
                keyboardVisible ? Theme.OK : Theme.TEXT_DARK);
    }

    /** 与全局设置 ACTIONS 下标一一对应；0「无动作」走 default。 */
    private void execute(int code) {
        switch (code) {
            case 1:
                cb.onMinimize();
                break;
            case 2:
                cb.onSwitchHost();
                break;
            case 3:
                toggleToolbar();
                break;
            case 4:
                cb.onFullscreen(true);
                break;
            case 5:
                cb.onRestore();
                break;
            case UiState.ACTION_TOGGLE_KEYBOARD:
                toggleKeyboard();
                break;
            default:
                break;
        }
    }

    public void showDiagnostics(String text) {
        diagLine.setText(text);
    }

    private void updateInfo() {
        UiState state = cb.uiState();
        String proxy = p.proxySummary(state.globalProxy);
        statusLine.setText((tunnel == null ? "未连接" : "隧道已就绪") + " · "
                + p.displayTitle() + " · " + p.targetEndpoint());
        endpointLine.setText("隧道入口 " + endpoint() + " · 经 " + proxy);
        TextView addr = (TextView) endpointLine.getTag();
        if (addr != null) {
            addr.setText(endpoint());
        }
    }

    private final Runnable statsTask = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (tunnel != null) {
                statsLine.setText(String.format(Locale.ROOT,
                        "活动通道 %d · 累计连接 %d · 上行 %s / 下行 %s · %s",
                        tunnel.activeConnections(), tunnel.totalConnections(),
                        formatSize(tunnel.bytesToTarget()), formatSize(tunnel.bytesFromTarget()),
                        tunnel.proxyName()));
            } else {
                statsLine.setText("隧道未就绪");
            }
            ui.postDelayed(this, 1000);
        }
    };

    /** 每 10 秒采一次建链耗时，攒 3 次取平均，避免单次抖动让指示灯乱闪。 */
    private final Runnable sampleTask = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            final UiState state = cb.uiState();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ConnectivityTester.Result r = ConnectivityTester.test(
                                p.effectiveProxy(state.globalProxy), p.host, p.port);
                        if (!r.success) {
                            return;
                        }
                        final long ms = r.connectMs;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (destroyed) {
                                    return;
                                }
                                samples.add(ms);
                                if (samples.size() > 3) {
                                    samples.remove(0);
                                }
                                long sum = 0;
                                for (Long v : samples) {
                                    sum += v;
                                }
                                long avg = sum / samples.size();
                                latencyLine.setText("建链 " + avg + " ms（3 次平均）");
                                bubble.setLatency(avg);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
            ui.postDelayed(this, 10000);
        }
    };

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Activity 销毁时停止所有定时器。 */
    public void onDestroy() {
        destroyed = true;
        stopSession();
        ui.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------
    // 内嵌 RDP 会话（FreeRDP）
    // ------------------------------------------------------------------

    /** 内嵌 RDP 一律经应用内隧道转发；隧道按会话配置选择直连 / HTTP / SOCKS 连接器。 */
    private static final boolean USE_TUNNEL = true;

    /** 建立内嵌 RDP 连接。已连接时重复调用无效。 */
    public void startSession() {
        if (rdpSession != null || destroyed) {
            return;
        }
        String host = p.host;
        int port = p.port;
        if (tunnel != null && USE_TUNNEL) {
            // 走应用内的代理隧道，外部代理链由 TunnelServer 负责
            host = tunnel.bindAddress();
            port = tunnel.localPort();
        }

        int w = p.options.desktopWidth > 0 ? p.options.desktopWidth : 1280;
        int h = p.options.desktopHeight > 0 ? p.options.desktopHeight : 800;
        if (p.options.dynamicResolution) {
            // 自适应：按屏幕真实像素申请桌面（横屏长边×短边），比例与屏幕一致，
            // 全屏时画面正好 1:1 铺满、无黑边。
            android.view.WindowManager wm = (android.view.WindowManager)
                    getContext().getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point real = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(real);
            int a = Math.max(real.x, real.y) & ~1;
            int b = Math.min(real.x, real.y) & ~1;
            if (a > 1920) {
                // 高分屏按 1920 长边等比缩，兼顾清晰度与流量
                float s = 1920f / a;
                a = Math.round(a * s) & ~1;
                b = Math.round(b * s) & ~1;
            }
            w = Math.max(640, a);
            h = Math.max(480, b);
        }

        /*
         * 「远程缩放」的兼容实现：Win10 RDP 会话实测会忽略客户端声明的
         * desktopScaleFactor / MS-RDPEDISP Monitor Layout PDU（画面无变化），
         * 因此按缩放比例缩小请求桌面尺寸；远端元素像素尺寸不变，本地把
         * 较小桌面等比铺满屏幕后即产生 1.25×/1.5×/1.75× 的放大效果。
         * 代价是清晰度随放大倍数下降，但所有服务端（含 Win7）均生效。
         */
        int scale = Math.max(100, Math.min(500, p.options.desktopScale));
        if (scale > 100) {
            w = Math.max(640, Math.round(w * 100f / scale) & ~1);
            h = Math.max(480, Math.round(h * 100f / scale) & ~1);
        }

        requestedW = w;
        requestedH = h;
        // FreeRDP 需要 HOME / TMPDIR，Android 应用进程默认没有，先给一个私有可写目录
        FreeRdpSession.setWorkDir(getContext().getFilesDir().getAbsolutePath());

        // 缩放已由上面的请求桌面尺寸实现；native 层固定 100%，避免某些服务端
        // 若真的应用 DPI 声明时出现「DPI 放大 + 分辨率缩小」的双重放大。
        FreeRdpSession s = new FreeRdpSession(host, port, p.domain, p.userName, p.password,
                w, h, p.options.colorDepth, 100, p.options.performanceMask());
        // 直连会话保持 /sec:rdp（老服务器只认传统 RDP 加密）；经代理 / 内网穿透的
        // 会话改用协商模式，否则 FreeRDP 发出的纯 X.224 CR 收不到任何回包。
        boolean proxied = p.effectiveProxy(cb.uiState().globalProxy).isProxyEnabled();
        s.setNegotiateSecurity(proxied);
        // 代理链路 RTT 更高，把 MOVE 发送上限从 60Hz 降到约 30Hz，避免服务端积压追尾
        s.setInputIntervalMs(proxied ? RdpInputDispatcher.PROXY_INTERVAL_MS
                : RdpInputDispatcher.DIRECT_INTERVAL_MS);
        s.setListener(new FreeRdpSession.Listener() {
            @Override
            public void onConnected(int width, int height) {
                if (centerPane != null) {
                    centerPane.setVisibility(GONE);
                }
                if (remote != null) {
                    remote.setVisibility(VISIBLE);
                    remote.requestFocus();
                }
                if (statusLine != null) {
                    statusLine.setText(p.displayTitle() + "  " + width + " × " + height);
                }
                // 服务端可能不接受请求尺寸（例如 Windows 7 把 1600×900 回退成
                // 1280×720），如实显示实际协商值，避免看起来像「改了没效果」
                if (requestedW > 0 && (width != requestedW || height != requestedH)) {
                    cb.onStatus("已连接 · 服务端桌面 " + width + " × " + height
                            + "（请求的 " + requestedW + " × " + requestedH
                            + " 未被接受）", Theme.WARN);
                } else {
                    cb.onStatus("已连接", 0xFF7DE2A8);
                }
            }

            @Override
            public void onFramebufferUpdated() {
                if (remote != null) {
                    remote.onFrame();
                }
            }

            @Override
            public void onLog(String message) {
                cb.onLog(message);
            }

            @Override
            public void onError(String message) {
                backToDiagnostics("连接失败：" + message);
            }

            @Override
            public void onClosed() {
                backToDiagnostics("会话已断开");
            }
        });

        rdpSession = s;
        if (remote != null) {
            remote.setSession(s);
            remote.setStatus("正在连接 " + p.host + " …", true);
            remote.setVisibility(VISIBLE);
        }
        if (centerPane != null) {
            centerPane.setVisibility(GONE);
        }
        if (statusLine != null) {
            statusLine.setText("正在连接 " + p.displayTitle() + " …");
        }
        s.start();
    }

    /** 断开内嵌会话并回到诊断面板。 */
    public void stopSession() {
        if (rdpSession != null) {
            rdpSession.stop();
            rdpSession = null;
        }
        if (remote != null) {
            remote.setSession(null);
            remote.setVisibility(GONE);
        }
        if (centerPane != null) {
            centerPane.setVisibility(VISIBLE);
        }
    }

    private void backToDiagnostics(String text) {
        if (rdpSession != null) {
            rdpSession = null;
        }
        if (remote != null) {
            remote.setVisibility(GONE);
            remote.setSession(null);
        }
        if (centerPane != null) {
            centerPane.setVisibility(VISIBLE);
        }
        if (statusLine != null) {
            statusLine.setText(text);
        }
        cb.onStatus(text, 0xFFE07A7A);
    }

    // ------------------------------------------------------------------
    // 工具栏线性图标：纯 Canvas 绘制，不依赖任何图片资源
    // ------------------------------------------------------------------
    private static class ToolIcon extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;

        ToolIcon(Context c, int kind) {
            super(c);
            this.kind = kind;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.6f);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Theme.TEXT_DARK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h) / 24f;
            if (s <= 0) {
                return;
            }
            canvas.save();
            canvas.scale(s, s);
            canvas.translate((w / s - 24f) / 2f, (h / s - 24f) / 2f);
            drawIcon(canvas);
            canvas.restore();
        }

        private void drawIcon(Canvas c) {
            Path p = new Path();
            switch (kind) {
                case 0: // 显示：显示器
                    p.addRoundRect(new RectF(2f, 3f, 22f, 17f), 2f, 2f, Path.Direction.CW);
                    c.drawPath(p, line);
                    c.drawLine(12f, 17f, 12f, 21f, line);
                    c.drawLine(8f, 21f, 16f, 21f, line);
                    break;
                case 1: // 模式：鼠标指针
                    p.moveTo(5f, 2f);
                    p.lineTo(5f, 20f);
                    p.lineTo(10f, 15f);
                    p.lineTo(13f, 22f);
                    p.lineTo(16f, 20f);
                    p.lineTo(13f, 13f);
                    p.lineTo(20f, 13f);
                    p.close();
                    c.drawPath(p, line);
                    break;
                case 2: // 窗口：全屏四角
                    c.drawLine(3f, 9f, 3f, 3f, line);
                    c.drawLine(3f, 3f, 9f, 3f, line);
                    c.drawLine(15f, 3f, 21f, 3f, line);
                    c.drawLine(21f, 3f, 21f, 9f, line);
                    c.drawLine(21f, 15f, 21f, 21f, line);
                    c.drawLine(21f, 21f, 15f, 21f, line);
                    c.drawLine(9f, 21f, 3f, 21f, line);
                    c.drawLine(3f, 21f, 3f, 15f, line);
                    break;
                case 3: // 气泡：圆 + 指示灯
                    c.drawCircle(12f, 12f, 9f, line);
                    c.drawCircle(12f, 12f, 2.5f, line);
                    break;
                case 4: // 缩放：放大镜
                    c.drawCircle(10.5f, 10.5f, 6.5f, line);
                    c.drawLine(15.5f, 15.5f, 21f, 21f, line);
                    break;
                case 5: // 会话：电源
                    c.drawArc(new RectF(3f, 3f, 21f, 21f), 40f, 280f, false, line);
                    c.drawLine(12f, 2f, 12f, 11f, line);
                    break;
                case 6: // 键盘：外框 + 按键点阵
                    p.addRoundRect(new RectF(2f, 6f, 22f, 18f), 2f, 2f, Path.Direction.CW);
                    c.drawPath(p, line);
                    for (int col = 0; col < 4; col++) {
                        for (int row = 0; row < 2; row++) {
                            c.drawRect(5f + col * 4f, 9f + row * 4f,
                                    7f + col * 4f, 11f + row * 4f, line);
                        }
                    }
                    c.drawLine(7f, 16f, 17f, 16f, line);
                    break;
                default:
                    break;
            }
        }
    }
}
