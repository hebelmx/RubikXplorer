# Story 10.20 baseline: 10.22-pipeline corner groups (reported, not enforced; Story 10.21 enforces)

## Lenovo TB128XU × back × ceilback × torch=n/a

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| lenovo-tb128xu_stla_ceilback (heldout) / no saved calibration | 19/12/1 | 0/4 | 0/0 | 1/34 | U:grid(closeCall=2,disagree=0) R:grid(closeCall=1,disagree=0) F:grid(closeCall=2,disagree=0) D:grid(closeCall=5,disagree=0) L:grid(closeCall=2,disagree=2) B:grid(closeCall=4,disagree=1) |
| all / no saved calibration | 19/12/1 | 0/4 | 0/0 | 1/34 | |
| lenovo-tb128xu_stla_ceilback (heldout) / matching (frontal calibration on angled side faces) | 13/19/0 | 0/4 | 0/0 | 0/21 | U:grid(closeCall=2,disagree=2) R:grid(closeCall=1,disagree=1) F:grid(closeCall=2,disagree=3) D:grid(closeCall=5,disagree=2) L:grid(closeCall=4,disagree=4) B:grid(closeCall=4,disagree=2) |
| all / matching (frontal calibration on angled side faces) | 13/19/0 | 0/4 | 0/0 | 0/21 | |

Backlit light scores, lenovo-tb128xu_stla_ceilback: hold 1 uncalibrated 45.2; hold 2 uncalibrated 31.3; hold 1 calibrated 1.316 PASSES (backlit); hold 2 calibrated 1.421

## Lenovo TB128XU × front × bar-cool × torch=n/a

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| lenovo-tb128xu_stka_bar-cool (tuning) / no saved calibration | 30/2/0 | 2/4 | 0/18 | 0/29 | U:grid(closeCall=1,disagree=0) R:kept(closeCall=0,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=3,disagree=0) L:grid(closeCall=1,disagree=0) B:kept(closeCall=0,disagree=0) |
| lenovo-tb128xu_stla_bar-cool (heldout) / no saved calibration | 21/3/8 | 2/4 | 7/27 | 8/22 | U:grid(closeCall=0,disagree=0) R:grid(closeCall=2,disagree=0) F:kept(closeCall=0,disagree=0) D:kept(closeCall=0,disagree=0) L:grid(closeCall=1,disagree=0) B:kept(closeCall=0,disagree=0) |
| all / no saved calibration | 51/5/8 | 4/8 | 7/45 | 8/51 | |
| lenovo-tb128xu_stka_bar-cool (tuning) / matching (frontal calibration on angled side faces) | 30/2/0 | 2/4 | 0/18 | 0/28 | U:grid(closeCall=1,disagree=1) R:kept(closeCall=0,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=3,disagree=0) L:grid(closeCall=1,disagree=0) B:kept(closeCall=0,disagree=0) |
| lenovo-tb128xu_stla_bar-cool (heldout) / matching (frontal calibration on angled side faces) | 26/1/5 | 3/4 | 5/34 | 0/16 | U:kept(closeCall=0,disagree=0) R:kept(closeCall=0,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=1,disagree=0) L:kept(closeCall=0,disagree=0) B:kept(closeCall=0,disagree=0) |
| all / matching (frontal calibration on angled side faces) | 56/3/5 | 5/8 | 5/52 | 0/44 | |


## Lenovo TB128XU × front × bar-warm × torch=n/a

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| lenovo-tb128xu_stka_bar-warm (tuning) / no saved calibration | 30/2/0 | 2/4 | 0/18 | 0/29 | U:grid(closeCall=1,disagree=0) R:kept(closeCall=0,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=3,disagree=0) L:grid(closeCall=1,disagree=0) B:kept(closeCall=0,disagree=0) |
| lenovo-tb128xu_stla_bar-warm (heldout) / no saved calibration | 20/9/3 | 0/4 | 0/0 | 5/42 | U:grid(closeCall=0,disagree=1) R:grid(closeCall=3,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=0,disagree=1) L:grid(closeCall=2,disagree=1) B:grid(closeCall=2,disagree=0) |
| lenovo-tb128xu_stlb_bar-warm (tuning) / no saved calibration | 19/8/5 | 0/4 | 1/9 | 8/36 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=1,disagree=0) F:grid(closeCall=3,disagree=0) D:kept(closeCall=0,disagree=0) L:grid(closeCall=1,disagree=1) B:grid(closeCall=2,disagree=0) |
| all / no saved calibration | 69/19/8 | 2/12 | 1/27 | 13/107 | |
| lenovo-tb128xu_stka_bar-warm (tuning) / matching (frontal calibration on angled side faces) | 30/2/0 | 2/4 | 0/18 | 0/29 | U:grid(closeCall=1,disagree=0) R:kept(closeCall=0,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=3,disagree=0) L:grid(closeCall=1,disagree=0) B:kept(closeCall=0,disagree=0) |
| lenovo-tb128xu_stla_bar-warm (heldout) / matching (frontal calibration on angled side faces) | 17/14/1 | 0/4 | 0/0 | 2/35 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=0,disagree=4) F:grid(closeCall=0,disagree=2) D:grid(closeCall=2,disagree=0) L:grid(closeCall=3,disagree=2) B:grid(closeCall=0,disagree=4) |
| lenovo-tb128xu_stlb_bar-warm (tuning) / matching (frontal calibration on angled side faces) | 20/12/0 | 0/4 | 0/0 | 0/37 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=1,disagree=0) F:grid(closeCall=3,disagree=3) D:grid(closeCall=0,disagree=2) L:grid(closeCall=4,disagree=0) B:grid(closeCall=2,disagree=1) |
| all / matching (frontal calibration on angled side faces) | 67/28/1 | 2/12 | 0/18 | 2/101 | |


## SM-S721U1 × back × ceilback × torch=false

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| sm-s721u1_stla_ceilback (heldout) / no saved calibration | 17/15/0 | 0/4 | 0/0 | 0/35 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=1,disagree=1) F:grid(closeCall=2,disagree=3) D:grid(closeCall=3,disagree=0) L:grid(closeCall=7,disagree=0) B:grid(closeCall=1,disagree=0) |
| all / no saved calibration | 17/15/0 | 0/4 | 0/0 | 0/35 | |
| sm-s721u1_stla_ceilback (heldout) / matching (frontal calibration on angled side faces) | 7/25/0 | 0/4 | 0/0 | 0/22 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=1,disagree=3) F:grid(closeCall=2,disagree=7) D:grid(closeCall=3,disagree=0) L:grid(closeCall=7,disagree=2) B:grid(closeCall=1,disagree=5) |
| all / matching (frontal calibration on angled side faces) | 7/25/0 | 0/4 | 0/0 | 0/22 | |

Backlit light scores, sm-s721u1_stla_ceilback: hold 1 uncalibrated 30.3; hold 2 uncalibrated 18.7 PASSES (backlit); hold 1 calibrated 1.306 PASSES (backlit); hold 2 calibrated 1.075 PASSES (backlit)

## SM-S721U1 × front × bar-cool × torch=false

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| sm-s721u1_stka_bar-cool-3 (heldout) / no saved calibration | 23/5/4 | 0/4 | 0/0 | 4/45 | U:grid(closeCall=3,disagree=0) R:grid(closeCall=1,disagree=0) F:grid(closeCall=2,disagree=0) D:grid(closeCall=1,disagree=0) L:grid(closeCall=1,disagree=0) B:grid(closeCall=1,disagree=0) |
| sm-s721u1_stla_bar-cool-2 (tuning) / no saved calibration | 32/0/0 | 4/4 | 0/44 | 0/7 | U:kept(closeCall=0,disagree=0) R:kept(closeCall=0,disagree=0) F:kept(closeCall=0,disagree=0) D:grid(closeCall=2,disagree=0) L:kept(closeCall=0,disagree=0) B:kept(closeCall=0,disagree=0) |
| all / no saved calibration | 55/5/4 | 4/8 | 0/44 | 4/52 | |
| sm-s721u1_stka_bar-cool-3 (heldout) / matching (frontal calibration on angled side faces) | 13/19/0 | 0/4 | 0/0 | 0/28 | U:grid(closeCall=3,disagree=0) R:grid(closeCall=1,disagree=6) F:grid(closeCall=2,disagree=6) D:grid(closeCall=1,disagree=0) L:grid(closeCall=1,disagree=4) B:grid(closeCall=1,disagree=1) |
| sm-s721u1_stla_bar-cool-2 (tuning) / matching (frontal calibration on angled side faces) | 28/4/0 | 1/4 | 0/17 | 0/28 | U:kept(closeCall=0,disagree=0) R:kept(closeCall=0,disagree=0) F:grid(closeCall=0,disagree=1) D:grid(closeCall=2,disagree=1) L:grid(closeCall=0,disagree=2) B:grid(closeCall=0,disagree=2) |
| all / matching (frontal calibration on angled side faces) | 41/23/0 | 1/8 | 0/17 | 0/56 | |


## SM-S721U1 × front × bar-cool × torch=true

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| sm-s721u1_stka_bar-cool (tuning) / no saved calibration | 16/9/7 | 0/4 | 0/0 | 10/38 | U:grid(closeCall=3,disagree=0) R:grid(closeCall=2,disagree=1) F:grid(closeCall=2,disagree=1) D:grid(closeCall=2,disagree=1) L:grid(closeCall=0,disagree=1) B:grid(closeCall=2,disagree=0) |
| sm-s721u1_stla_bar-cool (heldout) / no saved calibration | 21/10/1 | 0/4 | 1/9 | 1/34 | U:kept(closeCall=0,disagree=0) R:grid(closeCall=1,disagree=1) F:grid(closeCall=3,disagree=0) D:grid(closeCall=1,disagree=0) L:grid(closeCall=1,disagree=0) B:grid(closeCall=3,disagree=1) |
| all / no saved calibration | 37/19/8 | 0/8 | 1/9 | 11/72 | |
| sm-s721u1_stka_bar-cool (tuning) / matching (frontal calibration on angled side faces) | 15/17/0 | 0/4 | 0/8 | 0/24 | U:kept(closeCall=0,disagree=0) R:grid(closeCall=3,disagree=6) F:grid(closeCall=1,disagree=2) D:grid(closeCall=1,disagree=1) L:grid(closeCall=0,disagree=2) B:grid(closeCall=1,disagree=4) |
| sm-s721u1_stla_bar-cool (heldout) / matching (frontal calibration on angled side faces) | 18/13/1 | 0/4 | 0/0 | 1/36 | U:grid(closeCall=0,disagree=1) R:grid(closeCall=1,disagree=4) F:grid(closeCall=3,disagree=1) D:grid(closeCall=1,disagree=0) L:grid(closeCall=1,disagree=1) B:grid(closeCall=3,disagree=2) |
| all / matching (frontal calibration on angled side faces) | 33/30/1 | 0/8 | 0/8 | 1/60 | |


## SM-S721U1 × front × bar-warm × torch=false

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| sm-s721u1_stka_bar-warm-2 (heldout) / no saved calibration | 15/16/1 | 0/4 | 0/0 | 1/28 | U:grid(closeCall=1,disagree=3) R:grid(closeCall=4,disagree=1) F:grid(closeCall=3,disagree=1) D:grid(closeCall=1,disagree=4) L:grid(closeCall=1,disagree=1) B:grid(closeCall=5,disagree=0) |
| sm-s721u1_stla_bar-warm-2 (tuning) / no saved calibration | 21/8/3 | 1/4 | 1/9 | 3/34 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=3,disagree=0) F:kept(closeCall=0,disagree=0) D:grid(closeCall=2,disagree=0) L:grid(closeCall=4,disagree=0) B:grid(closeCall=1,disagree=0) |
| all / no saved calibration | 36/24/4 | 1/8 | 1/9 | 4/62 | |
| sm-s721u1_stka_bar-warm-2 (heldout) / matching (frontal calibration on angled side faces) | 18/14/0 | 0/4 | 0/17 | 0/20 | U:kept(closeCall=0,disagree=0) R:grid(closeCall=3,disagree=4) F:grid(closeCall=0,disagree=1) D:kept(closeCall=0,disagree=0) L:grid(closeCall=0,disagree=1) B:grid(closeCall=4,disagree=3) |
| sm-s721u1_stla_bar-warm-2 (tuning) / matching (frontal calibration on angled side faces) | 9/23/0 | 0/4 | 0/0 | 1/26 | U:grid(closeCall=1,disagree=0) R:grid(closeCall=3,disagree=1) F:grid(closeCall=0,disagree=6) D:grid(closeCall=2,disagree=0) L:grid(closeCall=4,disagree=5) B:grid(closeCall=1,disagree=5) |
| all / matching (frontal calibration on angled side faces) | 27/37/0 | 0/8 | 0/17 | 1/46 | |


## SM-S721U1 × front × bar-warm × torch=true

| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |
|---|---|---|---|---|---|
| sm-s721u1_stka_bar-warm (tuning) / no saved calibration | 25/7/0 | 0/4 | 0/0 | 0/43 | U:grid(closeCall=0,disagree=1) R:grid(closeCall=1,disagree=0) F:grid(closeCall=2,disagree=0) D:grid(closeCall=2,disagree=1) L:grid(closeCall=1,disagree=0) B:grid(closeCall=3,disagree=0) |
| sm-s721u1_stla_bar-warm (heldout) / no saved calibration | 18/5/9 | 1/4 | 6/18 | 8/27 | U:grid(closeCall=2,disagree=0) R:kept(closeCall=0,disagree=0) F:grid(closeCall=3,disagree=0) D:kept(closeCall=0,disagree=0) L:grid(closeCall=2,disagree=0) B:grid(closeCall=2,disagree=0) |
| all / no saved calibration | 43/12/9 | 1/8 | 6/18 | 8/70 | |
| sm-s721u1_stka_bar-warm (tuning) / matching (frontal calibration on angled side faces) | 7/25/0 | 0/4 | 0/9 | 0/15 | U:kept(closeCall=0,disagree=0) R:grid(closeCall=1,disagree=3) F:grid(closeCall=2,disagree=5) D:grid(closeCall=2,disagree=0) L:grid(closeCall=1,disagree=8) B:grid(closeCall=3,disagree=5) |
| sm-s721u1_stla_bar-warm (heldout) / matching (frontal calibration on angled side faces) | 13/16/3 | 0/4 | 0/0 | 5/30 | U:grid(closeCall=2,disagree=1) R:grid(closeCall=0,disagree=5) F:grid(closeCall=3,disagree=2) D:grid(closeCall=0,disagree=2) L:grid(closeCall=2,disagree=1) B:grid(closeCall=2,disagree=4) |
| all / matching (frontal calibration on angled side faces) | 20/41/3 | 0/8 | 0/9 | 5/45 | |



Story 10.19 Quick Scan side-face replay (QuickScanClassification; side cells correct/uncertain/wrong)

Light check (SideLight) score per hold; uncalibrated: darker side face's 75th-percentile L* below the top face, cut 27.0 L*; calibrated: top's relative brightness over the mean of the two sides' (against the group's recorder calibration), cut 1.34
| group | direction | scored | uncalibrated hold 1 | uncalibrated hold 2 | calibrated hold 1 | calibrated hold 2 |
|---|---|---|---|---|---|---|
| lenovo-tb128xu_stka_cool | back | false | 32.4 FAIL | 30.2 FAIL | 1.392 FAIL | 1.337 pass |
| lenovo-tb128xu_stka_cool-front | front | false | 23.0 pass | 22.7 pass | 1.109 pass | 0.994 pass |
| lenovo-tb128xu_stka_warm | front | false | 23.3 pass | 25.0 pass | 1.177 pass | 1.165 pass |
| lenovo-tb128xu_stkb_cool | back | false | 29.1 FAIL | 28.7 FAIL | 1.425 FAIL | 1.352 FAIL |
| lenovo-tb128xu_stla_cool | back | false | 17.2 pass | 36.3 FAIL | 1.431 FAIL | 1.394 FAIL |
| lenovo-tb128xu_stla_cool-front | front | false | 21.6 pass | 20.9 pass | 1.232 pass | 1.286 pass |
| lenovo-tb128xu_stla_warm | front | false | 13.8 pass | 24.6 pass | 0.991 pass | 1.161 pass |
| lenovo-tb128xu_stlb_cool | back | false | 32.8 FAIL | 28.6 FAIL | 1.553 FAIL | 1.349 FAIL |

| group / scenario | side correct/uncertain/wrong | side faces kept | wrong on kept faces | R<->O on kept | per color c/u/w |
|---|---|---|---|---|---|
| lenovo-tb128xu_stka_cool / no saved calibration | 16/15/1 of 32 | 0/4 | 0 | 0 | White 1/4/1 **Yellow** 3/4/0 Green 5/0/0 Blue 4/0/0 Red 0/5/0 **Orange** 3/2/0 |
| lenovo-tb128xu_stka_cool / matching (frontal calibration on angled side faces) | 17/15/0 of 32 | 0/4 | 0 | 0 | White 5/1/0 **Yellow** 3/4/0 Green 5/0/0 Blue 1/3/0 Red 0/5/0 **Orange** 3/2/0 |
| lenovo-tb128xu_stka_cool / other light, same cube | 31/33/0 of 64 | 0/8 | 0 | 0 | White 9/3/0 **Yellow** 5/9/0 Green 10/0/0 Blue 4/4/0 Red 0/10/0 **Orange** 3/7/0 |
| lenovo-tb128xu_stka_cool / other cube | 58/101/1 of 160 | 0/20 | 0 | 0 | White 22/7/1 **Yellow** 12/23/0 Green 6/19/0 Blue 6/14/0 Red 0/25/0 **Orange** 12/13/0 |
| lenovo-tb128xu_stka_cool-front / no saved calibration | 23/8/1 of 32 | 0/4 | 0 | 0 | White 5/0/0 **Yellow** 6/0/0 Green 5/0/0 Blue 5/0/0 Red 0/5/0 **Orange** 2/3/1 |
| lenovo-tb128xu_stka_cool-front / matching (frontal calibration on angled side faces) | 18/14/0 of 32 | 0/4 | 0 | 0 | White 5/0/0 **Yellow** 2/4/0 Green 4/1/0 Blue 5/0/0 Red 0/5/0 **Orange** 2/4/0 |
| lenovo-tb128xu_stka_cool-front / other light, same cube | 30/34/0 of 64 | 0/8 | 0 | 0 | White 10/0/0 **Yellow** 1/11/0 Green 9/1/0 Blue 8/2/0 Red 0/10/0 **Orange** 2/10/0 |
| lenovo-tb128xu_stka_cool-front / other cube | 58/101/1 of 160 | 0/20 | 0 | 0 | White 22/3/0 **Yellow** 7/23/0 Green 3/22/0 Blue 18/7/0 Red 0/25/0 **Orange** 8/21/1 |
| lenovo-tb128xu_stka_warm / no saved calibration | 20/12/0 of 32 | 0/4 | 0 | 0 | White 4/1/0 **Yellow** 4/3/0 Green 5/0/0 Blue 1/4/0 Red 2/4/0 **Orange** 4/0/0 |
| lenovo-tb128xu_stka_warm / matching (frontal calibration on angled side faces) | 21/11/0 of 32 | 0/4 | 0 | 0 | White 4/1/0 **Yellow** 4/3/0 Green 5/0/0 Blue 3/2/0 Red 1/5/0 **Orange** 4/0/0 |
| lenovo-tb128xu_stka_warm / other light, same cube | 26/38/0 of 64 | 0/8 | 0 | 0 | White 8/2/0 **Yellow** 8/6/0 Green 2/8/0 Blue 2/8/0 Red 0/12/0 **Orange** 6/2/0 |
| lenovo-tb128xu_stka_warm / other cube | 72/88/0 of 160 | 0/20 | 0 | 0 | White 19/6/0 **Yellow** 20/15/0 Green 1/24/0 Blue 9/16/0 Red 3/27/0 **Orange** 20/0/0 |
| lenovo-tb128xu_stkb_cool / no saved calibration | 21/10/1 of 32 | 0/4 | 0 | 0 | White 6/0/0 **Yellow** 2/3/0 Green 5/0/0 Blue 3/3/0 Red 1/3/1 **Orange** 4/1/0 |
| lenovo-tb128xu_stkb_cool / matching (frontal calibration on angled side faces) | 17/14/1 of 32 | 0/4 | 0 | 0 | White 6/0/0 **Yellow** 1/4/0 Green 2/3/0 Blue 3/3/0 Red 0/4/1 **Orange** 5/0/0 |
| lenovo-tb128xu_stkb_cool / other light, same cube | n/a (no such calibration recorded) | | | | |
| lenovo-tb128xu_stkb_cool / other cube | 103/114/7 of 224 | 0/28 | 0 | 0 | White 42/0/0 **Yellow** 0/35/0 Green 29/6/0 Blue 10/32/0 Red 1/27/7 **Orange** 21/14/0 |
| lenovo-tb128xu_stla_cool / no saved calibration | 13/17/2 of 32 | 0/4 | 0 | 0 | White 2/3/2 **Yellow** 3/3/0 Green 5/0/0 Blue 3/0/0 Red 0/5/0 **Orange** 0/6/0 |
| lenovo-tb128xu_stla_cool / matching (frontal calibration on angled side faces) | 9/23/0 of 32 | 0/4 | 0 | 0 | White 2/5/0 **Yellow** 3/3/0 Green 4/1/0 Blue 0/3/0 Red 0/5/0 **Orange** 0/6/0 |
| lenovo-tb128xu_stla_cool / other light, same cube | 21/43/0 of 64 | 0/8 | 0 | 0 | White 4/10/0 **Yellow** 4/8/0 Green 10/0/0 Blue 3/3/0 Red 0/10/0 **Orange** 0/12/0 |
| lenovo-tb128xu_stla_cool / other cube | 56/100/4 of 160 | 0/20 | 0 | 0 | White 10/21/4 **Yellow** 15/15/0 Green 25/0/0 Blue 6/9/0 Red 0/25/0 **Orange** 0/30/0 |
| lenovo-tb128xu_stla_cool-front / no saved calibration | 23/9/0 of 32 | 1/4 | 0 | 0 | White 5/0/0 **Yellow** 4/0/0 Green 5/0/0 Blue 3/3/0 Red 3/3/0 **Orange** 3/3/0 |
| lenovo-tb128xu_stla_cool-front / matching (frontal calibration on angled side faces) | 27/5/0 of 32 | 2/4 | 0 | 0 | White 5/0/0 **Yellow** 4/0/0 Green 5/0/0 Blue 5/1/0 Red 4/2/0 **Orange** 4/2/0 |
| lenovo-tb128xu_stla_cool-front / other light, same cube | 46/18/0 of 64 | 2/8 | 0 | 0 | White 10/0/0 **Yellow** 8/0/0 Green 10/0/0 Blue 8/4/0 Red 6/6/0 **Orange** 4/8/0 |
| lenovo-tb128xu_stla_cool-front / other cube | 114/46/0 of 160 | 5/20 | 0 | 0 | White 23/2/0 **Yellow** 20/0/0 Green 25/0/0 Blue 18/12/0 Red 12/18/0 **Orange** 16/14/0 |
| lenovo-tb128xu_stla_warm / no saved calibration | 16/11/5 of 32 | 0/4 | 0 | 0 | White 3/1/1 **Yellow** 3/2/1 Green 1/2/2 Blue 0/5/0 Red 5/1/0 **Orange** 4/0/1 |
| lenovo-tb128xu_stla_warm / matching (frontal calibration on angled side faces) | 14/14/4 of 32 | 0/4 | 0 | 0 | White 3/1/1 **Yellow** 3/2/1 Green 0/3/2 Blue 1/4/0 Red 3/3/0 **Orange** 4/1/0 |
| lenovo-tb128xu_stla_warm / other light, same cube | 21/38/5 of 64 | 0/8 | 0 | 0 | White 6/2/2 **Yellow** 6/6/0 Green 0/8/2 Blue 0/9/1 Red 7/5/0 **Orange** 2/8/0 |
| lenovo-tb128xu_stla_warm / other cube | 66/76/18 of 160 | 0/20 | 0 | 0 | White 14/6/5 **Yellow** 15/12/3 Green 3/14/8 Blue 2/21/2 Red 17/13/0 **Orange** 15/10/0 |
| lenovo-tb128xu_stlb_cool / no saved calibration | 18/14/0 of 32 | 0/4 | 0 | 0 | White 2/3/0 **Yellow** 0/5/0 Green 4/0/0 Blue 2/2/0 Red 2/4/0 **Orange** 8/0/0 |
| lenovo-tb128xu_stlb_cool / matching (frontal calibration on angled side faces) | 16/16/0 of 32 | 0/4 | 0 | 0 | White 2/3/0 **Yellow** 0/5/0 Green 3/1/0 Blue 2/2/0 Red 1/5/0 **Orange** 8/0/0 |
| lenovo-tb128xu_stlb_cool / other light, same cube | n/a (no such calibration recorded) | | | | |
| lenovo-tb128xu_stlb_cool / other cube | 88/133/3 of 224 | 0/28 | 0 | 0 | White 11/24/0 **Yellow** 0/32/3 Green 26/2/0 Blue 8/20/0 Red 7/35/0 **Orange** 36/20/0 |

Scored (10.19-pipeline) groups: 0 — no group is scored yet; the bar applies once the device session records them.
Required but missing scored groups:
- Lenovo TB128XU: a scored front-lit stickered cube group
- Lenovo TB128XU: a scored front-lit stickerless cube group
- Lenovo TB128XU: at least 2 front lights (has 0)
- Lenovo TB128XU: a scored backlit group
- Galaxy S24 FE: a scored front-lit stickered cube group
- Galaxy S24 FE: a scored front-lit stickerless cube group
- Galaxy S24 FE: at least 2 front lights (has 0)
- Galaxy S24 FE: a scored backlit group
Bar violations:
- none
