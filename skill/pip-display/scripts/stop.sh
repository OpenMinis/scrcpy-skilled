#!/bin/sh
# 停止副屏（server/桥/悬浮窗）。虚拟屏随 server 销毁，其中的 App 会回落到主屏。
. "$(dirname "$0")/common.sh"; ensure_adb >/dev/null 2>&1 || exit 0
adbx shell "am stopservice $PKG/.PipService >/dev/null 2>&1; am force-stop $PKG"
PIDS=$(adbx shell "ps -A -o PID,ARGS" | grep -E 'scrcpy\.Server|skilled\.Bridge' | grep -v grep | awk '{print $1}')
for p in $PIDS; do adbx shell "kill $p" >/dev/null 2>&1; done
rm -f "$STATE/display"
[ "$1" = quiet ] || echo stopped
