#!/usr/bin/env bash
# Story 7.8 release gate (AD-14): the instrumentation, UI and renderer-sync suites on the CI emulator.
# KeyFlowRotationTest needs a cleared install per method (Story 12.2), so it runs through
# tools/run-keyflow-rotation.sh; every other androidTest class runs through Gradle in one pass.
set -euo pipefail
serial="${1:-emulator-5554}"
label=ci
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
# tools/run-keyflow-rotation.sh defaults to the local SDK path; hosted runners keep the SDK under ANDROID_HOME.
export ADB="${ADB:-${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}}"
export ADB="${ADB:-$(command -v adb)}"

ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=dev.cubentia.app.compat.KeyFlowRotationTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash tools/run-keyflow-rotation.sh "$serial" "$label"

summary="docs/evidence/12-2/keyflow/$label/summary.txt"
methods="$(wc -l < "$summary")"
# The tool lists the methods by name; a @Test added to the class but not to the tool must fail here, not be skipped.
declared="$(grep -c '@Test' app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt)"
if [ "$methods" -ne "$declared" ]; then
  echo "::error::Key Flow rotation ran $methods methods but KeyFlowRotationTest declares $declared" >&2
  exit 1
fi
if [ "$methods" -eq 0 ] || grep -qv ' pass$' "$summary"; then
  echo "::error::Key Flow rotation suite failed:" >&2
  cat "$summary" >&2
  exit 1
fi
echo "Key Flow rotation suite: $methods methods passed"
