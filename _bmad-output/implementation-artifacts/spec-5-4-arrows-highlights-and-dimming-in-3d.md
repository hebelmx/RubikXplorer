---
title: 'Story 5.4: Arrows, highlights and dimming in 3D'
type: 'feature'
created: '2026-11-04'
baseline_revision: 'b5190dafa2f0851a50c2a53bce5c56dac9b63e87'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      No rendered-output (pixel) verification of the new shader emphasis (dim, 3dp/1dp outline, pulse) or of the Compose arrow overlay (stroke, arrowhead, label, projection under view rotation).
    evidence: |-
      Cube3DEmphasisTest, LessonScreenTest and DemonstrationPlayerTest assert semantics and GlSnapshot flags only; Robolectric has no GL context and the repo has no screenshot/PixelCopy test infrastructure. Settle with an instrumented render check on a device or emulator (dim/outline/pulse pixels on vs off, arrow visible and moving with the orbit), alongside the pending hardware stories.
    location: >-
      app/src/main/res/raw/cube_fragment.glsl; app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Lessons still draw on the 2D net, so Demonstrations, Hint rung 2 and *Guide me* never show on the 3D cube, and the 3D renderer has no Move arrow, highlight outline, pulse or dimming (FR-6, FR-11, AD-4, AD-20, UX-DR4, UX-DR6).

**Approach:** Teach `Cube3DRenderer` to draw a `NetEmphasis` (per-cubie dim / 3dp highlight / pulse in the GL shader) and an optional Move arrow (a pure world-space arc in `:core:domain`, projected through the current `ViewPose` onto a Compose overlay, drawn in over a short tween or at once under reduce motion); then move the Lesson stage and the Show me Demonstration player from `Net2DRenderer` to `Cube3DRenderer`, keeping every existing emphasis rule.

## Boundaries & Constraints

**Always:**
- `Cube3DRenderer.Content(style, modifier, onMoveIntent = null, emphasis: NetEmphasis? = null, arrow: Move? = null, reduceMotion: Boolean = false)`. With the defaults it behaves exactly as in Story 5.3 (free cube unchanged: no arrow, no emphasis, same tags, gestures, view controls and commit path).
- Emphasis semantics equal `Net2DRenderer`'s, moved into `NetEmphasis` members both renderers use: centers and pieces in `lit` or `highlighted` draw normally, every other piece's stickers get `sticker-dimmed-overlay` (#00000099, i.e. color × 0.4); `highlighted` pieces get a 3dp `sticker-highlight` (#A797FF) outline inside the sticker edge and a 1dp `cubie-body` inner stroke inside that, sized in screen dp (`fwidth` in the fragment shader, uniform px-per-dp); while `pulse > 0` highlighted stickers also mix toward `sticker-highlight` by `0.55 × pulse`. Stickers are never recolored. A piece is the set of its cubie's sticker colors (matches `Piece.at`), so emphasis follows a moving piece through turns.
- GL boundary: `GlSnapshot` carries per-cubie emphasis flags (dim, highlight) and the pulse, built by one pure function that the semantics also use; the per-facelet `StickerDraw` in `RenderedStickersKey` gains `dimmed`, `highlighted`, `pulsing` (all `false` without emphasis, so free-cube semantics are unchanged).
- Arrow: pure `MoveArrow.arc(move: Move, eye: Vector3): List<Vector3>` in `core/domain/.../input/` — ≥ 16 points on a circle of radius `ARROW_RADIUS` = 1.85 about the Move's turn axis (the axis `CubieModel.begin` uses: face/wide → that face, M → L, E → D, S → F, x → R, y → U, z → F), in the plane at axis offset 1 (face), 0.5 (wide), 0 (slice, Cube Rotation); spanning 90° (180° for `Turn.HALF`) in the turn's direction (`CLOCKWISE` = −90° about the outward axis, as `CubieModel`); its angular midpoint points toward the eye projected into that plane (fallback +y for x/z axes, +z for y when that projection is shorter than 0.1). The last point is the tip.
- Overlay: drawn above the GL surface in the same box, through `ViewPose(width, height, orbit.eye())`; a 4dp `sticker-highlight` stroke with an arrowhead at the tip and `Notation.format(move)` in `RubikTheme.textStyles.notation` beside the tip. When `arrow` becomes non-null or changes, the stroke draws in over `ARROW_IN_MILLIS` = 200 ms on the frame clock; with `reduceMotion` it appears complete at once. `cube3d` exposes `RenderedArrowKey` → `RenderedArrow(notation, progress)` (absent when `arrow == null`).
- Lesson stage (`LessonScreen` Guide me / Test me / Let me try): one `Cube3DRenderer` per Lesson replaces the net; same emphasis `when` as today; `arrow` = the Move of a running stage demonstration (Show me how, Hint top rung, adaptive demonstrate), else `Tutor.nextMove(lesson, session.entry, session.virtual)` when `session.shownRung >= HintRung.TARGET && !decision.advance`, else null. `reduceMotion` = `rememberReduceMotion()`.
- Lesson gestures: `onMoveIntent = { caption = null; send { SessionEvent.MoveCommitted(it.move, t) } }` — exactly the Lesson Move keys' path (SessionHost → `Reduce`); never `MoveHistory`, no haptic. View Rotation stays renderer-local; the Lesson toolbar gains the free cube's reset-view button and "View options" menu (same tags/strings), extracted into one shared composable both screens use.
- Show me: `DemonstrationPlayerState` plays on a `Cube3DRenderer` (square aspect, no gestures), emphasis as today, `arrow` = `currentMove`. `NetPlayback` takes its renderer from its owner so `SolutionPlayback` keeps `Net2DRenderer`.
- `demonstrateOnNet`, `demonstrateStep`, `climbHintLadder`, `ResetConfirmation` wiring accept `CubeRenderer` instead of `Net2DRenderer`; behavior and timing (`REORIENT_STEP_MILLIS`, `REORIENT_HOLD_MILLIS`) unchanged.

**Never:**
- No change to `CubeRenderer`, `PresentationItem`, `SessionEvent`, `Reduce`, `MoveHistory`, `FreeCubeViewModel` commit path, the Manual Cube Editor, `SolutionPlayback`'s renderer, `build-logic/`, `_bmad/`, `.claude/skills/`, `_bmad-output/planning-artifacts/`, spike source sets.
- No TalkBack summary/focus-order work (5.5), no new dependency, no logging of Cube States, no shake/red flash, no sticker recoloring.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Face arc | `arc(R, DEFAULT_EYE)` | all points x = 1, distance from x axis 1.85; tip = start rotated −90° about +x | — |
| Prime / half | `R'` / `R2` | sweep +90° / −180° | — |
| Slice / wide / rotation | `M`, `r`, `y` | plane x = 0 (axis −x), x = 0.5, y = 0 | — |
| Faces eye | `arc(U, eye)` | midpoint · eye-in-plane > 0 | — |
| Degenerate eye | `arc(F, (0,0,6))` | fallback center +y; points finite | — |
| No emphasis | free cube | every `StickerDraw` dimmed/highlighted/pulsing false; snapshot flags 0 | — |
| Off-path hint | rung TARGET, `nextMove` null | no arrow, outline kept | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- `Content` (pointer host, `publish()` → `GlSnapshot`, `clearAndSetSemantics` on `cube3d` with `RenderedStickersKey`); add emphasis/arrow params, overlay, `RenderedArrowKey`. Children semantics are cleared, so arrow data must sit on the `cube3d` node.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` -- per-cubie `glDrawArrays` loop (set per-cubie emphasis uniforms there); attribute layout `sizes = 3,3,3,2,3,1` (stride `FLOATS_PER_VERTEX` = 15, mirrored in `CubeMesh`).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeMesh.kt` -- `face()` writes per-vertex data; sticker-local (a, b) ∈ [−1, 1] is available there; body boxes vs stickers; `StickerDraw`, `stickerDraws`.
- `app/src/main/res/raw/cube_vertex.glsl`, `cube_fragment.glsl` -- add sticker-local coords/flag varyings and dim/highlight/pulse/px-per-dp uniforms.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubieModel.kt` -- `cubies[i].stickers[*].color` (piece identity), `begin()` axis choice to mirror in `MoveArrow`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt` -- `NetEmphasis` and the inline dim/highlight rule in `Sticker()` to move into `NetEmphasis` members; `StickerDimmedKey`… keys stay for the net.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Tokens.kt` -- `CubeTokens.stickerHighlight`, `cubieBody`, `stickerDimmedOverlay`; `Type.kt` `notation`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewPose.kt` -- `Vector3`, `project`, `DEFAULT_EYE`; `cube/Move.kt` move types.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- `LessonBody` (renderer, `stopReorientation`, emphasis `when`), `LearnerStage`, `demonstrateOnNet`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/HintLine.kt`, `DemonstrationPlayer.kt`, `app/src/main/kotlin/dev/rubikxplorer/app/ui/playback/PlaybackTransport.kt` (`NetPlayback`), `app/src/main/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlayback.kt`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt` -- reset-view + `DropdownMenu` block to extract (tags `stage:resetView`, `stage:viewMenu`, `viewRotate:*`).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Tutor.kt:48` -- `nextMove`.
- Tests: `app/src/test/.../ui/lesson/LessonScreenTest.kt`, `DemonstrationPlayerTest.kt`, `ui/shell/AppShellTest.kt` read `sticker:$i` nodes (net) -- switch to `RenderedStickersKey` on `cube3d`; `render/Cube3DRendererTest.kt`, `ui/freecube/FreeCubeScreenTest.kt` must stay green; `androidTest/.../Cube3DRenderSyncTest.kt` unchanged.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/MoveArrow.kt` -- pure arc per Always -- testable geometry under Kover.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/MoveArrowTest.kt` -- every I/O matrix arc row, for all 18 face turns plus slices/wides/rotations: plane, radius, sweep, finiteness.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt` -- `NetEmphasis.dims(piece)` / `highlights(piece)`; net uses them (net behavior identical).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeMesh.kt`, `CubeGlRenderer.kt`, `res/raw/cube_*.glsl` -- emphasis flags builder, vertex layout, uniforms, shader outline/dim/pulse.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- new params, snapshot flags, `StickerDraw` emphasis fields, arrow overlay and `RenderedArrowKey`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt` (+ shared view-controls composable) -- extraction only.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/playback/PlaybackTransport.kt`, `ui/solve/SolutionPlayback.kt`, `ui/lesson/DemonstrationPlayer.kt`, `HintLine.kt`, `LessonScreen.kt` -- Lessons on `Cube3DRenderer` per Always; stage demonstration exposes its playing Move as state for `arrow`.
- `app/src/test/kotlin/dev/rubikxplorer/app/render/Cube3DEmphasisTest.kt` -- emphasis flags vs `NetEmphasis` rule on a scrambled state, flags follow a piece after an `Animate` drains, snapshot flags equal semantics; arrow progress 0 → 1 over 200 ms, instant with `reduceMotion`; no arrow/emphasis by default.
- Existing lesson/shell tests -- migrate sticker reads to `RenderedStickersKey`; keep every assertion's meaning; add the ACs below.
- `docs/ARCHITECTURE.md` -- one line: Lessons draw on `Cube3DRenderer`; arrows are a projected `MoveArrow` overlay, emphasis is per-cubie shader flags.

**Acceptance Criteria:**
- Given *Show me* on a Lesson, when the Demonstration shows step 1, then `cube3d` reports `RenderedArrow(Notation.format(currentMove), 1f)` after 200 ms and the focused step's target stickers are `highlighted`, off-step stickers `dimmed`, Keep stickers lit.
- Given *Test me* at Hint rung 2 on the path, when the rung shows, then the arrow notation equals `Tutor.nextMove(...)` and the `HintLadder.highlight` stickers are `highlighted` with nothing dimmed; at rung 3 the arrow follows each demonstrated Move, then returns to the next Move.
- Given a *Guide me* step, when it shows, then stickers of pieces outside `focus.lit` are `dimmed`, Keep pieces are not, and the existing Diagnosis / Guided-recovery / pulse emphasis tests pass on the 3D stage.
- Given reduce motion, when an arrow appears, then its first frame already reports progress `1f`.
- Given the Lesson cube, when a layer-turn drag or twist commits, then `host` receives `MoveCommitted` (session `virtual` and `history` advance exactly as with the Move key), and `renderedFacelets()` equals `virtual` after draining.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes (Kover ≥ 80 % on `:core:domain`, no new dependency).

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 19 findings — high 0, medium 6, low 10, false 3, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Publishing coroutine captured the first `density`, so a live density change left the GL px-per-dp stale — `density` now read via `rememberUpdatedState` in `Cube3DRenderer.Content`.
  - `[low]` `[patch]` (blind) `fwidth(vLocal)` sums |dFdx|+|dFdy|, so diagonal sticker edges got up to √2-wide strokes — fragment shader now divides by the per-axis Euclidean gradient length, with derivatives taken outside branches.
  - `[low]` `[reject]` (blind) Show me has no view rotation, so back/bottom targets can be hidden — the intent fixes Show me as "square aspect, no gestures" and gives view controls to the Lesson toolbar only; the arrow midpoint always faces the eye.
  - `[low]` `[reject]` (blind) Arrow label may overflow the stage at small sizes or large font scales — rare at stage sizes in use; fix needs measured-bounds layout logic.
  - `[low]` `[reject]` (blind) Slice/rotation arc collapses to a line when the eye lies in the arc plane — needs an orbit exactly in that plane; fix adds a presentation fallback branch beyond the spec's geometry.
  - `[low]` `[reject]` (blind) Repeated identical Moves do not restart the draw-in — matches the spec ("becomes non-null or changes"); fix adds an occurrence key parameter.
  - `[false]` `[reject]` (blind) Cancelled demonstration's `finally { onMove(null) }` can clear the replacement's arrow — `stopReorientation` cancels before `scope.launch`; on the FIFO UI dispatcher the cancelled job's `finally` runs before the new job's first `onMove(move)`.
  - `[medium]` `[defer]` (blind) No pixel-level verification of shader/overlay — grouped with the rendered-output gap; needs a GPU device, deferred.
  - `[medium]` `[patch]` (blind) AC "layer-turn drag or twist" only tested for the twist — added `lesson layer-turn drag commits through the session like the Move key` in LessonScreenTest (U via projected drag, history/virtual/render match, agrees with `moveKey:U`).
  - `[low]` `[patch]` (edge) Stale density in publish closure — same root and fix as the first row.
  - `[low]` `[patch]` (edge) Diagonal edges up to √2 stroke width — same root and fix as the second row.
  - `[medium]` `[patch]` (verification-gap) Shader emphasis could silently vanish (e.g. sticker marker always 0) while tests pass — CubeMeshTest now asserts the sticker marker (0 on body, 1 on stickers) and sticker-local corners ±1 in the vertex data; pixel output itself deferred (row above).
  - `[medium]` `[defer]` (verification-gap) Arrow overlay drawing/projection not observed by tests — part of the deferred rendered-output gap.
  - `[medium]` `[defer]` (intent) Visual reading (shader output, Canvas drawing) untested — part of the deferred rendered-output gap.
  - `[low]` `[reject]` (intent) Ambiguity: value-equality arrow keying — same as the repeated-Moves row.
  - `[medium]` `[defer]` (intent) Free cube pixel-identity under the new vertex format untested — part of the deferred rendered-output gap; JVM side covered by CubeMeshTest and `No emphasis` flags/semantics tests.
  - `[false]` `[reject]` (intent) Arrow clearing on cancellation untested — the outcome cannot occur: `stopReorientation` sets `playingMove = null` before cancelling.
  - `[false]` `[reject]` (intent) Lesson pulse untested — existing LessonScreenTest pulse tests read `pulsing` from `RenderedStickersKey` on `cube3d`, and the snapshot pulse comes from the same `latestEmphasis`.
  - `[low]` `[patch]` (intent) `glSnapshot` inserted under `glEye`'s KDoc — gave `glSnapshot` its own KDoc and restored `glEye`'s.

### 2026-11-04 — Review pass
- verdicts: 24 findings — high 0, medium 9, low 12, false 3, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Show me hides back/bottom targets with no view controls — carried: the intent fixes Show me as "square aspect, no gestures" and gives view controls to the Lesson toolbar only.
  - `[low]` `[reject]` (blind) Arrow collapses to a line when the eye lies in its plane — carried: needs an orbit exactly in that plane; fix adds a presentation fallback branch.
  - `[low]` `[reject]` (blind) Notation label at `tip + 8.dp` unclamped — carried: rare at stage sizes in use; fix needs measured-bounds layout logic.
  - `[false]` `[reject]` (blind) Gestures during a demonstration commit against a different state — `onMoveIntent` goes through `send`, which calls `stopReorientation()` (snapping the renderer back to the session state) before dispatching `MoveCommitted`; layer Moves are positional, so this is exactly the Move-key path the intent requires.
  - `[low]` `[reject]` (blind) Rapid stepping in Show me shows a later Move's arrow while earlier queued turns still animate — real but transient (at most one `TURN_MILLIS` per queued turn) and matches the intent's `arrow = currentMove`; syncing to the drained item needs new renderer state plumbing.
  - `[low]` `[reject]` (blind) Repeated identical Moves get no fresh draw-in — carried: matches the spec ("becomes non-null or changes"); fix adds an occurrence key parameter.
  - `[medium]` `[defer]` (blind) No device render test of shader dim/outline/pulse or free-cube appearance — carried: part of the deferred rendered-output gap.
  - `[medium]` `[defer]` (blind) Arrow tests never check drawn path/arrowhead/label under orbit changes — carried: part of the deferred rendered-output gap.
  - `[medium]` `[patch]` (blind) Lesson view controls (an Always item) have no integration coverage — added `lesson view controls rotate and reset the camera without touching the session` in LessonScreenTest: rotate right leaves virtual/history unchanged, a drag projected through the rotated orbit commits U, reset records nothing, a drag through the default orbit commits U again, net matches session.
  - `[low]` `[patch]` (blind) `NetPlayback`'s injected renderer lost its "starts at `start`" doc — the class KDoc now states the owner supplies a renderer already showing `start` (both owners do).
  - `[low]` `[reject]` (edge) Edge-on slice/rotation arc — carried, same as the blind row.
  - `[low]` `[reject]` (edge) Label outside stage at large font scale / small stage — carried, same as the blind row.
  - `[medium]` `[defer]` (verification-gap) Shader emphasis can disappear while tests stay green — carried: deferred rendered-output gap.
  - `[medium]` `[defer]` (verification-gap) Arrow semantics do not verify the visible overlay — carried: deferred rendered-output gap.
  - `[medium]` `[defer]` (intent) Dim/outline/pulse pixels untested — carried: deferred rendered-output gap.
  - `[false]` `[reject]` (intent) Emphasis tests compare code with itself and never check mid-turn — the per-sticker loop checks against `NetEmphasis.dims`/`highlights`, and `CubeMesh.emphasisFlags` keys on each cubie's own sticker colors, which a turn never changes, so flags cannot drift mid-animation.
  - `[medium]` `[defer]` (intent) Projected arrow overlay drawing untested — carried: deferred rendered-output gap.
  - `[low]` `[reject]` (intent) Value-equality arrow keying (identical Move no restart) — carried, same as the repeated-Moves row.
  - `[low]` `[reject]` (intent) Toggling `reduceMotion` restarts the draw-in — a system-setting change mid-arrow is rare and the result (a 200 ms redraw, or instant) is harmless; fix adds keying logic.
  - `[low]` `[reject]` (intent) Show me how / adaptive demonstrate arrow not tested — both pass the same `onPlayingMove` into the same `demonstrateOnNet` that the tested Hint top-rung path uses; an extra test adds little.
  - `[medium]` `[patch]` (intent) Lesson `ViewControls` untested — same root and fix as the blind view-controls row.
  - `[medium]` `[defer]` (intent) Free cube pixel identity under the new vertex layout — carried: deferred rendered-output gap.
  - `[false]` `[reject]` (intent) DemonstrationPlayerTest asserts `false` where it asserted `null` — the meaning ("not dimmed / not highlighted") is unchanged; `RenderedStickersKey` always carries flags, so `false` is the exact equivalent of the net's absent key.
  - `[low]` `[reject]` (intent) Net2D emphasis refactor covered only by existing tests — no production caller now passes emphasis to `Net2DRenderer` (Solve Playback has none), and the shared `dims`/`highlights` are exercised by Cube3DEmphasisTest.

## Design Notes

The arrow is a Compose overlay rather than a GL mesh: it needs text at the tip, must be testable without a GL context, and is never occluded. Emphasis is per cubie because a piece is a cubie; the pixel-exact 3dp/1dp stroke comes from `fwidth` of the sticker-local edge distance. Lesson view controls are needed because the Lesson cube now accepts gestures (NFR-7: every gesture has a non-gesture equivalent).

## Verification

**Commands:**
- `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` -- expected: pass.
- `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.*'` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: pass.

**Manual checks (if no CLI):**
- `git diff --stat <baseline>` shows nothing under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `build-logic/`, `app/src/spike3d*`.

## Auto Run Result

Status: done

**Summary:** `Cube3DRenderer` now draws `NetEmphasis` (per-cubie dim / 3dp highlight + 1dp body stroke / pulse in the GL shader, flags from one pure `CubeMesh.emphasisFlags`) and an optional Move arrow (pure `MoveArrow.arc` in `:core:domain`, projected onto a Compose overlay, 200 ms draw-in or instant under reduce motion, exposed as `RenderedArrowKey`). The Lesson stage and the Show me Demonstration player moved from `Net2DRenderer` to `Cube3DRenderer`; Lesson gestures commit through `SessionEvent.MoveCommitted`, and the free cube's view controls were extracted into a shared `ViewControls`. Solve Playback keeps `Net2DRenderer`.

**Files changed:**
- `core/domain/.../input/MoveArrow.kt` (+ `MoveArrowTest.kt`) — pure arc geometry and its full Move-type test.
- `app/.../render/Cube3DRenderer.kt` — emphasis/arrow/reduceMotion params, snapshot flags, arrow overlay, `RenderedArrow(Key)`.
- `app/.../render/Net2DRenderer.kt` — `NetEmphasis.dims` / `highlights`, used by both renderers.
- `app/.../render/gl/CubeMesh.kt`, `CubeGlRenderer.kt`, `res/raw/cube_vertex.glsl`, `cube_fragment.glsl` — emphasis flags, 18-float vertex layout (sticker-local coords + sticker marker), per-cubie uniforms, shader dim/outline/pulse.
- `app/.../ui/freecube/ViewControls.kt` (new), `FreeCubeScreen.kt` — shared reset-view + View options menu.
- `app/.../ui/lesson/LessonScreen.kt`, `HintLine.kt`, `DemonstrationPlayer.kt` — Lessons on the 3D cube, arrow wiring, gestures, `CubeRenderer` parameters.
- `app/.../ui/playback/PlaybackTransport.kt`, `ui/solve/SolutionPlayback.kt` — `NetPlayback` takes its renderer from its owner.
- Tests: new `Cube3DEmphasisTest.kt`; `CubeMeshTest.kt`, `LessonScreenTest.kt`, `DemonstrationPlayerTest.kt`, `AppShellTest.kt` migrated/extended.
- `docs/ARCHITECTURE.md` — one line on Lessons on `Cube3DRenderer`, arrow overlay, per-cubie shader flags.

**Review findings:** 19 findings. Patches applied: 5 entries (medium 2: lesson layer-turn drag test, vertex sticker-marker/local-coord test; low 3: density via `rememberUpdatedState`, Euclidean-gradient stroke width, `glSnapshot` KDoc). Deferred: 1 entry (no rendered-output pixel verification of shader emphasis and arrow overlay; medium). Rejected: Show me view rotation (intent fixes Show me as no-gesture), label overflow (rare, needs layout logic), edge-on arc (rare, extra fallback branch), repeated-Move draw-in (matches spec, extra key parameter), cancellation race (false: FIFO dispatch), cancellation-clearing test (false: outcome cannot occur), Lesson pulse test (false: covered).

**Follow-up review recommendation:** true — two medium entries were patched (first pass). Named unverified risk: the GL shader emphasis (dim ×0.4, 3dp/1dp outline, pulse mix) and the arrow overlay have never been observed on a real GPU; only flags/semantics are tested.

**Verification:** `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` — pass. `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.*'` — pass (LessonScreenTest 47, Cube3DEmphasisTest 2, CubeMeshTest, 0 failures). `./gradlew spotlessCheck build` — pass (after `spotlessApply` normalized line endings of new files to the checkout's CRLF). Scope check: no changes under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/`, `build-logic/`, spike source sets. Matrix audit: every I/O row covered (MoveArrowTest for arc rows; Cube3DEmphasisTest for No emphasis; LessonScreenTest rung-3 test for Off-path hint).

**Residual risks:** shader and overlay visuals unverified on device; the GLSL compiles only at runtime on a GL ES 3 context; label placement on very small stages; Show me cannot be rotated to see hidden faces (by design).

### Follow-up review pass (2026-11-04)

**Summary:** Fresh four-layer review (Codex blind, edge-case and verification-gap layers; Claude intent-alignment) of the full 5.4 diff since `b5190dafa2f0851a50c2a53bce5c56dac9b63e87`. No high findings; two small patches applied.

**Files changed this pass:**
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt` — new test: Lesson view controls rotate and reset the camera without changing the session.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/playback/PlaybackTransport.kt` — `NetPlayback` KDoc states the injected renderer must already show `start`.

**Review findings:** 24 findings. Patches applied: 2 entries (medium 1: Lesson view-controls test; low 1: `NetPlayback` renderer KDoc). Deferred: none new (rendered-output pixel gap already deferred; carried rows). Rejected: Show me view rotation, edge-on arc, label overflow, identical-Move draw-in (all carried); gesture-during-demonstration (false: `send` snaps back first, same as Move keys); rapid-step arrow lead (low, transient, matches `arrow = currentMove`); `reduceMotion` toggle restart (low, harmless); Show me how/adaptive arrow test (low, shared callback path is tested); mid-turn emphasis drift (false: flags keyed on cubie colors); `false` vs `null` assertion (false: same meaning); Net2D emphasis coverage (low, no production caller passes emphasis).

**Follow-up review recommendation:** false — follow-up pass patched no high (patched: high 0, medium 1, low 1); the work has converged.

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.ui.lesson.LessonScreenTest'` — pass (48 tests, 0 failures, new test included). `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.input.*'` — pass. `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.*'` — pass. `./gradlew spotlessCheck build` — pass.

**Residual risks:** unchanged from the first pass: shader and arrow overlay visuals are unverified on a real GPU (deferred); label placement on very small stages; Show me cannot be rotated.
