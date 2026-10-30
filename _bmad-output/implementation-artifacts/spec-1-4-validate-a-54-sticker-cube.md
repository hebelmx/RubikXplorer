---
title: 'Story 1.4: Validate a 54-sticker cube'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '9a92c34828d3697cb312b18b94879b486aa84f04'
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

**Problem:** Nothing turns 54 entered or scanned sticker colors into a `CubeState`, and nothing tells the Learner why a set of colors cannot be a real cube. The Manual Cube Editor (FR-14), scan review (FR-23) and the renderer's `renderedFacelets()` check all need one sticker layout and one validation gate.

**Approach:** Add a production `Facelets` type (54 `Color`s, URFDLB, row-major per the Kociemba net), `CubeState.toFacelets()`, and `CubeValidator.validate(Facelets): ValidationResult`, the only route from `Facelets` to `CubeState`. Failures are sealed values, one per rule, each with a `Text` key and, for local rules, the offending sticker positions. Add a minimal `Text(key, args)` value type.

## Boundaries & Constraints

**Always:**
- Pure Kotlin in `:core:domain`. `Facelets`, `CubeValidator` and `ValidationResult` live in package `dev.rubikxplorer.domain.cube` (covered by the 90% cube gate). `Text` lives in `dev.rubikxplorer.domain.text`. Tests go in the same package as the subject; fixtures go under `core/domain/src/test/resources/fixtures/`.
- `Facelets(colors: List<Color>)`: exactly 54 entries, index = `face.ordinal * 9 + row * 3 + col` (URFDLB; U has the B edge on top, D has the F edge on top, F R B L have the U edge on top). A wrong size is programmer error (`require`), not a validation result.
- `CubeState.toFacelets()` returns what the Learner sees, so it accounts for `orientation`. It uses the `Cubies` slot, twist and flip conventions in the `Cubies` KDoc. It must agree with the test-only geometric `StickerReader`, but it is derived independently (for example, from per-slot facelet index tables). It never calls test code.
- `validate` returns `Valid(state)` with `state.toFacelets() == input` for every legal input, in any of the 24 orientations. The orientation comes from the six centers.
- Checks run in this order; the first rule that fails is returned: (1) color counts, (2) impossible pieces, (3) twisted corner, (4) flipped edge, (5) parity.
  - **Counts:** each of the 6 colors appears exactly 9 times. Otherwise `WrongColorCounts(counts)`, where `counts` covers all 6 colors, including zeros.
  - **Impossible pieces:** first the centers must form one of the 24 orientations. Then every corner and edge position must show the colors of a real cubie, in a chirality that a real cubie can show, and each cubie must appear exactly once. Otherwise `ImpossiblePiece(positions, piece)`. `piece` names the first kind with a fault, checked in the order center, corner, edge. `positions` holds every sticker index of every faulty piece of that kind, ascending (for centers: all six center indices).
  - **Twisted corner:** the sum of corner twists is not 0 mod 3. **Flipped edge:** the sum of edge flips is not 0 mod 2. **Parity:** the corner permutation parity differs from the edge permutation parity. All three are global, so their `positions` are empty.
- Every `Invalid` exposes `reason: Text` and `positions: List<Int>`. Keys: `msg.validation.wrong-color-counts`, `msg.validation.impossible-piece` (arg `piece` = `center` | `corner` | `edge`), `msg.validation.twisted-corner`, `msg.validation.flipped-edge`, `msg.validation.parity`.
- `Text(key: String, args: Map<String, Any> = emptyMap())` is an immutable data class.
- `ValidationResult.Valid` has a non-public constructor. No public function other than `CubeValidator.validate` takes `Facelets` and returns a `CubeState`, `Cubies` or `ValidationResult`.
- Property tests use Kotest `checkAll` with a pinned `PropTestConfig(seed = …)` under JUnit Jupiter, as in `CubeStateTest`.

**Never:**
- No Manual Cube Editor UI, no scan types, no `content/strings/en.json` bundle or content test (Epic 2 story), no stored 54-char facelet-string or orientation-ID format, and no Scramble (story 1.5).
- `validate` never throws for any 54-color input, and never returns `Valid` just because all 54 colors are filled in.
- No copying of third-party cube or solver code. No new module. No edits to planning artifacts, `_bmad/`, `.claude/`, or the coverage gates.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved | `SOLVED.toFacelets()` | equals the solved colors in the layout fixture; `validate` → `Valid(SOLVED)` | none |
| Round trip | 1,000 random legal states (all Move types, any orientation; pinned seed) | `toFacelets()` == `StickerReader.read`; `validate(s.toFacelets())` == `Valid(s)` | none |
| Wrong counts | `SOLVED` with sticker 0 changed to YELLOW | `WrongColorCounts` (WHITE 8, YELLOW 10, others 9), empty positions | value, never thrown |
| Bad centers | `SOLVED` with centers 4 (U) and 31 (D) swapped | `ImpossiblePiece(piece=center, positions=[4,13,22,31,40,49])` | value |
| Two whites | `SOLVED` with 8 (U8 of URF) and 18 (F0 of UFL) swapped | `ImpossiblePiece(piece=corner, positions=[6,8,9,18,20,38])` | value |
| Mirrored corner | `SOLVED` with 9 (R0) and 20 (F2) swapped | `ImpossiblePiece(piece=corner, positions=[8,9,20])` | value |
| Twisted corner | `SOLVED` with URF's stickers 8→9→20→8 cycled | `TwistedCorner`, empty positions | value |
| Flipped edge | `SOLVED` with 5 (U5) and 10 (R1) swapped | `FlippedEdge` | value |
| Parity | `SOLVED` with whole edges UR (5,10) and UF (7,19) exchanged, each keeping its U sticker on U | `ParityViolation` | value |
| Any orientation | each rule's case built from `CubeState(SOLVED cubies, o).toFacelets()` for a non-home `o` | the same failure kind | value |
| 54 filled, illegal | every swap of two non-center stickers of different colors in `SOLVED` (960 cases) | never `Valid` | value |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Cubies.kt` -- `internal constructor`; slot order (corners URF UFL ULB UBR DFR DLF DBL DRB, edges UR UF UL UB DR DF DL DB FR FL BL BR), twist and flip definitions in the KDoc; `CORNERS/EDGES/TWISTS/FLIPS` constants; `SOLVED`. Decoding builds `Cubies` through the internal constructor. Its `init` checks shape only.
- `.../cube/Orientation.kt` -- `of(up, front)` (requires adjacency), `homeFaceAt`, `colorAt`, `ALL`. Centers → orientation: home face of the U center color is `up`, home face of the F center color is `front`; then all six centers must equal `colorAt`.
- `.../cube/Color.kt` -- `Color.home(face)`; the inverse map is color → home face.
- `.../cube/CubeState.kt` -- public data class `(cubies, orientation)`; add `toFacelets()` here.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/StickerReader.kt` -- test-only geometric reader `CubeState → List<Color>`. It is the independent oracle for `toFacelets`. Keep it; don't make production code depend on it.
- `.../cube/AllMoves.kt` (test) -- `arbAnyState` (any orientation, every Move type). Reuse it for the 1,000-state round trip.
- `.../cube/SliceFixtureTest.kt` + `core/domain/src/test/resources/fixtures/slice-moves.txt` -- hand-derived sticker fixtures read through `StickerReader`. Also assert `toFacelets()` matches them.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/CubeCoverageGate.kt` -- the cube package needs ≥ 90% lines. Read-only.
- Sticker anchors (Kociemba net): URF = U8 8, R0 9, F2 20; UFL = U6 6, F0 18, L2 38; UR = U5 5, R1 10; UF = U7 7, F1 19; centers 4, 13, 22, 31, 40, 49.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt` -- `data class Text(val key: String, val args: Map<String, Any> = emptyMap())`. KDoc the key form `<area>.<contentId>.<field>[.<n>]` -- AD-6.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Facelets.kt` -- `data class Facelets(val colors: List<Color>)` with the size `require` and a KDoc of the layout -- AD-2.
- `.../cube/CubeState.kt` -- add `fun toFacelets(): Facelets` (Learner view, through `orientation`) -- AD-2.
- `.../cube/CubeValidator.kt` -- `object CubeValidator { fun validate(facelets: Facelets): ValidationResult }` plus `sealed interface ValidationResult { Valid; sealed interface Invalid : ValidationResult { reason; positions }; WrongColorCounts; ImpossiblePiece; TwistedCorner; FlippedEdge; ParityViolation }`. `Valid` uses an internal constructor (`@ConsistentCopyVisibility`). Decoding stays private or internal to this file -- AD-2 single gate, AD-12.
- `core/domain/src/test/resources/fixtures/facelets-layout.txt` -- checked-in ASCII net diagram showing each cell's index (0–53) and a second net with the solved home colors (W Y G B R O), hand-written, with a comment block on how to read it -- AC 1.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/FaceletsTest.kt` -- parse the diagram: every index cell equals `face.ordinal*9+row*3+col`, and the solved net equals `SOLVED.toFacelets()`; `Facelets` of the wrong size throws; round-trip property over 1,000 `arbAnyState` states with a pinned seed (iterations = 1000); solved in every orientation round-trips -- AC 1.
- `.../cube/CubeValidatorTest.kt` -- every I/O-matrix failure row, with the exact variant, positions and `reason.key`/args; the non-home orientation variants; all 960 two-sticker swaps are never `Valid` -- AC 2, 3.
- `.../cube/SingleGateTest.kt` -- list the compiled classes of `dev.rubikxplorer.domain.cube` from the test classpath. Using Java reflection, assert that the only public, non-synthetic method or constructor whose name has no `$`, that takes a `Facelets` and returns `CubeState`, `Cubies` or `ValidationResult`, is `CubeValidator.validate`. Also assert that `ValidationResult.Valid` has no public constructor -- AC 4.
- `.../cube/SliceFixtureTest.kt` -- also assert `toFacelets().colors` equals each fixture line (cross-checks the production reader against the hand-derived stickers) -- AC 1.

**Acceptance Criteria:**
- Given the checked-in layout diagram fixture, when `Facelets` are converted to and from `CubeState`, then round trips match the fixture for the solved state and for 1,000 random legal states.
- Given `CubeValidator.validate(Facelets)`, when the input has wrong color counts, an impossible piece, a twisted corner, a flipped edge or a parity violation, then it returns that rule's sealed `ValidationResult` failure, with the offending positions where the rule is local and a `Text` key for the plain-language reason.
- Given 54 colors that are all filled in, when they form an illegal state, then validation still fails.
- Given the codebase, when any module needs a `CubeState` from `Facelets`, then `CubeValidator` is the only public route.
- Given the cube package, when `./gradlew :core:domain:check` runs, then the 80% module and 90% cube-package Kover rules still pass.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass
- verdicts: 23 findings — high 0, medium 0, low 16, false 7, maybe-false 0
- findings:
  - `[low]` `[patch]` (verification-gap) no test reaches the `up == front` half of the center guard, so dropping it would let `Orientation.of` throw unnoticed — patched: centers test adds `swap(solved, 22, 0)` → `ImpossiblePiece([4,13,22,31,40,49], CENTER)`.
  - `[false]` `[reject]` (intent) random states are checked against `StickerReader`, not the fixture — refuted: the fixture is the layout diagram; `FaceletsTest` pins every index cell to it and the solved net, and the 1,000 states match the geometric reader that draws that same net (plus the hand-derived slice fixtures via `toFacelets`).
  - `[false]` `[reject]` (intent) the "from" direction exists only as `validate` — refuted: AC 4 requires `CubeValidator` to be the only route from `Facelets` to `CubeState`.
  - `[false]` `[reject]` (intent) global rules carry no positions — refuted: the AC asks for positions only "where the rule is local"; any single corner or edge can restore a twist/flip/parity sum, so no piece is to blame.
  - `[false]` `[reject]` (intent) no string bundle, so nothing resolves to words — refuted: the AC asks for "a `Text` key for the plain-language reason"; the bundle and its content test belong to the Epic 2 content story.
  - `[low]` `[patch]` (intent) AC 3 sampled only by single two-sticker swaps — grouped with the blind fuzz finding; patched there.
  - `[low]` `[patch]` (intent) single-gate scan covers one package only — grouped with the blind/edge single-gate findings; patched there.
  - `[false]` `[reject]` (intent) first-failure-wins order is not in the intent — refuted as a defect: the AC asks for "a failure for that rule", which one result per input satisfies; every rule is tested in isolation.
  - `[false]` `[reject]` (blind) `CORNER_FACES` duplicates StickerReader's table, so both could share a wrong chirality — refuted: StickerReader reorders its corner faces geometrically (`clockwiseFromUD`), so a wrong production order would fail the 1,000-state round trip.
  - `[low]` `[patch]` (blind) no fuzz test for "never throws for any 54-color input" — patched: two pinned-seed `checkAll` properties (centers-fixed shuffles of solved, seed 6_404; arbitrary 54-color lists, seed 7_404), asserting no throw and `Valid` ⇒ `toFacelets() == input`.
  - `[low]` `[patch]` (blind) impossible edge only tested at home orientation — patched: `swap(c, 10, 7)` added to the shared cases, so it runs in all 24 orientations.
  - `[low]` `[reject]` (blind) `WrongColorCounts.reason` has no args — the variant already carries `counts` for the UI; adding Text args is new public behavior with no consumer yet.
  - `[low]` `[patch]` (blind) single-gate test misses other packages and wrapper return types — patched: scan walks all of `dev.rubikxplorer.domain` and flags generic return types mentioning the gated types. The synthetic-constructor part is not reachable from Kotlin/Java source; `Result<…>`/inline-class returns stay invisible to Java reflection (residual risk).
  - `[low]` `[reject]` (blind) `toFacelets`/`validate` repeat linear lookups per call — 54 stickers over 20 small slots is negligible; caching per orientation adds code for no measured cost.
  - `[false]` `[reject]` (blind) `pieceToHome` can index -1 — refuted: `Orientation` has a private constructor and only the 24 valid rotations exist, so every slot maps to a home slot.
  - `[low]` `[reject]` (blind) `ImpossiblePiece` does not enforce sorted, in-range positions — only the validator produces these in practice; guards add code for misuse nobody meets.
  - `[low]` `[reject]` (blind) 960-swap test asserts only "not Valid" — that is exactly AC 3; per-rule routing is pinned by the explicit cases.
  - `[low]` `[patch]` (blind) `SliceFixtureTest` duplicates the SOLVED-or-parse branch — patched: private `stateAfter(token)` shared by both tests.
  - `[low]` `[reject]` (edge) `Facelets` keeps a caller's mutable list by reference — same as `Cubies`' existing pattern; mutating after construction is unusual and a defensive copy changes the data-class shape.
  - `[low]` `[reject]` (edge) result data classes accept arbitrary arguments — same root as the positions-invariant row; same reason.
  - `[low]` `[reject]` (edge) `counts` map mutable via cast to `MutableMap` — requires deliberate abuse of the read-only type.
  - `[low]` `[patch]` (edge) a route returning `List`/`Result` wrapping `CubeState` would pass the gate test — grouped with the blind single-gate row; patched there.
  - `[low]` `[reject]` (edge) `Text.args` aliasing a caller's mutable map — same reasoning as the `Facelets` aliasing row.

## Design Notes

Why twist, flip and parity carry no positions: fixing any one corner (or edge) would restore the sum, so no single piece is to blame. Only impossible pieces and centers are local. FR-23's "names the likely bad stickers" therefore applies only to `ImpossiblePiece`.

Decoding sketch: for each corner slot, read its three Learner facelets. The slot is identified by the home faces of the adjacent center colors; the cubie by the home faces of its sticker colors. The twist is the position of the cubie's U/D-colored sticker, counted clockwise from the slot's U/D facet. A color set that matches no cubie, or matches one only in mirror order, is impossible. Edges work the same way with the reference sticker (U/D, else F/B).

## Verification

**Commands:** (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :core:domain:check :core:domain:koverXmlReport` -- expected: all tests pass; cube package ≥ 90% lines.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Story 1.4 adds `Facelets` (54 `Color`s, URFDLB, Kociemba net), `CubeState.toFacelets()` (Learner view through `orientation`, table-driven, independent of the test `StickerReader`), and `CubeValidator.validate(Facelets): ValidationResult`, the only public route from stickers to a `CubeState`. Rules run counts → impossible pieces (center, corner, edge) → twisted corner → flipped edge → parity; each failure is a sealed value with a `msg.validation.*` `Text` key, and `ImpossiblePiece` carries the offending Learner sticker indices. `Text(key, args)` is new in `dev.rubikxplorer.domain.text`.

**Files changed:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt` (new) -- `Text(key, args)`.
- `.../domain/cube/Facelets.kt` (new) -- `Facelets` plus internal `FaceletTables` (slot facelet indices, Learner↔home mapping).
- `.../domain/cube/CubeValidator.kt` (new) -- `CubeValidator`, `ValidationResult` (Valid with private constructor), `PieceKind`.
- `.../domain/cube/CubeState.kt` -- `toFacelets()`.
- `core/domain/src/test/resources/fixtures/facelets-layout.txt` (new) -- index net and solved color net.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/{Facelets,CubeValidator,SingleGate}Test.kt` (new) -- layout fixture, 1,000-state round trip, every rule in all 24 orientations, 960 swaps, fuzz properties, reflection single-gate check.
- `.../cube/SliceFixtureTest.kt` -- `toFacelets()` also checked against hand-derived slice fixtures.

**Review findings:** 23 findings (high 0, medium 0, low 16, false 7, maybe-false 0).
- Patched 5 entries (all low): same-color U/F centers test; fuzz properties for never-throws / never-valid-by-default; impossible edge in every orientation; single-gate scan widened to all of `dev.rubikxplorer.domain` and generic return types; shared `stateAfter` helper in `SliceFixtureTest`.
- Deferred: none.
- Rejected: Text args on wrong counts, per-call lookup cost, positions/counts invariants on public result constructors (×2), 960-swap test strength, mutable-list/map aliasing in `Facelets`, `Text` and `counts` (×3) — low, not worth the added code; 7 false findings refuted. Every reason is in the Review Triage Log.

**Follow-up review recommended:** false -- first pass patched no high or medium entries (patched counts: high 0, medium 0, low 5).

**Verification:**
- `./gradlew :core:domain:check :core:domain:koverXmlReport` -- exit 0; 78/78 domain tests pass; cube Kover gate passes.
- `./gradlew spotlessCheck build` -- exit 0.
- Matrix audit: every I/O-matrix row is covered by a passing test in `CubeValidatorTest`, `FaceletsTest` or `SliceFixtureTest`.

**Residual risks:**
- `SingleGateTest` uses Java reflection, so a function returning `kotlin.Result<CubeState>` or another inline-class wrapper compiles to `Object` and would not be flagged.
- `SingleGateTest` lists classes from the build output directory; a jar-packaged test classpath would make it fail loudly.
- No `content/strings/en.json` entries exist yet for the five `msg.validation.*` keys; the Epic 2 content test must add them.
