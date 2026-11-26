---
title: 'Sprint Change Proposal: continuous Quick Scan enhancement (10.36) and action-item sweep (12.18) before 10.11'
date: 2026-11-25
status: approved
mode: batch
trigger: PO request on 2026-11-25 while reviewing the pending stories
scope: Moderate
---

# Sprint Change Proposal: continuous Quick Scan enhancement and action-item sweep before 10.11

**Approved by the product owner on 25 November 2026. Planning only; no code changes.**

## 1. Issue summary

On 2026-11-25 six stories were pending: 10.32 → 10.33 → 10.11 (the `0.11.0` camera release), the recorder follow-ups 10.34 and 10.35, and 12.5. The PO asked for two changes:

1. **Learn from real recordings before raising `QUICK_SCAN`.** Once the continuous recorder (Story 10.34) exists, record new scrambles with the usual fixture cubes (`stla`, `stlb`, `stka`, `stkb`), analyse the recorded data, and improve continuous Quick Scan. Story 10.11 is queued after this work. It is **not** a hard dependency. Whether 10.11 has to wait depends on the continuous Quick Scan results.
2. **Don't leave open action items behind.** The sprint record holds **199** open or in-progress `action_items` (78 Epic 10, 25 Epic 12, 24 Epic 11, 20 Epic 15, 17 Epic 14, 35 across Epics 1–9 and 13; 63 owned by the PO). They have to be reviewed **before** Story 10.11.

**Evidence.** Story 10.33 measures the pipeline live from the Solve tab, but a live session can't be replayed. Story 10.34 (SCP 2026-11-25 continuous-fixture-recorder) adds replayable capture but is limited to "the examples needed to verify the migration". Until now no story turns a curated recorded corpus into pipeline improvements. Several open items already point at continuous-scan weaknesses that a corpus would reproduce: `epic-10-deferred-10.31-lenovo-p50-under-1ms-margin` (Lenovo p50 49.36 ms against a 50 ms budget) and `epic-10-deferred-10.31-sampler-drops-faces-at-frame-edge`.

## 2. Impact analysis

| Checklist area | Status | Finding |
| --- | --- | --- |
| 1 Trigger and context | [x] | New requirement from the PO; evidence above. |
| 2 Epic impact | [x] | Epic 10 gains Story 10.36 and a new order. Epic 12 gains Story 12.18. No epic is invalidated. |
| 3.1 PRD | [N/A] | FR-22, the recognition NFRs and the gate-6 wording stand. The PO's level call stays in the PRD `.memlog.md`. |
| 3.2 Architecture | [N/A] | 10.36 uses Story 10.34's AD-9 Frames amendment and its replay placement. A retrained or replaced model is out of scope without PO approval in the spec. |
| 3.3 UX | [N/A] | Viewfinder-guidance enhancements stay within the existing EXPERIENCE.md Scan rows. A change outside them goes through a UX pass in the 10.36 spec. |
| 3.4 Other artifacts | [x] | `epics.md` and `sprint-status.yaml` are edited by this proposal. 10.36 adds `docs/evidence/10-36/` and `Fixtures/` groups; 12.18 adds `docs/evidence/12-18/action-item-triage.md`. |
| 4 Path forward | [x] | Direct adjustment. No rollback, no MVP cut. |

**Story impact**
- **10.11:** queued after 10.36 and 12.18. 12.18 is a hard dependency. 10.36 is not: when 10.33 calls `QUICK_SCAN` BETA, the PO may start 10.11 before 10.36 is done; when 10.33 calls OFF, or the PO defers the call, the `QUICK_SCAN` level comes from 10.36's results.
- **10.33, 10.34, 10.35:** annotations only (10.33 is 10.36's baseline; 10.34 is its dependency; 10.35 runs before it).
- **New 10.36** and **new 12.18** (§4).

**New order:** 10.32 → 10.33 → 10.34 → 10.35 → 10.36 → 10.11 → 12.5, with 12.18 running beside them and finishing before 10.11 starts.

## 3. Recommended approach

**Direct adjustment.** Add two stories and annotate four. Release timing still follows the existing fallback in SCP 2026-11-24 camera-release-timeline: if `QUICK_SCAN` is not ready, `0.11.0` raises `CAMERA_SCAN` and `PLACEMENT` only, and `QUICK_SCAN` follows in a 10.11-n.

**Effort.** 10.36 is medium: one recording session plus analysis, then one enhancement cycle for each failure class the PO picks. 12.18 is medium as triage (199 items). Fixes it marks release-blocking are routed as issue fixes and sized separately.

**Risks**
- **12.18 becomes the long pole for 10.11.** Mitigation: triage starts now and runs in parallel. Only *release-blocking* items have to be fixed or explicitly accepted by the PO before 10.11. Everything else is deferred to a GitHub issue or dropped.
- **Overfitting to the corpus.** Mitigation: whole-corpus replay is the regression gate, and a BETA call that rests on post-10.33 enhancements needs at least 5 live Lenovo scans on the main app build.
- **Fixture size.** `Fixtures/` is 343 MB with no LFS. 10.36 stays within the limit Story 10.34 sets.

## 4. Detailed change proposals (applied to `epics.md` and `sprint-status.yaml` in this branch)

**Epic 10 header, Order line.** Append: *(SCP 2026-11-25 continuous-scan-enhancement: the order is now 10.32 → 10.33 → 10.34 → 10.35 → 10.36 → 10.11 …; Story 12.18 is a hard dependency of 10.11.)*

**Story 10.11, Depends on.** Prepend the annotation: queued after Stories 10.36 and 12.18; 12.18 is a hard dependency; 10.36 is not (BETA from 10.33 lets 10.11 start; OFF or deferred → decided by 10.36 AC5; raise in `0.11.0` or leave to a 10.11-n).

**Story 10.33.** Append: 10.36 follows 10.35 and uses this report as its baseline; a BETA call lets 10.11 start without waiting for 10.36.

**Story 10.34, Depends on.** Append: Story 10.36 depends on it; 10.11 waits on 10.34 only through 10.36, and only when 10.33 does not call BETA. (Its "gates neither Story 10.11 nor any tag" stays true as a direct dependency.)

**Story 10.35, Depends on.** Append: runs before Story 10.36.

**New Story 10.36:** *Record new scrambles on the continuous recorder, analyse them, and improve continuous Quick Scan*. Five ACs: (1) ≥ 3 scrambles × 4 cubes × 2 devices, labels taken from the scramble, warm light plus one hard light, failures kept; (2) replay analysis in `docs/evidence/10-36/analysis.md` against the 10.33 baseline, failure classes ranked; (3) each enhancement reproduced by a failing replay first, whole-corpus no-regression, confidently wrong ≤ 0.1%, Lenovo p50 ≤ 50 ms, size < 30 MB, no model retrain without PO approval; (4) reviewed as product code; (5) the PO's `QUICK_SCAN` call recorded, backed by ≥ 5 live Lenovo scans if it rests on new enhancements.

**New Story 12.18:** *Settle every open action item before the camera release*. Four ACs: (1) every non-done item gets one outcome (already done / release-blocking / deferred to an issue / dropped) in `docs/evidence/12-18/action-item-triage.md`; (2) release-blocking items are fixed or explicitly accepted by the PO before 10.11; (3) PO items are confirmed by the PO, and items that need a device are grouped into one session list; (4) `sprint-status.yaml` is updated, with duplicates merged.

**sprint-status.yaml.** Add `10-36-record-new-scrambles-on-the-continuous-recorder-analyse-the: backlog` after 10-35 and `12-18-settle-every-open-action-item-before-the-camera-release: backlog` after 12-17.

## 5. Implementation handoff

**Scope: Moderate** (backlog reorganisation).
- **PO (hebelmx):** approves this proposal; runs the 10.36 recording session and makes its `QUICK_SCAN` call; confirms the outcomes of the 63 PO-owned items in 12.18.
- **SM / orchestrator:** schedules 12.18 now (device-free triage) and 10.36 after 10.35; keeps 10.11 blocked until 12.18 is done.
- **Dev:** builds the 10.36 replay analysis and the enhancements, and the fixes routed from 12.18.

**Success criteria:** 12.18's triage file covers every open item and none is left without an outcome; 10.36's analysis and enhancements are merged with the corpus replay green; the PRD `.memlog.md` records the `QUICK_SCAN` level before 10.11's release-prep PR.
