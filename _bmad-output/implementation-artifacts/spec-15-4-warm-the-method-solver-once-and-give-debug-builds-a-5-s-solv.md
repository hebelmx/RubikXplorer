---
title: 'Story 15.4: Warm the method solver once, and give debug builds a 5 s solve deadline'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: '4b0dc56d8f2f878480a57f8d74185474417b0a49'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-27-solver-deadline-tier.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The first method solve of a process pays about 1 s (release) to 3.7 s (debug) of one-time class loading and JIT inside `SolveByMethod`'s 3 s deadline. A busy release device can time out the first *Solution style* tap, and a Lenovo debug build always does.

**Approach:** After the solve flow's pruning tables are ready, start one throwaway `MethodSolve` per process, off-main, untimed and cancellable, whose result is discarded. Choose one solve deadline per process in `:app` from `ApplicationInfo.FLAG_DEBUGGABLE` (3 s, or 5 s when debuggable) and pass it to `SolveCube`, `SolveByMethod` and `PlanRecovery`.

Refs: FR-30, FR-15 · AD-8, AD-21 · NFR-3 · SCP 2026-11-27 solver-deadline-tier (PO decision: A + B, with E folded into the wording fix).

## Boundaries & Constraints

**Always:**
- AD-8: only `SolveCube` calls `CubeSolver`, as an Effect with a timeout of 3 s, or 5 s in debuggable builds. Release and benchmark builds keep 3 s. The value is chosen once per process in `:app`. `PlanRecovery` and `SolveByMethod` use the same Effect-with-timeout shape and the same deadline.
- AD-21: `MethodSolve` never calls the Solver, and `SolveByMethod` re-verifies every result. After solver preparation, one throwaway `MethodSolve` warm-up runs off-main, untimed and cancellable, outside the deadline. Its result is discarded and never reaches a session.
- The warm-up runs at most once per process. It runs in its own coroutine on `Dispatchers.Default` and shares no lock, mutex or single-thread dispatcher with `SolveByMethod`. A real solve runs alongside it and is never queued behind it.
- A warm-up failure (an exception, content that does not load, or `NotFound`) or a cancellation is silent. It is logged with `Log.d` only when the build is debuggable.
- `MethodSolveBenchmarkActivity` keeps its intent extras and its existing log lines unchanged. It reuses the production `MethodSolveWarmUp` class, with no copied warm-up logic.

**Never:**
- No change to `TwoPhaseCubeSolver`'s search cap (`3_000_000_000L` ns), NFR-3, the PRD, the UX copy or any `SessionEvent`, reducer or `SessionState`.
- No device tier and no device-class detection. No planning-artifact edits.
- The warm-up never dispatches to `SessionHost`, never writes `MethodSolutionCache` and never touches `SolutionStyleViewModel`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Tables ready | first `SolverLoad.Ready` in `SolveFlowViewModel.accept` | `warmUp.start()` launches one `TWO_LOOK` `MethodSolve` of the fixed warm-up scramble on Default; result discarded | none |
| Second accept | warm-up already started (running or done) | `start()` does nothing | none |
| Real tap during warm-up | warm-up solver still running | `SolveByMethod` completes on its own deadline while the warm-up is still running | none |
| Warm-up throws / content failed / NotFound | — | nothing reaches a session or cache | `Log.d` in debuggable builds only |
| Cancel | `cancel()` while running | `shouldContinue` turns false, job completes cancelled, no exception escapes | debug-only log |
| Deadline | `FLAG_DEBUGGABLE` set / not set | `SolveDeadline.millis` = 5000 / 3000 | none |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveCube.kt` -- the deadline is a private `DEADLINE_MILLIS = 3_000L` (:88). Add the constructor param `deadlineMillis: Long = DEADLINE_MILLIS` (public `val`), and make `DEADLINE_MILLIS` a public `companion object` const.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` (:62) and `PlanRecovery.kt` (:36) -- already take `deadlineMillis`. Make it a public `val` so the wiring tests can read it. Keep `DEADLINE_MILLIS = 3_000L`.
- `core/application/src/test/.../usecase/{SolveCubeTest,SolveByMethodTest,PlanRecoveryTest}.kt` -- the existing `DEADLINE_MILLIS == 3_000` asserts stay. Model the deadline tests on `PlanRecoveryTest` (:97, `deadlineMillis = 50`).
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` -- the Hilt singletons. `methodSolverFactory` (:50) builds `SolveByMethod(it, algorithmStatuses = repository)`. Add the deadline and the `SolveDeadline` and `MethodSolveWarmUp` providers. `FlagsModule.kt:30` shows the `FLAG_DEBUGGABLE` idiom.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- the Hilt VM with `@Inject constructor(source)`. In `accept` (:258), `SolverLoad.Ready` (:290) runs `SolveCube(loaded.solver)` (:294). That is the warm-up trigger point and where the deadline goes.
- `app/src/main/kotlin/dev/cubentia/app/content/LessonCatalog.kt` -- the `@Singleton` with `suspend load()` and `state: StateFlow<CatalogState>` (`Ready(bundle)` or `Failed`). This is the warm-up's bundle source.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- the default `SolveFlowViewModel(object : SolverSource …)` (:166) and `SolutionStyleViewModel({ SolveByMethod(it) }, …)` (:176). `LessonScreen(` call sites are at :737, :803 and :857.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:165` -- `planRecovery: PlanRecovery = remember { PlanRecovery() }`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- fields are `@Inject lateinit`, and `AppShell(...)` is called at :294.
- `app/src/corpusRunner/kotlin/dev/cubentia/app/solver/MethodSolveBenchmarkActivity.kt` -- the harness (debug and benchmark only). The `prepared,nanos=` line is at :136. Untimed `warmup` repeats follow it.
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` -- a stateless `object` that is safe to call concurrently. `MethodSolveFunction` (`SolveByMethod.kt:27`) is the injectable shape. `Notation.parse` is in `domain/cube/Notation.kt`.
- `docs/benchmarks.md` § Method-solve deadline, "How to run it" (:795) -- document the warm-up step here. The orchestrator adds the result rows later.

## Tasks & Acceptance

**Execution:**
- `core/application/.../usecase/SolveCube.kt` -- add `val deadlineMillis: Long = DEADLINE_MILLIS` and use it in `withTimeoutOrNull`. Make the companion public (`DEADLINE_MILLIS = 3_000L`) -- so `:app` can pass 5 s.
- `core/application/.../usecase/SolveByMethod.kt`, `PlanRecovery.kt` -- turn `deadlineMillis` into `val` (no behaviour change).
- `core/application/src/test/.../usecase/SolveCubeTest.kt` -- add tests: a solver that blocks past a short injected deadline returns `TimedOut`, a solve under it returns `Solved`, and `DEADLINE_MILLIS == 3_000`.
- `app/src/main/kotlin/dev/cubentia/app/solver/SolveDeadline.kt` (new) -- add `data class SolveDeadline(val millis: Long)` with `RELEASE_MILLIS = 3_000L`, `DEBUGGABLE_MILLIS = 5_000L`, `RELEASE`, `of(debuggable: Boolean)` and `of(info: ApplicationInfo)` (`FLAG_DEBUGGABLE`) -- the one per-process value.
- `app/src/main/kotlin/dev/cubentia/app/solver/MethodSolveWarmUp.kt` (new) -- add `class MethodSolveWarmUp(bundle: suspend () -> ContentBundle?, scope: CoroutineScope, solver: MethodSolveFunction = MethodSolve::solve, log: (String, Throwable?) -> Unit)`. `start(): Job?` launches once (an `AtomicBoolean` guard) on `Dispatchers.Default` and solves `WARM_UP_STATE` (`SOLVED` plus a fixed 25-move scramble parsed by `Notation`) with `WARM_UP_METHOD = TWO_LOOK`, with no timeout, `statuses = emptyMap()` and `shouldContinue = { isActive }`. It discards the result, and on a null bundle, `NotFound`, an exception or a cancellation it calls `log` (cancellation is rethrown). `cancel()` cancels the job. It returns nothing and holds no session reference.
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` -- add `@Provides @Singleton solveDeadline(context) = SolveDeadline.of(context.applicationInfo)`. Add a `warmUp(context, catalog)` with `bundle = { catalog.load(); (catalog.state.value as? CatalogState.Ready)?.bundle }`, a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and a log that calls `Log.d(TAG, …)` only when debuggable. In `methodSolverFactory`, pass `deadlineMillis = deadline.millis`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- make the primary constructor private, `(source, deadline: SolveDeadline, warmUp: MethodSolveWarmUp?)`. Add an `@Inject` secondary `constructor(source, deadline, warmUp: MethodSolveWarmUp)`, which is non-null for Dagger, and a secondary `constructor(source)` (`RELEASE`, `null`) for the existing callers. On `SolverLoad.Ready`, call `warmUp?.start()` before `SolveCube(loaded.solver, deadline.millis)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt`, `ui/lesson/LessonScreen.kt`, `MainActivity.kt` -- add a `solveDeadline: SolveDeadline = SolveDeadline.RELEASE` param to `AppShell` and thread it through `ShellContent` to the three `LessonScreen` calls. Add `LessonScreen(…, planRecovery = remember(deadline) { PlanRecovery(deadlineMillis = deadline.millis) })`. `MainActivity` injects `SolveDeadline` and passes it.
- `app/src/corpusRunner/.../MethodSolveBenchmarkActivity.kt` -- for the method kinds only, after the `prepared` line and before the untimed `warmup` repeats and the first timed solve: unless the new extra `--ez warmUp false` is given, run `MethodSolveWarmUp(bundle = { bundle }, scope = this, log = …).start()?.join()` and log one new line, `warmed-up,nanos=<n>`. Every existing extra and line stays as it is. Update the KDoc.
- `docs/benchmarks.md` -- in "How to run it", add one bullet on the default-on production warm-up, the `warmed-up,nanos=` line and `--ez warmUp false` to reproduce the "before" rows. No result rows.
- `app/src/test/kotlin/dev/cubentia/app/solver/MethodSolveWarmUpTest.kt` (new) -- JVM tests: (a) the warm-up runs exactly once across two `start()` calls, untimed (a solver sleeping past 3 s still completes), with the fixed state and `TWO_LOOK`; (b) cancellation: a solver looping on `shouldContinue` stops, the job completes cancelled, nothing is thrown and `log` is called; (c) failure: an exception or a null bundle is logged, not thrown; (d) not queued: while the warm-up solver is blocked on a latch, `SolveByMethod` with a fast solver completes; (e) never reaches a session: `SolveFlowViewModel.accept` with a ready source and a warm-up whose solver returns `Solved` calls `onSolved` exactly once with the `SolveCube` moves, and `MethodSolutionCache` stays empty.
- `app/src/test/kotlin/dev/cubentia/app/solver/SolveDeadlineTest.kt` (new) -- `of(false)` gives 3000 and `of(true)` gives 5000. An `ApplicationInfo` with and without `FLAG_DEBUGGABLE` gives the same values. `SolverModule.methodSolverFactory(repo, SolveDeadline(5000)).create(bundle).deadlineMillis == 5000` and likewise for 3000. `SolveFlowViewModel` passes the deadline to `SolveCube`: with a short deadline and a solver blocking past it, the outcome is the timed-out Editing.

**Acceptance Criteria:**
- Given content and pruning tables prepared in the app process, when the solve flow's tables become ready, then one throwaway `MethodSolve` runs off-main, untimed and cancellable, and its result is never dispatched. A failure or cancellation is silent, logged in debuggable builds only.
- Given a style tap before the warm-up finishes, when `SolveByMethod` runs, then it runs on its own deadline concurrently, not behind the warm-up.
- Given a release or benchmark build, when `SolveCube`, `SolveByMethod` or `PlanRecovery` runs from the app, then its deadline is 3 s, and 5 s in a debuggable build, from one `SolveDeadline` provided once per process. `TwoPhaseCubeSolver`'s cap and NFR-3 are unchanged, and unit tests cover both values.
- Given the change, when `./gradlew --no-daemon spotlessCheck build` runs, then it is green, including `MethodSolveWarmUpTest`.
- **Device-verified later (by the orchestrator and the PO, not this build):** the Lenovo TB128XU and Galaxy Tab S7 FE `MethodSolveBenchmarkActivity` re-run, with its rows added to `docs/benchmarks.md`, and the PO's 5 cold-start first style taps on the Lenovo debug build. This build only makes the harness run the production warm-up, with its protocol and output kept.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 18 findings — high 0, medium 1, low 9, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) The warm-up's `catalog.load()` may contend with the playback's content load — `LessonCatalog.load` is mutex-guarded and returns at once when the content is already loaded. AppShell loads it at shell start, so the warm-up never delays playback.
  - `[low]` `[reject]` (blind) No test for a bundle provider that throws — `LessonCatalog.load` never throws (its KDoc says so), and the warm-up's `catch (Exception)` covers it anyway. A Learner is unlikely to meet it, and the fix adds a test only.
  - `[low]` `[reject]` (blind) No test for cancellation while `bundle()` loads — the same `catch (CancellationException)` rethrow covers that path. Unlikely in use, and the fix adds test infrastructure only.
  - `[false]` `[reject]` (blind) The concurrency test uses stubs, so the real `MethodSolve` might not be safe to run concurrently — `MethodSolve` is a stateless object, its only shared cache (`PieceDistances.tables`) is a `ConcurrentHashMap`, and `RecoveryPlanner` search state is per instance.
  - `[false]` `[reject]` (blind) The benchmark harness logs warm-up notes with `Log.i` in non-debug builds — the debug-only rule binds the Learner-facing app. The harness is a measurement tool that is never in `release`, and a warm-up failure must show in its evidence.
  - `[low]` `[reject]` (blind) No test that `PlanRecovery` gets the injected deadline through AppShell — the wiring was checked by reading `MainActivity` → `AppShell` → the 3 `LessonScreen` sites. Recovery searches peak under 1 s, so 3 vs 5 s only matters if the argument is dropped in debug builds. The fix needs new Compose test infrastructure. (Same root as the verification-gap row.)
  - `[low]` `[patch]` (blind) The default `SolutionStyleViewModel` `remember {}` captured the first `solveDeadline` — fixed: keyed `remember(solveDeadline)`.
  - `[low]` `[patch]` (blind) The recompiled epic context dropped "the only combinators are `allOf` and `anyOf`" — re-added to the AD-6 bullet in `epic-15-context.md`.
  - `[false]` `[reject]` (blind) No follow-up record for the device gate — the intent assigns the device ACs to the orchestrator and PO after this build, and the spec records them as device-verified later.
  - `[low]` `[reject]` (verification-gap) No integration assertion that MainActivity's debuggable deadline reaches AppShell's consumers — filed as a gap with disposition patch. In MainActivity, method-style solves use the Hilt `SolutionStyleViewModel`, whose factory deadline is tested in `SolveDeadlineTest`. Only lesson recovery depends on the AppShell argument (peak < 1 s), and the fix needs new UI test infrastructure.
  - `[false]` `[reject]` (intent) The lazy trigger (first `SolverLoad.Ready`) diverges from an eager reading — the AC's precondition is "pruning tables are prepared", and in this app that happens only at the solve flow's first table load. No earlier trigger satisfies it, and the debug 5 s deadline covers an overlap.
  - `[false]` `[reject]` (intent) The benchmark waits for the warm-up but the app does not — the intent says to run the production warm-up "after preparation and before the first timed solve", which the harness does literally.
  - `[low]` `[reject]` (intent) The new `warmUp` extra and `warmed-up,nanos=` line change the protocol — they only add to it. Every existing extra and line is unchanged, so the documented commands still run as before.
  - `[low]` `[patch]` (intent) The "never reaches a session" test's `MethodSolutionCache` check was vacuous (the cache was never connected) — removed the unconnected cache. The test is now `theWarmUpNeverReachesASession` and keeps the published-once and `start()==null` assertions.
  - `[low]` `[reject]` (intent) The cancel test uses a stub, not the real `MethodSolve` stopping on `isActive` — the warm-up passes the same `{ isActive }` contract that `SolveByMethod` already uses in production. A real-search cancel test would be slow, and Learners are unlikely to meet the gap.
  - `[medium]` `[patch]` (intent) The production debug-only log gate in `SolverModule.warmUp` was untested — added `SolveDeadlineTest.theProductionWarmUpLogsOnlyInDebuggableBuilds`. Using Robolectric `ShadowLog` and failed content, it asserts a `MethodSolveWarmUp` entry only when `FLAG_DEBUGGABLE` is set.
  - `[low]` `[reject]` (intent) `LessonScreen`'s own default is still `PlanRecovery()` at 3 s, so a future call site could fall back to it — all 3 current sites pass the deadline. This is a future-regression concern, with the same root as the wiring gap above.
  - `[false]` `[reject]` (intent) Build status not visible in the diff — `./gradlew --no-daemon spotlessCheck build` was run: BUILD SUCCESSFUL.

## Design Notes

The warm-up starts at the solve flow's `SolverLoad.Ready`, which is the first point where the pruning tables are prepared. Content is loaded through the singleton `LessonCatalog`. Every Solution Playback starts from `SolveFlowViewModel.accept`, so the warm-up runs before any style chip can be tapped. `MethodSolve` itself needs no tables. Waiting for them keeps the warm-up off app startup and off Lessons (`SolverModule` KDoc: "Nothing here runs at app startup").

The one solve uses `TWO_LOOK`: it covers the shared first-two-layer search plus `FasterLastLayer`/`LastLayerMatcher` (used by 2-look and full). The story asks for one `MethodSolve`, and the device re-run measures all three methods. No UI behaviour changes. On a debuggable build the existing timeout copy simply appears after 5 s instead of 3 s (SCP §5, UX: none).

## Verification

**Commands:** (env: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew --no-daemon :core:application:test :app:testDebugUnitTest --tests '*MethodSolveWarmUp*' --tests '*SolveDeadline*' --tests '*SolveFlowViewModel*' --tests '*SolutionStyle*'` -- expected: pass
- `./gradlew --no-daemon spotlessApply && ./gradlew --no-daemon spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** `:app` now picks one solve deadline per process from `FLAG_DEBUGGABLE` (`SolveDeadline`: 3 s, or 5 s when debuggable). It passes the value to `SolveCube` (new `deadlineMillis` param), `SolveByMethod` (via `methodSolverFactory`) and `PlanRecovery` (via AppShell → LessonScreen). `TwoPhaseCubeSolver` and NFR-3 are untouched. A process-singleton `MethodSolveWarmUp` runs one throwaway `TWO_LOOK` `MethodSolve` of a fixed 25-move scramble. It starts when the solve flow's pruning tables first become ready, on `Dispatchers.Default`, untimed and cancellable, with no lock shared with `SolveByMethod`. Its result is discarded, and failures and cancellations are logged in debuggable builds only. `MethodSolveBenchmarkActivity` runs the same class after `prepared` and before any timed solve: it is on by default, logs a new `warmed-up,nanos=` line, and `--ez warmUp false` reproduces the "before" rows. Existing extras and lines are unchanged.

**Files:**
- `core/application/.../usecase/SolveCube.kt` — `deadlineMillis` param; public `DEADLINE_MILLIS = 3_000`.
- `core/application/.../usecase/SolveByMethod.kt`, `PlanRecovery.kt` — `deadlineMillis` exposed as `val`.
- `app/src/main/.../solver/SolveDeadline.kt` (new) — the per-process deadline.
- `app/src/main/.../solver/MethodSolveWarmUp.kt` (new) — the once-per-process warm-up.
- `app/src/main/.../di/SolverModule.kt` — provides `SolveDeadline` and `MethodSolveWarmUp` (debug-gated log); the factory passes the deadline.
- `app/src/main/.../ui/solve/SolveFlowViewModel.kt` — starts the warm-up on `SolverLoad.Ready`; `SolveCube` uses the deadline.
- `app/src/main/.../ui/shell/AppShell.kt`, `MainActivity.kt` — thread `solveDeadline` to the default style VM and the 3 `LessonScreen` `PlanRecovery`s.
- `app/src/corpusRunner/.../MethodSolveBenchmarkActivity.kt` — runs the production warm-up for method kinds.
- `docs/benchmarks.md` — "How to run it" bullet for the warm-up.
- Tests: `MethodSolveWarmUpTest` (new, 5), `SolveDeadlineTest` (new, 5), `SolveCubeTest` (+2), `MethodSolverWiringTest` and 4 `testDebug` route tests (bind the new singletons).
- `_bmad-output/implementation-artifacts/epic-15-context.md` — recompiled with Story 15.4.

**Review:** 18 findings. 4 patched (1 medium, 3 low), 0 deferred, 14 rejected (8 false, 6 low), each with its reason in the Review Triage Log.

**Follow-up review recommended:** false (patched: high 0, medium 1, low 3).

**Verification:** `./gradlew --no-daemon spotlessCheck build` — BUILD SUCCESSFUL (after the patches). `MethodSolveWarmUpTest` 5/5, `SolveDeadlineTest` 5/5, `SolveCubeTest` 11/11 from the JUnit XML. The implementer saw one transient Android lint detector crash on a first full build; the rerun was green.

**Device-verified later (orchestrator / PO):** the Lenovo TB128XU and Galaxy Tab S7 FE `MethodSolveBenchmarkActivity` re-run, with rows added to `docs/benchmarks.md` § Method-solve deadline, and the PO's 5 cold-start first style taps on the Lenovo debug build.

**Residual risks:** the warm-up warms one method (`TWO_LOOK`). The BEGINNER last-layer path (`BeginnerLastLayer`) is not exercised by it, so the device re-run should confirm the BEGINNER cold first solve. A style tap made before the warm-up finishes runs alongside it, still cold, within its own deadline.
