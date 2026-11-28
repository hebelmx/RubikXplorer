---
title: 'GH #409: debug overlay of detector peaks and fitted lattice on the continuous Quick Scan'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: 'd57fcbbdd0f34b47e9f0a23a712a17d86601ea57'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred:
  - summary: 'Device check owed (issue Done-when 1): on a debug build with the overlay toggle on, a device screenshot shows the peaks and lattice centres on the stickers at all four display rotations and in the two-pane layout.'
    evidence: 'No physical device or camera in the build session (PO clarification 2026-11-27); JVM/Robolectric tests cover the view-fraction mapping and the two-pane placement only.'
    severity: 'low'
  - summary: 'Device use owed (issue Done-when 3): use the overlay in the Story 10.33 device matrix to sort each failed face into detector miss, fitter reject or mapper drop.'
    evidence: 'Needs the Story 10.33 device session; nothing in this build can run it (PO clarification 2026-11-27). GH #409 stays open while either device point is owed.'
    severity: 'low'
---

<intent-contract>

## Intent

**Problem:** On device the continuous Quick Scan shows only the fitted face outlines, so a face that is missed cannot be traced to the detector (no peaks), the lattice fitter (peaks but no face) or the mapper (face fitted but dropped by `filterPlausibleQuads`).

**Approach:** In debug builds only, behind a runtime toggle that is off by default and set by an adb broadcast, the pipeline attaches a debug overlay to each frame: every detector peak (view fractions + score), each fitted face's nine lattice centres in view fractions with inlier flags, and a counts line (peaks, fitted, sampled, kept by the mapper, tracked, plus this frame's stage timings from #406). The continuous screen draws it over the preview when present.

## Boundaries & Constraints

**Always:** The overlay is built only when `ScanDiagnostics.QUICK_SCAN_DEBUG_VIEW` (const: true in `app/src/debug`, false in `app/src/release`) and the runtime toggle are both true; otherwise `ContinuousFrame.debug` is null and no peak list leaves the pipeline. Each peak keeps its detector `score` (Story 10.37 E4 needs score tiers). Lattice centres map upright → view with the same `StickerCenterModelTransform.uprightToViewFraction` the face outlines use. The toggle holder is generic (`QuickScanDebugView`) so #414 (model-input thumbnail) can reuse it. The adb receiver lives only in `app/src/camera/debug`, requires `android.permission.DUMP` like `QuickScanSessionReceiver`, and is registered only while a Quick Scan screen lives. Counts text is numbers and fixed words only (AD-13), not Learner text.

**Never:** No change to the screen, frame contents or behaviour with the toggle off or in release. Do not implement Story 10.37, #414 or bugs #431–#438; do not change the layout (#415 does); no new Learner strings; never `close/fix/resolve` before an issue number in commits or docs (write `Refs #409`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Toggle on, read frame | peaks + fitted faces | `debug` has every peak (viewX, viewY, score), one lattice per fitted face (9 view-fraction centres, inlier flags), counts line | No error expected |
| Toggle off / release | any frame | `debug == null`; screen identical to today | — |
| Toggle on, skipped frame | detector skipped / unavailable / bad crop / sampler failure | `debug` with no peaks, no lattices, counts `skipped` | Never throws |
| Rotation 0/90/180/270 | crop + view of upright aspect | a lattice centre at upright fraction (fx, fy) lands at view fraction (fx, fy) and equals the outline mapping | — |
| adb broadcast | `--ez on true|false`, missing extra | sets the toggle; missing extra toggles | Unknown action ignored |

</intent-contract>

## Code Map

- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `analyse` has `detection.peaks`, `fittedFaces`, `observations`, stage ns (`detectorNs`, `fitNs`, `sampleNs`, `mapNs`; -1 when timings off) and the overlay mapping loop to reuse; `skippedFrame` for the skipped case; `ContinuousPipelineFrame` gains `debug`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `ContinuousFrame(snapshot, viewOverlays)`: add `debug: ContinuousDebugOverlay? = null` and the debug data classes.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- `PipelineEngine.processFrame` builds `ContinuousFrame`; pass `result.debug`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- `StickerCenterPeak(x, y, score, viewX, viewY)`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/FaceLatticeFitter.kt` -- `FittedFace.inlierIndices` are indices into the detection centres (peaks), `gridCenters` the 9 projected cells (row-major, upright px). Read-only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- `processFrame` L358 `filterPlausibleQuads(detections)`; add a read-only count of quads kept last frame (0 on a twist frame and after `reset`), like `unreadSlots` (GH #405).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanFlow.kt` -- `next(frame, nowMs)`: carry `frame.debug` into a new `debugOverlay` field.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt` -- camera `Canvas` draws outlines; draw the overlay after them, and the counts `Text` (tag `quickScan:debugCounts`) at the camera's bottom start, only when `state.debugOverlay != null`.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt`, `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- const gate pattern `QUICK_SCAN_TIMINGS`; benchmark shares release code.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionModule.kt` -- `DebugQuickScanSessionFactory.create` registers `QuickScanSessionReceiver` and unregisters in `onRelease`; register/unregister the new toggle receiver alongside.
- Tests to mirror: `app/src/testDebug/.../continuous/ContinuousScanPipelineTimingTest.kt` (scripted detector, synthetic frame), `app/src/testDebug/.../ContinuousQuickScanScreenTest.kt` (`faceOutlinesStayOnThePreviewInTheTwoPaneLayoutAtSixHundredDpPortrait`, w600dp-h960dp-port pixel check), `app/src/testDebug/.../session/QuickScanSessionReceiverTest.kt`.

## Tasks & Acceptance

**Execution:**
- `app/src/debug/.../ScanDiagnostics.kt`, `app/src/release/.../ScanDiagnostics.kt` -- add `const val QUICK_SCAN_DEBUG_VIEW` true / false -- compile-time gate so release has no overlay path.
- `app/src/camera/kotlin/dev/cubentia/app/camera/QuickScanDebugView.kt` -- new: process-wide `@Volatile` toggle, off by default; `enabled` = gate && toggle; `set`/`toggle` -- shared with #414.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- add `DebugPeak(viewX, viewY, score)`, `DebugLattice(centers, inliers)`, `ContinuousDebugOverlay(peaks, lattices, counts)`, `ContinuousFrame.debug`.
- `core/domain/.../ContinuousFaceletMapper.kt` -- expose the last frame's plausible-quad count; domain test for it.
- `ContinuousScanPipeline.kt` -- injectable `debugView: () -> Boolean` (default `QuickScanDebugView::enabled`); when on, build the overlay (inlier cell = nearest grid centre of each inlier peak); counts line `peaks= fitted= sampled= kept= tracked=` plus `detectMs= fitMs= sampleMs= mapMs=` when timed; skipped frames give the `skipped` overlay.
- `ContinuousScanEngineModule.kt` -- pass `debug` through.
- `ContinuousQuickScanFlow.kt`, `ContinuousQuickScanScreen.kt` -- carry and draw (peaks: filled dots sized by score; lattice: inlier filled, non-inlier ring; distinct colours from the outline highlight).
- `app/src/camera/debug/.../QuickScanDebugViewReceiver.kt` -- new receiver `dev.cubentia.debug.QUICK_SCAN_DEBUG_VIEW` (`--ez on`), DUMP-protected; registered/unregistered in `QuickScanSessionModule.kt`.
- Tests (`app/src/testDebug`): pipeline overlay on/off/skipped and four-rotation mapping; flow carries `debug`; screen two-pane pixel test with overlay and no-overlay unchanged; receiver sets/toggles.

**Acceptance Criteria:**
- Given a debug build with the toggle on, when a frame is read, then the continuous screen draws one dot per peak and nine centres per fitted face at their view fractions, and the counts line shows peaks, fitted, sampled, kept, tracked and stage timings.
- Given rotations 0/90/180/270, when the pipeline maps a lattice centre, then a JVM test shows it lands at the expected view fraction, equal to the outline mapping of the same point.
- Given the two-pane layout (w600dp-h960dp-port), when an overlay is shown, then a Robolectric pixel test finds the peak and lattice marks inside the preview pane at their fractions.
- Given the toggle off (and release, by the const gate), when frames are read, then `ContinuousFrame.debug` is null, no overlay node or pixels are drawn, and the existing screen tests pass unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 17 findings — high 0, medium 1, low 9, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Inlier cell = nearest grid centre of each inlier peak, no threshold or one-to-one match — inliers sit within the fitter's tight tolerance of their cell, so the nearest cell is the matched one in practice; the approximation is in Design Notes, and an exact flag needs a fitter output change.
  - `[low]` `[reject]` (blind) Two inlier peaks nearest one cell could leave the matched cell drawn as a ring — same root and reason as the row above.
  - `[low]` `[reject]` (blind) A skipped frame shows only `skipped`, with no reason — the intent asks for no reason; adding one means a branch per skip path in a debug-only line.
  - `[false]` `[reject]` (blind) `kept` can be stale on twist or skipped frames — the mapper sets the count to 0 on the twist path, and skipped frames get the SKIPPED overlay, which never reads `kept`.
  - `[low]` `[reject]` (blind) The toggle stays on across later Quick Scan screens until the process ends — off at process start, as the issue asks; keeping it lets one device session cover several scans, and the receiver test asserts it.
  - `[low]` `[reject]` (blind) The counts line can wrap to two lines on a narrow preview — cosmetic, and only on a debug build with the toggle on.
  - `[low]` `[reject]` (blind) No legend for colours and dot sizes — this is a developer tool; colours are documented in code.
  - `[false]` `[reject]` (blind) No accessibility semantics — the counts are a Compose `Text`, which carries its text semantics; the canvas marks are a developer-only picture.
  - `[medium]` `[patch]` (verification-gap) No test checks that `PipelineEngine.processFrame` passes `debug` on — added the engine-level test "the engine forwards the pipeline's debug overlay only while the debug view is on" to `ContinuousScanEngineModuleTest` (4 tests pass); reverting `ContinuousScanEngineModule.kt:60` now fails it.
  - `[false]` `[reject]` (intent) The rotation test is close to a tautology — its first assertion (centre = upright fraction at each rotation) does not depend on the mapping function; only the second compares against the outline mapping.
  - `[low]` `[reject]` (intent) Rotations and the two-pane layout are never tested together — the issue gives that alignment to the device screenshot, which is a deferred entry; the JVM tests cover each surface.
  - `[low]` `[reject]` (intent) The overlay types and drawing branch compile into release, kept inactive only by the const gate — the release frame's `debug` is always null (the established #406 const-gate pattern); removing them from the release build would need new seams split across source sets.
  - `[false]` `[reject]` (intent) "Screen unchanged with the toggle off" is not shown — the existing screen tests pass in the full build, and a new no-overlay pixel and node test was added.
  - `[low]` `[reject]` (intent) The toggle lasts for the whole process — same as the toggle-lifetime row above.
  - `[false]` `[reject]` (intent) The deferred device checks are not visible in the patch — they are in this spec's `deferred` frontmatter, which is committed with the change.
  - `[low]` `[reject]` (intent) The inlier heuristic can misdraw a cell — same as the first row.
  - `[false]` `[reject]` (intent) The counts line adds `sampled` and `tracked` and shows `skipped` — within the intent (numbers and fixed words, timings allowed); not a defect.

## Design Notes

Inlier flags: `FittedFace` does not record which cell each inlier matched, so the app assigns each inlier peak to its nearest of the nine `gridCenters`; a cell with an assigned peak is an inlier, else a projected cell with no peak. Keeping this in the app avoids changing the fitter's output type.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-409 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew --no-daemon spotlessCheck build` -- expected: BUILD SUCCESSFUL (all JVM/Robolectric tests, lint, Kover, release APK).

## Auto Run Result

Status: done

**Summary:** Debug builds can draw a debug overlay on the continuous Quick Scan preview. It shows every detector peak (a dot sized by its score; the score is kept in the data), each fitted face's nine lattice centres (inlier filled, a cell with no peak a ring), and a counts line: `peaks= fitted= sampled= kept= tracked=` plus this frame's stage timings from #406. A process-wide runtime toggle, `QuickScanDebugView`, turns it on and off and can be reused by #414. The toggle is off by default and set by `adb shell am broadcast -a dev.cubentia.debug.QUICK_SCAN_DEBUG_VIEW [--ez on true|false] -p com.exxerpro.cubentia`; the receiver requires the DUMP permission and exists only while a debug Quick Scan screen lives. In release, the const `ScanDiagnostics.QUICK_SCAN_DEBUG_VIEW = false` means no overlay is built and the frame's `debug` is null. Refs #409.

**Files changed:**
- `app/src/debug/.../ScanDiagnostics.kt`, `app/src/release/.../ScanDiagnostics.kt` — the `QUICK_SCAN_DEBUG_VIEW` const gate.
- `app/src/camera/kotlin/.../QuickScanDebugView.kt` — new runtime toggle.
- `app/src/camera/kotlin/.../ContinuousScanEngine.kt` — `DebugPeak`, `DebugLattice`, `ContinuousDebugOverlay` and the `ContinuousFrame.debug` field.
- `app/src/camera/continuous/kotlin/.../ContinuousScanPipeline.kt` — builds the overlay; skipped frames get the `skipped` overlay.
- `app/src/camera/continuous/kotlin/.../ContinuousScanEngineModule.kt` — passes `debug` on.
- `app/src/camera/kotlin/.../ContinuousQuickScanFlow.kt` — carries `debugOverlay`.
- `app/src/camera/kotlin/.../ContinuousQuickScanScreen.kt` — draws the marks and the counts `Text` (`quickScan:debugCounts`).
- `app/src/camera/debug/kotlin/.../session/QuickScanDebugViewReceiver.kt` — new adb receiver.
- `app/src/camera/debug/kotlin/.../session/QuickScanSessionModule.kt` — registers and unregisters the receiver with the screen.
- `core/domain/.../ContinuousFaceletMapper.kt` — read-only `lastPlausibleQuadCount`.
- Tests: `ContinuousScanPipelineDebugViewTest` (on, off, skipped, counts, four rotations), `ContinuousScanEngineModuleTest` (engine passes the overlay on), `ContinuousQuickScanFlowTest`, `ContinuousQuickScanScreenTest` (two-pane pixel test with and without the overlay), `QuickScanDebugViewTest`, `QuickScanDebugViewReceiverTest`, `PlausibleQuadCountTest`.

**Review:** 17 findings: 1 patch (medium: the engine-forwarding test), 0 deferred from review, 16 rejected with the reasons in the triage log.

**Follow-up review recommendation:** false. Patched entries by verdict: high 0, medium 1, low 0. A first pass recommends a follow-up only when a high was patched or two or more mediums were.

**Verification:** `./gradlew --no-daemon spotlessCheck build` gave BUILD SUCCESSFUL after the patch (JUnit XML: every new and touched test class passed, 0 failures). The matrix audit covers every row, from the pipeline, flow, screen and receiver tests.

**Residual risks:** Both device checks are still owed and are recorded as deferred entries: the alignment screenshot at four rotations and in two-pane, and use in the Story 10.33 matrix. GH #409 stays open until they are done. Inlier flags are an approximation (nearest cell). The overlay types still compile into release, inactive behind the const gate.
