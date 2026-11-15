package com.qiao.remotedesk.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qiao.remotedesk.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话页内的屏幕键盘：不依赖硬件键盘，按键直接转成 RDP 扫描码。
 * 键位与宽度按 ThinkPad 笔记本 6 行紧凑键盘复刻；顶部把手可上下拖动调高度，高度会被记住。
 */
public class OnScreenKeyboard extends LinearLayout {

    public interface Sink {
        /** 普通键：按下后延时抬起，保证远端能采样到按下状态。 */
        void tapKey(int keyCode);

        /** 修饰键：保持按下 / 松开，用于 Ctrl+C 之类组合键。 */
        void holdKey(int keyCode, boolean down);
    }

    /** 左 Win 键：Android 有 KEYCODE_WINDOW，但需要一个独立标记以便补扫描码。 */
    public static final int KEY_WIN = -100;

    private static final String PREF_NAME = "remotedesk_prefs";
    private static final String KEY_KB_HEIGHT = "keyboard_height_px";

    private static final int KEY_BG = 0xFFF8F9FC;
    private static final int KEY_BORDER = 0xFFB6C3D6;
    private static final int KEY_PRESSED = 0xFFDCE6FA;
    private static final int KEY_HELD = 0xFF2F6BFF;
    private static final int GRIP_COLOR = 0xFFB6C3D6;
    private static final int SUB_LABEL = 0xFF2F6BFF;

    /** 顶部把手的可见高度压到一条细线；按键区按剩余高度等分，随高度一起拉伸。 */
    private static final int HANDLE_DP = 6;
    /** 不占布局的触摸热区：细线本身不好抓，容器顶部 16dp 内按下都算拖高度。 */
    private static final int HOT_ZONE_DP = 16;
    /** 默认总高度 = 把手 6 + 上下 padding 10 + 按键区 228（6 行） = 244dp。 */
    private static final int DEFAULT_DP = 244;
    private static final int MIN_DP = 170;
    /** 每行权重和固定为 15，键宽按 ThinkPad 实际比例分配。 */

    /**
     * 键面文字：\n  = 上下双标注（符号在上、字符在下）；
     * |  = 主标签 + 蓝色小副标签（功能键的 Fn 功能，如 F1 静音、Insert/PrtSc）。
     */
    private static final String[][] LABELS = {
            {"Esc|FnLk", "F1|Mute", "F2|Vol-", "F3|Vol+", "F4|Mic", "F5|Dim", "F6|Bright",
                    "F7|Disp", "F8|WiFi", "F9|Prev", "F10|Play", "F11|Next", "F12|Menu",
                    "Insert|PrtSc", "Delete|SysRq", "Home|ScrLk", "End|Break"},
            {"~\n`", "!\n1", "@\n2", "#\n3", "$\n4", "%\n5", "^\n6", "&\n7", "*\n8", "(\n9",
                    ")\n0", "_\n-", "+\n=", "Backspace"},
            {"Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{\n[", "}\n]", "|\n\\"},
            {"CapsLock", "A", "S", "D", "F", "G", "H", "J", "K", "L", ":\n;", "\"\n'", "Enter"},
            {"Shift", "Z", "X", "C", "V", "B", "N", "M", "<\n,", ">\n.", "?\n/",
                    "PgUp", "↑", "PgDn"},
            {"Ctrl", "Fn", "Win", "Alt", "Space", "Alt", "Ctrl", "←", "↓", "→"},
    };

    private static final int[][] CODES = {
            {KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
                    KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7,
                    KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10,
                    KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_INSERT,
                    KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_MOVE_HOME,
                    KeyEvent.KEYCODE_MOVE_END},
            {KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
                    KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
                    KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0,
                    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_DEL},
            {KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E,
                    KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U,
                    KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
                    KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
                    KeyEvent.KEYCODE_BACKSLASH},
            {KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
                    KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J,
                    KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SEMICOLON,
                    KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_ENTER},
            {KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C,
                    KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M,
                    KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_DOWN},
            {KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_FUNCTION, KEY_WIN,
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ALT_RIGHT,
                    KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT},
    };

    /** 每行各键宽度权重，行内合计 15；功能键行 17 个窄键均分。 */
    private static final float[][] WEIGHTS = {
            {0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f,
                    0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f, 0.882f},
            {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2},
            {1.5f, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5f},
            {1.75f, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2.25f},
            {2.25f, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0.917f, 0.917f, 0.916f},
            {1.25f, 1, 1.25f, 1.25f, 5, 1.25f, 1.25f, 0.917f, 0.917f, 0.916f},
    };

    /** 功能键行更矮，且与主键区拉开一点距离。 */
    private static final float[] ROW_WEIGHTS = {0.8f, 1, 1, 1, 1, 1};

    private final Sink sink;
    private final Set<Integer> held = new HashSet<Integer>();
    private final Map<Integer, TextView> modViews = new HashMap<Integer, TextView>();

    private final int defaultHeight;
    private final int minHeight;
    private final int maxHeight;
    private View handle;
    private View gripView;
    private boolean dragging;
    private float dragStartRawY;
    private int dragStartHeight;

    public OnScreenKeyboard(Context c, Sink sink) {
        super(c);
        this.sink = sink;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(c, 8);
        setPadding(pad, Theme.dp(c, 2), pad, pad);
        setBackground(Theme.rounded(0xF2F8F9FC, 8, c));

        defaultHeight = Theme.dp(c, DEFAULT_DP);
        minHeight = Theme.dp(c, MIN_DP);
        maxHeight = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.72f);

        handle = buildHandle(c);
        addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(c, HANDLE_DP)));
        for (int r = 0; r < LABELS.length; r++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, ROW_WEIGHTS[r]);
            if (r == 0) {
                lp.bottomMargin = Theme.dp(c, 6);
            }
            addView(buildRow(c, r), lp);
        }
    }

    /** 供会话层创建布局参数：沿用上次拖动后的高度。 */
    public int savedHeight() {
        int h = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_KB_HEIGHT, defaultHeight);
        return clamp(h);
    }

    private int clamp(int h) {
        return Math.max(minHeight, Math.min(maxHeight, h));
    }

    /** 屏幕坐标是否落在顶部拖动热区，用于会话层把按下事件放行给拖动逻辑。 */
    public boolean isResizeHandleAt(float rawX, float rawY) {
        if (getVisibility() != VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + getWidth()
                && rawY >= loc[1]
                && rawY <= loc[1] + Theme.dp(getContext(), HOT_ZONE_DP);
    }

    private View buildHandle(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(0x00000000);
        gripView = new View(c);
        gripView.setBackground(Theme.rounded(GRIP_COLOR, 1, c));
        gripView.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(c, 36), Theme.dp(c, 2)));
        bar.addView(gripView);
        return bar;
    }

    /** 细线本身太窄抓不住：由容器拦截顶部一块不占布局的触摸热区来拖动高度。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                && getVisibility() == VISIBLE
                && ev.getY() <= Theme.dp(getContext(), HOT_ZONE_DP)) {
            beginResize(ev);
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return dragging;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateHeight(e.getRawY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    endResize();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    private void beginResize(MotionEvent e) {
        dragging = true;
        dragStartRawY = e.getRawY();
        ViewGroup.LayoutParams lp = getLayoutParams();
        dragStartHeight = lp != null ? lp.height : defaultHeight;
        if (gripView != null) {
            gripView.setBackground(Theme.rounded(Theme.ACCENT, 1, getContext()));
        }
    }

    private void updateHeight(float rawY) {
        // 底边固定：手指上移则顶边上移、键盘变高
        int h = clamp((int) (dragStartHeight + dragStartRawY - rawY));
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            setLayoutParams(lp);
        }
    }

    private void endResize() {
        dragging = false;
        ViewGroup.LayoutParams lp = getLayoutParams();
        int finalH = lp != null ? lp.height : defaultHeight;
        getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_KB_HEIGHT, clamp(finalH)).apply();
        if (gripView != null) {
            gripView.setBackground(Theme.rounded(GRIP_COLOR, 1, getContext()));
        }
    }

    private LinearLayout buildRow(Context c, int rowIndex) {
        LinearLayout row = Theme.horizontal(c);
        String[] labels = LABELS[rowIndex];
        int[] codes = CODES[rowIndex];
        float[] weights = WEIGHTS[rowIndex];
        for (int i = 0; i < labels.length; i++) {
            final int code = codes[i];
            final boolean modifier = isModifier(code);
            final TextView key = keyView(c, labels[i], weights[i], modifier);
            if (modifier) {
                modViews.put(code, key);
            }
            key.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onKey(code, modifier, key);
                }
            });
            row.addView(key);
        }
        return row;
    }

    private TextView keyView(Context c, String label, float weight, boolean modifier) {
        TextView tv = new TextView(c);
        int sep = label.indexOf('|');
        boolean symbolPair = label.indexOf('\n') >= 0;
        // '|' 只作功能键副标签分隔符；键面本身以 | 开头（|\ 键）必须走普通双标注
        boolean subLabel = sep > 0 && !symbolPair;
        if (subLabel) {
            String main = label.substring(0, sep);
            String sub = label.substring(sep + 1);
            SpannableString text = new SpannableString(main + "\n" + sub);
            int from = main.length() + 1;
            text.setSpan(new RelativeSizeSpan(0.62f), from, text.length(), 0);
            text.setSpan(new ForegroundColorSpan(SUB_LABEL), from, text.length(), 0);
            tv.setText(text);
        } else {
            tv.setText(label);
        }
        tv.setTextColor(Theme.TEXT_DARK);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.textSp(c, R.dimen.text_small));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(false);
        tv.setMaxLines(2);
        // 双标注键（如 ! / 1、~ / `）把上标与字符拉开，避免两行挤在一起
        boolean twoLine = subLabel || symbolPair;
        tv.setLineSpacing(twoLine ? Theme.dp(c, 2) : 0, twoLine ? 1.12f : 1f);
        tv.setClickable(true);
        tv.setBackground(Theme.outlined(KEY_BG, KEY_BORDER, 4, c));
        tv.setPadding(Theme.dp(c, 2), 0, Theme.dp(c, 2), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(Theme.dp(c, 2), Theme.dp(c, 2), Theme.dp(c, 2), Theme.dp(c, 2));
        tv.setLayoutParams(lp);
        if (!modifier) {
            tv.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        v.setBackgroundColor(KEY_PRESSED);
                    } else if (e.getAction() == MotionEvent.ACTION_UP
                            || e.getAction() == MotionEvent.ACTION_CANCEL) {
                        v.setBackground(Theme.outlined(KEY_BG, KEY_BORDER, 4, v.getContext()));
                    }
                    return false;
                }
            });
        }
        return tv;
    }

    private static boolean isModifier(int code) {
        return code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
                || code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_ALT_LEFT
                || code == KeyEvent.KEYCODE_ALT_RIGHT || code == KEY_WIN;
    }

    private void onKey(int code, boolean modifier, TextView key) {
        if (modifier) {
            boolean nowHeld = !held.contains(code);
            if (nowHeld) {
                held.add(code);
            } else {
                held.remove(code);
            }
            sink.holdKey(code, nowHeld);
            if (nowHeld) {
                key.setBackground(Theme.rounded(KEY_HELD, 4, getContext()));
                key.setTextColor(0xFFFFFFFF);
            } else {
                key.setBackground(Theme.outlined(KEY_BG, KEY_BORDER, 4, getContext()));
                key.setTextColor(Theme.TEXT_DARK);
            }
        } else {
            sink.tapKey(code);
        }
    }

    /** 面板隐藏时把还按着的修饰键全部松开，避免远端一直保持 Ctrl/Shift 按下。 */
    public void releaseAllModifiers() {
        for (Integer code : held) {
            sink.holdKey(code, false);
        }
        held.clear();
        for (TextView v : modViews.values()) {
            v.setBackground(Theme.outlined(KEY_BG, KEY_BORDER, 4, getContext()));
            v.setTextColor(Theme.TEXT_DARK);
        }
    }

    /** 供会话层把自定义 Win 标记翻译成真实扫描码。 */
    public static int normalize(int code) {
        return code == KEY_WIN ? KeyEvent.KEYCODE_WINDOW : code;
    }
}
