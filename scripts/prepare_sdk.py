#!/usr/bin/env python3
"""Install a fixed macOS Android toolchain locally without modifying system SDKs."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shutil
import subprocess
import zipfile
from fetch import fetch

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / ".tools/android-sdk"
PACKAGES = [
    ("android-ndk-r27c-darwin.zip", 836128272, "0217c10ffbec496bb9fbfbb3c6fc2477c6b77297", "ndk/27.2.12479018"),
    ("platform-35_r02.zip", 64273788, "0bb560a90a7a2cbd0dd8348224d518b638fe7949", "platforms/android-35"),
    ("build-tools_r35_macosx.zip", 76857898, "93ab8ce91230e067b5add4bfa79919c52b27f072", "build-tools/35.0.0"),
    ("cmake-3.22.1-darwin.zip", 38223266, "8604eeef9adadb626dbb70a7ff58a87e6a7b967a", "cmake/3.22.1"),
    ("platform-tools_r37.0.2-darwin.zip", 16516152, "6f86aa58ba42dd4bfe27477f3f11c09edc3ebe3f", "platform-tools"),
]

def install(entry):
    name, size, digest, target = entry
    destination = SDK / target
    if (destination / "source.properties").exists(): return
    archive = ROOT / ".tools" / name
    fetch("https://dl.google.com/android/repository/" + name, archive, size, digest, "sha1", workers=6)
    temp = ROOT / ".tools" / (name + ".unpack")
    temp.mkdir(exist_ok=True)
    # System unzip preserves executable mode bits required by Android's toolchain.
    subprocess.run(["unzip", "-qo", str(archive), "-d", str(temp)], check=True)
    entries = [p for p in temp.iterdir() if p.name != "__MACOSX"]
    source = entries[0] if len(entries) == 1 and entries[0].is_dir() else temp
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source), str(destination))
    print(f"Installed {target}", flush=True)

if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(install, PACKAGES))
