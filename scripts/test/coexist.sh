#!/bin/bash
# Regression: a scrcpy server started by someone else (different scid) must NOT be touched by start.sh / restart / stop.sh.
# Run from the repo root on the machine that has scripts/remote.sh.
R=scripts/remote.sh; FOREIGN=0000beef; PASS=0; FAIL=0
ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
count() { sh $R sh "adb -s \$SERIAL shell 'ps -A -o PID,ARGS | grep -E \"$1\" | grep -v grep | wc -l'" | tr -d ' \r\n'; }
sock()  { sh $R sh "adb -s \$SERIAL shell 'grep -c scrcpy_$FOREIGN /proc/net/unix'" | tr -d ' \r\n'; }

sh $R skill >/dev/null
sh $R run stop.sh quiet >/dev/null
cat > /tmp/foreign.sh <<EOF
#!/system/bin/sh
CLASSPATH=/data/local/tmp/sc/server.jar exec app_process / com.genymobile.scrcpy.Server 5.0.1 scid=$FOREIGN tunnel_forward=true control=false audio=false log_level=info
EOF
cat /tmp/foreign.sh | ssh -o BatchMode=yes ethan@mini2.wsen.me 'cat > /tmp/foreign.sh'
sh $R sh "adb -s \$SERIAL push /tmp/foreign.sh /data/local/tmp/sc/foreign.sh >/dev/null; adb -s \$SERIAL shell 'chmod 755 /data/local/tmp/sc/foreign.sh; nohup setsid /data/local/tmp/sc/foreign.sh </dev/null >/data/local/tmp/sc/foreign.log 2>&1 &'" >/dev/null
sleep 3
[ "$(sock)" = 1 ] && ok "foreign server is up before the test" || { bad "could not start the foreign server"; exit 1; }

echo "[1] start.sh"
sh $R run start.sh | head -1 | grep -q '"ok":true' && ok "our display started" || bad "our start failed"
[ "$(sock)" = 1 ] && ok "foreign server survived start.sh" || bad "foreign server was killed by start.sh"

echo "[2] second start.sh (replaces OUR processes)"
OLD=$(count 'scrcpy\.Server .*scid=76646973')
sh $R run start.sh | head -1 | grep -q '"ok":true' && ok "restart ok (ours: $OLD → $(count 'scrcpy\.Server .*scid=76646973'))" || bad "restart failed"
[ "$(count 'scrcpy\.Server .*scid=76646973')" = 1 ] && ok "exactly one of our servers" || bad "our server count != 1"
[ "$(sock)" = 1 ] && ok "foreign server survived the restart" || bad "foreign server was killed by the restart"

echo "[3] stop.sh"
sh $R run stop.sh quiet >/dev/null
[ "$(count 'scrcpy\.Server .*scid=76646973')" = 0 ] && ok "our server gone" || bad "our server still running"
[ "$(count 'vdisplay\.Bridge')" = 0 ] && ok "our bridge gone" || bad "our bridge still running"
[ "$(sock)" = 1 ] && ok "foreign server survived stop.sh" || bad "foreign server was killed by stop.sh"

# cleanup the foreign server
sh $R sh "adb -s \$SERIAL shell 'for p in \$(ps -A -o PID,ARGS | grep scid=$FOREIGN | grep -v grep | awk \"{print \\\$1}\"); do kill \$p; done'" >/dev/null
echo "result: $PASS passed, $FAIL failed"; [ $FAIL = 0 ]
