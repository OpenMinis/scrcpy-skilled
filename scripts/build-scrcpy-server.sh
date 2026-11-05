#!/bin/bash
# Build third_party/scrcpy/server -> build/scrcpy-server (dex jar) without modifying the submodule
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
# aidl is an x86 binary and cannot run here: use the hand-written equivalent stubs in shell/aidl-stubs (replace when aidl is available)
# Keep the submodule read-only: copy to the build dir and only replace the VANILLA_ICE_CREAM constant (missing from the android-34 jar) with the literal 35
mkdir -p $B/src && cp -r $S/src/main/java/. $B/src/
sed -i 's/Build.VERSION_CODES.VANILLA_ICE_CREAM/35/' $B/src/com/genymobile/scrcpy/AndroidVersions.java
cd $B/src
SRCS=$(ls android/content/*.java com/genymobile/scrcpy/*.java com/genymobile/scrcpy/*/*.java)
javac -encoding UTF-8 -bootclasspath $AJ -cp $BTD/core-lambda-stubs.jar:$B/gen -d $B/classes -source 1.8 -target 1.8 $SRCS $(find $B/gen $ROOT/shell/aidl-stubs -name '*.java') 2>&1 | rg -v 'warning|Picked|^\s|^Note' || true
cd $B/classes
$BTD/d8 --classpath $AJ --output $B/classes.zip $(find . -name '*.class') 2>&1 | rg -v Picked || true
cp $B/classes.zip $ROOT/build/scrcpy-server.jar
echo "version=$VER"; ls -la $ROOT/build/scrcpy-server.jar
