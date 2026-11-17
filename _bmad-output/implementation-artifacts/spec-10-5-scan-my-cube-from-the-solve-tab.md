---
title: 'Story 10.5: Scan my cube from the Solve tab'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_revision: '99c967e8270df805b83bd2812588cb7053c77490'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      CameraXFrameSource lifecycle (stop during provider acquisition, stop before layout, repeated preview swap) has no automated test.
    evidence: |-
      CameraX code moved out of GuidedScanActivity into CameraXFrameSource; ScanRouteTest replaces it with a fake. Settle on device session B3: scan, calibration and rescan on the Lenovo and the Galaxy S24 FE with no stuck preview or Unavailable card.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Nothing in the main app starts a Guided Scan: `MainActivity` never registers `ScanFlowContract`, so Epic 10 is reachable only from the debug-only `GuidedScanDebugActivity` launcher; the scan → calibration → rescan route has no Activity-level test (deferred in #158, #161, #164, #166); calibration swatches are manual-capture only; debug raises `QUICK_SCAN`/`PLACEMENT`/`CASE_RECOGNITION` with no wired entry (Welcome's dead *Start from my cube*); Stage 6 promises a camera check that does not exist.

**Approach:** Wire *Scan my cube* on the Solve tab through `ScanFlowContract` into the Manual Cube Editor, put CameraX behind an injectable frame source so one Robolectric test drives the real route with fake frames, keep review corrections across a calibration rescan, auto-capture calibration swatches with the face steady hold, fix the debug flag map and Stage 6 copy, and delete the debug-only scan launcher.

## Boundaries & Constraints

**Always:**
- Camera code stays in `app/src/camera/` behind the `ScanLauncher` port; `app/src/main` never imports CameraX or `dev.cubentia.app.camera`.
- *Scan my cube* shows only when `CAMERA_SCAN` is above OFF (the injected `FeatureFlags` is the `ScanPortGate`, which already folds in binding and camera presence) and a `ScanLauncher` is bound; otherwise the Solve tab is exactly as today — no dead or disabled scan button (UX-DR19).
- The scan result is handled only by the Solve flow (`SolveFlowViewModel`); nothing dispatches a `SessionEvent` from the scan. Scanned `Facelets` are only a starting point in the Editor; nothing solves until the Learner accepts in the Editor.
- All new Learner text is content strings in `core/domain/src/main/resources/content/strings/en.json` (exact copy below).
- Calibration auto-capture reuses the face steady hold by driving a `GuidedScanController` (`STEADY_MS`, `SETTLE_MS`, `STEADY_DELTA_E`, Remeter) — no copied thresholds. Logo/uneven and implausible swatches still get the plain-sticker re-prompt through the unchanged `CalibrationSession.offer`; *Keep* still confirms, *Capture* stays.
- Release stays camera-free: release/benchmark flag levels unchanged; no new runtime dependency (test-only dependencies allowed, versions in `gradle/libs.versions.toml`).

**Never:**
- No solve, playback, BETA label or tooltip work (Story 10.15); no camera check action and no camera copy at Stage 6 (Story 10.7); no wiring of Welcome *Start from my cube* (Story 11.2).
- No change to the calibration offer rule (`CalibrationOffer.offered`) — the PO decides it at device session B3.
- Do not delete the fixture recorder or `app/src/camera/debug/` recorder files; delete only the scan launcher pieces named below.
- No blank-sticker mode in the Manual Cube Editor.
- Never weaken, skip or delete an existing test except `GuidedScanDebugSummaryTest` (deleted by AC) and expectations this story intentionally changes (debug flag map, Stage 6 BETA copy).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Entry shown | `CAMERA_SCAN` GA, launcher bound | Solve Home shows `solve:scan` (*Scan my cube*) and `solve:enterColors` | — |
| Entry hidden | `CAMERA_SCAN` OFF, or no binding / no camera (gate reads OFF) | Only `solve:enterColors`; no `solve:scan` node | — |
| Accepted | `ScanOutcome.Accepted(state)` | Editor opens with `state.toFacelets()` as its initial stickers; no note; no solve starts | — |
| Fell back | `ScanOutcome.FellBackToEditor` | Editor opens with the *Enter colors* start colors and note `msg.solve.scan-fell-back` | — |
| Cancelled | `ScanOutcome.Cancelled` | Solve tab stays on Home, unchanged | — |
| Denied | permission not granted and a denial recorded earlier (`CameraDenialRecord`) | tap opens the Editor directly with note `msg.solve.scan-camera-denied`; no scan Activity starts | record read off the main thread |
| Corrections kept | review edited (sticker i painted c), then calibration *Save* → rescan | the new review has sticker i = c (other stickers from the rescan) | — |
| Swatch auto-capture | calibration phase, swatch pending none, cell aligned and steady ≥ `STEADY_MS` | ring is read as by *Capture*; plain plausible → swatch + *Keep*; logo/implausible → plain re-prompt | after a re-prompt or *Try again* a new hold can capture again |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- injects `FeatureFlags`, `Optional<ScanLauncher>`; Settings calibration already wired (L72-83). Register the scan contract here.
- `app/src/main/kotlin/dev/cubentia/app/scan/{ScanFlowContract,ScanLauncher,ScanOutcome,ScanPortGate}.kt` -- contract encode/decode (validates via `CubeValidator`); port with `hasCamera`, `guidedScanIntent`, `calibrationIntent` (default methods pattern).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/{SolveScreen,SolveFlowViewModel}.kt` -- `SolvePhase.Home/Editing(initial, error)/Solving`; `enterColors()` uses `accepted` (last accepted, default SOLVED); Home renders `solve:enterColors` then the free cube; Editing renders `solve:error` line then `ManualCubeEditor(initial=…)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `AppShell(…, calibration)` → `ShellContent`; Solve tab at ~L641 builds `SolveScreen`; `cameraScan = flags.level(CAMERA_SCAN)` passed to `LessonScreen` at L319/388/454.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `cameraScan` param (L160), `camera = cameraScan != OFF` (L357), first-solve prompt key choice (L910-923).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- CameraX: `startCamera` (L324), `bindWhenLaidOut` (L361), `analyze` (L417: `FrameReader.locate` → stats, calibration branch with manual `captureCalibration` + `FrameReader.centerRing`, else controller), `meterOnGrid` (L501), PreviewView `AndroidView` factory with rebind (L263-287), review unbind effect (L216-224). `GuidedScanViewModel.saveCalibration` (L559: on Saved from review → new controller, `phase = Viewfinder()`; edits lost). `CameraDenialRecord` (L631).
- `app/src/camera/kotlin/dev/cubentia/app/camera/{GuidedScanFlow,GuidedScanScreen}.kt` -- pure decisions (`reviewOf`, `calibrationCapture`, `phaseOf`); `GuidedScanPhase.Review(review, offerCalibration, edited)`, `Calibration(session, coaching, saveFailed, fromReview)`; test tags `scanCapture`, `scanClose`, `scanPrompt`, `scanViewfinder`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{CameraScanLauncher,CameraModule}.kt` -- the port binding.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- tags `review:accept`, `review:calibrate:offer`, `review:calibrate:confirm`, `review:close`.
- `core/domain/.../recognition/GuidedScan.kt` -- `GuidedScanController(plan, reprompt)`, `onFrame` steady hold (L391-440), `ScanStep(centerColor)`, `GuidedScanEffect.{Capture,Remeter,Done}`; `ColorCalibration.kt` -- `CalibrationSession.current/pending/offer/keep/retake`, `CalibrationOffer.offered`; `ScanReview.kt` -- immutable `stickers`, `select`, `paint`, `fromSamples(samples, saved)`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt` + `build-logic/src/test/.../policy/FeatureFlagLevelsTest.kt` -- debug map is `flags.associateWith { "GA" }`.
- Delete: `app/src/camera/debug/kotlin/dev/cubentia/app/camera/scan/GuidedScanDebugActivity.kt`, its `<activity>` in `app/src/camera/debug/AndroidManifest.xml` (keep the recorder activity), `app/src/testDebug/kotlin/dev/cubentia/app/camera/scan/GuidedScanDebugSummaryTest.kt`.
- Tests to mirror/update: `app/src/test/.../ui/shell/{AppShellSolveTest,AppShellCameraScanTest}.kt`, `app/src/test/.../flags/BuildConfigFeatureFlagsTest.kt` (expects every debug flag GA), `app/src/test/.../ui/solve/SolveFlowViewModelTest.kt`, `app/src/testDebug/.../camera/{GuidedScanFlowTest,CalibrationViewModelTest,CameraScanLauncherTest}.kt`; `core/domain/src/test/.../recognition/GuidedScanTest.kt` (`stats(...)`, `faceOf(face)` synthetic `FrameStats` helpers to copy for fake frames).

## Tasks & Acceptance

**Execution:**
- `build-logic/.../policy/FeatureFlagLevels.kt` -- debug: `CAMERA_SCAN`, `TEST_ME`, `ADAPTIVE_ASSISTANCE` GA; `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` OFF; KDoc states "debug levels follow wiring (AD-18)" -- AC6.
- `build-logic/.../policy/FeatureFlagLevelsTest.kt` -- pin `QUICK_SCAN=OFF,PLACEMENT=OFF,CASE_RECOGNITION=OFF,TEST_ME=GA,CAMERA_SCAN=GA,ADAPTIVE_ASSISTANCE=GA` for debug.
- `app/src/test/.../flags/BuildConfigFeatureFlagsTest.kt` -- debug expectation per flag from the new map.
- `core/domain/src/main/resources/content/strings/en.json` -- add `"btn.solve.scan": "Scan my cube"`, `"msg.solve.scan-fell-back": "No scan this time. Enter your cube's colors here."`, `"msg.solve.scan-camera-denied": "Camera access is off, so enter your cube's colors here."`.
- `app/src/main/.../scan/ScanLauncher.kt` -- add `fun cameraDenied(context: Context): Boolean = false` (blocking disk read; call off main). `app/src/camera/.../CameraScanLauncher.kt` -- true when CAMERA is not granted and `CameraDenialRecord` holds a recorded denial (add a read-only `CameraDenialRecord.denied(context)`).
- `app/src/main/.../ui/solve/SolveFlowViewModel.kt` -- `SolvePhase.Editing` gains `note: Text? = null`; add `onScanOutcome(outcome)` (Accepted → `Editing(state.toFacelets())`, not touching `accepted` until the Learner accepts; FellBackToEditor → `Editing(accepted, note = msg.solve.scan-fell-back)`; Cancelled → no change) and `scanDenied()` (→ `Editing(accepted, note = msg.solve.scan-camera-denied)`).
- `app/src/main/.../ui/solve/SolveScreen.kt` -- new `onScan: (() -> Unit)? = null`; Home shows a `solve:scan` button next to `solve:enterColors` only when non-null; Editing shows `current.note` as one line tagged `solve:scanNote` above the editor.
- `app/src/main/.../ui/shell/AppShell.kt` -- thread `onScan: (() -> Unit)?` (default null) through `AppShell`/`ShellContent` to `SolveScreen`, passed only when `flags.isEnabled(CAMERA_SCAN)`; stop deriving the Stage 6 camera copy from `CAMERA_SCAN` (pass no camera check).
- `app/src/main/.../ui/lesson/LessonScreen.kt` -- the first-solve prompt uses `msg.physical.first-solve-prompt-camera` only when a camera check action is shown; none exists yet, so it always uses the camera-free key (replace the `camera = cameraScan != OFF` derivation with a `cameraCheck: Boolean = false` input). Update `AppShellCameraScanTest`: BETA and OFF both show the camera-free prompt -- AC7.
- `app/src/main/.../MainActivity.kt` -- in `onCreate` (after `super`, before `setContent`), when `CAMERA_SCAN` is enabled and the launcher is present, `registerForActivityResult(ScanFlowContract(launcher)) { solveFlow.onScanOutcome(it) }`; `onScan` = read `cameraDenied` on `Dispatchers.IO` in `lifecycleScope`, then `solveFlow.scanDenied()` or `launch(Unit)`. Otherwise `onScan = null`.
- `app/src/camera/.../ScanFrameSource.kt` (new) + `GuidedScanActivity.kt` + `CameraModule.kt` -- move CameraX (provider, preview view creation, binding with viewport, analyzer, `FrameReader.locate`, `centerRing`, metering, camera-state → unavailable) behind an internal injectable `ScanFrameSource` (Hilt-bound to a `CameraXFrameSource`). It creates the preview view, starts/stops delivering `ScanFrame(stats, rotationDegrees, ring: () -> CellReading?)` on the analyzer executor, meters on request and reports unavailability. The Activity keeps all decisions (controller, calibration, freeze, review) and calls start/stop exactly where it binds/unbinds today, including the rebind after a new preview view.
- `GuidedScanActivity.kt` / `GuidedScanFlow.kt` -- calibration auto-capture: a per-swatch `GuidedScanController(plan = listOf(ScanStep(current)), reprompt = { emptyList() })` fed every calibration frame while `session.current != null && session.pending == null`; a `Capture` effect reads `ring()` and applies `GuidedScanFlow.calibrationCapture` exactly as the manual path; `Remeter` meters; a new hold starts whenever the session object changes. Put the hold bookkeeping in a pure, unit-tested helper.
- `GuidedScanActivity.kt` / `GuidedScanScreen.kt` / `GuidedScanFlow.kt` -- corrections across rescan: `GuidedScanPhase.Review` records the Learner's painted stickers (index → color, from `onReview`); `saveCalibration` from a review keeps them, and the next published review applies them (pure `GuidedScanFlow.withCorrections(review, corrections)`, selecting the first remaining blank) and stays `edited`.
- `app/src/camera/debug/...` + `app/src/testDebug/.../scan/GuidedScanDebugSummaryTest.kt` -- delete the debug scan launcher, its manifest entry and test -- AC8.
- `gradle/libs.versions.toml` + `app/build.gradle.kts` -- add test-only `com.google.dagger:hilt-android-testing` (version.ref `hilt`) as `testImplementation` and `kspTest` of `hilt-compiler`, if the route test uses Hilt testing.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` (new) -- Robolectric, real `MainActivity` and `GuidedScanActivity` (e.g. `@HiltAndroidTest` + `HiltTestApplication`, `@UninstallModules(CameraModule)` with a fake `ScanFrameSource` and a `ScanLauncher` whose `hasCamera` is true; CAMERA granted via the shadow application). Route: Solve tab `solve:scan` → the started intent is `GuidedScanActivity`, launched for result → fake frames for six faces whose read leaves at least one blank and triggers the calibration offer with nothing saved (e.g. a logo/non-plain white center) → review: paint a blank → `review:calibrate:offer` → confirm → six steady swatch frames auto-capture, *Keep* each → *Save* → assert `CalibrationRepository` holds the swatches, the frame source was started again (preview rebound), and frames reach the new controller → rescan six faces → review shows the earlier painted sticker kept and no calibration offer → `review:accept` → feed the Activity result back to `MainActivity` → Editor shows the scanned stickers.
- Unit tests -- `SolveFlowViewModelTest` (three outcomes + denied), `AppShellSolveTest` or a Solve screen test (button shown/hidden by `onScan`; note line), `GuidedScanFlowTest` (`withCorrections`; hold helper: steady capture, reset on session change, no capture while pending), `CameraScanLauncherTest` (`cameraDenied`).

**Acceptance Criteria:**
- Given `MainActivity` with `CAMERA_SCAN` above OFF and a `ScanLauncher` bound, when the app starts, then `ScanFlowContract` is registered and the Solve tab shows *Scan my cube* next to *Enter colors*; with `CAMERA_SCAN` OFF, no binding or no camera, *Enter colors* is the only action and no scan button exists.
- Given *Scan my cube*, when the scan returns `Accepted`, `FellBackToEditor` or `Cancelled`, then the matrix rows hold, and only `SolveFlowViewModel` handles the result.
- Given a recorded camera denial, when the Learner taps *Scan my cube*, then the Editor opens directly with the one-line note.
- Given `ScanRouteTest`, when `./gradlew :app:testDebugUnitTest` runs, then it passes the full route above with a fake frame source, including the kept review edit.
- Given Color Calibration (from review or Settings), when a swatch's cell is aligned and steady, then it is captured automatically; *Keep* confirms, *Capture* still works, and logo/implausible swatches get the plain re-prompt.
- Given the debug build, when `FeatureFlagLevels.byBuildType["debug"]` is built, then only `CAMERA_SCAN`, `TEST_ME`, `ADAPTIVE_ASSISTANCE` are GA and `FeatureFlagLevelsTest` pins it; Welcome no longer shows *Start from my cube* in debug.
- Given Stage 6's first-solve prompt in physical mode with `CAMERA_SCAN` BETA or GA, when it renders, then it shows the camera-free copy.
- Given the PR, then `GuidedScanDebugActivity`, its launcher entry and `GuidedScanDebugSummaryTest` are gone and the fixture recorder remains.
- Human gate (not automatable here): device session B3 on the main debug build (Lenovo TB128XU, Galaxy S24 FE; stka, stla) — full scan from the Solve tab reaches the Editor and the #166 checks run; the PO records the calibration offer rule in the PRD memlog.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 19 findings — high 0, medium 5, low 8, false 5, maybe-false 1
- findings:
  - `[false]` `[reject]` (blind) `MainActivity` never checks `hasCamera`, so a camera-less device shows a dead scan action — the injected `FeatureFlags` is `ScanPortGate` (`FlagsModule`), which reads `CAMERA_SCAN` as OFF unless `launcher.hasCamera(context)`; `onScan` is null there.
  - `[false]` `[reject]` (blind) FellBackToEditor restores `accepted` instead of an "empty" editor — the editor has no blank state; `accepted` is exactly what *Enter colors* opens (the Learner's own last entry, default SOLVED), which is the non-scan start the spec's Design Notes define as "empty".
  - `[medium]` `[patch]` (blind) Repeated *Scan my cube* taps launch several scan activities — `MainActivity` now holds `scanInFlight` from the tap until the denial check opens the Editor or the scan result returns.
  - `[false]` `[reject]` (blind) Unavailable does not stop the frame source — Unavailable is entered only when binding fails or the bound camera closes with an error, so no frames are delivered; unchanged from before the refactor.
  - `[low]` `[reject]` (blind) Camera-state observers accumulate across rebinds — they observe the same back camera's state and only act on a real error in a camera phase; a generation guard adds state for an outcome not shown to occur.
  - `[low]` `[reject]` (blind) Calibration auto-capture shows no live coaching — same as before this story (coaching appears after a *Capture*); not asked by the AC; plumbing the hold's coaching adds state.
  - `[false]` `[reject]` (blind) Auto-capture uses the full face gate, so a plain sticker with odd surroundings may not auto-capture — the AC prescribes "the same steady hold as the face capture (`GuidedScanController.onFrame`)", and *Capture* stays available.
  - `[medium]` `[patch]` (blind) Scan notes are clipped by `maxLines = 1` at large font — removed `maxLines`; the note wraps (padded like the pause note).
  - `[low]` `[reject]` (blind) Route test covers only Accept, not fallback/cancel at Activity level — the outcome mapping is unit-tested in `SolveFlowViewModelTest` and the result callback is the same pass-through the Accept route exercises; the denial part is patched below.
  - `[maybe-false]` `[defer]` (blind) `CameraXFrameSource` lifecycle (stop during provider acquisition, stop before layout, repeated preview swap) has no direct test — moved CameraX code, not runnable under Robolectric; settle on device session B3 (scan, calibration, rescan on both devices); medium if true.
  - `[medium]` `[patch]` (edge) Rapid repeated taps launch multiple scan activities — same root as the blind repeated-tap row; same fix.
  - `[medium]` `[patch]` (verification-gap) MainActivity's denial shortcut unverified — added `ScanRouteTest.recordedDenialOpensTheEditorWithTheNoteAndStartsNoScan` (launcher reports a recorded denial; asserts Editor, the denial note and no started activity).
  - `[low]` `[reject]` (intent) Hidden-entry cases tested through `AppShell` only — the gate lives in `ScanPortGate`/`FlagsModule` (existing tests) and `MainActivity` passes null when it reads OFF; one-line glue.
  - `[low]` `[reject]` (intent) FellBack/Cancelled only VM-tested — same as the blind route-coverage row.
  - `[medium]` `[patch]` (intent) Denial path at `MainActivity` untested — same root as the verification-gap row; same fix.
  - `[low]` `[reject]` (intent) Settings-entry auto-capture untested — the calibrating branch of `analyze` does not depend on `fromReview`; the hold is unit-tested and the review-entry route exercises it.
  - `[low]` `[reject]` (intent) No Welcome test for the removed dead button — `WelcomeScreen` already gates *Start from my cube* on `PLACEMENT` (existing test); the debug map is pinned by `FeatureFlagLevelsTest`.
  - `[low]` `[patch]` (intent) `PhysicalLessonScreenTest` kept "showsCameraPrompt" names and a dead `cameraScan` arg while asserting camera-free copy — tests now take `cameraCheck`: without it the camera-free prompt, with it the camera prompt.
  - `[false]` `[reject]` (intent) Learner edits override a better rescan reading — intended: the AC keeps review edits, and the Design Notes treat Learner corrections as evidence.

### 2026-11-17 — Review pass (follow-up)
- verdicts: 17 findings — high 0, medium 2, low 7, false 7, maybe-false 1
- findings:
  - `[false]` `[reject]` (blind) A first-ever permission denial leaves the scan on its denied card instead of returning to the Editor — the intent's Denied row requires "a denial recorded earlier"; Design Notes keep Story 10.2's denied card for the first denial, whose *Enter colors instead* returns `FellBackToEditor`.
  - `[false]` `[reject]` (blind) A recorded denial blocks permission retries from Solve — the Denied row prescribes opening the Editor directly; recovery exists: granting in system Settings makes `cameraDenied` false (it requires CAMERA not granted) and the scan clears the record on grant.
  - `[false]` `[reject]` (blind) FellBackToEditor reuses the previously accepted cube — carried: same claim as the 2026-11-17 first-pass row; `SolveFlowViewModel.onScanOutcome` unchanged; the Editor has no blank state and the matrix names the *Enter colors* start colors.
  - `[low]` `[reject]` (blind) A pending denial check can launch the scan after the Learner taps *Enter colors* — the window is one SharedPreferences read on `Dispatchers.IO`; cancelling it on navigation adds state and branches for a race not met in everyday use.
  - `[false]` `[reject]` (edge) A stale layout callback in `CameraXFrameSource.bindWhenLaidOut` can bind a cleared analyzer after stop/start — a pending listener only exists while the viewport is null; the layout that makes it non-null fires it first (inactive → removed; active → replaced by the newer bind, whose listener is registered later or whose immediate bind runs after), so the final binding is the newest analysis; same shape as the pre-refactor code.
  - `[false]` `[reject]` (edge) `MainActivity` never checks `hasCamera` — carried: same claim as the first-pass row; the injected `FeatureFlags` is `ScanPortGate`, which reads `CAMERA_SCAN` OFF without a camera.
  - `[medium]` `[patch]` (verification-gap) The `scanInFlight` guard and its reset after a result are untested — added `ScanRouteTest.repeatedTapsStartOneScanAndACancelledScanCanBeStartedAgain` (two taps start one scan, a tap while open starts nothing, a cancelled result leaves Solve on Home and a new scan starts); mutant with the guard disabled fails it.
  - `[false]` `[reject]` (intent) Auto-capture uses the controller's face-level gate (R1b) — carried: same claim as the first-pass "full face gate" row; the intent names driving a `GuidedScanController`.
  - `[medium]` `[patch]` (intent) The route test's kept edit does not discriminate: the calibrated rescan could read the painted sticker right without the correction — the rescan now misreads that facelet as another clear color (non-center blank chosen), so only the carried correction yields the accepted cube; asserts `review.corrections`; mutant without `withCorrections` at publish fails it.
  - `[low]` `[reject]` (intent) Entry shown/hidden checked through `AppShell`, not `MainActivity` — carried: same claim as the first-pass hidden-entry row.
  - `[low]` `[reject]` (intent) Accepted result bridged with `receiveResult`; no check that no `SessionEvent` is dispatched — Robolectric does not run a real cross-Activity result; `SolveFlowViewModel` has no session dependency to dispatch through.
  - `[low]` `[reject]` (intent) FellBack/Cancelled only VM-tested — carried: first-pass route-coverage row (Cancelled now also passes through the new route test).
  - `[low]` `[reject]` (intent) Denied route uses the fake launcher's flag, not the real record — the real `CameraScanLauncher.cameraDenied` is unit-tested; the route test covers the Activity branch.
  - `[low]` `[reject]` (intent) Route test reaches into `GuidedScanViewModel` (`endFreeze`, `saving`, `phase`) — the capture freeze is timed; Learner actions still go through UI tags; no behaviour left unexercised.
  - `[maybe-false]` `[defer]` (intent) `CameraXFrameSource` and the phase-driven stop/start are exercised only by a fake — carried: already in `deferred` (device session B3).
  - `[false]` `[reject]` (intent) Rewriting the GA Stage 6 expectation exceeds the "BETA copy" exception — AC7 requires camera-free copy for BETA and GA, and the Never rule forbids camera copy at Stage 6 at any level.
  - `[low]` `[reject]` (intent) `BuildConfigFeatureFlagsTest` hard-codes the wired set instead of reading the build-logic map — the app test cannot load build-logic sources; `FeatureFlagLevelsTest` pins the map and a mismatch fails the BuildConfig test.

## Design Notes

**"Editor empty":** the Manual Cube Editor has no blank stickers; "empty" means not prefilled from a scan — the same start colors *Enter colors* uses.

**Denied detection:** Android cannot tell "denied" from "never asked"; a denial recorded by the scan (`CameraDenialRecord`, cleared on grant) plus no grant is the signal. A first-ever denial inside the scan keeps Story 10.2's denied card (whose *Enter colors instead* yields `FellBackToEditor`); later taps open the Editor directly.

**Kept edits:** the AC's route passes through *Save* → full rescan, so "edits kept" means the Learner's painted stickers are re-applied by facelet index to the rescanned review (facelet positions are canonical URFDLB, independent of the turn each face was shown in). Learner corrections are evidence, not inferred colors.

**Frame source seam:** the test needs the Activity's real decisions without CameraX; the seam sits after pixel reading, so `FrameReader` stays covered by its own tests and the Activity glue (load saved calibration, rebind, rescan, review, finish) becomes testable.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (JVM tests incl. `ScanRouteTest`, lint, Kover, boundary/manifest/denylist checks, debug and release APKs)

## Auto Run Result

**Summary:** The Solve tab shows *Scan my cube* next to *Enter colors* when `CAMERA_SCAN` is above OFF and a `ScanLauncher` is bound (the `ScanPortGate` also requires a camera). `MainActivity` registers `ScanFlowContract`; `SolveFlowViewModel.onScanOutcome` opens the Manual Cube Editor with the scanned stickers (Accepted), with the *Enter colors* start colors and a note (FellBackToEditor), or leaves Solve unchanged (Cancelled). A recorded camera denial opens the Editor directly with its note. CameraX moved behind an injectable `ScanFrameSource` (`CameraXFrameSource`), so `ScanRouteTest` (Robolectric + Hilt testing) drives the real route with fake frames: Solve → scan → review edit → calibrate offer → auto-captured swatches → *Save* → rescan with the saved calibration and a rebound source → review keeps the edit, no offer → *Accept* → Editor. Calibration swatches auto-capture through a per-swatch `GuidedScanController` hold (`CalibrationHold`). Debug flags: `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` OFF. Stage 6 shows camera copy only with a camera check action (none yet). The debug-only `GuidedScanDebugActivity`, its launcher entry and `GuidedScanDebugSummaryTest` are deleted.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- registers the scan contract; `onScan` with denial check off main and an in-flight guard.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanLauncher.kt` -- `cameraDenied(context)` (default false).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/{SolveFlowViewModel,SolveScreen}.kt` -- `onScanOutcome`, `scanDenied`, `Editing.note`; `solve:scan` button and `solve:scanNote` line.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- threads `onScan` (only with `CAMERA_SCAN` on); stops passing the camera flag to lessons.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `cameraCheck: Boolean = false` replaces the `cameraScan` level for Stage 6 copy.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` (new) -- `ScanFrame`, `ScanFrameSource`, `CameraXFrameSource` (binding, analyzer, `FrameReader`, ring, metering, diagnostics).
- `app/src/camera/kotlin/dev/cubentia/app/camera/{GuidedScanActivity,GuidedScanFlow,GuidedScanScreen,CameraModule,CameraScanLauncher}.kt` -- Activity uses the frame source; `CalibrationHold`; `withCorrections`; `Review.corrections`; `CameraDenialRecord.denied`; binding.
- `app/src/camera/debug/AndroidManifest.xml`, deleted `GuidedScanDebugActivity.kt` and `GuidedScanDebugSummaryTest.kt`.
- `build-logic/.../policy/FeatureFlagLevels{,Test}.kt` -- debug map follows wiring.
- `core/domain/src/main/resources/content/strings/en.json` -- three Solve scan strings.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` -- test-only `hilt-android-testing` + `kspTest`.
- Tests: `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` (route + denial), `GuidedScanFlowTest`, `CameraScanLauncherTest`, `SolveFlowViewModelTest`, `AppShellSolveTest`, `AppShellCameraScanTest`, `PhysicalLessonScreenTest`, `BuildConfigFeatureFlagsTest`.

**Review findings:** 19 (high 0, medium 5, low 8, false 5, maybe-false 1). Patched: repeated-tap guard (medium; blind + edge), wrapping scan note (medium), MainActivity denial test (medium; verification-gap + intent), Stage 6 test naming via `cameraCheck` (low). Deferred: `CameraXFrameSource` lifecycle untested off-device (maybe-false, medium if true; device session B3). Rejected: camera-less dead button (false: `ScanPortGate`), FellBack "empty" editor (false: the editor has no blank state; same start as *Enter colors*), Unavailable keeps source running (false: no frames after a failed bind), observers across rebinds (low, same camera), no live calibration coaching (low, unchanged), full face gate for swatch hold (false: AC names `onFrame`), Activity-level fallback/cancel (low, unit-tested pass-through), hidden-entry glue via AppShell (low), Settings-entry auto-capture (low, same branch), Welcome test (low, existing gate test), edits override rescan (false, intended).

**Follow-up review recommendation:** true -- three medium entries were patched on this first pass. Unverified risk: the new `scanInFlight` guard has no test of a second tap while a scan is open or of an Activity recreated mid-scan, and the CameraX frame-source extraction is exercised only by a fake until device session B3.

**Verification:** Codex ran `./gradlew spotlessApply`, `./gradlew -p build-logic test` and `./gradlew spotlessCheck build` (pass). After review patches: targeted `ScanRouteTest` (2), `PhysicalLessonScreenTest` (43), `AppShellSolveTest` (13) passed; `./gradlew -p build-logic test` and `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** Human gate open: device session B3 on the main debug build (Lenovo TB128XU, Galaxy S24 FE; stka, stla) must confirm the full scan from the Solve tab, the #166 checks and swatch auto-capture, and the PO records the calibration offer rule in the PRD memlog. The CameraX refactor is unverified on a device.

### Follow-up pass (2026-11-17)

**Summary:** Follow-up review of the merged-ready story. No production code changed; `ScanRouteTest` was strengthened.

**Files changed:** `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- new repeated-tap/cancel-then-rescan test; the rescan misreads the painted facelet so the kept edit is proved at the route level.

**Review findings:** 17 (high 0, medium 2, low 7, false 7, maybe-false 1). Patched: `scanInFlight` guard test (medium), discriminating kept-edit assertion (medium). Deferred: none new (CameraX frame-source lifecycle already deferred to device session B3). Rejected: first-ever denial card (false, intended by Design Notes), recorded denial blocks retries (false, matrix row; Settings grant recovers), FellBack reuses accepted (false, carried), pending denial-check race (low, ms window, fix adds state), stale layout callback (false, listener order), `hasCamera` (false, carried), face-level gate (false, carried), hidden entry via AppShell (low, carried), `receiveResult` bridge / no SessionEvent check (low), FellBack/Cancelled VM-only (low, carried), fake denial flag (low), VM internals in route test (low), GA Stage 6 expectation (false, AC7), hard-coded wired set (low).

**Follow-up review recommendation:** false -- follow-up pass patched no high (patched: 0 high, 2 medium, 0 low); both patches are test-only and each was shown to fail on a mutant (guard disabled; `withCorrections` removed).

**Verification:** `ScanRouteTest` (3 tests) passed; mutants killed. The new test first left state that broke the next class in the fork (`Cube3DEmphasisTest`, passing alone 3/3); it now returns the second scan's result and idles before teardown, and the full suite passes. `./gradlew spotlessApply`, `./gradlew -p build-logic test`, `./gradlew spotlessCheck build --rerun-tasks`: BUILD SUCCESSFUL.

**Residual risks:** unchanged -- device session B3 (Lenovo TB128XU, Galaxy S24 FE) must confirm the CameraX frame source, the scan from the Solve tab and swatch auto-capture; PO records the calibration offer rule.
