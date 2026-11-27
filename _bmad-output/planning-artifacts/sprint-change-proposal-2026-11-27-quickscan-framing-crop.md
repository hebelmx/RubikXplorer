---
title: 'Sprint Change Proposal: Quick Scan stacks in tablet portrait and reads the full camera frame (#415), crops to the tracked cube with a "move closer" cue (#407), and Story 10.33 records per-stage timings (#406)'
date: 2026-11-27
status: approved
approved: 2026-11-27
mode: batch
trigger: 'GitHub issues #415, #407; one acceptance line for #406'
scope: Minor
---

# Sprint Change Proposal: Quick Scan framing, full-frame detection and the tracked-cube crop

**Bottom line.** The continuous Quick Scan stacks like a phone in any portrait window, the Lenovo tablet included. It reads the whole camera frame instead of only the preview's crop and gets two framing cues, "Move the cube into the frame" and "Move the cube closer". Once a cube is tracked, the detector reads only the region around it. Story 10.33 must also record the per-stage timings from #406. This proposal only changes planning text: two documents are amended, no code is touched and no story is added. Two code fixes go to the Developer agent.

- **Trigger:** GitHub issues #415 and #407, read from the orchestrator's snapshots (no `gh` call). One acceptance line for #406.
- **Pending items included:** none.
- **Mode:** Batch.
- **Scope:** Minor. `epics.md` (Stories 10.31, 10.32, 10.33) and `EXPERIENCE.md` are amended. The PRD and the architecture spine are unchanged and no story is added. Two fixes are built on their own branches, and Story 10.32 stays done.
- **Status:** approved by the PO (hebelmx) on 2026-11-27, as drafted, including the "Move the cube closer" copy and threshold (section 3.3) and the derived rules of section 3. The section 4 edits are applied on branch `docs/scp-2026-11-27-quickscan-framing-crop`.
- **Product owner decisions already made (hebelmx, 2026-11-26/27):**
  1. #415: only the Quick Scan screen stacks in tablet portrait. The Guided viewfinder and the calibration screens keep their layout (`scanTwoPane`).
  2. Stickers on a face read entirely off-screen MAY be recorded. Its outline is not drawn, and the cue asks the Learner to bring the cube into view.
  3. The approved cue copy for #415 is "Move the cube into the frame".
  4. #407 is in this batch and is built after #415 lands. The PO reviews its "Move the cube closer" copy and threshold in this proposal.
  5. Story 10.33 must require per-stage timings.

## 1. Issue summary

### 1.1 Tall camera pane on the Lenovo in portrait (#415, part 1)

The continuous Quick Scan first stacked in every portrait window. Commit 59f23372 (Story 10.32 fix round, 2026-11-26) switched it to the Guided viewfinder's `scanTwoPane` rule (two panes from 600 dp, or in landscape). SCP 2026-11-26 recorded that rule as built. On the Lenovo TB128XU in portrait the camera pane became about 3:1 tall, and the 256×256 model input lost about two-thirds of its width to letterbox bars (#410, #414). The PO was not told that the layout change also changed what the detector sees. On the Galaxy S24 FE in portrait (stacked), a continuous scan completed, and it responded faster than the Lenovo.

### 1.2 The detector reads only the preview crop (#415, part 2)

The analysis frame is cropped to the PreviewView viewport for the Guided scan's sake (Stories 10.2 and 10.10): the grid the Learner lines up is the grid that gets read. The continuous scan has no grid but inherited the crop. The PC spike it was ported from runs on the full webcam frame (#413). The PO chose this over the retry in #410.

### 1.3 Small cubes in a 256-pixel input (#407)

The model always sees the whole crop letterboxed to 256×256. A cube held at a normal distance fills only part of it, so side-face stickers end up 2–3 heatmap cells apart (64×64 heatmap, stride 4). Then `FaceLatticeFitter` loses the peaks it needs (6+ spanning every row and column). This probably contributes to #391 but has not been measured yet.

### 1.4 No per-stage timing on device (#406)

Without per-stage numbers, the Story 10.33 matrix cannot tell whether a slow scan comes from the model, the fitter or the mapper, or whether #402 or #407 helped. #406 is built as its own fix; this proposal adds only the 10.33 acceptance line.

## 2. Impact analysis (checklist)

| # | Item | Status | Finding |
| --- | --- | --- | --- |
| 1.1 | Triggering story | [x] | Story 10.32 (done): its layout criterion and cue list change, and three criteria are added. Story 10.33 (backlog): one criterion is added. |
| 1.2 | Core problem | [x] | Story 10.32 tied the detector input to the layout, and Story 10.33 had no way to measure the stages. Found in the 2026-11-26 device session and in the continuous Quick Scan review. |
| 1.3 | Evidence | [x] | #410/#414 letterbox measurements; S24 FE session; the PC spike reference (#413). |
| 2.1 | Current epic | [x] | Epic 10 stays as planned. Both fixes are built on Story 10.32 after Story 10.34 (PR #396) merges, as #415 and #406 ask. |
| 2.2–2.4 | Other epics, new or obsolete stories | [N/A] | No new story. #410's retry is superseded by #415 (the issue says so). |
| 2.5 | Priority or order | [x] | Order: #406 (timings), then #415, then #407. #407 is measured with #406. The 10.33 matrix runs after #406 lands. |
| 3.1 | PRD | [N/A] | No FR or NFR changes. FR-22 and the recognition NFRs still hold. |
| 3.2 | Architecture spine | [x] no edit | AD-9: only numbers and fixed words cross into `:core:domain`. The cues are chosen in `app/src/camera/`, and `ContinuousFaceletMapper` is unchanged. AD-13: no frame is stored. AD-17 keys layout on window size, and "portrait = height ≥ width" is read from the window size, as the earlier "any landscape window" rule was. AD-12: the copy goes in `en.json`. |
| 3.3 | UX (EXPERIENCE.md) | [!] → edited | The Quick Scan capture row (cue list, framing, layout) and the Responsive & Platform Scan layout. |
| 3.4 | Other artifacts | [!] handoff | The AD-17 layout sentence in the Story 10.32 spec (`_bmad-output/implementation-artifacts/spec-10-32-…`) is updated by the #415 fix, not here. `DESIGN.md` has no Quick Scan layout or cue text, so it is not edited. |
| 4 | Path | [x] | Direct adjustment (option 1). |
| 5 | Proposal parts | [x] | This document. |

**Recognition effect (PO note: UI changes affect recognition).** Today the pane shape decides what the detector sees. After #415, the continuous detector reads the full upright frame whatever the pane shape, so the layout no longer changes the detector input for Quick Scan. The analysis resolution is chosen for the full frame instead of from the pane (`analysisTarget`). The Guided scan and calibration still read the viewport crop, and their layouts do not change.

## 3. Recommended approach and derived rules

**Direct adjustment.** Story 10.32 gets the new rules as acceptance criteria. Each issue is built as a fix on its own branch through `bmad-build`, and the story stays done, as with #390. Effort: #415 is medium (layout, frame geometry, cue, tests); #407 is medium (ROI arithmetic, fallback, cue, tests). Risk: low to medium. The S1–S7 stress scenarios and the Guided tests are the guard. Timeline: both come before the Story 10.33 matrix, so the BETA call measures the improved scan.

### 3.1 Layout (#415, decision 1)

- `ContinuousQuickScanScreen` stacks in any portrait window (height ≥ width), whatever the width class: camera on top at full width, then the cue, count, net and actions. It is side by side in any landscape window.
- The camera pane keeps a near-4:3 or squarer shape in portrait, so it is never tall.
- The Guided viewfinder and calibration keep `scanTwoPane` unchanged.
- Expected on devices: Lenovo portrait stacks; Lenovo landscape and both S24 FE orientations look as they do today.

### 3.2 Full frame, off-screen faces and "Move the cube into the frame" (#415, decisions 2 and 3)

- In continuous mode the detector, fitter and sampler read the full upright analysis frame. The Guided scan and calibration still read only the crop.
- A face outside the visible preview may record its stickers (decision 2) but has no outline. Overlay points outside the visible area are clipped.
- **"Mostly out of view" (derived):** more than half of the tracked faces' total quad area lies outside the visible preview. It is one named fraction (0.5), kept in one place.
- **Copy:** "Move the cube into the frame." The approved words get a trailing period, as all their siblings in `en.json` have one (`msg.quick-scan.continuous.*`). TalkBack announces it like the other cues.

### 3.3 Tracked-cube crop and "Move the cube closer" (#407) — for PO review

- **Crop:** when the previous frame tracked faces, the detector reads a square region: the tracked faces' bounding box plus 35% margin, clamped to the full frame and letterboxed alone to 256×256. Its peaks are mapped back to full-frame upright coordinates (an offset and scale on top of `LetterboxParams` and `uprightToFrame`). The fitter, sampler, mapper and overlay are unchanged, and so is the inference cost.
- **Fallback to the full frame:** when nothing is tracked, every N analysed frames (a named default; the spec picks N, for example about 1 s of frames), and whenever the crop finds fewer faces than the last full-frame read.
- **Proposed threshold rule (median sticker spacing) — PO to approve:**
  - For each fitted face, take the median distance between neighbouring sticker centres along its lattice rows and columns.
  - The frame's size value is the **largest** of those per-face medians. A foreshortened side face alone never triggers the cue; the face most square-on to the camera decides.
  - Scale it to the full frame's 256-pixel model input: `spacing_px × 256 / frame_long_side_px`. This makes the value independent of the camera resolution and of the #407 crop (the cue describes the cube's size in the frame, not in the crop).
  - **Show** "Move the cube closer." when the value is below **12 px** (3 heatmap cells, the point at which #407 finds side faces losing peaks). **Clear** it when the value is above **14 px**. The gap stops the cue flickering at the edge.
  - Worked example: on a 4:3 frame, 12 px is about 4.7% of the long side, so a face whose sticker pitch is under about a twentieth of the frame's width counts as small. A cube held at a normal tablet distance (face about a quarter of the frame's short side) gives about 15 px and shows no cue.
  - Both numbers are named constants in one place. Story 10.33 data may retune them, with the reason recorded in the evidence.
- **Copy for PO review:** "Move the cube closer." (`en.json`, with a trailing period like its siblings).

### 3.4 How the new cues relate to "Bring the cube into view", and which wins

- **"Bring the cube into view."** (existing, `msg.quick-scan.continuous.bring-into-view`) keeps its meaning: no face is tracked anywhere. After #415, "anywhere" means the full camera frame, not the preview crop. It stays the mapper's cue and is unchanged.
- **"Move the cube into the frame."** (new, #415): faces are tracked, but mostly outside what the preview shows. The cube is there; the Learner must move it towards the middle.
- **"Move the cube closer."** (new, #407): faces are tracked and in view, but small.
- **Order when several apply (one cue line):**
  1. Layer turned mid-scan.
  2. All 54 stickers verified.
  3. Bring the cube into view.
  4. Move the cube into the frame.
  5. Move the cube closer.
  6. The mapper's other cues (turn, tilt, light, conflict, remaining stickers), in their existing order.
- The unavailable card (SCP 2026-11-26) replaces the cue line altogether.
- A cube that is both off-screen and small gets "into the frame" first, because size cannot be judged well until it is in view.
- Each framing cue shows only after its condition has held for 3 consecutive analysed frames, and clears after 3 frames without it (a named default, derived to avoid flicker).
- The framing cues are chosen on the camera side (`app/src/camera/`, for example in `ContinuousQuickScanFlow`). `ContinuousFaceletMapper`'s `computeCue` and its parity with `mapper.py` stay unchanged. Whether the two cues become new `ContinuousScanCue` variants or a camera-side cue type is left to the build (fixed words only, AD-9).

## 4. Detailed change proposals (applied)

### 4.1 `epics.md`

**4.1.1 Story 10.32, criterion 1 (cue list), line 2971.**
OLD: "… covering all mapper states (bring into view, …, all 54 stickers verified)"
NEW: "… covering all mapper states (bring into view, …, all 54 stickers verified) and the two framing cues, move the cube into the frame and move the cube closer *(SCP 2026-11-27 quickscan-framing-crop; cue order in the criteria below)*"
Rationale: decisions 3 and 4.

**4.1.2 Story 10.32, three new criteria inserted before "all 54 stickers verified" (lines 2985–2995):** full frame, off-screen faces and the into-the-frame cue (#415); the tracked-cube crop, fallback and closer cue (#407); and the cue order (section 3.4). The full text is in the file. Rationale: the issues' "Done when" lists, decisions 1–4, and section 3.

**4.1.3 Story 10.32, layout criterion, line 3007.**
OLD: "the preview sits on the left and the net, count, cue and actions on the right from 600 dp width (the medium window width class, the same rule as the Guided viewfinder) and in any landscape window *(SCP 2026-11-26 …)*"
NEW: "in any portrait window (height ≥ width, whatever the width class, the Lenovo's 600 dp included) the screen stacks like a phone, with the preview on top at full width in a near-4:3 or squarer shape, never tall, and the cue, count, net and actions below, while in any landscape window (width > height) the preview sits on the left and the net, count, cue and actions on the right *(SCP 2026-11-27 quickscan-framing-crop, `#415`: replaces the 600 dp rule of SCP 2026-11-26; the Guided viewfinder and calibration keep their own rule)*; a 600×960 dp window at 200% font stacks, with *Close* and the torch not overlapping"
Rationale: decision 1.

**4.1.4 Story 10.31, the "When it analyses a `YuvFrame`" line, line 2950.** A note is appended: as built; in the continuous Quick Scan the detector now reads the full frame and then the tracked-cube region instead of `cropRect` (see Story 10.32). Rationale: stops a reader taking the done story's `cropRect` clause as the current rule.

**4.1.5 Story 10.33, new criterion after the evidence criterion, line 3025.**
NEW: "**Given** the per-stage timings that `#406` adds to the debug build's `ScanDiagnostics` (merged before the matrix runs) … **Then** `docs/evidence/10-33/continuous-quick-scan-beta.md` records for that run, with the other measurements, the p50/p90/max of the detector (total and inference), fit, sample and map stages and of the analyzer total, plus the analysed frame rate, read from the end-of-run timing line; a run without that line is marked as missing its timings, not dropped"
Rationale: decision 5 and #406's last "Done when" item.

### 4.2 `EXPERIENCE.md`

**4.2.1 Front matter.** `updated: 2026-11-26` → `updated: 2026-11-27`.

**4.2.2 Quick Scan capture row (line 106).**
1. After the quoted cue list, a new **Framing** paragraph: the scan reads the whole camera frame; off-screen faces may be read with no outline; the two new cues; the one-cue order (section 3.4); no flicker.
2. Layout. OLD: "from 600 dp width and in any landscape window the preview sits on the left … (Responsive & Platform; SCP 2026-11-26, as built)". NEW: "in any portrait window, tablets included, the screen stacks like a phone: the preview on top at full width, in a near-4:3 or squarer shape and never tall, with the cue, count, net and actions below; in any landscape window the preview sits on the left and the net, count, cue and actions on the right (Responsive & Platform, Scan layout; SCP 2026-11-27 quickscan-framing-crop, #415, replacing the 600 dp rule of SCP 2026-11-26)".

**4.2.3 Responsive & Platform, Scan layout (line 220).** A new bullet: "**Quick Scan capture (SCP 2026-11-27 quickscan-framing-crop, #415):** follows its own rule instead of the two above: stacked like a phone in any portrait window, tablets included, with the camera on top; side by side in any landscape window. The Guided viewfinder and calibration keep the rules above."

### 4.3 Checked and left unchanged

- `prd.md`, `ARCHITECTURE-SPINE.md` (section 2, items 3.1–3.2) and `DESIGN.md`.
- `sprint-status.yaml`: not edited here (section 5).
- The Story 10.32 spec's AD-17 layout sentence: updated by the #415 fix.

## 5. Implementation handoff

**Scope: Minor.** Direct implementation by the Developer agent through `bmad-build`, on its own branch for each issue, after Story 10.34 (PR #396) merges.

| Who | What |
| --- | --- |
| PO (hebelmx) | Approves this proposal, **including the "Move the cube closer" copy and the 12 px / 14 px threshold rule (section 3.3)** and the derived rules (mostly out of view = more than half the area; cue order; 3-frame hold). |
| Orchestrator | Merges this branch. Adds two action items in `sprint-status.yaml`: the #415 device check (Lenovo portrait and landscape and S24 FE, fitted faces and time to 6/6, before and after) and the #407 device comparison (crop on and off, side-face coverage and time to 6/6, with the #406 timings), both on the Story 10.33 matrix or a Story 10.34 replay. Marks #410's retry as superseded by #415. |
| Developer agent | Builds #406 (already routed), then #415, then #407. |

**What the #415 fix must do:** stack `ContinuousQuickScanScreen` in any portrait window and keep it side by side in landscape, with a near-4:3 camera pane; leave `scanTwoPane` for the Guided and calibration screens; run the detector, fitter and sampler on the full upright frame in continuous mode only, with the analysis resolution chosen for the full frame; draw no outline for off-screen faces, clip overlay points, and let them record their stickers; add the cue "Move the cube into the frame." (`en.json`) with the section 3.2 rule and the section 3.4 order, chosen in `app/src/camera/` with the mapper unchanged; update the Robolectric fit tests (600×960 dp at 200% stacks) and the 10.32 spec's layout sentence. AD-13 is unchanged.

**What the #407 fix must do:** add the tracked-cube square ROI (35% margin, clamped) with peaks mapped back to full-frame coordinates; fall back to the full frame as described; add the cue "Move the cube closer." with the section 3.3 rule (named constants) and the section 3.4 order. Tests: a 0.5 px round-trip at four rotations, the fallback, S1–S7 at 54/54 with 0 wrong, and the cue's small and normal cases (JVM plus large-font screen test).

**Success criteria:** the "Done when" lists of #415 and #407 as written into Story 10.32. The device checks become action items. `./gradlew spotlessCheck build` and `checkLearnerText` pass.
