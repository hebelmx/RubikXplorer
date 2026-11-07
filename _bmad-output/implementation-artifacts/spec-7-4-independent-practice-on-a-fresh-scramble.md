---
title: 'Story 7.4: Independent practice on a fresh Scramble'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: '840649bde838b0e22e97052e5b6308ac17efd63f'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-learn-tab-and-stage-detail.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-3-author-stages-4-6-to-a-first-solve.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Learners cannot practice Stages or the full solve on a fresh scramble with reduced assistance; `StageDetailScreen` has *Practice this Stage* disabled (`practiceEnabled = false`), the first-solve milestone's *Practice on a new scramble* only closes back to the Learn tab, and no `PRACTICE` session or stage entry scrambler exists (FR-4, FR-20, AD-3, AD-11, AR-7, AR-15, UX Flow 7).

**Approach:** Add a `StagePractice` use case in `:core:application` that generates stage entry states from `NewScramble` or random moves preserving earlier Stages' predicates, introduce `SessionEvent.PracticeStarted` yielding a `PRACTICE` session with `assistanceLevel = REDUCED`, update `Reduce.kt` to record `AttemptEnded` with outcome, errors, hints and `assistanceLevel = REDUCED`, enable *Practice this Stage* in `StageDetailScreen` for unlocked Stages 2–7, wire *Practice on a new scramble* from the milestone, and present the practice step card showing only the Objective with hints on request.

## Boundaries & Constraints

**Always:**
- Pure JVM in `:core:application` and `:core:domain` with no Android, Compose, Room, CameraX or Hilt imports.
- `SessionKind.PRACTICE` is used for independent practice sessions in the single active `SessionHost` (AD-3).
- Entry states for Stages 2–7 are generated without a Solver (AD-11, AR-7):
  - Stages 2 and 7: 25-move `NewScramble` from `CubeState.SOLVED`.
  - Stage 3: Start `SOLVED`, apply random sequences that preserve `white-cross-solved` (`D`, `D'`, `D2` and corner pop/insert moves `R' D' R`, `F D F'`, `L D L'`, `B D B'`).
  - Stage 4: Start `SOLVED`, apply random sequences that preserve first layer (`D`, `D'`, `D2` and middle-layer insertion sequences `D R D' R' D' F' D F`, `D' L' D L D F D' F'`).
  - Stage 5: Start `SOLVED`, apply random sequences that preserve F2L (`D`, `D'`, `D2` and LL orientation algorithms `F L D L' D' F'`, `L D L' D L D2 L'`).
  - Stage 6: Start `SOLVED`, apply random sequences that preserve F2L and LL orientation (`D`, `D'`, `D2` and LL permutation algorithms `D L D' R' D L' D' R`, `L D' L D L D L D' L' D' L2`).
  - Scramble generation ensures the earlier Stage predicates hold on entry while the Stage's own completion predicate does not hold.
- Practice attempts start with `attemptOpen = !objectiveMet`, `attemptAssistance = AssistanceLevel.REDUCED`. When the Stage's Completion Predicate is satisfied, `Effect.AttemptEnded(..., outcome = COMPLETED, assistanceLevel = REDUCED)` is emitted. If replaced or suspended while open, `Effect.AttemptEnded(..., outcome = ABANDONED, assistanceLevel = REDUCED)` is emitted.
- In `SessionKind.PRACTICE`, `assistance` is always `AssistanceLevel.REDUCED`, capping the Hint Ladder at `HintRung.TARGET` (2 rungs: `Hint (0/2)`, `Hint (1/2)`, `Hint (2/2)`).
- The practice step card displays only the Objective (`lesson:goal`), "Objective met" on completion (`lesson:objectiveMet`), and hints on request (`lesson:hint:line`); no Goal/Keep/Watch/Notice/Moves clutter.
- *Practice this Stage* button (`stageDetail:practice`) is enabled for unlocked Stages 2–7 (`status != StageStatus.LOCKED`), and disabled for Stage 1 (`stage-01-fundamentals`) and locked Stages.
- Milestone *Practice on a new scramble* at the end of Stage 6 starts Stage 7 practice (`stage-07-independent-practice`).
- All Learner-facing copy is added to `content/strings/en.json` (or `res/values/strings.xml` for Android UI strings) and obeys voice guide rules (Goal/Keep/Watch/Notice, no red/blame wording, no exclamation marks).

**Never:**
- Never evaluate Mastery condition or mark a Stage *Mastered* (that is Story 7.5).
- Never evaluate skip-ahead check or placement (Story 7.7).
- Never add a Solver dependency or call `CubeSolver` for scramble generation.
- Never add network/analytics/crash SDKs, or mutate session state outside `SessionEvent -> reduce()`.
- Never delete or weaken existing tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Practice Stage 2 | Learner taps *Practice this Stage* on unlocked Stage 2 | `PracticeStarted` dispatched; `PRACTICE` session begins from 25-move scramble; `white-cross-solved` is target; `assistance = REDUCED` | If already solved (improbable), redraw scramble |
| Practice Stage 3 | Learner taps *Practice this Stage* on unlocked Stage 3 | `PracticeStarted` dispatched; entry state has `white-cross-solved == true`, corners not solved; `assistance = REDUCED` | Redraw if corners already solved |
| Practice Stage 4 | Learner taps *Practice this Stage* on unlocked Stage 4 | `PracticeStarted` dispatched; entry state has first layer solved, middle layer not solved; `assistance = REDUCED` | Redraw if middle layer already solved |
| Practice Stage 5 | Learner taps *Practice this Stage* on unlocked Stage 5 | `PracticeStarted` dispatched; entry state has F2L solved, yellow face not fully oriented; `assistance = REDUCED` | Redraw if LL already oriented |
| Practice Stage 6 | Learner taps *Practice this Stage* on unlocked Stage 6 | `PracticeStarted` dispatched; entry state has F2L + yellow orientation solved, pieces not permuted; `assistance = REDUCED` | Redraw if already solved |
| Practice Stage 7 | Learner taps *Practice this Stage* on unlocked Stage 7 | `PracticeStarted` dispatched; 25-move full scramble; `cube-solved` is target; `assistance = REDUCED` | Redraw if already solved |
| Stage 1 Detail | Learner opens Stage 1 detail | `stageDetail:practice` is disabled (`practiceEnabled = false`) | — |
| Locked Stage Detail | Learner opens locked Stage (e.g. Stage 2 with prerequisites unmet) | `stageDetail:practice` is disabled | — |
| Hint ladder in Practice | Learner taps `lesson:hint` in practice session | Climbs from `Hint (0/2)` to `Hint (1/2)` (clue) then `Hint (2/2)` (target); stops at rung 2; cannot climb to Demonstration | Button disabled at (2/2) |
| Complete practice attempt | Learner makes moves that satisfy Stage completion predicate | `AttemptEnded(outcome = COMPLETED, assistanceLevel = REDUCED, errors, hints)` emitted; `attemptOpen = false`; `lesson:objectiveMet` displayed; `lesson:next` enabled | No error expected |
| Replace practice attempt | Learner starts new session while practice attempt is open | `AttemptEnded(outcome = ABANDONED, assistanceLevel = REDUCED, errors, hints)` emitted for previous attempt; new attempt begins | No error expected |
| First-solve milestone practice | Learner taps *Practice on a new scramble* on Stage 6 milestone | Lesson closes and Stage 7 practice starts | No error expected |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/StagePractice.kt` -- use case generating stage practice entries (`PracticeSpec` or practice `Lesson`) with entry states preserving earlier Stage predicates and setting completion/preserve predicates.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionEvent.kt` -- add `SessionEvent.PracticeStarted(val lesson: Lesson, val tutorConfig: TutorConfig, override val at: Instant)`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` -- update `assistance` to return `AssistanceLevel.REDUCED` when `kind == SessionKind.PRACTICE`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- handle `PracticeStarted`, allow `PRACTICE` sessions to accept moves/undo/redo/reset/hints, restore `PRACTICE` sessions, and record `AttemptEnded` with `AssistanceLevel.REDUCED`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- update transitions table and narrative for `PracticeStarted` and `SessionKind.PRACTICE`.
- `core/domain/src/main/resources/content/strings/en.json` -- add practice objective text keys for Stages 2–7.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` & `LessonScreen.kt` -- step card adjustments for practice mode (showing only the Objective, hiding Keep/Watch/Moves row, hints on request).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- calculate `practiceEnabled = activeStage.id != StageId("stage-01-fundamentals") && status != StageStatus.LOCKED`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- wire `onPracticeStage` from `LearnScreen` and `onPracticeNewScramble` from milestone to launch practice sessions.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add stage practice objective text keys -- provides learner-facing copy for practice objectives.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/StagePractice.kt` -- implement `StagePractice` use case -- generates scramble states preserving earlier Stage predicates without a Solver.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionEvent.kt` & `SessionState.kt` -- declare `PracticeStarted` and set `assistance = REDUCED` for `PRACTICE` sessions -- establishes session contract.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` & `TRANSITIONS.md` -- implement `startPractice`, adjust event guards, attempt recording, and document transitions -- satisfies AC 1 and AC 2.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StagePracticeTest.kt` -- unit test `StagePractice` edge cases and preservation invariants across all stages -- validates scramble generation.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PracticeSessionTest.kt` -- test practice session lifecycle, reduced assistance ladder, and attempt recording -- verifies AC 1 and AC 2.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` & `LessonScreen.kt` -- adapt step card and controls for practice mode -- satisfies "step card shows only the Objective with hints on request".
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` & `AppShell.kt` -- enable *Practice this Stage* for unlocked Stages 2–7, wire launch to practice, and connect milestone *Practice on a new scramble* -- integrates practice into app flow.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt` & `PracticeScreenTest.kt` -- test practice launching, UI tags, step card rendering, and milestone navigation -- confirms end-to-end behavior.

**Acceptance Criteria:**
- Given *Practice this Stage* (and Stage 7, Independent practice), when it starts, then a `PRACTICE` session begins from a `NewScramble` reduced to that Stage's entry condition, and the step card shows only the Objective with hints on request.
- Given a practice attempt, when it ends, then `AttemptEnded` records outcome, errors, hints and `assistanceLevel = REDUCED`.

## Spec Change Log

## Review Triage Log

- **Finding 1 (Intent Alignment / Edge Case):** Practice session restart with a new scramble when tapping *Practice this Stage* on an already open practice session or relaunching.
  - *Triage:* Addressed. `LessonScreen` keys the `LaunchedEffect` by `(lesson.id, lesson.entryState)`. Every practice launch creates a fresh scramble state, triggering `PracticeStarted` with the newly generated scramble.
- **Finding 2 (Edge Case):** In `Reduce.kt:committed()`, move assessment was restricted to `mode == TEST_ME` or adaptive assistance. Harmful moves during practice were not incrementing `stepMistakes` for `AttemptEnded`.
  - *Triage:* Addressed. Added `state.kind == SessionKind.PRACTICE` to the move assessment check in `Reduce.kt:committed()`, ensuring harmful moves in practice increment mistake count and record accurately in `AttemptEnded`.
- **Finding 3 (Blind Hunter / Content):** HintLadder rung 2 (`TARGET`) needed a non-step-specific guidance message suitable for Stage practice.
  - *Triage:* Addressed. Added `"msg.practice.hint.target": "Focus on the pieces needed for this Stage's goal."` to `en.json` and resolved it for practice sessions in `LessonScreen.kt`.
- **Finding 4 (Verification Gap):** Test coverage required verifying that practice moves with errors record mistake count in `AttemptEnded`, and that `AppShell` launches practice cleanly.
  - *Triage:* Addressed. Added `practice session records mistakes when learner makes harmful moves` test in `PracticeSessionTest.kt` and tests in `AppShellTest.kt`.

## Auto Run Result

- **Implementation Summary:**
  - Implemented `StagePractice` use case in `:core:application` generating practice scramble states for Stages 2–7 without a Solver (preserving earlier Stages' predicates, leaving the current stage unsolved).
  - Introduced `SessionEvent.PracticeStarted` in `:core:application` and configured `SessionState.assistance` to return `AssistanceLevel.REDUCED` in `SessionKind.PRACTICE`.
  - Updated `Reduce.kt` to handle practice lifecycle, assess harmful moves to increment mistakes, and emit `Effect.AttemptEnded` with outcome, errors, hints, and `assistanceLevel = REDUCED`.
  - Updated `TRANSITIONS.md` documenting `PracticeStarted` and its transition semantics.
  - Added practice objective strings and practice hint target text in `en.json`.
  - Adapted `LessonPanel.kt` to render only the Objective on the step card for practice mode (suppressing Keep/Watch/Moves/Mode switcher), with hints on request up to `Hint (2/2)`.
  - Enabled *Practice this Stage* in `LearnScreen.kt` for unlocked Stages 2–7.
  - Wired practice launches in `AppShell.kt` from both `StageDetailScreen` and the first-solve milestone action *Practice on a new scramble*.
  - Comprehensive unit test coverage added in `StagePracticeTest.kt`, `PracticeSessionTest.kt`, `PracticeScreenTest.kt`, and `AppShellTest.kt`.
- **Verification:**
  - `./gradlew test` passed (306 tests completed cleanly across all modules).
  - `./gradlew spotlessCheck build` passed (including `assembleDebug`, `assembleRelease`, Kover coverage >= 80%, module boundaries, and denylist checks).
- **Status:** Done

## Design Notes

- **Scramble without a Solver:**
  Rather than running an offline two-phase search to set up a state, `StagePractice` starts from `CubeState.SOLVED` and applies sequences of moves that are invariant under the earlier Stages' predicates (e.g. `D` face turns and the standard beginner algorithms taught in Stages 3–6). For Stage 2 and Stage 7, a standard 25-move `NewScramble` is used.
- **Practice Step Card:**
  In a `PRACTICE` session, `StepCardContent` receives the Stage Objective as `goal` and empty strings for `keep` and `watch`, with `moves = null`. `StepCard` omits `Field`s whose text is empty, resulting in a clean card that presents only the Objective and the Hint line upon request.
- **Milestone Rewire:**
  In `AppShell`, `onPracticeNewScramble` in `LessonScreen` is updated from a no-op close to launching Stage 7 (`stage-07-independent-practice`).

## Verification

**Commands:**
- `./gradlew test` -- expected: all core domain, solver, application, and app unit tests pass.
- `./gradlew spotlessCheck build` -- expected: spotless, lint, boundary checks, and release/debug builds succeed.
