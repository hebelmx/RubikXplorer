# Story 10.19 evidence: light check thresholds and side-face replay

Generated from `QuickScanSideFaceReplayTest` (`./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*'`) on the
pre-10.19 Lenovo TB128XU captures in `Fixtures/corner/` (8 groups, 2 holds each, schema 3, no `group.json`). The replay
prints this report on every run; re-run it after the device session to refresh the numbers.

## Light check (`SideLight`)

- **Light level** of a face: the 75th-percentile L* of its nine stickers (linear interpolation; with nine stickers it is
  the 7th lightest).
- **Check:** fails when the darker side face's level is more than `MAX_SIDE_DARKER_L` = **27 L*** below the top face's
  (PO 2026-11-21 D: side faces against the top face only, no background measurement).
- **Derivation:** the cut sits between the front-lit maximum (25.0, `stka warm` hold 2) and the backlit holds (28.6 and
  up), so it passes all 8 front-lit holds and trips at least one hold in every backlit group (7 of 8 backlit holds;
  `stla cool` hold 1 measures 17.2 and passes). The replay asserts exactly this on the legacy groups.

Darker side face below the top face, per group and hold (legacy light labels: `warm` and `cool-front` front-lit,
`cool` backlit):

| group | direction | scored | hold 1 | hold 2 |
|---|---|---|---|---|
| lenovo-tb128xu_stka_cool | back | false | 32.4 FAIL | 30.2 FAIL |
| lenovo-tb128xu_stka_cool-front | front | false | 23.0 pass | 22.7 pass |
| lenovo-tb128xu_stka_warm | front | false | 23.3 pass | 25.0 pass |
| lenovo-tb128xu_stkb_cool | back | false | 29.1 FAIL | 28.7 FAIL |
| lenovo-tb128xu_stla_cool | back | false | 17.2 pass | 36.3 FAIL |
| lenovo-tb128xu_stla_cool-front | front | false | 21.6 pass | 20.9 pass |
| lenovo-tb128xu_stla_warm | front | false | 13.8 pass | 24.6 pass |
| lenovo-tb128xu_stlb_cool | back | false | 32.8 FAIL | 28.6 FAIL |

## Side-face replay

Production path: `QuickScanClassification.needGrid` (quality plus the gated classification) decides which faces go to
the Guided grid ("kept" = not sent); `QuickScanClassification.read` gives the gated readings. Side cells are the 8
non-center stickers of the two side faces of each hold (32 per group). "wrong on kept faces" counts every confidently
wrong sticker on a kept face (top faces included). Per color: correct/uncertain/wrong side stickers by truth color
(Yellow and Orange in bold). "other light" and "other cube" pool every such calibration recorded on the same device.
"matching" is the group's own recorder calibration, which is taken flat, so it is also the "frontal calibration on
angled side faces" scenario.

| group / scenario | side correct/uncertain/wrong | side faces kept | wrong on kept faces | R<->O on kept | per color c/u/w |
|---|---|---|---|---|---|
| lenovo-tb128xu_stka_cool / no saved calibration | 16/15/1 of 32 | 0/4 | 0 | 0 | White 1/4/1 **Yellow** 3/4/0 Green 5/0/0 Blue 4/0/0 Red 0/5/0 **Orange** 3/2/0 |
| lenovo-tb128xu_stka_cool / matching (frontal calibration on angled side faces) | 17/15/0 of 32 | 0/4 | 0 | 0 | White 5/1/0 **Yellow** 3/4/0 Green 5/0/0 Blue 1/3/0 Red 0/5/0 **Orange** 3/2/0 |
| lenovo-tb128xu_stka_cool / other light, same cube | 31/33/0 of 64 | 0/8 | 0 | 0 | White 9/3/0 **Yellow** 5/9/0 Green 10/0/0 Blue 4/4/0 Red 0/10/0 **Orange** 3/7/0 |
| lenovo-tb128xu_stka_cool / other cube | 58/101/1 of 160 | 0/20 | 0 | 0 | White 22/7/1 **Yellow** 12/23/0 Green 6/19/0 Blue 6/14/0 Red 0/25/0 **Orange** 12/13/0 |
| lenovo-tb128xu_stka_cool-front / no saved calibration | 23/8/1 of 32 | 0/4 | 0 | 0 | White 5/0/0 **Yellow** 6/0/0 Green 5/0/0 Blue 5/0/0 Red 0/5/0 **Orange** 2/3/1 |
| lenovo-tb128xu_stka_cool-front / matching (frontal calibration on angled side faces) | 18/14/0 of 32 | 0/4 | 0 | 0 | White 5/0/0 **Yellow** 2/4/0 Green 4/1/0 Blue 5/0/0 Red 0/5/0 **Orange** 2/4/0 |
| lenovo-tb128xu_stka_cool-front / other light, same cube | 30/34/0 of 64 | 0/8 | 0 | 0 | White 10/0/0 **Yellow** 1/11/0 Green 9/1/0 Blue 8/2/0 Red 0/10/0 **Orange** 2/10/0 |
| lenovo-tb128xu_stka_cool-front / other cube | 58/101/1 of 160 | 0/20 | 0 | 0 | White 22/3/0 **Yellow** 7/23/0 Green 3/22/0 Blue 18/7/0 Red 0/25/0 **Orange** 8/21/1 |
| lenovo-tb128xu_stka_warm / no saved calibration | 20/12/0 of 32 | 0/4 | 0 | 0 | White 4/1/0 **Yellow** 4/3/0 Green 5/0/0 Blue 1/4/0 Red 2/4/0 **Orange** 4/0/0 |
| lenovo-tb128xu_stka_warm / matching (frontal calibration on angled side faces) | 21/11/0 of 32 | 0/4 | 0 | 0 | White 4/1/0 **Yellow** 4/3/0 Green 5/0/0 Blue 3/2/0 Red 1/5/0 **Orange** 4/0/0 |
| lenovo-tb128xu_stka_warm / other light, same cube | 26/38/0 of 64 | 0/8 | 0 | 0 | White 8/2/0 **Yellow** 8/6/0 Green 2/8/0 Blue 2/8/0 Red 0/12/0 **Orange** 6/2/0 |
| lenovo-tb128xu_stka_warm / other cube | 72/88/0 of 160 | 0/20 | 0 | 0 | White 19/6/0 **Yellow** 20/15/0 Green 1/24/0 Blue 9/16/0 Red 3/27/0 **Orange** 20/0/0 |
| lenovo-tb128xu_stkb_cool / no saved calibration | 21/10/1 of 32 | 0/4 | 0 | 0 | White 6/0/0 **Yellow** 2/3/0 Green 5/0/0 Blue 3/3/0 Red 1/3/1 **Orange** 4/1/0 |
| lenovo-tb128xu_stkb_cool / matching (frontal calibration on angled side faces) | 17/14/1 of 32 | 0/4 | 0 | 0 | White 6/0/0 **Yellow** 1/4/0 Green 2/3/0 Blue 3/3/0 Red 0/4/1 **Orange** 5/0/0 |
| lenovo-tb128xu_stkb_cool / other light, same cube | n/a (no such calibration recorded) | | | | |
| lenovo-tb128xu_stkb_cool / other cube | 103/114/7 of 224 | 0/28 | 0 | 0 | White 42/0/0 **Yellow** 0/35/0 Green 29/6/0 Blue 10/32/0 Red 1/27/7 **Orange** 21/14/0 |
| lenovo-tb128xu_stla_cool / no saved calibration | 13/17/2 of 32 | 0/4 | 0 | 0 | White 2/3/2 **Yellow** 3/3/0 Green 5/0/0 Blue 3/0/0 Red 0/5/0 **Orange** 0/6/0 |
| lenovo-tb128xu_stla_cool / matching (frontal calibration on angled side faces) | 9/23/0 of 32 | 0/4 | 0 | 0 | White 2/5/0 **Yellow** 3/3/0 Green 4/1/0 Blue 0/3/0 Red 0/5/0 **Orange** 0/6/0 |
| lenovo-tb128xu_stla_cool / other light, same cube | 21/43/0 of 64 | 0/8 | 0 | 0 | White 4/10/0 **Yellow** 4/8/0 Green 10/0/0 Blue 3/3/0 Red 0/10/0 **Orange** 0/12/0 |
| lenovo-tb128xu_stla_cool / other cube | 56/100/4 of 160 | 0/20 | 0 | 0 | White 10/21/4 **Yellow** 15/15/0 Green 25/0/0 Blue 6/9/0 Red 0/25/0 **Orange** 0/30/0 |
| lenovo-tb128xu_stla_cool-front / no saved calibration | 23/9/0 of 32 | 1/4 | 0 | 0 | White 5/0/0 **Yellow** 4/0/0 Green 5/0/0 Blue 3/3/0 Red 3/3/0 **Orange** 3/3/0 |
| lenovo-tb128xu_stla_cool-front / matching (frontal calibration on angled side faces) | 27/5/0 of 32 | 2/4 | 0 | 0 | White 5/0/0 **Yellow** 4/0/0 Green 5/0/0 Blue 5/1/0 Red 4/2/0 **Orange** 4/2/0 |
| lenovo-tb128xu_stla_cool-front / other light, same cube | 46/18/0 of 64 | 2/8 | 0 | 0 | White 10/0/0 **Yellow** 8/0/0 Green 10/0/0 Blue 8/4/0 Red 6/6/0 **Orange** 4/8/0 |
| lenovo-tb128xu_stla_cool-front / other cube | 114/46/0 of 160 | 5/20 | 0 | 0 | White 23/2/0 **Yellow** 20/0/0 Green 25/0/0 Blue 18/12/0 Red 12/18/0 **Orange** 16/14/0 |
| lenovo-tb128xu_stla_warm / no saved calibration | 16/11/5 of 32 | 0/4 | 0 | 0 | White 3/1/1 **Yellow** 3/2/1 Green 1/2/2 Blue 0/5/0 Red 5/1/0 **Orange** 4/0/1 |
| lenovo-tb128xu_stla_warm / matching (frontal calibration on angled side faces) | 14/14/4 of 32 | 0/4 | 0 | 0 | White 3/1/1 **Yellow** 3/2/1 Green 0/3/2 Blue 1/4/0 Red 3/3/0 **Orange** 4/1/0 |
| lenovo-tb128xu_stla_warm / other light, same cube | 21/38/5 of 64 | 0/8 | 0 | 0 | White 6/2/2 **Yellow** 6/6/0 Green 0/8/2 Blue 0/9/1 Red 7/5/0 **Orange** 2/8/0 |
| lenovo-tb128xu_stla_warm / other cube | 66/76/18 of 160 | 0/20 | 0 | 0 | White 14/6/5 **Yellow** 15/12/3 Green 3/14/8 Blue 2/21/2 Red 17/13/0 **Orange** 15/10/0 |
| lenovo-tb128xu_stlb_cool / no saved calibration | 18/14/0 of 32 | 0/4 | 0 | 0 | White 2/3/0 **Yellow** 0/5/0 Green 4/0/0 Blue 2/2/0 Red 2/4/0 **Orange** 8/0/0 |
| lenovo-tb128xu_stlb_cool / matching (frontal calibration on angled side faces) | 16/16/0 of 32 | 0/4 | 0 | 0 | White 2/3/0 **Yellow** 0/5/0 Green 3/1/0 Blue 2/2/0 Red 1/5/0 **Orange** 8/0/0 |
| lenovo-tb128xu_stlb_cool / other light, same cube | n/a (no such calibration recorded) | | | | |
| lenovo-tb128xu_stlb_cool / other cube | 88/133/3 of 224 | 0/28 | 0 | 0 | White 11/24/0 **Yellow** 0/32/3 Green 26/2/0 Blue 8/20/0 Red 7/35/0 **Orange** 36/20/0 |

### Reading

- **Safety holds in every scenario:** 0 confidently wrong stickers and 0 Red<->Orange confusions on kept faces, for every
  group, with no calibration, the matching one, another light's and another cube's. Every confidently wrong side
  sticker is on a face sent to the Guided grid (a safe fallback, PO 2026-11-21 C).
- **Coverage is low on these captures:** the agreement gate sends most side faces to the grid (best: `stla cool-front`
  2/4 with the matching calibration, 1/4 with none). These captures predate the 10.19 metering, light check and
  torch, so they are reported, not scored (PO 2026-11-21 A).
- **The bar is enforced only on 10.19-pipeline groups** (`group.json` with `"pipeline": "10.19"`). None exists yet, so
  the test passes and lists them as missing. Whether 10.19 captures reach the bar (≥ 90% side stickers correct and
  ≥ 3 of 4 side faces kept in the matching and no-saved scenarios) is open until the device session (D4); the story
  stays unmerged if they miss it.

## Known risk for D1

The light level is the face's own sticker lightness, so it depends on which colors a face shows. A solved (or nearly
solved) cube holds a mostly red side face (L* about 45–55) under a white top (about 80–90) at hold 1, and a blue side
under a yellow top at hold 2: the difference exceeds 27 in any light, so such a cube is light-held at both holds
(*Scan face by face* stays available). D1's false-block count should include a solved cube. The replay and the
recorded captures are all scrambled cubes.

## Device-session checklist (before-merge human gate; not claimed by the build)

- [ ] **D1** ≥ 10 front-lit and ≥ 10 backlit attempts per device on the Lenovo TB128XU and the Galaxy S24 FE:
      false-block and missed-block counts from the `quickScan lightHold start/clear` and `quickScan frame … lightCheck=`
      diagnostics (include a solved cube).
- [ ] **D2** metering support and settle times on both devices (`quickScan metering supported=… region=…`,
      `quickScan metering settleMs=… timeout=…`).
- [ ] **D3** S24 FE torch: default on, toggle, carry-over hold 1 → hold 2, off on *Scan face by face* / leaving the
      capture view / background, back on when returning to the corner view; glossy cube at both holds with the torch on
      and off; torch glare coached ("Glare from the torch — turn the torch off.") and cleared by turning the torch off.
      Lenovo: no torch control, no torch copy (`quickScan torch hasFlash=false`).
- [ ] **D4** new 10.19-pipeline corner fixtures (with `group.json`) on both devices: stickered, stickerless and glossy
      cubes, ≥ 2 front lights per device and a backlit group; then the replay bar on them (story stays unmerged if
      missed).
- [ ] **D5** `uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner`
      on ≥ 20 positives and ≥ 10 negatives per device (recall ≥ 92%, rejection ≥ 96%, corner error ≤ 3% of the
      outline diagonal).
- [ ] **D6** PO two-corner completion before/after on the Lenovo, and the Quick Scan BETA call (release level stays with
      Stories 10.8/10.11).
