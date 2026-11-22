# Sprint Change Proposal: Epic 15 acceptance-criteria wording (2026-11-22)

- **Trigger:** Epic 15 retrospective (`implementation-artifacts/epic-15-retro-2026-11-22.md`, findings A5, A6, R6; action item 3) and the open sprint-status item `epic-15-deferred-15.1-ac1-wording-ad-21`
- **Mode:** Batch
- **Scope:** Minor (planning text only; no code change)
- **Product owner decisions:** hebelmx, 2026-11-22 (listed in section 3)

## 1. Issue summary

Epic 15 (Method-aware solutions; Stories 15.1, 15.2, 15.3) is merged and done (PRs #285, #287, #288). The retrospective found that three acceptance criteria in `epics.md` describe behaviour the product owner changed during the build, so the plan no longer matches the as-built and accepted product:

1. **Story 15.1 AC 1 (retro A5).** It says all five beginner segments, last layer included, come from the bounded `preservedPieces` search. Binding AD-21 (`ARCHITECTURE-SPINE.md`) says only the first-two-layer segments do; last-layer segments use `LastLayerMatcher` (AD-19) with the Stages 5–6 Lessons' Algorithms. The as-built follows AD-21. PO decision 2026-11-22: AD-21 wins; amend the AC. Already tracked as `epic-15-deferred-15.1-ac1-wording-ad-21` and flagged in `epic-15-context.md` ("Point to reconcile").
2. **Story 15.3 AC 1 (retro A6).** It says the suggested method is "preselected". PO answer 1 during 15.3 (2026-11-22): *Shortest* stays selected and plays; the suggested method's chip carries a "Suggested" badge, and one tap starts it (`spec-15-3` line 35; triage row "AC1 'preselected' literal"; `SolutionStyles.kt` `suggestedStyle`).
3. **Story 15.2 AC 1 and AC 2 (retro R6).** The retro accepts as-is two labelling behaviours that follow 15.2 PO clarifications 1–2 (2026-11-22) but are not in the AC: one leading Cube Rotation folded into the first Case segment (`z2` for the usual hold), with each Algorithm played as written plus U adjustments; the final AUF appended to the last Case segment, and a lone AUF tagged with the method's last-layer Stage. AC 2 ("only Algorithms of the chosen set plus U adjustments") also omits the leading rotation the corpus check allows (`spec-15-2` AC 2).

Evidence: retro rows A5, A6, R6; `spec-15-1` Residual risks; `spec-15-2` "PO clarifications (2026-11-22)" and triage rows on rotation and lone AUF; `spec-15-3` PO answer 1.

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
|---|---|---|---|
| 1.1–1.3 | Trigger understood, evidence | [x] | Retro A5/A6/R6, spec triage logs, PO decisions 2026-11-22 |
| 2.1 | Current epic | [x] | Epic 15 is done; its stories stay done. Only AC wording changes. |
| 2.2 | Epic-level changes | [x] | Amend AC text of Stories 15.1 (AC 1, Refs), 15.2 (AC 1, AC 2), 15.3 (AC 1). |
| 2.3–2.5 | Future epics, order | [N/A] | No other epic depends on this wording; no resequencing. |
| 3.1 | PRD | [N/A] | FR-30 already says the method is "suggested from the Learner's progress, and the Learner can change it" and the Solve tab keeps the optimal solution as default; the badge satisfies it. No edit. |
| 3.2 | Architecture | [N/A] | AD-21 is the source of truth here and already says what the amended 15.1 AC says. No edit. |
| 3.3 | UX | [N/A] | EXPERIENCE.md (Solution Playback row) calls *Shortest* the default and does not say "preselected"; no conflict. |
| 3.4 | Other artifacts | [N/A] | No code, test, CI or content change. `epic-15-context.md` "Point to reconcile" is a historical implementation artifact and is left as is. |
| 4.1 | Direct adjustment | Viable | Effort low, risk low |
| 4.2 | Rollback | Not viable | The as-built behaviour is the PO's choice. |
| 4.3 | MVP review | Not viable | MVP unaffected. |
| 6.4 | sprint-status.yaml | [!] | Not edited by this run; see section 5. |

### Epic, story and artifact impact

- **Epic 15:** wording of four AC lines and one Refs line in `epics.md`. Stories remain `done`.
- **PRD, architecture, UX:** no change.
- **Technical impact:** none. No code change follows.

## 3. Recommended approach

**Direct adjustment.** Align the AC wording with AD-21 and the product owner's 2026-11-22 decisions. Effort: low. Risk: low. Timeline impact: none.

Product owner decisions (hebelmx, 2026-11-22):

1. 15.1: AD-21 wins. Stages 2–4 come from the `preservedPieces` search; Stages 5–6 from `LastLayerMatcher` with the Stages 5–6 Lessons' Algorithms; never `CubeSolver`.
2. 15.3: *Shortest* stays selected and plays; the suggested method's chip carries a "Suggested" badge, and one tap starts it.
3. 15.2 (clarifications 1–2): one leading Cube Rotation (`z2` for the usual hold), then each Algorithm as written with U adjustments; the final AUF joins the last Case segment; a lone AUF is one segment tagged with the method's last-layer Stage.

Considered and not added: 15.2 clarification 3 (*Learning*/*Learned* beats length). The retro does not name it as a drift, and AC 1's "when more than one fits, an Algorithm with status *Learning* or *Learned* wins" already states it.

## 4. Detailed change proposals (applied)

### 4.1 Stories: `epics.md`

**Story 15.1, Refs (line 3478)**

OLD: `**Refs:** FR-30, FR-15, FR-4 · AD-6, AD-8, AD-21 · NFR-3, NFR-12`

NEW: `**Refs:** FR-30, FR-15, FR-4 · AD-6, AD-8, AD-19, AD-21 · NFR-3, NFR-12`

Rationale: the amended AC 1 names `LastLayerMatcher` (AD-19).

**Story 15.1, AC 1 (line 3484)**

OLD:
> **Then** it returns segments tagged with Course Stage IDs in order (cross, first-layer corners, middle layer, last-layer orientation, last-layer completion), each found by the bounded `preservedPieces` search (the `RecoveryPlanner` search) against that Stage's completion predicate, never by calling `CubeSolver` (AD-8, AD-21)

NEW:
> **Then** it returns segments tagged with Course Stage IDs in order (cross, first-layer corners, middle layer, last-layer orientation, last-layer completion), never by calling `CubeSolver` (AD-8, AD-21): the first-two-layer segments (Stages 2–4) are each found by the bounded `preservedPieces` search (the `RecoveryPlanner` search) against that Stage's completion predicate, and the last-layer segments (Stages 5–6) use `LastLayerMatcher` (AD-19) with the Stages 5–6 Lessons' Algorithms *(SCP 2026-11-22 epic-15-ac-wording: aligned with AD-21, PO decision 2026-11-22)*

Rationale: PO decision 1; matches binding AD-21 and the as-built `MethodSolve`.

**Story 15.2, AC 1 (line 3502)**

OLD:
> **Then** the last-layer segments use only Library Algorithms matched through `LastLayerMatcher` (2-look: the 2-look Lessons' Algorithms; full: one per Case), tagged with Case IDs, and when more than one fits, an Algorithm with status *Learning* or *Learned* wins

NEW:
> **Then** the last-layer segments use only Library Algorithms matched through `LastLayerMatcher` (2-look: the 2-look Lessons' Algorithms; full: one per Case), tagged with Case IDs, and when more than one fits, an Algorithm with status *Learning* or *Learned* wins; the first last-layer segment starts with one Cube Rotation that brings yellow to U (`z2` for the usual hold; none when yellow is already on U), then each Algorithm plays exactly as written, with U adjustments; the final AUF joins the last Case segment, and a lone AUF (no Case) is one segment tagged with the method's last-layer Stage *(SCP 2026-11-22 epic-15-ac-wording: 15.2 PO clarifications 1–2, 2026-11-22)*

Rationale: PO decision 3; retro R6 accepted these labels as-is.

**Story 15.2, AC 2 (line 3506)**

OLD: `**Then** every result solves its input and uses only Algorithms of the chosen set plus U adjustments`

NEW: `**Then** every result solves its input and uses only Algorithms of the chosen set plus U adjustments and the one leading Cube Rotation`

Rationale: PO decision 3; matches `spec-15-2` AC 2 and the corpus conformance check.

**Story 15.3, AC 1 (line 3520)**

OLD:
> **Then** it shows *Shortest* (the optimal solution) by default, with a *Solution style* control offering *Beginner*, *2-look* and *Full OLL + PLL*, the suggested method preselected from progress

NEW:
> **Then** *Shortest* (the optimal solution) is selected and plays by default, with a *Solution style* control offering *Beginner*, *2-look* and *Full OLL + PLL*; the method suggested from progress is not preselected: its chip carries a "Suggested" badge, and one tap starts it *(SCP 2026-11-22 epic-15-ac-wording: PO decision 2026-11-22)*

Rationale: PO decision 2; matches the as-built `SolutionStyles.kt` and `spec-15-3` AC 1.

### 4.2 PRD, architecture, UX

No edits (section 2).

## 5. Implementation handoff

- **Scope:** Minor. The edits above are applied in `epics.md` on branch `docs/scp-2026-11-22-epic-15-ac-wording`.
- **Sprint-status (for the orchestrator, not edited here):** this proposal resolves `epic-15-deferred-15.1-ac1-wording-ad-21` and `epic-15-retro-item-209-run-bmad-correct-course-to-amend-epics-m`; mark both `done` with a ref to this file.
- **Success criteria:** Story 15.1 AC 1 names Stages 2–4 by the `preservedPieces` search and Stages 5–6 by `LastLayerMatcher` with the Stages 5–6 Lessons' Algorithms, never `CubeSolver`; Story 15.3 AC 1 keeps *Shortest* selected and playing with a "Suggested" badge on the suggested chip; Story 15.2 AC 1–2 carry the leading rotation and AUF rules.
- **Recipients:** PO (approve and merge the docs PR); no Developer work.
