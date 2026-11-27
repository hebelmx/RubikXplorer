---
title: 'GH #404: a closed LiteRtStickerCenterDetector reads unavailable'
type: 'bugfix'
created: '2026-11-27'
status: 'done'
baseline_revision: '34892cd530963c51146f77c4c0e813c859981846'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      close() leaves inputValid set, so copyModelInput() still returns the last frame's model input after close.
    evidence: |-
      Pre-existing (close() never reset inputValid before #404). Not reachable through the pipeline today: after close
      processFrame takes skippedFrame and never calls copyModelInput. Fix would be inputValid = false in close().
    location: >-
      app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt:239
    severity: low
  - summary: >-
      close() calls session?.close() without runCatching, unlike latchDisabled(); a throwing close propagates and leaves the session set.
    evidence: |-
      Pre-existing shape of close(). unavailable is now latched before the call, so the #404 guarantee holds; only the
      exactly-once close and the exception to the caller are affected. No production caller of close() exists.
    location: >-
      app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt:243
    severity: low
---

<intent-contract>

## Intent

**Problem:** `LiteRtStickerCenterDetector.close()` sets `closed = true` and drops the session, but never latches `unavailable`. After close, `ensureSession()` returns null before the `unavailable = true` line, so `detect()` returns `StickerCenterDetection.EMPTY` forever while `unavailable` stays false; `ContinuousScanPipeline` then feeds the empty frame to the mapper and the Learner sees "Bring the cube into view" instead of the #390 fallback card.

**Approach:** Make `close()` latch `unavailable = true` (inside the existing lock), so the pipeline's existing `detectorUnavailable` check returns `skippedFrame` and the mapper never sees the frame. Update the two JVM tests that pinned the old behaviour.

## Boundaries & Constraints

**Always:** `unavailable` reads true immediately after `close()` returns, whether or not a session was ever opened; `detect()` / `detectRgb()` after close still return `EMPTY` without loading the model or running inference; the session is still closed exactly once.

**Never:** No throwing on detect-after-close (the PO picked the unavailable reading); no change to `ContinuousScanPipeline`, the `StickerCenterDetector` interface, the UI, or any production caller; no new lifecycle owner that calls `close()`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Close before first frame | fresh detector, `close()` | `unavailable` true; `detect()` EMPTY; loader never called | No error |
| Close after healthy frames | session opened, 3 runs, `close()` | `unavailable` true; session closed once; next `detect()` does not run inference | No error |
| Pipeline after close | closed detector in `ContinuousScanPipeline` | `processFrame` returns the skipped frame (`detectorUnavailable` true, snapshot unchanged, no fitted faces); mapper untouched | No error |

</intent-contract>

## Code Map

- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `close()` (near end of class) sets `closed` and drops the session; `ensureSession()` returns null on `if (closed)` before its `unavailable = true`; `unavailable` is `@Volatile ... private set`; class KDoc says init/inference failures latch [unavailable].
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- read-only: line ~42 `detectorUnavailable get() = detector.unavailable`, line ~58 `if (detectorUnavailable) return skippedFrame(frameIndex)`, `skippedFrame` (~106) leaves the mapper untouched.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- `a healthy session runs once per frame and is closed exactly once` and `closed detector returns empty safely and feeds cleanly through ContinuousScanPipeline` currently assert `assertFalse(detector.unavailable)` after close and `BringIntoView` cue; these pin the bug.
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtStickerCenterDetectorDeviceTest.kt` -- read-only: calls `close()` in finally blocks; no `unavailable` assertions, unaffected.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- in `close()`, set `unavailable = true` under the lock; add a one-line KDoc on `close()` and extend the class KDoc so it says close also latches [unavailable] (GH #404) -- the #390 rule: a disabled detector must not look like an empty view.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- flip the healthy-session test to assert `unavailable` true after close (runs and closes counts unchanged); rewrite the closed-detector pipeline test to assert `unavailable` true, loader never called, and that `processFrame` returns the skipped frame (`pipeline.detectorUnavailable` true, no fitted faces, `committedCount` 0), and add a pipeline case that processes one valid frame on a healthy detector, closes it, then asserts the next `processFrame` returns the same snapshot instance (`assertSame`) with \`detection\` EMPTY and no inference run, which proves the mapper did not see the frame) -- covers close -> unavailable and detect after close.

**Acceptance Criteria:**
- Given a detector, when `close()` returns, then `unavailable` is true.
- Given a closed detector inside `ContinuousScanPipeline`, when a valid frame is processed, then the pipeline takes its `detectorUnavailable` skippedFrame path and the mapper does not receive the frame.
- Given the change, when `./gradlew spotlessCheck build` runs, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 13 findings — high 0, medium 0, low 9, false 3, maybe-false 0 (verification-gap layer: no gaps)
- findings:
  - `low` `defer` (edge) copyModelInput returns stale input after close — pre-existing (close never reset inputValid); unreachable via pipeline, which skips before the tap; deferred.
  - `low` `defer` (edge) session.close() throwing leaves session set and propagates — pre-existing close() shape; unavailable already latched first; deferred.
  - `low` `defer` (edge, claim) "closed exactly once" holds only if close does not throw — same root cause as the previous row; deferred.
  - `false` `reject` (intent) Diff implements reading A; dropped BringIntoView cue assertion, engine/UI untested — descriptive only; the PO's Done-when sets the bar at the detector flag and the pipeline skip path, both tested.
  - `low` `patch` (blind) StickerCenterDetector.unavailable interface KDoc did not list close as a cause — KDoc extended to name a closed detector (GH #404).
  - `false` `reject` (blind) close() is irreversible and turns Quick Scan off with the unavailable card — that is the outcome the intent requires (#390 rule); no reopen path is in scope.
  - `low` `defer` (blind) close() does not reset inputValid — same root cause as edge row 1; deferred with it.
  - `low` `defer` (blind) close() lacks runCatching unlike latchDisabled() — same root cause as edge row 2; deferred with it.
  - `false` `reject` (blind) no double-close test — a second close() sees session == null and closes nothing; no bad outcome.
  - `low` `reject` (blind) detectRgb after closing an open session untested — ensureSession() returns null on closed before any run; test-only gap, unlikely to bite.
  - `low` `reject` (blind) no engine-level test that engine.detectorUnavailable turns true after close — the engine reads pipeline.detectorUnavailable directly, which is tested; adding a test is extra scope.
  - `low` `reject` (blind) tap onSkippedFrame and close-mid-frame race untested for the close path — skippedFrame tap behaviour and the post-detect re-check are covered by the 10.34 and #390 tests; adding a race test is extra complexity.
  - `low` `patch` (blind) rewritten test class header was one 167-character line — split into a short multi-line KDoc.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.LiteRtStickerCenterDetector*'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** `LiteRtStickerCenterDetector.close()` now latches `unavailable = true` under its lock, so a closed detector reads unavailable and `ContinuousScanPipeline` takes its existing `detectorUnavailable` skippedFrame path; the mapper never sees the frame and the Learner gets the #390 fallback instead of "Bring the cube into view".

**Files changed:**
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- close() latches unavailable; class and close() KDoc updated.
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- unavailable KDoc names a closed detector as a cause (review patch).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- two post-close tests now assert unavailable true and the pipeline skip; new mid-scan close test asserts the same snapshot instance comes back and no inference runs.

**Review findings:** 2 patches applied (both low: interface KDoc, header line wrap); 2 items deferred (low, pre-existing: stale copyModelInput after close, unguarded session close); 9 rejected with reasons in the triage log.

**Follow-up review recommended:** false (patched: high 0, medium 0, low 2).

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.continuous.LiteRtStickerCenterDetector*'` passed (17 tests, 0 failures); `./gradlew spotlessCheck build` BUILD SUCCESSFUL after the patches. Matrix rows 1-3 covered by the three post-close tests.

**Residual risks:** close() is one-way for the singleton; a future lifecycle owner that closes it turns Quick Scan off until process restart (intended by #390/#404). Device test not run (unaffected; no unavailable assertions).
