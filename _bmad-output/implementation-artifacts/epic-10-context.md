# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera: scan it face by face, correct and calibrate colors, solve straight from the scan, verify physical Moves with a Quick Check, and use Quick Scan (two corner photos) as the faster path. The epic is v1.0 scope behind feature flags: camera code merges to `main` with release flags OFF, and a feature reaches users only when the PO raises its flag at a release from measured accuracy. The remaining work makes Quick Scan reliable and forgiving: a fitted keep rule that never lets a confidently wrong sticker through, clean fixture labels and a per-cube pastel plausibility check, a way to retake a bad corner, accepting whichever corner is shown first, and (after the BETA call) filling a few unread stickers from cube rules. Every flow keeps a manual fallback and never produces a guessed state.

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

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or tutor. An uncertain sticker stays blank for the Learner, never a suggested color. A kept Quick Scan face never carries a confidently wrong sticker, including under a calibration from another cube, light or torch state. A wrongly named corner is a wrong cube: 0 wrong corner names on held-out replays.
- **Quick Scan bar** (enforced per device, light and torch state, never pooled, on held-out groups only): 0 confidently wrong side stickers, 0 Red/Orange confusions on kept faces, at least 90% of side stickers correct, at least 3 of 4 side faces kept (a face kept with one blank counts as kept; the blank counts as not correct). Backlit groups are reported, not scored, and must trip the light check.
- **Prompt capture.** Every corner hold auto-captures within 10 s of the cube being detected; any added wait (e.g. too few CONFIDENT stickers) counts toward the 10 s. A miss is recorded with its blocking gate and routed to the PO.
- **Fixture hygiene.** Tuning and held-out results record the label-set commit and the `Fixtures/corner/label-exclusions.json` hash; held-out groups are never used for tuning. Corner fixture labels must give 9 of each colour per group and only legal pieces, re-checked in CI from labels alone. Pastel cubes must work after calibration without loosening the check for every cube.
- **Devices and cubes.** Lenovo Tab M10 Plus 3rd Gen (TB128XU, minimum spec, no flash) and Galaxy S24 FE (flash) gate acceptance, with stickered, stickerless and glossy cubes, front-lit and backlit.
- **Privacy and size.** Frames and crops stay in memory and are discarded when the scan ends; nothing is stored, sent or logged in release. `CAMERA` is the only runtime permission. Release install stays under 30 MB. No `INTERNET`, analytics, crash SDK, ML Kit or Play-services ML.
- **Acceptance.** Camera stories are accepted on the main app build from their real entry point with the flag raised in debug; a debug-only launcher is never the gate. Learner text lives in `en.json`. Device evidence goes to `docs/evidence/<story>/`. `:core:*` stays pure Kotlin.

## Technical Decisions

- **Camera isolation.** Only the camera adapter (`app/src/camera/`) touches pixels and camera control (metering, torch). Results cross the port as categorical values only (a color plus `StickerConfidence`).
- **Hybrid recognition.** All deciding sticker classification stays pure Kotlin. Quick Scan corner detection uses an in-house quantized LiteRT model behind `CornerDetector`; faces are unwarped from detected corners. LiteRT and model bytes compose only when a corner-model flag is above OFF; OpenCV stays rejected. The 10.23 shadow reader is debug-only and influences nothing.
- **Domain ownership.** Quick Scan logic (corner naming, retake, better-read merge, same-corner state, waits) lives in `:core:domain` `QuickScanController`/`ScanAssembler`. `ScanAssembler` stays the only place samples rotate into `Facelets` order.
- **Calibration and keep rule.** A saved calibration is used only when its recorded torch state matches (nullable torch column via Room migration; older rows count as torch-off) and every captured face centre reads CONFIDENT as its own colour; otherwise Quick Scan self-calibrates and logs `calibration=mismatch <reason>`. With a match, a face is kept with at most 1 uncertain sticker and no disagreement beyond the agreement gate; without one, all 9 must be CONFIDENT. Agreement gate, confident-count wait N and light-check cuts (with hysteresis) are fitted on the 10.20 tuning split only.
- **Exposure and light.** Metering on the detected cube, exposure locked until capture or a re-meter trigger; the light check only coaches, never blocks, and is skipped only while the torch is actually lit. Debug builds log hold state changes to `CubentiaScanDiag`.
- **Plausibility (10.27).** A per-calibration `ColorPlausibility.plausibleFor` judges a sticker against its own cube's calibrated centres; the fixed-window `plausible` stays unchanged as the calibration swatch gate. Fitted constants are committed separately before validation. A shared corner-fixture filter over `label-exclusions.json` is used by `QuickScanSideFaceReplayTest` (10.21 adopts it). Label-audit tooling lives in `tools/label-audit/` and runs outside CI.
- **Corner naming (10.25).** Hold-1 faces are named from their three centres, never from the prompt; a red/orange close call never names a corner. With no unique corner, it stays unnamed and is named from all six centres after hold 2, else falls to the Guided grid or review.
- **Retake merge (10.24).** A corner retake keeps the better read face by face (passes keep rule beats fails; then more CONFIDENT stickers; tie takes new) — the one exception to "a kept face never changes until review".
- **Cube-rule fill (10.26).** A per-sticker safety assertion is built and run first. Cube rules only rank or reject image-derived candidates, with a search cap; fills arrive in review marked and pre-selected.

## UX & Interaction Patterns

- **Corner prompts.** Hold 1 asks for any corner; hold 2 names the missing faces by colour (generic wording if corner 1 is unnamed). If hold 2 still shows the hold-1 faces for about 2 s (judged over a window), coaching says it is the same corner and *Retake corner 1* is highlighted; TalkBack announces it once.
- **Retake corner.** Outlined *Retake corner {n}*: in the corner view while corner 2 is awaited (system Back does the same), in the hand-over face-by-face viewfinder, and above the face net in Quick Scan review. Backing out of a retake changes nothing; *Close* after a completed retake asks "Discard this scan?".
- **Capture and torch.** Each capture gives a visible cue and shutter sound, never haptics alone. On a flash device the torch is on by default in the corner view, recorder and calibration screen, with a visible toggle; no torch UI without a flash.
- **Hand-over and review.** *Scan face by face* is always visible and keeps confidently captured faces. Blank or filled stickers are pre-selected in review; *Enter colors instead* is always available. Portrait phone screens fit without scrolling.

## Cross-Story Dependencies

- **Remaining order:** 10.21 (in progress) → 10.24 → 10.25; 10.27 is device-free and runs in parallel; 10.26 starts only after the PO's 10.21 BETA call. 10.1–10.20, 10.22 and 10.23 are done.
- **10.21 ↔ 10.27:** 10.21 tunes on the PO-corrected labels and does not wait for 10.27; once 10.27's fixture filter exists 10.21 uses it, and its baseline is re-run when 10.27's exclusions land.
- **10.24 → 10.25:** 10.25's same-corner way out uses 10.24's retake; 10.25 also needs 10.21's calibration-match check and confident-count wait.
- **10.11:** its first run raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF. A later run raises `QUICK_SCAN` after 10.21's BETA call, at the level the 10.8 matrix supports; it does not wait for 10.23 or 10.25, and no later story is added to its dependencies.
- **Epic 11:** Quick Scan's two-corner capture comes from Story 11.1.
