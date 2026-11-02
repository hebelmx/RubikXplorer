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
      Mandatory Story 3.8 owns reference-device acceptance, delivery decision and production integration; no longer a revised Story 3.2 before-merge gate.
    evidence: |-
      No adb devices were attached. The approved 2026-11-02 split transfers Tab S7 FE 5G measurements, NFR-11 installed-size evaluation, architecture delivery decision and production StrictMode verification to mandatory Story 3.8. This is tracked follow-up scope, not a high-severity defect or deferred acceptance criterion in revised Story 3.2.
    location: >-
      docs/pruning-tables.md
    severity: medium
---

## Approved scope amendment — 2026-11-02 (operative contract)

The user approved [the course-correction proposal](planning-artifacts/sprint-change-proposal-2026-11-02-device-validation-splits.md). The replacement Story 3.2 contract below supersedes the original before-merge hardware gate for infrastructure acceptance only. The original intent, ACs, review and gate results below remain historical evidence. Missing hardware is not a defect in revised 3.2 and must not trigger futile code retries.

As the developer,
I want tested clean-room pruning-table generation, optional off-main cache infrastructure and a reproducible device measurement harness,
So that search can use verified table APIs while production delivery is decided from real-device evidence in Story 3.8.

**Refs:** FR-15 · NFR-3, NFR-11, NFR-12 · AD-1, AD-8, AD-11, AD-12, AD-16 · AR-12, AR-22 · UX-DR25

**Acceptance Criteria:**

**Given** the clean-room Story 3.1 coordinate transitions
**When** phase-1 twist/slice and flip/slice and phase-2 corner/slice and edge/slice pruning projections are generated
**Then** all reachable entries have breadth-first distances from solved, solved entries have distance zero, graph consistency and descending-path tests pass, and the API exposes admissible phase heuristics and deterministic payload size without third-party solver code

**Given** the optional pruning-table provider
**When** tables are generated or a versioned private-file cache is loaded or replaced
**Then** CPU generation uses `Dispatchers.Default`, disk access uses an I/O dispatcher, the bounded cache validates its version and checksum, corrupt data is regenerated, replacement is atomic, and load failures are sealed values

**Given** cancellation, concurrent callers and failed cache writes
**When** provider tests run
**Then** cancellation is cooperative and retryable, concurrent loads are serialized, no partial table result is published, and a failed replacement preserves the prior destination and cleans temporary files

**Given** the debug measurement Activity and documented adb procedure
**When** the debug build is produced
**Then** the harness can report device identity, cold-process generation duration, payload/cache bytes, an independent warm disk-cache load and main-thread StrictMode violations; no unrun physical result is claimed

**Given** app startup and Lessons
**When** the infrastructure is installed
**Then** neither calls or awaits it, and no bundled-versus-first-run production policy is selected by this story

**Given** the implementation and acceptance record
**When** Story 3.2 is independently reviewed
**Then** JVM/cache/cancellation tests and CI-equivalent gates pass, host and format facts are distinguished from device results, and every physical measurement, delivery decision, production wiring and applicable StrictMode obligation transferred below remains required in Story 3.8

**Closure boundary:** Story 3.2 can close independently after this replacement is approved, its independent acceptance gate is rerun and normal PR/CI gates pass. Approval alone does not mark it done or authorize a fabricated production decision. Its current before-merge hardware gate is superseded only for this revised infrastructure scope.

**Required follow-up:** `3-8-reference-device-pruning-table-delivery-decision-and-product` owns every transferred measurement, installed-size decision, architecture delivery-choice append and production wiring/StrictMode/Lesson-independence check. Production bundled-versus-first-run remains pending. Story 3.8 depends on accepted 3.2 and must pass before production acceptance of 3.7 and Epic 3/P0.S delivery readiness. Stories 3.3–3.6 may proceed against accepted APIs; 3.4's injected contract can be built before final loading integration in 3.8. NFR-3 physical corpus confirmation remains 12.2; no phase/release gate or requirement is lost.

**Current status:** 3.2 remains `review`, 3.8 `backlog`, Epic 3 `in-progress`. Independent acceptance must be rerun against the revised contract, followed by normal PR/CI gates and merge before 3.2 is done. No production decision or physical result is claimed.

## Historical original plan and gate evidence (superseded scope)

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

## Historical Auto Run Result (original acceptance contract)

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

## Amendment history

- 2026-11-02: User approved the 3.2 infrastructure / mandatory 3.8 device-delivery split; operative contract above replaces only the original hardware-before-merge condition. Historical gate failures remain recorded, without carrying that external high finding into revised 3.2 acceptance. Revised independent acceptance and CI/merge remain pending.
