# Epic 1 Context: Turn and verify a cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Build a correct, pure-Kotlin cube engine (state, Moves, Notation, validation, Scrambles) on a gated multi-module scaffold, so a Learner can turn a validated cube on a 2D net with buttons, undo and redo, and one Completion Predicate can tell a helping Move from a harmful one. Exit criterion: a random Scramble applies, renders and validates with no mismatch. The epic also closes the early spikes and decisions (3D engine, scan recognition, schedule, reference devices) and provides on-demand benchmark harnesses. Stories 1.1–1.10 are done; only Story 1.11 remains: confirm the OpenGL ES rendering choice with frame-timing baselines on the two reference devices, then delete the SceneView spike code.

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

- **Rendering performance:** ≥ 30 FPS during face turns and View Rotation on the minimum reference device, 60 FPS target on mid-range. A quarter turn animates in ≤ 250 ms; face-turn input is acknowledged within 100 ms. For the rendering-choice confirmation, a GL frame-interval p90 ≤ 33.3 ms on physical devices confirms OpenGL ES; above that reopens the choice.
- **Correctness:** zero mismatches between engine and rendered state; pinned-seed property tests (M⁴ = identity, M·M⁻¹ = identity, sequence + inverse = identity). `:core:*` coverage ≥ 80%, cube package ≥ 90%.
- **Offline and privacy:** no `INTERNET`; v1.0 is camera-free and declares no runtime permissions (`CAMERA` returns only in v1.1); `allowBackup="false"`; no analytics/ads/crash SDK. Benchmark modules and spike removal must not break these manifest and dependency checks.
- **Compatibility:** minSdk 29; phones and tablets, portrait and landscape.
- **Decisions already closed (do not re-record):** reference devices (minimum Samsung Galaxy Tab S7 FE 5G, mid Samsung Galaxy S24 FE; neither truly low-end; API 29–30 coverage is emulator-only). The recognition spike scored 62.5% per sticker against a ≥ 99% target, which reopened the schedule decision; it was closed again by moving all camera recognition to v1.1 so v1.0 ships within ~16 weeks.

## Technical Decisions

- **Module boundaries:** `:core:domain`, `:core:solver`, `:core:application` are `kotlin("jvm")` with no Android, Compose, Room, CameraX or Hilt; only `:app` wires adapters to ports declared in `:core:application`. A `build-logic` convention check fails the build on violations. The JVM benchmark harness stays pure JVM; Android Macrobenchmark lives in its own Android test module targeting `:app`.
- **Build conventions:** versions only in `gradle/libs.versions.toml`; `build-logic` convention plugins; Kotlin 2.4.21 on the root classpath; AGP 9.5.0 built-in Kotlin (no `kotlin-android`, no kapt); compileSdk 37 / targetSdk 36 / minSdk 29; JDK 17.
- **App identity:** the app is now Cubentia — `applicationId` `com.exxerpro.cubentia`, Kotlin packages `dev.cubentia.*`. Benchmark target packages and instrumentation commands must use these.
- **CI gates:** every PR to `main` runs build, Spotless/ktlint, Android lint, JVM tests, Kover, privacy manifest checks and an AAB size check; detekt is non-blocking. Benchmarks must build in CI but run only on demand, never as a blocking device step.
- **Frame-timing method:** the 2D net (`Net2DRenderer`, Compose) is measured with Macrobenchmark frame timing. The 3D renderer is custom OpenGL ES 3.0 on `GLSurfaceView`, behind the `CubeRenderer` port; `FrameTimingMetric`/`FrameMetrics` cannot see GL surface frames, so GL timing uses an in-app `onDrawFrame` interval log, optionally traced for `TraceSectionMetric`. Cover View Rotation as well as face turns.
- **Spike leftovers to delete once the choice is confirmed:** `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/`, the `rubikxplorer.spike3d.sceneview` switch in `app/build.gradle.kts`, and the `sceneview` entry in the version catalog.
- **Rendering pipeline:** commit before animate; every input → `MoveIntent` → `MoveCommitted`; `CubeRenderer` never mutates state and exposes `renderedFacelets()`.
- **Conventions:** sealed results, errors as values; `RandomSource`/`Clock` injected; `FeatureFlags` port only, no `BuildConfig` checks. Rendering and device decisions go to the architecture memlog via `uv run _bmad/scripts/memlog.py append`.

## UX & Interaction Patterns

- Material3 theme from DESIGN.md tokens, light and dark following the system; bundled IBM Plex fonts; sticker colors identical in both themes.
- Cube stage with Undo · Redo · Reset toolbar; Move key bar in Notation; ≥ 48dp targets; plain-name TalkBack announcements; color-blind glyphs from one `StickerAppearance` table.

## Cross-Story Dependencies

- Story 1.11 builds on Story 1.10's Macrobenchmark module (per-device 2D net baselines) and the OpenGL ES renderer chosen in Story 1.8 (now the production 3D renderer delivered by Epic 5), re-measured with the `onDrawFrame` log.
- Story 1.11 belongs to the device batch run when both reference devices are connected, together with Story 3.8 (pruning-table delivery; closes Story 3.7), Story 12.2 (full on-device performance and compatibility, reusing the same harnesses) and the Story 12.1 physical accessibility pass. The device-free stories (including the pilot-path stories before 7.8) run first.
- Story 3.3's solve corpus runs through Story 1.10's JVM benchmark harness; physical solve-corpus confirmation belongs to Stories 3.8 and 12.2, not 1.11.
- Pending doc fix (not code): the architecture spine's performance note still says "Story 1.10 measures it"; it should name Story 1.11, via a `bmad-architecture` update.
