# Sprint Change Proposal: Drag preview gestures and the view-rotated indicator

- **Date:** 2026-11-18
- **Trigger:** GitHub issues #206 (*Virtual cube gestures misfire and block the Course: preview on drag, commit on release*) and #207 (*Virtual cube: show when the view is rotated, add Reset view and face letters*). Both come from PO internal testing and were triaged in a BMAD party on 2026-11-18, after device session C (Story 10.8).
- **Mode:** Batch
- **Status:** Approved by the PO on 2026-11-18, after a `bmad-review` pass (adversarial, edge-case, structure and prose lenses; findings checked against the code, see §7). The PO's decisions are in §2. The planning edits in §5 have been applied.
- **Scope:** Moderate. Two new stories are added to Epic 12. They run before Story 12.3 and the pilot (12.4). Two open Epic 5 action items are absorbed. FR-1, FR-2, AD-4, UX-DR4, UX-DR5, UX-DR26 and UX-DR29 are amended, and **UX-DR33** (drag preview) and **UX-DR34** (view-rotated badge and face labels) are added.

## 1. Issue Summary

**#206.** Face-turn gestures on the virtual cube often make a different Move than the Learner intended. The PO could **not finish the first layer of the Course** by gesture, so gestures currently block the core learning path.

What the code does today (`app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt:432-540`, `core/domain/src/main/kotlin/dev/cubentia/domain/input/GestureInterpreter.kt`):

- A drag already commits on release. But nothing shows while dragging, so the Learner can't see which layer or direction was picked until the Move has happened.
- `GestureInterpreter.interpret` reads only the **first and last** touch points (`TouchPath(listOf(first, last))`). A curved or wobbly drag is judged by its chord, and a drag whose two axes are close (`DOMINANCE = 2`) gives no Move at all.
- A drag on a **middle** row or column already commits a slice (M/E/S) (`layer == 0 → SliceTurn`). A Learner aiming at an outer face who starts one sticker too far in gets a slice with no warning. This is the most likely cause of the "different Move" reports.
- Wide Moves (`r`, `u`, …) can only be entered from the Move key bar ("more").

**#207.** Notation stays in the cube frame (PRD FR-2, correct by design), so after a View Rotation `R` can look like `L'` from where the Learner is looking. Nothing shows that the view is off its home position. A **Reset view** button exists (`ViewControls.kt:22`, `stage:resetView`), but it's an icon-only toolbar button, and the PO did not find it. Double-tap reset can't be discovered either. No face letters are drawn.

**Issue type:** a usability failure found in stakeholder testing that blocks a core journey (UJ-1 Course), plus a new requirement.

## 2. PO Decisions (2026-11-18, this session)

These bind the rest of the proposal.

1. **Placement:** Epic 12, Stories 12.16 and 12.17. Epic 5 stays closed.
2. **Middle-layer drag:** "Keep slices, keep the ghost turn." A middle-row drag previews and commits M/E/S. This replaces the #206 party note that slices are not made by gesture.
3. **Wide Moves:** a parallel two-finger drag on the cube turns two layers together as a wide Move. The Move key bar still offers wide and slice Moves under "more".
4. **Rotated view:** show the View rotated badge with its Reset view action and letters beside the visible faces. Remove the separate toolbar Reset view button.
5. **Preview and cancel:** show the turn during the drag. Lift to commit, or drag back to within the lock distance of where the drag began and lift to cancel. Leaving the stage does not cancel.
6. **Face letters at home:** hidden at home; shown only while the view is rotated (PO, at approval).

## 3. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 12 gets **12.16** (#206) and **12.17** (#207), both before 12.3. Epic List coverage lines amended; Stories 5.2 and 5.3 annotated (§5.1). |
| PRD | FR-1 and FR-2 amended, FR-1's "wide/slice by button only" assumption superseded, a §10 decision line added (§5.2). Nothing is cut. |
| Architecture | AD-4 amended: path-based, multi-pointer `GestureInterpreter`; a renderer-local drag preview with a defined handoff to the commit animation; a gesture host separate from the renderer (§5.3). No new port, module, entity or flag. |
| UX | EXPERIENCE.md and DESIGN.md amended; UX-DR4, 5, 26, 29 amended; UX-DR33 and UX-DR34 added (§5.4). |
| Code (later, not in this proposal) | `GestureInterpreter` and a gesture host split out of `Cube3DRenderer.kt` (622 lines); `CubieModel` / `PresentationQueue` (held ghost turn, `finishAll`); `CubeMesh` emphasis flags; the stage overlays (badge, face labels) and `ViewControls.kt`; `ViewOrbit` (at-home test, animated reset); `FreeCubeScreen` (reduce motion, `ContentStrings`); `en.json` keys; six screen-test files that use `stage:resetView`. |
| Sprint status | Not changed here. Sprint planning adds `12-16-…` and `12-17-…`. Completing the stories closes `epic-5-retro-item-19-split-cube3drenderer-kt-551-lines-gestur` (12.16, gesture host part) and `epic-5-retro-item-18-double-tap-reset-also-checks-tap-distanc` (12.17). |
| Device | One short device session after 12.17 on the Lenovo Tab M10 Plus 3rd Gen (minimum) and the Galaxy S24 FE. |
| Other work | #195 (Quick Scan perspective guide) is independent. Epics 10, 11 and 13 are unaffected. |

### Checklist summary

- [x] 1 Trigger and evidence: #206/#207; the PO could not finish the first layer by gesture; the code facts in §1.
- [x] 2 Epic impact: Epic 12 gains 12.16 and 12.17 ahead of 12.3; Epic 5 stays closed (PO decision 1); every 3D stage shares the changes; two Epic 5 action items absorbed.
- [x] 3 Artifacts: PRD FR-1/FR-2, AD-4, DESIGN.md, EXPERIENCE.md amended; no CI, deployment or flag change.
- [x] 4 Path: Direct Adjustment. Rollback gains nothing; an MVP cut is not acceptable, because gestures are the Course's main input.
- [x] 5 Final review: approved by the PO, 2026-11-18.

## 4. Recommended Approach

**Direct Adjustment.** Add two Epic 12 stories and amend the planning artifacts. No rollback and no MVP cut.

- **Effort:** 12.16 is **L**: a path-based multi-pointer interpreter, the ghost turn and its handoff to the commit animation, two-finger wide Moves, the gesture host split, and TalkBack. 12.17 is **M**: the badge on five stages, an animated reset, face-label geometry, the double-tap distance fix, and migrating six test files off `stage:resetView`.
- **Risk (12.16): medium.**
  - The ghost turn must hand over to the commit animation across the asynchronous `SessionHost` dispatch (AD-11) without a frame at 0° and without `CubieModel.begin`'s `check(turn == null)` failing. §5.3 A2 pins the handoff.
  - Two-finger drags now have two meanings on the cube (a parallel drag is a wide Move, a twist is z). A pure classifier with JVM fixtures keeps this testable.
  - The ghost turn must never leak into committed state; a test drags, cancels, and asserts nothing changed.
  - Frame time during a drag must stay at the Story 12.2 baseline on the Lenovo (NFR-2 ≥ 30 FPS).
  - If 12.16 runs long, the two-finger wide Move can split into its own story without changing the rest.
- **Risk (12.17): low.** The face labels are drawn in 2D over the GL view, so the shader doesn't change.
- **Timeline:** next in the queue, ahead of 12.3. The release date moves only by the extra device check, which can be combined with the #195 hands-on session.

## 5. Detailed Change Proposals

### 5.1 Stories (`epics.md`)

**S1: new Story 12.16, appended after Story 12.15.**

```markdown
### Story 12.16: See the turn while dragging, commit on release

As a Learner turning the cube by touch,
I want to see which layer will turn, and which way, while I drag,
So that I only make the Move I meant, and can back out before it counts.

**Refs:** FR-1, FR-2 · NFR-1, NFR-2, NFR-4, NFR-7 · AD-4, AD-11, AD-17 · AR-8 · UX-DR5, UX-DR6, UX-DR26, UX-DR27, UX-DR29, UX-DR33 · GitHub #206 · action item `epic-5-retro-item-19-split-cube3drenderer-kt-551-lines-gestur` (gesture host part) · `sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md`

**Runs before** Story 12.17 and Story 12.3. Needs a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (batched with 12.17).

**Acceptance Criteria:**

**Given** a pure `GestureInterpreter.preview(input: GestureInput, pose: ViewPose) → DragPreview?` in `:core:domain`, where `GestureInput` carries one touch path or two (one per finger), each read whole, not only its first and last points
**When** a one-finger drag starts on a sticker and has moved past the lock distance
**Then** it locks the drag axis and layer for the rest of the gesture and returns `DragPreview.Active(move, degrees)`: the Move in the cube frame (whatever the view pose) and a signed angle from the finger's travel along the locked tangent, clamped to [−90°, +90°]; a wobbly or curved drag along a row keeps its lock; before the lock it returns null; JVM fixtures cover pose × start sticker × direction, including curved paths and over-drag past 90°

**Given** a locked drag
**When** the finger comes back within the lock distance of where the drag began
**Then** `preview` returns `DragPreview.CancelZone` (never null once locked), the ghost layer eases back to 0°, and leaving the zone on the other side flips the Move (R ↔ R'), with the angle starting again from 0°

**Given** a locked one-finger drag
**When** the finger moves
**Then** that layer turns on screen with the finger (the ghost turn); all 9 cubies of the layer, centre included, get the `sticker-highlight` outline through a preview flag that is OR-ed with any Lesson `NetEmphasis` (never replacing it, and never taking part in the `uPulse` fill tint); a ghost arrow with the Move in `notation` (for example "R'") follows the finger; a teaching arrow (Demonstration, hint rung 2, playback) is hidden while the ghost is shown and comes back after; the preview is exposed to UI tests under its own semantics key (`PreviewedMoveKey`), separate from `RenderedArrowKey`; nothing is committed and `renderedFacelets()` still returns the committed state

**Given** a drag on a middle row or column
**When** it locks
**Then** it previews and commits the slice (M, E or S) exactly like an outer layer, so a slice is never a surprise (PO decision 2)

**Given** a two-finger drag that starts with at least one finger on the cube and both fingers moving in parallel
**When** it locks
**Then** it previews and commits the wide Move of the outer layer (±1 along the locked axis) that either finger touches, for example `r` when one finger is on the R layer and the other on M; if both fingers are on the middle layer, or on opposite outer layers, there is no preview and no Move; a two-finger twist still commits z on release, with no ghost turn; a two-finger drag that starts off the cube is still a View Rotation; the parallel / twist / pinch classifier is pure and has JVM fixtures (PO decision 3). With TalkBack on, a two-finger drag reaches the app as a one-finger drag, so wide Moves come from "more" on the Move key bar; that is expected, and NFR-7 is still met

**Given** a gesture in progress
**When** a second finger lands after a one-finger drag has locked, or one finger lifts during a locked two-finger drag
**Then** the late finger is ignored and the locked preview is kept; in a two-finger drag the preview freezes when the first finger lifts and resolves (commit or cancel) when the last finger lifts

**Given** a previewed Move (`Active`)
**When** the finger lifts, on or off the stage
**Then** the Move commits through the same `MoveIntent` → `MoveCommitted` path as a Move key (AD-4); the renderer holds the ghost layer at its angle until the matching `Animate(move)` arrives, then continues the turn from that angle over the remaining duration, with no frame back at 0° (§5.3 A2); the input is acknowledged within 100 ms, and the light haptic tick fires (switchable off)

**Given** a locked drag in `CancelZone`
**When** the finger lifts
**Then** the layer snaps back, nothing commits, no tick fires, and the Cube State is unchanged; the same happens if the system cancels or consumes the pointer stream mid-preview

**Given** a drag that never locks (below the confidence threshold)
**When** it ends
**Then** no preview was shown, no intent is returned, nothing is announced, and the Cube State is unchanged

**Given** a stage where Moves are blocked (no Move input is wired, for example a Lesson in Physical-cube Mode before *I did something else*)
**When** the Learner drags on the cube
**Then** no ghost turn is shown

**Given** a presentation queue that is still animating, or a Lesson reorientation or Demonstration job that is still running
**When** a drag starts on the cube
**Then** the job is cancelled and the queue finishes every outstanding item at once in order (`PresentationQueue.finishAll()`: commit the active `Animate`, then apply each waiting `Animate` and `Snap` without animating, closing any open `Cube3D.turn` trace section), so `renderedFacelets()` equals the last committed state before the preview is built; no committed Move is dropped

**Given** TalkBack is on
**When** a preview becomes `Active` or changes Move
**Then** the Move's plain name (from the existing plain-name helper) is announced politely, for example "R prime: right face counter-clockwise"; "Turn cancelled" is announced only when a locked drag ends in `CancelZone`

**Given** a Lesson in *Test me* or *Let me try*
**When** the Learner drags
**Then** the preview names the Move the Learner is making, never the expected one (the Tutor still judges only the committed Move)

**Given** new learner-facing copy ("Turn cancelled")
**When** it is added
**Then** it is a content key in `core/domain/src/main/resources/content/strings/en.json` read through `ContentStrings`, and `app/src/main/res/values/strings.xml` gains no learner text (the `checkLearnerText` gate stays green)

**Given** the gesture code
**When** the story is done
**Then** touch handling lives in its own gesture host component, not in `Cube3DRenderer.kt`; the renderer only draws committed state, the presentation queue and the renderer-local ghost turn

**Given** the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE
**When** the PO plays Stage 1 and Stage 2 Lessons by gesture
**Then** they complete them without an unintended Move, cancel works, and frame time during a drag stays at the Story 12.2 baseline (≥ 30 FPS on the Lenovo, NFR-2)
```

**S2: new Story 12.17, appended after Story 12.16.**

```markdown
### Story 12.17: Know when the view is rotated, and reset it in one tap

As a Learner who rotated the cube to look for a sticker,
I want to see that my view is turned, which face is which, and a clear way back,
So that I can still predict what R or U will do.

**Refs:** FR-2 · NFR-7 · AD-4, AD-17 · UX-DR4, UX-DR5, UX-DR29, UX-DR34 · GitHub #207 · action item `epic-5-retro-item-18-double-tap-reset-also-checks-tap-distanc` · `sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md`

**Depends on** Story 12.16 (the gesture host owns double-tap). **Runs before** Story 12.3.

**Acceptance Criteria:**

**Given** `ViewOrbit` with an "at home" test (within a small tolerance of `ViewOrbit.DEFAULT`)
**When** the view leaves home by a drag, the overflow arrow buttons or any other View Rotation
**Then** a **View rotated** badge shows in the stage's top-left overlay, and it hides again once the view is back home

**Given** the five 3D stages (Lesson, free cube, Demonstration, Solution Playback in 3D, Library detail)
**When** the badge shows
**Then** it sits right after the frame label where one is shown, and first in the top-left overlay where none is (today virtual Solution Playback and a Lesson *Show me* Demonstration have no frame label); the overlay wraps (a flow row) instead of clipping at 200% font scale; the badge's touch target is at least 48dp; TalkBack focus reaches it right after the frame label, or first on the stage when there is none; the 2D net has no badge

**Given** the view is rotated
**When** the Learner taps the badge's **Reset view** action or double-taps the stage
**Then** the view animates home by the shortest path in about 200 ms (it jumps when reduce motion is on), the badge hides, and the Cube State, Notation and Move key labels are unchanged; a drag that starts during the reset animation cancels it and starts from the current view; the free cube stage receives the reduce-motion setting like the other stages

**Given** the toolbar
**When** any 3D stage shows
**Then** there is no separate reset-view button at any width (Reset view lives on the badge); the ⋮ view menu with the View Rotation arrow buttons stays, so every rotation and its reset have a non-gesture route (NFR-7)

**Given** the existing screen tests that use `stage:resetView` (`FreeCubeScreenTest`, `LessonScreenTest`, `SolutionPlaybackTest`, `DemonstrationPlayerTest`, `AlgorithmDetailScreenTest`, `ScreenAudit`)
**When** the toolbar button is removed
**Then** they rotate the view first (for example through `stage:viewMenu` → `viewRotate:left`) and then use the badge's tag; `ScreenAudit` checks the badge's route and 48dp target while rotated, and checks that no badge is shown at home

**Given** double-tap on the stage
**When** two taps are close in time **and** in distance (`doubleTapSlop`)
**Then** the view resets; two far-apart taps don't reset it (a test covers far-apart taps; closes `epic-5-retro-item-18-…`)

**Given** a pure face-label helper in `:core:domain` (pose + stage size → label anchors)
**When** the view is rotated
**Then** a face gets a letter (U, D, F, B, R, L) in `notation`, in the cube frame, only when it faces the camera by more than a minimum angle (no labels on near edge-on faces); each letter sits outside the cube's projected silhouette, offset from its face centre away from the cube, and clamped inside the stage, so it never overlaps a sticker or the color-blind glyphs; JVM tests over a sweep of orbits and stage sizes (down to the smallest compact stage) assert no overlap with any sticker quad; the letters hide at home (PO decision 6)

**Given** TalkBack is on
**When** the view leaves or returns home
**Then** "View rotated" or "View reset" is announced once, and the badge and its Reset view action have content labels

**Given** new learner-facing copy ("View rotated", "Reset view" on the badge, "View reset")
**When** it is added
**Then** it is a content key in `en.json` read through `ContentStrings`, which the shared stage component receives on every stage, free cube included; `strings.xml` gains no learner text (the `checkLearnerText` gate stays green)

**Given** screenshot tests
**When** they run
**Then** they cover the home view (no badge, no letters) and a rotated view (badge and letters) in light and dark themes, on compact and expanded widths
```

**S3: Story 5.2 annotation** (after its last AC):

```markdown
*Annotated by SCP 2026-11-18: device testing showed gestures misfire (GitHub #206). Story 12.16 replaces first/last-point recognition with a path-based preview: the layer turns with the finger and commits on release, slices are previewed, and a two-finger drag on the cube makes wide Moves.*
```

**S4: Story 5.3 annotation** (after its last AC):

```markdown
*Annotated by SCP 2026-11-18: the reset-view affordance was not discoverable (GitHub #207). Story 12.17 adds a View rotated badge with a one-tap Reset view, removes the separate toolbar reset-view button (Reset view now lives on the badge; double-tap and the overflow View Rotation arrows stay), adds face labels while rotated, and adds a distance check on double-tap. Off-cube two-finger drags are still a View Rotation; on-cube parallel two-finger drags become wide Moves (Story 12.16).*
```

**S5: Epic 12 summary and coverage** (Epic List entry for Epic 12):

- OLD: `**FRs covered:** none new (verifies all), plus FR-16 and FR-19 additions in 12.14–12.15.`
- NEW: `**FRs covered:** none new (verifies all), plus FR-16 and FR-19 additions in 12.14–12.15, and FR-1/FR-2 gesture and view changes in 12.16–12.17.`
- Add `UX-DR5, UX-DR26, UX-DR33, UX-DR34` to the **Also:** list (around `UX-DR29, UX-DR32`).
- Append to the **Also carries** paragraph: `It also carries 12.16 (drag preview, #206) and 12.17 (view-rotated badge, #207), which run before 12.3 (sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md).`

**S6: requirements inventory and coverage map:**

- FR-1 line. OLD: `…buttons cover every Move type, including wide and slice turns (wide/slice by button only in MVP [ASSUMPTION]).` NEW: `…buttons cover every Move type, including wide and slice turns. A drag previews its Move and commits on release; a middle-layer drag makes a slice and a two-finger drag on the cube makes a wide Move (SCP 2026-11-18).`
- Coverage. OLD: `FR-1: Epic 1 (buttons, 2D net, all Move types) · Epic 5 (3D, gestures, 250 ms animation)` NEW: `… · Epic 12 (drag preview, slices and wide Moves by gesture, 12.16)`
- Coverage. OLD: `FR-2: Epic 5 — View Rotation independent of Cube State` NEW: `FR-2: Epic 5 — View Rotation independent of Cube State · Epic 12 (view-rotated badge, Reset view, face labels, 12.17)`

**S7: UX-DR amendments (inventory in `epics.md`):**

- UX-DR4: replace `reset-view affordance top-right and double-tap reset;` with `double-tap reset; a View rotated badge with a one-tap Reset view in the top-left overlay (after the frame label when shown) while the view is off home, and face letters beside the visible faces (SCP 2026-11-18);`
- UX-DR5: after `"more" for wide and slice turns,` add `(also reachable by gesture, UX-DR26),`
- UX-DR26. OLD: `one-finger drag starting on a sticker along a row/column turns that layer; drag starting off the cube or two-finger drag = View Rotation;` NEW: `one-finger drag starting on a sticker along a row/column previews that layer's Move (an outer face or a middle slice) and commits it on release (UX-DR33); a parallel two-finger drag starting on the cube previews and commits the wide Move; a one- or two-finger drag starting off the cube = View Rotation;`. Remove `[ASSUMPTION: gesture mapping pending confirmation.]` (confirmed by the PO). Replace `View Rotation has no label.` with `The View rotated badge is shown while the view is off home (UX-DR34).`
- UX-DR29: after `View Rotation arrow buttons in the stage overflow;` add `the previewed Move announced by name, and "View rotated" / "View reset" announced;`
- Add **UX-DR33:** `Drag preview: once a drag locks, the layer turns with the finger (clamped to a quarter turn) and is highlighted without replacing Lesson emphasis, and a ghost arrow carries the Move in notation (cube frame) while any teaching arrow is hidden; reversing through the start flips the Move; lift commits through the Move key path (also when lifted off the stage) and the turn continues from the ghost angle; returning to within the lock distance of where the drag began and lifting cancels with a snap back and no tick; TalkBack announces the previewed Move (SCP 2026-11-18).`
- Add **UX-DR34:** `View-rotated badge and face labels: a "View rotated" badge with a Reset view action in the stage's top-left overlay (after the frame label when shown) while the view is off home; reset animates home (jumps under reduce motion); face letters U/D/F/B/R/L in notation outside the cube's silhouette beside each face that clearly faces the camera, never on stickers, while rotated (SCP 2026-11-18).`

### 5.2 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

**P1: FR-1 body.**

- OLD: `Gestures cover face turns and Cube Rotations; buttons cover every Move type, including wide and slice turns. [ASSUMPTION: wide and slice turns can be entered by button only in MVP.]`
- NEW: `Gestures cover face turns, slice turns, wide turns and Cube Rotations; buttons cover every Move type. A drag shows the Move it will make while the finger moves, and commits it only when the finger lifts (SCP 2026-11-18).`

**P2: FR-1 consequences**, added after the 250 ms consequence:

```markdown
- While a turn gesture is in progress, the turning layer and the Move name are shown, and the Cube State is unchanged until release; a cancelled drag changes nothing.
- The previewed Move name is in the cube frame, so it is the Move that will commit whatever the view angle.
```

**P3: FR-2 consequences**, added:

```markdown
- While the view is off its home position, the stage says so and offers a one-tap Reset view (SCP 2026-11-18).
```

**P4: §10**, add: `- FR-1 — gestures preview on drag and commit on release; slices by middle-layer drag, wide Moves by two-finger drag on the cube (SCP 2026-11-18, GitHub #206).`

### 5.3 Architecture (`architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md`)

**A1: AD-4, Gestures bullet.**

- OLD: `**Gestures:** gesture interpretation is a pure GestureInterpreter (touch path + view pose → MoveIntent?). Below the confidence threshold it returns no intent.`
- NEW: `**Gestures:** gesture interpretation is a pure GestureInterpreter (one or two touch paths + view pose → DragPreview? while dragging, MoveIntent? on release). It reads whole paths and locks the axis and layer once the drag passes the lock distance; once locked it reports Active(move, degrees ∈ [−90°, +90°]) or CancelZone, never null. Below the confidence threshold it returns nothing. Touch handling lives in a gesture host separate from the renderer.`

**A2: AD-4, new bullet after View Rotation:**

```markdown
  - **Drag preview:** like View Rotation, the ghost turn of a dragged layer is renderer-local. It never becomes a `SessionEvent` and never changes `renderedFacelets()`, which always returns committed state. Its highlight is a separate per-cubie flag OR-ed with `NetEmphasis`.
    - **Start:** a drag that starts while the queue is busy first runs `PresentationQueue.finishAll()` (every outstanding item applied in order, none dropped, trace sections closed), so the preview always starts from the committed state.
    - **Commit handoff:** on release the renderer holds the ghost at its angle. When the matching `Animate(move)` reaches the queue, `CubieModel.begin` adopts the held turn and continues from its angle over the remaining duration (`1 − |degrees| / 90` of a turn) instead of starting from 0°. If a different item arrives first, or no commit follows (input blocked), the held ghost snaps back to 0° before that item starts.
    - **Cancel:** the layer snaps back with no event (SCP 2026-11-18).
```

### 5.4 UX

**U1: EXPERIENCE.md, Interaction Primitives → Cube gestures.** Replace the three bullets with:

```markdown
  - A one-finger drag that **starts on a sticker** and runs along a row or column previews that layer's Move: the layer turns with the finger (up to a quarter turn), it is highlighted, and the Move name (for example `R'`) follows the finger. A middle row or column previews a slice (M, E, S).
  - A two-finger drag that **starts on the cube**, with both fingers moving the same way, previews the wide Move of the outer layer under the fingers (for example `r`).
  - **Lift to commit**, also when the finger has slid off the stage; the turn finishes from where the finger left it. To cancel, drag back to within the lock distance of where the drag began and lift: the layer snaps back and nothing changes.
  - A drag that starts while a Move is animating first finishes the animation at once, then previews.
  - A one- or two-finger drag **starting off the cube** is a View Rotation.
  - With TalkBack on, a two-finger drag reaches the app as a one-finger drag, so wide Moves come from "more" on the Move key bar.
  - Below the confidence threshold, a drag shows nothing and does nothing. It never guesses a turn.
```

Replace `**Double-tap** on the stage resets the view.` with `**Double-tap** on the stage resets the view; so does the **Reset view** action on the View rotated badge. The reset animates home, or jumps when reduce motion is on.`

**U2: EXPERIENCE.md, Cube stage row.** After `Double-tap resets the view.` add: `While the view is off home, a View rotated badge with Reset view shows in the stage's top-left overlay (after the frame label when shown), and face letters sit beside the faces that face the camera (SCP 2026-11-18).`

**U3: EXPERIENCE.md, Accessibility Floor → TalkBack.** Add: `- The previewed Move is announced by name while dragging; "Turn cancelled" (only when a locked drag is cancelled), "View rotated" and "View reset" are announced once.`

**U4: DESIGN.md, Cube stage.** Replace `A **reset-view** affordance sits in the top-right corner.` with:

```markdown
  - There is no separate reset-view button in the toolbar at any width; Reset view lives on the View rotated badge. The ⋮ view menu with the View Rotation arrow buttons stays.
  - While the view is off home, a **View rotated** badge sits in the stage's top-left overlay, right after the frame label when one is shown: an outlined `rounded.sm` tag in `label` with a ⟲ icon and the text "View rotated · Reset view", with a 48dp touch target. Tapping it resets the view. The overlay wraps at large font sizes.
  - While the view is off home, **face labels** U, D, F, B, R, L in `notation` sit outside the cube's silhouette beside each face that clearly faces the camera, in `ink-secondary`, kept inside the stage. They are never drawn on a sticker.
```

**U5: DESIGN.md, new component after Move arrow overlay:**

```markdown
- **Drag preview**: while a turn gesture is locked, the dragged layer turns with the finger (at most a quarter turn) and all its cubies get the `sticker-highlight` outline, added to any Lesson emphasis and without the pulse tint. A ghost Move arrow (the Move arrow overlay at 60% opacity) carries the Move in `notation` near the finger; a teaching arrow on the stage is hidden while the ghost shows. With reduce motion on, the layer stays still and only the arrow and label show.
```

## 6. Implementation Handoff

**Scope: Moderate.** Requires backlog reorganization (two stories ordered ahead of 12.3), then direct development.

| Who | Does |
|---|---|
| PO (hebelmx) | Approved 2026-11-18. Run the device check after 12.17. |
| SM / sprint planning | Add `12-16-see-the-turn-while-dragging-commit-on-release` and `12-17-know-when-the-view-is-rotated-and-reset-it-in-one-tap` to `sprint-status.yaml` as backlog, ahead of 12.3. Close the two Epic 5 action items when the stories are done. |
| Dev (via `/bmad-orchestrate story 12-16-…`, then 12-17) | Implement. JVM fixtures first for `GestureInterpreter.preview`, the two-finger classifier and the face-label helper. |
| Architect | Review the gesture host split and the AD-4 drag preview handoff in the 12.16 PR. |

**Success criteria:** the Story 12.16 and 12.17 ACs pass, including the batched PO device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (Stage 1–2 Lessons completed by gesture with no unintended Move, at ≥ 30 FPS during a drag). This closes #206 and #207.

## 7. Review Record (`bmad-review`, 2026-11-18)

The proposal went through four review lenses. Each code claim was checked against the source before it was accepted.

| Finding (lens #) | Verified in code | Resolution |
|---|---|---|
| Commit handoff: `CubieModel.begin` checks `turn == null` and resets the angle; dispatch is async (adv 2, edge 6) | `CubieModel.kt:96-97`, `PresentationQueue.kt:41-53` | §5.3 A2 *Commit handoff*; 12.16 lift AC |
| Mid-animation drain via `Snap` needs a target the renderer doesn't hold (adv 1, edge 7) | `PresentationQueue.snapNow(target)` clears waiting items; `LessonScreen.kt:578` reorientation job | Replaced with `finishAll()` and cancelling the job (12.16 AC, A2 *Start*) |
| Cancel zone vs never-locked; angle clamp (adv 3, edge 1–2) | design gap | `DragPreview.Active` / `CancelZone`, ±90° clamp, reversal rule |
| Single-path API can't carry two fingers; middle-layer resolution (adv 4–5, edge 3) | `GestureInterpreter.interpret(path, pose)` | `GestureInput` with one or two paths; outer-layer rule; no preview on {0,0} or {+1,−1} |
| Late second finger, early lift, OS cancel (edge 4–5) | design gap | 12.16 multi-pointer and cancel ACs |
| Highlight collides with `NetEmphasis` and `uPulse`; dual arrows (adv 6–7, edge 8) | `cube_fragment.glsl:23-25`, `CubeMesh.emphasisFlags` | Separate OR-ed flag, no pulse, teaching arrow hidden, `PreviewedMoveKey` |
| No frame label on some 3D stages; 200% font (adv 8, edge 10) | `SolutionPlayback.kt:376` (physical only), `DemonstrationPlayer.kt:151` | Top-left overlay rule, flow row, 48dp, focus order (12.17) |
| `FreeCubeScreen` has no reduce motion; reset is synchronous (adv 9, edge 9) | `FreeCubeScreen.kt` passes no `reduceMotion`; `resetView()` assigns `DEFAULT` | Animated reset, reduce-motion wiring, drag cancels the reset (12.17) |
| Face-label grazing angles and small stages (adv 10, edge 11) | design gap | Pure helper, facing threshold, silhouette offset, clamp, sweep tests |
| `checkLearnerText` fails on new `strings.xml` text (adv 11) | `build-logic/.../CheckLearnerTextTask.kt`, `app/learner-text-baseline.txt` | New copy in `en.json` via `ContentStrings` (12.16, 12.17) |
| Removing `stage:resetView` breaks screen tests (edge 12) | used in 6 test files incl. `ScreenAudit.kt` | 12.17 test-migration AC |
| Structure: PO decisions at the end; long checklist; duplicate success criteria; detailed impact rows | — | Decisions moved to §2; checklist condensed (kept as the workflow trace); success criteria and impact rows condensed |
| Prose: "Story 5.2 and 5.3", "it closes", duplicate *Risk* labels, "It needs", "Wide entry" | — | All applied |
