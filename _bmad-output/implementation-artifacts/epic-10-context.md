# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real 3×3 cube into Cubentia on-device with the camera: Guided 6-face Scan, continuous facelet Quick Scan (turning the cube freely without fixed corner holds or a drawn guide), sticker review and correction, color calibration, solving from a scan, and Quick Check verification of physical moves. Camera code merges behind feature flags kept OFF in release until raised from measured accuracy: release `0.11.0` (then `v1.0.0`) raises Guided Scan to GA, Placement to BETA, and continuous Quick Scan to BETA once its port and device BETA call pass, with Case recognition OFF. Remaining v1.0 work replaces the parked two-corner pipeline with continuous facelet scanning and retires the debug shadow reader. Every flow keeps a manual fallback and never produces a guessed or unvalidated state.

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
- Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group
- Story 10.29: Solution Playback shows the same Quick Check result as Lessons
- Story 10.30: Port continuous facelet lattice fitter and cube mapper to pure JVM
- Story 10.31: Wire LiteRT v2 INT8 sticker-center detector, split `continuous` source set, and retire shadow reader
- Story 10.32: Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off
- Story 10.33: Continuous Quick Scan physical-device matrix and PO BETA call

## Requirements & Constraints

- **Never guess; validate everything.** No partial, unvalidated, or guessed state reaches the Solver or tutor. Uncertain stickers stay blank. Every scan mode targets ≤ 0.1% confidently wrong stickers (zero silent wrong commits).
- **Continuous Quick Scan bar.** Turning the cube continuously from any side (1–3 faces/frame, no fixed holds or drawn guide) stitches faces across shared edges into a verified 54-sticker cube. It self-calibrates shading-invariant colors from the six centers (stickered, stickerless, pastel; calibration optional), merges across regrips only when orientation is unambiguous (never locking a symmetric single face), reopens conflicting stickers via cube invariants, and pauses on mid-scan layer twists. Full R8 latency: ≤ 50 ms p50 on Lenovo TB128XU, ≤ 20 ms p50 on Galaxy S24 FE; BETA requires ≥ 20 full scans (≥ 1,080 stickers) across cubes and lights. Continuous move tracking during solving stays out of scope.
- **Guided Scan & Quick Check.** Guided Scan accepts any rotation (re-prompting ≤ 2 ambiguous faces with a named top; > 2 go to review) and targets ≥ 98% valid scans within 3 corrections and ≥ 99.5% indoor accuracy. Quick Check verifies only a match from affected faces; a mismatch is verified only by a 6-face scan (0 false verified).
- **Exit, review & fallback.** Guided Scan *Close*/Back leaves at 0 faces (`Cancelled`) and asks "Discard the faces you captured?" at ≥ 1 face (every caller). Quick Scan leaves at 0 mapped faces and asks "Discard this scan?" at ≥ 1 face. Unchanged review *Close* returns `FellBackToEditor` with stickers (seeding the Solve-tab Editor only, unread/unresolved stickers unset; Placement opens blank); changed review asks "Discard this scan?". Likely-misread hints appear only within 20 ΔE relative margin of an under-counted center (max 3, never padded).
- **Devices, privacy & size.** Gating devices: Lenovo TB128XU (minimum, no flash) and Galaxy S24 FE (flash); Tab S7 FE 5G is compatibility-only; emulator cameras never gate acceptance. Phone portrait fits without scrolling; 200% font on 600 dp must not clip; Learner text lives in content strings. Frames stay in memory and are never stored, logged, or sent in release. `CAMERA` is the only runtime permission (with rationale); no network, analytics, OpenCV, ML Kit, or Play-services ML. Release install stays < 30 MB (16 KB ELF aligned, largest ABI).
- **Main-app acceptance & release sync.** Accept camera features on the main app build from real entry points (Solve tab for Quick Scan), never a debug launcher. Raising a release flag syncs `privacy-facts.json`, the privacy site (before the flag-raise PR or while `APP_REF` stays pinned to the prior tag), Play Data safety, store listing (beta label only for BETA), and pilot protocol.

## Technical Decisions

- **Isolation & neutral boundary.** Only `app/src/camera/` touches pixels, CameraX, and camera control; main code uses optional Hilt ports (`@BindsOptionalOf` `ScanLauncher`, `StickerCenterDetector`), hiding entry points when unbound. Only neutral 2D centers, per-cell RGB/CIELAB stats, and quality scalars cross into `:core:domain`. Scan flows return `ScanOutcome` (`Accepted`, `Cancelled`, `FellBackToEditor`) and never mutate session state.
- **Source-set & LiteRT split.** `build-logic` composes per build type: any camera flag > OFF adds `app/src/camera/` + CameraX (other camera flags require `CAMERA_SCAN` > OFF); `QUICK_SCAN` > OFF adds `app/src/camera/continuous/` (`sticker_centers_v2_int8.tflite` 369.4 KB, `UINT8[1,256,256,3] → FLOAT32[1,64,64,3]`, 2 XNNPACK threads off-main) + `litert:2.3.2` (no `ai-delivery`); `CASE_RECOGNITION` > OFF adds legacy `app/src/camera/corner/` (`corner_keypoints.tflite`, OFF in 0.11.0/v1.0.0). All LiteRT `Interpreter` calls stay inside `app/src/camera/` for R8 keep rules; size/classpath checks never double-count OFF assets/classes.
- **Pure-JVM recognition (`:core:domain`, ≥ 80% Kover).** Guided Scan stays pure Kotlin (`FaceLocator`, `StickerClassifier`, `ScanAssembler`). Continuous Quick Scan's reader of record is `FaceLatticeFitter` + `ContinuousFaceletMapper` (`dev.cubentia.domain.recognition.continuous`): projective quad fitting, shared-edge face graph with center-color lock, 24-SO(3) regrip merger with symmetry/outlier guards, bounded log-RGB AWB drift, shading-invariant CIELAB + WHITE-referenced hue with balanced 8-per-color refinement, cubie-invariant conflict reopening, and twist detection. Completed scans canonicalize to `Orientation.HOME` (WHITE on U, GREEN on F) and export typed handoffs for Review, Guided handover (keeping confirmed faces without reclassifying), retake, suspect ranking, and Solve-tab Editor seeding. `ScanAssembler` stays the sole rotator for Guided Scan, Editor, retake, and handover.
- **Cleanup, provenance & state.** Story 10.23's debug shadow reader (`app/src/camera/debug/sticker/`, `CapturedFaceObserver`) is removed while `ShadowReaderPolicy` keeps blocking shadow assets/classes from non-debuggable builds. Quick-Scan-only 2-corner code is removed while keeping `CornerLocator`/`CornerReader`/`CornerGuide` for `CASE_RECOGNITION` and the debug fixture recorder. Model provenance (`model-card.json`) and holdout-disjointness tests live in `tools/center-model/`. `CalibrationRepository` solely owns Color Calibration (never in session state or checkpoints; optional for Quick Scan). Shared Quick Check results (v1.1) render Certainty from `tutor/Assess` (never numeric confidence) and route actions through `SessionEvent` → `reduce()`.

## UX & Interaction Patterns

- **Continuous Quick Scan viewfinder.** Live preview outlines tracked faces beside a growing 6-face `URFDLB` net (`Sides mapped: N/6`) using shared `StickerAppearance` color-blind glyphs and one inline cue (bring into view, turn for remaining sides, tilt for adjacent face or away from glare, show conflict face, layer turned mid-scan, or all 54 verified).
- **Controls, torch & fallback.** *Reset* clears in place; *Scan face by face* keeps confirmed faces into Guided Scan (Back returns with faces intact); *Enter colors instead* seeds the Solve-tab Editor when ≥ 1 face is mapped. On flash devices, optional torch defaults OFF and turns off on handover/exit/background; no torch UI on flashless devices. At 54/54 verified, Scan Review opens (retake, suspect hints, Paint mode).
- **BETA badge & accessibility.** BETA features share one BETA badge and tooltip; GA Guided Scan has none. In tablet landscape, *Close* sits with primary capture controls. TalkBack announces side milestones, cues, conflict face, completion, discard questions before actions, and Quick Check Certainty once.

## Cross-Story Dependencies

- **Continuous Quick Scan chain:** `10.30` (JVM fitter/mapper) → `10.31` (LiteRT v2 INT8 detector, `continuous` split, shadow-reader retirement, R8/size checks) → `10.32` (live UI, handoffs, 2-corner cleanup) → `10.33` (3-device matrix & PO BETA call). Stories `10.24–10.27` were dropped as superseded (Guided Scan's pastel-plausibility follow-up is tracked separately).
- **Release flag-raise (`10.11`) & Epic 12:** `10.11` runs after `12.3`, `12.4`, and `13.6`, and before `12.5`. Its first run ships `0.11.0` with `CAMERA_SCAN` GA, `PLACEMENT` BETA, and `QUICK_SCAN` BETA once `10.30–10.33` pass (fallback ships `CAMERA_SCAN` + `PLACEMENT` in `0.11.0` and `QUICK_SCAN` in `0.11.x`). `12.5` then cuts and promotes `v1.0.0` with the same flags after re-running release gates.
- **v1.1 & Epic 11:** `10.29` (after `10.7`) is v1.1 scope. Placement (`11.2`) uses Guided Scan only; Case recognition (`11.3`) stays on `app/src/camera/corner/` and OFF in `0.11.0`/`v1.0.0`.
