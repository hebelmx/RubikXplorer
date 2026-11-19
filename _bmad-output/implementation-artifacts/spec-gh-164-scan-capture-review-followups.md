---
title: 'GH-164: Story 10.14 scan capture and review follow-ups'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: 'd80c22d475ca31f12ae25367f9ca4ca95e1f3e73'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [multiple-goals, oversized]
deferred:
  - summary: >-
      ScanAssembler reports branch NO_VALID_REPROMPT when no state is valid but no face is eligible to re-prompt (all posed), so the debug trace names a re-prompt that never happens.
    evidence: |-
      ScanAssembler.kt:503-508 picks NO_VALID_REVIEW only when involved.size > MAX_REPROMPT_FACES; an empty involved list falls to NO_VALID_REPROMPT. Pre-existing for all-posed scans (e.g. after a re-prompt round); #164 only widens it to posed center mismatches. Affects the debug-only CubentiaScanDiag trace label, not Learner behavior.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt:507
    severity: low
---

<intent-contract>

## Intent

**Problem:** The Story 10.14 acceptance gate (#164) left five code gaps in scan review and assembly: system Back on Scan review discards an edited scan with no "Discard this scan?" confirmation; the first-use coach mark stays on screen after every blank is filled; the Activity/frame-source wiring has no test for the re-prompt round, `FrameReader.locate`, `ScanDiagnostics.onFrame` or coach-mark persistence; `ScanAssembler.repromptFaces` lists center-disagreeing faces even when they already had a prompted top; the fading update-cue "✓" joins the sticker's TalkBack description.

**Approach:** Add a `BackHandler` in `ScanReviewScreen` enabled only while the review is changed, opening the same `DiscardAction.CLOSE` dialog; render the coach mark only while blanks remain (without calling `onCoachMarkDismissed`); gate center-disagreeing faces by the same "unposed, or `repromptPosed`" rule as undecided faces; wrap the cue in `clearAndSetSemantics`; add Robolectric route/frame-source tests for what is still untested.

## Boundaries & Constraints

**Always:** Keep `MAX_REPROMPT_FACES = 2` behavior exactly (SCP 2026-11-19 scan re-prompt cap): no valid state → re-prompt only when at most 2 faces are involved, else `NO_VALID_REVIEW` with the best fit; ≥ 2 valid states → always re-prompt the faces that differ. Back with no change keeps today's behavior (the Activity's own `BackHandler` finishes as Cancelled). The coach mark still shows on a later review while its record is pending; only a tap records it as seen. Learner text only through existing `Text(key)` keys — no new strings. Keep the cue's `sticker:<i>:cue` test tag findable.

**Never:** Exposure step-down/slider, the solid single-color face check, Galaxy correction measurements, epics.md or any planning artifact, anything needing a physical device. No change to `ScanResult`, `CubeValidator`, classifier thresholds, review layout, the controller's one-round rule, or release flags/gates. No re-test of what `ScanRouteTest` already covers (Enter, Accept, unedited Back-Cancel, camera stop/rotation). No edits to `_bmad/`, `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Back after edit | review painted (or `edited = true`), system Back | discard dialog shows; `onClose` not called; Confirm → `onClose`; Cancel → stays | No error expected |
| Back unedited | no change | screen does not intercept (no enabled screen callback); Activity finishes Cancelled as today | No error expected |
| Coach, all blanks filled | `coachMarkPending`, Learner paints every blank without tapping coach | coach mark gone; `onCoachMarkDismissed` not called; record still pending | No error expected |
| Posed face, center disagrees | posed sample (`upNeighborColor` set) whose plain center reads another color, `repromptPosed = false` | not in `repromptFaces` | No error expected |
| Unposed face, center disagrees | as today | still first in `repromptFaces` | No error expected |
| Posed, center disagrees, `repromptPosed = true` | Quick Scan hand-off | still listed (unchanged) | No error expected |
| Cue visible | color just set on sticker 7 | sticker 7's merged semantics contain no "✓" text; cue node still exists by tag | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- `changed` (L95, rememberSaveable, seeded from `edited`), `discardAction`/`DiscardAction.CLOSE` (L97, L194), dialog L230-254. `coachVisible` set by `LaunchedEffect(coachMarkPending && needsFix)` (L98-102), rendered L146-151. Imports `androidx.activity.compose.BackHandler` already used by `GuidedScanActivity` (so the dependency is on the classpath for `main`; check `SettingsScreen.kt` for a `main` usage).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- L219-227: Activity-level `BackHandler`s (Calibration / everything else → `finishWith(Cancelled)`); the screen's handler, composed later inside `ScanReviewScreen`, takes precedence. L256-305: review wiring, `coachMarkPending = scan.coachMarkPending`, `onCoachMarkDismissed` → `ScanCoachMarkRecord.markSeen` on IO. L151: pending read on IO. L528-569 `handle` (Capture + re-prompt phase, Done → `reviewOf`). `GuidedScanViewModel.newController` L648 (default reprompt + `ScanDiagnostics.onAssembly("first")`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `CameraXFrameSource.read(image: ImageProxy)` (private, ~L236-249) is where `FrameReader.locate(image, grid, rotation)` and `ScanDiagnostics.onFrame(...)` are wired, then `deliver(ScanFrame(...))`. `start()` sets `deliver` and returns early in `startCamera()` when `preview()` was never called (no CameraX). `viewSize` is 0×0 until laid out.
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- `locate(ImageProxy, grid, rotation)` L149, `ImageProxy.asYuvFrame()`, `locate(YuvFrame, …)` L178 returning `FrameReading(stats, lattice)`.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `onFrame` L267: logs (tag `CubentiaScanDiag`, contains `lattice=scale…`) only when `inspection.failed != null || inspection.outlinedOnly` and all 9 cells readable; throttled 500 ms by a static `lastLogMs` (advance `SystemClock` in the test or the line may be throttled).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/FrameReaderTest.kt` -- synthetic `YuvFrame` builders (`image(...)` L211, `cubeFace(...)` L266, `stickerlessFace`) to reuse/copy for a fake `ImageProxy` (planes from the same buffers).
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- Hilt Robolectric harness: `FakeFrames` (`starts`, `running`, `feed`), `captureFaces(vm, logo, misread)` L349 (feeds `cube` faces in net order; net order equals the posed turn for every plan re-prompt top), `labs`, `closeCall`, existing back test L284, coach taps at L149/L319.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanCoachMarkRecord.kt` -- `pending(context)` / `markSeen(context)`, prefs file `scan_review`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- controller `repeating` L373, `repromptCount` L379, `step` L382.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- `search` L433; L490 `unposed`; L501 `disagreeing = order.filter { faces[it.ordinal].centerDisagrees }` (the bug); L502 `involved`; L504 cap. `Placement.Placed.posed` / `centerDisagrees` L381-386. KDoc of `repromptFaces` L269-275.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanAssemblerTest.kt` -- existing center-disagree tests ~L400-415 (`Face.L, Face.U` / logo case) and posed-sample helpers to extend.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- cue `Box` L372-383 (`testTag("sticker:$index:cue")`, `Text("✓")`).
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- harness (`launch`, `screen()`, `review` blanks at 7 and 20, `coachPending`, `coachDismissed`, `closed`); coach tests L353-385; cue test L305-321. `SettingsScreenTest.kt:67` shows getting `LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher` in a compose test.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- add `BackHandler(enabled = changed && discardAction == null) { discardAction = DiscardAction.CLOSE }`; render the coach mark only when `coachVisible && needsFix` (do not call `onCoachMarkDismissed`); update KDoc -- items 1 and 2.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- give the cue `Box` `clearAndSetSemantics { testTag = "sticker:$index:cue" }` (or keep the `testTag` modifier if it stays findable) so "✓" is not in the sticker's description -- item 5.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- `disagreeing` keeps only faces that are unposed or `repromptPosed`; update the `repromptFaces` KDoc -- item 4. Do not touch the cap or `branch` logic.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- make the per-image read reachable from tests with the smallest change (e.g. `@VisibleForTesting internal fun read(image: ImageProxy)`); no behavior change -- enables item 3's frame test.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- tests: Back after painting a blank shows `review:discard` and leaves `closed` false, confirm closes; Back with `edited = true` asks too; unedited review registers no enabled back callback (`hasEnabledCallbacks()` false) and `closed` stays false; coach pending + paint both blanks without tapping → `review:coach` gone, `coachDismissed == 0`, `coachPending` still true; cue visible → sticker 7 merged semantics have no "✓" in Text/ContentDescription while `sticker:7:cue` exists (unmerged).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanAssemblerTest.kt` -- posed face with a disagreeing plain center is not listed (repromptPosed false); same samples with `repromptPosed = true` still list it; existing unposed disagree and cap tests stay green unchanged.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- add: (a) re-prompt round through `GuidedScanActivity`: `captureFaces` with one misread edge sticker (2 faces involved) → after the sixth capture the phase is a Viewfinder in the re-prompt round (`vm.controller.repeating`), the prompt shows "…with <color> on top" from content strings, the camera still runs; feeding the prompted faces (correct colors) opens Review, with no second round; (b) coach-mark persistence: with the record pending (clear the `scan_review` prefs), a review with blanks shows `review:coach`; leaving it untapped leaves `ScanCoachMarkRecord.pending` true; tapping it makes `pending` false (after idling IO/main), and a new `GuidedScanActivity` review with blanks shows no coach; (c) edited review + system Back at Activity level shows `review:discard` and the Activity is not finishing.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt` (new, Robolectric) -- fake `ImageProxy` over a synthetic face frame; `start(...)` without `preview` (no CameraX bind), call the read path; assert the delivered `ScanFrame.stats` equal `FrameReader.locate(image.asYuvFrame(), grid, rotation).stats` for the same grid, and that for a frame meeting `onFrame`'s log condition one `CubentiaScanDiag` line containing `lattice=` is logged (`ShadowLog`).

**Acceptance Criteria:**
- Given an edited Scan review, when the Learner presses system Back, then "Discard this scan?" shows and the scan finishes Cancelled only on Discard.
- Given the first-use coach mark showing, when the Learner fills every blank without tapping it, then it disappears and is still pending for the next review.
- Given the camera frame source, when an image is analysed, then its stats come from `FrameReader.locate` and the debug frame line is logged through `ScanDiagnostics.onFrame`.
- Given `./gradlew spotlessCheck build`, when run, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 14 findings — high 0, medium 0, low 9, false 5, maybe-false 0
- findings:
  - `[low]` `[defer]` (blind) `NO_VALID_REPROMPT` is reported when the pose filter leaves zero eligible faces — real but pre-existing for all-posed scans and only a debug trace label; added to `deferred`.
  - `[low]` `[patch]` (blind) The new filter is only tested with every face posed; no mixed case where a posed center mismatch would push two unposed faces over the cap — same root as the verification-gap row; added `a posed face with a disagreeing center does not count toward the re-prompt limit` (U, F unposed + posed L misread center → `[F, U]`; would be empty if counted).
  - `[low]` `[patch]` (blind) The Activity re-prompt test never checks the retakes repair the cube — now asserts `review.accepted` equals the scanned cube.
  - `[low]` `[reject]` (blind) Re-meter on entering the named-top round is not asserted — untouched Activity code (`GuidedScanFlow.remeters`), not caused by this change; covered by `GuidedScanFlowTest`; the fix adds a counting fake for a path #164 did not name.
  - `[low]` `[patch]` (blind) No Activity-level test that Back on an unedited review cancels at once — added `backFromUneditedReviewFinishesCancelledWithoutAsking`.
  - `[low]` `[reject]` (blind) `CameraXFrameSourceTest` bypasses the analyzer callback that closes the `ImageProxy` — that callback (`bindWhenLaidOut`, `try { read } finally { image.close() }`) is unchanged and needs CameraX to bind; not part of #164.
  - `[low]` `[patch]` (edge) The low-light fixture returns before lattice search, so a source using the fixed grid would still pass — added `deliversTheLocatedLatticeReadingWhenTheFaceIsOffTheDrawnGrid`: a face a half cell off reads MISALIGNED on the drawn grid and ACCEPTABLE after `locate`; delivered stats equal the located ones and differ from `readAt`.
  - `[low]` `[patch]` (verification-gap) Mixed-pose cap case untested (pre-verified gap) — same fix as the second blind row.
  - `[false]` `[reject]` (intent) Item 3's `locate`/`onFrame` are tested on `CameraXFrameSource`, not end to end through `GuidedScanActivity` — that wiring lives in `CameraXFrameSource.read` since 10.14 and CameraX cannot bind under Robolectric (same as #161); the Activity's own glue (re-prompt, coach mark, Back) is tested through `GuidedScanActivity`.
  - `[low]` `[patch]` (intent) KDoc said the coach mark hides "for this review" while the code hides it only while no sticker needs a color — KDoc reworded.
  - `[false]` `[reject]` (intent) Narrowing `disagreeing` can change whether the cap triggers — that is the requested change (#164 item 4); the cap's rule and the ≥ 2-valid branch are unchanged, and only posed faces, which are never re-asked, stop counting.
  - `[false]` `[reject]` (intent) `clearAndSetSemantics { testTag = … }` instead of an empty block — same TalkBack effect (asserted: no "✓" in the sticker's merged Text/ContentDescription); the tag keeps the cue findable by tests.
  - `[false]` `[reject]` (intent) Route test covers only the ≤ 2-face no-valid branch — the cap branches are unchanged and covered by `ScanAssemblerTest`/`GuidedScanTest`; #164 asked for the re-prompt round only.
  - `[false]` `[reject]` (intent) `read` changed from private to `@VisibleForTesting internal` — the minimal seam the spec names; no behavior change.

## Design Notes

Item 4 reads "limit to faces that had no prompted top" as the same gate undecided faces already use (`repromptPosed || unposed`): Quick Scan's corner-to-grid hand-off passes `repromptPosed = true` precisely to include posed faces, so it keeps its behavior. Only fewer faces can be listed, so the `MAX_REPROMPT_FACES` rule is unchanged. Item 3: since Story 10.14 the `FrameReader.locate` / `ScanDiagnostics.onFrame` wiring moved from the Activity into `CameraXFrameSource.read`, so that is the level tested; the Activity's `FakeFrames` bypasses it by design.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test --tests '*ScanAssemblerTest' --tests '*QuickScanControllerTest' --tests '*GuidedScanTest'` -- expected: pass
- `./gradlew :app:testDebugUnitTest --tests '*ScanReviewScreenTest' --tests '*ScanRouteTest' --tests '*CameraXFrameSourceTest' --tests '*FrameReaderTest'` -- expected: pass
- `./gradlew :core:domain:evaluateScanAccuracy && git diff --stat docs/scan-accuracy.md` -- expected: no diff
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** All five #164 code items are in. System Back on an edited Scan review now opens the same "Discard this scan?" dialog as Close. An unedited review still cancels at once. The coach mark hides while no sticker needs a color, without being recorded as seen. `ScanAssembler.repromptFaces` lists a center-disagreeing face only when it is unposed, or when `repromptPosed` is set. The `MAX_REPROMPT_FACES = 2` rule is unchanged. The update-cue "✓" is out of the sticker's TalkBack description. New Robolectric tests cover the re-prompt round, coach-mark persistence and Back through `GuidedScanActivity`. They also cover `FrameReader.locate` and `ScanDiagnostics.onFrame` through `CameraXFrameSource.read`.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- `BackHandler` on the Close discard rule; coach mark shown only while blanks remain.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- cue `Box` uses `clearAndSetSemantics` (keeps its test tag).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- disagreeing centers gated by `repromptPosed || unposed`; KDoc.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `read(image)` is `@VisibleForTesting internal`.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- Back (edited, pre-edited, unedited), coach hidden on fill, cue semantics.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanAssemblerTest.kt` -- posed disagreeing center (both flag values) and the mixed-pose cap case.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- re-prompt round to an accepted review, coach-mark persistence across Activities, Back on edited and unedited reviews.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt` -- new: located stats delivered (an off-grid face), `CubentiaScanDiag` lattice line logged.

**Review findings:** 14 in total. 7 low findings were patched; two of them (the mixed-pose case) shared one fix. 1 low finding was deferred: the debug branch label for an invalid scan with no eligible face is pre-existing. 2 low findings were rejected because they concern unchanged code paths (re-meter, `ImageProxy` close). 5 were rejected as false. Each one's reason is in the Review Triage Log above.

**Follow-up review recommendation:** false. Patched counts: high 0, medium 0, low 7.

**Verification:** Codex implemented the change. Claude applied the review patches and re-ran every `## Verification` command. All passed: `:core:domain:test` (ScanAssemblerTest 30/0, QuickScanControllerTest, GuidedScanTest), and `:app:testDebugUnitTest` (ScanReviewScreenTest 32/0, ScanRouteTest 8/0, CameraXFrameSourceTest 2/0, FrameReaderTest 29/0). `evaluateScanAccuracy` showed no content diff in `docs/scan-accuracy.md`, and `spotlessCheck build` reported BUILD SUCCESSFUL.

**Residual risks:** CameraX binding and the analyzer callback are still not exercised under Robolectric. The frame-source test calls `read` directly. Real-device Back and TalkBack behavior is still to be checked at the next device session.

