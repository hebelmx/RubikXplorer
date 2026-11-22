---
title: 'Story 14.1: Choose my entry level when I can already solve it'
type: 'feature'
created: '2026-11-22'
status: done
review_loop_iteration: 0
baseline_revision: 6dc8f83ef19e5d62dc12afc2140e9108df716aff
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Tests do not cover the resets that end a Placement run started from Learn (tab change, Idle after a run).
    evidence: |-
      Unverified. Settle with a Robolectric test: scan from the Find my place sheet, switch tab, deliver ScanOutcome.Accepted, assert placement:screen does not exist.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt (LaunchedEffect(tab), LaunchedEffect(placementPhase))
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** A Learner who can already solve must pass the whole-Course scramble check (Story 7.7) to skip ahead; there is no way to say "I solve with the beginner method" or "I know 2-look" and start there (FR-4, #209).

**Approach:** Add the entry-level picker ("Where do you want to start?") reached from Welcome → *I can already solve it* and from a new Learn top-app-bar *Find my place* sheet → *Choose my level*; record a choice through a new single-writer use case `ChooseEntryLevel` that appends the new `StageSkippedByChoice` event (only add, never downgrade); keep the Story 7.7 whole-Course check as the fourth choice.

Binding ADs (story Refs line, verbatim): **AD-6, AD-10, AD-18**. UX: UX-DR18, UX-DR22, UX-DR25, UX Flow 8. FRs: FR-4, FR-17, FR-19.

## Boundaries & Constraints

**Always:**
- `ChooseEntryLevel` is the only writer of `StageSkippedByChoice` (Ownership Matrix); it appends `StageSkippedByChoice` only for earlier Stages whose status (on the loaded log) is `LOCKED` or `IN_PROGRESS`, and `StageUnlocked` only for a target Stage not yet unlocked; never `ProgressReset`, never touches Lesson completions, Checkpoints, attempts or Algorithm Statuses (AD-10).
- `StageProgress` treats `StageSkippedByChoice` exactly like `StageSkippedByCheck` (status `SKIPPED`, unlocks, satisfies prerequisites, never `MASTERED`); the row renders ◇ *Skipped* (UX-DR18).
- All new Learner copy is content `Text` keys in `core/domain/src/main/resources/content/strings/en.json` (AD-6); controls have labels for TalkBack; layout scrolls and fits at 200% font on 600 dp.
- *Not sure? Scan my cube* renders only when `PLACEMENT` is above OFF (AD-18); nothing camera-related in `app/src/main`.
- *Check me with a scramble instead* opens the existing `openSkipCheck = "course"` flow unchanged (Story 7.7).

**Never:** build *Find my place* → *Scan my cube* re-placement (#252, separate issue); change `PassSkipCheck` / `PlaceFromScan` behavior; add a Room schema version (the `type` column is an open `String`, so no migration is needed); edit planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First two layers, fresh | empty log; choose level 1 | `StageSkippedByChoice` for stages 01–04, `StageUnlocked(stage-05)` | — |
| Beginner method, fresh | empty log; choose level 2 | `StageSkippedByChoice` for 01–07, `StageUnlocked(stage-08-two-look-oll)`; *Faster last layer* shows | — |
| 2-look, fresh | empty log; choose level 3 | `StageSkippedByChoice` for 01–07, 08, 09; no `StageUnlocked` (target is the Library, not a Stage) | — |
| Existing progress | stage-01 completed, stage-02 skipped by check, stage-03 mastered | only LOCKED/IN_PROGRESS earlier Stages get a skip; nothing re-locked; no `ProgressReset` | — |
| Target already reached | stage-05 already unlocked/completed; level 1 | no `StageUnlocked`; only missing skips | — |
| Write fails | repository append fails | nothing navigates; picker stays with an error line | `msg.placement.save-failed`-style content copy |
| Round trip | `StageSkippedByChoice` saved to Room | read back as the same event | unknown type row stays ignored as today |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/ProgressEvent.kt` -- add `StageSkippedByChoice(stageId, at)`; any exhaustive `when` over `ProgressEvent` must gain a branch (compiler finds them).
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/StageProgress.kt` -- `status` (L117-126), `isStageUnlocked` (L163-170), `isStageSatisfied` (L200-216): every `StageSkippedByCheck` test also accepts `StageSkippedByChoice`; latest-skip of type Choice → `StageStatus.SKIPPED`.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/PlaceFromScan.kt` -- template (load → filter earlier LOCKED/IN_PROGRESS → unlock → `ProgressRecord(sessionId, revision, event)` → append; `inMemory` companion).
- `core/application/src/main/kotlin/dev/cubentia/application/port/ProgressRepository.kt` -- KDoc writer list gains `ChooseEntryLevel`.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/ProgressWriterTest.kt` -- `ALLOWED_WRITERS` and messages gain `ChooseEntryLevel.kt`.
- `app/src/main/kotlin/dev/cubentia/app/data/ProgressEventEntity.kt` -- `TYPE_STAGE_SKIPPED_BY_CHOICE = "STAGE_SKIPPED_BY_CHOICE"` in `record()` / `of()`; `type` is an open `String` column → no schema version, no migration.
- `app/src/main/kotlin/dev/cubentia/app/di/PersistenceModule.kt` (L79-85) -- provide `ChooseEntryLevel`.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- inject `ChooseEntryLevel` (both constructors, L72-121); add `chooseEntryLevel` mirroring `placeFromScan` (L558-589: writer.read, in-memory fallback, `refreshProgressAfterWrite`).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- Welcome `onCanAlreadySolve` (L519-522); `startCourse` (L443); `placementStale` (L475-479) only allows Welcome/Recognize; Placement renders before Welcome (L481); skip-check `onClose` (L664-679), `onSkipCheckPassed` (L686-711) and `BackHandler` (L716-740) set `welcomeAbandoned` for `"course"`; `scrollToTwoLook` (L265-273); `libraryFilter` (L262) / `libraryGrid` (L263); LibraryScreen call (L790); LearnScreen call (L829).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/LearnScreen.kt` -- `StageListContent` (L214) has no top bar; 2-look rows use `StageProgress.status(stage, listOf(stage), …)` (L272) so a Choice skip shows ◇ Skipped there with no change.
- `app/src/main/kotlin/dev/cubentia/app/ui/welcome/WelcomeScreen.kt` -- PLACEMENT button + `BetaLabel`/`BetaNote` pattern to copy for scan entries.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/AlgorithmLibrary.kt` -- `LibraryFilter(family, status)`; `CaseSet { OLL, PLL }` in `content/Model.kt:125`.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- `LibraryFilterSaver`, chip rows, `LibraryChip`; `msg.library.set.oll` exists in en.json.
- `core/domain/src/main/resources/content/strings/en.json` -- all new copy (AD-6); `checkLearnerText` rejects Learner text in `strings.xml`.
- Tests: `core/domain/src/test/.../tutor/StageProgressTest.kt`, `core/application/src/test/.../usecase/StageProgressUseCasesTest.kt` (or new `ChooseEntryLevelTest.kt`), `app/src/test/.../data/RoomProgressRepositoryTest.kt`, `app/src/test/.../ui/shell/AppShellSkipCheckTest.kt`, `AppShellPlacementTest.kt` (L717), `AppShellTest.kt` (L684), `AppShellLibraryTest.kt`, `ui/library/LibraryScreenTest.kt`, `ui/welcome/WelcomeScreenTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../tutor/ProgressEvent.kt`, `StageProgress.kt` -- add the event; treat it like `StageSkippedByCheck` everywhere -- AD-10, never MASTERED.
- `core/application/.../usecase/ChooseEntryLevel.kt` (new) -- `enum class EntryLevel { FIRST_TWO_LAYERS, BEGINNER_METHOD, TWO_LOOK }` and `ChooseEntryLevel(repository)` with `suspend operator fun invoke(level, courseStages, twoLookStages, at, sessionId = "", revision = -1L): ProgressWrite` plus `inMemory`; plan per Design Notes; session id default `entry-level:<level>:epoch-<n>`, revision 0 -- the only writer of `StageSkippedByChoice`.
- `core/application/.../port/ProgressRepository.kt`, `ProgressWriterTest.kt` -- writer list.
- `app/.../data/ProgressEventEntity.kt` -- new type constant both ways.
- `app/.../di/PersistenceModule.kt`, `app/.../session/SessionHost.kt` -- provide/inject; `suspend fun chooseEntryLevel(level, courseStages, twoLookStages): Boolean`.
- `core/domain/.../content/AlgorithmLibrary.kt`, `app/.../ui/library/LibraryScreen.kt` -- `LibraryFilter.set: CaseSet? = null` filtering `case.set` (AND with family/status); saver keeps `set`; while `set != null` a removable chip row shows above the family chips (Design Notes).
- `app/.../ui/shell/EntryLevelScreen.kt` (new) -- the picker and the *Find my place* sheet composables (Design Notes).
- `app/.../ui/shell/LearnScreen.kt` -- top bar on the Stage list view: `R.string.nav_learn` heading + *Find my place* `TextButton` (`learn:findMyPlace`), callback param `onFindMyPlace: (() -> Unit)? = null` (absent → hidden).
- `app/.../ui/shell/AppShell.kt` -- picker origin state, sheet, wiring (Design Notes).
- `core/domain/src/main/resources/content/strings/en.json` -- keys below.
- Tests -- every I/O matrix row as a JVM test of `ChooseEntryLevel`; `StageProgress` Choice = SKIPPED (status, unlock, prerequisite); Room round-trip; Robolectric shell tests for each AC below; update tests that tapped `welcome:canSolve` expecting the check to tap `entryLevel:check` next.

**Acceptance Criteria:**
- Given Welcome, when *I can already solve it* is tapped, then the picker shows the title, the four choices and the note; *Not sure? Scan my cube* shows only with `PLACEMENT` above OFF.
- Given the Learn Stage list, when *Find my place* is tapped, then a sheet shows *Choose my level*, and *Scan my cube* only with `PLACEMENT` above OFF; *Choose my level* opens the same picker.
- Given the picker, when *I can solve the first two layers* is chosen, then Stages 1–4 read ◇ Skipped, *Last-layer orientation* is unlocked, and the Learner is on the Learn Stage list (onboarded).
- Given the picker, when *I solve with the beginner method* is chosen, then Stages 1–7 read Skipped, *Faster last layer* shows, and the list is scrolled to it.
- Given the picker, when *I know 2-look OLL and PLL* is chosen, then Stages 1–7 and both 2-look Stage rows read Skipped, the Library tab shows only OLL tiles with a removable *OLL* chip; tapping the chip shows all Algorithms; leaving the Library tab and returning shows the unfiltered Library.
- Given *Check me with a scramble instead*, when chosen, then the 7.7 whole-Course check runs unchanged; giving it up returns to Welcome with "Let's find where to start" when opened from Welcome, and to the picker when opened from Learn.
- Given PLACEMENT on, when *Scan my cube* (sheet) or *Not sure? Scan my cube* (picker) is tapped, then the existing Placement flow runs; closing it returns to its caller; accepting behaves as from Welcome.
- Given the picker, when back is pressed, then the Learner returns to its caller (Welcome or Learn) with nothing written.
- Given a failed write, when a level is chosen, then the picker stays and shows `msg.entry-level.save-failed`.
- Given every new control, when read by TalkBack, then each has a content label from en.json; the picker scrolls at 200% font on 600 dp.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 30 findings — high 0, medium 3, low 19, false 6, maybe-false 2
- findings:
  - `[low]` `[reject]` (intent) Copy/TalkBack/200% is checked in Robolectric, not on devices — the device check is the story's human gate, outside this automated run.
  - `[low]` `[reject]` (intent) Existing-progress rule is tested only in `ChooseEntryLevelTest`, not end to end — the shell calls the same use case; fix would only add test breadth.
  - `[false]` `[reject]` (intent) OLL filter survives recreation while on the Library tab — that is the same visit; PO answer 1 only requires the next visit to be unfiltered, which `LaunchedEffect(tab)` does and a test covers.
  - `[low]` `[reject]` (intent) Give-up via BackHandler from Learn untested — the shell branch is unreachable while the check shows (LessonScreen's own BackHandler calls the tested `onClose`).
  - `[low]` `[reject]` (intent) Welcome buttons after a give-up are not re-tested — unchanged Story 7.7 behavior, covered by existing Welcome tests.
  - `[medium]` `[patch]` (intent) Scan from the Learn-opened picker untested — same root as the verification-gap finding; test added.
  - `[false]` `[reject]` (intent) 2-look writes no `StageUnlocked` — the target is the Library, as the intent-contract matrix states.
  - `[false]` `[reject]` (intent) 7.7 tests need an extra `entryLevel:check` tap — the entry path moved by design (SCP 2026-11-20); the check itself is unchanged.
  - `[low]` `[reject]` (blind) Picker back uses the close icon and "Close" label — matches the project's convention (Stage detail and Placement use `ic_close`); no back-arrow asset exists.
  - `[medium]` `[patch]` (blind) Save-error line not announced by TalkBack — added `liveRegion = Polite`; the failed-write test asserts it.
  - `[low]` `[reject]` (blind) `UnknownStage` shows the generic save error — unreachable with shipped content (stage-05 and 2-look Stages are bundled); a fix adds branches.
  - `[low]` `[reject]` (blind) stage-05 id hard-coded — the shipped-bundle tests assert stage-05 at index 4, so a rename fails the build.
  - `[low]` `[reject]` (blind) Recreation mid-write can drop navigation — the write is durable; tapping again writes nothing new and navigates; a ViewModel move adds complexity for a rare case.
  - `[false]` `[reject]` (blind) In-memory path lacks try/catch — the in-memory repository cannot throw (a lambda read and a list append).
  - `[low]` `[patch]` (blind) Sheet not checked for 48 dp / with PLACEMENT on at 200% — added `assertClickablesAtLeast48dp("Find my place")` with both rows shown.
  - `[low]` `[patch]` (blind) Sheet opens partially expanded — now `skipPartiallyExpanded = true`.
  - `[false]` `[reject]` (blind) OLL chip exposes two click actions — semantics config holds one `OnClick` per merged node; the test reads its single label.
  - `[low]` `[reject]` (blind) Several shell paths untested (Learn with progress, double tap, pass from Learn, empty write) — the use case covers the logic; pass-from-picker is covered in `AppShellSkipCheckTest`.
  - `[false]` `[reject]` (blind) Filter outlives one visit after process death — same as the intent row above: restoring the same visit is not a "next visit".
  - `[low]` `[reject]` (blind) New "Learn" heading for every Learner — the spec chose the top bar (UX IA: "Learn top app bar").
  - `[low]` `[patch]` (blind) New en.json keys split the OLL/PLL pair — keys moved after `msg.library.set.pll`.
  - `[low]` `[reject]` (blind) Note copy vs IN_PROGRESS skip — the copy is the UX spec's verbatim line; the skip rule is AD-10's.
  - `[medium]` `[patch]` (gap) Scan from the Learn-opened picker never run — added test "Not sure Scan my cube on the picker opened from Learn runs Placement and closing returns to the picker".
  - `[maybe-false]` `[defer]` (gap) Tab-change / Idle resets of `placementFromLearn` untested — would need a test that scans from the sheet, leaves Learn, then delivers an outcome; deferred as medium (unverified).
  - `[low]` `[reject]` (gap other) Edited BackHandler branch is unreachable during a check — harmless defensive code; `onClose` is the live path and is tested.
  - `[low]` `[reject]` (edge) `UnknownStage` retry loop — same as blind row above.
  - `[low]` `[reject]` (edge) Recreation mid-write — same as blind row above.
  - `[low]` `[reject]` (edge) "I can already solve it" before the catalog is Ready — Welcome renders only after content is not Loading; with a Failed catalog nothing in the course works, as before.
  - `[maybe-false]` `[reject]` (edge) `placementFromLearn` stays on after a cancel before leaving Idle — a cancelled scan delivers no later outcome; it clears on leaving Learn; if true only low.
  - `[low]` `[reject]` (edge) *Faster last layer* summary reads In progress while both 2-look rows read Skipped — the spec settled this (group row, not a Stage).

### 2026-11-22 — Review pass (follow-up)
- verdicts: 32 findings — high 0, medium 1, low 19, false 9, maybe-false 3
- findings:
  - `[false]` `[reject]` (intent) Sheet *Scan my cube* contradicts the Never clause on #252 re-placement — PO clarifications 2 and 4 (binding) put the sheet's *Scan my cube* in scope and name #252's note and "already reached" result as the excluded part; neither is built and `PlaceFromScan` is unchanged.
  - `[false]` `[reject]` (intent) OLL Library filter is surface the intent did not name — PO clarification 1 (binding) asks for exactly this chip and filter.
  - `[false]` `[reject]` (intent) Target unlock uses derived status, not "no StageUnlocked event" — a target that reads IN_PROGRESS is already unlocked by its prerequisites, so no unlock is missing; the intent's "not yet unlocked" holds.
  - `[low]` `[reject]` carried (intent) 7.7 entry/abandon routing changed and tests gained an `entryLevel:check` tap — same as the earlier row: the entry path moved by design; the check itself is unchanged.
  - `[low]` `[reject]` carried (intent) Existing-progress and target-reached rows tested only at the use case — same as the earlier row.
  - `[false]` `[reject]` (intent) New `epic-14-context.md` is a planning-artifact edit — it lives in implementation-artifacts; nothing under planning-artifacts changed.
  - `[false]` `[reject]` (intent) Unknown-type Room rows get no new test — `ProgressEventEntity` unknown-type handling is unchanged; only a new known type was added.
  - `[low]` `[reject]` carried (blind) `UnknownStage` shows the generic save error — same as the earlier row.
  - `[low]` `[reject]` carried (blind) stage-05 / stage-08 ids hard-coded — same as the earlier row (shipped-bundle tests pin the ids).
  - `[false]` `[reject]` (blind) Mastery not passed, so a mastered Stage with open Lessons gets a Choice skip — `StageProgress.status` returns MASTERED before any skip is read, and mastery only ends with a `ProgressReset`, which also ends the skip's epoch; the Learner never sees a change, and the spec defines status on the loaded log.
  - `[false]` `[reject]` (blind) 2-look status computed against `course + twoLook` while the UI uses `listOf(stage)` — a completed or mastered 2-look Stage reads COMPLETED/MASTERED in the UI before any skip is consulted, so an extra skip never changes what is shown.
  - `[false]` `[reject]` (blind) Giving up from Learn skips Flow 8's "Let's find where to start" line — PO clarification 5 (binding) says no abandon line on the picker.
  - `[low]` `[reject]` (blind) Save error below the choices is easy to miss for sighted users — a rare failure path; moving or restyling it is a layout change, not a direct correction.
  - `[low]` `[reject]` (blind) Learn top bar has no 200% overflow check — a heading with `weight(1f)` beside one TextButton; a test would only add breadth.
  - `[low]` `[reject]` (blind) `SessionHost.chooseEntryLevel` has no unit tests — it mirrors `placeFromScan`, and the shell tests drive it end to end.
  - `[low]` `[reject]` carried (blind) Cancelled scan from Learn leaves `placementFromLearn` set — same as the earlier maybe-false row.
  - `[low]` `[reject]` (blind) `entryLevelFrom` is a plain string — refactor with no named failure; two values, all compared in one file.
  - `[low]` `[reject]` carried (blind) Picker state after recreation / `entryLevelSaving` not saveable — same as the earlier recreation row.
  - `[low]` `[reject]` carried (blind) Picker back uses the close icon and `btn.solve.close` — same as the earlier row.
  - `[false]` `[reject]` (blind) `at` defaults to epoch 0 — the same default as `PlaceFromScan` and `PassSkipCheck`; the only production caller passes `clock.now()`.
  - `[low]` `[reject]` (blind) Skip-type check repeated in four places in `StageProgress` — refactor; no caller diverges today.
  - `[low]` `[reject]` (blind) "No PLL tile" asserted with `runCatching { … }.isFailure` — weak but correct; the patched Library test now also asserts the PLL family chip is absent.
  - `[low]` `[reject]` (blind) `ChooseEntryLevelTest` misses COMPLETED / placement / post-reset seeds — breadth only; the filter is one status check covered by the matrix rows.
  - `[low]` `[reject]` carried (edge) Recreation mid-write drops navigation — same as the earlier row.
  - `[low]` `[reject]` carried (edge) `entryLevelSaving` resets on recreation — same root as the earlier recreation row.
  - `[low]` `[reject]` carried (edge) `UnknownStage` retry loop — same as the earlier row.
  - `[maybe-false]` `[reject]` carried (edge) `placementFromLearn` stays on after a cancelled scan — same as the earlier row.
  - `[low]` `[patch]` (edge) With the OLL set filter on, PLL family chips still show and tapping one empties the grid with "No algorithms marked Learning yet." — `LibraryScreen` now offers only the families of the cases the set filter shows; `LibraryScreenTest` asserts the PLL family chip is hidden while the filter is on and back after clearing.
  - `[maybe-false]` `[reject]` (edge) Same mastered-Stage claim as the blind row — see that row's refutation; if true only low.
  - `[maybe-false]` `[defer]` carried (gap) Tab-change / Idle resets of `placementFromLearn` untested — same as the earlier deferred row; already in `deferred`, not added again.
  - `[medium]` `[patch]` (gap) The picker's *Not sure? Scan my cube* Beta label and note are never tested (every test flag set is GA) — added `while PLACEMENT shows Beta the picker and the sheet label their scan entries` (CAMERA_SCAN at BETA) and a GA counterpart in `AppShellEntryLevelTest`.
  - `[low]` `[patch]` (gap other) The *Find my place* sheet's *Scan my cube* shows no Beta label or note while PLACEMENT is Beta, unlike Welcome and the picker — `FindMyPlaceSheet` gained `scanBeta` (`BetaLabel`, "…, BETA" description, `BetaNote`); `AppShell` passes `showsBeta(PLACEMENT)`; covered by the same new test.

## Design Notes

**PO clarifications (2026-11-22), binding:** (1) *I know 2-look* opens the Library with a removable "OLL" chip, shown only while the filter is on; tapping it clears it; the filter is not kept — the next Library visit is unfiltered. (2) *Not sure? Scan my cube* from Learn runs the existing Placement flow as Welcome/Recognize do; #252's note and "already reached" result are out of scope. (3) After levels 1 and 2 the Learner lands on the Learn Stage list; level 2 scrolls to *Faster last layer*. (4) With PLACEMENT on, the sheet shows *Scan my cube* beside *Choose my level*. (5) Giving up the check opened from Learn returns to the picker (no abandon line on it).

**Write plan** (`status` = `StageProgress.status(stage, planStages, loadedEvents)`; skip each planned Stage whose status is LOCKED or IN_PROGRESS; unlock target only when its status is LOCKED):

| Level | planStages | skipped candidates | target unlock |
|---|---|---|---|
| FIRST_TWO_LAYERS | course | course before `stage-05-last-layer-orientation` | stage-05 |
| BEGINNER_METHOD | course + twoLook.first() | all course | twoLook.first() |
| TWO_LOOK | course + twoLook | course + both 2-look | none (Library) |

Missing stage-05 or empty twoLook → `ProgressWrite.UnknownStage`. Nothing new → `Written(emptyList())`, still navigates.

**Shell state** (rememberSaveable): `entryLevelFrom: String?` (`"welcome"`/`"learn"`), `findMyPlace: Boolean`, `placementFromLearn: Boolean`. Picker renders when `entryLevelFrom != null && openSkipCheck == null`, after the Placement block (so a running Placement covers it) and before Welcome. `placementStale` also stays false while `placementFromLearn`; that flag clears in `startCourse`, when the Placement phase returns to Idle after having left it, and when the Learn tab is left. `startCourse` clears `entryLevelFrom`. Check opened from picker: `openSkipCheck = "course"`, `skipCheckEpoch++`, origin kept; give up → origin `"welcome"`: clear origin + `welcomeAbandoned = true`; origin `"learn"`: keep origin, no `welcomeAbandoned`. Pass → clear origin, then today's pass code.

**Success navigation:** onboard (`Preference.Onboarded(true)`, `welcome = false`), clear origin; levels 1–2: `tab = LEARN`, `selectedStage = null`; level 2 `scrollToTwoLook = true`; level 3: `tab = LIBRARY`, `openAlgorithm = null`, `recognizeFlow.reset()`, `libraryFilter = LibraryFilter(set = CaseSet.OLL)` (family/status cleared), grid scrolled to the top. A `LaunchedEffect(tab)` clears `libraryFilter.set` when `tab != LIBRARY` (family/status stay as today). Write runs in the shell scope; choices disabled while it runs; no spinner, no animation.

**Picker UI:** full-screen task flow (UX-DR22): top bar with back arrow (existing back label) and the title as heading; three full-width `OutlinedButton`s (48 dp min), then a `TextButton` *Check me with a scramble instead*, the note in `inkSecondary`, the error line when the write failed, and, with PLACEMENT on, a `TextButton` *Not sure? Scan my cube* with `BetaLabel`/`BetaNote` when `showsBeta(PLACEMENT)` (Welcome's pattern). Column scrolls. `BackHandler` → caller. Copy never depends on existing progress. Tags: `entryLevel`, `entryLevel:firstTwoLayers`, `entryLevel:beginner`, `entryLevel:twoLook`, `entryLevel:check`, `entryLevel:scan`, `entryLevel:note`, `entryLevel:error`, `entryLevel:back`.

**Sheet:** Material3 `ModalBottomSheet` (heading + one row per action, 48 dp, `findMyPlace:scan` first when shown, `findMyPlace:chooseLevel`); dismiss by back/scrim; an action closes the sheet first. Neither action writes.

**OLL chip:** an `InputChip` (selected, label `msg.library.set.oll`, trailing close icon) in its own row above the family chips, tag `library:set:OLL`, click label `msg.library.set-filter.clear`; click → `filter.copy(set = null)`. The empty-filter message works as today.

**New en.json keys:** `btn.learn.find-my-place` "Find my place"; `msg.find-my-place.title` "Find my place"; `btn.find-my-place.scan` "Scan my cube"; `btn.find-my-place.choose-level` "Choose my level"; `msg.entry-level.title` "Where do you want to start?"; `btn.entry-level.first-two-layers` "I can solve the first two layers"; `btn.entry-level.beginner-method` "I solve with the beginner method"; `btn.entry-level.two-look` "I know 2-look OLL and PLL"; `btn.entry-level.check` "Check me with a scramble instead"; `msg.entry-level.note` "Skipped Stages stay open, and nothing is marked mastered."; `btn.entry-level.scan` "Not sure? Scan my cube"; `msg.entry-level.save-failed` "Could not save your progress. Try again."; `msg.library.set-filter.clear` "Show all, not only {set}".

*Faster last layer*'s summary row keeps its `TwoLookMastery` status (it is a group row, not a Stage); the two 2-look Stage rows show Skipped.

## Verification

**Commands** (env per AGENTS.md):
- `./gradlew :core:domain:test :core:application:test` -- pass.
- `./gradlew :app:testDebugUnitTest` -- pass.
- `./gradlew spotlessCheck build` -- pass (CI gate: lint, Kover, checkLearnerText, boundaries).

## Auto Run Result

Status: done

**Summary:** Story 14.1 adds the entry-level picker ("Where do you want to start?") reached from Welcome → *I can already solve it* and from a new Learn top-bar *Find my place* sheet. The three levels are recorded by the new single writer `ChooseEntryLevel` as `StageSkippedByChoice` (only add, never downgrade, AD-10). *I know 2-look* opens the Library on a removable OLL filter that clears when the Library tab is left. The whole-Course check stays as the fourth choice. With PLACEMENT on, scan entries run the existing Placement flow.

**Files changed:**
- `core/domain/.../tutor/ProgressEvent.kt`, `StageProgress.kt`: new event, treated as SKIPPED.
- `core/domain/.../content/AlgorithmLibrary.kt`: `LibraryFilter.set`.
- `core/application/.../usecase/ChooseEntryLevel.kt` (new): `EntryLevel` and the single writer.
- `core/application/.../port/ProgressRepository.kt`, `ProgressWriterTest.kt`: writer list.
- `app/.../data/ProgressEventEntity.kt`: `STAGE_SKIPPED_BY_CHOICE` type; no migration (open String column).
- `app/.../di/PersistenceModule.kt`, `app/.../session/SessionHost.kt`: wiring and `chooseEntryLevel`.
- `app/.../ui/shell/EntryLevelScreen.kt` (new): the picker and the *Find my place* sheet.
- `app/.../ui/shell/AppShell.kt`, `LearnScreen.kt`, `ui/library/LibraryScreen.kt`, `ui/welcome/WelcomeScreen.kt`: navigation, Learn top bar, OLL chip.
- `core/domain/src/main/resources/content/strings/en.json`: 13 keys.
- Tests: `ChooseEntryLevelTest` and `AppShellEntryLevelTest` (16) are new; `StageProgressTest`, `AlgorithmLibraryTest`, `LibraryScreenTest`, `RoomProgressRepositoryTest` and `ScreenAudit` are extended; the skip-check, placement and shell tests are updated for the new picker step.

**Review findings:** 30 total. 5 entries patched: 2 medium (save error announced as a live region; test for scanning from the Learn-opened picker) and 3 low (sheet fully expanded; 48 dp sheet check; en.json key order). 1 deferred: the `placementFromLearn` reset tests, medium (unverified). 24 rejected; each reason is in the Review Triage Log.

**Follow-up review recommended:** true. Two medium entries were patched (0 high, 2 medium, 3 low). The named unverified risk is the lifecycle of a Placement run started from Learn (the `placementFromLearn` flag): its resets on leaving Learn are untested.

**Verification:** `./gradlew spotlessCheck build` passed before and after the patches (BUILD SUCCESSFUL in 5m 30s, after the patches). JUnit reports show all 8 `ChooseEntryLevelTest` tests passing (every I/O matrix row) and all 16 `AppShellEntryLevelTest` tests passing; `RoomProgressRepositoryTest` round-trips six event types.

**Residual risks:**
- Recreating the Activity during the write can leave the Learner on the picker; tapping again is harmless.
- The *Faster last layer* summary row keeps its mastery status while the 2-look rows read Skipped.
- The device check on the Lenovo TB128XU and Galaxy S24 FE (main app build) is still pending.

### Follow-up review pass (2026-11-22)

**Summary:** A fresh four-layer review (blind, edge-case, verification-gap, intent-alignment; all on Claude, no Codex marker) of the whole story diff. 32 findings: 3 patched, 1 deferred (carried, already listed), 28 rejected.

**Files changed in this pass:**
- `app/.../ui/shell/EntryLevelScreen.kt`: the *Find my place* sheet's *Scan my cube* shows the Beta label, the "…, BETA" description and the camera Beta note while PLACEMENT shows Beta (same pattern as Welcome and the picker).
- `app/.../ui/shell/AppShell.kt`: passes `showsBeta(PLACEMENT)` to the sheet.
- `app/.../ui/library/LibraryScreen.kt`: family chips list only the families of the cases the set filter shows, so a PLL family chip can no longer empty the OLL-filtered grid behind the misleading "No algorithms marked Learning yet." line.
- `app/src/test/.../ui/shell/AppShellEntryLevelTest.kt`: two new tests (Beta labels on the sheet and picker with CAMERA_SCAN at BETA; none at GA).
- `app/src/test/.../ui/library/LibraryScreenTest.kt`: the OLL filter test asserts the PLL family chip is hidden, then shown after clearing.

**Review findings:** patched 1 medium (Beta disclosure on the picker scan untested) and 2 low (sheet scan missing its Beta label; PLL family chips under the OLL filter). Deferred: the `placementFromLearn` reset tests (carried; unchanged entry). Rejected: 28, each with its reason in the Review Triage Log (follow-up pass entry).

**Follow-up review recommended:** false. This was a follow-up pass and no high was patched (patched: high 0, medium 1, low 2).

**Verification:** `./gradlew spotlessCheck build` passed after the patches (BUILD SUCCESSFUL in 5m 14s); it covers `:core:domain:test`, `:core:application:test` and `:app:testDebugUnitTest`. JUnit reports: `AppShellEntryLevelTest` 18/18 and `LibraryScreenTest` 11/11 passed.

**Residual risks:** unchanged from the first pass: Activity recreation during the write, the *Faster last layer* summary row's status, the untested `placementFromLearn` resets, and the pending device check.
