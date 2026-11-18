# Epic 11 Context: Scan shortcuts: Quick Scan, Placement and Case recognition

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Build three camera shortcuts on top of the Epic 10 scan pipeline for v1.1. Quick Scan reads the whole cube from two corner photos. Placement scans the Learner's cube and suggests the Course Stage to start from. Case recognition names the OLL/PLL Case from one top-corner photo. Each feature ships behind its own feature flag, merges with that flag OFF in release, and can be left OFF with no dead UI. They are the first things cut if work slips, in this order: Quick Scan drops to beta, then Case recognition, then Placement. Story 10.11 raises the release flags. Epic 11 never gates the v1.0 release work in Epics 12 and 13.

## Stories

- Story 11.1: Quick Scan from two corners
- Story 11.2: Start the Course from my cube (Placement)
- Story 11.3: Recognize an OLL/PLL Case

## Requirements & Constraints

- **Quick Scan:** two captures from opposite corners give all 54 stickers. If any sticker is uncertain or the state is invalid, the app asks for only the missing faces through the Guided 6-face Scan grid. It never guesses a state. It is recommended for GA only if ≥ 95% of scans reach a valid state after ≤ 3 corrections, in under 15 s. Otherwise it stays BETA.
- **Placement:** evaluate each Stage Predicate on an accepted Full scan and suggest the first unmet Stage. The Learner can accept it or choose another Stage. Accepting unlocks that Stage and marks every earlier Stage *skipped by placement*: never mastered, always revisitable (same rule as the skip-ahead check). MVP assumes a white cross first.
- **Case recognition:** compare the visible U and side stickers with every Case pattern under AUF and return the result less than 1 s after capture. At most 1% of single-Case results may name the wrong Case. If more than one Case fits, list the candidates and never pick one silently. If the first two layers are unsolved, say so and offer Placement. Offer an Algorithm Status change, but never apply one automatically.
- **Recognition test matrix:** at least one stickered and one stickerless cube, 4 lighting conditions, and the reference devices (Lenovo Tab M10 Plus 3rd Gen sets the minimum, S24 FE is mid, Tab S7 FE is a compatibility row that does not gate). At most 0.1% of stickers may be both misread and marked confident.
- **Privacy:** camera frames are processed in memory and never stored or sent. A manual route is always one tap away.
- **Size:** each feature must fit the APK size budget. Any new runtime (OpenCV or LiteRT) goes only in the camera source set.
- **Human gates:** 11.1 and 11.3 capture need device session C with real cubes. 11.3 matching and 11.2 are device-free and tested on the JVM.

## Technical Decisions

- **Feature flags:** the `FeatureFlags` port maps `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` to OFF, BETA or GA, with levels set per build type in `build-logic`. Each of these flags can be above OFF only when `CAMERA_SCAN` is above OFF in the same build type, or the build fails. Any of them above OFF adds the camera source set to that build type. Code never reads `BuildConfig` directly.
- **Debug levels follow wiring:** debug keeps a flag OFF until the PR that wires its main-app entry point raises it (normally to GA) in `FeatureFlagLevels.byBuildType["debug"]`. `FeatureFlagLevelsTest` pins that map. A story is accepted on the main-app build from its real entry point, never from a debug-only launcher.
- **Recognition:** only the CameraX adapter in `app/src/camera/` touches pixels. Main code reaches the camera only through the `ScanLauncher` port, which has an optional binding. When the binding is absent, every scan entry point is hidden. Quick Scan's corner detection and perspective unwarp live inside the adapter and emit ordinary `FaceSample`s.
- **Detection method:** Kotlin nearest-center classification with a per-cell histogram-mode reader, self-calibrated from the six face centers. It adds no runtime. LiteRT is the fallback, and OpenCV is rejected because of APK size. Story 11.1 builds within this decision.
- **Scan assembly and acceptance:** only `ScanAssembler` rotates samples into `Facelets` order. Quick Scan's per-face hand-off keeps the prompted top. The result is `ScanResult` Full or Partial. The reducer accepts a scan only if the Full result is valid, every UNCERTAIN sticker is resolved, and the revision is current. The scan sub-flow returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result. Only the caller dispatches it, and the scan flow never touches a session.
- **Placement:** reuse the existing writer `core/application/.../usecase/PlaceFromScan.kt`, which is the only writer of `StageSkippedByPlacement`. Add the predicate evaluation and the UI around it, and do not rewrite the writer. Placement never calls the Solver. Stage Predicates are content data (`Predicate` JSON), and all user-facing text is `Text(key, args)` content keys.
- **Progress log:** `progress_event` is append-only, and Stage status is a pure function over the current epoch. Write through the single serial writer with idempotency keys.
- **Case recognition:** `LastLayerMatcher` in `:core:domain` is the only definition of Case patterns and AUF. Never define patterns elsewhere. Partial views return the full candidate set. The LL is the face opposite the solved F2L color (yellow for the Course), rotated to U.

## UX & Interaction Patterns

- **Flag states:** when a flag is OFF, its entry point and every cross-link to it disappear. For example, *Start from my cube* disappears from Welcome and Recognize. When a flag is BETA, the feature shows the one shared BETA label component (the one *Test me* uses), with the camera tooltip "May misread colors in poor light. Manual entry always works." TalkBack reads "<feature>, BETA".
- **Quick Scan capture:** a corner outline (Y-shaped three-face guide) and a "1 / 2" counter. Any face that can't be read hands off to the per-face Guided grid. *Enter colors instead* stays visible.
- **Placement (Flow 4):** Welcome → *Start from my cube* → scan → review → Accept → a suggestion such as "Your cross is done and 2 of 4 corners are in. Start at *First-layer corners*?" with *Choose another Stage*. Skipped Stages show ◇ *Skipped by placement*.
- **Placement outcomes:**
  - Partly solved: suggest the first unmet Stage.
  - Nothing solved: suggest Stage 1.
  - Non-white cross: "Start with a white cross for this course."
  - At the last layer: offer *Faster last layer* or Stage 5.
  - Solved: "Your cube is solved — scramble it and scan again, or start Stage 1."
- **Welcome:** Welcome shows only on first launch and respects the persisted onboarding-complete state (#137). *Start from my cube* is also reachable from Recognize and from the skip-ahead failure "Let's find where to start".
- **Recognize (Flow 5):** Library → *Recognize case* → one top-corner photo.
  - Unique match: "OLL 45 · T-shape" opens the Algorithm detail with its Demonstration playing, and *Mark as Learning* is offered.
  - Ambiguous: "This could be one of 3 cases" with candidate tiles and *Scan another angle*.
  - Not at the LL: "The first two layers aren't solved yet." with *Start from my cube* when `PLACEMENT` is on.

## Cross-Story Dependencies

- Sequence: Epic 11 runs after the Epic 10 pipeline (10.2 → 10.3 → 10.14 → 10.4 → 10.5 → 10.15 → 10.7) and before 10.8 (field test) and 10.11 (release flag decision).
- 11.1 depends on 10.2 and 10.3 (and the 10.10 and 10.13 detection baselines).
- 11.2 depends on an accepted Full scan, which Story 10.5 provides. Its PR wires Welcome's `onStartFromCube` in `AppShell`, puts `PLACEMENT` back to GA in debug, and adds an AppShell test that taps the button and reaches the scan.
- 11.3 depends on 10.2 and on `LastLayerMatcher` and the Algorithm Library from Epic 9 (9.2). Its *Start from my cube* link appears only when `PLACEMENT` is on.
