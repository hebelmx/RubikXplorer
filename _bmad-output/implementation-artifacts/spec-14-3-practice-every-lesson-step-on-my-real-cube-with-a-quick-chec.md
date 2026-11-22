---
title: 'Story 14.3: Practice every Lesson step on my real cube, with a Quick Check at any step'
type: 'feature'
created: '2026-11-22'
status: 'blocked'
baseline_revision: '07abb2cd960623d792b7240517969cd1c01a5316'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** In a physical Lesson, the *Quick Check* icon shows only at Objective boundaries, in a mismatch or on a retry (`LessonScreen.kt` gate `syncState == PHYSICAL_MISMATCH || decision.advance || objectiveMet || quickCheckRetry != null`), so a Learner partway through a step's Moves cannot confirm their cube (FR-26 as amended by SCP 2026-11-20, #208). No test covers Physical-cube Mode in a 2-look (`TWO_LOOK`) Lesson.

**Approach:** Keep the icon in the physical row at every step of every Lesson (LESSON and TWO_LOOK) while `CAMERA_SCAN` is above OFF, and highlight it only where Story 10.7 offered it (today's gate). The Physical-mode switch, *Done*, Expected State, mismatch ladder and Quick Check evidence are reused unchanged; tests prove them for a 2-look Lesson and mid-step.

Binding ADs (story Refs line, verbatim): **AD-3, AD-5, AD-18**. UX: UX-DR12. FRs: FR-8, FR-17, FR-26, FR-7. Stories 8.2, 9.7, 10.7. #208.

## Boundaries & Constraints

**Always:**
- AD-3: every change goes through `SessionEvent` -> `reduce()` on the `SessionHost` session; `QuickCheckRequested` already passes `quickAllowed` (Reduce.kt ~L1293) mid-step for LESSON and TWO_LOOK; no new `SessionKind` or event.
- AD-5: Certainty only from `tutor/Assess`; `quickObserved` compares against `state.expected` at the current position; a Partial verifies only a match, a Partial mismatch is at most LIKELY and offers *Scan all 6 faces*; never a false *verified*.
- AD-18: the icon exists only when `quickCheck` is non-null (`AppShell.kt` ~L227 `takeIf { flags.isEnabled(CAMERA_SCAN) }`); with it OFF no camera control shows and Physical-cube Mode works with *Done* only.
- Never required: *Done* / *Next* never wait on a Quick Check.
- AD-6: any new copy in `core/domain/src/main/resources/content/strings/en.json`; TalkBack labels; fits at 200% font on 600 dp.

**Never:** reducer or Assess changes; Quick Check in `PHYSICAL_AWAITING_CONFIRM` beyond the Confirm card's *Scan it*; changes to Solution Playback or Algorithm practice (pending PO question 2); editing planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Mid-step, camera on | physical, PREDICTED, some of the step's Moves confirmed with *Done*, not advance | `lesson:quickCheck` shown, not highlighted; tap dispatches `QuickCheckRequested` and starts the capture | second tap while pending does nothing (existing guard) |
| Boundary, camera on | `decision.advance` or `objectiveMet`, or MISMATCH / retry | icon shown, highlighted | — |
| Mid-step match | Full observation == expected | VERIFIED frame | Partial without Full baseline never VERIFIED |
| Mid-step mismatch | Partial differs | LIKELY, MISMATCH, *Scan all 6 faces* | — |
| 2-look Lesson | `TWO_LOOK` session from Learn | switch offered; Confirm card -> *Done* advances Expected; mismatch ladder | — |
| Camera OFF | `CAMERA_SCAN` OFF, any Lesson incl. 2-look | no `lesson:quickCheck` / `mismatch:quickCheck` / *Scan it*; Done-only works; ladder starts at rung 1 | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` ~L743-799 -- `quickCheck` composable lambda and its visibility gate (the gate becomes the highlight condition; visibility becomes `quickCheck != null`); L318 `showPhysicalMode = !isSkipCheck` (switch already in every Lesson, incl. TWO_LOOK via `isTwoLook` L180/277/298); L639-645 `stepMove`; L715 `onDone`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt` ~L235-254 -- Confirm card / MismatchLadder / `quickCheck?.invoke()` in PREDICTED/VERIFIED physical row; L293-312 Done/Next.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/MismatchLadder.kt` L65-68 -- rung 0 `mismatch:quickCheck` (unchanged).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` L454-487 -- playback icon at every step, no highlight (reference; unchanged).
- `core/application/.../session/Reduce.kt` L629 `switchMode` (no kind restriction), L1293 `quickAllowed`, L1320 `quickObserved`; read-only.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` ~L225-227 flag gate; L711-721 `isTwoLook = entry.stage in twoLookStages`.
- Tests: `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` (`setLessonContent(..., quickCheck)` L100, `enterPhysicalMode` L136, `reportMismatch` L146, Quick Check tests L1539-1700); `ui/shell/AppShellLibraryTest.kt` `openFirstTwoLookLesson()` L241; `ui/shell/AppShellCameraScanTest.kt` (`testFlags(cameraScan = ...)`); `core/application/src/test/.../session/QuickCheckSessionTest.kt`, `TwoLookSessionTest.kt`.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- show the `quickCheck` lambda whenever `quickCheck != null`; pass `highlighted = PHYSICAL_MISMATCH || decision.advance || objectiveMet || quickCheckRetry != null` to the icon; treatment per PO answer 1.
- `core/application/src/test/.../session/QuickCheckSessionTest.kt` -- mid-step (LESSON and TWO_LOOK): request accepted after one `PhysicalConfirmed` of a multi-Move step; Full match -> VERIFIED; Partial mismatch -> LIKELY + `offersFullScan`; Partial match without baseline not VERIFIED.
- `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` -- icon mid-step (not highlighted) and at boundary (highlighted); tap dispatches and starts capture; TWO_LOOK Lesson switch -> Confirm -> Done -> mismatch ladder; camera OFF hides every camera node.
- `app/src/test/.../ui/shell/AppShellCameraScanTest.kt` (or new `AppShellLessonQuickCheckTest.kt`) -- from Learn, a 2-look Lesson in physical mode: icon mid-step with `CAMERA_SCAN` on; none with it OFF and Done still advances.

**Acceptance Criteria:**
- Given any Lesson including a 2-look Lesson on the main shell, when it opens, then `lesson:physicalModeSwitch` is offered and switching shows the Confirm card, then *Done* advances the Expected State and the mismatch ladder works as Story 8.2.
- Given Physical-cube Mode with `CAMERA_SCAN` above OFF, when partway through a step's Moves, then `lesson:quickCheck` is available, highlighted only at Objective boundaries, milestones and after mismatches, *Done* still works without it, and a Partial observation never yields VERIFIED.
- Given `CAMERA_SCAN` OFF, when a Lesson renders in Physical-cube Mode, then no camera control shows and *Done* advances.

## Spec Change Log

## Review Triage Log

## Design Notes

Settled from the story, UX spec and precedent:
- *Highlighted* points = exactly Story 10.7's current offer gate (Objective boundary `decision.advance` / `objectiveMet`, which covers the end of a Stage's Lesson incl. the Stage 6 milestone; `PHYSICAL_MISMATCH`; `quickCheckRetry`). EXPERIENCE.md Physical controls: "offered (highlighted) only at the points above".
- Placement: the existing physical-row slot in `LessonPanel`; retry line, *Scan all 6 faces* and *Use my cube* keep their current conditions. No animation, no auto-start of a capture.
- Physical-mode switch, Confirm card, *Done*, Expected badge and ladder are 8.2/8.3/12.8 behavior, reused unchanged for TWO_LOOK (the reducer has no kind restriction).

Not settled (PO questions, see Auto Run Result): the visual and TalkBack treatment of highlighted vs plain; whether Solution Playback / Algorithm practice also gain the highlight in this story.

## Verification

**Commands:**
- `./gradlew :core:application:test` -- pass.
- `./gradlew :app:testDebugUnitTest` -- pass.
- `./gradlew spotlessCheck build` -- pass.

## Auto Run Result

Status: blocked
Blocking condition: intent gap

Questions for the PO:
1. How should a *highlighted* Quick Check icon (Objective boundary, milestone, after a mismatch) differ from the plain always-available one mid-step, and does TalkBack announce the difference? Recommended: highlighted = accent-filled icon button (`accent` container, `onAccent` icon; DESIGN.md "one accent ... used for action and highlight"), plain = the current `IconButton` in `inkSecondary`; TalkBack label "Quick Check" in both, with no extra state copy.
2. EXPERIENCE.md says the same highlight rule applies to Solution Playback and Algorithm practice, where Story 10.7/14.2 show a plain icon at every step. Should 14.3 also add the highlight there, or stay Lesson-only as its ACs read? Recommended: Lesson-only; raise playback parity as a follow-up issue.
