# Sprint Change Proposal — Recognize uses two opposite corners; Placement and Recognize copy match what shipped

- **Date:** 2026-11-18
- **Trigger:** The Epic 11 retrospective (`implementation-artifacts/epic-11-retro-2026-11-18.md`, action item A2). The PO made decisions during the Story 11.2 and 11.3 builds (2026-11-17/18), and they differ from the planning text.
- **Mode:** Batch (PO choice)
- **Status:** Approved by the PO on 2026-11-18, with five review refinements folded in (the full no-fit quote in Flow 5; the non-white-cross Stage 1 suggestion and the 2-look-choice skip in Placement outcomes; the UJ-5 Climax and the FR-28 first sentence; all three hold re-prompts; the UX-DR25 summary)
- **Scope:** Minor. The PRD, epics and UX wording change to match the merged code (#189, #191). No story is added or removed, and no code changes.

## 1. Issue Summary

PRD FR-28, epics.md (the FR-28 inventory line and Story 11.3 AC 1) and EXPERIENCE.md Flow 5 all say Case recognition uses **one corner capture** (U plus two side faces).

While Story 11.3 was being built, the PO decided otherwise (ledger, 2026-11-18). Recognition **always takes two opposite top-corner captures, with yellow on top**, so all four sides' lower rows are checked before a Case is named. The candidate Cases from the captures are **intersected**.

One corner cannot show whether the first two layers are solved on the two hidden sides, so a single capture could name a Case for a cube that isn't at the last layer. Story 11.3 shipped the two-corner rule (#191, `f70bbdf`).

The PO's other decisions from the same builds aren't in the UX text either:
- the Recognize result states (no fit, last layer already solved), calibration first, and the manual route;
- the Placement copy: the plain "Start at *Stage*?", the *Faster last layer* skip record, the editor fallback, and the Stage list;
- the OLL family labels, now "P-shape", "T-shape" and so on.

## 2. Impact Analysis

| Area | Impact |
|---|---|
| PRD | FR-28's capture sentence and one consequence change. UJ-5's path and UJ-4's suggestion line change. One §10 decision line is added. MVP scope doesn't change. |
| Epics | Epic 11 only: the FR-28 inventory line and Story 11.3 AC 1 change, and two ACs are added to record the shipped states. Story 11.3 is already `done`, so the added ACs describe merged behavior and create no new work. Story 11.2's ACs already point to the EXPERIENCE.md Placement-outcome copy, so they don't change. |
| Architecture | None. AD-9 (neutral samples in, validated observations out) already covers two captures. `LastLayerMatcher` and `RecognizeCase` are unchanged. |
| UX | EXPERIENCE.md: the Library and Placement surface rows, a new *Recognize capture* component row, the Placement-outcome row, two new Recognize state rows, Flow 4 step 2, and Flow 5 (step 1 and the failure paths). DESIGN.md: none, since it doesn't name families or Recognize copy. |
| Code | None. The shipped strings already match: `msg.recognize.*`, `msg.placement.*` and `msg.library.family.*` in `en.json`. |
| Sprint status | None. The story set doesn't change. Retro item A2 closes once this merges. |

### Checklist summary

- [x] 1.1 Trigger: the Epic 11 retrospective, action item A2. It covers Stories 11.2 and 11.3.
- [x] 1.2 Problem type: the requirement text describes an approach the PO replaced during the build, because one corner can't check the hidden sides' first two layers.
- [x] 1.3 Evidence: ledger DECISION lines for 11.2 and 11.3 (2026-11-17/18); `en.json` keys `msg.recognize.hold-first`, `hold-second`, `no-fit` and `solved`; spec-11-3 and spec-11-2.
- [x] 2.1–2.5 Epic impact: Epic 11 is closed (#192). No epic is added, removed or resequenced. Story 10.11's camera checklist is unaffected.
- [x] 3.1 PRD: FR-28, UJ-4, UJ-5 and §10. [N/A] 3.2 Architecture. [x] 3.3 UX: EXPERIENCE.md. [N/A] 3.4 Other artifacts: `post-launch/v1.1-camera-recognition.md` and the architecture/UX review files are historical records and are left as they are.
- [x] 4 Path: Direct Adjustment.
- [x] 5 Proposal components: §4.
- [x] 6 Final review: approved by the PO on 2026-11-18, with five refinements.

## 3. Recommended Approach

**Direct Adjustment.** Edit the wording so the plan matches the PO's decisions and the merged code. Effort is about 15 minutes and the risk is nil, because the docs follow shipped behavior and nothing has to be rebuilt.

## 4. Detailed Change Proposals

### PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

**P1. FR-28, description**

OLD:
> The Learner can scan the top layer, and the app names its OLL or PLL Case, opens the Algorithm in the Algorithm Library and plays its Demonstration. The scan is one corner-angle capture showing the U face and two side faces. Realizes UJ-5.

NEW:
> The Learner can scan the top layer, and the app names its OLL or PLL Case; tapping the result opens the Algorithm in the Algorithm Library and plays its Demonstration. The scan is two captures of opposite top corners, yellow on top, each showing the U face and two side faces; together they show every side, so the first two layers are checked before a Case is named (SCP 2026-11-18). Realizes UJ-5.

Rationale: this is the PO decision for 11.3. One corner can't show the hidden sides.

**P2. FR-28, ambiguity consequence**

OLD:
> - If the visible stickers fit more than one Case, the app lists the candidates or asks for another angle. It never picks one silently.

NEW:
> - The candidate Cases from each capture are intersected. If more than one Case remains, the app lists the candidates and offers another angle, which intersects again. It never picks one silently. If no Case remains, the app says it couldn't match the cube to a Case.

**P3. UJ-5 path**

OLD:
> - **Path:** he solves the first two layers, opens *Recognize case*, and photographs the cube from a top corner.

NEW:
> - **Path:** he solves the first two layers, opens *Recognize case*, and photographs the cube from one top corner, then from the opposite one.

And the Climax:

OLD:
> - **Climax:** the app reports "OLL 45 (T-shape family)," opens that Algorithm in the Algorithm Library, and plays its Demonstration.

NEW:
> - **Climax:** the app reports "OLL 45 · T-shape". He taps it, and the app opens that Algorithm in the Algorithm Library and plays its Demonstration.

**P4. UJ-4 path, step 2**

OLD:
> 2. The tutor evaluates each Stage Predicate on the scanned state and suggests: "Your cross is done and 2 of 4 corners are in. Let's continue with *First-layer corners*."

NEW:
> 2. The tutor evaluates each Stage Predicate on the scanned state and suggests: "Start at *First-layer corners*?"

Rationale: the PO chose the plain suggestion with no per-Stage progress sentence (11.2, 2026-11-17).

**P5. §10, new decision line**

NEW (append):
> - 2026-11-18: Case recognition takes two opposite top-corner captures, with yellow on top, and intersects their candidate Cases. A Placement suggestion reads "Start at *Stage*?" with no progress sentence (SCP 2026-11-18 recognize-two-corners).

### Epics (`epics.md`)

**E1. Requirements inventory, FR-28 (line 64)**

OLD:
> FR-28: Recognize an OLL/PLL Case from one corner capture (U + two sides): compare against every Case's derived pattern under AUF; ambiguous → list candidates or ask for another angle; F2L not solved → say so and offer Placement; offer (never force) an Algorithm Status change.

NEW:
> FR-28: Recognize an OLL/PLL Case from two opposite top-corner captures (yellow on top; U + two sides each): compare against every Case's derived pattern under AUF and intersect the captures' candidates; ambiguous → list candidates and offer another angle; no fit → say so; F2L not solved → say so and offer Placement; offer (never force) an Algorithm Status change.

**E2. Story 11.3, AC 1**

OLD:
> **When** Library → *Recognize case* captures one top-corner view (U + two sides)
> **Then** `RecognizeCase` matches the visible stickers through `LastLayerMatcher` under AUF and returns the Case within 1 s

NEW:
> **When** Library → *Recognize case* captures two opposite top-corner views, with yellow on top (U + two sides each; "1 / 2") *(SCP 2026-11-18)*
> **Then** `RecognizeCase` matches the visible stickers through `LastLayerMatcher` under AUF, intersects the two captures' candidates, and returns the Case within 1 s

**E3. Story 11.3, new ACs, appended after the "first two layers are unsolved" AC**

NEW:
> **Given** no Case fits, or the last layer is already solved *(SCP 2026-11-18)*
> **When** recognized
> **Then** "I couldn't match this to a case. Try another angle or better light." shows with *Scan another angle* and *Browse the Library*, or "Your last layer is already solved." shows with *Back to Library*
>
> **Given** no saved Color Calibration, a camera denial, or *Enter colors instead* *(SCP 2026-11-18)*
> **When** Recognize opens
> **Then** Color Calibration runs once and returns to the capture (closing it unsaved returns to the Library), or the Manual Cube Editor opens and its accepted cube goes through the same matching

**E4. UX-DR25 summary (line 138)**

OLD:
> … Placement outcomes, Recognize not-at-LL/ambiguous, filter empty, progress reset).

NEW:
> … Placement outcomes, Recognize not-at-LL/ambiguous/no-fit/last-layer-solved, filter empty, progress reset).

### UX (`ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md`)

**U1. Surfaces, Library row (line 38)**

OLD:
> 78 Algorithms grouped by OLL and PLL family, with filters by family and Algorithm Status.

NEW:
> 78 Algorithms grouped by OLL and PLL family, with filters by family and Algorithm Status. The letter families use shape names (*P-shape*, *T-shape*, *C-shape*, *W-shape*, *L-shape*), the same names the Recognize result uses.

**U2. Surfaces, Recognize case row (line 40)**

OLD:
> Scan the top layer and get the Case, or candidates.

NEW:
> Capture two opposite top corners, with yellow on top, and get the Case or candidates.

**U3. Components, new row after *Quick Scan capture* (line 100)**

NEW:
> | Recognize capture | Recognize | Hold yellow on top with one corner facing you, then turn the cube halfway for the opposite corner (the counter shows "1 / 2"). A non-yellow top or a wrong second corner re-prompts that hold ("Turn the cube so yellow is on top.", "Keep yellow on top.", "That looks like the same corner…"). Colors come from the saved Color Calibration; without one, calibration runs once first. A sticker read as uncertain counts as unseen, which can only widen the candidates. Results show only after both corners. |

**U4. States, Placement outcomes (line 127)**

OLD (in the cell):
> Cross or more done but a non-white cross: "Start with a white cross for this course."

NEW:
> Cross or more done but a non-white cross: "Start with a white cross for this course.", suggesting Stage 1, with *Choose another Stage*.

NEW (appended to the existing cell):
> A suggestion reads "Start at *Stage*?". Accepting *Faster last layer*, or choosing a 2-look Stage, marks Stages 1–7 *skipped by placement*. *Choose another Stage* lists Stages 1–7 and the 2-look Stages. *Enter colors instead* or a camera denial opens the Manual Cube Editor, and its accepted cube is placed like a scanned one (SCP 2026-11-18).

**U5. States, Recognize rows (lines 139–140), with two rows added and the ambiguous row amended**

NEW:
> | Recognize: ambiguous | Recognize | "This could be one of 3 cases" with tiles, plus *Scan another angle* (one more corner; the candidates are intersected again). |
> | Recognize: no fit | Recognize | "I couldn't match this to a case. Try another angle or better light." Offers *Scan another angle* (both corners again, fresh) and *Browse the Library*. |
> | Recognize: last layer solved | Recognize | "Your last layer is already solved." Offers *Back to Library*. |

**U6. Flow 4, step 2**

OLD:
> 2. Placement: "Your cross is done and 2 of 4 corners are in. Start at *First-layer corners*?" He can also tap *Choose another Stage*, but he accepts. Stages 1–2 show ◇ *Skipped by placement*.

NEW:
> 2. Placement: "Start at *First-layer corners*?" He can also tap *Choose another Stage*, but he accepts. Stages 1–2 show ◇ *Skipped by placement*.

**U7. Flow 5, step 1, step 2 and failure paths**

OLD:
> 1. Library → **Recognize case** (BETA) → one top-corner photo.
> 2. **Climax:** "OLL 45 · T-shape". The Algorithm detail opens with its Demonstration playing.

NEW:
> 1. Library → **Recognize case** (BETA) → a photo of one top corner, then of the opposite corner, with yellow on top ("1 / 2").
> 2. **Climax:** "OLL 45 · T-shape". He taps it, and the Algorithm detail opens with its Demonstration playing.

Failure paths, added:
> - If no Case fits, he sees "I couldn't match this to a case. Try another angle or better light." with *Scan another angle* and *Browse the Library*.

## 5. Implementation Handoff

- **Scope:** Minor.
- **Who:** the Developer applies the edits in §4 on `docs/scp-2026-11-18-recognize-two-corners`. They also add memlog lines to the PRD and UX `.memlog.md` files, then open a PR, wait for CI and merge.
- **Success criteria:**
  - No planning artifact (PRD, epics.md, EXPERIENCE.md) still describes Recognize as a single corner capture.
  - The Flow 4 and UJ-4 suggestion text matches `msg.placement.suggest`.
  - Retro action item A2 (epic-11-retro) can be closed.
