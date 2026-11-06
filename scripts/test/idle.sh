#!/bin/bash
# Idle auto-close test (runs on the Mac next to the phone). IDLE_MIN=1 so it finishes in about 2 minutes.
# Rule: ANY request resets the idle clock, including /status. So after the first check we stay silent and observe
# the phone from the outside (logcat / ps / port) without calling the agent.
SK=/tmp/android-vdisplay/scripts
A="adb -s ${SERIAL:-10.1.10.11:5555}"
ok(){ echo "  PASS  $*"; }; bad(){ echo "  FAIL  $*"; FAILED=1; }
field(){ python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

echo "[1] start with IDLE_MIN=1"
IDLE_MIN=1 sh $SK/start.sh | head -c 100; echo
$A logcat -c
S=$(sh $SK/agent.sh status); LIM=$(echo "$S" | field idleLimitMs)
[ "$LIM" = 60000 ] && ok "idleLimitMs=60000" || bad "idleLimitMs=$LIM"
T0=$(date +%s)

echo "[2] polling resets the clock: 3 status calls 4s apart must all report idleMs < 2s"
MAXI=0; for i in 1 2 3; do sleep 4; I=$(sh $SK/agent.sh status | field idleMs); [ "${I:-99999}" -gt "$MAXI" ] && MAXI=$I; done
[ "$MAXI" -lt 2000 ] && ok "idleMs stayed small while polling (max $MAXI)" || bad "idleMs reached $MAXI while polling"
T1=$(date +%s)

echo "[3] stay silent. No agent calls from here on."
sleep 40
echo "  still alive at +$(( $(date +%s) - T1 ))s of silence?"
ALIVE=$($A shell 'ps -A | grep -c vdisplay' | tr -d '\r ')
[ "$ALIVE" -ge 1 ] && ok "still running at 40s of silence (limit is 60s)" || bad "exited too early"

echo "[4] wait past the limit (60s of silence + countdown + 0.9s shutdown)"
sleep 35
END=$(( $(date +%s) - T1 ))
PROCS=$($A shell 'ps -A | grep -c "vdisplay"' | tr -d '\r ')
PORT=$($A shell 'nc -z -w1 127.0.0.1 28080 && echo open || echo closed' 2>/dev/null | tail -1 | tr -d '\r')
LOGS=$($A logcat -d -s SKILLED:V | grep -c 'closed: idle timeout')
[ "$PROCS" = 0 ] && ok "app process gone at +${END}s" || bad "$PROCS process(es) still alive at +${END}s"
[ "$PORT" = closed ] && ok "agent port closed" || bad "agent port $PORT"
[ "$LOGS" = 1 ] && ok "closeAll ran exactly once (idempotent)" || bad "closeAll log lines: $LOGS (expected 1)"
SC=$($A shell 'ps -A -o ARGS | grep -E "scrcpy.Server.*76646973|vdisplay.Bridge" | grep -v grep | wc -l' | tr -d '\r ')
[ "$SC" = 0 ] && echo "  note: server+bridge also gone" || echo "  note: $SC server/bridge process(es) remain (stop.sh cleans them)"
exit ${FAILED:-0}
