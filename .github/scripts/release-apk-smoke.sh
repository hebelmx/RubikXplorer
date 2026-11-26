#!/usr/bin/env bash
# Release-APK install-and-launch smoke (epic-7-retro-item-68; closes epic-2-retro-item-6).
# The release workflow runs instrumentation on the debug build; this proves the signed, R8-shrunk
# release artifact installs, launches and stays up before it is uploaded to Play.
#
# Usage: release-apk-smoke.sh <universal.apk | release.aab>
#   An .apk is installed as given (CI passes the universal APK AGP builds from the signed AAB).
#   An .aab is turned into a universal APK with a pinned, checksum-verified bundletool and signed with
#   KEYSTORE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (passwords go to bundletool through 0600 files).
# Env: ANDROID_SERIAL   target device/emulator (required when more than one is attached)
#      PACKAGE          applicationId (default com.exxerpro.cubentia)
#      HOLD_SECONDS     how long the launched app must stay up (default 10)
#      RESUME_TIMEOUT   seconds to wait for the launcher activity to resume (default 60)
#      WELCOME_TEXT     text the first screen must show (default "Welcome to Cubentia"; empty skips it)
#      LOG_DIR          where logcat is saved on failure (default: a temp dir, printed)
#      BUNDLETOOL_JAR   use this jar instead of downloading one; its checksum is still verified
#      ADB              adb binary (default: \$ANDROID_HOME/platform-tools/adb, else adb on PATH)
# The package must not be installed already: the smoke uninstalls it at the end and must never wipe a
# real install's data. Exit: 0 pass, 1 smoke failed, 2 bad usage or environment.
set -euo pipefail

BUNDLETOOL_VERSION=1.18.3
BUNDLETOOL_SHA256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29

artifact="${1:-}"
pkg="${PACKAGE:-com.exxerpro.cubentia}"
hold="${HOLD_SECONDS:-10}"
resume_timeout="${RESUME_TIMEOUT:-60}"
welcome="${WELCOME_TEXT-Welcome to Cubentia}"
adb="${ADB:-${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}}"
adb="${adb:-$(command -v adb || true)}"

usage() {
  echo "::error::$*" >&2
  exit 2
}
[[ -n "$artifact" && -f "$artifact" ]] || usage "usage: release-apk-smoke.sh <universal.apk|release.aab> (got '$artifact')"
[[ -n "$adb" && -x "$adb" ]] || usage "adb not found; set ADB or ANDROID_HOME"

work="$(mktemp -d)"
log_dir="${LOG_DIR:-$work/logs}"
mkdir -p "$log_dir"
installed=0
passed=0

device() { "$adb" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }

finish() {
  local code=$?
  if [[ $passed -ne 1 ]]; then
    device logcat -d -v threadtime > "$log_dir/logcat.txt" 2>&1 || true
    echo "--- logcat lines for $pkg, crashes and activity manager (full log: $log_dir/logcat.txt) ---" >&2
    grep -E "$pkg|AndroidRuntime|ActivityManager|ActivityTaskManager|DEBUG  |libc  " "$log_dir/logcat.txt" | tail -n 150 >&2 || true
  fi
  if [[ $installed -eq 1 ]]; then
    device uninstall "$pkg" > /dev/null 2>&1 || echo "::warning::could not uninstall $pkg" >&2
  fi
  # Keep the logs when they live in the temp dir and the smoke failed.
  if [[ $passed -eq 1 || -n "${LOG_DIR:-}" ]]; then rm -rf "$work"; else find "$work" -mindepth 1 -maxdepth 1 ! -name logs -exec rm -rf {} +; fi
  exit "$code"
}
trap finish EXIT

fail() {
  echo "::error::release smoke failed: $*" >&2
  exit 1
}

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

universal_apk() {
  case "$artifact" in
    *.apk)
      echo "$artifact"
      ;;
    *.aab)
      local var
      for var in KEYSTORE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
        [[ -n "${!var:-}" ]] || usage "an .aab needs $var to sign the universal APK"
      done
      local jar="${BUNDLETOOL_JAR:-$work/bundletool-all-$BUNDLETOOL_VERSION.jar}"
      if [[ -z "${BUNDLETOOL_JAR:-}" ]]; then
        curl -fsSL --retry 3 -o "$jar" \
          "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar" >&2
      fi
      [[ "$(sha256_of "$jar")" == "$BUNDLETOOL_SHA256" ]] || usage "bundletool $jar does not match the pinned SHA-256 for $BUNDLETOOL_VERSION"
      (
        umask 077
        printf '%s' "$KEYSTORE_PASSWORD" > "$work/ks.pass"
        printf '%s' "$KEY_PASSWORD" > "$work/key.pass"
      )
      java -jar "$jar" build-apks --mode=universal --bundle="$artifact" --output="$work/universal.apks" \
        --ks="$KEYSTORE" --ks-pass="file:$work/ks.pass" --ks-key-alias="$KEY_ALIAS" --key-pass="file:$work/key.pass" >&2
      rm -f "$work/ks.pass" "$work/key.pass"
      unzip -p "$work/universal.apks" universal.apk > "$work/universal.apk"
      echo "$work/universal.apk"
      ;;
    *) usage "expected an .apk or .aab, got $artifact" ;;
  esac
}

resumed_line() { device shell dumpsys activity activities 2> /dev/null | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | grep -F " $pkg/" | head -n 1 || true; }
app_pid() { device shell pidof "$pkg" 2> /dev/null | tr -d '\r' || true; }

apk="$(universal_apk)"
echo "Release smoke: $apk on ${ANDROID_SERIAL:-the only attached device} ($(device shell getprop ro.product.model | tr -d '\r'), API $(device shell getprop ro.build.version.sdk | tr -d '\r'))"

if device shell pm path "$pkg" 2> /dev/null | grep -q '^package:'; then
  usage "$pkg is already installed on the target; refusing to replace and uninstall it (uninstall it first)"
fi

device install "$apk" > /dev/null
installed=1
component="$(device shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r' | tail -n 1)"
[[ "$component" == "$pkg/"* ]] || fail "no launcher activity resolves for $pkg (got '$component')"
echo "Launcher activity: $component"

device logcat -c || true
device shell am start -W -n "$component" > "$log_dir/am-start.txt" 2>&1 || true

deadline=$((SECONDS + resume_timeout))
until [[ -n "$(resumed_line)" ]]; do
  ((SECONDS < deadline)) || fail "$component was not resumed within ${resume_timeout}s"
  sleep 1
done
pid="$(app_pid)"
[[ -n "$pid" ]] || fail "$pkg is resumed but has no process"
echo "Resumed: $(resumed_line | sed 's/^ *//') (pid $pid)"

if [[ -n "$welcome" ]]; then
  seen=0
  for _ in 1 2 3 4 5; do
    # Some devices SIGTERM uiautomator after it writes the dump (exit 143), so judge the file, not the exit code.
    device shell uiautomator dump /sdcard/release-smoke.xml > /dev/null 2>&1 || true
    if device shell cat /sdcard/release-smoke.xml 2> /dev/null | grep -qF "text=\"$welcome\""; then
      seen=1
      break
    fi
    sleep 2
  done
  device shell rm -f /sdcard/release-smoke.xml > /dev/null 2>&1 || true
  [[ $seen -eq 1 ]] || fail "the first screen never showed '$welcome'"
  echo "First screen shows: $welcome"
fi

sleep "$hold"

crash_pattern="AndroidRuntime.*Process: ${pkg//./\\.}, PID|ANR in ${pkg//./\\.}([^.[:alnum:]_]|$)|>>> ${pkg//./\\.} <<<"
if device logcat -d -b main -b system -b crash -v brief | grep -E "$crash_pattern" > "$log_dir/crash.txt"; then
  cat "$log_dir/crash.txt" >&2
  fail "logcat shows a FATAL EXCEPTION, native crash or ANR for $pkg"
fi
now="$(app_pid)"
[[ "$now" == "$pid" ]] || fail "$pkg process $pid died during the ${hold}s hold (now '${now:-none}')"
[[ -n "$(resumed_line)" ]] || fail "$component is no longer resumed after the ${hold}s hold"

passed=1
echo "Release smoke passed: $pkg launched $component, stayed resumed for ${hold}s (pid $pid), no crash or ANR in logcat"
