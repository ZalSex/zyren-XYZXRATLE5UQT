#!/usr/bin/env python3

import sys, os, zipfile, hashlib, struct
import glob as _glob

def _find_apk() -> str:
    patterns = [
        "android/app/build/outputs/apk/release/app-release-unsigned.apk",
        "android/app/build/outputs/apk/release/app-release.apk",
        "android/app/build/outputs/apk/release/*.apk",
    ]
    for p in patterns:
        found = _glob.glob(p)
        if found:
            return found[0]
    return ""

APK_PATH = _find_apk()
SKIP_NAMES = {"assets/uid.json"}
SKIP_PREFIX = ("META-INF/",)
XOR_KEY = 0x7E
OUT_FILE = "android/app/src/main/assets/integrity.bin"

def hash_apk(path: str) -> str:
    sha = hashlib.sha256()
    with zipfile.ZipFile(path, 'r') as zf:
        names = sorted(zf.namelist())
        for name in names:
            if name in SKIP_NAMES:
                continue
            if any(name.startswith(p) for p in SKIP_PREFIX):
                continue
            if name == "assets/integrity.bin":
                continue
            data = zf.read(name)
            sha.update(name.encode())
            sha.update(data)
    return sha.hexdigest()

if not os.path.exists(APK_PATH):
    print(f"APK Not Found: {APK_PATH}")
    sys.exit(1)

digest = hash_apk(APK_PATH)
print(f"APK Hash: {digest}")

raw = digest.encode()
xored = bytes(b ^ XOR_KEY for b in raw)

with open(OUT_FILE, 'wb') as f:
    f.write(xored)

print(f"Written To {OUT_FILE}")

with zipfile.ZipFile(APK_PATH, 'a') as zf:
    pass

import shutil, tempfile

tmp = APK_PATH + ".tmp"

with zipfile.ZipFile(APK_PATH, 'r') as zin, \
     zipfile.ZipFile(tmp, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "assets/integrity.bin":
            continue
        zout.writestr(item, zin.read(item.filename))

    zout.write(OUT_FILE, "assets/integrity.bin")

os.replace(tmp, APK_PATH)

print(f"Injected Into APK OK")