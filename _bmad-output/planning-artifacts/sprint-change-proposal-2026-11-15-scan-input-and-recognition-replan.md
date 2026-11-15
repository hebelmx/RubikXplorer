---
title: 'Sprint Change Proposal: scan input, calibration and recognition re-plan after device session A'
date: 2026-11-15
status: approved
approved_by: hebelmx
approved_on: 2026-11-15
trigger: Story 10.10 device session A result and PO AC3 decision (2026-11-15)
scope: Moderate (two new stories, three AC changes, UX and planning-text updates; no rollback)
mode: batch
branch: plan/scan-input-replan
---

# Sprint Change Proposal: scan input, calibration and recognition re-plan

## 1. Issue Summary

**Trigger:** Story 10.10 device session A (2026-11-14/15) and the PO's AC3 decision recorded in the PRD memlog on 2026-11-15.

**Category:** a finding from implementation (a live measurement), not a failure. The gate was met, but only with heavy operator effort, and that effort exposed design gaps that would reach Learners.

**What happened:**
- **Result:** `./gradlew :core:domain:evaluateLiveBaseline` scored 216/216 stickers and 24/24 faces (gate MET, ≥ 99%), on the Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE, studio light, cubes `stla` (stickerless) and `stka` (stickered, logo on the white center), with the unchanged Kotlin nearest-center classifier (`docs/spikes/oq-4-guided-scan.md` § Live baseline and § Device session A notes).
- **Calibration is fragile.** WHITE metered on a non-white patch (L76 a20 b71) gave 53/54, with per-face margins of 4–5 ΔE. Recalibrated on a plain sticker (L88 a-6 b-16), the same cube gave 54/54, with margins of 26–34. A logo on the white center reads far from white (L44–77, b-26 to b31), so the operator had to move a plain white sticker into the grid's center cell.
- **Focus.** At the distance where the cube fills the grid, the S24 FE's first autofocus missed. *Re-meter* (grid AF/AE/AWB) fixed it.
- **The input is slow and error-prone** (PO, 2026-11-15):
  - On a phone the recorder needs vertical scrolling to see the cube and the face colors together.
  - Tap-to-cycle color entry needs several touches per sticker.
  - The quality-override buttons sit next to the labels, and a mis-tap saved Lenovo `stka` D as MISALIGNED; only that flag was corrected.
  - Calibration plus labeling took most of the session. Two planned extra cubes (`stlb`, `stkb`) were skipped because of it.
- **OQ-7 revised by the PO:** Kotlin-first is no longer settled. Evaluate OpenCV and an on-device ML model, with size cost.

**PO decisions (2026-11-15, memlog):**
1. Proceed with the session A captures. `CAMERA_SCAN` is BETA only.
2. Re-plan now, with a UX pass on scan layout, calibration and color input.
3. Prefill only high-confidence colors. Close pairs (e.g. yellow vs white) stay blank.
4. In the recorder, prefilled truth is tagged per sticker.
5. The debug recorder may save frames.
6. The UX pass runs before the recorder rework.

## 2. Impact Analysis

### Epic impact
- **Epic 10** gets two new stories (10.12 recorder rework, 10.13 recognition-approach spike) and AC changes to 10.1, 10.2 and 10.4. Epic 10 still exits on 10.8; no story is removed.
- **Epic 11:** 11.1 Quick Scan's "decided by Story 11.1" detection method is now informed by 10.13. No AC text change beyond the reference.
- No other epic is affected. Release gating (10.11, 12.3–12.5, 13.6) is unchanged; every camera flag stays OFF in release.

### Story impact

| Story | Impact |
|---|---|
| 10.10 | Closes as is (gate MET; AC3 recorded). PR #149. |
| **10.12 (new)** | Recorder rework, following the UX pass. Its human gate is **session A2**, a faster recapture that adds cubes `stlb`/`stkb` and saves frames. |
| **10.13 (new)** | OQ-7 spike: compare Kotlin, OpenCV and on-device ML on the A2 frames, with size cost. |
| 10.1 | Depends on 10.13. The classifier follows the OQ-7 outcome; the confidence rule doubles as the prefill rule. |
| 10.2 | Gains an AC to re-trigger grid focus on alignment, plus a phone layout without scrolling. |
| 10.4 | The calibration AC changes. The current text, "each of the six centers is shown in turn", meters a logo center. |
| 10.3 | No AC change. Its tap-sticker-then-color entry stays, and the UX pass may refine it. |
| 10.8, 11.1 | References only. |

### Artifact conflicts
- **UX (EXPERIENCE.md):**
  - Flow 9 step 2 says "Show the white center." That conflicts with logo-center cubes.
  - The Scan viewfinder row has no phone-layout or focus rule.
  - The Sticker review net row needs the prefill rule.
- **PRD:**
  - OQ-7 (`prd.md:710`) says "Initial: Kotlin-first … confirmed by Story 10.10", which is now revised.
  - §10 needs a decision line.
- **Architecture spine:**
  - AD-9 "Frames" says never persisted. The debug recorder exception must be stated.
  - The Quick Scan detection-method line (`ARCHITECTURE-SPINE.md:537`) says Kotlin-first per OQ-7.
- **Fixtures README:** gets a frame-fixture section (10.12).

### Technical impact
- Debug-only code: the recorder (`app/src/camera/debug/`) and the `:core:domain` evaluation source set.
- New debug fixture files: one downscaled JPEG per face next to its JSON. Repo size grows by about 50–100 KB per face.
- No release, benchmark, manifest, permission or `docs/privacy-facts.json` change. The 10.9 classpath gate still bans `org.opencv*` and LiteRT in release; 10.13 evaluates them in a non-shipping source set only.

## 3. Recommended Approach

**Direct adjustment:** add two stories, amend three, and update the UX and planning text. No rollback, and no MVP change (camera is v1.1, flag-gated).

**Order:**
1. UX pass (`bmad-ux` update)
2. Story 10.12 recorder rework
3. Session A2
4. Story 10.13 OQ-7 spike
5. Story 10.1
6. Story 10.2 onward, as planned

**Rationale:**
- The UX pass first keeps the recorder and the product scan screens on one interaction model (PO decision 6).
- The recorder rework makes the next device session short, which is the PO's goal: recalibrate sooner and quicker.
- Frames from A2 are the only way 10.13 can compare OpenCV and ML fairly.
- 10.1 waits for 10.13 so the classifier isn't built twice.

**Effort:**
- UX pass: one session.
- 10.12: about one story (debug UI plus the export format).
- A2: about 30–45 minutes per device with the new recorder (the goal).
- 10.13: one spike story.

**Risk:**
- **Low** for release (debug-only work).
- **Medium** on schedule: 10.1 and 10.2 start later, by roughly two stories plus one device session.

## 4. Detailed Change Proposals

### 4.1 Stories (`epics.md`)

**(a) New Story 10.12, inserted after Story 10.11**

```
### Story 10.12: Fixture recorder a phone operator can finish quickly

As the developer recording fixtures,
I want the recorder to fit one phone screen and take few, safe touches,
So that a device session covers more cubes and lights with fewer input errors.

**Refs:** FR-21, FR-24 · AD-9, AD-15 · OQ-7, OQ-8 · Story 10.10 · SCP 2026-11-15 · UX pass 2026-11-15 (EXPERIENCE.md Scan rows)

**Depends on** Story 10.10 and the 2026-11-15 UX pass. **Human gate:** device session A2 (Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE; cubes stla, stka, stlb, stkb; studio light), recorded with this recorder.

**Acceptance Criteria:**

**Given** a phone in portrait (Galaxy S24 FE)
**When** the recorder is on a face
**Then** the viewfinder, the 3×3 truth grid, the color picker and Capture are visible together without scrolling, following the UX pass layout

**Given** truth entry
**When** the operator labels a face
**Then** a color is chosen once and applied by tapping stickers (no tap-to-cycle). A high-confidence prediction may prefill a sticker; close-call stickers stay blank (two calibrated centers within the UX-pass threshold, e.g. yellow vs white). Each sticker's truth is stored as `entered` or `prefilled`

**Given** the evaluator
**When** it scores fixtures
**Then** it reports accuracy over all stickers and over `entered` stickers only

**Given** the capture-quality override
**When** the operator sets it
**Then** it sits apart from the truth grid and needs a confirm, and Capture shows the active flag

**Given** calibration of a center
**When** the metered swatch is implausible for its color (for WHITE, chroma or lightness outside the UX-pass bounds), or the center cell is not uniform (a logo)
**Then** the recorder warns and asks for a plain sticker of that color in the center cell, and the swatch is shown before it is saved

**Given** a face is selected or the cube is realigned
**When** the preview is shown
**Then** grid AF/AE/AWB re-meters automatically (Re-meter stays available)

**Given** a capture
**When** it is saved
**Then** a downscaled frame (JPEG, long edge ≤ 1024 px) is written next to the face JSON in `Fixtures/live/<device>_<cube>_<light>/`, and no release or benchmark build contains the recorder or writes frames
```

**(b) New Story 10.13, inserted after Story 10.12**

```
### Story 10.13: Choose the recognition approach (OQ-7)

As the developer,
I want Kotlin, OpenCV and an on-device ML model compared on the same live fixtures,
So that Epic 10 builds on the approach with the best accuracy, effort and size trade-off.

**Refs:** FR-21, FR-24, FR-27 · recognition NFRs, NFR-8, NFR-11 · AD-9, AD-14, AD-18 · OQ-7 · Stories 10.10, 10.12, 11.1

**Depends on** Story 10.12 (session A2 frames).

**Acceptance Criteria:**

**Given** the session A and A2 fixtures (samples and frames)
**When** the current Kotlin nearest-center classifier, an OpenCV pipeline and an on-device ML model (bundled LiteRT or equivalent; no ML Kit or Play-services ML, NFR-8) are run in a non-shipping evaluation source set
**Then** per-sticker accuracy, confidently-wrong rate, sensitivity to calibration error (the 2026-11-15 bad-white case), logo-center handling and added release size (checked against NFR-11's 30 MB) are reported in `docs/spikes/oq-7-recognition-approach.md`

**Given** the report
**When** the story closes
**Then** the PO records the OQ-7 decision in the PRD memlog, and the PRD OQ-7 line and the spine's Quick Scan detection-method line are updated to match. The release classpath gate (Story 10.9) is unchanged unless the decision adds a runtime, and then it is changed in this story.
```

**(c) Story 10.1, Depends on**

OLD:
```
**Depends on** Story 10.10 (live fixtures).
```
NEW:
```
**Depends on** Story 10.10 (live fixtures) and Story 10.13 (OQ-7 approach). *(SCP 2026-11-15: the classifier follows the OQ-7 decision; its confidence threshold is also the prefill rule for Story 10.3 review.)*
```

**(d) Story 10.2, new AC after the auto-capture AC**

NEW:
```
**Given** the cube aligned in the grid at the distance that fills it *(SCP 2026-11-15: session A focus miss on the S24 FE)*
**When** alignment is first detected, or the face changes
**Then** focus and exposure are re-triggered on the grid region before auto-capture, and on a phone in portrait the viewfinder and the face prompt fit without scrolling (UX pass 2026-11-15)
```

**(e) Story 10.4, first AC**

OLD:
```
**Then** each of the six centers is shown in turn, the Learner confirms each swatch, and *Save* calls `UpdateCalibration`
```
NEW:
```
**Then** each of the six colors is shown in turn, sampled from a plain sticker of that color (a logo or printed center is rejected or excluded from the sample, per the UX pass), an implausible swatch is refused with a plain re-prompt, the Learner confirms each swatch, and *Save* calls `UpdateCalibration` *(SCP 2026-11-15: a logo white center gave a bad WHITE and a 53/54 read in session A)*
```

**(f) Story 10.3, first AC (refinement)**

OLD:
```
**Then** uncertain stickers are pre-selected, corrections use tap-sticker-then-color, and invalid states name the likely bad stickers ("This corner shows two whites.")
```
NEW:
```
**Then** only high-confidence stickers are prefilled, uncertain ones (including close calibrated pairs such as yellow vs white) are pre-selected and blank, corrections use the color-entry model from the UX pass (tap-sticker-then-color at most two touches per sticker), and invalid states name the likely bad stickers ("This corner shows two whites.")
```

**(g) Epic 10 intro note.** Append to the SCP note: `SCP 2026-11-15: Stories 10.12 (recorder rework, session A2) and 10.13 (OQ-7 spike) run after 10.10 and before 10.1.`

### 4.2 PRD (`prd.md`)

**OQ-7 (`:710`)**

OLD:
```
Initial: Kotlin-first classical CV (Story 1.9); confirmed by Story 10.10.
```
NEW:
```
Initial: Kotlin-first classical CV (Story 1.9). Story 10.10 met the live gate (216/216) only with careful operator calibration; the PO re-opened OQ-7 on 2026-11-15. Kotlin, OpenCV and an on-device ML model are compared by Story 10.13.
```

**§10 decision line:** add one line for SCP 2026-11-15:
- `CAMERA_SCAN` is BETA-only on the session A result.
- Re-plan with a UX pass.
- Stories 10.12 and 10.13 are added.
- OQ-7 is re-opened.

### 4.3 Architecture spine (`ARCHITECTURE-SPINE.md`)

**AD-9 Frames**

OLD:
```
- **Frames:** never persisted or sent anywhere. The **debug-only** fixture recorder (…) exports `FaceSample` JSON with ground truth to `Fixtures/live/`. No release or benchmark build contains it.
```
NEW:
```
- **Frames:** never persisted or sent anywhere by any release or benchmark build. The **debug-only** fixture recorder (…) exports `FaceSample` JSON with ground truth, and from Story 10.12 a downscaled frame per face, to `Fixtures/live/`. No release or benchmark build contains it.
```

**Quick Scan detection method (`:537`)**

OLD:
```
Kotlin-first per OQ-7, confirmed by Story 10.10 and decided by Story 11.1.
```
NEW:
```
re-opened by the PO on 2026-11-15; Kotlin, OpenCV and LiteRT are compared by Story 10.13, and Story 11.1 decides Quick Scan's detection within that outcome.
```

### 4.4 UX (`EXPERIENCE.md`, `DESIGN.md`): run by `bmad-ux` update, before Story 10.12

The UX pass must settle the following, then edit the rows below. Its input is the evidence above and the screenshots from session A.

1. **Phone layout:** the scan and recorder screen fits a phone in portrait without scrolling. That screen holds the viewfinder, the face prompt, the color picker and the actions.
2. **Color entry:** pick-then-paint, or tap-then-color in at most two touches per sticker. Colors carry glyphs. Prefill only high-confidence stickers; close-call stickers stay blank. Set the "close call" rule.
3. **Calibration (Flow 9 step 2):** replace "Show the white center." with a plain-sticker instruction that handles logo centers. Add plausibility bounds per color and a swatch preview before save.
4. **Destructive or rare controls** (quality override, reset) sit apart from frequent ones and need a confirm.
5. **Focus:** after alignment or a face change, the scan re-meters on its own.

Rows to edit:
- Scan viewfinder
- Sticker review net
- Flow 9 step 2
- Color Calibration screen row

Add DESIGN.md color-picker component tokens if new.

### 4.5 Sprint status (`sprint-status.yaml`)

Add `10-12-fixture-recorder-a-phone-operator-can-finish-quickly: backlog` and `10-13-choose-the-recognition-approach-oq-7: backlog`, regenerated with `sprint_plan.py`. The recommended next story after 10.10 becomes 10.12, after the UX pass.

### 4.6 Orchestrator gates (`_bmad/custom/bmad-orchestrate.toml`)

Add gates:
- 10.12: `before-merge`, device session A2.
- 10.13: `before-merge`, PO OQ-7 decision.

## 5. Implementation Handoff

**Scope:** Moderate (backlog reorganization plus UX/PRD/spine text). Handed to the PO for approval, then:

| Step | Who | Deliverable |
|---|---|---|
| 1 | Orchestrator (this branch) | Apply §4.1, §4.2, §4.3, §4.5 and §4.6 on `plan/scan-input-replan`. PR, CI, merge. |
| 2 | `bmad-ux` update (UX designer) | §4.4 edits to EXPERIENCE.md/DESIGN.md; PR. |
| 3 | `/bmad-orchestrate story 10-12-…` | Recorder rework; stops at the before-merge gate for session A2. |
| 4 | PO with both devices | Session A2: 4 cubes × 2 devices with the new recorder; `evaluateLiveBaseline`. |
| 5 | `/bmad-orchestrate story 10-13-…` | OQ-7 report; PO decision in the memlog. |
| 6 | `/bmad-orchestrate` | 10.1, then 10.2 onward. |

**Success criteria:**
- Session A2 covers 4 cubes on both devices in about 30–45 minutes per device.
- No scrolling on the S24 FE.
- No mis-tapped quality flags.
- Labeling takes ≤ 2 touches per sticker on average.
- 10.13 ends with a recorded OQ-7 decision that names its size cost.
- Release builds stay camera-free throughout.

## Checklist record

| Item | Status | Note |
|---|---|---|
| 1 Trigger and context | [x] | Story 10.10 session A; PO AC3 memlog 2026-11-15 |
| 2 Epic impact | [x] | Epic 10 +2 stories, 3 AC edits; Epic 11 reference only |
| 3 Artifact conflicts | [x] | EXPERIENCE Flow 9 and Scan rows; PRD OQ-7; spine AD-9 Frames, :537 |
| 4 Path forward | [x] | Direct adjustment; no rollback; no MVP change |
| 5 Proposal components | [x] | §4.1–§4.6 |
| 6 Final review and handoff | [x] | Approved by hebelmx 2026-11-15 |
