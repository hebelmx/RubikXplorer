---
title: 'Sprint Change Proposal: 2-look rules reconciliation and Epic 9 seam fixes'
date: 2026-11-10
status: approved
approved_by: hebelmx (PO), 2026-11-10
trigger: Epic 9 retrospective (epic-9-retro-2026-11-09.md), action items 1–8 and its open questions
scope: Moderate
mode: batch
---

# Sprint Change Proposal: 2-look rules and Epic 9 seam fixes

## 1. Issue Summary

The Epic 9 retrospective closed the epic as *accepted-with-open-items* and recommended a course correction. There are two triggers, and both must land **before the Story 7.8 pilot build**.

**A. The planning rules for 2-look were decided during the build.**
- **Mastered vs Completed.** FR-17 defines the two states separately. Story 9.8 built *Mastered* without requiring *Completed* (`TwoLookMastery.kt:36`: `mastered = count >= solvesRequired`). So the *Faster last layer* row can read ◆ *Mastered* while 2-look Lessons under it are unfinished.
- **A second last-layer definition.** AD-19 says `LastLayerMatcher` is the one definition of Case patterns and AUF, used by "the 2-look predicates". Story 9.8 judges mastery with `TwoLookSolve`, which compares exact cubie effects of 2-look Algorithms and applies its own U-turn rule. The choice was an intent reading in the 9.8 Design Notes, not a planning decision.
- **Scramble pool.** The retro asked whether LL practice scrambles should use all 78 Library Algorithms (as built) or only the 2-look set.

**B. Three seam defects that no per-story gate saw** (retro findings 1–3; each was found by two independent review lenses and re-checked against source):
1. **A Course Lesson opened after LL practice runs as practice.** `LessonScreen.kt:253` treats a host already in `SessionKind.PRACTICE` as practice. Closing practice never ends the host session, and since 9.8 LL practice is also restored on cold start. The Lesson then has no Teaching Modes and writes no `LessonCompleted`.
2. **The 2-look offer can downgrade *Learned* to *Learning*.** `AppShell.kt:207` reads statuses with an `emptyMap()` initial value. `RoomAlgorithmStatusRepository.kt:19-21` emits `emptyMap()` and completes on any failure. *Add to Learning* then overwrites a Learner's *Learned* judgment (9.6 rule).
3. **Continue reopens locked 2-look content after *Reset progress*.** `AppShell.kt:467-473` offers Continue for a `TWO_LOOK` or LL-practice session without checking the unlock, because a reset does not end the live session (FR-17 unlock, 9.7 AC).

Smaller follow-ups from the same retro: stale `TRANSITIONS.md` lines 223 and 272 (item 4), an untested shell choice of recognition diagram (item 5), 2-look Learner copy in `strings.xml` (item 6, regresses `epic-2-retro-item-3`), and Epic 9 deferrals stranded in spec frontmatter (item 8).

### PO decisions (2026-11-10)

| Question | Decision |
| --- | --- |
| Does 2-look *Mastered* require *Completed*? | **Yes.** *Mastered* needs every 2-look Lesson completed **and** two qualifying solves. Qualifying solves made before completion still count. |
| `TwoLookSolve` vs `LastLayerMatcher` | **Narrow AD-19.** The matcher keeps sole ownership of Case patterns and AUF. `TwoLookSolve` is the recorded judge of *which Algorithms a solve used*, a different question; it must not define Case patterns. A cross-check test pins the two together. |
| What does *Reset progress* do to a live session? | **It ends it.** Reset ends and discards any live session and its snapshot. Closing LL practice also ends the practice session. |
| LL practice scramble pool | **All 78 Library Algorithms** (as built). Any last layer is solvable with 2-look, so scrambles stay realistic. |

## 2. Impact Analysis

- **Epic impact.** Epic 9 is closed and stays closed; its rules change through new stories, not by reopening it. Epic 12 gains two stories (12.11, 12.12) before the pilot. No epic is added, removed or reordered.
- **Story impact.**
  - Story 9.8 (done): its *Mastered* AC is annotated as amended by this proposal; Story 12.12 implements the change.
  - New Story 12.11 fixes the three seam defects and the reset rule.
  - New Story 12.12 implements the 2-look rule decisions and the small follow-ups.
  - Story 7.8 (pilot) gains a dependency on 12.11 and 12.12.
- **Artifact conflicts.**
  - **PRD** FR-17 (mastered rule, scramble pool) and FR-19 (reset ends a live session).
  - **Architecture** AD-19 (narrowed scope; names `TwoLookSolve`), AD-10 (*Reset progress* ends the live session and deletes its snapshot).
  - **UX** EXPERIENCE.md (reset sheet note; the *Faster last layer* row's state order).
  - **Epics** Story 9.8 annotation, Stories 12.11–12.12, the 7.8 dependency line.
  - **Sprint status** two new backlog stories; stranded Epic 9 deferrals added as action items; retro item 7 closed by this proposal.
- **Technical impact.** Contained to `:core:domain` (`TwoLookMastery`), `:core:application` (reset use case, session host end), and `:app` wiring (`AppShell`, `LessonScreen`, `RoomAlgorithmStatusRepository`). No Room schema change is expected: ending a session deletes the existing snapshot row. No new dependency.

## 3. Recommended Approach

**Direct Adjustment:** two new stories inside the existing plan.

- **Why not reopen Epic 9:** its ACs were met as written. The changes are new decisions and seam fixes, and Epic 12 already holds the pre-pilot fix stories (12.6–12.10).
- **Why two stories, not one:** Story 9.8 needed five gate rounds. Smaller stories gate faster. 12.11 is behavior fixes on session lifecycle. 12.12 is rule and hygiene work in the 2-look area.
- **Effort:** 12.11 medium (session lifecycle touches the shell, the host and Reset). 12.12 small to medium.
- **Risk:** medium for 12.11, because ending sessions on reset and on practice close touches the snapshot/restore path that Epic 6 built; existing restore tests guard it. Low for 12.12.
- **Timeline:** adds two stories before 7.8; no change to the ~16-week plan.

## 4. Detailed Change Proposals

### 4.1 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

**FR-17, Consequences — mastered rule**

OLD:
> - The mini-Course is *completed* when every Lesson's predicate has been met. It is *mastered* once the Learner has twice solved the LL of a fresh Scramble using only 2-look Algorithms without hints. [ASSUMPTION: 2-look mastery rule.]

NEW:
> - The mini-Course is *completed* when every Lesson's predicate has been met. It is *mastered* once it is *completed* and the Learner has twice solved the LL of a fresh Scramble using only 2-look Algorithms without hints. Qualifying solves made before completion count. [ASSUMPTION: 2-look mastery rule.] *(SCP 2026-11-10)*
> - LL practice Scrambles are built from the whole Algorithm Library, so every last-layer Case can appear. *(SCP 2026-11-10)*

Rationale: PO decision; makes *Mastered* imply *Completed*, so the row never claims mastery over unfinished Lessons.

**FR-19, Consequences — reset**

Add after the reset consequence:
> - *Reset progress* also ends any live Lesson, 2-look or practice session and deletes its snapshot, so nothing locked by the reset can be continued. *(SCP 2026-11-10)*

### 4.2 Architecture (`ARCHITECTURE-SPINE.md`)

**AD-19 — One last-layer matcher**

OLD:
> - **Rule:** `LastLayerMatcher` in `:core:domain` is used by the FR-18 verification, diagram derivation, FR-28 and the 2-look predicates.

NEW:
> - **Rule:** `LastLayerMatcher` in `:core:domain` is the only definition of Case patterns and AUF. It is used by the FR-18 verification, diagram derivation, FR-28 and the 2-look predicates.
> - **Algorithm-use judge (SCP 2026-11-10):** `TwoLookSolve` decides whether an LL solve used only 2-look Algorithms, U adjustments and rotations, by comparing Move effects. It answers *which Algorithms were used*, not *which Case this is*, and must not define Case patterns or AUF itself. A test pins the two: every 2-look Lesson Demonstration scores 0 off-path Moves under `TwoLookSolve`, and its result satisfies the Lesson's matcher-built predicate.

**AD-10 — Changes and deletes**

OLD:
> - **Changes and deletes:** rows are never updated. The only deletes are the Learner's scoped resets (progress, calibration, preferences).

NEW:
> - **Changes and deletes:** rows are never updated. The only deletes are the Learner's scoped resets (progress, calibration, preferences). *Reset progress* also ends the live session and deletes its snapshot (SCP 2026-11-10).

### 4.3 UX (`EXPERIENCE.md`)

**Flow, line 290**

OLD:
> 4. Later, in Settings → *Reset progress*, the sheet lists exactly what is erased: Stages, attempts and Algorithm Statuses. Calibration and preferences have their own resets.

NEW:
> 4. Later, in Settings → *Reset progress*, the sheet lists exactly what is erased: Stages, attempts and Algorithm Statuses. Any Lesson or practice in progress also ends, so the Continue card is cleared. Calibration and preferences have their own resets.

**Learn tab row (line 29), append:**
> The *Faster last layer* row shows ◆ *Mastered* only once it is also *completed*; before that it shows *Completed* or *In progress*, with "Mastery: 1 of 2" when a qualifying solve exists.

### 4.4 Epics (`epics.md`)

**Story 9.8 — annotate the second AC** (append below it):
> *Amended by SCP 2026-11-10: Mastered also requires Completed. Implemented in Story 12.12.*

**Story 7.8 — Refs/dependency:** add "Depends on Stories 12.11 and 12.12 (Epic 9 seams and 2-look rules, SCP 2026-11-10)."

**New stories (Epic 12):**

### Story 12.11: Sessions end when practice closes or progress resets

As a Learner,
I want closing practice and resetting progress to really end what I was doing,
So that Lessons teach me properly and locked content stays locked.

**Refs:** FR-17, FR-19, FR-4 · AD-3, AD-10 · Epic 9 retro items 1–3 · SCP 2026-11-10

**Acceptance Criteria:**

**Given** LL practice (live, or restored on cold start) or any other practice
**When** the Learner closes it and opens a Course Lesson
**Then** the Lesson runs as `LESSON` with its Teaching Modes, and meeting its predicate writes `LessonCompleted`

**Given** a live Lesson, 2-look or practice session
**When** the Learner confirms *Reset progress*
**Then** the session ends, its snapshot is deleted, the Continue card offers nothing locked by the reset, and a cold start restores nothing

**Given** Algorithm Statuses not yet loaded, or the status stream failed
**When** a 2-look Lesson ends
**Then** the "Add these algorithms to Learning?" offer is not shown, and accepting it never changes an Algorithm whose current status is not *Not started*

**Given** the status repository's upstream fails
**When** the Library is open
**Then** the failure is surfaced or retried; the stream does not silently end as "all *Not started*"

### Story 12.12: 2-look rules reconciled

As a Learner,
I want *Mastered* to mean I've finished and proven 2-look,
So that the mini-Course status is honest.

**Refs:** FR-17 · AD-19, AD-6 · Epic 9 retro items 4–7 · epic-2-retro-item-3 · SCP 2026-11-10

**Acceptance Criteria:**

**Given** two qualifying LL practice solves but not every 2-look Lesson completed
**When** progress is read
**Then** the row shows *Completed* or *In progress* with "Mastery: 2 of 2", not *Mastered*, and no milestone plays

**Given** that state
**When** the last 2-look Lesson is completed
**Then** the row becomes ◆ *Mastered* and the milestone plays once

**Given** every 2-look Lesson's Demonstration
**When** content tests run
**Then** `TwoLookSolve` scores it 0 off-path Moves, and its end state satisfies the Lesson's `LastLayerMatcher`-built predicate

**Given** the shell picks each 2-look Lesson's recognition diagram
**When** shell tests run
**Then** for at least one OLL and one PLL Lesson it equals `RecognitionDiagram.derive` of the Lesson's first referenced Algorithm

**Given** 2-look Learner copy
**When** the Learner-text gate runs
**Then** the 2-look strings come from `en.json` keys, and a new Learner string in `strings.xml` fails the gate

**Given** `TRANSITIONS.md`
**When** it is read
**Then** it describes the LL-practice `practiceEntry` snapshot field and restore

### 4.5 Sprint status (`sprint-status.yaml`)

- Add `12-11-sessions-end-when-practice-closes-or-progress-resets: backlog` and `12-12-2-look-rules-reconciled: backlog` under Epic 12; set `epic-12` to `in-progress` if it is not already.
- Action items:
  - `epic-9-retro-item-40` … `-42` → Story 12.11; `-43` … `-45` → Story 12.12. Set each to `status: in-progress`.
  - `epic-9-retro-item-46` (spec reconciliation): `done` with this proposal's merge. The code half (the cross-check test) is in Story 12.12.
  - `epic-9-retro-item-47` (route stranded deferrals): `in-progress`. This proposal routes the Epic 9 deferrals below; having the orchestrator copy spec `deferred:` entries at story close is still open.
  - Route the stranded Epic 9 deferrals (retro item 8) as new `open` action items, owner Dev:
    - per-Case family membership pinned only by counts, and no Case-identity oracle independent of Notation (9.1/9.2);
    - `Net2DRenderer` and `StickerReviewNet` mirror under RTL (9.3, medium; fold into Story 12.1 accessibility);
    - rotation regenerates a Course practice scramble (9.8, medium);
    - physical Stage mastery plays no milestone (9.8, low);
    - in physical mode the 2-look result line shows before "Yes, it's solved" (9.8 gate, medium, `LessonScreen.kt:353`).
- Retro item 9 (epic-boundary review before the last story merges) is an orchestrator process change and stays with the orchestrator, not this proposal.

## 5. Implementation Handoff

- **Scope:** Moderate. The backlog grows by two stories, and planning text changes in four artifacts.
- **PO (hebelmx):** approve this proposal. On approval, the edits in 4.1–4.5 are applied on branch `docs/scp-2026-11-10-two-look-rules` and merged by PR.
- **Dev, through `/bmad-orchestrate`:** run 12.11, then 12.12 (name them explicitly; the recommender points at device-gated 1.11), then the remaining leftovers, then 7.8.
- **Success criteria:**
  - 12.11 and 12.12 merged with green CI and a passing acceptance gate.
  - Epic 9 retro items 1–7 closed in `sprint-status.yaml`.
  - The 7.8 pilot build starts with no open Epic 9 pre-pilot item.

## Checklist record

| Section | Status | Note |
| --- | --- | --- |
| 1. Trigger and context | [x] | Epic 9 retrospective, items 1–8 and open questions; evidence in the retro, re-checked against source there |
| 2. Epic impact | [x] | Epic 9 stays closed; Epic 12 gains 12.11, 12.12; 7.8 depends on both |
| 3. Artifact conflicts | [x] | PRD FR-17/FR-19, AD-19, AD-10, EXPERIENCE.md, epics |
| 4. Path forward | [x] | Direct Adjustment chosen; rollback and MVP review not needed |
| 5. Proposal components | [x] | Sections 1–5 |
| 6. Final review and handoff | [x] | Approved by the PO 2026-11-10; edits applied on `docs/scp-2026-11-10-two-look-rules` |
