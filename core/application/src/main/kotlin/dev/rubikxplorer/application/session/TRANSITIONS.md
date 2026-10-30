# Session transitions (normative)

What each `SessionEvent` does to a `LearningSession` in **virtual mode** (AD-3). `TransitionsTableTest`
parses the table below and checks every row against `reduce()`; a `SessionEvent` type without a row,
or a row naming no type, fails the build. Physical-mode rows (`expected`, `observed`) arrive with Epic 8.

Columns:

- **Mutates** — the fields the event may change, from `session` (the Lesson and its entry state),
  `virtual` (the current Cube State), `pending` (the reset confirmation awaiting the Learner) and
  `tutor` (`teachingMode`, `stepIndex`, `revealRequested`); `—` for none. Any change of `session`,
  `virtual` or the undo/redo history advances `revision` by exactly 1 and cancels a pending
  confirmation; a change of `pending` or `tutor` alone does not advance it.
- **Undoable** — `yes` when the event pushes a Move onto the undo history, so a following `Undo`
  returns `virtual` to where it was before the event.
- **Clears redo** — `yes` when nothing is left to redo whenever the event advances `revision`.

Every event other than `LessonStarted` leaves an idle state (no session) unchanged. A result
(`ResetConfirmed`, `ResetDeclined`) whose `effectId` and `revision` do not match the pending
confirmation at the current revision changes nothing but `lastDiscarded`
(`EffectResultDiscarded`); a `ResetConfirmed` overtaken by a newer revision re-offers a fresh confirmation while unsaved
steps remain.

| Event | Mutates | Undoable | Clears redo |
|-------|---------|----------|-------------|
| LessonStarted | session, virtual, pending, tutor | no | yes |
| MoveCommitted | virtual, pending | yes | yes |
| Undo | virtual, pending | no | no |
| Redo | virtual, pending | yes | no |
| Reset | pending, tutor | no | yes |
| ResetConfirmed | virtual, pending, tutor | no | yes |
| ResetDeclined | pending | no | no |
| TeachingModeChanged | tutor | no | no |
| RevealRequested | tutor | no | no |
| StepAdvanced | tutor | no | no |

`Reset` with unsaved steps (at least one committed, not-undone Move) only issues
`Effect.ConfirmReset` and records it as `pending`; without unsaved steps it resets at once, which
leaves `virtual` at the entry state and clears redo.

The tutor fields: `LessonStarted` keeps `teachingMode` (the last mode is remembered) and, like an
applied `Reset` or `ResetConfirmed`, sets `stepIndex` to 0 and `revealRequested` to false; a `Reset`
that only asks leaves them until it is confirmed. `TeachingModeChanged` sets `teachingMode`,
`RevealRequested` sets `revealRequested`. `StepAdvanced` applies only when the tutor's decision says
`advance`, the Lesson is not yet satisfied and a later step exists; it then moves `stepIndex` on by
one and hides the Moves again (`revealRequested` false). None of the three changes the Cube State,
the history or `revision`.
