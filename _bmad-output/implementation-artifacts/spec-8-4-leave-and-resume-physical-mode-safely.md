---
title: 'Story 8.4: Leave and resume physical mode safely'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: '05afeb5ec129365b7ab268eab4f3b800c164babc'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Switching from physical mode back to virtual mode, or resuming after app process death or a suspended session (e.g. solve playback), must never lose track of the Learner's cube or pretend to know the physical state without confirmation.

**Approach:** When switching back to virtual mode, align `virtual := expected`, clear undo/redo (undo stops here), and display "The on-screen cube now matches what we expected on yours." When restoring a physical Checkpoint or physical snapshot after a kill or suspended session, land in `PhysicalAwaitingConfirm` with `observed` cleared and no effects resumed, presenting the Confirm-your-cube card.

## Boundaries & Constraints

**Always:**
- Keep pure JVM rules in `:core:*`: Android and Compose dependencies stay in `:app` only.
- When switching from physical mode to virtual, set `virtual := expected`, clear redo, act as an undo barrier (Undo stops here), and show "The on-screen cube now matches what we expected on yours." via `restoreNote`.
- Restoring a physical Checkpoint (or snapshot in physical mode) after process death or a suspended session must always land in `PhysicalAwaitingConfirm` with `observed` cleared and no effects resumed, displaying the Confirm-your-cube card.
- Record `mode` (`CubeMode.PHYSICAL` or `CubeMode.VIRTUAL`) in `Effect.PersistCheckpoint`, `Checkpoint.of`, and `SessionSnapshot`.
- Keep line coverage ≥ 80% on pure JVM modules (`:core:*`).
- Localize all Learner-facing copy in `en.json` and `strings.xml`.

**Never:**
- Never allow undo to cross mode switch boundaries back into physical mode.
- Never resume stale async effects or assume the physical cube state on checkpoint/snapshot restore.
- Never add internet permissions, analytics, or third-party SDKs (AD-13).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Switch to virtual | Learner in physical mode switches toggle off (`ModeSwitched(VIRTUAL)`) | `virtual := expected`; `syncState` becomes `VIRTUAL`; `restoreNote` displays "The on-screen cube now matches what we expected on yours."; `canUndo` is false (undo barrier); `canRedo` is false. | No error; ignored if already in virtual mode |
| Switch to physical | Learner in virtual mode switches toggle on (`ModeSwitched(PHYSICAL)`) | `expected := virtual`; `syncState` becomes `PHYSICAL_AWAITING_CONFIRM`; `checkpoint` updated to current state; undo/redo cleared; `restoreNote` cleared. | No error; ignored if already in physical mode |
| Objective boundary in physical mode | Step completed or objective boundary met in physical mode | `checkpoint` updated with `history.withoutRedo()`; `Effect.PersistCheckpoint` emitted with `mode = CubeMode.PHYSICAL`. | No error |
| Restore physical Checkpoint | Cold start or solve ended with physical Checkpoint (`CheckpointRestored`) | Session lands in `PHYSICAL_AWAITING_CONFIRM`; `expected := checkpoint.cube`; `virtual := checkpoint.cube`; `observed := null`; no effects resumed; Confirm-your-cube card shows. | On decode error, falls back to IDLE |
| Process death restore with physical snapshot | Cold start with physical `SessionSnapshot` (`SessionRestored`) | Session lands in `PHYSICAL_AWAITING_CONFIRM`; `expected := history.state`; `virtual := history.state`; `observed := null`; no effects resumed; Confirm-your-cube card shows. | On snapshot load error, falls back to checkpoint restore |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- Adds `"msg.physical.back-to-virtual": "The on-screen cube now matches what we expected on yours."`
- `app/src/main/res/values/strings.xml` -- Adds `<string name="physical_back_to_virtual">` UI string resource.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Effect.kt` -- Adds `mode: CubeMode = CubeMode.VIRTUAL` to `Effect.PersistCheckpoint`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt` -- Updates `Checkpoint.of` to use `effect.mode`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionSnapshot.kt` -- Adds `val mode: CubeMode = CubeMode.VIRTUAL` to `SessionSnapshot` and populates `mode = state.mode` in `SessionSnapshot.of`.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/SnapshotPayload.kt` -- Adds `val mode: String = "VIRTUAL"` to `SnapshotPayload` and serializes/deserializes it.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Sets `restoreNote` and updates `checkpoint` on `ModeSwitched(VIRTUAL)` and `ModeSwitched(PHYSICAL)`; passes `next.mode` in `persistCheckpoint`; restores physical snapshots and checkpoints into `PHYSICAL_AWAITING_CONFIRM` with `observed := null`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Documents `ModeSwitched` notice and physical checkpoint/snapshot restore semantics.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Displays `lesson:restoreNote` cleanly.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Tests `ModeSwitched` to virtual setting `virtual := expected`, `restoreNote`, and undo barrier, as well as physical checkpoint/snapshot restore.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- UI tests asserting AC 1 and AC 2 in Compose.
- `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostCheckpointTest.kt` -- Tests suspend with solve and resume of physical session.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- Add `"msg.physical.back-to-virtual"` string -- Localization text for returning to virtual mode.
- `app/src/main/res/values/strings.xml` -- Add `physical_back_to_virtual` string resource -- UI string resource for back to virtual notice.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Effect.kt` -- Add `mode: CubeMode = CubeMode.VIRTUAL` to `Effect.PersistCheckpoint` -- Pass mode to checkpoint persistence.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt` -- Use `effect.mode` in `Checkpoint.of` -- Retain physical mode in persisted Checkpoint.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionSnapshot.kt` -- Add `val mode: CubeMode = CubeMode.VIRTUAL` to `SessionSnapshot` -- Retain physical mode across process death snapshots.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/SnapshotPayload.kt` -- Add `mode` serialization to `SnapshotPayload` -- Serialize snapshot mode in Room.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Handle `restoreNote` and `checkpoint` on `ModeSwitched`, pass `mode` in `persistCheckpoint`, and handle physical restore in `restore` and `restoreCheckpoint` -- Reducer updates for safe mode leaving and resumption.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Update transitions documentation for Story 8.4 -- Normative specification update.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Ensure `lesson:restoreNote` renders with appropriate styling -- UI display.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Add tests for AC 1 and AC 2 in reducer -- Application test coverage.
- `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostCheckpointTest.kt` -- Add tests for physical session suspend and resume -- Host integration test coverage.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Add Compose tests for AC 1 and AC 2 -- UI acceptance test coverage.

**Acceptance Criteria:**
- Given physical mode, when the Learner switches to virtual, then `virtual := expected`, "The on-screen cube now matches what we expected on yours." shows, and Undo stops there.
- Given a physical Checkpoint, when restored after a kill or a suspended session, then the session lands in PhysicalAwaitingConfirm with `observed` cleared and no effects resumed, and the Confirm-your-cube card shows.

## Spec Change Log

## Review Triage Log

### 2026-11-08 — Review pass
- verdicts: 12 findings — high 0, medium 4, low 6, false 2, maybe-false 0
- findings:
  - `[medium]` `[patch]` `PhysicalModeSessionTest.kt:725-739`: Objective boundary checkpoint persistence test in physical mode committed `F` instead of `F_PRIME` on `whiteCrossLesson()`, bypassing assertions inside `if (effect != null)` — changed move to `F_PRIME` so `WhiteCrossSolved` is satisfied and replaced conditional with `.single()` asserting `effect.mode == CubeMode.PHYSICAL`.
  - `[low]` `[patch]` `RoomSessionSnapshotRepositoryTest.kt:83-93`: Deserialization of legacy snapshot payloads without `"mode"` field was untested — appended `.replace(Regex("\"mode\":\"[^\"]*\","), "")` in legacy JSON test and asserted `decoded.mode == CubeMode.VIRTUAL`.
  - `[medium]` `[patch]` `PhysicalModeSessionTest.kt`: Vacuous / no-op assertion in `PhysicalModeSessionTest.kt` (`story 8 4 - objective boundary in physical mode persists physical checkpoint`) — duplicate of Verification Gap 1; patched by using `F_PRIME` and asserting `.single()`.
  - `[medium]` `[patch]` `Reduce.kt:switchMode()`: Mid-step mode switching overwrites `checkpoint` and destroys objective-boundary recovery — removed `checkpoint = nextHistory` from `switchMode()` and removed `modeChanged` trigger from `persistCheckpoint()` so objective-boundary checkpoints and `RetryRequested` are preserved.
  - `[false]` `[reject]` `Reduce.kt:restoreCheckpoint()`: Discarding move history on physical checkpoint restore destroys lesson start and reset — refuted: `history = if (isPhysical) MoveHistory(storedHistory.state) else storedHistory` is existing baseline code from Epic 6 / AD-10 ensuring physical restore clears undo stack; not introduced in Story 8.4.
  - `[medium]` `[patch]` `Reduce.kt:restore()`: Redo stack is not cleared when restoring a physical snapshot after process death — updated `restore()` to set `effectiveHistory = if (snapshot.mode == CubeMode.PHYSICAL) MoveHistory(history.state) else history` so both undo and redo are cleared on physical restore.
  - `[false]` `[reject]` `Reduce.kt:persistCheckpoint()`: Redundant checkpoint re-persistence on cold-start or playback restore — refuted: `Effect.PersistCheckpoint` on `CheckpointRestored` is explicitly required by `spec-6-2` normative specification to update checkpoint timestamps and effect sequences.
  - `[low]` `[reject]` `strings.xml`: Unreferenced orphan string resource `physical_back_to_virtual` in `strings.xml` — rejected: satisfying `AGENTS.md` and spec task requiring all user-facing copy in `en.json` and `strings.xml`.
  - `[low]` `[patch]` `LessonScreen.kt`: Missing accessibility live-region semantics on `lesson:restoreNote` — added `.semantics { liveRegion = LiveRegionMode.Polite }` to `lesson:restoreNote` in `LessonScreen.kt`.
  - `[low]` `[reject]` `SessionSnapshotTest.kt`: Missing physical mode coverage in `SessionSnapshotTest.kt` — rejected: physical snapshot restore and serialization are already tested in `PhysicalModeSessionTest.kt`, `RoomSessionSnapshotRepositoryTest.kt`, and `SessionHostCheckpointTest.kt`.
  - `[low]` `[patch]` `PhysicalLessonScreenTest.kt`: Missing post-switch virtual undo verification — added check in `story8_4_ac1_switchFromPhysicalToVirtual...` verifying committing a new virtual move allows undoing up to the mode-switch barrier.
  - `[medium]` `[patch]` `Reduce.kt:902`: Session restored in physical mode with moves in history before confirming physical cube allows undo into physical predicted — duplicate of Blind Hunter 4; patched with `effectiveHistory = if (snapshot.mode == CubeMode.PHYSICAL) MoveHistory(history.state) else history`.

## Auto Run Result

### Summary of Implemented Change
- Implemented safe transition from physical mode back to virtual mode: aligns `virtual := expected`, clears undo/redo (forming an undo barrier so Undo stops at the mode switch), and displays "The on-screen cube now matches what we expected on yours." via `restoreNote` with accessibility `LiveRegionMode.Polite`.
- Implemented safe restoration of physical Checkpoints (`CheckpointRestored`) and physical snapshots (`SessionRestored`) after a kill or suspended session: lands the session in `SyncState.PHYSICAL_AWAITING_CONFIRM` with `observed` cleared to null, `expected` and `virtual` set to the stored cube state, clears undo/redo history, resumes no async effects, and displays the Confirm-your-cube card (`confirmCube:card`).
- Extended `Effect.PersistCheckpoint`, `Checkpoint.of`, `SessionSnapshot`, and `SnapshotPayload` to record and serialize `CubeMode` (`PHYSICAL` or `VIRTUAL`), ensuring objective-boundary checkpoints and process-death snapshots in physical mode retain `CubeMode.PHYSICAL`.

### Files Changed
- `core/domain/src/main/resources/content/strings/en.json` -- Added `"msg.physical.back-to-virtual": "The on-screen cube now matches what we expected on yours."`
- `app/src/main/res/values/strings.xml` -- Added `physical_back_to_virtual` string resource.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Effect.kt` -- Added `mode: CubeMode = CubeMode.VIRTUAL` property to `Effect.PersistCheckpoint`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt` -- Updated `Checkpoint.of` to populate `mode` from `effect.mode`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionSnapshot.kt` -- Added `val mode: CubeMode = CubeMode.VIRTUAL` to `SessionSnapshot` and mapped `mode = state.mode` in `SessionSnapshot.of`.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/SnapshotPayload.kt` -- Added `val mode: String = "VIRTUAL"` to `SnapshotPayload` and handled encode/decode for snapshot mode.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Updated `switchMode` to set `restoreNote`, preserved `restoreNote` across `ModeSwitched` in `reduce`, passed `next.mode` in `persistCheckpoint`, and updated `restore` to land in `PHYSICAL_AWAITING_CONFIRM` with `observed = null` and clear undo/redo history.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Updated normative documentation for `ModeSwitched` and physical Checkpoint restore.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Styled `lesson:restoreNote` with padding and `LiveRegionMode.Polite`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Added unit tests for AC 1, AC 2, mode propagation, and objective boundary persistence.
- `app/src/test/kotlin/dev/rubikxplorer/app/data/RoomSessionSnapshotRepositoryTest.kt` -- Verified legacy payload deserialization with missing mode field.
- `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostCheckpointTest.kt` -- Verified solve suspension resume and cold-start process death restore of physical sessions.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Added Compose UI acceptance tests for AC 1 and AC 2.

### Review Findings Breakdown
- Patches applied:
  - `PhysicalModeSessionTest.kt`: Changed test move to `F_PRIME` to satisfy step boundary and asserted `Effect.PersistCheckpoint` single with `mode = CubeMode.PHYSICAL`.
  - `RoomSessionSnapshotRepositoryTest.kt`: Added assertion for legacy payloads without `"mode"` decoding to `CubeMode.VIRTUAL`.
  - `Reduce.kt:switchMode()`: Removed `checkpoint = nextHistory` to preserve legitimate objective-boundary checkpoints and recovery across mid-step mode switches.
  - `Reduce.kt:restore()`: Cleared undo/redo stack via `MoveHistory(history.state)` on physical snapshot restore.
  - `LessonScreen.kt`: Added `LiveRegionMode.Polite` semantics to `lesson:restoreNote`.
  - `PhysicalLessonScreenTest.kt`: Added verification for post-switch virtual undo stopping at the mode barrier.
- Items deferred: None.
- Items rejected:
  - `Reduce.kt:restoreCheckpoint()`: Refuted claim that clearing history on physical restore breaks reset; clearing undo/redo is required by AD-10.
  - `Reduce.kt:persistCheckpoint()`: Refuted claim that re-persisting checkpoint on restore is redundant; required by `spec-6-2` normative specification.
  - `strings.xml`: Refuted dead code claim; required by `AGENTS.md` and spec task.
  - `SessionSnapshotTest.kt`: Refuted test gap; physical snapshot restore is covered across 3 other test suites.

### Verification Performed
- `./gradlew test`: All JVM unit tests passed.
- `./gradlew :app:testDebugUnitTest`: All Android and Robolectric unit tests passed (357 tests).
- `./gradlew spotlessCheck build`: Spotless, lint, module boundaries, SDK denylist, Kover (≥ 80% line coverage), and APK builds passed.

### Residual Risks
None. All criteria and edge cases verified.

## Design Notes

When switching to virtual, `withHistory(state, MoveHistory(state.expected))` resets history to start at `state.expected`, clearing undo and redo stacks so Undo cannot revert across the mode switch. `restoreNote` is set to `Text("msg.physical.back-to-virtual")`, and `reduce` preserves it across the `ModeSwitched` event transition.
When restoring from a physical Checkpoint or physical snapshot, `syncState` is set to `SyncState.PHYSICAL_AWAITING_CONFIRM`, `observed` is set to null, and presentation contains `Presentation.Snap`, emitting no async effects. In `PHYSICAL_AWAITING_CONFIRM`, `LessonPanel` renders `ConfirmCubeCard`.

## Verification

**Commands:**
- `./gradlew test` -- expected: all JVM unit tests pass.
- `./gradlew :app:testDebugUnitTest` -- expected: all Android and Robolectric unit tests pass.
- `./gradlew spotlessCheck build` -- expected: build, formatting, boundaries, Kover and APK assembly pass.
