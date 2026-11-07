#!/usr/bin/env python3
"""Fetch pinned model weights, validate hashes, and package all models inside the APK."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
from fetch import fetch

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "app/src/main/assets/models"
DOWNLOADS = ROOT / ".tools/models"
WHISPER = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309/"
QWEN = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/"

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""): h.update(data)
    return h.hexdigest()

def main():
    MODELS.mkdir(parents=True, exist_ok=True)
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / "config/models.lock.json").read_text())
    def one(entry):
        dest = DOWNLOADS / entry["downloadName"]
        fetch(entry["url"], dest, entry["bytes"], entry.get("sha256"), workers=24 if entry["name"] == "punctuation.gguf" else 8)
        target = MODELS / entry["name"]
        if target.exists() and digest(target) == entry["modelSha256"]:
            return
        if entry.get("member"):
            with tarfile.open(dest) as archive:
                member = archive.getmember(entry["member"])
                assert member.isfile()
                with archive.extractfile(member) as src, target.open("wb") as out:
                    shutil.copyfileobj(src, out)
        else:
            shutil.copyfile(dest, target)
        if digest(target) != entry["modelSha256"]:
            raise RuntimeError(f"Packaged model checksum mismatch: {target}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(one, lock))
    manifest = {}
    for entry in lock:
        path = MODELS / entry["name"]
        h = hashlib.sha256()
        with path.open("rb") as stream:
            for data in iter(lambda: stream.read(1024 * 1024), b""): h.update(data)
        manifest[path.name] = {"sha256": h.hexdigest(), "bytes": path.stat().st_size}
    (MODELS / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (ROOT / "config/packaged-models.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("All six model files are ready and checksummed.", flush=True)

if __name__ == "__main__": main()
