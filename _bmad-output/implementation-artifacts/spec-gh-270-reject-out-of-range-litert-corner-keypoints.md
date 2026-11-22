---
title: 'Reject out-of-range LiteRT corner keypoint outputs (#270)'
type: 'bugfix'
created: '2026-11-22'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      decode() rejections are silent and indistinguishable (size, non-finite, out-of-range, low presence, geometry all return null with no log).
    evidence: |-
      Pre-existing design; LiteRtCornerDetector.locate passes null through without logging, so a mis-exported model is invisible in device diag logs.
    location: >-
      app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/LiteRtCornerDetector.kt:55
    severity: low
  - summary: >-
      The output.size < OUTPUT_FLOATS guard and oversized buffers (extra values ignored) have no unit test.
    evidence: |-
      Pre-existing guard in decode(); neither old nor new CornerModelTransformTest cases exercise undersized or oversized output arrays.
    location: >-
      app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/CornerModelTransform.kt
    severity: low
baseline_revision: '715ad9bd255f7f67026f48b690b462fde4052fed'
---

<intent-contract>

## Intent

**Problem:** `CornerModelTransform.decode()` rejects non-finite values, low presence and bad cube geometry, but accepts a presence score above 1 and normalized keypoint coordinates outside the crop (`u`/`v` < 0 or > 1), mapping them into frame pixels and handing a quad to face unwarping (GitHub issue #270).

**Approach:** Before mapping keypoints to frame coordinates, `decode()` returns `null` unless presence and every one of the 14 normalized keypoint values (`u` and `v` of all 7 keypoints) lie in the closed range `[0, 1]`. Unit tests prove each rejection.

## Boundaries & Constraints

**Always:** Bounds are inclusive: exactly `0` and exactly `1` are accepted. The range check runs on the raw `float` outputs before `cropToFrame`; existing checks (size, non-finite, presence threshold, `isValidCubeGeometry`) keep their behavior. Returning `null` is the only rejection signal (that is the existing `decode` contract). Every commit subject carries `#270`.

**Never:** Do not clamp or rescale out-of-range values. Do not change `cropToFrame`, `frameToCrop`, `fillRgbInput`, `LiteRtCornerDetector`, `CornerLocator`, the default presence threshold, or the model/training tools. No new production files or abstractions.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Valid output | presence 0.91, all u/v in [0,1], valid geometry | non-null Location with 3 quads (unchanged) | No error expected |
| Presence above 1 | otherwise valid output, presence = 1.2 | `null` | no quad produced |
| Presence below 0 | presence = -0.1 with `presenceThreshold = -1f` (so only the range check can reject) | `null` | no quad produced |
| Presence exactly 1 | otherwise valid output, presence = 1.0 | non-null Location | No error expected |
| u out of range | any single keypoint u = -0.05 or 1.05, rest valid | `null` | no quad produced |
| v out of range | any single keypoint v = -0.05 or 1.05, rest valid | `null` | no quad produced |

</intent-contract>

## Code Map

- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/CornerModelTransform.kt` -- `decode()` (bottom of file): output layout is `[presence, T.x, T.y, ..., C.x, C.y]`, `OUTPUT_FLOATS = 15`; add the range check after the non-finite loop and before the presence-threshold / `cropToFrame` mapping. Update the KDoc to mention the range rejection.
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/LiteRtCornerDetector.kt:55` -- sole production caller; returns `decode()`'s result as the `CornerLocator.Location?` whose `quads` feed face unwarping. Read-only: a `null` here means no quad reaches unwarping.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt:107` -- `isValidCubeGeometry`; read-only. Note that some out-of-range keypoints may also fail geometry; tests must pick values so the range check alone is what rejects (e.g. perturb a single coordinate slightly past the bound, or rely on a case where geometry would otherwise pass).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerModelTransformTest.kt` -- existing test `decode maps valid 7 keypoints ...` builds a valid output per rotation from `CornerLocator.projectCube` + `frameToCrop`; reuse that construction for the new tests.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/CornerModelTransform.kt` -- in `decode()`, return `null` if `output[0]` or any of `output[1 until OUTPUT_FLOATS]` is outside `[0, 1]` -- implements issue #270.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerModelTransformTest.kt` -- add tests covering every I/O matrix row; for out-of-range u/v, loop over all 7 keypoints and both coordinates (and both bounds) and assert `decode` returns `null` -- shows no Location (hence no quad) reaches unwarping.

**Acceptance Criteria:**
- Given a model output whose presence is outside `[0,1]`, when `CornerModelTransform.decode()` runs, then it returns `null` and no quads are produced.
- Given a model output where any normalized keypoint `u` or `v` is outside `[0,1]`, when `decode()` runs, then it returns `null` and no quads are produced.
- Given an out-of-range keypoint that would otherwise pass `isValidCubeGeometry`, when `decode()` runs, then it still returns `null` (the range check, not geometry, rejects it).
- Given the existing valid-output test, when the suite runs, then it still passes unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 11 findings — high 0, medium 0, low 10, false 1, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Finiteness loop now redundant since NaN/Inf fail `!in 0f..1f` — behavior correct; spec keeps existing checks unchanged; negligible cost.
  - `[low]` `[patch]` (blind) [0,1] rule not tied to the model's sigmoid head — added one KDoc sentence citing `train_corner_model.py` sigmoid head and rejecting rather than clamping.
  - `[low]` `[defer]` (blind) Rejections are silent and indistinguishable — pre-existing; adding logging is new surface beyond #270.
  - `[low]` `[reject]` (blind) u/v loop test may pass on pre-fix code via geometry — true, but the third test asserts geometry passes and still null, so the range check is proven; the loop is breadth coverage.
  - `[low]` `[reject]` (blind) Near-edge test depends on tuned fixture values — `checked >= 2` guards against vacuity; making it parametric adds complexity for a test-only concern.
  - `[low]` `[reject]` (blind) atBound assertion conflates geometry with range — only a misattributed failure message on future change; not worth extra assertions.
  - `[low]` `[reject]` (blind) Fixture builder duplicated with the existing valid-output test — refactoring the existing test is outside "unchanged"; cosmetic.
  - `[false]` `[reject]` (blind) Spec I/O matrix does not list extra cases / spec untracked — every matrix row is covered; extra tests do not contradict it; fix would edit this build's spec.
  - `[low]` `[defer]` (blind) Buffer-size guard untested — pre-existing, not caused by this change.
  - `[low]` `[reject]` (blind) Threshold-ordering cases (threshold > 1, negative presence at default threshold) untested — no caller passes a threshold above 1; negative presence at default threshold is rejected either way.
  - `[low]` `[patch]` (intent) Done-when (3) "show no quad reaches face unwarping" was shown only at decode(), not through CornerReader — added test wrapping decode() in a CornerDetector and running CornerReader.read: out-of-range presence and u/v give detected=false and all faces null; valid output yields a 3-quad Location.
  - edge-case-hunter: no findings (`[]`). verification-gap: "No verification gaps found."

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.CornerModelTransformTest'` -- expected: BUILD SUCCESSFUL, all tests pass including the new ones.
- `./gradlew spotlessCheck` -- expected: BUILD SUCCESSFUL (run `spotlessApply` if formatting fails).

## Auto Run Result

Status: done

**Summary:** `CornerModelTransform.decode()` now returns `null` when presence or any of the 14 normalized keypoint values is outside the closed range `[0, 1]` (inclusive bounds, never clamped), checked before mapping to frame pixels. Unit tests cover out-of-range presence and u/v at all four rotations, prove the range check rejects even when cube geometry would pass, and show through `CornerReader.read` that no quad reaches face unwarping.

**Files changed:**
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/CornerModelTransform.kt` -- range check in `decode()` plus KDoc (sigmoid-head contract).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerModelTransformTest.kt` -- four new #270 tests and a `validOutput` helper.

**Review findings:** 11 total. Patches applied: 2 (both low: KDoc sigmoid note; CornerReader pipeline test). Deferred: 2 (silent rejections; buffer-size guard untested). Rejected: 7 (6 low as negligible/test-only complexity, 1 false), reasons in the Review Triage Log.

**Follow-up review recommendation:** false — patched entries: high 0, medium 0, low 2.

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.CornerModelTransformTest' spotlessCheck` -- BUILD SUCCESSFUL; JUnit XML: 7 tests, 0 failures, 0 skipped. Range-check removal made the presence and "range alone" tests fail (implementer check).

**Residual risks:** The near-edge test relies on tuned fixture values (cube scale 1.5, 0.06 edge window); changes to `projectCube` or `isValidCubeGeometry` may require retuning. Full `./gradlew build` was not run locally; CI will gate it.
