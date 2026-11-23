# Handoff: device session, part 3 (written 2026-11-23 ~14:40 CST)

Continues `handoff-device-session-2026-11-23-part2.md` and the original `handoff-device-session-2026-11-23.md` ("Working style" still applies).

## Bottom line

**Story 10.20 recording is complete on both devices** (Lenovo 6 groups, Galaxy 9 groups), `Fixtures/corner/groups.json` is written, and the after-recording steps 1–4 ran: recall and rejection pass on both devices, corner error fails only because labels are the guide's default points (#315), and one pastel sticker fails plausibility. Issues #327 and #328 were opened today. Everything is committed locally on `docs/device-session-2026-11`; **nothing is pushed or merged**.

## Where things are

- **Worktree:** `/mnt/e/Dynamic/IndFun/RubikXplorer-device-session-2`, branch `docs/device-session-2026-11`, local commits only. Never use the main checkout.
- **Evidence:** `docs/evidence/10-20/README.md` now holds the Galaxy table, data handling, findings F1–F9, the ticked coverage matrix and "Results after recording". Also `corner-model.json` (acceptance run) and `replay-baseline.md`.
- **Fixtures:** 15 groups (all pipeline 10.22) in `Fixtures/corner/` + `groups.json`. Galaxy originals stay on the phone under `/sdcard/Android/data/com.exxerpro.cubentia/files/Fixtures/corner/` (including the stray `sm-s721u1_stka_bar-cool-2`, merged into group 6 in the repo).

## Devices now

| | Lenovo TB128XU (USB `HA1S28MM`) | Galaxy S24 FE (wireless; was `192.168.0.131:41067`, find with `adb mdns services`) |
|---|---|---|
| Build | debug | debug, fixture recorder open (corner mode, `stla · ceilback`, torch off) |
| Settings changed | none | screen_off_timeout 86400000 (was 30000), lock_screen_lock_after_timeout 86400000 (was 2147483647): **still to restore at session end**; dark mode on; font 1.0; TalkBack off |

## What happened today on the Galaxy (details in the evidence)

- The agent filled every recorder dialog over adb (`uiautomator dump` + taps; type into the field, then `keyevent 4` to close the keyboard; never `keyevent 111`, it closes the dialog). Cube, light, direction, torch were checked before Save, so no id relabels were needed.
- Torch-off groups with the glossy `stka` (and the backlit group) need **EV+** before the detector finds the cube (F7); EV+ leaves `settle: null` (F8).
- **#328:** corners of groups 7, 8, 9 were saved `torch: true` with the flash off; relabelled torch fields only (photos and HAL log as proof). Torch truth is best judged from the photo (torch = centre hot spot + vignette).
- **#327:** face review selects sticker 0 instead of the first `?`; workaround: tap the `?` first. 17 faces with sticker 0 `entered` (list in #327) must be checked against their photos before the labels are trusted.
- Group 6's extra shots went to a `-2` folder after *Record both corners again* (F9); merged.

## Results (acceptance, held-out split)

| Device | Pos | Neg | Recall | Rejection | Corner error |
|---|---|---|---|---|---|
| Lenovo | 34 | 15 | 100% | 100% | 5.61% ⛔ (default labels) |
| Galaxy | 41 | 26 | 100% | 96.2% (1 FP) | 7.23% ⛔ (default labels) |

`ColorCalibrationTest` pastel: `lenovo-tb128xu_stlb_bar-warm hold 2 L[8]` BLUE implausible at Lab(56.2, -20.1, -13.4): chroma floors need pastel tuning (follow-up).

## Next steps, in order

1. **PO decisions:** accept the corner-error miss until #315 relabels (so 10.21 can start)? Pastel chroma-floor tuning as a follow-up story/issue?
2. **Issues still to file** (one per finding, adversarial review, then post): F7 (detector needs EV+ in torch-off/backlit, no hint), F8 (EV+ leaves settle null), F9 (*Record both corners again* splits extras into a new folder), pastel plausibility. F1 is #315, F5 #328, F6 #327.
3. **10.23 H3:** add the new held-out groups through `tools/sticker-model/assemble.py` (README line ~72), produce Kotlin reads, then `evaluate.py --groups … --name evaluation-10-20`; report into `docs/evidence/10-23/`.
4. **#251 (6.7):** case 2 (R U2 from solved), case 3 (non-white cross / F2L from yellow, *Show me the last layer anyway*), TalkBack listen on both devices; **#249 (6.6)** and **6.9** in the same TalkBack pass.
5. Block 2 (incl. 8.7 and the #200 end-of-playback check), Block 6 camera items (6.2 #198, 6.4 #195, 6.5 #196, 6.8 #253), Blocks 3, 4, 7.
6. Close-out per the original handoff: issue comments (PO reviews), `sprint_plan.py` for settled items (incl. `epic-10-deferred-10.20-recording-session` once the PO accepts), **restore Galaxy settings**, `gh pr list --state open`, push, PR, merge only on green CI + PO OK (merge commit), remove worktree.

## Tricks added today

- Pull a group: `adb pull /sdcard/Android/data/com.exxerpro.cubentia/files/Fixtures/corner/<group>` (external files dir, not `run-as files/`).
- Torch truth: HAL log `logcat | grep "ANDROID_FLASH_MODE("` (0->2 = torch on) while the buffer lasts (~20 min), else the photo.
- Diagnostic burst (passive, no metering): `am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_BURST --ei frames 5 --ez meter false --es label X`.
- Incoming calls take over the screen: check `mCurrentFocus` before tapping.
