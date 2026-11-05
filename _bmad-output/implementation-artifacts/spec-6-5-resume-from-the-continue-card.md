---
title: 'Story 6.5: Resume from the Continue card'
type: 'feature'
created: '2026-11-05'
baseline_revision: '2e2feb5e4de01501bc779873718c67267502d38d'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Nothing in production calls SessionHost.endSolve or dispatches SolvePlaybackStarted yet, so in the running app a solve cannot suspend a Lesson and resume it (AC 2 is proven only at the SessionHost surface).
    evidence: |-
      The Solve tab is still the free cube. Story 3.7 (Solve tab end to end, backlog) owns the solve entry and the "Starting a solve pauses your lesson." copy, and it must call endSolve(bundle) when the solve ends. Covered today by SessionHostCheckpointTest.endingSolveReadsQueuedCheckpointAndDropsLaterMoves and endingSolveWithoutCheckpointStaysIdle.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt (endSolve)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** A stored Checkpoint is restored into `SessionHost` at cold start, but the Learn tab gives no way to see or reach it, a resumed Lesson shows no note, a playback snapshot hides the stored Lesson, and a solve cannot start over a Lesson. So nothing can suspend a Lesson or resume it afterwards (FR-19, AD-3, AD-10, UX-DR25, UX Flow 1).

**Approach:**
- The Learn tab shows a Continue card for the Lesson that `SessionHost` holds. After the cold-start restore, that is the stored Checkpoint's Lesson, or its newer snapshot.
- A resumed session carries `restoreNote = Text("msg.lesson.resumed")`, which reads "Picked up where you left off."
- `SolvePlaybackStarted` over an active Lesson now suspends it.
- A new `SolvePlaybackEnded` event, run through `SessionHost.endSolve(bundle)`, resumes the suspended Lesson from its stored Checkpoint. It uses the same `CheckpointRestored` path as cold start.

## Boundaries & Constraints

**Always:**
- **Note rule.** Every *applied* restore sets `restoreNote`, as one rule:
  - a `CheckpointRestored` that restarts keeps `msg.lesson.updated`;
  - every other applied `CheckpointRestored`, and every applied `SessionRestored`, gets `msg.lesson.resumed`.
  - The existing clearing rules are unchanged: any later Lesson event clears the note, and persistence-failure events keep it. Add `"msg.lesson.resumed": "Picked up where you left off."` to `en.json`.
- **Suspend.** `SolvePlaybackStarted` with a verified Solution over an active `LESSON` replaces it with the playback session, exactly as it does from IDLE.
  - When the Lesson's attempt is open, it emits `AttemptEnded(ABANDONED)` with the Lesson's counters and peak help. This is the same ordering and effect-id rules as a Lesson replacing a Lesson. The host already uses the previous session ID for ABANDONED.
  - An unverified Solution still changes nothing.
  - No `PersistCheckpoint` is emitted, because the Lesson's last Checkpoint is already queued or written.
- **End.** New `SessionEvent.SolvePlaybackEnded(at)`:
  - In a `SOLVE_PLAYBACK` session it returns to no session: `SessionState.IDLE` with `revision = before.revision + 1` and `nextEffectId` kept, so revisions stay monotonic. It has no effects and no presentation.
  - In any other state it changes nothing.
  - Add its row and prose to `TRANSITIONS.md`, and replace the "suspension arrives with Story 3.7" sentence.
- **`SessionHost.endSolve(bundle: ContentBundle)`:**
  - It dispatches `SolvePlaybackEnded`.
  - If the state is then idle, it runs the same stored-Checkpoint resume that cold start uses: extract `restoreLatest`'s checkpoint block into one private function that both call. The read goes through `writer.read`, so it is ordered after any queued Checkpoint save.
  - It regenerates `sessionId` on the applied `CheckpointRestored`, as today.
- **Cold start after a playback snapshot.** A `SOLVE_PLAYBACK` snapshot is still never resumed, but `restoreLatest` must then continue to the stored Checkpoint instead of returning. A Lesson suspended by a solve and then killed is still offered.
- **Continue card** (`LearnScreen`):
  - It appears above the Lesson rows when `continueEntry` is non-null, with the tag `learn:continue`.
  - It shows the label "Continue" (`R.string.learn_continue`), the Lesson's Objective and its Stage title. Tapping it calls `onOpen(lessonId)`, so it opens through the same full-screen path as a row.
  - Its style follows the tokens: `surfaceRaised`, `md` shape, no shadow, at least `touchMin` tall, `Role.Button`.
  - `AppShell` derives `continueEntry` from `host.state`: when `kind == LESSON` and a Lesson is present, it is `ready.find(lesson.id)`. Otherwise it is null.
- **Silent resume.** Opening the held Lesson dispatches nothing: `LessonScreen` already skips `LessonStarted` when the host holds that Lesson. There is no dialog. The note shows on the `lesson:restoreNote` line that already exists.

**Never:**
- No Solve-tab entry, no "Starting a solve pauses your lesson." copy, no `SolvePlaybackStarted` and no `endSolve` call from any UI. Those belong to Story 3.7.
- No physical-mode resume or *Confirm your cube* (Epic 8), no Stage rows (Story 7.2), and no Room schema change.
- Never resume `SOLVE_PLAYBACK` on cold start. Never keep a suspended Lesson in `SessionState`: the resume always reads the stored Checkpoint.
- Never hard-code the note in a screen.
- Never change unrelated test expectations. The only expectations that change are those this story redefines:
  - the 3.6 "starting while a Lesson is active … unchanged" test;
  - the 6.2 `restoreNote` null-after-resume assertions;
  - the 6.2 `SessionHostCheckpointTest` "playback" scenario, which now resumes the Lesson.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Cold start, virtual Checkpoint | Checkpoint for Lesson L stored, no snapshot | The Learn tab shows the Continue card for L. Tapping it opens L at the stored cube with "Picked up where you left off.", with no `LessonStarted` | — |
| Cold start, same-content snapshot | Snapshot of L at the current revision | The card shows L. Opening it shows the snapshot's cube and the resumed note | — |
| Content changed | Checkpoint revision differs | The card shows L, and opening it shows the `msg.lesson.updated` note | — |
| Nothing stored | No snapshot and no Checkpoint | No card. The rows are unchanged | Load failure: no card, rows shown |
| Playback snapshot + Checkpoint | Latest snapshot is `SOLVE_PLAYBACK` | Playback is not resumed. L is restored from the Checkpoint, and the card shows | — |
| Solve over a Lesson | Active L with an open attempt, then `SolvePlaybackStarted` (verified) | `SOLVE_PLAYBACK` with `AttemptEnded(ABANDONED)` | Unverified: unchanged |
| Solve ends | Playback that suspended L, then `endSolve(bundle)` | L is at its last stored Checkpoint (Moves after it are dropped), with the resumed note and a new `AttemptStarted` if the Objective is unmet | No Checkpoint, or a failed read: IDLE (a failed read also dispatches `CheckpointLoadFailed`) |
| End outside playback | IDLE or Lesson, then `SolvePlaybackEnded` | Unchanged | — |

</intent-contract>

## Code Map

- `core/application/.../session/Reduce.kt`:
  - `reduce` (l.~29): handle `SolvePlaybackEnded` before the `!starts && kind != LESSON` guard (l.~49).
  - `startPlayback` (l.~154): remove the active-other-kind rejection and update its KDoc.
  - `attempts` (l.~675) returns early when `next.kind != LESSON`. Emit ABANDONED there for a playback start over an open attempt. `end(before, ABANDONED)` is the pattern (l.~703).
  - `restoreCheckpoint` (l.~644) and `restore` (SessionRestored, l.~538-581) set the note.
- `core/application/.../session/{SessionEvent.kt (add the event beside SolvePlaybackStarted l.~59), SessionState.kt (IDLE KDoc l.~230), TRANSITIONS.md (l.~20, row table l.~29-40, playback prose l.~59-67, note prose l.~194-201)}`.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.lesson.resumed`.
- `app/.../session/SessionHost.kt`:
  - `restoreLatest` (l.~97): change the l.~110 `return` on a playback snapshot so it falls through to the checkpoint block, and extract the checkpoint block from l.~115 to the end.
  - `dispatch` regenerates `sessionId` (l.~157). Add an `endSolve` beside `restore`.
- `app/.../ui/shell/LearnScreen.kt` -- add `continueEntry: CatalogEntry? = null` and the card. `LessonRow` is the click and tag pattern.
- `app/.../ui/shell/AppShell.kt` -- `ShellContent` (l.~93): collect `host.state` and pass `continueEntry` to `LearnScreen`. `openLesson = it.value` is the open path.
- `app/.../ui/lesson/LessonScreen.kt:127-158` -- already resumes silently and renders `restoreNote`. This is a read-only reference.
- `app/src/main/res/values/strings.xml:19` -- add `learn_continue`.
- Tests to update or extend:
  - core: `SolvePlaybackSessionTest.kt:31`, `CheckpointTest.kt` (l.~88, 118, 134, 148: null → resumed), `TransitionsTableTest.kt` (l.~123 event builder), `AttemptTest.kt`, `Lessons.kt:143` (helper).
  - app: `app/src/test/.../data/SessionHostCheckpointTest.kt` (l.~174-186: playback and same/kept/corrupt expectations; its Room plus `SerialWriter` setup is the host-test pattern), `ui/shell/AppShellTest.kt` (setup l.~90-110, `openWhiteCross` l.~133), `ui/lesson/LessonScreenTest.kt:211`.

## Tasks & Acceptance

**Execution:**
- `core/application/.../session/{SessionEvent,Reduce,SessionState}.kt`, `TRANSITIONS.md` -- add the `SolvePlaybackEnded` event, the suspend-on-start, ABANDONED on suspend and the note rule -- the reducer owns every state change (AD-3).
- `core/domain/.../strings/en.json` -- add the `msg.lesson.resumed` copy -- all copy comes from content keys.
- `app/.../session/SessionHost.kt` -- add `endSolve`, the shared checkpoint resume and the playback-snapshot fall-through -- so a suspended Lesson resumes from its stored Checkpoint.
- `app/.../ui/shell/{LearnScreen,AppShell}.kt`, `res/values/strings.xml` -- add the Continue card -- one tap to continue (FR-19).
- core tests:
  - suspend over an active Lesson, with and without an open attempt (the ABANDONED effect and its ids);
  - `SolvePlaybackEnded` from playback, IDLE and Lesson;
  - the note on each restore kind and its clearing;
  - the updated transitions table.
- app tests:
  - `SessionHostCheckpointTest`: a new case where a Lesson reaches a Checkpoint, makes extra Moves, `SolvePlaybackStarted` suspends it (ABANDONED row written), and `endSolve` restores the Checkpoint's cube and history with the resumed note. Another case where `endSolve` with no Checkpoint leaves the state idle. Update the playback scenario so the Lesson is restored.
  - `AppShellTest`: with a stored Checkpoint (the host built with a `CheckpointRepository` fake or Room), Learn shows `learn:continue` with the Lesson's Objective. Tapping it opens the Lesson at the stored cube with the "Picked up where you left off." text, and no `LessonStarted` is reduced. With nothing stored, there is no card.

**Acceptance Criteria:**
- Given a stored virtual-mode Checkpoint, when the Learn tab opens, then a Continue card shows its Lesson, and tapping it opens that Lesson at the stored cube with no dialog and the one-line note "Picked up where you left off."
- Given a Lesson suspended by `SolvePlaybackStarted`, when `SessionHost.endSolve` runs, then the Lesson resumes from its stored Checkpoint.
- Given the whole change, when `./gradlew spotlessCheck build` runs, then it passes, with `:core:*` Kover at 80% or more.

## Spec Change Log

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 12 findings — high 0, medium 1, low 5, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) cancellation during endSolve's Checkpoint read leaves the host IDLE with no retry. No production caller exists yet, and the stored Checkpoint still restores on the next cold start. A recoverable-cancel path would add state for an unlikely case.
  - `[false]` `[reject]` (blind) endSolve does not persist IDLE, so a later cold start can restore the Lesson that the process failed to resume. That outcome is the intended behavior: a playback snapshot is skipped and the stored Checkpoint is offered (AD-3, AC 1).
  - `[false]` `[reject]` (blind) no revision guard on endSolve's async read. restoreCheckpoint applies only from IDLE, so an interleaved second resume is rejected once the first applies, and either one restores the same stored Checkpoint.
  - `[false]` `[reject]` (blind) the "playback" scenario corrupts the snapshot. The invalid row is an older LESSON snapshot. The latest row is a valid SOLVE_PLAYBACK snapshot, and the older row only proves that the resume comes from the Checkpoint, not from a Lesson snapshot.
  - `[low]` `[reject]` (blind) the endSolve test does not hold a Checkpoint write in flight. The writes are enqueued and not flushed before endSolve, and the FIFO SerialWriter guarantees the ordering. A blocking fake adds complexity for little gain.
  - `[false]` `[reject]` (blind) the shell test's initial-state Checkpoint cannot tell a restart from a resume. The test asserts that no LessonStarted was reduced and that the "Picked up where you left off." note shows, which a restart would clear.
  - `[low]` `[reject]` (blind) the shell tests omit the snapshot and changed-content paths. The card is derived from host.state, and those restore paths are asserted at the host level (SessionHostCheckpointTest kept/same/revision scenarios).
  - `[medium]` `[defer]` (intent) AC 2 has no production caller; the solve UI is Story 3.7. Deferred in frontmatter.
  - `[false]` `[reject]` (intent) the card follows host.state (D2) instead of the latest stored Checkpoint (D1). Every LessonStarted emits PersistCheckpoint, and cold start restores the newest snapshot or Checkpoint, so the held Lesson is the latest Checkpoint's Lesson.
  - `[low]` `[reject]` (intent) "virtual-mode" is never checked. CubeMode has only VIRTUAL until Epic 8, so a check would be dead code.
  - `[low]` `[reject]` (intent) the content-changed note is not covered in AppShellTest. It is asserted at the host level (the "revision" scenario), and LessonScreen renders any restoreNote.
  - `[false]` `[reject]` (intent) the changed playback-scenario setup tests something different. It still writes a valid playback snapshot as the latest row and now asserts the Checkpoint fallback the spec requires.

## Design Notes

- **Card source.** Cold start already restores the newest snapshot or the stored Checkpoint into `SessionHost`. The fall-through fix closes the only gap, a playback snapshot. So the host's Lesson *is* the latest Checkpoint's Lesson, and a card derived from `host.state` cannot disagree with what opening it shows. A Lesson closed earlier in the same process also shows the card, without the note, because nothing was restored.
- **Suspension lives here, not in 3.7.** Story 6.2 assigned "suspend/resume by a solve" to 6.5. Story 3.7 adds only the Solve-tab entry and the pause copy. The outermost surface that exists today is `SessionHost`, so AC 2 is proven there.
- **Resume from the stored Checkpoint, not from memory.** AD-3 says "a suspended Lesson resumes from its Checkpoint". Reusing `CheckpointRestored` gives one resume path for both the kill case and the in-process case, and a kill during the solve still lands correctly.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

**Summary**
- Learn shows a Continue card for the Lesson that SessionHost holds after the cold-start restore.
- Every applied resume carries the note "Picked up where you left off." (`msg.lesson.resumed`). A content-changed restart keeps the updated note.
- `SolvePlaybackStarted` now suspends an active Lesson and records the open attempt as ABANDONED.
- The new `SolvePlaybackEnded` event, run through `SessionHost.endSolve(bundle)`, returns the session to idle and resumes the stored Checkpoint through the shared resume path.
- A playback snapshot at cold start now falls through to the stored Checkpoint.

**Files changed**
- `core/application/.../session/Reduce.kt`: the suspend, the end event, the note rule and ABANDONED on suspend.
- `SessionEvent.kt`: adds `SolvePlaybackEnded`.
- `SessionState.kt`: updates the IDLE KDoc.
- `TRANSITIONS.md`: updates the rows and the prose.
- `core/domain/.../strings/en.json`: adds `msg.lesson.resumed`.
- `app/.../session/SessionHost.kt`: adds `endSolve` and the shared `resumeCheckpoint`, and makes a playback snapshot fall through.
- `app/.../ui/shell/LearnScreen.kt`: adds the Continue card.
- `app/.../ui/shell/AppShell.kt`: derives `continueEntry` from `host.state`.
- `res/values/strings.xml`: adds `learn_continue`.
- Tests: `SolvePlaybackSessionTest`, `CheckpointTest`, `TransitionsTableTest`, `SessionHostCheckpointTest` and `AppShellTest`.

**Review**
- 12 findings: 0 patches, 1 deferred (AC 2 has no production caller until Story 3.7).
- 11 were rejected; the triage log above gives each reason.
- Follow-up review recommended: false (no patches).

**Verification**
- Codex implementation run, then `./gradlew spotlessCheck build`: BUILD SUCCESSFUL. It also passed on my rerun (exit 0), including JVM tests, Kover, lint and both APKs.

**Residual risks**
- In the running app, solve suspension and resume are reachable only after Story 3.7 calls `endSolve`.
- Tests from earlier stories were redefined, as the spec allows: the 3.6 test for a solve started over an active Lesson, and the 6.2 note assertions and playback scenario.

