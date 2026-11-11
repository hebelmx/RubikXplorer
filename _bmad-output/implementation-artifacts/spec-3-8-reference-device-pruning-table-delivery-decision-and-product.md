---
title: 'Reference-device pruning-table delivery decision and production integration'
type: 'feature'
created: '2026-11-10'
status: done
baseline_revision: 86a32d120fcd72b0eec6e1e12ca54227f490fed2
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

Attempt 1 is complete and reusable: local branch `wip/3-8-attempt-1` (tip `aa036523751f3168601768d1d206ceef19de7fe2`, base `00cfb5be46c35a6ea4d9ec3c6957541527988e0c`), also `spec-3-8-attempt-1.patch`. Its commits, in order: `79e6611` measurement tooling (device corpus runner, `--time-scale`, harness memory evidence, debug StrictMode), `ed490da` bundled-candidate asset build, `ef9c6df` bundled production delivery, `dd1d4ac` move tables built off-main before ready, `eeac7db` harness bundled-only timing, `03a69f9` fallback path without main-thread disk access, `dcf3b65` docs + `docs/evidence/3-8/`, `aa03652` memlog (do NOT cherry-pick: re-append via memlog.py).

- `core/solver/src/benchmarkShared/kotlin/dev/cubentia/solver/benchmark/SolveCorpusHarness.kt` -- `run()` builds `Sample(scale(nanos), succeeded)`; a scaled time > `budgetNanos` must count as a failure (review finding, high).
- `core/solver/src/test/kotlin/dev/cubentia/solver/benchmark/SolveCorpusHarnessTest.kt` -- add the scaled-over-budget failure test.
- `core/solver/src/main/kotlin/dev/cubentia/solver/MoveTables.kt` -- `internal object MoveTables` with a private global `tables` map; add an `internal` test-only reset/inspection hook so a test can prove `PruningTableProvider` builds absent move tables.
- `core/solver/src/main/kotlin/dev/cubentia/solver/PruningTableProvider.kt` -- `prepareMoveTables()` on `cpuDispatcher` for BUNDLED/CACHE results (attempt 1).
- `core/solver/src/test/kotlin/dev/cubentia/solver/PruningTablesTest.kt` -- test `loaded tables build the move tables on the CPU dispatcher before ready` passes with the preparation body removed (review finding); make it clear `MoveTables` first and assert every coordinate is built.
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt`, `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt`, `app/src/test/kotlin/dev/cubentia/app/solver/ProductionTableDeliveryTest.kt`, `ShippedTableAssetTest.kt` -- bundled production path + I/O-matrix tests (attempt 1).
- `app/src/debug/.../DebugStrictMode.kt`, `app/src/release/.../DebugStrictMode.kt`, `app/src/main/kotlin/dev/cubentia/app/CubentiaApp.kt` -- debug-only StrictMode (attempt 1).
- `docs/pruning-tables.md`, `docs/benchmarks.md`, `docs/evidence/3-8/` -- evidence (attempt 1). Shell snippets using `$ADB` with embedded args break under zsh word splitting; calibrated-profile section and Known issues need updating.
- `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md` -- append-only via memlog.py.

## Tasks & Acceptance

**Execution:**
- [x] Restore attempt 1 -- cherry-pick `79e6611`, `ed490da`, `ef9c6df`, `dd1d4ac`, `eeac7db`, `03a69f9`, `dcf3b65` (not `aa03652`) onto this branch; resolve conflicts against current HEAD; reuse its device measurements, do not re-measure them.
- [x] `SolveCorpusHarness.kt` + `SolveCorpusHarnessTest.kt` -- count a sample whose scaled time exceeds the budget as a failure; unit-test it (k = 1 unaffected).
- [x] `MoveTables.kt` + `PruningTablesTest.kt` -- internal test hook; the move-table readiness test clears move tables, loads via BUNDLED/CACHE, and asserts all coordinates are built on the CPU dispatcher (fails if `prepareMoveTables` body is removed).
- [x] Calibrated rerun -- rerun only the host calibrated profile with the fixed harness (`--cases 10000 --seed 20261031 --warmup 200 --time-scale 16.94`), save to `docs/evidence/3-8/`, and report its real median/p95/max/memory/failures/share <= 3 s; keep k = 16.94 (no recalibration, #105).
- [x] `docs/pruning-tables.md`, `docs/benchmarks.md` -- report the device corpus timeout (3,000.9 ms, 1 of 10,101) and the calibrated-profile failure(s) as measured; add a "Known issues (v1.1)" note: per-process ~8 s preparation vs "one time, a few seconds" copy accepted by PO, deferred to #104; zero-failure deviation and release-equivalent recalibration waived by PO, deferred to #105; fix `$ADB` snippets to work in zsh (explicit `-s` args or arrays); link the `./gradlew spotlessCheck build` verification result.
- [x] Memlog -- `uv run _bmad/scripts/memlog.py append ... --type decision --by build` with attempt 1's decision text (bundled, evidence, revisions as recorded) replacing its "Open: Learner copy ... needs PO/UX decision" with the PO acceptance (#104) and the #105 waiver.

**Acceptance Criteria:**
- Given `R52T50ELHWK` and the 3.2 harness, when repeated cold-process generation and independent warm-cache loads run, then generation duration, payload/cache bytes and cache-load duration are recorded with device/Android identity, revision, run conditions and memory evidence, and no host or emulator number substitutes.
- Given a release-equivalent unbundled baseline and a temporary bundled candidate, when installed size and delivery costs are measured, then method, installed totals/deltas, cache cost and recognition-model headroom are recorded against < 30 MB, not APK size alone.
- Given those measurements, when the mode is selected, then the memlog holds the rationale, tradeoffs, evidence and the PO-accepted deferrals (#104, #105), and the mode respects AD-8, offline operation and NFR-11.
- Given the selected mode in production, when cold and warm loading run on `R52T50ELHWK` under debug StrictMode, then no main-thread disk access from table loading is reported, logs are inspected, Lessons stay usable, and no partial Solution is published.
- Given the 3.3 corpus and paired device/JVM runs, when the calibrated profile reruns it with scaled-over-budget samples counted as failures, then every Solution is verified, median/p95/memory/failure count are reported as measured, >= 95% solve within 3 s on the calibrated profile, and any nonzero failure count is documented as the PO-accepted waiver referencing #105, with identities, revision, seed and boundaries linked.
- Given the finished work, when reviewed, then `./gradlew spotlessCheck build` passes, the Learner copy is unchanged, no move-table delivery or solver-performance work was added, and every unobtainable measurement is listed as open.

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

### 2026-11-10 — Review pass (attempt 2, PO clarifications applied)
- verdicts: 20 findings — high 0, medium 2, low 13, false 5, maybe-false 0 (intent-alignment 9, blind 10, edge 1, verification-gap 0)
- findings:
  - `[low]` `[reject]` Intent: spec rewrite uncommitted, tasks unticked, Design Notes "one time" wording stale — fix edits this build's spec; spec is committed and updated at finalize.
  - `[false]` `[reject]` Intent: off-main move-table preparation may be forbidden "move-table delivery work" (R2a vs R2b) — the PO asked to reuse attempt 1's bundled integration, which includes this correctness fix (a one-move cube timed out on device without it); #104 concerns shipping/speeding move tables, which the diff does not do.
  - `[low]` `[reject]` Intent: `LessonTableIndependenceTest` is a source scan, not runtime proof — runtime evidence exists in `production-strictmode-tab-s7-fe.log`; an instrumented test is more than a direct correction.
  - `[low]` `[reject]` Intent: `filesDir` fallback never run on device — carried: AC names the selected (bundled) path; listed under "Open measurements" in docs.
  - `[low]` `[reject]` Intent: build log captured at `29de857`, not branch tip; no automated release-size check — tip verified with `./gradlew spotlessCheck build` in this pass; size gate is the recorded device measurement per AC2.
  - `[false]` `[reject]` Intent: calibrated profile not reference-device-equivalent — waived by PO under #105 (no recalibration).
  - `[medium]` `[patch]` Intent: cited attempt-1 SHAs unreachable from main after merge — added attempt-1 → cherry-picked SHA mapping table in `docs/pruning-tables.md` (7ff070a).
  - `[false]` `[reject]` Intent: "100.0%" rounding — docs already report exact counts (10,100 of 10,101).
  - `[medium]` `[patch]` Intent (R2c): `docs/benchmarks.md` still says the 3.8 corpus requirement blocks 3.7/P0.S without recording the waiver — added the #105 waiver outcome sentence (7ff070a).
  - `[low]` `[patch]` Blind: Reproduce runs `logcat -d` right after async start — added a wait loop for the final marker (7ff070a).
  - `[low]` `[patch]` Blind: "at most ~7.6 s" contradicts 7.78 s observation — now "7.6–7.8 s (coarse UI polling, upper bounds)" (7ff070a).
  - `[low]` `[patch]` Blind: budget omits fallback state (asset + private file) — added 11,988,314 B / ~18.0 MB headroom (7ff070a).
  - `[low]` `[patch]` Blind: device heap sampled only after attempts — labelled "maximum sampled used heap" in `docs/benchmarks.md` (7ff070a).
  - `[low]` `[patch]` Blind: no test cancels during move-table preparation — added cancellation test in `PruningTablesTest.kt`; mutation (no ensureActive) makes it fail (7ff070a).
  - `[low]` `[reject]` Blind: bundled read not asserted on I/O dispatcher — device StrictMode run shows zero table-loading violations; instrumented-stream test adds test machinery beyond a direct correction.
  - `[low]` `[reject]` Blind: `ShippedTableAssetTest` checks Robolectric debug assets only — asset is in the `main` source set for all variants and its presence in the benchmark APK is recorded in `installed-size-tab-s7-fe.log`.
  - `[low]` `[reject]` Blind: failed-case index/reason not retained — carried from previous pass: diagnostic tooling beyond a direct correction; reproducible from seed 20261031.
  - `[false]` `[reject]` Blind: report renders 10,100/10,101 as 100.0% — the docs state exact counts; changing the shared 3.3 report format is out of this story's ACs.
  - `[low]` `[reject]` Blind: spec Auto Run Result stale ("blocked") — fix edits this build's spec; replaced at finalize.
  - `[false]` `[reject]` Edge: huge time scale overflows median — carried: requires k ≈ 1e9+, unreachable with k = 16.94.

## Design Notes

**Selection rule (resolves bundled vs first-run without guessing):** Let G = median cold-process first-run preparation on `R52T50ELHWK` (generation + cache write, as the harness reports), and B = measured installed-size delta of the bundled candidate over the unbundled baseline. Select **first-run** when G ≤ 5 s (the UX copy promises "one time, a few seconds") and baseline installed total + table cache stays < 30 MB. Otherwise select **bundled** if bundled installed total < 30 MB, else neither passes: record it, keep the interim path, and leave the decision criterion open. Record both G and B and the losing option's costs in the memlog either way.

**First-run storage:** `filesDir` (app-private, not evictable) so preparation really is one time; `cacheDir` eviction would silently re-trigger it. The old interim `cacheDir/solver/pruning-v1.bin` exists only on developer installs (no release yet); no migration code.

**PO clarifications (2026-11-10, resolve the attempt-1 intent gaps):** (1) The ~8 s per-process preparation on the Tab S7 FE vs the 3.7 copy is accepted for v1.0, deferred to v1.1 as GitHub issue #104: keep the copy, no move-table delivery work here; this overrides the intent's conditional copy-change clause. (2) The device corpus timeout (3,000.9 ms of 10,101) and the calibrated case at 3,068 ms (k = 16.94 from debug-build pairing) are accepted for v1.0, deferred to v1.1 as #105 (incl. release-equivalent recalibration): no solver performance work or recalibration; report honestly; the zero-failure criterion is a PO-accepted waiver. Bundled delivery is approved.

**Calibration meaning:** paired = same corpus code, seed, case count, warmup and table-readiness boundary on both sides. k scales host per-case times; "within 3 s on the calibrated profile" means host time × k ≤ 3 s. The device run is also reported as direct evidence but Story 12.2 still owns final physical confirmation.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:solver:solveCorpusBenchmark --args="--cases 10000 --seed 20261031 --warmup 200 --time-scale 16.94"` -- expected: ≥ 95% within 3 s; failures reported as measured (nonzero = #105 waiver)
- `/home/abelbriones/Android/Sdk/platform-tools/adb -s R52T50ELHWK logcat -d -s StrictMode PruningTablesBenchmark` (after device runs) -- expected: harness reports present, no table-loading main-thread disk violations

**Manual checks:**
- `docs/evidence/3-8/` holds raw device logs referenced by `docs/pruning-tables.md` and `docs/benchmarks.md`; memlog tail shows the 3.8 decision entry.

## Auto Run Result

Previous result (attempt 1, 2026-11-10): blocked on intent gap; both questions answered by the PO (#104, #105) and attempt 1 restored by cherry-pick (see Review Triage Log).

Status: done

**Summary:** Story 3.8 delivered on the PO-amended reading: bundled pruning-table delivery in production (asset read/validated on the I/O dispatcher, move tables built on `Dispatchers.Default` before ready, `filesDir` first-run fallback), debug-only StrictMode, and device evidence from attempt 1 reused (Tab S7 FE `R52T50ELHWK`: cold generation median 10,014 ms; bundled installed 7,802,989 B, +922,770 B over baseline, ~22.0 MB headroom). The calibrated harness now counts scaled-over-budget cases as failures; host rerun at k = 16.94: 10,101 cases, median 46.750 ms, p95 291.891 ms, max 3,303.584 ms, 1 failure (10,100 within 3 s), peak heap 604.4 MiB. Device corpus: 1 timeout (3,000.9 ms) of 10,101. The zero-failure deviation is a PO-accepted waiver (#105); the per-process ~8 s preparation vs unchanged Learner copy is a deferred known issue (#104). Memlog decision appended via memlog.py.

**Files changed:**
- `core/solver/src/main/.../PruningTableProvider.kt` -- bundled source, shared decode, off-main move-table preparation.
- `core/solver/src/main/.../MoveTables.kt` -- internal test hooks (observer, built set, reset).
- `core/solver/src/benchmarkShared/.../SolveCorpusHarness.kt` -- injectable heap sampler, `timeScale`, scaled-over-budget = failure.
- `core/solver/src/benchmark/...` -- `--time-scale`, JVM heap sampler, asset writer main; `core/solver/build.gradle.kts` -- `writePruningTableAsset`, shared source set.
- `app/build.gradle.kts`, `app/src/main/.../di/SolverModule.kt`, `CubentiaApp.kt`, `app/src/{debug,release}/.../DebugStrictMode.kt` -- production bundled wiring, StrictMode in debug only.
- `app/src/debug/.../PruningTablesBenchmarkActivity.kt`, `SolveCorpusBenchmarkActivity.kt`, debug manifest -- device harness/corpus runner.
- Tests: `ProductionTableDeliveryTest`, `ShippedTableAssetTest`, `LessonTableIndependenceTest`, `PruningTablesTest`, `SolveCorpusHarnessTest`, `SolveCorpusBenchmarkMainTest`.
- `docs/pruning-tables.md`, `docs/benchmarks.md`, `docs/evidence/3-8/` -- evidence, decision, known issues #104/#105, revision mapping.
- Architecture memlog -- decision entry with PO deferrals.

**Review findings:** 20 findings; 7 patched (medium 2: revision mapping, waiver outcome in benchmarks.md; low 5: Reproduce wait loop, 7.6–7.8 s wording, fallback budget, heap label, move-table cancellation test); 0 deferred; 13 rejected with reasons in the triage log (spec-edit findings, carried rejections, PO-waived calibration, rounding already documented, device-evidence-covered test gaps).

**Follow-up review recommendation:** false — two medium entries were patched, but both are documentation edits verified directly (mapping SHAs checked against `git log`; waiver sentence read); no unverified risk can be named.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL at tip (after patches); targeted suites green (ProductionTableDeliveryTest 6, ShippedTableAssetTest 1, LessonTableIndependenceTest 1, PruningTableSolverSourceTest 1, PruningTablesTest, SolveCorpusHarnessTest 9). Device measurements reused from attempt 1; no new device runs this pass.

**Residual risks:** cited measurement SHAs live on local `wip/3-8-attempt-1` (mapping table provided; keep or push that branch for traceability); `filesDir` fallback never run on device; k = 16.94 derived from a debug build (#105); the device still has an attempt-1 benchmark build installed; `docs/evidence/3-8/build-verification.log` predates the final test/doc commits (tip re-verified here).
