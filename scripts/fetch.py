#!/usr/bin/env python3
"""Verified, resumable downloads. Uses curl's system trust store; never disables TLS checks."""
import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

def fetch(url, destination, size, digest=None, algorithm="sha256", workers=8):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    def valid(path):
        if not path.exists() or path.stat().st_size != size:
            return False
        if not digest:
            return True
        h = hashlib.new(algorithm)
        with path.open("rb") as stream:
            for data in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(data)
        return h.hexdigest() == digest
    if valid(destination):
        return
    parts = destination.with_name(destination.name + ".parts")
    parts.mkdir(exist_ok=True)
    chunk = 4 * 1024 * 1024
    ranges = [(i, offset, min(offset + chunk, size) - 1) for i, offset in enumerate(range(0, size, chunk))]
    def download(item):
        i, start, end = item
        output = parts / str(i)
        headers = parts / f"{i}.headers"
        if output.exists() and output.stat().st_size == end - start + 1 and headers.exists():
            previous = re.findall(r"content-range:\s*bytes\s+(\d+)-(\d+)/(\d+)", headers.read_text(errors="replace"), re.I)
            if previous and tuple(map(int, previous[-1])) == (start, end, size):
                return
        for attempt in range(5):
            result = subprocess.run(["curl", "-fsSL", "--connect-timeout", "30", "--max-time", "600",
                "--retry", "2", "--range", f"{start}-{end}", "-D", str(headers), url, "-o", str(output)], capture_output=True)
            text = headers.read_text(errors="replace") if headers.exists() else ""
            matches = re.findall(r"content-range:\s*bytes\s+(\d+)-(\d+)/(\d+)", text, re.I)
            correct = matches and tuple(map(int, matches[-1])) == (start, end, size)
            if result.returncode == 0 and output.exists() and output.stat().st_size == end-start+1 and (correct or (start == 0 and end == size-1)):
                return
            output.unlink(missing_ok=True)
            time.sleep(attempt + 1)
        raise RuntimeError(f"Failed verified byte range {start}-{end}: {url}")
    print(f"Downloading {destination.name}: {size / 1048576:.1f} MiB", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for done, _ in enumerate(pool.map(download, ranges), 1):
            if done % 8 == 0 or done == len(ranges):
                print(f"  {destination.name}: {done}/{len(ranges)} parts", flush=True)
    temp = destination.with_name(destination.name + ".tmp")
    with temp.open("wb") as out:
        for i, _, _ in ranges:
            with (parts / str(i)).open("rb") as src:
                shutil.copyfileobj(src, out)
    if not valid(temp):
        temp.unlink(missing_ok=True)
        shutil.rmtree(parts)
        raise RuntimeError(f"Checksum mismatch: {destination}")
    os.replace(temp, destination)
    shutil.rmtree(parts)

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("url"); p.add_argument("destination"); p.add_argument("size", type=int)
    p.add_argument("--sha256"); p.add_argument("--sha1"); p.add_argument("--workers", type=int, default=8)
    args = p.parse_args()
    fetch(args.url, args.destination, args.size, args.sha256 or args.sha1, "sha1" if args.sha1 else "sha256", args.workers)
