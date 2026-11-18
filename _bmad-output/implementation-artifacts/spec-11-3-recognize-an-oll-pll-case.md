---
title: 'Story 11.3: Recognize an OLL/PLL Case'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '7b1e10e3edd59298f9266a20b09003259e41d559'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The corner-to-LastLayerView sticker mapping is proven only against synthetic CornerViews built from CubeState, not against the real 11.1 unwarp output.
    evidence: |-
      maybe-false. ScanAssembler.lastLayerView assumes CornerFrame.faces are [top, front-left, front-right] in net order with U8 the near corner (same assumption QuickScanPlan uses with orientation 0). Uniform-color route-test frames hide orientation. Settled by device session C (stickered cube, a known OLL/PLL Case named correctly from Library -> Recognize case).
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt (lastLayerView)
    severity: high (unverified)
---

<intent-contract>

## Intent

**Problem:** The Library has no way to name the Case on the Learner's real cube: there is no *Recognize case* entry, no `RecognizeCase` use case, and the camera adapter has no one-corner capture that hands back the U face and two sides.

**PO clarifications (2026-11-18, settled):** saved Color Calibration is used, else the 10.4 flow runs once first; prompt "Hold yellow on top, with one corner facing you."; two-angle capture is always required (the opposite corner, yellow still on top) before any Case is named; candidates of the captures are intersected; no-fit, LL-solved and manual-route states as in the matrix; never a silent pick.

**PO clarifications, round 2 (2026-11-18, settled):** (1) hold-2 prompt "Now turn the cube halfway, yellow still on top, so the opposite corner faces you." with Quick Scan's "1 / 2" counter; (2) every result state shows only after both corners are captured; (3) *Scan another angle* from *several* captures ONE more corner (hold-1 copy) and intersects again; from *no fit* it restarts with both corners and discards earlier candidates; (4) unique match first shows a result card "OLL 45 · T-shape"; tapping it opens Algorithm detail with its Demonstration playing (stepped under reduce motion); *Mark as Learning* is the detail's existing *Learning* status chip, never set automatically; (5) wrong second corner (same side centers as corner 1, a white or yellow side center, or sides that are not neighbours) re-prompts that hold with "That looks like the same corner — turn the cube halfway so the opposite corner faces you." (same corner / non-neighbours) or "Keep yellow on top." (white/yellow side center), then captures again — never the no-fit state; (6) a sticker the saved calibration reads UNCERTAIN is unseen in matching; (7) Color Calibration closed without saving returns to the Library.

**Approach:** Library top bar → *Recognize case* (only when `CASE_RECOGNITION` is above OFF; BETA uses the shared `BetaLabel`/`BetaNote`) → a two-hold corner capture (yellow on top, then the opposite corner) in the camera source set that reuses Story 11.1's `CornerLocator`/unwarp → a neutral classified three-face result per corner crosses the Activity boundary → `RecognizeCase` (`:core:application`) gets `LastLayerView`s from `ScanAssembler` and asks `LastLayerMatcher.candidates` / `isSolvedUpToAuf` → unique / several / no fit / LL solved / not-at-LL result states. Debug raises `CASE_RECOGNITION` to GA in the same change.

Refs: FR-28, FR-18, FR-27 · recognition NFRs · AD-9, AD-18, AD-19 · UX-DR19, UX-DR25 · UX Flow 5. Binding ADs: **AD-9**, **AD-18**, **AD-19**.

## Boundaries & Constraints

**Always:**
- AD-9: only the CameraX adapter in `app/src/camera/` touches pixels; main code reaches the capture only through the `ScanLauncher` port (new default method, overridden in `CameraScanLauncher`); only `ScanAssembler` rotates samples into sticker order, so the captured faces are turned into `LastLayerView` order (and the opposite corner merged into the first corner's frame) by new `ScanAssembler` functions, not in the adapter or the use case. Frames are never stored or sent.
- AD-18: *Recognize case* shows only when `flags.isEnabled(CASE_RECOGNITION)` and a recognize action exists; BETA shows the shared `BetaLabel` inside the control plus `BetaNote(msg.feature.camera.beta-note)`, TalkBack "<label>, BETA". *Start from my cube* in the not-at-LL state shows only when `PLACEMENT` is enabled and reuses the 11.2 Placement flow (`PlacementFlowViewModel`, `PlacementScreen`, `SessionHost.placeFromScan`). Debug `CASE_RECOGNITION=GA`; release/benchmark stay OFF.
- AD-19: `LastLayerMatcher` is the only definition of Case patterns and AUF; `RecognizeCase` only builds the view and calls `candidates` / `isSolvedUpToAuf`. The LL color is the U center read in the capture.
- All Learner text is `Text(key, args)` in `core/domain/src/main/resources/content/strings/en.json`.
- *Mark as Learning* is offered via the existing Algorithm detail status chips; never applied automatically.

**Never:** Define Case patterns or AUF outside `LastLayerMatcher`; write a second corner reader; pick one Case silently when several fit; change `PlaceFromScan.invoke`; edit planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Unique match | candidates = {one Case} | result card "OLL 45 · T-shape" (case name · family name); tapping it opens Algorithm detail of the Case's primary Algorithm with the Demonstration auto-playing (reduce motion steps, as the shared player does); status chips offer *Learning* | — |
| No saved calibration | `CalibrationRepository.load()` not Loaded | the 10.4 Color Calibration flow runs once, is saved via `UpdateCalibration`, then the Recognize capture starts; closed without Save → Library | — |
| Top not yellow | U center of a corner reads other than YELLOW | "Turn the cube so yellow is on top." and the same hold is captured again | — |
| Wrong second corner | hold 2 shares a side center with corner 1, or sides not neighbours | "That looks like the same corner — turn the cube halfway so the opposite corner faces you." and hold 2 again | — |
| Side center white/yellow | any hold | "Keep yellow on top." and the same hold again | — |
| Not at LL | after both corners, any seen side sticker outside the top row on F, R, B or L differs from its center | "The first two layers aren't solved yet." + *Start from my cube* iff `PLACEMENT` on | — |
| LL solved | the merged two-corner view is solved up to AUF | "Your last layer is already solved." + *Back to Library* | — |
| Ambiguous | candidates after both corners (and any extra angle) ≥ 2 | "This could be one of {n} cases" + candidate `AlgorithmTile`s (tap opens that detail) + *Scan another angle* (one more corner, intersected again) | — |
| No fit | candidates empty | "I couldn't match this to a case. Try another angle or better light." + *Scan another angle* (both corners again, earlier candidates discarded) + *Browse the Library* | — |
| Manual route | camera denied, or *Enter colors instead* | Manual Cube Editor; the accepted cube's LL goes through the same `RecognizeCase` path and states | — |

</intent-contract>

## Code Map

- `core/domain/.../lastlayer/LastLayerMatcher.kt` -- `candidates(view)`, `isSolvedUpToAuf(view)` (true only when all 20 stickers seen); reuse only.
- `core/domain/.../lastlayer/LastLayerView.kt` -- `partial(lastLayer, front, stickers)` (require: 20 stickers, `front` adjacent to `lastLayer`; `ring` = F,R,B,L center colors); `of(state, lastLayer)` returns `FirstTwoLayersUnsolved` or `View` (manual route). LAYOUT with LL on U: F block = U7,U6,F0,F1,F2; R = U5,U8,R0..2; B = U1,U2,B0..2; L = U3,U0,L0..2.
- `app/src/camera/.../CornerLocator.kt` `CornerGuide.quads` / `CornerReader.read` -- `CornerFrame.faces` is [top, front-left, front-right] `FrameStats` (9 `CellReading`s, `.value: Lab`, `.plain`), already in net order when front-left = F and front-right = R (QuickScan places them with orientation 0, up = U / B). Reuse as is.
- `core/domain/.../recognition/QuickScan.kt` -- `QuickScanController.onFrame` holds the metering/quality/steady auto-capture logic (L58-100) and `turnedOver`/`changed` (L190-215); hard-wired to `QuickScanPlan`.
- `core/domain/.../recognition/StickerClassifier.kt` -- `classify(lab, calibration)` → `Classified(StickerReading(color, CONFIDENT|UNCERTAIN))`.
- `core/domain/.../recognition/ScanAssembler.kt` -- the only rotator (AD-9); `rotateClockwise` L479. Add the corner functions here.
- `app/src/main/.../scan/ScanLauncher.kt`, `QuickCheckCapture.kt` (`QuickCheckContract` encode/decode of non-validated colors is the pattern for the new contract), `ScanFlowContract.kt`.
- `app/src/camera/.../GuidedScanActivity.kt` -- mode extras read in `onCreate` L116-137; `capturePhase()` L525; `saveCalibration` L563 (`fromReview == null` → `calibrationFinished`); `analyze` corner branch L397-405; `captureCorner` L411; `quickResult` → `setResult` L210-216.
- `app/src/camera/.../GuidedScanScreen.kt` -- `GuidedScanPhase.CornerViewfinder` L110; counter L208-219 (`msg.quick-scan.counter`); `Viewfinder(cornerHold)` L333; `CornerPrompt` L555 (swatches from `QuickScanPlan`).
- `app/src/camera/.../CameraScanLauncher.kt` -- add the override.
- `app/src/main/.../MainActivity.kt` -- `guidedScanAction` L100-140 (denial check, `scanInFlight`), `onPlacementScan` L143; register the new contract next to them and pass it to `AppShell`.
- `app/src/main/.../ui/shell/AppShell.kt` -- params L130-151; `ShellContent`; Placement evaluation `LaunchedEffect` L293 (welcome-only today) and `PlacementScreen` only inside the Welcome block L372-404; Library tab destination L640-686 (`openAlgorithm`, `AlgorithmDetailScreen`, `LibraryScreen`); back handler L583.
- `app/src/main/.../ui/library/LibraryScreen.kt` L69-110 (heading row), `familyHeading` L207; `AlgorithmTile.kt`; `AlgorithmDetailScreen.kt` (`rememberDemonstrationPlayerState` L115; status chips L163-174).
- `app/src/main/.../ui/placement/PlacementFlowViewModel.kt`, `PlacementScreen.kt` -- `Editing` with `ManualCubeEditor(strings, onAccepted, modifier)` is the editor pattern; reuse for *Start from my cube*.
- `app/src/main/.../ui/solve/SolveScreen.kt` `ScanButton` L170-195 and `ui/welcome/WelcomeScreen.kt` L128-163 -- BETA label + note + TalkBack pattern.
- `build-logic/.../policy/FeatureFlagLevels.kt` L50-56 + `build-logic/src/test/.../FeatureFlagLevelsTest.kt` L11.
- Tests: `app/src/test/.../ui/shell/AppShellPlacementTest.kt`, `AppShellLibraryTest.kt` (Robolectric shell tests), `app/src/testDebug/.../PlacementFromScanRouteTest.kt`, `QuickScanRouteTest.kt`, `camera/QuickScanScreenTest.kt`, `app/src/test/.../scan/QuickCheckContractTest.kt`; domain `core/domain/src/test/.../recognition/QuickScan*Test`, `lastlayer/LastLayerMatcherTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../recognition/CornerHold.kt` (new) + `QuickScan.kt` -- extract the metering / quality coaching / steady-hold auto-capture of `QuickScanController.onFrame` into an internal shared helper used by `QuickScanController` and the new controller; Quick Scan behavior and its tests stay unchanged -- one corner hold logic, no second corner reader.
- `core/domain/.../recognition/CaseCapture.kt` (new) -- `CornerView(top, left, right: List<Color?>)` (9 each, net order, centers non-null, UNCERTAIN non-centers null) and `CaseCaptureController(saved: CalibrationCenters, corners: Int /*2 or 1*/)`: `onFrame(detected, faces, now)` / `onShutter()` effects `Remeter`, `CornerCaptured(hold)`, `Done(views)`; exposes `hold`, `coaching`, `prompt: Text`. On capture it asks `ScanAssembler.cornerView` and applies the hold checks (Design Notes) — a failed check sets the re-prompt `prompt` and keeps the hold. After a capture or re-prompt, auto-capture waits until the view differs from the last captured one (`changed` rule of Quick Scan); the shutter always captures.
- `core/domain/.../recognition/ScanAssembler.kt` -- add `cornerView(samples [top, left, right], calibration): CornerView?` (classification; null when a sample is not ACCEPTABLE or malformed) and `lastLayerView(corner): CornerLastLayer` / `lastLayerView(first, opposite): CornerLastLayer` returning `View(view: LastLayerView, lowerRows: List<Pair<Color?, Color>>)` (seen sticker, its face center) or `NotOnRing`; the opposite corner's blocks F,R land in the first corner's B,L; a position both corners see with different colors becomes null.
- `core/application/.../usecase/RecognizeCase.kt` (new) -- `RecognizeCase.of(cases, algorithms)` (null when the matcher is `Invalid`); `fromCorners(views: List<CornerView>, prior: Set<CaseId>?)` and `fromCube(state)` → sealed `Recognition { Unique(caseId), Several(caseIds), NoFit, LastLayerSolved, NotAtLastLayer }`; order: not-at-LL, then solved, then candidates (intersected with `prior` when given). Two views = one merged opposite pair; one view = an extra angle.
- `app/src/main/.../scan/CaseCaptureContract.kt` (new) + `ScanLauncher.kt` + `app/src/camera/.../CameraScanLauncher.kt` -- `caseCaptureIntent(context, corners)` (default `guidedScanIntent`; camera override puts `GuidedScanActivity.EXTRA_CASE_CAPTURE`); contract `CaseCaptureInput(corners)` → `CaseCaptureOutcome { Captured(views), Cancelled, FellBackToEditor }`, encode/decode string arrays (null = absent marker), malformed → Cancelled.
- `app/src/camera/.../GuidedScanActivity.kt`, `GuidedScanFlow.kt`, `GuidedScanScreen.kt` -- case mode: no saved calibration → `Calibration()` phase first; Save → case corner viewfinder (not `calibrationFinished`); Close/back → Cancelled. `CornerViewfinder` gains the prompt text and total holds; case mode shows the prompt without swatches, counter "n / 2" only when two holds, no mini net. `Done` → `setResult(CaseCaptureContract.encode(views))`. *Enter colors instead* → `FellBackToEditor`.
- `app/src/main/.../ui/recognize/RecognizeFlowViewModel.kt`, `RecognizeScreen.kt` (new) -- phases `Idle`, `Editing(note)`, `Result(recognition)`; keeps `prior` for *Scan another angle*; `onCapture(outcome)` / `scanDenied()` / `editorAccepted(state)` / `evaluate(recognizeCase)` (pending capture evaluated by the shell once content is Ready, as Placement does). Screen renders the matrix states with a top *Close* (`btn.solve.close`) back to the Library; Editing uses `ManualCubeEditor` with notes `msg.solve.scan-camera-denied` / `msg.solve.scan-fell-back`.
- `app/src/main/.../ui/library/LibraryScreen.kt` -- `onRecognize: (() -> Unit)?`, `recognizeBeta: Boolean`: *Recognize case* button in the heading row (tag `library:recognize`), BETA label/note/TalkBack per pattern.
- `app/src/main/.../ui/library/AlgorithmDetailScreen.kt` -- `autoPlay: Boolean = false`: starts the player once on first composition.
- `app/src/main/.../ui/shell/AppShell.kt`, `MainActivity.kt` -- `onRecognize: ((corners: Int) -> Unit)?` + `recognizeFlow` params; MainActivity registers `CaseCaptureContract` only when `CASE_RECOGNITION` enabled and the launcher is present, with the denial check of `guidedScanAction`. Library tab order: open detail → Recognize screen (phase ≠ Idle) → Library. Unique card / tiles open detail (`autoPlay` for the card); back from detail returns to the result. *Start from my cube* launches `onPlacementScan`; `PlacementScreen` and its evaluation run outside Welcome too (Accept keeps the 11.2 `startCourse` behavior).
- `core/domain/src/main/resources/content/strings/en.json` -- new keys (Design Notes).
- `build-logic/.../FeatureFlagLevels.kt` + `FeatureFlagLevelsTest.kt` -- debug `CASE_RECOGNITION=GA`.
- Tests -- domain: `CaseCaptureControllerTest` (holds, every re-prompt, uncertain → null, wait-for-change), `ScanAssemblerCornerTest` (mapping to LAYOUT, merge, conflicts, NotOnRing); application: `RecognizeCaseTest` (every matrix state from real Case states via `LastLayerMatcher.caseState` + yellow, merged opposite pair names one Case, extra-angle intersection, `fromCube`, matching < 1 s); app: `CaseCaptureContractTest`, `RecognizeScreen`/`AppShellRecognizeTest` (button hidden OFF, BETA label/note/TalkBack, each state's copy and buttons, *Start from my cube* only with PLACEMENT, card → detail autoplay, chip not set automatically, back returns to result), a testDebug route test tapping *Recognize case* and reaching the capture, `GuidedScanActivity` case-mode tests (calibration first, close → Cancelled, prompts/counter).

**Acceptance Criteria:**
- Given `CASE_RECOGNITION` is BETA or GA and a camera binding, when the Learner taps Library → *Recognize case* and captures two opposite top corners, then `RecognizeCase` returns a `Recognition` through `LastLayerMatcher` under AUF in under 1 s and the matching state shows.
- Given `CASE_RECOGNITION` is OFF, when the Library shows, then no *Recognize case* control exists.
- Given the debug build, when `FeatureFlagLevels.forBuildType("debug")` is read, then it reports `CASE_RECOGNITION=GA` and release/benchmark report OFF.

## Spec Change Log

- 2026-11-18 (resume): intent contract updated with the PO clarifications (calibration, prompt, two-angle capture, intersection, no-fit, LL solved, manual route); Code Map extended.
- 2026-11-18 (resume 2): round-2 PO answers added to the intent (Unique row now card-first; wrong-corner rows added; Scan another angle shapes); Tasks and Design Notes finalized.

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 28 findings — high 0, medium 3, low 9, false 15, maybe-false 1
- findings:
  - `[false]` `[reject]` Blind: starts with two corners, story says one — PO rounds 1-2 settled two opposite corners always before any result.
  - `[false]` `[reject]` Blind: unique match should auto-open detail — PO round 2 (4) settled a result card first, tap opens detail.
  - `[false]` `[reject]` Blind: retry discards prior observations / generic prompt — PO round 2 (3) settled one more corner with the hold-1 copy, candidates intersected.
  - `[medium]` `[patch]` Blind: conflicting top stickers become wildcards in the merged view — RecognizeCase now also intersects each corner's own candidates; test `aStickerTheCornersReadDifferentlyNeverBringsBackACaseOneCornerRulesOut`.
  - `[low]` `[reject]` Blind: uncertain centers taken as definite — spec Design Notes choice; a wrong ring needs both side centers misread consistently past the neighbour/opposite checks; fix adds a new re-prompt branch.
  - `[low]` `[reject]` Blind: unreadable lower rows pass the F2L check — PO round 2 (6) makes UNCERTAIN unseen; needs every differing F2L sticker to read UNCERTAIN; fix adds an evidence threshold.
  - `[false]` `[reject]` Blind: extra angle can return LastLayerSolved — `isSolvedUpToAuf` needs all 20 stickers; one corner leaves 10 null.
  - `[low]` `[patch]` Blind: rejected frames keep the steady timer — `CornerHold` now clears its steady reference when auto-capture is not allowed.
  - `[low]` `[reject]` Blind: no feedback when a shutter tap cannot capture — same as Quick Scan's corner pattern (coaching line only); fix adds UI.
  - `[low]` `[patch]` Blind: matcher rebuilt per capture on main — AppShell now `remember`s one RecognizeCase per loaded catalog.
  - `[low]` `[patch]` Edge: extra-angle result after process death names a Case from one corner — `evaluate` drops a one-corner capture with no earlier Several; test `aSingleCornerWithoutEarlierCandidatesNamesNothing`.
  - `[low]` `[patch]` Edge: time on the previous corner counts toward the steady hold — same root and fix as the CornerHold row above.
  - `[low]` `[reject]` Edge: Close while the denial check is pending can reopen the editor — the check is a millisecond SharedPreferences read; fix adds flow revisions.
  - `[medium]` `[patch]` Edge: the two initial captures are not intersected — same root and fix as the Blind conflict row.
  - `[medium]` `[patch]` Edge: card reads "OLL 45 · T" not "OLL 45 · T-shape" — letter families renamed in en.json (P-shape, T-shape, C-shape, W-shape, L-shape); AppShellRecognizeTest asserts "OLL 45 · T-shape".
  - `[low]` `[patch]` VerificationGap: extra-angle test cannot detect dropped prior candidates — added `anExtraAngleKeepsTheCasesTheFirstPairRuledOutExcluded` (PLL pair then an OLL corner -> NoFit).
  - `[low]` `[patch]` VerificationGap: Placement from the Library untested past the callback — added `placementStartedFromRecognitionShowsItsSuggestionAfterOnboarding`.
  - `[low]` `[patch]` VerificationGap: editor Accept route untested — added `enterColorsInsteadOpensTheEditorAndItsAcceptedCubeIsRecognized`.
  - `[false]` `[reject]` Intent: merge versus intersect — now both (patched above); merge is also what the AD-9 approach text says.
  - `[false]` `[reject]` Intent: same-corner pair is NoFit below the capture surface — the controller re-prompts and never emits such a pair; NoFit there is the defensive path for a malformed result.
  - `[false]` `[reject]` Intent: card text — patched above (counted there).
  - `[false]` `[reject]` Intent: UNCERTAIN centers exempt — recorded spec choice (centers fix the frame); same as the Blind row.
  - `[maybe-false]` `[defer]` Intent: sticker order not tied to the real unwarp orientation — deferred to device session C (frontmatter `deferred`).
  - `[false]` `[reject]` Intent: Placement reuse only callback-tested — test added (counted above).
  - `[false]` `[reject]` Intent: reduce-motion stepping not tested — the shared player owns it and is tested in its own suite; the diff only calls `play()`.
  - `[false]` `[reject]` Intent: extra angle can be "LL solved" / loses AUF consistency — one corner cannot be solved-up-to-AUF; per-capture intersection is the PO's rule.
  - `[false]` `[reject]` Intent: QuickScan refactor has no new tests — existing QuickScanController/QuickScanScreen/QuickScanRoute tests pass unchanged.
  - `[false]` `[reject]` Intent: silent shutter on a poor frame — same as the Blind feedback row (counted there).

## Design Notes

**Corner → `LastLayerView`** (ScanAssembler): with top `t`, left `f`, right `r` in net order, stickers = `[t7,t6,f0,f1,f2, t5,t8,r0,r1,r2, t1,t2,_,_,_, t3,t0,_,_,_]`, `partial(lastLayer = t4, front = f4, …)`. Lower rows = `f3,f5..f8` vs `f4`, `r3,r5..r8` vs `r4`. Opposite corner `(t', f', r')` fills blocks B = `[t'7,t'6,f'0..2]`, L = `[t'5,t'8,r'0..2]` of the first view and is checked against the first view's U stickers via its own `t'` (U rotated by y2).

**Hold checks** (in order, every hold): U center (nearest color, any confidence) ≠ YELLOW → `msg.recognize.turn-yellow-up`; a side center WHITE/YELLOW → `msg.recognize.keep-yellow-up`; right center ≠ the left center's clockwise neighbour around yellow (`LastLayerView.partial(...).ring[1]`) → hold 2: `msg.recognize.same-corner`, hold 1 / extra angle: its own hold copy again (a misread; recorded choice); hold 2 sharing any side center with corner 1 (same or quarter-turned corner, so not all four sides seen) → `msg.recognize.same-corner`. Centers use their nearest color; non-center UNCERTAIN → null.

**Recorded cosmetic / pattern choices:** card text `msg.recognize.match` = "{case} · {family}" from the Library's own names (`case.*.name`, `msg.library.family.*`), i.e. the PRD's "T-shape family" reads with the Library family name; extra-angle hold shows no counter; case mode hides the mini net; a cancelled extra-angle/no-fit rescan returns to the result it came from, a cancelled first capture returns to the Library; results have a top *Close* like `PlacementScreen`; detail back returns to the result.

**New en.json keys:** `btn.library.recognize-case` "Recognize case"; `msg.recognize.hold-first`, `msg.recognize.hold-second`, `msg.recognize.turn-yellow-up`, `msg.recognize.keep-yellow-up`, `msg.recognize.same-corner` (PO copy verbatim); `msg.recognize.match`; `msg.recognize.several` "This could be one of {n} cases"; `msg.recognize.no-fit`; `msg.recognize.solved`; `msg.recognize.not-at-ll`; `btn.recognize.scan-another-angle`, `btn.recognize.browse-library` "Browse the Library", `btn.recognize.back-to-library` "Back to Library", `btn.recognize.start-from-cube` "Start from my cube".

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply spotlessCheck build` -- expected: BUILD SUCCESSFUL (tests, Kover, boundary/denylist gates).
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL.

**Manual checks (device session C, human gate):** capture acceptance with the stickered cube on the debug build from Library → *Recognize case*.

## Auto Run Result

Status: done

**Summary:** Library -> *Recognize case* (shown only when `CASE_RECOGNITION` is above OFF; BETA label/note/TalkBack) runs Color Calibration first when none is saved, then a two-hold corner capture (yellow on top, then the opposite corner, "1 / 2" counter, re-prompts for non-yellow top, white/yellow side, same or non-opposite corner). Classified corners cross the Activity boundary via `CaseCaptureContract`; `RecognizeCase` gets LastLayerViews from `ScanAssembler` and matches through `LastLayerMatcher` (merged pair intersected with each corner's own candidates). Result states: unique card -> Algorithm detail auto-playing, several + tiles + Scan another angle (one corner, intersected), no fit (fresh two-corner rescan / Browse the Library), LL solved, not at LL with *Start from my cube* (11.2 Placement, now also after onboarding). Manual route via the Manual Cube Editor. Debug `CASE_RECOGNITION=GA`.

**Files:** domain `CornerHold.kt` (shared steady hold), `CaseCapture.kt` (CornerView, CaseCaptureController), `QuickScan.kt` (uses CornerHold), `ScanAssembler.kt` (cornerView, lastLayerView single/pair); application `RecognizeCase.kt`; app `ScanLauncher.kt`, `CaseCaptureContract.kt`, `MainActivity.kt`, `AppShell.kt`, `LibraryScreen.kt`, `AlgorithmDetailScreen.kt` (autoPlay), `ui/recognize/*`; camera `CameraScanLauncher.kt`, `GuidedScanActivity.kt`, `GuidedScanFlow.kt`, `GuidedScanScreen.kt`; `en.json` (new keys; letter OLL families renamed "X-shape"); `FeatureFlagLevels.kt`; tests (domain, application, app, testDebug route).

**Review:** 28 findings; 8 patch rows (3 medium, 5 low entries after grouping: intersection, card text, CornerHold steady reset, matcher reuse, one-corner guard, 3 test gaps); 1 deferred (sticker-order vs real unwarp, settled by device session C); rest rejected with reasons in the triage log.

**Follow-up review recommended:** true — two medium entries were patched (intersection of per-corner candidates; family label copy). Specific unverified risk: the patched intersection path and the renamed Library family labels have not had an independent review pass.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL; `./gradlew -p build-logic test` BUILD SUCCESSFUL (after patches).

**Residual risks:** device session C (human gate) still pending — capture acceptance with the stickered cube and the deferred sticker-order check; Library family chips/headings now read "P-shape/T-shape/C-shape/W-shape/L-shape" instead of "P/T/C/W/L shape".
