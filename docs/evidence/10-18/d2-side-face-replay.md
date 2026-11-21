# Story 10.18 device gate D2: Quick Scan side-face replay

Recorded 2026-11-21 by the PO on the Lenovo TB128XU (minimum device) with the debug FixtureRecorder: four cubes
(stla = GAN, stlb = other Chinese, stka = Rubik's with logo, stkb = generic sticker) in cool light, plus stka and stla in
warm light (recorded while this analysis ran, so they served as an untouched held-out set), two corner holds each, in
`Fixtures/corner/lenovo-tb128xu_<cube>_<light>/`. Every face was ACCEPTABLE ("clean") at the shutter.

Replay: `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanSideFaceReplayTest.kt` feeds each
fixture's unwarped per-sticker Lab through the production path Quick Scan runs after the unwarp
(`SelfCalibration.from` over both holds with the recorder calibration as `saved`, then `QuickScanReadout`, the
classification the fallback decision in `QuickScanController` now calls). Counts are the 8 non-center cells per face;
"uncertain" is a cell that is not CONFIDENT, so its face goes to the 2D Guided grid.

## AC3 bar (side faces, clean frames)

1. 0 RED<->ORANGE confidently wrong reads.
2. 0 RED or ORANGE cells uncertain (forced to the 2D fallback).
3. Other colors read like a frontal face: >= 90% correct and <= 2% confidently wrong (top faces read 62/64 = 97%
   against the recorder calibration; 90% leaves room for the side faces' shading, and it is not met today).

## Baseline (current code, `5f0ae132` + fixtures)

| cube | top correct/uncertain/wrong | side correct/uncertain/wrong | side R<->O wrong | side R/O uncertain | side faces to grid |
|---|---|---|---|---|---|
| stka | 13/3/0 | 23/8/1 | 0 | 4 | 4/4 |
| stkb | 13/3/0 | 25/6/1 | 0 | 3 | 4/4 |
| stla | 8/8/0 | 13/17/2 | 0 | 11 | 4/4 |
| stlb | 7/7/2 | 20/11/1 | 0 | 4 | 4/4 |
| **all cool** | **41/21/2 of 64** | **81/42/5 of 128** | **0** | **22** | **16/16** |
| stka warm | 13/3/0 | 23/9/0 | 0 | 4 | 4/4 |
| stla warm | 14/2/0 | 17/5/10 | 1 | 1 | 3/4 |
| **all (cool + warm)** | **68/26/2 of 96** | **121/56/15 of 192** | **1** | **27** | **23/24** |

The bar fails on every item with warm light included (cool light alone: items 2 and 3). In cool light there is no
confident RED/ORANGE swap, but 22 RED/ORANGE side cells are uncertain and every side face (and 6 of 8 top faces) would
fall back to the Guided grid; stla in warm light reads 10 side cells confidently wrong, one of them RED<->ORANGE. The replay test asserts this gap as a strict expected
failure; `CUBENTIA_D2_ENFORCE=1 ./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*' --rerun` runs it
as the real bar, which is red.

## Why side faces misread

- **Self-calibration mixes two illuminations.** Quick Scan calibrates each color from that face's own center. RED,
  GREEN (hold 1) and ORANGE, BLUE (hold 2) come from shaded side faces; WHITE, YELLOW from the lit tops. Example stla:
  RED center L57 a30 b28 (side) vs L58 a62 b33 in the recorder calibration (frontal). Top-face stickers are then read
  against shaded centers too, which is why tops lose confidence (41/64; 62/64 with the recorder calibration).
- **Shading is not one gain per face.** Within a single side face, same-color stickers spread by 15-20 ΔE (stla hold 1
  F face: WHITE at L39 and L68), so a face-wide luminance/white-balance correction cannot line them all up.
- **RED and ORANGE overlap on the side faces.** Side-face RED is desaturated and pulled toward ORANGE's hue (stka RED
  hue 30-47° vs ORANGE 52-61°; stla RED 35-47° vs ORANGE 41-58°). Smallest distance between a RED and an ORANGE side cell on
  the same face: stla cool 7.0 ΔE (hold 1 R face: RED L57 a30 b28 vs ORANGE L61 a35 b31), stka cool 14.7, stla warm
  14.9, stlb cool 21.4, stkb cool 22.3, stka warm 26.4.
  A pair 7 ΔE apart is under the 10 ΔE close-call rule, so any classifier on these Lab values either reads one of them
  wrong or leaves one uncertain: bar item 2 cannot be met by classification alone on stla.
- The stkb white calibrated as a cyan cast (L87 a-30 b-12) is consistent across top and side faces, so the cast itself
  did not cause misreads (stkb tops 13/3/0).

## Candidate fixes measured (not shipped)

All candidates are parameter-free (no threshold was fitted to these fixtures), so every cube is effectively held out
(leave-one-cube-out gives the same numbers); the warm-light groups arrived after the candidates were chosen and are a
strict held-out set. Side faces: correct/uncertain/wrong, R<->O wrong, R/O uncertain.

| candidate | stka cool | stkb cool | stla cool | stlb cool | all cool | held-out warm (stka + stla) |
|---|---|---|---|---|---|---|
| baseline (self-calibration) | 23/8/1, 0, 4 | 25/6/1, 0, 3 | 13/17/2, 0, 11 | 20/11/1, 0, 4 | 81/42/5, 0, 22 | 40/14/10, 1, 5 |
| recorder (saved) calibration only | 22/7/3, 0, 4 | 19/12/1, 0, 4 | 23/9/0, 0, 5 | 17/15/0, 0, 9 | 81/43/4, 0, 22 | 53/7/4, 0, 2 |
| per-face luminance gain from the known center to the saved center | 25/3/4, 1, 3 | 23/7/2, 1, 2 | 26/3/3, 1, 2 | 27/5/0, 0, 4 | 101/18/9, 3, 11 | 54/6/4, 0, 4 |
| per-face von Kries (per-channel) gain, same reference | 10/2/20, 3, 1 | 21/7/4, 1, 4 | 15/9/8, 1, 4 | 17/10/5, 0, 4 | 63/28/37, 5, 13 | 30/17/17, 0, 5 |
| L shift only, same reference | 25/2/5, 2, 2 | 18/12/2, 1, 3 | 24/4/4, 1, 2 | 24/8/0, 0, 5 | 91/26/11, 4, 12 | 48/11/5, 0, 4 |
| luminance-invariant chromaticity (rgb ratios) | 20/12/0, 0, 10 | 21/8/3, 3, 2 | 12/20/0, 0, 11 | 16/16/0, 0, 14 | 69/56/3, 3, 37 | 40/18/6, 0, 10 |

The best candidate (per-face luminance gain) halves the uncertain cells (held-out warm: 54/6/4 vs 40/14/10) but turns
3 cool-light RED/ORANGE cells (one per cube on three of four cubes) from "uncertain, ask the grid" into confident wrong
reads, still misses the 90% / 2% bar on both sets, which is worse for the Learner than a
fallback, and it needs a saved calibration that Quick Scan does not require. No candidate meets the bar, so production
classification is unchanged; only the fallback decision's classification moved into `QuickScanReadout` (behavior
unchanged) so the replay exercises production code.

## Decision needed

Meeting AC3 needs more than a classifier change: e.g. per-face side calibration (calibrate RED/ORANGE as seen on a
shaded side face), a different capture (a light/angle that lights both side faces, or a per-face exposure), or
accepting that RED/ORANGE side cells go to the Guided fallback and revising AC3. That is a scope/UX decision for
`bmad-correct-course`.
