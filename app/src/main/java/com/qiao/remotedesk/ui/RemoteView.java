package com.qiao.remotedesk.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;

import com.qiao.remotedesk.rdp.FreeRdpSession;

/**
 * 远端画面视图：把 FreeRDP 渲染出的 Bitmap 等比缩放居中绘制，并把触摸 / 按键
 * 换算成远端坐标后转发给会话。
 */
public class RemoteView extends View {

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private FreeRdpSession session;
    private String statusText = "正在连接…";
    private boolean showStatus = true;

    private float lastX = -1;
    private float lastY = -1;

    // ---------------- 操作模式 ----------------
    /** 触控模式：手指落点直接对应远端坐标。 */
    public static final int MODE_TOUCH = 0;
    /** 模拟鼠标模式：手指像触摸板一样相对移动本地光标，轻点 = 左键。 */
    public static final int MODE_MOUSE = 1;

    private static final long LONG_PRESS_MS = 500;
    /** 模拟点击时 down/up 的最小间隔，避免服务端忽略 1ms 的瞬时按压。 */
    private static final int CLICK_HOLD_MS = 70;
    /** 模拟鼠标的相对移动灵敏度。 */
    private static final float MOUSE_SENS = 1.6f;

    private int inputMode = MODE_TOUCH;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            longPressed = true;
        }
    };

    private float touchStartX;
    private float touchStartY;
    private float touchLastX;
    private float touchLastY;
    private boolean moved;
    private boolean longPressed;
    private boolean dragging;
    private float cursorX;
    private float cursorY;
    private boolean cursorReady;
    private float wheelAccum;

    /** 鼠标按钮事件可能同时从 onTouchEvent 和 onGenericMotionEvent 到达，用状态去重。 */
    private boolean mousePressSent;
    private int mousePressedButton;
    private long mouseDownAt;

    private final Paint cursorShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorEdge = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---------------- 本地视图缩放 / 平移（只影响本机绘制，不动远端分辨率） ----------------
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 5f;
    /** 松手后缩放落在这个区间且画面接近居中，就吸回 100% 居中（还原初始全屏）。 */
    private static final float SNAP_ZOOM_MIN = 0.9f;
    private static final float SNAP_ZOOM_MAX = 1.1f;
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_ZOOM = 1;
    private static final int GESTURE_PAN = 2;
    private static final int GESTURE_WHEEL = 3;

    /** 相对「适配窗口」的额外放大倍数，1 = 整屏铺满。 */
    private float userZoom = 1f;
    /** 缩放后的额外平移，单位是屏幕像素。 */
    private float panX;
    private float panY;
    /** 适配窗口时的基准缩放，onDraw / 触摸换算共用。 */
    private float baseScale = 1f;

    private int gesture = GESTURE_NONE;
    private float gestureStartDist;
    private float gestureStartZoom = 1f;
    private float gestureFocusX;
    private float gestureFocusY;
    private float gestureStartFocusX;
    private float gestureStartFocusY;
    private float zoomAnchorCx;
    private float zoomAnchorCy;
    private long gestureDownAt;
    private float gestureMaxMove;
    private boolean multiTouchSeen;

    private final float pinchSlop;
    private final float tapSlop;
    private final float wheelStep;
    private final Paint zoomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RemoteView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF10141A);
        textPaint.setColor(0xFFBFC9DA);
        textPaint.setTextSize(Theme.dp(c, 16));
        textPaint.setTextAlign(Paint.Align.CENTER);
        pinchSlop = Theme.dp(c, 8);
        tapSlop = Theme.dp(c, 12);
        wheelStep = Theme.dp(c, 40);
        zoomPaint.setColor(0xCCBFC9DA);
        zoomPaint.setTextSize(Theme.dp(c, 12));
        zoomPaint.setTextAlign(Paint.Align.RIGHT);
        // 白色箭头在白色远端桌面上要有一圈半透明阴影 + 深色描边才能区分。
        // 顺序固定：阴影 → 白色填充 → 深色描边；纯 STROKE 保证四边线宽完整
        // （原来 FILL_AND_STROKE 先画边再填充，白色会盖掉描边内侧一半）。
        // 阴影只留极淡一圈，避免深色轮廓堆成「粗黑边卡通箭头」
        cursorShadow.setColor(0x26000000);
        cursorShadow.setStyle(Paint.Style.STROKE);
        cursorShadow.setStrokeWidth(Theme.dp(c, 2));
        cursorShadow.setStrokeJoin(Paint.Join.ROUND);
        cursorFill.setColor(0xFFFFFFFF);
        cursorFill.setStyle(Paint.Style.FILL);
        cursorEdge.setColor(0xFF14202C);
        cursorEdge.setStyle(Paint.Style.STROKE);
        cursorEdge.setStrokeWidth(Theme.dp(c, 1));
        cursorEdge.setStrokeJoin(Paint.Join.ROUND);
        applySmallPointerIcon();
    }

    public void setSession(FreeRdpSession s) {
        session = s;
        invalidate();
    }

    public void setStatus(String text, boolean visible) {
        statusText = text;
        showStatus = visible;
        invalidate();
    }

    public void onFrame() {
        showStatus = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap bmp = session == null ? null : session.bitmap();
        computeDst();
        if (bmp == null || bmp.isRecycled() || dstRect.isEmpty()) {
            canvas.drawText(statusText, getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }

        canvas.drawBitmap(bmp, srcRect, dstRect, paint);

        if (showStatus) {
            canvas.drawText(statusText, getWidth() / 2f, getHeight() / 2f, textPaint);
        }
        // 鼠标模式的核心反馈就是本地虚拟光标，必须始终绘制。
        // 旧逻辑用「是否接过真实鼠标」这个一次性置位、永不复位的标志屏蔽它，
        // 导致接过一次鼠标后（哪怕已拔掉 / 重新切模式）光标永久消失。
        if (inputMode == MODE_MOUSE) {
            ensureCursor();
            drawCursor(canvas);
        }
        String tag = inputMode == MODE_MOUSE ? "鼠标模式" : "";
        if (Math.abs(userZoom - 1f) > 0.01f) {
            tag += (tag.isEmpty() ? "" : " · ")
                    + String.format(java.util.Locale.ROOT, "%.1f×", userZoom);
        }
        if (!tag.isEmpty()) {
            canvas.drawText(tag, getWidth() - Theme.dp(getContext(), 10),
                    getHeight() - Theme.dp(getContext(), 10), zoomPaint);
        }
    }

    /** 依据当前 zoom / pan 计算画面落点；绘制与坐标换算共用，保证点哪打哪。 */
    private void computeDst() {
        Bitmap bmp = session == null ? null : session.bitmap();
        int vw = getWidth();
        int vh = getHeight();
        if (bmp == null || bmp.isRecycled() || vw <= 0 || vh <= 0) {
            dstRect.setEmpty();
            return;
        }
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) {
            dstRect.setEmpty();
            return;
        }
        baseScale = Math.min((float) vw / bw, (float) vh / bh);
        clampPan(bw, bh, vw, vh);
        float s = baseScale * userZoom;
        float dw = bw * s;
        float dh = bh * s;
        float left = (vw - dw) / 2f + panX;
        float top = (vh - dh) / 2f + panY;
        dstRect.set(left, top, left + dw, top + dh);
        srcRect.set(0, 0, bw, bh);
    }

    /** 平移量限制在画面边缘内，避免把远端桌面拖出屏幕后找不回来。 */
    private void clampPan(int bw, int bh, int vw, int vh) {
        float s = baseScale * userZoom;
        float mx = Math.max(0f, (bw * s - vw) / 2f);
        float my = Math.max(0f, (bh * s - vh) / 2f);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
    }

    private boolean pannable(int bw, int bh, int vw, int vh) {
        float s = baseScale * userZoom;
        return bw * s > vw + 1 || bh * s > vh + 1;
    }

    /**
     * 双指手势结束时调用：画面尺寸已回到屏幕的 90%~110%、且平移量很小，
     * 说明用户想回到初始全屏状态，直接吸附成 100% + 居中，省掉手动微调。
     */
    private void snapToFullscreenIfClose() {
        if (userZoom < SNAP_ZOOM_MIN || userZoom > SNAP_ZOOM_MAX) {
            return;
        }
        float slop = Theme.dp(getContext(), 36);
        if (Math.abs(panX) > slop || Math.abs(panY) > slop) {
            return;
        }
        userZoom = 1f;
        panX = 0f;
        panY = 0f;
        invalidate();
    }

    // ------------------------------------------------------------------
    // 坐标换算
    // ------------------------------------------------------------------

    private int mapX(float x) {
        Bitmap bmp = session == null ? null : session.bitmap();
        if (bmp == null || dstRect.width() <= 0) {
            return 0;
        }
        int v = (int) ((x - dstRect.left) * bmp.getWidth() / dstRect.width());
        return Math.max(0, Math.min(bmp.getWidth() - 1, v));
    }

    private int mapY(float y) {
        Bitmap bmp = session == null ? null : session.bitmap();
        if (bmp == null || dstRect.height() <= 0) {
            return 0;
        }
        int v = (int) ((y - dstRect.top) * bmp.getHeight() / dstRect.height());
        return Math.max(0, Math.min(bmp.getHeight() - 1, v));
    }

    // ------------------------------------------------------------------
    // 操作模式与输入原语
    // ------------------------------------------------------------------

    public int getInputMode() {
        return inputMode;
    }

    public float getZoom() {
        return userZoom;
    }

    /** 工具栏「视图」用：只改本地缩放矩阵，不动远端分辨率、不重连。 */
    public void setZoom(float z) {
        userZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        if (userZoom <= 1f) {
            panX = 0f;
            panY = 0f;
        }
        invalidate();
    }

    public void resetView() {
        userZoom = 1f;
        panX = 0f;
        panY = 0f;
        invalidate();
    }

    public void setInputMode(int mode) {
        int m = mode == MODE_MOUSE ? MODE_MOUSE : MODE_TOUCH;
        if (m == inputMode) {
            return;
        }
        cancelPendingInput();
        inputMode = m;
        invalidate();
    }

    /** 切换模式或中断手势时收干净状态，避免留下半个按下事件。 */
    private void cancelPendingInput() {
        ui.removeCallbacks(longPressTask);
        if (dragging) {
            rdpUp((int) lastX, (int) lastY, FreeRdpSession.PTR_BUTTON1);
        }
        resetGestureState();
    }

    private void resetGestureState() {
        gesture = GESTURE_NONE;
        multiTouchSeen = false;
        dragging = false;
        moved = false;
        longPressed = false;
        wheelAccum = 0f;
    }

    /** 投递一个 MOVE 坐标；会话层会把连续的过期坐标合并成 16ms 一个。 */
    private void rdpMove(int x, int y) {
        if (session != null && session.isConnected()) {
            session.mouse(x, y, FreeRdpSession.PTR_MOVE);
        }
    }

    private void rdpDown(int x, int y, int button) {
        // FreeRDP 官方客户端：MOVE 与按钮事件严格分离，混在一起会让 Windows
        // 忽略按钮转换（点击 / 长按 / 拖动全部失效）。
        rdpMove(x, y);
        if (session != null && session.isConnected()) {
            session.mouse(x, y, button | FreeRdpSession.PTR_DOWN);
        }
    }

    private void rdpUp(int x, int y, int button) {
        if (session != null && session.isConnected()) {
            session.mouse(x, y, button);
        }
    }

    /** 完整点击：down 后延迟一小段再 up，保证服务端能采样到按下状态。 */
    private void rdpClick(final int x, final int y, final int button) {
        rdpDown(x, y, button);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                rdpUp(x, y, button);
            }
        }, CLICK_HOLD_MS);
    }

    private void ensureCursor() {
        if (!cursorReady && !dstRect.isEmpty()) {
            cursorX = dstRect.centerX();
            cursorY = dstRect.centerY();
            cursorReady = true;
        }
        if (!dstRect.isEmpty()) {
            cursorX = Math.max(dstRect.left, Math.min(dstRect.right - 1, cursorX));
            cursorY = Math.max(dstRect.top, Math.min(dstRect.bottom - 1, cursorY));
        }
    }

    /** 模拟鼠标模式下画一个本地虚拟光标（纯本地绘制，不动远端分辨率）。 */
    private void drawCursor(Canvas canvas) {
        float x = cursorX;
        float y = cursorY;
        // 回到用户认可的「原尺寸 50%」= 7dp；描边/阴影同步调细，避免白芯被吃光
        float s = Theme.dp(getContext(), 7);
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, y + s * 1.7f);
        p.lineTo(x + s * 0.42f, y + s * 1.28f);
        p.lineTo(x + s * 0.78f, y + s * 1.95f);
        p.lineTo(x + s * 1.06f, y + s * 1.80f);
        p.lineTo(x + s * 0.70f, y + s * 1.14f);
        p.lineTo(x + s * 1.28f, y + s * 1.10f);
        p.close();
        // 先阴影、再白色填充、最后深色描边：白色远端桌面上也看得清，四边线宽完整
        canvas.drawPath(p, cursorShadow);
        canvas.drawPath(p, cursorFill);
        canvas.drawPath(p, cursorEdge);
    }

    /** 外接鼠标的系统默认箭头偏大，给本视图换成自绘的小箭头（不支持时自动忽略）。 */
    private void applySmallPointerIcon() {
        try {
            float d = getResources().getDisplayMetrics().density;
            float u = d;
            float stroke = 1.0f * d;
            float shadow = 2.0f * d;
            // 四周留白：原来路径从 x=0 起笔，描边向外扩的一半被 Bitmap 左边界裁掉，
            // 左侧等于没有边框线，白色箭头直接和白色背景糊在一起。
            float pad = Math.max(3f * d, shadow * 0.5f + stroke);
            int size = Math.max(12, Math.round(18 * u + 2 * pad));
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            Path arrow = new Path();
            arrow.moveTo(pad, pad);
            arrow.lineTo(pad, pad + 13 * u);
            arrow.lineTo(pad + 3.2f * u, pad + 10 * u);
            arrow.lineTo(pad + 6 * u, pad + 15.2f * u);
            arrow.lineTo(pad + 8.2f * u, pad + 14 * u);
            arrow.lineTo(pad + 5.4f * u, pad + 8.8f * u);
            arrow.lineTo(pad + 10 * u, pad + 8.5f * u);
            arrow.close();
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(0x26000000);
            shadowPaint.setStyle(Paint.Style.STROKE);
            shadowPaint.setStrokeWidth(shadow);
            shadowPaint.setStrokeJoin(Paint.Join.ROUND);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setColor(0xFF14202C);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            edge.setStrokeJoin(Paint.Join.ROUND);
            cv.drawPath(arrow, shadowPaint);
            cv.drawPath(arrow, fill);
            cv.drawPath(arrow, edge);
            // 热点跟着箭头尖端一起平移，点击位置仍精确对应指尖
            setPointerIcon(PointerIcon.create(bmp, pad, pad));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 外接真实鼠标（蓝牙 / USB / 触摸板）
    // ------------------------------------------------------------------

    private static boolean isMouseEvent(MotionEvent e) {
        if (e.getPointerCount() == 0) {
            return false;
        }
        return (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }

    private static int buttonFlag(int actionButton) {
        switch (actionButton) {
            case MotionEvent.BUTTON_PRIMARY:
                return FreeRdpSession.PTR_BUTTON1;
            case MotionEvent.BUTTON_SECONDARY:
                return FreeRdpSession.PTR_BUTTON2;
            case MotionEvent.BUTTON_TERTIARY:
                return FreeRdpSession.PTR_BUTTON3;
            default:
                return 0;
        }
    }

    private static int firstPressedButton(int state) {
        if ((state & MotionEvent.BUTTON_SECONDARY) != 0) {
            return FreeRdpSession.PTR_BUTTON2;
        }
        if ((state & MotionEvent.BUTTON_TERTIARY) != 0) {
            return FreeRdpSession.PTR_BUTTON3;
        }
        return FreeRdpSession.PTR_BUTTON1;
    }

    /** 两条事件通道共用一次按下状态，避免 down/up 重复发送。 */
    private void mouseDown(int x, int y, int button) {
        if (mousePressSent) {
            return;
        }
        mousePressSent = true;
        mousePressedButton = button;
        mouseDownAt = System.currentTimeMillis();
        rdpDown(x, y, button);
    }

    private void mouseUp(final int x, final int y) {
        if (!mousePressSent) {
            return;
        }
        final int button = mousePressedButton;
        mousePressSent = false;
        long wait = CLICK_HOLD_MS - (System.currentTimeMillis() - mouseDownAt);
        if (wait > 0) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    rdpUp(x, y, button);
                }
            }, wait);
        } else {
            rdpUp(x, y, button);
        }
    }

    /** 外接鼠标的 ACTION_DOWN/UP 兼容通道。 */
    private boolean handleMouseTouch(MotionEvent e) {
        computeDst();
        int x = mapX(e.getX());
        int y = mapY(e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mouseDown(x, y, firstPressedButton(e.getButtonState()));
                return true;
            case MotionEvent.ACTION_MOVE:
                rdpMove(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mouseUp(x, y);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (session == null || !session.isConnected() || !isMouseEvent(e)) {
            return super.onGenericMotionEvent(e);
        }
        computeDst();
        int x = mapX(e.getX());
        int y = mapY(e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                // 无按键滑动也要持续报坐标，Windows 右键菜单项才会随鼠标悬停高亮；
                // 标记为重要 MOVE，绕过去抖阈值，避免菜单项不跟随高亮
                session.hover(x, y);
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS: {
                int b = buttonFlag(e.getActionButton());
                if (b != 0) {
                    mouseDown(x, y, b);
                }
                return true;
            }
            case MotionEvent.ACTION_BUTTON_RELEASE:
                mouseUp(x, y);
                return true;
            case MotionEvent.ACTION_SCROLL: {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0 && session != null) {
                    session.wheel(x, y, v > 0 ? -1 : 1);
                }
                return true;
            }
            default:
                return super.onGenericMotionEvent(e);
        }
    }

    // ------------------------------------------------------------------
    // 触摸
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (session == null || !session.isConnected()) {
            return true;
        }
        if (isMouseEvent(e)) {
            return handleMouseTouch(e);
        }
        computeDst();
        if (dstRect.isEmpty()) {
            return true;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                requestFocus();
                resetGestureState();
                gestureDownAt = System.currentTimeMillis();
                gestureMaxMove = 0f;
                touchStartX = touchLastX = e.getX();
                touchStartY = touchLastY = e.getY();
                if (inputMode == MODE_MOUSE) {
                    // 触摸板式：按下不点击，只把远端光标对齐到本地虚拟光标
                    ensureCursor();
                    int x = mapX(cursorX);
                    int y = mapY(cursorY);
                    lastX = x;
                    lastY = y;
                    rdpMove(x, y);
                } else {
                    // 触控式：按下瞬间就发「按下转换」，让服务端拿到真实的按压时长
                    int x = mapX(e.getX());
                    int y = mapY(e.getY());
                    lastX = x;
                    lastY = y;
                    rdpDown(x, y, FreeRdpSession.PTR_BUTTON1);
                    dragging = true;
                }
                // 长按且未移动 → 抬起时改判右键（两种模式一致）
                ui.postDelayed(longPressTask, LONG_PRESS_MS);
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                ui.removeCallbacks(longPressTask);
                multiTouchSeen = true;
                if (dragging) {
                    // 第二根手指落下：先结束左键拖动，再转入双指手势
                    rdpUp((int) lastX, (int) lastY, FreeRdpSession.PTR_BUTTON1);
                    dragging = false;
                }
                gesture = GESTURE_NONE;
                gestureDownAt = System.currentTimeMillis();
                gestureMaxMove = 0f;
                wheelAccum = 0f;
                gestureStartDist = e.getPointerCount() >= 2 ? dist(e) : 0f;
                gestureStartZoom = userZoom;
                gestureStartFocusX = focusX(e);
                gestureStartFocusY = focusY(e);
                gestureFocusX = gestureStartFocusX;
                gestureFocusY = gestureStartFocusY;
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2) {
                    handleTwoFingerMove(e);
                    return true;
                }
                if (multiTouchSeen) {
                    // 双指结束后残留的那根手指不再当单指输入，避免误点击
                    return true;
                }
                float dx = e.getX() - touchStartX;
                float dy = e.getY() - touchStartY;
                if (!moved && Math.hypot(dx, dy) > tapSlop) {
                    moved = true;
                    ui.removeCallbacks(longPressTask);
                }
                if (inputMode == MODE_MOUSE) {
                    // 相对位移驱动本地光标，纯本地变换，不改远端分辨率、不重连
                    cursorX = Math.max(dstRect.left, Math.min(dstRect.right - 1,
                            cursorX + (e.getX() - touchLastX) * MOUSE_SENS));
                    cursorY = Math.max(dstRect.top, Math.min(dstRect.bottom - 1,
                            cursorY + (e.getY() - touchLastY) * MOUSE_SENS));
                    int x = mapX(cursorX);
                    int y = mapY(cursorY);
                    if (moved && longPressed && !dragging) {
                        // 长按后继续滑动 = 按住左键拖动
                        rdpDown(x, y, FreeRdpSession.PTR_BUTTON1);
                        dragging = true;
                    }
                    // 按下状态由服务端保持，移动只报坐标，绝不重发 button 位
                    rdpMove(x, y);
                    lastX = x;
                    lastY = y;
                    invalidate();
                } else {
                    int x = mapX(e.getX());
                    int y = mapY(e.getY());
                    rdpMove(x, y);
                    lastX = x;
                    lastY = y;
                }
                touchLastX = e.getX();
                touchLastY = e.getY();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                // 还有手指在屏上：保持多指状态，等最后一根手指抬起再收尾
                return true;

            case MotionEvent.ACTION_UP: {
                ui.removeCallbacks(longPressTask);
                if (multiTouchSeen) {
                    if (gesture == GESTURE_NONE
                            && System.currentTimeMillis() - gestureDownAt < 320
                            && gestureMaxMove < tapSlop) {
                        // 双指轻点 = 右键
                        rdpClick(mapX(gestureStartFocusX), mapY(gestureStartFocusY),
                                FreeRdpSession.PTR_BUTTON2);
                    }
                    snapToFullscreenIfClose();
                    resetGestureState();
                    return true;
                }

                int x = inputMode == MODE_MOUSE ? mapX(cursorX) : mapX(e.getX());
                int y = inputMode == MODE_MOUSE ? mapY(cursorY) : mapY(e.getY());
                lastX = x;
                lastY = y;

                if (longPressed && !moved) {
                    // 长按左键放开 = 右键；先松开已按下的左键，再补一次右键点击
                    if (dragging) {
                        rdpUp(x, y, FreeRdpSession.PTR_BUTTON1);
                    }
                    rdpClick(x, y, FreeRdpSession.PTR_BUTTON2);
                } else if (dragging) {
                    rdpUp(x, y, FreeRdpSession.PTR_BUTTON1);
                } else if (inputMode == MODE_MOUSE && !moved) {
                    // 模拟鼠标模式轻点 = 左键单击
                    rdpClick(x, y, FreeRdpSession.PTR_BUTTON1);
                }
                resetGestureState();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                ui.removeCallbacks(longPressTask);
                if (dragging) {
                    rdpUp((int) lastX, (int) lastY, FreeRdpSession.PTR_BUTTON1);
                }
                resetGestureState();
                return true;
            }

            default:
                return true;
        }
    }

    /**
     * 双指手势：捏合 = 本地缩放（缩放锚点在双指中点，画面不跳）；
     * 同距拖动 = 已放大时本地平移，未放大（无处可移）= 远程滚轮，保留原有习惯。
     */
    private void handleTwoFingerMove(MotionEvent e) {
        if (e.getPointerCount() < 2 || session == null) {
            return;
        }
        Bitmap bmp = session.bitmap();
        if (bmp == null || bmp.isRecycled()) {
            return;
        }
        int vw = getWidth();
        int vh = getHeight();
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
            return;
        }

        float d = dist(e);
        float fx = focusX(e);
        float fy = focusY(e);
        gestureMaxMove = Math.max(gestureMaxMove,
                Math.max(Math.abs(fx - gestureStartFocusX), Math.abs(fy - gestureStartFocusY)));

        if (gesture == GESTURE_NONE) {
            if (gestureStartDist > 1f && Math.abs(d - gestureStartDist) > pinchSlop) {
                // 进入捏合：以此刻为新基准，避免阈值判定造成画面跳变
                gesture = GESTURE_ZOOM;
                gestureStartDist = d;
                gestureStartZoom = userZoom;
                gestureStartFocusX = fx;
                gestureStartFocusY = fy;
                float s = baseScale * userZoom;
                zoomAnchorCx = (fx - ((vw - bw * s) / 2f + panX)) / s;
                zoomAnchorCy = (fy - ((vh - bh * s) / 2f + panY)) / s;
            } else if (gestureMaxMove > tapSlop) {
                gesture = pannable(bw, bh, vw, vh) ? GESTURE_PAN : GESTURE_WHEEL;
                gestureFocusX = fx;
                gestureFocusY = fy;
                wheelAccum = 0f;
            }
            return;
        }

        if (gesture == GESTURE_ZOOM) {
            float ratio = gestureStartDist > 1f ? d / gestureStartDist : 1f;
            userZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, gestureStartZoom * ratio));
            float s1 = baseScale * userZoom;
            float left1 = fx - zoomAnchorCx * s1;
            float top1 = fy - zoomAnchorCy * s1;
            panX = left1 - (vw - bw * s1) / 2f;
            panY = top1 - (vh - bh * s1) / 2f;
            clampPan(bw, bh, vw, vh);
            invalidate();
            return;
        }

        if (gesture == GESTURE_PAN) {
            panX += fx - gestureFocusX;
            panY += fy - gestureFocusY;
            gestureFocusX = fx;
            gestureFocusY = fy;
            clampPan(bw, bh, vw, vh);
            invalidate();
            return;
        }

        // GESTURE_WHEEL：未放大时双指上下滑动继续当滚轮用
        wheelAccum += fy - gestureFocusY;
        gestureFocusY = fy;
        int wx = mapX(fx);
        int wy = mapY(fy);
        while (wheelAccum > wheelStep) {
            wheelAccum -= wheelStep;
            session.wheel(wx, wy, -1);
        }
        while (wheelAccum < -wheelStep) {
            wheelAccum += wheelStep;
            session.wheel(wx, wy, 1);
        }
    }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static float focusX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2f;
    }

    private static float focusY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2f;
    }

    // ------------------------------------------------------------------
    // 键盘
    // ------------------------------------------------------------------

    /** 屏幕键盘：普通键按下后延时抬起，保证远端能采样到按下状态。 */
    public void tapKey(final int keyCode) {
        if (!sendKey(keyCode, true)) {
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendKey(keyCode, false);
            }
        }, CLICK_HOLD_MS);
    }

    /** 屏幕键盘：修饰键保持按下 / 松开，用于 Ctrl+C 之类组合键。 */
    public void holdKey(int keyCode, boolean down) {
        sendKey(keyCode, down);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (sendKey(keyCode, true)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (sendKey(keyCode, false)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean sendKey(int keyCode, boolean down) {
        if (session == null || !session.isConnected()) {
            return false;
        }
        int sc = scancode(keyCode);
        if (sc == 0) {
            int ch = keyChar(keyCode);
            if (ch != 0) {
                session.unicode(ch, down);
                return true;
            }
            return false;
        }
        session.key(sc & 0xFF, (sc & 0x100) != 0, down);
        return true;
    }

    private static int keyChar(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
                return ' ';
            case KeyEvent.KEYCODE_ENTER:
                return '\r';
            case KeyEvent.KEYCODE_TAB:
                return '\t';
            default:
                if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                    return 'a' + (keyCode - KeyEvent.KEYCODE_A);
                }
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    return '0' + (keyCode - KeyEvent.KEYCODE_0);
                }
                return 0;
        }
    }

    /** 返回 RDP 扫描码；bit8 置位表示扩展键。0 表示未映射。 */
    private static int scancode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
                return 0x01;
            case KeyEvent.KEYCODE_1:
                return 0x02;
            case KeyEvent.KEYCODE_2:
                return 0x03;
            case KeyEvent.KEYCODE_3:
                return 0x04;
            case KeyEvent.KEYCODE_4:
                return 0x05;
            case KeyEvent.KEYCODE_5:
                return 0x06;
            case KeyEvent.KEYCODE_6:
                return 0x07;
            case KeyEvent.KEYCODE_7:
                return 0x08;
            case KeyEvent.KEYCODE_8:
                return 0x09;
            case KeyEvent.KEYCODE_9:
                return 0x0A;
            case KeyEvent.KEYCODE_0:
                return 0x0B;
            case KeyEvent.KEYCODE_MINUS:
                return 0x0C;
            case KeyEvent.KEYCODE_EQUALS:
                return 0x0D;
            case KeyEvent.KEYCODE_DEL:
                return 0x0E;
            case KeyEvent.KEYCODE_TAB:
                return 0x0F;
            case KeyEvent.KEYCODE_Q:
                return 0x10;
            case KeyEvent.KEYCODE_W:
                return 0x11;
            case KeyEvent.KEYCODE_E:
                return 0x12;
            case KeyEvent.KEYCODE_R:
                return 0x13;
            case KeyEvent.KEYCODE_T:
                return 0x14;
            case KeyEvent.KEYCODE_Y:
                return 0x15;
            case KeyEvent.KEYCODE_U:
                return 0x16;
            case KeyEvent.KEYCODE_I:
                return 0x17;
            case KeyEvent.KEYCODE_O:
                return 0x18;
            case KeyEvent.KEYCODE_P:
                return 0x19;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return 0x1A;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return 0x1B;
            case KeyEvent.KEYCODE_ENTER:
                return 0x1C;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return 0x1D;
            case KeyEvent.KEYCODE_A:
                return 0x1E;
            case KeyEvent.KEYCODE_S:
                return 0x1F;
            case KeyEvent.KEYCODE_D:
                return 0x20;
            case KeyEvent.KEYCODE_F:
                return 0x21;
            case KeyEvent.KEYCODE_G:
                return 0x22;
            case KeyEvent.KEYCODE_H:
                return 0x23;
            case KeyEvent.KEYCODE_J:
                return 0x24;
            case KeyEvent.KEYCODE_K:
                return 0x25;
            case KeyEvent.KEYCODE_L:
                return 0x26;
            case KeyEvent.KEYCODE_SEMICOLON:
                return 0x27;
            case KeyEvent.KEYCODE_APOSTROPHE:
                return 0x28;
            case KeyEvent.KEYCODE_GRAVE:
                return 0x29;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return 0x2A;
            case KeyEvent.KEYCODE_BACKSLASH:
                return 0x2B;
            case KeyEvent.KEYCODE_Z:
                return 0x2C;
            case KeyEvent.KEYCODE_X:
                return 0x2D;
            case KeyEvent.KEYCODE_C:
                return 0x2E;
            case KeyEvent.KEYCODE_V:
                return 0x2F;
            case KeyEvent.KEYCODE_B:
                return 0x30;
            case KeyEvent.KEYCODE_N:
                return 0x31;
            case KeyEvent.KEYCODE_M:
                return 0x32;
            case KeyEvent.KEYCODE_COMMA:
                return 0x33;
            case KeyEvent.KEYCODE_PERIOD:
                return 0x34;
            case KeyEvent.KEYCODE_SLASH:
                return 0x35;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return 0x36;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                return 0x37;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return 0x38;
            case KeyEvent.KEYCODE_SPACE:
                return 0x39;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return 0x3A;
            case KeyEvent.KEYCODE_F1:
                return 0x3B;
            case KeyEvent.KEYCODE_F2:
                return 0x3C;
            case KeyEvent.KEYCODE_F3:
                return 0x3D;
            case KeyEvent.KEYCODE_F4:
                return 0x3E;
            case KeyEvent.KEYCODE_F5:
                return 0x3F;
            case KeyEvent.KEYCODE_F6:
                return 0x40;
            case KeyEvent.KEYCODE_F7:
                return 0x41;
            case KeyEvent.KEYCODE_F8:
                return 0x42;
            case KeyEvent.KEYCODE_F9:
                return 0x43;
            case KeyEvent.KEYCODE_F10:
                return 0x44;
            case KeyEvent.KEYCODE_NUM_LOCK:
                return 0x45;
            case KeyEvent.KEYCODE_SCROLL_LOCK:
                return 0x46;
            case KeyEvent.KEYCODE_F11:
                return 0x57;
            case KeyEvent.KEYCODE_F12:
                return 0x58;
            case KeyEvent.KEYCODE_INSERT:
                return 0x100 | 0x52;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return 0x100 | 0x53;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0x100 | 0x4B;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0x100 | 0x4D;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0x100 | 0x48;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0x100 | 0x50;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0x100 | 0x47;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0x100 | 0x4F;
            case KeyEvent.KEYCODE_PAGE_UP:
                return 0x100 | 0x49;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 0x100 | 0x51;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return 0x100 | 0x1D;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return 0x100 | 0x38;
            case KeyEvent.KEYCODE_WINDOW:
                return 0x100 | 0x5B;
            default:
                return 0;
        }
    }
}
