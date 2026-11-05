#!/bin/sh
# Launch an app on the secondary display and confirm it is really in the foreground there: run-app.sh <package>
# Outputs JSON: {"ok":true,"display":N,"top":"<package/Activity>"}; on failure {"ok":false,...} and non-zero exit
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
[ -z "$1" ] && { echo '{"ok":false,"error":"缺少包名"}'; exit 2; }
ID=$(cat "$STATE/display" 2>/dev/null)
S=$(agent "/status" 2>/dev/null); echo "$S" | grep -q '"ready":true' || { echo '{"ok":false,"error":"副屏未就绪","hint":"先运行 start.sh"}'; exit 1; }
adbx shell "pm path $1 >/dev/null 2>&1" || { echo "{\"ok\":false,\"error\":\"未安装 $1\"}"; exit 1; }
agent "/start?pkg=$1" >/dev/null; echo >/dev/null
TOP=""
for i in 1 2 3 4 5 6 7 8; do
  sleep 1
  # Filter on the device and return only one line. Do not exit early with awk locally: dumpsys output is large,
  # and adb hangs until timeout once the pipe reader closes (this once stalled run-app for minutes).
  TOP=$(adbx shell "dumpsys activity activities | grep -A12 '^Display #$ID ' | grep -m1 topResumedActivity" | sed 's/.*u0 //;s/ .*//' | tr -d '\r')
  case "$TOP" in $1*) echo "{\"ok\":true,\"display\":$ID,\"top\":\"$TOP\"}"; exit 0;; esac
done
echo "{\"ok\":false,\"error\":\"命令已发送，但 $1 没有出现在副屏 $ID 前台\",\"top\":\"$TOP\",\"hint\":\"可能 App 被系统拦截/需要登录/启动慢；用 agent.sh shot 看画面\",\"upgrade\":\"仍失败请先更新到最新版 skill/APK 再试\"}"; exit 1
