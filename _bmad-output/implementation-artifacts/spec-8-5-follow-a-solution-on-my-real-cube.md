---
title: 'Story 8.5: Follow a Solution on my real cube'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: '2b93f6c3c8887774e48180c3712339c8203a036f'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** When solving a physical cube using Solution Playback, auto-playing Moves or lacking real-cube step confirmation causes the Learner to lose their place, while claiming the cube is observed as solved without verification violates the core honesty contract.

**Approach:** Allow the `SOLVE_PLAYBACK` session to enter physical mode on tapping *I'm following on my cube*, pause auto-play and present the primary *Done* button to advance one step at a time with the MoveNote under each Move, and upon confirming the final step display "Your cube should be solved now." without asserting `observed` as solved.

## Boundaries & Constraints

**Always:**
- Keep pure JVM rules in `:core:*`: Android, Compose, and UI dependencies stay in `:app` only.
- In physical mode, auto-play is paused and manual confirmation (*Done*) advances one step (AD-20, UX-DR12).
- Each step displays the generated MoveNote from `MoveNote.of(move): Text` under the Move (AD-20).
- The cube stage frame label displays `EXPECTED` with dashed border in physical mode, never claiming a verified observation (AD-5, FR-7).
- Confirming the final step in physical mode displays "Your cube should be solved now." (`msg.playback.cube-should-be-solved`), leaving `observed` as null and `syncState` as `PHYSICAL_PREDICTED` (never asserted as observed).
- Keep line coverage ≥ 80% on pure JVM modules (`:core:*`).
- Localize all Learner-facing copy in `en.json` and `strings.xml`.

**Never:**
- Never assert *solved* as observed in physical mode on confirmation without explicit verification (FR-7).
- Never store playback position or controller in session state, events, or effects (AD-20, `PlaybackNotCheckpointedTest`).
- Never add internet permissions, analytics, or third-party SDKs (AD-13).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Follow on cube tapped | `SOLVE_PLAYBACK` session in virtual mode, user taps *I'm following on my cube* | Session enters physical mode (`mode == CubeMode.PHYSICAL`, `syncState == SyncState.PHYSICAL_PREDICTED`), auto-play paused, stage frame shows `EXPECTED` with dashed border, **Done** button shows, MoveNote shows under the Move. | Ignored if already in physical mode |
| Done tapped on non-final step | Physical playback at step `i < size - 1`, user taps **Done** | `stepForward()` advances position by 1, `PhysicalConfirmed(moves[i])` advances `expected := expected.apply(moves[i])`, MoveNote shows for next Move `moves[i+1]`. | Ignored at end of playback |
| Done tapped on final step | Physical playback at final step `i == size - 1`, user taps **Done** | `stepForward()` reaches `atEnd == true`, `PhysicalConfirmed(moves[size - 1])` advances `expected` to `CubeState.SOLVED`, UI displays "Your cube should be solved now.", `observed` remains `null`, `syncState` remains `PHYSICAL_PREDICTED`, stage frame remains `EXPECTED`. | No error |
| Step back in physical playback | Physical playback at `position > 0`, user taps step back | `stepBack()` decrements position by 1, previous Move and its MoveNote show, Done button shows for that step. | Disabled at `position == 0` |
| Mode switch back to virtual | Physical playback, user switches to virtual mode | Session enters virtual mode (`syncState == SyncState.VIRTUAL`), `virtual := expected`, restore note displays "The on-screen cube now matches what we expected on yours.", standard playback transport reappears. | Ignored if already in virtual mode |
| Empty solution | `SOLVE_PLAYBACK` session with already solved cube (`solution = []`) | Transport disabled, no MoveNote, if in physical mode displays "Your cube should be solved now." without asserting observed. | Handled safely |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- Adds `"msg.playback.follow-on-my-cube": "I'm following on my cube"` and `"msg.playback.cube-should-be-solved": "Your cube should be solved now."`
- `app/src/main/res/values/strings.xml` -- Adds `playback_follow_on_my_cube` and `playback_cube_should_be_solved` UI string resources.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Permits `SOLVE_PLAYBACK` to process `ModeSwitched` and `PhysicalConfirmed`; transitions to `PHYSICAL_PREDICTED` on physical switch; advances `expected` on physical confirmation without modifying `observed`.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Documents `SOLVE_PLAYBACK` transitions for `ModeSwitched` and `PhysicalConfirmed`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/SolvePlaybackSessionTest.kt` -- Unit tests for physical mode transitions and confirmations in `SOLVE_PLAYBACK`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/TransitionsTableTest.kt` -- Table test ensuring transition invariants remain valid.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlayback.kt` -- Updates `SolutionPlaybackState` and `SolutionPlayback` composable: provides *I'm following on my cube* action, switches to physical mode controls with **Done** button and `EXPECTED` badge, displays MoveNote under each Move, and shows "Your cube should be solved now." upon final confirmation.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlaybackTest.kt` -- Robolectric tests asserting AC 1 and AC 2 in Compose.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- Add `"msg.playback.follow-on-my-cube"` and `"msg.playback.cube-should-be-solved"` -- Content string localization.
- `app/src/main/res/values/strings.xml` -- Add `playback_follow_on_my_cube` and `playback_cube_should_be_solved` -- Android UI strings.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Handle `ModeSwitched` and `PhysicalConfirmed` for `SOLVE_PLAYBACK` sessions -- Core reducer support for physical solution playback.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Update transitions documentation for Story 8.5 -- Normative specification documentation.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/SolvePlaybackSessionTest.kt` -- Add unit tests for physical mode switching, move confirmations, and final step honesty in `SOLVE_PLAYBACK` -- Application unit test coverage.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlayback.kt` -- Implement physical mode UI, *I'm following on my cube* button, **Done** stepping, FrameLabel EXPECTED badge, and solved notice -- Solution Playback UI implementation.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlaybackTest.kt` -- Add Compose tests for tapping *I'm following on my cube*, *Done* advancing, MoveNote display, and final step completion honesty -- UI acceptance testing.

**Acceptance Criteria:**
- Given Solution Playback, when the Learner taps *I'm following on my cube*, then the session enters physical mode, each *Done* advances one step, and the MoveNote shows under each Move.
- Given the final step, when confirmed, then the app says "Your cube should be solved now." and does not assert *solved* as observed.

## Spec Change Log

## Review Triage Log

### 2026-11-08 — Review pass
- verdicts: 16 findings — high 0, medium 3, low 12, false 1, maybe-false 0
- findings:
  - `[medium]` `[patch]` `SolutionPlayback.kt:254-259`: Stepping back and tapping Done again duplicated move on `SessionState.expected` — added `Reduce.kt` support for `SessionEvent.Undo` in physical `SOLVE_PLAYBACK` sessions, updated `SolutionPlaybackState.stepBack()` to dispatch `Undo`, and verified in `SolutionPlaybackTest.kt` that expected state stays aligned and completes to `CubeState.SOLVED`.
  - `[low]` `[patch]` `SolutionPlayback.kt:243-247`: Entering physical mode after virtual progression desynchronized playback position from entry state — updated `followOnMyCube()` to reset position via `playback.scrubTo(0.0)` so real cube solve starts at initial state, and added verification test in `SolutionPlaybackTest.kt`.
  - `[low]` `[patch]` `SolutionPlayback.kt:294-298`: Fragile `playbackKey` in `rememberSolutionPlaybackState` reset playback on virtual session transitions — simplified remember keys to `(session.entry, session.solution, reduceMotion)` and handled restart via `updateSession()`.
  - `[low]` `[patch]` `SolutionPlayback.kt`: Dead localization keys in `core/domain/en.json` bypassed by direct Android string resources — updated `SolutionPlayback` to resolve `Text("msg.playback.follow-on-my-cube")` and `Text("msg.playback.cube-should-be-solved")` through `strings.resolve()`.
  - `[low]` `[patch]` `SolutionPlayback.kt:PhysicalPlaybackControls`: Confusing mode switch controls and missing semantics — extracted `physicalModeLabel` from `R.string.physical_mode`, attached `contentDescription` to `playback:physicalModeSwitch`, and added minimum touch target height.
  - `[low]` `[patch]` `SolutionPlayback.kt:354`: Missing minimum touch target height on `playback:followOnMyCube` — added `Modifier.heightIn(min = Spacing.touchMin)` ensuring 48dp minimum touch target.
  - `[low]` `[patch]` `TransitionsTableTest.kt`: Omission of `TransitionsTableTest.kt` updates for physical `SOLVE_PLAYBACK` — added normative table test scenarios covering `SOLVE_PLAYBACK` mode switching to physical, move confirmation, and returning to virtual mode.
  - `[low]` `[patch]` `SolutionPlayback.kt:updateSession`: Dual reducer execution ignoring external updates — updated `updateSession` to accept updates when `newSession.revision >= session.revision`.
  - `[low]` `[patch]` `SolutionPlaybackTest.kt`: Missing edge-case test coverage — added tests for auto-play pausing on entering physical mode, toggling physical mode via switch, and step back state rollback.
  - `[false]` `[reject]` `LessonScreen.kt:FrameLabel`: Suppression of Certainty description in physical mode — rejected: `FrameLabelTest.kt` explicitly requires and tests that Certainty description is null in physical mode per AD-5 and FR-7, where the dashed `EXPECTED` badge alone represents the unverified predicted state.
  - `[medium]` `[patch]` `SolutionPlayback.kt:254-259` (Edge Case Hunter 1): Move applied twice on step back and re-confirmation — duplicate of Finding 1; patched by enabling `Undo` and rolling back `expected`.
  - `[low]` `[patch]` `SolutionPlayback.kt:243-247` (Edge Case Hunter 2): Expected cube state starts from entry while position is advanced — duplicate of Finding 2; patched with `playback.scrubTo(0.0)`.
  - `[low]` `[patch]` `SolutionPlayback.kt:294-298` (Edge Case Hunter 3): Remember re-instantiates state when restore note clears — duplicate of Finding 3; patched by keying remember on `(entry, solution, reduceMotion)`.
  - `[medium]` `[patch]` `SolutionPlaybackTest.kt` (Verification Gap 1): Missing verification of physical playback step-back state rollback — duplicate of Finding 1; patched by extending `stepping back in physical playback decrements position and redisplays move note` to verify `expected`.
  - `[low]` `[patch]` `SolutionPlayback.kt` (Verification Gap 2): Regression gap in entering physical mode after virtual progression — duplicate of Finding 2; patched with `entering physical mode after virtual progression rewinds position and pauses`.
  - `[low]` `[patch]` `SolutionPlayback.kt` (Verification Gap 3): Missing adoption of content string keys in `en.json` — duplicate of Finding 4; patched by resolving `en.json` content keys in `SolutionPlayback`.

## Auto Run Result

### Summary of Implemented Change
- Enabled physical mode transitions and confirmations in `SOLVE_PLAYBACK` sessions: `ModeSwitched(CubeMode.PHYSICAL)` transitions directly to `SyncState.PHYSICAL_PREDICTED`, manual confirmations (`PhysicalConfirmed(move)`) advance `expected` and `history` without touching `observed` (remains `null`), and `Undo` is permitted in physical mode to roll back physical steps.
- Implemented physical mode in `SolutionPlayback`:
  - When the Learner taps *I'm following on my cube* (`playback:followOnMyCube`), auto-play is paused, position resets to 0 if advanced, the session enters physical mode, the cube stage frame displays `EXPECTED` with dashed border (`stage:frame`), each tap of the primary **Done** button (`playback:done`) advances one step and commits the move to `expected`, and the MoveNote from `MoveNote.of(move)` is displayed under each Move.
  - When the final step is confirmed (`state.atEnd`), the app displays "Your cube should be solved now." (`playback:solvedNow`), the stage shows the solved cube with the `EXPECTED` badge, and `observed` remains strictly `null` (never asserted as observed).
  - Supported stepping back in physical playback (`playback:back`), rolling back both playback position and `SessionState.expected`.
  - Supported switching back to virtual mode (`playback:physicalModeSwitch` / `playback:switchToVirtual`), aligning `virtual := expected` and presenting the restore note.
- Added comprehensive unit tests in `SolvePlaybackSessionTest.kt`, table tests in `TransitionsTableTest.kt`, and Robolectric Compose tests in `SolutionPlaybackTest.kt`.

### Files Changed
- `core/domain/src/main/resources/content/strings/en.json` -- Added `"msg.playback.follow-on-my-cube"` and `"msg.playback.cube-should-be-solved"` localization strings.
- `app/src/main/res/values/strings.xml` -- Added `playback_follow_on_my_cube` and `playback_cube_should_be_solved` UI string resources.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt` -- Added `isPhysical: Boolean` property (`syncState != SyncState.VIRTUAL`).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt` -- Supported `ModeSwitched`, `PhysicalConfirmed`, and physical `Undo` in `SOLVE_PLAYBACK` sessions.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- Documented normative transitions for `SOLVE_PLAYBACK` in physical mode.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/SolvePlaybackSessionTest.kt` -- Added tests for physical mode transitions, move confirmations, physical undo, and final step honesty in `SOLVE_PLAYBACK`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/TransitionsTableTest.kt` -- Added table scenarios for physical `SOLVE_PLAYBACK` transitions.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- Made `certainty: Certainty? = null` in `FrameLabel` for stage components without Certainty.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlayback.kt` -- Implemented physical solution playback controls, **Done** stepping, FrameLabel EXPECTED badge, MoveNote display, and solved notice.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlaybackTest.kt` -- Added Robolectric tests covering AC 1, AC 2, step back rollback, virtual progression rewinds, mode switching, and empty solutions.

### Review Findings Breakdown
- Patches applied:
  - `SolutionPlayback.kt`: Allowed `Undo` in physical `SOLVE_PLAYBACK` sessions and dispatched `Undo` on `stepBack()` to prevent duplicate move application on `expected`.
  - `SolutionPlayback.kt`: Rewound position via `scrubTo(0.0)` when entering physical mode after virtual progression.
  - `SolutionPlayback.kt`: Simplified `remember` keys in `rememberSolutionPlaybackState` to prevent unintended state resets on session changes.
  - `SolutionPlayback.kt`: Resolved `en.json` content keys `msg.playback.follow-on-my-cube` and `msg.playback.cube-should-be-solved` via `strings.resolve()`.
  - `SolutionPlayback.kt`: Added `contentDescription` on `playback:physicalModeSwitch` and added `Spacing.touchMin` touch target heights.
  - `TransitionsTableTest.kt`: Added normative table test cases for physical `SOLVE_PLAYBACK` transitions.
  - `SolutionPlaybackTest.kt`: Added tests for step-back state rollback, virtual progression rewinds, switch toggling, and autoplay pausing.
- Items deferred: None.
- Items rejected:
  - `LessonScreen.kt:FrameLabel`: Suppression of Certainty description in physical mode is required by AD-5, FR-7, and `FrameLabelTest.kt`.

### Verification Performed
- `./gradlew test`: All JVM unit tests passed across `:core:*` and `:app`.
- `./gradlew :app:testDebugUnitTest`: All Android/Robolectric unit tests passed.
- `./gradlew spotlessCheck build`: Spotless check, lint, JVM tests, Kover coverage verification (≥ 80%), module boundary checks, denylist checks, APK assembly, and R8 minification passed.

### Residual Risks
None. All acceptance criteria, constraints, and edge cases are verified.

## Design Notes

In `SolutionPlaybackState`, `isPhysical` reflects `session.isPhysical`. When `followOnMyCube()` is triggered, playback auto-play pauses, and `SessionEvent.ModeSwitched(CubeMode.PHYSICAL, at)` is dispatched. In `Reduce.kt`, `switchMode()` transitions a `SOLVE_PLAYBACK` session into `SyncState.PHYSICAL_PREDICTED` (since the solution was generated directly for the learner's entered cube).
When *Done* is tapped at step `position`, `state.stepForward()` advances position by 1, and `SessionEvent.PhysicalConfirmed(currentMove, at)` updates `expected` by the confirmed move.
At step boundaries in physical mode, `currentMove` and its `MoveNote` reflect the step to be performed (`moves[position]`).
When the final step is confirmed (`position == size`), the stage net renders the solved cube with the `EXPECTED` frame badge, `observed` remains `null`, and `playback:solvedNow` displays "Your cube should be solved now."

## Verification

**Commands:**
- `./gradlew test` -- expected: all JVM unit tests pass in `:core:*` and `:app`.
- `./gradlew :app:testDebugUnitTest` -- expected: all Android/Robolectric unit tests pass.
- `./gradlew spotlessCheck build` -- expected: formatting, boundary checks, SDK denylist, Kover ≥ 80%, and APK assembly pass.
