---
title: 'Sprint Change Proposal: truthful solver-preparing copy, and Epic 1/3 retro routing'
date: 2026-11-11
status: pending-approval
approved_by: pending (PO approval via the PR; the per-start copy was decided by the PO on 2026-11-11, the first-use copy in §4.3 needs approval)
trigger: 'GitHub issue #112 (pre-release), with retro items epic-3-retro-item-61, epic-3-retro-item-64 (copy part), epic-1-retro-item-55 and epic-1-retro-item-56'
scope: Minor
mode: batch (non-interactive; PO decisions taken from the request)
---

# Sprint Change Proposal: truthful solver-preparing copy, and Epic 1/3 retro routing

## 1. Issue Summary

**#112, the preparing copy is untrue.** The Solve tab shows "Preparing the solver (one time, a few seconds)." while the solver prepares (`msg.solve.preparing`, `core/domain/src/main/resources/content/strings/en.json:3`, rendered at `SolveScreen.kt:64`). Story 3.8 measured this preparation on the minimum reference device (Galaxy Tab S7 FE 5G). It takes about 8 s, and it repeats on **every** new app process, not once:

- Reading the bundled pruning tables takes about 24 ms. Building the move tables takes about 8.3 s on the debug build and ≤ 7.6–7.8 s on the release-equivalent build (`docs/pruning-tables.md:173-180`, architecture `.memlog.md:72`).
- Generating the tables from scratch (the first-run fallback) took a cold median of 10,014 ms (n = 5).
- The 3.8 device walk-through shows the preparing line again after a force-stop (`docs/pruning-tables.md:150-151`).

The PO accepted the 8 s for v1.0 and deferred the speed-up to v1.1 as **#104**. This SCP does not plan #104. It only makes the v1.0 copy true. The old copy is quoted word for word in the Story 3.7 AC (`epics.md:850`) and in the EXPERIENCE.md State Patterns table (`EXPERIENCE.md:130`). Both must change with the string (`epic-3-retro-item-61`).

**3.7 residual "preparing copy on cold cache read"** (`epic-3-retro-item-64`, copy part; `spec-3-7-solve-tab-end-to-end.md:174`). A new process that reads the bundled or saved tables still rebuilds the move tables and shows the preparing state. The new per-start copy describes exactly this case, so the residual becomes intended behavior. The other three deferrals in item 64 (3.5 outline ring, 3.6 Move strip, 3.7 editor drafts) are out of scope here.

**Retro item 55: `Net2DFrameTimingBenchmark` can pass with no data** (`epic-1-retro-2026-11-11.md` F2). It has no minimum-frame-count assertion. On the S24 FE it "passed" with `frameCount = 1` per iteration. On the Tab S7 FE it found no slices, because the Solve tab no longer draws a 2D net. #106 (v1.1) already defers retargeting it.

**Retro item 56: the architecture spine is stale** (`epic-1-retro-2026-11-11.md` F3). `ARCHITECTURE-SPINE.md:525` says "Story 1.10 measures it". The measurement moved to 1.11 at the split, the full pass ran in 12.2, and the 2D-net NFR-2 waiver (#106) is not recorded. The spine is `final`, so it cannot be hand-edited.

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epics 1 and 3 stay closed. Only the Story 3.7 AC text changes, and the story stays `done`. No story is added, removed or reordered. 7.8 is not blocked by a new story; #112 is already labeled `pre-release`. |
| PRD | No conflict. FR-15 and NFR-3 (3 s solve) do not quote this copy. The solve itself still meets NFR-3 (12.2: 10,101/10,101 within 3 s). Preparation is outside the timed solve (AD-8, fixed in 3.8). |
| Architecture | Not affected by the copy change. Item 56 routes a stale line in the Deferred section to `bmad-architecture` update (§4.5). It is not hand-edited here. |
| UX | EXPERIENCE.md State Patterns: the "Pruning tables building (first use)" row is renamed and reworded, and a row is added for first-use table generation (§4.2). UX-DR25 ("solver working/timeout/tables building") needs no text change. |
| Code | `en.json` (one string reworded, one key added). A small change in `:core:solver` and `:app` so the Solve tab can tell the two preparing states apart (§5). No schema, dependency or manifest change. |
| Tests / audits | No test pins the old *text*. `AppShellSolveTest.kt:209,256` and `ScreenAudit.kt:194-198` use the `solve:preparing` tag only, and the `ScreenAudit.kt:75` comment says "Preparing the solver" (still true). The build adds coverage for the new key and for the first-use state. |
| Docs | `docs/pruning-tables.md:140,151,175-177` quote the old copy as dated 3.8 evidence. The build keeps the walk-through text as recorded evidence and annotates the Known issues bullet (§5). |

### Can the code tell the two states apart today? No.

- `SolveFlowViewModel.accept` (`SolveFlowViewModel.kt:79`) sets `SolvePhase.Solving(preparing = !source.isReady)` **before** loading starts. "Preparing" is one Boolean.
- `PruningTableProvider.load()` (`core/solver/.../PruningTableProvider.kt`) picks the path internally: bundled asset (validated) → private cache file → generate and write atomically. The result carries `PruningTableSource.BUNDLED | CACHE | GENERATED`, but only **after** preparation finishes, so it cannot choose the copy shown during the wait.
- `PruningTableSolverSource` (`app/.../solver/SolverSource.kt`) passes on only `Ready` or `Failed`.

The provider does know which path it takes before the slow part starts. It decides to generate after the bundled read (~24 ms) and the cache read have both missed. So a small signal is enough (§5).

**Reachability.** Production ships the tables in the APK (3.8 decision: BUNDLED, `SolverModule.kt:39`). On a healthy install, every process start, including the first after install, takes the bundled path and shows the per-start copy. Generation (the first-use copy) happens only when the shipped asset is missing or fails validation. After it succeeds, the tables are saved, so it happens once per install, or again after app data is cleared. The first-use copy is a fallback string. It is rarely seen, but it must still be true when it is seen.

### Checklist summary

- [x] 1 Trigger and context: #112 and Story 3.8's device measurement. Category: technical limitation found in implementation (copy fixed in an AC before the measurement existed).
- [x] 2 Epic impact: Story 3.7 AC text only. Epics 1 and 3 stay closed. No future epic is affected.
- [x] 3 Artifact conflicts: epics.md and EXPERIENCE.md (edited here). Spine (routed, §4.5). PRD [N/A]. docs/pruning-tables.md (in the build).
- [x] 4 Path: Direct Adjustment. Rollback [N/A]. MVP review [N/A].
- [x] 5 Proposal components: below.
- [!] 6 Approval: the PO must approve the first-use copy (§4.3). sprint-status.yaml is updated by SM after merge (§5), not by this SCP.

## 3. Recommended Approach

**Direct Adjustment.** Reword the copy, add a first-use string, and reconcile the planning text. Ship the code part as a build on #112, with no new story. It is one Solve-tab change of the same size as a fix, and it already has a pre-release issue that tracks it.

- Effort: small (planning text edited here; the code change is a few hours).
- Risk: low. The copy changes, plus a progress signal that is used only for UI. The solve path, timeouts and table formats are unchanged.
- Timeline: none. #112 is already pre-release work before 7.8.

**Item 55: fold into #106 (v1.1). Recommended.** The benchmark is assembled in CI but never run there (`.github/workflows/ci.yml:50`). Story 12.2 is done and explicitly did not rely on it: "`Net2DFrameTimingBenchmark` is kept unchanged for that retargeting" (`docs/benchmarks.md:468-470`). A minimum-frame-count assertion on the current target would fail on every run, because that screen no longer draws a 2D net. That would add a red harness with no v1.0 fix. The assertion belongs in the same change that gives the benchmark a screen that renders a 2D net. The alternative, a v1.0 chore that adds only the assertion, gains nothing for release evidence, so it is not recommended. Action: add "assert a minimum frame count per iteration (fail on no slices)" to #106's follow-up list, and close item 55 as moved to #106.

**Item 56: route to `bmad-architecture` update.** The exact edit is in §4.5. Owner: Architect.

## 4. Detailed Change Proposals

### 4.1 Story 3.7 — Acceptance Criteria (`epics.md:848-850`) — applied

OLD:
```
**Then** a cancelable indeterminate progress bar shows, "Preparing the solver (one time, a few seconds)." shows while tables build, and a timeout shows "Couldn't find a solution in time. Your colors are still here — try again." with no partial result
```

NEW:
```
**Then** a cancelable indeterminate progress bar shows; while the solver prepares (the first solve in each app process) "Preparing the solver. This can take a few seconds the first time after the app starts." shows, or, when no valid shipped or saved tables exist and they must be generated, "Setting up the solver for first use. This can take a little while." (SCP 2026-11-11); and a timeout shows "Couldn't find a solution in time. Your colors are still here — try again." with no partial result
```

Rationale: this matches the measured per-process behavior and the PO's copy (#112). The AC no longer promises "one time" or a fixed short duration.

### 4.2 EXPERIENCE.md — State Patterns (`EXPERIENCE.md:130`) — applied

OLD:
```
| Pruning tables building (first use) | Solve | "Preparing the solver (one time, a few seconds)." Lessons are unaffected. |
```

NEW:
```
| Solver preparing (first solve after the app starts) | Solve | "Preparing the solver. This can take a few seconds the first time after the app starts." Shown on the first solve in each app process, while the solver loads its tables. Later solves in the same process skip it. Lessons are unaffected. |
| Solver tables generated (first use) | Solve | "Setting up the solver for first use. This can take a little while." Shown instead of the line above, only when no valid shipped or saved tables exist and they must be generated and saved. Lessons are unaffected. |
```

Rationale: the old row named a "first use" state that the shipped design (bundled tables plus a per-process move-table build) does not have. The two rows match the two code paths.

### 4.3 New content string (for the build; PO approval needed)

| Key | Text | When |
|---|---|---|
| `msg.solve.preparing` (reworded, PO-decided) | "Preparing the solver. This can take a few seconds the first time after the app starts." | Bundled or saved tables are read and the move tables are built: the first solve in each process. |
| `msg.solve.preparing-first-use` (new, **proposed**) | "Setting up the solver for first use. This can take a little while." | Tables are generated and saved, because the shipped asset is missing or invalid and no saved file is valid. |

Why this text is true on the reference devices: generation took a 10 s cold median on the Tab S7 FE, longer than the per-start warm-up, so the copy promises no duration. "First use" is literally true: generation runs only while no valid tables exist, and it saves them, so it happens once per install unless app data is cleared. The copy does not say "one time", because that would also need the save to succeed.

### 4.4 `docs/pruning-tables.md` (in the build, not here)

- Lines 140 and 151 are the dated 3.8 walk-through. Keep them as recorded.
- At the Known issues bullet (lines 173-180), add: "Copy reworded for v1.0 by #112 (SCP 2026-11-11): `msg.solve.preparing` now reads "Preparing the solver. This can take a few seconds the first time after the app starts."; first-use generation has its own string (`msg.solve.preparing-first-use`). The speed-up itself stays #104 (v1.1)." Also change "The copy is unchanged" to "The copy was unchanged in 3.8".

### 4.5 Architecture spine — route to `bmad-architecture` update (do not hand-edit)

`ARCHITECTURE-SPINE.md:525`, section *Deferred*:

OLD:
```
- **Performance on the reference devices:** Story 1.10 measures it. The solve corpus uses a JVM benchmark. GL frame timing uses …
```

NEW:
```
- **Performance on the reference devices:** Story 1.11 measured GL frame timing on both reference devices, and Story 12.2 ran the full performance and compatibility pass (`docs/benchmarks.md`). The solve corpus uses a JVM benchmark. GL frame timing uses … [rest of the bullet unchanged] … The 2D net (`Net2DRenderer`) NFR-2 baseline was not measured, because the Macrobenchmark target no longer draws a 2D net. The PO waived it for v1.0 on 2026-11-10, and it is deferred to v1.1 (#106).
```

For the same update, optionally: the *Solver internals* bullet (`:528`) still lists "whether pruning tables are bundled or built at first run" as open. Story 3.8 decided this: bundled, with first-run generation as a fallback (`.memlog.md:72`). The architect can mark it decided in the same pass.

## 5. Implementation Handoff

- **Scope:** Minor. Planning text (§4.1, §4.2) is applied in this SCP's PR. Code goes to `bmad-build` on **#112**. The spine goes to `bmad-architecture` update (Architect).
- **#112 build — what the code change must do:**
  1. `en.json`: reword `msg.solve.preparing` to the PO text, and add `msg.solve.preparing-first-use` (§4.3).
  2. Let the provider say which path it takes before the slow part. For example, `PruningTableProvider.load(onGenerating: () -> Unit = {})` (or a phase callback) is called right before generation starts. `PruningTableSolverSource` passes it through `SolverSource.load`.
  3. `SolveFlowViewModel`: replace `SolvePhase.Solving(preparing: Boolean)` with a three-state preparation (none / warming up / first use). Start in "warming up" when `!source.isReady`, and switch to "first use" when the signal fires. Keep the existing `attempt != revision` guard, and drop the signal from a stale attempt.
  4. `SolveScreen.kt:64`: choose the key from that state. Keep the `solve:preparing` tag (the tests and `ScreenAudit` use it), and keep Cancel.
  5. Tests: a JVM `PruningTableProvider` test proves the signal fires on the generate path and not on the bundled or cache paths. A `SolveFlowViewModel`/AppShell test shows the first-use text with an injected source that generates. `ScreenAudit` still passes.
  6. `docs/pruning-tables.md` annotation (§4.4).
  7. Do not change table formats, timeouts or start-up behavior, and do not prepare at startup or in Lessons. That work is #104.
- **Issues (PO/SM):** update #106's follow-up list with the minimum-frame-count assertion (item 55). #104–#107 stay v1.1.
- **Sprint status (SM, after merge, via the usual tooling):**
  - `epic-3-retro-item-61` → done.
  - `epic-3-retro-item-64` → note that the copy part is resolved by this SCP and #112. The other three deferrals stay open.
  - `epic-1-retro-item-55` → closed, moved to #106.
  - `epic-1-retro-item-56` → stays open until the `bmad-architecture` update merges.
- **Success criteria:**
  - No shipped string or planning text promises "one time" or a fixed short duration for solver preparation.
  - Both strings come from content strings.
  - The 3.7 AC and EXPERIENCE.md quote the new copy.
  - The first-use state is reachable in tests.
  - CI is green.
