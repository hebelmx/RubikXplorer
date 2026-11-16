---
title: 'Review and correct a Scan Result'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_revision: 'def7473c8502e89f60d527b8a4e2c13693a58424'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      GuidedScanActivity review wiring (Accept/Close finishing the Activity, camera unbind on entering review, review surviving rotation) has no Activity-level test.
    evidence: |-
      Verification-gap layer: replacing onAccept with finishWith(Cancelled) or deleting the unbind effect leaves every test green; only the ViewModel phase and the standalone screen are tested. Same untested Activity-glue layer deferred in Story 10.2 (needs Hilt + CameraX under Robolectric); check on device in session B or add an Activity test harness.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt (Review phase, finishWith, LaunchedEffect unbind)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The Guided Scan (10.2) returns raw `FaceSample`s; nothing lets the Learner see the read, fix misread stickers, or get a validated cube back, so a bad read could reach the Solver or tutor.

**Approach:** After the sixth capture the scan Activity assembles a `ScanResult` and shows a review screen: confident stickers prefilled, uncertain ones blank and pre-selected, tap-sticker-then-color correction with auto-advance, sticker-specific invalid messages, and *Accept* only for a complete valid cube. The Activity returns a typed `ScanOutcome {Accepted(CubeState), Cancelled, FellBackToEditor}`; only the caller acts on it.

## Boundaries & Constraints

**Always:**
- Prefill rule = `StickerConfidence.CONFIDENT` from `ScanAssembler` (the 10 ΔE close-call rule); every UNCERTAIN non-center sticker, every non-center sticker of a `Partial` gap face, and every non-center sticker when `SelfCalibration` returns `NeedsCalibration` starts blank (no color). Centers are always the home color (fixed by the prompt) and not editable.
- Review logic is a pure, immutable domain value in `:core:domain` (Kover-covered); the UI only renders it and forwards taps.
- Selection on open = lowest-index blank sticker (null if none). Painting a color sets the selected sticker, then selection moves to the next blank after it in index order, wrapping; if no blank remains, selection stays. Tapping any non-center sticker selects it.
- *Accept* enabled only when no sticker is blank AND `CubeValidator.validate` is `Valid`; it returns that `CubeState`. `CubeValidator` stays the only route to `CubeState`; the decoder re-validates.
- Invalid-state messages: per-piece lines for every corner/edge whose stickers are all filled and that show a repeated color ("This corner shows two whites.") or two opposite colors (W/Y, G/B, R/O), outlining only the offending stickers; plus `CubeValidator.problems` lines (existing keys and outlines) once no sticker is blank. Faces holding outlined or blank stickers are badged in one-face mode.
- All new Learner text (copy and content descriptions) in `core/domain/src/main/resources/content/strings/en.json`, resolved through `ContentStrings`; no new `strings.xml` entries.
- Samples never leave the scan Activity; the result carries only the accepted 54 colors.

**Never:**
- No `SessionEvent`, `ScanAccepted`, reducer change, session or `SessionHost` reference in scan/review code; no Solve-tab or Learner entry point (10.5); no calibration persistence or recalibrate prompt (10.4); no per-face retake; no rotation search (10.14).
- No change to `ManualCubeEditor` behavior, `CubeValidator` rules, `StickerClassifier`, or the release camera flags/gates.
- Never fill a blank from an expected state or from the classifier's uncertain guess.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| All confident, valid | Full, 54 CONFIDENT | No blanks, no selection, no problem lines, *Accept* enabled → `Accepted(state)` | — |
| Uncertain stickers | Full with UNCERTAIN at 7, 20 | 7 and 20 blank, 7 selected; paint → 20 selected; paint → selection stays; *Accept* enabled iff valid | — |
| Partial | gap on B | B's 8 non-centers blank, center BLUE; *Accept* disabled until filled and valid | — |
| Needs calibration | `SelfCalibration.NeedsCalibration` | all 48 non-centers blank | — |
| Two whites on a corner | filled corner W,W,R | line "This corner shows two whites.", the two white stickers outlined, *Accept* disabled | — |
| Opposite colors | filled edge W,Y | line for opposite colors, both outlined | — |
| Wrong counts / twist / flip / parity | complete, invalid | existing validator lines and outlines; *Accept* disabled | — |
| Close / back | review or viewfinder | `Cancelled` | — |
| *Enter colors instead* | rationale/denied cards | `FellBackToEditor` | — |
| Bad result payload | missing/invalid facelets with RESULT_OK | decodes as `Cancelled` | never throws |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/{ScanAssembler,StickerClassifier,SelfCalibration}.kt` -- `ScanResult.Full(facelets, confidence)` / `Partial(observation.faces, confidence, gaps)`; `SelfCalibration.from(samples)` → `Calibrated(calibration)` | `NeedsCalibration`; assembly pipeline is exactly `summarize(samples)` in the debug activity.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/{CubeValidator,Facelets}.kt` -- `problems()`/`validate()`; internal `FaceletTables.CORNER_FACELETS` / `EDGE_FACELETS` (home-frame sticker indices per piece; centers are home in review, so no orientation mapping). `PieceKind.id` is the precedent for a `{piece}` arg.
- `app/src/main/kotlin/dev/cubentia/app/scan/GuidedScanContract.kt` -- current `GuidedScanCapture` + contract (`encode`/`decode`, `RESULT_FELL_BACK_TO_EDITOR`); replace with the `ScanOutcome` contract.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `finishWith` (L202), `GuidedScanViewModel.result` (L406) carries a finished result across rotation; `GuidedScanFlow.resultOf` (GuidedScanFlow.kt) maps `Done` effects. `GuidedScanScreen.kt` `GuidedScanPhase` sealed type.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/scan/GuidedScanDebugActivity.kt` -- session-B launcher; switch to the new contract.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/{ManualCubeEditor,StickerReviewNet,ManualCubeEditorState}.kt` -- reuse `StickerReviewNet` (extend for blank/`?`), `Swatch`, `FaceSelector`, one-face rule, `currentWindowSizeClass` (make the needed private composables `internal`; editor output unchanged).
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` -- `fontScale200`, `assertNoMidWordBreaks`, `assertNoTextOverflow`, `assertClickablesAtLeast48dp`; `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt` -- Robolectric pattern (`@Config(application = Application::class, qualifiers = ...)`, shipped `ContentStrings`).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/{GuidedScanContractTest,GuidedScanFlowTest}.kt`, `.../camera/scan/` -- tests to update with the contract change.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- immutable `ScanReview(stickers: List<Color?>, selected: Int?)` with `from(result: ScanResult)`, `fromSamples(samples)` (SelfCalibration → assemble, NeedsCalibration → all non-centers blank), `select`, `paint`, `blanks`, `problems: List<ReviewProblem(reason: Text, positions)>`, `outlined`, `accepted: CubeState?` -- the rules in Boundaries.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt` -- per-corner/edge repeated-color and opposite-color problems on a `List<Color?>` (skip pieces with a blank) -- needs internal `FaceletTables`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanReviewTest.kt` + `.../cube/PieceCheckTest.kt` -- every I/O-matrix row, auto-advance wrap, center ignored on select/paint, problems ordering, accepted only when complete and valid.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.validation.piece-repeats.<color>` ×6 (`{piece}` arg), `msg.validation.piece-opposite`, review title/hint, blank-sticker state description, `btn.scan.accept`.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanOutcome.kt` + `ScanFlowContract.kt` (replacing `GuidedScanContract.kt`/`GuidedScanCapture`) -- `ActivityResultContract<Unit, ScanOutcome>`; `encode`/`decode`, Accepted carries 54 colors and decodes only via `CubeValidator` `Valid`.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- net (full net, or one face with `FaceSelector` when compact / cells < touchMin), palette, problem lines in a polite live region, *Accept* and *Close*; blank stickers drawn with a neutral (non-white) fill, dashed hairline and `?`; test tags `review:*`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/{StickerReviewNet,ManualCubeEditor}.kt` -- nullable colors + `unresolved` support, shared composables `internal`; editor visuals unchanged.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{GuidedScanActivity,GuidedScanFlow,GuidedScanScreen}.kt` -- on `Done`, unbind the camera and show a `Review` phase holding `ScanReview` in the ViewModel (survives rotation); taps update it; *Accept*/*Close*/back/*Enter colors instead* finish via the new contract.
- `app/src/camera/debug/.../scan/GuidedScanDebugActivity.kt` -- launch the new contract; summary per outcome.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- blank+selected rendering, two-touch correction, auto-advance, Accept disabled/enabled, "two whites" line and outline; `w600dp-h960dp` with `fontScale200`: `assertNoMidWordBreaks`, `assertNoTextOverflow`, 48 dp clickables; `w360dp-h640dp` one-face mode.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/` -- update contract/flow tests: round-trip of each outcome, invalid Accepted → Cancelled.
- `docs/accessibility-manual-check.md` -- add a scan-review line: 200% type on the Lenovo at 600 dp, no mid-word break, nothing cut off.

**Acceptance Criteria:**
- Given a finished six-face scan, when review opens, then only CONFIDENT stickers are prefilled, uncertain ones are blank with the first selected, each blank is fixed with at most two touches (one when auto-advance lands on it), and an invalid piece is named ("This corner shows two whites.") with its stickers outlined.
- Given any blank sticker or an invalid state, when the Learner looks at *Accept*, then it is disabled.
- Given the scan flow completes, when it finishes, then the caller receives a `ScanOutcome` through `ScanFlowContract`, and `grep -rnE "SessionEvent|SessionHost|dev\.cubentia\.app\.session|dev\.cubentia\.application\.session" app/src/camera app/src/main/kotlin/dev/cubentia/app/scan app/src/main/kotlin/dev/cubentia/app/ui/scan` finds nothing.
- Given the review at 200% font and `w600dp`, when it renders, then no word breaks mid-word and no text overflows.
- Given `./gradlew spotlessCheck build`, when run, then it passes (Kover ≥ 80 % on `:core:domain`; release classpath/manifest gates unchanged).

## Spec Change Log

## Review Triage Log

### 2026-11-16 — Review pass
- verdicts: 25 findings — high 0, medium 7, low 13, false 5, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) Close yellow/white pairs not tested as blank — the classifier's 10 ΔE close-call rule marks them UNCERTAIN (Story 10.1 tests) and `ScanReview.from` blanks every UNCERTAIN sticker (ScanReviewTest); the composition holds.
  - `[low]` `[reject]` (intent) One-face mode needs a face-tab tap before fixing a sticker on another face (three touches) — the tab is navigation, not color entry; blanks are reached by auto-advance across faces, and the shipped editor uses the same pattern; widths ≥ 600 dp show the full net.
  - `[false]` `[reject]` (intent) Count/twist/flip/parity lines don't name stickers — these failures belong to no single piece; the approved EXPERIENCE.md copy gives them their own non-local lines.
  - `[low]` `[patch]` (intent) Contract test lost the 10.2 "activity declared in the merged manifest" assertion — restored `resolveActivity` assertion in `GuidedScanContractTest`.
  - `[false]` `[reject]` (intent) No production caller, so "only the caller dispatches" is unobserved — the Solve entry is Story 10.5; the flow returns a typed value and holds no session reference (grep clean).
  - `[medium]` `[defer]` (intent) Activity integration (Accept → finish, unbind, rotation) untested — same root as the verification-gap Activity findings; deferred.
  - `[low]` `[patch]` (intent) 200% check rendered one fixed state only — the w600dp/200% test now renders blanks, the selected-sticker line and a problem line together.
  - `[low]` `[reject]` (intent) Debug summary lost calibration/gap diagnostics — operator-only launcher; the review screen now shows the read itself; restoring adds code to a debug tool.
  - `[low]` `[reject]` (intent) NeedsCalibration blanks the whole cube without explanation — Story 10.4 AC1 owns the "Colors look off — calibrate?" prompt for many uncertain stickers.
  - `[false]` `[reject]` (intent) Editor composables made internal — module-internal reuse, no named harm.
  - `[medium]` `[patch]` (blind) Switching face tabs leaves the selection on a hidden face; the palette then paints it — tab tap now selects that face's first blank, and the palette paints only when the selection is on the shown face; test `switching faces never lets the palette paint a hidden sticker`.
  - `[low]` `[reject]` (blind) NeedsCalibration discards the failure information — same as the intent NeedsCalibration row (Story 10.4).
  - `[low]` `[reject]` (blind) PieceCheck skips a piece with a blank even when two filled stickers already repeat — the line appears once the piece is filled and Accept is disabled meanwhile; no wrong outcome.
  - `[low]` `[reject]` (blind) Color-count errors wait until every blank is filled — Accept is disabled while blanks remain; the count line appears on completion.
  - `[low]` `[reject]` (blind) Mirrored corners / duplicate cubies not reported during partial entry — same as above; CubeValidator reports them once complete.
  - `[medium]` `[patch]` (blind) Auto-advance moves the selection silently for TalkBack users — added a polite live-region line "Selected: {sticker}" (`msg.scan.selected`, tag `review:selected`), asserted in two tests.
  - `[low]` `[reject]` (blind) `ScanReview` constructor accepts a short list and throws — every caller passes 54; loud failure on misuse.
  - `[low]` `[reject]` (blind) `StickerReviewNet` allows invalid nullable combinations that crash — two callers, both correct; loud failure.
  - `[medium]` `[defer]` (blind) Activity-to-review lifecycle untested — same root as the verification-gap Activity findings.
  - `[medium]` `[patch]` (edge) Palette paints a hidden-face sticker after a face switch — same root as the blind face-switch row; same fix.
  - `[low]` `[reject]` (edge) `ScanReview` short list throws — same as the blind constructor row.
  - `[false]` `[reject]` (edge) `PieceCheck.problems` short list throws — only `ScanReview` calls it, always with 54 stickers.
  - `[low]` `[reject]` (edge) Blank stickers without `strings` crash `StickerReviewNet` — same as the blind nullable-parameter row.
  - `[medium]` `[defer]` (verification-gap) Accept → `finishWith(Accepted)` through the Activity untested — Activity glue needs Hilt + CameraX under Robolectric, deferred since 10.2; added to `deferred`.
  - `[medium]` `[defer]` (verification-gap) Camera unbind on entering review untested — same root; deferred.

## Design Notes

Review is hosted in the camera Activity but rendered by a main-source-set composable, so main never imports camera code and the screen is Robolectric-testable without a camera. The camera is unbound on entering review. Back from review cancels (retake is a future story). Process death during review restarts the scan (same accepted trade-off as 10.2).

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, all suites green.
- `grep -rnE "SessionEvent|SessionHost|dev\.cubentia\.app\.session|dev\.cubentia\.application\.session" app/src/camera app/src/main/kotlin/dev/cubentia/app/scan app/src/main/kotlin/dev/cubentia/app/ui/scan` -- expected: no output.

**Manual checks (if no CLI):**
- Device session B: scan on the Lenovo and S24 FE, correct a blank, Accept returns to the debug launcher with "Accepted".

## Auto Run Result

**Summary:** After the sixth capture the Guided Scan Activity assembles the samples into a `ScanReview` and shows `ScanReviewScreen`: confident stickers prefilled, uncertain/gap stickers blank with the first selected, tap-sticker-then-color with auto-advance, per-piece lines ("This corner shows two whites.") plus validator lines, and *Accept* only for a complete valid cube. The Activity returns `ScanOutcome {Accepted(CubeState), Cancelled, FellBackToEditor}` through `ScanFlowContract`; samples no longer leave the Activity, and no session code is referenced.

**Files changed:**
- `core/domain/.../recognition/ScanReview.kt` -- immutable review value (prefill, select, paint/auto-advance, problems, accepted).
- `core/domain/.../cube/PieceCheck.kt` -- repeated-color and opposite-color checks per corner/edge.
- `core/domain/src/main/resources/content/strings/en.json` -- piece lines, review copy, blank-sticker labels, `btn.scan.accept`, `msg.scan.selected`.
- `app/src/main/.../scan/ScanOutcome.kt`, `ScanFlowContract.kt` -- typed result; replaces `GuidedScanContract.kt` (deleted).
- `app/src/main/.../ui/scan/ScanReviewScreen.kt` -- review UI.
- `app/src/main/.../ui/editor/StickerReviewNet.kt`, `ManualCubeEditor.kt` -- blank-sticker rendering; shared composables made internal.
- `app/src/camera/.../GuidedScanActivity.kt`, `GuidedScanFlow.kt`, `GuidedScanScreen.kt`, `AndroidManifest.xml` -- Review phase, camera unbind, typed finish.
- `app/src/camera/debug/.../GuidedScanDebugActivity.kt` -- launches the new contract.
- Tests: `ScanReviewTest`, `PieceCheckTest`, `ScanReviewScreenTest`, updated `GuidedScanContractTest`, `GuidedScanFlowTest`, `GuidedScanDebugSummaryTest`.
- `docs/accessibility-manual-check.md` -- scan-review 200% check line.

**Review findings:** 25 findings. Patched: face switch could paint a hidden sticker (medium), auto-advance not announced (medium), manifest assertion restored (low), 200% test widened to blanks + lines (low). Deferred: Activity-level review wiring untested (medium, carried from the 10.2 Activity-glue deferral). Rejected: see the triage log rows with their reasons.

**Follow-up review recommendation:** true -- two medium entries were patched on this first pass (patched counts: high 0, medium 2, low 2). Unverified risk: the face-switch selection rule and the TalkBack live-region announcement are verified only under Robolectric, and the Activity Review phase (unbind, Accept/Close finish) has no automated test.

**Verification:** `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (after patches; all suites green, Kover gate passed). Targeted: `ScanReviewScreenTest` 5/5, `GuidedScanContractTest` 3/3, `ScanReviewTest` 9/9, `PieceCheckTest` 3/3. Session-isolation grep -- no output.

**Residual risks:** Device session B not run (Lenovo/S24 FE review, 200% on the physical Lenovo). Process death during review restarts the scan. A NeedsCalibration scan opens an all-blank review until Story 10.4 adds the calibrate prompt.
