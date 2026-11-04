#!/bin/sh
# 启动副屏：scrcpy server(新虚拟屏) → TCP 桥 → 悬浮窗。成功后打印 DISPLAY_ID。
# 用法: start.sh [宽x高/dpi，默认 540x960/240] [小窗x] [小窗y] [小窗宽dp]
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
SIZE=${1:-540x960/240}; WX=${2:-20}; WY=${3:-300}; WW=${4:-200}; SCID=00001234
adbx shell "[ -f $DEV/server.jar ]" || sh "$SK_DIR/scripts/install.sh" || exit 1
adbx push "$SK_DIR/scripts/device-run.sh" $DEV/run.sh >/dev/null; adbx shell "chmod 755 $DEV/run.sh"
sh "$SK_DIR/scripts/stop.sh" quiet
adbx shell "nohup setsid $DEV/run.sh server $SCID $SIZE </dev/null >/dev/null 2>&1 &"
sleep 3
adbx shell "nohup setsid $DEV/run.sh bridge $SCID </dev/null >/dev/null 2>&1 &"
sleep 2
adbx shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; am start-foreground-service -n $PKG/.PipService --ei x $WX --ei y $WY --ei w $WW" | tail -1
for i in 1 2 3 4 5 6 7 8; do
  ID=$(adbx shell "grep -o 'New display: .*id=[0-9]*' $DEV/server.log" | tail -1 | sed 's/.*id=//' | tr -d '\r)')
  [ -n "$ID" ] && break; sleep 1
done
[ -z "$ID" ] && { echo "ERR: server 未创建虚拟屏，日志:"; adbx shell "tail -5 $DEV/server.log"; exit 1; }
echo "$ID" > "$STATE/display"; echo "DISPLAY_ID=$ID"
