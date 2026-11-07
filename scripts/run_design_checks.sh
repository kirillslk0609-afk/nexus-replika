#!/bin/bash
# Focused presentation checks plus real clean/noisy/two-speaker inference.
# Full engine regressions remain available in run_android_checks.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
DEVICE_SERIAL="${1:-emulator-5566}"
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.tools/android-sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$PWD"/.tools/zulu*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
ADB="$ANDROID_HOME/platform-tools/adb"
GRADLE_BIN="$PWD/.tools/gradle-8.13/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then GRADLE_BIN=./gradlew; fi
./scripts/build.sh
"$GRADLE_BIN" --console=plain assembleDebugAndroidTest
"$ADB" -s "$DEVICE_SERIAL" install -r artifacts/neuroscribe-debug.apk
"$ADB" -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s "$DEVICE_SERIAL" shell cmd connectivity airplane-mode enable
mkdir -p artifacts/design
"$ADB" -s "$DEVICE_SERIAL" shell am instrument -w -r \
  -e class 'ru.neuroscribe.app.PresentationTest,ru.neuroscribe.app.PipelineTest#twoSpeakerFixtureKeepsStableIdsAcrossFourTurns,ru.neuroscribe.app.QualityBenchmark#noisyRussianAndLongPauseKeepValidOutput' \
  ru.neuroscribe.app.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/design/android-instrumentation.txt
grep -q 'OK (5 tests)' artifacts/design/android-instrumentation.txt
python3 scripts/summarize_design_checks.py "$DEVICE_SERIAL"
