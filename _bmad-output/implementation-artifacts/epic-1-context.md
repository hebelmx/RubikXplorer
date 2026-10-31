# Epic 1 Context: Turn and verify a cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Build a correct, pure-Kotlin cube engine (state, Moves, Notation, validation, Scrambles) on a gated multi-module scaffold, so a Learner can turn a validated cube on a 2D net with buttons, undo and redo, and one Completion Predicate can tell a helping Move from a harmful one. Exit criterion (P0.1, weeks 1–2): a random Scramble applies, renders and validates with no mismatch. The epic also closes the week-2 spikes and decisions (3D engine, scan recognition, schedule, reference devices) and puts benchmark harnesses in place, so the rest of P0 is planned against evidence. Device-dependent work (Stories 1.9, 1.11) is deferred until the hardware is on hand and must land before Story 5.1 merges.

## Stories

- Story 1.1: Scaffold the gated multi-module project
- Story 1.2: Model the Cube State and face Moves
- Story 1.3: Parse and format every Move type in Notation
- Story 1.4: Validate a 54-sticker cube
- Story 1.5: Generate Scrambles
- Story 1.6: Turn a free cube on a 2D net with buttons
- Story 1.7: Tell a helping Move from a harmful one (Objective proof of concept)
- Story 1.8: Spike — choose the 3D rendering approach (OQ-3)
- Story 1.9: Spike — Guided 6-face Scan prototype and recognition test matrix (OQ-7, OQ-8)
- Story 1.10: Week-2 decision gate: schedule and benchmark harnesses
- Story 1.11: Reference-device baselines and SceneView spike removal

## Requirements & Constraints

- **Rendering performance:** ≥ 30 FPS during face turns and View Rotation on the minimum reference device; 60 FPS target on mid-range. A quarter turn animates in ≤ 250 ms; face-turn input acknowledged within 100 ms.
- **Solver performance:** 95% of a representative random-legal-state corpus (not only easy Scrambles) solved within 3 s on the reference device; report median, p95, memory and failure rate. Until the solver exists (Epic 3), the harness carries a placeholder corpus; Epic 3 runs the real corpus as a JVM proxy in CI, confirmed on device in the release-readiness epic.
- **Correctness:** zero mismatches between engine and rendered state; property tests with pinned seeds (M⁴ = identity, M·M⁻¹ = identity, sequence + inverse = identity). Cube package has the strongest coverage (≥ 90% assumed); all `:core:*` ≥ 80%.
- **Offline and privacy:** no `INTERNET`, `CAMERA` the only runtime permission, `allowBackup="false"`, no analytics/ads/crash SDK. Any new module (including benchmark modules) must not break these manifest/dependency checks.
- **Validation:** wrong color counts, impossible piece, twisted corner, flipped edge and parity each fail with a plain-language reason; 54 colors entered is never valid by itself.
- **Compatibility:** minSdk 29; phones and tablets, portrait and landscape.
- **Schedule (OQ-9):** decided — keep P0 at ~16 weeks with scan uses in P0. A Story 1.9 result below ≥ 99% per-sticker accuracy is a trigger to reopen it (camera then ships as BETA, Manual Cube Editor becomes the primary Solve action). The decision is already in the PRD memlog; record only what is missing.

## Technical Decisions

- **Module boundaries (AD-1):** `:core:domain`, `:core:solver`, `:core:application` are `kotlin("jvm")` with no Android, Compose, Room, CameraX or Hilt; `:app` depends on all three, `:core:solver` on application + domain, application on domain, nothing else. `:core:application` declares every port; only `:app` wires adapters. A `build-logic` convention check fails the build on violations. A benchmark harness must respect this: the JVM harness stays pure JVM, Android Macrobenchmark lives in its own Android test module targeting `:app`.
- **Build conventions:** versions only in `gradle/libs.versions.toml`; `build-logic` convention plugins (jvm-core, android-app, quality, release); Kotlin 2.4.21 pinned on the root classpath; AGP 9.5.0 built-in Kotlin (no `kotlin-android`, no kapt); compileSdk 37 / targetSdk 36 / minSdk 29; JDK 17; Gradle ≥ 9.6.0.
- **CI gates (AD-14):** every PR to `main` runs build, Spotless/ktlint, Android lint (incl. accessibility), JVM tests, Kover ≥ 80% on `:core:*`, AD-13 privacy checks and an AAB size check; detekt non-blocking. Instrumentation, UI and renderer-sync tests run only on release tags. Benchmarks must *build* in CI but run on demand, never as a blocking PR step requiring a device.
- **Reference devices (ARCH-OQ-1, closed):** minimum Samsung Galaxy Tab S7 FE 5G, mid Samsung Galaxy S24 FE; neither is truly low-end. API 29–30 coverage is emulator-only. Already recorded in the architecture memlog and spine — confirm, do not re-record.
- **Frame-timing method:** the 2D net (`Net2DRenderer`, Compose) can be measured with Macrobenchmark frame timing. The 3D renderer is custom OpenGL ES 3.0 on `GLSurfaceView` (OQ-3 decided by Story 1.8); `FrameTimingMetric`/`FrameMetrics` cannot see GL surface frames, so GL timing uses an in-app `onDrawFrame` interval log, optionally traced for `TraceSectionMetric`. Cover View Rotation as well as face turns. A physical-device p90 above 33.3 ms reopens the rendering choice, which stays behind the `CubeRenderer` port (AD-4).
- **Solve corpus:** measured by a JVM benchmark, not on-device Macrobenchmark.
- **Spike leftovers:** Story 1.8 left a SceneView variant (`app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/`, the `rubikxplorer.spike3d.sceneview` switch in `app/build.gradle.kts`, `sceneview` in the version catalog). It stays until Story 1.11 confirms the GL choice on device, then is deleted.
- **Cube model:** `CubeState = (cubies, orientation)`, immutable; home frame WHITE U, GREEN F, RED R; `Facelets` 54 × URFDLB row-major per Kociemba net. `CubeValidator` is the only `Facelets → CubeState` route; `Notation.parse`/`format` the only string↔Move conversion; Move equality by effect; WCA slices.
- **Rendering pipeline:** commit before animate; every input → `MoveIntent` → `MoveCommitted`; presentation queue fast-forwards above 3 queued; `CubeRenderer` never mutates state and exposes `renderedFacelets()`.
- **Conventions:** sealed results, errors as values; `RandomSource`/`Clock` injected; `Logger` port implemented only in `:app`; `FeatureFlags` port only, no `BuildConfig` checks; decisions recorded with `uv run _bmad/scripts/memlog.py append` (architecture memlog for rendering/devices, PRD memlog for schedule/recognition).

## UX & Interaction Patterns

- Material3 theme from DESIGN.md tokens, light and dark following the system; bundled IBM Plex Sans/Mono; sticker colors identical in both themes.
- Cube stage with Undo · Redo · Reset toolbar and VIRTUAL frame label; Move key bar labeled in `notation` with x/y/z group and "more" for wide/slice; ≥ 48dp targets; plain-name TalkBack announcements; color-blind glyphs W Y R O G B from one `StickerAppearance` table.

## Cross-Story Dependencies

- 1.1–1.8 are done. 1.10 is not device-gated; 1.9 (needs a stickerless cube) and 1.11 (needs both reference devices) are gated and deferred, and must happen before Story 5.1 merges.
- 1.10 builds on 1.6's `Net2DRenderer` free-cube screen as the Macrobenchmark target, and on 1.8's spike result for the schedule decision.
- 1.11 depends on 1.10's Macrobenchmark module (per-device 2D net baselines) and 1.8's OpenGL ES renderer (re-measure with the `onDrawFrame` log), then removes the SceneView spike; it closes the sprint action item for the spike deletion.
- Downstream: Story 3.3 runs the 10,000-state solve corpus through 1.10's JVM benchmark harness; Story 12.2 does the full on-device performance check reusing the same harnesses; Epic 5 uses the OQ-3 decision and `CubeRenderer` port; Epic 10 uses 1.9's test matrix and `FaceSample` fixture format.
- Pending doc fix (not this epic's code): the architecture spine's performance note still says "Story 1.10 measures it"; it should read Story 1.11, via a `bmad-architecture` update.
