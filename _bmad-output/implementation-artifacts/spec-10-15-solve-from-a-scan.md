---
title: 'Story 10.15: Solve from a scan'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: '4cc23b487820a0bdc37af1916735f1ce86a55eb4'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 10.5 wired Solve → *Scan my cube* → review → Manual Cube Editor, but no test proves the scan reaches Solution Playback without leaving the flow, *Enter colors instead* is missing from the scan viewfinder, calibration and review, and at `CAMERA_SCAN` BETA the Solve tab shows no BETA label or camera tooltip and keeps *Scan my cube* as the primary action. The BETA label exists only as inline copies (Test me segment, Assistance line), so there is no shared component to reuse.

**Approach:** Extract the Test me BETA label and note into one shared component and reuse it for *Scan my cube* at BETA (with *Enter colors* primary and the approved camera tooltip); show *Enter colors instead* on every screen of a Solve-started scan; add a Robolectric route test that drives the real `MainActivity` from the Solve tab through scan → review → Editor → solve → playback.

## Boundaries & Constraints

**Always:**
- Keep Story 10.5 and 10.7 behavior and tests intact: Accepted → Editor prefilled with the scan (nothing solves until the Editor's accept); `isAcceptedScan()` → `SolvePlaybackStarted(fromAcceptedScan = true)` baseline (unchanged or corrected); physical-playback re-solve from the observed cube; FellBack/Denied/Cancelled matrix of 10.5.
- One shared BETA label composable and one shared BETA note composable; the Test me segment, its note, the Assistance line label and the Solve scan button all call them. Existing test tags (`mode:TEST_ME:beta`, `lesson:testMe:betaNote`, `lesson:assistance:beta`) and their rendered text stay as today.
- New Learner text only in `core/domain/src/main/resources/content/strings/en.json`: `"msg.feature.camera.beta-note": "May misread colors in poor light. Manual entry always works."` (reuse `msg.feature.beta`, `btn.scan.enter-colors-instead`).
- *Enter colors instead* returns `ScanOutcome.FellBackToEditor` (existing `GuidedScanActions.onEnterColors`), so the Editor opens with the 10.5 fell-back note.
- Camera code stays in `app/src/camera/`; release flag levels unchanged; no new runtime dependency.

**Never:**
- No change to Quick Check (lessons or playback) screens: *Enter colors instead* shows only when the Activity runs a Solve scan (intent has neither `QuickCheckContract.EXTRA_FACES` nor `GuidedScanActivity.CALIBRATION_MODE`).
- No skipping the Editor after an accepted scan; no `SessionEvent` from the scan; no change to `Reduce`, `Assess`, the assembler or the calibration offer rule.
- No debug-only launcher; no change to `FeatureFlagLevels`.
- Never weaken, skip or delete an existing test.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| GA Solve Home | `CAMERA_SCAN` GA, `onScan` set | as today: `solve:scan` (filled) then `solve:enterColors`; no BETA label, no note | — |
| BETA Solve Home | `CAMERA_SCAN` BETA, `onScan` set | `solve:enterColors` first and filled; `solve:scan` outlined, contains the shared label (`solve:scan:beta`, "BETA"), TalkBack reads "Scan my cube, BETA"; note `solve:scan:betaNote` = camera tooltip copy, visible under the actions | — |
| OFF | `CAMERA_SCAN` OFF | only `solve:enterColors`; no label, no note | — |
| Viewfinder / calibration (Solve scan) | face or swatch phase | `scanEnterColors` button visible; tap → `FellBackToEditor` | — |
| Review (Solve scan) | review shown | `review:enterColors` visible; tap with no change → `FellBackToEditor`; with changed stickers → the existing discard confirm, then `FellBackToEditor` | cancel keeps the review |
| Quick Check / Settings calibration | intent has `EXTRA_FACES` or `CALIBRATION_MODE` | no `scanEnterColors`, no `review:enterColors` | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/TeachingModeSwitcher.kt` -- `Segment` (L~150-175) draws the beta label inline (bordered `labelMedium`, `Radii.xs`, tag `mode:${mode}:beta`); `ModeBetaBadge`. Replace the inline Text with the shared label.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt` -- `TestMeBetaNote` (L~573, `bodyMedium`, `inkSecondary`, polite live region, tag `lesson:testMe:betaNote`); `TEST_ME_BETA_NOTE`; caller L~218-232.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/AssistanceLine.kt` -- inline beta label L~71-81 (tag `lesson:assistance:beta`, `bodySmall`); `BETA_LABEL = TextKey("msg.feature.beta")` L103.
- New shared file (e.g. `app/src/main/kotlin/dev/cubentia/app/ui/beta/BetaLabel.kt`) -- `BetaLabel(text, color, style, modifier)` and `BetaNote(text, modifier)`; callers keep their tags/style via parameters.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- Home row (L~47-57): `onScan` button then `solve:enterColors`; add `scanBeta: Boolean = false`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- Solve tab L~690-707 builds `SolveScreen` (`onScan = onScan.takeIf { flags.isEnabled(CAMERA_SCAN) }`; `onSolved` dispatches `SolvePlaybackStarted(..., solveFlow.isAcceptedScan())`); pass `scanBeta = flags.level(FeatureFlag.CAMERA_SCAN) == FlagLevel.BETA`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanScreen(phase, strings, actions, frozen, viewfinder)` L~183; `Viewfinder` L~294 (compact portrait: nothing scrolls; `MiniNet` takes weight), `CaptureButtons` L~558, `CalibrationViewfinder` L~607; existing *Enter colors instead* on Rationale/Denied/Unavailable cards L~220-252.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `onCreate` reads `CALIBRATION_MODE` (L114) and `QuickCheckContract.EXTRA_FACES` (L118); `actions.onEnterColors = finishWith(FellBackToEditor)` (L146); `ScanReviewScreen(` call L~218; `GuidedScanScreen(` call L~262.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- params L77-89; Accept/Close buttons L~182-197; discard confirm dialog L~221-238 (reuse for a changed review).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt`, `MainActivity.kt` -- read-only here (10.5/10.7 behavior).
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` -- `SolverSource` binding; a route test can `@UninstallModules(CameraModule::class, SolverModule::class)` and provide a fake `SolverSource` (`SolverLoad.Ready(CubeSolver { SolveResult.Solved(moves) })`, as in `AppShellSolveTest` L~111).
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- Hilt + Robolectric route pattern: `FakeFrames`, `labs`, `CameraTestModule`, `frames(...)`, `receiveResult` bridge, `vm.endFreeze`. Shared fakes may move to a sibling test file without changing this test's assertions.
- Tags to assert: `solve:scan`, `solve:enterColors`, `editor:accept` (ManualCubeEditor L173), `solvePlayback:screen`, `solutionPlayback`.
- Tests to extend: `app/src/test/.../ui/shell/AppShellSolveTest.kt` (`show(scan, cameraScan)` L132), `app/src/test/.../ui/lesson/*` beta tests, `app/src/testDebug/.../camera/GuidedScanScreenTest.kt`, `app/src/test/.../ui/scan/*ScanReview*Test.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.feature.camera.beta-note` (copy above).
- `app/src/main/.../ui/beta/BetaLabel.kt` (new) -- shared `BetaLabel` and `BetaNote`; move the Segment's label look and `TestMeBetaNote`'s look into them.
- `TeachingModeSwitcher.kt`, `LessonPanel.kt`, `AssistanceLine.kt` -- call the shared composables; same tags, text and styling.
- `SolveScreen.kt` -- `scanBeta`: at BETA render `solve:enterColors` as the first filled `Button` and `solve:scan` as an `OutlinedButton` containing the text and `BetaLabel` (tag `solve:scan:beta`, merged content description "Scan my cube, BETA"), then `BetaNote` with the camera note (tag `solve:scan:betaNote`); GA unchanged.
- `AppShell.kt` -- pass `scanBeta`.
- `GuidedScanScreen.kt` -- new param `offerEnterColors: Boolean = false`; when true, `Viewfinder` and `CalibrationViewfinder` show an `OutlinedButton` tagged `scanEnterColors` (copy `btn.scan.enter-colors-instead`, ≥ 48 dp) calling `actions.onEnterColors`; compact portrait still fits without scrolling.
- `ScanReviewScreen.kt` -- new param `onEnterColors: (() -> Unit)? = null`; when set, an `OutlinedButton` tagged `review:enterColors` under *Close*; changed review → the existing discard confirm, whose confirm then calls `onEnterColors`.
- `GuidedScanActivity.kt` -- `solveScan = !calibrationMode && !intent.hasExtra(QuickCheckContract.EXTRA_FACES)`; pass `offerEnterColors = solveScan` and `onEnterColors = { finishWith(FellBackToEditor) }` only when `solveScan`.
- `app/src/testDebug/kotlin/dev/cubentia/app/SolveFromScanRouteTest.kt` (new) -- Hilt Robolectric, real `MainActivity` + `GuidedScanActivity`, fake frames and fake `SolverSource`: Solve tab → `solve:scan` → six clean faces → review (assert `review:enterColors` exists) → `review:accept` → result to `MainActivity` → Editor shows the scanned stickers → `editor:accept` → `solvePlayback:screen` shown and `sessionHost.state` has `kind == SessionKind.SOLVE_PLAYBACK` and `observed` Full of the scanned cube; second test: viewfinder shows `scanEnterColors`, tapping it returns `FellBackToEditor` → Editor with `solve:scanNote` fell-back copy.
- Unit tests -- `AppShellSolveTest`: BETA order/label/note/content description, GA and OFF rows; `GuidedScanScreenTest`: `scanEnterColors` shown with the flag, absent without it, fits compact portrait; review test: `review:enterColors` direct and via discard confirm; existing Test me / Assistance beta tests still pass unchanged.

**Acceptance Criteria:**
- Given the Solve tab with `CAMERA_SCAN` above OFF, when *Scan my cube* is chosen and the scan accepted and confirmed in the Editor, then `SolveFromScanRouteTest` reaches Solution Playback from the real `MainActivity` without leaving the Solve flow, and *Enter colors instead* was visible on the viewfinder and review.
- Given `CAMERA_SCAN` BETA, when the Solve tab renders, then *Enter colors* is the primary action and *Scan my cube* carries the shared BETA label and the note "May misread colors in poor light. Manual entry always works."; the Test me segment and Assistance line render the same shared label.
- Given a Quick Check or a Settings calibration, when its screens render, then no *Enter colors instead* is added.
- Human gate (not automatable here): device acceptance runs on the main debug build from the Solve tab (no debug launcher exists).

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 19 findings — high 0, medium 1, low 9, false 6, maybe-false 3
- findings:
  - `[low]` `[patch]` (blind) Calibration's *Enter colors instead* can finish the Activity while a calibration save is in flight (Close already waits) — `onEnterColors` now does nothing while `scan.saving`, mirroring Close.
  - `[maybe-false]` `[reject]` (blind) In a short window or large font the viewfinder's three stacked buttons could squeeze *Enter colors instead* — `MiniNet` takes the remaining weight and shrinks first; compact portrait and medium tablet tests show the button ≥ 48 dp in bounds; would only be low; a reserved-space layout adds complexity for an unmet case.
  - `[low]` `[reject]` (blind) Review *Enter colors instead* sits below *Close* inside the scrolling review — same scroll column and placement as *Accept* and *Close*; consistent, reachable; pinning it adds layout state.
  - `[medium]` `[patch]` (blind) Route test only checks the review fallback is visible, not that it returns to the Editor — same root as the verification-gap review-fallback row; added `SolveFromScanRouteTest.manualEntryFromAnEditedReviewAsksThenReturnsToTheEditorWithTheFallbackNote` (edit a sticker, *Enter colors instead*, confirm discard, assert `FellBackToEditor` and the Editor note).
  - `[low]` `[patch]` (blind) Calibration fallback reached from a Solve review is only component-tested — same root as the verification-gap calibration row; added `calibrationFromASolveReviewOffersManualEntryBackToTheEditor`.
  - `[low]` `[reject]` (blind) Full Quick Check triggers (six faces, no calibration, `fullScan`) are not tested one by one — one `if (a || b || c)` branch puts the same extra; the test covers that branch.
  - `[low]` `[reject]` (blind) BETA test does not check filled vs outlined or a wrapped row — cosmetic; `FlowRow` keeps *Enter colors* first; order, label, content description and note are asserted.
  - `[false]` `[reject]` (blind) Route test does not cover a corrected scan's baseline — unchanged 10.7 behavior (`isAcceptedScan` true after Editor corrections), already pinned by `SolveFlowViewModelTest`; this diff does not touch it.
  - `[low]` `[patch]` (edge) Enter colors during calibration save can cancel the write — same root as the first blind row; same fix.
  - `[low]` `[reject]` (edge) Calibration opened from an edited review leaves without the discard confirm — the Learner explicitly chose manual entry; a second confirm dialog in calibration adds UI state for a rare path.
  - `[maybe-false]` `[reject]` (edge) Fixed 45% preview leaves the last button whatever height remains — carried in this pass from the blind squeeze row; same evidence.
  - `[medium]` `[patch]` (verification-gap) Review fallback route unverified — same root as the blind review-fallback row; same new test.
  - `[low]` `[patch]` (verification-gap) Wide viewfinder's *Enter colors instead* untested — added a Solve-offer case to `GuidedScanMediumScreenTest` (in bounds, beside the preview, ≥ 48 dp, click reaches the callback).
  - `[low]` `[patch]` (verification-gap) Calibration from Solve review can lose manual entry undetected — same root as the blind calibration row; same new test.
  - `[false]` `[reject]` (intent) Validation lives in the Editor, not at the review's *Accept* — the verbatim intent tells the build to keep 10.5's scan → review → Editor behavior and its tests.
  - `[false]` `[reject]` (intent) The BETA presentation is reachable in no build (debug GA, release OFF) — the AC is conditioned on `CAMERA_SCAN` BETA; the debug map follows wiring (10.5, AGENTS.md) and 10.11 raises release levels; the JVM test injects BETA.
  - `[maybe-false]` `[reject]` (intent) The note is always visible instead of revealed on tap like Test me — Design Notes: the tap opens the camera, so a tap-revealed note would never be read; same shared component and copy; PO can revisit at device session.
  - `[false]` `[reject]` (intent) "Always visible" is narrower than every surface — the Editor is manual entry itself; Quick Check and Settings calibration are not the Solve flow the AC names.
  - `[false]` `[reject]` (intent) AC3 is checked only in Robolectric — AC3 is a human device gate; no debug launcher exists; recorded under residual risks.

## Design Notes

**Editor stays in the flow:** the PO keeps 10.5's Accepted → Editor step; the Editor's accept is the "validate" step, so the route is scan → review → Editor (validate) → solve → playback, all inside the Solve tab.

**Note always visible at BETA:** tapping *Scan my cube* opens the camera at once, so a tap-revealed note (the Test me pattern) would never be read; the shared `BetaNote` shows under the actions whenever BETA. Same component, same copy rules.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (JVM tests incl. `ScanRouteTest`, `SolveFromScanRouteTest`, lint, Kover, boundary/manifest/denylist checks, debug and release APKs)

## Auto Run Result

**Summary:** One shared BETA component (`ui/beta/BetaLabel.kt`: `BetaLabel`, `BetaNote`) now backs the Test me segment, its note, the Assistance line and the Solve scan action. At `CAMERA_SCAN` BETA the Solve tab shows *Enter colors* first and filled, *Scan my cube* outlined with the BETA label (TalkBack "Scan my cube, BETA"), and the camera note "May misread colors in poor light. Manual entry always works."; GA and OFF are unchanged. A Solve-started scan shows *Enter colors instead* on the viewfinder (compact and wide), calibration and review (with the discard confirm after edits), returning `FellBackToEditor`; Quick Checks (partial and full, now marked by `QuickCheckContract.EXTRA_FULL_QUICK_CHECK`) and Settings calibration do not. `SolveFromScanRouteTest` drives the real `MainActivity`: Solve → scan → review → Editor (prefilled, nothing solved yet) → *Accept* → Solution Playback with `observed` Full of the scanned cube. 10.5/10.7 behavior is unchanged.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/beta/BetaLabel.kt` (new) -- shared BETA label and note.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/{TeachingModeSwitcher,LessonPanel,AssistanceLine}.kt` -- use the shared component; same tags and text.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt`, `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `scanBeta` layout.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- optional `review:enterColors` with the discard confirm.
- `app/src/main/kotlin/dev/cubentia/app/scan/QuickCheckCapture.kt` -- full Quick Check intent marker.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{GuidedScanActivity,GuidedScanScreen}.kt` -- `solveScan` gate; `scanEnterColors` on viewfinder and calibration; no exit while saving.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.feature.camera.beta-note`.
- Tests: `SolveFromScanRouteTest` (new, 7), `AppShellSolveTest`, `ScanReviewScreenTest`, `GuidedScanScreenTest`, `GuidedScanMediumScreenTest`, `ScanRouteTest` (`labs` made internal for reuse).

**Review findings:** 19 (high 0, medium 1, low 9, false 6, maybe-false 3). Patched: review-fallback route test (medium; blind + verification-gap), calibration-from-review route test (low; blind + verification-gap), wide viewfinder test (low), no exit during calibration save (low; blind + edge). Deferred: none. Rejected: button squeeze at large font (maybe-false, would be low; `MiniNet` absorbs), review fallback below Close (low, consistent placement), per-trigger full Quick Check tests (low, one branch), BETA filled/wrapped checks (low, cosmetic), corrected-scan baseline (false, unchanged 10.7 coverage), no confirm leaving calibration from an edited review (low, explicit choice, adds dialog state), validation in the Editor (false, PO keep-10.5 instruction), BETA unreachable in builds (false, AC conditioned on BETA), always-visible note (maybe-false, design note), narrower "always visible" (false, Editor is manual entry; Quick Check is not the Solve flow), AC3 JVM-only (false, human gate).

**Follow-up review recommendation:** false -- patched 0 high, 1 medium, 3 low; all but the one-line save guard are test additions.

**Verification:** Codex ran `./gradlew spotlessApply` and `./gradlew spotlessCheck build` (pass, 802 app tests). After review patches: `SolveFromScanRouteTest` (7) and `GuidedScanMediumScreenTest` (2) passed; `./gradlew spotlessApply` and `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** Human gate open: device acceptance on the main debug build from the Solve tab (device session B3; Lenovo TB128XU, Galaxy S24 FE). The debug build runs `CAMERA_SCAN` at GA, so the BETA layout is seen on a device only once a build sets BETA; the PO may still prefer the tap-revealed note pattern over the always-visible note.
