---
title: 'Story 5.1: Render the cube in 3D behind the CubeRenderer port'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: '9cc623ca2952f50e2c53a0392c509d0bfc56ba8a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The 250 ms quarter turn is proven on the Compose frame clock and the 10,000-step sync suite on an API 29 emulator only, not on a reference device.
    evidence: |-
      No physical device was attached. TURN_MILLIS = 200 leaves 50 ms of frame latency budget; Story 1.11 re-measures GLES frame time on the reference devices and should also log one Animate's submit-to-settle wall time.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/render/gl/PresentationQueue.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The Learner only ever sees a flat 2D net; Epic 5 needs a real-looking 3D cube, and Story 1.8 chose custom OpenGL ES 3.0 (no dependency) as the engine. Its spike code is throwaway, flag-gated and never tested in CI.

**Approach:** Build a production 3D `CubeRenderer` adapter (pure cubie model + pure frame-driven presentation queue + pure mesh builder + a thin GLES 3.0 `GLSurfaceView` layer with a glyph atlas) in `:app` main, wire it into the free cube in place of the net, and prove it with JVM tests plus an instrumented port of the Story 2.3 randomized sync suite.

## Boundaries & Constraints

**Always:**
- The adapter implements `CubeRenderer` unchanged; `renderedFacelets()` is read back from the model's committed cubie geometry (rotated sticker normal → face, rotated position → row/col), never echoed from `PresentationItem.target`.
- The model animates every `Move` subtype in the Learner/viewer frame (+x R, +y U, +z F): `FaceTurn` (outer layer), `WideTurn` (outer + middle), `SliceTurn` (M like L, E like D, S like F, middle layer), `CubeRotation` (all layers; X like R, Y like U, Z like F). Committed rotations change only by exact integer quarter turns.
- Queue semantics match `Net2DRenderer`: FIFO, nothing dropped, fast-forward (duration ÷ 4) when ≥ 3 items wait behind the one starting; `Snap` resets the model to its target in the frame it is dequeued, with no animation. Every `Animate` item runs `TURN_MILLIS = 200` on the frame clock so it settles within 250 ms of submit including frame latency.
- The queue is advanced on the Compose frame clock (`withFrameNanos`, suspended when idle so `waitForIdle` returns); the GL thread only draws the latest immutable snapshot (27 model matrices + mesh version) and is driven by `requestRender()` (`RENDERMODE_WHEN_DIRTY`).
- Sticker fill, glyph and glyph ink come only from `StickerAppearance`; glyphs (when `RenderStyle.colorBlindGlyphs`) are drawn from a 6-letter texture atlas rendered once with IBM Plex Sans SemiBold through `Canvas`.
- GL lifecycle: forward `onPause`/`onResume` from the Compose `LifecycleOwner`; rebuild program, VBO and atlas texture in every `onSurfaceCreated` (context loss).
- Manifest declares `<uses-feature android:glEsVersion="0x00030000" android:required="true" />` (spine Stack row).
- A test hook semantics property on the 3D view (tag `cube3d`) exposes the 54 drawn stickers (fill, glyph or null, glyph ink or null) as built for the mesh, so Compose tests can assert what is drawn.

**Never:**
- No change to `CubeRenderer`, `PresentationItem`, `RenderStyle`, `:core:*`, `build-logic/`, `_bmad/`, `.claude/skills/`, or `_bmad-output/planning-artifacts/`.
- No new third-party runtime dependency; no `INTERNET`; never log Cube States or facelets (no logging at all in this story).
- No gestures, picking, View Rotation, Cube Rotation 200 ms re-framing label, arrows, highlights, dimming or TalkBack description (Stories 5.2–5.5). `LessonScreen`, `DemonstrationPlayer`, `PlaybackTransport`, `SolutionPlayback`, `HintLine`, editor and scan review keep `Net2DRenderer` (they need `NetEmphasis`, which 3D gains in 5.4).
- Do not edit or delete the spike source sets (Story 1.11 deletes them); production code must not import `dev.rubikxplorer.app.spike3d`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved readback | new model | `facelets()` == `CubeState.SOLVED.toFacelets()` | — |
| Every single Move | each of 54 Moves (6 faces, 6 wide, 3 slices, 3 rotations × 3 turns) from a scrambled state | `facelets()` == `state.apply(move).toFacelets()` | — |
| Mid-animation | an Animate at 50% progress | `renderedFacelets()` still the pre-turn state | — |
| Quarter turn timing | one Animate submitted, frame clock | not equal at +50 ms, equal to target by +250 ms | — |
| Snap | Snap after queued Animates | Animates play in order, Snap lands in its own frame, no animation | — |
| Burst of 8 | 8 Animates submitted at once | items with ≥ 3 behind run at 50 ms; every intermediate target drawn in order; none dropped | — |
| Random sequence | 200 random Moves (all subtypes) + Snaps, pinned seed | final `facelets()` == committed state | — |
| Glyphs | style on / off | sticker draws carry `StickerAppearance` glyph + ink when on, none when off; fills identical either way | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/rubikxplorer/app/render/CubeRenderer.kt`, `PresentationItem.kt`, `RenderStyle.kt` -- the port, items and style. Read-only (only `CubeRenderer` KDoc may mention the new adapter).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt` -- reference queue: `Channel` + `AtomicInteger` behind-count, `FAST_FORWARD_AFTER = 3`, `FAST_FORWARD_DIVISOR = 4`, `finally` completes the current item on cancellation. Mirror semantics; keep the class.
- `app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SpikeCubeModel.kt` -- port its design (integer rotation, `faceletIndex(x,y,z,nx,ny,nz)`, Rodrigues float + int rotation, `modelMatrix`) into main and generalize from `FaceTurn` to every `Move`. Spike stays untouched.
- `app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/GlesCubeRenderer.kt` -- GLES 3.0 reference: VBO layout, `face/box/quad` geometry, shaders, camera (eye 4.2/4.6/6.4, FOV 38° across the narrower side). Reuse ideas; add uv + ink + glyph-flag attributes and a sampler.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- `FaceTurn/WideTurn/SliceTurn/CubeRotation`, `Turn` (CW = clockwise seen from that face; slice/rotation follow the named face). Oracle: `CubeState.apply(...)`, `toFacelets()` (Learner frame).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearance.kt` -- fill/glyph/glyphInk, `CUBIE_BODY`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Type.kt` -- `PlexSans` font family (font resources under `app/src/main/res/font/`) for the atlas.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` -- `val renderer: Net2DRenderer` → the 3D adapter. `FreeCubeScreen.kt:130` calls `renderer.Content(style, modifier)`.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- reads `sticker:$i` / `StickerColorKey` / `StickerGlyphInkKey` (lines ~79, 205–236), Kociemba cell bounds (~297), `Net2DRenderer.FAST_FORWARD_AFTER` (~358), timing tests (~331–385). Retarget to the 3D hook.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/SessionRenderSyncTest.kt` + `TestLessons.kt` -- the Story 2.3 suite (10,000 seeded steps, seed 20261030) and its helpers (`whiteCrossLesson`, `shippedTutorConfig`, `allMoves`, `TickingClock`), all `internal`.
- `app/build.gradle.kts` -- add `testInstrumentationRunner`, androidTest deps, shared test source dir. `gradle/libs.versions.toml` -- add `androidx.test:runner` 1.7.0 (in local Gradle cache); `androidx-test-ext-junit` 1.3.0 already exists.
- Robolectric 4.17 ships `ShadowGLSurfaceView`; under Robolectric no surface is created, so the GL thread never initialises EGL — JVM Compose tests still drain the queue via the frame clock.
- Local emulator: AVD `spike3d_api29` (API 29 x86_64, KVM) under `~/Android/Sdk`; `ANDROID_HOME`/`JAVA_HOME` per AGENTS.md.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubieModel.kt` -- pure 27-cubie model: `reset(Facelets)`, `begin(Move)`, `setProgress`, `commit`, `facelets()`, `modelMatrix(i, out, off)`, sticker list per cubie -- exact geometry readback for all Move types.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/PresentationQueue.kt` -- pure FIFO driven by `onFrame(nanos)`: `TURN_MILLIS = 200`, `FAST_FORWARD_AFTER = 3`, `FAST_FORWARD_DIVISOR = 4`, Snap → `reset`, `isBusy`, `finishCurrent()` for cancellation -- testable without Android.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeMesh.kt` -- pure: `stickerDraws(model, style): List<StickerDraw>` (54, by facelet index) and `vertices(model, style): FloatArray` (body boxes in `CUBIE_BODY`, sticker quads with fill, ink, uv, glyph flag) -- one source for both GL and the test hook.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/GlyphAtlas.kt` -- 6-cell `Bitmap` of `W Y R O G B` in Plex Sans SemiBold (white on transparent) via `Canvas`; uv lookup by glyph.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt` -- `GLSurfaceView.Renderer`: program/VBO/texture (re)built in `onSurfaceCreated`, re-upload when mesh version changes, draw latest snapshot; fragment mixes fill and ink by atlas alpha × glyph flag.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` -- the `CubeRenderer` adapter (main-thread `submit`, Channel/`AtomicInteger` like Net2D or a lock) and `@Composable Content(style, modifier)`: frame loop, snapshot publication + `requestRender`, lifecycle forwarding, `testTag("cube3d")` + semantics hook `RenderedStickersKey`.
- `app/src/main/AndroidManifest.xml` -- add the GLES 3.0 `uses-feature`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` -- renderer becomes `Cube3DRenderer`; KDoc updated.
- `app/src/test/kotlin/dev/rubikxplorer/app/render/gl/CubieModelTest.kt`, `PresentationQueueTest.kt`, `CubeMeshTest.kt` -- JVM tests for every I/O matrix row (mesh test: every sticker draw's fill/glyph/ink equals `StickerAppearance.of(color)` for the read-back facelets, glyph fields null when off).
- `app/src/test/kotlin/dev/rubikxplorer/app/render/Cube3DRendererTest.kt` -- Robolectric: render a `Net2DRenderer` and a `Cube3DRenderer` in the same state with glyphs on and off; the 3D hook's fills and glyph inks equal the net's `StickerColorKey` / `StickerGlyphInkKey` per index.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- retarget sticker reads to the `cube3d` hook and constants to the 3D adapter; move the Kociemba-cell test to a new `app/src/test/kotlin/dev/rubikxplorer/app/render/Net2DRendererTest.kt` rendering the net directly; keep every other behavior asserted.
- `app/src/sharedTest/kotlin/dev/rubikxplorer/app/session/TestLessons.kt` -- moved (git mv) from `src/test`; `app/build.gradle.kts` adds `src/sharedTest/kotlin` to both `test` and `androidTest` source sets, `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`, and `androidTestImplementation` of compose BOM, `ui-test-junit4`, `androidx.test.ext:junit`, `androidx.test:runner`.
- `app/src/androidTest/kotlin/dev/rubikxplorer/app/render/Cube3DRenderSyncTest.kt` -- instrumented port of `SessionRenderSyncTest` (same seed, steps, event mix and assertions) against `Cube3DRenderer.Content` on a real `GLSurfaceView`; also asserts the GL renderer drew ≥ 1 frame.
- `docs/ARCHITECTURE.md` -- one line under rendering: 3D adapter location, free cube uses it, lessons keep the net until 5.4, how to run the instrumented suite.

**Acceptance Criteria:**
- Given the free cube showing the 3D adapter, when a Move key commits a quarter turn, then `renderedFacelets()` differs from the committed state 50 ms later and equals it by 250 ms on the frame clock, and Reset/Scramble (`Snap`) equal the committed state within one frame.
- Given 8 Move keys tapped with no idle in between, when the queue drains, then every intermediate committed state is drawn in order, items with ≥ 3 queued behind run at the fast-forward speed, and the final drawn state equals the committed state.
- Given `./gradlew :app:connectedDebugAndroidTest` on an attached device or emulator, when `Cube3DRenderSyncTest` runs, then `renderedFacelets()` equals the virtual state at every drained step of the 10,000-step seeded suite and GL drew frames.
- Given color-blind glyphs toggled on the free cube, when the 3D hook is read, then each sticker's fill, glyph and ink equal `StickerAppearance.of(color)` and match the 2D net for the same state; toggled off, no sticker carries a glyph.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes with the manifest/denylist gates and no new runtime dependency.

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 20 findings — high 0, medium 7, low 8, false 4, maybe-false 1
- findings:
  - `[low]` `[reject]` (blind) `renderedFacelets()` updates before GL draws the snapshot — true by at most one vsync; the port defines rendered state as the adapter's drawn geometry and GL catches up next frame; a GL acknowledgement handshake is more than a direct correction.
  - `[low]` `[reject]` (blind) latest-only snapshot can skip an intermediate pose if GL lags — every item spans ≥ 50 ms of frames so turns are still seen; committed state is never lost; buffering/ack adds machinery for a cosmetic gap.
  - `[low]` `[patch]` (blind) directional shading darkened fills/ink up to 50 %, so displayed colors drifted from `StickerAppearance` — shader light range narrowed to 0.8–1.0 (fill and ink shaded alike), keeping stickers close to their table colors.
  - `[medium]` `[patch]` (blind) `stickerDraws()` is computed separately from the vertex data, so wrong vertex colors/glyph flags pass every test — grouped with verification-gap #2 and intent #6; new `CubeMeshTest.vertexAttributesCarryFillInkGlyphCellAndFlag` decodes the VBO floats (body color, fill, ink, glyph flag, atlas cell) per read-back facelet.
  - `[medium]` `[patch]` (blind) `modelMatrix()` never tested — grouped with verification-gap #1 and intent #5; new `CubieModelTest.modelMatricesAnimateExactlyTheTurningLayer` checks, for all 54 Moves, unchanged at 0, only the expected layer moves at 0.5, and progress 1 equals the committed matrices.
  - `[low]` `[reject]` (blind) no test of pause/resume, context recreation or disposal mid-turn — code read: every `onSurfaceCreated` rebuilds program, VBO and texture and resets `builtVersion`; disposal commits the active item and keeps the rest queued; an instrumented lifecycle test is more than a direct correction.
  - `[medium]` `[patch]` (blind) GL clear color hard-coded light gray, a light box on the dark stage — `CubeGlRenderer.background` now takes `RubikTheme.tokens.surfaceStage` each composition.
  - `[low]` `[reject]` (blind) per-frame allocations (27 matrices, facelet readback, semantics list) — a few KB per frame with no measured jank; no named harm; optimisation without a measurement is not a direct correction.
  - `[low]` `[reject]` (blind) instrumented suite can exceed 10,000 steps on its last burst — identical to the Story 2.3 suite it ports, intentionally kept the same.
  - `[low]` `[reject]` (edge) GL slower than Compose overwrites completed snapshots — same root cause and reason as blind #2.
  - `[false]` `[reject]` (edge) Snap waits behind queued animations instead of landing within one frame — FIFO is required (I/O matrix "Snap" row; AD-4 never drops or reorders); "within one frame" applies once Snap is dequeued, as `reset snaps to solved within one frame` asserts.
  - `[medium]` `[patch]` (verification-gap) turn animation matrices not verified — see blind #5 (shared test).
  - `[medium]` `[patch]` (verification-gap) appearance tests do not read the vertex attributes GL consumes — see blind #4 (shared test).
  - `[maybe-false]` `[defer]` (intent) AC1 names 250 ms on the reference device; only the frame clock and emulator were measured — no reference device is attached; settle with Story 1.11's on-device GL frame log; recorded in `deferred`.
  - `[false]` `[reject]` (intent) lessons still render on the net — no 5.1 AC names lesson surfaces; they need `NetEmphasis` (dim/highlight), which 3D gains in Story 5.4; the free cube commits Moves through the 3D port.
  - `[false]` `[reject]` (intent) fast-forward threshold reading (≥ 3 behind vs ≥ 4) — matches `Net2DRenderer` and the Epic 2 suite: "more than 3 queued" counts the starting item.
  - `[low]` `[reject]` (intent) the 2.3 suite is ported as a copy rather than shared — sharing one test body across Robolectric and instrumented source sets needs a harness abstraction; fixtures are shared via `src/sharedTest`.
  - `[medium]` `[patch]` (intent) GL path checked only by a frame count — see blind #5; matrices handed to GL now asserted on the JVM; pixel readback stays out of scope.
  - `[medium]` `[patch]` (intent) glyph vertex data and atlas mapping untested — see blind #4; the new test also checks each glyph has its own in-range atlas cell.
  - `[false]` `[reject]` (intent) collateral test moves (Kociemba layout test to `Net2DRendererTest`, free-cube asserts via the 3D hook) — descriptive; behavior is still asserted on the surface that shows it.

### 2026-11-04 — Review pass (follow-up)
- verdicts: 22 findings — high 0, medium 5, low 7, false 9, maybe-false 1
- findings:
  - `[low]` `[reject]` (blind) `publish()` updates `drawn` before GL consumes the snapshot — carried: same claim as pass 1 blind #1; at most one vsync lead; a GL acknowledgement handshake is more than a direct correction.
  - `[low]` `[reject]` (blind) single-slot `snapshot` can be overwritten before GL draws an intermediate state — carried: pass 1 blind #2; every item spans ≥ 50 ms of frames, committed state never lost.
  - `[medium]` `[patch]` (blind) sync suite asserts GL only via `drawnFrames > 0`; per-step asserts read the CPU model — carried: pass 1 intent "GL path checked only by a frame count"; matrices handed to GL asserted on the JVM (`CubieModelTest.modelMatricesAnimateExactlyTheTurningLayer`), pixel readback still not present as that row records; not patched again.
  - `[medium]` `[patch]` (blind) no test of the rasterized glyph atlas or shader output — carried: pass 1 intent "glyph vertex data and atlas mapping untested"; vertex/atlas-cell decode test in `CubeMeshTest` stands; not patched again.
  - `[false]` `[reject]` (blind) GL program/VBO/texture never explicitly released — `GLSurfaceView` calls `onSurfaceCreated` only after creating a new EGL context, and without `setPreserveEGLContextOnPause` the old context (and every handle in it) is destroyed on pause/detach, so nothing leaks.
  - `[false]` `[reject]` (blind) shader compile/link failure throws on the GL thread — the shaders are fixed bundled resources and the manifest requires GLES 3.0; a loud failure on a state the program is not shown to reach is correct behavior.
  - `[low]` `[reject]` (blind) no automated pause/resume, context-recreation or disposal-mid-burst test — carried: pass 1 blind lifecycle row; code still rebuilds everything in `onSurfaceCreated` and commits the active item on dispose.
  - `[low]` `[reject]` (blind) no in-app `onDrawFrame` frame-time measurement despite the epic's measurement note — the intent says "no logging at all in this story"; the epic assigns the on-device p90 confirmation to Story 1.11; adding an opt-in measurement path is more than a direct correction.
  - `[low]` `[reject]` (blind) per-frame allocations and per-frame recomposition via `geometryRevision` — carried: pass 1 per-frame allocations row; no measured jank, no named harm.
  - `[low]` `[reject]` (edge) GL lag lets successive snapshots overwrite completed states — carried: pass 1 edge #1 / blind #2.
  - `[false]` `[reject]` (edge) Snap appended behind animations so Reset/Scramble is not visible within one frame — carried: pass 1 edge row; FIFO is required by the I/O matrix "Snap" row.
  - `[medium]` `[patch]` (verification-gap) GL output can regress (e.g. model-matrix offset `i * MAT4` → `0` in `CubeGlRenderer.onDrawFrame`) while the sync suite passes — carried: same claim and location as pass 1 intent "GL path checked only by a frame count"; code reads as that row describes; not patched again.
  - `[false]` `[reject]` (intent) adapter checks only one Move mid-turn — `renderedFacelets()` is `model.facelets()`, and `CubieModelTest` covers all 54 Moves at progress 0.5 and 1; no bad outcome at the adapter.
  - `[false]` `[reject]` (intent) "+50 ms not equal" checked only in `PresentationQueueTest` — `FreeCubeScreenTest` "one Move settles within 250 ms on the frame clock" asserts not-equal at +50 ms and equal at +250 ms through the adapter.
  - `[false]` `[reject]` (intent) burst-of-8 queue test counts a trailing Snap toward fast-forward — carried: pass 1 threshold row; counting every queued item matches `Net2DRenderer`.
  - `[false]` `[reject]` (intent) random-sequence model test calls `model.reset()` directly rather than queue Snaps — Snap-through-queue is asserted in `PresentationQueueTest` and by the reducer's Snaps in the 10,000-step instrumented suite.
  - `[medium]` `[patch]` (intent) `cube3d` hook derives from `stickerDraws`, not the VBO; glyph orientation unchecked — carried: pass 1 blind #4 vertex-attribute decode test; orientation belongs to the same pixel-readback gap.
  - `[medium]` `[patch]` (intent) GL layer (snapshot drawing, context-loss rebuild, pause/resume) covered only by `drawnFrames > 0` — carried: pass 1 intent frame-count row.
  - `[low]` `[reject]` (intent) instrumented suite is not run by CI — `.github/workflows/ci.yml` forbids `connected*` tasks by project rule; adding an emulator CI job is more than a direct correction; already recorded as a residual risk.
  - `[false]` `[reject]` (intent) Snap readback equals `item.target` by construction — the intent requires `Snap` to reset the model to its target; Animate readback still comes from geometry.
  - `[maybe-false]` `[defer]` (intent) 250 ms settle measured only on the paused Compose clock — carried: existing `deferred` entry (Story 1.11 on-device timing); not deferred again.
  - `[false]` `[reject]` (intent) hook uses `style` while the mesh uses `latestStyle` — `rememberUpdatedState(style)` makes them the same value in every composition; no divergence.

## Design Notes

Generic turn = (axis, layers, quarters) in the viewer frame. Clockwise as seen from the outward face is −90° about that face's outward axis (the spike's `FaceTurn` convention):

```
FaceTurn(f)  -> axis(f), layers {+1}        WideTurn(f) -> axis(f), layers {+1, 0}
SliceTurn(M) -> axis(L), {0}  E -> axis(D), {0}  S -> axis(F), {0}
CubeRotation X -> axis(R) Y -> axis(U) Z -> axis(F), layers {-1, 0, +1}
degrees = -90 * quarters(CW=1, HALF=2, CCW=-1)   layer test: dot(position, axis) in layers
```

The "every single Move" test against `CubeState.apply` is the guard for these signs. Reference-device timing (NFR-2 p90) is Story 1.11's job; this story proves 250 ms on the frame clock and runs on the emulator.

If `GLSurfaceView` cannot be constructed under Robolectric after all, add a `CompositionLocal` that JVM tests set to skip the `AndroidView` while the frame loop and hook stay active; record it as a deviation.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: passes (all JVM tests incl. new ones, lint, gates, APKs).
- `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*' --tests 'dev.rubikxplorer.app.session.*'` -- expected: pass.
- Boot `spike3d_api29` headless (`emulator -avd spike3d_api29 -no-window -gpu swiftshader_indirect -no-audio &`, wait for `sys.boot_completed`), then `./gradlew :app:connectedDebugAndroidTest` -- expected: `Cube3DRenderSyncTest` passes. If no emulator can boot, record "not run" with the reason.

**Manual checks (if no CLI):**
- `git diff --stat` shows nothing under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `build-logic/`, `app/src/spike3d*`, `core/`.

## Auto Run Result

Status: done

**Summary:** Production OpenGL ES 3.0 `CubeRenderer` adapter (`Cube3DRenderer`) in `:app` main: a pure 27-cubie model that animates and reads back every Move type from its own geometry, a pure FIFO presentation queue on the Compose frame clock (200 ms per item, ÷4 fast-forward with ≥ 3 behind, Snap in its own frame), a pure mesh builder shared by GL and a `cube3d` semantics hook, a Plex Sans glyph atlas, and a `GLSurfaceView` layer drawing immutable snapshots with lifecycle forwarding and context-loss rebuild. The free cube now shows the 3D cube; lessons and playback keep the net until Story 5.4.

**Files changed:**
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` — the adapter, frame loop, snapshot publication, lifecycle, test hook, themed stage background.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/gl/` — `CubieModel`, `PresentationQueue`, `CubeMesh`, `GlyphAtlas`, `CubeGlRenderer`.
- `app/src/main/res/raw/cube_vertex.glsl`, `cube_fragment.glsl` — shaders (subtle shading, atlas glyph mix).
- `app/src/main/AndroidManifest.xml` — GLES 3.0 `uses-feature`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` — renderer is `Cube3DRenderer`.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — instrumentation runner, androidTest deps (`androidx.test:runner` 1.7.0), `src/sharedTest` source dir.
- `app/src/sharedTest/.../session/TestLessons.kt` — moved unchanged from `src/test`.
- Tests: `render/gl/CubieModelTest`, `PresentationQueueTest`, `CubeMeshTest`, `render/Cube3DRendererTest`, `render/Net2DRendererTest` (Kociemba layout test moved here), updated `ui/freecube/FreeCubeScreenTest`, new instrumented `androidTest/.../render/Cube3DRenderSyncTest`.
- `docs/ARCHITECTURE.md` — one line on the 3D adapter and the instrumented suite.

**Review:** 20 findings (high 0, medium 7, low 8, false 4, maybe-false 1). Patched 4 entries: vertex-attribute verification (medium), model-matrix verification (medium), themed GL background (medium), narrower shading (low). Deferred 1: reference-device 250 ms timing (medium, unverified → Story 1.11). Rejected 11 rows with reasons in the triage log (4 false; 7 low: one-frame readback lead, latest-only snapshot (blind + edge), lifecycle test, per-frame allocations, step-count overshoot, suite copy vs share). Follow-up review recommended: true (patched: high 0, medium 3, low 1) — the GL output itself (dark-stage background, glyph legibility, shading) was never checked visually or by pixel readback; only its inputs are asserted.

**Verification:** `./gradlew spotlessCheck build` — pass (after patches). `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.freecube.*'` — pass (CubieModel 3, PresentationQueue 2, CubeMesh 2, Cube3DRenderer 2, Net2DRenderer 1, FreeCubeScreen 15). `./gradlew :app:connectedDebugAndroidTest` on AVD `spike3d_api29` (API 29, swiftshader, headless) — `Cube3DRenderSyncTest` 10,000 steps pass (116 s, after patches). Matrix audit: every I/O row covered by a passing test (solved/every Move/mid-animation/random: `CubieModelTest`; timing: `FreeCubeScreenTest` "one Move settles within 250 ms"; Snap and burst: `PresentationQueueTest` + `FreeCubeScreenTest` burst; glyphs: `CubeMeshTest`, `Cube3DRendererTest`, `FreeCubeScreenTest`).

**Residual risks:** emulator-only timing (deferred to Story 1.11); no pixel-level check of what GL draws; the instrumented suite is not run by CI (`build` does not run connected tests). Implementation ran on Codex; its sandbox had no KVM, so the emulator run and review patches were done by the build-auto runner.

### Follow-up review pass (2026-11-04)

**Summary:** Fresh four-layer review (blind, edge-case and verification-gap on Codex; intent alignment on Claude) of the full diff since `9cc623ca2952f50e2c53a0392c509d0bfc56ba8a`. No code changed in this pass.

**Review:** 22 findings (high 0, medium 5, low 7, false 9, maybe-false 1). Patches applied: none (all 5 medium rows are carried from pass 1's already-patched rows and are not patched again). Deferred: none new (the reference-device timing row is carried onto the existing `deferred` entry). Rejected: 16 rows with reasons in the triage log — 9 false (GL resource release, shader failure path, Snap FIFO, one-Move adapter mid-turn, +50 ms adapter assert, burst Snap counting, random-sequence reset path, Snap readback by construction, `style` vs `latestStyle`) and 7 low (one-frame readback lead, single-slot snapshot (blind + edge), lifecycle test, frame-time logging forbidden this story, per-frame allocations/recomposition, instrumented suite not in CI). Follow-up review recommended: false (patched: high 0, medium 0, low 0) — the work has converged.

**Verification:** no files outside this spec changed, so pass-1 results stand (`./gradlew spotlessCheck build` pass; targeted unit tests pass; `Cube3DRenderSyncTest` 10,000 steps pass on `spike3d_api29`). Reviewers confirmed existing JUnit XML reports for the renderer and mesh tests show passes.

**Residual risks:** unchanged — what GL draws is still not checked by pixel readback (a wrong model-matrix offset in `CubeGlRenderer.onDrawFrame` would pass every test); emulator-only timing (Story 1.11); instrumented suite not run by CI.
