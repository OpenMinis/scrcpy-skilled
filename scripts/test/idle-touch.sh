#!/bin/bash
# A human touch on the floating window must reset the idle clock (it never goes through an HTTP request).
# Runs on the Mac. IDLE_MIN=1. We touch at +35s and expect the display to still be alive at +75s (it would
# have closed at +60s without the touch), then to close ~60s after the touch.
SK=/tmp/android-vdisplay/scripts
A="adb -s ${SERIAL:-192.0.2.10:5555}"
ok(){ echo "  PASS  $*"; }; bad(){ echo "  FAIL  $*"; FAILED=1; }

IDLE_MIN=1 sh $SK/start.sh >/dev/null
sleep 2
FR=$($A shell dumpsys input | grep -m1 'name=.*app.openminis.vdisplay' | sed 's/.*frame=\[\([0-9-]*\),\([0-9-]*\)\]\[\([0-9-]*\),\([0-9-]*\)\].*/\1 \2 \3 \4/')
set -- $FR; CX=$(( ($1+$3)/2 )); CY=$(( ($2+$4)/2 ))
echo "window frame: $FR  touch point: $CX,$CY"
T0=$(date +%s)

sleep 35
$A shell input tap $CX $CY            # one light tap on the video area (does not take over, but is a touch)
echo "touched at +$(( $(date +%s) - T0 ))s"

sleep 40                               # now at ~+75s: without the touch it would already be closed
N=$($A shell 'ps -A | grep -c vdisplay' | tr -d '\r ')
[ "$N" -ge 1 ] && ok "still alive at +$(( $(date +%s) - T0 ))s because the touch reset the clock" || bad "closed despite the touch"

sleep 45                               # ~+120s: 60s after the touch it must be gone
N=$($A shell 'ps -A | grep -c vdisplay' | tr -d '\r ')
[ "$N" = 0 ] && ok "closed after the new full idle period (+$(( $(date +%s) - T0 ))s)" || bad "still alive at +$(( $(date +%s) - T0 ))s"
exit ${FAILED:-0}
