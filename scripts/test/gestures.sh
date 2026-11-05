#!/bin/sh
# Gesture regression test for the PiP overlay. Runs from Minis, drives the phone through mini2.
# Each case prints PASS/FAIL. Requires: scripts/remote.sh, /data/local/tmp/sc/pinch.jar on the phone.
cd "$(dirname "$0")/../.."
R=scripts/remote.sh
pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }
FR() { for _ in 1 2 3 4 5; do f=$(sh $R sh "adb -s \$SERIAL shell dumpsys input | grep -m1 -E 'name=.*app.openminis.vdisplay'" | sed -n 's/.*frame=\[\([0-9-]*\),\([0-9-]*\)\]\[\([0-9-]*\),\([0-9-]*\)\].*/\1 \2 \3 \4/p'); [ -n "$f" ] && { echo "$f"; return; }; sleep 0.5; done; echo "0 0 0 0"; }
MAN() { sh $R run agent.sh status | python3 -c 'import sys,json;print(json.load(sys.stdin)["manualTakeover"])'; }
PINCH() { sh $R sh "adb -s \$SERIAL shell CLASSPATH=/data/local/tmp/sc/pinch.jar app_process / Pinch $*" >/dev/null; }
AI()  { sh $R run agent.sh tap 270 150 >/dev/null; }          # mark "AI working"
RESET() { sh $R run agent.sh resume >/dev/null; sh $R run agent.sh window x=20 y=500 w=168 >/dev/null; sleep 0.5; }
center() { set -- $(FR); CX=$(( ($1+$3)/2 )); CY=$(( ($2+$4)/2 )); W=$(( $3-$1 )); X1=$1; }

RESET; center; echo "start: width=$W center=($CX,$CY)"

echo "[1] AI working, light tap → no takeover"
AI; sh $R sh "adb -s \$SERIAL shell input tap $CX $CY"; sleep 0.4
[ "$(MAN)" = False ] && ok "tap ignored" || bad "tap took over"; RESET; center

echo "[2] AI working, one-finger drag → moves, no takeover"
AI; sh $R sh "adb -s \$SERIAL shell input swipe $CX $CY $((CX+100)) $((CY+60)) 700"; sleep 0.4
set -- $(FR); [ "$(MAN)" = False ] && [ "$1" != "$X1" ] && ok "moved to x=$1, no takeover" || bad "drag: manual=$(MAN) x=$1"; RESET; center

echo "[3] AI working, two fingers held still 1.3s then spread → resize, no takeover"
AI; PINCH $CX $CY 60 160 14 1300; sleep 0.4
set -- $(FR); NW=$(( $3-$1 )); [ "$(MAN)" = False ] && [ $NW -gt $W ] && ok "width $W→$NW, no takeover" || bad "pinch-hold: manual=$(MAN) width $W→$NW"; RESET; center

echo "[4] AI working, two-finger pinch in → smaller, no takeover"
AI; PINCH $CX $CY 160 60 14 0; sleep 0.4
set -- $(FR); NW=$(( $3-$1 )); [ "$(MAN)" = False ] && [ $NW -lt $W ] && ok "width $W→$NW, no takeover" || bad "pinch-in: manual=$(MAN) width $W→$NW"; RESET; center

echo "[5] Idle, two-finger spread → resize, no takeover"
sleep 4.5; PINCH $CX $CY 60 140 14 0; sleep 0.4
set -- $(FR); NW=$(( $3-$1 )); [ "$(MAN)" = False ] && [ $NW -gt $W ] && ok "width $W→$NW, no takeover" || bad "idle pinch: manual=$(MAN) width $W→$NW"; RESET; center

echo "[6] AI working, stationary long-press 1.4s → takeover"
AI; sh $R sh "adb -s \$SERIAL shell input swipe $CX $CY $CX $CY 1400"; sleep 0.4
[ "$(MAN)" = True ] && ok "took over" || bad "no takeover"; RESET; center

echo "[7] Idle, stationary long-press 1.4s → takeover"
sleep 4.5; sh $R sh "adb -s \$SERIAL shell input swipe $CX $CY $CX $CY 1400"; sleep 0.4
[ "$(MAN)" = True ] && ok "took over" || bad "no takeover"; RESET; center

echo "[8] pinch follows fingers: the window point under the finger midpoint stays under it"
# pinch around a point 1/4 from the window's left edge (off-center, so a center-anchored resize would fail)
set -- $(FR); PX=$(( $1 + ($3-$1)/4 )); PY=$(( $2 + ($4-$2)/3 ))
RX0=$(python3 -c "print(round(($PX-$1)/($3-$1),3), round(($PY-$2)/($4-$2),3))")
PINCH $PX $PY 50 130 14 0; sleep 0.4
set -- $(FR); RX1=$(python3 -c "print(round(($PX-$1)/($3-$1),3), round(($PY-$2)/($4-$2),3))")
python3 -c "
a=[float(v) for v in '$RX0'.split()]; b=[float(v) for v in '$RX1'.split()]
import sys; sys.exit(0 if abs(a[0]-b[0])<0.04 and abs(a[1]-b[1])<0.04 else 1)" \
  && ok "relative pos before=($RX0) after=($RX1)" || bad "drifted: before=($RX0) after=($RX1)"; RESET

echo "result: $pass passed, $fail failed"
[ $fail -eq 0 ]
