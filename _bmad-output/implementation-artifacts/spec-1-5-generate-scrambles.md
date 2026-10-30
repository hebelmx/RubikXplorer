---
title: 'Story 1.5: Generate Scrambles'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: 'ec5dfbb729dba06ed5e2e5f7fdd5508c8b1472d3'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/AGENTS.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Practice, skip-ahead checks and the P0.1 exit demo all need a fresh random Scramble, but nothing generates one, and AD-11 requires that randomness be injected and that Scrambles travel as explicit Moves, never as seeds.

**Approach:** Add a `RandomSource` port and a `NewScramble` use case in `:core:application`. `NewScramble` draws 25 face turns from home through the port and returns them as an explicit `List<Move>`; tests drive it with a seeded fake.

## Boundaries & Constraints

**Always:**
- Pure Kotlin in `:core:application`, plain constructor injection (no Hilt, no Android). `RandomSource` lives in `dev.rubikxplorer.application.port`; `NewScramble` in `dev.rubikxplorer.application.usecase`. Tests go in the same package as the subject.
- `interface RandomSource { fun nextInt(until: Int): Int }` -- returns a value in `0 until until`. It exposes no seed, no state accessor and nothing else.
- `class NewScramble(private val random: RandomSource)` with `operator fun invoke(): List<Move>`. The returned list is the whole Scramble: exactly `NewScramble.LENGTH = 25` `FaceTurn`s, meant to be applied from `CubeState.SOLVED` (home). No wide, slice or Cube Rotation Moves, so the orientation stays home.
- No Move cancels or merges with its neighbors: a face never repeats the previous Move's face, and a face is never drawn when the two previous Moves are on its axis (e.g. after `R L`, neither `R` nor `L`). Within those rules, the face is picked uniformly from the allowed faces and the `Turn` uniformly from the three turns, each by one `nextInt` call.
- A `RandomSource` returning a value outside `0 until until` is programmer error: `NewScramble` fails with `IllegalStateException` (`check`).
- Tests use a test-only `SeededRandomSource(seed: Long)` backed by `kotlin.random.Random(seed)` with pinned seeds, under JUnit Jupiter.

**Never:**
- No seed parameter, seed field or seed in the return value of `NewScramble`; no persistence, session, `SessionEvent` or Checkpoint wiring (Epic 4/7); no `:app` adapter or Hilt binding for `RandomSource` (added with the first screen that needs it).
- No changes to `:core:domain` production code, the coverage gates, planning artifacts, `_bmad/` or `.claude/`. No new module, no new dependency.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Legal from home | `NewScramble(SeededRandomSource(1))()` | 25 `FaceTurn`s; `CubeValidator.validate(SOLVED.apply(moves).toFacelets())` is `Valid` with state `SOLVED.apply(moves)` and `Orientation.HOME` | none |
| Reproducible | two `NewScramble` instances, each with `SeededRandomSource(42)` | identical Move lists | none |
| Seeds differ | seeds 1 and 2 | different Move lists | none |
| No cancellation | any generated Scramble | no two neighbors share a face; no three in a row share an axis | none |
| 10,000 Scrambles | one `NewScramble`, seeded source, 10,000 calls | every state validates `Valid`; none `isSolved`; all 10,000 states distinct; each of the 18 face turns occurs within ±10% of 10,000·25/18; every Scramble has 25 face turns | none |
| Bad source | a `RandomSource` returning `until` | `IllegalStateException` | programmer error |

</intent-contract>

## Code Map

- `core/application/build.gradle.kts` -- `implementation(project(":core:domain"))`; JUnit Jupiter and Kover ≥ 80% come from the `rubikxplorer.jvm-core` convention plugin (`build-logic/.../JvmCoreConventionPlugin.kt`, read-only). No kotest here; plain JUnit is enough.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/FeatureFlags.kt` -- existing port; follow its KDoc style (cite AD-11).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- `Move`, `FaceTurn(face, turn)`, `Turn.entries` (CLOCKWISE, HALF, COUNTERCLOCKWISE).
- `.../domain/cube/Face.kt` -- `Face.entries` = U R F D L B, so axis = `ordinal % 3` and opposite face = `(ordinal + 3) % 6`.
- `.../domain/cube/CubeState.kt` -- `SOLVED`, `apply(List<Move>)`, `toFacelets()`, `isSolved`, `orientation`.
- `.../domain/cube/CubeValidator.kt` -- `CubeValidator.validate(Facelets): ValidationResult`; `ValidationResult.Valid(state)`.
- `.../domain/cube/Orientation.kt` -- `Orientation.HOME`.

## Tasks & Acceptance

**Execution:**
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/RandomSource.kt` -- add the `RandomSource` port with KDoc: seeds belong only to test fakes and are never persisted in place of Moves -- AD-11.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/NewScramble.kt` -- add `NewScramble` per the Always rules, with `companion object { const val LENGTH = 25 }` -- FR-4, FR-20, AD-11.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/SeededRandomSource.kt` -- test fake over `kotlin.random.Random(seed)`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/NewScrambleTest.kt` -- one test per I/O-matrix row, plus a reflection check that `RandomSource` declares only `nextInt` and that `NewScramble`'s only public constructor takes just a `RandomSource` -- AC 1–3.

**Acceptance Criteria:**
- Given the `NewScramble` use case and an injected `RandomSource`, when it is called, then it returns an explicit `List<Move>` from home that yields a legal state.
- Given a pinned seed in a test, when `NewScramble` runs twice, then it returns the same Moves, and no seed is ever persisted in place of Moves (the port and use case expose no seed).
- Given 10,000 generated Scrambles, when each is applied and validated, then every resulting state is valid and every Scramble is deep (25 face turns, none cancelling).
- Given `./gradlew spotlessCheck build`, when it runs, then it passes, including the 80% Kover rule for `:core:application`.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass
- verdicts: 16 findings — high 0, medium 0, low 10, false 6, maybe-false 0
- findings:
  - `[low]` `[patch]` (verification-gap) the allowed-face rule was only checked negatively, so a stricter filter (always banning the opposite face) would pass every test — patched: scripted `RandomSource` test pins face-draw `until` values (6, 5, then 4 after `R L`), turn draws (3) and that neither R nor L follows `R L`; the 10,000 run asserts opposite-face neighbors occur.
  - `[low]` `[patch]` (blind) `until` values of each `nextInt` call never checked — same root as the verification-gap row; patched there.
  - `[low]` `[patch]` (blind) axis-closed rule tested only indirectly through seeded output — same root; patched there.
  - `[low]` `[patch]` (blind) test helper `Face.axis = ordinal % 3` depends on enum order while production uses `opposite` — patched: axis is `minOf(ordinal, opposite.ordinal)`.
  - `[low]` `[patch]` (blind) ±10% distribution tolerance is ~12 sigma and misses an 8% skew — patched: tightened to ±3% (still ~3.6 sigma under the pinned seed).
  - `[low]` `[patch]` (blind) `LENGTH` KDoc claims an effective length of exactly 25, overstated because opposite-face pairs commute — patched: KDoc now says "no adjacent cancellations or merges". The spec's Design Notes wording is left as is (fixing it would edit this build's spec).
  - `[low]` `[reject]` (blind) opposite-face pairs allowed in either order skew the state distribution — real but negligible: no random-move Scramble is uniform over states, the spec allows both orders, and a canonical-order rule adds a branch no Learner would notice.
  - `[low]` `[patch]` (blind) no-seed reflection test checked constructors only — patched: also asserts `invoke` is the only public method and `random` the only instance field.
  - `[low]` `[reject]` (blind) `SeededRandomSource` not reusable outside this test source set — no other consumer exists yet; a `testFixtures` set adds build setup for a future need.
  - `[low]` `[patch]` (blind) `invoke()` returned its internal `ArrayList` — patched: returns `moves.toList()`.
  - `[low]` `[patch]` (blind) small simplifications (`lastOrNull`, redundant parentheses) — patched.
  - `[low]` `[patch]` (blind) out-of-range test only hit the first draw — patched: a source that fails on the 4th call (a turn draw) asserts `IllegalStateException` after exactly 4 calls.
  - `[false]` `[reject]` (intent) "no seed is ever persisted" checked only structurally — refuted: nothing persists data yet; the port and use case cannot carry a seed, which is the whole guarantee this story can give; persistence arrives with sessions (Epic 4/7).
  - `[false]` `[reject]` (intent) fixed length 25 makes "distribution covers deep Scrambles" a single point — refuted: the AC's assumption is "≥ 25 random face turns", which every Scramble meets; the distribution is checked over states and turns.
  - `[false]` `[reject]` (intent) no production `RandomSource` or caller — refuted: the AC asks for the use case with an injected source; "Implement exactly this story's acceptance criteria" excludes app wiring.
  - `[false]` `[reject]` (intent) "runs twice" tested with two instances, not one instance twice — refuted: one instance called twice consumes more randomness; the AC means two runs from the same pinned seed, which the test does.

## Design Notes

"Deep" is the epic's assumption of ≥ 25 random face turns. The no-cancel rules make the effective length exactly 25: without them, `R R'` or `R L R` would shorten a Scramble silently. Face turns only keep the Scramble expressed from home, with no rotation to undo.

## Verification

**Commands:** (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :core:application:check` -- expected: all tests pass; Kover ≥ 80%.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Story 1.5 adds the `RandomSource` port and the `NewScramble` use case in `:core:application`. `NewScramble(random)()` returns an explicit `List<Move>` of 25 face turns from home, drawn through the port with no adjacent cancellations or merges (no repeated face, no three in a row on one axis). No seed exists anywhere in the port or use case; tests use a seeded fake.

**Files changed:**
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/RandomSource.kt` (new) -- `nextInt(until)` port, AD-11.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/NewScramble.kt` (new) -- the use case, `LENGTH = 25`, `check` on out-of-range draws.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/SeededRandomSource.kt` (new) -- test-only seeded fake.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/NewScrambleTest.kt` (new) -- 8 tests: legal from home, reproducible, seeds differ, no cancellation, 10,000 Scrambles (valid, not solved, distinct, ±3% turn spread, opposite neighbors occur), scripted draw ranges, bad source, no-seed reflection.

**Review findings:** 16 findings (high 0, medium 0, low 10, false 6, maybe-false 0).
- Patched 8 entries (all low): exact allowed-face draw test (3 grouped rows), order-independent axis helper, ±3% tolerance, LENGTH KDoc wording, wider no-seed reflection check, defensive `toList()`, small simplifications, later-draw out-of-range test.
- Deferred: none.
- Rejected: opposite-pair order skew (negligible, would add a rule), reusable `SeededRandomSource` (no consumer yet); 4 false intent-alignment findings refuted. Every reason is in the Review Triage Log.

**Follow-up review recommended:** false -- first pass patched no high or medium entries (patched counts: high 0, medium 0, low 8).

**Verification:**
- `./gradlew :core:application:check spotlessCheck build` -- exit 0 after patches; `NewScrambleTest` 8/8 and `FeatureFlagsTest` 3/3 pass; Kover rules pass.
- Matrix audit: every I/O-matrix row is covered by a passing test in `NewScrambleTest`.

**Residual risks:**
- The 10,000-Scramble statistics use one pinned seed; a change to the draw order changes the exact counts (the ±3% bound should still hold).
- No `:app` adapter or Hilt binding for `RandomSource` exists yet; the first screen that needs a Scramble must add one.
