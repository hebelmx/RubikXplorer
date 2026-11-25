# Story 10.31 Evidence — LiteRT v2 INT8 Sticker-Center Detector & 0.11.0 Size Gates

**Date:** 2026-11-25  
**Model:** `app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite`  
**Size / SHA-256:** `378,288 B` (`369.4 KB`) · `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`  
**Runtime:** LiteRT `2.3.2` (`com.google.ai.edge.litert:litert`, `ai-delivery` excluded), XNNPACK CPU (`2` threads), off main thread (`Executors.newSingleThreadExecutor`), zero frames persisted (AD-13).

## 1. Size & Packaging Gates (NFR-11 `< 30,000,000 B`, 16 KB ELF, AD-13)

| Gate / Build Profile | Measured Result | Ceiling / Requirement | Verdict |
| --- | --- | --- | --- |
| Default `release` (`:app:checkReleaseSize`, all camera flags OFF) | `13,025,179 B` installed (`AAB 6,345,351 B`, `APK 3,439,603 B`, `dex 3,143,992 B`) | `< 30,000,000 B`, `0 B` camera/LiteRT | PASS |
| Projected `0.11.0` camera release (`:app:checkCameraSize`, excludes `recorder/**`, `sticker/**`, `corner/**`, and `corner/assets`) | **`28,282,576 B`** (`+1,060,442 B × 3` classes, `+11,697,783 B` CameraX + LiteRT arm64-v8a, `+378,288 B` `sticker_centers_v2_int8.tflite`; headroom `1,717,424 B`) | `< 30,000,000 B`, `0 B` `corner_keypoints.tflite`, `0 B` `sticker_reader*.tflite` | PASS |
| Real minified `0.11.0` profile (`-Pcubentia.release011=true :app:checkRelease011Size`, `CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA, CASE_RECOGNITION=OFF`) | **`26,079,010 B`** installed (`AAB 16,565,127 B`, `APK 13,137,982 B`, `dex 4,262,476 B`; headroom **`3,920,990 B`**) | `< 30,000,000 B`, no `CORNER_R8_TEST_RULES` | PASS |
| `release011` AD-13 / ELF / Shadow gates (`checkRelease011ElfAlignment`, `checkRelease011MergedManifest`, `checkRelease011CameraClasspath`, `checkRelease011ShadowReader`) | 16 KB ELF alignment green, `FOREGROUND_SERVICE*` stripped, `CAMERA` only, `0` forbidden SDKs, `0` shadow-reader classes/assets | All green | PASS |
| `cornerR8` CI compatibility (`-Pcubentia.cornerR8=true :app:assembleCornerR8 :app:assembleCornerR8AndroidTest :app:checkCornerR8ShadowReader`) | Composes both `continuous` and legacy `corner` models, `0` shadow-reader classes/assets | All green | PASS |

## 2. Physical-Device R8 End-to-End Pipeline Benchmark (`cornerR8`)

Measured on R8-minified builds (`:app:connectedCornerR8AndroidTest`) across **60 steady-state positive 5-stage cube frames** (synthetic 3-face stickered/stickerless cubes across `0°/90°/180°/270°`, timed separately from **15 negative empty/dark/glossy frames**, which must fit 0 faces and decode 0 sticker-center peaks; every frame is camera-shaped `YUV_420_888`: direct buffers, 32-byte row padding, `pixelStride = 2` chroma) running the full end-to-end pipeline (`bilinear-Y YUV→RGB letterbox + LiteRT INT8 2-thread XNNPACK + 3×3 NMS decode + FaceLatticeFitter + bilinear-Y ContinuousFaceSampler + ContinuousFaceletMapper + ViewfinderGrid.frameToViewFraction`):

| Device | Resolution | Pos 5-Stage E2E `p50` | Budget | Pos `p90` | Pos `min` / `max` | LiteRT `p50` | Neg `p50` | Positives (`>= 1` face) | Negatives (`0` faces) | Overlays verified (`< 1e-6`) | Verdict |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| **Lenovo TB128XU** (Android 13, gating minimum) | `640×480` | **49.36 ms** | `<= 50 ms` | 66.97 ms | 44.42 / 88.11 ms | 30.64 ms | 36.51 ms | 60/60 | 15/15 | 84 | **PASS** |
| **Lenovo TB128XU** (Android 13, gating minimum) | `1280×960` | **49.28 ms** | `<= 50 ms` | 62.37 ms | 43.98 / 97.31 ms | 30.60 ms | 36.62 ms | 60/60 | 15/15 | 88 | **PASS** |
| **Galaxy S24 FE (`SM-S721U1`)** (Android 17, mid-range gate) | `640×480` | **15.19 ms** | `<= 20 ms` | 21.66 ms | 13.08 / 34.96 ms | 7.68 ms | 9.69 ms | 60/60 | 15/15 | 84 | **PASS** |
| **Galaxy S24 FE (`SM-S721U1`)** (Android 17, mid-range gate) | `1280×960` | **14.65 ms** | `<= 20 ms` | 20.71 ms | 12.44 / 33.64 ms | 7.70 ms | 9.79 ms | 60/60 | 15/15 | 88 | **PASS** |

Legacy `LiteRtCornerDetectorDeviceTest` also ran in the same `cornerR8` suite and passed on both devices:
- Lenovo TB128XU: `6.43 ms` (`320×240`), `6.35 ms` (`640×480`), `6.46 ms` (`1280×960`), `20/20` negative rejection.
- Galaxy S24 FE: `2.69 ms` (`320×240`), `2.66 ms` (`640×480`), `2.69 ms` (`1280×960`), `20/20` negative rejection.

Numbers above are from the follow-up review run on 2026-11-25. Fewer overlays are verified than in the first run (84/88 against 101/103) because `ContinuousFaceSampler` now rejects a face whose sampled cells leave the frame instead of reading clamped border pixels. The Lenovo margin to the 50 ms budget is under 1 ms.

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

# Push the 3 tuning stills as lossless PNG (the RGB bytes PIL decodes on the PC), named <group>_<still>.png.
# The parity test skips when none is pushed and fails when only some are.
for serial in 192.168.0.20:37589 192.168.0.131:41067; do
  for still in lenovo-tb128xu_stka_bar-warm/corner-1 lenovo-tb128xu_stlb_bar-warm/corner-1 sm-s721u1_stla_bar-cool-2/corner-2; do
    png="$(mktemp -d)/$(echo "$still" | tr / _).png"
    python3 -c "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').save(sys.argv[2])" "Fixtures/corner/$still.jpg" "$png"
    adb -s "$serial" push "$png" /data/local/tmp/cubentia-10-31-tuning/
  done
done

# Physical-device R8 parity & latency benchmarks (Lenovo TB128XU + Galaxy S24 FE)
ANDROID_SERIAL=192.168.0.20:37589 ./gradlew -Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest,dev.cubentia.app.camera.LiteRtStickerCenterDetectorDeviceTest
ANDROID_SERIAL=192.168.0.131:41067 ./gradlew -Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest,dev.cubentia.app.camera.LiteRtStickerCenterDetectorDeviceTest
```
