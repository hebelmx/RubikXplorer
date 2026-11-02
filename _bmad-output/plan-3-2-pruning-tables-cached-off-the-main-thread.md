---
title: 'Pruning tables, cached off the main thread'
type: 'feature'
ticket: ''
created: '2026-11-02'
status: 'blocked'
baseline_revision: 'aab7e880d09087e2606e97a2354c3d76e029c397'
route: 'full'
route_source: 'auto'
risk: 'high'
review: ''
review_source: ''
lenses_ran: []
review_loop_iteration: 0
followup_review_recommended: false
context:
  - /mnt/e/Dynamic/IndFun/RubikXplorer/AGENTS.md
  - /mnt/e/Dynamic/IndFun/RubikXplorer/_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 3.2 requires pruning tables with measured size and generation time on the minimum reference device, followed by a recorded bundled-versus-first-run decision against NFR-11.

**Approach:** Generate clean-room pruning tables over the Story 3.1 coordinate transitions, measure the actual minimum device, and use that evidence to decide packaging. If first-run generation is selected, generate on Dispatchers.Default, cache in app-private storage, leave Lessons independent, and verify StrictMode reports no main-thread disk access.

## Boundaries & Constraints

**Always:** Preserve AD-1 pure-JVM core boundaries, AD-8 off-main generation and private caching, and NFR-11 install size below 30 MB. Append any architecture decision only with the memlog script. Measure on the Samsung Galaxy Tab S7 FE 5G, which the final architecture identifies as the minimum reference device.

**Never:** Substitute host timing for the device acceptance measurement, claim unrun StrictMode results, copy third-party solver code, implement search or SolveCube, or edit protected planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|---------------|---------------------------|----------------|
| Generation measurement | Minimum reference device and generated pruning tables | Record table size and build duration, then decide packaging against NFR-11 | Device unavailable blocks acceptance and the evidence-based decision |
| First run, if selected | No app-private cache | Build on Dispatchers.Default without delaying Lessons; cache valid results | Report generation/cache failure as a value |
| Subsequent run, if selected | Valid app-private cache | Reuse tables off-main with no StrictMode disk violations | Reject incompatible or corrupt cache and regenerate off-main |

</intent-contract>

## Code Map

- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/Coordinates.kt` — six existing internal coordinate codecs, read as Story 3.1 continuity.
- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt` — lazy coordinate transitions for phase 1 (18 moves) and phase 2 (10 moves); reuse to generate pruning distances.
- `app/` — future Android private-cache wiring and physical-device measurement surface.
- `docs/benchmarks.md` — existing benchmark documentation; reference-device baselines belong to physical-device runs.
- `_bmad-output/planning-artifacts/epics.md` — Story 3.2 acceptance contract, read-only.

## Tasks & Acceptance

**Execution:**

- [ ] `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` — implement and test clean-room pruning table generation after an executable verification plan is available.
- [ ] `app/` — provide the minimum-device measurement surface and measure generation size/time; use results to decide bundled versus first-run.
- [ ] Architecture memlog — record the measured evidence and packaging choice using `uv run _bmad/scripts/memlog.py append`.
- [ ] `app/` — if first-run is chosen, implement Dispatchers.Default generation, app-private caching, Lesson independence, and StrictMode verification.

**Acceptance Criteria:**

- Given pruning tables, when generated, then size and build time are measured on the minimum reference device and the bundled-versus-first-run choice is decided against NFR-11 and recorded in the architecture memlog.
- Given first-run generation if chosen, when tables are built, then generation uses Dispatchers.Default, results are cached in app-private storage, Lessons never wait, and StrictMode reports no main-thread disk access.

## Implementation Notes

No implementation dispatched: the first AC requires a physical measurement that this environment cannot execute, and its result selects the conditional implementation branch.

## Plan Change Log

## Review Triage Log

## Verification

- `$HOME/Android/Sdk/platform-tools/adb devices -l` — executed 2026-11-02; output contained only `List of devices attached`, with no attached device.
- Architecture spine Stack identifies minimum Samsung Galaxy Tab S7 FE 5G and mid Samsung Galaxy S24 FE. Story 3.3 explicitly allows a JVM proxy; Story 3.2 does not.
- Once the minimum reference device is available, measure real table generation, storage bytes, install size, and the selected cache path under StrictMode before claiming acceptance.

## Auto Run Result

Status: blocked

Blocking condition: unavailable minimum reference device for Story 3.2 AC1 (Samsung Galaxy Tab S7 FE 5G); no attached adb devices. The plan cannot meet the full route's testable/sufficient Ready for Development standard because the required measurement and resulting packaging decision are unavailable. No timing, packaging choice, cache verification, or StrictMode result has been invented. No implementation or review subagents were launched because execution halted during planning, before those steps.
