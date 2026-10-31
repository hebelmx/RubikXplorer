---
title: 'Story 2.7: Author Stages 1–3 with path tests'
type: 'feature'
created: '2026-10-30'
baseline_revision: '70bee17b7aeab525dcc42e29aafc6f954b0a8312'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Test me classifies the opening Move of an authored Stage 2-3 insert (R', B', U') as Harmful.
    evidence: |-
      MoveFeedback.classify compares whole-Objective progress; R' lifts a cross edge, so progress drops on a taught Move. Story 2.6 fixed this rule; Story 4.2 Diagnosis replaces the Harmful placeholder.
    location: >-
      core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MoveFeedback.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The shipped bundle has no Stages (`bundle.json` `stages: []`), so the Learner has nothing to learn, no Lesson is proven solvable from its entry state, `tutor-config.json` does not exist, and the validator still accepts Lessons with no steps, empty or no-op steps, or a `demonstration` that disagrees with the steps (deferred from Stories 2.5/2.6) (FR-4, FR-5, FR-6, NFR-10, AD-6, AR-10, AR-22, UX-DR24).

**Approach:** Author Stages 1–3 (12 Lessons) as JSON plus `en.json` copy; tighten `ContentValidator` with the deferred step/demonstration rules; add a voice-guide content test and a scenario test that drives every shipped Lesson's step path through `reduce()` to its Completion Predicate and shows an authored harmful Move fails it; add `tutor-config.json`, loaded, validated and hashed with the bundle.

## Boundaries & Constraints

**Always:**
- IDs: Stages `stage-01-fundamentals`, `stage-02-first-cross`, `stage-03-first-layer-corners`; Lessons `lesson-0S-0N`. Text keys `stage.<stageId>.title`, `lesson.<id>.{objective,learner-action}`, `lesson.<id>.{goal,keep,watch,notice}.<n>` with `n` = 1-based step number. Prerequisites chain each Lesson to the previous one (first Lesson: none). `recovery` = `recovery-undo-last-move`.
- Home frame WHITE on U, GREEN on F (cross and corners are built on U). Each Lesson's `entryState` = `FaceletString.encode(SOLVED.apply(setup))`, `demonstration` = its steps' Moves joined by a space, and the path (steps) leads from the entry to the Completion Predicate: for Stage 1 (Notation exercises, `state-reached` of the steps' Moves) the entry is SOLVED; for Stages 2–3 the steps are the inverse of `setup`, ending SOLVED. Curriculum (completion · preserve · setup · steps · harmful):
  - 01-01 pieces — `state-reached U` · same · `` (SOLVED) · [`U`] · `L`
  - 01-02 face letters — `state-reached "R U"` · same · `` (SOLVED) · [`R`, `U`] · `L`
  - 01-03 prime and double — `state-reached "R' U2"` · same · `` (SOLVED) · [`R'`, `U2`] · `L`
  - 02-01 edge from the bottom — `white-cross-solved` · `white-cross-solved` · `F2` · [`F2`] · `U`
  - 02-02 align first — cross · cross · `F2 D` · [`D'`, `F2`] · `U`
  - 02-03 edge in the middle layer — cross · cross · `F` · [`F'`] · `U`
  - 02-04 flipped edge — cross · cross · `F U' R U` · [`U' R' U`, `F'`] · `U`
  - 02-05 whole cross — cross · cross · `L2 B2 R2 F2` · [`F2`, `R2`, `B2`, `L2`] · `U`
  - 03-01 white faces right — `allOf[cross, corners]` · cross · `R' D R` · [`R' D' R`] · `U`
  - 03-02 white faces front — allOf · cross · `F D' F'` · [`F D F'`] · `U`
  - 03-03 white faces down — allOf · cross · `R' D R D' R' D2 R` · [`R' D2 R D`, `R' D' R`] · `U`
  - 03-04 all four corners — allOf · cross · inverse of path · [`R' D' R`, `B' D' B`, `L' D' L`, `F' D' F`] · `U`
- Verified facts the copy may rely on: 02-01 entry white-green edge at DF, white down, green under green; 02-02 at DR, white down; 02-03 at FR, white facing R; 02-04 at UF flipped (green up, white on F), and after `U' R' U` the state equals 02-03's entry; 03-01 corner W/G/R at DFR, white on R; 03-02 at DFR, white on F; 03-03 at DFR, white on D, and after `R' D2 R D` the state equals 03-01's entry; 03-04 entry has cross 4/4, corners 0/4, all four white corners in the bottom layer, each step solves one more. Any other claim in copy must be checked against the state before it is written. If an authored harmful Move does not classify HARMFUL in the path test, pick another single Move that disturbs placed pieces and record the swap in the Spec Change Log.
- Voice guide (UX-DR24, EXPERIENCE.md, PRD addendum §I): Goal says what we achieve, Keep what must not move, Watch where to look before turning, Notice what changed and what stayed; second person, short declarative sentences, Notation alongside words never instead of them, Glossary terms; no `!`, no "wrong", "error", "correct", "perform", "let's".
- `ContentValidator` new `ContentProblem`s: `NoSteps(lesson)`; `EmptyStep(lesson, index)` (Moves parse to nothing); `NoOpStep(lesson, index)` (the step's path state equals the previous one, judged with `CubeState` equality exactly like `Tutor.decide`'s advance; only when the entry state and all step Notation are valid); `DemonstrationMismatch(lesson)` (parsed `demonstration` ≠ concatenated parsed step Moves; only when all parse).
- `tutor-config.json` at `content/tutor-config.json`, schema `content/schema/tutor-config.schema.json`, Kotlin `TutorConfig` in `content/`, decoded strictly by `ContentLoader` after strings, included in `contentRevision`, carried as `ContentBundle.tutorConfig`. It holds the tutor values needed so far: none — the spine's values (`maxDepth`, `moveSet`, hint and mastery thresholds) have their first consumer in Epic 4 — so it is an empty object model, and a test fails if `domain/tutor` or `application/session` main sources declare a numeric `const val`.

**Never:**
- No new predicate, schema field on Lesson/Step, `SessionEvent`, Gradle dependency, or `strings.xml` Learner text; no Stage rows in Learn (Story 7.2); no Epic 4 values invented into `tutor-config.json`.
- No edits to planning artifacts, `_bmad/`, `.claude/skills/`, `gradle.properties`, `gradle-daemon-jvm.properties`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped path | each shipped Lesson started via `LessonStarted`, every step's Moves committed, `StepAdvanced` when `decision.advance` and not last | entry not `objectiveMet`; each step ends with `decision.advance`; last ends `objectiveMet` and `decision.satisfied` | No error expected |
| Harmful Move | path state before the last step + authored harmful Move | `MoveFeedback.classify` = HARMFUL; full path + harmful Move → completion not satisfied | No error expected |
| No steps | Lesson `steps = []` | `NoSteps` | Loader `Invalid` |
| Empty step | step `moves = ""` | `EmptyStep(id, i)` | Loader `Invalid` |
| No-op step | step `moves = "R R'"` | `NoOpStep(id, i)` | Loader `Invalid` |
| Demo mismatch | `demonstration = "F"` with steps `F'` | `DemonstrationMismatch(id)` | Loader `Invalid` |
| Equivalent notation | `demonstration = "R2'"`, step `R2` | valid (same parsed Moves) | — |
| Config unknown key | `tutor-config.json` = `{"x":1}` or missing | `Malformed` / `MissingResource` | sealed result |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/{bundle.json,strings/en.json,stages/}` -- manifest lists three `stages/<stageId>.json` (schema pattern `stages/stage-NN-<slug>.json`); delete `stages/.gitkeep`. en.json has only `msg.*` keys now.
- `core/domain/.../content/{Model,ContentValidator,ContentProblem,ContentLoader}.kt` -- `Lesson`/`Step`/`ContentBundle`; `lessonProblems` is where new rules go (`notationProblem`, `entryStateProblem` reuse); `ContentLoader.decode` reads files through the hashing `read()`; constants `MANIFEST`, `STRINGS`.
- `core/domain/.../tutor/Tutor.kt` -- `pathState` (step path semantics the no-op rule must mirror). `playback/Demonstration.kt` plays steps, not `demonstration` (why consistency matters).
- `core/domain/src/main/resources/content/schema/*.schema.json` + `test/.../content/SchemaModelTest.kt` -- `schemas` map and `roots` map list each schema file; comparator handles `StructureKind.OBJECT`.
- `core/domain/src/test/.../content/{ContentValidatorTest,ContentLoaderTest,ShippedContentTest,ContentChecks}.kt` -- `withLesson {}` helper; `fixture(variant)` overlays `content-fixtures/<variant>/` on `valid/` (add `valid/tutor-config.json`; every fixture must still fail only for its own reason).
- `core/domain/src/test/.../predicate/Fixtures.kt`, `tutor/MoveFeedback.kt` -- classify helper for the harmful check.
- `core/application/src/test/.../session/{Lessons.kt,TeachingModesScenarioTest.kt}` -- `run`, `started`, `commit`, `stepAdvanced` helpers; shipped content loads via `ContentLoader.load(ContentSource.SHIPPED)` from this module's test classpath.
- `app/src/test/.../session/TestLessons.kt` -- `testBundle()` builds a `ContentBundle` by constructor: add the `tutorConfig` argument. `LearnScreen` shows every shipped Lesson (no app code change expected).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/stages/stage-0{1,2,3}-*.json`, `bundle.json`, `strings/en.json` -- author the 12 Lessons and their copy per the curriculum -- AC 1, 2.
- `core/domain/.../content/{ContentProblem,ContentValidator}.kt` -- four new problems and rules -- deferred review items.
- `core/domain/.../content/{Model,ContentLoader}.kt`, `resources/content/{tutor-config.json,schema/tutor-config.schema.json}` -- `TutorConfig`, load, hash -- AC 3.
- `core/domain/src/test/.../content/{ContentValidatorTest,ContentLoaderTest,SchemaModelTest}.kt` + fixtures -- every matrix validator/config row; schema file matches `TutorConfig`; the revision changes when `tutor-config.json` bytes change.
- `core/domain/src/test/.../content/{ShippedContentTest,VoiceGuide}.kt` -- shipped bundle: exactly the three Stage IDs in order, Lesson IDs `lesson-01-01`…`lesson-03-04`, every step key slot/number rule, voice rules above, Stage text keys; plus the no-numeric-`const val` guard -- AC 1, 3.
- `core/application/src/test/.../session/ShippedLessonPathsTest.kt` -- table `lessonId → (setup, harmful)` covering every shipped Lesson (fails on a Lesson with no row); asserts `entryState` = SOLVED + setup, then the matrix rows "Shipped path" and "Harmful Move" -- AC 2.
- `app/src/test/.../session/TestLessons.kt` -- compile fix only.

**Acceptance Criteria:**
- Given the shipped bundle, when `ShippedContentTest` runs, then it loads with no `ContentProblem`, holds Stages 1–3 with 3, 5 and 4 Lessons in stable IDs, and every step's Goal/Keep/Watch/Notice text passes the voice-guide check.
- Given each shipped Lesson's entry state, when `ShippedLessonPathsTest` drives its step path through `reduce()`, then it reaches the Completion Predicate, and its harmful Move is classified HARMFUL and fails the predicate.
- Given `tutor-config.json`, when content loads, then its bytes are part of `contentRevision`, an unknown key is `Malformed`, and no tutor/session main source holds a numeric constant.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 24 findings — high 0, medium 1, low 19, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) no app-surface test runs the shipped content — the ACs name the content test and scenario tests; `LearnScreen`/`LessonScreen` render any loaded bundle and are covered with test bundles; the shipped bundle loads through the same `ContentLoader`.
  - `[low]` `[patch]` (intent) the voice check never verifies each slot's meaning — grouped with blind #13; VoiceGuide KDoc now states which rules are mechanical and which are reviewed by hand.
  - `[low]` `[reject]` (intent) Lesson-level no-ops (entry already meets the Objective, path misses it) not rejected by the validator — grouped with blind #8.
  - `[low]` `[patch]` (verification-gap) no test shows an unparseable step suppresses the no-op/Demonstration checks — ContentValidatorTest case `F3` + `R R'` asserts only the BadNotation problems.
  - `[medium]` `[defer]` (blind) Test me classifies the opening Move of an authored insert (R', B', U') as Harmful — real on the new Stage 2–3 content, but the whole-Objective classifier is Story 2.6's rule (rejected for change there) and Story 4.2's Diagnosis replaces the placeholder; pre-existing.
  - `[false]` `[reject]` (blind) `preserve` equal to `completion` for Stages 1–2 "cannot hold" — no code reads `preserve.isSatisfied`; its only consumer is `Demonstration` via `preservedPieces`, which returns the pieces already placed (the Keep focus), as in the Story 2.1 fixture.
  - `[low]` `[patch]` (blind) the path test never checks the cross is kept along Stage 3 paths — ShippedLessonPathsTest asserts `preserve` holds after every step where it held at entry, and that every Stage 3 Lesson is in that set.
  - `[low]` `[patch]` (blind) the spec's linking facts are unasserted — tests added: 02-04 step 1 ends at 02-03's entry, 03-03 step 1 at 03-01's, 03-04 corner progress 0→4.
  - `[low]` `[reject]` (blind) sticker-position claims in copy untested — each claim was checked against computed states before writing; a per-sticker table is extra machinery for cosmetic risk.
  - `[low]` `[patch]` (blind) "The centers never move." is false for slice Moves — now "A face turn never moves a center."
  - `[false]` `[reject]` (blind) "R' moves part of it down" is inaccurate — R' takes the UR cross edge from the top layer down to the middle layer (B' likewise); "down" is accurate.
  - `[low]` `[reject]` (blind) validator does not reject a Lesson already complete at entry or completing early — every shipped Lesson is held to both by ShippedLessonPathsTest (which fails on a Lesson without a row); no other content source exists; fix adds new public `ContentProblem`s.
  - `[false]` `[reject]` (blind) no-op rule only compares with the previous step — `Tutor.decide` compares the current state with the next path state; a step that returns to an earlier path state still differs from its own start, so advance is not ambiguous.
  - `[low]` `[reject]` (blind) entry state decoded twice — negligible cost on 12 Lessons at load.
  - `[low]` `[reject]` (blind) the `const val` guard scans another module's sources undeclared to Gradle — the same `../..` scan pattern already exists in ShippedContentTest (Story 2.1); CI builds fresh.
  - `[low]` `[reject]` (blind) the guard is too narrow/broad — a heuristic tripwire; AC 3 is met by the config home itself; a full static analysis adds complexity for little gain.
  - `[low]` `[patch]` (blind) VoiceGuide covers only part of the voice guide — KDoc now says so explicitly (grouped with intent #2, edge #6).
  - `[false]` `[reject]` (blind) "Moves" capitalisation inconsistent — Move is a Glossary term, capitalised by project convention (AGENTS.md: use Glossary terms verbatim).
  - `[low]` `[reject]` (edge) path misses the Objective or an earlier step meets it — same as blind #8.
  - `[low]` `[reject]` (edge) entry already satisfies the completion predicate — same as blind #8.
  - `[low]` `[patch]` (edge) FIRST_PERSON misses "me"; BANNED misses "lets"/"let us" — regexes extended, bad samples added.
  - `[low]` `[reject]` (edge) const-val guard false hits in KDoc or misses `(3)`/`Int.MAX_VALUE` — same as blind #12.
  - `[low]` `[patch]` (edge) NoOpStep KDoc wrong about tutor behaviour — corrected: the tutor would mark it done at once.
  - `[low]` `[patch]` (edge) claim: VoiceGuide implied to enforce second person/Glossary — grouped with blind #13.

## Design Notes

- **Why the path is the steps, not a solver:** AC 2 needs "at least one legal path"; the authored steps are the path the Learner is taught, and running them through `reduce()` also proves `Tutor.decide`'s per-step `advance` fires for each step. Setup moves live in the test table, so a reader sees how each entry state was made.
- **Why `tutor-config.json` is empty:** AR-22 sets tutor values "during curriculum authoring", and the spine lists them as `maxDepth`, `moveSet`, hint and mastery thresholds — all consumed first in Epic 4. Creating the loaded, hashed, schema-checked home now means Epic 4 adds fields, not code constants.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test` -- expected: green incl. ShippedContentTest, ContentValidatorTest, ContentLoaderTest, SchemaModelTest, ShippedLessonPathsTest.
- `./gradlew spotlessCheck build` -- expected: exit 0 (Kover ≥ 80% on `:core:*`, Learner-text, boundary, lint gates; app tests green).

## Auto Run Result

Status: done

**Summary:** Authored Stages 1–3 (`stage-01-fundamentals` 3 Lessons, `stage-02-first-cross` 5, `stage-03-first-layer-corners` 4) as shipped JSON with Goal/Keep/Watch/Notice copy in `en.json`; `ContentValidator` now rejects Lessons with no steps, empty or no-op steps, and a `demonstration` that differs from its steps' Moves; `tutor-config.json` (empty — no tutor value has a consumer before Epic 4) is loaded strictly, schema-checked and hashed into `contentRevision`.

**Files changed:**
- `core/domain/src/main/resources/content/{bundle.json,stages/*.json,strings/en.json,tutor-config.json,schema/tutor-config.schema.json}` — curriculum, copy, config.
- `core/domain/.../content/{Model,ContentLoader,ContentProblem,ContentValidator}.kt` — `TutorConfig`, loading, four new problems and rules.
- Tests: `ContentValidatorTest`, `ContentLoaderTest`, `SchemaModelTest`, `ShippedContentTest`, new `VoiceGuide.kt`, new `ShippedLessonPathsTest` (application), app `TestLessons.kt` compile fix.

**Review findings:** 24 (high 0, medium 1, low 19, false 4). Patched: low 9 (preserve-along-path and linking-fact tests, 01-01 Keep copy, VoiceGuide regexes and KDoc, NoOpStep KDoc, bad-Notation suppression test). Deferred: medium 1 (Test me Harmful on taught insert Moves — Story 2.6 rule, Epic 4). Rejected with reasons in the triage log.

**Follow-up review recommended:** false — no high or medium entry patched.

**Verification:** `./gradlew spotlessCheck build` exit 0 before and after patches; ShippedLessonPathsTest 43, ContentValidatorTest 16, ShippedContentTest 15, ContentLoaderTest 20, SchemaModelTest 5, all green. Every I/O matrix row covered by a passing test.

**Residual risks:** `tutor-config.json` holds no values (reading 3b of AC 3: no tutor value is consumed before Epic 4); if the product owner expected values now, that is a follow-up. The voice-guide test checks only mechanical rules; slot meaning, second person and Glossary use need a human read. In Test me, taught insert Moves read Harmful until Epic 4 Diagnosis. The new Lessons now appear in the Learn list; not checked on a device.
