# Cube photo fixtures

This directory contains 132 original JPEG photos of two physical 3×3 cubes, photographed with two Samsung devices. Each photo shows one cube from one camera view. The 16 populated subfolders organize photos by cube, device, and capture group S1 through S4 (four groups for each cube/device combination).

## Folder contents

| Folder | What is pictured | Camera model | Photos | EXIF capture times |
| --- | --- | --- | ---: | --- |
| `GANStickerless_GalaxyS24FE_S1/` | Stickerless GAN cube, first capture state | Galaxy S24 FE | 8 | 13:32:36–13:33:25 |
| `RubikStickered_GalaxyS24FE_S1/` | Stickered Rubik cube, first capture state | Galaxy S24 FE | 5 | 13:33:31–13:34:05 |
| `RubikStickered_GalaxyTab_S1/` | Stickered Rubik cube, first capture state | Galaxy Tab (`SM-T738U`) | 9 | 13:34:54–13:35:45 |
| `GANStickerless_GalaxyTab_S1/` | Stickerless GAN cube, first capture state | Galaxy Tab (`SM-T738U`) | 7 | 13:35:52–13:36:35 |
| `RubikStickered_GalaxyS24FE_S2/` | Stickered Rubik cube, second capture state | Galaxy S24 FE | 9 | 13:41:59–13:42:46 |
| `GANStickerless_GalaxyS24FE_S2/` | Stickerless GAN cube, second capture state | Galaxy S24 FE | 7 | 13:43:01–13:43:33 |
| `GANStickerless_GalaxyTab_S2/` | Stickerless GAN cube, second capture state | Galaxy Tab (`SM-T738U`) | 8 | 13:43:58–13:44:50 |
| `RubikStickered_GalaxyTab_S2/` | Stickered Rubik cube, second capture state | Galaxy Tab (`SM-T738U`) | 11 | 13:44:59–13:46:14 |

The S1/S2 photos were taken on 2026-10-31. Times above are local camera times from EXIF `DateTimeOriginal`.

## S3 and S4 folder contents

| Folder | What is pictured | Camera model | Photos | Filename capture times |
| --- | --- | --- | ---: | --- |
| `RubikStickered_GalaxyS24FE_S3/` | Stickered Rubik cube, third capture state | Galaxy S24 FE | 9 | 16:29:28–16:33:11 |
| `GANStickerless_GalaxyS24FE_S3/` | Stickerless GAN cube, third capture state | Galaxy S24 FE | 8 | 16:29:59–16:33:39 |
| `GANStickerless_GalaxyTab_S3/` | Stickerless GAN cube, third capture state | Galaxy Tab (`SM-T738U`) | 10 | 16:31:13–16:32:50 |
| `RubikStickered_GalaxyTab_S3/` | Stickered Rubik cube, third capture state | Galaxy Tab (`SM-T738U`) | 8 | 16:32:43–17:03:50 |
| `RubikStickered_GalaxyS24FE_S4/` | Stickered Rubik cube, fourth capture state | Galaxy S24 FE | 8 | 16:38:52–16:39:38 |
| `GANStickerless_GalaxyS24FE_S4/` | Stickerless GAN cube, fourth capture state | Galaxy S24 FE | 9 | 16:39:45–16:40:54 |
| `GANStickerless_GalaxyTab_S4/` | Stickerless GAN cube, fourth capture state | Galaxy Tab (`SM-T738U`) | 8 | 16:41:59–16:42:37 |
| `RubikStickered_GalaxyTab_S4/` | Stickered Rubik cube, fourth capture state | Galaxy Tab (`SM-T738U`) | 8 | 16:42:52–16:43:21 |



## Capture states

S1 through S4 label four capture groups for each cube/device combination. Each folder keeps photos of one cube state from that capture group; rotating the whole cube is fine, but do not turn its layers between views. The S3 Galaxy Tab stickered set contains photos from 16:32 and 17:03; both timestamp groups are retained in that folder.


## State assignment

`S1` is the first chronological pass through the four device/cube combinations (13:32–13:36); `S2` is the later pass (13:41–13:46). S3 and S4 are the third and fourth capture groups. Capture order and timestamps help audit which folder a photo belongs to, but do not prove that sticker arrangements match across devices. The labels describe capture groups; they are not a solver-verified comparison of every sticker in the cube state.

The uploaded folders originally used V1/V2 labels inconsistently. The folders here use S1/S2 according to the timestamps. One Galaxy Tab GAN photo, `20261031_134358.jpg`, was originally in the early-labeled V2 folder, but its EXIF time places it at the start of the later capture pass, so it is in `GANStickerless_GalaxyTab_S2/`.

## Filenames and metadata

Original timestamp filenames are retained. Their `YYYYMMDD_HHMMSS.jpg` portion can be used to sort photos approximately by capture time. The original S1/S2 photos contain EXIF `DateTimeOriginal`; in four cases, the filename is one second earlier than the EXIF value. Use EXIF time when a second-level tie matters. S3/S4 folder counts and filename time ranges are listed above.

The Galaxy S24 FE photos report camera model `Galaxy S24 FE`; Galaxy Tab photos report `SM-T738U`. All timestamps are local. Some Galaxy Tab photos omit `OffsetTimeOriginal`; where present, the offset is `-06:00`.

`state-assignment.csv` records the original S1/S2 reorganization. Each row gives the previous folder name, filename, EXIF timestamp, timezone offset when present, camera model, assigned state, and destination folder. S3/S4 photos retain their uploaded folder placement and timestamp filenames.

## Intended use and limits

These are source photos organized into multi-view capture groups for cube framing, lighting, focus, and recognition work. Views vary in angle, distance, sharpness, and illumination; lighting conditions were not metered. The S3/S4 subset described below has visual per-sticker color labels only. Other images remain source-only, and there are no face-identity/orientation labels or manually verified 54-facelet cube states. Do not treat folder names as face orientation or color ground truth. The photo subset does not constitute a live CameraX sticker-accuracy measurement.

## Guided scan spike fixtures

`labeled/face-samples.json` contains one deterministic **synthetic** CIELAB face sample, six explicit synthetic calibration centers, and explicit nine-sticker truth. It validates the JSON contract and classifier only; it is not derived from the JPEGs and is not camera accuracy evidence. Run it with `./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.ScanFixtureEvaluationTest'`. The live test matrix and scoring rules are in `docs/spikes/oq-4-guided-scan.md`. The 132 JPEGs above remain unlabeled source photos in four capture groups per cube/device combination.

### Offline S3/S4 photo color check

`labeled/photo-evaluation.json` is a separate, visually transcribed dataset: six monochrome S3 calibration faces and six single-face S4 scoring photos for each cube/device pair. It records exact source filenames, S4 sticker-grid rectangles and nine row-major color labels; multi-face views and duplicate S3 colors are explicitly listed as excluded. Labels are grounded in the visible image, never in folder names or state assignment. The S3 and S4 folders are treated as distinct captured lighting groups; no named light level or shared cube state is inferred. Face identity and orientation are unknown.

Run `python3 -m pip install -r tools/requirements-photo-eval.txt` once, then `python3 tools/evaluate_fixture_photos.py` to regenerate `labeled/photo-evaluation-results.json`, the S4 QA overlay `labeled/photo-evaluation-overlay.jpg`, and the S3 calibration overlay `labeled/photo-calibration-overlay.jpg`. Cyan dots show the four patch locations; green/red centers show color match/mismatch. `python3 tools/evaluate_fixture_photos.py --check` checks deterministic output. The current result is **135/216 (62.5000%)** across 24 S4 photos, with 54 stickers per cube/device group. Sampling uses four small patches around each sticker-center region to avoid center logos and matches the prototype D65 CIELAB nearest-center rule. This is offline RGB JPEG evidence only: it does not test CameraX/YUV capture, cube-face identity/orientation, or solve validity. The controlled live-device ≥99% gate remains unrun.


### Live recorder fixtures

`live/<device>_<cube>_<light>/<FACE>.json` contains one self-contained record per face (U, R, F, D, L, B). Each directory part is lowercase ASCII letters/digits with other runs collapsed to hyphens and trimmed; empty parts are rejected. Records include schema version, model/manufacturer, OS release/build, SDK, ISO-8601 capture time, cube/light descriptions, six calibration centers and a labeled CIELAB FaceSample. Schema 2 (Story 10.12) adds `truthSources`, nine `"entered"`/`"prefilled"` values saying whether the operator set each sticker or kept a close-call-gated prefill, and `frame`, the sibling `<FACE>.jpg` (the measured frame, long edge ≤ 1024 px). Schema-1 records (session A) have neither and count every sticker as entered. Recapture overwrites both files.

> Story 10.35 retired the debug fixture recorder (and the `tools/cube-session.sh recorder|pull` commands); the recording steps below are kept as history. The fixtures stay readable; see `docs/evidence/10-35/legacy-replay.md` for the replay commands and the last revision that contains the recorder.

After recording with the debug **Cubentia fixture recorder**, pull into the repository root:

```sh
adb pull /sdcard/Android/data/com.exxerpro.cubentia/files/Fixtures/live Fixtures/
./gradlew :core:domain:evaluateLiveBaseline
```

Each JPEG is the analysis frame's measured crop in the sample's pixel orientation (apply the record's `orientationDegrees` to view it upright); the recorder's grid is the centered square of 80% of its short edge, split 3×3, and the calibration ring sits at 30% of a cell side around the middle cell's center.

**Session A2 reuses session A names.** The same device, cube (`stla`, `stka`) and light (`studio`) give the same `<device>_<cube>_<light>` directory, so on the device the recorder would mark session A faces as already captured, and the pull would overwrite the checked-in session A records. Before A2, clear the app's on-device `Fixtures/live` (`adb shell rm -r /sdcard/Android/data/com.exxerpro.cubentia/files/Fixtures/live`) and enter a distinct light description such as `studio-a2`.

The pull brings the JPEG frames with the JSON. Review the exported metadata, truth and frames before checking in device-session records; the evaluator reads only the JSON and reports all-sticker and entered-only accuracy. The evaluator updates only the marked Live baseline section in `docs/spikes/oq-4-guided-scan.md`.

### Scan accuracy (Story 10.1)

`./gradlew :core:domain:evaluateScanAccuracy` runs the same `live/` records through the product scan pipeline of OQ-7 decision K: the all-Kotlin cell reader on the A2 frames (ImageIO decode, sRGB → CIELAB, histogram mode, no OpenCV) and on the recorded center-pixel samples, then self-calibration from the six centers and assembly into URFDLB. It rewrites only the generated span of `docs/scan-accuracy.md` (per-device sticker and scan accuracy, confidently wrong, the logo-center guard). It is on demand and not part of `build`.

### Quick Scan corner fixtures

> Story 10.35 retired the debug fixture recorder (and the `tools/cube-session.sh recorder|pull` commands); the recording steps below are kept as history. The fixtures stay readable; see `docs/evidence/10-35/legacy-replay.md` for the replay commands and the last revision that contains the recorder.

In the debug fixture recorder's Cube and light dialog, enable **Corner mode**.
Calibrate the six colors, then align white on top, green on the left and red
on the right. Capture corner 1 and confirm the nine labels on each of its three
faces. For corner 2, align yellow on top, orange on the left and blue on the
right. Capture and confirm its labels the same way. The recorder saves each
hold as a schema-3 JSON file alongside its downscaled upright JPEG.

```sh
adb pull /sdcard/Android/data/com.exxerpro.cubentia/files/Fixtures/corner Fixtures/
```

### Continuous Quick Scan sessions (Story 10.34)

`continuous/<sessionId>/` holds operator-curated sessions recorded from the debug build's Solve-tab Quick Scan (its Rec
control): a manifest written last, one neutral observation line per frame and one mapper snapshot line per read frame,
optional operator `labels.json` (truth, separate from predictions) and every third frame's 256 × 256 model-input PNG.
`./gradlew :checkFixtureSize` (in `check`) caps each session at 15,000,000 B and the folder at 60,000,000 B. Replay with
`./gradlew :core:domain:replayContinuousSession`, the replacement for the Quick Scan use of `exportKotlinStickerReads`;
the format, operator procedure (Rec, sheet, baseline, notice, labeller, adb), partition rule, golden refresh and image
replay are in [`continuous/README.md`](continuous/README.md). The two checked-in `synthetic-*-v1` sessions are generated
from `SyntheticCubeStream` and flagged `synthetic: true`.
