---
title: 'Story 15.1: Build a beginner-method solution from the Course''s Stages'
type: 'feature'
created: '2026-11-22'
status: 'done'
baseline_revision: '227a3e900ab3e0877c3cd1d6edd49c62d5ae9705'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      The first solve builds the pair and triple distance tables (up to about 220 triple BFS tables) without polling shouldContinue, so a cold-start solve could overrun the 3 s deadline on the reference device.
    evidence: |-
      Not verified. PieceBound.init calls PieceDistances.table, which runs a BFS and never polls. withTimeoutOrNull can only return once the blocking build ends. The corpus test warms up before timing, which hides the cost. To settle it, time a cold first MethodSolve on the Lenovo Tab M10 Plus 3rd Gen, or pre-warm the tables or poll during the build.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/tutor/PieceDistances.kt (table/build), PieceBound.init
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The Solve tab can only show the Solver's shortest path, which teaches no method (FR-30, GitHub #210). There is no domain service that builds a solution shaped like the beginner Course, one segment per Stage.

**Approach:** Add `MethodSolve` in `:core:domain`. `MethodSolve(BEGINNER)` builds five segments tagged with Course Stage IDs, in order. The first-two-layer segments (cross, first-layer corners, middle layer) come from the bounded `preservedPieces` search of `RecoveryPlanner`, run against each Stage's completion predicate. The last-layer segments (Stage 5 orientation, Stage 6 completion) come from `LastLayerMatcher` (AD-19) and only the Stages 5–6 Lessons' Algorithms (AD-21). An application use case runs it as an Effect with a timeout and re-verifies the result. A JVM corpus test of solved plus 10,000 random legal states reports median and p95 time.

Binding ADs (story Refs line, verbatim): **AD-6, AD-8, AD-21**. Also followed: AD-19 (PO clarification), AD-3, AD-7, AD-1.

PO clarification (2026-11-22, supersedes the earlier blocked run): AD-21 wins over AC 1's wording. Only the first-two-layer segments use the `preservedPieces` search. The Stage 5 and Stage 6 segments use `LastLayerMatcher` and the Stages 5–6 Lessons' Algorithms, and they stay in this story. All other ACs stand as written.

## Boundaries & Constraints

**Always:**
- `MethodSolve` is pure Kotlin in `:core:domain`, package `dev.cubentia.domain.method`. It reads Stages and Lessons from the loaded `ContentBundle` and its move set from `tutorConfig.moves` (AD-6).
- Beginner Stages, by stable ID: `stage-02-first-cross`, `stage-03-first-layer-corners`, `stage-04-middle-layer` (first two layers), then `stage-05-last-layer-orientation` and `stage-06-last-layer-completion` (last layer). A Stage's completion predicate is its final Lesson's `completion`, as `Placement` already uses it.
- The segments are `MethodSegment(tag, moves)`, with `tag: SegmentTag` = `SegmentTag.OfStage(StageId)` or `SegmentTag.OfCase(CaseId)`. The Case variant exists for AD-21 and Story 15.2; beginner emits only Stage tags. Each segment's `moves` apply, in the input's held frame, to the state the earlier segments leave.
- A Stage whose completion predicate already holds at its turn gets no segment. Segments with no moves are never emitted, so a solved input returns `Solved(emptyList())`. AD-20 segments label parts of a Move list, and an empty part has nothing to play.
- First-two-layer segment: the goal pieces are the Stage completion predicate's `preservedPieces(CubeState.SOLVED)`. Pieces are placed one at a time with `RecoveryGoal(keep = completion predicates of this and earlier Stages, pieces = already-preserved goal pieces + one target)`. The next target is the cheapest one: for bound b = 0..`PIECE_MAX_DEPTH` (12), try each missing piece in a stable order (cubie index) and take the first plan found. The segment ends when its completion predicate `isSatisfied`, and every piece an earlier segment placed is still in that predicate set's `preservedPieces`.
- The search is `RecoveryPlanner`'s bounded IDA*. It keeps the same bound loop, Move order, same-face redundancy rule, `CHECK_INTERVAL` `shouldContinue` polling and `Found / NotFound / Stopped` results. For speed, add a piece-tracking entry point, for example `RecoveryPlanner.planPieces(from, goal, maxDepth, moveSet, shouldContinue)`:
  - It works only for goals whose keep predicates preserve a piece exactly when that cubie is home and unflipped/untwisted. That holds for the first-two-layer predicates, and a test proves it.
  - It tracks only the goal pieces' (slot, orientation) through per-cubie move tables.
  - Its admissible lower bound is the larger of the existing count bound and exact small-group distances: each goal piece alone, each pair, and each triple that contains a piece missing at `from`. These come from tables built lazily by BFS over all 18 face turns and cached process-wide in a thread-safe way.
  - Before it reports a goal state, it confirms with `goal.isMet` on the real `CubeState`.
  - Its moveSet must be face turns only. Held-frame faces map to home faces through `from.orientation`.
- Last-layer segment (LL on home D, opposite WHITE):
  - The Stage's Algorithms are the distinct step `moves` of its Lessons, parsed with `Notation.parse`. Leading and trailing LL-face turns are stripped (the search adds AUF itself), and duplicates by cubie effect are removed. That gives Stage 5 = `F L D L' D' F'` and `L D L' D L D2 L'`, and Stage 6 = `D L D' R' D L' D' R` and `L D' L D L D L D' L' D' L2`.
  - Rungs, judged by `LastLayerStepReached(step)` (that is, `LastLayerMatcher.stepPieces`): Stage 5 = `EDGES_ORIENTED` then `ORIENTED`. Stage 6 = `CORNERS_PERMUTED` then `SOLVED_UP_TO_AUF`, then a closing LL-face turn (none, D, D2, D') that makes the Stage completion predicate hold.
  - Each rung is a breadth-first search by fewest Algorithm applications, up to `LL_MAX_APPLICATIONS` (6). Each application is an optional LL-face pre-turn followed by one Algorithm, and visited states are deduplicated.
  - Algorithm faces are home-frame, so they are emitted mapped to held faces (`Face` whose `orientation.homeFaceAt` is the home face), as `SolveCube` does.
- Within a first-two-layer segment, adjacent same-face turns are merged with `mergeSameFaceTurns`. LL segments are never merged, so they stay split into pre-turn, Algorithm and closing turn. Output is deterministic for a given input and content.
- `MethodSolveResult` is sealed: `Solved(segments)`, `NotFound` (a bound was exhausted, or the content has no beginner Stage or Algorithm), and `Stopped` (`shouldContinue` said no). Domain failures are never exceptions.
- `:core:application` adds `Effect.MethodSolve(effectId, revision, state, method)` and a `SolveByMethod(bundle, deadlineMillis = 3_000)` use case shaped like `SolveCube`:
  - It runs on `Dispatchers.Default` inside `withTimeoutOrNull` and passes `{ coroutine isActive }` as `shouldContinue`.
  - It re-verifies the result: the input with all segments applied must be `isSolved`, and each segment's Stage completion predicate must hold at its boundary. Otherwise the result is `Failed`.
  - It returns `MethodSolveCompleted(effectId, revision, outcome)` with `MethodSolveOutcome.{Solved(segments), TimedOut, Failed}`, rethrows `CancellationException`, and maps other exceptions to `Failed`.

**Never:**
- Never call or import `CubeSolver`, `:core:solver` or `SolveCube` from `MethodSolve`/`SolveByMethod` (AD-8, AD-21).
- Never use the `preservedPieces` search for Stage 5–6 segments, or Library OLL/PLL Algorithms for beginner (AD-21). Never define Case patterns or AUF judgment outside `LastLayerMatcher` (AD-19).
- Never change `RecoveryPlanner.plan`'s results or signature, or Guided recovery behaviour. Existing `RecoveryPlannerTest`/`PlanRecoveryTest` pass unchanged.
- No UI, ViewModel, reducer emission of `Effect.MethodSolve`, `en.json` strings, content JSON, tutor-config or schema changes (Story 15.3 owns UI and copy; 15.2 owns 2-look/full).
- Never shrink the corpus below solved + 10,000 uniform random legal states to make it faster; optimize the search instead.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved | `CubeState.SOLVED` (any orientation) | `Solved(emptyList())` | none |
| Random legal | uniform random legal state | 5 segments max, Stage-tagged in Course order; applied in order they solve the input | none |
| Partly done | cross already solved | no `stage-02` segment; the first segment is `stage-03` | none |
| Only LL left | F2L solved, LL scrambled | only `stage-05`/`stage-06` segments, built from the Stage 5–6 Algorithms + LL-face turns only | none |
| Held rotated | random state `reoriented` (e.g. yellow on U) | the moves apply to the held state and solve it | none |
| Stopped | `shouldContinue` returns false | `Stopped` | no partial segments |
| Timeout (app) | `SolveByMethod` with a tiny deadline or a stopping fake | `TimedOut`, echoes `effectId`/`revision` | no Moves |
| Bad result (app) | injected solver function returning a non-solving segment list | `Failed` | no Moves |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/RecoveryPlanner.kt` -- the IDA* (`plan`, `Search`, `heuristic`, `redundant`, `CHECK_INTERVAL`). Add the piece-tracking entry point by sharing or generalizing `Search`, not by forking a different algorithm. `RecoveryGoal.missing` and `isMet` stay the predicate truth.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Cubies.kt` -- slot order (corners URF..DRB 0–7, edges UR..BR 0–11), `compose`/`turn` semantics and `QUARTER_TURNS` for the per-cubie move tables. `FaceletTables.CORNER_FACES`/`EDGE_FACES` (`cube/Facelets.kt`, internal) map `Piece.colors` to a cubie index through `Color.home(face)`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeState.kt` -- `apply` maps held FaceTurns through `orientation.homeFaceAt`. Cubies are center-relative in the home frame (WHITE U, GREEN F).
- `core/domain/src/main/kotlin/dev/cubentia/domain/predicate/{WhiteCrossSolved,WhiteCornersSolved,MiddleLayerSolved,AllOf}.kt` -- the first-two-layer predicates (`preservedPieces` = home pieces; prove it in a test).
- `core/domain/src/main/kotlin/dev/cubentia/domain/predicate/LastLayerStepReached.kt`, `lastlayer/LastLayerMatcher.kt` (`stepPieces`), `lastlayer/LastLayerStep.kt`, `lastlayer/LastLayerView.kt` -- the AD-19 judge for the LL rungs.
- `core/domain/src/main/resources/content/stages/stage-0{2..6}-*.json` -- Stage IDs, final-Lesson completions, and the Stage 5–6 step `moves` (read-only).
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Placement.kt:66-85` -- precedent: the "final Lesson's completion" is the Stage predicate.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Model.kt` -- `ContentBundle`, `Stage`, `Lesson`, `Step`, `TutorConfig.moves`. `content/Ids.kt` -- `StageId`, `CaseId`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/FaceTurnMerge.kt` -- `mergeSameFaceTurns`.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveCube.kt`, `PlanRecovery.kt` -- the Effect + timeout + re-verify + revision-echo pattern to mirror. `session/Effect.kt` -- where `Effect.MethodSolve` goes. `app/.../session/SessionHost.kt:713` uses `else` over `Effect`, so a new variant compiles.
- `core/solver/src/benchmarkShared/.../SolverWorkload.kt:52` (`randomLegalState`) and `core/solver/src/test/.../TwoPhaseCubeSolverTest.kt` (10k corpus as a regular test that prints a report) -- precedent. `:core:domain` may not depend on `:core:solver` (AD-1), so copy the generator into a domain test fixture.
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/TutorFixtures.kt` -- `ContentLoader.load(ContentSource.SHIPPED)`, `CONFIG`, `moves()` helpers.
- Planning prototype (JS): one-piece-at-a-time cheapest-first with pair+triple tables averaged ~20k nodes and ≤ 8 Moves per piece. LL rungs needed ≤ 3/3/4/2 applications, with ≤ 325 nodes over 400 random LLs.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/PieceDistances.kt` -- new internal per-cubie move tables plus lazily cached single/pair/triple BFS distance tables -- the admissible bound that makes the bounded search fast.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/RecoveryPlanner.kt` -- add the piece-tracking bounded IDA* entry point sharing `plan`'s search mechanics, confirming goals with `goal.isMet`; leave `plan` unchanged -- AD-21 "the bounded preservedPieces search RecoveryPlanner uses".
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` (+ an optional `BeginnerLastLayer.kt`) -- `SolveMethod { BEGINNER }`, `SegmentTag`, `MethodSegment`, `MethodSolveResult`, `MethodSolve.solve(state, method, bundle, shouldContinue)` per the Always rules -- FR-30, AD-21.
- `core/application/src/main/kotlin/dev/cubentia/application/session/Effect.kt` -- add `Effect.MethodSolve` -- AD-3/AD-21 Effect.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` -- new use case, completion and outcome types; the domain call is injectable through a `fun interface` for tests (like `RecoveryPlannerFunction`) -- Effect + timeout + re-verify.
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/RecoveryPlannerPiecesTest.kt` -- prove that the F2L predicates' `preservedPieces` equal the home goal pieces over seeded random states; that `planPieces` finds plans, honours `maxDepth`, and returns `Stopped`; and that its plan length equals `plan`'s on small cases.
- `core/domain/src/test/kotlin/dev/cubentia/domain/method/MethodSolveTest.kt` -- cover the I/O matrix rows for the domain (solved, partly done, LL only, held rotated, Stopped, determinism, tags order). LL segments contain only LL-face turns and the Stage's Algorithms (check by parsing a segment into a pre-turn/Algorithm/closing-turn split).
- `core/domain/src/test/kotlin/dev/cubentia/domain/method/MethodSolveCorpusTest.kt` + `RandomLegalStates.kt` -- the FR-15 corpus: solved + 10,000 uniform random legal states (pinned seed) + 100 deep Scrambles (100 random face turns). For each: `Solved`; the input with all moves applied `isSolved`; at every segment boundary, every completion predicate of this and earlier segments' Stages holds; tags are in Course order. Print median, p95 and max time per case (ms) and the total, after an untimed warm-up.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/SolveByMethodTest.kt` -- the Solved echo of `effectId`/`revision` on a real state; TimedOut; Failed on a non-solving injected result; Failed on a thrown exception; cancellation propagates.

**Acceptance Criteria:**
- Given any valid `CubeState`, when `SolveByMethod` runs `Effect.MethodSolve(method = BEGINNER)` with its 3 s timeout, then it returns segments tagged `stage-02`…`stage-06` in Course order. The first-two-layer segments come from `RecoveryPlanner`'s bounded `preservedPieces` search against each Stage's completion predicate. The Stage 5–6 segments come from `LastLayerMatcher` and the Stages 5–6 Lessons' Algorithms. No code path reaches `CubeSolver` (AD-6, AD-8, AD-21).
- Given the FR-15 corpus (solved + ≥ 10,000 random legal states), when the JVM suite applies each result to its input, then every result solves its input, no segment breaks a piece placed by an earlier segment, and the median and p95 times on the JVM are printed in the test output.
- Given the existing Guided recovery tests, when the suite runs, then `RecoveryPlannerTest`, `RecoveryPathScenarioTest` and `PlanRecoveryTest` pass unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 26 findings — high 0, medium 1, low 13, false 11, maybe-false 1
- findings:
  - `[false]` `[reject]` IA: the planner is a new `planPieces` entry point, not `plan` (reading A2 vs A1) — the PO named "the RecoveryPlanner search". `planPieces` runs the same shared bounded IDA* (`Search<N>`) against `RecoveryGoal` and confirms with `goal.isMet`, and `plan`'s tests pass unchanged.
  - `[false]` `[reject]` IA: the last layer uses the matcher as a goal judge, not recognise-then-lookup (B1 vs B2) — the Stage 5–6 Lesson Algorithms are not Library Cases, so the matcher has no pattern to look them up by. It judges every rung (`LastLayerStepReached` → `LastLayerMatcher.stepPieces`), as AD-19 requires.
  - `[false]` `[reject]` IA: `Effect.MethodSolve` is not emitted by the reducer or host — Story 15.3 owns playback wiring (epic context). The AC's "runs as an Effect with a timeout" is met by `Effect.MethodSolve` + `SolveByMethod`.
  - `[low]` `[reject]` IA/B8: the corpus bypasses `SolveByMethod`, has no time gate and uses only the home orientation — AC 2 asks for the times to be reported, not gated. The held-frame mapping is covered by `MethodSolveTest` and `SolveByMethodTest`, and a fix would add corpus complexity for an already-covered path.
  - `[low]` `[patch]` IA/B1: `SolveByMethod.verified` checks only the segment's own Stage predicate, and Stage 5's predicate does not cover the first two layers — fixed: every earlier segment's Stage predicate must also hold at each boundary. A new `breaksEarlier` fixture expects Failed.
  - `[false]` `[reject]` IA: pieces are not checked inside a segment (E2) — insertions must move kept pieces temporarily. The AC is judged at segment boundaries, as the corpus test does.
  - `[false]` `[reject]` IA: at most five segments instead of exactly five (C1) — the spec settled that empty Stage segments are omitted (AD-20 rationale).
  - `[low]` `[patch]` VG: no test reaches the final `isSolved` guard in `verified` — added the `endsUnsolved` fixture (SOLVED+R, cross segment "R' D") expecting Failed.
  - `[low]` `[patch]` VG/B11: the misleading `wrongStage` fixture name — renamed to `validCross`.
  - `[low]` `[reject]` B2: the re-verifier does not check segment order, duplicates or foreign Stage tags — only a faulty producer could emit those, `MethodSolve` cannot, and the fix adds guard branches.
  - `[low]` `[patch]` B3: `OfCase` segments passed unchecked — fixed: `OfCase` now fails verification until Story 15.2 adds Case checks, and the test expects Failed.
  - `[medium]` `[patch]` B4/E9: last-layer Algorithms kept their authored leading and trailing D turns, so segments showed "D D L…" or "D' D…" — fixed: `algorithmsOf` emits the stripped core, per the spec's Always rule. Tests were updated and now assert there are no adjacent D turns. Back-to-back repeats of one Algorithm can still meet on a non-D face (e.g. "F' F"); that is a property of repeating an Algorithm, recorded as a residual risk.
  - `[low]` `[reject]` B5: same-face turns can be adjacent across a segment boundary — segments are separately labelled parts, so merging across them would break the AD-20 labels. It is rare and cosmetic, and the fix adds search constraints.
  - `[maybe-false]` `[defer]` B6/E4/E5: the cold-start table build does not poll `shouldContinue`, so a timeout could arrive late — unmeasured on the device. Deferred (medium, unverified). Settle it by timing a cold first solve on the reference device.
  - `[low]` `[patch]` B7: the `PieceSearch.startBound` getter recomputed the bound on every read — now a val computed once.
  - `[low]` `[patch]` B9: `RandomLegalStates` copied the facelet tables — it now uses `FaceletTables.CORNER_FACELETS`/`EDGE_FACELETS`.
  - `[low]` `[reject]` B10: the "final Lesson's completion" lookup is repeated in three places — the fix adds a public helper. The rule is stable and already shared with `Placement`.
  - `[low]` `[patch]` B11: `assertEquals` expected and actual were swapped — fixed.
  - `[low]` `[reject]` B12: the `PieceDistances` numeric constants are plain `val`s — `ShippedContentTest` forbids numeric `const val` in `tutor`, the same as `CHECK_INTERVAL`. Cosmetic.
  - `[low]` `[reject]` B13/E7: `homeIndices` hands out the shared `IntArray` — `FaceletTables` is `internal` and its only caller is `CubeState.toFacelets`, which only reads it. Copying would undo the speed fix.
  - `[false]` `[reject]` B14: a 175-character KDoc line would fail ktlint — `./gradlew spotlessCheck build` passed.
  - `[false]` `[reject]` E1: `PieceSearch` throws when a piece has no cubie — `preservedPieces` builds `Piece`s from real sticker colours, so every one maps to a cubie.
  - `[false]` `[reject]` E2: `planPieces` is inadmissible for goals that are not home-based — its only caller passes the first-two-layer predicates, and `RecoveryPlannerPiecesTest` proves they are equivalent to home pieces. The precondition is documented.
  - `[low]` `[patch]` E3: the earlier-pieces check used only `completions[index-1]` — it now uses the union of all earlier completions.
  - `[false]` `[reject]` E6: `table()` with duplicate or out-of-range ids — its callers pass distinct, sorted ids taken from a `Set`.
  - `[false]` `[reject]` E8: domain failures surface as exceptions — `require`/`checkNotNull` guard programming errors that cannot be reached. Every reachable failure is a sealed result.

## Design Notes

Why piece-tracking. `plan` evaluates predicates through `toFacelets()` at every node and uses a bound of `ceil(missing/4)`. Middle-layer insertions need about 8 Moves, so that search would visit around 10^9 nodes. Tracking only the goal cubies, with triple-distance tables, brings this to about 10^4 nodes per solve. The search stays the same bounded IDA* over the same move set and goal, and `goal.isMet` is still the final word.

Why LL by Algorithms. AD-21 assigns last-layer segments to `LastLayerMatcher` plus Library/Lesson Algorithms. A short face-turn search could not reach a last-layer completion within a small bound.

Stage 6's corner Algorithm twists corners. In the prototype, the `CORNERS_PERMUTED` rung (placed and oriented, under AUF) was always reached within 4 applications of it with pre-turns.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- set first.
- `./gradlew :core:domain:test --tests '*RecoveryPlanner*' --tests '*MethodSolve*' --tests '*RecoveryPath*'` -- expected: pass; the corpus report prints median/p95 and the corpus finishes in well under ~5 min.
- `./gradlew :core:application:test --tests '*SolveByMethod*' --tests '*PlanRecovery*' --tests '*SolveCube*'` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (Kover ≥ 80%, boundary checks).
- `rg -n "CubeSolver|core.solver" core/domain/src/main/kotlin/dev/cubentia/domain/method core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` -- expected: no matches.

## Auto Run Result

Status: done

**Summary:** `MethodSolve(BEGINNER)` is in `:core:domain`. The first-two-layer segments (Stages 2–4) are placed one piece at a time, cheapest first, with `RecoveryPlanner.planPieces`, which shares `plan`'s bounded IDA* and is sped up by exact pair/triple piece-distance tables. Each segment is checked against its Stage completion predicate. The Stage 5–6 segments come from the Lessons' Algorithms plus LL-face turns, and each rung is judged by `LastLayerMatcher` through `LastLayerStepReached`. `CubeSolver` is never called. `Effect.MethodSolve` and the `SolveByMethod` use case run it with a 3 s timeout and re-verify every result.

**Files:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/MethodSolve.kt` — API (SolveMethod, SegmentTag, MethodSegment, MethodSolveResult) and the beginner build.
- `core/domain/src/main/kotlin/dev/cubentia/domain/method/BeginnerLastLayer.kt` — LL rung BFS over Stage 5–6 Algorithms.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/PieceDistances.kt` — per-cubie move tables, cached distance tables, PieceBound.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/RecoveryPlanner.kt` — generic shared Search, plus `planPieces`/`PieceSearch`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/{CubeState,Cubies,Facelets}.kt` — speed-ups with the same results: precomputed sticker/orientation tables, a turn table, and a linear permutation check.
- `core/application/src/main/kotlin/dev/cubentia/application/session/Effect.kt` — `Effect.MethodSolve`.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SolveByMethod.kt` — Effect use case: timeout and re-verification.
- Tests: `MethodSolveTest`, `MethodSolveCorpusTest`, `RandomLegalStates`, `RecoveryPlannerPiecesTest`, `SolveByMethodTest`.

**Review:** 26 findings. 9 patched (1 medium, 8 low), 1 deferred (cold-start table build, medium unverified), 16 rejected; the reasons are in the triage log. Follow-up review recommended: false (patched: 0 high, 1 medium).

**Verification:**
- Targeted domain and application tests pass.
- `./gradlew spotlessCheck build`: exit 0.
- Corpus: 10,101 cases (solved + 10,000 uniform + 100 deep), 0 failures. Inside the full build: median 8.89 ms, p95 41.15 ms, max 147.65 ms; run alone earlier: median 6.00 ms, p95 27.00 ms. Mean 113 Moves.
- No reference to `CubeSolver` or `:core:solver` in the new code.

**Residual risks:**
- Timing on the reference device is unmeasured, including the cold-start table build (deferred).
- The shared cube primitives (`toFacelets`, `Cubies.turn`) were optimized and are used app-wide; all existing tests pass.
- Back-to-back applications of the same LL Algorithm can show cancelling-looking turns on a non-D face (e.g. "F' F").
- The AC 1 wording still needs amending through correct-course (PO decision).
