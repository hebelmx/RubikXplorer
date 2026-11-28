# Story 15.4 evidence: method-solve cold first solves with the warm-up on

Measured 2026-11-27 20:35–20:56 CST for Story 15.4 (AC: Lenovo benchmark and debug, Galaxy benchmark), with the
same harness and cold protocol as the "before" rows in
[`../scp-2026-11-27-solver-deadline/`](../scp-2026-11-27-solver-deadline/README.md).

## Revision and builds

- Revision `120af5a8` (branch `chore/15-4-warm-the-method-solver-once-and-give-debug-builds-a-5-s-solv`, Story 15.4
  implementation `2651028a`). `MethodSolveBenchmarkActivity` runs the production `MethodSolveWarmUp` (TWO_LOOK on a
  fixed 25-move scramble) once per process after `prepared` and before the first timed solve, and logs
  `warmed-up,nanos=`. No `--ez warmUp false` was passed.
- `./gradlew --no-daemon :app:assembleBenchmark :app:assembleDebug`; installed with `adb install -r` (benchmark
  dexopt `verify` on the Lenovo, no AOT or profile).
- Afterwards the debug APK was installed on both tablets (`install -r`, data kept) and the app force-stopped.

## Protocol

Per sample: `am force-stop`, `logcat -c`, `am start -W ... MethodSolveBenchmarkActivity --es kind <K> --es mode cold
--ei index <i> --ei count 3`, wait for `done`, keep the `MethodSolveBenchmark` lines. Benchmark: 25 processes per
method, `index` 0, 10, …, 240, kinds interleaved (BEGINNER, TWO_LOOK, FULL_OLL_PLL per index). Debug: 20 per method,
`index` 0 … 190. Times are from the `summary` lines (every run logged one; no sample missing, every solve `Solved`,
no `warm-up-note` line). The "1st solve" is the first timed solve, the one a Learner's first tap would be.

## Devices and conditions

- Lenovo TB128XU, USB serial `HA1S28MM` only. USB powered, 100 %, battery 25.3 → 27.2 °C, thermal status 0,
  power save off. Load average 1.22 → 3.27 (benchmark set, 20:35–20:38), 0.49 → 1.99 (debug set, 20:45–20:52).
- Galaxy Tab S7 FE `SM-T738U`, wireless adb `192.168.0.12:46201`. **On battery** (89 → 88 %), **power save on**
  (left as found), 21.8 → 21.7 °C, thermal status 0, about 0.6 GB available memory, load average 4.1–8.8
  throughout (20:35–20:55); each run took about 15 s over Wi-Fi. A moderately busy tablet, between the "before"
  quiet set 2 (3.7–6.8) and busy set 1 (8–14).
- The Lenovo debug set ran while the Galaxy benchmark set was still running (separate devices).
- Snapshots before and after each set, plus `/proc/loadavg` every 5th index: `*-conditions.txt`.

## Results (ms; p95 nearest-rank)

| Device, build | Method | Sample | n | Median | p95 | Max | > 3 s | > 5 s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Lenovo TB128XU, benchmark | BEGINNER | 1st solve (JIT-cold, after warm-up) | 25 | 109 | 197 | 229 | 0 | 0 |
| Lenovo TB128XU, benchmark | BEGINNER | 2nd solve | 25 | 61 | 153 | 171 | 0 | 0 |
| Lenovo TB128XU, benchmark | BEGINNER | 3rd solve | 25 | 66 | 181 | 268 | 0 | 0 |
| Lenovo TB128XU, benchmark | BEGINNER | warm-up itself (untimed in the app) | 25 | 1075 | 1087 | 1098 | 0 | 0 |
| Lenovo TB128XU, benchmark | TWO_LOOK | 1st solve (JIT-cold, after warm-up) | 25 | 42 | 140 | 144 | 0 | 0 |
| Lenovo TB128XU, benchmark | TWO_LOOK | 2nd solve | 25 | 34 | 124 | 127 | 0 | 0 |
| Lenovo TB128XU, benchmark | TWO_LOOK | 3rd solve | 25 | 30 | 136 | 236 | 0 | 0 |
| Lenovo TB128XU, benchmark | TWO_LOOK | warm-up itself (untimed in the app) | 25 | 1076 | 1093 | 1094 | 0 | 0 |
| Lenovo TB128XU, benchmark | FULL_OLL_PLL | 1st solve (JIT-cold, after warm-up) | 25 | 79 | 177 | 181 | 0 | 0 |
| Lenovo TB128XU, benchmark | FULL_OLL_PLL | 2nd solve | 25 | 69 | 158 | 163 | 0 | 0 |
| Lenovo TB128XU, benchmark | FULL_OLL_PLL | 3rd solve | 25 | 63 | 170 | 265 | 0 | 0 |
| Lenovo TB128XU, benchmark | FULL_OLL_PLL | warm-up itself (untimed in the app) | 25 | 1074 | 1099 | 1102 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | BEGINNER | 1st solve (JIT-cold, after warm-up) | 25 | 417 | 828 | 1001 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | BEGINNER | 2nd solve | 25 | 171 | 600 | 680 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | BEGINNER | 3rd solve | 25 | 294 | 815 | 1041 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | BEGINNER | warm-up itself (untimed in the app) | 25 | 3086 | 3164 | 3223 | 16 | 0 |
| Galaxy Tab S7 FE, benchmark | TWO_LOOK | 1st solve (JIT-cold, after warm-up) | 25 | 218 | 630 | 653 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | TWO_LOOK | 2nd solve | 25 | 88 | 461 | 468 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | TWO_LOOK | 3rd solve | 25 | 100 | 605 | 1021 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | TWO_LOOK | warm-up itself (untimed in the app) | 25 | 3089 | 3177 | 3182 | 14 | 0 |
| Galaxy Tab S7 FE, benchmark | FULL_OLL_PLL | 1st solve (JIT-cold, after warm-up) | 25 | 318 | 721 | 729 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | FULL_OLL_PLL | 2nd solve | 25 | 302 | 530 | 709 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | FULL_OLL_PLL | 3rd solve | 25 | 293 | 719 | 1015 | 0 | 0 |
| Galaxy Tab S7 FE, benchmark | FULL_OLL_PLL | warm-up itself (untimed in the app) | 25 | 3092 | 3218 | 3229 | 19 | 0 |
| Lenovo TB128XU, **debug** | BEGINNER | 1st solve (JIT-cold, after warm-up) | 20 | 775 | 1071 | 1097 | 0 | 0 |
| Lenovo TB128XU, **debug** | BEGINNER | 2nd solve | 20 | 342 | 591 | 712 | 0 | 0 |
| Lenovo TB128XU, **debug** | BEGINNER | 3rd solve | 20 | 313 | 656 | 990 | 0 | 0 |
| Lenovo TB128XU, **debug** | BEGINNER | warm-up itself (untimed in the app) | 20 | 3560 | 3591 | 3599 | 20 | 0 |
| Lenovo TB128XU, **debug** | TWO_LOOK | 1st solve (JIT-cold, after warm-up) | 20 | 281 | 633 | 689 | 0 | 0 |
| Lenovo TB128XU, **debug** | TWO_LOOK | 2nd solve | 20 | 219 | 387 | 433 | 0 | 0 |
| Lenovo TB128XU, **debug** | TWO_LOOK | 3rd solve | 20 | 189 | 463 | 826 | 0 | 0 |
| Lenovo TB128XU, **debug** | TWO_LOOK | warm-up itself (untimed in the app) | 20 | 3559 | 3594 | 3599 | 20 | 0 |
| Lenovo TB128XU, **debug** | FULL_OLL_PLL | 1st solve (JIT-cold, after warm-up) | 20 | 488 | 852 | 908 | 0 | 0 |
| Lenovo TB128XU, **debug** | FULL_OLL_PLL | 2nd solve | 20 | 442 | 594 | 632 | 0 | 0 |
| Lenovo TB128XU, **debug** | FULL_OLL_PLL | 3rd solve | 20 | 354 | 645 | 948 | 0 | 0 |
| Lenovo TB128XU, **debug** | FULL_OLL_PLL | warm-up itself (untimed in the app) | 20 | 3561 | 3600 | 3600 | 20 | 0 |

Warm-up (`warmed-up,nanos=`, all methods), median / max ms: Lenovo benchmark 1074 / 1102, Lenovo debug
3560 / 3600, Galaxy benchmark 3089 / 3229. Preparation (`prepared,nanos=`) median: Lenovo benchmark
241, Lenovo debug 1216, Galaxy benchmark 496.

## Files

- `lenovo-bench-raw.log`, `galaxy-bench-raw.log`, `lenovo-debug-raw.log`: the harness's logcat lines per run.
- `lenovo-bench-conditions.txt`, `galaxy-bench-conditions.txt`, `lenovo-debug-conditions.txt`.
- `samples.csv`: every timed sample (`set,kind,mode,run,index,ordinal,millis,warmup_millis,prepared_millis`).


## Solution Playback cold-start taps (AC 5), 2026-11-27

Lenovo TB128XU (HA1S28MM), debug build from this branch, driven over adb by the orchestrator (the PO delegated the taps). Each trial: `am force-stop`, launch, Solve → Enter colors, enter a random 22-move scramble, Accept, and tap the method style as soon as Solution Playback appears (right after "Preparing the solver." ends, so the warm-up is still running: the worst case).

| Cold start | Scramble (seeded) | Style tapped | Result |
| --- | --- | --- | --- |
| 1 | L F B L F2 B' R' D F L2 F B2 U B' F' B' L' U B U R2 B' | Beginner | method solution shown, 104 moves |
| 2 | R' L B' R' D' B F2 B' U L B' D' U F2 D L U2 F' D R U' L | 2-look | method solution shown, 95 moves |
| 3 | R2 B D2 U' D F' R2 D2 R U' B' F U2 R' L2 D F R' L2 B2 D' F | Full OLL + PLL | method solution shown, 66 moves |
| 4 | B' U2 B2 U2 F' D U' B2 R2 F' L U2 F' L2 U2 R F2 D F2 R F' U' | Beginner | method solution shown, 65 moves |
| 5 | B2 R B' L B2 L' U2 R2 B2 D L2 D L2 R2 D2 L D U D U' D2 R | 2-look | method solution shown, 88 moves |

"Couldn't build that solution in time" did not appear in any of the 5.
