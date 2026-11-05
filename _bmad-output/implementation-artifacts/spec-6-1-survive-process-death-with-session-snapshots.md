---
title: 'Story 6.1: Survive process death with session snapshots'
type: 'feature'
created: '2026-11-05'
baseline_revision: '5aca3695b71e4a2a2d03ec5f5465d1cc5237bb7b'
status: 'ready-for-dev'
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

**Problem:** The one `LearningSession` lives only in `SessionHost` memory. A phone call that lets Android kill the process, or a swipe-away, loses the Learner's Lesson cube and progress (FR-19, NFR-6). There is no persistence layer yet.

**Approach:** Add Room (`androidx.room3` 3.0.3, KSP) in `:app/data` with a migration-test harness and one serial writer. `SessionHost` turns every committed transition into a pure `SessionSnapshot` DTO from `:core:application`. The serial writer stores it off the main thread, keyed by `SessionKind` and conditional on `revision`. On cold start the host restores the latest snapshot through a new `SessionEvent.SessionRestored`, and only when the snapshot's kind resumes (`LESSON`). A `SOLVE_PLAYBACK` snapshot never resumes.

## Boundaries & Constraints

**Always:**
- **DTO.** `SessionSnapshot` (`:core:application/session`) is a pure data class holding only primitives and enums. It never holds raw `SessionState` or embedded content (AD-10). Its fields:
  - `kind`, `sessionId: String`, `revision`.
  - `lessonId: String?` and `contentRevision: String?`, both `LESSON` only.
  - `start` (54-char Facelets), `done`, `undone`, `solution`, `checkpoint: String?`. The history fields are Notation strings from `start`.
  - The tutor fields: `teachingMode`, `adaptiveAssistance`, `hintsUsed`, `hintRung`, `revealRequested`, `noticeStep`, `stepMistakes`, `cleanSteps`, `cleanCredited`, `stepHelped`, `assistanceDismissed`.
  - Transients are not stored: `pendingReset`, `guidedRecovery`, `lastMoveFeedback`, `lastDiagnosis`, `lastDiscarded`, `nextEffectId`. No effect is resumed.
  - The current step is never stored (AD-6).
- **Capture.** `SessionSnapshot.of(state, sessionId, contentRevision)` is pure and returns `null` for IDLE.
- **Restore.** `SessionEvent.SessionRestored(snapshot, lesson, tutorConfig, at)` applies only from IDLE, and only when all of these hold:
  - `snapshot.kind.resumesOnColdStart` holds (`LESSON`, `PRACTICE`, `TWO_LOOK`). Only `LESSON` sessions exist today, so any other kind leaves IDLE.
  - `lesson.id` equals `snapshot.lessonId`.
  - `start` equals the Lesson's entry state.
  - Every Notation string parses.
  - `checkpoint` is a prefix-consistent history from `start`.

  Otherwise the state stays IDLE.
  - The result is the stored history (done, then undone kept as redo), checkpoint and tutor fields. The transients are cleared and `kind = LESSON`.
  - `objectiveMet` and `progress` are recomputed.
  - `revision` is IDLE's + 1, so the TRANSITIONS rule "session change advances by exactly 1" holds unchanged.
  - Presentation is `Snap(revision, virtual)`.
- **Write path.**
  - After each `dispatch` whose transition returned a different `SessionState` instance and an active session, `SessionHost` captures a snapshot and enqueues `repository.save(snapshot)` on the serial writer. Capture happens on the confined dispatcher, in commit order, never awaited by the caller.
  - The host assigns a fresh `sessionId` (UUID) whenever the transition starts a session: an applied `LessonStarted`, `SolvePlaybackStarted` or `SessionRestored`.
  - The repository writes in one transaction. It skips a write (`Stale`) only when the stored row for that kind has the same `sessionId` and a higher `revision`. Otherwise it replaces the row for that kind and stamps it with a DB-wide increasing `writtenSeq`.
- **Restore trigger.**
  - `SessionHost.restore(bundle)` runs once, when the catalog is `Ready` (from `AppShell`). It reads the latest row by `writtenSeq` through the writer.
  - It dispatches `SessionRestored` only when that row's kind resumes and its `contentRevision` and lesson resolve against the bundle.
  - It completes `awaitRestored()` in every outcome, including no row, unresolvable content or a failure. The host built by the test constructor (no repository) is restored from the start.
- `LessonScreen`'s start `LaunchedEffect` awaits `host.awaitRestored()` before deciding whether to dispatch `LessonStarted`. A restored Lesson reopened through the saved `openLesson` is therefore kept, not restarted.
- **Ports and errors.** The port is `SessionSnapshotRepository` in `:core:application/port`. It returns sealed results: `SnapshotSave.{Saved, Stale, Failed}` and `SnapshotLoad.{Found, None, Failed}`. Room exceptions become `Failed` and never crash (AD-12).
- **Serial writer.** All DB I/O runs on one serial writer: a FIFO, single consumer on `Dispatchers.IO.limitedParallelism(1)`, with an app-lifetime scope and a `suspend fun flush()` for tests. It never runs on the main thread.
- **Schema.**
  - Database version 1 has one table, `session_snapshot`, with columns `kind` (PK), `session_id`, `revision`, `written_seq` and `payload`. `payload` is JSON from a `@Serializable` mirror in `:app/data` with a `format` int.
  - The schema is exported to `app/schemas/`.
  - A migration-test harness built on room3-testing's `MigrationTestHelper` creates v1 from the exported schema and validates it.
- Room, KSP and serialization versions live only in `gradle/libs.versions.toml`. Verify every Room 3 API, artifact and driver against current docs.

**Never:**
- No Checkpoint DTO, `PersistCheckpoint`, attempt rows, preferences, Continue card or "Picked up where you left off" copy. Those belong to Stories 6.2–6.5.
- The free cube is never persisted (AD-20), and `FreeCubeViewModel` is untouched.
- No Room, Android or serialization imports in `:core:*`.
- No `INTERNET`, backup, analytics or logging of Cube States.
- No debug-only hook that starts a playback.
- Do not edit planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Lesson resumes | Stored LESSON snapshot (moves done + one undone, a Hint, Test me), same content | Restored state equals the pre-death state except transients and revision; redo available | — |
| Playback | Latest row is SOLVE_PLAYBACK | Stays IDLE | — |
| Content changed | `contentRevision` differs or lesson id missing | Stays IDLE (6.2 adds the message) | — |
| Corrupt row | Bad Notation, Facelets, start mismatch or unknown `format` | Stays IDLE | decode → `Failed` / reducer ignores |
| Stale write | Same sessionId, lower revision than stored | Row unchanged, `Stale` | — |
| New session | Different sessionId, lower revision | Row replaced | — |
| Non-IDLE | `SessionRestored` while a session is active | Unchanged | — |
| DB failure | Room throws on save or load | `Failed`; app keeps running; `awaitRestored` completes | caught |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` -- every field to capture. `revision` +1 rule; the `IDLE` constructor is internal, so restore builds the state inside the reducer.
- `.../session/Reduce.kt` -- `reduce()` guard: add `SessionRestored` to `starts`. Reuse `entryState(lesson)` (l.140) and `withHistory` (l.459), which recomputes `objectiveMet`/`progress` and bumps the revision.
- `.../session/SessionEvent.kt` -- add `SessionRestored`. `.../session/SessionKind.kt` -- add `resumesOnColdStart`.
- `.../session/TRANSITIONS.md` + `core/application/src/test/.../session/TransitionsTableTest.kt` -- a new row is required (an exhaustive `when` plus the samples map). The row is `SessionRestored | session, virtual, tutor, checkpoint | no | no`, and the idle prose sentence must mention it.
- `core/domain/.../cube/MoveHistory.kt` -- `done`/`undone` semantics: rebuild by committing `done`, then `undone.reversed()`, then `undo()` × `undone.size`. `Notation.format/parse` and `FaceletString`/`CubeValidator` cover cube I/O.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- the single owner. Keep the internal test constructor working by defaulting the new params; `SessionHostTest` and the Robolectric Lesson tests use it.
- `app/.../content/LessonCatalog.kt` -- `CatalogState.Ready.find(LessonId)`; `bundle.contentRevision` (`core/domain/.../content/Model.kt:112`).
- `app/.../ui/shell/AppShell.kt` -- `LaunchedEffect(catalog){ catalog.load() }`: call `host.restore(...)` after Ready. `openLesson` is `rememberSaveable`, so after a kill the Lesson screen reopens from saved state.
- `app/.../ui/lesson/LessonScreen.kt:127-133` -- the start check that must await restoration.
- `app/.../di/*Module.kt` -- the Hilt module pattern. Add `PersistenceModule` (database, DAO, writer, repository binding).
- `app/build.gradle.kts`, `gradle/libs.versions.toml` -- the KSP plugin is already applied. Add the room3 runtime, compiler and testing artifacts, the room3 Gradle plugin (schema dir), the androidx.sqlite driver if Room 3 requires one, and the kotlin-serialization plugin with `kotlinx-serialization-json` for `:app` main.
- `build-logic/.../policy/ModuleBoundaryPolicy.kt:69-70` -- `androidx.room3` is already banned in `:core:*`. Read-only.
- `macrobenchmark/` -- a `com.android.test` module (separate process, UiAutomator, `benchmark` build of `:app`, `:macrobenchmark:connectedBenchmarkAndroidTest`) with `Net2DFrameTimingBenchmark.kt` as the UiAutomator pattern. Lesson Move keys carry content descriptions such as `"R: right face clockwise"`. The cube node (`testTag cube3d`) has a contentDescription summary.
- Emulator: AVD `spike3d_api29` under `~/Android/Sdk` (KVM). Boot it headless with `-gpu swiftshader_indirect`. CI never runs `connected*`.

## Tasks & Acceptance

**Execution:**
- `gradle/libs.versions.toml`, `app/build.gradle.kts` -- add the Room 3, room3 Gradle plugin, sqlite driver, serialization and room3-testing deps (`testImplementation`/`androidTestImplementation`). Export schemas to `app/schemas` and add that dir as an androidTest/test asset if the helper needs it -- the persistence stack.
- `core/application/.../session/SessionSnapshot.kt` (new), `SessionKind.kt`, `SessionEvent.kt`, `Reduce.kt`, `TRANSITIONS.md` -- the DTO, capture, `resumesOnColdStart`, and `SessionRestored` reduction per Always -- pure capture and restore.
- `core/application/.../port/SessionSnapshotRepository.kt` (new) -- the port and sealed results.
- `app/.../data/` (new): `RubikDatabase.kt` (v1), `SessionSnapshotEntity.kt`, `SessionSnapshotDao.kt`, `SnapshotPayload.kt` (JSON codec), `RoomSessionSnapshotRepository.kt` (transactional conditional write, latest by `writtenSeq`), `SerialWriter.kt` -- the adapters.
- `app/.../di/PersistenceModule.kt` (new) -- provides the DB file `rubikxplorer.db` in app-private storage, the writer and the repository.
- `app/.../session/SessionHost.kt` -- the write path, sessionId, `restore`, `awaitRestored`.
- `app/.../ui/shell/AppShell.kt`, `app/.../ui/lesson/LessonScreen.kt` -- trigger restore, and await it before the start check.
- Tests (JVM): `core/application/src/test/.../session/SessionSnapshotTest.kt` covers every I/O row that reaches the reducer. It also runs a seeded random Lesson event sequence: capture, restore and compare the history, checkpoint and tutor fields at many points. Update `TransitionsTableTest` scenarios for `SessionRestored`.
- Tests (Robolectric, `app/src/test/.../data/`): `RoomSessionSnapshotRepositoryTest` (stale, new session, latest-by-seq, corrupt payload → `Failed`), `SerialWriterTest` (FIFO; the block runs off the main thread), `SessionDatabaseMigrationTest` (the harness: v1 created from the exported schema and validated by Room), and `SessionHostPersistenceTest`. The host test uses a file DB: dispatch, flush, close, then a new DB, writer and host on the same file, `restore`. It shows a LESSON resumes, a SOLVE_PLAYBACK does not, and an unresolvable lesson does not. Add a `LessonScreen` test showing a restored Lesson is not restarted.
- `macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/LessonProcessDeathTest.kt` (new) -- forced process death, UiAutomator:
  - `pm clear`, launch, open the first Lesson, tap two Move keys and read the cube description.
  - Wait ~1 s, press Home, run `am kill dev.rubikxplorer` and assert the process is gone (`pidof`).
  - Relaunch through the launcher intent (`monkey -p … -c android.intent.category.LAUNCHER 1`).
  - Assert the Lesson screen shows the same cube description and the Undo key is enabled.
- `app/src/androidTest/kotlin/dev/rubikxplorer/app/data/SessionSnapshotRestartTest.kt` (new) -- on the device, the same file-DB restart as the host test: a LESSON resumes and a SOLVE_PLAYBACK does not.
- `docs/ARCHITECTURE.md` -- one line: the persistence layer location and how to run the two instrumented tests.

**Acceptance Criteria:**
- Given Room 3 with the exported v1 schema and the migration harness, when `./gradlew spotlessCheck build` runs, then the harness test passes, and a snapshot is written after a committed transition through the serial writer, never on the main thread, and only when its revision is not older than the stored row of the same session.
- Given a Lesson with Moves made on the `benchmark` build, when the process is killed with `am kill` and the app is relaunched, then the Lesson screen shows the same cube with Undo available (`LessonProcessDeathTest`).
- Given a stored SOLVE_PLAYBACK snapshot as the latest row, when the app's persistence graph restarts on the device, then no session resumes (`SessionSnapshotRestartTest`).

## Spec Change Log

## Review Triage Log

## Design Notes

- **Revision on restore.** A restored session restarts its revision at IDLE + 1 with a fresh `sessionId`. The conditional write then simply replaces the old row, and the TRANSITIONS revision rule needs no exception. Revisions and effect ids are process-local bookkeeping: no effect survives a restart.
- **Two instrumented tests.** A real `am kill` needs a test that runs outside the app process, and only `:macrobenchmark` does. `SOLVE_PLAYBACK` has no in-app entry until Story 3.7, so its non-resume is proven on the device by restarting the persistence graph on the same DB file, not by a UI kill. Recorded as a residual risk.
- **Which snapshot wins.** "Latest by `writtenSeq`" means a playback started after a Lesson keeps that Lesson from resuming through its snapshot. Resuming a suspended Lesson is Story 6.5's job, through Checkpoints.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass (JVM tests, Kover, gates, APKs, `assembleBenchmark`).
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*Snapshot*' --tests '*Persistence*' --tests '*Migration*' --tests '*TransitionsTable*'` -- expected: pass.
- Boot `spike3d_api29` headless, then `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rubikxplorer.app.data.SessionSnapshotRestartTest` and `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rubikxplorer.macrobenchmark.LessonProcessDeathTest` -- expected: pass. If no emulator boots, record "not run" with the reason.
