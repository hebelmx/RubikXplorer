---
title: 'Story 7.2: Learn tab and Stage detail'
type: 'feature'
created: '2026-11-06'
baseline_revision: '43b8c974d383857868db8fd4553bf64fd4d3371f'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/AGENTS.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Learn tab currently renders a flat list of Lessons from only 3 Stage files, with no Stage rows, no Stage status text or icons, no *Faster last layer* placeholder row, and no Stage detail screen showing a Stage's Lessons, Mastery progress line, *Practice this Stage* button, and *I already know this* action (FR-4, FR-17, FR-20, AD-10, AD-17, UX-DR18, UX-DR22, UX-DR23).

**Approach:** Add the 7 Course Stages to `bundle.json` and `en.json` (Stages 1–3 with their authored Lessons, Stages 4–7 with titles and empty Lesson lists until Stories 7.3–7.4), expose reactive `progressEvents: StateFlow<List<ProgressEvent>>` on `SessionHost` backed by `ProgressRepository`, replace `LearnScreen`'s flat Lesson list with the Continue card, 7 Stage rows (number in `data`, title in `title`, status text + icon derived via `StageProgress.statuses`), and a hidden *Faster last layer* placeholder row (`showFasterLastLayer: Boolean = false`), and add `StageDetailScreen` in `LearnScreen.kt` (keeping bottom/rail navigation visible) displaying the Stage's Lessons with prerequisite gating, the Mastery progress line with a two-segment indicator, *Practice this Stage* (disabled until Story 7.4), and *I already know this* (enabled, invoked in Story 7.7).

## Boundaries & Constraints

**Always:**
- **7 Course Stages in `content/` (`:core:domain` resources):**
  - `content/bundle.json` lists all 7 Stage files in order:
    1. `stages/stage-01-fundamentals.json` (`"Cube fundamentals"`)
    2. `stages/stage-02-first-cross.json` (`"First cross"`)
    3. `stages/stage-03-first-layer-corners.json` (`"First-layer corners"`)
    4. `stages/stage-04-middle-layer.json` (`"Middle layer"`, `lessons: []`)
    5. `stages/stage-05-last-layer-orientation.json` (`"Last-layer orientation"`, `lessons: []`)
    6. `stages/stage-06-last-layer-completion.json` (`"Last-layer completion"`, `lessons: []`)
    7. `stages/stage-07-independent-practice.json` (`"Independent practice"`, `lessons: []`)
  - Add `"stage.stage-04-middle-layer.title"` .. `"stage.stage-07-independent-practice.title"` to `content/strings/en.json`.
- **Reactive Stage progress on `SessionHost` (`:app/session/`):**
  - `SessionHost` accepts optional `progress: ProgressRepository? = null` (wired in `@Inject` constructor alongside `completeLesson`) and exposes `val progressEvents: StateFlow<List<ProgressEvent>>`.
  - `restore(bundle)` loads initial `progress.all()` (via `writer?.read` when `writer != null`, else directly) and publishes `Found.events` to `progressEvents`.
  - When `completeLesson` succeeds (`ProgressWrite.Written`) in `dispatch`, or when `reloadProgress()` is called, `SessionHost` refreshes `progressEvents` from `progress.all()`.
- **Learn tab (`LearnScreen` in `:app/ui/shell/LearnScreen.kt`):**
  - Shows the Continue card (`testTag("learn:continue")`) when `continueEntry != null`.
  - Shows a Stage row (`testTag("learn:stage:${stage.id}")`) for each Stage in `catalog.bundle.stages`:
    - Stage 1-based number in `RubikTheme.textStyles.data` (`testTag("learn:stage:number:${stage.id}")`),
    - Stage title in `MaterialTheme.typography.titleLarge` (`RubikTheme.tokens.inkPrimary` when unlocked, `inkDisabled` when `LOCKED`),
    - Stage status text + icon (`testTag("learn:stage:status:${stage.id}")`, with merged semantics and `stateDescription`):
      - `LOCKED` → icon (`R.drawable.ic_stage_locked`, `inkSecondary`) + `"Locked"` (`R.string.stage_status_locked`, `inkSecondary`)
      - `IN_PROGRESS` → icon (`R.drawable.ic_stage_in_progress`, `inkSecondary`) + `"In progress"` (`R.string.stage_status_in_progress`, `inkSecondary`)
      - `COMPLETED` → icon (`R.drawable.ic_check`, `inkSecondary`) + `"Completed"` (`R.string.stage_status_completed`, `inkSecondary`)
      - `SKIPPED` → `"◇"` + `"Skipped"` (`R.string.stage_status_skipped`, `inkSecondary`)
      - `SKIPPED_BY_PLACEMENT` → `"◇"` + `"Skipped by placement"` (`R.string.stage_status_skipped_by_placement`, `inkSecondary`)
      - `MASTERED` → `"◆"` (`RubikTheme.tokens.accent`) + `"Mastered"` (`R.string.stage_status_mastered`, `RubikTheme.tokens.accent`)
    - Every Stage row has minimum touch height `Spacing.touchMin` (`48.dp`) and is tappable (`onSelectStage(stage.id)`), opening Stage detail so the Learner can inspect any Stage's Lessons, Mastery progress line, and *I already know this* action.
  - Includes a *Faster last layer* placeholder row (`testTag("learn:fasterLastLayer")`, title `R.string.learn_faster_last_layer`) that is hidden by default (`showFasterLastLayer: Boolean = false`) until Epic 9 enables it.
- **Stage detail (`StageDetailScreen` in `:app/ui/shell/LearnScreen.kt`, wired in `AppShell`):**
  - Opened when a Stage row is tapped on the Learn tab; keeps the shell's `NavigationBar` / `NavigationRail` visible (unlike an open Lesson, which is a full-screen task flow). System back and the Stage detail back/close button (`testTag("stageDetail:back")`) return to the Stage list on the Learn tab; switching to another tab and back or restoring saved state preserves navigation cleanly.
  - Shows the Stage number and title (`testTag("stageDetail:title")`, heading semantics) and status badge (`testTag("stageDetail:status")`).
  - Shows the **Mastery progress line** (`testTag("stageDetail:mastery")`) in `MaterialTheme.typography.bodySmall` (`meta`) using `R.string.stage_mastery_progress` (`"Mastery: %1$d of %2$d solves · %3$d with reduced help"`, defaulting to `0 of 2 solves · 0 with reduced help` until Story 7.5 wires `Mastery`) alongside a two-segment visual indicator (`testTag("stageDetail:mastery:segment:1")`, `testTag("stageDetail:mastery:segment:2")`).
  - Lists the Stage's Lessons (`testTag("learn:lesson:${lesson.id}")`, minimum height `Spacing.touchMin`):
    - Each row shows the Lesson Objective (`bodyLarge`), completion/lock state, and is enabled when `StageProgress.isLessonUnlocked(lesson, stages, progressEvents, masteredStages)` is `true` (disabled when locked). Tapping an unlocked Lesson calls `onOpen(lesson.id)`.
    - If a Stage has no authored Lessons yet (Stages 4–7 before Stories 7.3–7.4), shows a calm note (`testTag("stageDetail:noLessons")`).
  - Shows *Practice this Stage* (`OutlinedButton` / `Button` with `testTag("stageDetail:practice")`, label `R.string.stage_practice` = `"Practice this Stage"`), disabled (`practiceEnabled: Boolean = false` until Story 7.4).
  - Shows *I already know this* (`TextButton` / `OutlinedButton` with `testTag("stageDetail:skipCheck")`, label `R.string.stage_skip_check` = `"I already know this"`), enabled (`skipCheckEnabled: Boolean = true`, invoking `onSkipCheck(stage.id)` for Story 7.7).

**Never:**
- Never hard-code Learner-facing strings in Kotlin sources (`LearnerTextPolicy`); all UI strings go in `app/src/main/res/values/strings.xml` or `content/strings/en.json`.
- Never write to `ProgressRepository` outside the 4 single-writer use cases (`ProgressWriterTest`).
- Never author Stages 4–6 Lesson steps/predicates (Story 7.3), start a `PRACTICE` session (Story 7.4), evaluate `Mastery` over `attempt` rows (Story 7.5), implement the You tab or Settings progress reset (Story 7.6), or implement the skip-ahead check session flow (Story 7.7).
- Never hide the bottom/rail navigation bar when viewing Stage detail (only an open Lesson hides the navigation bar).
- Never edit planning artifacts, `_bmad/`, or `.agents/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Learn tab opens (fresh epoch) | Shipped 7-Stage bundle, empty `progress_event` | Stage 1 shows `1`, `"Cube fundamentals"`, `"In progress"` + icon; Stages 2–7 show `2`..`7`, titles, `"Locked"` + icon; *Faster last layer* (`learn:fasterLastLayer`) does not exist | — |
| Continue card on Learn tab | Active or restored `LESSON` session | `learn:continue` card renders above the 7 Stage rows; tapping it opens that Lesson | — |
| Faster last layer placeholder | `showFasterLastLayer = false` (default) vs `true` | Hidden when `false`; renders `learn:fasterLastLayer` row when `true` | — |
| Stage row tapped | Learner taps Stage 1 (`learn:stage:stage-01-fundamentals`) | Opens `stageDetail` with nav bar still visible; shows Stage 1's 3 Lessons (`lesson-01-01` enabled, `01-02` and `01-03` disabled), `"Mastery: 0 of 2 solves · 0 with reduced help"`, disabled *Practice this Stage*, enabled *I already know this* | — |
| Locked Stage tapped | Learner taps Stage 2 (`learn:stage:stage-02-first-cross`) on fresh epoch | Opens `stageDetail` for Stage 2; all its Lessons are disabled (`isLessonUnlocked == false`); *Practice this Stage* disabled; *I already know this* enabled | — |
| All 6 Stage statuses rendered | `statuses` contains `LOCKED`, `IN_PROGRESS`, `COMPLETED`, `SKIPPED`, `SKIPPED_BY_PLACEMENT`, `MASTERED` | Each Stage row and Stage detail displays its exact state text + icon (`Locked`, `In progress`, `Completed`, `◇` + `Skipped`, `◇` + `Skipped by placement`, `◆` + `Mastered` in `accent`) | — |
| Completing a Lesson updates Learn/Stage detail | Learner completes `lesson-01-01` in `LessonScreen` and taps Next | Returns to Stage 1 detail; `SessionHost.progressEvents` reflects `LessonCompleted(lesson-01-01)` and `lesson-01-02` becomes enabled | — |
| Back from Stage detail | System back or `stageDetail:back` tapped | Returns to Learn tab Stage list (`learn:stage:*` visible, `stageDetail` gone) | — |
| Empty or failed catalog | `CatalogState.Ready` with empty stages or `CatalogState.Failed` | Shows `learn:empty` or `learn:error` without crashing | — |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/bundle.json` -- list all 7 Stage JSON files in order (`stage-01-fundamentals.json` .. `stage-07-independent-practice.json`).
- `core/domain/src/main/resources/content/stages/stage-0{4-middle-layer,5-last-layer-orientation,6-last-layer-completion,7-independent-practice}.json` (new) -- Stage JSON files with `id`, `title`, and `"lessons": []` (valid against `stage.schema.json` and `ContentValidator`).
- `core/domain/src/main/resources/content/strings/en.json` -- add `"stage.stage-04-middle-layer.title"` .. `"stage.stage-07-independent-practice.title"`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt` -- update Stage count/IDs assertion from Stages 1–3 to Stages 1–7 (`listOf(3, 5, 4, 0, 0, 0, 0)`).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageProgressTest.kt` & `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StageProgressUseCasesTest.kt` -- update any full-`stages` status map assertions to account for Stages 4–7 being `LOCKED`.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- inject optional `progress: ProgressRepository? = null`, expose `progressEvents: StateFlow<List<ProgressEvent>>`, load in `restore()`, and refresh after `completeLesson` writes or `reloadProgress()`.
- `app/src/main/res/drawable/ic_stage_{locked,in_progress}.xml` (new) & `app/src/main/res/values/strings.xml` -- vector icons for `Locked` and `In progress`, and string resources for Stage statuses, Mastery progress line, *Faster last layer*, *Practice this Stage*, *I already know this*, and Stage detail back/empty copy.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` -- replace flat Lesson list with Stage rows (`learn:stage:<stageId>`), Continue card (`learn:continue`), hidden *Faster last layer* placeholder (`learn:fasterLastLayer`), and `StageDetailScreen` (`stageDetail`, `stageDetail:mastery`, `stageDetail:practice`, `stageDetail:skipCheck`, `learn:lesson:<lessonId>`).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- hold `selectedStage: String?` in `rememberSaveable`, pass `progressEvents` from `host.progressEvents`, keep nav bar/rail visible on Stage detail, and wire back navigation (`Lesson` → `StageDetail` or `Learn` → exit).
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt` & `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt` -- update existing shell navigation helpers (tap Stage row then Lesson row, or unlock Lesson in test setup) and add comprehensive tests for Story 7.2 ACs and I/O matrix rows.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/{bundle.json,strings/en.json,stages/stage-04-middle-layer.json,stages/stage-05-last-layer-orientation.json,stages/stage-06-last-layer-completion.json,stages/stage-07-independent-practice.json}` -- register all 7 Course Stages in `bundle.json` and `en.json` with empty lesson lists for Stages 4–7 -- required so the Learn tab shows all seven Stage rows from content data (AD-6).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt`, `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageProgressTest.kt`, `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StageProgressUseCasesTest.kt` -- update shipped Stage count assertions from 3 to 7 -- keeps pure JVM content and progress tests aligned with the 7-Stage bundle.
- `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` -- add `progress: ProgressRepository? = null` and `progressEvents: StateFlow<List<ProgressEvent>>` loaded on `restore()` and refreshed when `completeLesson` writes -- supplies reactive Stage progress to the UI without a second writer.
- `app/src/main/res/drawable/{ic_stage_locked.xml,ic_stage_in_progress.xml}` & `app/src/main/res/values/strings.xml` -- add vector icons and string resources for Stage statuses, Mastery line, *Practice this Stage*, *I already know this*, and *Faster last layer* -- satisfies AD-12 (`LearnerTextPolicy`) and AD-17 (status icon + text).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/{LearnScreen.kt,AppShell.kt}` -- implement Stage rows with status text + icon, hidden *Faster last layer* placeholder row, and `StageDetailScreen` (Lessons list with prerequisite gating, Mastery progress line + 2-segment indicator, disabled *Practice this Stage*, enabled *I already know this*) integrated into `AppShell` -- implements Story 7.2 ACs 1 and 2.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt`, `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt`, `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostProgressTest.kt` -- test all I/O & Edge-Case Matrix rows (7 Stage rows, Continue card, hidden/shown *Faster last layer*, all 6 `StageStatus` text + icon treatments, Stage detail Lessons/Mastery/Practice/Skip-check controls, live Lesson completion unlocking next Lesson, and back/state restoration) -- verifies Story 7.2 end-to-end.

**Acceptance Criteria:**
- Given the Learn tab, when it opens, then it shows the Continue card (when a Lesson is resumable), the seven Stage rows with state text + icon, and a *Faster last layer* placeholder row hidden until Epic 9.
- Given a Stage row, when tapped, then Stage detail shows its Lessons, the Mastery progress line, *Practice this Stage* (disabled until Story 7.4) and *I already know this* (enabled in Story 7.7).

## Spec Change Log

## Review Triage Log

### 2026-11-06 — Review pass
- verdicts: 18 findings — high 0, medium 1, low 12, false 5, maybe-false 0
- findings:
  - `[low]` `[reject]` `StageRow` sets `SemanticsProperties.Text` and `stateDescription` while child `learn:stage:number:<id>` and `learn:stage:status:<id>` set `mergeDescendants = true` — Rejected as low because the merged semantics structure is intentional so merged-tree `onNodeWithTag` queries on the Stage row, number, and status badge all work simultaneously, and restructuring it adds complexity for negligible everyday impact.
  - `[low]` `[reject]` `StageStatusBadge` renders `"◇"` / `"◆"` as `Text` nodes alongside `statusLabel` and `stateDescription` — Rejected as low because the spec requires `"◇"` and `"◆"` text symbols in `StageStatusBadge` and hiding them from semantics would break merged-tree text assertions while adding complexity.
  - `[low]` `[reject]` `StageDetailScreen` uses an unhoisted `rememberScrollState()` when opening/closing a Lesson or Settings — Rejected as low because a Stage has at most 5 Lessons (which fit without scrolling on standard screens) and hoisting a second scroll state across `AppShell` and `LearnScreen` adds parameters for negligible everyday benefit.
  - `[low]` `[reject]` Tapping the Learn navigation bar/rail item while already on `Tab.LEARN` viewing `StageDetailScreen` does not reset `selectedStage` to `null` — Rejected as low because `StageDetailScreen` provides an explicit `stageDetail:back` button and `BackHandler` to return to the Stage list, while tab-switching to another tab and back is required to preserve `selectedStage`.
  - `[false]` `[reject]` `BackHandler` in `AppShell` stays enabled when `selectedStage` does not match any loaded Stage — Refuted because in `AppShell`, `selectedStage` starts `null` and is only ever assigned from a `StageRow` rendered from `catalog.bundle.stages` when `catalog` is `CatalogState.Ready`, so `selectedStage` cannot hold an invalid Stage ID.
  - `[false]` `[reject]` `StageListContent` shows `learn:empty` when `catalog.lessons.isEmpty()` — Refuted because `testBundle(*emptyArray())` in the pre-existing `AppShellTest` (`an empty bundle shows the empty line`) constructs a `ContentBundle` with a Stage whose `lessons` list is empty and requires `learn:empty` to be displayed when the bundle has zero lessons across all stages.
  - `[low]` `[reject]` Mastery progress bar segments (`stageDetail:mastery:segment:1`, `stageDetail:mastery:segment:2`) expose no separate semantics and segment fill transitions are not asserted — Rejected as low because `stageDetail:mastery` already exposes the full Mastery text semantics and Mastery evaluation/progression is scoped to Story 7.5.
  - `[low]` `[patch]` `AppShellTest` test `"all six StageStatus values render their exact state text and icon on Learn and Stage detail"` does not navigate into `StageDetailScreen` to verify `stageDetail:status` for the six statuses — Patched `AppShellTest.kt` to tap each Stage row and assert `stageDetail:status` text, symbol, and `StateDescription` in `StageDetailScreen` for all six `StageStatus` values.
  - `[low]` `[patch]` `SessionHost.refreshProgressAfterWrite` leaves `currentProgress.value` stale if `repo != null` and `repo.all()` returns `ProgressLoad.Failed` — Patched `SessionHost.kt` to fall back to `currentProgress.value = currentProgress.value + written` when `loaded !is ProgressLoad.Found`.
  - `[low]` `[reject]` `SessionHost.reloadProgress()` and `refreshProgressAfterWrite()` update `currentProgress.value` without `withContext(confined)` — Rejected as low because `MutableStateFlow.value` assignment is thread-safe and production reads/writes are serialized through `SnapshotWriter`.
  - `[low]` `[reject]` `LessonRow` in `StageDetailScreen` repeats `strings.resolve(stage.title)` beneath the Lesson Objective — Rejected as low because the pre-existing `AppShellTest` asserts that `learn:lesson:lesson-01-01` contains `STAGE_TITLE` and the subtitle is harmless.
  - `[false]` `[reject]` `StageStatusBadge` uses `inkSecondary` for `LOCKED`, `StageDetailScreen` uses `inkSecondary`/`inkPrimary` for a locked Stage header, and Stage detail omits the Trend row — Refuted because `epic-7-context.md` and `UX-DR18` specify `inkSecondary` for `LOCKED` status badges (`accent` only for `MASTERED`) and `epics.md` assigns Trend rows to Story 7.6.
  - `[low]` `[patch]` `SessionHost.refreshProgressAfterWrite` when `progress` repository is present and `repo.all()` returns `ProgressLoad.Failed` leaves `progressEvents` stale in memory (grouped with BH-9) — Patched `SessionHost.kt` to append `written` to `currentProgress.value` when `loaded !is ProgressLoad.Found`.
  - `[false]` `[reject]` `AppShell.kt` `BackHandler(enabled = tab != Tab.LEARN || selectedStage != null)` when `selectedStage` is non-null while `catalog` is not `Ready` or Stage ID is missing (same claim as BH-5) — Refuted because `selectedStage` in `AppShell` starts `null` and is only set by clicking a `StageRow` rendered from `catalog.bundle.stages` when `catalog` is `CatalogState.Ready`.
  - `[medium]` `[patch]` `StageDetailScreen` per-Lesson completion and lock `StateDescription` (`"Completed"`, `"In progress"`, `"Locked"`) on `learn:lesson:<id>` is not asserted in `AppShellTest` — Patched `AppShellTest.kt` (`completing Lesson 1 in LessonScreen and tapping Next returns to Stage detail and unlocks Lesson 2`) to assert `SemanticsProperties.StateDescription` on `learn:lesson:lesson-01-01`, `learn:lesson:lesson-01-02`, and `learn:lesson:lesson-01-03` before and after completing Lesson 1.
  - `[low]` `[patch]` `AppShellTest` test `"tapping a Stage row opens Stage detail with Lessons, Mastery line, disabled Practice and enabled Skip check"` mounts `LearnScreen` with `selectedStage` pre-set instead of tapping the Stage row (grouped with BH-8) — Patched `AppShellTest.kt` to start with `selectedStage = null`, tap `learn:stage:stage-01-fundamentals`, and verify `StageDetailScreen` opens.
  - `[low]` `[patch]` `AppShellTest` test `"all six StageStatus values render their exact state text and icon on Learn and Stage detail"` only checks `learn:stage:status:*` on the Learn list and never checks `stageDetail:status` in `StageDetailScreen` (grouped with BH-8) — Patched `AppShellTest.kt` to open each Stage in `StageDetailScreen` and assert `stageDetail:status` for all six `StageStatus` values.
  - `[false]` `[reject]` `StageDetailScreen` omits the Trend row, renders Stage title in `titleLarge`, and renders `stageDetail:noLessons` for Stages 4–7 with empty `lessons: []` — Refuted because `epics.md` scopes Stages 4–6 Lessons to Story 7.3, Stage 7 Practice to Story 7.4, and Trend rows to Story 7.6, while `UX-DR18` specifies `title` (`titleLarge`) for Stage names.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: pass (all JVM/Robolectric tests, Kover ≥ 80% on `:core:*`, LearnerTextPolicy, module boundaries, manifest/SDK denylist checks, debug and release APKs).

## Auto Run Result

- **Summary of implemented change:** Added the 7 Course Stages to `bundle.json` and `en.json` (Stages 1–3 with their authored Lessons, Stages 4–7 with titles and empty Lesson lists awaiting Stories 7.3–7.4), wired reactive `progressEvents: StateFlow<List<ProgressEvent>>` on `SessionHost` backed by `ProgressRepository`, replaced `LearnScreen`'s flat Lesson list with the Continue card, 7 Stage rows (number in `data`, title in `title`, status text + icon derived via `StageProgress.statuses`), and a hidden *Faster last layer* placeholder row (`showFasterLastLayer = false`), and added `StageDetailScreen` integrated into `AppShell` (preserving bottom/rail navigation) showing the Stage's Lessons with prerequisite gating, the Mastery progress line with a two-segment indicator, disabled *Practice this Stage*, and enabled *I already know this*.
- **Files changed:**
  - `core/domain/src/main/resources/content/bundle.json` — registered all 7 Course Stage JSON files in order.
  - `core/domain/src/main/resources/content/stages/stage-04-middle-layer.json` — added Stage 4 definition with empty `lessons` list.
  - `core/domain/src/main/resources/content/stages/stage-05-last-layer-orientation.json` — added Stage 5 definition with empty `lessons` list.
  - `core/domain/src/main/resources/content/stages/stage-06-last-layer-completion.json` — added Stage 6 definition with empty `lessons` list.
  - `core/domain/src/main/resources/content/stages/stage-07-independent-practice.json` — added Stage 7 definition with empty `lessons` list.
  - `core/domain/src/main/resources/content/strings/en.json` — added English title strings for Stages 4–7.
  - `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt` — updated shipped Stage count and ID assertions to cover Stages 1–7.
  - `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageProgressTest.kt` — updated full-bundle Stage status assertions for Stages 4–7.
  - `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StageProgressUseCasesTest.kt` — updated full-bundle Stage status assertions for Stages 4–7.
  - `app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt` — injected `ProgressRepository` and exposed `progressEvents: StateFlow<List<ProgressEvent>>` loaded on `restore()` and refreshed on Lesson completion.
  - `app/src/main/res/drawable/ic_stage_locked.xml` — added vector drawable for locked Stage/Lesson status.
  - `app/src/main/res/drawable/ic_stage_in_progress.xml` — added vector drawable for in-progress Stage/Lesson status.
  - `app/src/main/res/values/strings.xml` — added string resources for Stage statuses, symbols, Mastery progress line, *Faster last layer*, *Practice this Stage*, and *I already know this*.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt` — implemented `StageListContent`, `StageRow`, `FasterLastLayerRow`, `StageDetailScreen`, and `StageStatusBadge`.
  - `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` — wired `selectedStage`, `progressEvents`, and back navigation for Stage detail while keeping the navigation bar/rail visible.
  - `app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostProgressTest.kt` — verified `SessionHost.progressEvents` on fresh epoch, Lesson completion, and restore across 7 Stages.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt` — updated shell navigation tests to open Stage 1 detail before selecting Lesson 1.
  - `app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt` — added tests covering all Story 7.2 acceptance criteria and I/O matrix rows.
- **Review findings breakdown:**
  - Patched entries by verdict: high 0, medium 1, low 2 (5 findings across 3 grouped entries: `SessionHost.refreshProgressAfterWrite` fallback when `repo.all()` fails; `LessonRow` `StateDescription` assertions in `AppShellTest`; `StageDetailScreen` status and Stage-row tap assertions in `AppShellTest`).
  - Deferred items: 0.
  - Rejected findings: 13 (8 `low` rejected because they had negligible everyday impact and fixing them would add unnecessary complexity or break merged-tree test tag queries; 5 `false` refuted against `AppShell` state invariants, `AppShellTest` fixtures, `epic-7-context.md`, and `epics.md` story boundaries).
- **Follow-up review recommendation:** `false` (patched counts by verdict: high 0, medium 1, low 2; no unverified risk remains).
- **Verification performed:**
  - `./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest` — passed.
  - `./gradlew spotlessCheck build` — passed (compile, lint, JVM and Robolectric tests, Kover ≥ 80% on `:core:*`, `checkLearnerText`, `checkModuleBoundaries`, manifest/SDK denylist checks, debug and release APKs).
- **Residual risks:** Stages 4–7 currently have empty `lessons: []` until Story 7.3 authors Stages 4–6 Lessons and Story 7.4 enables Stage practice; *Practice this Stage* (`practiceEnabled = false`), Mastery solve counts (`0 of 2 solves · 0 with reduced help`), and *I already know this* (`onSkipCheck`) will be connected to their respective domain/application flows in Stories 7.4, 7.5, and 7.7.
