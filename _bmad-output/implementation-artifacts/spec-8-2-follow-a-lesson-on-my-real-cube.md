---
title: 'Story 8.2: Follow a Lesson on my real cube'
type: 'feature'
created: '2026-11-07'
baseline_revision: '3c9a22cb40ba19358233c493ae0af548d25479c8'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Done button is visible during PHYSICAL_MISMATCH state before ladder discovery.
    evidence: |-
      Handling PHYSICAL_MISMATCH with honest discovery ladder and Manual Cube Editor re-entry is explicitly scoped to Story 8.3 ("Report a mismatch honestly without a camera").
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt:246
    severity: low
---

<intent-contract>

## Intent

**Problem:** Learners following lessons with a real physical cube need UI controls to confirm their turns or enter alternative moves, verify their cube state on mode entry, see honest dashed EXPECTED badges without unevidenced certainty chips, and receive honest physical completion feedback without false celebration animations.

**Approach:** Introduce a Physical mode toggle in the Lesson top bar; display the Confirm-your-cube card (`It matches` / `Enter it`) upon switching to physical mode; render the dashed `EXPECTED` frame badge without certainty chips in predicted physical states; provide the primary **Done** confirmation and secondary *I did something else* move-entry controls; and show the honest physical completion copy ("If your cube matches the picture, this step is done.") without celebration animations when the Expected State meets the Objective.

## Boundaries & Constraints

**Always:**
- Keep pure JVM rules in `:core:*`: Android and Compose dependencies live in `:app` only.
- In physical mode (`syncState != SyncState.VIRTUAL`), the frame badge displays `EXPECTED` with a dashed 1.5dp `inkSecondary` border and suppresses Certainty chip / live region description while in predicted state.
- In `PHYSICAL_AWAITING_CONFIRM`, display the Confirm-your-cube card offering *It matches* (dispatches `PhysicalConfirmed(null)`) and *Enter it* (opens `ManualCubeEditor`, which dispatches `EditorEntered` on accept); *Scan it* remains hidden.
- Primary action in physical mode is always **Done**; secondary action is *I did something else*, which reveals the `MoveKeyBar` to enter the turn actually made.
- Tapping **Done** advances the Expected State by the current step's taught move (`Tutor.nextMove` or step moves); tapping a Move key on `MoveKeyBar` advances Expected State by the entered Move.
- When the Objective is met on the Expected State in physical mode, display "If your cube matches the picture, this step is done.", keep the `EXPECTED` frame badge, enable *Next*, and suppress celebration animations (no pulse, no milestone popup/burst).
- Switching physical mode off dispatches `ModeSwitched(CubeMode.VIRTUAL)`, cleanly returning to virtual mode.

**Never:**
- Never display *Scan it* while camera scanning is unavailable (Epic 10).
- Never trigger celebration animations or milestone celebration dialogs when meeting an Objective on an unverified expected physical state.
- Never allow View Rotation to mutate Notation, `virtual`, or `expected`.
- Never add internet permissions or third-party tracking/analytics/crash SDKs (AD-13).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Toggle Physical mode ON | User turns Physical mode switch on in LessonTopBar | Dispatches `ModeSwitched(CubeMode.PHYSICAL)`; syncState enters `PHYSICAL_AWAITING_CONFIRM`; Confirm-your-cube card appears; frame shows dashed `EXPECTED` with no Certainty chip. | No error; no-op if already in physical mode |
| Tap *It matches* | User taps *It matches* on Confirm-your-cube card | Dispatches `PhysicalConfirmed(null)`; syncState transitions to `PHYSICAL_PREDICTED`; Confirm card dismisses; step controls appear. | No error |
| Tap *Enter it* | User taps *Enter it* on Confirm-your-cube card | Opens `ManualCubeEditor`; on Accept dispatches `EditorEntered(state)`; transitions to `PHYSICAL_PREDICTED` with `expected = state`. | Invalid cube states prevented by editor validation |
| Tap **Done** on step | User taps **Done** in `PHYSICAL_PREDICTED` | Dispatches `PhysicalConfirmed(stepMove)`; `expected` and `virtual` advance by `stepMove`; tutor evaluates new state. | If no move needed, advances or no-op |
| Tap *I did something else* | User taps *I did something else* in `PHYSICAL_PREDICTED` | Reveals `MoveKeyBar` on stage; learner taps move key (e.g. `U`); dispatches `PhysicalConfirmed(Move.U)`; `expected` and `virtual` advance by `U`; `MoveKeyBar` hides. | Only valid notation moves emitted |
| Objective met in physical mode | `expected` meets lesson objective in `PHYSICAL_PREDICTED` | Panel reads "If your cube matches the picture, this step is done."; frame badge remains `EXPECTED`; *Next* button is enabled; no celebration pulse or milestone dialog. | No error |
| Toggle Physical mode OFF | User turns Physical mode switch off in LessonTopBar | Dispatches `ModeSwitched(CubeMode.VIRTUAL)`; syncState returns to `VIRTUAL`; `virtual := expected`; frame returns to `VIRTUAL` with Certainty chip; MoveKeyBar visible by default. | Redo cleared; undo barrier |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- Adds `"msg.physical.objective-met": "If your cube matches the picture, this step is done."` and related content strings.
- `app/src/main/res/values/strings.xml` -- UI string resources: `frame_expected`, `physical_mode`, `confirm_your_cube`, `confirm_cube_matches`, `confirm_cube_enter`, `physical_done`, `physical_did_something_else`, `physical_objective_met`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ConfirmCubeCard.kt` -- Composable card for *Confirm your cube* offering *It matches* and *Enter it* (*Scan it* hidden).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Integrates physical mode toggle in top bar, manages Confirm-your-cube and ManualCubeEditor display, updates FrameLabel to render dashed EXPECTED badge, suppresses celebrations/milestones in physical mode, and manages MoveKeyBar visibility in physical mode.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Renders primary **Done** button and secondary *I did something else* in physical mode, updates Objective-met panel copy to "If your cube matches the picture, this step is done.", and enables *Next*.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/FrameLabelTest.kt` -- Verifies dashed EXPECTED badge rendering in physical mode while preserving existing virtual frame label tests.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Comprehensive unit and Compose tests covering AC 1, AC 2, and AC 3 in `LessonScreen`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- Add `"msg.physical.objective-met"` and physical mode string keys -- Ensures domain content string resolution.
- `app/src/main/res/values/strings.xml` -- Add UI string resources (`frame_expected`, `physical_mode`, `confirm_your_cube`, `confirm_cube_matches`, `confirm_cube_enter`, `physical_done`, `physical_did_something_else`, `physical_objective_met`) -- Localization strings for physical mode UI.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ConfirmCubeCard.kt` -- Implement `ConfirmCubeCard` Composable -- Displays *Confirm your cube* card with *It matches* and *Enter it* buttons.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Wire physical mode toggle, FrameLabel dashed EXPECTED badge, ConfirmCubeCard/editor handling, celebration/milestone suppression, and MoveKeyBar visibility -- Core screen coordination for physical mode.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Wire primary **Done** button, *I did something else* button, and physical Objective-met copy -- Step interaction and completion presentation.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/FrameLabelTest.kt` -- Add tests for physical frame label behavior -- Asserts dashed border, EXPECTED text, and certainty description suppression.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Create tests asserting AC 1, AC 2, and AC 3 -- System-level UI acceptance verification.

**Acceptance Criteria:**
- Given the *Physical mode* toggle in a Lesson, when switched on, then a Confirm-your-cube card offers *It matches* / *Enter it* (*Scan it* hidden until Epic 10), and the frame turns to dashed EXPECTED with no Certainty chip.
- Given a step, when the Learner taps **Done** (always primary) or *I did something else* then a Move key, then the Expected State advances by the confirmed or entered Move.
- Given the Objective is met on the Expected State, when the panel updates, then it reads "If your cube matches the picture, this step is done.", keeps EXPECTED, enables *Next*, and shows no celebration.

## Spec Change Log

## Review Triage Log

### 2026-11-07 — Review pass
- verdicts: 22 findings — high 2, medium 4, low 13, false 3, maybe-false 0
- findings:
  - `[low]` `[patch]` Learner taps Done when stepMove evaluates to null on step without moves — Patched `LessonScreen.kt` so `onDone` only commits when `stepMove != null`.
  - `[low]` `[patch]` MoveKeyBar remains visible after tapping Done instead of entering move — Patched `LessonScreen.kt` to reset `showMoveKeyBar = false` in `onDone` and `onNext`.
  - `[low]` `[patch]` Physical mode switch toggle while ManualCubeEditor dialog is open — Patched `LessonScreen.kt` with `LaunchedEffect(isPhysical)` to reset editor state.
  - `[low]` `[defer]` Session enters PHYSICAL_MISMATCH state and learner taps Done button — Out of scope for Story 8.2; explicitly handled in Story 8.3 ("Report a mismatch honestly without a camera").
  - `[high]` `[patch]` Multi-step lesson progression blocked in physical mode — Patched `LessonPanel.kt` to render and enable `Next` whenever `nextEnabled` or `step.objectiveMet` is true, advancing intermediate steps via `StepAdvanced`.
  - `[low]` `[patch]` onDone fails to dismiss MoveKeyBar — Duplicate of finding 2; patched `LessonScreen.kt` to reset `showMoveKeyBar = false` in `onDone`.
  - `[low]` `[patch]` No dismiss action for MoveKeyBar when opened via I did something else — Patched `LessonScreen.kt` to reset `showMoveKeyBar = false` on `onDone`, `onNext`, and mode switch.
  - `[low]` `[reject]` Missing on-screen cancel control on ManualCubeEditor overlay — Rejected: BackHandler provides full system back/gesture navigation; editor already defines its own canonical interface.
  - `[medium]` `[patch]` msg.physical.objective-met in en.json unused via direct R.string reference — Patched `LessonPanel.kt` to resolve domain `TextKey("msg.physical.objective-met")` through `strings.resolve()`.
  - `[low]` `[patch]` Nested clickable modifier on physical mode toggle row in LessonTopBar — Patched `LessonTopBar` to remove `.clickable` on parent `Row` and let `Switch` handle clicks directly.
  - `[low]` `[patch]` onDone interactive when stepMove is null — Duplicate of finding 1; patched `LessonScreen.kt` to guard `onDone` with `stepMove != null`.
  - `[low]` `[patch]` DemonstrationPlayer retains virtual frame badge in physical mode — Patched `LessonScreen.kt` to ensure physical state is reflected.
  - `[low]` `[patch]` FrameLabel hardcodes EXPECTED for all non-virtual states — Patched `FrameLabel` to support `SyncState` mapping cleanly.
  - `[medium]` `[patch]` Direct 3D cube drag gestures commit physical moves without confirmation — Patched `LessonScreen.kt` so canvas gestures do not commit physical moves unless `showMoveKeyBar` is active.
  - `[low]` `[patch]` Multi-step lesson flow and editor back navigation omitted from tests — Patched `PhysicalLessonScreenTest.kt` with multi-step progression test.
  - `[medium]` `[patch]` Celebration suppression assertions check fictitious dialog tags — Patched `PhysicalLessonScreenTest.kt` to assert actual milestone tags (`milestone:overlay`, `milestone:burst`) on milestone-eligible lesson `lesson-06-03`.
  - `[high]` `[patch]` Multi-step lesson intermediate step progression in physical mode is untested — Patched `PhysicalLessonScreenTest.kt` with `physicalMode_multiStepLesson_advancesStepWithNext()` testing `lesson-02-05`.
  - `[low]` `[patch]` Reset toolbar button suppression in physical mode is unasserted — Patched `PhysicalLessonScreenTest.kt` to assert `node("toolbar:reset").assertIsNotEnabled()` in physical mode.
  - `[medium]` `[patch]` Learner-facing copy resolved via Android strings.xml instead of domain ContentStrings — Duplicate of finding 9; patched `LessonPanel.kt` to resolve domain `TextKey("msg.physical.objective-met")`.
  - `[false]` `[reject]` Taught move determination fallback heuristic vs domain tutor — Disproved: `Tutor.nextMove` is the canonical `:core:domain` API designed for authored path move resolution.
  - `[false]` `[reject]` Celebration and milestone suppression handled in Compose instead of reducer — Disproved: Milestone dialogs are presentation-layer components rendered exclusively by `LessonScreen.kt`.
  - `[false]` `[reject]` Tests exercise Compose UI surface rather than JVM core — Disproved: Story 8.2 is explicitly a UI feature in `:app` and properly tested via Robolectric Compose tests.

## Auto Run Result

### Summary of Implemented Change
Implemented the learner-facing Physical-cube Mode controls and state handling in Lessons:
- Added Physical mode toggle in `LessonTopBar` to switch between `VIRTUAL` and `PHYSICAL` modes.
- Displayed `ConfirmCubeCard` upon switching to physical mode offering *It matches* (confirms current state) and *Enter it* (opens `ManualCubeEditor`), keeping *Scan it* hidden until Epic 10.
- Updated `FrameLabel` to render a dashed 1.5dp `inkSecondary` border with the text `EXPECTED` and suppressed Certainty chips/announcements while in physical predicted state.
- Provided primary **Done** button and secondary *I did something else* control in `LessonPanel`, where **Done** commits the step's taught move and *I did something else* reveals `MoveKeyBar` to enter the turn actually made.
- Rendered honest physical completion copy ("If your cube matches the picture, this step is done.") when the Objective is met on the Expected State, kept the `EXPECTED` frame badge, enabled *Next*, and suppressed celebration animations (pulse and milestone dialogs).
- Handled intermediate step advancement in multi-step lessons using `Next` to dispatch `StepAdvanced`.

### Files Changed
- `core/domain/src/main/resources/content/strings/en.json` -- Added `"msg.physical.objective-met"`.
- `app/src/main/res/values/strings.xml` -- Added UI string resources for physical mode, confirm card, and button labels.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ConfirmCubeCard.kt` -- Created Confirm-your-cube card composable.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Integrated physical toggle, dashed frame label, editor handling, MoveKeyBar coordination, and celebration suppression.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Added Done, I did something else, physical completion copy, and Next button handling for intermediate and completed steps.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/FrameLabelTest.kt` -- Added unit tests for dashed EXPECTED badge rendering and certainty suppression.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Added comprehensive Robolectric Compose test suite covering AC 1, AC 2, AC 3, multi-step progression, and mode switching.

### Review Findings Breakdown
- Patches applied: 17 findings addressed (intermediate step progression in multi-step lessons, domain content string resolution for completion copy, MoveKeyBar dismissal on Done/Next, null-move guard on Done, top bar clickable cleanup, canvas gesture guard, milestone tag assertions, multi-step test, reset button assertion).
- Items deferred: 1 finding deferred to Story 8.3 (mismatch state controls).
- Items rejected: 4 findings rejected (3 false claims disproved, 1 non-standard close button rejected).

### Follow-up Review Recommendation
`false` — All patched code verified through complete test suite and `./gradlew spotlessCheck build` with zero residual risks.

### Verification Performed
- `./gradlew testDebugUnitTest`: All 349 tests passed including `PhysicalLessonScreenTest` and `FrameLabelTest`.
- `./gradlew spotlessCheck`: Formatting validated across all Kotlin and Gradle files.
- `./gradlew spotlessCheck build`: Completed full build including lint, Kover coverage verification, manifest denylist checks, and APK packaging.

### Residual Risks
- None. Camera scan integration intentionally deferred to Epic 10 as specified.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest` -- expected: All unit tests pass including FrameLabelTest and PhysicalLessonScreenTest.
- `./gradlew spotlessCheck` -- expected: Formatting passes.
