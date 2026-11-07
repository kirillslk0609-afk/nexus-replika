#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
DEVICE_SERIAL="${1:-emulator-5566}"
export ANDROID_HOME="${ANDROID_HOME:-$PROJECT_ROOT/.tools/android-sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$PROJECT_ROOT"/.tools/zulu*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
ADB="$ANDROID_HOME/platform-tools/adb"
GRADLE_BIN="$PROJECT_ROOT/.tools/gradle-8.13/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then GRADLE_BIN=./gradlew; fi
"$ADB" -s "$DEVICE_SERIAL" get-state
./scripts/build.sh
"$GRADLE_BIN" --console=plain assembleDebugAndroidTest
"$ADB" -s "$DEVICE_SERIAL" install -r artifacts/neuroscribe-debug.apk
"$ADB" -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s "$DEVICE_SERIAL" shell cmd connectivity airplane-mode enable
"$ADB" -s "$DEVICE_SERIAL" shell am instrument -w -r \
  -e class ru.neuroscribe.app.DecoderRegressionTest,ru.neuroscribe.app.SpeedRegressionTest,ru.neuroscribe.app.PipelineSpeedTest,ru.neuroscribe.app.DiarizationRegressionTest,ru.neuroscribe.app.PipelineTest,ru.neuroscribe.app.QualityBenchmark,ru.neuroscribe.app.ModelPreparationTest,ru.neuroscribe.app.NoiseRegressionTest \
  ru.neuroscribe.app.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/android-instrumentation.txt
grep -Eq 'OK \([0-9]+ tests\)' artifacts/android-instrumentation.txt
python3 scripts/summarize_android_checks.py "$DEVICE_SERIAL"
python3 scripts/summarize_noise_checks.py
python3 scripts/summarize_speed_checks.py
echo 'Android checks passed. Reports: artifacts/test-results/'
