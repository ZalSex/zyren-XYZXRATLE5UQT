#!/usr/bin/env python3

import os, sys
from pathlib import Path

SRC_DIR = Path("android/app/src/main/assets")

# Asset encryption disabled — assets tidak diubah
print("[encrypt] ✓ Asset encryption disabled")
print("[encrypt] Semua asset file dimuat tanpa enkripsi")
print("[encrypt] No changes made to assets")
sys.exit(0)