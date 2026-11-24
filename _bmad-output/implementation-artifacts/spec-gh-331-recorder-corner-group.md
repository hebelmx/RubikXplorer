---
title: 'Keep recorder supplemental shots in their corner group'
type: 'bugfix'
created: '2026-11-23'
status: 'done'
baseline_revision: '6c24535c59203b97324bfa4a186bed10f8ce5419'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Add an operator-flow regression around folder routing and write failures.
    evidence: |-
      All three route cases ran as JVM rule tests and the UI call sites were traced.
      There is no existing recorder automation that drives controls, displays the group and inspects the written files.
    location: >-
      FixtureRecorderScreen
    severity: medium
  - summary: >-
      Make fixture directory and manifest writes more resilient to I/O interruption.
    evidence: |-
      Existing manifest and hold writers can leave partial files on an I/O error.
      The recorder reports the failure and the active group changes only after a successful hold write.
    location: >-
      FixtureExport
    severity: medium
---

<intent-contract>

## Intent

**Problem:** In debug corner recording, “Record both corners again” clears the saved group immediately. Supplemental positives and negatives taken before another hold is saved go into an unintended new folder with an empty holds manifest.

**Approach:** Keep the current folder until a new first hold is written, then route subsequent saves to the fresh group. Show the current folder in the recorder's corner panel.

## Boundaries & Constraints

**Always:** New holds must never overwrite a previously completed group. Settings or calibration changes still start a new recording condition. Keep writes serialized through the recorder's existing saving flow and preserve support for intentional standalone keypoint capture.

**Never:** Change learner camera behavior, fixture schemas, existing recorded files, or unrelated harness code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Same-condition restart | A completed group, then “Record both corners again” | Keypoints-only and no-cube files still append to the completed group. A camera miss, cancelled placement, or abandoned review does not rotate the folder. | An error saving the new hold leaves the previous group current until a new hold is written. |
| New hold saved | Restart pending, first labeled hold is confirmed and written | Pick a fresh non-overwriting folder. The hold's paired keypoints and later supplemental shots and hold 2 use it. | Report save failures through recorder status. If the hold itself reached disk, avoid allocating a second group on retry because a companion write failed. |
| New capture condition | Cube, light, torch or calibration changed | Forget the prior folder and start a fresh group under the new condition, retaining existing standalone keypoint behavior. | Never append to a prior condition. |

</intent-contract>

## Code Map

- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` — group state, `newCondition`, `groupDirectory`, `writeKeypoints`, negative save, missed detection and `confirmCornerFace`; the entry panel contains the restart button and operator status.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` — `freshGroupName` is the existing collision avoidance rule.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` — existing capture writes and manifest updates.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` — existing JVM recorder routing rules and group naming tests.
- `_bmad-output/planning-artifacts/epics.md` — Story 10.20 (record corner fixtures) binds manifest contents to recording conditions.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` — express folder routing as a small testable recorder state transition, reusing the existing name collision rule.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` — distinguish supplemental and hold saves, publish the current folder after a successful write, bind the delayed same-condition restart, retain immediate reset for a new condition, and display the current folder or an explicit empty state.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` — regress both kinds of supplemental save through their common routing, hold transition, avoided collisions, and new condition.

**Acceptance Criteria:**
- Given a completed corner group, when the operator taps “Record both corners again”, then the recorder still displays that group's folder, and the next keypoints-only and no-cube shots are saved there without creating a new folder.
- Given a same-condition restart, when the operator saves and confirms the next actual corner hold, then its capture and paired keypoints are written to the next available folder and the displayed folder changes to that folder.
- Given a prior group, when the operator changes a capture condition, then any new files use the new condition's folder and calibration as before.

## Spec Change Log

## Review Triage Log

### 2026-11-23 — Review pass

- verdicts: 15 findings — high 0, medium 12, low 3, false 0, maybe-false 0
- findings:
  - `[medium]` `[defer]` First-hold I/O failure can strand a partial folder — the existing export creates a manifest before writing the hold, and the failure is reported. Staging whole groups is a separate persistence improvement.
  - `[medium]` `[patch]` Paired keypoint error can leave a durable hold out of the manifest — attempt the manifest update before paired keypoints.
  - `[low]` `[reject]` Retrying a companion write replays the hold files — rare repeated I/O failure on the existing same-hold retry is below the scope for introducing a separate resume state.
  - `[medium]` `[defer]` An interrupted manifest rewrite can leave unreadable metadata — the existing writer does not replace atomically, and the recorder reports a manifest update error.
  - `[medium]` `[defer]` Rule tests do not drive the folder label or two supplemental buttons — both actions call the tested routing, but a UI regression needs a recorder driver.
  - `[medium]` `[defer]` Failed first-hold writes can leave a partial folder on disk — the previous active group is preserved and an error is visible.
  - `[medium]` `[patch]` Active group may omit a durable hold if its optional keypoints fail — update the manifest first.
  - `[low]` `[reject]` Existing four-digit keypoint sequence would hit a collision at 10,000 saves in a group — the reported operation adds eight samples, and the existing naming format is unchanged.
  - `[medium]` `[defer]` Restart and supplemental controls lack an automated operator flow — debug recorder has no Compose test driver, and the callback mapping and folder label were reviewed in source.
  - `[medium]` `[defer]` Hold-save failure and retry are tested at the routing boundary only — the staged source shows publication after the hold, and dependency injection for write failure is separate.
  - `[medium]` `[defer]` New-condition test does not drive the recalibration dialog — the modal calls the same condition reset used by settings before allowing another save.
  - `[low]` `[defer]` Collision rules use a fake occupancy predicate in tests — the screen's filesystem check treats a regular file or unreadable path as occupied, while tests check suffix selection.
  - `[medium]` `[defer]` A failed write can leave the displayed folder and a partial disk folder out of sync — the old folder stays active and the error is reported, preserving intended destinations after a restart.
  - `[medium]` `[patch]` A paired keypoint error can precede manifest update after a successful hold — manifest update now runs first.
  - `[medium]` `[defer]` On-device controls and writes are unverified — focused JVM regressions and source inspection cover routing and wiring respectively.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests '*RecorderRulesTest' --tests '*FixtureExportTest'` — all focused recorder tests pass.
- `./gradlew spotlessCheck build` — repository gate passes, or report the known `CornerFixtureHarnessTest` decode failure being fixed separately by PR #336.

## Auto Run Result

Status: done.

The debug corner recorder keeps supplemental captures in the displayed group while a same-condition restart is pending. A successfully written hold activates a fresh folder and records that hold in its manifest before the paired keypoints write. New conditions clear the active folder.

- `FixtureRecorderScreen.kt` binds the folder display, restart, and actual save paths.
- `RecorderRules.kt` holds the small folder transition and reuses suffix allocation.
- `RecorderRulesTest.kt` covers the reported split, successful switch and retry routing, and capture condition changes.

Four review layers recommended one medium patch, applied to the manifest save order. Other findings were deferred or rejected with evidence in the review log. No follow-up review is recommended from this pass.

- `./gradlew :app:testDebugUnitTest --tests '*RecorderRulesTest' --tests '*FixtureExportTest'` passed with 37 tests, 0 failures and 0 skips.
- `./gradlew spotlessCheck build` ran 1,367 debug tests. Its only failure was the pre-existing `CornerFixtureHarnessTest` manifest decode error being repaired by PR #336. The build must pass on the integration branch once that repair is available.
- The shared corner panel shows the current folder in both layouts. Source inspection verified positive, negative and detector-miss saves share supplemental routing, and condition controls cannot alter the group during an in-flight save. The UI label has not been checked on a physical device.
