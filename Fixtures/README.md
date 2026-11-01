# Cube photo fixtures

This directory contains 64 original JPEG photos of two physical 3×3 cubes, photographed with two Samsung devices. Each photo shows one cube from one camera view. The eight subfolders organize the photos by cube, device, and the first or second capture state.

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

All photos were taken on 2026-10-31. Times above are local camera times from EXIF `DateTimeOriginal`.

## State assignment

`S1` means the first chronological pass through the four device/cube combinations. It runs from 13:32:36 to 13:36:35. `S2` means the later pass, from 13:41:59 to 13:46:14. Within each pass, the next combination starts about a minute after the previous one. This order is the evidence for matching S1 and S2 across devices and cube types. The labels describe capture groups; they are not a solver-verified comparison of every sticker in the cube state.

The uploaded folders originally used V1/V2 labels inconsistently. The folders here use S1/S2 according to the timestamps. One Galaxy Tab GAN photo, `20261031_134358.jpg`, was originally in the early-labeled V2 folder, but its EXIF time places it at the start of the later capture pass, so it is in `GANStickerless_GalaxyTab_S2/`.

## Filenames and metadata

Original timestamp filenames are retained. Their `YYYYMMDD_HHMMSS.jpg` portion can be used to sort photos approximately by capture time. All 64 photos contain EXIF `DateTimeOriginal`, and the filename time agrees within one second; four filenames are one second earlier than the EXIF value. Use the EXIF time when a second-level tie matters.

The Galaxy S24 FE photos report camera model `Galaxy S24 FE`; Galaxy Tab photos report `SM-T738U`. All timestamps are local. Twenty-six Galaxy Tab photos omit `OffsetTimeOriginal`; the others report `-06:00`.

`state-assignment.csv` is the audit trail for this organization. Each row gives the previous folder name, filename, EXIF timestamp, timezone offset when present, camera model, assigned state, and destination folder.

## Intended use and limits

These are multi-view camera fixtures for cube framing, lighting, focus, and recognition work. Views vary in angle, distance, sharpness, and illumination. The photos are not labeled face scans, and this dataset does not include a manually verified 54-facelet cube state for each scramble. Do not treat folder names as face orientation or color ground truth.

## Guided scan spike fixtures

`labeled/face-samples.json` contains one deterministic **synthetic** CIELAB face sample, six explicit synthetic calibration centers, and explicit nine-sticker truth. It validates the JSON contract and classifier only; it is not derived from the JPEGs and is not camera accuracy evidence. Run it with `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.recognition.ScanFixtureEvaluationTest'`. The live test matrix and scoring rules are in `docs/spikes/oq-4-guided-scan.md`. The original 64 JPEGs above remain unlabeled source-only data.
