# Quick Scan fix analysis — why capture is slow and why it keeps nothing

Written 2026-11-22 from a source read at 43e4f472 and the two bench captures in
[docs/evidence/quick-scan-2026-11-22](../../../docs/evidence/quick-scan-2026-11-22/README.md). It complements the
[pipeline audit](pipeline-audit.md): the audit lists what to measure; this note says what the two captures already
answer, which code is responsible, and what to change. Nothing here has been implemented or tested yet.

## The two problems

1. **Capture waits far longer than the gates need.** With every gate passing on the first try, a hold needs about
   3 s (settle up to 1.5 s, then 1 s steady). Measured: 19.2 s and 24.8 s (capture 2), about 2 min (capture 1, hold 1).
2. **A captured hold rarely produces kept faces.** Capture 2 captured both holds and kept 0 of 6 faces. The project's
   own replay (`docs/evidence/10-19/light-check-and-replay.md`) keeps no side face in 26 of the 30 scenario rows
   that have data, and no row keeps more than half.

Fixing only the first makes Quick Scan a faster route to face-by-face. Both need fixing.

## Where the time goes (capture 2, hold 1, 19.2 s)

| Span | Seconds | Cause |
|---|---|---|
| First settle | 1.5 | Metering wait hit its cap (`timeout=true`) |
| Three light holds | 8.2 | Score 1.32–1.38 against a cut of 1.34 |
| Three extra re-meters after each clear | 3.8 | `CornerHold` forces a metering change when a hold clears |
| Steady window restarts | 4.8 | Seven restarts on a tablet and cube that were both resting; 1.0 s was needed |
| Remainder | 0.9 | |

Light accounts for about 12 s of 19, steady restarts for about 4.

## Findings in the code

### F1. The light hold flaps around its cut (confirmed in both logs)

`SideLight.MAX_TOP_OVER_SIDES_RATIO` = 1.34 is the midpoint between two clusters of 16 fixture holds (1.286 and 1.392),
and the evidence file calls it provisional. On the bench the score sat on the cut: capture 1 logs 1.339 pass, 1.349
fail, 1.334 pass in consecutive frames, and later 1.341, 1.346, 1.339, 1.336, 1.341. `CornerHold.updateLight`
(CornerHold.kt:257-283) engages the hold on a single failing frame once settled and needs 500 ms of passes to clear, with
no gap between the engage and clear levels. A reading this close to the cut therefore toggles the hold indefinitely.

### F2. Clearing a light hold re-meters, and the re-meter re-engages it (confirmed)

CornerHold.kt:160 treats `lightCleared` as a metering change. After each clear the camera re-meters on the cube
(0.9–1.5 s), and the hold may engage on the first settled frame. In capture 2 that is what happened at 11:19:37.128,
11:20:01.311 and 11:20:03.532: `metering settleMs=…` and `lightHold start` carry the same or the next millisecond.
Capture 1 shows the same at 11:10:47.439. Passing readings tend to appear 2–4 s after a metering request and failing
ones within 1 s of it, which fits the 3-second metering lock (`METERING_LOCK_SECONDS`) expiring and exposure returning
to the whole scene. That link is a hypothesis; the logs do not record exposure.

### F3. The light hold cannot be overridden

`CornerHold.press` returns `Ignored` while the hold is active, so the manual shutter does nothing. The learner's only
exit is *Scan face by face*.

### F4. The light hold is not what keeps wrong colors out

The replay's safety result (0 confidently wrong stickers on kept faces in every scenario) was obtained on captures that
predate the light check. The agreement gate in `QuickScanClassification` provides that safety. The light hold adds
waiting without being needed for it. Capture 2 passed the light check at capture time (1.05–1.12) and still had 17 of
54 stickers uncertain.

### F5. The steady window measures color noise, so it restarts on a still cube (confirmed)

`CornerHold.steadyMove` restarts the 1 s window when two cells of a face differ by ΔE ≥ 5 from the reference frame.
With tablet and cube resting, capture 2 logged restarts at ΔE 5.3–8.5 on the same cells again and again (face 1 cells
1 and 3; face 0 cells 2, 3 and 8). The cause is not established: candidates are frame-to-frame movement of the detected
quads shifting the sample areas, and exposure or white balance drifting after the metering lock expires. Either way
the test does not measure what it is for (the cube being held still).

### F6. One frame decides every sticker

`QuickScanController.take` copies the cells of the latest frame only. F5 shows the same cell varying by ΔE 5–8 between
frames, and a sticker is CONFIDENT only with a 10 ΔE margin (`CLOSE_CALL_DELTA_E`). A single-frame read therefore lands
on either side of that margin by chance.

### F7. One uncertain sticker discards the face

`QuickScanClassification.needGrid` sends a face to the grid when any judged sticker is not CONFIDENT. Capture 2's faces
had 7, 7, 6, 8, 2 and 6 confident stickers of 9 and all six were discarded. On a scrambled cube this rule rejects
nearly every face. Story 10.21 (backlog) already targets it.

### F8. A saved calibration can veto a cube it does not match

With a saved calibration the second read compares against the saved centers after an L*-only gain
(`QuickScanClassification.corrected`). Hue and chroma are not adapted. Capture 1's green face had all nine stickers
confident in the self-calibration read and all nine rejected by the saved one (`disagree=9`) on a solved-looking
face. The calibrated light check uses the same saved centers. Which cube and light the saved calibration came from was
not recorded, so a mismatch is the likely explanation, not a proven one.

### F9. Smaller defects

- `QuickScanController.backOut` does not clear `previous`, so a back-out after hold 1 keeps stale turnover state
  (also item 3 of the 2026-11-21 code review on `review/3d-quickscan-2026-11-21`). Confirmed by reading; not reproduced.
- Hold 2 meters without AWB while AWB stays in continuous auto, so white balance can differ between the holds whose
  centers are combined into one calibration. Unverified.
- Diagnostics: frame lines are throttled to 500 ms, and CameraX debug logging (`CXCP`) filled the log buffer and cost
  capture 1 its first minute.

## Fix plan

Ordered so each step can be checked with the same bench recording. Steps 1–3 are domain-only changes with unit tests in
`QuickScanControllerTest`; none needs new fixtures.

1. **Light check stops blocking.** Keep the sides-dark coaching line; let capture proceed. F4 is the argument. If the
   PO wants a block kept: engage above 1.40 for three consecutive frames, clear below 1.30, cap the hold at 3 s, and let
   the manual shutter through. Either way remove the re-meter on clear (F2).
2. **Steady by geometry.** Restart the window when the detected corners move more than a small share of the outline
   diagonal, not when colors differ. Keep a color check only against a different face being shown.
3. **Read over the steady window.** Keep the per-cell Lab of every frame in the window and capture their median (F6).
   This is the first step of the multi-frame idea in the audit, with no new model.
4. **Keep stickers, not faces.** Carry each uncertain sticker with its two nearest colors and let the cube decide:
   `ScanAssembler` already enumerates option grids and checks piece legality. If exactly one legal cube fits, finish.
   Otherwise ask the learner about the remaining stickers in review, or for the one face that holds them. This is Story
   10.21 made concrete.
5. **Calibrate on this cube.** After hold 2 all six centers of the cube in hand are known under the current light. Use
   them as the primary reference and use a saved calibration only where it agrees with them; drop it when its centers
   are far from this cube's (F8).
6. **Metering.** Lock exposure and white balance once after hold 1 settles and keep them through hold 2; re-meter only
   when the cube was lost. Removes F2's exposure swings and the AWB difference in F9.
7. **Small fixes.** Clear `previous` in `backOut`; log state transitions with a per-hold elapsed clock; raise the log
   buffer before recording.

Expected effect, to be verified: steps 1–2 take capture 2's hold 1 from 19 s toward the 3 s floor; steps 3–5 decide
whether two holds finish the scan.

## How this fits the existing documents

- The audit's first item (attribute the wait) is largely answered for the bench case: light hold, its re-meters and
  steady restarts. Handheld use still needs a run.
- The audit's "gather several views" and "keep the faces that read well" ideas are steps 3 and 4.
- The hybrid LiteRT color reader (SPEC CAP-3) needs labelled data from Story 10.20. Steps 3–5 need none, so they come
  first; the hybrid reader stays an option if correct crops still read poorly afterwards.
- The audit says to keep release Quick Scan OFF until the full flow is measured. That gate is unchanged by this plan;
  the plan is how to get a flow worth measuring.

## Limits of this analysis

Two runs, one device, one room, tablet on a base, no ground-truth colors. The videos were not compared with the logs
frame by frame. The frame rate of the analyzer is unknown from these logs. No fix has been tried.
