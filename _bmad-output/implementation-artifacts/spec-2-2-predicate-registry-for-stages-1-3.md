---
title: 'Story 2.2: Predicate registry for Stages 1–3'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'c3cb7e106ea1d2804d471d17a004aff62337f676'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The closed predicate registry holds only `white-cross-solved` (Story 1.7/2.1). Stage 1 Notation exercises and Stage 3 first-layer corners have no Completion Predicate, and there are no `allOf`/`anyOf` combinators to say "corners with the cross preserved" (FR-4, FR-5, FR-12, AD-6, AR-10).

**Approach:** Add three serializable variants and two combinators to the sealed `Predicate` hierarchy in `:core:domain/predicate`, extend `predicate.schema.json` to match, and prove each with fixture tests (color-anchored, all 24 orientations, helping vs harmful Move) plus content decode tests, including an unknown nested discriminator failing the load.

## Boundaries & Constraints

**Always:**
- Registry after this story (serial name → Kotlin type, all in `dev.rubikxplorer.domain.predicate`, flat parameters beside `"predicate"`):
  - `white-cross-solved` → existing `WhiteCrossSolved` (Stage 2), unchanged behavior.
  - `white-corners-solved` → `data object WhiteCornersSolved` (Stage 3): each of the 4 corners touching the face whose center is WHITE shows WHITE on that face and each other sticker equals the center of the face it sits on. Progress `done/4`; preserved = the correct corners as `Piece(3 colors)`.
  - `state-reached` → `data class StateReached(val moves: String)` (Stage 1): satisfied when `state.cubies` equals `CubeState.SOLVED.apply(parsed moves).cubies` — the pattern those Moves make on a solved cube, Moves read in the home frame (WHITE U, GREEN F), compared up to whole-cube rotation. Progress = number of the 20 corner+edge slots whose cubie and twist/flip match the target, out of 20; preserved = the pieces in those matching slots, each named by its colors. `moves` must parse (`Notation.parse`): an invalid string is a `require` failure in `init` (so content decode yields `ContentLoadError.Malformed`).
  - `all-of` → `data class AllOf(val predicates: List<Predicate>)`: satisfied iff every child is; preserved = union of children's; progress = `ObjectiveProgress(Σ done, Σ total)`.
  - `any-of` → `data class AnyOf(val predicates: List<Predicate>)`: satisfied iff any child is; progress total = LCM of child totals, done = max over children of `done · (L / total)`; preserved = preserved pieces of the first child with that maximal scaled progress.
  - Both combinators `require(predicates.isNotEmpty())`.
- Every predicate is pure, reads stickers or cubies only (never `state.orientation` in effect), and returns identical `isSatisfied`/`progress`/`preservedPieces` in all 24 `Orientation.ALL`. `progress.done == total` iff `isSatisfied` for the leaf predicates and `all-of`.
- `predicate.schema.json` gets one `oneOf` option per variant (`additionalProperties: false`, all members required, `"predicate": {"const": …}`); combinator `predicates` is `{"type":"array","minItems":1,"items":{"$ref":"#"}}`; `moves` is `{"type":"string"}`. `SchemaModelTest` must still pass — teach `SchemaComparator` to stop recursing when it re-enters a sealed descriptor already on the current path.
- Fixture tests per variant in `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/`, mirroring `WhiteCrossSolvedTest`: solved, scrambled, partial, a helping Move raises progress and keeps preserved pieces, a harmful Move lowers progress, white moved off U (`x2`) still found by its center, every fixture equal in all 24 orientations, a seeded Kotest property over random face-turn sequences.
- Stage 3 composite (`all-of[white-cross-solved, white-corners-solved]`): a test shows a Move that breaks the cross lowers its progress and drops cross edges from its preserved pieces even when corners stay.
- Content decode tests (via `ContentLoader.json`): each variant decodes from its flat JSON form to the expected Kotlin value and round-trips; fixture overlays under `src/test/resources/content-fixtures/` add (a) a valid variant whose Lesson uses `all-of`/`any-of`/`state-reached`/`white-corners-solved` and loads `Loaded` with 0 problems, (b) `unknown-nested-predicate` (`"nope"` inside `all-of`) → `Malformed` naming `nope`, (c) `bad-predicate-moves` (`state-reached` with `"moves": "Q"`) → `Malformed`, (d) `empty-all-of` → `Malformed`.
- `:core:domain` stays pure Kotlin, Kover ≥ 80%, `./gradlew spotlessCheck build` green.

**Never:**
- No Stage 1–3 authoring in the shipped bundle (`stages` stays `[]`; Story 2.7), no `tutor-config.json`, no reducer/session/Test me wiring (Story 2.3+).
- No expression language, no nested `params` object, no predicate reading `teachingMode` or counting Moves.
- No change to `Predicate`'s three function signatures, `ObjectiveProgress`, `Piece`, `CubeState`, or `ContentLoader` behavior beyond what the new variants need; no new dependencies; no edits to `_bmad/`, `.claude/skills/`, planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Corners done, cross done | `SOLVED` | `white-corners-solved` satisfied, 4/4, 4 corner pieces | No error expected |
| Corner popped | `SOLVED` + `R U R'` | corners 3/4 or less, not satisfied | No error expected |
| Notation exercise | `state-reached {moves:"R"}` on `SOLVED` / `SOLVED`+`R` / `SOLVED`+`R` then `y` | 12/20 unsatisfied / 20/20 satisfied / 20/20 satisfied | No error expected |
| Wrong face turned | `state-reached {moves:"R"}` on `SOLVED`+`L` | not satisfied, progress < 20 | No error expected |
| Stage 3 composite | `all-of[cross, corners]` on `SOLVED` / after `F` | 8/8 / cross edge and corners lost, progress < 8 | No error expected |
| Any-of | `any-of[state-reached R, state-reached U]` on `SOLVED`+`U` | satisfied; progress = best child scaled | No error expected |
| Unknown discriminator, top level or nested | `{"predicate":"nope"}` | content test: `Failed(Malformed(path, …nope…))` | sealed, no throw |
| Bad `moves` / empty combinator | `"moves":"Q"` / `"predicates":[]` | `Failed(Malformed(…))` | sealed |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/Predicate.kt` -- sealed `@Serializable` interface: `isSatisfied`, `preservedPieces`, `progress`; update KDoc to list the registry. Keep signatures.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/WhiteCrossSolved.kt` -- template for a color-anchored sticker predicate (finds WHITE face via `FaceletTables.center`, walks `FaceletTables.EDGE_FACELETS`). `WhiteCornersSolved` mirrors it with `CORNER_FACELETS`; share a small internal helper if it avoids duplication.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{ObjectiveProgress,Piece}.kt` -- reuse as-is (`ObjectiveProgress(done,total)` requires `total > 0`, comparable only at equal totals).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Facelets.kt:40-140` -- `internal object FaceletTables`: `CORNER_FACES`, `CORNER_FACELETS`, `EDGE_FACES`, `EDGE_FACELETS`, `center(face)`; slot order in `Cubies.kt` KDoc.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/{Cubies,CubeState,Notation,Color}.kt` -- `Cubies` public vals `cornerPermutation/Orientation`, `edgePermutation/Orientation`; `CubeState.SOLVED.apply(moves)`; `Notation.parse(text): NotationResult.{Parsed(moves),Invalid(error)}`; `Color.home(face)` names a cubie's colors from `CORNER_FACES[k]` / `EDGE_FACES[k]`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentLoader.kt:89-96` -- `ContentLoader.json` (discriminator `"predicate"`, strict); `load` maps any decode `Exception` to `Malformed`. No change expected.
- `core/domain/src/main/resources/content/schema/predicate.schema.json` -- one `oneOf` option today; extend.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/SchemaModelTest.kt` -- `SchemaComparator.sealedNode`/`node`: `$ref "#"` resolves to the file root, so the recursive `items` would loop forever; add a guard (e.g. set of sealed serial names on the current path).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ContentLoaderTest.kt:104-112` -- overlay-fixture pattern (`fixture(variant)`, `malformed(variant)`; variant dirs hold only the broken file, fallback `valid/`). Add new variants beside the existing `unknown-predicate`.
- `core/domain/src/test/resources/content-fixtures/valid/{stages/stage-01-basics.json,strings/en.json}` -- existing valid Lesson (`white-cross-solved`, entry `SOLVED`+`F`, demonstration `F'`); do not change it, add an overlay instead.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/WhiteCrossSolvedTest.kt` -- test pattern (fixtures map, `assertSameInEveryOrientation`, seeded `checkAll`).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{WhiteCornersSolved,StateReached,AllOf,AnyOf}.kt` + `Predicate.kt` KDoc -- add variants with `@SerialName` -- AC 1.
- `core/domain/src/main/resources/content/schema/predicate.schema.json` -- add the four options -- schema is the shape source.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/SchemaModelTest.kt` -- recursion guard -- keep schema↔model check passing.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/{WhiteCornersSolvedTest,StateReachedTest,CombinatorsTest}.kt` -- fixture tests per the Always list and I/O rows 1–6 -- AC 1.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/PredicateJsonTest.kt` -- decode + round-trip of each variant's flat JSON -- AC 1.
- `core/domain/src/test/resources/content-fixtures/{all-predicates,unknown-nested-predicate,bad-predicate-moves,empty-all-of}/…` + `ContentLoaderTest.kt` -- load tests for I/O rows 7–8 and the valid overlay -- AC 2.

**Acceptance Criteria:**
- Given the sealed `Predicate` hierarchy with discriminator `"predicate"`, when `./gradlew :core:domain:test` runs, then `white-cross-solved`, `white-corners-solved`, `state-reached`, `all-of` and `any-of` each pass fixture tests showing `preservedPieces` and `progress` agree with `isSatisfied`, are identical in all 24 orientations, find WHITE by its center, and rank a helping Move above a harmful one.
- Given a stage JSON whose predicate (top level or nested in a combinator) has an unknown discriminator, when the content test loads it, then the result is `ContentLoadResult.Failed(Malformed)` naming the discriminator, and `SchemaModelTest` fails if the schema lists a predicate the Kotlin registry lacks.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 23 findings — high 0, medium 3, low 17, false 3, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) `all-of` progress sums children while preserved pieces union them, so overlapping children disagree — only reachable with overlapping children, which no Stage needs; the spec defines progress as the sum and `done == total` iff satisfied still holds; fix adds a branch. KDoc now states the count guarantee is leaf-only.
  - `[low]` `[patch]` (blind) `any-of` silently drops the piece-count = `done` agreement — KDoc on `Predicate.preservedPieces`/`progress` now states piece count equals `done` only for leaf predicates and `done == total` iff satisfied only for leaves and `all-of`.
  - `[low]` `[reject]` (blind) `AnyOf` re-evaluates child progress several times per call — predicates are tiny and bounded; no measurable cost; fix restructures the API (a fixed `total`).
  - `[false]` `[reject]` (blind) `AnyOf.scaled` divides by zero / LCM overflows — zero is impossible (`ObjectiveProgress` requires `total > 0`); overflow needs dozens of coprime totals, never authored; overflow part is low and needs a guard.
  - `[low]` `[reject]` (blind) `state-reached` accepts identity targets (`""`, `"y"`, `"R R'"`) that are met before the Learner acts — authoring error that Story 2.7's path tests (harmful Move must fail the predicate) catch; fix adds a guard.
  - `[low]` `[reject]` (blind) schema `moves` has no pattern/minLength — the schema is not used at runtime; decode rejects bad Notation as `Malformed`; mirroring the Notation grammar in a regex duplicates the parser.
  - `[low]` `[patch]` (blind) `StateReached` parses Notation twice with an unchecked cast — now parsed once via `requireNotNull(... as? Parsed)`.
  - `[medium]` `[patch]` (blind) `SchemaComparator` recursion guard skips checking the nested `items` target — grouped with the verification-gap and edge rows below; on re-entry the comparator now requires the resolved schema to be the sealed root being walked, `resolve` follows array indices in JSON pointers, and a new test breaks `all-of` items to `#/oneOf/0` and sees a problem.
  - `[low]` `[patch]` (blind) failure tests accept too broad an error (`MissingFieldException` is an IAE; `"Q"` single-char match) — `PredicateJsonTest` now expects `MissingFieldException` naming `moves`; bad moves / empty combinator assert their specific messages in both tests.
  - `[low]` `[reject]` (blind) missing combinator tests (ties, nested any-of, all-of with state-reached) — tie rule is documented; nested combinators reuse the same code paths already covered; low value.
  - `[low]` `[reject]` (blind) magic sticker index 31 in tests — matches the existing `WhiteCrossSolvedTest` convention with a comment; cosmetic.
  - `[false]` `[reject]` (blind) diff omits the spec and ships no Stage content — the spec is a separate tracked file by design; Stage authoring is Story 2.7 per the epic's story list.
  - `[false]` `[reject]` (edge) LCM overflow in `AnyOf` — same as the blind overflow row.
  - `[low]` `[reject]` (edge) overlapping `all-of` children — same as the blind overlap row.
  - `[low]` `[reject]` (edge) very deep combinator nesting throws `StackOverflowError`, which escapes `load` — requires hundreds of nested levels in repo-authored, PR-reviewed content; fix adds a depth cap.
  - `[low]` `[reject]` (edge) identity `state-reached` target — same as the blind identity row.
  - `[low]` `[reject]` (edge) schema `moves` minLength — same as the blind schema row.
  - `[low]` `[reject]` (edge) `StateReached("R") != StateReached("R' R2")` as data-class values — no code compares or deduplicates predicates; equality by content text is the expected data semantics.
  - `[medium]` `[patch]` (edge) schema re-entry does not check the nested `$ref` target — same group as the blind comparator row; same fix.
  - `[low]` `[patch]` (edge, claim) combinators do not keep piece count = progress — same group as the blind any-of doc row; KDoc fix.
  - `[low]` `[reject]` (edge, claim) no combinator test finds WHITE off U directly — combinators delegate to leaf predicates whose x2 tests cover it, and the combinator orientation sweep runs every fixture through all 24 orientations.
  - `[medium]` `[patch]` (verification-gap) schema-vs-model check stops at nested combinator children — same group as the blind comparator row; fixed plus a breaking-`items` test (minItems comparison not added: the comparator does not compare constraints for any field).
  - `[low]` `[reject]` (intent) diff implements the narrowest reading: registry + schema + fixture tests, no shipped Stage content, kebab-case `all-of`/`any-of` discriminators — Stage authoring is Story 2.7 in the epic; kebab-case matches the shipped `white-cross-solved` serial name and AD-6 names the combinators as concepts, not wire strings.

## Design Notes

- **Why `state-reached` with `moves`:** a Notation exercise ("turn R") is judged by the resulting pattern, not by which buttons were pressed, so any equivalent sequence counts (`R` = `R' R2`, `y`-rotated holding still counts). Comparing `cubies` (center-relative) is exactly "same stickers up to whole-cube rotation", which makes it color-anchored and orientation-invariant for free. Authors write readable Notation instead of 54-letter targets.
- **AnyOf progress uses LCM** so its `total` is fixed per predicate instance and progress values stay comparable across states (`ObjectiveProgress.compareTo` requires equal totals).
- Example content: `{"predicate":"all-of","predicates":[{"predicate":"white-cross-solved"},{"predicate":"white-corners-solved"}]}`, `{"predicate":"state-reached","moves":"R U"}`.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: exit 0 (Kover ≥ 80% on `:core:domain`).
- `./gradlew :core:domain:test --tests '*predicate*' --tests '*content*'` -- expected: all green, including the new variants and fixtures.

## Auto Run Result

Status: done

**Summary:** Extended the closed `Predicate` registry in `:core:domain/predicate` with `white-corners-solved` (Stage 3), `state-reached` (Stage 1 Notation exercises, judged by center-relative cubies against the pattern its `moves` make on a solved cube) and the `all-of`/`any-of` combinators, next to the existing `white-cross-solved` (Stage 2). Each implements `preservedPieces` and `progress`, is color-anchored and orientation-invariant, and has fixture tests; `predicate.schema.json` matches the model, and unknown discriminators (top level or nested) fail the content load as `Malformed`.

**Files changed:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{WhiteCornersSolved,StateReached,AllOf,AnyOf,WhiteLayer}.kt` — new variants and the shared white-layer helper.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,WhiteCrossSolved}.kt` — registry KDoc and guarantees; cross now uses `WhiteLayer` (behavior unchanged).
- `core/domain/src/main/resources/content/schema/predicate.schema.json` — four new `oneOf` options, recursive `predicates`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/{SchemaModelTest,ContentLoaderTest}.kt` — recursion guard with root check, array-index pointers, new load cases.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/{WhiteCornersSolvedTest,StateReachedTest,CombinatorsTest,PredicateJsonTest,Fixtures}.kt` — fixture, orientation, property and JSON tests.
- `core/domain/src/test/resources/content-fixtures/{all-predicates,unknown-nested-predicate,bad-predicate-moves,empty-all-of}/` — overlay fixtures.

**Review findings:** 23 findings (high 0, medium 3, low 17, false 3; the 3 medium rows are one grouped entry). Patched: schema comparator re-entry check + test (medium), combinator KDoc guarantees, single Notation parse, tighter failure assertions (low). Deferred: none. Rejected with reasons in the Review Triage Log (all-of overlap, any-of recomputation, overflow, identity targets, schema moves pattern, extra combinator tests, magic index, no shipped content, deep nesting, data-class equality, kebab-case naming).

**Follow-up review recommended:** false — patched entries: high 0, medium 1, low 3.

**Verification:** `./gradlew spotlessCheck build` exit 0 (incl. Kover); `./gradlew :core:domain:test --tests '*predicate*' --tests '*content*'` green (CombinatorsTest 9, StateReachedTest 9, WhiteCornersSolvedTest 9, WhiteCrossSolvedTest 10, PredicateJsonTest 2, ContentLoaderTest 17, SchemaModelTest 4); every I/O matrix row covered by a passing test.

**Residual risks:** `state-reached` with an identity target and overlapping `all-of` children are accepted (documented); combinator discriminators are kebab-case (`all-of`/`any-of`) rather than AD-6's `allOf`/`anyOf` spelling; the schema is checked only against the model, not used to validate content at runtime.
