#!/usr/bin/env bash
# Evidence helper for the physical-cube device session (session C / OQ-8).
#   tools/cube-session.sh cell <lenovo|s24> <cube> <light> <feature>   log one matrix cell (+ screenshots)
#   tools/cube-session.sh shot <lenovo|s24> <label>                    one screenshot
#   tools/cube-session.sh recorder <lenovo|s24>                        open the fixture recorder
#   tools/cube-session.sh pull <lenovo|s24>                            pull recorder fixtures into Fixtures/
#                                                                      (continuous sessions into build/continuous-sessions/ to curate)
#   tools/cube-session.sh session <lenovo|s24> <start|stop|save|discard|baseline> [label]
#                                                                      drive the recorder's Continuous mode (Story 10.34)
#   tools/cube-session.sh deny-camera <lenovo|s24>                     revoke the camera permission (Placement denial path)
# cube: stla stlb stka stkb   light: daylight warm cool low
# feature: guided quickscan quickcheck recognize placement
set -euo pipefail
PKG=com.exxerpro.cubentia
ROOT=$(git rev-parse --show-toplevel)
SHOTS="$ROOT/docs/evidence/session-c/shots"

serial() {
  local model
  case "$1" in
    lenovo) model=Lenovo_TB128XU ;;
    s24) model=SM_S721U1 ;;
    *) echo "device must be lenovo or s24" >&2; exit 2 ;;
  esac
  local s
  s=$(adb devices -l | awk -v m="model:$model" '$2=="device" && index($0,m){print $1; exit}')
  [ -n "$s" ] || { echo "$1 ($model) is not connected (adb devices -l)" >&2; exit 3; }
  echo "$s"
}
token() { case "$1" in lenovo) echo lenovo-tb128xu ;; s24) echo sm-s721u1 ;; esac; }
shot() { # serial file
  mkdir -p "$(dirname "$2")"
  adb -s "$1" exec-out screencap -p > "$2"
  echo "  screenshot -> ${2#$ROOT/}"
}

cmd=${1:-}; shift || true
case "$cmd" in
  cell)
    [ $# -eq 4 ] || { sed -n '2,12p' "$0"; exit 2; }
    S=$(serial "$1"); CELL="$(token "$1")_$2_$3_$4"
    case "$3" in daylight|warm|cool|low) ;; *) echo "bad light: $3" >&2; exit 2 ;; esac
    case "$4" in guided|quickscan|quickcheck|recognize|placement) ;; *) echo "bad feature: $4" >&2; exit 2 ;; esac
    LOG="$ROOT/Fixtures/matrix/$CELL.log"; mkdir -p "$(dirname "$LOG")"
    [ -f "$LOG" ] && echo "note: $CELL.log exists; new runs are APPENDED (number truth rows after the existing runs)"
    adb -s "$S" logcat -c
    ( adb -s "$S" logcat -v time -s CubentiaScanDiag:* | tee -a "$LOG" | grep --line-buffered -E ' run ' | sed -u 's/^/  RUN> /' ) &
    PID=$!
    trap 'kill $PID 2>/dev/null || true; pkill -P $PID 2>/dev/null || true' EXIT
    echo "Logging $CELL  (Enter = screenshot, q + Enter = finish cell)"
    n=0
    while read -r k; do
      [ "$k" = q ] && break
      n=$((n+1)); shot "$S" "$SHOTS/$CELL-$(printf %02d $n).png"
    done
    sleep 1
    echo "Done: $(grep -c ' run ' "$LOG" || true) logged run(s) in Fixtures/matrix/$CELL.log"
    ;;
  shot)
    [ $# -eq 2 ] || { echo "usage: shot <lenovo|s24> <label>" >&2; exit 2; }
    S=$(serial "$1"); shot "$S" "$SHOTS/$(token "$1")_$2_$(date +%H%M%S).png" ;;
  recorder)
    S=$(serial "$1"); adb -s "$S" shell am start -n "$PKG/dev.cubentia.app.camera.recorder.FixtureRecorderActivity" ;;
  pull)
    S=$(serial "$1")
    for d in live corner; do
      adb -s "$S" shell ls "/sdcard/Android/data/$PKG/files/Fixtures/$d" >/dev/null 2>&1 \
        && adb -s "$S" pull "/sdcard/Android/data/$PKG/files/Fixtures/$d" "$ROOT/Fixtures/" || echo "  (no $d fixtures on $1)"
    done
    # Story 10.34: continuous sessions are curated by hand into Fixtures/continuous/ (see Fixtures/continuous/README.md).
    INBOX="$ROOT/build/continuous-sessions/$(token "$1")"; mkdir -p "$INBOX"
    adb -s "$S" shell ls "/sdcard/Android/data/$PKG/files/Fixtures/continuous" >/dev/null 2>&1 \
      && adb -s "$S" pull "/sdcard/Android/data/$PKG/files/Fixtures/continuous/." "$INBOX/" \
      && echo "  continuous sessions -> ${INBOX#$ROOT/} (curate into Fixtures/continuous/)" || echo "  (no continuous sessions on $1)" ;;
  session)
    [ $# -ge 2 ] || { echo "usage: session <lenovo|s24> <start|stop|save|discard|baseline> [label]" >&2; exit 2; }
    case "$2" in start|stop|save|discard|baseline) ;; *) echo "bad session command: $2" >&2; exit 2 ;; esac
    if [ $# -ge 3 ]; then
      # The label passes through the device shell unquoted: letters, digits and '-' only.
      [[ "$3" =~ ^[A-Za-z0-9-]+$ ]] || { echo "bad label: $3 (use letters, digits and -)" >&2; exit 2; }
    fi
    S=$(serial "$1")
    if [ $# -ge 3 ]; then
      adb -s "$S" shell am broadcast -p "$PKG" -a dev.cubentia.debug.RECORDER_SESSION --es command "$2" --es label "$3"
    else
      adb -s "$S" shell am broadcast -p "$PKG" -a dev.cubentia.debug.RECORDER_SESSION --es command "$2"
    fi ;;
  deny-camera)
    S=$(serial "$1"); adb -s "$S" shell pm revoke "$PKG" android.permission.CAMERA && echo "camera permission revoked on $1" ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
