---
title: 'Story 5.3: Rotate the view and the cube'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: 'bf3c778a729fa44ad1328007fce5cbe6f45dab04'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The 3D free cube (Stories 5.1–5.2) is seen from one fixed camera; the Learner cannot look at the back or bottom, a two-finger twist does nothing, a Cube Rotation gives no sign of the new front, and there is no view reset or non-gesture view control (FR-1, FR-2, FR-7, AD-4, UX-DR4/5/26/29).

**Approach:** Add a pure orbit camera (`ViewOrbit`, yaw/pitch around the origin) and a pure two-finger twist recognizer in `:core:domain`; keep the orbit as renderer-local state in `Cube3DRenderer` that drives both picking and the GL camera; host off-cube/two-finger drags (orbit), two-finger twist (z/z' Cube Rotation through the shared free-cube commit), double-tap reset, and toolbar reset-view + overflow arrow buttons on the free cube stage; show the new front color on the frame label after a Cube Rotation commit.

## Boundaries & Constraints

**Always:**
- View Rotation is local to `Cube3DRenderer` (`orbit` state + `rotateView`/`dragView`/`resetView`); it never touches `FreeCubeViewModel.history`, never submits a `PresentationItem`, never produces a `SessionEvent`.
- One pose source: picking (`GestureInterpreter`) and `CubeGlRenderer`'s view-projection both use `ViewPose(width, height, orbit.eye())`. Notation and Move keys stay in the cube frame (world +x R, +y U, +z F), so key labels and the Move a key/gesture commits never depend on the camera.
- `ViewOrbit(yawDegrees, pitchDegrees)`: eye = radius × (cos p · sin y, sin p, cos p · cos y), radius = `ViewPose.DEFAULT_EYE.length()`; `DEFAULT` reproduces `DEFAULT_EYE` (within 1e-9); pitch clamped to ±`PITCH_LIMIT` = 85°; yaw wrapped to (−180, 180]. `dragged(dx, dy, sidePx)`: `DRAG_DEGREES` = 180° per narrower-side length; the cube follows the finger (drag right → yaw decreases, so F's projected center moves right; drag down → pitch increases). `STEP_DEGREES` = 45 for the arrow buttons, each equal to a drag in that direction (left → yaw +45, right → −45, up → pitch −45, down → +45).
- Gesture host (on `cube3d`): a single-pointer gesture whose start ray hits the box [−1.5, 1.5]³ (new `GestureInterpreter.startsOnCube(point, pose)`) keeps 5.2 layer-turn behavior; a single-pointer gesture starting off the box orbits live with the finger and commits nothing. Once a second pointer goes down the gesture is two-finger: no layer turn; the camera follows the two pointers' centroid while both are down; if the start centroid is on the cube and the twist angle between the pointer pair reaches `TWIST_MIN_DEGREES` = 30°, the gesture locks as a twist (orbit restored to its value at gesture start, no further following) and on lift commits `CubeRotation(Z, …)` per `GestureInterpreter.interpretTwist(startA, startB, endA, endB, pose)` (null when |angle| < 30° at lift or start centroid off the cube). Screen-clockwise twist → `z` when `eye.z ≥ 0`, else `z'`. Pointers beyond the first two are ignored.
- Double-tap (two single-pointer taps, each moving < touch slop, second down within `viewConfiguration.doubleTapTimeoutMillis`) anywhere on the stage resets the view; taps commit nothing.
- Camera changes snap (no animation), which also satisfies reduce motion.
- A twist commit and the ⟳ x/y/z keys use the existing shared free-cube `commit` function (haptic tick if `hapticsEnabled`, then `viewModel.onMoveIntent` → `MoveHistory.commit`, product-owner-accepted equivalent of `MoveCommitted`); the existing `PresentationQueue` animates it in `TURN_MILLIS` = 200 ms.
- After any commit whose Move is a `CubeRotation`, the frame label (tag `stage:frame`) reads `VIRTUAL · {color} front` (color = `history.state.orientation.colorAt(Face.F)` via `colorName`) for `FRONT_NOTICE_MILLIS` = 1500 ms, as a polite live region, then returns to `VIRTUAL`. A newer rotation restarts the timer; undo/redo/reset show no notice.
- Stage toolbar right end: reset-view `IconButton` (tag `stage:resetView`, label "Reset view", new `ic_reset_view` vector) and overflow `IconButton` (tag `stage:viewMenu`, label "View options", new `ic_more_vert` vector) opening a `DropdownMenu` with items tagged `viewRotate:left|right|up|down`, labels "Rotate view left/right/up/down". All ≥ 48dp, strings in `strings.xml`.

**Never:**
- No change to `CubeRenderer`, `PresentationItem`, `RenderStyle`, `MoveHistory`, `Move`/`Orientation`, `build-logic/`, `_bmad/`, `.claude/skills/`, `_bmad-output/planning-artifacts/`, spike source sets, or lesson screens (lessons stay on `Net2DRenderer`).
- No x/y gestures, arrows, highlights, dimming or TalkBack summary changes (5.4/5.5); no Settings persistence of view or haptics.
- No new dependency (Kotest property stays on `:core:domain` only), no logging of Cube States/facelets, no new `SessionEvent`, no new state owner for the free cube.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Default | `ViewOrbit.DEFAULT.eye()` | equals `DEFAULT_EYE` | — |
| Drag right | `dragged(+side/4, 0, side)` | yaw −45; F center projects further right | — |
| Pitch clamp | repeated drag down / up | pitch stays within ±85 | — |
| Yaw wrap | 9 × left step | yaw = 45 (wrapped) | — |
| Twist CW, front | default pose, pair rotated +40° clockwise on screen, centroid on cube | `CubeRotation(Z, CLOCKWISE)` | — |
| Twist CCW | same, −40° | `CubeRotation(Z, COUNTERCLOCKWISE)` | — |
| Twist from back | eye.z < 0, +40° clockwise | `CubeRotation(Z, COUNTERCLOCKWISE)` | — |
| Small twist | |angle| < 30° | `null` | no commit |
| Twist off cube | start centroid misses box | `null` | no commit |
| Start test | point at projected F center / at viewport corner | `startsOnCube` true / false | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewPose.kt` -- `ViewPose(width, height, eye = DEFAULT_EYE, fov)`; already handles vertical eyes (worldUp fallback); `ray`, `project`, `viewProjection`. `Vector3` lives here.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/GestureInterpreter.kt` -- `interpret(path, pose)`; private `pick(ray)` / `boxEntry(ray, center, half)`; add `startsOnCube` and `interpretTwist` here, plus `TWIST_MIN_DEGREES`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- `CubeRotation(Axis.Z, Turn)`; `Orientation.colorAt(Face.F)` in `cube/Orientation.kt`; `CubeState.orientation`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/` -- `GestureInterpreterTest`, `ViewPoseTest` patterns; Kotest property is on this classpath (`core/domain/build.gradle.kts:8`).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- `Content(style, modifier, onMoveIntent)`; pointer overlay `awaitEachGesture` (one pointer, endpoints, `ViewPose(size.width, size.height)` — must use the orbit eye); `publish()` → `renderer.snapshot` + `view.requestRender()`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` -- `onSurfaceChanged` computes `viewProj` once from `ViewPose(width, height)`; add `@Volatile var eye` and recompute when eye or size changes in `onDrawFrame`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/PresentationQueue.kt` -- `TURN_MILLIS = 200` already animates `CubeRotation` (`CubieModel.begin` handles it); nothing to change.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt` -- shared `commit` lambda; `CubeStage` toolbar (`frame_virtual` text, `ToolbarButton` helper); add notice state, reset-view, overflow menu, `onResetView`/rotate wiring to `viewModel.renderer`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/MoveKeyBar.kt` -- ⟳ x/y/z keys (`MoveKeys.rotation`) already commit via `onMove`; unchanged.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/StickerReviewNet.kt:200` -- `colorName(Color)` → `R.string.color_*`.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- Robolectric harness: `launch`, recording haptics, `gesturePoints` (builds `ViewPose(bounds…)` — use the renderer's current orbit), `assertRenderedMatchesState`, `mainClock`. The 5.2 multi-pointer case (start, start+5px) must still commit nothing.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewOrbit.kt` -- new pure orbit per Always rules (`DEFAULT`, `eye()`, `rotated`, `dragged`, `PITCH_LIMIT`, `DRAG_DEGREES`, `STEP_DEGREES`).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/GestureInterpreter.kt` -- `startsOnCube`, `interpretTwist`, `TWIST_MIN_DEGREES`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/ViewOrbitTest.kt` + `GestureInterpreterTest.kt` -- every I/O matrix row; Kotest property: any sequence of `rotated`/`dragged` keeps |eye| = radius and pitch within limits.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` -- camera from current eye.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- `orbit` state, `rotateView`/`dragView`/`resetView`; gesture host per Always (off-cube orbit, two-finger orbit/twist, double-tap reset, picking with current pose); push eye to GL and request a render on change.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/drawable/ic_reset_view.xml`, `ic_more_vert.xml` -- frame notice, reset-view button, overflow arrow menu.
- `app/src/test/kotlin/dev/rubikxplorer/app/render/ViewRotationInvarianceTest.kt` -- seeded randomized property (≥ 200 sequences, plain JUnit): random committed Moves on a `FreeCubeViewModel`, then random `rotateView`/`dragView`/`resetView` sequences on its renderer → `history` and `renderer.renderedFacelets()` unchanged.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- ACs below; existing tests stay green.
- `docs/ARCHITECTURE.md` -- one line: View Rotation is renderer-local `ViewOrbit`; a twist is a z Cube Rotation through the free-cube commit.

**Acceptance Criteria:**
- Given the free cube, when a one-finger drag starts off the cube or a two-finger drag (no twist) moves, then `renderer.orbit` changes during the drag, and after lift `history` is unchanged, no haptic fires, and `renderedFacelets()` equals the committed state.
- Given a rotated view, when the Move keys render and `moveKey:R` is tapped, then the key tag/label set equals the default-view set and `history.state` is `SOLVED.apply(R)`; a drag on the projected (current-pose) F top-row sticker toward −x still commits `U`.
- Given `moveKey:x` (or `y`) tapped or a two-finger clockwise twist on the cube from the default view, when it commits, then `history.state` equals `SOLVED.apply(x)` (resp. `z`) with one haptic tick, `renderedFacelets()` matches within 250 ms on the frame clock, `stage:frame` reads `VIRTUAL · Yellow front` after `x` (`Red` after `y`, `Green` after `z`), and reads `VIRTUAL` again after 1500 ms.
- Given a rotated view, when the Learner double-taps the stage or taps `stage:resetView`, then `orbit == ViewOrbit.DEFAULT`; when `stage:viewMenu` → `viewRotate:left` is used, then orbit yaw increases by 45 and history is unchanged.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes (Kover ≥ 80 % on `:core:domain`, no new dependency).

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 22 findings — high 0, medium 3, low 8, false 11, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) long presses count as taps for double-tap reset — the second down must arrive within `doubleTapTimeoutMillis` (300 ms) of the first down, so the first press is shorter than any long press.
  - `[low]` `[reject]` (blind) sub-slop jitter on an off-cube tap moves the camera — at most about 1.5° (slop px × 180° / side), invisible; gating on slop would add a visible jump at slop and a branch.
  - `[false]` `[reject]` (blind) consumed secondary pointer can still twist — no other pointer handler sits on or above the `cube3d` overlay (stage Column and screen root are not scrollable), so nothing can consume these changes; same reasoning as Story 5.2's triage.
  - `[low]` `[patch]` (blind) twist judged against the pre-drag pose when the second finger joins after an off-cube drag — `Cube3DRenderer.kt` now builds `pairPose` from the orbit at pair formation, uses it for both `interpretTwist` calls, and restores that orbit on lock (equal to gesture start for a pure two-finger gesture).
  - `[low]` `[reject]` (blind) the front notice can wrap beside five 48dp toolbar buttons — at 411dp it wraps to two 12sp lines inside the 48dp row; only very large font scales grow the row briefly; a responsive toolbar is more than a direct correction.
  - `[low]` `[reject]` (blind) `ViewRotationInvarianceTest` never drains the queue, so its facelet assertion is self-comparison — the AC's property (state unchanged) is the `history` assertion, which is real; rendered equality after orbit changes is asserted by `FreeCubeScreenTest` via `assertRenderedMatchesState`.
  - `[medium]` `[patch]` (blind) camera tests never check that GL receives the new eye — grouped with verification-gap #1; added internal `Cube3DRenderer.glEye` and assertions after `viewRotate:left` and reset that the GL renderer's eye equals `orbit.eye()`.
  - `[low]` `[patch]` (blind) twist host cases missing (retreat below threshold, off-cube centroid, rear orbit) — grouped with verification-gap #2 for the retreat case (new test); off-cube centroid and rear orbit are covered in `GestureInterpreterTest` and the host passes `pairPose` straight through.
  - `[false]` `[reject]` (edge) consumed tracked pointer moves the camera — same as blind #3; no competing handler.
  - `[low]` `[patch]` (edge) second finger after off-cube drag uses the stale pose — same root cause as blind #4; patched there.
  - `[false]` `[reject]` (edge) NaN/overflow into `ViewOrbit` — callers pass finite pointer deltas, a positive side, or ±45° constants; no reachable non-finite input.
  - `[medium]` `[patch]` (verification-gap) orbit not verified at the GL boundary — see blind #7; renderer-boundary eye assertion added (the `onDrawFrame` matrix rebuild itself needs a GL context; `ViewPoseTest` covers the matrix math).
  - `[medium]` `[patch]` (verification-gap) twist lock after retreat unverified — new `FreeCubeScreenTest` case crosses 40°, retreats to 20° while translating 40px, and asserts the orbit stays at default, no commit, no tick.
  - `[false]` `[reject]` (intent) property test drives renderer methods, not gestures — the gesture host's only history-changing call is `latestCommit` on a recognized layer turn or twist; every orbit path calls the same `orbit` setters the property exercises; gesture examples are in `FreeCubeScreenTest`.
  - `[false]` `[reject]` (intent) "camera rotates" checked only on `orbit` — now also checked at the GL renderer's eye (see blind #7).
  - `[false]` `[reject]` (intent) only face-key labels compared — labels come from `Notation.format(move)` over the constant `MoveKeys` lists; no key code reads the orbit.
  - `[false]` `[reject]` (intent) Notation after a Cube Rotation unchecked — out of this story's ACs; `Orientation` semantics are Epic 1 code, unchanged.
  - `[false]` `[reject]` (intent) 200 ms not measured — `PresentationQueue.TURN_MILLIS = 200` is unchanged and drives every `Animate`, rotations included; the 250 ms settle tests bound it.
  - `[false]` `[reject]` (intent) twist with haptics off untested — the twist goes through the same `commit` lambda whose `hapticsEnabled` check is covered by the existing haptics-switch tests.
  - `[low]` `[reject]` (intent) overflow entries are text menu items, not arrow buttons — each is a labelled ≥48dp control that rotates the view without gestures; adding arrow glyphs is cosmetic.
  - `[low]` `[patch]` (intent) only `viewRotate:left` tested — test now exercises right, up and down too.
  - `[false]` `[reject]` (intent) double-tap only inside `cube3d`, and extra scope (1500 ms, z notice, lock, 45°, 85°) — `cube3d` fills the stage well below the toolbar, whose buttons would conflict with double-tap; the extra choices are spec decisions inside the intent, not divergences.

### 2026-11-04 — Review pass (follow-up)
- verdicts: 30 findings — high 0, medium 3, low 17, false 10, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) double-tap has no spatial tolerance, so two quick taps far apart reset the view — matches the contract ("anywhere on the stage"); resetting the view is harmless and two rapid unrelated taps are rare; a distance guard adds a branch.
  - `[low]` `[reject]` (blind) a second tap held for a long time still counts on release — rare and harmless (view reset only, no commit); a duration guard adds a branch.
  - `[low]` `[reject]` (blind) nearly coincident pointers can jitter past 30° and twist — real touch panels report two fingers well apart (finger contact width), so a few-pixel span is not met in everyday use; a minimum-span guard adds a constant and a branch. Grouped with edge #1.
  - `[low]` `[reject]` (blind) sub-slop off-cube jitter moves the camera — carried: at most about 1.5°, invisible; gating on slop adds a visible jump and a branch.
  - `[low]` `[reject]` (blind) toolbar notice can wrap beside five 48dp buttons — carried: only at very large font scales; a responsive toolbar is more than a direct correction.
  - `[low]` `[reject]` (blind) `ViewRotationInvarianceTest` never drains the queue — carried: the property is the `history` assertion; rendered equality after orbit changes is in `FreeCubeScreenTest`.
  - `[medium]` `[patch]` (blind) `glEye` check verifies assignment only, not the matrix used to draw — grouped with verification-gap #1 and intent #8; moved the view-projection rebuild into internal `gl/ViewProjectionCache` (used by `CubeGlRenderer.onSurfaceChanged`/`onDrawFrame`), and new `ViewProjectionCacheTest` asserts the drawn matrix equals `ViewPose(w, h, eye).viewProjection()` after an eye change and after a resize.
  - `[low]` `[reject]` (blind) z/z' chosen by the sign of `eye.z` flips near side-on views — the rule is fixed inside the intent contract, and x/y twist alternatives are excluded (5.4/5.5); changing it is not this story's call.
  - `[low]` `[reject]` (edge) nearly coincident pointers commit an unintended twist — same root cause as blind #3; rejected there.
  - `[medium]` `[patch]` (verification-gap) GL camera update not verified at the drawing boundary — see blind #7; patched there.
  - `[low]` `[patch]` (verification-gap) two-finger drag test does not check centroid direction or scale — `FreeCubeScreenTest` now asserts the exact orbit: `DEFAULT.dragged(40, 18, side)` for the off-cube drag and `DEFAULT.dragged(0, 30, side)` for the two-finger centroid drag (`side` = the `cube3d` node's narrower side).
  - `[low]` `[patch]` (intent) twist reference is the orbit at pair formation, not gesture start — carried: the earlier pass's deliberate `pairPose` patch; equal to gesture start for a pure two-finger gesture.
  - `[false]` `[reject]` (intent) double-tap timed from the first down rather than the first up — the contract says "second down within `doubleTapTimeoutMillis`", which the code implements.
  - `[false]` `[reject]` (intent) double-tap only inside `cube3d` — carried: `cube3d` fills the stage below the toolbar, whose buttons would conflict.
  - `[false]` `[reject]` (intent) undo/redo/reset also clear a notice that is showing — "show no notice" holds either way; no bad outcome.
  - `[low]` `[reject]` (intent) overflow entries are text items, not arrow glyphs — carried: labelled ≥48dp controls; glyphs are cosmetic.
  - `[low]` `[patch]` (intent) frame label is a live region permanently, so the return to `VIRTUAL` is announced — `FreeCubeScreen.kt` sets `liveRegion = Polite` only while the front notice shows; the front-notice test asserts Polite during the notice and none after.
  - `[false]` `[reject]` (intent) property test drives the renderer API, not gestures — carried: every gesture orbit path calls the same `orbit` setters; gesture examples are in `FreeCubeScreenTest`.
  - `[medium]` `[patch]` (intent) "one pose source" checked only at `CubeGlRenderer.eye` — see blind #7; patched there.
  - `[false]` `[reject]` (intent) GL surface size vs. Compose overlay size — the `AndroidView` and the gesture `Box` both fill the same parent box, so their sizes match; unchanged since 5.1.
  - `[low]` `[patch]` (intent) twist from back / off cube only tested in the domain — carried: earlier pass's host-coverage row (retreat test added; `pairPose` passed straight through).
  - `[low]` `[patch]` (intent) small twist at lift only tested in the domain — carried: same host-coverage row as above.
  - `[false]` `[reject]` (intent) pitch clamp tested with one call, not repeated drags — repeated drags are covered by the Kotest property's ±85 bound.
  - `[false]` `[reject]` (intent) drag right tested from `ViewOrbit(0, 0)`, not `DEFAULT` — yaw change is linear and independent of the start orbit; same formula.
  - `[false]` `[reject]` (intent) only face-key labels compared — carried: labels come from `Notation.format` over constant `MoveKeys`.
  - `[false]` `[reject]` (intent) third pointer moving or arriving later untested — the handler reads only the changes whose ids are `down.id` or `secondId`, so a third pointer cannot affect the gesture.
  - `[low]` `[reject]` (intent) double-tap timeout boundary, slop and off-stage taps untested — test-only gap on a harmless view reset; not worth extra tests.
  - `[low]` `[reject]` (intent) a single off-cube tap leaves a sub-slop orbit change — carried: same as blind #4.
  - `[low]` `[patch]` (intent) 48dp asserted only for `stage:resetView` width — test now also asserts height for `stage:resetView` and width and height for `stage:viewMenu` and `viewRotate:left`.
  - `[false]` `[reject]` (intent) 200 ms not measured — carried: `PresentationQueue.TURN_MILLIS = 200` unchanged; 250 ms settle tests bound it.

## Design Notes

Twist angle: θ = atan2 of (endB − endA) minus atan2 of (startB − startA) in y-down screen pixels, normalized to (−180°, 180°]; θ > 0 is screen-clockwise. From the F side, screen-clockwise is F-clockwise, i.e. `z`; from the back it is `z'`.

Locking the twist and restoring the orbit avoids a twist that also nudges the camera; a two-finger drag that never reaches 30° of twist is a pure View Rotation.

## Verification

**Commands:**
- `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` -- expected: pass.
- `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*'` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: pass.

**Manual checks (if no CLI):**
- `git diff --stat <baseline>` shows nothing under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `build-logic/`, `app/src/spike3d*`.

## Auto Run Result

Status: done

**Summary:** A pure `ViewOrbit` (yaw/pitch around the origin) in `:core:domain` drives one `ViewPose` for both picking and the GL camera, held as renderer-local state in `Cube3DRenderer` (`orbit`, `rotateView`, `dragView`, `resetView`). A one-finger drag off the cube or a two-finger drag orbits the camera live with no commit; a two-finger twist of ≥ 30° on the cube locks, restores the camera and commits `z`/`z'` through the shared free-cube commit (tick + `onMoveIntent` → `MoveHistory.commit`), animated in 200 ms like the ⟳ x/y keys. After any Cube Rotation commit the frame label reads `VIRTUAL · {color} front` for 1.5 s. Double-tap, a reset-view toolbar button and a "View options" overflow menu with four rotate-view items give gesture-free view control.

**Files changed:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewOrbit.kt` — new orbit camera (default, eye, rotate/drag, clamp, wrap, step).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/GestureInterpreter.kt` — `startsOnCube`, `interpretTwist`, `TWIST_MIN_DEGREES`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` — orbit state and gesture host (off-cube orbit, two-finger orbit/twist lock, double-tap reset, current-pose picking); `glEye`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` — view-projection rebuilt from a volatile `eye`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt` — front-color frame notice, reset-view button, view overflow menu.
- `app/src/main/res/values/strings.xml`, `app/src/main/res/drawable/ic_reset_view.xml`, `ic_more_vert.xml` — labels and icons.
- Tests: `domain/input/ViewOrbitTest` (incl. Kotest property), `GestureInterpreterTest` (start/twist matrix), `app/render/ViewRotationInvarianceTest` (200 seeded sequences), 6 new `FreeCubeScreenTest` cases.
- `docs/ARCHITECTURE.md` — one line on `ViewOrbit` and the twist path.

**Review:** 22 findings (high 0, medium 3, low 8, false 11). Patched 4 entries: GL-eye boundary check (medium; blind + verification-gap), twist lock-retreat test (medium; verification-gap + blind host-coverage row), pair-formation pose for twists (low; blind + edge), all four overflow directions tested (low). Deferred 0. Rejected 15 rows with reasons in the triage log — 11 false (long-press double-tap, consumed pointers ×2, NaN orbit, property via renderer API, camera-check surface, face-key-only labels, Notation after rotation, 200 ms timing, twist haptics, double-tap area / extra scope) and 4 low (sub-slop jitter, notice wrap, self-comparing facelet assertion, text menu items vs arrow glyphs). Follow-up review recommended: true (patched: high 0, medium 2, low 2). Named unverified risk: the GL boundary check stops at `CubeGlRenderer.eye`; that `onDrawFrame` actually rebuilds and uploads the view-projection from it is unverified without a GL context.

**Verification:** `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` — pass (ViewOrbit 2, GestureInterpreter 7, ViewPose 3). `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*'` — pass (FreeCubeScreen 26, ViewRotationInvariance 1). `./gradlew spotlessCheck build` — pass after patches. Implementation fix in verify: the Kotest property test returned a non-Unit value and was silently skipped by JUnit 5; changed to `runBlocking<Unit>` and confirmed 2 tests ran. Matrix audit: default/drag right/pitch clamp/yaw wrap — `ViewOrbitTest`; twist CW/CCW/back/small/off cube and start test — `GestureInterpreterTest.cube start and twist matrix`.

**Residual risks:** gestures, orbit and twist were exercised only under Robolectric, never on a real touchscreen or emulator; `CubeGlRenderer.onDrawFrame`'s matrix rebuild runs only with a GL context (not unit-tested); the frame notice can grow the toolbar row at very large font scales. Implementation ran on Codex.

### Follow-up review pass (2026-11-04)

**Summary:** Fresh four-layer review (Blind, Edge Case and Verification Gap on Codex; Intent Alignment on Claude) of the full diff since `bf3c778a729fa44ad1328007fce5cbe6f45dab04`. No behavior defects in the orbit, twist or commit paths; patches close the GL-matrix verification gap and three small gaps.

**Files changed in this pass:**
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/ViewProjectionCache.kt` — new internal cache that rebuilds the view-projection from `ViewPose(w, h, eye)` on eye or size change.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` — uses `ViewProjectionCache` instead of inline fields.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt` — frame label is a polite live region only while the front notice shows.
- `app/src/test/kotlin/dev/rubikxplorer/app/render/gl/ViewProjectionCacheTest.kt` — drawn matrix follows the eye and the surface size.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` — exact orbit after off-cube and two-finger drags; live-region on/off; 48dp on view controls.

**Review:** 30 findings (high 0, medium 3, low 17, false 10). Patched 4 entries: GL view-projection boundary (medium; blind #7, verification-gap #1, intent #8), two-finger centroid scale (low), live region only during notice (low), 48dp on view controls (low). Deferred 0. Rejected: 10 false (double-tap timing, double-tap area, undo clears notice, property via renderer API, GL vs overlay size, pitch clamp repeats, drag-right start, face-key labels, third pointer, 200 ms) and 9 low (double-tap distance, long second tap, near-coincident pointers ×2, sub-slop jitter ×2, notice wrap, self-comparing facelet assertion, eye.z twist rule, text menu items, double-tap test gaps) — reasons in the triage log. Follow-up review recommended: false (follow-up pass; patched high 0, medium 1, low 3).

**Verification:** `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*' :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*'` — pass (ViewProjectionCacheTest 1, FreeCubeScreenTest 26). `./gradlew spotlessCheck build` — pass.

**Residual risks:** `onDrawFrame` still runs only with a GL context; its matrix now comes from the tested `ViewProjectionCache`, but the `glUniformMatrix4fv` upload itself is not unit-tested. Gestures were exercised only under Robolectric, never on a device.
