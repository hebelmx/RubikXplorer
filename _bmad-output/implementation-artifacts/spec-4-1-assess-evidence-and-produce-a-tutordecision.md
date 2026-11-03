---
title: 'Assess evidence and produce a TutorDecision'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: 5dc0b9f7d223aafd332a05f34257795f3c534e8c
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The tutor has no evidence engine: no `Certainty`, `ErrorCategory`, `Diagnosis`, `CandidateEnumerator` or `tutor/Assess`, and `TutorDecision` lacks most AD-5 fields. Steps have no `done` predicate, so the step card advances only on an exact path-state match and a stored `stepIndex`; out-of-order steps and Undo across a step boundary leave the card wrong (FR-9, FR-10, AD-5, AD-6, AD-12, AR-9, Principle 3).

**Approach:** Add the closed tutor types and a pure `Assess` in `:core:domain` `tutor/` that produces Certainty only from candidates enumerated by one `CandidateEnumerator(expected, maxDepth, moveSet)` configured from `tutor-config.json`, and that builds the full AD-5 `TutorDecision`. Give every step a required `done: Predicate`, derive the current step from the Cube State (`Tutor.currentStep`), replace the stored `stepIndex` with a transient Notice acknowledgement, and author `done` for every shipped Stage 1–3 step.

## Boundaries & Constraints

**Always:**
- `Certainty` is exactly `VERIFIED | LIKELY | UNKNOWN`; `ErrorCategory` is exactly the 7 AD-5 values; each `ErrorCategory` maps to exactly one `RecoveryPath` through an exhaustive `when` (no `else`).
- Certainty constants are referenced to produce a value only in `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Assess.kt` (and declared in `tutor/Certainty.kt`). `Diagnosis` and its factories therefore live in `Assess.kt`. Other main code may only *match* on a Certainty (`Certainty.X ->` branch).
- VERIFIED needs exactly one consistent candidate; more than one → LIKELY; none → UNKNOWN. A Full observation (virtual mode: the virtual Cube State) covers every face.
- `Diagnosis.verified(...)` is a smart constructor returning a sealed result (AD-12): it refuses (never throws, never returns null) when the consistent-candidate count is not exactly 1.
- `TutorDecision` has exactly the AD-5 fields: `objectiveId`, `satisfied`, `diagnosis: Diagnosis?`, `certainty: Certainty`, `nextAction`, `explanation: Text`, `recovery: Recovery` (`Pending(effectId) | Ready(moves) | Unavailable`), `reveal`, `advance`, `feedback: MoveFeedback?`. Only `Assess.decide` builds it.
- `maxDepth` and `moveSet` come from `content/tutor-config.json` via `TutorConfig`; the reducer reaches them through the Lesson session (no hard-coded copy in production code).
- Current step is a pure function of `(lesson, entry, state)`, never stored in `SessionState`. All session changes go through `SessionEvent` → `reduce` (AD-3). `:core:*` stay pure JVM.
- Completion is still decided only by `lesson.completion`; the last step's `done` is structurally equal to it.
- No auto-advance past a Notice: the card keeps a completed step's Notice until *Next* (`StepAdvanced`).

**Never:** Per-category diagnosis rules or copy, `MoveFeedback` classifier changes, `ON_PATH`, preserved-piece/Harmful judging (Story 4.2); Hint Ladder (4.3); recovery prompt UI (4.4); `RecoveryPlanner`/`PlanRecovery`, `Recovery.Pending/Ready` ever being produced (4.5); `AdaptivePolicy` (4.6); `PhysicalObservation`, `expected`/`observed` session fields, physical mode (Epic 8); any new UI component. Do not edit planning artifacts, `_bmad/` or `.claude/skills/`. No numeric confidence anywhere.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Virtual confirm | `Assess.confirm(CandidateEnumerator(s, 2, moves), observed = s)` | `VERIFIED` (only the empty sequence fits) | — |
| One-move evidence | expected `s`, observed `s·R` | `Assess.diagnose(...)` → Diagnosis `VERIFIED`, candidate `[R]` | — |
| Ambiguous evidence | expected `s`, observed `s·U D` (maxDepth 2) | candidates `[U D]` and `[D U]` fit → `LIKELY`, never VERIFIED | — |
| Out of reach | observed 3 face turns away, maxDepth 2 | no candidate → `UNKNOWN` | — |
| Smart constructor | `Diagnosis.verified(category, consistent)` with 2 (or 0) candidates | refusal variant carrying the count; with 1 → verified Diagnosis | sealed result |
| Enumeration bound | 18 face turns, maxDepth 2 | 289 candidates (1 + 18 + 18·15): no two consecutive Moves on the same face; deterministic order | `maxDepth < 0` or empty `moveSet` → `require` (programmer error) |
| Virtual decision | any Lesson state | `certainty == VERIFIED`, `diagnosis == null`, `recovery == Unavailable` | — |
| On path mid-step | `lesson-03-04` after steps 1–3 and `F'` (step 1's corner lifted) | `stepIndex == 3` (on-path rule wins) | — |
| Out of order (Let me try, Test me) | `lesson-02-05`: `R2` | card stays step 0 (no Notice, `advance` false) | — |
| …then the first step | then `F2` | card shows step 0 with its Notice, `advance` true; `StepAdvanced` → card step 2 (first unmet `done`), `revealRequested` false | — |
| Undo across boundary | `lesson-02-05`: `F2`, `StepAdvanced`, `Undo` | card back to step 0, Notice cleared, `advance` false | — |
| Redo | then `Redo` | card step 0 with Notice again, `advance` true | — |
| Lesson complete | final state meets `completion` | `satisfied`, `advance`, card = last step, `nextAction == FINISH` | — |
| Bad config | `tutor-config.json` with `maxDepth` 0 or 4, or invalid/empty `moveSet` | content does not load (`ContentProblem`) | sealed load failure |
| Bad step piece | `white-piece-solved` naming a non-white-layer piece | decoding fails like an invalid `state-reached` | load failure |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/TutorDecision.kt` -- today 5 fields; extend to the AD-5 set.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Tutor.kt` -- `decide(lesson, entry, current, mode, stepIndex, revealRequested, lastMoveFeedback)` (path-state advance at :33-34) and `pathState`. `decide` moves to `Assess`; `pathState` stays.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MoveFeedback.kt` -- read-only in this story (4.2 replaces it); `feedback` keeps today's rule: shown only in `TEST_ME` while `!advance`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Model.kt` -- `Step` (add `done: Predicate`), `data object TutorConfig` (becomes a class), `ContentBundle.tutorConfig`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/{ContentValidator,ContentProblem,ContentLoader}.kt` -- validator pattern (`notationProblem`, sealed problems); loader decodes `tutor-config.json` strictly (:129).
- `core/domain/src/main/resources/content/{tutor-config.json,schema/tutor-config.schema.json,schema/lesson.schema.json,schema/predicate.schema.json,stages/*.json}` -- shapes; `SchemaModelTest` compares schema ↔ descriptor (supports string, integer, list, object, sealed; **no enum kind**, so new predicate params are strings).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,StateReached,WhiteLayer,WhiteCrossSolved,AnyOf,AllOf}.kt` -- registry; `StateReached` validates its param in a `@Transient` initializer (copy that pattern); `WhiteLayer.correctPieces(state, FaceletTables.EDGE_FACELETS|CORNER_FACELETS)` is the color-anchored reading to reuse.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/{Move,MoveHistory,Notation}.kt` -- `FaceTurn(face, turn)`; `MoveHistory(start, state, done, undone)`.
- `core/domain/src/test/resources/content-fixtures/*/stages/*.json`, `valid/tutor-config.json` -- every fixture step needs `done`; `bad-json` stays malformed.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/{ContentLoaderTest,ShippedContentTest,SchemaModelTest,ContentValidatorTest}.kt`, `.../tutor/TutorTest.kt` -- assert `TutorConfig` as an object today; update.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/{SessionState,Reduce,SessionEvent,TRANSITIONS.md}.kt|md` -- stored `stepIndex` (SessionState :49, Reduce :51/:77/:122/:175-177), `decision` lazy (:61), `advance()`; table parsed by `TransitionsTableTest` (tutor field list in the Columns text).
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/{ShippedLessonPathsTest,TutorEventsTest,TransitionsTableTest,TeachingModesScenarioTest,ReduceTest,SolvePlaybackSessionTest,Lessons.kt}` -- `Lessons.kt` builds `Step(...)` and `started()`; `ShippedLessonPathsTest` table gives each entry as `SOLVED·setup` (so any path state is `state-reached "<setup> <moves so far>"`).
- `app/src/main/kotlin/dev/rubikxplorer/app/content/LessonCatalog.kt` (`CatalogEntry`, built at :37), `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` (:107 `LessonStarted`, :199-200 Next, :212/:297 `session.stepIndex`, :385 `canReset`) -- keep reading `session.stepIndex` (now derived).
- `app/src/test/kotlin/dev/rubikxplorer/app/session/TestLessons.kt` -- builds `Step(...)`/`ContentBundle`; `app/src/test/.../flags/BuildConfigUsageTest.kt` -- source-scan test pattern to copy.

## Tasks & Acceptance

**Execution:**
- [x] `core/domain/.../tutor/Certainty.kt`, `ErrorCategory.kt` (+ `RecoveryPath` enum `UNDO, GUIDED_RECOVERY, RETRY, REORIENT, RE_ENTER`; map WRONG_MOVE_OR_DIRECTION→UNDO, WRONG_PIECE→UNDO, WRONG_PIECE_ORIENTATION→GUIDED_RECOVERY, UNEXPECTED_ORIENTATION→REORIENT, COMPLETED_LAYER_DISTURBED→GUIDED_RECOVERY, INVALID_ENTERED_STATE→RE_ENTER, UNKNOWN_DISCREPANCY→RETRY), `NextAction.kt` (`WATCH, TURN, NEXT_STEP, FINISH`), `Recovery.kt` (sealed `Pending(effectId: Long)`, `Ready(moves: List<Move>)`, `data object Unavailable`) -- closed tutor vocabulary.
- [x] `core/domain/.../tutor/CandidateEnumerator.kt` -- `class CandidateEnumerator(expected: CubeState, maxDepth: Int, moveSet: List<Move>)`; `candidates(): List<Candidate>` (every sequence of length 0..maxDepth, skipping a Move that turns the same face as — or equals — the previous one; breadth-first, moveSet order); `consistentWith(observed: CubeState): List<Candidate>`; `data class Candidate(moves: List<Move>, state: CubeState)`; companion `of(expected, config: TutorConfig)` -- AD-5 single enumerator.
- [x] `core/domain/.../tutor/Assess.kt` -- `object Assess { confirm(enumerator, observed): Certainty; diagnose(category, enumerator, observed): Diagnosis; decide(lesson, history: MoveHistory, config: TutorConfig, mode, revealRequested, noticeStep: Int?, lastMoveFeedback): TutorDecision }`; plus `class Diagnosis private constructor(category, certainty, candidates)` with `verified(category, consistent): DiagnosisResult` (sealed `Verified(diagnosis)` / `NotUnique(consistent: Int)`) and internal LIKELY/UNKNOWN factories. `decide`: `satisfied = completion.isSatisfied(state)`; shown step `Tutor.shownStep(...)`; `advance = satisfied || noticeStep != null`; `certainty = confirm(CandidateEnumerator.of(state, config), state)`; `diagnosis = null`; `recovery = Recovery.Unavailable`; `reveal = mode == SHOW_ME || revealRequested`; `feedback = lastMoveFeedback` only in TEST_ME while `!advance`; `nextAction` = FINISH if satisfied, NEXT_STEP if advance, WATCH in SHOW_ME, else TURN; `explanation = Text(shown step's notice)` when advance, else `Text(shown step's goal)`.
- [x] `core/domain/.../tutor/Tutor.kt` + `TutorDecision.kt` -- remove `decide`; add `currentStep(lesson, entry, state): Int` (on the authored path — `state` equals the state after `i` of the flattened step Moves, first match — the step holding Move `i+1`, or the last step after the final Move; off the path the first step whose `done` is unmet, else the last step) and `shownStep(lesson, entry, state, noticeStep): Int` (last step if `completion` holds, else `noticeStep ?: currentStep`); KDocs updated.
- [x] `core/domain/.../predicate/WhitePieceSolved.kt` + `Predicate.kt` registry KDoc + `predicate.schema.json` -- `@SerialName("white-piece-solved") data class WhitePieceSolved(val piece: String)`: `piece` is `white-<c1>[-<c2>]` naming a white-layer edge or corner (lowercase color names, e.g. `white-green`, `white-green-red`), validated in a `@Transient` init (invalid → `require` fails, like `state-reached`); satisfied when that piece is in `WhiteLayer.correctPieces`; progress 0/1 or 1/1; preserved = that piece when correct. Color-anchored and orientation-invariant.
- [x] `core/domain/.../content/Model.kt`, `schema/lesson.schema.json` -- `Step.done: Predicate` required (schema `"done": {"$ref": "predicate.schema.json"}` in `required`). `TutorConfig` → `@Serializable data class TutorConfig(val maxDepth: Int, val moveSet: String)` with a parsed `moves: List<Move>` helper; `schema/tutor-config.schema.json` (`maxDepth` integer 1–3, `moveSet` string, both required); `tutor-config.json` = `{"maxDepth": 2, "moveSet": "U U' U2 D D' D2 R R' R2 L L' L2 F F' F2 B B' B2"}`.
- [x] `core/domain/.../content/ContentValidator.kt`, `ContentProblem.kt` -- new `ContentProblem.InvalidTutorConfig(field, detail)` for `maxDepth` outside 1..3 and `moveSet` that is not valid Notation or empty; KDoc on `stepProblems` no longer cites `Tutor.decide`.
- [x] `core/domain/src/main/resources/content/stages/stage-0{1,2,3}-*.json` -- author `done` per Design Notes table.
- [x] `core/domain/src/test/resources/content-fixtures/**` -- add `done` (= the Lesson's `completion`) to every step; `valid/tutor-config.json` = shipped values; add a fixture or validator test for bad config; fix assertions in `ContentLoaderTest`, `ShippedContentTest`, `SchemaModelTest`, `ContentValidatorTest`, `TutorTest`.
- [x] `core/domain/src/test/.../tutor/{AssessTest,CandidateEnumeratorTest,TutorVocabularyTest,CertaintyProducerTest}.kt`, `.../predicate/WhitePieceSolvedTest.kt`, `TutorTest.kt` -- matrix rows 1–7; enum entry lists; one `RecoveryPath` per category; `TutorDecision` declared fields (Java reflection, non-static) equal the AD-5 set; `currentStep` on/off path; `CertaintyProducerTest` scans `../../core/*/src/<non-test sets>` and `../../app/src/<non-test sets>` (`*.kt`), allowlisting `tutor/Certainty.kt` and `tutor/Assess.kt`, and fails on `Certainty.valueOf`, `Certainty.entries`, an import of a `Certainty` member, or `\b(VERIFIED|LIKELY|UNKNOWN)\b` not followed by `\s*->` in a file that mentions `Certainty`; include a self-test of the matcher on in-memory snippets (one offending, one `when` branch).
- [x] `core/application/.../session/{SessionState,Reduce,SessionEvent}.kt`, `TRANSITIONS.md` -- `LessonStarted(lesson, tutorConfig: TutorConfig, at)`; `SessionState.tutorConfig: TutorConfig?` (null outside a Lesson); stored `stepIndex` → `noticeStep: Int?`; `stepIndex` becomes a derived `val` (`Tutor.shownStep`, 0 outside a Lesson); `decision` calls `Assess.decide`. Rules: after any history change in a Lesson (commit, undo, redo), with `s0` = shown step before and `d1` = `currentStep` after, `noticeStep = s0` if `d1 > s0` and not satisfied, else `null`; `revealRequested` resets whenever the shown step changes; `StepAdvanced` applies only when `advance && !satisfied` and then clears `noticeStep`; LessonStarted, SolvePlaybackStarted and an applied Reset/ResetConfirmed clear `noticeStep`. Update the table's tutor field list and prose.
- [x] `core/application/src/test/...` -- update `Lessons.kt` (load shipped `TutorConfig` once; `Step(done = …)`), `TransitionsTableTest`, `TutorEventsTest`, `TeachingModesScenarioTest`, `ReduceTest`, `SolvePlaybackSessionTest`; new `StepDerivationScenarioTest.kt` covering matrix rows 8–13 in both `LET_ME_TRY` and `TEST_ME`, plus `lesson-03-04` steps out of order.
- [x] `core/application/src/test/.../ShippedLessonPathsTest.kt` -- extend: last step `done == completion`; walking the authored path Move by Move, step k's `done` is false at every state before step k's last Move and true at the end of step k and at every later step boundary; at each boundary through `reduce`, `stepIndex == k`, `advance` true and `StepAdvanced` moves on; keep existing tests (replace `state.stepIndex` stored-field uses as needed).
- [x] `app/src/main/kotlin/dev/rubikxplorer/app/content/LessonCatalog.kt`, `ui/lesson/LessonScreen.kt` -- `CatalogEntry.tutorConfig` from `bundle.tutorConfig`; dispatch `LessonStarted(lesson, entry.tutorConfig, it)`. `app/src/test/**` -- pass the shipped config wherever `LessonStarted`/`CatalogEntry`/`Step` are built.

**Acceptance Criteria:**
- Given `tutor/Assess` and one `CandidateEnumerator` configured from `tutor-config.json`, when a virtual-mode state and Move history are assessed, then the `TutorDecision` has exactly the AD-5 fields and VERIFIED certainty.
- Given the closed `ErrorCategory` enum, when code switches on it, then the `when` is exhaustive, each category has exactly one Recovery Path, and Certainty has only `VERIFIED | LIKELY | UNKNOWN`.
- Given `./gradlew build`, when a main source file outside `tutor/Assess.kt` produces a Certainty value, then `CertaintyProducerTest` fails.
- Given the shipped Stages 1–3, when the content tests run, then every step has a `done`, the authored path satisfies each in order, and the last step's `done` equals `completion`.

## Spec Change Log

- 2026-11-03 (review pass 1, no loopback): lesson-03-04 steps 2 and 3 ship cumulative `allOf[WP white-green-red, WP white-red-blue(, WP white-blue-orange)]` instead of the single `WP` in the Design Notes table. Why: the single form breaks AD-6's content rule, because step 1's `R'` briefly seats white-red-blue and step 2's `B'` briefly seats white-blue-orange. The cumulative form derives the same current step. KEEP: the cumulative form.

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 33 findings — high 0, medium 4, low 14, false 15, maybe-false 0
- findings:
  - `[false]` `[reject]` IA: AC1 lists 9 fields, decision has 10 (`feedback`) — AD-5 (spine) lists 10 including `feedback`; AC1 says "exactly the AD-5 fields".
  - `[false]` `[reject]` IA: `decide` confirms the state against itself, so certainty is always VERIFIED — in virtual mode the observation is Full by definition (AD-5); non-trivial evidence goes through `confirm`/`diagnose`, which are tested directly.
  - `[medium]` `[patch]` IA: `Diagnosis.verified` trusts a caller-built list (grouped with BH-5) — made `verified` internal.
  - `[false]` `[reject]` IA: Certainty guard is a regex scan, not a module boundary — the AC asks for "a code search when CI runs", which `CertaintyProducerTest` is.
  - `[false]` `[reject]` IA: `advance` is a flag and the on-path rule overrides first-unmet mid-step — *Next* (`StepAdvanced`) moves to the first unmet step; AD-6 mandates the on-path rule.
  - `[false]` `[reject]` IA: the rendered step card is untested — `LessonScreen` passes `session.stepIndex` straight to the card (LessonScreen.kt:297), and that value is pinned by the reducer scenario tests.
  - `[false]` `[reject]` IA: config is invisible in the decision — `CandidateEnumeratorTest` covers `of(config)`, and the config is threaded through `LessonStarted`.
  - `[medium]` `[patch]` ECH: non-face Moves (`r r'`) in `moveSet` produce identity candidates (grouped: moveSet validation) — the validator now rejects non-`FaceTurn` Moves.
  - `[medium]` `[patch]` ECH: duplicate `moveSet` entries give duplicate candidates (grouped: moveSet validation) — the validator now rejects duplicates by effect.
  - `[false]` `[reject]` ECH: R L / L R both enumerated, so the result is LIKELY — the spec's matrix row wants U D / D U as LIKELY (two Move sequences fit).
  - `[low]` `[reject]` ECH: a public `TutorConfig` built with bad values throws in the reducer — only validated content reaches a session; guarding it adds an init and a failure path.
  - `[low]` `[reject]` ECH: `CandidateEnumerator` built directly with maxDepth > 3 — a programmer-error path only; the config is validated to 1..3.
  - `[low]` `[reject]` ECH: `ContentValidator` doesn't enforce the step-done path rules — only shipped content exists, and the content test enforces them per the AC.
  - `[low]` `[reject]` ECH: with a Notice pending, steps finished before *Next* skip their Notices — this is the AC behaviour ("first step whose done is unmet"); a Notice queue would add state.
  - `[low]` `[reject]` ECH: a path that revisits a state uses the first match — no shipped path does; guarding it adds a validator rule.
  - `[low]` `[reject]` ECH: `Tutor.pathState` has no main caller — it is used by the content and path tests and was pre-existing public API.
  - `[low]` `[reject]` ECH: Step KDoc invariant (last done == completion) not enforced at load — same as above: the content test enforces it.
  - `[low]` `[reject]` BH-1: the enumeration repeats on every state — 289 applies per state at depth 2; the 10k-step render-sync test passes; caching adds complexity.
  - `[low]` `[reject]` BH-2: `TutorConfig.moves` returns empty on bad Notation — validated content never has bad Notation; same as the ECH config item.
  - `[medium]` `[patch]` BH-3: duplicate `moveSet` entries pass (grouped: moveSet validation) — fixed by the duplicate rule.
  - `[medium]` `[patch]` BH-4: `moveSet` accepts wide/slice/rotation Moves (grouped: moveSet validation) — fixed by the FaceTurn-only rule.
  - `[medium]` `[patch]` BH-5: anyone can fabricate a VERIFIED Diagnosis through the public `verified` — `verified` is now internal.
  - `[low]` `[patch]` BH-6: `CertaintyProducerTest` misses `values()`, `::valueOf`, `enumValues`/`enumValueOf`, and its allowlist matches by suffix — regex widened, allowlist uses exact repo paths, self-test extended.
  - `[low]` `[reject]` BH-7: `TutorDecision` keeps a public constructor — no production code builds it outside `Assess`; enforcing it needs a factory or scan.
  - `[false]` `[reject]` BH-8: lesson-03-04 steps 2–3 use cumulative `allOf` instead of single predicates — the table version fails the content rule (step 1's `R'` briefly seats white-red-blue, and `B'` seats white-blue-orange); patch attempt reverted, and the current step derives the same either way; recorded in the Spec Change Log.
  - `[false]` `[reject]` BH-8b: Stage 3 `C` is written inline, not verbatim — the inline object is structurally equal to `completion` (`ShippedLessonPathsTest` asserts equality).
  - `[low]` `[patch]` BH-9: `white-piece-solved` accepts two spellings of one corner, and the schema pattern is looser than the decoder — schema `enum` of the 8 canonical names, and `parse` accepts only those.
  - `[low]` `[patch]` BH-10: no loader fixture uses `white-piece-solved` — added to the all-predicates fixture, with an assertion.
  - `[low]` `[reject]` BH-11: the maxDepth bounds live in both the schema and the validator — they agree today; a cross-check test is extra machinery.
  - `[false]` `[reject]` BH-12: nothing enforces tutorConfig null ⇔ lesson null — `SessionState`'s constructor is internal and only `reduce` sets both, together.
  - `[false]` `[reject]` BH-13: a Notice vanishes when the next Move undoes that step — specified (`d1 <= s0` clears it), and the card then shows the re-derived step.
  - `[low]` `[reject]` BH-14: three test helpers load shipped content — test-only duplication with no harm.
  - `[false]` `[reject]` BH-15: spec frontmatter is stale — the status moves through the workflow, and the logs fill at review.
  - `[low]` `[patch]` VG-1: no test that an applied Reset/ResetConfirmed and LessonStarted clear a pending `noticeStep` — added a `TutorEventsTest` case.
  - `[low]` `[patch]` VG-other: the schema pattern disagrees with the decoder — same fix as BH-9.

## Design Notes

Step `done` predicates (`SR x` = `{"predicate":"state-reached","moves":"x"}`; `WP p` = `white-piece-solved` with `piece` p; `C` = the Lesson's own `completion` object, verbatim):

| Lesson | step `done`s in order |
|---|---|
| 01-01, 02-01, 02-03, 03-01, 03-02 | `C` |
| 01-02 | `anyOf[SR "R", SR "R U"]`, `C` |
| 01-03 | `anyOf[SR "R'", SR "R' U2"]`, `C` |
| 02-02 | `anyOf[SR "F2", C]`, `C` |
| 02-04 | `anyOf[SR "F", C]`, `C` |
| 02-05 | `WP white-green`, `WP white-red`, `WP white-blue`, `C` |
| 03-03 | `anyOf[SR "R' D R", C]`, `C` |
| 03-04 | `WP white-green-red`, `WP white-red-blue`, `WP white-blue-orange`, `C` |

`anyOf[SR <path state>, C]` keeps a positional step done at every later boundary, as AD-6's content rule demands. If the extended path test shows a row breaking that rule, fix the row (never weaken the test).

Current-step derivation uses each step's own `done`, not AD-6's effective `allOf(lesson.preserve, done)`: Stage 1–2 Lessons ship `preserve == completion`, which would leave every step unmet until the Lesson is complete and break the AC's out-of-order advance. Effective done and preserved pieces stay for Story 4.2's classifier.

The transient `noticeStep` holds no current step. It only keeps a completed step's Notice on screen until *Next* (EXPERIENCE bans auto-advancing past a Notice). Any change of the Cube State re-derives it.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (all JVM tests incl. `ShippedLessonPathsTest`, `StepDerivationScenarioTest`, `CertaintyProducerTest`, Kover ≥ 80% on `:core:*`, boundary/learner-text checks).

## Auto Run Result

- **Summary:** Added the closed tutor vocabulary (`Certainty`, `ErrorCategory`→`RecoveryPath`, `NextAction`, `Recovery`), one `CandidateEnumerator` configured from `tutor-config.json` (`maxDepth` 2, 18 face turns, validated), and `tutor/Assess`. `Assess` confirms, diagnoses (with the `Diagnosis.verified` smart constructor) and builds the AD-5 `TutorDecision`. Steps gain a required `done` predicate, authored for every Stage 1–3 step with a new `white-piece-solved` predicate. The current step is now derived (`Tutor.currentStep`/`shownStep`), and a transient `noticeStep` replaces the stored `stepIndex`. A source-scan test keeps Certainty production inside `Assess.kt`.
- **Files:** `core/domain` tutor/ (Assess, CandidateEnumerator, Certainty, ErrorCategory, NextAction, Recovery, Tutor, TutorDecision), predicate/WhitePieceSolved, content model/validator/problems, schemas, tutor-config.json, stage JSON, fixtures, and tests; `core/application` session state/reducer/events/TRANSITIONS.md and tests (new StepDerivationScenarioTest); `app` LessonCatalog/LessonScreen (config threaded into `LessonStarted`) and test helpers.
- **Review:** 33 findings. 11 patched across 7 fixes: moveSet FaceTurn-only and no duplicates; `Diagnosis.verified` internal; Certainty scan widened with exact allowlist; canonical `white-piece-solved` names; fixture coverage; noticeStep-clearing test. 0 deferred. 22 rejected (15 false, 7 low), with reasons in the triage log. The cumulative lesson-03-04 predicates are kept and recorded in the Spec Change Log.
- **Follow-up review recommended:** true. Two or more medium entries were patched (moveSet validation; `Diagnosis.verified` visibility). Patched counts: high 0, medium 2 entries (4 rows), low 4 entries. Unverified risk: the moveSet rules and the internal `verified` constructor were patched after the reviewers ran, and no second review read them.
- **Verification:** `./gradlew spotlessCheck build` exit 0 before and after the patches (JVM tests, Kover, boundary and learner-text gates, APKs).
- **Residual risks:** in virtual mode certainty is always VERIFIED by construction; real evidence paths are exercised only by unit tests until Stories 4.2 and Epic 8. Steps finished while a Notice is pending skip their own Notice, as specified.
