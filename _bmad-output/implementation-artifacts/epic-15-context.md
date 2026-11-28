# Epic 15 Context: Method-aware solutions

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can ask for a solution to any valid cube that follows the method they are learning: beginner layer-by-layer, beginner first two layers plus 2-look OLL/PLL, or beginner first two layers plus full OLL + PLL. They get this instead of the shortest path, and each segment is tagged with the Stage or Case it practices. Today the Solver's optimal solution teaches no method (GitHub #210). The epic links the Solve tab to the Course and the Algorithm Library without making the Solver explain its own output. It is in v1.0 scope and runs 15.1 -> 15.2 -> 15.3 -> 15.4, before release Stories 12.3, 12.4 and 12.5. Story 15.4 makes the first method solution in a process arrive in time: on-device measurement showed that the first solve is slow only because of one-time warm-up (class loading and JIT), not search.

## Stories

- Story 15.1: Build a beginner-method solution from the Course's Stages
- Story 15.2: Last-layer segments for 2-look and full OLL + PLL, preferring my Algorithms
- Story 15.3: Choose a solution style in Solution Playback
- Story 15.4: Warm the method solver once, and give debug builds a 5 s solve deadline

## Requirements & Constraints

- **FR-30:** for any valid Cube State, offer a method-shaped solution in three styles: beginner, 2-look, full OLL + PLL. The Solve tab still defaults to the optimal solution (FR-15).
- Each segment is tagged with a Stage ID or Case ID. Its label comes from content, never from a note about Solver output.
- Last-layer segments use Library Algorithms only. When several fit, prefer one the Learner has marked *Learning* or *Learned*.
- The suggested method comes from the Learner's progress (for example 2-look once *Faster last layer* is unlocked).
- **Correctness:** test on the solved state plus at least 10,000 random legal states, including deep Scrambles. Every result must solve its input, and no segment may break a piece placed by an earlier segment. The 2-look and full styles may use only Algorithms from the chosen set, plus U adjustments. Report median and p95 time on the JVM.
- **NFR-3 (unchanged):** 95% of random legal states solved within 3 s on the minimum reference device (Lenovo Tab M10 Plus 3rd Gen, TB128XU), with median, p95, memory and failure rate reported. This is a corpus performance target, not the timeout. It is met today.
- **Solve deadlines:** 3 s in release and benchmark builds, 5 s in debuggable builds (`ApplicationInfo.FLAG_DEBUGGABLE`). This applies to `SolveCube`, `SolveByMethod` and `PlanRecovery`. There are no device tiers.
- **Measured baseline:** on the Lenovo benchmark build the cold first method solve peaks at about 1.3 s, and the 2nd solve in the same process stays under 0.4 s. The Lenovo debug build's cold first solve takes 3.5-4.5 s. A busy Galaxy Tab S7 FE (the mid-tier comparison) exceeded 3 s on 22 of 75 cold first solves. Story 15.4's device gate re-runs this protocol through `MethodSolveBenchmarkActivity`, with the production warm-up, and records the results in `docs/benchmarks.md` § Method-solve deadline.
- **NFR-12:** `MethodSolve` is pure Kotlin with no Android dependencies and at least 80% line coverage. `./gradlew spotlessCheck build` must pass.
- **FR-4:** Stage order and completion predicates define the beginner segments. Completion is decided by predicates, never by counting Moves.
- **FR-16:** Solution Playback notes keep the fixed face/direction/piece-group template and never claim a strategic purpose.
- **Out of scope:** CFOP F2L, Roux and ZZ segments. Re-explaining arbitrary Solver output in beginner terms is also out of scope.

## Technical Decisions

- **AD-21: method solutions come from the Course, not the Solver.**
  - `MethodSolve` lives in `:core:domain`. It returns ordered segments `(tag: StageId | CaseId, moves)` and **never calls `CubeSolver`**.
  - First-two-layer segments (Stages 2-4) follow the Stage completion predicates in order. Each is found by the bounded `preservedPieces` search that `RecoveryPlanner` uses.
  - Last-layer segments go through `LastLayerMatcher` with Library Algorithms only:
    - beginner: the Stages 5-6 Lessons' Algorithms
    - 2-look: the 2-look Lessons' Algorithms
    - full: one Algorithm per Case
  - It runs as an Effect with the AD-8 deadline. Every result is re-verified by applying it to its input before it is shown.
  - After solver preparation, one throwaway `MethodSolve` warm-up runs off-main. It is untimed, cancellable and outside every deadline. Its result is discarded and never reaches a session. A failure or cancellation is silent (logged in debug builds only). A real solve must not queue behind the warm-up.
- **AD-8: `CubeSolver` (clean-room Kociemba) only finds paths.**
  - Only `SolveCube` calls it. `PlanRecovery` (`RecoveryPlanner`) and `SolveByMethod` (`MethodSolve`) never call it, but they use the same Effect-with-timeout shape and the same deadline.
  - Results are `SolveResult.{Solved, Invalid, TimedOut, Failed}`. Re-labelling Kociemba output as a beginner solve is forbidden.
  - The deadline is one value chosen once per process in `:app` and passed to the use cases (`SolveCube` gains a deadline constructor parameter). Unit tests cover both values.
  - `TwoPhaseCubeSolver`'s own search cap is unchanged. Preparation, such as pruning tables, always runs outside the deadline.
- **AD-6: content is data.**
  - Stages, Lessons and Algorithms are JSON under `:core:domain` resources.
  - Predicates are color-anchored and orientation-invariant, and implement `preservedPieces(state)` and `ObjectiveProgress`. The only combinators are `allOf` and `anyOf`.
  - Stage, Lesson, Case (`case-oll-45`) and Algorithm (`alg-oll-45-a`, which declares its `caseId`) IDs are separate, stable namespaces. Lessons reference Algorithm IDs and never copy Notation.
  - Learner-facing text is `Text(key, args)` from `content/strings/<locale>.json`, and the content test checks the keys.
- **AD-19:** `LastLayerMatcher` is the only definition of Case patterns and AUF.
  - The canonical frame puts the last layer (YELLOW) on U.
  - OLL is matched by orientation mask under pre-AUF. PLL is matched by permutation under pre- and post-AUF and `y^k` viewing.
- **AD-20:** one pure `PlaybackController` drives all playback.
  - A Move list may be split into labeled segments. A label is a content `Text` key naming the Stage or Case, never why a Move was chosen.
  - Per-Move notes come only from `MoveNote.of(move)`.
- **AD-3:** method playback is a `SOLVE_PLAYBACK` session (no new `SessionKind`). It is not resumed after the app closes. Stale Effect results are discarded by `revision`/`effectId`.
- **AD-7:** `Notation.parse`/`format` are the only string-to-Move conversions. Move equality is by effect.

## UX & Interaction Patterns

- Solution Playback from the Solve tab opens on *Shortest*. A *Solution style* control offers *Beginner*, *2-look* and *Full OLL + PLL*. The method suggested from progress is not preselected: its chip carries a "Suggested" badge, and one tap starts it.
- Choosing a style restarts playback at step 1 with segment labels. It works in Physical-cube Mode, where *Done* advances one step.
- While pending, show "Building a step-by-step solution…". On timeout, show "Couldn't build that solution in time. The shortest one is still here." and keep *Shortest*. The copy is unchanged by 15.4. On debug builds the timeout can take up to 5 s to appear.
- Use the shared playback transport (UX-DR14): Move strip, scrubber snapping to Move boundaries, and a MoveNote line. The 3D/2D view switch also applies.

## Cross-Story Dependencies

- 15.2 extends 15.1's `MethodSolve`. 15.3 depends on both, and on Solution Playback (Epic 3), physical follow-along (Epic 8) and the view switch and Move strip (Stories 12.14-12.15).
- 15.4 builds on 15.1-15.3 (`SolveByMethod` and the style control). It touches `SolveCube` (Epic 3) and `PlanRecovery` (Epic 4). Its acceptance needs device sessions on the Lenovo TB128XU (benchmark and debug builds, plus 5 cold-start first style taps on debug) and on the Galaxy Tab S7 FE.
- Shared seams: Epic 14 (#208) uses the same labeled segments. Algorithm Statuses and 2-look/full Algorithm content come from Epic 9. `RecoveryPlanner` comes from Epic 4.
