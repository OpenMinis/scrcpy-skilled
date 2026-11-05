#!/bin/bash
# Build app/ (APK) and shell/ (Bridge jar) without gradle. Outputs go to build/
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd); B=$ROOT/build/apk; rm -rf $B; mkdir -p $B/app $B/shell
# SDK: $ANDROID_HOME, else the macOS default, else the Linux path used in Minis
if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME=$HOME/Library/Android/sdk; else ANDROID_HOME=/opt/android-sdk; fi
fi
SDK=$ANDROID_HOME; BT=$SDK/build-tools/${ANDROID_BUILD_TOOLS:-35.0.0}; AJ=$SDK/platforms/android-34/android.jar
KS=${DEBUG_KEYSTORE:-$ROOT/build/dbg.jks}
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 9999 -dname CN=dbg >/dev/null 2>&1
# app: compile resources (localized strings) and generate R.java first, then compile Java
VER=$(cat $ROOT/VERSION); VCODE=$(echo $VER | awk -F. '{print $1*10000+$2*100+$3}')
mkdir -p $B/res $B/gen
$BT/aapt2 compile --dir $ROOT/app/res -o $B/res/res.zip
$BT/aapt2 link -o $B/base.apk -I $AJ --manifest $ROOT/app/AndroidManifest.xml --java $B/gen $B/res/res.zip \
  --min-sdk-version 29 --target-sdk-version 33 --version-code $VCODE --version-name $VER
javac -cp $AJ -d $B/app/c $(find $ROOT/app/src $B/gen -name '*.java') 2> $B/javac.log || { cat $B/javac.log; exit 1; }
$BT/d8 --lib $AJ --output $B/app $(find $B/app/c -name '*.class') 2>&1 | grep -v Picked || true
cp $B/base.apk $B/u.apk; (cd $B/app && zip -q ../u.apk classes.dex)
$BT/apksigner sign --ks $KS --ks-pass pass:android --out $ROOT/build/skilled.apk $B/u.apk
# shell
javac -cp $AJ -d $B/shell/c $(find $ROOT/shell/src -name '*.java') 2> $B/javac2.log || { cat $B/javac2.log; exit 1; }
$BT/d8 --lib $AJ --output $B/shell $(find $B/shell/c -name '*.class') 2>&1 | grep -v Picked || true
(cd $B/shell && rm -f $ROOT/build/skilled-shell.jar && zip -q $ROOT/build/skilled-shell.jar classes.dex)
ls -la $ROOT/build/skilled.apk $ROOT/build/skilled-shell.jar
