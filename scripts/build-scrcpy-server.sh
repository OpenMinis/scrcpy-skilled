#!/bin/bash
# 不改子模块，编译 third_party/scrcpy/server -> build/scrcpy-server (dex jar)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/opt/android-sdk}
PLATFORM=${ANDROID_PLATFORM:-34}
BT=${ANDROID_BUILD_TOOLS:-35.0.0}
AJ=$SDK/platforms/android-$PLATFORM/android.jar
AIDLF=$SDK/platforms/android-$PLATFORM/framework.aidl
BTD=$SDK/build-tools/$BT
S=$ROOT/third_party/scrcpy/server
VER=$(rg -o 'versionName "([^"]+)"' -r '$1' $S/build.gradle)
B=$ROOT/build/scrcpy-server; rm -rf $B; mkdir -p $B/classes $B/gen/com/genymobile/scrcpy
cat > $B/gen/com/genymobile/scrcpy/BuildConfig.java <<EOT
package com.genymobile.scrcpy;
public final class BuildConfig { public static final boolean DEBUG = false; public static final String VERSION_NAME = "$VER"; }
EOT
# aidl 是 x86 二进制，本机跑不了：用 shell/aidl-stubs 里的手写等价 Stub（有 aidl 时可替换）
# 子模块保持只读：拷到构建目录，仅把 android-34 jar 里缺失的 VANILLA_ICE_CREAM 常量替换为字面量 35
mkdir -p $B/src && cp -r $S/src/main/java/. $B/src/
sed -i 's/Build.VERSION_CODES.VANILLA_ICE_CREAM/35/' $B/src/com/genymobile/scrcpy/AndroidVersions.java
cd $B/src
SRCS=$(ls android/content/*.java com/genymobile/scrcpy/*.java com/genymobile/scrcpy/*/*.java)
javac -encoding UTF-8 -bootclasspath $AJ -cp $BTD/core-lambda-stubs.jar:$B/gen -d $B/classes -source 1.8 -target 1.8 $SRCS $(find $B/gen $ROOT/shell/aidl-stubs -name '*.java') 2>&1 | rg -v 'warning|Picked|^\s|^Note' || true
cd $B/classes
$BTD/d8 --classpath $AJ --output $B/classes.zip $(find . -name '*.class') 2>&1 | rg -v Picked || true
cp $B/classes.zip $ROOT/build/scrcpy-server.jar
echo "version=$VER"; ls -la $ROOT/build/scrcpy-server.jar
