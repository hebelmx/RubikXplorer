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

- [ ] **D1** *(2026-11-21: partial, counts not completed; deferred, see the device session below)* ≥ 10 front-lit and ≥ 10 backlit attempts per device on the Lenovo TB128XU and the Galaxy S24 FE:
      false-block and missed-block counts from the `quickScan lightHold start/clear` and `quickScan frame … lightCheck=`
      diagnostics (include a solved cube).
- [x] **D2** *(2026-11-21: done; supported with `region=outline` on both devices, settle times below)* metering support and settle times on both devices (`quickScan metering supported=… region=…`,
      `quickScan metering settleMs=… timeout=…`).
- [ ] **D3** *(2026-11-21: partial; default on, toggle, labels, carry-over hold 1 → hold 2, off on leaving the capture
      view, back on when returning and Lenovo `hasFlash=false` seen; glossy cube and torch-glare coaching not tested)* S24 FE torch: default on, toggle, carry-over hold 1 → hold 2, off on *Scan face by face* / leaving the
      capture view / background, back on when returning to the corner view; glossy cube at both holds with the torch on
      and off; torch glare coached ("Glare from the torch — turn the torch off.") and cleared by turning the torch off.
      Lenovo: no torch control, no torch copy (`quickScan torch hasFlash=false`).
- [ ] **D4** *(2026-11-21: not done; no 10.19-pipeline fixtures recorded; bar deferred by the PO)* new 10.19-pipeline corner fixtures (with `group.json`) on both devices: stickered, stickerless and glossy
      cubes, ≥ 2 front lights per device and a backlit group; then the replay bar on them (story stays unmerged if
      missed).
- [ ] **D5** *(2026-11-21: not done; deferred by the PO)* `uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner`
      on ≥ 20 positives and ≥ 10 negatives per device (recall ≥ 92%, rejection ≥ 96%, corner error ≤ 3% of the
      outline diagonal).
- [ ] **D6** *(2026-11-21: not done; completion before/after not measured; BETA call deferred to the PO)* PO two-corner completion before/after on the Lenovo, and the Quick Scan BETA call (release level stays with
      Stories 10.8/10.11).

## Device session 2026-11-21 (Lenovo TB128XU + Galaxy S24 FE)

Before-merge human gate, run over adb (Lenovo over USB, S24 FE over wireless adb) with the PO holding the cube. Sources:
the orchestrator's session notes (`.git/bmad-orchestrate/ledger.md`, story 10-19 entries) and the raw `CubentiaScanDiag`
logs `.git/bmad-orchestrate/d1-lenovo-*.log` and `d3-s24-*.log` (`d3-s24-back1` ⊂ `back2` ⊂ `recal-off` are cumulative
dumps of one logcat). Times are device-local (CST). Builds changed during the session (see *Fixes made during the
session*); each run names the build it ran on. Light cuts: uncalibrated `sideDiff` > 27 L* fails, calibrated
`lightScore` > 1.34 fails; with the torch lit (`lightMode=torch`, from f7b0573f) no hold engages. Per face below:
confident/uncertain, then kept or grid and the logged reason (`disagree`/`closeCall`). Runs marked *(notes)* have no log
and come from the session notes only.

### Lenovo TB128XU (no flash: `torch hasFlash=false` in every log)

| # | build | cube | calibration | light | light holds (count, duration, scores vs cut) | centre check | captures (hold 1 / hold 2) | outcome, time |
|---|---|---|---|---|---|---|---|---|
| L1 *(notes)* | 83e7f9ae | stla (GAN) | none (uncalibrated) | monitor behind the cube (backlit) | 1 hold, 18.7 s, `sideDiff` 28–34 vs 27; correct block | none at the time: hold 2 auto-captured about 2 s after hold 1, before the cube was turned | not logged | fell to *Scan face by face* |
| L2 (`d1-lenovo-att2`, 18:42) | 83e7f9ae | stla | none | desk lamp in front | 2 holds: 2.0 s at start (`sideDiff` 26.2, 18.4 while held; settle timed out at 1.575 s), 7.0 s later (`sideDiff` up to 34.3, hand shadow suspected); 96 scored frames, `sideDiff` −14.7 to 34.3, 3 fail | none | not logged; 32 s (18:42:43–18:43:15) detected, light pass, no hold, no auto-capture (steady gate reset by one flickering cell, fixed in 46005a11); hold 1 captured about 18:43:26, hold 2 captured | *Scan face by face* (Face 2 of 6); no run line |
| L3 (`d1-lenovo-att3`, 19:36) | f7b0573f | stla, yellow top | none | front/side light | 6 holds: 13.1 s (`sideDiff` about 40), then 5 cycles of 2.7–3.1 s (clear → re-meter → settle → hold again; `sideDiff` 25.5–29.0 vs 27); 62 scored frames, 1.9–45.8, 47 fail. Colour dependence: yellow top L* 80–86 against green/blue left 51–60 | none | no capture | abandoned; PO then calibrated the Lenovo (led to d02c6330) |
| L4 *(notes; run line in `d1-lenovo-cube2`)* | f7b0573f | stla | calibrated (GAN) | front-lit | 2 short holds 0.6 s and 0.8 s, `lightScore` 1.0–1.27 | none: hold 2 captured at once in the hold-1 pose | not logged (no per-capture line yet) | `CANCELLED`, 180.9 s, `fallbackFaces=6` |
| L5 (`d1-lenovo-cube2`, 19:42) | f7b0573f | second (other) cube, scrambled | calibrated | front-lit | none; 28 scored frames 0.986–1.331, 0 fail | none | not logged | `ACCEPTED` (8-move solve), 62.4 s, `fallbackFaces=6` (every face to the grid) |
| L6 (`d1-lenovo-centreblock`, 20:03) | d3921a00 | cube3 (pastel stickerless) | calibrated from the GAN cube | front-lit | 2 holds: 1.24 s (1.317, 1.321), 1.06 s (1.198); 82 scored frames 1.014–1.371, 3 fail | prompted-colour rule: hold 1 blocked 5 times before its capture; hold 2 blocked 70 times (20:03:14–20:04:06): pale blue centre read WHITE against the GAN calibration, so it never matched BLUE | hold 1: U 9/0 kept, F 9/0 kept, R 7/2 grid (disagree=2) / hold 2: never captured | stuck at hold 2 (led to 3eb3638b); no run line |
| L7 (`d1-lenovo-back1`, 20:29) | 3eb3638b | stla, solved | calibrated (GAN) | front-lit | 1 hold at hold 2, 2.73 s (1.766, 1.697, 1.691 with the B face `LOW_LIGHT`, cleared at 1.264); 24 scored frames 0.976–4.083 (4.083 a one-frame lift), 4 fail | pose-unchanged rule: hold 2 blocked 2 times (`deltaE` 0.5–5.5) until turned: PASS | hold 1: U 8/0 kept, F 9/0 kept, R 9/0 kept / hold 2: D 9/0 kept, L 9/0 kept, B 0/9 grid (disagree=9) | `ACCEPTED`, 27.8 s, `fallbackFaces=1`; 5/6 kept, side faces 3/4 |
| L8 (`d1-lenovo-back1`, 20:30) | 3eb3638b | stla, scrambled | calibrated (GAN) | front-lit | none; 11 scored frames 1.039–1.291, 0 fail | no block logged | hold 1: U 8/0 kept, F 8/1 grid (closeCall=1), R 9/0 kept / hold 2: D 9/0 kept, L 8/1 grid (closeCall=1), B 5/4 grid (disagree=4) | `ACCEPTED` (24-move solve), 52.4 s, `fallbackFaces=4`; 3/6 kept at capture, side faces 1/4 |

### Galaxy S24 FE (`torch hasFlash=true`, default on)

| # | build | cube | calibration | torch | light holds (count, duration, scores vs cut) | centre check | captures (hold 1 / hold 2) | outcome, time |
|---|---|---|---|---|---|---|---|---|
| S1 (`d3-s24-torch1`, 18:49) | 83e7f9ae | stla | older saved calibration (no torch; age and cube unknown) | on at 18:49:33 (default), off 18:50:12.9, on 18:50:16.9: toggle and labels PASS | 1 calibrated hold from 18:49:37.8, never cleared in the log; per notes cleared 18:53:54 after 256 s once the torch was off; 38 scored frames with the torch on, 1.085–1.508, 19 fail (the torch lights the top more than the sides) | none | no capture: 127 of 210 frames undetected, 130 re-meters in about 108 s (one-frame detection flicker) | session paused for fixes (f7b0573f) |
| S2 (`d3-s24-back1`, 20:34) | 3eb3638b | stla | older saved calibration | on (default), off at 20:35:24.5 on leaving the capture view | none (`lightMode=torch`, check skipped: PASS); logged scores 1.019–1.494 | hold 2 blocked 3 times (`deltaE` 0.4–3.4) until turned: PASS | hold 1: U 7/2 grid (disagree=2/closeCall=0), F 4/5 grid (2/3), R 3/6 grid (2/4) / hold 2: D 2/7 grid (4/3), L 3/6 grid (3/3), B 0/9 grid (3/6) | `CANCELLED`, 100.5 s, `fallbackFaces=6`; 0/6 kept |
| S3 (20:36:19) | 3eb3638b | stla | older saved calibration | on | none (`lightMode=torch`); 28 frames 0.934–1.242 | no block logged | hold 1: U 6/3 grid (2/1), F 7/2 grid (0/2), R 4/5 grid (2/3) / hold 2: D 6/3 grid (0/3), L 8/1 grid (1/0), B 8/1 grid (0/1) | `CANCELLED`, 34.1 s, `fallbackFaces=5`; 0/6 kept at capture |
| S4 (20:36:56) | 3eb3638b | stla | older saved calibration | turned off at 20:37:00 | 4 calibrated holds: 16.8 s (1.267–1.610), 1.6 s (1.286–1.657), 11.7 s (one scored frame, 0.707; the rest unscored), 3.7 s at hold 2 (1.226–1.345); 63 scored frames 0.644–1.835, 13 fail | no block logged | hold 1: U 8/0 kept, F 1/8 grid (4/4), R 7/2 grid (0/2) / hold 2: D 1/8 grid (2/6), L 1/8 grid (1/7), B 3/6 grid (3/3) | `CANCELLED`, 127.4 s, `fallbackFaces=6`; 1/6 kept at capture |
| S5 (20:42:04) | 3eb3638b | stla | recalibrated in the session light with the torch off | on (a new session defaults it on; the PO believed it was off) | none (`lightMode=torch`); 37 frames 1.037–1.428 | no block logged | hold 1: U 8/1 grid (0/1), F 7/2 grid (1/1), R 5/4 grid (0/4) / hold 2: D 6/3 grid (0/3), L 3/6 grid (3/3), B 6/3 grid (0/3) | no run line in the log; 0/6 kept |
| S6 *(notes)* | 3eb3638b | stla | fresh calibration, torch off | off | hold 2 light-held at 1.37–1.39 (cut 1.34) on a visually well-lit cube until the PO moved the light: counted as a false hold | not noted | hold 1: U 7/1 (per-face correction rejected), F 1/8 (disagree=6), R 7/2 / hold 2: D 7/2, L 2/7 (disagree=6), B 5/4; all grid | 0/6 kept |

### Metering (D2, AC3)

`quickScan metering supported=true region=outline` on every episode on both devices. Settle times (`settleMs`):

| device | settles | min | median | 90th percentile | max | timeouts (`timeout=true`) |
|---|---|---|---|---|---|---|
| Lenovo TB128XU (5 logs) | 29 | 217 ms | 1188 ms | 1543 ms | 1575 ms | 2 (1554, 1575 ms) |
| Galaxy S24 FE (`d3-s24-torch1` + `d3-s24-recal-off`) | 77 | 197 ms | 598 ms | 1033 ms | 1512 ms | 2 (1501, 1512 ms) |

The Lenovo usually settles in about 0.3–1.6 s (10th percentile 299 ms) and the S24 FE in about 0.2–1.0 s (10th percentile
214 ms); both reach the 1.5 s cap occasionally.

### Results per acceptance criterion

| AC | result | evidence |
|---|---|---|
| AC1 light check and hold | met (live) | Holds engage and clear on both devices in both modes (L1–L3, L6–L7, S1, S4); the backlit Lenovo hold (L1) blocked correctly; torch-lit frames skip the check (S2, S3, S5). |
| AC2 false and missed blocks | partial | Counts not completed (far fewer than 10 front-lit and 10 backlit attempts per device). Observed false holds: the Lenovo uncalibrated on scrambled cubes (L2 7.0 s, L3 hold/clear cycling at `sideDiff` 25.5–29.0); the S24 FE torch off and calibrated at 1.37–1.39 on a visually well-lit cube (S6; S4 holds of 16.8 s and 11.7 s). Correct hold: the backlit Lenovo (L1, 18.7 s, `sideDiff` 28–34). No missed block was observed. |
| AC3 cube metering | met | Regional metering supported on both devices; settle times above (Lenovo 217–1575 ms, median 1188 ms; S24 FE 197–1512 ms, median 598 ms; 2 cap timeouts each). |
| AC4 torch and coaching | met except glossy-cube glare | S24 FE default on, toggle, "Torch on"/"Torch off", carry-over between holds, off on leaving the capture view, back on when returning; Lenovo has no torch control. Torch glare on a glossy cube and its coaching were not tested. |
| AC5 no confidently wrong sticker on a kept face | met | No kept face showed a confidently wrong sticker in any run (L7 and L8 states accepted as scanned; every S24 FE face went to the grid). |
| AC6 side-face coverage bar | not met | S24 FE 0/6 kept in every run (S2–S6; one face kept at capture in S4, `fallbackFaces=6`). Lenovo calibrated 3/6–5/6 kept, side faces 1/4–3/4 (L7, L8); uncalibrated and other-cube runs `fallbackFaces=6` (L4, L5). |
| AC7 replay on 10.19 fixtures and held-out metrics | not done | No 10.19-pipeline fixture group recorded (D4, D5). |
| AC8 Quick Scan BETA call | deferred to the PO | |

### Fixes made during the session

- `4244a1ad` fix(quick-scan): no light hold while metering settles, AC6 coverage gate, torch applied on success (10.19)
- `83e7f9ae` fix(quick-scan): no light hold on an episode's first detected frame, before metering settles on the cube (10.19)
- `46005a11` fix(quick-scan): corner steady and light-clear windows restart only when two cells of one face move (10.19)
- `f7b0573f` fix(quick-scan): light clear ignores detection flicker, 300 ms lost grace, torch skips the light check (10.19)
- `d02c6330` feat(quick-scan): offer calibration after a 5 s uncalibrated light hold in the corner view (10.19)
- `d3921a00` fix(quick-scan): capture a corner only when its centres read as the prompted pose, log each capture (10.19)
- `3eb3638b` fix(quick-scan): block a corner only while its centres match the other hold's in Lab, not the prompted colours (10.19)

### Deferred by PO decision 2026-11-21

10.19 merges as the lighting pipeline; the following move to a new side-face accuracy story (GitHub issue #274):

- AC2 false- and missed-block counts (D1).
- AC4 torch glare on a glossy cube and its coaching (D3).
- AC6 side-face bar (≥ 90% side stickers correct, ≥ 3 of 4 side faces kept) on 10.19-pipeline fixtures (D4).
- AC7 recorder held-out metrics (D5).
- AC8 Quick Scan BETA call (D6).

### Open findings

- A single close call sends a whole face to the grid (L8 F and L: 8/1 with `closeCall=1`).
- The shaded back face and the other faces disagree: B went to the grid in every Lenovo calibrated run (`disagree` 4–9).
- Torch-off calibration against the torch-on default on the S24 FE: the calibration screen has no torch, while Quick Scan
  opens with the torch on (S5), so the saved colours never match the scan light.
- Uncalibrated mode depends on the cube's colours (L3: yellow top against green/blue side, `sideDiff` 25.5–29.0 around
  the 27 cut).
- The calibrated cut 1.34 hovers at ordinary S24 FE torch-off light (S4 1.226–1.610, S6 1.37–1.39): needs hysteresis or
  a refit on 10.19 captures.
- The Settings screen text is clipped at the left edge on the Lenovo.
- Quick Scan reopened once into a stale face-by-face step.
