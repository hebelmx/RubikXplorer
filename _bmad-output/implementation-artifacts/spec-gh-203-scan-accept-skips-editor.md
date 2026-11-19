---
title: 'GH-203: the scan review Accept goes straight to solve and Solution Playback'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: '35dcaab3ecb70b950ffe2c5747421051bb7b5a86'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-19-scan-accept-skips-editor.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** From the Solve tab, a Guided or Quick Scan's review *Accept* opens the Manual Cube Editor with the same net, so the Learner confirms the same cube twice (#203). The review must be the one and only confirmation (SCP 2026-11-19, PO decisions 1–4).

**Approach:** `SolveFlowViewModel.onScanOutcome(Accepted)` stores a pending accepted scan instead of opening the Editor; `AppShell` consumes it exactly once when the `LessonCatalog` is `Ready` and runs the existing `solveFlow.confirm(state, onSolved)`. A scan-started solve that is cancelled, times out or fails falls back to the Editor with the scan reoriented to `Orientation.HOME`, still counting as the scan baseline until `closeEditor` or leaving the Solve tab. The pause note also shows on Solve Home.

## Boundaries & Constraints

**Always:** `confirm` decides solved vs unsolved (solved → `SolvePhase.AlreadySolved`, Solver not loaded). `onSolved` reads `isAcceptedScan()` and dispatches `SolvePlaybackStarted(..., scanned)` exactly as the Editor's *Accept* path does. A pending scan is taken atomically (read-and-clear) so recomposition or recreation never consumes it twice. While the catalog is `Loading` or `Failed`, the scan stays pending and nothing is dispatched. `scanned` is kept through cancel, `TimedOut`, `Failed` and Editor edits; cleared on `closeEditor`, on leaving the Solve tab, and (unchanged) on a successful solve, a solved `confirm`, `enterColors`, `scanDenied`, `FellBackToEditor`.

**Never:** No change to `FellBackToEditor`, `scanDenied`, `enterColors`, `ScanOutcome.Cancelled`, `ScanFlowContract`, Placement, Quick Check, `GuidedScanActivity`, or `SessionEvent`/reducer. No new Learner text (reuse `msg.solve.pauses-lesson`, tag `solve:pauseNote`). No planning-artifact edits. Do not solve inside `MainActivity`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Unsolved scan accepted | Solve tab, catalog Ready, `Accepted(scrambled)` | `Solving` → `SolvePlaybackStarted(…, fromScan=true)` → Solution Playback; Editor never shown | — |
| Solved scan accepted | `Accepted(SOLVED in any orientation)` | `SolvePhase.AlreadySolved`; Solver not loaded; no playback | — |
| Catalog still loading | `Accepted` while `CatalogState.Loading` | Scan stays pending, no dispatch, no Editor; once Ready, solved exactly once | Failed catalog: stays pending |
| Scan solve cancelled / TimedOut / Failed | scan held in non-home orientation | `Editing(state.reoriented(HOME).toFacelets(), recovery?)`; `isAcceptedScan()` true; retry from Editor (edited or not) starts playback with `fromScan=true` | — |
| Close or leave tab after fallback | `closeEditor()` or tab ≠ SOLVE | `isAcceptedScan()` false; a later manual entry is not the baseline | — |
| Fallbacks | `FellBackToEditor`, `scanDenied`, `enterColors` | Editor opens as today with existing notes | — |
| Lesson open on Solve Home | `pausesLesson == true`, phase Home | `solve:pauseNote` shows next to *Scan my cube* / *Enter colors* | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `onScanOutcome` (L61–81) sets `Editing` for `Accepted`; `accept` (L134) stores `accepted = state.toFacelets()` (keeps the scan's orientation) used by `cancel` (L112), `TimedOut`/`Failed` (L188–194). `confirm` (L118) already handles solved → `AlreadySolved` with HOME reorientation. `CubeState.reoriented(o)` = `copy(orientation = o)` (core/domain CubeState.kt:56), a no-op for Editor (home-frame) states.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt:47–54` -- reuse pattern: `acceptedScan` StateFlow set in `onScanOutcome(Accepted)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `ready` at L307; placement consumer `LaunchedEffect(ready, placementScanState, placementFlow)` at L315 (put the solve-scan consumer next to it, before the `SOLVE_PLAYBACK` early return); Solve tab `onSolved` lambda at L831–837 (extract to one shared lambda used by SolveScreen and the consumer); `LaunchedEffect(tab) { if (tab != Tab.SOLVE) solveFlow.cancelSolving() }` at L675 (also clear the scan there); `pausesLesson = session.kind?.takesMoves == true` passed to SolveScreen.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- Home branch (L62) lacks the pause note; Editing (L102) and Solving (L136) show it with `Modifier.padding(horizontal = Spacing.s4).testTag("solve:pauseNote")`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt:150–151` -- calls `solveFlow.onScanOutcome`; unchanged.
- `app/src/main/kotlin/dev/cubentia/app/content/LessonCatalog.kt` -- `internal constructor(loadContent, io)` lets AppShell tests gate loading.
- Tests: `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` (L78–184 Accepted cases), `app/src/testDebug/kotlin/dev/cubentia/app/SolveFromScanRouteTest.kt` (L113–163 two "…AfterValidatingInTheEditor" tests), `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` (L82–224, asserts `SolvePhase.Editing` ~L202), `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt:132` (asserts Editing after accepted SOLVED scan), `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt:193–203` ("Solve Enter colors scanned" then `editor:accept`; gated `loadGate`/`solveGate` solver), `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` (L287 `solvedScanReturnsToSolveHome`, L486 `playbackFromAnAcceptedScan…` click `editor:accept`; L544 lesson pause-note test).

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- add `pendingScan: StateFlow<CubeState?>` and `takePendingScan(): CubeState?` (atomic read-and-clear); `Accepted` sets `scanned` and `pendingScan`, leaves phase unchanged; `accept` stores `accepted = state.reoriented(Orientation.HOME).toFacelets()`; stop clearing `scanned` only where the SCP says keep (cancel/TimedOut/Failed already keep it); add `leaveSolveTab()` that runs `cancelSolving()` then clears `scanned` and `pendingScan`; update the `isAcceptedScan` KDoc. -- single confirmation, home-frame fallback, deterministic baseline.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- collect `pendingScan`; `LaunchedEffect(ready, pendingScan)` → when `ready != null`, `takePendingScan()?.let { solveFlow.confirm(it, solveOnSolved) }`; share one `onSolved` lambda with SolveScreen; replace the tab effect with `solveFlow.leaveSolveTab()` when `tab != Tab.SOLVE`. -- hand-off once content is ready.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- show the pause note on Home when `pausesLesson`. -- Story 3.7 AC 1 / EXPERIENCE.md:110.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- replace Accepted→Editing assertions: Accepted keeps phase Home, sets pending once (second take is null), `isAcceptedScan()` true; cancel/TimedOut/Failed of a scan in `Orientation.of(Face.U, Face.R)` hold reopen `Editing(reoriented HOME facelets)` with `isAcceptedScan()` still true and a retry reports `fromScan=true`; `closeEditor` and `leaveSolveTab` clear it.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- scan tests no longer click `editor:accept`: solved scan → AlreadySolved directly, unsolved scan → playback with `editor:accept` never shown; add a pending-while-loading test (gated `LessonCatalog` internal constructor: no dispatch and no Editor while Loading, playback exactly once after Ready); assert `solve:pauseNote` on Solve Home with a Lesson open.
- `app/src/testDebug/kotlin/dev/cubentia/app/SolveFromScanRouteTest.kt` -- replace the two "…AfterValidatingInTheEditor" tests with `acceptedScanGoesStraightToPlayback` (no `editor:accept` node, playback shown, `observed == Full(cube)`) and `solvedScanShowsAlreadySolvedWithoutTheEditor`.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- rename/adjust `scanCalibrationRescanReturnsCorrectedStickersToSolveEditor` to assert the corrected scan reaches the Solve flow without the Editor (pending consumed: phase not `Editing`, `editor:accept` absent, the solve starts with the corrected cube); keep the diagnostics-record assertions.
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- at L132 assert `SolvePhase.AlreadySolved` (accepted SOLVED) instead of `Editing`.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt` -- deliver the accepted scan from Solve Home (no `solve:enterColors` click, no `editor:accept` click); the flow goes straight to "Solve preparing"; keep the remaining audited screens.

**Acceptance Criteria:**
- Given the Solve tab with content Ready, when the scan review's *Accept* returns `Accepted`, then the Manual Cube Editor never opens and the outcome is Solution Playback (unsolved) or the AlreadySolved message (solved).
- Given the scan fallbacks (`FellBackToEditor`, denied camera, *Enter colors*), when they return to Solve, then the Editor opens with today's notes.
- Given the full JVM test suite, when it runs, then the updated SolveFlowViewModel, AppShellSolveTest and route tests verify the single-review flow and pass.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply` -- formatting applied
- `./gradlew :app:testDebugUnitTest --tests '*SolveFlowViewModelTest' --tests '*AppShellSolveTest' --tests '*SolveFromScanRouteTest' --tests '*ScanRouteTest' --tests '*QuickScanRouteTest' --tests '*ScreenAudit*' --tests '*A11y*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 15 findings — high 0, medium 1, low 13, false 1, maybe-false 0
- findings:
  - `[low]` `[patch]` (intent-alignment) Fallback-after-failed-scan-solve guarantees were only checked in the ViewModel, not through the shell's Editor — added `AppShellSolveTest.aFailedScanSolveRetriedFromTheEditorKeepsTheScanBaseline` (home-frame Editor, Editor Accept publishes `Full` baseline).
  - `[low]` `[patch]` (intent-alignment) Leaving-Solve clearing was only tested by calling `leaveSolveTab()` directly — added `AppShellSolveTest.leavingSolveAfterAFailedScanSolveDropsTheScanBaseline` (tab switch, then Editor Accept is not a scan baseline).
  - `[low]` `[reject]` (blind) `enterColors`/`scanDenied`/`FellBackToEditor` leave `pendingScan` queued — those actions are only reachable while SolveScreen renders (catalog Ready), where the consumer takes the scan in the same frame; the intent keeps those three paths unchanged and the fix adds clears to them.
  - `[low]` `[reject]` (blind) Scan result arriving after leaving Solve queues a stale scan — GuidedScanActivity covers MainActivity, so only the short permission-check window before launch allows a tab change; fix needs a new discard branch.
  - `[low]` `[reject]` (blind) No recreation test for exactly-once consumption — `takePendingScan` is an atomic `getAndUpdate { null }` on a ViewModel that survives recreation.
  - `[low]` `[reject]` (blind) No `Loading → Failed` catalog test — the consumer is guarded by `ready != null`, which a Failed catalog never yields.
  - `[low]` `[patch]` (blind) No shell-level corrected-retry baseline test — same fix as the intent-alignment fallback row.
  - `[low]` `[reject]` (blind) Scrambled Quick Scan result not delivered to MainActivity — Quick Scan uses the same `solveFlow.onScanOutcome` path covered by `SolveFromScanRouteTest`/`ScanRouteTest`; its solved delivery is already asserted.
  - `[false]` `[reject]` (blind) ScreenAudit lost the scan-populated Editor audit — "Solve Enter colors retry" still audits the Editor reopened after the scan-started solve times out.
  - `[low]` `[reject]` (edge) Stale scan after leaving Solve during the permission check — same as the blind stale-scan row.
  - `[medium]` `[patch]` (edge) A pending scan could start playback before the cold-start restore finished (process recreated while scanning; `welcome` is saveable so nothing awaits restore) — consumer now calls `host.awaitRestored()` before `takePendingScan()`.
  - `[low]` `[reject]` (edge) Editor entry superseding a queued scan — same as the blind pending-not-cleared row.
  - `[low]` `[patch]` (verification-gap) Leaving Solve not verified through navigation — added `leavingSolveAfterAFailedScanSolveDropsTheScanBaseline`.
  - `[low]` `[patch]` (verification-gap) Corrected scan retry baseline not asserted at playback — added `aFailedScanSolveRetriedFromTheEditorKeepsTheScanBaseline`.
  - `[low]` `[patch]` (verification-gap) Non-home scrambled scan success not covered — added `aScanHeldOffHomeStartsPlaybackInItsOwnFrame` (entry and `Full` observation keep the held frame).

## Auto Run Result

Status: done

**Summary:** From the Solve tab, an accepted scan no longer opens the Manual Cube Editor. `SolveFlowViewModel` queues it as `pendingScan`; `AppShell` takes it once (content Ready, Solve tab, cold-start restore finished) and runs `confirm`, so an unsolved cube goes to Solution Playback with the scan as the Full verified baseline and a solved cube shows AlreadySolved. A cancelled, timed-out or failed scan-started solve reopens the Editor in the home frame and keeps the baseline until `closeEditor` or leaving the Solve tab. The pause note now also shows on Solve Home.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- pending scan, `takePendingScan`, `leaveSolveTab`, home-frame fallback state.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- pending-scan consumer, shared `onSolved`, leave-tab clearing.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- pause note on Solve Home.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- single-review, fallback, baseline-clearing cases.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- scan tests without Editor, catalog-loading, off-home frame, retry baseline, leave-tab, pause note.
- `app/src/testDebug/kotlin/dev/cubentia/app/SolveFromScanRouteTest.kt`, `ScanRouteTest.kt`, `QuickScanRouteTest.kt` -- route tests end at playback / AlreadySolved.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt` -- scan from Solve Home goes straight to preparing.

**Review:** 15 findings; patched 1 medium (cold-start restore race) and 6 low (test gaps, grouped into 3 new shell tests + the restore guard); 0 deferred; 8 rejected (7 low, 1 false) with reasons in the triage log. Follow-up review: not recommended (no high, one medium patched).

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches); `AppShellSolveTest` 26/26, `SolveFlowViewModelTest` 16/16, `SolveFromScanRouteTest` 8/8, `ScanRouteTest` 3/3, `QuickScanRouteTest` 4/4, a11y audits pass.

**Residual risks:** device verification deferred to the next device session; a scan result returning after a tab change in the brief pre-launch permission-check window is not discarded (rejected low).
