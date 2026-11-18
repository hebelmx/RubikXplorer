---
title: 'Story 11.1: Quick Scan from two corners'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '7a9b3d454144021dd584d5955a337d2240640a73'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A wrongly held corner is labeled from the prompt, so self-calibration can produce a valid but wrong state.
    evidence: |-
      QuickScanController stamps centerColor/upNeighborColor from QuickScanPlan and SelfCalibration.from has no
      plausibility check; the Guided Scan trusts its prompt the same way (pre-existing). A ColorPlausibility check
      of each center against its prompted color, or a saved-calibration cross-check, would settle it.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt
    severity: medium
  - summary: >-
      CameraXFrameSource's corner branch (ImageProxy to CornerReader) has no automated test.
    evidence: |-
      QuickScanRouteTest injects prebuilt CornerFrames through a fake source; no test drives the production adapter.
      Needs a pure frame-reading seam; device session C exercises the real path before merge.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt
    severity: medium
  - summary: >-
      CheckCameraSizeTask has no task-level test of its runtime-byte accounting.
    evidence: |-
      CameraSizePolicyTest covers only the arithmetic and forbidden filter; a TestKit run with a fixture AAR
      (jar + .so) would pin the subtotal.
    location: >-
      build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckCameraSizeTask.kt
    severity: low
  - summary: >-
      Corner detection is verified only on rendered and warped views inside the guide's search ranges; the real-corner
      harness prints counts without assertions.
    evidence: |-
      CornerFixtureHarnessTest is skipped (no Fixtures/corner yet) and asserts nothing; add expected recognition and
      assembly results once device session C records labeled corner pairs.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerFixtureHarnessTest.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The only camera path today is the Guided 6-face Scan (six captures). Story 11.1 (FR-22, FR-23) adds Quick Scan: two captures from opposite corners give all 54 stickers. Uncertain faces or an invalid state fall back to the Guided Scan grid for those faces only, and it never guesses a state.

**Approach:** Corner detection and a perspective unwarp inside the CameraX adapter (`app/src/camera/`), within OQ-7 decision K (Kotlin nearest-center classification, per-cell histogram-mode reader, self-calibration from the six centers, no new runtime). It emits ordinary `FaceSample`s with the prompted top. `ScanAssembler` assembles them, and the existing 10.3 review follows. `QUICK_SCAN` is raised in debug in the same PR that wires its main-app entry point. The on-device accuracy measurement and the GA/BETA recommendation happen at device session C, which is a before-merge human gate.

## Boundaries & Constraints

**Always:** Binding ADs from the story's Refs line: **AD-9**, **AD-18**. Only the camera source set touches pixels. Main code reaches the camera only through the `ScanLauncher` port. `ScanAssembler` is the only place samples are rotated (AD-9). The flag is read through the `FeatureFlags` port, never `BuildConfig`. `QUICK_SCAN` stays OFF in release and benchmark. Debug raises it only together with its wired entry point (AD-18). Learner copy goes in `en.json` content strings (NFR-10). The size cost fits NFR-11, with no OpenCV or LiteRT.

**Never:** a guessed state, a fill from the expected state, a numeric confidence shown to the Learner, a debug-only launcher as the acceptance path, or frames stored or sent.

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/scan/ScanLauncher.kt` -- port; methods default to `guidedScanIntent` (pattern for a new `quickScanIntent`). `ScanFlowContract.kt` -- `ActivityResultContract<Unit, ScanOutcome>`, `createIntent` calls `guidedScanIntent`; encode/decode 54 colors, decode re-validates. `ScanPortGate.kt:31` already lists `QUICK_SCAN` in `cameraFlags`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` ~L102-137 -- `guidedScanAction(onDenied, onOutcome)` (flag + `scanLauncher.isPresent` gate, `scanInFlight`, `cameraDenied` on IO); `onScan` routes to `solveFlow.onScanOutcome`. Template for the Quick Scan action; register in `onCreate`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` L137 `onScan`, L758-765 `onScan = onScan.takeIf { CAMERA_SCAN }`, `scanBeta`. `ui/solve/SolveScreen.kt` L41-99 -- Home: BETA `FlowRow` (Enter colors, OutlinedButton `solve:scan` with `BetaLabel` `solve:scan:beta`, `contentDescription "$label, $beta"`, `BetaNote` `solve:scan:betaNote`) vs GA `Row` (Button `solve:scan`, Enter colors). `ui/beta/BetaLabel.kt` -- `BetaLabel`, `BetaNote`; keys `msg.feature.beta`, `msg.feature.camera.beta-note`. `ui/solve/SolveFlowViewModel.kt` L61 `onScanOutcome`, L90 `scanDenied`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/` -- `FaceSample.kt:30` (`face, orientationDegrees, stickers: List<Lab>` row-major, `quality`, `centerColor`, `upNeighborColor`, `centerPlain`); `ScanAssembler.kt` `assemble` L194, `repromptFaces` L205 (10.14 involved faces, empty when > `MAX_REPROMPT_FACES`), `place` L320 (named top → fixed turn); `StickerClassifier.classify` (UNCERTAIN under 10 ΔE); `SelfCalibration.from(samples, saved)`; `ScanReview.fromSamples`; `GuidedScan.kt` -- `ScanStep`, `GuidedScanPlan.steps`/`reprompt(face)` (U→BLUE top, D→GREEN, sides→WHITE), `GuidedScanController(plan, reprompt)` L333 (steady hold L394-440: Remeter, `SETTLE_MS` 300, `STEADY_DELTA_E` 5, `STEADY_MS` 600, `FACE_CHANGE_DELTA_E` 15; `store()` L481; one re-prompt round), `FrameQuality.assess/assessShutter`, `FrameStats` L55. `LiveFixture.kt` -- `LiveFixtureRecord` (schema 2), `LiveFixturePaths`, `LiveFixtureJson`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/` -- `FrameReader.kt` (`YuvPlane`, `YuvFrame` L17-44 pure Kotlin; `readAt(frame, GridSquare): FrameStats` L245 = per-cell histogram-mode reader, the reuse point for an unwarped face; `rgbToLab`); `FaceLocator.kt` (axis-aligned lattice search, seam/contrast scoring idea; no homography exists anywhere); `ViewfinderGrid.kt` (`GridSquare`, `inView`, `inFrame(..., rotationDegrees)`, `GRID_FRACTION` 0.8); `ScanFrameSource.kt` (`ScanFrame(stats, rotationDegrees, ring)` L36, `ScanFrameSource` L42, `CameraXFrameSource.read()` L221, `meterOnGrid` L239); `GuidedScanActivity.kt` (extras parse L108-130, `solveScan` L115, review L223-262, `analyze` L325, `handle` L384, `newController()` L458 — Quick Check plan swap is the subset-plan precedent); `GuidedScanFlow.kt` (`phaseOf`, `reviewOf`, `withCorrections`, `CaptureFreeze` 400 ms); `GuidedScanScreen.kt` (`GuidedScanPhase.Viewfinder(stepIndex, captured, coaching, canUseAnyway, step, again, total, retake)`, `GuidedScanScreen` L183, `FrozenOverlay` haptic L409-414, `progressText` (`msg.scan.progress[-again]`), `Prompt` L471 (`retake` → `msg.scan.prompt-again-with-top`), `Swatch`, `MiniNet` L522); `CameraScanLauncher.kt`, `CameraModule.kt`.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` (+ release no-op `app/src/release/.../ScanDiagnostics.kt`) -- `TAG = "CubentiaScanDiag"`, `onAssembly`; numbers only, never pixels.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/` -- `FixtureRecorderActivity`, `FixtureRecorderScreen` (linear steps: cube+light, calibrate, per-face capture + `TruthEntry` with prefill), `RecorderRules`; writes `getExternalFilesDir/Fixtures/live/<device>_<cube>_<light>/<FACE>.{json,jpg}`; `Fixtures/README.md:76` export.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt:50` debug map (`&& it != "PLACEMENT"` exclusion); `build-logic/src/test/.../policy/FeatureFlagLevelsTest.kt:11`; `app/src/test/.../flags/BuildConfigFeatureFlagsTest.kt:21,31`; `app/src/sharedTest/.../session/TestLessons.kt:154` `testFlags` (`else -> GA`, so QUICK_SCAN is GA in every shell test today); `policy/AppSizePolicy.kt`, `tasks/CheckReleaseSizeTask.kt`, `ReleaseConventionPlugin.kt:86-120` (camera source set composition, `checkReleaseSize`); `policy/CameraClasspathPolicy.kt` (litert/tensorflow/opencv groups).
- Tests: `app/src/testDebug/kotlin/dev/cubentia/app/camera/FrameReaderTest.kt`/`FaceLocatorTest.kt` (synthetic `frame(w,h){x,y->rgb}` → YUV; `FaceLocatorTest.kt:244-310` loads `../Fixtures/live/*-a` JPEGs via `ImageIO`); `GuidedScanFlowTest.kt` (pure controller drive); `GuidedScanScreenTest.kt` (Robolectric `w360dp-h640dp-port`); `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` / `SolveFromScanRouteTest.kt` (`@UninstallModules(CameraModule)`, fake frames/launcher, `solve:scan` → `nextStartedActivityForResult`, `captureCube`, `returnToSolve`); `app/src/test/.../ui/shell/AppShellSolveTest.kt` L147 `show(...)`.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/scan/` + `core/domain/build.gradle.kts:41` -- on-demand evaluation tasks (`evaluateScanAccuracy`); pattern for a Quick Scan tally task.
- `Fixtures/live/*-a/{U,R,F,D,L,B}.{json,jpg}` -- flat faces with truth (2 devices × 4 cubes); no corner fixtures exist. The recorder grid inside each JPEG: `core/domain/src/evaluation/.../oq7/FrameGrid` (evaluation source set, not visible to `app`) and the grid `FaceLocatorTest.kt:244-310` uses; reproduce the latter in app tests.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` (new) -- `QuickScanPlan` (the two holds of Design Notes: faces in order top, front-left, front-right with center and named-top colors) and a pure `QuickScanController`: corner phase driven by per-frame `CornerReading`-agnostic input (three `FrameStats?` + detected flag), steady-hold auto-capture reusing the Guided constants (Remeter on first detection, settle, 27 cells steady 600 ms, wait for `FACE_CHANGE_DELTA_E` change before arming corner 2), `onShutter` capturing whatever was located, emitting effects `Remeter`, `CornerCaptured(n)`, `Fallback(faces, round)`, `Done(samples)`; builds `FaceSample`s with `face`/`centerColor`/`upNeighborColor` from the plan and `orientationDegrees = 0`; after corner 2 computes the hand-off (rules in Design Notes) and hands fallback faces to a `GuidedScanController(plan = faces.map(GuidedScanPlan::reprompt), reprompt = involved faces over merged samples)` -- keeps all state logic JVM-testable in `:core:domain` (Kover).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanControllerTest.kt` (new) -- every I/O row in Design Notes' behavior table: auto-capture timing, no double capture before face change, shutter capture, any-UNCERTAIN face → fallback, missing/poor face → fallback, invalid after fallback → `repromptFaces` faces once, still invalid → `Done` (review), all good → `Done` with 6 posed samples that `ScanAssembler.assemble` turns into the truth state for a scrambled cube.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt` (new) -- pure Kotlin on `YuvFrame`: `CornerGuide` (regular hexagon + Y, top vertex up in the Learner's view, inscribed in the `ViewfinderGrid` square, rotated by `rotationDegrees`), a guide-relative search for the seven corner-view vertices (scored on dark seams/outline contrast like `FaceLocator`), returning three face quads or null; `Homography` (4-point solve, apply) and `unwarp(frame, quad, side = 96): YuvFrame` (bilinear resample into an upright square per the quad-to-square mapping in Design Notes); `CornerReader.read(frame, guide): CornerFrame(detected, faces: List<FrameStats?>)` calling `FrameReader.readAt` on each unwarped face -- detection inside the adapter, no new runtime (AD-9, OQ-7 K).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- add a corner mode (`start(..., corner: Boolean)` or a mode property) so `read()` attaches `ScanFrame.corner: CornerFrame?` using the corner guide; metering targets the guide square -- frames still never stored.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt`, `GuidedScanFlow.kt`, `GuidedScanScreen.kt` -- `EXTRA_QUICK_SCAN` starts a `QuickScanController`; Quick Scan counts as a Solve scan (`offerEnterColors`, `FellBackToEditor`); new `GuidedScanPhase.CornerViewfinder(hold, counter n/2, captured, coaching)` rendering the Y outline (`scan-grid` 70 % + 1dp dark halo, static), counter `msg.quick-scan.counter` in the `data` type style as a polite live region (tag `quickScan:counter`), hold prompt with three glyph swatches (tag `scanPrompt`), mini net (captured faces filled, the hold's three faces outlined), Capture shutter, Close, Enter colors instead; on each corner capture: 400 ms freeze (no shrink), shutter sound and haptic as Guided; fallback renders the existing `Viewfinder` phase with `again` progress (`msg.scan.progress-again`, n of fallback faces), `retake = true` prompt, mini net showing every face read so far plus the current face outlined, and no counter; then the existing 10.3 review via `ScanReview.fromSamples` -- one Activity, one review path.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CameraScanLauncher.kt` -- `quickScanIntent(ctx) = guidedScanIntent(ctx).putExtra(EXTRA_QUICK_SCAN, true)`.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanLauncher.kt`, `ScanFlowContract.kt` -- `quickScanIntent` with default `guidedScanIntent`; `ScanFlowContract(launcher, quick: Boolean = false)` picks the intent.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- `quickScanAction` gated on `QUICK_SCAN` enabled + binding present (generalize `guidedScanAction` with a flag/contract parameter; one shared `scanInFlight`), outcomes to `solveFlow.onScanOutcome`, denial to `solveFlow.scanDenied()`; pass `onQuickScan` to `AppShell`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt`, `ui/solve/SolveScreen.kt` -- `onQuickScan.takeIf { QUICK_SCAN enabled }`, `quickScanBeta = level == BETA`; Solve Home layout per Design Notes (button tag `solve:quickScan`, label tag `solve:quickScan:beta`, `contentDescription "Quick Scan, BETA"` at BETA, note tag `solve:quickScan:betaNote`); no other screen changes.
- `core/domain/src/main/resources/content/strings/en.json` -- add `btn.solve.quick-scan` "Quick Scan", `msg.quick-scan.hold-first` "Hold white on top, green and red facing you", `msg.quick-scan.hold-second` "Now yellow on top, blue and orange facing you", `msg.quick-scan.counter` "{n} / {total}".
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` + release no-op -- `onCornerFrame` (detected, per-face quality, search score; rate-limited like `onFrame`) and `onQuickScanRun(elapsedMs, fallbackFaces, repromptFaces, corrections, outcome)` logging one `quickScan run …` line (elapsed from the first corner-viewfinder frame to Accept/Close; corrections = review stickers the Learner changed) -- the on-device measurement source.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/quickscan/QuickScanMeasurement.kt` (new) + `core/domain/build.gradle.kts` task `evaluateQuickScan` (args: log file) + `core/domain/src/test/.../QuickScanMeasurementTest.kt` -- parse `quickScan run` lines; a run succeeds when ACCEPTED, corrections ≤ 3 and elapsed < 15 000 ms; recommend GA iff successes / runs ≥ 0.95, else BETA; prints counts and writes `core/domain/build/reports/quick-scan-measurement.md`; not part of `build`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `CornerFixtureRecord` (schema 3: device, cube, light, capturedAt, calibration, `hold` 1|2, three `LabeledFaceSample`s in hold order, per-face `truthSources`, `frame = "corner-<hold>.jpg"`), `CornerFixturePaths` (`Fixtures/corner/<device>_<cube>_<light>/corner-<hold>.{json,jpg}`), `CornerFixtureJson.decode` validation; schema 1/2 untouched; tests in `core/domain/src/test/.../LiveFixtureTest` style.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt`, `FixtureRecorderActivity.kt`, `RecorderRules.kt` -- a "Corner mode" toggle on step 1; in corner mode the capture step shows the Y outline and hold 1 then hold 2, captures with the corner reader, then `TruthEntry` for each of the three faces (prefilled with the existing close-call rule) and writes a schema-3 record + downscaled JPEG; `Fixtures/README.md` documents corner mode and `adb pull …/Fixtures/corner Fixtures/` -- developer tool only.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerLocatorTest.kt` (new) -- (a) analytic: a rendered solved and a scrambled cube corner under a projective transform (scale 0.7-1.0 of the guide, ±10° roll, perspective), plus YUV noise, in portrait and rotated frames → detected, all 27 stickers read to the rendered colors; (b) synthesized from fixtures: for every `Fixtures/live/*-a` group, warp the recorded U/F/R and D/L/B face crops (recorder grid) into the two corner views on a dark background, run `CornerReader`, and assert detection on every view and that no sticker the flat-frame `readAt` classifies CONFIDENT-correct becomes CONFIDENT-wrong after unwarp; (c) negative: no cube / a single flat face → not detected.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerFixtureHarnessTest.kt` (new) -- runs `CornerReader` + classification over `Fixtures/corner/*` schema-3 records and prints per-sticker correct / confidently wrong / uncertain and per-scan valid counts; skipped by JUnit assumption when the folder is absent.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/QuickScanScreenTest.kt` (new, Robolectric `w360dp-h640dp-port`) -- corner phase shows outline, "1 / 2" counter (live region), hold-1 copy, Enter colors instead; after capture 1 shows "2 / 2" and hold-2 copy; fallback phase shows no counter, the re-prompt form ("Show the green face again, with white on top."), "Again: face 1 of n", mini net.
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` (new, pattern of `SolveFromScanRouteTest`) -- debug flags: Welcome → Solve → `solve:quickScan` starts `GuidedScanActivity` with `EXTRA_QUICK_SCAN`; fake corner frames for two good holds → review → Accept → Solve receives `Accepted`; a run with one uncertain face asks only that face in the grid before review.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt`, `app/src/sharedTest/.../session/TestLessons.kt` -- `testFlags(quickScan: FlagLevel = OFF)`; tests: BETA shows `solve:quickScan` with "Quick Scan, BETA" next to *Scan my cube* and tapping it calls `onQuickScan`; GA shows it without the label; OFF (or no action) hides it; Welcome/Physical/mismatch entry points unchanged.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt`, `build-logic/src/test/.../FeatureFlagLevelsTest.kt`, `app/src/test/.../flags/BuildConfigFeatureFlagsTest.kt` -- debug `QUICK_SCAN=BETA`; release/benchmark OFF unchanged; update pinned strings and the per-flag debug expectation.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/CameraSizePolicy.kt` (new), `tasks/CheckCameraSizeTask.kt` (new), registration in `ReleaseConventionPlugin.kt`, `build-logic/src/test/.../CameraSizePolicyTest.kt` -- `:app:checkCameraSize`: release estimate (as `checkReleaseSize`) + 3 × bytes of the compiled debug camera classes outside `recorder/` + bytes of the camera runtime artifacts on `debugRuntimeClasspath` (jar + uncompressed `.so`), fails at ≥ 30 000 000 and lists any litert/tensorflow/opencv artifact; report `app/build/reports/cubentia/camera-size.txt`; on demand, not wired into `build` -- the NFR-11 check device session C runs.

**Acceptance Criteria:**
- Given debug flags (`QUICK_SCAN` BETA), when the Learner opens Solve, then *Quick Scan* with the shared BETA label sits next to *Scan my cube*, and tapping it opens the corner capture from the main-app entry point.
- Given the corner capture, when two good holds are held steady (or the shutter is used), then the counter goes 1 / 2 → 2 / 2 with a haptic and a polite announcement, the adapter emits six `FaceSample`s, and `ScanAssembler` yields all 54 stickers for review.
- Given any face with an UNCERTAIN sticker or not found, when assembly finishes, then only those faces are asked in the Guided grid before review; an invalid state then asks the 10.14 involved faces once; still invalid goes to the 10.3 review, never a guessed state.
- Given release and benchmark, when built, then `QUICK_SCAN` is OFF and nothing changes; given `./gradlew :app:checkCameraSize`, then the projected size is under 30 MB with no OpenCV or LiteRT.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 38 findings — high 0, medium 10, low 15, false 13, maybe-false 0
- findings:
  - `[medium]` `[defer]` (blind) Quick Scan stamps face identity from the prompt, so a wrongly held cube can self-calibrate into a valid but wrong state — same prompt-trust as the Guided Scan (`GuidedScanController.store` stamps `centerColor` from the step, `SelfCalibration` has no plausibility check); pre-existing design, deferred.
  - `[low]` `[reject]` (blind) Two shutter taps with no frame send all six faces to the Guided grid — never a guessed state, needs a deliberate tap with no cube in view; the fix adds a guard.
  - `[medium]` `[patch]` (blind) Hold-2 lock clears on any single changed sticker, so a shifted first corner can be taken as corner 2 — `QuickScanController.turnedOver` now requires a detected frame whose top center (white vs yellow) changed; test `the second corner arms only on a detected view whose top center changed`.
  - `[false]` `[reject]` (blind) Parity-only invalid states skip the grid — clarification 3 asks only for the faces the involved-faces rule names, then the 10.3 review, which is what happens.
  - `[false]` `[reject]` (blind) A Guided replacement with uncertain stickers reaches review — clarification 3 rescans corner faces once; uncertain stickers from the grid are resolved in review (AD-9 acceptance), never guessed.
  - `[low]` `[reject]` (blind) Timing starts at the first corner frame and omits pre-frame exits — the spec settles the start; pre-frame exits are permission/camera start, not recognition; fix adds a logging branch.
  - `[low]` `[reject]` (blind) GA line can come from one run or one cube; no matrix labels — the PO tallies one log per matrix cell and reads the printed counts; adding labels adds fields and parameters.
  - `[low]` `[reject]` (blind) Real-corner harness skips/prints without assertions — spec design (device session C reads counts); see the deferred real-fixture assertion.
  - `[low]` `[reject]` (blind) Recorder analyzes a crop-based guide while the preview draws a view-based one — same assumption as the existing flat-face recorder grid, and the locator searches scale 0.7–1.0; developer tool.
  - `[false]` `[reject]` (blind) `checkCameraSize` not in CI — the intent names it as the check device session C runs; on demand by design.
  - `[false]` `[reject]` (blind) Size estimate ignores resources/assets and non-CameraX deps — the camera source set has only `kotlin/` and manifests, and its only dependencies are the three CameraX artifacts (`ReleaseConventionPlugin.kt:96`).
  - `[medium]` `[patch]` (edge) An undetected frame with changed readings unlocks hold 2 — same root cause as the hold-2 lock row; unlock now requires `detected`.
  - `[medium]` `[patch]` (edge) A partial first corner with no comparable cell blocks hold 2 forever — same root cause; `turnedOver` unlocks when no cell is comparable; test `a second corner with no cell comparable to a partial first corner still arms`.
  - `[medium]` `[patch]` (edge) When the corners cannot self-calibrate, a kept corner face with an UNCERTAIN sticker skips the grid — the round-1 re-prompt now re-checks kept corner faces (`needGrid`) on the merged scan; test `a kept corner face with an uncertain sticker is asked once calibration becomes possible`.
  - `[medium]` `[patch]` (edge) The fallback grid accepts a face already read from a corner — `GuidedScanController` takes internal `known` samples for its repeated-face check; test `the guided grid rejects a face already read from a corner`.
  - `[false]` `[reject]` (edge) System Back in review discards edits — identical to the Guided Scan, where Back finishes the Activity as Cancelled with no confirmation; the spec says Close/back as Guided.
  - `[low]` `[reject]` (edge) Runs closed before the first frame are not logged — same root cause as the timing row.
  - `[false]` `[reject]` (edge) Dual-BETA note carries only `solve:scan:betaNote` — that is the spec's settled behavior (one note, existing tag).
  - `[low]` `[patch]` (edge) Recorder recalibration keeps a pending corner read under the old calibration — Recalibrate now calls `resetCorner()`.
  - `[low]` `[reject]` (edge) Recorder crashes if external storage or mkdirs fails — app-specific external storage is always present on the session devices; developer tool.
  - `[low]` `[reject]` (edge) JPEG written before JSON can leave a stale pair on a process kill — rare, developer tool, fix needs staging logic.
  - `[low]` `[reject]` (edge) Out-of-range digits abort the measurement — log lines come from our own `Long`/`Int` formatting.
  - `[low]` `[reject]` (edge) Shared group:module with differing versions undercounts camera size — release has no CameraX; shared androidx modules differ marginally; fix needs version-aware accounting.
  - `[medium]` `[patch]` (verification-gap) Auto-capture is not verified through the Solve route — added `steadyCornersCaptureThemselvesAndTheRunRecordCountsReviewEdits` (timed steady frames, no shutter, counter 1 / 2 → 2 / 2, review opens).
  - `[medium]` `[defer]` (verification-gap) `CameraXFrameSource` corner branch has no test — needs an `ImageProxy` seam refactor; device session C exercises it before merge.
  - `[medium]` `[patch]` (verification-gap) The emitted `quickScan run` record is untested — the same new route test edits one review sticker, closes, and asserts exactly one record with `corrections=1 … outcome=CANCELLED` via `ShadowLog`.
  - `[low]` `[defer]` (verification-gap) `CheckCameraSizeTask` has no task-level test — needs a TestKit fixture AAR; the report is read at device session C.
  - `[medium]` `[defer]` (verification-gap) Real-corner harness asserts nothing — add assertions once device session C supplies labeled corner pairs.
  - `[false]` `[reject]` (verification-gap, other) Overlay is 80 % of the shorter side, spec says 70 % — the spec's 70 % is the `scan-grid` opacity, not the size.
  - `[false]` `[reject]` (intent) AC1 "method from OQ-7 / 10.10" is not a confirmed corner method — the spine says Story 11.1 builds detection within decision K (Kotlin, no new runtime), which the diff does.
  - `[low]` `[defer]` (intent) Detection is tested only inside the guide's own search ranges — same root cause as the deferred real-corner-fixture assertion.
  - `[low]` `[reject]` (intent) Route test skips the pixel pipeline; haptic/announcement not asserted — auto-capture now covered (patched row); pixels deferred; haptic is the existing `FrozenOverlay` and the announcement is the asserted polite live region.
  - `[false]` `[reject]` (intent) The 10.14 rule is widened (`repromptPosed`) — for an all-posed Quick Scan the unwidened rule names no face, which would make clarification 3's re-ask void; the widening stays inside `ScanAssembler` (AD-9) with its `MAX_REPROMPT_FACES` cap.
  - `[false]` `[reject]` (intent) Hand-off widened to non-ACCEPTABLE quality — "not found/occluded" covers glare/blur/low light; settled in the spec's behavior table.
  - `[false]` `[reject]` (intent) Measurement proxies (ACCEPTED, review edits, time to Accept) — Accept requires a valid Full state (AD-9), and the definitions are settled in the spec; pooled-vs-matrix is the rejected matrix-label row.
  - `[false]` `[reject]` (intent) Size check is a projection — the intent asks for the size check the session runs; NFR-11 is measured at release by Story 10.11.
  - `[low]` `[reject]` (intent) Copy/visual details: Guided re-prompt string vs "· White on top", hold-2 swatch order, mini-net highlight checked on a hand-built phase — the re-prompt string is the spec's reading of "Guided re-prompt form"; swatches follow left-to-right placement; `phaseOf(QuickScanController)` sets `captured` and the step face.
  - `[false]` `[reject]` (intent) Quick Scan gated without `CAMERA_SCAN` — `FeatureFlagLevels.cameraViolations` fails the build unless `CAMERA_SCAN` is above OFF whenever `QUICK_SCAN` is.

## Design Notes

**Holds and face mapping** (PO clarification 5; chirality fixed by geometry: with yellow on top the D-B-L corner puts orange front-left, blue front-right). Hexagon vertices: top T, upper-left UL, lower-left LL, bottom B, lower-right LR, upper-right UR, center C. Unwarp square (TL, TR, BR, BL): top face (T, UR, C, UL); front-left (UL, C, B, LL); front-right (C, UR, LR, B). Emitted `upNeighborColor` is the face across the unwarped top edge; `orientationDegrees = 0`.

| Hold | Copy key | Top (up) | Front-left (up) | Front-right (up) |
|---|---|---|---|---|
| 1 | `msg.quick-scan.hold-first` | WHITE (BLUE) | GREEN (WHITE) | RED (WHITE) |
| 2 | `msg.quick-scan.hold-second` | YELLOW (RED) | ORANGE (YELLOW) | BLUE (YELLOW) |

**Behavior table (settled UI choices; source in brackets).**

| Scenario | Behavior |
|---|---|
| Entry (clar. 1, AD-18 label from flag) | Debug `QUICK_SCAN=BETA` (the PO's button carries the BETA label; GA/BETA is decided at device session C). CAMERA_SCAN GA: `FlowRow` [Scan my cube (Button)] [Quick Scan (OutlinedButton + BetaLabel)] [Enter colors], then the camera `BetaNote` (`solve:quickScan:betaNote`). CAMERA_SCAN BETA: [Enter colors] [Scan my cube BETA] [Quick Scan BETA], one note (the existing `solve:scan:betaNote`). QUICK_SCAN GA: Quick Scan outlined, no label, no extra note. Only Solve. |
| Corner aligned (clar. 2, UX-DR15) | Corner detected and every face passes `assessShutter` → Remeter on first detection, settle, 600 ms steady → auto-capture. Shutter always captures; unlocated faces become fallback faces. No "Use anyway". |
| Capture feedback (clar. 2, Guided parity) | 400 ms freeze of the preview, shutter sound and `KeyboardTap` haptic (when haptics are on) as Guided; no shrink animation; counter updates in its polite live region, prompt switches to hold 2; corner 2 arms only after the view changes (`FACE_CHANGE_DELTA_E`). |
| Coaching (UX-DR15 "inline quality coaching") | Only face-agnostic quality coaching (glare, blur, low light) via existing keys from the worst face; no alignment/move coaching in the corner phase (the outline and hold prompt guide). Fallback grid keeps all Guided coaching. |
| Hand-off (clar. 3) | After corner 2: `SelfCalibration.from(samples, saved)`; fallback = faces missing, not ACCEPTABLE, or with ≥ 1 UNCERTAIN sticker. If calibration fails, every face whose center is missing is fallback; still uncalibrated after fallback → same end-of-scan handling as Guided. |
| Fallback screen (clar. 6) | Existing Guided Viewfinder: no counter, `again` progress "Again: face n of total" (total = faces asked), `retake` prompt `msg.scan.prompt-again-with-top` via `GuidedScanPlan.reprompt`, mini net with all faces read and the asked face outlined, Enter colors instead, Close. |
| Invalid state (clar. 3, AD-9) | `ScanAssembler.repromptFaces(merged, cal)` faces asked once in the same fallback screen; empty or still invalid → 10.3 review (which names the bad stickers). |
| Close / back | `Cancelled`, as Guided. Enter colors instead → `FellBackToEditor`. |

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: success (lint, JVM/Robolectric tests incl. new ones, Kover, boundary/manifest/denylist/classpath gates, debug and release APKs).
- `./gradlew -p build-logic test` -- expected: success (flag levels, size policy).
- `./gradlew :app:checkCameraSize` -- expected: success, report under 30 000 000 bytes, no litert/tensorflow/opencv.
- `./gradlew :core:domain:evaluateQuickScan --args=<sample log>` on a test log -- expected: report with counts and a GA/BETA line.

**Manual checks (device session C, before merge):**
- PO runs Quick Scan on the test matrix, pulls `CubentiaScanDiag` logs, runs `evaluateQuickScan`, records the GA/BETA recommendation; records corner fixtures with the recorder's corner mode.

## Auto Run Result

Status: done
Blocking condition: none (before-merge human gate remains: device session C).

**Summary.** Quick Scan from two corners, code side. Solve shows *Quick Scan* with the shared BETA label next to *Scan my cube* when `QUICK_SCAN` is on (debug BETA; release/benchmark OFF). The camera adapter finds the Y-shaped corner in pure Kotlin, unwarps each visible face with a 4-point homography into a 96 px square, and reads it with the existing histogram-mode `FrameReader.readAt`. It emits posed `FaceSample`s (prompted top, `orientationDegrees = 0`). `QuickScanController` (`:core:domain`) auto-captures after settle + 600 ms steady (shutter as backup) and hands missing / poor / UNCERTAIN faces to the Guided grid for those faces only. It then asks the involved faces once and opens the 10.3 review. Also added: a debug `quickScan run` log line plus the `evaluateQuickScan` tally (GA at ≥ 95 % accepted with ≤ 3 corrections in < 15 s, otherwise BETA), a recorder corner mode with schema-3 corner fixtures, and `:app:checkCameraSize`.

**Files changed.**
- `core/domain/.../recognition/QuickScan.kt` (new) -- `QuickScanPlan` holds, `QuickScanController` state machine and hand-off.
- `core/domain/.../recognition/GuidedScan.kt` -- internal constructor (`allowAdditionalReprompts`, `known` corner faces for the repeated-face check); re-prompt can add faces.
- `core/domain/.../recognition/ScanAssembler.kt` -- `repromptFaces(..., repromptPosed)` so posed Quick Scan faces implicated by illegal pieces are named.
- `core/domain/.../recognition/LiveFixture.kt` -- `CornerFixtureRecord` schema 3, paths, JSON validation.
- `core/domain/src/main/resources/content/strings/en.json` -- `btn.solve.quick-scan`, `msg.quick-scan.hold-first/second`, `msg.quick-scan.counter`.
- `core/domain/src/evaluation/.../quickscan/QuickScanMeasurement.kt`, `core/domain/build.gradle.kts` -- `evaluateQuickScan` tally and report.
- `app/src/camera/.../CornerLocator.kt` (new) -- `CornerGuide`, `Homography`, `CornerLocator`, `unwarp`, `CornerReader`.
- `app/src/camera/.../ScanFrameSource.kt`, `FrameReader.kt` -- corner mode delivering `ScanFrame.corner`; `ImageProxy.asYuvFrame`.
- `app/src/camera/.../GuidedScanActivity.kt`, `GuidedScanFlow.kt`, `GuidedScanScreen.kt` -- `EXTRA_QUICK_SCAN`, `CornerViewfinder` phase (Y outline, live-region counter, hold prompt with swatches, mini net), freeze/haptic/sound per capture, fallback as Guided re-prompt, run logging.
- `app/src/camera/.../CameraScanLauncher.kt`, `app/src/main/.../scan/ScanLauncher.kt`, `ScanFlowContract.kt`, `MainActivity.kt`, `ui/shell/AppShell.kt`, `ui/solve/SolveScreen.kt` -- `quickScanIntent`, Quick Scan action gated on `QUICK_SCAN`, Solve button and BETA note.
- `app/src/debug|release/.../ScanDiagnostics.kt` -- `onCornerFrame`, `onQuickScanRun` (release no-op).
- `app/src/camera/debug/.../recorder/*` -- corner mode (toggle, Y outline, per-face truth, schema-3 save; reset on recalibration); `Fixtures/README.md` export notes.
- `build-logic/.../FeatureFlagLevels.kt` (+ test), `CameraSizePolicy.kt`, `CheckCameraSizeTask.kt`, `ReleaseConventionPlugin.kt` -- debug `QUICK_SCAN=BETA`, `:app:checkCameraSize`.
- Tests: `QuickScanControllerTest`, `QuickScanMeasurementTest`, `LiveFixtureTest`, `CornerLocatorTest`, `CornerFixtureHarnessTest`, `QuickScanScreenTest`, `QuickScanRouteTest`, `AppShellSolveTest`, `BuildConfigFeatureFlagsTest`, `CameraSizePolicyTest`, `TestLessons.testFlags(quickScan)`.

**Review findings.** 38 findings across 4 layers (high 0, medium 10, low 15, false 13). Patched: 7 rows, which make 5 medium entries (hold-2 unlock guard; re-check of kept corner faces for UNCERTAIN stickers; known corner faces in the grid's repeated-face check; route test for auto-capture; route test for the run record), plus 1 low entry (recorder reset on recalibration). Deferred: 4 entries (wrong-hold prompt trust, pre-existing; production frame-source corner branch test; size-task TestKit test; real-corner assertions after session C). Rejected: 13 false and 12 low rows, each with its reason in the Review Triage Log above.

**Follow-up review recommended: true.** Patched by verdict: high 0, medium 5 entries, low 1. Specific unverified risk: the post-review hand-off changes (`needGrid` re-check in the round-1 re-prompt and `GuidedScanController.known`) are covered only by the new JVM tests. They have not been through a review layer, and they change which faces the second fallback round asks.

**Verification.** After the patches:
- `./gradlew spotlessCheck build` -- success.
- `./gradlew -p build-logic test` -- success.
- `./gradlew :app:checkCameraSize` -- success: 24,006,720 bytes projected against 30,000,000; no forbidden artifacts.
- `./gradlew :core:domain:evaluateQuickScan --args=<sample log>` -- success.

New suites passed: `QuickScanControllerTest` 10/10, `QuickScanRouteTest` 3/3, `CornerLocatorTest` 3/3. `CornerFixtureHarnessTest` is skipped until `Fixtures/corner` exists.

**Residual risks.**
- Real-world corner detection is unmeasured. The tests use rendered and warped views only.
- The ≥ 95 % / < 15 s measurement, the GA/BETA recommendation and real corner fixtures all happen at device session C, which is the before-merge human gate.
- The projected camera size counts the whole CameraX stack. NFR-11's release measurement stays with Story 10.11.
