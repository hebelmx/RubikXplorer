# Epic 3 Context: Solve any cube offline

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner who just wants a stuck cube solved can enter its colors, get a verified Solution from the in-house clean-room Solver, and step through it with a plain note per Move, all offline and without taking the Course. Solve is a secondary tab, not the front door; it must never freeze the app, invent an answer, slow down Lessons or push the app over its size budget. Epic 3 (P0.S) closes only when pruning-table delivery is decided and integrated from real reference-device measurements, the solve corpus meets the performance target on a calibrated reference-device-equivalent profile, and every returned Solution is re-verified.

## Stories

- Story 3.1: Solver coordinates and move tables (clean room)
- Story 3.2: Pruning tables, cached off the main thread
- Story 3.3: Two-phase search verified on a 10,000-state corpus
- Story 3.4: SolveCube use case
- Story 3.5: Enter a cube in the Manual Cube Editor
- Story 3.6: Step through a Solution with generated notes
- Story 3.7: Solve tab end to end
- Story 3.8: Reference-device pruning-table delivery decision and production integration

## Requirements & Constraints

- Solving works fully offline. Invalid input is rejected before the Solver runs; a timeout or failure yields an explicit error with a recovery prompt, never a made-up or partial Solution. The Solver never blocks the UI thread.
- Every `Solved` result is re-verified by applying it to the input. The corpus is the solved state plus at least 10,000 random legal states including deep Scrambles, with zero failures.
- Performance: at least 95% of the corpus solved within 3 s, with median, p95, memory and failure rate reported. Uncalibrated host JVM results are only a regression check. Reference-device equivalence needs a JVM profile calibrated from measured paired device/JVM runs, with device/Android and host/JVM identities, revision, seed, warmup/table-readiness boundaries and run conditions linked. Final physical corpus confirmation belongs to Story 12.2. Emulator or host timings never substitute for device evidence.
- Minimum reference device: Samsung Galaxy Tab S7 FE 5G. Pruning-table measurements record cold-process generation duration, payload/cache bytes and warm cache-load duration, with device/Android identity, revision, run conditions and available memory.
- Install size below 30 MB including Solver tables (recognition models count only from v1.1). Installed size is compared between a release-equivalent unbundled baseline and a bundled candidate; APK file size alone is not installed-size evidence.
- Debug StrictMode must report no main-thread disk access on cold and warm table-loading paths, logs are inspected, and Lessons remain independently usable.
- A story requiring hardware evidence stays open if any required measurement, calibration, decision record or production verification is missing; nothing is waived or fabricated.
- Editor validation rejects wrong color counts, impossible pieces, a twisted corner, a flipped edge and parity violations, explaining each plainly. Notes follow a fixed template (face + direction + piece group) and never claim strategic purpose.
- v1.0 is camera-free: no `INTERNET`, no runtime permissions, no backup, telemetry, ads or crash SDK. *Scan my cube* is deferred to v1.1; its flag stays OFF in release.

## Technical Decisions

- `CubeSolver` is an original Kotlin Kociemba two-phase implementation written from the published description; no third-party solver code (notably min2phase) is copied, and sources cite provenance. Solver and domain code stay pure JVM; Hilt only in `:app`.
- Only `CubeValidator` constructs `CubeState`. The Solver receives `cubies` only and returns centers-relative Moves as `SolveResult.{Solved, Invalid, TimedOut, Failed}`. Only `SolveCube` and `PlanRecovery` call it, always as a timed (3 s) Effect; `SolveCube` maps Moves back through `orientation` and re-verifies. Errors are sealed values; effect failures become `…Failed` events, never crashes.
- Pruning tables: generation on `Dispatchers.Default`, disk on an I/O dispatcher, versioned/checksummed app-private cache, serialized loads, atomic replace, cooperative cancellation with no partial publication. Bundled vs first-run delivery is chosen from device and installed-size measurements; either mode keeps loading off-main, Lessons never wait for readiness, and no app-startup wait is added.
- The delivery decision (rationale, tradeoffs, evidence) is appended to the architecture memlog with `uv run _bmad/scripts/memlog.py append`.
- StrictMode runs in debug only; Android vitals is the only field signal.
- Sessions: one `LearningSession` app-wide via `SessionHost`. Solution Playback is `SessionKind.SOLVE_PLAYBACK`, suspends an open Lesson, is never checkpointed and never resumed on cold start. Async results echo `effectId`/`revision` and are dropped when stale. The Manual Cube Editor is not a session. Playback uses the shared `PlaybackController`; note text comes from `MoveNote.of(move): Text`.
- Feature visibility comes from the `FeatureFlags` port (`CAMERA_SCAN`), never `BuildConfig`.

## UX & Interaction Patterns

- Solve is a bottom-nav tab; in v1.0 *Enter colors* is its only action. With a Lesson open, "Starting a solve pauses your lesson." shows before suspending it.
- Solving shows a cancelable indeterminate progress bar; while tables build, "Preparing the solver (one time, a few seconds)." Timeout/failure: "Couldn't find a solution in time. Your colors are still here — try again." with no partial result.
- Playback is a full-screen flow with the shared transport and the MoveNote under the current Move; after the final step a quiet "Want to learn how to do this yourself?" links to Learn.

## Cross-Story Dependencies

- Stories 3.1–3.6 are done. Story 3.7 is merged but stays in review until Story 3.8 passes; 3.8's last criterion is what unblocks 3.7 production acceptance and Epic 3/P0.S closure.
- Story 3.8 builds on the accepted 3.2 measurement harness and cache provider, integrates production loading behind the 3.4 `SolveCube` port, and owns the calibration and calibrated-corpus evidence deferred from 3.3. Table loading also serves `PlanRecovery` (Epic 4).
- Story 3.8 runs first in the device batch (with 1.11, 12.2 and the 12.1 physical pass) once the reference devices are connected; Story 12.2 later confirms physical corpus performance.
