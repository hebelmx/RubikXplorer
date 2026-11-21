# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera: scan face by face, refine and calibrate colors, solve straight from a scan, verify physical Moves with a Quick Check, and use 3D corner detection with face-by-face hand-over in Quick Scan. Promoted from v1.1 into v1.0 behind feature flags, camera code merges continuously to `main` with release flags OFF until release prep raises `CAMERA_SCAN`, `QUICK_SCAN`, and `PLACEMENT` (`CASE_RECOGNITION` decided separately) from measured accuracy, shipping honest GA/BETA labels and a manual fallback on every flow.

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

## Requirements & Constraints

- **Never guess:** No unvalidated or partial Scan Result reaches the Solver or tutor. 54 recognized colors alone are never valid; review must resolve all uncertain stickers and pass validation before *Accept* enables.
- **Accuracy & latency:** Guided 6-face Scan ≥ 98% valid after ≤ 3 corrections, ≥ 99.5% stickers correct indoors, < 45 s; Quick Scan (GA) ≥ 95% valid after ≤ 3 corrections (Guided fallback allowed), < 15 s (else BETA); Quick Check 0 false *verified*, ≥ 98% matches in ≤ 2 captures; ≤ 0.1% stickers misread *and* confident. Quick Scan 7-keypoint inference runs off the main thread in `< 40 ms` median on the minimum device, with 3D Red/Orange side-face separation validated to avoid forced 2D fallbacks.
- **Devices & cubes:** Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, minimum camera spec) and Galaxy S24 FE gate acceptance; Galaxy Tab S7 FE is reported. Sessions test stickered (scrambled and solved) and stickerless cubes.
- **Privacy & permissions:** Frames stay in memory only—never persisted, saved as photos, sent, or logged in release. `CAMERA` (`android.hardware.camera.any required="false"`) is the only runtime permission, declared in release only when a camera flag is above OFF and requested after a plain rationale. No `INTERNET`, backup, analytics/ads/crash SDK, ML Kit, or Play-services ML. Raising flags for v1.0 requires regenerating `docs/privacy-facts.json`, updating the website privacy policy, and re-confirming Play Data safety ("no data collected").
- **Size & binary gates:** Release install `< 30 MB` (single-ABI `arm64-v8a` accounting, including CameraX, transitive LiteRT, and `.tflite`); `libLiteRt.so` passes 16 KB ELF page alignment (`targetSdk 36`); R8 release builds run inference cleanly.
- **Fallback & cut order:** Every scan flow keeps *Enter colors instead*; denied permission from Solve opens the Editor directly. If Quick Scan corner detection slips, v1.0 can ship `CAMERA_SCAN` + `PLACEMENT` with `QUICK_SCAN` BETA or OFF (holding 10.17, zero LiteRT bytes).
- **Copy, a11y & acceptance:** Learner text lives in `en.json`; no mid-word breaks or clipping at 200% font on 600 dp; portrait phone viewfinder fits without scrolling. Stories accept on the main app build from their real entry point, never a debug-only launcher. `:core:*` stays pure Kotlin with ≥ 80% line coverage.

## Technical Decisions

- **Camera isolation & port:** Only the CameraX adapter touches pixels (`app/src/camera/`, never `app/src/main`). `:app` reaches it only via `ScanLauncher` (optional Hilt binding); absent binding hides scan entries, and a debuggable app with a raised camera flag and no binding fails at startup.
- **Hybrid detection:** Guided 6-face Scan and all sticker classification stay pure Kotlin (per-cell histogram-mode reader, six-center self-calibration, nearest-center classifier in `:core:domain`). Quick Scan 3D corner detection activates the pre-approved LiteRT fallback: a tiny in-house quantized 7-keypoint `.tflite` model (`com.google.ai.edge.litert`, trained on reproducible synthetic 3D renders covering stickered/stickerless cubes, hands, cluttered desks, and dark glossy reflections, fine-tuned/validated on Lenovo and S24 FE frames) predicts `[T, UL, LL, B, LR, UR, C]` in `app/src/camera/corner/` behind the JVM-testable `CornerDetector` interface, feeding `unwarp()`, `isValidCubeGeometry`, `CornerOverlaySmoother`, and `FrameReader`. OpenCV stays rejected on size.
- **Flag-composed builds:** `FeatureFlagLevels.byBuildType` is the sole source. Any camera flag (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`) above OFF composes `app/src/camera/`, CameraX, and `CAMERA`. The corner-model source set (`app/src/camera/corner/` Kotlin + `.tflite`) and LiteRT compose strictly when `QUICK_SCAN` or `CASE_RECOGNITION` is above OFF—zero LiteRT bytes when OFF. Shortcut flags require `CAMERA_SCAN` above OFF. Debug raises a flag only in the PR wiring its main-app entry point.
- **Assembly & ownership:** `ScanAssembler` (`:core:domain`) is the sole place samples rotate into `Facelets` order—for any-rotation Guided Scan, review retakes, and Quick Scan → Guided Scan hand-over (keeping captured poses fixed without reclassifying). Missing up-neighbor faces try quarter turns against `CubeValidator`; ambiguous/no-fit scans re-prompt only involved faces with a named top, capped at `MAX_REPROMPT_FACES` (2) on no-fit before opening review with the best fit (tied differing faces blank). `StickerClassifier` takes calibration as a parameter; cross-module confidence is categorical (`CONFIDENT`/`UNCERTAIN`; `VERIFIED`/`LIKELY`/`UNKNOWN`).
- **Prefill, contract & calibration:** Stickers prefill only when nearest color beats second by ≥ 10 ΔE; close calls stay blank and pre-selected. The scan sub-flow returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` via typed navigation result without touching session state; the reducer rejects `ScanAccepted` unless Full, valid, all uncertain stickers resolved, and `basedOnRevision` current. `CalibrationRepository` exclusively owns calibration (Room, via `UpdateCalibration`/`ResetCalibration`, never in `SessionState`/Checkpoint). Only `tutor/Assess` produces Certainty (`Partial` verifies matches only, never completed from `expected`; `Full` required to verify mismatch or `AdoptObserved`). Debug-only source sets hold developer tools only (fixture recorder to `Fixtures/live/`).

## UX & Interaction Patterns

- **Guided viewfinder:** Full-bleed 3×3 grid, auto-capture (~600 ms steady) + manual shutter, auto grid AF/AE/AWB, center-color prompt with glyph swatch (any rotation; named top only on re-prompt), mini net, inline glare/blur coaching; wrong orientation is a re-prompt.
- **Quick Scan & hand-over:** Minimal 3-line Y anchor + smoothed detected outline (no perspective hexagon sketch); visible cue (subtle flash, corner thumbnail, enlarged counter) and identical shutter sound on both captures (never haptics-only); "Try a plain, light surface." after ~3 s without detection. *Scan face by face* stays visible before, between, and after captures to finish remaining faces in Guided Scan while keeping captured faces (back restores Quick Scan intact).
- **Review net, paint mode & retake:** Uncertain/blank stickers pre-select with pulsing cursor; tap-sticker-then-color auto-advances. Long-press a palette swatch (or TalkBack *Lock this color*) to paint directly on tap (tap/long-press locked color, plain-tap another, or enter retake to exit). Invalid pieces outline with plain explanations; *Accept* goes straight to solving (no Editor detour). *Close* prompts "Discard this scan?" only after an edit or completed retake. *Retake faces* rescans 1–6 faces (any-rotation, no re-prompt, kept edits preserved, all-or-nothing on back) in Guided Scan review and combined Quick Scan review.
- **Calibration, flags & Quick Check:** Calibration samples six centers (rejecting logos/implausible swatches, auto-capture + *Keep*), offered from review only on uncertain reads and always in Settings. OFF hides all UI/rationale; BETA uses the shared BETA tag/tooltip with *Enter colors* primary. Quick Check is offered at Objective boundaries, Stage milestones, and mismatches (mismatch ladder rung 1), stays available at any step of Lessons, Solution Playback, and Algorithm practice (including mid-Algorithm), and is never required (*Done* always works).

## Cross-Story Dependencies

- **Sequence:** 10.9 → 10.10 → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.14 → 10.16 / 10.4 → 10.5 → 10.15 → 10.7) → Epic 11 → 10.8 → 10.18 → 10.17 → 10.11. Stories 10.1–10.10, 10.12–10.16, and Epic 11 are done; 10.18, 10.17, and 10.11 remain.
- **10.18 → 10.17:** 10.18 depends on 11.1 and precedes 10.17; 10.17 builds on 11.1, 10.16, and 10.18 so manual *Scan face by face* switches and automatic uncertain-face hand-overs share one path into combined review with retake.
- **10.11 & v1.0 release:** 10.11 runs after 10.18, 10.17, and Epics 14–15 to raise `CAMERA_SCAN`, `QUICK_SCAN`, and `PLACEMENT` (plus any `CASE_RECOGNITION` decision) for v1.0 from 10.8/device evidence, gating Stories 12.4–12.5 and updating `docs/privacy-facts.json` and the website privacy page.
