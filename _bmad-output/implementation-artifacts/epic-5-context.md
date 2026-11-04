# Epic 5 Context: Turn the cube in 3D

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Replace the 2D net with a smooth, real-looking 3D cube behind the existing `CubeRenderer` port. Learners can turn layers by dragging, rotate the view freely without changing the Cube State, reorient the cube on purpose, and watch Demonstrations and hints with 3D arrows, highlights and dimming. Every action must also work without gestures or sight: Move keys, View Rotation buttons, and a TalkBack description of the cube. The epic delivers the "feels like the cube in my hands" moment that the portfolio evaluation flow depends on: smooth turns (60 FPS target), gestures, then a View Rotation.

## Stories

- Story 5.1: Render the cube in 3D behind the CubeRenderer port
- Story 5.2: Turn layers with gestures
- Story 5.3: Rotate the view and the cube
- Story 5.4: Arrows, highlights and dimming in 3D
- Story 5.5: Describe the cube to TalkBack

## Requirements & Constraints

- **Turn animation:** a quarter turn animates in ≤ 250 ms on the reference device. A Cube Rotation (x/y/z) re-frames the cube with a 200 ms turn. `Snap` items jump without animating.
- **Frame rate:** ≥ 30 FPS (frame-time p90 ≤ 33.3 ms) during face turns and View Rotation on the minimum reference device (Galaxy Tab S7 FE 5G), with a 60 FPS target on mid-range (Galaxy S24 FE). API 29–30 coverage is emulator-only.
- **Responsiveness:** face-turn input is acknowledged within 100 ms.
- **Correctness:** zero mismatches between engine and rendering. After every committed Move, the rendered cube matches the Virtual State sticker by sticker. The Story 2.3 randomized sync suite must pass against the 3D renderer in an instrumented test, with `renderedFacelets()` equal to the committed state at every drained step.
- **View Rotation:** no sequence of View Rotations changes the Cube State, and a property test proves it. Notation and Move key labels follow the cube's orientation, never the camera.
- **Accessibility:** every gesture has a non-gesture equivalent: the Move key bar, View Rotation arrow buttons in the stage overflow, and the reset-view affordance. Color-blind glyphs show on the 3D stickers. Every control has a content label. Screen readers get a cube description.
- **App size:** install stays under 30 MB. The 3D engine must add almost nothing; it is the main reason SceneView was rejected.
- **Privacy:** no `INTERNET` permission and no denylisted SDK. Never log Cube States or facelets; frame-time logs are fine.

## Technical Decisions

- **Engine: custom OpenGL ES 3.0 on `GLSurfaceView`, with no third-party dependency.** Story 1.8 chose it after testing both engines. SceneView 4.63.0 passed turn control, readback and frame time, but added about 30.4 MB to the universal release APK (about 10.7 MB arm64-only), over the 10 MB budget. It also pulled in `INTERNET`/`VIBRATE` and an HTTP client. The OpenGL ES prototype added about 66 KB, with emulator p90 of about 18 ms. The manifest declares `glEsVersion 0x00030000` as required. Physical-device confirmation is Story 1.11; a p90 above 33.3 ms there reopens the choice, which stays swappable behind `CubeRenderer`.
- **Reuse the spike's design** (`app/src/spike3d/`, report in `docs/spikes/oq-3-3d-rendering.md`):
  - **Cubie model:** a pure 27-cubie model with integer positions and an exact 3×3 rotation per cubie. Each sticker has a color and a local normal.
  - **Animation:** a temporary angle about the turn axis on the layer's cubies. `commit()` snaps by an exact integer 90° matrix, so readback is exact.
  - **Readback:** `renderedFacelets()` is read from the cubie geometry (world normal → face; position → row/col on the Kociemba net), never echoed from the last target.
  - **Rendering:** one VBO and a model matrix per cubie.
  - The spike's model tests ran only behind `-Prubikxplorer.spike3d=true`, so CI never saw them. The production model and its tests must live in the main source sets.
- **Spike gaps Epic 5 must build:**
  - GL lifecycle: forward `GLSurfaceView.onPause()`/`onResume()`, handle context loss, and delete the old program and VBOs.
  - A glyph texture atlas: 6 letters rendered once from IBM Plex Sans through `Canvas`, plus a textured sticker shader.
  - Per-sticker highlight uniforms and arrow meshes.
  - Axis-aligned ray–cubie picking for gestures.
  - Logging through the `Logger` port instead of `android.util.Log`.
- **Commit before animate:** every input source (gesture, key, accessibility action) produces a `MoveIntent`, which maps to the same `MoveCommitted` event. Input during an animation commits immediately.
  - The presentation is a list of `Animate(move)`/`Snap` items, each carrying its target revision and `CubeState`. Undo produces `Animate(inverse)`; restores and scans produce `Snap`.
  - With more than 3 items queued, the queue fast-forwards (the spike used about 62 ms per turn). It never drops a committed Move.
  - Reuse `Net2DRenderer`'s FIFO queue and fast-forward semantics.
- **Renderer contract:** `CubeRenderer` receives a `CubeState`, the presentation queue and a `RenderStyle` (color-blind display, speed). It never mutates state, and it must end exactly at the last target state.
- **Gestures:** a pure `GestureInterpreter(touchPath, viewPose) → MoveIntent?` in `:core:domain`, unit-tested over pose × direction fixtures. Below the confidence threshold it returns nothing.
- **View Rotation** stays local to the renderer and is never a `SessionEvent`. A Cube Rotation is a real Move and goes through the reducer.
- **Sticker appearance:** the one `StickerAppearance(Color) → {fill, glyph, pattern}` table in `:core:domain` drives the 3D stickers, matching the 2D net.
- **Screen readers:** the cube's semantics description is derived from `CubeState` and the current predicate.
- **Playback:** Demonstrations and hint arrows come through the pure `PlaybackController` via `CubeRenderer`, which is never checkpointed.
- **Frame-timing measurement:** Macrobenchmark `FrameTimingMetric` and `FrameMetrics` do not see `GLSurfaceView` frames. Use an in-app per-frame log of `onDrawFrame` intervals on the GL thread, optionally traced for `TraceSectionMetric`. It covers View Rotation as well as face turns.
- Home frame: WHITE on U, GREEN on F, RED on R. `Facelets` are 54 values in URFDLB order, row-major per the Kociemba net.

## UX & Interaction Patterns

- **Gestures:**
  - A one-finger drag that starts on a sticker and runs along a row or column turns that layer.
  - A one-finger drag that starts off the cube, or a two-finger drag anywhere, is a View Rotation, with no label.
  - Below the threshold, nothing happens; it never guesses a turn.
  - A two-finger twist is z. x and y come from the ⟳ keys only, since gestures for them are deferred.
  - Double-tap on the stage resets the view.
- **Cube Rotation:** a 200 ms turn, after which the frame label briefly shows the new front color.
- **Haptics:** a light tick on each committed Move and nothing on a mistake. It can be switched off in Settings.
- **Cube stage:** the `surface-stage` well, with the frame label VIRTUAL / EXPECTED / VERIFIED and a reset-view affordance top-right.
- **Arrow and highlight:**
  - A curved violet `sticker-highlight` (#A797FF) arrow follows the turning layer, with the Move in `notation` at its tip.
  - A highlighted piece gets a 3dp `sticker-highlight` outline with a 1dp `cubie-body` inner stroke, about 7:1 against the body.
  - Stickers are never recolored.
- **Dimming:** in *Guide me*, off-step pieces get `sticker-dimmed-overlay` (#00000099), and Keep pieces stay lit.
- **Reduce motion** (system setting or app override): arrows appear without animating, Demonstrations step instead of animating, and camera transitions snap.
- **TalkBack:**
  - Focusing the cube reads a summary such as "White cross solved; 2 of 4 first-layer corners placed", with a *Read faces* action that reads face by face.
  - Focus order is frame label → cube description → Move key bar → lesson panel → Teaching Mode switcher.
  - Every Certainty change is announced.
  - Move keys announce plain names.
- **Banned:** shake on error and red flashes for mistakes.

## Cross-Story Dependencies

- **5.1 comes first:** 5.2–5.5 all build on the 3D adapter.
  - 5.2 needs its picking.
  - 5.3 needs its camera and view pose, which 5.2's `GestureInterpreter` also consumes.
  - 5.4 needs per-sticker highlight and dim support.
- **Story 1.11** (blocked on reference hardware) confirms the OpenGL ES p90 on physical devices, then deletes the SceneView spike source sets, the `rubikxplorer.spike3d.sceneview` switch and the `sceneview` catalog entry. Epic 5 must not depend on SceneView code.
- **Story 2.3's randomized sync suite** is the oracle for 5.1's instrumented test.
- **Upstream pieces 5.4 reuses:** Epic 2 Demonstrations and step cards, Epic 4 Hint Ladder rung 2 (highlighted target or Move arrow), and *Guide me* Keep pieces (`preserve`).
- **5.5** uses the Epic 2/4 predicates and `ObjectiveProgress` for the summary, and the Epic 4 Certainty for announcements.
- **Later reuse:** Epic 9's Library and Epic 3's Solution Playback, through `PlaybackController`, and Physical-cube Mode (Epic 8), where View Rotation changes neither Notation nor the Expected State.
