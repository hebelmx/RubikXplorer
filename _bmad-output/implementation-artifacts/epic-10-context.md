# Epic 10 Context: Scan my cube

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera. They can scan it face by face, correct and calibrate the colours, solve straight from the scan, check physical Moves with a Quick Check, and use Quick Scan as the faster path. Quick Scan is a continuous facelet scan: the Learner turns the cube in front of the camera and a 6-face map fills in. It replaces the dropped two-corner pipeline. All of this is v1.0 scope and flag-gated. Camera code merges to `main` with the release flags OFF. A feature reaches users only when the PO raises its flag at a release (Story 10.11), based on measured accuracy, and OFF is a valid level. The first camera release is 0.11.0: the Guided Scan at GA, Placement at BETA, and Quick Scan at BETA only if the PO makes that call from measured evidence. Every flow keeps a manual fallback and never produces a guessed state. The remaining work makes continuous Quick Scan good enough for that call: detection, lattice-fitting and tracking enhancements (10.37), the device matrix (10.33) and the recorded-scramble analysis (10.36).

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
- Story 10.10: Live recognition baseline and fixture recorder (the recorder is retired by 10.35)
- Story 10.11: Raise a camera feature above OFF for a release
- Story 10.12: Fixture recorder a phone operator can finish quickly (retired by 10.35)
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
- Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group
- Story 10.29: Solution Playback shows the same Quick Check result as Lessons
- Story 10.30: Port continuous facelet lattice fitter and cube mapper to pure JVM
- Story 10.31: Wire LiteRT v2 INT8 sticker-center detector, split `continuous` source set, and retire shadow reader
- Story 10.32: Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off
- Story 10.33: Continuous Quick Scan physical-device matrix and PO BETA call
- Story 10.34: Record and replay a continuous Quick Scan session in the developer build's Quick Scan
- Story 10.35: Retire the Quick-Scan-only two-corner recorder paths
- Story 10.36: Record new scrambles on the continuous recorder, analyse them, and improve continuous Quick Scan
- Story 10.37: Continuous Quick Scan detection, lattice fitting, and tracking enhancements

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or the tutor. Unread and conflicted stickers stay blank for the Learner. The confidently-wrong rate must be at most 0.1%, with zero silent wrong commits. A layer twisted mid-scan is detected and rejected, never stitched in.
- **Continuous Quick Scan behaviour.** It works from any starting side (White included or not), with 1 to 3 faces visible per frame, no fixed corner pose and no drawn guide. It calibrates itself from the cube's own six centres, so a saved calibration is optional and never required. It must work on stickered, stickerless and pastel cubes. After a regrip it recovers only when the orientation is unambiguous, and it never locks onto a single symmetric face. The rules of the dropped two-corner stories (corner retake and merge, cube-rule fill, hold/exposure-lock/settle) do not apply; retakes happen per face in Scan Review.
- **Enhancement gates (10.37, 10.36).** Every pipeline change must pass these checks:
  - A recorded session reproduces the failure as a failing replay, and the change makes it pass.
  - Replaying the whole recorded corpus (`frames.jsonl`) is the regression gate.
  - The S1–S7 stress scenarios reach 54/54 with 0 wrong stickers.
  - Cube-free frames produce no false-positive face fits.
  - Any deliberate mapper or colour-model deviation from the PC spike is catalogued in `Fixtures/continuous/mapper-parity/expected-differences.json` and in the `ContinuousFaceletMapper` KDoc, so that `MapperParityReplayTest` passes.
  - Retraining or replacing `sticker_centers_v2_int8.tflite` is out of scope without a separate PO model-change decision.
- **Quick Scan GA bar.** At least 95% of scans reach a valid state with at most 3 corrections (the Guided fallback is allowed), in under 15 s. Below that bar it ships as BETA.
- **Latency.** The full per-frame pipeline p50 is 50 ms or less on the Lenovo TB128XU and 20 ms or less on the Galaxy S24 FE. Device runs record per-stage timings (detector total and inference, fit, sample, map, analyzer total; p50/p90/max) plus the analysed frame rate.
- **Devices.** The Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) is the gating minimum and the Galaxy S24 FE is the mid-range gate. The Galaxy Tab S7 FE 5G is for compatibility only. The fixture cubes are `stla`, `stlb`, `stka` and `stkb`. Layouts must fit a phone in portrait without scrolling, and work at 200% font on the Lenovo's 600 dp width without cutting or breaking words.
- **Privacy and size.**
  - Frames stay in memory and are never stored, sent or logged by a release or benchmark build. The one exception is the debug-only Quick Scan session recording, which saves frames on the operator's own device on request.
  - `CAMERA` is the only runtime permission, and the app has no internet permission.
  - The release stays under 30,000,000 B, checked by `:app:checkCameraSize` and by a real minified 0.11.0-profile build. 16 KB ELF alignment is required.
  - No ML Kit, Play-services ML or OpenCV.
- **Exit and review rules.** The Guided Scan leaves at once when no face is captured; otherwise it asks "Discard the faces you captured?". Quick Scan leaves at once when 0 faces are mapped; otherwise it asks "Discard this scan?". Closing an unchanged Scan Review falls back to the Editor with its stickers. System Back behaves like *Close*.
- **Acceptance.** Camera stories are accepted on the main app build from their real entry point, with the flag raised in debug; a debug-only launcher is never the gate. Learner text comes from content strings (`checkLearnerText`). Device evidence goes to `docs/evidence/<story>/`.
- **Release passes.** From 0.11.0, the first release that raises `CAMERA_SCAN`, the camera flows are part of the accessibility manual check, the performance pass and the recognition release gate. A release with every camera flag OFF declares no runtime permission.
- **Fixtures.**
  - Held-out groups are never used for tuning, and a session and its derivatives stay in one partition.
  - Labels must pass `StickerTruthCheck` (54 stickers, nine of each colour, reviewed provenance). An off-count set is accepted only with a non-blank operator reason.
  - New continuous sessions go in `Fixtures/continuous/`: at most 15 MB per session and 60 MB in total (`:checkFixtureSize`).
  - The legacy groups (`live`, `corner`, `matrix`, `labeled`, photo groups) are read-only evidence for the `:core:domain` evaluation readers.

## Technical Decisions

- **Camera isolation.** Only `app/src/camera/` touches pixels and calls `Interpreter`. Main code reaches the camera only through optional Hilt ports (`@BindsOptionalOf`); with no binding, every scan entry point is hidden. Only neutral numbers and fixed words cross into `:core:domain`.
- **Continuous pipeline.**
  - **Detector.** `LiteRtStickerCenterDetector` in `app/src/camera/continuous/` runs the clean-room `sticker_centers_v2_int8.tflite` model on LiteRT 2.3.2 with XNNPACK CPU, off the main thread, at a 256×256 letterboxed input and a 64×64 heatmap. Peaks are decoded in `StickerCenterModelTransform.decodePeaks`. If the detector fails to load, mismatches its tensor contract or fails an inference, it reports "unavailable" as its own state, never as an empty detection. One bad frame never counts as unavailable.
  - **Reader of record.** `FaceLatticeFitter` and `ContinuousFaceletMapper` are pure, deterministic JVM code in `:core:domain` (`recognition.continuous`), with parity against the PC spike. `ContinuousScanPipeline` connects detection, fitting, sampling and mapping.
- **Detector input framing.** In continuous mode the detector reads the full upright analysis frame, not the preview crop, so the pane shape no longer changes what it sees. Once faces are tracked, it reads only a square region: the tracked faces' bounding box plus a 35% margin, letterboxed to 256×256, with peaks mapped back to full-frame coordinates. It falls back to the full frame every N frames, when nothing is tracked, or when the region finds fewer faces than the last full-frame read. The Guided Scan and calibration still read the viewport crop.
- **10.37 enhancement rules.**
  - **Peak tiers.** Peaks may be decoded down to 0.25–0.30, but only peaks of 0.45 or more set the lattice scale and seeds. Lower peaks count only as score-weighted reprojection inliers.
  - **Faces 2 and 3.** Face 1's fitted edges serve as priors when searching for them.
  - **Temporal priors.** The previous frame's tracked quads may seed the next fit, within `FULL_SPAN_MASK = 7` and bounded displacement and distortion. A prior fit with fewer than 6 inliers is tracking-only and writes no observation.
  - **Saved calibration.** It may serve as a fallback 3–5-centre anchor, compared in an aligned CIELAB space. Self-calibration from the six centres still takes over once all of them are seen.
  - **Thread count.** XNNPACK threads (2, 3 or 4) are chosen by benchmark and recorded in `docs/evidence/10-37/`.
- **Mapper output.** A completed scan is put into `Orientation.HOME` (WHITE on U, GREEN on F) and goes straight to `ScanReview`. Confirmed faces are exported in `ScanAssembler`'s kept-face format for the Guided hand-over. `ScanAssembler` stays the only code that rotates samples for the Guided Scan, the Editor and per-face retake.
- **Flags compose the build.** `QUICK_SCAN` above OFF composes `camera/continuous/`. `CASE_RECOGNITION` alone composes the legacy `camera/corner/` model and stays OFF in 0.11.0 and v1.0.0. LiteRT is included only when either flag is above OFF. The opt-in `cornerR8` build type composes both model sets and is assembled on every PR. `ShadowReaderPolicy` guards against any `sticker_reader*.tflite` asset or `camera/sticker/` class.
- **Session recording and replay.** Sessions are recorded from the debug build's real Solve-tab Quick Scan through the debug-only package `dev.cubentia.app.camera.session` (Rec control, sheet, labeller, writer, adb trigger). Release, benchmark, `cornerR8` and `release011` builds carry none of it, which `FixtureWriterPolicy` guards. Capture copies frames into a bounded queue and never blocks the analyzer. State replay (`replayContinuousSession`) runs in the `:core:domain` evaluation source set. Image replay is an instrumented debug test, and `frames.jsonl` holds only peaks of 0.45 or more, so measuring the lower-threshold work (E4) needs full-resolution clips (#413).
- **Case recognition and legacy readers.** Case capture keeps its corner classes (`CaseCaptureController`, `CornerHold`, `CornerDetector`/`Locator`/`Reader`/`Guide`, `LiteRtCornerDetector`, `QuickScanPlan`). The historical readers stay in `:core:domain` evaluation. A disabled release flag alone never counts as evidence of dead code.
- **State and calibration.** `CalibrationRepository` is the only owner of calibration, which is never stored in session state or a Checkpoint. The scan flow returns a `ScanOutcome`, and only the caller dispatches it. Solution Playback actions go through `SessionEvent` → `reduce()`. Lessons and Solution Playback share one Quick Check result component, which shows the Certainty chip and never a numeric confidence.

## UX & Interaction Patterns

- **Quick Scan screen.** It shows the live preview with outlines around the tracked faces, a compact growing URFDLB net with the colour-blind glyphs from `StickerAppearance`, a "Sides mapped: N/6" count, and one cue line.
- **Layout.** In any portrait window (the Lenovo's 600 dp included) the screen stacks like a phone: the preview on top, full width, near 4:3 or squarer and never tall, with the cue, count, net and actions below. In any landscape window the preview is on the left and the rest on the right. The Guided viewfinder and calibration keep their own two-pane rule from 600 dp.
- **Cue priority (one line).** When several cues apply, the first in this list wins:
  1. Layer turned mid-scan.
  2. All 54 verified.
  3. Bring the cube into view (no face tracked anywhere in the frame).
  4. "Move the cube into the frame." (more than half the tracked quad area is off-screen).
  5. "Move the cube closer." (the largest per-face median sticker spacing, scaled to 256 px, is below 12 px; it clears above 14 px).
  6. The mapper's other cues (turn, tilt, light, conflict, remaining stickers).

  The framing cues need 3 consecutive frames to show and 3 to clear. Faces outside the visible preview may still record stickers but are not outlined.
- **Controls.**
  - *Reset* clears the map.
  - *Scan face by face* is always visible. It keeps the confirmed faces and opens the Guided Scan for the missing faces only; Back returns with the faces intact.
  - *Review and Correct* is outlined and appears once more than half the sides and stickers are mapped. Its place is always reserved. Nothing done in that early review is kept.
  - *Enter colors instead* opens the Editor pre-filled with the confirmed stickers.
  - At 54/54, Scan Review opens, with per-face retake, suspect ranking and Paint mode.
- **Detector unavailable.** A card ("Quick Scan isn't available right now…") replaces the live scan and the cue line, offering only *Scan face by face* and *Enter colors instead*. It is not retried until the app process restarts.
- **Torch.** The toggle appears only on devices with a flash and is OFF by default. It turns off on hand-over, on leaving, and when the app goes to the background.
- **BETA labelling and accessibility.** Quick Scan shows the shared BETA badge and tooltip; the GA Guided Scan has no label. A flag left OFF shows no entry point, string or permission rationale. TalkBack announces side-count milestones, cues (the framing cues included), a conflict face, the unavailable card and completion.

## Cross-Story Dependencies

- **Status.** 10.30–10.32, 10.34 and 10.35 are done. 10.37 is ready for development, and 10.33, 10.36 and 10.11 are in the backlog. The 10.34 Lenovo device gate is still an open action item.
- **10.37** depends on 10.34 and on the bug fixes #431–#437, especially #434, #435 and #437, which touch the fitter, `decodePeaks` and the shared-edge geometry. Measuring E4 (lower-threshold peaks) and E11 (thread count) also needs #406 (per-stage timings) and #413 (full-resolution clips). 10.37 feeds 10.36 and the 10.33 matrix. It does not hard-block 10.11.
- **10.33** runs after #406. It is also meant to follow the framing fixes #415 and then #407, so that it measures the improved scan. A BETA call from 10.33 lets 10.11 start without 10.36; an OFF call moves the `QUICK_SCAN` decision to 10.36.
- **10.36** records with the 10.34 mode, and its labels must pass `StickerTruthCheck`. A BETA call based on enhancements made after 10.33 needs at least 5 full live Lenovo scans from the Solve tab.
- **10.11** depends on 12.3, 12.4, 13.6 and 12.18 (every open action item settled) and runs before 12.5. 12.5 promotes a v1.0.0 tag cut from main with the same flag levels. The site's privacy page must be updated before the tag.
- **Reused behaviour.** 10.32 reuses the review (10.3), solve and Editor fallback (10.15), per-face retake (10.16) and the Guided hand-over (10.17).
