#!/bin/bash
# 一键：停旧进程 -> 启 scrcpy server(new_display) -> 启 TCP 桥 -> 拉起悬浮窗。需设备已 adb 连接。
cd "$(dirname "$0")/.."; A=scripts/adb-safe.sh
$A shell 'am force-stop me.openminis.skilled'
$A shell 'for p in $(ps -A -o PID,ARGS | grep -E "scrcpy.Server|skilled.Bridge" | grep -v grep | tr -s " " | cut -d" " -f2); do kill $p; done'
$A push build/scrcpy-server.jar /data/local/tmp/sc/server.jar >/dev/null
$A push build/skilled-shell.jar /data/local/tmp/sc/shell.jar >/dev/null
$A push scripts/run-server-newdisplay.sh /data/local/tmp/sc/run.sh >/dev/null
$A push scripts/device-bridge.sh /data/local/tmp/sc/bridge.sh >/dev/null
$A shell 'chmod 755 /data/local/tmp/sc/*.sh; nohup setsid /data/local/tmp/sc/run.sh </dev/null >/dev/null 2>&1 &'
sleep 3
$A shell 'nohup setsid /data/local/tmp/sc/bridge.sh </dev/null >/dev/null 2>&1 &'
sleep 2
$A shell 'am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; am start-foreground-service -n me.openminis.skilled/.PipService' | tail -1
sleep 5
$A shell 'grep -o "id=[0-9]*" /data/local/tmp/scsrv.log | tail -1'
