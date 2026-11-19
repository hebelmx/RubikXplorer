---
title: 'Know when the view is rotated, and reset it in one tap'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'b6eed016182715f99ac744dbfa5ce359fa7ad7bc'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Notation stays in the cube frame (FR-2), so after a View Rotation `R` can look like `L'`, but nothing shows the view is off home, the only reset is an icon-only toolbar button the PO never found (GitHub #207), double-tap reset is undiscoverable and ignores tap distance, and no face letters are drawn.

**Approach:** Story 12.17 (Refs: FR-2 · NFR-7 · AD-4, AD-17 · UX-DR4, UX-DR5, UX-DR29, UX-DR34 · GitHub #207 · closes action item `epic-5-retro-item-18-double-tap-reset-also-checks-tap-distanc`). `ViewOrbit` gains a pure at-home test and shortest-path interpolation; the renderer animates the reset (~200 ms, jump under reduce motion, cancelled by a new press); a **View rotated · Reset view** badge replaces the toolbar reset button on all five 3D stages; a pure `:core:domain` face-label helper places U/D/F/B/R/L beside faces that clearly face the camera while rotated; double-tap also checks distance; TalkBack hears "View rotated" / "View reset" once.

## Boundaries & Constraints

**Always:**
- AD-4: View Rotation and its reset stay renderer-local — never a `SessionEvent`; Cube State, history, `renderedFacelets()`, Notation and Move key labels never change from a rotation, reset, badge or label.
- AD-17 / NFR-7: every rotation and its reset keep a non-gesture route — the ⋮ `stage:viewMenu` with `viewRotate:left|right|up|down` stays; the badge is the button route for reset (48dp target, content labels).
- Pure logic (`ViewOrbit` home test + interpolation, face-label placement, double-tap decision) lives in `:core:domain` with no Android/Compose imports and JVM tests; `:app` only draws.
- New Learner copy is content keys in `core/domain/src/main/resources/content/strings/en.json`, resolved through `ContentStrings`: `msg.view.rotated` = "View rotated", `btn.view.reset` = "Reset view", `msg.view.reset` = "View reset". `strings.xml` gains no Learner text (`checkLearnerText` green). When `ContentStrings` returns the raw key (no content loaded), the announcement stays silent and the badge still shows (12.16 `TURN_CANCELLED_KEY` pattern for the announcement).
- Badge look (DESIGN.md "Cube stage"): outlined `rounded.sm` tag, `label` text style, ⟲ icon (reuse `R.drawable.ic_reset_view`), text "View rotated · Reset view" (the two keys joined by ` · `), ≥ 48dp touch target, test tag `stage:viewRotated`; tapping it resets the view.
- Face letters: `notation` text style, `inkSecondary`, in the cube frame (world +x R, +y U, +z F, −x L, −y D, −z B), drawn in 2D over the GL view (no shader change), decorative (no TalkBack node), hidden at home (PO decision 6), never on the 2D net.

**Never:**
- No new module, port, persisted entity, `SessionEvent`, feature flag, or Gradle gate change; no persistence of the orbit.
- No separate reset-view button in any toolbar at any width; do not remove `stage:viewMenu` or double-tap reset.
- No badge, letters or view announcement on `Net2DRenderer`.
- Never edit planning artifacts, `_bmad/`, `.agents/skills/` or `.claude/skills/`.
- No change to 12.16 gesture semantics other than the double-tap distance check and the reset cancel on press.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Leave home | drag, menu arrow, or `rotateView` moves orbit beyond `HOME_TOLERANCE_DEGREES` | badge shows; letters show; "View rotated" announced once | — |
| Tiny drag | orbit moves < tolerance | still home: no badge, no letters, no announcement | — |
| Reset by badge / double-tap | rotated, motion on | orbit animates to `DEFAULT` by shortest yaw path in `RESET_MILLIS = 200`; ends exactly `DEFAULT`; badge and letters hide; "View reset" announced once | — |
| Reset, reduce motion | rotated, reduce motion on (any stage, free cube included) | orbit jumps to `DEFAULT` at once | — |
| Reset off-screen | renderer's `Content` not composed | jump to `DEFAULT` | — |
| Press during reset | pointer down on the stage mid-animation | animation stops at the current orbit; the gesture's pose/base orbit is read after the stop, so a drag continues from the current view | — |
| Yaw wrap | yaw near ±180 vs `DEFAULT` | interpolation takes the shorter way round; home test handles wrap | — |
| Far-apart double tap | two taps within `doubleTapTimeoutMillis` but downs farther apart than `doubleTapSlop` | no reset; second tap becomes the new first tap | — |
| Near double tap | within time and within slop | reset (animated) | — |
| Face edge-on | face facing angle ≤ `MIN_FACING_DEGREES` | no letter for that face | — |
| Face head-on | face centre projects onto the cube's projected centre | letter placed via fixed fallback direction | — |
| No room | label box cannot sit outside the silhouette and inside the stage after trying alternative directions | letter omitted (never overlaps) | — |
| 200% font | compact width | frame label + badge group wraps (flow row), badge fully visible, not clipped | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/input/ViewOrbit.kt` -- `data class ViewOrbit(yawDegrees, pitchDegrees)`, `rotated` (yaw wrapped to (−180,180], pitch clamped ±85), `dragged`, `DEFAULT` (≈ yaw 33.3°, pitch 30.9°). Add `isHome(tolerance = HOME_TOLERANCE_DEGREES)` and `towards(target, fraction)` (shortest signed yaw delta, linear pitch).
- `core/domain/src/main/kotlin/dev/cubentia/domain/input/ViewPose.kt` -- `ViewPose(width, height, eye, fov=38°)`, `project(Vector3) → ScreenPoint`; world frame +x R, +y U, +z F. Face-label helper projects with this.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeGeometry.kt` -- `BODY_HALF 0.47`, `STICKER_HALF 0.40`, `STICKER_LIFT 0.472`; cubie centres at −1/0/+1, so sticker quads are at ±1.472 with corners ±0.40 around each cubie centre; the silhouette is the convex hull of the 8 projected corners at ±(1 + `BODY_HALF`) (stickers at `1 + STICKER_LIFT` along the normal — use the larger extent).
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Face` (enum U,R,F,D,L,B) -- letters are `Face.name`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/input/ViewOrbitTest.kt` -- extend.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- `orbit` state `:110`, `rotateView`/`dragView`/`dragViewFrom`/`resetView` `:113-139` (reset assigns `DEFAULT` synchronously today); `Content(...)` `:323` has `reduceMotion`, `strings`, the `cube3d` node with `clearAndSetSemantics` + `RenderedOrbitKey` `:536-554`, overlay `Canvas` pattern `:557-609`, the 12.16 announcement node `cube3d:announcement` `:612-625` (only when `onMoveIntent != null`), `cancelFrom` + `LaunchedEffect` animation pattern `:417-429` to copy for the reset animation; `publishNow != null` means composed.
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- `baseOrbit = renderer.orbit` read at down `:52` and on late second finger `:78`; double-tap block `:174-184` (time only, `previousTap: Long?`). Add press-cancels-reset before reading the orbit, and the distance check.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/ViewControls.kt:22` -- the `stage:resetView` `ToolbarButton` to delete; the `stage:viewMenu` dropdown stays.
- Stage headers (the "top-left overlay" = the group holding the frame label):
  - `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt:175-206` -- `Row{frame label (weight 1f, tag stage:frame), undo, redo, reset, ViewControls}`; `Content` already gets `reduceMotion = rememberReduceMotion()` and `strings` (12.16).
  - `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:1228-1279` -- `FlowRow{Row{FrameLabel, CertaintyChip?}, Row(weight 1f, traversalIndex 3){caption, toolbar, ViewControls}}`; cube `traversalIndex 1f` `:1288`. `FrameLabel` composable `:1526`.
  - `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt:145-178` -- `DemonstrationStage`: `Row{FrameLabel? , Spacer, Row(traversalIndex 3){ViewControls}}`, `Content` without `strings`. Used by the Lesson *Show me* Demonstration (no frame label) and `ui/library/AlgorithmDetailScreen.kt:198,206` (frame label, `freeViewRotation = true`).
  - `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt:341-386` -- header `Row{Spacer, viewSwitch, ViewControls(cube)}`; stage `Box` holds `Content` (no `strings`) and, when physical, `FrameLabel` overlaid at the box's top-left.
- `app/src/main/res/values/strings.xml:133` `reset_view` and `app/learner-text-baseline.txt` -- `reset_view` becomes unused once the button goes; remove both if nothing else references it (lint `UnusedResources`).
- Tests using `stage:resetView` to migrate: `app/src/test/.../ui/freecube/FreeCubeScreenTest.kt:521-555`, `ui/lesson/LessonScreenTest.kt:917,927,1264`, `ui/solve/SolutionPlaybackTest.kt:138`, `ui/lesson/DemonstrationPlayerTest.kt:322`, `ui/library/AlgorithmDetailScreenTest.kt:261`, `ui/a11y/ScreenAudit.kt:336` (`assertButtonRoutes`). Traversal helpers: `LessonScreenTest.kt:2279-2420` (`assertLearnerTraversal`, `assertStageTraversal`). `render/ViewRotationInvarianceTest.kt:39` calls `resetView()` (off-screen jump keeps it valid).
- Pixel-assertion "screenshot" pattern: `app/src/test/kotlin/dev/cubentia/app/ui/playback/MovePictureScreenshotTest.kt` (Robolectric `GraphicsMode.NATIVE`, `captureToImage().toPixelMap()`, ink counts). `SolutionPlaybackTest.kt:741` notes the GL `SurfaceView` breaks `captureToImage` (multi-window), so screenshots capture the overlay composables, not the GL surface.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/ReduceMotion.kt:15` -- `rememberReduceMotion()` / `LocalReduceMotionOverride` (tests force reduce motion through the override).

## Tasks & Acceptance

**Execution:**
- `core/domain/.../input/ViewOrbit.kt` -- add `HOME_TOLERANCE_DEGREES = 1.0`, `fun isHome(tolerance: Double = HOME_TOLERANCE_DEGREES): Boolean` (wrapped yaw delta and pitch delta both ≤ tolerance vs `DEFAULT`), `fun towards(target: ViewOrbit, fraction: Double): ViewOrbit` (shortest signed yaw delta in (−180,180], result yaw re-wrapped, pitch linear; fraction 1 returns `target` exactly).
- `core/domain/.../input/FaceLabels.kt` (new) -- pure placement helper per Design Notes: `object FaceLabels { const val MIN_FACING_DEGREES = 20.0; fun place(pose: ViewPose, labelWidth: Double, labelHeight: Double, gap: Double): List<FaceLabel> }`, `data class FaceLabel(val face: Face, val left: Double, val top: Double)` (box top-left in stage px); also expose `silhouette(pose): List<ScreenPoint>` and `stickerQuads(pose): List<List<ScreenPoint>>` (visible stickers' projected quads) for tests.
- `core/domain/.../input/DoubleTap.kt` (new) -- `fun isDoubleTap(firstDownMillis: Long, firstDown: ScreenPoint, secondDownMillis: Long, secondDown: ScreenPoint, timeoutMillis: Long, slopPx: Double): Boolean` (time ≤ timeout **and** distance ≤ slop).
- `core/domain/src/test/.../input/ViewOrbitTest.kt`, `FaceLabelsTest.kt` (new), `DoubleTapTest.kt` (new) -- home/tolerance/wrap/shortest-path/end-exact fixtures; face-label sweep (yaw every 15°, pitch −80…80 step 20, stage sizes incl. the smallest compact stage 96dp×96dp at density 1–3.5 and portrait/landscape/large, label box from a ~1.25-char notation glyph) asserting each box is inside the stage, intersects no sticker quad and no silhouette edge, only faces with facing > `MIN_FACING_DEGREES` get letters, no two boxes overlap, plus non-vacuous assertions (a rotated orbit on a ≥ 600px square stage yields letters for every face facing by more than the minimum angle that has room — at least two letters at yaw 30°/pitch 25°); double-tap near/far/late fixtures.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- `val isViewRotated get() = !orbit.isHome()`; reset animation per Design Notes (`resetView()` animated/jump, `cancelViewReset()`; `rotateView`/`dragView`/`dragViewFrom` also cancel a running reset); store the latest `reduceMotion` from `Content` (`SideEffect`); face-label `Canvas` overlay inside the `cube3d` box (above the arrow overlay, below the gesture box) drawn only while rotated, using `FaceLabels.place` with the measured `notation` box; `RenderedFaceLabelsKey` (list of `Face`) on the `cube3d` node, absent at home; an always-present polite live-region node `cube3d:viewAnnouncement` whose text becomes "View rotated" / "View reset" only on home-state transitions after first composition (empty initially; silent if the key is unresolved). Put the label overlay in its own internal composable (e.g. `FaceLabelOverlay(orbit, modifier)`) so the screenshot test can compose it without the GL surface.
- `app/src/main/kotlin/dev/cubentia/app/render/ViewRotatedBadge.kt` (new) -- `@Composable fun ViewRotatedBadge(renderer: Cube3DRenderer, strings: ContentStrings, modifier)` rendering nothing at home; otherwise the badge (tag `stage:viewRotated`, `Role.Button`, `onClickLabel` = "Reset view", merged text "View rotated · Reset view", min 48dp), click → `renderer.resetView()`. Also an orbit-taking overload/inner composable for the screenshot test.
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- on `awaitFirstDown` call `renderer.cancelViewReset()` before reading `renderer.orbit`; replace the time-only double-tap with `isDoubleTap` using the first tap's down time/position and `android.view.ViewConfiguration.get(context).scaledDoubleTapSlop` (read `LocalContext` in the composable; verify the API).
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/ViewControls.kt` -- delete the reset `ToolbarButton`; keep the ⋮ menu.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt` -- header becomes a `FlowRow`: a top-left `FlowRow{frame label, ViewRotatedBadge}` then the toolbar group; keep `stage:frame` live-region behavior.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- the inner frame-label `Row` becomes a `FlowRow{FrameLabel, CertaintyChip?, ViewRotatedBadge}` (badge after the label group, before the toolbar's traversal group).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` -- `DemonstrationStage` header: `FlowRow(weight 1f){FrameLabel?, ViewRotatedBadge}` then the `ViewControls` group; pass `strings` to `Content`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- stage box top-left overlay `FlowRow{FrameLabel? (physical), ViewRotatedBadge (3D only)}` with traversal before the cube (`isTraversalGroup` on the box, negative `traversalIndex` on the overlay); pass `strings` to `Content`.
- `core/domain/src/main/resources/content/strings/en.json` -- the three keys; `app/src/main/res/values/strings.xml` + `app/learner-text-baseline.txt` -- drop `reset_view` if unused.
- Tests (`app/src/test/...`): migrate the six files per the AC; new host/stage tests in `FreeCubeScreenTest` (animated reset with manual clock, reduce-motion jump via `LocalReduceMotionOverride`, press-during-reset continues from current view, far-apart taps don't reset, near double-tap resets, badge shows/hides, letters key present/absent, announcements, history/key labels unchanged, 200% font badge not clipped); badge presence + traversal on Lesson (extend `assertLearnerTraversal`/`assertStageTraversal` or add a rotated-state traversal test), Demonstration (Show me: badge first), Library detail (after frame label), Solution Playback 3D (badge, first on the stage) and 2D net (no badge); new `app/src/test/.../render/ViewRotatedScreenshotTest.kt`.

**Acceptance Criteria:**
- Given any of the five 3D stages at home, when it shows, then `stage:viewRotated` does not exist, `RenderedFaceLabelsKey` is absent, and no toolbar node `stage:resetView` exists at compact or expanded width while `stage:viewMenu` → `viewRotate:*` still rotate the view.
- Given a stage rotated through `stage:viewMenu` → `viewRotate:left` (or a drag), when it recomposes, then the badge shows right after the frame label (Free cube, Lesson, Library detail, physical Solution Playback) or first (virtual Solution Playback, Lesson *Show me*), its touch target is ≥ 48dp, it has the "View rotated" text and a "Reset view" click label, TalkBack traversal reaches it right after the frame label or first on the stage, letters are reported, and `cube3d:viewAnnouncement` carries "View rotated"; the Solution Playback 2D net never shows the badge.
- Given a rotated free cube with motion on, when the badge is tapped (or the stage double-tapped within time and slop), then with a manual clock the orbit is strictly between the rotated orbit and `DEFAULT` mid-way, equals `DEFAULT` by ~200 ms (+ a frame), the badge and letters are gone, the announcement is "View reset", and history, Cube State and Move key labels are unchanged; with reduce motion on the orbit is `DEFAULT` immediately.
- Given a reset animation in progress, when a pointer goes down and drags off the cube, then the reset stops and the view follows the drag from the orbit at the press (not `DEFAULT`, not the pre-reset orbit), and it stays off home after the clock passes 200 ms.
- Given two taps within `doubleTapTimeoutMillis` but farther apart than `doubleTapSlop`, when performed on a rotated stage, then the view does not reset (closes `epic-5-retro-item-18-…`).
- Given the six migrated test files, when they run, then they rotate first and use `stage:viewRotated`; `ScreenAudit.assertButtonRoutes` asserts no badge at home, then after `viewRotate:left` the badge's click action and 48dp target, and that clicking it hides it.
- Given 200% font scale on a compact width, when the free cube and Lesson stages are rotated, then the badge's bounds lie fully inside the stage header width (it wraps rather than clips).
- Given `ViewRotatedScreenshotTest` (Robolectric NATIVE graphics, the overlay composables on `surfaceStage`, qualifiers compact `w360dp` and expanded `w840dp`, light and dark), when it runs, then the home capture has no badge or letter ink and the rotated capture has badge ink in the top-left and `inkSecondary` letter ink at each `FaceLabels.place` box.
- Given `./gradlew spotlessCheck build`, when run, then it passes (Kover ≥ 80% on `:core:*`, `checkLearnerText` green, lint green).
- **Deferred to the PO device session (batched with 12.16, before merge, orchestrator-owned):** badge, letters and reset feel on the Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 21 findings — high 0, medium 1, low 12, false 8, maybe-false 0
- findings:
  - `[low]` `[patch]` Missing platform accessibility traversal tests on four of the five 3D stages (`FreeCubeScreenTest`, `DemonstrationPlayerTest`, `AlgorithmDetailScreenTest`, `SolutionPlaybackTest`) — added platform `AccessibilityNodeProvider` (`EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL`) traversal assertions in all four test files verifying `stage:frame → stage:viewRotated → cube3d` (or `stage:viewRotated → cube3d` when no frame label is shown).
  - `[low]` `[patch]` `FreeCubeScreen.kt` omits explicit `isTraversalGroup` and `traversalIndex` semantics on its nested `FlowRow`s — added `isTraversalGroup = true` on `CubeStage` and explicit `isTraversalGroup = true` + `traversalIndex` (`0f` on the top-left `FlowRow`, `1f` on `renderer.Content`, `3f` on the toolbar `Row`).
  - `[low]` `[reject]` `SolutionPlayback.kt` overlays `ViewRotatedBadge` inside the 3D stage `Box` above the `FaceLabelOverlay` canvas without an exclusion rect in `FaceLabels.place` — in `SolutionPlayback`, the cube fits inside the stage box centered with perspective margin while face labels sit tight against the projected silhouette (`gap = 6dp`); passing dynamic Compose overlay rects into pure `:core:domain` `FaceLabels` would add cross-layer layout plumbing not warranted by everyday use.
  - `[medium]` `[patch]` `FaceLabels.place` uses `pose.eye.normalized()` instead of `(pose.eye - centre).normalized()` for the facing angle check — updated `FaceLabels.place` and `FaceLabelsTest` to compute `toEye = pose.eye - centre` so facing angles match the perspective line-of-sight to each face center `c = n * 1.5`.
  - `[low]` `[patch]` `Cube3DRenderer.Content` computes `FaceLabels.place` twice per frame while rotated — added optional `labels: List<FaceLabel>? = null` to `FaceLabelOverlay` and passed `faceLabels` from `Cube3DRenderer.Content` so placement runs once per composition.
  - `[false]` `[reject]` `Cube3DRenderer.kt` leaves `cube3d:viewAnnouncement` permanently populated with `"View reset"` text and an active polite live region after reset — matches the established `cube3d:announcement` live-region pattern in `Cube3DRenderer.kt:696-701` and is required for synchronous semantics assertion of `"View reset"` in Compose UI tests; Android polite live regions only announce on text changes.
  - `[low]` `[patch]` `Cube3DRenderer.kt` `wasRotated` and `viewNoticeKey` state does not reset if `Cube3DRenderer` instance changes without an outer `key(renderer)` — keyed `wasRotated` and `viewNoticeKey` on `this` (`remember(this)`).
  - `[low]` `[patch]` `DemonstrationStage` header in `DemonstrationPlayer.kt` uses a non-wrapping outer `Row` instead of `FlowRow` — changed the outer header in `DemonstrationStage` to `FlowRow` and marked `AlgorithmDetailScreen`'s stage `Column` as `isTraversalGroup = true`.
  - `[false]` `[reject]` `ViewRotatedBadge` inner pill can clip horizontally on narrow stages at 200% font scale — verified by `200 percent font on compact width wraps badge inside stage header` (`FreeCubeScreenTest`) and `rotatedBadgeWrapsInsideCompactLessonStageAt200PercentFont` (`LessonScreenTest`) at `w360dp` with `fontScale = 2.0f`, where the badge wraps cleanly on its own flow line inside the stage width.
  - `[false]` `[reject]` `Spec 12.17` Code Map note about `DemonstrationPlayer.kt` and Lesson *Show me* frame label — the fix requested would edit this build's spec, and `DemonstrationStage` already handles both `showFrameLabel = true` and `showFrameLabel = false`.
  - `[low]` `[patch]` `Cube3DRenderer.kt:468-480` leaving composition while reset animation is running skips reset completion — wrapped `animate(...)` in `LaunchedEffect(resetRun)` in `try { ... } finally { if (resetFrom === run) { orbit = ViewOrbit.DEFAULT; resetFrom = null } }`.
  - `[low]` `[patch]` `Cube3DRenderer.kt:595-608` `BoxWithConstraints` could receive unbounded width or height (`Constraints.Infinity`) when rotated — guarded `FaceLabels.place` call with `constraints.hasBoundedWidth && constraints.hasBoundedHeight`.
  - `[low]` `[patch]` `CubeGestureHost.kt:80-93` late second pointer going down while an externally triggered view reset animation is running reads `renderer.orbit` without cancelling reset — added `renderer.cancelViewReset()` before `baseOrbit = renderer.orbit` on late second-pointer down.
  - `[low]` `[patch]` `FaceLabels.kt:40-48` `pose.eye` zero-length or closer than face centre along face normal — computed `val toEye = pose.eye - centre; val len = toEye.length(); val facingSin = if (len > 0.0) normal.dot(toEye * (1.0 / len)) else 0.0`.
  - `[low]` `[patch]` `FaceLabels.kt:60-96` first step outside hull overlapping a placed label or clamped hull edge breaks the ray walk early — updated the ray walk to only break when `!overlapsHull && !overlapsPlaced` or when both axes are clamped (`rawLeft != clampedLeft && rawTop != clampedTop`).
  - `[low]` `[patch]` `ViewOrbit.kt:39-49` `towards` called with `fraction = Double.NaN` bypasses `t >= 1.0` and `t <= 0.0` guards — added `if (fraction.isNaN()) return this` at the start of `towards` and covered it in `ViewOrbitTest`.
  - `[low]` `[patch]` Intent-alignment divergence 1: `FaceLabels.kt` used `pose.eye.normalized()` instead of `(pose.eye - centre).normalized()` — patched with the perspective face-center ray above.
  - `[low]` `[reject]` Intent-alignment divergence 2: `FaceLabels.place` does not take an exclusion rect for the overlaid top-left badge in `SolutionPlayback` — rejected for the same reason as the duplicate Blind Hunter finding above (negligible everyday overlap risk vs cross-layer layout coupling).
  - `[false]` `[reject]` Intent-alignment divergence 3: `ViewRotatedScreenshotTest` composes `FaceLabelOverlay` and `ViewRotatedBadge` on `surfaceStage` rather than a GL `SurfaceView` — Robolectric `captureToImage()` cannot capture a multi-window GL `SurfaceView`, and the spec explicitly directs `ViewRotatedScreenshotTest` to compose the overlay composables on `surfaceStage` while the five stage test suites verify stage integration.
  - `[false]` `[reject]` Intent-alignment divergence 4: 200% font wrapping tests check `FreeCubeScreen` and `LessonScreen` horizontal bounds — matches the acceptance criteria (`Given 200% font scale on a compact width, when the free cube and Lesson stages are rotated, then the badge's bounds lie fully inside the stage header width`).
  - `[false]` `[reject]` Intent-alignment divergence 5: diff includes `_bmad-output/implementation-artifacts/spec-12-17-...` — `_bmad-output/implementation-artifacts/` is the implementation artifact directory, not `_bmad-output/planning-artifacts/`.

## Design Notes

**Reset animation (renderer, main thread).** `resetView()`: if `orbit.isHome()` set `orbit = DEFAULT` and return; if not composed (`publishNow == null`) or the latest `reduceMotion` is true, cancel and jump; else set `resetFrom = ResetRun(orbit)` (a fresh object each call). In `Content`: `LaunchedEffect(resetFrom) { run ?: return; animate(0f, 1f, tween(RESET_MILLIS, easing = FastOutSlowInEasing)) { v, _ -> if (resetFrom === run) orbit = run.from.towards(DEFAULT, v.toDouble()) }; if (resetFrom === run) { orbit = DEFAULT; resetFrom = null } }`. `cancelViewReset()` sets `resetFrom = null`, leaving `orbit` where it is.

**Why the press cancels the reset.** The gesture host fixes the pose at pointer down (it picks the sticker and builds every preview from that pose), so a reset still moving the camera would make the first drag sample jump and could mis-pick a layer. Cancelling on down and reading `renderer.orbit` afterwards gives "starts from the current view" for both view drags and Move drags. A plain tap during the 200 ms leaves the view part-way, still rotated, with the badge visible.

**Face-label placement (pure).** For each face with outward normal `n`: centre `c = n × 1.5`; facing = angle between `n` and `(eye − c)`; skip when its sine is ≤ `sin(MIN_FACING_DEGREES)`. `o = project(origin)`, `p = project(c)`; direction `d = normalize(p − o)`, or the fixed fallback `(0, 1)` (below) when `|p − o| < 1px`. Walk the box centre from `p` along `d` in small steps until the box (inflated by `gap`) no longer intersects the silhouette polygon; clamp the box inside the stage; if the clamped box intersects the silhouette or an already placed box, retry with `d` rotated ±15°, ±30°, … ±90°; if none fits, omit the face. Sort faces by facing (most facing first) so the clearest faces win room. Example: yaw 30°, pitch 25° on a 1000×1000 stage → F letter below-left of the F face, R right of the R face, U above the U face, each outside the hull.

**Top-left group order.** In the Lesson the badge follows the frame label *and* its certainty chip: the chip qualifies the label (DESIGN.md frame label), so splitting them would misread; TalkBack still reaches the badge right after the frame label group and before the toolbar.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- formats.
- `./gradlew :core:domain:test` -- ViewOrbit, FaceLabels, DoubleTap fixtures pass.
- `./gradlew :app:testDebugUnitTest --tests '*FreeCubeScreenTest*' --tests '*LessonScreenTest*' --tests '*SolutionPlaybackTest*' --tests '*DemonstrationPlayerTest*' --tests '*AlgorithmDetailScreenTest*' --tests '*ViewRotatedScreenshotTest*'` -- pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

**Manual checks:**
- `grep -rn "stage:resetView" app/src` -- no matches.

## Auto Run Result

Status: done

### Summary of Implemented Change
Implemented Story 12.17 ("Know when the view is rotated, and reset it in one tap"):
- Added `ViewOrbit.HOME_TOLERANCE_DEGREES = 1.0`, `ViewOrbit.isHome(tolerance)`, and `ViewOrbit.towards(target, fraction)` (shortest signed yaw path, linear pitch, exact target at `fraction = 1.0`, `NaN`-safe).
- Added pure 2D face-label placement (`FaceLabels.place`, `FaceLabels.silhouette`, `FaceLabels.stickerQuads`) in `:core:domain` using perspective face-center facing (`MIN_FACING_DEGREES = 20.0`) and Separating Axis Theorem collision checks against the projected cube silhouette and placed labels.
- Added pure `isDoubleTap` checking both `timeoutMillis` and `slopPx` distance (closing `epic-5-retro-item-18-double-tap-reset-also-checks-tap-distanc`).
- Updated `Cube3DRenderer` with `isViewRotated`, 200 ms `FastOutSlowInEasing` animated `resetView()` (with immediate jump when off-screen or under `reduceMotion`), `cancelViewReset()` (called on pointer down, late second-pointer down, and `rotateView`/`dragView`/`dragViewFrom`), `FaceLabelOverlay` (`notation` + `inkSecondary`), `RenderedFaceLabelsKey`, and polite live-region `cube3d:viewAnnouncement`.
- Added `ViewRotatedBadge` (`stage:viewRotated`, `Role.Button`, `onClickLabel` = `"Reset view"`, text `"View rotated · Reset view"`, 48dp touch target) in a wrapping `FlowRow` across all five 3D stages (Free Cube, Lesson, Demonstration, Algorithm Detail, and 3D Solution Playback), removed the toolbar `stage:resetView` button from `ViewControls`, added the three content string keys to `en.json`, and dropped unused `reset_view` from `strings.xml` and `app/learner-text-baseline.txt`.
- Migrated all six existing screen test files to `stage:viewRotated`, added platform TalkBack traversal assertions across all five 3D stages, and added `ViewRotatedScreenshotTest`.

### Files Changed
- `core/domain/src/main/kotlin/dev/cubentia/domain/input/ViewOrbit.kt` -- added `HOME_TOLERANCE_DEGREES`, `isHome`, and `towards`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/input/FaceLabels.kt` -- new pure 2D face-letter placement helper (`place`, `silhouette`, `stickerQuads`).
- `core/domain/src/main/kotlin/dev/cubentia/domain/input/DoubleTap.kt` -- new pure `isDoubleTap` time-and-distance classifier.
- `core/domain/src/main/resources/content/strings/en.json` -- added `msg.view.rotated`, `btn.view.reset`, and `msg.view.reset`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/input/ViewOrbitTest.kt` -- added unit tests for `isHome` and `towards`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/input/FaceLabelsTest.kt` -- new unit and sweep tests for `FaceLabels`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/input/DoubleTapTest.kt` -- new unit tests for `isDoubleTap`.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- added `isViewRotated`, animated `resetView()`, `cancelViewReset()`, `FaceLabelOverlay`, `RenderedFaceLabelsKey`, and `cube3d:viewAnnouncement`.
- `app/src/main/kotlin/dev/cubentia/app/render/ViewRotatedBadge.kt` -- new **View rotated · Reset view** badge composable (`stage:viewRotated`).
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- cancels view reset on pointer down and uses `isDoubleTap` with `scaledDoubleTapSlop`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/ViewControls.kt` -- removed `stage:resetView` toolbar button while keeping `stage:viewMenu`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt` -- placed `ViewRotatedBadge` in a wrapping `FlowRow` after `stage:frame` with traversal ordering.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- placed `ViewRotatedBadge` in a wrapping `FlowRow` after `FrameLabel` and `CertaintyChip`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` -- placed `ViewRotatedBadge` in a wrapping `FlowRow` in `DemonstrationStage` and passed `strings` to `Content`.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- marked the stage column as a traversal group for deterministic TalkBack ordering.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- placed `ViewRotatedBadge` in the 3D stage top-left overlay `FlowRow` (never on `Net2DRenderer`) and passed `strings` to `Content`.
- `app/src/main/res/values/strings.xml` -- removed unused `reset_view` string resource.
- `app/learner-text-baseline.txt` -- removed `reset_view` baseline entry.
- `app/src/test/kotlin/dev/cubentia/app/render/ViewRotatedScreenshotTest.kt` -- new Robolectric NATIVE pixel-assertion test for home and rotated overlays across compact/expanded and light/dark themes.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt` -- migrated `assertButtonRoutes` from `stage:resetView` to `stage:viewRotated`.
- `app/src/test/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreenTest.kt` -- migrated to `stage:viewRotated` and added animated reset, reduce-motion jump, mid-reset press/drag cancel, far-apart double-tap, 200% font scale, and platform TalkBack traversal tests.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt` -- migrated to `stage:viewRotated` and added rotated-state TalkBack traversal and 200% font scale tests.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayerTest.kt` -- migrated to `stage:viewRotated` and added platform TalkBack traversal assertion.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreenTest.kt` -- migrated to `stage:viewRotated` and added platform TalkBack traversal assertion.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- migrated to `stage:viewRotated`, tested 2D net exclusion, and added virtual/physical platform TalkBack traversal assertions.

### Review Findings Breakdown
- **Patches applied (11 entries across 13 findings — high: 0, medium: 1, low: 10):**
  - Perspective face-center ray `(pose.eye - centre)` in `FaceLabels.place` and `FaceLabelsTest` (`medium`).
  - Ray-walk obstacle continuation in `FaceLabels.place` (`low`).
  - `Double.NaN` guard in `ViewOrbit.towards` (`low`).
  - Single `FaceLabels.place` computation per frame passed into `FaceLabelOverlay` (`low`).
  - `constraints.hasBoundedWidth && constraints.hasBoundedHeight` guard in `Cube3DRenderer.Content` (`low`).
  - `try / finally` reset completion on `LaunchedEffect(resetRun)` cancellation in `Cube3DRenderer.Content` (`low`).
  - `remember(this)` key on `wasRotated` and `viewNoticeKey` in `Cube3DRenderer.Content` (`low`).
  - `renderer.cancelViewReset()` before `baseOrbit = renderer.orbit` on late second-finger down in `CubeGestureHost.kt` (`low`).
  - Explicit `isTraversalGroup` and `traversalIndex` semantics in `FreeCubeScreen.kt` and `AlgorithmDetailScreen.kt` (`low`).
  - Wrapping outer `FlowRow` in `DemonstrationStage` (`DemonstrationPlayer.kt`) (`low`).
  - Platform TalkBack traversal assertions (`AccessibilityNodeProvider`) in `FreeCubeScreenTest`, `DemonstrationPlayerTest`, `AlgorithmDetailScreenTest`, and `SolutionPlaybackTest` (`low`).
- **Deferred (0 items):** None.
- **Rejected (8 findings):**
  - `SolutionPlayback` top-left badge exclusion rect in `FaceLabels.place` (2 duplicate findings, `low`): negligible everyday overlap risk vs cross-layer layout coupling into pure `:core:domain`.
  - `cube3d:viewAnnouncement` retaining `"View reset"` text (`false`): follows `cube3d:announcement` pattern and enables synchronous Compose semantics tests; Android polite live regions only announce on text changes.
  - `ViewRotatedBadge` inner pill horizontal clipping at 200% font scale (`false`): disproved by `w360dp` `fontScale = 2.0f` tests on `FreeCubeScreen` and `LessonScreen`.
  - `Spec 12.17` Code Map note about `DemonstrationPlayer` (`false`): edits this build's spec; code supports both `showFrameLabel = true` and `false`.
  - `ViewRotatedScreenshotTest` composing overlay composables on `surfaceStage` (`false`): required by Robolectric multi-window `SurfaceView` constraint and explicit spec design.
  - 200% font tests checking `FreeCubeScreen` and `LessonScreen` horizontal bounds (`false`): matches the acceptance criterion.
  - Diff containing `_bmad-output/implementation-artifacts/spec-12-17-...` (`false`): implementation artifact path, not planning artifacts.

### Follow-Up Review Recommendation
- `followup_review_recommended: false` (patched counts by entry verdict: `high: 0`, `medium: 1`, `low: 10`).

### Verification Performed
- `./gradlew spotlessApply` -- passed.
- `./gradlew :core:domain:test` -- passed (`ViewOrbitTest`, `FaceLabelsTest`, `DoubleTapTest`).
- `./gradlew :app:testDebugUnitTest --tests '*FreeCubeScreenTest*' --tests '*LessonScreenTest*' --tests '*SolutionPlaybackTest*' --tests '*DemonstrationPlayerTest*' --tests '*AlgorithmDetailScreenTest*' --tests '*ViewRotatedScreenshotTest*'` -- passed.
- `./gradlew spotlessCheck build` -- `BUILD SUCCESSFUL` (Kover ≥ 80% on `:core:*`, `checkLearnerText`, `checkModuleBoundaries`, Android Lint, and debug/release/benchmark builds).
- `grep -rn "stage:resetView" app/src` -- zero matches.

### Residual Risks
- Physical-device verification of the badge, face letters, and 200 ms reset feel on the Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE is deferred to the batched PO device session before merge (orchestrator-owned).
