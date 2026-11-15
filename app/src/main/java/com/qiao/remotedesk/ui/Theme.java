package com.qiao.remotedesk.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.qiao.remotedesk.R;

/** 自绘深色主题的控件工厂。不依赖 AndroidX，所有尺寸按 dp／资源限定符换算。 */
public final class Theme {

    public static final int TOP = 0xFF232A3A;
    public static final int TOP_ALT = 0xFF2B3244;
    public static final int SIDE = 0xFF1F2532;
    public static final int SIDE_ITEM = 0xFF2A3242;
    public static final int CONTENT = 0xFFF4F6F9;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int STATUS_BG = 0xFFEDF0F5;
    public static final int TEXT_DARK = 0xFF2C3444;
    public static final int TEXT_LIGHT = 0xFFE7EBF3;
    public static final int MUTED = 0xFF8A93A5;
    public static final int ACCENT = 0xFF2F6BFF;
    public static final int LINE = 0xFFDDE3EC;
    /** 下拉框描边：比输入框的 LINE 略深，保证在白色表单面板上边界可见。 */
    public static final int SPINNER_BORDER = 0xFFB6C3D6;
    public static final int OK = 0xFF16A34A;
    public static final int FAIL = 0xFFDC2626;
    public static final int WARN = 0xFFF59E0B;

    public static final int BTN_PRIMARY = 0;
    public static final int BTN_DARK = 1;
    public static final int BTN_LIGHT = 2;
    public static final int BTN_FLAT = 3;

    private Theme() {
    }

    public static int dp(Context c, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics());
    }

    public static int dimen(Context c, int id) {
        return c.getResources().getDimensionPixelSize(id);
    }

    public static float textSp(Context c, int id) {
        return c.getResources().getDimension(id) / c.getResources().getDisplayMetrics().scaledDensity;
    }

    public static GradientDrawable rounded(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable outlined(int fill, int strokeColor, int radiusDp, Context c) {
        GradientDrawable d = rounded(fill, radiusDp, c);
        d.setStroke(dp(c, 1), strokeColor);
        return d;
    }

    public static TextView label(Context c, String text, int color, int dimenId) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp(c, dimenId));
        return t;
    }

    public static TextView title(Context c, String text) {
        TextView t = label(c, text, TEXT_DARK, R.dimen.text_title);
        t.getPaint().setFakeBoldText(true);
        return t;
    }

    public static TextView hint(Context c, String text) {
        return label(c, text, MUTED, R.dimen.text_small);
    }

    public static Button button(Context c, String text, int kind) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp(c, R.dimen.text_body));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setGravity(Gravity.CENTER);
        switch (kind) {
            case BTN_PRIMARY:
                b.setTextColor(Color.WHITE);
                b.setBackground(rounded(ACCENT, 4, c));
                break;
            case BTN_DARK:
                b.setTextColor(TEXT_LIGHT);
                b.setBackground(rounded(TOP_ALT, 4, c));
                break;
            case BTN_LIGHT:
                b.setTextColor(TEXT_DARK);
                b.setBackground(outlined(SURFACE, LINE, 4, c));
                break;
            default:
                b.setTextColor(ACCENT);
                b.setBackground(rounded(Color.TRANSPARENT, 4, c));
                break;
        }
        int h = dimen(c, R.dimen.button_height);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, h));
        return b;
    }

    public static EditText input(Context c, String hint, boolean password) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(TEXT_DARK);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp(c, R.dimen.text_body));
        e.setBackground(outlined(SURFACE, LINE, 4, c));
        e.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        e.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return e;
    }

    public static Spinner spinner(Context c, String[] items) {
        Spinner s = new Spinner(c);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(c,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        // 系统背景里带下拉箭头，保留它，只在最上层叠一圈 1dp 描边，
        // 让下拉框与白色表单面板明确区分开。
        android.graphics.drawable.Drawable base = s.getBackground();
        int pl = s.getPaddingLeft();
        int pt = s.getPaddingTop();
        int pr = s.getPaddingRight();
        int pb = s.getPaddingBottom();
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(Color.TRANSPARENT);
        stroke.setCornerRadius(dp(c, 4));
        stroke.setStroke(dp(c, 1), SPINNER_BORDER);
        if (base != null) {
            android.graphics.drawable.LayerDrawable layers =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{base, stroke});
            // 默认 NESTED 会把上层按底层 9-patch 的内边距往里缩，描边就会比
            // 控件本身窄一截，看起来和右侧的输入框不对齐；STACK 让两层共用同一边界。
            layers.setPaddingMode(android.graphics.drawable.LayerDrawable.PADDING_MODE_STACK);
            s.setBackground(layers);
        } else {
            stroke.setColor(SURFACE);
            s.setBackground(stroke);
        }
        // LayerDrawable 不继承原 9-patch 的内边距，这里显式恢复并左右各留点余量
        s.setPadding(pl + dp(c, 6), pt, pr + dp(c, 4), pb);
        s.setPopupBackgroundDrawable(rounded(SURFACE, 4, c));
        s.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return s;
    }

    /** 表单滑杆：去掉系统为滑块预留的左右内边距，让横线两端与输入框边框对齐。 */
    public static android.widget.SeekBar seekBar(Context c, int max, int progress) {
        android.widget.SeekBar bar = new android.widget.SeekBar(c);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setPadding(0, 0, 0, 0);
        bar.setThumbOffset(0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(c, 28), 1f));
        return bar;
    }

    public static CheckBox check(Context c, String text, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setTextColor(TEXT_DARK);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp(c, R.dimen.text_body));
        return cb;
    }

    /** 表单行：左侧定宽标签 + 右侧控件。HD 平板标签列更宽。 */
    public static LinearLayout formRow(Context c, String name, View field) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = label(c, name, TEXT_DARK, R.dimen.text_body);
        Resources res = c.getResources();
        int labelWidth = dp(c, res.getConfiguration().smallestScreenWidthDp >= 720 ? 160 : 120);
        tv.setLayoutParams(new LinearLayout.LayoutParams(labelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(tv);
        row.addView(field);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        row.setLayoutParams(lp);
        return row;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
}
