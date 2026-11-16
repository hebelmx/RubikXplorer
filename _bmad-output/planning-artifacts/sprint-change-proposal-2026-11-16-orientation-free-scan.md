# Sprint Change Proposal — Scan each face in any rotation

- **Date:** 2026-11-16
- **Trigger:** PO idea at Story 10.2 device session B (ledger DECISION 2026-11-16 10:50; issue #158, "Product follow-ups")
- **Mode:** Batch
- **Status:** Approved by PO 2026-11-16
- **Scope:** Moderate (one new story, doc amendments; no shipped code changes in this proposal)
- **Out of scope:** retaking one or more faces from review (PO: planned in a separate story); Quick Check (10.6/10.7) and Quick Scan's per-face hand-off (11.1), which keep the prompted top

## 1. Issue Summary

Story 10.2's Guided 6-face Scan prompts each face as "Show the green center, with white on top". `ScanAssembler` uses that prompted up color to turn each face into Kociemba net order (`ScanAssembler.kt:145`). At device session B the PO saw that holding the right side on top is the hardest part of the scan for a beginner. At least one full Galaxy run was done without care for the up face (ledger 10:49). The rotation does not have to be dictated: it can be computed.

**Why it can be computed.** With the centers known, each face has 4 possible quarter turns, so six faces give 4⁶ = 4,096 assignments. Exactly one of them normally yields a state that `CubeValidator` accepts (legal edges and corners, parity, twist). A face whose turns all read the same (a solved face) gives the same `Facelets` under every turn, so it is not ambiguous. Checking 4,096 candidates is cheap on the JVM, and the session A and A2 fixtures, randomly turned, test it offline.

**Evidence:** `GuidedScan.kt:8-33` (prompt and fixed plan), `ScanAssembler.kt:46-50,135-166` (rotation from up neighbor), `GuidedScanScreen.kt:409-428` (prompt UI), ledger 2026-11-16 10:49–10:50, issue #158.

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 10 gains **Story 10.14**, sequenced after 10.3. Story 10.2 is done; it gets an annotation only. 10.3–10.8 are unchanged. 10.6/10.7 (Quick Check, partial scans) and 11.1 keep the prompted top: fewer than six faces cannot be solved from validity. |
| PRD | FR-21: "orientation prompts" becomes face prompts; the wrong-orientation consequence becomes "rotation worked out from adjacency; when it can't be, re-prompt the faces involved naming the top". No NFR or scope change. |
| Architecture | AD-9: `upNeighborColor` is present only when the prompt named a top; `ScanAssembler` stays the only place samples are rotated and solves the turns when no up neighbor is given. No new module, runtime or dependency. |
| UX | DESIGN.md Scan viewfinder face prompt and EXPERIENCE.md Scan viewfinder row: the prompt names the center only; the "· X on top" part appears only on a re-prompt. UX-DR15 in epics.md follows. |
| AC4 (PO 2026-11-15/16) | Kept: fixed face order, only a repeated face is rejected live (PO choice). New: a wrong face now usually makes every assignment invalid, so it is caught at assembly and re-prompted, instead of reaching 10.3 as an invalid state. |
| Code (in 10.14) | `GuidedScan.kt` (prompt step without top; re-prompt step with top), `ScanAssembler.kt` (rotation search), `GuidedScanScreen.kt` (prompt copy), `en.json` strings. The debug fixture recorder keeps its prompted top (ground truth needs a known pose). |
| Sprint status | Add `10-14-scan-each-face-in-any-rotation: backlog`. |

### Checklist summary

- [x] 1 Trigger and context: PO observation at device session B; evidence above.
- [x] 2 Epic impact: Epic 10 gains 10.14; no story removed or reordered except the new one.
- [x] 3 Artifact conflicts: PRD FR-21, AD-9, DESIGN.md, EXPERIENCE.md, epics.md (FR-21 inventory, UX-DR15, 10.2 note).
- [x] 4 Path: Direct Adjustment (new story).
- [x] 5 Proposal components: §4.
- [x] 6 Final review and handoff: §5.

## 3. Recommended Approach

**Direct Adjustment: add Story 10.14 after Story 10.3.** PO decisions taken for this proposal (2026-11-16):

1. **Fixed face order kept.** The prompt names only the center ("Show the **green** center"). The app does not identify faces from center color, so the red/orange center risk in #158 does not grow.
2. **Never guess.** When no assignment is valid, or more than one distinct valid state exists, the app re-prompts only the faces involved with the 10.2 prompt ("…with **white** on top") and uses that prompted turn for them.
3. **Sequence:** 10.3 first (review is the safety net for any turn the search gets wrong), then 10.14.

- **Effort:** about one story: the rotation search and its tests are JVM-only; the UI change is prompt copy plus the re-prompt step.
- **Risk:** low to medium. The main risk is misread stickers making no assignment valid, which raises re-prompts. The fixture AC measures this before the device session.
- **Timeline:** adds one story and one short device session (B2) after 10.3. Nothing else moves.

## 4. Detailed Change Proposals

### 4.1 Stories (epics.md)

**E1 — Epic 10 intro, append a note**

OLD (end of the Epic 10 intro paragraph):
```
*(SCP 2026-11-15: Stories 10.12 (recorder rework, session A2) and 10.13 (OQ-7 spike) run after 10.10 and before 10.1.)*
```
NEW:
```
*(SCP 2026-11-15: Stories 10.12 (recorder rework, session A2) and 10.13 (OQ-7 spike) run after 10.10 and before 10.1.)* *(SCP 2026-11-16: Story 10.14 (scan each face in any rotation) runs after 10.3.)*
```
Rationale: the How-to-read rule for appended IDs.

**E2 — New Story 10.14, inserted after Story 10.13 (before Epic 11)**

```
### Story 10.14: Scan each face in any rotation

As a Learner,
I want to show each face however it happens to be turned,
So that I don't have to work out which side goes on top.

**Refs:** FR-21, FR-23 · recognition NFRs, NFR-10 · AD-2, AD-9 · UX-DR15 · Stories 10.2, 10.3 · #158

**Depends on** Stories 10.2 and 10.3. **Human gate:** device session B2 on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, with a stickered and a stickerless cube. *(SCP 2026-11-16: PO idea from device session B. Per-face retake from review is a separate story.)*

**Acceptance Criteria:**

**Given** the Guided 6-face Scan
**When** a face is prompted
**Then** the prompt names only the center color with its glyph swatch ("Show the **green** center"), the fixed face order and the live repeated-face rule of Story 10.2 (AC4) are unchanged, and the captured `FaceSample` carries no `upNeighborColor`

**Given** six captured faces with no up neighbor
**When** `ScanAssembler` assembles them
**Then** it tries the four quarter turns of every face (4⁶ assignments, centers fixed) on the classified colors and keeps the `Facelets` that `CubeValidator` accepts; assignments that give the same `Facelets` count as one; `ScanAssembler` stays the only place samples are rotated (AD-9)

**Given** the session A and A2 live fixtures, each face turned by a random quarter turn (fixed seed)
**When** the JVM suite assembles them
**Then** every scan whose prompted-pose assembly was valid yields the same `Facelets`, the share of scans needing a re-prompt is reported per device in `docs/scan-accuracy.md`, and one assembly takes under 50 ms on the JVM

**Given** no valid assignment, or two or more distinct valid states
**When** assembly ends
**Then** the scan never guesses: it re-prompts only the faces involved with the Story 10.2 prompt ("Show the **green** center, with **white** on top"), first any face whose center read disagrees with its prompt, then the faces whose turn differs across the valid states or that hold the illegal pieces of the assignment with the most legal pieces; assembly then uses the prompted turn for those faces, and if the state is still invalid the result goes to Story 10.3 review as today, with no further loop

**Given** the prompt and re-prompt copy
**When** they render
**Then** they come from content strings (NFR-10), and on a phone in portrait the viewfinder and prompt still fit without scrolling

**Given** the debug fixture recorder
**When** it records
**Then** it keeps the prompted top, so fixture ground truth keeps a known pose
```

**E3 — Story 10.2 (done), annotate the prompt AC**

OLD:
```
**Then** the face is captured automatically (manual shutter also available), the prompt names the center and up colors with a glyph swatch, and the mini net advances
```
NEW:
```
**Then** the face is captured automatically (manual shutter also available), the prompt names the center and up colors with a glyph swatch, and the mini net advances *(SCP 2026-11-16: Story 10.14 drops the up color from the prompt; this prompt stays as its re-prompt.)*
```

**E4 — FR-21 inventory line (epics.md line 57)**

OLD: `FR-21: Guided 6-face Scan: live viewfinder, orientation prompts, automatic capture when aligned and stable plus a manual shutter; wrong orientation detected from center/adjacency checks and re-prompted; …`
NEW: `FR-21: Guided 6-face Scan: live viewfinder, face prompts, automatic capture when aligned and stable plus a manual shutter; each face's rotation worked out from adjacency checks, and the faces involved re-prompted with a named top when it can't be (SCP 2026-11-16); …` (rest unchanged)

**E5 — UX-DR15 (epics.md)**

OLD: `face prompt naming center and up colors with a glyph swatch`
NEW: `face prompt naming the center color with a glyph swatch (the up color only on a re-prompt, SCP 2026-11-16 / Story 10.14)`

### 4.2 PRD (prd.md FR-21)

OLD:
```
The Learner can capture all six faces in a live viewfinder, with orientation prompts and automatic capture.
…
- A capture in the wrong orientation is detected from center and adjacency checks, and the Learner is prompted again.
```
NEW:
```
The Learner can capture all six faces in a live viewfinder, with face prompts and automatic capture.
…
- Each face may be shown in any rotation. The app works out each face's rotation from adjacency checks across the six faces; when it can't, it asks again for only the faces involved, naming the side to put on top. It never guesses. (SCP 2026-11-16)
```
Impact: none on MVP scope; FR-21 is v1.1, flag-gated.

### 4.3 Architecture (ARCHITECTURE-SPINE.md AD-9)

OLD (Camera adapter):
```
- It emits `FaceSample`s: `centerColor`, `upNeighborColor` and 9 CIELAB samples **in viewfinder order**, plus quality flags and the capture pose.
```
NEW:
```
- It emits `FaceSample`s: `centerColor`, `upNeighborColor` (only when the prompt named a top, SCP 2026-11-16) and 9 CIELAB samples **in viewfinder order**, plus quality flags and the capture pose.
```
OLD (Assembly and classification):
```
- Only `ScanAssembler` in `:core:domain` rotates samples into `Facelets` order.
```
NEW:
```
- Only `ScanAssembler` in `:core:domain` rotates samples into `Facelets` order. A sample with no `upNeighborColor` gets the quarter turn that makes the six faces a state `CubeValidator` accepts; when no turn or more than one distinct state fits, it reports the faces involved for a re-prompt with a named top and never guesses (Story 10.14, SCP 2026-11-16).
```
No ripple: no new type crosses a module, and `ScanResult` is unchanged.

### 4.4 UX

**DESIGN.md, Scan viewfinder:**
OLD: `the face prompt names the center color ("**White** center facing you · **Green** on top")`
NEW: `the face prompt names the center color ("**Green** center facing you"); "· **White** on top" is added only when the app asks for a face again (SCP 2026-11-16)`

**EXPERIENCE.md, Scan viewfinder row:**
OLD: `The face prompt names the center color and the up color. A wrong orientation produces a re-prompt, not an error.`
NEW: `The face prompt names the center color; the face may be turned any way (SCP 2026-11-16, Story 10.14). When the app can't work out how a face was turned, it asks for that face again and names the side to put on top: a re-prompt, not an error.`

### 4.5 Memlogs and sprint status

- PRD, spine and UX `.memlog.md`: one `(change)` line each citing this SCP.
- epics.md Epic 10 summary **Order** line: 10.3 → 10.14 → 10.4, and device session B2 = 10.14.
- `sprint-status.yaml`: add `10-14-scan-each-face-in-any-rotation: backlog` after 10-13.
- Issue #158: tick the "Scan in any orientation" product follow-up with a link to this SCP; the AC4 follow-up is answered (kept; wrong faces now caught at assembly).

## 5. Implementation Handoff

- **Scope:** Moderate — backlog addition plus doc amendments.
- **Now (this branch `docs/scp-orientation-free-scan`):** apply E1–E5 and §4.2–§4.5, open a docs PR.
- **Build:** `/bmad-orchestrate` Story 10.14 by name, after 10.3 merges (the recommender will not pick it).
- **Success criteria:** the turned-fixture AC passes on the JVM; device session B2 completes 6-face scans on both devices with faces shown in arbitrary rotations, no confidently wrong turn, and re-prompts only when a face really can't be placed.
