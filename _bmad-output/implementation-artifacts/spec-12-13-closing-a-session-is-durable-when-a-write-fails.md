---
title: 'Closing a session is durable when a write fails'
type: 'bugfix'
created: '2026-11-10'
baseline_revision: 'd952d39fcda007e48ef98a0a7a56394efef456c4'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-10-session-end-deferrals.md'
  - '{project-root}/core/application/src/main/kotlin/dev/cubentia/application/session/TRANSITIONS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The background retry wiring in MainActivity.onStop has no Activity-lifecycle test; only the host-level retryPendingEnd() barrier is tested.
    evidence: |-
      DurableSessionEndTest proves retryPendingEnd() alone persists a pending end (observed outside the writer, and a no-op mutation of retryPendingEnd fails it). Removing the one-line onStop call would still pass every JVM test. The JVM test suite has no Hilt/ActivityScenario infrastructure for MainActivity, so a lifecycle test needs new test infrastructure.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/MainActivity.kt:39
    severity: low
---

<intent-contract>

## Intent

**Problem:** Ending a session (`SessionEnded`: close or Back) deletes every snapshot row (`repository.clear`), which breaks AD-10 ("the only deletes are the Learner's scoped resets"); and when that delete fails it is never retried, so a later cold start can restore a practice the Learner closed (Story 12.11 deferrals D1/D2). Separately, `AppShellLibraryTest > last two look Lesson milestone practices a fresh LL scramble` is flaky in CI.

**Approach:** SCP Option A. Ending a session writes an *ended* snapshot for that session (kind, sessionId, revision after the end) through the existing revision-conditional `save`, with any pending attempt rows; restore treats an *ended* latest row as no snapshot. A failed end write stays pending in the host and is retried before every operation on the shared `SerialWriter` and when the app goes to the background, and is dropped once any later snapshot save succeeds. Fix the flaky test's race.

## Boundaries & Constraints

**Always:**
- Ordinary end deletes nothing; `SessionSnapshotDao.clear` is called only by *Reset progress*.
- *Reset progress* (`SessionHost.resetProgress`) keeps its 12.11 behavior: snapshot delete + Checkpoint delete + `ProgressReset` in one writer step, reset written only if both deletes succeed, returns false otherwise. A successful reset also drops any pending end write (its attempt rows go into the reset's delete batch, as `pendingAttempts` already do).
- The *ended* marker lives in the payload JSON (no Room schema change). Payload `format` becomes 2; the decoder accepts format 1 (no `ended` field → not ended) and format 2.
- Pending end state and `pendingAttempts` are touched only inside writer operations (one at a time).
- A pending end write is dropped (never written) once a later snapshot save returns `Saved` or `Stale`, so it can never hide or replace a newer session's row.
- Checkpoint resume on cold start is unchanged when the latest snapshot is *ended*.
- Domain failures stay sealed results; no new dependency; JVM tests (Robolectric where Room is needed).

**Never:**
- No new SessionEvent, no reducer change, no change to which kinds resume on cold start.
- Do not add the end-write retry to the reducer or to screens; do not add a scoped delete query.
- Do not edit planning artifacts, `ARCHITECTURE-SPINE.md`, `_bmad/`, or `.claude/skills/`.
- Do not "fix" the flaky test by raising timeouts alone or by `@Ignore`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normal end | Live LESSON / PRACTICE / TWO_LOOK, `endSession()` | One `save` of an *ended* snapshot (same kind + sessionId, revision = post-end revision, attemptOpen=false, attemptId=null) with pending + ABANDONED attempt rows; snapshot rows still present; `latest()` → `None` | — |
| Cold start after end | Latest row *ended*; Checkpoint row exists | No `SessionRestored`; Checkpoint resume runs as before | — |
| End write fails | `save` returns Failed/throws | `SnapshotSaveFailed` dispatched once; end write + its attempt rows stay pending | Retried before next writer op (any caller, incl. `PreferenceStore`) and on `retryPendingEnd()`; cold start after successful retry restores nothing |
| Superseded | Pending end, newer session's snapshot save succeeds first (retry failed or not yet run) | Pending end dropped; newer row stays latest and restores | — |
| Old payload | Format-1 JSON without `ended` | Decodes to the same `SessionSnapshot` as before (`ended=false`) | — |
| Reset | `resetProgress()` with or without pending end | Unchanged 12.11 behavior; pending end dropped on success | Failure → false, dialog retry (unchanged) |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- `dispatch` (~:547-563) builds ABANDONED rows and calls `clearSnapshots(rows)` on `SessionEnded`; replace with an end-write. `before` is the live state, `previousSessionId` its id, `transition.state.revision` the post-end revision. Snapshot save op (~:603-624) is the pattern to copy (batch = `pendingAttempts + attempts`, Failed → keep pending + `SnapshotSaveFailed`). `clearSnapshotsNow` (~:336) stays for `resetProgress` (~:381) only. `restoreLatest` (~:243) should also ignore `snapshot.ended`. Host without `writer` (tests) runs the end write directly, no retry.
- `app/src/main/kotlin/dev/cubentia/app/data/SerialWriter.kt` -- shared app-lifetime FIFO (also used by `PreferenceStore`); add a hook API (e.g. `beforeEach(hook: suspend () -> Unit)`) run inside the writer before each queued operation, exceptions swallowed except consumer cancellation. Host registers it once in `init` when `writer != null`.
- `app/src/main/kotlin/dev/cubentia/app/data/RoomSessionSnapshotRepository.kt` -- `save` already revision-conditional (Stale only for same sessionId with higher revision); `latest()` must return `SnapshotLoad.None` when the decoded latest row is *ended*. `clear` unchanged (reset only).
- `core/application/src/main/kotlin/dev/cubentia/application/port/SessionSnapshotRepository.kt` -- document that `latest()` reports an *ended* row as `None` and `clear` is reset-only.
- `core/application/src/main/kotlin/dev/cubentia/application/session/SessionSnapshot.kt` -- add `val ended: Boolean = false` (KDoc: written by an ordinary session end; restore treats it as no snapshot).
- `app/src/main/kotlin/dev/cubentia/app/data/SnapshotPayload.kt` -- add `ended: Boolean = false`, encode `format = 2`, `snapshot()` accepts format 1 or 2.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- override `onStop` to call `sessionHost.retryPendingEnd()` (non-suspending; enqueues on the writer).
- `app/src/test/kotlin/dev/cubentia/app/data/SessionEndingTest.kt` -- Room-backed (Robolectric) host tests incl. a failing-delete wrapper (~:148-170); pattern for new failing-save tests. Existing reset tests must keep passing unchanged.
- `app/src/test/kotlin/dev/cubentia/app/data/RoomSessionSnapshotRepositoryTest.kt`, `app/src/test/kotlin/dev/cubentia/app/session/SessionHostTest.kt`, `SessionHostPersistenceTest.kt`, `app/src/androidTest/.../SessionSnapshotRestartTest.kt`, `AppShellSolveTest.kt` (~:303 fake `clear`) -- may assert delete-on-end; update to the *ended*-row behavior.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt` (~:341-422) -- flaky test. CI failure on PR #101 (run 42914725894 attempt 1) was an `AssertionError` at the line `onNodeWithTag("learn:stage:${last.stageId.value}").performScrollTo()` (node missing). Likely cause: `AppShell` launches `host.restore(bundle)` (AppShell.kt:210), whose `reloadProgress`/`reloadAttempts` run via `withContext(confined)` on `Dispatchers.Default`, which Compose idling does not track, so the 2-look rows may not exist yet. Confirm, then make it deterministic (e.g. wait for the host's progress and attempts to load before navigating).
- `core/application/src/main/kotlin/dev/cubentia/application/session/TRANSITIONS.md` -- persistence notes for `SessionEnded`.

## Tasks & Acceptance

**Execution:**
- `core/application/.../session/SessionSnapshot.kt`, `core/application/.../port/SessionSnapshotRepository.kt` -- add `ended` field; doc port contract -- marker in the model.
- `app/.../data/SnapshotPayload.kt` -- `ended`, format 2, format-1 decoder -- backward compatible payloads.
- `app/.../data/RoomSessionSnapshotRepository.kt` -- `latest()` maps *ended* to `None` -- restore sees no snapshot.
- `app/.../data/SerialWriter.kt` -- before-each hook -- retry on the writer's next operation.
- `app/.../session/SessionHost.kt` -- end-write via `save` with pending attempts; pending-end slot; retry hook; `fun retryPendingEnd()`; drop pending on later `Saved`/`Stale` and on successful reset; `restoreLatest` ignores `ended` -- D1/D2.
- `app/.../MainActivity.kt` -- `onStop` → `sessionHost.retryPendingEnd()` -- background retry.
- `core/application/.../session/TRANSITIONS.md` -- note: an ordinary end writes an *ended* snapshot (no delete), retried until saved or superseded; reset deletes -- normative doc.
- Tests (`app/src/test/.../data/`, `SerialWriterTest` if present or new, `core/application` `SessionSnapshotTest`) -- cover every I/O matrix row: ended row written and no row deleted (DAO row count), cold start restores nothing and Checkpoint still resumes, failed end write retried on next writer op and on `retryPendingEnd()` then cold start restores nothing, superseded pending end never written after newer save, literal format-1 payload decodes identically, ended round-trip; update existing delete-on-end assertions.
- `app/src/test/.../ui/shell/AppShellLibraryTest.kt` -- remove the race; run the test ≥20 consecutive times locally and record the result in this spec's Auto Run Result.
- `_bmad-output/implementation-artifacts/spec-12-11-sessions-end-when-practice-closes-or-progress-resets.md` -- append a note under its existing deferred entry's evidence or Auto Run Result that it is resolved by Story 12.13 (do not delete the entry).

**Acceptance Criteria:**
- Given a live Lesson, practice or 2-look session, when it ends normally, then the host writes an *ended* snapshot through the revision-conditional `save` with pending attempt rows, and deletes no snapshot row.
- Given an *ended* latest row, when the app cold starts, then no session is restored from snapshots and Checkpoint resume behaves as before.
- Given *Reset progress*, when it runs, then all existing `SessionEndingTest` reset tests pass unchanged.
- Given the flaky test, when the story closes, then its cause is fixed and it passes ≥20 consecutive local runs.
- Given the full build, when `./gradlew spotlessCheck build` runs, then it is green.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 16 findings — high 0, medium 5, low 6, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) Retry test failed every save, so no resumable row ever existed and the cold-start check passed trivially — fixed: only *ended* saves fail; the test now asserts the live PRACTICE row is persisted and still latest after the failed end, before the retry.
  - `[low]` `[patch]` (blind) No same-kind supersession test (Practice A → Practice B) — added `a pending end never replaces a newer session of the same kind`.
  - `[low]` `[reject]` (blind) `Stale` treated as success is untested — pre-existing semantics (the old save path also cleared pending rows on any non-Failed result); an ended write is Stale only if its own session wrote a higher revision, which the host never does.
  - `[false]` `[reject]` (blind) Reset failures could let pending retries recreate snapshots after the eventual reset — a successful reset deletes all rows and clears `pendingEnd` in the same writer step; a retry before it can only write an *ended* row, which restores nothing and is then deleted.
  - `[medium]` `[patch]` (blind) `resetProgress` cancellation between the suppressed end write and enqueueing the reset op leaves the live snapshot with no ended row or pending end — fixed: the end step runs in `withContext(confined + NonCancellable)`, so the reset op is always enqueued once the session ends (`writer.read` enqueues before its first suspension).
  - `[medium]` `[patch]` (blind) `MainActivity.onStop` background retry is not exercised — grouped with the verification-gap row below; host-level fix applied, Activity-lifecycle test deferred (needs Hilt/ActivityScenario infra).
  - `[low]` `[patch]` (blind) TWO_LOOK coverage used a stage Lesson with an overridden kind — now starts a real `bundle.twoLookStages` Lesson and asserts the saved row's kind.
  - `[false]` `[reject]` (blind) Spec records no observed verification results — recorded under Auto Run Result at finalize (build green; 20/20 runs twice).
  - `[medium]` `[patch]` (verification-gap) Explicit retry test was masked by `writer.flush()`, whose hook also retries — fixed: after `retryPendingEnd()` the test polls the repository outside the writer until the ended row lands; mutating `retryPendingEnd()` to a no-op now fails the test (checked).
  - `[medium]` `[patch]` (intent) Background retry lives at `Activity.onStop` and no test drives it — same root cause as the row above; host barrier now proven, lifecycle test deferred.
  - `[false]` `[reject]` (intent) "Retried until saved" is one attempt per trigger, no loop — the AC names exactly the two triggers (next writer op, backgrounding); the SCP records process death before any retry as accepted residual risk.
  - `[low]` `[reject]` (intent) Cold start is simulated by a second host on the same in-memory DB — the established pattern from `SessionEndingTest`; the androidTest `SessionSnapshotRestartTest` is unchanged and still exercises a real restart.
  - `[low]` `[patch]` (intent) Only the "retry fails, newer save wins" ordering is tested — the "retry succeeds first" ordering is benign (the newer save writes after it with a higher sequence); same-kind supersession test added (see second row).
  - `[low]` `[reject]` (intent) Format-1 payload is checked at the decoder, not through `restore()` — the decoder is the only changed surface; `latest()`/restore read through it unchanged.
  - `[false]` `[reject]` (intent) Failed reset leaves the session ended with no ended row — identical to Story 12.11's failure mode (its own end-time delete failed the same way), which AC6 requires unchanged; the dialog keeps its retry and a successful retry deletes the row.
  - `[false]` `[reject]` (intent) Flake cause unconfirmed and 20 runs unrecorded — CI run 42914725894 attempt 1 failed with `AssertionError` at the `learn:stage:` node lookup (AppShellLibraryTest.kt:406 at 7dc8a30), before the host's async restore had loaded progress; the fix waits for that load. 20/20 recorded below.

### 2026-11-10 — Review pass (follow-up)
- verdicts: 18 findings — high 0, medium 5, low 7, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) `resetProgress` enqueues the reset after leaving the `NonCancellable` block; `withContext` returns to the caller with a prompt-cancellation check, so a cancelled caller can throw before the enqueue, leaving the live snapshot with no ended row and no pending end — fixed: the end step and the reset `writer.read` (or direct run) now share one `withContext(confined + NonCancellable)` block; targeted reset/session tests pass.
  - `[false]` `[reject]` (blind) `failEnd` in `DurableSessionEndTest` is read on the writer thread without synchronization — every flip happens before the next `enqueue`/`trySend`, and the channel hand-off establishes happens-before for the consumer's read.
  - `[medium]` `[defer]` (blind) `MainActivity.onStop` lifecycle wiring is untested — carried: same row as the earlier pass; already in the `deferred` list (needs Hilt/ActivityScenario infrastructure).
  - `[low]` `[reject]` (blind) No test for "retry succeeds immediately before the newer save" — carried: the ordering is benign (the newer save writes after it with a higher sequence).
  - `[low]` `[reject]` (blind) `Stale` clearing `pendingEnd` is untested — carried: pre-existing semantics; an ended write is Stale only if its own session wrote a higher revision.
  - `[low]` `[reject]` (blind) No test of an end failing while attempt rows are already pending from an earlier failed live save — code checked: the failed end stores `pendingAttempts + rows` and the retry re-sends `pendingAttempts`, so each row is saved once; adding the fixture is more than a direct correction for an unlikely path.
  - `[low]` `[reject]` (blind) No test of an adapter-thrown `CancellationException` during an end write — the handler keeps `pendingEnd`/`pendingAttempts` and rethrows, and `SerialWriter.enqueue` swallows an operation's cancellation unless the consumer itself is cancelled; an unlikely path, not worth a new fixture.
  - `[low]` `[reject]` (blind) Pending-end reset test covers only the `useCase == null` branch, not a failed-then-successful real reset — carried in substance: existing `SessionEndingTest` reset tests use the real `ResetProgress` incl. fail-then-retry; a successful reset deletes all rows in the same writer step.
  - `[low]` `[reject]` (blind) No cold-start cases without a Checkpoint — `latest()` maps an ended row to `None` independent of Checkpoint presence and `restoreLatest` also checks `ended`; the Checkpoint is orthogonal.
  - `[medium]` `[patch]` (edge-case) Caller cancellation during `resetProgress` end step skips the reset enqueue — same root cause as the first row; fixed by the same change.
  - `[medium]` `[defer]` (verification-gap) Background retry has no Activity-lifecycle verification — carried: same deferral as above.
  - `[false]` `[reject]` (intent) Failed reset leaves the session ended with no ended row — carried: identical to Story 12.11's failure mode, which the intent requires unchanged; the dialog retry deletes the row.
  - `[false]` `[reject]` (intent) Deletes succeed but the reset use case fails, keeping `pendingEnd`, so a later ended row lands in a cleared table — an ended row restores nothing (`latest()` → `None`) and the next successful reset deletes it; no bad outcome.
  - `[medium]` `[defer]` (intent) Backgrounding retry tested only at the host barrier — carried: same deferral.
  - `[low]` `[reject]` (intent) `PreferenceStore` trigger is covered structurally, not by a test — `PreferenceStore` uses the same singleton `SerialWriter`, whose hook runs before every queued operation, which `SerialWriterTest` covers.
  - `[false]` `[reject]` (intent) "Superseded, retry not yet run" cannot occur — not a defect: the hook always retries before the newer save, so that matrix state reduces to the tested "retry failed" case.
  - `[false]` `[reject]` (intent) Flake root cause not evidenced in the diff — carried: CI run 42914725894 attempt 1 failed at the `learn:stage:` lookup before the async restore loaded progress.
  - `[false]` `[reject]` (intent) Appending a note to the 12.11 spec may violate "do not edit planning artifacts" — the 12.11 spec lives under `implementation-artifacts`, and this spec's tasks require that note.

## Design Notes

End write, in `dispatch` on `SessionEnded` (sketch):

```kotlin
val ended = SessionSnapshot.of(before, previousSessionId, contentRevision)
    ?.copy(revision = transition.state.revision, attemptOpen = false, attemptId = null, ended = true)
// enqueue: batch = pendingAttempts + rows; save(ended, batch); Failed → pendingEnd = ended, pendingAttempts = batch
```

The post-end revision is `before.revision + 1`, so the end write is never `Stale` against its own session's last row. The retry hook calls the same save with `pendingEnd` and `pendingAttempts`, silently (only the first failure dispatches `SnapshotSaveFailed`). Residual risk accepted by the SCP: end-write failure followed by process death before any retry can restore the closed practice once.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `for i in $(seq 1 20); do ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.shell.AppShellLibraryTest' --rerun -q || break; done` -- expected: 20 passes, no break

## Auto Run Result

**Status:** done

**Summary:** An ordinary session end (`SessionEnded`) now saves an *ended* snapshot for that session through the revision-conditional `save`, with any pending and ABANDONED attempt rows, and deletes nothing (AD-10). Restore treats an *ended* latest row as no snapshot, and Checkpoint resume is unchanged. A failed end write stays pending in `SessionHost`. It is retried before every operation on the shared `SerialWriter` (a new `beforeEach` hook) and on `MainActivity.onStop` (`retryPendingEnd()`). It is dropped once any later snapshot save succeeds or a reset succeeds. The payload is now format 2 with `ended`, and format 1 still decodes. *Reset progress* keeps its 12.11 behavior. The `AppShellLibraryTest` flake (a race with the host's async restore) is fixed by waiting for progress and attempts to load.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- end-write via the shared `saveSnapshotNow`; `pendingEnd` slot, retry hook, `retryPendingEnd()`; reset suppresses the end write and runs its end step NonCancellable; restore ignores `ended`.
- `app/src/main/kotlin/dev/cubentia/app/data/SerialWriter.kt` -- `beforeEach` hooks run before every queued operation; failures are swallowed.
- `app/src/main/kotlin/dev/cubentia/app/data/SnapshotPayload.kt` -- `ended` field, format 2, accepts format 1.
- `app/src/main/kotlin/dev/cubentia/app/data/RoomSessionSnapshotRepository.kt` -- `latest()` maps an *ended* row to `None`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- `onStop` triggers the background retry.
- `core/application/.../session/SessionSnapshot.kt`, `.../port/SessionSnapshotRepository.kt` -- `ended` marker and the port contract docs.
- `core/application/.../session/TRANSITIONS.md` -- normative note on the ended snapshot and its retry.
- Tests: `DurableSessionEndTest.kt` (new, 5 tests), `RoomSessionSnapshotRepositoryTest.kt` (literal format-1 decode, ended round-trip), `SerialWriterTest.kt` (hook), `SessionHostTest.kt` (an end saves and never deletes), `SessionSnapshotTest.kt`, `AppShellLibraryTest.kt` (flake fix).
- `spec-12-11-...md` -- notes that its durable-cleanup deferral is resolved by 12.13.

**Review findings:** 16 findings in total (see the triage log).
- **Patched:** 8 rows in 6 entries.
  - 3 medium entries: the retry-test seeding, the reset cancellation window, and the masked background-retry test (blind, verification-gap and intent rows).
  - 3 low entries: the same-kind supersession test (blind and intent rows), and the real TWO_LOOK Lesson in the test.
- **Deferred:** 1 item, the `MainActivity.onStop` lifecycle test, which needs Hilt/ActivityScenario test infrastructure.
- **Rejected:** 8 findings, each with its reason in the triage log:
  - Stale untested: pre-existing semantics.
  - Reset recreate: false.
  - Unrecorded results: recorded below.
  - One attempt per trigger: the AC names the triggers, and the SCP accepts the residual risk.
  - Simulated cold start: the established pattern.
  - Format-1 decoded only at the decoder: the decoder is the only changed surface.
  - Failed reset: the 12.11 behavior is unchanged, as AC6 requires.
  - Flake cause unconfirmed: confirmed from the CI log.

**Follow-up review recommendation:** true. This pass patched 3 medium entries. The specific unverified risk is the reset path: `resetProgress` now suppresses the end write through the `resettingProgress` flag and uses a NonCancellable end step. That interplay with concurrent `SessionEnded` dispatches during a reset has only been checked by the existing `SessionEndingTest` reset tests and the new successful-reset test.

**Verification:**
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (4m 2s) after the review patches. An earlier attempt failed transiently, because `spotlessKotlin` could not read a benchmark `.class` file while the build directory was being written. Rerunning on its own passed.
- The 20-run loop on `AppShellLibraryTest` -- 20/20 consecutive passes, run twice: once before and once after the review patches (17 tests per run).
- The targeted `DurableSessionEndTest` (5/5) and `SessionEndingTest` (3/3) passed.
- A mutation check made `retryPendingEnd()` a no-op, and the explicit-retry test failed as expected; the code was then restored.
- Flake cause: CI run 42914725894 attempt 1 failed at the `learn:stage:` lookup (AppShellLibraryTest.kt:406 at 7dc8a30) because `AppShell`'s `host.restore` loads progress on `Dispatchers.Default`, which Compose idling does not track.

**Residual risks:**
- As the SCP records, if an end write fails and the process dies before any retry, a closed practice can restore once.
- The `onStop` wiring is untested at the Activity level (deferred).

### Follow-up review pass (2026-11-10)

**Summary:** A fresh four-layer review (blind, edge-case and verification-gap on Codex, intent alignment on Claude) of the full diff since `d952d39fcda007e48ef98a0a7a56394efef456c4`. One real defect was found and patched: `resetProgress` ended the session in one `NonCancellable` block and enqueued the reset after returning from it, so a caller cancelled at that return could skip the reset and leave the live snapshot with neither an ended row nor a pending end.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- `resetProgress` now ends the session and enqueues and awaits the reset operation in one `withContext(confined + NonCancellable)` block.

**Review findings:** 18 findings (high 0, medium 5, low 7, false 6); see the triage log.
- **Patched:** 1 medium entry (2 rows: blind and edge-case), the reset cancellation window.
- **Deferred:** none new; the 3 `MainActivity.onStop` lifecycle-test rows were carried onto the existing deferral.
- **Rejected:** 14 findings, each with its reason in the triage log: unsynchronized `failEnd` (false: channel hand-off orders it); retry-succeeds ordering, Stale clearing, pre-pending attempts, adapter cancellation, reset-branch coverage, no-Checkpoint cold start, `PreferenceStore` trigger (low, unlikely, more than a direct correction or carried); failed reset, ended row after partial reset, superseded-not-yet-run, flake cause, 12.11 spec note (false).

**Follow-up review recommendation:** false. This follow-up pass patched no `high` entry (patched: high 0, medium 1, low 0), so the work has converged.

**Verification:**
- Targeted tests `SessionEndingTest`, `DurableSessionEndTest`, `SessionHostTest`, `SessionHostProgressTest`, `SessionHostAlgorithmStatusTest`, `SettingsScreenTest` -- pass.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL in 3m 46s.
- The 20-run loop on `AppShellLibraryTest` -- 20/20 consecutive passes.

**Residual risks:** unchanged from the first pass (process death before any retry after a failed end write; `onStop` wiring untested at the Activity level). No regression test drives a cancellation at the reset boundary; the fix is structural.

