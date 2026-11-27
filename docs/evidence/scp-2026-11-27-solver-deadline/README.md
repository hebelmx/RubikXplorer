# Solver deadline evidence (SCP 2026-11-27 solver-deadline-tier)

Measured 2026-11-27 15:06–15:39 CST on the minimum reference device for the sprint change proposal
`_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-27-solver-deadline-tier.md`.

## Device and conditions

- Lenovo Tab M10 Plus 3rd Gen, `TB128XU`, USB serial `HA1S28MM` (always `-s HA1S28MM`; the Wi-Fi
  transport `192.168.0.20:37589` is the same device and was not used).
- Fingerprint `Lenovo/TB128XU_MXTC/TB128XU:13/TKQ1.221220.001/S101018_250611_ROW:user/release-keys`,
  Android 13 / API 33, SoC SM6225 (Snapdragon 680, `bengal`), 8 CPUs (4 × 1.90 GHz + 4 × 2.40 GHz),
  MemTotal 3,756,260 kB.
- `ro.config.low_ram` unset (so `ActivityManager.isLowRamDevice()` is false);
  `ro.odm.build.media_performance_class` unset (so `Build.VERSION.MEDIA_PERFORMANCE_CLASS` is 0).
- USB powered, battery 100 %, battery temperature 26.4 → 31.7 °C, thermal status 0 throughout,
  power save off. Device timeout, lock and display settings were not changed. Per-run conditions are in
  each raw log (`conditions,start` / `conditions,end`) and in the `*-conditions.txt` snapshots.
- No Samsung Galaxy was listed by `adb devices -l` before measuring, between sets, or after, so there
  is no mid-tier comparison.

## Code and builds

- Revision: `origin/main` `5a459c34` (PR #429 merged) plus this branch's harness
  `app/src/corpusRunner/kotlin/dev/cubentia/app/solver/MethodSolveBenchmarkActivity.kt` (declared in the
  `debug` and `benchmark` manifests only; never in `release`).
- `benchmark` build type (release-equivalent: R8, release flags, non-debuggable, debug-signed), installed
  with `adb install -r`: dexopt status `verify` (no AOT, no profile), so every first solve is
  interpreter/JIT-cold. A Play install with a cloud or baseline profile can only be faster.
- `debug` build type (debuggable) for the debug set.
- The prior app on the device (a debug build) was pulled before and reinstalled afterwards
  (`adb install -r` of the pulled base.apk); its data was kept.

## What is timed

The harness calls the use cases exactly as the app does, on a background thread with `runBlocking`:

- `SolveByMethod(bundle, deadlineMillis = Long.MAX_VALUE)` for BEGINNER, TWO_LOOK and FULL_OLL_PLL,
  with no Algorithm Statuses (no preference). The deadline is removed so a slow solve shows its real time;
  "> 3 s" in the table means it would have returned `TimedOut` with today's 3 s deadline.
- `SolveCube(TwoPhaseCubeSolver(tables))` with its own fixed 3 s deadline, tables loaded from the
  bundled asset by `PruningTableProvider` (as `SolverModule` does).
- `PlanRecovery(deadlineMillis = Long.MAX_VALUE)`: a random Lesson's entry state after 1–4 random Moves
  from the tutor Move set, goal `RecoveryPlanner.goal(lesson, [entry, entry])`, the content's
  `recoveryMaxDepth`.

Content loading and table preparation happen before the first timed solve (`prepared,nanos=`), outside
every timing, as in the app. Inputs are uniform random legal cubes (`randomLegalState(Random(seed + i))`,
seed 20261127), like a scanned cube. Time is wall clock (`System.nanoTime`) around the use-case call,
including the dispatcher hop and re-verification. Every timed solve returned `Solved` (`Found` for
PlanRecovery); none failed.

- **Cold:** `am force-stop`, then a new process; the first timed solve of that kind in the process is the
  JIT-cold one, followed by a 2nd and 3rd solve of different cubes. 25 processes per kind (benchmark,
  idle), 20 per method (loaded and debug sets). Kinds were interleaved run by run.
- **Warm:** one process per kind, 20 untimed solves, then 200 timed ones.
- **Loaded:** the cold method runs repeated while 8 `sh -c 'while :; do :; done'` shell processes
  (`timeout 900`) saturated all 8 cores (`top`: 800 % cpu, 9 % idle; load average 11.4 at the end).
  They were removed afterwards.

## Commands

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
./gradlew --no-daemon :app:assembleBenchmark        # and :app:assembleDebug for the debug set
adb -s HA1S28MM install -r app/build/outputs/apk/benchmark/app-benchmark.apk
# one cold sample (repeat with --ei index 0,10,20,... and kind BEGINNER|TWO_LOOK|FULL_OLL_PLL|SOLVE_CUBE|PLAN_RECOVERY)
adb -s HA1S28MM shell am force-stop com.exxerpro.cubentia
adb -s HA1S28MM shell am start -W -n com.exxerpro.cubentia/dev.cubentia.app.solver.MethodSolveBenchmarkActivity \
  --es kind TWO_LOOK --es mode cold --ei index 0 --ei count 3
# one warm corpus
adb -s HA1S28MM shell am start -W -n com.exxerpro.cubentia/dev.cubentia.app.solver.MethodSolveBenchmarkActivity \
  --es kind TWO_LOOK --es mode warm --ei index 100000 --ei cases 200 --ei warmup 20
adb -s HA1S28MM logcat -d -v epoch -s MethodSolveBenchmark:*
# loaded set: start 8 busy loops first
adb -s HA1S28MM shell "for i in 1 2 3 4 5 6 7 8; do (timeout 900 sh -c 'while :; do :; done' >/dev/null 2>&1 &); done"
```

Logcat occasionally dropped a line; each run also logs `summary,...,nanos=a;b;c` with every time, and the
per-solve lines are used where a summary was dropped (one debug TWO_LOOK run). No sample is missing.

## Results (milliseconds; p95 nearest-rank)

| Set | Use case / method | Sample | n | Median ms | p95 ms | Max ms | > 3 s | > 5 s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| benchmark build, idle, cold | BEGINNER | 1st solve (JIT-cold) | 25 | 1136 | 1263 | 1313 | 0 | 0 |
| benchmark build, idle, cold | BEGINNER | 2nd solve | 25 | 93 | 293 | 333 | 0 | 0 |
| benchmark build, idle, cold | BEGINNER | 3rd solve | 25 | 68 | 181 | 283 | 0 | 0 |
| benchmark build, idle, cold | TWO_LOOK | 1st solve (JIT-cold) | 25 | 1070 | 1188 | 1208 | 0 | 0 |
| benchmark build, idle, cold | TWO_LOOK | 2nd solve | 25 | 70 | 272 | 331 | 0 | 0 |
| benchmark build, idle, cold | TWO_LOOK | 3rd solve | 25 | 33 | 143 | 243 | 0 | 0 |
| benchmark build, idle, cold | FULL_OLL_PLL | 1st solve (JIT-cold) | 25 | 1116 | 1251 | 1252 | 0 | 0 |
| benchmark build, idle, cold | FULL_OLL_PLL | 2nd solve | 25 | 102 | 307 | 364 | 0 | 0 |
| benchmark build, idle, cold | FULL_OLL_PLL | 3rd solve | 25 | 67 | 178 | 275 | 0 | 0 |
| benchmark build, idle, cold | SOLVE_CUBE | 1st solve (JIT-cold) | 25 | 67 | 228 | 336 | 0 | 0 |
| benchmark build, idle, cold | SOLVE_CUBE | 2nd solve | 25 | 25 | 134 | 282 | 0 | 0 |
| benchmark build, idle, cold | SOLVE_CUBE | 3rd solve | 25 | 32 | 167 | 352 | 0 | 0 |
| benchmark build, idle, cold | PLAN_RECOVERY | 1st solve (JIT-cold) | 25 | 15 | 43 | 106 | 0 | 0 |
| benchmark build, idle, cold | PLAN_RECOVERY | 2nd solve | 25 | 5 | 53 | 58 | 0 | 0 |
| benchmark build, idle, cold | PLAN_RECOVERY | 3rd solve | 25 | 7 | 186 | 977 | 0 | 0 |
| benchmark build, idle, warm | BEGINNER | warm corpus (after 20 untimed) | 200 | 56 | 159 | 243 | 0 | 0 |
| benchmark build, idle, warm | TWO_LOOK | warm corpus (after 20 untimed) | 200 | 27 | 137 | 249 | 0 | 0 |
| benchmark build, idle, warm | FULL_OLL_PLL | warm corpus (after 20 untimed) | 200 | 49 | 159 | 263 | 0 | 0 |
| benchmark build, idle, warm | SOLVE_CUBE | warm corpus (after 20 untimed) | 200 | 29 | 160 | 456 | 0 | 0 |
| benchmark build, idle, warm | PLAN_RECOVERY | warm corpus (after 20 untimed) | 200 | 6 | 130 | 736 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | BEGINNER | 1st solve (JIT-cold) | 20 | 1219 | 1774 | 3004 | 1 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | BEGINNER | 2nd solve | 20 | 94 | 311 | 769 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | BEGINNER | 3rd solve | 20 | 72 | 376 | 575 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | TWO_LOOK | 1st solve (JIT-cold) | 20 | 1129 | 1242 | 1356 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | TWO_LOOK | 2nd solve | 20 | 72 | 225 | 230 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | TWO_LOOK | 3rd solve | 20 | 42 | 211 | 251 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | FULL_OLL_PLL | 1st solve (JIT-cold) | 20 | 1160 | 1255 | 1354 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | FULL_OLL_PLL | 2nd solve | 20 | 117 | 239 | 261 | 0 | 0 |
| benchmark build, 8 busy-loop shell processes, cold | FULL_OLL_PLL | 3rd solve | 20 | 78 | 246 | 484 | 0 | 0 |
| debug build (debuggable), idle, cold | BEGINNER | 1st solve (JIT-cold) | 20 | 3974 | 4381 | 4505 | 20 | 0 |
| debug build (debuggable), idle, cold | BEGINNER | 2nd solve | 20 | 473 | 948 | 1035 | 0 | 0 |
| debug build (debuggable), idle, cold | BEGINNER | 3rd solve | 20 | 312 | 725 | 1077 | 0 | 0 |
| debug build (debuggable), idle, cold | TWO_LOOK | 1st solve (JIT-cold) | 20 | 3520 | 4048 | 4088 | 20 | 0 |
| debug build (debuggable), idle, cold | TWO_LOOK | 2nd solve | 20 | 334 | 752 | 764 | 0 | 0 |
| debug build (debuggable), idle, cold | TWO_LOOK | 3rd solve | 20 | 208 | 548 | 867 | 0 | 0 |
| debug build (debuggable), idle, cold | FULL_OLL_PLL | 1st solve (JIT-cold) | 20 | 3744 | 4245 | 4298 | 20 | 0 |
| debug build (debuggable), idle, cold | FULL_OLL_PLL | 2nd solve | 20 | 524 | 959 | 961 | 0 | 0 |
| debug build (debuggable), idle, cold | FULL_OLL_PLL | 3rd solve | 20 | 377 | 731 | 1033 | 0 | 0 |

## Files

- `lenovo-cold-raw.log`, `lenovo-warm-raw.log`, `lenovo-loaded-cold-raw.log`, `lenovo-debug-cold-raw.log`:
  the harness's logcat lines (warm per-solve lines dropped; the `summary` lines carry every time).
- `lenovo-conditions.txt`, `lenovo-loaded-conditions.txt`, `lenovo-debug-conditions.txt`: battery,
  thermal, memory and load snapshots before and after each set (the loaded one includes `top`).
- `samples.csv`: every timed sample (`set,kind,mode,run,ordinal,millis`); sets `bench-idle`,
  `bench-loaded`, `debug-idle`.
