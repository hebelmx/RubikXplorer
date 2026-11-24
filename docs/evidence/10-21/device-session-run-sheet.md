# Story 10.21: device-session run sheet

Story 10.21's device and PO acceptance (AC7, AC8 and the PO light decisions) needs the two physical reference devices. The offline replay is done: safety is enforced, coverage is reported in [tuning-and-replay.md](tuning-and-replay.md). This sheet lists each device check with its steps and the fields to record. Nothing on it has been run yet.

## Setup (record once per device)

- Build: main app **debug** build of this branch (revision `git rev-parse HEAD`), `QUICK_SCAN` raised in debug only, so the release flag stays OFF (AD-18). Use the real entry point: Solve tab → Quick Scan. Never use a debug-only launcher.
- Devices: Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) and Galaxy S24 FE (flash). Record model, OS version and app build.
- Cubes: stickered, stickerless and glossy. Record the cube id (`stla` GAN, `stlb` other Chinese, `stka` Rubik's, `stkb` generic).
- Light: the front-lit and backlit setups (lamp positions, warm or cool), described well enough to repeat.
- Diagnostics: `adb logcat -s CubentiaScanDiag` for the whole session, saved per device.

## AC7: 10 front-lit + 10 backlit Quick Scans per device

Steps for each scan: open Quick Scan, take both corners (automatically where possible), follow the result through the hand-over or review, then compare every kept sticker with the physical cube. Repeat a run spoiled by a setup error and record both attempts.

| field | where it comes from |
|---|---|
| device / run # / cube / light (front, back; warm, cool) | operator |
| torch requested and lit, per hold (S24 FE) | `capture … torch=lit|off` and the toggle state |
| saved calibration: present? torch tag? match | `capture … calibration=match|mismatch torch|mismatch centre|none` |
| hold 1 / hold 2 `sinceDetectedMs` | `capture … sinceDetectedMs=` |
| trigger (auto or manual) | `capture … trigger=` |
| blocking gate on any wait over 10 s | `poseRejected reason=` (`few-confident amount=<count>`, `centres`, `turn`), metering lines |
| coaching shown (sides-dark, glare, …) | screen, `lightFail` and `lightClear` lines |
| **front-lit false block** (sides-dark coaching on a front-lit hold) | screen and `lightFail` |
| **backlit missed block** (no sides-dark coaching on a backlit hold) | screen and `lightFail` |
| side faces kept (of 4) | `capture … kept|grid` per face, review |
| blanks on kept faces, pre-selected in review? | `kept,…,blanks=n`, review screen |
| confidently wrong kept stickers / Red↔Orange (must be 0) | review vs physical cube |

Summary per device: front-lit false blocks out of 10, backlit missed blocks out of 10, holds over 10 s from detection (with their gate), distribution of side faces kept per scan, wrong kept stickers.

### Glossy cube with the torch (S24 FE)

For a glossy cube, run at least 3 front-lit scans with the torch lit and 3 with it off. Record glare coaching (`glare=torch|plain`), faces kept and wrong kept stickers. Record any torch failure (requested but never `lit`).

### Lenovo (no flash)

Confirm that no torch control shows in the corner view or in calibration. A calibration saved there is tagged torch-off and matches (`calibration=match`).

## Calibration torch and match checks

1. Galaxy: open calibration from **Settings** and from **review** → the torch toggle shows "Torch on".
2. Galaxy: in the corner view turn the torch **off**, then tap *Calibrate colors* (the offer) → calibration opens with "Torch off". Toggle it on, go back → the corner view shows "Torch on" (the choice carries).
3. Keep the first centre swatch → the toggle stays visible but is disabled until *Save* or leaving.
4. Save with the torch lit → the next Quick Scan with the torch lit logs `calibration=match`. Turn the torch off → `calibration=mismatch torch`, and Quick Scan behaves as with no calibration (the offer can appear).
5. Torch asked for but not lit (cover the flash or force a failure if possible): the light check still runs, and the match uses "off".

## Calibration offer and return

- Torch off, light coaching held for 5 s → *Calibrate colors* shows. Repeat with a mismatched saved calibration: it must show too.
- Return via **Save**, **Close** and **Back**. Each time the sides-dark line is gone, and the offer needs a fresh 1 s + 5 s of failing light (`lightClear reason=calibration` on Close or Back; a new controller on Save).

## Confident-count wait (live 10 s check)

- Hold a corner whose stickers read poorly (half-hidden or very dim) → no auto capture. The hold's prompt stays and there is no extra coaching line. `poseRejected reason=few-confident amount=<count>` is logged, and the shutter still captures.
- On every auto capture of the AC7 runs, check `sinceDetectedMs ≤ 10000`. Record each miss with its blocking gate and route it to the PO.

## PO light decisions (record the answer)

- **Light-clear steadiness:** with coaching on, move the light until the score is between the cuts (`lightScore` between 26.6 and 27.9 uncalibrated, or 1.33 and 1.35 calibrated). The line must stay with no flicker (`quickScan light flip` must not appear). Then clear the light: the line goes after about 500 ms on a steady view and restarts when two cells of one face change. The PO confirms the feel: yes / no / notes.
- **Backlit blocking:** in this story the light check only coaches. After the backlit runs, the PO decides whether a backlit hold should block capture: decision, date, notes. This is a later story if yes.
- The calibrated hold cut (1.35) was fitted on front light only. Note whether a backlit hold with a *matching* calibration trips it.

## AC8: BETA call record (template)

| field | value |
|---|---|
| date, build revision, PO | |
| offline replay (label commit, exclusions hash, safety result, coverage per condition) | from `replay-10-21.md` |
| device results per device (false blocks, missed blocks, waits over 10 s, faces kept, wrong kept, Red↔Orange) | from the AC7 tables |
| pastel gate decision (plain fit as committed, or pastel-inclusive Red 44.1) | see tuning-and-replay.md |
| coverage regression vs the 10.19 gate, weighed (fewer side stickers correct on 2 of 3 scored held-out groups: `stka_bar-cool-3` 23 → 20 of 32, `stka_bar-warm-2` 16 → 13 of 32; tuning faces kept 43/156 → 18/156) | see tuning-and-replay.md |
| backlit blocking decision, light-clear steadiness confirmation | |
| decision: `QUICK_SCAN` to BETA, or stays OFF | |
| rationale, follow-ups (coverage issue draft, 10.26 go/no-go) | |
