# Story 10.20 recording session: evidence (recording complete; acceptance pending)

**Status:** both devices recorded (Lenovo ~09:35 CST, Galaxy 13:30–14:34 CST, 2026-11-23). `groups.json` written. Acceptance run done: recall/rejection pass, corner error fails only because labels are the guide defaults (#315). Pastel plausibility: one sticker fails (follow-up). 10.23 H3 not run yet.

> **Labels corrected after this session (2026-11-23, SCP pastel-plausibility rev 4):** the PO label review corrected 20 corner labels in 12 records (`docs/evidence/10-27/label-corrections-2026-11-23.json`, commit `c9c9f5cc`). The acceptance results on this page were computed on the labels before that correction; Story 10.23 H3 (`docs/evidence/10-23/evaluation-10-20.md`) used the corrected ones.

- **Date:** 2026-11-23
- **Main SHA:** `dfc5c3d6` (PR #311 merge); worktree `RubikXplorer-device-session-2`, branch `docs/device-session-2026-11`
- **Build:** main-app debug (`./gradlew :app:assembleDebug`), installed on both devices at 08:31 CST
- **Devices:** Lenovo Tab M10 Plus 3rd Gen (TB128XU, Android 13, USB `HA1S28MM`, no flash); Galaxy S24 FE (SM-S721U1, Android 17, wireless adb)
- **Operator:** PO; agent drove adb and checked every group after "done"

## PO decisions this session

| Decision | Detail |
|---|---|
| Glossy cube | `stka` (Rubik's, stickered) is the glossy cube; no new id. Its S24 FE groups fill the AC2 glossy row. |
| Pastel cube | `stlb` is the pastel ("pale") stickerless cube; no new id. Its groups are marked `pastel: true` in `groups.json`. |
| Lights | `bar-warm` = LED bar in front, warm (3000 K suggested); `bar-cool` = LED bar in front, cool (6000 K suggested); `ceilback` = LED bar off, studio ceiling light behind the cube (backlit). |
| Corner labels | Placing the 7 corners by finger on the 128 px crop is not feasible (PO). Captures continue with the guide's default points; real corners come later from a labelling script outside the app (follow-up). |

## Lenovo TB128XU groups (pipeline 10.22)

| Group | Cube | Light / direction | Cube shots | No-cube shots | Planned split |
|---|---|---|---|---|---|
| `lenovo-tb128xu_stla_bar-warm` | stla | bar-warm / front | 12 | 6 | held-out |
| `lenovo-tb128xu_stka_bar-warm` | stka | bar-warm / front | 9 | 5 | tuning |
| `lenovo-tb128xu_stla_bar-cool` | stla | bar-cool / front | 9 | 5 | held-out |
| `lenovo-tb128xu_stka_bar-cool` | stka | bar-cool / front | 8 | 8 | tuning |
| `lenovo-tb128xu_stla_ceilback` | stla | ceilback / back | 13 | 4 | held-out |
| `lenovo-tb128xu_stlb_bar-warm` | stlb (pastel) | bar-warm / front | 2 | 4 | tuning, pastel |

Held-out totals: 34 positive, 15 negative (AC2 needs 20 / 10). `groups.json` is **not written yet** (after the Galaxy), so no 10.22 group can leak into training meanwhile.

### Recorder device check (the review's open item)

Every `group.json`: `pipeline "10.22"`, device, cube, light, lightDirection, `torch: null` (no flash), six-centre `calibration`, `holds` 1 and 2. Every `corner-N.json`: `diagnostics` with light levels, `lightScore`, `lightPassed`, `lightMode: calibrated`, `torch: null`, settle `{settleMs, outcome, lock: "on+awb", futureMs}`. Hold 2 kept the hold-1 white-balance lock (`on+awb`) in every group. Torch on the S24 FE: not checked yet.

| Group | Hold 1 light / settle | Hold 2 light / settle |
|---|---|---|
| stla bar-warm | 1.04 pass / timeout 1510 ms | 1.12 pass / settled 1488 ms |
| stka bar-warm | 0.85 pass / timeout 1554 ms | 0.92 pass / timeout 1522 ms |
| stla bar-cool | 0.93 pass / timeout 1547 ms | 0.85 pass / settled 1567 ms |
| stka bar-cool | 0.82 pass / settled 1466 ms | 0.86 pass / timeout 1509 ms |
| stla ceilback (back) | 1.32 **pass** / timeout 1510 ms | 1.42 **fail** / timeout 1533 ms |
| stlb bar-warm | 1.07 pass / settled 1269 ms | 0.97 pass / settled 1552 ms |

### Pastel calibration (`stlb`, bar-warm), CIELAB chroma

Blue 40, Orange 45, Red 73, Green 91, Yellow 83, White 7 (GAN `stla` same light: Blue 62, Orange 87, Red 92, Green 111). `ColorPlausibility` run pending (after recording).

### Data handling (relabels, no capture repeated)

The recorder keeps the previous cube, light and direction until the dialog is changed, and the PO mistyped ids. Fixed by editing only the id fields of the JSON files (images and labels untouched); originals kept on the Lenovo under `files/mislabeled/`:

- GAN group recorded as `stka` → `stla` (folder + 5 files); 16 further GAN shots recorded as `stka` merged into `stla_bar-warm` as `0003`–`0018` (`frame` renamed).
- `stka-real` → `stka` (17 files).
- Pastel group recorded as `ceilback`/back (dialog carried over) → `bar-warm`/front (9 files; PO confirmed the warm bar from the front).

## Findings

| # | Severity | Finding |
|---|---|---|
| F1 | S2 (AC3) | **Keypoint labels are not ground truth.** Every positive keeps the guide's default points (red hexagon ~4% off the cube). Recall and rejection are unaffected; the corner-error metric cannot be scored and these groups must not train the corner model until relabelled. Follow-up: labelling script under `tools/` that finds the 7 real corners, PO reviews overlays. |
| F2 | S3 | 10.22 settle hits its cap (~1.5 s, `timeout`) on 7 of 12 holds; capture still works. |
| F3 | S3 | Recorder: after a capture where a face has an empty cell, *Use corners* silently saves "keypoints only" and stays on the same corner; the "Detector missed" line is easy to miss. |
| F4 | S3 | Recorder dialog carries the previous light and direction into a new cube's group; three relabels were needed. |
| F5 | S2 | **Recorded torch state is stale** (#328): torch-off corners saved as `torch: true` on 3 Galaxy groups, with and without an app restart. Fixed by hand. |
| F6 | S2 | **Face review selects sticker 0** (#327), not the first `?`, so a colour tap can overwrite a correct sticker. 17 faces with sticker 0 `entered` need checking against their photos. |
| F7 | S3 | Torch off, glossy `stka` (and backlit `stla`): the corner detector misses until EV+ (shaded face reads too dark); the recorder only says "Detector missed". |
| F8 | S3 | Using EV+ leaves the hold's `settle` null (groups 3, 9). |
| F9 | S3 | *Record both corners again* after both corners starts a new `-N` folder; extra shots taken after it are split from their group. |

## Galaxy S24 FE groups (pipeline 10.22), 2026-11-23 13:30–14:34 CST

Dialog filled by the agent over adb for every group (cube, light, direction, torch verified before Save), so no id relabels were needed.

| # | Group | Cube | Light / dir | Torch | Corner holds (light, settle) | Keypoints pos / neg | Split |
|---|---|---|---|---|---|---|---|
| 1 | `sm-s721u1_stla_bar-warm` | stla | bar-warm / front | on | 1.13 settled 1116 ms; 1.13 settled 1189 ms | 6 / 5 | held-out |
| 2 | `sm-s721u1_stka_bar-warm` | stka | bar-warm / front | on | 1.02 settled 1246; 1.06 settled 1284 | 7 / 4 | tuning |
| 3 | `sm-s721u1_stka_bar-warm-2` | stka | bar-warm / front | off | 0.94 / 0.97, settle **null** (EV+ used) | 12 / 4 | held-out |
| 4 | `sm-s721u1_stla_bar-warm-2` | stla | bar-warm / front | off | 1.13 settled 1237; 1.32 settled 1232 | 3 / 3 | tuning |
| 5 | `sm-s721u1_stla_bar-cool` | stla | bar-cool / front | on | 1.02 timeout 1523; 1.01 settled 1163 | 6 / 5 | held-out |
| 6 | `sm-s721u1_stka_bar-cool` | stka | bar-cool / front | on | 1.07 timeout 1530; 0.94 settled 1274 | 6 / 4 (4+4 merged from `-2`) | tuning |
| 7 | `sm-s721u1_stka_bar-cool-3` | stka | bar-cool / front | off | (torch-mode score) timeout 1517 / 1533 | 9 / 6 | held-out |
| 8 | `sm-s721u1_stla_bar-cool-2` | stla | bar-cool / front | off | (torch-mode score) settled | 5 / 4 | tuning |
| 9 | `sm-s721u1_stla_ceilback` | stla | ceilback / back | off | (torch-mode score) settled 1463; settle null (EV +1.2) | 8 / 6 | held-out |

Held-out totals: **41 positive, 26 negative** (AC2: 20 / 10).

### Galaxy data handling

- Group 6's extra shots landed in a new folder `sm-s721u1_stka_bar-cool-2` (a *Record both corners again* tap starts a fresh folder). Same cube, light, direction and torch, taken 1 min after the corners: merged into group 6 as `0003`–`0010` (`frame` renamed). The `-2` folder stays on the phone only.
- **Torch relabels (#328):** groups 7, 8 and 9 corners (and holds in `group.json`) were written `torch: true` while the flash was off (photos show no torch light; HAL log has no `ANDROID_FLASH_MODE 0->2` after 14:08 CST). Only the torch fields were changed; their `diagnostics.lightScore` / `lightMode: "torch"` were computed in the wrong mode and are not comparable. A first relabel of group 6 corner 1 was reverted: its photo shows the torch.
- Group 3 and group 9 hold 2 needed EV+ (group 9: EV +1.2) before the detector found the cube; both have `settle: null`.

## Coverage matrix (AC2, ticked by hand)

| Need | Lenovo | Galaxy |
|---|---|---|
| Stickered cube (`stka`) | ✅ | ✅ |
| Stickerless cube (`stla`) | ✅ | ✅ |
| Glossy cube (`stka`, PO) | n/a | ✅ |
| ≥ 2 front-lit lights | ✅ bar-warm, bar-cool | ✅ bar-warm, bar-cool |
| ≥ 1 backlit group | ✅ ceilback | ✅ ceilback |
| Torch on and off | n/a | ✅ (torch labels corrected, #328) |
| Held-out ≥ 20 pos + ≥ 10 neg | ✅ 34 / 15 | ✅ 41 / 26 |
| Held-out + tuning per light × torch | ✅ (bar-warm, bar-cool; ceilback held-out only, backlit is not a scored front condition) | ✅ (2 lights × 2 torch states, each held-out + tuning; ceilback held-out only) |
| Pastel group with calibration | ✅ `stlb` bar-warm | (one device enough) |

## Results after recording (2026-11-23 ~14:35 CST)

1. `./gradlew :core:domain:test --tests '*CornerGroupIndexTest' --tests '*ColorCalibrationTest' --tests '*QuickScanSideFaceReplayTest'`: 16 tests, **1 failed**: `ColorCalibrationTest` pastel plausibility, `lenovo-tb128xu_stlb_bar-warm hold 2 L[8]: BLUE is not plausible for Lab(56.2, -20.1, -13.4)` (a pale teal-blue). `CornerGroupIndexTest` and the replay test pass.
2. `evaluate_model.py` (held-out split), JSON `corner-model.json`:

| Device | Positives | Negatives | Recall | Rejection | Corner error |
|---|---|---|---|---|---|
| Lenovo TB128XU | 34 | 15 | 100.0% | 100.0% (0 FP) | 5.61% ⛔ |
| Galaxy S24 FE | 41 | 26 | 100.0% | 96.2% (1 FP) ✅ at the bar | 7.23% ⛔ |

   Recall and rejection pass AC3 (92% / 96%). Corner error fails on both, but it is measured against the guide's default points left in the labels (F1, #315), so it says nothing about the model yet. **Routed to the PO**; per the handoff it does not block 10.21 unless the PO says so.
3. Pastel: `ColorPlausibility` rejects one pale-blue sticker on hold 2. The chroma floors need tuning for pastel cubes: a follow-up (code change), not a session edit.
4. Replay baseline (report-only): `replay-baseline.md`.
5. **10.23 H3 not run:** it needs the new held-out groups added through `tools/sticker-model/assemble.py` and Kotlin reads first. Next session.

## PO decisions after recording (2026-11-23 ~14:50 CST)

- **Corner-error miss accepted** until the corners are relabelled (#315); recall and rejection pass, so Story 10.21 may start.
- **Pastel plausibility → a story** (not an issue): `lenovo-tb128xu_stlb_bar-warm hold 2 L[8]` is a real blue at hue 213.6° (Blue window 215–315°, its own calibrated centre 235°). Direction: calibration-relative plausibility (judge stickers against the cube's own centres) rather than widening fixed windows; candidate home is Story 10.21's refit, or a new story via correct-course.
- **Issues filed** (adversarial review, then posted): #329 (F7 detector needs EV+ in torch-off/backlit, no hint), #330 (F8 `settle: null`), #331 (F9 *Record both corners again* splits extra shots). F1 = #315, F5 = #328, F6 = #327.

## Resume note (Galaxy, done)

Plan, 9 groups (folder names never collide because cubes alternate per light):

| # | Cube | Light | Torch | Split | Extra shots |
|---|---|---|---|---|---|
| 1 | stla | bar-warm | on | held-out | 3 keypoints, 3 no-cube |
| 2 | stka | bar-warm | on | tuning | 2 no-cube |
| 3 | stka | bar-warm | off | held-out | 3 keypoints, 3 no-cube |
| 4 | stla | bar-warm | off | tuning | 2 no-cube |
| 5 | stla | bar-cool | on | held-out | 3 keypoints, 3 no-cube |
| 6 | stka | bar-cool | on | tuning | 2 no-cube |
| 7 | stka | bar-cool | off | held-out | 3 keypoints, 3 no-cube |
| 8 | stla | bar-cool | off | tuning | 2 no-cube |
| 9 | stla | ceilback | off | held-out | 3 keypoints, 3 no-cube |

Debug build is already on the Galaxy. Open the recorder with `adb shell am start -n com.exxerpro.cubentia/dev.cubentia.app.camera.recorder.FixtureRecorderActivity`; in each dialog check cube, light **and direction**. After the Galaxy: write `Fixtures/corner/groups.json`, then the after-recording steps 1–6 of the handoff.

## Resume note (2026-11-23 ~12:50 CST, break)

**State:** worktree `/mnt/e/Dynamic/IndFun/RubikXplorer-device-session-2`, branch `docs/device-session-2026-11` (local commits, not pushed). Both devices have the **debug** build installed. Galaxy: screen timeout and lock delay set to 24 h (restore to 30 s / default when the session ends); dark mode on; TalkBack off. Lenovo: app data was wiped by the connected tests (fixtures are in the commit and in `/mnt/e/Dynamic/IndFun/device-backups-2026-11-23/`).

**Next, in order (PO plan: Galaxy recording after the break):**
1. **Story 10.20, Galaxy:** 9 groups per `docs/evidence/10-20/README.md` resume table. Check cube, light **and direction** in every dialog; corner points are left at defaults (#315). Then write `Fixtures/corner/groups.json` and run the handoff's after-recording steps 1–6.
2. **#251 (6.7) remaining:** case 2 (R U2 from solved), case 3 (non-white cross / F2L from yellow, *Show me the last layer anyway*), then one TalkBack listen of the result screen per device.
3. **#249 (6.6) and 6.9** in the same TalkBack pass.
4. **Block 2** (includes 8.7 single-wrong-move Quick Check and the #200 end-of-playback check), **Block 6** camera items (6.2 #198, 6.4 #195, 6.5 #196, 6.8 #253), **Blocks 3, 4, 7**.
5. Close-out: issue comments for what passed, `sprint_plan.py` for settled action items, push, PR, merge on green CI + PO OK, remove worktree.

## Preview: corner model on the Lenovo groups (not the acceptance run)

`evaluate_model.py --fixtures-corner <copy of the 6 Lenovo 10.22 groups>`, no group index (split = all, tuning groups included), 2026-11-23. JSON: `corner-model-lenovo-preview.json`.

| Device | Positives | Negatives | Recall | Rejection | Corner error |
|---|---|---|---|---|---|
| Lenovo TB128XU | 53 | 32 | 100.0% | 100.0% (0 FP) | 5.33% of the outline diagonal (5.45 px mean, 9.10 px p90) |

Recall and rejection already clear the AC3 bars (92% / 96%) on this data. The corner error is **not meaningful**: it is measured against the guide's default points left in the labels (#315), not the real corners. The negatives are similar empty-table views; harder negatives would make rejection more informative. The official run needs the Galaxy groups and `groups.json` (held-out split only).
