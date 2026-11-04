#!/bin/sh
# 启动副屏（幂等、可自愈）：scrcpy server(新虚拟屏) → TCP 桥 → 悬浮窗，并等待"真正就绪"（视频帧已到达）。
# 用法: start.sh [宽x高/dpi，默认=物理主屏尺寸与 dpi] [小窗x] [小窗y] [小窗宽dp]
# 成功输出一行 JSON：{"ok":true,"display":N,"port":P,"w":..,"h":..}；失败输出 {"ok":false,"stage":"...","hint":"...","log":"..."} 并返回非 0。
. "$(dirname "$0")/common.sh"; ensure_adb || { echo "{\"ok\":false,\"stage\":\"adb\",\"hint\":\"设备未连接或未授权，见 references/setup.md\"}"; exit 3; }
if [ -z "$1" ]; then   # 默认与物理主屏一致（尺寸+dpi），App 布局与主屏相同
  WMS=$(adbx shell "wm size | tail -1 | sed 's/.*: //'" | tr -d '\r'); WMD=$(adbx shell "wm density | tail -1 | sed 's/.*: //'" | tr -d '\r')
  case "$WMS" in [0-9]*x[0-9]*) SIZE="$WMS/${WMD:-420}";; *) SIZE=540x960/240;; esac
else SIZE=$1; fi
 WX=${2:-20}; WY=${3:-300}; WW=${4:-200}; SCID=00001234
PIX=$(echo "${SIZE%%/*}" | awk -Fx '{print $1*$2}'); BR=$((PIX>1500000?8000000:4000000))
fail() { # fail <stage> <hint>
  L=$(adbx shell "echo '--- server'; tail -4 $DEV/server.log; echo '--- bridge'; tail -4 $DEV/bridge.log" 2>/dev/null | tr '\n\r' '  ' | sed 's/"/\\"/g' | cut -c1-600)
  V=$(version_info 2>/dev/null)
  echo "{\"ok\":false,\"stage\":\"$1\",\"hint\":\"$2\",\"version\":${V:-null},\"upgrade\":\"出错请先更新到最新版 skill/APK 再试\",\"log\":\"$L\"}"; exit 1; }

# 1. 自动安装/升级：jar 缺失、APK 未装、或 APK 版本与 skill 自带版本不一致 → 重装
WANT=$(cat "$SK_DIR/assets/VERSION" 2>/dev/null)
HAVE=$(adbx shell "dumpsys package $PKG 2>/dev/null | grep -m1 versionName | sed 's/.*=//'" | tr -d '\r ')
if ! adbx shell "[ -f $DEV/server.jar ] && echo ok" | grep -q ok || [ "$HAVE" != "$WANT" ]; then
  IR=$(sh "$SK_DIR/scripts/install.sh" 2>&1) || { sleep 2; IR=$(sh "$SK_DIR/scripts/install.sh" 2>&1); } || fail install "安装失败（已自动重试一次）：$(echo "$IR" | tr '\n"' '  ' | cut -c1-200)"
fi
adbx push "$SK_DIR/scripts/device-run.sh" $DEV/run.sh >/dev/null; adbx shell "chmod 755 $DEV/run.sh"

# 2. 选一个空闲端口给桥（默认 27183 被占用时自动顺延），并传给桥与小窗
PORT=$(adbx shell "p=27183; while [ \$p -lt 27283 ]; do if ! (cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -qi \":\$(printf %04X \$p) \"); then echo \$p; break; fi; p=\$((p+1)); done" | tr -d '\r')
[ -z "$PORT" ] && fail port "27183~27282 全被占用"

# 3. 干净重启各进程
sh "$SK_DIR/scripts/stop.sh" quiet
adbx shell "BITRATE=$BR nohup setsid $DEV/run.sh server $SCID $SIZE </dev/null >/dev/null 2>&1 &"
# 等 server 进入监听（abstract socket 出现），而不是盲 sleep
for i in 1 2 3 4 5 6 7 8 9 10; do adbx shell "grep -q scrcpy_$SCID /proc/net/unix && echo up" | grep -q up && break; sleep 1; done
adbx shell "grep -q scrcpy_$SCID /proc/net/unix" || fail server "scrcpy server 未监听：看 server.log（常见：jar 丢失/版本不匹配/Android 限制）"
adbx shell "PORT=$PORT nohup setsid $DEV/run.sh bridge $SCID $PORT </dev/null >/dev/null 2>&1 &"
for i in 1 2 3 4 5 6; do adbx shell "grep -q listening $DEV/bridge.log" && break; sleep 1; done
adbx shell "grep -q 'listening' $DEV/bridge.log" || fail bridge "桥未能监听端口 $PORT：看 bridge.log"
adbx shell "am start-foreground-service -n $PKG/.PipService --ei port $PORT --ei x $WX --ei y $WY --ei w $WW" >/dev/null

# 4. 等"就绪"：虚拟屏已创建 + 视频帧已到达（最多 20 秒）
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
