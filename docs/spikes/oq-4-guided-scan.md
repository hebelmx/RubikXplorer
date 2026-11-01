# OQ-4: guided six-face scan spike

## Result and recommendation

**Release recommendation: BETA.** No independently ground-truthed physical face captures have been evaluated, so this spike does not establish sticker accuracy and cannot support GA. Keep the Manual Cube Editor as the primary entry path. Recommend the guided scan for the next measured prototype; do not promote Quick Scan until the normal indoor live-device gate below reaches at least 99% correct stickers.

Current classifier fixture score is synthetic contract validation only. It checks the nearest-center implementation and JSON reader, not camera recognition. The 132 JPEGs in `Fixtures/` are source photos organized into 16 capture folders spanning S1 through S4 for two cubes and two devices. Their folder names and capture-group labels are not per-image ground truth, face orientation, or sticker truth. The photos have no established lighting conditions, and do not constitute the physical sticker-accuracy measurement. The single labeled JSON sample under `Fixtures/labeled/` is a deterministic synthetic sample, clearly separated from those photos.

## Live capture matrix

Run all 16 cells with nine stickers per face, six faces per cube, and explicit labels entered independently from the scan. Record device OS/build, camera, cube, light measurement/description, capture date, correct/total stickers, unavailable captures, and recapture reasons. The normal indoor condition is the release recommendation gate; the other conditions characterize robustness.

| Cube | Device | Normal indoor | Diffuse bright | Warm indoor | Dim indoor |
| --- | --- | --- | --- | --- | --- |
| GAN stickerless | Galaxy S24 FE | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| GAN stickerless | Galaxy Tab S7 FE 5G | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Rubik stickered | Galaxy S24 FE | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Rubik stickered | Galaxy Tab S7 FE 5G | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

No physical measurements are claimed. Before merge, run both devices with both cubes under normal indoor light and independently verify each face's truth. GA requires at least 99% correct stickers across the measured normal-indoor set; otherwise remain BETA with Manual Cube Editor primary. Do not infer labels from folder or filename conventions.

## OQ-7 initial Quick Scan method recommendation

Start with classical computer vision in Kotlin in the CameraX adapter: use cube-edge/corner geometry and a perspective transform to produce the same nine-cell `FaceSample`, then fall back to Guided 6-face Scan whenever a face is uncertain. This is the smallest first experiment and avoids adding an OpenCV or LiteRT runtime before corner-view feasibility is known. The incremental third-party library/model cost is **0 bytes**; the measured CameraX + prototype baseline is the 16,039,650-byte debug APK increase reported below (release impact 0 bytes). The Kotlin algorithm's own DEX size is not measured yet and must be reported if Quick Scan is prototyped. Revisit OpenCV only if the Kotlin perspective correction cannot meet accuracy; consider LiteRT only if labeled training data supports a model.

## FaceSample JSON contract

`Fixtures/labeled/face-samples.json` is the runnable, synthetic example. A sample contains schema version, face, orientation degrees, exactly nine CIELAB triples in row-major order, and a quality status/detail. Index 4 is the center and index 1 is the up-neighbor. A labeled row separately carries exactly nine explicit `Color` truth values. Calibration must contain one valid CIELAB center for each of WHITE, YELLOW, GREEN, BLUE, RED, and ORANGE. Missing calibration, malformed samples, poor quality, or absent truth are unavailable and excluded from accuracy; no expected state or path-derived label is substituted.

Evaluation command:

```sh
./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.recognition.ScanFixtureEvaluationTest'
```

Accuracy is `correct predicted stickers / explicitly labeled stickers`. Report unavailable captures separately, and report measured physical accuracy separately from synthetic fixture checks. The classifier uses squared Euclidean distance in CIELAB to the six calibrated centers; it does not claim full CIEDE2000 perceptual difference or solve-state validity.

## Prototype and size cost

The disposable screen is compiled only with `-Prubikxplorer.spikeScan=true`; CameraX dependencies and CAMERA permission are attached only to the debug variant. Frames are analyzed in memory and are not persisted or exported. The screen supports explicit per-sticker truth entry and reports sticker accuracy only after all nine labels are provided. It automatically flags very low light and multiple near-white samples; the operator must mark blur, misalignment, or an incomplete grid with the quality controls, which reject the face and request recapture. These are prototype aids, not validated automated blur or alignment detection. The default release variant does not include the spike source or camera permission.

CameraX stable version selected from Android's release notes is 1.6.2. On this checkout, the default debug APK is 41,616,989 bytes; the opt-in spike debug APK is 57,656,639 bytes, a **16,039,650-byte increase**. Both builds used the same local JDK/SDK configuration. The unsigned release APK is 2,516,083 bytes in both builds; the spike has no release APK size cost. These are local build artifacts, not Play-delivered download sizes.
