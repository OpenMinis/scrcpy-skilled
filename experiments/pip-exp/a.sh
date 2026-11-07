#!/bin/sh
# adb 包装：带超时，卡死则换新端口重启
P=$(cat /tmp/adbport 2>/dev/null || echo 5300)
export ANDROID_ADB_SERVER_PORT=$P
if ! timeout 8 adb devices 2>/dev/null | rg -q 'pixel.*device'; then
  P=$((P+1)); echo $P > /tmp/adbport; export ANDROID_ADB_SERVER_PORT=$P
  (adb start-server >/dev/null 2>&1 &); sleep 4
  timeout 15 adb connect 192.0.2.10:5555 >/dev/null 2>&1
fi
exec timeout ${T:-30} adb "$@"
