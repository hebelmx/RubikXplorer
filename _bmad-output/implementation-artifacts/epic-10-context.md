# Epic 10 Context: Scan my cube (v1.1, flag-gated)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can put their real cube into the app with the camera: scan it face by face, correct and calibrate the colors, solve it straight from the scan, and confirm physical Moves with a Quick Check. Camera work merges to `main` continuously, but every camera flag stays OFF in release until the PO raises one, so tester-fix releases stay exactly as camera-free as v1.0. Each feature ships with an honest GA or BETA label based on measured accuracy, and every scan flow keeps a manual fallback.

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

## Requirements & Constraints

- **Never guess.** No unvalidated or partial Scan Result reaches the Solver or the tutor. 54 recognized colors are never valid on their own; review must resolve every uncertain sticker and the state must be valid before *Accept* enables.
- **Recognition targets (provisional):** Guided 6-face Scan at least 98% valid after at most 3 corrections, at least 99.5% stickers correct indoors, under 45 s for a repeat user; at most 0.1% of stickers misread *and* marked confident; Quick Check gives 0 false *verified*, confirms at least 98% of matching cubes, at most 2 captures. Measured per device: Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (gating), Galaxy Tab S7 FE (reported).
- **Privacy/offline:** frames are processed in memory only, never persisted or sent, and never logged in release. Camera is the only runtime permission, requested at first scan after a rationale card; no network permission ever. No ML Kit or Play-services ML.
- **Size:** install stays under 30 MB including any recognition code.
- **Fallback:** every scan feature has a manual route one tap away (*Enter colors instead*); permission denied from Solve opens the Manual Cube Editor directly.
- **Copy and accessibility:** all Learner-facing text in content strings; at 200% font on the Lenovo's 600 dp width no mid-word breaks or clipping; on a phone in portrait the viewfinder, prompt and controls fit without scrolling.
- **Main-app acceptance:** a camera story is accepted on the main app build from its real entry point (normally the Solve tab), never via a debug-only launcher. Device sessions use the Lenovo and the S24 FE with stickered and stickerless cubes.
- `:core:*` stays pure Kotlin with at least 80% line coverage.

## Technical Decisions

- **Recognition pipeline:** only the CameraX adapter touches pixels; it lives in the build-time camera source set (`app/src/camera/`), never `app/src/main`. It emits `FaceSample` (center color, optional up-neighbor only when a top was named, 9 CIELAB samples in viewfinder order, quality flags, pose).
- **Detection method (decided):** Kotlin nearest-center classification with a per-cell histogram-mode reader, self-calibrated from the six centers; no new runtime. LiteRT is the fallback if field use demands; OpenCV rejected on size.
- **Domain ownership:** `ScanAssembler` (in `:core:domain`) is the only place samples are rotated into `Facelets` order; `StickerClassifier` takes calibration as a parameter. Results are `ScanResult.Full(Facelets + confidence)` or `Partial`. Confidence crossing module boundaries is categorical only (`CONFIDENT`/`UNCERTAIN`; Certainty `VERIFIED`/`LIKELY`/`UNKNOWN`).
- **Rotation:** faces may be shown in any rotation; the assembler tries the quarter turns and keeps states `CubeValidator` accepts. Ambiguous or no-fit results re-prompt only the faces involved with a named top, capped at `MAX_REPROMPT_FACES` (2) when nothing is valid; beyond that, go to review with the best fit (most legal pieces), differing faces left blank.
- **Close-call prefill rule:** a sticker is prefilled only when its nearest calibrated color beats the second by at least 10 ΔE; close calls stay blank and pre-selected.
- **Scan sub-flow contract:** returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result; only the caller dispatches it, and the scan flow never touches a session. The reducer rejects `ScanAccepted` unless Full, valid, all uncertain stickers resolved, and `basedOnRevision` current.
- **Calibration:** owned solely by `CalibrationRepository`, written only by `UpdateCalibration` / `ResetCalibration`; never in `SessionState` or a Checkpoint.
- **Quick Check / Certainty:** only `tutor/Assess` produces Certainty. A `Partial` is checked for internal consistency only, never completed from `expected`, and verifies only a match; only a Full observation verifies a mismatch. A Partial must be completed to Full before `AdoptObserved`.
- **Flags compose the build:** `FeatureFlagLevels.byBuildType` is the single source; a build type with any of `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` above OFF gets the camera source set, CameraX deps and the `CAMERA` permission; otherwise none. Shortcut flags require `CAMERA_SCAN` above OFF. Debug raises a flag only once its main-app entry point is wired. BETA uses the one shared BETA label component.
- **Port:** `:app` reaches the camera only via `ScanLauncher` with an optional Hilt binding from the camera source set; no binding hides every scan entry point, and a debuggable app with a camera flag above OFF but no binding fails at startup.
- **Privacy facts:** `docs/privacy-facts.json` is derived from the release manifest; it changes only when a release camera flag is raised.
- Debug-only source sets hold developer tools only (fixture recorder writing to `Fixtures/live/<device>_<cube>_<light>/`).

## UX & Interaction Patterns

- **Viewfinder:** full-bleed preview, 3×3 grid, auto-capture after ~600 ms aligned and steady plus manual shutter, prompt names the center color with a glyph swatch, mini net progress, AF/AE/AWB re-meter on the grid automatically, inline coaching ("Too much glare — tilt the cube"); a wrong orientation is a re-prompt, not an error.
- **Review net:** uncertain stickers pre-selected (dashed ring, "?" badge); tap a sticker then a color, with auto-advance to the next blank; invalid pieces outlined with plain explanations ("This corner shows two whites."); *Accept* is the only confirmation before solving. *Close* asks "Discard this scan?" only after a sticker change or completed retake.
- **Retake mode (Guided Scan review only):** *Retake faces* switches to face selection ("Tap each face to scan again."), *Retake N faces* rescans only those faces with the any-rotation prompt and no re-prompt; kept faces keep their edits; backing out restores review unchanged (all or nothing).
- **Calibration:** six centers in turn, sampled from the ring around the center; logo or implausible swatches get a plain re-prompt; auto-capture with *Keep* confirmation. Offered from review only on uncertain reads; always available in Settings.
- **Flag OFF:** no entry point, string, dead button or permission rationale is visible. When `CAMERA_SCAN` is BETA, *Enter colors* is primary and scan carries the BETA tooltip "May misread colors in poor light. Manual entry always works."
- **Quick Check:** offered at Objective boundaries, Stage milestones and mismatches, never required (*Done* always works); it is the first rung of the mismatch ladder.

## Cross-Story Dependencies

- Order: 10.9 → 10.10 → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.14 → 10.16 / 10.4 → 10.5 → 10.15 → 10.7) → Epic 11 → 10.8 → 10.11. All but 10.11 and 10.16 are done.
- 10.16 builds on 10.3 review and 10.14 assembly; its accepted result follows 10.15's caller path (solve and playback, already-solved state, or Editor fallback) unchanged. Quick Scan review retake is a later follow-up.
- 10.11 runs per release that raises a camera flag and depends on 10.8's recommendation and Epic 12/13 release work (12.3–12.5, 13.6); it also updates the separate site repository's privacy page.
- Epic 10 never blocks tester-feedback fixes: camera stories merge only with release camera flags OFF.
