# Epic 10 Context: Scan my cube

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera: scan it face by face, correct and calibrate colors, solve straight from the scan, verify physical Moves with a Quick Check, and use Quick Scan (two corner photos) as a faster path. The epic is v1.0 scope behind feature flags: camera code merges to `main` with release flags OFF, and a feature reaches users only when the PO raises its flag at a release from measured accuracy. The remaining work makes Quick Scan reliable: capture promptly, read shaded side faces without ever being confidently wrong, and gather the labelled evidence for its BETA call. Every flow keeps a manual fallback.

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
- Story 10.16: Retake faces from the scan review
- Story 10.17: Switch from Quick Scan to the Guided Scan and keep the captured faces
- Story 10.18: Quick Scan finds the cube's real edges and corners
- Story 10.19: Quick Scan reads the side faces in good light
- Story 10.20: Record 10.19-pipeline corner fixtures and check the corner model
- Story 10.21: Quick Scan keeps the side faces it reads well
- Story 10.22: Quick Scan captures promptly once the cube is in view
- Story 10.23: A shadow sticker reader runs beside the Kotlin reads

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or tutor. An uncertain sticker stays blank for the Learner; it is never turned into a suggested color. A kept Quick Scan face never carries a confidently wrong sticker, including under a calibration from another cube, light or torch state (result must be uncertainty or fallback).
- **Quick Scan targets.** GA needs ≥ 95% of scans valid after ≤ 3 corrections in < 15 s (Guided fallback allowed), else BETA. Side-face bar, enforced per device, light and torch state and never pooled: 0 confidently wrong stickers, 0 Red↔Orange confusions, ≥ 90% side stickers correct, ≥ 3 of 4 side faces kept. Backlit captures are reported, not scored.
- **Prompt capture.** Every corner hold auto-captures within 10 s of the cube being detected, still or hand-held; a miss is recorded with its blocking gate and routed to the PO.
- **Corner model.** Held-out real captures per device: recall ≥ 92%, rejection ≥ 96%, mean corner error ≤ 3% of the outline diagonal; median inference < 40 ms off the main thread on the minimum device.
- **Devices and cubes.** Lenovo Tab M10 Plus 3rd Gen (TB128XU, minimum spec, no flash) and Galaxy S24 FE (flash) gate acceptance, with stickered, stickerless and glossy cubes, front-lit and backlit.
- **Privacy and size.** Frames and face crops stay in memory and are discarded when the scan ends; nothing is stored, sent or logged in release. `CAMERA` is the only runtime permission (the torch needs none). Release install stays < 30 MB.
- **Acceptance.** Camera stories are accepted on the main app build from their real entry point, with the flag raised in debug; a debug-only launcher is never the gate. Learner text lives in `en.json`. Device evidence goes to `docs/evidence/<story>/`.

## Technical Decisions

- **Camera isolation.** Only the camera adapter (`app/src/camera/`) touches pixels and camera control (metering, torch). Results cross the port as categorical values only (a color plus `StickerConfidence`).
- **Hybrid recognition.** Guided 6-face Scan and all deciding sticker classification stay pure Kotlin (histogram-mode cell reader, nearest-center classifier, self-calibration from face centers). Quick Scan corner detection uses a small in-house quantized 7-keypoint LiteRT model behind `CornerDetector`; faces are unwarped from detected corners, never from a fixed guide. OpenCV stays rejected on size.
- **Flag-composed builds.** Camera code and `CAMERA` compose only when a camera flag is above OFF; LiteRT and the corner model only when `QUICK_SCAN` or `CASE_RECOGNITION` is above OFF (zero LiteRT bytes otherwise).
- **Exposure and steadiness (10.22).** Metering is on the detected cube. Once converged, exposure stays locked until capture or a re-meter trigger (cube lost, torch change, outline leaving the metered region, brightness shift beyond a bound); focus stays continuous; hold 2 carries hold 1's white balance. "Settled" means exposure time × ISO stable over a window, never the AE state alone. The steady window restarts on outline movement (largest corner displacement over the outline diagonal); color comparison only detects a different face. Turn and pose checks must not rely on a logo centre. Debug builds log every hold state change with timings to `CubentiaScanDiag`.
- **Light check is advisory (from 10.22).** It only shows and hides coaching; it no longer blocks auto-capture or the manual shutter. Story 10.21 refits its cuts per mode with hysteresis, skips it only while the torch is actually lit, and the PO decides from backlit captures whether it returns to blocking.
- **Calibration and keep rule.** Quick Scan uses a saved calibration only when its recorded torch state matches the current one and every captured face centre reads CONFIDENT as its own color; otherwise it self-calibrates. Each face is also corrected against its own centre. Until 10.21, a face is kept only with all 9 stickers CONFIDENT; 10.21 allows one blank with a matching calibration and tunes the agreement gate between the self-calibrated and calibrated reads. Stories 10.22 and 10.23 change neither.
- **Shadow sticker reader (10.23).** LiteRT is now allowed for per-face sticker color classification, in shadow mode only: the reader and its model (< 500 KB, image-only input) live in the debug camera source set, run once per capture off the main thread, log beside both Kotlin reads, and have no input to classification, the keep rule, assembly or review. A debug-only runtime toggle switches it. Release and benchmark builds carry no model bytes or reader class, enforced by a build check. Letting the model influence a result needs a further change proposal.
- **Fixtures.** Labelled corner captures live under `Fixtures/corner/`, hand-labelled (detector points are never labels), tagged with pipeline, device, cube id, light direction and torch state. Whole capture groups are held out and never used for tuning; training pipelines are seeded and reproducible.

## UX & Interaction Patterns

- **Quick Scan capture.** Minimal 3-line Y anchor plus the smoothed detected outline; no hexagon sketch. Each capture gives a visible cue (subtle flash, corner thumbnail, enlarged counter) and the same shutter sound; nothing relies on haptics alone. "Try a plain, light surface." appears after about 3 s without detection.
- **Light coaching.** "Turn so the light shines on the cube." shows after about 1 s of a failing light check and hides when it passes, without blocking capture.
- **Torch.** On a device with a flash the torch is on by default in the corner view and on the calibration screen, with a visible toggle; the choice carries from hold 1 to hold 2 and the torch turns off on hand-over, on leaving and in the background. No torch control or copy on a device without a flash.
- **Hand-over and review.** *Scan face by face* is always visible and keeps confidently captured faces; unreadable faces go to the Guided grid for that face only. In review, blank stickers are pre-selected, *Accept* enables only once valid, and *Enter colors instead* is always available.

## Cross-Story Dependencies

- **Order of remaining work:** 10.19 (done) → 10.22 → (10.20 ∥ 10.23) → 10.21. Stories 10.1–10.10 and 10.12–10.19 are done.
- **10.22 before fixtures:** 10.20 records under 10.22's exposure handling, and 10.21's refit uses those captures.
- **10.20 → 10.21:** 10.20 supplies the tuning and held-out splits and the unenforced baseline; 10.21 enforces the bar on the held-out split and yields the PO's BETA call.
- **10.23:** needs 10.22's diagnostics and capture pipeline; blocks nothing. It adds a report on 10.20's held-out groups once they exist.
- **10.11:** not blocked by 10.20, 10.21 or 10.23. Its first run raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF; a later run raises `QUICK_SCAN` only after 10.21's BETA call, at the level the recognition matrix (10.8) supports.
- **Epic 11:** Quick Scan itself (two-corner capture) comes from Story 11.1, which these stories build on.
