---
title: 'Story 8.6: Confirm the first physical solve and prove no desync'
type: 'feature'
created: '2026-11-08'
status: 'in-review'
baseline_revision: '2f4ee9bf37b4e48da1c43f354d5badec42b646b7'
review_loop_iteration: 1
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Celebrating a physical solve before verification violates the core honesty contract (FR-7, FR-9), while complex multi-mode state transitions (moves, confirmations, undo, mode switches, pause, kill, and resume) risk silent desynchronization between virtual state, expected physical state, and the rendered cube (NFR-4).

**Approach:** At the end of Stage 6 in physical mode upon reaching the expected solved state, display "Your cube should be solved now. Check it with the camera, or tell me if it is." and suppress milestone celebrations until explicit learner confirmation (*Yes, it's solved*). Build an end-to-end instrumented and Robolectric verification suite asserting zero silent desync between `virtual`, `expected`, and the rendered representations across all interaction and lifecycle transitions.

## Boundaries & Constraints

**Always:**
- Keep pure JVM rules in `:core:*`: Android, Compose, and UI dependencies stay in `:app` only.
- In physical mode at the end of Stage 6 (`lesson-06-03`), meeting the Objective on the Expected State displays `"Your cube should be solved now. Check it with the camera, or tell me if it is."` (UX-DR20, UX-DR25).
- Suppress the milestone overlay and burst until explicit learner confirmation via *Yes, it's solved* (FR-9, UX-DR20).
- Preserve the *I did something else* button alongside *Yes, it's solved* so learners can report discrepancies honestly without false milestones.
- Keep the stage frame as dashed `EXPECTED` until verified evidence is provided (AD-5, FR-7).
- Verify zero silent desynchronization between `virtual`, `expected`, and rendered cube representations (both `Cube3DRenderer` and `Net2DRenderer`) across virtual moves, physical confirmations, undo, mode switches, pause, process death, and resume (NFR-4, Gate 3).
- Maintain line coverage ≥ 80% on pure JVM modules (`:core:*`).
- Localize all learner-facing copy in `en.json` and `strings.xml`.

**Never:**
- Never trigger celebratory milestone moments in physical mode without explicit learner confirmation or verified quick check (FR-7, FR-9).
- Never introduce internet permissions, analytics, or third-party tracking (AD-13).
- Never allow `virtual`, `expected`, or the rendered representation to silently diverge (NFR-4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Stage 6 objective met in physical mode | Physical mode on `lesson-06-03`, final step moves confirmed, `objectiveMet == true` | Panel displays "Your cube should be solved now. Check it with the camera, or tell me if it is." (`msg.physical.first-solve-prompt`), frame remains dashed `EXPECTED`, button reads *Yes, it's solved*, `milestone:overlay` is suppressed. | Ignored if already dismissed |
| Yes, it's solved tapped | Learner taps *Yes, it's solved* on pending physical first solve | `milestone:overlay` displays with six-color burst (`milestone:burst`), headline "You solved the Rubik's cube!", *Practice on a new scramble*, and *Continue*. | None |
| Discrepancy reported instead of solved | Learner taps *I did something else* at end of Stage 6 | Mismatch ladder or move selector opens, milestone is NOT shown, expected state adjusts according to reported move/evidence. | Escalates down mismatch ladder |
| Mode switch back to virtual at solve | Learner toggles to virtual mode at end of Stage 6 | `virtual := expected`, restore note "The on-screen cube now matches what we expected on yours." displays, virtual controls show. | Safe transition |
| Process kill at physical solve prompt | App paused/killed while awaiting *Yes, it's solved*, then resumed | Session restores to `PHYSICAL_AWAITING_CONFIRM`, expected state is preserved, rendered cube matches `expected`. | Checkpoint restore safe |
| Non-Stage 6 physical objective met | Physical mode on `lesson-01-01` or intermediate lesson | Displays standard "If your cube matches the picture, this step is done.", *Next* button enabled, milestone suppressed. | Normal step progression |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- Adds `"msg.physical.first-solve-prompt": "Your cube should be solved now. Check it with the camera, or tell me if it is."` and `"btn.physical.yes-its-solved": "Yes, it's solved"`.
- `app/src/main/res/values/strings.xml` -- Adds `physical_first_solve_prompt` and `physical_yes_its_solved` string resources.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Manages `physicalFirstSolveConfirmed` state; binds `objectiveMetCopy` to `first-solve-prompt` for `lesson-06-03` in physical mode; triggers milestone only after confirmation.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Renders *Yes, it's solved* button (`lesson:yesItsSolved`) when `isEndOfStage6 && step.objectiveMet` in physical mode.
- `app/src/sharedTest/kotlin/dev/rubikxplorer/app/session/EndToEndSyncScenario.kt` -- Common test scenario executing virtual and physical moves, confirmations, undo, mode switches, pause, kill, and resume with zero desync assertions.
- `app/src/androidTest/kotlin/dev/rubikxplorer/app/session/EndToEndSyncVerificationTest.kt` -- Instrumented end-to-end test suite (`@RunWith(AndroidJUnit4::class)`).
- `app/src/test/kotlin/dev/rubikxplorer/app/session/EndToEndSyncVerificationTest.kt` -- Robolectric unit test suite (`@RunWith(RobolectricTestRunner::class)`).
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Verifies Story 8.2 AC 3 using standard lesson and adds Story 8.6 AC 1 test for end of Stage 6 confirmation and milestone gating.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- Add domain localization keys `msg.physical.first-solve-prompt` and `btn.physical.yes-its-solved` -- Required by UX-DR20 and UX-DR25.
- `app/src/main/res/values/strings.xml` -- Add UI strings `physical_first_solve_prompt` and `physical_yes_its_solved` -- Required for Compose string resolution.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Add `physicalFirstSolveConfirmed` state, bind `isEndOfStage6` honest prompt copy, and gate milestone burst behind confirmation -- Fulfills AC 1.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Add *Yes, it's solved* button (`lesson:yesItsSolved`) for `isEndOfStage6 && step.objectiveMet` in physical mode -- Fulfills AC 1 interaction.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Update AC 3 to use `lessonEntry` and add AC 1 test for Stage 6 completion prompt and gated milestone -- Verifies AC 1.
- `app/src/sharedTest/kotlin/dev/rubikxplorer/app/session/EndToEndSyncScenario.kt` -- Create multi-mode lifecycle scenario verifying zero desync across `virtual`, `expected`, `Net2DRenderer`, and `Cube3DRenderer` -- Fulfills AC 2.
- `app/src/androidTest/kotlin/dev/rubikxplorer/app/session/EndToEndSyncVerificationTest.kt` -- Create instrumented test suite running `EndToEndSyncScenario` -- Fulfills AC 2 instrumented requirement.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/EndToEndSyncVerificationTest.kt` -- Create JVM Robolectric test suite running `EndToEndSyncScenario` -- Ensures CI gates verify zero desync.

**Acceptance Criteria:**
- Given the end of Stage 6 in physical mode, when the Objective is met on the Expected State, then "Your cube should be solved now. Check it with the camera, or tell me if it is." shows, and the milestone plays only after *Yes, it's solved* (or a Quick Check, once Epic 10 lands).
- Given an end-to-end instrumented suite, when it runs virtual and physical flows with Moves, confirmations, undo, mode switches, pause, kill and resume, then there is zero silent desync between `virtual`, `expected` and the rendered cube.

## Spec Change Log

## Review Triage Log

Review 1 (Codex, 2026-11-08, diff 2f4ee9b..c1c9ce5) — gates green (build, unit tests, kover, spotless, androidTest compile); 4 findings OPEN, not yet fixed:
- [ ] MAJOR `LessonScreen.kt:192` — toggling physical→virtual at the Stage 6 solve prompt bypasses *Yes, it's solved*: solved `expected` becomes `virtual`, `decision.satisfied` holds, milestone shows without confirmation.
- [ ] MAJOR `LessonScreen.kt:186` — `SessionHost` records `LessonCompleted` when the physical objective is met, before confirmation; on re-entry `initialCompleted` is true, so *Yes, it's solved* closes the screen and the milestone never plays.
- [ ] MAJOR `EndToEndSyncScenario.kt:208` — resume check uses the restored state as its own oracle (renderers initialised from `restoredExpected`, compared to it); pre-kill cube never retained/compared.
- [ ] MINOR `EndToEndSyncScenario.kt:183` — no desync assertion after second physical mode switch / initial confirmation; "pause" only closes persistence, no lifecycle events.

## Design Notes

In `LessonScreen`, `isEndOfStage6` evaluates `entry.lesson.id.value == "lesson-06-03"`. When in physical mode and `session.objectiveMet` holds, `objectiveMetCopy` resolves `msg.physical.first-solve-prompt` ("Your cube should be solved now. Check it with the camera, or tell me if it is.").
The action button row in `LessonPanel` displays *Yes, it's solved* (`lesson:yesItsSolved`), leaving *I did something else* available to report accidental turns.
Clicking *Yes, it's solved* sets `physicalFirstSolveConfirmed = true`, which allows `FirstSolveMilestone` to overlay the celebratory 6-color burst and display "You solved the Rubik's cube!".
For AC 2, `EndToEndSyncScenario` integrates `SessionHost` with real Room persistence (`RubikDatabase`) and both renderers (`Cube3DRenderer` and `Net2DRenderer`), exercising random moves in virtual mode, physical mode toggle, physical confirmations, undo barriers, process kill via database recreation, and session resumption, continuously verifying `renderer.renderedFacelets() == (if (isPhysical) host.state.value.expected else host.state.value.virtual).toFacelets()`.

## Verification

**Commands:**
- `./gradlew test` -- expected: all JVM unit tests pass in `:core:*` and `:app`.
- `./gradlew :app:testDebugUnitTest` -- expected: all Android/Robolectric unit tests pass.
- `./gradlew compileDebugAndroidTestKotlin` -- expected: instrumented test suite compiles cleanly without errors.
- `./gradlew spotlessCheck build` -- expected: formatting, boundary checks, SDK denylist, Kover ≥ 80%, and APK assembly pass.
