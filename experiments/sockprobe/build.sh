set -e; cd "$(dirname "$0")"
BT=/opt/android-sdk/build-tools/35.0.0; AJ=/opt/android-sdk/platforms/android-34/android.jar
rm -rf out; mkdir -p out/c
$BT/aapt2 link -o out/base.apk -I $AJ --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 33
javac -cp $AJ -d out/c src/*.java 2>&1 | rg -v Picked || true
$BT/d8 --lib $AJ --output out $(find out/c -name '*.class') 2>&1 | rg -v Picked || true
cp out/base.apk out/u.apk; (cd out && zip -q u.apk classes.dex)
KS=/var/minis/workspace/pip-exp/dbg.jks
$BT/apksigner sign --ks $KS --ks-pass pass:android --out out/probe.apk out/u.apk
ls -la out/probe.apk
