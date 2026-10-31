package com.split.systemui.di;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.ServiceManager;
import android.os.UserManager;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.statusbar.IStatusBarService;
import com.android.wm.shell.dagger.WMSingleton;

import dagger.Module;
import dagger.Provides;

/**
 * WMShell 需要、但不由 WMShell 自己提供的系统对象。
 * AOSP 里这些来自 SystemUI 的 FrameworkServicesModule / GlobalModule。
 */
@Module
public abstract class SystemServicesModule {

    @Provides
    @WMSingleton
    static WindowManager provideWindowManager(Context context) {
        return context.getSystemService(WindowManager.class);
    }

    @Provides
    @WMSingleton
    static IWindowManager provideIWindowManager() {
        return WindowManagerGlobal.getWindowManagerService();
    }

    @Provides
    @WMSingleton
    static ActivityTaskManager provideActivityTaskManager() {
        return ActivityTaskManager.getInstance();
    }

    @Provides
    @WMSingleton
    static UserManager provideUserManager(Context context) {
        return context.getSystemService(UserManager.class);
    }

    @Provides
    @WMSingleton
    static LauncherApps provideLauncherApps(Context context) {
        return context.getSystemService(LauncherApps.class);
    }

    @Provides
    @WMSingleton
    static PackageManager providePackageManager(Context context) {
        return context.getPackageManager();
    }

    @Provides
    @WMSingleton
    static AccessibilityManager provideAccessibilityManager(Context context) {
        return context.getSystemService(AccessibilityManager.class);
    }

    @Provides
    @WMSingleton
    static IStatusBarService provideIStatusBarService() {
        return IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    @Provides
    @WMSingleton
    static InteractionJankMonitor provideInteractionJankMonitor() {
        return InteractionJankMonitor.getInstance();
    }

    @Provides
    @WMSingleton
    static UiEventLogger provideUiEventLogger() {
        return new UiEventLoggerImpl();
    }
}
