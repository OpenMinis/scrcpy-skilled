#!/bin/sh
# 调用 agent 接口：agent.sh tap 100 200 | swipe x1 y1 x2 y2 [ms] | key 4 | text 你好 | start pkg | status | takeover 1|0
#                  window x=.. y=.. w=.. | hide | show | shot [输出.png]
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
c=$1; shift
case "$c" in
  tap) agent "/tap?x=$1&y=$2" ;;
  swipe) agent "/swipe?x1=$1&y1=$2&x2=$3&y2=$4&ms=${5:-300}" ;;
  key) agent "/key?code=$1" ;;
  text) agent "/text?s=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1" 2>/dev/null || printf %s "$1")" ;;
  start) agent "/start?pkg=$1" ;;
  status) agent "/status" ;;
  takeover) agent "/takeover?on=$1" ;;
  window) q=""; for kv in "$@"; do q="$q&$kv"; done; agent "/window?${q#&}" ;;
  hide) agent "/window?hide=1" ;;
  show) agent "/window?hide=0" ;;
  shot) OUT=${1:-$STATE/shot.png}; agent_to /screenshot "$OUT" && echo "$OUT" ;;
  *) echo "未知命令: $c"; exit 2 ;;
esac
echo
