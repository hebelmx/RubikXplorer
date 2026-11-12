---
title: 'GH #112: truthful solver-preparing copy, with a first-use state for table generation'
type: 'bugfix'
created: '2026-11-11'
status: 'done'
baseline_revision: '36bafb3e00622342e051da66b329ca15717b43ab'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-11-preparing-copy-and-benchmark.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Solve tab says "Preparing the solver (one time, a few seconds)." but preparation takes ~8 s on every new app process on the minimum device, so the copy is false (#112). The code also cannot tell the per-start warm-up from first-use table generation: `SolvePhase.Solving(preparing: Boolean)` is set before `load()`, and `PruningTableSource` is only known after load finishes.

**Approach:** Reword `msg.solve.preparing` and add `msg.solve.preparing-first-use` (texts fixed by the approved SCP 2026-11-11 §4.3). `PruningTableProvider.load` takes an `onGenerating` callback invoked right before the generate path starts; `SolverSource.load` passes it through; `SolvePhase.Solving` carries a three-state preparation (`NONE` / `WARMING_UP` / `FIRST_USE`); `SolveScreen` picks the string key from it.

## Boundaries & Constraints

**Always:**
- Exact copy: `msg.solve.preparing` = "Preparing the solver. This can take a few seconds the first time after the app starts."; `msg.solve.preparing-first-use` = "Setting up the solver for first use. This can take a little while." Both in `core/domain/src/main/resources/content/strings/en.json`, resolved via `ContentStrings`.
- The callback fires only after both the bundled read and the cache read missed, immediately before generation; never on BUNDLED or CACHE paths, never when `ready` is already cached.
- The VM applies the first-use signal only for the current attempt (`attempt == revision`) and only while the phase is still `Solving(WARMING_UP)`; a stale attempt's signal is dropped. State writes happen on the VM's main context (hop via `viewModelScope.launch` or equivalent), not from the provider's worker thread.
- Both preparing texts render with test tag `solve:preparing`; progress bar and `solve:cancel` Cancel behaviour unchanged.
- Callback parameter has a no-op default so existing provider/source callers compile unchanged.

**Never:** No change to table file format/version, timeouts, startup timing, preloading, or move-table delivery (that is #104). No edits under `_bmad-output/planning-artifacts`, `_bmad/`, `.claude/skills/`. Do not rewrite `docs/pruning-tables.md` lines ~140/~151 (dated 3.8 evidence).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Bundled path | valid bundled asset | no callback; UI shows `msg.solve.preparing` while loading | — |
| Cache path | no/invalid bundle, valid cache file | no callback; `msg.solve.preparing` | — |
| Generate path | no valid bundle, no valid cache | callback fires once before generate; UI switches to `msg.solve.preparing-first-use` | generation failure still yields `Failed` → editor error as today |
| Already ready | `source.isReady` true | `Solving(NONE)`, no preparing text | — |
| Stale signal | attempt cancelled/superseded before callback | new phase not changed by old signal | dropped |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json:3` -- `msg.solve.preparing`; add the new key next to it.
- `core/solver/src/main/kotlin/dev/cubentia/solver/PruningTableProvider.kt` `load()` -- order: bundled → `read()` cache → `else` branch with `generate` on `cpuDispatcher`. Invoke callback at the start of that `else` branch (inside the mutex, after cache miss).
- `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt` -- `SolverSource.load()` interface and `PruningTableSolverSource`; add `onGenerating: () -> Unit = {}` and forward to `provider.load(onGenerating)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt:34-36,79,92` -- `SolvePhase.Solving(preparing: Boolean)`; `accept()` sets `Solving(!source.isReady)` then `Solving(false)` after Ready; `revision` guard pattern.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt:64` -- renders preparing text with tag `solve:preparing`.
- Test fakes overriding `SolverSource.load()` (must adopt new signature): `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt:48,111`, `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt:108`, `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt:84`. `SolveFlowViewModelTest.kt:82,85,202` assert `Solving(true/false)`.
- `core/solver/src/test/kotlin/dev/cubentia/solver/PruningTablesTest.kt` -- provider tests; `validFileBytes()` helper and `bundled = { ByteArrayInputStream(asset) }` pattern (~:215-230) for BUNDLED/CACHE setups; `generate = { tables }` for GENERATED.
- `app/src/test/kotlin/dev/cubentia/app/solver/ProductionTableDeliveryTest.kt` -- builds `PruningTableSolverSource` with injectable `generate`/`bundled`; reuse if a pass-through test is natural.
- `docs/pruning-tables.md:173-180` -- Known issues bullet for #104 quotes old copy and says "The copy is unchanged".
- No test pins the old copy text (SCP §2); `ScreenAudit` uses the tag only.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- reword `msg.solve.preparing`; add `msg.solve.preparing-first-use` -- SCP §4.3.
- `core/solver/src/main/kotlin/dev/cubentia/solver/PruningTableProvider.kt` -- `suspend fun load(onGenerating: () -> Unit = {})`; call it right before generation; update KDoc -- lets the UI know before the slow path.
- `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt` -- add the parameter to the interface and pass through -- seam for the VM.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- add `enum class Preparation { NONE, WARMING_UP, FIRST_USE }`; `Solving(val preparation: Preparation)`; start `WARMING_UP` when `!source.isReady` else `NONE`; on signal switch to `FIRST_USE` under the guards; `NONE` after Ready -- three-state preparation.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- map `WARMING_UP`→`msg.solve.preparing`, `FIRST_USE`→`msg.solve.preparing-first-use`, both tagged `solve:preparing` -- copy selection.
- Test fakes listed in Code Map -- adopt new signature; update `Solving(true/false)` assertions.
- `core/solver/src/test/kotlin/dev/cubentia/solver/PruningTablesTest.kt` -- new test: callback count 0 on BUNDLED and CACHE, 1 on GENERATED, and fires before `generate` is entered.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- new tests: injected source that calls `onGenerating` then waits on a gate → phase becomes `Solving(FIRST_USE)`; stale attempt's signal (after `cancel()`/new `accept`) does not change phase.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- new test: generating fake source shows the exact first-use text under `solve:preparing` with Cancel present; existing preparing test also asserts the reworded per-start text.
- `docs/pruning-tables.md` -- in the #104 Known issues bullet, change "The copy is unchanged" to "The copy was unchanged in 3.8" and append the SCP §4.4 annotation sentence -- doc truth; leave :140/:151 as is.

**Acceptance Criteria:**
- Given the Solve tab with an unready solver whose tables are read (not generated), when the Learner accepts a cube, then "Preparing the solver. This can take a few seconds the first time after the app starts." shows under `solve:preparing` with Cancel.
- Given a source whose load takes the generate path, when the Learner accepts a cube, then "Setting up the solver for first use. This can take a little while." shows under `solve:preparing` with Cancel, and Cancel returns to the editor with colors kept.
- Given the shipped strings, when searched, then no string in `en.json` says "one time" for solver preparation.
- Given the repo, when `./gradlew spotlessCheck build` runs, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 13 findings — high 0, medium 0, low 10, false 3, maybe-false 0
- findings:
  - `low` `reject` (blind-hunter) Preparing text switch to first-use has no live region — the switch happens within the bundled+cache miss (milliseconds after Solving appears), the pre-existing preparing text never had a live region, and adding one is new a11y surface beyond a direct correction.
  - `low` `reject` (blind-hunter) ScreenAudit never exercises FIRST_USE — same composable, tag and Cancel as the audited WARMING_UP state, and the first-use text is shorter than the audited text; adding an audit state is more than a direct fix.
  - `low` `reject` (blind-hunter) Stale-signal test uses Unconfined main, not a queued main — the guard (`attempt == revision` and phase still WARMING_UP) is evaluated inside the main-scope launch at drain time, so a queued stale signal is checked against the current revision by construction.
  - `low` `reject` (blind-hunter) No test of generate-then-fail from FIRST_USE — the Failed branch sets Editing(accepted, recovery) regardless of preparation; existing loadFailurePreservesColors covers it.
  - `low` `reject` (blind-hunter) No cancellation-during-generation retry signal test — existing provider test covers cancelled generation retry, and the callback sits unconditionally in the generate branch so a retry re-signals.
  - `false` `reject` (blind-hunter) Spec calls SCP texts approved while SCP says pending-approval — the invocation intent states "Approved by SCP" for #112; fix would edit this build's spec (rejected by rule).
  - `low` `reject` (intent-alignment) No single test runs real provider → real source → VM → screen on the generate path — each link is tested with real provider and real source; the VM depends only on the SolverSource contract; a full-chain fixture is new test infrastructure.
  - `low` `reject` (intent-alignment) Source pass-through only checked on GENERATED — the source forwards the lambda verbatim to the provider, whose BUNDLED/CACHE silence is tested directly.
  - `low` `reject` (intent-alignment) Per-start text pinned only with fake sources — exact text is asserted under `solve:preparing`; the fake models the non-generating path the real source takes on bundled/cache.
  - `low` `reject` (intent-alignment) Stale-signal test bypasses the Default→main thread hop — same as blind-hunter row 3.
  - `low` `reject` (intent-alignment) ScreenAudit adjusted only — same as blind-hunter row 2.
  - `false` `reject` (intent-alignment) "Already ready" not covered on screen — SolveFlowViewModelTest timeout test asserts Solving(NONE) with a ready source, and SolveScreen maps NONE to no text.
  - `false` `reject` (intent-alignment) New spec file under implementation-artifacts is out of scope — it is the build workflow's required artifact, not product scope.
  - (edge-case-hunter: `[]`, verification-gap: no gaps found.)

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:solver:test :app:testDebugUnitTest` -- expected: new tests run and pass

## Auto Run Result

Status: done

**Summary:** `msg.solve.preparing` reworded to the SCP per-start text and `msg.solve.preparing-first-use` added. `PruningTableProvider.load(onGenerating)` signals right before generation; `SolverSource.load` passes it through; `SolvePhase.Solving` now carries `Preparation { NONE, WARMING_UP, FIRST_USE }`, with the first-use switch applied on the VM scope under the revision guard; `SolveScreen` picks the key, keeping `solve:preparing` and Cancel. Docs annotated.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- reworded + new key.
- `core/solver/src/main/kotlin/dev/cubentia/solver/PruningTableProvider.kt` -- `onGenerating` callback before generate.
- `app/src/main/kotlin/dev/cubentia/app/solver/SolverSource.kt` -- interface param + pass-through.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `Preparation` enum, guarded first-use switch.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` -- key selection.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- preview fake signature only.
- Tests: `PruningTablesTest`, `ProductionTableDeliveryTest`, `SolveFlowViewModelTest`, `AppShellSolveTest`, `ScreenAudit` (signature).
- `docs/pruning-tables.md` -- Known issues annotation; :140/:151 untouched.

**Review:** 13 findings (10 low, 3 false) — 0 patched, 0 deferred, all rejected with reasons in the triage log. Follow-up review recommended: false (no patched entries).

**Verification:** `./gradlew spotlessCheck build :core:solver:test :app:testDebugUnitTest` — BUILD SUCCESSFUL; the 5 new tests ran and passed, 0 failures/skips in test results. Implementation by Codex.

**Residual risks:** First-use copy is rarely reachable in production (bundled tables); no on-device check of it. The SCP front matter still reads pending-approval for the first-use text (PO approval is via the SCP PR).
