# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera. They scan it face by face, correct and calibrate colours, solve straight from the scan, and check physical Moves with a Quick Check. Quick Scan is the faster path. It is now a continuous facelet scan: the Learner turns the cube in front of the camera and a 6-face map fills in. This replaces the parked two-corner pipeline. All of it is v1.0 scope and flag-gated (v1.1 is retired). Camera code merges to `main` with release flags OFF, and a feature reaches users only when the PO raises its flag at a release (Story 10.11), based on measured accuracy. The first camera release is 0.11.0: Guided Scan at GA, Placement at BETA, and Quick Scan at BETA only if the continuous port and its evidence support it. The debug-only continuous fixture recorder (10.34–10.36) adds session record/replay. That replay drives the measured `QUICK_SCAN` decision and the fixes. Every flow keeps a manual fallback, and none produces a guessed state.

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
- Stories 10.24–10.27: dropped (superseded by continuous Quick Scan); do not start
- Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group
- Story 10.29: Solution Playback shows the same Quick Check result as Lessons
- Story 10.30: Port continuous facelet lattice fitter and cube mapper to pure JVM
- Story 10.31: Wire LiteRT v2 INT8 sticker-center detector, split `continuous` source set, and retire shadow reader
- Story 10.32: Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off
- Story 10.33: Continuous Quick Scan physical-device matrix and PO BETA call
- Story 10.34: Record and replay a continuous Quick Scan session in the developer fixture recorder
- Story 10.35: Retire the Quick-Scan-only two-corner recorder paths
- Story 10.36: Record new scrambles on the continuous recorder, analyse them, and improve continuous Quick Scan

## Requirements & Constraints

- **Never guess.** No partial or unvalidated Scan Result reaches the Solver or tutor, and uncertain stickers stay blank for the Learner. The confidently-wrong rate must be at most 0.1%, with zero silent wrong commits. A layer twisted mid-scan is detected and rejected, never stitched in.
- **Continuous Quick Scan.** It works from any starting side, with 1–3 faces visible per frame and no fixed pose or drawn guide. It self-calibrates from the cube's six centres, so a saved calibration is optional. It must handle stickered, stickerless and pastel cubes. After a regrip it recovers only when the orientation is unambiguous.
- **GA bar.** At least 95% of scans reach a valid state with at most 3 corrections (the Guided fallback is allowed) in under 15 s. Below that bar the feature ships as BETA.
- **Latency.** The full per-frame pipeline must run at p50 ≤ 50 ms on the Lenovo TB128XU and ≤ 20 ms on the Galaxy S24 FE. Recorder capture must not raise the analyzer p50 by more than 10%.
- **Devices.** The Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) is the gating minimum and the S24 FE is the mid-range gate. The Galaxy Tab S7 FE 5G is for compatibility only. Layouts must fit a phone in portrait without scrolling, and work at 200% font on the Lenovo's 600 dp width.
- **Privacy and size.**
  - No Learner-installable build stores, sends or logs frames. The debug fixture recorder is the only exception.
  - `CAMERA` is the only runtime permission, and the app has no internet permission.
  - The release must stay under 30,000,000 B, checked by `:app:checkCameraSize` and by a real minified 0.11.0-profile build. 16 KB ELF alignment is required.
  - No ML Kit, Play-services ML or OpenCV.
- **Fixtures.**
  - Held-out data is never used for tuning. A session and everything derived from it stay in one partition.
  - Each recorder group must have exactly 9 of each colour unless the operator gives a written reason.
  - Size limits: 15 MB per continuous session and 60 MB for `Fixtures/continuous/` (CI-enforced, no LFS).
  - Every existing fixture root must keep a working reader.
- **Acceptance.** Camera stories are accepted on the main app build from the real entry point, with the flag raised in debug, never through a debug-only launcher. Learner text comes from content strings (`checkLearnerText`). Evidence goes to `docs/evidence/<story>/`. Release-level calls (10.33, 10.36) are recorded in the PRD `.memlog.md`.

## Technical Decisions

- **AD-9 Camera isolation.** Only `app/src/camera/` touches pixels or calls `Interpreter`. Main code reaches the camera only through optional `@BindsOptionalOf` ports (`ScanLauncher`, `StickerCenterDetector`). With no binding, entry points are hidden. Only neutral data enters `:core:domain`: 2D sticker centres, per-cell colour statistics and quality scalars.
- **AD-9 Continuous pipeline.** The detector is `LiteRtStickerCenterDetector` in `app/src/camera/continuous/`. It runs the clean-room `sticker_centers_v2_int8.tflite` (378,288 B) on LiteRT 2.3.2 with XNNPACK on 2 threads, off the main thread. The reader of record is `FaceLatticeFitter` + `ContinuousFaceletMapper`: pure JVM in `:core:domain` (`recognition.continuous`), deterministic and at parity with the PC spike. A complete scan is canonicalized to `Orientation.HOME` (AD-2) and goes into `ScanReview`. Confirmed faces are exported in `ScanAssembler`'s kept-face format. `ScanAssembler` stays the only rotator for Guided, Editor, retake and hand-over. Mapper outputs are sealed snapshots and cue enums (AD-12).
- **AD-9 Frames (amended 2026-11-25, confirmed by the architect; this clears 10.34's start gate).** On operator request, the debug-only recorder may keep one bounded continuous session in app-specific debug storage. A session holds ordered detector inputs or downscaled frames with their transform, the observations and a manifest. The writer is reached only through an optional binding from the debug camera source set, and shipped `continuous/` code holds no writer. Two replay paths:
  - State replay runs on neutral observations in the `:core:domain` evaluation source set.
  - Image replay runs only as an instrumented debug test, because LiteRT is Android-only.
- **AD-15 Debug tools.** Debug-only source sets hold developer tools only. The recorder is not release scope and gates neither 10.11 nor any tag. A build-logic `FixtureWriterPolicy` sits beside `ShadowReaderPolicy` and rejects writer classes in every non-debuggable variant.
- **AD-18 Flags compose the build.** `QUICK_SCAN` above OFF composes `camera/continuous/`. `CASE_RECOGNITION` alone composes the legacy `camera/corner/` model, and it stays OFF in 0.11.0 and v1.0.0. LiteRT is included only if either flag is above OFF. Debug levels follow wiring: raise a flag in debug only in the PR that wires its entry point.
- **Retired code.** The Story 10.23 shadow reader is removed, but `ShadowReaderPolicy` stays as a release guard. Two-corner Quick Scan code is removed only once its consumers are inventoried and a legacy replay has been archived, and a disabled flag alone does not count as proof that code is dead. These stay for Case capture and the recorder:
  - `CornerLocator`, `CornerReader` and `CornerGuide`
  - Corner-mode capture
  - Readers of historical fixtures, such as `exportKotlinStickerReads` and `evaluateQuickScan`
- **Shared rules.** The 10.28 count/override rule moves into a format-neutral `StickerTruthCheck` in `:core:domain`, used by both labellers.
- **Model provenance.** The model's recipe, model card, SHA-256 and holdout test live in `tools/center-model/`. Retraining or replacing the model needs PO approval.
- **AD-9 / AD-3 State.** `CalibrationRepository` is the only calibration owner, and calibration is never kept in session state or a Checkpoint. The scan flow returns a `ScanOutcome` and only the caller dispatches it. A ViewModel never mutates state. AD-13 makes offline and privacy build-enforced, and AD-17 covers glyphs, accessibility and adaptive layout.

## UX & Interaction Patterns

- **Quick Scan screen.** A live preview with tracked face outlines and a compact growing URFDLB net using the `StickerAppearance` colour-blind glyphs. It shows "Sides mapped: N/6" and one inline cue line: bring the cube into view, turn, tilt for an adjacent face, tilt away from the light, conflict face, layer turned, or all 54 verified.
- **Controls.**
  - *Reset* clears the map in place.
  - *Scan face by face* keeps confirmed faces and opens Guided for the missing ones; Back returns to Quick Scan.
  - *Enter colors instead* opens the Editor with the confirmed stickers filled in.
  - *Torch* appears only on flash devices, starts OFF and turns off on hand-over, on leaving and in the background.
  - At 54/54, Scan Review opens with retake and Paint mode.
- **Exit rules.** Closing Quick Scan with 0 faces mapped leaves at once; otherwise it asks "Discard this scan?". The same rule holds for Guided Scan. System Back behaves like *Close*.
- **BETA labelling.** Quick Scan shows the shared BETA badge and tooltip, and Guided Scan has no beta label. A flag that is OFF shows no UI at all.
- **Accessibility.** TalkBack announces side milestones, cues, a conflict face and completion.

## Cross-Story Dependencies

- **Order:** 10.30 → 10.31 → 10.32 → 10.33 → 10.34 → 10.35 → 10.36 → 10.11.
  - 10.30 and 10.35 are device-free. 10.31 needs an R8 benchmark on both gating devices.
  - Fix stories routed from 10.33 do not block 10.34.
- **`QUICK_SCAN` call:** a BETA call at 10.33 lets 10.11 start before 10.36. An OFF call moves the decision to 10.36's replay analysis. A BETA call that rests on post-10.33 enhancements needs at least 5 live Solve-tab scans on the Lenovo.
- **Recorder chain:** 10.34 builds on the recorders from 10.12, 10.20 and 10.28. 10.35 needs 10.34's on-device capture and replay demonstrations. 10.36 uses 10.33's report as its baseline and the whole-corpus replay as its regression gate.
- **10.11:** needs 12.3, 12.4, 13.6 and 12.18. It runs before 12.5, which tags v1.0.0 with the same flag levels. The site privacy page must update before the tag.
- **Reused behaviour:** 10.32 reuses 10.3, 10.15, 10.16 and 10.17. Epic 11 Placement shares the Guided exit rule, and Case recognition keeps the legacy corner model.
