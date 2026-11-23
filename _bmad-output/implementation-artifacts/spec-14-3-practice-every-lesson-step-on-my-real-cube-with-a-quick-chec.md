---
title: 'Story 14.3: Practice every Lesson step on my real cube, with a Quick Check at any step'
type: 'feature'
created: '2026-11-22'
status: 'done'
baseline_revision: 'fde4e82efa3581a6cc49ebb0d24adc4b192c08ba'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A Hint Ladder climb that dispatches HintRequested while a Quick Check is pending may bump the revision and discard the capture as stale.
    evidence: |-
      Pre-existing since Story 10.7 (unverified). Settle with a test that dispatches HintRequested while pendingQuickCheck is set and checks whether the observation is rejected as stale.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt request(full)
    severity: medium (unverified)
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

**Never:** reducer or Assess changes; Quick Check in `PHYSICAL_AWAITING_CONFIRM` beyond the Confirm card's *Scan it*; changes to Solution Playback or Algorithm practice (PO answer 2: Lesson-only; playback parity is follow-up #298); editing planning artifacts.

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
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` L454-487 -- playback icon at every step, no highlight (reference; unchanged per PO answer 2).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` L84-126 -- precedent for test-only `SemanticsPropertyKey<Boolean>` state flags (not announced by TalkBack).
- `app/src/test/.../ui/shell/AppShellCameraScanTest.kt` L86-105 -- existing finale tests assert `lesson:quickCheck` exists (GA + capture) / absent (OFF); must stay green.
- `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` L1642-1650 `quickCheckUnavailableKeepsExistingLadderAndHidesCameraNodes` -- no-capture case; stays green.
- `core/application/.../session/Reduce.kt` L629 `switchMode` (no kind restriction), L1293 `quickAllowed`, L1320 `quickObserved`; read-only.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` ~L225-227 flag gate; L711-721 `isTwoLook = entry.stage in twoLookStages`; ~L756-805 `practiceEntry` (Stage practice and `"two-look-practice"`) also renders `LessonScreen` (`isPractice = true` -> `SessionKind.PRACTICE`, physical switch shown) -- out of scope, must keep Story 10.7 behavior; ~L806+ skip-check (`showPhysicalMode = false`).
- Tests: `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` (`setLessonContent(..., quickCheck)` L100, `enterPhysicalMode` L136, `reportMismatch` L146, Quick Check tests L1539-1700); `ui/shell/AppShellLibraryTest.kt` `openFirstTwoLookLesson()` L241; `ui/shell/AppShellCameraScanTest.kt` (`testFlags(cameraScan = ...)`); `core/application/src/test/.../session/QuickCheckSessionTest.kt`, `TwoLookSessionTest.kt`.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- compute `highlighted = PHYSICAL_MISMATCH || decision.advance || objectiveMet || quickCheckRetry != null` and `everyStep = session.kind == SessionKind.LESSON || session.kind == SessionKind.TWO_LOOK`; pass the `quickCheck` lambda when `quickCheck != null && (everyStep || highlighted)` (so a `PRACTICE` session keeps exactly Story 10.7's gate); the accent treatment applies only when `everyStep && highlighted` (a `PRACTICE` session always renders the plain icon); highlighted renders `FilledIconButton` (`containerColor = tokens.accent`, icon tint `tokens.onAccent`), plain renders the current `IconButton` with icon tint `tokens.inkSecondary`; both keep tag `lesson:quickCheck`, the same `contentDescription` "Quick Check" and the same `request(false)` click; expose the state to tests through a new `QuickCheckHighlightedKey = SemanticsPropertyKey<Boolean>("QuickCheckHighlighted")` set on the button (no stateDescription, no new copy).
- `core/application/src/test/.../session/QuickCheckSessionTest.kt` -- mid-step (LESSON and TWO_LOOK): request accepted after one `PhysicalConfirmed` of a multi-Move step; Full match -> VERIFIED; Partial mismatch -> LIKELY + `offersFullScan`; Partial match without baseline not VERIFIED.
- `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` -- LESSON-kind mid-step on a multi-Move step after one *Done* (not at step start) and TWO_LOOK mid-step: `QuickCheckHighlightedKey` false, same content description; at boundary, after mismatch and with a pending `quickCheck:retry` mid-step: true; the drawn colors match (`captureToImage()`: accent container when highlighted, no accent when plain); mid-step Full observation -> `stage:frame` VERIFIED, mid-step Partial mismatch -> Likely + `mismatch:scanAll`; no `lesson:quickCheck` while the Confirm card shows; a PRACTICE session (Stage practice) mid-step shows no `lesson:quickCheck` and at its Objective boundary shows it with `QuickCheckHighlightedKey` false; at `fontScale = 2f` on `w600dp` the mid-step physical row shows the icon and *Done* fully (no clipping); tap dispatches and starts capture; TWO_LOOK Lesson switch -> Confirm -> Done -> mismatch ladder; camera OFF hides every camera node.
- `app/src/test/.../ui/shell/AppShellCameraScanTest.kt` (or new `AppShellLessonQuickCheckTest.kt`) -- from Learn, a 2-look Lesson in physical mode: icon mid-step with `CAMERA_SCAN` on; none with it OFF (also no `confirmCube:scan`) and Done still advances.

**Acceptance Criteria:**
- Given any Lesson including a 2-look Lesson on the main shell, when it opens, then `lesson:physicalModeSwitch` is offered and switching shows the Confirm card, then *Done* advances the Expected State and the mismatch ladder works as Story 8.2.
- Given Physical-cube Mode with `CAMERA_SCAN` above OFF, when partway through a step's Moves, then `lesson:quickCheck` is available, highlighted only at Objective boundaries, milestones and after mismatches, *Done* still works without it, and a Partial observation never yields VERIFIED.
- Given `CAMERA_SCAN` OFF, when a Lesson renders in Physical-cube Mode, then no camera control shows and *Done* advances.

## Spec Change Log

### 2026-11-22 — Review loop 1 (bad_spec)
- **Trigger:** edge-case and intent reviewers: the visibility change had no session-kind condition, so `PRACTICE` sessions (Stage practice, 2-look LL practice), which also render `LessonScreen` in Physical-cube Mode, gained an always-visible icon, beyond the Lesson-only scope (PO answer 2). Also folded in: test gaps (LESSON-kind truly mid-step, retry highlight, drawn colors, screen-level VERIFIED / Likely, Confirm-card absence, 200% font fit, shell OFF Scan it).
- **Amended:** Code Map (`practiceEntry`), Execution task for `LessonScreen.kt` (`everyStep` kind condition; PRACTICE keeps the 10.7 gate and plain icon), test task list, Design Notes (Lesson-only = LESSON + TWO_LOOK).
- **Known-bad state avoided:** Quick Check icon shown at every step in Stage practice and 2-look LL practice.
- **KEEP:** `QuickCheckHighlightedKey` semantics key (test-only, no stateDescription); `FilledIconButton` with `filledIconButtonColors(accent, onAccent)` for highlighted, `IconButton` with `inkSecondary` tint for plain, both tagged `lesson:quickCheck` with the same "Quick Check" label and `request(false)`; the three `QuickCheckSessionTest` mid-step tests (`midStep(kind, full)` over `oneLongStepLesson()`); `PhysicalLessonScreenTest` `isTwoLook` helper parameter, `twoLookEntry`, `assertQuickCheckHighlighted` and the two TWO_LOOK tests; `AppShellLessonQuickCheckTest` (Learn -> stage-08 -> lesson-08-01 after `passSkipCheck`). The first attempt's diff is at /tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/dd9a5691-dbea-4274-b41f-65e1278e3158/scratchpad/14-3-attempt-1.patch for this run.

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 24 findings — high 0, medium 6, low 9, false 8, maybe-false 1
- findings:
  - `[medium]` `[bad_spec]` (edge, intent) Visibility has no kind condition: PRACTICE sessions (Stage practice, 2-look LL practice) now show the icon at every step — verified at AppShell.kt practiceEntry -> LessonScreen(isPractice) and quickAllowed accepting PRACTICE; amended Execution task with `everyStep` (LESSON/TWO_LOOK) condition
  - `[medium]` `[bad_spec]` (verification-gap) Only the semantics flag is tested, never the accent / inkSecondary colors — filed evidence trusted; added a captureToImage color check to the test task list
  - `[medium]` `[bad_spec]` (verification-gap; blind) Retry-pending highlight is untested — filed evidence trusted; added a mid-step `quickCheck:retry` -> highlighted test to the task list
  - `[false]` `[reject]` (blind) Diff out of date vs spec status / empty logs — the diff was staged before step-04 set `in-review`; spec status edits are workflow bookkeeping, logs fill at triage
  - `[low]` `[reject]` (blind) Two button branches duplicate the Icon body; redundant tint — cosmetic; FilledIconButton and IconButton differ in shape/defaults, so the refactor is not a direct correction
  - `[false]` `[reject]` (blind) `QuickCheckHighlightedKey` is public — follows the public test-only key precedent in StickerReviewNet.kt L84-126; no caller or rule named that this breaks
  - `[low]` `[reject]` (blind) Second-tap-while-pending assertion may not exercise the UI guard — even without the UI guard, Reduce.kt quickRequested ignores a request while pending, so the observable outcome is the same; test-tightening only
  - `[medium]` `[bad_spec]` (blind, edge, intent) The LESSON-kind screen test asserts plain at step start, not partway through a multi-Move step — verified: assertion runs right after enterPhysicalMode; task list now requires a multi-Move LESSON step after one Done
  - `[low]` `[reject]` (blind) Highlight not tested for objectiveMet alone or turning back off — same gate expression as Story 10.7, already covered by its tests; extra branch tests add no named protection
  - `[low]` `[bad_spec]` (blind, intent) Shell-level CAMERA_SCAN OFF test checks only `lesson:quickCheck` — folded into the task list: also assert no `confirmCube:scan` through AppShell
  - `[low]` `[reject]` (blind) Shell test hardcodes stage/lesson ids — existing shell tests use shipped ids directly; a content rename would fail loudly
  - `[low]` `[reject]` (blind) Core test names and `assertEquals(true, ...)` style — naming/style only; the U2 precondition holds because a U2 changes every requested face set that includes U, F, R, B or L
  - `[low]` `[bad_spec]` (blind) No test that the icon stays off the Confirm card — LessonPanel only invokes the slot outside AWAITING_CONFIRM; cheap assertion folded into the task list
  - `[medium]` `[bad_spec]` (blind) 200% font on 600 dp not checked with the icon now always in the physical row — AD-6 in Always; folded a fontScale 2 / w600dp test into the task list
  - `[low]` `[reject]` (edge) Icon looks tappable while a capture is pending — pre-existing at the 10.7 gate points; a disabled state is a new UI behavior the PO did not settle
  - `[maybe-false]` `[reject]` (edge) Icon shown when tutorConfig is null — every LessonScreen entry passes `entry.tutorConfig` from the shipped bundle; would need a null-config entry path to matter, and at worst a tap is ignored (low)
  - `[medium]` `[bad_spec]` (intent) Mid-step VERIFIED frame and Likely + Scan all 6 faces are only tested in the reducer — matrix rows are screen-level; folded screen tests into the task list
  - `[false]` `[reject]` (intent) In MISMATCH the highlight sits on the ladder rung-0 copy — the matrix row says highlighted in MISMATCH; the ladder rung 0 is where the slot renders then (MismatchLadder.kt L65-68), as before
  - `[low]` `[reject]` (intent) Camera OFF split between shell flag and screen no-capture — duplicate of the shell OFF row above; both surfaces are covered
  - `[false]` `[reject]` (intent) Highlight is not announced to TalkBack — PO answer 1: label stays "Quick Check" with no extra state copy
  - `[false]` `[reject]` (intent) Diff edits a planning artifact — the spec lives under implementation-artifacts, not planning-artifacts
  - `[false]` `[reject]` (intent) 2-look Lesson from Learn path — reported as matched; no divergence
  - `[false]` `[reject]` (intent) Session kinds: diff uses R3b — duplicate claim of the first row; routed there
  - `[false]` `[reject]` (blind) Lesson-only scope beyond practice — covered by the first row

### 2026-11-22 — Review pass (loop 1)
- verdicts: 16 findings — high 0, medium 0, low 8, false 7, maybe-false 1
- findings:
  - `[low]` `[patch]` (blind) `quickCheckEveryStep` duplicates `SessionKind.isTaught` — fixed: now `session.kind?.isTaught == true`
  - `[false]` `[reject]` (blind) Retry term highlights beyond the AC — the intent contract matrix lists MISMATCH / retry as highlighted (Story 10.7 gate); dropping it would edit the spec
  - `[false]` `[reject]` (blind) Highlight is colour-only, not announced — PO answer 1: TalkBack label stays "Quick Check", no extra state copy
  - `[low]` `[patch]` (blind, edge) Swapping FilledIconButton/IconButton rebuilds the node and can drop TalkBack focus when the highlight flips — fixed: one FilledIconButton whose colors switch (accent/onAccent vs transparent/inkSecondary); pixel and highlight tests still pass
  - `[low]` `[reject]` (blind, edge) No feedback when tapping while a capture is pending — carried: pre-existing at the 10.7 gate points; a disabled state is a new UI behavior the PO did not settle
  - `[false]` `[reject]` (blind) 2-look mid-step tests assert only !objectiveMet — they also assert `QuickCheckHighlightedKey` false, and highlight includes `decision.advance`, so a boundary would fail them
  - `[low]` `[reject]` (blind) Boundary test relies on lesson-01-02 having a one-Move step — deterministic shipped content; a content change fails loudly
  - `[low]` `[reject]` (blind) Only GA tested for "above OFF" — `isEnabled` for BETA is covered by the existing AppShellCameraScanTest BETA test
  - `[low]` `[reject]` (blind) Pixel test light theme only; 200% test plain state only — theme tokens are design-system values; the highlighted button is the same size as the plain one
  - `[false]` `[reject]` (blind) Public test-only semantics key — carried: follows StickerReviewNet.kt precedent; no named harm
  - `[low]` `[reject]` (intent) Practice icon tint now explicit inkSecondary — PO answer 1 defines the plain icon as inkSecondary; spec says PRACTICE renders the plain icon
  - `[false]` `[reject]` (intent) Highlight visual only — PO answer 1
  - `[low]` `[reject]` (intent) Some matrix rows proved at screen level with an injected capture, not through the shell — the AD-18 flag gate is proved at the shell (GA and OFF); the rest is the established screen-test surface
  - `[false]` `[reject]` (edge) Done while a capture is pending discards the result as stale — by design: Done never waits on a Quick Check (Boundaries); the stale result shows the retry line
  - `[maybe-false]` `[defer]` (edge) A running Hint Ladder climb can bump the revision during a capture — pre-existing since Story 10.7; would need a test that dispatches HintRequested while a Quick Check is pending to settle
  - `[false]` `[reject]` (edge) Second Done in the shell OFF test may land on Next — the test is deterministic and passes; after one Done the step is mid-step, so `lesson:done` exists
- verification-gap layer: no gaps found.

## Design Notes

Settled from the story, UX spec and precedent:
- *Highlighted* points = exactly Story 10.7's current offer gate (Objective boundary `decision.advance` / `objectiveMet`, which covers the end of a Stage's Lesson incl. the Stage 6 milestone; `PHYSICAL_MISMATCH`; `quickCheckRetry`). EXPERIENCE.md Physical controls: "offered (highlighted) only at the points above".
- Placement: the existing physical-row slot in `LessonPanel`; retry line, *Scan all 6 faces* and *Use my cube* keep their current conditions. No animation, no auto-start of a capture.
- Physical-mode switch, Confirm card, *Done*, Expected badge and ladder are 8.2/8.3/12.8 behavior, reused unchanged for TWO_LOOK (the reducer has no kind restriction).
- Lesson-only (PO answer 2) means `SessionKind.LESSON` and `TWO_LOOK`. `LessonScreen` also hosts `PRACTICE` sessions (Stage practice, 2-look LL practice); they are not Lessons and keep Story 10.7's gate with the plain icon, unchanged.

PO answers (2026-11-22):
1. Highlighted = accent-filled icon button (`accent` container, `onAccent` icon; DESIGN.md "one accent ... used for action and highlight"); plain = the current `IconButton` in `inkSecondary`. TalkBack label stays "Quick Check" in both states, no extra state copy. The switch between the two is immediate (no animation, no auto-play of a capture); nothing is cleared when the highlight goes away.
2. Scope is Lesson-only, as the ACs read; Solution Playback and Algorithm practice keep their plain icon. Playback parity: https://github.com/hebelmx/RubikXplorer/issues/298 (not here).

## Verification

**Commands:**
- `./gradlew :core:application:test` -- pass.
- `./gradlew :app:testDebugUnitTest` -- pass.
- `./gradlew spotlessCheck build` -- pass.

## Auto Run Result

Earlier pass: blocked on intent gap (two PO questions on the highlight treatment and scope). Both answered by the PO on 2026-11-22 and recorded in Design Notes; planning resumed.

Final pass (2026-11-22): done.
- **Summary:** In a physical Lesson (LESSON and TWO_LOOK), the Quick Check icon now shows at every step when a capture is wired (CAMERA_SCAN above OFF), accent-filled only at Story 10.7's points (mismatch, Objective boundary/met, retry); PRACTICE sessions keep the 10.7 gate with the plain icon. No reducer, Assess, copy, Playback or Algorithm-practice change.
- **Files:** `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` (visibility/highlight gate, single FilledIconButton, `QuickCheckHighlightedKey`); `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` (12 new screen tests); `app/src/test/.../ui/shell/AppShellLessonQuickCheckTest.kt` (new, 2-look Lesson from Learn, GA/OFF); `core/application/src/test/.../session/QuickCheckSessionTest.kt` (3 mid-step reducer tests, LESSON and TWO_LOOK).
- **Review:** loop 1 bad_spec (PRACTICE scope + test gaps) re-derived; final pass patched 2 low (isTaught reuse, one button so focus survives highlight flips); 1 deferred (maybe-false Hint Ladder revision race, pre-existing); all rejections logged in the triage log with reasons.
- **Follow-up review:** false (patched: 0 high, 0 medium, 2 low).
- **Verification:** `./gradlew :core:application:test :app:testDebugUnitTest spotlessCheck build` — BUILD SUCCESSFUL after the patches.
- **Residual risks:** device check on Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE (human gate) not done; pixel test covers light theme only.
