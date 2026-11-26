---
title: 'Continuous Quick Scan: skip a bad camera frame instead of crashing the analyzer (#389)'
type: 'bugfix'
created: '2026-11-26'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      A plane buffer that is short only in its last rows passes fillYuvInput, and ContinuousFaceSampler.sample can then throw outside detect(), which still crashes the analyzer.
    evidence: |-
      fillYuvInput samples the crop bilinearly at the 256-pixel model size, so it never reads the last source rows (a 1280x720 frame at 0 degrees is read only through row 718). ContinuousFaceSampler.warpFromYuv reads the planes with absolute ByteBuffer.get and no try, and ContinuousScanPipeline.processFrame calls it outside detect(); the ScanFrameSource analyzer is still try/finally with no catch. Pre-existing and outside issue #389, which names fillYuvInput and decodePeaks in detect() only. Needs a frame short by a few rows plus a fitted face that reaches them. Reported by the blind and edge-case review layers.
    location: >-
      app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt:182
    severity: medium
baseline_revision: '1a289845e44df132daf519e013e5ea96762e844d'
---

<intent-contract>

## Intent

**Problem:** On the continuous Quick Scan path, `LiteRtStickerCenterDetector.detect()` calls `StickerCenterModelTransform.fillYuvInput` and `decodePeaks` outside any `try`, and the `ScanFrameSource` analyzer is `try/finally` with no `catch`. One short or malformed YUV plane buffer therefore throws on the analyzer executor and crashes the app mid-scan (GitHub issue #389, tracker `epic-10-deferred-10.31-fill-yuv-input-uncaught`, release-blocking for `QUICK_SCAN` BETA).

**Approach:** In `detect()`, a frame whose input fill or peak decoding throws is skipped: `detect` returns `StickerCenterDetection.EMPTY`, keeps the inference session open, counts the failed frame, and reports the count to `ScanDiagnostics` (debug logs numbers only; release is a no-op). Tests drive too-short plane buffers.

## Boundaries & Constraints

**Always:** The failure path itself never throws: a failing diagnostics call is swallowed. A failed frame leaves the session open and usable (no latch, no close, no reload). The inference-failure path keeps today's behavior (latch disabled, not counted as a failed frame). The debug main-thread guard (`check`) stays outside the guarded block and still throws. Diagnostics carry fixed words and the count only: no exception message, frame, pixel or buffer data. The debug `ScanDiagnostics` and its release twin get the same new function signature. Every commit subject carries `#389`.

**Never:** Do not change `ScanFrameSource`, `ContinuousScanPipeline`, `ContinuousFaceSampler`, `detectRgb`, or the bodies of `fillYuvInput` / `decodePeaks` (no buffer pre-validation). Do not change the `quickScan run` line. Do not catch `Throwable` or `Error` around the frame body. No new production file, interface or constructor seam. Do not edit `_bmad-output/implementation-artifacts/sprint-status.yaml` or anything under `_bmad-output/planning-artifacts/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Good frame | valid planes, healthy session | inference runs once; detection decoded as today; failed count unchanged | No error expected |
| Short Y plane | 64×48 crop, Y buffer shorter than the crop needs; rotations 0, 90, 180, 270 | `StickerCenterDetection.EMPTY`, no throw, inference not run for that frame, failed count +1 | caught and counted; session kept |
| Short U/V plane | same crop, chroma buffer too short | same as above | same as above |
| Good after bad | bad frame, then a valid frame, same detector | the valid frame runs inference; session opened once, never closed | not latched |
| Repeated bad frames | 5 bad frames in a row | failed count 5; debug log has `quickScan frameFailed count=1`, `count=2`, `count=4` only | log throttled to powers of two |
| Inference failure | `session.run` throws | EMPTY, detector latched disabled (unchanged); failed count unchanged | unchanged |
| Diagnostics unavailable | bad frame on a plain JVM where `android.util.Log` throws | EMPTY, no throw, failed count +1 | diagnostics failure swallowed |
| Pipeline goes on | `ContinuousScanPipeline.processFrame` with a bad frame, then a valid one | first returns a frame with EMPTY detection and no fitted faces; second is processed normally; neither throws | — |

</intent-contract>

## Code Map

- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `detect()` (~L72-108): `ensureSession()` → `fillYuvInput` → `runInference` (own try/catch, latches via `latchDisabled()`) → `decodePeaks`, all inside `synchronized(lock)`. Put fill → inference → decode in one guarded block catching `Exception`; keep `ensureSession()` and the `check(...)` guard outside it. Add a failed-frame `Int` counter (guarded by `lock`) with an `internal` read-only accessor for tests, and report it with `runCatching { ScanDiagnostics.… }`. Update the class KDoc.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelTransform.kt` -- read-only. `fillYuvInput` reads planes with absolute `ByteBuffer.get(index)` (throws `IndexOutOfBoundsException` on a short buffer) and `require`s the output size; it fully rewrites `inputBytes` and the scratch LUTs each call, so a half-filled buffer never leaks into the next frame. `decodePeaks` has no input that throws today.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- debug twin; `TAG = "CubentiaScanDiag"`; existing space-separated line `quickScan torch hasFlash=… torch=…` (`onTorch`) is the format precedent. Add `onQuickScanFrameFailed(failedFrames: Int)`.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- release no-op twin (also compiled into `benchmark`, `cornerR8`, `release011`); add the same signature `= Unit`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` (~L373-377 analyzer, ~L397-406 `read`) -- read-only evidence of the uncaught path.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- read-only: an EMPTY detection fits no faces, skips the sampler, and still advances the mapper.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- plain JUnit 5 (no Robolectric: `Log` throws there); has `makeBlankYuv` and the fake `StickerCenterInferenceSession` pattern to reuse.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorThreadGuardTest.kt` -- Robolectric JUnit 4 pattern (`@Config(application = Application::class)`).
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsRunLineTest.kt` -- `ShadowLog.getLogsForTag(ScanDiagnostics.TAG)` pattern for asserting diagnostics lines.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt` -- read-only: `release` has `QUICK_SCAN` OFF, so only `-Pcubentia.release011=true` compiles the detector against the release twin.

## Tasks & Acceptance

**Execution:**
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- add `onQuickScanFrameFailed(failedFrames: Int)`: logs `quickScan frameFailed count=<failedFrames>` under `TAG` only when `failedFrames` is a positive power of two; KDoc says why (a stream of bad frames must not fill the log buffer) -- the count reaches the debug diagnostics.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- add the matching no-op -- release twin compiles.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- guard the per-frame body as described in the Code Map; on a caught `Exception` increment the counter, report it, return `StickerCenterDetection.EMPTY` -- one bad frame is skipped, not fatal, not latching.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- add plain-JVM tests for the matrix rows *Short Y plane* (all four rotations), *Short U/V plane*, *Good after bad*, *Inference failure* (count stays 0), *Diagnostics unavailable* and *Pipeline goes on* -- proves EMPTY with no throw.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorBadFrameTest.kt` -- new Robolectric test: a too-short plane buffer gives EMPTY with no throw and the `CubentiaScanDiag` log shows the *Repeated bad frames* row, numbers only; a good frame logs no `frameFailed` line -- proves the count reaches `ScanDiagnostics`.

**Acceptance Criteria:**
- Given a continuous Quick Scan frame whose plane buffer is too short, when `detect()` runs, then it returns `StickerCenterDetection.EMPTY` without throwing and the next valid frame runs inference on the same session.
- Given the input fill, inference and peak decode share one guarded block, when peak decoding throws, then the outcome is the same as for a failed input fill (shown by code structure: no input makes `decodePeaks` throw today, and no test seam is added for it).
- Given a debuggable app, when `detect()` is called on the main thread, then it still throws `IllegalStateException`.
- Given the 0.11.0 release profile, when `:app:compileRelease011Kotlin` runs with `-Pcubentia.release011=true`, then the detector compiles against the release no-op twin.
- Given the change, when `git diff` is read, then no file under `_bmad-output/planning-artifacts/` and not `sprint-status.yaml` changed.

## Spec Change Log

## Review Triage Log

### 2026-11-26 — Review pass
- verdicts: 20 findings — high 0, medium 2, low 14, false 4, maybe-false 0
- findings:
  - `[medium]` `[defer]` (blind) A plane short only in its last rows passes conversion, then the face sampler throws outside `detect()` and still crashes the analyzer — verified: `fillYuvInput` never reads the last source rows at model size; `ContinuousFaceSampler.warpFromYuv` (~L182) reads planes with no try and is called from `ContinuousScanPipeline.processFrame`. Pre-existing; the intent limits this fix to `fillYuvInput`/`decodePeaks` in `detect()` ("exactly this and nothing more"). Recorded in `deferred`.
  - `[low]` `[patch]` (blind) Recovery test's fake ignores the input bytes, so stale bytes from a partly converted bad frame would pass — the code rewrites the whole input each frame, but nothing pinned it. Added test `a valid frame after a partly converted bad frame gives inference the same input as a fresh detector` (fake session now keeps a copy of its input).
  - `[low]` `[reject]` (blind) Pipeline test never builds scan progress before the bad frame — the diff does not touch the pipeline or mapper; an EMPTY detection takes the same path as any frame with no stickers found. A fixture with fitted faces and committed progress is far more than a direct correction.
  - `[low]` `[patch]` (blind) Logging test sent its good frame at count 5, where the throttle hides a wrong report — the good frame now follows count 4 and the test asserts no second `count=4` line.
  - `[low]` `[patch]` (blind) Shared `makeBlankYuv` rounds chroma down, so an odd-sized valid frame would under-allocate — no caller uses odd sizes today; chroma width and height now round up.
  - `[low]` `[reject]` (blind) Code Map says only `release011` compiles the detector against the release twin; `cornerR8` does too — true (`cornerR8` raises `QUICK_SCAN` and uses `src/release/kotlin`), but the fix is an edit to this build's spec; the verification command is unaffected.
  - `[medium]` `[defer]` (edge) Claim "a too-short plane buffer returns EMPTY" does not hold for a buffer missing only the last row; sampling can still crash — same root cause as the first row; shares its `deferred` entry.
  - `[low]` `[reject]` (edge) `failedFrameCount` wraps after 2,147,483,648 failed frames — more than two years of failed frames at 30 fps in one process; a guard adds a branch for a state no session reaches.
  - `[low]` `[patch]` (edge) Odd-sized blank frame under-allocates chroma — same root cause and fix as the blind `makeBlankYuv` row.
  - `[low]` `[patch]` (gap) Recovery can pass while the next valid frame gets stale input — same root cause and fix as the blind recovery-test row.
  - `[low]` `[patch]` (gap) Widening the frame catch to `Throwable` would pass every test — added test `an Error is not a bad frame - it reaches the caller and is not counted`.
  - `[low]` `[patch]` (gap) Five frames cannot tell powers of two from "first and every even" — the Robolectric test now runs to count 8 and asserts lines for 1, 2, 4, 8 only.
  - `[low]` `[reject]` (gap) Nothing asserts the release twin stays silent — `:app` has no release unit-test source set; adding one for a one-line `= Unit` (as none of the other no-op twins has) is new test infrastructure, not a direct correction.
  - `[low]` `[reject]` (intent) The analyzer lambda in `ScanFrameSource` is unchanged and no test drives `read(image)`; "the scan goes on" is shown at detector and pipeline level — the intent sets the surface: Done-when (1), (2) and (4) are worded at `detect()` and "the fix is adapter code plus a test". The one remaining analyzer-level path is the deferred sampler entry.
  - `[false]` `[reject]` (intent) A bad frame is handled as an empty-detection frame (mapper still advances) rather than dropped — the issue defines the skip as "`detect` returns `StickerCenterDetection.EMPTY` for it, and the scan goes on with the next frame", which is what happens.
  - `[low]` `[reject]` (intent) No test makes `decodePeaks` throw — real: no input makes it throw, and fill, inference and decode share one `try`, so the catch a fill failure reaches is the same one. Driving decode alone needs a new constructor seam, which is added complexity for an identical path.
  - `[low]` `[reject]` (intent) The count is per process, is not in the `quickScan run` line, and the debug log shows powers of two only — Done-when (3) asks that the count reaches `ScanDiagnostics` with a debug implementation and a release no-op; the detector reports the exact count on every failed frame. An exact per-scan total would need the run line and the engine port changed.
  - `[false]` `[reject]` (intent) A default `build` does not compile the detector against the release twin — it was compiled against it: `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` is BUILD SUCCESSFUL.
  - `[false]` `[reject]` (intent) The catch also covers `fillYuvInput`'s `require(output.size >= INPUT_BYTES)`, a programming-error check — it cannot fire: `inputBytes` is a private field always allocated at `INPUT_BYTES`.
  - `[false]` `[reject]` (intent) The diff also reworks an existing test around a shared fake session, moves `makeBlankYuv` and edits KDoc — the reworked test keeps every assertion (3 runs, no run after close, 1 close) and passes; separate U and V buffers are what the short-U and short-V cases need.

## Design Notes

The detector is a process `@Singleton`, so the failed-frame count is cumulative for the process, not per scan. The count is reported on every failed frame; the debug twin decides what to log. A power-of-two throttle needs no clock or shared state (deterministic in tests) and keeps resolution where it matters, at low counts.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- expected: set before every Gradle command.
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.*' --tests 'dev.cubentia.app.ScanDiagnosticsRunLineTest'` -- expected: BUILD SUCCESSFUL, new tests ran and passed.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- expected: BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (fix formatting with `./gradlew spotlessApply`).

## Auto Run Result

Status: done

**Summary:** `LiteRtStickerCenterDetector.detect()` now runs input fill, inference and peak decoding inside one guarded block. A frame whose fill or decode throws returns `StickerCenterDetection.EMPTY`, keeps the inference session open, and adds one to a failed-frame count that is reported to `ScanDiagnostics` on every failed frame. The debug twin logs `quickScan frameFailed count=<n>` at powers of two; the release twin is a no-op. Inference failures still latch the detector disabled and are not counted.

**Files changed:**
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- guarded per-frame body, `failedFrames` count, diagnostics report, KDoc.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `onQuickScanFrameFailed(failedFrames)`, logs at powers of two.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- matching no-op.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- plain-JVM tests: short Y plane at four rotations, short U and V planes, good frame after bad, no stale input after a partly converted frame, an `Error` is not swallowed, pipeline goes on; shared `makeBlankYuv` and `CountingStickerCenterSession`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorBadFrameTest.kt` -- new Robolectric test: the count reaches the `CubentiaScanDiag` log as 1, 2, 4, 8 only, numbers only.

**Implementation:** Codex wrote the change (`build-implement.result`: `codex`); the review patches were applied in this session.

**Review findings:** 20 total from four layers (blind 6, edge-case 3, verification-gap 4, intent-alignment 7). Patches applied: 5 entries, all low, all test-only (7 rows). Deferred: 1 entry, medium (2 rows): the face sampler can still throw outside `detect()` on a frame short only in its last rows. Rejected: 11 rows (7 low, 4 false); each reason is in the Review Triage Log.

**Follow-up review recommendation:** false -- patched entries: high 0, medium 0, low 5.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.*' --tests 'dev.cubentia.app.ScanDiagnosticsRunLineTest'` -- BUILD SUCCESSFUL; JUnit XML: 46 tests, 0 failures, 0 skipped (`LiteRtStickerCenterDetectorFailureTest` 11, `LiteRtStickerCenterDetectorBadFrameTest` 1).
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL after the review patches.
- Mutation check: with the `runCatching` around the diagnostics call removed, three plain-JVM bad-frame tests fail with `RuntimeException` (`android.util.Log` not mocked); restored.
- Every I/O matrix row has a test that ran and passed.

**Residual risks:**
- The deferred sampler path: a bad frame can still crash the analyzer when the buffer is short only in rows `fillYuvInput` does not read and a fitted face reaches them.
- No test drives a `decodePeaks` failure; it shares the catch a fill failure reaches.
- The debug log shows the count at powers of two, so the exact total between two lines is not visible; the count is per process, not per scan.
- Not run on a physical device.
