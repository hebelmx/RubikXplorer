---
title: 'Story 6.2: Versioned Checkpoints at Objective boundaries'
type: 'feature'
created: '2026-11-05'
baseline_revision: 'c44b99a51cbef067d7a10447667de7108fac10b9'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Lesson's Checkpoint (FR-12) lives only in memory, and 6.1's snapshot restore gives up silently when the content changed (`contentRevision` differs). A content update therefore throws the Learner's cube away (FR-19, NFR-6, AD-10).

**Approach:** The reducer emits a new `Effect.PersistCheckpoint` whenever a Lesson's Checkpoint is set. `SessionHost` turns it into a pure, versioned `Checkpoint` DTO and stores it through the serial writer in a new Room `checkpoint` table (schema v2 with a migration). On cold start, when no snapshot resumes, the host restores the LESSON Checkpoint through a new `SessionEvent.CheckpointRestored`. With unchanged content it resumes at the Checkpoint; with a changed `contentRevision` or stage id it restarts the Lesson at the stored cube and shows "This lesson was updated. We've restarted it with your cube as it was."

## Boundaries & Constraints

**Always:**
- **DTO.** `Checkpoint` (`:core:application/session`, pure, primitives and enums only) holds:
  - `kind`, `contentRevision`, and the content IDs `lessonId` and `stageId`.
  - `start` and `cube`: 54-char Facelets each, plus `startOrientation` and `cubeOrientation` ids. An orientation id is the home faces at Learner U and F, e.g. `"UF"`.
  - `history`: Notation from `start`.
  - `mode` (enum `CubeMode`, only `VIRTUAL` until Epic 8), `teachingMode`, `adaptiveAssistance`, and `savedAt` (UTC epoch ms from the event's `at`).
  - It never holds `SessionState`, a `Lesson`, step text, the current step (AD-6) or tutor counters.
- **Emission.** After a Lesson transition, `reduce()` appends `Effect.PersistCheckpoint(nextEffectId, revision, …)` and raises `nextEffectId` by 1 when either:
  - the result's `checkpoint` differs from the one before (an Objective boundary, or an applied Reset), or
  - the event is `LessonStarted` or an applied `CheckpointRestored`.
  - Nothing else emits it: no other Move, no tutor event, `SessionRestored`, playback or IDLE.
  - The effect carries `lessonId`, `stageId`, the Checkpoint `MoveHistory`, `teachingMode`, `adaptiveAssistance` and `at`. `Checkpoint.of(effect, contentRevision)` is pure.
- **Write.** `SessionHost` handles every `PersistCheckpoint` in `dispatch`. It captures the DTO with the host's `contentRevision` and enqueues `checkpoints.save(dto)` on the serial writer, in commit order, never awaited. The row for the kind is replaced. A `Failed` result dispatches `SessionEvent.CheckpointSaveFailed`.
- **Port.** `CheckpointRepository` (`:core:application/port`) has `save` → `CheckpointSave.{Saved, Failed}` and `find(kind)` → `CheckpointLoad.{Found, None, Failed}`. Room exceptions become `Failed` and never crash (AD-12).
- **Storage.** Room schema v2 adds table `checkpoint`, with `kind` as the PK and a `payload` JSON column, plus a 1→2 migration. The v2 schema is exported to `app/schemas/`.
  - The JSON payload holds `checkpointFormat` (now 1) and the DTO fields only.
  - Decoding reads `checkpointFormat` first and dispatches to the decoder for that format. The decoders are kept for every past format. An unknown format or invalid JSON → `Failed`.
- **Restore event.** `CheckpointRestored(checkpoint, lesson, tutorConfig, contentRevision, at)` applies only from IDLE, with `kind == LESSON` and `lessonId == lesson.id`. All of these must also hold, or the state stays IDLE:
  - both Facelets decode and validate, and each orientation id equals the validated state's orientation;
  - `history` parses;
  - `start` + `history` equals `cube`.
  - **Resume.** When `contentRevision` and `stageId` both match: the history is `start` + `history` with nothing to redo, `checkpoint` is that history, and `restoreNote` is null.
  - **Restart.** Otherwise: the history is empty at `cube` (the new entry), `checkpoint` is that history, and `restoreNote = Text("msg.lesson.updated")`.
  - In both cases `teachingMode` and `adaptiveAssistance` come from the DTO, the other tutor fields are fresh (as `LessonStarted`), the revision is IDLE's + 1, and the presentation is `Snap`.
- **Note.** `restoreNote: Text?` is a transient tutor field: null in IDLE, never snapshotted, and cleared by every later event a Lesson session takes except the four persistence-failure events.
- **Host restore.** `restoreLatest` keeps 6.1's snapshot path. Then, if no session is active and the latest snapshot is not `SOLVE_PLAYBACK`:
  - it reads `checkpoints.find(LESSON)` through the writer;
  - when the Checkpoint's `lessonId` resolves in the bundle, it dispatches `CheckpointRestored`;
  - a missing lesson stays IDLE;
  - a `Failed` read (or a throw) dispatches `SessionEvent.CheckpointLoadFailed`;
  - `awaitRestored` completes in every outcome.
- **Snapshot start.** `SessionRestored` no longer requires `start` to equal the Lesson's entry state; it still requires valid Facelets. A restarted Lesson's entry is the stored cube.
- `CheckpointSaveFailed` and `CheckpointLoadFailed` change nothing.
- The Lesson screen shows `restoreNote`, resolved through `ContentStrings`, as a one-line note while it is non-null. The string `msg.lesson.updated` is added to `strings/en.json`.

**Never:**
- No Continue card, "Picked up where you left off" note, suspend/resume by a solve, attempt rows, preferences or physical restore (Stories 6.3–6.5, Epic 8).
- No Room, Android or serialization imports in `:core:*`. Never persist the free cube, playback, or Cube States in logs.
- Never change the 6.1 snapshot payload format or table. Never edit planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Boundary | A Lesson Move makes a Notice pending | One `PersistCheckpoint`; the row's payload has exactly the DTO keys | — |
| No boundary | A Move with no Notice, a Hint, a mode change | No `PersistCheckpoint` | — |
| Content changed | Stored `contentRevision` ≠ bundle's | Lesson at the stored cube, empty history, note shown, new Checkpoint persisted | — |
| Stage changed | Same revision, different `stageId` | Restart with note | — |
| Same content | Matching Checkpoint, no usable snapshot | Resumes at the Checkpoint history, no note | — |
| Lesson gone | `lessonId` not in bundle | Stays IDLE | — |
| Corrupt | Bad Facelets, orientation mismatch, bad Notation, `start + history ≠ cube`, unknown format | Stays IDLE | decode → `Failed` / reducer ignores |
| Playback latest | Latest snapshot is SOLVE_PLAYBACK | No Checkpoint read | — |
| DB failure | Room throws on save or find | `CheckpointSaveFailed` / `CheckpointLoadFailed`; the app runs on | caught |

</intent-contract>

## Code Map

**Starting point.** The working tree already holds the earlier full implementation of this spec (applied from the saved intent-gap patch, uncommitted). It covers the DTO, the port, Room v2, the host write and restore paths, the note and their tests. Keep it and add only the **Tutor path entry** delta and the extra test below. Do not rewrite what exists.

**The delta: the Tutor path entry (PO clarifications 1 and 2).**
- `core/application/.../session/SessionState.kt` -- `entry` (l.~206) is `history.start`. It stays the session's start: Reset target, snapshot `start`, Solution Playback's start. Add a derived `pathEntry: CubeState`:
  - in a Lesson, the Lesson's decoded authored `entryState`;
  - otherwise `history.start`.
  - Make it a `by lazy(PUBLICATION)` like `decision`. It is never stored or snapshotted.
  - `stepIndex` (l.~184) passes `pathEntry` to `Tutor.shownStep`, and `decision` passes it to `Assess.decide`.
- `core/application/.../session/Reduce.kt`:
  - `entryState(lesson)` (l.177) is the decoder to share. Make it `internal` or move it beside `SessionState`.
  - `move()` (l.~202, l.~234, l.~288) passes `pathEntry` instead of `entry` to `Assess.classify` and `Tutor.currentStep`.
  - `reset()` and `restoreCheckpoint()` stay as they are: the history starts at the stored cube.
- `core/domain/.../tutor/Assess.kt` -- `decide` (l.~168) derives the shown step from `history.start`. Add an `entry: CubeState` parameter for the path entry and use it there. Default it to `history.start` only if that keeps existing tests compiling; never change their expectations.
- `app/.../ui/lesson/LessonScreen.kt` -- `Tutor.nextMove` (l.~371 arrow, l.~464 guide) and `Tutor.reorient` (l.~502, the Lesson's hold) take `session.pathEntry`.
- The existing off-path behavior is already "the recovery behavior" the PO names:
  - `nextMove` returns null off the path, so there is no guide chip, no hint arrow, the Assistance line says to Undo back, and the TARGET hint line is hidden (`stepCard`, l.~464-511).
  - `isOnPath` is false, so there is no ON_PATH feedback. `currentStep` falls back to the first unmet step.
  - No new recovery code is needed. It must simply be reached through `pathEntry`.

**Already implemented (patch); reference only.**
- `core/application/.../session/{Checkpoint,Effect,SessionEvent,SessionState,Reduce}.kt`, `TRANSITIONS.md`, `port/CheckpointRepository.kt`.
- `core/domain/src/main/resources/content/strings/en.json` (`msg.lesson.updated`).
- `app/.../data/{CheckpointEntity,CheckpointDao,CheckpointPayload,RoomCheckpointRepository,RubikDatabase}.kt`, `app/schemas/.../2.json`.
- `app/.../di/PersistenceModule.kt`, `app/.../session/SessionHost.kt` (`dispatch`, `restoreLatest`), and `LessonScreen.kt` (the `lesson:restoreNote` line).
- Tests:
  - core: `CheckpointTest`, `ReduceTest`, `SessionSnapshotTest`, `TransitionsTableTest`;
  - app: `CheckpointPayloadTest`, `RoomCheckpointRepositoryTest`, `SessionDatabaseMigrationTest`, `SessionHostCheckpointTest`, `LessonScreenTest`, fixture `app/src/test/resources/checkpoint/format-1.json`.
- `docs/ARCHITECTURE.md`.

## Tasks & Acceptance

**Execution:**
- `core/application/.../session/SessionState.kt` -- add `pathEntry` and use it in `stepIndex` and `decision`. Update the KDoc on `entry`, `history` and the class to say that the Tutor walks the authored path from `pathEntry`, while `entry` is where Reset returns.
- `core/application/.../session/Reduce.kt` -- share `entryState`, and use `pathEntry` in `move()`'s classify and current-step calls.
- `core/domain/.../tutor/Assess.kt` -- give `decide` the path entry.
- `app/.../ui/lesson/LessonScreen.kt` -- use `session.pathEntry` for `nextMove` and `reorient`.
- `core/application/.../session/TRANSITIONS.md` -- one sentence: after a restart, the Tutor's step and path come from the Lesson's authored entry; history and Reset come from the stored cube.
- `core/application/src/test/.../session/CheckpointTest.kt` -- add the restart-path cases:
  - (a) **On-path cube.** The stored cube is the authored entry plus all of step 0's Moves (a Lesson with at least 2 steps).
    - After a revision-changed `CheckpointRestored`, `stepIndex` is 1.
    - `Tutor.nextMove(lesson, s.pathEntry, s.virtual)` is step 1's first authored Move.
    - Committing that Move in *Test me* gives `ON_PATH`.
    - `history.start` is the stored cube, and Reset returns there.
  - (b) **Off-path cube.** The stored cube is on no authored path (for example, the entry plus a Move that is not authored).
    - `nextMove` is null, and `stepIndex` is the first unmet step.
    - Committing step 0's first authored Move gives no `ON_PATH`.
    - After Moves that return the cube to an authored path state, `nextMove` is non-null again.
- `app/src/test/.../ui/lesson/LessonScreenTest.kt` -- a revision-changed restore with an on-path mid-Lesson cube shows step 2 of N (not step 1).
- `app/src/test/.../data/SessionHostCheckpointTest.kt` -- add `decoded-invalid-history`: a snapshot row that decodes, but whose `done` Notation is invalid, is rejected by the reducer, and the host falls back to the Checkpoint (resume, no note).

**Acceptance Criteria:**
- Given a Lesson Move that reaches an Objective boundary, when the reducer emits `PersistCheckpoint`, then the stored `checkpoint` row's payload holds exactly `checkpointFormat`, `kind`, `contentRevision`, `lessonId`, `stageId`, `start`, `startOrientation`, `cube`, `cubeOrientation`, `history`, `mode`, `teachingMode`, `adaptiveAssistance` and `savedAt`, written off the main thread through the serial writer.
- Given a stored Checkpoint whose `contentRevision` or `stageId` no longer matches the loaded content, when the app restores on cold start, then the Lesson screen shows the stored cube at the start of the Lesson and the text "This lesson was updated. We've restarted it with your cube as it was."
- Given that restart with a stored cube on the updated Lesson's authored path after step k, when the Tutor derives the step, then the step card shows step k+1 and the guide Move, hint arrow and ON_PATH feedback follow the authored path from there, while Undo has nothing to undo and Reset returns to the stored cube.
- Given that restart with a stored cube on no authored path, when the Learner opens guidance, then there is no guide Move or hint arrow, a Move gives no ON_PATH feedback, the existing off-path recovery line shows, and path guidance returns once the cube reaches an authored path state.
- Given every Checkpoint format from 1 to the current one, when `./gradlew spotlessCheck build` runs, then the round-trip test and the frozen-fixture decoder tests pass, and the v1→v2 migration test passes.

## Spec Change Log

### 2026-11-05 — Re-plan after the intent gap (PO clarifications)
- **Trigger.** The follow-up review's medium intent gap: after a content-update restart, the Tutor used the stored cube as its path entry (step 0), so the guidance undid mid-Lesson progress.
- **PO answers.**
  1. The Tutor derives the step and the authored path from the updated Lesson's authored entry, so a mid-Lesson stored cube lands on its first unmet step. The move history and Reset still start at the stored cube.
  2. A stored cube on no authored path gets the existing recovery behavior instead of path guidance (guide Move, ON_PATH feedback, hints) until it is back on an authored path.
- **Amended.**
  - Added `SessionState.pathEntry` and routed every Tutor and Assess path call to it.
  - Added two ACs and the restart-path tests.
  - Rewrote the Design Note "Why the restart enters at the stored cube".
  - Carried the verification-gap test from the follow-up pass.
- **Avoids.** Guidance that leads a restarted Learner to undo work already done.
- **KEEP.** Everything from the earlier implementation: the DTO, emission, write path, codec and fixtures, migration, host fallback, the note (which wraps, with no `maxLines`), and the rotated-cube and host-matrix tests.

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 17 findings — high 0, medium 5, low 8, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) after a content-changed restart, the Demonstration still plays from the Lesson's authored entry, not the stored cube — a Demonstration is the authored lesson by design, and AD-10 restarts "with the stored cube"; re-rooting Demonstrations adds new behavior for a rare case.
  - `[low]` `[reject]` (blind) a snapshot restore never seeds a missing Checkpoint row (v1 databases) — no released v1 install exists, and the next Objective boundary writes the row; seeding adds a new write path.
  - `[low]` `[reject]` (blind) a failed Checkpoint write is not retried — same stance as 6.1's snapshot no-retry row; the next boundary writes again, and retry machinery is more than a direct fix.
  - `[low]` `[reject]` (blind) a new Lesson whose Checkpoint write failed can fall back to the previous Lesson's Checkpoint — needs a failed write plus a content update before the next boundary; a cross-check guard adds branches for an unlikely case.
  - `[medium]` `[patch]` (blind) the update note used `maxLines = 1` and clipped the ~70-character sentence at phone width — removed `maxLines` in `LessonScreen.kt`, so the note wraps.
  - `[false]` `[reject]` (blind) background events could clear `restoreNote` before it is read — after a restore nothing dispatches without the Learner: the persistence-failure events keep it, no effect is pending on a fresh restore, and `LessonScreenTest` shows the note after the screen settles.
  - `[low]` `[reject]` (blind) Checkpoint history rebuild is quadratic — same stance as 6.1's rebuild row; Lesson histories are tens of Moves.
  - `[low]` `[patch]` (blind) Checkpoint tests only covered the `UF` orientation — added `CheckpointTest` "rotated cubes with wide and slice turns round trip through both restore paths" (`y R x M r' z2 U`, non-`UF` cube orientation, resume, restart, and restart-then-resume); passes.
  - `[medium]` `[patch]` (blind) the host restart matrix omitted a failed snapshot read and a usable snapshot that skips the Checkpoint lookup — added `kept` and `corrupt` scenarios to `SessionHostCheckpointTest` (asserts zero Checkpoint reads and `SessionRestored` for `kept`; `SnapshotLoadFailed` plus silent Checkpoint resume for `corrupt`).
  - `[medium]` `[patch]` (edge) the restore sentence is clipped on compact screens or large fonts — same root cause and patch as the blind `maxLines` row.
  - `[medium]` `[patch]` (verification-gap) a failed snapshot read falling back to a valid Checkpoint was untested — same patch as the blind host-matrix row (the `corrupt` scenario); an early return after `SnapshotLoadFailed` now fails it.
  - `[low]` `[reject]` (intent) a Checkpoint whose `lessonId` is gone stays IDLE silently instead of "restarting the Lesson" — there is no Lesson to restart, and picking a substitute would invent behavior; recorded in Design Notes, surfacing it belongs to 6.5's Continue card.
  - `[low]` `[reject]` (intent) after a cold start the note is seen only when the Learner reopens that same Lesson — the session is restored in `SessionHost`, and surfacing it is Story 6.5's Continue card (same stance as 6.1's swipe-away row).
  - `[medium]` `[patch]` (intent) the note may be clipped by `maxLines = 1` — same root cause and patch as the blind `maxLines` row.
  - `[false]` `[reject]` (intent) 6.1 behavior changed (snapshot start check relaxed, playback snapshot skips Checkpoint fallback, migration test targets v2) — each is specified in this spec's Always/Design Notes; a restarted Lesson's snapshot must restore, and suspend/resume is 6.5's.
  - `[false]` `[reject]` (intent) the DTO carries fields the AC does not list (`teachingMode`, `adaptiveAssistance`, `savedAt`, start orientation) — none is raw `SessionState` or content; no bad outcome.
  - `[false]` `[reject]` (intent) Checkpoints are also written at Lesson start and Reset — both set the Objective-boundary Checkpoint (FR-12, TRANSITIONS.md); documented and tested, no bad outcome.

### 2026-11-05 — Review pass (follow-up)
- verdicts: 17 findings — high 0, medium 1, low 11, false 5, maybe-false 0
- findings:
  - `[medium]` `[intent_gap]` (blind) after a content-update restart the Tutor treats the stored cube as the authored entry: `currentStep` returns step 0 and `nextMove`/`isOnPath` offer and praise step 0's first authored Move, although a mid-Lesson Checkpoint cube already has earlier steps done, so following the guidance undoes the Learner's progress — verified: `SessionState.entry` is `history.start`, and `restoreCheckpoint` sets the history to `MoveHistory(cube)`; `contentRevision` hashes every content file, so any content release hits this for every Learner mid-Lesson. The intent itself makes the stored cube "the new entry", so the fix is inside `<intent-contract>`. Story code reverted to `baseline_revision`; attempted change saved as `_bmad-output/implementation-artifacts/spec-6-2-versioned-checkpoints-at-objective-boundaries-intent-gap.patch` (applies cleanly with `git apply`).
  - `[low]` `[reject]` (blind) the Demonstration still plays from the authored entry after a restart — carried: a Demonstration is the authored lesson by design, and AD-10 restarts "with the stored cube"; re-rooting Demonstrations adds new behavior for a rare case.
  - `[low]` `[reject]` (blind) the snapshot and Checkpoint writes are separate, so process death between them leaves the Checkpoint one boundary behind — the two writes are adjacent on one serial writer, so the window is tiny; a cross-table transaction adds a new API for an unlikely case.
  - `[low]` `[reject]` (blind) a failed snapshot write beside a successful Checkpoint write lets restart resume the older snapshot — both rows live in one database, so one failing and the next succeeding is unlikely; ordering metadata adds fields. Same stance as the carried no-retry row.
  - `[low]` `[reject]` (blind) a failed Checkpoint write is not retried — carried: same stance as 6.1's snapshot no-retry row; the next boundary writes again.
  - `[low]` `[reject]` (blind) a new Lesson whose Checkpoint write failed can fall back to the previous Lesson's Checkpoint — carried: needs a failed write plus a content update before the next boundary; a cross-check adds branches for an unlikely case.
  - `[low]` `[reject]` (blind) `restoreNote` is not persisted, so process death before the Learner sees it loses the explanation — the intent says the note is "never snapshotted"; out of scope by intent.
  - `[low]` `[reject]` (blind) the note has no announcement semantics and any later event (even a stale one) clears it — the stale-event part is carried `false` (nothing dispatches without the Learner after a fresh restore); a live region does not announce on first composition, and TalkBack reads the note as plain text in traversal.
  - `[low]` `[reject]` (blind) no device test kills the process around a boundary — already a recorded residual risk; device process-death infrastructure is far more than a direct fix.
  - `[low]` `[patch]` (verification-gap) the fallback after a decoded-but-rejected snapshot (e.g. invalid `done` Notation) is untested — the code is right (`SessionHost.kt:100` checks `isActive`), only regression protection is missing; not applied, moot under the intent gap. Add a `decoded-invalid-history` case to `SessionHostCheckpointTest` when the story is re-implemented.
  - `[false]` `[reject]` (intent) emission is keyed on the `checkpoint` field, so Moves after a boundary while the Notice is pending or the Objective is met emit again — `move()`'s boundary rule (`Reduce.kt:278`) predates this story, and the persisted value always equals the in-memory Checkpoint (FR-12); the only effect is an extra replace-by-kind write.
  - `[false]` `[reject]` (intent) a rejected `SolvePlaybackStarted` during a Lesson now changes state by clearing the note — the intent clears the note on every later event a Lesson session takes; the only effect is one snapshot write of the same session, with no Learner-visible change.
  - `[false]` `[reject]` (intent) a failed snapshot read still reads the Checkpoint — an unknown latest kind is "not SOLVE_PLAYBACK"; the fallback only restores a LESSON Checkpoint whose Lesson exists, and playback never resumes on cold start anyway.
  - `[false]` `[reject]` (intent) the migration is an `AutoMigration` — the intent asks for "a 1→2 migration"; Room validates it in `SessionDatabaseMigrationTest`.
  - `[low]` `[reject]` (intent) the tests never reach the real app path or the Hilt wiring — Hilt validates the graph at compile time in `build`; the reopen-the-Lesson path is the carried 6.5 Continue-card stance; device coverage is a recorded residual risk.
  - `[low]` `[reject]` (intent) `LessonScreen` shows `restoreNote` without checking that the session's Lesson is the one on screen — opening another Lesson dispatches `LessonStarted`, which clears the note; at most one frame.
  - `[false]` `[reject]` (intent) a `PersistCheckpoint` handled before `restore()` stores `contentRevision = ""` — `LessonScreen` waits on `awaitRestored()` before any `LessonStarted`, so no Lesson effect can precede the revision being set.

### 2026-11-05 — Review pass (re-plan after PO clarifications)
- verdicts: 16 findings — high 0, medium 1, low 8, false 7, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) at USUAL assistance, the off-path check in `LessonScreen.stepCard` is skipped, so a revealed card still shows the step's authored Move chips — PO clarification 2 asks for "the existing recovery behavior". The diff delivers exactly that: every off-path cube already behaves this way today, and the arrow (`nextMove` returns null) and ON_PATH (`isOnPath` returns false) are suppressed at every level. Hiding chips at USUAL would be new behavior.
  - `[low]` `[reject]` (blind) the off-path Assistance line says "Undo back", but a restart has nothing to undo, and Try again returns to the same off-path cube — this is the existing off-path behavior PO clarification 2 selects. GUIDE only appears after Harmful Moves on the step, so Moves exist by then. New copy or branches would be new behavior.
  - `[low]` `[reject]` (blind) the v1→v2 migration leaves the checkpoint table empty, so an upgrade together with a content change loses the cube — carried: no released v1 install exists, and the next Objective boundary writes the row.
  - `[low]` `[reject]` (blind) the snapshot and Checkpoint writes are separate, so process death between them can leave mismatched records — carried: both writes are adjacent on one serial writer; a cross-table transaction adds a new API for an unlikely case.
  - `[low]` `[patch]` (blind) the restart-path tests change only the revision string, never the Lesson — added the `CheckpointTest` "restart walks the updated lesson's authored path while history starts at the stored cube" test (a `copy` with a new entry and steps; `pathEntry`, `stepIndex` and `nextMove` follow it). It passes.
  - `[false]` `[reject]` (blind) the host matrix saves only after the whole demonstration, so Moves after a boundary are untested — a row is written only on `PersistCheckpoint`, and `CheckpointTest` "ordinary moves … never persist" shows that ordinary Moves never emit it, so later Moves cannot reach the row.
  - `[false]` `[reject]` (blind) the snapshot round trip after a restart is untested for step, next Move and Reset — `pathEntry` is derived from the Lesson in every state, never stored or snapshotted. `CheckpointTest` "changed content … later snapshot preserves its entry" round-trips a restarted snapshot and keeps its start.
  - `[low]` `[patch]` (blind) the frozen `format-1.json` fixture was trivial (start equal to cube, the same orientations, empty history), so a decoder that swapped fields would still pass — the fixture and `checkpointFixture()` now hold a real example: `x F U'` start in `FD`, history `R U y F'`, cube in `FR`. `CheckpointPayloadTest` and `RoomCheckpointRepositoryTest` pass.
  - `[low]` `[reject]` (blind) there is no test that cancels and retries the Checkpoint read — the restore runs once at start-up, so cancelling it is unlikely; the test needs suspendable fakes, which is more than a direct fix.
  - `[medium]` `[patch]` (blind) no screen test covers restored guidance — same root cause as the verification-gap row.
  - `[false]` `[reject]` (edge) at USUAL, off-path restarts show authored Moves instead of the promised recovery — same as the first blind row: the promise is the existing off-path behavior, and the diff delivers it.
  - `[medium]` `[patch]` (verification-gap) reverting the screen's `nextMove` calls to `session.entry` failed no test — added `LessonScreenTest` restored on-path cases (arrow `R2`, single chip `moveChip:R2`, Step 2 of 4) and an off-path case (no arrow, no chips, the off-path Assistance line). A mutation check reverted each call in turn, and both tests failed each time.
  - `[false]` `[reject]` (intent) the contract's "(the new entry)" conflicts with a Tutor that walks from the authored entry — PO clarification 1 resolves it: the history entry and Reset target are the stored cube, and only the Tutor's path entry is the authored one.
  - `[false]` `[reject]` (intent) off the path, hint lines and chips at non-GUIDE levels remain (reading C1 against C2) — "existing recovery behavior" selects C1: the diff adds no new suppression, as the PO asked.
  - `[low]` `[reject]` (intent) right after a restart the Undo-back line and the recovery offers point back to the stored off-path cube — same as the blind Undo row; this is the existing behavior.
  - `[low]` `[patch]` (intent) no test restores against a Lesson that really changed — same root cause and patch as the blind restart-path row.

## Design Notes

- **Two entries after a restart.** AD-10 says "falls back to the start of the Lesson with the stored cube". So the session's history, its Undo barrier and Reset start at the stored cube (`entry` = `history.start`). The Tutor still judges the cube against the updated Lesson's authored path from the Lesson's own entry (`pathEntry`, AD-6). A mid-Lesson cube is therefore on its real step, as `currentStep`'s path match and first-unmet-step rules already define. With unchanged content, `entry` and `pathEntry` are equal. A later snapshot has `start ≠ lesson.entryState`, which is why `SessionRestored` drops that check.
- **Off the path.** No new code is needed. `nextMove` returns null and `isOnPath` returns false, so the screen's existing off-path branch (no chips or arrow, the Undo-back Assistance line, the TARGET line hidden) and the Diagnosis and recovery offers apply. Guidance returns by itself once the cube reaches an authored path state.
- **Undo barrier.** The restart has nothing to undo or redo. The same-content resume clears redo but keeps the Checkpoint's Moves undoable. Physical-mode barriers arrive with Epic 8.
- **Format versioning.** There is only format 1 today. The frozen `format-1.json` fixture is its permanent decoder test: a future bump adds `format-2.json` and keeps the format-1 decoder, and the fixture loop fails if either is missing.
- **Missing lesson.** No Lesson exists to restart, so the state stays IDLE. Surfacing this belongs to 6.5's Continue card.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass (JVM tests, Kover ≥ 80% on `:core:*`, gates, APKs).
- `./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest --tests '*Checkpoint*' --tests '*Snapshot*' --tests '*Persistence*' --tests '*Migration*' --tests '*TransitionsTable*' --tests '*LessonScreen*' --tests '*Assess*' --tests '*Tutor*'` -- expected: pass.

## Auto Run Result

**Summary:**
- The reducer emits `Effect.PersistCheckpoint` whenever a Lesson's Checkpoint is set: at `LessonStarted`, at an applied Reset, at each Objective boundary, and at an applied `CheckpointRestored`. `SessionHost` stores it as a versioned `Checkpoint` DTO through the serial writer in a new Room `checkpoint` table (schema v2, migrated from v1).
- On cold start, when no snapshot resumes, the host restores the LESSON Checkpoint:
  - With unchanged content it resumes at the Checkpoint history.
  - With a changed `contentRevision` or `stageId` it restarts the Lesson at the stored cube, and the Lesson screen shows "This lesson was updated. We've restarted it with your cube as it was."
- The PO clarifications are implemented by a new derived field, `SessionState.pathEntry`, the updated Lesson's authored entry:
  - The Tutor's step, the guide Move, the hint arrow, ON_PATH and Diagnoses walk the path from `pathEntry`. A mid-Lesson stored cube lands on its first unmet step.
  - History, Undo and Reset start at the stored cube (`entry`).
  - A stored cube on no authored path gets the existing off-path behavior until it rejoins the path.
- The saved intent-gap patch was used as the starting point and then deleted.

**Files:**
- `core/application/.../session/{Checkpoint,Effect,SessionEvent,SessionState,Reduce}.kt`, `TRANSITIONS.md`:
  - the DTO, the emission, the restore events, `restoreNote`, `pathEntry`, and the relaxed snapshot start check.
- `core/application/.../port/CheckpointRepository.kt`: the port, with sealed results.
- `core/domain/.../tutor/Assess.kt`: `decide` takes the path entry.
- `core/domain/.../strings/en.json`: `msg.lesson.updated`.
- `app/.../data/{CheckpointEntity,CheckpointDao,CheckpointPayload,RoomCheckpointRepository,RubikDatabase}.kt` and `app/schemas/.../2.json`: v2 and the codec that dispatches on format.
- `app/.../di/PersistenceModule.kt` and `app/.../session/SessionHost.kt`: the write path and the fallback restore.
- `app/.../ui/lesson/LessonScreen.kt`: the note, and guidance from `pathEntry`.
- Tests:
  - `CheckpointTest`, `CheckpointPayloadTest` (with the `format-1.json` fixture), `RoomCheckpointRepositoryTest` and `SessionHostCheckpointTest`, including `decoded-invalid-history`.
  - Updated: `LessonScreenTest`, `SessionDatabaseMigrationTest`, `ReduceTest`, `SessionSnapshotTest` and `TransitionsTableTest`.
- `docs/ARCHITECTURE.md`: one line on the checkpoint table.

**Review:** 16 findings: high 0, medium 1, low 8, false 7.
- Patched (3 entries):
  - restored-screen guidance tests (medium);
  - a restore against an updated Lesson (low);
  - a nontrivial frozen fixture (low).
- Deferred: none.
- Rejected: 5 low and 7 false, with reasons in the triage log.

**Follow-up review recommended:** false. One medium entry and two low entries were patched, and every patch changes only tests.

**Verification:**
- `./gradlew spotlessCheck build`: pass, after implementation and again after the patches.
  - One run after the patches failed in `Cube3DEmphasisTest`, a frame-clock animation test that this story does not touch. The test passed when run alone, and the full rerun passed.
- The targeted `--tests` command: pass.
- Matrix audit: every row is covered by tests that passed:
  - `CheckpointTest`: boundary, no boundary, corrupt;
  - `SessionHostCheckpointTest`: revision, stage, same, missing, playback, DB failure;
  - `CheckpointPayloadTest`: keys, unknown format, broken JSON;
  - `LessonScreenTest`: the note.

**Residual risks:**
- After a cold start, the restored Lesson is visible only when the Learner reopens it, until 6.5's Continue card.
- An off-path restart at USUAL assistance still shows the step's authored chips when revealed. This is the existing off-path behavior that the PO chose.
- The Undo-back line can appear when Undo cannot reach the path.
- `Cube3DEmphasisTest` can fail intermittently and is unrelated to this story.
- No device test covers process death.
