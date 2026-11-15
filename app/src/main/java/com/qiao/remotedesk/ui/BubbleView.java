package com.qiao.remotedesk.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** 贴在画面右缘的半圆气泡：单击最小化、双击切主机、长按弹面板、按住拖动换位置。 */
public class BubbleView extends View {

    public static final int ACT_MINIMIZE = 0;
    public static final int ACT_SWITCH = 1;
    public static final int ACT_MENU = 2;
    public static final int ACT_FULLSCREEN = 3;
    public static final int ACT_RESTORE = 4;

    public interface Callback {
        void onAction(int action);

        /** 气泡被上下拖动时通知外部同步菜单的垂直位置。 */
        void onMove(float translationY);
    }

    private final Callback cb;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int diameter;
    private int borderColor = Color.WHITE;
    /** 透明程度：0 = 完全不透明，0.9 = 最透明；数值越大画得越淡。 */
    private float transparency = 0.5f;
    private int latencyColor = Theme.MUTED;
    private int cardColor = Theme.ACCENT;
    private float startRawY;
    private float startTranslation;
    private boolean dragging;
    private long downTime;
    private int clicks;
    private final Runnable clickTask = new Runnable() {
        @Override
        public void run() {
            if (clicks == 1) {
                cb.onAction(ACT_MINIMIZE);
            }
            clicks = 0;
        }
    };

    public BubbleView(Context c, Callback cb, int diameterDp, int cardColor) {
        super(c);
        this.cb = cb;
        this.cardColor = cardColor;
        this.diameter = Theme.dp(c, diameterDp);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Theme.dp(c, 2));
        dot.setStyle(Paint.Style.FILL);
        applyColors();
    }

    public void setDiameterDp(int dp) {
        diameter = Theme.dp(getContext(), dp);
        applyColors();
        requestLayout();
        invalidate();
    }

    public void setBorderColor(int color) {
        borderColor = color;
        applyColors();
        invalidate();
    }

    /** @param value 透明程度 0~0.9；内部换算成不透明度绘制，方向不再反。 */
    public void setOpacity(float value) {
        transparency = value < 0f ? 0f : (value > 0.9f ? 0.9f : value);
        applyColors();
        invalidate();
    }

    /** 建链耗时指示灯：<1000ms 浅绿、1000~2000ms 橙、2000ms+ 红、测不到中性灰。 */
    public void setLatency(long millis) {
        latencyColor = millis < 0 ? Theme.MUTED
                : millis < 1000 ? 0xFF86EFAC
                : millis < 2000 ? 0xFFFBBF24 : 0xFFF87171;
        dot.setColor(latencyColor);
        invalidate();
    }

    private void applyColors() {
        float alpha = 1f - transparency;
        fill.setColor(cardColor);
        fill.setAlpha((int) (255 * alpha));
        stroke.setColor(borderColor);
        stroke.setAlpha((int) (200 * alpha));
        dot.setColor(latencyColor);
        dot.setAlpha((int) (255 * alpha));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(diameter / 2, diameter);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = diameter / 2f;
        // 圆心压在右边界上，右半被父容器裁掉，视觉上就是贴边的半圆
        canvas.drawCircle(getWidth(), r, r - Theme.dp(getContext(), 2), fill);
        canvas.drawCircle(getWidth(), r, r - Theme.dp(getContext(), 2), stroke);
        canvas.drawCircle(getWidth(), r, r * 0.26f, dot);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startRawY = event.getRawY();
                startTranslation = getTranslationY();
                dragging = false;
                downTime = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getRawY() - startRawY;
                if (Math.abs(dy) > Theme.dp(getContext(), 4)) {
                    dragging = true;
                }
                if (dragging) {
                    float next = startTranslation + dy;
                    if (next < -Theme.dp(getContext(), 200)) {
                        next = -Theme.dp(getContext(), 200);
                    }
                    if (next > Theme.dp(getContext(), 600)) {
                        next = Theme.dp(getContext(), 600);
                    }
                    setTranslationY(next);
                    cb.onMove(next);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    return true;
                }
                if (System.currentTimeMillis() - downTime > 600) {
                    cb.onAction(ACT_MENU);
                    return true;
                }
                clicks++;
                handler.removeCallbacks(clickTask);
                if (clicks >= 2) {
                    clicks = 0;
                    cb.onAction(ACT_SWITCH);
                } else {
                    handler.postDelayed(clickTask, 300);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
