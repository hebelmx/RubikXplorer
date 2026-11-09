---
title: 'One LastLayerMatcher and build-failing Algorithm verification'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: 'f3b83ec86a4c8840279703190a77698292634c38'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      Case identity at snapshot time has no committed oracle independent of the Notation under test.
    evidence: |-
      The golden case-signatures.txt was generated from the shipped Notation, so it detects later change but not an Algorithm already filed under the wrong Case when Story 9.1 was authored. That check was a one-off comparison against the numbering sheets. Settling it needs hand-authored recognition data for the Cases, at least the mirror pairs. Pre-existing Story 9.1 data; also relates to the family-membership item deferred there.
    location: >-
      core/domain/src/test/resources/lastlayer/case-signatures.txt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The 78 Algorithms from Story 9.1 parse, but nothing proves they solve their Cases, so a typo reaches the Library unnoticed (FR-18, NFR-4). AD-19 requires one `LastLayerMatcher` that defines Case patterns and AUF for verification, diagrams, 2-look predicates and later Case recognition.

**Approach:** Add `LastLayerMatcher` in `:core:domain` `lastlayer/`, deriving each Case pattern from its primary Algorithm's Notation. Add a JVM verification test over the shipped bundle, which `./gradlew build` runs, so a wrong Algorithm fails the build.

## Boundaries & Constraints

**Always:** Canonical LL frame per AD-19: the LL is the face opposite the solved F2L color, rotated to U, with the LL color passed by the caller. OLL matches by orientation mask under pre-AUF `U^k`. PLL matches by permutation under pre- and post-AUF and `y^k`. A view with unseen stickers returns every Case consistent with what is seen. Domain failures are sealed results, never exceptions or nulls. Notation is read only through `Notation.parse`. Case patterns are derived from Notation, never stored in content (AD-19).

**Never:** No diagrams (9.3), no Library UI (9.4), no 2-look predicates (9.7), no Case recognition or camera work (v1.1), no `FeatureFlags` change. No runtime verification at content load. No change to shipped Notation unless verification proves an entry wrong; such a change must be reported in Design Notes. No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`. No new Gradle task or change to `build-logic`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped Algorithm | Inverse of each of the 78 applied to solved | Candidates are exactly its own Case; applying the Algorithm leaves the LL solved up to AUF; signature equals the golden entry | No error expected |
| Mistyped Algorithm fixture | Shipped content with one token of the T perm changed in `algorithms/pll.json` | Content still loads; verification reports that Algorithm and the gate throws `AssertionError` | Build fails |
| Typo that breaks F2L | Notation that disturbs the first two layers | `LastLayerMatcher.of` returns `Invalid` with `LeavesFirstTwoLayers` | Build fails |
| Typo landing on another Case | Algorithm whose inverse reaches a different Case's pattern | Candidates hold two Cases | Build fails |
| Two Algorithms swapped | Notations of two Cases exchanged | Golden signature mismatch for both | Build fails |
| Case without primary | Case with no `-a` Algorithm | `Invalid` with `NoPrimaryAlgorithm` | Sealed result |
| Bad Notation | Unparseable Notation | `Invalid` with `BadAlgorithmNotation` and the `NotationError` | Sealed result |
| OLL pre-AUF | Case state then `U^k`, k = 0..3 | Same single OLL Case | No error expected |
| PLL AUF and view | Case state with pre-AUF, post-AUF, and `y^k` | Same single PLL Case | No error expected |
| Course frame | Same Case reached with YELLOW on U, read with LL color YELLOW | Same Case as the home-frame reading | No error expected |
| F2L unsolved | Scrambled state | `LastLayerReading.FirstTwoLayersUnsolved` | Sealed result |
| Solved or AUF-only | Solved, or solved then `U^k` | No candidates; `isSolvedUpToAuf` true | No error expected |
| Partial view | Every sticker unseen | All 78 Cases | No error expected |
| Partial view | Only the U stickers seen | Exactly the Cases consistent with them, including the true Case | No error expected |
| Partial view on AUF check | Any sticker unseen | `isSolvedUpToAuf` false | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeState.kt` -- `apply`, `toFacelets`; `copy` is module-internal, so a re-framed state is `state.copy(orientation = ...)`. Cubies are relative to centers, so re-framing never moves pieces.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Facelets.kt:20` -- net layout. With the LL on U: U 0..8; top rows R 9..11, F 18..20, L 36..38, B 45..47.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Orientation.kt` -- `Orientation.ALL`, `of(up, front)`, `colorAt`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Color.kt` -- `Color.home(face)` finds the home face of an LL color.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Move.kt:83` -- `List<Move>.inverse()`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Model.kt:123-157` -- `Case`, `Algorithm.isPrimary`, `CaseSet`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/ContentLoader.kt:13-30` -- `ContentSource` is a `fun interface`, so a fixture overlay wraps `ContentSource.SHIPPED`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt:32` -- how a test loads the shipped bundle.
- `core/domain/src/main/resources/content/algorithms/pll.json` -- source to copy for the mistyped fixture.
- Algorithm Notation assumes the home frame, so the LL of a derived Case is WHITE (U of `CubeState.SOLVED`).
- Read-only: `FeatureFlagLevels.kt`, `content/schema/*`, shipped Algorithm JSON.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerView.kt` -- add `LastLayerView` (20 `Color?` stickers, the LL color, the 4-color side ring), `LastLayerView.of(state, lastLayer): LastLayerReading`, a public partial-view factory, and sealed `LastLayerReading` (`View` / `FirstTwoLayersUnsolved`) -- the canonical frame shared by matching and Story 9.3.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt` -- add `LastLayerMatcher.of(cases, algorithms)` returning sealed `Built` / `Invalid(problems)`, `candidates(view): Set<CaseId>`, `isSolvedUpToAuf(view)`, and a derivation of an Algorithm's Case state; sealed `LastLayerProblem` -- the one definition of Case patterns and AUF.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcherTest.kt` -- one test per matcher row of the I/O matrix -- behavior of the frame, AUF, views and partial sets.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/AlgorithmVerification.kt` -- add the gate: `problems(cases, algorithms)` and `assertVerified(bundle)`, which throws `AssertionError` listing each failing Algorithm; computes the color-neutral signature (minimum encoding over the orbit) -- one code path for the shipped test and the fixture test.
- `core/domain/src/test/resources/lastlayer/case-signatures.txt` -- golden `caseId signature` line for each of the 78 -- pins which pattern belongs to which Case, so swaps are caught.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/ShippedAlgorithmVerificationTest.kt` -- `assertVerified` on the shipped bundle -- the build gate.
- `core/domain/src/test/resources/content-fixtures/mistyped-algorithm/algorithms/pll.json` -- copy of shipped `pll.json` with exactly one token of `alg-pll-t-a` changed, the Notation still parsing -- the deliberately mistyped fixture.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/MistypedAlgorithmFixtureTest.kt` -- load the fixture over `ContentSource.SHIPPED`, assert it loads, assert `assertVerified` throws naming `alg-pll-t-a`; programmatic rows for F2L break, collision and swap -- proves the gate has teeth.

**Acceptance Criteria:**
- Given `LastLayerMatcher` and the shipped bundle, when each Algorithm's inverse is applied to solved, then its state matches its own Case and no other, and applying the Algorithm solves the LL up to AUF.
- Given the deliberately mistyped Algorithm fixture, when the verification test runs, then it fails with an `AssertionError` naming that Algorithm, which fails `./gradlew build`.
- Given shipped `algorithms/*.json` with one token mistyped, when `./gradlew :core:domain:test` runs, then the task fails; restoring the file makes it pass.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 26 findings — high 0, medium 7, low 15, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: dropped leading rotation passes the gate — confirmed by reading `LastLayerView.of`, which re-frames orientation away; the gate now reports `ends in a net rotation` when the derived orientation is not `Orientation.HOME`, pinned by a new test.
  - `[low]` `[reject]` Blind: the solve check cannot fail — true and acknowledged in Design Notes; the AC asks for it literally, and a state independent of the Notation needs an oracle this story does not have.
  - `[medium]` `[defer]` Blind: the golden file has no independent oracle — it pins today's assignments only; recorded in frontmatter `deferred`.
  - `[low]` `[reject]` Blind: `of` returns `Built` for degenerate Algorithms — reachable only with content the gate rejects through the golden check; a guard adds public surface for inputs no shipped bundle has.
  - `[medium]` `[patch]` Blind: `signature` re-implements encode and orbit — AD-19 names the verification test as a site that must not define patterns separately; moved to an `internal` matcher function, golden file unchanged.
  - `[low]` `[reject]` Blind: `goldenSignatures` does not validate the file — a missing key already fails as a mismatch, and the candidate check catches duplicate patterns; a stale extra line is harmless.
  - `[low]` `[reject]` Blind: the file fixture covers only the F2L branch — the other branches have programmatic rows; exhaustive token mutation is new test machinery.
  - `[low]` `[reject]` Blind: non-white, non-yellow last layers under-tested — the Course uses YELLOW, the Library WHITE, and no caller passes another color in v1.0.
  - `[low]` `[reject]` Blind: `partial` handles bad input inconsistently — no production caller until v1.1; `require` matches `Facelets` and `Orientation.of` for programmer errors.
  - `[low]` `[patch]` Blind: partial-view tests never exercise ring labels with unseen stickers — added the all-seen and side-sticker mask tests.
  - `[false]` `[reject]` Blind: `epic-9-context.md` is unrelated — it is this workflow's own recompiled epic context, stale after the 9.1 memlog entry.
  - `[low]` `[reject]` Edge: the does-not-solve branch cannot fire — same as the Blind solve-check row.
  - `[low]` `[reject]` Edge: a partial sticker in the opposite color is treated inconsistently — same as the Blind `partial` row.
  - `[false]` `[reject]` Edge: duplicate `-a` Algorithms reach `associateBy` — `ContentValidator` rejects duplicate Algorithm IDs and the matcher only receives loaded bundles.
  - `[false]` `[reject]` Edge: duplicate IDs make the `Built` cast throw — the same validator guard makes this unreachable.
  - `[medium]` `[patch]` Edge: `alg-pll-v-a` with trailing `y` passes — same root cause as the dropped rotation row; fixed by the same check and pinned in the same test.
  - `[low]` `[patch]` Verification gap: partial views with side stickers seen have no test — same fix as the Blind partial-view row.
  - `[low]` `[patch]` Verification gap: aggregation in `of` not pinned — added a two-Algorithm test asserting both `Invalid.problems` and `AlgorithmVerification.problems`.
  - `[medium]` `[defer]` Verification gap: Case identity is self-derived — same deferred entry as the Blind golden row.
  - `[low]` `[reject]` Intent: AC 2 lives at the build outcome while the fixture test lives at the assertion — a permanently failing fixture would keep every build red; the real mutation run observed the failing build, recorded below.
  - `[low]` `[reject]` Intent: the file fixture exercises one gate branch — same as the Blind fixture row.
  - `[medium]` `[defer]` Intent: "proven correct" is self-consistency and stability — same deferred entry as the Blind golden row.
  - `[low]` `[reject]` Intent: the solve clause cannot fail — same as the Blind solve-check row.
  - `[medium]` `[patch]` Intent: test-side `signature` is a second pattern definition — same fix as the Blind signature row; the test-resource golden itself keeps shipped content derived.
  - `[low]` `[patch]` Intent: partial views covered on two shapes only — covered by the added all-seen and side-sticker mask tests.
  - `[false]` `[reject]` Intent: the `epic-9-context.md` edit is outside the story — same as the Blind epic-context row.

## Design Notes

**Layout of the 20 stickers.** Four blocks of five, in the order F, R, B, L. Each block is: U edge sticker, U corner sticker clockwise of it, then that side's top row. `U^k` is then a cyclic shift by `5k`.

**PLL orbit.** Label each side sticker by its index in the ring. Pre-AUF shifts positions and post-AUF shifts labels; `y^k` is one of each. So a Case's orbit is every (position shift, label shift) pair, 16 in all.

**Golden provenance.** Generate `case-signatures.txt` once from the shipped Notation, which Story 9.1 checked against the numbering sheets, and inspect it for 78 lines with distinct signatures. Self-derived patterns alone cannot catch a swap of two Algorithms; the pinned file does, and it catches any later edit.

**Why the solve check is not the proof.** Applying an Algorithm after its own inverse always returns to solved, so that assertion is required by the AC but proves little. The real teeth are F2L preservation, the single-Case match and the golden signature.

**The build surface.** The fixture test proves the gate rejects a mistyped entry using the same code path as the shipped test. The third AC is verified by a one-off real mutation of shipped content, recorded in the Auto Run Result.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `:core:domain` tests and Kover.
- With one token of `core/domain/src/main/resources/content/algorithms/oll.json` changed: `./gradlew :core:domain:test --tests '*ShippedAlgorithmVerificationTest*'` -- expected: FAILED naming that Algorithm. Then `git checkout -- core/domain/src/main/resources/content/algorithms/oll.json`.

## Auto Run Result

**Summary.** `LastLayerMatcher` and `LastLayerView` now live in `:core:domain` `lastlayer/`, deriving Case patterns from each primary Algorithm's Notation. A JVM verification gate over the shipped bundle fails `./gradlew build` for a mistyped Algorithm. All 78 shipped Algorithms pass; no shipped Notation changed.

**Files changed.**
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerView.kt` -- canonical 20-sticker frame, partial views, sealed `LastLayerReading`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt` -- derived patterns, `candidates`, `isSolvedUpToAuf`, `caseState`, internal `signature`, sealed results.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/AlgorithmVerification.kt` -- the gate.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/ShippedAlgorithmVerificationTest.kt` -- runs the gate on shipped content.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/MistypedAlgorithmFixtureTest.kt` -- proves the gate rejects wrong Algorithms.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcherTest.kt` -- frame, AUF, views and partial sets.
- `core/domain/src/test/resources/lastlayer/case-signatures.txt` -- golden signature per Case.
- `core/domain/src/test/resources/content-fixtures/mistyped-algorithm/algorithms/pll.json` -- the mistyped fixture (`alg-pll-t-a`, last `F'` changed to `F`).
- `_bmad-output/implementation-artifacts/epic-9-context.md` -- recompiled by this workflow.

**Review.** 26 findings. Patches applied: the net-rotation gate check (medium), one signature definition in the matcher (medium), partial-view and aggregation tests (low). Deferred: one entry, the independent Case-identity oracle (medium). Every rejected finding and its reason is in the Review Triage Log.

**Follow-up review: recommended.** Patched by verdict: high 0, medium 2, low 2. Specific unverified risk: review alone found the net-rotation typo class, and no exhaustive single-token mutation of the 78 Algorithms has been run, so other single-token typos that pass the gate remain unverified.

**Verification.** After the patches, `./gradlew spotlessCheck build` was BUILD SUCCESSFUL. JUnit reports: `LastLayerMatcherTest` 18, `MistypedAlgorithmFixtureTest` 8, `ShippedAlgorithmVerificationTest` 1, no failures or skips. Real mutation (AC 3), run after the patches: changing `alg-oll-27-a` in the shipped `oll.json` to `R U R U R U2 R'` made `:core:domain:test` report `BUILD FAILED` with `alg-oll-27-a: disturbs the first two layers`; the file was restored by `git checkout` and shows no diff. No instrumented or device runs.

**Residual risks.** The golden file pins Case assignments as of Story 9.1 and cannot catch an error already present then. The solve check is required by the AC but cannot fail on its own, as Design Notes state. The gate rejects any future Algorithm that deliberately ends in a net whole-cube rotation.
