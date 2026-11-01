---
title: 'Solver coordinates and move tables (clean room)'
type: 'feature'
ticket: '3-1-solver-coordinates-and-move-tables-clean-room'
created: '2026-11-01'
status: 'in-progress'
baseline_revision: '729afcc5eafcb38b1dacd7e2eaa2119ee82164f3'
route: 'full'
route_source: 'auto'
risk: 'high'
review: ''
review_source: ''
lenses_ran: []
review_loop_iteration: 0
followup_review_recommended: false
context:
  - /mnt/e/Dynamic/IndFun/RubikXplorer/AGENTS.md
  - /mnt/e/Dynamic/IndFun/RubikXplorer/_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The app promises an offline Solver that solves any valid cube (FR-15), but `:core:solver` ships no solver implementation yet — no phase-1/phase-2 coordinates and move tables — so the P0.S solve-any-cube track cannot start.

**Approach:** Implement the clean-room Kociemba two-phase coordinate model in `:core:solver`: phase-1 coordinates (corner twist, edge flip, slice position) and phase-2 coordinates (corner permutation, edge permutation, slice permutation) with move tables that translate moves into coordinate deltas, written from the published algorithm description.

## Boundaries & Constraints

**Always:** Keep `:core:solver` pure JVM (kotlin-jvm module; no Android/Compose/Hilt). Write clean-room Kotlin from the published algorithm description; cite the source in each file; no solver code derived from min2phase. Respect module edges (AD-1) and the SingleGate public route (`CubeValidator.validate` only). Treat errors as values (AD-12). Work through the public `CubeState`/`apply` semantics (AD-2); coordinates encode home-frame cubies.

**Never:** Add pruning tables (story 3.2), the search, the `CubeSolver` port or `SolveResult` (stories 3.3-3.4), app wiring, `:core:domain` edits, or Learner-facing prose in `:core:solver` main sources.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
| ---------- | ----------- | --------------------------- | ------------- |
| Solved state | `CubeState.SOLVED` | All phase coordinates identity; every move delta zero | None |
| Scrambled legal state | home state plus Scramble (seeded) | Coordinate round-trip equals the cubies; per-move delta keeps coordinates consistent | Scramble legality untouched |

</intent-contract>

## Code Map

- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` -- phase-1 and phase-2 coordinate plus move-table implementation; new source set.
- `core/solver/src/test/kotlin/dev/rubikxplorer/solver/` -- coordinate/move-table tests; new source set.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/` -- read-only reference; do not change (SingleGate, AD-2).

## Tasks & Acceptance

**Execution:**

- [ ] `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` -- implement phase-1 coordinates (twist, flip, slice) and phase-2 coordinates (corner permutation, edge permutation, slice permutation) plus phase move tables mapping moves to coordinate deltas.
- [ ] `core/solver/src/test/kotlin/dev/rubikxplorer/solver/` -- add tests proving coordinate-to-cubie round-trips and move-table transitions match direct cubie application for all coordinates.

**Acceptance Criteria:**

- Given `:core:solver` with no third-party solver code, when phase-1 (twist, flip, slice) and phase-2 (corner permutation, edge permutation, slice permutation) coordinates and their move tables are implemented, then coordinate ↔ cubie round-trips and move-table transitions match direct cubie application for all coordinates in tests.
- Given the repo, when a reviewer checks provenance, then each source file cites the published algorithm description it was written from, and none is derived from min2phase.

## Implementation Notes

## Plan Change Log

## Review Triage Log

## Design Notes

- Phase-1 coordinate vector over home-frame cubies: corner twist, edge flip, slice position. Phase-2 coordinate vector: corner permutation, edge permutation, slice permutation. Phase 1 owns orientation, phase 2 owns permutation.
- Move tables map moves to deltas over the phase vector. Half turn equals two quarters; prime equals inverse; wide and slice moves are face-turn-equivalent compositions and are covered through their parts.
- Cube rotations carry zero coordinate delta (orientation-only; coordinates stay home-frame).

## Verification

**Commands:**

- `./gradlew :core:solver:test` -- expected: coordinate and move-table tests pass.

**Manual checks:** none beyond the Gradle gates.

