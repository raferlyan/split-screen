#!/bin/bash
# 在编译服务器上执行（AOSP 编完之后）：把 Gradle 工程需要的东西收集到 /root/export
#   用法：ssh root@<server> 'bash -s' < scripts/export_on_server.sh
set -e
AOSP=/aosp
OUT=$AOSP/out
INTER=$OUT/soong/.intermediates
EXPORT=/root/export
rm -rf $EXPORT && mkdir -p $EXPORT
cd $AOSP

# 1. WMShell 的类（含 static_libs 合并后的 combined jar）。
#    androidx（含 android.support.v4 的 AIDL 和 guava ListenableFuture）/ kotlin / kotlinx（含 _COROUTINE）/
#    jetbrains 注解 / dagger / javax.inject 在 Gradle 里走 Maven 依赖，这里剔掉避免重复类；
#    R 类也剔掉，由 Gradle 根据资源重新生成。
SHELL_JAR=$(find $INTER/frameworks/base/libs/WindowManager/Shell/WindowManager-Shell -path "*combined*" -name "WindowManager-Shell.jar" | head -1)
echo "WMShell jar: $SHELL_JAR"
TMP=$(mktemp -d)
(cd $TMP && unzip -q -o "$SHELL_JAR")
(cd $TMP && rm -rf androidx android/support kotlin kotlinx _COROUTINE org/intellij org/jetbrains \
        com/google/common dagger javax/inject \
        META-INF/*.kotlin_module META-INF/*.version META-INF/maven META-INF/versions \
    && find . -path "./com/android/wm/shell/R.class" -o -path "./com/android/wm/shell/R\$*.class" \
         -o -path "./com/android/launcher3/icons/R.class" -o -path "./com/android/launcher3/icons/R\$*.class" | xargs -r rm -f \
    && zip -q -r $EXPORT/wmshell-classes.jar .)
rm -rf $TMP

# 2. WMShell 资源，以及 iconloader_base 的资源（WMShell 合并进来的 IconProvider 会用到）
cp -r frameworks/base/libs/WindowManager/Shell/res $EXPORT/wmshell-res
# drawable/layout 里的 ?androidprv: 保留（文件自带 xmlns，aapt2 能识别）；
# values 会被 AGP 合并成一个文件、丢掉 xmlns:androidprv，只能换成最接近的公开主题属性
find $EXPORT/wmshell-res -path "*/values*" -name "*.xml" | xargs grep -l '?androidprv:attr/' | xargs -r sed -i -E \
  -e 's#\?androidprv:attr/materialColorOnSurfaceVariant#?android:attr/textColorSecondary#g' \
  -e 's#\?androidprv:attr/materialColorOnSurface#?android:attr/textColorPrimary#g' \
  -e 's#\?androidprv:attr/(materialColorSurfaceBright|materialColorSurfaceContainerHigh|colorSurfaceVariant|colorSurface)#?android:attr/colorBackground#g' \
  -e 's#\?androidprv:attr/(colorAccentPrimaryVariant|colorAccentPrimary)#?android:attr/colorAccent#g'
ICON_RES=$(find frameworks/libs/systemui/iconloaderlib -maxdepth 1 -type d -name res | head -1)
[ -n "$ICON_RES" ] && cp -r "$ICON_RES" $EXPORT/iconloader-res

# 3. 带 @hide API 的 framework 类（只用于编译）
FW_JAR=$(find $INTER/frameworks/base/framework-minus-apex -path "*/combined/*" -name "framework*.jar" | head -1)
echo "framework jar: $FW_JAR"
cp "$FW_JAR" $EXPORT/framework.jar

# 4. framework-res（aapt2 链接 @*android: 私有资源用）
cp $(find $OUT/target/product -path "*/system/framework/framework-res.apk" | head -1) $EXPORT/framework-res.apk

# 5. AOSP 测试平台签名
cp build/target/product/security/platform.pk8 build/target/product/security/platform.x509.pem $EXPORT/

# 6. androidx 预编译仓库（和编 WMShell 时版本一致）
tar -C prebuilts/sdk/current/androidx -czf $EXPORT/m2repository.tgz m2repository

# 7. 模拟器镜像
cp $(ls -t $OUT/target/product/*/sdk-repo-linux-system-images*.zip | head -1) $EXPORT/emulator-image.zip

ls -lh $EXPORT
