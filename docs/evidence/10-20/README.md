# Story 10.20 recording session: evidence (in progress)

**Status:** paused after the Lenovo, 2026-11-23 ~09:35 CST. Galaxy S24 FE not recorded yet. Resume note at the end.

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

## Coverage matrix (AC2, ticked by hand)

| Need | Lenovo | Galaxy |
|---|---|---|
| Stickered cube (`stka`) | ✅ | ☐ |
| Stickerless cube (`stla`) | ✅ | ☐ |
| Glossy cube (`stka`, PO) | n/a | ☐ |
| ≥ 2 front-lit lights | ✅ bar-warm, bar-cool | ☐ |
| ≥ 1 backlit group | ✅ ceilback | ☐ |
| Torch on and off | n/a | ☐ |
| Held-out ≥ 20 pos + ≥ 10 neg | ✅ 34 / 15 | ☐ |
| Held-out + tuning per light × torch | ✅ (bar-warm, bar-cool; ceilback held-out only, backlit is not a scored front condition) | ☐ |
| Pastel group with calibration | ✅ `stlb` bar-warm | (one device enough) |

## Resume note (Galaxy)

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
