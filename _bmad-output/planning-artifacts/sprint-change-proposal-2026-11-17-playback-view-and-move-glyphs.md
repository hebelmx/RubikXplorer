# Sprint Change Proposal: Playback view switch and move pictures

- **Date:** 2026-11-17
- **Trigger:** GitHub issues #175 (*Solution Playback: switch between the 2D net and the 3D cube*) and #176 (*Move glyph: a small picture of each turn next to the move label, show/hide in Settings*). Both were raised in the 2026-11-17 Story 10.15 device session and shaped by a party panel.
- **Mode:** Batch
- **Status:** Approved. The product owner's decisions are recorded verbatim in §6. The planning edits in §4 have been applied.
- **Scope:** Moderate. Two new stories are added to Epic 12, the backlog order changes (both run before Story 12.3 and the store screenshots), and one deferred action item is closed by a story.

## 1. Issue Summary

**#175.** Solution Playback shows only the P0.1 2D net (`SolutionPlayback.kt` builds a `Net2DRenderer`). DESIGN.md puts the 3D cube on the stage, and Lessons already play on `Cube3DRenderer`. Learners want to watch the Solution on the 3D cube. `NetPlayback` already accepts any renderer, so this is a stage and preference change, not a new renderer.

**#176.** Notation is hard to read for beginners. The product owner wants a small picture of each turn next to the move label, like the standard notation icon row (B2, R, L, M), which can be hidden in Settings. The UX-DR14 Move strip, deferred from Story 3.6 (`epic-3-deferred-3-6-ux-dr14-move-strip`), is the second place the picture goes.

The product owner made both **HIGH priority, v1 release scope** (`plan-remaining-work-2026-11-17.md`, phase 3; on branch `docs/remaining-work-plan`). They must ship before Story 12.3 and before the store screenshots (Story 12.5).

**Issue type:** a new requirement from stakeholders (product owner, device session).

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 12 gets two stories: **12.14** (#175) and then **12.15** (#176). Both run before 12.3 and 12.5. The Epic 12 summary, the FR-16/FR-19 inventory and coverage lines, UX-DR4, UX-DR6 and UX-DR14 are amended, and **UX-DR32** (Move picture) is added. Story 3.6 gets a deferral note pointing to 12.15. No story is removed or reworded in scope. |
| PRD | FR-16 gets two consequences (the view switch and move pictures). The FR-19 preferences list gets the playback view and *Move pictures*. A §10 decision line is added. MVP scope grows by two v1 features. Nothing is cut. |
| Architecture | AD-4 *First adapter* bullet: "the 3D adapter replaces it" now has an exception. On Solution Playback both adapters stay. The view is a preference written by `UpdatePreference`, never a `SessionEvent`, and a switch is a `Snap` at the committed state. No new port, module, persisted entity or flag. The Preferences entity gains two fields under its existing single writer. |
| UX | DESIGN.md: a cube stage view switch, the Move arrow overlay on playback in 3D, the transport's Move strip and visible scrubber, and a new **Move picture** component. EXPERIENCE.md: Settings, the Solution Playback screen row, and the Cube stage and Playback transport behavior rows. |
| Code (later, not in this proposal) | `SolutionPlayback.kt` (renderer choice), `PlaybackTransport.kt` (Move strip, scrubber, picture), Settings and `PreferenceStore` (two preferences), a pure move-to-picture function, `en.json` strings, and `Net2DFrameTimingBenchmark.requireNet()` (exact tag instead of the `2D|Net` text fallback). |
| Sprint status | Not changed here. Sprint planning adds `12-14-…` and `12-15-…` and later closes `epic-3-deferred-3-6-ux-dr14-move-strip` when 12.15 is done. |
| Interactions | Phase 4 (Story 12.3) absorbs epic-6-retro-item-1 (*PreferenceStore must not overwrite saved prefs with defaults after a read error*). 12.14 and 12.15 add preferences first, so 12.3's fix covers them. |

### Checklist summary

- [x] 1.1 Trigger: issues #175 and #176 (Story 10.15 device session, 2026-11-17).
- [x] 1.2 Problem type: a new stakeholder requirement.
- [x] 1.3 Evidence: the issue text, the PO's reference glyph image (`.git/bmad-orchestrate/issue-176-fb-glyph-reference.png`), and `SolutionPlayback.kt:86` (always a `Net2DRenderer`).
- [x] 2.1 Epic 12 can still be completed. It gains two stories ahead of 12.3.
- [x] 2.2 Epic change: two stories added to Epic 12.
- [x] 2.3 Future epics: Epics 10, 11 and 13 are unaffected. The Epic 10 Quick Check in playback keeps working in either view: the frame label, the certainty chip and the SEEN strip don't depend on the renderer.
- [x] 2.4 Nothing becomes obsolete. The Story 3.6 deferral is absorbed.
- [x] 2.5 Order: 12.14, then 12.15, then 12.3, then 12.5's screenshots.
- [x] 3.1 PRD: FR-16 and FR-19 amended. [x] 3.2 Architecture: AD-4 amended. [x] 3.3 UX: DESIGN.md and EXPERIENCE.md amended. [x] 3.4 Other: the benchmark tag (in the 12.14 AC).
- [x] 4 Path: Direct Adjustment.
- [x] 5 Proposal components: §4.
- [x] 6 Final review: approved by the PO's binding decisions (§6).

## 3. Recommended Approach

**Direct Adjustment.** Add two Epic 12 stories and amend the planning artifacts to match. No rollback and no MVP cut.

- **Effort:** 12.14 is M. 12.15 is M, because it includes the Move strip (PO decision 3).
- **Risk:** low to medium.
  - **12.14:** 3D playback on the minimum device has to meet the Story 12.2 baseline (#106). A mid-turn switch has to land exactly on the committed state; AD-4 `Snap` and `renderedFacelets()` make that testable.
  - **12.15:** a picture must never disagree with its note. The prime-mirroring and every-Move tests guard this, and so does the F/B rule.
- **Timeline:** both stories are in phase 3 of the remaining-work plan, ahead of phase 4 (12.3). Visual acceptance of both joins the phase 5 device-only session. `Cube3DRenderSyncTest` runs in phase 3 if an emulator is available, otherwise in phase 5.

## 4. Detailed Change Proposals (all applied)

### 4.1 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

**P1: FR-16 consequences.** After the Physical-cube Mode consequence:
- NEW: "The Learner can switch playback between the 3D cube and the 2D net at any step without losing their place. Playback opens in 3D, except in Physical-cube Mode, where it opens on the 2D net; once the Learner has switched, their choice is remembered (FR-19)."
- NEW: "Each Move can be shown with a small picture of the turn, next to its notation and in the Move strip, unless the Learner turns *Move pictures* off (FR-19). The picture is never the only way the Move is given: the notation and the note are always there."
- Rationale: #175 and #176, v1 scope.

**P2: FR-19 preferences.**
- OLD: "preferences: animation speed, color-blind display, the last Teaching Mode used."
- NEW: "… the last Teaching Mode used, the Solution Playback view (3D cube or 2D net, once chosen), and *Move pictures* (default on)."

**P3: §10 decision line** for 2026-11-17. Memlog entries were appended (a decision and a change).

### 4.2 Architecture (`ARCHITECTURE-SPINE.md`, AD-4)

**A1: First adapter.**
- OLD: "The 3D adapter replaces it behind the same port."
- NEW: "… replaces it behind the same port, except on Solution Playback, where both adapters stay and the Learner picks one. The playback view is a preference (written by `UpdatePreference`), never a `SessionEvent`; switching views builds the other adapter at the committed state (`Snap`) with the same playback position, and nothing in the session changes."
- Rationale: the decision "3D replaces 2D" no longer holds everywhere. The rest keeps AD-4 (committed state only) and the Ownership Matrix (one writer for preferences).
- The memlog entry was appended.

### 4.3 Stories (`epics.md`)

| ID | Location | Change |
|---|---|---|
| E1 | Inventory FR-16 | Adds the view switch (3D by default, the net in Physical-cube Mode, then remembered) and optional move pictures. |
| E2 | Inventory FR-19 | Preferences add the Solution Playback view and Move pictures. |
| E3 | UX-DR4 | Adds the Solution Playback toolbar view switch, labeled with the view it switches to. |
| E4 | UX-DR6 | The arrow also shows on the current Move of Solution Playback in 3D. |
| E5 | UX-DR14 | The Move strip and visible scrubber are shown, read-only with no scrubber in Physical-cube Mode, with move pictures when they are on. |
| E6 | New UX-DR32 | The Move picture glyph and its Settings toggle (default on). |
| E7 | FR coverage map | FR-16 adds Epic 12 (12.14–12.15). FR-19 adds Epic 12 (two preferences). |
| E8 | Epic 12 summary | FRs covered: FR-16 and FR-19 additions. Also adds UX-DR4, UX-DR6, UX-DR14 and UX-DR32. "Also carries" names 12.14, then 12.15, both before 12.3 and 12.5's screenshots. |
| E9 | Story 3.6 | A deferral note: the Move strip and scrubber are built by Story 12.15. |
| E10 | New **Story 12.14** | *Solution Playback in 3D or on the 2D net* (#175). Seven ACs: the default view; the switch persisting through `UpdatePreference` and cleared by *Reset preferences*; a mid-turn `Snap` with the label, chip and SEEN strip unchanged; the Story 5.4 arrow; TalkBack (the 5.5 description, a switch labeled with its target); the 3D sync suite plus mid-turn and physical-mode tests; the Story 12.2 baseline, with `Net2DFrameTimingBenchmark` using the switch's exact tag in `requireNet()`. |
| E11 | New **Story 12.15** | *Move pictures and the Move strip* (#176). Seven ACs: the pure function over every Move; prime mirroring; F/B drawn from the front; the Move strip and visible scrubber (read-only in Physical-cube Mode), closing the 3.6 deferral; the picture next to the label and in the strip, skipped by TalkBack; the Settings toggle (default on, persisted, reset); screenshot samples. |

### 4.4 UX

**DESIGN.md**
- **U1, Cube stage:** a view switch icon button on the Solution Playback toolbar. Its icon and TalkBack label name the view it switches *to*.
  - OLD: "The 3D cube (or the 2D net in P0.1)."
  - NEW: the 3D cube. The 2D net stays as Solution Playback's second view, and it is the opening view in Physical-cube Mode until the Learner picks one.
- **U2, Move arrow overlay:** it also shows on the current Move of Solution Playback in 3D.
- **U3, Playback transport:**
  - the move picture sits before the current Move's notation;
  - a **Move strip** of `notation-chip`s, with the current Move in an `ink-primary` outline and a picture on each chip (at least 20dp);
  - a visible **scrubber** that snaps to Move boundaries, hidden in Physical-cube Mode, where the strip is read-only.
- **U4, new Move picture component:**
  - Ink only: `ink-primary` lines, with the layer shaded in `ink-secondary` at about 35%. Decorative for TalkBack.
  - **R / L / U / D:** a curved arrow (R up, L down, U left, D right).
  - **F / B:** seen from the front. B's round arrow is drawn behind the grid and reads counter-clockwise.
  - **M / E / S:** a straight arrow. M goes down on the middle column. E goes right on the middle row. S is drawn on the Up face from above, with a "top" tick.
  - **Prime** reverses the arrow. A **half turn** adds a superscript 2.
  - **Wide:** two layers shaded. Fw and Bw get a doubled round arrow.
  - **x / y / z:** the R, U or F arrow across a face with a doubled outline.

**EXPERIENCE.md**
- **X1, Settings row:** adds *Move pictures* (default on, next to color-blind glyphs).
- **X2, Solution Playback screen row:** "on the 3D cube or the 2D net".
- **X3, Cube stage behavior row:**
  - the opening view, and the remembered choice after the first switch;
  - *Reset preferences* clears the choice;
  - a mid-turn switch is a jump with no animation (AD-4), and the frame label, chip and SEEN strip are unchanged;
  - the 3D view has the arrow and the TalkBack description.
- **X4, Playback transport behavior row:**
  - a tap on a strip chip or a scrub jumps to that Move boundary (AD-4);
  - Physical-cube Mode: a read-only strip and no scrubber;
  - the pictures follow *Move pictures*, and TalkBack skips them.

The UX memlog entry was appended. No mockup is regenerated: visual acceptance happens on the device in phase 5.

## 5. Derived choices (not new product decisions)

These follow from the binding decisions and the existing artifacts. The story builder should keep them unless the PO says otherwise.

| # | Choice | Derived from |
|---|---|---|
| C1 | There is one playback-view preference. Until the Learner first switches, it is unset, and the view follows the mode (3D virtual, net physical). Once it is set, it applies in both modes. *Reset preferences* unsets it. | PO decision 1 ("after the first switch, the remembered choice wins"), FR-19 separate resets. |
| C2 | In Physical-cube Mode, the Move strip is read-only and the scrubber is hidden. | FR-16 (each step advances only on *Done*) and the existing physical controls, which already replace the transport. |
| C3 | The Move strip and the pictures are part of the shared transport, so they also appear in Demonstrations and Algorithm detail. Acceptance is on Solution Playback. | UX-DR14 and EXPERIENCE.md: one shared transport (AD-20). |
| C4 | The pure move-to-picture function has no Android imports. `:app` only draws its output. | AGENTS.md (logic in pure modules, JVM tests). The builder picks the module under AD-1. |

## 6. Decision log

**Product owner decisions (binding, recorded verbatim):**

1. #175 default view: Solution Playback opens in 3D, except in Physical-cube Mode where it opens on the 2D net (all 54 stickers to compare with EXPECTED). After the first switch, the remembered choice wins.
2. #176 "Move pictures" defaults to ON.
3. #176 scope INCLUDES the UX-DR14 Move strip with a visible scrubber (Effort M), closing epic-3-deferred-3-6-ux-dr14-move-strip.
4. #176 B and F glyphs are drawn as seen from the FRONT (the standard convention): F = the front 3x3 grid with a clockwise round arrow; B = the round arrow drawn behind the grid, so it reads counter-clockwise from the front, while the spoken move note still says "Turn the Back face clockwise". Reference image: /mnt/e/Dynamic/IndFun/RubikXplorer/.git/bmad-orchestrate/issue-176-fb-glyph-reference.png (read it).
5. One Sprint Change Proposal covers both issues. Build order: #175 first, then #176 (the preference schema and playback stage settle first).

**Process:** batch mode, under the orchestrator.

## 7. Open items for visual acceptance (non-blocking)

These are UX drawing details. The issue said they "need designs", and none of the PO decisions fixes them. DESIGN.md proposes a design for each. Confirm or adjust them in the phase 5 device session, as a 12.15 follow-up:

1. **S slice:** drawn on the Up face seen from above (middle row shaded, rightward arrow, a "top" tick), because S is not visible as a row or column from the front.
2. **Wide Moves:** two layers shaded. Fw and Bw use a doubled round arrow.
3. **Cube Rotations x/y/z:** the R, U or F arrow across the whole face, with a doubled face outline and no shading.

## 8. Implementation Handoff

- **Scope:** Moderate. Backlog reorganization: two stories are added and ordered ahead of 12.3.
- **Sprint planning (PO/SM):**
  - add `12-14-solution-playback-in-3d-or-on-the-2d-net` and `12-15-move-pictures-and-the-move-strip` to `sprint-status.yaml` as `backlog`;
  - link `epic-3-deferred-3-6-ux-dr14-move-strip` to 12.15, and close it when 12.15 is done.
- **Developer:** build 12.14 first, for #175. Then build 12.15, for #176, which closes the 3.6 deferral.
- **QA / device session (phase 5):**
  - run `Cube3DRenderSyncTest` (if it did not run in phase 3);
  - check the 3D playback FPS against the 12.2 baseline;
  - give TalkBack and visual acceptance of both stories and the §7 items.
- **Success criteria:**
  - Solution Playback switches views mid-turn without losing its place or its labels;
  - the view and *Move pictures* survive a restart and are cleared by *Reset preferences*;
  - every Move has a picture that matches its note's direction;
  - the Move strip and scrubber ship;
  - the store screenshots are taken after both stories.
