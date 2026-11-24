# Continuous scan experiment tracker

**Current decision.** Prove the continuous-scan algorithm end-to-end on PC replay before creating or building Android stories. Super-Morse YOLO11n is disqualified as the multi-face PC reference due to an upstream synthetic-label bug (29.5% exact accuracy on Cubentia's corner photos). The existing release decision defers Quick Scan; the lowest supported hardware for a future continuous scanner stays open until the PC algorithm succeeds.

| State | Work item | Evidence needed to close it |
|---|---|---|
| Done | Record the learner experience | [Agreed proposal](brief.md) |
| Done | Find usable detector weights, mobile paths and tracking precedents | [Research report](research.md) and linked source digests |
| Done | Check whether Super-Morse returns proposals and accurate 3-face reads on Cubentia's photos | [PC still probe](digests/still-probe.md) and [scoring script](digests/score_cubentia_fixtures.py). Exposed Lenovo-stickerless device confound, 29.5% 3-face accuracy (18.5% on pastel `stlb`) vs. 88.9% on 42 valid frontal faces, and 19.2% FP on `128×128` negative crops. |
| Done | Score Super-Morse on third-party labeled photos and determine failure cause | 104 of 243 annotated stickers matched ([facelet_probe.py](digests/facelet_probe.py)); confirmed upstream `camera` vs. `offscreenCamera` aspect bug in Super-Morse's synthetic generator ([facelets.md](digests/facelets.md)). |
| Next | Assemble the first clean PC replay clips | Start with two clean camera recordings (stickered, then stickerless) with known scrambles, independently verified 54-cell nets, whole-cube rotation, and at least one face leaving and returning. Label visible cells, identity and timestamps on selected keyframes. Follow with stress clips covering solved/mid-solve symmetric faces, pastel `stlb`, logo centers, overhead glare/AWB shift, finger occlusion, and a mid-scan layer twist. |
| Next | Select or train an unpoisoned PC geometry detector | Freeze the **union** of `tools/sticker-model/holdout.json['heldout']` and `Fixtures/corner/groups.json[split=='heldout']` strictly out of training and selection. Benchmark `yush-io/cubeSolver` / `YORO` (read-only clean-room comparators, tuning split + full-frame negatives only) OR extend `tools/corner-model/render_cube.py` (1–3 visible faces, off-center, hand-occlusion masks) to train a small LiteRT detector. |
| Open | Prove the continuous-scan algorithm on PC replay (Android gate) | Show video, proposals/quads, graded quality scores, and growing net side by side. Start with an intrinsics-free 2D projective face-graph baseline + deferred global 6-cluster CIELAB assignment + local cubie invariants (including corner chirality & evidence-weighted conflict reopening). Pass first with human-verified keyframes (including solved/mid-solve symmetry and regrip recovery), then with the unpoisoned automatic detector. |
| Open | Compare a portable LiteRT candidate on the identical PC input | Use the same coordinates, score definitions and held-out clips. Measure misses, wrong color, wrong position, duplicate placement, and recovery after occlusion separately, plus `./gradlew :app:checkCameraSize`. |
| Open | Try the live interaction (only after PC gate passes) | Observe when a learner first sees progress, whether cues restore progress, whether the net flickers or moves trusted stickers, and how many cells they must correct. |
| Open | Benchmark devices and decide the camera feature floor | Complete the device matrix below with model, runtime and build identities. |

## First replay

The original checkout has an untracked 170-second screen recording at `captures/quickscan-20261122-111103/quickscan.mp4` (720×1280, 5,754 frames, variable rate). Sampled frames show the old scan UI and multiple cube appearances (Super-Morse fired ≥ 9 proposals on only 8 of 25 sampled frames, median 6). It can supply rough exploratory frames only; a single fixed scramble, 54-cell truth, and a clean camera feed require fresh recordings.

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
