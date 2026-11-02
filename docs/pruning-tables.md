# Pruning table measurement (Story 3.2)

The production bundled-versus-first-run choice is **pending** minimum-reference-device measurements.
The generator and optional private-file cache are infrastructure, called only by the debug measurement
activity until that choice is recorded. App startup and Lessons do not call or await this infrastructure.
No third-party solver implementation is used.

The generator stores four breadth-first distance projections: twist/slice position, flip/slice position,
corner permutation/slice permutation, and edge permutation/slice permutation. Their maxima provide
admissible phase heuristics. Transitions reuse the clean-room Story 3.1 move tables.

## Run on the minimum reference device

Use the **Samsung Galaxy Tab S7 FE 5G**, not a host JVM timing or an emulator, for acceptance.
The Android StrictMode APIs used by the harness were checked against the
[official ThreadPolicy.Builder reference](https://developer.android.com/reference/android/os/StrictMode.ThreadPolicy.Builder).

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:installDebug
adb devices -l
adb logcat -c
adb shell am force-stop dev.rubikxplorer
adb shell am start -W -n dev.rubikxplorer/dev.rubikxplorer.app.solver.PruningTablesBenchmarkActivity --ez cold true
adb logcat -s PruningTablesBenchmark:I StrictMode:D AndroidRuntime:E
```

Wait for `cacheBytes=...,mainThreadDiskViolations=...`. Save the whole report with device model,
Android version, build revision, thermal/battery state and repeated-run timings. `first` must report
`GENERATED`; `warm` must report `CACHE`; any `failed` or `measurement-incomplete` means the run is
not valid. The `cold` extra deletes only the benchmark cache on an I/O dispatcher. Restarting the
process discards lazy move tables, so cold timing includes their initialization. The second provider
instance measures a real disk-cache read, not reuse of the first provider's in-memory result.

The policy detects main-thread disk reads and writes while the measurement Activity is active and
logs each violation; require zero and inspect StrictMode logcat too. This does not prove a future
production integration is safe: repeat the check when the selected delivery mode is wired. Review
that Lessons still run independently of table readiness; benchmark generation is opt-in and no
Lesson or app-startup code calls it.

## Make the delivery decision

Measure installed size using a release-equivalent build and record the method and breakdown. APK
file size alone is insufficient: NFR-11 targets install size below 30 MB, including tables and the
recognition model. Capture the unbundled baseline, cache bytes, and a temporary bundled candidate's
installed delta (including native/compiled code where applicable). The current debug harness is not
an installed release-size measurement.

- Bundling adds distribution/install bytes but can remove cold generation delay. Measure its actual
  compressed APK and installed delta before selecting it; tables still need off-main access.
- First-run generation avoids a table asset in the APK but adds measured CPU time, working memory
  and private cache bytes. The optional provider supports it; enabling it remains pending evidence.

Record generation duration, payload/cache bytes, installed-size totals and the chosen mode in the
architecture memlog with `uv run _bmad/scripts/memlog.py append` (see its `--help` for arguments).
Do not hand-edit the memlog. Do not select a delivery mode solely from host numbers.

## Evidence available in this run

`adb devices -l` on 2026-11-02 listed no devices. No minimum-device build time, installed size,
physical StrictMode result, or production delivery decision is claimed. These are a human
before-merge acceptance gate. JVM tests validate distance tables and cache behavior; CI-equivalent
Gradle gates validate the reversible implementation separately.

The deterministic table payload is **4,031,685 bytes**; the versioned cache adds 40 bytes for its
header and checksum. This is a generated format-size fact, not an installed-size measurement.
Host test timing (~309 ms in an initial run) may reuse initialized move tables and is neither cold
startup timing nor the reference-device result. Measure cold process runs as described above.
