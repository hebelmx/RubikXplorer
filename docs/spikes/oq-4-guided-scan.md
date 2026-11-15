# OQ-4: guided six-face scan spike

## Result and recommendation

**Release recommendation: BETA.** The offline JPEG check below measures sticker color classification on captured photos, but does not evaluate the live CameraX pipeline. It does not satisfy the normal indoor live-device gate or support GA. Keep the Manual Cube Editor as the primary entry path; do not promote Quick Scan until the live-device gate below reaches at least 99% correct stickers.

## Offline S3-to-S4 source-photo evaluation (2026-10-31)

The fixture photos were visually inspected. For each cube/device group, six face-on, single-face S3 images (one visibly uniform face per canonical color) supplied calibration; six face-on S4 images supplied nine row-major sticker labels each. The checked-in manifest records every exact image, explicit color label, normalized S4 grid rectangle, S3 sample point, and excluded image. S3 and S4 are treated as distinct captured lighting groups, as reported by the user; no named illumination, photometric level, or relationship between the cube states is inferred. Face identity and orientation remain unknown, so this scores color classification only.

The evaluator extracts four small RGB patches around each sticker-center region to reduce center-logo contamination, converts them with the prototype's D65 sRGB-to-CIELAB constants, derives one center per color from the corresponding S3 image, and uses the same squared Euclidean nearest-center rule. The four patches avoid literal center pixels; this is an offline image-space color check, not a CameraX/YUV measurement. `Fixtures/labeled/photo-evaluation-overlay.jpg` marks each S4 grid, center, and four patch sites (green means predicted color matched visual truth, red means it did not; cyan marks the patch sites); `photo-calibration-overlay.jpg` shows the S3 calibration patches. Labels and sample rectangles were visually checked against the source views; logo centers are labeled by the visible underlying sticker/plastic color. No image was unavailable.

Run `python3 -m pip install -r tools/requirements-photo-eval.txt`, then `python3 tools/evaluate_fixture_photos.py`. `--check` verifies deterministic regeneration against the checked-in result. The machine-readable inventory and all predictions are in `Fixtures/labeled/photo-evaluation-results.json`; the source labels and sample rectangles are in `Fixtures/labeled/photo-evaluation.json`.

| Cube | Device | Correct stickers | Accuracy |
|---|---|---:|---:|
| Rubik stickered | Galaxy S24 FE | 34/54 | 62.9630% |
| GAN stickerless | Galaxy S24 FE | 39/54 | 72.2222% |
| Rubik stickered | Galaxy Tab S7 FE 5G | 26/54 | 48.1481% |
| GAN stickerless | Galaxy Tab S7 FE 5G | 36/54 | 66.6667% |
| **All four groups** | **2 cubes × 2 devices** | **135/216** | **62.5000%** |

The low offline score is evidence against promoting the current simple color-center method; it is not a live accuracy estimate. Because illumination was not metered or held to a named level in the source photos, the score cannot isolate lighting effects or be generalized to a particular lighting condition. The task's AC requiring both physical devices and both cubes to reach 99% in a controlled normal indoor live capture remains human-gated and unrun.

Current classifier fixture score is synthetic contract validation only. It checks the nearest-center implementation and JSON reader, not camera recognition. The 132 JPEGs in `Fixtures/` are source photos organized into 16 capture folders spanning S1 through S4 for two cubes and two devices. Their folder names and capture-group labels are not per-image ground truth, face orientation, or sticker truth. The photos have no established lighting conditions, and do not constitute the physical sticker-accuracy measurement. The single labeled JSON sample under `Fixtures/labeled/` is a deterministic synthetic sample, clearly separated from those photos.

## Live capture matrix

Run all 16 cells with nine stickers per face, six faces per cube, and explicit labels entered independently from the scan. Record device OS/build, camera, cube, light measurement/description, capture date, correct/total stickers, unavailable captures, and recapture reasons. The normal indoor condition is the release recommendation gate; the other conditions characterize robustness.

| Cube | Device | Normal indoor | Diffuse bright | Warm indoor | Dim indoor |
| --- | --- | --- | --- | --- | --- |
| GAN stickerless | Galaxy S24 FE | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| GAN stickerless | Galaxy Tab S7 FE 5G | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Rubik stickered | Galaxy S24 FE | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Rubik stickered | Galaxy Tab S7 FE 5G | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

No physical measurements are claimed. Device session A will run the Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE with both cubes under normal indoor light and independently verify each face's truth; it is deferred from this code-only implementation. GA requires at least 99% correct stickers across the measured normal-indoor set; otherwise remain BETA with Manual Cube Editor primary. Do not infer labels from folder or filename conventions.

## OQ-7 initial Quick Scan method recommendation

Start with classical computer vision in Kotlin in the CameraX adapter: use cube-edge/corner geometry and a perspective transform to produce the same nine-cell `FaceSample`, then fall back to Guided 6-face Scan whenever a face is uncertain. This is the smallest first experiment and avoids adding an OpenCV or LiteRT runtime before corner-view feasibility is known. The incremental third-party library/model cost is **0 bytes**; the measured CameraX + prototype baseline is the 16,039,650-byte debug APK increase reported below (release impact 0 bytes). The Kotlin algorithm's own DEX size is not measured yet and must be reported if Quick Scan is prototyped. Revisit OpenCV only if the Kotlin perspective correction cannot meet accuracy; consider LiteRT only if labeled training data supports a model.

## FaceSample JSON contract

`Fixtures/labeled/face-samples.json` is the runnable, synthetic example. A sample contains schema version, face, orientation degrees, exactly nine CIELAB triples in row-major order, and a quality status/detail. Index 4 is the center and index 1 is the up-neighbor. A labeled row separately carries exactly nine explicit `Color` truth values. Calibration must contain one valid CIELAB center for each of WHITE, YELLOW, GREEN, BLUE, RED, and ORANGE. Missing calibration, malformed samples, poor quality, or absent truth are unavailable and excluded from accuracy; no expected state or path-derived label is substituted.

Evaluation command:

```sh
./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.ScanFixtureEvaluationTest'
```

Accuracy is `correct predicted stickers / explicitly labeled stickers`. Report unavailable captures separately, and report measured physical accuracy separately from synthetic fixture checks. The classifier uses squared Euclidean distance in CIELAB to the six calibrated centers; it does not claim full CIEDE2000 perceptual difference or solve-state validity.

## Prototype and size cost

The fixture recorder is composed automatically into camera-enabled debuggable build types from `app/src/camera/debug`. Release and benchmark never include the recorder, even when a camera flag is raised. CameraX dependencies and CAMERA permission follow the camera feature flags. Frames stay in memory; only CIELAB samples, operator truth, calibration and device/cube/light metadata are saved. Low-light and glare checks and manual quality controls remain prototype aids, not validated blur or alignment detection.

The size figures below are historical Story 1.9 measurements from the former opt-in prototype, not measurements of the current recorder.

CameraX stable version selected from Android's release notes is 1.6.2. On this checkout, the default debug APK is 41,616,989 bytes; the opt-in spike debug APK is 57,656,639 bytes, a **16,039,650-byte increase**. Both builds used the same local JDK/SDK configuration. The unsigned release APK is 2,516,083 bytes in both builds; the spike has no release APK size cost. These are local build artifacts, not Play-delivered download sizes.


## Live baseline

<!-- live-baseline:start -->
Gate: **MET** (per-sticker target ≥ 99%).

Coverage: 4 groups, 2 devices, 2 cubes. The Story 10.10 live gate needs at least 2 devices × 2 cubes with six classified faces each; an incomplete matrix yields INCOMPLETE.

The prototype has no uncertainty output; every misread sticker of a classified face counts as confidently wrong. Unavailable captures are excluded from accuracy. INCOMPLETE has no gate verdict.

| Scope | Correct stickers | Sticker accuracy | Correct faces | Face accuracy | Confidently wrong | Unavailable | Missing classified faces |
|---|---:|---:|---:|---:|---:|---:|---|
| Group: lenovo-tb128xu_stka_studio | 54/54 | 100.0000% | 6/6 | 100.0000% | 0 | 0 | — |
| Group: lenovo-tb128xu_stla_studio | 54/54 | 100.0000% | 6/6 | 100.0000% | 0 | 0 | — |
| Group: sm-s721u1_stka_studio | 54/54 | 100.0000% | 6/6 | 100.0000% | 0 | 0 | — |
| Group: sm-s721u1_stla_studio | 54/54 | 100.0000% | 6/6 | 100.0000% | 0 | 0 | — |
| Device: LENOVO Lenovo TB128XU | 108/108 | 100.0000% | 12/12 | 100.0000% | 0 | 0 | see groups |
| Device: samsung SM-S721U1 | 108/108 | 100.0000% | 12/12 | 100.0000% | 0 | 0 | see groups |
| Total | 216/216 | 100.0000% | 24/24 | 100.0000% | 0 | 0 | see groups |
<!-- live-baseline:end -->

Install the debug APK and launch **Cubentia fixture recorder**. In device session A use the Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE, both cubes, normal indoor light, six faces each. Enter the cube and light description, calibrate all six centers, select a face, then independently label all nine truth cells before capture. Truth is never filled from predictions. Capture is disabled until these inputs exist. Each capture saves a self-contained JSON record, then displays its path and prediction/accuracy (or unavailable reason). Recapturing the same face overwrites its file. No image or frame bytes are saved.

Pull the records as described in `Fixtures/README.md`, then run:

```sh
./gradlew :core:domain:evaluateLiveBaseline
```

The non-shipping evaluator uses the unchanged prototype nearest-center classifier. It reports per-group, per-device and total sticker/face accuracy, unavailable captures, missing classified faces and confidently wrong stickers. Every misread classified sticker counts as confidently wrong because the prototype has no uncertainty output. The ≥99% gate is MET or MISSED only when at least two cubes are recorded, at least two devices each have all six faces classified for every recorded cube, and every recorded group has six classified faces; otherwise INCOMPLETE has no verdict and lists the missing device × cube cells. An extra device that does not cover every cube (such as the report-only Galaxy Tab S7 FE) does not block the verdict. Cubes are matched by their entered description (case-insensitive), so type each cube's name identically on every device. With no fixtures the command fails naming `Fixtures/live` and leaves this document untouched. Physical numbers and the PO's subsequent decision remain deferred.

### Device session A notes (2026-11-14/15)

- **Matrix recorded:** Lenovo TB128XU and Galaxy S24 FE (SM-S721U1), studio light, cubes `stla` (stickerless) and `stka` (stickered, logo on the white center). Two more cubes with other shades were skipped by PO decision (calibration effort); the gate needs two.
- **Calibration matters more than the classifier.** A first Lenovo `stka` run calibrated WHITE on a non-white patch (L76 a20 b71). It scored 53/54: one light orange sticker sat nearer that "white" than the real orange center, and per-face margins fell to 4–5 ΔE. After WHITE was recalibrated on a plain sticker (L88 a-6 b-16), the same cube scored 54/54 with margins of 26–34. The kept runs use one calibration per device × cube.
- **Logo centers.** The `stka` white center carries a logo that reads far from white (about L44–77, b-26 to b31 between runs), so WHITE must be metered on a plain white sticker moved into the center cell. The production scanner cannot ask users to do this; it needs patch averaging that rejects the logo, or a known center scheme.
- **Margins by cube:** `stka` (saturated stickers) holds minimum per-face margins of 25–35 on both devices; `stla` (stickerless plastic) holds 13.5 on the S24 FE and 8.3 on the Lenovo (its center logo). Stickerless cubes are the tighter case.
- **Focus at grid distance.** On the S24 FE the cube must be close to fill the drawn grid, and the first autofocus can miss there; **Re-meter** (grid AF/AE/AWB) restored focus. The production viewfinder should re-trigger grid focus on alignment instead of relying on one AF pass.
- **Fixture correction.** Lenovo `stka` D was saved with the capture quality override at MISALIGNED by a mis-tap (PO confirmed); its readings and truth labels were valid. The flag alone was edited to ACCEPTABLE rather than re-rigging the tripod for one face.
- **Truth labels** agree across devices face by face, up to how each face was held (rotations only).
