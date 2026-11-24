# 10.21 device session notes (working file, 2026-11-24)

Build: bbd47aa1 (fix few-confident wait) + f21dd966 (debug diagnostics). No saved calibration on either device.

## Pre-fix (39ee8b3a), scan #1
- S24 FE hold 1 (stka, torch lit): few-confident amount=0 for 24 s, manual capture, centre=- on all faces -> bug, fixed in bbd47aa1.

## Post-fix scans (local 09:19-09:24)
| # | device | cube | hold1 wait (sinceDetectedMs) | hold2 wait | side faces kept at hand-over (of 4) | fallback faces | corrections | wrong kept |
|---|---|---|---|---|---|---|---|---|
| 1 | S24 FE (torch lit) | stka | 3708 auto | 8083 auto | 0 | 6 | 1 (blank fill) | 0 |
| 1 | Lenovo | stka | 10708 auto | 8578 auto | 0 | 6 | 0 | 0 |
| 2 | S24 FE (torch lit) | stla | 8673 auto | 7692 auto | 1 (F) | 5 | 1 (blank fill) | 0 |
| 2 | Lenovo | stla | 7487 auto | 7982 auto | 0 | 6 | 0 | 0 |

Observations:
- Fix confirmed: every hold auto-captures; 1 of 8 holds over 10 s (Lenovo stka hold 1, 10.7 s).
- Hold-1 capture lines show faces "kept" 9/9 against a partial reference (refColors W,G,R only); the hand-over re-reads with the full six-colour self-calibration and drops them (e.g. Lenovo stla: U,F,R kept at hold 1 -> kept=- at hand-over). Capture-time 'kept' is provisional; hand-over is authoritative.
- With the full reference, hold-2 faces read 1-5 of 9 confident; most faces go to the grid as 'uncertain' (close calls), some 'centre-uncertain'.
- Logo centres: Rubik's logo read as plain White on S24 but guarded on Lenovo; GAN logo guarded on S24, plain on Lenovo.
- light=fail at capture on several Lenovo holds (lightScore 28-51) and S24 stla hold 2 (35.1); light setup to be confirmed.
- Guided fallback's first face (F) logs ref=none colours=--------- on every run.

## Calibrated scans (local 09:28-09:38), cube stka, both devices calibrated just before
| device | calibration | sides kept at hand-over | wrong kept | face-by-face misreads |
|---|---|---|---|---|
| S24 FE (torch lit) | match on hold 1 (F kept with 1 blank), mismatch centre on hold 2 (lightScore 58) -> scan-wide mismatch-centre | B | 0 | 0 (1 blank filled) |
| Lenovo | mismatch centre on every hold, right after saving | R, D, B (hold 2 manual after 41.6 s, L missing) | 0 | 3 (U1 Y->W, U8 O->W, F2 W->B; U and F from face-by-face), fixed in review |

Note: provisional per-hold "stickers"/"faceRead" lines read against a partial reference (e.g. refColors=WGR) are not what is kept; the hand-over re-read is authoritative. Judge kept stickers from the accepted state only.

## Summary across the session (post-fix, 6 scans per device-pair)
- Safety: 0 confidently wrong stickers on any face Quick Scan kept.
- Coverage: side faces kept at hand-over: 0, 0, 1, 0 (uncalibrated) and 1, 3 (calibrated). Nearly every scan falls back to face-by-face for most faces.
- Calibration match: fails the centre check on both devices even right after calibrating (Lenovo every hold; S24 on hold 2), so the 1-blank rule ran on a single face in the whole session.
- Waits: after bbd47aa1 every hold auto-captures; holds over 10 s: Lenovo stka uncalibrated hold 1 (10.7 s), Lenovo calibrated hold 1 (12.9 s), S24 calibrated hold 1 (17.8 s), Lenovo calibrated hold 2 (41.6 s, manual).
- Light check: light=fail on most front-lit holds (Lenovo 28-51, S24 up to 58), i.e. front-lit false blocks are common.

## PO conclusion (AC8, 2026-11-24)
PO: "with these quick tests it is easy to tell that the Quick Scan feature doesn't work, and will not work with the pipeline we have implemented." BETA call: QUICK_SCAN stays OFF. Follow-up: rethink the Quick Scan recognition pipeline (beyond threshold tuning); not filed yet.
