# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Recompiled from epics.md in Story 10.35 (SCP 2026-11-26 retire-recorder). -->

## Goal

A Learner can put their real cube into the app with the camera: scan it face by face, correct and calibrate colours, solve straight from the scan, check physical Moves with a Quick Check, and use Quick Scan as the faster path. Quick Scan is a continuous facelet scan: the Learner turns the cube in front of the camera and a 6-face map fills in, replacing the dropped two-corner pipeline. All of it is v1.0 scope and flag-gated. Camera code merges to `main` with release flags OFF; a feature reaches users only when the PO raises its flag at a release (Story 10.11), based on measured accuracy, and OFF is a valid level. The first camera release is 0.11.0, with the Guided Scan at GA, Placement at BETA, and Quick Scan at BETA only if the PO calls it so from measured evidence. Every flow keeps a manual fallback and never produces a guessed state.

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
- Story 10.10: Live recognition baseline and fixture recorder (done; the recorder it built is retired by 10.35)
- Story 10.11: Raise a camera feature above OFF for a release
- Story 10.12: Fixture recorder a phone operator can finish quickly (done; retired by 10.35)
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
- Stories 10.24–10.27: dropped (replaced by the continuous Quick Scan, 10.30–10.33); do not start
- Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group (done; its rule lives on in `StickerTruthCheck`)
- Story 10.29: Solution Playback shows the same Quick Check result as Lessons
- Story 10.30: Port continuous facelet lattice fitter and cube mapper to pure JVM
- Story 10.31: Wire LiteRT v2 INT8 sticker-center detector, split `continuous` source set, and retire shadow reader
- Story 10.32: Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off
- Story 10.33: Continuous Quick Scan physical-device matrix and PO BETA call
- Story 10.34: Record and replay a continuous Quick Scan session in the developer build's Quick Scan
- Story 10.35: Retire the Quick-Scan-only two-corner recorder paths
- Story 10.36: Record new scrambles on the continuous recorder (the 10.34 recording mode), analyse them, and improve continuous Quick Scan

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or tutor. Unread and conflicted stickers stay blank for the Learner. The confidently-wrong rate target is at most 0.1%, with zero silent wrong commits. A mid-scan layer twist is detected and rejected, never stitched in.
- **Continuous Quick Scan behaviour.** It works from any starting side (including without White) with 1 to 3 faces visible per frame, with no fixed corner pose and no drawn guide. It self-calibrates from the cube's own six centres, so a saved calibration is optional and never required. It must work on stickered, stickerless and pastel cubes. After a regrip it recovers only when the orientation is unambiguous, and it never locks onto a single symmetric face.
- **Dropped two-corner rules.** The rules of the dropped Stories 10.24–10.27 (retaking a corner and merging it, starting from whichever corner is shown and naming the same-corner case, filling unread stickers from cube rules, the corner-label fixture filter) and the two-corner hold's exposure lock, settle and prompt-capture rules do not apply to the continuous scan. Retakes happen per face in Scan Review.
- **Quick Scan GA bar.** At least 95% of scans reach a valid state with at most 3 corrections (the Guided fallback is allowed), in under 15 s. Below that bar it ships as BETA.
- **Latency.** Full per-frame pipeline p50: 50 ms or less on the Lenovo TB128XU, 20 ms or less on the Galaxy S24 FE.
- **Devices.** The Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) is the gating minimum. The Galaxy S24 FE is the mid-range gate. The Galaxy Tab S7 FE 5G is for compatibility only. Layout must fit a phone in portrait without scrolling, and work at 200% font on the Lenovo's 600 dp width without cutting or breaking words.
- **Privacy and size.** Frames stay in memory and are never stored, sent or logged by any release or benchmark build; the only exception is the debug-only Quick Scan session recording (10.34), which saves frames on an operator's own device on request. `CAMERA` is the only runtime permission, and there is still no internet permission. The release must stay under 30,000,000 B, checked by `:app:checkCameraSize` and by a real minified 0.11.0-profile build. 16 KB ELF alignment is required. No ML Kit, Play-services ML or OpenCV.
- **Exit and review rules.**
  - Guided Scan: closing with no face captured leaves at once. With faces captured it asks "Discard the faces you captured?".
  - Quick Scan: closing leaves at once at 0 mapped faces. Otherwise it asks "Discard this scan?".
  - Scan review: closing an unchanged review falls back to the Editor with its stickers.
  - System Back behaves the same as *Close*.
- **Acceptance.** Camera stories are accepted on the main app build from their real entry point, with the flag raised in debug. A debug-only launcher is never the gate. Learner text comes from content strings (`checkLearnerText`). Device evidence goes to `docs/evidence/<story>/`.
- **Release passes.** From the first release that raises `CAMERA_SCAN` (0.11.0), the camera flows are part of the accessibility manual check, the performance pass and the recognition release gate. A release with every camera flag OFF declares no runtime permission.
- **Fixtures.** Held-out groups are never used for tuning, and a session and its derivatives stay in one partition. The nine-of-each-colour rule lives in `StickerTruthCheck` (`:core:domain` main): labels without exactly 9 of each colour, or with an unreviewed pre-fill, are refused, and an off-count set is accepted only with a non-blank operator reason. The existing `Fixtures/live`, `corner`, `matrix` and `labeled` groups and the photo groups stay as read-only evidence for the `:core:domain` evaluation readers; `docs/evidence/10-35/legacy-replay.md` is the single legacy-replay record. New continuous sessions go in `Fixtures/continuous/`, at most 15 MB per session and 60 MB in total (`:checkFixtureSize`).

## Technical Decisions

- **Camera isolation.** Only `app/src/camera/` touches pixels and calls `Interpreter`. Main code reaches the camera only through optional Hilt ports (`@BindsOptionalOf`); with no binding, every scan entry point is hidden.
- **Continuous pipeline.** The detector and the reader are split across two places:
  - **Detector.** `LiteRtStickerCenterDetector` lives in `app/src/camera/continuous/`. It uses the clean-room `sticker_centers_v2_int8.tflite` model (369.4 KB) on LiteRT 2.3.2 with XNNPACK CPU on 2 threads, off the main thread. It sends only neutral 2D sticker-centre points, per-cell colour statistics and quality scalars into the domain. A detector that fails to load, mismatches its tensor contract or fails an inference reports "unavailable" as its own state inside `app/src/camera/`, never as an empty detection; one bad frame never counts as unavailable.
  - **Reader of record.** `FaceLatticeFitter` and `ContinuousFaceletMapper` are pure JVM code in `:core:domain` (`recognition.continuous`). They must be deterministic and match the PC spike exactly.
- **Mapper output.** A completed scan is put into `Orientation.HOME` (WHITE on U, GREEN on F) and goes straight into `ScanReview`. Confirmed faces (all nine stickers read, none in conflict) are exported in `ScanAssembler`'s kept-face format for the Guided hand-over, which keeps them without reclassifying. `ScanAssembler` is still the only code that rotates samples for the Guided Scan, the Editor and per-face retake.
- **Flags compose the build.** `QUICK_SCAN` above OFF composes `camera/continuous/`. `CASE_RECOGNITION` alone composes the legacy `camera/corner/` model, and it stays OFF in 0.11.0 and v1.0.0. LiteRT is included only when either flag is above OFF. The opt-in `cornerR8` build type composes both model sets and is assembled on every PR.
- **Shadow reader retired.** The Story 10.23 shadow reader is removed. `ShadowReaderPolicy` stays as a release guard against any `sticker_reader*.tflite` asset or `camera/sticker/` class.
- **Model provenance.** The training and evaluation recipe lives in `tools/center-model/`, with a model card, the full SHA-256 and a holdout-disjointness test.
- **Two-corner code and Case recognition.** The Quick-Scan-only two-corner code is removed, and there is no separate capture screen. Case recognition keeps `CaseCaptureController`, `CornerHold`, `CornerDetector`, `CornerLocator`, `CornerReader`, `CornerGuide`, `LiteRtCornerDetector`, `GuidedScanPhase.CornerViewfinder`, `QuickScanPlan` and `CornerSettle.CAP_MS`. The historical readers (`QuickScanClassification`, `GridReason`, `SideLight`, `LiveFixture` decoding and the evaluation tasks) stay, owned by `:core:domain` evaluation. A disabled release flag alone never counts as evidence of dead code.
- **Session recording (10.34).** Continuous sessions are recorded from the debug build's real Solve-tab Quick Scan (same camera configuration, pipeline and UI). The writer, Rec control, session sheet and labeller live in the debug-only package `dev.cubentia.app.camera.session`, reached only through optional bindings the debug camera source set provides. Release, benchmark, `cornerR8` and `release011` builds carry no writer or Rec control, guarded by `FixtureWriterPolicy`. Capture never blocks the analyzer: it copies into a bounded queue and counts skips. Replay (`replayContinuousSession`) runs in the `:core:domain` evaluation source set.
- **Calibration.** `CalibrationRepository` is the only owner of calibration. Calibration is never stored in session state or a Checkpoint.
- **State changes.** The scan flow returns a `ScanOutcome`, and only the caller dispatches it. Solution Playback actions go through `SessionEvent` → `reduce()`. A ViewModel or composable never mutates state.
- **Quick Check result (10.29).** Lessons and Solution Playback share one result component. It shows the Certainty chip from `Assess` and never a numeric confidence.

## UX & Interaction Patterns

- **Quick Scan screen.** It shows the live preview with outlines around the tracked faces, a compact growing URFDLB net using the colour-blind glyphs from `StickerAppearance`, a "Sides mapped: N/6" count, and one inline cue line. The cues cover: bring the cube into view, turn to show the remaining sides, tilt for an adjacent face, tilt away from the light, a conflict face, a layer turned mid-scan, and all 54 verified. From 600 dp width, and in any landscape window, the preview sits on the left and the net, count, cue and actions on the right.
- **Controls.**
  - *Reset* clears the map in place.
  - *Scan face by face* is always visible. It keeps the confirmed faces and opens the Guided Scan for the missing faces only. Back from there returns to Quick Scan with the faces intact.
  - *Review and Correct* (outlined, never emphasized) appears once more than half the sides and more than half the stickers are mapped; its place is always reserved so nothing moves. It opens Scan Review on a fresh export with unread stickers blank. Back returns to the live scan as it was, and nothing done in that review is kept.
  - *Enter colors instead* opens the Editor, pre-filled with the confirmed stickers.
  - Once all 54 stickers are verified, Scan Review opens, with per-face retake, suspect ranking and Paint mode.
- **Detector unavailable.** The live scan is replaced by a card ("Quick Scan isn't available right now…") with only *Scan face by face* and *Enter colors instead*; it is not retried until the app process restarts.
- **Torch.** The toggle appears only on devices with a flash and is OFF by default. It turns off on hand-over, on leaving and when the app goes to the background.
- **BETA labelling.** Quick Scan shows the shared BETA badge and tooltip. The GA Guided Scan has no beta label. A flag that stays OFF shows no entry point, string or permission rationale.
- **Accessibility.** TalkBack announces side-count milestones, cues, a conflict face, the unavailable card and completion. A Quick Check Certainty is announced once.

## Cross-Story Dependencies

- **Remaining order:** 10.33 (live matrix and the PO's BETA call), and 10.35 → 10.34 → 10.36 → 10.11. 10.35 is device-free and runs first; 10.34 is then built on a fresh branch from `main`, cherry-picking from the closed PR #396. 10.36 records with the 10.34 mode, and its labels pass `StickerTruthCheck`. The recording stories are debug tooling and gate no tag; 10.11 waits on 10.36 only when 10.33 does not call `QUICK_SCAN` BETA.
- **10.11:** depends on 12.3, 12.4, 13.6 and 12.18 (settle every open action item) and runs before 12.5. Its first run, 0.11.0, raises `CAMERA_SCAN` to GA and `PLACEMENT` to BETA, and `QUICK_SCAN` to BETA only when the PO's call (10.33 or 10.36) supports it; otherwise it follows in a later 0.11.x run.
- **12.5:** promotes a new v1.0.0 tag cut from main, with the same flag levels.
- **Privacy page:** the site's privacy page must update before the tag.
- **Reused behaviour:** 10.32 reuses the review (10.3), solve and Editor fallback (10.15), per-face retake (10.16) and Guided hand-over (10.17).
- **10.29:** done in v1.0. Its deferred TalkBack and 200% font device check is an open action item, not a v1.0.0 tag gate. It also touches Lessons' physical mode (Epic 8 and Story 14.3 flows).
