---
title: 'Story 8.3: Report a mismatch honestly without a camera'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: 'f635a01bce704e6493a7252da40db84bc74f08bd'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** When a Learner's physical cube does not match what the tutor expects, or the Learner is unsure what turn was made, the app must not guess or pretend to see the real cube without evidence.

**Approach:** Implement honest mismatch reporting from the overflow menu, escalate sequentially through the no-camera discovery ladder (Move keys → one-face picker → Manual Cube Editor), use an entered move as evidence in `Assess` (yielding *verified* only with a unique candidate, otherwise *Likely* with the candidate shown), and offer Guided recovery when a validated re-entered cube (`EditorEntered`) is accepted as a Full observation.

## Boundaries & Constraints

**Always:**
- Keep pure JVM rules in `:core:*`: Android and Compose dependencies stay in `:app` only.
- In `PHYSICAL_MISMATCH`, Certainty starts strictly at ? `UNKNOWN`; never guess an error without evidence.
- Present the no-camera discovery ladder in FR-9 order: (1) "Which turn did you make?" with Move keys → (2) one-face picker ("Look at the front face — which corner is white?") → (3) "Let's re-enter your cube" via the Manual Cube Editor.
- When the Learner enters the Move they made, `Assess` evaluates it as evidence: Certainty is `VERIFIED` only if exactly one candidate explains the state; otherwise `LIKELY` with the candidate shown.
- An accepted re-entered cube (`EditorEntered`) sets `observed := PhysicalObservation.Full(state)`, updates `expected := state` and `virtual := state`, transitions to `PHYSICAL_PREDICTED`, acts as an undo barrier, and offers Guided recovery back to the lesson objective.
- Keep line coverage ≥ 80% on pure JVM modules (`:core:*`).
- Localize all learner-facing copy in `en.json` and `strings.xml`.

**Never:**
- Never display Quick Check (camera scan) while camera scanning is unavailable (Epic 10).
- Never report `VERIFIED` when multiple candidates explain the evidence, or without evidence.
- Never silently replace the learner's state with the expected state.
- Never add internet permissions or third-party analytics/crash SDKs (AD-13).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Report mismatch | Learner selects *My cube doesn't match* or *I'm not sure what I did* in physical mode | Dispatches `MismatchReported`; `syncState` becomes `PHYSICAL_MISMATCH`; Certainty chip shows ? *Unknown*; Rung 1 prompt "Which turn did you make?" is shown with Move key bar. | No error; no-op if already mismatch |
| Enter move: unique candidate | Learner enters turn `R` where only `R` produces the observed state from previous state | `Assess` runs; Diagnosis has `VERIFIED` certainty with candidate `R`; Certainty chip displays ✓ *Verified*; recovery prompt offers *Undo*, *Guide me back*, *Try again*. | If move produces invalid state, prevented by notation bounds |
| Enter move: ambiguous candidates | Learner enters turn where multiple candidate sequences produce the observed state | `Assess` runs; Diagnosis has `LIKELY` certainty; Certainty chip displays ≈ *Likely* with candidate shown; recovery prompt offers *Undo*, *Guide me back*, *Try again*. | Falls back to LIKELY |
| Advance to one-face picker | Learner taps Next / skip from "Which turn did you make?" | Tutor escalates to Rung 2: "Look at the front face — which corner is white?" with 4 corner options (Top-left, Top-right, Bottom-left, Bottom-right). | Stays at ? Unknown |
| Advance to re-enter cube | Learner taps Next from one-face picker or chooses to re-enter | Tutor escalates to Rung 3: "Let's re-enter your cube" with button to open Manual Cube Editor. | Stays at ? Unknown until editor accepted |
| Accept re-entered cube | Learner validates and accepts cube state in Manual Cube Editor | Dispatches `EditorEntered(state)`; `observed := PhysicalObservation.Full(state)`; `expected := state`; `virtual := state`; transitions to `PHYSICAL_PREDICTED`; Guided recovery is offered (*Guide me back*). | Editor enforces full cube validation (FR-14) |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- Adds `"msg.physical.mismatch.which-turn"`, `"msg.physical.mismatch.one-face"`, `"msg.physical.mismatch.re-enter"`, `"msg.physical.mismatch.candidate"`, and related content strings.
- `app/src/main/res/values/strings.xml` -- UI string resources: `physical_mismatch_my_cube_doesnt_match`, `physical_mismatch_not_sure_what_i_did`, `physical_mismatch_which_turn`, `physical_mismatch_one_face`, `physical_mismatch_reenter`, corner picker strings.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Assess.kt` -- Provides `diagnosePhysicalMove` (or evidence-based diagnosis for physical moves) returning `Diagnosis` with `VERIFIED` for a single candidate or `LIKELY` with candidates, and updates `decide` to support physical mode diagnosis and certainty reporting.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Handles `MismatchReported`, `PhysicalConfirmed` with move in `PHYSICAL_MISMATCH`, and wires Guided recovery offer upon `EditorEntered`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/CertaintyChip.kt` -- Implements `CertaintyChip` composable (`rounded.full`, icon + label + status token color) with test tag and semantics.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/MismatchLadder.kt` -- Implements `MismatchLadder` composable for the no-camera discovery sequence: "Which turn did you make?" with Move keys → one-face picker → "Let's re-enter your cube".
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Adds overflow menu with *My cube doesn't match* and *I'm not sure what I did*, hosts the mismatch ladder when in `PHYSICAL_MISMATCH`, and displays candidate when diagnosis certainty is `LIKELY`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Integrates `CertaintyChip`, routes editor opening from mismatch ladder, and connects ladder move entry to session events.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/AssessTest.kt` -- Tests `Assess` diagnosis with unique candidate (`VERIFIED`) and ambiguous candidates (`LIKELY`).
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Tests `PHYSICAL_MISMATCH` transitions, move evidence diagnosis, and `EditorEntered` offering Guided recovery.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- UI and Compose tests asserting AC 1, AC 2, and AC 3 in `LessonScreen`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- Add mismatch ladder text keys -- Localization for tutor questions and candidate copy.
- `app/src/main/res/values/strings.xml` -- Add mismatch UI strings (`physical_mismatch_my_cube_doesnt_match`, `physical_mismatch_not_sure_what_i_did`, `physical_mismatch_which_turn`, `physical_mismatch_one_face`, `physical_mismatch_reenter`, corner picker options) -- UI localization resources.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Assess.kt` -- Implement evidence-based move diagnosis in `Assess` returning `VERIFIED` on unique candidate and `LIKELY` with candidates, and allow physical diagnoses in `decide` -- Core domain certainty and diagnosis engine.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Update `confirmPhysical` in `PHYSICAL_MISMATCH` to diagnose move and transition/update state, and ensure `enterEditor` offers Guided recovery -- Application session reducer updates.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/CertaintyChip.kt` -- Create `CertaintyChip` Composable displaying icon + label + status token color -- Status indicator for certainty.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/MismatchLadder.kt` -- Create `MismatchLadder` Composable rendering the 3 non-camera rungs -- UI for honest discovery sequence.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Add overflow menu (*My cube doesn't match*, *I'm not sure what I did*), render `MismatchLadder` in `PHYSICAL_MISMATCH`, and show candidates for `LIKELY` diagnosis -- Panel presentation.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Integrate `CertaintyChip` in stage, connect ladder rungs to `ManualCubeEditor`, and dispatch mismatch events -- Screen orchestration.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/AssessTest.kt` -- Add unit tests for entered move diagnosis with unique and non-unique candidates -- Domain test coverage.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Add tests verifying `PHYSICAL_MISMATCH` handling, entered move diagnosis, and `EditorEntered` Guided recovery offering -- Application test coverage.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Add Compose and unit tests for AC 1, AC 2, and AC 3 -- UI acceptance verification.

**Acceptance Criteria:**
- Given *My cube doesn't match* or *I'm not sure what I did*, when chosen, then the chip shows ? *Unknown* and the tutor asks in FR-9 order, skipping the Quick Check rung while scanning is unavailable: "Which turn did you make?" with Move keys → a one-face picker ("Look at the front face — which corner is white?") → "Let's re-enter your cube" via the Manual Cube Editor.
- Given the Learner enters the Move they made, when `Assess` runs, then the Diagnosis uses it as evidence, and Certainty is *verified* only with a unique candidate, otherwise ≈ *Likely* with the candidate shown.
- Given a re-entered cube (`EditorEntered`), when accepted, then it becomes the Full observation and a Guided recovery is offered.

## Spec Change Log

## Review Triage Log

- `[low]` `[patch]` `PhysicalLessonScreenTest.kt` - `CertaintyChip` used `mergeDescendants = true` which merged the icon and label semantics in testing - removed `mergeDescendants = true` so child icon and label remain queryable.
- `[low]` `[patch]` `PhysicalLessonScreenTest.kt` - test queried `recovery:guideMeBack` instead of `lesson:recovery:guideMeBack` - updated test tag selector to match `RecoveryPrompt`.
- `[low]` `[patch]` `PhysicalModeSessionTest.kt` - reference to non-existent `shippedBundle` in test - replaced with `shippedLesson("lesson-03-01")`.
- `[medium]` `[patch]` `Reduce.kt` - `enterEditor` cleared `lastDiagnosis` because `EditorEntered` was missing from `keeps` set - added `event is SessionEvent.EditorEntered` to `keeps` to preserve discrepancy diagnosis for Guided recovery.

## Auto Run Result

### Summary of Implemented Change
- Implemented honest mismatch reporting from physical controls overflow menu (*My cube doesn't match*, *I'm not sure what I did*) transitioning the session to `PHYSICAL_MISMATCH`.
- Rendered `CertaintyChip` on stage well in `PHYSICAL_MISMATCH` with ? `Unknown`, and escalated sequentially through the no-camera discovery ladder (Move keys → one-face picker → Manual Cube Editor).
- Evaluated entered moves as evidence in `Assess.diagnosePhysicalMove`, reporting `VERIFIED` certainty only with a unique candidate, otherwise `LIKELY` with candidate displayed.
- Validated re-entered cube states (`EditorEntered`), adopting them as `PhysicalObservation.Full` and offering Guided recovery back to the lesson objective.

### Files Changed
- `core/domain/src/main/resources/content/strings/en.json` -- Added domain content keys for mismatch discovery ladder and candidate copy.
- `app/src/main/res/values/strings.xml` -- Added UI strings for mismatch overflow menu, questions, corner picker options, and candidate display.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Assess.kt` -- Implemented `diagnosePhysicalMove`, `unknown` helper, and updated `decide` to evaluate physical mode certainty and diagnoses outside `TEST_ME`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/AssessTest.kt` -- Added unit tests for unique candidate (`VERIFIED`), ambiguous candidates (`LIKELY`), and physical mode decision handling.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Updated reducer for `MismatchReported`, `PhysicalConfirmed` in `PHYSICAL_MISMATCH`, and preserved diagnosis on `EditorEntered`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` -- Updated `decision` to supply physical and mismatch context and updated `recoveryGoal` with `pathEntry`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Documented mismatch transition and editor entry Guided recovery contracts.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt` -- Added tests for mismatch transitions, move evidence diagnosis, and editor entry Guided recovery.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/CertaintyChip.kt` -- Created `CertaintyChip` composable (`rounded.full`, icon + label + status token color).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/MismatchLadder.kt` -- Created `MismatchLadder` composable for the 3 no-camera rungs.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- Added overflow menu, rendered mismatch ladder in `PHYSICAL_MISMATCH`, and displayed candidate copy.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Integrated `CertaintyChip` on stage, connected ladder move entry and editor transitions.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- Added Robolectric Compose tests asserting AC 1, AC 2, and AC 3.

### Review Findings Breakdown
- Patches applied: 4 patches applied (semantics unmerged icon/label in `CertaintyChip`, test tag alignment in `PhysicalLessonScreenTest`, lesson reference in `PhysicalModeSessionTest`, diagnosis retention in `Reduce.kt`).
- Items deferred: 0.
- Items rejected: 0.

### Follow-up Review Recommendation
`false` — All patched code verified through full test suite, Kover coverage verification, spotless, and `./gradlew spotlessCheck build`.

### Verification Performed
- `./gradlew :core:domain:test`: Passed all domain tests including `AssessTest`.
- `./gradlew :core:application:test`: Passed all application tests including `PhysicalModeSessionTest`.
- `./gradlew :app:testDebugUnitTest`: Passed all UI and Compose unit tests including `PhysicalLessonScreenTest`.
- `./gradlew spotlessApply`: Applied spotless formatting.
- `./gradlew spotlessCheck build`: Passed full project build, lint, Kover coverage gates (all modules ≥ 80%), and APK assembly.

### Residual Risks
- None. Camera scan integration intentionally deferred to Epic 10 as specified.

## Verification

**Commands:**
- `./gradlew :core:domain:test` -- expected: all domain tests pass including new Assess move evidence tests.
- `./gradlew :core:application:test` -- expected: all application tests pass including physical mismatch and EditorEntered guided recovery tests.
- `./gradlew :app:testDebugUnitTest` -- expected: all UI and Compose unit tests pass including PhysicalLessonScreenTest.
- `./gradlew spotlessCheck build` -- expected: entire project compiles, passes spotless, tests, Kover (>= 80%), and lint gates cleanly.
