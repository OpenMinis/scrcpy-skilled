#!/bin/bash
# 0.12.0 /ui tests (accessibility widget tree of the second screen). Run from the repo root; phone unlocked, SERIAL=pixel.wsen.me:5555.
R=scripts/remote.sh; PASS=0; FAIL=0
ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
A() { sh $R run agent.sh "$@" 2>&1; }
jget() { python3 -c "import sys,json;print(json.loads(sys.stdin.read().strip().splitlines()[0]).get('$1'))"; }
tap_ui() { A ui-tap "$1" >/dev/null; }
sh $R skill >/dev/null
SVC=app.openminis.vdisplay/app.openminis.vdisplay.UiService
SETTING() { sh $R sh 'adb -s $SERIAL shell settings get secure enabled_accessibility_services' | tr -d '\r'; }
sh $R run start.sh >/dev/null; sh $R run run-app.sh com.google.android.calculator >/dev/null; sleep 1

echo "[1] service state in /status"
ST=$(A status)
[ "$(echo "$ST" | jget serviceEnabled)" = True ] && [ "$(echo "$ST" | jget serviceConnected)" = True ] && ok "serviceEnabled+Connected true" || bad "state: $ST"
[ -n "$(echo "$ST" | jget displayId)" ] && [ "$(echo "$ST" | jget displayId)" != "-1" ] && ok "displayId=$(echo "$ST" | jget displayId)" || bad "no displayId"

echo "[2] find 7 on the calculator, tap its centre, display shows 7"
tap_ui AC >/dev/null; A ui-tap "全部清除" >/dev/null; sleep 0.4
O=$(A ui find 7); echo "$O" | sed -n 2,3p
C=$(echo "$O" | rg -o 'digit_7.*c=\((\d+,\d+)\)' -r '$1' | head -1)
[ -n "$C" ] && ok "found digit_7 centre ($C)" || bad "digit_7 not found"
A tap "${C%,*}" "${C#*,}" >/dev/null; sleep 0.5
A ui find formula json | rg -q '"text":"7"|"desc":"7"' && ok "formula shows 7 after tap" || { A ui | rg formula; bad "formula did not show 7"; }

echo "[3] 37*42 read through /ui only"
A ui-tap "全部清除" >/dev/null; for k in 3 7 乘 4 2 等于; do A ui-tap "$k" >/dev/null; done; sleep 0.6
A ui find result | rg -q 1554 && ok "result 1554 via /ui" || { A ui find result; bad "1554 not seen"; }

echo "[4] missing text -> count=0, not an error"
O=$(A ui find zzz_no_such_text); echo "$O" | rg -q 'count=0' && ok "count=0" || bad "$O"

echo "[5] takeover: /ui still works while a human has control"
A takeover 1 >/dev/null
A tap 100 100 | rg -q '"human has control"' && ok "tap blocked (423)" || bad "tap not blocked"
A ui find 7 | rg -q 'digit_7' && ok "/ui works during takeover" || bad "/ui blocked during takeover"
A takeover 0 >/dev/null

echo "[6] Settings in front -> Settings nodes, not Calculator"
sh $R run run-app.sh com.android.settings >/dev/null; sleep 2
O=$(A ui); echo "$O" | rg -q 'pkg=com.android.settings' && ok "settings window" || bad "no settings pkg"
echo "$O" | rg -q 'digit_7|calculator' && bad "calculator leaked into settings result" || ok "no calculator nodes"

echo "[7] main screen not mixed in"
echo "$O" | rg -q 'pkg=(com.android.systemui|app.openminis.vdisplay|com.openminis.app)' && bad "main-screen window in second screen result" || ok "only second-screen windows"
A ui display 0 | rg -q 'pkg=com.android.systemui|pkg=com.openminis' && ok "display 0 has its own windows" || bad "display 0 empty"

echo "[8] service disabled -> 503 + hint; re-enabled -> works; other services untouched"
BEFORE=$(SETTING | tr ':' '\n' | rg -v 'app.openminis.vdisplay' | sort)
sh $R sh 'adb -s $SERIAL shell "settings delete secure enabled_accessibility_services"' >/dev/null  # test phone had no other service; see below for coexistence
sleep 2
O=$(A ui); echo "$O" | rg -q '"hint"' && echo "$O" | rg -q 'enabled_accessibility_services' && ok "503 body with hint" || bad "$O"
A ui >/dev/null; [ $? -ne 0 ] && ok "agent.sh ui exits non-zero" || bad "exit 0 on 503"
echo "$(A status)" | jget serviceEnabled | rg -q False && ok "status serviceEnabled=false" || bad "status still enabled"
sh $R run start.sh >/dev/null   # reuse path re-enables
sh $R run run-app.sh com.google.android.calculator >/dev/null; sleep 1; A ui find 7 | rg -q digit_7 && ok "re-enabled by start.sh" || bad "not re-enabled"

echo "[9] coexistence: an unrelated service entry is preserved by append/remove"
sh $R sh 'adb -s $SERIAL shell "settings put secure enabled_accessibility_services dummy.other/dummy.Svc:app.openminis.vdisplay/app.openminis.vdisplay.UiService"' >/dev/null
sh $R run stop.sh quiet >/dev/null; sh $R run start.sh >/dev/null
S=$(SETTING); echo "$S" | rg -q 'dummy.other/dummy.Svc' && [ "$(echo "$S" | tr ':' '\n' | rg -c UiService)" = 1 ] && ok "other entry kept, ours once" || bad "setting: $S"
sh $R sh 'adb -s $SERIAL shell "settings delete secure enabled_accessibility_services"' >/dev/null; sh $R run start.sh --fresh >/dev/null
echo; echo "ui.sh: $PASS passed, $FAIL failed"; [ $FAIL = 0 ]
