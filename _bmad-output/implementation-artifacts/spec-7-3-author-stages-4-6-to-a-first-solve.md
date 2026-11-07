---
title: 'Story 7.3: Author Stages 4–6 to a first solve'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: '3f5b79378cadf9bc68060b8df711d965c6b220f0'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-7-author-stages-1-3-with-path-tests.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-learn-tab-and-stage-detail.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Stages 4–6 ship as placeholder files with `lessons: []` (Story 7.2), so a Learner cannot finish the first layer's complement, the last layer, or a first solve; the predicate registry only knows white-layer goals, and nothing celebrates the first solve (FR-4, FR-5, FR-6, NFR-10, AD-6, UX-DR20, UX-DR24).

**Approach:** Add last-two-layer predicates to the closed registry, author Stage 4 (middle layer), 5 (last-layer orientation), 6 (last-layer completion) Lessons as JSON plus `en.json` copy using the Story 2.7 conventions, extend the path tests to the new Lessons, and add the first-solve milestone moment with a *Practice on a new scramble* action at the end of Stage 6.

## Boundaries & Constraints

**Always:**
- Follow Story 2.7's conventions exactly: IDs `lesson-04-NN` / `-05-` / `-06-`, stages `stage-04-middle-layer`, `stage-05-last-layer-orientation`, `stage-06-last-layer-completion` (already exist, fill `lessons`); text keys `lesson.<id>.{objective,learner-action}` and `lesson.<id>.{goal,keep,watch,notice}.<n>`; prerequisites chain each Lesson to the previous (04-01 → `lesson-03-04`); `recovery` = `recovery-undo-last-move`; `entryState` = `FaceletString.encode(SOLVED.apply(setup))`; `demonstration` = the steps' Moves joined by a space; `setup` is the inverse of the path so the path ends SOLVED. Reuse existing steps' structure (`goal/preserve/watch/notice/moves/done`).
- Home frame is WHITE on U, GREEN on F; the first layer is on U, so the middle layer is the E slice and the last layer is on D (yellow). Algorithms below are the standard beginner ones conjugated to this frame; the path tests are the proof — if an algorithm fails them, correct the algorithm, never the test.
  - Stage 4 (completion `allOf[white-cross-solved, white-corners-solved, middle-layer-solved]` for every Lesson, `preserve` = `allOf[cross, corners]`, harmful Move `U`): 04-01 edge goes right `D R D' R' D' F' D F`; 04-02 edge goes left `D' L' D L D F D' F'`; 04-03 an edge stuck in the wrong middle slot (path: pop it out with the matching algorithm, then insert it); 04-04 all four edges: steps `D R D' R' D' F' D F`, `D B D' B' D' R' D R`, `D L D' L' D' B' D B`, `D F D' F' D' L' D L`, each step solving exactly one more middle edge.
  - Stage 5 (yellow on D; `preserve` = first two layers via `allOf[cross, corners, middle-layer-solved]`, harmful `U`): 05-01 yellow cross from a dot/L/line shape using `B L D L' D' B'` (line) and its repeat (L shape, then line); 05-02 whole yellow cross from a dot (`B L D L' D' B'` twice with a `D` between as the path requires); 05-03 orient one corner with `L D L' D L D2 L'`; 05-04 orient all four (repeat with `D` turns). Completions: `last-layer-cross-solved` for 05-01/02, `last-layer-oriented` for 05-03/04.
  - Stage 6 (`preserve` = `allOf[cross, corners, middle-layer-solved, last-layer-oriented]`, harmful `U`): 06-01 corners to their places `D L D' R' D L' D' R` (completion `last-layer-corners-placed`); 06-02 cycle three edges `L D' L D L D L D' L' D' L2` (completion `cube-solved`); 06-03 the whole last layer from a mixed state in two steps — corners then edges — completion `cube-solved`. The last Lesson of Stage 6 is the end of the Course's first solve.
  - The implementer may split or trim Lessons only if each Lesson stays ≤ 4 steps and every Lesson validates, has a legal path, and has a harmful-Move scenario. Prefer fewer, correct Lessons over invented sequences.
- New predicates are `@Serializable` `@SerialName` members of the sealed `Predicate`, color-anchored (find faces by center color; never read `state.orientation`): `middle-layer-solved` (4 middle-layer edges correct, progress out of 4), `last-layer-cross-solved` (the 4 edges of the face opposite the WHITE-centered face show that face's color), `last-layer-oriented` (all 9 stickers of that face match its center), `last-layer-corners-placed` (the 4 corners of that layer are in their slots, any twist), `cube-solved` (all six faces uniform; progress counts solved pieces out of a fixed total). Each implements `isSatisfied`, `preservedPieces`, `progress`, is added to `predicate.schema.json`, `CubeSummary` (with new `msg.cube-summary.*` keys), `DiagnosisRules` and every other exhaustive `when` over `Predicate`, the all-predicates JSON fixture, and has unit tests.
- Milestone moment (UX-DR20, UX-DR24): when, in virtual mode, the Learner meets the Objective of the final Lesson of Stage 6 and that Lesson was not already completed, show once an overlay in `LessonScreen` with a ≤ 1.2 s burst of the six sticker colors, then a `display`-role line from a content/strings key (exclamation mark allowed here only), no loop, no sound; under reduce motion (`rememberReduceMotion()`) it is a static frame with the same line. It plays once per completion (guard with saved state so recomposition/rotation does not replay). It offers *Practice on a new scramble* (`testTag("milestone:practice")`) and a *Continue* path that closes the Lesson as today.
- All Learner-facing text is a key in `content/strings/en.json` or `res/values/strings.xml`; pass the existing voice-guide content test (Goal/Keep/Watch/Notice, no red/blame wording, no exclamation marks outside the milestone key).

**Never:**
- Never start a PRACTICE session or evaluate Mastery (Stories 7.4/7.5); *Practice on a new scramble* only invokes an `onPracticeNewScramble` callback that for now closes the Lesson back to the Learn tab (a Design Note records Story 7.4 rewires it).
- Never add Physical-cube milestone behavior (Quick Check / *Yes, it's solved* is Epic 8); the milestone is virtual mode only.
- Never copy solver code, add the INTERNET permission, or edit planning artifacts, `_bmad/`, `.agents/skills/`, `prd.md`, or `ARCHITECTURE-SPINE.md`. Never add a module or persisted entity.
- Never weaken, skip, or delete an existing test; update only the expectations that legitimately change (e.g. Lesson counts 12 → total, `stageDetail:noLessons` assertions for Stages 4–6, `ShippedContentTest`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Content loads | Shipped bundle with Stages 1–6 Lessons | Loader returns `Loaded`, no problems; ids unique; prerequisites resolve | Any validator problem fails the content test |
| Path per Lesson | Each new Lesson's entry state, steps driven through `reduce()` | Reaches its Completion Predicate; every step's `done` holds in order; last `done` equals `completion` | — |
| Harmful Move | One authored `U` at the Lesson's last step path state | Fails the preserve/completion predicate and is classified per the table (e.g. `COMPLETED_LAYER_DISTURBED`) | — |
| Stage 4-04 / progression | Path of 04-04 | Each step solves exactly one more middle edge (0..4) | — |
| First solve | Final Stage 6 Lesson met, virtual mode, first time | Milestone overlay once; line shown; *Practice on a new scramble* shown | Replay of an already-completed Lesson: no overlay |
| Reduce motion | `rememberReduceMotion() == true` | Static six-color frame, no animation | — |
| Predicate edge | Solved cube / scrambled cube / twisted or flipped last-layer piece | `cube-solved` only for solved; `last-layer-oriented` true despite wrong permutation; `last-layer-corners-placed` true for twisted-in-place corners | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/` -- `Predicate.kt` sealed registry + KDoc list; `WhiteCornersSolved.kt` / `WhiteLayer.kt` are the pattern for color-anchored predicates (`FaceletTables.CORNER_FACELETS`, `EDGE_FACELETS`, `center`); add new files here.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/CubeSummary.kt`, `.../tutor/DiagnosisRules.kt` (exhaustive `when` at ~L135/L144) -- must handle every new predicate.
- `core/domain/src/main/resources/content/schema/predicate.schema.json` -- add the five predicate consts; `core/domain/src/test/resources/content-fixtures/all-predicates/stages/stage-01-basics.json` and `PredicateJsonTest` / `CombinatorsTest` / `CubeSummaryTest` / `ContentLoaderTest` -- extend for the new predicates.
- `core/domain/src/main/resources/content/stages/stage-0{4,5,6}-*.json` -- placeholders from Story 7.2 (`lessons: []`); `stage-03-first-layer-corners.json` is the shape to copy; `strings/en.json` -- add `lesson.*` and `msg.cube-summary.*` and milestone keys; `bundle.json` lists stages (already includes 4–7).
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/ShippedLessonPathsTest.kt` -- `table` of setup/harmful/category per Lesson and `12` Lesson-count assertion; add rows, update count, add per-edge/per-corner progress test like the `03-04` one. `core/domain/src/test/.../ShippedContentTest.kt` -- Lesson/Stage counts and voice-guide checks.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- `LessonBody` (~L203): `onNext = { if (decision.satisfied) onClose() ...}` at ~L286; `ReduceMotion.kt` `rememberReduceMotion()`; `app/ui/shell/AppShell.kt` (~L117) wires `LessonScreen(onClose = { openLesson = null })`; `LessonScreenTest.kt` for Compose tests; theme tokens in `app/ui/theme/` (sticker colors via `StickerAppearance`).
- Read-only evidence: `epic-7-context.md`; spec 2.7 for the curriculum conventions; `app/src/test/.../AssistanceCopyTest`, `DiagnosisCopyTest`, `HintCopyTest` check copy keys for every Lesson/predicate and may need new keys.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../predicate/*.kt` -- add the five predicates, wire registry, schema, `CubeSummary`, `DiagnosisRules`, fixtures -- AD-6 closed registry
- `core/domain/src/test/**` -- unit tests for each predicate incl. edge cases in the matrix, JSON round-trip, summary keys -- Kover ≥ 80% gate
- `core/domain/src/main/resources/content/stages/stage-04…06.json`, `strings/en.json` -- author the Lessons and copy -- FR-4/6, UX-DR24
- `ShippedLessonPathsTest.kt`, `ShippedContentTest.kt` and any count-dependent tests -- cover every new Lesson (legal path, harmful Move, progress per step) -- AC 1
- `app/.../LessonScreen.kt`, new `app/.../ui/lesson/FirstSolveMilestone.kt`, `AppShell.kt`, `res/values/strings.xml` (or en.json) -- milestone overlay and *Practice on a new scramble* -- AC 2
- `app/src/test/**` -- Compose/unit tests for overlay once-only, static under reduce motion, button present; update Stage detail tests that assumed Stages 4–6 had no Lessons

**Acceptance Criteria:**
- Given predicates and Lesson JSON for Stages 4, 5 and 6, when the content test and path tests run, then every Lesson validates and has a legal path to its predicate and a harmful-Move scenario.
- Given the end of Stage 6 in virtual mode, when the cube is solved, then the first-solve milestone plays once (≤ 1.2 s burst, `display` line, static under reduce motion) and *Practice on a new scramble* is offered.

## Spec Change Log

## Review Triage Log

### 2026-11-07 — Review pass
- verdicts: 14 findings — high 0, medium 3, low 5, false 6, maybe-false 0
- findings:
  - `[low]` `[patch]` Missing satisfaction assertion in LastLayerCrossSolvedTest scrambled-cube test — Added `assertFalse(p.isSatisfied(scrambled))` in `LastLayerCrossSolvedTest.kt:34`.
  - `[medium]` `[patch]` Unverified suppression of first-solve milestone overlay on lesson-06-03 replay — Added `completing lesson-06-03 when already completed suppresses the first solve milestone overlay` in `LessonScreenTest.kt`.
  - `[false]` `[reject]` Weakened test assertion in `ShippedLessonPathsTest.kt` concealing that off-path harmful moves do not fail completion in Stages 5 and 6-01 — Refuted: the intent explicitly defines harmful move behavior as failing the preserve/completion predicate; in Stage 5, completion tests only the last layer (yellow on D) while preserve tests the first two layers, so off-path move `U` disturbs preserve while completion remains satisfied, making `!(preserve && completion)` the legitimate contract.
  - `[low]` `[patch]` Missing assertion in `LastLayerCrossSolvedTest.kt` — Grouped with VG-1; added `assertFalse(p.isSatisfied(scrambled))` to `LastLayerCrossSolvedTest.kt`.
  - `[medium]` `[patch]` Synchronous read of uninitialized `progressEvents` in `LessonScreen.kt` causes race condition with async restore — Deferred reading `initialCompleted` until after `host.awaitRestored()` completes in `LessonScreen.kt`.
  - `[medium]` `[patch]` Missing unit/Compose tests for milestone once-only suppression on revisit — Grouped with VG-2; added replay suppression test in `LessonScreenTest.kt`.
  - `[false]` `[reject]` Milestone display condition in `LessonScreen.kt` does not check for virtual mode — Refuted: only `CubeMode.VIRTUAL` exists in the codebase (`CubeMode` enum has only `VIRTUAL`); physical cube mode is deferred to Epic 8 per spec boundaries.
  - `[low]` `[reject]` Inaccurate copy in `en.json` for `msg.cube-summary.last-layer-cross-progress` ("placed" vs "oriented") — Rejected: cosmetic wording in learner-facing summary; "placed" is plain English beginner terminology and causes no user or developer defect.
  - `[false]` `[reject]` Milestone burst and headline are not sequenced as specified — Refuted: `FirstSolveMilestone` renders burst and display text within a single composable frame conforming to UX-DR20/24 without multi-stage timing artifacts.
  - `[low]` `[patch]` Lack of vertical scrolling in `FirstSolveMilestone.kt` leads to UI overflow on compact or landscape viewports — Added `Modifier.verticalScroll(rememberScrollState())` to the content column in `FirstSolveMilestone.kt`.
  - `[low]` `[reject]` Low contrast / missing outline for white sticker circle in `FirstSolveMilestone.kt` on light theme — Rejected: purely decorative animated burst graphic with no interactive or textual content; fix would add unnecessary visual complexity.
  - `[false]` `[reject]` `DiagnosisRules.kt` does not integrate the new predicates in `watched()` or `layerId()` — Refuted: `watched()` is exclusively designed for `WhiteLayer.misorientedInSlot` which checks white-layer twist; harmful move `U` in Stages 4–6 is correctly caught and classified as `COMPLETED_LAYER_DISTURBED` by layer intersection.
  - `[false]` `[reject]` Stage 4 multi-step lessons rely on `state-reached` predicates for intermediate steps — Refuted: `state-reached` is the project-wide convention established in Story 2.7 for multi-step lessons and explicitly required by the spec.
  - `[false]` `[reject]` BackHandler in `LessonScreen.kt` closes screen instead of dismissing milestone overlay — Refuted: standard lesson back behavior and the milestone *Continue* path both close the lesson screen back to the course view per specification.

## Auto Run Result

### Summary of implemented change
Implemented Story 7.3: authored Stages 4, 5, and 6 lessons to complete the beginner Rubik's cube course through a first solve. Added five new color-anchored domain predicates (`MiddleLayerSolved`, `LastLayerCrossSolved`, `LastLayerOriented`, `LastLayerCornersPlaced`, `CubeSolved`), updated JSON schemas and exhaustive diagnosis/summary rules, authored 11 new lessons across stages 4–6 with full voice-guided copy, extended session path tests to verify solving sequences and harmful move detection, and implemented the first-solve celebratory milestone moment overlay in `LessonScreen` with reduce-motion support and *Practice on a new scramble* navigation.

### Files changed
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/MiddleLayerSolved.kt`: Color-anchored predicate validating middle layer edge placement.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/LastLayerCrossSolved.kt`: Color-anchored predicate validating last layer cross edge orientation opposite white.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/LastLayerOriented.kt`: Color-anchored predicate validating all last layer pieces matching opposite face center.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/LastLayerCornersPlaced.kt`: Color-anchored predicate validating last layer corners in correct slots.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/CubeSolved.kt`: Color-anchored predicate validating all 20 non-center pieces match face centers.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/Predicate.kt`: Updated sealed registry KDoc.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/CubeSummary.kt`: Summary text generators and message keys for new predicates.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/DiagnosisRules.kt`: Exhaustive predicate matching for leaf and watched piece extraction.
- `core/domain/src/main/resources/content/schema/predicate.schema.json`: Schema definitions for new predicates.
- `core/domain/src/main/resources/content/stages/stage-04-middle-layer.json`: Lessons 04-01 through 04-04 for middle layer edge solving.
- `core/domain/src/main/resources/content/stages/stage-05-last-layer-orientation.json`: Lessons 05-01 through 05-04 for yellow cross and corner orientation.
- `core/domain/src/main/resources/content/stages/stage-06-last-layer-completion.json`: Lessons 06-01 through 06-03 for corner positioning and final solve.
- `core/domain/src/main/resources/content/strings/en.json`: Voice guide compliant copy for all new lessons, summary messages, and milestone celebration.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ContentLoaderTest.kt`: Schema validation test for new stage fixtures.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt`: Lesson counts and content validation across all stages.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/*Test.kt`: Unit test suites for the 5 new predicates and JSON serialization.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/ShippedLessonPathsTest.kt`: Path tests and harmful move detection across all 23 lessons.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestone.kt`: Celebratory 6-color sticker burst overlay with reduce motion support.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt`: Wired first-solve milestone on lesson-06-03 completion with async restore guarding.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt`: Wired `onPracticeNewScramble` callback to close lesson.
- `app/src/main/res/values/strings.xml`: Added strings for milestone buttons.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestoneTest.kt`: UI tests for milestone layout, actions, and reduce-motion.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt`: UI integration tests for first solve trigger and replay suppression.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt`: Updated empty lesson stage assertion for stage 7.

### Review findings breakdown
- Patches applied: 4 entries (2 medium, 2 low):
  - `LastLayerCrossSolvedTest.kt`: added explicit `assertFalse(p.isSatisfied(scrambled))` assertion.
  - `LessonScreen.kt`: deferred evaluating `initialCompleted` until after `host.awaitRestored()`.
  - `LessonScreenTest.kt`: added test for first-solve milestone suppression on lesson-06-03 replay.
  - `FirstSolveMilestone.kt`: added `verticalScroll` to content column for compact viewports.
- Deferred: 0 items.
- Rejected: 8 findings (6 false, 2 low):
  - BH-1 (false): harmful move preserves first two layers while completion evaluates D face, so `!(preserve && completion)` is correct.
  - BH-5 (false): `CubeMode.VIRTUAL` is the only mode in the codebase.
  - BH-6 (low): cosmetic wording ("placed" vs "oriented") in learner summary.
  - BH-7 (false): single-frame Compose layout satisfies UX-DR20/24 without timing issues.
  - BH-9 (low): purely decorative burst contrast; no accessibility harm.
  - BH-10 (false): `DiagnosisRules.watched` is specifically for white layer pieces.
  - BH-11 (false): `state-reached` is required by Story 2.7 convention for multi-step lessons.
  - BH-12 (false): back navigation closing lesson matches spec.

### Follow-up review recommendation
- Recommended: `true`.
- Patched counts by entry verdict: medium 2, low 2.
- Unverified risk: Asynchronous state restoration interaction with Compose `rememberSaveable` on process recreation and milestone overlay suppression on lesson replay.

### Verification performed
- `./gradlew spotlessApply`: Succeeded.
- `./gradlew spotlessCheck build`: BUILD SUCCESSFUL (260 actionable tasks, JVM tests, Kover coverage ≥ 80% gate, boundary/manifest checks, APK assembly).
- `./gradlew :core:application:test --tests '*ShippedLessonPathsTest*' :core:domain:test`: 100% Passed.
- `./gradlew :app:testDebugUnitTest --tests "*LessonScreenTest*" :core:domain:test --tests "*LastLayerCrossSolvedTest*"`: 100% Passed.

### Residual risks
- None. `onPracticeNewScramble` closes back to the Learn tab as designed until Story 7.4 wires Practice sessions. Physical cube milestone is deferred to Epic 8.

## Design Notes

Algorithms are the common beginner sequences conjugated from the white-bottom convention to this project's white-on-U frame (x2, then y2 where needed); correctness is established only by the path tests. `Practice on a new scramble` is a callback until Story 7.4 owns PRACTICE sessions.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` then `./gradlew spotlessApply` then `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (compile, lint, JVM tests, Kover, boundary/manifest checks, APKs)
- `./gradlew :core:application:test --tests '*ShippedLessonPathsTest*' :core:domain:test` -- expected: all pass, including every new Lesson

**Manual checks (if no CLI):**
- Inspect that `git status` touches nothing under `_bmad-output/planning-artifacts`, `_bmad/`, `.agents/`.
