# Epic 4 Context: Get help when I go wrong

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

When a Learner makes a mistake in a Lesson, the tutor names what happened with honest, evidence-based Certainty, offers help one Hint Ladder rung at a time, and leads back by undo, retry or the shortest Guided recovery. Assistance adapts to the Learner's history through configuration-driven rules. The epic also replaces the Epic 2 stopgaps: the whole-Objective *Test me* classifier and the step advance that needed an exact path-state match. Step guidance now follows the cube, and taught Moves are never labeled Harmful. `TEST_ME` stays OFF in release until Story 4.2 merges.

## Stories

- Story 4.1: Assess evidence and produce a TutorDecision
- Story 4.2: Diagnose each Error Category with tested copy
- Story 4.3: Climb the Hint Ladder
- Story 4.4: Undo or try again after a mistake, keeping the Checkpoint
- Story 4.5: Guided recovery by the shortest correction
- Story 4.6: Adaptive assistance from configuration

## Requirements & Constraints

- **Diagnosis:** every Error Category maps to a deterministic rule or an explicit *unknown*. Each one has a scenario test and a tested, actionable Recovery Path. If several causes fit, the result is *likely* or *unknown*, never a false *verified*. The tutor never silently replaces the Learner's state with the Expected State.
- **Hint Ladder:** each request moves up exactly one rung: conceptual clue → highlighted target or Move arrow → full Demonstration. It resets when the Objective changes. Hint use is counted per attempt; persistence lands in Epic 6. The full Solution is never shown by default.
- **Recovery:** the Learner can retry, undo, or take a Guided recovery, which is the shortest correction that restores the current Objective's preserved pieces. The Lesson never restarts unless the Learner chooses it. Disturbed-cross scenario: say the previous layer was disturbed, offer undo or Guided recovery, keep the earlier Checkpoint, and don't unlock the next Stage.
- **Adaptive assistance (rule-based):** first mistake → explain the Objective and highlight the piece. Repeated mistake → demonstrate. Continued difficulty → guide step by step. Repeated success → less help. Thresholds are configuration data with tests. Tuning from pilot data is P1. On the cut order, adaptive assistance may fall back to a fixed Hint Ladder, and *Test me* is cut before it.
- **Test me success criteria:** every taught Move of every shipped Lesson classifies On path, never Harmful. Out-of-order steps and Undo across a step boundary keep the step card in sync with the cube.
- All Learner-facing text is a keyed `Text`, never a screen literal.

## Technical Decisions

- **Certainty has one producer:** only `tutor/Assess` in `:core:domain` produces Certainty, for both confirmations and Diagnoses. It uses a single `CandidateEnumerator(expected, maxDepth, moveSet)`, and `maxDepth`/`moveSet` come from `tutor-config.json`. Story 4.1 ships its first values: `maxDepth` 2 and the 18 face turns. `VERIFIED` needs exactly one consistent candidate, plus full face coverage when the evidence is physical. `Diagnosis.verified(...)` is a smart constructor that rejects more than one candidate. Certainty is `{VERIFIED, LIKELY, UNKNOWN}` only, and no numeric confidence crosses a module boundary. A CI search ensures no other module constructs a Certainty.
- **`ErrorCategory` is a closed enum:** `WRONG_MOVE_OR_DIRECTION`, `WRONG_PIECE`, `WRONG_PIECE_ORIENTATION`, `UNEXPECTED_ORIENTATION`, `COMPLETED_LAYER_DISTURBED`, `INVALID_ENTERED_STATE`, `UNKNOWN_DISCREPANCY`. Every `when` over it is exhaustive, each category maps to exactly one Recovery Path, and each has one test.
- **`TutorDecision` has a fixed field set:** `objectiveId`, `satisfied`, `diagnosis?`, `certainty`, `nextAction`, `explanation: Text`, `recovery: Pending(effectId) | Ready(moves) | Unavailable`, `reveal`, `advance`, `feedback: MoveFeedback?`. The UI renders `reveal` and never decides it.
- **Test me feedback:** `MoveFeedback` is `ON_PATH | PROGRESS | NEUTRAL | HARMFUL`. Only `Assess.classify(lesson, entry, before, move, after)` produces it, on `MoveCommitted` in *Test me*, and every other event clears it. It judges the Move against the step derived from `before` and checks in this order:
  - `ON_PATH`: `before` is an authored path state and the Move is the next authored Move, compared as canonical Moves. Either quarter turn of an authored half turn counts. This is a state match, never a counter.
  - `HARMFUL`: the Move disturbs a piece the step preserves. It produces a Diagnosis.
  - `PROGRESS`: the step's effective-done progress rises.
  - `NEUTRAL`: anything else.

  While the state is on the authored path, Assess emits no `COMPLETED_LAYER_DISTURBED`. This replaces Epic 2's whole-Objective classifier.
- **Steps:** each step gains a required `done: Predicate`.
  - Effective done is `allOf(lesson.preserve, done)`. The last step's `done` must structurally equal `completion`, and only `completion` decides that the Lesson is complete.
  - `Tutor.currentStep(lesson, entry, state)` is a pure function that is never stored in session state, a Checkpoint or a Snapshot. On the authored path it is the step whose Moves lead to the next path state; off the path it is the first step whose effective done is unmet.
  - Per-step UI state (`revealRequested`) resets when the derived step changes.
  - Preserved pieces are derived, not authored: the Lesson's `preserve.preservedPieces` plus those of every other step already done. `steps[].preserve` is Keep-card text only.
  - The content test checks that along the authored path, step k's `done` is false before step k's last Move and holds at every later step boundary; it may lapse mid-step. A step `done` that the registry can't express uses `state-reached` or a new color-anchored predicate.
- **Predicates** are color-anchored and orientation-invariant. Each one supplies `preservedPieces(state)` and a pure `ObjectiveProgress`, which *Test me*, Diagnosis and recovery share.
- **Reducer and effects:**
  - All changes go through `SessionEvent`/`reduce`. `reduce` does only bounded work: predicates, rule tables, and candidate enumeration up to `maxDepth`.
  - `RecoveryPlanner` is decided in this epic as a bounded IDA* over `preservedPieces`. It runs via `PlanRecovery` as an Effect with a timeout (3 s Solver cap) on `Dispatchers.Default`.
  - Results echo `effectId` and `revision`. A stale result is discarded (`EffectResultDiscarded`) and re-offered. Any change to `virtual` or `expected` cancels the dependent effects.
  - Diagnosis and predicates never call the Solver. Production Solver use must follow the delivery path chosen in Story 3.8.
- **Errors are values:** sealed results, an explicit unknown variant, and effect failures dispatched as `…Failed` events.
- **Feature flags:** `ADAPTIVE_ASSISTANCE` and `TEST_ME` are read only through the `FeatureFlags` port, never `BuildConfig`.
- **Physical mode** (consumed by Epic 8): a Harmful Diagnosis is at most `LIKELY` on a predicted state, and there is no feedback while awaiting confirmation or after a mismatch.

## UX & Interaction Patterns

- **Test me copy:**
  - On path: "That's the move — keep going."
  - Progress: "That works too — you're closer."
  - Neutral: "That didn't change much. Try another turn."
  - Harmful: shown as a diagnosed error with a recovery prompt.
- **Category copy:**
  - Unexpected orientation: "Your cube is turned — hold it with white on the bottom, green in front." It offers *Show me how* and no undo.
  - Wrong piece: "That moved the white-orange corner; we want white-red-green."
  - Wrong orientation: "Right corner, but twisted — white should face down."
- **Tone:** say "That turn moved the cross. Undo, or let me guide you back.", never "Wrong move." Certainty words are only *Verified*, *Likely* and *Unknown*, each paired with the next action.
- **Certainty chip:** `rounded.full`, icon plus label (✓ / ≈ / ?) in a status color, never a percentage. Tapping it explains the evidence. Every certainty change is announced to accessibility services.
- **No error theatrics:** no red fill, ✗, shake or mistake haptic. Red is reserved for invalid entered states. Disturbed pieces get a violet (`sticker-highlight`) outline, and stickers are never recolored.
- **Hint button:** labeled **Hint (n/3)**. Rung 2 shows a violet highlight or Move arrow. In *Test me* and *Let me try*, Move chips stay hidden until a hint is requested.
- **Recovery prompt:** inline in the lesson panel, never a dialog. It offers *Undo*, *Guide me back* and *Try again*, in that order. *Guide me back* stays disabled until 4.5.
  - Pending: "Finding the shortest way back…"
  - Plan: "3 moves — your cross stays", with the preserved pieces highlighted.
  - No plan: "I can't find a short way back. Undo, or start this step again."
  - *Try again* restarts the Objective from the last Checkpoint, not the Lesson.
- **Assistance line:** one `meta` line under the step card that says why help changed ("Showing the turn, since this step tripped you up twice."), with *Back to my usual help*. It never switches Teaching Mode without a tap, and it is absent when `ADAPTIVE_ASSISTANCE` is OFF, which also fixes the Hint Ladder.
- The Lesson screen file is already large (about 420 lines), so put the hint, diagnosis and recovery UI in separate components.

## Cross-Story Dependencies

- 4.1 is the foundation: Assess, TutorDecision, the step `done` schema and the authored step predicates for Stages 1–3, extending `ShippedLessonPathsTest`. 4.2–4.6 build on it.
- 4.2 depends on 4.1's step derivation and preserved-piece rules, and replaces Epic 2's `MoveFeedback` classifier and placeholder copy.
- 4.4 adds the recovery prompt with *Guide me back* disabled, and 4.5 enables it through `PlanRecovery`/`RecoveryPlanner`.
- 4.3's per-attempt hint counts and 4.6's history feed the attempt log, which Story 6.3 persists. Checkpoint persistence itself belongs to Epic 6.
- Epic 8 (physical mode, mismatch ladder) and Epic 10 (Quick Check evidence) reuse Assess, Certainty and the chip. Epic 7 authors the Stage 4–6 steps against the same step schema.
- No Lesson may depend on pruning-table readiness, and production Solver calls wait on Story 3.8's delivery decision.
