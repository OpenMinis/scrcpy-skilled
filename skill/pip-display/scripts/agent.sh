#!/bin/sh
# Call the agent API: agent.sh tap 100 200 | swipe x1 y1 x2 y2 [ms] | key 4 | text hello | start pkg | status | takeover 1|0
#                  title "what I'm doing" | version | resume | window x=.. y=.. w=.. | hide | show | shot [out.png]
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
REF=${REF_W:-540}
c=$1; shift
case "$c" in
  tap) agent "/tap?x=$1&y=$2&ref=$REF" ;;
  swipe) agent "/swipe?x1=$1&y1=$2&x2=$3&y2=$4&ms=${5:-300}&ref=$REF" ;;
  key) agent "/key?code=$1" ;;
  text) agent "/text?s=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1" 2>/dev/null || printf %s "$1")" ;;
  start) agent "/start?pkg=$1" ;;
  status) agent "/status" ;;
  title) agent "/title?s=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1" 2>/dev/null)" ;;
  version) echo "$(version_info)"; agent "/version" ;;
  resume) agent "/resume" ;;
  takeover) agent "/takeover?on=$1" ;;
  window) q=""; for kv in "$@"; do q="$q&$kv"; done; agent "/window?${q#&}" ;;
  hide) agent "/window?hide=1" ;;
  show) agent "/window?hide=0" ;;
  shot) OUT=${1:-$STATE/shot.png}; agent_to "/screenshot?w=$REF" "$OUT"
        if [ "$(head -c 4 "$OUT" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "89504e47" ]; then echo "$OUT"
        else echo "ERR: 截图失败: $(head -c 200 "$OUT" 2>/dev/null)"; echo "hint: 先 agent.sh status 看 ready/frames；ready=false 就 start.sh；锁屏需先解锁"; upgrade_hint; rm -f "$OUT"; exit 1; fi ;;
  *) echo "未知命令: $c"; exit 2 ;;
esac
echo
