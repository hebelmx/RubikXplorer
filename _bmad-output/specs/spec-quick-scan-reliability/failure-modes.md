# Failure modes

Defects and weaknesses found by reading the source at 43e4f472 and by the 2026-11-22 bench evidence
([concerns-and-evidence.md](concerns-and-evidence.md)). Status says how far each is established.

| ID | Failure | Effect | Status |
|---|---|---|---|
| F1 | Exposure changes during a hold | Steady restarts on a still cube | Measured in bursts |
| F2 | Settle signal trusts the camera's AE state | Steady window starts while exposure still moves | Measured once |
| F3 | Light check fails for three different reasons | Tens of seconds of waiting | Confirmed in logs; causes of two not established |
| F4 | Clearing a light hold re-meters | Next hold engages on the settle frame | Confirmed in both capture logs |
| F5 | Light hold cannot be overridden | Manual shutter ignored | Confirmed by reading |
| F6 | Steady rule compares colors | Restarts on a still cube; almost never passes hand-held | Measured in bursts |
| F7 | One uncertain sticker discards the face | 0 of 6 faces kept; also what contains wrong stickers today | Confirmed in capture 2 and the 10.19 replay |
| F8 | One frame decides every sticker | Reading depends on the exposure level at that instant | Follows from F1 |
| F9 | Second read rejects a whole face | A whole face rejected | Seen; cause open (mismatched calibration or shaded face) |
| F10 | Logo centre reads unstably | Per-face correction anchored on a bad value | Measured in bursts |
| F11 | `backOut` keeps stale turnover state | Hold after back-out may not capture | Confirmed by reading; not reproduced |
| F12 | Hold 2 meters without AWB | Centers from the two holds may differ in white balance | Unverified |
| F13 | Diagnostics gaps | First minute of capture 1 lost; capture trigger and restart counts unknown | Confirmed |
| F14 | Cube lost during a hold | Re-meter, settle and steady restart; light hold frozen | Confirmed in capture 1 |
| F15 | Turn check anchored on the top centre | Hold 2 may capture the hold-1 pose | Inferred; not observed |

## F1. Exposure changes during a hold

Metering on the cube sets an exposure for about 3 seconds (`METERING_LOCK_SECONDS`). When the action cancels the
camera returns to the whole-scene exposure. The metering action is not an exposure lock: in still burst 1 the exposure
alternated between 20 ms and 30 ms inside the 3-second window. Every sticker shifts by ΔE 5–8 between the two levels, the size of the logged
steady restarts (5.3–8.5). With exposure steady, sticker variation on a resting cube is ΔE 0.3–0.5.

## F2. Settle signal trusts the camera's AE state

`CameraXFrameSource.settleOutcome` reports settled once the metering future completes and AE reports converged. In
still burst 1 the exposure kept changing for 1.5 seconds while most frames reported converged. Exposure time and ISO
had been constant for nine frames before that swing, so "unchanged for a few frames" is not a sufficient test either.

## F3. Light check fails for three different reasons

`SideLight.MAX_TOP_OVER_SIDES_RATIO` = 1.34 is the midpoint between two clusters of 16 fixture holds, fitted on
captures made before cube metering existed, and is marked provisional.

- **Sustained fail far above the cut.** Capture 1's 52.9 s hold scored about 1.8 in 62 of 68 logged frames, above
  every backlit fixture (maximum 1.553), on an ordinary bench. It cleared when the left face's level jumped from 0.64
  to 1.00. Whether the cube was badly lit, or the levels were computed against a calibration that did not match, is
  not known.
- **Flapping at the cut.** Scores of 1.339 pass, 1.349 fail, 1.334 pass in consecutive frames. `CornerHold.updateLight`
  engages on one failing frame once settled and clears after 500 ms of passes, with no gap between the two levels.
- **One face's level jumping.** The left face alternated between 0.71 and 0.83 on consecutive frames while top and
  right stayed fixed, moving the score across the cut. A whole-picture exposure change cannot do this. A sticker's
  nearest-colour match flipping the face median is a candidate; not established.

Holding exposure (F1) addresses none of the three with certainty.

## F4. Clearing a light hold re-meters

`CornerHold.onFrame` treats `lightCleared` as a metering change. The re-meter moves exposure (F1), and the hold
re-engaged on the frame metering settled at 11:19:37.128, 11:20:01.311, 11:20:03.532 and 11:10:47.439.

## F5. Light hold cannot be overridden

`CornerHold.press` returns `Ignored` while the hold is active. The learner's only exit is *Scan face by face*.

What the light hold buys is unmeasured. The 10.19 replay's zero confidently wrong stickers on kept faces was obtained
on captures that predate the light check, but it covers almost no kept faces (none in 26 of 30 rows, none in any
backlit group). No capture in the 2026-11-22 logs was taken at a failing score. The 10.19 session recorded one
correct backlit block (run L1).

## F6. Steady rule compares colors

`CornerHold.steadyMove` restarts the 1-second window when two stickers of a face differ by ΔE 5 from the reference
frame. On a still cube it restarts whenever exposure moves (F1). Hand-held, 52 of 119 consecutive frame pairs trip it
and one window completed in 9.7 seconds. An outline-movement rule at 5% of cube size completed six.

## F7. One uncertain sticker discards the face

`QuickScanClassification.needGrid` sends a face to the grid when any judged sticker is not CONFIDENT. Capture 2's
faces had 7, 7, 6, 8, 2 and 6 confident stickers and all were discarded. The 10.19 replay keeps no side face in 26 of
its 30 scenario rows with data.

The same rule is the only thing containing wrong reads. The replay holds confidently wrong side stickers after the
gate (for example 5 of 32 for `stla_warm` with no saved calibration); each sits on a face that was discarded. Keeping
stickers individually removes that containment.

## F8. One frame decides every sticker

`QuickScanController.take` copies the latest frame's cells. With two exposure levels (F1) the captured values depend
on which level that frame fell in, while confidence needs a ΔE 10 margin. A median over a short window does not
address this: it adds nothing at steady exposure and little hand-held (10% of readings beyond ΔE 10 becomes 7%).

## F9. Second read rejects a whole face

The second read applies an L*-only gain and compares with the saved centers (`QuickScanClassification.corrected`). Hue
and chroma are not adapted. Capture 1's green face was confident on all nine stickers against the cube's own centers
and rejected on all nine by the saved calibration. Two explanations are open: the saved calibration came from another
cube or light, or the L*-only correction fails on a shaded side face. The saved calibration was made on `stla` in `cool`
front light (PO 2026-11-22); whether the recorded cube was `stla` is not confirmed. The second has precedent: in the 10.19 session
the shaded back face was rejected in every Lenovo calibrated run with a matching calibration. The calibrated light
check uses the same saved centers.

The saved calibration is also the only independent second read. The first read already uses the cube's own centers,
and replay rows without a saved calibration show more confidently wrong stickers than matching rows.

## F10. Logo centre reads unstably

The top centre sticker, which carries a logo in the burst picture, read up to ΔE 36 apart between frames on a still cube. The
per-face correction (F9) and the pose check use centre cells.

## F14. Cube lost during a hold

Capture 1 logged 48 of 228 frames with no cube detected, on a bench reported as still. Loss longer than 300 ms
re-meters and restarts settle and steady; 13–14 of that capture's 22 metering requests follow a loss. A running light
hold neither advances nor clears while the cube is undetected, so about 6 s of the 11.1 s hold and 9 s of the 14.8 s
hold were undetected time. Whether the cube was being handled then is not known. The recorder bursts detected the
cube in every frame and do not show this.

## F15. Turn check anchored on the top centre

`QuickScanController.turnedOver` passes when the top centre differs by ΔE 15 from the previous hold. A logo top centre
read ΔE 19–36 from its first value for 2.1 s on a still cube. In capture 2 one frame passed the pose gate between two
`centerBlock` lines with the cube unturned. Today's waits delay a wrong hold-2 capture; removing them exposes it.

## F11–F13

- `QuickScanController.backOut` does not clear `previous` (also item 3 of the 2026-11-21 code review on
  `review/3d-quickscan-2026-11-21`).
- `GuidedScanActivity` meters hold 2 with `includeAwb = false` while AWB stays in continuous auto.
- `ScanDiagnostics` throttles frame and steady-restart lines to one per 500 ms, and does not log whether a capture
  was automatic or manual. CameraX debug logging filled the log buffer during capture 1.
