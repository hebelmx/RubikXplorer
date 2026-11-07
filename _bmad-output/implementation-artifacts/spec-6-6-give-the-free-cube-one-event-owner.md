---
title: 'Story 6.6: Give the free cube one event owner'
type: 'feature'
created: '2026-11-06'
baseline_revision: '8afe4fd6bc76410694792ddda5c7d79715937b41'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/AGENTS.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `FreeCubeViewModel` writes `MoveHistory` directly in five separate methods (`onMoveIntent`, `onUndo`, `onRedo`, `onReset`, `onScramble`), each calculating its own revision and constructing presentation items. This violates AD-4's single `MoveCommitted` path guarantee, leaves transition logic untested on the JVM, and creates multiple writers outside a reducer (AD-3, AD-4, AD-20).

**Approach:** Introduce a pure reducer `reduceFreeCube(state, event)` in `:core:application` (`dev.rubikxplorer.application.freecube`) with `FreeCubeEvent` (`MoveCommitted(move)`, `Undo`, `Redo`, `Reset`, `Scrambled(moves)`) and `FreeCubeState(history, revision)`. Refactor `FreeCubeViewModel` to be a pure event dispatcher that submits the returned `Presentation`, keeping the free cube unpersisted and outside `SessionHost` (AD-20).

## Boundaries & Constraints

**Always:**
- Pure functional reducer `reduceFreeCube(state: FreeCubeState, event: FreeCubeEvent): FreeCubeTransition` in `:core:application` (`dev.rubikxplorer.application.freecube`).
- Monotonic revision: every state-advancing transition increments `state.revision` by 1.
- Stamped presentation: `Animate(move)` for `MoveCommitted` and `Redo`, `Animate(inverse)` for `Undo`, and `Snap` for `Reset` and `Scrambled`.
- No-op guard: `Undo` or `Redo` with nothing to undo or redo returns `state` unchanged and `presentation = null`.
- Scramble generation: scramble moves are generated outside the reducer via `NewScramble`; no `RandomSource` or random generation inside `reduceFreeCube`.
- Single writer: `FreeCubeViewModel` never modifies `MoveHistory` directly; it only dispatches `FreeCubeEvent` and submits the returned `Presentation` via `port.submit(listOf(it.toItem()))`.
- Pure JVM tests in `:core:application` covering Story 1.6's undo, redo, reset, and redo-clearing rules.

**Never:**
- Never make the free cube a `SessionKind` or integrate it into `SessionHost`, snapshots, Checkpoints, or Room persistence (AD-20).
- Never persist free-cube state or history (it lives in memory only).
- Never change the public behavioral contracts of existing free-cube suites (`FreeCubeScreenTest`, `ObjectiveOverlayTest`, `ViewRotationInvarianceTest`).
- Never perform random move generation inside the reducer.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| MoveCommitted | State(history, rev), MoveCommitted(R) | State(history.commit(R), rev + 1), Animate(R, rev + 1, target) | No error expected |
| Undo with history | State with undoable move U, Undo | State(history.undo(), rev + 1), Animate(U.inverse(), rev + 1, target) | No error expected |
| Undo on empty history | State with empty history, Undo | Input state unchanged, presentation null | No error expected |
| Redo with undone move | State with redoable move U, Redo | State(history.redo(), rev + 1), Animate(U, rev + 1, target) | No error expected |
| Redo on empty redo stack | State with empty undone list, Redo | Input state unchanged, presentation null | No error expected |
| MoveCommitted clears redo | State with redoable moves, MoveCommitted(F) | State has F committed and undone cleared, rev + 1, Animate(F, rev + 1, target) | No error expected |
| Reset from non-empty | State with moves, Reset | State(history.reset(), rev + 1), Snap(rev + 1, CubeState.SOLVED) | No error expected |
| Reset from solved | State with SOLVED, Reset | State(history.reset(), rev + 1), Snap(rev + 1, CubeState.SOLVED) | No error expected |
| Scrambled with moves | State, Scrambled([R, U, F]) | State(resets then commits [R, U, F], rev + 1), Snap(rev + 1, target) | No error expected |
| Scrambled with empty list | State, Scrambled([]) | State(resets to SOLVED, rev + 1), Snap(rev + 1, CubeState.SOLVED) | No error expected |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeEvent.kt` -- New: `FreeCubeEvent` sealed interface with `MoveCommitted(move: Move)`, `Undo`, `Redo`, `Reset`, `Scrambled(moves: List<Move>)`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeState.kt` -- New: `FreeCubeState(history: MoveHistory = MoveHistory(), revision: Long = 0L)`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeTransition.kt` -- New: `FreeCubeTransition(state: FreeCubeState, presentation: Presentation? = null)`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/ReduceFreeCube.kt` -- New: pure `reduceFreeCube(state: FreeCubeState, event: FreeCubeEvent): FreeCubeTransition`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/freecube/ReduceFreeCubeTest.kt` -- New: pure JVM unit tests verifying all transitions, Story 1.6 undo/redo/reset/redo-clearing rules, monotonicity, and edge cases.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` -- Refactored: holds `FreeCubeState`, exposes `dispatch(FreeCubeEvent)`, routes `onMoveIntent`, `onUndo`, `onRedo`, `onReset`, `onScramble` through `dispatch`, and submits returned `Presentation`.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModelTest.kt` -- New: Robolectric / JVM unit test asserting `FreeCubeViewModel` dispatch behavior and verifying gesture vs Move key produce identical transitions.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- Extended: includes UI test proving gesture and Move key produce identical transitions.

## Tasks & Acceptance

**Execution:**
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeEvent.kt` -- define `FreeCubeEvent` sealed interface and variants (`MoveCommitted`, `Undo`, `Redo`, `Reset`, `Scrambled`) -- provides pure event types (AC 1).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeState.kt` -- define `FreeCubeState` data class holding `MoveHistory` and monotonic `Long` revision -- provides pure state model (AC 1).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeTransition.kt` -- define `FreeCubeTransition` holding next `FreeCubeState` and optional `Presentation` -- returns result of reduction (AC 1).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/ReduceFreeCube.kt` -- implement `reduceFreeCube` -- pure reducer executing state transitions and generating revision-stamped presentations (AC 1).
- `core/application/src/test/kotlin/dev/rubikxplorer/application/freecube/ReduceFreeCubeTest.kt` -- test `reduceFreeCube` covering Story 1.6 rules, presentations, no-ops, and monotonicity -- verifies AC 1.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` -- update `FreeCubeViewModel` to delegate all state changes to `reduceFreeCube` and submit returned presentation -- satisfies AC 2 single writer requirement.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModelTest.kt` -- test `FreeCubeViewModel` dispatching and parity between gesture and Move key transitions -- verifies AC 2.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- add test verifying gesture and equal Move key produce identical transitions at the screen surface -- verifies AC 2.

**Acceptance Criteria:**
- Given a pure `FreeCubeEvent` (`MoveCommitted(move)`, `Undo`, `Redo`, `Reset`, `Scrambled(moves)`) and `reduceFreeCube(state, event)` in `:core:application`, where the state holds the `MoveHistory` and a monotonic `revision`, when an event is reduced, then it returns the next state and a revision-stamped presentation: `Animate(move)` for a commit, `Animate(inverse)` for Undo, `Animate(move)` for Redo, and `Snap` for Reset and Scramble. Undo or Redo with nothing to undo or redo returns the state unchanged with no presentation. JVM tests cover Story 1.6's undo, redo, reset and redo-clearing rules.
- Given the free cube screen, when a gesture, a Move key or an accessibility action produces a `MoveIntent`, or the Learner taps Undo, Redo, Reset or Scramble, then `FreeCubeViewModel` only dispatches a `FreeCubeEvent` and submits the returned presentation. It is not a second writer of the history, the Scramble Moves are generated outside the reducer (no `RandomSource` in `reduce`), and a test shows a gesture and the equal Move key produce identical transitions.
- Given the existing free-cube suites (the 2D/3D randomized sync suite and the Story 5.2 gesture tests), when they run, then they pass unchanged, and the free cube is still not a Learning Session and persists nothing (AD-20).
- Given the whole change, when `./gradlew spotlessCheck build` runs, then it passes, with `:core:*` Kover at 80% or more.

## Spec Change Log

## Review Triage Log

### 2026-11-06 — Review pass
- verdicts: 8 findings — high 0, medium 0, low 3, false 5, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) `FreeCubeState.kt` permits custom start state in MoveHistory; default constructor is solved and free cube does not load persisted states (AD-20).
  - `[low]` `[patch]` (blind) Added counterclockwise, half turns, slice turns and rotations coverage in `ReduceFreeCubeTest`.
  - `[low]` `[patch]` (blind) Added consecutive multi-step Redo verification in `ReduceFreeCubeTest`.
  - `[false]` `[reject]` (blind) FreeCubeViewModelTest lacks renderer seam; renderer submissions and parity are already directly tested on the Compose UI surface in `FreeCubeScreenTest`.
  - `[false]` `[reject]` (edge-case) `state.revision == Long.MAX_VALUE` wrap-around; unreachable in practice on a 64-bit counter.
  - `[false]` `[reject]` (gap) no gaps found in acceptance criteria or rule compliance.

## Design Notes

- **Separation from Sessions:** The free cube logic is placed in `dev.rubikxplorer.application.freecube` rather than `dev.rubikxplorer.application.session`. The free cube does not have `SessionKind`, session ID, effects, snapshots, or database persistence (AD-3, AD-20).
- **Presentation Stamping:** Reuses `dev.rubikxplorer.application.session.Presentation` (`Animate` and `Snap`) which already integrates with `PresentationMapping.toItem()` in `:app`.
- **Monotonic Revision:** Revisions strictly increase whenever history changes, ensuring `PresentationQueue` in `:app` receives sequentially stamped items. No-op undo/redo operations preserve the exact input state instance and return `presentation = null`.
- **Scramble Generation:** `NewScramble` generates moves before event dispatch, keeping `reduceFreeCube` strictly deterministic and free of `RandomSource`.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, with testDebugUnitTest and Kover passing at >= 80%.

## Auto Run Result

**Summary**
- Pure `reduceFreeCube(state, event)` reducer introduced in `:core:application` with `FreeCubeEvent` and `FreeCubeState(history, revision)`.
- `FreeCubeViewModel` refactored into a pure event dispatcher routing all interactions (`onMoveIntent`, `onUndo`, `onRedo`, `onReset`, `onScramble`) through `dispatch` and submitting the returned revision-stamped `Presentation`.
- Full JVM and Robolectric test coverage in `:core:application` and `:app` asserting transitions, parity between gesture and key intents, animation on frame clock, and monotonicity.

**Files changed**
- `core/application/.../freecube/FreeCubeEvent.kt`
- `core/application/.../freecube/FreeCubeState.kt`
- `core/application/.../freecube/FreeCubeTransition.kt`
- `core/application/.../freecube/ReduceFreeCube.kt`
- `core/application/.../freecube/ReduceFreeCubeTest.kt`
- `app/.../ui/freecube/FreeCubeViewModel.kt`
- `app/.../ui/freecube/FreeCubeViewModelTest.kt`
- `app/.../ui/freecube/FreeCubeScreenTest.kt`

**Review**
- 8 findings across 3 review layers: 0 high, 0 medium, 2 patches applied, 6 rejected.
- Follow-up review recommended: false.

**Verification**
- `./gradlew spotlessCheck build`: BUILD SUCCESSFUL in 26s (exit 0). All JVM tests, Android lint, Kover (>=80%), and APK assembly passed.

