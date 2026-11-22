# Epic 15 Context: Method-aware solutions

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can ask for a solution to any valid cube that follows the method they are learning: beginner layer-by-layer, beginner first two layers plus 2-look OLL/PLL, or beginner first two layers plus full OLL + PLL. They get this instead of the shortest path, and each segment is tagged with the Stage or Case it practices. Today the Solver's optimal solution teaches no method (GitHub #210). This epic links the Solve tab to the Course and the Algorithm Library without making the Solver explain its own output. The 2026-11-20 promotion moved it into v1.0 scope. It runs 15.1 -> 15.2 -> 15.3, before release Stories 12.3, 12.4 and 12.5.

## Stories

- Story 15.1: Build a beginner-method solution from the Course's Stages
- Story 15.2: Last-layer segments for 2-look and full OLL + PLL, preferring my Algorithms
- Story 15.3: Choose a solution style in Solution Playback

## Requirements & Constraints

- **FR-30:** for any valid Cube State, offer a method-shaped solution in three styles: beginner, 2-look, full OLL + PLL. The Solve tab still defaults to the optimal solution (FR-15).
- Each segment is tagged with the Stage ID or Case ID it practices. Its label comes from content and never from a note about Solver output.
- Last-layer segments use Library Algorithms only. When several fit, prefer one the Learner has marked *Learning* or *Learned*.
- The suggested method comes from the Learner's progress (for example 2-look once *Faster last layer* is unlocked). The Learner can change it.
- **Correctness (FR-15 corpus):** test on the solved state plus at least 10,000 random legal states, including deep Scrambles. Every result must solve its input, and no segment may break a piece placed by an earlier segment. The 2-look and full styles may use only Algorithms from the chosen set, plus U adjustments. Report median and p95 time on the JVM.
- **NFR-3:** the Solver's time budget is 95% of random legal states within 3 s on the minimum reference device (Lenovo Tab M10 Plus 3rd Gen), with median, p95, memory and failure rate reported. Use it as the reference budget for method-solve timing.
- **NFR-12:** `MethodSolve` is pure Kotlin with no Android dependencies, with at least 80% line coverage. Static analysis and formatting must pass in CI.
- **FR-4:** Stage order and Stage completion predicates define the beginner segments. Completion is decided by predicates, never by counting Moves.
- **FR-16:** Solution Playback notes keep the fixed face/direction/piece-group template and never claim a strategic purpose (Principle 3).
- **Out of scope:** CFOP F2L, Roux and ZZ segments (P2). Re-explaining arbitrary Solver output in beginner terms is also out of scope.

## Technical Decisions

- **AD-21:** method-aware solutions come from the Course, not the Solver.
  - `MethodSolve` lives in `:core:domain`. It returns an ordered list of segments, each `(tag: StageId | CaseId, moves)`.
  - It **never calls `CubeSolver`**.
  - First-two-layer segments follow the Course's Stage completion predicates in order. Each is found by the bounded `preservedPieces` search that `RecoveryPlanner` uses (bounded IDA*), so a segment cannot break a piece an earlier segment placed.
  - Last-layer segments go through `LastLayerMatcher` with Library Algorithms only:
    - beginner: the Stages 5-6 Lessons' Algorithms
    - 2-look: the 2-look Lessons' Algorithms
    - full: one Algorithm per Case
  - It runs as an Effect with a timeout, like `SolveCube`. Every result is re-verified by applying it to its input before it is shown.
- **AD-8 (unchanged):** `CubeSolver` (clean-room Kociemba) only finds paths. Only `SolveCube` and `PlanRecovery` call it, as an Effect with a 3 s timeout, returning `SolveResult.{Solved, Invalid, TimedOut, Failed}`. Re-labelling Kociemba output as a beginner solve is forbidden.
- **AD-6:** content is data.
  - Stages, Lessons and Algorithms are JSON under `:core:domain` resources.
  - Predicates are color-anchored and orientation-invariant. Each implements `preservedPieces(state)` and an `ObjectiveProgress` measure. The only combinators are `allOf` and `anyOf`.
  - Stage, Lesson, Case (`case-oll-45`) and Algorithm (`alg-oll-45-a`, which declares its `caseId`) IDs are separate, stable namespaces. 2-look Lessons reference Algorithm IDs and never copy Notation.
  - All Learner-facing text is `Text(key, args)` from `content/strings/<locale>.json`. The content test fails on missing, duplicate or argument-mismatched keys.
- **AD-19:** `LastLayerMatcher` is the only definition of Case patterns and AUF.
  - The canonical frame puts the last layer (opposite the solved F2L color, YELLOW) on U.
  - OLL is matched by orientation mask under pre-AUF. PLL is matched by permutation under pre- and post-AUF and `y^k` viewing.
  - Never define Case patterns or AUF elsewhere. `TwoLookSolve` judges which Algorithms were used, not which Case a state is.
- **AD-20:** one pure `PlaybackController` drives all playback and is never checkpointed.
  - A Move list may be split into labeled segments. A segment label is a content `Text` key that names the Stage or Case being practiced, never why a Move was chosen.
  - Per-Move notes come only from `MoveNote.of(move)`.
- **AD-3:** method-solution playback is a `SOLVE_PLAYBACK` session (no new `SessionKind`). It is not resumed after the app closes. Stale effect results are discarded by `revision`/`effectId`.
- **AD-7:** `Notation.parse`/`format` are the only string-to-Move conversions. Move equality is by effect.

## UX & Interaction Patterns

- Solution Playback from the Solve tab opens on *Shortest* by default. A *Solution style* control offers *Beginner*, *2-look* and *Full OLL + PLL*, with the method suggested from progress preselected.
- Choosing a style restarts playback at step 1 and shows segment labels (the Stage or Case name from content). It works in Physical-cube Mode, where *Done* advances one step and Quick Check stays available.
- Pending state: "Building a step-by-step solution…". On timeout, show "Couldn't build that solution in time. The shortest one is still here." and keep *Shortest*.
- Use the shared playback transport (UX-DR14): Move strip, scrubber snapping to Move boundaries, and a MoveNote line. Physical-cube Mode makes the strip read-only and hides the scrubber. The 3D/2D view switch, Move pictures and the no-frame-label rule for Solution Playback still apply.

## Cross-Story Dependencies

- 15.2 extends the `MethodSolve` that 15.1 creates. It reuses 15.1's first-two-layer segments and swaps in 2-look or full last-layer segments.
- 15.3 depends on both, and on the playback stack: Solution Playback (Epic 3), physical follow-along (Epic 8), and the 3D/2D view switch and Move strip (Stories 12.14-12.15).
- Shared seams:
  - Epic 14 (#208, Algorithm practice) uses the same AD-20 labeled segments (*Set-up* / *Algorithm*).
  - Algorithm Statuses and the 2-look/full OLL+PLL Algorithm content come from Epic 9 (Library, 2-look Lessons).
  - `RecoveryPlanner` comes from Epic 4 (FR-12).
- Point to reconcile in 15.1's spec: its acceptance criteria find all five beginner segments, including last-layer orientation and completion, through the `preservedPieces` search. AD-21 instead assigns beginner last-layer segments to `LastLayerMatcher` with the Stages 5-6 Lessons' Algorithms.
