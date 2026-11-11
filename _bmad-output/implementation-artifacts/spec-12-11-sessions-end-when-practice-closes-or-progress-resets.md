---
title: 'Sessions end when practice closes or progress resets'
type: 'bugfix'
created: '2026-11-10'
status: 'done'
baseline_revision: '906363c4cf4671aa2fcb725273ee12ce233e7350'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/core/application/src/main/kotlin/dev/cubentia/application/session/TRANSITIONS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Session-end cleanup on practice close is not durable under a Room write failure: a failed snapshot delete is reported but not retried, so a later cold start can restore the closed practice. (Reset is resolved: it deletes the snapshot and Checkpoint in the same writer step as the ProgressReset write and writes the reset only when both deletes succeed, otherwise it returns false.)
    evidence: |-
      SessionHost.clearSnapshots keeps failed attempt rows in pendingAttempts but no pending-clear marker. Needs a durable pending-clear marker or a retry. Only reachable on a DB write failure; nothing reset-locked can reopen, since a failed reset cleanup writes no ProgressReset (SessionEndingTest).
    location: >-
      app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Three Epic 9 seams (retro items 1–3, SCP 2026-11-10): a Course Lesson opened after (live or cold-start-restored) practice runs as practice because `LessonScreen` infers the kind from the host; *Reset progress* never ends the live session, so Continue and cold start reopen content the reset locked; and Algorithm Statuses read as `emptyMap()` before load or after a stream failure (the Room flow emits `emptyMap()` and completes), so the 2-look *Add to Learning* offer can downgrade *Learned* to *Learning*.

**Approach:** Add one reducer event that ends the live session (to IDLE, closing an open attempt as ABANDONED) and have `SessionHost` delete the snapshot when it fires. The shell ends the session whenever any practice closes; `resetProgress()` ends the session first, then also deletes the Checkpoint. `LessonScreen` stops reading the kind from the host. Statuses become a sealed load (loaded vs failed) that the Room adapter retries instead of completing; the shell offers *Add to Learning* only on loaded statuses and re-filters to *Not started* at accept time.

## Boundaries & Constraints

**Always:** session state changes only via `SessionEvent` → `reduce()` (AD-3); every new `SessionEvent` gets a `TRANSITIONS.md` row (`TransitionsTableTest`); domain failures are sealed results, not nulls; Algorithm Status written only via `SetAlgorithmStatus`; snapshot deletes and their attempt rows go through `SerialWriter` in one Room transaction; keep `:core:*` free of Android imports; JVM/Robolectric tests for every AC.

**Never:** no Room schema change or migration (deletes use existing tables); no new dependency; no new Learner-facing string (AC4 is met by retrying, not by new copy); do not change 2-look mastery rules, `TwoLookSolve`, `strings.xml` 2-look copy or `TRANSITIONS.md` practiceEntry text (Story 12.12); never edit planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Close practice | live PRACTICE (LL, Stage or skip-check), Learner closes/back/next-stage | host IDLE; open attempt ended ABANDONED; all snapshot rows deleted | delete failure dispatches `SnapshotSaveFailed`, state stays IDLE |
| Lesson after practice | host PRACTICE (live or restored), Learner opens a Course Lesson | `LessonStarted(kind = LESSON)`; Teaching Modes shown; predicate met → `LessonCompleted` row | — |
| Reset with live session | LESSON / TWO_LOOK / PRACTICE live, *Reset progress* confirmed | session ended before `ProgressReset` appended; snapshot and Checkpoint rows deleted; Continue empty; new host `restore()` leaves IDLE | progress write failure returns false; session stays ended |
| End when idle | IDLE | no state change, no effect, no delete | — |
| Statuses unknown | flow not yet emitted, or emitted `Failed` | 2-look offer hidden (`offerAlgorithms` empty) | — |
| Accept offer | offer shown, an id now *Learned* | only ids whose current loaded status is *Not started* get `LEARNING` | — |
| Room upstream fails | DAO flow throws | emits `AlgorithmStatusLoad.Failed`, then retries with backoff; never emits an empty map for the failure and never completes | `CancellationException` rethrown |

</intent-contract>

## Code Map

- `core/application/.../session/SessionEvent.kt` -- add `SessionEnded(at)`.
- `core/application/.../session/Reduce.kt` -- `applied()` ~L68-88: handle `SessionEnded` like `SolvePlaybackEnded` (IDLE copy, `revision + 1`, keep `nextEffectId`); emit `Effect.AttemptEnded(... ABANDONED ...)` when `before.attemptOpen`, mirroring `attempts().end()` ~L1124. `attempts()` returns early for IDLE, so do it in the new branch.
- `core/application/.../session/TRANSITIONS.md` -- table row + idle-unchanged sentence.
- `core/application/.../port/SessionSnapshotRepository.kt` -- add `suspend fun clear(attempts: List<AttemptRecord> = emptyList()): SnapshotSave`.
- `core/application/.../port/CheckpointRepository.kt` -- add `suspend fun clear(): CheckpointSave`.
- `core/application/.../port/AlgorithmStatusRepository.kt` -- `statuses(): Flow<AlgorithmStatusLoad>`; new sealed `AlgorithmStatusLoad { Loaded(statuses); Failed }`.
- `app/.../data/SessionSnapshotDao.kt`, `CheckpointDao.kt` -- `@Query("DELETE FROM ...")`.
- `app/.../data/RoomSessionSnapshotRepository.kt` -- `clear` = insert attempts + delete all snapshot rows in one `withWriteTransaction` (pattern of `save`).
- `app/.../data/RoomCheckpointRepository.kt` -- `clear`.
- `app/.../data/RoomAlgorithmStatusRepository.kt` L14-21 -- replace `catch { emit(emptyMap()) }` with `Loaded` map + `retryWhen` that emits `Failed`, delays (bounded backoff), returns true (false for cancellation).
- `app/.../session/SessionHost.kt` -- L117 `algorithmStatuses`: keep `Flow<Map>` (Loaded only) for existing callers, add `algorithmStatusLoad: Flow<AlgorithmStatusLoad>` (memory path = `Loaded`); new `suspend fun endSession()`; `dispatch()` ~L460: on `SessionEnded` with a changed state, build the ABANDONED `AttemptRecord` (owner `previousSessionId`, id `attemptId`) and enqueue `repository.clear(pendingAttempts + rows)`; `resetProgress()` L300: `endSession()` first, then the use case, then enqueue `checkpoints.clear()`.
- `app/.../ui/lesson/LessonScreen.kt` L253-262 -- `practicing = !isTwoLook && (isPractice || isSkipCheck)`; non-practicing `shouldStart` also when the host kind is not the expected `LESSON`/`TWO_LOOK`.
- `app/.../ui/shell/AppShell.kt` -- L131 collect `algorithmStatusLoad` with `null` initial; L205-226 offer only from `Loaded`, `onAddToLearning` re-filters from current load at accept time (pure helper, unit-tested); Library/detail use the last `Loaded` map; practice `onClose` L281, its `onNextStage`, skip-check `onClose`/`onSkipCheckPassed` L329-379, BackHandler practice/skip-check branches L381-399 -- also `scope.launch { host.endSession() }` (skip-check reads `decision` before ending).
- Tests to extend: `app/src/test/.../session/SessionHostTest.kt`, `data/SessionHostPersistenceTest.kt`, `data/SessionHostCheckpointTest.kt`, `data/RoomAlgorithmStatusRepositoryTest.kt` (L80-87 asserts the old empty-map behavior), `ui/shell/AppShellTest.kt`, `ui/shell/AppShellAlgorithmStatusRoomTest.kt`, `ui/lesson/LessonScreenTest.kt`, core `TransitionsTableTest`/reduce tests; anonymous fakes of the three ports need the new members.

## Tasks & Acceptance

**Execution:**
- `core/application` session + ports -- add event, reducer branch, TRANSITIONS row, port members, sealed load -- one owner ends sessions.
- `core/application` tests -- reducer: ended from each kind → IDLE, revision+1, ABANDONED effect iff attempt open; idle unchanged.
- `app/data` DAOs + Room repos -- clear/delete and status retry -- persistence of the end.
- `app/session/SessionHost.kt` -- `endSession`, dispatch handling, reset ordering, `algorithmStatusLoad`.
- `app/ui/lesson/LessonScreen.kt`, `app/ui/shell/AppShell.kt` -- kind fix, end on practice close, guarded offer.
- `app/src/test` -- one test per I/O row and AC below; update fakes and the old empty-map assertion.

**Acceptance Criteria:**
- Given LL practice (live, or restored on cold start) or Stage practice, when the Learner closes it and opens a Course Lesson, then the Lesson runs as `LESSON` with its Teaching Modes, and meeting its predicate writes `LessonCompleted` (AppShell Robolectric test).
- Given a live Lesson, 2-look or practice session, when the Learner confirms *Reset progress*, then the session ends, its snapshot is deleted, the Continue card offers nothing, and a new host over the same database restores nothing.
- Given statuses not yet loaded or `Failed`, when a 2-look Lesson ends, then the offer is not shown; given a shown offer, accepting never changes an Algorithm whose current status is not *Not started*.
- Given the status repository's upstream fails, when the Library is open, then the flow retries (emits `Failed`, does not complete, recovers on a later success) and the shell never treats the failure as "all *Not started*".

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 16 findings — high 0, medium 4, low 9, false 2, maybe-false 1
- findings:
  - `[medium]` `[patch]` (blind) Reset never clears snapshot rows when the host is already IDLE (failed restore or failed earlier delete), so a later cold start can reopen locked content — `resetProgress()` now always enqueues `clearSnapshots(emptyList())` after `endSession()`; new SessionEndingTest covers an idle host with a leftover row.
  - `[medium]` `[defer]` (blind) A failed snapshot delete on practice close keeps attempt rows pending but drops the delete request; a later cold start can restore the closed practice — needs a durable pending-clear marker; DB write failure only.
  - `[medium]` `[defer]` (blind) Checkpoint clear failure on reset is not reflected in the returned result, so a fallback checkpoint can survive a "successful" reset — same root cause as the previous row (cleanup not durable under write failure).
  - `[low]` `[reject]` (blind) A failed progress reset still ends the session and deletes the checkpoint — the spec's I/O matrix chose "session stays ended"; only reachable on a DB write failure.
  - `[low]` `[reject]` (blind) Offer acceptance filters a collected map that could lag a committed status change, letting *Learning* overwrite *Learned* — no Learner path changes a status while the Lesson screen is open (Library is behind the full-screen Lesson); acceptance re-filters the current load; a write-side compare-and-set would add public surface.
  - `[low]` `[reject]` (blind) Library shows an initial empty map as *Not started* before the first successful load (including a first-load failure) — same as the pre-existing loading state; the stream retries and recovers (AC4 "retried"); fixing needs loading state plumbed into Library and Detail.
  - `[medium]` `[patch]` (blind) SessionEndingTest ABANDONED assertion passes on the older row created by the LESSON→PRACTICE/TWO_LOOK switch — test now asserts exactly one new ABANDONED row paired with the live attempt id, before `ProgressReset.at`.
  - `[low]` `[reject]` (blind) SessionEndedTest builds a playback state by copying a Lesson state — reducer branch is kind-agnostic (`isActive` + `attemptOpen`); extra cases add no coverage of a distinct path.
  - `[false]` `[reject]` (blind) A delayed close-practice `endSession()` could end a newly opened Lesson — opening a Course Lesson needs a later Learner tap on Learn; the close coroutine dispatches on the confined loop long before; `onNextStage` lands on Stage detail, not a Lesson.
  - `[medium]` `[defer]` (edge) Snapshot deletion fails once, then reset succeeds; the retained snapshot restores — grouped with the durable-cleanup deferral; the unconditional reset clear patched above also retries pending rows.
  - `[low]` `[reject]` (edge) Algorithm becomes *Learned* between filter and queued *Learning* write — same as the blind lag finding; no reachable path.
  - `[low]` `[patch]` (verification-gap) Skip-check Close/Back/pass exits call `endSession()` but no test asserts the host goes idle — AppShellSkipCheckTest now asserts `!host.state.value.isActive` after each exit.
  - `[low]` `[patch]` (intent) AC2 tested only at host level, not via Settings confirm and the Continue card — added AppShellTest driving You → Settings → Reset progress → confirm and asserting `learn:continue` is gone and the host idle.
  - `[low]` `[reject]` (intent) "Restored on cold start" LL practice simulated by dispatching `SessionRestored` into the running host — that is the same event the cold restore dispatches; SessionEndingTest covers a Room-backed cold host.
  - `[low]` `[reject]` (intent) AC3 accept path not tapped in UI; covered by `algorithmsToOffer` unit test — the lambda is a one-line call of the tested helper.
  - `[maybe-false]` `[reject]` (intent) Port changes (`SessionEnded`, `clear` members, `AlgorithmStatusLoad`) exceed the story surface — they are the spec's chosen mechanism (Code Map); no harm named; would only be low.

### 2026-11-10 — Review pass
- verdicts: 21 findings — high 0, medium 3, low 15, false 3, maybe-false 0
- findings:
  - `[medium]` `[defer]` (blind) Failed snapshot delete keeps attempt rows but drops the delete request; a later cold start can restore the closed practice — carried: same as the logged durable-cleanup deferral; `clearSnapshots` unchanged.
  - `[low]` `[patch]` (blind) Process death between the `ProgressReset` write and the Checkpoint delete leaves a Checkpoint that reopens locked content — `resetProgress()` now enqueues `clearCheckpoint()` before the reset write (failure path already deleted the Checkpoint, so outcomes are unchanged).
  - `[medium]` `[defer]` (blind) Checkpoint clear failure is not reflected in the reset result — carried: logged deferral (cleanup not durable under write failure).
  - `[false]` `[reject]` (blind) Practice close leaves the Lesson Checkpoint, so cold start resumes the older Lesson — the Lesson Checkpoint is the designed Course resume point (`endSolve` falls back to it the same way); the intent requires only snapshot rows deleted on close, and resuming the Lesson does not reopen the practice.
  - `[low]` `[reject]` (blind) Reset confirmation copy omits that in-progress work ends — the intent forbids new Learner-facing strings in this story.
  - `[low]` `[reject]` (blind) `AppShell` ignores the `resetProgress()` result — pre-existing (line not changed by this story), reachable only on a DB write failure, and a fix needs new failure UI and copy the intent forbids.
  - `[low]` `[reject]` (blind) Library shows an initial or failed first load as *Not started* — carried: logged reject (pre-existing loading state; stream retries).
  - `[low]` `[reject]` (blind) Offer acceptance checks a collected map that can lag a committed write — carried: logged reject (no reachable path).
  - `[low]` `[reject]` (blind) Offer accept path, partial write failure and retry not exercised in UI — carried: logged reject (lambda is a one-line call of the tested helper).
  - `[low]` `[reject]` (blind) No Room-backed close → new host → restore test for ordinary practice close — each link is tested: shell close leaves host idle (AppShellTest), `endSession` writes ABANDONED and calls `clear` (SessionHostTest), Room `clear` deletes every kind (RoomSessionSnapshotRepositoryTest); an end-to-end Room shell test adds harness weight for no distinct path.
  - `[low]` `[patch]` (blind) Recompiled `epic-12-context.md` dropped measurable criteria (contrast ratios; solver median, p95, memory, failure rate) that later Epic 12 stories read from the cache — restored both in the Requirements section.
  - `[medium]` `[defer]` (edge) Snapshot or Checkpoint clear failure lets cold start restore closed or reset content — carried: logged durable-cleanup deferral.
  - `[low]` `[reject]` (edge) *Learned* committed before acceptance but not yet collected gets downgraded — carried: logged reject (no reachable path).
  - `[low]` `[reject]` (edge) Claim "accepting never changes a non-*Not started* status" relies on collected state — carried: logged reject.
  - `[low]` `[patch]` (verification-gap) System Back from ordinary practice calls `endSession()` but no test asserts it — added `system back from LL practice ends it then opening Course records Lesson completion` (practiceToCourse with `systemBack = true`).
  - `[low]` `[reject]` (intent) Opening a Course Lesson directly while the host is still PRACTICE is not tested through to `LessonCompleted` — LessonScreenTest asserts the restart to kind `LESSON` with Teaching Modes; completion of a `LESSON` session is covered by existing tests and the shell close-then-open tests.
  - `[false]` `[reject]` (intent) `shouldStart` adds a new host-kind read — the kind still comes from the caller (`isTwoLook`); the host kind is only compared to restart a mismatched session, never used to choose the Lesson's kind.
  - `[low]` `[reject]` (intent) Shell close tests never inspect snapshot or attempt rows — same coverage argument as the Room-backed close finding above.
  - `[low]` `[reject]` (intent) Checkpoint deletion on the failed-reset path is untested — DB-write-failure only; behaviour is the logged spec choice.
  - `[low]` `[reject]` (intent) No UI test changes a status between offer and accept — carried: logged reject.
  - `[false]` `[reject]` (intent) Rewriting `epic-12-context.md` violates "never edit planning artifacts" — the file is a compiled cache under implementation-artifacts written by the workflow's own epic-context step, not a planning artifact.

## Design Notes

- Checkpoint deletion on reset is required by "a cold start restores nothing": `restoreLatest()` falls back to `resumeCheckpoint()`, which would reopen a Lesson the reset locked. AD-10 allows deletes in the Learner's scoped progress reset.
- Ending clears *all* snapshot rows: `latest()` picks the newest row of any kind, so leaving an older LESSON row would resurrect a session the practice had replaced (and its abandoned attempt id).
- Ending before appending `ProgressReset` keeps the ABANDONED attempt row in the old epoch.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply spotlessCheck build` -- expected: BUILD SUCCESSFUL, all JVM/Robolectric tests and Kover green.

## Auto Run Result

**Summary:** Closing any practice (LL, Stage, skip-check; close, back, next-Stage, pass) now ends the session through a new `SessionEvent.SessionEnded` (IDLE, open attempt closed as ABANDONED) and deletes every snapshot row. *Reset progress* ends the live session first, always clears snapshot rows, then appends `ProgressReset` and clears the Checkpoint, so Continue is empty and a cold start restores nothing. `LessonScreen` no longer infers practice from the host kind. Algorithm Statuses are a sealed `AlgorithmStatusLoad` that the Room adapter retries on failure; the 2-look offer shows only on loaded statuses and re-filters to *Not started* at accept time.

**Files changed:**
- `core/application/.../session/SessionEvent.kt`, `Reduce.kt`, `TRANSITIONS.md` -- `SessionEnded` event, reducer branch, table row.
- `core/application/.../port/SessionSnapshotRepository.kt`, `CheckpointRepository.kt`, `AlgorithmStatusRepository.kt` -- `clear` members, `AlgorithmStatusLoad`.
- `app/.../data/SessionSnapshotDao.kt`, `CheckpointDao.kt`, `RoomSessionSnapshotRepository.kt`, `RoomCheckpointRepository.kt` -- delete queries, transactional clear with attempt rows.
- `app/.../data/RoomAlgorithmStatusRepository.kt` -- emits `Failed` and retries with backoff instead of completing with an empty map.
- `app/.../session/SessionHost.kt` -- `endSession`, `clearSnapshots`, reset ordering and checkpoint clear, `algorithmStatusLoad`.
- `app/.../ui/lesson/LessonScreen.kt` -- kind taken from the caller; restart on kind mismatch.
- `app/.../ui/shell/AppShell.kt` -- end session on every practice exit; `algorithmsToOffer` gate; Library keeps last loaded map.
- Tests: new `SessionEndedTest`, `SessionEndingTest`, `AlgorithmOfferTest`; extended Room, host, LessonScreen, AppShell, skip-check and transitions tests.

**Review:** 16 findings. Patches applied: 4 (medium 2, low 2) -- unconditional snapshot clear on reset, tightened ABANDONED assertion, skip-check idle assertions, Settings-driven reset test. Deferred: 1 entry (durable cleanup under write failure, medium; 3 findings). Rejected: 9 (offer write race x2 -- no reachable path; failed reset ends session -- spec choice; Library first-load shows Not started -- pre-existing loading state, stream retries; playback test construction; delayed close race -- false; simulated cold restore; UI accept tap; port surface -- spec mechanism).

**Follow-up review recommended:** true -- two medium entries were patched (patched counts: high 0, medium 2, low 2). Unverified risk: the `clearSnapshots` refactor and the extra reset-time clear (`SessionHost.resetProgress`) were not re-reviewed; check its interaction with `pendingAttempts` and the writer ordering.

**Verification:** `./gradlew spotlessApply spotlessCheck build` -- BUILD SUCCESSFUL after the implementation (Codex run: 1,538 tests) and again after the review patches (3m 40s).

**Residual risks:** Room write failures during cleanup are reported but not retried (deferred). The reset sheet copy ("in-progress work ends", EXPERIENCE.md) was not added; it is not in this story's ACs.

### Follow-up review pass (2026-11-10)

**Summary:** Fresh four-layer review of the full story diff. Three small patches: the Checkpoint delete is queued before the `ProgressReset` write, a system-Back practice test was added, and the recompiled Epic 12 context regained its measurable release criteria.

**Files changed this pass:**
- `app/.../session/SessionHost.kt` -- `resetProgress()` queues `clearCheckpoint()` before the reset write.
- `app/src/test/.../ui/shell/AppShellTest.kt` -- `practiceToCourse(systemBack = true)` case.
- `_bmad-output/implementation-artifacts/epic-12-context.md` -- restored contrast ratios and solver reporting metrics.

**Review:** 21 findings (high 0, medium 3, low 15, false 3). Patches applied: 3 (low 3). Deferred: none new (3 medium findings carried onto the existing durable-cleanup deferral). Rejected: 15 -- checkpoint fallback after close (false, designed Lesson resume); reset copy (intent forbids new strings); ignored reset result (pre-existing, needs forbidden copy); Library first-load, offer lag x3, offer UI accept x2 (carried); Room-backed close test and shell row inspection (each link tested); direct-to-Course from PRACTICE completion (kind restart tested); new host-kind read (false); failed-reset checkpoint test (spec choice, write-failure only); context rewrite as planning edit (false).

**Follow-up review recommended:** false -- follow-up pass patched no high (patched counts: high 0, medium 0, low 3).

**Verification:** `./gradlew spotlessApply spotlessCheck build` -- BUILD SUCCESSFUL in 3m 50s; AppShellTest 42 tests, 0 failures (new system-Back test included).

**Residual risks:** unchanged -- Room write failures during cleanup are reported but not retried (deferred).

Story 12.13 resolves deferrals D1/D2: ordinary close saves an ended snapshot without deleting rows, with writer and background retries until saved or superseded.
