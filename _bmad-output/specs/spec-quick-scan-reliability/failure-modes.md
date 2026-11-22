# Failure modes

Defects and weaknesses found by reading the source at 43e4f472 and by the 2026-11-22 bench evidence
([concerns-and-evidence.md](concerns-and-evidence.md)). Status says how far each is established.

| ID | Failure | Effect | Status |
|---|---|---|---|
| F1 | Exposure changes during a hold | Steady restarts; probably light flapping | Measured in bursts |
| F2 | Settle signal trusts the camera's AE state | Steady window starts while exposure still moves | Measured once |
| F3 | Light hold flaps at its cut | Tens of seconds of waiting | Confirmed in both capture logs |
| F4 | Clearing a light hold re-meters | Next hold engages on the settle frame | Confirmed in both capture logs |
| F5 | Light hold cannot be overridden | Manual shutter ignored | Confirmed by reading |
| F6 | Steady rule compares colors | Restarts on a still cube; almost never passes hand-held | Measured in bursts |
| F7 | One uncertain sticker discards the face | 0 of 6 faces kept | Confirmed in capture 2 and the 10.19 replay |
| F8 | One frame decides every sticker | Reading depends on the exposure level at that instant | Follows from F1 |
| F9 | Saved calibration can veto a cube it does not match | A whole face rejected | Likely; calibration origin unrecorded |
| F10 | Logo centre reads unstably | Per-face correction anchored on a bad value | Measured in bursts |
| F11 | `backOut` keeps stale turnover state | Hold after back-out may not capture | Confirmed by reading; not reproduced |
| F12 | Hold 2 meters without AWB | Centers from the two holds may differ in white balance | Unverified |
| F13 | Diagnostics gaps | First minute of capture 1 lost; no frame rate | Confirmed |

## F1. Exposure changes during a hold

Metering on the cube sets one exposure for about 3 seconds (`METERING_LOCK_SECONDS`). When the lock expires the camera
returns to the whole-scene exposure. Every sticker shifts by ΔE 5–8 between the two levels, the size of the logged
steady restarts (5.3–8.5). With exposure steady, sticker variation on a resting cube is ΔE 0.3–0.5.

## F2. Settle signal trusts the camera's AE state

`CameraXFrameSource.settleOutcome` reports settled once the metering future completes and AE reports converged. In
still burst 1 the exposure kept changing for 1.5 seconds while most frames reported converged.

## F3. Light hold flaps at its cut

`SideLight.MAX_TOP_OVER_SIDES_RATIO` = 1.34 is the midpoint between two clusters of 16 fixture holds and is marked
provisional. Bench scores sat on it: 1.339 pass, 1.349 fail, 1.334 pass in consecutive frames. `CornerHold.updateLight`
engages on one failing frame once settled and clears after 500 ms of passes, with no gap between the two levels.

## F4. Clearing a light hold re-meters

`CornerHold.onFrame` treats `lightCleared` as a metering change. The re-meter moves exposure (F1), and the hold
re-engaged on the frame metering settled at 11:19:37.128, 11:20:01.311, 11:20:03.532 and 11:10:47.439.

## F5. Light hold cannot be overridden

`CornerHold.press` returns `Ignored` while the hold is active. The learner's only exit is *Scan face by face*.

The light hold is also not what keeps wrong colors out: the 10.19 replay's zero confidently wrong stickers on kept
faces was obtained on captures that predate the light check. The agreement gate provides that safety.

## F6. Steady rule compares colors

`CornerHold.steadyMove` restarts the 1-second window when two stickers of a face differ by ΔE 5 from the reference
frame. On a still cube it restarts whenever exposure moves (F1). Hand-held, 52 of 119 consecutive frame pairs trip it
and one window completed in 9.7 seconds. An outline-movement rule at 5% of cube size completed six.

## F7. One uncertain sticker discards the face

`QuickScanClassification.needGrid` sends a face to the grid when any judged sticker is not CONFIDENT. Capture 2's
faces had 7, 7, 6, 8, 2 and 6 confident stickers and all were discarded. The 10.19 replay keeps no side face in 26 of
its 30 scenario rows with data.

## F8. One frame decides every sticker

`QuickScanController.take` copies the latest frame's cells. With two exposure levels (F1) the captured values depend
on which level that frame fell in, while confidence needs a ΔE 10 margin. A median over a short window does not
address this: it adds nothing at steady exposure and little hand-held (10% of readings beyond ΔE 10 becomes 7%).

## F9. Saved calibration can veto a cube it does not match

The second read applies an L*-only gain and compares with the saved centers (`QuickScanClassification.corrected`). Hue
and chroma are not adapted. Capture 1's green face was confident on all nine stickers against the cube's own centers
and rejected on all nine by the saved calibration. The calibrated light check uses the same saved centers.

## F10. Logo centre reads unstably

The top centre sticker, which carries a logo in the burst picture, read up to ΔE 36 apart between frames on a still cube. The
per-face correction (F9) and the pose check use centre cells.

## F11–F13

- `QuickScanController.backOut` does not clear `previous` (also item 3 of the 2026-11-21 code review on
  `review/3d-quickscan-2026-11-21`).
- `GuidedScanActivity` meters hold 2 with `includeAwb = false` while AWB stays in continuous auto.
- `ScanDiagnostics` throttles frame lines to one per 500 ms. CameraX debug logging filled the log buffer during
  capture 1.
