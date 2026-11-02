---
title: 'Solver coordinates and move tables (clean room)'
type: 'feature'
ticket: '3-1-solver-coordinates-and-move-tables-clean-room'
created: '2026-11-01'
status: 'built'
baseline_revision: '729afcc5eafcb38b1dacd7e2eaa2119ee82164f3'
route: 'full'
route_source: 'auto'
risk: 'high'
review: 'quick'
review_source: 'pinned'
lenses_ran: [quick]
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

**Approach:** Implement the clean-room Kociemba two-phase coordinate model in `:core:solver`: phase-1 coordinates (corner twist, edge flip, slice position) and phase-2 coordinates (corner permutation, edge permutation, slice permutation) with move tables that translate coordinates and moves into successor coordinates, written from the published algorithm description.

## Boundaries & Constraints

**Always:** Keep `:core:solver` pure JVM (kotlin-jvm module; no Android/Compose/Hilt). Write clean-room Kotlin from the published algorithm description; cite the source in each file; no solver code derived from min2phase. Respect module edges (AD-1) and the SingleGate public route (`CubeValidator.validate` only). Treat errors as values (AD-12). Work through the public `CubeState`/`apply` semantics (AD-2); coordinates encode home-frame cubies.

**Never:** Add pruning tables (story 3.2), the search, the `CubeSolver` port or `SolveResult` (stories 3.3-3.4), app wiring, `:core:domain` edits, or Learner-facing prose in `:core:solver` main sources.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
| ---------- | ----------- | --------------------------- | ------------- |
| Solved state | `CubeState.SOLVED` | All phase coordinates identity (zero); face-turn transitions match direct cubie application | None |
| Scrambled legal state | home state plus Scramble (seeded) | Coordinate round-trip equals the cubies; per-move delta keeps coordinates consistent | Scramble legality untouched |

</intent-contract>

## Code Map

- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` -- phase-1 and phase-2 coordinate plus move-table implementation; new source set.
- `core/solver/src/test/kotlin/dev/rubikxplorer/solver/` -- coordinate/move-table tests; new source set.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/` -- read-only reference; do not change (SingleGate, AD-2).

## Tasks & Acceptance

**Execution:**

- [x] `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` -- implement phase-1 coordinates (twist, flip, slice) and phase-2 coordinates (corner permutation, edge permutation, slice permutation) plus phase move tables mapping coordinates and moves to successor coordinates.
- [x] `core/solver/src/test/kotlin/dev/rubikxplorer/solver/` -- add tests proving coordinate-to-cubie round-trips and move-table transitions match direct cubie application for all coordinates.

**Acceptance Criteria:**

- Given `:core:solver` with no third-party solver code, when phase-1 (twist, flip, slice) and phase-2 (corner permutation, edge permutation, slice permutation) coordinates and their move tables are implemented, then coordinate ↔ cubie round-trips and move-table transitions match direct cubie application for all coordinates in tests.
- Given the repo, when a reviewer checks provenance, then each source file cites the published algorithm description it was written from, and none is derived from min2phase.

## Implementation Notes

- Added six internal coordinate codecs with projection representatives, leaving domain construction behind CubeValidator. Generated independent lazy transition tables from public solved-state face turns, using 18 phase-1 moves and 10 phase-2 moves.
- Exhaustive tests enumerate all 85,394 coordinate values, repair unused parity, validate sticker representatives, and compare table successors against public CubeState.apply. Seeded 1,000-step walks in each phase and identity/rotation checks cover both matrix rows.
- Every added source file cites Kociemba’s published mathematical description; no third-party solver implementation was consulted or copied.

## Plan Change Log

- 2026-11-02: Parent orchestration explicitly authorized resolving contradictory wording against the story ACs and architecture. Corrected the solved-state matrix: identity coordinates are zero, but face turns produce successor coordinates rather than universally zero additive deltas. This preserves the explicit all-coordinate transition AC and avoids treating face turns as no-ops.

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 0 findings — high 0, medium 0, low 0, false 0, maybe-false 0
- findings: none; independent context-free quick review reported no findings.

## Design Notes

- Phase-1 coordinate vector over home-frame cubies: corner twist, edge flip, slice position. Phase-2 coordinate vector: corner permutation, edge permutation, slice permutation. Phase 1 owns orientation, phase 2 owns permutation.
- Move tables map coordinates and moves to successor coordinates over the phase vector. Half turn equals two quarters; prime equals inverse; wide and slice moves are face-turn-equivalent compositions and are covered through their parts.
- Cube rotations carry zero coordinate delta (orientation-only; coordinates stay home-frame).

## Verification

**Commands:**

- `./gradlew :core:solver:test` -- expected: coordinate and move-table tests pass.

**Manual checks:** none beyond the Gradle gates.


## Auto Run Result

- Implemented all six clean-room coordinate codecs and lazy phase move tables.
- Files changed: `core/solver/src/main/kotlin/dev/rubikxplorer/solver/Coordinates.kt` (rank/unrank and internal cubie projection); `core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt` (18-move phase-1 and 10-move phase-2 successor tables); `core/solver/src/test/kotlin/dev/rubikxplorer/solver/CoordinatesTest.kt` (exhaustive independent domain oracle, seeded walks, identity/rotation and invalid-input tests); this plan (execution record).
- Review: zero findings; zero patches, deferred items, or rejected findings. Follow-up review recommended: false; patched counts high 0, medium 0, low 0.
- Verification: `./gradlew :core:solver:test` passed; four coordinate tests ran with zero skips/failures. All 85,394 coordinate values round-trip and 891,780 allowed transitions agree with validator-created legal domain states and public `CubeState.apply`. Both matrix rows have executed passing tests.
- Full gates: `./gradlew :core:solver:test spotlessCheck build` passed, including Kover, boundaries, manifest/SDK checks, Android lint and debug/release APK builds.
- Residual scope: home-frame face-turn tables only; search, pruning, persistence and app wiring remain subsequent stories.
