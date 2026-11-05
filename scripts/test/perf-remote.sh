#!/bin/bash
# Runs ON THE MAC (mini2), next to the phone's adb. One ssh session, N loops per stage, so the iSH→ssh hop
# is NOT part of any sample. Prints "stage<TAB>ms" lines; perf.py aggregates them.
# Env: SERIAL, N, SK (skill dir), DISP (virtual display id), FP (adb-forwarded agent port)
export PATH=/opt/homebrew/bin:$PATH
N=${N:-15}; SK=${SK:-/tmp/android-vdisplay}; S="adb -s $SERIAL"
now() { python3 -c 'import time;print(time.perf_counter())'; }
# time a command: prints "stage<TAB>ms"
T() { name=$1; shift; a=$(now); "$@" >/dev/null 2>&1; b=$(now); python3 -c "print('$name\t%.1f' % (($b-$a)*1000))"; }

FP=$(cat ~/.android-vdisplay/fport 2>/dev/null)
[ -z "$FP" ] && { FP=28093; adb -s $SERIAL forward tcp:$FP tcp:28080 >/dev/null; }
H="http://127.0.0.1:$FP"
DISP=${DISP:-$(cat ~/.android-vdisplay/display)}

# --- floor costs: what any adb / http call pays before the app does anything ---
for i in $(seq $N); do T "floor: adb shell true" $S shell true; done
for i in $(seq $N); do T "floor: http /ping (adb forward)" curl -s -m 5 $H/ping; done

# --- agent actions over HTTP (what the skill's agent.sh does) ---
for i in $(seq $N); do T "http /status" curl -s -m 5 $H/status; done
for i in $(seq $N); do curl -s -m 5 "$H/resume" >/dev/null; T "http /tap" curl -s -m 5 "$H/tap?x=270&y=150&ref=540"; done
for i in $(seq $N); do curl -s -m 5 "$H/resume" >/dev/null; T "http /swipe (120ms gesture)" curl -s -m 8 "$H/swipe?x1=270&y1=600&x2=270&y2=300&ms=120&ref=540"; done
for i in $(seq $N); do curl -s -m 5 "$H/resume" >/dev/null; T "http /key" curl -s -m 5 "$H/key?code=4"; done
for i in $(seq $N); do curl -s -m 5 "$H/resume" >/dev/null; T "http /text (ascii 5 chars)" curl -s -m 8 "$H/text?s=12345"; done

# --- screenshots ---
for i in $(seq $N); do T "shot: agent PNG full-size" curl -s -m 10 "$H/screenshot" -o /tmp/pf_full.png; done
for i in $(seq $N); do T "shot: agent PNG scale=0.5" curl -s -m 10 "$H/screenshot?scale=0.5" -o /tmp/pf_half.png; done
for i in $(seq $N); do T "shot: agent PNG w=540" curl -s -m 10 "$H/screenshot?w=540" -o /tmp/pf_540.png; done
# server-side breakdown (from X-Timing-Ms) -- averaged over the run
for i in $(seq 5); do curl -s -m 10 -D - -o /dev/null "$H/screenshot" | tr -d '\r' | grep -i '^X-Timing-Ms' | sed 's/.*: //'; done | sed 's/^/TIMING\t/'
for i in $(seq $N); do T "shot: screencap main screen (adb exec-out)" bash -c "$S exec-out screencap -p > /tmp/pf_main.png"; done
echo "SIZE	agent_full_png	$(wc -c < /tmp/pf_full.png)"
echo "SIZE	agent_half_png	$(wc -c < /tmp/pf_half.png)"
echo "SIZE	agent_w540_png	$(wc -c < /tmp/pf_540.png)"
echo "SIZE	main_screencap_png	$(wc -c < /tmp/pf_main.png)"

# --- accessibility / UI tree ---
# Pin the foreground first: the dump cost scales with the number of nodes on screen, so record it per app.
UIBENCH() {   # UIBENCH <label> <package>
  sh $SK/scripts/run-app.sh $2 >/dev/null 2>&1; sleep 2
  for i in $(seq $N); do T "ui dump [$1]: on device (--display)" $S shell "uiautomator dump --display $DISP /sdcard/pf_ui.xml"; done
  for i in $(seq $N); do T "ui dump [$1]: dump + pull to host" bash -c "$S shell 'uiautomator dump --display $DISP /sdcard/pf_ui.xml' >/dev/null && $S pull /sdcard/pf_ui.xml /tmp/pf_ui.xml"; done
  $S shell "uiautomator dump --display $DISP /sdcard/pf_ui.xml" >/dev/null 2>&1; $S pull /sdcard/pf_ui.xml /tmp/pf_ui.xml >/dev/null 2>&1
  echo "SIZE	ui_xml_$1	$(wc -c < /tmp/pf_ui.xml)"
  echo "NODES	$1	$(grep -o '<node ' /tmp/pf_ui.xml | wc -l | tr -d ' ')"
}
UIBENCH calculator com.google.android.calculator
UIBENCH settings com.android.settings
# the app's own accessibility service (Minis) dumps the whole device, for comparison: skipped if unavailable

# --- app-level actions ---
for i in $(seq 5); do sh $SK/scripts/run-app.sh com.android.chrome >/dev/null 2>&1; T "run-app.sh (switch, incl. verification)" sh $SK/scripts/run-app.sh com.google.android.calculator; done
