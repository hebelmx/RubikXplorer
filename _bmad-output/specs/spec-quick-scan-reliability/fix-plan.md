# Fix plan

Ordered by what the 2026-11-22 evidence shows costs most ([failure-modes.md](failure-modes.md)). Each step is checked
with the same bench recording and a hand-held run. Nothing here is implemented yet.

| Step | Change | Addresses | Serves | Check |
|---|---|---|---|---|
| 1 | Hold one exposure through the scan | F1, F2, F4, F12 | CAP-4 | Burst shows one exposure level per hold; no steady restart on a still cube |
| 2 | Steady by outline movement | F6 | CAP-4 | Hand-held replay completes a window about every 1.6 s at 5% |
| 3 | Light check stops blocking | F3, F4, F5 | CAP-4 | Capture 2's hold 1 near 3 s on the bench |
| 4 | Keep stickers, resolve by cube legality | F7, F8 | CAP-5 | Capture 2-like scan reaches review without face-by-face |
| 5 | Calibrate on the cube in hand | F9, F10 | CAP-6 | Capture 1's green face kept |
| 6 | Small fixes and diagnostics | F11, F13 | CAP-2 | Unit test; per-hold timeline in the log |

## Steps

1. **Hold one exposure.** Meter on the cube once per hold and keep that exposure until capture: no auto-cancel
   mid-hold, no re-meter when a light hold clears, re-meter only after the cube is lost. Judge "settled" by exposure
   time and ISO staying unchanged for several frames, not by the AE state. Meter hold 2 the same way as hold 1 or
   carry hold 1's white balance over.
2. **Steady by outline.** Restart the steady window when any outline corner moves more than about 5% of the cube's
   size from the reference frame. Keep a color comparison only to detect that a different face is shown. The 5%
   figure comes from one hand-held burst and needs a second.
3. **Light check advisory.** Keep the sides-dark coaching line; let capture proceed. If the product owner keeps a
   block: engage above 1.40 for three consecutive frames, clear below 1.30, cap the hold at 3 seconds, and let the
   manual shutter through.
4. **Keep stickers, not faces.** Carry each uncertain sticker with its two nearest colors. `ScanAssembler` already
   enumerates option grids and checks piece legality: if exactly one legal cube fits, finish; otherwise ask the
   learner about the remaining stickers in review, or for the one face that holds them. This is Story 10.21 made
   concrete. The agreement gate's safety property must still hold in the replay.
5. **Calibrate on this cube.** After hold 2 all six centers are known under the current light. Use them as the
   primary reference; use a saved calibration only where it agrees, and drop it when its centers are far from this
   cube's. Read a logo centre from the ring around the logo (`FrameReader.centerRing`), or mark it unusable as an anchor.
6. **Small fixes.** Clear `previous` in `backOut`. Log each hold's state transitions with an elapsed clock. Raise the
   device log buffer before recording.

Steps 1–3 are domain and camera-adapter changes with unit tests in `QuickScanControllerTest` and
`CameraXFrameSourceTest`; they need no new fixtures. Steps 4–5 are scored on the side-face replay and on Story 10.20
fixtures.

## Candidate ideas, not yet planned

- **Gather several views during one corner hold.** A small tilt can move glare off a sticker or bring a side into
  better light. Keep a short burst in session memory, align each visible face to a common 3×3 grid, and retain clear
  views across angles. A one-cell alignment error could assign convincing readings to the wrong sticker, so start a
  fresh group when tracking loses the pose. The hand-held burst supports this more than a plain median: readings
  change with tilt.
- **Keep the faces that read well and show it.** In the net, distinguish faces already seen, colors still uncertain
  and the corner currently requested. Prompt for a slight tilt when one side stays ambiguous.
- **Multi-frame median.** Measured: no gain on a still cube at steady exposure, small gain hand-held. Optional.
- **Hybrid per-face sticker reader (CAP-3).** As a bounded ML spike, evaluate a **hybrid per-face sticker reader** on the nine cells of already detected and unwarped faces. Give the model the face image and the existing Kotlin classifier's per-sticker candidate colors and certainty, plus prior-capture readings and known center colors where available. Prior evidence can come from earlier captures in the current scan. A bounded recent-frame buffer in memory could also provide appearance context; discard its images when the scan ends. The project currently says release and benchmark builds never persist camera frames. Existing saved color calibration is a possible cross-session color reference, but the repository does not establish a same-cube identity/profile mechanism. Persisting cube-specific model outputs or reference images would therefore be a separate privacy and architecture decision, not implied by this experiment.
  Treat the model inputs as evidence: the model should be able to use the image to correct a misleading Kotlin result, while cube-level consistency can rank or reject implausible combined readings. The standard cube scheme gives six known center colors and nine stickers of each color across the full cube, and previous captures add constraints, but these facts do not in general identify every unsampled sticker by themselves. Keep visual evidence and an explicit uncertain outcome so global constraints cannot force an unsupported confident label.
  Use manually labeled real face crops across supported devices, cubes, lighting and relevant torch/calibration conditions. Keep whole capture groups held out to avoid near-duplicate frames leaking across splits. Measure results as labeled data accumulates (a learning curve) to determine whether a useful model is possible with a practical dataset; the required data volume is not yet known. Compare hybrid and Kotlin-only results on correct colors, uncertain readings, confidently wrong labels, latency, and packaged model size. Make no production replacement decision until the held-out comparison is reviewed.
- **Judge the learner's whole scan.** Compare time to a correct reviewed cube, faces requested again, abandoned scans
  and incorrect cubes accepted, on known cubes, hand-held.

## Relation to planned stories

- **Story 10.20** (record 10.19-pipeline fixtures): still needed to score steps 4–5 and CAP-3. The recorder burst and
  the `lenovo-tb128xu_stla_cool-2` group are a start.
- **Story 10.21** (keep the side faces it reads well): steps 4–5 are its content.
- **Story 10.11** (raise the release flag): unchanged; follows 10.21's BETA call.
- Steps 1–3 are not covered by any planned story.
