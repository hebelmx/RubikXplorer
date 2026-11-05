---
title: 'Story 6.2: Versioned Checkpoints at Objective boundaries'
type: 'feature'
created: '2026-11-05'
baseline_revision: '8570775fa83d0e56c6fbe336668897119f8a4092'
status: 'blocked'
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

- `core/application/.../session/Reduce.kt` -- `reduce()` is the top-level guard: add `CheckpointRestored` beside `SessionRestored` and the failure events beside `SnapshotSaveFailed`. Put the central PersistCheckpoint append and the `restoreNote` clear there.
  - Reuse: `start()` for the fresh tutor fields, `withHistory` (revision +1, objectiveMet/progress), and `restore()` (6.1) as the decode pattern. Drop its `validated.state != entryState(lesson)` check.
  - `move()` sets the boundary Checkpoint; `reset()` sets it to the entry.
- `.../session/Effect.kt` -- add `PersistCheckpoint`. `SessionEvent.kt` -- add `CheckpointRestored`, `CheckpointSaveFailed` and `CheckpointLoadFailed`. `SessionState.kt` -- add `restoreNote` (IDLE null; KDoc in the tutor list).
- `.../session/SessionSnapshot.kt` -- the 6.1 DTO pattern to mirror. `TRANSITIONS.md` and `TransitionsTableTest.kt` need new rows (the `when` is exhaustive, and the samples map needs each event):
  - `CheckpointRestored | session, virtual, tutor, checkpoint | no | yes`
  - `CheckpointSaveFailed | — | no | no`
  - `CheckpointLoadFailed | — | no | no`
  - Also update the idle sentence and add prose for emission and `restoreNote`.
- Existing core tests that assert `effects` empty or exact `nextEffectId` after `LessonStarted`/Reset/boundaries (`ReduceTest`, `SessionSnapshotTest:59-61`, `StaleEffectTest`, `GuidedRecoverySessionTest`, `TutorEventsTest`, `HintLadderSessionTest`) -- update to ignore or expect `PersistCheckpoint`. Never weaken what they assert about the other effects.
- `core/domain/.../cube/Orientation.kt` -- `Orientation.of(up, front)`, `up`, `front`. `CubeState.orientation`. `FaceletString` + `CubeValidator` derive the orientation from the centers.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.lesson.updated`. `ContentStringsTest` shows the resolve pattern.
- `app/.../data/` -- mirror 6.1's files:
  - `RubikDatabase.kt` (v1 → 2; an `@AutoMigration` or `Migration`, verified against room3 3.0.3 docs).
  - `SnapshotPayload.kt` / `RoomSessionSnapshotRepository.kt` are the codec and repository patterns.
  - `SerialWriter` has `enqueue`, `read` and `flush`.
- `app/.../session/SessionHost.kt` -- `dispatch` (the effect handling goes beside the snapshot enqueue) and `restoreLatest`. Default the new `checkpoints` param to null so the test constructor keeps working. Update the `@Inject` constructor.
- `app/.../di/PersistenceModule.kt` -- bind `CheckpointRepository`, and add the migration if it is manual.
- `app/.../ui/lesson/LessonScreen.kt` (`LessonBody`/panel) -- render the note. The start check at l.127-133 already keeps a restored Lesson with the same id.
- `app/src/test/.../data/{SessionDatabaseMigrationTest,SessionHostPersistenceTest}.kt`, `app/src/sharedTest/.../data/SnapshotRestartScenario.kt` -- the file-DB restart harness to reuse.

## Tasks & Acceptance

**Execution:**
- `core/application/.../session/{Checkpoint.kt (new),Effect.kt,SessionEvent.kt,SessionState.kt,Reduce.kt,TRANSITIONS.md}` -- the DTO, `Checkpoint.of`, orientation-id helpers, `CubeMode`, the emission, `CheckpointRestored` (resume/restart), the relaxed snapshot start, the failure events and `restoreNote`.
- `core/application/.../port/CheckpointRepository.kt` (new) -- the port and its sealed results.
- `core/domain/src/main/resources/content/strings/en.json` -- `"msg.lesson.updated": "This lesson was updated. We've restarted it with your cube as it was."`
- `app/.../data/{CheckpointEntity,CheckpointDao,CheckpointPayload,RoomCheckpointRepository}.kt` (new), `RubikDatabase.kt` -- v2 and the versioned codec. Export `app/schemas/.../2.json`.
- `app/.../session/SessionHost.kt`, `app/.../di/PersistenceModule.kt` -- the write path and the Checkpoint fallback restore.
- `app/.../ui/lesson/LessonScreen.kt` -- the note line (`testTag("lesson:restoreNote")`).
- Tests (JVM, core):
  - `CheckpointTest`: the emission cases, effect ids, `Checkpoint.of` fields, every reducer row of the matrix, and that the restored IDLE/non-IDLE guards hold.
  - Updated `TransitionsTableTest`, `SessionSnapshotTest` (the start-mismatch case now restores), and the effect assertions listed in the Code Map.
- Tests (app):
  - `CheckpointPayloadTest`: round-trip of format 1, and an exact JSON key set. It decodes the frozen fixtures `app/src/test/resources/checkpoint/format-<n>.json` for every n in 1..CURRENT to their expected DTOs, so a test fails if a fixture is missing. Unknown format and broken JSON throw.
  - `RoomCheckpointRepositoryTest`: replace per kind, corrupt → `Failed`, closed DB → `Failed`.
  - `SessionDatabaseMigrationTest`: v1 with a snapshot row migrates to v2, validates, and keeps the row.
  - `SessionHostCheckpointTest` (file DB, restart through a new DB/writer/host):
    - The boundary writes a row with the DTO keys.
    - A changed revision restarts at the stored cube with the note.
    - A changed stage restarts with the note.
    - A missing lesson stays IDLE.
    - The same revision with the snapshot row deleted resumes silently.
    - A latest playback snapshot means no fallback.
    - A failing repository dispatches the failure events.
  - `LessonScreenTest`: a content-changed restore shows the exact English sentence and keeps the stored cube.
- `docs/ARCHITECTURE.md` -- one line: the `checkpoint` table and its fallback role.

**Acceptance Criteria:**
- Given a Lesson Move that reaches an Objective boundary, when the reducer emits `PersistCheckpoint`, then the stored `checkpoint` row's payload holds exactly `checkpointFormat`, `kind`, `contentRevision`, `lessonId`, `stageId`, `start`, `startOrientation`, `cube`, `cubeOrientation`, `history`, `mode`, `teachingMode`, `adaptiveAssistance` and `savedAt`, written off the main thread through the serial writer.
- Given a stored Checkpoint whose `contentRevision` or `stageId` no longer matches the loaded content, when the app restores on cold start, then the Lesson screen shows the stored cube at the start of the Lesson and the text "This lesson was updated. We've restarted it with your cube as it was."
- Given every Checkpoint format from 1 to the current one, when `./gradlew spotlessCheck build` runs, then the round-trip test and the frozen-fixture decoder tests pass, and the v1→v2 migration test passes.

## Spec Change Log

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

## Design Notes

- **Why the restart enters at the stored cube.** AD-10 says "falls back to the start of the Lesson with the stored cube". The changed Lesson's entry state and path may no longer lead to the Learner's cube, so the stored cube becomes the session's entry. `Tutor` then derives the step from there (step 0). Reset returns to it, and a Notation history from the new content's entry is never invented. A later snapshot therefore has `start ≠ lesson.entryState`, which is why `SessionRestored` drops that check.
- **Undo barrier.** The restart has nothing to undo or redo. The same-content resume clears redo, but keeps the Checkpoint's Moves undoable, because the step derivation walks the authored path from the Lesson entry (AD-6). A history re-rooted at the Checkpoint would misplace the step. Physical-mode barriers arrive with Epic 8.
- **Format versioning.** There is only format 1 today. The frozen `format-1.json` fixture is its permanent decoder test: a future bump adds `format-2.json` and keeps the format-1 decoder, and the fixture loop fails if either is missing.
- **Missing lesson.** No Lesson exists to restart, so the state stays IDLE. Surfacing this belongs to 6.5's Continue card.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass (JVM tests, Kover ≥ 80% on `:core:*`, gates, APKs).
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*Checkpoint*' --tests '*Snapshot*' --tests '*Persistence*' --tests '*Migration*' --tests '*TransitionsTable*' --tests '*LessonScreen*'` -- expected: pass.

## Auto Run Result

**Summary:**
- The reducer now emits `Effect.PersistCheckpoint` whenever a Lesson's Checkpoint is set: at `LessonStarted`, at an applied Reset, at each Objective boundary, and at an applied `CheckpointRestored`. `SessionHost` turns it into a pure, versioned `Checkpoint` DTO and writes it through the serial writer to a new Room `checkpoint` table (schema v2, auto-migrated from v1).
- On cold start, when no snapshot resumes, the host restores the LESSON Checkpoint through `SessionEvent.CheckpointRestored`:
  - With unchanged content it resumes at the Checkpoint history.
  - With a changed `contentRevision` or `stageId` it restarts the Lesson at the stored cube.
  - The Lesson screen then shows "This lesson was updated. We've restarted it with your cube as it was."

**Files:**
- `core/application/.../session/Checkpoint.kt` (new) — the DTO, `Checkpoint.of`, `CubeMode`, and the orientation ids.
- `core/application/.../session/{Effect,SessionEvent,SessionState,Reduce}.kt`, `TRANSITIONS.md`:
  - `PersistCheckpoint`, `CheckpointRestored`, `CheckpointSaveFailed`, `CheckpointLoadFailed` and `restoreNote`.
  - The central emission and the resume/restart reduction.
  - The relaxed snapshot start check.
- `core/application/.../port/CheckpointRepository.kt` (new) — the port with sealed results.
- `core/domain/.../content/strings/en.json` — `msg.lesson.updated`.
- `app/.../data/{CheckpointEntity,CheckpointDao,CheckpointPayload,RoomCheckpointRepository}.kt` (new), `RubikDatabase.kt` — v2 and the format-dispatching codec.
- `app/schemas/.../2.json` — the exported schema.
- `app/.../di/PersistenceModule.kt`, `app/.../session/SessionHost.kt` — the wiring, the write path and the Checkpoint fallback restore.
- `app/.../ui/lesson/LessonScreen.kt` — the note line (`lesson:restoreNote`), which wraps.
- Tests:
  - `CheckpointTest` (new), `CheckpointPayloadTest` (new), `RoomCheckpointRepositoryTest` (new), `SessionHostCheckpointTest` (new).
  - Updated: `SessionDatabaseMigrationTest` (v1→v2 keeps a snapshot row), `LessonScreenTest`, `TransitionsTableTest`, `ReduceTest`, `SessionSnapshotTest`.
  - Fixture: `app/src/test/resources/checkpoint/format-1.json`.
- `docs/ARCHITECTURE.md` — one line on the `checkpoint` table.

**Review:** 17 findings: high 0, medium 5, low 8, false 4.
- Patched (3 entries):
  - The clipped update note (medium; reported by the blind, edge and intent layers).
  - The missing host fallback cases, a failed snapshot read and a usable snapshot (medium; reported by the blind and verification-gap layers).
  - Rotated-cube coverage (low).
- Deferred: none.
- Rejected: 7 low and 4 false, with reasons in the triage log.

**Follow-up review recommended:** true (patched: medium 2 entries, low 1). The named risk is that the note's wrapping was fixed without a visual check at phone width or with large fonts. `LessonScreenTest` asserts the node text, not its rendered layout.

**Verification:**
- `./gradlew spotlessCheck build`: pass, both after implementation and after the review patches.
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*Checkpoint*' --tests '*Snapshot*' --tests '*Persistence*' --tests '*Migration*' --tests '*TransitionsTable*' --tests '*LessonScreen*'`: pass.
- The Codex implementation run normalized the CRLF checkout to LF. `./gradlew spotlessApply` restored the line endings with no content change.
- Matrix audit, every row covered by a passing test:
  - Boundary and no boundary: `CheckpointTest`, with the payload key set in `CheckpointPayloadTest`.
  - Content changed: `SessionHostCheckpointTest` `revision` and `LessonScreenTest`.
  - Stage changed: `stage`.
  - Same content: `same`, `corrupt`.
  - Lesson gone: `missing`.
  - Corrupt: the `CheckpointTest` invalid list, plus `CheckpointPayloadTest` for an unknown format and broken JSON.
  - Playback latest: `playback` (zero reads).
  - DB failure: `failedAndThrowingRepositoriesDispatchFailureEvents` and the closed DB in `RoomCheckpointRepositoryTest`.

**Residual risks:**
- After a cold start, the restored Lesson and its note are visible only when the Learner reopens that Lesson, until Story 6.5's Continue card.
- A Checkpoint whose Lesson was removed from the content is dropped silently.
- Only format 1 exists. The previous-format decoder test is the frozen `format-1.json` fixture, which a future bump must keep decoding.
- The same-content resume keeps the Checkpoint's Moves undoable, which is not a strict undo barrier (see Design Notes).
- No device test covers Checkpoints; all coverage is JVM and Robolectric.

### Follow-up review pass (2026-11-05)

Status: blocked
Blocking condition: intent gap

- 17 findings (high 0, medium 1, low 11, false 5). One medium routes to `intent_gap`; everything else is rejected or moot (see the follow-up entry in the Review Triage Log).
- **The gap.** After a content-update restart, the stored cube becomes the Lesson's entry (as the intent requires). The Tutor then derives step 0 and the authored path from that cube, so hints, the guide Move and ON_PATH feedback lead a Learner whose Checkpoint was mid-Lesson to undo work already done. Because `contentRevision` hashes all content, every content release hits this for every Learner mid-Lesson.
- **Code reverted.** The story's code changes were reverted to `baseline_revision` (8570775fa83d0e56c6fbe336668897119f8a4092). The full implementation is saved in `_bmad-output/implementation-artifacts/spec-6-2-versioned-checkpoints-at-objective-boundaries-intent-gap.patch`, which `git apply` re-applies cleanly.
- **Unresolved questions:**
  1. After a content-update restart, should the Tutor derive the step and the authored path from the updated Lesson's authored entry (so a mid-Lesson cube lands on its first unmet step through the off-path rule), while the history and Reset still start at the stored cube? Or should the stored cube stay the Tutor's entry (step 0, authored Moves from there)?
  2. If the stored cube stays the Tutor's entry, should path guidance (guide Move, ON_PATH feedback, hints) be suppressed or replaced by recovery until the cube is back on an authored path?
- **When re-implemented:** add the verification-gap case (a decoded snapshot with invalid `done` Notation falls back to the Checkpoint) to `SessionHostCheckpointTest`.
- **Verification:** the revert was checked against `baseline_revision` (only this spec differs), and `git apply --check` confirms the saved patch applies. No Gradle run, because no code changed beyond the reversion.

