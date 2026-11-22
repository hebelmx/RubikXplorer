# Sprint Change Proposal: Move arrow casing and Story 10.4 calibration notes (2026-11-22)

- **Trigger:** GitHub issues #260 ("3D Solver arrow: improve visibility with higher-contrast color and larger arrowhead tip") and #166 ("Story 10.4 follow-ups: calibration offer untested on device, auto-capture, scan entry")
- **Mode:** Batch
- **Scope:** Minor (planning text only; #260 then goes to a build, #166 is resolved by this change)
- **Product owner decisions:** hebelmx, 2026-11-22 (listed in section 3)

## 1. Issue summary

**#260.** The 3D Move arrow is drawn in `sticker-highlight` (lavender) with a small open-V tip (`Cube3DRenderer.kt`: 12dp back by 6dp half-width, 4dp stroke). On bright or busy faces it is hard to see, and the tip is too small to read the turn direction at a glance. DESIGN.md (Move arrow overlay, ghost Move arrow) gives only the colour, so a fix needs the casing and the arrowhead shape written into the plan first.

**#166.** Most of Story 10.4's follow-ups were closed in device session B3 (Story 10.5, PR #168, 2026-11-17). Two loose ends remain:

1. The pastel-cube `ColorPlausibility` check, deferred in `implementation-artifacts/spec-10-4-calibrate-colors-for-my-cube.md` (chroma floors set from standard fixtures only), was never run on a recorded pastel cube.
2. Story 10.4's AC and human gate speak of a "first stickerless scan", while the PO's rule (PRD memlog, 2026-11-17) offers calibration only on uncertain reads. The AC already carries the SCP 2026-11-17 annotation; the human gate does not, and neither cites #166.

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
|---|---|---|---|
| 1 | Trigger understood | [x] | #260 (code-level observation); #166 issue body plus the B3 comment of 2026-11-17 |
| 2 | Epic impact | [x] | Epic 5 (3D arrow, done): DESIGN.md text only, then a build. Epic 10: Story 10.4 (done) annotation; Story 10.20 (backlog) gains one AC. No epic added, removed or resequenced. |
| 3 | PRD | [N/A] | FR-24 already says pastel cubes work after calibration; no FR or NFR changes. A memlog line records the decisions. |
| 4 | Architecture | [N/A] | No AD is affected; the arrow is a renderer detail and calibration ownership is unchanged. |
| 5 | UX documents | [!] | DESIGN.md Move arrow overlay and Drag preview (ghost arrow) edited. EXPERIENCE.md only calls the arrow "violet" (Hint button row) and names it on the Cube stage row; both still hold, so no edit. |
| 6 | Code | [!] | `Cube3DRenderer.kt` arrow drawing (#260 build). Story 10.20 may tune the chroma floors in `ColorCalibration.kt`. |
| 7 | Path forward | [x] | Direct adjustment |
| 8 | Sprint status | [N/A] | No story added; Story 10.20 keeps its key. The spec-10-4 deferral is now owned by Story 10.20. |

## 3. Recommended approach

**Direct adjustment.** PO decisions recorded on 2026-11-22:

1. **#260:** keep `sticker-highlight` for the Move arrow and add a dark contrast casing (outline) so it reads against all six sticker colours. The tip becomes a filled triangular arrowhead about 1.5 times the current size. The ghost Move arrow (60% opacity) gets the same casing and tip. The casing uses the existing dark `cubie-body` token, as the cube highlight rule already does.
2. **#166:** the pastel-cube plausibility check moves into Story 10.20's fixture recording: record a pastel stickerless cube and run the `ColorCalibration` plausibility check on it (pass, or a tuned chroma floor). Story 10.4's "first stickerless scan" wording is annotated with the PO's offer rule (offer only on uncertain reads), citing #166. After this, #166 is fully resolved by the docs change.

Effort is small (one renderer change plus a test; one extra recording group in an already planned session). Risk is low. No timeline impact.

## 4. Detailed change proposals (applied)

### 4.1 UX: DESIGN.md

**Move arrow overlay (line 232)**

- OLD: "…a curved arrow in `sticker-highlight` follows the turning layer, with the Move shown in `notation` at its tip. With reduce motion on, the arrow appears without animating."
- NEW: "…a curved arrow in `sticker-highlight` follows the turning layer and ends in a filled triangular arrowhead, with the Move shown in `notation` at its tip. The stroke and the arrowhead carry a dark `cubie-body` casing (an outline on both sides), so the arrow reads against all six sticker colors. The arrowhead is about 1.5 times the earlier open-V tip (about 18dp long and 9dp half-width, from 12dp and 6dp) (SCP 2026-11-22, #260). With reduce motion on, the arrow appears without animating."

**Drag preview (line 233)**

- OLD: "A ghost Move arrow (the Move arrow overlay at 60% opacity) carries…"
- NEW: "A ghost Move arrow (the Move arrow overlay at 60% opacity, with the same casing and arrowhead) carries…"

Front matter `updated:` 2026-11-20 → 2026-11-22.

Rationale: done-criterion (a) of #260.

### 4.2 Stories: epics.md

**Story 10.4, Human gate:** append
> *(SCP 2026-11-22, #166: the PO's offer rule applies to this gate too: the prompt is offered only on uncertain reads; see the AC annotation below. Accepted at session B3.)*

**Story 10.4, first AC:** after the SCP 2026-11-17 annotation, append
> *(SCP 2026-11-22: this annotation closes the 10.4 AC wording item in #166.)*

**Story 10.20, Refs:** add FR-24, Story 10.4 and #166.

**Story 10.20, new AC** (after the corner-model held-out AC):
> **Given** a pastel stickerless cube (a new cube id) recorded in the same session, with its recorder calibration *(SCP 2026-11-22, #166: the pastel-cube check deferred from Story 10.4)*
> **When** `ColorCalibrationTest`'s fixture-plausibility check runs on its swatches and stickers
> **Then** they pass `ColorPlausibility`, or the chroma floors in `ColorCalibration.kt` are tuned so they pass while the standard-fixture checks still pass and implausible swatches are still refused; the result and any new floors are written to `docs/evidence/10-20/` (FR-24: pastel cubes work after calibration)

Rationale: both #166 loose ends get an owner; the deferral in the 10.4 spec is now tracked by Story 10.20.

### 4.3 PRD (`prds/prd-RubikXplorer-2026-10-29/.memlog.md`)

Appended (via `memlog.py`): the two PO decisions above. No FR change.

### 4.4 UX memlog (`ux-designs/ux-RubikXplorer-2026-10-29/.memlog.md`)

Appended: the DESIGN.md change in 4.1.

## 5. Implementation handoff

**Scope: Minor.**

| Role | Responsibility |
| --- | --- |
| PO (`hebelmx`) | Approve this SCP and merge the docs PR; record the pastel cube in the Story 10.20 session. |
| Developer agent | **#260 (build):** in `Cube3DRenderer.kt`, draw the Move arrow and the ghost Move arrow with a dark `cubie-body` casing around the `sticker-highlight` stroke and a filled triangular arrowhead about 18dp long and 9dp half-width, cased too; add a renderer test for the arrowhead dimensions and colours. **#166:** close as resolved by this docs change; the pastel check runs in Story 10.20. |

**Success criteria:**
- #260: the arrow and ghost arrow show the casing and the filled arrowhead on device; a renderer test pins the arrowhead size and the casing and fill colours.
- #166: closed with a pointer to this SCP; Story 10.20's evidence reports the pastel plausibility result.
