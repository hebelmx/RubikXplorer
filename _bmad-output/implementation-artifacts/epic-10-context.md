# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera: scan it face by face, correct and calibrate colors, solve straight from the scan, verify physical Moves with a Quick Check, and use Quick Scan (two corner photos) as the faster path. The epic is v1.0 scope behind feature flags: camera code merges to `main` with release flags OFF, and a feature reaches users only when the PO raises its flag at a release from measured accuracy. The remaining work makes Quick Scan reliable and forgiving: fixtures and a fitted keep rule that never let a confidently wrong sticker through, a way to retake a bad corner, accepting whichever corner is shown first, and (after the BETA call) filling a few unread stickers from cube rules. Every flow keeps a manual fallback and never produces a guessed state.

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

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or tutor. An uncertain sticker stays blank for the Learner, never a suggested color. A kept Quick Scan face never carries a confidently wrong sticker, including under a calibration from another cube, light or torch state. A wrongly named corner is a wrong cube: the bar for corner naming is 0 wrong names on held-out replays.
- **Quick Scan bar (enforced per device, light and torch state, never pooled, on held-out groups):** 0 confidently wrong side stickers, 0 Red/Orange confusions on kept faces, at least 90% of side stickers correct, at least 3 of 4 side faces kept (a face kept with one blank counts as kept). Backlit groups are reported, not scored. GA needs at least 95% of scans valid after 3 or fewer corrections in under 15 s, else BETA.
- **Prompt capture.** Every corner hold auto-captures within 10 s of the cube being detected; any new wait (e.g. too few CONFIDENT stickers) counts toward the 10 s. A miss is recorded with its blocking gate and routed to the PO.
- **Corner model.** Per device on held-out captures: recall at least 92%, rejection at least 96%, mean corner error at most 3% of the outline diagonal; median inference under 40 ms off the main thread on the minimum device.
- **Devices and cubes.** Lenovo Tab M10 Plus 3rd Gen (TB128XU, minimum spec, no flash) and Galaxy S24 FE (flash) gate acceptance, with stickered, stickerless and glossy cubes, front-lit and backlit. Pastel stickerless cubes must work after calibration.
- **Privacy and size.** Frames and crops stay in memory and are discarded when the scan ends; nothing is stored, sent or logged in release. `CAMERA` is the only runtime permission. Release install stays under 30 MB. No `INTERNET`, backup, analytics, ads, crash SDK, ML Kit or Play-services ML. Raising a camera flag regenerates `docs/privacy-facts.json` and re-confirms Play Data safety.
- **Acceptance.** Camera stories are accepted on the main app build from their real entry point with the flag raised in debug; a debug-only launcher is never the gate. Learner text lives in `en.json`. Device evidence goes to `docs/evidence/<story>/`. `:core:*` stays pure Kotlin with at least 80% line coverage.

## Technical Decisions

- **Camera isolation.** Only the camera adapter (`app/src/camera/`) touches pixels and camera control (metering, torch). Results cross the port as categorical values only (a color plus `StickerConfidence`).
- **Hybrid recognition.** Guided Scan and all deciding sticker classification stay pure Kotlin. Quick Scan corner detection uses an in-house quantized LiteRT model behind `CornerDetector`; faces are unwarped from detected corners. LiteRT and model bytes compose only when `QUICK_SCAN` or `CASE_RECOGNITION` is above OFF; OpenCV stays rejected.
- **Domain ownership.** Quick Scan logic (corner naming, retake, better-read merge, same-corner state, waits) lives in `:core:domain` `QuickScanController`/`ScanAssembler`; no new port, entity or `SessionEvent`. `ScanAssembler` stays the only place samples rotate into `Facelets` order.
- **Calibration and keep rule.** A saved calibration is used only when its recorded torch state matches and every captured face centre reads CONFIDENT as its own color; otherwise Quick Scan self-calibrates and logs `calibration=mismatch <reason>`. With a matching calibration a face is kept with at most 1 uncertain sticker and no disagreement beyond the agreement gate; without one, all 9 must be CONFIDENT. Thresholds (agreement gate, confident-count wait N, light-check cuts with hysteresis) are fitted on the 10.20 tuning split only.
- **Exposure (from 10.22).** Metering on the detected cube, exposure locked until capture or a re-meter trigger; the light check only coaches, never blocks. Debug builds log hold state changes to `CubentiaScanDiag`.
- **Corner naming (10.25).** Hold-1 faces are named from their three centres, never from the prompt. White/yellow and green/blue are named by color; a red/orange centre takes the color the scheme allows for that corner. If no unique corner results, it stays unnamed and is named from all six centres after hold 2, else falls to the Guided grid or review.
- **Retake merge (10.24).** A corner retake keeps the better read face by face (passes keep rule beats fails; then more CONFIDENT stickers; tie takes new) — the one exception to "a kept face never changes until review".
- **Cube-rule fill (10.26).** A per-sticker safety assertion is built and run first. The search prunes by cube rules with a cap; rules only rank or reject candidates and never assign a color outside a sticker's image-derived candidates. Fills arrive in review marked and pre-selected.
- **Shadow sticker reader (10.23).** Debug-only LiteRT per-face reader logs beside the Kotlin reads with no influence on any result; release and benchmark builds carry no model or reader class. Letting it influence results needs a further change proposal.
- **Fixtures.** Labelled corner captures under `Fixtures/corner/`, hand-labelled, tagged with pipeline, device, cube id, light direction and torch state. Whole capture groups are held out (named in the group index) and never used for tuning; training and replays are seeded and reproducible.

## UX & Interaction Patterns

- **Corner prompts.** Hold 1 asks for any corner showing three faces; hold 2 names the missing faces by color (generic wording if corner 1 is unnamed). If hold 2 still shows the hold-1 faces for about 2 s (judged over a window), inline coaching says it is the same corner and *Retake corner 1* takes the highlighted accent outline; TalkBack announces it once.
- **Retake corner.** Outlined *Retake corner {n}* button: in the corner view only while corner 2 is awaited (beside *Scan face by face*; system Back does the same); in the hand-over face-by-face viewfinder one per captured corner; in Quick Scan review retake mode both in one row above the face net. Backing out of a retake changes nothing; *Close* after a completed retake asks "Discard this scan?".
- **Capture feedback.** Each capture gives a visible cue and shutter sound, never haptics alone. Light coaching shows/hides without blocking capture.
- **Torch.** On a flash device the torch is on by default in the corner view, recorder and calibration screen, with a visible toggle; no torch UI without a flash.
- **Hand-over and review.** *Scan face by face* is always visible and keeps confidently captured faces. In review, blank or filled stickers are pre-selected, *Accept* enables only once valid, and *Enter colors instead* is always available. Portrait phone screens fit without scrolling.

## Cross-Story Dependencies

- **Remaining order:** 10.20 (in progress) → 10.21 → 10.24 → 10.25; 10.26 starts only after the PO's 10.21 BETA call. 10.1–10.19, 10.22 and 10.23 are done.
- **10.20 → 10.21:** 10.20 supplies the tuning and held-out splits (recorded under 10.22's exposure handling) and the unenforced baseline; 10.21 enforces the bar and yields the BETA call. 10.20 also hosts the deferred pastel-cube calibration check.
- **10.24 → 10.25:** 10.25's same-corner way out uses 10.24's retake; 10.25 also needs 10.21's calibration-match check and confident-count wait.
- **10.11:** its first run raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF. A later run raises `QUICK_SCAN` after 10.21's BETA call, at the level the 10.8 matrix supports; it does not wait for 10.23, 10.24, 10.25 or 10.26.
- **Epic 11:** Quick Scan's two-corner capture comes from Story 11.1; 10.25 closes the 11.1 wrong-corner self-calibration action item.
