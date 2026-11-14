# romroot — the root surface of an AEmulator image

Two pieces, both meant to be audited before they are trusted:

- **`su.c`** — the guest's su. No libc, ~2 KB of arm64 (compiled `su` rides beside it for
  rootify; rebuild with `aarch64-linux-gnu-gcc -nostdlib -static -no-pie -O2 -ffreestanding
  -fno-builtin -fno-stack-protector -fno-asynchronous-unwind-tables -Wl,--build-id=none
  -Wl,-e,_start -o su su.c`). It does not elevate anything: the engine's shim already
  grants every guest id call. Its whole job is the command-line behaviour its callers
  were built against — `su`, `su CMD`, `su -c CMD`, `su -mm`, `su -v/-V` — with stdin,
  stdout and stderr passed straight through. That passthrough is what the previous
  attempt was missing and why Shizuku never started while root checkers passed.
- **`rootify.py`** — turns a raw ext4 system image into a rooted one IN PLACE (debugfs,
  no tree extraction): su into `/system/xbin`, `/system/bin` and `/su/bin`; root-visible
  properties (`ro.debuggable=1`, `ro.secure=0`, test-keys, `ro.aemu.root`); an untouched
  `build.prop.aemu-bak` backup (so `--undo` restores everything); the marker file
  `/system/etc/aemu-root.marker` that tells the app this image carries the surface.

  ```
  python3 rootify.py system.img                          # su only
  python3 rootify.py system.img --xposed-jar ../xposed/build/XposedBridge.jar
  python3 rootify.py system.img --undo                   # restore build.prop, remove everything
  ```

  Needs `debugfs`/`e2fsck` (e2fsprogs). Paths to the binaries can be overridden with
  `DEBUGFS` / `E2FSCK` / `DBG_LD`.

Shizuku notes (why this surface is enough): Shizuku app → libsu probe `su -c id` →
persistent su shell fed on stdin → `CLASSPATH=… app_process /system --nice-name=…
moe.shizuku.server.ShizukuServer`. The guest answers all three: real passthrough su,
`app_process64` straight out of the GSI, `/data/local/tmp` created by the host at first
boot, the server running as uid 0 (the shim), and service registration through the
user-space binderd that keeps no service-permission wall.

Xposed notes: the jar is the **API 100 declaration layer** (`tools/xposed`), riding the
boot classpath host-side (see `RootSurface.xposedJar` in `GuestRunner`). Hooking is
declared, not wired — no ART hook engine ships in this build, and `hookMethod` fails
with a message that says so rather than pretending.
