#!/system/bin/sh
# 设备上运行：启动 TCP 桥（shell 身份）
exec >/data/local/tmp/sc/bridge.log 2>&1
CLASSPATH=/data/local/tmp/sc/shell.jar exec app_process / me.openminis.skilled.Bridge 00001234 27183 video,control
