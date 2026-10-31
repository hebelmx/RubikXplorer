---
title: Adversarial review — AD-5 / AD-6 step-and-feedback update (RubikXplorer)
reviewed: ../ARCHITECTURE-SPINE.md (diff vs main, 2026-10-31 update run)
against: ../../../sprint-change-proposal-2026-10-31.md (approved), current code in core/domain/.../tutor, content/Model.kt, predicate/, core/application/.../session/Reduce.kt, shipped Stage 1–3 content
lens: "Construct two units one level down that each obey every AD to the letter yet still build incompatibly."
scope: changed ADs only — AD-5 (feedback: MoveFeedback?, Test me feedback rule), AD-6 (steps[].done, Steps block) and their interaction with AD-3, AD-10, AD-20
date: 2026-10-31
---

# Adversarial review — derived steps and Test me feedback

**Verdict.** The update fixes the right problem, but as written it still lets two compliant builders ship incompatible step and feedback behavior. Worse, on shipped content it breaks the change's own success criterion ("every taught Move is On path") in `lesson-03-04`. Close the seven holes below before Stories 4.1 and 4.2 start.

**Counts:** Critical 1 · High 4 · Medium 2 · Low 1 (8 total)

| # | Sev | Hole | ADs to amend |
|---|---|---|---|
| C1 | Critical | A later step's authored path temporarily breaks an earlier step's `done`, so the derived step drops back mid-trigger and ON_PATH stops matching | AD-6, AD-5 |
| H1 | High | Two owners of "current step": stored `stepIndex` and `StepAdvanced` (AD-3) vs the derived step (AD-6) | AD-3, AD-6 |
| H2 | High | "Final step's done agrees with completion" is checked at one point, so all steps can be done while the Lesson is incomplete, and a step can be done while `preserve` is broken | AD-6 |
| H3 | High | Feedback has no defined producer call site, reference state, or step (before or after the Move), and AD-5 and the 4.2 AC disagree on what NEUTRAL means | AD-5, AD-3 |
| H4 | High | ON_PATH vs the Diagnosis channel (COMPLETED_LAYER_DISTURBED, FR-12 checkpoints), and Certainty of a HARMFUL Diagnosis in physical mode | AD-5, AD-10 |
| M1 | Medium | The "exact state match" and "next authored Move" comparisons are underspecified: orientation, half turns split into quarter turns, equivalent wide/slice Moves | AD-5, AD-7 |
| M2 | Medium | The closed predicate registry cannot express the step `done`s that the shipped content needs, and nothing says who adds them | AD-6 |
| L1 | Low | Which source drives the step card in *Show me*: `PlaybackController` position (AD-20) or the derived step | AD-6, AD-20 |

---

## C1 — Critical: a later step's path breaks an earlier step's `done`, so the derived step regresses mid-trigger

**Builders.** The content author (Story 4.1 content) writes per-corner step `done`s for `lesson-03-04`. These pass the AD-6 content test, which only checks that "the authored path satisfies each step's `done` in order", that is, at step boundaries. The tutor builder (Story 4.2) implements AD-5 ON_PATH as "the pre-Move state is an authored path state *inside the current step*", with the current step derived per AD-6.

**Collision, on shipped content.** `lesson-03-04` = `R' D' R | B' D' B | L' D' L | F' D' F`, with the white cross on U (the entry U face is `GWBWWWBWR`). Each trigger's first quarter turn sends its target U corner down, and it also moves the neighbouring U corner out of its slot for the length of the trigger:

| Step | Trigger | Target slot | U corner it moves temporarily |
|---|---|---|---|
| 1 | R' D' R | UFR | UBR (step 2's target, still unmet, so harmless) |
| 2 | B' D' B | UBR | UBL (step 3's target, harmless) |
| 3 | L' D' L | UBL | UFL (step 4's target, harmless) |
| 4 | **F' D' F** | UFL | **UFR, which step 1 already solved** |

After step 4's `F'`, step 1's `done` is unmet. AD-6 then derives the current step as **step 1**, and:
- the step card jumps back to "insert the first corner" in the middle of the fourth insert;
- the next taught Move `D'` is **not** ON_PATH (its pre-Move state belongs to step 4's path, not step 1's), so it is judged against step 1 and shows NEUTRAL ("That didn't change much"). This violates the proposal's success criterion, *every taught Move of every shipped Lesson is classified On path*;
- with count-based `done`s ("≥ k corners solved") the derived step regresses to step 3 instead. The symptom is the same, so no choice of predicate saves it.

The same hole has a sibling: **a step whose `done` already holds at entry**, or at the start of its own segment. The derived step skips it. The first authored Move then sits in a skipped step's path, so it is never ON_PATH, and *Show me* plays Moves for a step the card says is finished.

**Proposed tightening (AD-6 Steps, AD-5 ON_PATH).**
1. *Content test:* along the authored path, step *k*'s `done` is false at every path state before the end of step *k* and true at every path state after it, for every *k* (monotone). The rule says "every path state", not "every boundary". `lesson-03-04` then fails until its `done`s or its step order are fixed.
2. *Derivation:* "If the state equals an authored path state, the current step is the step whose segment contains that state (a boundary state belongs to the later step). Otherwise it is the first step whose `done` is unmet." ON_PATH is then evaluated against that step. Path matching outranks predicate derivation, so a transient lift during a taught trigger can never move the card.

## H1 — High: two owners of the current step

**Builders.** The 4.1 reducer builder keeps `SessionState.stepIndex` and the `StepAdvanced` event, which `session/TRANSITIONS.md` makes normative today. AD-6 only forbids a stored index *alone*, so a hybrid passes: it stores `stepIndex`, gates Next on the derived `advance`, and moves the index on tap. The 4.2 Assess builder judges feedback against the purely derived step.

**Collision.**
- A step is done but the Learner has not tapped Next: the card shows step *k* while feedback is judged against step *k+1*.
- Undo across a boundary: the derived step returns to *k*, but `stepIndex` stays at *k+1* unless someone re-derives it. TRANSITIONS.md says Undo does not touch the tutor fields.
- `revealRequested` and `lastMoveFeedback` are cleared on `StepAdvanced`. Under derivation, nothing says they reset when the derived step changes because of a Move or an Undo, so a reveal leaks into the earlier step.
- AD-10 checkpoints a "mode". If one builder adds `stepIndex` to the Checkpoint DTO, restore can disagree with the derivation.

**Proposed tightening (AD-3 Teaching Mode, AD-6 Steps).** "`currentStep` is a pure function of `(lesson, virtual)` computed only in `:core:domain` (`Tutor`). `SessionState` never stores it, and neither Checkpoint nor Snapshot persists it. Any per-step UI state (`revealRequested`, an acknowledged-Notice flag) is keyed by step index and is cleared whenever the derived step changes. `StepAdvanced` is removed or reduced to acknowledging the Notice." Then update TRANSITIONS.md to match.

## H2 — High: "agrees with completion" is unenforceable; all steps done with the Lesson incomplete

**Builders.** The content author writes `lesson-03-01`'s single step as `done: white-corners-solved`. On the authored path it "agrees" with `completion = allOf(cross, corners)` at the final state. The tutor builder follows AD-6 to the letter.

**Collision.** The Learner inserts the corner by an off-path route that leaves a cross edge flipped. Every `done` holds, so the current step is "the last step" with its `done` met, but `completion` is unmet:
- `advance` / Notice / Next behavior is undefined. One builder shows the step's Notice, which is a dead end. Another treats it as still in progress.
- `preservedPieces(state)` is state-dependent. The broken cross edge has already left the Lesson's preserved set, so nothing classifies further damage as HARMFUL.
- The same gap applies to intermediate steps. A step's `done` can hold while the Lesson's `preserve` is broken, so the card advances over a broken cross.

"Agrees" is checked at one state, the end of the path. That cannot prove equivalence over the states a Learner reaches.

**Proposed tightening (AD-6 Steps).** "The last step's `done` is not authored: it *is* the Lesson's `completion`, so a single-step Lesson's step is exactly its completion. A step's effective `done` is `allOf(lesson.preserve, steps[k].done)`. When every intermediate `done` holds but `completion` does not, the current step is the last step, and it is unmet." The schema then drops `done` on the last step, or the validator requires it to equal `completion` structurally.

## H3 — High: feedback has no defined producer call site, reference state or step

**Builders.** The 4.1 reducer builder keeps today's shape: it classifies at `MoveCommitted` in every mode and stores `lastMoveFeedback` (`Reduce.kt:60-66`), and *Test me* only gates its display. The 4.2 Assess builder reads AD-5 literally: "only `tutor/Assess` produces it, only in *Test me*", and `TutorDecision` is derived from the current state (`SessionState.decision`). So it re-derives feedback from `history`'s last Move.

**Collision.**
- After Undo, the history-derived version re-shows feedback for the *previous* Move, while the stored version shows nothing.
- After a switch from *Let me try* to *Test me*, the stored version may hold a stale classification.
- The Move that completes step *k*: is PROGRESS measured on step *k* (derived before the Move) or step *k+1* (derived after it)? Step *k+1*'s progress usually does not rise, so one builder says PROGRESS and the other says NEUTRAL.
- Contract conflict: AD-5 makes NEUTRAL "every other case", which includes a Move that **lowers** the current step's progress without touching a preserved piece. The approved 4.2 AC says "Neutral if nothing changes". A test written from the AC fails against an Assess written from the spine.

**Proposed tightening (AD-5 Test me feedback).** "Feedback is `Assess.classify(lesson, before, move, after)`, judged against the step derived from `before`. The reducer calls it only on `MoveCommitted` while `teachingMode == TEST_ME`, and stores the result. Undo, Redo, Reset, restore and mode change clear it (one TRANSITIONS.md row each). A fall in step progress that disturbs no preserved piece is NEUTRAL." Then align the 4.2 AC wording.

## H4 — High: ON_PATH vs the Diagnosis channel, and Certainty in physical mode

**Builders.** The 4.2 builder makes ON_PATH win over HARMFUL, as AD-5 orders. The FR-12 / AD-10 builder computes `COMPLETED_LAYER_DISTURBED` from `preservedPieces` against the last Checkpoint, independently of feedback, which AD-6 allows.

**Collision.**
- Mid-trigger (after `R'` in `R' D' R`, which lifts the UR cross edge), `TutorDecision.feedback = ON_PATH` while `TutorDecision.diagnosis = COMPLETED_LAYER_DISTURBED` with a recovery prompt. That is one decision with two contradictory messages. The same happens in *Let me try*, where feedback is null but the Diagnosis still fires.
- In physical mode (`PhysicalPredicted`), the Learner's Move is only *confirmed*, and Assess judges `expected`. AD-5 says HARMFUL "produces a Diagnosis", but not at what Certainty. A builder that emits `Diagnosis.verified` on a predicted cube violates AD-5's own Prevents ("a predicted cube treated as observed"). Another builder emits LIKELY. The screens then differ.
- In `PhysicalAwaitingConfirm` or `PhysicalMismatch` there is no trustworthy state to classify against at all.

**Proposed tightening (AD-5).** "While the state is an authored path state of the current step (the C1 rule), Assess emits no disturbance Diagnosis in any mode. A HARMFUL Diagnosis takes the Certainty of the evidence: VERIFIED only in Virtual or `PhysicalVerified`, at most LIKELY in `PhysicalPredicted`, and feedback is null in `PhysicalAwaitingConfirm` and `PhysicalMismatch`. The current step in physical mode is derived from `expected`."

## M1 — Medium: the "exact state match" and "next authored Move" comparisons are underspecified

**Builders.** One builder compares `CubeState ==`, as `Tutor.decide` does today. That compares `orientation` too (`CubeState.kt` warns about this), and it compares Moves as values in the home frame. Another builder compares `cubies` only, consistent with the orientation-invariant predicates, and compares Moves by their effect relative to the centers.

**Collision.**
- After a whole-cube rotation (`y`), the first builder never reports ON_PATH again, and the second does.
- A Learner who plays the authored `F2` as `F`, `F` (a future quarter-turn gesture, or the `F` key twice) gets a non-ON_PATH first `F` from both builders. In Stage 3, splitting `D2` the same way gives NEUTRAL on a taught Move.
- `r` and `L x` are equivalent, but the result is unspecified.

**Proposed tightening (AD-5 ON_PATH, AD-7).** "Path matching compares `cubies` only. A Move matches the next authored Move when the two have the same effect on `cubies`. A quarter turn that is half of an authored half turn is ON_PATH, and so is the completing second quarter turn. The content test requires the authored path states within one Lesson to be distinct, so 'next Move' is unique."

## M2 — Medium: the closed registry cannot express the shipped step `done`s, and the owner of new types is unnamed

**Builders.** The proposal says the roughly 19 step predicates are "authored from the existing registry" (`white-cross-solved`, `white-corners-solved`, `state-reached`, `allOf`, `anyOf`).
- `lesson-03-03` step 1 ("turn the corner so its white sticker faces right") and the per-corner steps of `lesson-03-04` cannot be expressed with these.
- `state-reached` is anchored to `SOLVED.apply(moves)`, not to the entry state, so as a step `done` it degenerates into the exact-state match this change set out to remove, and its 20-slot progress is not step progress.

4.1's content builder and 4.2's Assess builder will each add a predicate (for example `corner-solved{colors}` vs `white-corners-count{n}`). Both pass "an unknown predicate fails the content test".

**Proposed tightening (AD-6 Predicates).** "Step `done` predicates use the leaf types listed here (name the ones needed, for example a piece-placed predicate with color and orientation parameters). Story 4.1 owns the additions to the registry. `state-reached` is allowed only in Stage 1 Notation Lessons."

## L1 — Low: the step card source in *Show me*

The Demonstration is not a session (AD-3) and is driven by `PlaybackController` over `List<Move>` (AD-20). One builder labels the card by playback index, which is a counter. Another applies AD-6 derivation to the playback state. These diverge exactly in the C1 cases.

**Proposed tightening (AD-20).** "During a Demonstration the step card follows the authored step segment that contains the playback position. This is consistent with the C1 rule, because a Demonstration is always on the path."

---

## Edge cases checked

| Case | Spine as written | Covered by |
|---|---|---|
| Single-step Lesson | `done` is authored separately and can drift from `completion` | H2 (last `done` *is* `completion`) |
| All steps done, completion unmet | Undefined Notice/Next; dead end | H2 |
| Move that both disturbs a preserved piece and is on-path | Feedback ON_PATH, but a Diagnosis can still fire | H4 |
| Step `done` holds at entry or before its segment | Step skipped; first taught Move not ON_PATH | C1 (monotone content test) |
| Later step lifts an earlier step's piece | Card regresses; taught Move NEUTRAL (`lesson-03-04`) | C1 |
| *Show me* / *Let me try* | Feedback null (fine); Diagnosis channel unconstrained; card source ambiguous | H4, L1 |
| Physical mode, unverified state | HARMFUL Diagnosis Certainty unspecified | H4 |
| "Agrees with completion" enforceable? | No: checked at one state only | H2 |
| Undo across a step boundary | Derived step returns, stored `stepIndex` and reveal do not | H1, H3 |
| Progress falls without disturbing preserved pieces | Spine NEUTRAL vs AC "nothing changes" | H3 |
