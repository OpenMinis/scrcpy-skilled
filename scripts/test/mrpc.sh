#!/bin/bash
# Query the Android Minis debug server (5321) from the Mac via adb forward (loopback => no token needed).
# usage: mrpc.sh <method> [json-params]     e.g.  mrpc.sh rpc.discover   |   mrpc.sh debug.appInfo '{}'
export PATH=/opt/homebrew/bin:$PATH
S=${SERIAL:-192.0.2.10:5555}; LP=${LP:-25321}
adb -s $S forward tcp:$LP tcp:5321 >/dev/null 2>&1
M=$1; P=${2:-{\}}
curl -s -m ${T:-60} -X POST "http://127.0.0.1:$LP/" -H 'Content-Type: application/json' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$M\",\"params\":$P}"
echo
