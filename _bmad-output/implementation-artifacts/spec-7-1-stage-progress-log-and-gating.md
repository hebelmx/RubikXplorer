---
title: 'Story 7.1: Stage progress log and gating'
type: 'feature'
created: '2026-11-06'
baseline_revision: '8747a544b5cc816adbc1e9e242b938494e6b6703'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/AGENTS.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The app can run individual Lessons and persist attempts, checkpoints, snapshots, and preferences, but has no persistent `progress_event` log, no single-writer use cases for Stage progression, and no pure derivation of Stage status or prerequisite gating across the Course (FR-4, FR-19, FR-20, AD-10, AR-14, AR-21).

**Approach:** Add the pure `ProgressEvent` hierarchy (`LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageUnlocked`, `ProgressReset(epoch)`), `StageStatus` enum (`LOCKED`, `IN_PROGRESS`, `COMPLETED`, `SKIPPED`, `SKIPPED_BY_PLACEMENT`, `MASTERED`), and pure `StageProgress` derivation and prerequisite-gating functions in `:core:domain`. Declare `ProgressRepository` and the four single-writer use cases (`CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, `ResetProgress`) in `:core:application`, back them with an append-only Room `progress_event` table (schema v5, `AutoMigration(4, 5)`), and wire `SessionHost` to invoke `CompleteLesson` when a Lesson's Completion Predicate is satisfied.

## Boundaries & Constraints

**Always:**
- **Pure domain model (`:core:domain/tutor/`):**
  - `sealed interface ProgressEvent` with `val at: Instant` and five variants:
    - `LessonCompleted(val lessonId: LessonId, override val at: Instant = Instant.fromEpochMilliseconds(0))`
    - `StageSkippedByCheck(val stageId: StageId, override val at: Instant = Instant.fromEpochMilliseconds(0))`
    - `StageSkippedByPlacement(val stageId: StageId, override val at: Instant = Instant.fromEpochMilliseconds(0))`
    - `StageUnlocked(val stageId: StageId, override val at: Instant = Instant.fromEpochMilliseconds(0))`
    - `ProgressReset(val epoch: Long, override val at: Instant = Instant.fromEpochMilliseconds(0))`
  - `enum class StageStatus { LOCKED, IN_PROGRESS, COMPLETED, SKIPPED, SKIPPED_BY_PLACEMENT, MASTERED }`.
  - `object StageProgress` is pure and operates only over the current epoch's events (all events after the last `ProgressReset`, or all events when no `ProgressReset` is present; `currentEpoch(events)` returns the last `ProgressReset.epoch` or `0L`):
    - A Stage's prerequisites are its transitive external `Lesson.prerequisites` (or the preceding Stage in `stages` when the Stage is after index 0 and declares no external Lesson prerequisites). A prerequisite Lesson is satisfied when `LessonCompleted(lessonId)` is in the current epoch, when its owning Stage is skipped (`StageSkippedByCheck` / `StageSkippedByPlacement`) or mastered in the current epoch, or when a subsequent Stage has `StageUnlocked` / `StageSkipped*` in the current epoch.
    - A Stage is unlocked when its prerequisites are met, when `StageUnlocked(stage.id)` is in the current epoch, when it is skipped or mastered in the current epoch, or when at least one of its Lessons has `LessonCompleted` in the current epoch.
    - `status(stage, stages, events, mastered: Set<StageId> = emptySet()): StageStatus` returns:
      1. `LOCKED` if the Stage is not unlocked;
      2. `MASTERED` if `stage.id in mastered` (neither Lesson completion nor skipping ever produces `MASTERED` on its own);
      3. `COMPLETED` if `stage.lessons.isNotEmpty()` and every Lesson in `stage.lessons` has `LessonCompleted` in the current epoch;
      4. `SKIPPED` if the latest skip event for `stage.id` in the current epoch is `StageSkippedByCheck`;
      5. `SKIPPED_BY_PLACEMENT` if the latest skip event for `stage.id` in the current epoch is `StageSkippedByPlacement`;
      6. `IN_PROGRESS` otherwise (unlocked, not yet completed/skipped/mastered).
    - `statuses(stages, events, mastered): Map<StageId, StageStatus>` maps every Stage in `stages` to its `status`.
    - `isLessonUnlocked(lesson, stages, events, mastered): Boolean` checks that the Lesson's Stage is unlocked and all transitive `lesson.prerequisites` are satisfied (or the Stage is already completed, skipped, or mastered, keeping skipped/completed Stages revisitable).
- **Port & single writers (`:core:application`):**
  - `ProgressRecord(val sessionId: String, val revision: Long, val event: ProgressEvent)` and `ProgressRepository { suspend fun all(): ProgressLoad; suspend fun append(records: List<ProgressRecord>): ProgressSave }` in `:core:application/port/`, with sealed `ProgressLoad { Found(events, records), Failed }`, `ProgressSave { Saved, Failed }`, and `ProgressWrite { Written(events), PredicateNotMet, UnknownStage(stageId), Failed }` (AD-12).
  - Only `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress` in `:core:application/usecase/` call `ProgressRepository.append`:
    - `CompleteLesson(repository)` checks `lesson.completion.isSatisfied(state)` (Completion Predicate only, never Move count). If false, it returns `ProgressWrite.PredicateNotMet` without writing. If true, it loads existing events, appends `LessonCompleted(lesson.id, at)`, and appends `StageUnlocked(stage.id, at)` for any Stage in `stages` that transitions from `LOCKED` to unlocked.
    - `PassSkipCheck(repository)` appends `StageSkippedByCheck(stageId, at)` for the passed Stage(s) and `StageUnlocked(nextStage.id, at)` for any newly unlocked Stage (including the Stage immediately following a skipped Stage).
    - `PlaceFromScan(repository)` appends `StageSkippedByPlacement(earlierStage.id, at)` for each earlier Stage before `targetStageId` that is `LOCKED` or `IN_PROGRESS`, and `StageUnlocked(targetStageId, at)` when `targetStageId` was `LOCKED` (or after index 0 without an existing `StageUnlocked` in the current epoch).
    - `ResetProgress(repository)` appends `ProgressReset(currentEpoch + 1L, at)` and never deletes or updates rows.
- **Room storage & wiring (`:app`):**
  - Room schema v5 adds table `progress_event` (`ProgressEventEntity`, `ProgressDao`, `RoomProgressRepository`) with columns `seq` (autogenerated PK), `session_id`, `revision`, `type`, `stage_id`, `lesson_id`, `epoch`, `at_ms`, and a unique index on `(session_id, revision, type, stage_id, lesson_id)` with `OnConflictStrategy.IGNORE`. `ProgressDao` has `insert` and `all` only (no update, upsert, or delete).
  - `RubikDatabase` bumps to `version = 5` with `AutoMigration(from = 4, to = 5)` and exports `app/schemas/dev.rubikxplorer.app.data.RubikDatabase/5.json`.
  - `PersistenceModule` provides `ProgressRepository`, `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress`.
  - `SessionHost` accepts optional `completeLesson: CompleteLesson? = null` (injected in `@Inject` constructor) and records `bundle.stages` on `restore`/`endSolve`. When a `LESSON` transition newly satisfies `objectiveMet`, `SessionHost` enqueues `completeLesson` on `SerialWriter` with the Lesson, `transition.state.virtual`, `stages`, timestamp, `sessionId`, and `revision`.

**Never:**
- Never determine Lesson or Stage completion by Move count; completion is decided only by `lesson.completion.isSatisfied(state)`.
- Never mark a Stage `MASTERED` from `LessonCompleted`, `StageSkippedByCheck`, or `StageSkippedByPlacement`.
- Never update or delete rows in `progress_event`, and never write to `ProgressRepository` or `ProgressDao` outside `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress`.
- Never modify Learn tab UI (Story 7.2),Stages 4–6 JSON content (Story 7.3), Practice sessions (Story 7.4), Mastery Condition evaluation over attempts (Story 7.5), You tab / Settings reset UI (Story 7.6), or Welcome / skip-ahead check UI (Story 7.7).
- Never import Android, Room, Compose, or Hilt in `:core:*`. Never edit planning artifacts, `_bmad/`, or `.agents/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh epoch | Empty `progress_event` log over Stages 1–3 | Stage 1 is `IN_PROGRESS` (`lesson-01-01` unlocked, `01-02` locked); Stages 2–3 are `LOCKED` | — |
| Predicate unmet (Move count reached) | `CompleteLesson` called after 10 Moves that do not satisfy `lesson.completion` | Returns `ProgressWrite.PredicateNotMet`; 0 rows appended; Stage status unchanged | Sealed `PredicateNotMet` |
| Predicate met | `CompleteLesson` called with `CubeState` satisfying `lesson-01-01..03` | Appends `LessonCompleted` for each; on `lesson-01-03`, also appends `StageUnlocked(stage-02-first-cross)`; Stage 1 becomes `COMPLETED`, Stage 2 becomes `IN_PROGRESS` | — |
| Skip check passed | `PassSkipCheck` on Stage 2 | Appends `StageSkippedByCheck(stage-02)` and `StageUnlocked(stage-03)`; Stage 2 is `SKIPPED` (never `MASTERED`), Stage 3 is `IN_PROGRESS` | Unknown Stage → `UnknownStage` |
| Placement accepted | `PlaceFromScan` targeting Stage 3 on fresh epoch | Appends `StageSkippedByPlacement(stage-01)`, `StageSkippedByPlacement(stage-02)`, `StageUnlocked(stage-03)`; Stages 1–2 are `SKIPPED_BY_PLACEMENT`, Stage 3 is `IN_PROGRESS` | Unknown Stage → `UnknownStage` |
| Revisit skipped Stage | Stage 1 is `SKIPPED_BY_PLACEMENT`, then all Stage 1 Lessons are completed | Stage 1 transitions to `COMPLETED`; lessons in skipped Stages are unlocked for revisit | — |
| Reset progress | `ResetProgress` after completions and skips | Appends `ProgressReset(epoch = 1)`; prior rows remain in DB; current epoch statuses reset (Stage 1 `IN_PROGRESS`, Stages 2–3 `LOCKED`); preferences untouched | — |
| Replayed idempotency key | Same `(sessionId, revision, type, stageId, lessonId)` inserted twice | Duplicate ignored (`OnConflictStrategy.IGNORE`); single row retained | IGNORE |
| Repository failure | Room read or insert throws | Returns `ProgressLoad.Failed` / `ProgressSave.Failed` / `ProgressWrite.Failed`; no crash | Caught, sealed `Failed` |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Model.kt` & `Ids.kt` -- `Stage`, `Lesson`, `StageId`, `LessonId`, `Lesson.prerequisites`, `Lesson.completion` (read-only).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/ProgressEvent.kt` (new) -- `ProgressEvent` sealed interface and its 5 data class subtypes.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageProgress.kt` (new) -- `StageStatus` enum and pure `StageProgress` functions (`currentEpoch`, `currentEpochEvents`, `prerequisitesMet`, `isLessonUnlocked`, `status`, `statuses`, `newlyUnlocked`).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/CertaintyProducerTest.kt` -- pattern for source-scanning single-producer / single-writer invariant tests.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/AttemptRepository.kt` & `PreferencesRepository.kt` -- pattern for port and sealed load/save results.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/ProgressRepository.kt` (new) -- `ProgressRecord`, `ProgressRepository`, `ProgressLoad`, `ProgressSave`, `ProgressWrite`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/{CompleteLesson,PassSkipCheck,PlaceFromScan,ResetProgress}.kt` (new) -- the 4 single-writer use cases for `progress_event`.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/{AttemptEntity,AttemptDao,RoomAttemptRepository}.kt` -- pattern for append-only Room entity, DAO (`OnConflictStrategy.IGNORE`), and repository.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/{ProgressEventEntity,ProgressDao,RoomProgressRepository}.kt` (new) -- Room `progress_event` entity, DAO, and repository adapter.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/RubikDatabase.kt` -- bump `version = 5`, register `ProgressEventEntity::class`, add `AutoMigration(from = 4, to = 5)`, and declare `abstract fun progress(): ProgressDao`.
- `app/src/main/kotlin/dev/rubikxplorer/app/di/PersistenceModule.kt` -- provide `ProgressRepository`, `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress`.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- add optional `completeLesson: CompleteLesson? = null` (injected in `@Inject` constructor), capture `bundle.stages` on `restore`/`endSolve`, and invoke `completeLesson` on the `SerialWriter` when a `LESSON` transition newly satisfies `objectiveMet`.
- `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionDatabaseMigrationTest.kt` -- add 4→5 migration test preserving v4 tables and validating `progress_event`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/{ProgressEvent,StageProgress}.kt` (new) -- define `ProgressEvent`, `StageStatus`, and pure `StageProgress` derivation and gating functions.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageProgressTest.kt` (new) -- unit-test all 6 `StageStatus` values, epoch isolation across multiple `ProgressReset(epoch)` events, Lesson and Stage prerequisite gating on the shipped Stages 1–3 bundle, revisiting skipped Stages, and empty-lesson Stage handling.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/ProgressRepository.kt` (new) -- define `ProgressRecord`, `ProgressRepository`, `ProgressLoad`, `ProgressSave`, and `ProgressWrite`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/{CompleteLesson,PassSkipCheck,PlaceFromScan,ResetProgress}.kt` (new) -- implement the 4 single-writer use cases for Stage progress.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/{StageProgressUseCasesTest,ProgressWriterTest}.kt` (new) -- unit-test all I/O & Edge-Case Matrix scenarios for `CompleteLesson` (including Move count vs Completion Predicate), `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress`, plus a source-scan test verifying only those 4 use cases call `ProgressRepository.append` and `ProgressDao` has no update/delete queries.
- `app/src/main/kotlin/dev/rubikxplorer/app/data/{ProgressEventEntity,ProgressDao,RoomProgressRepository,RubikDatabase}.kt`, `app/schemas/dev.rubikxplorer.app.data.RubikDatabase/5.json`, `app/src/main/kotlin/dev/rubikxplorer/app/di/PersistenceModule.kt`, `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- add Room `progress_event` persistence, v5 migration, DI bindings, and `SessionHost` lesson-completion wiring.
- `app/src/test/kotlin/dev/rubikxplorer/app/data/{RoomProgressRepositoryTest,SessionHostProgressTest,SessionDatabaseMigrationTest}.kt` -- test Room round-trip of all 5 event types, idempotency on replayed keys, DB failure handling, end-to-end `SessionHost` lesson completion unlocking Stage 2 while non-satisfying moves do not, and 4→5 Room schema migration.
- `docs/ARCHITECTURE.md` -- add a brief note on the `progress_event` table and its four single-writer use cases.

**Acceptance Criteria:**
- Given a `progress_event` table (`LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageUnlocked`, `ProgressReset(epoch)`) written only by `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, `ResetProgress`, when Stage status is read, then it is a pure function over the current epoch's events (`Locked`, `In progress`, `Completed`, `Skipped`, `Skipped by placement`, `Mastered`).
- Given a Stage's prerequisites, when they are met, then the Stage unlocks; completion is by Completion Predicate only, never by Move count.
- Given the v4 database, when the app upgrades to v5, then the 4→5 migration test preserves existing rows and creates `progress_event`, and `./gradlew spotlessCheck build` passes.

## Spec Change Log

## Review Triage Log

### 2026-11-06 — Review pass
- verdicts: 19 findings — high 0, medium 4, low 7, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` `StageProgress.isLessonUnlocked` delegated intra-Stage prerequisites of an `IN_PROGRESS` Stage to `isStageSatisfied`, unlocking its later Lessons when a subsequent Stage was skipped or unlocked — patched `StageProgress.isLessonUnlocked` to require `ProgressEvent.LessonCompleted` in the current epoch for `ownLessons` while using `isLessonPrerequisiteMet` for external prerequisites, and added unit test assertions in `StageProgressTest.kt`.
  - `[low]` `[patch]` `StageProgress.prerequisitesMet` treated `stageIndex == -1` (an unknown Stage absent from `stages` with no external Lesson prerequisites) as `<= 0` and returned `true` (`IN_PROGRESS`) — patched `StageProgress.prerequisitesMet` to return `false` when `stageIndex < 0` and added unit test coverage in `StageProgressTest.kt`.
  - `[medium]` `[patch]` `SessionHost.dispatch` triggered `completeLesson` on `SessionRestored` / `CheckpointRestored` when restoring a snapshot/checkpoint whose `objectiveMet` was already `true`, and skipped `completeLesson` if a newly started Lesson satisfied `objectiveMet` while `before.objectiveMet` was `true` — patched `SessionHost.dispatch` to guard with `stamped !is SessionEvent.SessionRestored && stamped !is SessionEvent.CheckpointRestored && (!before.objectiveMet || before.lesson?.id != lesson.id)`.
  - `[low]` `[reject]` `SessionHost.stages` is empty if `dispatch` is called before `restore(bundle)` or `endSolve(bundle)` — rejected because `AppShell` always runs `restore(bundle)` and awaits `awaitRestored()` before any Lesson interaction, and adding `stages` to `SessionEvent.LessonStarted` would add unnecessary public surface.
  - `[low]` `[reject]` `SessionHost.dispatch` does not emit a `SessionEvent` when `completeLesson.invoke` returns `ProgressWrite.Failed` — rejected because `CompleteLesson` already returns sealed `ProgressWrite.Failed` without throwing, and adding a new `SessionEvent` variant would violate the closed `SessionEvent` contract in the architecture spine.
  - `[low]` `[patch]` `RoomProgressRepository.all()` used `mapNotNull { it.record() }`, silently dropping corrupt rows instead of returning `ProgressLoad.Failed` — patched `RoomProgressRepository.all()` to map with `it.record() ?: return ProgressLoad.Failed`.
  - `[false]` `[reject]` `ProgressEventEntity` unique index `(session_id, revision, type, stage_id, lesson_id)` omits `epoch` — refuted because `ResetProgress` embeds the epoch in `session_id` (`reset:epoch-$nextEpoch`) and only emits one `ProgressReset` per `(sessionId, revision)`, and the index matches `<intent-contract>`.
  - `[low]` `[patch]` `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress` defaulted `actualRevision` to `loaded.records.size + 1` when `revision < 0L`, defeating unique-index deduplication on repeated default-argument calls in the same epoch — patched all four use cases to default `actualRevision` to `0L` when `revision < 0L`.
  - `[false]` `[reject]` `PassSkipCheck` and `PlaceFromScan` emit `StageSkippedByCheck` / `StageUnlocked` according to the spec's rules even if a target Stage was already completed or skipped — refuted because the implementation matches `<intent-contract>` lines 51–52 verbatim.
  - `[low]` `[patch]` `ProgressWriterTest` regexes missed callable references (`::append`, `::insert`) and `@RawQuery` / `DROP` / `ALTER` / `TRUNCATE` — patched `ProgressWriterTest.kt` to match callable references and additional SQL/Room mutation constructs.
  - `[medium]` `[patch]` Edge-case report that `SessionRestored` or `CheckpointRestored` on a completed Lesson snapshot re-invokes `CompleteLesson` — grouped with Blind Hunter finding 3 and patched in `SessionHost.kt`.
  - `[medium]` `[patch]` Edge-case report that skipping or unlocking a later Stage satisfies `isStageSatisfied` for an earlier `IN_PROGRESS` Stage and unlocks its intra-Stage Lessons out of order — grouped with Blind Hunter finding 1 and patched in `StageProgress.kt`.
  - `[low]` `[patch]` Edge-case report that `StageProgress.prerequisitesMet` returns `true` when `stage` is absent from `stages` (`stageIndex == -1`) — grouped with Blind Hunter finding 2 and patched in `StageProgress.kt`.
  - `[false]` `[reject]` Intent-alignment observation comparing pure domain Stage/Lesson gating (`StageProgress`) with Learn-tab UI gating — refuted because Learn-tab Stage list/detail UI gating is Story 7.2 in `epics.md`, outside Story 7.1's acceptance criteria.
  - `[false]` `[reject]` Intent-alignment observation that `StageProgress.status` accepts `mastered: Set<StageId> = emptySet()` — refuted because `progress_event` contains only the five event types in AC 1 / AD-10 and `Mastered` is evaluated from `attempt` rows by `Mastery` in Story 7.5.
  - `[false]` `[reject]` Intent-alignment observation that `PassSkipCheck` and `PlaceFromScan` take `StageId` parameters rather than evaluating a `CubeState` internally — refuted because AC 2 requires Lesson completion by Completion Predicate (`CompleteLesson`), while skip-check and placement assessment flows belong to Stories 7.7 and 11.2.
  - `[false]` `[reject]` Intent-alignment observation on dual representation of `StageUnlocked` log events and `StageProgress.prerequisitesMet` — refuted because AC 1 requires `StageUnlocked` in `progress_event` and AC 2 requires pure prerequisite unlocking.
  - `[false]` `[reject]` Intent-alignment observation that `SessionHost` runs `completeLesson` in its own `SerialWriter` block — refuted because `SerialWriter` serializes all writes on one single-threaded FIFO queue and `:core:application` use cases must remain Room-free per AD-1.
  - `[false]` `[reject]` Intent-alignment observation that `epic-7-context.md` and `spec-7-1-stage-progress-log-and-gating.md` appear in the diff — refuted because both are required `bmad-build-auto` workflow artifacts.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass (all JVM tests, Kover ≥ 80% on `:core:*`, module boundaries, manifest/SDK checks, debug and release APKs).
- `./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest --tests '*Progress*' --tests '*Migration*'` -- expected: pass.

## Auto Run Result

- **Summary of implemented change:** Implemented the pure `ProgressEvent` hierarchy (`LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageUnlocked`, `ProgressReset(epoch)`), `StageStatus` enum (`LOCKED`, `IN_PROGRESS`, `COMPLETED`, `SKIPPED`, `SKIPPED_BY_PLACEMENT`, `MASTERED`), and pure `StageProgress` derivation and prerequisite-gating functions in `:core:domain`; added `ProgressRepository` and the four single-writer use cases (`CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, `ResetProgress`) in `:core:application`; added the append-only Room `progress_event` table in schema v5 with `AutoMigration(from = 4, to = 5)`, Hilt bindings in `PersistenceModule`, and `SessionHost` completion wiring in `:app`.
- **Files changed:**
  - `../../core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/ProgressEvent.kt` — Sealed `ProgressEvent` interface and five event data classes (`LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageUnlocked`, `ProgressReset`).
  - `../../core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageProgress.kt` — `StageStatus` enum and pure `StageProgress` functions for epoch slicing, Stage/Lesson prerequisite evaluation, and status derivation.
  - `../../core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageProgressTest.kt` — Unit tests for all six `StageStatus` values, epoch resets, intra-Stage and cross-Stage prerequisite gating, skipped-Stage revisit, empty-lesson Stages, and unknown Stages.
  - `../../core/application/src/main/kotlin/dev/rubikxplorer/application/port/ProgressRepository.kt` — `ProgressRecord`, `ProgressRepository` port, and sealed `ProgressLoad`, `ProgressSave`, and `ProgressWrite` results.
  - `../../core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/CompleteLesson.kt` — Single writer for `LessonCompleted` (gated by `lesson.completion.isSatisfied(state)`) and newly unlocked `StageUnlocked` events.
  - `../../core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PassSkipCheck.kt` — Single writer for `StageSkippedByCheck` and newly unlocked `StageUnlocked` events.
  - `../../core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PlaceFromScan.kt` — Single writer for `StageSkippedByPlacement` on earlier Stages and `StageUnlocked` on the placed Stage.
  - `../../core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/ResetProgress.kt` — Single writer for `ProgressReset(epoch)` without deleting or mutating prior rows.
  - `../../core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StageProgressUseCasesTest.kt` — Unit tests for all four single-writer use cases, including Completion Predicate vs Move count, skip check, placement, revisit, reset, and repository failures.
  - `../../core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/ProgressWriterTest.kt` — Source-scanning invariant test verifying only the four use cases call `ProgressRepository.append` and `ProgressDao` has no update/upsert/delete/raw-mutation queries.
  - `../../app/src/main/kotlin/dev/rubikxplorer/app/data/ProgressEventEntity.kt` — Room entity for `progress_event` with unique index on `(session_id, revision, type, stage_id, lesson_id)`.
  - `../../app/src/main/kotlin/dev/rubikxplorer/app/data/ProgressDao.kt` — Append-only Room DAO (`insert` with `OnConflictStrategy.IGNORE` and `all` ordered by `seq ASC`).
  - `../../app/src/main/kotlin/dev/rubikxplorer/app/data/RoomProgressRepository.kt` — Room implementation of `ProgressRepository`.
  - `../../app/src/main/kotlin/dev/rubikxplorer/app/data/RubikDatabase.kt` — Bumped Room database to version 5 with `ProgressEventEntity` and `AutoMigration(from = 4, to = 5)`.
  - `../../app/schemas/dev.rubikxplorer.app.data.RubikDatabase/5.json` — Exported Room v5 schema.
  - `../../app/src/main/kotlin/dev/rubikxplorer/app/di/PersistenceModule.kt` — Hilt `@Provides` bindings for `ProgressRepository`, `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress`.
  - `../../app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` — Wired `CompleteLesson` on `SerialWriter` when an active `LESSON` transition newly satisfies `objectiveMet`.
  - `../../app/src/test/kotlin/dev/rubikxplorer/app/data/RoomProgressRepositoryTest.kt` — Robolectric Room tests for round-tripping all 5 event types, duplicate-key idempotency, DB restart persistence, preference isolation on reset, and failure handling.
  - `../../app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostProgressTest.kt` — End-to-end `SessionHost` + Room test verifying Stage 1 Lesson completions unlock Stage 2 while 10 non-satisfying Moves do not.
  - `../../app/src/test/kotlin/dev/rubikxplorer/app/data/SessionDatabaseMigrationTest.kt` — Added 4→5 migration test verifying existing v4 tables/rows are preserved and `progress_event` is created and validated.
  - `../../docs/ARCHITECTURE.md` — Documented `progress_event` and its four single-writer use cases.
- **Review findings breakdown:**
  - Patches applied: 6 grouped entries (9 findings: 4 `medium`, 5 `low`) — intra-Stage prerequisite check in `StageProgress.isLessonUnlocked`, unknown Stage check in `StageProgress.prerequisitesMet`, restore guard on `SessionHost` lesson completion trigger, corrupt-row failure return in `RoomProgressRepository.all()`, deterministic `0L` default revision in the 4 use cases, and expanded regexes in `ProgressWriterTest`.
  - Items deferred: 0.
  - Rejected findings: 10 (2 `low` rejected because `AppShell` always calls `restore(bundle)` before dispatch and `CompleteLesson` already returns sealed `ProgressWrite.Failed` without adding a new `SessionEvent`; 8 `false` refuted against `<intent-contract>`, `epics.md` Story boundaries 7.2/7.5/7.7/11.2, and `bmad-build-auto` artifact rules).
- **Follow-up review recommendation:** `false` (patched entry counts by verdict: `high` 0, `medium` 2, `low` 4; all patched entries are small, self-contained guards covered directly by unit tests in `StageProgressTest`, `StageProgressUseCasesTest`, `ProgressWriterTest`, `RoomProgressRepositoryTest`, and `SessionHostProgressTest`, leaving no specific unverified risk).
- **Verification performed:**
  - `./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest --tests '*Progress*' --tests '*Migration*'` — passed (22 test cases across `StageProgressTest`, `StageProgressUseCasesTest`, `ProgressWriterTest`, `RoomProgressRepositoryTest`, `SessionHostProgressTest`, and `SessionDatabaseMigrationTest`).
  - `./gradlew spotlessCheck build` — passed (compile, lint, all JVM tests, Kover ≥ 80% on `:core:*`, module boundaries, manifest/SDK denylist checks, debug and release APKs).
- **Residual risks:** None; UI presentation of Stage list/detail and Learner-facing skip/reset/mastery flows are scheduled in Stories 7.2–7.7.
