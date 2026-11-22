# Story 10.22 evidence: Quick Scan captures promptly once the cube is in view

Threshold derivations with their replays, the 2026-11-22 baseline, and the device-session table for the human gate.
The recorded data is in [`../quick-scan-2026-11-22/`](../quick-scan-2026-11-22/README.md): five recorder bursts from
the Lenovo TB128XU (two with tablet and cube resting, two hand-held, one moved quickly on purpose).

**Status:** implementation and replays done. The device session (10 resting and 10 hand-held holds on each of the
Lenovo TB128XU and the Galaxy S24 FE, every hold auto-capturing within 10 s) is **not run yet**; its table at the end
is empty.

## What changed

| Area | Before (Story 10.19) | Now |
| --- | --- | --- |
| Settle signal | Metering future done and AE state converged | Metering future done, then exposure time × ISO within 6% for 600 ms; cap 1.5 s unchanged |
| Exposure | Metering action only (cancels after 3 s, exposure returns to the scene) | AE lock from settle until the capture or a re-meter; AWB locked at hold 1 and carried through hold 2 |
| Steady rule | Two cells of one face moving ΔE 5 | Outline corner moving 0.10 of the outline's bounding-box diagonal; two plain centres changing ΔE 15 means another face |
| Light check | Blocks auto-capture and the shutter; clearing re-meters | Coaching only; clearing changes nothing else |
| Re-meter | Cube lost, torch change, light hold cleared | Cube lost, torch change, outline 0.30 of its diagonal from where it was metered, faces' mean luma changed 25% since the settle |
| Turn check (hold 2) | Top centre ΔE 15 on one frame | Two plain centres changed, on 3 consecutive detected frames; a centre that is not plain (a logo) is left out |
| Diagnostics | Throttled restart lines, no trigger | One line per state change with `hold=` and `t=` ms; capture line with trigger, times and light |

## Thresholds and their replays

Every number below is asserted by a test that reads the committed bursts:
`QuickScanBurstReplayTest` (`:core:domain`) for the outline, face and re-meter rules, and
`ScanFrameSourcePolicyTest` (`:app`, test "still burst 1's exposure swing is reported not settled throughout") for
the settle signal.

### Outline steady fraction: 0.10 (`CornerHold.OUTLINE_STEADY_FRACTION`)

Measure: the largest displacement of one of the twelve quad points from the steady window's reference frame, divided
by the diagonal of the reference outline's bounding box. A cube lost for 300 ms also ends the window. Cells are
restarts / completed 1 s windows over the whole burst, for the outline rule alone.

| Fraction | still (5.1 s) | still-long (13.0 s) | handheld (9.9 s) | handheld-2 (9.8 s) | fast (6.7 s, detected in 56 of 120 frames) |
| --- | --- | --- | --- | --- | --- |
| 0.04 | 5 / 2 | 0 / 11 | 11 / 5 | 32 / 1 | 44 / 0 |
| 0.05 | 2 / 3 | 0 / 11 | 4 / 6 | 13 / 2 | 38 / 0 |
| 0.06 | 2 / 3 | 0 / 11 | 3 / 6 | 7 / 6 | 34 / 0 |
| 0.07 | 0 / 4 | 0 / 11 | 1 / 7 | 7 / 5 | 29 / 0 |
| 0.08 | 0 / 4 | 0 / 11 | 0 / 8 | 2 / 7 | 22 / 0 |
| **0.10** | **0 / 4** | **0 / 11** | **0 / 8** | **2 / 7** | **16 / 0** |
| 0.12 | 0 / 4 | 0 / 11 | 0 / 8 | 2 / 7 | 9 / 1 |
| 0.15 | 0 / 4 | 0 / 11 | 0 / 8 | 2 / 7 | 7 / 2 |
| 0.20 | 0 / 4 | 0 / 11 | 0 / 8 | 0 / 8 | 3 / 2 |

Frame-to-frame displacement (median / 90th percentile / maximum): still 0.010 / 0.027 / 0.038, still-long
0.004 / 0.008 / 0.029, handheld 0.017 / 0.032 / 0.053, handheld-2 0.026 / 0.044 / 0.080, fast 0.069 / 0.111 / 0.128.

Reading: a resting cube needs at least 0.07 (the detector's own jitter reaches 0.06 on still burst 1). From 0.08 to
0.15 both hand-held bursts give the same result. Above 0.10 the fast burst starts completing windows. 0.10 is the
largest value at which fast movement completes none.

### The whole hold, replayed (`CornerHold` with the settle signal always true)

| Burst | Completed windows | Outline restarts | Different-face restarts | Cube lost | Drift re-meters |
| --- | --- | --- | --- | --- | --- |
| still | 4 | 0 | 0 | 0 | 0 |
| still-long | 10 | 0 | 0 | 0 | 0 |
| handheld | 8 | 0 | 0 | 0 | 0 |
| handheld-2, missing face filled in | 6 | 2 | 1 | 0 | 0 |
| handheld-2, as recorded | 0 | | | | |
| fast, missing faces filled in | 0 | 15 | 0 | 1 | 1 |

**Hand-held burst 2 reads its right face in only 21 of 120 frames.** Auto-capture still requires all three faces to be
readable (unchanged by this story), so that burst as recorded completes no window; the steady rule itself completes six
once the missing face is filled in with a neutral one. If the device session shows hand-held holds missing 10 s for
this reason, the blocking gate to record is "face unreadable" (`steadyRestart reason=quality` and `quality=…,-` on
the frame lines), and it goes to the product owner.

### Different face: 2 plain centres changed by ΔE 15 (`CornerHold.FACE_CHANGE_CENTRES`)

Colors no longer judge steadiness. They only detect that another face is shown: two of the three centres, each plain
in both frames, differ by `FACE_CHANGE_DELTA_E` (15) from the reference frame. On the two resting bursts no centre
moves by 15 within a second (largest 14.6, during the exposure swing). A single centre is not enough: the logo top
centre of the recorded cube reads up to ΔE 36 apart between frames (F10). A rule on the majority of a face's cells
(5 of 9 at ΔE 15) was rejected: it restarts 6 times on hand-held burst 2.

### Settle: exposure time × ISO within 6% for 600 ms (`SETTLE_TOLERANCE`, `SETTLE_WINDOW_MS`)

Still burst 1 alternates 20 ms × ISO 125–147 and 30 ms × ISO 100–116 between 1.5 s and 3.3 s, while 17 of its 22
frames in that span report AE converged. Inside a level the product moves about 5%; between the two levels about 14%.
Cells: frames between 1.5 s and 3.3 s that a rolling window would report settled (0 is required).

| Tolerance | 300 ms | 400 ms | 500 ms | **600 ms** | 750 ms |
| --- | --- | --- | --- | --- | --- |
| 0.04 | 3 | 1 | 0 | 0 | 0 |
| 0.05 | 3 | 1 | 0 | 0 | 0 |
| **0.06** | 4 | 2 | 0 | **0** | 0 |
| 0.08 | 4 | 2 | 0 | 0 | 0 |
| 0.10 | 8 | 6 | 2 | 0 | 0 |
| 0.12 | 10 | 8 | 4 | 2 | 1 |

0.06 / 600 ms has a clean neighbour on every side (500 and 750 ms; 0.04 to 0.10). The shipped tracker is stricter than
the table's rolling window: a result outside the tolerance, a result with AE searching, or a result without exposure
data starts the level over. The test asserts that, whenever the metering future completes at or before 3.3 s, no frame
of the swing reads settled.

The nine constant frames before the swing (0.37 s to 1.04 s) do settle, at 0.79 s. That is accepted by design: the
exposure is locked at that moment, so the swing that followed in the recording (no lock then) cannot happen. After the
swing the level settles at about 4.0 s.

A camera that reports no exposure time or ISO never settles by this rule; the 1.5 s cap releases the wait and the line
says `timeout=true`. A camera without AE lock behaves as before and the line says `lock=unsupported`.

### Re-meter bounds

- **Outline 0.30 of the metered outline's diagonal** (`REMETER_OUTLINE_FRACTION`). Largest corner displacement from
  each burst's first detected frame: still 0.074, still-long 0.030, handheld 0.060, handheld-2 0.131, fast 0.207. The
  bound is above all of them, so a hand-held hold keeps its lock; in the hold replay only the fast burst trips
  a drift re-meter (once).
- **Faces' mean luma 25%** since the frame the metering settled on (`REMETER_LUMA_FRACTION`). Largest change between
  two frames at the same exposure time × ISO: still 0.085, still-long 0.043, handheld 0.103, handheld-2 0.031, fast
  0.129. At any exposure the largest is 0.184 (fast). The bound is about twice the largest hand-held value.

Neither bound has a recorded case that should trigger it (a cube carried out of the metered region, a lamp switched).
They are safety bounds and should be checked in the device session.

### Turn and pose checks at hold 2

`QuickScanBurstReplayTest` captures hold 1 on each of still burst 1's 60 frames and then shows all 60 as hold 2 with
the cube not turned: no automatic capture and no shutter capture in any of the 3,600 frames. In that burst the top
centre is plain in 13 frames, not plain in 47, and reads up to ΔE 36 from its first value, which the old check
(top centre ΔE 15, one frame) accepted as a turn.

## Reading the log

```
quickScan viewfinder hold=1 t=0
quickScan firstFrame hold=1 t=412
quickScan cubeFound hold=1 t=500
quickScan meteringRequested hold=1 t=500 reason=first
quickScan metering supported=true region=outline
quickScan metering settleMs=780 timeout=false lock=on+awb futureMs=150
quickScan meteringSettled hold=1 t=1300 ms=800
quickScan steadyStart hold=1 t=1300
quickScan steadyRestart hold=1 t=1380 reason=outline amount=0.123
quickScan capture hold=1 t=2400 trigger=auto
quickScan exposureLock released confirmed=true heldMs=1105
quickScan capture hold=1 trigger=auto totalMs=2400 undetectedMs=88 sinceDetectedMs=1900 lightScore=1.210 light=pass U:center=WHITE,…
```

`t` is milliseconds since the hold began (the viewfinder opening for hold 1, the previous capture for hold 2).
`sinceDetectedMs` on the capture line is the figure the 10 s target is measured on: the time from the first detected
frame of the hold that passed the pose and turn checks to the capture. At hold 2 that is the first frame showing the
cube turned to the other corner (the `poseAccepted` line), not the first corner still in view after capture 1.
On the `metering settleMs=` line, `lock=` is the lock asked for and `futureMs=` the part of the wait the metering
future took; `exposureLock released confirmed=` says whether a capture result reported AE LOCKED while the lock was
wanted. `meteringSettled` carries `timeout=true` only when the hold's own 1.5 s cap released the wait before the camera
adapter reported settled; the adapter's own timeout is on the `metering settleMs=` line. A lost cube releases the
exposure lock. Other events:
`cubeLost`, `poseRejected reason=centres|turn`, `poseAccepted`, `meteringRequested reason=first|lost|torch|outline|brightness`,
`lightFail`, `lightClear`, `steadyRestart reason=outline|face|quality`. `quickScan frame` lines stay sampled at one per
500 ms.

## Baseline, 2026-11-22 (before this story)

Lenovo TB128XU, tablet, cube and lights resting ([source](../quick-scan-2026-11-22/README.md)).

| Recording | Hold | Time to capture | Notes |
| --- | --- | --- | --- |
| 1 | 1 | about 2 min | one light hold of 52.9 s; the first minute of the log was lost |
| 1 | 2 | not captured in 65 s | six light holds, 48 of 228 frames undetected |
| 2 | 1 | 19.2 s | probably ended by a manual press; seven steady restarts (ΔE 5.3–8.5) |
| 2 | 2 | 24.8 s | three light holds |

Floor if every gate passes first time: about 3 s (never observed).

## Device session (human gate, AC7): not run yet

Main app build, `QUICK_SCAN` raised in debug, from the Solve tab. Per device: at least 10 holds with everything
resting and 10 hand-held. Take `sinceDetectedMs`, `undetectedMs`, `trigger` and `light` from each
`quickScan capture … totalMs=` line. A hold passes when `trigger=auto` and `sinceDetectedMs` ≤ 10000. For a hold
that misses, name the gate it waited on from the timeline lines above; it goes to the product owner.

| Device | Mode | Run | Hold | sinceDetectedMs | undetectedMs | trigger | light | lock | Pass | Blocking gate if missed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Lenovo TB128XU | resting | | | | | | | | | |
| Lenovo TB128XU | hand-held | | | | | | | | | |
| Galaxy S24 FE | resting | | | | | | | | | |
| Galaxy S24 FE | hand-held | | | | | | | | | |

| Device | Mode | Holds | Median (ms) | Worst (ms) | All within 10 s |
| --- | --- | --- | --- | --- | --- |
| Lenovo TB128XU | resting | | | | |
| Lenovo TB128XU | hand-held | | | | |
| Galaxy S24 FE | resting | | | | |
| Galaxy S24 FE | hand-held | | | | |

Also to record per device: one recorder burst during a hold, showing one exposure level (exposure time × ISO within
6%) from settle to capture, and the `lock=` word of the settle lines (`on`, `on+awb` or `unsupported`).
