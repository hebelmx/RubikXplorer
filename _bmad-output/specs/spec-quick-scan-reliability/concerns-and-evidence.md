# Concerns and Evidence

What the owner reported, what the repository shows, and what was measured on 2026-11-22. Causes are in
[failure-modes.md](failure-modes.md); fixes in [fix-plan.md](fix-plan.md). Raw logs and burst data are in
[docs/evidence/quick-scan-2026-11-22](../../../docs/evidence/quick-scan-2026-11-22/README.md).

## User observations

- After positioning the cube inside the static three-axis **guide**, automatic capture can take about 3–4 seconds and sometimes 30 seconds or longer.
- **All attempts so far were made with the tablet, the cube and the light fixtures still.** The reported times are for these ideal conditions, not hand-held use.
- The detected-corner **grid** moves as the model updates its estimate. It improved substantially with LiteRT compared with earlier sessions, but its stability is still in question.
- The learner cannot tell what the app is waiting for. Existing viewfinder feedback and logs have not made the delay easy to diagnose.
- Sticker colors appear to vary with lighting; white balance is a suspected contributor.

## What repository evidence supports

| Concern | Code/evidence | Finding |
| --- | --- | --- |
| Guide alignment | `app/src/camera/kotlin/dev/cubentia/app/camera/ViewfinderGrid.kt` maps the centered overlay through the preview crop, rotation, and fill scaling to camera-frame coordinates. `ScanFrameSource.kt` uses that mapping for analysis. | The intended coordinate mapping is shared. This does not prove the rendered guide matches the physical cube on the device; a recording is needed to judge that. |
| Detected grid movement | `GuidedScanActivity.kt` updates `detectedCornerQuads` from `CornerOverlaySmoother`. `CornerLocator.kt` applies an EMA to small changes, clears on detection loss, and snaps on a large change. | Some movement is expected. Detection loss and large pose changes can make the overlay disappear or jump; code alone cannot establish whether the observed motion is acceptable. |
| Long capture time | `QuickScanController` considers detection, camera metering settlement, the light gate, stable sticker readings, and the changed-hold check. The app records per-frame corner diagnostics, but no complete user-visible wait breakdown. | There are several possible blockers. Existing evidence does not identify which one accounts for a 3–30+ second user wait. |
| LiteRT speed | `docs/evidence/10-18/litert-model-evaluation.md` reports about 14 ms median inference in a Lenovo device test. | This is model inference time per tested frame, not time from cube positioning to capture. It cannot explain the reported wait by itself. |
| Color reliability | `docs/evidence/10-18/quick-scan-litert-escalation.md` records Red/Orange uncertainty on shaded side faces. Kotlin reads sampled colors and classifies in CIELAB; follow-up work adjusts the sample ROI and when AWB is re-metered. | Lighting and side-face color separation are credible concerns. Evidence does not establish that Kotlin is inherently insufficient or that a learned classifier would improve results. |
| Existing captures | `Fixtures/corner/` and `Fixtures/live/` contain fixture images and labels; `docs/evidence/10-18/lenovo-quickscan-2026-11-20-6cd18ab1.log` is a historical diagnostic log. | These are not a recording of the newly reported slow session. The repository does not establish whether screenshots from other sessions were saved outside it. |

## Bench evidence, 2026-11-22

Lenovo TB128XU, Android 13, no flash, tablet on a base, `lightMode=calibrated`. App 0.1.0 installed 2026-11-21 for
the captures; a debug build of `audit/quick-scan` for the bursts.

### Quick Scan screen recordings

| | Capture 1 (solved-looking cube) | Capture 2 (scrambled cube) |
|---|---|---|
| Hold 1, time to capture | about 2 min; one light hold of 52.9 s | 19.2 s |
| Hold 2 | not captured in 65 s | 24.8 s |
| Faces kept | 2 of 3 (green face: 9 of 9 rejected, `disagree=9`) | 0 of 6 (7, 7, 6, 8, 2, 6 confident of 9) |
| Light check, logged frames | 125 fail, 43 pass | 37 fail, 33 pass |
| Metering requests | 22 in the surviving 123 s | 8 in 44 s |
| After capture | recording ended on corner 2 | face-by-face rejected 20 frames as MISALIGNED, cube still corner-on |

Capture 1's log lost its first minute. Its final screenshot shows the first corner's faces filled in the net beside
the live second corner, including the green face that was in fact rejected: a learner could read a filled face as
confirmed. Capture 2, hold 1, splits into light holds 8.2 s, re-meters after each clear
3.8 s, steady restarts 4.8 s and first settle 1.5 s.

### Recorder bursts

Each burst records, per analysis frame, the detected quads, the 27 sticker readings, face quality and the camera's
exposure. Analysis ran at about 12 frames per second.

| | Still, 60 frames | Still, 150 frames | Hand-held, 120 frames |
|---|---|---|---|
| Cube detected, all faces readable | 60 of 60 | 150 of 150 | 120 of 120 |
| Exposure | moving from 1.3 s to 2.8 s after metering | one level 0.4–3.5 s, a second level after | changed at 0.5 s and 3.6 s |
| Sticker variation with exposure steady | 1 sticker (logo centre) | ΔE 0.3–0.5; none reach 5 | not separable from tilt |
| Stickers ΔE ≥ 5 from their median | up to 19 of 27 during the swing | 14–22 of 27 on the first level | 33% of readings (10% at ΔE ≥ 10) |
| Outline movement | 5 px typical, 13 px worst | 1 px typical | 1.7% of cube size per frame, 5.3% worst |

Replaying the hand-held burst through steady rules (1-second window; light and metering gates ignored):

| Rule | Restarts | Windows completed in 9.7 s |
|---|---|---|
| Current: two stickers of a face change by ΔE 5 | 56 | 1 |
| Current rule on a 5-frame median | 20 | 1 |
| Outline moved more than 3% of cube size | 17 | 3 |
| Outline moved more than 5% | 4 | 6 |
| Outline moved more than 8% | 0 | 8 |

A labelled corner fixture group, `lenovo-tb128xu_stla_cool-2` (pipeline 10.19, front light), was recorded in the same
session and is held locally for Story 10.20.

## Evidence limits

- One device, one room, one session. No Galaxy S24 FE data.
- The Quick Scan recordings were made with everything still. The only hand-held data is one recorder burst, which
  does not run the Quick Scan gates.
- No ground-truth colors exist for the two recorded scans. The videos were not compared with the logs frame by frame.
- The hand-held 33% and 10% figures compare each reading with its median over 10 seconds of tilting, so they include
  real lighting change.
- This camera reports white-balance gains as a constant, so the bursts cannot show white-balance drift.
- The 14 ms LiteRT measurement is per-frame inference, not capture latency.
- Static source inspection can assess the intended preview-to-frame transform but cannot validate its appearance on the user's current device, screen layout, or session.
- The source commit of the app build used for captures 1 and 2 was not recorded.
