# Story 10.19 evidence: light check thresholds and side-face replay

Generated from `QuickScanSideFaceReplayTest` (`./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*'`) on the
pre-10.19 Lenovo TB128XU captures in `Fixtures/corner/` (8 groups, 2 holds each, schema 3, no `group.json`). The replay
prints this report on every run; re-run it after the device session to refresh the numbers.

## Light check (`SideLight`)

**S24 FE device session (D3, 2026-11-21) changes** (`.git/bmad-orchestrate/d3-s24-torch1.log`):

- **Light clear:** the cube was lost for one frame every 100–800 ms and any undetected frame, unscored face or moved
  frame restarted the 500 ms pass window, so a hold lasted 256 s. While held, only a failing verdict now restarts the
  window; undetected and unscored frames are neutral (neither advance nor reset it), and movement no longer restarts it
  (the capture after the clear re-meters, waits for the settle and needs `STEADY_MS` steady).
- **Detection grace:** one-frame flicker re-metered 63 times in about 60 s. The cube now counts as lost (re-meter,
  settle, steady window and queued press restart) only once undetected for `CornerHold.LOST_MS` = 300 ms.
- **Torch skips the check (PO 2026-11-21):** the torch is camera-side light, so while it is lit no light hold engages,
  a running hold clears at once when it turns on (`quickScan lightHold clear` is logged) and no sides-dark line shows;
  torch-glare coaching is unchanged. The verdict is still logged, with `lightMode=torch`. With the torch off or no
  flash unit the check works as before.
- **Calibration offer (PO 2026-11-21, device session):** an uncalibrated light hold that runs `QuickScanController.CALIBRATION_OFFER_MS` = 5 s without clearing shows the Guided Scan's "Colors look off — calibrate?" / *Calibrate colors* (same confirm) below the sides-dark line; it hides when the hold clears, the cube is lost, on *Scan face by face*, and never shows calibrated or torch-lit; the hold blocks as before (`quickScan calibrationOffer show hold=… heldMs=…`).
- **Centre check (PO 2026-11-21, Lenovo device session):** hold 2 auto-captured 1–2 s after hold 1 with the cube still in the hold-1 pose, and the mislabeled faces sent the scan to the Guided grid. A hold now auto- or manual-captures only once the pose changed: it is blocked while all three of its centre cells lie, in Lab, within `CenterCheck.POSE_MATCH_DELTA_E` = 11 of the centre cells captured at the other hold in the same screen positions (the cube was not turned); no colour classification, so a saved calibration from another cube (a pastel cube's pale blue centre reading WHITE against a GAN calibration, which blocked hold 2 forever under the first, prompted-colour rule) cannot stick a capture (AC5). Hold 1 before any capture is never blocked. While blocked the hold's prompt stays and the steady count does not start (`quickScan centerBlock hold=… deltaE=…`, per screen position). Each corner capture logs `quickScan capture hold=…` with per face the centre read, CONFIDENT/UNCERTAIN counts, kept or grid and the reason (to explain a `fallbackFaces=6` run).

**Both cuts are provisional** until the device session (D1 false and missed blocks, D4 new 10.19 captures): each was
fitted on the same 16 Lenovo TB128XU holds below, with no held-out data and no second device.

Two modes (PO 2026-11-21 E); the controller runs the check itself on every detected corner frame, calibrated when the
Learner has a valid saved calibration, and the diagnostics log the mode (`lightMode=calibrated|uncalibrated`).

- **Uncalibrated** (no saved calibration): a face's level is the 75th-percentile L* of its nine stickers (linear
  interpolation; with nine stickers the 7th in ascending L*, i.e. the 3rd lightest). Fails when the darker side face is more than
  `MAX_SIDE_DARKER_L` = **27 L*** below the top face (PO 2026-11-21 D: side faces against the top only, no background).
  The cut sits between the front-lit maximum (25.0, `stka warm` hold 2) and the backlit holds (28.6 and up): it passes
  all 8 front-lit holds and trips at least one hold in every backlit group (7 of 8 backlit holds; `stla cool` hold 1
  measures 17.2 and passes).
- **Calibrated** (a saved calibration; PO 2026-11-21 F): a face's level is its **relative brightness**, the median
  over its nine stickers of (sticker L* ÷ the saved L* of that sticker's nearest saved colour, matched on a*b* only).
  The score is the top face's relative brightness ÷ the **mean** of the two side faces'; the check fails when it is
  above `MAX_TOP_OVER_SIDES_RATIO` = **1.34**. Measured with each group's own recorder calibration (table below):
  front-lit holds 0.991–1.286 (maximum `stla cool-front` hold 2), backlit holds 1.337–1.553, and every backlit
  group's worse hold is at least 1.392 (`stka cool` hold 1). 1.34 is the midpoint of 1.286 and 1.392 (margin about
  0.05 on each side): it passes all 8 front-lit holds and trips 7 of 8 backlit holds, at least one in every backlit
  group (`stka cool` hold 2 at 1.337 passes). Averaging both sides keeps the side-lit `stla warm` hold 1 (one side
  shaded, the other bright; 0.991) from being held, which the earlier median-L*-difference signal could not do (front
  maximum 24.2 against a backlit group maximum of 23.3; Spec Change Log, review loop 2).

The diagnostics log both the levels and the mode's score (`light=… sideDiff=… lightScore=… lightMode=…`; calibrated
values with three decimals).

Light check score per group and hold (legacy light labels: `warm` and `cool-front` front-lit, `cool` backlit);
uncalibrated: darker side face below the top face in L*; calibrated: top ÷ mean of both sides in relative brightness.
`QuickScanSideFaceReplayTest` asserts every value (calibrated within 0.005):

| group | direction | scored | uncalibrated hold 1 | uncalibrated hold 2 | calibrated hold 1 | calibrated hold 2 |
|---|---|---|---|---|---|---|
| lenovo-tb128xu_stka_cool | back | false | 32.4 FAIL | 30.2 FAIL | 1.392 FAIL | 1.337 pass |
| lenovo-tb128xu_stka_cool-front | front | false | 23.0 pass | 22.7 pass | 1.109 pass | 0.994 pass |
| lenovo-tb128xu_stka_warm | front | false | 23.3 pass | 25.0 pass | 1.177 pass | 1.165 pass |
| lenovo-tb128xu_stkb_cool | back | false | 29.1 FAIL | 28.7 FAIL | 1.425 FAIL | 1.352 FAIL |
| lenovo-tb128xu_stla_cool | back | false | 17.2 pass | 36.3 FAIL | 1.431 FAIL | 1.394 FAIL |
| lenovo-tb128xu_stla_cool-front | front | false | 21.6 pass | 20.9 pass | 1.232 pass | 1.286 pass |
| lenovo-tb128xu_stla_warm | front | false | 13.8 pass | 24.6 pass | 0.991 pass | 1.161 pass |
| lenovo-tb128xu_stlb_cool | back | false | 32.8 FAIL | 28.6 FAIL | 1.553 FAIL | 1.349 FAIL |

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

- **Uncalibrated mode** (no saved calibration): the level is the face's own sticker lightness, so it depends on which
  colors a face shows. A solved (or nearly solved) cube holds a mostly red side face (L* about 45–55) under a white top
  (about 80–90) at hold 1, and a blue side under a yellow top at hold 2: the difference exceeds 27 in any light, so such
  a cube is light-held at both holds (*Scan face by face* stays available; accepted by the PO, decision E). D1's
  false-block count should include a solved cube without a saved calibration.
- **Calibrated mode** removes that colour dependence (a solved cube in uniform front light reads 1.0 on every face,
  `SideLightTest`) and tolerates light from one side (the mean of both sides), but its margin rests on 16 Lenovo holds
  (about 0.05 each side of the cut) and one backlit hold (`stka cool` hold 2, 1.337) passes. D1 should count false and
  missed blocks per mode, from `lightScore` and `lightMode`.

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
