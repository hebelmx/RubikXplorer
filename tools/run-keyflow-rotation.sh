#!/usr/bin/env bash
# Story 12.2 AC3: runs every KeyFlowRotationTest method on one device or emulator, each from a cleared
# install, and keeps the results under docs/evidence/12-2/keyflow/<label>/.
#
#   tools/run-keyflow-rotation.sh <serial> [label] [method ...]
#
# Needs the debug app and its androidTest APK built first:
#   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Touches no device setting: it installs this app's APKs, clears this app's data (pm clear) before
# each method, and the test rotates only through Activity.requestedOrientation, restored to
# SCREEN_ORIENTATION_UNSPECIFIED afterwards. The rotation settings are read before and after as a check.
set -euo pipefail

serial="${1:?usage: run-keyflow-rotation.sh <serial> [label] [method ...]}"
label="${2:-$serial}"
shift $(( $# >= 2 ? 2 : 1 ))
methods=("$@")
if [ ${#methods[@]} -eq 0 ]; then
  methods=(flow1FirstSolvePhysicalCube flow2TwoLookLastLayer flow3JustSolveIt flow6FiveMinuteEvaluation
    flow7PracticeToMastery flow8SkipAhead flow9ResetProgress)
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
pkg=com.exxerpro.cubentia
class=dev.cubentia.app.compat.KeyFlowRotationTest
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
out="$root/docs/evidence/12-2/keyflow/$label"
mkdir -p "$out"

a() { "$adb" -s "$serial" "$@"; }

rotation() {
  echo "accelerometer_rotation=$(a shell settings get system accelerometer_rotation | tr -d '\r')" \
    "user_rotation=$(a shell settings get system user_rotation | tr -d '\r')"
}

{
  echo "# Key Flow rotation run $(date -Is)"
  echo "serial=$serial label=$label git=$(git -C "$root" rev-parse HEAD)$(git -C "$root" diff --quiet HEAD -- app || echo ' (app has uncommitted changes)')"
  echo "model=$(a shell getprop ro.product.model | tr -d '\r') release=$(a shell getprop ro.build.version.release | tr -d '\r')" \
    "sdk=$(a shell getprop ro.build.version.sdk | tr -d '\r') emulator=$(a shell getprop ro.kernel.qemu | tr -d '\r')"
  echo "fingerprint=$(a shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "density=$(a shell wm density | tr -d '\r' | tr '\n' ' ') size=$(a shell wm size | tr -d '\r' | tr '\n' ' ')"
  echo "rotation before: $(rotation)"
} > "$out/device.txt"

a install -r -t "$root/app/build/outputs/apk/debug/app-debug.apk" > /dev/null
a install -r -t "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" > /dev/null

summary="$out/summary.txt"
: > "$summary"
for method in "${methods[@]}"; do
  a shell pm clear "$pkg" > /dev/null
  a logcat -c
  set +e
  a shell am instrument -w -r -e class "$class#$method" "$runner" > "$out/$method.instrument.txt" 2>&1
  set -e
  a logcat -d -s KeyFlowRotation:I TestRunner:* AndroidRuntime:E > "$out/$method.logcat.txt" 2>&1 || true
  if grep -q "^OK (1 test)" "$out/$method.instrument.txt"; then
    result=pass
  else
    result=fail
  fi
  echo "$method $result" | tee -a "$summary"
done
echo "rotation after: $(rotation)" >> "$out/device.txt"
