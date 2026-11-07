#!/usr/bin/env python3
"""Word error rate for exported transcripts. No third-party packages required."""
import argparse
import json
from pathlib import Path
import re

def words(text): return re.findall(r"[^\W_]+", text.lower(), flags=re.UNICODE)

def wer(reference, hypothesis):
    a, b = words(reference), words(hypothesis)
    if not a:
        return {"reference_words": 0, "errors": len(b), "wer": 0.0 if not b else None}
    previous = list(range(len(b) + 1))
    for i, expected in enumerate(a, 1):
        row = [i]
        for j, actual in enumerate(b, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j-1] + (expected != actual)))
        previous = row
    return {"reference_words": len(a), "errors": previous[-1], "wer": previous[-1] / len(a)}

def transcript_text(path):
    if path.suffix == ".json":
        content = json.loads(path.read_text())
        return " ".join(turn["text"] for turn in sorted(content["transcript"], key=lambda x: x["start_ms"]))
    return path.read_text()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("hypothesis", type=Path)
    parser.add_argument("--audio-seconds", type=float)
    parser.add_argument("--processing-seconds", type=float)
    args = parser.parse_args()
    report = wer(transcript_text(args.reference), transcript_text(args.hypothesis))
    if args.audio_seconds is not None and args.processing_seconds is not None:
        if args.audio_seconds <= 0 or args.processing_seconds < 0: parser.error("Invalid duration")
        report["rtf"] = args.processing_seconds / args.audio_seconds
    print(json.dumps(report, ensure_ascii=False, indent=2))
