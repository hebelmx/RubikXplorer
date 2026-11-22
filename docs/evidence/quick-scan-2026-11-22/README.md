# Quick Scan bench captures — 2026-11-22 (Lenovo TB128XU)

Two screen recordings of Quick Scan with the matching diagnostic logs, collected as evidence for
[the Quick Scan reliability spec](../../../_bmad-output/specs/spec-quick-scan-reliability/SPEC.md) and its
[pipeline audit](../../../_bmad-output/specs/spec-quick-scan-reliability/pipeline-audit.md).

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
Capture 1's logcat buffer was not cleared beforehand; capture 2's was, so its log holds that run only.

## Capture 1 — 11:09:14 to 11:11:17 (solved-looking cube)

- Hold 1 took **58.5 s** from the first diagnostic line (11:09:14.290) to capture (11:10:12.810). One light hold lasted 52.9 s (`heldMs=52933`), followed by one of 11.1 s.
- Hold 1 capture: U white 9 confident, kept. R red 9 confident, kept. F green 0 confident / 9 uncertain (`disagree=9`), sent to grid.
- Hold 2 never captured in the remaining 65 s. Light holds of 7.4, 4.8, 13.1, 6.1, 3.2 and 14.8 s cleared and re-engaged; the last re-engaged 1 s after clearing.
- Frames: 228 logged, 48 with `detected=false`. Light check: 125 fail, 43 pass, 60 not evaluated.
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
