# Epic 1 Context: Turn and verify a cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Stand up a gated, from-scratch Android repo whose CI enforces the architecture, offline and privacy rules from the first commit. On top of it, build a correct cube engine (state, Moves, Notation, validation, Scrambles). A Learner can turn a validated cube on a 2D net with buttons, undo and redo, and one Completion Predicate tells a helping Move from a harmful one. Exit criterion (P0.1, weeks 1–2): a random Scramble applies, renders and validates with no mismatch, and the predicate proof of concept works end to end. The epic also closes the week-2 spikes (3D engine, scan recognition) and decisions (schedule, reference devices, baselines), so the rest of P0 is planned against evidence.

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
- Story 1.10: Week-2 decision gate: schedule, reference devices and baselines

## Requirements & Constraints

- **Offline:** a fresh install with no network must work fully. No network permission is declared, and camera is the only runtime permission.
- **Privacy:** no account, no PII, no analytics SDK, no ads, no crash SDK. Data stays in app-private storage only, with no cloud backup. The Play data-safety form declares "no data collected". Android vitals is the only field signal.
- **Code quality:** `:core:*` modules are pure Kotlin with ≥ 80% line coverage. Static analysis and formatting are enforced in CI, and every merge to `main` must build, test and lint green.
- **Correctness:** zero mismatches between engine state and rendered state. Property tests (pinned seeds) prove inverse restores, M⁴ = identity, and sequence + inverse = identity. Illegal states are rejected. The cube package has the strongest coverage, assumed ≥ 90%.
- **Buttons:** cover every Move type: face, wide, slice and Cube Rotation. Undo restores the exact previous state, redo restores an undone state, and any new Move clears redo. Reset on the free cube needs no confirmation.
- **Validation:** rejects wrong color counts, impossible pieces, a twisted corner, a flipped edge and a parity violation, each with a plain-language reason. Having all 54 colors entered never makes a cube valid by itself.
- **Accessibility:** every control has a content label and a ≥ 48dp touch target. Move keys announce plain names. Color-blind glyphs are P0. Contrast meets WCAG AA.
- **Compatibility:** minSdk 29; phones and tablets; portrait and landscape.
- **Spike exit:** ≥ 99% of stickers correct under indoor light on two reference devices. Otherwise `CAMERA_SCAN` ships as BETA and the Manual Cube Editor becomes the primary Solve action.

## Technical Decisions

**Stack (pinned; versions live only in `gradle/libs.versions.toml`):**
- Kotlin 2.4.21, covering KGP plus the compose and serialization plugins, pinned on the root build classpath.
- AGP 9.5.0 with built-in Kotlin. No `kotlin-android` plugin and no kapt.
- Gradle ≥ 9.6.0, JDK toolchain 17.
- SDK levels: compileSdk 37, targetSdk 36, minSdk 29.
- Compose BOM 2026.09.00 (Compose 1.12.1, Material3 1.4.0).
- DI and storage: Hilt 2.60.1, KSP 2.3.12, Room `androidx.room3` 3.0.3 (KSP).
  - KSP 2.3.12 is unconfirmed against Kotlin 2.4.21. Check it at scaffold, or pin a working KSP version and record it in the architecture memlog.
- CameraX 1.6.2. kotlinx.serialization 1.12.0, coroutines 1.11.0.
- Testing: JUnit Jupiter 6.1.x for JVM tests, Kotest 6.2.6 for property tests, AndroidX JUnit4 runner for instrumented and Compose UI tests, Kover 0.9.11.
- Formatting and analysis: Spotless 8.10.x + ktlint 1.8.0, Android lint, detekt 2.0.0-alpha (non-blocking).
- 3D: SceneView 4.62.0 (Filament 1.72.x) is an assumption until the OQ-3 spike. The Solver is in-house, with no dependency. CI runs on GitHub Actions.

**Module layout and dependency direction (hexagonal; functional core, imperative shell):**
- `:core:domain`: cube, Notation, `CubeValidator`, content, predicates, tutor, recognition, playback, `StickerAppearance`.
- `:core:solver`: clean-room Kociemba `CubeSolver`.
- `:core:application`: use cases, every port, the session reducer.
- `:app`: Compose UI, ViewModels, `SessionHost`, Hilt and all adapters.
- Allowed edges: app → {application, solver, domain}; application → domain; solver → {application, domain}. Nothing else.
- All `:core:*` modules are `kotlin("jvm")` with no Android, Compose, Room, CameraX or Hilt dependency. A `build-logic` convention check must fail the build when one is added.
- `build-logic` holds convention plugins `jvm-core`, `android-app`, `quality` and `release` (R8 keep rules, flag levels).
- Package root is `dev.rubikxplorer.<module>.<area>` (assumption). Structural seed areas: domain `cube/ content/ predicate/ tutor/ lastlayer/ recognition/ playback/ appearance/`; application `session/ usecase/ port/`; app `session/ ui/ render/ camera/ data/ di/`.

**CI gates (every PR to `main`):**
- Must pass: the build, Spotless/ktlint, Android lint (including accessibility), JVM tests, Kover ≥ 80% on `:core:*`, the privacy checks below, and an AAB size check against the < 30 MB install target.
- detekt runs non-blocking until it has a stable release for Kotlin 2.4.
- Release tags (`vX.Y.Z`) also run instrumentation, UI and renderer-sync tests.

**Privacy enforced by the build (AD-13):**
- The merged manifest has no `INTERNET`, and `CAMERA` is the only runtime permission.
- `android:allowBackup="false"`, with `dataExtractionRules` that exclude everything.
- Release logs never contain Cube States or scan samples. Logging goes through a `Logger` port implemented only in `:app`.
- CI fails on any violation, or on any dependency from an analytics/ads/crash-SDK denylist.

**Feature flags (AD-18):**
- A `FeatureFlags` port in `:core:application` maps the closed enum `{QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, CAMERA_SCAN, ADAPTIVE_ASSISTANCE}` to `{OFF, BETA, GA}`.
- The levels are set per build type in `build-logic`; `debug` enables every flag. Flags are read-only at runtime, and the adapter lives in `:app`.
- No feature code reads `BuildConfig` directly, and a test enforces this.

**Build and release:**
- AAB only, with Play App Signing; the upload keystore is in GitHub secrets.
- `debug` adds StrictMode and the fixture recorder. `release` uses R8 with resource shrinking, and the R8 mapping file is uploaded.
- `versionCode` is the CI run number. Development is trunk-based on `main`, with Conventional Commits.

**DI, errors and randomness:**
- Hilt only in `:app`; `:core:*` use plain constructor injection.
- Domain and port functions return sealed results, with "unknown" as an explicit variant. Exceptions are only for programmer error.
- `NewScramble` uses an injected `RandomSource` and returns an explicit `List<Move>`. Seeds are used only to reproduce tests and are never persisted in place of Moves.

**Cube model:**
- `CubeState = (cubies, orientation)`, where `orientation` is one of 24 whole-cube rotations.
- Home frame: WHITE on U, GREEN on F, RED on R.
- Face Moves apply through `orientation`. x/y/z change only `orientation`. Slice and wide Moves apply as their face-turn equivalent plus a rotation.
- `isSolved` and all predicates ignore `orientation`.
- `Facelets` is 54 entries in URFDLB order, row-major per the Kociemba net: U has the B edge at the top, D has the F edge at the top, and the side faces have the U edge at the top. A diagram fixture of this layout is checked in.
- `CubeValidator.validate` is the only route from `Facelets` to `CubeState`.
- `Notation.parse`/`format` are the only string↔Move conversions. Parse errors are sealed values, never exceptions. Move equality is by effect (`R2 == R2'`), and `format` emits the canonical form. Slices follow WCA: M like L, E like D, S like F.
- Naming: `Face` = `U R F D L B`; `Color` = `WHITE YELLOW GREEN BLUE RED ORANGE`.
- Predicates are color-anchored and orientation-invariant. Each one exposes `preservedPieces` and a pure `ObjectiveProgress`. User text is `Text(key, args)`.

**Rendering:**
- Commit before animate: every input becomes a `MoveIntent`, which maps to a `MoveCommitted` event. The presentation queue holds `Animate`/`Snap` items and fast-forwards when more than 3 are queued.
- `CubeRenderer` never mutates state. It exposes `renderedFacelets()` for tests.
- `Net2DRenderer` is the first adapter.
- One `StickerAppearance(Color) → {fill, glyph, pattern}` table serves every sticker surface.

**Tests:** same package as the subject; fixtures in `src/test/resources/fixtures/`; property tests use pinned seeds.

## UX & Interaction Patterns

- **Theme:** a Compose Material3 theme built from the DESIGN.md tokens, light and dark, following the system with a Settings override.
  - Sticker tokens, `cubie-body` and highlight are identical in both themes.
  - Contrast tests assert text ≥ 4.5:1 and status ≥ 3:1 as icons.
- **Fonts:** IBM Plex Sans and IBM Plex Mono, bundled with no network. The `notation` role sits one step above surrounding text, and `data` uses tabular figures.
- **Spacing and shape:** spacing 4/8/12/16/24/32/48dp, 48dp touch minimum. Shapes: `xs` 2dp for stickers, `sm` 4dp for keys, `md` 8dp for cards and buttons, `lg` 12dp for the stage well. No pill buttons and no card shadows.
- **Cube stage:** a `surface-stage` well with an Undo · Redo · Reset icon toolbar. Icons carry TalkBack labels and use `ink-disabled` when disabled. Frame label VIRTUAL.
- **Move key bar:** keys labeled in `notation` (`U`, `U'`, `U2`…), a ⟳ x/y/z group, and "more" for wide and slice turns. Long-press shows the plain-language name. A light haptic tick fires on commit. Labels never change with View Rotation. The bar scrolls horizontally on compact widths and is a vertical grid on expanded widths.
- **Color-blind glyphs:** W Y R O G B from `StickerAppearance`, legible at 16dp. The glyph is drawn in `cubie-body` on light stickers (including orange) and in `sticker-white` on dark ones.

## Cross-Story Dependencies

- 1.1 gates everything. After it merges, run `bmad-project-context` (refresh) so `AGENTS.md` lists the real `./gradlew` commands.
- 1.3 builds on 1.2's Moves. 1.4 builds on 1.2's state, plus the `Text` key convention. 1.5 needs 1.2–1.4.
- 1.6 needs 1.2–1.3 (all Move types), and 1.7 runs on the free cube from 1.6.
- 1.8 and 1.9 are time-boxed spikes in weeks 1–2. Record their decisions in the architecture memlog (`uv run _bmad/scripts/memlog.py append`) and the PRD memlog.
- 1.10 needs 1.8 and 1.9. It decides the schedule (OQ-9) and names the reference devices (ARCH-OQ-1).
- Downstream epics depend on this one:
  - Epic 5 uses the OQ-3 decision and the `CubeRenderer` port.
  - Epic 10 uses the recognition test matrix and the `FaceSample` fixture format.
  - Epic 3 uses `CubeValidator`.
  - All later epics rely on the CI gates and `FeatureFlags`.
