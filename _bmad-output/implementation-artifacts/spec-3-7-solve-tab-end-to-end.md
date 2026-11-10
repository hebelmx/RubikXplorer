---
title: 'Solve tab end to end'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: d6d2ccac3514c59144eeb4396af4ca543269e1d8
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Every solve piece exists (Manual Cube Editor 3.5, `SolveCube` 3.4, `SolutionPlayback` 3.6/8.5, `SolvePlaybackStarted`/`SessionHost.endSolve` 6.5) but nothing in the running app connects them: the Solve tab shows only the free cube, so a Learner cannot enter a cube and get it solved (FR-14/15/16, UX Flow 3, UX-DR22/25).

**Approach:** Add an *Enter colors* entry on the Solve tab that opens the editor, solves the accepted `CubeState` through a small app-side solve flow (table preparation, then `SolveCube`, cancelable, revision-guarded), starts a `SOLVE_PLAYBACK` session on success and shows playback as a full-screen task flow that ends through `SessionHost.endSolve`, with the "learn" offer after the last step. Fix the two Epic 8 retro defects in `SolutionPlayback` that wiring exposes.

## Boundaries & Constraints

**Always:** Session changes only through `SessionHost.dispatch`/`endSolve` (AD-3); the editor is not a session and returns a validated `CubeState`. The Solver is reached only via `SolveCube` (3 s deadline, re-verification) and only with a `CubeValidator` state. Table load and solve run off the main thread, are cancelable, and a cancelled or superseded attempt publishes nothing (echo-or-discard, AD-3). All Learner copy is `en.json` content resolved through `ContentStrings` (`Text(key)`), passing `checkLearnerText`/`ShippedContentTest`. *Scan my cube* is not rendered (no v1.1 scan flow exists; UX "no dead buttons"). `SOLVE_PLAYBACK` stays non-resumable on cold start (`SessionKind.resumesOnColdStart`). The free cube stays on the Solve tab below the *Enter colors* action (NFR-2 macrobenchmark and existing shell tests depend on it).

**Never:** Choose or implement the production pruning-table delivery policy, bundle tables, measure devices, calibrate, or change `:core:solver` (Story 3.8 owns these; this story uses the existing `PruningTableProvider` with a private cache file as the interim path). No camera/scan/Quick Check UI. No new `SessionEvent`, `SessionKind` or persisted entity. No change to `prd.md`, `ARCHITECTURE-SPINE.md`, planning artifacts, `_bmad/` or `.claude/skills/`. No `INTERNET` or new SDKs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solve home | Solve tab opened, no active session | `solve:enterColors` shown; no scan action anywhere; free cube below | — |
| Lesson open | Session kind takes Moves (Lesson/Practice/2-look) and Learner taps *Enter colors* | Editor shows "Starting a solve pauses your lesson." (`solve:pauseNote`); session unchanged until a Solution is ready | — |
| Valid entry, tables cold | Accept valid cube; `SolverSource` not ready | Indeterminate progress bar (`solve:progress`) + Cancel (`solve:cancel`) + "Preparing the solver (one time, a few seconds)." (`solve:preparing`) | Load failure → failure copy, back to editor with colors |
| Valid entry, tables warm | Accept; source ready | Progress bar + Cancel, no preparing line | — |
| Solved | `SolveCubeOutcome.Solved(moves)` for the current attempt | `SolvePlaybackStarted(state, moves)` dispatched; any open Lesson attempt ends ABANDONED by the existing reducer; full-screen playback (`solvePlayback:screen`), no bottom bar | — |
| Timeout | `SolveCubeOutcome.TimedOut` | Editor again with exactly the accepted stickers and "Couldn't find a solution in time. Your colors are still here — try again." (`solve:error`) | no playback, session unchanged |
| Failed | `SolveCubeOutcome.Failed` | Editor with colors and `msg.solve.failed` copy | no playback |
| Cancel | Tap Cancel while preparing or solving | Job cancelled; editor with colors, no error line; a late completion is discarded | session unchanged |
| Already solved | Accept the solved cube | Empty Solution playback; learn offer shown immediately | — |
| Last step | Playback at end (virtual end, or physical end after *Done*) | "Want to learn how to do this yourself?" (`solve:learnOffer`) → `endSolve(bundle)`, Learn tab shown | — |
| Close playback | Top-bar back / system back | `endSolve(bundle)`; Solve tab home; a suspended Lesson is back on the Continue card from its Checkpoint | — |
| App closed during playback | Cold start with a `SOLVE_PLAYBACK` snapshot | Shell shows normal tabs, no playback | — |
| Physical step back at undo barrier | Physical playback, `session.history.canUndo == false` | Position does not move; no Undo dispatched | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `Tab.SOLVE` currently renders `FreeCubeScreen` (destination lambda ~L560); full-screen task flows return early before the tab scaffold (Lesson ~L196, practice, skip-check); `BackHandler` ~L360; `ShellContent` collects `host.state`, `catalog.state` (`CatalogState.Ready` has `bundle`, `strings`). `LearnScreen` Continue uses `session.kind?.isTaught`.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- `dispatch { at -> SessionEvent }`, `endSolve(bundle)` (L259: ends playback then `resumeCheckpoint`), `restoreLatest` skips non-resumable snapshot kinds then resumes the Checkpoint.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveCube.kt` -- `SolveCube(solver)(Effect.SolveCube(effectId, revision, state)): SolveCubeCompleted` with `SolveCubeOutcome.{Solved(List<FaceTurn>), TimedOut(Text), Failed(Text)}`; read-only.
- `core/application/.../session/{SessionEvent,Reduce,SessionKind,Effect}.kt` -- `SolvePlaybackStarted(state, solution, at)` (verified in reducer; suspends a takes-Moves session and ends its attempt ABANDONED, Reduce.kt ~L1135), `SolvePlaybackEnded`; `Effect.SolveCube`; read-only.
- `core/solver/src/main/kotlin/dev/cubentia/solver/{PruningTableProvider,TwoPhaseCubeSolver}.kt` -- `PruningTableProvider(cacheFile).load(): PruningTableLoadResult.{Ready(tables,…),Failed(reason)}` (serialized, cancellable, cached in memory after first success); `TwoPhaseCubeSolver(tables): CubeSolver`; read-only. Debug harness usage: `app/src/debug/.../solver/PruningTablesBenchmarkActivity.kt`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- `ManualCubeEditor(strings, onAccept: (CubeState) -> Unit, modifier, initial: Facelets, …)`; state `rememberSaveable` keyed on `initial` (so passing the accepted facelets restores colors).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState` (L145 `stepBack`, L160 `dispatch` local `reduce` + `onEvent`, L166 `updateSession` revision gate), `rememberSolutionPlaybackState(session, reduceMotion, onEvent, clock)`, `SolutionPlayback(state, strings, …)`; `atEnd`, `size`, `isPhysical`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt`, `ui/freecube/FreeCubeViewModel.kt`, `di/*Module.kt` -- ViewModel and Hilt patterns; `SessionHost` is an `@Singleton` `@Inject`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.solve.timed-out` (L4, copy to replace), `msg.solve.failed` (L5), `btn.physical.done` precedent for `btn.*` keys.
- Tests to extend/mirror: `app/src/test/.../ui/shell/AppShellTest.kt` (nav:solve at L270/368/1293 expects the free cube), `ui/solve/SolutionPlaybackTest.kt`, `data/SessionHostCheckpointTest.kt` (endSolve), `app/src/sharedTest/.../data/SnapshotRestartScenario.kt`; `macrobenchmark/.../Net2DFrameTimingBenchmark.kt` must keep finding the Solve tab's Move keys.
- Epic 8 retro item 5 (`_bmad-output/implementation-artifacts/epic-8-retro-2026-11-08.md` L52-55, L126): stepBack drift at undo barrier; local double-reduce vs host state.

## Tasks & Acceptance

**Execution:**
- [ ] `core/domain/src/main/resources/content/strings/en.json` -- set `msg.solve.timed-out` to "Couldn't find a solution in time. Your colors are still here — try again."; add `msg.solve.pauses-lesson` "Starting a solve pauses your lesson.", `msg.solve.preparing` "Preparing the solver (one time, a few seconds).", `msg.solve.learn-offer` "Want to learn how to do this yourself?", `btn.solve.enter-colors` "Enter colors", `btn.solve.cancel` "Cancel" (and a playback top-bar close label if needed) -- AC copy as content; update any test asserting the old timed-out text.
- [ ] `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt` -- `interface SolverSource { val isReady: Boolean; suspend fun load(): SolverLoad }`, `SolverLoad.{Ready(CubeSolver), Failed}`; `PruningTableSolverSource(provider)` builds and caches one `TwoPhaseCubeSolver` from `provider.load()`, cancellation propagates -- one seam for the interim table path that 3.8 will replace.
- [ ] `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` -- Hilt `@Singleton` `PruningTableProvider(File(context.cacheDir, "solver/pruning-v1.bin"))` and `SolverSource` binding; KDoc names Story 3.8 as owner of the production delivery policy.
- [ ] `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- ViewModel (Hilt-injected `SolverSource`; constructor usable in tests) with observable phase `Home | Editing(initial: Facelets, error: Text?, pausesLesson flag supplied by caller) | Solving(preparing: Boolean)`; `enterColors()`, `closeEditor()`, `accept(state, onSolved: suspend (CubeState, List<FaceTurn>) -> Unit)`, `cancel()`; each accept bumps a local attempt revision, runs load then `SolveCube` as `Effect.SolveCube(attempt, attempt, state)` in `viewModelScope`, discards completions whose revision is not current; TimedOut/Failed/load-failure return to `Editing` with the accepted facelets and the outcome's recovery `Text`; Solved calls `onSolved` and returns to `Home`. Not saved state: process death returns to Home.
- [ ] `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- Solve tab content: Home = *Enter colors* button (`solve:enterColors`) above `FreeCubeScreen`; Editing = `ManualCubeEditor(initial = phase.initial)` with close button, pause note when a takes-Moves session is active, error line; Solving = indeterminate `LinearProgressIndicator` (`solve:progress`), Cancel, preparing line while `preparing`. BackHandler: Editing → Home, Solving → cancel.
- [ ] `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- full-screen task flow for a `SOLVE_PLAYBACK` session: top bar with close (`solvePlayback:close`), `SolutionPlayback` with `onEvent` dispatching to the host, learn offer (`solve:learnOffer`) when `atEnd` (virtual or physical); close/back → `onClose`, offer → `onLearn`.
- [ ] `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- retro item 5: remove the local `reduce` in `dispatch` (host state via `updateSession` is the only session source; accept host state at equal-or-higher revision as needed so physical toggles stay correct); physical `stepBack` returns without moving position when `!session.history.canUndo`. Keep virtual behaviour and existing tags.
- [ ] `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- new `solveFlow: SolveFlowViewModel` param (default for tests); before the Lesson/practice/skip-check task flows, when `session.kind == SOLVE_PLAYBACK` and content is ready render `SolvePlaybackScreen` (no bar), close → `host.endSolve(bundle)`, learn → `endSolve` then `tab = LEARN`; `Tab.SOLVE` renders `SolveScreen`; `onSolved` → `host.dispatch { SolvePlaybackStarted(state, moves, it) }`. Update the `AppShell` KDoc.
- [ ] `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- obtain the Hilt `SolveFlowViewModel` and pass it.
- [ ] `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- fake `SolverSource`/`CubeSolver`: preparing true only while loading, Solved → `onSolved` with verified moves, TimedOut (real `SolveCube` deadline or suspending fake) and Failed → Editing with same facelets + key, cancel → Editing without error and no `onSolved` from a late result, second accept supersedes the first.
- [ ] `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- Robolectric over a real `SessionHost` and fake source: Solve home shows Enter colors and no scan node; pause note only with an active Lesson; accept solved/scrambled cube → playback screen, bar hidden; step to end → learn offer → Learn tab, session not `SOLVE_PLAYBACK`; close → Solve home; Lesson started then solved then closed → Continue card returns (in-memory or Checkpoint path); timeout copy with colors kept; cold-start scenario with a playback snapshot shows no playback.
- [ ] `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- adapt tests that relied on the local reduce to drive a real `SessionHost` (`onEvent = { e -> host.dispatch { e } }`-style, collecting `host.state`); add the undo-barrier stepBack test.

**Acceptance Criteria:**
- Given the Solve tab in the bottom bar, when it opens, then it offers *Enter colors* and no *Scan my cube*, and with a Lesson session active the solve flow shows "Starting a solve pauses your lesson." before `SolvePlaybackStarted` suspends that session.
- Given a valid entry, when the Solver runs, then a cancelable indeterminate progress bar shows, "Preparing the solver (one time, a few seconds)." shows while tables build, and a timeout shows "Couldn't find a solution in time. Your colors are still here — try again." with the entered colors kept and no playback.
- Given the last Playback step, when the cube is solved, then "Want to learn how to do this yourself?" links to Learn, and after the process restarts no solve playback is resumed.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 18 findings — high 0, medium 5, low 7, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: switching tabs mid-entry drops unaccepted sticker edits (editor `rememberSaveable` leaves composition) — the accepted colors are kept for retry as the AC requires; keeping drafts needs a `SaveableStateHolder` across the shell's tabs, new plumbing for an occasional detour.
  - `[medium]` `[patch]` Blind: leaving Solve does not cancel a running solve, so its late result can suspend a Lesson started meanwhile — added `SolveFlowViewModel.cancelSolving()` and `LaunchedEffect(tab)` in `AppShell` cancelling it when the tab leaves Solve; test `leavingSolveCancelsARunningSolve`.
  - `[medium]` `[patch]` Blind: Activity recreation during physical playback rebuilds the transport at position 0 while the host keeps confirmed Moves — `SolutionPlaybackState` now aligns to the host's Expected State on construction (`alignToExpected`, shared with `followOnMyCube`); test `aStateRebuiltMidFollowStartsAtTheHostExpectedStep`.
  - `[low]` `[reject]` Blind: rapid Done/Back within one frame can act on stale `canUndo` — a Back against a stale barrier is ignored, never applied; serializing taps against host acknowledgements adds pending-state guards for a sub-frame double tap.
  - `[low]` `[patch]` Blind: physical Back stays enabled at the undo barrier though it does nothing — added `canStepBack` (position and host `canUndo`) for enabled state and ink; asserted `assertIsNotEnabled` in the barrier test.
  - `[low]` `[patch]` Blind: timeout/failure line is not announced to screen readers — `solve:error` is now a polite live region; asserted in the timeout test.
  - `[low]` `[reject]` Blind: scrambled-cube shell tests call `flow.accept` directly — the editor-to-flow wiring (`onAccept` → `accept`) is the same lambda for every cube and is exercised by `solvedEditorEntryOffersLearnAndEndsPlayback` via `editor:accept`; editor output itself is covered by 3.5's tests.
  - `[low]` `[reject]` Edge: two physical Backs before the host update can cross the barrier — same sub-frame race as the Blind rapid-tap row; rejected for the same reason.
  - `[medium]` `[patch]` Edge: leaving Solve during preparation then starting a Lesson lets playback suspend it — same root cause and fix as the Blind tab-leave row.
  - `[medium]` `[patch]` Verification: `PruningTableSolverSource` is never exercised; a broken Ready branch would pass every test — added `PruningTableSolverSourceTest` (real provider, temp cache, `SolveCube` solves a scrambled cube, solver cached).
  - `[medium]` `[patch]` Intent: the real table-building path is never tested — same root cause and fix as the Verification row.
  - `[low]` `[reject]` Intent: "Preparing the solver" also shows while a warm disk cache loads in a cold process — the provider exposes no cache-presence signal; the line is brief and accurate in spirit, and 3.8 owns delivery.
  - `[false]` `[reject]` Intent: pause note covers Practice and 2-look, not just Lessons — those sessions are also suspended (attempt ended ABANDONED), so warning before it is the honest behaviour; no bad outcome.
  - `[false]` `[reject]` Intent: free cube kept beside *Enter colors* — UX names *Enter colors* the only solve *action*; the free cube is not one and has no other IA home (Design Notes).
  - `[false]` `[reject]` Intent: pause-note timing reading — the AC says "before the Lesson session is suspended"; the note shows in the flow and suspension happens only at `SolvePlaybackStarted`.
  - `[false]` `[reject]` Intent: learn offer keyed on `atEnd` rather than solved — the reducer and `SolveCube` both verify the Solution solves the entry, so the last step is the solved state.
  - `[false]` `[reject]` Intent: `SolutionPlayback` fixes go beyond the ACs — Epic 8 retro item 5 assigns them to 3.7 as a precondition for wiring physical playback.
  - `[false]` `[reject]` Intent: tables live in evictable `cacheDir`, generated on first use — the intent defers delivery policy to 3.8 explicitly.

## Design Notes

- **Free cube stays.** UX says *Enter colors* is the only Solve *action* in v1.0; the free cube is not a solve action and has no other IA home. Removing it would orphan Stories 1.6/5.x and break the NFR-2 macrobenchmark, which this story does not ask for. It sits below the action.
- **Pause note timing.** The note shows in the editor and progress phases whenever `session.kind?.takesMoves == true`; the session is suspended only when a verified Solution starts playback, so a cancelled or failed solve leaves the Lesson untouched. Resume after the solve is 6.5's `endSolve` Checkpoint path.
- **Interim table path.** `PruningTableProvider` with a private cache file is the accepted 3.2 API. 3.8 replaces only the `SolverSource` binding after device evidence; production acceptance of 3.7 waits on 3.8 (SCP 2026-11-02).
- **Revision guard.** The editor flow has no session revision, so the flow's attempt counter is the `Effect.SolveCube` revision; only the current attempt's completion is applied.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :app:testDebugUnitTest --tests '*Solve*' --tests '*AppShell*' --tests '*SolutionPlayback*'` -- expected: new and existing tests pass.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: all CI gates pass (Kover, LearnerText, boundaries, lint, APKs, macrobenchmark compiles).

## Auto Run Result

Status: done. The Solve tab now offers *Enter colors* (no *Scan my cube*) above the free cube. Accepting a valid cube runs an app-side solve flow (`SolveFlowViewModel`): interim pruning-table load through a `SolverSource` seam ("Preparing the solver…" while not ready), then `SolveCube` under its 3 s deadline, cancelable and revision-guarded. A verified Solution starts a `SOLVE_PLAYBACK` session, shown as a full-screen playback task flow with a close button and, at the last step, "Want to learn how to do this yourself?" linking to Learn; closing calls `SessionHost.endSolve`, which resumes a suspended Lesson from its Checkpoint. Timeout/failure returns to the editor with the entered colors and recovery copy. Epic 8 retro item 5 fixed in `SolutionPlayback` (host is the single session source; physical step back stops at the undo barrier).

Files changed:
- `app/.../solver/SolverSource.kt` — `SolverSource` seam, `PruningTableSolverSource` (interim; 3.8 owns delivery).
- `app/.../di/SolverModule.kt` — Hilt provider for the cache-file `PruningTableProvider` and source.
- `app/.../ui/solve/SolveFlowViewModel.kt` — solve-flow phases, load + `SolveCube`, cancel, stale-result discard, `cancelSolving`.
- `app/.../ui/solve/SolveScreen.kt` — Solve tab: Enter colors, editor with pause note and live-region error, progress + cancel + preparing line.
- `app/.../ui/solve/SolvePlaybackScreen.kt` — full-screen playback with close and learn offer.
- `app/.../ui/solve/SolutionPlayback.kt` — no local reduce, host-revision acceptance, `canStepBack` barrier, align to Expected State on rebuild.
- `app/.../ui/shell/AppShell.kt`, `app/.../MainActivity.kt` — routing, `endSolve` on close/learn, cancel on leaving Solve, ViewModel wiring.
- `core/domain/.../strings/en.json` — AC copy (timeout, pause, preparing, learn offer, buttons).
- Tests: `SolveFlowViewModelTest` (7), `AppShellSolveTest` (7), `SolutionPlaybackTest` (21, now on a real `SessionHost`), `PruningTableSolverSourceTest` (1).

Review: four layers, 18 findings (medium 5, low 7, false 6). Patched 5 entries (medium 3: tab-leave cancel, physical rebuild alignment, real solver-source test; low 2: Back disabled at barrier, live-region error); deferred 0; rejected the rest with reasons in the Review Triage Log. Follow-up review recommended: true — three medium entries were patched after the review layers ran, and the post-review code (tab-leave cancellation and physical-playback realignment after Activity recreation) has not been independently reviewed; recreation is tested by direct construction, not a real Activity restart. Patched by verdict: high 0, medium 3, low 2.

Verification: `./gradlew :app:testDebugUnitTest --tests '*Solve*' --tests '*AppShell*' --tests '*SolutionPlayback*' --tests '*PruningTableSolverSource*'` and `./gradlew spotlessCheck build` — BUILD SUCCESSFUL before review and after patches; 0 skipped/failed. Matrix audit: every row covered by an executed test (physical last-step learn offer added during verification).

Residual risks: production acceptance waits on Story 3.8 (table delivery, device timing, StrictMode, installed size); "Preparing the solver" also shows during a cold-process cache read; unaccepted editor edits are lost when leaving the Solve tab; not run on a device or emulator.
