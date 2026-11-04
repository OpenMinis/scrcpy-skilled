#!/bin/bash
# 构建 app/（APK）与 shell/（Bridge jar），不依赖 gradle。产物在 build/
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd); B=$ROOT/build/apk; rm -rf $B; mkdir -p $B/app $B/shell
SDK=${ANDROID_HOME:-/opt/android-sdk}; BT=$SDK/build-tools/${ANDROID_BUILD_TOOLS:-35.0.0}; AJ=$SDK/platforms/android-34/android.jar
KS=${DEBUG_KEYSTORE:-$ROOT/build/dbg.jks}
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 9999 -dname CN=dbg >/dev/null 2>&1
# app
$BT/aapt2 link -o $B/base.apk -I $AJ --manifest $ROOT/app/AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 33
javac -cp $AJ -d $B/app/c $(find $ROOT/app/src -name '*.java') 2> $B/javac.log || { cat $B/javac.log; exit 1; }
$BT/d8 --lib $AJ --output $B/app $(find $B/app/c -name '*.class') 2>&1 | rg -v Picked || true
cp $B/base.apk $B/u.apk; (cd $B/app && zip -q ../u.apk classes.dex)
$BT/apksigner sign --ks $KS --ks-pass pass:android --out $ROOT/build/skilled.apk $B/u.apk
# shell
javac -cp $AJ -d $B/shell/c $(find $ROOT/shell/src -name '*.java') 2> $B/javac2.log || { cat $B/javac2.log; exit 1; }
$BT/d8 --lib $AJ --output $B/shell $(find $B/shell/c -name '*.class') 2>&1 | rg -v Picked || true
(cd $B/shell && rm -f $ROOT/build/skilled-shell.jar && zip -q $ROOT/build/skilled-shell.jar classes.dex)
ls -la $ROOT/build/skilled.apk $ROOT/build/skilled-shell.jar
