---
title: 'GH-202 Solver: merge consecutive same-face turns before showing the plan'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: 'c2f56134eb52849efc698bb07a0e71431c696090'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The two-phase solver restarts its same-face pruning at the phase-1/phase-2 boundary (`phase2(..., previous = -1)`), so a returned plan can contain adjacent turns of one face. For the scramble `R U2 F L2 D B2` it returns `B2 D' L2 F' U2 R R2` (7 moves) instead of `B2 D' L2 F' U2 R'` (6). The plan is correct but unsimplified, and it is what the Learner sees and plays.

**Approach:** Add a pure domain function that merges consecutive same-face `FaceTurn`s (sum quarter-turns mod 4; drop on 0; repeat until stable, so e.g. `R U U' R` collapses to `R2`). Apply it in the `SolveCube` use case to the solver's moves before orientation mapping and the existing independent verification, so every plan that reaches presentation or playback is simplified regardless of adapter.

## Boundaries & Constraints

**Always:** Merging is keyed on the same `Face` only (adjacent in the list). The merged plan must produce exactly the same cube state as the input plan. The existing `verified.isSolved` check in `SolveCube` stays and runs on the merged, mapped moves. Pure Kotlin in `:core:domain` (no new module, port, or dependency). Use `Turn.quarters` (internal to domain) for the arithmetic.

**Never:** Do not change the IDA* search, pruning tables, move tables, phase bounds, or the `CubeSolver`/`SolveResult` port. Do not cancel or reorder opposite-face commuting moves (e.g. `R L R`) — out of scope. No Learner-text changes, no UI changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Issue case | `B2 D' L2 F' U2 R R2` | `B2 D' L2 F' U2 R'` | No error expected |
| Cancel to zero | `R R'` / `U2 U2` | `[]` | No error expected |
| Cascade until stable | `R U U' R` | `R2` | No error expected |
| Three in a row | `R R R` | `R'` | No error expected |
| Already simplified / empty | `R U F` / `[]` | unchanged | No error expected |

</intent-contract>

## Code Map

- `core/solver/src/main/kotlin/dev/cubentia/solver/TwoPhaseCubeSolver.kt` -- READ-ONLY. Root cause: `phase1` calls `phase2(c, e, p, depth, level, -1)`, resetting `previous`, so the last phase-1 face may repeat as the first phase-2 face.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Move.kt` -- `FaceTurn(face, turn)`, `Turn` with `internal val quarters` (1/2/3). Reuse for arithmetic.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveCube.kt` -- the only consumer of `SolveResult.Solved`; loops `result.moves`, maps each face via `effect.state.orientation.homeFaceAt`, applies to `verified`, emits `SolveCubeOutcome.Solved(moves)`. Insertion point: iterate the merged list instead of `result.moves`.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/SolveCubeTest.kt` -- existing fake-solver tests; `request(state)` builds `Effect.SolveCube(51, 73, state)`.
- `core/solver/src/test/kotlin/dev/cubentia/solver/TwoPhaseCubeSolverTest.kt` -- `SolverWorkload.preparedTables` gives real tables; solver tests may use `SolveCube` (solver depends on `:core:application`).
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/` -- domain test home (JUnit 5, `kotlin.random.Random` seeds used elsewhere).
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt:434` -- fake solver returns `R R2` for cube `R`; after merge the plan is `R'` (still solves). Must keep passing.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/FaceTurnMerge.kt` -- add public `fun List<FaceTurn>.mergeSameFaceTurns(): List<FaceTurn>` (stack-based single pass: merging with the top of the output, popping on 0, is equivalent to repeating until stable) with KDoc -- the simplification rule lives once in the domain.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/FaceTurnMergeTest.kt` -- example tests for every I/O matrix row, plus a seeded property test over many random `FaceTurn` lists (lengths 0..40, biased toward repeated faces) asserting no two adjacent merged moves share a face and `CubeState.SOLVED.apply(input) == CubeState.SOLVED.apply(merged)` -- the issue's property-test requirement.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveCube.kt` -- iterate `result.moves.mergeSameFaceTurns()` instead of `result.moves` -- every shown/played plan is simplified and still independently verified.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/SolveCubeTest.kt` -- add a test: for state `SOLVED.apply(R)`, a fake solver returning `R R2` yields `SolveCubeOutcome.Solved(listOf(R'))`; a fake returning `R R'` for `SOLVED` yields `Solved(emptyList())`.
- `core/solver/src/test/kotlin/dev/cubentia/solver/TwoPhaseCubeSolverTest.kt` -- add a test running `SolveCube(TwoPhaseCubeSolver(tables))` on `SOLVED.apply(R U2 F L2 D B2)` asserting the outcome moves equal `B2 D' L2 F' U2 R'` and that no two adjacent moves share a face.

**Acceptance Criteria:**
- Given the scramble `R U2 F L2 D B2`, when `SolveCube` runs with the real `TwoPhaseCubeSolver`, then the `SolveCubeOutcome.Solved` plan is `B2 D' L2 F' U2 R'`.
- Given any solver plan with adjacent same-face turns, when `SolveCube` completes, then the emitted plan has no two adjacent moves on the same face and still solves the input state.
- Given the change, when `./gradlew spotlessCheck build` runs, then it succeeds with all existing solver, application and app tests passing.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 7 findings — high 0, medium 0, low 5, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind-hunter) Real-solver regression test never proves the raw plan still has the `R R2` boundary pair, so it could pass vacuously if the solver's search order changed — added an assertion that `TwoPhaseCubeSolver.solve` returns `B2 D' L2 F' U2 R R2` before checking the merged `SolveCube` plan; it passes.
  - `[low]` `[reject]` (blind-hunter) No rotated-orientation merge test — face mapping in `SolveCube` is a per-face bijection applied after merging, so merging commutes with it; the existing all-orientation mapping test covers the remap. Adding a test is extra surface for a regression nobody is likely to meet.
  - `[low]` `[patch]` (blind-hunter) Empty merged plan only tested on a solved cube — the code already falls to `Failed` (`verified.isSolved` is false); added `R R'` on an `R2`-scrambled state asserting `SolveCubeOutcome.Failed`.
  - `[low]` `[patch]` (verification-gap) Same root cause as the previous row (plan that cancels to empty on an unsolved cube) — covered by the same added assertion.
  - `[low]` `[reject]` (intent-alignment) No app-module test of the rendered plan — `SolveFlowViewModel` reaches the solver only through `SolveCube`, and the existing `AppShellSolveTest` R/R2 fake still passes; an extra UI test adds cost for no distinct risk.
  - `[false]` `[reject]` (intent-alignment) Property test runs on random sequences, not real solver plans — the property holds for every `FaceTurn` list, a superset of solver plans; the issue scramble is additionally checked end to end through the real solver.
  - `[false]` `[reject]` (intent-alignment) Solver's own output stays unsimplified for a future caller that bypasses `SolveCube` — the intent requires simplification before a plan is shown or played, and `SolveCube` is the only path to presentation; no current caller is affected.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test :core:solver:test` -- expected: all pass, including new tests.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** `SolveCube` now merges consecutive same-face turns from the solver (quarter-turns summed mod 4, zero dropped, stack-based so cascades settle in one pass) before orientation mapping and the independent solved check. The scramble `R U2 F L2 D B2` now yields `B2 D' L2 F' U2 R'` (raw solver output `... R R2`).

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/FaceTurnMerge.kt` -- new `List<FaceTurn>.mergeSameFaceTurns()` (plus a checkpoint overload for cancellation).
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveCube.kt` -- merges adapter moves before mapping and verification.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/FaceTurnMergeTest.kt` -- matrix examples and a seeded 2,050-case property test (no adjacent same face, same cube state).
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/SolveCubeTest.kt` -- merge, cancel-to-empty on solved, and cancel-to-empty on unsolved (`Failed`).
- `core/solver/src/test/kotlin/dev/cubentia/solver/TwoPhaseCubeSolverTest.kt` -- real-solver regression: raw `... R R2`, merged `B2 D' L2 F' U2 R'`.

**Review findings:** 3 patches applied (all low; two share one root cause), 0 deferred, 4 rejected (2 low, 2 false; reasons in the triage log).

**Follow-up review recommendation:** false — patched: high 0, medium 0, low 3.

**Verification:** `./gradlew :core:domain:test :core:application:test :core:solver:test spotlessCheck build` -- BUILD SUCCESSFUL after patches. The SolveCubeTest JUnit XML records 9 tests, all passed, including the adapter-turn merge and empty-plan test.

**Residual risks:** The real-solver regression test is pinned to the solver's current search order; a future solver change would need it updated.

