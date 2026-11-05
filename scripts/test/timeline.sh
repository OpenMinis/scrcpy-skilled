#!/bin/bash
# Fine-grained timeline of one cold start, taken ON THE MAC with device-side timestamps (so ssh/adb hops don't blur the picture).
# Every line: <ms since t0> <event>.  Run it N times: scripts/test/timeline.sh [N]
export PATH=/opt/homebrew/bin:$PATH
S="adb -s ${SERIAL:-10.1.10.11:5555}"; DEV=/data/local/tmp/sc; PKG=app.openminis.vdisplay
SK=/tmp/android-vdisplay
N=${1:-3}
dnow() { $S shell 'date +%s%3N' | tr -d '\r'; }           # device wall clock, ms
for run in $(seq $N); do
  sh $SK/scripts/stop.sh quiet >/dev/null 2>&1; sleep 2
  # clean logs so we only see this run
  $S shell "rm -f $DEV/server.log $DEV/bridge.log"
  T0=$(dnow); echo "== run $run  t0=$T0"
  # launch exactly like start.sh does, but stamp every phase on the device
  $S shell "BITRATE=8000000 nohup setsid $DEV/run.sh server 00001234 1080x2352/420 </dev/null >/dev/null 2>&1 &"
  $S shell "PORT=27184 nohup setsid $DEV/run.sh bridge 00001234 27184 </dev/null >/dev/null 2>&1 &"
  $S shell "am start-foreground-service -n $PKG/.PipService --ei port 27184 --ei x 20 --ei y 300 --ei w 168" >/dev/null
  # poll fast (100 ms) on the device and record when each milestone first appears
  $S shell "
    t0=$T0; seen=''
    mark() { case \" \$seen \" in *\" \$1 \"*) ;; *) seen=\"\$seen \$1\"; echo \"\$((\$(date +%s%3N)-t0)) \$1\";; esac; }
    i=0; while [ \$i -lt 300 ]; do
      grep -q 'Device:' $DEV/server.log 2>/dev/null && mark server_jvm_up
      grep -q scrcpy_00001234 /proc/net/unix 2>/dev/null && mark server_socket_listening
      grep -q listening $DEV/bridge.log 2>/dev/null && mark bridge_listening
      grep -q 'stream 0 connected' $DEV/bridge.log 2>/dev/null && mark app_connected_video
      grep -q 'stream 1 connected' $DEV/bridge.log 2>/dev/null && mark app_connected_control
      grep -q 'New display' $DEV/server.log 2>/dev/null && mark virtual_display_created
      grep -q 'Starting app' $DEV/server.log 2>/dev/null && mark server_launched_home
      [ \$i -gt 8 ] && case \" \$seen \" in *virtual_display_created*) break;; esac
      i=\$((i+1)); sleep 0.1
    done" 2>&1 | sort -n
  # first frame, from the app's own status
  curl -s -m 3 "http://127.0.0.1:$(cat ~/.android-vdisplay/fport 2>/dev/null || echo 28090)/status" >/dev/null 2>&1
done
