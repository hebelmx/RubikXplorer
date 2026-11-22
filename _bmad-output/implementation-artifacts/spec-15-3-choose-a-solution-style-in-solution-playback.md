---
title: 'Story 15.3: Choose a solution style in Solution Playback'
type: 'feature'
created: '2026-11-22'
status: done
baseline_revision: '5fc87568f173ad0cec6a5f6dd5419b128cda9fd6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-2-last-layer-segments-for-2-look-and-full-oll-pll-preferring-m.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A session revision change during a method build is cancelled immediately only through the shell's LaunchedEffect(session.revision), which no shell test pins.
    evidence: |-
      SolutionStyleViewModelTest.aSessionMoveCancelsAPendingBuildAtOnce covers sessionMoved() directly; deleting the AppShell LaunchedEffect leaves the result discarded by the VM's own revision check, so only the spinner and "Building…" line would linger until the build ends (at most 3 s).
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt (LaunchedEffect(session.revision))
    severity: low
---

<intent-contract>

## Intent

**Problem:** Solution Playback from the Solve tab only plays the optimal (Shortest) solution. `MethodSolve` / `SolveByMethod` (Stories 15.1, 15.2) can build Beginner, 2-look and Full OLL + PLL solutions, but nothing in the app constructs `SolveByMethod`, issues `Effect.MethodSolve`, or shows segment labels (FR-30, FR-16).

**Approach:** Add a *Solution style* control to Solution Playback, run `Effect.MethodSolve` through `SolveByMethod` (constructed with the real `AlgorithmStatusRepository`, plus a wiring test — action item epic-15-deferred-15.2-wire-status-repository-in-15-3), restart the `SOLVE_PLAYBACK` session at step 1 with the method Moves, and show the current segment's Stage/Case name from content.

Binding ADs (story Refs line, verbatim): **AD-3, AD-20, AD-21**. Also followed: AD-6, AD-7, AD-8, AD-19. UX: UX-DR14.

**Settled by the PO (2026-11-22 clarifications, in order):**
1. Control: segmented chips *Shortest* · *Beginner* · *2-look* · *Full OLL + PLL*. *Shortest* is selected and plays by default; the suggested method's chip carries a "Suggested" badge so one tap starts it.
2. Suggestion: *Full OLL + PLL* once 2-look is mastered (`TwoLookMastery`) or the entry level says the Learner knows 2-look OLL and PLL (Story 14.1 is backlog; read as both 2-look Stages skipped); *2-look* once *Faster last layer* is unlocked; *Beginner* otherwise.
3. A method solution is built only when its chip is tapped, and kept for the app session per input state, so choosing it again does not rebuild.
4. While it builds, *Shortest* keeps playing and the chips stay usable; tapping another chip (including *Shortest*) cancels the build.
5. A loaded method solution restarts playback at step 1, paused; switching back to *Shortest* also restarts at step 1, paused.
6. Physical-cube Mode: chips disabled after the first *Done*, with the hint "Start over to change style"; after a "Use my cube" re-solve the style returns to *Shortest*.
7. Non-timeout failure (not found or fails re-check): "Couldn't build a <method> solution for this cube. Showing the shortest." (content strings); *Shortest* is always kept.
8. The current segment's Stage or Case name shows as text above the Move counter, and in the Physical-mode *Done* view; no dividers in the Move strip.

**Further PO answers (2026-11-22, third pass):**
9. Physical-cube Mode: the chips work again once the Learner steps *Back* to step 1; the hint reads "Go back to step 1 to change style" (content string, replaces the hint in 6); no new *Start over* control; switching to the virtual cube does not unlock them.
10. The timeout and failure messages stay until the Learner taps any chip or leaves playback.
11. During a build the tapped method chip shows selected with a progress mark while *Shortest* keeps playing; on timeout or failure the selection returns to *Shortest*; tapping *Shortest* during a build only cancels it and does not restart playback.

**Derived (from the answers above and AD-3, no new behavior):**
- "Locked" (chips disabled + hint) means the real cube is not at the input: `session.expected != input`. *Done* moves Expected off the input, *Back* to step 1 returns it, a switch to virtual keeps Expected (Reduce.kt `switchMode`), so 6/9 hold exactly.
- A build's `Effect.MethodSolve` carries the session `revision` at the tap; if the session revision changes before it completes (*Done*, *Back*, mode switch, Quick Check), the build is cancelled/discarded as stale (AD-3) and the selection returns to *Shortest* with no message — the same end state as 11's cancel.
- Tapping the chip already playing, or the chip already building, changes nothing except clearing a shown timeout/failure message (10).
- Segment label: the segment containing the current step index (`currentIndex`); none when there is no current step (physical at end, like the MoveNote) and none for *Shortest*.
- A new playback (Solve tab solve) and a "Use my cube" re-solve both reset the style to *Shortest*, cancel any build and clear messages.

## Boundaries & Constraints

**Always:**
- Method solution playback is a `SOLVE_PLAYBACK` session (AD-3), not resumed after app close; stale `MethodSolveCompleted` results are discarded by `revision`/`effectId`.
- One `PlaybackController` drives playback (AD-20); segment labels are content `Text` keys (`stage.<id>.title`, `case.<id>.name`); per-Move notes only from `MoveNote.of(move)`.
- `MethodSolve` never calls `CubeSolver`; results are re-verified by `SolveByMethod` before they are shown (AD-21, AD-8).
- Copy: pending "Building a step-by-step solution…"; timeout "Couldn't build that solution in time. The shortest one is still here." with *Shortest* kept. New strings in `content/strings/en.json`.

**Never:** CFOP F2L / Roux / ZZ; re-labelling Solver output; new `SessionKind`; edits to planning artifacts.


## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open | Solve tab solve → playback | Chips Shortest·Beginner·2-look·Full OLL + PLL, *Shortest* selected, Shortest Moves at step 1 paused, suggested chip shows "Suggested" | — |
| Suggest | 2-look mastered, or both 2-look Stages SKIPPED/SKIPPED_BY_PLACEMENT / Faster last layer unlocked (all Course Stages done) / otherwise | FULL_OLL_PLL / TWO_LOOK / BEGINNER | — |
| Build OK | tap *Beginner* (virtual) | chip selected + progress mark, pending line "Building a step-by-step solution…", Shortest keeps playing; on Solved: session restarted with method Moves, step 1, paused; segment label above counter | — |
| Cached | tap *Beginner* again later for the same input (any playback in this app process) | loads at once, no build | — |
| Cancel | tap *Shortest* or another chip during a build | build cancelled; Shortest: no restart; other method: new build | — |
| Timeout | `MethodSolveOutcome.TimedOut` | selection back to *Shortest*, "Couldn't build that solution in time. The shortest one is still here." until a chip tap or leaving | Shortest untouched |
| Failure | `MethodSolveOutcome.Failed` | selection back to *Shortest*, "Couldn't build a <method> solution for this cube. Showing the shortest." | Shortest untouched |
| Stale | session revision changed before completion | discarded, selection *Shortest*, no message | AD-3 |
| Physical | real cube off the input (after *Done*) | chips disabled, hint "Go back to step 1 to change style"; *Back* to step 1 re-enables | — |
| Physical choose | physical, at step 1 | method loads → session restarted then `ModeSwitched(PHYSICAL)`, still following, step 1 | — |
| Use my cube | re-solve from observed | style resets to *Shortest*, build cancelled, messages cleared | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState` (`currentIndex` :127, `note` :146, `alignToExpected` :160); `rememberSolutionPlaybackState` keyed on `session.solution` (:281-301) so a new list = new state at 0 paused (physical: aligned to Expected = 0). Virtual controls call `PlaybackTransport` (:449); physical counter row in `PhysicalPlaybackControls` (:500-511).
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- `PlaybackTransport(state, strings, modifier, note, onScrub, prominentCounter)` :242; counter row :311. Add an optional label above the counter row.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- header row + `SolutionPlayback`; chips go between them.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- pattern to copy: Hilt VM running a use case in `viewModelScope` with a revision guard (`accept` :200-265).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `AppShell` params (:133-160; `solveFlow` default built in `remember`), `solveOnSolved` :329-336 (dispatches `SolvePlaybackStarted(state, moves, at, tutorConfig, scanned)`), playback block :346-399 (`onUseMyCube` dispatches `SolvePlaybackStarted` + `ModeSwitched(PHYSICAL)` :370-372; `onClose`/`onLearn` end playback), `allStagesFinished` :700-705, `TwoLookMastery.evaluate` call :836-843, `progressEvents`/`attemptRecords` :217-218.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- `solveFlow by viewModels()` :63, `AppShell(...)` :294.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- `state: StateFlow<SessionState>`, `dispatch { at -> event }` :655.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` -- `SolveByMethod(bundle, deadlineMillis, solver, algorithmStatuses)`, `invoke(Effect.MethodSolve): MethodSolveCompleted(effectId, revision, outcome)`; outcomes `Solved(segments)|TimedOut|Failed`. Read-only.
- `core/application/src/main/kotlin/dev/cubentia/application/session/Effect.kt` -- `Effect.MethodSolve(effectId, revision, state, method)` :78. `Reduce.kt` `startPlayback` :420 (verifies `start.apply(solution).isSolved`, resets to virtual), `switchMode` :630 (VIRTUAL keeps `expected`). Read-only: no reducer/event change needed.
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` -- `SolveMethod`, `SegmentTag.OfStage(stageId)|OfCase(caseId)`, `MethodSegment(tag, moves: List<Move>)`. Labels: `Stage.title` (bundle.stages + twoLookStages), `Case.name` (bundle.cases) — both `TextKey`s.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/{StageProgress,TwoLookMastery}.kt` -- `StageProgress.status(stage, stages, events)`, `TwoLookMastery.evaluate(twoLookStages, attempts, events, solvesRequired).mastered`.
- `app/src/main/kotlin/dev/cubentia/app/di/{SolverModule,PersistenceModule}.kt` -- Hilt; `AlgorithmStatusRepository` provided at PersistenceModule.kt:88.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.playback.*` (:471-477). ICU via `MessageFormat` (`ContentStrings.resolve`).
- Tests to model on: `app/src/test/kotlin/dev/cubentia/app/ui/solve/{SolutionPlaybackTest,SolveFlowViewModelTest}.kt`, `ui/shell/AppShellSolveTest.kt`, `ui/shell/AppShellAlgorithmStatusRoomTest.kt` + `data/RoomAlgorithmStatusRepositoryTest.kt` (in-memory Room), `core/application/src/test/.../usecase/SolveByMethodTest.kt` (status-preference fixture).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add: `msg.playback.style.label` "Solution style", `.shortest` "Shortest", `.beginner` "Beginner", `.two-look` "2-look", `.full` "Full OLL + PLL", `.suggested` "Suggested", `.building` "Building a step-by-step solution…", `.timed-out` "Couldn't build that solution in time. The shortest one is still here.", `.failed` "Couldn't build a {method} solution for this cube. Showing the shortest.", `.locked` "Go back to step 1 to change style" -- AD-6 content text.
- `app/src/main/kotlin/dev/cubentia/app/solver/MethodSolverFactory.kt` (new) + `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` -- `fun interface MethodSolverFactory { fun create(bundle: ContentBundle): SolveByMethod }`; `@Provides @Singleton` it as `{ SolveByMethod(it, algorithmStatuses = repository) }` from the injected `AlgorithmStatusRepository` -- action item epic-15-deferred-15.2-wire-status-repository-in-15-3.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionStyles.kt` (new) -- `enum SolutionStyle(method: SolveMethod?)`; pure `suggestedStyle(bundle, progressEvents, attempts)`; `@HiltViewModel SolutionStyleViewModel(factory)` exposing `StateFlow` of (selected, building, message: Text?, segments of the loaded method) with `opened(input, shortest, fromAcceptedScan)`, `choose(style, bundle, revision: () -> Long, restart: suspend (List<Move>) -> Unit)`, `left()`; builds `Effect.MethodSolve(effectId, sessionRevision, input, method)` and runs it in `viewModelScope`; cache keyed (input facelets, method) for the process; applies the matrix rules -- AD-3, AD-21.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- chip row (`selectableGroup`, `Role.RadioButton`, tags `solutionStyle:<STYLE>`), "Suggested" badge, progress mark on the building chip, status line (`solutionStyle:message`, live region) for pending/timeout/failure/locked hint; chips disabled while locked -- UX-DR14.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` + `ui/playback/PlaybackTransport.kt` -- optional segment labels (list of segment end index → `Text`, used only when their Moves equal `session.solution`); show the current segment's name above the counter (`playback:segment`) in virtual and physical controls -- AD-20.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` + `MainActivity.kt` -- new `solutionStyles` param (default built in `remember` with `SolveByMethod(it)`; Activity passes `by viewModels()`); call `opened` in `solveOnSolved` and the "Use my cube" re-solve, `left` on close/learn; restart = `SolvePlaybackStarted(input, moves, fromAcceptedScan)` then `ModeSwitched(PHYSICAL)` when physical.
- Tests -- `app/src/test/.../ui/solve/SolutionStyleViewModelTest.kt` (matrix rows with a fake `SolveByMethod` solver: build, cache, cancel, timeout, failure, stale, use-my-cube reset, suggestion rule); `SolutionPlaybackTest`/`AppShellSolveTest` (chips shown, Shortest default, label above counter, physical lock + hint, Back re-enables); `app/src/test/.../di/MethodSolverWiringTest.kt` (Room in-memory `AlgorithmStatusRepository` through `SolverModule`'s provider: a *Learning* Algorithm wins).

**Acceptance Criteria:**
- Given Solution Playback from the Solve tab, when it opens, then *Shortest* plays by default and the chips offer *Beginner*, *2-look*, *Full OLL + PLL* with the progress-suggested one badged "Suggested".
- Given a method chosen, when its solution loads, then playback restarts at step 1 paused, the current Stage/Case name shows above the Move counter, notes still come only from `MoveNote`, and Physical-cube Mode keeps working with *Done*.
- Given the method Effect pending or timing out, when shown, then the pending copy shows and on timeout the timeout copy shows with *Shortest* kept.
- Given the app wiring, when `SolveByMethod` is built for playback, then it reads the real `AlgorithmStatusRepository`.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 40 findings — high 0, medium 3, low 24, false 13, maybe-false 0
- findings:
  - `[medium]` `[patch]` (verification-gap) Use my cube resetting the style is not checked in the shell — added assertions to playbackFromAnAcceptedScanPlansAtMostTwoFacesAndUseMyCubeReSolvesAFullMismatch: Shortest selected, Beginner enabled, Beginner restarts from the observed cube still physical.
  - `[medium]` `[patch]` (verification-gap) A style restart from an accepted scan is untested — added aStyleRestartFromAnAcceptedScanKeepsTheScanBaseline (touchedSinceVerified stays emptySet).
  - `[low]` `[defer]` (verification-gap) sessionMoved cancel only tested on the VM — VM revision check already discards the result; only spinner timing unpinned; deferred.
  - `[low]` `[reject]` (verification-gap) Cache ignores later Algorithm Status changes — PO answer 3 keys the cache per input state; needs the same cube re-solved after a status change; fix needs status observation.
  - `[low]` `[patch]` (verification-gap) Started-elsewhere branch unreachable and guesses fromAcceptedScan — deleted the LaunchedEffect(Unit) branch in AppShell.
  - `[false]` `[reject]` (blind) Style lifecycle leaves stale input on other exits — every SOLVE_PLAYBACK in :app starts through solveOnSolved or Use my cube, both call opened(); playback is full-screen, process death drops the VM.
  - `[low]` `[reject]` (blind) Process cache ignores statuses / unbounded — same as the cache row; entries are a few small lists per process.
  - `[false]` `[reject]` (blind) Silent revert after a session move — the spec's derived row (AD-3 stale discard, no message) is what the code does.
  - `[false]` `[reject]` (blind) Failed build while a method plays restarts Shortest — the only coherent reading of PO 7/11 (selection Shortest + "Showing the shortest") is to play Shortest.
  - `[false]` `[reject]` (blind) choose() does not enforce the lock — its only caller is the chip, disabled while locked.
  - `[low]` `[reject]` (blind) AppShell default VM outside a ViewModelStore without the repository — test-only default, same pattern as solveFlow; production passes the Hilt VM.
  - `[low]` `[reject]` (blind) Restart is two dispatches / reads runScanColors at restart — copies the existing Use my cube pattern; gap is one confined dispatch; held colors are those of the open playback.
  - `[low]` `[patch]` (blind) Started-elsewhere path guesses the scan flag — grouped with the deletion above.
  - `[low]` `[patch]` (blind) Rendered copy untested (nested {method}) — added SolutionStyleChipsTest asserting building line + progress mark, exact failure and timeout copy. Missing period and "2-look" naming are the PO's verbatim copy (false parts).
  - `[false]` `[reject]` (blind) Unknown Stage/Case gives no label — tags come from the same validated bundle MethodSolve read; unreachable.
  - `[low]` `[reject]` (blind) Live-region chatter on segment label — changes only at segment boundaries; the MoveNote line is already a polite live region; announcing the new Stage is useful.
  - `[low]` `[reject]` (blind) Fully qualified names instead of imports — matches existing AppShell style; ktlint passes.
  - `[low]` `[reject]` (blind) VM tests use Thread.sleep for negative assertions — can only false-pass, never flake red; scopes joined at teardown.
  - `[low]` `[reject]` (intent) Cache scope app-process vs playback — grouped with cache rows; PO said per input state.
  - `[low]` `[reject]` (intent) Wiring tested at the provider, not the Hilt graph — Hilt compiles the graph through that provider; a graph test needs new Hilt test infra.
  - `[low]` `[patch]` (intent) Copy and visual states tested only as VM state — grouped with SolutionStyleChipsTest.
  - `[medium]` `[patch]` (intent) Use my cube reset not exercised by UI — grouped with the first row.
  - `[low]` `[patch]` (intent) "Virtual switch does not unlock" untested — added to physicalStyleLocksAfterDone…: after Done and a switch to virtual, chips stay disabled with the hint.
  - `[false]` `[reject]` (intent) Restart with identical Moves may not return to step 1 — updateSession rewinds to 0 on a revision bump in virtual; physical restarts only at step 1.
  - `[false]` `[reject]` (intent) Notes under a method not tested — MoveNote path unchanged; note derives from currentMove for any list.
  - `[false]` `[reject]` (intent) AC1 "preselected" literal — PO answer 1 replaced it with the badge.
  - `[false]` `[reject]` (intent) Events during a build not PO-settled — AD-3 mandates discarding stale results; the end state equals PO 11's cancel.
  - `[false]` `[reject]` (edge) Stale input when a playback opens without left() — see lifecycle row.
  - `[low]` `[patch]` (edge) fromAcceptedScan inferred from touchedSinceVerified — grouped with the deletion.
  - `[low]` `[reject]` (edge) Style build finishing during a Use my cube re-solve drops the re-solve — needs a chip tap then Use my cube within a ≤3 s build in a mismatch; Learner can tap again; fix adds a new cancel surface.
  - `[low]` `[reject]` (edge) Untracked restart job after close — restartPlayback checks kind == SOLVE_PLAYBACK; race window is one confined dispatch.
  - `[low]` `[reject]` (edge) Tap before the previous restart dispatched cancels the new build — millisecond window.
  - `[low]` `[reject]` (edge) Overlapping restarts lose physical — same millisecond window.
  - `[low]` `[reject]` (edge) Restart guard no-op leaves chips showing a method — only reachable after close.
  - `[low]` `[reject]` (edge) Cache ignores status changes — grouped with cache rows.
  - `[false]` `[reject]` (edge) choose() lock not in VM — see blind row.
  - `[low]` `[reject]` (edge) Stale discard selects the playing style, matrix says Shortest — identical when Shortest plays; with a method playing the code is the coherent state; the fix would edit the spec.
  - `[false]` `[reject]` (edge) Shortest tapped during a build while a method plays restarts — PO 11 assumes Shortest playing; PO 5 says switching back to Shortest restarts.
  - `[low]` `[reject]` (edge) Use my cube does not cancel the build before the re-solve — grouped with the Use my cube race row.
  - `[false]` `[reject]` (edge) fallBack restart on failure — see blind failed-build row.

### 2026-11-22 — Review pass
- verdicts: 36 findings — high 0, medium 2, low 23, false 11, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Restart reads `solutionStyles.input` late, so a Use my cube re-solve in between pairs old Moves with the new input — carried: same as the logged "build finishing during a Use my cube re-solve" row; the reducer's `startPlayback` also rejects a solution that does not solve the entry.
  - `[false]` `[reject]` (blind) Autoplay bumps the session revision and silently cancels a build — virtual playback position is local; `SolutionPlayback` dispatches only ModeSwitched, Undo and PhysicalConfirmed, so stepping does not change the revision.
  - `[low]` `[reject]` (blind) Cache ignores Algorithm Status changes — carried: PO answer 3 keys the cache per input state.
  - `[low]` `[reject]` (blind) Cache has no size bound — carried: a few small lists per process.
  - `[false]` `[reject]` (blind) Locked hint is wrong after a switch to virtual — PO answer 9 settles that a virtual switch does not unlock and gives the hint copy verbatim.
  - `[low]` `[patch]` (blind) Selected chip uses disabled ink on the accent fill while locked (poor contrast, seen after every physical *Done*) — `StyleChip` now checks `selected` before `!enabled`, so a selected chip keeps `onAccent` ink; semantics still report it disabled.
  - `[low]` `[reject]` (blind) Restart may carry different scan colours than the original playback — carried: same as the logged "reads runScanColors at restart" row; the new verification-gap test now pins that the held colours survive.
  - `[low]` `[reject]` (blind) Physical restart is two dispatches, not atomic — carried: copies the Use my cube pattern; one confined dispatch gap.
  - `[low]` `[reject]` (blind) AppShell default VM is outside a ViewModelStore and lacks the repository — carried: test-only default; production passes the Hilt VM.
  - `[low]` `[reject]` (blind) Segment label live region announced on every step — carried: Compose announces live regions on content change, which is only at segment boundaries.
  - `[false]` `[reject]` (blind) Stage/Case missing from content gives a silent label — carried: tags come from the same validated bundle.
  - `[low]` `[reject]` (blind) Nested `Text` argument resolvable only by `resolveNested` — `SolvePlaybackScreen` is the only renderer of `state.message`; a shared resolver adds surface for no caller.
  - `[low]` `[reject]` (blind) `14.dp` progress size is a magic number — cosmetic; no named harm.
  - `[false]` `[reject]` (blind) `effectIds` repeat across playbacks — ids are only compared by identity within one VM (`pending != effect`); nothing logs or matches them elsewhere.
  - `[low]` `[reject]` (blind) Fully qualified names instead of imports, copied `@BindValue` block — carried: matches existing style; ktlint passes.
  - `[low]` `[reject]` (blind) No test for 2-look Stages skipped without the Course finished — that combination is the spec's suggestion rule and `SolutionStyleViewModelTest.theSuggestionFollowsProgress` pins the rule; another case is cosmetic.
  - `[false]` `[reject]` (blind) No test for choosing a style after *Done* then a Quick Check — chips are locked after *Done* (`expected != input`), so the path cannot be reached.
  - `[low]` `[reject]` (blind) `Thread.sleep` negative assertions — carried: can only false-pass, never flake red.
  - `[low]` `[defer]` (intent) Stale handling lives in the VM; `LaunchedEffect(session.revision)` link untested in the shell — carried: already in `deferred`; not deferred again.
  - `[low]` `[reject]` (intent) Wiring tested at the provider, not the Hilt graph — carried.
  - `[false]` `[reject]` (intent) Timeout/failure while a method plays restarts Shortest — carried: the coherent reading of PO 7/11.
  - `[false]` `[reject]` (intent) Cache keyed by facelets lets two orientations share a Move list — `toFacelets()` is the Learner-facing view and Face Moves act through `orientation`, so equal facelets give equal Move effects; `isSolved` ignores orientation.
  - `[low]` `[patch]` (intent) Timeout/failure copy checked only at VM level — carried: patched in the earlier pass with `SolutionStyleChipsTest`; not patched again.
  - `[low]` `[reject]` (intent) Real-device surface (paused start, physical following after the two-event restart) not exercised — needs a device; kept as a named residual risk.
  - `[low]` `[reject]` (edge) Two quick physical restarts lose physical mode — carried: millisecond window.
  - `[low]` `[reject]` (edge) Restart coroutine reads a swapped input — carried, grouped with the blind restart-input row.
  - `[low]` `[reject]` (edge) Cache ignores status changes — carried.
  - `[low]` `[reject]` (edge) Unbounded cache — carried.
  - `[false]` `[reject]` (edge) Identical Moves do not return to step 1 — carried: `updateSession` rewinds on a revision bump.
  - `[false]` `[reject]` (edge) `opened()` runs before a `SolvePlaybackStarted` the reducer ignores — both callers dispatch a Shortest already verified to solve that input, so `startPlayback` accepts it.
  - `[false]` `[reject]` (edge) `choose()` with null input leaves chips enabled but inert — carried: every `SOLVE_PLAYBACK` in :app starts through `opened()`.
  - `[low]` `[reject]` (edge) Default VM scope never cleared — carried.
  - `[low]` `[reject]` (edge) Stale discard selects the playing method, not Shortest — carried.
  - `[false]` `[reject]` (edge) Restart with identical Moves keeps old position — carried.
  - `[medium]` `[patch]` (verification-gap) A style restart from an accepted scan can drop the run-scan colours untested — `aStyleRestartFromAnAcceptedScanKeepsTheScanBaseline` now scans with non-null `runScanColors` and asserts `host.runScanColors()` before and after the Beginner restart.
  - `[medium]` `[patch]` (verification-gap) After Use my cube, the style restart's verified baseline is not asserted — the Use my cube test now asserts `touchedSinceVerified == emptySet()` and `observed == PhysicalObservation.Full(actual)` after the Beginner restart.

## Design Notes

No reducer, `SessionEvent` or `SessionState` change: a style switch is a new `SolvePlaybackStarted` on the same `SOLVE_PLAYBACK` kind (AD-3), as the "Use my cube" re-solve already does; style, cache and segments are ephemeral app state like playback position (AD-20). Process death never restores a `SOLVE_PLAYBACK` (`resumesOnColdStart`), so nothing is persisted. `SolveByMethod` already re-verifies (AD-21) and never calls the Solver.

## Verification

**Commands:** (env: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :app:testDebugUnitTest --tests '*SolutionStyle*' --tests '*SolutionPlayback*' --tests '*AppShellSolve*' --tests '*MethodSolverWiring*' --tests '*ContentStrings*'` -- expected: pass
- `./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** Solution Playback now has a *Solution style* chip row (Shortest · Beginner · 2-look · Full OLL + PLL) with a progress-based "Suggested" badge. A method solution is built on tap through `SolveByMethod` (real `AlgorithmStatusRepository`), cached per input for the app process, and restarts the same `SOLVE_PLAYBACK` at step 1 (re-entering physical mode when following); the current Stage/Case name shows above the Move counter in virtual and physical controls. Pending/timeout/failure copy and the physical lock hint follow PO answers 1–11. No reducer/event/state change.

**Files:**
- `core/domain/src/main/resources/content/strings/en.json` — 10 `msg.playback.style.*` strings.
- `app/src/main/kotlin/dev/cubentia/app/solver/MethodSolverFactory.kt` — factory interface + process cache (new).
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt` — provides the factory with the real repository.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionStyles.kt` — styles, suggestion rule, segment labels, `SolutionStyleViewModel` (new).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` — chip row and status line.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt`, `ui/playback/PlaybackTransport.kt` — segment label above the counter.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt`, `MainActivity.kt` — wiring, restart, Use my cube reset, lock.
- Tests: `SolutionStyleViewModelTest` (15), `SolutionStyleChipsTest` (1), `MethodSolverWiringTest` (1), `AppShellSolveTest` (+3), `SolutionPlaybackTest` (+1); testDebug route tests bind `MethodSolverFactory`.

**Review:** 40 findings (medium 3, low 24, false 13). Patched: 2 medium entries (shell tests for Use my cube reset and scan-baseline restart) and 4 low entries (deleted unreachable started-elsewhere branch, rendered-copy compose test, virtual-switch lock test). Deferred: 1 (sessionMoved shell pin). Rejected: all others with reasons in the triage log.

**Follow-up review recommended:** true — two medium entries patched (counts: high 0, medium 2, low 4). Named unverified risk: 2-look/Full solutions contain a leading cube rotation and wide/slice Moves; *Done* on those steps in Physical-cube Mode and their 3D rendering are only covered by the reducer, not on a device.

**Verification:** `./gradlew :app:testDebugUnitTest --tests '*SolutionStyle*' --tests '*SolutionPlayback*' --tests '*AppShellSolve*' --tests '*MethodSolverWiring*' --tests '*ContentStrings*'` passed; `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches). Matrix audit: every I/O row covered by a passing VM or shell test.

**Residual risks:** cache does not refresh after an Algorithm Status change in the same process (by PO answer 3); a style build finishing during a Use my cube re-solve can drop the re-solve (narrow timing); device check of rotation/wide Moves in physical mode pending.

### Follow-up review pass (2026-11-22)

**Summary:** Follow-up review of the 15.3 diff since `5fc87568`. No reducer or behavior change; tightened two shell tests and one chip colour.

**Files changed this pass:**
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` — scan-baseline test now carries run-scan colours and asserts they survive a style restart; Use my cube test asserts the restart keeps the Full verified baseline.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` — a selected chip keeps `onAccent` ink while locked.

**Review:** 36 findings (high 0, medium 2, low 23, false 11). Patched: 2 medium (verification gaps), 1 low (locked selected chip contrast). Deferred: none new (the `LaunchedEffect(session.revision)` item stays deferred). Rejected: all others, with reasons in the triage log (most carried from the first pass).

**Follow-up review recommended:** false — follow-up pass patched no high (patched counts: high 0, medium 2, low 1).

**Verification:** `./gradlew :app:testDebugUnitTest --tests '*SolutionStyle*' --tests '*SolutionPlayback*' --tests '*AppShellSolve*' --tests '*MethodSolverWiring*' --tests '*ContentStrings*'` BUILD SUCCESSFUL (AppShellSolveTest 32/32); `./gradlew spotlessApply && ./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** unchanged from the first pass — cache not refreshed after an Algorithm Status change in-process (PO 3); narrow Use my cube / restart timing; device check of rotation and wide/slice Moves in physical mode and of the two-event physical restart pending.
