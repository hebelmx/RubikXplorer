# Handoff: device session, part 2 (written 2026-11-23 ~13:00 CST)

Continues `handoff-device-session-2026-11-23.md` (read its "Working style" and "Sitting D" sections; they still apply). The first session ran out of context; this note is what the next agent needs.

## Bottom line

Sitting D (Story 10.20) is half done: the Lenovo's 6 corner groups are recorded and committed; the Galaxy's 9 groups are next, right after the PO's break. Block 1 (release smoke) passed; Block 5 sync tests passed; Block 8 is done except 8.7. Issues #313–#326 were opened, #276 and #197 closed. Nothing is pushed or merged.

## Where things are

- **Worktree:** `/mnt/e/Dynamic/IndFun/RubikXplorer-device-session-2`, branch `docs/device-session-2026-11`, all work committed locally, **not pushed**. Never use the main checkout.
- **Evidence:** `docs/evidence/10-20/README.md` (recorder check, findings, coverage matrix, **Galaxy 9-group plan table**, Lenovo preview) and `docs/evidence/release-device-session/README.md` (Blocks 1, 5, 6.1, 8, Placement runs, issues, resume note). Shots under `shots/`, JUnit under `block5/`.
- **Fixtures:** 6 groups in `Fixtures/corner/lenovo-tb128xu_{stla,stka}_bar-{warm,cool}`, `stla_ceilback`, `stlb_bar-warm`. No `groups.json` yet (write it after the Galaxy).
- **Backups:** both devices' app files before the test runs: `/mnt/e/Dynamic/IndFun/device-backups-2026-11-23/`. Mislabelled originals on the Lenovo were wiped by the test run (backed up).
- **Scratch (may vanish):** session scratchpad holds overlay script and drafts; nothing needed from it.

## Devices now

| | Lenovo TB128XU (USB `HA1S28MM`) | Galaxy S24 FE (wireless, `adb mdns services`; was `192.168.0.131:41067`) |
|---|---|---|
| Build | debug (`app/build/outputs/apk/debug/app-debug.apk` in the worktree) | debug |
| App data | fresh (wiped by connected tests), onboarded | Placement runs done: stages 1–4 skipped, stage 5 unlocked |
| Settings changed | none | screen_off_timeout 86400000 (was 30000), lock_screen_lock_after_timeout 86400000 (was 2147483647); **restore at session end**; dark mode on (was on); font 1.0; TalkBack off |

## PO decisions this session (also in the evidence)

- `stka` (Rubik's) = glossy cube; `stlb` = pastel cube (memory updated). Lights: `bar-warm` (LED bar front 3000 K), `bar-cool` (6000 K), `ceilback` (bar off, ceiling light behind the cube, direction **back**).
- Corner points are **not** placed by hand (infeasible): leave the defaults; script-based labelling later (#315).
- 10k-step test timeout: 2–3 device tiers (#314). Issues one per finding; fold only near-duplicates; run an adversarial review before posting, then post without asking again.
- Panel: #324 (solved-cube Placement offers *I can already solve it*), #325 (Discard confirm when faces captured), #326 (align Solve-tab Quick Check with Lessons).

## Next steps, in order

1. **Galaxy 10.20 recording** (9 groups, table in `docs/evidence/10-20/README.md`): open recorder `adb -s <galaxy> shell am start -n com.exxerpro.cubentia/dev.cubentia.app.camera.recorder.FixtureRecorderActivity`. In every dialog the PO must set **cube, light and direction** (the dialog keeps the previous ones, #317); torch toggle on flash devices (on by default). Held-out groups: corners + 3 Keypoints only + 3 No cube; tuning: corners + 2 No cube. After each "done": pull the group, check `group.json` (pipeline 10.22, torch per hold) and diagnostics, fix mislabels by editing id fields only (pull → edit JSON → push → move original to `files/mislabeled/`). Watch "Saved positive … (keypoints only)" on a corner = colour read failed (#318): retake.
2. Write `Fixtures/corner/groups.json` (heldout/tuning per the tables, `pastel: true` on `lenovo-tb128xu_stlb_bar-warm`), then the original handoff's after-recording steps 1–6 (CornerGroupIndexTest/ColorCalibrationTest/QuickScanSideFaceReplayTest; evaluate_model.py held-out; pastel plausibility; replay report; 10.23 H3; README).
3. **#251 (6.7):** case 2 (R U2 from solved), case 3 (non-white cross / F2L from yellow, *Show me the last layer anyway*), then a TalkBack listen of the result screen on both devices. Then **#249 (6.6)** and **6.9** in the same TalkBack pass.
4. Block 2 (include 8.7 single-wrong-move Quick Check and the #200 end-of-playback check), Block 6 camera items (6.2 #198, 6.4 #195, 6.5 #196, 6.8 #253), Blocks 3, 4, 7.
5. Close-out per the original handoff: issue comments for what passed (PO reviews), `sprint_plan.py` for settled action items, restore Galaxy settings, `gh pr list --state open` check, push, PR, merge only on green CI + PO OK (merge commit), remove worktree.

## Tricks that worked (adb)

- Read the screen: `uiautomator dump` then parse text/content-desc + bounds; tap by bounds centre. Fresh-state the app with `pm clear` (not reinstall).
- **TalkBack:** `settings put secure enabled_accessibility_services` is refused on Android 17; the PO turns it on; turn it off with `adb shell am force-stop com.google.android.marvin.talkback`.
- Light/dark: `cmd uimode night no|yes` (app theme = System). Font: `settings put system font_scale 2.0` (restore 1.0).
- Offline tests on the **Lenovo** (USB): `cmd connectivity airplane-mode enable|disable`; never on the Galaxy (wireless adb).
- Verify saves in the debug app DB: `adb exec-out run-as com.exxerpro.cubentia cat databases/cubentia.db(-wal,-shm)` → `sqlite3` (`progress_event`).
- Connected tests (`:app:connectedDebugAndroidTest`) **uninstall the app** afterwards: back up `files/` first, reinstall debug after.
- Delete any screenshot that shows a camera frame of the room or personal notifications (WhatsApp popups); keep app screens only.
- Shell `.then(...)` callbacks after a script returns may not run: restore temp edits explicitly (the 10k-test timeout edit was restored by hand).

## PO working notes

Bottom line first, one step at a time, plain names. The PO often answers loosely ("done last layer") — screenshot before assuming. The Physical-mode switch is hard to see (#320); the Lenovo touch sometimes freezes (#319, lock/unlock fixes it).
