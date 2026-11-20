---
title: 'Story 10.16: Retake faces from the scan review'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: '9498d47437b216567183bcfd7541e36af132c967'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-19-review-face-retake.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** On the Guided 6-face Scan review (Story 10.3), a Learner whose scan misread a whole face can only fix it sticker by sticker or close and rescan all six faces (#235).

**Approach:** Add a retake mode to the review net: select 1–6 faces, run the Guided Scan viewfinder for only those faces (any-rotation prompt, no re-prompt), then reopen review with kept faces exactly as they were and retaken faces replaced by a `ScanAssembler` search that holds the kept faces fixed.

Refs (binding, verbatim): FR-23, FR-14, FR-21 · recognition NFRs, NFR-10 · AD-3, AD-9, AD-13, AD-18 · UX-DR15, UX-DR16 · Stories 10.2, 10.3, 10.14, 10.15 · #235

## Boundaries & Constraints

**Always:**
- AD-9: `ScanAssembler` stays the only place samples are rotated; retaken faces try their 4 quarter turns with kept faces fixed; kept faces are never reclassified (their review colors are copied, not re-read).
- AD-3: the scan flow never touches a session; *Accept* returns through the same `ScanOutcome.Accepted` and the caller path (Story 10.15) is unchanged.
- AD-13: camera code stays in `app/src/camera/`; no frame from the retake is persisted or sent; no new permission.
- AD-18: no flag change; release camera flags stay OFF.
- All new Learner copy in `core/domain/src/main/resources/content/strings/en.json` (NFR-10).
- All-or-nothing: backing out of the retake viewfinder (system Back or the viewfinder *Close*) before every selected face is captured restores the exact pre-retake `GuidedScanPhase.Review` (same review, corrections, edited flag).
- A completed retake sets `edited = true` so *Close* asks "Discard this scan?".

**Never:**
- No named-top re-prompt in a retake run (controller `reprompt = { emptyList() }`); ambiguity/no-fit goes straight to review.
- No retake in Quick Scan review (`quickScanMode`), Quick Check or calibration runs.
- No new port, persisted entity, `SessionEvent`, or flag.
- Never re-apply earlier corrections to a retaken face.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Single valid fit | kept faces + 1 retaken sample whose 4 turns give exactly one valid state | Retaken face filled by the close-call rule in that turn; kept faces identical | None |
| Ambiguous | 2+ valid states | First valid assignment; retaken faces that differ across valid states all UNCERTAIN (blank) | Usual validation lines |
| No valid state | 0 valid | Assignment(s) with most legal pieces; retaken faces differing across tied best fits blank | Usual validation lines, no re-prompt |
| Kept blanks | a kept face still has blank stickers | Blank stays blank; a piece with a blank facet counts as not legal in the search | — |
| Retaken gap | retaken sample POOR_QUALITY ("use anyway"), MALFORMED, or claims another face's center | That retaken face is all blank (center fixed); kept faces unchanged | — |
| Uncalibrated | `SelfCalibration` over (original samples with retaken replaced) fails | Retaken faces blank; kept faces unchanged | — |
| Back-out | Back/Close in retake viewfinder after 0..k-1 captures | Pre-retake review restored exactly | — |
| Corrections | Learner edited stickers on face A (kept) and face B (retaken) | A's edits kept; B's dropped from stickers and from `corrections` | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- `analyze`/`place`/`search` (turn search over unposed faces, `legal`/`cubies`, best-fit + `differing` → UNCERTAIN). Add a retake entry, e.g. `assembleRetake(kept: Map<Face, List<Color?>>, retaken: List<FaceSample>, calibration): ScanResult.Full`: kept faces are fixed single options (null → a sentinel that makes the piece illegal; emitted as UNCERTAIN placeholder), retaken faces placed via `place` and searched over 4 turns; never returns re-prompt faces. Reuse `search` internals rather than duplicating.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- `from`, `preselected`, `corrected`, `fromSamples`, `ScanEvidence.without`. Add `ScanReview.retaken(before: ScanReview, retaken: List<FaceSample>, samples: List<FaceSample>, saved)` (name free): calibration = `SelfCalibration.from(original samples with retaken faces replaced, saved)`; kept face stickers copied from `before.stickers`; evidence = before's labs on kept faces + new labs on retaken faces; selection by the Story 10.3 `preselected` rule.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `ScanStep`, `GuidedScanPlan.steps` (fixed order G,R,B,O,W,Y), `GuidedScanController(plan, reprompt)`; retake plan = `GuidedScanPlan.steps.filter { it.face in selected }` (no top named).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanPhase.Review` (L95: add the six original `samples`), `Viewfinder` (L137: add retake context, e.g. `kept: List<FaceSample>` for the mini net and `fromReview: Review?` for back-out), `progressText` (L522, "Face n of N" already handles a subset), `MiniNet` (L624: `captured` → filled; kept faces must render as captured).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `reviewOf` (builds review from Done samples), `phaseOf(controller, quickCheck)`, `withCorrections`. Add the pure retake decisions (retake plan, retake phase, review-after-retake incl. trimmed corrections) here so they are JVM-tested.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `ScanReviewScreen(` call (~L218; pass `onRetake` only when `isGuidedRun`-style Guided 6-face review: not `quickScanMode`), `actions.onClose`/`BackHandler` (must restore the review during a retake), `handle` (~L560: Done → `reviewOf`; retake Done must build the merged review instead and must not go through `publish`'s VM `corrections` re-application), `GuidedScanViewModel.newController`/`publish`/`corrections`/`measuredCenters` (update `measuredCenters` from merged samples).
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- params L~100; `changed` from `edited`; `instruction()`; button column (Accept/Close/Enter colors); `StickerReviewNet` call; `oneFace` compact layout. Add retake mode (local `rememberSaveable` selection set) and `onRetake: ((Set<Face>) -> Unit)? = null`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- L197; needs a face-level selection overlay (accent outline + ✓ badge, static) and face-level click/semantics in retake mode.
- `core/domain/src/main/resources/content/strings/en.json` -- new keys: `btn.scan.retake-faces` "Retake faces", `msg.scan.retake-hint` "Tap each face to scan again.", `btn.scan.retake-count` "{count, plural, one {Retake # face} other {Retake # faces}}", `msg.scan.retake-face-selected` "{color} face, selected for retake". Reuse `btn.scan.cancel`.
- Tests: `core/domain/src/test/.../recognition/{ScanAssemblerTest,ScanReviewTest,RecognitionFixtures}.kt`; `app/src/testDebug/.../camera/{GuidedScanFlowTest,GuidedScanScreenTest}.kt`; `app/src/test/.../ui/scan/ScanReviewScreenTest.kt` (200% font / 600 dp checks follow existing patterns); `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` (Hilt route pattern, known flake at L450).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- add the retake assembly (kept faces fixed, retaken faces searched over 4 turns, best-fit/differing → UNCERTAIN, never re-prompt faces; a blank kept sticker makes its piece illegal without indexing past `CORNER_CUBIE`/`EDGE_CUBIE`) reusing `place`/`search` internals -- AD-9 single rotation site.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- add the retake review factory (kept stickers copied from the prior review, retaken faces from the retake assembly with the close-call rule, evidence merged, Story 10.3 pre-selection; uncalibrated → retaken faces blank) -- kept faces never reclassified.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- pure retake decisions: retake controller plan (fixed G,R,B,O,W,Y order filtered to selected faces, no top, `reprompt = { emptyList() }`), merged samples, review-after-retake (corrections on retaken faces dropped, `edited = true`, calibration offer recomputed), back-out = the stored pre-retake `Review` -- JVM-testable.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanPhase.Review` carries the six samples; `Viewfinder` carries retake context (pre-retake review for back-out, kept samples so the mini net shows kept faces as captured); progress "Face n of N" with N = selected faces.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- pass `onRetake` to `ScanReviewScreen` only for a Guided 6-face review (`scan.isGuidedRun`; never Quick Scan, Quick Check, calibration, Case capture); start a retake controller on the analyzer thread; on its Done build the merged review (bypassing `publish`'s re-application of VM `corrections`, but still honoring the capture freeze/`held` path) and update `measuredCenters` from merged samples; viewfinder *Close* and system Back during a retake restore the pre-retake review instead of finishing Cancelled; Accept path unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- add `onRetake: ((Set<Face>) -> Unit)? = null`; *Retake faces* outlined button directly after *Close* when non-null; retake mode per Design Notes (saveable selection, full net, hidden controls, Back = Cancel, instruction line, *Retake N faces*/*Cancel*).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- optional retake params (e.g. `retakeSelected: Set<Int>?`, `onFaceClick`): face-level click targets with Role.Button and the TalkBack labels below, static `accent` outline + ✓ badge (`on-accent` on an `accent` dot) on selected faces, sticker clicks/rings/problem marks/pulse off; Editor callers unchanged.
- `core/domain/src/main/resources/content/strings/en.json` -- add `btn.scan.retake-faces` "Retake faces", `msg.scan.retake-hint` "Tap each face to scan again.", `btn.scan.retake-count` "{count, plural, one {Retake # face} other {Retake # faces}}" (match the file's existing plural syntax), `msg.scan.retake-face` "{color} face", `msg.scan.retake-face-selected` "{color} face, selected for retake"; reuse `btn.scan.cancel`.
- Tests -- `ScanAssemblerTest`/`ScanReviewTest` (every I/O matrix row), `GuidedScanFlowTest` (retake plan order and no top, back-out restores exactly, corrections trimmed, edited flag), `GuidedScanScreenTest` (retake progress and mini net), `ScanReviewScreenTest` (every UI AC below incl. compact phone full net, 200% font at 600 dp, `onRetake == null` hides the button).

**Acceptance Criteria:**
- Given a Guided 6-face Scan review, when *Retake faces* is tapped, then the instruction line reads "Tap each face to scan again.", the full 6-face net shows (also on a compact phone), tapping a face toggles it, a selected face is outlined with a ✓ badge and announced "Green face, selected for retake" (color varies; unselected "Green face"), sticker taps edit nothing, and *Retake N faces* ("Retake 1 face" for one) is enabled only with ≥ 1 face selected.
- Given retake mode, when it renders, then only the title, net, instruction line, *Retake N faces* and *Cancel* show (no palette, face selector, problem lines, set/selected/suspect lines, coach mark, calibration offer, *Accept*, *Close*, *Enter colors instead*).
- Given retake mode, when *Cancel* or system Back is pressed, then retake mode ends with the selection cleared and the review unchanged (no discard dialog).
- Given faces selected, when *Retake N faces* is tapped, then the viewfinder asks only those faces in G,R,B,O,W,Y order with "Show the **green** center"-style prompts, never a named top, with "Face n of N" for N selected faces and the mini net showing kept faces as captured.
- Given a completed retake, when review reopens, then kept faces equal their pre-retake stickers (corrections included), retaken faces show only the new capture, it is out of retake mode, *Close* asks "Discard this scan?", and *Accept* returns `ScanOutcome.Accepted` exactly as before.
- Given a Quick Scan review, a Quick Check or a calibration run, when it renders, then no *Retake faces* button exists.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 29 findings — high 1, medium 5, low 11, false 12, maybe-false 0
- findings:
  - `[medium]` `[patch]` (verification-gap) Activity retake wiring (onRetake gate, startRetake/handleRetake, Close/Back back-out) untested — added ScanRouteTest retake route (Face 1 of 1, Back restores the same review, Activity not finishing) and review:retakeFaces absence checks in QuickScanRouteTest and QuickCheckRouteTest.
  - `[high]` `[patch]` (verification-gap) Frames during the 400 ms freeze after the last retake capture are not covered — fixed with the freeze-window guard below; covered by the guard, no separate frame-level test.
  - `[high]` `[patch]` (verification-gap other) After a retake Done clears scan.retake, handle()'s normal path replaces the retake viewfinder with the finished original controller's Viewfinder (fromReview null), so Close/Back cancel the scan — handle() no longer replaces a Viewfinder whose fromReview is set.
  - `[false]` `[reject]` (blind) Fake placeholder Labs on blank retaken stickers become suspect evidence — SuspectRanking.rank returns empty while any sticker is null and paint() drops a painted index's Lab; a fixed center with a Lab equal to its calibration center is never a suspect.
  - `[low]` `[reject]` (blind) Stale-post guard compares the review, not the run — needs a back-out and three taps to re-enter a retake before already-queued main posts drain; not met in practice, fix adds a token.
  - `[low]` `[reject]` (blind) Two startRetake calls can orphan the viewfinder — the screen leaves retake mode on the first tap; a second start needs a re-entry within one analyzer hop; fix adds an in-flight guard.
  - `[medium]` `[patch]` (blind) Selected face announces its state twice (selected semantics plus the suffix) against Design Notes — removed selected semantics; tests now assert the content description.
  - `[medium]` `[patch]` (blind) 54 sticker nodes stay in the accessibility tree in retake mode — stickers clear their semantics in retake mode (test tags kept).
  - `[low]` `[reject]` (blind) Entering retake mode discards ReviewMode's saveable view state (compact shown face) — review data is unchanged; the face re-derives from the selection; fix needs a SaveableStateHolder.
  - `[false]` `[reject]` (blind) Retake offered on reviews without six samples — every Guided-run review comes from GuidedScanFlow.reviewOf, which sets samples; publish(ScanReview) is only used by the Quick Scan path, which never offers retake.
  - `[low]` `[reject]` (blind) Debug diagnostics count retaken stickers as corrections via initialStickers — debug-only, no-op in release; changing initialStickers would undercount kept-face edits.
  - `[low]` `[reject]` (blind) Kept faces' old Labs paired with merged-calibration centers can shift suspects — spec-directed evidence merge; kept stickers unchanged; fix is not a direct correction.
  - `[false]` `[reject]` (blind) Activity untested — duplicate of the verification-gap finding, patched there (recorded as its own row: same claim).
  - `[medium]` `[patch]` (blind) Vacuous doesNotExist assertions in the retake-mode hidden-controls test — test now launches with onCalibrate, a wrong-counts review with a suspect and a painted sticker, asserts presence in normal review then absence in retake mode.
  - `[false]` `[reject]` (blind) Matrix "Retaken gap" uses BLURRED not POOR_QUALITY — any non-ACCEPTABLE quality (BLURRED included) is the POOR_QUALITY gap in place(); the row is covered.
  - `[low]` `[reject]` (blind) No tests for all-six retake, second retake, within-run repeat rule, Enter colors in retake — existing controller/assembler behaviour reused; low risk.
  - `[low]` `[reject]` (blind) Back-out leaves the analyzer CaptureFreeze running for ≤400 ms — at most a sub-second delay on an immediate re-retake.
  - `[false]` `[reject]` (blind) Spec record sections empty at in-review — fix is editing this build's spec; sections are filled at finalize.
  - `[false]` `[reject]` (edge) Freeze-window overwrite — duplicate claim of the high row above, patched there.
  - `[false]` `[reject]` (edge) Close/Back during the held window cancels the scan — same root cause, patched by the handle() guard.
  - `[medium]` `[patch]` (edge) Back-out after a held retake keeps measuredCenters from discarded samples — backOutOfRetake restores runScanColorsOf(from.samples, saved).
  - `[false]` `[reject]` (edge) Disagreeing retaken center becomes blank and uneditable — ScanReview's constructor forces every center to Color.home(face).
  - `[low]` `[reject]` (edge) Kept Labs vs new centers — same as the blind row; rejected.
  - `[low]` `[reject]` (edge) Stale post from a backed-out run — same as the blind row; rejected.
  - `[false]` `[reject]` (edge) retakeSelected with null strings gives no face targets — ScanReviewScreen always passes strings.
  - `[false]` `[reject]` (edge) onRetake turning null mid-retake leaves retake mode stuck — onRetake depends only on isGuidedRun/isQuickCheck, constant for the Activity.
  - `[false]` `[reject]` (edge claim) Spec claim that Close/Back restore the review broken in the freeze window — true before the patch; resolved by the handle() guard.
  - `[low]` `[reject]` (intent) Title kept in retake mode vs strict "only … remain" — clarification 2 scopes to review controls and its list omits the title; recorded as a device-session check.
  - `[low]` `[reject]` (intent) Back-out restores review data, not retake mode with its selection — AC is "nothing replaced (all or nothing)"; recorded as a device-session check.

### 2026-11-20 — Review pass (follow-up)
- verdicts: 29 findings — high 1, medium 8, low 15, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` (verification-gap) A retake that finishes is never run through the Activity (handleRetake, publishRetake, retake cleared, handle() guard) — added ScanRouteTest.finishedRetakeReturnsToAnEditedReviewWithOnlyTheRetakenFaceReplaced: feeds the retaken face through FakeFrames, asserts the merged Review (kept faces unchanged, F replaced), edited, vm.retake null, the main controller back.
  - `[medium]` `[patch]` (verification-gap) measuredCenters after a finished retake or a back-out unchecked — the new route test asserts measuredCenters == runScanColorsOf(merged samples); the GuidedScanFlowTest back-out test now sets a held review and a cleared measuredCenters and asserts runScanColorsOf(from.samples).
  - `[medium]` `[patch]` (verification-gap) Viewfinder Close during a retake unchecked — added ScanRouteTest.retakeViewfinderCloseRestoresTheReview (scanClose restores the same review, Activity not finishing).
  - `[medium]` `[patch]` (blind) No Activity-level test runs a retake to the end — same root cause as the first verification-gap row; patched there.
  - `[high]` `[patch]` (blind) Freeze-window guard in handle() has no frame-level test — carried: same claim as the logged high row ("covered by the guard, no separate frame-level test"); the guard still reads as described; not patched again. The new route test now passes a retake capture with its freeze through handleRetake.
  - `[medium]` `[patch]` (blind) Back-out tests miss Close, held/frozen clearing and measuredCenters — grouped with the Close and measuredCenters rows; the back-out test now also asserts frozen cleared and a held review never published by endFreeze.
  - `[low]` `[reject]` (blind) Mini net draws kept faces from raw sample Labs, so a corrected kept sticker shows its old read — spec-directed ("kept faces as captured"); a small progress thumbnail; fix adds a stickers path to the viewfinder.
  - `[low]` `[reject]` (blind) No diagnostics trace for a retake — debug-only logs, no Learner impact; fix adds new diagnostic events.
  - `[low]` `[reject]` (blind) scan.retake installed before the main thread switches phase — the gap is one main-thread hop, and a capture needs a sustained stable read over many frames (tests feed 16 frames at 100 ms); fix adds a guard.
  - `[false]` `[reject]` (blind) Data-class equals on large phase objects every frame — Kotlin data-class equals short-circuits on identity and fromReview/kept are the same references per run; the cost is negligible.
  - `[low]` `[reject]` (blind) Self-calibration on the main thread in handleRetake/backOutOfRetake — one run over six samples, once per retake; negligible.
  - `[low]` `[reject]` (blind) Retake mode ignores window size; landscape pushes the buttons down — the column scrolls, so the buttons stay reachable; fix adds a layout branch.
  - `[low]` `[patch]` (blind) assembleRetake returns placeholder Labs that look measured — KDoc of ScanAssembler.assembleRetake and ScanReview.retaken now state that kept faces and unplaced retaken faces carry calibration-center placeholders, not measurements.
  - `[low]` `[reject]` (blind) Test cleanup (duplicated scrambled fixture, formatter-split lambda, repeated helpers) — no defect; refactor only.
  - `[false]` `[reject]` (edge) Showing a kept face during a retake is not caught as repeated, so it is stored as the retaken face — the intent's matrix row "Retaken gap: claims another face's center → that retaken face is all blank" defines the outcome; the sample is never used as the retaken face's colors.
  - `[low]` `[reject]` (edge) Camera failure during a retake goes to Unavailable, whose Close cancels the scan — camera loss mid-run is rare; fix adds a branch.
  - `[low]` `[reject]` (edge) Enter colors in the retake viewfinder drops an edited review without the discard dialog — Design Notes keep its FellBackToEditor meaning, same as the normal viewfinder; a deliberate, labelled choice; fix needs a viewfinder dialog. Recorded as a device-session check.
  - `[false]` `[reject]` (edge) Blank stickers get home-center placeholder Labs as evidence, skewing suspects — carried: same claim as the logged false row; SuspectRanking (the only evidence.labs reader) returns empty while any sticker is blank.
  - `[low]` `[reject]` (edge) Kept Labs scored against merged centers — carried: same as the logged low rows.
  - `[low]` `[patch]` (edge claim) ScanReview.retaken KDoc says retaken evidence is the new Labs — KDoc corrected (placeholders on an unplaced retaken face).
  - `[low]` `[patch]` (edge claim) assembleRetake KDoc says labs holds the retaken faces' measured CIELAB — KDoc corrected (only placed retaken faces are measured).
  - `[medium]` `[patch]` (intent) Completed retake reopening the review is tested only below the Activity — grouped with the first verification-gap row; patched there.
  - `[medium]` `[patch]` (intent) "Close asks Discard this scan?" after a retake tested only as the edited flag — the new route test clicks review:close and asserts review:discard is displayed.
  - `[medium]` `[patch]` (intent) Back-out tested only for system Back with 0 captures, not viewfinder Close — Close route test added; back-out after 1..k-1 captures of a multi-face retake uses the same backOutOfRetake path and is left to the device session.
  - `[low]` `[reject]` (intent) Accept after a retake not tested — onAccept is unchanged and reads the published Review, which the new route test asserts.
  - `[low]` `[reject]` (intent) Calibration and Case capture rely on isGuidedRun with no test — both runs never render the Guided review with onRetake; fix adds tests for a negligible risk.
  - `[false]` `[reject]` (intent) enumerate refactor changes assemble with no test of its own — the existing ScanAssemblerTest assemble suite runs through enumerate and passes.
  - `[false]` `[reject]` (intent) Back-out allowed during the last capture's freeze is wider than "0..k-1" — the merged review was never shown, so restoring the pre-retake review is all-or-nothing as the intent asks; no bad outcome.
  - `[low]` `[reject]` (intent) Diagnostics skipped during a retake — same as the blind diagnostics row.

## Design Notes

Settled from the story, UX spec (EXPERIENCE.md:96–97, DESIGN.md:272), PO clarifications 2026-11-19 and precedent:
- Compact phone: retake mode switches the review to the full 6-face net; faces are toggled directly (PO clarification 1).
- Retake mode hides every other review control: palette, face selector, problem lines, set-sticker/selected-sticker/suspect lines, coach mark, calibration offer, *Accept*, *Close*, *Enter colors instead*; also the uncertain rings, problem marks and next-sticker pulse (DESIGN.md). Only the title (a heading, not a control), net, instruction line, *Retake N faces* (filled) and *Cancel* (outlined) remain (PO clarification 2).
- System Back in retake mode = *Cancel*: leaves retake mode, clears the selection; the review's discard `BackHandler` is inactive meanwhile (PO clarification 3).
- Face TalkBack labels: unselected "{Color} face", selected "{Color} face, selected for retake" — the AC's suffix is the only state marker; color names come from the existing `colorName` resources, capitalized as in the AC example.
- Selection is `rememberSaveable`, starts empty on entry; starting a retake leaves retake mode. The selected-face outline is static (no animation).
- Retake viewfinder: progress reuses "Face n of N" with N = selected faces (Quick Check precedent); the mini net shows kept faces as captured; viewfinder *Close* and Back both back out (calibration-from-review precedent `Calibration.fromReview`); *Enter colors instead* keeps its `FellBackToEditor` meaning; the repeated-face rule only compares within the retake run.
- After a retake, review reopens out of retake mode with pre-selection by the Story 10.3 rule; a later retake uses the merged samples.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (known flake: ScanRouteTest:450; re-run once if only it fails)

## Auto Run Result

**Summary:** Retake faces from the Guided 6-face Scan review. *Retake faces* enters a retake mode (full net on phones, face toggles with outline and ✓, TalkBack "Green face, selected for retake", *Retake N faces*/*Cancel*, Back = Cancel, every other control hidden). The viewfinder asks only the chosen faces in fixed order with no re-prompt. `ScanAssembler.assembleRetake` searches retaken faces' turns with kept faces fixed (AD-9). `ScanReview.retaken` keeps kept faces and edits and drops edits on retaken faces. Backing out restores the exact review, and a completed retake counts as edited.

**Files changed:**
- `core/domain/.../recognition/ScanAssembler.kt`: `assembleRetake` and a shared enumeration; blank facets make a piece illegal.
- `core/domain/.../recognition/ScanReview.kt`: `retaken` and `mergedSamples`.
- `core/domain/src/main/resources/content/strings/en.json`: 5 retake strings.
- `app/src/camera/.../GuidedScanFlow.kt`: `retakePlan`/`retakeRun`/`retakePhase`/`reviewAfterRetake`, plus `RetakeRun`.
- `app/src/camera/.../GuidedScanScreen.kt`: `Review.samples` and `Viewfinder.kept`/`fromReview`; the mini net shows kept faces as captured.
- `app/src/camera/.../GuidedScanActivity.kt`: `onRetake` gate, start and handle a retake, back-out, `publishRetake`.
- `app/src/main/.../ScanReviewScreen.kt`: retake mode.
- `app/src/main/.../StickerReviewNet.kt`: face-level retake targets, outline and badge; sticker semantics cleared in retake mode.
- Tests: `ScanAssemblerTest`, `ScanReviewTest`, `GuidedScanFlowTest`, `GuidedScanScreenTest`, `ScanReviewScreenTest`, `ScanRouteTest`, `QuickScanRouteTest`, `QuickCheckRouteTest`.

**Review findings:** 29 findings. 6 patch entries applied: high 1 (freeze-window phase overwrite, with its 2 gap rows), medium 5 (route tests, double TalkBack state, sticker a11y nodes, vacuous assertions, `measuredCenters` on back-out). 0 deferred. 12 false and 11 low were rejected; reasons are in the triage log.

**Follow-up review recommended:** true. A high was patched, the freeze-window guard in `GuidedScanActivity.handle()`. It has no frame-level route test: no test feeds a frame between the last retake capture and `endFreeze`. Patched by verdict: high 1, medium 5, low 0.

**Verification:** `./gradlew spotlessCheck build` (JDK 25): BUILD SUCCESSFUL before and after the patches. Every I/O matrix row has a passing test (`ScanAssemblerTest`, `ScanReviewTest`, `GuidedScanFlowTest`).

**Residual risks:**
- The device-session human gate (Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE, main app from the Solve tab) has not run yet. The orchestrator runs it with the PO after the PR opens.
- Readings to confirm with the PO in that session:
  - The review title stays visible in retake mode.
  - Backing out of the retake viewfinder returns to normal review, not to retake mode with the earlier selection.
  - Kept faces' suspect hints are re-ranked against the merged self-calibration.
- TalkBack behaviour on device is unverified; it was checked through semantics only.

### Follow-up review pass (2026-11-20)

**Review findings:** 29 findings (high 1 carried, medium 8, low 15, false 5). Patched: medium 8 in two root-cause groups (Activity retake completion untested; back-out Close/measuredCenters/held untested), low 3 (placeholder-Lab KDoc). 0 deferred. 18 rejected, with reasons in the triage log. No new high was patched.

**Files changed:**
- `core/domain/.../recognition/ScanAssembler.kt`: `assembleRetake` KDoc states which labs are placeholders.
- `core/domain/.../recognition/ScanReview.kt`: `retaken` KDoc states the same.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt`: `finishedRetakeReturnsToAnEditedReviewWithOnlyTheRetakenFaceReplaced`, `retakeViewfinderCloseRestoresTheReview`, `startRetakeOfFront` helper.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt`: back-out test asserts frozen and held cleared and `measuredCenters` restored.

**Follow-up review recommended:** false. This follow-up pass patched no high; the work has converged. Patched by verdict: high 0, medium 8, low 3.

**Verification:** targeted `ScanRouteTest` + `GuidedScanFlowTest` passed; `./gradlew spotlessCheck build` (JDK 25): BUILD SUCCESSFUL.

**Residual risks:** back-out after 1..k-1 captures of a multi-face retake, Enter colors from the retake viewfinder with an edited review, and the earlier device-session checks remain for the PO device session.
