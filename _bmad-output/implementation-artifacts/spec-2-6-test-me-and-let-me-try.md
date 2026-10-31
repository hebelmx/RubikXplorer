---
title: 'Story 2.6: Test me and Let me try'
type: 'feature'
created: '2026-10-30'
baseline_revision: 'ab1ca76956bac8472398eb3b78ac0e8381594909'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Lesson screen offers only *Show me* and *Guide me*; the Learner cannot pick the next Move themselves and be told whether it helped (*Test me*), nor work a step with only Goal/Keep/Watch on screen (*Let me try*), and nothing gates *Test me* behind its `TEST_ME` flag (FR-5, FR-11, AD-3, AD-6, AD-18, UX-DR8, UX-DR25).

**Approach:** Add `TEST_ME` and `LET_ME_TRY` to `TeachingMode`, a pure `MoveFeedback` (Progress / Neutral / Harmful) classified by the Completion Predicate's `ObjectiveProgress` before vs after a committed Move, carried as a tutor field in `SessionState` and exposed through `TutorDecision.feedback` only in *Test me*; the Lesson screen renders both new modes on the Learner's net, hides Move chips until Hint, and the switcher hides *Test me* when `FeatureFlags` says `TEST_ME` is OFF.

## Boundaries & Constraints

**Always:**
- `TeachingMode` order: `SHOW_ME, GUIDE_ME, TEST_ME, LET_ME_TRY`; `DEFAULT` stays `GUIDE_ME`. `Tutor.decide`'s reveal rule is unchanged (`SHOW_ME || revealRequested`), so in *Test me* and *Let me try* Moves show only after `RevealRequested` (Hint).
- `MoveFeedback` (`:core:domain` `tutor/`): enum `PROGRESS`, `NEUTRAL`, `HARMFUL`, each with a `text: Text` (`msg.test-me.progress` = "That works too — you're closer.", `msg.test-me.neutral` = "That didn't change much. Try another turn.", `msg.test-me.harmful` = "That moved a piece you need." — added to `content/strings/en.json`). `MoveFeedback.classify(completion: Predicate, before: CubeState, after: CubeState)`: `after.progress > before.progress` → PROGRESS, `<` → HARMFUL, equal → NEUTRAL. It never takes a `TeachingMode`.
- `SessionState` gains tutor field `lastMoveFeedback: MoveFeedback?`: `MoveCommitted` sets it to `classify(lesson.completion, old virtual, new virtual)` in every mode; `LessonStarted`, `Undo`, `Redo`, an applied `Reset`/`ResetConfirmed`, an applied `StepAdvanced` and `TeachingModeChanged` (to a different mode) set it to `null`. It never bumps `revision`.
- `TutorDecision` gains `feedback: MoveFeedback?`; `Tutor.decide` takes `lastMoveFeedback` and sets `feedback = lastMoveFeedback` only when `mode == TEST_ME && !advance`, else `null` (once the step is done, Notice and Next take over; Objective met supersedes). KDoc: Story 4.2 replaces the Harmful placeholder with Diagnosis. `satisfied` still reads only `lesson.completion.isSatisfied(current)`.
- `TRANSITIONS.md`: `tutor` now lists `lastMoveFeedback`; `MoveCommitted`, `Undo`, `Redo` rows add `tutor`; prose describes the feedback rule. `TransitionsTableTest` stays green against it.
- Lesson screen: *Test me* and *Let me try* use the same Learner stage as *Guide me* (toolbar, net, `MoveKeyBar`, `ResetConfirmation`), but with no step dimming or target outline (UX: dimming is for Demonstrations and *Guide me*); the Objective-met pulse still lights the target. Step card shows Goal, Keep, Watch; Notice iff `decision.advance`; Move chips iff `decision.reveal`; when hidden, the Moves row shows a quiet "Hidden until you ask for a hint." line instead of the "Show the Move" control (that control stays *Guide me* only). Hint enabled iff `!decision.reveal`. When `decision.feedback != null` the card shows its resolved text (`lesson:feedback`, neutral ink, polite live region; never red, no ✗, no shake).
- `TEST_ME` gating: `AppShell` takes `flags: FeatureFlags` (MainActivity passes its injected one) and gives `LessonScreen` the available modes = all `TeachingMode.entries` minus `TEST_ME` when `!flags.isEnabled(FeatureFlag.TEST_ME)`; the switcher renders only those. Switching modes dispatches `TeachingModeChanged` only; Cube State, history, `stepIndex` and `revision` stay.
- New chrome strings (`mode_test_me` "Test me", `mode_let_me_try` "Let me try", `lesson_moves_hidden`) in `strings.xml`; the feedback copy comes only from content strings via `Text`.

**Never:**
- No Diagnosis, recovery prompt, Hint Ladder rungs/counts, BETA label or tooltip, haptics, mode persistence, adaptive assistance, authored Stage content, or change to `FeatureFlagLevels` (release keeps `TEST_ME=OFF`).
- Predicates and `MoveFeedback.classify` never read `teachingMode`; the screen never decides reveal or feedback.
- No new Gradle dependency; no `BuildConfig` read outside `BuildConfigFeatureFlags`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Test me neutral | whiteCrossLesson (SOLVED+F, cross 3/4), TEST_ME, commit `D` | `lastMoveFeedback=NEUTRAL`, `decision.feedback=NEUTRAL`, `reveal=false` | No error expected |
| Test me harmful | same, commit `U` | `decision.feedback=HARMFUL` | No error expected |
| Test me progress | one-step Lesson, entry SOLVED+F+U', step Moves `U F'`; TEST_ME, commit `U` (progress rises, step path state SOLVED not reached) | `decision.feedback=PROGRESS`, `advance=false` | No error expected |
| Test me completes | commit `F'` | `satisfied=true`, `advance=true`, `feedback=null` | No error expected |
| Feedback cleared | after a Move, `Undo` / `Redo` / Reset applied / `StepAdvanced` applied / mode change | `lastMoveFeedback=null`, revision unchanged by the clear itself | Same-mode `TeachingModeChanged`: unchanged |
| Other modes | GUIDE_ME or LET_ME_TRY, commit `D` | `lastMoveFeedback=NEUTRAL` but `decision.feedback=null` | No error expected |
| Let me try | LET_ME_TRY step start | `reveal=false` until `RevealRequested` | IDLE: no-op |
| Same predicate | whiteCrossLesson run in each of the 4 modes with the same Moves | identical `satisfied`/`objectiveMet`/`progress`; equals `completion.isSatisfied(virtual)` | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/{TeachingMode,TutorDecision,Tutor}.kt` -- add modes, `feedback` field, `lastMoveFeedback` param + rule. `TutorTest.kt` callers of `decide` need the new arg.
- `core/domain/.../tutor/MoveFeedback.kt` (new) -- enum + `classify`; `ObjectiveProgress` (`predicate/ObjectiveProgress.kt`) is `Comparable` (same total only, which holds for one predicate).
- `core/domain/src/main/resources/content/strings/en.json` -- three `msg.test-me.*` keys (key regex in `content/Ids.kt:69`; `ShippedContentTest` checks every `Text("...")` call in main sources against en.json, no `!`).
- `core/application/.../session/{SessionState,Reduce,TRANSITIONS.md}` -- new field in data class + `IDLE`; `decision` lazy passes it; `move()` is shared by MoveCommitted/Undo/Redo — set/clear per event; `reset()` and `start()` clear; `advance()` clears when applied; TeachingModeChanged clears only on an actual change.
- `core/application/src/test/.../session/{TutorEventsTest,TransitionsTableTest,Lessons}.kt` -- extend; `TransitionsTableTest.changed()` computes `tutor` membership — include the new field. Lessons has `F`, `F_PRIME`, `U`; add `D` if needed.
- `app/.../ui/lesson/TeachingModeSwitcher.kt` -- `labelOf` exhaustive `when` (add two labels); take `modes: List<TeachingMode>` instead of iterating `entries`.
- `app/.../ui/lesson/LessonPanel.kt` -- `StepCardContent` gains `feedback: String?` and a flag for hidden-Moves presentation (Show-the-Move control vs hidden line); `MovesRow`.
- `app/.../ui/lesson/LessonScreen.kt` -- `stepCard()` `when(mode)`: treat TEST_ME/LET_ME_TRY like GUIDE_ME for index/notice/moves; stage `when(mode)` routes them to `GuideMeStage` with emphasis per the rule; new `modes` parameter passed to `LessonPanel`/switcher.
- `app/.../ui/shell/AppShell.kt` + `MainActivity.kt` -- `flags: FeatureFlags` parameter → available modes → `LessonScreen`. `AppShellTest` calls `AppShell(...)` and must pass flags.
- `app/src/test/.../ui/lesson/LessonScreenTest.kt` -- Robolectric template (`open(lessonId)`, `node(tag)`, `whiteCrossLesson`); `LessonScreen(...)` call must pass modes. A test `FeatureFlags` fake: object implementing `level()`.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/TestLessons.kt` -- `testBundle()` strings: add the `msg.test-me.*` texts so the screen resolves them.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../tutor/{TeachingMode,MoveFeedback,TutorDecision,Tutor}.kt` + `content/strings/en.json` -- modes, classifier, feedback rule, copy -- AC 1, 2, 4.
- `core/domain/src/test/.../tutor/{MoveFeedbackTest,TutorTest}.kt` -- classify Progress/Neutral/Harmful; feedback only in TEST_ME and not when advance; reveal for new modes; satisfied mode-independent; a reflection check that every `Predicate` member function takes only `CubeState` parameters (no mode reachable) -- AC 1, 2, 4.
- `core/application/.../session/{SessionState,Reduce,TRANSITIONS.md}` -- `lastMoveFeedback` set/clear rules -- AC 1, 3.
- `core/application/src/test/.../session/{TutorEventsTest,TransitionsTableTest,TeachingModesScenarioTest}.kt` -- every I/O matrix row; the four-mode scenario (start, Hint, neutral Move, `F'` in each mode; same predicate results; mode switch keeps `virtual`, history, `stepIndex`, `revision`) -- AC 3, 4.
- `app/.../ui/lesson/{TeachingModeSwitcher,LessonPanel,LessonScreen}.kt`, `ui/shell/AppShell.kt`, `MainActivity.kt`, `res/values/strings.xml` -- modes, gating, hidden-Moves line, feedback line -- AC 1–3.
- `app/src/test/.../ui/lesson/LessonScreenTest.kt`, `ui/shell/AppShellTest.kt`, `session/TestLessons.kt` -- Robolectric tests per AC below.

**Acceptance Criteria:**
- Given *Test me* on `whiteCrossLesson`, when the Learner commits `D` then `U` from the key bar, then the card shows "That didn't change much. Try another turn." then "That moved a piece you need.", no `moveChip:*` and no "Show the Move" exist until Hint is tapped, after which `F'` shows (the session's `decision.reveal` is true).
- Given *Let me try*, when the step shows, then Goal, Keep and Watch are visible, no Move chip shows until Hint, no Test me feedback line ever shows, and committing `F'` shows Objective met exactly as in *Guide me*.
- Given `TEST_ME` OFF, when the Lesson opens through `AppShell`, then no `mode:TEST_ME` segment exists and the other three do; switching Guide me → Let me try → Show me → Guide me after a committed Move leaves the session's `virtual`, history and `stepIndex` unchanged. Given `TEST_ME` ON, all four segments show.
- Given the four modes, when `TeachingModesScenarioTest` runs the same Lesson in each, then all report the same `objectiveMet`, `progress` and `decision.satisfied`, each equal to `lesson.completion.isSatisfied(virtual)`.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 27 findings — high 0, medium 3, low 16, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) a session already in Test me while `TEST_ME` is off keeps a hidden mode — unreachable: flag levels are fixed per build type, the mode defaults to Guide me, is not persisted (Epic 6), and only the switcher (which never offers Test me then) sets it.
  - `[false]` `[reject]` (blind) flags are read once and never observed — AD-18 levels are build-type constants; `FeatureFlags` has no runtime change.
  - `[medium]` `[reject]` (blind) feedback compares whole-Objective progress, so a correct setup Move that briefly lifts a solved piece reads Harmful — real for multi-Move inserts, but the intent fixes the classifier ("`ObjectiveProgress` classifies it") and marks Harmful as a placeholder until Epic 4 Diagnosis; changing the rule is outside this story's intent.
  - `[low]` `[reject]` (blind) Redo clears feedback instead of recomputing it; the Redo table fixture is hand-built — spec rule (Redo clears); fix would edit the spec.
  - `[low]` `[patch]` (blind) the polite live region does not re-announce a repeated classification (D then D) — grouped with edge #1; feedback node keyed per committed Move.
  - `[false]` `[reject]` (blind) copy problems ("too", Harmful has no next step, `lesson_moves_hidden` in strings.xml) — the three messages are the intent's verbatim copy; the hidden-Moves line is chrome, placed in strings.xml as the spec requires.
  - `[low]` `[patch]` (blind) TRANSITIONS.md garbled sentence and unwrapped Mutates line — reworded and rewrapped.
  - `[medium]` `[patch]` (blind) no test covers Test me / Let me try emphasis (no dimming, pulse lights the target) — grouped with verification-gap #1; LessonScreenTest case added.
  - `[low]` `[patch]` (blind) `GuideMeStage` now serves three modes — renamed `LearnerStage`.
  - `[low]` `[patch]` (blind) TeachingModesScenarioTest weak typing (`Any?`) and hardcoded `4` — typed `ObjectiveProgress?`, compares to `TeachingMode.entries.size`.
  - `[low]` `[reject]` (blind) the Predicate reflection test asserts the exact method set — deliberate: any new Predicate member must be re-checked for mode access; the failure is the point.
  - `[low]` `[reject]` (blind) step-index clamp in `stepCard` vs raw index in `Tutor.decide` for zero-step Lessons — pre-existing from Story 2.5 and already deferred there (ContentValidator degenerate steps, Story 2.7).
  - `[medium]` `[patch]` (verification-gap) pulse-only emphasis branch untested — same entry as blind #8.
  - `[low]` `[reject]` (intent) Progress copy shows for the path's own Move, though UX calls Progress "a different Move" — AC lists the three outcomes by `ObjectiveProgress` only; telling path Moves apart needs a new rule not in the intent.
  - `[low]` `[reject]` (intent) the Move that completes a step shows no Test me message — spec-defined (Notice and Next take over once `advance`); fix would edit the spec.
  - `[low]` `[reject]` (intent) Harmful means progress fell, not Keep disturbed — same root as blind #3; the intent names `ObjectiveProgress` as the classifier.
  - `[false]` `[reject]` (intent) Let me try shows Watch — the AC says Goal and Keep "stay visible" (a minimum), and EXPERIENCE.md says the mode "hides only the Moves".
  - `[false]` `[reject]` (intent) no dimming/outline in Test me / Let me try is extra behavior — EXPERIENCE.md limits step dimming to Demonstrations and Guide me; the spec follows it.
  - `[low]` `[reject]` (intent) no BETA treatment — not in the story's ACs; spec Never.
  - `[low]` `[reject]` (intent) no test of the production `TEST_ME` level — `BuildConfigFeatureFlagsTest` and `FeatureFlagLevelsTest` cover parsing and per-build levels; the level itself is a release decision.
  - `[low]` `[patch]` (intent) Progress copy never rendered in a UI test — one-step `U F'` test Lesson added; Test me test asserts the Progress line.
  - `[false]` `[reject]` (intent) AC 4 only exercised at the reducer — the AC is explicitly a scenario test plus a structural invariant; both exist.
  - `[low]` `[patch]` (intent) keeping `revealRequested` across a mode switch never asserted — asserted in TeachingModesScenarioTest.
  - `[false]` `[reject]` (intent) session in Test me with the flag off — same refutation as blind #1.
  - `[low]` `[patch]` (edge) repeated classification not re-announced by TalkBack — same entry as blind #5.
  - `[low]` `[reject]` (edge) a reveal requested in Guide me carries into Test me / Let me try — the Learner already asked to see this step's Move; the spec keeps reveal state on a mode switch ("keeps progress"); hiding it again would edit the spec.
  - `[false]` `[reject]` (edge) session mode outside `modes` or empty `modes` — `availableModes` removes at most Test me, and Test me cannot be the session mode while the flag is off (same as blind #1).

## Design Notes

- **Why store `lastMoveFeedback` instead of deriving it:** the derived alternative (undoable Move's before/after) would show stale feedback for an older Move after Undo; a stored field set only by `MoveCommitted` keeps the message tied to the Move just made. It is computed in every mode so the reducer stays mode-blind; only `Tutor.decide` gates what is shown.
- **Why feedback hides once `advance`:** the authored Move reaching the step's path state is not "a different Move that still helps"; Notice and Next speak then.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test` -- expected: green incl. MoveFeedbackTest, TutorTest, TutorEventsTest, TransitionsTableTest, TeachingModesScenarioTest, ShippedContentTest.
- `./gradlew :app:testDebugUnitTest` -- expected: green incl. new LessonScreenTest / AppShellTest cases.
- `./gradlew spotlessCheck build` -- expected: exit 0 (Kover ≥ 80% on `:core:*`, Learner-text, boundary, lint gates).

## Auto Run Result

Status: done

**Summary:** Added *Test me* and *Let me try* to `TeachingMode`; a pure `MoveFeedback` (Progress / Neutral / Harmful) classified by the Completion Predicate's `ObjectiveProgress` before vs after each committed Move; a `lastMoveFeedback` tutor field in `SessionState` (set by `MoveCommitted` in every mode, cleared by Lesson start, Undo, Redo, applied Reset/step advance and a mode change, never bumping `revision`); `TutorDecision.feedback` shown only in *Test me* before the step is done. The Lesson screen runs both new modes on the Learner's net without dimming (the Objective-met pulse still lights the target), hides Move chips behind Hint with a quiet "Hidden until you ask for a hint." line, shows the feedback line in neutral ink, and the switcher drops *Test me* when `FeatureFlags` reports `TEST_ME` OFF.

**Files changed:**
- `core/domain/.../tutor/{TeachingMode,MoveFeedback,TutorDecision,Tutor}.kt`, `content/strings/en.json` — modes, classifier, feedback rule, `msg.test-me.*` copy.
- `core/application/.../session/{SessionState,Reduce,TRANSITIONS.md}` — `lastMoveFeedback` set/clear rules and table rows.
- `app/.../ui/lesson/{LessonScreen,LessonPanel,TeachingModeSwitcher}.kt`, `ui/shell/AppShell.kt`, `MainActivity.kt`, `res/values/strings.xml` — modes, gating, `LearnerStage`, hidden-Moves line, per-Move feedback line.
- Tests: `MoveFeedbackTest`, `TutorTest`, `TutorEventsTest`, `TransitionsTableTest`, `TeachingModesScenarioTest`, `LessonScreenTest` (15), `AppShellTest` (14), `TestLessons.kt`, `Lessons.kt`.

**Review findings:** 27 (high 0, medium 3, low 16, false 8). Patched entries: medium 1 (Test me / Let me try emphasis and pulse test), low 6 (TalkBack re-announce per Move, TRANSITIONS.md wording, `LearnerStage` rename, scenario-test typing/count, Progress copy UI test, `revealRequested` kept across switch). Deferred: none. Rejected with reasons in the triage log (whole-Objective classifier and Harmful-as-placeholder are fixed by the intent; Redo/step-completing-Move/reveal-carry rules are spec-fixed; unreachable flag/mode combinations; verbatim copy; BETA label out of scope).

**Follow-up review recommended:** false — one medium entry and no high entry patched on a first pass.

**Verification:** `./gradlew spotlessCheck build` exit 0 before and after the patches (all JVM tests incl. MoveFeedbackTest 5, TutorTest 10, TutorEventsTest 15, TeachingModesScenarioTest 2, TransitionsTableTest 2, LessonScreenTest 15, AppShellTest 14; Kover, Learner-text, boundary and lint gates). Every I/O matrix row is covered by a passing test.

**Residual risks:** Harmful is "progress fell", so a correct multi-Move insert whose middle Move lifts a solved piece reads Harmful until Epic 4 Diagnosis; the Progress line also shows for the authored path's own Move. Release keeps `TEST_ME=OFF`, so *Test me* ships only in debug until a release decision raises it. The four-segment switcher at 411dp passes layout tests but was not checked on a device.
