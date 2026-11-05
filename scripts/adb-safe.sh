#!/bin/sh
# adb wrapper: with timeout; if adb hangs, restart it on a new port
P=$(cat /tmp/adbport 2>/dev/null || echo 5300)
export ANDROID_ADB_SERVER_PORT=$P
if ! timeout 8 adb devices 2>/dev/null | rg -q 'pixel.*device'; then
  P=$((P+1)); echo $P > /tmp/adbport; export ANDROID_ADB_SERVER_PORT=$P
  (adb start-server >/dev/null 2>&1 &); sleep 4
  timeout 15 adb connect pixel.wsen.me:5555 >/dev/null 2>&1
fi
exec timeout ${T:-30} adb "$@"
