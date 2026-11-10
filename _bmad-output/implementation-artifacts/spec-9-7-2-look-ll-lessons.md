---
title: '2-look LL Lessons'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: 'fe3992c12d6518a159745c9cbf77d1e762c0fee7'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A Learner who can already solve has no 2-look last layer: *Faster last layer* is an inert placeholder row, no 2-look content exists, and `SessionKind.TWO_LOOK` is never used (FR-17).

**Approach:** Add 16 2-look Lessons (3 + 7 OLL, 2 + 4 PLL) as content that names Library Algorithm IDs and carries no Notation; resolve them at load into ordinary `Lesson`s whose entry state is the Case state derived from the Algorithm. Judge them with one new LL-step predicate built on `LastLayerMatcher`, run them as `TWO_LOOK` sessions through the existing reducer, and end each with the "Add these algorithms to Learning?" offer.

## Boundaries & Constraints

**Always:** 2-look content names Algorithms by `AlgorithmId` only; Notation and entry state are derived at load from the Library, so the two cannot diverge. The 2-look set is decided here (OQ-2): edges *dot* `alg-oll-02-a`, *line* `alg-oll-45-a`, *L* `alg-oll-44-a`; corners `alg-oll-21-a` to `alg-oll-27-a`; PLL corners `alg-pll-t-a`, `alg-pll-y-a`; PLL edges `alg-pll-ua-a`, `alg-pll-ub-a`, `alg-pll-h-a`, `alg-pll-z-a`. Each Lesson's `completion` is a `last-layer-step` predicate, color-anchored and orientation-invariant, read through `LastLayerView` and `LastLayerMatcher`. Each Lesson's `preserve` is the first two layers. 2-look Stages stay outside `bundle.stages`, so Course status, the seven Stage rows and skip-ahead are unchanged. Both 2-look rows unlock together, exactly when the Course is finished (`allStagesFinished`, the rule the Learn tab already uses), and each row's Lessons are all open. A `TWO_LOOK` session takes Moves, hints, Teaching Modes, attempts and Guided recovery exactly as `LESSON` does, and resumes after process death from its snapshot. The offer appears once the Objective is met and only for that Lesson's Algorithms still *Not started*; accepting calls `SetAlgorithmStatus` (via `SessionHost.setAlgorithmStatus`) with *Learning* for each; declining writes nothing; ignoring it and pressing *Next* writes nothing. Learner text is content strings or Android resources, sets an ink color, and follows the voice guide.

**Never:** No Notation in 2-look JSON. No second Case or AUF definition outside `LastLayerMatcher`. No automatic Algorithm Status change, and no demoting an Algorithm already *Learning* or *Learned*. No 2-look *Completed*/*Mastered* mini-Course rule, "Mastery: 1 of 2" line, LL practice or 2-look milestone (9.8); the Stage mastery line, Trend, *Practice this Stage*, *I already know this* and the Stage mastery milestone do not appear for 2-look. No Checkpoints for `TWO_LOOK`; `Checkpoint` stays `LESSON` only. No Room schema change. No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Content loads | Shipped bundle | 16 resolved Lessons in 2 Stages; `demonstration` and `steps[].moves` equal the Library Notation; Course Stages unchanged | No error expected |
| Unknown Algorithm | Lesson step names an absent `AlgorithmId` | `ContentLoadError.Invalid` with `UnknownAlgorithm` | Sealed problem |
| Legal path | Each Lesson | Entry state valid, F2L solved, `preserve` holds, `completion` unmet; steps' Moves reach `completion` with `preserve` holding | Content test |
| Predicate frame | Same cube with white on U, yellow on U, after `y` | Same result | No error expected |
| F2L broken | LL goal met but a first-two-layers piece is out | Unsatisfied, progress 0 | No error expected |
| Corners permuted | Oriented LL, corners right, edges cycled | `corners-permuted` satisfied, `solved-up-to-auf` not | No error expected |
| Up to AUF | Solved LL turned by `U` | `solved-up-to-auf` satisfied | No error expected |
| Course unfinished | Any Stage still locked or in progress | No 2-look rows; Library link still shows the locked note | No error expected |
| Course skipped | Skip-ahead passed from Welcome | Two 2-look rows appear, *In progress* | No error expected |
| Open a row | Tap *2-look OLL* | Stage detail lists its Lessons, all open; no mastery line, Trend, Practice or Skip | No error expected |
| Run a Lesson | Tap a Lesson | `TWO_LOOK` session; recognition diagram, Teaching Modes and hints show | No error expected |
| Accept offer | Objective met, Algorithm *Not started*, tap *Add to Learning* | Status becomes *Learning*; offer disappears; Library tile shows it | No error expected |
| Decline offer | Tap *Not now* | No status event; offer disappears | No error expected |
| Already in set | Every Lesson Algorithm *Learning* or *Learned* | No offer | No error expected |
| Resume | Process death mid-Lesson | Session restored as `TWO_LOOK` on the same Lesson | No error expected |
| Course Lesson | Any Course Lesson | Still `LESSON`, no diagram, no offer | No error expected |

</intent-contract>

## Code Map

- `core/domain/.../lastlayer/LastLayerMatcher.kt` -- companion owns patterns and AUF; `caseState(algorithm, lastLayer)` derives Case states; `isSolvedUpToAuf` is the existing whole-LL check.
- `core/domain/.../lastlayer/LastLayerView.kt` -- `of(state, lastLayer)` returns `FirstTwoLayersUnsolved` or the 20 stickers; each block is U edge, U corner, then that side's top row; `ring` holds the side centers.
- `core/domain/.../lastlayer/RecognitionDiagram.kt` -- `derive(algorithm)` with yellow LL, as the Library shows it.
- `core/domain/.../predicate/Predicate.kt` -- sealed registry; `CubeSummary.kt` and `tutor/DiagnosisRules.kt:136-162` have exhaustive `when`s over it.
- `core/domain/.../content/Model.kt` -- `BundleManifest`, `Stage`, `Lesson`, `Step`, `ContentBundle`; `ContentLoader.kt:decode`; `ContentValidator.kt:validate` and `lessonProblems`; `ContentProblem.kt`.
- `core/domain/src/main/resources/content/schema/` -- one schema per file shape; `SchemaModelTest` compares them to the Kotlin descriptors (`required` must match non-default fields).
- `core/domain/.../tutor/Tutor.kt`, `StageProgress.kt` -- `currentStep` compares `CubeState` with orientation, so the entry frame must be kept; `status(stage, listOf(stage), events)` makes a Stage its own unlocked root.
- `core/application/.../session/SessionKind.kt`; `Reduce.kt:49,98,121,256,974,1030,1107-1108` kind gates; `SessionState.kt:209`; `SessionEvent.kt:46` `LessonStarted`; `SessionSnapshot.kt:56`.
- `app/.../content/LessonCatalog.kt` -- `Ready.lessons`, `find`.
- `app/.../session/SessionHost.kt` -- `restoreLatest` lesson lookup; `dispatch` `completeLesson` gate on `kind == LESSON`; `setAlgorithmStatus`.
- `app/.../ui/shell/LearnScreen.kt` -- `StageListContent`, `FasterLastLayerRow`, `StageRow`, `StageDetailScreen`; `AppShell.kt` -- `openLesson` branch, `continueEntry`, `showFasterLastLayer`.
- `app/.../ui/lesson/LessonScreen.kt:167-262` start and milestone logic, `LessonBody`; `LessonPanel.kt:112-160`; `app/.../ui/library/RecognitionDiagramView.kt`.
- `app/src/test/.../ui/shell/AppShellTest.kt:457` -- the placeholder row's click test, replaced here; `core/domain/src/test/.../content/ShippedContentTest.kt:247,264` -- key-slot and used-string checks to extend.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../lastlayer/LastLayerStep.kt` -- `@Serializable enum` with serial names `edges-oriented` (4 pieces), `oriented` (8), `corners-permuted` (4), `solved-up-to-auf` (8) -- the four 2-look goals.
- `core/domain/.../lastlayer/LastLayerMatcher.kt` -- add companion `stepPieces(view, step): List<Set<Color>>`, the colors of each piece the step counts as done, taking the best AUF for the two permutation steps; add `caseState(notation, lastLayer)` and delegate the `Algorithm` overload -- keeps AUF in one place.
- `core/domain/.../predicate/LastLayerStepReached.kt` -- `@SerialName("last-layer-step") data class(step)`; finds the LL color opposite WHITE's center, reads `LastLayerView`, returns no pieces when F2L is unsolved; register in `Predicate` KDoc.
- `core/domain/.../predicate/CubeSummary.kt`, `tutor/DiagnosisRules.kt` -- add the new predicate to the exhaustive `when`s; literal summary keys per step.
- `core/domain/.../content/TwoLook.kt` -- `@Serializable TwoLookStage(id, title, lessons)`, `TwoLookLesson(id, stageId, prerequisites, objective, learnerAction, completion, preserve, recovery, steps)`, `TwoLookStep(goal, preserve, watch, notice, algorithm, done)`; `TwoLookResolver.resolve(stages, algorithms)` returning resolved `Stage`s, the `Map<LessonId, List<AlgorithmId>>` and `UnknownAlgorithm` problems.
- `core/domain/.../content/Model.kt`, `ContentLoader.kt`, `ContentValidator.kt`, `ContentProblem.kt` -- `BundleManifest.twoLook` default empty; `ContentBundle.twoLookStages` and `lessonAlgorithms` default empty; read each file into the revision, resolve, validate Course and 2-look Lessons together.
- `core/domain/src/main/resources/content/bundle.json`; `two-look/stage-08-two-look-oll.json` (10 Lessons `lesson-08-01`..`10`); `two-look/stage-09-two-look-pll.json` (6 Lessons `lesson-09-01`..`06`); `schema/two-look.schema.json`; `schema/bundle.schema.json`, `predicate.schema.json`; `strings/en.json` Stage titles, Lesson text and summary keys.
- `core/application/.../session/SessionKind.kt` -- `isTaught` (LESSON, TWO_LOOK) and `takesMoves` (not SOLVE_PLAYBACK).
- `core/application/.../session/SessionEvent.kt`, `Reduce.kt`, `SessionState.kt`, `SessionSnapshot.kt`, `TRANSITIONS.md` -- `LessonStarted.kind` default `LESSON`; gates use the new properties; restore accepts `TWO_LOOK`; Checkpoint gate stays `LESSON`.
- `app/.../content/LessonCatalog.kt`, `app/.../session/SessionHost.kt` -- catalog and restore include 2-look; `completeLesson` runs for `isTaught`.
- `app/.../ui/shell/LearnScreen.kt` -- *Faster last layer* becomes a non-clickable heading (same tag); two `StageRow`s under it, numbered 1 and 2; 2-look Stage detail through `StageDetailScreen` with `courseControls = false`.
- `app/.../ui/shell/AppShell.kt` -- pass 2-look flag, diagram, offer list and `onAddToLearning`; `continueEntry` uses `isTaught`.
- `app/.../ui/lesson/TwoLookOffer.kt` -- question, *Add to Learning*, *Not now*, tags `lesson:twoLookOffer*`.
- `app/.../ui/lesson/LessonScreen.kt`, `LessonPanel.kt` -- start as `TWO_LOOK`, no mastery milestone, diagram above the step card (tag `lesson:recognition`), offer when the Objective is met.
- `app/src/main/res/values/strings.xml` -- offer and diagram-description strings.
- Tests: `core/domain/.../lastlayer/LastLayerStepTest.kt` (steps, AUF, F2L, frames), `predicate/LastLayerStepReachedTest.kt` (done equals pieces, JSON round trip), `content/TwoLookContentTest.kt` (the set, IDs, derivation, legal path, no Notation in JSON), `ContentLoaderTest` (unknown Algorithm), `SchemaModelTest` (new root), `ShippedContentTest` (2-look keys and voice), `:core:application` reducer and snapshot tests for `TWO_LOOK`, `SessionHost` resume and completion test, `LearnScreen`/`AppShell` row, detail and offer tests covering every matrix row; update `AppShellTest:457`.

**Acceptance Criteria:**
- Given 2-look Lessons (3 + 7 OLL, 2 + 4 PLL) referencing Library Algorithm IDs, never copied Notation, when content tests run, then each Lesson's predicate is an LL-step predicate built on `LastLayerMatcher` with a legal path test.
- Given the Course is completed or skipped, when the Learn tab renders, then the *Faster last layer* rows unlock and run as a `TWO_LOOK` session with Teaching Modes, hints and recognition diagrams.
- Given a Lesson ends, when the Learner sees "Add these algorithms to Learning?", then accepting calls `SetAlgorithmStatus` and declining changes nothing.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 23 findings — high 0, medium 0, low 16, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: accepting the offer dismisses it before the status writes land, so a failed write leaves no retry — needs a local Room insert failure; a retry path adds state and branches, and the Library control remains the way to set the status (same reasoning as 9.6).
  - `[false]` `[reject]` Blind: the accept callback writes from a captured *Not started* list and could overwrite a concurrent *Learned* — the Lesson screen replaces the shell, so no other status control is reachable while it is open, and the list is recomputed on every composition from the live status Flow.
  - `[low]` `[reject]` Blind: `LessonStarted.kind` accepts any `SessionKind` — the only callers pass `LESSON` or `TWO_LOOK`; a guard would protect a state no caller can reach.
  - `[low]` `[patch]` Blind: every 2-look diagram has the same TalkBack description — the description now names the Lesson's objective.
  - `[low]` `[patch]` Blind: the copy says "match the picture" with no recognition cue — Lesson copy rewritten per Case (shared root cause: generic Lesson copy).
  - `[low]` `[patch]` Blind: the edge Lessons do not say the corners may differ — covered by the same copy rewrite.
  - `[low]` `[patch]` Blind: the PLL Lessons do not explain the final U turn — covered by the same copy rewrite.
  - `[low]` `[patch]` Blind: no explanation linking the four phases — covered by the same copy rewrite.
  - `[low]` `[reject]` Blind: content tests check only each Algorithm's own inverse entry, not other corner arrangements — no defect shown: each step predicate is AUF- and rotation-invariant (tested), and an edge-orientation Algorithm's effect on edges does not depend on the corners.
  - `[low]` `[reject]` Blind: no physical-mode test for a `TWO_LOOK` completion — the physical completion gate in `SessionHost.dispatch` is kind-agnostic and already covered for `LESSON`; the only change was the kind check.
  - `[low]` `[reject]` Edge: a failed `SetAlgorithmStatus` after accepting leaves the offer gone — same root cause and reasoning as the first Blind row.
  - `[low]` `[reject]` Edge: `LessonStarted` with `SOLVE_PLAYBACK` starts a Lesson that takes no Moves — same as the Blind `kind` row; no caller passes it.
  - `[low]` `[patch]` Verification gap: partial `last-layer-step` progress was only compared to its own pieces — added partial-state fixtures with independent expected counts and a partial `CubeSummary` assertion.
  - `[low]` `[patch]` Intent: the edge Lessons start from full OLL Cases and end fully oriented, so the "smaller step" exists only as the predicate — addressed by the copy rewrite, which says only the edges matter in that phase.
  - `[false]` `[reject]` Intent: the dot Lesson's `alg-oll-02-a` is not the standard dot edge Algorithm — its Notation is the standard dot sequence (the line Algorithm, then the wide L Algorithm).
  - `[low]` `[reject]` Intent: the shell tests cover only the skipped Course, not the completed one — the unlock gate `allStagesFinished` is unchanged by this story and treats Completed and Skipped alike.
  - `[low]` `[patch]` Intent: the 2-look rows hard-code *In progress*, so a Stage whose Lessons are all done never reads Completed — status now derived with `StageProgress.status(stage, listOf(stage), events)`.
  - `[false]` `[reject]` Intent: hints and Teaching Modes are checked only for presence in the UI — `TwoLookSessionTest` drives the Hint Ladder and a mode change through the reducer, and the UI shares that code with Course Lessons.
  - `[false]` `[reject]` Intent: the diagram is tested only for the first Lesson — every Lesson takes the same path (`RecognitionDiagram.derive` of its first Algorithm), and the content test proves every Lesson's Algorithm derives.
  - `[low]` `[reject]` Intent: AC3 tests use the host's in-memory status fallback, not `SetAlgorithmStatus` — the offer calls `SessionHost.setAlgorithmStatus`, whose path through `SetAlgorithmStatus` and Room is covered by 9.6's `AppShellAlgorithmStatusRoomTest` and is unchanged.
  - `[false]` `[reject]` Intent: "a Lesson ends" read as Objective met — that is the moment the Lesson's completion is recorded; no bad outcome is claimed.
  - `[false]` `[reject]` Intent: offering only *Not started* Algorithms narrows "these algorithms" — it is the spec's rule, and it prevents demoting *Learned*; no bad outcome.
  - `[false]` `[reject]` Intent: `takesMoves` lets a future kind take Moves by default, and the heading is no longer a button — no such kind exists, and the heading change is specified.

## Design Notes

**Why a separate authored shape.** `Lesson` requires `demonstration` and `steps[].moves` as Notation, which the AC forbids copying. `TwoLookLesson` swaps them for `algorithm`, and the resolver produces a plain `Lesson`, so the tutor, reducer, hints and renderer need no 2-look branches.

**Entry frame.** The Case state is derived with yellow on U (`LastLayerMatcher.caseState(notation, YELLOW)`), so Library Notation reads as printed. Encoding through `FaceletString` keeps that frame, because `CubeValidator` derives orientation from the centers, and `Tutor.currentStep` compares orientation too.

**Progress and pieces.** A leaf predicate's `done` must equal its preserved-piece count. `corners-permuted` counts corners oriented and placed under the best AUF, so 4 of 4 is exact satisfaction; a side-sticker "headlights" count could reach its total on an unoriented layer.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including the new domain, reducer, host and shell tests, with no Room schema change.

## Auto Run Result

**Summary.** Learners who finished or skipped the Course now see two *Faster last layer* rows, *2-look OLL* and *2-look PLL*, with 16 Lessons: edges dot, line and L; corners OLL 21–27; PLL corners T and Y; PLL edges Ua, Ub, H and Z. The Lesson JSON names only Library Algorithm IDs. The Notation and entry state come from the Library when content loads. Each Lesson is judged by the new `last-layer-step` predicate, which counts pieces through `LastLayerMatcher.stepPieces` and reads the LL color-anchored, in any frame, under the best AUF. Lessons run as `TWO_LOOK` sessions with Teaching Modes, hints, attempts, Guided recovery, a recognition diagram and snapshot resume. When the Objective is met, "Add these algorithms to Learning?" offers the Lesson's *Not started* Algorithms; accepting sets them to *Learning* through `SessionHost.setAlgorithmStatus`, and declining writes nothing.

**Files changed.**
- `core/domain/.../lastlayer/LastLayerStep.kt`, `LastLayerMatcher.kt` -- the four step goals; `stepPieces`; `caseState(notation, lastLayer)`.
- `core/domain/.../predicate/LastLayerStepReached.kt`, `Predicate.kt`, `CubeSummary.kt`, `tutor/DiagnosisRules.kt` -- the predicate, its summary text, and the exhaustive `when`s.
- `core/domain/.../content/TwoLook.kt`, `Model.kt`, `ContentLoader.kt`, `ContentValidator.kt`, `ContentProblem.kt` -- the authored shape, resolver, `twoLookStages`, `lessonAlgorithms` and `UnknownAlgorithm`.
- `core/domain/src/main/resources/content/` -- `bundle.json`, the two `two-look/` Stage files, the `two-look`, `bundle` and `predicate` schemas, and `en.json` titles, Lesson copy and summary keys.
- `core/application/.../session/SessionKind.kt`, `SessionEvent.kt`, `Reduce.kt`, `SessionState.kt`, `SessionSnapshot.kt`, `TRANSITIONS.md` -- `isTaught`/`takesMoves`, `LessonStarted.kind`, and `TWO_LOOK` in the gates and in restore.
- `app/.../content/LessonCatalog.kt`, `session/SessionHost.kt` -- 2-look Lessons in the catalog, restore and completion.
- `app/.../ui/shell/LearnScreen.kt`, `AppShell.kt` -- the heading, two Stage rows, 2-look Stage detail without Course controls, and the Lesson wiring.
- `app/.../ui/lesson/LessonScreen.kt`, `LessonPanel.kt`, `TwoLookOffer.kt`, `app/src/main/res/values/strings.xml` -- the `TWO_LOOK` start, the diagram, the offer, and their strings.
- Tests: new `LastLayerStepTest`, `LastLayerStepReachedTest`, `TwoLookContentTest`, `TwoLookSessionTest`, `TwoLookSessionHostTest`; additions to `ContentLoaderTest`, `SchemaModelTest`, `ShippedContentTest`, `CubeSummaryTest`, `SessionSnapshotTest`, `LessonScreenTest`, `AppShellTest` and `AppShellLibraryTest`.

**Review.** 23 findings: 6 patched, 0 deferred, 17 rejected (10 low, 7 false). Patched:
- the diagram's TalkBack description now names the Lesson;
- the Lesson copy is rewritten per Case and phase (5 rows, one root cause);
- the 2-look Stage status is derived from progress;
- partial-progress tests were added.

Every rejected row and its reason is in the Review Triage Log.

**Follow-up review: not recommended.** Patched by verdict: high 0, medium 0, low 6 entries.

**Verification.** `./gradlew spotlessCheck build` was BUILD SUCCESSFUL after the patches: 1602 tests, 0 failed, 0 skipped, by the JUnit XML reports. Every matrix row has a covering test that ran. There were no instrumented or device runs, so the drawn diagram, TalkBack and real process death are unverified.

**Residual risks.**
- A failed status write after *Add to Learning* keeps the offer on screen with an error, so the Learner can retry; there is no automatic retry.
- The edge Lessons start from full OLL Cases, so the cube ends fully oriented. The copy explains that only the edges count.
- A 2-look Stage with every Lesson done reads *Completed* through the generic Stage rule. The mini-Course *Completed*/*Mastered* rule is 9.8.
- The implementation ran on Codex first, which ended without a report. A Claude subagent then finished and corrected the work.
