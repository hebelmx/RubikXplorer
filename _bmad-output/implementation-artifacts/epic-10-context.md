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
- Story 10.14: Scan each face in any rotation

## Requirements & Constraints

- **Accuracy targets (provisional, measured by 10.8):** Guided Scan: at least 98% of scans valid after at most 3 corrections, at least 99.5% of stickers correct indoors, under 45 s for a repeat user. Quick Check: 0 false *verified*, a matching cube confirmed in at least 98% of checks, at most 2 captures. All modes: at most 0.1% of stickers both misread and marked confident.
- Every sticker gets a Sticker Confidence; uncertain stickers must be resolved before Accept. 54 recognized colors never count as valid by themselves: every Scan Result goes through the same validation as the Manual Cube Editor, and invalid states name the likely bad stickers ("This corner shows two whites.").
- Each face's rotation is worked out from adjacency; when it can't be, only the faces involved are re-prompted with a named top. The scan never guesses.
- Glare, blur, low light or an incomplete capture produce specific coaching and a recapture, never an error.
- The Solver is never called on an unvalidated Scan Result. Denied camera permission opens the Manual Cube Editor. Every scan flow has a manual fallback.
- Calibration classifies against the cube's own centers (stickerless and pastel cubes work), persists per device, and has its own reset. Standard color scheme only.
- **Privacy:** frames are processed in memory on the device, never stored, saved or sent; release logs contain no samples. Camera is the only runtime permission, asked at first scan after the rationale "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." All Learner-facing copy comes from content strings.
- No ML Kit or Play-services ML. Install size stays under 30 MB.
- Reference devices: Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (gating), Galaxy Tab S7 FE (reported). Test matrix: stickered and stickerless cubes × daylight, warm indoor, cool LED and low light.

## Technical Decisions

- **Recognition approach (OQ-7 decision K):** Kotlin nearest-center classification fed by a per-cell histogram-mode reader (central ROI, glare mask, Lab histogram mode), self-calibrated from the six face centers. Logo-center guard: a center whose mode share is below 60% is not used for calibration (fall back to a plain sticker or the other five centers). No new runtime, so the release classpath gate is unchanged. LiteRT is the fallback if field use shows K is not enough; OpenCV is rejected on size.
- **Confidence / prefill rule:** a sticker is CONFIDENT (and prefilled in review) only when its nearest calibrated color beats the second nearest by at least 10 ΔE. Close calls such as yellow vs white stay UNCERTAIN.
- **Recognition boundary:** only the CameraX adapter touches pixels, and it lives in the build-time camera source set `app/src/camera/`, never `app/src/main`. It emits `FaceSample`s (center color, 9 CIELAB samples in viewfinder order, quality flags, pose; `upNeighborColor` only when the prompt named a top). `StickerClassifier` takes calibration as a parameter. Only `ScanAssembler` in `:core:domain` rotates samples into URFDLB `Facelets`; for samples with no up neighbor it searches the four quarter turns of every face (centers fixed) for the state `CubeValidator` accepts, and reports the faces involved when zero or several distinct states fit. The result is `ScanResult.Full(Facelets + confidence)` or `Partial`. `CubeValidator` is the only route from `Facelets` to a `CubeState`.
- **Categorical only:** `StickerConfidence {CONFIDENT, UNCERTAIN}` and `Certainty {VERIFIED, LIKELY, UNKNOWN}`. No numeric confidence crosses a module boundary.
- **Observations:** a `Full` observation comes only through `CubeValidator`. A `Partial` is checked for internal consistency only, is never completed from `expected`, and must be completed by a Guided Scan or the Editor before `AdoptObserved`. Only `tutor/Assess` produces Certainty; VERIFIED needs coverage of every face touched since the last Full VERIFIED observation and exactly one consistent candidate.
- **Acceptance:** the reducer rejects `ScanAccepted` unless the Full result is valid, every UNCERTAIN sticker is confirmed or corrected, and `basedOnRevision` is current. The scan sub-flow returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result; only the caller dispatches it, and the flow never touches a session.
- **Calibration ownership:** only `CalibrationRepository`, written only by `UpdateCalibration` / `ResetCalibration`; never in `SessionState` or a Checkpoint. Room moves v7 → v8 with an exported schema, a migration and a row-preserving migration test.
- **Flags compose the build:** `FeatureFlags` maps `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` (among others) to OFF/BETA/GA per build type in `build-logic`. A camera flag above OFF adds the camera source set, the `CAMERA` permission and CameraX to that build type only; scan shortcuts require `CAMERA_SCAN` above OFF. `:app` main reaches the camera only through the `ScanLauncher` port (optional Hilt binding); with no binding every scan entry point is hidden. BETA shows through the one shared BETA label component, never direct `BuildConfig` checks.
- The debug-only fixture recorder writes `FaceSample` JSON, truth tagged `entered`/`prefilled`, and downscaled frames to `Fixtures/live/<device>_<cube>_<light>/`; it keeps the prompted top so ground truth has a known pose. No release or benchmark build contains it.

## UX & Interaction Patterns

- **Viewfinder:** auto-capture after about 600 ms aligned and steady, plus a manual shutter; AF/AE/AWB re-meter on the grid when alignment is found and on every face change. Fixed face order. The prompt names only the center color with its glyph swatch ("Show the **green** center"); "…with **white** on top" appears only on a re-prompt. A mini net advances. On a phone in portrait the viewfinder, prompt, net, palette and primary action fit on one screen without scrolling; on tablets the viewfinder sits left, net/palette/action right. Rare controls (override, recalibrate) sit apart and need a confirm.
- **Review net:** only confident stickers are prefilled; close calls stay blank (styled so they can't read as white) and pre-selected with a dashed accent ring and "?" badge. Tap a sticker, then a color, with auto-advance to the next blank or uncertain sticker. Invalid pieces are outlined in `status-invalid`; non-local errors (twisted corner, flipped edge, swapped pieces) get their own lines. Accept stays disabled until every uncertain sticker is resolved and the state is valid. No mid-word breaks at 200% font on the Lenovo's 600 dp two-pane width.
- **Calibration:** the six colors in turn, each sampled from the ring around the center's middle so a logo is excluded; a non-uniform ring or implausible swatch asks for a plain sticker in the middle square. The Learner confirms each swatch, then *Save*. Reached from "Colors look off — calibrate?", the first stickerless scan, or Settings (rows hidden while `CAMERA_SCAN` is OFF).
- **Solve tab:** with BETA, *Enter colors* is primary and scan carries the BETA label and tooltip "May misread colors in poor light. Manual entry always works." With OFF, unchanged from today. *Enter colors instead* is always visible.
- **Quick Check:** offered at Objective boundaries, Stage milestones and mismatches, never required (*Done* still works); first rung of the mismatch ladder. Match → ✓ *Verified* chip; mismatch → SEEN strip with differing stickers outlined. Stale result → discarded, with "Your cube changed while scanning — scan again?". An OFF feature shows no entry, string or rationale.

## Cross-Story Dependencies

- Order: 10.9 → 10.10 → UX pass → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.14 → 10.4 → 10.5 → 10.7) → Epic 11 → 10.8 → 10.11. Done: 10.9, 10.10, 10.12, 10.13, 10.1, 10.2. 10.3 is in progress.
- 10.3 is the safety net for any turn the 10.14 rotation search gets wrong, so it lands first. 10.14's re-prompt uses the 10.2 prompt with a named top; still-invalid states go to 10.3 review with no further loop. Per-face retake from review is a separate, future story.
- Quick Check (10.6/10.7) and Quick Scan's per-face hand-off (Epic 11) keep the prompted top: fewer than six faces can't be oriented from validity.
- Device sessions: B = 10.2 and 10.4 acceptance; B2 = 10.14; C = 10.8 and Epic 11 capture.
- Quick Check builds on the Story 8.7 / 12.8 / 12.10 physical-mode work without changing those tests; the first-solve milestone is recorded via Story 12.7.
- 10.11 needs 10.8's recommendation and Stories 12.3, 12.4, 12.5 and 13.6. Camera work never blocks tester-fix releases.
