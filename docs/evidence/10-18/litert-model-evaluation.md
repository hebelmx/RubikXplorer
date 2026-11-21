# Story 10.18: Tiny 7-Keypoint LiteRT Corner Model Evaluation

- **Date:** 2026-11-20
- **Story / Decision:** Story 10.18 (*Quick Scan finds the cube's real edges and corners*, #195, #196, #197), AD-9 / SCP 2026-11-20
- **Model artifact:** [`app/src/camera/corner/assets/corner_keypoints.tflite`](../../../app/src/camera/corner/assets/corner_keypoints.tflite)
- **Pipeline:** [`tools/corner-model/`](../../../tools/corner-model/README.md) (`render_cube.py`, `train_corner_model.py`, `evaluate_model.py`)
- **Runtime:** `com.google.ai.edge.litert:litert:2.3.2` (XNNPACK CPU, 4 threads, off-main `analyzerExecutor`) behind `CornerDetector` in `app/src/camera/corner/`

---

## 1. Model Provenance & Architecture

- **Input:** `[1, 128, 128, 3]` `float32` RGB in `[0, 1]`, extracted upright at any sensor orientation (`0°/90°/180°/270°`) around `CornerGuide` with half-size `guide.radius * 1.65` (`CornerModelTransform`).
- **Backbone + Head:** Compact depthwise-separable inverted-residual CNN (`16 → 24 → 40 → 64 → 96`, 198,247 parameters) with a spatial-preserving `4×4×32` flattened head (`Dense(64) → Dense(15, sigmoid)`), predicting `[presence, T.x, T.y, UL.x, UL.y, LL.x, LL.y, B.x, B.y, LR.x, LR.y, UR.x, UR.y, C.x, C.y]`.
- **Artifact identity:** `corner_keypoints.tflite` SHA-256 `4875896238d96564175912e9bfdb1d2a0b7a341705e98aad525a9bf83c73720d` (406,100 bytes), exported by `tools/corner-model/train_corner_model.py` (seed `20261120`, `--quant fp16`) with TensorFlow 2.19.1 and its bundled `tf.lite.TFLiteConverter` (`from_keras_model`, `Optimize.DEFAULT` + `float16` supported type) on Python 3.12.13, NumPy 2.1.3, Pillow 11.3.0, Linux x86_64 + CUDA (`tensorflow[and-cuda]`).
- **Quantization & Packaging:** FP16 weight quantization (`406,100` bytes = **396.6 KB**, limit `< 450 KB`), stored uncompressed (`compress_type = 0` via `androidResources.noCompress += "tflite"`) for zero-copy `mmap`.
- **Training Data (seeded, `20261120`):** generation now always uses 8 fixed chunks, so the dataset no longer depends on the machine's core count (the committed artifact was generated before this was pinned). GPU training is not bit-exact, so a retrain reproduces the recipe and its metrics, not the SHA-256 above.
  - 24,000 train / 2,400 val synthetic 3D cubes rendered with randomized pinhole perspective (`pitch 16–52°`, `yaw ±26°`, `roll ±28°`, `persp 0.05–0.38`, off-center shifts), both stickered (black/white/grey body) and stickerless, solved and scrambled, directional face shading, specular glare, YUV 4:2:0 chroma subsampling, sensor noise/blur, gripping hands, cluttered desks, and dark glossy tabletop reflections (#196), plus 26% hard negatives (empty surfaces, dark glossy reflection-only frames, single flat 2D faces, hands only, colored clutter).
  - Real device palettes and warped 3×3 face crops sourced from `Fixtures/live/` (Lenovo TB128XU & Galaxy S24 FE `stka`/`stkb`/`stla`/`stlb`), holding out `lenovo-tb128xu_stkb_studio-a` and `sm-s721u1_stlb_studio-a` crops for out-of-distribution evaluation.

> **PO Device Session Note (`Fixtures/corner/`):** No real corner-hold captures are committed under `Fixtures/corner/` yet. `train_corner_model.py` includes a `--fixtures-corner` fine-tuning hook ready to ingest real Lenovo TB128XU and Galaxy S24 FE corner captures once recorded during the PO hands-on BETA session.

---

## 2. Held-Out Model Evaluation (`evaluate_model.py`, threshold `0.55` + `CornerLocator.isValidCubeGeometry`)

All slices are synthetic renders from the training generator (fresh seeds). Only `heldout_real_crop_3d` withholds data the model never saw (two fixture groups' face crops); the rest are in-distribution, so these figures are not a measure of live-camera accuracy.

| Evaluation Slice (`N = 160` each) | Recall / Rejection | Raw Presence | Mean Err (128 px crop) | Median Err | p90 Err |
| --- | --- | --- | --- | --- | --- |
| `stickered_3d` (scrambled & solved) | **98.1%** recall | 100.0% | 1.38 px | 1.14 px | 2.59 px |
| `stickerless_3d` (scrambled & solved) | **96.9%** recall | 100.0% | 1.39 px | 1.20 px | 2.54 px |
| `heldout_real_crop_3d` (unseen Lenovo `stkb` + S24 FE `stlb` crops) | **98.1%** recall | 100.0% | 1.36 px | 1.21 px | 2.44 px |
| `dark_glossy_cube_196` (cube over dark glossy table + reflection) | **100.0%** recall | 100.0% | 1.36 px | 1.20 px | 2.43 px |
| `neg_dark_glossy_reflection_196` (reflection only, no cube) | **100.0%** rejected (`0/160` FP) | — | — | — | — |
| `neg_empty_and_hand` (empty surface or hand only) | **100.0%** rejected (`0/160` FP) | — | — | — | — |
| `neg_flat_2d_face` (single frontal 2D cube face) | **99.4%** rejected (`1/160` FP) | — | — | — | — |
| **Overall (`N = 1,120`)** | **Precision 99.84% / Recall 98.28% / Neg Reject 99.79%** | — | — | — | — |

---

## 3. On-Device Verification — Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, `HA1S28MM`, Snapdragon 680)

Executed via `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest` against the main app debug build (off main thread on single-thread analyzer executor). Run on 2026-11-20 23:04 CST against the committed `corner_keypoints.tflite` (406,100 bytes); result `TEST-Lenovo TB128XU - 13.xml` (1 test, 0 failures, device `HA1S28MM`) and logcat tag `LiteRtDeviceTest`:

```
LiteRtCornerDetector device benchmark: frames=60 medianMs=13.99 p90Ms=14.48 minMs=13.62 maxMs=30.78 posDetected=40/40 meanPxErr=2.52 negRejected=20/20
```

The benchmark frames are synthetic renders (with the Kotlin test palette) fed through the real on-device LiteRT runtime; they are not live camera frames. The live hands-on R/L side-face check on real cubes remains a PO device-session gate.

| Metric | Pure-Kotlin Heuristic Baseline (`6cd18ab1`) | LiteRT `corner_keypoints.tflite` (10.18) | Target |
| --- | --- | --- | --- |
| **Median latency / frame** | ~920–1,050 ms | **13.99 ms** (**68× faster**) | `< 40 ms` |
| **p90 / min / max latency (60 frames)** | ~1,050 ms | **14.48 ms** / 13.62 ms / 30.78 ms | `< 40 ms` median |
| **3D cube detection (0/90/180/270°, stickered & stickerless, solved & scrambled)** | 0/91 on scrambled stickered | **40 / 40 (100%)** | High recall |
| **Mean keypoint error (320×240 frame)** | N/A | **2.52 px** | `< 10 px` |
| **Dark glossy reflection & empty negatives (#196)** | False locks / slow | **20 / 20 (100% rejected)** | `null` |
| **End-to-end `CornerReader.read` unwarp** | Broken on scrambled stickered | **Passed** | Unwarps from detected 7 corners |

---

## 4. Size, 16 KB ELF Alignment, Flag Isolation & R8 Verification

- **Release (`OFF` on `main`):**
  - `checkReleaseSize`: **12,405,575 bytes (12.41 MB)** vs. 30 MB budget (17.59 MB headroom).
  - `checkReleaseCameraClasspath`: **0** CameraX / LiteRT classes, native libs, or `.tflite` assets.
- **Projected Camera + LiteRT (`checkCameraSize`):**
  - Single-ABI `arm64-v8a` native accounting + conservative 40% R8 retention for unminified debug jars + uncompressed `.tflite` (`406,100` bytes): **26,715,613 bytes (26.72 MB < 30.00 MB)**.
- **Measured R8-minified build (`benchmark`, temporary init-script with `CAMERA_SCAN = BETA, QUICK_SCAN = BETA`):**
  - Single-ABI `arm64-v8a` APK: `13,004,957` bytes; uncompressed DEX: `4,248,056` bytes; **installed estimate: `25,902,725` bytes (25.90 MB, 4.10 MB headroom below NFR-11 30 MB)**.
  - `corner_keypoints.tflite` packaged uncompressed (`compress_type = 0`, `406,100` bytes).
  - R8 mapping confirmed `org.tensorflow.lite.NativeInterpreterWrapper`, `org.tensorflow.lite.TensorImpl`, and `LiteRtCornerDetector` retained cleanly with `cubentia-keep-rules.pro`.
- **16 KB ELF Page Alignment (`checkDebugElfAlignment`, `checkReleaseElfAlignment`, `targetSdk 36`):**
  - All 10 64-bit native `.so` entries (`arm64-v8a` and `x86_64`), including `litert-2.3.2.aar` (`libLiteRt.so`) and `litert-api-2.3.2.aar` (`liblitert_jni.so`), verified `min_p_align = 0x4000` (16 KB).

---

## 5. 3D Side-Face Readout (`R`/`L`) & Capture-Cue Fixes

- **3D side-face Red (`R`) vs. Orange (`L`):**
  - Tightened 3D unwarped cell ROI (`CornerReader.CORNER_CELL_ROI_FRACTION = 0.48`, keeping 2D Guided Scan at `0.60`) and disabled Hold-2 AWB re-metering (`includeAwb = (quick.hold == 1)`) so Hold 2 (`Y/O/B`, no white face) does not shift white balance.
  - Verified across all 8 real Lenovo TB128XU & Galaxy S24 FE `stka`/`stkb`/`stla`/`stlb` studio palettes under directional side-face shading in `CornerLocatorTest`: **0 `R`/`L` 2D fallbacks**.
- **Capture-cue regressions (#197):**
  - Fixed `quickScan:captureFlash` overlay in `GuidedScanScreen.CameraArea` (child overlay `Box`, no longer overwritten by `.testTag("scanViewfinder")`).
  - Fixed Capture 2 fallback transition in `GuidedScanActivity.captureCorner` (`GuidedScanViewModel.holdTransition`) so leaving `CornerViewfinder` for Guided fallback waits for the full 400 ms freeze cue.
  - Covered by `QuickScanScreenTest` and `QuickScanRouteTest`.

---

## 6. Next Step (PO Hands-On BETA Session)

Run the fast hands-on BETA check on Lenovo TB128XU (`HA1S28MM`) and Galaxy S24 FE with both stickered (scrambled & solved) and stickerless cubes across plain light and dark/glossy surfaces, and optionally capture real corner frames into `Fixtures/corner/` if any real-world pose needs fine-tuning.
