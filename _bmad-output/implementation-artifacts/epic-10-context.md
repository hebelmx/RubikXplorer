# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera: scan it face by face, correct and calibrate colors, solve straight from the scan, verify physical Moves with a Quick Check, and use Quick Scan (two corner photos) as the faster path. The epic is v1.0 scope behind feature flags: camera code merges to `main` with release flags OFF, and a feature reaches users only when the PO raises its flag at a release from measured accuracy. The remaining work makes Quick Scan forgiving and its fixtures trustworthy: retake a bad corner, accept whichever corner is shown first, fill a few unread stickers from cube rules (after the BETA call), clean the corner fixture labels with a per-cube pastel plausibility check, and stop the fixture recorder from saving labels that cannot be a real cube. Every flow keeps a manual fallback and never produces a guessed state.

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
- Story 10.24: Retake a corner in Quick Scan
- Story 10.25: Quick Scan starts from whichever corner it is shown
- Story 10.26: Quick Scan fills a few unread stickers from the cube's rules
- Story 10.27: Clean the corner fixture labels and judge pastel stickers against the cube's own colours
- Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group
- Story 10.29: Solution Playback shows the same Quick Check result as Lessons

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or tutor. An uncertain sticker stays blank for the Learner. A kept Quick Scan face never carries a confidently wrong sticker. A wrongly named corner is a wrong cube. Cube rules may only rank or reject image-derived candidates, never invent a colour.
- **Quick Scan bar** (per device, light and torch state, never pooled, held-out groups only): 0 confidently wrong side stickers, 0 Red/Orange confusions on kept faces, at least 90% of side stickers correct, at least 3 of 4 side faces kept. Every corner hold auto-captures within 10 s of detection.
- **Fixture hygiene.** Corner fixture labels must give exactly 9 of each colour per group (centres included) and only legal pieces; CI re-checks this from labels alone. The recorder refuses a group that breaks the count unless the operator gives a non-empty override reason, saved to the manifest; overridden groups are skipped by tuning and shadow-reader scoring unless the PO includes them by name. Side-face pre-fills must each be tapped before a face can be confirmed. Held-out groups are never used for tuning; results cite the label-set commit and the `Fixtures/corner/label-exclusions.json` hash.
- **Pastel cubes** must pass after calibration without loosening the check for every cube.
- **Exit and review rules (v1.0 issue fixes).** Closing the Guided Scan viewfinder with no face captured leaves at once (`Cancelled`); with faces captured it asks "Discard the faces you captured?" (*Keep scanning* / *Discard*), the same for every caller and for system Back. Closing an unchanged scan review returns `FellBackToEditor` with its stickers; a changed review asks "Discard this scan?". Review names a sticker as a likely misread only when its measured Lab is within a 20 ΔE margin of an under-counted colour's centre versus its read colour (at most 3, never padded).
- **Devices and cubes.** Lenovo Tab M10 Plus 3rd Gen (TB128XU, minimum spec, no flash) and Galaxy S24 FE (flash) gate acceptance; 200% font on the Lenovo's 600 dp width must not cut or break words; phone portrait fits without scrolling.
- **Privacy and size.** Frames stay in memory and are never stored, sent or logged in release. `CAMERA` is the only runtime permission. Release install stays under 30 MB. No network, analytics, ML Kit or Play-services ML.
- **Acceptance.** Camera stories are accepted on the main app build from their real entry point with the flag raised in debug; a debug-only launcher is never the gate. Learner text lives in content strings. Device evidence goes to `docs/evidence/<story>/`.

## Technical Decisions

- **Camera isolation.** Only the camera source set (`app/src/camera/`) touches pixels and camera control; results cross the port as categorical values (a colour plus `StickerConfidence`). Main code reaches the camera only through an optional Hilt-bound port; no binding hides every scan entry point.
- **Hybrid recognition.** Deciding sticker classification stays pure Kotlin. Quick Scan corner detection uses an in-house LiteRT model behind `CornerDetector`, composed only when a corner-model flag is above OFF; OpenCV stays rejected. The shadow sticker reader is debug-only and influences nothing.
- **Domain ownership.** Quick Scan logic (corner naming, retake merge, same-corner detection, waits, cube-rule fill) lives in `:core:domain`; `ScanAssembler` is the only place samples rotate into Facelets order. The scan flow returns a `ScanOutcome`; only the caller dispatches it.
- **Calibration.** `CalibrationRepository` is the only owner; calibration is never in session state or a Checkpoint. A saved calibration applies to Quick Scan only when its torch state matches and every face centre reads confidently as its own colour.
- **Plausibility.** A per-calibration `ColorPlausibility.plausibleFor` judges a sticker against its own cube's centres; the fixed-window `plausible` stays unchanged as the calibration swatch gate. A shared corner-fixture filter over the exclusions file is used by the side-face replay test. Label-audit tooling lives in `tools/label-audit/` and runs outside CI.
- **Recorder.** The fixture recorder is a debug-only developer tool; its group count check lives in `RecorderRules` and runs only once both holds are complete, on the latest capture of each hold.
- **Quick Check result (v1.1).** Lessons and Solution Playback render one shared result component with the Certainty chip from `Assess` (never a numeric confidence); Playback actions route through its existing `SessionEvent` → `reduce()` transitions, never a ViewModel or composable mutating state.

## UX & Interaction Patterns

- **Corner prompts and retake.** Hold 1 asks for any corner; hold 2 names the missing faces by colour. Showing the same corner again is coached and highlights *Retake corner 1*. *Retake corner {n}* is available in the corner view, the hand-over viewfinder and Quick Scan review; a retake keeps the better read face by face.
- **Hand-over and review.** *Scan face by face* is always visible and keeps confidently captured faces. Blank or filled stickers arrive pre-selected in review; *Enter colors instead* is always available.
- **Capture and torch.** Each capture gives a visible cue and shutter sound, never haptics alone. On a flash device the torch is on by default in the corner view, with a visible toggle; no torch UI without a flash.
- **Accessibility.** *Close* sits in the same control group as the shutter and face prompt and is reached in order by TalkBack; confirmation questions are read before their actions; a Quick Check Certainty is announced once.

## Cross-Story Dependencies

- **Remaining order:** 10.24 → 10.25 (its same-corner way out uses 10.24's retake). 10.27 and 10.28 are device-free and independent of each other; 10.28 must land before any new corner-fixture recording session. 10.26 starts only after the PO's 10.21 BETA call. 10.29 is v1.1 scope, after 10.7.
- **10.27 ↔ 10.28:** 10.28 stops new bad groups at the recorder; 10.27 audits existing groups offline and its piece check catches balanced wrong labels the count misses.
- **10.11:** its first run raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF; a later run raises `QUICK_SCAN` after the 10.21 BETA call and does not wait for 10.23, 10.25, 10.27 or 10.28.
- **Epic 11:** Quick Scan's two-corner capture and Placement come from Stories 11.1 and 11.2; the Guided viewfinder's exit rule applies to Placement's scan as well.
