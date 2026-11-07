---
title: 'Story 7.6: Progress on the You tab, with reset'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: '1b37411d5d0f2af9b09131bde26091c85371d53f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-learn-tab-and-stage-detail.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-5-mastery-condition-and-milestone.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Learners lack visibility into their growing independence because the You tab is an unpopulated placeholder without Stage progression or hint-use trends, and Settings lacks a scoped progress reset mechanism to start over cleanly without destroying preferences or calibration (FR-19, FR-20, AD-10, UX-DR18, UX-DR25, UX-DR28, UX Flows 7, 9, SM-5, SM-C1).

**Approach:** Implement `StageTrend` pure functions in `:core:domain` over active-epoch attempts and events to extract date-ordered hint counts and attempt totals; build a Compose `DotSparkline` and `TrendRow` component showing the dot sparkline in `ink-secondary` with latest point emphasized and attempt count in `data` (no totals, scores, or red); populate `YouScreen` with each Stage's title, derived status badge, and trend row; integrate `TrendRow` into `StageDetailScreen`; wire a *Reset progress* action and confirmation dialog in `SettingsScreen` to invoke `ResetProgress(at = clock.now())` via `SessionHost`, appending `ProgressReset(epoch)` to Room persistence while leaving calibration and preferences untouched.

## Boundaries & Constraints

**Always:**
- Keep `:core:domain` pure JVM with no dependencies on Android, Compose, Room, or Hilt.
- Maintain Stage status derivation and hint trend evaluation as pure functions over current-epoch `ProgressEvent`s and `MasteryAttempt`s.
- Isolate attempts strictly by epoch (`it.at > lastReset.at`).
- Render the trend row with a dot sparkline of hints per attempt in date order, with the latest point emphasized, beside attempt count in `data` (monospace tabular).
- Render `SettingsScreen` reset progress confirmation with a sheet/dialog naming exactly what is erased (Stages and attempts) and confirming that calibration and preferences remain untouched.
- Invoke `ResetProgress` through `SessionHost` on the serial writer off the main thread, updating reactive `progressEvents` upon success.
- Enforce accessibility: all interactive controls have touch targets >= 48dp and appropriate content descriptions / semantic descriptions for TalkBack.

**Never:**
- Never display hint totals as a score, never frame hint use as failure, and never display red or error styling in the trend row or You tab (SM-C1, UX-DR18, UX-DR28).
- Never include streaks, points, XP, or gamification badges (SM-C1, SM-C3, UX-DR28).
- Never mutate, delete, or overwrite `progress_event` rows on reset; `ResetProgress` strictly appends `ProgressReset(epoch)`.
- Never reset or alter preferences or color calibration when resetting progress.
- Never add network, analytics, or crash SDKs.
- Never weaken or delete existing tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Zero attempts for Stage | Stage unlocked or locked, 0 attempts in epoch | `attemptsCount = 0`, `hintCounts = emptyList()`; Sparkline renders empty baseline/space; attempt count displays "0"; status badge reflects derived StageStatus | No error |
| Single attempt | 1 completed attempt with 3 hints | Sparkline displays 1 dot at centered X, latest emphasized; attempt count displays "1" | No error |
| Multiple attempts falling | 5 attempts in date order with hints [3, 2, 2, 1, 1] | Sparkline displays 5 dots in chronological order connected with hairline trend; final dot at hint = 1 emphasized; attempt count "5" | No error |
| Zero hints on attempts | 3 attempts each with 0 hints | Sparkline displays 3 dots along 0-hint baseline; latest emphasized; attempt count "3" | No error |
| Epoch isolation after reset | 3 attempts in epoch 0, then `ProgressReset(epoch = 1)` | In epoch 1, attempts count resets to 0 and sparkline is empty; prior attempts remain in DB | No error |
| Reset Progress confirmation | User clicks *Reset progress* in Settings | Confirmation dialog opens stating Stages and attempts will be erased and preferences/calibration kept | Dismissing dialog cancels with no change |
| Confirm Reset Progress | User taps confirm in dialog | `ResetProgress` appends `ProgressReset(nextEpoch, now)`; `SessionHost` refreshes `progressEvents`; Stage 1 reverts to `IN_PROGRESS`, Stages 2-7 revert to `LOCKED`; preferences untouched | If DB write fails, returns failure without altering state |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MasteryAttempt.kt` -- Add `val hints: Int? get() = null` property to `MasteryAttempt` interface and `StageAttempt` data class so attempt records expose hint counts to pure domain functions.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageTrend.kt` (new) -- Pure `StageTrend` data model and `StageTrendCalculator` evaluating date-ordered hints and attempt counts per stage for the current epoch.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageTrendTest.kt` (new) -- Unit tests for `StageTrendCalculator` verifying zero attempts, chronological ordering, hint extraction, epoch isolation across resets, and stage matching.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- Expose `suspend fun resetProgress(): Boolean` to dispatch `ResetProgress(at = clock.now())` via `writer` and refresh `progressEvents`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/DotSparkline.kt` (new) -- Reusable Compose Canvas component drawing the dot sparkline in `ink-secondary` with the latest point emphasized.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/TrendRow.kt` (new) -- Reusable Compose component rendering Stage name (or label), `DotSparkline`, and attempt count in `data`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/YouScreen.kt` -- Replace placeholder with full You tab screen rendering all Stages with status badges and `TrendRow`, plus Settings access.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- Embed `TrendRow` in `StageDetailScreen` for the active Stage.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreen.kt` -- Add *Reset progress* button and confirmation dialog detailing erased items and untouched preferences/calibration.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- Wire `content`, `progressEvents`, and `attemptRecords` to `YouScreen`, and pass `onResetProgress` to `SettingsScreen`.
- `app/src/main/res/values/strings.xml` -- Add strings for reset progress title, description, and confirm button.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/YouScreenTest.kt` (new) -- Compose tests verifying You tab stages, status badges, trend rows, attempt counts, and settings navigation.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreenTest.kt` -- Extend existing tests to verify *Reset progress* confirmation dialog, dismissal, confirmation, and state preservation.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MasteryAttempt.kt` -- Add `hints: Int?` to `MasteryAttempt` and `StageAttempt` -- Enables domain-level access to attempt hint counts.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageTrend.kt` -- Create `StageTrend` and `StageTrendCalculator` -- Pure domain evaluation of stage attempt counts and date-ordered hint history.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageTrendTest.kt` -- Add unit tests for `StageTrendCalculator` -- Verifies epoch isolation, ordering, and edge cases under pure JVM.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- Implement `resetProgress()` -- Provides single-entry point for resetting progress and refreshing reactive state.
- `app/src/main/res/values/strings.xml` -- Add localized strings for reset progress -- Provides accessible copy adhering to UX-DR25.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/DotSparkline.kt` -- Create `DotSparkline` canvas component -- Renders dots in `ink-secondary` with latest emphasized and no red.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/TrendRow.kt` -- Create `TrendRow` composable -- Displays Stage trend row and tabular monospace attempt count.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/YouScreen.kt` -- Implement full You tab UI -- Shows each Stage's state, name, and trend row.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- Add `TrendRow` to `StageDetailScreen` -- Aligns Stage detail with DESIGN.md specifications.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreen.kt` -- Add reset progress trigger and confirmation dialog -- Ensures explicit confirmation naming erased items.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- Wire `SessionHost`, `progressEvents`, `attemptRecords`, and `onResetProgress` -- Integrates screens into top-level navigation.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/YouScreenTest.kt` -- Create Compose unit tests for `YouScreen` and `TrendRow` -- Verifies rendering, accessibility, and tags.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreenTest.kt` -- Add tests for reset progress flow -- Verifies dialog confirmation, cancellation, and repo execution.

**Acceptance Criteria:**
- Given the You tab, when it opens, then each Stage shows its state and a trend row (dot sparkline of hints per attempt in date order, latest emphasized, attempt count), with no totals, scores or red.
- Given *Reset progress* in Settings, when the confirmation sheet lists exactly what is erased (Stages, attempts) and is confirmed, then `ResetProgress` appends `ProgressReset(epoch)`, and calibration and preferences are untouched.

## Spec Change Log

## Review Triage Log

### 2026-11-07 — Review pass
- verdicts: 14 findings — high 0, medium 2, low 9, false 3, maybe-false 0
- findings:
  - `[medium]` `[patch]` Unverified TrendRow integration on StageDetailScreen — patched `AppShellTest.kt` with assertions verifying that when Stage 2 detail is open with attempt records, `stageDetail:trend` is displayed and shows the attempt count.
  - `[medium]` `[patch]` Epoch isolation in YouScreen attempt counts is unverified by YouScreenTest — patched `YouScreenTest.kt` with a test supplying `ProgressEvent.ProgressReset` in `progressEvents` and asserting that attempts prior to the reset timestamp are excluded from the displayed trend row count.
  - `[false]` `[reject]` Reset Progress confirmation dialog copy omits Algorithm Statuses — refuted because Algorithm Statuses do not exist until Epic 9; Story 7.6 AC explicitly specifies listing Stages and attempts.
  - `[false]` `[reject]` Mutating use case executed via SerialWriter.read — refuted because `SerialWriter.read` is the designated suspend method for synchronously executing operations on the serial worker and obtaining the result.
  - `[false]` `[reject]` Progress refresh bypasses SerialWriter queue coordination — refuted; identical architectural parity with `refreshProgressAfterWrite` and `refreshAttemptsAfterWrite` established and verified in Story 7.1 and 7.5 to prevent channel self-deadlock.
  - `[low]` `[patch]` Active session, checkpoint, and navigation state not invalidated on progress reset — patched `AppShell.kt` to clear `selectedStage = null` on progress reset.
  - `[low]` `[patch]` Sparkline canvas lacks attempt windowing or downsampling for large counts — patched `DotSparkline.kt` to window sparkline points to `takeLast(20)` while keeping total `attemptsCount` intact.
  - `[low]` `[patch]` Vertical scaling collapses when attempt hint counts are identical and non-zero — patched `DotSparkline.kt` to set a minimum scale ceiling of 3 for `maxHints`.
  - `[low]` `[patch]` Zero attempts renders blank space rather than a subtle baseline — patched `DotSparkline.kt` to draw a subtle muted baseline when `hintCounts.isEmpty()`.
  - `[low]` `[patch]` Incomplete and redundant screen reader semantics in TrendRow — patched `TrendRow.kt` to include latest hint count in TalkBack description when attempts exist ("Trend: %1$d attempts, latest %2$d hints") and empty description when 0 attempts.
  - `[low]` `[patch]` Hardcoded test tags at the end of modifier chains in reusable trend components — patched `TrendRow.kt` to clean modifier chain so caller's testTag is preserved without collision.
  - `[low]` `[patch]` Missing Stage number and layout divergence on the You tab — patched `YouScreen.kt` to display 1-based stage numbers in `data` style before each stage title.
  - `[low]` `[patch]` Unlabelled trend data in StageDetailScreen — patched `LearnScreen.kt` to pass `label = stringResource(R.string.stage_trend_label)` and `Modifier.testTag("stageDetail:trend")` to `TrendRow`.
  - `[low]` `[patch]` Silent empty state when catalog fails to load in YouScreen — patched `YouScreen.kt` to render `Message(R.string.learn_error, "you:error")` when `catalog is CatalogState.Failed`.

## Design Notes

- `DotSparkline` calculates points from `hintCounts`:
  - Normalized across max hints in the series (`maxHints = maxOf(hintCounts.maxOrNull() ?: 1, 1)`).
  - Heights map non-linearly or linearly within bounded canvas height (e.g. 20.dp).
  - Normal dots have radius 2.5.dp in `tokens.inkSecondary`; the latest point has radius 4.5.dp in `tokens.accent` or `tokens.inkPrimary`.
  - A subtle connecting line (`tokens.inkSecondary.copy(alpha = 0.35f)`) links consecutive dots chronologically.
  - No totals, no scores, no red.
- `StageTrendCalculator.evaluate`:
  - Filters attempts to active epoch using `Mastery.currentEpochAttempts(attempts, events)`.
  - Matches attempts to stage if `attempt.objectiveId == "practice-${stage.id.value}" || attempt.objectiveId == stage.id.value || stage.lessons.any { it.id.value == attempt.objectiveId }`.
  - Sorts matching ended attempts chronologically by `it.at`.
  - Extracts `hintCounts = matching.map { it.hints ?: 0 }` and `attemptsCount = matching.size`.

## Verification

**Commands:**
- `./gradlew :core:domain:test` -- expected: `StageTrendTest` and all domain tests pass.
- `./gradlew test` -- expected: all JVM and Robolectric tests pass.
- `./gradlew spotlessCheck` -- expected: spotless formatting passes clean.
- `./gradlew build` -- expected: full build, Kover gates (>= 80% on core modules), and APK assembly succeed.

## Auto Run Result

- **Implementation Summary:**
  - Added pure `StageTrend` data model and `StageTrendCalculator` in `dev.rubikxplorer.domain.tutor` evaluating chronological hint counts and attempt totals per stage filtered by the active epoch.
  - Added `val hints: Int?` to `MasteryAttempt` and implemented `override val hints: Int?` on `StageAttempt` and `AttemptRecord`.
  - Added `suspend fun resetProgress(): Boolean` to `SessionHost`, delegating to `ResetProgress(at = clock.now())` on the serial writer off the main thread and updating reactive `progressEvents`.
  - Created Compose components `DotSparkline` and `TrendRow` rendering hint trends in `ink-secondary` with latest point emphasized in `accent` (and muted baseline for empty attempts), beside attempt count in `data` font style, with no totals, scores, or red.
  - Populated `YouScreen` with 1-based stage numbers, Stage titles, `StageStatusBadge`s, `TrendRow`s, and Settings button (`you:settings`).
  - Integrated `TrendRow` into `StageDetailScreen` for the active Stage.
  - Added *Reset progress* button and confirmation dialog in `SettingsScreen` naming erased items (Stages and attempts) and confirming calibration and preferences are kept.
  - Added comprehensive test coverage in `StageTrendTest`, `SessionHostProgressTest`, `SettingsScreenTest`, `YouScreenTest`, and `AppShellTest`.
- **Files Changed:**
  - `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MasteryAttempt.kt` — Added `hints: Int?` property to interface and `StageAttempt`.
  - `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageTrend.kt` — Pure `StageTrend` and `StageTrendCalculator` functions.
  - `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageTrendTest.kt` — Unit tests for domain trend calculation and epoch isolation.
  - `core/application/src/main/kotlin/dev/rubikxplorer/application/session/AttemptRecord.kt` — Added `override` to `hints` property to implement `MasteryAttempt`.
  - `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` — Added `resetProgress()` executing `ResetProgress` use case.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/DotSparkline.kt` — Canvas sparkline drawing dots in `ink-secondary` with latest point emphasized.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/TrendRow.kt` — Reusable trend row with sparkline and attempt count in `data`.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/YouScreen.kt` — Full You tab implementation showing stage status badges and trend rows.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` — Embedded `TrendRow` into `StageDetailScreen`.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreen.kt` — Added reset progress action and confirmation dialog.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` — Wired `content`, `progressEvents`, `attemptRecords`, and `onResetProgress`.
  - `app/src/main/res/values/strings.xml` — Localized strings for reset progress dialog and trend descriptions.
  - `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostProgressTest.kt` — Test for SessionHost progress reset and state refresh.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreenTest.kt` — Tests for reset progress confirmation, dismissal, and preference preservation.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/YouScreenTest.kt` — Tests for You tab stages, badges, trend rows, attempt counts, and sparklines.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt` — Integration test for stage detail trend row display.
- **Review Findings Breakdown:**
  - 11 patches applied: 2 medium verification gaps (`AppShellTest` stage detail trend assertion, `YouScreenTest` epoch isolation test), 9 low quality and accessibility improvements (DotSparkline windowing, minimum scale ceiling, muted empty baseline, TrendRow semantics, clean testTag chaining, 1-based stage numbers in YouScreen, stage detail trend label and tag, AppShell selectedStage reset, YouScreen catalog failed error message).
  - 0 items deferred.
  - 3 rejected findings: Algorithm Statuses omission in reset copy (refuted: Algorithm Statuses belong to Epic 9 and do not exist yet; AC explicitly specifies Stages and attempts), SerialWriter.read usage for reset use case (refuted: SerialWriter.read is the designated suspend method for synchronously executing work on the serial queue and returning the result), refreshProgressAfterWrite direct DB read (refuted: prevents channel self-deadlock established in Story 7.5).
- **Follow-up Review Recommendation:**
  - `true` — 2 medium findings patched in pass 1 (`AppShellTest` stage detail trend integration and `YouScreenTest` epoch isolation assertion). While all tests passed clean, follow-up review is recommended per protocol for first-pass runs patching two or more medium findings.
- **Verification Performed:**
  - `./gradlew :core:domain:test` — passed clean.
  - `./gradlew checkLearnerText` — passed clean.
  - `./gradlew test` — passed clean across all modules.
  - `./gradlew spotlessCheck` — passed clean.
  - `./gradlew build` — passed clean (compile, lint, JVM tests, Robolectric tests, Kover coverage >= 80% on core modules, debug/release/benchmark APKs).
- **Residual Risks:**
  - None. All acceptance criteria and verification gates are satisfied.
