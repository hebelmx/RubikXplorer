# Epic 3 Context: Solve any cube offline

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner who just wants a stuck cube solved can enter its colors, get a verified Solution from the in-house clean-room Solver, and step through it with a plain note per Move, all offline and without taking the Course. Solve is a secondary tab, not the front door; it must never freeze the app, invent an answer, or slow down Lessons. P0.S closes only when the solve corpus meets the performance target on reference-device-equivalent evidence and every returned Solution is re-verified.

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
- Every `Solved` result is re-verified by applying it to the input. CI verifies solved plus at least 10,000 random legal states including deep Scrambles.
- Performance: 95% of the representative corpus solved within 3 s on the reference device, with median, p95, memory and failure rate reported. Host JVM results are only a regression check; calibrated reference-device-equivalent evidence belongs to Story 3.8, and final physical confirmation to Story 12.2. Minimum device: Samsung Galaxy Tab S7 FE 5G. Emulator or host timings never substitute for device evidence.
- Install size below 30 MB including Solver tables (recognition models count only from v1.1). APK file size alone is not installed-size evidence.
- Editor validation rejects wrong color counts, impossible pieces, a twisted corner, a flipped edge and parity violations, explaining each plainly; 54 entered colors never count as valid by themselves. Color-blind glyphs stay available.
- Notes follow a fixed template (face + direction + piece group, e.g. "Turn the Right face clockwise: the right column moves up") and never claim strategic purpose. In Physical-cube Mode, playback advances one step per *Done* tap.
- v1.0 is camera-free: no `INTERNET`, no runtime permissions, no backup, telemetry, ads or crash SDK. Camera recognition (and *Scan my cube*, Quick Check) is deferred to v1.1; its flags stay OFF in release.

## Technical Decisions

- `CubeSolver` is an original Kotlin Kociemba two-phase implementation written from the published description; no third-party solver code (notably GPL min2phase) is copied, and sources cite provenance. Solver and domain code stay pure JVM.
- Home frame: WHITE on U, GREEN on F; facelets URFDLB row-major. Only `CubeValidator` constructs `CubeState`, so unvalidated input cannot reach the Solver by type. Failures are sealed values; Learner text is `Text(key, args)` content, never screen literals.
- The Solver receives `cubies` only and returns centers-relative Moves as `SolveResult.{Solved, Invalid, TimedOut, Failed}`. Only `SolveCube` and `PlanRecovery` call it, always as a timed (3 s) Effect; predicates, diagnosis and Placement never do. `SolveCube` maps Moves back through `orientation` and re-verifies.
- Pruning tables: off-main (`Dispatchers.Default` for generation, I/O dispatcher for disk), versioned/checksummed private cache, serialized loads, atomic replace, cooperative cancellation with no partial publication. Bundled vs first-run delivery is decided in Story 3.8 from device measurements and recorded via the architecture memlog. Lessons and app startup never wait on tables.
- Sessions: at most one `LearningSession` app-wide, held by a singleton `SessionHost`. Solution Playback is `SessionKind.SOLVE_PLAYBACK`; starting it suspends an open Lesson. `SOLVE_PLAYBACK` is never resumed on cold start. All changes go through `SessionEvent` + pure `reduce`; async results echo `effectId` and `revision` and are discarded when stale. The Manual Cube Editor is not a session; it returns a validated `CubeState` to its caller.
- Playback uses the shared pure `PlaybackController` (also used by Demonstrations and the Library), is never checkpointed, and takes all note text from `MoveNote.of(move): Text`.
- Feature visibility (e.g. *Scan my cube*) comes from the `FeatureFlags` port (`CAMERA_SCAN`), never `BuildConfig`.

## UX & Interaction Patterns

- Solve is one of four bottom-nav tabs (Learn, Solve, Library, You; rail at medium/expanded widths). In v1.0 *Enter colors* is its only action; *Scan my cube* stays hidden until v1.1.
- With a Lesson open, starting a solve shows "Starting a solve pauses your lesson." before suspending it; the Lesson later resumes from its Checkpoint (a paused physical Lesson returns through *Confirm your cube*).
- Solving shows a cancelable indeterminate progress bar; while tables build, "Preparing the solver (one time, a few seconds)." Timeout/failure: "Couldn't find a solution in time. Your colors are still here — try again." with no partial result.
- Playback is a full-screen task flow (bottom bar hidden, top app bar with back) with the shared transport: step back/forward, play/pause, replay, speed 0.5×/1×/2×, step counter, current Move in large notation with its MoveNote beneath. After the final step a quiet "Want to learn how to do this yourself?" links to Learn. Solves are not resumed after app closure.
- Editor: tap a sticker, then a color; invalid pieces outlined in `status-invalid` with labels; non-local errors on their own lines (twisted corner, flipped edge, swapped pieces); **Accept** disabled until valid. Mistakes in neutral ink; no exclamation marks.

## Cross-Story Dependencies

- Stories 3.1–3.6 are done; 3.7 is in progress; 3.8 is in backlog.
- 3.7 integrates the editor (3.5), `SolveCube` (3.4) and playback (3.6) behind the Solve tab, and relies on `SessionHost` suspend/resume from Epics 6 and 8.
- Open Epic 8 retro action before 3.7 wires physical playback: stop `stepBack` at an undo barrier, remove the local double-reduce in `SolutionPlayback.kt`, and test against a real `SessionHost`.
- 3.7 can be implemented against the current table-loading path, but its production end-to-end acceptance, and Epic 3/P0.S closure, are blocked until Story 3.8 passes (device measurements, installed-size comparison, delivery decision + memlog, production integration, StrictMode/independent-Lesson evidence, calibrated 10,000-state corpus). Story 12.2 confirms final physical corpus performance; no performance requirement is waived.
