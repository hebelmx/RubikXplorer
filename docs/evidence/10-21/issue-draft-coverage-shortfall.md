# Draft issue (not filed): Quick Scan side-face coverage is far below the bar on the held-out fixtures

> Draft from Story 10.21 (PO decision 2026-11-23, option (a)). The PO or the orchestrator files it; this story does not.

## Summary

After Story 10.21, Quick Scan never keeps a confidently wrong sticker on a scored held-out group. But it keeps almost no side face. On every scored held-out condition (all Galaxy S24 FE), 0 of 4 side faces are kept and 40–63% of side stickers are read correctly. The bar is at least 3 of 4 faces and at least 90% of stickers. Quick Scan will likely stay OFF at the BETA call until this is fixed.

## Evidence (label-set commit `fd2eff4e`, label exclusions `absent`)

| held-out group (condition) | side stickers correct | side faces kept | ceiling on current reads (10.21 run 3) |
|---|---|---|---|
| `sm-s721u1_stka_bar-cool-3` (cool, torch off) | 20/32 (62.5%) | 0/4 | 24/32, 0/4 |
| `sm-s721u1_stla_bar-cool` (cool, torch on) | 20/32 (62.5%) | 0/4 | 27/32, 1/4 |
| `sm-s721u1_stka_bar-warm-2` (warm, torch off) | 13/32 (40.6%) | 0/4 | 28/32, 1/4 |

This is a regression against the 10.19 gate: Story 10.21's tuned gate reads fewer side stickers correctly on two of the three scored groups (`sm-s721u1_stka_bar-cool-3` 23 → 20 of 32, `sm-s721u1_stka_bar-warm-2` 16 → 13 of 32; `sm-s721u1_stla_bar-cool` unchanged at 20 of 32), and keeps 18 of 156 tuning faces where the 10.19 gate kept 43.

The ceiling is the best any gate allowed by the story's ACs could do. It counts every sticker that some read gets right as correct.

## Causes found

1. **Blue reads as White on the S24 FE.** Blue stickers (h1 F1, F6, h2 L2 on `stka_bar-warm-2`; h1 F1, F6, R7 on `stka_bar-cool-3`) are nearest White in both reads. No gate can supply the right colour.
2. **Every S24 FE recorder calibration fails the centre check.** The Blue centre reads as White CONFIDENT, or the Red or Orange centre reads UNCERTAIN, so the saved calibration is never used. The 1-blank keep rule never applies there, and every face needs all 9 stickers CONFIDENT.
3. **The Orange margin (21.3, fitted on the tuning split).** A red read confidently as orange by both reads on a kept tuning face (`sm-s721u1_stla_bar-warm-2` h1 F2) forces it. With no saved calibration, the light-normalized second read lowers Orange margins on faces whose colour mix differs from the source face. Many correct oranges are left uncertain as a result.
4. **Red ↔ Orange confusions** under cool light (`stka_bar-cool-3` h1 F9, R6, R8; `stla_bar-cool` h1 R1).
5. **Off-face samples** in three held-out groups (`sm-s721u1_stla_bar-warm`, `lenovo-tb128xu_stla_bar-cool`, `lenovo-tb128xu_stla_bar-warm`, all hold 2 `L`). They corrupt the self-calibration's orange reference. As a result, the Lenovo has no scored held-out front-light condition at all. These go to Story 10.27, #315 and #338.

## Possible directions (for planning, not decided)

- Blue vs White separation on the S24 FE: white balance or exposure at capture, or a chroma-weighted distance for the Blue/White pair.
- A calibration-match rule that tolerates a centre that is UNCERTAIN but still nearest its own colour, while staying safe under other-cube and other-light calibrations.
- A second read that does not treat colour mix as light, so the normalized read stops costing Orange margin.
- New held-out recordings for the Lenovo front-light conditions, with real corners.

## Links

- Evidence: `docs/evidence/10-21/tuning-and-replay.md`, `core/domain/build/reports/quick-scan/replay-10-21.md`
- Stories: 10.21 (this), 10.27 (labels and pastel), 10.26 (cube-rule fill, after the BETA call); issues #315, #338
