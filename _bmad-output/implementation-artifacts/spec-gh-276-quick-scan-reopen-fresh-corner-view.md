---
title: 'GH #276: Quick Scan reopens at a fresh corner view after Close from the Guided fallback'
type: 'bugfix'
created: '2026-11-22'
status: 'done'
baseline_revision: 'fb774327bc4133551e56433a229505ac84fcd4e5'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      CameraXFrameSource.stop() wiring (own use cases only, torch gate) is not run by any test; only the pure releaseOwnUseCases helper is.
    evidence: |-
      Route tests bind a fake ScanFrameSource; CameraXFrameSourceTest never binds a preview. Reverting stop() to unbindAll() would fail no test. Closing it needs a seam over ProcessCameraProvider or a CameraX fake.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt stop()
    severity: medium
  - summary: >-
      The #276 root cause (a closed scan's late stop() unbinding the next scan's camera) is inferred from code, not reproduced on the TB128XU.
    evidence: |-
      Settle with the device check: tap Quick Scan right after Discard and confirm the corner preview stays live and auto-captures at hold 1, with no Face 1 of 6.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt
    severity: medium (unverified)
  - summary: >-
      Scan face by face accepts a tap before the corner camera delivers its first frame (pre-existing), which can hand over all six faces at once.
    evidence: |-
      switchToFaceByFace checks only the phase and mode; the button has no camera-started gate. Out of scope for #276.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt switchToFaceByFace
    severity: low
---

<intent-contract>

## Intent

**Problem:** On the Lenovo TB128XU, after a Quick Scan fell through to the Guided fallback ("Face 2 of 6") and the Learner tapped Close, the next Quick Scan tap showed the Guided scan at "Face 1 of 6"; a second tap worked. Investigation found no stale phase or controller state across scan Activities (each has its own `GuidedScanViewModel`; a Robolectric reproduction of Close, Discard, reopen already lands at hold 1). The real-device cause is the camera: `CameraXFrameSource.stop()` calls `ProcessCameraProvider.unbindAll()` on the process-wide provider, and the closed scan's `onDestroy` (which calls `stop()`) can run after the next scan has bound its camera. The new corner view then sits at hold 1 with a dead preview that never captures, and only a phase-type change (tapping *Scan face by face*, which at hold 1 with nothing captured hands over all six faces = "Face 1 of 6") restarts the camera.

**Approach:** `stop()` releases only this source's own use cases (its preview and analysis), and turns its torch off only while those use cases are still bound, so a late `stop()` from a closed scan never touches the next scan's camera. Add a route test for Close, then reopen Quick Scan, and a unit test for the release rule.

## Boundaries & Constraints

**Always:** Camera code stays in `app/src/camera/`. `bind()` keeps its `unbindAll()` (a newly starting scan takes the camera over, as intended). Close in the Guided fallback keeps its Story 10.17 behaviour ("Discard this scan?" when a face would be lost, then `Cancelled`).

**Never:** No change to Quick Scan domain logic, phases, copy, feature flags or the Story 10.17 back-out rules. No new early-tap guard on *Scan face by face* (out of scope). No frame persisted or sent.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Late stop of a closed scan | Scan A closed; scan B bound its camera; then A's `stop()` runs | B's use cases stay bound; A unbinds nothing it does not own; A's torch-off is skipped | No error expected |
| Normal stop | This source's use cases are bound | They are unbound and its torch turned off, as before | No error expected |
| Stop before any bind | No use cases | Nothing unbound, no crash | No error expected |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `CameraXFrameSource.stop()` (~L185-200: `enableTorch(false)` on `camera`, then `cameraProvider?.unbindAll()`, the defect); `bindWhenLaidOut` (~L306) builds the `Preview` locally (store it in a field beside `analysisUseCase`, ~L158) and calls `provider.unbindAll()` before `bindToLifecycle` (keep); `analysis()` sets `analysisUseCase` (~L371). `ProcessCameraProvider` is process-wide (`getInstance`, ~L278).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- read-only: `onDestroy` (L377) calls `frameSource.stop()`; `LaunchedEffect(phase::class, ready)` (~L284) restarts the camera only on a phase-type change; `closeOrConfirm` (~L438) and `onDiscardConfirm` (~L224) end in `finishWith(Cancelled)` → `finish()`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CameraModule.kt` -- read-only: `ScanFrameSource` is unscoped (one `CameraXFrameSource` per Activity).
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- route test `closeFromTheGuidedFallbackThenReopenStartsAFreshCornerViewAtHoldOne` and helper `openQuickScan` (already drafted during investigation; passes).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` -- home for pure policy tests of `ScanFrameSource.kt` helpers.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- keep the bound `Preview` in a field; add an internal top-level pure helper (e.g. `releaseOwnUseCases(ours, isBound, unbind): Boolean`) that unbinds only the given use cases still bound and returns whether any were; `stop()` uses it with `provider::isBound`/`provider.unbind`, turns the torch off only when it returns true, and clears the preview field -- a late stop from a closed scan must not unbind the next scan's camera (#276).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` -- unit-test the helper over the I/O matrix rows with fake use cases.
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- keep the drafted route test: Solve → Quick Scan → *Scan face by face* → capture one face ("Face 2 of 6") → Close → Discard → result `Cancelled` delivered to Main → Quick Scan again → corner view at hold 1 ("1 / 2"), no `scanProgress`.

**Acceptance Criteria:**
- Given a Quick Scan in the Guided fallback at "Face 2 of 6", when the Learner taps Close and confirms Discard, then the scan ends `Cancelled` and the next Quick Scan tap from Solve opens a fresh corner view at hold 1 ("1 / 2"), with no "Face n of 6" progress.
- Given a closed scan whose `stop()` runs after the next scan bound the camera, when it runs, then the next scan's use cases stay bound and its torch is not turned off.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 18 findings — high 0, medium 4, low 7, false 5, maybe-false 2
- findings:
  - `[medium]` `[defer]` (intent) The route test and helper tests do not run the changed CameraX code — grouped with the stop() wiring gap; deferred (needs a CameraX seam).
  - `[maybe-false]` `[defer]` (intent) Root cause inferred; the reported Face 1 of 6 needs a Scan face by face tap the issue does not mention — settled only by the device check; deferred medium (unverified).
  - `[medium]` `[patch]` (blind) Torch turned off after unbinding, likely a no-op on an in-Activity stop — fixed: torch off before unbind, only while one of this source's use cases is still bound.
  - `[low]` `[reject]` (blind) Route test would pass without the fix — it pins the Learner-visible Close→reopen contract (criterion 3); the shared fake cannot model CameraX's late stop.
  - `[medium]` `[defer]` (blind) Nothing tests how stop() uses the helper — same root cause as the verification-gap finding; deferred.
  - `[false]` `[reject]` (blind) analysisUseCase not cleared in stop() — a stale reference fails isBound and is replaced on the next bind; no bad outcome.
  - `[false]` `[reject]` (blind) previewUseCase set before a bind succeeds — an unbound use case fails isBound; nothing is unbound or torched.
  - `[low]` `[reject]` (blind) No diagnostic log for a late stop — adds surface for a rare path; the device check covers it.
  - `[low]` `[reject]` (blind) Spec omits CameraX lifecycle reasoning — fix edits this build's spec.
  - `[low]` `[reject]` (blind) Matrix lacks provider-null / one-bound rows — fix edits this build's spec; helper handles both.
  - `[low]` `[reject]` (blind) Code Map line numbers drifted — fix edits this build's spec.
  - `[low]` `[defer]` (blind) Early-tap guard on Scan face by face not recorded — pre-existing; added to deferred.
  - `[low]` `[patch]` (blind) Local `bound` misnames the last camera — renamed to `lastCamera`.
  - `[medium]` `[defer]` (verification-gap) stop() wiring is not run by any test — pre-verified; deferred as filed.
  - `[maybe-false]` `[defer]` (verification-gap other) Late-stop ordering never reproduced — grouped with the device-check deferral.
  - `[false]` `[reject]` (verification-gap other) Test reports may be stale — re-ran after patches: QuickScanRouteTest 13/13, ScanFrameSourcePolicyTest 15/15, CameraXFrameSourceTest 4/4 pass.
  - `[false]` `[reject]` (edge) Torch-off skipped on a normal finish — CameraX's ON_DESTROY already detached the camera before onDestroy, so the old enableTorch there was equally a no-op; closing the camera turns the torch off.
  - `[false]` `[reject]` (edge) "Normal stop ... as before" claim — same evidence: the effective torch behaviour on finish is unchanged, and the in-Activity stop now turns it off while attached.

## Design Notes

Root cause is inferred from code (CameraX lifecycle: a finished Activity's `onStop`/`onDestroy` run once the resumed Activity is idle or after a ~10 s timeout), not reproduced on a device; Robolectric uses a fake `ScanFrameSource`, so the route test pins the Learner-visible Close→reopen contract and the policy test pins the release rule. Device check: Developer options "Don't keep activities" off, tap Quick Scan right after Discard; the corner preview must stay live and auto-capture at hold 1.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.QuickScanRouteTest' --tests 'dev.cubentia.app.camera.ScanFrameSourcePolicyTest' --tests 'dev.cubentia.app.camera.CameraXFrameSourceTest'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** `CameraXFrameSource.stop()` no longer calls `unbindAll()` on the process-wide `ProcessCameraProvider`; it turns its torch off while its own use cases are still bound and then unbinds only its own Preview and ImageAnalysis, so a closed scan's late `onDestroy` cannot kill the next Quick Scan's camera (the likely cause of the dead hold-1 corner view that led to "Face 1 of 6"). A route test covers Close from the Guided fallback, Discard, then reopen Quick Scan at hold 1.

**Files changed:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` — `previewUseCase` field, `releaseOwnUseCases` helper, `stop()` releases only its own use cases.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` — three helper tests (late stop, normal stop, stop before bind).
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` — `closeFromTheGuidedFallbackThenReopenStartsAFreshCornerViewAtHoldOne` and `openQuickScan` helper.

**Review:** 18 findings; 2 patched (torch order, medium; rename, low); 3 deferred (stop() wiring untested, device confirmation of root cause, early-tap guard); 13 rejected with reasons in the triage log.

**Follow-up review recommended:** false (patched: high 0, medium 1, low 1).

**Verification:** `./gradlew :app:testDebugUnitTest --tests QuickScanRouteTest --tests ScanFrameSourcePolicyTest --tests CameraXFrameSourceTest` — pass (13, 15, 4 tests); `./gradlew spotlessCheck build` — BUILD SUCCESSFUL.

**Residual risks:** the root cause is inferred from code; confirm on the TB128XU by tapping Quick Scan right after Discard (the corner preview must stay live and auto-capture at hold 1). The real `stop()` wiring has no automated test.
