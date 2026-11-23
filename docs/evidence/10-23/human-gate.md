# Story 10.23: human gates (open for the PO)

Story 10.23 (*A shadow sticker reader runs beside the Kotlin reads*) leaves three steps that need a person with the
devices. None is done here; the tables are empty to fill. Nothing in them gates a build: the reader decides nothing, and
any later mode (second read or rescue) needs a further SCP.

Build: the main app **debug** build from its real entry point (Solve tab, *Quick Scan*), `QUICK_SCAN` raised in debug.
Logs: `adb logcat -s CubentiaScanDiag`. Toggle without reinstalling:

```bash
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.SHADOW_READER --ez on false   # off
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.SHADOW_READER --ez on true    # on (default)
```

Each scan's first reader line is `quickScan shadowReader state=on|off`; with the reader on, each capture logs three
`quickScan shadow hold=… face=… inferMs=… model=… self=… second=…` lines; the per-hold capture times are the Story 10.22
`quickScan capture hold=… totalMs=…` lines.

## H1: extra fixture captures where the inventory is thin

`inventory.md` marks every device x cube x light cell thin (fewer than 2 groups or 12 image-backed faces). Record with the
debug fixture recorder (corner mode, `Fixtures/README.md`), label, pull into `Fixtures/corner/`, then re-run
`assemble.py` (new groups train unless named in `holdout.json`). Priority: what does not exist at all.

| # | Device | Cube | Light / torch | Why | Groups recorded | Faces | Date | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Galaxy S24 FE | stka (stickered) | front, torch off | no S24 FE corner capture exists | | | | |
| 2 | Galaxy S24 FE | stla (stickerless) | front, torch off | no S24 FE corner capture exists | | | | |
| 3 | Galaxy S24 FE | stka / stla | backlit or side-shaded | no shaded or backlit S24 FE face exists | | | | |
| 4 | Galaxy S24 FE | stka / stla | torch on | no torch-on capture exists | | | | |
| 5 | Lenovo TB128XU | stkb, stlb | front (warm or cool-front) | corner captures of these cubes are backlit only | | | | |
| 6 | Lenovo TB128XU | any | a second group per existing cell | every cell has one group | | | | |

## H2: device session, reader on and off (AC8)

Per device, at least 10 Quick Scans with the reader on and 10 with it off. Record each scan; then the summary. The aim is
to show the reader does not slow capture (inference runs on its own thread).

### Lenovo TB128XU

| Scan | Reader | Hold 1 totalMs | Hold 2 totalMs | inferMs (3 faces, hold 1) | inferMs (3 faces, hold 2) | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | on | | | | | |
| 2 | on | | | | | |
| 3 | on | | | | | |
| 4 | on | | | | | |
| 5 | on | | | | | |
| 6 | on | | | | | |
| 7 | on | | | | | |
| 8 | on | | | | | |
| 9 | on | | | | | |
| 10 | on | | | | | |
| 11 | off | | | - | - | |
| 12 | off | | | - | - | |
| 13 | off | | | - | - | |
| 14 | off | | | - | - | |
| 15 | off | | | - | - | |
| 16 | off | | | - | - | |
| 17 | off | | | - | - | |
| 18 | off | | | - | - | |
| 19 | off | | | - | - | |
| 20 | off | | | - | - | |

### Galaxy S24 FE

| Scan | Reader | Hold 1 totalMs | Hold 2 totalMs | inferMs (3 faces, hold 1) | inferMs (3 faces, hold 2) | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | on | | | | | |
| 2 | on | | | | | |
| 3 | on | | | | | |
| 4 | on | | | | | |
| 5 | on | | | | | |
| 6 | on | | | | | |
| 7 | on | | | | | |
| 8 | on | | | | | |
| 9 | on | | | | | |
| 10 | on | | | | | |
| 11 | off | | | - | - | |
| 12 | off | | | - | - | |
| 13 | off | | | - | - | |
| 14 | off | | | - | - | |
| 15 | off | | | - | - | |
| 16 | off | | | - | - | |
| 17 | off | | | - | - | |
| 18 | off | | | - | - | |
| 19 | off | | | - | - | |
| 20 | off | | | - | - | |

### Summary

| Device | Reader | Scans | inferMs median | inferMs worst | Hold 1 totalMs median | Hold 2 totalMs median | Faces where model and gated Kotlin disagree (both confident) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lenovo TB128XU | on | | | | | | |
| Lenovo TB128XU | off | | - | - | | | - |
| Galaxy S24 FE | on | | | | | | |
| Galaxy S24 FE | off | | - | - | | | - |

## H3: evaluation on Story 10.20's held-out corner groups (AC9)

Story 10.20 (*Record 10.19-pipeline corner fixtures and check the corner model*) has not recorded its groups yet. Once
they exist in `Fixtures/corner/`:

1. Add the 10.20 held-out group names (`corner/<dir>`) to `tools/sticker-model/holdout.json` `heldout`, so no retrain
   can see them, and list them in a file, e.g. `tools/sticker-model/groups-10-20.json` (`["corner/…", …]`).
2. `uv run --project tools/sticker-model python tools/sticker-model/assemble.py` (rewrites `split.json`; commit it).
3. Add the groups to the Kotlin export (it reads `holdout.json`): `./gradlew :core:domain:exportKotlinStickerReads`.
4. `uv run --project tools/sticker-model python tools/sticker-model/evaluate.py --groups tools/sticker-model/groups-10-20.json --name evaluation-10-20`
   writes `docs/evidence/10-23/evaluation-10-20.{md,json}` (it refuses groups the carded model trained on).

| Date | Groups | Faces | Model confidently wrong | Gated Kotlin confidently wrong | Report | PO decision |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |
