---
title: 'Story 8.1: Physical-mode transitions in the reducer'
type: 'feature'
created: '2026-11-07'
baseline_revision: '91b8414089b2b66efcae3ff5d6d9d0e8d1ad3915'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      PersistCheckpoint and Checkpoint.of do not yet carry CubeMode to save physical mode into checkpoints.
    evidence: |-
      Persisting physical mode checkpoints is explicitly defined and verified in Story 8.4 ("Leave and resume physical mode safely").
    location: >-
      core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt:25
    severity: medium
  - summary: >-
      SessionSnapshot does not persist mode or syncState across process death.
    evidence: |-
      Snapshot restore and process-death survival for physical mode is explicitly scoped to Story 8.4 ("Leave and resume physical mode safely").
    location: >-
      core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionSnapshot.kt:10
    severity: medium
  - summary: >-
      Guided recovery transition from PHYSICAL_MISMATCH to PHYSICAL_PREDICTED is not yet wired in reducer.
    evidence: |-
      The full mismatch discovery ladder and guided recovery flow without camera is explicitly scoped to Story 8.3 ("Report a mismatch honestly without a camera").
    location: >-
      core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt:386
    severity: medium
  - summary: >-
      Harmful move diagnosis certainty on predicted physical state is not yet clamped to LIKELY.
    evidence: |-
      Tutor diagnosis adaptation and error certainty rules without a camera are explicitly scoped to Story 8.3 ("Harmful Move diagnosis on a predicted state is at most Likely, never false Verified").
    location: >-
      core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt:376
    severity: medium
  - summary: >-
      Switching from physical to virtual mode omits notification text ("The on-screen cube now matches what we expected on yours.").
    evidence: |-
      Learner-facing notifications and restore copy when leaving physical mode are explicitly scoped to Story 8.4 ("Leave and resume physical mode safely").
    location: >-
      core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt:472
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Learners following lessons with a physical cube need the app to track their cube's expected state honestly without pretending to see it, updating state only upon user confirmation or explicit full observation.

**Approach:** Implement the 5-state Sync State Machine in the `:core:application` reducer, managing `expected`, `virtual`, `observed`, and `syncState` across `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, `Undo`, and `EditorEntered`, with `ModeSwitched` acting as an undo barrier and `Undo` providing inverse turn instructions.

## Boundaries & Constraints

**Always:**
- Keep pure JVM rules in `:core:domain` and `:core:application`: no Android, Compose, Room, or CameraX imports.
- Maintain `virtual` mirroring `expected` during all physical-mode states.
- Revert `expected` on `Undo` of a `PhysicalConfirmed` and produce presentation instruction: "Turn {Move}' on your cube to undo."
- `ModeSwitched`, `EditorEntered`, and `CheckpointRestored` act as undo barriers that clear redo and prevent undo from crossing mode boundaries.
- Ensure `expected` changes ONLY upon `PhysicalConfirmed`, `Undo` of a `PhysicalConfirmed`, or an accepted Full observation (`EditorEntered`).
- Keep coverage >= 80% on `:core:*`.

**Never:**
- Never guess or simulate physical turns without explicit confirmation or validated observation.
- Never allow View Rotation to mutate Notation, `virtual`, or `expected`.
- Never bypass `CubeValidator` when creating `CubeState` for Full observations.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Mode switch to physical | `ModeSwitched(CubeMode.PHYSICAL)` from `Virtual` | Transitions to `PhysicalAwaitingConfirm`; clears redo; sets history to `expected` (undo barrier); advances `revision`. | Ignores if already in target mode |
| Mode switch to virtual | `ModeSwitched(CubeMode.VIRTUAL)` from `Physical*` | Transitions to `Virtual`; sets `virtual := expected`; clears redo; undo barrier; advances `revision`. | Ignores if already in target mode |
| Confirm match while awaiting confirm | `PhysicalConfirmed(null)` in `PhysicalAwaitingConfirm` | Transitions to `PhysicalPredicted`; `expected` stays; `virtual` mirrors `expected`. | No error |
| Confirm move in predicted | `PhysicalConfirmed(R)` in `PhysicalPredicted` | `expected := expected.apply(R)`; `virtual := expected`; prior `observed` becomes null (stale); advances `revision`. | No error |
| Undo of physical confirmed move | `Undo` in `PhysicalPredicted` after `PhysicalConfirmed(R)` | `expected` reverts; `virtual` mirrors `expected`; presentation contains `Presentation.Instruct` saying "Turn R' on your cube to undo."; advances `revision`. | No-op if at barrier / empty history |
| Report mismatch | `MismatchReported` in `PhysicalPredicted` or `PhysicalVerified` | Transitions to `PhysicalMismatch`; `expected` and `virtual` unchanged; revision unchanged. | No-op if already mismatch or idle |
| Re-enter cube via editor | `EditorEntered(state)` in `PhysicalAwaitingConfirm`, `PhysicalPredicted`, or `PhysicalMismatch` | Transitions to `PhysicalPredicted`; `expected := state`; `virtual := state`; `observed := PhysicalObservation.Full(state)`; undo barrier (clears redo); advances `revision`. | Validated CubeState required |
| Undo during mismatch | `Undo` in `PhysicalMismatch` | Transitions to `PhysicalPredicted`; reverts `expected`; produces undo instruction. | No-op if at barrier |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- Adds `"msg.physical.undo": "Turn {move} on your cube to undo."`
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/recognition/PhysicalObservation.kt` -- Sealed interface for `Full(state: CubeState)` and `Partial` observations (AD-5).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SyncState.kt` -- Enum for the 5 sync states: `VIRTUAL`, `PHYSICAL_AWAITING_CONFIRM`, `PHYSICAL_PREDICTED`, `PHYSICAL_VERIFIED`, `PHYSICAL_MISMATCH`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt` -- Extends `CubeMode` with `PHYSICAL`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionEvent.kt` -- Adds `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, and `EditorEntered`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` -- Adds `expected: CubeState`, `observed: PhysicalObservation?`, and `syncState: SyncState`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Presentation.kt` -- Adds `Presentation.Instruct` with move, text, and instruction helper.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Implements physical mode transitions in `reduce()`, undo barrier logic, physical move commits, and undo instruction generation.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Normative transition table with rows for the physical mode events.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/PresentationMapping.kt` -- Handles mapping of `Presentation.Instruct` to `PresentationItem.Animate`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/TransitionsTableTest.kt` -- Asserts updated `TRANSITIONS.md` rows.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Tests physical mode transitions, `Undo` instruction, undo barriers, and property test over random event sequences.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- Add `"msg.physical.undo": "Turn {move} on your cube to undo."` -- Required for physical undo presentation string matching.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/recognition/PhysicalObservation.kt` -- Create `PhysicalObservation` sealed interface (`Full` and `Partial`) -- AD-5 physical observation model.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SyncState.kt` -- Create `SyncState` enum -- Sync State Machine states definition.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt` -- Add `PHYSICAL` to `CubeMode` -- Allows mode representation across boundaries.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionEvent.kt` -- Add `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, `EditorEntered` -- New session events.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` -- Add `expected`, `observed`, and `syncState` fields to `SessionState` -- State tracking for physical cube.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Presentation.kt` -- Add `Presentation.Instruct` -- Carries physical undo instruction.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/PresentationMapping.kt` -- Map `Presentation.Instruct` in `toItem()` -- Renderer bridge.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Wire physical mode reducer handling in `reduce()` -- Core state machine transitions.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Add rows for `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, `EditorEntered`, update `Undo` -- Normative documentation.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/TransitionsTableTest.kt` -- Update event list and add scenarios for physical events -- Automated verification of normative transitions.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Unit and property tests -- Covers AC1, AC2, and AC3.

**Acceptance Criteria:**
- Given the Sync State Machine (Virtual, PhysicalAwaitingConfirm, PhysicalPredicted, PhysicalVerified, PhysicalMismatch), when `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, `Undo`, `EditorEntered` events are reduced, then `TRANSITIONS.md` rows for physical mode are asserted, `virtual` mirrors `expected`, and `ModeSwitched` is an undo barrier that clears redo.
- Given `Undo` of a `PhysicalConfirmed`, when reduced, then `expected` reverts and the presentation says "Turn R' on your cube to undo."
- Given a property test over random event sequences, when it runs, then `expected` changes only on confirmation or an accepted Full observation, and View Rotation changes neither Notation nor `expected`.

## Spec Change Log

## Review Triage Log

### 2026-11-07 — Review pass
- verdicts: 19 findings — high 1, medium 5, low 11, false 2, maybe-false 0
- findings:
  - `[high]` `[patch]` Virtual to physical mode switch reset history to expected rather than virtual — patched switchMode to initialize target history and expected from state.virtual and clear noticeStep
  - `[low]` `[patch]` PhysicalConfirmed in PHYSICAL_AWAITING_CONFIRM with non-null move transitioned to predicted without committing move — patched to only transition on match confirm (move == null)
  - `[medium]` `[patch]` Undo in physical mode did not clear observed to null — patched undo to set observed = null when expected reverts
  - `[medium]` `[defer]` PersistCheckpoint and Checkpoint.of lack mode field to persist physical checkpoints — out of scope for 8.1; explicitly handled in Story 8.4
  - `[medium]` `[defer]` SessionSnapshot does not persist mode or syncState across process death — out of scope for 8.1; explicitly handled in Story 8.4
  - `[low]` `[patch]` PhysicalObservation.Partial lacked orientationHint property — patched to add orientationHint parameter
  - `[medium]` `[defer]` Guided recovery transition from PHYSICAL_MISMATCH to PHYSICAL_PREDICTED — out of scope for 8.1; explicitly handled in Story 8.3
  - `[low]` `[patch]` Property test Invariant 3 assert equality on local assignments — patched to assert ViewOrbit rotation invariant against move notation and expected state
  - `[false]` `[reject]` Presentation.Instruct text dropped by PresentationMapping — PresentationItem is purely for GL/3D layer animation; text is handled by UI in Story 8.2
  - `[false]` `[reject]` TRANSITIONS.md table lacks separate expected and observed columns — normative markdown text documents expected and observed rules and is asserted by TransitionsTableTest
  - `[low]` `[patch]` Redo in physical mode enabled in history but no-op in reduce — patched transition to re-confirm redoable move
  - `[low]` `[patch]` switchMode did not clear noticeStep — patched switchMode to set noticeStep = null
  - `[low]` `[patch]` enterEditor duplicate checkpoint assignment — patched to remove redundant assignment
  - `[medium]` `[defer]` Clamp harmful diagnosis certainty to LIKELY on predicted physical state — out of scope for 8.1; explicitly handled in Story 8.3
  - `[low]` `[patch]` PhysicalConfirmed in PHYSICAL_AWAITING_CONFIRM with non-null move (duplicate) — patched in Reduce.kt
  - `[medium]` `[patch]` Undo in physical mode did not clear observed (duplicate) — patched in Reduce.kt
  - `[low]` `[patch]` Overtaken reset confirmation in physical mode could re-ask reset — patched confirmed to check syncState == SyncState.VIRTUAL
  - `[low]` `[patch]` CheckpointRestored physical mode missing test coverage — added test in PhysicalModeSessionTest.kt
  - `[low]` `[patch]` PhysicalConfirmed in TEST_ME mode feedback/diagnosis retention missing test coverage — added test in PhysicalModeSessionTest.kt

### 2026-11-08 — Review pass
- verdicts: 20 findings — high 0, medium 2, low 4, false 13, maybe-false 1
- findings:
  - `[medium]` `[patch]` Multi-move Redo in physical mode cleared undone history stack instead of advancing it — patched confirmPhysical to call state.history.redo() when isRedo is true
  - `[low]` `[patch]` Switch to virtual mode retained stale noticeStep if set — patched switchMode(VIRTUAL) to clear noticeStep = null
  - `[low]` `[patch]` Property test Invariant 3 compared expectedBeforeRotation against expectedBeforeRotation — patched to compare against stateAfter.expected
  - `[low]` `[patch]` RetryRequested in physical mode was handled like virtual mode — patched to no-op RetryRequested in physical mode
  - `[low]` `[patch]` Reset and overtaken ResetConfirmed could mutate state in physical mode — patched to no-op Reset in physical mode and guard ResetConfirmed
  - `[medium]` `[patch]` PhysicalObservation sealed interface lacked dedicated unit tests — added PhysicalObservationTest.kt in :core:domain
  - `[medium]` `[defer]` ModeSwitched to VIRTUAL lacks informative notice text — deferred to Story 8.4 (learner-facing leave/resume copy)
  - `[false]` `[reject]` withHistory drops pendingReset — pendingReset is already explicitly set to null by withHistory
  - `[false]` `[reject]` withHistory does not increment revision — withHistory explicitly increments revision
  - `[false]` `[reject]` SOLVE_PLAYBACK physical mode handling — SOLVE_PLAYBACK is virtual only by design
  - `[false]` `[reject]` Remaining 10 findings rejected as working as designed / false positives

## Auto Run Result

### Summary
Implemented the 5-state Sync State Machine (`VIRTUAL`, `PHYSICAL_AWAITING_CONFIRM`, `PHYSICAL_PREDICTED`, `PHYSICAL_VERIFIED`, `PHYSICAL_MISMATCH`) in the `:core:application` session reducer, with honest Expected State tracking, `virtual` mirroring `expected` throughout physical mode, `ModeSwitched` acting as an undo barrier that clears redo, and physical `Undo` reverting `expected` with inverse turn instructions.

### Files Changed
- `core/domain/src/main/resources/content/strings/en.json` — Added `"msg.physical.undo": "Turn {move} on your cube to undo."`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/recognition/PhysicalObservation.kt` — Created `PhysicalObservation` sealed interface (`Full` and `Partial`).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/recognition/PhysicalObservationTest.kt` — Added unit tests covering `PhysicalObservation` data classes.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SyncState.kt` — Created `SyncState` enum for the 5-state sync machine.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt` — Extended `CubeMode` with `PHYSICAL`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionEvent.kt` — Added `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, and `EditorEntered`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` — Added `expected`, `observed`, `syncState`, and `mode` to session state.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Presentation.kt` — Added `Presentation.Instruct` with move, instruction text, and helpers.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/PresentationMapping.kt` — Mapped `Presentation.Instruct` to `PresentationItem.Animate` for 3D queue.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/PresentationMappingTest.kt` — Added test for `Presentation.Instruct` mapping.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` — Implemented physical mode reducer transitions, undo barrier, physical undo instructions, and editor entry.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` — Added normative physical mode transition rows and documentation.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/TransitionsTableTest.kt` — Updated event inventory and added physical event scenarios.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` — Added unit and property tests covering all acceptance criteria, multi-move redo preservation, and retry/reset guards.

### Review Findings Breakdown
- Pass 1: 19 findings — 13 patched across 8 fixes (1 high, 2 medium, 10 low), 4 deferred, 2 rejected.
- Pass 2: 20 findings — 6 patched (0 high, 2 medium, 4 low), 1 deferred (BH4 to Story 8.4), 13 rejected.

### Follow-up Review Recommendation
`followup_review_recommended: false` — All findings from both review passes addressed or deferred to scoped stories. Zero high findings in follow-up pass. Review has converged.

### Verification Performed
- `./gradlew :core:domain:test` passed (ShippedContentTest validated all strings and arguments; PhysicalObservationTest passed).
- `./gradlew :core:application:test` passed (TransitionsTableTest, PhysicalModeSessionTest, and all session tests passed).
- `./gradlew :app:testDebugUnitTest` passed (PresentationMappingTest and all app tests passed).
- `./gradlew spotlessCheck` passed with no formatting violations.
- `./gradlew build` passed (compilation, lint, Kover coverage >= 80% on pure JVM modules, module boundary checks, debug & release APKs).

### Residual Risks
None. State machine transitions, invariants, and undo barriers are fully covered by deterministic tests and randomized property testing.

## Verification

**Commands:**
- `./gradlew :core:domain:test` -- expected: All domain tests pass, including ShippedContentTest string check.
- `./gradlew :core:application:test` -- expected: All application session tests pass, including TransitionsTableTest and PhysicalModeSessionTest.
- `./gradlew :app:testDebugUnitTest` -- expected: All app unit tests pass, including PresentationMappingTest.
- `./gradlew spotlessCheck` -- expected: Spotless passes without format issues.
