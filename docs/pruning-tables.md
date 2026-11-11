# Pruning tables: infrastructure (Story 3.2) and production delivery (Story 3.8)

**Production delivery is bundled** (Story 3.8, decided 2026-11-10 from Galaxy Tab S7 FE 5G
measurements; decision record in the architecture memlog). The versioned, checksummed table file is
generated at build time by `:core:solver:writePruningTableAsset` and ships as the APK asset
`assets/solver/pruning-v1.bin`. On the first solve of a process, `PruningTableProvider` reads and
validates it on the I/O dispatcher and then builds the coordinate move tables on
`Dispatchers.Default` before the solver is ready. Only if the asset is unreadable or invalid does it
fall back to first-run generation into app-private, non-evictable `filesDir/solver/pruning-v1.bin`.
App startup and Lessons never call or await any of this. No third-party solver implementation is used.

The generator stores four breadth-first distance projections: twist/slice position, flip/slice position,
corner permutation/slice permutation, and edge permutation/slice permutation. Their maxima provide
admissible phase heuristics. Transitions reuse the clean-room Story 3.1 move tables.

## Approved acceptance boundary — 2026-11-02

[Story 3.2](../_bmad-output/planning-artifacts/epics.md#story-32-pruning-tables-cached-off-the-main-thread) owns tested generation/cache APIs and this reproducible harness. [Mandatory Story 3.8](../_bmad-output/planning-artifacts/epics.md#story-38-reference-device-pruning-table-delivery-decision-and-production-integration) owns the device measurements, installed-size evaluation, delivery-choice memlog append and production integration below. See the [approved proposal](../_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-02-device-validation-splits.md); NFR-3 physical solve-corpus confirmation remains Story 12.2.

## Story 3.8 evidence (2026-11-10)

Every number below was measured in this run on the **minimum reference device**. Raw logs are in
[`evidence/3-8/`](evidence/3-8/). No host, emulator or Galaxy S24 FE number is used for a criterion.

Revision mapping: all measurements were taken at the attempt-1 revisions cited below, which exist
only on the local branch `wip/3-8-attempt-1`. The same commits were cherry-picked without conflicts
onto the story branch:

| Attempt-1 revision (measured) | Cherry-picked on the story branch |
| --- | --- |
| `79e66110b5962eb68773bcad0abbdeb7d37b4f49` | `85985c4c607e5f43a88120ffce52141f3e48db1c` |
| `ed490da5736b84a95ae3ee225be93f69e247f52b` | `cdd911b75cd35e426b7a5bab4ae9fd43c284ab28` |
| `ef9c6df62712182af85ebba8bc64b9e513a44ddb` | `50bc3d7c9fe60061fcce0257e50159b0282f1b35` |
| `dd1d4ac26d74af8661356a93c5d0e0eb2ccfb6a4` | `3d3b75726940a206a269eabc70d18683091ac2e3` |
| `eeac7db6ff95c0fa198a05c52e39d2a7aa28f7cc` | `c2aed43000614f0a44dcc1aebf81a307db6b2d8c` |
| `03a69f9712ba44a9831db0a8e892ae9a42b23046` | `eb870e2b96fe80dc5e0f7a33c786733c2088e75f` |
| `dcf3b653254fabb04084f0c9e9744f9c9b2820ef` | `eba4cc13b9581e47f19dd91236c252305fcea273` |

| Identity | Value |
| --- | --- |
| Device | Samsung Galaxy Tab S7 FE 5G, SM-T738U, serial `R52T50ELHWK`, SoC SM7225, 8 CPUs, MemTotal 3,518,216 kB |
| Android | 12 (API 31), fingerprint `samsung/gts7xllitesqwnc/gts7xllite:12/SP1A.210812.016/T738USQU3BVG7:user/release-keys` |
| Conditions | USB-powered and charging, battery 82 → 76 %, battery temperature 32.9–34.4 °C, thermal status 0–1 (none/light), stay-awake on (`mStayOn=true`), screen interactive, power save off |
| Revisions | measurement tooling `79e66110b5962eb68773bcad0abbdeb7d37b4f49`; bundled candidate `ed490da5736b84a95ae3ee225be93f69e247f52b`; production checks `03a69f9712ba44a9831db0a8e892ae9a42b23046` (bundled timing `eeac7db6ff95c0fa198a05c52e39d2a7aa28f7cc`) |

### Cold generation and warm cache load (3.2 harness, debug build)

Five cold-process runs (`am force-stop`, then `am start -W … --ez cold true`) and five independent
warm runs (force-stop, start without `cold`, so each warm run is a new process reading the file).
Raw: [`pruning-tables-tab-s7-fe.log`](evidence/3-8/pruning-tables-tab-s7-fe.log), revision `79e6611`.

| Measurement (n = 5 each) | min | median | max |
| --- | --- | --- | --- |
| Generation (move tables + 4 BFS projections), `generationNanos` | 9,724.7 ms | 9,845.8 ms | 10,258.7 ms |
| Cache write (atomic, fsync), `cacheWriteNanos` | 159.1 ms | 174.3 ms | 242.3 ms |
| **G** = generation + write (first-run preparation) | 9,883.8 ms | **10,014.0 ms** | 10,432.9 ms |
| Warm private-file read + SHA-256 validation, new process, `cacheReadNanos` | 8.0 ms | 8.9 ms | 10.9 ms |

- Payload 4,031,685 bytes; cache file 4,031,725 bytes (40-byte header and checksum) on every run.
- Every cold run reported `first,source=GENERATED`; every warm run `first,source=CACHE`. No `failed` or
  `measurement-incomplete` line.
- Memory: `/proc/meminfo` MemAvailable before the run 448,656–572,172 kB (median 534,058 kB);
  `ActivityManager` availMem 814.7–915.1 MB, `lowMemory=false`. Process PSS was 31.7–71.2 MB before
  generation and 72.4–109.1 MB after it. Java heap in use after generation was 13.7–17.1 MiB, with a
  512 MiB heap limit.
- Harness StrictMode counter: 0 on 9 runs and 2 on cold run 1. Those 2 were
  `ActivityThread.getProfileSizeOfApp` (the platform's ART profile check on the main looper after a
  fresh install), not table code. Every process also logged Samsung `Typeface.setFlipFonts` /
  `getFullFlipFont` reads from `Application.onCreate` under the app-wide debug policy. Both come from
  platform code.

### Production (bundled) preparation in a fresh process

`--ez bundledOnly true` runs only the production path (asset read + validation, then move-table
build) in a new process, five runs. Raw: [`pruning-tables-bundled-tab-s7-fe.log`](evidence/3-8/pruning-tables-bundled-tab-s7-fe.log), revision `eeac7db`.

| Measurement (n = 5, debug build) | min | median | max |
| --- | --- | --- | --- |
| Asset read + inflate + SHA-256 validation | 23.7 ms | 24.4 ms | 26.1 ms |
| Move-table build (`moveTableNanos`) | 8,214.4 ms | 8,259.2 ms | 8,542.5 ms |
| Total preparation | 8,238.9 ms | 8,283.0 ms | 8,566.8 ms |

PSS after preparation 66.7–89.3 MB; Java heap in use 10.3–17.4 MiB.

**Finding: move tables dominate preparation.** About 8.3 s of the ~10 s "generation" is the
Story 3.1 move-table build, which any delivery mode must do once per process. Before this story a
loaded (cached) table set skipped it, and the first solve then built it inside `SolveCube`'s 3 s
timeout. On the device a one-move cube returned "Couldn't find a solution in time…" (see
[`production-check-attempt1-ef9c6df.log`](evidence/3-8/production-check-attempt1-ef9c6df.log)).
The provider now builds the move tables off-main as part of preparation (`dd1d4ac`).

Release-equivalent check, coarse: the `benchmark` build (R8, not debuggable, dexopt `verify`) showed
7.61, 7.78 and 7.63 s from tapping Accept to the Solution note in three fresh processes, polled by
`uiautomator` with about 1–2 s resolution, so these are upper bounds. Raw:
[`production-release-timing-tab-s7-fe.log`](evidence/3-8/production-release-timing-tab-s7-fe.log).

### Installed size (release-equivalent `benchmark` build type)

Method: `adb install` of each APK, `pm path`, `ls -l` of the code directory, and `ls -l` by name of
`oat/arm64/base.{odex,vdex,art}` (the `oat/` directory is not listable by shell, but its files can be
stat-ed). The install-time dexopt filter varied between installs of the same APK (`verify` or
`speed-profile` with `base.dm`), so both states are recorded. Limits: app data of a non-debuggable
build is not readable by shell, so app data was measured on the debug build with `run-as du`.
Background dexopt may recompile later; the largest observed state is used for the budget. Raw:
[`installed-size-tab-s7-fe.log`](evidence/3-8/installed-size-tab-s7-fe.log).

| Build | APK | dexopt `verify` total | dexopt `speed-profile` total |
| --- | --- | --- | --- |
| Unbundled baseline (`79e6611`) | 3,477,192 B | 3,590,108 B | 6,880,214 B |
| Bundled candidate (`ed490da`, `-Pcubentia.bundleTables=true`) | 4,399,962 B | 4,512,878 B | not observed |
| Production bundled (`ef9c6df`) | 4,399,962 B | 4,512,882 B | 7,802,989 B |

- **B**, the bundled installed delta over the baseline, is 922,770 B in the same `verify` state (922,775 B in
  `speed-profile`): the asset stays deflated in the APK (4,031,725 → 922,637 bytes) and adds no
  compiled code.
- First-run alternative: baseline 6,880,214 B plus the 4,031,725 B private table file =
  10,911,939 B, plus app data.
- App data after solves (debug build, `run-as du -k`): 150 KiB, of which 132 KiB is databases. The
  bundled mode writes no table file. `files/` holds only `profileInstalled`.
- Budget (NFR-11, 30 MB = 30,000,000 B): bundled 7,802,989 B + 153,600 B data = 7,956,589 B, which
  leaves **22,043,411 B (~22.0 MB) of headroom** for the v1.1 recognition model. First-run would
  leave 18,934,461 B.
- Fallback state (asset invalid, so tables are generated into the private file as well): 7,802,989 B
  bundled install + 4,031,725 B private file + 153,600 B data = 11,988,314 B, leaving 18,011,686 B
  (~18.0 MB) of headroom under 30,000,000 B.

### Decision

Selection rule (Story 3.8 Design Notes): first-run requires G ≤ 5 s. G = 10,014 ms fails, so the rule
selects bundled because its installed total, 7,956,589 B, is under 30 MB. Bundled also costs fewer
bytes than first-run (0.92 MB vs 4.03 MB) and removes ~1.7 s (generation BFS + write) from the
first solve. It does not remove the per-process move-table build. Recorded in the architecture memlog.

### Production StrictMode and Lesson independence (debug build, `5921e65` + `detectNetwork()`)

Scripted `adb` UI run with the app-wide debug StrictMode policy (`detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog()`, `DebugStrictMode` in `app/src/debug`; no-op in release/benchmark). Raw: [`production-strictmode-tab-s7-fe.log`](evidence/3-8/production-strictmode-tab-s7-fe.log).

1. Fresh install, cold process: opened a Lesson and turned U before any solve. The Lesson responded
   ("Pattern reached", "Objective met") with tables never requested.
2. Entered the cube after U in the Manual Cube Editor and accepted it. "Preparing the solver (one
   time, a few seconds)." appeared with Cancel.
3. During preparation: switched to Learn, reopened the Lesson and turned U. The Lesson responded.
   Leaving Solve cancelled the pending solve (3.7 behaviour), nothing was published, and the
   editor kept the colors.
4. Retried Accept: preparing line, then playback of `U'` with its note. A second solve in the same
   process went straight to playback with no preparing line.
5. New process (force-stop): accepted the U cube again. The preparing line appeared (the asset is
   re-read and move tables rebuilt per process), then playback of `U'`. No table file was written.

StrictMode result (re-run 2026-11-10 with network detection added): 6 violations in process 1 and
4 in process 2, all `DiskReadViolation`, none network, **0 attributable to table loading or
solving**, and no app frame performs disk access (the only app frames are `CubentiaApp.onCreate` →
`super.onCreate()`). The remaining violations, reported verbatim in the log: Samsung
`Typeface.setFlipFonts`/`getFullFlipFont` disk reads from `Application.onCreate` (each process) and
`ActivityThread.getProfileSizeOfApp` (first process after install). Both are platform code; whether
they may be waived against the epic AC ("no main-thread disk access is reported") is an open PO
question. An earlier
pass (`eeac7db`, [`production-check-attempt2-eeac7db.log`](evidence/3-8/production-check-attempt2-eeac7db.log))
found 2 real violations: `Context.getFilesDir()` called from `SolverModule.provider` on the main
thread. These were fixed in `03a69f9` by building the path from `applicationInfo.dataDir`.

### Known issues (v1.1)

Both items were accepted by the PO for v1.0 on 2026-11-10 and are deferred to v1.1. Neither is fixed
in Story 3.8.

- **Per-process preparation vs Learner copy (#104).** Preparation repeats in every new app process
  (asset read + move-table build). It took about 8.3 s on the debug build and 7.6–7.8 s (coarse UI polling, upper bounds) on the
  release-equivalent build (coarse timing) on the minimum device. The copy "Preparing the solver (one
  time, a few seconds)." (`msg.solve.preparing`) therefore overstates how often and how quickly it
  happens. The PO accepted this for v1.0. The copy is unchanged, and no move-table delivery work was
  added. #104 tracks the options: ship the move tables, build them faster, or prepare when the Solve
  tab opens (never at startup or for Lessons).
- **Corpus failures and calibration (#105).** The direct device corpus had 1 timeout (3,000.9 ms of
  10,101), and the calibrated profile (k = 16.94) has 1 failure (3,303.6 ms scaled). See
  [benchmarks.md](benchmarks.md#story-38-calibrated-reference-device-equivalent-profile-2026-11-10).
  The zero-failure deviation and the recalibration of k against a release-equivalent device build are
  waived by the PO for v1.0 and deferred to #105.

### Open measurements

None of the Story 3.8 acceptance measurements is missing. The limits are recorded where they apply:
installed size of a non-debuggable build's app data is taken from the debug build, release-equivalent
preparation timing is coarse (UI polling), and the `filesDir` fallback was not exercised on the device.

### Verification

`./gradlew spotlessCheck build` on the final Story 3.8 revision: BUILD SUCCESSFUL, see
[`build-verification.log`](evidence/3-8/build-verification.log).

## Reproduce

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
# adb path and serial as separate words: works in bash and zsh (zsh does not word-split "$ADB")
ADB=$ANDROID_HOME/platform-tools/adb
SERIAL=R52T50ELHWK
ANDROID_SERIAL=$SERIAL ./gradlew :app:installDebug
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am force-stop com.exxerpro.cubentia
# cold generation + warm read in the same process; omit --ez cold for a warm (cache) process;
# --ez bundledOnly true times only the production bundled path
"$ADB" -s "$SERIAL" shell am start -W -n com.exxerpro.cubentia/dev.cubentia.app.solver.PruningTablesBenchmarkActivity --ez cold true
# the harness runs asynchronously after am start returns: wait for its final report line
until "$ADB" -s "$SERIAL" logcat -d -s PruningTablesBenchmark:I | grep -qE 'cacheBytes=|bundledOnly,|failed|measurement-incomplete'; do sleep 2; done
"$ADB" -s "$SERIAL" logcat -d -s PruningTablesBenchmark:I StrictMode:D AndroidRuntime:E
```

Wait for `cacheBytes=…,mainThreadDiskViolations=…` (or `bundledOnly,…`). The harness logs
device/Android identity, fingerprint, charging/battery/thermal conditions, `ActivityManager` and
`/proc/meminfo` available memory, process PSS and heaps. `first` must be `GENERATED` after `cold`,
`warm` must be `CACHE`, and `bundled` must be `BUNDLED`. Any `failed` or `measurement-incomplete`
line makes the run invalid. Installed size: `./gradlew :app:assembleBenchmark` (production, bundled).
The unbundled baseline is the revision before `ef9c6df`.
