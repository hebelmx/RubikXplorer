# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let a Learner point the camera at a real cube so the app knows its state instead of predicting it. The Learner scans face by face, corrects and calibrates the result, solves from it, and verifies physical Moves with a Quick Check. Everything runs on the device, and no scan result is used before it is validated. Every story merges to `main` with every camera flag OFF in release. The epic exits when the Guided Scan meets its accuracy targets on the test matrix, or `CAMERA_SCAN` ships as BETA. A camera feature reaches Learners only when the PO raises its flag for a release. Until then, releases stay as camera-free as v1.0, with no camera code, dependency or permission.

## Stories

- Story 10.1: Classify stickers and assemble a Scan Result
- Story 10.2: Guided 6-face Scan viewfinder
- Story 10.3: Review and correct a Scan Result
- Story 10.4: Calibrate colors for my cube
- Story 10.5: Scan my cube from the Solve tab
- Story 10.6: Quick Check evidence and Certainty
- Story 10.7: Quick Check in Physical-cube Mode and playback
- Story 10.8: Recognition test matrix and GA/BETA decision
- Story 10.9: Keep camera code out of a release whose camera flags are OFF
- Story 10.10: Live recognition baseline and fixture recorder
- Story 10.11: Raise a camera feature above OFF for a release
- Story 10.12: Fixture recorder a phone operator can finish quickly
- Story 10.13: Choose the recognition approach (OQ-7)
- Story 10.14: Scan each face in any rotation
- Story 10.15: Solve from a scan

## Requirements & Constraints

- **Never a false *verified*.** This rule outranks every example in the requirements.
- **Recognition targets** (provisional, measured on the test matrix with recorded ground truth):
  - Guided 6-face Scan: ≥ 99.5% of stickers read correctly under normal indoor light, ≥ 98% of scans valid after ≤ 3 corrections, < 45 s for a repeat user.
  - Quick Scan: GA only if ≥ 95% of scans are valid after ≤ 3 corrections (Guided Scan fallback allowed), < 15 s. Otherwise it ships as BETA.
  - Quick Check: 0 false *verified* in the scenario suite, ≥ 98% of matching cubes confirmed, ≤ 2 captures.
  - Case recognition: ≤ 1% wrong Case, counted only when a single Case is named. A result shows < 1 s after capture.
  - Confidently wrong (all modes): ≤ 0.1% of all stickers are both misread and marked *confident*.
- **Test matrix:** stickered and stickerless cubes × four lights (daylight, warm indoor, cool LED, low light). The Lenovo Tab M10 Plus 3rd Gen (minimum) and the Galaxy S24 FE gate. The Galaxy Tab S7 FE is reported only. Report accuracy per sticker and per scan, corrections, time and the confidently-wrong rate.
- **Known residual risk:** a constructed face-preserving sequence (e.g. `R U2 R'` with plan faces {B, D}) can make a Partial *match* falsely VERIFIED. This happened for 1 of 44 such sequences, out of 123,500 tried. The matrix report must measure this rate in the Quick Check scenario suite and give a recommendation. The PO then decides whether a mitigation is needed or BETA labeling is enough.
- **GA/BETA outcome:** if a target is missed, record a per-feature recommendation (OFF, BETA or GA) in the PRD memlog. Every scan flow must have a working manual fallback. The release level is set only when a flag is raised for a release, never by the measurement story.
- **Quick Check rule:** a Partial verifies only a match. A Partial mismatch is at most *likely* and offers a Guided 6-face Scan. Only a Full observation verifies a mismatch.
- **Calibration offer:** the review offers calibration only on uncertain reads (NeedsCalibration, a guarded center with no saved calibration, or ≥ 6 blank stickers). Settings → Color calibration is always available.
- **Privacy and offline:** there is no network permission, and the camera is the only runtime permission. Frames stay in memory and are never stored, saved as photos or sent. Release logs contain no samples. No ML Kit or Play-services ML is allowed. The install stays under 30 MB.
- **Code:** pure-Kotlin domain with ≥ 80% coverage. All Learner-facing text comes from content strings.

## Technical Decisions

- **Recognition boundary:** only the CameraX adapter in the build-time camera source set `app/src/camera/` touches pixels. It emits `FaceSample`s. `:app` main code reaches the camera only through the `ScanLauncher` port, which has an optional Hilt binding. With no binding, every scan entry point is hidden.
- **Detection method (OQ-7 decision K):** Kotlin nearest-center classification with a per-cell histogram-mode reader, self-calibrated from the six centers. A center whose mode share is below 60% (a logo) is not used for calibration. No new runtime is added. LiteRT is the fallback, and OpenCV was rejected on size.
- **Assembly:** `ScanAssembler` is the only place samples are rotated. It tries the quarter turns of every face and keeps the states `CubeValidator` accepts. When the result is ambiguous it re-prompts with a named top, and it never guesses. Only categorical confidence crosses a module boundary: `StickerConfidence {CONFIDENT, UNCERTAIN}` and `Certainty {VERIFIED, LIKELY, UNKNOWN}`.
- **Scan flow:** returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}`, and only the caller dispatches it. `ScanAccepted` needs a Full, valid result with every uncertain sticker resolved and a current revision. It is an undo barrier.
- **Observations:** `PhysicalObservation = Full | Partial`. A Partial is never completed from `expected`. Only `tutor/Assess` produces Certainty, through one `CandidateEnumerator`. Stale async results are discarded.
- **Calibration:** `CalibrationRepository` (Room v8) is the only owner. It is never stored in `SessionState` or a Checkpoint.
- **Flags compose the build:** `FeatureFlagLevels.byBuildType` is the only source of flag levels. A camera flag above OFF adds the camera source set, the CameraX dependencies and `CAMERA` to that build type only. `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` need `CAMERA_SCAN` above OFF. Debug raises a flag only once its entry point is wired in the main app. One shared BETA label component is used.
- **Main-app rule:** a feature is accepted on the main app build from its real entry point. Debug-only source sets hold developer tools only, such as the fixture recorder, which writes to `Fixtures/live/<device>_<cube>_<light>/`.
- **Raising a flag:** the release-prep PR regenerates `docs/privacy-facts.json` and keeps the size gate. It is the last PR merged before the tag. The `cubentia-site` privacy page update and the Play Data safety re-confirmation follow it.

## UX & Interaction Patterns

- **Viewfinder:** auto-capture after ~600 ms steady, with a manual shutter. The prompt names only the center color with a glyph swatch, and nothing scrolls on a phone in portrait. Glare, blur and low light get specific coaching, never an error.
- **Review:** only confident stickers are prefilled. Uncertain stickers are pre-selected and blank. A correction is tap a sticker, then a color. *Accept* is disabled until the state is valid.
- **Certainty chip:** ✓ *Verified*, ≈ *Likely* or ? *Unknown*, never a percentage. A mismatch shows the SEEN strip, and a Partial is never drawn as a whole cube.
- **Flags:** a flag that is OFF hides every entry point, chip and rung. The BETA tooltip reads "May misread colors in poor light. Manual entry always works."

## Cross-Story Dependencies

- **Order:** 10.9 → 10.10 → 10.12 → 10.13 → 10.1 → 10.2 → 10.3 → 10.14 → 10.4 → 10.5 → 10.15, with 10.6 → 10.7 → Epic 11 → 10.8 → 10.11.
- **10.8** covers the Guided Scan, the Quick Check and every built Epic 11 feature: Quick Scan from two corners, Placement, and Case recognition from two opposite top corners. It runs in device session C, a human gate on the physical devices with both test cubes in all four lights.
- **10.11** needs 10.8's recommendation for each raised feature, and Stories 12.3, 12.4, 12.5 and 13.6. It runs once for each release that raises a camera flag.
