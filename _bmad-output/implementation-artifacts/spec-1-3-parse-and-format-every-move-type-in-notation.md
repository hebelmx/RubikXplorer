---
title: 'Story 1.3: Parse and format every Move type in Notation'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '81d6d64ddbf35e130e4ca8320b3f7d7d60a65874'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The cube model from 1.2 only knows face Moves, and nothing converts strings to Moves. Demonstrations, buttons and Algorithms need one shared meaning for every Move type (face, wide, slice, Cube Rotation) and one string form.

**Approach:** Add wide (`u d l r f b`), slice (`M E S`) and Cube Rotation (`x y z`) variants to the sealed `Move`; a Cube Rotation changes only `orientation`, and wide/slice Moves apply as their face-turn equivalent plus a rotation. Add `Notation.parse` (string → `List<Move>` or a sealed error naming the bad token, never throwing) and `Notation.format` (canonical string), and verify slices against checked-in sticker fixtures.

## Boundaries & Constraints

**Always:**
- Pure Kotlin in `:core:domain`, package `dev.rubikxplorer.domain.cube` (so the 90% cube gate covers it); tests in the same package; fixtures under `core/domain/src/test/resources/fixtures/`.
- New Move types are immutable `data class`es using the existing `Turn`, so equality is by effect for each token (`R2 == R2'`, `M2 == M2'`): there is one value per effect.
- Canonical form (`format`): face `R`, `R'`, `R2`; wide lowercase `r`, `r'`, `r2`; slice `M`, `M'`, `M2`; rotation `x`, `x'`, `x2`. A list formats as tokens joined by one space; the empty list formats as `""`.
- `parse` accepts tokens separated by one or more whitespace characters (leading/trailing whitespace ignored). A token is a letter from `U R F D L B u r f d l b M E S x y z` followed by nothing, `'`, `2` or `2'`. Empty or blank input parses to an empty list.
- Any other token (`Q`, `R3`, `R''`, `R'2`, `X`, `Rw`, `RU`, `(R`) yields the sealed error with the exact token text and its 0-based token index; the first bad token wins. `parse` never throws.
- Cube Rotations follow the face they are named after: `x` like `R`, `y` like `U`, `z` like `F`. Slices follow WCA: `M` like `L`, `E` like `D`, `S` like `F`. Wide = the face plus the adjacent slice (`r` = `R` + `M'`).
- Equivalences, each with the same `Turn` applied to every part (and `'`/`2` carried through): `r = L x`, `l = R x'`, `u = D y`, `d = U y'`, `f = B z`, `b = F z'`, `M = R L' x'`, `E = U D' y'`, `S = F' B z`. Every part is a Learner-frame Move, so wide/slice Moves act through `orientation` like face Moves.
- Property tests use Kotest `checkAll` with pinned `PropTestConfig(seed = …)` under JUnit Jupiter, as in `CubeStateTest`.

**Never:**
- No production `Facelets` type, `CubeValidator`, Scramble or `MoveHistory` (stories 1.4–1.6). The sticker reader used to check fixtures is test code only.
- No alternate notations (`Rw`, `R'2`, compact `RU`, parentheses, comments, repetition counts) and no Move simplification/cancellation across tokens.
- No exceptions for bad input; `require` only for programmer error.
- No copying third-party cube/solver code; no new module; no edits to planning artifacts, `_bmad/`, `.claude/`, or the coverage gates.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Every token | each of the 54 canonical tokens (18 face, 18 wide, 9 slice, 9 rotation) | `parse` returns exactly one matching Move; `format` of it returns the same token | none |
| Sequence | `"R U R' U'"`, `"  M2   r  y' "` | `[R, U, R', U']`; `[M2, r, y']` | none |
| Same effect | `"R2'"` vs `"R2"`; `"x2'"` vs `"x2"` | equal Moves; `format` → `"R2"`, `"x2"` | none |
| Empty | `""`, `"   "` | empty list; `format(emptyList())` = `""` | none |
| Bad token | `"R U3 F"`, `"Rw"`, `"R'2"`, `"X"`, `"RU"` | sealed error with token `U3` index 1; `Rw` 0; `R'2` 0; `X` 0; `RU` 0 | returned value, never thrown |
| Round trip | random `List<Move>` of all types | `parse(format(seq))` = `seq` | none |
| Rotation | any state `s`, any rotation `m` | `s.apply(m).cubies == s.cubies`; `orientation` differs from `s.orientation`; `isSolved` unchanged | none |
| Rotation direction | `CubeState.SOLVED.apply(x)` | Learner U shows GREEN (F came up); `y`: Learner F shows RED; `z`: Learner U shows ORANGE | none |
| Equivalents | any state `s`, each wide/slice Move `m` with each `Turn` | `s.apply(m) == s.apply(equivalent parts)` | none |
| Group laws | random states, all Move types | M⁴ = id, M·M⁻¹ = id, sequence + inverse = id | none |
| WCA slices | `M`, `E`, `S` (and `M'`, `E'`, `S'`) from SOLVED | 54 stickers equal the checked-in fixture; the middle layer moves the same way `L`, `D`, `F` move their outer layer | none |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- sealed `Move` with `inverse()`; `Turn` (`quarters` internal, `inverse()`); `FaceTurn`; `List<Move>.inverse()`. Add the three new variants here.
- `.../cube/CubeState.kt` -- `apply(move)` is a `when` over `Move` with only `FaceTurn`; face turns resolve via `orientation.homeFaceAt(move.face)` then `cubies.turn(homeFace, turn)`. Add branches: rotation → `copy(orientation = …)`; wide/slice → `apply(equivalentParts)`.
- `.../cube/Orientation.kt` -- private ctor `(up, front)`; `homeFaceAt`, `colorAt`, `of(up, front)`, `HOME`, `ALL`. Add an internal rotation function. One quarter turn: `x` → new up = `homeFaceAt(F)`, new front = `homeFaceAt(D)`; `y` → up unchanged, new front = `homeFaceAt(R)`; `z` → new up = `homeFaceAt(L)`, front unchanged. Repeat `turn.quarters` times.
- `.../cube/Cubies.kt` -- slot order (corners URF UFL ULB UBR DFR DLF DBL DRB; edges UR UF UL UB DR DF DL DB FR FL BL BR) and twist/flip conventions in the KDoc; the test sticker reader must use them.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/CubeStateTest.kt` -- existing property-test pattern (`arbMove`, `arbState`, `config(seed)`, `runBlocking { checkAll(...) }`); widen the Move generator to all types or add a parallel test.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/{Move,Orientation,Cubies}Test.kt` -- JUnit 5 style: backtick names, `Assertions`.
- `build-logic/.../CubeCoverageGate.kt` -- the cube package needs ≥ 90% lines; read-only here.
- `core/domain/src/test/resources/` -- does not exist yet.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- add `enum class Slice { M, E, S }`, `enum class Axis { X, Y, Z }`, `data class WideTurn(face: Face, turn: Turn)`, `data class SliceTurn(slice: Slice, turn: Turn)`, `data class CubeRotation(axis: Axis, turn: Turn)`, each with `inverse()` returning its own type; update the sealed-interface KDoc -- PRD Glossary Move types.
- `.../cube/Orientation.kt` -- internal `rotated(axis: Axis, turn: Turn): Orientation` as in the Code Map -- AD-2 rotation changes only orientation.
- `.../cube/CubeState.kt` -- exhaustive `when`: `CubeRotation` changes only `orientation`; `WideTurn`/`SliceTurn` apply their equivalent part list from the Constraints table; KDoc the rules -- AD-2.
- `.../cube/Notation.kt` -- `object Notation { fun parse(text: String): NotationResult; fun format(move: Move): String; fun format(moves: List<Move>): String }`; `sealed interface NotationResult { data class Parsed(val moves: List<Move>); data class Invalid(val error: NotationError) }`; `sealed interface NotationError { data class BadToken(val token: String, val index: Int) }` -- AD-7 single conversion point; errors are values.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/NotationTest.kt` -- every I/O row for parse/format (all 54 tokens, `2'` variants, whitespace, empty, each bad-token example, round-trip property with pinned seed) -- AC 1, 3.
- `.../cube/MoveTypesTest.kt` (or extend `CubeStateTest`) -- rotation leaves `cubies` unchanged and turns `orientation` in the named direction; every wide/slice Move with every `Turn` equals its parts on random states; M⁴/inverse/sequence properties over all Move types with pinned seeds; inverse of each new type -- AC 2, 3.
- `.../cube/StickerReader.kt` (test source) -- test-only reader `CubeState → 54 Colors` in URFDLB Kociemba-net order (U: B edge on top; D: F edge on top; F R B L: U edge on top), derived from 3D sticker geometry and the `Cubies` slot/twist conventions, mapping each Learner facelet through `orientation` (including in-face rotation of the 3×3 grid) -- needed to compare against fixtures.
- `core/domain/src/test/resources/fixtures/slice-moves.txt` -- one line per case `<token> <54 letters>` using `W Y G B R O`, for `SOLVED`, `L`, `D`, `F`, `M`, `M'`, `E`, `E'`, `S`, `S'`, each hand-derived from a solved home cube (not generated by the code under test); plus a short layout comment -- AC 4 sticker fixtures.
- `.../cube/SliceFixtureTest.kt` -- `SOLVED` and each listed Move from SOLVED matches its fixture line; for M/E/S additionally assert that the stickers changed by the middle layer carry the same colors in the same places as those `L`/`D`/`F` change on their outer layer (adjacent rows/columns) -- AC 4.

**Acceptance Criteria:**
- Given `Notation.parse`, when it receives face, wide (u d l r f b), slice (M E S) and Cube Rotation (x y z) tokens with `'` and `2` suffixes, then it returns `Move` values, or a sealed parse error naming the bad token (never an exception).
- Given a Cube Rotation, when it is applied, then only `orientation` changes, and slice/wide Moves equal their face-turn equivalent plus a rotation.
- Given two Moves with the same effect, such as `R2` and `R2'`, when they are compared, then they are equal, and `Notation.format` emits the canonical form.
- Given the WCA slice convention, when `M`, `E` and `S` are applied, then they turn like `L`, `D` and `F` respectively, verified against sticker fixtures.
- Given the cube package, when `./gradlew :core:domain:check` runs, then the existing 80% module and 90% cube-package Kover rules still pass.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass
- verdicts: 22 findings — high 0, medium 3, low 12, false 7, maybe-false 0
- findings:
  - `[low]` `[patch]` (edge) `trim()` strips Unicode whitespace but `split(\s+)` does not, so `"R U"` with an NBSP became `BadToken("R U", 0)` — patched: `parse` splits on `Char.isWhitespace` (same rule as `trim`); NotationTest pins NBSP and em-space separators.
  - `[low]` `[reject]` (edge) fixture loader ignores extra fields / unclear error on one-field lines — test-only file edited by hand, a bad line already fails loudly (exception or key-list test); a guard adds code for a case nobody meets in normal use.
  - `[false]` `[reject]` (intent) the 27 equivalence pairs in `MoveTypesTest` repeat `parts()` — refuted as a defect: a wrong slice entry fails `SliceFixtureTest` (hand-derived stickers), and wide Moves are checked independently as face + adjacent slice (`r = R M'`); the implementer showed a reversed M failing 2 fixture tests.
  - `[false]` `[reject]` (intent) equality is per token, not by effect across Move values — refuted: no two distinct single Move values in this model have the same effect, so per-token equality is equality by effect for single Moves; sequences are not Moves.
  - `[false]` `[reject]` (intent) fixtures are read through a test-only reader, not production `Facelets` — refuted: the AC asks for sticker fixtures, which this checks; production `Facelets` is story 1.4 per the epic.
  - `[medium]` `[patch]` (intent) fixtures cover only home orientation and quarter turns — grouped with the non-home rotation entry below; patched there.
  - `[false]` `[reject]` (intent) grammar follows the AC's literal list and rejects `Rw`/`X` — refuted as a defect: the AC names exactly `u d l r f b`, `M E S`, `x y z` with `'` and `2`.
  - `[false]` `[reject]` (intent) additions not named in the ACs (`index`, whitespace splitting, empty input) — refuted as a defect: none conflicts with a Never rule; all are the minimum shape of a token parser that names the bad token.
  - `[low]` `[patch]` (blind) Unicode whitespace mismatch in `parse` — same root as the edge row; same patch.
  - `[low]` `[reject]` (blind) typographic primes `’`/`′` from the web are rejected — the intent defines `'`; normalizing adds a branch for an input source (pasting) that no screen offers yet.
  - `[false]` `[reject]` (blind) rejecting `Rw` contradicts "per WCA" — refuted: WCA is cited only for slice directions (AD-7); the story's AC specifies lowercase wide tokens.
  - `[low]` `[reject]` (blind) `BadToken.index` is a token index, not a character offset — adds public surface no caller needs yet; the token text is returned verbatim.
  - `[false]` `[reject]` (blind) equivalents table repeats implementation, so a wrong `S` copied into both would pass — refuted: `SliceFixtureTest` would fail on a wrong `S` (hand-derived S/S' stickers plus the middle-layer-vs-F comparison).
  - `[medium]` `[patch]` (blind) no sticker check after a rotation (`x M`, `y S`) — grouped with the non-home rotation entry; patched with the `y M` sticker test.
  - `[low]` `[patch]` (blind) no half-turn check for new Move types — patched: pinned-seed property `half turn equals two quarter turns for every new Move type` (seed 7313).
  - `[low]` `[patch]` (blind) `Move` KDoc overstates equality by effect — patched: reworded to "each effect of a single token has exactly one value: `R2'` parses to the same value as `R2`".
  - `[low]` `[patch]` (blind) public `apply` KDoc links internal `[parts]` — patched: link dropped, equivalences named in text.
  - `[low]` `[reject]` (blind) Move types have no Notation `toString` — adds public behavior; data-class `toString` is readable enough in failure messages.
  - `[low]` `[reject]` (blind) unchecked `as NotationResult.Parsed` casts in two test helpers — a failure is still a loud test failure; unifying helpers is churn with no named harm.
  - `[low]` `[patch]` (blind) `quarter turn four times…` test draws all turns — patched: renamed `any Move four times is identity for every Move type`.
  - `[low]` `[reject]` (blind) fixture loader accepts malformed lines silently — same as the edge fixture-loader row.
  - `[medium]` `[patch]` (verification-gap) Cube Rotations only tested from HOME — patched: `rotations act from non-home orientations` asserts exact results of x/y/z from `of(U, R)` (→ `of(R, D)`, `of(U, B)`, `of(F, R)`) and `of(F, D)` (→ `of(D, B)`, `of(F, R)`, `of(L, D)`), hand-derived, cubies unchanged; plus `y M` stickers `U=WOWWOWWOW R=BBBBBBBBB F=RWRRWRRWR D=YRYYRYYRY L=GGGGGGGGG B=OYOOYOOYO`, hand-derived. Note: this layer's full report was relayed by the coordinator after an interruption; only this finding was received.

## Design Notes

Why the equivalences hold (`M = R L' x'`): `x'` turns all three layers like `L`; `R` beforehand cancels it on the right layer and `L'` cancels it on the left, leaving only the middle turned like `L`. `r = L x`: `x` turns all layers like `R`; `L` cancels it on the left. Others follow by symmetry. Because parts are Learner-frame Moves applied in order, this works under any orientation with no extra code.

Expected sticker picture for `M` from solved home: Learner F middle column WHITE (from U), D middle column GREEN, B middle column YELLOW, U middle column BLUE; R and L faces unchanged; centers move too, so Learner F's center is WHITE (orientation changed by `x'`).

Sticker reader sketch: model each of the 54 Learner facelets as (position, normal) in Learner coordinates; map them to home coordinates using the orientation (Learner face → `homeFaceAt` axis); find the home slot and facet; look up the cubie in that slot and its twist/flip to get which home face color appears. Keep it independent of `QUARTER_TURNS`.

## Verification

**Commands:** (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :core:domain:check :core:domain:koverXmlReport` -- expected: all tests pass; cube package ≥ 90% lines.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Story 1.3 adds wide (`WideTurn`), slice (`SliceTurn`, WCA `M E S`) and Cube Rotation (`CubeRotation`, `x y z`) Moves to the sealed `Move` in `:core:domain`. A rotation changes only `orientation`. Wide and slice Moves apply as Learner-frame face turns plus a rotation (`r = L x`, `M = R L' x'`, ...). `Notation.parse` returns `NotationResult.Parsed(moves)` or `Invalid(BadToken(token, index))` and never throws; `Notation.format` emits canonical tokens (`R2'` → `R2`). Slices are checked against hand-derived 54-sticker fixtures read by a test-only geometric `StickerReader`.

**Files changed:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- `Slice`, `Axis`, `WideTurn`, `SliceTurn`, `CubeRotation`.
- `.../cube/Orientation.kt` -- internal `rotated(axis, turn)`.
- `.../cube/CubeState.kt` -- `apply` handles every Move type; internal `parts` equivalence tables.
- `.../cube/Notation.kt` (new) -- `parse`, `format`, `NotationResult`, `NotationError.BadToken`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/{AllMoves,StickerReader}.kt` (new) -- generators for all Move types; test-only sticker reader.
- `.../cube/{Notation,MoveTypes,SliceFixture}Test.kt` (new) -- I/O matrix, group-law properties over all Move types, non-home rotations, fixtures, `y M` stickers.
- `core/domain/src/test/resources/fixtures/slice-moves.txt` (new) -- SOLVED, L, D, F, M, M', E, E', S, S' stickers.

**Review findings:** 22 findings (high 0, medium 3, low 12, false 7, maybe-false 0).
- Patched 6 entries: rotations/slices from non-home orientations (medium; grouped from intent, blind and verification-gap rows); Unicode whitespace split (low; edge + blind); half = two quarters for new types (low); `Move` KDoc wording (low); `apply` KDoc internal link (low); misleading test name (low).
- Deferred: none.
- Rejected: fixture-loader guards ×2, typographic primes, character offset in `BadToken`, notation `toString`, unchecked test casts (low, not worth the added code); 7 false findings refuted. Every reason is in the Review Triage Log.

**Follow-up review recommended:** false -- first pass patched no high and only one medium entry (patched counts: high 0, medium 1, low 5).

**Verification:**
- `./gradlew :core:domain:check :core:domain:koverXmlReport` -- exit 0; 59/59 tests pass in 7 suites; cube package 231/231 lines (100%).
- `./gradlew spotlessCheck build` -- exit 0.
- Fixture strings for L, D, F, M, E, S were re-derived by hand during review and match.

**Residual risks:**
- Sticker fixtures and `StickerReader` share one picture of the Kociemba net; production `Facelets` (story 1.4) should cross-check the same fixtures.
- The verification-gap layer's full report was not received (session interruption); only its rotation finding, relayed by the coordinator, was triaged.
