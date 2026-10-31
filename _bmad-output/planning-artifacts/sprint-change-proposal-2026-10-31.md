---
title: 'Sprint Change Proposal: Test me judges the current step'
date: 2026-10-31
trigger: 'Epic 2 retrospective, action item 2 (findings 5 and 6)'
scope: moderate
status: approved
approved: 2026-10-31 by product owner
mode: incremental
---

# Sprint Change Proposal: Test me judges the current step

## 1. Issue summary

**Problem.** In *Test me*, `MoveFeedback.classify` compares whole-Objective `ObjectiveProgress` before and after each Move. The opening Move of an authored insert (R' in R' D' R, U' in U' R' U) temporarily lifts a solved piece. So a Learner who plays exactly the taught Move is told "That moved a piece you need." Separately, a multi-step Lesson advances its step card only when the cube matches the authored path state exactly. A Learner who reaches a step's goal another way (or does the steps out of order) keeps seeing an old step card, and Undo across a step boundary leaves the card on the later step.

**Discovery.** Story 2.7 shipped Stages 1–3 (12 Lessons, 7 of them multi-step). The 2.7 review deferred the classifier issue (medium). The Epic 2 retrospective (`_bmad-output/implementation-artifacts/epic-2-retro-2026-10-30.md`, findings 5 and 6, action item 2) traced it to planning. Story 4.2 replaces the Harmful *copy*, but not the *classifier*. Steps have no goal predicate, so step progress can only be measured against exact path states.

**Category.** Misunderstanding of requirements. EXPERIENCE.md defines Harmful as "the same as a diagnosed error", and Story 2.6 shipped it as a whole-Objective progress drop.

**Evidence.**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MoveFeedback.kt:33-44`, called for every commit at `core/application/.../session/Reduce.kt:60-66`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Tutor.kt:33-34`: `advance = satisfied || current == pathState(lesson, entry, stepIndex + 1)`.
- `spec-2-7-author-stages-1-3-with-path-tests.md`, deferred item (medium); `spec-2-5-…md` and `spec-2-6-…md` residual risks.
- Multi-step Lessons where step order is free: `lesson-02-05` (F2, R2, B2, L2) and `lesson-03-04` (four corner inserts).

**Exposure.** Debug builds only. Release keeps `TEST_ME` OFF, and the product owner confirmed (2026-10-31) that it stays OFF until Epic 4 lands.

## 2. Impact analysis

- **Epic impact.** Epic 2 is done and stays closed, and no rollback is needed. Epic 4 Stories 4.1 and 4.2 gain and replace acceptance criteria. No other epic changes, and no epic is added, removed or resequenced.
- **Story impact.** 4.1 gains step-derivation and step-predicate content ACs. 4.2's Test me AC is replaced by three ACs. Story 2.6's `MoveFeedback` classifier and Story 2.3/2.5's path-state step advance are superseded inside Epic 4.
- **PRD.** No conflict. FR-5's testable consequences still hold, and completion stays decided by the Completion Predicate.
- **Architecture.** AD-6's Lesson shape gains `steps[].done: Predicate`, plus a rule that the current step is derived from state.
- **UX.** EXPERIENCE.md "Test me outcomes" gains an **On path** outcome, and Harmful is qualified as "disturbs a piece the current step preserves".
- **Technical.** The content schema and model gain a required step field, and about 19 step predicates are authored from the existing registry. `ShippedLessonPathsTest` is extended. The classifier moves behind `tutor/Assess`. There are no infrastructure, CI or deployment changes.

## 3. Recommended approach

**Direct Adjustment (Option 1).** Edit the Epic 4 story ACs, AD-6 and one EXPERIENCE.md row, and do the work inside Stories 4.1 and 4.2.

- **Rollback (Option 2)** is not viable: 2.6 and 2.7 are correct as specified, and the gap is in what comes next.
- **MVP review (Option 3)** is not needed: scope and goals are unchanged.
- **Effort:** low–medium (19 step predicates plus classifier rework, already within 4.1/4.2's area).
- **Risk:** low.
- **Timeline:** no change to the ~16-week plan.

## 4. Detailed change proposals

All three were approved individually by the product owner on 2026-10-31.

### 4.1 Stories

**Story 4.2 — Diagnose each Error Category with tested copy. Section: Acceptance Criteria (3rd).**

OLD:

> **Given** a Harmful Move in *Test me*
> **When** it is committed
> **Then** it now produces the diagnosed error and a recovery prompt (replacing Story 2.6's placeholder)

NEW:

> **Given** *Test me* on any shipped Lesson
> **When** the Learner commits the next Move of the current step's authored path
> **Then** it is never classified Harmful, even if it temporarily lifts a solved piece (e.g. R' opening R' D' R); it is classified On path and shows "That's the move — keep going."
>
> **Given** *Test me* and a Move off the authored path
> **When** it is committed
> **Then** it is judged against the current step (its `preserve` set and the step's ObjectiveProgress), not whole-Objective progress: Progress if it gets closer without disturbing the step's preserved pieces, Neutral if nothing changes, Harmful only if it disturbs a preserved piece
>
> **Given** a Harmful Move in *Test me*
> **When** it is committed
> **Then** it produces the diagnosed error and a recovery prompt, replacing both Story 2.6's placeholder copy and its whole-Objective classifier (`MoveFeedback`); scenario tests cover a taught insert in Stages 2 and 3

*Rationale:* as written, 4.2 replaced the copy but kept the classifier, so the taught Moves would stay Harmful (retro finding 5).

**Story 4.1 — Assess evidence and produce a TutorDecision. Section: Acceptance Criteria (appended).**

NEW:

> **Given** a multi-step Lesson in *Let me try* or *Test me*
> **When** the Learner meets a step's `done` predicate by any legal route (including steps out of order)
> **Then** `TutorDecision.advance` moves to the first step whose `done` is unmet, and the step card follows
>
> **Given** an Undo across a step boundary
> **When** the state is re-assessed
> **Then** the current step is re-derived from the state, so the card returns to the earlier step
>
> **Given** the shipped Stages 1–3 content
> **When** the content test runs
> **Then** every step has a `done` predicate, the authored path satisfies each step's `done` in order (extends `ShippedLessonPathsTest`), and the final step's `done` agrees with the Lesson's Completion Predicate

*Rationale:* retro finding 6. Completion stays decided by the Completion Predicate, and step guidance now follows the cube.

### 4.2 Architecture

**ARCHITECTURE-SPINE.md, AD-6, "Lesson shape".**

OLD: `steps[]: { goal, preserve, watch, notice: TextKey, moves }`

NEW: `steps[]: { goal, done: Predicate, preserve, watch, notice: TextKey, moves }`, plus a bullet: "A step is current until its `done` predicate holds; the tutor derives the current step from the state (the first step whose `done` is unmet), never from a stored index alone."

*Rationale:* without a step-level predicate, a step can only be recognized by an exact path-state match.

### 4.3 UX

**EXPERIENCE.md, State Patterns, "Test me outcomes" row.**

OLD: **Progress** (a different Move that still helps): "That works too — you're closer." **Neutral**: "That didn't change much. Try another turn." **Harmful**: the same as a diagnosed error, with a recovery prompt.

NEW: **On path** (the next Move of the current step's authored path): "That's the move — keep going." **Progress** (a different Move that still helps): "That works too — you're closer." **Neutral**: "That didn't change much. Try another turn." **Harmful** (disturbs a piece the current step preserves): the same as a diagnosed error, with a recovery prompt.

*Rationale:* the taught Move needs a line that is not "That works too".

### 4.4 PRD

No change.

## 5. Implementation handoff

**Scope: Moderate.** Backlog ACs change, plus one architecture decision edit. There is no replan.

| Role | Responsibility |
|---|---|
| Architect (Winston, `bmad-architecture`) | Apply the AD-6 Lesson-shape change in ARCHITECTURE-SPINE.md. |
| PO / Developer (`bmad-correct-course` apply) | Apply the Story 4.1 and 4.2 AC edits in `epics.md` and the EXPERIENCE.md row, on a docs branch through a PR. |
| Developer (`/bmad-orchestrate`) | Build Stories 4.1 and 4.2 to the new ACs. They include the content-schema change and the step `done` predicates for Stages 1–3. |
| Product owner | Keep `TEST_ME` OFF in release until 4.2 is merged. |

**Success criteria.**
- In *Test me*, every taught Move of every shipped Lesson is classified On path, never Harmful (scenario tests).
- In multi-step Lessons, out-of-order steps and Undo across step boundaries keep the step card in sync with the cube.
- Every shipped step carries a `done` predicate that the authored path satisfies in order.
- `TEST_ME` stays OFF in release until 4.2 merges.
