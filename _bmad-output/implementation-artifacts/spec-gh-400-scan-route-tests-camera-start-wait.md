---
title: 'GH #400: scan route tests wait for the camera start with a full sync on every poll'
type: 'bugfix'
created: '2026-11-26'
status: 'done'
baseline_revision: '79c948e58d6e6662f8dd302a411c5be3f8759cd7'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred:
  - summary: >-
      No committed test pins the per-poll Compose sync of awaitScanCamera, and nothing stops a new route test from
      waiting for the camera with a bare waitUntil again.
    evidence: |-
      Verification-gap review, 2026-11-26: the seven classes pass ordinary runs with or without the fix (the old waits
      failed only under the uncommitted schedule fuzz), so CI stays green if waitForIdle is dropped from the helper or
      a new wait is written the old way. A deterministic test is possible with public APIs only: an ordered pair of
      tests where an outer JUnit rule writes a global snapshot state after the first test's teardown (this stalls
      AndroidUiDispatcher.Main for the next test, reproduced every time in the investigation), and the second test
      posts a state write to the main looper after its content settled and asserts awaitScanCamera returns. A
      source-scan guard like ComposeTestTimingGuardTest, or a pitfall line in AGENTS.md, would cover new bare waits.
      Left out here because the issue sets the proof as the recorded consecutive runs and the build was told to do
      nothing more.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/ScanCameraWait.kt:19
    severity: medium
  - summary: >-
      ScanRouteTest.coachMarkStaysPendingUntilTappedAndIsAbsentInTheNextActivity opens the review without waiting for
      the coach-mark read, so it fails when that read is slow.
    evidence: |-
      With a random 0–60 ms delay on the coach-mark read (ScanCoachMarkRecord.pending on Dispatchers.IO,
      GuidedScanActivity.kt:181) the test failed once in 300 test runs at ScanRouteTest.kt:449 with "could not find
      review:coach": captureFaces reached the review before coachMarkPending was set. Not a camera-start wait and
      not changed by this fix; never seen in CI so far.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt:449
    severity: low
  - summary: >-
      Other waits in the scan route tests poll with waitUntil without a looper pump or a Compose sync and may hang
      the same way when a background hand-off lands late.
    evidence: |-
      Blind-hunter and intent-alignment reviews, 2026-11-26, not confirmed to hang: CaseRecognitionRouteTest.kt:125
      (activity.isFinishing, no pump), ContinuousQuickScanRouteTest.kt:731 (model.phase is Review, no pump), about 14
      host.state.value waits in QuickCheckRouteTest (lines 96, 148, 192, 219, 250 and later, no pump), ScanRouteTest.kt:500
      and :538 (vm.phase, pump only), ContinuousQuickScanRouteTest.kt:484 (calibrationLoaded and the rationale node).
      What would settle it: the schedule fuzz of this investigation (a random delay inside the background hop each
      wait depends on) applied to the analyzer-to-main hand-offs behind these waits.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/CaseRecognitionRouteTest.kt:125
    severity: medium (unverified)
  - summary: >-
      Issue #400 gained a second "Done when" item after this build started: the model.busy assertion flake in
      ContinuousQuickScanRouteTest is not investigated or fixed here.
    evidence: |-
      Issue comment by the product owner, 2026-11-26T18:14Z: run 45863188726 attempt 2 failed in
      anUnavailableFrameCannotInterruptAnExportAndBackFromEarlyReviewReturnsToTheCard with AssertionError at
      ContinuousQuickScanRouteTest.kt:645, the assertTrue(model.busy) right after model.reviewAndCorrect() inside
      feedUnavailable. It is not a camera-start wait. This build received the issue as it stood before that comment
      and was told to do exactly that and nothing more, so the race is neither identified nor removed; the test did
      pass in every one of the recorded runs here, which proves nothing about a rare race.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt:645
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `:app:testDebugUnitTest` fails now and then in CI with `ComposeTimeoutException`: after `Robolectric.buildActivity(GuidedScanActivity…).setup()` a scan route test waits 10 s for the fake `ScanFrameSource` to be started and it never is. The wait is not slow, it is stuck: the write that lets the camera start is never shown to Compose (root cause in Design Notes).

**Approach:** Give the route tests one shared wait for the camera start that, on every poll, runs the paused main looper and a full Compose sync (`waitForIdle`, which applies pending snapshot writes), and use it at every camera-start wait in the seven scan route test classes. Tests only; the 10 s timeout stays.

## Boundaries & Constraints

**Always:**
- Every wait for the camera start (or restart) in `ScanRouteTest`, `SolveFromScanRouteTest`, `ContinuousQuickScanRouteTest`, `QuickCheckRouteTest`, `PlacementFromScanRouteTest`, `GuidedScanCloseRouteTest` and `CaseRecognitionRouteTest` goes through the shared helper; each keeps its own condition.
- Each class still runs the same tests with the same assertions (100 tests across the seven classes).
- Record in this spec the command and the count of the consecutive passing runs, and the schedule-fuzz result before and after.
- Leave no unused import in an edited file; format with `./gradlew spotlessApply`.

**Never:**
- No change under `app/src/main`, `app/src/camera`, `app/src/debug` or `core/`: the cause is in how the tests wait, so production code stays as it is. `ScanFrameSource` and the continuous scan code are not touched (Stories 10.34 and 10.35 edit them next).
- No raised timeout, no `Thread.sleep`, no test deleted, ignored or weakened.
- No change to waits that are not for the camera start, no new test rule or runner, no reflection on Compose internals in committed code.

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- READ ONLY. Start path: `onCreate` launches on `lifecycleScope` the calibration load (line 162), the controller build on `analyzerExecutor` (170) and `scan.calibrationLoaded = true` (174); `lessonCatalog.load()` (178) gates the content (`strings == null` returns early, 302); `ready = previewReady && scan.calibrationLoaded` (334) keys `LaunchedEffect(phase::class, ready)` (337), which calls `startCamera()` → `frameSource.start` (620). `previewReady` is set in the `AndroidView` factory (427).
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- waits at 160 and 310 (`source.starts > 0`, no looper pump), 363 (`idle()`, `source.running`); private `awaitCameraStart(phase)` at 639 already syncs on every poll (commit c66d5b92) and is the pattern to share. Holds the shared `FakeFrames` and `labs`.
- `app/src/testDebug/kotlin/dev/cubentia/app/SolveFromScanRouteTest.kt` -- `withScan` wait at 363 (`idleFor(16 ms)`, `camera.starts > 0`).
- `app/src/testDebug/kotlin/dev/cubentia/app/GuidedScanCloseRouteTest.kt` -- `withScan` wait at 409 (`idleFor(16 ms)`, `camera.starts > starts`); private `awaitCamera()` at 422 already syncs on every poll.
- `app/src/testDebug/kotlin/dev/cubentia/app/PlacementFromScanRouteTest.kt` -- wait at 144 (`idleFor(16 ms)`, `camera.starts > 0`).
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickCheckRouteTest.kt` -- waits at 132, 308, 378 (`idle()`, `source.starts > 0`) and 171 (`source.starts > priorStarts`).
- `app/src/testDebug/kotlin/dev/cubentia/app/CaseRecognitionRouteTest.kt` -- wait at 108 (`camera.corner && camera.receiver != null`, no looper pump).
- `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` -- `waitForCamera(continuous)` at 1520 (`idle()`, `camera.receiver != null && camera.continuous == continuous`), used by about 20 tests.
- Compose UI test 1.12.1 (read only, library): `AndroidComposeUiTestEnvironment.waitUntil` only steps the test clock and sleeps 10 ms; `ComposeIdlingResource.isIdleNow` (reached by `waitForIdle`) calls `Snapshot.sendApplyNotifications()` and `RobolectricIdlingStrategy.runUntilIdle` drains the main looper.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/ScanCameraWait.kt` -- create `internal fun ComposeTestRule.awaitScanCamera(running: () -> Boolean)`: `waitUntil(10_000)` whose poll runs `shadowOf(Looper.getMainLooper()).idle()`, then `waitForIdle()`, then returns `running()`. Its KDoc states the root cause in three or four lines and names GH #400 -- one place for the wait and the reason.
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- use `rule.awaitScanCamera` at 160, 310 and 363; make `awaitCameraStart(phase)` delegate to it and shorten its comment -- no private copy of the technique.
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/SolveFromScanRouteTest.kt` -- use it in `withScan` (363).
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/GuidedScanCloseRouteTest.kt` -- use it in `withScan` (409); make `awaitCamera()` delegate to it.
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/PlacementFromScanRouteTest.kt` -- use it at 144.
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/QuickCheckRouteTest.kt` -- use it at 132, 171, 308 and 378.
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/CaseRecognitionRouteTest.kt` -- use it at 108.
- [x] `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` -- use it inside `waitForCamera` (1520).
- [x] `_bmad-output/implementation-artifacts/spec-gh-400-scan-route-tests-camera-start-wait.md` -- add a `**Results:**` list under `## Verification` with the fuzz result after the fix, the count of consecutive passing runs with the command, and the build result -- the PR cites them.

**Acceptance Criteria:**
- Given the schedule fuzz described in Design Notes on the two background hops the camera start depends on (the catalog IO block and the `analyzerExecutor` hop, a random 0–60 ms delay each), when the seven classes run three times, then all 100 tests pass each time (before the fix 23 of 100 failed in one run).
- Given the fixed tests and no fuzz, when the seven classes run 30 times in a row with the command in Verification, then every run passes.
- Given the branch diff, when it is listed, then it touches only `app/src/testDebug` and this spec, and no `@Test` is removed, ignored or given a weaker assertion.
- Given the worktree, when `./gradlew spotlessCheck build` runs, then it passes.

## Spec Change Log

### 2026-11-26 — Fuzz criterion narrowed to the camera-start hops (found in step-03 verification, no code re-derived)
- Trigger: with the fuzz on all three hops the fixed tests failed once in 300 test runs, at `ScanRouteTest.kt:449`, a coach-mark assertion. The third hop (the coach-mark read) is not on the camera start path; the spec had wrongly called all three "start path" hops.
- Amended: the first acceptance criterion and the fuzz line under Verification now name the two hops the camera start depends on; Design Notes say which hops are on the start path, correct the "no unit test" note (the stall can be set up with public APIs, across two ordered tests) and add the note on the issue's lead. Nothing inside `<intent-contract>` changed.
- Known-bad state avoided: reading the coach-mark failure as a failure of the camera-start fix, or widening this change to a wait that is not for the camera start.
- KEEP: the shared `awaitScanCamera` helper and its use at every camera-start wait; the three-hop results stay in Results as recorded.

## Review Triage Log

### 2026-11-26 — Review pass
- verdicts: 26 findings — high 0, medium 4, low 14, false 6, maybe-false 2
- findings:
  - `[low]` `[reject]` Intent alignment 1: the 30 runs are local and filtered to the seven classes; no CI run is in the evidence — true, but the intent sets the proof as 30 consecutive runs of those classes and the issue allows them locally; the fix does not depend on the dispatcher's state, and the full suite ran in the build (1551 tests). Repeating CI runs would be new infrastructure.
  - `[false]` `[reject]` Intent alignment 2: reproduced by a schedule fuzz, not by CPU contention — the intent offers contention "for example"; the fuzz produced the same exception at the same lines as CI (`ScanRouteTest.kt:160`, `SolveFromScanRouteTest.kt:363`, `ContinuousQuickScanRouteTest.kt:1521`).
  - `[false]` `[reject]` Intent alignment 3: the hops and the stalled dispatcher remain; test dispatchers were not used — the intent names "a deterministic wait for the camera start" as an accepted fix; the wait no longer depends on the order of the hops.
  - `[low]` `[reject]` Intent alignment 4: the spec does not dismiss the issue's line-130 lead — the fix would be a spec edit; the note was added to Design Notes with the change-log entry above.
  - `[medium]` `[defer]` Intent alignment 5: nothing in the repo exercises the fix — same entry as the verification-gap finding below.
  - `[false]` `[reject]` Intent alignment 6: the root cause, command and count are in the spec, not in the diff — that is where the intent asks for them ("recorded in the spec so the PR can cite them").
  - `[false]` `[reject]` Intent alignment 7: two helpers that already synced were made to delegate and three `idleFor(16 ms)` sites now use `idle()` — all are camera-start waits in the classes the intent names; 60 consecutive runs and two full builds pass.
  - `[low]` `[defer]` Intent alignment 8: the coach-mark failure was said to be under `deferred` while the list was empty — the item is now in `deferred` (pre-existing race, not a camera-start wait).
  - `[maybe-false]` `[defer]` Intent alignment 9: waits after `setup()` that are not on the fake frame source stay as they are — same entry as blind hunter 6; would be medium if one hangs; settled by fuzzing the hand-off behind each wait.
  - `[medium]` `[defer]` Verification gap 1: the per-poll sync is pinned only by a manual fuzz; an edit that drops it leaves CI green — real. The reviewer's test needs an ordered pair of tests with their own JUnit rule; the intent sets the proof as recorded runs and says nothing more, and the spec's contract excludes a new test rule. Deferred with the test design.
  - `[low]` `[defer]` Verification gap, other: the coach-mark failure is tracked nowhere — now in `deferred`.
  - `[medium]` `[defer]` Blind hunter 1: the second flake added to issue #400 by the product owner's comment (`assertTrue(model.busy)`, `ContinuousQuickScanRouteTest.kt:645`) is not addressed — the comment is real (2026-11-26T18:14Z, after this build started); it is a pre-existing race outside the intent this build received. Deferred and raised in the run result.
  - `[low]` `[reject]` Blind hunter 2: a poll's `waitForIdle` can run past 10 s (Espresso's idle policy) and fail with `AppNotIdleException` — true only if Compose never settles; the test still fails loudly, as at every other `waitForIdle` in these classes, and the two helpers this replaces polled the same way. Bounding it means changing Espresso's global policy around the wait. Design Notes now state it.
  - `[false]` `[reject]` Blind hunter 3: three sites lost their 16 ms looper-clock advance — nothing on the start path waits on a delayed main-looper message (the reviewers found none; 60 runs pass); a future one would fail loudly at this helper.
  - `[low]` `[reject]` Blind hunter 4: timeouts stay anonymous — true of every wait in these classes; the stack already names the helper and its caller, and a state-dump parameter is new surface.
  - `[medium]` `[defer]` Blind hunter 5: nothing stops the bare wait from coming back (no guard test, no AGENTS.md pitfall) — same entry as verification gap 1; an AGENTS.md edit is an agent-context change.
  - `[maybe-false]` `[defer]` Blind hunter 6: sibling waits with the same exposure remain — not confirmed to hang; medium if true; listed in `deferred` with what would settle it.
  - `[low]` `[defer]` Blind hunter 7: the remaining fuzz failure is not tracked — now in `deferred`.
  - `[low]` `[patch]` Blind hunter 8: the explicit looper pump is redundant and the KDoc implied two required steps — the KDoc now says `waitForIdle` does both and the pump is a safeguard; code unchanged.
  - `[low]` `[reject]` Blind hunter 9: `waitForIdle()` calls next to the helper are now no-ops — harmless lines the diff did not touch; removing them is clean-up the intent ("nothing more") leaves out.
  - `[low]` `[patch]` Blind hunter 10: the KDoc described `running` more narrowly than it is used — the KDoc now says "usually (not always) on its fake frame source"; the name stays as the spec sets it, and the `10_000` literal matches the other waits.
  - `[low]` `[reject]` Blind hunter 11: the evidence is local, and `ScanRouteTest.kt:363` has no recorded failure before the fix — same answer as intent alignment 1; that site had the same pump-only form as the `QuickCheckRouteTest` waits that did fail under the fuzz.
  - `[low]` `[reject]` Edge case 1: the wait can block up to Espresso's idle policy — same as blind hunter 2.
  - `[low]` `[reject]` Edge case 2: with no Compose root each poll blocks 2 s in `waitForComposeRoots` — only once the scan activity is gone, where the wait is failing anyway; the guard adds a branch for a failure path.
  - `[false]` `[reject]` Edge case 3: removed `idleFor(16 ms)` — same as blind hunter 3.
  - `[low]` `[reject]` Edge case 4: the KDoc's root-cause paragraph is longer than the "three or four lines" the spec asked for — nothing functional; the extra lines carry the issue reference and the safeguard note.

## Design Notes

**Root cause (reproduced 2026-11-26 on main 79c948e5).** `frameSource.start` (`GuidedScanActivity.kt:620`) runs from `LaunchedEffect(phase::class, ready)`, so it needs a recomposition after `scan.calibrationLoaded` becomes true. That write is made by a `lifecycleScope` coroutine on the main looper, after two real-thread hops (the Room calibration load, then the controller built on `analyzerExecutor`). Two things then keep Compose from ever seeing it:

1. *The write is not applied.* It is a global snapshot write made outside the Recomposer's coroutines. In an app, `GlobalSnapshotManager` applies such writes through `AndroidUiDispatcher.Main` (a handler message). In the Robolectric JVM that dispatcher is usually stalled: it is flagged "dispatch scheduled" with one task queued while the main looper holds no message for it, because Robolectric clears the main looper's queue between tests and the process-wide dispatcher keeps its flag. A two-test demo reproduced this every time (a global write after a test's last looper drain; in the next test a write followed by `idle()` and three clock frames stays pending until `Snapshot.sendApplyNotifications()`), and the dispatcher was in that state at 257 of 376 `waitUntil` calls in one normal run of the seven classes. `rule.waitUntil` only steps the Compose test clock, so the write is applied only by accident: when a later Recomposer coroutine resumes (the test dispatcher applies snapshots after each one), or by `waitForIdle`.
2. *The continuation may not even run.* `waitUntil` does not run the paused Robolectric main looper, so a wait without a pump (`ScanRouteTest.kt:160`, `:310`, `CaseRecognitionRouteTest.kt:108`) never runs a continuation posted after the test's last `waitForIdle`.

So the test passes when the calibration hop ends before the content first composes (the composition that follows applies the write in passing) and hangs for the full 10 s when it lands after the composition has settled, or when its continuation is posted after the last looper drain of a wait that does not pump. Thread scheduling picks the order, which is why any test could fail on any diff. Every stuck wait showed `calibrationLoaded=true previewReady=true cameraStarted=false`, pending global changes, and recovered at once on `Snapshot.sendApplyNotifications()` but not on more looper pumping. Commits c66d5b92 and 1d6f402b fixed single sites (a full sync at two waits, a looper pump at others) and left the rest.

**Reproduction.** Repeating the classes under CPU contention (test JVM pinned to one or two cores with busy loops) did not fail in 3 runs. A schedule fuzz did, every time: investigation-only instrumentation (never committed) slept a random 0–60 ms inside three background hops that `onCreate` starts (`LessonCatalog.load`'s IO block, the `analyzerExecutor` block that builds the controller, the coach-mark read on `Dispatchers.IO`), enabled by the `GH400_JITTER=60` environment variable. Only the first two are on the camera start path; the coach-mark read is not (see Results). Result before the fix: 21, 23 and 22 of 100 tests failed in three runs, all at camera-start waits (`ContinuousQuickScanRouteTest.kt:1521`, `SolveFromScanRouteTest.kt:363`, `GuidedScanCloseRouteTest.kt:409`, `QuickCheckRouteTest.kt:132/308/378`, `PlacementFromScanRouteTest.kt:144`, `ScanRouteTest.kt:160/310`).

**Why no unit test of the helper.** A helper test passes with or without the fix unless the dispatcher is stalled first, and stalling it takes a global write after an earlier test's last looper drain: an ordered pair of tests with a JUnit rule of their own. That is outside this change (no new test rule) and is recorded under `deferred`; the evidence here is the fuzz and the repeated runs. A real regression in the start path still fails the wait: with `ComposeTimeoutException` after 10 s, or with Espresso's `AppNotIdleException` from a poll's `waitForIdle` if Compose never settles.

**The lead in the issue.** `CameraDenialRecord.readAndMark` (`GuidedScanActivity.kt:130`) runs only in the permission-denied callback, so it is not on the start path of these tests (they grant the permission). The hops that matter are the calibration load (162), the controller build on `analyzerExecutor` (170) and `lessonCatalog.load()` (178).

**Why a full sync per poll removes the cause.** `waitForIdle` calls `Snapshot.sendApplyNotifications()` itself and drains the main looper, so the wait no longer depends on the stalled dispatcher or on which thread finishes first; it returns as soon as the background hops are done. What is left on the wall clock is only those hops (milliseconds of real work), bounded by the unchanged 10 s.

## Verification

**Commands:**
- `for i in $(seq 1 30); do ./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.ScanRouteTest --tests dev.cubentia.app.SolveFromScanRouteTest --tests dev.cubentia.app.ContinuousQuickScanRouteTest --tests dev.cubentia.app.QuickCheckRouteTest --tests dev.cubentia.app.PlacementFromScanRouteTest --tests dev.cubentia.app.GuidedScanCloseRouteTest --tests dev.cubentia.app.CaseRecognitionRouteTest --rerun || break; done` -- expected: 30 of 30 runs pass (100 tests each).
- The same Gradle command three times with the investigation-only fuzz applied to the two camera-start hops and `GH400_JITTER=60` -- expected: 3 of 3 runs pass; the fuzz is removed again before committing.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `git diff --stat main...HEAD` -- expected: only `app/src/testDebug/…` and this spec.

**Results (2026-11-26, local, JDK 25 for Gradle, tests on the JDK 17 toolchain):**
- Consecutive runs, no fuzz: `./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.ScanRouteTest --tests dev.cubentia.app.SolveFromScanRouteTest --tests dev.cubentia.app.ContinuousQuickScanRouteTest --tests dev.cubentia.app.QuickCheckRouteTest --tests dev.cubentia.app.PlacementFromScanRouteTest --tests dev.cubentia.app.GuidedScanCloseRouteTest --tests dev.cubentia.app.CaseRecognitionRouteTest --rerun --console=plain` run 30 times in a row -- 30 of 30 passed, 100 tests each (3000 test runs, 0 failures), 60–62 s per run.
- Schedule fuzz on the two hops of the camera start path (catalog IO block, `analyzerExecutor` hop), `GH400_JITTER=60`, same Gradle command: before the fix 23 of 100 tests failed in one run, every one at a camera-start wait; after the fix 0 of 300 failed in three runs.
- Schedule fuzz on all three hops (also the coach-mark read): before the fix 21, 23 and 22 of 100 failed in three runs, every one at a camera-start wait; after the fix 0, 0 and 1 failed. The one failure is `ScanRouteTest.coachMarkStaysPendingUntilTappedAndIsAbsentInTheNextActivity` at `ScanRouteTest.kt:449` ("could not find review:coach"): the test opens the review before the delayed coach-mark read has returned. It is not a camera-start wait and the fix does not touch it; recorded under `deferred`.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL; `:app:testDebugUnitTest` ran 1551 tests with 0 failures.
- Changed files: eight under `app/src/testDebug/kotlin/dev/cubentia/app/` (38 insertions, 59 deletions, one new file) and this spec. The fuzz instrumentation was removed before the 30 runs and the build; nothing of it is in the diff.
