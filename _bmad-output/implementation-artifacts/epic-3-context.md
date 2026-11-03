# Epic 3 Context: Solve any cube offline

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can enter any physical cube's colors, obtain a verified offline Solution from the clean-room Solver, and follow it through shared playback without taking a course. P0.S requires a representative solve corpus meeting the performance target and verification of every returned Solution. JVM evidence enables independent search development; it does not establish physical-device performance or production table delivery readiness.

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

Solving must work entirely offline. A valid entered state receives a Solution or a clear failure, never an invented or partial answer. The representative corpus includes random legal states and deep Scrambles, with solved plus at least 10,000 random legal states verified in CI. Story 3.3 reports median, p95, memory and failure rate with the actual host/JVM identity and retains zero failures and at least 95% within three seconds as a host regression check. The approved calibration deferral assigns measured reference-device-equivalent JVM calibration and a full calibrated corpus pass to mandatory Story 3.8; actual production device corpus confirmation remains Story 12.2. Search cancellation must stop promptly without publishing partial results.

The editor validates all stickers and cube legality, points to offending pieces, explains non-local parity/orientation failures, fixes centers to the home frame, and disables acceptance until valid. Color-blind glyphs remain available. No unvalidated or partially inferred state reaches solving.

Production installed size remains below the provisional 30 MB budget including tables and recognition models. Host timings, emulator measurements and APK file size do not substitute for required physical loading and installed-size evidence. The minimum physical device is Samsung Galaxy Tab S7 FE 5G. No INTERNET permission, backup, telemetry, advertising or crash SDK is permitted.

## Technical Decisions

Use an original Kotlin Kociemba two-phase implementation based on the published algorithm description. No third-party solver source, including GPL-only min2phase, may be copied; solver sources preserve provenance. Domain and solver logic remain pure JVM, isolated from Android and UI dependencies; app composition wires adapters.

Cube home frame is WHITE on U and GREEN on F. Facelets use URFDLB row-major indexing. CubeValidator alone constructs validated CubeState; failures are sealed values. CubeSolver accepts cubies only and returns Solved, Invalid, TimedOut or Failed. Moves are relative to centers; SolveCube maps them through orientation and re-verifies by applying them to the input before presentation. Only SolveCube and PlanRecovery call the Solver, as timed Effects; predicates, diagnosis and Placement never call it. Search and table work must remain off the main thread, independently of Lessons.

Pruning tables expose admissible phase heuristics from breadth-first phase projections. Optional private cache loading validates bounded versioned/checksummed data, serializes concurrent loads, replaces atomically and supports cancellation/retry without partial publication. Production bundled-versus-first-run delivery is deliberately pending Story 3.8 measurements and architecture memlog decision. First-run generation uses Dispatchers.Default and private storage; any chosen loading path stays off-main and never blocks startup or Lessons.

Session changes use SessionEvent and reduce; asynchronous results echo effect revision or are discarded. Solution playback uses the common pure PlaybackController, is not checkpointed, and derives every note from MoveNote.of(move): Text. Notes describe face, direction and piece movement, never claim strategic intent. Learner-facing text is keyed content, not screen literals.

## UX & Interaction Patterns

Solve is a separate bottom-navigation destination. Enter colors is available without camera; Scan my cube stays hidden until its feature lands. Starting a solve pauses an open Lesson with an explicit message. The editor returns a validated state to its caller rather than creating a session.

Solving shows cancelable indeterminate progress and a preparation message while tables build. Timeout preserves entered colors and offers retry without a partial answer. Playback offers forward/back, pause, replay and shared speed controls, with generated notes under the current Move. After the final step, a quiet Learn link offers teaching. Solves do not resume after app closure.

## Cross-Story Dependencies

Stories 3.1–3.4 form a parallel track depending on Epic 1. Search depends on accepted coordinate/move-table and pruning APIs, not a production packaging decision. SolveCube can implement its injected effect/port contract before production loading is selected. Editor and playback feed the integrated Solve flow.

The approved split permits independent closure of Story 3.2 infrastructure and development of 3.3–3.6. Story 3.8 retains physical cold-generation/warm-cache measurements, installed-size comparison, production delivery decision and integration, minimum-device StrictMode/independent-Lesson evidence, and the measured reference-device-equivalent JVM calibration plus calibrated corpus performance acceptance transferred from Story 3.3. It must pass before production end-to-end acceptance of 3.7 or Epic 3/P0.S delivery closure. Story 12.2 still confirms final physical solve-corpus performance; no performance requirement is waived.
