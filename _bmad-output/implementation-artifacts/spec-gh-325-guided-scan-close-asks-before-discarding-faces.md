---
title: 'GH #325: Guided scan Close asks before discarding captured faces'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: 'fb046d7fd32a10b90829d27b84d445f042670755'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      After a scan opened from Learn → Find my place ends Cancelled (Discard, or Close/Back with no face), the Learner lands on the Learn Stage list, not on the Find my place sheet that offered *Scan my cube*.
    evidence: |-
      Pre-existing caller behaviour, not changed by this diff: the Find my place sheet closes when *Scan my cube* is tapped, before the scan starts (AppShellEntryLevelTest waits for findMyPlace:scan to disappear). GuidedScanCloseRouteTest waits for findMyPlace:scan after Discard and times out; learn:findMyPlace (Stage list top bar) is what shows. EXPERIENCE.md's Scan viewfinder row says Discard returns to "Find my place (Re-placement)"; whether the sheet must reopen needs a PO call and a caller-side change.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt (findMyPlace:scan)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** In the Guided 6-face Scan viewfinder, *Close* (and system Back) after one or more faces are captured cancels the scan at once (`guidedScan … outcome=CANCELLED`) and the captured faces are lost without asking (GH #325, Lenovo TB128XU and Galaxy S24 FE). Quick Scan already asks "Discard this scan?" (Story 10.17).

**Approach:** Extend the viewfinder's existing close-or-confirm path so a plain Guided 6-face viewfinder with at least one captured face shows "Discard the faces you captured?" with *Keep scanning* and *Discard*; with none it still leaves at once. *Discard* ends the scan `Cancelled` (the caller returns to its previous screen, unchanged caller code). Approved by SCP 2026-11-24 github-issue-triage (Story 10.2 amendment in epics.md; EXPERIENCE.md Scan viewfinder row).

## Boundaries & Constraints

**Always:**
- Applies to the plain Guided 6-face viewfinder (`GuidedScanPhase.Viewfinder` with `fromReview == null`, `handOver == false`, not `quickScanMode`, and the intent has no `QuickCheckContract.EXTRA_FACES`), for every caller: Welcome → *Start from my cube* (Placement), Solve → *Scan my cube*, Learn → *Find my place* → *Scan my cube* (Re-placement), and the Quick Check full scan (`EXTRA_FULL_QUICK_CHECK`, *Scan all six faces*). Same rule for *Close* (`scanClose`) and system Back.
- "Captured" = the faces in the shown viewfinder phase's `captured` (first round and re-prompt round alike); 0 → leave at once with `ScanOutcome.Cancelled`, ≥ 1 → the question.
- Copy from content strings only (`core/domain/src/main/resources/content/strings/en.json`): new keys `msg.scan.discard-faces-title` = "Discard the faces you captured?" and `btn.scan.keep-scanning` = "Keep scanning"; *Discard* reuses `btn.scan.discard`.
- The question reuses the existing `DiscardDialog` / `scan.confirmDiscard` VM state (survives rotation; analysis is already paused while it shows, so no face is captured behind it) and its tags `scan:discard`, `scan:discard:confirm`, `scan:discard:cancel`; only title and dismiss text differ for this case. TalkBack reads the title, then the two actions (AlertDialog default order: no custom semantics needed).
- *Keep scanning* closes the question; every captured face, the mini net, the progress and the current prompt stay; the scan goes on. *Discard* calls `finishWith(ScanOutcome.Cancelled)` (one `guidedScan run … outcome=CANCELLED` diagnostics line, as today).

**Never:**
- Change the retake-from-review back-out (Story 10.16), the Quick Scan corner view / hand-over rules and their "Discard this scan?" / *Cancel* copy (Story 10.17), the review's own "Discard this scan?" (Story 10.3), the partial Quick Check (`EXTRA_FACES`), calibration, or Case capture.
- Move or restyle *Close* (tablet placement is GH #313, a separate PR). No caller-side (MainActivity, Solve, Placement, Lesson, Playback) code changes; no planning-artifact edits; no Learner text in `strings.xml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Leave with none | Plain Guided viewfinder, 0 faces; *Close* or Back | No dialog; Activity finishes `RESULT_CANCELED` / `ScanOutcome.Cancelled`; caller shows its previous screen | No error expected |
| Ask with faces | Plain Guided viewfinder, ≥ 1 face (e.g. 2); *Close* or Back | `scan:discard` shows "Discard the faces you captured?", *Keep scanning*, *Discard*; Activity not finishing | No error expected |
| Keep scanning | Question shown; tap `scan:discard:cancel` | Dialog gone; same faces captured (count unchanged), same prompt/step; next fed face is captured | No error expected |
| Discard | Question shown; tap `scan:discard:confirm` | Finishes `Cancelled`; one CANCELLED run line; caller back on the screen it opened the scan from | No error expected |
| Quick Check full scan | `EXTRA_FULL_QUICK_CHECK` intent, 0 / ≥ 1 faces | Same as rows above; Lesson / Playback screen that asked shows again | No error expected |
| Retake viewfinder | `fromReview != null`; *Close* or Back | Review restored exactly (unchanged) | No error expected |
| Quick Scan | corner view / hand-over with faces | "Discard this scan?" / *Cancel* (unchanged); hand-over Back → corner view | No error expected |
| Partial Quick Check | `EXTRA_FACES` intent with a face captured; Close | Leaves at once (unchanged) | No error expected |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `closeOrConfirm()` (~L449-461) is the single decision point both `actions.onClose` (else-branch ~L182) and the non-calibration `BackHandler` (else-branch ~L266) already call; today it returns `false` unless `quickScanMode`. Add the plain-viewfinder case there. `isQuickCheck` (~L418) and the `solveScan` test (~L135-138) show how to read `EXTRA_FACES` / `EXTRA_FULL_QUICK_CHECK`. `onDiscardConfirm`/`onDiscardCancel` (~L230-234) already do Discard / dismiss. `analyze` returns early while `scan.confirmDiscard` (~L565). `finishWith` (~L483) logs the run and encodes the result. Pass to the screen whatever it needs to pick the copy (e.g. a discard-kind or a boolean), next to `confirmDiscard = scan.confirmDiscard` (~L365).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanViewModel` (same file, ~L1015-1030, ~L1115) -- `phase` setter clears `confirmDiscard` on a phase-kind change; `confirmDiscard` state. Viewfinder→Viewfinder updates keep it.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanScreen(... confirmDiscard ...)` (~L230-244) and private `DiscardDialog` (~L970-990): choose title `msg.scan.discard-faces-title` + dismiss `btn.scan.keep-scanning` for the Guided case, keep `msg.scan.discard-title` + `btn.scan.cancel` for Quick Scan. `GuidedScanPhase.Viewfinder` (~L136-163: `captured`, `fromReview`, `handOver`, `total`).
- `core/domain/src/main/resources/content/strings/en.json` -- scan discard keys at ~L789-791; add the two keys beside them.
- Tests (patterns to reuse): `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` (`withScan`, `captureFace`/`captureFaces` feed `FrameStats` through `FakeFrames` and end the freeze, `awaitCameraStart`, `backFromUneditedReview…`, `repeatedTaps…` for Back → CANCELLED log), `SolveFromScanRouteTest.kt` (`solveScan`, `withScan(intent)`, `returnToSolve`, `captureCube`, full Quick Check intent via `QuickCheckContract(launcher).createIntent(..., fullScan = true)`), `PlacementFromScanRouteTest.kt` (`placementScan` from `welcome:startFromCube`, `cancelledScanFromWelcomeReturnsToWelcome`), `QuickCheckRouteTest.kt` (`physicalLesson`, `awaitStarted`, `mismatch:scanAll`), `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` (`findMyPlace:scan`, reached from `learn:findMyPlace` in `LearnScreen.kt`), `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` (`playback:scanAll`). Quick Scan dialog tests that must stay green: `QuickScanRouteTest.kt` (~L425-570), `camera/QuickScanScreenTest.kt` (~L135-145).

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- in `closeOrConfirm()` treat a plain Guided 6-face `Viewfinder` (per Always) with `captured.isNotEmpty()` as "ask"; expose to the screen which copy to use; keep every other branch as is -- #325 rule, single decision point for Close and Back.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `DiscardDialog` takes the copy choice; Guided: "Discard the faces you captured?" / *Discard* / *Keep scanning*; Quick Scan unchanged -- copy per EXPERIENCE.md Scan viewfinder row.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.scan.discard-faces-title`, `btn.scan.keep-scanning` -- NFR-10 content strings.
- `app/src/testDebug/kotlin/dev/cubentia/app/GuidedScanCloseRouteTest.kt` (new, Robolectric + Hilt, same module setup as `ScanRouteTest`/`SolveFromScanRouteTest`) -- for each caller (Welcome → `welcome:startFromCube`; Solve → `solve:scan`; Learn → `learn:findMyPlace` → `findMyPlace:scan`; Quick Check full scan via a Lesson `mismatch:scanAll` or Playback `playback:scanAll`, or the full-scan intent if the UI route cannot be reached in Robolectric): take the real started intent from `MainActivity`, run `GuidedScanActivity` with it, and cover: 0 faces + `scanClose` and 0 faces + system Back → finishes `Cancelled`, no `scan:discard`; 2 faces + `scanClose` and 2 faces + Back → dialog with the new title and *Keep scanning*/*Discard* texts, not finishing; *Keep scanning* → dialog gone, `captured` size still 2, a third face can be captured; *Discard* → finishes `Cancelled`, then `receiveResult` on `MainActivity` and assert the caller's previous screen shows (its launching control or screen tag is displayed again, e.g. `welcome:startFromCube`, `solve:scan`, `findMyPlace:scan`, the Lesson/Playback control). Parameterize or use helpers to keep it compact -- Done-when (1), (2), (4).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanScreenTest.kt` -- screen-level: the Guided discard dialog renders the new title and both actions; the Quick Scan dialog copy is unchanged -- copy regression.
- Existing tests: keep `ScanRouteTest` retake back-out tests, `QuickScanRouteTest` and `QuickScanScreenTest` discard tests and the partial Quick Check behavior green without editing their expectations; add one assertion (in the new file or `ScanRouteTest`) that a partial Quick Check (`EXTRA_FACES`) with a face captured still leaves at once -- Done-when (3).

**Acceptance Criteria:**
- Given the debug main app, when *Close* or Back is used in a plain Guided viewfinder from any of the four callers, then the I/O matrix rows hold and the caller code is unchanged.
- Given `./gradlew spotlessCheck build`, when run, then it passes (lint, JVM/Robolectric tests, Kover, `checkLearnerText`, boundary and manifest gates).

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 25 findings — high 0, medium 4, low 19, false 2, maybe-false 0
- findings:
  - `[medium]` `[patch]` (edge) `plainGuidedViewfinder` keyed on `!hasExtra(EXTRA_FACES)`: a Quick Check whose faces resolved null runs the full 6-face plan but dropped faces without asking — rebuilt as `Viewfinder && fromReview == null && !handOver && scan.isGuidedRun`; new test `quickCheckFallingBackToTheSixFacePlanAsksBeforeDiscardingFaces`.
  - `[low]` `[reject]` (edge) dialog vanishes when an in-flight 6th-face freeze publishes the review — no face is lost (the review holds all six); needs a Close during the last ~freeze window; fix adds guards; same as existing Quick Scan behaviour.
  - `[low]` `[reject]` (edge) Close between the analyzer capture and the main-thread phase update drops that face — millisecond window, only for the very first face; fix adds a guard.
  - `[low]` `[reject]` (edge) Unavailable phase after a camera failure loses faces without asking — scanning cannot continue there, so *Keep scanning* would keep nothing usable.
  - `[low]` `[reject]` (edge) Back tested with 3 faces, not exactly 2 — same branch (`captured.isNotEmpty()`), covered.
  - `[medium]` `[defer]` (edge) Learn caller asserts `learn:findMyPlace`, not the Find my place sheet — verified: the app returns to the Stage list because the sheet closes before the scan starts (pre-existing caller behaviour); deferred.
  - `[low]` `[reject]` (blind) re-prompt round not tested — `captured` is the controller's captured list in both rounds; same branch.
  - `[low]` `[reject]` (blind) Playback `playback:scanAll` host not tested — the activity rule keys only on the intent shape, tested via the Lesson's `EXTRA_FULL_QUICK_CHECK` launch; host cancel handling unchanged; extra host setup is more than a direct fix.
  - `[low]` `[reject]` (blind) Quick Check diagnostics line not asserted — diagnostics are not part of the intent.
  - `[low]` `[patch]` (blind) *Keep scanning* checked only by count — added `assertSameScan` (captured samples, order, step, stepIndex, again) after recreate and after *Keep scanning*.
  - `[low]` `[reject]` (blind) dismiss by outside tap/Back untested — `onDismissRequest = onDiscardCancel`, same handler as *Keep scanning*.
  - `[low]` `[reject]` (blind) Close during the 6th face's freeze — same root cause as the edge freeze row; rejected there.
  - `[low]` `[patch]` (blind) duplicated "plain Guided run" definition — same root cause as the first row; now built on `scan.isGuidedRun`.
  - `[low]` `[patch]` (blind) stale comments naming only "Discard this scan?" — reworded to "the discard question" (analyze comment, phase setter KDoc, confirmDiscard KDoc).
  - `[false]` `[reject]` (blind) plural title for one face — the PO-accepted copy is "Discard the faces you captured?" verbatim (issue, epics.md, EXPERIENCE.md).
  - `[low]` `[reject]` (blind) partial Quick Check covers Close only; no-face Back log not asserted — Back and Close share `closeOrConfirm()`; log not in intent.
  - `[false]` `[reject]` (blind) `discardKind` recomputed from live phase could switch mid-question — a plain Guided viewfinder can never become a hand-over or retake without a phase-kind change, which clears `confirmDiscard`; the default is a developer-only concern with no named diverging caller.
  - `[medium]` `[patch]` (verification-gap) Quick Scan copy not checked in the running activity — `QuickScanRouteTest` now asserts "Discard this scan?" / *Cancel* and no Guided title at corner-view Close and a new hand-over Close.
  - `[low]` `[reject]` (intent) Quick Check reached via `confirmCube:scan`, not *Scan all six faces* — same `EXTRA_FULL_QUICK_CHECK` intent, asserted in the test.
  - `[low]` `[reject]` (intent) Playback host untested — same as the blind row.
  - `[medium]` `[defer]` (intent) Find my place "previous screen" looser fit — same root cause as the edge Learn row; deferred.
  - `[low]` `[reject]` (intent) Close→Discard and Back→Keep combinations not run — the two inputs reach the same `closeOrConfirm()` and the same dialog handlers.
  - `[low]` `[patch]` (intent) Keep scanning does not check prompt — same root cause as the blind Keep row; patched there.
  - `[low]` `[reject]` (intent) Quick Check run line skipped — same as the blind diagnostics row.
  - `[low]` `[reject]` (intent) device acceptance and TalkBack order not in the diff — device-session work outside code; AlertDialog's default reading order is used.

### 2026-11-24 — Review pass (follow-up)
- verdicts: 23 findings — high 0, medium 1, low 15, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` (intent) `EXTRA_FACES` launch that falls back to the six-face plan asks (reading R2) instead of leaving at once (R1) — carried: same row as the first pass's `isGuidedRun` rule; code still reads `scan.isGuidedRun`; not patched again.
  - `[low]` `[reject]` (intent) Quick Check full scan: one CANCELLED run line not asserted — carried: diagnostics are not part of the intent.
  - `[low]` `[reject]` (intent) device behaviour, rotation on device and TalkBack order are not tested — carried: device-session work outside code; AlertDialog default order.
  - `[low]` `[reject]` (intent) the scan result is handed to MainActivity with `receiveResult`, not the real activity-result path — standard Robolectric pattern used by every route test here; a real-path harness is more than a direct fix and no defect is shown.
  - `[false]` `[reject]` (blind) the dialog copy is recomputed from the live phase, not saved when the question opens — carried: a plain Guided viewfinder cannot turn into a hand-over or retake without a phase-kind change, which clears `confirmDiscard`.
  - `[low]` `[patch]` (blind) `discardKind` defaults to `Scan`; `quickScanDiscardQuestionKeepsItsCopy` tests the default, not the Quick Scan kind — the default itself is carried false (no diverging caller); the screen test now passes `discardKind = DiscardKind.Scan` explicitly.
  - `[false]` `[reject]` (blind) unsafe cast `current as GuidedScanPhase.Viewfinder` in `closeOrConfirm()` — `plainGuidedViewfinder` checks `is GuidedScanPhase.Viewfinder` first, so the cast cannot fail; no harm named.
  - `[low]` `[patch]` (blind) `closeOrConfirm()` KDoc says only "system Back in Quick Scan's corner view" — reworded to "system Back when no retake, hand-over or calibration handles it first".
  - `[low]` `[patch]` (blind) `plainGuidedViewfinder` KDoc hides that its exclusions come from `isGuidedRun`, and `isGuidedRun` does not say it decides the question — KDoc now names `GuidedScanViewModel.isGuidedRun`, and `isGuidedRun` got a KDoc naming GH #325.
  - `[low]` `[patch]` (blind) `GuidedScanCloseRouteTest` KDoc says Discard returns to "the screen it opened the scan from" while Learn asserts the Stage list — KDoc now says Learn returns to `learn:findMyPlace` and points to the spec deferral.
  - `[low]` `[reject]` (blind) no-face path's diagnostics not asserted — carried: diagnostics are not part of the intent.
  - `[low]` `[reject]` (blind) closing the question by Back or an outside tap untested — carried: `onDismissRequest = onDiscardCancel`, same handler as *Keep scanning*.
  - `[low]` `[reject]` (blind) test helpers duplicated from `ScanRouteTest` / `QuickScanRouteTest`, reaching into `ScanRouteTest.FakeFrames` — the existing route tests share fixtures the same way; a shared fixture file is a refactor, not a direct fix.
  - `[low]` `[patch]` (blind) redundant `scan:discard` assertion before `assertQuickScanDiscardCopy()`; hand-over Cancel does not check kept faces — redundant line deleted; the hand-over kept-face check is Story 10.17 behaviour the diff does not change, left as is.
  - `[low]` `[reject]` (blind) question may close itself when a held review publishes at the end of a freeze; no comment says so — carried: no face is lost; same as Quick Scan.
  - `[false]` `[reject]` (edge) Close in a Guided Review with six faces drops them without asking — the review's Close is `ScanReviewScreen`'s own (`onClose` / `review:discard`, asks when edited, Story 10.3) and never reaches `closeOrConfirm()`'s viewfinder branch; the intent forbids changing it.
  - `[low]` `[reject]` (edge) Close during the last face's freeze lets the held Review dismiss the question — carried: no face lost; guards add complexity.
  - `[low]` `[reject]` (edge) a frame passing the `confirmDiscard` check just before Close sets it — carried: millisecond window; guard adds complexity.
  - `[false]` `[reject]` (edge) Quick Scan Viewfinder that is neither hand-over nor retake drops faces — unchanged pre-existing branch; Quick Scan's capture phase is a CornerViewfinder or a hand-over Viewfinder (`capturePhase()`), and the only other Viewfinder is the pre-load initial phase with no faces.
  - `[false]` `[reject]` (edge) catalog failure shows raw keys for the new copy — a failed catalog blanks every scan string alike, not the new keys; both keys are in `en.json` and the content gates passed.
  - `[low]` `[reject]` (edge) Back tested with 3 faces, not 2 — carried: same `captured.isNotEmpty()` branch.
  - `[medium]` `[defer]` (edge) Learn asserts `learn:findMyPlace`, not the Find my place sheet — carried: already deferred; not deferred again.
  - `[low]` `[patch]` (verification-gap) no test feeds a frame while the Guided question is open — `asksBeforeDiscardingFaces` now feeds a clear frame of the prompted face after Close and asserts the question still shows and `assertSameScan`; mutation check (dropping `|| scan.confirmDiscard` in `analyze`) made `welcomeAsksBeforeDiscardingFaces` fail, guard restored.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-325 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.GuidedScanCloseRouteTest' --tests 'dev.cubentia.app.ScanRouteTest' --tests 'dev.cubentia.app.QuickScanRouteTest' --tests 'dev.cubentia.app.SolveFromScanRouteTest' --tests 'dev.cubentia.app.PlacementFromScanRouteTest' --tests 'dev.cubentia.app.QuickCheckRouteTest' --tests 'dev.cubentia.app.camera.GuidedScanScreenTest' --tests 'dev.cubentia.app.camera.QuickScanScreenTest'` -- expected: all pass, new tests ran
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-325 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** In the plain Guided 6-face Scan viewfinder, *Close* and system Back ask "Discard the faces you captured?" (*Keep scanning* / *Discard*) once one or more faces are captured, and leave at once with none, from every caller (Welcome, Solve, Learn → Find my place, Quick Check full scan, including a Quick Check that falls back to the six-face plan). *Discard* ends the scan `Cancelled`; caller code is unchanged. This follow-up pass found no high or new medium defects and tightened tests and comments.

**Files changed (whole change since baseline):**
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `plainGuidedViewfinder()` (built on `scan.isGuidedRun`), `closeOrConfirm()` asks for it and passes the copy kind; follow-up: KDoc of `closeOrConfirm`, `plainGuidedViewfinder` and `isGuidedRun` updated.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `DiscardKind` (Scan / GuidedFaces) picks title and dismiss text of `DiscardDialog`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.scan.discard-faces-title`, `btn.scan.keep-scanning`.
- `app/src/testDebug/kotlin/dev/cubentia/app/GuidedScanCloseRouteTest.kt` -- 10 route tests; follow-up: feeds a frame behind the open question (no capture), `feedNextFace` helper, KDoc names the Learn return screen.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanScreenTest.kt` -- Guided and Quick Scan dialog copy; follow-up: Quick Scan test passes `DiscardKind.Scan` explicitly.
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- Quick Scan copy at activity level; follow-up: redundant assertion removed.

**Review findings (follow-up pass):** 23 rows. Patched: 6 low (frame-behind-question test, explicit `DiscardKind.Scan`, three KDoc fixes, redundant assertion); the medium `isGuidedRun` row was carried, not re-patched. Deferred: none new (the Find my place sheet item stays deferred). Rejected: 11 low and 5 false, with reasons in the triage log. First pass: see the earlier triage entry.

**Follow-up review recommended:** false -- follow-up pass patched no high (patched counts: high 0, medium 0, low 6); the work has converged.

**Verification:** targeted `:app:testDebugUnitTest` suites green (GuidedScanCloseRouteTest 10/10, QuickScanRouteTest 16/16, GuidedScanScreenTest 21/21, plus ScanRouteTest, SolveFromScanRouteTest, PlacementFromScanRouteTest, QuickCheckRouteTest, QuickScanScreenTest); mutation check of the `confirmDiscard` analyzer guard fails the new assertion as intended; `./gradlew spotlessCheck build` BUILD SUCCESSFUL after the patches (one spotless blank-line fix applied).

**Residual risks:** no device run on the Lenovo / Galaxy S24 FE yet (TalkBack order, real camera timing); a Close tapped during the last face's capture freeze lets the dialog close itself when the review opens (no face lost); after Discard from Learn the Stage list shows, not the Find my place sheet (deferred).
