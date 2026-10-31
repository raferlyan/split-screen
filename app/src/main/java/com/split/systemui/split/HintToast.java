package com.split.systemui.split;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.split.systemui.R;

/**
 * 分屏相关的轻提示。
 * 不能用 {@link android.widget.Toast}：Android 11 起文字 Toast 由 SystemUI 通过 IStatusBar#showToast
 * 绘制，这个 SystemUI 没有实现那一套，Toast 会直接不显示。
 */
class HintToast {
    private static final long SHOW_MS = 1800;
    private static final long FADE_MS = 150;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private TextView mView;

    HintToast(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    void show(String text) {
        if (mView != null) {
            mView.setText(text);
            mView.removeCallbacks(mHide);
            mView.postDelayed(mHide, SHOW_MS);
            return;
        }
        final float density = mContext.getResources().getDisplayMetrics().density;
        TextView v = new TextView(mContext);
        v.setText(text);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(16);
        int padH = (int) (20 * density);
        int padV = (int) (10 * density);
        v.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6303134);
        bg.setCornerRadius(24 * density);
        v.setBackground(bg);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                TYPE_NAVIGATION_BAR_PANEL, FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = mContext.getResources().getDimensionPixelSize(R.dimen.nav_bar_height)
                + (int) (32 * density);
        lp.setTitle("SplitHint");
        lp.setFitInsetsTypes(0);
        lp.windowAnimations = 0;
        mView = v;
        mWindowManager.addView(v, lp);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(FADE_MS).start();
        v.postDelayed(mHide, SHOW_MS);
    }

    private final Runnable mHide = this::hide;

    private void hide() {
        final TextView v = mView;
        if (v == null) return;
        mView = null;
        v.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> mWindowManager.removeViewImmediate(v)).start();
    }
}
