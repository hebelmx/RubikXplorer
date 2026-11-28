---
epic: 15
date: 2026-11-27
verdict: accepted-with-open-items
criteria: declared
headless: true
scope: addendum (Story 15.4 only)
supersedes: none
addendum_to: _bmad-output/implementation-artifacts/epic-15-retro-2026-11-22.md
---

# Epic 15 Retrospective Addendum: Story 15.4 (method-solve warm-up and debug solve deadline)

This addendum reviews only Story 15.4. Stories 15.1-15.3 were reviewed in the main Epic 15 retrospective,
[`epic-15-retro-2026-11-22.md`](epic-15-retro-2026-11-22.md) (verdict accepted-with-open-items, items
`epic-15-retro-item-207` ... `-213`). Nothing here re-opens or repeats those findings or the
`epic-15-deferred-*` items already in `sprint-status.yaml` `action_items`.

## Epic summary

- **Epic:** 15, Method-aware solutions (FR-30, AD-21). Story 15.4 was added after the epic closed, by
  `sprint-change-proposal-2026-11-27-solver-deadline-tier.md` (PO decision A + B: release deadline stays 3 s, debug
  builds get 5 s, and one throwaway method-solve warm-up runs after solver preparation). Commit `9fe5dbde` added the
  story and moved `epic-15` from `done` back to `in-progress`.
- **Story:** `15-4-warm-the-method-solver-once-and-give-debug-builds-a-5-s-solv`, PR #445, merge `d0706dfc`.
- **Diff range:** `d0706dfc^1..d0706dfc` (30 files, +4,500 / -50; about 3,590 of the added lines are raw device
  logs and samples under `docs/evidence/15-4/`).
- **Per-story commits:** `4b0dc56d` start; `2651028a` feat(solver); `120af5a8` in review; `fe31ba3c`
  docs(benchmarks) device results; `686fd4b7` docs(evidence) Solution Playback cold-start taps; `32d2a09b` done.
- **Completeness:** `detect-epic --epic 15`: `story_count` 4 in scope, `pending_stories: []`. All four Epic 15
  stories are `done`.
- **Evidence inventory:**
  - available: the story spec with its intent contract, review triage log (18 findings) and Auto Run Result
    (`spec-15-4-warm-the-method-solver-once-and-give-debug-builds-a-5-s-solv.md`); the SCP and its "before"
    evidence (`docs/evidence/scp-2026-11-27-solver-deadline/`); the "after" device evidence
    (`docs/evidence/15-4/README.md`, three raw logs, three conditions files, `samples.csv`); the
    `docs/benchmarks.md` § Method-solve deadline rows; PR #445 CI (both checks SUCCESS); sprint-status.
  - missing: session logs (process lessons limited to the spec's triage log); no human or second-model PR review on
    #445 (`reviews: []`; the only comment is the SemanticDiff bot); no Galaxy cold-start taps through the UI (not
    required by the AC).

## Findings

### Aggregate views

| # | View | Finding | Source | Disposition |
|---|------|---------|--------|-------------|
| A1 | Architecture delta | Clean. The deadline is one `SolveDeadline` Hilt singleton chosen from `FLAG_DEBUGGABLE` in `:app` and passed to `SolveCube`, `SolveByMethod` (via `methodSolverFactory`) and `PlanRecovery` (via `AppShell` -> the 3 `LessonScreen` sites). `TwoPhaseCubeSolver`'s cap, NFR-3, the reducer and `SessionEvent` are untouched, matching the intent's Never list. | `app/src/main/kotlin/dev/cubentia/app/solver/SolveDeadline.kt`; `di/SolverModule.kt` (`solveDeadline`, `methodSolverFactory`); `AppShell.kt` (3 `planRecovery = remember(solveDeadline)`); `git diff d0706dfc^1 d0706dfc` has no `core/solver` or reducer path | accept |
| A2 | Pattern conformance | The warm-up reuses the production `MethodSolveFunction` / `{ isActive }` cancel contract and an `AtomicBoolean` once-guard on its own `Dispatchers.Default` coroutine, sharing no lock with `SolveByMethod`; the benchmark harness runs the same class rather than a copy, as the spec required. | `MethodSolveWarmUp.kt:39-63`; `MethodSolveBenchmarkActivity.kt` (`MethodSolveWarmUp(bundle = { bundle }, scope = this, ...)`) | accept |
| A3 | God-class growth | `AppShell.kt` grew by 13 lines (one parameter threaded to three call sites). Already covered by `epic-15-retro-item-210` / `epic-11-retro-item-105`; not re-raised. | `git show --stat d0706dfc` | defer (already tracked) |
| A4 | Spec-to-implementation | Every Execution task in the spec maps to a change in the diff (SolveCube param, `val deadlineMillis`, `SolveDeadline`, `MethodSolveWarmUp`, SolverModule providers, SolveFlowViewModel trigger, AppShell/MainActivity threading, harness `--ez warmUp` + `warmed-up,nanos=` line, benchmarks.md bullet, the two new test classes). | spec-15-4 § Tasks & Acceptance vs `git show --stat d0706dfc` | accept |
| A5 | Unused API | `MethodSolveWarmUp.cancel()` has no production caller; the warm-up's scope is a process-lifetime `SupervisorJob` in `SolverModule.warmUp`. "Cancellable" is met by contract and `MethodSolveWarmUpTest`, not exercised by the app. Harmless for a one-shot, process-scoped job. | `rg 'warmUp\.' app/src/main` finds only `MethodSolveWarmUp.kt:67`; `SolverModule.kt` `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | accept |
| A6 | Tracking state | All four Epic 15 stories and `epic-15-retrospective` are `done`, but `epic-15` is still `in-progress` since the SCP reopened it. | `sprint-status.yaml:212-217`; `git log -L212,212` (`9fe5dbde` done -> in-progress) | fix now (tracking; for the orchestrator, this retro does not edit sprint-status) |

### Diff-scope and evidence review

| # | Severity | Finding | Source | Disposition |
|---|----------|---------|--------|-------------|
| R1 | medium | **The release-build overlap case is unmeasured.** The harness `join()`s the warm-up before the first timed solve, so every "1st solve (JIT-cold, after warm-up)" row describes a tap made *after* the warm-up finished. In the app the warm-up starts at `SolverLoad.Ready` and is not awaited, so a style tap during it runs cold and in parallel. The warm-up itself takes a median 3,089 ms (max 3,229, 16-19 of 25 over 3 s) on the Galaxy benchmark build and 1,074 ms on the Lenovo benchmark build, so the overlap window is wide. The only overlap evidence is the 5 cold-start taps on the **Lenovo debug** build (5 s deadline, all shown). No release/benchmark-build overlap was run, and the SCP's busy-Galaxy "before" set already had 7-8 of 25 cold first solves over 3 s *without* a concurrent warm-up competing for CPU. The spec's Residual risks and `docs/benchmarks.md` both name the overlap honestly; no item tracks measuring it. | `MethodSolveBenchmarkActivity.kt` (`.start()?.join()` before `timed`); `SolveFlowViewModel.kt` (`warmUp?.start()` not awaited); `docs/evidence/15-4/README.md` Results (warm-up rows) and "Solution Playback cold-start taps"; SCP lines 140-142 (busy Galaxy set 1); spec-15-4 Residual risks | fix now (measure; new action item) |
| R2 | resolved | The spec's residual risk "the warm-up warms TWO_LOOK only, so the BEGINNER cold first solve is unconfirmed" is closed by the device rows: BEGINNER 1st solve after warm-up, Lenovo benchmark median 109 / max 229 ms, Lenovo debug 775 / 1,097 ms, Galaxy benchmark 417 / 1,001 ms, 0 over 3 s. | `docs/evidence/15-4/README.md` Results; `docs/benchmarks.md` rows "after Story 15.4" | accept |
| R3 | low | The debug-only log rule holds for the app (`SolverModule.warmUp` gates `Log.d` on debuggable, test `SolveDeadlineTest.theProductionWarmUpLogsOnlyInDebuggableBuilds`); the harness logs `warm-up-note` with `Log.i` by design and no run logged one (0 in all three raw logs, every solve `Solved`). | `SolverModule.kt`; spec triage row `[medium] [patch]`; `grep -c warm-up-note docs/evidence/15-4/*-raw.log` = 0 | accept |
| R4 | low | Build-type evidence is consistent: every benchmark run logged `build,debuggable=false` and every debug run `build,debuggable=true`, so the benchmark rows exercised the 3 s tier and the debug rows the 5 s tier. | `grep build,debuggable docs/evidence/15-4/*-raw.log` | accept |
| R5 | low | Review triage rejected 14 of 18 findings with reasons; the two rejected wiring gaps (no UI test that `PlanRecovery` receives the injected deadline; `LessonScreen`'s own default is still `PlanRecovery()` at 3 s) share one root and only matter in debug builds, where recovery peaks under 1 s. Accepted as recorded; not re-raised. | spec-15-4 Review Triage Log | accept |
| R6 | - | The CI flake on PR #445 (`AppShellTest` recreation timeout, possibly the warm-up's CPU load) is already tracked as `epic-15-deferred-15.4-appshelltest-recreation-ci-flake`. Not re-raised. | `sprint-status.yaml:3413-3418` | defer (already tracked) |

## Behavior verification

This retrospective did not exercise the app itself (no device session in this run). The story's runtime behavior was
exercised on devices by the orchestrator and recorded as evidence:

- `MethodSolveBenchmarkActivity` re-run at revision `120af5a8`: Lenovo TB128XU benchmark (75 processes) and debug
  (60), Galaxy Tab S7 FE benchmark (75, on battery, power save on, load 4-9). Every first method solve after the
  warm-up was under 0.23 s (Lenovo benchmark), 1.1 s (Lenovo debug; was 60 of 60 over 3 s before) and 1.0 s (Galaxy);
  0 timeouts at either tier. Source: `docs/evidence/15-4/README.md`, `samples.csv`.
- AC 5: 5 cold-start Solution Playback style taps on the Lenovo debug build, tapped while the warm-up was still
  running (Beginner x2, 2-look x2, Full OLL + PLL x1); every one showed the method solution and "Couldn't build that
  solution in time" never appeared. Source: `docs/evidence/15-4/README.md` § Solution Playback cold-start taps,
  commit `686fd4b7`.
- JVM: `MethodSolveWarmUpTest` 5/5, `SolveDeadlineTest` 5/5 (+1 log-gate test from review), `SolveCubeTest` 11/11;
  `spotlessCheck build` green; PR #445 CI "Build and gates" and "Compile retained spikes" SUCCESS.

Not exercised anywhere: a style tap during the warm-up on a release/benchmark build (finding R1).

## Previous-retro follow-through

Scope: only items this story bears on; the full follow-through for earlier epics is in the main Epic 15 retro.

| Item id | Landed? | Evidence | Status it argues for |
|---------|---------|----------|----------------------|
| `epic-15-retro-item-208-reference-device-nfr-3-timing-for-method` | partly | Lenovo TB128XU cold first method solve, all three styles, benchmark and debug builds, median / p95 / max and 0 failures, before (`docs/evidence/scp-2026-11-27-solver-deadline/`) and after the warm-up (`docs/evidence/15-4/`). Still missing from that item: memory, and a chip switch during a cold build. | `in-progress` (proposed only) |
| `epic-15-deferred-15.1-cold-start-table-build-timeout` | no evidence found | 15.4 moved the cold cost into an untimed warm-up but did not change `MethodSolve`'s table-build polling; the item's defect (no `shouldContinue` poll while building tables) is unchanged by this diff. | none |

Headless run: no `--set-action-status` was passed; the transitions above are proposals for the PO.

## Action items

| # | Action | Owner | Kind |
|---|--------|-------|------|
| 1 | Measure the release-build warm-up overlap: add a harness option that starts the first timed method solve while the production warm-up is still running (no `join()`), then run it cold on the Lenovo TB128XU and on a busy Galaxy Tab S7 FE benchmark build for all three styles and record >3 s counts in `docs/benchmarks.md` § Method-solve deadline; if any time out, take it to the PO (start the warm-up earlier, or hold the style chips until it ends). Batch with `epic-15-retro-item-208`. | Dev (Amelia) + PO (devices) | remediation (measurement), proposed |
| 2 | Close `epic-15` in sprint-status (`in-progress` -> `done`): every story and the retrospective are `done` since 15.4 merged. | SM / orchestrator | tracking, proposed |

## Acceptance verdict

**accepted-with-open-items** (criteria declared in spec-15-4 § Acceptance Criteria; machine verdict, no human decision).

- AC 1 (one throwaway, off-main, untimed, cancellable warm-up after the tables are ready; silent failure, debug-only
  log): met, `MethodSolveWarmUp.kt`, `SolveFlowViewModel.kt`, `MethodSolveWarmUpTest`, `SolveDeadlineTest` log-gate test.
- AC 2 (a tap before the warm-up finishes runs on its own deadline, not queued): met at unit level
  (`MethodSolveWarmUpTest` not-queued test) and on the Lenovo debug build (5 taps); not measured on a release build (R1).
- AC 3 (3 s release/benchmark, 5 s debuggable, one per-process `SolveDeadline`, solver cap and NFR-3 unchanged): met,
  `SolveDeadline.kt`, `SolverModule.kt`, `SolveDeadlineTest`, `SolveCubeTest`; device logs confirm the build flags (R4).
- AC 4 (`spotlessCheck build` green): met, spec Verification and PR #445 CI.
- AC 5 (device re-runs and 5 cold-start taps): met, `docs/evidence/15-4/` and `docs/benchmarks.md`.
- Open items: R1 (new action item 1), A6 (new action item 2), and the already-tracked
  `epic-15-deferred-15.4-appshelltest-recreation-ci-flake`.

## Open questions

- Should a Learner on a release build be able to tap a style before the warm-up ends? R1's measurement answers whether
  that matters; the product answer (start the warm-up earlier, or gate the chips) is the PO's.
- Is a merged PR with no human or second-model review acceptable for solver-timing changes while agy and Codex are
  out of quota? Recorded only; PR #445's own review pass (18 findings, 4 patched) is in the spec.

## Assumptions

- Epic 15 taken from the invocation (`-H epic 15`); scope narrowed to Story 15.4 by the orchestrator's instruction,
  written as an addendum to `epic-15-retro-2026-11-22.md`.
- `detect-epic --epic 15`: `story_count` 4 (Epic 15 keys), `pending_stories: []`, `retro_status: done`.
- Machine verdict **accepted-with-open-items**, rendered with no human decision.
- Both action items are proposals; neither is applied.
- **Phase 5 sprint-status update not run:** the orchestrator instructed this run not to edit `sprint-status.yaml`. The
  retro key was already `done`; the two action items above are for the orchestrator to append. No previous item's
  status was changed.
- No `bmad-review` sub-run: the story's own review (18 findings) was read from its triage log, and the code lenses were
  applied inline over the 15.4 diff only (narrowed scope).
