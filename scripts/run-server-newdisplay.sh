#!/system/bin/sh
exec >/data/local/tmp/scsrv.log 2>&1
CLASSPATH=/data/local/tmp/sc/server.jar exec app_process / com.genymobile.scrcpy.Server 5.0.1 scid=00001234 log_level=debug audio=false control=true tunnel_forward=true send_dummy_byte=true new_display=540x960/240 vd_system_decorations=true video_bit_rate=4000000 max_fps=30 cleanup=false
