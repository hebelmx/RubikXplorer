---
title: 'See the turn while dragging, commit on release'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '407fd4f411ce177f32e91bbcfd30b195c6de333e'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md'
warnings: ['oversized']
deferred:
  - summary: >-
      No automated check proves the GL surface draws the preview outline (uPreview) without the pulse tint.
    evidence: |-
      Snapshot flags and semantics are tested (Cube3DRendererTest, FreeCubeScreenTest), but no test reads
      surface pixels; forcing uPreview to 0 would pass every JVM test. A pixel check needs an instrumented
      run on a device or emulator (androidTest is not part of ./gradlew build). The PO device session
      batched with Story 12.17 observes the outline directly.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/render/gl/CubeGlRenderer.kt:139
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Face-turn gestures misfire (GitHub #206): `GestureInterpreter.interpret` judges only the first and last touch points, nothing shows while dragging, a middle-row drag silently makes a slice, and wide Moves are button-only. The PO could not finish the first layer of the Course by gesture.

**Approach:** Story 12.16 (Refs: FR-1, FR-2 · NFR-1, NFR-2, NFR-4, NFR-7 · **AD-4, AD-11, AD-17** · AR-8 · UX-DR5, UX-DR6, UX-DR26, UX-DR27, UX-DR29, UX-DR33 · GitHub #206 · action item `epic-5-retro-item-19-split-cube3drenderer-kt-551-lines-gestur` (gesture host part)). A pure path-based, multi-pointer `GestureInterpreter.preview(GestureInput, ViewPose) → DragPreview?` locks axis + layer, a renderer-local ghost turn shows the layer turning with the finger, release commits through the unchanged `MoveIntent` → `MoveCommitted` path with a held-ghost handoff to the commit animation, and touch handling moves out of `Cube3DRenderer.kt` into its own gesture host.

## Boundaries & Constraints

**Always:**
- AD-4: every gesture commit is a `MoveIntent` through the same callback a Move key uses; the ghost turn is renderer-local, never a `SessionEvent`, and `renderedFacelets()` always returns committed state (never the ghost). Ghost highlight is a separate per-cubie flag OR-ed with `NetEmphasis`, outline only, never in the `uPulse` fill tint.
- AD-4 handoff: on release the renderer holds the ghost at its angle; when the matching `Animate(move)` starts, `CubieModel.begin` adopts the held turn and animates the remaining `1 − |degrees|/90` of the (speed-scaled, fast-forward-aware) turn duration — no frame at 0°. If a different item starts first, a `Snap` arrives, or no matching `Animate` arrives within `HOLD_TIMEOUT_MILLIS = 500`, the held ghost snaps to 0° (before that item starts).
- AD-4 start: when a drag starts on the cube with Move input wired, the stage's `onDragStart` callback runs first (Lesson: cancel the reorientation / net-demonstration job via the existing `stopReorientation`), then `PresentationQueue.finishAll()` (commit the active `Animate`, apply each waiting `Animate`/`Snap` without animating, in order, closing any open `Cube3D.turn` trace section), then the preview is built. No committed Move is dropped.
- AD-11: the handoff survives the asynchronous `SessionHost` dispatch; all gesture-host and renderer state mutation stays on the main thread; the GL thread still only draws published `GlSnapshot`s.
- AD-17: every gesture action keeps its Move key bar equivalent (wide/slice under "more"); the cube's existing semantics description is unchanged; new announcement copy is accessible.
- `GestureInterpreter`, `GestureInput`, `DragPreview` and the two-finger classifier are pure Kotlin in `:core:domain` (no Android/Compose imports) with JVM fixtures.
- Learner copy "Turn cancelled" is the content key `msg.gesture.turn-cancelled` in `core/domain/src/main/resources/content/strings/en.json`, resolved through `ContentStrings`; `strings.xml` gains no learner text. The Move plain name reuses `movePlainName` (`MovePlainName.kt`).
- The haptic tick (UX-DR27, `LocalHaptics`, switchable off) fires once per gesture commit (drag commit and z twist), never on cancel, never on lock.

**Never:**
- No new module, port, persisted entity, `SessionEvent`, feature flag, or Gradle gate change.
- No 12.17 work: no View rotated badge, no face labels, no removal of the toolbar reset-view button / `stage:resetView`, no double-tap distance check (double-tap moves into the host unchanged).
- Never commit a Move from an unlocked drag, from `CancelZone`, from a pinch, or from a cancelled pointer stream. Never preview the Tutor's expected Move (preview always names the Learner's Move).
- Never edit planning artifacts, `_bmad/`, or `.claude/skills/`.

## Settled UI behavior (from the story ACs, SCP 2026-11-18 §2/§5.4, DESIGN.md "Drag preview", EXPERIENCE.md "Cube gestures")

- **Ghost turn:** while `Active`, the locked layer is drawn rotated by `|degrees|` in the direction of the previewed Move (follows the finger every pointer event, no smoothing). Commit continues from that angle.
- **Cancel zone easing:** on entering `CancelZone` the ghost angle animates to 0° at the normal turn rate (`TURN_MILLIS` per 90°, scaled by the animation-speed preference), linear easing, i.e. the existing turn motion run backwards. On lift in `CancelZone` (or system cancel) any remaining angle snaps to 0° at once.
- **Highlight:** the 9 cubies of the locked layer (all cubies of both layers for a wide Move), centre included, get the `sticker-highlight` outline for the whole locked gesture (`Active` and `CancelZone`), removed on release/cancel.
- **Ghost arrow:** only while `Active`: the teaching arc of the previewed Move (`arrowPoints`) drawn complete (no draw-in animation) at 60% opacity with its arrowhead, and the Move in `notation` as a label placed beside the current finger position (`arrowLabelTopLeft(finger, …)`, clamped inside the stage). Hidden in `CancelZone` (no Move to name).
- **Teaching arrow:** hidden from lock until the gesture ends (release, cancel or system cancel), then shown again with its normal state; `RenderedArrowKey` is absent while hidden.
- **Reduce motion:** the layer does not turn (no ghost angle; committed Move then animates as today) and no layer highlight is drawn; only the ghost arrow and its label show (DESIGN.md: "only the arrow and label show").
- **Moves blocked:** when a stage wires no Move input, no preview, highlight, arrow or announcement appears; off-cube drags still rotate the view where they did before.
- **TalkBack:** a polite announcement of `movePlainName(move)` each time the preview becomes `Active` or its Move changes (not on every angle update); `Turn cancelled` announced once, only when a locked drag ends by lift in `CancelZone` (not on system cancel, not on never-locked drags).
- **Two-finger twist:** commits z on release as today, no ghost turn, no ghost arrow.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One-finger lock | drag from a sticker past `LOCK_DISTANCE` with dominant axis | `Active(move in cube frame, degrees)`; null before lock | — |
| Wobbly/curved row drag | path bends after lock | lock kept, same Move | — |
| Over-drag | travel past a quarter turn | degrees clamped to ±90 | — |
| Return to start | locked, finger back within `LOCK_DISTANCE` of start | `CancelZone` (never null) | — |
| Reversal | exits zone on the other side | Move inverted (R ↔ R'), degrees restart from 0 | — |
| Middle row/column | drag on a centre-row sticker | `Active(SliceTurn M/E/S)` | — |
| Two-finger parallel on cube | one finger on R layer, other on M | `Active(WideTurn(R, …))` (`r`) | both on middle, or on +1 and −1 → null, no Move |
| Two-finger twist on cube | pair rotates ≥ `TWIST_MIN_DEGREES` | no preview; z on release | — |
| Two-finger pinch on cube | separation changes, not parallel | null, no Move, no view rotation | — |
| Two-finger off cube | neither finger nor midpoint on cube | View Rotation (unchanged) | — |
| Late second finger | lands after one-finger lock | ignored; preview kept | — |
| Early lift in pair | first finger lifts after pair lock | preview frozen; resolves on last lift | — |
| Never locks | diagonal or short drag | no preview, no intent, no announcement | — |
| Lift off stage | `Active`, finger released outside the stage | commits | ray misses face plane → last valid point kept |
| System cancel | pointer consumed / gesture coroutine cancelled while locked | snap back, nothing commits, no tick | — |
| Busy queue | drag starts while `Animate` items queued / Lesson job running | job cancelled, `finishAll()`, then preview from committed state | — |
| Held ghost, no commit | release `Active` but no matching `Animate` within 500 ms, or another item first | ghost snaps to 0° first | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/input/GestureInterpreter.kt` -- today: `interpret(TouchPath, ViewPose)` (first/last points only, `MIN_DRAG = 0.35`, `DOMINANCE = 2`), `interpretTwist`, `startsOnCube`, private `pick(ray)` returning (cubie, hit point, normal) and the layer/turn mapping (layer 0 → `SliceTurn`). Reuse `pick`, the plane-intersection math and the move mapping; add `preview`, `release`, pair classifier.
- `core/domain/src/main/kotlin/dev/cubentia/domain/input/TouchPath.kt` -- `TouchPath(points)`; add `GestureInput` (1 or 2 `TouchPath`s) and `DragPreview` beside it (new files fine, same package).
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Move.kt:60` -- `WideTurn(face, turn)`; `Notation.format` gives `r`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/input/GestureInterpreterTest.kt` -- existing pose×sticker×direction sweep (`eyes`, `axes`, `path()` helper) to extend with path fixtures; keep the existing `interpret` cases passing via the new API or migrate them.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt:432-540` -- the `pointerInput` block to move into the gesture host; `:380`/`:394-431` teaching arrow + `RenderedArrowKey`; `publish()` `:268-292` builds `GlSnapshot` with `CubeMesh.emphasisFlags`; `snapNow`, `traceTurn`/`endTracedTurn` (close trace sections on `finishAll`); `arrowPoints`, `arrowLabelTopLeft` reused for the ghost arrow.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/PresentationQueue.kt` -- add `finishAll()`; `onFrame` starting an `Animate` must adopt a held ghost for the same Move (shorter duration) or clear it first; `Snap` clears it.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubieModel.kt:96` -- `begin` has `check(turn == null)` and sets `angle = 0`; add ghost set/clear and adopt-on-begin (start fraction). Layer selection for `WideTurn` is `d >= 0`.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubeMesh.kt` -- `emphasisFlags` (bit 1 dim, bit 2 highlight); add bit 4 preview from the ghost layer; `StickerDraw` gains `previewed`.
- `app/src/main/res/raw/cube_fragment.glsl` + `gl/CubeGlRenderer.kt:131-137` -- add `uPreview` uniform: outline when `uHighlight` or `uPreview`, tint only for `uHighlight`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt:88-134,200` -- `commit` ticks haptics and MoveKeyBar uses `tickOnCommit = false`; move the gesture tick into the host and let the key bar tick itself (one tick per commit either way); pass `reduceMotion = rememberReduceMotion()` and `ContentStrings` (new `strings` parameter from `AppShell.kt:843` and `SolveScreen.kt:95`, fallback `ContentStrings(emptyMap())` as `AppShell.kt:269` does).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:1284-1300` -- passes `onMoveIntent` even when physical with no key bar (intent dropped): pass null there instead, keep view gestures; wire `onDragStart` to `stopReorientation` (`:579`); pass `strings`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt:166`, `ui/solve/SolutionPlayback.kt:361` -- no Move input; must keep working (view rotation only).
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/MovePlainName.kt:24` -- `movePlainName(move)` (composable) for announcements.
- `app/src/test/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreenTest.kt:419-440` -- touch tests; the two-finger case at `:432` starts on the cube and expects a View Rotation, so it must move off-cube (or assert the new wide behavior).
- `app/src/test/kotlin/dev/cubentia/app/render/gl/PresentationQueueTest.kt`, `Cube3DRendererTest.kt`, `Cube3DEmphasisTest.kt` -- extend for `finishAll`, handoff, preview flag.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../input/GestureInput.kt`, `DragPreview.kt` (new) -- `data class GestureInput(val paths: List<TouchPath>)` (size 1 or 2, `require`); `sealed interface DragPreview { data class Active(val move: Move, val degrees: Double); data object CancelZone }`; `enum class PairKind { UNDECIDED, PARALLEL, TWIST, PINCH }`.
- `core/domain/.../input/GestureInterpreter.kt` -- add `preview(input, pose): DragPreview?`, `release(input, pose): MoveIntent?` (= `Active.move` or null), `classifyPair(a, b, pose): PairKind`; one-finger rule per Design Notes; two-finger: if neither finger's start nor the midpoint is on the cube → null (host handles View Rotation); TWIST → null preview (host commits `interpretTwist` on release); PARALLEL → lock from the first finger whose start is on a sticker, wide face from the outer-layer rule; remove or reimplement the old `interpret(TouchPath, pose)` on top of `preview` (no first/last-only code path left).
- `core/domain/src/test/.../GestureInterpreterTest.kt` (+ new `GestureClassifierTest.kt` if clearer) -- JVM fixtures: pose (the 4 `eyes` × portrait/landscape) × start sticker (all visible rows/cols incl. middle) × direction; null before lock; curved/wobbly paths keep lock; over-drag clamps to ±90; return → `CancelZone`; reversal flips Move and restarts at 0; cube-frame Move independent of pose; diagonal never locks; ray miss mid-path keeps last valid point; parallel/twist/pinch/undecided classifier fixtures; wide outer-layer rule incl. {0,0} and {+1,−1} → null; one finger off cube + one on R → `r`-family.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubieModel.kt` -- `setGhost(move: Move, degrees: Float)` / `clearGhost()` (renderer-local turn on the move's layer, `facelets()` unaffected); `begin(move)` adopts a ghost of an equal Move, returning the start fraction (`|degrees|/90`), else clears the ghost first; `ghostLayer` indices for the preview flag.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/PresentationQueue.kt` -- `finishAll()`; on starting an `Animate`, adopt matching held ghost (duration × remaining fraction, from progress = start fraction), else clear ghost; `Snap` clears ghost.
- `app/src/test/kotlin/dev/cubentia/app/render/gl/PresentationQueueTest.kt`, `CubieModelTest` (existing or new) -- `finishAll` applies every item in order and ends at the last target; handoff first frame angle equals held angle (never 0) and ends committed; mismatched item / Snap clears the ghost; `facelets()` unchanged while ghosted; drag-cancel leaves state unchanged.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubeMesh.kt`, `CubeGlRenderer.kt`, `res/raw/cube_fragment.glsl` -- preview flag bit 4 OR-ed with emphasis; `uPreview` outline-only; `StickerDraw.previewed`.
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` (new) -- the gesture host: owns the `pointerInput` (one/two-finger tracking with whole paths, late-finger ignore, freeze on first lift, double-tap reset as today, off-cube View Rotation as today, system-cancel in `finally`), calls `GestureInterpreter.preview` on every event, drives the renderer's preview API, commits via the stage callback + haptic tick, emits announcements.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- remove touch handling; add renderer-local preview API (`beginDrag()` = `finishAll` + publish + close trace; `showPreview(DragPreview?, finger)`; `holdForCommit()`; `cancelPreview(animated)`), cancel-zone ease, hold timeout, ghost arrow overlay, `PreviewedMoveKey` (`PreviewedMove(notation, degrees)`, present only while `Active`), hide teaching arrow while locked, polite announcement node (testTag `cube3d:announcement`, `liveRegion = Polite`); new `Content` params `onDragStart: (() -> Unit)? = null`, `viewGestures: Boolean = onMoveIntent != null || freeViewRotation`, `strings: ContentStrings? = null`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt`, `ui/shell/AppShell.kt`, `ui/solve/SolveScreen.kt` -- strings + reduce motion plumbing, single haptic tick per commit.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- null `onMoveIntent` when Moves are blocked (physical, no key bar) with `viewGestures = true`; `onDragStart = stopReorientation`; `strings`.
- `core/domain/src/main/resources/content/strings/en.json` -- `"msg.gesture.turn-cancelled": "Turn cancelled"`.
- `app/src/test/...` (Robolectric UI tests in `Cube3DRendererTest.kt` / new `CubeGestureHostTest.kt`, `FreeCubeScreenTest.kt`, `LessonScreenTest.kt`) -- host-level tests per Acceptance Criteria below; fix the existing two-finger test.

**Acceptance Criteria:**
- Given the free cube, when a one-finger drag on an R-column sticker locks and is held, then `PreviewedMoveKey` reports `R` or `R'` with non-zero degrees, `RenderedStickersKey` marks the 9 layer cubies' stickers `previewed`, `renderedFacelets()` equals the committed state, and the history is unchanged.
- Given that locked drag, when the finger lifts (inside or outside the stage), then exactly one `MoveIntent` commits, the history grows by that Move, one haptic tick fires (none with haptics off), and the queue animation starts from the held angle and ends at the committed state.
- Given a locked drag returned to its start, when lifted, then nothing commits, no tick fires, `PreviewedMoveKey` is absent, and the announcement node carries "Turn cancelled"; a system-cancelled pointer stream commits nothing and announces nothing.
- Given a Lesson with a teaching arrow, when a drag locks, then `RenderedArrowKey` is absent until the gesture ends, then returns; given a Lesson in Physical-cube Mode with no Move key bar, when the Learner drags on the cube, then no `PreviewedMoveKey` appears and nothing commits.
- Given a renderer with queued `Animate` items, when a drag starts on the cube, then before the first preview `renderedFacelets()` equals the last submitted target and no item was dropped.
- Given an on-cube parallel two-finger drag with one finger on the R layer and one on M, when it locks and lifts, then `r`/`r'` commits; given both on M, then nothing commits and no view rotation happens; given a two-finger twist on the cube, then z commits with no `PreviewedMoveKey`; given a two-finger drag starting off the cube, then the view rotates and nothing commits.
- Given TalkBack semantics, when the preview becomes `Active` or changes Move, then the announcement node's text is `movePlainName(move)` (e.g. "R prime: right face counter-clockwise").
- Given `./gradlew spotlessCheck build`, when run, then it passes (Kover ≥ 80% on `:core:*`, `checkLearnerText` green, no new `strings.xml` learner text).
- Given `Cube3DRenderer.kt`, when the story is done, then it contains no `pointerInput` / `awaitEachGesture` (touch handling lives in `CubeGestureHost.kt`).
- **Pending a PO device session (batched with Story 12.17, after the PR is open):** given the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, when the PO plays Stage 1 and Stage 2 Lessons by gesture, then they complete them without an unintended Move, cancel works, and frame time during a drag stays at the Story 12.2 baseline (≥ 30 FPS on the Lenovo, NFR-2). Implementation must make this pass (publish only on pointer events, no per-event mesh rebuild: the preview flag goes through `emphasisFlags`, not the vertex buffer); it is recorded here as pending, not excluded.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 29 findings — high 0, medium 8, low 16, false 5, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Sideways motion outside the radial cancel zone keeps an `Active` preview at 0° and lift commits it — real, but the cancel zone is the AC's own definition ("within the lock distance of where the drag began") and the ghost arrow and label still name the Move; changing it is a design change, not a direct fix.
  - `[false]` `[reject]` (blind) A twist can turn into a wide Move — `preview` returns null at the first TWIST sample before any PARALLEL lock and never reaches the wide branch; z at release still needs `interpretTwist` on the final points, as before.
  - `[medium]` `[patch]` (blind) Both fingers off the cube with the midpoint on it lost View Rotation — host now treats finger picks only as on-cube; midpoint-only pairs rotate the view and latch a twist (orbit held, z on release). Test added to `FreeCubeScreenTest` (straddling pair orbits).
  - `[false]` `[reject]` (blind) Lesson job stops before the drag locks — the AC requires the job cancelled "when a drag starts on the cube"; it now starts only past touch slop (jitter on a tap no longer stops it, fixed during verify).
  - `[low]` `[reject]` (blind) Final-pass consumption by a parent not observed — same check as the pre-change host; no stage has an intercepting parent in everyday use, fix adds a pass-specific branch.
  - `[false]` `[reject]` (blind) Cancel easing leaves the layer highlighted at 0° — spec "Settled UI behavior" keeps the highlight for the whole locked gesture, CancelZone included (still locked, never null).
  - `[low]` `[reject]` (blind) An earlier identical Animate can adopt the held ghost — needs a same-Move key commit to reach the queue after release but before the drag's own Animate; during the drag `finishAll` absorbs arrivals; unlikely, fix needs per-presentation identity.
  - `[low]` `[patch]` (blind) Leaving the stage with a held ghost kept a stale pose on the ViewModel-held free-cube renderer — `Content` disposal now clears the preview.
  - `[low]` `[patch]` (blind) Free cube without loaded content announced the raw key — the announcement now stays silent when `ContentStrings` returns the key (`TURN_CANCELLED_KEY`).
  - `[low]` `[reject]` (blind) Whole-path replay is quadratic — a few hundred cheap plane intersections per event at most; the pure whole-path API is what the AC asks for; frame time is part of the pending device check.
  - `[low]` `[reject]` (edge) Sideways return commits at 0° — same as the first blind row.
  - `[false]` `[reject]` (edge) Highlight stays after cancel easing — same as the blind highlight row (settled behavior).
  - `[low]` `[reject]` (edge) Path storage and classification grow with drag length — same as the blind quadratic row.
  - `[medium]` `[patch]` (edge) Midpoint-only pair loses View Rotation — same root and fix as the blind midpoint row.
  - `[low]` `[patch]` (edge) Raw key announced on the fallback route — same fix as the blind key row.
  - `[false]` `[reject]` (edge) A Snap during a preview re-applies the ghost on the new state — intended: the finger is still down, the preview stays live on the committed state it will commit to; `renderedFacelets()` is the Snap target.
  - `[medium]` `[patch]` (verification-gap) No end-to-end check that the first animation frame after a real drag continues from the held angle — added `FreeCubeScreenTest` "a released drag continues from the held angle…" (manual clock, no uncommitted frame equals the 0° baseline).
  - `[medium]` `[patch]` (verification-gap) No test that a drag stops a running Show me how — added `LessonScreenTest` "a drag started during Show me how stops the demonstration…".
  - `[medium]` `[defer]` (verification-gap) No pixel check of the `uPreview` outline — needs an instrumented device run; deferred, observed in the PO device session.
  - `[low]` `[patch]` (verification-gap, other) Raw key on the Solve-tab fallback — same fix as the blind key row.
  - `[medium]` `[patch]` (intent) Handoff verified only at queue level — same as the verification-gap first-frame row.
  - `[low]` `[reject]` (intent) No test of the 100 ms acknowledgement or NFR-2 — the commit callback runs synchronously in the release event; frame time is the pending device AC.
  - `[low]` `[reject]` (intent) Announcement checked as node text only; caller modifier moved to a wrapper Box — the live region is the standard Compose route to TalkBack; existing focus-order tests (5.5) still pass in `build`; raw-key edge patched above.
  - `[low]` `[reject]` (intent) Teaching arrow return tested only after cancel — the arrow is shown whenever `preview == null`, the same branch on commit and cancel.
  - `[medium]` `[patch]` (intent) Straddling pair reading (story's "at least one finger on the cube") — same fix as the blind midpoint row; the story reading is now implemented.
  - `[low]` `[patch]` (intent) Curved/over-drag fixtures at one pose only — the pose × sticker × direction sweep now also asserts a curved, over-dragged path per case (clamped ±90, same Move).
  - `[medium]` `[patch]` (intent) Lesson job cancellation untested — same as the verification-gap Show me how row.
  - `[low]` `[reject]` (intent) Renderer still owns announcement state and cancel easing — the AC moves touch handling out (done); announcement and easing are presentation of the renderer-local ghost.
  - `[low]` `[reject]` (intent) Let me try preview not tested — `preview` never reads Tutor state, so the named Move is the Learner's by construction (Test me drag asserts `U`).

### 2026-11-18 — Review pass (follow-up)
- verdicts: 36 findings — high 0, medium 6, low 21, false 9, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) A pair that locks PARALLEL and then spreads apart still commits the wide Move — Design Notes keep the lock "fixed at first PARALLEL lock"; a pinch that first travels a full lock distance in parallel is unlikely, and re-classifying after lock adds a branch.
  - `[low]` `[reject]` (blind) carried: sideways motion outside the radial cancel zone keeps an `Active` preview at 0° and lift commits it — the cancel zone is the AC's own definition; the ghost arrow and label still name the Move.
  - `[low]` `[patch]` (blind) A second finger bypassed touch slop, so two resting fingers with jitter on the Lesson cube stopped Show me how and finished the queue — `CubeGestureHost` now sets `moved` only when either finger travels past touch slop, and the drag-start side effects require `moved`.
  - `[low]` `[patch]` (blind) A straddling pair latched a twist and froze the camera even when the stage wires no Move input — `midpointOnly` now requires a Move callback, so view-only stages keep rotating the view.
  - `[low]` `[reject]` (blind) carried: final-pass consumption by a parent not observed — no stage has an intercepting parent in everyday use.
  - `[low]` `[reject]` (blind) carried: whole-path replay is quadratic — frame time is part of the pending device check.
  - `[low]` `[reject]` (blind) carried: an earlier identical Animate can adopt the held ghost — unlikely; fix needs per-presentation identity.
  - `[low]` `[reject]` (blind) The Solve-tab fallback Free Cube (content failed to load) has no "Turn cancelled" announcement — only when the content catalog fails to load; a second copy source outside `ContentStrings` would break the spec's single learner-text route.
  - `[medium]` `[patch]` (blind) The straddling-pair twist branch was untested — added `FreeCubeScreenTest` "a straddling pair that twists restores the camera and commits z on its last lift" (camera back at base orbit, no preview, nothing on first lift, one z and one tick on the last lift).
  - `[medium]` `[defer]` (blind) carried: no pixel check of the `uPreview` outline — already deferred; observed in the PO device session.
  - `[low]` `[patch]` (blind) `DebugToggles` read `LocalHaptics.current` into an unused local — removed.
  - `[low]` `[reject]` (edge) carried: locked drag displaced sideways commits from a 0° preview — same as the blind sideways row.
  - `[false]` `[reject]` (edge) carried: highlight stays after cancel easing — settled behavior keeps the highlight for the whole locked gesture.
  - `[low]` `[reject]` (edge) An `Animate` reaching the queue between drag start (past slop) and lock animates for one frame alongside the first preview — the next frame's `finishAll` (preview != null) completes it; needs a Move dispatched just before the drag to arrive in that window; fix adds drag-ownership state.
  - `[low]` `[reject]` (edge) carried: identical queued Move consumes the held angle — same as the blind identical row.
  - `[low]` `[reject]` (edge) carried: stored samples grow and work is quadratic — same as the blind quadratic row.
  - `[false]` `[reject]` (edge) Teaching arrow stays hidden while waiting in CancelZone — the settled behavior hides it "from lock until the gesture ends"; CancelZone is still the locked gesture.
  - `[false]` `[reject]` (edge) An off-cube pair whose midpoint is on the cube can commit z — the I/O matrix defines "off cube" as "neither finger nor midpoint on cube"; a midpoint-on-cube pair is an on-cube twist.
  - `[low]` `[reject]` (edge) Fallback route's empty `ContentStrings` silences "Turn cancelled" — same as the blind fallback row.
  - `[medium]` `[patch]` (verification-gap) Cancel-zone easing could disappear without failing a test — added `FreeCubeScreenTest` "returning to the start eases the layer back to rest at the turn rate and keeps it outlined" (manual clock: an intermediate model frame, at rest within one turn duration before lift, highlight kept, nothing commits).
  - `[medium]` `[patch]` (verification-gap) Straddling twist release untested — same test as the blind twist-test row.
  - `[medium]` `[defer]` (verification-gap) carried: preview outline not checked on the GL surface — already deferred.
  - `[low]` `[reject]` (intent) Device feel, real GL output and spoken TalkBack are not covered by JVM tests — this is the spec's pending PO device-session AC, recorded as a residual risk.
  - `[medium]` `[patch]` (intent) Cancel-zone easing tested only as "previewed stickers remain" — same test as the verification-gap easing row.
  - `[low]` `[reject]` (intent) Ghost arrow hidden in CancelZone and drawn complete are not asserted — the overlay is keyed on the same `Active` preview whose absence in CancelZone is asserted (`PreviewedMoveKey` null in the return-to-start test).
  - `[low]` `[reject]` (intent) Pinch on cube giving no view rotation, and on-cube twist showing no ghost, are not asserted at the host — on-cube pairs never call `dragViewFrom`, and release commits only `Active` or a TWIST; the on-cube twist test asserts the camera stays at default.
  - `[false]` `[reject]` (intent) A different item clears the ghost in the same frame it starts, not strictly before — `CubieModel.begin` clears the ghost before the new turn is built, so no published frame shows the ghost with the other item.
  - `[false]` `[reject]` (intent) `Cube3DRenderer.kt` grew instead of shrinking — the intent names only the "gesture host part" of the retro item; touch handling is out of the file.
  - `[false]` `[reject]` (intent) Off-cube twist behavior changed from "anywhere" — the matrix row defines the off-cube case as View Rotation; the change follows the intent.
  - `[false]` `[reject]` (intent) Either finger on the cube makes the pair on-cube (one off + one on R → `r`) — the spec's own test list asks for "one finger off cube + one on R → `r`-family".
  - `[low]` `[reject]` (intent) Fallback Free Cube stays silent on cancel — same as the blind fallback row.
  - `[low]` `[reject]` (intent) carried: Lesson traversal groups and caller modifier moved to a wrapper Box — description unchanged; existing focus-order tests pass.
  - `[low]` `[patch]` (intent) Unused `hapticsEnabled` in `DebugToggles` — same fix as the blind row.
  - `[false]` `[reject]` (intent) carried: `beginDrag`/`onDragStart` run past touch slop, before lock — the AC says "when a drag starts on the cube".
  - `[low]` `[reject]` (intent) `finishAll` runs each frame during a drag, beyond the start-of-drag rule — intended so the preview stays on committed state; no Move is dropped.
  - `[false]` `[reject]` (intent) The 500 ms Handler never fires because the frame loop nulls `heldUntil` when idle — the frame loop runs only while `queue.isBusy`; with no matching `Animate` the loop waits on `wake` and the Handler expires the hold (`heldGhostTimesOut…` covers it).

## Design Notes

**One-finger rule (pure, recomputed from the whole path on each call).** Pick the start sticker with `pick(ray)` (null → `null`). Project every path point onto the sticker's face plane (points whose ray misses the plane or is behind the eye reuse the previous valid projection). Let `d(p) = proj(p) − hit`. The lock point is the first point with `|d| ≥ LOCK_DISTANCE` (`= 0.35` cubie units, the old `MIN_DRAG`); if at that point `|d[major]| < DOMINANCE (2) × |d[minor]|`, the drag never locks (return null for this and every later call). Otherwise lock the tangent axis `major` and the layer (axis, layer index) exactly as `interpret` does today. For the current (last) point: if `|d| < LOCK_DISTANCE` → `CancelZone`; else `t = d[major]`, the Move is the old mapping for direction `sign(t)` (so crossing the start flips R ↔ R'), and `degrees = sign(t) × min(90, toDegrees(max(0, |t| − LOCK_DISTANCE) / RADIUS))` with `RADIUS = 1.5` (the face-plane distance from the turn axis), so the angle starts at 0 at the zone edge on either side.

**Two-finger rule.** `classifyPair` on screen displacements `a`, `b` of each finger since the pair formed, with `decide = 0.04 × min(width, height)` px: UNDECIDED while both `< decide`; TWIST if `interpretTwist(...) != null`; PARALLEL if both `≥ decide / 2`, angle between `a` and `b` ≤ 35°, and length ratio ≥ 0.5; PINCH otherwise. Wide Move: run the one-finger rule on the first on-sticker finger's own path; collect layer indices (along the locked rotation axis) of every finger whose start picks a sticker; `+1` present and `−1` absent → `WideTurn(+face)`, `−1` present and `+1` absent → `WideTurn(−face)`, turn direction = the `FaceTurn` the outer layer would get; otherwise null. Lock (axis/layer/wide face) is fixed at first PARALLEL lock and kept.

**Handoff example.** Release at `Active(R, 54°)` → renderer holds ghost R at 54° and sends `MoveIntent(R)`. `SessionHost` reduces and submits `Animate(R)`. `onFrame` starts it: `model.begin(R)` sees the equal ghost, `startFraction = 0.6`; queue sets `started` so progress begins at 0.6 and `duration × 0.4` remains. First published frame: 54°, never 0°.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- formats.
- `./gradlew :core:domain:test` -- GestureInterpreter/classifier fixtures pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (lint, JVM + Robolectric tests, Kover, `checkLearnerText`, boundary/manifest gates, debug and release APKs).

**Manual checks:**
- `grep -n "pointerInput\|awaitEachGesture" app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- no matches.
- PO device session (pending, batched with 12.17): Stage 1–2 Lessons by gesture on Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE.

## Auto Run Result

Status: done

**Summary.** Touch drags on the 3D cube now go through a pure, path-based `GestureInterpreter.preview(GestureInput, ViewPose) → DragPreview?` (`Active(move, degrees)` / `CancelZone`, lock at 0.35 cubie units, dominance 2, ±90° clamp, reversal flips the Move), a two-finger `classifyPair` (parallel / twist / pinch) and an outer-layer wide-Move rule. A new gesture host owns all touch handling; the renderer draws a renderer-local ghost turn with a preview outline flag OR-ed with Lesson emphasis, a 60% ghost arrow with the notation at the finger, hides the teaching arrow while locked, and hands the held ghost to the matching `Animate` so the commit continues from its angle. `PresentationQueue.finishAll()` drains the queue when a drag starts, and the Lesson's reorientation / demonstration job stops first. TalkBack hears the Move's plain name and "Turn cancelled" (`msg.gesture.turn-cancelled`).

**Files changed.**
- `core/domain/.../input/GestureInterpreter.kt` -- whole-path preview, release, pair classifier, wide rule.
- `core/domain/.../input/GestureInput.kt`, `DragPreview.kt` -- new input and preview types, `PairKind`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.gesture.turn-cancelled`.
- `app/.../render/CubeGestureHost.kt` -- new gesture host (one/two fingers, late finger, freeze, system cancel, double-tap, view rotation, haptic tick on commit).
- `app/.../render/Cube3DRenderer.kt` -- touch handling removed; preview API, ghost arrow, `PreviewedMoveKey`, cancel easing, 500 ms hold timeout, announcement node.
- `app/.../render/gl/CubieModel.kt`, `PresentationQueue.kt` -- ghost turn, adopt-on-begin handoff, `finishAll`.
- `app/.../render/gl/CubeMesh.kt`, `CubeGlRenderer.kt`, `res/raw/cube_fragment.glsl` -- preview flag bit 4, `uPreview` outline without pulse tint.
- `app/.../ui/freecube/FreeCubeScreen.kt`, `ui/shell/AppShell.kt`, `ui/solve/SolveScreen.kt` -- strings and reduce-motion plumbing, one tick per commit.
- `app/.../ui/lesson/LessonScreen.kt`, `DemonstrationPlayer.kt` -- no Move input when blocked, `onDragStart = stopReorientation`, traversal groups.
- Tests: `GestureInterpreterTest`, `PresentationQueueTest`, `Cube3DRendererTest`, `FreeCubeScreenTest`, `LessonScreenTest`.

**Review findings.** 29 findings: 12 patch rows (6 distinct fixes: midpoint-only pairs, dispose clears held ghost, silent fallback for missing copy, first-frame handoff test, Show me how interruption test, plus the curved/over-drag sweep), 1 deferred (GL outline pixel check, needs a device run), 12 rejected low and 5 false, each with its reason in the triage log. Also fixed during verify: a tap's sub-slop jitter no longer finishes the queue or stops a Lesson job.

**Follow-up review recommended: true.** Patched medium entries: 3 distinct (midpoint-only pair handling, handoff test, Show me how test); high: 0. Named unverified risk: the new midpoint-only branch in `CubeGestureHost.kt` (view rotation that latches into a z twist) was written after the review layers ran and is covered only by the straddling-pair orbit test, not by a straddling-twist test.

**Verification.** `./gradlew spotlessApply` ok; `./gradlew :core:domain:test --tests '*GestureInterpreterTest*'` passed; `./gradlew :app:testDebugUnitTest` for FreeCubeScreenTest, LessonScreenTest and Cube3DRendererTest passed (new tests present in the JUnit XML, no failures); `./gradlew spotlessCheck build` BUILD SUCCESSFUL (lint, JVM and Robolectric tests, Kover, `checkLearnerText`, gates, debug and release APKs); `grep pointerInput|awaitEachGesture Cube3DRenderer.kt` finds nothing.

**Residual risks.**
- Pending a PO device session (batched with Story 12.17, after the PR is open): Stage 1 and Stage 2 Lessons by gesture on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, with no unintended Move, working cancel, and drag frame time at the Story 12.2 baseline (≥ 30 FPS on the Lenovo, NFR-2). Lock distance, dominance and the pair thresholds may need tuning from that session.
- The GL preview outline is checked only through flags and semantics (deferred item).

### Follow-up review pass (2026-11-18)

**Summary.** A follow-up review (Codex blind, edge-case and verification-gap layers, Claude intent-alignment) of the whole story diff found 36 findings: high 0, medium 6, low 21, false 9. Patches: two-finger jitter below touch slop no longer stops a Lesson job or finishes the queue; a straddling pair on a stage with no Move input keeps rotating the view; unused `LocalHaptics` read removed from `DebugToggles`; new tests for the straddling-pair z twist and for cancel-zone easing.

**Files changed in this pass.**
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- either finger past touch slop marks the drag as moved; drag-start side effects require it; `midpointOnly` requires a Move callback.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt` -- unused `hapticsEnabled` removed from `DebugToggles`.
- `app/src/test/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreenTest.kt` -- straddling-twist and cancel-easing tests.

**Review findings.** Patched entries by verdict: medium 2 (straddling-twist test, cancel-easing test), low 3 (pair slop, view-only straddle, unused local). Deferred: none new (GL outline pixel check carried, already in `deferred`). Rejected: 21 low and 9 false, each with its reason in the follow-up triage log.

**Follow-up review recommended: false.** This was a follow-up pass and it patched no high finding.

**Verification.** `./gradlew spotlessApply` ok; `./gradlew :core:domain:test` passed; `./gradlew :app:testDebugUnitTest --tests '*FreeCubeScreenTest*'` passed twice in a row; `./gradlew spotlessCheck build` BUILD SUCCESSFUL; `grep pointerInput|awaitEachGesture Cube3DRenderer.kt` finds nothing.

**Residual risks.** Unchanged: the pending PO device session (batched with 12.17) and the GL outline pixel check. The cancel-easing test samples published model matrices per frame and needed `waitForIdle` between frames; one full-build run failed before that sync was added.
