#!/bin/sh
# 安装/更新：推送 jar、安装 APK、授予悬浮窗权限。幂等。
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
adbx shell "mkdir -p $DEV" || exit 1
T=60 adbx push "$SK_DIR/assets/scrcpy-server.jar" $DEV/server.jar >/dev/null || exit 1
T=60 adbx push "$SK_DIR/assets/skilled-shell.jar" $DEV/shell.jar >/dev/null || exit 1
T=120 adbx install -r "$SK_DIR/assets/skilled.apk" 2>&1 | tail -1
adbx shell "appops set $PKG SYSTEM_ALERT_WINDOW allow; pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null; cmd deviceidle whitelist +$PKG >/dev/null 2>&1"
echo "installed: $(adbx shell dumpsys package $PKG | grep -m1 versionCode | tr -d '\r')"
