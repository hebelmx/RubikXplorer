# Sprint Change Proposal — Orientation copy names the Lesson's hold

- **Date:** 2026-11-04
- **Trigger:** Story 4.2 PO decision (recorded at 4.2 merge, PR #43); Epic 4 retrospective (PR #48) recommends this before Epic 5
- **Mode:** Batch
- **Status:** Approved by PO 2026-11-04
- **Scope:** Minor (doc-only; shipped code and tests already comply)

## 1. Issue Summary

Story 4.2's acceptance criterion and the EXPERIENCE.md error-category copy hard-code a hold of "white on the bottom, green in front". Shipped Lessons are authored in the home frame (AD-2: WHITE on U, GREEN on F). Shown as written, the literal would tell the Learner to hold the cube upside-down relative to every Lesson.

During 4.2 the PO decided that the orientation diagnosis names the Lesson's actual hold. The shipped `msg.diagnosis.unexpected-orientation` key takes `{bottom}` and `{front}` ICU arguments, and Lessons render "yellow on the bottom, green in front" (`LessonScreenTest.kt:739`). `DiagnosisCopyTest` still checks that the old white literal resolves, but only as a template case.

The same issue affects the "Wrong orientation" example "white should face down". The shipped key `msg.diagnosis.wrong-piece-orientation` takes a `{face}` argument. In the white-on-U frame a first-layer corner's white sticker faces **up** (`DiagnosisCopyTest.kt:92`).

**Evidence:** `epics.md:958`, `EXPERIENCE.md:120` (literals), `core/domain/src/main/resources/content/strings/en.json:13,15` (parameterized keys), `DiagnosisCopyTest.kt:46-58,86-99`, `LessonScreenTest.kt:739`.

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 4 is done. Only the Story 4.2 AC text changes; the story stays `done`. No other epic, story or sequencing changes. Epic 5 is unaffected. |
| PRD | No conflict. FR-10 lists "cube held in an unexpected orientation" without copy. |
| Architecture | No conflict. AD-2 already fixes the home frame (WHITE on U, GREEN on F). |
| UX | EXPERIENCE.md State Patterns, "Error category copy" row: two literals become templates with home-frame examples. |
| Code / tests | None required. `en.json` and the tests already implement the decision. Optional cleanup is listed in §5. |

### Checklist summary

- [x] 1 Trigger and context: the 4.2 PO decision; the docs drifted from the shipped behavior.
- [x] 2 Epic impact: Story 4.2 AC text only.
- [x] 3 Artifact conflicts: epics.md and EXPERIENCE.md. PRD [N/A], architecture [N/A].
- [x] 4 Path: Direct Adjustment.
- [x] 5 Proposal components: below.
- [x] 6 Final review and handoff: below.

## 3. Recommended Approach

**Direct Adjustment.** Align the two documents with the shipped, PO-approved behavior.

- Effort: minutes.
- Risk: none. No code changes, and future stories that cite UX-DR25 copy (Epic 5+) inherit the correct template.
- Timeline impact: none. Epic 5 can start right after.

## 4. Detailed Change Proposals

### 4.1 Story 4.2 — Acceptance Criteria (`epics.md:956-958`)

OLD:
```
**Given** an orientation-only difference
**When** it is diagnosed
**Then** it is `UNEXPECTED_ORIENTATION` with "Your cube is turned — hold it with white on the bottom, green in front." and *Show me how*, without undo
```

NEW:
```
**Given** an orientation-only difference
**When** it is diagnosed
**Then** it is `UNEXPECTED_ORIENTATION` with "Your cube is turned — hold it with {bottom} on the bottom, {front} in front.", the colors naming the Lesson's authored hold (for the shipped home-frame Lessons, WHITE on U and GREEN on F: "…hold it with yellow on the bottom, green in front."), and *Show me how*, without undo
```

Rationale: this matches the 4.2 PO decision and the shipped `en.json` key. The literal is now correct for every shipped Lesson.

### 4.2 EXPERIENCE.md — State Patterns, "Error category copy" row (`EXPERIENCE.md:120`)

OLD:
```
**Unexpected orientation:** "Your cube is turned — hold it with white on the bottom, green in front." Offers *Show me how*, no undo. … **Wrong orientation:** "Right corner, but twisted — white should face down." …
```

NEW:
```
**Unexpected orientation:** "Your cube is turned — hold it with {bottom} on the bottom, {front} in front." The colors name the Lesson's hold; for the shipped home-frame Lessons (white on top, green in front) this reads "hold it with yellow on the bottom, green in front." Offers *Show me how*, no undo. … **Wrong orientation:** "Right corner, but twisted — white should face {face}." The face is given in the Lesson's hold, e.g. "white should face up" for a first-layer corner. …
```

The Wrong piece and Invalid entry text in the row is unchanged.

Rationale: both literals assumed a white-on-D hold, which no shipped Lesson uses.

## 5. Implementation Handoff

- **Scope:** Minor. Developer applies §4.1 and §4.2 directly on a `docs/` branch and opens a PR to main.
- **Optional cleanup (dev, non-blocking):** in `DiagnosisCopyTest`, relabel the "EXPERIENCE literal" test names so the asserted examples match the new EXPERIENCE text (yellow/green; face up). Behavior is unchanged.
- **Sprint status:** close the Epic 4 retro action item for the 4.2 orientation copy when the PR merges.
- **Success criteria:** no planning artifact tells the Learner "white on the bottom" or "white should face down" as fixed copy. Story 4.2's AC matches `en.json` and `LessonScreenTest`. Epic 5 starts unblocked.
