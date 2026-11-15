package com.qiao.remotedesk.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qiao.remotedesk.R;
import com.qiao.remotedesk.model.CardColors;
import com.qiao.remotedesk.model.ConnectionProfile;
import com.qiao.remotedesk.model.UiState;

import java.util.List;

/** 启动首页：九宫格卡片。列数按内容区实际宽度与最小宽度分级计算，HD 平板最多 4 列。 */
public class HomeView extends LinearLayout {

    public interface Callback {
        List<ConnectionProfile> profiles();

        boolean isRunning(ConnectionProfile p);

        UiState uiState();

        void onOpen(ConnectionProfile p);

        void onMenu(ConnectionProfile p, View anchor);

        void onNew();
    }

    private final Callback cb;
    private final TextView counter;
    private final LinearLayout grid;
    private int lastWidth = -1;

    public HomeView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dimen(c, R.dimen.content_pad);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(Theme.CONTENT);

        LinearLayout head = Theme.horizontal(c);
        head.addView(Theme.title(c, "远程主机"));
        counter = Theme.hint(c, "0 台");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = Theme.dp(c, 8);
        counter.setLayoutParams(cp);
        head.addView(counter);
        Button add = Theme.button(c, "＋ 新建连接", Theme.BTN_LIGHT);
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HomeView.this.cb.onNew();
            }
        });
        head.addView(add);
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View gap = new View(c);
        gap.setLayoutParams(new LayoutParams(1, Theme.dimen(c, R.dimen.section_gap)));
        addView(gap);

        grid = Theme.vertical(c);
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(false);
        sv.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 不能在 layout 过程中同步 remove/add 子 View：新 View 不会被本轮测量，
        // 结果是尺寸恒为 0、整块卡片不绘制。推到下一帧再重建。
        post(new Runnable() {
            @Override
            public void run() {
                refresh(getWidth());
            }
        });
    }

    /** 手动触发刷新（配置变化、会话状态变化后）。 */
    public void refresh() {
        refresh(lastWidth > 0 ? lastWidth : getWidth());
    }

    private void refresh(int widthPx) {
        if (widthPx <= 0) {
            return;
        }
        lastWidth = widthPx;
        Context c = getContext();
        int pad = Theme.dimen(c, R.dimen.content_pad);
        int gap = Theme.dp(c, 12);
        int minCard = Theme.dimen(c, R.dimen.card_min_width);
        int avail = widthPx - pad * 2;
        int cols = (avail + gap) / (minCard + gap);
        int sw = c.getResources().getConfiguration().smallestScreenWidthDp;
        if (sw < 600) {
            cols = Math.min(cols, 2);
        } else if (sw < 720) {
            cols = Math.min(cols, 3);
        } else {
            cols = Math.min(cols, 4);
        }
        cols = Math.max(1, cols);

        List<ConnectionProfile> profiles = cb.profiles();
        counter.setText(profiles.size() + " 台");
        grid.removeAllViews();

        int cardH = Theme.dimen(c, R.dimen.card_height);
        // 贴片固定尺寸：始终按「满行 cols 个」时的宽度计算，行内不足也不拉伸
        int cardW = (avail - (cols - 1) * gap) / cols;
        if (cardW < minCard) {
            cardW = minCard;
        }
        LinearLayout row = null;
        for (int i = 0; i < profiles.size(); i++) {
            if (i % cols == 0) {
                row = Theme.horizontal(c);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = gap;
                row.setLayoutParams(rp);
                grid.addView(row);
            }
            final ConnectionProfile p = profiles.get(i);
            boolean lastInRow = (i % cols == cols - 1) || (i == profiles.size() - 1);
            View card = buildCard(p, cardW, cardH, lastInRow ? 0 : gap);
            row.addView(card);
        }
        if (profiles.isEmpty()) {
            TextView empty = Theme.hint(c, "还没有远程主机。点右上角「新建连接」开始。");
            empty.setPadding(0, Theme.dp(c, 24), 0, 0);
            grid.addView(empty);
        }
    }

    private View buildCard(final ConnectionProfile p, int width, int height, int rightMargin) {
        Context c = getContext();
        LinearLayout card = Theme.vertical(c);
        int bgColor = CardColors.parseColor(p.cardColor, p.id);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(bgColor);
        shape.setCornerRadius(Theme.dp(c, 8));
        card.setBackground(shape);
        int pad = Theme.dp(c, 14);
        card.setPadding(pad, pad, pad, pad);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout first = Theme.horizontal(c);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(cb.isRunning(p) ? 0xFF7DE2A8 : 0xFFFFFFFF);
        int dotSize = Theme.dp(c, 8);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dp.rightMargin = Theme.dp(c, 8);
        dot.setLayoutParams(dp);
        first.addView(dot);
        TextView name = Theme.label(c, p.displayTitle(), Color.WHITE, R.dimen.text_title);
        name.getPaint().setFakeBoldText(true);
        name.setSingleLine(true);
        first.addView(name);
        card.addView(first);

        TextView addr = Theme.label(c, p.targetEndpoint(), 0xDDFFFFFF, R.dimen.text_body);
        addr.setPadding(0, Theme.dp(c, 6), 0, 0);
        addr.setSingleLine(true);
        card.addView(addr);

        UiState currentUi = cb.uiState();
        TextView proxy = Theme.label(c,
                p.proxySummary(currentUi == null ? null : currentUi.globalProxy),
                0xAAFFFFFF, R.dimen.text_small);
        proxy.setSingleLine(true);
        proxy.setPadding(0, Theme.dp(c, 2), 0, 0);
        card.addView(proxy);

        TextView state = Theme.label(c, cb.isRunning(p) ? "会话进行中" : "未连接",
                0xCCFFFFFF, R.dimen.text_small);
        state.setPadding(0, Theme.dp(c, 6), 0, 0);
        card.addView(state);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.rightMargin = rightMargin;
        card.setLayoutParams(lp);
        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onOpen(p);
            }
        });
        card.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                cb.onMenu(p, v);
                return true;
            }
        });
        return card;
    }
}
