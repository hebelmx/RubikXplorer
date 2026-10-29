# Epic 1 Context: Turn and verify a cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Build a correct, pure-Kotlin cube engine (state, Moves, Notation, validation, Scrambles) on the gated multi-module scaffold, so a Learner can turn a validated cube on a 2D net with buttons, undo and redo, and one Completion Predicate can tell a helping Move from a harmful one. Exit criterion (P0.1, weeks 1–2): a random Scramble applies, renders and validates with no mismatch, and the predicate proof of concept works end to end. The epic also closes the week-2 spikes (3D engine, scan recognition) and decisions (schedule, reference devices, baselines), so the rest of P0 is planned against evidence rather than assumptions.

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

- **Correctness:** zero mismatches between engine state and rendered state. Property tests (Kotest, pinned seeds) prove M⁴ = identity, M·M⁻¹ = identity, and sequence + inverse = identity. The cube package has the strongest coverage, assumed ≥ 90%; all `:core:*` stay ≥ 80%.
- **Offline and privacy:** works fully with no network; no `INTERNET`, `CAMERA` is the only runtime permission; no analytics, ads, crash SDK, account or PII; release logs never contain Cube States or scan samples (log only through the `Logger` port in `:app`).
- **Validation:** rejects wrong color counts, impossible pieces, a twisted corner, a flipped edge and a parity violation, each as its own failure with a plain-language reason and offending positions where local. 54 colors entered is never valid by itself.
- **Free cube controls:** buttons cover every Move type (face, wide, slice, Cube Rotation). Undo restores the exact previous state, redo restores an undone state, any new Move clears redo, and Reset needs no confirmation.
- **Accessibility:** every control has a content label and a ≥ 48dp target; Move keys announce plain names ("R prime: right face counter-clockwise"); color-blind glyphs are P0; contrast meets WCAG AA.
- **Compatibility:** minSdk 29; phones and tablets; portrait and landscape.
- **Recognition spike exit:** ≥ 99% of stickers correct under indoor light on two reference devices. Otherwise `CAMERA_SCAN` ships as BETA and the Manual Cube Editor becomes the primary Solve action.

## Technical Decisions

- **Layering:** hexagonal, functional core / imperative shell. `:core:domain` (cube, Notation, `CubeValidator`, predicates, `StickerAppearance`), `:core:application` (use cases, ports), `:app` (Compose UI, adapters, Hilt). `:core:*` are pure JVM with plain constructor injection; Hilt only in `:app`.
- **Cube State:** `CubeState = (cubies, orientation)`, immutable; `cubies` are corner/edge permutation and orientation relative to centers; `orientation` is one of 24 whole-cube rotations mapping U R F D L B to center colors.
  - Home frame: WHITE on U, GREEN on F, RED on R. Scrambles and fixtures are expressed from home.
  - Face Moves apply through `orientation`; x/y/z change only `orientation`; slice and wide Moves are their face-turn equivalent plus a rotation.
  - `isSolved` and every predicate ignore `orientation`.
- **Facelets:** `List<Color>` of 54 in URFDLB order, row-major per the Kociemba net (U: B edge on top; D: F edge on top; F, R, B, L: U edge on top). A diagram fixture of this layout is checked in. Stored form is a 54-char facelet string plus an orientation ID.
- **Single gates:** `CubeValidator.validate(Facelets): ValidationResult` is the only route from `Facelets` to `CubeState`. `Notation.parse`/`format` are the only string↔Move conversions; modules exchange `Move` values. Move equality is by effect (`R2 == R2'`); `format` emits the canonical form. Slices follow WCA: M like L, E like D, S like F.
- **Naming:** `Face` = `U R F D L B`; `Color` = `WHITE YELLOW GREEN BLUE RED ORANGE`.
- **Errors are values:** sealed results with "unknown" as an explicit variant; parse and validation errors never throw. Exceptions only for programmer error. User-facing text is `Text(key, args)` with keys `<area>.<contentId>.<field>[.<n>]` (domain messages under `msg.*`).
- **Randomness:** `NewScramble` uses an injected `RandomSource` and returns an explicit `List<Move>`; seeds only reproduce tests and are never persisted in place of Moves.
- **Predicates:** color-anchored (find faces by center color) and orientation-invariant; each implements `preservedPieces(state)` and a pure `ObjectiveProgress` measure.
- **Rendering:** commit before animate. Every input (button, gesture, accessibility action) becomes a `MoveIntent` mapped to the same `MoveCommitted` path. The presentation queue holds `Animate(move)`/`Snap` items (undo animates the inverse) and fast-forwards when more than 3 are queued, never dropping a Move. `CubeRenderer` never mutates state and exposes `renderedFacelets()` for tests; `Net2DRenderer` is the first adapter. The free cube is not a `LearningSession`; its history is a pure `MoveHistory` in `:core:domain`.
- **Sticker appearance:** one `StickerAppearance(Color) → {fill, glyph, pattern}` table serves every sticker surface.
- **Feature flags:** read through the `FeatureFlags` port only (closed enum incl. `CAMERA_SCAN`, levels OFF/BETA/GA per build type); never read `BuildConfig` directly.
- **3D engine:** SceneView (Filament) is an assumption until the OQ-3 spike; custom OpenGL ES is the alternative, contained behind `CubeRenderer`.
- **Recognition:** the camera adapter emits platform-neutral `FaceSample`s (9 CIELAB colors per face plus quality flags); a pure-JVM nearest-calibrated-center classifier produces categorical confidence; every scan result goes through `CubeValidator`. The Guided Scan uses a fixed overlay with fixed sample regions (no CV library).
- **Tests:** same package as the subject; fixtures in `src/test/resources/fixtures/`; JUnit Jupiter for JVM tests, AndroidX JUnit4 runner for instrumented/Compose tests.

## UX & Interaction Patterns

- **Theme:** Material3 from DESIGN.md tokens, light and dark following the system. Sticker tokens, `cubie-body` and highlight are identical in both themes. Text contrast ≥ 4.5:1, status ≥ 3:1.
- **Type:** bundled IBM Plex Sans and IBM Plex Mono (no network fonts); the `notation` role sits one step above surrounding text.
- **Spacing and shape:** 4/8/12/16/24/32/48dp; shapes `xs` 2dp stickers, `sm` 4dp keys, `md` 8dp cards/buttons, `lg` 12dp stage well. No pill buttons, no card shadows.
- **Cube stage:** a `surface-stage` well with an Undo · Redo · Reset icon toolbar; icons carry TalkBack labels and use `ink-disabled` when disabled; frame label VIRTUAL.
- **Move key bar:** keys labeled in `notation` (`U`, `U'`, `U2`…), a ⟳ x/y/z group, and "more" for wide and slice. Long-press shows the plain name; a light haptic tick on commit. Labels never change with View Rotation. Scrolls horizontally on compact widths, vertical grid on expanded.
- **Color-blind glyphs:** W Y R O G B legible at 16dp; drawn in `cubie-body` on light stickers (including orange) and `sticker-white` on dark ones.

## Cross-Story Dependencies

- 1.1 (scaffold and CI gates) is merged and gates everything.
- 1.3 builds on 1.2's Moves; 1.4 builds on 1.2's state and the `Text` key convention; 1.5 needs 1.2–1.4.
- 1.6 needs 1.2–1.3 (all Move types); 1.7 runs on the free cube from 1.6 via a debug overlay.
- 1.8 and 1.9 are time-boxed spikes in weeks 1–2; record decisions with `uv run _bmad/scripts/memlog.py append` (architecture memlog for 1.8, PRD memlog for 1.9's GA/BETA outcome).
- 1.10 needs 1.8 and 1.9; it decides the schedule (OQ-9, run `bmad-correct-course` if scope moves), names minimum and mid-tier reference devices (ARCH-OQ-1), and adds Macrobenchmark and JVM benchmark baselines.
- Downstream: Epic 3 uses `CubeValidator`; Epic 5 uses the OQ-3 decision and the `CubeRenderer` port; Epic 10 uses the recognition test matrix and the `FaceSample` JSON fixture format.
