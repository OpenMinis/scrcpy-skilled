#!/bin/sh
# Build on a remote Mac (default: build-host.local) and deploy to the test phone over the Mac's adb.
# Avoids the flaky adb server inside iSH. Usage:
#   scripts/remote.sh build              sync source, build APK + shell jar on the Mac
#   scripts/remote.sh install            build + install APK on the phone (+ grant overlay)
#   scripts/remote.sh skill              sync the skill dir (scripts/assets) to the Mac
#   scripts/remote.sh run <cmd> [args]   run a skill script on the Mac, e.g. run start.sh / run agent.sh status
#   scripts/remote.sh sh '<cmd>'         run an arbitrary command on the Mac (adb, SERIAL preset)
#   scripts/remote.sh snap <out.png> [x1,y1,x2,y2]   screenshot the phone, pulled back here
# Env: REMOTE (ssh target), SERIAL (adb device), SKILL_DIR (local skill path)
set -e
REMOTE=${REMOTE:-user@build-host.local}
SERIAL=${SERIAL:-192.0.2.10:5555}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SKILL_DIR=${SKILL_DIR:-/var/minis/skills/android-vdisplay}
RDIR=/tmp/scrcpy-skilled
RSKILL=/tmp/android-vdisplay
SSH="ssh -o ConnectTimeout=10 -o BatchMode=yes -o ServerAliveInterval=15"
ENV="export PATH=/opt/homebrew/bin:/opt/homebrew/opt/openjdk@17/bin:\$PATH ANDROID_HOME=\$HOME/Library/Android/sdk SERIAL=$SERIAL ADB_CONNECT=$SERIAL"

rsh() { $SSH "$REMOTE" "$ENV; $*"; }

sync_src() {
  # tar over ssh: rsync may be absent in iSH; exclude build outputs and the scrcpy submodule (not needed to build the app)
  # wipe the remote source dirs first: tar only adds files, so renamed/removed files would otherwise linger and get compiled
  $SSH "$REMOTE" "rm -rf $RDIR/app $RDIR/shell $RDIR/scripts && mkdir -p $RDIR"
  tar -C "$ROOT" -cf - --exclude=./build --exclude=./third_party --exclude=./.git . \
    | $SSH "$REMOTE" "mkdir -p $RDIR && tar -C $RDIR -xf -"
  # same signing key as before, otherwise the phone rejects the update (INSTALL_FAILED_UPDATE_INCOMPATIBLE)
  [ -f "$ROOT/build/dbg.jks" ] && $SSH "$REMOTE" "mkdir -p $RDIR/build && cat > $RDIR/build/dbg.jks" < "$ROOT/build/dbg.jks"
}

ensure_device() {
  rsh "adb connect $SERIAL >/dev/null 2>&1; adb -s $SERIAL get-state 2>/dev/null" | grep -q device \
    || { echo "ERR: $SERIAL not reachable from $REMOTE" >&2; exit 3; }
}

case "$1" in
  build)
    sync_src
    rsh "cd $RDIR && bash scripts/build-apk.sh 2>&1 | grep -v Picked | tail -3"
    ;;
  install)
    sync_src
    rsh "cd $RDIR && bash scripts/build-apk.sh 2>&1 | grep -v Picked | tail -3"
    ensure_device
    rsh "adb -s $SERIAL install -r $RDIR/build/skilled.apk | tail -1; adb -s $SERIAL shell 'appops set app.openminis.vdisplay SYSTEM_ALERT_WINDOW allow; dumpsys package app.openminis.vdisplay | grep -E \"versionName|lastUpdateTime\"'"
    # keep local copies of the artifacts in sync (for packaging the skill)
    $SSH "$REMOTE" "cat $RDIR/build/skilled.apk" > "$ROOT/build/skilled.apk"
    $SSH "$REMOTE" "cat $RDIR/build/skilled-shell.jar" > "$ROOT/build/skilled-shell.jar"
    ;;
  skill)
    cp "$ROOT/build/skilled.apk" "$ROOT/build/skilled-shell.jar" "$SKILL_DIR/assets/" 2>/dev/null || true
    cp "$ROOT/VERSION" "$SKILL_DIR/assets/VERSION"
    tar -C "$(dirname "$SKILL_DIR")" -cf - "$(basename "$SKILL_DIR")" | $SSH "$REMOTE" "rm -rf $RSKILL && mkdir -p /tmp && tar -C /tmp -xf -"
    echo "skill synced to $REMOTE:$RSKILL"
    ;;
  run)
    shift; ensure_device
    S=$1; shift
    # quote each arg so values with spaces (e.g. a title) survive the remote shell
    Q=""; for a in "$@"; do Q="$Q '$(printf %s "$a" | sed "s/'/'\\\\''/g")'"; done
    rsh "cd $RSKILL && sh scripts/$S$Q"
    ;;
  sh)
    shift; rsh "$*"
    ;;
  snap)
    OUT=$2; CROP=$3; ensure_device
    $SSH "$REMOTE" "$ENV; adb -s $SERIAL exec-out screencap -p" > "$OUT.raw"
    python3 -c "
from PIL import Image
im=Image.open('$OUT.raw'); im.load()
c='$CROP'
if c: im=im.crop(tuple(int(v) for v in c.split(',')))
im.save('$OUT')" && rm -f "$OUT.raw" && echo "$OUT"
    ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
