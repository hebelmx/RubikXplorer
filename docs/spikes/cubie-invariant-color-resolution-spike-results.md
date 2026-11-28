# Phase 1 Spike Report: CubieInvariantResolver Replay

Groups loaded: total=23 (TUNING=7, HELDOUT=8, LEGACY=8)

## Tuning Split: L_WEIGHT Comparison (1.0 vs 0.5)

### TUNING (lWeight = 1.0)
| Scenario | Runs | Full No-Fallback (before -> after) | Side Faces Kept (before -> after) | Rescued Runs (stickers) | Wrong Resolved Stickers | Reasons |
|---|---|---|---|---|---|---|
| no saved calibration | 6 | 0 -> 4 | 9/24 -> 17/24 | 4 (25) | 0 | RESOLVED=4, TOO_MANY_UNCERTAIN=1, CONFIDENT_INVARIANT_VIOLATION=1 |
| matching | 6 | 0 -> 2 | 5/24 -> 9/24 | 2 (13) | 0 | CONFIDENT_INVARIANT_VIOLATION=4, RESOLVED=2 |
| other light, same cube | 18 | 0 -> 5 | 15/72 -> 21/72 | 5 (31) | 0 | CONFIDENT_INVARIANT_VIOLATION=13, RESOLVED=5 |
| other cube | 36 | 0 -> 1 | 8/144 -> 11/144 | 1 (12) | 0 | CONFIDENT_INVARIANT_VIOLATION=35, RESOLVED=1 |

Rescued:
- lenovo-tb128xu_stka_bar-cool [no saved calibration]: rescued 6 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-warm [no saved calibration]: rescued 6 stickers, saved 4 grid faces
- sm-s721u1_stka_bar-warm [no saved calibration]: rescued 11 stickers, saved 6 grid faces
- sm-s721u1_stla_bar-cool-2 [no saved calibration]: rescued 2 stickers, saved 1 grid faces
- lenovo-tb128xu_stka_bar-cool [matching]: rescued 7 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-warm [matching]: rescued 6 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-cool [other light, same cube]: rescued 9 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-cool [other light, same cube]: rescued 9 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-cool [other light, same cube]: rescued 8 stickers, saved 3 grid faces
- sm-s721u1_stla_bar-cool-2 [other light, same cube]: rescued 2 stickers, saved 1 grid faces
- sm-s721u1_stla_bar-cool-2 [other light, same cube]: rescued 3 stickers, saved 2 grid faces
- lenovo-tb128xu_stka_bar-cool [other cube]: rescued 12 stickers, saved 5 grid faces

### TUNING (lWeight = 0.5)
| Scenario | Runs | Full No-Fallback (before -> after) | Side Faces Kept (before -> after) | Rescued Runs (stickers) | Wrong Resolved Stickers | Reasons |
|---|---|---|---|---|---|---|
| no saved calibration | 6 | 0 -> 4 | 9/24 -> 17/24 | 4 (25) | 0 | RESOLVED=4, TOO_MANY_UNCERTAIN=1, CONFIDENT_INVARIANT_VIOLATION=1 |
| matching | 6 | 0 -> 2 | 5/24 -> 9/24 | 2 (13) | 0 | CONFIDENT_INVARIANT_VIOLATION=4, RESOLVED=2 |
| other light, same cube | 18 | 0 -> 5 | 15/72 -> 21/72 | 5 (31) | 0 | CONFIDENT_INVARIANT_VIOLATION=13, RESOLVED=5 |
| other cube | 36 | 0 -> 1 | 8/144 -> 11/144 | 1 (12) | 0 | CONFIDENT_INVARIANT_VIOLATION=35, RESOLVED=1 |

Rescued:
- lenovo-tb128xu_stka_bar-cool [no saved calibration]: rescued 6 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-warm [no saved calibration]: rescued 6 stickers, saved 4 grid faces
- sm-s721u1_stka_bar-warm [no saved calibration]: rescued 11 stickers, saved 6 grid faces
- sm-s721u1_stla_bar-cool-2 [no saved calibration]: rescued 2 stickers, saved 1 grid faces
- lenovo-tb128xu_stka_bar-cool [matching]: rescued 7 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-warm [matching]: rescued 6 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-cool [other light, same cube]: rescued 9 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-cool [other light, same cube]: rescued 9 stickers, saved 4 grid faces
- lenovo-tb128xu_stka_bar-cool [other light, same cube]: rescued 8 stickers, saved 3 grid faces
- sm-s721u1_stla_bar-cool-2 [other light, same cube]: rescued 2 stickers, saved 1 grid faces
- sm-s721u1_stla_bar-cool-2 [other light, same cube]: rescued 3 stickers, saved 2 grid faces
- lenovo-tb128xu_stka_bar-cool [other cube]: rescued 12 stickers, saved 5 grid faces

## Legacy Split (lWeight = 1.0)
| Scenario | Runs | Full No-Fallback (before -> after) | Side Faces Kept (before -> after) | Rescued Runs (stickers) | Wrong Resolved Stickers | Reasons |
|---|---|---|---|---|---|---|
| no saved calibration | 8 | 0 -> 1 | 1/32 -> 4/32 | 1 (10) | 0 | TOO_MANY_UNCERTAIN=6, CONFIDENT_INVARIANT_VIOLATION=1, RESOLVED=1 |
| matching | 8 | 0 -> 0 | 2/32 -> 2/32 | 0 (0) | 0 | CONFIDENT_INVARIANT_VIOLATION=6, TOO_MANY_UNCERTAIN=1, PLAUSIBILITY_REJECTED=1 |
| other light, same cube | 28 | 0 -> 1 | 5/112 -> 8/112 | 1 (10) | 0 | CONFIDENT_INVARIANT_VIOLATION=25, TOO_MANY_UNCERTAIN=1, RESOLVED=1, PLAUSIBILITY_REJECTED=1 |
| other cube | 76 | 0 -> 1 | 9/304 -> 11/304 | 1 (9) | 0 | CONFIDENT_INVARIANT_VIOLATION=67, TOO_MANY_UNCERTAIN=4, PLAUSIBILITY_REJECTED=3, RESOLVED=1, AMBIGUOUS=1 |

Rescued:
- lenovo-tb128xu_stla_cool-front [no saved calibration]: rescued 10 stickers, saved 4 grid faces
- lenovo-tb128xu_stla_cool-front [other light, same cube]: rescued 10 stickers, saved 4 grid faces
- lenovo-tb128xu_stla_cool-front [other cube]: rescued 9 stickers, saved 3 grid faces

## Held-Out Split — Untouched Evaluation Gate (lWeight = 1.0)
| Scenario | Runs | Full No-Fallback (before -> after) | Side Faces Kept (before -> after) | Rescued Runs (stickers) | Wrong Resolved Stickers | Reasons |
|---|---|---|---|---|---|---|
| no saved calibration | 8 | 0 -> 0 | 3/32 -> 3/32 | 0 (0) | 0 | CONFIDENT_INVARIANT_VIOLATION=4, TOO_MANY_UNCERTAIN=3, UNCALIBRATED=1 |
| matching | 8 | 0 -> 0 | 3/32 -> 3/32 | 0 (0) | 0 | CONFIDENT_INVARIANT_VIOLATION=8 |
| other light, same cube | 29 | 0 -> 0 | 6/116 -> 6/116 | 0 (0) | 0 | CONFIDENT_INVARIANT_VIOLATION=25, TOO_MANY_UNCERTAIN=4 |
| other cube | 46 | 0 -> 0 | 17/184 -> 17/184 | 0 (0) | 0 | CONFIDENT_INVARIANT_VIOLATION=44, TOO_MANY_UNCERTAIN=2 |
