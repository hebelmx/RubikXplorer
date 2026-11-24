# Continuous scan experiment tracker

**Current decision.** Prove the continuous-scan algorithm end-to-end on PC replay before creating or building Android stories. Super-Morse YOLO11n is disqualified as the multi-face PC reference due to an upstream synthetic-label bug (29.5% exact accuracy on Cubentia's corner photos). The existing release decision defers Quick Scan; the lowest supported hardware for a future continuous scanner stays open until the PC algorithm succeeds.

| State | Work item | Evidence needed to close it |
|---|---|---|
| Done | Record the learner experience | [Agreed proposal](brief.md) |
| Done | Find usable detector weights, mobile paths and tracking precedents | [Research report](research.md) and linked source digests |
| Done | Check whether Super-Morse returns proposals and accurate 3-face reads on Cubentia's photos | [PC still probe](digests/still-probe.md) and [scoring script](digests/score_cubentia_fixtures.py). Exposed Lenovo-stickerless device confound, 29.5% 3-face accuracy (18.5% on pastel `stlb`) vs. 88.9% on 42 valid frontal faces, and 19.2% FP on `128×128` negative crops. |
| Done | Score Super-Morse on third-party labeled photos and determine failure cause | 104 of 243 annotated stickers matched ([facelet_probe.py](digests/facelet_probe.py)); confirmed upstream `camera` vs. `offscreenCamera` aspect bug in Super-Morse's synthetic generator ([facelets.md](digests/facelets.md)). |
| Done | Prove the core continuous-scan algorithm on synthetic PC replay | [PC algorithm spike](pc_spike/README.md) (`pc_spike/run_spike.py`, `pc_spike/test_spike.py`): intrinsics-free 2D shared-edge face-graph + shading-invariant/AWB-compensated deferred 6-cluster solver + symmetry guard + local cubie invariants + twist detection passed **7/7 stress scenarios** (54/54 exact, 0 wrong, 0 transient wrong on scenarios 1–6; twist caught on scenario 7; **2.6–5.9 ms/frame** on PC CPU). |
| Done | Benchmark read-only public detectors on tuning split and bridge to mapper | On the 14 tuning `Fixtures/corner/` photos (aligned `detectorKeypoints`, canonical holdout union untouched): **`yush-io/cubeSolver`** (single-class `sticker`) achieved **89.9% hit (340/378), 0 duplicates, 99.3% on frontal tuning** (`YORO` scored only 26.5% and is disqualified). Built `pc_spike/lattice_fit.py` + `pc_spike/run_real_video.py`: recovered **40/42 faces (95.2%, 13/14 full 3-face corners, 32 shared edges)** on real tuning photos and ran at **~63 ms/frame CPU** on the cropped camera feed of `quickscan.mp4`. |
| Next | Record and score clean real-camera PC replay clips | Record two clean handheld clips (stickered, then stickerless) with known 54-cell truth (or test live via `run_real_video.py --webcam 0 --show`) and score exact 54-cell agreement and regrip recovery end-to-end. Follow with stress clips covering solved/mid-solve symmetric faces, pastel `stlb`, logo centers, glare/AWB shift, finger occlusion, and a mid-scan layer twist. |
| Next | Train a clean Cubentia-owned LiteRT detector (replacing PC-only comparator) | Extend `tools/corner-model/render_cube.py` (1–3 visible faces, off-center, hand-occlusion masks; canonical holdout union strictly excluded) and train a compact LiteRT sticker-center / face-quad model on PC GPU so PC and Android share the exact same clean-room model. |
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
