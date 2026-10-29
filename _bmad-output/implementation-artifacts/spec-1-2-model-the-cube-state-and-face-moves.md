---
title: 'Story 1.2: Model the Cube State and face Moves'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '5613c2313ac93bdb456b493a25a19045100720eb'
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

**Problem:** `:core:domain` is empty. Every later story (Notation, validation, Scrambles, the free cube, predicates) needs an immutable cube model whose face Moves behave exactly like a real 3×3.

**Approach:** Add the AD-2 cube model in `dev.rubikxplorer.domain.cube`: `Face`, `Color`, `Cubies` (corner/edge permutation + orientation, relative to centers), `Orientation` (one of 24 whole-cube rotations), `CubeState(cubies, orientation)`, and face Moves (U R F D L B; clockwise, half, counterclockwise) applied through `orientation`. Prove it with Kotest property tests on pinned seeds and gate the cube package at ≥ 90% Kover line coverage.

## Boundaries & Constraints

**Always:**
- Pure Kotlin in `:core:domain` (no Android/Compose/Hilt). Package `dev.rubikxplorer.domain.cube`; tests in the same package.
- Everything immutable with structural equality (`data class` / `List`, never exposed arrays). `apply` returns a new `CubeState`; the input is unchanged.
- Naming verbatim: `Face` = `U R F D L B`; `Color` = `WHITE YELLOW GREEN BLUE RED ORANGE`. Home frame: WHITE on U, GREEN on F, RED on R (so YELLOW D, BLUE B, ORANGE L).
- A face Move names a Learner-facing face. It turns the home face that `orientation` currently places there, in the same direction (rotations preserve handedness). Moves never change `orientation`.
- `isSolved` checks `cubies` only and ignores `orientation`.
- Turn direction is as seen looking at that face from outside the cube (standard notation).
- Invalid construction input (wrong list size, out-of-range value, impossible up/front pair) is programmer error: `require(...)`. No legality checks (twist/flip/parity) — that is `CubeValidator` in 1.4.
- Property tests use Kotest property (`checkAll`/`forAll` with `PropTestConfig(seed = …)`, pinned seeds) under JUnit Jupiter.

**Never:**
- No Notation parsing/formatting, wide/slice Moves, Cube Rotations (x y z) as Moves, `Facelets`, or `CubeValidator` — stories 1.3/1.4.
- No copying third-party solver/cube code (clean-room rule in AGENTS.md); write move definitions from the cube's geometry.
- No new module, no changes to planning artifacts, `_bmad/`, `.claude/`.
- Do not lower the 80% gate or put coverage rules in per-module build scripts — gates live in `build-logic`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Face move on solved | `CubeState.SOLVED.apply(R)` | new state ≠ SOLVED, `isSolved` false; `SOLVED` unchanged | none |
| Quarter ×4 | any state `s`, any face Move `m`, `s.apply(m,m,m,m)` | equals `s` | none |
| Move + inverse | `s.apply(m).apply(m.inverse())` | equals `s`; inverse of half is itself; inverse of CW is CCW | none |
| Sequence + inverse | random `List<Move>` `seq`, `s.apply(seq).apply(seq.inverse())` | equals `s` (inverse = reversed, each inverted) | none |
| Half = two quarters | `s.apply(R2)` vs `s.apply(R, R)` | equal | none |
| Real-cube orders | from SOLVED: `(R U)`, `(R U R' U')`, `(R2 U2)` | return to solved after exactly 105, 6, 6 repetitions, not sooner | none |
| Invariants | random sequence from SOLVED | corner twist sum ≡ 0 (mod 3), edge flip sum ≡ 0 (mod 2), corner and edge permutation parities equal | none |
| Orientation only | `CubeState(Cubies.SOLVED, o)` for each of 24 `o` | `isSolved` true | none |
| Through orientation | `o` with home `R` face at Learner U: `CubeState(Cubies.SOLVED, o).apply(U)` | `cubies` equal `CubeState.SOLVED.apply(R).cubies`; orientation unchanged | none |
| Bad construction | `Cubies` with 7 corners, twist 3; `Orientation.of(U, D)` | `IllegalArgumentException` | programmer error |

</intent-contract>

## Code Map

- `core/domain/build.gradle.kts` -- only applies `rubikxplorer.jvm-core`; add the Kotest test dependency here.
- `core/domain/src/` -- does not exist yet; create `main/kotlin/dev/rubikxplorer/domain/cube/` and `test/kotlin/dev/rubikxplorer/domain/cube/`.
- `gradle/libs.versions.toml` -- single source of versions; add `kotest = "6.2.6"` (verified on Maven Central) and library `kotest-property = io.kotest:kotest-property`.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/JvmCoreConventionPlugin.kt` -- Kover `verify { rule { minBound(80) } }` wired into `check`; add the cube-package rule here.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/ProjectExtensions.kt` -- `libs`, `library(alias)`, `addJUnitDependencies()` helpers to reuse.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/port/FeatureFlagsTest.kt` -- existing JUnit 5 test style (backtick names, `Assertions`).
- Boundary check (`CheckModuleBoundariesTask`) inspects all configurations but only rejects Android/project-edge violations; Kotest is a plain JVM artifact and passes.

## Tasks & Acceptance

**Execution:**
- `gradle/libs.versions.toml` -- add Kotest 6.2.6 version + `kotest-property` library -- property testing per epic stack.
- `core/domain/build.gradle.kts` -- `testImplementation(libs.kotest.property)` -- only domain needs it now.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Face.kt`, `Color.kt` -- the two closed enums in the order above -- AD naming.
- `.../cube/Cubies.kt` -- `Cubies` with `cornerPermutation`, `cornerOrientation`, `edgePermutation`, `edgeOrientation` (`List<Int>`, sizes 8/8/12/12, twist 0..2, flip 0..1, permutations of 0..n-1), `internal` constructor, `Cubies.SOLVED`; internal face-turn application -- AD-2 `cubies`.
- `.../cube/Orientation.kt` -- 24 rotations as which home face sits at each Learner-facing face; `HOME`, `ALL` (24 distinct), `of(up, front)`, `homeFaceAt(face)`, `colorAt(face)` (center color shown on that Learner face) -- AD-2 `orientation`.
- `.../cube/Move.kt` -- `sealed interface Move { fun inverse(): Move }`, `enum class Turn { CLOCKWISE, HALF, COUNTERCLOCKWISE }`, `data class FaceTurn(face: Face, turn: Turn) : Move`, `fun List<Move>.inverse(): List<Move>` -- sealed so 1.3 can add wide/slice/rotation variants; HALF has one value so `R2 == R2'` by construction.
- `.../cube/CubeState.kt` -- `data class CubeState(cubies, orientation)`, `SOLVED`, `apply(move)`, `apply(moves: List<Move>)`, `apply(vararg)`, `isSolved` -- AD-2 state.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/` -- JUnit tests for every I/O-matrix row (example tests for fixed cases; Kotest `checkAll` with pinned seeds for random states/sequences, random states generated by applying random face-Move sequences to SOLVED under random orientations) -- AC 1–3.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/` -- for `:core:domain` only, fail `check` when line coverage of package `dev.rubikxplorer.domain.cube` is < 90% (Kover rule with a package filter if the 0.9.11 DSL supports it; otherwise a small build-logic task reading the Kover XML report, wired after `koverXmlReport` into `check`). Keep the module-wide 80% rule -- AC 4.

**Acceptance Criteria:**
- Given the solved home state, when any of the 18 face Moves is applied, then a new `CubeState` is returned and the original value is unchanged.
- Given Kotest property tests with pinned seeds, when `./gradlew :core:domain:test` runs, then M⁴ = identity, M·M⁻¹ = identity and sequence + inverse = identity pass on random states and sequences.
- Given a state that differs from solved only in `orientation`, when `isSolved` is evaluated, then it is true.
- Given the cube package, when `./gradlew :core:domain:check` runs, then Kover verification passes with cube-package line coverage ≥ 90% and fails if it drops below 90%; it is the highest-covered package in the Kover report.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass
- verdicts: 28 findings — high 0, medium 4, low 14, false 8, maybe-false 2
- findings:
  - `[low]` `[patch]` (edge) `order()` test helper loops forever on a broken table — patched: loop bounded at 1260 repetitions with `check(...)` naming the sequence.
  - `[low]` `[reject]` (edge) exposed `List<Int>` can be cast to `MutableList` and mutated — needs a deliberate unchecked cast against Kotlin's read-only contract; defensive wrapping adds code for a case nobody meets in normal use.
  - `[maybe-false]` `[reject]` (edge) Kover cube filter matching no classes may pass vacuously — unverified whether Kover 0.9.11 passes or fails an empty variant; settle by renaming the package and running `koverVerifyCube`. If true it is only low (the package name is fixed by the spine), so rejected.
  - `[low]` `[reject]` (edge) hard-coded `:core:domain` path silently drops the gate on rename — the CI guard patch (row below) now fails loudly when `:core:domain:koverVerifyCube` disappears; a further opt-in extension adds surface for an unlikely rename.
  - `[maybe-false]` `[reject]` (edge, claim) "fails below 90%" holds only while the filter matches — same unknown and same low if-true grade as the empty-filter row.
  - `[medium]` `[patch]` (verification-gap) CI "Verify build wires the gates" did not list `:core:domain:koverVerifyCube` — patched: added to the `ci.yml` task list; `./gradlew build --dry-run` shows the task.
  - `[medium]` `[patch]` (verification-gap) no fixed-answer test of what a turn does; consistently reversed or twist-mirrored tables pass — patched: `CubiesTest` asserts exact cubies after clockwise R, U and F from solved, each with the physical reason.
  - `[low]` `[reject]` (intent) "exactly like a real cube" lives at sticker level, but no stickers are checked — the intent says implement exactly this story's ACs, which are cubie-level; `Facelets` arrives with 1.3/1.4.
  - `[low]` `[reject]` (intent) immutability is not exercised by a test — grouped with the mutable-list row; immutability rests on `val` + read-only `List`, and the 18-move test confirms `SOLVED` is unchanged.
  - `[false]` `[reject]` (intent) "Kotest property tests" could mean the Kotest framework — refuted: `kotest-property` with pinned `PropTestConfig` seeds is Kotest property testing; the epic stack lists JUnit Jupiter as the JVM runner.
  - `[false]` `[reject]` (intent) random states only sample the reachable subgroup — refuted: unreachable states are not real-cube states; that is exactly what the AC's real cube means.
  - `[false]` `[reject]` (intent) orientation-only state not reached through a rotation Move — refuted: the AC asks for a state whose only difference is `orientation`; rotations are story 1.3.
  - `[low]` `[reject]` (intent) "highest coverage of any package" is not enforced — holds today (cube is at 100% lines, the maximum, and is the only `:core:domain` package); a relative-ranking gate needs new build machinery.
  - `[medium]` `[patch]` (blind) turn direction and face labels never checked against a known state — grouped with the fixed-answer verification-gap row; same patch.
  - `[false]` `[reject]` (blind) `QUARTER_TURNS` literals are Kociemba's tables, breaking the clean-room rule — refuted: with the standard URF… slot order these values are forced by geometry, not code; the implementer derived them with a geometry script, and the clean-room rule targets solver code (min2phase), not cube facts.
  - `[medium]` `[patch]` (blind) build-logic gate change has no test showing `check` runs `koverVerifyCube` — grouped with the CI-guard row; the dry-run guard now asserts it, and the implementer showed the rule failing at 88.0% by name.
  - `[low]` `[reject]` (blind) AC 4 "highest-covered package" not enforced — same as the intent row above.
  - `[low]` `[reject]` (blind) gate hard-coded to one module path — same as the edge path row; the CI guard makes a rename loud.
  - `[false]` `[reject]` (blind) task name rebuilt from a string; `minBound` defaults — refuted: `dependsOn` on a missing task fails the build loudly, and Kover's `minBound` defaults to LINE / COVERED_PERCENTAGE.
  - `[low]` `[reject]` (blind) lists mutable through a cast — same as the edge mutable-list row.
  - `[low]` `[reject]` (blind) `apply` name clashes with the stdlib scope function — the member overloads win for Move arguments; renaming changes the spec's named API, so the fix would edit this spec.
  - `[low]` `[reject]` (blind) up to 3 composes per Move, with re-validation — negligible at current scale; an unchecked constructor adds surface and no caller needs speed yet.
  - `[false]` `[reject]` (blind) "leaves solved unchanged" test cannot fail — refuted: it compares `SOLVED` after 18 applies against a freshly built value, so any in-place mutation of `SOLVED`'s lists would fail it.
  - `[low]` `[patch]` (blind) `CubiesTest` misses negative `edgeOrientation` and same-size duplicate `edgePermutation` — patched: both rejection assertions added.
  - `[false]` `[reject]` (blind) `Color.home` only tested circularly — refuted: `OrientationTest` asserts `colorAt` under `HOME` against the literal WHITE/RED/GREEN/YELLOW/ORANGE/BLUE list.
  - `[low]` `[reject]` (blind) coroutines and `ExperimentalKotest` used without being declared — they come through `kotest-property`'s published API dependencies; adding catalog entries buys nothing today.
  - `[false]` `[reject]` (blind) pinned seeds do not make failures reproducible — refuted: a fixed `PropTestConfig(seed)` makes the generated inputs deterministic, and Kotest reports the failing input.
  - `[low]` `[patch]` (blind) `isSolved` ignores orientation but `== SOLVED` does not, undocumented — patched: `isSolved` KDoc says to use `isSolved`, not `== SOLVED`.

### 2026-10-29 — Review pass (follow-up)
- verdicts: 24 findings — high 0, medium 3, low 10, false 11, maybe-false 0
- findings:
  - `[low]` `[reject]` (edge) exposed `List<Int>` can be cast to `MutableList`, corrupting shared `SOLVED` — carried: same row as the first pass's mutable-list rejection; needs a deliberate unchecked cast.
  - `[false]` `[reject]` (edge) internal constructor keeps a caller's `MutableList` that may later be mutated — refuted: the constructor is `internal`, and every in-module call site (`SOLVED`, `compose`, `QUARTER_TURNS`, tests) passes a freshly built list that nobody keeps a reference to.
  - `[low]` `[reject]` (edge) hard-coded `:core:domain` path silently drops the gate on rename — carried: the CI dry-run guard requires `:core:domain:koverVerifyCube`, so a rename fails loudly.
  - `[low]` `[reject]` (edge, claim) "highest-covered package" is not enforced — carried: true today (cube 147/147 lines, the only package); a ranking gate needs new build machinery.
  - `[low]` `[reject]` (edge, claim) deep immutability relies on the read-only `List` contract — carried: grouped with the mutable-list row.
  - `[low]` `[reject]` (blind) shared lists mutable through a cast — carried: same as the edge mutable-list row.
  - `[medium]` `[patch]` (blind) D, L and B quarter-turn tables have no exact-effect test — grouped with the verification-gap row; patched: `CubiesTest` now pins D, L and B clockwise from solved. The expected values were checked independently against the standard URF-slot cubie tables, not copied from the code.
  - `[false]` `[reject]` (blind) exact-effect tests repeat production tables so they cannot catch a wrong table; the "derived from geometry" comment is unbacked — refuted: all six tables were checked in this pass against the standard published cubie tables for this slot order and every entry matches, so no table is wrong. The comment describes the derivation method; the first pass recorded the geometry script.
  - `[false]` `[reject]` (blind) `face Moves act through orientation` checks `homeFaceAt` against itself — refuted: `homeFaceAt` is pinned on its own by `OrientationTest` (literal home frame, hand-checked x/y/z cases, opposites kept, round trip through `of`), so a wrong `homeFaceAt` fails there.
  - `[low]` `[reject]` (blind) cube gate can stop working silently on rename or empty filter — carried: same as the path row and the first pass's maybe-false empty-filter row.
  - `[low]` `[reject]` (blind) Moves allocate (vector lists, up to 3 validated composes) — carried: negligible at current scale.
  - `[false]` `[reject]` (blind) invariant test only covers the home orientation — refuted: any face Move under any orientation is exactly one home `FaceTurn`, which is checked for all 24×18 cases, so the invariants hold for every orientation.
  - `[low]` `[reject]` (blind) `apply` clashes with the stdlib scope function — carried: a rename changes the spec's named API.
  - `[low]` `[reject]` (blind) `kotlinx.coroutines.runBlocking` comes in only through a transitive dependency — carried: it is part of `kotest-property`'s published API dependencies.
  - `[low]` `[patch]` (blind) no test for `isSolved` vs `== SOLVED` — patched: `CubeStateTest` asserts that `CubeState(Cubies.SOLVED, o) != SOLVED` for all 23 non-home orientations.
  - `[false]` `[reject]` (blind) no public way to build an arbitrary `Cubies` — refuted: nothing outside `:core:domain` needs one; `Facelets` and `CubeValidator` (1.3/1.4) live in this module, and the intent's bad-construction row is covered.
  - `[false]` `[reject]` (blind) KDoc gaps (`Turn.inverse`, `Face` index) and `Turn.quarters` being internal — refuted as a defect: no harm is named; Notation (1.3) is in the same module and can read `quarters`.
  - `[medium]` `[patch]` (verification-gap) D, L and B edge-flip deltas are unpinned; self-consistent mutations pass every property — patched with the D/L/B exact-effect tests above.
  - `[false]` `[reject]` (intent) `Cubies` construction is internal while the bad-construction row reads like a public API — refuted: same as the public-factory row; the `require` checks run and are tested.
  - `[false]` `[reject]` (intent) no named `R`/`R2`/`R'` Move values — refuted: the intent leaves Notation to 1.3; `FaceTurn(face, turn)` is the face Move.
  - `[false]` `[reject]` (intent) "Through orientation" is checked through the same `homeFaceAt` — refuted: same as the blind self-check row.
  - `[medium]` `[patch]` (intent) D, L and B turn direction is only pinned indirectly — grouped with the D/L/B exact-effect patch.
  - `[false]` `[reject]` (intent) gate passing and Kover filter/line defaults are not shown — refuted: this pass ran `:core:domain:check` (green), `build --dry-run` lists `:core:domain:koverVerifyCube`, and the first pass showed the rule failing by name at 88.0%.
  - `[false]` `[reject]` (intent) additions not named in the intent (`Face.opposite`, `Color.home`, `colorAt`, `ALL`, `HOME`) — refuted as a defect: the auditor found no conflict with any Never rule, and `colorAt`/`ALL`/`HOME` are named in the spec's tasks.

## Design Notes

Cubie model: fix 8 corner slots and 12 edge slots in the home frame; `cornerPermutation[i]` = which corner cubie sits in slot `i`, `cornerOrientation[i]` its twist there (same idea for edges). Each face turn is a 4-cycle of corner slots with twists and a 4-cycle of edge slots with flips. With the usual reference (U/D stickers define corner twist; F/B quarter turns flip edges; R/L/F/B quarter turns twist corners), the "Real-cube orders" and "Invariants" rows catch wrong cycles or twists, which group-identity properties alone cannot.

Orientation: represent it by the home faces at Learner U and F; the rest follow by geometry (right = up × front on face axis vectors). `of(up, front)` requires the two faces to be adjacent. Composition with x/y/z is 1.3's job.

## Verification

**Commands:** (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :core:domain:check` -- expected: tests pass, Kover 80% and cube 90% rules pass.
- `./gradlew :core:domain:koverXmlReport` -- expected: `dev/rubikxplorer/domain/cube` line coverage ≥ 90% and the highest package.
- Temporarily add an untested public function with ~50 lines to the cube package, run `./gradlew :core:domain:check`, expect failure naming the 90% rule; revert.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `./gradlew -p build-logic test` -- expected: pass (if build-logic gained policy code, add its unit test).

## Auto Run Result

Status: done

**Summary:** Story 1.2 adds the AD-2 cube model to `:core:domain` (`dev.rubikxplorer.domain.cube`): `Face`, `Color`, `Cubies`, `Orientation` (24 rotations), sealed `Move` with `FaceTurn`/`Turn`, and immutable `CubeState`. Face Moves act through `orientation`, and `isSolved` ignores it. Kotest property tests with pinned seeds prove M⁴ = id, M·M⁻¹ = id and sequence + inverse = id. A build-logic Kover variant gates the cube package at ≥ 90% lines inside `check`. This follow-up pass pinned the remaining face tables.

**Files changed:**
- `gradle/libs.versions.toml`, `core/domain/build.gradle.kts` -- Kotest 6.2.6 `kotest-property` test dependency.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/{Face,Color,Move,Orientation,Cubies,CubeState}.kt` -- the cube model.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/{CubeState,Cubies,Move,Orientation}Test.kt` -- 34 tests covering every I/O-matrix row plus exact-effect tests for all six clockwise face turns (D/L/B added in the follow-up) and the `isSolved` vs `==` rule.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/CubeCoverageGate.kt`, `JvmCoreConventionPlugin.kt` -- `cube` Kover variant with a 90% line rule; `check` depends on `koverVerifyCube` (`:core:domain` only).
- `.github/workflows/ci.yml` -- gate guard also requires `:core:domain:koverVerifyCube`.
- `epic-1-context.md` -- recompiled in the first pass.

**Review findings (follow-up pass):** 24 findings (medium 3, low 10, false 11, maybe-false 0).
- Patched 2 entries:
  - exact-effect tests for D, L and B clockwise (medium; grouped from the blind, verification-gap and intent rows);
  - `CubeState(Cubies.SOLVED, o) != SOLVED` for non-home orientations (low).
- Deferred: none.
- Rejected: 9 low findings carried from the first pass with their earlier reasons (mutable-list cast ×3, path rename, highest-package ranking, gate on rename/empty filter, allocations, `apply` name, transitive coroutines). 11 false findings were refuted. Every reason is in the Review Triage Log.

**Follow-up review recommended:** false -- this is a follow-up pass and it patched no high entry (patched counts: high 0, medium 1, low 1). The first pass's named risk (twist convention shared by tables and tests) is resolved: this pass checked all six quarter-turn tables against the standard published cubie tables for this slot order, and every entry matched.

**Verification:**
- `./gradlew :core:domain:check :core:domain:koverXmlReport` -- exit 0; 34/34 tests pass; cube package 147/147 lines (100%), the only package.
- `./gradlew spotlessCheck build` -- exit 0.
- `./gradlew -p build-logic test` -- exit 0.
- `./gradlew build --dry-run` -- lists `:core:domain:koverVerifyCube`.

**Residual risks:**
- "Highest-covered package" is true now but not gated.
- After a package rename, an empty cube filter may pass Kover vacuously (unverified). The CI guard catches a module rename, not a package rename.
