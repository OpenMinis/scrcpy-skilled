#!/bin/bash
# 0.11.0 regression: rendered/ready semantics, healthy-display reuse, --fresh, broken-state detection, black screenshot is not "ready".
# Run from the repo root (uses scripts/remote.sh). Needs the phone unlocked.
R=scripts/remote.sh; PASS=0; FAIL=0
ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
jget() { python3 -c "import sys,json;print(json.loads(sys.stdin.read().strip().splitlines()[0]).get('$1'))"; }
sh $R skill >/dev/null; sh $R run stop.sh quiet >/dev/null

echo "[1] rendered > 0 and ready after start"
S1=$(sh $R run start.sh); echo "$S1"
ID1=$(echo "$S1" | jget display); T1=$(echo "$S1" | python3 -c "import sys,json;print(json.loads(sys.stdin.read())['ms']['total'])")
ST=$(sh $R run agent.sh status)
[ "$(echo "$ST" | jget ready)" = True ] && ok "ready:true" || bad "ready not true: $ST"
[ "$(echo "$ST" | jget rendered)" -gt 0 ] 2>/dev/null && ok "rendered=$(echo "$ST" | jget rendered) (frames=$(echo "$ST" | jget frames))" || bad "rendered not > 0"

echo "[2] second start is reused and fast; --fresh gives a new id"
S2=$(sh $R run start.sh); echo "$S2"
[ "$(echo "$S2" | jget reused)" = True ] && ok "reused:true" || bad "not reused"
[ "$(echo "$S2" | jget display)" = "$ID1" ] && ok "same display id $ID1" || bad "display id changed"
T2=$(echo "$S2" | python3 -c "import sys,json;print(json.loads(sys.stdin.read())['ms']['total'])"); echo "  first=${T1}ms reused=${T2}ms"
[ "$T2" -lt "$T1" ] && ok "reuse faster" || bad "reuse not faster"
S3=$(sh $R run start.sh --fresh); echo "$S3"
[ "$(echo "$S3" | jget reused)" = False ] && [ "$(echo "$S3" | jget display)" != "$ID1" ] && ok "--fresh rebuilt (id $ID1 -> $(echo "$S3" | jget display))" || bad "--fresh did not rebuild"

echo "[3] broken state: kill our Bridge -> not reused, rebuilt"
sh $R sh "adb -s \$SERIAL shell 'for p in \$(ps -A -o PID,ARGS | grep \"vdisplay.Bridge 76646973\" | grep -v grep | awk \"{print \\\$1}\"); do kill \$p; done'" >/dev/null
sleep 3
S4=$(sh $R run start.sh); echo "$S4"
[ "$(echo "$S4" | jget ok)" = True ] && [ "$(echo "$S4" | jget reused)" = False ] && ok "unhealthy display rebuilt" || bad "unhealthy display not rebuilt"
sh $R sh "ls -d ~/.android-vdisplay/logs/*/ | tail -1" | grep -q logs && ok "logs preserved" || bad "no saved logs"

echo "[4] pure-black screenshot is not 'ready'"
sh $R run agent.sh shot /tmp/ready-shot.jpg jpg 70 >/dev/null
sh $R sh "python3 -c \"
from PIL import Image
im=Image.open('/tmp/ready-shot.jpg').convert('L'); print(im.getextrema())\"" 2>&1 | tail -1
sh $R run run-app.sh com.android.settings >/dev/null; sleep 1
sh $R run agent.sh shot /tmp/ready-shot2.jpg jpg 70 >/dev/null
SZ=$(sh $R sh "wc -c < /tmp/ready-shot2.jpg" | tr -d ' ')
[ "$SZ" -gt 8000 ] && ok "Settings screenshot is a real picture ($SZ bytes, not the 4532-byte black JPEG)" || bad "screenshot looks black ($SZ bytes)"
sh $R run stop.sh >/dev/null
echo "PASS=$PASS FAIL=$FAIL"; [ $FAIL = 0 ]
