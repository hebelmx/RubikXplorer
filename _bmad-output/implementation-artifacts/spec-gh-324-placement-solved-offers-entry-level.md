---
title: 'GH #324: a solved-cube Placement outcome also offers "I can already solve it"'
type: 'feature'
created: '2026-11-24'
status: 'done'
baseline_revision: 'fb046d7fd32a10b90829d27b84d445f042670755'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-24-github-issue-triage.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** A solved cube scanned into Placement offers only *Scan again* and *Start Stage 1*, but a solved cube says nothing about skill; an experienced solver has no way to skip the Course from there (GH #324, Story 11.2 amendment, SCP 2026-11-24).

**Approach:** Add a third action, *I can already solve it*, to the solved outcome. It opens the existing `EntryLevelScreen` (Story 14.1) as a new picker caller, and Back from that picker returns to the same solved outcome. Nothing else in Placement or the picker changes.

## Boundaries & Constraints

**Always:** Order is *Scan again*, *Start Stage 1*, *I can already solve it*. The reading ("Your cube is solved.") and reason ("Scramble it and scan again, or start Stage 1.") lines are unchanged. The new label is a content string in `en.json` (`btn.placement.can-already-solve` = "I can already solve it"), never `strings.xml`. Opening the picker writes no progress and no preference; only choosing a level writes (via the existing `chooseEntryLevel` → `ChooseEntryLevel`), and then lands exactly as from Welcome/Learn (`startCourse`, which resets the Placement flow). The Placement flow's `Result` phase is kept while the picker is open so Back can show it again.

**Never:** No change to `EntryLevelScreen`'s contents (its *Not sure? Scan my cube* stays — SCP finding F13 was not applied), to other Placement outcomes, to `PlacementFlowViewModel` phases, or to `ChooseEntryLevel`. No new SessionEvent, port, or persisted state. No edits to planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Offer | Solved cube accepted into Placement (from Welcome or Learn re-placement) | Three buttons in order; two copy lines unchanged | — |
| Open picker | Tap *I can already solve it* | `EntryLevelScreen` shows; no progress record, not onboarded | — |
| Back | Top-bar close or system Back on that picker | Same solved outcome shows again; still no writes | — |
| Choose level | Pick *I can solve the first two layers* from that picker | Same events and landing as Story 14.1 (Stages 1–4 skipped, Learn list), onboarded, Placement gone | Save failure keeps the picker with its existing error line |
| Scan from picker | PLACEMENT on, tap *Not sure? Scan my cube* on that picker | A fresh Placement scan starts and its result is visible (not hidden behind the picker) | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- `PlacementScreen` params (~L45-60) and `PlacementResult` `PlacementOutcome.Solved` branch (~L207-216) render *Scan again* (`placement:scanAgain`) and *Start Stage 1* (`placement:startStage1`). Add an `onCanAlreadySolve: () -> Unit` callback (default `{}`) threaded to `PlacementResult`, and a third `OutlinedButton` tagged `placement:canAlreadySolve`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `entryLevelFrom` (rememberSaveable String?, ~L284) holds the picker caller ("welcome"/"learn"). Placement branch (~L622-667) renders before and returns early over the EntryLevel branch (~L669-699). Plan: new caller value `"placement"`; gate the Placement branch with `entryLevelFrom != "placement"` so the picker shows while the phase stays `Result`; picker `onBack` already nulls `entryLevelFrom` → outcome reappears. In the picker's `onScan`, when the caller is "placement", clear `entryLevelFrom` and keep `placementFromLearn` as is (don't overwrite it with `fromLearn`) so the new scan's result is visible. Skip-check give-up paths (~L861, ~L914) already null any non-"learn" caller.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` -- read-only; KDoc lists callers (update the KDoc sentence only to name the Placement entry).
- `core/domain/src/main/resources/content/strings/en.json` -- `btn.placement.*` keys (~L52-59); add the new key next to `btn.placement.start-stage-1`.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- Robolectric shell tests; helpers `show()`, `startScan()`, `deliver()`, `assertPrompt()`, `assertOnboarded()`, `back` dispatcher, `records`; existing solved tests ~L379, ~L412; keyed-strings test ~L344 covers the solved outcome (new label must pass it).
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellEntryLevelTest.kt` -- 200% font pattern: `@Config(qualifiers = "w600dp-h960dp")`, `show(largeType = true)` wrapping `fontScale200`, `assertClickablesLabelled`/`assertClickablesAtLeast48dp`/`assertNoTextOverflow` from `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add `"btn.placement.can-already-solve": "I can already solve it"` -- content-string rule.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- add `onCanAlreadySolve` param and the third solved-outcome button after *Start Stage 1* -- the new action.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- wire `onCanAlreadySolve = { entryLevelFailed = false; entryLevelFrom = "placement" }`, gate the Placement branch on `entryLevelFrom != "placement"`, adjust the picker's `onScan` for that caller, update the `entryLevelFrom` comment -- open the picker and return to the outcome on Back.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` -- KDoc only: name the Placement solved-outcome entry.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- add tests: (a) solved outcome shows the three buttons in order and the two unchanged lines; (b) *I can already solve it* opens `entryLevel`, writes nothing, not onboarded; (c) top-bar close and system Back each return to the solved outcome (prompt lines intact) with no writes; (d) choosing a level from that picker writes the Story 14.1 events and lands on Learn; (e) *Not sure? Scan my cube* from that picker starts a new scan whose result is shown; (f) at 200% font on w600dp the solved outcome's controls are labelled, ≥48dp and don't overflow. Existing *Scan again* / *Start Stage 1* tests keep covering those two actions.

**Acceptance Criteria:**
- Given a solved cube accepted into Placement, when the outcome shows, then it offers *Scan again*, *Start Stage 1*, *I can already solve it* in that order with the reading and reason lines unchanged.
- Given that outcome, when *I can already solve it* is tapped, then `EntryLevelScreen` opens and no progress or onboarding is written.
- Given the picker opened from that outcome, when Back (top bar or system) is pressed, then the solved outcome shows again.
- Given the Placement tests, when they run, then all three actions and the Back navigation are covered, and the new label comes from content strings and fits at 200% font on 600 dp.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 22 findings — high 0, medium 2, low 13, false 6, maybe-false 1
- findings:
  - `[low]` `[reject]` (edge) *Check me with a scramble instead* from the Placement-opened picker sets `openSkipCheck`, `placementStale` resets the flow, so the solved outcome is lost — real, but the give-up lands exactly where the Welcome picker's give-up does (Welcome with its abandon line, or Learn); the intent only asks Back from the picker to return, which holds; a fix adds new branches to the skip-check paths.
  - `[low]` `[reject]` (edge) giving up that skip check lands on Welcome/Learn, not the picker or outcome — same root and reason as the row above.
  - `[false]` `[reject]` (edge) Learn-origin run loses `placementFromLearn` when the skip check resets the flow — once the flow is reset the run is over; clearing the flag is the existing cleanup, nothing reads it afterwards for that run.
  - `[low]` `[reject]` (edge) `entryLevelFrom = "placement"` restored after process death with an Idle phase shows the picker without an outcome under it — rare; the picker still works and Back goes to Welcome/Learn; guarding adds a new effect.
  - `[maybe-false]` `[reject]` (edge) scan from the Placement-opened picker skips `clearSaveFailure()` — a Failed acceptance needs an accept write, which the solved outcome never makes, and *Scan again* clears it on the way in; would need a path to a solved result carrying a stale Failed to settle; if true only low.
  - `[low]` `[reject]` (edge, claim) the spec says the Result phase is kept while the picker is open, which the skip check breaks — same root as the first row.
  - `[low]` `[reject]` (blind) skip check from this picker breaks the promised Back — same root as the first row.
  - `[low]` `[reject]` (blind) a Learn-origin picker is treated as Welcome-origin (`fromLearn` false) — only affects the skip-check give-up (first row); `onScan` keeps the Learn origin explicitly.
  - `[low]` `[patch]` (blind) no test for a failed level save from the Placement-opened picker — added `a failed level write from the solved outcome's picker keeps the picker with its error` in AppShellPlacementTest.
  - `[low]` `[reject]` (blind) no state-restoration test for the "placement" caller — same as the process-death row above.
  - `[low]` `[reject]` (blind) Welcome's label comes from strings.xml, the new one from en.json — AGENTS.md forbids new Learner text in strings.xml; Welcome's string is pre-existing.
  - `[false]` `[reject]` (blind) reason line does not mention the new option — the intent requires both copy lines unchanged (PO Q2).
  - `[low]` `[reject]` (blind) `entryLevelFrom` is a growing magic-string protocol — real erosion, but an enum refactor is more than a direct correction and out of the "nothing more" scope.
  - `[false]` `[reject]` (blind) cancelling the picker's rescan shows the old solved outcome — the same flow handles Cancelled as for *Scan again* (existing test: cancelling a rescan returns to Welcome).
  - `[low]` `[reject]` (blind) redundant/loose test assertions — cosmetic.
  - `[false]` `[reject]` (blind) design docs not updated and spec untracked — EXPERIENCE.md/epics.md were already amended by the merged SCP; the spec is committed with this change.
  - `[medium]` `[patch]` (verification-gap) Learn-origin scan from the Placement-opened picker untested; a regression overwriting `placementFromLearn` would drop the new result — added `a Learn re-placement's solved outcome opens the picker, Back returns to it, and its scan shows the new result` in AppShellEntryLevelTest.
  - `[low]` `[reject]` (verification-gap, other) skip check from this picker loses the outcome — same root as the first row.
  - `[medium]` `[patch]` (intent) the issue's repro path (Learn → Find my place → Scan my cube) is never exercised — grouped with the verification-gap row; the same new Learn-origin test covers open, Back and no writes.
  - `[false]` `[reject]` (intent) no device acceptance — the Lenovo/S24 FE check is the human gate after merge, not something a build run produces.
  - `[false]` `[reject]` (intent) no new tests for *Scan again* / *Start Stage 1* — existing tests `solved cube can rescan and then start Stage 1 without writing progress` and `cancelling a rescan from the solved result returns to Welcome` cover them and passed.
  - `[low]` `[reject]` (intent) skip-check route untested — same root as the first row.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-324 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.shell.AppShellPlacementTest' --tests 'dev.cubentia.app.ui.shell.AppShellEntryLevelTest'` -- expected: all pass
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-324 && ./gradlew spotlessCheck build` (same env) -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** The solved Placement outcome now offers *Scan again*, *Start Stage 1* and *I can already solve it* (content string `btn.placement.can-already-solve`). The new action opens the existing `EntryLevelScreen` as a new caller (`entryLevelFrom = "placement"`) while the Placement Result phase stays underneath, so Back (top bar or system) returns to the same outcome; opening writes nothing, choosing a level goes through the existing `chooseEntryLevel`. The picker's *Not sure? Scan my cube* from this entry leaves the picker and keeps the run's Welcome/Learn origin.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- new label key.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- `onCanAlreadySolve` callback and third solved-outcome button.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- "placement" picker caller, Placement branch gate, picker `onScan` for that caller.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` -- KDoc names the new entry.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- 7 new tests (order + copy, opens/no writes, both Backs, choose level, failed save, scan from picker, 200% on 600dp) and a `largeType` option.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellEntryLevelTest.kt` -- 1 new Learn re-placement test.

**Review:** 22 findings; 2 patch entries applied (1 medium: Learn-origin test, grouped from verification-gap and intent; 1 low: failed-save test); 0 deferred; 19 rejected with reasons in the triage log (13 low, 6 false, 1 maybe-false).

**Follow-up review recommended:** false (patched: 0 high, 1 medium, 1 low; tests only).

**Verification:** `./gradlew :app:testDebugUnitTest` for AppShellPlacementTest (40/40) and AppShellEntryLevelTest (27/27) passed; `./gradlew spotlessCheck build` BUILD SUCCESSFUL after the patches.

**Residual risks:** From the Placement-opened picker, giving up *Check me with a scramble instead* lands on Welcome (abandon line) or Learn, not on the solved outcome (the skip check resets the Placement flow); untested. The picker still offers *Not sure? Scan my cube* from this entry (SCP finding F13 not applied). Device check on the Lenovo TB128XU and Galaxy S24 FE is still to do.
