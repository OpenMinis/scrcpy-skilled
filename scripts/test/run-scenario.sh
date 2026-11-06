#!/bin/bash
# Run one Minis chat scenario on the phone via the debug server and keep the full transcript. Runs on the Mac.
# usage: run-scenario.sh <n>   (reads /tmp/sc<n>.txt, writes /tmp/sc<n>.out)
n=$1
S=$(date +%s)
MAXOUT=1500 python3 /tmp/minis-ask.py "$(cat /tmp/sc$n.txt)" --wait 360 > /tmp/sc$n.out 2>&1
echo "scenario $n finished rc=$? in $(( $(date +%s) - S ))s, $(wc -c < /tmp/sc$n.out) bytes"
