---
title: 'Story 6.1: Survive process death with session snapshots'
type: 'feature'
created: '2026-11-05'
baseline_revision: '98ad6e599908d694efdd12608ea21b60e5d251d0'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      SOLVE_PLAYBACK non-resume is proven only by a same-process restart of the persistence graph on the device, not by a real process kill.
    evidence: |-
      Intent audit: LessonProcessDeathTest kills the app with am kill only for a Lesson; playback has no in-app entry until Story 3.7, so SessionSnapshotRestartTest (device) and SessionHostPersistenceTest close and reopen the file DB in one process instead. Settle by adding a playback leg to LessonProcessDeathTest once Story 3.7 wires playback into the Solve tab.
    location: >-
      macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/LessonProcessDeathTest.kt
    severity: medium
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

### 2026-11-05 — Review pass
- verdicts: 19 findings — high 1, medium 3, low 10, false 5, maybe-false 0
- findings:
  - `[high]` `[patch]` (blind) restore rejects a valid snapshot whose Checkpoint sits on another branch (Undo below a boundary, then a different Move) — reachable: `move()` moves the Checkpoint only at boundaries, so the prefix check dropped the whole Lesson. Removed the prefix check in `Reduce.kt` restore; the Checkpoint is rebuilt from `start` on its own. Added the `SessionSnapshotTest` branching regression and dropped the `done = "R", checkpoint = "F"` "bad" case that encoded the bug.
  - `[medium]` `[patch]` (blind) `SessionHost.restore` completed `restored` in `finally` even when cancelled — a shell leaving composition mid-read (for example a rotation on cold start) left restoration marked done without loading, so the reopened Lesson restarted fresh and overwrote the snapshot. Moved the work into `restoreLatest` and complete only after a non-cancelled attempt.
  - `[medium]` `[patch]` (blind) an operation throwing `CancellationException` ended the `SerialWriter` consumer, so later saves queued forever and `read`/`flush` hung. The wrapper now calls `currentCoroutineContext().ensureActive()`: only the consumer's own cancellation stops the queue.
  - `[low]` `[reject]` (blind) tutor fields get no semantic validation on restore — only a hand-corrupted row could carry them, since the payload is written by this code; the guards would add branches for an unlikely case.
  - `[low]` `[reject]` (blind) the history rebuild by repeated commit/undo is quadratic — Lesson histories are tens of Moves; negligible.
  - `[low]` `[reject]` (blind) the unlimited writer queue can hold many snapshots — they are small strings and the single writer drains in milliseconds; coalescing adds complexity for no seen harm.
  - `[low]` `[reject]` (blind) a `SnapshotSave.Failed` is not retried — the next committed transition writes again; retry machinery is more than a direct fix.
  - `[low]` `[reject]` (blind) the process-death test compares only the cube summary text — it also asserts Undo is enabled, which a fresh restart never has, so a lost session fails the test.
  - `[low]` `[reject]` (blind) the 1 s sleep before the kill is timing-based — the writer commits within milliseconds and the test app exposes no cross-process signal; it passed on the emulator.
  - `[high]` `[patch]` (edge) the Checkpoint prefix check rejects branched history — the same root cause as the first blind row, fixed by the same patch.
  - `[low]` `[reject]` (edge) a cold launch after swipe-away does not reopen the restored Lesson — by design: the session resumes in `SessionHost`, and the Continue card that surfaces it is Story 6.5's acceptance criterion, outside this story's intent.
  - `[medium]` `[patch]` (edge) a cancelled restore is marked complete — the same root cause and patch as the second blind row.
  - `[medium]` `[patch]` (edge) a `CancellationException` from an operation kills the writer consumer — the same root cause and patch as the third blind row.
  - `[low]` `[patch]` (verification-gap) a non-empty `assistanceDismissed` and the other tutor collections were never round-tripped through JSON and Room. `RoomSessionSnapshotRepositoryTest` now saves and reloads a snapshot with non-empty `stepMistakes`, `cleanCredited`, `stepHelped`, `assistanceDismissed` and a `noticeStep`; the derived assistance is covered by the reducer round-trip.
  - `[medium]` `[defer]` (intent) SOLVE_PLAYBACK non-resume is not proven under a real kill — playback has no in-app entry until Story 3.7. Recorded in `deferred`.
  - `[false]` `[reject]` (intent) a fresh cold launch does not navigate to the Lesson — the session does resume, and the UI entry is Story 6.5.
  - `[false]` `[reject]` (intent) latest-wins across kinds could hide a Lesson behind a later playback — unreachable: the reducer rejects a playback start while a Lesson is active, and a Lesson never ends.
  - `[false]` `[reject]` (intent) dropping a snapshot whose `contentRevision` changed, with no message, is ground Story 6.2 owns — 6.1 has no copy for that case; 6.2 adds the restart-with-stored-cube message for Checkpoints.
  - `[false]` `[reject]` (intent) the migration harness only validates v1 — there is no v2 schema to migrate yet; the harness (exported schema plus `MigrationTestHelper`) is what AD-10 requires per schema change.

### 2026-11-05 — Review pass
- verdicts: 23 findings — high 0, medium 3, low 12, false 8, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) tutor counters, `noticeStep` and collection keys are not range-checked on restore — carried: the earlier "tutor fields get no semantic validation" row; `Reduce.kt` restore still copies them unchecked, and only a hand-corrupted row could carry bad values.
  - `[low]` `[reject]` (blind) a LESSON snapshot with `checkpoint = null` restores with no Checkpoint — only a hand-corrupted row can carry it: `LessonStarted` always sets the entry Checkpoint (`Reduce.kt:99`) and nothing clears it for a Lesson; the guard adds a branch for an unreachable case.
  - `[low]` `[patch]` (blind) `SnapshotPayload.format` defaulted to `1`, so JSON with no `format` decoded as the current format. Removed the default; `encode` passes `format = 1` explicitly (the written JSON is unchanged, `encodeDefaults` already wrote it). `RoomSessionSnapshotRepositoryTest` now also asserts a row with the `format` field stripped loads as `Failed`.
  - `[low]` `[reject]` (blind) the unlimited writer queue holds whole growing histories — carried: same claim as the earlier unlimited-queue row.
  - `[low]` `[reject]` (blind) a `SnapshotSave.Failed` is never retried — carried: same claim as the earlier no-retry row.
  - `[low]` `[reject]` (blind) production never flushes pending saves on backgrounding; the device test sleeps 1 s — carried for the sleep; the writer drains in milliseconds, far inside the time Android takes to kill a backgrounded process, and a lifecycle flush adds new surface for no observed loss.
  - `[low]` `[reject]` (blind) `LessonProcessDeathTest` compares only the cube description — carried: same claim as the earlier summary-text row; Undo enabled is also asserted.
  - `[medium]` `[patch]` (blind) the cancellation fix in `SessionHost.restore` has no regression test — grouped with the verification-gap row. Added `SessionHostPersistenceTest.cancelledRestoreStaysOpenForTheNextCall`: a gated read is cancelled, the next `restore` loads the snapshot and `awaitRestored` completes. A mutation (complete on cancellation) makes it fail with `[]` instead of `[R]`.
  - `[false]` `[reject]` (blind) the round trip never covers several undone Moves or Cube Rotations — the seeded 400-event sequence draws `undo` repeatedly, producing multi-entry redo stacks, and asserts full `history` equality (done and undone order); rotation formatting round-trips in `NotationTest`.
  - `[low]` `[reject]` (edge) a relaunch after task removal leaves the restored Lesson hidden and another Lesson overwrites it — carried: same claim as the earlier swipe-away row; surfacing it is Story 6.5's Continue card.
  - `[low]` `[reject]` (edge) an out-of-range `noticeStep` is restored — carried: the tutor-validation row above.
  - `[low]` `[reject]` (edge) quadratic history rebuild on restore — carried: same claim as the earlier quadratic row.
  - `[medium]` `[patch]` (verification-gap) restore retry after lifecycle cancellation is unverified — same root cause and patch as the blind cancellation-test row.
  - `[false]` `[reject]` (intent) swipe-away is only partly delivered (reading A2) — carried: same claim as the earlier "fresh cold launch does not navigate" row.
  - `[low]` `[reject]` (intent) the macrobenchmark checks only the cube description and Undo, after a fixed 1 s sleep — carried: the earlier summary-text and sleep rows.
  - `[false]` `[reject]` (intent) the `AppShell` → saved `openLesson` → `LessonScreen` chain is exercised only on the device — it is exercised, by `LessonProcessDeathTest`, which passed; no defect at that surface.
  - `[false]` `[reject]` (intent) the Checkpoint is validated by replay, not as a strict prefix of `done` — deliberate: the earlier high patch showed the reducer produces branched Checkpoints, so the strict reading rejects reachable states.
  - `[medium]` `[patch]` (intent) no test cancels a restore — same root cause and patch as the blind cancellation-test row.
  - `[low]` `[reject]` (intent) DB failure is tested with a closed DB and a fake repository, not through the Hilt graph — the graph binds the same `RoomSessionSnapshotRepository` whose catch is under test; a device-graph failure test adds a harness for no distinct risk.
  - `[false]` `[reject]` (intent) `PersistenceModule` exposes an unused `SessionSnapshotDao` — Hilt providers are lazy; an unused binding causes no runtime or build harm.
  - `[false]` `[reject]` (intent) a host from the test constructor saves `contentRevision = null` — that host has no repository or writer, so it saves nothing.
  - `[false]` `[reject]` (intent) starting a different Lesson overwrites the LESSON row — the intent keys rows by `SessionKind`; one LESSON row is the specified behavior.
  - `[false]` `[reject]` (intent) a later playback hides an older LESSON row — carried: same claim as the earlier latest-wins row.

## Design Notes

- **Revision on restore.** A restored session restarts its revision at IDLE + 1 with a fresh `sessionId`. The conditional write then simply replaces the old row, and the TRANSITIONS revision rule needs no exception. Revisions and effect ids are process-local bookkeeping: no effect survives a restart.
- **Two instrumented tests.** A real `am kill` needs a test that runs outside the app process, and only `:macrobenchmark` does. `SOLVE_PLAYBACK` has no in-app entry until Story 3.7, so its non-resume is proven on the device by restarting the persistence graph on the same DB file, not by a UI kill. Recorded as a residual risk.
- **Which snapshot wins.** "Latest by `writtenSeq`" means a playback started after a Lesson keeps that Lesson from resuming through its snapshot. Resuming a suspended Lesson is Story 6.5's job, through Checkpoints.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass (JVM tests, Kover, gates, APKs, `assembleBenchmark`).
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*Snapshot*' --tests '*Persistence*' --tests '*Migration*' --tests '*TransitionsTable*'` -- expected: pass.
- Boot `spike3d_api29` headless, then `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rubikxplorer.app.data.SessionSnapshotRestartTest` and `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rubikxplorer.macrobenchmark.LessonProcessDeathTest` -- expected: pass. If no emulator boots, record "not run" with the reason.

## Auto Run Result

**Summary:**
- A Lesson now survives process death. Every committed transition writes a pure `SessionSnapshot` through one serial writer to Room 3 (`session_snapshot`, keyed by `SessionKind`). The write is conditional on `revision` and happens off the main thread.
- On cold start `SessionHost.restore` reads the latest row. A resumable `LESSON` comes back through the new `SessionEvent.SessionRestored`. `SOLVE_PLAYBACK`, changed content or a corrupt row stays idle.
- `LessonScreen` waits for restoration before deciding whether to start the Lesson, so the reopened Lesson is kept.

**Files:**
- `core/application/.../session/SessionSnapshot.kt` — the pure DTO and `of(state, sessionId, contentRevision)`.
- `core/application/.../session/{SessionEvent,SessionKind,Reduce,SessionState}.kt`, `TRANSITIONS.md` — the `SessionRestored` reduction (revision IDLE + 1), `resumesOnColdStart`, and the table row.
- `core/application/.../port/SessionSnapshotRepository.kt` — the port with the sealed `SnapshotSave`/`SnapshotLoad` results.
- `app/.../data/{RubikDatabase,SessionSnapshotEntity,SessionSnapshotDao,SnapshotPayload,RoomSessionSnapshotRepository,SerialWriter}.kt` — Room v1, the JSON payload, the transactional conditional write, and the FIFO writer.
- `app/.../di/PersistenceModule.kt` — Hilt wiring for `rubikxplorer.db`.
- `app/.../session/SessionHost.kt` — capture on each commit, sessionId, `restore`, `awaitRestored`.
- `app/.../ui/shell/AppShell.kt`, `app/.../ui/lesson/LessonScreen.kt` — trigger the restore and await it.
- `app/schemas/.../1.json` — the exported schema.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — Room 3.0.3, sqlite-framework 2.7.1, serialization and room3-testing.
- `build-logic/.../LearnerTextPolicy.kt` (+ test) — Room `@Query` SQL is exempt from the learner-text gate.
- Tests: `SessionSnapshotTest`, `TransitionsTableTest`, `RoomSessionSnapshotRepositoryTest`, `SerialWriterTest`, `SessionDatabaseMigrationTest`, `SessionHostPersistenceTest`, `LessonScreenTest`; instrumented `SessionSnapshotRestartTest` and `LessonProcessDeathTest`.
- `docs/ARCHITECTURE.md` — one line on the layer and the device checks.

**Review:** 19 findings: high 1, medium 3, low 10, false 5.
- Patched: the branched-Checkpoint restore (high), the cancelled restore marked complete (medium), and the writer consumer dying on `CancellationException` (medium). Each was reported twice, by the blind and edge-case layers. Also patched: the tutor-collection payload round-trip test (low).
- Deferred: a real-kill proof of SOLVE_PLAYBACK non-resume (medium, until Story 3.7).
- Rejected: 7 low and 5 false, with reasons in the triage log.

**Follow-up review recommended:** true (patched: high 1, medium 2, low 1). The named risk: the seeded 400-event round-trip in `SessionSnapshotTest` passed before the high fix, so it never explored a branched history. Other unexplored history and Checkpoint shapes are unverified until a follow-up pass, or a generator that targets Undo across boundaries, checks them.

**Verification:**
- `./gradlew spotlessCheck build`: pass, after the patches.
  - The Codex implementation run had normalized the CRLF checkout to LF, which made Spotless fail in this checkout (global `core.autocrlf=true`). `./gradlew spotlessApply` restored the line endings with no content change.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rubikxplorer.app.data.SessionSnapshotRestartTest`: pass, 4/4 on AVD `spike3d_api29` (API 29, headless, KVM).
- `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rubikxplorer.macrobenchmark.LessonProcessDeathTest`: pass, 1/1. A real `am kill` followed by a relaunch from the launcher.
- Matrix audit, every row covered by a passing test:
  - Lesson resumes: `SessionSnapshotTest` and the restart scenario.
  - Playback: the restart scenario `playback`.
  - Content changed: the restart scenario `changed` / `missing`.
  - Corrupt row: the `SessionSnapshotTest` bad list, plus the repository test (`format` 99, broken JSON).
  - Stale write and new session: `RoomSessionSnapshotRepositoryTest`.
  - Non-IDLE: `SessionSnapshotTest` active.
  - DB failure: the repository test on a closed DB, plus `failureCompletesRestorationAndWriterContinues`.

**Residual risks:**
- SOLVE_PLAYBACK non-resume has no real-kill proof (deferred to Story 3.7).
- `LessonProcessDeathTest` relies on a 1 s wait for the async writer before the kill.
- The device tests are not run by CI (`connected*` is excluded by rule).
- A cold launch after swipe-away resumes the session in memory, but nothing shows it until Story 6.5's Continue card.

### Follow-up review pass (2026-11-05)

**Summary:** A fresh four-layer review of the full 6.1 diff. No high finding; two small patches.

**Files changed in this pass:**
- `app/.../data/SnapshotPayload.kt` — `format` has no default; `encode` writes `format = 1` explicitly.
- `app/src/test/.../data/RoomSessionSnapshotRepositoryTest.kt` — a row with no `format` field loads as `Failed`.
- `app/src/test/.../data/SessionHostPersistenceTest.kt` — `cancelledRestoreStaysOpenForTheNextCall`, the regression test for the cancelled-restore fix.

**Review:** 23 findings: high 0, medium 3, low 12, false 8.
- Patched: the missing cancelled-restore regression test (medium; reported by the blind, verification-gap and intent layers) and the defaulted payload `format` (low).
- Deferred: none new.
- Rejected: 10 low and 8 false, with reasons in the triage log (most carried from the first pass).

**Follow-up review recommended:** false. This was a follow-up pass and patched no high (patched: medium 1 entry, low 1), so the work has converged.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests '*SessionHostPersistence*' --tests '*RoomSessionSnapshotRepository*'`: pass.
- Mutation check: making `restore` complete on cancellation fails the new test (`expected [R] but was []`); reverted.
- `./gradlew spotlessCheck build`: pass.
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*Snapshot*' --tests '*Persistence*' --tests '*Migration*' --tests '*TransitionsTable*'`: pass.
- Device tests not re-run: this pass changes no runtime behavior on the device path (the encoded JSON is identical).

**Residual risks:** unchanged from the first pass.
