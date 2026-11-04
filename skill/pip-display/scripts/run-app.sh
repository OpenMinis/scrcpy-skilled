#!/bin/sh
# 在副屏启动 App 并确认它真的在副屏前台：run-app.sh <包名>
# 输出 JSON：{"ok":true,"display":N,"top":"<包名/Activity>"}；失败 {"ok":false,...} 并返回非 0
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
[ -z "$1" ] && { echo '{"ok":false,"error":"缺少包名"}'; exit 2; }
ID=$(cat "$STATE/display" 2>/dev/null)
S=$(agent "/status" 2>/dev/null); echo "$S" | grep -q '"ready":true' || { echo '{"ok":false,"error":"副屏未就绪","hint":"先运行 start.sh"}'; exit 1; }
adbx shell "pm path $1 >/dev/null 2>&1" || { echo "{\"ok\":false,\"error\":\"未安装 $1\"}"; exit 1; }
agent "/start?pkg=$1" >/dev/null; echo >/dev/null
TOP=""
for i in 1 2 3 4 5 6 7 8; do
  sleep 1
  TOP=$(adbx shell "dumpsys activity activities" | awk -v id="$ID" '/^Display #/{cur=$2} cur=="#" id && /topResumedActivity/ {print; exit}' | sed 's/.*u0 //;s/ .*//' | tr -d '\r')
  case "$TOP" in $1*) echo "{\"ok\":true,\"display\":$ID,\"top\":\"$TOP\"}"; exit 0;; esac
done
echo "{\"ok\":false,\"error\":\"命令已发送，但 $1 没有出现在副屏 $ID 前台\",\"top\":\"$TOP\",\"hint\":\"可能 App 被系统拦截/需要登录/启动慢；用 agent.sh shot 看画面\",\"upgrade\":\"仍失败请先更新到最新版 skill/APK 再试\"}"; exit 1
