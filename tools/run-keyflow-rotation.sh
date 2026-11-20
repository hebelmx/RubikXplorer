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
#
# Story 12.3 AC2 (NFR-5): OFFLINE=1 runs every method with the network off. The script records
# `settings get global airplane_mode_on`, turns airplane mode on (`cmd connectivity airplane-mode
# enable`), waits until it reads 1, and passes `-e offline true` so each flow's harness asserts the
# device is offline before it starts. On exit (pass or fail) it puts airplane mode back as found and
# checks it. Without OFFLINE=1 the script never touches airplane mode. Use an emulator: adb over Wi-Fi
# to a physical device drops when the radios go off.
# EVIDENCE_DIR overrides the evidence root (default docs/evidence/12-2/keyflow; relative to the repo):
#   OFFLINE=1 EVIDENCE_DIR=docs/evidence/12-3/offline tools/run-keyflow-rotation.sh emulator-5554 avd-GalaxyS24FE_API36
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
evidence="${EVIDENCE_DIR:-docs/evidence/12-2/keyflow}"
case "$evidence" in
  /*) ;;
  *) evidence="$root/$evidence" ;;
esac
out="$evidence/$label"
offline="${OFFLINE:-0}"
case "$offline" in
  0 | 1) ;;
  *) echo "error: OFFLINE must be 0 or 1, got '$offline'" >&2; exit 2 ;;
esac
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
  return "$failed"
}

airplane() { a shell settings get global airplane_mode_on | tr -d '\r'; }

# Airplane mode as found (OFFLINE=1 only), put back on exit and checked.
saved_airplane=""
if [ "$offline" = 1 ]; then
  saved_airplane="$(airplane)"
  [ -z "$saved_airplane" ] && saved_airplane=null
fi
restore_airplane() {
  [ "$offline" = 1 ] || return 0
  local want=0 got
  [ "$saved_airplane" = 1 ] && want=1
  if [ "$want" = 1 ]; then
    a shell cmd connectivity airplane-mode enable > /dev/null 2>&1 || echo "warning: could not enable airplane mode" >&2
  else
    a shell cmd connectivity airplane-mode disable > /dev/null 2>&1 || echo "warning: could not disable airplane mode" >&2
  fi
  got="$(airplane 2> /dev/null || true)"
  if [ -f "$out/device.txt" ]; then
    echo "airplane after (restored): airplane_mode_on=$got (found $saved_airplane)" >> "$out/device.txt" 2> /dev/null || true
  fi
  # An empty read means adb failed: the restore is unverified, so it is an error.
  if [ -z "$got" ]; then
    echo "error: could not read airplane_mode_on after restore (expected $want, found $saved_airplane)" >&2
    return 1
  fi
  # An unset value ("null") means off.
  [ "$got" = null ] && got=0
  if [ "$got" != "$want" ]; then
    echo "error: airplane_mode_on is $got after restore, expected $want (found $saved_airplane)" >&2
    return 1
  fi
}

restore_device() {
  local status=$? failed=0
  restore_airplane || failed=1
  restore_rotation || failed=1
  if [ "$failed" -ne 0 ]; then exit 1; fi
  exit "$status"
}
trap restore_device EXIT

{
  echo "# Key Flow rotation run $(date -Is)"
  echo "serial=$serial label=$label git=$(git -C "$root" rev-parse HEAD)$(git -C "$root" diff --quiet HEAD -- app || echo ' (app has uncommitted changes)')"
  echo "model=$(a shell getprop ro.product.model | tr -d '\r') release=$(a shell getprop ro.build.version.release | tr -d '\r')" \
    "sdk=$(a shell getprop ro.build.version.sdk | tr -d '\r') emulator=$(a shell getprop ro.kernel.qemu | tr -d '\r')"
  echo "fingerprint=$(a shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "density=$(a shell wm density | tr -d '\r' | tr '\n' ' ') size=$(a shell wm size | tr -d '\r' | tr '\n' ' ')"
  echo "ignore-orientation-request=$(a shell wm get-ignore-orientation-request 2>&1 | tr -d '\r' | tr '\n' ' ')"
  echo "rotation before: $(rotation)"
  echo "offline=$offline"
  if [ "$offline" = 1 ]; then echo "airplane before: airplane_mode_on=$saved_airplane"; fi
} > "$out/device.txt"

instrument_args=()
if [ "$offline" = 1 ]; then
  a shell cmd connectivity airplane-mode enable > /dev/null
  for _ in $(seq 1 30); do
    [ "$(airplane)" = 1 ] && break
    sleep 1
  done
  if [ "$(airplane)" != 1 ]; then
    echo "error: airplane mode did not turn on (airplane_mode_on=$(airplane))" >&2
    exit 1
  fi
  # The radios go down after the setting flips: wait for no default network too (best effort; the
  # harness fails a flow that still sees one).
  for _ in $(seq 1 30); do
    a shell dumpsys connectivity | tr -d '\r' | grep -q 'Active default network: none' && break
    sleep 1
  done
  echo "airplane during: airplane_mode_on=$(airplane)" >> "$out/device.txt"
  instrument_args=(-e offline true)
fi

a install -r -t "$root/app/build/outputs/apk/debug/app-debug.apk" > /dev/null
a install -r -t "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" > /dev/null

summary="$out/summary.txt"
: > "$summary"
for method in "${methods[@]}"; do
  a shell pm clear "$pkg" > /dev/null
  # A headless emulator's screen can be asleep (after boot or between methods), which fails the method (best effort).
  a shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  a shell wm dismiss-keyguard > /dev/null 2>&1 || true
  a logcat -c
  set +e
  a shell am instrument -w -r ${instrument_args[@]+"${instrument_args[@]}"} -e class "$class#$method" "$runner" > "$out/$method.instrument.txt" 2>&1
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
if [ "$offline" = 1 ]; then echo "airplane after run: airplane_mode_on=$(airplane)" >> "$out/device.txt"; fi
