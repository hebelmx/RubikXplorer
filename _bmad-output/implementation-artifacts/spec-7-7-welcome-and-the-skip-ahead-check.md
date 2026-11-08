---
title: 'Story 7.7: Welcome and the skip-ahead check'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: 'aee48c6c4fded518a4d21fa029a860502b2e7682'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-1-stage-progress-log-and-gating.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-4-independent-practice-on-a-fresh-scramble.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-6-progress-on-the-you-tab-with-reset.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Beginners on first launch lack an entry surface to select their starting point or configure color-blind glyphs before starting, while intermediate learners who already know specific Stages or the entire Course cannot prove their proficiency and skip ahead, forcing them through introductory content they have already mastered (FR-4, FR-17, AD-10, AD-18, UX-DR21, UX-DR22, UX-DR25, UX Flow 8).

**Approach:** Build a Compose `WelcomeScreen` offering *I'm new*, *I can already solve it*, a hidden *Start from my cube* placement action (enabled only when `PLACEMENT` flag is active), and an inline color-blind glyphs toggle (`W Y R O G B`); wire a skip-ahead check session in `SessionHost` and `LessonScreen` evaluating stage or full-course objectives from a fresh scramble with hints off and no time limit; invoke `PassSkipCheck` on success to record `StageSkippedByCheck` (never `MASTERED`) and unlock the next stage; and if the check is abandoned, display supportive exit copy ("Let's do this Stage together — it'll be quick." or "Let's find where to start") without logging any attempt record against the learner.

## Boundaries & Constraints

**Always:**
- Keep `:core:domain`, `:core:solver`, and `:core:application` pure JVM without Android, Compose, Room, or Hilt dependencies.
- Render `WelcomeScreen` on first launch with no profile, offering *I'm new* (navigating to Stage 1), *I can already solve it* (full-Course skip check), an inline color-blind glyph toggle (`W Y R O G B`), and hiding *Start from my cube* when `FeatureFlags.isEnabled(FeatureFlag.PLACEMENT)` is false (AD-18).
- Execute skip-ahead checks with hints off, no teaching mode selector, and no time limit.
- Single-writer enforcement: mark skipped stages and newly unlocked stages exclusively via `PassSkipCheck(stageIds, stages)` writing to `progress_event`.
- Status purity: skipped stages derive `StageStatus.SKIPPED` (◇ *Skipped*), never `MASTERED` (AD-10).
- Zero-record failure rule: when a learner exits or gives up a skip-ahead check before satisfying the completion predicate, record nothing in `AttemptRepository` or `attemptRecords` (no attempt, mistakes, or hints logged against them).
- Display designated copy on exit: "Let's do this Stage together — it'll be quick." on Stage detail exit, and "Let's find where to start" on Welcome exit.
- Accessibility floor: all interactive controls have minimum 48dp touch targets and descriptive TalkBack semantics.

**Never:**
- Never display or enable *Start from my cube* while `FeatureFlag.PLACEMENT` is `OFF` (Epic 11 scope).
- Never mark a Stage as `MASTERED` as a result of passing a skip-ahead check (FR-4, FR-20).
- Never record an attempt record (`AttemptRecord` / `attempt` table row) when a skip-ahead check is abandoned or given up.
- Never add network permissions, backup permissions, or analytics/crash SDKs (NFR-8, AD-13).
- Never bypass `SessionEvent` reduction or mutate state directly outside `SessionHost` and single writers.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Welcome first launch | `showWelcome = true`, `PLACEMENT = OFF` | `WelcomeScreen` displays *I'm new*, *I can already solve it*, and color-blind glyph toggle; *Start from my cube* is NOT rendered | No error |
| Welcome with PLACEMENT flag | `showWelcome = true`, `PLACEMENT = GA` | `WelcomeScreen` displays *Start from my cube* button alongside standard choices | No error |
| Color-blind glyphs toggle on Welcome | User toggles glyph switch on Welcome | `PreferenceStore` receives `Preference.ColorBlindGlyphs(true)`, persisting preference immediately | No error |
| Tap *I'm new* on Welcome | User taps *I'm new* | Welcome dismisses, Learn tab opens with Stage 1 selected | No error |
| Tap *I can already solve it* on Welcome | User taps *I can already solve it* | Full-course skip check opens with 25-move fresh scramble, "Solve this scramble your way. Hints are off.", hints disabled, no time limit | Scramble generation validates predicate |
| Solve full-course skip check | User commits moves solving full cube (`CubeSolved`) | Next enables; on commit, `PassSkipCheck` writes `StageSkippedByCheck` for all 7 stages; all stages show ◇ *Skipped* (never mastered); Faster last layer row unlocks | If write fails, session logs failure without crash |
| Abandon full-course skip check | User taps back or X without solving | Session closes with no attempt record saved; Welcome shows "Let's find where to start" banner | No attempt recorded |
| Tap *I already know this* on Stage detail | User taps `stageDetail:skipCheck` on Stage 2 (or 3-6) | Stage skip check opens with fresh scramble preserving earlier stages, hints disabled, no time limit | Earlier stage predicates verified on scramble |
| Solve Stage skip check | User commits moves meeting Stage completion predicate | Next enables; on commit, `PassSkipCheck` writes `StageSkippedByCheck(stageId)` and `StageUnlocked(nextStageId)`; Stage status becomes `SKIPPED`; next Stage unlocks | If write fails, status remains unchanged |
| Abandon Stage skip check | User taps back or X without solving Stage objective | Session closes with no attempt record saved; Stage detail shows "Let's do this Stage together — it'll be quick." | No attempt recorded |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/StagePractice.kt` -- Add `skipCheckLesson(stageId: StageId)` supporting Stage 1 (simple 1-move scramble returning to `CubeSolved`) and Stages 2-7, plus `courseSkipCheckLesson()` for full-course skip check with 25-move scramble and `CubeSolved` predicate.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StagePracticeTest.kt` -- Unit-test `skipCheckLesson` across all stages and `courseSkipCheckLesson` validating scramble legality, non-satisfaction of target predicate, and preservation of earlier stages.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- Add `passSkipCheck(stageIds: List<StageId>, stages: List<Stage>): Boolean` delegating to `PassSkipCheck` use case with `Clock.now()` on `writer`, refreshing `progressEvents`; filter out attempts for lessons matching `skip-check-*` so skip checks never persist attempt records.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreen.kt` (new) -- Compose screen displaying welcome title, *I'm new* button (`welcome:imNew`), *I can already solve it* button (`welcome:canSolve`), conditional *Start from my cube* button (`welcome:startFromCube`), color-blind glyphs switch (`welcome:glyphs`) with `W Y R O G B` display, and optional abandoned check banner ("Let's find where to start", `welcome:abandoned`).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Add `isSkipCheck: Boolean = false`, `skipCheckInstruction: String? = null`, and `onSkipCheckPassed: (() -> Unit)? = null`; disable Hint button when `isSkipCheck = true`; show skip check instruction banner; invoke `onSkipCheckPassed` when Next is clicked after objective completion.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- Pass abandoned banner text ("Let's do this Stage together — it'll be quick.") to `StageDetailScreen` when `abandonedSkipCheckStage == stage.id`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- Add `showWelcome: Boolean = false` state and navigation handling for `WelcomeScreen`, `onSkipCheck` from `StageDetailScreen` and `onCanAlreadySolve` from `WelcomeScreen`; manage `openSkipCheck` state, execute `host.passSkipCheck(...)` on success, and track abandoned messages for Stage and Welcome flows.
- `app/src/main/res/values/strings.xml` -- Add strings for welcome choices, skip check instruction, and abandoned feedback.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreenTest.kt` (new) -- Compose tests verifying Welcome screen actions, PLACEMENT feature flag gating, glyph toggle persistence, and abandoned message display.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellSkipCheckTest.kt` (new) -- Compose tests verifying full-course skip check from Welcome, Stage skip check from Stage detail, `PassSkipCheck` status updates (◇ *Skipped*, never *Mastered*), zero-record guarantee on abandon, and abandoned copy presentation.

## Tasks & Acceptance

**Execution:**
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/StagePractice.kt` -- Add skip check lesson generation for stages 1-7 and full course -- Produces legal entry states and target predicates for skip-ahead checks.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StagePracticeTest.kt` -- Add unit tests for skip check lesson generation -- Verifies scramble generation and predicate enforcement under pure JVM.
- `app/src/main/res/values/strings.xml` -- Add localized strings for Welcome, skip check instruction, and abandoned feedback -- Supplies accessible, tested copy adhering to UX Flow 8 and UX-DR25.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- Implement `passSkipCheck` method and suppress attempt recording for `skip-check-*` lessons -- Ensures skip check passes are persisted via `PassSkipCheck` while abandoned checks leave zero attempt records.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreen.kt` -- Implement `WelcomeScreen` composable -- Displays entry options, inline glyph switch, and respects `PLACEMENT` flag.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Support `isSkipCheck` mode with hints disabled and skip check completion handling -- Executes skip-ahead check tasks with hints off and no time limit.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- Add abandoned skip check message support in `StageDetailScreen` -- Displays reassuring copy when a Stage check is abandoned.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- Wire Welcome screen, skip check tasks, and `passSkipCheck` execution -- Integrates Welcome and skip check flows into the app navigation shell.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreenTest.kt` -- Create Compose unit tests for `WelcomeScreen` -- Verifies actions, feature flag gating, and glyph preference updates.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellSkipCheckTest.kt` -- Create Compose unit tests for skip-ahead check flows -- Verifies skip-ahead execution, status transition to ◇ *Skipped*, next stage unlocking, zero attempt logging on abandon, and banner copy.

**Acceptance Criteria:**
- Given first launch with no profile, when Welcome shows, then it offers *I'm new* (Stage 1) and *I can already solve it*; *Start from my cube* stays hidden until Epic 11 enables `PLACEMENT`; color-blind glyphs are offered.
- Given *I already know this* on a Stage, or *I can already solve it* for the whole Course, when the Learner solves that Objective from a fresh Scramble with hints off and no time limit, then `PassSkipCheck` marks the Stage(s) ◇ *Skipped* (never mastered) and unlocks the next.
- Given the Learner gives up the check, when they exit, then "Let's do this Stage together — it'll be quick." (or "Let's find where to start" from Welcome) shows, with no record against them.

## Spec Change Log

- 2026-11-07: Authored spec for Story 7.7 (Welcome and the skip-ahead check) covering AC 1, 2, and 3.
- 2026-11-07: Completed implementation and 4-lens adversarial review; all review findings triaged and patched; verified build and tests pass.

## Review Triage Log

### 2026-11-07 — Review pass 1 (4 lenses: Intent Alignment, Verification Gap, Edge Case Hunter, Blind Hunter)
- [medium] [patch] MainActivity was not passing initial `showWelcome` state based on empty progress and attempt history -> Patched `MainActivity.kt` with `collectAsState()` on progress events and attempt records.
- [low] [patch] `SessionHost.passSkipCheck` fallback branch only emitted `StageSkippedByCheck` and omitted `immediatelyFollowingLocked` -> Patched `SessionHost.kt` fallback to unlock next stage.
- [low] [patch] `courseSkipCheckLesson` had no fallback if stage bundle was empty -> Patched `AppShell.kt` with empty-bundle guard.
- [low] [patch] `stageIndex == -1` guard missing in `onSkipCheckPassed` -> Patched `AppShell.kt` with bounds guard.
- [low] [patch] Closing a satisfied skip check without clicking next triggered the abandon message -> Patched `AppShell.kt` to only show abandon banner if `!objectiveMet`.
- [low] [patch] `skipCheckEnabled` was active on completed or skipped stages -> Patched `LearnScreen.kt` to gate on `isLocked || isInProgress`.
- [low] [patch] Reset progress in Settings left `welcome = false` -> Patched `AppShell.kt` `onResetProgress` to restore `welcome = true`.
- [low] [patch] Random seed robustness test in `StagePracticeTest.kt` -> Patched with multi-iteration test over 10 seeds.
- [low] [patch] System back gesture handling test missing -> Patched `AppShellSkipCheckTest.kt` with back gesture verification.
- [medium] [patch] Compose lint error `StateFlowValueCalledInComposition` in `MainActivity.kt` -> Patched using `collectAsState()`.
- [false] [reject] Placement button in WelcomeScreen should be functional -> Rejected: Placement flow is explicitly deferred to Epic 11 per story AC.
- [false] [reject] Skip check dispatches `LessonStarted` -> Rejected: Dispatches `PracticeStarted` via `StagePractice.skipCheckLesson`.
- [false] [reject] Stage 1 scramble limited to U/R/F -> Rejected: Single-move scramble returning to `CubeSolved` is intentional and verified.
- [low] [reject] Dead branch in `AppShell` `BackHandler` -> Rejected: `LessonScreen` correctly owns internal back handling.
- [low] [reject] Course skip check top bar title cosmetic tweak -> Rejected: Existing title meets UX spec.

## Design Notes

- Skip-ahead check lesson IDs: `skip-check-${stageId.value}` for single stages, and `skip-check-course` for the whole course.
- In `SessionHost.dispatch`, `AttemptStarted` and `AttemptEnded` are suppressed whenever `objectiveId.startsWith("skip-check-")`. This guarantees zero attempt records against the learner, preserving trend row and mastery statistics.
- When `PassSkipCheck` executes, it appends `ProgressEvent.StageSkippedByCheck` and `ProgressEvent.StageUnlocked`. Stage status evaluates to `StageStatus.SKIPPED`, which renders as ◇ *Skipped* in the UI.
- On exit from a skip check without `objectiveMet`:
  - If from Welcome: sets `welcomeAbandoned = true`, which displays "Let's find where to start" (`welcome:abandoned`).
  - If from Stage detail: sets `stageAbandoned = stageId`, which renders "Let's do this Stage together — it'll be quick." (`stageDetail:skipCheckAbandoned`).

## Verification

**Commands:**
- `./gradlew :core:application:test` -- expected: `StagePracticeTest` and all application unit tests pass.
- `./gradlew test` -- expected: all JVM and Robolectric tests pass, including new `WelcomeScreenTest` and `AppShellSkipCheckTest`.
- `./gradlew spotlessCheck` -- expected: code formatting is clean.
- `./gradlew build` -- expected: full build, Kover gate (>= 80% on core modules), and APK assembly succeed.

## Auto Run Result

- **Status:** done
- **Spec File:** `_bmad-output/implementation-artifacts/spec-7-7-welcome-and-the-skip-ahead-check.md`
- **Summary:**
  - Implemented `WelcomeScreen` offering *I'm new* (navigating to Stage 1), *I can already solve it* (full-course skip check), color-blind glyphs switch (`W Y R O G B`), and hiding *Start from my cube* when `PLACEMENT` flag is disabled.
  - Implemented single-stage and full-course skip-ahead checks in `StagePractice`, `SessionHost`, and `LessonScreen` with hints disabled and no time limit.
  - Passing a skip check persists `PassSkipCheck`, marking stages ◇ *Skipped* (never mastered) and unlocking the next stage (or *Faster last layer* for full course).
  - Abandoning a skip check produces zero attempt records against the learner and shows supportive copy ("Let's do this Stage together — it'll be quick." or "Let's find where to start").
- **Files Modified / Created:**
  - `_bmad-output/implementation-artifacts/spec-7-7-welcome-and-the-skip-ahead-check.md`
  - `app/src/main/kotlin/dev/rubikxplorer/app/MainActivity.kt`
  - `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt`
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt`
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt`
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt`
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreen.kt`
  - `app/src/main/res/values/strings.xml`
  - `app/src/sharedTest/kotlin/dev/rubikxplorer/app/session/TestLessons.kt`
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellSkipCheckTest.kt`
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreenTest.kt`
  - `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/StagePractice.kt`
  - `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StagePracticeTest.kt`
  - `core/domain/src/main/resources/content/strings/en.json`
- **Review Findings Breakdown:**
  - Total findings: 15
  - Patched: 10 (1 medium, 9 low / compose lint)
  - Rejected: 5 (1 medium, 4 low / cosmetic / out of scope)
- **Follow-up Review Recommended:** false
- **Verification Commands Executed:**
  - `./gradlew spotlessCheck build`: PASS (spotless clean, lint clean, Kover gates met, debug & release APKs built)
  - `./gradlew -p build-logic test`: PASS (7/7 tests up-to-date)
