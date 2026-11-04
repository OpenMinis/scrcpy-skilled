set -e
cd /var/minis/workspace/pip-exp
BT=/opt/android-sdk/build-tools/35.0.0
AJ=/opt/android-sdk/platforms/android-34/android.jar
rm -rf out gen && mkdir -p out/app out/srv gen
# srv (shell jar)
javac -cp $AJ -d out/srv srv/Srv.java 2>&1 | grep -v Picked || true
$BT/d8 --lib $AJ --output out/srv $(find out/srv -name '*.class') 2>&1 | grep -v Picked || true
(cd out/srv && rm -f ../srv.jar && zip -q ../srv.jar classes.dex)
# app
$BT/aapt2 link -o out/base.apk -I $AJ --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 33
javac -cp $AJ -d out/app src/pip/OverlayService.java 2>&1 | grep -v Picked || true
$BT/d8 --lib $AJ --output out/app $(find out/app -name '*.class') 2>&1 | grep -v Picked || true
cp out/base.apk out/unsigned.apk
(cd out/app && zip -q ../unsigned.apk classes.dex)
cp out/unsigned.apk out/aligned.apk
[ -f dbg.jks ] || keytool -genkeypair -keystore dbg.jks -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 9999 -dname CN=dbg 2>&1 | grep -v Picked
$BT/apksigner sign --ks dbg.jks --ks-pass pass:android --out out/pip.apk out/aligned.apk
ls -la out/pip.apk out/srv.jar
