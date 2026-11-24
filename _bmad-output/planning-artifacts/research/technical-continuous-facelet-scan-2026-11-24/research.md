---
title: Feasibility of continuous facelet scanning
type: technical
topic: Continuous facelet scan
decision: Select the first desktop replay experiment and device comparisons
source: Primary sources and local PC inference
status: complete
created: 2026-11-24
updated: 2026-11-24
---

# Feasibility of continuous facelet scanning

**Proceed with a PC-first algorithm spike — without Super-Morse's weights — and keep both tablets in the later device trial.** Auditing Super-Morse YOLO11n against Cubentia's own labeled fixtures revealed an upstream synthetic-label bug that breaks its 3-face localization (**29.5% exact cell+color accuracy** on `Fixtures/corner/` vs. **90.0%** on single frontal faces). Do not build the multi-face PC replay on it. Instead, prove the continuous mapping, angle-weighted color fusion, and regrip-recovery algorithm on PC first — starting with verified keyframes and either a clean geometry-only comparator (`yush-io/cubeSolver` / `YORO`) or a small LiteRT model trained from Cubentia's existing 3D renderer (`tools/corner-model/render_cube.py`) — before writing new Android stories or setting a Galaxy S24 hardware floor. [1] [2] [3] [6] [8]

## Which model to try

| Candidate | What can be used today | Role in the experiment |
|---|---|---|
| Cubentia-owned LiteRT detector (`render_cube.py` + `Fixtures/`) | Cubentia already bundles `litert:2.3.2` (0 extra runtime bytes) and owns a domain-randomized 3D renderer (`tools/corner-model/render_cube.py`) with real Lenovo/S24 FE palettes, hands, glare, and exact 2D face/sticker projections. [5] [7] | **Primary portable & PC-parity path.** Train either a free-pose multi-face quad detector or a geometry-only sticker-center heatmap (~0.2–0.8 MB FP16/INT8 `.tflite`), then pair with unwarped color reading (`sticker_reader.tflite` + CIELAB). Runs identically on PC and Android. |
| `yush-io/cubeSolver` (single-class `sticker`) / `YORO` (OBB) | Downloadable PyTorch YOLOv8n checkpoints (~6.3–6.4 MB); neither repository carries a license file, though The Kitchen's dataset (CC BY 4.0) and YORO's dataset card (MIT) are documented. [8] | **PC-only unpoisoned geometry check (optional).** Benchmark on Cubentia's 30 human-keypoint `Fixtures/corner/` photos first; use only as a temporary PC detector comparator if clean, never bundled in the app. |
| NanoDet-Plus-m | Generic Apache-2.0 box detector. Published 416-pixel ncnn pair is 2.42 MB unpacked; needs cube-specific training. Adding an ncnn native library is tightly constrained by NFR-11 (see below). [2] [7] | **Secondary PC box comparator.** Keep off Android unless an INT8 export fits inside the already-bundled LiteRT runtime or a measured stripped CPU runtime fits NFR-11. |
| Super-Morse YOLO11n | Downloadable 10.67 MB ONNX (AGPL-3.0 metadata). Accurate on frontal single faces (97.2% hit, 90.0% color on `Fixtures/live/`), **but structurally broken on 3-face angled cubes** due to a `camera` vs. `offscreenCamera` aspect-ratio bug in its synthetic generator (`tools/dataset-generator/index.html` L359, L462–484, L554–562). [1] [6] [8] | **Disqualified for multi-face replay.** Do not use its raw 3-face proposals; its horizontal compression (~0.45× around the cube midline, 320 duplicate hits on 30 photos) would confound every tracking/registration test. |
| Cubentia's existing corner and sticker models | `corner_keypoints.tflite` (406 KB, **6.35–6.46 ms median** on Lenovo TB128XU under R8) locates a guided corner; `sticker_reader.tflite` (108 KB) reads nine stickers from a rectified face (4 confidently wrong of 216 held-out). [5] | **Existing baseline & color reader.** Reuse `sticker_reader.tflite` + CIELAB on well-supported near-frontal face crops once continuous tracking locates the faces. |

A detector score alone cannot decide that a sticker belongs in a named square. Separating geometry detection from color classification avoids locking the detector to a single lighting/cube palette. All candidate weights, dataset splits, and license limitations are catalogued in the [detector digest](digests/facelets.md).

## What worked on our photos

Scoring Super-Morse `best.onnx` on Cubentia's own labeled photos exposed why proposal counts alone were misleading ([still-probe.md](digests/still-probe.md)):

- **Raw counts hid a device confound:** Across all 46 corner photos, stickered counts were 25–27 on both Lenovo (median 26) and Galaxy S24 FE (median 26.5), while stickerless split sharply by device: **Galaxy S24 FE median 24 (22–27)** vs. **Lenovo median 17 (6–22)** — masked by the combined stickerless median of 19.
- **3-face accuracy collapsed despite high counts:** On all 30 human-keypoint-labeled `Fixtures/corner/` photos (810 ground-truth stickers), Super-Morse achieved only **46.3% unique cell hit (375/810)** and **29.5% exact cell+color accuracy (239/810; 63.7% color given hit)**, with **320 duplicate hits (45.8% of all 699 proposals)** and median box aspect ratio **`w/h = 0.30`**. Side-face recall was lowest where needed most: top `U`/`D` 63.0% hit (41.5% correct), left `F`/`L` 40.0% (25.9%), right `R`/`B` 35.9% (21.1%). `S24 FE / stickered` had 210 proposals yet only **17.6% exact accuracy (38/216)** due to 102 duplicate/shifted hits.
- **Single frontal faces worked cleanly (confirming the root cause):** On 48 frontal `Fixtures/live/` faces (432 stickers), the exact same model scored **97.2% unique hit (420/432)** and **90.0% exact cell+color (389/432)** with square boxes (`w/h = 1.00`). On 73 cube-free negative frames (`Fixtures/corner/*/keypoints/*.png`), it fired false positives on **14/73 frames (19.2%, 46 boxes)**.
- **Nine third-party three-face photos (`seandavidreed/rubiks_cube_segmentation`):** Reproduced **229 boxes for 243 labeled stickers**, **118 matched centers (48.6%)**, and **104 color+location matches (42.8%)**, invariant to letterbox/stretch preprocessing (118–120/243). The horizontal box clustering observed on `16_jpg` and `0_jpg` (`w/h = 0.29`) matches the exact upstream synthetic generator bug (`offscreenCamera.aspect = 1.0` for rendering vs. viewport `camera` for `calculateYoloLabels()`). [1] [6] [8]

## How the map can grow

Start by marking visible stickers or face quads in the preview. Fit candidate projective 3×3 grids to sticker centers or face boundaries and retain the visible pixels, viewing angle, and source frame supporting each reading. A lone face can have four in-plane rotations; wait for an overlapping view, or two readable neighboring centers and a shared edge (with logo-tolerant center handling for Rubik's/GAN white centers), before committing observations to the permanent net. Track points between detector frames, periodically re-detect, and suspend map updates during a regrip. On return, verify alignment before adding to an existing face and keep disagreements available for review. [3] [4]

**Use continuous rotation to beat oblique side-face shading.** Story 10.21 showed that single-hold oblique side faces compress Red↔Orange and White↔Yellow. In continuous scanning, decouple **topological stitching** (oblique adjacent faces establish shared edges and quarter-turn alignment) from **color commitment** (weight and gate final colors by near-frontal viewing angle and low specular glare as each face rotates past front-on).

**Apply immediate local physical invariants before full-cube legality.** Full-cube legality runs late and accepts other legal scrambles. Under fixed-layer rotation, two local checks catch misregistrations and color errors mid-scan: (1) two stickers on the same shared edge or corner cubie can never have identical or opposite colors (e.g., White+Yellow on one cubie), and (2) stitched edge and corner cubies must form a duplicate-free multiset. Validate completed nets against independently recorded truth.

A published system using a robot, a large cube and a depth camera assembled exact nets in five of five indoor sequences and three of five outdoor sequences; both incomplete scans lacked a usable sixth view, motivating a cue for the missing side. A separate handheld RGB project documents five phone recordings and a failure where a stationary-corner rule captured only two faces despite readable colors on all six — confirming that stability must be tested in the moving face's frame. Note for Android portability: OpenCV is useful in the **PC Python spike**, but **AD-9 and `CameraSizePolicy.kt` forbid bundling OpenCV on Android**, so portable geometry/tracking should stay minimal enough for pure Kotlin. [3] [4]

The first PC spike must run the same mapper on **human-verified keyframes** first, then on an **unpoisoned automatic detector**. If it misplaces verified inputs, fix registration and invariants before touching models or Android.

## Is a Galaxy S24 minimum warranted?

**Keep the Lenovo Tab M10 Plus (3rd Gen), Galaxy Tab S7 FE 5G and Galaxy S24 FE in the comparison — and defer hardware gating until the PC algorithm works.** The available phone is an FE (Exynos 2400e); naming a standard S24 minimum would require its own identified SKU and measurements. On the budget tablet side, Story 10.18's R8-minified device gate D3 measured `corner_keypoints.tflite` at **6.35–6.46 ms median** on the Lenovo TB128XU (more than 2× faster than its ~14 ms debug timing), confirming lightweight LiteRT inference has real headroom even on the lowest tier — though a wider free-angle continuous workload still needs direct measurement. [5]

**Size budget (NFR-11, 30 MB installed ceiling) strongly favors staying on LiteRT.** Story 10.18's historical camera-enabled projection was **26.86 MB** (on a 12.41 MB release baseline), but by Story 10.23 `checkReleaseSize` had already grown to **12.98 MB (+575 KB)**, and `CameraSizePolicy.kt` charges `3 × classBytes` for post-10.18 camera code. Realistic current headroom is roughly **~2.0–2.5 MB** (or ~2.4–2.9 MB replacing the 406 KB corner model). An uncompressed 2.42 MB NanoDet FP32 pair leaves virtually no room for any added runtime, and a stock ncnn Android demo ships a 12.62 MB arm64 native library (including OpenCV/Vulkan). Measure `./gradlew :app:checkCameraSize` on the live branch before adopting any new runtime; a **0.2–0.8 MB LiteRT model** exported into the already-bundled `litert:2.3.2` runtime avoids native-library bloat completely. [2] [5] [7]

Only after the PC spike passes its exit bar should we replay identical recordings on each physical device to isolate compute cost, then run matched live captures to measure time to first correct placement, wrong accepted cells, correct reappearance, exact final map, manual corrections, and sustained camera-to-map delay. If only the phone delivers a usable scanner, propose a **camera-feature minimum** as a separate decision from whole-app support. The [tracker](tracker.md) holds the PC-first gates and device matrix.

## Source notes

All sources accessed 2026-11-24. Historical project measurements describe the recorded build and fixture set. Recheck artifact hashes, runtime cost and license metadata on any model upgrade. The unmeasured quantities are continuous-scan accuracy, physical-device latency and actual installed footprint.

| Ref | Finding | Source | Date and evidence |
|---|---|---|---|
| [1] | Downloadable model, wrapper, metadata, and synthetic-label bug | [Super-Morse export](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/models/best.onnx) and [generator bug (`camera` vs `offscreenCamera`)](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/tools/dataset-generator/index.html#L554-L562) | 2026-11-19 repository revision. Artifact loaded locally; 3-face label bug confirmed. |
| [2] | Compact detector and mobile export | [NanoDet project](https://github.com/RangiLyu/nanodet) and [mobile digest](digests/mobile.md) | Upstream release assets inspected. Generic detector size only. |
| [3] | Multi-view registration and exact-net outcomes | [Amiet et al., ETH Zürich](https://arxiv.org/html/2610.24610v1) | 2026-10-17 preprint, one controlled RGB-D study. |
| [4] | Handheld capture failures | [Handheld scanner author’s fixture notes](https://github.com/ductm54/rubik_solver/blob/74b66ff2182d101805f38bca18dcc5276c3cb90c/README.md#notes) and [tracking digest](digests/tracking.md) | 2026-11-19 revision. Small author-maintained sample. |
| [5] | Existing model, R8 latency, and release-size measurements | [Cubentia corner evaluation](../../../../docs/evidence/10-18/litert-model-evaluation.md), [sticker evaluation](../../../../docs/evidence/10-23/evaluation.md), and [Story 10.23 release size](../../../../docs/evidence/10-23/release-size.md) | 2026-11 project records. Different inputs and builds. |
| [6] | Local still-image probe and Cubentia fixture accuracy | [Method, device/cube breakdown, and accuracy](digests/still-probe.md) and [scoring script](digests/score_cubentia_fixtures.py) | 2026-11-24 local measurement: 46 corner counts, 30 labeled corner photos (810 stickers), 73 negatives, 48 frontal faces. |
| [7] | Example native library footprint | [ncnn Android example release](https://github.com/nihui/ncnn-android-nanodet/releases/tag/20261020.5432cef) | 2026-10-20 example build. A project-specific stripped library requires measurement. |
| [8] | Cross-source annotated stills | [Polygon dataset](https://huggingface.co/datasets/seandavidreed/rubiks_cube_segmentation/tree/main/test) and [detector digest](digests/facelets.md) | 2026-11-24 local check, nine images. Training overlap unknown. |
