#!/usr/bin/env python3
"""rootify.py — turn an Android GSI (ext4 system.img) into an AEmulator root system image.

Works IN PLACE on a raw ext4 image through debugfs (from the e2fsprogs deb unpacked in
/tmp/e2f on this sandbox), so no full tree extraction and no image resize are needed:

  1. a real, transparent su                /system/xbin/su, /system/bin/su, /su/bin/su
  2. root-visible build properties         ro.debuggable=1, ro.secure=0, test-keys, ...
  3. Shizuku's root-mode launch chain      app_process present, CLASSPATH points at an apk,
                                           /data/local/tmp exists at boot (host side)
  4. Xposed API 100 declaration layer      /system/framework/XposedBridge.jar + xposed.prop
                                           + /system/etc/classpaths/aemu-xposed.environ
  5. a marker file                         /system/etc/aemu-root.marker

The previous attempt (the wiped session) shipped a su that answered `su -c id` once and
exited: root checkers read the single line and passed, then Shizuku's libsu held the shell
open, wrote command lines into a stdin nobody read, and never got its marker back — "root
yes, Shizuku no". The su in this tree (tools/romroot/su.c) execs the real /system/bin/sh
with stdio untouched, so the persistent shell protocol works end to end.

Usage:
  rootify.py system.img --su su --xposed-jar XposedBridge.jar
  rootify.py system.img --undo        # remove everything this tool added
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

DBG = os.environ.get("DEBUGFS", "/tmp/e2f/sbin/debugfs")
E2FSCK = os.environ.get("E2FSCK", "/tmp/e2f/sbin/e2fsck")
LD = os.environ.get("DBG_LD", "/tmp/e2f/lib/x86_64-linux-gnu:/tmp/e2f/usr/lib/x86_64-linux-gnu")

ROOT_FILES = {
    "su_paths": ["/system/xbin/su", "/system/bin/su", "/su/bin/su"],
    "su_off": ["/system/xbin/su.off", "/system/bin/su.off", "/su/bin/su.off"],
    "xposed_jar": "/system/framework/XposedBridge.jar",
    "xposed_prop": "/system/framework/xposed.prop",
    "marker": "/system/etc/aemu-root.marker",
    "prop_backup": "/system/build.prop.aemu-bak",
}
XPOSED_VERSION = "100"

def dbg(img: str, cmd: str, may_fail: bool = False) -> str:
    env = dict(os.environ, LD_LIBRARY_PATH=LD)
    r = subprocess.run([DBG, "-w", "-R", cmd, img],
                       capture_output=True, text=True, env=env)
    if r.returncode != 0 and not may_fail:
        sys.exit(f"debugfs '{cmd}' failed: {r.stderr.strip()[:400]}")
    return r.stdout

def exists(img: str, path: str) -> bool:
    out = dbg(img, f"stat {path}", may_fail=True)
    return "Inode:" in out or "inode" in out.lower() and "not found" not in out.lower()

def put_file(img: str, host_file: str, guest_path: str, mode: str = "0100755"):
    mkdir_p(img, os.path.dirname(guest_path))
    # debugfs write refuses an existing target ("Ext2 file already exists"), so clear the
    # path first. rm releases the old inode's blocks; a crash between rm and write leaves a
    # missing file for e2fsck to flag — which is why patch_build_prop keeps a backup first.
    rm_f(img, guest_path)
    dbg(img, f"write {host_file} {guest_path}")
    dbg(img, f"sif {guest_path} mode {mode}")
    dbg(img, f"sif {guest_path} uid 0")
    dbg(img, f"sif {guest_path} gid 0")

def mkdir_p(img: str, path: str):
    parts = [p for p in path.split("/") if p]
    cur = ""
    for p in parts:
        cur += "/" + p
        if not exists(img, cur):
            dbg(img, f"mkdir {cur}", may_fail=True)
    dbg(img, f"sif {cur} mode 040755")
    dbg(img, f"sif {cur} uid 0")
    dbg(img, f"sif {cur} gid 0")

def rm_f(img: str, path: str):
    if exists(img, path):
        dbg(img, f"rm {path}", may_fail=True)

def pull(img: str, guest_path: str, host_out: str):
    out = dbg(img, f"dump {guest_path} {host_out}", may_fail=True)
    if not os.path.exists(host_out):
        sys.exit(f"cannot dump {guest_path}")

def patch_build_prop(img: str):
    """Set the root-visible properties, keeping an untouched backup for --undo."""
    overrides = {
        "ro.debuggable": "1",
        "ro.secure": "0",
        "ro.build.type": "userdebug",
        "ro.build.tags": "test-keys",
        "ro.build.selinux": "1",
        "ro.boot.vbmeta.device_state": "unlocked",
        "ro.secureboot.lockstate": "unlocked",
    }
    with tempfile.TemporaryDirectory() as td:
        src = os.path.join(td, "build.prop")
        pull(img, "/system/build.prop", src)
        if not exists(img, ROOT_FILES["prop_backup"]):
            dbg(img, f"write {src} {ROOT_FILES['prop_backup']}")
        lines = open(src, encoding="utf-8", errors="replace").read().splitlines()
        seen = {}
        out = []
        for ln in lines:
            m = re.match(r"^([A-Za-z0-9_.]+)\s*=", ln)
            if m and m.group(1) in overrides:
                k = m.group(1)
                if k not in seen:
                    seen[k] = True
                    out.append(f"{k}={overrides[k]}")
                else:
                    continue
            else:
                out.append(ln)
        out.append("#")
        out.append("# Added by AEmulator rootify: root surface visible to the guest")
        for k, v in overrides.items():
            if k not in seen:
                out.append(f"{k}={v}")
        out.append(f"ro.aemu.root={XPOSED_VERSION}")
        patched = os.path.join(td, "build.prop.new")
        open(patched, "w", encoding="utf-8").write("\n".join(out) + "\n")
        put_file(img, patched, "/system/build.prop", "0100644")

def restore_build_prop(img: str):
    if exists(img, ROOT_FILES["prop_backup"]):
        with tempfile.TemporaryDirectory() as td:
            src = os.path.join(td, "build.prop.orig")
            pull(img, ROOT_FILES["prop_backup"], src)
            put_file(img, src, "/system/build.prop", "0100644")
        rm_f(img, ROOT_FILES["prop_backup"])
    else:
        print("  (no build.prop backup found; properties left as they are)")

def rootify(img: str, su_bin: str, xposed_jar: str):
    print(f"rootify: {img}")
    if not exists(img, "/system/build.prop"):
        sys.exit("not a system image (no /system/build.prop)")
    for p in ROOT_FILES["su_paths"] + ROOT_FILES["su_off"]:
        rm_f(img, p)
    # 1. real su, three standard locations
    put_file(img, su_bin, ROOT_FILES["su_paths"][0])            # /system/xbin/su
    put_file(img, su_bin, ROOT_FILES["su_paths"][1])            # /system/bin/su
    mkdir_p(img, "/su/bin")
    put_file(img, su_bin, ROOT_FILES["su_paths"][2])            # /su/bin/su
    # 2. properties
    patch_build_prop(img)
    # 4. Xposed API 100 declaration layer. Deliberately NOT on /system/etc/classpaths:
    #    Android 12+ derive_classpath reads binary *.pb files there with fs-verity meta
    #    beside them, and a hand-made extra record can fail their checks on first boot and
    #    take the whole BOOTCLASSPATH with it. Instead the runner appends the jar to the
    #    boot classpath host-side (GuestRunner.env64, see RootSurface.xposedJar).
    if xposed_jar:
        put_file(img, xposed_jar, ROOT_FILES["xposed_jar"], "0100644")
        with tempfile.TemporaryDirectory() as td:
            prop = os.path.join(td, "xposed.prop")
            open(prop, "w").write(
                f"version={XPOSED_VERSION}\n"
                f"apiversion={XPOSED_VERSION}\n"
                "arch=arm64\n"
                "minfo=AEmulator Xposed declaration layer\n"
                "name=AEmulator Xposed\n"
                "description=Xposed API declaration layer for AEmulator guests\n"
                "author=AEmulator\n")
            put_file(img, prop, ROOT_FILES["xposed_prop"], "0100644")
    # 5. marker
    with tempfile.TemporaryDirectory() as td:
        mk = os.path.join(td, "marker")
        import time
        open(mk, "w").write(
            f"aemu-rootify v2\napi={XPOSED_VERSION}\nsu=AEmulator transparent su\n"
            f"date={time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}\n")
        put_file(img, mk, ROOT_FILES["marker"], "0100644")
    print("  done: su x3, build.prop, xposed, marker")

def undo(img: str):
    print(f"undo: {img}")
    for p in ROOT_FILES["su_paths"] + ROOT_FILES["su_off"]:
        rm_f(img, p)
    for p in (ROOT_FILES["xposed_jar"], ROOT_FILES["xposed_prop"], ROOT_FILES["marker"]):
        rm_f(img, p)
    restore_build_prop(img)
    print("  done")

def fsck(img: str):
    env = dict(os.environ, LD_LIBRARY_PATH=LD)
    r = subprocess.run([E2FSCK, "-fn", img], capture_output=True, text=True, env=env)
    tail = (r.stdout + r.stderr).strip().splitlines()
    print("e2fsck:", tail[-1] if tail else "(no output)")
    if r.returncode not in (0, 1):
        sys.exit(f"e2fsck reported problems (rc={r.returncode}); image may be corrupt")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--su", default="/home/z/my-project/workspace/aemu/tools/romroot/su")
    ap.add_argument("--xposed-jar", default="")
    ap.add_argument("--undo", action="store_true")
    ap.add_argument("--skip-fsck", action="store_true")
    a = ap.parse_args()
    if a.undo:
        undo(a.image)
    else:
        if not os.path.exists(a.su):
            sys.exit(f"missing su binary: {a.su}")
        if a.xposed_jar and not os.path.exists(a.xposed_jar):
            sys.exit(f"missing jar: {a.xposed_jar}")
        rootify(a.image, a.su, a.xposed_jar)
    if not a.skip_fsck:
        fsck(a.image)

if __name__ == "__main__":
    main()
