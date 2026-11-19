# Epic 12 Context: Release-ready for learners and reviewers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the app (Cubentia) pass every v1.0 release gate and give a hiring manager a clean five-minute evaluation. It must be accessible, fast and stable on the reference devices, private and offline by construction, with the pilot's findings fixed, a polished Play listing and a public portfolio repo. The epic also carries pre-pilot fixes (12.6–12.13), two v1 playback features (12.14 view switch, 12.15 Move pictures and Move strip), and two gesture/view fixes from PO testing (12.16 drag preview, #206; 12.17 view-rotated badge, #207). Gestures misfired so badly that the PO could not finish the Course's first layer by touch, so 12.16 unblocks the core learning path. All of 12.14–12.17 ship before Story 12.3 and the store screenshots of Story 12.5.

## Stories

- Story 12.1: Accessibility audit and fixes
- Story 12.2: Performance and compatibility on the reference devices
- Story 12.3: Stability, size and privacy hardening
- Story 12.4: Pilot findings fixed and release gates passed
- Story 12.5: Store listing and public portfolio repo
- Story 12.6: Rebrand to Cubentia
- Story 12.7: Physical progress is recorded only when true
- Story 12.8: The mismatch ladder resolves a mismatch
- Story 12.9: One source for physical copy; skip-check needs the virtual cube
- Story 12.10: Prove the release-permission gate end to end
- Story 12.11: Sessions end when practice closes or progress resets
- Story 12.12: 2-look rules reconciled
- Story 12.13: Closing a session is durable when a write fails
- Story 12.14: Solution Playback in 3D or on the 2D net
- Story 12.15: Move pictures and the Move strip
- Story 12.16: See the turn while dragging, commit on release
- Story 12.17: Know when the view is rotated, and reset it in one tap

## Requirements & Constraints

- **Gestures (FR-1, amended):** gestures cover face, slice, wide turns and Cube Rotations; buttons cover every Move type. A drag shows the Move it will make while the finger moves (layer turning, Move name in the cube frame) and commits only on release; Cube State is unchanged until then, and a cancelled drag changes nothing. Middle-row drag = slice (M/E/S); parallel two-finger drag starting on the cube = wide Move; drag starting off the cube = View Rotation. Below the confidence threshold nothing is shown or done; it never guesses.
- **View Rotation (FR-2, amended):** Notation stays in the cube frame. While the view is off home the stage says so and offers a one-tap Reset view.
- **Solution Playback (FR-16) and Preferences (FR-19):** 3D/2D switch at any step without losing place; optional per-Move picture never the only way a Move is given. Persisted prefs include playback view (unset until first switch) and *Move pictures* (default on); *Reset preferences* is separate from progress reset.
- **Responsiveness/rendering (NFR-1, NFR-2):** input acknowledged within 100 ms; ≥ 30 FPS on the minimum device and 60 FPS mid-range during turns, View Rotation and drag preview.
- **Accessibility (NFR-7):** content labels on every control, WCAG AA, dynamic type to 200% with reflow, a non-gesture route for every essential action (rotations and reset included), color-blind glyphs. All new learner copy ("Turn cancelled", "View rotated", "Reset view", "View reset") is an `en.json` content key read via `ContentStrings`; `strings.xml` gains no learner text (`checkLearnerText` gate).
- **Privacy, offline, stability, size:** no account, analytics, ads or crash SDK; no `INTERNET`, no runtime permission in v1.0; crash-free ≥ 99.5%; installed size < 30 MB.
- **Session integrity (12.7–12.13):** physical progress written only after explicit confirmation; closing practice or resetting progress ends the session; a closed session never returns after a failed write.

## Technical Decisions

- **AD-4 (commit before animate; renderer is a projection):** every input becomes `MoveIntent` → `MoveCommitted`; presentation is an `Animate`/`Snap` queue; `renderedFacelets()` always returns committed state. View Rotation is never a `SessionEvent`.
- **AD-4 gestures (amended):** pure `GestureInterpreter` in `:core:domain` takes one or two whole touch paths + view pose → `DragPreview?` while dragging, `MoveIntent?` on release. It locks axis and layer past the lock distance; once locked it returns `Active(move, degrees ∈ [−90°, +90°])` or `CancelZone`, never null. Two-finger parallel/twist/pinch classification is pure and JVM-tested. Touch handling lives in a gesture host separate from `Cube3DRenderer.kt`.
- **AD-4 drag preview:** the ghost turn is renderer-local like View Rotation: never a `SessionEvent`, never changes `renderedFacelets()`. Its highlight is a separate per-cubie flag OR-ed with `NetEmphasis` (no `uPulse` tint).
  - *Start:* if the queue is busy, `PresentationQueue.finishAll()` applies every outstanding item in order (none dropped, trace sections closed) and running reorientation/Demonstration jobs are cancelled, so preview starts from committed state.
  - *Commit handoff:* on release the ghost is held at its angle; when the matching `Animate(move)` arrives, `CubieModel.begin` adopts the held turn and continues over the remaining `1 − |degrees|/90` of a turn (no frame at 0°; mind the existing `check(turn == null)`). If a different item arrives first or no commit follows (input blocked), the ghost snaps to 0° first. Dispatch through `SessionHost` is async (AD-11).
  - *Cancel:* snap back, no event.
- **ViewOrbit:** gains an "at home" test (tolerance around `ViewOrbit.DEFAULT`) and an animated shortest-path reset (~200 ms, jump under reduce motion); a drag during the reset cancels it.
- **Face labels:** a pure `:core:domain` helper (pose + stage size → label anchors), drawn in 2D over the GL view (no shader change).
- **AD-20:** one pure `PlaybackController` drives Demonstrations, Solution Playback and the Library; notes only from `MoveNote.of(move)`.
- **AD-17 / AD-1:** one `StickerAppearance` table; every gesture has a `MoveButtonBar` equivalent; pure logic in `:core:*` with no Android imports, JVM-tested; `:app` only draws. Preferences have a single writer, `UpdatePreference`. No new port, module, entity or flag for 12.14–12.17.
- **Reference devices:** minimum Lenovo Tab M10 Plus 3rd Gen (API 33); mid Samsung Galaxy S24 FE. GL frame time is measured via the in-app `onDrawFrame` interval log, not Macrobenchmark `FrameTimingMetric`.

## UX & Interaction Patterns

- **Drag preview (UX-DR33):** once locked, the layer turns with the finger (max a quarter turn), all 9 cubies get the `sticker-highlight` outline added to any Lesson emphasis, and a ghost Move arrow (Move arrow overlay at 60% opacity) carries the Move in `notation` near the finger; any teaching arrow is hidden meanwhile. Reversing through the start flips the Move (R ↔ R'). Lift commits (also off the stage) with the light haptic tick; returning within the lock distance and lifting cancels with no tick. Reduce motion: layer stays still, only arrow and label show. In *Test me*/*Let me try* the preview names the Learner's Move, never the expected one. No ghost turn where Move input is blocked. UI tests read it via `PreviewedMoveKey`, separate from `RenderedArrowKey`.
- **View rotated badge (UX-DR34):** outlined `rounded.sm` tag in `label` with ⟲ icon, "View rotated · Reset view", 48dp target, in the stage's top-left overlay right after the frame label (first when there is none); overlay wraps as a flow row at 200%. Shown on all five 3D stages, never on the 2D net. The separate toolbar reset-view button is removed at every width; the ⋮ view menu with rotation arrows stays. Double-tap resets only if taps are close in time and distance (`doubleTapSlop`).
- **Face letters:** U/D/F/B/R/L in `notation`, `ink-secondary`, outside the cube's silhouette beside faces clearly facing the camera, clamped inside the stage, never on a sticker or glyph; hidden at home.
- **TalkBack:** previewed Move announced politely by plain name; "Turn cancelled" only for a cancelled locked drag; "View rotated"/"View reset" once. A two-finger drag reaches the app as one-finger under TalkBack, so wide Moves come from "more" on the Move key bar (expected).
- **Banned patterns:** shake or red flash on mistakes, countdowns, streaks, points, badges for achievement, push notifications, auto-advance past a Notice, carousels.

## Cross-Story Dependencies

- **Order:** 12.14 → 12.15 → 12.16 → 12.17 → 12.3 → 12.5 screenshots. 12.17 depends on 12.16 (the gesture host owns double-tap).
- 12.16 closes action item `epic-5-retro-item-19-…` (gesture host split); 12.17 closes `epic-5-retro-item-18-…` (double-tap distance) and must migrate the six screen-test files using `stage:resetView` to rotate first and use the badge's tag.
- 12.16/12.17 share one PO device check on the Lenovo and S24 FE after 12.17: Stage 1–2 Lessons completed by gesture with no unintended Move, ≥ 30 FPS during a drag.
- 12.3 absorbs epic-6-retro-item-1 (`PreferenceStore` read-error overwrite) and depends on Story 13.6; 12.5 on 13.4 and 13.5; 12.4 on the 7.8 pilot record.
