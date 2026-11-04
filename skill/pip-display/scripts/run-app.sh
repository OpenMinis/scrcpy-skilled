#!/bin/sh
# 在副屏启动 App：run-app.sh <包名>
. "$(dirname "$0")/common.sh"; ensure_adb || exit $?
agent "/start?pkg=$1"; echo
