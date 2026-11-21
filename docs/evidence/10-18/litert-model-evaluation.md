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

> **PO Device Session Note (`Fixtures/corner/`):** No real corner-hold captures are committed under `Fixtures/corner/` yet, so AC2's validation on real Lenovo TB128XU and Galaxy S24 FE frames has not run. Once the PO capture session records and labels them (format in [`tools/corner-model/README.md`](../../../tools/corner-model/README.md#real-corner-fixtures-fixturescorner)), the acceptance check is `uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner`: it reports recall, pixel error and negative rejection on the real captures and exits nonzero below recall 92% / rejection 96% or when the directory is missing or empty. `train_corner_model.py --fixtures-corner` can also fine-tune on the same captures (keep an evaluation split apart).

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

> **Status: from a previous revision of `LiteRtCornerDetectorDeviceTest`, pending device gate D1.** The numbers below come from an earlier revision of the test (before its poses were widened and the real-palette fields were added); its raw XML and logcat are not committed, so they are not reproducible from the repository. Re-run the current test on the Lenovo (D1) and commit the raw report before treating them as AC evidence.

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
- **R8 inference check (device gate D3):** `./gradlew -Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest` runs the device test against the opt-in `cornerR8` build type: release R8 and `cubentia-keep-rules.pro` with `CAMERA_SCAN`/`QUICK_SCAN`/`CASE_RECOGNITION` = BETA, debug-signed (the test-only keep rules retain `dev.cubentia.**` and the Kotlin runtime so the test APK links; LiteRT keeps only the shipped rules). `assembleCornerR8` and `assembleCornerR8AndroidTest` build locally; the connected run needs a device and has not run. Release flags stay OFF.
- **AD-13 manifest (corner flags on):** `litert` is added without `com.google.android.play:ai-delivery` (the Play AI-pack download path, unused with a bundled model: it brought asset-delivery, WorkManager and Play services), and `src/camera/corner/AndroidManifest.xml` removes litert-api's `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`; `src/camera/AndroidManifest.xml` removes `ACCESS_NETWORK_STATE` (media3-common via CameraX). `ManifestPolicy` now fails any variant that declares `FOREGROUND_SERVICE*`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` or a Play Core / WorkManager component.
- **Measured R8-minified build (`benchmark`, temporary init-script with `CAMERA_SCAN = BETA, QUICK_SCAN = BETA`; measured before the ai-delivery exclusion):**
  - Single-ABI `arm64-v8a` APK: `13,004,957` bytes; uncompressed DEX: `4,248,056` bytes; **installed estimate: `25,902,725` bytes (25.90 MB, 4.10 MB headroom below NFR-11 30 MB)**.
  - `corner_keypoints.tflite` packaged uncompressed (`compress_type = 0`, `406,100` bytes).
  - R8 mapping confirmed `org.tensorflow.lite.NativeInterpreterWrapper`, `org.tensorflow.lite.TensorImpl`, and `LiteRtCornerDetector` retained cleanly with `cubentia-keep-rules.pro`.
- **16 KB ELF Page Alignment (`checkDebugElfAlignment`, `checkReleaseElfAlignment`, `targetSdk 36`):**
  - All 10 64-bit native `.so` entries (`arm64-v8a` and `x86_64`), including `litert-2.3.2.aar` (`libLiteRt.so`) and `litert-api-2.3.2.aar` (`liblitert_jni.so`), verified `min_p_align = 0x4000` (16 KB).

---

## 5. 3D Side-Face Readout (`R`/`L`) & Capture-Cue Fixes

- **3D side-face Red (`R`) vs. Orange (`L`):**
  - Tightened 3D unwarped cell ROI (`CornerReader.CORNER_CELL_ROI_FRACTION = 0.48`, keeping 2D Guided Scan at `0.60`) and disabled Hold-2 AWB re-metering (`includeAwb = (quick.hold == 1)`) so Hold 2 (`Y/O/B`, no white face) does not shift white balance.
  - **Synthetic only; device check pending (device gate D2).** `CornerLocatorTest` gives **0 `R`/`L` 2D fallbacks** over all 8 real Lenovo TB128XU & Galaxy S24 FE `stka`/`stkb`/`stla`/`stlb` studio palettes under assumed 0.92/0.88 side-face shading with an exact injected detector. It is a regression guard, not a repro: it also passes with the old 0.60 ROI (checked locally by reverting the ROI), and the Lenovo log records only `unc=9` on `R`/`L`, no side-face colours to replay. Only the bevel-band ROI test fails on the old ROI. Whether the ROI and the Hold 2 AWB change remove the device `unc=9` fallback is unverified until D2.
- **Capture-cue regressions (#197):**
  - Fixed `quickScan:captureFlash` overlay in `GuidedScanScreen.CameraArea` (child overlay `Box`, no longer overwritten by `.testTag("scanViewfinder")`).
  - Fixed Capture 2 fallback transition in `GuidedScanActivity.captureCorner` (`GuidedScanViewModel.holdTransition`) so leaving `CornerViewfinder` for Guided fallback waits for the full 400 ms freeze cue.
  - Covered by `QuickScanScreenTest` and `QuickScanRouteTest`.

---

## 6. Next Step (PO Hands-On BETA Session)

Run the fast hands-on BETA check on Lenovo TB128XU (`HA1S28MM`) and Galaxy S24 FE with both stickered (scrambled & solved) and stickerless cubes across plain light and dark/glossy surfaces, and optionally capture real corner frames into `Fixtures/corner/` if any real-world pose needs fine-tuning.
