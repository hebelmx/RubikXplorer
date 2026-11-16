# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can scan a real cube face by face, correct and calibrate the read, solve it straight from the scan, and verify physical Moves with a Quick Check, so the tutor works from evidence instead of guesses. This is the v1.1 camera work: everything merges to `main` with every release camera flag OFF, so tester-fix releases stay as camera-free as v1.0. A feature reaches Learners only when the PO raises its flag for a release. The epic exits when the Guided Scan meets its accuracy targets on the test matrix, or `CAMERA_SCAN` ships as BETA.

## Stories

- Story 10.1: Classify stickers and assemble a Scan Result
- Story 10.2: Guided 6-face Scan viewfinder
- Story 10.3: Review and correct a Scan Result
- Story 10.4: Calibrate colors for my cube
- Story 10.5: Scan to solve
- Story 10.6: Quick Check evidence and Certainty
- Story 10.7: Quick Check in Physical-cube Mode and playback
- Story 10.8: Recognition test matrix and GA/BETA decision
- Story 10.9: Keep camera code out of a release whose camera flags are OFF
- Story 10.10: Live recognition baseline and fixture recorder
- Story 10.11: Raise a camera feature above OFF for a release
- Story 10.12: Fixture recorder a phone operator can finish quickly
- Story 10.13: Choose the recognition approach (OQ-7)

## Requirements & Constraints

- **Accuracy targets (provisional, measured by 10.8):** Guided Scan: at least 98% of scans valid after at most 3 corrections, at least 99.5% of stickers correct in normal indoor light, under 45 s for a repeat user. Quick Check: 0 false *verified* in the scenario suite, a matching cube confirmed in at least 98% of checks, at most 2 captures. All modes: at most 0.1% of stickers both misread and marked confident.
- Every sticker gets a Sticker Confidence. Uncertain stickers must be resolved before Accept. 54 recognized colors never count as valid by themselves: every Scan Result goes through the same validation as the Manual Cube Editor, and invalid states name the likely bad stickers ("This corner shows two whites.").
- Wrong orientation, glare, blur, low light or an incomplete capture produce specific coaching and a recapture, never an error.
- The Solver is never called on an unvalidated Scan Result. Denied camera permission opens the Manual Cube Editor. Every scan flow has a manual fallback.
- Calibration classifies against the cube's own centers (stickerless and pastel cubes work), persists per device, and can be reset separately. Standard color scheme only.
- **Privacy:** frames are processed in memory on the device, never stored, saved as photos or sent. Release logs contain no samples. The camera permission is asked only at first scan, after a rationale ("Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent.").
- No ML Kit or Play-services ML. Install size stays under 30 MB (`:app:checkReleaseSize`).
- Reference devices: Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (gating), Galaxy Tab S7 FE (reported). Test matrix: stickered and stickerless cubes × daylight, warm indoor, cool LED and low light.

## Technical Decisions

- **Recognition approach (OQ-7 decision K, PO 2026-11-15):** Kotlin nearest-center classification, fed by a per-cell histogram-mode reader (central ~60% ROI, glare mask, 3-D Lab histogram, mode of the peak bin and its neighbors), self-calibrated from the six face centers. It is pure logic in `:core:domain`. The camera adapter converts YUV to Lab. No new runtime, so the 10.9 classpath gate is unchanged. LiteRT is the fallback if field use shows K is not enough. OpenCV is rejected on size.
  - **Confidence rule:** a sticker is CONFIDENT only when its nearest calibrated center beats the second nearest by at least 10 ΔE (CIE76). The same threshold decides review prefill.
  - **Logo-center guard:** a center whose histogram-mode share is below 60% is not used as a calibration center. Instead, prompt for a plain sticker (10.4 flow) or calibrate that color from the stickers the other centers classify. This guard was not measured in the spike.
  - 10.1 must re-run the committed fixtures through the all-Kotlin reader, because the spike used OpenCV for decode and Lab conversion. Session B re-measures on live YUV frames.
- **Recognition boundary:** only the CameraX adapter touches pixels, and it lives in the build-time camera source set `app/src/camera/`, never `app/src/main`. It emits `FaceSample`s (center and up-neighbor color, 9 CIELAB samples in viewfinder order, quality flags, pose). Only `ScanAssembler` rotates samples into URFDLB `Facelets` order. `StickerClassifier` takes calibration as a parameter. The result is `ScanResult.Full(Facelets + confidence)` or `Partial`. Extend the existing `dev.cubentia.domain.recognition` types; don't duplicate them.
- **Categorical only:** `StickerConfidence {CONFIDENT, UNCERTAIN}` and `Certainty {VERIFIED, LIKELY, UNKNOWN}`. No numeric confidence crosses a module boundary.
- **Observations:** a `Full` observation is produced only through `CubeValidator`. A `Partial` is checked for internal consistency only, is never completed from `expected`, and must be completed by a Guided Scan or the Editor before `AdoptObserved`. Only `tutor/Assess` produces Certainty. VERIFIED requires the observed faces to cover every face touched since the last Full VERIFIED observation, and exactly one consistent candidate.
- **Acceptance:** the reducer rejects `ScanAccepted` unless the Full result is valid, every UNCERTAIN sticker is confirmed or corrected, and `basedOnRevision` is current. The scan sub-flow returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result, and only the caller dispatches it. The flow never touches a session.
- **Calibration ownership:** only `CalibrationRepository` owns it, written only by `UpdateCalibration` and `ResetCalibration`. It is never in `SessionState` or a Checkpoint. Room moves from v7 to v8 with an exported schema, a migration and a row-preserving migration test.
- **Flags compose the build:** `FeatureFlags` maps `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` (among others) to OFF/BETA/GA per build type in `build-logic`. A camera flag above OFF adds the camera source set, the `CAMERA` permission and the CameraX dependencies to that build type only. Scan shortcuts above OFF require `CAMERA_SCAN` above OFF. `:app` main code reaches the camera only through the `ScanLauncher` port (an optional Hilt binding). With no binding, every scan entry point is hidden. The UI shows BETA through the one shared BETA label component, never through direct `BuildConfig` checks.
- The debug-only fixture recorder writes `FaceSample` JSON, truth tagged `entered`/`prefilled`, and downscaled frames to `Fixtures/live/<device>_<cube>_<light>/`. No release or benchmark build contains it.

## UX & Interaction Patterns

- **Viewfinder:** auto-capture after about 600 ms aligned and steady, plus a manual shutter. AF/AE/AWB re-meter on the grid when alignment is first found and on every face change. The prompt names the center and up colors with glyph swatches, and a mini net advances. On a phone in portrait, the viewfinder, prompt, net, palette and primary action fit on one screen without scrolling.
- **Review net:** only confident stickers are prefilled. Close calls (for example, a yellow near white) stay blank, styled so they can't be read as white, and are pre-selected. Entry is tap a sticker, then a color (at most two touches), with auto-advance to the next blank or uncertain sticker. Accept stays disabled until every uncertain sticker is resolved and the state is valid. No mid-word breaks at 200% font on the Lenovo's 600 dp two-pane width.
- **Calibration (Flow 9):** the six colors in turn, each sampled from the ring around the center's middle, so a logo is excluded. A non-uniform ring or an implausible swatch asks for a plain sticker in the middle square. The Learner confirms each swatch. It is reached from "Colors look off — calibrate?", the first stickerless scan, or Settings, and its Settings rows are hidden while `CAMERA_SCAN` is OFF.
- **Solve tab:** with BETA, *Enter colors* is primary and scan carries the shared BETA label and the tooltip "May misread colors in poor light. Manual entry always works." With OFF, it is unchanged from today. *Enter colors instead* is always visible.
- **Quick Check:** offered at Objective boundaries, Stage milestones and mismatches, never required (*Done* still works). It is the first rung of the mismatch ladder. A match shows a ✓ *Verified* chip. A mismatch shows the SEEN strip with differing stickers outlined. A stale result is discarded, with "Your cube changed while scanning — scan again?". An OFF feature shows no entry, string or rationale.

## Cross-Story Dependencies

- Order: 10.9 → 10.10 → UX pass → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.4 → 10.5 → 10.7) → Epic 11 → 10.8 → 10.11. Stories 10.9, 10.10, 10.12 and 10.13 are done.
- 10.1 feeds 10.2, 10.3 (its confidence rule is the review prefill rule), 10.4 and 10.6. 10.2 needs 10.9's source set and `ScanLauncher` port, and deletes the Story 1.9 `spikeScan` prototype when it closes.
- Human device sessions: B covers acceptance of 10.2 and 10.4. C covers 10.8 and Epic 11 capture.
- Quick Check builds on the Story 8.7 / 12.8 / 12.10 physical-mode work without changing those tests. The first-solve milestone is recorded via Story 12.7.
- Epic 11 (Quick Scan, Placement, Case recognition) builds within decision K and on this epic's pipeline. 10.11 needs 10.8's recommendation and Stories 12.3, 12.4, 12.5 and 13.6. Camera work never blocks tester-fix releases.
