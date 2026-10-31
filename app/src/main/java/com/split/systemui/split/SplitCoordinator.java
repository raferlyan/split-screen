package com.split.systemui.split;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_MAIN;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_UNKNOWN;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.util.Log;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.split.systemui.di.ShellComponent;

import java.util.List;

/**
 * 分屏交互的状态机，运行在 SystemUI 的 UI 主线程。
 *
 * 线程约定：
 *  - 所有 SplitScreenController 调用都 post 到 wmshell.main（{@link #mShellExecutor}），
 *    UI 线程只做界面，不等待 WMShell。
 *  - 分屏状态（两个 task、左右位置、区域）由 SplitScreenListener 推送过来，回调在 UI 线程。
 *  - 截取 task 快照放在单独的后台线程。
 */
public class SplitCoordinator implements SplitScreen.SplitScreenListener {
    private static final String TAG = "SplitSysUI";

    private final Context mContext;
    private final SplitScreenController mController;
    private final ShellExecutor mShellExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread mBgThread = new HandlerThread("split-bg");
    private final Handler mBgHandler;

    private final AppPickerPanel mPicker;
    private final SplitExitOverlay mExitOverlay;
    private final HintToast mHint;

    // 由 SplitScreenListener 维护
    private boolean mSplitVisible;
    private int mMainTaskId = -1;
    private int mSideTaskId = -1;
    private int mSidePosition = SPLIT_POSITION_BOTTOM_OR_RIGHT;
    private final Rect mMainBounds = new Rect();
    private final Rect mSideBounds = new Rect();

    public SplitCoordinator(Context context, ShellComponent shell) {
        mContext = context;
        mController = shell.splitScreenController().orElse(null);
        mShellExecutor = shell.shellMainExecutor();
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());
        mPicker = new AppPickerPanel(context, mBgHandler, this::enterSplit);
        mExitOverlay = new SplitExitOverlay(context, this::keepTask);
        mHint = new HintToast(context);

        if (mController != null) {
            // ShellInterface#onInit 只是把初始化 post 到 wmshell.main，并不等它完成；
            // 注册也 post 到 wmshell.main，保证排在初始化之后（否则 StageCoordinator 可能还是 null）
            mShellExecutor.execute(() -> mController.asSplitScreen()
                    .registerSplitScreenListener(this, mMainHandler::post));
        } else {
            Log.w(TAG, "SplitScreenController 不可用：检查 config_supportsMultiWindow / low_ram");
        }
    }

    public void destroy() {
        if (mController != null) {
            mController.asSplitScreen().unregisterSplitScreenListener(this);
        }
        mPicker.dismiss(false);
        mExitOverlay.dismissNow();
        mBgThread.quitSafely();
    }

    /** 导航栏分屏按钮。 */
    public void onSplitButtonClicked() {
        if (mController == null) {
            toast("当前系统不支持分屏");
            return;
        }
        if (mPicker.isShowing()) {
            mPicker.dismiss(true);
            return;
        }
        if (mExitOverlay.isShowing()) {
            mExitOverlay.cancel();
            return;
        }
        RunningTaskInfo top = getTopTask();
        // 不能只信 onSplitVisibilityChanged：分屏在后台时用 am start 等方式拉起全屏应用，
        // WMShell 不一定回调 visible=false。再核对一次前台 task 是否属于分屏
        final boolean topInSplit = top != null
                && (top.taskId == mMainTaskId || top.taskId == mSideTaskId);
        if (mSplitVisible && mMainTaskId != -1 && mSideTaskId != -1 && topInSplit) {
            showExitOverlay();
            return;
        }

        if (top == null || top.getActivityType() == ACTIVITY_TYPE_HOME) {
            toast("请先打开一个应用，再点击分屏");
            return;
        }
        if (!top.supportsMultiWindow) {
            toast("当前应用不支持分屏");
            return;
        }
        String topPackage = top.baseActivity != null ? top.baseActivity.getPackageName() : null;
        mPicker.show(topPackage);
    }

    /** 选中了右侧应用：当前前台应用进入左侧（main stage），新应用启动到右侧（side stage）。 */
    private void enterSplit(ComponentName component) {
        Trace.beginSection("SplitSysUI#enterSplit");
        Intent intent = Intent.makeMainActivity(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final int userId = Process.myUserHandle().getIdentifier();
        // StageCoordinator#startIntent 在分屏未激活时，会在同一个 WindowContainerTransaction 里
        // 把当前顶部 task 挪进 main stage、把新 intent 启动到 side stage，
        // 然后作为一个 TRANSIT_SPLIT_SCREEN_PAIR_OPEN 过渡播放。
        mShellExecutor.execute(() -> mController.startIntent(pi, userId, null /* fillIn */,
                SPLIT_POSITION_BOTTOM_OR_RIGHT, null /* options */));
        Trace.endSection();
    }

    /** 再次点击分屏按钮：把两个分屏应用缩成快照卡片。 */
    private void showExitOverlay() {
        final int leftTask = mSidePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideTaskId : mMainTaskId;
        final int rightTask = leftTask == mMainTaskId ? mSideTaskId : mMainTaskId;
        final Rect leftBounds = new Rect(leftTask == mMainTaskId ? mMainBounds : mSideBounds);
        final Rect rightBounds = new Rect(rightTask == mMainTaskId ? mMainBounds : mSideBounds);
        mExitOverlay.show(mBgHandler, leftTask, leftBounds, rightTask, rightBounds);
    }

    /** 卡片选择结果：保留 taskId，另一个退到后台。 */
    private void keepTask(int taskId) {
        mShellExecutor.execute(() -> mController.exitSplitScreen(taskId, EXIT_REASON_UNKNOWN));
    }

    private RunningTaskInfo getTopTask() {
        List<RunningTaskInfo> tasks = ActivityTaskManager.getInstance().getTasks(1);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private void toast(String text) {
        mHint.show(text);
    }

    // ---- SplitScreen.SplitScreenListener（UI 线程）----

    @Override
    public void onStagePositionChanged(int stage, int position) {
        if (stage == STAGE_TYPE_SIDE) {
            mSidePosition = position;
        }
    }

    @Override
    public void onTaskStageChanged(int taskId, int stage, boolean visible) {
        if (stage == STAGE_TYPE_MAIN) {
            mMainTaskId = taskId;
            if (mSideTaskId == taskId) mSideTaskId = -1;
        } else if (stage == STAGE_TYPE_SIDE) {
            mSideTaskId = taskId;
            if (mMainTaskId == taskId) mMainTaskId = -1;
        } else {
            if (mMainTaskId == taskId) mMainTaskId = -1;
            if (mSideTaskId == taskId) mSideTaskId = -1;
        }
    }

    @Override
    public void onSplitBoundsChanged(Rect rootBounds, Rect mainBounds, Rect sideBounds) {
        mMainBounds.set(mainBounds);
        mSideBounds.set(sideBounds);
    }

    @Override
    public void onSplitVisibilityChanged(boolean visible) {
        mSplitVisible = visible;
        if (!visible) {
            mExitOverlay.onSplitExited();
        }
    }
}
