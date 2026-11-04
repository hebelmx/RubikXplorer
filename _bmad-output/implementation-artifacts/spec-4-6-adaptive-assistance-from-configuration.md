---
title: 'Adaptive assistance from configuration'
type: 'feature'
created: '2026-11-04'
baseline_revision: d91915627fbacc2a4816c2fce3d0c753b8758800
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The tutor gives the same help however the Learner is doing. The Hint Ladder always starts at rung 0 with 3 rungs, and nothing reacts to repeated mistakes or repeated success (FR-13, UX-DR10, AD-6, AD-18). `ADAPTIVE_ASSISTANCE` exists in `FeatureFlags` but nothing reads it.

**Approach:** Add a pure, rule-based `AdaptivePolicy` in `:core:domain/tutor`. It turns the shown step's mistake count and the session's run of clean steps into an `AssistanceLevel`, using thresholds from `tutor-config.json`. The level acts on the Hint Ladder: it sets a starting rung (floor) and a top rung. The session derives the level, and a *Back to my usual help* event undoes it for that step. A new inline Assistance line says why help changed. With the flag OFF, the level is always USUAL: the ladder stays fixed and the line is absent.

## Boundaries & Constraints

**Always:**
- **Config.** `TutorConfig` gains a required `adaptive: AdaptiveThresholds(explainAfterMistakes, demonstrateAfterMistakes, guideAfterMistakes, reduceAfterCleanSteps)`, all `Int` and strictly decoded.
  - Shipped values are `1, 2, 3, 2`. Add them to `tutor-config.json`, its schema (a nested object with `additionalProperties: false`, all required, `1..10`) and the test fixture JSON.
  - `ContentValidator` reports `InvalidTutorConfig("adaptive.<name>", OUT_OF_RANGE)` for a value outside `ADAPTIVE_THRESHOLD_RANGE = 1..10`.
  - It reports `InvalidTutorConfig("adaptive", NOT_INCREASING)` (a new `Detail`) unless `explain < demonstrate < guide`.
- **Policy.** `enum class AssistanceLevel(floor: HintRung, top: HintRung)`, declared in this order:
  - `REDUCED(NONE, TARGET)`;
  - `USUAL(NONE, DEMONSTRATION)`;
  - `EXPLAIN(CLUE, DEMONSTRATION)`;
  - `DEMONSTRATE(TARGET, DEMONSTRATION)`;
  - `GUIDE(TARGET, DEMONSTRATION)`.

  `AdaptivePolicy.level(thresholds, mistakes, cleanSteps)` checks, first match wins:
  - `mistakes ≥ guide` → GUIDE;
  - `mistakes ≥ demonstrate` → DEMONSTRATE;
  - `mistakes ≥ explain` → EXPLAIN;
  - `cleanSteps ≥ reduceAfterCleanSteps` → REDUCED;
  - else USUAL.

  `AdaptivePolicy.line(level, mistakes): Text?` returns null for USUAL, else the level's copy key with `count = mistakes`.
- **What each level does** (only through the effective rung and the screen's outline, never by switching the mode):
  - EXPLAIN: the clue line shows with no tap, and the step's target (`HintLadder.highlight`) is outlined.
  - DEMONSTRATE: the target is outlined and the step's Moves are revealed ("Showing the turn").
  - GUIDE: like DEMONSTRATE, but when no Notice is pending and `Tutor.nextMove(lesson, entry, state)` is non-null, the card's Move chips show only that next authored Move. `nextMove` is the next Move when `state` is an authored path state (first match), else null; it is null after the final Move.
  - REDUCED: the ladder stops at TARGET. The button reads **Hint (n/2)**.
- **Session.** It gets new fields:
  - `adaptiveAssistance: Boolean`: from `LessonStarted.adaptiveAssistance`, a new parameter placed after `at` with default `false`. It counts as a `session` field.
  - `stepMistakes: Map<Int, Int>`, `cleanSteps: Int` and `assistanceDismissed: Map<Int, AssistanceLevel>`: tutor fields. `LessonStarted` resets them, and nothing else does (not Reset, not Retry). IDLE and playback sessions hold them empty or 0.
  - None of these fields advances `revision`.
- **Counting.** In `committed`, a Move is assessed with `Assess.classify` when the mode is TEST_ME. It is also assessed when `adaptiveAssistance` is on and the mode is GUIDE_ME or LET_ME_TRY.
  - Only TEST_ME stores `lastMoveFeedback`/`lastDiagnosis`, exactly as today.
  - A `HARMFUL` result adds 1 to `stepMistakes[Tutor.currentStep(lesson, entry, before)]` and sets `cleanSteps = 0`.
  - A commit that makes a step's Notice newly pending, or newly completes the Lesson, completes step `s0 = state.stepIndex` (the step shown before). It is clean when `stepMistakes[s0]` is 0 (counted after this Move) and the before-state `hintRung == NONE`. A clean step raises `cleanSteps` by 1, and any other completed step sets it to 0.
  - Undo, Redo, Reset and Retry never count.
- **Derived values.**
  - `SessionState.assistance` is USUAL when any of these holds:
    - it is not a Lesson;
    - `!adaptiveAssistance`;
    - the mode is SHOW_ME;
    - the computed level equals `assistanceDismissed[stepIndex]`.

    Otherwise it is `AdaptivePolicy.level(config.adaptive, stepMistakes[stepIndex] ?: 0, cleanSteps)`.
  - `SessionState.shownRung` is `hintRung.coerceAtLeast(assistance.floor).coerceAtMost(assistance.top)`.
  - `decision` passes `shownRung` (not `hintRung`) to `Assess.decide`, whose signature is unchanged.
- **Hint.** `HintLadder.canClimb` gains `top: HintRung = DEMONSTRATION` and refuses at `top`. `hint()` climbs from `shownRung` (`hintRung = shownRung.next()`), with `hintsUsed + 1`, using `assistance.top`.
- **Event.** `SessionEvent.UsualHelpRequested(at)`. When `assistance != USUAL`, it sets `assistanceDismissed[stepIndex] = assistance`; otherwise nothing changes. A later rise to a different level shows again. It follows the generic non-commit clear in `reduce`. It never changes `teachingMode`.
- **Copy.** In `en.json`:
  - `msg.assistance.explain` "Pointing out the piece, since this step tripped you up.";
  - `msg.assistance.demonstrate` "Showing the turn, since this step tripped you up {count, plural, =1 {once} =2 {twice} other {# times}}.";
  - `msg.assistance.guide` "One turn at a time, since this step tripped you up {count, plural, =1 {once} =2 {twice} other {# times}}.";
  - `msg.assistance.reduced` "Fewer hints now — you've got this step.";
  - `msg.assistance.usual-help` "Back to my usual help".
- **Screen.**
  - `AppShell` passes `adaptiveAssistance = flags.isEnabled(FeatureFlag.ADAPTIVE_ASSISTANCE)` to `LessonScreen` (new parameter, default `false`), which puts it in `LessonStarted`.
  - A new `AssistanceLine` shows directly under the step card whenever `AdaptivePolicy.line` is non-null:
    - one `meta`-styled text, tagged `lesson:assistance`, in a polite live region;
    - a text button labelled with `msg.assistance.usual-help`, tagged `lesson:assistance:usual`, that sends `UsualHelpRequested`.
  - The hint line, the Hint button (`Hint (shownRung/top)`, enabled by `canClimb` with `top`) and `climbHintLadder` all use `shownRung`.
  - The target outline condition becomes `shownRung >= TARGET || assistance >= EXPLAIN`.
  - No dialog, and no red.

**Never:**
- switching `teachingMode`, auto-playing a Demonstration or applying Moves because of the level;
- adding fields to `TutorDecision`;
- reading `BuildConfig` or `FeatureFlags` in `:core:*` (the flag reaches the reducer only through `LessonStarted`);
- persistence or attempt-log effects (Story 6.3), or tuning thresholds from data;
- code constants for the thresholds;
- editing planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First mistake | shipped `lesson-03-01`, flag ON, *Test me*, `R`, then `Undo` | `stepMistakes[0] = 1`; `assistance == EXPLAIN`; `shownRung == CLUE`; `decision.reveal` false; `hintsUsed` 0; mode still TEST_ME | — |
| Repeated mistake | the same, `R`, `Undo` twice | DEMONSTRATE; `decision.reveal` true; the line resolves to "Showing the turn, since this step tripped you up twice." | — |
| Continued difficulty | three times | GUIDE; on screen, the chips show only `Tutor.nextMove` | — |
| Repeated success | flag ON, a shipped Lesson with ≥ 3 steps, two steps completed by their authored Moves with no Hint and no mistake | `cleanSteps = 2`; the third step is REDUCED; the Hint button reads `Hint (0/2)`, and two Hints reach TARGET and then stop | — |
| Unclean success | a Hint on step 1, then step 1 completed | `cleanSteps = 0` | — |
| Config-driven | `TutorConfig` with thresholds `(2, 4, 6, 3)`; each threshold and one below it | each level changes exactly at its configured value (a `AdaptivePolicyTest` table and a session test) | — |
| Back to usual | DEMONSTRATE, then `UsualHelpRequested` | `assistance == USUAL`; `shownRung == hintRung`; the line is gone; one more mistake → GUIDE and the line returns | — |
| Usual already | USUAL, `UsualHelpRequested` | nothing changes except the generic clear | ignored |
| Flag OFF | the same mistakes and clean steps with `adaptiveAssistance = false` | `assistance == USUAL` always; `shownRung == hintRung`; `Hint (n/3)`; no `lesson:assistance` node | — |
| Bad config | a threshold of 0 or 11; `explain = demonstrate` | `OUT_OF_RANGE` with the field `adaptive.<name>`; `NOT_INCREASING` with `adaptive` | not loaded |

</intent-contract>

## Code Map

- `core/domain/.../tutor/AdaptivePolicy.kt` (new) -- `AssistanceLevel`, the `AdaptivePolicy` object (`level`, `line`).
- `core/domain/.../tutor/HintLadder.kt:49` -- `canClimb` gets a `top` parameter; fix the KDoc.
- `core/domain/.../tutor/Tutor.kt` -- `nextMove(lesson, entry, state): Move?`, built over `pathOwners` (:133) the way `currentStep` (:26) walks it.
- `core/domain/.../content/{Model.kt:63-80, ContentValidator.kt:15-90, ContentProblem.kt:115}` -- `AdaptiveThresholds` (`@Serializable`), the `adaptive` field, `ADAPTIVE_FIELD`, `ADAPTIVE_THRESHOLD_RANGE`, `Detail.NOT_INCREASING`.
- `core/domain/src/main/resources/content/{tutor-config.json, schema/tutor-config.schema.json, strings/en.json}` and `core/domain/src/test/resources/content-fixtures/valid/tutor-config.json`.
- `TutorConfig(` constructions to update: `ContentValidatorTest`, `ContentLoaderTest`, `ShippedContentTest`, `TutorFixtures.kt` and any others (`grep -rn "TutorConfig(" core app`). Any schema-vs-model test must still pass.
- `core/application/.../session/SessionEvent.kt:17` -- `LessonStarted.adaptiveAssistance` (after `at`, default false); `UsualHelpRequested`.
- `core/application/.../session/SessionState.kt` -- the four fields (the constructor and IDLE :140); `assistance`, `shownRung`; `decision` (:96) passes `shownRung`; the KDoc tutor list.
- `core/application/.../session/Reduce.kt`:
  - `start` (:66) sets the new fields;
  - `startPlayback` resets them;
  - the dispatch (:43) adds `UsualHelpRequested`;
  - `committed` (:143) counts per Boundaries; it needs the before-state's `stepIndex`, `hintRung`, `noticeStep`, `objectiveMet` and the moved result's;
  - `hint` (:355) uses `shownRung` and `top`.
- `core/application/.../session/TRANSITIONS.md` -- add `stepMistakes`, `cleanSteps` and `assistanceDismissed` to the `tutor` list, and `adaptiveAssistance` to `session`; a row `UsualHelpRequested | tutor | no | no`; prose for Adaptive assistance.
- `core/application/src/test/.../session/TransitionsTableTest.kt` -- `tutor()` :217 includes the new fields; `samples` :185 gets the new event; the count :238 goes from 16 to 17. Model the new `AdaptiveAssistanceSessionTest.kt` on `HintLadderSessionTest.kt` / `DiagnosisSessionScenarioTest.kt` (`Lessons.kt` helpers).
- `app/.../ui/shell/AppShell.kt:82` -- pass the flag; `AppShellTest` if it constructs the screen.
- `app/.../ui/lesson/LessonScreen.kt`:
  - the `LessonScreen` params (:103) and `LessonStarted` (:119);
  - Hint wiring (:225-230);
  - `hinted`/`hintTarget` (:262-268), using the new outline condition;
  - `stepCard` (:361): GUIDE chips, `hint` uses `shownRung`, a new `assistance` content;
  - `LessonPanel` gets `hintTop`.
- `app/.../ui/lesson/LessonPanel.kt:64-130` -- `StepCardContent.assistance: AssistanceContent?` (text, label); render `AssistanceLine` under the `StepCard`; the label uses `hintTop.ordinal` instead of `HintLadder.RUNGS`; add an `onUsualHelp` callback.
- `app/.../ui/lesson/AssistanceLine.kt` (new) -- `AssistanceContent`, `AssistanceLine` (the `HintLine.kt` / `GuidedRecoveryLine.kt` style).
- `app/.../ui/lesson/HintLine.kt:51-60` -- `climbHintLadder` compares `shownRung`.
- `app/src/test/.../ui/lesson/LessonScreenTest.kt:120` -- `open(...)` gets `adaptiveAssistance`; `app/src/test/.../content/DiagnosisCopyTest.kt` (or a new `AssistanceCopyTest`) pins the 5 strings (count 1, 2, 3).

## Tasks & Acceptance

**Execution:**
- [x] `core/domain` content model, validator, JSON, schema and fixtures, with the `TutorConfig(` call sites -- `adaptive` thresholds as data (AR-22) -- plus validator tests for the Bad config row.
- [x] `core/domain/.../tutor/{AdaptivePolicy,HintLadder,Tutor}.kt` + `AdaptivePolicyTest.kt` (new) -- the level table and the Config-driven row, `line`, `canClimb` top, `nextMove`.
- [x] `core/application/.../session/{SessionEvent,SessionState,Reduce}.kt` + `TRANSITIONS.md` -- per Boundaries.
- [x] `core/application/src/test/.../session/AdaptiveAssistanceSessionTest.kt` (new) and `TransitionsTableTest.kt` -- every non-screen matrix row; also check that `teachingMode` is unchanged in every row -- AC 1, AC 3.
- [x] `en.json` + the copy test -- the 5 strings.
- [x] `app/.../ui/{shell/AppShell,lesson/LessonScreen,lesson/LessonPanel,lesson/AssistanceLine,lesson/HintLine}.kt` + `LessonScreenTest.kt` -- the screen tests:
  - flag ON, two mistakes: the line text is visible, the switcher is still on *Test me*, and tapping *Back to my usual help* removes the line and hides the Moves again;
  - flag OFF, the same Moves: no `lesson:assistance` node and `Hint (0/3)`;
  - REDUCED: `Hint (0/2)`;
  - GUIDE: one chip
  -- AC 2, AC 3.

**Acceptance Criteria:**
- Given `AdaptivePolicy` with thresholds in `tutor-config.json`, when the Learner makes a first mistake, a repeated mistake, continues to struggle, or succeeds repeatedly, then the tutor explains and highlights, demonstrates, guides step by step, or reduces help, and config-driven tests cover each threshold.
- Given help changes, when the panel renders, then the Assistance line says why ("Showing the turn, since this step tripped you up twice."), offers *Back to my usual help*, and the Teaching Mode never changes without a tap.
- Given `ADAPTIVE_ASSISTANCE` is OFF, when a Lesson runs, then the Assistance line is absent and the Hint Ladder is fixed.

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 18 findings — high 0, medium 1, low 13, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` BH-1: GUIDE falls back to the whole step's Moves when `Tutor.nextMove` is null (off the path) — this is the spec'd fallback. The Diagnosis and the recovery prompt are what speak off the path. A different off-path guide would mean editing the spec.
  - `[low]` `[reject]` BH-2: `nextMove` does not handle the state halfway through an authored half turn — it falls back to the step's Moves, which still include the half turn. Supporting it would add branches for a short transient state.
  - `[low]` `[reject]` BH-3: undoing a clean step's last Move and committing it again counts the step clean twice — a rare sequence. Its only effect is fewer Hints, which *Back to my usual help* undoes. A fix needs per-step credit state.
  - `[low]` `[reject]` BH-4: a Hint followed by Retry or Reset clears the rung, so the step can still count as clean — rare, and the fix needs per-attempt hint tracking. Same harm as BH-3.
  - `[medium]` `[patch]` BH-5: a step completed after Guide me's "Show the Move" (`revealRequested`) counted as clean, so revealing every step's Moves led to REDUCED in the default mode — patched: the clean condition also requires `!before.revealRequested`, with a session test.
  - `[false]` `[reject]` BH-6: *Show me* completions feed the clean run — the *Show me* stage is the DemonstrationPlayer and has no Move key bar, so no `MoveCommitted` reaches the reducer in that mode.
  - `[low]` `[reject]` BH-7: mistakes are keyed by the step derived before the Move, but the level follows the shown step, so the line can stay hidden while the cube is off the path — by design (Design Notes). The Diagnosis shows meanwhile, and the level applies once the Learner is back on the step.
  - `[false]` `[reject]` BH-8: DEMONSTRATE gives no Learner-controlled way to watch the turn — at its TARGET floor, one Hint tap climbs to DEMONSTRATION, which `climbHintLadder` plays (now covered by VG-1's test).
  - `[low]` `[reject]` BH-9: only HARMFUL Moves count as mistakes, not NEUTRAL wrong turns — the spec defines a mistake as Harmful. A Neutral Move breaks nothing, and FR-13's tuning is P1.
  - `[low]` `[reject]` BH-10: `ContentValidator` does not require the assistance string keys — the same holds for every non-lesson key today (Diagnosis and recovery copy). `AssistanceCopyTest` pins the shipped strings.
  - `[low]` `[reject]` ECH-1: same claim as BH-3 — same reason.
  - `[low]` `[reject]` ECH-2: same claim as BH-4 — same reason.
  - `[low]` `[reject]` ECH-3: GUIDE renders several chips when `nextMove` is null — same as BH-1/BH-2.
  - `[low]` `[patch]` VG-1: no test covered one Hint tap from the adaptive TARGET floor playing the Demonstration — patched: a new `LessonScreenTest` case (two mistakes, one Hint tap, playback, then the net is restored).
  - `[low]` `[patch]` VG-2: the EXPLAIN outline after one mistake was not checked on screen — patched: a new `LessonScreenTest` case asserts the highlighted stickers equal the step's target and that no Move chips show.
  - `[false]` `[reject]` IA-1: GUIDE's narrowing to one chip lives only in the app and is unverified — `LessonScreenTest` "continued difficulty shows only the next Move's chip" checks it on screen.
  - `[low]` `[reject]` IA-2: no test renders the shell with the flag off and checks the line is missing — the shell tests check the flag reaches `LessonStarted`, and the `LessonScreen` flag-off test checks the line is missing.
  - `[false]` `[reject]` IA-3: alternate thresholds are never run through the UI — the thresholds are consumed in the session, and `AdaptiveAssistanceSessionTest` runs `(2,4,6,3)` there. The UI only renders `assistance`.

## Design Notes

**Why the Hint Ladder.** "Flag OFF → the Hint Ladder is fixed" means adaptive assistance acts through the ladder. Each level is a floor and a top on the shown step's rung:
- the explain level starts at the clue and outlines the piece;
- the demonstrate level starts at the target rung, which shows the turn;
- the guide level also narrows the chips to the next Move;
- the reduced level drops the full-Demonstration rung ("Fewer hints now").

The Learner's own climbs stay in `hintRung` and `hintsUsed` counts only taps, so dismissing a level undoes exactly the tutor's change.

**What counts.** A mistake is a Move that `Assess.classify` judges HARMFUL against the step derived before it. That step is the key, because a harmful Move usually moves the derived step back. So "this step tripped you up twice" survives the Undo in between. A success is a step completed by a commit with no mistake and no Hint on it. Mistakes and hint use, but not retries, feed the policy; FR-13's "repeated attempts" tuning is P1.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, with the new tests passing and Kover ≥ 80% on `:core:*`.

## Auto Run Result

- **Summary:** Rule-based adaptive assistance (FR-13, UX-DR10, AD-18).
  - **Policy:** `AdaptivePolicy` maps the shown step's Harmful-Move count and the run of clean steps to an `AssistanceLevel` (REDUCED, USUAL, EXPLAIN, DEMONSTRATE, GUIDE). The thresholds come from `tutor-config.json` (`adaptive`: 1, 2, 3, 2).
  - **Hint Ladder:** each level is a floor and a top on the shown step's rung, so the level acts only through the ladder.
  - **Assistance line:** says why help changed, and offers *Back to my usual help* (`UsualHelpRequested`).
  - **Flag:** `ADAPTIVE_ASSISTANCE` reaches the reducer only through `LessonStarted`. With it OFF, the level is always USUAL: the ladder is fixed and the line is absent.
  - The Teaching Mode never changes.
- **Files:**
  - `core/domain`:
    - `content/{Model,ContentValidator,ContentProblem}.kt`: `AdaptiveThresholds`, its validation and `NOT_INCREASING`;
    - `tutor/AdaptivePolicy.kt` (new): the levels and the policy;
    - `tutor/HintLadder.kt`: `canClimb` takes a `top`;
    - `tutor/Tutor.kt`: `nextMove`;
    - `tutor-config.json`, its schema and the test fixture;
    - `en.json`: 5 strings.
  - `core/application/session`:
    - `SessionEvent.kt`: `LessonStarted.adaptiveAssistance` and `UsualHelpRequested`;
    - `SessionState.kt`: the counts, plus the derived `assistance` and `shownRung`;
    - `Reduce.kt`: counting, the Hint climbing from `shownRung`, and the dismissal;
    - `TRANSITIONS.md`: the new row and prose.
  - `app`:
    - `AppShell.kt`: passes the flag;
    - `LessonScreen.kt`, `LessonPanel.kt`, `HintLine.kt`: the shown rung, `Hint (n/top)`, the EXPLAIN outline, and the GUIDE level's single chip;
    - `AssistanceLine.kt` (new).
  - Tests:
    - new: `AdaptivePolicyTest`, `AdaptiveAssistanceSessionTest`, `AssistanceCopyTest`;
    - updated: `TransitionsTableTest`, `LessonScreenTest` (8 new cases), `ContentValidatorTest`, the content tests and fixtures.
- **Review:** 18 findings.
  - Patched: BH-5 (medium: a step whose Moves were revealed is no longer clean), VG-1 and VG-2 (low: new screen tests).
  - Deferred: none.
  - Rejected: 11 low and 4 false, each with its reason in the triage log.
- **Follow-up review recommended:** false. Patched counts by entry verdict: high 0, medium 1, low 2.
- **Verification:** `./gradlew spotlessApply && ./gradlew spotlessCheck build` gave BUILD SUCCESSFUL before review and again after the patches. Every I/O matrix row has a passing test: `AdaptivePolicyTest` 6, `AdaptiveAssistanceSessionTest` 12, `LessonScreenTest` 40, `ContentValidatorTest` 22, `AssistanceCopyTest` 3.
- **Residual risks:**
  - Undoing a step's last Move and committing it again, or a Hint followed by Retry or Reset, can still count a step as clean.
  - GUIDE falls back to the whole step's Moves off the path and halfway through an authored half turn.
  - Help that escalates after a Harmful Move shows only once the Learner is back on the step (by design).
  - The flag is read when the Lesson starts, not live.
