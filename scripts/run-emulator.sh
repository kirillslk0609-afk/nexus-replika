#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="$PROJECT_ROOT/.tools/android-sdk"
export ANDROID_AVD_HOME="$PROJECT_ROOT/.tools/avd"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="emulator-5566"
AVD_NAME="NeuroScribe_API_35"
mkdir -p "$ANDROID_AVD_HOME"

if [ ! -x "$ANDROID_HOME/emulator/emulator" ]; then
  echo "Android Emulator is not installed in $ANDROID_HOME/emulator" >&2
  exit 1
fi
if [ ! -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]; then
  echo "Create the NeuroScribe_API_35 virtual device before running this script." >&2
  exit 1
fi

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  python3 - "$ANDROID_HOME/emulator/emulator" "$AVD_NAME" "$PROJECT_ROOT/.tools/emulator.log" <<'PY'
import subprocess, sys
with open(sys.argv[3], "ab", buffering=0) as log:
    subprocess.Popen([sys.argv[1], "-avd", sys.argv[2], "-port", "5566",
        "-no-snapshot", "-no-boot-anim", "-memory", "4096", "-cores", "4",
        "-gpu", "auto", "-camera-back", "none", "-camera-front", "none"],
        stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
PY
fi

for attempt in $(seq 1 180); do
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then break; fi
  if [ "$attempt" = 180 ]; then echo "Emulator did not finish booting; see .tools/emulator.log" >&2; exit 1; fi
  sleep 2
done
ACTUAL_AVD="$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')"
if [ "$ACTUAL_AVD" != "$AVD_NAME" ]; then
  echo "Port 5566 belongs to a different virtual device: $ACTUAL_AVD" >&2
  exit 1
fi

if ! "$ADB" -s "$SERIAL" shell pm path ru.neuroscribe.app | grep -q '^package:'; then
  "$ADB" -s "$SERIAL" install "$PROJECT_ROOT/artifacts/neuroscribe-debug.apk"
fi
"$ADB" -s "$SERIAL" push "$PROJECT_ROOT/samples/russian.wav" /sdcard/Download/neuroscribe-test-russian.wav
"$ADB" -s "$SERIAL" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/neuroscribe-test-russian.wav >/dev/null
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
"$ADB" -s "$SERIAL" shell am start -W -n ru.neuroscribe.app/.MainActivity
echo "NeuroScribe is open in the Android emulator."
