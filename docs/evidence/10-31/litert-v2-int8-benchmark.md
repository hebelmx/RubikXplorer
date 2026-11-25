# Story 10.31 Evidence — LiteRT v2 INT8 Sticker-Center Detector & 0.11.0 Size Gates

**Date:** 2026-11-25  
**Model:** `app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite`  
**Size / SHA-256:** `378,288 B` (`369.4 KB`) · `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`  
**Runtime:** LiteRT `2.3.2` (`com.google.ai.edge.litert:litert`, `ai-delivery` excluded), XNNPACK CPU (`2` threads), off main thread (`Executors.newSingleThreadExecutor`), zero frames persisted (AD-13).

## 1. Size & Packaging Gates (NFR-11 `< 30,000,000 B`, 16 KB ELF, AD-13)

| Gate / Build Profile | Measured Result | Ceiling / Requirement | Verdict |
| --- | --- | --- | --- |
| Default `release` (`:app:checkReleaseSize`, all camera flags OFF) | `13,025,179 B` installed (`AAB 6,345,351 B`, `APK 3,439,603 B`, `dex 3,143,992 B`) | `< 30,000,000 B`, `0 B` camera/LiteRT | PASS |
| Projected `0.11.0` camera release (`:app:checkCameraSize`, excludes `recorder/**`, `sticker/**`, `corner/**`, and `corner/assets`) | **`28,264,318 B`** (`+1,054,356 B × 3` classes, `+11,697,783 B` CameraX + LiteRT arm64-v8a, `+378,288 B` `sticker_centers_v2_int8.tflite`; headroom `1,735,682 B`) | `< 30,000,000 B`, `0 B` `corner_keypoints.tflite`, `0 B` `sticker_reader*.tflite` | PASS |
| Real minified `0.11.0` profile (`-Pcubentia.release011=true :app:checkRelease011Size`, `CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA, CASE_RECOGNITION=OFF`) | **`26,079,010 B`** installed (`AAB 16,565,127 B`, `APK 13,137,982 B`, `dex 4,262,476 B`; headroom **`3,920,990 B`**) | `< 30,000,000 B`, no `CORNER_R8_TEST_RULES` | PASS |
| `release011` AD-13 / ELF / Shadow gates (`checkRelease011ElfAlignment`, `checkRelease011MergedManifest`, `checkRelease011CameraClasspath`, `checkRelease011ShadowReader`) | 16 KB ELF alignment green, `FOREGROUND_SERVICE*` stripped, `CAMERA` only, `0` forbidden SDKs, `0` shadow-reader classes/assets | All green | PASS |
| `cornerR8` CI compatibility (`-Pcubentia.cornerR8=true :app:assembleCornerR8 :app:assembleCornerR8AndroidTest :app:checkCornerR8ShadowReader`) | Composes both `continuous` and legacy `corner` models, `0` shadow-reader classes/assets | All green | PASS |

## 2. Physical-Device R8 End-to-End Pipeline Benchmark (`cornerR8`)

Measured on R8-minified builds (`:app:connectedCornerR8AndroidTest`) across **60 steady-state positive 5-stage cube frames** (synthetic 3-face stickered/stickerless cubes across `0°/90°/180°/270°`, timed separately from **15 negative empty/dark/glossy frames**) running the full end-to-end pipeline (`bilinear-Y YUV→RGB letterbox + LiteRT INT8 2-thread XNNPACK + 3×3 NMS decode + FaceLatticeFitter + bilinear-Y ContinuousFaceSampler + ContinuousFaceletMapper + ViewfinderGrid.frameToViewFraction`):

| Device | Resolution | Pos 5-Stage E2E `p50` | Budget | Pos `p90` | Pos `min` / `max` | LiteRT `p50` | Neg `p50` | Positives (`>= 1` face) | Negatives (`0` faces) | Overlays verified (`< 1e-6`) | Verdict |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| **Lenovo TB128XU** (Android 13, gating minimum) | `640×480` | **49.00 ms** | `<= 50 ms` | 67.29 ms | 44.19 / 93.58 ms | 30.67 ms | 36.18 ms | 60/60 | 15/15 | 101 | **PASS** |
| **Lenovo TB128XU** (Android 13, gating minimum) | `1280×960` | **48.17 ms** | `<= 50 ms` | 61.01 ms | 42.66 / 96.40 ms | 30.61 ms | 36.26 ms | 60/60 | 15/15 | 103 | **PASS** |
| **Galaxy S24 FE (`SM-S721U1`)** (Android 17, mid-range gate) | `640×480` | **15.12 ms** | `<= 20 ms` | 22.12 ms | 13.03 / 33.63 ms | 7.66 ms | 9.62 ms | 60/60 | 15/15 | 101 | **PASS** |
| **Galaxy S24 FE (`SM-S721U1`)** (Android 17, mid-range gate) | `1280×960` | **14.83 ms** | `<= 20 ms` | 20.87 ms | 12.44 / 31.85 ms | 7.69 ms | 9.74 ms | 60/60 | 15/15 | 103 | **PASS** |

Legacy `LiteRtCornerDetectorDeviceTest` also ran in the same `cornerR8` suite and passed on both devices:
- Lenovo TB128XU: `6.36 ms` (`320×240`), `6.35 ms` (`640×480`), `6.50 ms` (`1280×960`), `20/20` negative rejection.
- Galaxy S24 FE: `2.63 ms` (`320×240`), `2.66 ms` (`640×480`), `2.69 ms` (`1280×960`), `20/20` negative rejection.

## 3. PC-to-Android Peak Parity on 3 Tuning `Fixtures/corner/` Stills

Lossless RGB bytes matching `core/domain/src/test/resources/fixtures/continuous/corner-peaks-parity.json` (`threshold = 0.45`, `maxPeaks = 36`, symmetric bidirectional Hausdorff error) pushed to `/data/local/tmp/cubentia-10-31-tuning/` (never committed into APK assets) and verified on both physical devices via `LiteRtStickerCenterDetectorDeviceTest.pcToAndroidPeakParityAndThreeFaceLatticeRecoveryOnTuningStills`:

| Tuning Still | Size | PC Peaks | Android RGB Peaks | Max Peak Error (Lenovo / S24 FE) | RGB `FaceLatticeFitter` (Faces / Shared Edges) | YUV `FaceLatticeFitter` (Faces / Shared Edges) |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `lenovo-tb128xu_stka_bar-warm/corner-1` | `206×206` | 30 | 30 | **0.0001 px** / **0.0001 px** | **3/3** · **3/3** | **3/3** · **3/3** |
| `lenovo-tb128xu_stlb_bar-warm/corner-1` | `206×206` | 29 | 29 | **0.0001 px** / **0.0001 px** | **3/3** · **3/3** | **3/3** · **3/3** |
| `sm-s721u1_stla_bar-cool-2/corner-2` | `422×422` | 28 | 28 | **0.0001 px** / **0.0001 px** | **3/3** · **3/3** | **3/3** · **3/3** |

## 4. Verification Commands

```bash
# Provenance & holdout-disjointness (stdlib Python)
python3 -m unittest discover -s tools/center-model -p 'test_*.py' -v

# Build-logic unit + Gradle TestKit functional gates
./gradlew -p build-logic test

# Full CI gate + projected camera size
./gradlew spotlessCheck build :app:checkCameraSize

# Real minified 0.11.0-profile size, ELF, manifest, classpath & shadow-reader gates
./gradlew -Pcubentia.release011=true \
  :app:checkRelease011Size \
  :app:checkRelease011ElfAlignment \
  :app:checkRelease011MergedManifest \
  :app:checkRelease011CameraClasspath \
  :app:checkRelease011ShadowReader

# Physical-device R8 parity & latency benchmarks (Lenovo TB128XU + Galaxy S24 FE)
ANDROID_SERIAL=192.168.0.20:37589 ./gradlew -Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest,dev.cubentia.app.camera.LiteRtStickerCenterDetectorDeviceTest
ANDROID_SERIAL=192.168.0.131:41067 ./gradlew -Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest,dev.cubentia.app.camera.LiteRtStickerCenterDetectorDeviceTest
```
