# Epic 2 Context: Learn with a guided Lesson

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can open a data-defined Lesson, see its Objective, watch an explained Demonstration, and work through the Lesson in any of the four Teaching Modes (*Show me*, *Guide me*, *Test me*, *Let me try*) until its Completion Predicate is met. Stages 1–3 (Cube fundamentals, First cross, First-layer corners) are authored and tested. This epic turns the cube engine from Epic 1 into a tutor. Lessons are data, not hard-coded Move lists, so text, rules and thresholds can change without touching code. The exit bar is that the cross and corner Lessons pass scenario tests.

## Stories

- Story 2.1: Load validated Lesson content and strings
- Story 2.2: Predicate registry for Stages 1–3
- Story 2.3: Run a Lesson through the session reducer
- Story 2.4: Watch an explained Demonstration
- Story 2.5: Lesson screen with Show me and Guide me
- Story 2.6: Test me and Let me try
- Story 2.7: Author Stages 1–3 with path tests

## Requirements & Constraints

- Every Lesson declares prerequisites, Objective, Demonstration, learner action, Completion Predicate and Recovery Path. A schema test fails the build if any of them is missing.
- Every Lesson has an automated test that drives at least one legal path from its entry state to its predicate. A deliberately harmful Move must fail the predicate.
- Completion is decided by the predicate, never by counting Moves. All four Teaching Modes evaluate the same predicate.
- *Guide me* states the Objective (and each step's Goal) before revealing the Move. *Test me* and *Let me try* reveal the next Move only after a hint request.
- Demonstrations support play, pause, step forward/back, replay and speed 0.5×/1×/2×. Every step carries Goal, Preserve (shown as *Keep*), Watch and Notice (after the Move) as schema fields.
- Undo and redo work in-session, and any new Move clears redo. Reset asks "Reset the cube? This lesson's unsaved steps will be lost." only when progress would be lost.
- Correctness: the rendered facelets equal the virtual state at every drained step of a randomized Move/undo/redo suite (assumed 10,000 steps).
- Localizability: all Learner-facing text is in content data or resources, English only at launch. No string literals in `:core:*` or UI. `strings.xml` holds navigation chrome only.
- `:core:*` stays pure Kotlin with ≥ 80% line coverage. CI gates must stay green.
- Stage outcomes: Stage 1 teaches identifying centers, edges and corners and reading face Notation. Stage 2 places four cross edges relative to their centers. Stage 3 inserts four corners while preserving the cross.

## Technical Decisions

- **Content is data:** Stages, Lessons and tutor configuration are JSON under `:core:domain` resources (`content/schema/`, `content/stages/`, `tutor-config.json`, `content/strings/en.json`), parsed with kotlinx.serialization. `*.schema.json` is the single source of the content shape, and a test checks it against the Kotlin model. Each bundle carries `schemaVersion` and a `contentRevision` hash. Load failures are sealed results.
- **Lesson shape:** `{ id, stageId, prerequisites[], objective, entryState, demonstration, learnerAction, completion: Predicate, preserve: Predicate, recovery, steps[]: { goal, preserve, watch, notice, moves } }`. Every field is required.
- **Predicates:** a sealed `@Serializable` hierarchy with discriminator `"predicate"` and flat parameters. `allOf`/`anyOf` are the only combinators. Predicates are color-anchored (they find faces by center color) and orientation-invariant. Each implements `preservedPieces(state)` and a pure `ObjectiveProgress` measure, which Test me, Diagnosis and recovery all share. An unknown discriminator fails the content test.
- **IDs:** kebab-case, stable, in distinct namespaces, never reused once shipped (`stage-02-first-cross`, `lesson-02-03`).
- **Text:** `Text(key, args)` with key form `<area>.<contentId>.<field>[.<n>]` and ICU named args. The content test fails on duplicate, missing or argument-mismatched keys.
- **Session:** one active `LearningSession` app-wide, held by an `@Singleton SessionHost` in `:app` (never a ViewModel). It uses `SessionKind.LESSON`. The pure `reduce(SessionState, SessionEvent): Transition(state, effects, presentation)` runs on one confined dispatcher. Events carry `at: Instant`, and the reducer never reads `Clock`/`RandomSource`. `revision` increases monotonically. Async effects carry `effectId` + `revision`, and stale results are recorded as `EffectResultDiscarded`. `session/TRANSITIONS.md` is normative: for each event it lists the field mutated, whether it is undoable, and whether it clears redo. `teachingMode` is a `SessionState` field. Predicates never read it, and reveal is decided only by `TutorDecision.reveal`, never by a screen.
- **Commit before animate:** every input becomes a `MoveIntent` → `MoveCommitted`. Presentation is a queue of `Animate(move)`/`Snap` items, fast-forwarded when more than 3 are queued, and never dropped. Undo produces `Animate(inverse)`. `CubeRenderer` never mutates state and exposes `renderedFacelets()`. The 2D net renderer (`Net2DRenderer`) is the adapter for this epic.
- **Playback:** one pure `PlaybackController` over `List<Move>` in `:core:domain/playback`. Demonstrations, Solution Playback and the Library all reuse it. It is never checkpointed, and it is not a session.
- **Errors are values:** sealed results, explicit unknown variants, and effect failures dispatched as `…Failed` events.
- **Flags:** *Test me* is gated by `TEST_ME` through the `FeatureFlags` port, never `BuildConfig`.
- **Layout and accessibility:** layout is keyed on `WindowSizeClass`. Composables hold only ephemeral state. Stickers draw through the shared `StickerAppearance` table, and every gesture action has a `MoveButtonBar` equivalent.

## UX & Interaction Patterns

- **Navigation:** a navigation shell with a Learn tab (a flat Lesson list until Stage rows arrive later). Lesson is a full-screen task flow with a top app bar. System back closes the Lesson immediately with no confirmation.
- **Lesson panel order:** Objective (`title`) → step card (Goal, Keep, Watch before the Move; Notice after; Moves as `notation-chip`s) → Teaching Mode segmented switcher (active segment `accent`) → Hint (secondary) + **Next** (primary).
- **Layout:** compact is stacked (stage ≈ 45% height, min 280dp). Medium/expanded are side by side (stage ≈ 55%, panel capped at 560dp at expanded). An orientation change never changes session state.
- **Mode switching** keeps Cube State and progress, and the last mode is remembered. *Show me* plays on tap after the Objective. *Guide me* reveals the Move chip on tap. *Let me try* keeps Goal and Keep visible and hides only the Moves.
- **Demonstration on the net:** off-step pieces are dimmed and Keep pieces stay lit. The target gets the violet highlight outline (stickers are never recolored). The transport shows |◀ ◀ ▶/❚❚ ▶ ▶|, speed, a step counter in `data`, and the current Move in `notation`. Under reduce motion, the Demonstration steps instead of animating.
- **Objective met:** a subtle check, a brief violet pulse on the affected pieces, Notice text, and *Next* enabled. It never auto-advances past Notice.
- **Test me copy:** Progress "That works too — you're closer." · Neutral "That didn't change much. Try another turn." · Harmful: a neutral "That moved a piece you need" until Diagnosis arrives.
- **Voice:** precise and calm, Goal → Keep → Watch → Notice. No exclamation marks except at milestones. Mistakes appear in neutral ink, never red. No shake, red flash or auto-advance.

## Cross-Story Dependencies

- Builds on Epic 1: the cube model, Notation, `CubeValidator`, the 2D net renderer, the Move key bar, the theme, `StickerAppearance` and the `FeatureFlags` port.
- 2.1 (content loading and strings) → 2.2 (predicates plug into the schema) → 2.3 (the reducer evaluates predicates) → 2.4 (`PlaybackController`) → 2.5/2.6 (screen and modes) → 2.7 (authors Stages 1–3 on all of the above).
- Later epics extend this work: Epic 4 adds Diagnosis, the Hint Ladder, recovery and adaptive assistance, using `preservedPieces`, `ObjectiveProgress` and `TutorDecision`. Epic 5 swaps in the 3D renderer behind `CubeRenderer`. Epic 6 persists snapshots and Checkpoints. Story 7.2 replaces the flat Learn list, and Story 7.7 adds Welcome. Epic 8 adds the physical-mode transitions. Epic 3 reuses `PlaybackController` and has no dependency on this epic.
