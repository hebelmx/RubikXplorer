# Concerns and Evidence

## User observations

- After positioning the cube inside the static three-axis **guide**, automatic capture can take about 3–4 seconds and sometimes 30 seconds or longer.
- The detected-corner **grid** moves as the model updates its estimate. It improved substantially with LiteRT compared with earlier sessions, but its stability is still in question.
- The learner cannot tell what the app is waiting for. Existing viewfinder feedback and logs have not made the delay easy to diagnose.
- Sticker colors appear to vary with lighting; white balance is a suspected contributor.
- Prior sessions produced screenshots, but their storage location is unknown. No video or session screenshots for this specific report were found in the checked-in repository.

## What repository evidence supports

| Concern | Code/evidence | Finding |
| --- | --- | --- |
| Guide alignment | `app/src/camera/kotlin/dev/cubentia/app/camera/ViewfinderGrid.kt` maps the centered overlay through the preview crop, rotation, and fill scaling to camera-frame coordinates. `ScanFrameSource.kt` uses that mapping for analysis. | The intended coordinate mapping is shared. This does not prove the rendered guide matches the physical cube on the device; a recording is needed to judge that. |
| Detected grid movement | `GuidedScanActivity.kt` updates `detectedCornerQuads` from `CornerOverlaySmoother`. `CornerLocator.kt` applies an EMA to small changes, clears on detection loss, and snaps on a large change. | Some movement is expected. Detection loss and large pose changes can make the overlay disappear or jump; code alone cannot establish whether the observed motion is acceptable. |
| Long capture time | `QuickScanController` considers detection, camera metering settlement, the light gate, stable sticker readings, and the changed-hold check. The app records per-frame corner diagnostics, but no complete user-visible wait breakdown. | There are several possible blockers. Existing evidence does not identify which one accounts for a 3–30+ second user wait. |
| LiteRT speed | `docs/evidence/10-18/litert-model-evaluation.md` reports about 14 ms median inference in a Lenovo device test. | This is model inference time per tested frame, not time from cube positioning to capture. It cannot explain the reported wait by itself. |
| Color reliability | `docs/evidence/10-18/quick-scan-litert-escalation.md` records Red/Orange uncertainty on shaded side faces. Kotlin reads sampled colors and classifies in CIELAB; follow-up work adjusts the sample ROI and when AWB is re-metered. | Lighting and side-face color separation are credible concerns. Evidence does not establish that Kotlin is inherently insufficient or that a learned classifier would improve results. |
| Existing captures | `Fixtures/corner/` and `Fixtures/live/` contain fixture images and labels; `docs/evidence/10-18/lenovo-quickscan-2026-11-20-6cd18ab1.log` is a historical diagnostic log. | These are not a recording of the newly reported slow session. The repository does not establish whether screenshots from other sessions were saved outside it. |

## Small investigation and model proposal

1. Record one representative device session from before cube positioning through capture or fallback. Include the static guide, detected-corner grid, status/coaching, and timestamps. Preserve the recording and matching device logs together.
2. Instrument each hold with elapsed time and state transitions: viewfinder entry, model detection/loss, overlay stability, metering requested/settled, light gate, sticker stability, hold-turn gate, capture, and fallback. Show the current blocking reason in a developer-readable diagnostic view or log; learner-facing coaching can be decided separately.
3. Compare the static guide and analysis crop on the recording, then replay matching frames to determine whether long waits align with detector loss, overlay instability, or a downstream gate.
4. As a bounded ML spike, evaluate a **hybrid per-face sticker reader** on the nine cells of already detected and unwarped faces. Give the model the face image and the existing Kotlin classifier's per-sticker candidate colors and certainty, plus prior-capture readings and known center colors where available. Prior evidence can come from earlier captures in the current scan. A bounded recent-frame buffer in memory could also provide appearance context; discard its images when the scan ends. The project currently says release and benchmark builds never persist camera frames. Existing saved color calibration is a possible cross-session color reference, but the repository does not establish a same-cube identity/profile mechanism. Persisting cube-specific model outputs or reference images would therefore be a separate privacy and architecture decision, not implied by this experiment.

   Treat the model inputs as evidence: the model should be able to use the image to correct a misleading Kotlin result, while cube-level consistency can rank or reject implausible combined readings. The standard cube scheme gives six known center colors and nine stickers of each color across the full cube, and previous captures add constraints, but these facts do not in general identify every unsampled sticker by themselves. Keep visual evidence and an explicit uncertain outcome so global constraints cannot force an unsupported confident label.

   Use manually labeled real face crops across supported devices, cubes, lighting and relevant torch/calibration conditions. Keep whole capture groups held out to avoid near-duplicate frames leaking across splits. Measure results as labeled data accumulates (a learning curve) to determine whether a useful model is possible with a practical dataset; the required data volume is not yet known. Compare hybrid and Kotlin-only results on correct colors, uncertain readings, confidently wrong labels, latency, and packaged model size. Make no production replacement decision until the held-out comparison is reviewed.

## Evidence limits

- The current 14 ms LiteRT measurement is not end-to-end capture latency.
- Existing logs do not start a timer at the user's “cube positioned in guide” moment.
- Static source inspection can assess the intended preview-to-frame transform but cannot validate its appearance on the user's current device, screen layout, or session.
- The hybrid color model is a proposed experiment. Representative labeled captures, the amount of data it needs, and acceptance thresholds remain unknown. Cube-level constraints can reduce ambiguity but must not replace visual evidence or create unjustified confidence. Cross-session reuse of cube-specific images or derived references is not approved by this brief; the default proposal keeps recent images in memory only and discards them at scan end, consistent with current camera-frame persistence rules.
