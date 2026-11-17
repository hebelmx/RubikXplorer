# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let a Learner point the camera at a real cube and have the app know its state, instead of predicting it. The Learner scans face by face, corrects and calibrates the result, solves from it, and verifies physical Moves with a Quick Check. All of this runs on the device and is never used before it passes validation. Every story merges to `main` with every camera flag OFF in release. A camera feature reaches users only when the PO raises its flag for a release. Until then, releases stay as camera-free as v1.0, with no camera code, dependency or permission.

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

- **Guided 6-face Scan:** a live viewfinder with face prompts, automatic capture when the cube is aligned and steady (plus a manual shutter), and a Sticker Confidence for every sticker. Each face's rotation is worked out from adjacency. When it cannot be, the faces involved are re-prompted with a named top.
- **Review:** the Learner can correct any sticker. *Accept* works only once the state is valid and every uncertain sticker is resolved. 54 recognized colors are never valid on their own. An invalid state names the likely bad stickers. Low light, glare or blur gets specific coaching and a recapture, never an error.
- **Calibration:** the app reads the six centers and classifies stickers against the cube's own colors. Calibration persists per device and has its own reset. The review offers calibration only on uncertain reads; Settings → Color calibration is always available.
- **Scan to solve:** scan → review → validate → solve → playback, without leaving the flow. The Solver never runs on an unvalidated Scan Result. If camera permission is denied, the Manual Cube Editor opens.
- **Quick Check:** a scan of only the faces the latest Moves touched (≤ 2 captures), or a full scan. A match is recorded as *verified*. A mismatch becomes Diagnosis evidence. **A Quick Check of only some faces verifies only a match. Only a Guided 6-face Scan verifies a mismatch.** A Partial mismatch is *likely* and offers a Guided 6-face Scan. Quick Check is offered at Objective boundaries, Stage milestones and after mismatch reports. It is never required, and *Done* always works. It is also available at any step of physical Solution Playback.
- **Never a false *verified*** (this outranks every example in the requirements). The recognition targets are provisional:
  - Guided Scan: ≥ 99.5% of stickers read correctly, and ≥ 98% of scans valid after ≤ 3 corrections, in < 45 s.
  - Quick Check: 0 false *verified* in the scenario suite, and ≥ 98% of matching cubes confirmed.
  - Confidently wrong: ≤ 0.1% of stickers.
- **Known residual risk:** a constructed face-preserving sequence (e.g. `R U2 R'` with plan faces {B, D}) can make a Partial *match* falsely VERIFIED. This happened for 1 of 44 such sequences, out of 123,500 tried. Story 10.8 must measure this rate and report it.
- **Test matrix:** stickered and stickerless cubes × four lights (daylight, warm indoor, cool LED, low light). The Lenovo Tab M10 Plus 3rd Gen (minimum) and the Galaxy S24 FE gate; the Galaxy Tab S7 FE is reported only.
- **Privacy and offline:** no network permission. The camera is the only runtime permission. Frames stay in memory and are never persisted or sent. Release logs contain no samples. No ML Kit or Play-services ML. The install must stay under 30 MB.
- **Code:** pure-Kotlin domain code with ≥ 80% coverage. All Learner-facing text lives in content strings.

## Technical Decisions

- **Recognition boundary:**
  - Only the CameraX adapter touches pixels. It lives in the build-time camera source set `app/src/camera/`.
  - It emits `FaceSample`s: the center color, 9 CIELAB samples in viewfinder order, quality flags and the pose. A sample has no up-neighbor unless the prompt named a top.
  - `:app` main code reaches the camera only through the `ScanLauncher` port, with an optional Hilt binding. With no binding, every scan entry point is hidden. A debuggable app with a flag above OFF and no binding fails at startup.
- **Detection method (OQ-7 decision K):** Kotlin nearest-center classification with a per-cell histogram-mode reader, self-calibrated from the six centers. No new runtime is added. LiteRT is the fallback, and OpenCV is rejected on size.
- **Assembly and classification:**
  - `ScanAssembler` in `:core:domain` is the only place samples are rotated. It tries the quarter turns of every face and keeps the states `CubeValidator` accepts. It never guesses.
  - `StickerClassifier` receives the calibration as a parameter.
  - The result is `ScanResult = Full(Facelets + confidence) | Partial`.
  - Only categorical confidence crosses a module boundary: `StickerConfidence {CONFIDENT, UNCERTAIN}` and `Certainty {VERIFIED, LIKELY, UNKNOWN}`.
- **Scan flow:** a sub-flow that returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result. Only the caller dispatches it, and the flow never touches a session. The reducer rejects `ScanAccepted` unless all of these hold:
  - the result is Full and valid;
  - every uncertain sticker is resolved;
  - `basedOnRevision` is current.
- **Observations and Certainty:**
  - `PhysicalObservation = Full(state) | Partial(faces, pose)`. A Partial is checked for internal consistency only. It is never completed from `expected` and never becomes a `CubeState`. `AdoptObserved` needs a Full, so a Partial must first be completed by a Guided Scan or the Editor.
  - Only `tutor/Assess` produces Certainty, through one `CandidateEnumerator(expected, maxDepth, moveSet)`.
  - VERIFIED requires all three of these:
    - the observed faces cover every face any Move since the last Full verified observation could touch;
    - exactly one candidate is consistent;
    - for a Partial, that candidate is `[]`.
  - A Partial mismatch with one consistent candidate is at most LIKELY. It still moves Sync State to `PHYSICAL_MISMATCH`, keeps the candidate as the leading hypothesis and offers a Guided 6-face Scan.
- **Session rules:**
  - Async results carry `effectId` and `revision`. A stale result is discarded.
  - Any `PhysicalConfirmed` marks the prior `observed` as stale.
  - `ScanAccepted` is an undo barrier.
  - The information-request order is Quick Check → which Move → one face → full re-entry. Quick Check sits above the existing mismatch-ladder rungs without changing their tests.
- **Calibration:** `CalibrationRepository` is the only owner. It is written only by `UpdateCalibration` and `ResetCalibration`, and never stored in `SessionState` or a Checkpoint. It lives in Room v8, with an exported schema and a 7→8 migration test.
- **Flags compose the build:**
  - `FeatureFlagLevels.byBuildType` is the only source of flag levels.
  - A camera flag above OFF (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`) adds the camera source set, the CameraX dependencies, `CAMERA` and `uses-feature camera.any required=false` to that build type only.
  - `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` need `CAMERA_SCAN` above OFF, or the build fails.
  - Debug raises a flag only once its entry point is wired in the main app. `CAMERA_SCAN` is GA in debug, and the other camera flags stay OFF until Epic 11.
  - One shared BETA label component (from *Test me*) is used, and no feature checks `BuildConfig`.
- **Main-app rule:** a camera story is accepted on the main app build, reached from its real entry point. Debug-only source sets hold developer tools only, such as the fixture recorder, which writes to `Fixtures/live/<device>_<cube>_<light>/`.

## UX & Interaction Patterns

- **Viewfinder:** a 3×3 grid at 70%, auto-capture after ~600 ms steady, and a prompt that names only the center color with a glyph swatch (the up color appears only on a re-prompt). On a phone in portrait, nothing scrolls.
- **Review net:** only confident stickers are prefilled. A sticker is confident when its nearest calibrated color beats the second by ≥ 10 ΔE. Uncertain stickers are pre-selected with a dashed ring and a "?". Correcting is tap a sticker, then a color. *Accept* stays disabled until the state is valid.
- **Physical controls:** **Done** stays primary. *Quick Check* is a camera icon.
- **Certainty chip:** ✓ *Verified* / ≈ *Likely* / ? *Unknown*, never a percentage, and tappable to explain its evidence. A VERIFIED frame shows until the next *Done*.
- **Mismatch display:** a mismatch shows the SEEN strip with the differing stickers outlined. A Partial is never drawn as a whole cube. The Likely mismatch shows the candidate plus a *Scan all 6 faces* action.
- **Copy:** use the fixed Certainty wording. The proposed Likely copy is "That turn probably moved the cross." It is still pending PO confirmation.
- **Adopt and stale scans:** *Use my cube as it is now* is the adopt action, and it needs a Full observation. A stale scan the Learner started re-offers "Your cube changed while scanning — scan again?"
- **Camera permission:** a rationale card shows before the first request: "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." If permission is denied from Solve, the Editor opens with a one-line note. Elsewhere, a card offers *Enter colors instead*, plus a Settings deep-link when permission is permanently denied.
- **Flags:** a flag that is OFF hides every entry point, chip and rung, with no dead buttons. The BETA tooltip reads "May misread colors in poor light. Manual entry always works."
- **Milestones:** the first-solve milestone in physical mode shows only after a Quick Check or *Yes, it's solved*. Stage 6's camera copy ("Check it with the camera") appears only together with the camera check action.

## Cross-Story Dependencies

- **Order:** 10.9 → 10.10 → UX pass → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.14 → 10.4 → 10.5 → 10.15 → 10.7) → Epic 11 → 10.8 → 10.11.
- **10.7** builds on 10.6's `Assess` rule and on Epic 8 and Epic 12:
  - Story 8.7: the Confirm-your-cube card, which gains *Scan it*.
  - Story 12.7: first-solve recording.
  - Story 12.8: the mismatch rungs.
  - Story 12.10: the flag-OFF tests, which must pass unchanged.
- **10.8** needs every built Epic 11 feature and reports the R1 residual risk. **10.11** needs 10.8's recommendation and Stories 12.3, 12.4, 12.5 and 13.6. It also updates the privacy facts and the `cubentia-site` privacy page.
- **Device sessions are human gates:**
  - A = 10.10
  - A2 = 10.12
  - B = 10.2 and 10.4 (10.4 only partly)
  - B2 = 10.14
  - B3 = 10.5 on the main app
  - C = 10.8
