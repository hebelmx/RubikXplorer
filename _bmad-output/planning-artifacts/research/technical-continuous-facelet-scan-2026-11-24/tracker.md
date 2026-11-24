# Continuous scan experiment tracker

**Current decision.** Proceed to a PC video replay experiment. The existing release decision defers Quick Scan. The lowest supported hardware for a future continuous scanner remains open.

| State | Work item | Evidence needed to close it |
|---|---|---|
| Done | Record the learner experience | [Agreed proposal](brief.md) |
| Done | Find usable detector weights, mobile paths and tracking precedents | [Research report](research.md) and linked source digests |
| Done | Check whether a public sticker detector returns proposals on our cube photos | [PC still probe](digests/still-counts.csv). Localization, color accuracy and tracking still require labels. |
| Done | Score the public detector on third-party labeled photos | 104 of 243 annotated stickers had a matched box center and the correct color. The [comparison script](digests/facelet_probe.py) reproduces the result. |
| Next | Assemble the first replay clip | A known scramble and independently verified 54-cell net, whole-cube rotation, at least one face leaving and returning. Label visible cells, identity and timestamps on selected keyframes. |
| Open | Build the desktop replay | Show video, proposals and growing net side by side. Store frame, face, row, column and reason for accepting or reopening each observation. Run once with human-verified keyframe correspondences and once with automatic association. |
| Open | Compare a mobile candidate on the identical input | Use the same coordinates, score definitions and held-out clips. Measure misses, wrong color, wrong position, duplicate placement, and recovery after occlusion separately. |
| Open | Try the live interaction | Observe when a learner first sees progress, whether cues restore progress, whether the net flickers or moves trusted stickers, and how many cells they must correct. |
| Open | Benchmark devices and decide the camera feature floor | Complete the device matrix below with model, runtime and build identities. |

## First replay

The original checkout has an untracked 170-second screen recording at `captures/quickscan-20261122-111103/quickscan.mp4`. Sampled frames show the old scan interface and multiple cube appearances. It can supply exploratory frames. A single fixed scramble, the 54-cell truth and a clean camera feed have yet to be established for that recording.

Record a stickered cube first, then repeat with a stickerless cube under the same light. Include off-center framing, a partial neighboring face, a brief regrip, a return to an earlier face, and a few seconds of background without a cube. Preserve the original camera image and timestamps. Keep complete sessions together when splitting training, tuning and evaluation. Include glare and backlighting in subsequent held-out sessions. A single clip can expose a failure, while varied held-out sessions are needed to compare models.

Score time from first visible sticker to first correct map placement, unique correct slots over time, wrong accepted slots including transient ones, time to correct re-registration, unresolved cells, manual edits, and exact final agreement with the known scramble. Report incomplete scans separately. Use the same color/face coordinate convention for truth and predictions. A valid but different scramble counts as wrong. Proposed first milestone is a correct reappearance with zero wrong committed placements on the reference clip. This is a spike milestone, not a release threshold.

## Hardware decision record

| Device | Role | Continuous video accuracy | Live performance and size |
|---|---|---|---|
| Lenovo Tab M10 Plus (3rd Gen), TB128XU | Current minimum reference and first low-end test | Pending | Pending |
| Galaxy Tab S7 FE 5G, SM-T738U | Second lower-tier candidate | Pending | Pending |
| Galaxy S24 FE, SM-S721U1 | Available phone comparator | Pending | Pending |
| Galaxy S24, exact regional SKU and chip to record | Possible feature minimum if results warrant a higher floor | Pending hardware identification and test | Pending |

On each physical device run the same replay to isolate compute, then record live runs under matched lighting and framing to include camera differences. Record model bytes, runtime/native-library and installed-size delta, peak memory, preview smoothness, dropped frames, median and p95 camera-to-map delay, and sustained behavior across repeated scans. Report device, OS, backend, input resolution and thermal state. Separate slow inference from errors that persist on fast hardware.

**Decision rule.** If a tablet reaches the correct net at an acceptable pace, keep that tier in consideration. If speed is the limiting factor, try lighter inference or less frequent detection between tracked frames. If only the phone tier delivers the experience, propose a feature-specific floor backed by exact-device results. If all tiers confidently misplace stickers, revisit association, training or interaction before selecting hardware.
