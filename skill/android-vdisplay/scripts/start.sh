#!/bin/sh
# Start the secondary display (idempotent, self-healing): scrcpy server (new virtual display) → TCP bridge → floating window, then wait until truly ready (video frames arrived).
# Usage: start.sh [WxH/dpi, default = physical main screen size and dpi] [window x] [window y] [window width dp]
# On success prints one JSON line: {"ok":true,"display":N,"port":P,"w":..,"h":..}; on failure prints {"ok":false,"stage":"...","hint":"...","log":"..."} and exits non-zero.
. "$(dirname "$0")/common.sh"; ensure_adb || { echo "{\"ok\":false,\"stage\":\"adb\",\"hint\":\"设备未连接或未授权，见 references/setup.md\"}"; exit 3; }
now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }
T0=$(now_ms); TL=$T0; TIMING=""
lap() { _n=$(now_ms); TIMING="$TIMING${TIMING:+,}\"$1\":$((_n-TL))"; TL=$_n; }   # per-stage milliseconds, reported in the result JSON
if [ -z "$1" ]; then   # default matches the physical main screen (size + dpi) so app layouts match
  WMS=$(adbx shell "wm size | tail -1 | sed 's/.*: //'" | tr -d '\r'); WMD=$(adbx shell "wm density | tail -1 | sed 's/.*: //'" | tr -d '\r')
  case "$WMS" in [0-9]*x[0-9]*) SIZE="$WMS/${WMD:-420}";; *) SIZE=540x960/240;; esac
else SIZE=$1; fi
 WX=${2:-20}; WY=${3:-300}; WW=${4:-168}; SCID=00001234
PIX=$(echo "${SIZE%%/*}" | awk -Fx '{print $1*$2}'); BR=$((PIX>1500000?8000000:4000000))
fail() { # fail <stage> <hint>
  L=$(adbx shell "echo '--- server'; tail -4 $DEV/server.log; echo '--- bridge'; tail -4 $DEV/bridge.log" 2>/dev/null | tr '\n\r' '  ' | sed 's/"/\\"/g' | cut -c1-600)
  V=$(version_info 2>/dev/null)
  echo "{\"ok\":false,\"stage\":\"$1\",\"hint\":\"$2\",\"version\":${V:-null},\"upgrade\":\"出错请先更新到最新版 skill/APK 再试\",\"log\":\"$L\"}"; exit 1; }

# 1. Preflight in ONE device call: installed version, jar present, first free bridge port (27183+). Install/upgrade only if needed.
WANT=$(cat "$SK_DIR/assets/VERSION" 2>/dev/null)
PRE=$(adbx shell "echo \$(dumpsys package $PKG 2>/dev/null | grep -m1 versionName | sed 's/.*=//') \$([ -f $DEV/server.jar ] && echo jar || echo nojar) \$(p=27183; while [ \$p -lt 27283 ]; do if ! (cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -qi \":\$(printf %04X \$p) \"); then echo \$p; break; fi; p=\$((p+1)); done) \$(wc -c < $DEV/run.sh 2>/dev/null || echo 0)" | tr -d '\r')
HAVE=$(echo "$PRE" | awk '{print $1}'); JAR=$(echo "$PRE" | awk '{print $2}'); PORT=$(echo "$PRE" | awk '{print $3}'); RUNSZ=$(echo "$PRE" | awk '{print $4}')
if [ "$JAR" != "jar" ] || [ "$HAVE" != "$WANT" ]; then
  IR=$(sh "$SK_DIR/scripts/install.sh" 2>&1) || { sleep 2; IR=$(sh "$SK_DIR/scripts/install.sh" 2>&1); } || fail install "安装失败（已自动重试一次）：$(echo "$IR" | tr '\n"' '  ' | cut -c1-200)"
fi
[ -z "$PORT" ] && fail port "27183~27282 全被占用"

# 3. Cleanly restart everything and wait for readiness — ONE device-side call (stop → start server+bridge+overlay in parallel →
#    100 ms polling for "virtual display exists and the app is decoding"). Replaces ~10 separate adb round trips and 1 s polling.
LOCSZ=$(wc -c < "$SK_DIR/scripts/device-run.sh" | tr -d ' ')
if [ "$RUNSZ" != "$LOCSZ" ]; then adbx push "$SK_DIR/scripts/device-run.sh" $DEV/run.sh >/dev/null; adbx shell "chmod 755 $DEV/run.sh"; fi   # only when it changed
lap preflight
UP=$(T=60 adbx shell "BITRATE=$BR sh $DEV/run.sh up $SCID $SIZE $PORT $WX $WY $WW" | tr -d '\r' | tail -1)
case "$UP" in
  OK*) ID=$(echo "$UP" | awk '{print $2}'); DEVMS=$(echo "$UP" | awk '{print $3}') ;;
  *)   # one retry: heavy load can make the server slow to come up
       UP=$(T=60 adbx shell "BITRATE=$BR sh $DEV/run.sh up $SCID $SIZE $PORT $WX $WY $WW" | tr -d '\r' | tail -1)
       case "$UP" in OK*) ID=$(echo "$UP" | awk '{print $2}'); DEVMS=$(echo "$UP" | awk '{print $3}') ;;
         FAIL\ server*) fail server "scrcpy server did not start listening (retried once): see server.log (usual causes: jar missing / version mismatch / Android restrictions)" ;;
         FAIL\ frames*) fail frames "virtual display created but the overlay received no video frames: check the overlay permission and that the phone is unlocked" ;;
         *)             fail display "server did not create a virtual display: the bridge/overlay probably did not connect (port $PORT) or the phone is locked" ;;
       esac ;;
esac
lap up
echo "$ID" > "$STATE/display"; echo "$PORT" > "$STATE/port"
echo "{\"ok\":true,\"display\":$ID,\"port\":$PORT,\"size\":\"$SIZE\",\"ms\":{$TIMING,\"device\":${DEVMS:-0},\"total\":$(( $(now_ms) - T0 ))}}"
