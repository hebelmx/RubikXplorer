---
title: 'GH #406 continuous Quick Scan per-stage timings in debug ScanDiagnostics'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: 'd9c7127d068f306d1d5f0c69942791b1392ca91c'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      The debug timing line format and the evaluateQuickScan parser are linked only by a regex copied into ScanDiagnosticsTimingLineTest (GH #406 review).
    evidence: |-
      :app cannot depend on the :core:domain evaluation source set, so the app test keeps its own copy of the parser pattern, as ScanDiagnosticsRunLineTest does for the run line; a format change on one side alone would make every run count as missing timings without failing a test. Closing it needs a shared golden line read by both suites.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsTimingLineTest.kt
    severity: low
  - summary: 'Device check owed (Refs #406): run a continuous Quick Scan on a debug build on the Lenovo TB128XU and the Galaxy S24 FE, read the periodic and end-of-run quickScan timing lines with adb logcat -s CubentiaScanDiag, and record the per-stage p50/p90/max and frame rate (Story 10.33 device matrix, SCP 2026-11-27 quickscan-framing-crop 4.1.5). The GitHub issue stays open until this is recorded.'
    evidence: 'No physical device or camera in the build session (PO clarification); JVM and Robolectric tests cover the line format, percentiles and parsing only.'
    severity: 'low'
---

<intent-contract>

## Intent

**Problem:** The continuous Quick Scan has no on-device timing: `StickerCenterDetection.inferenceMs` is measured but never logged, and the fit, sample and map stages are not timed, so the Story 10.33 device matrix cannot tell which stage makes a scan slow (GitHub issue #406).

**Approach:** `ContinuousScanPipeline.processFrame` times each stage with a monotonic nanosecond clock into a preallocated, allocation-free accumulator; debug `ScanDiagnostics` logs a periodic `quickScan timing window …` line and, when the Quick Scan screen finishes (next to `onQuickScanRun`), one `quickScan timing run …` line that `evaluateQuickScan` parses. The release twin is a no-op and disables the accumulator.

## Boundaries & Constraints

**Always:**
- Stages per frame: `detector` (the whole `detector.detect` call), `inference` (`detection.inferenceMs`), `fit` (`FaceLatticeFitter.fit`), `sample` (all `sampler.sample` calls of the frame), `map` (`mapper.processFrame`), `analyzer` (the whole `processFrame` call, entry to return, skipped frames included). `detector`/`inference` are recorded for every frame the detector ran with a non-skipped result; `fit`/`sample`/`map` for every frame that reached the mapper; `analyzer` for every frame.
- Per-frame recording does not allocate: primitive `LongArray` rings preallocated at construction, a `fun interface NanoClock { fun nanoTime(): Long }` (never a `() -> Long`, which boxes), no lambdas or collections on the per-frame path. Sorting and string building happen only when a line is emitted.
- Percentiles are nearest-rank over the recorded samples: sorted ascending, `pQ = sorted[ceil(Q·n) − 1]`, max = last; values printed in ms with one decimal (`Locale.ROOT`) as `p50/p90/max`; a stage with no samples prints `-`.
- Line body, fixed field order (numbers and fixed words only, AD-13): `frames=<analysed> read=<reached mapper> fps=<x.x> detectorMs=… inferenceMs=… fitMs=… sampleMs=… mapMs=… analyzerMs=… fitted=<faces fitted, summed> observations=<observations passed to the mapper, summed>`. fps = frames ÷ (end of the last frame − start of the first frame of the span), `-` when the span is 0.
- Window line: emitted from the analyzer thread at the end of a frame once ≥ 2 s (named constant) of clock have passed since the window's first frame; the window then restarts. Run line: covers the pipeline's whole life (not cleared by `reset()`/`record()`), keeping the latest `RUN_CAPACITY` (4096, named) samples per stage for percentiles while `frames`/`read`/`fitted`/`observations`/fps count every frame; null when no frame was analysed.
- Accumulator access is `synchronized` (the run line is read on the main thread at finish).
- Debug log lines: `quickScan timing window <body>` and `quickScan timing run <body> device=<model>` (same device sanitising as `run()`), tag `CubentiaScanDiag`. The run line is logged immediately before the `quickScan run …` line.

**Never:** No timing assertions on wall-clock speed; no change to detector, fitter, sampler, mapper behaviour or snapshots; no per-frame log line; no frame/pixel/sticker data; no edits to planning artifacts, `_bmad/`, `.claude/skills/`, `sprint-status.yaml`, `Fixtures/`; no change to the existing `quickScan run` line or the `Report` fields' meaning.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Known samples | fake clock, 10 frames with analyzer 1..10 ms | analyzerMs=5.0/9.0/10.0, frames=10 | none |
| Single sample | 1 frame | p50 = p90 = max | none |
| Skipped frame | detector returns SKIPPED | analyzer counted, detector/fit/sample/map not; read unchanged | none |
| No frames | finish before any frame | no timing run line; evaluation marks the run as missing timings | none |
| Over capacity | 5000 frames | percentiles over the latest 4096; frames=5000 | none |
| Release | release ScanDiagnostics | accumulator not created, nothing logged, compiles | none |

</intent-contract>

## Code Map

- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `processFrame` (detector call, `FaceLatticeFitter.fit`, `sampler.sample` in `mapNotNull`, `mapper.processFrame`, three early `skippedFrame` returns plus the sampler-exception return). Add `timings: ContinuousStageTimings? = ContinuousStageTimings.forBuild()` constructor param and `fun timingRunLine(): String?`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- `PipelineEngine` delegates the new engine method.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- add `fun timingRunLine(): String? = null` to the interface.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` ~605 `finishWith` -- the `quickScanMode` branch: call `ScanDiagnostics.onQuickScanTimingRun(scan.engine?.timingRunLine())` just before `onQuickScanRun`.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` / `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- add `const val QUICK_SCAN_TIMINGS` (debug true, release false), `onQuickScanTimingWindow(body: String)`, `onQuickScanTimingRun(body: String?)` (null logs nothing); release versions are `= Unit`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- READ-ONLY: `inferenceMs` is set from `System.nanoTime` around `active.run`.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/quickscan/QuickScanMeasurement.kt` -- `measure` / `Report` / `markdown`; existing tests in `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanMeasurementTest.kt` construct `Report(runs, successes, fallbackRuns, repromptRuns)` and must pass unchanged.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineSkippedFrameTest.kt` -- `ScriptedDetector`, `goodFrame()` and `faceFor` patterns to reuse (JUnit 5). `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsRunLineTest.kt` -- Robolectric `ShadowLog` pattern (JUnit 4).

## Tasks & Acceptance

**Execution:**
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousStageTimings.kt` -- new: `NanoClock` (`SYSTEM` = `System.nanoTime`), `ContinuousStageTimings(clock, onWindow: (String) -> Unit, intervalNs, runCapacity)` with `record(...)` taking primitives (-1 for a stage that did not run), `runLine(): String?`, and `forBuild()` returning null unless `ScanDiagnostics.QUICK_SCAN_TIMINGS` (wired to `ScanDiagnostics::onQuickScanTimingWindow`) -- the allocation-free accumulator and body formatter.
- `ContinuousScanPipeline.kt`, `ContinuousScanEngine.kt`, `ContinuousScanEngineModule.kt`, `GuidedScanActivity.kt`, both `ScanDiagnostics.kt` -- as in the Code Map; with `timings == null` no clock is read.
- `QuickScanMeasurement.kt` -- parse `quickScan timing run` lines; attach the latest one to the next `quickScan run` line (cleared after each run line); add `runTimings: List<RunTiming> = emptyList()` (1-based run number, frames, fps, p50/p90/max per stage, nullable for `-`) to `Report`; the markdown gains a timings table and a "Runs missing timings: N" line.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousStageTimingsTest.kt` -- the I/O matrix over a fake clock (percentiles, field order, window emission at 2 s and restart, capacity, fps, `-` cases) plus an allocation check: 10,000 `record` calls with no emission allocate < 64 KiB on the test thread (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`; skipped via assumption if unsupported).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineTimingTest.kt` -- pipeline with a scripted detector and a fake clock advancing a fixed step per read: per-stage samples, inference taken from `inferenceMs`, skipped frame counted only in analyzer/frames.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsTimingLineTest.kt` -- Robolectric: window and run line prefixes, `device=`, null run body logs nothing, and the run line matches the `QuickScanMeasurement` pattern (copied, as `ScanDiagnosticsRunLineTest` does).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanMeasurementTest.kt` -- add: timing line attached to the following run, a run without one counted missing, `-` fields parsed as null, markdown contents.

**Acceptance Criteria:**
- Given a debug continuous Quick Scan, when frames are analysed, then a `quickScan timing window` line with the fields above appears at most once per 2 s, and when the screen finishes a `quickScan timing run … device=…` line precedes the `quickScan run` line.
- Given a log with timing run lines, when `./gradlew :core:domain:evaluateQuickScan --args="<log> <report>"` runs, then the report lists each run's per-stage p50/p90/max and fps, and runs without a timing line as missing.
- Given the release `ScanDiagnostics`, when `./gradlew build` and the opt-in `-Pcubentia.release011=true` release011 Kotlin compile run, then both compile and release011 creates no accumulator.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 27 findings — high 0, medium 2, low 17, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Detector/inference not recorded for detector-skipped frames — the spec records them only for a non-skipped detection; a SKIPPED frame ran no model (LiteRtStickerCenterDetector returns SKIPPED before or instead of a usable inference).
  - `[false]` `[reject]` (blind) Sampler-throw frames omit fit/sample — by spec fit/sample/map are recorded only for frames that reached the mapper; the frame still counts in frames/analyzer.
  - `[false]` `[reject]` (blind) detectorMs overlaps inferenceMs — intended: the issue asks for "detector total plus its inferenceMs"; the KDoc names detector as the whole detect call.
  - `[false]` `[reject]` (blind) Window lines also fire in other continuous modes — ContinuousScanPipeline is built only by ContinuousScanEngineModule for the continuous Quick Scan; no other mode uses it.
  - `[low]` `[reject]` (blind) RunTiming/markdown drop fitted and observations — the spec's report fields are frames, fps and the stage percentiles; the raw log keeps the counts; additions beyond the intent.
  - `[low]` `[reject]` (blind) runCapacity not validated — internal constructor, only the named default and tests' explicit values are used; a guard adds code for an unreachable case.
  - `[low]` `[reject]` (blind) A pending timing line can attach to a later unrelated run — the debug build logs the timing line immediately before its run line (now pinned by the route test); only a truncated log reaches it, and the fix adds a guard.
  - `[low]` `[patch]` (edge) A sampler-throw frame records detector/inference although the test KDoc says a skipped frame counts only in frames/analyzer — KDoc reworded (detector-skipped vs sampler-unreadable) and a test now pins the sampler-throw path (frames=1 read=0, detector and inference sampled, fit/sample/map `-`).
  - `[low]` `[reject]` (edge) A frame whose analyse throws is not recorded — such a frame propagates out of the analyzer; losing its timing sample is negligible next to the failure itself.
  - `[low]` `[reject]` (edge) A pause in analysis (confirm dialog, hold, background) stretches the window/run span and lowers fps — mid-scan pauses are uncommon in a Story 10.33 run and the end-of-scan hold ends the span at the last analysed frame; an active-time fps needs new gap logic.
  - `[low]` `[reject]` (edge) reset() leaves a part-filled window mixing two scans — one debug window line at most; fixing needs a new reset API.
  - `[low]` `[reject]` (edge) Finishing during a debug session baseline run reads the baseline engine's timings — developer-only recording tool edge; the fix adds engine-selection logic.
  - `[low]` `[reject]` (edge) Stale pending timing on an unmatched run line — same as the blind stale-pending row; same reason.
  - `[low]` `[reject]` (edge) Merged multi-device logs could cross-attach timings — evaluateQuickScan reads one device's logcat file per run set; keying by device adds logic for an unused workflow.
  - `[false]` `[reject]` (edge) The frame that latches detectorUnavailable loses its detector cost — the pipeline treats that frame as skipped (GH #390), matching the spec's "detector ran with a non-skipped result" rule for a read frame.
  - `[low]` `[patch]` (edge) Claim mismatch on sampler-failed frames in the pipeline test header — same root cause as the sampler-throw row; fixed by the same KDoc rewording and test.
  - `[medium]` `[patch]` (verification-gap) Activity ordering of the timing run line before the run line not verified — ContinuousQuickScanRouteTest's fake engine now returns a fixed timing body and a new test asserts exactly one timing run line immediately before the quickScan run line.
  - `[medium]` `[patch]` (verification-gap) PipelineEngine.timingRunLine and forBuild() default untested — ContinuousFrameTapTest now builds the module engine, processes one frame and asserts the run line starts with "frames=1 ".
  - `[low]` `[defer]` (verification-gap) Emitter format and parser linked only by a hand-copied regex — follows the established ScanDiagnosticsRunLineTest pattern (:app cannot depend on the evaluation source set); deferred.
  - `[low]` `[patch]` (verification-gap, other) Sampler-throw path unpinned — same root cause as the sampler-throw row; covered by the new pipeline test.
  - `[false]` `[reject]` (intent) Device readings not taken — the PO clarification routes them to a spec deferred entry, which exists; the issue stays open.
  - `[false]` `[reject]` (intent) No evidence the release no-op compiles — `./gradlew spotlessCheck build` and `-Pcubentia.release011=true :app:compileRelease011Kotlin` both passed in this run (camera + release ScanDiagnostics).
  - `[low]` `[reject]` (intent) Allocation test covers record() only — the pipeline's own timing path is primitive locals and fields plus NanoClock reads (no boxing); a pipeline-level allocation test would measure detector/mapper allocations unrelated to timing.
  - `[low]` `[reject]` (intent) analyzerMs times processFrame, not the whole CameraX callback — the difference is the YuvFrame wrapper and the frameDone forward, negligible against the stages; the spec defines analyzer as processFrame.
  - `[low]` `[reject]` (intent) "observations" sums the mapper's inputs, not the mapper's internal observation store — the issue lists it only as an example field; mapMs shows the #402 growth, and the store's size is internal to :core:domain.
  - `[false]` `[reject]` (intent) Run line is per engine life, not per Quick Scan run — the activity logs one run line per screen (quickLogged) from its one engine, so they coincide.
  - `[low]` `[defer]` (intent) Producer/parser agreement rests on a copied regex — same root cause as the verification-gap regex row; deferred with it.

### 2026-11-27 — Review pass (follow-up)
- verdicts: 25 findings — high 0, medium 0, low 15, false 10, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) fitNs left unset when sampling throws — carried: by spec fit/sample/map are recorded only for frames that reached the mapper; the sampler-throw path is pinned by ContinuousScanPipelineTimingTest.
  - `[false]` `[reject]` (blind) sampleNs unset when a sampler call throws mid-frame — carried: same rule as the row above; the frame still counts in frames/analyzer.
  - `[false]` `[reject]` (blind) Latch frame drops detector/inference cost — carried: the pipeline treats the latching frame as skipped (GH #390), matching "detector ran with a non-skipped result".
  - `[low]` `[reject]` (blind) A frame whose analyse throws is not recorded — carried: the exception propagates out of the analyzer; the lost sample is negligible next to the failure.
  - `[low]` `[reject]` (blind) The last partial window is never emitted — the run line covers every frame of the run, so no data is missing from evaluation; emitting a partial window needs a new flush API.
  - `[low]` `[reject]` (blind) Stale pending timing line can attach to a later run — carried: the debug build logs it immediately before its run line (route test); only a truncated log reaches it.
  - `[false]` `[reject]` (blind) runsMissingTimings can go negative for a hand-built Report — measure() is the only producer and attaches at most one RunTiming per run line, so runTimings.size <= runs always holds.
  - `[false]` `[reject]` (blind) Sampler-exception path has no assertions — ContinuousScanPipelineTimingTest already pins it (frames=1 read=0, detector and inference sampled, fit/sample/map `-`).
  - `[false]` `[reject]` (blind) Device readings not taken — carried: the PO clarification routes them to the deferred device-check entry; the issue stays open.
  - `[low]` `[reject]` (edge) frames/read above Int.MAX_VALUE make toInt() throw in measure — the emitter's counts are Int, so a real line cannot overflow; only a corrupted log could, and the existing run-line parsing uses the same toInt() convention; a guard adds a branch for an unreachable case.
  - `[low]` `[defer]` (verification-gap) Emitter format and parser linked only by a copied regex — carried: already in `deferred`; not deferred again.
  - `[false]` `[reject]` (intent) Device-matrix outcome not exercised — carried: deferred device check per the PO clarification.
  - `[low]` `[reject]` (intent) No test for the latch frame's timing — the latch skip itself is covered by the GH #390 tests and the timing path returns before any stage is stored; a new test adds no protection beyond those.
  - `[low]` `[reject]` (intent) Allocation test covers record() only — carried: the pipeline timing path is primitive locals and NanoClock reads.
  - `[low]` `[reject]` (intent) No concurrency test of the synchronized accumulator — plain synchronized methods; a two-thread test would be timing-dependent and flaky.
  - `[low]` `[reject]` (intent) Route test (fake body) and module-engine test are not combined — each half is tested; combining needs a real detector in the activity test.
  - `[low]` `[defer]` (intent) Producer/parser agreement rests on a copied regex — carried: same deferred entry as the verification-gap row.
  - `[low]` `[reject]` (intent) No activity-level zero-frame finish test — null body logging nothing and the missing-count are both tested; the activity glue is one null-safe call.
  - `[low]` `[reject]` (intent) Release "no accumulator" evidenced only by compile — forBuild() reads the release const QUICK_SCAN_TIMINGS = false; a release-variant test needs a new test source set.
  - `[low]` `[reject]` (intent) fps of a single short span is not a camera frame rate — the contract defines fps as frames over the span; Story 10.33 runs have many frames.
  - `[low]` `[reject]` (intent) Window-line logging cost falls outside analyzerMs — one Log.d at most every 2 s; negligible.
  - `[false]` `[reject]` (intent) Run line is per engine life, not per run — carried: one engine and one run line per screen, so they coincide.
  - `[false]` `[reject]` (intent) Report/markdown shape changes — the contract forbids changing existing fields' meaning, not adding fields; existing Report tests pass unchanged.
  - `[low]` `[reject]` (intent) fitted/observations parsed but dropped from RunTiming — carried: outside the report fields the spec names; the raw log keeps them.
  - `[false]` `[reject]` (intent) Spec file added under implementation-artifacts — that folder is not a planning artifact; the rule covers _bmad-output/planning-artifacts only.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- expected: BUILD SUCCESSFUL (camera + release no-op).

## Auto Run Result

Status: done

**Summary:** Debug builds time each continuous Quick Scan stage (detector, inference, fit, sample, map, analyzer) with an allocation-free accumulator, log a `quickScan timing window …` line at most every 2 s and a `quickScan timing run … device=…` line just before the `quickScan run` line; `evaluateQuickScan` attaches that line to its run and reports per-stage p50/p90/max, fps and runs missing timings. Release creates no accumulator (Refs #406).

**Files changed:**
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousStageTimings.kt` -- new accumulator, NanoClock, nearest-rank percentiles, line body.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- per-stage timing around detect/fit/sample/map; `timingRunLine()`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- PipelineEngine delegates `timingRunLine()`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `timingRunLine()` default null.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- logs the timing run line before `onQuickScanRun`.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt`, `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `QUICK_SCAN_TIMINGS`, window and run line (release no-op).
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/quickscan/QuickScanMeasurement.kt` -- parses timing run lines; `runTimings`, missing count, markdown table.
- Tests: `ContinuousStageTimingsTest`, `ContinuousScanPipelineTimingTest`, `ScanDiagnosticsTimingLineTest` (new); `ContinuousQuickScanRouteTest`, `ContinuousFrameTapTest`, `QuickScanMeasurementTest` (extended).

**Review:** 27 findings; patched 4 rows in 3 entries (2 medium: activity-level ordering test, module-engine run-line test; 1 low: sampler-throw KDoc and test); deferred 1 (copied parser regex, low); rejected 22 with reasons in the triage log (8 false, 14 low).

**Follow-up review recommended: true** -- two medium entries were patched on a first pass; the unverified risk is the new activity-level route test (fake engine timing body), which only this pass's verification run has exercised.

**Verification:** `./gradlew --no-daemon spotlessCheck build` and `./gradlew --no-daemon -Pcubentia.release011=true :app:compileRelease011Kotlin` both BUILD SUCCESSFUL after the patches.

**Residual risks:** the Lenovo and S24 FE device readings are owed (deferred entry; the issue stays open); fps over a span that includes an analysis pause reads low (rejected low, see triage log).

### Follow-up review pass (2026-11-27)

**Summary:** A follow-up review of the GH #406 per-stage timings change (Codex blind, edge-case and verification-gap layers plus the Claude intent-alignment layer). No code changed in this pass.

**Review:** 25 findings: 0 patched, 0 newly deferred (the copied-regex rows were carried onto the existing deferred entry), and 23 rejected with reasons in the triage log (10 false, 13 low).

**Follow-up review recommended: false.** This was a follow-up pass and it patched no high finding, so the work has converged.

**Verification:** No code changed, so the earlier `./gradlew --no-daemon spotlessCheck build` and `-Pcubentia.release011=true :app:compileRelease011Kotlin` results still hold for this tree.

**Residual risks:** The Lenovo and S24 FE device readings are still owed (deferred entry). The emitter and the parser are still linked only by a copied regex (deferred, low).
