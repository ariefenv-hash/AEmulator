# AEmulator 0.0.0.7

### The manager: root you can actually see and steer
- The guest's root and module machinery existed, but nothing on the launcher said so — users
  saw modules they never installed and no way to install more, and any Magisk-style expectation
  had no answer. A new `:manager` module ships **AEmulator Manager** (`app.aemu.manager`, plain
  android.* views, zero dependencies, ~40 KB): the Magisk-style surface that runs inside the guest.
- **Status page** — a live su probe (`su -c id` with output), the Xposed declaration-layer check
  done in-process (boot classpath → `XposedBridge.getXposedVersion()` = 101), and the honest
  "about Magisk" note: root here comes from the engine shim, not MagiskSU, so the Magisk app
  would report Not installed — this manager is the entry point instead.
- **Modules page** — every Xposed module the PackageManager can see (classic `xposedmodule`
  meta-data, the libxposed API generation, `xposedscope`/`xposeddescription` resolved from
  string or resource), badges for system vs user installs, real enable/disable through
  `pm disable`/`pm enable` (takes effect: icon gone, app stopped), uninstall for user modules,
  and "stop scope apps to apply" (`am force-stop` per declared scope — no reboot needed).
- **Install page** — drop a module APK into `Download` (from the host: the image's shared
  storage-card folder), tap scan, install via `pm install -r` as root. Preinstalled modules
  stay disable-only: `/system` is read-only inside the guest by design.
- **Diagnostics page** — a 15-item battery (su, shim ids, standard su paths, root props, the
  read-only /system probe, declaration layer, `xposed.prop`, BOOTCLASSPATH injection,
  preinstalled apps, pm, the module registry write probe, `adb version`, Shizuku server,
  Termux, `/data/local/tmp`), each with raw output and a copyable report.
- **Help page** — the how-to in six sections, including why some modules say "not activated":
  this build ships the API 101 declaration layer without an ART hook engine, and the manager
  says so instead of pretending.
- The registry `/data/system/aemu-xposed/modules.conf` (`pkg=on|off`, written base64 through
  su) is the single enabled-state source both the manager and anything that ever loads module
  entry points can read. Host settings sheet: a line under the Root switch points at the
  manager, in all 18 locales.

### Signing, finally solved
- A new release keystore (RSA-4096, 30-year validity, alias `aemu`) rides the repo as four
  Actions secrets; CI writes `keystore.properties` and signs **both** the app and the manager
  with the same stable key — debug and release alike — so updates install over the previous
  build without the per-runner debug-keystore lottery. The keystore file itself ships as a
  release asset with the password documented beside it (the old key's password was lost, which
  is what blocked local signing until now).

### ROM pipeline
- `scripts/build_rom_v4.sh` installs the manager APK into `/system/app/AEmulatorManager/`
  (debugfs, same xattr/mode treatment as adb) when it finds one at
  `tools/romroot/manager/AEmulatorManager.apk` (or `$AEMU_MANAGER_APK`), and verifies it
  byte-exact along the rest of the family.

# AEmulator 0.0.0.6

### RAM, without the 4 GB ceiling
- The settings slider stopped at 4096 MB by construction; heavy Android 15 firmware starved at
  exactly that. The ladder now runs 256 MB to 16 GB, bounded by what the phone can give: 3/4 of
  the host's memory (4 GB floor keeps the old ceiling on smaller devices), and a value carried in
  from another phone's `image.json` outside the steps stays selectable. The auto profile ("what
  phones of that Android version shipped with") is unchanged.

### Terminal
- The terminal panel drove one emulator cold start per command through `GuestRunner.run`, so
  every line cost seconds and `cd`/`export` evaporated between them. `GuestShellSession` keeps
  one guest `sh` alive: state persists, repeat commands are instant, a dead or `exit`ed shell
  respawns with the same prefix on the next command, a wedged command times out and resets, and
  the session closes with the VM.

### The ROM carries a terminal family
- The Android 15 GSI in the release now preinstalls the five Termux apps (main 0.118.3, :API
  0.53.0, :Boot 0.8.1, :Float 0.17.0, :Widget 0.15.0 — all one official signature, all
  system apps scanned on first boot) next to the three Xposed modules.
- The GSI ships no `adb` client (only the adbd apex): one is injected as `/system/bin/adb`
  (AOSP platform-tools 35.0.2 bionic build, Apache-2.0), so a guest terminal can run adb
  against the engine's own AdbServer at `127.0.0.1:5555`.
- The image grew to 2652 MiB for all of it (the filesystem had 0 free blocks after Termux alone;
  `scripts/build_rom_v4.sh` resizes when the headroom check says so) — every install verified by
  byte-exact `debugfs` stat plus `e2fsck`.

### L10n fixes
- The 0.0.0.4 Root strings had been appended past `</resources>` in 17 locale files (the merge
  step failed with "markup following the root element"), and French carried an unescaped
  apostrophe ("jusqu'au"). Both fixed; the release builds again from a clean checkout.

# AEmulator 0.0.0.4

**🇬🇧 English** · [🇷🇺 Русский](docs/i18n/CHANGELOG.ru.md) · [🇨🇳 简体中文](docs/i18n/CHANGELOG.zh-CN.md)

### Root, done properly
- A real, transparent su for the guest (`tools/romroot/su.c`): no-libc arm64, ~2 KB, execs the
  firmware's own `/system/bin/sh` with stdin/out/err untouched. This is what the previous attempt
  was missing — a su that answered `su -c id` once and exited let root checkers pass, but Shizuku's
  libsu holds one shell open and feeds it lines on stdin, so every root launch hung and Shizuku
  never started. `su`, `su CMD`, `su -c CMD`, `su -mm`, `su -v/-V` all behave the way libsu,
  Shizuku and the usual root checkers expect.
- `tools/romroot/rootify.py` turns a raw ext4 GSI into a rooted image in place (debugfs, no tree
  extraction): su into `/system/xbin`, `/system/bin` and `/su/bin`, root-visible properties
  (`ro.debuggable=1`, `ro.secure=0`, test-keys), the Xposed layer below, and a marker file. An
  untouched build.prop backup rides along; `--undo` restores everything.
- Shizuku's root launch chain works end to end: `app_process`/`app_process64` are verified present,
  `/data/local/tmp` is created at first boot for libsu's scratch files, the server runs as uid 0
  (the engine's shim grants setuid to every guest process), and binder services register through
  the user-space binderd, which has no service-permission wall to trip over.
- The Android 15 GSI shipped with the release (LineageOS 22.2 arm64, ext4) is rootified out of the
  box: root checkers detect root, Shizuku's "start with root" works, `adb root` mode available
  through `ro.debuggable`.

### Xposed API 100
- The guest now ships an Xposed API declaration layer at level 100 (`tools/xposed`, built with ECJ
  + D8, no Android SDK needed): `de.robv.android.xposed.*` on the boot classpath of every guest
  app, so a checker calling `XposedBridge.getXposedVersion()` gets 100 and LSPosed-class managers
  recognise the framework. `xposed.prop` accompanies the jar for the classic detectors.
- Honest by design: `hookMethod` is declared, not wired — no ART hook engine is bundled in this
  build, and modules calling it get an explicit failure naming what is missing instead of a silent
  no-op. Boot classpath injection is host-side (the runner appends the jar after
  `derive_classpath`), so the image's own classpath files stay untouched and fs-verity metadata
  stays valid.

### Settings
- New Root section for images that ship the rootify surface: one switch parks the su copies
  host-side (`su` → `su.off`) before any guest service runs; on restores them. Applies at the
  next boot like every other toggle; images without the surface simply don't show it.
- The switch is per instance (persisted in `image.json`), and an imported image that carries the
  surface starts with it on.

### Tools
- `tools/romroot/`: `su.c` (the transparent su), `rootify.py` (debugfs in-place image rewriter)
  and the release pipeline for the Android 15 GSI: segmented multi-mirror download, 7z extraction,
  in-place rootify, e2fsck verification, xz repack.
- `tools/xposed/`: the API 100 declaration layer sources and the ECJ+D8 build (`build.sh`).

### Android 15 GSI
- Verified on LineageOS 22.2 (Android 15, API 35) arm64 ext4: import, boot, root surface, Xposed
  declaration layer and the Shizuku launch chain all apply without any image-specific patches.

# AEmulator 0.0.0.3

**🇬🇧 English** · [🇷🇺 Русский](docs/i18n/CHANGELOG.ru.md) · [🇺🇦 Українська](docs/i18n/CHANGELOG.uk.md) · [🇩🇪 Deutsch](docs/i18n/CHANGELOG.de.md) · [🇫🇷 Français](docs/i18n/CHANGELOG.fr.md) · [🇪🇸 Español](docs/i18n/CHANGELOG.es.md) · [🇧🇷 Português](docs/i18n/CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](docs/i18n/CHANGELOG.it.md) · [🇵🇱 Polski](docs/i18n/CHANGELOG.pl.md) · [🇹🇷 Türkçe](docs/i18n/CHANGELOG.tr.md) · [🇸🇦 العربية](docs/i18n/CHANGELOG.ar.md) · [🇮🇷 فارسی](docs/i18n/CHANGELOG.fa.md) · [🇮🇳 हिन्दी](docs/i18n/CHANGELOG.hi.md) · [🇮🇩 Indonesia](docs/i18n/CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](docs/i18n/CHANGELOG.vi.md) · [🇨🇳 简体中文](docs/i18n/CHANGELOG.zh-CN.md) · [🇯🇵 日本語](docs/i18n/CHANGELOG.ja.md) · [🇰🇷 한국어](docs/i18n/CHANGELOG.ko.md)

### Emulation
- New native arm64 engine: Android 5.0–17 and GSIs (8–17) run directly on the phone; Android 2.3–4.4 runs through QEMU.
- 64-bit firmware support; 32-bit programs inside them run through qemu-arm.
- Full Android 8 support, including GSIs.
- zygote and mediaserver crash much less often (binder fixes, system_server deadlock fixed).
- `/system` is read-only for the guest, so precompiled framework code is no longer lost.
- Faster first boot of Dalvik firmware (Galaxy S III: 104 s → 59 s).
- Option for hardware 32-bit execution (AArch32) without a translator, with QEMU as the fallback.
- GLES 1/2/3 through SwiftShader.

### Firmware and vendors
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` and binder patches.
- Qualcomm firmware: removed `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) and Tecno (AOSP / HiOS) detected by Android version.
- Sony: notification LED emulation.
- Fixes for MIUI, HTC Sense, TouchWiz, Xperia and AOSP. Tested on Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Import
- New formats: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Features
- Internet works in more firmware.
- Sound fixed; microphone, camera, accelerometer and vibration added.
- Navigation bar: Back, Home and Recents work on every firmware.
- ADB over the local network and a built-in ADB terminal.
- Three-dot menu in a running system fixed: screenshots, screen recording, VNC, SMS, airplane mode, APK install and more.
- The menu button can be moved in a running system.
- Option to hide the LED indicator: in defaults for new systems and per system.

### Host services
- Every guest-visible socket is now declared in one place (`EndpointRegistry`) and started in declaration order, so the service list and its live state have a single source of truth. Restarting an instance no longer inherits the previous boot's declarations.
- New control socket `aemu_bridge` (bproto2) alongside the legacy endpoints: properties, power, clipboard and the low-memory killer, each carrying its own op byte. The legacy protocols (`rild`, `vold`, `dnsproxyd`, `lmkd`) are untouched.
- Host services are granted per instance from a closed capability set (`hostCaps` in the image settings); a service an instance was not granted answers `EPERM`, and the dispatch path uses no reflection.
- Recovery mode no longer leaves a bound input socket behind after it exits.
- Fixed the `ab_hello_rep` layout: the record is 76 bytes on the wire (2 bytes of padding before `pid`), not 74. A C11 static assertion now fails the build if it ever changes again.
- `lmkd` can actually be granted now: it lives outside the host-service table but inside the capability set, and a grant that matched nothing used to disappear silently.
- The capability set is now visible: the settings sheet has a Host services section with a switch per capability, shown only for firmware whose kernel tag exports the bridge (Android 9+), because on older images the switches could not do anything.

### Build
- GitHub Actions builds every push: the bridge host test (gcc, `SOCK_SEQPACKET`), the mock host byte-layout self-test (Python), an armv7 `libaemushim.so` compile with an export check for `ab_call`/`ab_status`/`ab_last_len`, then the JVM unit tests and a debug APK.
- Build failures are republished as check annotations, because job logs on a public repository need admin rights to read.
