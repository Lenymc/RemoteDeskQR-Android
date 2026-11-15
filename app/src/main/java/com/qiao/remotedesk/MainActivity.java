package com.qiao.remotedesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qiao.remotedesk.model.CardColors;
import com.qiao.remotedesk.model.ConnectionProfile;
import com.qiao.remotedesk.model.LoopbackPool;
import com.qiao.remotedesk.model.ProfileStore;
import com.qiao.remotedesk.model.UiState;
import com.qiao.remotedesk.net.ConnectivityTester;
import com.qiao.remotedesk.net.ProxyFactory;
import com.qiao.remotedesk.net.SessionManager;
import com.qiao.remotedesk.net.TunnelServer;
import com.qiao.remotedesk.net.TunnelService;
import com.qiao.remotedesk.ui.BubbleView;
import com.qiao.remotedesk.ui.HomeView;
import com.qiao.remotedesk.ui.ProfileEditorView;
import com.qiao.remotedesk.ui.SessionView;
import com.qiao.remotedesk.ui.SettingsView;
import com.qiao.remotedesk.ui.Theme;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** 主界面：侧栏 + 多标签页 + 状态栏；按 smallestScreenWidthDp 分级适配平板 HD。 */
public class MainActivity extends Activity implements
        HomeView.Callback, ProfileEditorView.Callback,
        SettingsView.Callback, SessionView.Callback {

    private static final String KEY_HOME = "home";
    private static final String KEY_SETTINGS = "settings";
    private static final String PREFIX_EDIT = "edit:";
    private static final String PREFIX_SESSION = "session:";
    private static final String[] COLOR_NAMES = {
            "蓝", "靛", "紫", "品红", "玫红", "红", "橙", "琥珀", "绿", "青绿", "青", "石板灰", "自动"
    };

    private static final int KIND_HOME = 0;
    private static final int KIND_EDITOR = 1;
    private static final int KIND_SESSION = 2;
    private static final int KIND_SETTINGS = 3;

    private static class Tab {
        String key;
        String title;
        int kind;
        ConnectionProfile profile;
        View view;
    }

    private final List<ConnectionProfile> profiles = new ArrayList<ConnectionProfile>();
    private final List<Tab> tabs = new ArrayList<Tab>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ProfileStore store;
    private UiState ui;
    private String activeKey = KEY_HOME;
    private ConnectionProfile selected;
    private int swDp;
    private boolean maximized;
    private boolean fullscreen;
    /** 调试入口 --ez fullscreen true：会话建立后自动进全屏。 */
    private boolean pendingFullscreen;
    private String lastTestDetail = "";

    private LinearLayout topBar;
    private LinearLayout tabStrip;
    private LinearLayout topActions;
    private LinearLayout sidebar;
    private View splitter;
    private LinearLayout sessionList;
    private LinearLayout sideActions;
    private LinearLayout statusBar;
    private LinearLayout statusActions;
    private TextView statusText;
    private FrameLayout contentHost;
    private Button btnMaximize;
    private Button btnFullscreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 诊断入口：adb shell am start -n com.qiao.remotedesk/.MainActivity --es tlsprobe <host>
        String probeHost = getIntent() == null ? null : getIntent().getStringExtra("tlsprobe");
        if (probeHost != null && probeHost.length() > 0) {
            TlsProbe.runToFile(getFilesDir(), probeHost, 3389);
        }
        // 探针所需凭据只从调用方传入，不在代码里预置账号或口令。
        String rdpHost = getIntent() == null ? null : getIntent().getStringExtra("rdpprobe");
        if (rdpHost != null && rdpHost.length() > 0) {
            String probeUser = stringExtra("probeUser");
            String probePassword = stringExtra("probePassword");
            String probeDomain = stringExtra("probeDomain");
            RdpProbe.start(this, rdpHost, 3389, probeUser, probePassword, probeDomain);
        }
        store = new ProfileStore(this);
        ui = store.loadUiState();
        profiles.addAll(store.load());
        // 公开版本不预置任何主机：首次启动显示「新建连接」，由用户自行录入。
        selected = profiles.isEmpty() ? null : profiles.get(0);
        swDp = getResources().getConfiguration().smallestScreenWidthDp;

        topBar = findViewById(R.id.top_bar);
        tabStrip = findViewById(R.id.tab_strip);
        topActions = findViewById(R.id.top_actions);
        sidebar = findViewById(R.id.sidebar);
        splitter = findViewById(R.id.splitter);
        sessionList = findViewById(R.id.session_list);
        sideActions = findViewById(R.id.side_actions);
        statusBar = findViewById(R.id.status_bar);
        statusActions = findViewById(R.id.status_actions);
        statusText = findViewById(R.id.status_text);
        contentHost = findViewById(R.id.content_host);

        buildTopActions();
        buildStatusActions();
        buildSideActions();
        statusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTestDetail();
            }
        });

        addTab(newTab(KEY_HOME, "主页", KIND_HOME, null, new HomeView(this, this)));
        // 冷启动固定落在主页九宫格，不记忆上次停留的标签
        activeKey = KEY_HOME;
        refreshTabs();
        rebuildSidebar();
        applyChrome();
        askNotificationPermission();
        setStatus("未连接", Theme.TEXT_DARK);

        // 调试入口：adb shell am start -n com.qiao.remotedesk/.MainActivity --es connect <host>
        // 可加 --ez fullscreen true 让会话建立后自动进入全屏（便于无人值守验证）。
        final String autoHost = getIntent() == null ? null : getIntent().getStringExtra("connect");
        final boolean autoFull = getIntent() != null && getIntent().getBooleanExtra("fullscreen", false);
        if (autoHost != null && autoHost.length() > 0) {
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    for (ConnectionProfile p : profiles) {
                        if (autoHost.equals(p.host) || autoHost.equals(p.name)) {
                            pendingFullscreen = autoFull;
                            connectProfile(p, false);
                            return;
                        }
                    }
                    setStatus("未找到主机 " + autoHost, Theme.FAIL);
                }
            }, 600);
        }
    }

    /** 读取调试入口的字符串参数，缺省返回空串（不预置任何账号或口令）。 */
    private String stringExtra(String key) {
        String v = getIntent() == null ? null : getIntent().getStringExtra(key);
        return v == null ? "" : v;
    }

    // ---------------------------------------------------------------- 顶栏 / 状态栏

    private void buildTopActions() {
        Context c = this;
        if (swDp >= 600) {
            btnMaximize = Theme.button(c, "最大化", Theme.BTN_DARK);
            btnMaximize.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleMaximized();
                }
            });
            topActions.addView(btnMaximize);
            btnFullscreen = Theme.button(c, "全屏", Theme.BTN_DARK);
            btnFullscreen.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    enterFullscreen();
                }
            });
            topActions.addView(btnFullscreen);
        }
        Button settings = Theme.button(c, "设置", Theme.BTN_DARK);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        topActions.addView(settings);
        if (swDp >= 720) {
            Button test = Theme.button(c, "测试连通性", Theme.BTN_DARK);
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selected != null) {
                        testConnectivity(selected);
                    }
                }
            });
            topActions.addView(test);
        }
        Button connect = Theme.button(c, "连接", Theme.BTN_PRIMARY);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected != null) {
                    connectProfile(selected, false);
                }
            }
        });
        topActions.addView(connect);
    }

    private void buildStatusActions() {
        Button reconnect = Theme.button(this, "重新连接", Theme.BTN_FLAT);
        reconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected != null) {
                    connectProfile(selected, false);
                }
            }
        });
        statusActions.addView(reconnect);
        Button disconnect = Theme.button(this, "断开", Theme.BTN_FLAT);
        disconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected != null) {
                    disconnect(selected);
                }
            }
        });
        statusActions.addView(disconnect);
        Button logs = Theme.button(this, "日志", Theme.BTN_FLAT);
        logs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogs();
            }
        });
        statusActions.addView(logs);
        Button dir = Theme.button(this, "配置目录", Theme.BTN_FLAT);
        dir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(store.configDir().getAbsolutePath());
                setStatus("配置目录已复制：" + store.configDir().getAbsolutePath(), Theme.TEXT_DARK);
            }
        });
        statusActions.addView(dir);
    }

    private void buildSideActions() {
        final String[] labels = {"新建", "复制", "删除"};
        for (int i = 0; i < labels.length; i++) {
            Button b = Theme.button(this, labels[i], Theme.BTN_DARK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Theme.dp(this, 4);
            b.setLayoutParams(lp);
            final int which = i;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (which == 0) {
                        createProfile();
                    } else if (which == 1) {
                        if (selected != null) {
                            duplicateProfile(selected);
                        }
                    } else if (selected != null) {
                        confirmDelete(selected);
                    }
                }
            });
            sideActions.addView(b);
        }
    }

    // ---------------------------------------------------------------- 标签系统

    private Tab newTab(String key, String title, int kind, ConnectionProfile p, View view) {
        Tab t = new Tab();
        t.key = key;
        t.title = title;
        t.kind = kind;
        t.profile = p;
        t.view = view;
        return t;
    }

    private void addTab(Tab t) {
        for (Tab old : tabs) {
            if (old.key.equals(t.key)) {
                activeKey = t.key;
                refreshTabs();
                return;
            }
        }
        tabs.add(t);
        activeKey = t.key;
        refreshTabs();
    }

    private void refreshTabs() {
        tabStrip.removeAllViews();
        for (final Tab t : tabs) {
            int tabH = Theme.dimen(this, R.dimen.tab_bar_height);
            boolean hasClose = t.kind != KIND_HOME;
            TextView label = Theme.label(this, t.title, Theme.TEXT_LIGHT, R.dimen.text_body);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            // 按实测文字宽度给 label 固定宽度（多留 10dp），从测量上保证不被裁剪
            int textW = (int) Math.ceil(label.getPaint().measureText(t.title));
            int fourChars = (int) (4 * label.getTextSize());
            int labelW = Math.max(textW + Theme.dp(this, 10), fourChars);
            int closeW = Theme.dp(this, 26);
            // 左侧留出与关闭按钮等宽的空位，文字才能在整个标签内水平居中
            int tabW = labelW + (hasClose ? closeW * 2 + Theme.dp(this, 12)
                    : Theme.dp(this, 12));
            LinearLayout item = Theme.horizontal(this);
            item.setBackgroundColor(activeKey.equals(t.key) ? Theme.ACCENT : Theme.TOP);
            item.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(tabW, tabH);
            ilp.rightMargin = Theme.dp(this, 4);
            item.setLayoutParams(ilp);
            View leftPad = new View(this);
            item.addView(leftPad, new LinearLayout.LayoutParams(
                    hasClose ? closeW + Theme.dp(this, 4) : Theme.dp(this, 6),
                    LinearLayout.LayoutParams.MATCH_PARENT));
            item.addView(label, new LinearLayout.LayoutParams(labelW,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            if (hasClose) {
                Button close = Theme.button(this, "×", Theme.BTN_FLAT);
                close.setTextColor(Theme.TEXT_LIGHT);
                close.setPadding(0, 0, 0, 0);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        closeW, LinearLayout.LayoutParams.MATCH_PARENT);
                clp.leftMargin = Theme.dp(this, 4);
                close.setLayoutParams(clp);
                close.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeTab(t.key);
                    }
                });
                item.addView(close);
            }
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeKey = t.key;
                    showActiveTab();
                }
            });
            tabStrip.addView(item);
        }
        showActiveTab();
    }

    private void showActiveTab() {
        Tab target = null;
        for (Tab t : tabs) {
            if (t.key.equals(activeKey)) {
                target = t;
                break;
            }
        }
        if (target == null) {
            activeKey = KEY_HOME;
            for (Tab t : tabs) {
                if (t.key.equals(KEY_HOME)) {
                    target = t;
                    break;
                }
            }
        }
        contentHost.removeAllViews();
        if (target != null && target.view != null) {
            contentHost.addView(target.view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (target.kind == KIND_HOME && target.view instanceof HomeView) {
                ((HomeView) target.view).refresh();
            }
        }
        refreshTabsColorsOnly();
    }

    private void refreshTabsColorsOnly() {
        for (int i = 0; i < tabStrip.getChildCount(); i++) {
            View v = tabStrip.getChildAt(i);
            Tab t = i < tabs.size() ? tabs.get(i) : null;
            v.setBackgroundColor(t != null && t.key.equals(activeKey) ? Theme.ACCENT : Theme.TOP);
        }
    }

    private void closeTab(String key) {
        Tab victim = null;
        for (Tab t : tabs) {
            if (t.key.equals(key)) {
                victim = t;
                break;
            }
        }
        if (victim == null || victim.kind == KIND_HOME) {
            return;
        }
        if (victim.kind == KIND_SESSION && victim.profile != null) {
            disconnect(victim.profile);
            return;
        }
        if (victim.view instanceof SessionView) {
            ((SessionView) victim.view).onDestroy();
        }
        tabs.remove(victim);
        if (key.equals(activeKey)) {
            activeKey = KEY_HOME;
        }
        refreshTabs();
    }

    // ---------------------------------------------------------------- 侧栏

    private void rebuildSidebar() {
        if (sessionList == null) {
            return;
        }
        sessionList.removeAllViews();
        for (final ConnectionProfile p : profiles) {
            LinearLayout item = Theme.vertical(this);
            boolean running = isRunning(p);
            boolean isSel = selected != null && selected.id.equals(p.id);
            item.setBackgroundColor(isSel ? Theme.SIDE_ITEM : Theme.SIDE);
            // 列表顶部不留空：第一张卡片紧贴标签栏，其余保持常规间距
            int topPad = sessionList.getChildCount() == 0 ? Theme.dp(this, 2) : Theme.dp(this, 10);
            item.setPadding(Theme.dp(this, 14), topPad, Theme.dp(this, 10), Theme.dp(this, 10));
            LinearLayout first = Theme.horizontal(this);
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(running ? 0xFF7DE2A8 : 0xFF5B667A);
            int size = Theme.dp(this, 8);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
            dlp.rightMargin = Theme.dp(this, 8);
            dot.setBackground(d);
            dot.setLayoutParams(dlp);
            first.addView(dot);
            TextView name = Theme.label(this, p.displayTitle(), Theme.TEXT_LIGHT, R.dimen.text_body);
            name.setSingleLine(true);
            first.addView(name);
            item.addView(first);
            TextView addr = Theme.label(this, p.targetEndpoint(), Theme.MUTED, R.dimen.text_small);
            addr.setSingleLine(true);
            addr.setPadding(size + Theme.dp(this, 8), 0, 0, 0);
            item.addView(addr);
            TextView proxy = Theme.label(this, p.proxySummary(ui.globalProxy),
                    Theme.MUTED, R.dimen.text_small);
            proxy.setSingleLine(true);
            proxy.setPadding(size + Theme.dp(this, 8), 0, 0, 0);
            item.addView(proxy);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected = p;
                    rebuildSidebar();
                    if (isRunning(p)) {
                        openSessionTab(p, SessionManager.get().get(p.id));
                    } else {
                        openEditorTab(p);
                    }
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    selected = p;
                    showProfileMenu(p);
                    return true;
                }
            });
            sessionList.addView(item);
        }
        // 没有活动会话时禁用最大化/全屏，并把按钮压暗，避免误以为可以点
        boolean active = hasActiveSession();
        if (btnMaximize != null) {
            btnMaximize.setEnabled(active);
            btnMaximize.setAlpha(active ? 1f : 0.35f);
        }
        if (btnFullscreen != null) {
            btnFullscreen.setEnabled(active);
            btnFullscreen.setAlpha(active ? 1f : 0.35f);
        }
    }

    private boolean hasActiveSession() {
        return !SessionManager.get().activeIds().isEmpty();
    }

    // ---------------------------------------------------------------- 连接生命周期

    private void connectProfile(final ConnectionProfile p, final boolean maximizeAfter) {
        if (!p.isConfigured()) {
            setStatus("配置不完整：缺少目标主机地址", Theme.FAIL);
            return;
        }
        String invalid = ProxyFactory.validate(p.effectiveProxy(ui.globalProxy));
        if (invalid != null) {
            setStatus(invalid, Theme.FAIL);
            return;
        }
        setStatus("正在建立隧道…", Theme.WARN);
        try {
            Intent service = new Intent(this, TunnelService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable t) {
            appendLog("启动前台服务失败：" + t.getMessage());
        }
        final TunnelServer.LogListener log = new TunnelServer.LogListener() {
            @Override
            public void onLog(String message) {
                appendLog(message);
            }
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final TunnelServer server = SessionManager.get()
                            .startSession(p, ui.globalProxy, log);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            onTunnelReady(p, server, maximizeAfter);
                        }
                    });
                } catch (final IOException e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("隧道建立失败：" + e.getMessage(), Theme.FAIL);
                        }
                    });
                }
            }
        }).start();
    }

    private void onTunnelReady(ConnectionProfile p, TunnelServer server, boolean maximizeAfter) {
        store.save(profiles); // 回环地址分配结果落盘
        selected = p;
        openSessionTab(p, server);
        rebuildSidebar();
        setStatus("隧道已就绪 · " + p.displayTitle() + " · " + server.bindAddress()
                + ":" + server.localPort(), Theme.OK);
        appendLog("会话已启动：" + p.targetEndpoint() + " → "
                + server.bindAddress() + ":" + server.localPort());
        if (maximizeAfter) {
            maximized = true;
            applyChrome();
        }
        // 「以全屏启动」或调试入口指定全屏时，会话建立后自动进入全屏
        if (p.options.fullScreen || pendingFullscreen) {
            pendingFullscreen = false;
            if (hasActiveSession()) {
                enterFullscreen();
            }
        }
        // 会话画面由内嵌 FreeRDP 在应用内渲染。
    }

    private void openSessionTab(ConnectionProfile p, TunnelServer server) {
        String key = PREFIX_SESSION + p.id;
        for (Tab t : tabs) {
            if (t.key.equals(key)) {
                if (t.view instanceof SessionView) {
                    ((SessionView) t.view).setTunnel(server);
                }
                activeKey = key;
                refreshTabs();
                return;
            }
        }
        SessionView view = new SessionView(this, p, server, this);
        addTab(newTab(key, p.displayTitle(), KIND_SESSION, p, view));
    }

    private void disconnect(ConnectionProfile p) {
        SessionManager.get().stopSession(p.id);
        String key = PREFIX_SESSION + p.id;
        Tab victim = null;
        for (Tab t : tabs) {
            if (t.key.equals(key)) {
                victim = t;
                break;
            }
        }
        if (victim != null) {
            if (victim.view instanceof SessionView) {
                ((SessionView) victim.view).onDestroy();
            }
            tabs.remove(victim);
            if (key.equals(activeKey)) {
                activeKey = KEY_HOME;
            }
        }
        if (!hasActiveSession()) {
            try {
                stopService(new Intent(this, TunnelService.class));
            } catch (Throwable ignored) {
            }
            if (maximized || fullscreen) {
                restore();
            }
        }
        rebuildSidebar();
        refreshTabs();
        setStatus("已断开 · " + p.displayTitle(), Theme.TEXT_DARK);
    }

    private void testConnectivity(final ConnectionProfile p) {
        if (!p.isConfigured()) {
            setStatus("配置不完整：缺少目标主机地址", Theme.FAIL);
            return;
        }
        setStatus("正在测试连通性…", Theme.WARN);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ConnectivityTester.Result r = ConnectivityTester.test(
                        p.effectiveProxy(ui.globalProxy), p.host, p.port);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder detail = new StringBuilder(r.message);
                        for (String step : r.steps) {
                            detail.append('\n').append(step);
                        }
                        lastTestDetail = detail.toString();
                        if (r.success) {
                            setStatus(p.displayTitle() + " · 连通正常 · 建链 "
                                    + r.connectMs + " ms", Theme.OK);
                        } else {
                            setStatus(p.displayTitle() + " · 连通失败（点此看分段诊断）", Theme.FAIL);
                        }
                        appendLog("连通性测试：" + r.message.replace('\n', ' '));
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                setStatus(hasActiveSession() ? "隧道运行中" : "未连接", Theme.TEXT_DARK);
                            }
                        }, 8000);
                        for (Tab t : tabs) {
                            if (t.view instanceof SessionView && t.profile == p) {
                                ((SessionView) t.view).showDiagnostics(
                                        r.steps.isEmpty() ? r.message : r.steps.get(r.steps.size() - 1));
                            }
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 配置管理

    private void openEditorTab(final ConnectionProfile p) {
        String key = PREFIX_EDIT + p.id;
        for (Tab t : tabs) {
            if (t.key.equals(key)) {
                activeKey = key;
                refreshTabs();
                return;
            }
        }
        ProfileEditorView editor = new ProfileEditorView(this, p, this);
        addTab(newTab(key, "配置 · " + p.displayTitle(), KIND_EDITOR, p, editor));
    }

    private void openSettings() {
        for (Tab t : tabs) {
            if (t.key.equals(KEY_SETTINGS)) {
                activeKey = KEY_SETTINGS;
                refreshTabs();
                return;
            }
        }
        addTab(newTab(KEY_SETTINGS, "全局设置", KIND_SETTINGS, null,
                new SettingsView(this, this)));
    }

    private void createProfile() {
        ConnectionProfile p = new ConnectionProfile();
        p.name = "连接 " + (profiles.size() + 1);
        p.loopbackAddress = allocateLoopback();
        profiles.add(p);
        selected = p;
        store.save(profiles);
        rebuildSidebar();
        openEditorTab(p);
    }

    private void duplicateProfile(ConnectionProfile src) {
        ConnectionProfile copy = src.cloneAsNew(src.name + " 副本");
        copy.loopbackAddress = allocateLoopback();
        profiles.add(copy);
        selected = copy;
        store.save(profiles);
        rebuildSidebar();
        openEditorTab(copy);
        setStatus("已复制配置：" + copy.name, Theme.OK);
    }

    private String allocateLoopback() {
        Collection<String> activeIds = SessionManager.get().activeIds();
        List<String> used = new ArrayList<String>();
        used.addAll(activeIds);
        for (ConnectionProfile p : profiles) {
            if (!p.loopbackAddress.isEmpty()) {
                used.add(p.loopbackAddress);
            }
        }
        return LoopbackPool.allocate(used);
    }

    private void confirmDelete(final ConnectionProfile p) {
        new AlertDialog.Builder(this)
                .setTitle("删除主机")
                .setMessage("确认删除「" + p.displayTitle() + "」？有活动会话会先断开。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (isRunning(p)) {
                            disconnect(p);
                        }
                        profiles.remove(p);
                        for (int i = tabs.size() - 1; i >= 0; i--) {
                            Tab t = tabs.get(i);
                            if (t.profile == p) {
                                if (t.view instanceof SessionView) {
                                    ((SessionView) t.view).onDestroy();
                                }
                                tabs.remove(i);
                            }
                        }
                        selected = profiles.isEmpty() ? null : profiles.get(0);
                        store.save(profiles);
                        activeKey = KEY_HOME;
                        refreshTabs();
                        rebuildSidebar();
                        setStatus("已删除主机：" + p.name, Theme.TEXT_DARK);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showProfileMenu(final ConnectionProfile p) {
        final String[] items = {"连接", "最大化连接", "测试连通性", "编辑", "卡片颜色",
                "断开", "重新连接", "复制", "删除主机"};
        new AlertDialog.Builder(this)
                .setTitle(p.displayTitle())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                connectProfile(p, false);
                                break;
                            case 1:
                                connectProfile(p, true);
                                break;
                            case 2:
                                testConnectivity(p);
                                break;
                            case 3:
                                openEditorTab(p);
                                break;
                            case 4:
                                showColorMenu(p);
                                break;
                            case 5:
                                disconnect(p);
                                break;
                            case 6:
                                connectProfile(p, false);
                                break;
                            case 7:
                                duplicateProfile(p);
                                break;
                            default:
                                confirmDelete(p);
                                break;
                        }
                    }
                })
                .show();
    }

    private void showColorMenu(final ConnectionProfile p) {
        new AlertDialog.Builder(this)
                .setTitle("卡片颜色")
                .setItems(COLOR_NAMES, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        p.cardColor = which >= CardColors.PALETTE.length
                                ? "" : CardColors.PALETTE[which];
                        store.save(profiles);
                        View home = findTabView(KEY_HOME);
                        if (home instanceof HomeView) {
                            ((HomeView) home).refresh();
                        }
                        setStatus("卡片颜色已更新", Theme.OK);
                    }
                })
                .show();
    }

    private View findTabView(String key) {
        for (Tab t : tabs) {
            if (t.key.equals(key)) {
                return t.view;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 布局模式

    private void toggleMaximized() {
        if (!hasActiveSession()) {
            setStatus("没有已连接的会话，无法进入最大化布局", Theme.FAIL);
            return;
        }
        maximized = !maximized;
        applyChrome();
    }

    private void enterFullscreen() {
        if (!hasActiveSession()) {
            setStatus("没有已连接的会话，无法进入全屏", Theme.FAIL);
            return;
        }
        fullscreen = true;
        maximized = true;
        applyChrome();
    }

    private void applyChrome() {
        boolean sideVisible = !maximized && !fullscreen && swDp >= 600;
        sidebar.setVisibility(sideVisible ? View.VISIBLE : View.GONE);
        splitter.setVisibility(sideVisible ? View.VISIBLE : View.GONE);
        statusBar.setVisibility(maximized || fullscreen ? View.GONE : View.VISIBLE);
        topBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        View decor = getWindow().getDecorView();
        if (fullscreen) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen || maximized) {
            restore();
        } else if (!KEY_HOME.equals(activeKey)) {
            activeKey = KEY_HOME;
            refreshTabs();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- 剪贴板与日志

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("RemoteDeskQR", text));
        }
    }

    // ---------------------------------------------------------------- 状态栏 / 日志 / 对话框

    private void setStatus(String text, int color) {
        if (statusText != null) {
            statusText.setText("●  " + text);
            statusText.setTextColor(color);
        }
    }

    private void appendLog(String line) {
        if (store != null) {
            store.appendLog(line);
        }
    }

    private void showTestDetail() {
        String body = lastTestDetail.isEmpty()
                ? "在首页卡片或侧栏条目上长按，选择「测试连通性」，结果会显示在这里。"
                : lastTestDetail;
        new AlertDialog.Builder(this).setTitle("连通性诊断").setMessage(body)
                .setPositiveButton("关闭", null).show();
    }

    private void showLogs() {
        File dir = new File(store.configDir(), "logs");
        File[] files = dir.listFiles();
        StringBuilder sb = new StringBuilder();
        if (files != null && files.length > 0) {
            java.util.Arrays.sort(files);
            File last = files[files.length - 1];
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(last));
                String line;
                java.util.Deque<String> tail = new java.util.ArrayDeque<String>();
                while ((line = reader.readLine()) != null) {
                    tail.addLast(line);
                    if (tail.size() > 30) {
                        tail.removeFirst();
                    }
                }
                for (String l : tail) {
                    sb.append(l).append('\n');
                }
            } catch (Exception e) {
                sb.append("读取日志失败：").append(e.getMessage());
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("暂无日志。日志目录：").append(dir.getAbsolutePath());
        }
        new AlertDialog.Builder(this).setTitle("运行日志（最近 30 行）")
                .setMessage(sb.toString()).setPositiveButton("关闭", null).show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ---------------------------------------------------------------- 各视图回调

    @Override
    public List<ConnectionProfile> profiles() {
        return profiles;
    }

    @Override
    public boolean isRunning(ConnectionProfile p) {
        return SessionManager.get().get(p.id) != null;
    }

    @Override
    public void onOpen(ConnectionProfile p) {
        selected = p;
        if (isRunning(p)) {
            openSessionTab(p, SessionManager.get().get(p.id));
        } else {
            connectProfile(p, false);
        }
    }

    @Override
    public void onMenu(ConnectionProfile p, View anchor) {
        selected = p;
        showProfileMenu(p);
    }

    @Override
    public void onNew() {
        createProfile();
    }

    @Override
    public void onSave(ConnectionProfile p) {
        if (!profiles.contains(p)) {
            profiles.add(p);
        }
        store.save(profiles);
        selected = p;
        // 保存后关闭该主机的配置标签并显示主页（与全局设置保存行为一致）
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab t = tabs.get(i);
            if (t.kind == KIND_EDITOR && t.profile == p) {
                tabs.remove(i);
            }
        }
        activeKey = KEY_HOME;
        rebuildSidebar();
        refreshTabs();
        View home = findTabView(KEY_HOME);
        if (home instanceof HomeView) {
            ((HomeView) home).refresh();
        }
        setStatus("配置已保存：" + p.displayTitle() + " · 重新连接会话后生效", Theme.OK);
    }

    @Override
    public void onClose() {
        closeTab(activeKey);
    }

    @Override
    public void onSave(UiState s) {
        ui = s;
        store.saveUiState(ui);
        setStatus("全局设置已保存并生效", Theme.OK);
    }

    @Override
    public void onCloseSettings() {
        // 保存后不留在设置页：移除「全局设置」标签，激活主页九宫格
        for (int i = tabs.size() - 1; i >= 0; i--) {
            if (KEY_SETTINGS.equals(tabs.get(i).key)) {
                tabs.remove(i);
            }
        }
        activeKey = KEY_HOME;
        rebuildSidebar();   // 侧栏的代理摘要随新配置即时刷新
        refreshTabs();      // 内部会 showActiveTab() 并刷新主页
    }

    @Override
    public UiState uiState() {
        return ui;
    }

    @Override
    public void onStatus(String text, int color) {
        setStatus(text, color);
    }

    @Override
    public void onDisconnect(ConnectionProfile p) {
        disconnect(p);
    }

    @Override
    public void onSaveProfile(ConnectionProfile p) {
        if (!profiles.contains(p)) {
            profiles.add(p);
        }
        store.save(profiles);
    }

    @Override
    public void onReconnect(ConnectionProfile p) {
        // RDP 桌面尺寸 / 色深只在建链时协商：必须先销毁旧会话与隧道再重开。
        // 否则 openSessionTab 命中已有 tab 会直接复用旧 SessionView，重连形同虚设。
        disconnect(p);
        connectProfile(p, false);
    }

    @Override
    public void onFullscreen(boolean on) {
        if (on) {
            enterFullscreen();
        } else {
            restore();
        }
    }

    @Override
    public void onRestore() {
        restore();
    }

    private void restore() {
        maximized = false;
        fullscreen = false;
        applyChrome();
    }

    @Override
    public void onMinimize() {
        moveTaskToBack(true);
    }

    @Override
    public void onSwitchHost() {
        int start = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).key.equals(activeKey)) {
                start = i;
                break;
            }
        }
        for (int k = 1; k <= tabs.size(); k++) {
            int i = (start + k + tabs.size()) % tabs.size();
            if (tabs.get(i).kind == KIND_SESSION) {
                activeKey = tabs.get(i).key;
                refreshTabs();
                return;
            }
        }
    }

    @Override
    public void onCopy(String text) {
        copyToClipboard(text);
        setStatus("已复制：" + text, Theme.OK);
    }

    @Override
    public void onSaveUiState(UiState s) {
        ui = s;
        store.saveUiState(ui);
    }

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) {
            if (t.view instanceof SessionView) {
                ((SessionView) t.view).onDestroy();
            }
        }
        main.removeCallbacksAndMessages(null);
        SessionManager.get().stopAll();
        try {
            stopService(new Intent(this, TunnelService.class));
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
