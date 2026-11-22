---
title: 'Story 15.2: Last-layer segments for 2-look and full OLL + PLL, preferring my Algorithms'
type: 'feature'
created: '2026-11-22'
status: done
baseline_revision: '28794a73cbbaaa9e728461af537fe12a38faa6c9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-1-build-a-beginner-method-solution-from-the-course-s-stages.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** `MethodSolve` (Story 15.1) only builds the beginner method. A Learner going faster cannot get a solution whose last layer uses the 2-look Lessons' Algorithms or one full OLL + PLL Algorithm per Case, preferring the Algorithms they marked *Learning* or *Learned* (FR-30, FR-17, FR-18).

**Approach:** Add `SolveMethod.TWO_LOOK` and `SolveMethod.FULL_OLL_PLL`. Reuse 15.1's first-two-layer segments (Stages 2–4), then rotate yellow to U once and build last-layer segments only from Library Algorithms, played verbatim with U adjustments, judged through `LastLayerMatcher`, each tagged `SegmentTag.OfCase(algorithm.caseId)`, with *Learning*/*Learned* Algorithms preferred. Extend `SolveByMethod` re-verification to these segments. Prove it on the FR-15 corpus.

Binding ADs (story Refs line, verbatim): **AD-19, AD-21**. Also followed: AD-6, AD-7, AD-8, AD-20.

PO clarifications (2026-11-22, answer the earlier blocked run):
1. Presentation: the 2-look and full last-layer segments start with one Cube Rotation (`z2` when yellow is on held D: keeps the front, brings yellow to U), emitted as the first Move of the first last-layer segment; then each Library Algorithm's Notation exactly as written (wide, slice and rotation Moves included), with U adjustments. `MethodSegment.moves` widens to `List<Move>`; 15.1's beginner output and tests keep working.
2. The final AUF after PLL is appended to the last Case segment. A lone AUF (no Case at all) becomes one segment tagged with the method's last-layer Stage.
3. Preference: a path using only *Learning*/*Learned* Algorithms beats a shorter path that does not (status wins over length); ties go to fewer applications.

## Boundaries & Constraints

**Always:**
- `MethodSolve` stays pure Kotlin in `:core:domain` and never calls `CubeSolver` (AD-8, AD-21). Output is deterministic for a given input, content and statuses.
- `MethodSolve.solve(state, method, bundle, statuses = emptyMap(), shouldContinue)`: `statuses: Map<AlgorithmId, AlgorithmStatus>`, absent = *Not started*. *Preferred* = status `LEARNING` or `LEARNED`.
- First two layers: exactly 15.1's Stage 2–4 segments (same search, same output). Beginner output is unchanged.
- Last-layer frame: after the first two layers, the cube is in the input's held orientation. If the last-layer color (YELLOW, opposite WHITE) is not on held U, one Cube Rotation brings it there: held D → `z2`, L → `z`, R → `z'`, F → `x`, B → `x'` (the only ones keeping the front where possible); none when already on U. It is the first Move of the first last-layer segment. No rotation and no last-layer segment when the last layer is already solved.
- An *application* is an optional held `U` pre-turn (`U`, `U2`, `U'`) followed by one Algorithm's `Notation.parse`d Moves verbatim (AD-7). Each application is one segment `OfCase(algorithm.caseId)`. Before every application yellow is on held U; an Algorithm whose Moves would leave yellow off U is never applied.
- Judging is only through `LastLayerMatcher` (AD-19): rungs by `LastLayerStepReached(step)` (`stepPieces`), full-method Cases by `candidates(view)` on `LastLayerView.of(state, YELLOW)`.
- 2-look set and looks: from `bundle.twoLookStages` Lessons in order; each Lesson's `completion` step (`LastLayerStepReached`) is a look, and its Algorithms are `bundle.lessonAlgorithms[lesson.id]`. Lessons sharing a step share one look, giving `EDGES_ORIENTED` (OLL 02, 45, 44), `ORIENTED` (OLL 21–27), `CORNERS_PERMUTED` (PLL T, Y), `SOLVED_UP_TO_AUF` (PLL Ua, Ub, H, Z). Each look is a breadth-first search over applications of that look's Algorithms, fewest applications first, up to `LL_MAX_APPLICATIONS` (6), states deduplicated by cubies.
- Full set: one Algorithm per Case. For each Case, the first of its Algorithms (content order) that is preferred, otherwise its primary (`-a`). Looks: `ORIENTED` with OLL Cases, then `SOLVED_UP_TO_AUF` with PLL Cases. Each look recognises the Case with `candidates` (restricted to that set), then tries its Algorithm after each pre-turn (none, `U`, `U2`, `U'`) and takes the first that reaches the look's step. A look already reached is skipped.
- Preference (clarification 3), per look: if any of the look's Algorithms are preferred, first search with only the preferred ones; if that reaches the step, use it. Otherwise search with all of the look's Algorithms. Within one search, fewer applications win; equal counts keep the search order (pre-turn order none, `U`, `U2`, `U'`, then Algorithm content order).
- Closing AUF: after the last look, the `U` turn (none first) that makes the cube `isSolved` is appended to the last Case segment. With no Case segment, it becomes one segment `[rotation?, AUF]` tagged `OfStage`: `stage-09-two-look-pll` for 2-look, `stage-06-last-layer-completion` for full (clarification 2: "PLL / last-layer-completion").
- `SolveByMethod` takes the statuses from an optional `AlgorithmStatusRepository` (`statuses().first()`; null repository or `Failed` load → no preference) and re-verifies 2-look/full results: Stage segments as today (Stage lookup also covers `bundle.twoLookStages`), every earlier Stage predicate still holds at each boundary, the input ends `isSolved`, and every last-layer segment conforms to its method via one domain check (`MethodSolve.conforms`): the first may start with a `CubeRotation`; then an optional `U` turn; then the exact parsed Moves of an Algorithm of the chosen set whose `caseId` equals the tag; the last may end with one `U` turn; a lone-AUF Stage segment holds only an optional rotation and one `U` turn. Any `OfCase` segment in a `BEGINNER` result still fails.

**Never:**
- No UI, reducer emission, `en.json`, content JSON, tutor-config or schema changes (Story 15.3 owns playback, copy and style choice).
- Never change Story 15.1's beginner segments or 2-look/full first-two-layer segments' search.
- Never define Case patterns or AUF judgment outside `LastLayerMatcher` (AD-19); never a non-Library Algorithm or a non-`U` adjustment in a 2-look/full last-layer segment.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved | `CubeState.SOLVED` | `Solved(emptyList())` for both methods | none |
| F2L only, home hold | LL scrambled, white on U | Case segments; first starts with `z2`; only set Algorithms + `U` turns; ends solved | none |
| Held yellow up | same, `reoriented` with yellow on U | no rotation Move | none |
| Lone AUF | solved then `D` (home hold) | one segment `[z2, U?]` tagged the method's LL Stage | none |
| Dot OLL, 2-look | edges-dot state; 45 and 44 preferred, 02 not | edge look = 45 then 44 (2 segments) | none |
| Dot OLL, none preferred | same, empty statuses | edge look = OLL 02 (1 segment) | none |
| Full, preferred alt | a Case with an extra preferred Algorithm (test bundle) | that Algorithm is used | none |
| Stopped | `shouldContinue` false | `Stopped` | no partial segments |
| Bad result (app) | injected Case segment with a non-set Algorithm or wrong Case tag | `Failed` | no Moves |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` -- `SolveMethod`, `SegmentTag`, `MethodSegment(tag, moves)`, the beginner build (F2L loop to share), `held()`. Widen `moves` to `List<Move>`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/BeginnerLastLayer.kt` -- the rung BFS pattern (effects composed as `Cubies`, `visited`, `shouldContinue` polling) to mirror in a new `FasterLastLayer.kt`; beginner stays home-frame, LL on D.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt` -- `of(cases, algorithms)` (Built/Invalid), `candidates(view)`, `stepPieces`. `LastLayerView.of(state, YELLOW)` keeps a yellow-up state's own front.
- `core/domain/src/main/kotlin/dev/cubentia/domain/predicate/LastLayerStepReached.kt` -- the rung judge (yellow found opposite white).
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/{CubeState,Move,Orientation}.kt` -- `apply` handles wide/slice/rotation; `Orientation.homeFaceAt`; quarter `x` brings F up, `z` brings L up; `isSolved` ignores orientation. An Algorithm's effect from orientation O composes: `state.cubies.compose(SOLVED.reoriented(O).apply(alg).cubies)`, new orientation from the same apply.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/{Model,TwoLook,AlgorithmLibrary}.kt` -- `ContentBundle.twoLookStages`, `lessonAlgorithms`, `cases`, `algorithms`, `Algorithm.isPrimary`, `Case.set`, `AlgorithmStatus`.
- `core/domain/src/main/resources/content/two-look/stage-0{8,9}-*.json`, `algorithms/{oll,pll}.json` -- read-only content.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` -- `MethodSolveFunction` (add statuses), `verified` (`OfCase` currently fails). `port/AlgorithmStatusRepository.kt` -- `statuses(): Flow<AlgorithmStatusLoad>`; `SetAlgorithmStatus.kt:42` reads it with `.first()`.
- Tests: `core/domain/src/test/kotlin/dev/cubentia/domain/method/{MethodSolveTest,MethodSolveCorpusTest,RandomLegalStates}.kt`, `core/application/src/test/.../usecase/SolveByMethodTest.kt` (helpers cast `moves` to `FaceTurn`; adjust for `List<Move>`).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` -- add `TWO_LOOK`, `FULL_OLL_PLL`, `statuses` param, `MethodSegment.moves: List<Move>`; extract the F2L loop for reuse; add the LL Stage constants and `conforms(segments, method, bundle)` -- FR-30, AD-21.
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/FasterLastLayer.kt` -- new: rotation, sets/looks, preferred-first search, full recognition, closing AUF, segments -- AD-19, AD-21.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` -- statuses from the optional repository; `MethodSolveFunction` passes statuses; re-verify 2-look/full via `MethodSolve.conforms` and Stage lookup incl. `twoLookStages`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/method/MethodSolveTest.kt` -- I/O matrix rows (domain), all set Algorithms keep yellow on U, determinism; adjust 15.1 helpers for `List<Move>`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/method/MethodSolveCorpusTest.kt` -- run TWO_LOOK and FULL_OLL_PLL over the same corpus (solved + 10,000 random + 100 deep); each: `Solved`, solves its input, F2L predicates hold at F2L boundaries, `conforms`, and an independent check that every LL segment is (rotation?)(U?)(set Algorithm)(U?) with a matching tag; print median/p95/max per method.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/SolveByMethodTest.kt` -- 2-look/full Solved echo; statuses read from a fake repository reach the solver; Failed on a wrong-tag or non-set Case segment; `OfCase` in BEGINNER still Failed.

**Acceptance Criteria:**
- Given a cube after the first two layers, when `MethodSolve` runs with `TWO_LOOK` or `FULL_OLL_PLL`, then the last-layer segments use only Library Algorithms matched through `LastLayerMatcher` (2-look: the 2-look Lessons' Algorithms; full: one per Case), tagged with Case IDs, and when more than one fits, an Algorithm with status *Learning* or *Learned* wins.
- Given the corpus, when verified, then every 2-look and full result solves its input and uses only Algorithms of the chosen set plus U adjustments (and the one leading Cube Rotation), and median/p95 are printed.
- Given the existing 15.1 tests, when the suite runs, then beginner results and tests pass (only type adjustments for `List<Move>`).

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 33 findings — high 0, medium 2, low 21, false 9, maybe-false 1
- findings:
  - `[low]` `[reject]` IA: the intent's value ("my Algorithms") lives at the app, but `SolveByMethod` is not wired and the status repository defaults to null — the intent limits this run to the story's ACs, which live at the `MethodSolve` surface; the epic context gives the Solve-tab wiring and Effect emission to Story 15.3. Recorded as a residual risk.
  - `[low]` `[reject]` IA: the corpus runs without statuses, so the "chosen set" under preference is not corpus-tested — preference only reorders which set Algorithm is tried; the dot-OLL and full-alternative unit tests plus the new SolveByMethod alternative test cover conformance with statuses.
  - `[false]` `[reject]` IA: preference is per look (C2), not over the whole last-layer path (C1) — clarification 3 answered the per-look question the blocked run asked (dot OLL: 02 vs 45+44), and AC 1's "when more than one fits" is a per-choice rule; status beats length is tested.
  - `[false]` `[reject]` IA: rotation generalised (z/z'/x/x' or none) instead of a literal z2 (D1 vs D2) — clarification 1 defines the rotation by its goals (bring yellow to U, keep the front); z2 meets them only when yellow is on D, and x/x' are the only single rotations bringing F/B up. The home hold gives z2 (tested).
  - `[false]` `[reject]` IA: lone-AUF tag stage-09 (2-look) / stage-06 (full) and the lone segment carries the rotation (E1 vs E2) — clarification 2 says "the method's" Stage and names two (PLL; last-layer-completion), one per method; dropping the rotation would need a held D turn, which AC 2 ("plus U adjustments") forbids.
  - `[false]` `[reject]` IA: 2-look picks Algorithms by search against matcher-judged steps, not by Case recognition (A1 vs A2) — 2-look looks are not whole Cases, so no pattern exists to recognise; every step is judged by `LastLayerMatcher.stepPieces` and no pattern is defined elsewhere (AD-19). Same reading accepted in 15.1; full uses `candidates`.
  - `[false]` `[reject]` IA: Case segments add no Stage predicate in `verified` — they are checked by `conforms` (tag, set, exact Moves), by every earlier Stage predicate, and by the final solved state, as the spec requires.
  - `[low]` `[patch]` VG: no `SolveByMethod` test of FULL_OLL_PLL using a preferred non-primary Algorithm from the repository — added a SolveByMethodTest case (alternative Algorithm, FakeStatuses LEARNING) expecting Solved with the alternative's Moves.
  - `[low]` `[patch]` VG: the `Halted` returns inside `breadthFirst`/`recognise` are never reached — added a counted `shouldContinue` test per method on a last-layer-only state expecting Stopped.
  - `[low]` `[reject]` VG other: nothing constructs `SolveByMethod`, so the null repository default silently disables preference — same as the wiring row: Story 15.3 owns construction; recorded as a residual risk.
  - `[low]` `[reject]` B: 2-look tags a Case the cube is not literally in (dot played as oll-45 then oll-44); verification never recognises the tagged Case — by design: AD-20 labels name the Case being practiced, which is the played Algorithm's Case; Case recognition in verification adds branches without a user-visible defect.
  - `[false]` `[reject]` B: BFS `visited` keyed by cubies only can prune a reachable look after y-bearing Algorithms — set Algorithms fix the first two layers, so playing one from another front is a U-conjugate; the reachable states differ only by a U turn and every look step is AUF-invariant. Corpus: 0 NotFound over 20,202 faster solves.
  - `[low]` `[reject]` B: a throwing, empty or hanging status Flow makes the solve Failed/TimedOut — the port reports failures as `AlgorithmStatusLoad.Failed`, handled as no preference; a throwing adapter is not shown reachable, and the fix adds a guard.
  - `[false]` `[reject]` B: preference is all-or-nothing and Learned ranks equal to Learning — clarification 3 defines exactly that rule ("only Learning/Learned" beats the rest, then fewer applications) and gives Learned no rank over Learning.
  - `[low]` `[reject]` B: 2-look look order follows content order, not step order — the shipped Lessons are in step order; reordering content is not a real scenario and validation adds branches.
  - `[low]` `[reject]` B: adjacent same-face turns (U pre-turn next to an Algorithm's U) are not merged — clarification 1 requires each Notation exactly as written, so merging would break it; cosmetic.
  - `[low]` `[reject]` B: the corpus checker always takes a leading U as the pre-turn — no shipped set Algorithm starts with a U turn (only x/x' starts were found), so it cannot misfire.
  - `[low]` `[reject]` B: `conforms` skips F2L order/FaceTurn-only checks, the rotation identity, and `held` casts to FaceTurn — only `MethodSolve` produces these segments; `SolveByMethod` still checks every Stage predicate at each boundary and the final state; the rotation check needs the held orientation as a new parameter.
  - `[false]` `[reject]` B: lone AUF shows [z2, U] instead of one D; x/x' do not keep the front — a D turn breaks AC 2 ("U adjustments"); with yellow on F/B no single rotation both keeps the front and brings yellow up (KDoc: "where possible").
  - `[low]` `[reject]` B: looks re-parsed and the matcher rebuilt per call; corpus timings have no budget — median 2–3 ms per faster solve; caching adds state for no measurable gain; the AC asks for times to be reported.
  - `[low]` `[reject]` B: missing tests (non-empty corpus statuses, preferred-only multi-application fallback, wrong rotation, out-of-order F2L) — the paths run in the dot-OLL and corpus tests; the rest are producer faults `MethodSolve` cannot emit.
  - `[low]` `[reject]` B: duplicated test helpers; member `setOf(method)` shadows `kotlin.collections.setOf` — cosmetic; resolves correctly by overload.
  - `[low]` `[reject]` B: `SolveByMethod` is not wired, so preference does nothing until a later story — duplicate of the wiring row; Story 15.3 owns it.
  - `[medium]` `[patch]` E: `conforms` always drops a leading `CubeRotation` on the first last-layer segment; yellow-up hold + first Algorithm starting with its own x (PLL Aa/Ab/E) is rejected and `SolveByMethod` returns Failed — fixed: the first and lone segments are accepted with or without dropping the leading rotation; new MethodSolveTest case on a yellow-up PLL-only state.
  - `[low]` `[patch]` E: the corpus checker copies the always-drop-the-rotation assumption — same root cause; fixed alongside.
  - `[low]` `[reject]` E: a preferred alternative that fails to parse or leaves yellow off U makes full NotFound with no primary fallback — content Notation is validated at load and no shipped alternative exists; a fallback adds branches.
  - `[low]` `[reject]` E: `conforms` accepts any leading rotation, not `rotationToTop` — producer-only fault; needs the held orientation as a new parameter.
  - `[low]` `[reject]` E: `conforms` accepts 2-look Case segments out of look order — producer-only fault; the final solved state and Stage predicates are still checked.
  - `[low]` `[reject]` E: status Flow throws or completes empty → Failed — duplicate of the B status row; same reasoning.
  - `[maybe-false]` `[reject]` E: a slow status load uses up the 3 s deadline — the Room-backed Flow's first emission is a local read; settle by timing `statuses().first()` on the reference device. If true it is low (TimedOut keeps *Shortest*).
  - `[false]` `[reject]` E: `visited` by cubies only prunes branches — see the B row: reachable states differ only by an AUF and every step is AUF-invariant.
  - `[medium]` `[patch]` E claim: with no leading rotation, an Algorithm's own x is dropped as if it were the leading rotation — same defect as the patched row above; fixed there.
  - `[low]` `[reject]` E claim: `conforms` is looser than the AC on the leading rotation — duplicate of the rotation-identity row.

## Design Notes

Rotation generalisation: clarification 1 names `z2` for the usual hold (white on U, so yellow on D). For other held orientations `z2` would not bring yellow up, so the same rule ("one rotation bringing yellow to U, keeping the front") picks the table above.

Preference is per look because each look is searched separately; a whole-LL joint search is not needed for the shipped sets, where every look is reachable in one application with all its Algorithms.

Example (2-look, home hold, OLL 45 line + Sune corners + T + Ua): `[OfCase(oll-45): z2 U F R U R' U' F'] [OfCase(oll-27): U' R U R' U R U2 R'] [OfCase(pll-t): …] [OfCase(pll-ua): … U2]`.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- set first.
- `./gradlew :core:domain:test --tests '*MethodSolve*'` -- expected: pass; corpus prints median/p95 per method.
- `./gradlew :core:application:test --tests '*SolveByMethod*'` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `rg -n "CubeSolver|core.solver" core/domain/src/main/kotlin/dev/cubentia/domain/method core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` -- expected: no matches.

## Auto Run Result

Status: done

**Summary:** `MethodSolve` now has `TWO_LOOK` and `FULL_OLL_PLL`. They reuse 15.1's first-two-layer segments unchanged, then (`FasterLastLayer`) rotate yellow to held U once (`z2` for the usual hold), and play Library Algorithms verbatim with `U` adjustments, one `OfCase` segment per application. 2-look searches each look (from the 2-look Lessons) breadth-first, preferred-only first; full recognises each Case with `LastLayerMatcher.candidates` and plays one Algorithm per Case (preferred, else primary). The closing AUF joins the last Case segment; a lone AUF is one segment tagged `stage-09-two-look-pll` / `stage-06-last-layer-completion`. `MethodSegment.moves` is `List<Move>`. `SolveByMethod` reads statuses from an optional `AlgorithmStatusRepository` and re-verifies via `MethodSolve.conforms`.

**Files:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` — new methods, statuses param, widened moves, shared F2L builder, LL Stage constants, `conforms`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/FasterLastLayer.kt` — new: rotation, sets/looks, preferred-first BFS, full recognition, closing AUF.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` — statuses from the repository, Case-segment re-verification, Stage lookup incl. 2-look Stages.
- Tests: `MethodSolveTest` (24), `MethodSolveCorpusTest` (3, all three methods), `SolveByMethodTest` (10).

**Review:** 33 findings (high 0, medium 2, low 21, false 9, maybe-false 1). Patched: 1 medium entry (conforms dropped an Algorithm's own leading x for yellow-up holds; 2 rows) and 3 low (corpus checker same root cause, SolveByMethod preferred-alternative test, mid-search stop test). Deferred: none. All other findings rejected with the reasons in the triage log. Follow-up review recommended: false (patched: 0 high, 1 medium entry).

**Verification:**
- `./gradlew spotlessCheck build`: BUILD SUCCESSFUL.
- Corpus (solved + 10,000 random + 100 deep, seed 20261122), 0 failures each, in the full build: BEGINNER median 7.28 ms / p95 30.61 ms; TWO_LOOK median 3.87 ms / p95 43.05 ms (82 Moves mean); FULL_OLL_PLL median 3.38 ms / p95 23.99 ms (69 Moves mean).
- No `CubeSolver`/`core.solver` reference in the method code or `SolveByMethod`.

**Residual risks:**
- `SolveByMethod` is not constructed in the app yet; Story 15.3 must pass the real `AlgorithmStatusRepository`, or preference is silently off (null default).
- Preference is per look; a whole-last-layer preferred path that needs a different earlier-look choice is not searched.
- Re-verification does not check that the leading rotation is the one `rotationToTop` picks (producer-only fault).
- Reference-device timing is unmeasured (15.1's cold-start deferral still applies).
