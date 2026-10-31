# SplitSystemUI

自研 SystemUI（不基于 AOSP SystemUI）+ AOSP 编出来的 WMShell 库，在 Android 14 模拟器上实现二分屏。

交互：
- 导航栏分屏按钮：在桌面时提示"请先打开一个应用"；在应用里时右侧滑出应用列表，选中后当前应用在左、选中应用在右。
- 分屏中再点分屏按钮：两个窗口缩成快照卡片。点卡片保留它；上滑卡片划掉它、保留另一个。

## 目录

| 路径 | 说明 |
|---|---|
| `wmshell/` | WindowManager-Shell 包成 Gradle library：`aosp/wmshell-classes.jar` + `aosp/wmshell-res` |
| `iconloader/` | WMShell 合并进来的 iconloader 的资源 |
| `app/.../di/ShellComponent.java` | 自己的 Dagger 组件，原样复用 `WMShellModule`，对应 AOSP 的 `WMComponent` |
| `app/.../SysUIApplication.java` | 进程启动时初始化 WMShell |
| `app/.../navbar/NavigationBar.java` | 导航栏（返回 / 主页 / 分屏） |
| `app/.../split/SplitCoordinator.java` | 分屏状态机 |
| `app/.../split/AppPickerPanel.java` | 右侧应用列表 |
| `app/.../split/SplitExitOverlay.java` | 退出时的快照卡片 |
| `app/.../split/SplitDividerCorner.java` + `res/layout/split_divider.xml` | 分隔条两侧的圆角（覆盖 WMShell 的布局） |
| `device/privapp-permissions-com.split.systemui.xml` | priv-app 权限白名单 |
| `scripts/export_on_server.sh` | 编译服务器上收集产物 |
| `scripts/fetch_from_server.sh` | 拉回 Mac 并生成平台签名 |
| `scripts/deploy_emulator.sh` | 编译并推进模拟器 `/system_ext/priv-app`，然后重启 |

`aosp/` 下是从 AOSP 产物里拿来的文件，不在版本管理里，用两个脚本重新生成。

## 镜像侧改动（AOSP android-14.0.0_r75，sdk_phone64_arm64-ap2a-userdebug）

1. `frameworks/base/core/res/res/values/config.xml`：`config_systemUIServiceComponent` 改成 `com.split.systemui/com.split.systemui.SystemUIService`。
2. `build/make/target/product/handheld_system_ext.mk`：去掉 `SystemUI`，避免两个 WMShell 同时注册 TaskOrganizer / transition player。

## 跑起来

```bash
scripts/export_on_server.sh   # 在服务器上：ssh root@<server> 'bash -s' < scripts/export_on_server.sh
scripts/fetch_from_server.sh root@<server>
emulator -avd SplitSysUI_Tablet -writable-system -no-snapshot   # 第一次需要 adb root && adb remount && adb reboot
scripts/deploy_emulator.sh
```

## 踩过的坑（自研 SystemUI 接 WMShell 时都要处理）

| 现象 | 原因 | 处理 |
|---|---|---|
| 开机服务 `not found` | system_server 在用户解锁前启动 SystemUI | `<service android:directBootAware="true">` |
| `BackgroundServiceStartNotAllowedException` | 装在 /data 的普通应用受后台启动限制 | 作为 priv-app 放进 `/system_ext/priv-app`，`android:persistent="true"` |
| 开机 system_server 抛 privapp 异常 | `ro.control_privapp_permissions=enforce` | 补 privapp 白名单（照抄 `com.android.systemui.xml`） |
| `UnsupportedOperationException: ProtoLog calls MUST be processed` | WMShell 的 Kotlin 代码没经过 ProtoLogTool | Application 构造里 `ProtoLog.REQUIRE_PROTOLOGTOOL = false`（AOSP SystemUIApplication 同样处理） |
| 分屏时 `PipTaskOrganizer` 空指针 | Dagger 按需创建，PiP 控制器没被取过就不会初始化 | 和 `SystemUIInitializer` 一样，先把 WMComponent 的各个 getter 取一遍再 `onInit()` |
| 偶发 `StageCoordinator` 空指针 | `ShellInterface#onInit` 只是 post，不等初始化完成 | 注册分屏监听也 post 到 wmshell.main |
| Toast 不显示 | Android 11 起文字 Toast 由 SystemUI 通过 IStatusBar 绘制 | 自己画提示浮层 |
| 分屏被全屏应用盖住后点分屏，弹出空白卡片 | `onSplitVisibilityChanged(false)` 不一定回调 | 点按钮时再核对前台 task 是否属于分屏 |
| 分隔条是一根直角黑条 | `DividerRoundedCorner` 用屏幕圆角半径，屏幕圆角为 0 就不画 | app 里覆盖 `split_divider` 布局，换成固定半径的子类；颜色/粗细/把手用同名资源覆盖 |
| WMShell 和 UI 抢主线程 | `config_enableShellMainThread` 默认 false | app 资源里覆盖成 true，WMShell 跑在 `wmshell.main` |

## 性能参考（模拟器，M1 Pro，仅供对比）

3 轮"进入分屏 → 卡片 → 退出"：SystemUI 进程 Janky 11%，90 分位 22ms；WMShell 退出分屏过渡 135ms 内完成。
模拟器 GPU 走 gfxstream，结论以 RK 实机 Perfetto trace 为准。
