package com.split.systemui.di;

import android.content.Context;
import android.os.HandlerThread;

import androidx.annotation.Nullable;

import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.dagger.WMShellModule;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.displayareahelper.DisplayAreaHelper;
import com.android.wm.shell.keyguard.KeyguardTransitions;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.sysui.ShellInterface;
import com.android.wm.shell.taskview.TaskViewFactory;
import com.android.wm.shell.transition.ShellTransitions;

import java.util.Optional;

import dagger.BindsInstance;
import dagger.Component;

/**
 * 对应 AOSP SystemUI 的 WMComponent。区别只在于 AOSP 里它是 GlobalRootComponent 的子组件，
 * 系统服务由父组件提供；这里没有父组件，由 {@link SystemServicesModule} 直接提供。
 *
 * WMShell 的模块（WMShellModule / WMShellBaseModule / WMShellConcurrencyModule）原样复用，
 * 所以线程模型、初始化顺序和 AOSP 完全一致。
 */
@WMSingleton
@Component(modules = {WMShellModule.class, SystemServicesModule.class})
public interface ShellComponent {

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder context(Context context);

        /** 传 null 时，若 config_enableShellMainThread=true，WMShell 自己创建 wmshell.main 线程。 */
        @BindsInstance
        Builder shellMainThread(@Nullable @ShellMainThread HandlerThread thread);

        ShellComponent build();
    }

    /**
     * 和 AOSP SystemUIInitializer#setupWmComponent 的顺序一致：先把 WMComponent 暴露的各个组件
     * 取一遍，再调用 ShellInterface#onInit。
     * Dagger 是按需创建对象的，没被取过的控制器（例如 PipController）根本不会创建，也就不会
     * 注册 ShellInit 回调；而分屏的 DefaultMixedHandler 会访问 PiP 的状态，缺了就会空指针。
     */
    default void init() {
        getPip();
        getSplitScreen();
        getOneHanded();
        getBubbles();
        getTaskViewFactory();
        getTransitions();
        getKeyguardTransitions();
        getStartingSurface();
        getDisplayAreaHelper();
        getRecentTasks();
        getBackAnimation();
        getDesktopMode();
        shell().onInit();
    }

    @WMSingleton
    ShellInterface shell();

    // ---- 以下和 AOSP SystemUI 的 WMComponent 一一对应 ----

    @WMSingleton
    Optional<Pip> getPip();

    @WMSingleton
    Optional<SplitScreen> getSplitScreen();

    @WMSingleton
    Optional<OneHanded> getOneHanded();

    @WMSingleton
    Optional<Bubbles> getBubbles();

    @WMSingleton
    Optional<TaskViewFactory> getTaskViewFactory();

    @WMSingleton
    ShellTransitions getTransitions();

    @WMSingleton
    KeyguardTransitions getKeyguardTransitions();

    @WMSingleton
    Optional<StartingSurface> getStartingSurface();

    @WMSingleton
    Optional<DisplayAreaHelper> getDisplayAreaHelper();

    @WMSingleton
    Optional<RecentTasks> getRecentTasks();

    @WMSingleton
    Optional<BackAnimation> getBackAnimation();

    @WMSingleton
    Optional<DesktopMode> getDesktopMode();

    // ---- 本 SystemUI 自己用的 ----

    @WMSingleton
    Optional<SplitScreenController> splitScreenController();

    @ShellMainThread
    ShellExecutor shellMainExecutor();
}
