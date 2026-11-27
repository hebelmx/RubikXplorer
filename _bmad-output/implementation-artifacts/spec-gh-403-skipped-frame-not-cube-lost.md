---
title: 'Continuous Quick Scan: a frame the detector skipped is a skipped frame, not "cube lost" (#403)'
type: 'bugfix'
created: '2026-11-27'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
baseline_revision: '2f2f5c72a5bbe32152ca87eb2912fba22b99c148'
---

<intent-contract>

## Intent

**Problem:** When `LiteRtStickerCenterDetector.detect()` skips a frame it cannot read (`fillYuvInput` returns null, or conversion/decoding throws and is caught), it returns `StickerCenterDetection.EMPTY`, which `ContinuousScanPipeline.processFrame` cannot tell from "no cube in view". The pipeline feeds the mapper an empty frame, the mapper's `emptyStreak` grows, and three in a row clear the tracked faces, so the next good frame starts a pending fragment instead of continuing the master (GitHub issue #403). This contradicts the #389/#397 rule that a skipped frame does not change the scan.

**Approach:** Give `StickerCenterDetection` a `skipped` flag (default false) and a `SKIPPED` constant. The LiteRT detector returns `SKIPPED` (and counts the frame once) on both skip paths; the pipeline returns `skippedFrame(frameIndex)` for a skipped detection without calling the mapper or `tap.onFrame`.

## Boundaries & Constraints

**Always:** A skipped frame leaves `lastSnapshot` and the mapper unchanged (no `processFrame` call, `emptyStreak` untouched) and does not latch `unavailable`. The tap sees `onSkippedFrame`, never `onFrame`, for it. Each skipped frame is counted exactly once, in the detector's existing failed-frame count (`recordFailedFrame`, reaching `ScanDiagnostics.onQuickScanFrameFailed`); the pipeline does not count it again. A real empty view (good frame, no peaks) still reaches the mapper as an empty frame. The mapper's streak limits (3 empty, 5 twist, 2 outline frames) stay frame counts (PO decision). Every commit subject carries `#403`.

**Never:** No change to the mapper's tracking logic or limits; no time-based streaks. No change to `ContinuousFaceSampler`, `StickerCenterModelTransform`, `ScanDiagnostics` or `ScanFrameSource`. The `ensureSession`/`runInference` null paths keep returning `EMPTY` (they latch `unavailable`, which the pipeline already handles). `detectRgb` is unchanged. No new counter. Do not edit `sprint-status.yaml` or anything under `_bmad-output/planning-artifacts/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Good frame | detector returns peaks | fitted, sampled, mapped as today; tap `onFrame` | No error expected |
| Real empty view | good frame, zero peaks (`skipped` false) | mapper gets an empty frame as today (`emptyStreak` +1) | — |
| Detector skipped (throw) | Y plane too short for `fillYuvInput` (e.g. `limit(1)`) | `detect` returns a detection with `skipped` true; counted once; pipeline returns `skippedFrame`: last snapshot (same instance), no fitted faces/overlays, tap `onSkippedFrame`, mapper not called | caught in `detect`, counted |
| 3 skipped between good frames | good, good, skip, skip, skip, good | tracked faces and `emptyStreak` unchanged across the skips; the last good frame continues the master (no pending fragment), equal to a control run without the skips | — |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- `StickerCenterDetection` data class (~L22-37) and its `EMPTY` companion constant. Add `val skipped: Boolean = false` (KDoc: the detector could not read this frame, GH #403; not an empty view) and `SKIPPED = StickerCenterDetection(emptyList(), 0, 0, 0.0, skipped = true)`. Update the `detect` KDoc on the interface to say a frame it could not read returns `SKIPPED`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `detect()` (~L87-132): the `fillYuvInput(...) ?: return EMPTY` (L102, today not counted) becomes `recordFailedFrame()` + `SKIPPED`; the `catch (_: Exception)` (L126-130) returns `SKIPPED` instead of `EMPTY`. `recordFailedFrame()` (L140) is the shared count helper; caller holds `lock` (it does inside `synchronized(lock)`). Leave the invalid-crop (L95), `ensureSession` (L98) and `runInference` (L103) returns as `EMPTY`. Update the class KDoc.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `processFrame` (~L46-109): after the `detectorUnavailable` check at L58 add `if (detection.skipped) return skippedFrame(frameIndex)` (no `countFailedFrame`: the detector already counted). Update `skippedFrame` KDoc (~L111) to name a frame the detector skipped.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrameTap` KDoc (~L62-69) lists what a skipped frame is; add "a frame the detector could not read". KDoc only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- private `emptyStreak` (L179), incremented at L222, clears tracked at >= 3 (L223). Add a public read-only `val emptyFrameStreak: Int get() = emptyStreak` next to the existing read-only diagnostics (`reappearanceMergeCount`, `masterLogAwb`, ~L184-188) so the test asserts the streak directly. No logic change.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineShortFrameTest.kt` -- reuse pattern: `CountingDetector` fake returning a face's `gridCenters` as peaks, `RecordingTap`, `goodFrame()`, `faceFor`, rendered `GOOD` planes. L174 asserts `detect(short) == EMPTY`: must become `SKIPPED` / `skipped` true.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorBadFrameTest.kt` -- L25, L36, L53 assert `EMPTY` for a `limit(1)` short frame: change to `SKIPPED`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- `CountingStickerCenterSession` (L347) gives a real `LiteRtStickerCenterDetector` with a fake session; check its asserts on `EMPTY` for skipped frames and update those that now get `SKIPPED`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/ContinuousSessionWriter.kt` -- read-only; records via `onSkippedFrame`, unaffected.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayTest.kt` -- read-only golden replay of the mapper; must pass unchanged (zero diffs).

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- add `skipped` + `SKIPPED`, KDoc -- the pipeline can tell a skipped frame from an empty view.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- return `SKIPPED` on both skip paths, count the null-fill path too -- one count per skipped frame.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- skip on `detection.skipped` -- mapper and `tap.onFrame` untouched.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- KDoc only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- read-only `emptyFrameStreak` getter -- test observability.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineSkippedFrameTest.kt` -- new plain JUnit 5 test: (a) a fake detector returning `SKIPPED` on chosen frames, a pipeline built with an explicit `ContinuousFaceletMapper`; frames good, good, skip x3, good: assert each skipped frame returns the same snapshot instance, tap events `frame 0, frame 1, skipped 2, skipped 3, skipped 4, frame 5`, `mapper.emptyFrameStreak` 0 before and after the skips, tracked quads unchanged, frame 5 has `pendingFragmentFaces` 0 and equals a control run on frames 0, 1, 5; (b) contrast: the same sequence with real empty detections (`skipped` false, no peaks) raises `emptyFrameStreak` to 3 and clears tracking (proves the test sees the bug path); (c) the real `LiteRtStickerCenterDetector` (via `CountingStickerCenterSession`) in a pipeline: a `limit(1)` short frame returns `skippedFrame`, tap `onSkippedFrame`, `failedFrames` 1 (not 2), `detectorUnavailable` false.
- Existing tests in `LiteRtStickerCenterDetectorBadFrameTest.kt`, `ContinuousScanPipelineShortFrameTest.kt`, `LiteRtStickerCenterDetectorFailureTest.kt` -- update `EMPTY` expectations for skipped frames to `SKIPPED`.

**Acceptance Criteria:**
- Given the change, when `ContinuousSessionReplayTest` runs, then every Story 10.34 golden session replays with zero diffs.
- Given the change, when `./gradlew spotlessCheck build` runs, then it succeeds.
- Given the change, when `git diff` is read, then only the files above changed, and neither `sprint-status.yaml` nor `_bmad-output/planning-artifacts/` did.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 20 findings — high 0, medium 0, low 13, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) The null-`fillYuvInput` half of Done-when (1) has no test — the path is unreachable after `detect`'s crop guard (letterbox fails only on non-positive dims); a test would need a new seam in `StickerCenterModelTransform`, which the spec forbids; the change is two lines read in the diff.
  - `[false]` `[reject]` (intent) "Inference threw" goes through `unavailable`, not `SKIPPED` — `runInference` catches its own failure and latches the detector (#390); the pipeline's existing `detectorUnavailable` check after `detect` already returns `skippedFrame` without the mapper, so the frame does not count as an empty view.
  - `[false]` `[reject]` (intent) The 3-skip test uses a scripted fake — the real detector's skip reaching the pipeline is covered by the separate LiteRT test (`failedFrames` 1, tap `skipped 1`, streak unchanged); together they cover Done-when (1) and (2).
  - `[false]` `[reject]` (intent) The golden replay cannot see the fix — Done-when (4) asks for it as a no-regression check on the mapper, which it is (13 tests, 0 failures).
  - `[false]` `[reject]` (intent) The streak-limit note is outside the diff — Done-when (3) asks for it in the spec; it is in this spec's Boundaries and Design Notes.
  - `[low]` `[reject]` (intent) Public `emptyFrameStreak` getter is new API — read-only, next to the mapper's existing read-only diagnostics; it lets the test assert `emptyStreak` directly as Done-when (2) names it. Removing it would weaken the test.
  - `[false]` `[reject]` (intent) The recording now marks these frames skipped — this is what Done-when (1) asks (`onSkippedFrame`, no `onFrame`).
  - `[low]` `[patch]` (edge) The interface KDoc promised `SKIPPED` for any unread frame while a degenerate crop returns `EMPTY` — KDocs on `detect`, `skipped` and `SKIPPED` now name exactly the SKIPPED cases and say a degenerate crop or an unavailable detector returns `EMPTY`.
  - `[low]` `[reject]` (edge) `skippedFrame` still reports `detection = EMPTY` — no production code reads `ContinuousPipelineFrame.detection` (`PipelineEngine` keeps only snapshot and overlays; device tests only read peaks/inferenceMs); changing it would alter all skip kinds and existing assertions.
  - `[low]` `[reject]` (blind) Endless skips freeze the scan on a stale snapshot — same rule as #389/#397 (a skipped frame does not change the scan); no device is known to deliver only unreadable frames; a cap adds state beyond "nothing more".
  - `[low]` `[reject]` (blind) `skippedFrame` drops the `SKIPPED` marker — same as the edge row; no consumer.
  - `[low]` `[patch]` (blind) KDocs disagree about what returns `SKIPPED` — same root cause as the edge KDoc row; same patch.
  - `[false]` `[reject]` (blind) Null-fill now counted and logged is dead code that changes diagnostics — Done-when (1) requires skipped frames, including null fill, to be counted.
  - `[low]` `[reject]` (blind) Nothing forbids `skipped = true` with peaks — only the `SKIPPED` constant sets it; a `require` or sealed type adds complexity for a state no code makes.
  - `[low]` `[patch]` (blind) `emptyFrameStreak` KDoc hard-codes "clears at 3" — clause removed; the getter itself kept (see intent row).
  - `[low]` `[patch]` (blind) No test that a skip inside a run of real empties neither resets nor bumps the streak — added test: good, good, empty, empty, SKIPPED, empty; streak 1, 2, 2, 3; tracking cleared on the last.
  - `[low]` `[reject]` (blind) The new test copies fixtures — moving them to a shared file is a refactor, not a direct correction (same call as #397).
  - `[low]` `[reject]` (blind) No pipeline-level check of the diagnostics log line — the detector's Robolectric test checks the log per count, and the pipeline test checks the count is 1; a Robolectric pipeline test adds nothing new.
  - `[low]` `[patch]` (blind) `ContinuousFrameTap` KDoc line too long, list without "or" — rewrapped to the block width and fixed the list.
  - `[false]` `[reject]` (blind) The spec is not in the reviewed diff — by design: the spec is the claims file, given only to the edge-case layer.

## Design Notes

A flag on the detection (not a null return) keeps `detect`'s non-null contract and every fake detector compiling; `EMPTY` stays the "nothing seen / not run" value. The pipeline does not call `countFailedFrame` for a skipped detection because the detector already counted it under its lock.

Streak limits: the mapper's 3-empty / 5-twist / 2-outline limits remain frame counts (PO decision on #403), so their duration still depends on the device's analysis rate; expressing them in time is out of scope.

`fillYuvInput` returns null only when the crop or letterbox is degenerate, which the earlier crop guard already rejects, so the null path has no reachable JVM test and no matrix row; it is checked at review by reading the diff.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- before every Gradle command.
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.*'` -- expected: BUILD SUCCESSFUL, new tests ran.
- `./gradlew :core:domain:test --tests '*ContinuousSessionReplayTest*' --tests '*ContinuousFaceletMapperTest*'` -- expected: BUILD SUCCESSFUL, zero replay diffs.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- expected: BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (`spotlessApply` to fix formatting).

## Auto Run Result

Status: done

**Summary:** `StickerCenterDetection` has a `skipped` flag and a `SKIPPED` constant. `LiteRtStickerCenterDetector.detect` returns `SKIPPED` when `fillYuvInput` returns null (now counted too) or when conversion/decoding throws (counted as before), so each skipped frame is counted once in the existing failed-frame count. `ContinuousScanPipeline.processFrame` returns `skippedFrame` for a skipped detection: last snapshot, mapper not called, tap `onSkippedFrame` (never `onFrame`), no second count. A real empty view still reaches the mapper. Streak limits stay frame counts (PO decision).

**Files changed:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- `skipped` field, `SKIPPED` constant, KDoc.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- both skip paths return `SKIPPED`; null fill counted.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- skip on `detection.skipped`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrameTap` KDoc only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- read-only `emptyFrameStreak` getter.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipelineSkippedFrameTest.kt` -- new: 3 skips between good frames (streak 0, same snapshot, equal to control, no pending fragment); 3 real empties clear tracking; skip inside a run of empties leaves the streak alone; real LiteRT short frame skipped and counted once.
- `LiteRtStickerCenterDetectorBadFrameTest.kt`, `LiteRtStickerCenterDetectorFailureTest.kt`, `ContinuousScanPipelineShortFrameTest.kt` -- skipped-frame expectations now `SKIPPED`.

**Implementation:** a Claude subagent (`build-implement.result`: `claude (guard)`); it also applied the review patches.

**Review findings:** 20 findings from four layers (intent 7, edge 2, blind 11, verification-gap 0). Patched 4 entries (6 rows, all low: 3 KDoc, 1 test). Deferred: none. Rejected: 14 rows (7 low, 7 false); reasons in the Review Triage Log.

**Follow-up review recommendation:** false -- patched entries: high 0, medium 0, low 4.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.*'` -- BUILD SUCCESSFUL; `ContinuousScanPipelineSkippedFrameTest` 4 tests, 0 skipped, 0 failures.
- `./gradlew :core:domain:test --tests '*ContinuousSessionReplayTest*' --tests '*ContinuousFaceletMapperTest*'` -- BUILD SUCCESSFUL; `ContinuousSessionReplayTest` 13 tests, 0 failures (zero replay diffs).
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (after `spotlessApply` on the patched test).
- Every I/O matrix row is covered by a test that ran and passed.

**Residual risks:**
- The null-`fillYuvInput` path is unreachable from JVM tests; checked only by reading the code.
- A device that only ever delivers unreadable frames would keep the last snapshot with no cue (same as #397; out of scope).
- Not run on a physical device.
