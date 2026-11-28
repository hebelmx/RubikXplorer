---
title: 'GH #414: debug thumbnail of the exact 256x256 model input on the continuous Quick Scan'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: '5569cb1d085bbb3b48222f199c33c855d68f4b42'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred:
  - summary: 'Device check owed (issue Done-when 3): use the model-input thumbnail in the #413 A/B and the Story 10.33 device sessions to judge how big the stickers are when the model reads them.'
    evidence: 'No physical device or camera in the build session (PO clarification 2026-11-27); the JVM/Robolectric tests prove the bytes, the per-frame update and the drawing only. GH #414 stays open while this point is owed.'
    severity: 'low'
  - summary: 'Device check owed (issue Done-when 1, on-device half): on a debug build with the toggle on, the thumbnail visibly updates as the cube moves, with the peaks on the stickers.'
    evidence: 'No camera in the build session; a JVM test drives two different frames through the real detector code path and shows the thumbnail and its peaks change.'
    severity: 'low'
---

<intent-contract>

## Intent

**Problem:** The viewfinder shows the detector's region at full preview quality, but not what the model actually receives: that region shrunk to 256x256, letterboxed with black bars, after the app's own YUV to RGB conversion. A cube that looks large on screen can be small and aliased in the model input, and nobody can see it on the device.

**Approach:** Behind the same debug toggle as the #409 overlay (`QuickScanDebugView`), the pipeline copies the detector's last model input (the exact bytes passed to the interpreter, via the existing `StickerCenterDetector.copyModelInput`) into a pipeline-owned buffer, builds a 256x256 ARGB thumbnail with the decoded peaks in model-input pixels and an info line, and attaches it to the #409 debug overlay. The continuous screen draws it, unsmoothed, in a viewfinder corner.

## Boundaries & Constraints

**Always:** The thumbnail is built only when the pipeline's `debugView()` is true (`QuickScanDebugView.enabled`: const gate false in release, toggle off by default); otherwise `copyModelInput` is not called for it and no buffer is allocated (the debug buffer is allocated lazily on first use). Its pixels are the model-input bytes exactly: pixel i = `0xFF000000 | r<<16 | g<<8 | b` of bytes `3i..3i+2`, letterbox bars included. Peaks map upright px to model-input px as `x * detection.inputScale + inputPadLeft`, `y * inputScale + inputPadTop`. The info line is numbers and fixed words only (AD-13): `input=<resizedW>x<resizedH> crop=<cropW>x<cropH> spacing=<median nearest-peak distance in heatmap cells, 1 decimal, or ->`. Drawn on screen only, never stored, logged or exported (AD-13). Drawn with no filtering (nearest neighbour) so aliasing shows. The session recording's tap copy (Story 10.34) is unchanged.

**Never:** No change to frames, screen or allocations with the toggle off or in release. Do not change the input region or layout (#415, #407); the thumbnail shows whatever `fillYuvInput` produces. Do not implement Story 10.37 or bugs #431-#438. No new Learner strings. Never write close/fix/resolve before an issue number (write Refs #414).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Toggle on, read frame | real `LiteRtStickerCenterDetector` (fake session) on a known YUV frame | `debug.thumbnail.pixels` equal, byte for byte, the output of `fillYuvInput(frame, rotation, ...)` for that frame; peaks in model-input px; info line | No error expected |
| Toggle on, next frame differs | second frame with other content / peaks | a new thumbnail with the second frame's bytes and peaks | -- |
| Toggle on, detector exposes no input | `copyModelInput` returns false or throws | overlay present, `thumbnail == null` | never throws |
| Toggle on, skipped frame | detector skipped / unavailable / bad crop / sampler failure | `ContinuousDebugOverlay.SKIPPED` (no thumbnail) | -- |
| Toggle off / release | any frame | `debug == null`, `copyModelInput` never called by the debug path | -- |
| Fewer than 2 peaks | 0 or 1 peak | info `spacing=-` | -- |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousDebugOverlay(peaks, lattices, counts)` (#409); add `thumbnail: ModelInputThumbnail? = null` and the new class (plain class, not data: holds an IntArray; size, pixels, peaks as `List<Point2D>` in model-input px, info).
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- `copyModelInput(into)` (Story 10.34) default false; `StickerCenterDetection.inputScale/inputPadLeft/inputPadTop`, `uprightWidth/Height`. Read-only.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `copyModelInput` copies `inputBytes` (the exact interpreter input, filled by `fillYuvInput`). Read-only.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `debugOverlay(...)` builds the #409 overlay; private `copyModelInput(buffer)` wraps the detector call with try/catch (GH #389); add a lazily allocated `ByteArray(INPUT_BYTES)` and build the thumbnail there. `StickerCenterModelTransform.letterbox(uprightW, uprightH)` gives `resizedWidth/Height`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelContract.kt` -- `INPUT_SIZE=256`, `INPUT_BYTES`, `HEATMAP_STRIDE=4`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt` -- camera `Box` (tag `scanViewfinder`): Close at TopStart, torch at TopEnd, counts at BottomStart above Rec; draw the thumbnail at BottomEnd (`quickScan:debugInput`, 96 dp square, `Bitmap.createBitmap(pixels, 256, 256, ARGB_8888).asImageBitmap()` remembered per thumbnail, `FilterQuality.None`), peaks as `DEBUG_PEAK_COLOR` dots scaled by side/256, info `Text` above it (`quickScan:debugInputInfo`).
- `ContinuousScanEngineModule.kt`, `ContinuousQuickScanFlow.kt` -- already pass `debug` / `debugOverlay` through unchanged; no edit expected.
- Tests to mirror: `app/src/testDebug/.../continuous/ContinuousScanPipelineDebugViewTest.kt` (scripted detector, `goodFrame`), `LiteRtStickerCenterDetectorFailureTest.kt` (`CountingStickerCenterSession`: real detector with fake session; `makeBlankYuv`), `StickerCenterModelTransformTest.kt` (known YUV frames), `ContinuousQuickScanScreenTest.kt` (`theDebugOverlayDrawsPeaksAndLatticeCellsOnThePreviewInTheTwoPaneLayout`, `withoutADebugOverlayNoCountsNodeOrMarkIsDrawn`).

## Tasks & Acceptance

**Execution:**
- `ContinuousScanEngine.kt` -- add `ModelInputThumbnail` and `ContinuousDebugOverlay.thumbnail` -- the data the screen draws.
- `ContinuousScanPipeline.kt` -- when `debugView()` is on, after the overlay data: copy the model input into the lazy debug buffer, convert to ARGB, map peaks, build the info line; on false/throw thumbnail null -- the copy exists only while the toggle is on.
- `ContinuousQuickScanScreen.kt` -- draw the thumbnail, its peaks and the info line when `state.debugOverlay?.thumbnail != null`.
- `app/src/testDebug/.../continuous/ContinuousScanPipelineModelInputTest.kt` -- new: byte-for-byte vs `fillYuvInput` through the real detector (fake session) at two rotations, letterbox bars zero; per-frame update with peaks in model-input px; info line; `copyModelInput` false and throwing; skipped frame; toggle off: a counting detector sees no `copyModelInput` call and `debug` is null.
- `ContinuousQuickScanScreenTest.kt` -- add a two-pane pixel test: thumbnail node inside `scanViewfinder`, its pixels show the thumbnail colours and a peak dot at its model-input position, info text; extend the no-overlay test to assert no `quickScan:debugInput` node.

**Acceptance Criteria:**
- Given a debug build with the toggle on, when a frame is read, then the thumbnail's pixels equal the `fillYuvInput` output of that frame byte for byte and its peaks sit at `upright * inputScale + pad`.
- Given two successive read frames with different content, when each is analysed, then each frame's overlay carries a thumbnail of its own bytes and peaks.
- Given the overlay has a thumbnail, when the continuous screen renders in the two-pane layout, then the thumbnail, its peak dots and the info line are drawn inside the viewfinder.
- Given the toggle off (and release by the const gate), when frames are read, then no thumbnail exists, the debug path never calls `copyModelInput`, and the existing screen tests pass unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 10 findings — high 0, medium 0, low 7, false 3, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Another frame could overwrite the detector's shared input between inference and the thumbnail copy — `detect` and `copyModelInput` both run on the one analyzer thread of the single active pipeline, and `copyModelInput` takes the detector lock; no second caller runs between them.
  - `[low]` `[reject]` (blind) A new thumbnail (IntArray, peak list) is allocated per frame with the toggle on — debug-only, toggle-on only; the intent limits allocation only with the toggle off, which a test proves (no copy, no buffer).
  - `[low]` `[reject]` (blind) A new Bitmap per thumbnail, never recycled — debug-only; the GC reclaims it, and reuse would add mutable shared state between the analyzer and the main thread.
  - `[low]` `[reject]` (blind) `spacing` is O(n^2) — peaks are capped at `MAX_PEAKS = 36`, so at most about 1,300 distance checks per frame, debug-only.
  - `[false]` `[reject]` (blind) The toggle-off screen test does not assert the thumbnail tags are absent — the diff adds `assertDoesNotExist` for both `quickScan:debugInput` and `quickScan:debugInputInfo` in `withoutADebugOverlayNoCountsNodeOrMarkIsDrawn`.
  - `[low]` `[patch]` (intent) The byte-for-byte test compares with an independent `fillYuvInput` call, not with the bytes the interpreter session received — the fake session now records its `run()` input, and the two-rotation test also asserts the thumbnail equals those bytes (6 tests pass).
  - `[low]` `[reject]` (intent) No single test drives pipeline -> engine -> flow -> screen — the thumbnail rides inside the #409 overlay object, whose engine forwarding (`ContinuousScanEngineModuleTest`) and flow carrying are already tested; the pipeline and screen ends are each tested here.
  - `[low]` `[reject]` (intent) Release is not tested directly; the thumbnail code compiles into release, inactive — the same const-gate pattern as #409 (`QUICK_SCAN_DEBUG_VIEW = false` in release makes `debugView()` false, and the off path is tested).
  - `[low]` `[reject]` (intent) "Median sticker spacing" is implemented as nearest-peak spacing, not lattice pitch — the issue does not say which; peak spacing reads before a face is fitted (Design Notes).
  - `[false]` `[reject]` (intent) "On the last analysed frame" could mean keeping the input through skipped frames — a skipped frame is not analysed, so showing nothing for it matches the issue; the choice is in Design Notes.

## Design Notes

The pipeline, not the screen, converts to ARGB so the main thread only wraps the array in a Bitmap. A skipped frame keeps the #409 `SKIPPED` overlay, so the thumbnail disappears for that frame rather than showing a stale input. Spacing uses peaks (nearest-neighbour distance in upright px x scale / 4), so it reads even before a face is fitted.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-414 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew --no-daemon spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Debug builds can show the exact 256x256 model input on the continuous Quick Scan, behind the #409 toggle (`QuickScanDebugView`, adb broadcast `dev.cubentia.debug.QUICK_SCAN_DEBUG_VIEW`). With the toggle on, the pipeline copies the detector's last interpreter input (Story 10.34 `copyModelInput`) into a lazily allocated buffer, converts it to an ARGB thumbnail (letterbox bars included), maps the peaks to model-input pixels and builds an info line `input=WxH crop=WxH spacing=S` (heatmap cells). It rides in the #409 debug overlay; the screen draws it unsmoothed, 96 dp, at the viewfinder's bottom-end corner with the peaks as dots and the info line above. With the toggle off, and in release by the const gate, nothing is copied or allocated and the frame's `debug` is null. Refs #414.

**Files changed:**
- `app/src/camera/kotlin/.../ContinuousScanEngine.kt` — `ModelInputThumbnail` and `ContinuousDebugOverlay.thumbnail`.
- `app/src/camera/continuous/kotlin/.../ContinuousScanPipeline.kt` — builds the thumbnail inside the debug-overlay path.
- `app/src/camera/kotlin/.../ContinuousQuickScanScreen.kt` — `ModelInputThumbnailView` (`quickScan:debugInput`, `quickScan:debugInputInfo`).
- `app/src/testDebug/.../continuous/ContinuousScanPipelineModelInputTest.kt` — new: byte for byte against `fillYuvInput` and the interpreter's received bytes at rotations 0 and 90, per-frame update with peaks, spacing dash, copy refused or throwing, skipped frame, toggle off (no copy call).
- `app/src/testDebug/.../ContinuousQuickScanScreenTest.kt` — two-pane pixel test of the thumbnail; the no-overlay test asserts no thumbnail nodes.

**Review:** 10 findings: 1 patch (low: the test also checks the interpreter's received bytes), 0 deferred from review, 9 rejected with the reasons in the triage log.

**Follow-up review recommendation:** false. Patched entries by verdict: high 0, medium 0, low 1.

**Verification:** `./gradlew --no-daemon spotlessCheck build` gave BUILD SUCCESSFUL after the patch; JUnit XML shows `ContinuousScanPipelineModelInputTest` (6), `ContinuousQuickScanScreenTest` (15) and `ContinuousScanPipelineDebugViewTest` (5) with 0 failures. Every I/O matrix row is covered by a test that ran.

**Residual risks:** Both device points are still owed and recorded as deferred entries: the thumbnail updating on a real camera, and its use in the #413 A/B and Story 10.33 sessions. GH #414 stays open until they are done. The thumbnail's corner placement next to the session-recording controls was not checked on a device. With the toggle on and a session recording, the input is copied twice per frame.
