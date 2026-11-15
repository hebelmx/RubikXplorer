# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can scan a physical cube face by face, correct and calibrate the read, solve from the scan, and verify physical Moves with a Quick Check, so the tutor works from evidence instead of predictions. This is v1.1, flag-gated work: every story merges to `main` with all camera flags OFF in release, and a feature reaches users only when the PO raises its flag for a release. The epic exits when the Guided Scan meets its accuracy targets on the test matrix, or `CAMERA_SCAN` ships as BETA. After the 2026-11-15 re-plan, `CAMERA_SCAN` is BETA-only. The live baseline met its gate only with careful operator calibration, so the fixture recorder is reworked (10.12) and the recognition approach is re-decided (10.13) before the product classifier is built.

## Stories

- Story 10.1: Classify stickers and assemble a Scan Result
- Story 10.2: Guided 6-face Scan viewfinder
- Story 10.3: Review and correct a Scan Result
- Story 10.4: Calibrate colors for my cube
- Story 10.5: Scan to solve
- Story 10.6: Quick Check evidence and Certainty
- Story 10.7: Quick Check in Physical-cube Mode and playback
- Story 10.8: Recognition test matrix and GA/BETA decision
- Story 10.9: Keep camera code out of a release whose camera flags are OFF (done)
- Story 10.10: Live recognition baseline and fixture recorder (done)
- Story 10.11: Raise a camera feature above OFF for a release
- Story 10.12: Fixture recorder a phone operator can finish quickly
- Story 10.13: Choose the recognition approach (OQ-7)

## Requirements & Constraints

- **Accuracy targets (provisional):**
  - Guided Scan: ≥ 99.5% of stickers read correctly in normal indoor light, and ≥ 98% of scans valid after ≤ 3 corrections, in < 45 s for a repeat user.
  - Quick Check: 0 false *verified* in the scenario suite, a matching cube confirmed in ≥ 98% of checks, ≤ 2 captures.
  - All modes: ≤ 0.1% of stickers both misread and marked confident.
- **Test matrix:** stickered and stickerless cubes × daylight, warm indoor, cool LED and low light. Devices: Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (gating), Galaxy Tab S7 FE (reported only).
- **Validation:** a Scan Result is never used before it passes cube validation. 54 recognized colors are not valid on their own. Invalid states name the likely bad stickers.
- **Color Calibration:** stickers are classified against the cube's own calibrated colors. Calibration persists per device and has its own reset. Only the standard color scheme is supported.
- **Privacy and offline:**
  - Frames are processed in memory only, never saved or sent. Release logs never contain samples or Cube States.
  - The camera permission is requested on the first scan, after a rationale.
  - Every scan feature has a manual fallback (*Enter colors instead*).
  - No ML Kit or Play-services ML.
- **Size:** install size stays < 30 MB, including any recognition model. Size cost is part of the OQ-7 decision.
- **Quality:** recognition classification is pure Kotlin in `:core:domain`, with ≥ 80% coverage. The JVM fixture tests run in CI.
- **Re-plan success criteria (recorder and session A2):**
  - Four cubes (`stla`, `stka`, `stlb`, `stkb`) on both gating devices in about 30–45 min per device.
  - No scrolling on the S24 FE.
  - No mis-tapped quality flags.
  - About ≤ 2 touches per sticker on average.

## Technical Decisions

- **Camera isolation:**
  - CameraX lives only in the build-time camera source set `app/src/camera/`, which is composed into a build type only when one of its camera flags (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`) is above OFF.
  - With all camera flags OFF, release and benchmark have no `CAMERA` permission, no `androidx.camera:*` and no ML/CV runtime (`org.opencv*`, LiteRT, `org.tensorflow*`, `com.google.mlkit:*`). CI enforces this.
  - Main code reaches the camera only through the `ScanLauncher` port (an optional Hilt binding). With no binding, every scan entry point is hidden.
- **Recognition pipeline:**
  - The adapter emits neutral `FaceSample`s: center color, up-neighbor color, 9 CIELAB samples in viewfinder order, quality flags and pose.
  - `ScanAssembler` (in `:core:domain`) alone rotates samples into facelet order.
  - `StickerClassifier` takes the calibration as a parameter.
  - The output is `ScanResult.Full(Facelets + StickerConfidence)` or `Partial`.
  - Only categorical confidence (`CONFIDENT`/`UNCERTAIN`) crosses a module boundary.
  - Extend the existing `dev.cubentia.domain.recognition` types; don't duplicate them.
- **Acceptance:** the reducer rejects `ScanAccepted` unless the `Full` result is valid, every `UNCERTAIN` sticker is resolved and `basedOnRevision` is current.
- **Scan flow:** the scan flow is a sub-flow that returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result. Only the caller dispatches it, and the flow never touches a session.
- **Observations and Certainty:**
  - A `Partial` observation is checked for internal consistency only. It is never completed from `expected` and must be completed by a Guided Scan or the Editor before `AdoptObserved`.
  - Only `Assess` produces Certainty. VERIFIED requires full coverage of the touched faces and exactly one consistent candidate.
- **Calibration storage:** `CalibrationRepository` is the only owner, written only by `UpdateCalibration` and `ResetCalibration`. Calibration is never stored in `SessionState` or a Checkpoint. Adding it is a Room version 7→8 migration with an exported schema and a test that preserves existing rows.
- **Fixture recorder (debug only):**
  - It writes `FaceSample` JSON with ground truth to `Fixtures/live/<device>_<cube>_<light>/`.
  - From 10.12, it also writes a downscaled JPEG per face (long edge ≤ 1024 px), and each sticker's truth is tagged `entered` or `prefilled`.
  - No release or benchmark build contains the recorder or writes frames.
- **OQ-7 is re-opened:** Kotlin nearest-center, OpenCV and on-device ML (LiteRT or equivalent) are compared by 10.13 in a non-shipping evaluation source set. 10.1's classifier follows that decision. If the chosen approach adds a runtime, 10.13 changes the release classpath gate.
- **Flags:** use the `FeatureFlags` port with levels `OFF`/`BETA`/`GA`, and the shared BETA label component (the *Test me* one). Never check `BuildConfig` directly.

## UX & Interaction Patterns

- **Scan layout never scrolls:**
  - On a phone in portrait, the viewfinder takes about the top 45% (grid, face prompt, 6-face progress strip). Below it sit the current face's 3×3 net beside a 2×3 palette with glyphs (W Y R O G B), then one primary action.
  - On a tablet (600 dp), the viewfinder is on the left and the net, palette and action are on the right.
- **Rare or destructive controls:** the quality override, *Reset labels* and *Recalibrate* sit apart from the net and palette and need a confirm. An active override shows on the primary action.
- **Viewfinder:**
  - It captures automatically after about 600 ms aligned and steady, with a manual shutter as backup.
  - Focus, exposure and white balance re-meter on the grid when alignment is first found and on every face change.
  - The prompt names the center and up colors with a glyph swatch.
  - Coaching is inline ("Too much glare — tilt the cube"), never an error.
- **Color entry and prefill:**
  - Tap a sticker, then a color. Selection auto-advances to the next blank or uncertain sticker. No tap-to-cycle.
  - A sticker is prefilled only when its nearest calibrated color beats the second nearest by ≥ 10 ΔE (the close-call rule; 10.13 may retune it). Close calls stay blank and pre-selected.
  - Accept stays disabled until every uncertain sticker is resolved and the state is valid.
- **Calibration:**
  - Each of the six colors is shown in turn, sampled from the ring around the center sticker's middle, so a logo is excluded.
  - If the ring isn't one even color, or the swatch is implausible for its color, ask for a plain sticker of that color in the middle square.
  - Show the swatch before saving.
- **Permission:** a rationale card comes before the system dialog, with *Continue* / *Enter colors instead* and copy from content strings. If permission is denied from Solve, the Editor opens directly.
- **Flag-gated UI:**
  - When a flag is OFF, its feature has no entry point, string or rationale.
  - When `CAMERA_SCAN` is BETA, *Enter colors* is primary, and the tooltip reads "May misread colors in poor light. Manual entry always works."
- **Accessibility:** scan review has no mid-word breaks at 200% font on the Lenovo's 600 dp two-pane width.

## Cross-Story Dependencies

- **Order:** 10.9 → 10.10 → UX pass (done) → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.4 → 10.5 → 10.7) → Epic 11 → 10.8 → 10.11.
- **Device sessions:**
  - A2: 10.12's before-merge gate, both gating devices, four cubes, studio light.
  - B: acceptance for 10.2 and 10.4.
  - C: 10.8, along with 11.1 and 11.3 capture.
- **10.13** needs the A2 frames from 10.12. The PO records the OQ-7 decision, which also informs Quick Scan detection in 11.1.
- **10.1's confidence threshold** is the prefill rule that 10.3 review uses, and the recorder (10.12) shares the same interaction model.
- **10.2** deletes the Story 1.9 prototype (`app/src/spikeScan/` and its switch) and binds CameraX off the main thread.
- **10.7** inserts Quick Check as the first mismatch-ladder rung above the existing Story 12.8 rungs without changing their tests. Story 8.7 / 12.10 tests must pass unchanged with the flag OFF.
- **10.11** runs per release, after 10.8's recommendation and Stories 12.3–12.5 and 13.6. It updates the privacy facts, the site privacy page (`hebelmx/cubentia-site`) and Play Console Data safety.
- **Never blocking:** camera stories never block tester-feedback fixes or Stories 12.3–12.5 and 13.6.
