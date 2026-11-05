# Session transitions (normative)

What each `SessionEvent` does to a `LearningSession` in **virtual mode** (AD-3). `TransitionsTableTest`
parses the table below and checks every row against `reduce()`; a `SessionEvent` type without a row,
or a row naming no type, fails the build. Physical-mode rows (`expected`, `observed`) arrive with Epic 8.

Columns:

- **Mutates** — the fields the event may change, from `session` (the session's kind, its Lesson and
  tutor configuration or its Solution, its `adaptiveAssistance` flag, and its entry state),
  `virtual` (the current Cube State), `pending` (the reset confirmation awaiting the Learner) and
  `tutor` (`teachingMode`, `noticeStep`, `revealRequested`, `hintRung`, `hintsUsed`, `lastMoveFeedback`,
  `lastDiagnosis`, `guidedRecovery`, `stepMistakes`, `cleanSteps`, `cleanCredited`, `stepHelped`, `assistanceDismissed`) and `checkpoint` (the Checkpoint history *Try again* returns to); `—` for none. Any
  change of `session`, `virtual` or the undo/redo history advances `revision` by exactly 1 and
  cancels a pending confirmation; a change of `pending`, `tutor` or `checkpoint` alone does not advance it.
- **Undoable** — `yes` when the event pushes a Move onto the undo history, so a following `Undo`
  returns `virtual` to where it was before the event.
- **Clears redo** — `yes` when nothing is left to redo whenever the event advances `revision`.

Every event other than `LessonStarted` and `SolvePlaybackStarted` and `SessionRestored` and `CheckpointRestored` leaves an idle state (no session)
unchanged. A result
(`ResetConfirmed`, `ResetDeclined`) whose `effectId` and `revision` do not match the pending
confirmation at the current revision changes nothing but `lastDiscarded`
(`EffectResultDiscarded`) and the last Move's feedback and Diagnosis, which it clears; a `ResetConfirmed` overtaken by a newer revision re-offers a fresh confirmation while unsaved
steps remain.

| Event | Mutates | Undoable | Clears redo |
|-------|---------|----------|-------------|
| CheckpointRestored | session, virtual, tutor, checkpoint | no | yes |
| CheckpointSaveFailed | — | no | no |
| CheckpointLoadFailed | — | no | no |
| SessionRestored | session, virtual, tutor, checkpoint | no | no |
| LessonStarted | session, virtual, pending, tutor, checkpoint | no | yes |
| SolvePlaybackStarted | session, virtual | no | no |
| MoveCommitted | virtual, pending, tutor, checkpoint | yes | yes |
| Undo | virtual, pending, tutor, checkpoint | no | no |
| Redo | virtual, pending, tutor, checkpoint | yes | no |
| Reset | pending, tutor, checkpoint | no | yes |
| ResetConfirmed | virtual, pending, tutor, checkpoint | no | yes |
| ResetDeclined | pending, tutor | no | no |
| TeachingModeChanged | tutor | no | no |
| RevealRequested | tutor | no | no |
| HintRequested | tutor | no | no |
| UsualHelpRequested | tutor | no | no |
| StepAdvanced | tutor | no | no |
| RetryRequested | virtual, pending, tutor | no | yes |
| GuidedRecoveryRequested | tutor | no | no |
| RecoveryPlanned | tutor | no | no |
| RecoveryPlanFailed | tutor | no | no |
| SnapshotSaveFailed | — | no | no |
| SnapshotLoadFailed | — | no | no |

Persistence failures (Story 6.1, AD-12, AD-16): `SessionHost` dispatches `SnapshotSaveFailed` when the
serial writer reports `SnapshotSave.Failed`, and `SnapshotLoadFailed` when the cold-start read reports
`SnapshotLoad.Failed` or throws. Neither changes anything, in any session (not even the last Move's
feedback): a failed save leaves the session running in memory and the next committed transition writes
again; a failed load leaves the state idle, so the Learner starts a fresh Lesson.

Solve Playback sessions (`SOLVE_PLAYBACK`, Story 3.6): `SolvePlaybackStarted(state, solution)`
starts, from no session or over a playing one, a session whose entry and `virtual` are `state` and
which holds `solution`, with no Lesson; while a session of another kind (a Lesson) is active it
changes nothing, because AD-3 suspends that session first and suspension arrives with Story 3.7; like `LessonStarted` it keeps `teachingMode`, clears `noticeStep`, the reveal
and the last Move's feedback, cancels a pending confirmation and snaps the net to `state`. It applies
only when `solution` leaves `state` solved; an unverified Solution changes nothing. A playback
session takes no other event but `LessonStarted` (which replaces it): `MoveCommitted`, `Undo`,
`Redo`, `Reset`, the reset results and the tutor events leave it unchanged. Playback position and
speed belong to the `PlaybackController`, never to the session, and are never checkpointed (AD-20).

`Reset` with unsaved steps (at least one committed, not-undone Move) only issues
`Effect.ConfirmReset` and records it as `pending`; without unsaved steps it resets at once, which
leaves `virtual` at the entry state and clears redo.

The tutor fields: `LessonStarted` (with the content's `tutorConfig`) keeps `teachingMode` (the last
mode is remembered) and, like an applied `Reset` or `ResetConfirmed`, clears `noticeStep` and sets
`revealRequested` to false; a `Reset` that only asks leaves them until it is confirmed.
`TeachingModeChanged` sets `teachingMode`, `RevealRequested` sets `revealRequested`.

The Hint Ladder (FR-11): `hintRung` is the shown step's rung (`NONE`, `CLUE`, `TARGET`,
`DEMONSTRATION`) and `hintsUsed` the Hints applied in this attempt. `HintRequested` applies only when
`HintLadder.canClimb(teachingMode, shownRung, decision.advance, assistance.top)` holds (with adaptive assistance off, `shownRung` is `hintRung` and the top is `DEMONSTRATION`): not in *Show me*, below
`DEMONSTRATION`, and not while the tutor says `advance` (a Notice or completion is shown); it then
raises `hintRung` by exactly one rung and `hintsUsed` by exactly one, and otherwise changes nothing
(beyond clearing the last Move's feedback). The decision reveals the Moves from `TARGET` up
(`reveal` = *Show me* or `revealRequested` or `hintRung` ≥ `TARGET`); `RevealRequested` (Guide me's
*Show the Move*) is not a Hint and leaves both fields. `hintRung` is per-step state (AD-6): it resets
to `NONE` wherever `revealRequested` resets — `LessonStarted`, `SolvePlaybackStarted`, an applied
`Reset` or `ResetConfirmed`, `StepAdvanced`, and any history change that changes the shown step —
and `TeachingModeChanged` keeps it. `hintsUsed` counts per attempt: `LessonStarted` and
`SolvePlaybackStarted` set it to 0 and no other event lowers it (not a step change, `Undo`, `Reset`
or a mode change); Story 6.3 persists it.

The current step is never stored: `stepIndex` is derived (`Tutor.shownStep`) from the Lesson, the
entry state, the Cube State and `noticeStep`, which only keeps a completed step's Notice on the card
until *Next*. After any history change in a Lesson (`MoveCommitted`, `Undo`, `Redo`), with `s0` the
step shown before and `d1` the current step after (`Tutor.currentStep`), `noticeStep` becomes `s0`
when `d1 > s0` and the Lesson is not satisfied, else none; `revealRequested` resets whenever the
shown step changes. `StepAdvanced` applies only when the tutor's decision says `advance` and the
Lesson is not yet satisfied; it then clears `noticeStep` and hides the Moves again
(`revealRequested` false). None of `TeachingModeChanged`, `RevealRequested`, `HintRequested` and `StepAdvanced`
changes the Cube State, the history or `revision`.

`lastMoveFeedback` and `lastDiagnosis` are `Assess.classify`'s word on the last committed Move,
judged against the step derived from the state before it (`MoveFeedback`: On path, Progress,
Neutral or Harmful, and the `Diagnosis` it names, if any). `MoveCommitted` sets them only while the
Teaching Mode is *Test me*; a `MoveCommitted` in any other mode clears both. The tutor's decision
shows the feedback only in *Test me* (beside the Notice or completion when the Move finished a step
or the Lesson) and the Diagnosis only in *Test me* while the step is not yet done (`advance`). Every
other event in a Lesson session clears both (AD-5): `LessonStarted`, `Undo`, `Redo`, `Reset` (also
when it only asks for confirmation), `ResetConfirmed` and `ResetDeclined` (also when discarded),
`TeachingModeChanged`, `RevealRequested`, `HintRequested`, `UsualHelpRequested`, `StepAdvanced` and `RetryRequested`, and
each of these also returns `guidedRecovery` to `None`; none of these clearings advances
`revision` by itself. The three Guided recovery events (`GuidedRecoveryRequested`, `RecoveryPlanned`,
`RecoveryPlanFailed`) act on the Diagnosis and never clear it. A Diagnosis never replaces or rewinds `virtual`: the Cube State stays
the Learner's own.

The Checkpoint (FR-12): `checkpoint` is the history at the last Objective boundary, `null` in an idle
or Solve Playback session (AD-20) and, in a Lesson, always with nothing to redo. `LessonStarted` and
an applied `Reset` or `ResetConfirmed` set it to the entry history. After any history change in a
Lesson (`MoveCommitted`, `Undo`, `Redo`) whose result has a pending Notice (`noticeStep`) or meets the
Lesson's Completion Predicate (`objectiveMet`), it becomes the new history with redo dropped. Nothing
else writes it: a Move that disturbs preserved pieces, the tutor events and `RetryRequested` leave it,
and an `Undo` below a boundary does not lower it. It never holds the current step (AD-6), and its
changes never advance `revision`.

*Try again* (`RetryRequested`): in a Lesson whose history differs from `checkpoint`, the history
becomes `checkpoint` (the next `revision`, a pending confirmation cancelled), `noticeStep` clears,
`revealRequested` is false and `hintRung` is `NONE`; the Lesson, its entry, `teachingMode`,
`hintsUsed` and `checkpoint` stay, and the net snaps to the Checkpoint's state. It pushes nothing
undoable and leaves nothing to redo, though the restored history can still be undone Move by Move.
When the history already equals `checkpoint` it changes nothing but the last Move's feedback and
Diagnosis, which it clears like every other non-commit Lesson event; idle and playback sessions
ignore it.

Guided recovery (FR-12, Story 4.5): `guidedRecovery` is `None`, `Planning(effect)`, `Planned(moves,
pieces, text)` or `NotFound`, and `None` in an idle state, at `LessonStarted` and `SolvePlaybackStarted`.
The goal (`RecoveryPlanner.goal`) is the preserved pieces at the Checkpoint's state and at the state
before the last Move (`history.undo().state`). `canGuideBack` holds in a Lesson whose shown Diagnosis
offers *Guide me back*, while `guidedRecovery` is `None`, with a Checkpoint and the goal unmet on
`virtual`. `GuidedRecoveryRequested` then issues `Effect.PlanRecovery(nextEffectId, revision, virtual,
goal, recoveryMaxDepth, moves)` and sets `Planning`; `nextEffectId` rises by 1 and `revision` stays.
Otherwise it changes nothing. The plan is searched by the `PlanRecovery` use case off the reducer
(IDA*, 3 s timeout, `Dispatchers.Default`), never in `reduce`.

A result (`RecoveryPlanned`, `RecoveryPlanFailed`) is current when `guidedRecovery` is `Planning` with
the same `effectId` and `revision`, and that `revision` is the current one. A current `RecoveryPlanned`
is verified again: when its Moves take `virtual` to a state meeting the goal it becomes `Planned` (the
plan copy, `RecoveryPlanner.describe`), else `NotFound`; a current `RecoveryPlanFailed` becomes
`NotFound`. A result that is not current changes nothing but `lastDiscarded`
(`EffectResultDiscarded`); when a newer revision overtook it and `canGuideBack` holds, a fresh
`Effect.PlanRecovery` is issued at the current revision and `guidedRecovery` is `Planning` (the
re-offer, as for a stale `ResetConfirmed`).

Every history change (`withHistory`) returns `guidedRecovery` to `None`, which cancels a running plan
(AD-3), with one exception: a `MoveCommitted` equal to the first Move of a `Planned` plan keeps it
`Planned` with the Moves left, and `None` once none are left. A plan is never applied to the cube; the
Learner turns its Moves.

Adaptive assistance (FR-13, Story 4.6): `LessonStarted(lesson, tutorConfig, at, adaptiveAssistance)`
records the `ADAPTIVE_ASSISTANCE` flag (a `session` field; the app reads the flag, `:core:*` never
does) and resets `stepMistakes` (Harmful Moves per step), `cleanSteps` (steps in a row completed
cleanly), `cleanCredited` (the steps whose clean completion already counted), `stepHelped` (the steps
that used help: an applied `HintRequested` or a `RevealRequested` adds the shown step) and `assistanceDismissed`
(the level dismissed per step); no other event resets them, not
`Reset` and not `RetryRequested`, and idle and playback sessions hold them empty or 0. `MoveCommitted`
assesses the Move with `Assess.classify` in *Test me*, and also in *Guide me* and *Let me try* while
`adaptiveAssistance` is on; only *Test me* keeps `lastMoveFeedback` and `lastDiagnosis`. A Harmful
result adds 1 to `stepMistakes[Tutor.currentStep(before)]` and sets `cleanSteps` to 0. A commit that
makes a Notice newly pending or newly completes the Lesson completes `s0`, the step shown before: it
is clean when `s0` has no mistakes (counted after this Move) and the before-state `hintRung` is
`NONE` with no reveal, and `s0` is not in `stepHelped` (a step that used help is never clean in this
Lesson, even after `Reset` or `RetryRequested`), which raises `cleanSteps` by 1 and adds `s0` to `cleanCredited` — once per Lesson: a clean
completion of a step already in `cleanCredited` (say after an `Undo` across its boundary) leaves
`cleanSteps`; any other completed step sets it to 0. `Undo`, `Redo`,
`Reset` and `RetryRequested` never count.

`assistance` is derived, never stored: `USUAL` outside a Lesson, with the flag off, in *Show me*, or
when the level equals `assistanceDismissed[stepIndex]`; else `AdaptivePolicy.level(tutorConfig.adaptive,
stepMistakes[stepIndex], cleanSteps)` — `GUIDE`, `DEMONSTRATE`, `EXPLAIN` at their mistake thresholds,
`REDUCED` at the clean-step threshold, else `USUAL`. Each level is a floor and a top on the Hint
Ladder: `shownRung` is `hintRung` held within them, the decision reveals from `shownRung`, and
`HintRequested` climbs from `shownRung` (`hintRung = shownRung.next()`) only below the level's top
(`TARGET` when `REDUCED`). `UsualHelpRequested` sets `assistanceDismissed[stepIndex]` to the current
level when it is not `USUAL` and otherwise changes nothing (beyond the generic clear); a later rise to
a different level shows again. No level, and no adaptive event, ever changes `teachingMode`, plays a
Demonstration in `reduce` or applies a Move, and none of these fields advances `revision`. (The app
plays the shown step's Moves once on the net when the step escalates to `DEMONSTRATE`; it dispatches
nothing and any session interaction cancels it.)

`PersistCheckpoint` is appended after Lesson transitions when the Checkpoint changes, on every
`LessonStarted`, and on an applied `CheckpointRestored`. It consumes one effect ID, carrying the
committed revision, content IDs, boundary history, teaching settings and event time. Snapshot
restoration, playback and tutor-only changes never emit it. The host serializes it off the main thread.

`CheckpointRestored` applies only from IDLE for the matching Lesson, with validated Facelets,
matching orientation IDs and a parsed history that reaches the stored cube. Matching content and
stage resume the boundary history without redo. Otherwise the stored cube becomes the entry with
empty history and `restoreNote = Text("msg.lesson.updated")`. Tutor counters are fresh in both cases.
`restoreNote` is transient, null in IDLE and never snapshotted. Every later event taken by a Lesson
clears it except `SnapshotSaveFailed`, `SnapshotLoadFailed`, `CheckpointSaveFailed` and
`CheckpointLoadFailed`, which change nothing.
