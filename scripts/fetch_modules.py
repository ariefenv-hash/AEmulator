#!/usr/bin/env python3
"""Fetch the bundled Xposed modules into tools/romroot/modules/.

Three modules, all open source and redistribution-friendly:

  - CorePatch          LSPosed/CorePatch            Apache-2.0
    Installer patches: signature-verification and downgrade restrictions off.
    The perfect companion for a ROM whose users feed it arbitrary APKs
    through ADB + pm install.
  - ThreeFingerSwipe   hxreborn/three-finger-swipe   MIT
    Built against libxposed API 101+ — the module that exercises the new
    declaration-layer level end to end.
  - QAuxiliary         cinit/QAuxiliary              GPL-3.0
    The everyday hook collection for the chat apps people actually run.

Each APK is renamed to its clean module name: rootify installs it as
/system/app/<name>/<name>.apk, and that directory name is what the guest
shows. Latest-release asset discovery goes through the GitHub API, so a
module bumping its version string does not break this script. When
github.com's release CDN is unreachable from your network, any mirror that
prefixes the release URL works — try, in order:
    https://gh-proxy.com/<url>  then  https://ghproxy.net/<url>
(the committed copies under tools/romroot/modules/ already work out of the
box; this script is only needed to refresh them).
"""
import json
import os
import sys
import urllib.request

REPOS = [
    ("LSPosed/CorePatch", "CorePatch"),
    ("hxreborn/three-finger-swipe", "ThreeFingerSwipe"),
    ("cinit/QAuxiliary", "QAuxiliary"),
]

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "tools", "romroot", "modules")

MIRRORS = ["", "https://gh-proxy.com/", "https://ghproxy.net/"]


def fetch(url: str, dest: str) -> bool:
    for m in MIRRORS:
        try:
            urllib.request.urlretrieve(m + url, dest)
            if os.path.getsize(dest) > 10_000:
                return True
        except Exception:
            continue
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    ok = True
    for repo, clean_name in REPOS:
        try:
            req = urllib.request.Request(
                f"https://api.github.com/repos/{repo}/releases/latest",
                headers={"Accept": "application/vnd.github+json",
                         "User-Agent": "aemu-module-fetcher"})
            with urllib.request.urlopen(req, timeout=30) as r:
                rel = json.load(r)
        except Exception as e:
            print(f"{repo}: release lookup failed: {e}")
            ok = False
            continue
        assets = [a for a in rel.get("assets", [])
                  if a.get("name", "").lower().endswith(".apk")]
        if not assets:
            print(f"{repo}: tag {rel.get('tag_name')} has no apk asset")
            ok = False
            continue
        asset = next((a for a in assets
                      if "arm64" in a["name"].lower()
                      or "universal" in a["name"].lower()), assets[0])
        url = asset["browser_download_url"]
        dest = os.path.join(OUT, f"{clean_name}.apk")
        print(f"{repo}: {rel.get('tag_name')} -> {asset['name']} "
              f"({asset['size'] // 1024} KB)")
        if fetch(url, dest):
            print(f"  saved {dest} ({os.path.getsize(dest) // 1024} KB)")
        else:
            print("  FAILED every mirror; keep the committed copy")
            ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
