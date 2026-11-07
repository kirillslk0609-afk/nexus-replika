#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$PROJECT_ROOT"/.tools/zulu*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [ -d "$PROJECT_ROOT/.tools/android-sdk" ]; then
  export ANDROID_HOME="$PROJECT_ROOT/.tools/android-sdk"
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi

python3 - <<'PY'
import hashlib,json
from pathlib import Path
root=Path('app/src/main/assets/models')
manifest=json.loads((root/'manifest.json').read_text())
required={'encoder.onnx','decoder.onnx','tokens.txt','segmentation.onnx','embedding.onnx','punctuation.gguf'}
assert set(manifest)==required, 'Incomplete model manifest'
for name, expected in manifest.items():
 p=root/name
 assert p.stat().st_size==expected['bytes'], name
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 assert h.hexdigest()==expected['sha256'], name
print('All packaged models verified.')
PY

if [ -x "$PROJECT_ROOT/.tools/gradle-8.13/bin/gradle" ]; then
  GRADLE_BIN="$PROJECT_ROOT/.tools/gradle-8.13/bin/gradle"
elif [ -x ./gradlew ]; then
  GRADLE_BIN=./gradlew
else
  GRADLE_BIN=gradle
fi
"$GRADLE_BIN" --console=plain testDebugUnitTest lintDebug assembleDebug
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/neuroscribe-debug.apk
shasum -a 256 artifacts/neuroscribe-debug.apk > artifacts/SHA256SUMS
printf '\nAPK: %s/artifacts/neuroscribe-debug.apk\n' "$PROJECT_ROOT"
