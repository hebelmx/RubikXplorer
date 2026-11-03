---
title: 'Two-phase search verified on a 10,000-state corpus'
type: feature
created: '2026-11-02'
status: done
baseline_revision: 07426471a933aa38488e3a47ecba496673cb991e
review_loop_iteration: 0
followup_review_recommended: false
context:
  - /mnt/e/Dynamic/IndFun/RubikXplorer/AGENTS.md
  - /mnt/e/Dynamic/IndFun/RubikXplorer/_bmad-output/implementation-artifacts/epic-3-context.md
warnings: [oversized]
deferred:
  - summary: >-
      Reference-device-equivalent JVM profile calibration remains a before-merge acceptance gate.
    evidence: |-
      Existing CI uses ubuntu-latest and Temurin17 with no reference-device calibration. Host corpus results prove host correctness and speed only. Original Story3.3 criterion remains unchanged; no equivalence is claimed. Physical corpus confirmation remains Story12.2.
    location: >-
      docs/benchmarks.md
    severity: medium
  - summary: >-
      Existing synchronized cold move-table preparation can delay cancellation while acquiring its monitor.
    evidence: |-
      MoveTables.transitions uses @Synchronized before its cancellation callback; an existing cold builder can hold its monitor. New search cancellation is tested after prepared tables; production loading and preparation integration remain mandatory Story3.8. Add concurrent cold preparation cancellation/retry coverage when addressing that infrastructure path.
    location: >-
      core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt:29
    severity: medium
---

<intent-contract>

## Intent

**Problem:** A Learner must trust every offline Solution. Coordinates and pruning infrastructure exist, but no search or real solve-corpus evidence exists.

**Approach:** Implement original Kociemba two-phase search behind CubeSolver, exercise solved plus at least 10,000 random legal states including deep Scrambles in CI, independently verify every returned Solution, and replace the benchmark placeholder with real search.

## Boundaries & Constraints

**Always:** Follow AD-1/8/11/12: application owns CubeSolver port, solver adapter pure JVM, plain injection, computation on Default, sealed Solved/Invalid/TimedOut/Failed results, center-relative Moves, cooperative cancellation propagates without partial result. Use accepted coordinate/pruning APIs and original code written from published mathematical description (https://kociemba.org/math/twophase.htm); no third-party solver source. Preserve all original Story3.3 criteria: median/p95/memory/failure reporting;95% within3s on reference-device-equivalent profile (CI JVM proxy assumption, device confirmation12.2). Report actual host profile honestly; missing calibration is residual before-merge acceptance evidence, never claim equivalence. No weakening corpus, thresholds, or gates.

**Never:** Change protected planning artifacts, domain constructor/validation gate, production table delivery policy (mandatory3.8), startup/Lesson wiring, UI/SolveCube effects (3.4), or other stories. No branch switching, pushing, PR creation or commits by implementation subagent.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
| --- | --- | --- | --- |
| Solved | Solved cubies | Solved empty Moves | No error |
| Legal state | Random legal state, deep Scramble, phase2 state | Complete Moves solve the exact input via public CubeState.apply oracle | Failed/TimedOut explicit, never partial success |
| Cancelled | Coroutine cancelled during active search | Stops promptly; no result published | Cancellation propagates |
| Deadline | Search exceeds3s | TimedOut only | No partial Moves |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/` — new CubeSolver and SolveResult; missing today; solver already depends application/domain.
- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/Coordinates.kt` — internal Coordinate codecs and Representative.from/apply, solved zero; phase2 encoders require slice partition.
- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt` — internal18/10 phase move arrays, transitions row-major reusable arrays; lazy synchronized construction cancel-aware.
- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/PruningTables.kt` — injectable admissible phase1/2 max-projection heuristics. Provider optional; production choice pending3.8.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/` — read-only independent public CubeState.apply oracle; CubeValidator sole sticker→state route. Uniform cubie fixtures can form facelets and validate as CoordinatesTest demonstrates.
- `core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/` — SolveCorpusHarness timings/stats, current PlaceholderWorkload inverse scrambling, Main default1000. Cases generated before timing,200 warmup. Ensure failures do not count as successful within budget.
- `core/solver/build.gradle.kts` — benchmark source set must see solver main output/runtime for real adapter; no build task dependency on opt-in solveCorpusBenchmark.
- `docs/benchmarks.md` — invocation, corpus, host metrics and profile evidence; no calibrated equivalent profile currently exists (CI ubuntu-latest Temurin17).

## Tasks & Acceptance

**Execution:**
- [x] `core/application/src/main/kotlin/dev/rubikxplorer/application/port/CubeSolver.kt` — declare suspend cubies-only port and sealed results.
- [x] `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` — implement deterministic cancel-aware bounded two-phase search, reusing existing arrays/heuristics and preserving clean-room provenance.
- [x] `core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/` and `core/solver/build.gradle.kts` — real reproducible representative corpus/workload; report metrics, seed and runtime profile; default10000 random legal states plus solved including deep scrambles. Remove inverse-solution placeholder.
- [x] `core/solver/src/test/kotlin/dev/rubikxplorer/solver/` — mandatory CI corpus at least10000 random legal states plus solved and deep Scrambles; independently apply every Solution; require zero failures and95% successful within3s. Test matrix and reproducibility/cancellation/deadline behavior with meaningful tests.
- [x] `docs/benchmarks.md` — document actual commands/results, memory and failure rate, profile and absent calibration separately; retain12.2 and3.8 obligations.

**Acceptance Criteria:**
- Given CubeSolver returning Solved/Invalid/TimedOut/Failed, when solved plus≥10000 random legal states including deep Scrambles run in CI, then applying every Solved Solution solves its exact input.
- Given Story1.10 harness, when real corpus runs, then median,p95,memory,failure rate are reported and95% solve within3s on the reference-device-equivalent profile [ASSUMPTION:JVM proxy in CI, confirmed on device12.2]. Record any missing equivalence evidence without changing criterion.
- Given a cancelled coroutine, when search is in progress, then it stops promptly and returns no partial result.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 12 findings — high 0, medium 5, low 2, false 5, maybe-false 0
- findings:
  - `[medium]` `[defer]` Blind: missing performance equivalence — checked CI workflow and docs: no calibration exists; this pre-existing evidence gate remains before merge. Implementation checkbox records executed host test, not acceptance of equivalence.
  - `[medium]` `[defer]` Blind: cancelled solve can wait for cold transition monitor — verified pre-existing @Synchronized acquisition precedes callback in MoveTables; prepared active search is cancellable. Grouped with cold-preparation test gap for required production infrastructure follow-up.
  - `[medium]` `[defer]` Blind: missing concurrent cold reconstruction cancellation/retry test — current test covers active search, not the pre-existing table monitor. Same preparation root cause as preceding row; retained for production loading integration.
  - `[low]` `[reject]` Blind: per-request immutable transform reconstruction — confirmed small input-independent allocations, but host p95 below29ms and no practical resource issue shown. Introducing shared lifetime initialization solely for this negligible overhead is unwarranted.
  - `[low]` `[reject]` Blind: missing failing case diagnostics — report retains seed and deterministic ordered corpus, sufficient to reproduce the complete run; additional failure taxonomy/state reporting adds complexity for hypothetical future regressions.
  - `[false]` `[reject]` Blind: missing deliberately difficult12/18 boundary tests — no failing bounded state demonstrated; requested extra test is not evidence of wrong bound. Bounds follow original two-phase algorithm and10101 independent inputs pass.
  - `[false]` `[reject]` Blind: missing concurrent same-instance test — Search/path/nodes/length/deadline are per-call; tables are read-only after publication. No shared mutable search state or interference demonstrated.
  - `[medium]` `[patch]` Verification: uniform segment can repeat undetected — strengthened SolverWorkloadTest to assert distinct states and per-segment reproducibility/seed sensitivity, independent of deep Scrambles.
  - `[medium]` `[defer]` Intent: host surface does not establish profile equivalence — agrees with docs and original AC; grouped with first calibration finding, preserving before-merge evidence gate.
  - `[false]` `[reject]` Intent: app-delivered trust surface absent — explicit story split assigns orientation mapping/presentation to3.4 and production wiring to3.8/3.7;3.3 AC explicitly observes CubeSolver and corpus.
  - `[false]` `[reject]` Intent: cold preparation excluded from active search timing — documented prepared-table corpus boundary, with physical loading and production delivery retained in3.8; no claim of user-visible cold latency.
  - `[false]` `[reject]` Intent: cancellation surface — reviewer confirms active search cancellation directly exercised without publication; no divergence or defect.


## Design Notes

Reuse admissible projected distances for IDA* in each phase and primitive transitions in hot loops. Do not retain inverse scramble information as a solution. Search depth bound belongs to solver internals per spine; document chosen bound and deadline behavior. Corpus cases must be independent of the search and test verification must use domain moves, not coordinate goal alone. Prepare tables outside timed attempts and document this boundary as3.8 table delivery remains separate. Cancellation testing must demonstrate cancellation during active work, not just before entry.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:solver:test` — matrix, corpus, harness and existing tests pass with metrics recorded.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` — all CI gates pass,≥80% Kover, Android builds unchanged.

## Auto Run Result

Implemented original deterministic two-phase IDA* behind application-owned CubeSolver, with center-relative Solved moves and explicit Invalid/TimedOut/Failed outcomes, Default execution,3s search deadline and cooperative active-search cancellation. Replaced inverse-scramble placeholder with reproducible uniform legal-state generation through CubeValidator, solved and100 deep100-turn Scrambles; every returned solution is independently applied using CubeState.apply.

Files changed:
- `core/application/.../port/CubeSolver.kt` — suspend port and sealed result contract.
- `core/solver/.../TwoPhaseCubeSolver.kt` — bounded12/18 phase search, prepared-table injection and per-call workspace.
- `core/solver/src/benchmark/.../SolverWorkload.kt` — real corpus, independent verification, uniform cubie generation; removed PlaceholderWorkload.
- `core/solver/src/benchmark/.../SolveCorpusBenchmarkMain.kt`, `CorpusStats.kt`, `core/solver/build.gradle.kts` — real default10000-state workload, actual runtime profile and successful-within-budget accounting.
- `core/solver/src/test/.../TwoPhaseCubeSolverTest.kt` and benchmark tests — full10101-state CI corpus; solved/phase2/determinism/invalid/deadline/cancellation and corpus regression tests.
- `docs/benchmarks.md` — reproducible commands, actual host evidence and explicit missing-equivalence gate.
- Epic context and this spec — distilled read-only planning context, execution and individual review verdicts.

All four review layers completed at inherited model capability. Twelve findings individually triaged: one medium patch, two grouped medium deferred items (external equivalence evidence and existing cold-preparation monitor behavior), seven rejected findings with reasons recorded above. Patched counts: high0,medium1,low0. Follow-up review recommended:false; no remaining demonstrated active-search defect. No planning artifact, production delivery policy, domain constructor, branch or remote changes.

Matrix audit: solved/single-turn/phase2 test covers empty and legal outputs; mandatory corpus covers uniform/deep states with domain verification; active cancellation test synchronizes after1024 explored nodes and proves prompt cancellation without result publication; injected-clock deadline test returns only TimedOut after active work. All five tests executed without skips or failures.

Verification: focused SolverWorkloadTest passes after the review patch. Full CI-equivalent command `:core:solver:test spotlessCheck build` passes, including>=80% Kover, boundaries/privacy/text gates, lint and debug/release/benchmark builds. Full logs: `/tmp/story33-build.log`, `/tmp/story33-verified.log`, `/tmp/story33-final-verification.log`; corpus XML is the normal `core/solver/build/test-results/test/TEST-dev.rubikxplorer.solver.TwoPhaseCubeSolverTest.xml`, with original retained in `/tmp/story33-corpus-first.xml`.

Residual acceptance gate: host results do not establish reference-device-equivalent calibration. Original95%-within3s criterion remains unchanged and requires evidence before merge; physical corpus confirmation remains12.2, and mandatory production delivery/loading/installed-size integration remains3.8 before3.7/Epic3 exit. Implementation completion here is distinct from acceptance of that external performance gate.

Final verification after review patch: `:core:solver:test spotlessCheck build` SUCCESS in1m29s. Corpus10101/10101 solved,0 failures,100% within3s,median3.637ms,p9521.348ms,max229.863ms,peak334.2MiB; corpus test69.745s,all five search tests70.251s. Final retained evidence:`/tmp/story33-corpus-final.xml` and `/tmp/story33-final-verification.log`.

## Historical Finalization Halt

Status: blocked.
Blocking condition: finalization left repository dirty.

Reviewed implementation committed as95f1913ddff67ddb5654246abbae6b293bf0121c. The only remaining unrelated change is `.claude/settings.json` (enabledPlugins). It was not authored by this run, was excluded from the story commit and is preserved unchanged. Parent explicitly instructed preservation without revert/stash/commit. Build-auto step04 Finalize2 requires: "Verify the version-controlled working copy is clean. Otherwise HALT with status blocked and blocking condition finalization left repository dirty." This finalization condition is separate from the external profile-equivalence before-merge acceptance gate.

## Finalization Complete

Status: done.
Blocking condition: none.

The user authorized the unrelated settings change, committed separately as8401a7845ca7aed755d9d91ede74482269d86b4f. The working copy was verified clean before this result update; the dirty-tree halt is resolved. This continuation changes only this execution record, with no implementation changes or additional review required. Follow-up review recommended:false. All deferred findings and the explicit reference-device-equivalence calibration before-merge acceptance gate remain unchanged; done records implementation/workflow completion, not approval of that external gate.
