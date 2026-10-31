package com.split.systemui.split;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.WindowManager;

import com.android.wm.shell.common.split.DividerRoundedCorner;
import com.split.systemui.R;

/**
 * 分隔条两侧的"反向圆角"，让左右两个应用看起来是圆角卡片。
 *
 * WMShell 自带的 DividerRoundedCorner 用屏幕圆角半径（Display#getRoundedCorner）来画，
 * 模拟器和多数 RK 板子屏幕圆角为 0，于是什么也不画，两侧应用是直角，分隔条就是一根硬黑条。
 * 这里继承它（DividerView 里按 DividerRoundedCorner 类型 findViewById，必须是子类），
 * 改用固定半径 split_corner_radius。
 *
 * 分隔条窗口宽 docked_stack_divider_thickness(48dp)，条本身 split_divider_bar_width 居中，
 * 两侧各有 19dp 空间，所以半径不能超过 19dp。
 */
public class SplitDividerCorner extends DividerRoundedCorner {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final int mBarWidth;
    private final int mRadius;
    private final int mNavBarHeight;
    private final int mDisplayHeight;

    public SplitDividerCorner(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(getResources().getColor(R.color.split_divider_background, null));
        mBarWidth = getResources().getDimensionPixelSize(R.dimen.split_divider_bar_width);
        mRadius = getResources().getDimensionPixelSize(R.dimen.split_corner_radius);
        mNavBarHeight = getResources().getDimensionPixelSize(R.dimen.nav_bar_height);
        mDisplayHeight = context.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics().getBounds().height();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mPath.reset();
        final float r = mRadius;
        if (w < h) {
            // 左右分屏：竖条。分隔条窗口高度等于屏幕时，下半部分在导航栏后面，
            // 下方圆角要画在导航栏上沿，否则会被导航栏挡住
            final float barL = (w - mBarWidth) / 2f;
            final float barR = barL + mBarWidth;
            final float bottom = h >= mDisplayHeight ? h - mNavBarHeight : h;
            addCorner(barL, 0, -1, 1, r);
            addCorner(barR, 0, 1, 1, r);
            addCorner(barL, bottom, -1, -1, r);
            addCorner(barR, bottom, 1, -1, r);
            if (bottom < h) mPath.addRect(0, bottom, w, h, Path.Direction.CW);
        } else {
            // 上下分屏：横条
            final float barT = (h - mBarWidth) / 2f;
            final float barB = barT + mBarWidth;
            addCorner(0, barT, 1, -1, r);
            addCorner(0, barB, 1, 1, r);
            addCorner(w, barT, -1, -1, r);
            addCorner(w, barB, -1, 1, r);
        }
    }

    /** (x, y) 是直角顶点，(sx, sy) 指向应用内部；填充正方形减去内切圆的那一角。 */
    private void addCorner(float x, float y, int sx, int sy, float r) {
        Path square = new Path();
        square.addRect(Math.min(x, x + sx * r), Math.min(y, y + sy * r),
                Math.max(x, x + sx * r), Math.max(y, y + sy * r), Path.Direction.CW);
        Path circle = new Path();
        circle.addCircle(x + sx * r, y + sy * r, r, Path.Direction.CW);
        square.op(circle, Path.Op.DIFFERENCE);
        mPath.addPath(square);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(mPath, mPaint);
    }
}
