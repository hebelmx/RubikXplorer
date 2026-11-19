# Sprint Change Proposal: Solution Playback shows Certainty once (2026-11-19)

- **Trigger:** GitHub issue #200, "Solution Playback: certainty status shown twice; the top label overlaps the net"
- **Mode:** Batch
- **Scope:** Minor (planning text only, then a build of #200)
- **Product owner decisions:** hebelmx, 2026-11-19 (listed in section 3)

## 1. Issue summary

In Solution Playback in Physical-cube Mode, a passing Quick Check shows the Certainty twice:

- a "✓ VERIFIED" frame label in the stage's top-left overlay, which overlaps the white face of the 2D net;
- a "✓ Verified" certainty chip directly below the net.

This was found on a Galaxy S24 FE in portrait during device session C (Story 10.8) on 2026-11-18. The expected result is one status indicator, the chip below the net, with nothing overlapping the cube stage.

The code does what the plan says. EXPERIENCE.md, DESIGN.md and epics.md require the frame label on every physical cube stage, Solution Playback included. Removing it on Solution Playback therefore needs a planning change before #200 can be built.

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
|---|---|---|---|
| 1 | Trigger understood | [x] | #200, with device evidence |
| 2 | Epic impact | [x] | Epic 7 (physical mode): UX-DR4 text only. Epic 10 Story 10.7 (done) and Epic 12 Stories 12.14 and 12.17 (done): AC wording only. No epic is added, removed or resequenced. |
| 3 | PRD | [N/A] | No FR or NFR names the frame label. FR-7 (EXPECTED shown honestly) still holds on Lesson screens. On Solution Playback the 2D net opens in Physical-cube Mode so the Learner can compare the cube with EXPECTED (Story 12.14). |
| 4 | Architecture | [N/A] | No AD is affected. Certainty still comes only from `tutor/Assess`. This is a UI change only. |
| 5 | UX documents | [!] | EXPERIENCE.md (Cube stage row, Expected only row, Verified row) and DESIGN.md (frame label) are edited below. |
| 6 | Code | [!] | `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` renders `FrameLabel` when `state.isPhysical`. The #200 build removes it. |
| 7 | Path forward | [x] | Direct adjustment |
| 8 | Sprint status | [N/A] | No story is added. #200 stays a GitHub issue built after this proposal merges. |

### Effects worth knowing

- **Before any evidence:** in Physical-cube Mode on Solution Playback, nothing labels the state as EXPECTED until a Quick Check gives evidence. With `CAMERA_SCAN` OFF (v1.0 release) there is no Quick Check, so the Physical-cube Mode Solution Playback stage carries no Certainty indicator at all. The PO accepted this: Solution Playback always shows the Expected State, and its net opens so the Learner can compare it with the cube.
- **Lessons:** unchanged. Lesson screens keep the frame label and the certainty chip.
- **View rotated badge:** on Solution Playback in 3D it now sits first in the top-left overlay in both modes, as it already does in virtual Solution Playback. No new rule is needed; Story 12.17's "first where none is" case covers it.
- **Accessibility:** TalkBack still hears Certainty changes through the chip (UX-DR29). On Solution Playback there is no frame label to focus first, so focus starts at the badge when it is shown, as Story 12.17 already says.

## 3. Recommended approach

**Direct adjustment.** These PO decisions are recorded on 2026-11-19:

1. Solution Playback drops the frame label entirely, both EXPECTED and VERIFIED, in the 2D net and the 3D view.
2. Solution Playback shows Certainty only through the certainty chip below the stage.
3. Lesson screens keep both the frame label and the chip, unchanged.
4. The View rotated badge on Solution Playback sits first in the top-left overlay.

Effort is small (one composable branch plus tests). Risk is low. There is no timeline impact.

## 4. Detailed change proposals (applied)

### UX: EXPERIENCE.md

**Cube stage row (line 85)**

- OLD: "A frame label always says which state is shown: VIRTUAL, EXPECTED or VERIFIED."
- NEW: "A frame label always says which state is shown: VIRTUAL, EXPECTED or VERIFIED, except on Solution Playback, which has no frame label and shows Certainty only through the certainty chip below the stage (SCP 2026-11-19)."
- OLD: "…jumps straight to the current step with no animation (AD-4); the frame label, certainty chip and SEEN strip are unchanged."
- NEW: "…jumps straight to the current step with no animation (AD-4); the certainty chip and SEEN strip are unchanged."
- Rationale: PO decisions 1 and 2. A view switch on Solution Playback can no longer leave a frame label unchanged, because there is none.

**Expected only row (line 113)**

- OLD: "…the EXPECTED frame is itself the honest label (decision logged)."
- NEW: "…the EXPECTED frame is itself the honest label (decision logged). Solution Playback has no frame label, so it shows no Certainty until there's evidence (SCP 2026-11-19)."
- Rationale: this states the effect of decision 1 on Solution Playback before any evidence, so it is not read as a gap.

**Verified row (line 117)**

- OLD: "A ✓ *Verified* chip and a VERIFIED frame. It reverts to EXPECTED on the next *Done*."
- NEW: "A ✓ *Verified* chip and a VERIFIED frame (Solution Playback shows the chip only, SCP 2026-11-19). It reverts to EXPECTED on the next *Done*."
- Rationale: this is the row #200 shows twice on Solution Playback.

### UX: DESIGN.md

**Cube stage, frame label (line 226)**

- OLD: "A small frame label sits in the top-left corner: `VIRTUAL`, `EXPECTED` (…) or `VERIFIED` (with a `status-verified` icon)."
- NEW: the same sentence, followed by "Solution Playback has no frame label: it shows Certainty only through the certainty chip below the stage, so nothing overlaps the 2D net or the 3D cube (SCP 2026-11-19)."

### Epics: epics.md

**UX-DR4 (line 117)**

- OLD: "frame label VIRTUAL / EXPECTED (dashed `expected-badge`) / VERIFIED (status icon); double-tap reset;"
- NEW: "frame label VIRTUAL / EXPECTED (dashed `expected-badge`) / VERIFIED (status icon), none on Solution Playback, which shows Certainty only through the certainty chip below the stage (SCP 2026-11-19); double-tap reset;"

**Story 10.7, matching-result AC (line 2006)**

- OLD: "**Then** a ✓ *Verified* chip and a VERIFIED frame appear until the next *Done*;"
- NEW: "**Then** a ✓ *Verified* chip and a VERIFIED frame appear until the next *Done* (on Solution Playback the chip only, with no frame label; SCP 2026-11-19);"

**Story 12.14, mid-turn view switch AC (line 2710)**

- OLD: "…no `SessionEvent` is dispatched, and the frame label (VIRTUAL / EXPECTED / VERIFIED), the certainty chip and the SEEN strip are unchanged"
- NEW: "…no `SessionEvent` is dispatched, and the certainty chip and the SEEN strip are unchanged (Solution Playback has no frame label; SCP 2026-11-19)"

**Story 12.17, badge placement AC (line 2862)**

- OLD: "(today virtual Solution Playback and a Lesson *Show me* Demonstration have no frame label)"
- NEW: "(Solution Playback, virtual or physical, and a Lesson *Show me* Demonstration have no frame label; SCP 2026-11-19)"

No other planning line states a frame label for Solution Playback. Lines 1162 and 1208 (frame label on a Cube Rotation, and focus order) are about Lessons and stay unchanged. The PRD and the architecture spine do not mention the frame label.

## 5. Implementation handoff

- **Scope:** Minor. The Developer builds GitHub issue #200 after this proposal merges.
- **Code intent:** in `SolutionPlayback.kt`, stop rendering `FrameLabel` in Physical-cube Mode. Keep the certainty chip below the stage as the only Certainty indicator. Keep the View rotated badge in the top-left overlay, where it is now first. Leave `LessonScreen`, `DemonstrationPlayer` and the other frame-label users unchanged.
- **Success criteria:**
  1. In Physical-cube Mode on Solution Playback, Certainty is shown by the certainty chip below the stage only, with no EXPECTED or VERIFIED frame label, in both the 2D net and the 3D view.
  2. No frame label or stage overlay overlaps the 2D net or the cube stage at portrait phone widths. Check on the Galaxy S24 FE.
  3. `SolutionPlayback` tests cover the 2D and 3D views in Physical-cube Mode: no frame label, the chip present after a matching Quick Check, and the badge first in the overlay when the view is rotated.
  4. Lesson frame-label tests (for example `FrameLabelTest`) still pass unchanged.
