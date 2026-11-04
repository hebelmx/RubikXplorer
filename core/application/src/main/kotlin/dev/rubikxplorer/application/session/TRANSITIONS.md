# Session transitions (normative)

What each `SessionEvent` does to a `LearningSession` in **virtual mode** (AD-3). `TransitionsTableTest`
parses the table below and checks every row against `reduce()`; a `SessionEvent` type without a row,
or a row naming no type, fails the build. Physical-mode rows (`expected`, `observed`) arrive with Epic 8.

Columns:

- **Mutates** — the fields the event may change, from `session` (the session's kind, its Lesson and
  tutor configuration or its Solution, and its entry state),
  `virtual` (the current Cube State), `pending` (the reset confirmation awaiting the Learner) and
  `tutor` (`teachingMode`, `noticeStep`, `revealRequested`, `hintRung`, `hintsUsed`, `lastMoveFeedback`,
  `lastDiagnosis`); `—` for none. Any
  change of `session`, `virtual` or the undo/redo history advances `revision` by exactly 1 and
  cancels a pending confirmation; a change of `pending` or `tutor` alone does not advance it.
- **Undoable** — `yes` when the event pushes a Move onto the undo history, so a following `Undo`
  returns `virtual` to where it was before the event.
- **Clears redo** — `yes` when nothing is left to redo whenever the event advances `revision`.

Every event other than `LessonStarted` and `SolvePlaybackStarted` leaves an idle state (no session)
unchanged. A result
(`ResetConfirmed`, `ResetDeclined`) whose `effectId` and `revision` do not match the pending
confirmation at the current revision changes nothing but `lastDiscarded`
(`EffectResultDiscarded`) and the last Move's feedback and Diagnosis, which it clears; a `ResetConfirmed` overtaken by a newer revision re-offers a fresh confirmation while unsaved
steps remain.

| Event | Mutates | Undoable | Clears redo |
|-------|---------|----------|-------------|
| LessonStarted | session, virtual, pending, tutor | no | yes |
| SolvePlaybackStarted | session, virtual | no | no |
| MoveCommitted | virtual, pending, tutor | yes | yes |
| Undo | virtual, pending, tutor | no | no |
| Redo | virtual, pending, tutor | yes | no |
| Reset | pending, tutor | no | yes |
| ResetConfirmed | virtual, pending, tutor | no | yes |
| ResetDeclined | pending, tutor | no | no |
| TeachingModeChanged | tutor | no | no |
| RevealRequested | tutor | no | no |
| HintRequested | tutor | no | no |
| StepAdvanced | tutor | no | no |

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
`HintLadder.canClimb(teachingMode, hintRung, decision.advance)` holds: not in *Show me*, below
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
`TeachingModeChanged`, `RevealRequested`, `HintRequested` and `StepAdvanced`; none of these clearings advances
`revision` by itself. A Diagnosis never replaces or rewinds `virtual`: the Cube State stays
the Learner's own.
