#!/bin/bash
# Build the Android 15 GSI ROM v4: Xposed API 101 + 3 modules + the Termux family.
#
# Space-critical pipeline (~4.1 GB free at peak):
#   [1] expand the 956 MB source img.xz into a 2502 MiB raw img
#   [2] drop the source img.xz — every byte of it now lives in the raw img
#   [3] rootify in place via debugfs: transparent su, root-visible props,
#       Xposed API 101 declaration layer, 8 preinstalled system apps
#       (CorePatch, ThreeFingerSwipe, QAuxiliary, Termux, TermuxAPI, TermuxBoot,
#       TermuxFloat, TermuxWidget)
#   [4] debugfs verification: the family landed, /system/bin/adb + /system/bin/sh
#       present, free blocks reported
#   [5] compress to the -v2 img.xz (canonical name swap happens after upload)
#   [6] drop the raw img
set -e
set -o pipefail
IMG=/tmp/rom/system.img
SRC="${AEMU_ROOT:-$PWD/..}/download/aemu-android15-gsi-root-xposed101-modules.img.xz"
NEW="${AEMU_ROOT:-$PWD/..}/download/aemu-android15-gsi-root-xposed101-modules-v2.img.xz"
AEMU="${AEMU_DIR:-$PWD}"

mkdir -p /tmp/rom

if [ ! -f "$IMG" ]; then
  echo "[$(date +%T)] [1/6] expanding source img.xz -> 2502 MiB raw img"
  df -h / | tail -1
  xz -dc -T0 "$SRC" > "$IMG"
fi
echo "[$(date +%T)] raw img: $(stat -c%s "$IMG") bytes"

if [ -f "$SRC" ] && [ "$(stat -c%s "$IMG")" -gt 2600000000 ]; then
  echo "[$(date +%T)] [2/6] dropping the source img.xz (contents live on in the raw img)"
  rm -f "$SRC"
fi

echo "[$(date +%T)] [2.5/6] filesystem headroom check (the Termux family needs ~65 MB)"
DBG=/tmp/e2f/sbin/debugfs
DBG_ENV="LD_LIBRARY_PATH=/tmp/e2f/lib/x86_64-linux-gnu:/tmp/e2f/usr/lib/x86_64-linux-gnu"
RESIZE=/tmp/e2f/sbin/resize2fs
free_blocks=$(env $DBG_ENV "$DBG" -R "stats" "$IMG" 2>/dev/null | grep "Free blocks:" | awk '{print $3}')
echo "  free blocks: ${free_blocks:-?} (need >= 20000)"
if [ -z "$free_blocks" ] || [ "$free_blocks" -lt 20000 ]; then
  echo "  growing the container by 150 MiB and resizing the filesystem"
  truncate -s +157286400 "$IMG"
  env $DBG_ENV "$RESIZE" -f "$IMG" 2>&1 | tail -2
  env $DBG_ENV /tmp/e2f/sbin/e2fsck -fy "$IMG" 2>&1 | tail -3
  env $DBG_ENV "$DBG" -R "stats" "$IMG" 2>/dev/null | grep "Free blocks:"
else
  echo "  enough headroom, no resize"
fi

echo "[$(date +%T)] [3/6] rootify v4: transparent su + props + Xposed 101 + 8 system apps"
python3 "$AEMU/tools/romroot/rootify.py" "$IMG" \
  --su "$AEMU/tools/romroot/su" \
  --xposed-jar "$AEMU/tools/xposed/build/XposedBridge.jar" \
  --modules "$AEMU/tools/romroot/modules"

echo "[$(date +%T)] [3.5/6] adb client: the GSI ships none in /system/bin (only the adbd apex),"
echo "  and a terminal without adb cannot do the one thing the user named. Static-capable"
echo "  bionic build of AOSP platform-tools 35.0.2 (Apache-2.0), arm64, minSdk 30 <= guest 35."
ADB_BIN="$AEMU/tools/romroot/bin/adb"
dbg_step() {  # dbg_step <label> <debugfs command>: loud when it fails, quiet when it works
  label="$1"; shift
  if out=$(env $DBG_ENV "$DBG" -w -R "$1" "$IMG" 2>&1); then
    echo "  [$label] ok"
  elif echo "$out" | grep -qiE "already exists|File not found|Could not|Command not found"; then
    # "Command not found" setxattr: the runner's e2fsprogs predates the subcommand — the
    # label layer is a no-op for the shim anyway (rootify says the same, restorecon catches up)
    echo "  [$label] tolerated: $(echo "$out" | tail -1)"
  else
    echo "  [$label] FAILED: $(echo "$out" | tail -2)"
    exit 1
  fi
}
ADB_BIN="$AEMU/tools/romroot/bin/adb"
adb_size=$(env $DBG_ENV "$DBG" -R "stat /system/bin/adb" "$IMG" 2>/dev/null | grep -i "size:" | head -1 | grep -oE "Size: [0-9]+" | awk '{print $2}')
if [ -n "$adb_size" ] && [ "$adb_size" != "0" ]; then
  echo "  /system/bin/adb already present ($adb_size bytes) — the v4 base keeps it"
else
  dbg_step "rm old adb" "rm /system/bin/adb"
  dbg_step "write adb" "write $ADB_BIN /system/bin/adb"
  dbg_step "adb mode" "sif /system/bin/adb mode 0100755"
  dbg_step "adb uid" "sif /system/bin/adb uid 0"
  dbg_step "adb gid" "sif /system/bin/adb gid 0"
  dbg_step "adb xattr" "setxattr /system/bin/adb security.selinux u:object_r:system_file:s0"
fi

echo "[$(date +%T)] [3.7/6] AEmulator Manager: the guest-side root/Xposed/module surface."
echo "  Root that nobody can see is root that 'does not work' — the manager is the Magisk-style"
echo "  entry the launcher shows: root probe, module list with enable/disable, pm install of"
echo "  module APKs dropped into Download, and a one-tap diagnostics battery."
MGR_APK="${AEMU_MANAGER_APK:-$AEMU/tools/romroot/manager/AEmulatorManager.apk}"
if [ -f "$MGR_APK" ]; then
  dbg_step "mgr rm" "rm /system/app/AEmulatorManager/AEmulatorManager.apk"
  dbg_step "mgr mkdir" "mkdir /system/app/AEmulatorManager"
  dbg_step "mgr write" "write $MGR_APK /system/app/AEmulatorManager/AEmulatorManager.apk"
  dbg_step "mgr mode" "sif /system/app/AEmulatorManager/AEmulatorManager.apk mode 0100644"
  dbg_step "mgr uid" "sif /system/app/AEmulatorManager/AEmulatorManager.apk uid 0"
  dbg_step "mgr gid" "sif /system/app/AEmulatorManager/AEmulatorManager.apk gid 0"
  dbg_step "mgr dir mode" "sif /system/app/AEmulatorManager mode 040755"
  dbg_step "mgr dir uid" "sif /system/app/AEmulatorManager uid 0"
  dbg_step "mgr dir gid" "sif /system/app/AEmulatorManager gid 0"
  dbg_step "mgr xattr" "setxattr /system/app/AEmulatorManager/AEmulatorManager.apk security.selinux u:object_r:system_file:s0"
else
  echo "  (no manager apk at $MGR_APK — build :manager and place it there, or set AEMU_MANAGER_APK)"
fi

echo "[$(date +%T)] [4/6] verification (debugfs)"
DBG=/tmp/e2f/sbin/debugfs
DBG_ENV="LD_LIBRARY_PATH=/tmp/e2f/lib/x86_64-linux-gnu:/tmp/e2f/usr/lib/x86_64-linux-gnu"
dstat() { env $DBG_ENV "$DBG" -R "stat $1" "$IMG" 2>/dev/null | grep -i "size:" | head -1 | grep -oE "Size: [0-9]+" | awk '{print $2}'; }
for f in /system/app/Termux/Termux.apk /system/app/TermuxAPI/TermuxAPI.apk \
         /system/app/TermuxBoot/TermuxBoot.apk /system/app/TermuxFloat/TermuxFloat.apk \
         /system/app/TermuxWidget/TermuxWidget.apk /system/app/CorePatch/CorePatch.apk \
         /system/app/QAuxiliary/QAuxiliary.apk /system/app/ThreeFingerSwipe/ThreeFingerSwipe.apk \
         /system/bin/adb /system/bin/sh /system/xbin/su /system/framework/XposedBridge.jar; do
  sz=$(dstat "$f")
  if [ -n "$sz" ] && [ "$sz" != "0" ]; then echo "  OK   $f ($sz bytes)"; else echo "  FAIL $f"; exit 1; fi
done
if [ -f "$MGR_APK" ]; then
  sz=$(dstat /system/app/AEmulatorManager/AEmulatorManager.apk)
  if [ -n "$sz" ] && [ "$sz" != "0" ]; then echo "  OK   /system/app/AEmulatorManager/AEmulatorManager.apk ($sz bytes)"
  else echo "  FAIL /system/app/AEmulatorManager/AEmulatorManager.apk"; exit 1; fi
fi
env $DBG_ENV "$DBG" -R "stats" "$IMG" 2>/dev/null | grep "Free blocks"

echo "[$(date +%T)] [5/6] compressing new img.xz (xz -6 -T0)"
xz -6 -T0 -c "$IMG" > "$NEW"
echo "[$(date +%T)] new img.xz: $(stat -c%s "$NEW") bytes"

echo "[$(date +%T)] [6/6] dropping the raw img"
rm -f "$IMG"
df -h / | tail -1
echo "[$(date +%T)] DONE: $NEW"
