---
title: 'Sprint Change Proposal: defer camera recognition (device vision) to v1.1'
date: 2026-11-08
status: approved
approved_by: hebelmx (PO), 2026-11-08
trigger: PO decision 2026-11-08
scope: Moderate
mode: batch
---

# Sprint Change Proposal: defer camera recognition to v1.1

## 1. Issue Summary

**Trigger:** a product-owner decision on 2026-11-08, made after story 8.6 merged (PR #76). The decision is to ship v1.0 without device vision and to add Camera Cube Recognition in a post-launch v1.1.

**Category:** a strategic pivot, backed by a technical constraint found during implementation.

**Problem statement:** the P0 plan puts eight FRs of Camera Cube Recognition (FR-21 – FR-28; Epics 10 and 11, 11 stories) before release. These are the riskiest and least proven parts of the MVP. Without them, v1.0 can ship in about 6–9 working days instead of about 11–17, with a simpler privacy story and no camera permission.

**Evidence:**
- **Recognition spike (Story 1.9, PRD memlog):** 135 of 216 sticker predictions were correct, **62.5%** (range 48.15–72.22%), on the Galaxy S24 FE and Tab S7 FE 5G. The exit criterion was ≥ 99%. Under PRD §6.2 this already set `CAMERA_SCAN` to BETA and **reopened OQ-9**.
- **Progress:** 53 of 81 stories are done in 11 days. Of the 28 left, 11 are Epics 10 and 11, the hardest to estimate (CV under real lighting, perspective correction).
- **Release path:** publishing is now through a verified Google Play organization account, which is not subject to the 12-tester, 14-day closed-test rule for new personal accounts. Camera work becomes the critical path.
- **Code today:** every AD-18 flag is `OFF` in release, the manifest declares no permissions, and no scan or Quick Check code exists. One merged string promises the camera: `msg.physical.first-solve-prompt` (8.6).

## 2. Impact Analysis

### Epic impact
| Epic | Impact |
|---|---|
| 1–8 (done or nearly done) | None. The one exception is a single copy fix from 8.6 (new Story 8.7). |
| 9 Faster last layer and Library | None. Its "recognition diagrams" (9.3) are diagrams derived from state, not camera. |
| **10 Scan my cube** | **Deferred to v1.1** in full (10.1–10.8). |
| **11 Scan shortcuts** | **Deferred to v1.1** in full (11.1–11.3). |
| 12 Release-ready | 12.3 and 12.4 acceptance criteria change: no `CAMERA` permission, and release gate 6 becomes N/A for v1.0. |
| 3 (3.7 Solve tab) | None. The AC already hides *Scan my cube* until Epic 10 lands. |

No epic is invalidated, and no new epic is needed in v1.0. Epic order is unchanged: 9 → leftovers (1.11, 3.7, 3.8, 7.8) → 12.

### Story impact
- **New Story 8.7:** a camera-free Stage 6 prompt, with the camera wording kept behind `CAMERA_SCAN`.
- **12.3 and 12.4:** acceptance criteria amended (§4.4).
- **10.1–10.8 and 11.1–11.3:** moved to `post-launch/v1.1-camera-recognition.md`, with their text unchanged.

### Artifact conflicts
- **PRD:**
  - §6.2 MVP scope and its "Always keep" line;
  - §7 Definition of Done and release gate 6;
  - NFR-5 and NFR-11;
  - OQ-9;
  - §4.8 header;
  - UJ-1, UJ-3, UJ-4, UJ-5 (annotations only);
  - §10.
- **Architecture spine:**
  - AD-13 (no runtime permission in v1.0);
  - AD-9 (status note);
  - the Capability map rows for FR-21 – FR-28;
  - the Deferred list.

  AD-18 itself does not change: the deferral uses it as designed.
- **UX (EXPERIENCE.md):**
  - Flows 1, 3, 4, 5 and 9 (annotations);
  - the IA rows for Scan, Recognize case and Color Calibration;
  - the Voice table row and the State-pattern rows that offer a Quick Check.

  UX-DR19 already defines how an `OFF` flag behaves (the entry point and all cross-links are hidden, with no dead buttons), so no new UX design is needed.
- **Sprint status:** remove the `epic-10*`, `10-*`, `epic-11*` and `11-*` keys (all `backlog`), and add `8-7-*` as `backlog`.
- **Other:** the `AGENTS.md` summary line mentions "on-device camera recognition", so it needs a `bmad-project-context` refresh after merge. CI is unaffected: the AD-13 manifest check already passes with zero permissions.

### Technical impact
Low. The flags stay in the code (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` are `OFF` in release, `GA` in debug), so v1.1 re-enables them per story without restructuring. The only code change for v1.0 is Story 8.7's copy.

## 3. Recommended Approach

**Selected: Option 3, PRD MVP Review (scope reduction), applied as Direct Adjustment.**

| Option | Verdict |
|---|---|
| 1. Direct Adjustment only (keep camera in P0, accept the slip) | Not viable as a goal: it keeps the riskiest 11 stories on the critical path after a 62.5% spike result. |
| 2. Rollback | Not needed: no camera code was ever merged. |
| **3. MVP Review: defer FR-21 – FR-28 to v1.1** | **Viable.** Effort: Low (docs plus one small story). Risk: Low. |

**Rationale:**
- The PRD's own fallback (§6.2: "launch on schedule anyway … recognition improves in P1") anticipated this. The PO now goes one step further: the camera is OFF rather than BETA, so v1.0 ships no half-working feature, which matters for the portfolio's "smooth, correct" first impression (UJ-6).
- Product Principle 3 ("Never claim what you don't know") is fully kept. Physical-cube Mode already works without a camera (Epic 8: *Done*, *I did something else*, the mismatch ladder rungs 2–4, *Yes, it's solved*).

**Effort, risk and timeline:**
- About 6–9 working days to v1.0 (17 stories instead of 28).
- The target is store-ready around late November to early December 2026, instead of mid to late December.

**Trade-offs accepted:**
- UJ-3 (Solve my cube) needs the Manual Cube Editor (54 taps).
- UJ-4 (Placement) and UJ-5 (Case recognition) are unavailable until v1.1.
- The hiring-manager "tries a scan" step (UJ-6) waits for v1.1.

## 4. Detailed Change Proposals

### 4.1 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`, through `bmad-prd` update)

**§6.2 MVP Scope, in-scope list**
```
OLD:
- **Camera Cube Recognition** (FR-21 – FR-28):
  - capture: Guided 6-face Scan and Quick Scan;
  - validation and correction, plus Color Calibration;
  - uses: scan to solve, Quick Check, Placement and Case recognition.
NEW:
(removed from P0, moved to a new "v1.1 (post-launch)" bullet under "Out of scope for MVP":)
- **v1.1 (post-launch): Camera Cube Recognition** (FR-21 – FR-28): Guided 6-face Scan,
  Quick Scan, validation/correction, Color Calibration, scan to solve, Quick Check,
  Placement, Case recognition. Deferred by PO decision 2026-11-08
  (sprint-change-proposal-2026-11-08-vision-deferral); the AD-18 flags stay OFF in v1.0.
```

**§6.2 NOTE FOR PM, "Always keep" line**
```
OLD:
> Always keep: the Course, Physical-cube Mode, the Guided 6-face Scan with scan to solve and Quick Check, and the verified Library animations.
NEW:
> Always keep: the Course, Physical-cube Mode (camera-free confirmation), the Manual Cube Editor with the Solver, and the verified Library animations.
> **2026-11-08:** the spike missed (62.5%), and the PO moved all camera features to v1.1 instead of shipping them as beta (sprint-change-proposal-2026-11-08-vision-deferral).
```

**§7 MVP Definition of Done**
```
OLD:  - scan or enter a cube and solve it with the local Solver;
NEW:  - enter a cube and solve it with the local Solver (scanning arrives in v1.1);
```

**§7 Release gate 6**
```
OLD:
6. Every scan flow has a working manual fallback, and no unvalidated Scan Result is ever used. Camera features ship as GA if they meet their §4.8 targets on the test matrix, otherwise as "beta" (the spike-failure decision, §6.2).
NEW:
6. *(v1.1)* Every scan flow has a working manual fallback, … [unchanged text]. For v1.0 this gate
   is N/A: every camera flag is OFF in release and the manifest declares no CAMERA permission.
```

**§5 NFR-5 and NFR-11**
```
OLD (NFR-5): A fresh install with no network can complete the Course, use the Algorithm Library, scan a cube and solve it. [ASSUMPTION: the MVP declares no network permission; the camera is its only runtime permission.]
NEW (NFR-5): A fresh install with no network can complete the Course, use the Algorithm Library, and enter a cube and solve it (scan as well, from v1.1). v1.0 declares no runtime permissions; from v1.1 the camera is the only one. [ASSUMPTION: no network permission.]
OLD (NFR-11): … including Solver tables and any recognition model …
NEW (NFR-11): … including Solver tables (and, from v1.1, any recognition model) …
```

**§4.8 header** (add one line under the "Camera Cube Recognition" heading)
```
NEW: > **Release: v1.1 (post-launch).** Deferred from P0 on 2026-11-08 (sprint-change-proposal-2026-11-08-vision-deferral). Requirements are unchanged; v1.0 hides every entry point behind the AD-18 flags.
```

**§9 OQ-9**
```
OLD: 9. **OQ-9 (closed 2026-10-30):** the P0 schedule of about 16 weeks is confirmed, and scan uses stay in P0. Reopen it if the Story 1.9 recognition spike misses ≥ 99% per-sticker accuracy.
NEW: 9. **OQ-9 (reopened by the 1.9 spike at 62.5%; closed again 2026-11-08):** the schedule stays within ~16 weeks; all camera features move to v1.1, and v1.0 ships camera-free.
```

**§2.3 User journeys** (annotations only; journeys stay as the product vision)
- **UJ-1:** after step 4, add *"v1.0 (no camera): the tutor goes straight to 'Which turn did you make?' (FR-9 rung 2); the Quick Check rung arrives in v1.1."* In the Climax, add *"v1.0: she confirms with* Yes, it's solved*."*
- **UJ-3:** add *"v1.0: she enters the colors in the Manual Cube Editor; Quick Scan arrives in v1.1."*
- **UJ-4 and UJ-5:** add the tag *"(v1.1)"* to the journey title.
- **UJ-6:** change "and tries a scan" to "and tries a scan (v1.1)".

**§10:** add the line *"Camera Cube Recognition deferred to v1.1 (PO, 2026-11-08)."* Append the same decision to `.memlog.md` through `memlog.py append`.

### 4.2 Architecture spine (`ARCHITECTURE-SPINE.md`, through `bmad-architecture` update)

**AD-13 rule**
```
OLD:  - The merged manifest declares no `INTERNET` permission. `CAMERA` is the only runtime permission.
NEW:  - The merged manifest declares no `INTERNET` permission. v1.0 declares no runtime permission; from v1.1 `CAMERA` is the only one, added by the first camera story.
```

**AD-9** (add a status line under "Binds")
```
NEW:  - **Status:** v1.1. No v1.0 code implements it; the rule binds the first camera story.
```

**Capability → Architecture Map:** add the suffix "(v1.1)" to the rows for FR-21 – FR-24 and FR-25 – FR-28.

**Deferred:** add *"Camera Cube Recognition (AD-9, FR-21 – FR-28) is v1.1. The Quick Scan detection method below is decided then."*

AD-18 is unchanged.

### 4.3 UX (`EXPERIENCE.md`, through `bmad-ux` update)

- **Voice table, line 74:** the do-say becomes *"Your cube should be solved now. Compare it with the picture, then tell me if it is."* (v1.0). Note that the camera variant returns with `CAMERA_SCAN`.
- **IA rows Scan, Recognize case and Color Calibration:** add "(v1.1; hidden while the flag is OFF, UX-DR19)".
- **Solve tab row:** *Enter colors* is the only action in v1.0.
- **State patterns, "Objective met, physical" and "Mismatch ladder":** add "Quick Check offer and rung 1 appear only when `CAMERA_SCAN` ≠ OFF (v1.1)".
- **State patterns, "First solve" (line 124):** add "v1.0: only after *Yes, it's solved*".
- **Flows 1, 3, 4, 5 and 9:** the same v1.0/v1.1 annotations as the PRD journeys.
- **Mockup `key-physical-and-scan.html`:** mark it "v1.1 reference".

### 4.4 Epics (`epics.md`) and the new v1.1 file

**Move** the "## Epic 10" and "## Epic 11" sections (all 11 stories, text unchanged) to `_bmad-output/planning-artifacts/post-launch/v1.1-camera-recognition.md`. Give that file a header citing this proposal.

**Epic List entries for Epic 10 and Epic 11:**
```
NEW (replaces the two entries):
### Epic 10: Scan my cube — **deferred to v1.1** (see post-launch/v1.1-camera-recognition.md)
### Epic 11: Scan shortcuts — **deferred to v1.1** (see post-launch/v1.1-camera-recognition.md)
```

**FR coverage map:**
- Suffix FR-21 – FR-28 with "(v1.1)".
- FR-9: "· Epic 10 (Quick Check rung, v1.1)".
- FR-16: "· Epic 10 (Quick Check in playback, v1.1)".

**Story 12.3, AC 1 and AC 2**
```
OLD: **Then** size is < 30 MB installed, the merged manifest has no `INTERNET` and only `CAMERA`, and no denylisted SDK is present
NEW: **Then** size is < 30 MB installed, the merged manifest has no `INTERNET` and no runtime permissions (v1.0 is camera-free), and no denylisted SDK is present
OLD: **When** the Course, Library, scan and solve are exercised
NEW: **When** the Course, Library, cube entry and solve are exercised
```

**Story 12.4, AC 2**
```
OLD: **Then** gates 1–6 each link to their passing evidence (CI runs, scenario suites, e2e suite, pilot notes, offline/a11y checks, scan GA/BETA decision)
NEW: **Then** gates 1–5 each link to their passing evidence (CI runs, scenario suites, e2e suite, pilot notes, offline/a11y checks), and gate 6 is recorded N/A for v1.0 (camera flags OFF in release, no CAMERA permission)
```

**New Story 8.7: camera-free Stage 6 prompt**
```
As a Learner on a real cube without camera support,
I want the end-of-Stage-6 prompt to ask only for what the app can use,
So that it never promises a camera check that doesn't exist.

Refs: FR-7, FR-26 · UX-DR19, UX-DR20, UX-DR25 · AD-18 · this proposal

AC 1: Given CAMERA_SCAN is OFF (v1.0 release), when Stage 6's Objective is met in Physical-cube Mode,
      then the panel reads "Your cube should be solved now. Compare it with the picture, then tell me if it is."
      and offers Yes, it's solved and I did something else; no camera wording or Quick Check entry appears.
AC 2: Given CAMERA_SCAN is BETA or GA, when the same happens,
      then the current camera wording ("Check it with the camera, or tell me if it is.") shows.
AC 3: The copy lives in content strings (two keys), and the UI string duplicate in strings.xml is removed or aligned (NFR-10);
      ShippedContentTest covers both keys; the 8.6 milestone gating is unchanged.
```

### 4.5 Sprint status (`sprint-status.yaml`)
- Remove `epic-10`, `10-1-…` through `10-8-…`, `epic-10-retrospective`, `epic-11`, `11-1-…` through `11-3-…` and `epic-11-retrospective` (all `backlog`; no progress lost).
- Add `8-7-camera-free-stage-6-prompt: backlog` under Epic 8. `epic-8` stays `in-progress` until 8.7 is done.
- Add a header comment pointing to this proposal and the v1.1 file.

## 5. Implementation Handoff

**Scope: Moderate.** This is a backlog reorganization plus requirement-document updates. No fundamental replan is needed: architecture and UX patterns already support it (AD-18, UX-DR19).

| Who | Does | Done when |
|---|---|---|
| PO / Developer (this workflow) | Writes the v1.1 file, edits epics.md, updates sprint-status.yaml, appends to the PRD memlog. Branch `docs/scp-2026-11-08-vision-deferral`, PR. | Merged with green CI; the sprint planner validates the status file. |
| PM (`bmad-prd` update) | Applies §4.1 to prd.md (status: final, so never hand-edited). | PRD shows camera as v1.1, and OQ-9 is closed again. |
| Architect (`bmad-architecture` update) | Applies §4.2 to the spine. | AD-13, AD-9 and the map are updated. |
| UX (`bmad-ux` update) | Applies §4.3 to EXPERIENCE.md. | Annotations are in. |
| Developer (`bmad-orchestrate`) | Story 8.7, then Epic 9, the leftovers and Epic 12. | 8.7 merged before 7.8's pilot build. |
| After merge | `bmad-project-context` refresh (the AGENTS.md summary mentions camera recognition). | AGENTS.md is accurate. |

**Success criteria:**
- The sprint recommender never proposes a 10.x or 11.x story.
- The release build has no camera wording and no `CAMERA` permission.
- Every planning artifact agrees that camera recognition is v1.1.
- v1.0 needs 17 remaining stories (16 plus 8.7) to reach the store.

## Checklist record

| Item | Status | Note |
|---|---|---|
| 1.1 Trigger | [x] | PO decision after 8.6; the evidence is the 1.9 spike |
| 1.2 Problem | [x] | Strategic pivot plus a technical limitation |
| 1.3 Evidence | [x] | 62.5% spike, story counts, flags and manifest state |
| 2.1 Current epic (8) | [x] | Completable; adds 8.7 |
| 2.2 Epic changes | [x] | Defer E10 and E11 |
| 2.3 Remaining epics | [x] | E9 none; E12 two AC edits; 3.7 none |
| 2.4 Obsolete or new epics | [x] | None obsolete; none new (v1.1 file holds E10/E11) |
| 2.5 Order | [x] | Unchanged |
| 3.1 PRD | [!] | §4.1 edits (through bmad-prd) |
| 3.2 Architecture | [!] | §4.2 edits (through bmad-architecture) |
| 3.3 UX | [!] | §4.3 annotations (through bmad-ux) |
| 3.4 Other | [!] | AGENTS.md refresh; CI unchanged |
| 4.1 Direct Adjustment | Viable as the mechanism | Low effort, low risk |
| 4.2 Rollback | Not viable / not needed | Nothing to roll back |
| 4.3 MVP Review | Viable | Selected |
| 4.4 Path | [x] | Option 3 applied as Direct Adjustment |
| 5.1 – 5.5 | [x] | Sections 1–5 |
| 6.1 – 6.2 | [x] | Reviewed |
| 6.3 Approval | [x] | Approved by PO 2026-11-08 |
| 6.4 sprint-status | [x] | E10/E11 keys removed; 8-7 added |
| 6.5 Handoff | [x] | Section 5 |
