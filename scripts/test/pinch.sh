#!/system/bin/sh
# pinch.sh <cx> <cy> <startHalfGap> <endHalfGap> [steps] [holdMsBeforeMove]
# Two-finger horizontal pinch via raw multitouch events on the Pixel 6 touchscreen (/dev/input/event3).
# Finger A at (cx-gap, cy), finger B at (cx+gap, cy). Gap goes start -> end over <steps> steps.
D=/dev/input/event3
CX=$1; CY=$2; G0=$3; G1=$4; N=${5:-12}; HOLD=${6:-0}
EV_SYN=0; EV_KEY=1; EV_ABS=3
BTN_TOUCH=330; ABS_MT_SLOT=47; ABS_MT_TRACKING_ID=57; ABS_MT_POSITION_X=53; ABS_MT_POSITION_Y=54
syn() { sendevent $D $EV_SYN 0 0; }
pos() { sendevent $D $EV_ABS $ABS_MT_SLOT $1; sendevent $D $EV_ABS $ABS_MT_POSITION_X $2; sendevent $D $EV_ABS $ABS_MT_POSITION_Y $3; }
# both fingers down
sendevent $D $EV_ABS $ABS_MT_SLOT 0; sendevent $D $EV_ABS $ABS_MT_TRACKING_ID 9001; pos 0 $((CX-G0)) $CY
sendevent $D $EV_KEY $BTN_TOUCH 1; syn
sendevent $D $EV_ABS $ABS_MT_SLOT 1; sendevent $D $EV_ABS $ABS_MT_TRACKING_ID 9002; pos 1 $((CX+G0)) $CY; syn
[ "$HOLD" -gt 0 ] && sleep $(awk "BEGIN{print $HOLD/1000}")
i=1
while [ $i -le $N ]; do
  g=$(( G0 + (G1-G0)*i/N ))
  pos 0 $((CX-g)) $CY; pos 1 $((CX+g)) $CY; syn
  sleep 0.03
  i=$((i+1))
done
# lift both
sendevent $D $EV_ABS $ABS_MT_SLOT 0; sendevent $D $EV_ABS $ABS_MT_TRACKING_ID -1
sendevent $D $EV_ABS $ABS_MT_SLOT 1; sendevent $D $EV_ABS $ABS_MT_TRACKING_ID -1
sendevent $D $EV_KEY $BTN_TOUCH 0; syn
