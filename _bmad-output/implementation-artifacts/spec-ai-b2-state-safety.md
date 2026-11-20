---
title: 'ai-b2 State safety: PreferenceStore read errors, Placement accept/late outcomes, Course practice rotation, physical result line, pending scan'
type: 'bugfix'
created: '2026-11-20'
status: 'done'
baseline_revision: '254b64763327d9b1daf15485e1fa7f01b0e9086e'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['multiple-goals', 'oversized']
deferred:
  - summary: >-
      PO device item: on a reference device, rotate (or trigger a configuration change) while Placement's accept write is in flight and confirm the Learner lands on Learn at the accepted Stage, onboarded, with no second write.
    evidence: |-
      Robolectric StateRestorationTester covers the recreation of AppShell with a gated placement write; real Activity recreation timing during a Room write is device-only. Batch with the combined human-verification session (#106).
    location: app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt
    severity: low
  - summary: >-
      PO device item: in the LL (2-look) practice in physical mode on a real cube, confirm the qualified / not-qualified result line appears only after tapping "Yes, it's solved".
    evidence: |-
      Robolectric test drives the session state; the physical-cube interaction itself is device-only. Batch with #106.
    location: app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:371
    severity: low
---

<intent-contract>

## Intent

**Problem:** Six open action items (batch ai-b2): (1) if PreferenceStore's initial load fails, the next `update` saves `DEFAULT + change`, overwriting the Learner's stored preferences; (2) Placement's accept write runs in AppShell's composition scope, so recreating the Activity mid-write persists the skips but drops navigation and onboarding; (3) a late Placement scan/denial callback after leaving Welcome (or a progress reset) leaves a stale Placement phase that renders over other screens; (4) rotation may re-deal a Course practice scramble; (5) in physical mode the 2-look result line shows before "Yes, it's solved"; (6) `enterColors`, `scanDenied` and `FellBackToEditor` leave `mutablePendingScan` set.

**Approach:** Never write a full preferences record over an unknown disk value: after a failed load, retry the read inside each queued write and only save over a found value. Move the Placement accept job into `PlacementFlowViewModel.viewModelScope` with a resumable acceptance state that AppShell consumes. Reset Placement on progress reset and whenever Welcome is no longer the active screen. Prove item 4 (fixed by 0ec0d6ba) with tests for the two uncovered Course-level paths. Gate the result line on `physicalSolveConfirmed`. Clear the pending scan on the three fallbacks.

Items: epic-6-retro-item-1-harden-preferencestore-error-handling, epic-11-deferred-11.2-accept-write-recreation, epic-11-deferred-11.2-stale-placement-phase, epic-9-deferred-9-8-rotation-regenerates-course-practice, epic-9-deferred-9-8-physical-result-line-before-confirm, issue-203-deferred-clear-pending-scan-on-fallback.

## Boundaries & Constraints

**Always:** Every fixed behavior gets a JVM or Robolectric test that fails on the old code. Preferences keep one writer (`UpdatePreference`, AD-3 single writer). In-memory preference changes still apply immediately even when disk is unreadable. Existing tests that assert the old wrong behavior (e.g. `PreferenceStoreTest.immediateApplyOrderedWritesAndFailedSaveKeepsMemory` expecting saves after a failed load) are updated to the new rule, not deleted. Placement stays reachable only from Welcome; a Placement scan result delivered into a recreated process while Welcome is showing still opens Placement.

**Never:** Edit sprint-status.yaml, planning artifacts, `_bmad/`, `.claude/skills/`. Add Learner text, UI, `SessionEvent`s, persisted fields, or repository/port changes. Change the spec-settled "write fails still onboards" behavior of Placement (11.2 I/O matrix), `PlaceFromScan`, `reduce()`, scan contracts, or anything outside these six items. Add attempt tokens through MainActivity/ScanLauncher.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Prefs load OK | Found(stored) | as today: changes saved over stored | - |
| Prefs load failed, read recovers | load Failed, then update; retry load Found(stored) | saved = stored folded with every change made since; `preferences` shows that value | - |
| Prefs load failed, still unreadable | every load Failed | no `save` call; changes kept in memory | silent, as today |
| Reset after failed load | reset() | repository.reset() runs; base becomes DEFAULT (known) | - |
| Accept, no recreation | accept non-first Stage, write Saved | navigate + onboard as today | Failed: saveFailed shown, as today |
| Accept, recreated mid-write | composition recreated while write suspended, then Saved | new composition navigates to Learn at target, Onboarded(true) written, exactly one placeFromScan call | - |
| Accept tapped twice / after recreation while saving | acceptance Saving | ignored, button inert | - |
| Late outcome after leaving Welcome | welcome == false (or course skip check open), then Accepted/FellBackToEditor/denial | phase returns to Idle; Placement never renders | - |
| Reset progress | Settings reset succeeds with a stale Placement phase | Placement reset; Welcome shows, not Placement | reset fails: unchanged |
| Physical LL practice solved | physical, objectiveMet, not confirmed | no `lesson:twoLookResult`; after "Yes, it's solved" it shows | - |
| Virtual LL practice solved | virtual, objectiveMet | result line as today | - |
| Pending scan then fallback | pendingScan set, then enterColors / scanDenied / FellBackToEditor | `takePendingScan()` returns null | - |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/preferences/PreferenceStore.kt` -- init load (on `Failed`, `written` stays DEFAULT); `update` enqueues `updater(written, preference)` -> the overwrite bug. `reset` writes DEFAULT legitimately.
- `app/src/test/kotlin/dev/cubentia/app/data/PreferenceStoreTest.kt:106` -- test encodes the old overwrite; update it.
- `app/src/main/kotlin/dev/cubentia/app/data/SerialWriter.kt` -- FIFO; `enqueue` ops run serially on IO, so a retry read inside the queued op is safe.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt` -- phase/acceptedScan; `reset()`; no accept state today. `internal constructor()` used by AppShell default and tests.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:220-221` -- `acceptingPlacement` / `placementSaveFailed` composition state; `:276-291` onResetProgress (no placement reset); `:395-402` startCourse (updates Onboarded(true), resets placementFlow); `:414-457` Placement render gate (no welcome check) and onAccept launching in `rememberCoroutineScope`; `:458` Welcome gate; `:581` course skip check renders after Placement. `:242-250`, `:541`, `:587`, `:922` scramble keeping (item 4 fix).
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt:152-160` -- Placement scan wiring (unchanged).
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- `placeFromScan(target, stages)`, internal constructor takes `placeFromScan`/`progress` for gated test fakes.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:371-380` -- `twoLookResult` only checks `objectiveMet`; `:236` milestone already gates on `physicalSolveConfirmed` (pattern to reuse); `:202` `isPhysical`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt:84-127` -- `onScanOutcome`, `scanDenied`, `enterColors` (no pending clear); `closeEditor`/`leaveSolveTab` show the clear.
- Tests: `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` (launch helper ~L473 takes `placementFlow`), `AppShellTest.kt:672-712` (`keepsGeneratedScramble`, StateRestorationTester), `ui/lesson/PhysicalLessonScreenTest.kt` and `LessonScreenTest.kt:256` (`lesson:twoLookResult`), `ui/solve/SolveFlowViewModelTest.kt:115`.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/preferences/PreferenceStore.kt` -- track whether the disk value is known; on a failed load keep it unknown; in each queued write while unknown, retry `repository.load()`: on Found set `written` to it folded with the changes made since the failed load, save that once and publish it to `current` (re-applying any newer in-memory changes); on Failed skip the save. `reset` makes the base known (DEFAULT). -- never overwrite stored prefs with defaults.
- `app/src/test/kotlin/dev/cubentia/app/data/PreferenceStoreTest.kt` -- update L106 test (no saves while load keeps failing, memory still updates); add: failed load then recovering read saves stored+changes and never `DEFAULT+change`; reset after failed load still calls `repository.reset()`.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt` -- add an acceptance state (Idle / Saving / Failed / Saved(stageId, faster)) and `accept(stageId, faster, save: suspend () -> Boolean)` running in `viewModelScope`, ignored while Saving; a consume function returning and clearing Saved; `reset()` also clears acceptance, and a Saving job's result is dropped if `reset()` ran meanwhile. -- accept survives recreation.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- replace `acceptingPlacement`/`placementSaveFailed` and the scope.launch in onAccept with the VM acceptance; a LaunchedEffect consumes Saved -> `placementFlow.accepted(id)`, `startCourse(...)`, `scrollToTwoLook`; add a LaunchedEffect that calls `placementFlow.reset()` when `welcome == false` or `openSkipCheck != null` while phase is non-Idle or a scan is pending; gate the Placement render on `welcome == true && openSkipCheck == null`; call `placementFlow.reset()` in the successful onResetProgress branch. -- items 2 and 3.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- add: recreation mid-write (StateRestorationTester + gated placeFromScan) lands on Learn at the target with Onboarded(true) and one write; cold start with placement skips persisted and onboarded=false shows Learn, not Welcome (proves the process-death half is resumable, AppShell.kt:301-304); late Accepted outcome after *I'm new* leaves phase Idle and Placement absent; late outcome during the course skip check does not show Placement; reset progress with a stale phase shows Welcome.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellTest.kt` -- add `keepsGeneratedScramble`-style tests for the Course skip-ahead check (Welcome *I can already solve*, key "course") and the Stage 1 practice that opens `practice-stage-07-independent-practice`; if they pass unchanged, item 4 is already fixed by 0ec0d6ba (`AppShell.kt:249-250,541,587`) and needs no code.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `twoLookResult` requires `!isPhysical || session.physicalSolveConfirmed`.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/PhysicalLessonScreenTest.kt` -- physical LL practice with objectiveMet: `lesson:twoLookResult` absent before "Yes, it's solved", present after.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- clear `mutablePendingScan` in `enterColors`, `scanDenied`, `FellBackToEditor`.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- for each of the three, a pending Accepted scan is gone (`takePendingScan()` null).

**Acceptance Criteria:**
- Given the existing Placement, Solve, Lesson and preference test suites, when the batch lands, then they pass with only the L106 PreferenceStore test changed to the new rule.
- Given an item proven already fixed, when the spec closes, then its Auto Run Result cites the test and file:line instead of a code change.

## Spec Change Log

- 2026-11-20 (implementation): the premise "Placement is only entered from Welcome" is false: a Recognize result's "Start from my cube" (Library tab, `AppShell.kt` RecognizeScreen `placementScan`) also enters it while `welcome == false`, pinned by `AppShellRecognizeTest.placementStartedFromRecognitionShowsItsSuggestionAfterOnboarding`. The stale rule is therefore "neither Welcome nor a Recognize result is the active screen, or the course skip check is open" (`placementStale` in `AppShell.kt`). `LessonScreenTest.twoLookPracticeResult` gained a wait for `physicalSolveConfirmed` after "Yes, it's solved" (the result line now waits for that background-dispatched confirmation); its assertions are unchanged.

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 28 findings — high 0, medium 4, low 19, false 5, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) placementStale can drop an in-flight accept from a Recognize-entered Placement after recreation — recognizePhase lives in RecognizeFlowViewModel (MainActivity.kt:67, survives recreation) and tab/openAlgorithm are rememberSaveable, so the flow does not become stale on recreation; PlacementScreen returns early, so no tab switch is possible mid-write.
  - `[low]` `[reject]` (blind) Spec Boundaries/Design Notes/Tasks still say Placement is only entered from Welcome — fix edits this build's spec; the Spec Change Log records the corrected premise.
  - `[low]` `[reject]` (blind) AC "only the L106 test changed" is broken by LessonScreenTest and helper changes — fix edits the spec; the LessonScreenTest wait is a necessary consequence of the fix and is logged in the Spec Change Log.
  - `[medium]` `[patch]` (blind) No direct tests of PlacementFlowViewModel acceptance state — added PlacementFlowViewModelTest (5 tests: second accept while Saving ignored, throwing save -> Failed, reset during Saving drops result, consumeSaved once, phase change clears Failed).
  - `[medium]` `[patch]` (blind) Recreation test clicks a disabled node, so the Saving guard is unproven — same root as above; the VM test now proves save is called once.
  - `[low]` `[reject]` (blind) accept swallows save exceptions as Failed without a log — Failed shows the existing save-failed message (graceful); adding diagnostics adds surface for an unlikely path.
  - `[low]` `[reject]` (blind) Changes made while preferences stay unreadable are lost if no later write retries — matches the matrix row (kept in memory, no save); the alternative destroyed every other stored preference; a background retry adds complexity for a rare disk failure.
  - `[low]` `[patch]` (blind) PreferenceStore synchronization and incomplete `generation` comment — `written` is only read/written inside serial SerialWriter ops (no race); comment corrected to say it is bumped only by a reset while the disk value is unknown.
  - `[low]` `[reject]` (blind) Verification commands omit AppShellRecognizeTest / PlacementFlowViewModelTest — fix edits the spec; both suites were run and pass, and `./gradlew build` runs all JVM tests.
  - `[low]` `[reject]` (blind) Item 4 proof does not drive the Stage 1 button; Code Map line numbers differ — Stage 1's onPracticeNewScramble sets the same openPractice key and epoch as the tested path (AppShell.kt LessonScreen onPracticeNewScramble), so the same practiceEntry code runs; line numbers are pre/post-change, cosmetic.
  - `[medium]` `[patch]` (blind) Physical result-line test checks only existence and not the switch back to virtual — grouped with the edge LessonScreen finding; test now asserts the qualified text and a new test covers unconfirmed physical -> virtual.
  - `[false]` `[reject]` (blind) Stale reset logs a misleading "run cancelled" diagnostic — onPlacementRun(null) records "no active run", which is accurate after a stale outcome; startCourse already resets the same way.
  - `[false]` `[reject]` (blind) Frontmatter followup flag / empty triage log inconsistent — both are set by this step's finalize, not before.
  - `[medium]` `[patch]` (edge) LessonScreen.kt:372 shows the result line for an unconfirmed physical solve switched to virtual, while the milestone (:236) stays hidden — gate now matches the milestone: `if (isPhysical) physicalSolveConfirmed else !physicalSolveUnconfirmed`; PhysicalLessonScreenTest.anUnconfirmedPhysicalTwoLookSolveSwitchedToVirtualShowsNoResultLine.
  - `[low]` `[reject]` (edge) placementStale becoming true while Saving drops Saved — no path shown: Welcome stays true until Saved is consumed, the skip check cannot open from Placement, and Recognize state survives recreation; fix adds a guard.
  - `[low]` `[reject]` (edge) Accept tapped again between Saved and its consumption — one-frame window before the LaunchedEffect consumes Saved; unlikely; fix adds a guard.
  - `[low]` `[reject]` (edge) reset() during Saving then accept again runs two writes — reset during Saving needs a scan outcome while the Placement buttons are inert; placement re-accept writes nothing new (11.2 triage); fix adds job tracking.
  - `[false]` `[reject]` (edge) reset() whose repository reset fails sets known=true, so the next write saves DEFAULT+change — the Learner asked to reset preferences, so DEFAULT+change is exactly the intended value, not an overwrite of other stored choices.
  - `[low]` `[reject]` (edge) unknownChanges grows without bound while unreadable — one entry per Learner tap; negligible; fix adds collapsing logic.
  - `[low]` `[patch]` (verification-gap) Save-failure message clearing on phase change is untested — failed-write test in AppShellPlacementTest now taps placement:chooseAnother and asserts the message is gone.
  - `[low]` `[patch]` (verification-gap) Recognize half of placementStale only tested in the not-stale case — added AppShellRecognizeTest.aLateScanOutcomeAfterLeavingTheRecognizeResultDoesNotShowPlacement.
  - `[low]` `[patch]` (verification-gap) PreferenceStore `generation` guard never exercised — added PreferenceStoreTest.aWriteQueuedBeforeAResetIsDroppedEvenWhenTheReadRecovers (writes == ["reset"], prefs DEFAULT).
  - `[low]` `[reject]` (intent-alignment) Process death leaves skips persisted with onboarded=false; onboarding is not resumed — Welcome is derived from `!onboarded && progress empty` (AppShell.kt:301-304), and the shipped course skip-check pass (AppShell.kt onSkipCheckPassed) already leaves onboarded=false with progress, so the Learner-visible flow resumes correctly; the cold-start test pins it.
  - `[low]` `[reject]` (intent-alignment) The explicit placementFlow.reset() in onResetProgress has no test of its own — the Learner-visible outcome (Welcome, not Placement, after reset) is asserted; isolating the call needs a stale phase set while welcome==true, which the stale effect makes unreachable.
  - `[low]` `[patch]` (intent-alignment) No test for the Recognize exception to the stale rule — same root as the verification-gap Recognize finding; covered by the new AppShellRecognizeTest case.
  - `[false]` `[reject]` (intent-alignment) Item 4 checks rotation only, not cold-start resume — the item's action text is "Rotation regenerates a Course practice scramble"; cold-start resume is a different finding not in this batch.
  - `[low]` `[reject]` (intent-alignment) Stage 1 practice path argued, not tested — same as the blind item-4 row.
  - `[low]` `[reject]` (intent-alignment) Changes made while the disk stays unreadable are never saved — same as the blind PreferenceStore-loss row.

### 2026-11-20 — Review pass (follow-up)
- verdicts: 25 findings — high 0, medium 0, low 17, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) reset() sets known=true even when the reset write fails, so the next update saves DEFAULT+change — carried: the Learner asked to reset preferences, so DEFAULT+change is the intended value, not an overwrite of other stored choices.
  - `[false]` `[reject]` (blind) saveOverRetriedLoad ignores a failed save and marks the value known — the next write saves found+changes (the value read from disk plus the Learner's changes), never DEFAULT+change; this matches the known path, where a failed save also advances `written` (PreferenceStoreTest failed-save rule).
  - `[low]` `[reject]` (blind) Changes made while the store stays unreadable are kept only in memory, with no diagnostic or retry — carried: matches the matrix row; a background retry or diagnostic adds surface for a rare disk failure.
  - `[low]` `[reject]` (blind) The stale-Placement effect can reset while an accept is Saving and drop Saved — carried: no path shown; Welcome stays true until Saved is consumed, the skip check cannot open from Placement, Recognize state survives recreation.
  - `[low]` `[reject]` (blind) Skips saved but onboarded=false is never repaired — carried: Welcome is derived from `!onboarded && progress empty`, so the Learner-visible flow resumes; the cold-start test pins it.
  - `[false]` `[reject]` (blind) show() keeps a stale save failure when the next phase is equal — every route from a failed Result passes through a different phase (Choosing, Editing) or through onScanAgain's clearSaveFailure(), so an equal-phase transition with Failed set does not occur.
  - `[false]` `[reject]` (blind) Every stale reset logs a phantom declined Placement run — carried: onPlacementRun(null) records "no active run", which is accurate after a stale outcome.
  - `[low]` `[reject]` (blind) The 2-look gate expression appears twice in LessonScreen.kt; confirm-then-switch-to-virtual untested — both copies are identical one-liners next to each other in one file; extracting a helper is a refactor, not a correction, and the confirmed path is the existing milestone behavior.
  - `[low]` `[patch]` (blind) `appends` in AppShellPlacementTest is a plain var written on the writer thread and polled from the test thread — marked `@Volatile` (single writer thread, so visibility is all that is needed).
  - `[low]` `[reject]` (blind) Recreation tests rebuild only the composition, not the Activity or process — real Activity recreation is already a deferred PO device item in frontmatter; process death is covered by the cold-start test.
  - `[false]` `[reject]` (blind) withTimeout in closeWriter cannot stop a blocking hang — `writer.close()` waits by suspending, and that suspension point is cancellable, so the timeout fires as the comment says.
  - `[low]` `[patch]` (edge) runCatching around the retried load swallows CancellationException — replaced it with try/catch that rethrows CancellationException (same idiom as PlacementFlowViewModel.accept).
  - `[false]` `[reject]` (edge) reset() whose repository reset fails sets known=true — carried: same as the blind reset row.
  - `[low]` `[reject]` (edge) reset() during Saving, then a second accept, runs two writes — carried: needs a scan outcome while the Placement buttons are inert; re-accept writes nothing new; the fix adds job tracking.
  - `[false]` `[reject]` (edge) A late Welcome scan outcome renders Placement over an open Recognize result — the scan outcome is delivered when the scan Activity returns to Welcome. Reaching the Library tab first sets welcome=false while Recognize is Idle, and the stale effect then resets the phase. "I'm new" / startCourse also resets recognizeFlow.
  - `[low]` `[reject]` (edge) Saved can land while ShellContent returns early (welcome==null, SOLVE_PLAYBACK) — neither screen is reachable from a Saving Placement screen (welcome is rememberSaveable; playback needs the Solve tab); hoisting the effect restructures the shell for an unreachable case.
  - `[low]` `[reject]` (edge) Code gates Placement on !placementStale, not welcome==true as the spec task says — carried: the fix edits this build's spec; the Spec Change Log already records the Recognize entry point.
  - `[low]` `[reject]` (edge) The result-line gate is stricter than the spec task (hides the line in virtual after an unconfirmed physical solve) — carried: a deliberate patch from the first review so it matches the milestone gate; the fix edits the spec.
  - `[low]` `[reject]` (edge) AC "only the L106 test changed" is broken by other test edits — carried: the fix edits the spec; the LessonScreenTest wait is logged in the Spec Change Log.
  - `[low]` `[reject]` (edge) Each recovered write saves found + changes only up to itself, so a crash between queued writes persists only part of the changes — each later change has its own queued write; per-change persistence is the same as the known path; the gap is a crash-timing window.
  - `[low]` `[reject]` (intent-alignment) The diff implements "Welcome or Recognize result" rather than the literal "only from Welcome" — the fix edits the spec; the existing Recognize entry is pinned by AppShellRecognizeTest and logged in the Spec Change Log.
  - `[low]` `[reject]` (intent-alignment) The recreation test is composition-only and counts appends rather than placeFromScan calls — placeFromScan commits in one append, so they are equivalent; real Activity recreation is a deferred device item.
  - `[low]` `[reject]` (intent-alignment) Virtual-mode result line changes for an unconfirmed physical solve — carried: deliberate first-review patch; the plain virtual path is unchanged.
  - `[false]` `[reject]` (intent-alignment) Cancel-on-reset in PreferenceStore goes beyond the matrix — the matrix's "Reset after failed load" row says base becomes DEFAULT. A write queued before the reset would overwrite that DEFAULT, so dropping it is what the row requires, not extra scope.
  - `[low]` `[reject]` (intent-alignment) Item-4 tests cannot be shown failing on the code before 0ec0d6ba — the AC for already-fixed items asks for the test and file:line, which the Auto Run Result gives.

## Auto Run Result

**Summary:** Six action items resolved. PreferenceStore no longer overwrites stored preferences with defaults after a failed load (each write retries the read and saves only over a found value). Placement's accept write runs in `PlacementFlowViewModel.viewModelScope` with a resumable acceptance state, so recreation mid-write still navigates and onboards. A stale Placement phase (late scan outcome, course skip check, progress reset) is reset and never drawn; Placement is live only on Welcome or a Recognize result. Item 4 was already fixed by 0ec0d6ba and is proven by two new tests. The physical 2-look result line waits for "Yes, it's solved" (same gate as the milestone). The three Solve fallbacks clear the pending scan.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/preferences/PreferenceStore.kt` -- known/unknown disk value, retried read inside queued writes, reset generation.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt` -- PlacementAcceptance state, accept/consumeSaved/clearSaveFailure, epoch-guarded reset.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- VM-scoped accept, Saved consumer, placementStale rule and render gate, reset on progress reset.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- result line gated like the 2-look milestone.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- pending scan cleared on enterColors, scanDenied, FellBackToEditor.
- Tests: `PreferenceStoreTest`, `PlacementFlowViewModelTest` (new), `AppShellPlacementTest`, `AppShellRecognizeTest`, `AppShellTest`, `PhysicalLessonScreenTest`, `LessonScreenTest` (wait only), `SolveFlowViewModelTest`.

**Review findings:** 28 findings: 6 patch entries (10 findings: result-line gate + tests, PlacementFlowViewModel tests, save-failure clearing test, Recognize stale test, generation-guard test, generation comment), 0 deferred, 18 rejected (5 false, 13 low), each with its reason in the triage log.

**Follow-up review recommendation:** true. Two medium entries were patched (result-line gate, acceptance-state tests). Unverified risk: no reviewer has re-checked the patched LessonScreen gate or the `placementStale` rule now that it spans Welcome and Recognize. Patched counts: high 0, medium 2, low 4 (entries).

**Verification:** targeted suites (PreferenceStoreTest, AppShellPlacementTest, AppShellTest, PhysicalLessonScreenTest, LessonScreenTest, SolveFlowViewModelTest, PlacementFromScanRouteTest, AppShellRecognizeTest, PlacementFlowViewModelTest) BUILD SUCCESSFUL; `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches). The implementer ran the new tests against the baseline in a temporary worktree: all fail there except the two item-4 proofs, which pass (already fixed).

**Residual risks:** real Activity recreation during the Room write and the physical-cube result line are PO device items (frontmatter deferred). Preference changes made while the disk stays unreadable live only in memory until a later write's retried read succeeds.

- Item 4 (epic-9-deferred-9-8-rotation-regenerates-course-practice): already fixed by 0ec0d6ba, no code change. Proven by `AppShellTest` "the Course skip-ahead check from Welcome keeps its scramble and attempt across recreation" and "the independent practice keeps its scramble and attempt across recreation" (both pass on the baseline), covering `AppShell.kt:249-250,549,595`. The independent-practice test opens `practice-stage-07-independent-practice` from the Stage 7 detail; the Stage 1 Lesson's "practice new scramble" sets the same `openPractice` key (`AppShell.kt` LessonScreen `onPracticeNewScramble`), so it shares the kept scramble.
- All other new tests fail on the baseline and pass after the change.

### Follow-up review pass (2026-11-20)

**Summary:** Follow-up review of the ai-b2 batch. Four layers ran (blind, edge-case, verification-gap, intent-alignment). The verification-gap layer found no gaps. Two low findings were patched. No high or medium findings were raised.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/preferences/PreferenceStore.kt` -- the retried load in `saveOverRetriedLoad` now rethrows `CancellationException` (it used `runCatching` before).
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- `appends` is now `@Volatile`.

**Review findings:** 25 findings, 2 patched (both low), 0 deferred, 23 rejected (8 false, 15 low). Each has its reason in the triage log entry "Review pass (follow-up)".

**Follow-up review recommendation:** false. This was a follow-up pass and patched no high finding. Patched counts: high 0, medium 0, low 2.

**Verification:** targeted suites (PreferenceStoreTest, AppShellPlacementTest, AppShellTest, PhysicalLessonScreenTest, LessonScreenTest, SolveFlowViewModelTest, PlacementFromScanRouteTest, AppShellRecognizeTest, PlacementFlowViewModelTest) BUILD SUCCESSFUL. `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** unchanged. The device items in frontmatter `deferred` remain open.

## Design Notes

PreferenceStore retry stays inside the SerialWriter op so reads and writes never interleave. Placement process death needs no fix: `PlaceFromScan` commits in one write, and a cold start with skips but `onboarded=false` already skips Welcome because `progressEvents` is non-empty (AppShell.kt:301-304); the test pins it. Placement is only entered from Welcome, so any non-Idle phase while Welcome is not the active screen is stale by definition; this replaces attempt tokens.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :app:testDebugUnitTest --tests '*PreferenceStoreTest' --tests '*AppShellPlacementTest' --tests '*AppShellTest' --tests '*PhysicalLessonScreenTest' --tests '*LessonScreenTest' --tests '*SolveFlowViewModelTest' --tests '*PlacementFromScanRouteTest'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
