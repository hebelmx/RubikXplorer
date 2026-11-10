---
title: '2-look completion and mastery'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: 'a37da79b2ec846ba2d35a7471e0834d92779ea21'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      A confirmed physical-mode solve that masters a Course Stage plays no Stage mastery milestone.
    evidence: |-
      LessonScreen still gates isMasteryMilestone on !isPhysical. UX-DR20 allows the milestone in Physical-cube Mode after an explicit "Yes, it's solved"; the 2-look milestone now follows it (physicalSolveConfirmed), the Stage mastery milestone is out of this story's scope.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt (isMasteryMilestone)
    severity: low
  - summary: >-
      Rotating the device during a Course Stage practice regenerates the scramble and restarts the attempt, and Course practice does not resume on cold start.
    evidence: |-
      AppShell builds the Course practiceEntry in remember(openPractice, practiceEpoch, ready) with a fresh scramble, and SessionHost resolves only bundled Lessons and the LL practice on restore. The LL practice now keeps its scramble in rememberSaveable and is rebuilt from the snapshot's start cube on cold start.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt (practiceEntry)
    severity: medium
  - summary: >-
      Practice Lessons have no demonstration, so later Hint rungs cannot show a solution.
    evidence: |-
      TwoLookPractice and StagePractice both build the Lesson with demonstration = "" and one generic Step, while the practice UI still exposes Hint controls. This is the shared practice hint design, not new to the LL practice.
    location: >-
      core/application/src/main/kotlin/dev/cubentia/application/usecase/TwoLookPractice.kt, StagePractice.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** The 2-look mini-Course has no overall state: the *Faster last layer* heading never reads *Completed* or *Mastered*, there is no LL practice on fresh Scrambles, and nothing records whether a solve used only 2-look Algorithms without hints (FR-17, FR-20, UX Flow 2).

**Approach:** Derive the mini-Course state purely from the current epoch: *Completed* when every 2-look Lesson has a `LessonCompleted`, *Mastered* when two LL practice attempts are completed with no hints and every Move belongs to a 2-look Algorithm or an adjustment. Add an LL practice on a fresh F2L-solved Scramble whose attempt `errors` count Moves outside 2-look Algorithms. Show the state on the *Faster last layer* row, show "Mastery: 1 of 2" after the first qualifying solve, and play the milestone when mastery is reached.

## Boundaries & Constraints

**Always:** The 2-look Algorithm set is `ContentBundle.lessonAlgorithms` over `twoLookStages` (the 16 from 9.7), never a hard-coded list. The 2-look-only check is one pure `:core:domain` function: the fewest Moves of the committed history (`MoveHistory.done`, so undone Moves never count) that are not covered by a split into segments, where each segment is either one free Move (a `U` face turn, any amount, or a Cube Rotation) or a contiguous run whose cubie effect equals one 2-look Algorithm's effect, ignoring the final orientation. That count is the LL practice attempt's `errors`, set by the reducer in `AttemptEnded`. A qualifying solve is an attempt with `objectiveId == "practice-two-look"`, outcome `COMPLETED`, `hints == 0` and `errors == 0`, in the current epoch, so *Reset progress* clears it. The number of solves required is `tutor-config.json` `twoLookSolvesRequired` = 2. Mini-Course status order: *Mastered* (≥ 2 qualifying) over *Completed* (every 2-look Lesson completed) over *In progress*. The LL practice is a `PRACTICE` session: entry state with yellow on U (`LastLayerView.canonicalFrame(YELLOW)`), F2L solved, LL not solved up to AUF, made by applying random Library Algorithms and `U` turns through `RandomSource`; completion `cube-solved`; preserve the first two layers. Learner text is in `en.json` or Android string resources and follows the voice guide.

**Never:** No new Room column, table or `progress_event` type; no `SessionSnapshot` or `Checkpoint` format change (the LL practice, like Course practice, is not restored on cold start). No change to Course Stage mastery, the seven Stage rows, the 2-look Stage rows or 9.7's Lesson offer. No Stage mastery milestone, Trend or Course mastery line for the LL practice. The milestone does not play in Physical-cube Mode (same rule as Stage mastery). No stars, scores or badges. No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Not started | 2-look rows unlocked, no 2-look Lesson done | *Faster last layer* row shows *In progress*; no mastery line | No error expected |
| Completed | Every 2-look Lesson has `LessonCompleted` in the epoch | Row shows *Completed* | No error expected |
| One qualifying solve | One qualifying attempt | Row reads "Mastery: 1 of 2"; status unchanged | No error expected |
| Mastered | Two qualifying attempts (Lessons done or not) | Row shows ◆ *Mastered*; no "1 of 2" line | No error expected |
| Hint used | LL solved with ≥ 1 hint | Not qualifying | No error expected |
| Off-2-look Move | LL solved, history has a Move outside any 2-look Algorithm or adjustment (e.g. `D`, or a full-OLL Algorithm not in the set) | `errors` ≥ 1; not qualifying | No error expected |
| Split half turn | Algorithm entered with `U U` instead of `U2`, or `r` as `L x` | Still covered; `errors` 0 | No error expected |
| Undone slip | Wrong Move then Undo | Not in `done`; `errors` 0 | No error expected |
| Rotations and AUF | `y`, `U'`, Algorithm, `U2` | `errors` 0 | No error expected |
| Reset | *Reset progress* after mastery | Row back to *In progress*, no line | No error expected |
| Milestone | Second qualifying solve in the virtual LL practice | Milestone burst then "2-look last layer mastered!"; static under reduce motion; *Practice on a new scramble* opens a fresh LL practice | No error expected |
| Not mastered yet | First qualifying solve | "Last layer solved with 2-look, no hints." under the step card; no milestone | No error expected |
| Non-qualifying solve | Solved with hints or off-2-look Moves | "Solved. Only 2-look algorithms with no hints count toward mastery." | No error expected |
| Course practice | Any Stage practice | `errors` stays the mistake count; no 2-look line | No error expected |

</intent-contract>

## Code Map

- `core/domain/.../tutor/Mastery.kt`, `MasteryAttempt.kt` -- `currentEpochAttempts` (epoch filter to reuse); `MasteryAttempt` has `hints` but no `errors`; `StageAttempt` test record.
- `core/domain/.../tutor/StageProgress.kt` -- `currentEpochEvents`; `StageStatus` (reuse IN_PROGRESS/COMPLETED/MASTERED for the row).
- `core/domain/.../lastlayer/LastLayerView.kt` `canonicalFrame(color)`; `LastLayerMatcher.kt:126` `caseState` (frame precedent); `LastLayerStep.kt`; `predicate/LastLayerStepReached.kt` (`SOLVED_UP_TO_AUF` for the redraw check).
- `core/domain/.../cube/CubeState.kt` -- `cubies` (compare effects), `apply`; `Move.kt` -- `FaceTurn`, `CubeRotation`; wide and slice Moves expand to face turns plus a rotation.
- `core/domain/.../content/Model.kt:79` `TutorConfig`; `resources/content/tutor-config.json`, `schema/tutor-config.schema.json`; `ContentValidator.kt` (threshold ranges); `SchemaModelTest` (`required` = non-default fields).
- `core/application/.../usecase/StagePractice.kt` -- practice Lesson shape, `RandomSource` draws, redraw loop; mirror it.
- `core/application/.../session/SessionEvent.kt:59` `PracticeStarted`; `Reduce.kt:288` `startPractice`; `Reduce.kt:1104-1147` `attempts`/`end` (where `errors` is set); `SessionState.kt` fields and `IDLE`; `AttemptRecord.kt` (has `errors`).
- `app/.../ui/shell/AppShell.kt` -- `openPractice`/`practiceEntry` pattern, `allStagesFinished`, `LearnScreen` call; `LearnScreen.kt:254-266` `StageListContent` 2-look block and `FasterLastLayerRow` (tag `learn:fasterLastLayer`), `StageStatusBadge`.
- `app/.../ui/lesson/LessonScreen.kt:167-370` -- `isMasteryMilestone`, `initialMastered`, `practicing` start branch, `FirstSolveMilestone` usage; `stepCard` practice branch at 882.
- `app/src/main/res/values/strings.xml:176-185` -- Learn and milestone strings.
- Tests to extend: `AppShellTest`, `LessonScreenTest`, `ReduceTest`/practice tests in `:core:application`, `ContentLoaderTest`, `SchemaModelTest`, `ShippedContentTest`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../tutor/MasteryAttempt.kt` -- add `val errors: Int? get() = null` to `MasteryAttempt`, `errors` param on `StageAttempt`; `AttemptRecord.errors` becomes `override`.
- `core/domain/.../lastlayer/TwoLookSolve.kt` -- `object TwoLookSolve { fun offPathMoves(moves: List<Move>, algorithms: List<List<Move>>): Int }` per the Always rule (DP over prefixes, segment length bounded by twice the longest Algorithm).
- `core/domain/.../tutor/TwoLookMastery.kt` -- `PRACTICE_ID = LessonId("practice-two-look")`; `qualifies(attempt)`; `evaluate(twoLookStages, attempts, events, solvesRequired): TwoLookProgress(completed, qualifyingSolves, solvesRequired, mastered, status)`.
- `core/domain/.../content/Model.kt`, `ContentValidator.kt`, `tutor-config.json`, `schema/tutor-config.schema.json` -- `twoLookSolvesRequired: Int = 2`, value 2 in JSON, range 1..10.
- `core/domain/src/main/resources/content/strings/en.json` -- `stage.two-look-practice.title` ("Faster last layer"), `msg.practice.two-look.objective` ("Solve the last layer using only 2-look algorithms."), `msg.milestone.two-look-mastered` ("2-look last layer mastered!").
- `core/application/.../usecase/TwoLookPractice.kt` -- `TwoLookPractice(random)`, `lesson(scrambleAlgorithms: List<List<Move>>): Lesson` with id `PRACTICE_ID`, stage id `two-look-practice`, entry/completion/preserve per Always.
- `core/application/.../session/SessionEvent.kt`, `SessionState.kt`, `Reduce.kt`, `TRANSITIONS.md` -- `PracticeStarted.twoLookAlgorithms: List<List<Move>> = emptyList()` kept in `SessionState.twoLookAlgorithms` (cleared by every other start); `end()` sets `errors = TwoLookSolve.offPathMoves(done, algorithms)` when non-empty.
- `app/.../ui/shell/LearnScreen.kt` -- `FasterLastLayerRow` shows `StageStatusBadge` (tag `learn:fasterLastLayer:status`), "Mastery: 1 of 2" (tag `learn:fasterLastLayer:mastery`, string `learn_two_look_mastery`, shown only when 0 < qualifying < required), and *Practice the last layer* (tag `learn:twoLookPractice`).
- `app/.../ui/shell/AppShell.kt` -- compute `TwoLookMastery.evaluate`; LL practice entry state (`openTwoLookPractice` + epoch) built with `TwoLookPractice` from all Library Algorithms and the 2-look set; open `LessonScreen` with `isPractice = true`, `twoLookPractice = true`.
- `app/.../ui/lesson/LessonScreen.kt` -- `twoLookPractice` param: dispatch `PracticeStarted` with the 2-look Move lists; no Stage mastery milestone; 2-look milestone when `initialTwoLookMastered == false` and now mastered, virtual only; result line (tag `lesson:twoLookResult`) per the matrix.
- `app/src/main/res/values/strings.xml` -- `learn_two_look_mastery`, `learn_two_look_practice`, `lesson_two_look_qualified`, `lesson_two_look_not_qualified`.
- Tests: `TwoLookSolveTest` (each matrix Move case, every 2-look Algorithm alone, the dot as 45 + `U` + 44), `TwoLookMasteryTest` (status order, hint/errors/outcome/objective filters, epoch reset), `TwoLookPracticeTest` (F2L solved, LL unsolved, yellow on U, seeded), reducer test that LL practice `AttemptEnded.errors` is the off-path count and Course practice is unchanged, `ContentLoaderTest`/`SchemaModelTest` for the new field, `LearnScreen`/`AppShell` tests for the row states, line and practice entry, `LessonScreen` test for the milestone and result lines.

**Acceptance Criteria:**
- Given every 2-look Lesson predicate met, when progress is read, then the mini-Course row shows *Completed*.
- Given LL practice on fresh Scrambles, when the Learner twice solves the LL using only 2-look Algorithms with no hints, then the mini-Course becomes *Mastered* and the milestone plays; after the first such solve the row reads "Mastery: 1 of 2".

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 23 findings — high 0, medium 5, low 11, false 7, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: rotations plus free `U` turns can reproduce any face turn, so any solve could earn zero errors — `TwoLookSolve` now counts a `U` turn as free, and lets an Algorithm segment start, only while the starting top layer is on U; tests cover `x' U x` and an Algorithm run from another side.
  - `[medium]` `[patch]` Blind: a mode switch replaces the history, so off-path Moves made before it disappear — `TwoLookSolve.offPathMoves(history, entry, …)` adds one error when the history no longer starts at the Lesson entry; `SessionState.twoLookErrors` uses it, and the reducer and the result line both read that one property. A reducer test switches mode mid-attempt.
  - `[medium]` `[patch]` Blind: the Manual Cube Editor can replace the history with a solved cube and qualify with no Moves — same root cause and fix as the row above.
  - `[low]` `[patch]` Blind: the 2-look milestone ignores `physicalSolveUnconfirmed`, so an unconfirmed physical solve followed by a switch to virtual shows it — added `!session.physicalSolveUnconfirmed`.
  - `[false]` `[reject]` Blind: a `practice-two-look` attempt started without Algorithms would qualify through zero step mistakes — the only caller always passes the content-derived set (non-empty when content is Ready), and practice sessions are never restored, so the state cannot occur.
  - `[low]` `[reject]` Blind: the segment-length bound can reject an Algorithm entered with many cancelling turns — the Learner then gets a non-qualifying solve, which is the strict reading; normalizing Moves adds a pass for input nobody needs to enter.
  - `[low]` `[patch]` Blind: the result line re-runs the classifier on every recomposition — the value is now `remember`ed on the history and Algorithm set.
  - `[low]` `[reject]` Blind: no test solves two generated scrambles end to end — each link is tested (generator, reducer `errors`, `TwoLookMastery`, the Learn row, the milestone and its practice action); solving a random scramble in a test needs a solver this module does not have.
  - `[medium]` `[patch]` Edge: rotations before a free `U` bypass qualification — same root cause and fix as the first Blind row.
  - `[medium]` `[patch]` Edge: mode switching or `EditorEntered` erases evidence or qualifies an edited solved state — same root cause and fix as the second Blind row.
  - `[low]` `[patch]` Edge: an unconfirmed physical solve then a switch to virtual shows the milestone — same fix as the `physicalSolveUnconfirmed` row.
  - `[low]` `[patch]` Verification gap: the practice-entry test checked only the Algorithm count — it now asserts the session's set equals the parsed Algorithms referenced by `twoLookStages`.
  - `[low]` `[patch]` Verification gap: the configurable threshold was tested only at 2 — `ContentLoaderTest` loads 3, and `TwoLookMasteryTest` shows two solves unmastered and three mastered at 3.
  - `[low]` `[reject]` Intent: *Completed* is tested with hand-made `LessonCompleted` events, not real Lesson runs — the completion path is 9.7's unchanged `SessionHost`/`CompleteLesson` code with its own tests; the row reads those events.
  - `[low]` `[reject]` Intent: the AC2 chain is never run end to end — same reasoning as the Blind end-to-end row.
  - `[false]` `[reject]` Intent: matching by cube effect is a policy the AC does not state — the spec chose it to accept any spelling of an Algorithm; no wrong outcome is shown.
  - `[low]` `[reject]` Intent: only one full-set Algorithm is shown to be rejected — rejection follows from effect comparison against the 16 effects; a test per Algorithm adds little.
  - `[false]` `[reject]` Intent: scrambles use all Library Algorithms while qualification uses the 2-look set — intended, so every LL Case can appear.
  - `[false]` `[reject]` Intent: nothing checks that the two solves used different scrambles — every practice opens a new random scramble (`practiceEpoch`), so the "fresh" requirement holds.
  - `[false]` `[reject]` Intent: *Mastered* can be reached without *Completed* — FR-17 defines the two separately, and the spec orders them.
  - `[low]` `[defer]` Intent: a physical solve can master 2-look without a milestone — it shares the pre-existing Stage mastery rule (`!isPhysical`); deferred so both milestones change together.
  - `[false]` `[reject]` Intent: "Mastery: 1 of 2" is configurable and not checked alongside *In progress* — the AC text appears at the shipped value, and the line depends only on the count, which `TwoLookMasteryTest` covers.
  - `[false]` `[reject]` Intent: `errors` takes a second meaning, and old records with null `errors` never qualify — the meaning is documented in TRANSITIONS.md and the spec, and no `practice-two-look` records exist before this story.

### 2026-11-09 — Review pass (follow-up)
- verdicts: 21 findings — high 0, medium 1, low 13, false 5, maybe-false 2
- findings:
  - `[low]` `[reject]` Blind: the segment-length bound rejects long equivalent spellings — carried: the Learner then gets a non-qualifying solve, which is the strict reading; normalizing Moves adds a pass for input nobody needs to enter.
  - `[false]` `[reject]` Blind: replacing the history with the original entry passes the `history.start == entry` check — if the cube is back at the entry state, the whole solve from that state is still in `done`, so no evidence is lost and nothing qualifies unearned.
  - `[medium]` `[defer]` Blind: practice entries live in `remember`, so activity recreation regenerates the scramble and restarts the attempt — real (no `configChanges` in the manifest), but the LL practice copies the existing Course practice pattern; deferred so both change together.
  - `[low]` `[reject]` Blind: a repeated random scramble would leave the completed session active — 20 random Algorithm + `U` draws repeating the previous state is negligible in use, and fixing it needs new session-identity plumbing.
  - `[low]` `[defer]` Blind: the generated Lesson has no demonstration, so later Hint rungs cannot show a solution — same as `StagePractice` (`demonstration = ""`); pre-existing practice hint design, deferred.
  - `[maybe-false]` `[reject]` Blind: the result line reads live session history rather than the recorded attempt — a Move after the solve clears `objectiveMet` and hides the line, and a re-solve ends a new attempt from the same `twoLookErrors`; settling a mismatch needs a flow that changes history while staying solved. If true it is only low.
  - `[low]` `[patch]` Blind: the result line has no live-region semantics — added `semantics { liveRegion = LiveRegionMode.Polite }` to `lesson:twoLookResult`, matching `lesson:feedback`.
  - `[false]` `[reject]` Blind: `TwoLookPractice` can throw during composition — `require` needs an empty Library, which Ready content never has, and 100 consecutive solved-up-to-AUF draws do not happen; these are loud failures on unreachable states.
  - `[low]` `[patch]` Blind: TRANSITIONS.md describes `errors` without the replaced-history +1 — the note now names `SessionState.twoLookErrors` and the +1 rule.
  - `[low]` `[reject]` Edge: rotations inside an Algorithm push its spelling past the segment bound — carried, same as the first Blind row.
  - `[low]` `[patch]` Edge: the redraw `check` ran before the solved test, so an acceptable hundredth draw still threw — the loop now breaks on an unsolved draw before counting the redraw.
  - `[low]` `[patch]` Verification gap: re-entering the LL practice was not verified through AppShell — the AppShell practice test now commits a Move, closes, reopens through the same `openPractice` + `practiceEpoch++` path the milestone action uses, and asserts a fresh entry, empty history, PRACTICE kind and the same 2-look set.
  - `[false]` `[reject]` Intent: free `U` turns and runs are refused while the start layer is off U, stricter than the contract's literal wording — the matrix cases still give 0, and the guard closes a loophole where rotations plus `U` reproduce any face turn; no wrong outcome.
  - `[low]` `[patch]` Intent: the replaced-history +1 is not documented — same root cause and fix as the TRANSITIONS.md row.
  - `[low]` `[reject]` Intent: `LessonScreenTest` result-line tests do not use the content-derived set — `AppShellTest` checks the real set reaches the session; the result line only reads `twoLookErrors`.
  - `[low]` `[reject]` Intent: split half turns are tested only at the domain level — the reducer calls the same function; a UI test would add nothing the domain test does not show.
  - `[low]` `[reject]` Intent: Mastered-before-Completed is tested only in the domain — the row renders `TwoLookMastery.evaluate`'s status directly.
  - `[maybe-false]` `[reject]` Intent: the result line comes from live state, the milestone from persisted records — same as the Blind result-line row.
  - `[low]` `[reject]` Intent: reduce motion is not exercised by a new test — the milestone reuses the shared overlay covered by `FirstSolveMilestoneTest` and `StageMasteryMilestoneTest`.
  - `[false]` `[reject]` Intent: the always-visible *Practice the last layer* entry is unspecified — the spec's tasks name it; no wrong outcome.
  - `[false]` `[reject]` Intent: the hard F2L `check` relies on shipped content — the Library holds only OLL and PLL Algorithms (`algorithms/oll.json`, `pll.json`), which preserve F2L by definition.

## Design Notes

**Why `errors`.** AD-10 fixes the attempt row to `AttemptEnded(objectiveId, outcome, errors, hints, assistanceLevel)` and Mastery to a pure function of it. For the LL practice the Objective is "only 2-look Algorithms", so a Move outside them is that attempt's error. This needs no schema change and keeps the reducer the only attempt writer.

**Why compare effects.** Matching Notation tokens would reject `U U` for `U2`, `L x` for `r`, or `R L' x'` for `M`. Comparing each segment's `cubies` after applying it to `SOLVED` accepts any spelling of the same Algorithm, and free rotations cover Algorithms run from another side.

```
y U' | F R U R' U' F' | U | F U R U' R' F' | R U R' U R U2 R' | U2 ... -> 0
D | ...                                                         -> ≥ 1
```

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, new domain, application and app tests run, no Room schema JSON change.

## Auto Run Result

**Summary.** The *Faster last layer* heading on Learn is now the mini-Course row:
- **Status.** It shows *In progress*, *Completed* once every 2-look Lesson is completed in the current epoch, or ◆ *Mastered* after two qualifying LL practice solves.
- **Progress line.** "Mastery: 1 of 2" appears after the first qualifying solve.
- **Practice entry.** *Practice the last layer* opens a `PRACTICE` session on a fresh scramble: yellow on U, F2L solved, and the LL not solved up to AUF. The scramble is built from random Library Algorithms and `U` turns.

How a solve is judged:
- The reducer sets that attempt's `errors` to the number of Moves outside 2-look Algorithms and adjustments.
- `TwoLookSolve` compares cube effects, so `U U` for `U2`, `L x` for `r` and other spellings still count.
- `U` turns and Algorithms count only while the starting top layer is on U. A history replaced by a mode switch or the editor adds one error.
- A solve qualifies when it is completed with no hints and no errors.

After each solve, a result line says whether it counted. The second qualifying solve plays the milestone, "2-look last layer mastered!", in the virtual cube. The required count is `tutor-config.json` `twoLookSolvesRequired` (2). There is no Room, snapshot or Checkpoint change.

**Files changed.**
- `core/domain/.../lastlayer/TwoLookSolve.kt` -- the 2-look-only Move judge (effect segments, orientation guard, replaced-history overload).
- `core/domain/.../tutor/TwoLookMastery.kt`, `MasteryAttempt.kt` -- mini-Course status, qualifying rule, `errors` on `MasteryAttempt`.
- `core/domain/.../content/Model.kt`, `ContentValidator.kt`, `tutor-config.json`, `tutor-config.schema.json`, `strings/en.json` -- `twoLookSolvesRequired` and the new Learner strings.
- `core/domain/.../cube/CubeState.kt`, `lastlayer/LastLayerView.kt` -- `reoriented` helper; `canonicalFrame` made public for the practice generator.
- `core/application/.../usecase/TwoLookPractice.kt` -- fresh LL practice Lesson.
- `core/application/.../session/SessionEvent.kt`, `SessionState.kt`, `Reduce.kt`, `AttemptRecord.kt`, `TRANSITIONS.md` -- `PracticeStarted.twoLookAlgorithms`, `SessionState.twoLookErrors`, `errors` in `AttemptEnded`.
- `app/.../ui/shell/LearnScreen.kt`, `AppShell.kt` -- the mini-Course row and the LL practice entry with the content-derived 2-look set.
- `app/.../ui/lesson/LessonScreen.kt`, `LessonPanel.kt`, `res/values/strings.xml` -- the result line and the 2-look mastery milestone.
- Tests:
  - new: `TwoLookSolveTest`, `TwoLookMasteryTest`, `TwoLookPracticeTest`, `TwoLookPracticeSessionTest`;
  - additions to `ContentLoaderTest`, `SchemaModelTest`, `ShippedContentTest`, `LessonScreenTest` and `AppShellTest`.

**Review.** 23 findings: 5 entries patched (2 medium, 4 low), 1 deferred (low), 13 rejected (6 low, 7 false).
- Patched:
  - the rotation loophole, by checking the top layer;
  - lost evidence after a history replacement (mode switch or editor), which now adds one error;
  - the milestone after an unconfirmed physical solve;
  - the result line recomputing on every recomposition, now `remember`ed;
  - the test of the practice Algorithm set, which now checks membership;
  - the non-default threshold, which is now tested.
- Deferred: a confirmed physical-mode mastery plays no milestone. This shares the existing Stage mastery rule.
- Every rejected row and its reason is in the Review Triage Log.

**Follow-up review: recommended.** Patched by verdict: high 0, medium 2, low 4 entries. Two fixes changed the qualification rule after review and have had no independent review:
- the orientation guard in `TwoLookSolve`;
- the replaced-history error.

**Verification.**
- After the patches, `./gradlew spotlessCheck build` was BUILD SUCCESSFUL. The JUnit XML reports show 1627 tests, 0 failed, 0 skipped.
- Every row of the I/O matrix has a covering test that ran. The milestone's reduce-motion frame is covered by the shared `FirstSolveMilestoneTest` and `StageMasteryMilestoneTest`.
- No Room schema JSON changed.
- There were no device or instrumented runs.

**Residual risks.**
- A Learner who corrects a slip by hand instead of with Undo gets a non-qualifying solve. This is the strict reading, and the result line explains it.
- Physical-mode LL practice can qualify from entered Moves; its attempt completes, and its milestone plays, only after an explicit *Yes, it's solved*.
- A cold-start restore of a physical LL practice keeps its committed Moves as 2-look evidence, so Undo after the restore can reach Moves entered before it.
- The intent reading was chosen here, not taken from planning, and the Design Notes record it:
  - "only 2-look Algorithms" is checked from the Learner's Moves;
  - the result is stored in the attempt's `errors`.
- The implementation ran on Codex. The review patches were applied in this run.

### Follow-up review pass (2026-11-09)

**Review.** 21 findings from four layers (three on Codex, intent alignment on Claude): 4 entries patched (all low), 2 deferred (1 medium, 1 low), 15 rejected (8 low, 5 false, 2 maybe-false). Every row is in the Review Triage Log.
- Patched:
  - `TwoLookPractice.kt` -- the redraw loop no longer throws on an acceptable hundredth draw.
  - `LessonPanel.kt` -- the 2-look result line is a polite live region.
  - `TRANSITIONS.md` -- documents the replaced-history error in `AttemptEnded.errors`.
  - `AppShellTest.kt` -- re-entering LL practice starts a fresh attempt with the same 2-look set.
- Deferred: device rotation regenerates a Course practice scramble (the LL practice keeps its scramble and resumes on cold start); practice Hints have no demonstration (shared practice design).

**Follow-up review: not recommended.** Patched by verdict: high 0, medium 0, low 4. This follow-up pass patched no high finding, so the work has converged.

**Verification.** `./gradlew spotlessCheck build` was BUILD SUCCESSFUL after the patches; JUnit XML shows 1627 tests, 0 failed, 0 skipped. No Room schema JSON changed. No device or instrumented runs.

**Residual risks.** The two deferred items above; otherwise unchanged from the first pass.
