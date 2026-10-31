package com.split.systemui.split;

import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.window.TaskSnapshot;

import com.split.systemui.R;

import java.util.function.IntConsumer;

/**
 * 退出分屏时的卡片选择界面。
 *
 * 流程：
 *  1. 后台线程向 WM 要两个 task 的快照（takeTaskSnapshot，由 system_server 从 SurfaceFlinger 截取），
 *     UI 线程不做截图。
 *  2. 覆盖窗口里两张卡片先摆在两个应用的真实位置上，再缩小并移到屏幕中间，看起来是窗口缩成了卡片。
 *     动画只改 scale / translation / alpha（RenderNode 属性），不触发布局和重绘；
 *     动画期间开硬件层：带圆角裁剪的卡片只渲染一次，之后每帧只是合成缩放。
 *     不画随缩放变化的阴影。
 *  3. 点击卡片 = 保留它；上滑卡片 = 划掉它，保留另一张。选择后立刻让 WMShell 退出分屏，
 *     同时卡片放回原位并淡出，下面 WMShell 的退出过渡和卡片动画同时进行。
 */
class SplitExitOverlay {
    private static final String TAG = "SplitSysUI";
    private static final long SHRINK_MS = 260;
    private static final long RESTORE_MS = 220;
    private static final float CARD_SCALE = 0.62f;
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IntConsumer mOnKeep;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout mRoot;
    private View mScrim;
    private Card mLeft;
    private Card mRight;
    private boolean mChoosing;     // 已经做出选择，正在播放收尾动画
    private int mPendingToken;     // 防止快照还没回来时就被取消

    SplitExitOverlay(Context context, IntConsumer onKeep) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mOnKeep = onKeep;
    }

    boolean isShowing() {
        return mRoot != null;
    }

    void show(Handler bgHandler, int leftTask, Rect leftBounds, int rightTask, Rect rightBounds) {
        if (mRoot != null) return;
        final int token = ++mPendingToken;
        bgHandler.post(() -> {
            Trace.beginSection("SplitSysUI#snapshots");
            final Bitmap left = snapshot(leftTask);
            final Bitmap right = snapshot(rightTask);
            Trace.endSection();
            mMainHandler.post(() -> {
                if (token != mPendingToken || mRoot != null) return;
                attach(new Card(leftTask, leftBounds, left), new Card(rightTask, rightBounds, right));
            });
        });
    }

    /** 再点一次分屏按钮 / 点空白处：卡片放回原位，保持分屏。 */
    void cancel() {
        mPendingToken++;
        if (mRoot == null || mChoosing) return;
        mChoosing = true;
        mLeft.restore(1f);
        mRight.restore(1f);
        mScrim.animate().alpha(0f).setDuration(RESTORE_MS)
                .withEndAction(this::dismissNow).start();
    }

    /** 分屏被其它方式退出（应用自己退出、回到桌面等）时，覆盖层不再有意义。 */
    void onSplitExited() {
        if (mRoot != null && !mChoosing) dismissNow();
    }

    void dismissNow() {
        mPendingToken++;
        if (mRoot != null) {
            mWindowManager.removeViewImmediate(mRoot);
            mRoot = null;
        }
        mChoosing = false;
    }

    private void attach(Card left, Card right) {
        mLeft = left;
        mRight = right;
        mChoosing = false;

        mRoot = new FrameLayout(mContext);
        mScrim = new View(mContext);
        mScrim.setBackgroundColor(mContext.getColor(R.color.exit_scrim));
        mScrim.setOnClickListener(v -> cancel());
        mRoot.addView(mScrim, new FrameLayout.LayoutParams(-1, -1));
        left.addTo(mRoot);
        right.addTo(mRoot);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_NAVIGATION_BAR_PANEL, FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("SplitExitOverlay");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        mWindowManager.addView(mRoot, lp);

        // 第一帧卡片和真实窗口完全重合，然后缩到屏幕中间
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setDuration(SHRINK_MS).start();
        mRoot.post(() -> {
            final int w = mRoot.getWidth();
            final int h = mRoot.getHeight();
            final float gap = mContext.getResources().getDimension(R.dimen.card_gap);
            final float lw = left.bounds.width() * CARD_SCALE;
            final float rw = right.bounds.width() * CARD_SCALE;
            left.shrinkTo(w / 2f - gap / 2f - lw / 2f, h / 2f);
            right.shrinkTo(w / 2f + gap / 2f + rw / 2f, h / 2f);
        });
    }

    private void choose(Card keep, Card drop, boolean dropFlewAway) {
        if (mChoosing) return;
        mChoosing = true;
        // 先通知 WMShell，退出分屏过渡和下面的卡片动画并行
        mOnKeep.accept(keep.taskId);
        if (!dropFlewAway) {
            drop.view.animate().alpha(0f).setDuration(RESTORE_MS).start();
        }
        keep.restore(0f);
        mScrim.animate().alpha(0f).setDuration(RESTORE_MS)
                .withEndAction(this::dismissNow).start();
    }

    private Bitmap snapshot(int taskId) {
        try {
            TaskSnapshot s = ActivityTaskManager.getService().takeTaskSnapshot(taskId,
                    false /* updateCache */);
            if (s == null || s.getHardwareBuffer() == null) return null;
            HardwareBuffer buffer = s.getHardwareBuffer();
            return Bitmap.wrapHardwareBuffer(buffer, s.getColorSpace());
        } catch (RemoteException e) {
            Log.w(TAG, "takeTaskSnapshot failed for task " + taskId, e);
            return null;
        }
    }

    /** 一张卡片 = 一个 task 的快照。 */
    private final class Card {
        final int taskId;
        final Rect bounds;
        final ImageView view;
        private final float mTouchSlop;
        private VelocityTracker mVelocity;
        private float mDownY;
        private float mBaseTranslationY;
        private boolean mDragging;

        Card(int taskId, Rect bounds, Bitmap snapshot) {
            this.taskId = taskId;
            this.bounds = bounds;
            mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
            view = new ImageView(mContext);
            view.setScaleType(ImageView.ScaleType.FIT_XY);
            if (snapshot != null) {
                view.setImageBitmap(snapshot);
            } else {
                view.setBackgroundColor(Color.DKGRAY);
            }
            final float radius = mContext.getResources().getDimension(R.dimen.card_radius);
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    // 圆角按缩放后的视觉大小换算，缩小后看起来是 card_radius
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius / CARD_SCALE);
                }
            });
            view.setClipToOutline(true);
            view.setOnTouchListener(this::onTouch);
        }

        void addTo(FrameLayout parent) {
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(bounds.width(), bounds.height());
            lp.leftMargin = bounds.left;
            lp.topMargin = bounds.top;
            parent.addView(view, lp);
        }

        void shrinkTo(float centerX, float centerY) {
            view.animate()
                    .scaleX(CARD_SCALE).scaleY(CARD_SCALE)
                    .translationX(centerX - bounds.exactCenterX())
                    .translationY(centerY - bounds.exactCenterY())
                    .setInterpolator(EMPHASIZED).setDuration(SHRINK_MS)
                    .withLayer()
                    .withEndAction(() -> mBaseTranslationY = view.getTranslationY())
                    .start();
        }

        /** 放回真实窗口的位置；endAlpha=0 时同时淡出，露出下面 WMShell 的过渡。 */
        void restore(float endAlpha) {
            view.animate().cancel();
            view.animate()
                    .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(endAlpha)
                    .setInterpolator(EMPHASIZED).setDuration(RESTORE_MS)
                    .withLayer()
                    .start();
        }

        private boolean onTouch(View v, MotionEvent ev) {
            if (mChoosing) return true;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownY = ev.getRawY();
                    mDragging = false;
                    mVelocity = VelocityTracker.obtain();
                    mVelocity.addMovement(ev);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    mVelocity.addMovement(ev);
                    float dy = ev.getRawY() - mDownY;
                    if (!mDragging && dy < -mTouchSlop) mDragging = true;
                    if (mDragging) {
                        view.setTranslationY(mBaseTranslationY + Math.min(0f, dy));
                        view.setAlpha(1f - Math.min(1f, -dy / view.getHeight()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    mVelocity.addMovement(ev);
                    mVelocity.computeCurrentVelocity(1000);
                    float vy = mVelocity.getYVelocity();
                    float dy = ev.getRawY() - mDownY;
                    mVelocity.recycle();
                    Card other = this == mLeft ? mRight : mLeft;
                    if (!mDragging) {
                        choose(this, other, false);      // 点击：保留这张
                    } else if (dy < -view.getHeight() * CARD_SCALE / 3f || vy < -1500f) {
                        flyAway();                        // 上滑：划掉这张，保留另一张
                        choose(other, this, true);
                    } else {
                        view.animate().translationY(mBaseTranslationY).alpha(1f)
                                .setInterpolator(new DecelerateInterpolator())
                                .setDuration(180).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    mVelocity.recycle();
                    view.animate().translationY(mBaseTranslationY).alpha(1f)
                            .setDuration(180).start();
                    return true;
            }
            return false;
        }

        private void flyAway() {
            view.animate().cancel();
            view.animate().translationY(-mRoot.getHeight()).alpha(0f)
                    .setInterpolator(new DecelerateInterpolator())
                    .setDuration(RESTORE_MS).withLayer().start();
        }
    }
}
