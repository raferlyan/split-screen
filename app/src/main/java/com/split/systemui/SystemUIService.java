package com.split.systemui;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.split.systemui.navbar.NavigationBar;
import com.split.systemui.split.SplitCoordinator;

/**
 * system_server 在开机时通过 config_systemUIServiceComponent 启动它（SystemServer#startSystemUi）。
 * WMShell 已在 Application#onCreate 里初始化好，这里只负责把导航栏和分屏交互挂起来。
 */
public class SystemUIService extends Service {

    private NavigationBar mNavigationBar;
    private SplitCoordinator mSplitCoordinator;

    @Override
    public void onCreate() {
        super.onCreate();
        SysUIApplication app = (SysUIApplication) getApplication();
        mSplitCoordinator = new SplitCoordinator(this, app.getShellComponent());
        mNavigationBar = new NavigationBar(this, mSplitCoordinator);
        mNavigationBar.attach();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mNavigationBar.detach();
        mSplitCoordinator.destroy();
        super.onDestroy();
    }
}
