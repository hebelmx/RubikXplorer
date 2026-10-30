---
title: 'Story 2.5: Lesson screen with Show me and Guide me'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'b1d7816059f693fbf16ef38c38978fdd8a788e7f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The session reducer, predicates and `DemonstrationPlayer` exist, but the app still opens straight on the debug free cube: there is no navigation shell, no Learn list and no Lesson screen, and nothing decides when a step's Move may be shown (FR-5, FR-6, UX-DR7/8/22/23).

**Approach:** Add the tutor's first rule in `:core:domain/tutor` (a `TeachingMode` and a `TutorDecision` with `reveal` and `advance`), carry `teachingMode`, the current step and the reveal request in `SessionState` with three new events, then build in `:app` a navigation shell (Learn + Solve), a flat Learn list, and a full-screen Lesson task flow whose panel renders Objective → step card → Show me / Guide me switcher → Hint + Next, laid out by window width class.

## Boundaries & Constraints

**Always:**
- `TeachingMode` = `SHOW_ME`, `GUIDE_ME` only (Story 2.6 adds `TEST_ME`, `LET_ME_TRY`). Default `GUIDE_ME`; `LessonStarted` keeps the current mode (last mode remembered for the app process).
- `TutorDecision(objectiveId: TextKey, satisfied: Boolean, reveal: Boolean, advance: Boolean)` from a pure `Tutor.decide(lesson, entry, current, mode, stepIndex, revealRequested)`. Rules: `satisfied = lesson.completion.isSatisfied(current)` (never reads mode); `reveal = mode == SHOW_ME || revealRequested`; `advance = satisfied || current == pathState(stepIndex + 1)`, where `pathState(k)` = `entry` with the Moves of steps `0 until k` applied. KDoc says the remaining AD-5 fields arrive with Story 4.1 (`tutor/Assess`).
- `SessionState` gains `teachingMode`, `stepIndex`, `revealRequested` and a derived `decision: TutorDecision?` (null in IDLE). New events: `TeachingModeChanged(mode)`, `RevealRequested`, `StepAdvanced` (each with `at`). These change only the new `tutor` fields: no revision bump, no pending cancel, nothing undoable, redo kept. `StepAdvanced` applies only when `decision.advance && !decision.satisfied && stepIndex < steps.lastIndex` (then `stepIndex + 1`, `revealRequested = false`). `LessonStarted`, an applied Reset and `ResetConfirmed` set `stepIndex = 0`, `revealRequested = false`. `TRANSITIONS.md` gets the three rows and a `tutor` Mutates value; `TransitionsTableTest` covers them.
- The screen never decides reveal: Move chips show iff `decision.reveal`; Next enabled iff `decision.advance`; Notice shows iff `decision.advance` (Guide me) or the focused Demonstration step has fully played (Show me).
- Show me: the stage is `DemonstrationPlayer`; the step card follows `focusedStep` (Goal, Keep, Watch; Notice once `position >= firstMove + moves.size` and `position > 0`). Guide me: the stage is the session's `Net2DRenderer` with Undo / Redo / Reset toolbar, `MoveKeyBar`, `ResetConfirmation`, and the current step's `StepFocus` as `NetEmphasis`. In Guide me before reveal the Moves row holds a "Show the Move" control that dispatches `RevealRequested`; Hint dispatches the same event and is enabled iff `!decision.reveal`.
- Objective met (`decision.satisfied`): a check icon + "Objective met" in the step card head, Notice text, Next enabled, and a brief (≈600 ms) violet pulse on the current step's target pieces (`NetEmphasis.pulse`, a translucent `sticker-highlight` fill; skipped under reduce motion). Nothing auto-advances; Next on a satisfied Lesson closes it back to Learn.
- Navigation: a shell with Learn and Solve (the existing free cube) — `NavigationBar` when compact, `NavigationRail` when medium/expanded; hidden in the Lesson task flow. Learn lists every Lesson of the loaded bundle in order (row = resolved Objective, meta = Stage title); an empty bundle shows a one-line empty message; a load failure shows a one-line error. Opening a Lesson dispatches `LessonStarted` only when the session does not already hold that Lesson id, so rotation never restarts it. System back (and the top-bar close button) closes the Lesson immediately, no dialog.
- Layout by a pure `WindowWidthClass.of(widthDp)` (<600 compact, <840 medium, else expanded): compact stacks stage (≈45% height, min 280dp) over a scrolling panel; medium/expanded place stage (≈55%) left of the panel, panel capped at 560dp at expanded.
- All chrome text in `strings.xml`; content text via `Text(key)` resolved by an ICU `ContentStrings` resolver over `ContentBundle.strings` (`android.icu.text.MessageFormat`); no multi-word literals in main sources.

**Never:**
- No Test me / Let me try, `TEST_ME` gating, Hint Ladder rungs, Diagnosis, Certainty, recovery prompt, haptics, persistence of mode (Epic 6), Welcome (7.7), Stage rows (7.2), Library/You tabs, or authored Stage 1–3 content (2.7).
- No playback type in any `SessionState` / `SessionEvent` / `Effect` property; predicates never read `teachingMode`.
- No new Gradle dependency (no navigation, window-size-class or icons artifacts).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Guide me start | whiteCross lesson started, GUIDE_ME | `reveal=false`, `advance=false`, `satisfied=false`; chips hidden | No error expected |
| Reveal | `RevealRequested` | `reveal=true`, revision unchanged | IDLE: no-op |
| Show me | `TeachingModeChanged(SHOW_ME)` | `reveal=true`; virtual, history, revision unchanged | same mode again: unchanged |
| Objective met | commit `F'` | `satisfied=true`, `advance=true` | No error expected |
| Intermediate step | 2-step lesson (`U` then `F'` from SOLVED+F+U'), commit `U` | `advance=true`, `satisfied=false`; `StepAdvanced` → `stepIndex=1`, `revealRequested=false` | `StepAdvanced` while `advance=false` or satisfied or on last step: no-op |
| Reset | stepIndex 1, Reset applied | `stepIndex=0`, `revealRequested=false` | pending ask: tutor fields unchanged until confirmed |
| New lesson | `LessonStarted` from SHOW_ME | mode stays SHOW_ME, `stepIndex=0` | No error expected |
| Empty / failed content | no Lessons / `ContentLoadResult.Failed` | Learn shows empty / error line | sealed result, no throw |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/{SessionState,SessionEvent,Reduce}.kt` -- add fields/events; `start()` keeps `state.teachingMode`; `reset()` (also reached by `confirmed`) zeroes tutor fields. `withHistory` is the one place revision/predicate are recomputed — tutor fields must not bump revision.
- `core/application/.../session/TRANSITIONS.md` + `src/test/.../TransitionsTableTest.kt` -- table parsed by the test; `changed()` must add `tutor`; `assertEquals(7, eventTypes.size)` → 10; new scenarios need a state with redo (`b`) to show "redo kept".
- `core/application/src/test/.../session/{Lessons,ReduceTest,ReducerPurityTest,PlaybackNotCheckpointedTest}.kt` -- fixtures (`whiteCrossLesson`, `started`, `after`), purity/never-checkpointed reflection that must stay green.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/` -- new package (spine lists `tutor/` in `:core:domain`): `TeachingMode.kt`, `TutorDecision.kt`, `Tutor.kt`. Parse step Moves with `Notation.parse`; content is validated (ContentValidator checks `steps.N.moves`), so a parse failure is a `check` programmer error like `Reduce.entryState`.
- `core/domain/.../playback/Demonstration.kt` -- `Demonstration.of(lesson)`, `steps[i].focus` (`StepFocus.lit/target`), `stepIndexAt`; reused for Guide me emphasis and the pulse target.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayer.kt` -- `rememberDemonstrationPlayerState`, `focusedStep`, `position`; Show me stage.
- `app/.../ui/lesson/ResetConfirmation.kt` -- add `onTransition: (Transition) -> Unit = {}` so the Lesson submits the Reset snap to its renderer.
- `app/.../render/Net2DRenderer.kt` -- `NetEmphasis` gains `pulse: Float = 0f`; draw translucent highlight fill on highlighted stickers when > 0; add `StickerPulsingKey` semantics (set only with emphasis).
- `app/.../session/{SessionHost,PresentationMapping}.kt` -- `host.dispatch { … }` returns `Transition`; `presentation.toItems()` → `renderer.submit`.
- `app/.../ui/freecube/{FreeCubeScreen,MoveKeyBar}.kt` -- toolbar button pattern (`ToolbarButton`, `ic_undo/redo/reset`), `MoveKeyBar(onMove, onShowName)`; FreeCubeScreen becomes the Solve tab.
- `app/.../MainActivity.kt` -- sets content to the new `AppShell`; keeps injected `SessionHost`; `FreeCubeViewModel by viewModels()`.
- `app/.../ui/theme/{Tokens,Type,Spacing,Theme}.kt` -- `RubikTheme.tokens` (`accent`, `onAccent`, `hairline`, `inkDisabled`, `surfaceRaised`, `surfaceStage`), `RubikTheme.textStyles.notation/.data`, `Spacing`.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/TestLessons.kt` -- `whiteCrossLesson()`, `TickingClock`; add a two-step lesson + a `ContentBundle` with its strings.
- `app/src/test/.../ui/lesson/{DemonstrationPlayerTest,ResetConfirmationTest}.kt` -- Robolectric + `createComposeRule` (`androidx.compose.ui.test.junit4.v2`) templates; `@Config(application = Application::class, qualifiers = "w411dp-h891dp")`.
- `build-logic/.../policy/LearnerTextPolicy.kt` -- multi-word literals and `text=`/`contentDescription=` literals fail the build.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/{TeachingMode,TutorDecision,Tutor}.kt` -- enum, decision, pure rule -- AC 2, 3, 4.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/TutorTest.kt` -- reveal per mode, advance on path state and on predicate, satisfied independent of mode -- AC 3, 4.
- `core/application/.../session/{SessionState,SessionEvent,Reduce,TRANSITIONS.md}` -- fields, derived `decision`, three events, reset/start rules -- AC 3, 4.
- `core/application/src/test/.../session/{TutorEventsTest,TransitionsTableTest}.kt` -- every I/O matrix session row; table rows -- AC 3, 4.
- `app/src/main/kotlin/dev/rubikxplorer/app/content/{ContentStrings,LessonCatalog}.kt` + `di/ContentModule.kt` -- ICU resolver; `@Singleton` catalog loading `ContentSource.SHIPPED` off the main thread into a sealed result -- AC 1.
- `app/.../ui/shell/{AppShell,WindowWidthClass,LearnScreen}.kt` + `MainActivity.kt` -- shell, tabs, flat list, lesson route, back handling -- AC 1, 5.
- `app/.../ui/lesson/{LessonScreen,LessonPanel,TeachingModeSwitcher}.kt` + `res/drawable/{ic_close,ic_check}.xml` + `res/values/strings.xml` -- task flow, layouts, panel, step card, Show me / Guide me stages, Objective-met state -- AC 1–4.
- `app/.../render/Net2DRenderer.kt`, `ui/lesson/ResetConfirmation.kt` -- pulse; `onTransition` -- AC 4.
- `app/src/test/.../ui/shell/{AppShellTest,WindowWidthClassTest}.kt`, `ui/lesson/LessonScreenTest.kt`, `content/ContentStringsTest.kt` -- Robolectric tests per AC below.

**Acceptance Criteria:**
- Given the app shell with a bundle holding `whiteCrossLesson`, when the Learner opens it from Learn, then the bar is hidden, a top bar with close shows, and the panel shows Objective, step card, switcher, Hint and Next in that vertical order; at `w411dp-h891dp` the panel sits below the stage, at `w1280dp-h800dp` it sits right of the stage and is ≤ 560dp wide.
- Given Show me on a two-step lesson, when play is tapped, then the card shows step 1 Goal/Keep/Watch without Notice before play, Notice 1 after step 1's Moves play, step 2's Goal/Keep/Watch when its Move plays, and Notice 2 at the end; Move chips are visible.
- Given Guide me, when a step begins, then Goal, Keep and Watch show, no Move chip exists, and tapping "Show the Move" makes `F'` appear only after the session's `decision.reveal` turns true (the test asserts the session state, not a screen flag).
- Given Guide me on `whiteCrossLesson`, when `F'` is committed from the key bar, then the check + "Objective met", Notice, an enabled Next and pulsing target stickers show; after 2 s nothing pulses and the Lesson is still open (no auto-advance); Next returns to Learn.
- Given an open Lesson, when system back is pressed, then Learn shows at once with no dialog.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 33 findings — high 0, medium 4, low 22, false 7, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) shipped bundle has no Lessons, so Learn is empty on device — the epic gives authoring Stages 1–3 to Story 2.7; the shell, list and empty state are the AC surfaces and are tested with a bundle.
  - `[low]` `[reject]` (intent) Show me shows the Move chips before play — Show me reveals by definition (`reveal = mode == SHOW_ME`); the Objective is still first in the panel; changing it would edit the spec.
  - `[low]` `[patch]` (intent) medium width only tested through the pure classifier — grouped with verification-gap #3; medium-width AppShellTest added.
  - `[false]` `[reject]` (intent) Notice timing is decided by the screen — the AC binds reveal to `TutorDecision.reveal`; Guide me Notice reads `decision.advance`, Show me Notice follows playback, which is not session state.
  - `[low]` `[reject]` (intent) back while the reset dialog is open declines instead of closing — the dialog is the top modal level and consumes back per Android convention; the AC is met with no dialog open.
  - `[false]` `[reject]` (intent) the end of a Show me Demonstration never shows Objective met — the AC is "when the last Move commits"; playback never commits (AD-20).
  - `[medium]` `[patch]` (verification-gap) the Guide me net is never compared with the session (send / onTransition / initial facelets) — added a LessonScreenTest that drives Move, Undo, Redo and a confirmed Reset and compares every sticker color with `virtual`.
  - `[medium]` `[patch]` (verification-gap) the reduce-motion pulse branch is untested — added a reduce-motion Objective-met test asserting no sticker pulses.
  - `[low]` `[patch]` (verification-gap) medium width never exercised — added `w700dp-h900dp` AppShellTest (rail, panel right of stage).
  - `[medium]` `[patch]` (verification-gap) open Lesson/tab survival across recreation untested — added a StateRestorationTester case to AppShellTest.
  - `[low]` `[patch]` (verification-gap) `canReset` step rule unpinned — added a two-step test: on step 2 Reset is enabled and returns to step 1, net in sync.
  - `[low]` `[reject]` (blind) Undo across a step boundary leaves the card on step 2 — the spec fixes `stepIndex` to move only by StepAdvanced or Reset; Redo restores; fix would edit the spec.
  - `[low]` `[reject]` (blind) in Show me Next / Objective met read the Guide me cube — spec rule "Next iff decision.advance"; fix would edit the spec.
  - `[false]` `[reject]` (blind) a bad Lesson shows a blank screen or crashes via `check` — ContentLoader rejects invalid entry states and step Notation (ContentValidator), so neither path is reachable from loaded content.
  - `[low]` `[reject]` (blind) whole-cube rotations stop an intermediate step from advancing — the spec's Design Notes define step completion as path-state equality; fix would edit the spec.
  - `[low]` `[reject]` (blind) opening another Lesson discards unsaved progress without asking — spec rule "dispatch LessonStarted when the session does not hold that Lesson"; checkpoints (Epic 6) make this moot; fix would edit the spec.
  - `[low]` `[reject]` (blind) ContentStrings may throw on a malformed pattern, rebuilds MessageFormat per call, English locale — ICU patterns are validated at load (IcuArguments); English only at launch; per-call cost negligible.
  - `[low]` `[reject]` (blind) the pulse recomposes the Lesson body each frame for ~600 ms — brief, once per Lesson; no measurable cost shown.
  - `[low]` `[reject]` (blind) layout keyed on width only; landscape phone gets a short stage — spec and UX table key on width (phone landscape = medium); the net still renders.
  - `[low]` `[reject]` (blind) Learn has no loading indicator or retry — loading a bundled resource is near-instant and deterministic, so a retry cannot change the result.
  - `[low]` `[patch]` (blind) restored unknown Lesson id lingers; inset area shows the window background in the rail layout — inset background patched (`background` before `safeDrawingPadding`); the lingering id is harmless (shell shows Learn), rejected.
  - `[low]` `[patch]` (blind) TalkBack: live region on an appearing node, no Notice/step announcement, no top-bar heading — top-bar title now has `heading()`; announcements rejected (cube/announcement accessibility story).
  - `[low]` `[patch]` (blind) missing tests: medium width, config change, reduce-motion pulse, Hint disabled in Show me, playback position across mode switch — first four patched (grouped with verification-gap rows; Hint asserted disabled in Show me); playback position has no requirement, rejected.
  - `[low]` `[patch]` (blind) misleading test name; duplicated twoStepLesson; ToolbarButton widened; remember keys differ; net only updated by own send — test renamed to `an empty bundle shows the empty line`; others rejected (separate test modules cannot share fixtures; Lesson equality makes keys equivalent; only the screen dispatches in the app).
  - `[low]` `[reject]` (edge) Undo/Redo do not recompute `stepIndex` — same as blind #1.
  - `[low]` `[reject]` (edge) working ahead on 3+ step lessons keeps Next disabled — `advance` is defined against the current step's path state in the spec; no 3-step content exists yet.
  - `[low]` `[reject]` (edge) Show me Next never enables — same as blind #2.
  - `[false]` `[reject]` (edge) switching to Show me while a reset confirmation is pending hides the dialog — the AlertDialog is modal, so the switcher cannot be tapped while it shows.
  - `[false]` `[reject]` (edge) invalid Demonstration shows a blank Lesson — same refutation as blind #3.
  - `[low]` `[reject]` (edge) a compact window under ~280dp tall leaves no room for the panel — needs a sub-280dp window; not reached on supported devices.
  - `[low]` `[reject]` (edge) ContentStrings malformed pattern crash — same as blind #6.
  - `[false]` `[reject]` (edge) Learn stuck on Failed with no retry — the shipped bundle is read from the APK; a retry yields the same result.
  - `[low]` `[reject]` (edge) opening another Lesson discards progress silently — same as blind #5.

## Design Notes

- **Why a partial `TutorDecision`:** AD-5 fixes the full field set, but `diagnosis`, `certainty`, `nextAction`, `explanation` and `recovery` need `tutor/Assess` (Story 4.1, which owns Certainty construction). Adding them now would mean fabricating Certainty outside `Assess`. This story adds the fields it can fill honestly; 4.1 completes the set.
- **Why tutor fields don't bump revision:** revision guards async effects against Cube State changes; a mode switch or reveal must not stale a pending Reset confirmation, and `TransitionsTableTest` already asserts "revision iff lesson or history changed".
- **Intermediate step done = path state reached:** steps carry no predicate; equality with the Demonstration path state is a state check, not a Move count, and the Lesson's completion stays predicate-decided.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test` -- expected: green incl. TutorTest, TutorEventsTest, TransitionsTableTest.
- `./gradlew :app:testDebugUnitTest` -- expected: green incl. new shell/lesson tests; existing free cube, render, session tests unchanged.
- `./gradlew spotlessCheck build` -- expected: exit 0 (Kover ≥ 80% on `:core:*`, Learner-text, boundary, lint gates).

## Auto Run Result

Status: done

**Summary:** Added the tutor's first rule in `:core:domain/tutor` (`TeachingMode` Show me / Guide me, `TutorDecision` with `reveal`/`advance`, pure `Tutor.decide`), carried `teachingMode`, `stepIndex`, `revealRequested` and a derived `decision` in `SessionState` with three new events (`TeachingModeChanged`, `RevealRequested`, `StepAdvanced`) that never bump revision, and built in `:app` a Learn + Solve navigation shell (bar/rail by width class), a flat Learn list over a Hilt-provided `LessonCatalog`, and a full-screen Lesson flow: Objective → step card → switcher → Hint + Next, Show me on `DemonstrationPlayer`, Guide me on the session net with toolbar and key bar, Objective-met check/Notice/pulse, no auto-advance, back closes at once.

**Files changed:**
- `core/domain/.../tutor/{TeachingMode,TutorDecision,Tutor}.kt` + `TutorTest` — mode, decision, rule.
- `core/application/.../session/{SessionState,SessionEvent,Reduce,TRANSITIONS.md}` + `TutorEventsTest`, `TransitionsTableTest`, `Lessons.kt` — tutor fields and events.
- `app/.../content/{ContentStrings,LessonCatalog}.kt`, `di/ContentModule.kt` — ICU resolver, catalog.
- `app/.../ui/shell/{AppShell,LearnScreen,WindowWidthClass}.kt`, `MainActivity.kt` — shell, list, width classes.
- `app/.../ui/lesson/{LessonScreen,LessonPanel,TeachingModeSwitcher}.kt`, `ResetConfirmation.kt` (`onTransition`), `DemonstrationPlayer.kt` (net weight), `render/Net2DRenderer.kt` (pulse), `freecube/FreeCubeScreen.kt` (`ToolbarButton` internal), drawables, `strings.xml`.
- Tests: `AppShellTest` (11), `LessonScreenTest` (9), `ContentStringsTest`, `WindowWidthClassTest`, `TestLessons.kt` fixtures.

**Review findings:** 33 (high 0, medium 4, low 22, false 7). Patched entries: medium 3 (net-vs-session sync test, reduce-motion pulse test, saved-state restore test), low 6 (medium-width test, Reset-on-step-2 test, inset background, top-bar heading, Hint-in-Show-me assertion, test rename). Deferred: none. Rejected with reasons in the triage log (empty shipped content is 2.7; spec-fixed rules for step index, Show me Next, path equality, lesson switching; unreachable invalid-content paths; modal dialog; negligible cost/cosmetic items).

**Follow-up review recommended:** true — three medium entries were patched on a first pass. Unverified risk: multi-step behavior after Undo past a step boundary (card stays on the later step) and Show me's disabled Next are spec-defined but untested with real Stage content until Story 2.7.

**Verification:** `./gradlew spotlessCheck build` exit 0 (all JVM tests incl. TutorTest 7, TutorEventsTest 10, AppShellTest 11, LessonScreenTest 9; Kover, Learner-text, boundary, lint gates). Every I/O matrix row is covered by a passing test.

**Residual risks:** no shipped Lessons until Story 2.7 (Learn shows the empty line); `gradle.properties` (`org.gradle.tooling.parallel`) and `gradle/gradle-daemon-jvm.properties` appeared in the working tree during the run from tooling outside this story and were left uncommitted.

