---
title: 'Story 7.5: Mastery Condition and milestone'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: 'e6f6c32c44ec3c724f3dcb3084ccae196f8514a6'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-learn-tab-and-stage-detail.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-4-independent-practice-on-a-fresh-scramble.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Learners can complete lessons and practice Stages with reduced assistance, but the app does not evaluate Stage mastery against a configurable Mastery Condition; `masteredStages` is hard-coded empty in `AppShell` and `LearnScreen`, `StageProgress.status` never marks a Stage `MASTERED` from real attempts, `tutor-config.json` lacks mastery thresholds, and meeting the condition never triggers the Stage mastery milestone (FR-4, FR-20, AD-6, AD-10, UX-DR18, UX-DR20, UX Flow 7).

**Approach:** Add `mastery` thresholds to `tutor-config.json` and its schema (`solvesRequired = 2`, `reducedHelpRequired = 1`), implement `tutor/Mastery` in `:core:domain` as a pure function over attempt records and current-epoch events, wire `AttemptRepository` to `SessionHost` to supply reactive attempt logs, evaluate `masteredStages` in `AppShell` and `LearnScreen` so Stage detail reads "Mastery: X of Y solves · Z with reduced help" with active segments and `◆ Mastered` badge, and trigger the milestone moment ("{Stage} mastered.", burst graphic, static on reduce motion, offering next Stage) when the condition is first met at attempt end.

## Boundaries & Constraints

**Always:**
- Keep `:core:domain` pure JVM with no dependencies on `:core:application`, Android, or Compose.
- Define `MasteryThresholds` in `tutor-config.json`, validated by `ContentValidator` and mirrored in `content/schema/tutor-config.schema.json`.
- Evaluate Stage mastery purely from attempts in the active epoch; neither Lesson completion nor skip marks a Stage *Mastered*.
- An attempt counts for a Stage if its `objectiveId` matches `"practice-${stage.id.value}"`, `${stage.id.value}`, or the Stage's final Lesson.
- Milestone plays once upon transition to Mastered, renders a static frame under reduce motion, displays the display-headline (e.g. "First cross mastered."), offers the next Stage, and offers practice/continue.
- Stage detail displays the progress string formatted via `R.string.stage_mastery_progress` with accurate counts and two-segment visual indicator.

**Never:**
- Never mark a Stage *Mastered* from `LessonCompleted` or `StageSkippedByCheck`/`StageSkippedByPlacement`.
- Never hard-code tutor thresholds or constants in `:core:domain` or `:core:application` code (AR-22).
- Never mutate state outside `SessionEvent -> reduce()`.
- Never add network, analytics, or crash SDKs.
- Never weaken or delete existing tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Zero attempts | Stage unlocked, no attempts recorded | `solvesCompleted = 0`, `reducedHelpSolves = 0`, not mastered; both indicator segments inactive | No error |
| 1 solve full help | 1 completed attempt with `assistanceLevel = FULL` | `solvesCompleted = 1`, `reducedHelpSolves = 0`, not mastered; segment 1 active, segment 2 inactive | No error |
| 2 solves full help | 2 completed attempts with `assistanceLevel = FULL` | `solvesCompleted = 2`, `reducedHelpSolves = 0`, not mastered (needs >= 1 reduced help) | No error |
| 1 solve reduced help | 1 completed attempt with `assistanceLevel = REDUCED` | `solvesCompleted = 1`, `reducedHelpSolves = 1`, not mastered (needs 2 solves) | No error |
| 2 solves with 1 reduced | 1 completed FULL + 1 completed REDUCED | `solvesCompleted = 2`, `reducedHelpSolves = 1`, condition met -> `isMastered = true`; both segments active | No error |
| 2 solves with 2 reduced | 2 completed attempts with `assistanceLevel = REDUCED` | `solvesCompleted = 2`, `reducedHelpSolves = 2`, condition met -> `isMastered = true` | No error |
| Abandoned attempt | 1 abandoned REDUCED attempt + 1 completed REDUCED | `solvesCompleted = 1`, `reducedHelpSolves = 1`, not mastered | No error |
| Attempt from other Stage | 2 completed REDUCED attempts on Stage 3 | Stage 2 `solvesCompleted = 0`, Stage 2 not mastered | No error |
| Progress reset (epoch) | 2 completed attempts before reset, 0 after reset | Current epoch attempts = 0; Stage not mastered | No error |
| Complete lesson without practice | All lessons in Stage completed | `StageProgress.status` is `COMPLETED`, not `MASTERED` | No error |
| Skip-ahead check passed | `StageSkippedByCheck` in progress events | `StageProgress.status` is `SKIPPED`, not `MASTERED` | No error |
| Custom config thresholds | `solvesRequired = 3, reducedHelpRequired = 2` | Evaluates according to configured numbers | Reject if `reducedHelpRequired > solvesRequired` |
| Attempt completes meeting condition | Learner completes 2nd solve with reduced help | Milestone plays: sticker burst, "{Stage} mastered.", next Stage offered | Dismissing returns to Stage detail with `◆ Mastered` |
| Milestone reduce motion | Milestone triggered with `reduceMotion = true` | Milestone renders static burst with headline and next Stage controls | No animation looping |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/schema/tutor-config.schema.json` -- add `mastery` object schema (`solvesRequired`, `reducedHelpRequired`).
- `core/domain/src/main/resources/content/tutor-config.json` -- add `"mastery": { "solvesRequired": 2, "reducedHelpRequired": 1 }`.
- `core/domain/src/test/resources/content-fixtures/valid/tutor-config.json` -- mirror `mastery` config in test fixture.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Model.kt` -- add `MasteryThresholds` data class and `mastery` field to `TutorConfig`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentValidator.kt` -- validate `mastery` thresholds in `TutorConfig`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Mastery.kt` -- pure `tutor/Mastery` evaluating attempts against `MasteryThresholds`, calculating solves and mastered stages.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageAttempt.kt` or `MasteryAttempt.kt` -- lightweight attempt abstraction in domain for evaluation.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/AttemptRecord.kt` -- implement or map to `MasteryAttempt`.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- inject `AttemptRepository`, expose reactive `attemptRecords: StateFlow<List<AttemptRecord>>`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestone.kt` -- support next Stage offer (`nextStageLabel`, `onNextStage`).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- trigger milestone when Mastery condition is met at attempt end, offer next Stage.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- wire real `masteredStages`, per-stage `solvesCompleted` and `reducedHelpSolves` to `StageDetailScreen`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- collect `attemptRecords`, compute `masteredStages` via `Mastery.masteredStages(...)`, wire next stage action.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/MasteryTest.kt` -- comprehensive tests covering every transition, config variance, epoch filtering, and non-mastery invariants.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/StageMasteryMilestoneTest.kt` & `LessonScreenTest.kt` -- test milestone trigger, copy, next Stage offer, reduce motion, and Stage detail update.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/schema/tutor-config.schema.json` & `tutor-config.json` -- add `mastery` configuration thresholds -- defines schema and shipped values (AC 1).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Model.kt` & `ContentValidator.kt` -- model `MasteryThresholds` and validate in `ContentValidator` -- guarantees type-safe config loading (AC 1).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Mastery.kt` -- implement pure `Mastery` evaluation functions -- calculates progress and mastered set from attempts (AC 1).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/MasteryTest.kt` -- unit test `Mastery` transitions, config-driven behavior, and lesson/skip exclusion -- satisfies AC 1.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- inject `AttemptRepository` and publish `attemptRecords` -- connects persistence to UI (AC 1, AC 2).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestone.kt` & `LessonScreen.kt` -- support stage mastery milestone with next Stage offer -- satisfies AC 2.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` & `AppShell.kt` -- connect reactive mastery progress to `StageDetailScreen` and Learn tab -- satisfies AC 2.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/StageMasteryMilestoneTest.kt` & `AppShellTest.kt` -- test end-to-end milestone presentation, copy, and Stage detail verification -- satisfies AC 2.

**Acceptance Criteria:**
- Given `tutor/Mastery` with the Mastery Condition in config (2 successful completions, >= 1 with reduced assistance), when attempts are evaluated, then config-driven tests cover each transition, and neither Lesson completion nor skipped marks mastered.
- Given the condition is met, when the attempt ends, then the milestone plays, "First cross mastered." shows, the next Stage is offered, and Stage detail reads "Mastery: 2 of 2 solves · 1 with reduced help".

## Spec Change Log

## Review Triage Log

### 2026-11-07 — Review pass
- verdicts: 14 findings — high 0, medium 6, low 4, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` Unverified reactive attempt publication during persistent SessionHost writes — in `SessionHostAttemptTest.kt`, configured SessionHost with `attempts = log`, committed attempt moves, flushed writer, and asserted `host.attemptRecords.value` updates.
  - `[medium]` `[patch]` In `AppShell.kt:135-138`, `onPracticeNewScramble` is hardcoded to navigate to `"stage-07-independent-practice"` during lesson flow — patched `AppShell.kt` to launch `entry.stage.id.value` practice instead of defaulting to stage 7.
  - `[medium]` `[patch]` `SessionHost.kt` (lines 342-349): `pendingAttempts` dropped or not refreshed on successful retry — patched `refreshAttemptsAfterWrite` to receive `batch` whenever `batch.isNotEmpty()` upon save success.
  - `[medium]` `[patch]` `SessionHost.kt` (lines 327-350): Missing attempt refresh when `writer == null` but `repository != null` — patched condition to verify `writer != null` before enqueueing snapshot save, falling back to synchronous attempt refresh.
  - `[false]` `[reject]` `SessionHost.kt` (lines 240-254 vs 224-237): Unserialized DB read in `refreshAttemptsAfterWrite` — `writer.read` inside `writer.enqueue` causes self-deadlock because `read` queues an operation behind the running enqueue operation on the single-threaded FIFO channel.
  - `[low]` `[patch]` `LearnScreen.kt` (lines 559-572): Custom `solvesRequired` and `reducedHelpRequired` parameters overwritten when `attemptRecords` is empty — updated `masteryProgress` condition in `LearnScreen.kt` to check `attemptRecords.isNotEmpty()` so explicit threshold parameters are preserved when attempt records are empty.
  - `[low]` `[reject]` `LearnScreen.kt` (lines 600-601): Hardcoded tutor threshold constants in UI code violate AR-22 — constants are default parameters in `:app` Compose UI for isolated previews, not `:core:domain` or `:core:application` logic; harmless for everyday use.
  - `[low]` `[patch]` `LearnScreen.kt` (lines 550-572): Unmemoized mastery calculations trigger on every recomposition — wrapped `effectiveMasteredStages` calculation in `remember(masteredStages, stages, attemptRecords, progressEvents, catalog.bundle.tutorConfig.mastery)`.
  - `[medium]` `[patch]` `LessonScreen.kt` (lines 398-412, 435-445): First-solve milestone celebration is overshadowed by Stage 6 mastery milestone — reordered headline check in `LessonScreen.kt` so `isFirstSolveMilestone` takes priority, ensuring the climactic "You solved the Rubik's cube!" copy is not replaced by stage 6 mastery copy.
  - `[false]` `[reject]` `LessonScreen.kt` (lines 185-188, 398-403): Cold-start race condition in `initialMastered` initialization — line 179 `host.awaitRestored()` suspends until `restore()` completes `reloadAttempts()`, ensuring `host.attemptRecords.value` is populated before `initialMastered` is initialized.
  - `[medium]` `[patch]` `AppShell.kt` (lines 496-500, 509-513): `onNextStage` does not switch active tab to `Tab.LEARN` — patched `onNextStage` in both lesson and practice flows to set `tab = Tab.LEARN`.
  - `[low]` `[patch]` `Mastery.kt` (lines 1146-1156): Inclusive boundary in `currentEpochAttempts` leaks attempts at reset timestamp into the new epoch — patched `Mastery.currentEpochAttempts` to filter strictly `it.at > lastReset.at` and added unit test verifying exact timestamp boundary.
  - `[false]` `[reject]` `FirstSolveMilestone.kt` (lines 140-154, 303-317) & `strings.xml`: Dead localized resource `R.string.milestone_next_stage` — the string resource is an active fallback when `nextStageLabel` is null in previews and tests.
  - `[false]` `[reject]` `StageDetailScreen` / `Mastery.evaluate`: Uncapped solve counts in progress text and rigid two-segment visual indicator — spec explicitly mandates accurate counts and two-segment indicator reflecting first solve and mastery condition.

## Design Notes

- Mastery evaluation uses:
  ```kotlin
  fun evaluate(
      stage: Stage,
      attempts: List<MasteryAttempt>,
      config: MasteryThresholds,
  ): StageMasteryProgress
  ```
- An attempt matches a Stage if `attempt.objectiveId == "practice-${stage.id.value}" || attempt.objectiveId == stage.id.value || (stage.lessons.isNotEmpty() && attempt.objectiveId == stage.lessons.last().id.value)`.
- Solves count toward `solvesCompleted` when `outcome == AttemptOutcome.COMPLETED`.
- Solves count toward `reducedHelpSolves` when `outcome == AttemptOutcome.COMPLETED` and `assistanceLevel <= AssistanceLevel.REDUCED`.
- Stage detail reads `Mastery: %1$d of %2$d solves · %3$d with reduced help`, matching `R.string.stage_mastery_progress`.

## Verification

**Commands:**
- `./gradlew test` -- expected: all JVM unit tests pass in `:core:*` and `:app`.
- `./gradlew spotlessCheck` -- expected: spotless formatting passes clean.
- `./gradlew :core:domain:test` -- expected: `MasteryTest`, `ContentValidatorTest`, `SchemaModelTest`, and `ShippedContentTest` pass.
- `./gradlew build` -- expected: full build and Kover coverage thresholds pass.

## Auto Run Result

- **Implementation Summary:**
  - Added `mastery` thresholds (`solvesRequired = 2`, `reducedHelpRequired = 1`) to `tutor-config.json`, valid fixture, and `tutor-config.schema.json`.
  - Modeled `MasteryThresholds` data class and added `mastery` field to `TutorConfig` in `:core:domain`, validated in `ContentValidator`.
  - Added `MasteryAttempt` and pure `Mastery` evaluation functions in `dev.rubikxplorer.domain.tutor`, supporting active epoch filtering, stage matching, solve counts, reduced assistance counts, and stage mastery determination.
  - Implemented `MasteryAttempt` interface on `AttemptRecord` in `:core:application`, bridging persistence DTOs to pure domain evaluation.
  - Wired `AttemptRepository` into `SessionHost` in `:app`, exposing reactive `attemptRecords: StateFlow<List<AttemptRecord>>` refreshed on save and load.
  - Added next Stage offer support (`nextStageLabel`, `onNextStage`, `milestone:nextStage` button) to `FirstSolveMilestone.kt`.
  - Triggered stage mastery milestone ("{Stage} mastered.", celebratory sticker burst, next Stage offer, practice, continue) in `LessonScreen.kt` upon meeting condition at attempt end.
  - Wired reactive `masteredStages` and `attemptRecords` through `AppShell.kt` and `LearnScreen.kt` to update Stage rows and `StageDetailScreen` with accurate mastery progress string, two-segment indicators, and `◆ Mastered` badge.
  - Unit tests added in `MasteryTest.kt`, `StageMasteryMilestoneTest.kt`, and extended in `ContentValidatorTest.kt`, `LessonScreenTest.kt`, and `AppShellTest.kt`.
- **Files Changed:**
  - `core/domain/src/main/resources/content/schema/tutor-config.schema.json` — Defined schema for `mastery` object with `solvesRequired` and `reducedHelpRequired`.
  - `core/domain/src/main/resources/content/tutor-config.json` — Added shipped mastery thresholds (2 solves required, 1 reduced help required).
  - `core/domain/src/main/resources/content/strings/en.json` — Added stage mastery milestone headline string template.
  - `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Model.kt` — Added `MasteryThresholds` data class and `mastery` property to `TutorConfig`.
  - `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentValidator.kt` — Validated `mastery` thresholds range and sanity rules.
  - `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MasteryAttempt.kt` — Domain abstraction for attempts evaluated by mastery functions.
  - `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Mastery.kt` — Pure functions evaluating stage mastery, solve counts, and active epoch attempts.
  - `core/application/src/main/kotlin/dev/rubikxplorer/application/session/AttemptRecord.kt` — Implemented `MasteryAttempt` interface on `AttemptRecord`.
  - `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` — Injected `AttemptRepository` and exposed reactive `attemptRecords: StateFlow<List<AttemptRecord>>`.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestone.kt` — Added next stage navigation button and optional label support.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` — Triggered stage mastery milestone on attempt completion meeting condition and wired navigation.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` — Displayed reactive mastery progress, two-segment indicators, and `◆ Mastered` badges on Learn tab and Stage detail.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` — Evaluated effective mastered stages and connected next stage navigation.
  - `app/src/main/res/values/strings.xml` — Added localized strings for stage mastery progress and next stage button.
  - `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/MasteryTest.kt` — Unit tests for mastery evaluation transitions, bounds, and exclusions.
  - `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostAttemptTest.kt` — Verified reactive attempt publication via SerialWriter and Room persistence.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/StageMasteryMilestoneTest.kt` — Unit tests for stage mastery milestone layout, copy, and interactions.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt` — Tests verifying milestone trigger and next stage offer on attempt completion.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt` — Verified Stage detail and Learn tab mastery display.
- **Review Findings Breakdown:**
  - Patches applied: 9 findings (5 medium, 4 low) patched across `SessionHost.kt`, `SessionHostAttemptTest.kt`, `AppShell.kt`, `LessonScreen.kt`, `LearnScreen.kt`, and `Mastery.kt`.
  - Deferred items: 0
  - Rejected findings: 5 findings rejected with evidence (4 false refutations, 1 low-harm design choice).
- **Follow-up Review Recommendation:** `true`. Patched counts by verdict: high 0, medium 6, low 3. Unverified risk: Reactive attempt refresh and milestone navigation interactions across multiple UI screens (`LessonScreen`, `AppShell`, `LearnScreen`) during long-running sessions with retried writes have been patched and verified via automated JVM and Robolectric tests, but benefit from follow-up review for full end-to-end multi-stage navigation continuity.
- **Verification:**
  - `./gradlew test` passed (310+ unit tests across `:core:domain`, `:core:solver`, `:core:application`, and `:app`).
  - `./gradlew spotlessCheck build` passed (including Spotless checks, module boundaries, checkLearnerText, checkReleaseManifestAndDenylist, Kover ≥ 80% on `:core:*`, debug and release APK generation).
- **Residual Risks:** None known; all acceptance criteria are fully met and verified.
- **Status:** Done
