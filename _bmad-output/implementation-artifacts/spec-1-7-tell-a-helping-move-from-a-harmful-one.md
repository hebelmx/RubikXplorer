---
title: 'Story 1.7: Tell a helping Move from a harmful one (Objective proof of concept)'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '05dae0b5054281e49c1d08389eb27a16c60b9bcf'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Nothing in the engine can judge a cube against a goal, so later Lessons could only check end states. The P0.1 exit criterion (a random Scramble applies, renders and validates with no mismatch, and a predicate proof of concept works end to end) is not yet demonstrable.

**Approach:** Add a pure `predicate` package to `:core:domain` with one color-anchored, orientation-invariant predicate, `WhiteCrossSolved`, exposing satisfaction, `preservedPieces` and an `ObjectiveProgress` measure. On the free cube, a debug-only Scramble button and a debug-only Objective overlay show the predicate result, the progress change, validation and render sync after every Move.

## Boundaries & Constraints

**Always:**
- Domain, in `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/`:
  - `sealed interface Predicate { fun isSatisfied(state: CubeState): Boolean; fun preservedPieces(state: CubeState): Set<Piece>; fun progress(state: CubeState): ObjectiveProgress }`.
  - `data object WhiteCrossSolved : Predicate` — the white cross aligned: each of the 4 edges on the WHITE-center face shows WHITE there, and its other sticker matches the center color of the face it sits on.
  - **Color-anchored:** evaluate on `state.toFacelets()`: find the face whose center sticker is `Color.WHITE`, and read each adjacent face's color from its center sticker. Never assume WHITE is on Learner U or home U. Reuse `FaceletTables` (`EDGE_FACELETS`, `center`) — it is `internal` to `:core:domain`, so it is visible from this package.
  - `data class Piece(val colors: Set<Color>)` names a cubie by its sticker colors (2 for an edge, 3 for a corner), which is orientation-invariant.
  - `preservedPieces(state)` = the cross edges that are currently correct, e.g. `{Piece({WHITE, GREEN})}`. All 4 when satisfied; empty when none is.
  - `data class ObjectiveProgress(val done: Int, val total: Int) : Comparable<ObjectiveProgress>`; `require(total > 0 && done in 0..total)`; `compareTo` requires equal `total` and compares `done`. For `WhiteCrossSolved`, `done` = number of correct cross edges, `total` = 4, so `isSatisfied(state) == (progress(state).done == 4)` and `preservedPieces(state).size == progress.done`.
  - All three functions are pure and ignore `state.orientation` in effect: identical results for `state.copy(orientation = o)` for every `o` in `Orientation.ALL`.
- App:
  - `app/src/main/kotlin/dev/rubikxplorer/app/random/KotlinRandomSource.kt`: `class KotlinRandomSource(private val random: Random = Random.Default) : RandomSource`, the first `RandomSource` adapter.
  - `FreeCubeViewModel` gains a constructor param `scramble: NewScramble = NewScramble(KotlinRandomSource())` (keep every param defaulted so `by viewModels()` still works) and `onScramble()`: reset the history, commit the 25 Scramble Moves in order (each undoable), then submit one `PresentationItem.Snap` to the scrambled facelets.
  - The ViewModel exposes `lastChange: ProgressChange?` (`CLOSER`, `UNCHANGED`, `FURTHER`, an app-side enum), recomputed on every history change (Move, Undo, Redo, Reset, Scramble) by comparing `WhiteCrossSolved.progress` of the previous state and the new state; `null` until the first change.
  - Debug section (only when `showDebugToggles`): a Scramble button tagged `debug:scramble` (≥ 48dp), and a second labeled switch row `debug:objective` (same pattern as `debug:glyphs`) that shows the overlay, tagged `debug:objectiveOverlay`, under the toggles. Overlay lines, each tagged:
    - `objective:satisfied` — "White cross: solved" / "White cross: not solved".
    - `objective:progress` — "Progress: 3/4".
    - `objective:change` — "Last Move: closer" / "no change" / "further"; "Last Move: —" when `null`.
    - `objective:valid` — "Validates: yes" iff `CubeValidator.validate(state.toFacelets())` is `Valid` and its `state.cubies == state.cubies`; otherwise "Validates: no".
    - `objective:sync` — "Rendered: in sync" iff `renderer.renderedFacelets() == history.state.toFacelets()`, else "Rendered: catching up" (read in composition; both are snapshot state).
  - All overlay and button text lives in `res/values/strings.xml` with format args; no English literals in Kotlin.

**Never:**
- No `@Serializable`, JSON, registry, `allOf`/`anyOf` or other predicates — Story 2.2 owns those. No kotlinx.serialization dependency.
- No `LearningSession`, `SessionEvent`, `Assess`, Diagnosis or Learner-facing coaching copy; the overlay is debug-only.
- No new Gradle module or port; never read `BuildConfig`; never edit planning artifacts, `_bmad/` or `.claude/`.
- Do not change existing Move key, toolbar or glyph behavior or their test tags.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved | `SOLVED` | satisfied; progress 4/4; 4 preserved pieces | none |
| Partly solved | `SOLVED.apply(F)` | not satisfied; progress 3/4; preserved lacks `{WHITE, GREEN}` | none |
| Cross only | `SOLVED.apply(R', D, R)` (U-layer edges untouched) | satisfied, though not `isSolved` | none |
| Misaligned cross | `SOLVED.apply(U)` | not satisfied; progress 0/4 (white on top, sides wrong) | none |
| Helping Move | fixture `SOLVED.apply(R', D, R, F)`, apply `F'` | progress rises 3 → 4 | none |
| Harmful Move | same fixture, apply `R` | progress falls (≤ 3) | none |
| Rotated | any fixture with `orientation = o`, all 24 `o` | same satisfied, progress, preservedPieces | none |
| Mismatched totals | `ObjectiveProgress(1, 4).compareTo(ObjectiveProgress(1, 5))` | throws `IllegalArgumentException` (programmer error) | require |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Facelets.kt`: `FaceletTables` (internal) — `EDGE_FACELETS[slot] = [ref index, other index]`, `center(face)`, `CENTERS`. Facelet index = `face.ordinal*9 + row*3 + col`; face of an index = `Face.entries[index / 9]`.
- `.../cube/CubeState.kt`: `toFacelets()` applies `orientation`; `copy(orientation = o)` builds rotated fixtures; `isSolved`.
- `.../cube/Orientation.kt`: `Orientation.ALL` (24).
- `.../cube/CubeValidator.kt`: `CubeValidator.validate(Facelets): ValidationResult`; `ValidationResult.Valid.state`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/AllMoves.kt`: `allMoves` (54) and kotest arbs; test helpers are in the `cube` test package, so import or copy what the predicate test needs.
- `core/application/.../usecase/NewScramble.kt`: `NewScramble(random)()` → 25 `FaceTurn`s; `.../port/RandomSource.kt`: `nextInt(until)`. `core/application/src/test/.../SeededRandomSource.kt` is test-only in another module; in app tests use `KotlinRandomSource(Random(seed))`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt`: `history`, `revision`, `port.submit(...)`; add `onScramble`, `lastChange` and route all history writes through one private setter that recomputes `lastChange`.
- `app/.../ui/freecube/FreeCubeScreen.kt`: `DebugToggles(viewModel)` — add the objective switch, Scramble button and overlay here (or in a new `ObjectiveOverlay.kt` in the same package).
- `app/.../render/Net2DRenderer.kt`: `renderedFacelets()` is backed by `mutableStateOf`.
- `app/src/main/res/values/strings.xml`: add the debug strings.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt`: `launch()` builds `FreeCubeViewModel()`; extend it to accept a ViewModel; `assertRenderedMatchesState()` and `tap(move)` helpers are reusable.
- `app/src/test/kotlin/dev/rubikxplorer/app/flags/BuildConfigUsageTest.kt`: scans sources for `BuildConfig`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,WhiteCrossSolved,Piece,ObjectiveProgress}.kt` -- add per Always -- FR-5, AD-6.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/WhiteCrossSolvedTest.kt` -- every I/O row; solved, a fixed 25-Move Scramble, and partly solved fixtures each checked in all 24 orientations; a pinned-seed kotest property: for random face-turn sequences, results are identical across all 24 orientations and `preservedPieces.size == progress.done`; for each of the 18 face turns `m` that breaks the cross from `SOLVED`, `progress(SOLVED.apply(m, m.inverse())) > progress(SOLVED.apply(m))` -- AC 1–2.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/ObjectiveProgressTest.kt` -- ordering, bounds and mismatched-total `require`s.
- `app/src/main/kotlin/dev/rubikxplorer/app/random/KotlinRandomSource.kt` -- the adapter.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/{FreeCubeViewModel,FreeCubeScreen}.kt` (+ optional `ObjectiveOverlay.kt`), `app/src/main/res/values/strings.xml` -- Scramble, `lastChange`, toggle and overlay per Always.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/ObjectiveOverlayTest.kt` (Robolectric Compose, same setup as `FreeCubeScreenTest`) -- overlay hidden until `debug:objective` is on and absent when `showDebugToggles = false`; on a seeded Scramble, after idle: `renderer.renderedFacelets() == history.state.toFacelets()`, the overlay reads "in sync" and "Validates: yes", and satisfied/progress text equals `WhiteCrossSolved` on the state; then from solved tap `F` → "not solved", "3/4", "further"; tap `F'` → "solved", "4/4", "closer"; tap `D` → "no change"; Undo/Redo update the lines; Scramble is undoable Move by Move -- AC 3.

**Acceptance Criteria:**
- Given `WhiteCrossSolved` with `preservedPieces` and `ObjectiveProgress`, when it is evaluated on solved, scrambled and partly solved fixtures in all 24 orientations, then satisfaction, progress and preserved pieces are identical across orientations.
- Given a fixture one Move from the cross, when the helping Move and a harmful Move are each applied, then `ObjectiveProgress` increases for the helping Move and decreases or stays for the harmful one.
- Given the free cube in a debug build, when the objective overlay is on and a random Scramble is applied followed by Moves, then after each Move the overlay shows the predicate result and the progress change, the cube validates, and the rendered net equals the engine state.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes, including the Kover rules and all AD-13/AD-18 gates.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 28 findings — high 0, medium 0, low 22, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) after Scramble or Reset the line still says "Last Move: closer/further" though no single Move caused it — real, but the intent contract explicitly says `lastChange` is recomputed on Reset and Scramble; the fix is to edit this build's spec, and the value it shows (the progress change of the last history change) is accurate.
  - `[low]` `[reject]` (blind) the ViewModel computes `WhiteCrossSolved.progress` twice per input even with the overlay off — microseconds per tap; gating it adds state and branches for no felt cost.
  - `[low]` `[patch]` (blind) "Rendered: catching up" is never tested — `renderedFacelets()` is `mutableStateOf`, so the line does recompose; the test gap is real. Patched: `the sync line reads catching up until the net settles` (paused clock).
  - `[low]` `[reject]` (blind) "Validates: no" is untested — engine states are always valid, so the branch cannot be reached through the UI; testing it needs a seam that injects an invalid state.
  - `[low]` `[reject]` (blind) the overlay recomputes validation and the predicate on every recomposition — debug-only, microseconds; `remember` adds keys for no felt cost.
  - `[low]` `[reject]` (blind) debug strings ship as translatable main resources; "—" is a poor TalkBack label — no other locales exist, so no MissingTranslation; moving them to `src/debug/res` would break the release compile of `ObjectiveOverlay`; the overlay is developer-only.
  - `[low]` `[patch]` (blind) `preservedPieces` is never checked against Moves, and the harmful check is only `<=` — patched: helping `F'` keeps `preservedPieces(fixture)` as a subset; `R` removes a piece and strictly lowers progress.
  - `[low]` `[reject]` (blind) `Piece` does not require 2–3 colors — only `WhiteCrossSolved` builds pieces, always with 2 colors; a guard adds code for an unreachable case.
  - `[false]` `[reject]` (blind) `ObjectiveProgress.compareTo` throws on different totals — intended loud programmer-error failure, documented in the KDoc and the I/O matrix; no caller compares across Objectives.
  - `[low]` `[patch]` (blind) `FreeCubeScreenTest.launch()` gained an unused `model` parameter — patched: removed.
  - `[false]` `[reject]` (blind) the objective toggle state is lost when `DebugToggles` leaves composition — `showDebugToggles` is a compile-time constant, so the section never leaves composition at runtime, and `rememberSaveable` survives configuration changes.
  - `[low]` `[reject]` (blind) `KotlinRandomSource` has no test — a one-line delegate to `Random.nextInt`; the port contract already states `until` is positive; it is exercised by the seeded overlay tests.
  - `[low]` `[reject]` (blind) Scramble UI tests rely on `NewScramble`'s length and on reproducing the seeded sequence — reproducing a seeded sequence is the intended test pattern (AD-11); a change to `NewScramble` drawing should fail these tests.
  - `[low]` `[reject]` (blind) Redo after an undone Scramble is untested — `MoveHistory.redo` is covered by `MoveHistoryTest`, and the Scramble commits ordinary Moves.
  - `[low]` `[patch]` (verification-gap) the overlay's sync and validation lines are only checked in their passing state — grouped with blind row 3; the catching-up half patched there; the "Validates: no" half rejected as in blind row 4.
  - `[low]` `[patch]` (verification-gap) `lastChange` is never asserted after a Scramble or the Moves that follow — patched: the seeded-Scramble test records progress before the Scramble and each of 5 Moves and asserts the change line from the before/after comparison.
  - `[low]` `[patch]` (verification-gap) unused `model` parameter — same as blind row 10; patched.
  - `[low]` `[reject]` (edge) Scramble's `lastChange` describes 26 combined steps — same root as blind row 1; rejected there.
  - `[low]` `[reject]` (edge) Reset is reported as "closer" — same root as blind row 1; rejected there.
  - `[low]` `[patch]` (edge) a stale "Last Move" after the Scramble goes undetected — same as verification-gap row 2; patched.
  - `[low]` `[patch]` (intent) no single UI test tells a helping Move from a harmful one on a scrambled cube — grouped with verification-gap row 2: the seeded-Scramble test now asserts the change line after each post-Scramble Move.
  - `[false]` `[reject]` (intent) "random" is seeded in every test — seeds only reproduce tests (AD-11); the runtime default is `Random.Default`.
  - `[false]` `[reject]` (intent) "renders" is `renderedFacelets()`, not pixels — that is the AD-4 contract for render/engine agreement; sticker colors are also checked through semantics in `FreeCubeScreenTest`.
  - `[low]` `[reject]` (intent) the overlay reports a change after Scramble/Reset — same root as blind row 1.
  - `[false]` `[reject]` (intent) additions beyond the ACs (Scramble action, adapter, shared switch, 48dp test) — the Scramble and its `RandomSource` adapter are required for AC 3's "random Scramble"; the shared switch is a refactor with no behavior change; no bad outcome named.
  - `[low]` `[patch]` (intent) `preservedPieces` tested only for size and invariance — same as blind row 7; patched.
  - `[false]` `[reject]` (intent) the AR-10 registry, `allOf`/`anyOf` and JSON are absent — Story 2.2's ACs own those; this story's intent is its own ACs.
  - `[low]` `[reject]` (intent) the overlay toggle is screen state, not ViewModel state — it survives configuration changes via `rememberSaveable`; nothing else reads it.

## Design Notes

**Why facelets, not cubies.** Cubies are already relative to the centers, so a cubie check would be orientation-invariant too, but reading the Learner's stickers and finding WHITE by its center is the literal color anchoring AD-6 asks for, and it is what Story 2.2's other predicates will copy.

**Why a count.** `done` = correct cross edges is the simplest measure that makes the helping/harmful AC hold: the helping Move restores one edge; a harmful Move can only keep or lose edges. Finer measures (white-on-top, distance) wait for Diagnosis in Epic 4.

## Verification

**Commands:** (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :core:domain:check` -- expected: predicate tests pass; `koverVerify` and `koverVerifyCube` pass.
- `./gradlew :app:testDebugUnitTest` -- expected: `ObjectiveOverlayTest`, `FreeCubeScreenTest` and the flags tests pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Story 1.7 adds the first Completion Predicate and shows it working on the free cube.
- **Domain:** a new `predicate` package in `:core:domain`: a sealed `Predicate` (`isSatisfied`, `preservedPieces`, `progress`), `WhiteCrossSolved` (color-anchored: it finds WHITE by its center in the Learner's facelets), `Piece` (named by its colors) and `ObjectiveProgress` (`done`/`total`, comparable within one total).
- **App:** a `KotlinRandomSource` adapter; `FreeCubeViewModel.onScramble()` (reset, then commit the 25 `NewScramble` Moves, each undoable, drawn as one Snap) and `lastChange` (closer / no change / further); a debug-only Objective overlay switch and Scramble button. The overlay shows white cross solved/not, progress n/4, the last change, "Validates" (validator round-trip) and "Rendered" (net vs engine).

**Files changed:**
- `core/domain/.../predicate/{Predicate,WhiteCrossSolved,Piece,ObjectiveProgress}.kt` (new): the predicate model.
- `core/domain/src/test/.../predicate/{WhiteCrossSolvedTest,ObjectiveProgressTest}.kt` (new): fixtures in all 24 orientations, a pinned-seed property, helping vs harmful Moves, bounds.
- `app/.../random/KotlinRandomSource.kt` (new): the first `RandomSource` adapter.
- `app/.../ui/freecube/FreeCubeViewModel.kt`: `onScramble`, `lastChange`, one private history writer.
- `app/.../ui/freecube/FreeCubeScreen.kt`: shared `DebugSwitch`, the objective switch and the Scramble button.
- `app/.../ui/freecube/ObjectiveOverlay.kt` (new): the five tagged overlay lines.
- `app/src/main/res/values/strings.xml`: debug strings.
- `app/src/test/.../ObjectiveOverlayTest.kt` (new, 7 tests): AC 3 at the screen surface.

**Review findings:** 28 (high 0, medium 0, low 22, false 6, maybe-false 0).
- **Patched (4 entries, all low, all test-only):** a paused-clock "catching up" test; change-line assertions after the Scramble and each following Move; `preservedPieces` subset/removal checks and a strict decrease for the harmful Move; removal of an unused test parameter.
- **Deferred:** none.
- **Rejected:** the "Last Move" label after Scramble/Reset (the intent contract defines it that way; the fix would edit the spec); the double progress computation; the unreachable "Validates: no"; recomposition cost; debug strings in main resources; no `Piece` size guard; no `KotlinRandomSource` test; seeded-sequence coupling; untested Redo after Scramble; the screen-level toggle state; and 6 false findings. Every reason is in the Review Triage Log.

**Follow-up review recommended:** false. Patched counts: high 0, medium 0, low 4.

**Verification:**
- `./gradlew spotlessCheck build` exited 0 after the patches (Kover module and cube gates, AD-13/AD-18 gates included).
- `WhiteCrossSolvedTest` 10/10, `ObjectiveProgressTest` 4/4, `ObjectiveOverlayTest` 7/7, `FreeCubeScreenTest` 16/16.
- Matrix audit: every I/O row (solved, partly solved, cross only, misaligned, helping, harmful, rotated, mismatched totals) is covered by a passing test in `WhiteCrossSolvedTest` or `ObjectiveProgressTest`.

**Residual risks:**
- The 25-Move scrambled fixture in `WhiteCrossSolvedTest` is hand-written, not produced by `NewScramble`.
- "Validates: no" cannot be reached from the UI and is untested.
- After Reset or Scramble the overlay's "Last Move" line reports the progress change of that action, not of a single Move.
- `Predicate` is not yet `@Serializable` and has no registry; Story 2.2 adds those.
