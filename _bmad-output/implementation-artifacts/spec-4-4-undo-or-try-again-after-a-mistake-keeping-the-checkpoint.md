---
title: 'Undo or try again after a mistake, keeping the Checkpoint'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: 33f91f51d174394b487bb67eab158c5fb1c14820
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

**Problem:** After a diagnosed mistake the Lesson panel only prints the Recovery Path sentence ("Undo, or let me guide you back."). There are no recovery buttons, the disturbed pieces are not outlined, and the session has no Checkpoint to return to, so *Try again* cannot exist (FR-12, UX-DR11, UX-DR25 "Completed layer disturbed").

**Approach:** Add a session-held **Checkpoint**. It is the undo history at the last Objective boundary: the Lesson's entry, or the last state where a step's Notice became pending or the Lesson became complete. Disturbing Moves never move it. A new `SessionEvent.RetryRequested` (*Try again*) restores the history to the Checkpoint without restarting the Lesson. A new inline `RecoveryPrompt` component renders the Diagnosis's `recoveryPath.offers` as buttons, *Undo*, *Guide me back* (disabled until 4.5) and *Try again*, in that order. The stage outlines `diagnosis.disturbed` in violet.

## Boundaries & Constraints

**Always:**
- `SessionState.checkpoint: MoveHistory?`. It is `null` in IDLE and in Solve Playback (AD-20), and in a Lesson it always has nothing to redo. It is written only here:
  - `LessonStarted` and an applied `Reset`/`ResetConfirmed` set it to `MoveHistory(entry)`.
  - After any history change (`MoveCommitted`, `Undo`, `Redo`) whose result has a pending Notice (`noticeStep != null`) or `objectiveMet`, it becomes the new history with redo dropped.
  - Nothing else writes it. A Move that breaks preserved pieces, any tutor event and `RetryRequested` leave it unchanged. The current step is never stored in it (AD-6).
- `RetryRequested` in a Lesson session whose history differs from `checkpoint`:
  - `history := checkpoint`, through `withHistory`, so `revision` +1 and a pending reset confirmation is cancelled.
  - `noticeStep = null`, `revealRequested = false`, `hintRung = NONE`; `hintsUsed`, `teachingMode`, Lesson and entry are kept.
  - Presentation is `Snap(revision, checkpoint.state)`.
  - It is not undoable and leaves nothing to redo.
  - When the history already equals the checkpoint, nothing changes except the generic feedback/Diagnosis clear. Idle and playback sessions ignore it.
- `checkpoint` changes never advance `revision`. It is a new `checkpoint` token in TRANSITIONS.md's Mutates column, and `TransitionsTableTest.changed()` checks it.
- The recovery prompt is inline, inside the step card under the `DiagnosisLine`, and never a dialog. It shows only when `decision.diagnosis != null`, with buttons in `recoveryPath.offers` order:
  - *Undo* sends `SessionEvent.Undo` and is enabled when `history.canUndo`.
  - *Guide me back* is shown and always disabled.
  - *Try again* sends `RetryRequested`.
  - *Show me how* (REORIENT) keeps its existing `DiagnosisLine` control; `RE_ENTER` renders nothing in a Lesson.
- Button labels are keyed content text in `en.json`: `msg.recovery.offer.undo` "Undo", `msg.recovery.offer.guide-me-back` "Guide me back", `msg.recovery.offer.try-again` "Try again". The UI never decides the offers; it renders them.
- While a Diagnosis is shown with non-empty `disturbed`, the net emphasis is `NetEmphasis(lit = ALL_PIECES, highlighted = disturbed, pulse)`, which takes precedence over the hint target. Stickers are never recolored, and there is no red, shake or dialog (UX-DR28).

**Never:**
- enabling *Guide me back*, `PlanRecovery`/`RecoveryPlanner`, or `Recovery.Pending/Ready` (all Story 4.5);
- Checkpoint persistence, a `PersistCheckpoint` effect or a DTO (Epic 6);
- Stage unlocking logic;
- changing `TutorDecision`'s field set;
- a recovery UI in `LessonScreen.kt`'s body beyond wiring;
- resetting `hintsUsed` on *Try again*;
- editing planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Disturbed cross | shipped `lesson-03-01` (cross solved at entry) in *Test me*, commit `R` | Diagnosis `COMPLETED_LAYER_DISTURBED` ("That turn moved the cross."), prompt "Undo, or let me guide you back.", offers UNDO, GUIDE_ME_BACK, TRY_AGAIN; `checkpoint == MoveHistory(entry)` unchanged; `objectiveMet` false, `decision.satisfied`/`advance` false (no next Stage) | — |
| Try again mid-Lesson | `lesson-02-05` *Test me*: `F2`, *Next* (checkpoint = [F2]), hint ×1, then a harmful Move | `RetryRequested` → `virtual == entry·F2`, `history.done == [F2]`, no redo, `stepIndex` 1, same Lesson, `hintsUsed` 1, `hintRung` NONE, revision +1, Diagnosis cleared, checkpoint unchanged | — |
| Undo after Try again | the state above | `Undo` takes back `F2` (the restored history is a real history) | — |
| Nothing to retry | fresh Lesson, `RetryRequested` | state unchanged (revision same) | ignored |
| Not a Lesson | IDLE or playback, `RetryRequested` | unchanged | ignored |
| Checkpoint follows boundaries | white-cross fixture: `F'` (complete) → cp [F'], `R`, `R'` → cp [F', R, R'], `Undo` ×2 → cp [F'] | as stated; a disturbing Move leaves cp alone | — |
| Reset | any Lesson with cp ahead of entry, applied Reset/ResetConfirmed | cp = `MoveHistory(entry)` | — |
| Screen | `lesson-03-01` on screen, *Test me*, press `R` | `lesson:recovery` row inside `lesson:stepCard` with `lesson:recovery:undo`, `…:guideMeBack` (not enabled), `…:tryAgain` left to right; no dialog node; highlighted stickers = stickers of `diagnosis.disturbed`; *Try again* → net shows entry, prompt gone; *Undo* → net shows entry, prompt gone | — |

</intent-contract>

## Code Map

- `core/domain/.../cube/MoveHistory.kt` -- private constructor; add `fun withoutRedo(): MoveHistory = MoveHistory(start, state, done, emptyList())` (KDoc), plus a test in the existing `MoveHistoryTest`.
- `core/application/.../session/SessionState.kt` -- tutor fields KDoc :17-31, constructor :33-80, `IDLE` :117-137. Add `checkpoint` (KDoc: the Objective-boundary history *Try again* returns to, never the current step).
- `core/application/.../session/SessionEvent.kt` -- add `RetryRequested(at)` beside `HintRequested` (KDoc: *Try again*, FR-12).
- `core/application/.../session/Reduce.kt`:
  - dispatch `when` :278-291;
  - `start` :293 and `startPlayback` :322 (set `checkpoint`, null for playback);
  - `move`/`withNotice` :381-403, where the boundary write goes: after `withNotice`, if `next.noticeStep != null || next.objectiveMet` → `checkpoint = next.history.withoutRedo()`;
  - `reset` :409 sets the checkpoint to `MoveHistory(entry)`, i.e. `history.reset()`;
  - `withHistory` :500 is reused for the retry.
  - The generic feedback clear :267-271 already covers the new event.
- `core/application/.../session/TRANSITIONS.md` -- the Columns list gets `checkpoint`. Rows:
  - add `RetryRequested | virtual, pending, tutor | no | yes`;
  - add `checkpoint` to LessonStarted, MoveCommitted, Undo, Redo, Reset and ResetConfirmed;
  - add prose for Checkpoint writes and *Try again*.
- `core/application/src/test/.../session/TransitionsTableTest.kt` -- `changed()` :159-179 adds `checkpoint`. Scenarios:
  - checkpoint-changing scenarios for MoveCommitted (`fresh to commit(F_PRIME)`), Undo/Redo (white-cross sequences per the matrix), Reset (`fresh.after(commit(F_PRIME), undo)`, no unsaved steps but redo) and ResetConfirmed (asked after `F'`);
  - a `RetryRequested` scenario list including one with redo to clear and one that moves the cube;
  - the `samples` entry;
  - event count 12 → 13.
- `core/application/src/test/.../session/Lessons.kt` -- add `internal val retry = SessionEvent.RetryRequested(T0)`. `DiagnosisSessionScenarioTest.kt` `testMe(id, steps)` is the shipped-Lesson *Test me* helper to reuse.
- `core/domain/src/main/resources/content/strings/en.json` :24-29 -- the `msg.recovery.*` block; add the three offer labels.
- `core/domain/.../tutor/ErrorCategory.kt` -- `RecoveryPath.offers` and `RecoveryOffer` are already final; update the "Story 4.4 renders them" KDoc only.
- `app/.../ui/lesson/DiagnosisLine.kt` -- the existing explanation + prompt + *Show me how*; the new prompt sits after it in `LessonPanel.StepCard` :277-290.
- `app/.../ui/lesson/LessonPanel.kt` -- `StepCardContent` :137-154 (add `recoveryActions: List<RecoveryAction>`, where `RecoveryAction(offer, label, enabled)`); `LessonPanel` params :162-176 (add `onRecovery: (RecoveryOffer) -> Unit = {}`) and `StepCard`.
- `app/.../ui/lesson/LessonScreen.kt`:
  - `stepCard()` :332-393 builds `recoveryActions` from `decision.diagnosis?.recoveryPath?.offers`, filtered to UNDO/GUIDE_ME_BACK/TRY_AGAIN and resolved via `strings.resolve(TextKey(...))`, like `SHOW_ME_HOW_LABEL` :540;
  - `LessonPanel(...)` :213 gets `onRecovery` (UNDO → `send(SessionEvent::Undo)`, TRY_AGAIN → `send(SessionEvent::RetryRequested)`);
  - the emphasis `when` :255-276 gets a first branch for disturbed pieces.
- `app/src/test/.../ui/lesson/LessonScreenTest.kt`:
  - `open()`/catalog :88-122 (add shipped `lesson-03-01` moved into `stage-01-basics` like `crossLesson`);
  - `stickersWith(StickerHighlightedKey)` :124;
  - the existing harmful test :814;
  - `press`, `switchTo` and `tagsStartingWith` helpers exist.
- `app/src/test/.../content/HintCopyTest.kt` / `DiagnosisCopyTest.kt` -- the exact-copy pattern for the new labels.

## Tasks & Acceptance

**Execution:**
- [x] `core/domain/.../cube/MoveHistory.kt` and its test -- `withoutRedo()` -- the Checkpoint never holds redo.
- [x] `core/application/.../session/{SessionEvent,SessionState,Reduce}.kt` -- `RetryRequested`, `checkpoint` and the writes/retry per Boundaries -- AC 2-3.
- [x] `core/application/.../session/TRANSITIONS.md` and `TransitionsTableTest.kt` -- the new row, the `checkpoint` token and scenarios -- the normative table stays checked.
- [x] `core/application/src/test/.../session/RecoverySessionTest.kt` (new) -- every non-screen matrix row through `reduce`, using shipped `lesson-03-01`/`lesson-02-05` and the white-cross fixtures. Fix other session tests that enumerate events or tutor fields.
- [x] `core/domain/src/main/resources/content/strings/en.json` -- three `msg.recovery.offer.*` labels; extend the copy test to pin them.
- [x] `app/.../ui/lesson/RecoveryPrompt.kt` (new) -- `RecoveryPrompt(actions, onAction)`:
  - an inline `Row` (FlowRow if needed for 200% type) tagged `lesson:recovery`, with `OutlinedButton`s tagged `lesson:recovery:undo|guideMeBack|tryAgain`;
  - touch-min height; `inkDisabled` when disabled;
  - renders nothing for an empty list -- UX-DR11.
- [x] `app/.../ui/lesson/{LessonPanel,LessonScreen}.kt` -- the `RecoveryAction` content, `onRecovery` wiring, the disturbed-piece emphasis -- AC 1-2.
- [x] `app/src/test/.../ui/lesson/LessonScreenTest.kt` -- the screen matrix row:
  - order by left bound (`fetchSemanticsNode().boundsInRoot.left`);
  - `rule.onAllNodes(isDialog()).assertCountEquals(0)`;
  - the highlighted set equals the stickers of `decision.diagnosis.disturbed`;
  - *Try again* and *Undo* each restore the net (`assertNetMatchesSession`) and the Lesson id is unchanged.
  - Also: a turned cube (REORIENT) shows no recovery row.

**Acceptance Criteria:**
- Given a diagnosed error in *Test me*, when the recovery prompt shows, then it is inline in the step card and offers *Undo*, *Guide me back* (disabled) and *Try again*, in that order, with no dialog.
- Given the cross is complete in `lesson-03-01` and `R` breaks it, when the tutor evaluates, then it says "That turn moved the cross.", outlines exactly the disturbed pieces in violet, offers undo or Guided recovery, keeps `checkpoint` equal to the Lesson-entry history, and the Lesson is neither satisfied nor advanceable.
- Given *Try again*, when it is tapped, then the cube and history return to the last Checkpoint, and the Lesson, Teaching Mode and `hintsUsed` stay (no `LessonStarted`).

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 14 findings — high 0, medium 0, low 9, false 5, maybe-false 0
- findings:
  - `[false]` `[reject]` BH-1: the checkpoint is rewritten by Moves made while a Notice is still pending — that is the spec's rule. Every such state still has the shown step done, so the Checkpoint is always a valid boundary state and no progress is lost.
  - `[false]` `[reject]` BH-2: Try again restores an undoable history, which conflicts with AD-3's undo barrier — AD-3's barrier is on the persisted-resume event `CheckpointRestored` (Epic 6), not on *Try again*. Undoing back through the restored history is plain Undo, and no harm is named.
  - `[low]` `[reject]` BH-3: a late `ResetConfirmed` after *Try again* re-offers the reset — this is the existing AD-3 re-offer path, shared with any Move made while a confirmation is pending. The confirmation is a modal dialog, so *Try again* can't normally be tapped while it is open.
  - `[low]` `[reject]` BH-4: the recovery FlowRow is not tested at 200% type or narrow widths — `FlowRow` wraps by construction, and a layout test adds code for an unlikely defect.
  - `[low]` `[reject]` BH-5: the disturbed outline is not tested with a hint target active — the precedence is the first branch of one `when`. A second screen test adds code for a defect nobody is likely to hit.
  - `[low]` `[reject]` BH-6: on screen, *Try again* is exercised only with the entry Checkpoint — the screen only sends `RetryRequested`, and the reducer test covers the mid-Lesson Checkpoint (`lesson-02-05` after F2).
  - `[low]` `[reject]` BH-7: no test where only the redo stack differs from the Checkpoint — then *Try again* only clears redo and snaps to the same state, which is harmless and not worth a dedicated test.
  - `[low]` `[reject]` BH-8: the UNDO and RETRY Recovery Paths are not tested on screen — `RecoveryPath.offers` returns the same three-offer list for UNDO, GUIDED_RECOVERY and RETRY, so the GUIDED_RECOVERY screen test covers the shared rendering.
  - `[false]` `[reject]` BH-9: the spec's Verification section records no results — results go under `## Auto Run Result`, written at finalize.
  - `[low]` `[patch]` VG-1: the exact Checkpoint after a Redo onto a boundary was not asserted — added `RecoverySessionTest` "a Redo onto a boundary makes it the Checkpoint, and Try again returns there" (F' R R', Undo ×3, Redo ×3, then a disturbing Move and *Try again* returns to [F', R, R'] with no redo).
  - `[false]` `[reject]` ECH: the edge-case layer reported no findings (empty list); logged for completeness.
  - `[false]` `[reject]` IA-1: "does not unlock the next Stage" is checked only through the decision flags — the app has no Stage-unlock state yet (Epic 6/7). Stage completion can only follow a satisfied Lesson, and `satisfied`, `advance` and `objectiveMet` are asserted false.
  - `[low]` `[reject]` IA-2: the violet colour is not asserted — `StickerHighlightedKey` marks the `sticker-highlight` outline, whose token is the violet one, and a pixel colour check adds test code for no real risk.
  - `[low]` `[reject]` IA-3: the mid-Lesson *Try again* is tested only in the reducer — grouped with BH-6, same reason.

## Design Notes

**Which Objective boundary.** In this app a Lesson has one Objective, made of steps. The shipped retry copy is "Undo, or start this step again." and UX-DR11's no-plan line reads the same, and the AC says "without restarting the Lesson". So the Checkpoint is the last step boundary: the state where a step's Notice became pending, or where the Lesson completed. It falls back to the entry. *Try again* restarts the current step from there. The Checkpoint stores the history, not a bare Cube State, so `entry` (`history.start`) and step derivation stay intact. This also matches AD-10's Checkpoint format, which keeps the history as Notation. An Undo that goes back below a boundary does not lower the Checkpoint. Only a later boundary, a Reset or a new Lesson moves it.

**Boundary write** (sketch, in `move`):
```kotlin
val next = moved.lesson?.let { withNotice(state, moved, it) } ?: moved
val boundary = next.lesson != null && (next.noticeStep != null || next.objectiveMet)
val kept = if (boundary) next.copy(checkpoint = next.history.withoutRedo()) else next
```

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `RecoverySessionTest`, `TransitionsTableTest`, `MoveHistoryTest`, `LessonScreenTest`, `ShippedContentTest` and the copy tests, with Kover ≥ 80% on `:core:*`.

## Auto Run Result

- **Summary:** Added the session Checkpoint and *Try again* (FR-12), and the inline recovery prompt (UX-DR11).
  - **Checkpoint:** `SessionState.checkpoint` is the entry history at Lesson start and after an applied Reset. After a Move, Undo or Redo it becomes the history (redo dropped) whenever a step's Notice is pending or the Lesson is complete. A disturbing Move never moves it.
  - **Try again:** `SessionEvent.RetryRequested` restores the Checkpoint's history. The revision goes up by one; the Notice, reveal and hint rung reset; the Lesson, Teaching Mode and `hintsUsed` stay.
  - **Recovery prompt:** rendered inside the step card from the Diagnosis's `recoveryPath.offers`: *Undo*, *Guide me back* (disabled) and *Try again*, in that order, never in a dialog.
  - **Outline:** the disturbed pieces are outlined in violet on the net.
- **Files:**
  - `core/domain/.../cube/MoveHistory.kt`: `withoutRedo()`.
  - `core/domain/.../tutor/ErrorCategory.kt`: KDoc only.
  - `core/domain/src/main/resources/content/strings/en.json`: three `msg.recovery.offer.*` labels.
  - `core/application/.../session/{SessionEvent,SessionState,Reduce}.kt`: `RetryRequested`, `checkpoint`, the boundary writes and the retry.
  - `core/application/.../session/TRANSITIONS.md`: the `checkpoint` token, the `RetryRequested` row and prose.
  - `app/.../ui/lesson/RecoveryPrompt.kt` (new): the inline button row.
  - `app/.../ui/lesson/{LessonPanel,LessonScreen}.kt`: the recovery actions, their wiring and the disturbed-piece emphasis.
  - Tests: `RecoverySessionTest` (new, 9 tests); `TransitionsTableTest`, `MoveHistoryTest`, `LessonScreenTest`, `DiagnosisCopyTest` and `Lessons.kt` updated.
- **Review:** 14 findings.
  - Patched: 1 low (VG-1, a Redo boundary-and-retry test).
  - Deferred: none.
  - Rejected: 8 low (BH-3 to BH-8, IA-2, IA-3) and 5 false (BH-1, BH-2, BH-9, ECH, IA-1), with reasons in the triage log.
- **Follow-up review recommended:** false. Patched counts by entry verdict: high 0, medium 0, low 1.
- **Verification:** `./gradlew spotlessApply && ./gradlew spotlessCheck build` gave BUILD SUCCESSFUL before review and again after the patch. Every I/O matrix row has a passing test: `RecoverySessionTest` 9/9, `LessonScreenTest` 29/29, `TransitionsTableTest` 2/2.
- **Residual risks:**
  - After undoing below a boundary, *Try again* moves the cube forward to the Checkpoint, so the Learner gets back Moves they had undone.
  - *Try again* from a Checkpoint saved at a pending Notice clears that Notice and shows the next step.
  - *Guide me back* stays disabled until Story 4.5; Checkpoint persistence is Epic 6.
