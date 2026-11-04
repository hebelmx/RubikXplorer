---
title: 'Climb the Hint Ladder'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: 76de942c6882c7911e828ad463bcbba2a4d23567
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

**Problem:** The Hint button is a one-shot "show the Moves" switch (`RevealRequested`): there is no conceptual clue first, no rung count on the button, and the session does not count hint use per attempt (FR-11, FR-5, UX-DR9).

**Approach:** Add a pure `HintLadder` in `:core:domain` `tutor/` with rungs `NONE → CLUE → TARGET → DEMONSTRATION`, a derived keyed clue, and a climb rule. A new `SessionEvent.HintRequested` climbs exactly one rung and counts the hint in `SessionState`; the rung is per-step tutor state that resets with the derived step and on a new Objective. The Lesson panel renders the rung: the button reads **Hint (n/3)**, rung 1 shows the clue, rung 2 reveals the Move chips and outlines the target in violet, and rung 3 plays the current step's Demonstration on the net without changing the session.

## Boundaries & Constraints

**Always:**
- Each applied `HintRequested` raises `hintRung` by exactly one and `hintsUsed` by exactly one. When the climb is not allowed (`HintLadder.canClimb` false: *Show me*, rung already `DEMONSTRATION`, or the decision says `advance`), nothing changes.
- `hintRung` resets to `NONE` everywhere `revealRequested` resets today: `LessonStarted` (the new Objective), `SolvePlaybackStarted`, an applied `Reset`/`ResetConfirmed`, `StepAdvanced`, and any history change that changes the shown step (AD-6 per-step UI state). `TeachingModeChanged` keeps it, as it keeps the reveal.
- `hintsUsed` counts per attempt: it starts at 0 on `LessonStarted` (and `SolvePlaybackStarted`) and nothing else lowers it — not a step change, Reset, Undo or mode change. Persistence is Story 6.3.
- Neither field advances `revision` (tutor fields). `HintRequested`, like every non-`MoveCommitted` event, clears `lastMoveFeedback` and `lastDiagnosis` (AD-5).
- Reveal stays a decision: `Assess.decide` gets `hintRung` and `reveal = SHOW_ME || revealRequested || hintRung >= TARGET`. `TutorDecision` keeps exactly its AD-5 fields.
- No rung ever shows more than the current step's Moves. The full Solution (the whole Lesson's Demonstration) is never shown by a hint; at rung `NONE` and `CLUE` the Moves stay hidden in *Test me* and *Let me try*.
- The rung-3 Demonstration is a projection only: it dispatches nothing, the session state and undo history never change, the net ends on the session's current state, any session interaction stops it, and under reduce motion it steps with `Snap` instead of animating.
- All new Learner-facing copy is `Text(key, args)` in `content/strings/en.json`; the clue is derived from predicates, never a new Lesson field (the AD-6 Lesson shape is fixed). Hint UI lives in a new component file, not in `LessonScreen.kt`'s body.

**Never:** a Move-arrow overlay in the renderer (rung 2 uses the "highlighted target" branch of FR-11); adaptive rung skipping or `ADAPTIVE_ASSISTANCE` behaviour (4.6); `AttemptStarted`/`AttemptEnded` effects or persistence (6.3); recovery prompt buttons (4.4); changing Guide me's "Show the Move" (`RevealRequested` stays and is not a hint); a new content schema field; a hint control in *Show me*; editing planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Climb | `lesson-02-05` started in *Test me*, `HintRequested` ×3 | rungs CLUE, TARGET, DEMONSTRATION; `hintsUsed` 1, 2, 3; `decision.reveal` false, true, true; `revision` unchanged | — |
| Past the top | then a 4th `HintRequested` | state unchanged (rung DEMONSTRATION, `hintsUsed` 3) | ignored |
| Show me | *Show me*, `HintRequested` | unchanged | ignored |
| Notice pending | step done, Notice shown (`advance`) | `HintRequested` unchanged | ignored |
| Step changes | rung TARGET on `lesson-02-05` step 1, commit `F2` (Notice shown, rung kept), then `StepAdvanced`; separately, Undo back across a step boundary | after `StepAdvanced` / after the Undo that changes the shown step: rung NONE, reveal false, `hintsUsed` kept | — |
| New Objective | `LessonStarted` (any Lesson) after hints | rung NONE, `hintsUsed` 0 | — |
| Mode switch | rung CLUE, *Test me* → *Let me try* | rung CLUE kept | — |
| Clue, one target piece | `lesson-02-05` step 1 | `msg.hint.clue.piece {piece: white_green}` → "Find the white-green edge. Where does it belong, and which face can carry it there?" | — |
| Clue, no single named target | `lesson-01-01` step 1 | `msg.hint.clue.goal` → generic clue | — |
| Rung 3 on screen | Lesson screen, third tap | button "Hint (3/3)" disabled; the net plays only the shown step's Moves from that step's authored start, then shows the session's state again; session revision and history unchanged | interrupted by any session event: net snaps to the session state |

</intent-contract>

## Code Map

- `core/domain/.../tutor/` -- new `HintLadder.kt` (the spine lists `tutor/HintLadder`). `Tutor.kt` has `currentStep`/`shownStep`/`stepDone`; `Assess.decide` (Assess.kt ~:150-190) builds `reveal = mode == SHOW_ME || revealRequested`; `TutorDecision.kt` field set is fixed.
- `core/domain/.../playback/Demonstration.kt` -- `Demonstration.of(lesson)` → `DemonstrationResult.Ready`; `steps[i].moves`, `firstMove`, `focus.target` (pieces the step newly brings into `completion`'s preserved set) and `start`. Reuse `focus.target` for the clue and the rung-2 highlight; the step's authored start is `start.apply(moves.take(firstMove))`.
- `core/domain/.../predicate/WhitePieceSolved.kt` -- `NAMES`, `pieceNamed(name)`, `nameOf(piece)`; `tutor/DiagnosisRules.kt` has the private `selectId` (canonical name → `white_green` select id) used by `msg.diagnosis.wrong-piece`; share it (make it `internal`) rather than duplicate.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.diagnosis.wrong-piece` already has the 8-piece ICU select to copy. `ShippedContentTest` checks `Text("…", mapOf(...))` argument names against en.json, so pass args as literal `mapOf` entries.
- `core/application/.../session/SessionEvent.kt` -- `RevealRequested` (~:79); add `HintRequested(at)` beside it.
- `core/application/.../session/Reduce.kt` -- dispatch `when` :41-53; `revealRequested = false` at `start` :69, `startPlayback` :97, `withNotice` :160, `reset` :171, `advance` :225. Every one of these also sets `hintRung = HintRung.NONE`; `start`/`startPlayback` also `hintsUsed = 0`. The generic feedback clear is at :30-34.
- `core/application/.../session/SessionState.kt` -- tutor fields and KDoc :26-29, constructor :61-63, `decision` lazy :65-78 (pass `hintRung`), `IDLE` :115-125.
- `core/application/.../session/TRANSITIONS.md` -- tutor field list :12, table rows :26-38, tutor prose :54-79. `TransitionsTableTest` requires a row per `SessionEvent` type and compares a `tutor(...)` tuple (:161) and per-event fixtures (:80-150).
- `core/application/src/test/.../session/{Lessons.kt,TutorEventsTest,TeachingModesScenarioTest,StepDerivationScenarioTest,ReducerPurityTest}.kt` -- `Lessons.kt:141` defines `reveal`; add `hint`. Look for exhaustive event lists that must include the new type.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- `LessonPanel(... hintEnabled = !decision.reveal, onHint = { send(SessionEvent::RevealRequested) })` at :216-218; stage emphasis :236-245 (Guide me highlights `focus.target`; others only pulse); `demonstrateReorientation` :423-446 and the `reorientation` job/`stopReorientation` :190-200 are the pattern for the rung-3 playback (generalize to "play these Moves from this state, then snap back").
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt` -- the Hint `OutlinedButton` :109-116 (tag `lesson:hint`, label `R.string.lesson_hint`); `StepCardContent` :57-73; `StepCard` :137-207.
- `app/src/main/res/values/strings.xml:29` -- `lesson_hint` "Hint" → a format string `Hint (%1$d/%2$d)` (static chrome, like the other button labels).
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt` -- Robolectric pattern; existing Hint assertions use `lesson:hint` and `RevealRequested` semantics and must be updated. `app/src/test/.../content/DiagnosisCopyTest.kt` is the exact-copy ICU pattern.

## Tasks & Acceptance

**Execution:**
- [ ] `core/domain/.../tutor/HintLadder.kt` -- `enum class HintRung { NONE, CLUE, TARGET, DEMONSTRATION }` (with `next()`), `object HintLadder { const val RUNGS = 3; fun canClimb(mode, rung, advance): Boolean; fun clue(lesson, step: Int): Text; fun target(lesson, step: Int): Set<Piece> }`. `clue` is `msg.hint.clue.piece {piece}` when the step's `focus.target` holds exactly one piece with a canonical white-piece name, else `msg.hint.clue.goal`; `target` is the step's `focus.target` (empty when the Demonstration is not Ready or the index is out of range) -- FR-11 rungs, derived clue.
- [ ] `core/domain/.../tutor/Assess.kt` -- `decide` gains `hintRung: HintRung`; `reveal = SHOW_ME || revealRequested || hintRung >= TARGET`; update KDoc. `DiagnosisRules.selectId` becomes `internal` for reuse -- reveal stays a decision (AD-3).
- [ ] `core/domain/src/main/resources/content/strings/en.json` -- add `msg.hint.clue.piece` ("Find the {piece S}. Where does it belong, and which face can carry it there?", select over the 8 canonical ids as in `msg.diagnosis.wrong-piece`), `msg.hint.clue.goal` ("Read the Goal again, then find the one face whose turn moves what you need and leaves what you keep."), `msg.hint.target` ("The outlined piece is the one to move. The Moves are below."), `msg.hint.demonstration` ("Watch this step on the cube, then try it yourself.") -- keyed copy.
- [ ] `core/domain/src/test/.../tutor/HintLadderTest.kt` -- climb order and top; `canClimb` false in SHOW_ME, at DEMONSTRATION and on advance, true otherwise; clue rows from the matrix; every shipped Lesson step yields a clue whose key exists in en.json; `target` of `lesson-02-05` step 1 is the white-green edge. Update `AssessTest` and `TutorVocabularyTest` for the new parameter/enum.
- [ ] `core/application/.../session/{SessionEvent,SessionState,Reduce}.kt` -- `HintRequested(at)`; `hintRung: HintRung` and `hintsUsed: Int` tutor fields (IDLE: NONE, 0); `HintRequested` applies only when `HintLadder.canClimb(teachingMode, hintRung, decision.advance)`; resets per Boundaries; `decision` passes `hintRung` -- AC 1-3.
- [ ] `core/application/.../session/TRANSITIONS.md` -- `HintRequested | tutor | no | no` row; add `hintRung`, `hintsUsed` to the tutor list; prose for climb, resets and the per-attempt count. Update `TransitionsTableTest` (tutor tuple, fixtures for the new event).
- [ ] `core/application/src/test/.../session/HintLadderSessionTest.kt` (new) -- every matrix row through `reduce`, including: no `revision` change; reveal false at NONE/CLUE in TEST_ME and LET_ME_TRY; rung resets on step change and on `LessonStarted` of the same and of another Lesson; `hintsUsed` survives step change, Undo, Reset and mode change; `HintRequested` clears a pending diagnosis. Fix other session tests that construct or enumerate tutor fields.
- [ ] `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/HintLine.kt` (new) -- composable showing the resolved rung line (clue, target or demonstration copy) with tag `lesson:hint:line`, `inkPrimary`, polite live region; nothing at rung NONE.
- [ ] `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/{LessonPanel,LessonScreen}.kt`, `app/src/main/res/values/strings.xml` -- button label `Hint (rung/3)`, `hintEnabled = HintLadder.canClimb(mode, session.hintRung, decision.advance)`, `onHint` sends `HintRequested`; `StepCardContent.hint: String?` rendered by `HintLine` under the Moves row; in TEST_ME/LET_ME_TRY at rung ≥ TARGET the net emphasis highlights `HintLadder.target` (violet `highlighted`, everything lit); when a `HintRequested` transition lands on DEMONSTRATION, play the shown step's Moves from its authored start on the net as a cancellable demonstration (generalized from `demonstrateReorientation`), then snap to the session state.
- [ ] `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt`, `app/src/test/.../content/HintCopyTest.kt` (new) -- screen: "Hint (0/3)" at start, each tap advances the label and shows the rung line; Move chips hidden at 0/3 and 1/3 and shown at 2/3 in *Test me*; 3/3 disabled; after the rung-3 demonstration the rendered net equals the session's state and the session revision is unchanged; a new Lesson shows "Hint (0/3)". Copy: each `msg.hint.*` resolves to the exact text above (`piece=white_green` → "white-green edge").

**Acceptance Criteria:**
- Given the Hint button **Hint (n/3)**, when the Learner taps it, then it moves up exactly one rung (clue → highlighted target with the Moves → the step's Demonstration) and the label shows the new rung.
- Given a new Objective (a Lesson started), when it starts, then the ladder is at rung 0 and the label reads "Hint (0/3)".
- Given an attempt, when hints are requested, then `SessionState.hintsUsed` counts each applied climb for that attempt, and no hint rung or default view shows more than the current step's Moves.

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 21 findings — high 0, medium 2, low 14, false 5, maybe-false 0
- findings:
  - `[low]` `[reject]` BH-1: cancelling a net demonstration does not cancel the renderer item already animating, so one flip can finish before the restoring Snap — shared with 4.2's *Show me how* path; at most one `ITEM_MILLIS` flip, and the fix needs a new renderer cancel API.
  - `[false]` `[reject]` BH-2: events dispatched outside the screen leave the demonstration running — every in-screen interaction goes through `send`, which stops it; a new Lesson remembers a new renderer (`remember(lesson.id)`), so the old job draws only on a discarded net.
  - `[low]` `[reject]` BH-3: no replay of the rung-3 Demonstration — the full Lesson Demonstration stays one tap away in *Show me*; a replay control adds UI and a non-counting path.
  - `[medium]` `[patch]` BH-4: the rung-2 line says "the outlined piece" when no piece or many pieces are outlined (3 shipped steps have an empty target, all Stage 1 steps 5–8) — `HintLadder.line` now uses `msg.hint.target` only for exactly one target, else new `msg.hint.moves` ("The Moves for this step are below."); tests pin both branches and the copy.
  - `[low]` `[patch]` BH-5: the generic clue promises "the one face", false for multi-face steps (02-04 step 1, 03-03 step 1) — reworded to "Read the Goal again: what has to move, and what has to stay where it is?"; copy test updated.
  - `[low]` `[reject]` BH-6: `HintLadder.target` rebuilds the Demonstration during composition — a few dozen cube applies per recomposition; caching adds parameters for no measurable cost.
  - `[low]` `[reject]` BH-7: a disabled Hint button still shows in *Show me* — it is the panel's fixed Hint + Next row and cannot climb (`canClimb` false); hiding it adds a branch for a cosmetic difference.
  - `[low]` `[patch]` BH-8: rung-3 playback tested only on a one-Move first step — grouped with VG-1; fixed there.
  - `[low]` `[reject]` BH-9: reduced-motion rung-3 playback untested — the `Snap` branch is 4.2's unchanged `demonstrateOnNet` code; only the start state was added, which the later-step test covers.
  - `[low]` `[reject]` BH-10: the interruption test checks only after settling — same root as BH-1 (one in-flight flip), rejected with it.
  - `[low]` `[reject]` ECH-1: same in-flight animation on interruption — grouped with BH-1, same reason.
  - `[medium]` `[patch]` ECH-2: TARGET line on an empty target — grouped with BH-4; same fix.
  - `[low]` `[patch]` VG-1: later-step playback unverified (a wrong `firstMove` slice or step 0 would pass) — new `LessonScreenTest` on lesson-02-05 step 3 asserts its authored start then end, never another step's state, and the unchanged session.
  - `[false]` `[reject]` IA-1: no Move arrow on rung 2 — FR-11 and the AC say "highlighted target **or** Move arrow"; the highlight branch satisfies it.
  - `[low]` `[patch]` IA-2: the rung-3 test cannot tell the step's Moves from "something ending solved" — grouped with VG-1; fixed there. (Whole-Lesson reading of "full Demonstration" is excluded by the AC's "full Solution is never shown".)
  - `[low]` `[reject]` IA-3: the new-Objective reset is driven by dispatching `LessonStarted` rather than navigation — that is the exact event the screen sends on open; navigation adds nothing to the reset.
  - `[false]` `[reject]` IA-4: an attempt equals a Lesson start — the AC asks for a per-attempt count in `SessionState`; boundaries beyond that belong to 4.4/6.3.
  - `[low]` `[patch]` IA-5: no multi-step check that a hint never shows a later step — grouped with VG-1; the new test asserts no later step's state is drawn.
  - `[false]` `[reject]` IA-6: no Hint in Practice — the story names the Lesson; Practice does not exist yet.
  - `[low]` `[reject]` IA-7: climbing in Guide me is not tested on screen — the reducer and `canClimb` are mode-agnostic and covered; the screen path is identical to Test me's.
  - `[false]` `[reject]` IA-8: interpretive additions (refusing hints at a Notice, rung kept on mode switch, reset on Reset, Show the Move not a hint) — consistent with AD-6/FR-11 and tested; no harm named.

## Design Notes

**Rung per step, count per attempt.** AD-6 makes per-step UI state reset when the derived step changes, and rung 2 is what reveals the Moves; a rung carried into the next step would reveal its Moves unasked (FR-5). So the rung resets with the shown step and, a fortiori, on a new Objective. The count is the attempt's, and an attempt here runs from `LessonStarted` (6.3 adds the persisted Attempt boundaries; 4.4's *Try again* may start a new one).

**Rung-3 playback** (sketch):
```kotlin
val step = demonstration.steps[index]
val from = demonstration.start.apply(demonstration.moves.take(step.firstMove))
renderer.submit(listOf(Snap(rev, from.toFacelets())))
step.moves.forEach { m -> /* Animate or Snap under reduceMotion */; awaitFrameMillis(REORIENT_STEP_MILLIS) }
renderer.submit(listOf(Snap(now.revision, now.virtual.toFacelets())))
```

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `HintLadderTest`, `HintLadderSessionTest`, `TransitionsTableTest`, `LessonScreenTest`, `HintCopyTest`, `ShippedContentTest`, Kover ≥ 80% on `:core:*`.

## Auto Run Result

- **Summary:** Added the Hint Ladder (FR-11). `tutor/HintLadder.kt` defines rungs `NONE → CLUE → TARGET → DEMONSTRATION`, the climb rule (`canClimb`: not in *Show me*, below the top, not while `advance`), a clue derived from the step's Demonstration focus target, and the rung lines. `SessionEvent.HintRequested` climbs one rung and counts it in `SessionState.hintsUsed`; `hintRung` resets wherever `revealRequested` does (new Lesson, playback start, applied Reset, Next, shown-step change), `hintsUsed` only on a Lesson/playback start. `Assess.decide` reveals the Moves from rung 2. The panel shows **Hint (n/3)** and a `HintLine`; rung 2 outlines the single target in violet in *Test me*/*Let me try*; rung 3 plays the shown step's Moves from its authored start on the net, then snaps back, dispatching nothing.
- **Files:**
  - `core/domain/.../tutor/HintLadder.kt` (new): rungs, climb rule, clue, lines, target.
  - `core/domain/.../tutor/{Assess,DiagnosisRules}.kt`: reveal from rung 2; `selectId` internal.
  - `core/domain/src/main/resources/content/strings/en.json`: five `msg.hint.*` keys.
  - `core/application/.../session/{SessionEvent,SessionState,Reduce,TRANSITIONS.md}`: `HintRequested`, `hintRung`, `hintsUsed`, resets, table row.
  - `app/.../ui/lesson/{HintLine,LessonPanel,LessonScreen}.kt`, `app/src/main/res/values/strings.xml`: button label, hint line, rung-2 highlight, rung-3 net demonstration (`demonstrateOnNet`, shared with *Show me how*).
  - Tests: `HintLadderTest`, `HintLadderSessionTest`, `HintCopyTest` (new); `AssessTest`, `TutorVocabularyTest`, `TransitionsTableTest`, `Lessons.kt`, `LessonScreenTest` updated.
- **Review:** 21 findings. Patched: 2 entries — the rung-2 line for empty/multi targets (medium, BH-4/ECH-2) and the generic clue wording (low, BH-5) — plus the later-step rung-3 playback test (low, VG-1/IA-2/IA-5/BH-8). Deferred: none. Rejected: 9 low (BH-1, BH-3, BH-6, BH-7, BH-9, BH-10, ECH-1, IA-3, IA-7) and 5 false (BH-2, IA-1, IA-4, IA-6, IA-8), reasons in the triage log.
- **Follow-up review recommended:** false. Patched counts by entry verdict: high 0, medium 1, low 2.
- **Verification:** `./gradlew spotlessApply && ./gradlew spotlessCheck build` — BUILD SUCCESSFUL before review and again after the patches; every I/O matrix row has a passing test (`HintLadderSessionTest` 18, `HintLadderTest` 9, `LessonScreenTest` 25, `HintCopyTest` 2).
- **Residual risks:**
  - An interrupted net demonstration may finish its one in-flight flip before the restoring Snap (shared with *Show me how*).
  - The Hint button stays visible but disabled in *Show me*.
  - "Attempt" currently means one Lesson start; Story 4.4's *Try again* and 6.3's Attempt log may redraw that boundary.
  - Rung-3 screen tests step the Compose clock by hand.
