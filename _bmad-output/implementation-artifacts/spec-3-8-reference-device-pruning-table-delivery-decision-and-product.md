---
title: 'Reference-device pruning-table delivery decision and production integration'
type: 'feature'
created: '2026-11-10'
status: 'blocked'
baseline_revision: 00cfb5be46c35a6ea4d9ec3c6957541527988e0c
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/docs/pruning-tables.md'
  - '{project-root}/docs/benchmarks.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Solver's pruning tables reach production only through 3.7's interim path (`PruningTableProvider` with a file in `cacheDir`), chosen without evidence. No minimum-device generation/cache timings, installed-size comparison, delivery decision, physical StrictMode check or reference-device-equivalent corpus calibration exist, so Story 3.7 production acceptance and Epic 3/P0.S closure are blocked (epics.md Story 3.8, SCPs 2026-11-02).

**Approach:** Measure on the physical minimum reference device (Galaxy Tab S7 FE 5G, serial `R52T50ELHWK`) with the 3.2 harness and a release-equivalent baseline vs. a temporary bundled candidate; select bundled vs. first-run by the rule in Design Notes; append the decision to the architecture memlog; wire the selected mode into the production `SolverSource` path; verify it under debug StrictMode on the device; and calibrate a reference-device-equivalent JVM profile from paired device/JVM runs of the Story 3.3 corpus, then rerun the corpus on it. All evidence goes in `docs/pruning-tables.md` and `docs/benchmarks.md`.

## Boundaries & Constraints

**Always:** Every recorded number is a real measurement from this run, with device model/serial, Android version and build fingerprint, full git revision, run conditions (charging, battery %, thermal status, stay-awake), and available-memory evidence (`/proc/meminfo` MemAvailable or `dumpsys meminfo`). Minimum-device criteria use only `R52T50ELHWK`; the Galaxy S24 FE (`R5CX83A5J1J`) may appear only as clearly labelled supplementary rows. Always pass `-s <serial>` to `/home/abelbriones/Android/Sdk/platform-tools/adb`. Table generation stays on `Dispatchers.Default`, disk on an I/O dispatcher; Lessons and app startup never call or await table readiness. Cancellation/failure never publishes a partial or invented Solution. Device-only/debug harness code ships in no release or `benchmark` variant APK beyond what the selected production mode needs. Memlog append only via `uv run _bmad/scripts/memlog.py append --workspace _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29 --type decision --by build --text "..."`. If a required measurement cannot be obtained, record that it is missing and leave the criterion open — never estimate, extrapolate or substitute host/emulator numbers.

**Never:** Factory-reset, change accounts or change device settings (only install/uninstall this app's builds and test APKs, `am`, `logcat`, read-only `dumpsys`/`getprop`/`cat`). Never run recursive `du`/`find` from `/` on a device. Never edit `_bmad-output/planning-artifacts/` (except the memlog append above), `_bmad/`, `.claude/`, `prd.md` or `ARCHITECTURE-SPINE.md`. No change to coordinate encoding, search algorithm, 3 s timeout or table format/`VERSION` in `:core:solver` main. No `INTERNET`, no new SDK, no camera work. Do not keep the temporary bundled candidate's build wiring if first-run is selected. Do not change Learner copy unless the selected mode makes "Preparing the solver (one time, a few seconds)." untrue.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First solve, no tables | Fresh install, Learner accepts a valid cube | Selected mode prepares tables off-main (preparing line shown), solves, playback starts | Load failure → `SolverLoad.Failed` → editor with colors and failure copy |
| Later solve, same process | Tables already loaded | No preparing line; solve runs immediately | — |
| New process, tables persisted | Cold start, private cache/asset valid | Tables load from private storage/asset off-main, no regeneration | Corrupt/short file → regenerated (first-run) or re-read from asset (bundled); never a crash |
| Cancel during preparation | Cancel tapped while loading | Load cancelled cooperatively; nothing published; next accept retries | — |
| Lesson while tables load | Lesson started/used during preparation | Lesson responds normally, never awaits tables | — |
| Persistent storage write fails | Private file write throws | Sealed `Failed`, prior file intact, temp cleaned (3.2 behavior) | Failure copy, retry possible |

</intent-contract>

## Code Map

- `app/src/debug/kotlin/dev/cubentia/app/solver/PruningTablesBenchmarkActivity.kt` -- 3.2 harness: `--ez cold true` deletes `filesDir/solver-benchmark/pruning-v1.bin`, logs `device=…`, `first,source=GENERATED,…generationNanos…`, `warm,source=CACHE,…cacheReadNanos…`, `cacheBytes=…,mainThreadDiskViolations=…` under tag `PruningTablesBenchmark`. Procedure in `docs/pruning-tables.md` §"Run on the minimum reference device". Extend (debug only) if more fields are needed (e.g. `Debug.getNativeHeap*`/`Runtime` memory, `ActivityManager.MemoryInfo`).
- `app/src/debug/AndroidManifest.xml` -- exported debug-only activities; add any new debug harness activity here.
- `core/solver/src/main/kotlin/dev/cubentia/solver/PruningTableProvider.kt` -- `PruningTableProvider(cacheFile, cpuDispatcher, ioDispatcher, generate)`; `load(): PruningTableLoadResult.{Ready(tables, source, generationNanos, cacheReadNanos, cacheWriteNanos), Failed}`; file = 8-byte header + 32-byte SHA-256 + payload (`FILE_BYTES` = 4,031,685 + 40). Bundled mode can reuse this exact file format as an asset (copy-on-first-use into private storage, or a stream-reading variant) — any addition must keep checksum validation and off-main I/O.
- `core/solver/src/main/kotlin/dev/cubentia/solver/PruningTables.kt` -- `generate()`, `expectedByteSize`, `byteSize`.
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` -- interim binding `PruningTableProvider(File(context.cacheDir, "solver/pruning-v1.bin"))`; KDoc names 3.8. This is where the selected production mode lands (first-run: move to `filesDir` so the "one time" promise is not broken by cache eviction).
- `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt` -- `SolverSource { isReady; load(): SolverLoad }`, `PruningTableSolverSource(provider)`; the single seam to change. `app/src/test/.../solver/PruningTableSolverSourceTest.kt` exists (3.7).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- calls `SolverSource.load()` then `SolveCube`; preparing line keyed on `!isReady`. Read-only unless the mode requires.
- `app/src/main/kotlin/dev/cubentia/app/CubentiaApp.kt` -- Application; no StrictMode yet. AD-16 requires StrictMode in `debug` only: add a debug-source-set hook (e.g. `app/src/debug/.../DebugStrictMode.kt` + a release/benchmark no-op, mirroring `DebugOptions.kt` in `app/src/debug` and `app/src/release`) enabling `detectDiskReads().detectDiskWrites().penaltyLog()`; the `benchmark` build type resolves its own source set — check how `DebugOptions` is supplied for it.
- `app/build.gradle.kts`, `app/src/benchmark/AndroidManifest.xml` -- `benchmark` build type = release copy (R8, shrinking), debug-signed, profileable: the release-equivalent build for installed-size measurement (`./gradlew :app:assembleBenchmark`). Temporary bundled candidate: e.g. a Gradle property (`-Pcubentia.bundleTables=true`) adding a generated asset dir containing the provider-format file, used only for measurement unless bundled is selected.
- `core/solver/src/benchmark/kotlin/dev/cubentia/solver/benchmark/{SolverWorkload,SolveCorpusHarness,CorpusStats,SolveCorpusBenchmarkMain}.kt` -- Story 3.3 corpus: `SolverWorkload(cases, seed)` (seed 20261031, 10,000 uniform + solved + 100 deep), `SolveCorpusHarness(warmup=200, budgetNanos)`; peak heap uses `java.lang.management` (absent on Android: make the heap sampler injectable, Android side uses `Runtime`/`Debug` memory). The device runner must execute this same corpus code (e.g. add `core/solver/src/benchmark/kotlin` to the app `debug` source set, or a shared non-shipping source) with the same seed/cases/warmup.
- `core/solver/build.gradle.kts` -- `solveCorpusBenchmark` JavaExec task; add the calibrated-profile option here/in `SolveCorpusBenchmarkMain` (e.g. `--time-scale <k>`), documented in `docs/benchmarks.md`. Tests: `core/solver/src/test/kotlin/dev/cubentia/solver/benchmark/*Test.kt`.
- `app/src/androidTest/kotlin/dev/cubentia/app/session/EndToEndSyncVerificationTest.kt` -- precedent for on-device instrumented Compose tests (`./gradlew :app:connectedDebugAndroidTest` with `ANDROID_SERIAL=R52T50ELHWK`), usable for the production StrictMode/Lesson-independence check.
- `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md` -- append-only via memlog.py; prior 3.8 traceability entries exist.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/debug/.../solver/PruningTablesBenchmarkActivity.kt` (+ manifest if needed) -- add available-memory evidence and process PSS/heap to the report; keep existing log lines. Run ≥ 5 cold-process runs (`am force-stop` then `am start -W … --ez cold true`) and ≥ 5 independent warm runs (force-stop, start without `cold`) on `R52T50ELHWK`; save raw logcat to `docs/evidence/3-8/` (new folder) and summarize (min/median/max) in `docs/pruning-tables.md`.
- [x] Bundled candidate + size -- build `benchmark` variant unbundled and bundled (temporary property); install each on `R52T50ELHWK`; record APK bytes, and installed size by a method that counts installed code/data (e.g. `dumpsys diskstats` package rows after its refresh, `dumpsys package` + `stat` of the `pm path` files, or `StorageStatsManager` via a debug probe) — state the method and its limits; record app data + table cache bytes after first-run generation; compute totals/deltas vs 30 MB and the headroom left for a v1.1 recognition model.
- [x] Decide -- apply the Design Notes rule to the measured numbers; append the memlog decision (mode, rationale, tradeoffs, evidence figures, revision).
- [x] `app/src/main/.../di/SolverModule.kt`, `app/src/main/.../solver/SolverSource.kt` (+ asset loader if bundled) -- integrate the selected mode as the production path; update KDoc; remove the temporary bundling wiring if not selected.
- [x] `app/src/debug/.../DebugStrictMode.kt` + release/benchmark no-op + `CubentiaApp.kt` -- debug-only thread-policy StrictMode (disk reads/writes, `penaltyLog`).
- [x] `app/src/test/.../solver/*Test.kt` -- JVM tests for the production path covering the I/O matrix (cold → generated/asset, persisted → no regeneration, corrupt file → recovered, cancel → nothing published and retry works, write failure → sealed failure).
- [x] Device production check -- on `R52T50ELHWK`, debug build, StrictMode on: run the Solve flow cold (no tables) and warm (new process, tables persisted), plus a Lesson used while tables load; inspect `logcat -s StrictMode` and app logs; require zero main-thread disk violations attributable to table loading/solving (report any other violations found verbatim as findings). An instrumented test under `app/src/androidTest` or a scripted `adb` UI run is acceptable; save logs to `docs/evidence/3-8/`.
- [x] Calibration -- device corpus runner (debug-only) running the 3.3 corpus (cases 10000, seed 20261031, warmup 200) on `R52T50ELHWK` with tables prepared before timing; same corpus on the host via `solveCorpusBenchmark` with identical args; record both distributions; define the calibrated profile as host JVM + factor k = max(device/host median ratio, device/host p95 ratio); add the `--time-scale` (or equivalent) option with unit tests; rerun the full corpus on the calibrated profile and report median, p95, max, memory, failures, share ≤ 3 s, all identities, revision, seed and warmup/table-readiness boundaries in `docs/benchmarks.md`.
- [x] `docs/pruning-tables.md`, `docs/benchmarks.md` -- replace "pending" with the measured evidence, decision, method and links to `docs/evidence/3-8/`; keep host-vs-device distinction explicit.

**Acceptance Criteria:**
- Given `R52T50ELHWK` and the 3.2 harness, when repeated cold-process generation and independent warm-cache loads run, then generation duration, payload/cache bytes and cache-load duration are recorded with device/Android identity, revision, run conditions and memory evidence, and no host or emulator number substitutes.
- Given a release-equivalent unbundled baseline and a temporary bundled candidate, when installed size and delivery costs are measured, then method, installed totals/deltas, cache cost and recognition-model headroom are recorded against < 30 MB, not APK size alone.
- Given those measurements, when the mode is selected, then the memlog holds the rationale, tradeoffs and evidence, and the mode respects AD-8, offline operation and NFR-11.
- Given the selected mode in production, when cold and warm loading run on `R52T50ELHWK` under debug StrictMode, then no main-thread disk access from table loading is reported, logs are inspected, Lessons stay usable, and no partial Solution is published.
- Given the 3.3 corpus and paired device/JVM runs, when the calibrated profile reruns it, then every Solution is verified, failures are zero, median/p95/memory/failure rate are reported and ≥ 95% solve within 3 s on the calibrated profile, with identities, revision, seed and boundaries linked.
- Given the finished work, when reviewed, then `./gradlew spotlessCheck build` passes and every unobtainable measurement is listed as open (criterion stays open, story not claimed complete for it).

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 18 findings — high 2, medium 6, low 8, false 2, maybe-false 0
- findings:
  - `[medium]` `[intent_gap]` Blind: "Preparing the solver (one time, a few seconds)." is untrue — measured ~8 s of move-table preparation on every new process (bundled asset read is 24 ms; `prepareMoveTables` ~8.26 s debug, ≤ ~7.6 s release-equivalent). Fixing it means either changing Learner copy fixed verbatim by Story 3.7's AC/UX, or new delivery work (ship move tables, speed them up, prewarm on Solve-tab open); the intent selects neither.
  - `[high]` `[patch]` Blind: calibrated `SolveCorpusHarness` scales time but keeps `succeeded`, so the case scaled to 3,068 ms counts as a success outside budget instead of the timeout it would be on the device — smallest fix: count scaled > budget as a failure. Moot under the intent gap (not applied); applied honestly, the calibrated run has 1 failure, so the AC's "zero failures" criterion does not pass (the direct device run also had 1 timeout at 3,000.9 ms).
  - `[low]` `[reject]` Blind: device timeout case not retained for a regression test — diagnostic tooling beyond a direct correction; the case is reproducible from seed 20261031.
  - `[medium]` `[intent_gap]` Blind: calibrated rerun under-predicts device median/p95 (45.4/277.4 ms vs device 53.8/338.1 ms) because host speed varied between the pairing and the rerun; profile validity needs a stated acceptance method — part of the calibration question below.
  - `[low]` `[reject]` Blind: benchmark JVM heap not pinned to the device's 512 MiB — memory is reported per AC; equivalence of GC conditions is not asserted by the AC; pinning adds configuration without a demonstrated wrong result.
  - `[medium]` `[patch]` Blind: no test proves bundled/cache loading builds absent move tables (fixture already initialized the global `MoveTables`) — moot under the intent gap; carry into the next attempt.
  - `[false]` `[reject]` Blind: `readBundled` catching `RuntimeException` swallows cancellation — the opener is non-suspending, and `withContext(ioDispatcher)` rethrows `CancellationException` on return when the job is cancelled, so no fallback continues.
  - `[low]` `[reject]` Blind: `SolveCorpusBenchmarkActivity` raw thread outlives the Activity — debug-only, opt-in harness started by adb; no user path.
  - `[low]` `[reject]` Blind: Lesson-independence check ran after navigation cancelled preparation — 3.7 cancels the solve when leaving Solve, so concurrent Lesson + preparation is not reachable in production.
  - `[low]` `[patch]` Blind: `$ADB` with embedded args fails under zsh word splitting in docs — moot under the intent gap; use an array or explicit args next attempt.
  - `[high]` `[patch]` Edge: scaled > 3 s with host success reported as zero failures — same root cause as the Blind calibrated-failure row.
  - `[low]` `[reject]` Edge: huge time scale saturates `Long` and overflows the median — requires k ≈ 1e9+, unreachable with measured k = 16.94.
  - `[medium]` `[patch]` Verification: move-table readiness test passes with the preparation body removed — same root cause as the Blind move-table test row.
  - `[medium]` `[intent_gap]` Intent: Learner-facing readiness (~8 s every process, untrue copy) vs provider-level tests — same root cause as the Blind copy row.
  - `[low]` `[reject]` Intent: decision followed the spec's 5 s threshold rather than a per-mode Learner-cost comparison — the memlog records both modes' costs; bundled is defensible on the measured numbers (+0.92 MB, ~22 MB headroom).
  - `[low]` `[reject]` Intent: `filesDir` fallback path never run on device under StrictMode — AC5 names the selected (bundled) path, which was run cold, same-process and new-process.
  - `[high]` `[intent_gap]` Intent: calibrated "zero failures" met only on scaled statistics (R4a), not device-equivalence (R4b) — same defect as the Blind calibrated row; whether the story may close with 1 calibrated/device timeout, or needs solver performance work the spec put out of scope, is a PO decision.
  - `[low]` `[patch]` Intent: AC7 "CI and release-size checks link their evidence" — docs link no CI result; moot under the intent gap.
- intent_gap: attempt saved as [spec-3-8-attempt-1.patch](spec-3-8-attempt-1.patch) (applies to baseline 00cfb5be46c35a6ea4d9ec3c6957541527988e0c; commits also kept at local branch `wip/3-8-attempt-1` = aa036523751f3168601768d1d206ceef19de7fe2); code changes reverted.

## Design Notes

**Selection rule (resolves bundled vs first-run without guessing):** Let G = median cold-process first-run preparation on `R52T50ELHWK` (generation + cache write, as the harness reports), and B = measured installed-size delta of the bundled candidate over the unbundled baseline. Select **first-run** when G ≤ 5 s (the UX copy promises "one time, a few seconds") and baseline installed total + table cache stays < 30 MB. Otherwise select **bundled** if bundled installed total < 30 MB, else neither passes: record it, keep the interim path, and leave the decision criterion open. Record both G and B and the losing option's costs in the memlog either way.

**First-run storage:** `filesDir` (app-private, not evictable) so preparation really is one time; `cacheDir` eviction would silently re-trigger it. The old interim `cacheDir/solver/pruning-v1.bin` exists only on developer installs (no release yet); no migration code.

**Calibration meaning:** paired = same corpus code, seed, case count, warmup and table-readiness boundary on both sides. k scales host per-case times; "within 3 s on the calibrated profile" means host time × k ≤ 3 s. The device run is also reported as direct evidence but Story 12.2 still owns final physical confirmation.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:solver:solveCorpusBenchmark --args="--cases 10000 --seed 20261031 --warmup 200 <calibrated-profile flag>"` -- expected: zero failures, ≥ 95% within 3 s on the calibrated profile
- `/home/abelbriones/Android/Sdk/platform-tools/adb -s R52T50ELHWK logcat -d -s StrictMode PruningTablesBenchmark` (after device runs) -- expected: harness reports present, no table-loading main-thread disk violations

**Manual checks:**
- `docs/evidence/3-8/` holds raw device logs referenced by `docs/pruning-tables.md` and `docs/benchmarks.md`; memlog tail shows the 3.8 decision entry.

## Auto Run Result

Status: blocked
Blocking condition: intent gap

Summary: The attempt measured everything on Tab S7 FE `R52T50ELHWK` (5 cold generation runs median 10,014 ms; warm cache loads; installed size: unbundled 6,880,214 B, bundled 7,802,989 B (+922,770 B), first-run 10,911,939 B; ~22 MB headroom under 30 MB), selected bundled delivery, appended the memlog, wired the bundled asset into `SolverModule`/`PruningTableProvider`, added debug StrictMode and fixed two device-found defects (move tables built inside the 3 s solve timeout; `getFilesDir()` on main). Review found two product-level gaps the intent does not settle, so the code was reverted and saved as a patch.

Unanswered questions:
1. Solver preparation takes ~8 s on every new app process on the minimum device (move-table build; the bundled asset does not remove it), so "Preparing the solver (one time, a few seconds)." is untrue. Should 3.8 (a) change the Learner copy (overriding Story 3.7's verbatim AC/UX text), (b) add delivery work to make it true (ship or persist move tables, speed up their build, prewarm when the Solve tab opens), or (c) accept and defer?
2. On the calibrated profile (k = 16.94 from paired debug-device/host runs) one case scales to 3,068 ms, a device timeout; the direct device corpus run also had 1 timeout (3,000.9 ms) in 10,101. The AC requires zero failures. Should this count as a failure (story stays open pending solver performance work, which this spec excluded), and should calibration pair against a release-equivalent (non-debuggable) device build instead of the debug build?

Verification performed: `./gradlew spotlessCheck build` BUILD SUCCESSFUL on the attempt (before revert). Raw evidence lives in the patch under `docs/evidence/3-8/`.

Residual risks: the device still has the release-equivalent (`benchmark`) build of the attempt installed.

