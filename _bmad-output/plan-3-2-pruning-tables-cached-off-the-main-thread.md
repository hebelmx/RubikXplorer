---
title: 'Pruning tables, cached off the main thread'
type: 'feature'
ticket: ''
created: '2026-11-02'
status: 'built'
baseline_revision: 'aab7e880d09087e2606e97a2354c3d76e029c397'
route: 'full'
route_source: 'auto'
risk: 'high'
review: 'quick'
review_source: 'pinned'
lenses_ran: [quick]
review_loop_iteration: 0
followup_review_recommended: false
context:
  - /mnt/e/Dynamic/IndFun/RubikXplorer/AGENTS.md
  - /mnt/e/Dynamic/IndFun/RubikXplorer/_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
warnings: []
deferred:
  - summary: >-
      Minimum-reference-device acceptance evidence and packaging decision remain a before-merge human gate.
    evidence: |-
      No adb devices were attached. Story 3.2 requires Tab S7 FE 5G generation size/time measurements, NFR-11 installed-size evaluation, a packaging decision in the architecture memlog, and physical StrictMode evidence. Orchestration explicitly authorized completing independently useful infrastructure before this external gate.
    location: >-
      docs/pruning-tables.md
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Story 3.2 requires pruning tables with measured size and generation time on the minimum reference device, followed by a recorded bundled-versus-first-run decision against NFR-11.

**Approach:** Generate clean-room pruning tables over the Story 3.1 coordinate transitions, measure the actual minimum device, and use that evidence to decide packaging. If first-run generation is selected, generate on Dispatchers.Default, cache in app-private storage, leave Lessons independent, and verify StrictMode reports no main-thread disk access.

## Boundaries & Constraints

**Always:** Preserve AD-1 pure-JVM core boundaries, AD-8 off-main generation and private caching, and NFR-11 install size below 30 MB. Append any architecture decision only with the memlog script. Measure on the Samsung Galaxy Tab S7 FE 5G, which the final architecture identifies as the minimum reference device.

**Never:** Substitute host timing for the device acceptance measurement, claim unrun StrictMode results, copy third-party solver code, implement search or SolveCube, or edit protected planning artifacts.

## Delivery scope authorized by orchestration

Implement independently useful generation, cache infrastructure, and device measurement support now. Keep the bundled-versus-first-run production choice pending real minimum-device measurement. Do not wire a startup wait or claim Story 3.2 acceptance before the human before-merge gate passes. The table/cache API must be ready for the subsequent search story while staying unused by Lessons.

</intent-contract>

## Code Map

- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/Coordinates.kt` — six existing internal coordinate codecs, read as Story 3.1 continuity.
- `core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt` — lazy coordinate transitions for phase 1 (18 moves) and phase 2 (10 moves); reuse to generate pruning distances.
- `app/` — future Android private-cache wiring and physical-device measurement surface.
- `docs/benchmarks.md` — existing benchmark documentation; reference-device baselines belong to physical-device runs.
- `_bmad-output/planning-artifacts/epics.md` — Story 3.2 acceptance contract, read-only.

## Tasks & Acceptance

**Execution:**

- [x] `core/solver/src/main/kotlin/dev/rubikxplorer/solver/` — implement clean-room phase-1 twist/slice and flip/slice and phase-2 corner/slice and edge/slice admissible distance tables; breadth-first distances from solved coordinates over existing phase move tables. Test completeness, solved distance, and one-step Lipschitz consistency. Keep table storage compact and provide size/build-time reporting.
- [x] `app/` — add an on-demand debug measurement surface reporting device identity, generation duration, table bytes and cache timing; document reproducible adb invocation and StrictMode setup. Physical measurements remain an explicit before-merge gate.
- [x] `docs/` — record pending packaging options with available host evidence and physical acceptance procedure; do not append a fabricated architecture decision.
- [x] `core/solver/` and `app/` — implement optional off-main generation and versioned, bounded, checksummed private-file cache infrastructure, corruption recovery and atomic replace; use Dispatchers.Default for CPU generation and an I/O dispatcher for disk. Add only catalog-pinned coroutines if needed. Cover cold/warm/corrupt cache, cancellation, concurrent callers, and error-value behavior with JVM tests. Do not enable production first-run policy before measurement.

**Acceptance Criteria:**

- Given pruning tables, when generated, then size and build time are measured on the minimum reference device and the bundled-versus-first-run choice is decided against NFR-11 and recorded in the architecture memlog.
- Given first-run generation if chosen, when tables are built, then generation uses Dispatchers.Default, results are cached in app-private storage, Lessons never wait, and StrictMode reports no main-thread disk access.

## Implementation Notes

Initial planning detected the unavailable physical gate. Parent orchestration subsequently explicitly authorized independently useful implementation and tests, with device measurements and final delivery mode left pending before merge.

- Added four clean-room breadth-first byte distance projections and phase lookup methods, retaining existing coordinate semantics. Total payload is 4,031,685 bytes; cache format adds 40 bytes. Move-table construction now reuses each decoded row and checks cooperative cancellation before publishing.
- Added an optional mutex-serialized provider: generation on Default, disk work on IO, bounded versioned SHA-256 cache reads, corrupt-cache recovery, atomic replacement, retryable cancellation and sealed load results. No production startup or Lesson dependency was introduced.
- Added a debug-only Activity to measure cold/warm loads under main-thread StrictMode disk detection. Private directory lookup itself runs on IO. Added docs/pruning-tables.md with adb procedure, installed-size measurement requirements and pending packaging decision.
- Added nine pruning/cache tests: exhaustive graph completeness/consistency/descent; cold/warm, corruption/truncation/oversize/version; concurrent callers; cancellation and actual caller cancel/retry; dispatcher execution; failure values; failed atomic replace preserves destination and cleans temporary files.
- Full gates initially rejected internal fallback/log messages as learner text; changed fallbacks and logs to machine identifiers/structured fields. No gate was weakened. A transient Spotless read raced changing Android ASM build outputs; the final separate formatting and --no-parallel gate run succeeded.

## Plan Change Log

- 2026-11-02: Orchestration directed completion of reversible implementation, measurement support, and cache infrastructure before the device gate. Keep original story ACs; report blocked acceptance if physical evidence remains unavailable.

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 2 findings — high 0, medium 2, low 0, false 0, maybe-false 0
- findings:
  - `[medium]` `[patch]` Documented adb shorthand resolves against applicationId rather than namespace — checked merged debug manifest: actual class is dev.rubikxplorer.app.solver.PruningTablesBenchmarkActivity, while applicationId is dev.rubikxplorer. Re-engaged implementer and corrected docs to the fully qualified component.
  - `[medium]` `[defer]` Physical Story 3.2 acceptance evidence remains unavailable — adb lists no devices; the architecture names Tab S7 FE 5G as minimum. This external verification condition predates the code and orchestration explicitly authorized a before-merge human gate. No packaging decision, memlog decision, device timing, installed-size or StrictMode result claimed.

## Verification

- `./gradlew :core:solver:test :app:testDebugUnitTest` — pruning, cache and existing app JVM tests pass; new cache tests prove cold/warm/corrupt paths and concurrency/cancellation/error behavior.
- `./gradlew spotlessCheck build` — all CI-equivalent gates pass.
- Minimum reference-device measurement and StrictMode check remain explicitly unrun; adb devices currently lists no devices.

## Auto Run Result

Implementation status: built. Story acceptance and merge remain gated on physical measurements; no unavailable result has been invented.

- Implemented four clean-room BFS pruning projections, optional off-main checksummed cache infrastructure, debug device measurement surface, and reproducible device/size decision documentation. No production packaging policy was selected and no Lesson/startup wait was introduced.
- Files changed:
  - `core/solver/src/main/kotlin/dev/rubikxplorer/solver/PruningTables.kt` — four projected distance tables, generation timing and admissible phase lookup methods.
  - `core/solver/src/main/kotlin/dev/rubikxplorer/solver/PruningTableProvider.kt` — Default/IO dispatch, versioned SHA-256 cache, bounded reads, atomic writes, serialized callers and failure values.
  - `core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt` — cancel-aware transition generation with per-row decode reuse.
  - `core/solver/src/test/kotlin/dev/rubikxplorer/solver/PruningTablesTest.kt` — nine exhaustive graph/cache/dispatch/concurrency/cancellation/failure tests.
  - `app/src/debug/kotlin/dev/rubikxplorer/app/solver/PruningTablesBenchmarkActivity.kt` — opt-in cold/warm measurement with main-thread disk StrictMode policy.
  - `app/src/debug/AndroidManifest.xml` — debug-only measurement entry point.
  - `core/solver/build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` — catalog-pinned coroutines dependency.
  - `docs/pruning-tables.md` — minimum-device adb procedure and pending packaging/installed-size evidence requirements.
  - This plan — execution, review and remaining acceptance evidence record.
- Fresh context-free quick review: two findings, both medium. Patched one documented Activity component; deferred one external physical acceptance gate. No rejected findings. Patched counts: high 0, medium 1, low 0. Follow-up review recommended: false.
- Verification: `./gradlew :core:solver:test :core:solver:koverVerify` passed. Test XML records 40 solver tests and 97 app debug JVM tests, zero failures/errors/skips. `./gradlew --no-parallel :core:solver:test :app:testDebugUnitTest spotlessCheck build` passed full CI-equivalent gates, including Kover, boundaries, privacy/text policy, Android lint and debug/release/benchmark APK builds. Repeated after the documentation review patch.
- Residual human before-merge gate: connect the minimum Samsung Galaxy Tab S7 FE 5G, collect generation duration/table/cache bytes and release-equivalent installed-size evidence against NFR-11, verify StrictMode and Lesson independence, choose bundled versus first-run, record the decision using the architecture memlog script, and wire/verify the selected production mode. `adb devices -l` returned no attached devices in this run. Hardware absence is not a code-build failure; Story 3.2 ACs are not yet fully accepted.
