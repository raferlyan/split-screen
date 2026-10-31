package com.split.systemui;

import android.app.Application;
import android.content.res.Configuration;
import android.os.Trace;
import android.util.Log;

import com.android.internal.protolog.common.ProtoLog;

import com.split.systemui.di.DaggerShellComponent;
import com.split.systemui.di.ShellComponent;

/**
 * 进程启动时先把 WMShell 拉起来，和 AOSP SystemUIInitializer#init 里的 setupWmComponent 对应。
 * 必须早于任何窗口/分屏操作，因为 WMShell 初始化时要向 WM 注册 TaskOrganizer 和 transition player。
 */
public class SysUIApplication extends Application {
    private static final String TAG = "SplitSysUI";

    private ShellComponent mShellComponent;

    public SysUIApplication() {
        super();
        // 和 AOSP SystemUIApplication 构造函数一致：WMShell 的 Kotlin 代码没经过 ProtoLogTool 处理，
        // 不关掉这个检查，第一次打 ProtoLog 就会抛 UnsupportedOperationException
        ProtoLog.REQUIRE_PROTOLOGTOOL = false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Trace.beginSection("SplitSysUI#initShell");
        // shellMainThread 传 null：资源里 config_enableShellMainThread=true，
        // WMShellConcurrencyModule 会自己创建 THREAD_PRIORITY_DISPLAY 的 wmshell.main 线程，
        // WMShell 的逻辑和动画都不会占用本进程的 UI 主线程。
        mShellComponent = DaggerShellComponent.builder()
                .context(this)
                .shellMainThread(null)
                .build();
        mShellComponent.init();
        Trace.endSection();
        Log.i(TAG, "WMShell initialized, split supported="
                + mShellComponent.splitScreenController().isPresent());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mShellComponent.shell().onConfigurationChanged(newConfig);
    }

    public ShellComponent getShellComponent() {
        return mShellComponent;
    }
}
