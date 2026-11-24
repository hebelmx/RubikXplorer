# Story 10.21: tuning and held-out replay

The fitted gate keeps no confidently wrong sticker on a kept face in any scored held-out group, under any calibration (none, matching, other light, other cube). Coverage, which is reported and not enforced, is far below the bar on every condition. No scored held-out condition keeps a single side face, and the best one reads 62.5% of its side stickers. The two off-face groups (`lenovo-tb128xu_stla_bar-cool` and `sm-s721u1_stla_bar-warm`) still keep confidently wrong stickers, because their bad hold-2 `L` sample corrupts the self-calibration. They are reported, as the PO decided. A saved calibration from another cube or another light no longer matches any held-out group: the match check now also needs every captured centre to lie close to the calibration's centre of its colour (PO 2026-11-24).

- Label-set commit (`git log -1 --format=%H -- Fixtures/corner`, the last commit that touched the label set): `c9c9f5cc7b9ecbc03cf90606e7db69772fea4c1d`
- SHA-256 of `Fixtures/corner/label-exclusions.json`: `absent` (Story 10.27 has not landed)
- Reports written by the tests: `core/domain/build/reports/quick-scan/tuning-10-21.md` (`QuickScanGateTuningTest`), `replay-10-21.md` (`QuickScanSideFaceReplayTest`) and `burst-wait-10-21.md` (`QuickScanBurstReplayTest`). The numbers below are copied from those reports.

## How the fit was made (tuning split only)

`QuickScanGateTuningTest` decodes only the folders that `Fixtures/corner/groups.json` marks `tuning`. It fits four things and then checks that the committed constants equal the fit.

- **Per-colour agreement gate** (`QuickScanClassification.AGREEMENT_MARGIN`). A sticker is CONFIDENT only when both reads name the same colour and both margins (ΔE to the second-nearest centre) reach that colour's margin. The fit starts from the 10.19 rule, which is 10 for every colour. It runs over every plain tuning group under every calibration the scan could hold: none, the group's own, and every other tuning group's on the same device. While a face that the keep rule keeps carries a confidently wrong sticker, the fit raises that colour's margin to the next 0.1 step above the worst such sticker. Margins only go up, so the fit ends.
- **Calibration match centre closeness** (`QuickScanClassification.MAX_CENTRE_DELTA_E`, PO 2026-11-24). Besides reading CONFIDENT as its own colour, each plain captured centre must lie within this ΔE of the saved calibration's centre of that colour; otherwise the match fails as `calibration=mismatch centre`. The fit takes the tuning groups whose own (torch-tagged) calibration passes the centre read alone, and gives each colour the 0.1 step above the largest distance of such a centre to its own calibration, so no same-cube match on the tuning split is lost. White has no such centre on the tuning split and takes the strictest (smallest) fitted value, so an unfitted colour fails safe toward a mismatch (PO 2026-11-24; the cuts are provisional, to be refit after Story 10.27 and the device session).
- **Confident-count wait N** (`QuickScanController.MIN_CONFIDENT`). This is the largest N up to 6 that every tuning hold's captured frame reaches. Each frame is read under the scan-time calibration (the group's own, torch-tagged) together with the holds before it.
- **Light hysteresis cuts** (`SideLight`). Each mode's hold cut is 10% above the tuning front-lit maximum: on the score itself when uncalibrated, and on the score's excess over 1 when calibrated. It is rounded up. The clear cut is the midpoint between that maximum and the hold cut, rounded down. Scores are recomputed from the samples with `SideLight.checkSamples`, and the recorded `lightMode` is never used (#328).

### Fitted constants

| constant | before (10.19) | fitted (10.21) | what drives it |
|---|---|---|---|
| Orange agreement margin | 10.0 | **21.3** | `sm-s721u1_stla_bar-warm-2` h1 F2: a red sticker read as orange by both reads, margins 21.42 / 21.27, on a kept face |
| White, Yellow, Green, Blue, Red margins | 10.0 | 10.0 | no kept face on the plain tuning groups reads them wrongly |
| centre closeness ΔE: Red / Orange / Yellow / Blue / Green | none | **12.3 / 14.8 / 18.1 / 31.3 / 40.4** | the largest own-calibration centre distance per colour: Red 12.24, Yellow 18.04, Blue 31.21, Green 40.35 (`lenovo-tb128xu_stka_bar-warm`), Orange 14.79 (`sm-s721u1_stla_bar-cool-2`) |
| centre closeness ΔE: White | none | **12.3** | no own-calibration White centre on a matching tuning group: the strictest fitted value (Red's), PO 2026-11-24 |
| N (confident-count wait) | none | **6** | every tuning hold reaches at least 15 of 27 |
| uncalibrated hold / clear cut (L*) | 27.0 / none | **27.9 / 26.6** | front-lit maximum 25.35 (`sm-s721u1_stla_bar-warm-2` hold 2) |
| calibrated hold / clear cut (ratio) | 1.34 / none | **1.35 / 1.33** | front-lit maximum 1.315 (same hold) |

### Before and after on the tuning split (every plain tuning group, every calibration)

| gate | correct | uncertain | confidently wrong | faces kept | blanks on kept faces | wrong on kept faces |
|---|---|---|---|---|---|---|
| 10.19 (both reads CONFIDENT) | 1096 | 237 | 55 | 43/156 | 4 | 10 |
| 10.21 per colour | 954 | 394 | 40 | 17/156 | 5 | **0** |

The tuned gate keeps fewer faces than the 10.19 gate: 17 of 156 tuning faces kept instead of 43, the price of 0 wrong stickers on kept faces. (Both rows use the tightened match check. Before it, the rows were 1088/245/55, 43/156, 6, 10 and 943/405/40, 18/156, 9, 0. The fitted margins did not change.)

### Calibration match before and after the centre closeness check

On the tuning split, own calibrations match 2 of 7 groups before and after: `lenovo-tb128xu_stka_bar-warm` and `sm-s721u1_stla_bar-cool-2`. Other calibrations of the same device match 2 of 18 before and 0 of 18 after. The two that matched before were `lenovo-tb128xu_stka_bar-cool`'s calibration on `lenovo-tb128xu_stka_bar-warm` and `sm-s721u1_stla_bar-warm-2`'s on `sm-s721u1_stla_bar-cool-2`, both the same cube under another light.

On the held-out split (replay report, enforced by `no other cube's or other light's calibration matches a held-out group`):

| group | other cube: before → after | other light: before → after | own calibration (matching): before → after |
|---|---|---|---|
| `lenovo-tb128xu_stla_bar-cool` | 7/8 → 0/8 | 4/5 → 0/5 | 1/1 → 0/1 |
| `lenovo-tb128xu_stla_bar-warm` | 4/8 → 0/8 | 3/5 → 0/5 | 0/1 → 0/1 |
| every other held-out group | 0 → 0 | 0 → 0 | 0 → 0 |

A single ΔE for every colour could not separate them. The tuning own-calibration Green centre at 40.35 forces a cut of at least 40.4, and under that cut 13 of the 18 foreign held-out matches would remain: other cube 5/8 and other light 3/5 on `lenovo-tb128xu_stla_bar-cool`, other cube 3/8 and other light 2/5 on `lenovo-tb128xu_stla_bar-warm`. The per-colour fit separates all of them. The off-face group `lenovo-tb128xu_stla_bar-cool` also loses its own match: its Red centre lies 14.5 from its own calibration, and its Yellow centre 23.3.

### Pastel tuning group: a decision for the PO

`lenovo-tb128xu_stlb_bar-warm` is the only tuning group the group index marks `pastel`. Its orange stickers read as red in both reads with margins up to 48. If it were part of the gate fit, Red would need a margin of 44.1, which is more than the whole red-to-orange distance on most cubes. Red would then never be CONFIDENT: 0 of 192 tuning faces kept. The fit therefore uses the six plain tuning groups. The pastel group is reported and carried to Story 10.27, which judges pastel stickers against the cube's own calibrated colours. The fitted gate leaves it exactly as unsafe as the 10.19 gate did: D2 (label Orange, read Red) on a kept face, with no saved calibration or with its own. This follows the epic rule that pastel cubes are handled without changing the check for every cube. **The PO should confirm it at the BETA call.** The other option is to commit the 44.1 Red margin, which makes Quick Scan keep almost nothing.

## Held-out replay (`QuickScanSideFaceReplayTest`)

### Safety: enforced in CI on every held-out group and scenario except the listed off-face scenarios

These are enforced: 0 confidently wrong stickers and 0 Red↔Orange mix-ups on kept faces, in all four scenarios, on six of the eight held-out groups and on `lenovo-tb128xu_stla_bar-warm`. `OFF_FACE_UNSAFE` skips all four scenarios of `lenovo-tb128xu_stla_bar-cool` and `sm-s721u1_stla_bar-warm`. Those are reported, not enforced (see below). Every scored group passes. Compared with the 10.19 gate, `sm-s721u1_stla_bar-cool` no longer keeps U4 (Red read as Orange) under any calibration. `lenovo-tb128xu_stla_bar-warm` no longer keeps a confidently wrong sticker under another light's or cube's calibration (1 before).

Off-face groups (PO 2026-11-23 standing rule): the replay asserts the safety the code achieves on them and reports the rest. The test also fails if a listed exception becomes safe, so the list must shrink when that happens.

| group | bad sample | scenarios still unsafe (reported) | what goes wrong |
|---|---|---|---|
| `sm-s721u1_stla_bar-warm` | hold 2 `L`: orange centre reads blue, Lab(43, 9, −38), like the `B` centre Lab(42, 6, −33) | all four | no valid orange reference, so orange stickers on R and D read confidently red (h1 R1, h2 D7, D8) together with D1 and D4 |
| `lenovo-tb128xu_stla_bar-cool` | hold 2 `L`: cells 1, 2, 3, 6, 9 read Yellow, Orange, Yellow, Blue, White against labels White, Yellow, Blue, White, Orange | all four | with no calibration, the corrupted orange reference reads orange as red on F, D and B; its own calibration no longer matches (centre closeness), so the matching scenario reads as with none |
| `lenovo-tb128xu_stla_bar-warm` | hold 2 `L`: cells 1, 2, 4, 8, 9 read Yellow, Blue, Orange, Orange, White against labels White, Yellow, Yellow, Blue, Orange | none (safe, enforced) | — |

All three groups are routed to Story 10.27 (fixture quarantine), #315 (corner-model face placement), #338 and the PO's BETA call. No other held-out group showed the off-face defect.

### Backlit: enforced (the refit light check trips)

| group | uncalibrated scores (hold 1 / 2) | calibrated scores | scan mode (own calibration) | trips |
|---|---|---|---|---|
| `lenovo-tb128xu_stla_ceilback` | 45.2 / 31.3 | 1.316 / 1.421 | uncalibrated (centre mismatch) | yes |
| `sm-s721u1_stla_ceilback` | 30.3 / 18.7 | 1.306 / 1.075 | uncalibrated (centre mismatch) | yes |

Note for the PO: if `sm-s721u1_stla_ceilback`'s own calibration had matched, the calibrated check (1.306 and 1.075, both below 1.35) would **not** trip. The calibrated hold cut was fitted on front light only, and no matched backlit calibration exists to validate it.

### Coverage: reported per device × light × torch, not enforced (PO decision (a))

The bar is at least 90% of side stickers correct and at least 3 of 4 side faces kept. Every condition is below it. The rows show no saved calibration / matching calibration (frontal recorder calibration).

| condition | group | 10.19 gate: correct, kept | 10.21: correct, kept |
|---|---|---|---|
| S24 FE × bar-cool × torch off | `sm-s721u1_stka_bar-cool-3` | 23/32, 0/4 | 20/32 (62.5%), 0/4 |
| S24 FE × bar-cool × torch on | `sm-s721u1_stla_bar-cool` | 20/32, 0/4 | 20/32 (62.5%), 0/4 |
| S24 FE × bar-warm × torch off | `sm-s721u1_stka_bar-warm-2` | 16/32, 0/4 | 13/32 (40.6%), 0/4 |
| S24 FE × bar-warm × torch on (unscored, off-face) | `sm-s721u1_stla_bar-warm` | 18/32, 1/4 | 18/32, 1/4 (6 wrong kept) |
| Lenovo × bar-cool (unscored, off-face) | `lenovo-tb128xu_stla_bar-cool` | 21–26/32, 2–4/4 | 21/32, 2/4 (wrong kept) |
| Lenovo × bar-warm (unscored, off-face) | `lenovo-tb128xu_stla_bar-warm` | 20/32, 0/4 | 19/32, 0/4 |

Coverage regresses against the 10.19 gate: the tuned gate reads fewer side stickers correctly on two of the three scored held-out groups (`sm-s721u1_stka_bar-cool-3` 23 → 20 of 32, `sm-s721u1_stka_bar-warm-2` 16 → 13 of 32) and the same on the third (`sm-s721u1_stla_bar-cool` 20 of 32).

The matching scenario never applies to a scored S24 FE group. Every recorder calibration fails the centre check, mostly because Blue reads as White, so the 1-blank rule never takes part there. The coverage shortfall is drafted as an issue in [issue-draft-coverage-shortfall.md](issue-draft-coverage-shortfall.md).

### Confident-count wait (N = 6)

- On the fixtures (captured frame only): no tuning or held-out hold falls below N under its scan-time calibration. The lowest is 10 of 27 (`sm-s721u1_stla_ceilback` hold 2). The wait delays none of them.
- On the Lenovo bursts (`docs/evidence/quick-scan-2026-11-22/bursts/`): the still, still-long and hand-held bursts never fall below N, and they auto-capture 1095, 1107 and 1897 ms after detection, the same as before the wait. Hand-held-2 is below N on 44 frames, in runs of up to 586 ms, and fast is below N on 14 frames, in runs of up to 165 ms. Neither captures automatically, as before, because a face goes unread. The live 10 s check is on the run sheet.

## Limits of this evidence

- **N = 6 is the cap, not a fitted value.** Every tuning hold reaches at least 15 of 27 CONFIDENT stickers, so the fit "largest N up to 6" just returns the cap. The fixtures cannot tell whether a higher N would still capture promptly.
- **The calibrated band (1.33 clear / 1.35 hold) is not validated.** It has not been checked on backlight with a matching calibration (no such group exists), nor on frame-to-frame jitter. The fixtures hold one frame per hold, and the Lenovo bursts are uncalibrated. Both are on the run sheet.
- **The 1-blank rule never applies on a held-out group.** Every S24 FE recorder calibration fails the centre check, and since the centre closeness check (PO 2026-11-24) no calibration matches any held-out group, so the held-out replay never exercises the matched keep rule. It is exercised only by unit tests and by the two tuning groups whose own calibration matches.
- **The centre closeness cut rests on two tuning groups.** Only `lenovo-tb128xu_stka_bar-warm` and `sm-s721u1_stla_bar-cool-2` have an own calibration that passes the centre read, so each colour's cut comes from one or two centres, and White takes the strictest fitted cut (Red's 12.3, PO 2026-11-24). A genuine same-cube calibration whose angled centres drift further than these cuts falls back to self-calibration, as with no calibration.

## Run history

The first three implementation runs stopped at the AC6 bar (see the spec's Auto Run Result). This run builds the story with the PO's 2026-11-23 decisions: off-face groups unscored for coverage, and AC6 split into enforced safety and reported coverage. No held-out data was read by fitting code. One disclosure: during this run the held-out replay was also run once with the pastel-inclusive fit (Red 44.1) before the plain fit was committed. Under that fit the scored groups were safe as well, and every held-out face went to the grid. The choice between the two fits rests on the epic's pastel rule above, not on held-out numbers. The PO should still weigh it at the BETA call.
