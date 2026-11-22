# Quick Scan pipeline audit

How a corner frame becomes a reviewed cube, and where each stage can delay or lose a scan. Reviewed at 33c1219b
against the operator notes and the [10.19 physical-device results](../../../docs/evidence/10-19/light-check-and-replay.md).
Measured behaviour is in [concerns-and-evidence.md](concerns-and-evidence.md); defects in
[failure-modes.md](failure-modes.md).

| Stage | What reaches the learner | Failure or delay to distinguish |
|---|---|---|
| Camera and aiming | The corner view shows a static aiming guide. ScanFrameSource maps the preview grid into the rotated analysis frame and calls CornerReader with the selected detector. | A cube inside the guide can still miss detection. Check crop alignment on device with the cube stationary, then moved toward each edge. |
| Detection and sampling | CornerReader supplies three faces of cell measurements. Detected quads are mapped back to preview coordinates. GuidedScanActivity smooths those quads for display and passes the original face readings to the controller. | A steady-looking grid can coexist with fluctuating sticker samples. Detection loss can clear the overlay before the capture controller's 300 ms loss grace expires. Log raw and displayed corners against the same frame. |
| Permission to capture | QuickScanController checks the changed hold. CornerHold handles detection loss, metering, lighting and sticker stability. A lit torch bypasses the side-light gate. | A rejected pose returns early. Loss can trigger fresh metering. Clearing a light hold needs 500 ms of passing evidence and re-metering. Movement in two cells of one face can restart the one-second stability window. The 1.5 s settle cap bounds one wait only. The November 22 diagnostics confirm a prolonged light hold and repeated re-entry. |
| Two holds and color reads | A capture advances the hold and saves available complete nine-cell samples. The second hold supplies the other three faces. Kotlin compares sticker colors in Lab using scan and calibration evidence. | Capture feedback acknowledges the hold even if faces later need recapture. The Lenovo evidence records a back face rejected in every calibrated run. The S24 FE session also identified different torch conditions between calibration and scanning. White balance remains a hypothesis. |
| Assembly and review | After the second hold, uncertain faces go to face-by-face capture. Assembly can request additional faces when the combined readings are ambiguous or do not fit. Completed scans enter review. | In the documented Lenovo run, eight confident cells and one close call sent an entire face back. Measure time to a correct reviewed cube and faces repeated alongside time to the shutter. |

## Capture gates in order

A hold captures when every gate below passes on consecutive frames (`CornerHold.onFrame`).

| Gate | Passes when | Cost when it fails |
|---|---|---|
| Pose | The cube was turned since the other hold (`CenterCheck`, `turnedOver`) | Waits; steady reference dropped |
| Detection | Cube not lost for 300 ms | Loss re-meters and restarts settle and steady |
| Metering settle | Camera reports AE converged, or 1.5 s cap | Up to 1.5 s per metering request |
| Light | Top-to-sides ratio at most 1.34 (calibrated) | Hold until 500 ms of passes, then a re-meter |
| Quality | All three faces ACCEPTABLE | Waits |
| Steady | No two stickers of a face moved ΔE 5 for 1 s | Window restarts |

With every gate passing first time a hold needs about 3 seconds.

## What to resolve before enabling release

1. **Attribute the wait on a reference device.** Answered for the still bench case (see concerns-and-evidence.md).
   Still open hand-held inside Quick Scan. Give each hold a monotonic timeline with viewfinder entry, first frame,
   detection transitions, pose rejection, metering request and settle, light hold, stability resets, shutter and
   handover. Report entry-to-capture and positioned-to-capture, plus fallback and abandoned holds.
2. **Separate geometry errors from color errors.** Use Story 10.20 to gather labeled corner captures on the current
   pipeline, including misses. Inspect the overlay and actual sample locations on the same frame. For valid geometry
   compare torch on/off and matching/mismatched calibration, with shaded and glossy sides. The existing target is at
   least 90% correct side stickers and three of four side faces kept.
3. **Apply the fix plan and re-measure** on the same bench and hand-held (fix-plan.md).
4. **Make the release decision on the full flow.** Repeat from the Solve tab through review with known cubes on
   physical devices. Record median and tail wait per hold, fallback frequency, repeated faces and incorrect cubes
   accepted. Agree on acceptable wait and error rates before evaluating results. Keep release Quick Scan OFF pending
   this evidence.

## Implementation landmarks

`ScanFrameSource.analyze` (corner frame production, metering, settle signal), `GuidedScanActivity` (overlay, capture
dispatch and review), `QuickScanController.onFrame`/`take` (pose and handover), `CornerHold.onFrame` (waits and
resets), `SideLight` (light check), `QuickScanClassification.needGrid` (face retention), `ScanAssembler` (cube
assembly and legality), `FixtureRecorderActivity` and `RecorderBurst` (debug recorder and bursts).
