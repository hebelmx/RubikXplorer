---
title: 'GH #178: a solved cube on the Solve tab says it is already solved instead of an empty playback'
type: 'bugfix'
created: '2026-11-17'
status: 'done'
baseline_revision: 'b23537bdd8be726647f8c46524fbf42f3ab54077'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred:
  - summary: >-
      A scan accepted in a non-HOME orientation with a scrambled cube opens the editor with rotated stickers but home-forced centers.
    evidence: |-
      ScanFlowContract.decode returns CubeValidator's state, whose orientation follows the scanned centers; ManualCubeEditorState forces home centers (ManualCubeEditorState.kt:27-28). Pre-existing, not caused by #178 (only solved scans are reoriented here). Settle by checking whether the guided scan can emit a non-HOME orientation on device.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt onScanOutcome
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** On the Solve tab, confirming an already-solved cube in the editor — whether it was opened from a scan or from manual Enter colors — starts a Solve Playback session with an empty solution, so the Learner sees "0 / 0", empty transport controls and "I'm following on my cube" (GH #178, Story 10.15 device check on Galaxy S24 FE).

**Approach:** When the editor confirms a cube whose cubies are all home (`CubeState.isSolved`, orientation ignored), the Solve flow skips the solver and shows a short Learner message in place of the editor, with one action back to the Solve home (scan or enter colors again) and one action on to the Learn tab. No `SolvePlaybackStarted` event is dispatched.

## Boundaries & Constraints

**Always:** Applies to both scan-opened and manual-entry editors (PO decision). The check runs after editor validation, on the confirmed `CubeState`, using `isSolved` (never `== CubeState.SOLVED`). All new Learner-facing text lives in `core/domain/src/main/resources/content/strings/en.json` and is resolved through `ContentStrings` with domain `Text(key)`; tone follows EXPERIENCE.md's Placement copy ("Your cube is solved — scramble it and scan again, or start Stage 1."). Existing test `AppShellSolveTest.solvedEditorEntryOffersLearnAndEndsPlayback` is rewritten to the new behavior, keeping its name. Other tests that relied on confirming a solved cube in the editor are adapted to feed a scrambled cube while keeping every one of their original assertions about their own subject.

**Never:** No change to `SolveFlowViewModel.accept` semantics (it is also the re-solve path of "Use my cube as it is now" during playback). No new `SessionEvent`, reducer, or persisted state. No strings in `strings.xml`. No edits under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/`, or `sprint-status.yaml`. Do not delete or skip existing tests. Do not change Placement or lesson flows.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved manual entry | Enter colors, confirm the default solved editor | `solve:alreadySolved` message shown; session kind never `SOLVE_PLAYBACK`; solver never loaded; nav bar visible | No error expected |
| Solved scan | Scan returns `ScanOutcome.Accepted` of a solved cube (also when held in a non-HOME orientation); Learner confirms in editor | Same as above | No error expected |
| Back to Solve | On the message, tap `solve:alreadySolved:again` (or system Back) | Solve home shown (`solve:enterColors` present), scan/enter possible again | No error expected |
| On to Learn | On the message, tap `solve:alreadySolved:learn` | Learn tab selected; Solve flow reset to Home | No error expected |
| Scrambled cube | Confirm a non-solved cube | Unchanged: solver runs, playback starts | Existing timeout/failure handling unchanged |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `SolvePhase` sealed interface (Home/Editing/Solving); `accept(state, onSolved)` at ~L118 runs the solver; `scanned` marks a scan-opened editor; `closeEditor()` resets to Home and bumps `revision`. Add a `SolvePhase.AlreadySolved` data object and an editor-confirm entry point (e.g. `confirm(state, onSolved)`) that, for `state.isSolved`, bumps `revision`, cancels `job`, stores `accepted`, clears `scanned`, and sets `AlreadySolved`; otherwise delegates to `accept`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- `when (phase)`; Editing branch calls `ManualCubeEditor(strings, { flow.accept(it, onSolved) }, ...)` — switch it to the new confirm entry point. Add the `AlreadySolved` branch (message with `liveRegion = Polite`, tag `solve:alreadySolved`; button tag `solve:alreadySolved:again` → `flow.closeEditor()`; button tag `solve:alreadySolved:learn` → new `onLearn` parameter). `BackHandler(phase != Home)` already routes non-Solving phases to `closeEditor()`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `SolveScreen(...)` call at ~L692; `var tab` at L197. Pass `onLearn = { solveFlow.closeEditor(); tab = Tab.LEARN }`. `solveFlow.accept` at ~L301 (onUseMyCube) stays on `accept`.
- `core/domain/src/main/resources/content/strings/en.json` -- solve keys at L3–43 (`msg.solve.*`, `btn.solve.*`). Add `msg.solve.already-solved` ("Your cube is already solved. Scramble it and try again, or learn to solve it yourself."), `btn.solve.again` ("Back to Solve"), `btn.solve.go-learn` ("Go to Learn").
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeState.kt:16` -- `isSolved` ignores orientation; reuse it.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- Robolectric shell tests. `solvedEditorEntryOffersLearnAndEndsPlayback` (L218) asserts the old playback; `physicalPlaybackQuickCheckAndScanAllReachTheCapture` and `lessonIsUntouchedDuringPreparationAndReturnsOnClose` click `editor:accept` on the default solved editor. Scrambled-editor pattern to reuse: `editorRetryAfterTimeoutStartsPlaybackOfTheEnteredCubeAndBackCloses` (solver `TimedOut` + `flow.accept(cube)` leaves the editor holding `cube`; then swap the solver). The scan test pattern: `playbackFromAnAcceptedScan...` (`scan = { flow.onScanOutcome(ScanOutcome.Accepted(cube)) }`, `click("solve:scan")`, `click("editor:accept")`).
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- JUnit5 VM tests with a fake `SolverSource` (`source(solver)` helper ~L44). `accept(CubeState.SOLVED)` at ~L241 stays valid since `accept` is unchanged.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add the three keys above -- Learner text per AGENTS.md.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- add `SolvePhase.AlreadySolved` and the confirm entry point described in the Code Map -- skip the solver for a solved cube.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- editor uses confirm; render the AlreadySolved branch; add `onLearn: () -> Unit` parameter -- the Learner-facing surface.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- pass `onLearn` to `SolveScreen` -- route to Learn.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- add tests: confirm of a solved cube (HOME and a non-HOME orientation) reaches `AlreadySolved` without loading the solver or calling onSolved and clears `isAcceptedScan()`; confirm of a scrambled cube behaves like accept; `closeEditor()` from AlreadySolved returns Home.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- rewrite `solvedEditorEntryOffersLearnAndEndsPlayback` (same name) to the matrix "Solved manual entry" + "On to Learn" rows; add a solved-scan test (scan Accepted of a solved cube in a non-HOME orientation, solver that fails the test if called) covering "Solved scan" + "Back to Solve"; adapt `physicalPlaybackQuickCheckAndScanAllReachTheCapture` and `lessonIsUntouchedDuringPreparationAndReturnsOnClose` to confirm a scrambled cube via the editor, keeping all their other assertions -- matrix coverage and no regressions.

**Acceptance Criteria:**
- Given the Solve tab with a solved cube in the editor (from scan or Enter colors), when the Learner confirms, then the message "Your cube is already solved…" is shown, the nav bar stays visible, `host.state.value.kind` is never `SOLVE_PLAYBACK`, and `solvePlayback:screen` does not exist.
- Given the already-solved message, when the Learner taps Back to Solve, then the Solve home with Enter colors (and Scan when enabled) is shown; when the Learner taps Go to Learn, then the Learn tab is shown.
- Given a scrambled cube, when confirmed, then playback starts exactly as before.
- Given the build, when `checkLearnerText` runs, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 17 findings — high 0, medium 3, low 8, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) ScreenAudit does not walk the AlreadySolved screen at 100%/200% type — added `check("Solve already solved")` after confirming the default solved editor, then Back to Solve.
  - `[low]` `[patch]` (blind) Audit's "Solve Enter colors" step became a scan-seeded editor — restored a manual default-editor `check("Solve Enter colors")`; the scan-seeded step is now labelled "Solve Enter colors scanned".
  - `[false]` `[reject]` (blind) No test for editing a solved scan into a scramble — `confirm` delegates unchanged to `accept` with `scanned` intact; scan-baseline playback is covered by `playbackFromAnAcceptedScan...`.
  - `[false]` `[reject]` (blind) `assertAlreadySolved` samples final state only — the solver source throws if loaded (`allowSolverLoad=false`) and `confirm` is synchronous, so `SolvePlaybackStarted` cannot be dispatched.
  - `[low]` `[reject]` (blind) No Go-to-Learn test with an active lesson — `onLearn` only calls `closeEditor()` and sets the tab; it never touches the session. Unlikely defect, extra test not worth it.
  - `[low]` `[patch]` (blind) Solved non-HOME confirm caches rotated facelets for the next Enter colors — `confirm` now stores `state.reoriented(HOME)`; VM test asserts home-frame reopening.
  - `[low]` `[patch]` (edge) AlreadySolved column could clip buttons at large font — added `verticalScroll(rememberScrollState())`.
  - `[medium]` `[defer]` (edge) Scrambled non-HOME scans meet home-forced editor centers — pre-existing, see `deferred`.
  - `[medium]` `[patch]` (verification-gap, Claude fallback after Codex misread the diff path) Audit gap for AlreadySolved — same root cause as the first row; same fix.
  - `[low]` `[patch]` (verification-gap other) VM test pins unreachable rotated cache — same root cause as the rotated-cache row; same fix.
  - `[low]` `[patch]` (verification-gap other) Audit label no longer matches content — same fix as the audit-label row.
  - `[false]` `[reject]` (intent) "Use my cube as it is now" re-solve not covered — the intent names only scan and Enter-colors editor entry on the Solve tab; `accept` is deliberately unchanged.
  - `[false]` `[reject]` (intent) Scan could show the message before the editor — intent says "after validation"; scans validate in the editor, matching the reported flow.
  - `[low]` `[reject]` (intent) Solved-scan reorientation exceeds literal ask — required for requirement (1): without it a rotated solved scan fails editor validation (confirmed by verification-gap layer).
  - `[false]` `[reject]` (intent) `isSolved` vs empty-solution trigger may differ — the solver input is cubies; `isSolved` is `cubies == Cubies.SOLVED`, so the two coincide.
  - `[low]` `[patch]` (intent) A11y audit omits the new screen — same root cause as the first row; same fix.
  - `[false]` `[reject]` (intent) Learner-text gate not shown as run — `checkLearnerText` ran and passed in verification.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck checkLearnerText :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.shell.AppShellSolveTest' --tests 'dev.cubentia.app.ui.solve.SolveFlowViewModelTest' --tests 'dev.cubentia.app.ui.a11y.*'` -- expected: BUILD SUCCESSFUL, all listed tests pass
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest :core:domain:test` -- expected: BUILD SUCCESSFUL (no regression in other app/content tests)

## Auto Run Result

Status: done

**Summary:** Confirming a solved cube in the Solve editor (manual or scan-opened, any orientation) now shows "Your cube is already solved. Scramble it and try again, or learn to solve it yourself." with Back to Solve and Go to Learn; the solver is never loaded and no Solve Playback session starts. Scrambled cubes are unchanged.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` — `SolvePhase.AlreadySolved`, `confirm()`, solved scans reoriented to home frame for the editor.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` — editor uses `confirm`; AlreadySolved message + two actions; `onLearn` param.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` — `onLearn` resets flow and opens Learn.
- `core/domain/src/main/resources/content/strings/en.json` — 3 new keys.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` — rewrote `solvedEditorEntryOffersLearnAndEndsPlayback`; added `solvedScanReturnsToSolveHome`; adapted two tests to scrambled editor input.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` — confirm tests.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt` — audits the manual editor and the AlreadySolved screen.

**Review:** 6 patch rows applied (1 medium root cause + 3 low root causes), 1 deferred (pre-existing non-HOME scrambled scan), 10 rejected (see triage log). Follow-up review recommended: false (patched: high 0, medium 1, low 3).

**Verification:** `spotlessCheck checkLearnerText` + targeted AppShellSolveTest (18), SolveFlowViewModelTest (14), a11y suites — pass; `:app:testDebugUnitTest :core:domain:test` — pass after patches.

**Residual risks:** Not device-tested (real camera scan path exercised only via injected `ScanOutcome.Accepted`).
