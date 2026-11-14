# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let a Learner scan a physical cube face by face, correct and calibrate the read, solve it from the scan, and verify physical Moves with a Quick Check, so the tutor works from evidence instead of prediction. This is v1.1 work built on `main` behind feature flags: every camera flag stays OFF in release, and a release whose camera flags are OFF must contain no camera code, dependency or permission. Each feature reaches Learners only when the PO raises its flag at a release, using test-matrix evidence to choose OFF, BETA or GA.

## Stories

- Story 10.9: Keep camera code out of a release whose camera flags are OFF
- Story 10.10: Live recognition baseline and fixture recorder
- Story 10.1: Classify stickers and assemble a Scan Result
- Story 10.2: Guided 6-face Scan viewfinder
- Story 10.3: Review and correct a Scan Result
- Story 10.4: Calibrate colors for my cube
- Story 10.5: Scan to solve
- Story 10.6: Quick Check evidence and Certainty
- Story 10.7: Quick Check in Physical-cube Mode and playback
- Story 10.8: Recognition test matrix and GA/BETA decision
- Story 10.11: Raise a camera feature above OFF for a release

## Requirements & Constraints

- **Never use an unvalidated scan.** Every Scan Result goes through the same validation as the Manual Cube Editor. 54 recognized colors are never valid by themselves. Uncertain stickers must be shown and resolved by the Learner before Accept is enabled. The Solver is never called on an unvalidated result.
- **Manual fallback always.** *Enter colors instead* is always reachable. If camera permission is denied from Solve, the Manual Cube Editor opens directly. On devices with no camera, or a camera that cannot be bound, every scan entry point is hidden, and the app still installs (camera `uses-feature` with `required="false"`).
- **Accuracy targets (provisional):**
  - Guided Scan: ≥ 99.5% of stickers read correctly in normal indoor light, ≥ 98% of scans valid after ≤ 3 corrections, < 45 s for a repeat user.
  - Quick Check: ≤ 2 captures, 0 false *verified* in the scenario suite, and a matching cube confirmed in ≥ 98% of checks.
  - Confidently-wrong stickers (misread but marked confident): ≤ 0.1%.
- **Live baseline go/no-go:** if live per-sticker accuracy on the Lenovo is below 90%, re-plan before the viewfinder story. From 90% to 99%, continue as BETA-only.
- **Devices:** the Lenovo Tab M10 Plus 3rd Gen (minimum) and the Galaxy S24 FE (mid) gate recognition. The Galaxy Tab S7 FE only reports results. Emulator cameras are never acceptance evidence. The test matrix is a stickered and a stickerless cube × daylight, warm indoor, cool LED and low light.
- **Privacy and offline:** frames stay in memory and are never persisted, sent or logged. Release logs contain no samples or Cube States. No INTERNET permission. CAMERA is the only runtime permission, and only in a build type where a camera flag is above OFF. ML Kit and Play-services ML are on the SDK denylist.
- **Size:** install size < 30 MB, including any recognition model. It is checked at each release that raises a camera flag.
- **Quality:** recognition classification and assembly are pure Kotlin in `:core:domain`, with ≥ 80% line coverage. Recognition fixture tests run on the JVM in CI.
- **Only standard color scheme** (white opposite yellow, green opposite blue, red opposite orange). Calibration persists per device and can be reset on its own.

## Technical Decisions

- **Build-time camera gating:** `FeatureFlagLevels.byBuildType` in `build-logic` is the only source of flag levels. If a build type has any of `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` or `CASE_RECOGNITION` above OFF, `build-logic` adds the camera source set (`app/src/camera/kotlin`, plus a camera `AndroidManifest.xml` declaring `CAMERA` and the optional camera `uses-feature`) and the CameraX dependencies to that build type only. `PLACEMENT`, `QUICK_SCAN` and `CASE_RECOGNITION` above OFF while `CAMERA_SCAN` is OFF fails the build, and the error names the flag. `ManifestPolicy` keys on `variant.debuggable`.
- **Camera port:** CameraX code lives only in `app/src/camera/`, never in `app/src/main`. Main code reaches it only through the `ScanLauncher` port, which has an optional Hilt binding (`@BindsOptionalOf`) that only the camera source set provides. With no binding, every scan entry point is hidden. A debuggable app with a camera flag above OFF and no binding fails at startup.
- **Pipeline:**
  - The CameraX adapter is the only code that touches pixels. It emits `FaceSample`s: the center and up-neighbor colors, 9 CIELAB samples in viewfinder order, quality flags and the capture pose.
  - Only `ScanAssembler` in `:core:domain` rotates samples into URFDLB `Facelets` order. Within each face, indices run row-major in the Kociemba net. The home frame has WHITE on U and GREEN on F.
  - `StickerClassifier` receives the calibration as a parameter. The output is `ScanResult.Full(Facelets + StickerConfidence)` or `Partial`.
  - Extend the existing `dev.cubentia.domain.recognition` types (`FaceSample`, `CalibrationCenters`, `PhysicalObservation`) instead of duplicating them.
  - `CubeValidator` is the only route from `Facelets` to a `CubeState`.
- **Categorical only:** `StickerConfidence {CONFIDENT, UNCERTAIN}` and `Certainty {VERIFIED, LIKELY, UNKNOWN}`. No numeric confidence crosses a module boundary.
- **Observations:**
  - `PhysicalObservation.Full` can come only through the validator.
  - A `Partial` is checked for internal consistency only. It is never completed from `expected`, and it must be completed by a Guided Scan or the Editor before `AdoptObserved`.
  - Only `tutor/Assess` produces Certainty. VERIFIED requires two things: the observed faces cover every face that any Move since the last Full verified observation could have touched, and exactly one `CandidateEnumerator` candidate fits.
- **Session rules:**
  - The reducer rejects `ScanAccepted` unless the result is a valid Full, every UNCERTAIN sticker has been resolved, and `basedOnRevision` is current.
  - `ScanAccepted` is an undo barrier and clears redo.
  - Any `PhysicalConfirmed` makes a prior observation stale. Stale results are discarded, and the action is re-offered if the Learner started it.
  - Scans produce a `Snap` presentation.
- **Scan sub-flow:** returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result. Only the caller dispatches it, and the flow never touches a session. Scan review is not a session.
- **Calibration:**
  - `CalibrationRepository` is the only owner. Only the `UpdateCalibration` and `ResetCalibration` use cases write it. It is never stored in `SessionState` or a Checkpoint.
  - It is stored in a Room table that moves the database from version 7 to 8, with an exported `8.json` schema, a 7→8 migration and a migration test that preserves every existing table's rows.
- **Concurrency:** classification runs on `Dispatchers.Default`, and CameraX is bound off the main thread. A debug StrictMode run must add no main-thread disk violation in app frames.
- **Fixture recorder:** a debug-only recorder, the former Story 1.9 spike activity, moved into the debug camera source set. It exports `FaceSample` JSON plus ground truth, device, OS build, cube and light to `Fixtures/live/<device>_<cube>_<light>/`. No release or benchmark build contains it. The `spikeScan` source set and its Gradle switch are deleted when the viewfinder story closes.
- **Detection approach:** classical CV in Kotlin first. The live baseline confirms or revises this, including its size cost.
- **Privacy facts:** with CAMERA present, `docs/privacy-facts.json` lists `runtimePermissions: ["android.permission.CAMERA"]`. Still undecided: whether to add `schemaVersion: 2` with `cameraFramesLeaveDevice: false`, or to have the site read the facts at the release tag. This must be decided no later than the first flag-raise release.
- **Flags:** use the `FeatureFlags` port and never `BuildConfig`. Use the one shared BETA label component that shipped for *Test me*.

## UX & Interaction Patterns

- **Viewfinder:**
  - Full-bleed preview with a 3×3 grid. It captures automatically after about 600 ms aligned and steady, with a manual shutter as backup.
  - The face prompt names the center and up colors, with a glyph swatch. A mini net shows progress.
  - A wrong orientation produces a re-prompt, not an error. Quality coaching appears inline ("Too much glare — tilt the cube").
  - The camera runs in portrait. In landscape the overlays rotate and the grid stays upright.
- **Permission rationale:** shown before the system dialog: "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." It has two actions: *Continue* and *Enter colors instead*. If the permission is denied, an explanation card appears, with a Settings deep-link when it is permanently denied. All copy comes from content strings.
- **Review net:**
  - Uncertain stickers are pre-selected, with a dashed accent ring and a "?" badge. To correct one, tap the sticker, then a color.
  - Invalid pieces are outlined in red, with plain labels ("This corner shows two whites."). Errors that don't belong to one piece get their own lines.
  - Accept stays disabled until every uncertain sticker is resolved and the state is valid.
  - Glyphs come from `StickerAppearance`.
  - At 200% font on a 600 dp two-pane layout, no word may break mid-word.
- **Solve tab:**
  - `CAMERA_SCAN` OFF: *Enter colors* is the only action.
  - BETA: *Enter colors* is primary, and scan carries the BETA label with the tooltip "May misread colors in poor light. Manual entry always works."
- **Physical mode:**
  - *Quick Check* (camera icon) is offered at Objective boundaries, Stage milestones and mismatch reports. It is never required, and *Done* always works.
  - Quick Check is the first rung of the mismatch ladder, above the existing rungs.
  - A match shows a ✓ *Verified* chip. A mismatch shows the SEEN strip with the differing stickers outlined.
  - With a Full observation, *Use my cube as it is now* starts a Guided recovery.
  - *Scan it* joins the Confirm-your-cube card.
  - A stale scan offers "Your cube changed while scanning — scan again?"
  - The first-solve milestone appears only after a verified Quick Check or *Yes, it's solved*.
- **Calibration:** reached from "Colors look off — calibrate?" (many uncertain stickers, or a first stickerless scan) or from Settings → Color Calibration. Each of the six centers is shown in turn for the Learner to confirm, then *Save*. Reset is separate. The Settings rows are hidden while `CAMERA_SCAN` is OFF.
- **Flag OFF:** hides every entry point, cross-link, string and rationale, leaving no dead UI. Existing Solve and physical-mode tests must pass unchanged.

## Cross-Story Dependencies

- **Order:** 10.9 → 10.10 → 10.1 → (10.6 in parallel with 10.2 → 10.3 → 10.4 → 10.5 → 10.7) → Epic 11 → 10.8 → 10.11.
- **10.9 first:** no CameraX code merges before it.
- **Device sessions:**
  - Session A: 10.10.
  - Session B: acceptance for 10.2 and 10.4.
  - Session C: 10.8, together with Epic 11 capture.
- **Issue #139** (phone cube size, tablet learning path) lands before 10.7, and camera stories rebase onto it.
- **Never blocks tester work:** Epic 10 never blocks tester-feedback fixes or Stories 12.3, 12.4, 12.5 and 13.6.
- **First flag raise:** no camera flag goes above OFF until those stories are done.
- **10.11 runs per release:** it runs at each release that raises a flag and needs 10.8's per-feature recommendation. It also touches the site repository's privacy page, Play Console Data safety and the store listing. The flag-raise PR is the last merge before the tag, and the site PR follows right after.
- **Epic 11** (Quick Scan, Placement, Case recognition) reuses this pipeline, the `ScanLauncher` port and the camera source set.
