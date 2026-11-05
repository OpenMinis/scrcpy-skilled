#!/system/bin/sh
# Runs on the device (pushed by start.sh): server <scid> <WxH/dpi> | bridge <scid> <port>
case "$1" in
server)
  exec >/data/local/tmp/sc/server.log 2>&1
  CLASSPATH=/data/local/tmp/sc/server.jar exec app_process / com.genymobile.scrcpy.Server 5.0.1 scid=$2 log_level=debug \
    audio=false control=true tunnel_forward=true send_dummy_byte=true new_display=$3 vd_system_decorations=true \
    video_bit_rate=${BITRATE:-4000000} max_fps=${FPS:-30} cleanup=false power_off_on_close=false power_on=false stay_awake=false ;;
bridge)
  exec >/data/local/tmp/sc/bridge.log 2>&1
  CLASSPATH=/data/local/tmp/sc/shell.jar exec app_process / me.openminis.skilled.Bridge $2 ${3:-27183} video,control ;;
esac
