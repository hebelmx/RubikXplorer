---
title: 'GH #415: Continuous Quick Scan stacks in any portrait window and reads the full camera frame, with a "Move the cube into the frame." cue'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: 'f4114a41bbc3f877a86e862543b634f13a40c4ba'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-27-quickscan-framing-crop.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Continuous Quick Scan now always binds a 1280x960 analysis stream, where panes at most 4:3 (for example the Lenovo in landscape) used 640x480 before.
    evidence: |-
      FULL_FRAME_ANALYSIS in ScanFrameSource.kt is returned by analysisTarget(..., continuous = true). The model input (256x256) and the sampler's cost do not scale with the frame size, but camera bandwidth and analyzer latency on the Lenovo TB128XU are unmeasured. Settled by the PO's #415 device check (Lenovo portrait and landscape, S24 FE) with the #406 per-stage timings; if slower, 640x480 is the alternative.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt (analysisTarget, FULL_FRAME_ANALYSIS)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The continuous Quick Scan uses the Guided `scanTwoPane` rule, so on the Lenovo in portrait (600 dp) the camera pane is about 3:1 tall, and its detector reads only the preview viewport crop, so the pane shape decides what the detector sees (#410, #414). The PC spike it was ported from reads the whole frame (#413).

**Approach:** Give `ContinuousQuickScanScreen` its own rule (stacked in any portrait window with a near-4:3 camera pane, side by side in landscape). In continuous mode only, hand the detector, fitter and sampler the whole upright analysis frame, keep the viewport crop only to map points to the visible preview, pick the analysis resolution for the full frame, and add a camera-side framing cue "Move the cube into the frame." when the tracked faces are mostly outside the preview (SCP 2026-11-27 quickscan-framing-crop, sections 3.1, 3.2, 3.4, 5).

## Boundaries & Constraints

**Always:**
- Only Quick Scan changes: `scanTwoPane`, the Guided viewfinder, calibration and Case capture keep their layout and keep reading only the viewport crop (`cropRect`); their existing tests pass unchanged.
- Layout: portrait = height >= width (from the window size, AD-17). Stacked: camera on top at full width, then net, count, cue, actions (today's order in the panel). Phone (width < 600 dp) stacked layouts stay pixel-identical (camera height 0.34 x window height). From 600 dp in portrait the camera pane is never tall: height = min(0.75 x width, 0.45 x window height). Landscape (width > height): side by side as today, including the short-panel paired last row.
- "Mostly out of view": more than `MOSTLY_OUT_OF_VIEW_FRACTION = 0.5` of the summed area of all tracked quads this frame lies outside the visible preview (view fractions outside [0,1]^2); one named constant, one place.
- The framing cue shows after the condition holds on `FRAMING_CUE_FRAMES = 3` consecutive analysed frames and clears after 3 consecutive analysed frames without it (one named default). Skipped frames (detector skipped/unavailable, sampler failure) neither advance nor reset the count.
- Cue order on the one cue line: layer turned mid-scan; all 54 verified; "Bring the cube into view." (mapper: nothing tracked anywhere in the full frame); "Move the cube into the frame."; then the mapper's other cues in their existing order. Chosen in `app/src/camera/kotlin` (`ContinuousQuickScanFlow`); the cue line stays the single polite live region, so TalkBack announces it.
- A quad with no part inside the visible preview gets no outline (it may still record stickers: the mapper is fed as before). Outline and debug-overlay drawing is clipped to the camera pane.
- AD-9: base camera code (`app/src/camera/kotlin`) never references `dev.cubentia.app.camera.continuous.*`; only numbers/fixed words cross into `:core:domain`. AD-13: no frame is stored, logged or sent; nothing new reaches `ScanDiagnostics`.
- Copy in `en.json` only (`checkLearnerText`).

**Never:**
- Do not touch `ContinuousFaceletMapper`, `ContinuousScanCue` (`:core:domain`) or mapper.py parity.
- Do not build #407 (tracked-cube crop, "Move the cube closer"), do not change the net's 24 dp cell cap (#392), do not change the 0.5 px sampling offset (#438).
- Do not edit planning artifacts, `_bmad/`, `.claude/skills/`, `sprint-status.yaml`, or `EXPERIENCE.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Cube outside crop | Continuous mode, image 640x480, cropRect a narrow band, cube painted outside it | Detector gets YuvFrame bounds 0,0,640,480 (view crop = cropRect); peaks, fitted face and a tracked quad come back; quad view corners lie outside [0,1] | No error expected |
| Guided frame | Same image, Guided viewfinder / calibration / corner | Frame bounds = cropRect exactly as today | No error expected |
| Wholly off-screen quad | Overlay quad all of whose corners lie right of x=1 | No outline for it; stickers still in snapshot | No error expected |
| Mostly off-screen x3 | 3 analysed frames with >50% of quad area outside | Cue line "Move the cube into the frame." (not on frames 1-2) | No error expected |
| Back in view | Cue shown, then 3 analysed frames at <=50% outside (or no quads) | Mapper cue returns after the 3rd frame, not before | No error expected |
| Priority | Framing held while mapper cue is LayerTwistDetected / AllVerified / BringIntoView | The mapper cue shows | No error expected |
| Skipped frames | Framing held 2 frames, then a skipped frame, then 1 more | Cue shows on that next analysed frame | No error expected |
| Exactly half | outside fraction == 0.5 | Condition false | No error expected |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt` -- `BoxWithConstraints` at the end uses `scanTwoPane(maxWidth, maxHeight)` and `maxHeight * 0.34f` for the stacked camera; camera `Canvas` draws `state.outlines` and debug peaks/lattices at view fractions; cue `Text(strings.resolve(state.cue.toText()))` with `liveRegion` tag `quickScan:cue`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt:253` -- `scanTwoPane` (read-only; Guided and calibration keep it). `VIEWFINDER_HEIGHT_FRACTION = 0.45f` precedent.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanFlow.kt` -- `next(frame, nowMs)` builds outlines from `frame.viewOverlays`; `cue` = mapper cue (used by `pulsesSince`, `keptForGuided`; keep its meaning). Put framing state/constants here.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrame(snapshot, viewOverlays, debug)`; add an `analysed` flag (default true).
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt:33` (`YuvFrame`) and `:155` (`ImageProxy.asYuvFrame` uses `cropRect`) -- add visible-region fields defaulting to the frame bounds, and a full-frame builder for continuous mode.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `read(image)` continuous branch (~line 390) builds the YuvFrame and calls `engine.frameDone(... crop ...)`; `bind()` calls `analysisTarget(view.width, view.height)` (~line 308); `analysisTarget` at ~line 540 (DEFAULT 640x480 / LARGE 1280x960).
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelTransform.kt` -- `uprightToFrame`, `uprightToViewFraction` (crop used for both upright origin and view mapping), `decodePeaks` (calls `uprightToViewFraction`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ViewfinderGrid.kt:73` -- `frameToViewFraction` is linear (no clamping): points outside the crop give fractions outside [0,1].
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt:111` -- `decodePeaks(... cropLeft = frame.left ...)`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- overlay and debug lattice mapping via `uprightToViewFraction(... frame.left ...)`; `skippedFrame`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- `PipelineEngine.processFrame` builds `ContinuousFrame`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt` -- samples within frame bounds (full frame after the change; no edit expected).
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/ContinuousSessionWriter.kt:459` -- records the analyzer's "crop" geometry; it must describe the region the detector read.
- `core/domain/src/main/resources/content/strings/en.json:848` -- `msg.quick-scan.continuous.*` siblings.
- Tests: `app/src/testDebug/kotlin/dev/cubentia/app/camera/ContinuousQuickScanLargeFontTest.kt` (`fits(twoPane, pairedLastRow)`), `ContinuousQuickScanScreenTest.kt` (600x960 two-pane tests ~lines 322-470), `ContinuousQuickScanFlowTest.kt` (`testSnapshot`, `testHandoff` helpers), `ScanFrameSourcePolicyTest.kt` (`analysisTarget`), `session/QuickScanRecGeometryTest.kt` (real `CameraXFrameSource.read` -> real engine harness with fake `ImageProxy` and stub detector), `CameraXFrameSourceTest.kt` (Guided read), `continuous/StickerCenterModelTransformTest.kt`.
- `_bmad-output/implementation-artifacts/spec-10-32-continuous-quick-scan-live-viewfinder-growing-net-guided-fal.md:76` -- the AD-17 layout sentence to update.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- give `YuvFrame` `viewLeft/viewTop/viewRight/viewBottom` (default = its bounds) and add a builder that reads the whole image (bounds 0,0,width,height) with the view region = `cropRect`; `asYuvFrame()` unchanged -- Guided keeps the crop.
- `app/src/camera/continuous/kotlin/.../StickerCenterModelTransform.kt` -- `uprightToViewFraction` and `decodePeaks` take view-region params defaulting to the crop: upright -> raw via the read bounds, raw -> view via the view region -- existing callers/tests unchanged.
- `app/src/camera/continuous/kotlin/.../LiteRtStickerCenterDetector.kt`, `ContinuousScanPipeline.kt` -- pass `frame.view*` as the view region; mark skipped frames (`ContinuousPipelineFrame.skipped`) -- peaks/outlines land on the visible preview.
- `app/src/camera/continuous/kotlin/.../ContinuousScanEngineModule.kt` -- carry `analysed = !skipped` into `ContinuousFrame`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrame.analysed: Boolean = true`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- continuous branch reads the full frame and passes the read bounds to `frameDone`; `analysisTarget` gains a continuous flag returning a named full-frame target (1280x960, independent of the pane) -- (b).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanFlow.kt` -- polygon clip/area helpers on [0,1]^2; skip outlines with zero visible area; framing counters and constants; `cueText: Text` applying the cue order; `cue` keeps the mapper cue.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt` -- own portrait/landscape rule and camera height rule; cue line shows `state.cueText`; clip the camera Canvas; skip debug peaks/cells outside [0,1].
- `core/domain/src/main/resources/content/strings/en.json` -- `"msg.quick-scan.continuous.move-into-frame": "Move the cube into the frame."`.
- Tests -- update `ContinuousQuickScanLargeFontTest` (600x960 at 200% and 599x960 stack; add a squarish portrait such as 800x800 and assert camera height <= width for every stacked case), the 600x960 tests in `ContinuousQuickScanScreenTest` (stacked: preview spans the width on top), `ScanFrameSourcePolicyTest`, `QuickScanRecGeometryTest` (recorded crop = read bounds); add a JVM test that drives a fake `ImageProxy` with a painted face outside `cropRect` through `CameraXFrameSource.read` in continuous mode into the real engine with a stub detector that finds bright blobs only within the frame bounds it is given, and gets peaks, a fitted face and a tracked quad; add a Guided-read test that the YuvFrame bounds equal `cropRect`; flow tests for every I/O-matrix row; a transform test for the view-region mapping at four rotations; a screen test that the cue line shows the new text as a polite live region.
- `_bmad-output/implementation-artifacts/spec-10-32-...-guided-fal.md` -- rewrite the AD-17 bullet to the new rule, citing SCP 2026-11-27 quickscan-framing-crop and #415.

**Acceptance Criteria:**
- Given a 600x960 dp portrait window at 200% font, when Quick Scan shows, then camera is on top at full width with height <= width, every control is on screen below it, and *Close* and the torch do not overlap; landscape windows stay side by side and phone portrait bounds are unchanged.
- Given continuous mode, when a frame whose cube lies outside the viewport crop is read, then peaks and faces come back for it; given Guided, calibration or corner mode, the reader still gets exactly `cropRect`.
- Given the cube mostly out of view for 3 analysed frames, when the cue line is drawn, then it reads "Move the cube into the frame." and is a polite live region, and a wholly off-screen face has no outline.
- Given any frame, then no frame data is stored and `ScanDiagnostics` receives nothing new.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 17 findings — high 0, medium 0, low 4, false 12, maybe-false 1
- findings:
  - `[false]` `[reject]` (blind) Detector-unavailable and sampler-failure frames may not be marked unanalysed — every non-read path in `ContinuousScanPipeline.analyse` (unavailable before/after detect, `detection.skipped`, sampler exception, bad bounds) returns `skippedFrame()`, which sets `skipped = true`.
  - `[false]` `[reject]` (blind) Debounce does not restart when the condition flips mid-run — `next()` resets `framingRun` to 0 on any frame whose reading equals `framingShown`, so out,out,in,out ends with run 1 and no cue.
  - `[low]` `[reject]` (blind) NaN quad corners give NaN area and suppress the cue — tracked quads come from fitted faces the sampler already checks with `quad.isFinite()`; unlikely in use and the fix adds guards.
  - `[false]` `[reject]` (blind) `twistDetected` check may keep the framing cue suppressed beyond the stated priority — a latched twist is "layer turned mid-scan", priority 1 in the intent; suppressing the framing cue then is the required order.
  - `[low]` `[reject]` (blind) Very short portrait windows are not defined — below 600 dp wide nothing changed (0.34 x height as before); from 600 dp the 0.45 x height cap matches the Guided viewfinder, and 800x800 is covered by the fit test.
  - `[false]` `[reject]` (blind) Tests do not assert the bottom action is on screen — `ContinuousQuickScanLargeFontTest.fits` asserts every tag, the last action included, lies within the root bounds.
  - `[false]` `[reject]` (blind) Summed area double-counts overlapping quads — tracked quads are distinct cube faces, whose projections do not overlap; the intent defines the measure as the tracked faces' total quad area.
  - `[low]` `[reject]` (blind) End-to-end full-frame test runs rotation 0 only — the view-region mapping is tested at all four rotations and the fill/sampler paths are unchanged per rotation; adding more end-to-end cases is not a direct correction.
  - `[false]` `[reject]` (blind) Calibration and corner modes are not proven crop-only through their real paths — those branches of `CameraXFrameSource.read` are unchanged and still call `asYuvFrame()` / `image.cropRect`; `onlyTheContinuousReadReadsTheWholeFrame` checks the bounds each mode receives.
  - `[false]` `[reject]` (blind) Off-screen face's stickers not shown to survive hand-off — the flow never filters snapshot or export data; only outlines are dropped, and the test asserts the net is unchanged.
  - `[false]` `[reject]` (intent) Panel order is net, count, cue, actions, not "cue, count, net and actions" — "phone layouts unchanged" fixes the shared panel's order, and the SCP's own landscape wording ("net, count, cue and actions on the right") shows these lists are enumerations, not orderings.
  - `[low]` `[reject]` (intent) An 800x800 portrait window gets an 800x360 strip, not near-4:3 — rare (square windows of 600 dp or more); "never tall" holds, and a 4:3 pane there would leave too little panel at 200% font; real tablets in portrait (600x960, 800x1280) get about 1.39:1.
  - `[maybe-false]` `[defer]` (intent) Continuous mode always asks for 1280x960, about four times the pixels where 640x480 was used before (panes at most 4:3, such as Lenovo landscape) — the model input and sampler cost do not scale with pixels, but camera bandwidth and latency on the Lenovo are unmeasured; settled by the PO's device check with the #406 timings. Deferred as medium (unverified).
  - `[false]` `[reject]` (intent) Full-frame test uses a stub detector — the real LiteRT detector's view-region wiring is covered by the `decodePeaks` and transform tests; the stub is the harness the intent's "JVM test" needs.
  - `[false]` `[reject]` (intent) Recorded session geometry now holds the read bounds, not the viewport crop — `ContinuousSessionWriter.track` documents the crop as "the analyzer's" region the detector read, and its upright size comes from the detector's params; recording the viewport crop would describe a region the detector no longer reads.
  - `[false]` `[reject]` (intent) TalkBack announcement not tested on a device — the screen test asserts the cue node shows the new text as a polite live region, the JVM-level form the intent asks for.
  - `[false]` `[reject]` (intent) Outlines are clipped by drawing, debug marks by dropping — both are clipping overlay points outside the visible area, as the intent asks.

## Design Notes

View mapping for a full-frame read (golden): `(fx, fy) = uprightToFrame(ux, uy, 0, 0, W, H, rot)`, then `ViewfinderGrid.frameToViewFraction(fx, fy, viewW, viewH, crop.left, crop.top, crop.right, crop.bottom, rot)`. With view region == read bounds this is today's function exactly.

Resolution: 1280x960 keeps the S24 FE phone-portrait size that worked (its 0.34-height pane was > 4:3, so it got LARGE) and leaves source pixels for the later #407 crop; the model input stays 256x256. The target is chosen at bind time from the mode then set.

Outside fraction: compute in view-fraction space (an affine image of pixel space, so area ratios hold): `outside = 1 - sum(area(clip(q))) / sum(area(q))` over this frame's `viewOverlays`; no quads or zero total area -> condition false.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-415 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew --no-daemon spotlessCheck build` -- expected: BUILD SUCCESSFUL (includes `checkLearnerText`, JVM/Robolectric tests, boundary checks).

## Auto Run Result

Status: done

**Summary.** The continuous Quick Scan stacks like a phone in any portrait window, the Lenovo's 600 dp included, and stays side by side in landscape. In continuous mode the detector, lattice fitter and sampler now read the whole analysis frame instead of the preview viewport crop; the crop is kept only to map points onto the visible preview. The analysis stream is a fixed 1280x960, independent of the pane. A camera-side cue "Move the cube into the frame." shows when more than half of the tracked faces' area is off the preview for 3 analysed frames, and clears after 3 frames without it. Guided 6-face Scan, calibration and Case capture still read only the crop, and their layout (`scanTwoPane`) is unchanged. `ContinuousFaceletMapper`, `ContinuousScanCue` and mapper.py parity are untouched. The 0.5 px sampling offset (#438) and the 24 dp net cell cap (#392) are left alone; none of #407 is built.

**Files changed**
- `app/src/camera/kotlin/.../FrameReader.kt` -- `YuvFrame` view region (defaults to the read bounds); `asFullYuvFrame()` for continuous mode.
- `app/src/camera/kotlin/.../ScanFrameSource.kt` -- continuous read of the full frame; the recording tap gets the read bounds; `analysisTarget(..., continuous)` returns `FULL_FRAME_ANALYSIS` (1280x960).
- `app/src/camera/kotlin/.../ContinuousScanEngine.kt` -- `ContinuousFrame.analysed`.
- `app/src/camera/kotlin/.../ContinuousQuickScanFlow.kt` -- `ViewClip` polygon helpers; no outline for wholly off-screen quads; framing debounce; `cueText` with the cue order; named constants `MOSTLY_OUT_OF_VIEW_FRACTION` and `FRAMING_CUE_FRAMES`.
- `app/src/camera/kotlin/.../ContinuousQuickScanScreen.kt` -- `quickScanSideBySide`, `quickScanCameraHeight`, a clipped camera Canvas, and the cue line showing `cueText`.
- `app/src/camera/kotlin/.../GuidedScanScreen.kt` -- `scanTwoPane` KDoc only.
- `app/src/camera/continuous/kotlin/.../StickerCenterModelTransform.kt`, `LiteRtStickerCenterDetector.kt`, `ContinuousScanPipeline.kt`, `ContinuousScanEngineModule.kt` -- view-region mapping and the skipped flag.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.quick-scan.continuous.move-into-frame`.
- Tests:
  - New: `ContinuousFullFrameReadTest`.
  - Updated: `CameraXFrameSourceTest`, `ContinuousQuickScanFlowTest`, `ContinuousQuickScanLargeFontTest`, `ContinuousQuickScanScreenTest`, `ScanFrameSourcePolicyTest`, `StickerCenterModelTransformTest` and `QuickScanRecGeometryTest`.
- `_bmad-output/implementation-artifacts/spec-10-32-...-guided-fal.md` -- the AD-17 layout sentence.

**Review findings:** 17 findings (blind hunter 10, intent alignment 7, edge-case hunter 0, verification gap 0). 0 patches. 1 deferred: the 1280x960 analysis cost on the Lenovo (medium, unverified). 16 rejected, each with its reason in the Review Triage Log above.

**Follow-up review recommended:** false (no patched entries: 0 high, 0 medium, 0 low).

**Verification:** `./gradlew --no-daemon spotlessCheck build` passed (BUILD SUCCESSFUL in 3m 4s). The new and updated test classes ran with 0 failures: FlowTest 19, FullFrameReadTest 1, CameraXFrameSourceTest 6, ScreenTest 17, LargeFontTest 10. Every I/O-matrix row has a passing flow or source test.

**Residual risks**
- Device behaviour is unmeasured on the Lenovo and the S24 FE: recognition with the full frame, time to 6/6, and the 1280x960 cost. The PO's device check covers this.
- The detector input no longer depends on the layout. A Learner may see stickers recorded for a face that is off the preview.
