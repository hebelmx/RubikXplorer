---
title: 'GH-139 Larger virtual Lesson cube on compact phones (tablet Learn tab unchanged)'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: 'b25c7438815493fd75d568e52fad47b947758399'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred:
  - summary: >-
      Device check: confirm the GL-rendered Lesson cube redraws at 34 degrees on a settled cube and stays fully visible on the Galaxy S24 FE (normal and 200% font), with teaching arrow and face labels aligned.
    evidence: |-
      Code is wired (LaunchedEffect(orbit, fovDegrees) requests a render; draw() uploads viewProjection(), which keys on eye + FOV) and JVM tests cover the handoff, but no rasterized frame is inspected; Robolectric cannot render GL. Planned for the next PO device session.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt:510, app/src/main/kotlin/dev/cubentia/app/render/gl/CubeGlRenderer.kt:107
    severity: low
---

<intent-contract>

## Intent

**Problem:** On a compact phone in virtual mode the Lesson stage's cube is drawn far smaller than in physical mode (about 20% vs 40% of the width on a Galaxy S24 FE): the stage (~45% of the height, min 280dp, UX-DR23) also holds the header (frame label + toolbar) and the 2-row Move key bar, so the cube canvas is a short, wide strip and the camera (FOV 38°, fit to the narrower side) leaves extra margin around the cube.

**Approach:** Product-owner decision (A), 2026-11-19: grow only the cube inside the unchanged stage. In COMPACT width + virtual mode (Guide me / Test me / Let me try Learner stage): (1) zoom the camera to fit the cube's bounding sphere (FOV 34° instead of 38°), keeping picking, arrows and face labels on the same pose; (2) trim the Learner stage's vertical inner padding from `Spacing.s2` to `Spacing.s1`. Decision (B): the 10-inch tablet Learn tab stays a single-column Stage list for v1.0 — no layout change; record this in the PR description.

## Boundaries & Constraints

**Always:** Stage height formula (`COMPACT_STAGE_FRACTION`, `COMPACT_STAGE_MIN`, gutter) unchanged; Move key bar stays at the stage's bottom edge with its rows/sizes unchanged; header (frame label, chips, View rotated badge, caption, toolbar) unchanged. Every `ViewPose` the renderer builds (GL projection, gesture picking, arrow overlay, face labels) uses the renderer's current FOV so touches still hit the drawn cube. The cube's bounding sphere (radius `(1 + STICKER_LIFT)·√3` ≈ 2.55, at the orbit radius) stays fully inside the cube canvas at FOV 34°, so turns and view rotations never clip.

**Never:** No change in physical mode (any sync state other than `VIRTUAL`), MEDIUM/EXPANDED layouts, Show me's `DemonstrationPlayer`, Free cube, Library, Solve playback, `MoveKeyBar`, stage proportions, Learn tab / Stage list, planning artifacts, Learner text or feature flags. No change to `ViewPose`'s default 38° FOV.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Compact virtual | 411x891dp, Let me try / Guide me, font 1.0 and 2.0 | Renderer FOV 34°; `lesson:stage` height unchanged (360dp in the test harness); projected cube silhouette ≥ 1.15× its 38° height at the same canvas (baseline ≈136dp → ≥156dp) and inside `cube3d`; `cube3d` overlaps no Move key, toolbar button or header badge | No error expected |
| Compact physical | same, `ModeSwitched(PHYSICAL)` | FOV 38°; `cube3d` height as before (275dp at font 1.0) | No error expected |
| Medium/expanded | w600dp+ virtual | FOV 38°, layout unchanged | No error expected |
| Mode flip | virtual → physical → virtual on compact | FOV follows (34 → 38 → 34) | No error expected |
| Drag on zoomed cube | compact virtual layer-turn drag | Commits the same Move as the Move key (picking uses 34°) | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `stage` lambda (~:805) builds `LearnerStage`; layout `BoxWithConstraints` (~:935) picks `WindowWidthClass` (COMPACT column with `stageHeight`, MEDIUM/EXPANDED row). `LearnerStage` (~:1205) → `StageColumn(header, body)` (~:1353, `padding(Spacing.s2)` around a custom `Layout`; body min `STAGE_BODY_MIN`) → `CubeAndKeys` (~:1393). Constants at ~:1507 (`COMPACT_STAGE_FRACTION`, `COMPACT_STAGE_MIN`, `CUBE_MIN`). The Lesson's `Cube3DRenderer` is `remember(lesson.id)` at ~:566.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- `class Cube3DRenderer` (:116, `orbit` state); `Content` (:359) sets `renderer.eye = orbit.eye()` (:495) on the GL renderer; `ViewPose(...)` built at :605 (face labels), :645 (arrow Canvas), :844 (`FaceLabelOverlay`, takes `orbit`). Semantics keys (`RenderedOrbitKey` etc., ~:100-115) set on the `cube3d` node (:615).
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- `ViewPose(size.width, size.height, baseOrbit.eye())` at :62 and :89 for picking; has the renderer in scope.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubeGlRenderer.kt` -- `@Volatile var eye`; `camera = ViewProjectionCache()`.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/ViewProjectionCache.kt` -- caches `ViewPose(width, height, eye).viewProjection()` keyed on eye only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/input/ViewPose.kt` -- `fovDegrees: Double = 38.0` param already exists ("FOV spans the narrower viewport side"); read-only except optionally naming the default as a companion constant.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeGeometry.kt` -- `STICKER_LIFT = 0.472f` (cube extent 1.472 per axis).
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt` -- Robolectric, class config `w411dp-h891dp`; `open(lessonId, fontScale=)`, `switchTo(mode)`, `node(tag)`; physical switch via `host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }`. Drag tests at ~:831, :868, :908, :1703 build `ViewPose(..., ViewOrbit.DEFAULT.eye())` with the default FOV -- must use the rendered FOV once compact virtual zooms.
- Measured baseline (harness, 411x891, font 1.0, Guide me): virtual `lesson:stage` 395x360 @y64, `cube3d` 379x176, `moveKey:U` top 304, `toolbar:undo` 48dp at y72; physical `cube3d` 379x275.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/render/gl/ViewProjectionCache.kt`, `gl/CubeGlRenderer.kt` -- add a `@Volatile var fovDegrees` (default 38.0) on `CubeGlRenderer`; cache keys on eye + fov and builds `ViewPose(width, height, eye, fov)`.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- add `var fovDegrees by mutableStateOf(38.0)` and an internal `pose(width, height, eye = orbit.eye())` helper; route every `ViewPose` construction (incl. `FaceLabelOverlay`, via a param) through it; push it to the GL renderer beside `eye` and request a render on change; expose a `RenderedFovKey` semantics value on `cube3d`.
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- build picking poses with the renderer's FOV.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- derive `compactVirtual` = COMPACT width class && `syncState == VIRTUAL`; set `renderer.fovDegrees` to `COMPACT_VIRTUAL_FOV = 34.0` when true else 38.0 (SideEffect, so it follows mode/size changes); pass a flag to `LearnerStage`/`StageColumn` that uses vertical padding `Spacing.s1` instead of `Spacing.s2` when true. Document both constants with UX-DR23 / #139.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt` -- make the drag helpers use the `RenderedFovKey` value; add tests covering the I/O matrix: compact virtual at font 1.0 and 2.0 (stage height unchanged, FOV 34, silhouette of the 8 extent corners and the bounding sphere inside `cube3d`, silhouette height ≥ 156dp, no overlap of `cube3d` with `moveKey:*` visible keys, `toolbar:*` or `stage:viewRotated`), physical (FOV 38, `cube3d` height unchanged), a `w600dp-h960dp` medium case (FOV 38), and virtual→physical→virtual flips.

**Acceptance Criteria:**
- Given a 411x891dp phone in virtual mode at normal or 200% font, when a Lesson's Learner stage shows, then the cube is visibly larger (≥ 1.15× linear) inside the unchanged ~45% stage, fully visible, overlapping neither the Move key bar nor the header badges/toolbar.
- Given physical mode or a medium/expanded window, when the Lesson shows, then stage proportions, cube size and layout are as before.
- Given a compact virtual Lesson, when the Learner drags a layer on the larger cube, then the same Move commits as before.
- Given the PR, then its description records decision (B): the tablet Learn tab stays single-column for v1.0, no layout change.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 17 findings — high 0, medium 3, low 6, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind-hunter) Existing drags start deep inside the front face, so they pass even if picking fell back to 38° — patched: added a compact virtual drag starting near the silhouette edge that is on the cube at 34° and off it at 38°, mutation-checked against a 38° picking pose.
  - `[medium]` `[patch]` (blind-hunter) Lesson tests read FOV from semantics and the cache test passes FOV directly, so a stale GL FOV handoff would go unseen — grouped with verification-gap GL row; patched: `CubeGlRenderer` exposes the matrix `draw()` uses, with JVM tests for it and for `Cube3DRenderer.Content` pushing 34°/38° to the GL renderer.
  - `[false]` `[reject]` (blind-hunter) Baseline built by subtracting 8dp may not match the old layout — pre-change measurement at 411x891 (font 1.0 and 2.0) gave a 176dp canvas; the new canvas is 184dp, so the reconstruction equals the measured baseline, and an absolute ≥156dp floor is also asserted.
  - `[false]` `[reject]` (blind-hunter) Large-font canvas may extend past the scrolling stage viewport; silhouette not re-checked after rotation — at 411x891 font 2.0 the stage does not scroll (measured canvas 176dp before, same as font 1.0), and the bounding-sphere assertion is orbit-independent.
  - `[low]` `[reject]` (blind-hunter) No test resizes an active Lesson across the compact/medium breakpoint — FOV and padding are derived each composition from the width class (SideEffect); live breakpoint crossing mid-lesson is rare and the fix is a new resize harness.
  - `[low]` `[reject]` (edge-case-hunter) An active drag surviving a resize across the breakpoint picks with the old pose — needs a mid-gesture window resize; fix adds pointerInput keys/branches for a near-unreachable case.
  - `[false]` `[reject]` (edge-case-hunter) Non-finite or out-of-range FOV breaks projection — the only writer is LessonScreen, which assigns 34.0 or 38.0.
  - `[false]` `[reject]` (edge-case-hunter) Baseline subtraction may understate the old canvas — same refutation as the blind-hunter baseline row (measured 176dp).
  - `[medium]` `[patch]` (verification-gap) Production GL frame's FOV unverified — grouped with the blind-hunter GL row; same patch.
  - `[low]` `[reject]` (verification-gap) Teaching-arrow Canvas placement at 34° unverified — both overlays call the same `pose()` helper whose FOV is now tested; a pixel screenshot of the arrow is a new harness for a regression-only risk.
  - `[low]` `[reject]` (verification-gap) Rotated face-label placement at 34° unverified — same reason as the arrow row; `FaceLabels.place` is pure and already tested.
  - `[false]` `[reject]` (intent-alignment) Growth falls short of parity with physical/Library (~40% width) — decision (A) fixes the header, Move key bar and stage, so parity is unreachable; the intent asks for "visibly larger", met at ≥1.15x (≈1.25x on the device's shorter stage).
  - `[false]` `[reject]` (intent-alignment) Tests check a projection model, not rendered pixels — Robolectric cannot rasterize GL; the model is the same `ViewPose` the GL matrix is built from, and the GL handoff is now tested (patch above).
  - `[false]` `[reject]` (intent-alignment) Overlap check only uses the canvas rectangle — the drawn cube is asserted inside the canvas (corners and bounding sphere), and the canvas is asserted clear of keys, toolbar and badge, so the drawn cube cannot overlap them.
  - `[low]` `[reject]` (intent-alignment) Each Learner mode tested at only one font scale — Guide me, Test me and Let me try share one `LearnerStage` layout; a cross-product adds runtime, not coverage.
  - `[low]` `[reject]` (intent-alignment) "Unchanged" assertions are fixed values, expanded width untested — the fixed values (360/351/275dp) are the pre-change measurements; expanded is excluded by the same `compactVirtual` predicate as medium.
  - `[false]` `[reject]` (intent-alignment) Renderer-wide FOV plumbing goes beyond "only the cube's size" — the plumbing is what keeps picking and overlays consistent with the zoom; no other screen sets a non-default FOV.

### 2026-11-19 — Review pass
- verdicts: 19 findings — high 0, medium 0, low 10, false 9, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind-hunter) Growth test uses a shorter baseline canvas, FOV alone gives ~1.126x — carried: pre-change measurement gave the 176dp canvas the reconstruction uses; an absolute >=156dp floor is also asserted.
  - `[false]` `[reject]` (blind-hunter) 200% font canvas may extend past the scrolling stage viewport; silhouette not re-checked after rotation — carried: the stage does not scroll at 411x891 font 2.0, and the bounding-sphere assertion is orbit-independent.
  - `[low]` `[defer]` (blind-hunter) GL tests stop at the FOV field and projection helper; a FOV-only change could fail to redraw — code requests a render on `fovDegrees` change (`LaunchedEffect(orbit, fovDegrees)`) and `draw()` uploads `viewProjection()`; only a rasterized frame check is missing, recorded as a deferred device check for the planned PO device session (grouped with the verification-gap GL row).
  - `[low]` `[reject]` (blind-hunter) Teaching-arrow and face-label alignment at 34° unverified — carried: both overlays call the same `pose()` helper whose FOV is tested; a pixel harness is new work for a regression-only risk.
  - `[low]` `[reject]` (blind-hunter) Medium test checks only FOV, expanded untested — carried: wide layouts take the same `compactVirtual` false branch for FOV and padding; adding geometry assertions is new coverage for a regression-only risk.
  - `[low]` `[reject]` (blind-hunter) Drag keeps the pointer-down pose across a live resize — carried: needs a mid-gesture window resize across the breakpoint; the fix adds cancellation branches for a near-unreachable case.
  - `[low]` `[reject]` (edge-case-hunter) Drag active while the window crosses the compact breakpoint — carried: same as the live-resize row.
  - `[false]` `[reject]` (edge-case-hunter) Non-finite or out-of-range FOV breaks projection — carried: the only writer (`LessonScreen.kt:943`) assigns 34.0 or 38.0.
  - `[low]` `[defer]` (verification-gap) FOV-only change on an idle cube not shown to produce a new 34° frame — grouped with the blind-hunter GL row; deferred device check.
  - `[low]` `[reject]` (verification-gap) Teaching-arrow Canvas placement at 34° unverified — carried: same reason as the arrow row above.
  - `[low]` `[reject]` (verification-gap) Rotated face-label placement at 34° unverified — carried: same reason; `FaceLabels.place` is pure and tested.
  - `[false]` `[reject]` (intent-alignment) "At the same canvas" vs the derived pre-change baseline — carried: the baseline equals the measured pre-change canvas, and the matrix's own figures (136 -> 156dp) only fit the before/after reading.
  - `[false]` `[reject]` (intent-alignment) Tests check the projection model, not rendered pixels — carried: Robolectric cannot rasterize GL; the GL matrix is the same `ViewPose`, and the handoff is tested; the pixel check is the deferred device check.
  - `[false]` `[reject]` (intent-alignment) Overlap uses the canvas rectangle; no-clip not checked during a layer turn — carried for the overlap; the bounding sphere contains every turning and rotated state, so the sphere assertion covers turns.
  - `[false]` `[reject]` (intent-alignment) Drag test proves picking at 34° but not that GL drew at 34° — GL `fovDegrees` is set in the same composition as picking's pose (`Cube3DRenderer.kt:509`), so the two cannot disagree; the handoff has its own test.
  - `[low]` `[reject]` (intent-alignment) Matrix coverage gaps (Test me, per-mode font scales, PHYSICAL_MISMATCH, expanded) — carried: all share one `LearnerStage` layout and the `== VIRTUAL` / COMPACT predicate; a cross-product adds runtime, not coverage.
  - `[false]` `[reject]` (intent-alignment) Lesson renderer holds 34° during Show me — Show me draws through `DemonstrationPlayer`'s own renderer at 38°; the Lesson renderer is not on screen.
  - `[low]` `[reject]` (intent-alignment) The 38.0 default is written in four places — no caller changes the default today and the spec forbids changing `ViewPose`'s 38°; a shared constant across modules is more than a direct correction for an unlikely divergence.
  - `[false]` `[reject]` (intent-alignment) Decision (B) is not in the diff — it is a PR-description deliverable, recorded in this spec's Auto Run Result for the PR author.

## Design Notes

FOV 34° comes from the bounding sphere: radius 1.472·√3 ≈ 2.55 at orbit radius |(4.2,4.6,6.4)| ≈ 8.93 subtends asin(2.55/8.93) ≈ 16.6°, so tan(17°) keeps the sphere (every turn and view rotation) inside the narrower side, filling ~97% of it vs ~87% at 38°. Gain ≈ tan(19°)/tan(17°) ≈ 1.126, plus ~8dp of padding; on the device's shorter stage the canvas grows ~8% and the cube ~25-30% in linear size. The header and key bar cannot shrink under decision (A), so this is the available headroom.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.lesson.*' --tests 'dev.cubentia.app.render.*'` -- expected: all pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** On compact phones in virtual mode the Lesson's Learner stage (Guide me / Test me / Let me try) now draws the cube with a 34° camera FOV (fit to the cube's bounding sphere) instead of 38°, and trims the stage's vertical inner padding from 8dp to 4dp. Stage height, header and Move key bar are unchanged; physical mode and medium/expanded widths keep 38° and 8dp. GL projection, gesture picking, teaching arrows and face labels all share the renderer's FOV. Decision (B): the 10-inch tablet Learn tab stays a single-column Stage list for v1.0 — no layout change (record in the PR description).

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `compactVirtual` predicate; sets `renderer.fovDegrees` (34/38) and the compact virtual stage padding.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- `fovDegrees` state, `pose()` helper for every `ViewPose`, `RenderedFovKey` semantics, FOV handoff to GL, `glFovDegrees` test accessor.
- `app/src/main/kotlin/dev/cubentia/app/render/CubeGestureHost.kt` -- picking uses the renderer's pose.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubeGlRenderer.kt`, `gl/ViewProjectionCache.kt` -- FOV-aware projection cache; `viewProjection()` used by `draw()`.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt` -- compact fit tests (font 1.0 and 2.0), physical/medium/mode-flip tests, edge-of-silhouette drag test; drag helpers use the rendered pose.
- `app/src/test/kotlin/dev/cubentia/app/render/Cube3DRendererTest.kt`, `gl/CubeGlRendererTest.kt`, `gl/ViewProjectionCacheTest.kt` -- FOV handoff and matrix tests.

**Review:** 17 findings; 2 patched entries (both medium: GL FOV handoff untested — grouped with verification-gap and blind-hunter rows; picking fallback to 38° undetected), 0 deferred, 15 rejected (reasons in the Review Triage Log). Patched counts by verdict: high 0, medium 2, low 0.

**Follow-up review recommended:** true — two medium entries were patched on this first pass. Named unverified risk: the GL-rendered zoom (and arrow/face-label overlay placement at 34°) is checked only through the shared projection model and JVM handoff tests; no rasterized frame is inspected, so a device screenshot on the Galaxy S24 FE (normal and large font) should confirm the larger cube is fully visible.

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.lesson.*' --tests 'dev.cubentia.app.render.*'` -- pass (LessonScreenTest 94/0 failures, new GL and renderer tests pass), before and after patches. `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL, before and after patches.

**Residual risks:** Growth is ≈1.17x linear in the test harness and ≈1.25x on the device's shorter stage — parity with physical mode is not reachable while the header and key bar stay fixed (decision A). Spec I/O row wording "at the same canvas" was interpreted as against the pre-change canvas (the measured baseline).

### Follow-up review pass (2026-11-19)

**Summary:** Fresh review of the done change (no code changes this pass). Compact virtual Lesson Learner stage uses FOV 34° and 4dp vertical padding; physical and wide layouts unchanged; GL, picking, arrows and face labels share the renderer's FOV.

**Files changed this pass:** spec only (triage log, deferred device check, this result).

**Review:** 19 findings — 0 patched, 1 deferred entry (2 rows: GL-rendered frame at 34° not rasterized in tests -> device check in the planned PO device session), 17 rejected (9 false, 8 low; reasons in the Review Triage Log; most carried from the first pass). Patched counts by verdict: high 0, medium 0, low 0.

**Follow-up review recommended:** false — follow-up pass patched no high finding; the work has converged.

**Verification:** No code changed this pass, so the first pass's results stand (`./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.lesson.*' --tests 'dev.cubentia.app.render.*'` pass; `./gradlew spotlessCheck build` BUILD SUCCESSFUL). Code inspection confirmed the FOV-keyed redraw (`Cube3DRenderer.kt:510`) and `draw()` uploading `viewProjection()` (`CubeGlRenderer.kt:107`).

**Residual risks:** GL-rendered size and overlay alignment are confirmed only by the shared projection model until the device session; parity with physical mode is out of reach under decision (A).
