---
title: 'GH #252 Re-placement from Learn keeps progress'
type: 'feature'
created: '2026-11-23'
status: 'done'
baseline_revision: 'f32d9e5d4bf67152a8e2140c33ddfbc9939caf0c'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Learn > *Find my place* > *Scan my cube* (Story 14.1 wiring) runs Placement, but it never tells the Learner their progress is kept, and accepting a Stage they have already reached still goes through the `PlaceFromScan` write instead of the approved "already reached" outcome (GitHub #252, Story 11.2 amendment, EXPERIENCE.md *Re-placement*).

**Approach:** Add the approved "keeps your progress" note under *Scan my cube* in the *Find my place* sheet (shown before the scan starts), and, for a Placement run started from Learn, replace the suggestion line and *Start* with "You've already reached *Stage*." and *Open Stage* when the suggested Course Stage is not `LOCKED`; opening it writes nothing. A Learn run that accepts a not-yet-reached Stage keeps using `PlaceFromScan`, which only adds events.

## Boundaries & Constraints

**Always:** Learner text only from `core/domain/src/main/resources/content/strings/en.json` with the exact approved copy: "This keeps your progress. Stages you've finished stay as they are.", "You've already reached {stage}." (stage title resolved from content), button "Open Stage". *Scan my cube* and its note stay hidden when `PLACEMENT` is OFF (the existing `placementScan` null gate). "Reached" for a Course Stage = `StageProgress.status(stage, ready.bundle.stages, progressEvents) != StageStatus.LOCKED`. Progress writes go only through `PlaceFromScan` (single writer, AD-10).

**Never:** No change to `PlaceFromScan`, `Placement.evaluate`, Welcome or Recognize Placement behavior, the entry-level picker, flag levels, or planning artifacts. No new interstitial screen or invented copy. No re-lock, downgrade or event removal. No "already reached" handling for 2-look Stages (*Faster last layer* keeps today's additive write).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Note shown | Learn, PLACEMENT above OFF, open *Find my place* | Sheet shows *Scan my cube*, the keeps-progress note under it, then *Choose my level* | — |
| Flag OFF | PLACEMENT OFF | Sheet shows only *Choose my level*; no note | — |
| Adds progress | Stage 1 completed, Stage 2 `IN_PROGRESS`, Stage 3 `LOCKED`; scan suggests Stage 4 (*Middle layer*); *Start* | Stages 2 and 3 become ◇ *Skipped by placement*, Stage 4 unlocks, Stage 1 stays *Completed*, every earlier record kept; Stage 4 detail opens | Write failure keeps the result with the existing save-failed line |
| Already reached | Stages 1-2 completed (Stage 3 unlocked); scan suggests *First-layer corners* | Reading and reason, then "You've already reached First-layer corners." and *Open Stage* (no *Start*); *Open Stage* opens its detail; zero records written | — |
| Reached via chooser | Learn run, *Choose another Stage* -> a non-`LOCKED` Course Stage (or *Show me the last layer anyway* to a reached Stage) | Opens that Stage, writes nothing | — |
| Welcome / Recognize run | Not from Learn | Unchanged (suggestion + *Start*) | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` -- `FindMyPlaceSheet` (~181): the `if (onScan != null)` block renders *Scan my cube* + beta label/note; add the keeps-progress `bodySmall`/`inkSecondary` text there (testTag `findMyPlace:scan:keepsProgress`).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `placementFromLearn` (~286) marks a Learn run; `PlacementScreen(...)` call (~624) with `onAccept` (stage-1 shortcut via `startStageOne`, else `placementFlow.accept { host.placeFromScan(...) }`); `startCourse(stage)` (~539) navigates to a Stage detail and resets the flow; `progressEvents` (~244). `StageProgress`/`StageStatus` already imported (~925).
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- `PlacementResult`, `ResultCopy.suggestion`, `suggestedStage(outcome)`; `else ->` branch renders `placement:start`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.placement.*` (~29-57), `find-my-place` keys (~594-597).
- `core/application/.../usecase/PlaceFromScan.kt` -- read-only: already only adds `StageSkippedByPlacement` for earlier `LOCKED`/`IN_PROGRESS` Stages and `StageUnlocked` for the target.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellEntryLevelTest.kt` -- harness for Learn + sheet + Placement (`show(welcome = false, placement = true)`, `openSheet()`, `entry(lessonId)`, in-memory `records` repository); existing sheet tests at ~241-437.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.find-my-place.keeps-progress`, `msg.placement.already-reached` ("You've already reached {stage}."), `btn.placement.open-stage` ("Open Stage") -- approved copy.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` -- render the note under *Scan my cube* only when `onScan != null`; update the KDoc.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- add params `reached: (Stage) -> Boolean = { false }` and `onOpen: (Stage) -> Unit = {}`; in `PlacementResult`, when the suggested Stage is reached, show the already-reached line (testTag `placement:alreadyReached`, inside the polite `placement:prompt` group, in place of `placement:suggestion`) and an *Open Stage* button (`placement:openStage`) in place of `placement:start`; other options unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- pass `reached` = Learn run && Stage in Course && status != `LOCKED`; `onOpen` = `placementFlow.accepted(id)` then `startCourse(id)` (no write); in `onAccept`, a Learn run whose target is reached also opens it without writing.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellEntryLevelTest.kt` -- add tests for every I/O row (seed `records` before `show`), asserting record counts/contents and on-screen text.

**Acceptance Criteria:**
- Given an onboarded Learner and PLACEMENT above OFF, when they open Learn > *Find my place*, then "This keeps your progress. Stages you've finished stay as they are." shows with *Scan my cube* before any scan starts, and tapping it launches the scan, review and Placement.
- Given a Learn re-placement accepting a not-yet-reached Stage, when recorded, then only new events are appended (earlier `LOCKED`/`IN_PROGRESS` Stages *Skipped by placement*, target unlocked) and no Stage already *Completed* changes status.
- Given a Learn re-placement whose target is already reached, when the result shows, then "You've already reached *Stage*." with *Open Stage* shows and no progress record is written.
- Given PLACEMENT OFF, when *Find my place* opens, then only *Choose my level* shows (no scan, no note).

## Spec Change Log

## Review Triage Log

### 2026-11-23 — Review pass
- verdicts: 22 findings — high 0, medium 0, low 8, false 12, maybe-false 2
- findings:
  - `[false]` `[reject]` (intent) Flow 10 narrates "Start at First-layer corners?" after finishing First cross, but the diff shows the already-reached outcome — the intent's done-when 3 and EXPERIENCE.md line 132 define reached as "already open or finished"; Stage 3 is open once Stage 2 is completed, so only the implemented reading fits the explicit rule (noted in Design Notes for the PO).
  - `[low]` `[reject]` (intent) 2-look targets (*Faster last layer*) are excluded from the no-write rule — a reached 2-look target only gets a redundant additive `StageUnlocked`; nothing is lost or re-locked, the case is rare, and a reliable 2-look reached check adds branches.
  - `[false]` `[reject]` (intent) *Choose another Stage* to a reached Stage opens silently without the already-reached line — the approved line is specified for the suggested Stage; the chooser pick still writes nothing.
  - `[false]` `[reject]` (intent) note lives in the sheet, not a step after tapping — an interstitial would need a continue button with no approved copy; the note is shown before the scan starts, as done-when 1 requires.
  - `[false]` `[reject]` (intent) tests inject the scan outcome instead of driving camera scan and review — the camera adapter is outside :app main; the existing Placement tests use the same seam and scan launch is asserted via `scanCount`.
  - `[false]` `[reject]` (blind) Stage 1 always reads as already reached in a Learn run — Stage 1 is never LOCKED, so it is "already open" per EXPERIENCE.md; *Open Stage* lands on the same Stage *Start* did, and Start on Stage 1 never wrote anything.
  - `[low]` `[reject]` (blind) no test for *Show me the last layer anyway* to a reached Stage — same `onAccept(stage, false)` branch the chooser test covers; a dedicated test needs a non-white F2L cube.
  - `[false]` `[reject]` (blind) Recognize half of the Welcome/Recognize row untested and `placementFromLearn` may leak — AppShell.kt:376 clears `placementFromLearn` whenever Learn is left, and Recognize lives on the Library tab.
  - `[low]` `[reject]` (blind) the `!faster` and `stage in course` guards have no test — developer-only regression risk; adding 2-look LL scenarios is more than a direct correction.
  - `[false]` `[reject]` (blind) recreation on the result screen could disagree — `placementFromLearn` is rememberSaveable and `progressEvents` is reloaded from the repository, so both inputs survive recreation.
  - `[false]` `[reject]` (blind) `openReached` calls `placementFlow.accepted`, muddying diagnostics — the existing Stage-1 no-write shortcut already does exactly this; behaviour is consistent.
  - `[false]` `[reject]` (blind) `openReached` skips the Saving guard — *Open Stage* is `enabled = !accepting`, and chooser picks go through `onAccept`, which checks Saving first.
  - `[low]` `[patch]` (blind) already-reached line not asserted inside the merged polite `placement:prompt` — added `onNodeWithTag("placement:prompt").assertTextContains("You've already reached First-layer corners.")`.
  - `[low]` `[reject]` (blind) with BETA the note sits below the beta note — still under *Scan my cube* and above *Choose my level*; cosmetic.
  - `[low]` `[reject]` (blind) spec artifact brittle (line anchors, elided command) — fix edits this build's spec.
  - `[low]` `[reject]` (blind) adds-progress test checks statuses, not Learn badges — statuses come from the same `StageProgress` the list renders; badge assertions add little.
  - `[low]` `[patch]` (blind) Welcome-run test never asserts which Stage the scramble suggests — added `placement:suggestion` assertTextEquals("Start at Cube fundamentals?").
  - `[false]` `[reject]` (edge) Stage 1 reads as already reached with no progress — same as the blind Stage 1 finding: Stage 1 is open by definition.
  - `[maybe-false]` `[reject]` (edge) result may flip to already-reached for a frame between the write's progress refresh and the Saved navigation — would need a frame trace of `placeFromScan` refresh vs `PlacementAcceptance.Saved`; if true it is a one-frame low-grade flash, so rejected.
  - `[false]` `[reject]` (edge) Learn picker scan gets reached handling without the note — picker scans opened from Learn are Learn re-placements (FR-27 "outside Welcome only adds progress"); the intent scopes the note to the sheet.
  - `[maybe-false]` `[reject]` (edge) reached check omits the mastered set — would need a reachable state where a Stage is unlocked only by mastery; the `PlaceFromScan` writer makes the same call, and if true the outcome is only an additive write (low).
  - `[false]` `[reject]` (edge) claim the entry-level picker changed — the picker screen is untouched; only the shared Placement result reacts to a Learn run.

## Design Notes

The note lives in the sheet, directly under *Scan my cube*: it is seen before the scan without inventing a continue button (no approved copy exists for one). Flow 10 narrates Marco accepting *First-layer corners* after finishing *First cross*; under today's `StageProgress` that Stage is already unlocked, so the binding AC (Story 11.2 amendment) gives him the already-reached outcome instead. "Reached" is limited to Course Stages because a 2-look Stage evaluated alone has no prerequisite and would always read as unlocked.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-252 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply :app:testDebugUnitTest --tests '*AppShellEntryLevelTest' --tests '*AppShellPlacementTest'` -- expected: pass
- `... ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** Learn > *Find my place* now shows "This keeps your progress. Stages you've finished stay as they are." under *Scan my cube* (hidden with PLACEMENT OFF). A Placement run started from Learn shows "You've already reached *Stage*." with *Open Stage* (no write) when the suggested Course Stage is not LOCKED, and a reached Course Stage picked from the chooser also opens without writing; other accepts still go through `PlaceFromScan`, which only adds events.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` — three approved strings.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` — keeps-progress note in the sheet.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` — `reached`/`onOpen`, already-reached line and *Open Stage*.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` — Learn-run reached check and no-write open.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellEntryLevelTest.kt` — seven new tests covering every I/O row.

**Review:** 22 findings; 2 low patches applied (test assertions); 0 deferred; 20 rejected (reasons in the triage log). Follow-up review recommended: false (patched: high 0, medium 0, low 2).

**Verification:** `./gradlew spotlessApply :app:testDebugUnitTest --tests '*AppShellEntryLevelTest' --tests '*AppShellPlacementTest'` — BUILD SUCCESSFUL (26 + 33 tests, 0 failures); `./gradlew spotlessCheck build` — BUILD SUCCESSFUL.

**Residual risks:** Flow 10's narrative (Marco gets "Start at First-layer corners?") differs from the binding rule, under which he sees the already-reached outcome — PO may want to confirm. Stage 1 suggestions in a Learn run read "You've already reached Cube fundamentals." A reached 2-look target still gets a redundant additive `StageUnlocked`. No device session run.
