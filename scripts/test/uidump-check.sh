#!/bin/bash
# Is `uiautomator dump --display N` really per-display? Run on the Mac. Needs a virtual display with Calculator on it
# and a DIFFERENT app on the main screen so the two can be told apart.
export PATH=/opt/homebrew/bin:$PATH
S="adb -s ${SERIAL:-10.1.10.11:5555}"
D=$($S shell "grep -o 'New display: .*id=[0-9]*' /data/local/tmp/sc/server.log | tail -1 | sed 's/.*id=//'" | tr -d '\r ')
echo "virtual display id: $D"
count() {  # count <label> <dump args...>
  label=$1; shift
  $S shell "uiautomator dump $* /sdcard/c.xml >/dev/null 2>&1"
  $S pull /sdcard/c.xml /tmp/c_$$.xml >/dev/null 2>&1
  n=$(grep -o '<node ' /tmp/c_$$.xml | wc -l | tr -d ' ')
  pk=$(grep -o 'package="[^"]*"' /tmp/c_$$.xml | sort -u | tr '\n' ' ')
  echo "$label: nodes=$n  $pk"
}
count "no --display          " ""
count "--display 0 (main)    " "--display 0"
count "--display $D (virtual)" "--display $D"
