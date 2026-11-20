# Session C notes (2026-11-20)

Mix on all cubes, from solved (white top, green front): `L' F' D2 B D F2 D' U2`. Light: white LED (cool).

| device | cube | flow | read vs truth | corrections | time | note |
|---|---|---|---|---|---|---|
| Galaxy S24 FE | stkb | 6-face scan | 54/54 | 0 | - | mix check; Accept pressed later |
| Galaxy S24 FE | stka | 6-face scan | 54/54 | 0 | 89.8 s | PO waited before Accept; 19-move solution |
| Galaxy S24 FE | stla | 6-face scan | 54/54 | 0 | 28.7 s | |
| Galaxy S24 FE | stlb | 6-face scan | 54/54 | 0 | 23.0 s | |
| Lenovo | stka | 6-face scan | 54/54 | 0 | 65.5 s | all faces auto-captured but PO saw it struggle; rejected frames: DARK_CELLS 14, PLAIN_OUTER_CELLS 7, LIGHT 3, SHARPNESS 2 (rate-limited log, approximate) |
| Lenovo | stkb | 6-face scan | 54/54 | - | elapsedMs=60173 corrections=0 outcome=ACCEPTED | PO changed background after face 1, seemed better (speculation); rejected frames {"LIGHT":2,"DARK_CELLS":16,"PLAIN_OUTER_CELLS":6,"SEAMS":1} |
| Lenovo | stla | 6-face scan | 54/54 | 0 | 36.1 s | app said colors seem odd and offered to calibrate, yet the first read was already 54/54 (false alarm); rejected frames {"SHARPNESS":9,"SEAMS":6,"DARK_CELLS":1,"PLAIN_OUTER_CELLS":4} |
| Lenovo | stlb | 6-face scan | 54/54 | - | elapsedMs=24129 corrections=0 outcome=ACCEPTED | rejected frames {"SHARPNESS":1,"SEAMS":2,"DARK_CELLS":3,"PLAIN_OUTER_CELLS":4} |

## PO observations
- Camera capture itself is very quick; most of the time is the person finding the next face and lining it up on the grid. (Possible UX follow-up: clearer "next face" cue / orientation hint.)
- Lenovo: background change after the first face seemed to help auto-capture. Needs a controlled experiment (same cube, same light, plain dark vs busy/light background, count rejected frames per face).
- Lenovo stla: "colors seem odd, calibrate?" prompt appeared although initial read = truth (54/54). Likely the saved colors from the stickered cubes don't fit the stickerless plastic. Candidate issue: calibration prompt fires on a correct read after switching cubes. Diag log has no calibration line, so ask PO for a screenshot next time it shows.
- PO agrees on the calibration false alarm. Stickerless cubes scan faster on the Lenovo; they are gapless (no black frame/bands), while stickered cubes have black plastic between stickers. Stickered scans are rejected mostly for DARK_CELLS, which points at the dark-cell check reacting to the black borders. Candidate tuning issue.
- PO: the Lenovo camera gives out-of-focus captures more often. Log "blurry" rejections on Lenovo: stka 2, stkb 0, stla 9, stlb 1 (rate-limited, approximate). Check whether the scan view requests continuous autofocus / a focus point on the grid, and the distance the Lenovo lens focuses at.

## Background experiment (Lenovo, white LED, same mix)

Log files: Fixtures/matrix/experiment_lenovo-tb128xu_*_cool_background.log. "Brightness" = average frame luma in the diag lines.

| cube | background (PO) | time | corrections | rejected frames | of which dark-sticker | brightness |
|---|---|---|---|---|---|---|
| stka | light | 22.7 s | 0 | 8 | 0 | 188 |
| stka | light | 24.5 s | 0 | 11 | 3 | 132 |
| stka | light | 20.3 s | 0 | 6 | 4 | 128 |
| stka | black | 60.4 s | 0 | 17 | 15 | 81 |
| stka | black | 49.0 s | 0 | 14 | 10 | 93 |
| stla | (warm-up, 5 logged vs 4 reported) | 26.8 s | 0 | 12 | 9 | 74 |
| stla | black | 38.6 s | 1 | 12 | 9 | 74 |
| stla | light | 26.7 s | 0 | 14 | 1 | 176 |
| stla | black | 30.2 s | 0 | 16 | 8 | 108 |
| stla | light | 37.1 s | 0 | 17 | 1 | 160 |

The stka 1st (cancelled) run is excluded. stla mapping assumes the first logged run was the slow first capture the PO mentioned; to confirm with PO.

Finding: a black background darkens the frame (luma ~75-110 vs 130-190) and the scan then rejects frames as DARK_CELLS on both stickered and stickerless cubes. The time cost is large on the stickered cube (49-60 s vs 20-25 s), small on the stickerless one. On a light background the stickerless cube's rejections shift to SHARPNESS/SEAMS. Every scan still read 54/54. Candidate issue: make the dark-cell check (and exposure) depend on the face region, not the whole frame/background.

### Statistics (9 runs, warm-up excluded)
- Image brightness vs dark-sticker rejections: Spearman rho = -0.95, exact permutation p = 0.0002. Both measured by the app, so this does not depend on the background labels.
- Background label vs dark-sticker rejections, stratified by cube: black > light in every pair, exact p = 1/60 = 0.017.
- Scan time: stka black 49-60 s vs light 20-25 s, but only 2 vs 3 runs (exact p = 0.10). stla shows no clear time effect. Brightness vs time rho = -0.68.
- To claim the time effect: about 5 runs per background per cube.

## PO decision (2026-11-20)
The Lenovo TB128XU camera is the minimum camera spec for scanning. Cameras below it: not tested, no guarantees.

## Experiment 2 (Lenovo, white LED): fixed order
1-2 light bg stla · 3-4 light bg stka · 5-6 black bg stla · 7-8 black bg stka

## Experiment 2 results so far
- Light bg: 5 accepted runs (one extra, likely the warm-up), 22-31 s, 0 corrections, dark-sticker rejections 0-2, luma 122-172, blurry rejections up to 19.
- Black bg stla: 31.0 s (dark 6, luma 94), 26.9 s (dark 16, luma 103), both 54/54, 0 corrections.
- Black bg stka #1: went very wrong. First read had 9 of each color but was not a valid cube (branch=NO_VALID_REVIEW) and matched the mix on only 37/54 stickers, even allowing per-face rotation; no 1-2 extra turns explain it. Screenshot shots/lenovo-tb128xu_stka_black_wrong.png (taken after PO edits). PO used it to probe the review UI.
- PO then chose to calibrate (first calibration this session). Every later run is "after calibration".

## PO UX feedback on the review/correction screen
- Good: error indicators are now clearly labelled and numbered on the net (fixes from the first live session confirmed).
- Missing: correcting a whole face takes about 16 taps (pick a color, tap a sticker, repeat). Proposal: long-press a palette color to keep it selected ("paint mode"), then tap stickers on the net. Another long-press or a tap on the color ends it.
- The calibrate prompt shows even though no calibration was needed for any earlier scan.

- Black bg stka after calibration: the run with the bad first read ended ACCEPTED, 54/54 and 0 corrections after calibration and a re-capture (458 s including PO's UI probing; excluded from timing). Next run: 35.2 s, 54/54, 0 corrections, dark rejections 13, luma 71. Calibration did not remove the dark-sticker rejections.

## Pooled result, Lenovo, both experiments (light n=10, black n=7; the 458 s run excluded)
| metric | light bg median | black bg median | Mann-Whitney exact p (one-sided) |
|---|---|---|---|
| dark-sticker rejections per scan | 1 | 10 | 0.00005 (complete separation) |
| scan time | 25.1 s | 35.2 s | 0.0015 |

Every accepted scan was 54/54 except the one bad first read on black bg. Caveats: light runs from experiment 2 are not split by cube; one black stka run is after calibration; one device, one light, one operator.
**Conclusion:** on the Lenovo, a black background makes the scan reject good frames as too dark (about 10x more) and makes it about 10 s (40%) slower. Accuracy holds.

## Correction (PO, 2026-11-20): the calibrate prompt
On the bad stka black-bg read, the calibrate prompt was right: calibrating and re-capturing gave 54/54. We never calibrated on any other run, so the earlier prompt (Lenovo stla) can't be called a false alarm. That read was already 54/54, but the prompt may reflect low color margin rather than a wrong read. Not an issue for now. Open question: does calibration change scan time or rejections? 1 data point (stka black bg after calibration 35.2 s vs 49-60 s before) suggests it may help speed. That needs a controlled before/after test.

## Stop (2026-11-20)
- Quick Scan (Part 1) not run. PO: the 3D corner grid still doesn't match the real cube and can't be aligned, so it would not pass. It needs v1.1 stories first.
- Not run: Part 2 (Recognize), Part 3 (Placement from Welcome), Part 4 (retake and robustness), and the calibration before/after test.
- Issues opened 2026-11-20: #247 dark background / DARK_CELLS, #248 Lenovo out-of-focus frames, #249 review-screen paint mode.
- One Galaxy screenshot removed because it showed a personal notification.
