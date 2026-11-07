#!/bin/bash
# Deploy the android-vdisplay skill into the Minis app ON THE PHONE (Pixel 6), then verify it is visible.
# Runs on the Mac (the build Mac). Needs: /tmp/android-vdisplay already synced (sh scripts/remote.sh skill) and the
# debug build of Minis (run-as works). Usage: SERIAL=192.0.2.10:5555 bash /tmp/deploy-skill.sh
set -e
A="adb -s ${SERIAL:-192.0.2.10:5555}"
SRC=/tmp/android-vdisplay
[ -f "$SRC/SKILL.md" ] || { echo "ERR: $SRC not synced"; exit 1; }
WANT=$(sed -n 's/^version: *//p' "$SRC/SKILL.md" | head -1)
echo "skill version to deploy: $WANT"

tar -C /tmp -cf /tmp/vd-skill.tar android-vdisplay
$A push /tmp/vd-skill.tar /data/local/tmp/sc/vd-skill.tar >/dev/null
$A shell 'run-as com.openminis.app sh -c "
  D=files/minis-global/skills
  [ -d \$D ] || { echo NO_SKILLS_DIR; exit 3; }
  rm -rf \$D/android-vdisplay
  tar -xf /data/local/tmp/sc/vd-skill.tar -C \$D
  chmod -R u+rwX \$D/android-vdisplay
  ls \$D/android-vdisplay | tr \"\\n\" \" \"; echo
"'
GOT=$($A shell 'run-as com.openminis.app sh -c "sed -n \"s/^version: *//p\" files/minis-global/skills/android-vdisplay/SKILL.md | head -1"' | tr -d '\r')
[ "$GOT" = "$WANT" ] && echo "OK: phone now has skill $GOT" || { echo "MISMATCH: phone has '$GOT', wanted '$WANT'"; exit 1; }
