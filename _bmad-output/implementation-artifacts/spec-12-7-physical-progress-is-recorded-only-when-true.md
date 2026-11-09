---
title: 'Physical progress is recorded only when true'
type: 'bugfix'
created: '2026-11-08'
status: 'done'
baseline_revision: 'cf23bbdc317d6b7f01322ba4ade9d97e53558123'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-retro-2026-11-08.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Attempt rows and the Mastery milestone still count a physical Objective that the Learner has not confirmed.
    evidence: |-
      attempts() in Reduce.kt emits AttemptEnded(COMPLETED) when objectiveMet flips, including on physical Done taps and EditorEntered, and isMasteryMilestone checks !isPhysical but not physicalSolveUnconfirmed. Pre-existing, and the story's ACs name LessonCompleted only.
    location: >-
      core/application/src/main/kotlin/dev/cubentia/application/session/Reduce.kt attempts(); app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt isMasteryMilestone
    severity: medium
  - summary: >-
      A confirmed solve can stay unrecorded if the completion write fails or the process dies between the snapshot save and the completion write.
    evidence: |-
      SessionHost.dispatch enqueues the snapshot before completeLesson as two separate writes with no retry; once physicalSolveConfirmed is restored, a repeat confirmation is rejected. The non-atomic, no-retry write is the same mechanism a virtual completion already has.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt completion write
    severity: medium
---

<intent-contract>

## Intent

**Problem:** In Physical-cube Mode, `SessionHost.dispatch` writes `LessonCompleted` (and Stage progress) the moment `objectiveMet` flips, which in physical mode is only a prediction (Done taps, `EditorEntered`); 8.6 hid this with `rememberSaveable` flags and a `pendingPhysicalSolve` workaround that are lost on re-entry. Physical Undo's instruction ("Turn {move} on your cube to undo.") is mapped to an animation and never shown, physical Redo silently moves `expected`, and switching to physical never persists a physical Checkpoint, so `endSolve`/checkpoint resume lands in virtual mode.

**Approach:** Add a `PhysicalSolveConfirmed` SessionEvent reduced by `reduce` that sets session-owned confirmed/unconfirmed flags (in `SessionState` and the snapshot); `SessionHost` writes completion for a physical session only on that accepted event. Render `Presentation.Instruct` as text in a polite live region (cube snaps, no animation), disable Redo in physical mode (UI and reducer), and emit `PersistCheckpoint` on every accepted Lesson `ModeSwitched` with the new mode and the unchanged `checkpoint` history.

## Boundaries & Constraints

**Always:**
- All new state changes go through `SessionEvent` → `reduce()`; the flags are `SessionState` fields, persisted in `SessionSnapshot` and `SnapshotPayload` (JSON, defaults `false`, no Room schema change).
- `PhysicalSolveConfirmed` is accepted only in a LESSON or PRACTICE session, `syncState` ∈ {`PHYSICAL_PREDICTED`, `PHYSICAL_VERIFIED`}, `objectiveMet`, and not already confirmed; otherwise it returns the same state. It never changes `revision`, history, `expected` or `checkpoint`.
- Flag rule after every Lesson/Practice transition: objective unmet → both flags false; accepted `PhysicalSolveConfirmed` → confirmed true, unconfirmed false; physical with objective met and not confirmed → unconfirmed true. `LessonStarted`/`PracticeStarted` start both false (then the rule). `SessionRestored` takes both from the snapshot.
- Completion write in `SessionHost`: a physical session (state `isPhysical`) writes only on an accepted `PhysicalSolveConfirmed` (`!before.physicalSolveConfirmed && after.physicalSolveConfirmed`); a virtual session keeps today's flip rule. Existing exclusions (skip-check, restores, non-LESSON) stay.
- The gate applies to every physical Lesson: *Yes, it's solved* (finale) and *Next* with the Objective met (other Lessons) both dispatch `PhysicalSolveConfirmed`, awaiting the dispatch before closing.
- The `"lesson-06-03"` literals in `app/src/main` become one named source (a single constant/extension used by every caller).
- Mode-switch checkpoint: persisted with `mode = next.mode` and `history = next.checkpoint`; `SessionState.checkpoint` (the recovery boundary) is not changed by `ModeSwitched`.

**Never:**
- No `rememberSaveable`/screen state for confirmed or unconfirmed; no `pendingPhysicalSolve`.
- Do not touch the mismatch ladder (12.8), physical copy sources / skip-check self-report (12.9), or `SolutionPlayback` fixes (3.7).
- No numeric certainty, no new strings outside `en.json` keys (reuse `msg.physical.undo`), no edits under `_bmad/`, `.claude/skills/` or planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Physical finale met, unconfirmed | lesson-06-03 physical, Done taps meet Objective | no `LessonCompleted`; `physicalSolveUnconfirmed = true`; no milestone | — |
| Confirm | then `PhysicalSolveConfirmed` | exactly one `LessonCompleted` write; confirmed true; milestone shows (first time) | — |
| Unconfirmed → virtual | then `ModeSwitched(VIRTUAL)` | no write; unconfirmed stays true; virtual milestone blocked | — |
| Re-entry / process death before confirm | close & reopen, or snapshot restore | unconfirmed restored; still no `LessonCompleted`; confirming after `PhysicalConfirmed(null)` writes it | — |
| Process death after confirm | snapshot restore | confirmed restored; no second write | — |
| Confirm in wrong state | AWAITING_CONFIRM, MISMATCH, VIRTUAL, objective unmet, IDLE/playback | state unchanged, no write | ignored |
| Physical Undo | Undo in physical | Instruct text "Turn R' on your cube to undo." shown in polite live region `lesson:physicalInstruction`; cube snaps to target, no Animate item | — |
| Physical Redo | Redo button / `Redo` event in physical | button disabled; reducer returns same state | ignored |
| Switch to physical | `ModeSwitched(PHYSICAL)` in a Lesson | `PersistCheckpoint(mode=PHYSICAL)` with unchanged checkpoint history; `checkpoint` field unchanged | save failure → existing `CheckpointSaveFailed` |

</intent-contract>

## Code Map

- `core/application/.../session/SessionEvent.kt` -- add `PhysicalSolveConfirmed(at)` with KDoc.
- `core/application/.../session/SessionState.kt` -- add `physicalSolveConfirmed`, `physicalSolveUnconfirmed` (default false; in `IDLE`); KDoc says they never advance revision.
- `core/application/.../session/Reduce.kt` -- `transition()` `when` (l.~94-210) add branch; `Redo` physical branch (l.129-136) → `Transition(state)`; `reduce()` post-processing (l.~60-85) is where the flag rule fits; `start`/`startPractice` reset; `restore` (l.867) copy flags from snapshot; `persistCheckpoint` (l.926-950) early return at `history == before.checkpoint` must not skip an accepted `ModeSwitched` (state changed, kind LESSON); `switchMode` (l.459) leaves `checkpoint` alone.
- `core/application/.../session/SessionSnapshot.kt` -- two fields with defaults, filled in `of`.
- `core/application/.../session/TRANSITIONS.md` -- table row for `PhysicalSolveConfirmed` (mutates `tutor`, no, no), Redo-physical no-op, mode-switch checkpoint, flag rule; `TransitionsTableTest` checks rows against the reducer.
- `app/.../data/SnapshotPayload.kt` -- two `Boolean = false` fields, encode/decode.
- `app/.../session/SessionHost.kt` -- completion gate at l.474-503.
- `app/.../session/PresentationMapping.kt:11` -- `Instruct` → `PresentationItem.Snap(revision, target)`.
- `app/.../ui/lesson/LessonScreen.kt` -- remove flags l.166-169, LaunchedEffect l.202-207, `pendingPhysicalSolve` l.224-236; milestone l.189-199 reads session flags; `onYesItsSolved` l.336 dispatches the event; `send` l.527 captures Instruct text into a polite live-region `Text` (clear on next send / leaving physical); `onNext` l.557 physical+objectiveMet dispatches then closes; Redo enabled l.1029 `canRedo && !isPhysical`; literals l.172, 603, 789, 857, 940.
- `app/.../ui/lesson/LessonPanel.kt:100,193` -- consumes `isEndOfStage6`; unchanged API.
- Tests: `core/application/src/test/.../PhysicalModeSessionTest.kt`, `SessionSnapshotTest.kt`, `TransitionsTableTest.kt`; `app/src/test/.../data/SessionHostProgressTest.kt` (has a progress-writer host), `SessionHostCheckpointTest.kt`, `RoomSessionSnapshotRepositoryTest.kt`; `app/src/test/.../session/PresentationMappingTest.kt`, `SessionRenderSyncTest.kt`; `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt`, `LessonScreenTest.kt:1974-2064`.

## Tasks & Acceptance

**Execution:**
- `core/application/.../SessionEvent.kt`, `SessionState.kt`, `SessionSnapshot.kt`, `Reduce.kt` -- add event, flags, flag rule, Redo-physical no-op, mode-switch checkpoint emission, restore of flags -- reducer owns all of it (AD-3).
- `core/application/.../TRANSITIONS.md` -- document the above -- normative table must match reducer.
- `app/.../data/SnapshotPayload.kt` -- persist flags -- process death.
- `app/.../session/SessionHost.kt` -- physical completion gate -- honest progress.
- `app/.../session/PresentationMapping.kt`, `app/.../ui/lesson/LessonScreen.kt` -- Instruct rendering, Redo disabled, session-flag milestone, workaround removal, one finale source, Next/Yes dispatch -- UI reads session state only.
- Tests (paths above) -- reducer flag lifecycle and ignored states; snapshot/payload round-trip; Redo-physical no-op; ModeSwitched emits PersistCheckpoint(mode) with unchanged checkpoint (both directions); SessionHost with progress writer: no write before confirm, one write after, re-entry and process-death (snapshot restore) before and after confirming; `SessionHostCheckpointTest`: switch → `SolvePlaybackStarted` → `endSolve` → `PHYSICAL_AWAITING_CONFIRM`; mapping Instruct→Snap; screen: undo text visible with polite live region, Redo disabled in physical, milestone only after *Yes, it's solved*; update tests asserting the old behavior.

**Acceptance Criteria:**
- Given Stage 6 in Physical-cube Mode with the Objective met, when the Learner has not tapped *Yes, it's solved*, then no `LessonCompleted` is written; `PhysicalSolveConfirmed` through `reduce` gates `completeLesson` in physical sessions; the flags live in `SessionState` and the snapshot; `pendingPhysicalSolve` is gone; `"lesson-06-03"` has one source in `app/src/main`; tests cover re-entry and process death before and after confirming.
- Given physical mode, when the Learner taps Undo, then "Turn {move} on your cube to undo." is shown and announced in a polite live region instead of an animation, and Redo is disabled.
- Given a mode switch to physical, when it is reduced, then a physical Checkpoint is persisted without moving the recovery boundary, and `SessionHostCheckpointTest` covers switch → `SolvePlaybackStarted` → `endSolve` → `PHYSICAL_AWAITING_CONFIRM`.

## Spec Change Log

## Review Triage Log

### 2026-11-08 — Review pass
- verdicts: 34 findings — high 0, medium 15, low 10, false 9, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: Checkpoint restore drops the solve flags — fixed: `physicalSolveUnconfirmed` carried on `Effect.PersistCheckpoint`, `Checkpoint`, `CheckpointPayload` and copied in `restoreCheckpoint`.
  - `[medium]` `[defer]` Blind: a confirmed solve can stay unrecorded after a failed or interrupted write — same non-atomic, no-retry write as virtual completion; deferred.
  - `[low]` `[reject]` Blind: a solve recorded in virtual is relabelled unconfirmed on a switch to physical and can write again — a repeat row is harmless (`StageProgress` uses any-match; virtual re-solves already append), the path is rare, and a guard adds complexity.
  - `[medium]` `[defer]` Blind: attempts complete on a predicted Objective — pre-existing; the intent is exactly the ACs, which name `LessonCompleted`.
  - `[medium]` `[defer]` Blind: `isMasteryMilestone` ignores the unconfirmed flag — same root as the attempts entry; deferred.
  - `[false]` `[reject]` Blind: leaving a met physical Lesson without *Next* records nothing — that is the required outcome; the host keeps the session, so reopening still offers the confirmation.
  - `[medium]` `[patch]` Blind: re-entry coverage is thin — fixed: screen-level remount tests before and after confirming; the misnamed host test was removed.
  - `[false]` `[reject]` Blind: instruction not cleared on Reset or Hint paths — Reset is disabled in physical mode (`canReset`, reducer no-op) and a Hint does not change the cube, so the text stays correct.
  - `[low]` `[patch]` Blind: saved instruction reappears after restore — fixed: cleared on `PHYSICAL_AWAITING_CONFIRM` and `VIRTUAL`.
  - `[medium]` `[patch]` Blind: live region can miss repeated identical instructions — fixed: waits one frame after the clear before setting the text.
  - `[false]` `[reject]` Blind: *Next*/*Yes* double tap and rejected confirm — `onClose` is an idempotent state clear in `AppShell`, a repeat confirm is rejected, and closing without a write is honest.
  - `[medium]` `[patch]` Edge: unconfirmed lost through a VIRTUAL Checkpoint — same fix as the Blind Checkpoint row.
  - `[low]` `[reject]` Edge: duplicate `LessonCompleted` after a virtual solve then physical confirm — as the Blind row above.
  - `[low]` `[reject]` Edge: confirmed flag lost through a physical Checkpoint causes a repeat write — harmless repeat row, and it needs Solve playback or a lost snapshot.
  - `[medium]` `[defer]` Edge: completion write failure or kill after the snapshot — as the Blind deferred write row.
  - `[false]` `[reject]` Edge: confirmed flag survives a content change — `restoreLatest` restores a snapshot only when `contentRevision` matches.
  - `[low]` `[reject]` Edge: a second send races the first Undo's instruction — needs a second tap within the dispatch latency; a counter adds state for an unreachable timing.
  - `[medium]` `[patch]` Edge: identical instruction silent in the live region — same fix as the Blind live-region row.
  - `[false]` `[reject]` Edge: upgraders with premature `LessonCompleted` rows — no installed users yet (epic-12 context).
  - `[medium]` `[patch]` Verification: *Yes* close-versus-celebrate branch untested — fixed: seeded replay test asserts one close; the first-solve test asserts none.
  - `[low]` `[patch]` Verification: clearing the instruction on leaving physical unverified — fixed: Undo, virtual, physical, then asserts empty.
  - `[low]` `[reject]` Verification other: duplicate completion after a virtual solve — as the Blind row above.
  - `[medium]` `[patch]` Verification other: unconfirmed lost through a VIRTUAL Checkpoint — same fix as the Blind Checkpoint row.
  - `[medium]` `[patch]` Verification other: the re-entry test performs no re-entry, none after confirming — same fix as the Blind re-entry row.
  - `[medium]` `[defer]` Intent: attempt rows outside the gate (all-progress reading) — as the attempts entry.
  - `[false]` `[reject]` Intent: a virtual turn and its inverse re-flip the Objective — that is a true virtual solve, and the flag rule clears only when the Objective is unmet.
  - `[medium]` `[patch]` Intent: physical reopen at the *Yes* prompt not exercised — same fix as the Blind re-entry row.
  - `[medium]` `[patch]` Intent: re-entry after confirming absent — same fix as the Blind re-entry row.
  - `[low]` `[reject]` Intent: screen consumers after process death not exercised — host plus Room with a real reopen covers it; `initialCompleted` reads the same log the remount tests use.
  - `[low]` `[reject]` Intent: announcement inferred from semantics — the limit of JVM tests; recorded as a residual risk.
  - `[false]` `[reject]` Intent: Solve playback's physical Undo has no text surface — playback has no caller in `app/src/main` (Story 3.7), and the spec excludes it.
  - `[false]` `[reject]` Intent: non-finale physical Lessons now record on *Next* — the recorded reading of "gates `completeLesson` in physical sessions" (Design Notes).
  - `[low]` `[reject]` Intent: single-write guarantee rests on the flag — as the Blind duplicate row.
  - `[false]` `[reject]` Intent: skip-check *Next* bypasses confirmation — the host excludes `skip-check-` Lessons from the write; Story 12.9 owns the self-report path.

### 2026-11-08 — Review pass
- verdicts: 30 findings — high 0, medium 4, low 17, false 9, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: a virtual solve then a physical confirmation writes a second `LessonCompleted` — carried: harmless repeat row (`StageProgress` uses any-match; virtual re-solves already append), rare path, and a guard adds complexity.
  - `[low]` `[reject]` Blind: confirmation is not reflected in the persisted Checkpoint, so a resume re-asks and can write again — carried: harmless repeat row that needs Solve playback or a lost snapshot.
  - `[low]` `[patch]` Blind: `SessionState.physicalSolveUnconfirmed` KDoc says no milestone can skip the confirmation, but `isMasteryMilestone` does not read it — fixed: the KDoc now names the first-solve milestone; the Mastery behaviour stays deferred.
  - `[low]` `[reject]` Blind: an unconfirmed solve switched to virtual is a silent dead end on *Next* — real, but "no write" is the matrix's required outcome for that state, the Learner must leave physical mode at the prompt to meet it, and a hint needs a new UI branch and copy.
  - `[low]` `[reject]` Blind: the instruction write in `send` has no staleness guard — carried: needs a second tap within the dispatch latency, and a counter adds state for that timing.
  - `[low]` `[reject]` Blind: the repeated-instruction behaviour has no test — real, but only identical consecutive Undos under TalkBack reach it, and frame stepping on the JVM still would not prove an announcement; recorded as a residual risk.
  - `[false]` `[reject]` Blind: the two booleans allow an impossible state — `reduce` cannot produce both true: an accepted confirm clears unconfirmed, the rule sets unconfirmed only when not confirmed, and `restoreCheckpoint` starts from `start()`, which clears confirmed. Only a hand-corrupted payload could, and two flags are what the intent requires.
  - `[false]` `[reject]` Blind: upgrade behaviour for sessions already in progress — carried: no installed users yet (epic-12 context).
  - `[false]` `[reject]` Blind: `physicalSolveConfirmed` survives a later mismatch or editor entry — that is the intent's flag rule (only an unmet Objective clears it); the completion was already written on the Learner's word, no second write follows, and no bad outcome results.
  - `[false]` `[reject]` Blind: the confirm handlers ignore whether the event was accepted — carried: a repeat confirm is rejected, and closing without a write is honest.
  - `[low]` `[reject]` Blind: the empty live-region `Text` reserves a blank line — a steady one-line gap is not a defect a Learner meets; collapsing it either removes the node from the semantics tree (its insertion is silent, against AC 2) or makes the stage jump on every Undo.
  - `[low]` `[reject]` Blind: Redo is a dead control in physical mode — the intent's matrix requires "button disabled", so hiding it would contradict the intent.
  - `[low]` `[reject]` Edge: duplicate `LessonCompleted` after a virtual solve then physical confirm — carried: as the Blind row above.
  - `[low]` `[reject]` Edge: confirmed flag lost through a physical Checkpoint causes a repeat write — carried: harmless repeat row that needs Solve playback or a lost snapshot.
  - `[medium]` `[defer]` Edge: completion write failure or kill after the snapshot — carried: already in `deferred`; same non-atomic, no-retry write as virtual completion.
  - `[low]` `[reject]` Edge: a second send races the first Undo's instruction — carried: as the Blind row above.
  - `[medium]` `[defer]` Edge: `isMasteryMilestone` ignores the unconfirmed flag — carried: already in `deferred` with the attempts entry.
  - `[medium]` `[patch]` Verification: a confirmed solve is not verified to survive a close that unmounts the screen — fixed: `story12_7_ac1_nextRecordsTheSolveWhenClosingUnmountsTheScreen` and `story12_7_ac1_yesConfirmsWhenClosingUnmountsTheScreen` unmount on close over a background-confined host with an in-flight write; the already-completed *Yes* test now asserts the confirmation.
  - `[low]` `[patch]` Verification: clearing the saved instruction on restore is not observed — fixed: `story12_7_ac2_savedInstructionIsClearedWhenRestoredToConfirmYourCube` restores saved state over a host in `PHYSICAL_AWAITING_CONFIRM`.
  - `[low]` `[reject]` Verification: the repeated-instruction live-region update is not verified — as the Blind row above; the filed disposition was defer, but the gap is caused by this change, so it does not meet the defer route.
  - `[low]` `[reject]` Intent: a virtual-recorded solve confirmed in physical writes twice — carried: as the Blind row above.
  - `[low]` `[reject]` Intent: Checkpoint resume after confirming re-asks and writes again — carried: harmless repeat row that needs Solve playback or a lost snapshot.
  - `[false]` `[reject]` Intent: the Checkpoint carries a flag the intent's persistence list does not name — JSON-only with a false default, so the "no Room schema change" constraint holds, and without it a Checkpoint resume would skip the confirmation.
  - `[low]` `[reject]` Intent: the on-screen sequence after a real process death is not exercised end to end — carried: host plus Room with a real reopen covers it, and `initialCompleted` reads the same log the remount tests use.
  - `[medium]` `[defer]` Intent: Mastery and attempt rows outside the gate — carried: already in `deferred`.
  - `[false]` `[reject]` Intent: "no Animate" is asserted only at `toItem` — `send` submits `presentation.toItems()` directly and `toItem` has one `Instruct` branch, so no other path can animate it.
  - `[low]` `[reject]` Intent: the `withFrameNanos` wait has no test — as the Blind row above.
  - `[false]` `[reject]` Intent: Solve playback's physical Undo has no text surface — carried: playback has no caller in `app/src/main` (Story 3.7), and the spec excludes it.
  - `[false]` `[reject]` Intent: the flag rule skips ignored events — documented in `TRANSITIONS.md` and pinned by a test; it differs from the other reading only for an inconsistent restored state.
  - `[false]` `[reject]` Intent: additions the intent does not mention — the instruction state is presentation, not confirmed or unconfirmed state, and neither addition breaches the Never list.

## Design Notes

The gate covers every physical Lesson, not only the finale: the AC says "gates `completeLesson` in physical sessions", and a non-finale physical Lesson already asks "If your cube matches the picture, this step is done." with *Next* as the Learner's answer, so *Next* is that Lesson's confirmation. This keeps the reducer free of any finale knowledge; only the UI needs the single finale source (copy and milestone).

Persisting on both mode-switch directions is required: persisting only PHYSICAL would leave a stale PHYSICAL Checkpoint after switching back to virtual.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply` -- formatting applied
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (lint, JVM tests, Kover, gates)
- `grep -rn '"lesson-06-03"' app/src/main` -- expected: exactly one match
- `grep -rn 'pendingPhysicalSolve\|physicalFirstSolveConfirmed' app/src/main` -- expected: no match

## Auto Run Result

Status: done
Blocking condition: none

**Summary:** Physical sessions now record `LessonCompleted` only on an accepted `PhysicalSolveConfirmed`, with confirmed and unconfirmed flags in `SessionState`, the snapshot and (unconfirmed) the Checkpoint. Physical Undo shows its Instruct text in a polite live region and snaps the cube, physical Redo is disabled in the UI and a no-op in the reducer, and every accepted Lesson `ModeSwitched` persists a Checkpoint with the new mode and an unchanged boundary.

**Files changed:**
- `core/application/.../session/SessionEvent.kt`, `SessionState.kt`, `SessionSnapshot.kt`, `Reduce.kt` -- event, flags, flag rule, Redo no-op, mode-switch Checkpoint.
- `core/application/.../session/Effect.kt`, `Checkpoint.kt` -- carry `physicalSolveUnconfirmed`.
- `core/application/.../session/TRANSITIONS.md` -- normative table and rules.
- `app/.../data/SnapshotPayload.kt`, `CheckpointPayload.kt` -- persist the flags (defaults false, no format bump).
- `app/.../session/SessionHost.kt` -- physical completion gate.
- `app/.../session/PresentationMapping.kt` -- `Instruct` maps to `Snap`.
- `app/.../ui/lesson/LessonScreen.kt` -- instruction live region, Redo disabled, session-flag milestone, `isCourseFinale` single source, *Yes*/*Next* dispatch.
- Tests in `core/application/src/test` and `app/src/test` -- flag lifecycle, snapshot and payload round trips, host progress and process death, Checkpoint path, screen behaviour and remounts.

**Review:** 34 findings. Patches applied: 4 medium entries (Checkpoint flag, re-entry tests, repeated-instruction live region, *Yes* close branch) and 2 low entries (clear on restore, clear on leaving physical). Deferred: 2 entries (attempts and Mastery; non-atomic completion write). Rejected: every `false` and `low` row, each with its reason in the Review Triage Log.

**Follow-up review recommended:** true (patched: high 0, medium 4, low 2). Unverified risk: the one-frame wait that should make two identical Undos announce has no test and TalkBack has not been run, and the `PHYSICAL_AWAITING_CONFIRM` instruction clear has no test.

**Verification:** `./gradlew spotlessApply`, then `./gradlew spotlessCheck build`: BUILD SUCCESSFUL after the patches (lint, JVM tests, Kover, gates). `grep '"lesson-06-03"' app/src/main`: one match. `grep 'pendingPhysicalSolve|physicalFirstSolveConfirmed' app/src/main`: no match. Matrix audit: every row has a covering test that ran with 0 failures and 0 skipped (JUnit XML before the patches; the full build re-ran them after).

**Residual risks:** Nothing ran on a device or emulator. A virtual-recorded Lesson confirmed in physical mode appends a harmless repeat `LessonCompleted`. *Next* in `PHYSICAL_AWAITING_CONFIRM` or `PHYSICAL_MISMATCH` closes without a write and without telling the Learner. Deferred items above.

### Follow-up review pass (2026-11-08)

**Summary:** A fresh four-layer review of the change (all layers on Claude, because the Codex marker file was absent) found no defect in the production behaviour. It produced one comment correction and two test gaps, now closed.

**Files changed:**
- `core/application/.../session/SessionState.kt` -- `physicalSolveUnconfirmed` KDoc now names the first-solve milestone.
- `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` -- three new tests (*Next* and *Yes* when closing unmounts the screen; saved instruction cleared on restore to `PHYSICAL_AWAITING_CONFIRM`), a confirmation assertion on the already-completed *Yes* test, and a shared `physicalSnapshot()` helper.

**Review:** 30 findings: high 0, medium 4, low 17, false 9. Patches applied: 1 medium (unmount on close untested) and 2 low (KDoc, restore clear untested). Deferred: no new items; the 2 carried medium findings are already in `deferred`. Rejected: every `false` row and every remaining `low` row, each with its reason in the Review Triage Log.

**Follow-up review recommended:** false (follow-up pass; patched: high 0, medium 1, low 2).

**Verification:** `./gradlew spotlessApply`, then `./gradlew spotlessCheck build`: the first run failed in `Cube3DEmphasisTest` ("arrow draws in on frame clock...", expected `U2` progress 0.0, got `R` progress 1.0); that test and its code are outside this story's diff, and it passed 3 of 3 isolated reruns; the second full run was BUILD SUCCESSFUL (`:app` JUnit XML: 390 tests, 0 failures, 0 errors, 0 skipped). `grep '"lesson-06-03"' app/src/main`: one match. `grep 'pendingPhysicalSolve|physicalFirstSolveConfirmed' app/src/main`: no match. Mutation check, reverted afterwards: closing before the dispatch fails the *Next* unmount test, and deleting the `PHYSICAL_AWAITING_CONFIRM` clear fails the restore test.

**Residual risks:** `Cube3DEmphasisTest` failed once under a full build and may be timing-sensitive, so CI could hit it; its cause was not investigated. The one-frame wait still has no test and TalkBack has not been run: if Compose samples semantics changes less often than once per frame, the cleared value could be missed and a repeated identical instruction would stay silent (from memory of Compose internals, not checked here). The *Yes* unmount test asserts the confirmed flag, which is set before the write, so it would not catch a close-before-dispatch change on that branch; only the *Next* test does. Nothing ran on a device or emulator. Deferred items above.
