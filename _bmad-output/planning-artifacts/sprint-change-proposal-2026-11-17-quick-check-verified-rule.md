# Sprint Change Proposal — A Quick Check verifies only a match

- **Date:** 2026-11-17
- **Trigger:** Story 10.6 (*Quick Check evidence and Certainty*) build. The scenario suite found false VERIFIED mismatches from Partial observations (commit `06748d1`, "blocked on false verified").
- **Mode:** Batch (chosen under the PO's delegation; the PO is away)
- **Status:** Approved. The rule was decided by a party panel (PM John, Architect Winston, UX Sally, Dev Amelia, QA Murat) on 2026-11-17 under the PO's delegation, and it is recorded in the orchestrator ledger. Follow-up questions were answered by the same panel (§6). Items for the PO are in §7.
- **Scope:** Minor (wording in the PRD, the spine, epics and UX to match the rule; no story added or removed; no code change in this proposal)

## 1. Issue Summary

PRD FR-26 consequence 3 says the tutor reports *verified* when the observed faces fit exactly one candidate Move sequence, for example "you turned `R` instead of `R'`". The example applies to a Quick Check of a few faces. AD-5's VERIFIED rule (coverage plus exactly one consistent candidate) allowed the same thing.

While building Story 10.6, the random scenario suite showed that errors outside the candidate model made about **0.14%** of Partial VERIFIED *mismatches* false. Two kinds of error did this: an earlier confirmed Move that was done wrong and then followed by more Moves, and three or more unconfirmed Moves. That breaks FR-10 ("never a false *verified*") and the Quick Check recognition NFR ("0 false *verified* … a matching cube is confirmed in ≥ 98%"). The PRD ranks both above FR-26's example.

With the new rule, a Partial verifies only a match. The suite then gives **0 false VERIFIED** and confirms **99.8%** of matching cubes.

**Known residual risk (Story 10.6):** a constructed face-preserving sequence can still make a Partial *match* falsely VERIFIED. Example: `R U2 R'` with plan faces {B, D}. It happened for 1 of 44 such sequences, out of 123,500 sequences tried. It is recorded here and does not block the story (see D3).

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 10 only. Story 10.6 AC 2 is reworded. The FR-26 line in the requirements inventory is reworded. Story 10.8 AC 1 now asks the report to cover the residual risk. Story 10.4 AC 1 gets an annotation (not a scope change). Story 10.7 is unchanged (D4). |
| PRD | FR-26 consequence 3 and a §10 decision line. MVP scope is unchanged. |
| Architecture | AD-5 VERIFIED rule: one added clause. No new AD, port, module or entity. |
| UX | EXPERIENCE.md Flow 1 step 6, Flow 4 step 3, and the mockup index row. The `key-physical-and-scan.html` mockup caption. The Do/Don't row "Verified: you turned R instead of R'" stays, since it still holds after a Guided 6-face Scan. |
| Code | None here. Story 10.6 already implements the rule (`619bbdc`, `af3720c` on `feat/10-6-quick-check-evidence-and-certainty`). |
| Sprint status | No change. The story set is unchanged. |

### Checklist summary

- [x] 1.1 Trigger: Story 10.6 scenario suite.
- [x] 1.2 Problem type: a requirement example that conflicts with a higher-ranked requirement (FR-10 and the Quick Check NFR).
- [x] 1.3 Evidence: §1 (0.14% false VERIFIED mismatches before the rule; 0 after; 99.8% match confirmation).
- [x] 2.1–2.5 Epic impact: Epic 10 can still be completed. No epic is added, removed or resequenced.
- [x] 3.1 PRD: FR-26 amended. [x] 3.2 Architecture: AD-5 amended. [x] 3.3 UX: two flows, one caption. [N/A] 3.4 Other artifacts.
- [x] 4 Path: Direct Adjustment.
- [x] 5 Proposal components: §4.
- [x] 6 Final review: approved under delegation.

## 3. Recommended Approach

**Direct Adjustment.** Make every planning artifact say what Story 10.6 already does: a Partial verifies only `[]`. A Partial mismatch is at most LIKELY, keeps its leading hypothesis, and offers a Guided 6-face Scan, which can verify it.

- **Effort:** documentation only (this branch).
- **Risk:** low. A Learner who made a real mistake sees ≈ *Likely* instead of ✓ *Verified* after a Quick Check, and the recovery prompt still appears. One more scan turns it into Verified.
- **Timeline:** none. Story 10.6 continues on its branch.

## 4. Detailed Change Proposals (all applied)

### 4.1 PRD (prd.md)

**P1 — FR-26 consequence 3.**
OLD: `The tutor reports *verified* only when the observed faces fit exactly one candidate Move sequence from the Expected State (e.g. "you turned R instead of R'"). If several fit, it reports *likely* and offers a Guided 6-face Scan.`
NEW: `The tutor reports *verified* only when the observed faces fit exactly one candidate Move sequence from the Expected State. A Quick Check of only some faces can verify only a match. A mismatch is *verified* only by a Guided 6-face Scan (e.g. "you turned R instead of R'"). When a Quick Check shows a mismatch, or several candidates fit, the tutor reports *likely* and offers a Guided 6-face Scan.` followed by an SCP note.
Rationale: FR-10 and the Quick Check NFR outrank the example.

**P2 — §10 decision line** for 2026-11-17. PRD memlog entries were appended with `memlog.py` (decision, change, event).

### 4.2 Architecture (ARCHITECTURE-SPINE.md, AD-5)

**A1 — VERIFIED rule.** A third clause was added under "`VERIFIED` requires both of", after "exactly one candidate is consistent". It says that for a `Partial`, that candidate is the empty sequence `[]`. A `Partial` verifies only `[]`, and only a `Full` observation verifies a mismatch. A `Partial` mismatch with one consistent candidate is at most `LIKELY`: it moves Sync State to `PHYSICAL_MISMATCH`, keeps the candidate as the leading hypothesis, and offers a Guided 6-face Scan. The architecture memlog entry was appended.

### 4.3 Stories (epics.md)

**E1 — Requirements inventory, FR-26 line.** Adds: "a Quick Check of only some faces verifies only a match (a mismatch is verified only by a Guided 6-face Scan)".

**E2 — Story 10.6 AC 2.**
OLD: `When the observed faces cover every face … and exactly one candidate fits / Then Certainty is VERIFIED; otherwise LIKELY or UNKNOWN, and a scenario suite yields 0 false *verified*`
NEW: the When clause adds "and for a `Partial` that candidate is the empty sequence (a match)". The Then clause adds: "A `Partial` mismatch with one consistent candidate is LIKELY: Sync State still moves to PHYSICAL_MISMATCH, that candidate stays the leading hypothesis, and a Guided 6-face Scan is offered. Only a Full observation verifies a mismatch", with the 0.14% evidence note.

**E3 — Story 10.8 AC 1.** The report also states the Story 10.6 known residual risk (the face-preserving Partial match case), with its measured rate in the Quick Check scenario suite and a recommendation (D3).

**E4 — Story 10.4 AC 1 annotation.** "a first stickerless scan" is annotated with the PO decision of 2026-11-17 (device session B3, PRD memlog). The review offers calibration only on uncertain reads: NeedsCalibration, a guarded center with no saved calibration, or 6 or more blank stickers. A clean scan gets no offer, and Settings → Color calibration is always available. This is not a scope change. It closes Q1 of SCP 2026-11-16 main-app entry points.

**E5 — Story 10.7: no change** (D4).

### 4.4 UX (EXPERIENCE.md, mockup)

**U1 — Flow 1 step 6.** OLD: ✓ *Verified*: "That turn moved the cross." NEW: ≈ *Likely*: "That turn probably moved the cross." The *Undo* / *Guide me back* prompt stays, and *Scan all 6 faces* is added.
**U2 — Flow 4 step 3.** OLD: the Quick Check gives ✓ *Verified* "You turned R instead of R'." NEW: the Quick Check gives ≈ *Likely* "You probably turned R instead of R'"; Marco takes *Scan all 6 faces*, and the Guided 6-face Scan gives ✓ *Verified*.
**U3 — Mockup index row and `key-physical-and-scan.html` caption.** "after a Quick Check" becomes "after a Guided 6-face Scan". The mockup is not regenerated (D2). The UX memlog entry was appended.

## 5. Residual risk register

| ID | Risk | Likelihood | Owner / action |
|---|---|---|---|
| R1 | A constructed face-preserving sequence (e.g. `R U2 R'` with plan faces {B, D}) makes a Partial *match* falsely VERIFIED: 1 of 44 such sequences, out of 123,500 tried | Very low for real Learners. It needs a deliberate sequence that touches faces outside the plan and restores the observed ones. | Story 10.8 reports the measured rate and a recommendation (E3). The PO decides at 10.8/10.11 whether a mitigation story is needed. |

## 6. Decision log

| # | Question | Answer | Decided by |
|---|---|---|---|
| D0 | The rule: may a Partial VERIFY a mismatch? | No. A Partial verifies only `[]`; a Partial mismatch is at most LIKELY and offers a 6-face Scan; only a Full verifies a mismatch. | Party panel 2026-11-17, delegated by the PO (orchestrator ledger) |
| D1 | Mode (incremental or batch) | Batch: approve all edits together. | Delegation (PO instruction) |
| D2 | UX flows and mockup | Rewrite Flow 1 step 6 and Flow 4 step 3 as Likely plus a 6-face offer. Fix only the mockup caption and its index row now; no regeneration. | Party (Sally proposed; John agreed) |
| D3 | Residual risk R1: planning action? | Record it as a known risk, and have Story 10.8's report measure and state it (an AC 1 clause). No new story now. John argued "record only" (real Learners don't construct such sequences). Murat argued the NFR says 0 false *verified* in the suite, so it must be measured. Winston sided with Murat. | Party (Murat, Winston; John's "record only" view noted) |
| D4 | Does Story 10.7 need a change? | No. Its ACs never assumed a Partial VERIFIED mismatch ("a mismatch shows the SEEN strip"); AD-5 and 10.6 AC 2 carry the rule. Sally noted that the Likely-mismatch copy is not pinned beyond the Do/Don't table. Not blocking: the 10.7 builder follows the Certainty wording rule. | Party (Amelia; Sally's note recorded) |
| D5 | Story 10.4 AC wording | Annotate per the PO decision of 2026-11-17 (calibration offer only on uncertain reads; Settings always available). | PO (decided 2026-11-17, PRD memlog) |
| D6 | Scope classification and handoff | Minor: documentation only; the story set and sprint-status are unchanged. | Party (John) |

## 7. Open questions for the PO

1. **R1 mitigation** (not urgent): after Story 10.8 reports the measured rate, decide whether a Partial match also needs a mitigation (for example, also requiring the candidate depth to cover faces outside the plan) or whether BETA labeling is enough.
2. **Likely-mismatch copy** (Sally, D4): confirm or replace the proposed Learner text "That turn probably moved the cross." and the *Scan all 6 faces* action label before Story 10.7.

## 8. Implementation Handoff

- **Scope:** Minor.
- **Now (branch `chore/scp-2026-11-17-quick-check-verified-rule`):** planning edits applied (§4); open a docs PR (orchestrator).
- **Story 10.6** continues on `feat/10-6-quick-check-evidence-and-certainty`; its implementation already matches the amended AC 2.
- **Success criteria:** PRD FR-26, AD-5, Story 10.6 AC 2 and EXPERIENCE.md all say that a Quick Check verifies only a match; Story 10.8's report covers R1.
