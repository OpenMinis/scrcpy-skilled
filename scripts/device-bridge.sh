#!/system/bin/sh
# Runs on device: start the TCP bridge (as shell user)
exec >/data/local/tmp/sc/bridge.log 2>&1
CLASSPATH=/data/local/tmp/sc/shell.jar exec app_process / app.openminis.vdisplay.Bridge 00001234 27183 video,control
