#!/usr/bin/env python3
"""Restore pinned source/runtime dependencies. Android SDK/JDK are installed separately."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import tarfile
from fetch import fetch

ROOT = Path(__file__).resolve().parents[1]

def main():
    aar = ROOT / "app/libs/sherpa-onnx-2.0.1.aar"
    fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar",
        aar, 49441341, "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7")
    sha = (ROOT / "config/llama-commit.txt").read_text().strip()
    destination = ROOT / "third_party/llama.cpp"
    marker = destination / ".neuroscribe-commit"
    if not marker.exists() or marker.read_text().strip() != sha:
        if destination.exists():
            raise RuntimeError("Existing llama.cpp checkout has no matching marker. Check its revision before replacing it.")
        destination.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--depth", "1", "--filter=blob:none", "--no-checkout",
            "https://github.com/ggml-org/llama.cpp.git", str(destination)], check=True)
        subprocess.run(["git", "-C", str(destination), "fetch", "--depth", "1", "origin", sha], check=True)
        subprocess.run(["git", "-C", str(destination), "sparse-checkout", "set", "src", "include", "ggml", "cmake", "vendor"], check=True)
        subprocess.run(["git", "-C", str(destination), "checkout", "--detach", sha], check=True)
        marker.write_text(sha + "\n")
    print("Pinned native dependencies are ready.")

if __name__ == "__main__": main()
