---
title: 'Story 5.2: Turn layers with gestures'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: '410fcc2299a5edef82297102abc7329813e53a2a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The 100 ms input acknowledgement is proved only structurally (commit and haptic fire synchronously on lift under a paused Robolectric frame clock), not timed on a device.
    evidence: |-
      No physical device is attached. FreeCubeScreenTest asserts commit and tick right after up() with no clock advance. Sprint change 2026-11-02 assigns physical latency measurement to Story 12.2; a touch-to-tick timing there settles it.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The 3D free cube (Story 5.1) can only be turned with Move keys; the Learner cannot drag a layer, so it does not feel like a real cube (FR-1, AD-4, UX-DR26/27).

**Approach:** Add a pure `GestureInterpreter(touchPath, viewPose) → MoveIntent?` in `:core:domain` (ray–box picking of the start sticker, end point projected onto the hit face plane, dominant in-plane direction → layer Move), drive the GL camera from the same `ViewPose`, and host one-finger drags on the 3D stage so a recognized drag commits through the free cube's existing `onMoveIntent` path with a switchable light haptic tick.

## Boundaries & Constraints

**Always:**
- `MoveIntent` moves (git mv + package change) to `:core:domain` package `dev.rubikxplorer.domain.input`, together with `GestureInterpreter`, `TouchPath`, `ViewPose`; all `:app` imports updated (keys, lesson screen, free cube). No Android/Compose imports in these files.
- One `ViewPose` (camera eye, look-at origin, up +y, FOV across the narrower viewport side, viewport width/height in px) is the single source for both the interpreter's unprojection and `CubeGlRenderer`'s view-projection matrix; `ViewPose.DEFAULT_EYE` = (4.2, 4.6, 6.4), FOV 38° (today's GL constants). Picking and drawing therefore cannot disagree.
- Cube geometry in the interpreter: cubies at integer coords −1..1 in the viewer frame (+x R, +y U, +z F), outer surface the box [−1.5, 1.5]³; hit cell = nearest integer of the hit point's two tangent coords, clamped to −1..1.
- Recognition: the path's first point must hit the box on a camera-facing face; the last point's ray is intersected with that face's plane; the in-plane displacement D must have length ≥ `MIN_DRAG` (0.35 cubie units) and its major tangent component ≥ 2 × its minor (direction confidence). Otherwise return `null`. Rotation axis = normal × major direction; layer = hit cell's coordinate on that axis; layer ±1 → `FaceTurn` of the face on that side, 0 → `SliceTurn` (M follows L, E follows D, S follows F); quarter turn only (`CLOCKWISE`/`COUNTERCLOCKWISE`), so the touched sticker moves in the drag direction.
- The host commits on finger lift (one pointer for the whole gesture); a gesture that ever has a second pointer, or starts off the cube, commits nothing (View Rotation is Story 5.3). Input during an animation commits immediately.
- A recognized gesture and a Move key share one free-cube commit function: light haptic tick (`HapticFeedbackType.KeyboardTap` via `LocalHapticFeedback`) when `FreeCubeViewModel.hapticsEnabled`, then `viewModel.onMoveIntent(intent)`. The switch is a ViewModel flag plus a debug toggle `debug:haptics` (default on); Settings persistence is Story 6.4.

**Never:**
- No View Rotation, two-finger twist, double-tap reset, camera movement, arrows, highlights or TalkBack changes (Stories 5.3–5.5); `ViewPose` stays fixed at the default eye in this story.
- No change to `CubeRenderer`, `PresentationItem`, `RenderStyle`, `build-logic/`, `_bmad/`, `.claude/skills/`, `_bmad-output/planning-artifacts/`, the spike source sets, or lesson rendering (lessons keep `Net2DRenderer` and their `MoveKeyBar` tick behavior).
- No new dependency, no logging of Cube States/facelets, no new `SessionEvent`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Face layer | default pose, start F top-row sticker, drag toward −x (screen-left) | `FaceTurn(U, CLOCKWISE)` | — |
| Opposite dir | same start, drag toward +x | `FaceTurn(U, COUNTERCLOCKWISE)` | — |
| Column | start F right column, drag toward +y | `FaceTurn(R, CLOCKWISE)` | — |
| Middle layer | start F center column, drag +y / F middle row, drag +x | `SliceTurn(M, COUNTERCLOCKWISE)` / `SliceTurn(E, CLOCKWISE)` | — |
| Any pose | ≥ 4 poses (eyes at distinct cube corners, together showing all 6 faces) × every visible face × row and column × both directions | the layer Move for that world direction, identical across poses | — |
| Short drag | in-plane length < `MIN_DRAG` | `null` | no commit, state unchanged |
| Diagonal | major < 2 × minor | `null` | no commit |
| Off cube | first point misses the box | `null` | — |
| Degenerate | < 2 points, end ray parallel to / behind the face plane | `null` | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/rubikxplorer/app/render/MoveIntent.kt` -- `data class MoveIntent(val move: Move)`; move to `core/domain/.../domain/input/MoveIntent.kt`. Users: `ui/freecube/FreeCubeScreen.kt:82`, `FreeCubeViewModel.kt:47`, `ui/lesson/LessonScreen.kt:537`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- `FaceTurn(face, turn)`, `SliceTurn(Slice.M|E|S, turn)`, `Turn.CLOCKWISE` = clockwise seen from that face; `Face` in `cube/Face.kt`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubieModel.kt` -- viewer-frame conventions: `axisOf(face)` (line ~221), turn = −90° × quarters about the outward axis; `begin(move)/setProgress/commit`, `Cubie.position()`, sticker normals. Independent oracle for gesture direction.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeMesh.kt` -- `BODY_HALF 0.47`, `STICKER_HALF 0.40`, `STICKER_LIFT 0.472` (stickers ≈ at ±1.472).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` -- `EYE_*`, `FOV_DEGREES`, `NEAR/FAR`, `onSurfaceChanged` (fovY from narrower side), `setLookAtM` in `onSurfaceCreated`; replace with `ViewPose`'s column-major view-projection.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- `Content(style, modifier)`; the `Box` with `testTag("cube3d")` + `clearAndSetSemantics` wraps the `AndroidView`. Add an `onMoveIntent: ((MoveIntent) -> Unit)? = null` parameter and a `matchParentSize` overlay with `pointerInput` over the `AndroidView` (GLSurfaceView does not consume touches, but the overlay avoids interop ordering).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/MoveKeyBar.kt` -- `KeyRow` ticks unconditionally (line ~123). Add `tickOnCommit: Boolean = true`; the free cube passes `false` and ticks in its shared commit function; `LessonScreen` keeps the default.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt` / `FreeCubeViewModel.kt` -- `CubeStage` calls `renderer.Content`; `DebugToggles` / `DebugSwitch` pattern (`debug:glyphs`) for the new `debug:haptics`; strings in `app/src/main/res/values/strings.xml`.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- Robolectric harness (`launch`, `assertRenderedMatchesState`, `mainClock.autoAdvance = false`, `cube3d` node). Inject a recording `HapticFeedback` via `CompositionLocalProvider(LocalHapticFeedback provides ...)` inside `setContent`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/AllMoves.kt` -- existing move-list helpers; Kotest property is on the domain test classpath. `:core:*` Kover gate ≥ 80 %.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/MoveIntent.kt` -- moved from `:app` (git mv), package `dev.rubikxplorer.domain.input`; update every import.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewPose.kt` -- immutable pose: eye, FOV, viewport px; `DEFAULT` eye; `viewProjection(): FloatArray` (column-major, GL-ready, same math as today's `setLookAtM` + `perspectiveM`), `ray(x, y)` unprojection, `project(point)` → screen px (for host tests and fixtures).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/TouchPath.kt` + `GestureInterpreter.kt` -- `TouchPath(points)` in viewport px (y down); `GestureInterpreter.interpret(path, pose): MoveIntent?` per the Always rules; `MIN_DRAG`, `DOMINANCE = 2` public constants.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/GestureInterpreterTest.kt` -- every I/O matrix row; fixtures built by projecting a sticker center and center + 0.6 × world direction through `pose.project`; plus `ViewPoseTest` (origin projects to viewport center, `ray` inverts `project`, viewProjection matches a hand-computed matrix for the default pose).
- `app/src/test/kotlin/dev/rubikxplorer/app/render/GestureModelAgreementTest.kt` -- for each fixture pose, every visible sticker × 4 in-plane directions: the interpreter's Move, run on `CubieModel` at a small progress, moves that sticker's world center with a positive component along the drag direction (independent oracle for signs).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` -- view-projection from `ViewPose(width, height)` in `onSurfaceChanged`; drop the duplicated camera constants.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- `onMoveIntent` parameter; overlay collects one pointer's path (positions in the cube node's px), on lift calls the interpreter with `ViewPose` of the node size and forwards a non-null intent; multi-pointer or null → nothing.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` -- `hapticsEnabled` (default true) + `onHapticsChange`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt`, `MoveKeyBar.kt`, `app/src/main/res/values/strings.xml` -- shared commit function (tick if enabled, then `onMoveIntent`) for keys and gestures; `tickOnCommit` param; `debug:haptics` switch.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- gesture tests per the ACs below; existing tests stay green.
- `docs/ARCHITECTURE.md` -- one line: gestures are interpreted by the pure `domain/input/GestureInterpreter`; `ViewPose` drives both picking and the GL camera.

**Acceptance Criteria:**
- Given the free cube on the default pose, when a one-finger swipe starts on the projected F top-row sticker and moves screen-left by ≥ 1 sticker, then on lift `history.state` equals `SOLVED.apply(U)` with no clock advance, the recording haptic received exactly one tick, and `renderedFacelets()` equals the committed state within 250 ms on the frame clock — the same states a `moveKey:U` tap produces.
- Given a swipe shorter than the threshold, a diagonal swipe, or a swipe starting off the cube, when it ends, then `history` is unchanged, no haptic fires, and the drawn stickers are unchanged.
- Given `debug:haptics` switched off, when a recognized swipe or a Move key commits, then the Move is committed and no haptic fires; switched back on, both tick.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes (boundary gates, Kover ≥ 80 % on `:core:domain`, no new dependency).

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 17 findings — high 0, medium 1, low 4, false 11, maybe-false 1
- findings:
  - `[false]` `[reject]` (blind) NaN touch coordinates reach `roundToInt()` and throw — the only caller is the Compose pointer host, whose positions are always finite; a loud failure on an unreachable input is correct behavior.
  - `[false]` `[reject]` (blind) `ViewPose` accepts degenerate eyes/FOV (origin, vertical axis) — production builds only `ViewPose(width, height)` with the fixed default eye this story; no caller can pass a degenerate pose (camera movement is Story 5.3).
  - `[low]` `[reject]` (blind) hits in the gap between stickers (±0.5) round to the nearest layer — the spec's nearest-cell rule; a touch in the 0.2-unit black gap picking the nearest layer is not a wrong turn users would notice; a rejection band adds branches.
  - `[low]` `[patch]` (blind) gesture host keeps every pointer sample though only endpoints are read — host now keeps just the first and last point (`Cube3DRenderer.kt`).
  - `[low]` `[reject]` (blind) picking during a 200 ms turn uses rest geometry — a start on a visibly displaced sticker for at most one animation; the spec requires immediate commit during animation; animated picking is more than a direct correction.
  - `[low]` `[reject]` (blind) no tests for cancellation / disposal / consumed-by-other-handler — code read: consumed changes set `valid = false`, and disposal cancels the `pointerInput` coroutine before the commit line; no named failure, adding tests for unlikely paths is not a direct correction.
  - `[medium]` `[patch]` (blind) portrait GL matrix untested — grouped with verification-gap #1; new `ViewPoseTest` case projects off-center points through `viewProjection()` in 800×600 and 600×800 and asserts they land on `project()`.
  - `[false]` `[reject]` (edge) NaN/infinite endpoint guard — same as blind #1; unreachable from the host.
  - `[false]` `[reject]` (edge) eye on the vertical axis gives NaN — same as blind #2; the only runtime pose is the default eye.
  - `[medium]` `[patch]` (verification-gap) portrait matrix can diverge from picking with no failing test — see blind #7 (shared test).
  - `[false]` `[reject]` (intent) gestures reach `onMoveIntent`, not `SessionEvent.MoveCommitted` — on the free cube the Move key uses exactly `onMoveIntent` (no session there), and the gesture shares the same commit function; lesson surfaces still render the 2D net, so no gesture can reach them.
  - `[false]` `[reject]` (intent) gestures wired only into the free cube — the free cube is the only 3D surface (Story 5.1); lessons need `NetEmphasis` until Story 5.4; no surface accepting gestures bypasses the key path.
  - `[false]` `[reject]` (intent) `ViewPose` cannot express camera roll — no reachable pose has roll; the interpreter is tested over 4 corner eyes × 2 aspects showing all 6 faces, which is the AC's pose × direction fixture set.
  - `[maybe-false]` `[defer]` (intent) 100 ms acknowledgement not timed — commit and tick are synchronous on lift; device timing needs hardware (Story 12.2); recorded in `deferred`.
  - `[false]` `[reject]` (intent) haptics switch is a debug toggle, not Settings — the AC asks for switchable off, which holds for keys and gestures; Settings is Story 6.4.
  - `[false]` `[reject]` (intent) sticker gaps count as hits — descriptive; see blind #3.
  - `[false]` `[reject]` (intent) only one end-to-end touch case — no bad outcome: `GestureModelAgreementTest` checks every visible sticker × 4 directions × 4 poses against `CubieModel`, and the host path is the same for every Move.

## Design Notes

Turn sign: ω = n × d (unit, axis-aligned) makes the surface at the hit point move along d. A +90° turn about +k is counter-clockwise for the face whose outward axis is +k and clockwise for the face on −k. So with ω = s·k: layer +1 (R/U/F) → s = +1 CCW, s = −1 CW; layer −1 (L/D/B) → s = +1 CW; layer 0 → M/E (follow −x/−y) like layer −1, S (follows +z) like layer +1. `GestureModelAgreementTest` guards these signs against `CubieModel`.

The 100 ms acknowledgement is proved by synchronous commit + tick on lift on the frame clock; physical-device latency is Story 12.2's measurement (sprint change 2026-11-02).

## Verification

**Commands:**
- `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` -- expected: pass.
- `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*' --tests 'dev.rubikxplorer.app.ui.lesson.*'` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: pass.

**Manual checks (if no CLI):**
- `git diff --stat 410fcc2299a5edef82297102abc7329813e53a2a` shows nothing under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `build-logic/`, `app/src/spike3d*`.

## Auto Run Result

Status: done

**Summary:** A pure `GestureInterpreter` in `:core:domain` (`domain/input`) picks the start sticker with a ray–box hit, projects the end point onto that face plane, and maps a dominant in-plane drag (≥ 0.35 cubie units, major ≥ 2 × minor) to the layer's `FaceTurn` or `SliceTurn`. `MoveIntent` moved to `:core:domain`. One `ViewPose` now drives both picking and the GL camera matrix. The 3D free cube commits a recognized one-finger drag on lift through the same commit function as the Move keys, with a light haptic tick that a `debug:haptics` switch turns off.

**Files changed:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/` — `MoveIntent` (moved from `:app`), `ViewPose` (+ `Vector3`, `ScreenPoint`, `Ray`), `TouchPath`, `GestureInterpreter`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` — `onMoveIntent` parameter and pointer overlay (one pointer, commit on lift, endpoints only).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` — view-projection from `ViewPose`; camera constants removed.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt`, `FreeCubeViewModel.kt`, `MoveKeyBar.kt` — shared commit with switchable tick; `hapticsEnabled`; `tickOnCommit`; `debug:haptics` toggle.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` — import only. `app/src/main/res/values/strings.xml` — `debug_haptics`.
- Tests: `domain/input/GestureInterpreterTest`, `ViewPoseTest`; `app/render/GestureModelAgreementTest`; 4 new `FreeCubeScreenTest` cases.
- `docs/ARCHITECTURE.md` — one line on the interpreter and `ViewPose`.

**Review:** 17 findings (high 0, medium 1, low 4, false 11, maybe-false 1). Patched 2 entries: portrait GL-matrix vs picking test (medium, blind + verification-gap), endpoint-only gesture buffer (low). Deferred 1: device-timed 100 ms acknowledgement (medium, unverified → Story 12.2). Rejected 14 rows with reasons in the triage log (11 false: NaN input ×2, degenerate pose ×2, MoveCommitted path, free-cube-only wiring, camera roll, debug haptics switch, sticker gaps, single end-to-end case — and 3 low: gap rounding, picking during animation, cancellation tests). Follow-up review recommended: false (patched: high 0, medium 1, low 1).

**Verification:** `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` — pass (GestureInterpreter 3, ViewPose 3). `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*' --tests 'dev.rubikxplorer.app.ui.lesson.*'` — pass (FreeCubeScreen 19, GestureModelAgreement 1). `./gradlew spotlessCheck build` — pass (after patches). Matrix audit: face/opposite/column/middle rows — `documented front face moves`; any pose — `every visible row and column has the same world move across poses` + `GestureModelAgreementTest`; short/diagonal/off cube/degenerate — `uncertain missing and degenerate paths do nothing`; host-level no-commit — `FreeCubeScreenTest` short/diagonal/off-cube/multi-pointer case.

**Residual risks:** 100 ms acknowledgement not timed on hardware (Story 12.2); the gesture was not tried on a real touchscreen or emulator (Robolectric only); picking ignores the in-flight turn for up to 200 ms. Implementation ran on Codex.


### Product owner decisions (2026-11-04)

- **(a) Gesture commit path.** On the free cube, Move keys and gestures share `FreeCubeViewModel.onMoveIntent` → `MoveHistory.commit` (commit before animate). `MoveCommitted` is the Learning Session's event, and the free cube has no session, so the product owner accepts the shared `onMoveIntent` path as meeting the AC's "same MoveCommitted path as a Move key". A follow-up story for a free-cube event/reducer owner will be planned through bmad-correct-course after Epic 5. Gestures in lessons (Story 5.4) must send `MoveCommitted`.
- **(b) Haptics switch.** The Haptics switch is visible in release builds on the free cube screen (one labelled switch, default on, not persisted yet), and it is no longer among the debug toggles.
