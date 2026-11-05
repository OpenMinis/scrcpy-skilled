#!/bin/sh
# Stop the secondary display (server/bridge/floating window). The virtual display is destroyed with the server; its apps fall back to the main screen.
. "$(dirname "$0")/common.sh"; ensure_adb >/dev/null 2>&1 || exit 0
adbx shell "am stopservice $PKG/.PipService >/dev/null 2>&1; am force-stop $PKG"
PIDS=$(adbx shell "ps -A -o PID,ARGS" | grep -E 'scrcpy\.Server|skilled\.Bridge' | grep -v grep | awk '{print $1}')
for p in $PIDS; do adbx shell "kill $p" >/dev/null 2>&1; done
rm -f "$STATE/display" "$STATE/port"
[ "$1" = quiet ] || echo stopped
