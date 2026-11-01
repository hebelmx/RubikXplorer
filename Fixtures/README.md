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

`labeled/face-samples.json` contains one deterministic **synthetic** CIELAB face sample, six explicit synthetic calibration centers, and explicit nine-sticker truth. It validates the JSON contract and classifier only; it is not derived from the JPEGs and is not camera accuracy evidence. Run it with `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.recognition.ScanFixtureEvaluationTest'`. The live test matrix and scoring rules are in `docs/spikes/oq-4-guided-scan.md`. The 132 JPEGs above remain unlabeled source photos in four capture groups per cube/device combination.

### Offline S3/S4 photo color check

`labeled/photo-evaluation.json` is a separate, visually transcribed dataset: six monochrome S3 calibration faces and six single-face S4 scoring photos for each cube/device pair. It records exact source filenames, S4 sticker-grid rectangles and nine row-major color labels; multi-face views and duplicate S3 colors are explicitly listed as excluded. Labels are grounded in the visible image, never in folder names or state assignment. The S3 and S4 folders are treated as distinct captured lighting groups; no named light level or shared cube state is inferred. Face identity and orientation are unknown.

Run `python3 -m pip install -r tools/requirements-photo-eval.txt` once, then `python3 tools/evaluate_fixture_photos.py` to regenerate `labeled/photo-evaluation-results.json`, the S4 QA overlay `labeled/photo-evaluation-overlay.jpg`, and the S3 calibration overlay `labeled/photo-calibration-overlay.jpg`. Cyan dots show the four patch locations; green/red centers show color match/mismatch. `python3 tools/evaluate_fixture_photos.py --check` checks deterministic output. The current result is **135/216 (62.5000%)** across 24 S4 photos, with 54 stickers per cube/device group. Sampling uses four small patches around each sticker-center region to avoid center logos and matches the prototype D65 CIELAB nearest-center rule. This is offline RGB JPEG evidence only: it does not test CameraX/YUV capture, cube-face identity/orientation, or solve validity. The controlled live-device ≥99% gate remains unrun.
