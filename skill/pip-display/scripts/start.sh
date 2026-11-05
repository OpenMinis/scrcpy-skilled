#!/bin/sh
# Start the secondary display (idempotent, self-healing): scrcpy server (new virtual display) → TCP bridge → floating window, then wait until truly ready (video frames arrived).
# Usage: start.sh [WxH/dpi, default = physical main screen size and dpi] [window x] [window y] [window width dp]
# On success prints one JSON line: {"ok":true,"display":N,"port":P,"w":..,"h":..}; on failure prints {"ok":false,"stage":"...","hint":"...","log":"..."} and exits non-zero.
. "$(dirname "$0")/common.sh"; ensure_adb || { echo "{\"ok\":false,\"stage\":\"adb\",\"hint\":\"设备未连接或未授权，见 references/setup.md\"}"; exit 3; }
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

# 1. Auto install/upgrade: reinstall if a jar is missing, the APK is not installed, or its version differs from the bundled one
WANT=$(cat "$SK_DIR/assets/VERSION" 2>/dev/null)
HAVE=$(adbx shell "dumpsys package $PKG 2>/dev/null | grep -m1 versionName | sed 's/.*=//'" | tr -d '\r ')
if ! adbx shell "[ -f $DEV/server.jar ] && echo ok" | grep -q ok || [ "$HAVE" != "$WANT" ]; then
  IR=$(sh "$SK_DIR/scripts/install.sh" 2>&1) || { sleep 2; IR=$(sh "$SK_DIR/scripts/install.sh" 2>&1); } || fail install "安装失败（已自动重试一次）：$(echo "$IR" | tr '\n"' '  ' | cut -c1-200)"
fi
adbx push "$SK_DIR/scripts/device-run.sh" $DEV/run.sh >/dev/null; adbx shell "chmod 755 $DEV/run.sh"

# 2. Pick a free port for the bridge (if default 27183 is taken, try the next ones) and pass it to the bridge and window
PORT=$(adbx shell "p=27183; while [ \$p -lt 27283 ]; do if ! (cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -qi \":\$(printf %04X \$p) \"); then echo \$p; break; fi; p=\$((p+1)); done" | tr -d '\r')
[ -z "$PORT" ] && fail port "27183~27282 全被占用"

# 3. Cleanly restart all processes
sh "$SK_DIR/scripts/stop.sh" quiet
adbx shell "BITRATE=$BR nohup setsid $DEV/run.sh server $SCID $SIZE </dev/null >/dev/null 2>&1 &"
# Wait for the server to listen (abstract socket appears). May take 10+ seconds under heavy load; on timeout restart the server and wait once more.
wait_server() { for i in $(seq 1 20); do adbx shell "grep -q scrcpy_$SCID /proc/net/unix && echo up" | grep -q up && return 0; sleep 1; done; return 1; }
if ! wait_server; then
  sh "$SK_DIR/scripts/stop.sh" quiet
  adbx shell "BITRATE=$BR nohup setsid $DEV/run.sh server $SCID $SIZE </dev/null >/dev/null 2>&1 &"
  wait_server || fail server "scrcpy server 40 秒内未监听（已重启一次）：看 server.log（常见：jar 丢失/版本不匹配/Android 限制）"
fi
adbx shell "PORT=$PORT nohup setsid $DEV/run.sh bridge $SCID $PORT </dev/null >/dev/null 2>&1 &"
for i in $(seq 1 15); do adbx shell "grep -q listening $DEV/bridge.log" && break; sleep 1; done
adbx shell "grep -q 'listening' $DEV/bridge.log" || fail bridge "桥未能监听端口 $PORT：看 bridge.log"
adbx shell "am start-foreground-service -n $PKG/.PipService --ei port $PORT --ei x $WX --ei y $WY --ei w $WW" >/dev/null

# 4. Wait for "ready": virtual display created + video frames arrived (up to 20 seconds)
ID=""; READY=""
for i in $(seq 1 20); do
  [ -z "$ID" ] && ID=$(adbx shell "grep -o 'New display: .*id=[0-9]*' $DEV/server.log" | tail -1 | sed 's/.*id=//' | tr -d '\r)')
  if [ -n "$ID" ]; then
    S=$(agent "/status" 2>/dev/null)
    echo "$S" | grep -q '"ready":true' && { READY=1; break; }
  fi
  sleep 1
done
[ -z "$ID" ] && fail display "server 未创建虚拟屏：多为桥与小窗未连上（端口 $PORT）或设备锁屏"
[ -z "$READY" ] && fail frames "虚拟屏 $ID 已建，但小窗没收到视频帧：确认悬浮窗权限已授予、手机已解锁亮屏"
echo "$ID" > "$STATE/display"; echo "$PORT" > "$STATE/port"
echo "{\"ok\":true,\"display\":$ID,\"port\":$PORT,\"size\":\"$SIZE\"}"
