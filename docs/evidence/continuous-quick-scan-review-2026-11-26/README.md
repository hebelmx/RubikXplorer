# Continuous Quick Scan pipeline review (2026-11-26)

Code review of the continuous Quick Scan pipeline on `main` at 79c948e5 (before Story 10.34, PR #396), followed by an adversarial pass that tried to disprove each finding. Scope: `app/src/camera/continuous/` (detector, model transform, face sampler, pipeline), the analyzer wiring in `ScanFrameSource` and `GuidedScanActivity`, `ContinuousQuickScanFlow`, and `core/domain/.../recognition/continuous/` (lattice fitter, projective geometry, colour model, facelet mapper).

The probes are JVM tests run on a scratch copy of the code, not on a device. Their sources are in [`probes/`](probes/), saved as `.kt.txt` so the build and Spotless ignore them; copy one into `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/` to run it, and set its output path. Note: `ReviewProbe2Test` needs a two-line patch to `SyntheticCubeStream` (`rx = FingerSize.rx`, `ry = FingerSize.ry`).

## Verdicts

| # | Finding | Verdict | Issue |
|---|---|---|---|
| 1 | A finger over an edge row latches a false layer twist | **Confirmed**, for a finger covering about one edge row (90×40 px ellipse on ~194 px faces: 20/20 seeds on the stickered scenario; 0/20 at the smaller sizes and with no finger) | #401 |
| 2 | Per-frame mapper cost and heap grow without bound | **Confirmed**: 1 face 0.36 → 6.9 ms/frame over 12,000 frames; 3 faces 0.73 → 2.67 ms/frame over 6,000; about 1 MB of heap per 1,000 frames | #402 |
| 3 | Only the detector is guarded on the analyzer thread; other per-frame exceptions crash | **Real, duplicate**: #397 already proposes the general catch in `ScanFrameSource.read` | #397 |
| 4 | Absolute pixel thresholds (2,800 px², 18 px edge, 6 px spacing) drop side faces at 640×480 | **Not confirmed**: at a normal holding distance a face is well above the thresholds, and the 10.32 device pass reached 6/6 sides. The inconsistency between the 640×480 and 1280×960 targets is real but has no shown effect | — |
| 5 | The live YUV letterbox (2×2 bilinear, no antialiasing) differs from the Pillow resize the model is evaluated with | **Not confirmed in code, reopened by the PO** (2026-11-26: the PC pipeline locates stickers much better than the app). The device test `LiteRtStickerCenterDetectorDeviceTest` shows the YUV path still recovers 3/3 faces and 3/3 shared edges on the tuning stills. Peak-level parity of the YUV path is not measured; worth a check in 10.33 | #408 |
| 6a | The 8-per-colour assignment runs only at exactly 48 ready cells | **Confirmed in code**; that it causes the "6/6 sides, 6-8 unread" stall is plausible but unproven | #405 (enhancement) |
| 6b | A full but illegal net has no way out | **Refuted**: the cue does not reopen a cell, but *Review and Correct* is offered, so the Learner can leave. Folded into #405 as a quality gap | #405 |
| 7 | A frame the detector skipped counts as "cube lost" | **Confirmed in code** (low severity) | #403 |
| 8 | After `close()`, the detector returns empty frames without setting `unavailable` | **Confirmed, latent**: only tests call `close()` | #404 |

## PC spike vs app parity

See [pc-vs-app-parity.md](pc-vs-app-parity.md): issues #410 (center-square retry), #411 (mapper deviations and golden replay), #412 (frame rate), #413 (same phone clips through both pipelines).

## Enhancement ideas (input for Story 10.33)

- Crop the detector input to the tracked cube, plus a "move closer" cue: #407.
- Debug timings per stage on device (`inferenceMs` is measured but never logged today): #406.
- Live YUV input vs the PC input: #408. The PO observed on 2026-11-26 that the PC pipeline locates stickers much better than the app (first look).
- Debug overlay of sticker-center peaks and the fitted lattice, like the PC pipeline: #409.
- Express the mapper's streak limits in time rather than frames (noted in #403).
