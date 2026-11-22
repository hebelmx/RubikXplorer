# Quick Scan bench evidence — 2026-11-22 (Lenovo TB128XU)

Raw data for [the Quick Scan reliability spec](../../../_bmad-output/specs/spec-quick-scan-reliability/SPEC.md): two
screen recordings of Quick Scan with their diagnostic logs, and three frame bursts from the debug fixture recorder.
The readings drawn from them are in the spec's
[concerns and evidence](../../../_bmad-output/specs/spec-quick-scan-reliability/concerns-and-evidence.md).

Every recording and the first two bursts were made with tablet, cube and lights still. Only the third burst is
hand-held.

## Conditions

- Device: Lenovo TB128XU (serial HA1S28MM), Android 13, tablet resting on a base, no flash (`hasFlash=false`, torch off).
- App: `com.exxerpro.cubentia` 0.1.0 (versionCode 1), installed 2026-11-21 20:12. The source commit of that build was not recorded.
- Light mode in both runs: `lightMode=calibrated`.
- Each recording starts about half a second before Quick Scan is tapped on the Solve tab.

## What is tracked here and what stays local

| Item | Capture 1 | Capture 2 |
|---|---|---|
| `CubentiaScanDiag` log (tracked) | [capture-1-111103-scan-diag.log](capture-1-111103-scan-diag.log) — 292 lines | [capture-2-112028-scan-diag.log](capture-2-112028-scan-diag.log) — 144 lines |
| Local folder (gitignored) | `RubikXplorer/captures/quickscan-20261122-111103/` | `RubikXplorer-quick-scan-audit/captures/quickscan-20261122-112028/` |
| Video (local only) | `quickscan.mp4`, 170 s, 168 MB | `quickscan.mp4`, 59 s, 56 MB |
| Final screenshot (local only) | `final-screen.png` | `final-screen.png` |
| Full logcat (local only) | `logcat-cubentia.txt`, `logcat-full.txt` | same names |

The videos, screenshots and full logcats are not committed: the videos are too large for git, and the
images show the camera view of the operator's desk. They exist only on the development machine.
Capture 1's logcat buffer was not cleared beforehand and lost the first minute of the run; capture 2's was cleared, so its log holds that whole run and nothing else.
The `quickScan frame` lines are throttled to one per 500 ms by `ScanDiagnostics`, so they sample the frames and do not give the analysis frame rate. State changes (hold, metering, restart, capture) are logged as they happen.

## Capture 1 — 11:09:14 to 11:11:17 (solved-looking cube)

- **The log is missing the first minute of this run.** Quick Scan was tapped at about 11:08:12, but the device's log buffer had already wrapped: the first surviving diagnostic line is 11:09:14.290. Hold 1 therefore took about **2 minutes** (11:08:12 to capture at 11:10:12.810), of which only the last 58.5 s are logged.
- One light hold lasted 52.9 s (`heldMs=52933`, so it began near 11:09:04, before the surviving log), followed by one of 11.1 s.
- Hold 1 capture: U white 9 confident, kept. R red 9 confident, kept. F green 0 confident / 9 uncertain (`disagree=9`), sent to grid.
- Hold 2 never captured in the remaining 65 s. Light holds of 7.4, 4.8, 13.1, 6.1, 3.2 and 14.8 s cleared and re-engaged; the last re-engaged 1 s after clearing.
- Frames in the surviving log: 228, 48 with `detected=false`. Light check: 125 fail, 43 pass, 60 not evaluated.
- The recording ended on corner 2 of 2, so there is no finished cube to score colors against.

## Capture 2 — 11:19:29 to 11:20:29 (scrambled cube)

- Hold 1 took **19.2 s** (11:19:29.974 to 11:19:49.136). The first metering wait hit its cap (`settleMs=1508 timeout=true`). Three light holds (4.2, 2.9, 1.1 s) and seven `steadyRestart` events (maxDeltaE 5.3–8.5) preceded the capture.
- Hold 1 capture: U white 7 confident / 1 uncertain. F green 7 / 2. R red 6 / 3. **All three faces sent to grid.**
- Hold 2 took **24.8 s** (to 11:20:13.938), with light holds of 9.0, 1.2 and 7.9 s.
- Hold 2 capture: D yellow 8 / 1. L orange 2 / 7 (`disagree=5`). B blue 6 / 3. **All three faces sent to grid.**
- Net result: both holds captured after 44 s, and all six faces were handed to face-by-face capture. No face was kept.
- Face-by-face then rejected every logged frame from 11:20:15.494 to 11:20:29.727: 20 frames with `failed=PLAIN_OUTER_CELLS, quality=MISALIGNED` (`plainOuter=4/min6`). Sharpness, glare and luma passed in those frames. The final screenshot shows "Face 1 of 6 — Show the green center" with the cube still corner-on to the camera, so these rejections may only reflect that the cube was not turned face-on.
- Frames: 81 logged, 3 with `detected=false`. Light check: 37 fail, 33 pass, 11 not evaluated.

## Observations the two runs share

- Corner frames repeatedly report `quality=ACCEPTABLE` on all three faces and `score=1.0` while the calibrated light check fails; the light hold is the largest logged contributor to the wait in both runs.
- Light holds clear and re-engage within a few seconds of each other without a capture in between.
- A captured hold does not mean kept faces: 2 of 3 kept in capture 1's first hold, 0 of 6 in capture 2.

These are log readings. The videos have not been reviewed frame by frame against the timelines, and no
labeled ground truth exists for either cube state.

## Recorder bursts

Recorded with the debug fixture recorder's burst (`RecorderBurst`, commit 763f4496), started over adb:

    adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_BURST --ei frames 60 --ez meter true --es label still

Each line of a `.jsonl` file is one analysis frame: `tMs`, `detected`, `score`, `quads` (three faces, four corners,
frame pixels), `faces` (quality, mean luma, glare, sharpness and nine cells as L*, a*, b*, plain), and `exposure` (AE
and AWB state, exposure time, ISO, colour gains). Metering on the cube was requested at the start of each burst.

| File | Conditions | Frames | Duration |
|---|---|---|---|
| [bursts/20261122-114155-still.jsonl](bursts/20261122-114155-still.jsonl) | Cube and tablet still | 60 | 4.9 s |
| [bursts/20261122-114230-still-long.jsonl](bursts/20261122-114230-still-long.jsonl) | Cube and tablet still | 150 | 12.9 s |
| [bursts/20261122-115658-handheld.jsonl](bursts/20261122-115658-handheld.jsonl) | Cube held in the hand, tablet still | 120 | 9.7 s |

The reference pictures saved with each burst (206×206, every tenth frame) stay local in
`RubikXplorer-quick-scan-audit/captures/bursts/`.

## Other local material from the session

- `captures/fixtures/lenovo-tb128xu_stla_cool-2/`: a labelled corner fixture group (pipeline 10.19, front light),
  for Story 10.20. Corner 2 was captured by an adb tap at 11:47:40.
- `captures/recorder-stuck-20261122-114708/`: screenshots and logs from when the tablet's touchscreen stopped
  reporting touches. The raw touch device produced no events for 12 seconds while adb taps worked; touch returned
  after the power menu was opened. Cause unknown.
