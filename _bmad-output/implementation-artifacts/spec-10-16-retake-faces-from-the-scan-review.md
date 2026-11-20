---
title: 'Story 10.16: Retake faces from the scan review'
type: 'feature'
created: '2026-11-19'
status: 'ready-for-dev'
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
