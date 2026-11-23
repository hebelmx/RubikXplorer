---
title: 'GH #262: Solver preparing copy makes no claim about later solves'
type: 'bugfix'
created: '2026-11-22'
status: 'done'
baseline_revision: '5526fe9792f91837a42372f3c5364a27eba8ecf4'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `msg.solve.preparing` reads "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster." No release-equivalent measurement backs "Later solves are faster" (the only device data, a debug same-process second solve, was not faster). What the code does guarantee — the preparing line is skipped on later solves in the same process — has no end-to-end test.

**Approach:** Set the copy to exactly "Preparing the solver." (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-g), update the pinned tests and the current-text clauses in two docs, and add one Robolectric JVM test that makes two accepts in one process against a SolverSource that keeps its solver.

## Boundaries & Constraints

**Always:** The new value is exactly `Preparing the solver.`. Keep the key `msg.solve.preparing`, the `solve:preparing` tag, Cancel and the WARMING_UP / FIRST_USE / NONE preparation states unchanged. Dated history (`docs/evidence/**`, dated run notes, the #112 and #123 reword sentences as history, `_bmad-output/**`) stays; only clauses that state the *current* copy change. Commit subjects carry "#262".

**Never:** Add any claim about how long later solves take or that they are faster, anywhere (copy, docs, test names, comments). Change `msg.solve.preparing-first-use` or any other string. Change production Kotlin. Edit `_bmad-output/planning-artifacts/**`, `sprint-status.yaml`, `_bmad/`, `.claude/skills/`. Push or open PRs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First solve in process | Source not ready, tables read (not generated) | `solve:preparing` shows exactly "Preparing the solver." with progress and Cancel | Cancel returns to the editor (existing) |
| Second solve, same process | Same source instance, it kept its solver after the first load | Solving shows (`solve:progress`) with no `solve:preparing` node | No error expected |
| First-use generation | Source not ready, generating | "Setting up the solver for first use. This can take a little while." (unchanged) | Unchanged |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json:89` -- `msg.solve.preparing` value to replace; `:90` first-use string is read-only.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt:469-471` (`leavingSolveCancelsARunningSolve`) and `:806-808` (`lessonIsUntouchedDuringPreparationAndReturnsOnClose`) -- pinned old text.
- `AppShellSolveTest.kt:122-139` -- shared fake `SolverSource`: `isReady` = `ready`; `load` awaits `loadGate` while not ready and returns `SolverLoad.Ready(solver)`, but never flips `ready` (it does not "keep its solver"). Helpers: `show()` :160, `click()` :201, `solve()` :209, `seedScrambledEditor()` :368.
- `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt:25-42` -- read-only: production `PruningTableSolverSource` keeps the loaded solver; `isReady` = solver != null.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt:208` -- read-only: `accept` shows `Solving(WARMING_UP)` only when `!source.isReady`, else `Solving(NONE)`.
- `app/src/main/kotlin/dev/cubentia/app/di/SolverModule.kt:45` -- read-only: `@Singleton` source, one per process.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt:157-163` -- read-only: WARMING_UP renders `msg.solve.preparing` under `solve:preparing`; NONE renders no preparing line.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt:78-101` -- reuse pattern: a fake source that sets `solverReady = true` after its gated load, and a solver held by a `NonCancellable` `solveGate` so the solving state is observable.
- `docs/benchmarks.md:708` -- limitation #2 row: "`msg.solve.preparing` now reads "...Later solves are faster." (... #123)" is a current-text clause. `:702` ("promises no duration (#123)") stays.
- `docs/pruning-tables.md:180` -- #104 Known issues bullet: the #123 sentence says "now reads" the old text.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- set `msg.solve.preparing` to `Preparing the solver.` -- PO Q-g.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- (a) change both pinned assertions to `"Preparing the solver."`; (b) add a test (e.g. `preparingShowsOnTheFirstSolveOnlyWhenTheSourceKeepsItsSolver`) with its own local `SolverSource` that, like `PruningTableSolverSource`, reports not ready until its first gated load completes and then stays ready (if the test needs the shared `flow`, give the shared fake an opt-in "keeps its solver" switch instead, leaving other tests' behaviour unchanged). Accept a scrambled cube: `solve:preparing` shows exactly "Preparing the solver."; release the load; playback starts; close playback. Accept again in the same process with the solver held (NonCancellable gate, as in ScreenAudit): `solve:progress` exists and `solve:preparing` does not exist, and the first-load counter shows the source loaded its tables only once; release; playback starts -- end-to-end test for the code-backed skip.
- `docs/benchmarks.md` -- in row #2 replace the current-text clause with: `msg.solve.preparing` now reads "Preparing the solver." (`core/domain/src/main/resources/content/strings/en.json`, #262; earlier wording #123) -- current text must match the copy.
- `docs/pruning-tables.md` -- in the #104 bullet change the #123 sentence's "now reads" to "then read", and append: Reworded again by #262 (SCP 2026-11-22 quick-scan-corners-and-solver-copy): `msg.solve.preparing` now reads "Preparing the solver."; `msg.solve.preparing-first-use` is unchanged. -- record the reword without a speed claim.

**Acceptance Criteria:**
- Given the Solve tab and a source that is not ready, when the Learner accepts a cube, then `solve:preparing` shows exactly "Preparing the solver.".
- Given the same process after one completed solve against a source that keeps its solver, when the Learner accepts a second cube, then the solving state shows with no `solve:preparing` node.
- Given the repo outside `_bmad-output/**` and `docs/evidence/**`, when grepping "Later solves are faster", then the only match is the dated #123 history sentence in `docs/pruning-tables.md` (past tense), and no file states it as current behaviour.
- Given this change, when `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read` is assessed, then the assessment below holds and `sprint-status.yaml` is unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 15 findings — high 0, medium 0, low 8, false 7, maybe-false 0
- findings:
  - `[low]` `[patch]` Edge: second solve held over 3 s of real time hits SolveCube's deadline (TimedOut, playback never starts) and the comment claimed the deadline cannot end it — comment reworded to say the gate only keeps the solving state held and must be released within the 3 s deadline (as ScreenAudit notes); release stays immediately after the checks.
  - `[low]` `[reject]` Edge: overlapping loads while not ready make `tableLoads++` non-atomic and double-count — the new test never overlaps loads (second accept happens after playback closes); a Mutex/AtomicInteger adds complexity for a path no test reaches.
  - `[low]` `[patch]` Blind: timing race with the 3 s deadline undocumented — same root cause as the first Edge row; same comment fix.
  - `[low]` `[reject]` Blind: test name implies a not-keeping counterpart is tested — the name describes the setup; a counter-test with `keepsSolver = false` is coverage beyond the intent's single two-accept test.
  - `[false]` `[reject]` Blind: `tableLoads` says nothing about the production source — `PruningTableSolverSourceTest.kt:26-30` already checks the real source is ready after load and returns the same solver on the second load.
  - `[false]` `[reject]` Blind: inconsistent volatility of shared fake flags — `keepsSolver`/`generating`/`allowSolverLoad` are written before `accept` launches the load, so coroutine dispatch gives happens-before; no concurrent increments occur.
  - `[low]` `[reject]` Blind: dropping "The first solve after the app starts takes longer" loses a backed claim — the intent (PO Q-g) requires exactly "Preparing the solver." with no timing claim; out of scope by intent.
  - `[low]` `[reject]` Blind: `docs/benchmarks.md` row lead-in and line 702 credit only #123 — both statements remain true; the row now cites #262; cosmetic.
  - `[low]` `[reject]` Blind: pruning-tables history sentence long and unlinked — cosmetic; matches the existing #112/#123 sentence style.
  - `[false]` `[reject]` Blind: action-item assessment only in an untracked spec — the spec is committed at finalize with the change.
  - `[false]` `[reject]` Blind: `solve:preparing` absence checked only once — `onGenerating` can switch to FIRST_USE only while the phase is WARMING_UP (`SolveFlowViewModel.kt` accept), and the second accept starts in NONE, so the line cannot appear later in that solve.
  - `[false]` `[reject]` Verification-gap: no gaps found — nothing to act on.
  - `[false]` `[reject]` Intent: test uses a fake source, not `PruningTableSolverSource`/Hilt scope — the intent asks for "a SolverSource that keeps its solver"; the real source's keep behaviour is covered by `PruningTableSolverSourceTest`; the test drives the real ViewModel line 208 and SolveScreen.
  - `[low]` `[reject]` Intent: `_bmad-output/implementation-artifacts/epic-7-context.md:69` still quotes the old copy as required — a compiled context cache, invalidated because planning artifacts are newer; the intent forbids adding a claim, not regenerating caches.
  - `[false]` `[reject]` Intent: criterion 3 lives outside the diff — the spec with the assessment is committed with this change.

## Design Notes

**Action item `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read` — assessment: resolved by this change (recommend closing).** The item (from Story 3.7, when tables were generated and the copy said "one time, a few seconds") asked to show the line only while tables are built, because it also showed during a cold-process cache read. Since Story 3.8 tables are bundled: every new process reads the asset and builds move tables (about 7.6 s on the Tab S7 FE, up to 10.5 s on the Lenovo, release-equivalent), so the cold read *is* the real preparation, and generation already has its own copy (`msg.solve.preparing-first-use`, FIRST_USE). The remaining mismatch was the copy's claims ("one time", then "later solves are faster"); "Preparing the solver." makes no claim about frequency or duration, so it is true during a cold read. Hiding the line during a cold read would leave a 10 s wait unexplained. The line still never shows once the process holds its solver (new JVM test). The tracker entry is left for the sprint owner to close.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests '*AppShellSolveTest*'` -- expected: BUILD SUCCESSFUL, new test passes.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `rg -n "Later solves are faster" -g '!_bmad-output/**' -g '!docs/evidence/**'` -- expected: only the dated #123 sentence in `docs/pruning-tables.md`.

## Auto Run Result

**Summary:** `msg.solve.preparing` now reads exactly "Preparing the solver." (PO Q-g, #262). The two pinned `AppShellSolveTest` assertions and the current-text clauses in `docs/benchmarks.md` (limitation #2) and `docs/pruning-tables.md` (#104 bullet) match it. A new Robolectric test, `preparingShowsOnTheFirstSolveOnlyWhenTheSourceKeepsItsSolver`, makes two accepts in one process against a fake source that keeps its solver after its first load: the first shows "Preparing the solver.", the second shows the solving progress with no `solve:preparing` node and the tables loaded once. No production Kotlin changed.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- new `msg.solve.preparing` value.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- pinned text updated; opt-in `keepsSolver` switch and `tableLoads` counter on the shared fake; new two-accept test.
- `docs/benchmarks.md` -- limitation #2 current text now "Preparing the solver." (#262).
- `docs/pruning-tables.md` -- #123 sentence moved to past tense; #262 reword sentence appended.

**Action item `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read`:** resolved by this change; recommend the sprint owner close it (reasoning in Design Notes). `sprint-status.yaml` not edited.

**Review:** 15 findings (high 0, medium 0, low 8, false 7). Patched 1 entry (low: the second-solve comment about SolveCube's 3 s deadline, two findings). Deferred 0. Rejected the rest with reasons in the Review Triage Log. Follow-up review recommended: false (patched counts: high 0, medium 0, low 1).

**Verification:** `./gradlew :app:testDebugUnitTest --tests '*AppShellSolveTest*'` -- BUILD SUCCESSFUL, 34 tests, 0 failures, 0 skipped (new test included); `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL before and after the patch; `rg "Later solves are faster"` outside `_bmad-output/**` and `docs/evidence/**` -- only the past-tense #123 sentence in `docs/pruning-tables.md`. Matrix audit: first solve (new test, `leavingSolveCancelsARunningSolve`, `lessonIsUntouchedDuringPreparationAndReturnsOnClose`), second solve (new test), first-use (`firstUsePreparationShowsCopyAndCancelKeepsColors`) all ran and passed.

**Residual risks:** the new test's held second solve must be released within SolveCube's 3 s wall-clock deadline (same exposure as ScreenAudit); a very slow runner could flake. The test uses a fake source; the production source's keep behaviour rests on `PruningTableSolverSourceTest` and `@Singleton` wiring. Not run on a device.
