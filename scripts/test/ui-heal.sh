#!/bin/bash
# agent.sh ui must heal a lost accessibility service by itself, safely. Runs on the Mac -> phone.
export PATH=/opt/homebrew/bin:$PATH
export SERIAL=${SERIAL:-pixel.wsen.me:5555} ADB_CONNECT=${ADB_CONNECT:-pixel.wsen.me:5555}
SK=/tmp/android-vdisplay/scripts; A="adb -s $SERIAL"
SVC=app.openminis.vdisplay/app.openminis.vdisplay.UiService
STATE=${SKILLED_STATE:-$HOME/.android-vdisplay}
ok(){ echo "  PASS  $*"; }; bad(){ echo "  FAIL  $*"; FAILED=1; }
lst(){ $A shell 'settings get secure enabled_accessibility_services' | tr -d '\r'; }
reset_flags(){ rm -f "$STATE/ui_blocked" "$STATE/ui_heal_ts"; }

sh $SK/stop.sh quiet >/dev/null 2>&1; sleep 2
sh $SK/start.sh >/dev/null 2>&1; sh $SK/run-app.sh com.google.android.calculator >/dev/null 2>&1

echo "[1] entry deleted mid-session -> ui heals it and returns the tree (rc 0)"
reset_flags
$A shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0" >/dev/null; sleep 1
OUT=$(sh $SK/agent.sh ui find 7 2>/tmp/ui.err); RC=$?
[ $RC = 0 ] && echo "$OUT" | grep -q 'digit_7' && ok "ui returned the 7 key after healing" || bad "rc=$RC out=$(echo "$OUT" | head -c 120)"
grep -q 're-enabled' /tmp/ui.err && ok "one explanatory note on stderr" || bad "no note on stderr: $(cat /tmp/ui.err)"
lst | grep -q "$SVC" && ok "our entry is back in the list" || bad "list=$(lst)"

echo "[2] other services are kept (we append, never overwrite)"
reset_flags
$A shell "settings put secure enabled_accessibility_services 'dummy.other/dummy.Svc'; settings put secure accessibility_enabled 1" >/dev/null; sleep 1
sh $SK/agent.sh ui find 7 >/dev/null 2>&1
L=$(lst); echo "$L" | grep -q 'dummy.other/dummy.Svc' && echo "$L" | grep -q "$SVC" && ok "dummy kept + ours appended: $L" || bad "list=$L"
N=$(echo "$L" | tr ':' '\n' | grep -c "$SVC"); [ "$N" = 1 ] && ok "ours appears exactly once" || bad "ours appears $N times"
$A shell "settings put secure enabled_accessibility_services '$SVC'" >/dev/null

echo "[3] rate limit: a second loss within 60 s is NOT re-enabled (someone means it)"
reset_flags
$A shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0" >/dev/null; sleep 1
sh $SK/agent.sh ui find 7 >/dev/null 2>&1                       # heal #1 (uses the 60 s budget)
$A shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0" >/dev/null; sleep 1
OUT=$(sh $SK/agent.sh ui find 7 2>/dev/null); RC=$?
[ $RC != 0 ] && echo "$OUT" | grep -q 'serviceEnabled' && ok "second loss inside 60 s reported, not healed (rc=$RC)" || bad "rc=$RC out=$(echo "$OUT" | head -c 100)"
[ -z "$(lst | grep -v null)" ] && ok "setting stayed off" || bad "setting was changed: $(lst)"

echo "[4] explicit start.sh re-authorises"
sh $SK/start.sh >/dev/null 2>&1
lst | grep -q "$SVC" && ok "start.sh re-enabled it" || bad "list=$(lst)"
[ ! -f "$STATE/ui_heal_ts" ] && ok "heal budget reset" || bad "ui_heal_ts still present"

echo "[5] system refuses the write -> remembered, no retry loop, clear message"
reset_flags
$A shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0" >/dev/null; sleep 1
# simulate a refusing ROM: make ensure_ui fail by pointing it at a service name that cannot bind? Not possible without root,
# so check the bookkeeping directly: a present ui_blocked file must stop any healing attempt.
: > "$STATE/ui_blocked"
OUT=$(sh $SK/agent.sh ui find 7 2>/tmp/ui.err); RC=$?
[ $RC != 0 ] && ok "blocked flag respected (rc=$RC), no attempt made" || bad "healed despite ui_blocked"
[ -z "$(lst | grep -v null)" ] && ok "setting untouched" || bad "setting changed: $(lst)"

echo "[6] outside a session (after stop.sh) ui never touches the setting"
sh $SK/stop.sh quiet >/dev/null 2>&1
$A shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0" >/dev/null
sh $SK/agent.sh ui find 7 >/dev/null 2>&1
[ -z "$(lst | grep -v null)" ] && ok "no healing without a session" || bad "setting changed outside a session: $(lst)"
[ ! -f "$STATE/ui_blocked" ] && ok "stop.sh cleared the flags" || bad "ui_blocked survived stop.sh"

sh $SK/stop.sh quiet >/dev/null 2>&1
exit ${FAILED:-0}
