---
title: 'GH-318: Fixture recorder says plainly when a keypoints-only save did not record the corner colors, and offers Retake'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Keypoints-only save reports "the detector found no cube" when samples were null only because shot.jpeg was null.
    evidence: |-
      captureCorner falls back to CornerMiss.NO_CUBE when cornerMiss() returns null (samples readable) but jpeg is null; pre-existing from #329.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt:380
    severity: low
  - summary: >-
      The missedHold state that switches the primary button to "Retake corner N" has no automated test.
    evidence: |-
      Only cornerCaptureLabel(hold, retake) is unit-tested; no UI test harness drives FixtureRecorderScreen. Verify on device, or move the set/clear rule into a pure RecorderRules helper.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt:181
    severity: low
baseline_revision: '80907e19fe8178b6ed7af4dfdb90439695b3600a'
---

<intent-contract>

## Intent

**Problem:** In the debug fixture recorder (corner mode), when a *Capture corner N* press yields no color samples (`CornerMiss`: NO_CUBE, TOO_DARK, UNREAD_CELL), *Use corners* saves a keypoints-only positive and the status reads only "Saved positive 000N (keypoints only)." (plus an EV+ hint for TOO_DARK only, from #329). The operator does not learn that the corner's colors were not recorded, why, or that the hold did not advance, so it looks like Capture is broken.

**Approach:** After a keypoints-only save that followed a corner miss, the status leads with "Corner N colors NOT recorded" and the reason for every `CornerMiss` value, and the primary corner button directly below it reads *Retake corner N* (same action as Capture corner N, same hold) until the next capture or reset.

## Boundaries & Constraints

**Always:** Keep message wording and the button label in pure functions in `RecorderRules.kt` so `RecorderRulesTest` (JVM) covers them. Put the "not recorded" fact and the reason at the start of the status (the status line is `maxLines = 2` with ellipsis and may be prefixed by coaching text). A deliberate *Keypoints only* save (`miss == null`) keeps its current status "Saved positive %04d (keypoints only)." unchanged. Use "colors" spelling, matching existing recorder statuses.

**Never:** Do not change what gets saved, the hold numbering, `cornerMiss` classification, `cornerMissStatus` (pre-placement text), the Activity capture path, or any main-app/Learner text. No new screens or dialogs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Unread cell | `keypointsOnlySaved(3, 2, UNREAD_CELL)` | `"Corner 2 colors NOT recorded: a face had an unread cell. Saved keypoints only as positive 0003; Retake corner 2 to record them."` | none |
| Too dark | `keypointsOnlySaved(3, 2, TOO_DARK)` | `"Corner 2 colors NOT recorded: faces too dark, tap EV+ first. Saved keypoints only as positive 0003; Retake corner 2 to record them."` | none |
| No cube | `keypointsOnlySaved(3, 1, NO_CUBE)` | `"Corner 1 colors NOT recorded: the detector found no cube. Saved keypoints only as positive 0003; Retake corner 1 to record them."` | none |
| Deliberate keypoints-only | `keypointsOnlySaved(3, 1, null)` | `"Saved positive 0003 (keypoints only)."` | none |
| Button after miss | `cornerCaptureLabel(2, retake = true)` | `"Retake corner 2"` | none |
| Button normally | `cornerCaptureLabel(2, retake = false)` | `"Capture corner 2"` | none |

</intent-contract>

## Code Map

- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- `CornerMiss` (~l.377), `cornerMissStatus` (~l.406, leave as is), `keypointsOnlySaved(index, miss)` (~l.423-428, TOO_DARK-only hint from #329): change to `keypointsOnlySaved(index, hold, miss)` per matrix; add `cornerCaptureLabel(hold: Int, retake: Boolean): String` next to it.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- `usePlacement()` (~l.383-410) calls `keypointsOnlySaved(index, placed.miss)` after `saveSupplemental`; `cornerHold` is unchanged between capture and save, so pass it. `captureCorner(kind)` (~l.341) starts every corner-mode capture; `resetCorner()` (~l.234) resets corner state (called by `retakeHold`, `recordBothAgain`, after a hold save). Primary button `Text("Capture corner $cornerHold")` (~l.936) in the `cornerMode ->` else-branch (shown when no placement, no `cornerShot`, no refusal) is where Retake goes.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- existing test `a keypoint-only save after a too-dark miss adds the EV+ hint` (~l.729-737) uses the old signature and strings; replace it.

## Tasks & Acceptance

**Execution:**
- `RecorderRules.kt` -- change `keypointsOnlySaved` to take `hold` and return the matrix strings (one reason per `CornerMiss` via `when`); add `cornerCaptureLabel`; update KDoc -- wording testable on the JVM.
- `FixtureRecorderScreen.kt` -- add `var missedHold by remember { mutableStateOf<Int?>(null) }`; in `usePlacement` pass `cornerHold` and, after a successful save with `placed.miss != null`, set `missedHold = cornerHold`; set `missedHold = null` at the start of `captureCorner` (after the `saving` guard) and in `resetCorner()`; the primary button text becomes `cornerCaptureLabel(cornerHold, missedHold == cornerHold)` (onClick unchanged) -- Retake offered right under the status.
- `RecorderRulesTest.kt` -- replace the old keypoints-only test with tests for every matrix row (UNREAD_CELL row required) -- covers done-when (3).

**Acceptance Criteria:**
- Given a corner capture whose read failed with any `CornerMiss`, when the operator taps *Use corners*, then the status begins "Corner N colors NOT recorded: <reason>" and the primary button reads *Retake corner N*, which captures hold N again.
- Given the operator then captures again (any kind) or the corner state resets, when the corner screen shows, then the primary button reads *Capture corner N* again.
- Given a deliberate *Keypoints only* save, then the status and button are as before this change.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 13 findings — high 0, medium 0, low 12, false 1, maybe-false 0
- findings:
  - `low` `reject` (blind) Status `maxLines = 2` may cut the tail of the new message — the "NOT recorded: <reason>" fact leads and survives; only the trailing Retake clause can be cut, and the button itself reads Retake; the fix would change the intent-contract strings.
  - `low` `patch` (blind) `missedHold` cleared on any capture start, incl. failed/Negative/Keypoints-only captures — moved the clear to just before a CORNER-kind placement is created (grouped with edge 1-2).
  - `low` `defer` (blind) NO_CUBE reason is wrong when `shot.jpeg == null` but the cube was read — pre-existing `cornerMiss(...) ?: NO_CUBE` fallback from #329 (the pre-placement status already said "Detector missed").
  - `low` `defer` (blind) `missedHold` set/clear logic has no test — debug Compose screen with no UI test harness; grouped with verification-gap 1.
  - `low` `reject` (blind) Story 10.28 `retakeHold` status says Retake while the button reads Capture — pre-existing, and that flow's colors were recorded, so the GH-318 "colors missed" label does not apply.
  - `low` `reject` (blind) All-caps "NOT" and message style — wording is fixed by the intent-contract matrix; fix would edit the spec.
  - `low` `reject` (blind) No call-site test that deliberate KEYPOINTS_ONLY passes `miss = null` — guard is pre-existing and unchanged; a test needs a screen harness.
  - `low` `patch` (edge) Retake capture that fails early reverts the label — fixed by the same move of the clear (grouped).
  - `low` `patch` (edge) Negative/Keypoints-only capture after a miss drops the label — fixed by the same move (grouped).
  - `low` `patch` (edge) `usePlacement` read live `cornerHold` inside the coroutine; `newCondition` (settings dialog) can reset it mid-save — captured `val hold = cornerHold` before launch and used it for status and `missedHold`.
  - `low` `defer` (verification-gap) `missedHold` → "Retake corner N" wiring untested; only `cornerCaptureLabel` is — filed disposition defer; check on device.
  - `low` `defer` (intent) On-screen surface (status visibility, Retake button) is exercised only through pure string functions — grouped with verification-gap 1.
  - `false` `reject` (intent) Retake is a relabelled capture rather than a distinct action — the relabelled button re-captures the same hold N (hold does not advance after a keypoints-only save), which is a retake; the intent asks only that one is offered.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-318 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.recorder.RecorderRulesTest'` -- expected: all tests pass
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-318 && ./gradlew spotlessCheck :app:compileDebugKotlin` (same env) -- expected: success

## Auto Run Result

Status: done

**Summary:** After a keypoints-only save that followed a corner color miss, the debug fixture recorder's status now leads with "Corner N colors NOT recorded: <reason>" for every miss (unread cell, too dark with an EV+ hint, no cube), and the primary corner button reads "Retake corner N" (re-captures the same hold) until the next corner capture or a reset. A deliberate Keypoints-only save keeps "Saved positive NNNN (keypoints only)."

**Files changed:**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- `keypointsOnlySaved(index, hold, miss)` with a reason per `CornerMiss`; new `cornerCaptureLabel(hold, retake)`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- `missedHold` state; set after a missed keypoints-only save, cleared when a CORNER capture freezes a placement or on reset; button label uses `cornerCaptureLabel`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- tests for UNREAD_CELL, TOO_DARK, NO_CUBE, null and both button labels (replaces the #329 TOO_DARK-only test).

**Review:** 13 findings; 4 patched (all low, one root cause plus the hold capture), 4 deferred (2 deferred items, low), 5 rejected (reasons in the Review Triage Log).

**Follow-up review recommended:** false (patched: 0 high, 0 medium, 4 low).

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.recorder.RecorderRulesTest' spotlessCheck :app:compileDebugKotlin` -- BUILD SUCCESSFUL; RecorderRulesTest 55 tests, 0 failures, 0 skipped (after patches).

**Residual risks:** The Capture/Retake label switching is screen state verified by code reading only; confirm on a device session. Status line is two lines max, so with long coaching text the trailing Retake clause can be cut (the button still reads Retake).
