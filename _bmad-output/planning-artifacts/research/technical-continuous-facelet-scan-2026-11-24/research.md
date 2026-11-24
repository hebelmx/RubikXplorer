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

**Proceed with a desktop video spike and keep both tablets in the device trial.** A downloadable sticker detector already responds to Cubentia's angled photos. A much smaller detector could fit the phone's model budget after training. The question that should decide further investment is whether an observed sticker returns to its correct square after the cube turns or disappears behind a hand. Selecting a Galaxy S24 minimum today would leave that question unanswered. [1] [2] [3]

## Which model to try

| Candidate | What can be used today | Role in the experiment |
|---|---|---|
| Super-Morse YOLO11n | Downloadable 10.67 MB ONNX model. Predicts boxes for six sticker colors and a whole-cube class. Its application reduces the detections to nine cells. The weights declare AGPL-3.0 in their metadata and the repository carries an MIT notice. [1] | **Desktop reference.** Use its raw sticker proposals, record false positives and misses, and keep its weights outside the app. |
| NanoDet-Plus-m | Generic box detector. The published 416-pixel ncnn model and parameter files total 2.42 MB unpacked. Available weights need cube-specific training or fine-tuning. Thin, oblique stickers are an important held-out case. [2] | **Portable challenger.** Train for individual stickers if the replay shows automatic localization limits the map. Compare against known keyframes and the public detector before integrating a runtime. |
| Cubentia's existing corner and sticker models | A 406,100-byte model locates a guided corner. A 107,848-byte model reads nine stickers from an already rectified face. In a historical held-out evaluation the reader accepted four wrong stickers among 216. [5] | **Existing baseline.** Reuse the reader on well-supported face crops and compare with the detector's color decisions. The guided crop and nine-cell input leave free-position localization open. |

A detector score alone cannot decide that a sticker belongs in a named square. Also test a geometry-only sticker detector with color sampled independently. Other downloadable candidates include oriented sticker boxes and a four-corner face model. Their weights, dataset splits, and license limitations are catalogued in the [detector digest](digests/facelets.md).

## What worked on our photos

The PC probe ran the same public weights over all 46 corner photos in this checkout. After excluding the whole-cube class and suppressing overlapping boxes, it returned a median of **26 sticker proposals per image on 20 stickered-cube photos** and **19 on 26 stickerless-cube photos**. The respective ranges were 25–27 and 6–27. These photos were already framed around the cube. A count can include a misplaced box, a duplicate or a wrong color. Framing, lighting and device mix differ between the groups. The result justifies inspecting multi-face proposals and including stickerless cubes in the first replay. [1] [6]

A second PC check compared predictions with annotated sticker polygons on nine third-party three-face images. The model returned **229 sticker boxes for 243 labeled stickers**. Only **118 stickers** contained a uniquely matched predicted center, and **104** also matched the labeled color. Even a frame with 27 proposals can therefore give a misleading impression of coverage. This small sample makes accurate placement the first replay question. Audit coordinate transforms and label alignment before attributing the misses to the model. [8]

## How the map can grow

Start by marking visible stickers in the preview. Fit candidate grids to sticker centers or face boundaries and retain the visible pixels supporting each reading. A lone face can have several possible rotations. Wait for an overlapping view, or two readable neighboring centers and a shared edge, before assigning ambiguous observations to the permanent net. Track points between detector frames, periodically re-detect, and suspend map updates during a regrip. On return, verify the alignment before adding to an existing face. Keep disagreements available for review. [3] [4]

A published system using a robot, a large cube and a depth camera assembled exact nets in five of five indoor sequences and three of five outdoor sequences. In both incomplete scans the sixth face lacked a usable view. That directly motivates a cue for the missing side. Its hardware and planned viewpoints leave handheld RGB performance open. A separate handheld RGB project documents five phone recordings and a failure in which a stationary-corner rule captured only two faces despite readable colors on all six. Test movement and recovery explicitly. [3] [4]

The first reference can use human-corrected keyframes on the PC. Feed those and the automatic detections to the same mapper. If it misplaces a correctly labeled observation, improve registration. If it maps verified inputs correctly but loses the automatic ones, inspect the detector, color reader and visibility threshold. Validate completed nets against independently recorded truth. Cube legality can reject impossible readings but several different legal scrambles can satisfy the checker.

## Is a Galaxy S24 minimum warranted?

**Keep the Lenovo Tab M10 Plus (3rd Gen), Galaxy Tab S7 FE 5G and Galaxy S24 FE in the comparison.** The available phone is an FE. A standard S24 would require its own identified variant and measurements before naming it as the minimum. Measurements from a different phone, backend, or task cannot settle this decision. The earlier corner model took about 14 ms median on the Lenovo in a synthetic-frame device test. That result suggests testing lightweight inference there. It measures a much smaller workload than the proposed free-angle scanner. [5]

Size could rule out an integration even if the phone is fast. A historical camera-enabled projection was **26.86 MB** against a **30 MB installed-app budget**. Replacing only its 0.406 MB model with a 2.42 MB NanoDet artifact gives roughly **28.87 MB before runtime changes**. Retaining the corner model and adding NanoDet gives about 29.28 MB. A stock ncnn Android example includes a 12.62 MB uncompressed arm64 native library with other components linked in. Measure the incremental cost of the actual runtime and exported weights in a camera-enabled build. If a new runtime costs too much, the first phone experiment should train a sticker-center heatmap that exports to the LiteRT runtime Cubentia already uses. It would share the PC observation format and avoid adding ncnn. Its accuracy and exported size need measurement. The full desktop model is already 10.67 MB by itself. [1] [2] [5] [7]

Test identical recordings on each device to isolate processing cost. Then use the live cameras with the same cube, lighting and hand movement to measure the learner experience. The decision needs time to first correct placement, wrong accepted cells, correct reappearance, exact final map, manual corrections and sustained camera-to-map delay. If only the phone delivers a usable scanner, consider a **camera-feature minimum**. Record whole-app support as a separate product decision. The [tracker](tracker.md) contains the device matrix, inputs and exit criteria.

## Source notes

All sources accessed 2026-11-24. Historical project measurements describe the recorded build and fixture set. Recheck artifact hashes, runtime cost and license metadata on any model upgrade. The unmeasured quantities are continuous-scan accuracy, physical-device latency and actual installed footprint.

| Ref | Finding | Source | Date and evidence |
|---|---|---|---|
| [1] | Downloadable model, wrapper and metadata | [Super-Morse export](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/models/best.onnx) | 2026-11-19 repository revision. Artifact loaded locally. |
| [2] | Compact detector and mobile export | [NanoDet project](https://github.com/RangiLyu/nanodet) and [mobile digest](digests/mobile.md) | Upstream release assets inspected. Generic detector size only. |
| [3] | Multi-view registration and exact-net outcomes | [Amiet et al., ETH Zürich](https://arxiv.org/html/2610.24610v1) | 2026-10-17 preprint, one controlled RGB-D study. |
| [4] | Handheld capture failures | [Handheld scanner author’s fixture notes](https://github.com/ductm54/rubik_solver/blob/74b66ff2182d101805f38bca18dcc5276c3cb90c/README.md#notes) and [tracking digest](digests/tracking.md) | 2026-11-19 revision. Small author-maintained sample. |
| [5] | Existing model measurements | [Cubentia corner evaluation](../../../../docs/evidence/10-18/litert-model-evaluation.md) and [sticker evaluation](../../../../docs/evidence/10-23/evaluation.md) | 2026-11 project records. Different inputs and builds. |
| [6] | Local still-image probe | [Method, pinned weights, command and per-photo results](digests/still-probe.md) | 2026-11-24 local measurement, 46 guided stills. |
| [7] | Example native library footprint | [ncnn Android example release](https://github.com/nihui/ncnn-android-nanodet/releases/tag/20261020.5432cef) | 2026-10-20 example build. A project-specific stripped library requires measurement. |
| [8] | Cross-source annotated stills | [Polygon dataset](https://huggingface.co/datasets/seandavidreed/rubiks_cube_segmentation/tree/main/test) and [detector digest](digests/facelets.md) | 2026-11-24 local check, nine images. Training overlap unknown. |
