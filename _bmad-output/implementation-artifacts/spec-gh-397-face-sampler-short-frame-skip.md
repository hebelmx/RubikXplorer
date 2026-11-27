---
title: 'Continuous Quick Scan: skip a frame the face sampler cannot read instead of crashing the analyzer (#397)'
type: 'bugfix'
created: '2026-11-27'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
baseline_revision: '093dc0714c188e8fcf78b5fa7d0a72041fd755cc'
---

<intent-contract>

## Intent

**Problem:** `ContinuousFaceSampler.warpFromYuv` reads the YUV planes with absolute `ByteBuffer.get` and no guard, and `ContinuousScanPipeline.processFrame` calls `sampler.sample` outside `detect()`. `fillYuvInput` samples at the 256-px model size and never reads the last source rows, so a plane buffer short only in those rows passes the #389 guard; the sampler then throws when a fitted face reaches them, and the uncaught exception ends the continuous Quick Scan (GitHub issue #397, tracker `issue-389-deferred-face-sampler-short-frame-crash`).

**Approach:** In `ContinuousScanPipeline.processFrame`, catch an `Exception` thrown while sampling the fitted faces and return `skippedFrame(frameIndex)` (last snapshot, mapper untouched, `tap?.onSkippedFrame`). The failure is counted by the detector's existing failed-frame count through a new default-no-op `StickerCenterDetector` method, so it reaches `ScanDiagnostics.onQuickScanFrameFailed` with no second counter.

## Boundaries & Constraints

**Always:** A skipped frame leaves `lastSnapshot` and the mapper unchanged and does not latch `unavailable` (#390 rule). The tap sees `onSkippedFrame` and never `onFrame` for that frame (Story 10.34 contract). The failure path never throws: the diagnostics call stays inside `runCatching`. One failed-frame count per process: sampler failures and #389 detect failures add to the same `LiteRtStickerCenterDetector` counter. Catch `Exception` only, never `Throwable`/`Error`. The debug `ScanDiagnostics` and its release twin keep compiling unchanged. Every commit subject carries `#397`.

**Never:** No change to `ContinuousFaceSampler` (no buffer pre-validation), `StickerCenterModelTransform`, the mapper, or `ScanDiagnostics` signatures. No analyzer-level catch in `ScanFrameSource` (allowed only as a backstop; not needed). No new counter, diagnostics function, production file or constructor parameter. Do not edit `sprint-status.yaml` or anything under `_bmad-output/planning-artifacts/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Good frame | valid planes, fitted face | sampled and mapped as today; failed count unchanged | No error expected |
| Short last rows | frame whose Y buffer `limit` stops a few rows before the crop bottom; detector returns centers of a face reaching those rows | `processFrame` returns without throwing: last snapshot (same instance), `EMPTY` detection, no fitted faces, no overlays; `tap.onSkippedFrame(i)`, no `onFrame`; `detectorUnavailable` false; detector told once | caught in the pipeline, counted |
| Next valid frame | the same face on a full buffer right after | processed normally; tap sees `onFrame`; snapshot advances | — |
| Error from sampling | an `Error` thrown during sampling | propagates, not counted | not caught |

</intent-contract>

## Code Map

- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `processFrame`: the `fittedFaces.mapNotNull { sampler.sample(...) }` block (~L64-69) runs after `detect()` and before `mapper.processFrame`. Wrap only that block; on `Exception` call `detector.countFailedFrame()` then `return skippedFrame(frameIndex)`. `skippedFrame` (~L104) already returns `lastSnapshot` and calls `tap?.onSkippedFrame`; update its KDoc to name the sampler failure.
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- interface (~L45-66). Add `fun countFailedFrame() = Unit` with KDoc: a frame read past `detect` that the pipeline could not sample (GH #397); counted with the detector's own bad frames; same thread as `detect`; never throws.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `failedFrameCount` (guarded by `lock`) and the catch in `detect()` (~L125-129) that increments and calls `runCatching { ScanDiagnostics.onQuickScanFrameFailed(failedFrameCount) }`. Move that increment-and-report into one private helper used by both the catch and the `countFailedFrame` override (under `lock`). Update class KDoc.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrameTap` KDoc (~L62-69) lists what a skipped frame is; add "a frame it cannot sample".
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt` -- read-only. `warpFromYuv` (~L120-230) clamps sample coordinates to the crop but not to the buffer limit, so `yBuf.get(row * rowStride + col)` throws `IndexOutOfBoundsException` past `limit`. `sampledCellsInside` checks only the cell ROI against the crop.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSamplerTest.kt` -- `renderGridRgb`, `rgbToYuv`, `faceFor` and the fake `StickerCenterDetector` returning a face's `gridCenters` as peaks (~L184-240): reuse the pattern (helpers are private; copy what is needed). Two frames of one face give a tracked quad.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousFrameTapTest.kt` -- `RecordingTap` pattern (its `onFrame` asserts empty observations, so the new test needs its own tap).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- `CountingStickerCenterSession` and `failedFrames` accessor; plain JUnit 5, where `android.util.Log` throws.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` (~L373 analyzer) -- read-only; unchanged.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- add `countFailedFrame()` default no-op -- lets the pipeline count without a second counter.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- override it through the shared increment-and-report helper -- one count reaches `ScanDiagnostics.onQuickScanFrameFailed`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- guard the sampling block as in the Code Map -- the frame is skipped, not fatal.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- KDoc only.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineShortFrameTest.kt` -- new plain JUnit 5 test covering every matrix row: build a frame (e.g. 240x240) and a face whose warp reaches the last rows, first assert `ContinuousFaceSampler().sample` itself throws on the short buffer (proves the path is exercised), then drive the pipeline with a recording tap: good, good, short, good; assert the matrix outcomes, tap events `frame 0, frame 1, skipped 2, frame 3`, same snapshot instance for frame 2, `detectorUnavailable` false, and a counting fake detector told exactly once. Add a test that `LiteRtStickerCenterDetector.countFailedFrame()` adds to `failedFrames` without throwing on plain JVM, and one that an `Error` from sampling is not caught.

**Acceptance Criteria:**
- Given the change, when `git diff` is read, then only the files listed above changed, and neither `sprint-status.yaml` nor `_bmad-output/planning-artifacts/` did.
- Given the 0.11.0 release profile, when `:app:compileRelease011Kotlin` runs with `-Pcubentia.release011=true`, then the continuous code compiles against the release no-op `ScanDiagnostics`.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 24 findings — high 0, medium 0, low 13, false 11, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) The analyzer in `ScanFrameSource` is not changed or tested; only the pipeline is — the PO's Done-when (1) puts the catch in `ContinuousScanPipeline` around `sampler.sample` and allows an analyzer catch only as an optional backstop; the pipeline now returns normally, so `read` goes on to `frameDone` and `deliver` unchanged.
  - `[false]` `[reject]` (intent) An `Error` still escapes and ends the analyzer — the short-buffer case throws `IndexOutOfBoundsException` (an `Exception`); catching `Exception` only matches the #389 rule, and the spec's Always forbids catching `Error`.
  - `[low]` `[patch]` (intent) The test frame (240x240, 12 rows short, fake detector) does not show the #397 condition: a frame that passes the real #389 guard — the test now uses a 1280x960 frame short only in its last Y row, checks the real `LiteRtStickerCenterDetector` passes it (failedFrames 0, one inference), and that the sampler then throws on it.
  - `[low]` `[patch]` (intent) Nothing checks that `ScanDiagnostics` is actually called for the sampler path — same root cause as the verification-gap row; fixed there.
  - `[false]` `[reject]` (intent) `frameDone` is not exercised for a skipped frame — `frameDone` is called by `ScanFrameSource`, which the diff does not touch, and the pipeline's tap contract for a skipped frame (`onSkippedFrame`, no `onFrame`) is asserted.
  - `[low]` `[patch]` (gap) A sampler failure reaching `ScanDiagnostics.onQuickScanFrameFailed` is not verified — added a Robolectric test in `LiteRtStickerCenterDetectorBadFrameTest`: `countFailedFrame()` logs `quickScan frameFailed count=1`, and a following #389 bad frame logs `count=2`.
  - `[false]` `[reject]` (edge) Catching every `Exception` hides sampler logic bugs — the PO's Done-when (1) says "a sampler.sample failure"; `Exception` matches the #389 catch in `detect()`, and the spec fixes `Exception` as the boundary.
  - `[false]` `[reject]` (edge) One bad face drops the whole frame, including faces that were read — Done-when (1) asks that the failing frame "returns skippedFrame"; keeping part of a frame would feed the mapper a frame that was not fully read.
  - `[low]` `[reject]` (edge) A device that always delivers short planes would stall the scan on a stale snapshot — no device is known to produce such frames (issue text); a stall counter or cue adds state and UI beyond the intent's "do exactly this".
  - `[false]` `[reject]` (edge) Exception-driven control flow; pre-validate the buffer limit instead — the spec's Never forbids buffer pre-validation in the sampler, and the intent names the catch around `sampler.sample` as the fix.
  - `[low]` `[patch]` (edge) The Error test errors on Windows, where `setLength` on a mapped file throws — moved the map-and-truncate setup into `runCatching` with `assumeTrue` on its success.
  - `[low]` `[reject]` (edge) The Error test may skip silently on a JVM that does not fault — it ran (0 skipped) here, and CI is Linux; a deterministic seam needs a sampler change or a constructor parameter, which the spec forbids.
  - `[false]` `[reject]` (blind) One bad face drops the whole frame — same as the edge row; required by Done-when (1).
  - `[low]` `[reject]` (blind) `countFailedFrame()` runs unguarded in the catch — the only implementation runs under its lock and wraps the diagnostics call in `runCatching`; the default is `Unit`. A guard for a hypothetical throwing implementation is added complexity.
  - `[low]` `[patch]` (blind) The test does not reproduce the real #397 condition — same root cause as the intent row; same patch.
  - `[low]` `[reject]` (blind) Only a short Y plane is tested, not U/V — a short chroma plane throws the same `IndexOutOfBoundsException` from the same `sample` call into the same catch; another case adds no new path.
  - `[low]` `[patch]` (blind) "Mapper untouched" is shown only by the returned snapshot — added a control pipeline fed frames 0, 1 and 3; its last snapshot equals the tested pipeline's frame-3 snapshot (data equality).
  - `[low]` `[patch]` (blind) The Error test breaks off Linux — same root cause as the edge row; same patch.
  - `[low]` `[reject]` (blind) The Error test is heavy and JVM-dependent — same as the edge row; a deterministic seam is forbidden by the spec.
  - `[false]` `[reject]` (blind) The cause of a failure is lost — #389's diagnostics rule is numbers only, with no exception text; the catch matches it on purpose.
  - `[false]` `[reject]` (blind) The user gets no signal when this keeps happening — same claim as the edge stall row; no device produces such frames, and a cue is outside the intent.
  - `[false]` `[reject]` (blind) Pipeline accounting on the detector interface; a decorator would drop the count — no delegating `StickerCenterDetector` exists (the only production binding is `LiteRtStickerCenterDetector`); the PO's "no parallel counter" puts the count with the detector's.
  - `[low]` `[reject]` (blind) Test fixtures are copied from `ContinuousFaceSamplerTest` — the spec allows copying; moving the helpers into a shared fixture is a refactor, not a direct correction.
  - `[false]` `[reject]` (blind) The new test file has LF line endings — `spotlessCheck` passes, and `* text=auto` stores LF in the index like every other file.

## Design Notes

The PO's "no parallel counter" rule: the detector is a process `@Singleton` and already owns the failed-frame count that #389 reports, so the pipeline tells the detector instead of keeping its own. A default interface method keeps every fake detector compiling. The catch sits around sampling only, so a detector or mapper failure is not masked.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- set before every Gradle command.
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.*'` -- BUILD SUCCESSFUL, new tests ran and passed.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (fix formatting with `./gradlew spotlessApply`).

## Auto Run Result

Status: done

**Summary:** `ContinuousScanPipeline.processFrame` now catches an `Exception` thrown while sampling the fitted faces. That frame is skipped (`skippedFrame`: the last snapshot, the mapper untouched, `tap?.onSkippedFrame`), and `detectorUnavailable` is not latched. The failure is counted by the detector's existing failed-frame count through a new default-no-op `StickerCenterDetector.countFailedFrame()`, so it reaches `ScanDiagnostics.onQuickScanFrameFailed` with no second counter. No change to `ScanFrameSource`, the sampler or `ScanDiagnostics`.

**Files changed:**
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- catch around sampling, counts and skips; KDoc.
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- `countFailedFrame()` default no-op.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `countFailedFrame` override and shared `recordFailedFrame()` helper; KDoc.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrameTap` KDoc only.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineShortFrameTest.kt` -- new: a 1280x960 frame short only in its last Y row passes the real detector, makes the sampler throw, and is skipped by the pipeline (tap `frame 0, frame 1, skipped 2, frame 3`, counted once, map equal to a control run without it); the LiteRT count is shared with #389 bad frames; an `Error` is not caught.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorBadFrameTest.kt` -- new Robolectric test: `countFailedFrame()` reaches the `CubentiaScanDiag` log.

**Implementation:** a Claude subagent wrote the change (`build-implement.result`: `claude (guard)`); it also applied the review patches.

**Review findings:** 24 findings from four layers (intent 5, verification-gap 1, edge-case 6, blind 12). Patched 9 rows (4 entries, all low, all test-only). Deferred: none. Rejected: 15 rows (6 low, 9 false); each reason is in the Review Triage Log.

**Follow-up review recommendation:** false -- patched entries: high 0, medium 0, low 4.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.*'` -- BUILD SUCCESSFUL; JUnit XML: `ContinuousScanPipelineShortFrameTest` 4 tests and `LiteRtStickerCenterDetectorBadFrameTest` 2 tests, 0 skipped, 0 failures.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL after the review patches.
- Every I/O matrix row has a test that ran and passed.

**Residual risks:**
- No analyzer-level backstop: an `Error`, or an exception from outside sampling, still ends the analyzer (out of scope by the intent).
- The `Error` test depends on the JVM turning a fault on a truncated memory-mapped file into `InternalError`; elsewhere it skips.
- Not run on a physical device; no device is known to produce such frames.
