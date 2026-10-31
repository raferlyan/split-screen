#!/bin/bash
# 编译 APK，作为 priv-app 推进模拟器的 /system_ext，然后重启。
# 前提：模拟器用 -writable-system 启动，且已经执行过一次 adb remount + reboot。
#   用法：scripts/deploy_emulator.sh [emulator-5554]
set -e
export ANDROID_SERIAL=${1:-emulator-5554}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
cd "$ROOT"

# 本机 ~/.gradle 里的代理端口是 7890，实际代理在 7897，这里在命令行覆盖
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)} ./gradlew :app:assembleDebug -q \
    -Dhttp.proxyPort=7897 -Dhttps.proxyPort=7897

$ADB root >/dev/null; sleep 2; $ADB wait-for-device
$ADB remount >/dev/null
$ADB shell mkdir -p /system_ext/priv-app/SplitSystemUI
$ADB push app/build/outputs/apk/debug/app-debug.apk /system_ext/priv-app/SplitSystemUI/SplitSystemUI.apk
$ADB push device/privapp-permissions-com.split.systemui.xml /system_ext/etc/permissions/
$ADB shell 'chmod 755 /system_ext/priv-app/SplitSystemUI;
            chmod 644 /system_ext/priv-app/SplitSystemUI/SplitSystemUI.apk /system_ext/etc/permissions/privapp-permissions-com.split.systemui.xml'
$ADB reboot
$ADB wait-for-device
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "booted, SystemUI pid: $($ADB shell pidof com.split.systemui)"
