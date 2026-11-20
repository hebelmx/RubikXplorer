#!/usr/bin/env bash
# Story 12.2 AC3: runs every KeyFlowRotationTest method on one device or emulator, each from a cleared
# install, and keeps the results under docs/evidence/12-2/keyflow/<label>/.
#
#   tools/run-keyflow-rotation.sh <serial> [label] [method ...]
#
# Needs the debug app and its androidTest APK built first:
#   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Leaves the device as it found it: it installs this app's APKs and clears this app's data (pm clear)
# before each method. The test rotates the display through UI Automator (UiDevice.setOrientation*,
# which freezes the rotation; #119 item 1) and unfreezes it afterwards, which turns auto-rotate on, so
# this script records accelerometer_rotation and user_rotation first and puts them back on exit, also
# when a step fails. It records `wm get-ignore-orientation-request` and never changes it.
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

setting() { a shell settings get system "$1" | tr -d '\r'; }

rotation() {
  echo "accelerometer_rotation=$(setting accelerometer_rotation) user_rotation=$(setting user_rotation)"
}

# The rotation settings as found, put back on exit (best effort per key; "null" means unset).
saved_accelerometer="$(setting accelerometer_rotation)"
saved_user="$(setting user_rotation)"
restore_rotation() {
  local key value
  for key in accelerometer_rotation user_rotation; do
    if [ "$key" = accelerometer_rotation ]; then value="$saved_accelerometer"; else value="$saved_user"; fi
    if [ -z "$value" ] || [ "$value" = null ]; then
      a shell settings delete system "$key" > /dev/null 2>&1 || echo "warning: could not delete $key" >&2
    else
      a shell settings put system "$key" "$value" > /dev/null 2>&1 || echo "warning: could not restore $key=$value" >&2
    fi
  done
  if [ -f "$out/device.txt" ]; then
    echo "rotation after (restored): $(rotation)" >> "$out/device.txt" 2> /dev/null || true
  fi
  # Check the restore: an unset value ("" or "null") must read back as null.
  local want got failed=0
  for key in accelerometer_rotation user_rotation; do
    if [ "$key" = accelerometer_rotation ]; then want="$saved_accelerometer"; else want="$saved_user"; fi
    [ -z "$want" ] && want=null
    got="$(setting "$key" 2> /dev/null || true)"
    [ -z "$got" ] && got=null
    if [ "$got" != "$want" ]; then
      echo "error: $key is $got after restore, expected $want" >&2
      failed=1
    fi
  done
  if [ "$failed" -ne 0 ]; then exit 1; fi
}
trap restore_rotation EXIT

{
  echo "# Key Flow rotation run $(date -Is)"
  echo "serial=$serial label=$label git=$(git -C "$root" rev-parse HEAD)$(git -C "$root" diff --quiet HEAD -- app || echo ' (app has uncommitted changes)')"
  echo "model=$(a shell getprop ro.product.model | tr -d '\r') release=$(a shell getprop ro.build.version.release | tr -d '\r')" \
    "sdk=$(a shell getprop ro.build.version.sdk | tr -d '\r') emulator=$(a shell getprop ro.kernel.qemu | tr -d '\r')"
  echo "fingerprint=$(a shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "density=$(a shell wm density | tr -d '\r' | tr '\n' ' ') size=$(a shell wm size | tr -d '\r' | tr '\n' ' ')"
  echo "ignore-orientation-request=$(a shell wm get-ignore-orientation-request 2>&1 | tr -d '\r' | tr '\n' ' ')"
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
echo "rotation after run: $(rotation)" >> "$out/device.txt"
