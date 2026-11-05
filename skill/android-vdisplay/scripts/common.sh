#!/bin/sh
# Shared helpers: locate the skill dir, adb wrapper with timeout and self-healing. Sourced by other scripts.
SK_DIR=$(cd "$(dirname "$0")/.." && pwd)
PKG=app.openminis.vdisplay
DEV=/data/local/tmp/sc
AGENT_PORT=${AGENT_PORT:-28080}
STATE=${SKILLED_STATE:-$HOME/.android-vdisplay}; mkdir -p "$STATE"

# adb target: SERIAL env var first; else the only online device; else try ADB_CONNECT (default localhost:5555)
adbx() {
  if [ -n "$SERIAL" ]; then timeout ${T:-30} adb -s "$SERIAL" "$@"; else timeout ${T:-30} adb "$@"; fi
}
# The adb server occasionally hangs (commands unresponsive). If detected, restart it on a different local port. The port is stored in $STATE/adbport.
adb_heal() {
  P=${ANDROID_ADB_SERVER_PORT:-$(cat "$STATE/adbport" 2>/dev/null || echo 5037)}
  export ANDROID_ADB_SERVER_PORT=$P
  if ! timeout 8 adb devices >/dev/null 2>&1; then
    P=$((P<5400?5400:P+1)); echo $P > "$STATE/adbport"; export ANDROID_ADB_SERVER_PORT=$P
    (adb start-server >/dev/null 2>&1 &); sleep 4
    [ -n "$ADB_CONNECT" ] && timeout 15 adb connect "$ADB_CONNECT" >/dev/null 2>&1
  fi
}
ensure_adb() {
  command -v adb >/dev/null 2>&1 || { echo "ERR: 找不到 adb（Alpine: apk add android-tools）"; return 2; }
  adb_heal
  if ! adbx devices 2>/dev/null | grep -q 'device$'; then
    adb start-server >/dev/null 2>&1
    if [ -n "$ADB_CONNECT" ] || [ -z "$SERIAL" ]; then timeout 15 adb connect "${ADB_CONNECT:-localhost:5555}" >/dev/null 2>&1; fi
  fi
  N=$(timeout 8 adb devices 2>/dev/null | grep -c 'device$')
  if [ "$N" = "0" ]; then echo "ERR: 无在线设备。请先开启开发者选项 → 无线调试并 adb pair/connect，见 references/setup.md"; return 3; fi
  if [ "$N" -gt 1 ] && [ -z "$SERIAL" ]; then echo "ERR: 多个设备，请设置 SERIAL=<序列号>"; adb devices; return 4; fi
  return 0
}
# Host calls the on-device agent API via adb forward; the local port is saved in $STATE/fport and the forward is recreated if lost
fwd() {
  FP=$(cat "$STATE/fport" 2>/dev/null || echo 28090)
  if ! timeout 4 curl -s "http://127.0.0.1:$FP/status" >/dev/null 2>&1; then
    adbx forward --remove "tcp:$FP" >/dev/null 2>&1
    while ! adbx forward "tcp:$FP" "tcp:$AGENT_PORT" >/dev/null 2>&1; do FP=$((FP+1)); [ $FP -gt 28190 ] && return 1; done
    echo $FP > "$STATE/fport"
  fi
}

# Fast path: call the agent straight through the existing forward; only if that fails, run the (slower) adb health check / forward repair.
# This removes `adb devices` and the /status probe from every successful call (they cost ~150–400 ms each time).
agent() {
  FP=$(cat "$STATE/fport" 2>/dev/null || echo 28090)
  R=$(timeout ${CT:-15} curl -s -m ${CT:-15} "http://127.0.0.1:$FP$1" 2>/dev/null) && [ -n "$R" ] && { printf '%s\n' "$R"; return 0; }
  ensure_adb || return $?
  fwd || { echo '{"ok":false,"error":"forward failed"}'; return 1; }
  timeout ${CT:-15} curl -s "http://127.0.0.1:$FP$1"
}
agent_to() {
  FP=$(cat "$STATE/fport" 2>/dev/null || echo 28090)
  timeout 20 curl -s -m 20 "http://127.0.0.1:$FP$1" -o "$2" 2>/dev/null && [ -s "$2" ] && return 0
  ensure_adb || return $?
  fwd || return 1; timeout 20 curl -s "http://127.0.0.1:$FP$1" -o "$2"
}

# Upgrade hint appended to all errors: update to the latest version and retry
SK_VERSION=$(cat "$SK_DIR/assets/VERSION" 2>/dev/null)
upgrade_hint() { echo "hint: 本 skill 版本 ${SK_VERSION:-?}。出错请先更新到最新版（重新安装本 skill 包，再运行 start.sh 会自动升级设备上的 APK），再试一次是否解决。详见 CHANGELOG.md"; }
# Read the APK version on the device: prints JSON (device_version/skill_version/match)
version_info() {
  HAVE=$(adbx shell "dumpsys package $PKG 2>/dev/null | grep -m1 versionName | sed 's/.*=//'" 2>/dev/null | tr -d '\r ')
  M=false; [ "$HAVE" = "$SK_VERSION" ] && M=true
  echo "{\"skill\":\"${SK_VERSION}\",\"apk\":\"${HAVE:-not-installed}\",\"match\":$M}"
}
