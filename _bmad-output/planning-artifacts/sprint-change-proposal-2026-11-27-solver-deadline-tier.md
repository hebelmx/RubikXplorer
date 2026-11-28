---
title: 'Sprint Change Proposal: solver deadline by device tier — measured; approved: 3 s release deadline (5 s on debug builds) plus a method-solve warm-up'
date: 2026-11-27
status: approved
approved: 'Approved by PO 2026-11-27: A + B, with E folded into the wording fix'
mode: batch
trigger: 'PR #429: SolveByMethodTest cold TWO_LOOK solve timed out at 3 s on a loaded CI runner (run 46007677912 attempt 1); PO asks for a deadline tiered by device processor class'
scope: Minor
---

# Sprint Change Proposal: solver deadline by device tier

> **Approved by PO 2026-11-27: A + B, with E folded into the wording fix.** (hebelmx)
>
> 1. **A + B.** The release solve deadline stays **3 s** and **NFR-3 is unchanged**. B is a new story
>    (**Story 15.4**): one throwaway method-solve warm-up, off-main and outside the deadline, after solver
>    preparation.
> 2. **E, folded into the wording fix.** Debuggable builds get a **5 s** solve deadline, because the debug
>    first solve on the Lenovo was 3.5–4.5 s. The release and benchmark builds keep 3 s.
> 3. The AD-8 / AR-12 wording fix is applied (`PlanRecovery` does not call `CubeSolver`), together with the
>    debug-build 5 s wording.
> 4. The `MethodSolveBenchmarkActivity` harness is kept, with a `docs/benchmarks.md` section on how to run
>    it and the results.
> 5. The Galaxy Tab S7 FE was measured as the mid-tier comparison (section 2).
>
> No device tier. The PRD is unchanged. The edits in section 4 are applied in this change.

**Bottom line.** On the minimum reference device (Lenovo TB128XU), with the release-equivalent build,
no solve came close to the 3 s deadline. The slowest JIT-cold method solve took **1.31 s**, and there were
0 of 75 over 3 s. On the Galaxy Tab S7 FE (mid-tier comparison) the slowest was **0.60 s** on a quiet
tablet, but **22 of 75 went over 3 s (max 3.49 s) while the tablet was busy** with other work, on battery
with power save on. In every set, the 2nd solve in the same process was under 0.9 s. The over-3 s cases are
one-time warm-up (class loading and JIT), not search. So the fix is to warm the method solver once,
outside the deadline (B, Story 15.4), not a device tier. Debug builds are 3–4× slower on the first solve
(Lenovo 60/60 over 3 s, all under 5 s), so they get a 5 s deadline (E).

- **Trigger:** PR #429 (merged `5a459c34`). PO request (hebelmx, 2026-11-27): tier the solve deadline
  by device processor class, for example 3 s by default and 5 s for low-end devices.
- **Mode:** Batch. The PO decided on 2026-11-27 (box above).
- **Scope:** Minor. One new Developer story (15.4) in Epic 15, plus wording edits to AD-8, AD-21, the
  spine's Deferred list, AR-12, the Story 3.4 SolveCube AC and the Epic 15 order (section 4).
- **Status:** approved and applied: architecture spine (with a `.memlog.md` decision line), `epics.md`
  and `sprint-status.yaml` (Story 15.4 `backlog`, through `sprint_plan.py generate`). The PRD is not edited.
- **Evidence:** `docs/evidence/scp-2026-11-27-solver-deadline/` (README with commands, revision, build,
  fingerprint, battery and thermal state for both devices; raw logs; `samples.csv`). Harness:
  `app/src/corpusRunner/kotlin/dev/cubentia/app/solver/MethodSolveBenchmarkActivity.kt` (only in
  `debug` and `benchmark`, never in `release`). How to run it: `docs/benchmarks.md` § Method-solve
  deadline.

## 1. Issue summary

### 1.1 What happened

`SolveByMethod` (FR-30, AD-21; Stories 15.1–15.3) wraps `MethodSolve.solve` in
`withTimeoutOrNull(DEADLINE_MILLIS = 3_000L)`, the same value as `SolveCube` and `PlanRecovery`.
In CI run 46007677912 attempt 1 (PR #424, job "Build and gates"), `SolveByMethodTest`'s first, JIT-cold
TWO_LOOK solve ran next to `:app:minifyBenchmarkWithR8`. It went past 3 s and returned `TimedOut`.
Locally it reproduced only under a 20 % CPU quota (`systemd-run -p CPUQuota=20%`). Unloaded, the test
takes about 0.56 s. PR #429 made the real-search tests use an unbounded deadline and kept the timeout
path's deterministic test. No production code changed.

`SolveByMethod` was added after AD-8 and had never been timed on a device. The PO's concern is that a
budget device might behave like the loaded CI runner. If so, a Learner would see "timed out" for a
method solution that would have arrived a little later.

### 1.2 Where the 3 s lives today

| Place | Text / value |
| --- | --- |
| PRD NFR-3 (`prd.md:597`) | 95 % of a random-state corpus solved **within 3 s on the minimum reference device** (a performance target, not a timeout) |
| PRD FR-15 (`prd.md:376`) | a Solver timeout or failure gives an explicit error with a recovery prompt (no value) |
| Spine AD-8 (`ARCHITECTURE-SPINE.md:238`) | `SolveCube` and `PlanRecovery` call the Solver "always as an Effect with a timeout of 3 s" |
| Spine AD-21 (`ARCHITECTURE-SPINE.md:430`) | `MethodSolve` "runs as an Effect with a timeout, like `SolveCube`" (no value) |
| Spine Deferred (`ARCHITECTURE-SPINE.md:575`) | solver internals "within AD-8's 3 s timeout" |
| Epics AR-12 (`epics.md:100`) | "called only by `SolveCube` and `PlanRecovery` as Effects with a 3 s timeout" |
| Epics Story 3.x SolveCube AC (`epics.md:824`) | "When `SolveCube` runs as an Effect with a 3 s timeout" |
| Epics Story 15.1 AC (`epics.md:4270`) | "`MethodSolve(BEGINNER)` runs as an Effect with a timeout" (no value) |
| Code | `SolveCube.kt:88`, `SolveByMethod.kt:144`, `PlanRecovery.kt:64` (`3_000L`), and the Solver's own search cap `TwoPhaseCubeSolver.kt:39,43` (`3_000_000_000L` ns) |
| `docs/ARCHITECTURE.md:202` | "within the 3 s timeout and 30 MB app size" |

Side note (pre-existing, not part of this change): `PlanRecovery` does not call `CubeSolver`. It runs
`RecoveryPlanner`. AD-8's "Callers" line and AR-12 still name it as a Solver caller. The approved
wording fix is in section 4.1.

## 2. Evidence (measured 2026-11-27)

Device: Lenovo Tab M10 Plus 3rd Gen `TB128XU` (`HA1S28MM`), the minimum reference device.
Fingerprint `Lenovo/TB128XU_MXTC/TB128XU:13/TKQ1.221220.001/S101018_250611_ROW:user/release-keys`,
Snapdragon 680 (SM6225), 8 CPUs, 3.6 GiB RAM. USB powered, battery 100 %, battery temperature
26.4–31.7 °C, thermal status 0 throughout, power save off. Revision `5a459c34` plus the harness.
`benchmark` build: release-equivalent (R8, non-debuggable), installed by `adb install` with dexopt
`verify` (no AOT or profile, the worst case for a first solve). `debug` build: debuggable. The Galaxy Tab S7 FE rows
(marked **Galaxy**) were measured later the same day with the same harness and protocol; their
conditions follow the table.

"Cold" means a new process (`am force-stop`), with content and pruning tables prepared before the
timer starts, as the app does. The 1st solve is the JIT-cold one, and the 2nd is the next solve in the same
process. "Warm" means 200 solves after 20 untimed ones. SolveByMethod and PlanRecovery ran **without a
deadline**, so a slow solve shows its real time. "> 3 s" counts solves that would have returned
`TimedOut` today. Every solve returned a verified solution, and none failed. "Saturated" means 8
busy-loop shell processes holding all 8 cores (`top` 800 % cpu, 9 % idle).

| Use case, build, sample (Lenovo TB128XU unless marked **Galaxy**) | n | Median ms | p95 ms | Max ms | > 3 s | > 5 s |
| --- | --- | --- | --- | --- | --- | --- |
| SolveByMethod BEGINNER, benchmark, cold 1st solve | 25 | 1136 | 1263 | 1313 | 0 | 0 |
| SolveByMethod TWO_LOOK, benchmark, cold 1st solve | 25 | 1070 | 1188 | 1208 | 0 | 0 |
| SolveByMethod FULL_OLL_PLL, benchmark, cold 1st solve | 25 | 1116 | 1251 | 1252 | 0 | 0 |
| SolveByMethod BEGINNER, benchmark, cold 2nd solve | 25 | 93 | 293 | 333 | 0 | 0 |
| SolveByMethod TWO_LOOK, benchmark, cold 2nd solve | 25 | 70 | 272 | 331 | 0 | 0 |
| SolveByMethod FULL_OLL_PLL, benchmark, cold 2nd solve | 25 | 102 | 307 | 364 | 0 | 0 |
| SolveByMethod BEGINNER, benchmark, warm | 200 | 56 | 159 | 243 | 0 | 0 |
| SolveByMethod TWO_LOOK, benchmark, warm | 200 | 27 | 137 | 249 | 0 | 0 |
| SolveByMethod FULL_OLL_PLL, benchmark, warm | 200 | 49 | 159 | 263 | 0 | 0 |
| SolveCube, benchmark, cold 1st solve | 25 | 67 | 228 | 336 | 0 | 0 |
| SolveCube, benchmark, warm | 200 | 29 | 160 | 456 | 0 | 0 |
| PlanRecovery, benchmark, cold 1st solve | 25 | 15 | 43 | 106 | 0 | 0 |
| PlanRecovery, benchmark, warm | 200 | 6 | 130 | 736 | 0 | 0 |
| SolveByMethod BEGINNER, benchmark, all 8 cores saturated, cold 1st | 20 | 1219 | 1774 | 3004 | 1 | 0 |
| SolveByMethod TWO_LOOK, benchmark, all 8 cores saturated, cold 1st | 20 | 1129 | 1242 | 1356 | 0 | 0 |
| SolveByMethod FULL_OLL_PLL, benchmark, all 8 cores saturated, cold 1st | 20 | 1160 | 1255 | 1354 | 0 | 0 |
| SolveByMethod BEGINNER, **debug**, cold 1st solve | 20 | 3974 | 4381 | 4505 | 20 | 0 |
| SolveByMethod TWO_LOOK, **debug**, cold 1st solve | 20 | 3520 | 4048 | 4088 | 20 | 0 |
| SolveByMethod FULL_OLL_PLL, **debug**, cold 1st solve | 20 | 3744 | 4245 | 4298 | 20 | 0 |
| SolveByMethod BEGINNER, **debug**, cold 2nd solve | 20 | 473 | 948 | 1035 | 0 | 0 |
| SolveByMethod TWO_LOOK, **debug**, cold 2nd solve | 20 | 334 | 752 | 764 | 0 | 0 |
| SolveByMethod FULL_OLL_PLL, **debug**, cold 2nd solve | 20 | 524 | 959 | 961 | 0 | 0 |
| **Galaxy** SolveByMethod BEGINNER, benchmark, cold 1st solve | 25 | 512 | 575 | 603 | 0 | 0 |
| **Galaxy** SolveByMethod TWO_LOOK, benchmark, cold 1st solve | 25 | 470 | 539 | 545 | 0 | 0 |
| **Galaxy** SolveByMethod FULL_OLL_PLL, benchmark, cold 1st solve | 25 | 500 | 561 | 562 | 0 | 0 |
| **Galaxy** SolveByMethod BEGINNER, benchmark, cold 2nd solve | 25 | 48 | 144 | 151 | 0 | 0 |
| **Galaxy** SolveByMethod TWO_LOOK, benchmark, cold 2nd solve | 25 | 38 | 133 | 145 | 0 | 0 |
| **Galaxy** SolveByMethod FULL_OLL_PLL, benchmark, cold 2nd solve | 25 | 66 | 166 | 174 | 0 | 0 |
| **Galaxy** SolveByMethod BEGINNER, benchmark, warm | 200 | 62 | 156 | 233 | 0 | 0 |
| **Galaxy** SolveByMethod TWO_LOOK, benchmark, warm | 200 | 22 | 127 | 228 | 0 | 0 |
| **Galaxy** SolveByMethod FULL_OLL_PLL, benchmark, warm | 200 | 51 | 150 | 234 | 0 | 0 |
| **Galaxy** SolveCube, benchmark, cold 1st solve | 25 | 74 | 186 | 245 | 0 | 0 |
| **Galaxy** SolveCube, benchmark, warm | 200 | 25 | 144 | 494 | 0 | 0 |
| **Galaxy** PlanRecovery, benchmark, cold 1st solve | 25 | 6 | 19 | 58 | 0 | 0 |
| **Galaxy** PlanRecovery, benchmark, warm | 200 | 2 | 65 | 442 | 0 | 0 |
| **Galaxy** SolveByMethod BEGINNER, benchmark, busy tablet (set 1), cold 1st | 25 | 2744 | 3364 | 3374 | 8 | 0 |
| **Galaxy** SolveByMethod TWO_LOOK, benchmark, busy tablet (set 1), cold 1st | 25 | 2768 | 3171 | 3271 | 7 | 0 |
| **Galaxy** SolveByMethod FULL_OLL_PLL, benchmark, busy tablet (set 1), cold 1st | 25 | 2876 | 3381 | 3485 | 7 | 0 |
| **Galaxy** SolveByMethod BEGINNER, **debug**, cold 1st solve | 20 | 2120 | 2422 | 2658 | 0 | 0 |
| **Galaxy** SolveByMethod TWO_LOOK, **debug**, cold 1st solve | 20 | 1819 | 2114 | 2157 | 0 | 0 |
| **Galaxy** SolveByMethod FULL_OLL_PLL, **debug**, cold 1st solve | 20 | 1949 | 2188 | 2207 | 0 | 0 |
| **Galaxy** SolveByMethod BEGINNER, **debug**, cold 2nd solve | 20 | 325 | 682 | 719 | 0 | 0 |
| **Galaxy** SolveByMethod TWO_LOOK, **debug**, cold 2nd solve | 20 | 185 | 389 | 399 | 0 | 0 |
| **Galaxy** SolveByMethod FULL_OLL_PLL, **debug**, cold 2nd solve | 20 | 361 | 609 | 625 | 0 | 0 |

Galaxy conditions: Samsung Galaxy Tab S7 FE `SM-T738U` (`R52T50ELHWK`, the previous minimum reference
device), over wireless adb. Fingerprint
`samsung/gts7xllitesqwnc/gts7xllite:12/SP1A.210812.016/T738USQU3BVG7:user/release-keys`, Android 12,
Snapdragon 750G (SM7225), 8 CPUs, 3.4 GiB RAM with about 2 GB of swap in use. **On battery** (99 → 96 %),
**power save on**, battery temperature 23.7–26.2 °C, thermal status 0 throughout. Power save, timeout, lock
and display settings were left as found. Revision `49044baf` (the solver and use cases unchanged from
`5a459c34`). The unmarked Galaxy benchmark rows are set 2 (17:51–17:56, load average 3.7–6.8). "Busy tablet
(set 1)" ran first (17:16–17:44) while the tablet was busy with other work (load average 8–14, under
0.4 GB available, each run about 6× slower to complete); a 10-run recheck between the sets matched set 2.
The debug set ran twice with the same result (the repeat is in the evidence README).

Context from earlier evidence: the 10,101-case Kociemba corpus on this device had 10,101 within 3 s, with a
max of 1,561 ms (`docs/benchmarks.md`). Cold Accept → Solution was 3.51 s median, with preparation outside
the deadline (SCP 2026-11-25 close-104).

### 2.1 What the numbers say

1. **Release-equivalent, idle:** the worst JIT-cold method solve is 1.31 s, which leaves 1.7 s of margin.
   0/75 cold and 0/600 warm method solves went over 3 s. A device tier would change nothing a Learner sees.
2. **Release-equivalent, all cores saturated:** TWO_LOOK and FULL_OLL_PLL barely move (max 1.36 s).
   One BEGINNER first solve reached 3,004 ms, 1 of 60. The app runs in the foreground (top-app) and
   gets CPU ahead of background work. That is why the CI runner, where the test competed with R8 at equal
   priority, is not a model for a phone or tablet.
3. **About 1 s of every first method solve is one-time warm-up.** The 2nd solve of the same method is
   about 10× faster (median 70–102 ms). The warm-up is class loading and JIT (and possibly lazy content
   structures), not search. A tier would only pay for this warm-up. Doing the warm-up outside the deadline
   removes it.
4. **Debug build:** debuggable ART is 3–4× slower on a first solve. All 60 cold first method solves were
   3.5–4.5 s, so all would time out at 3 s and none at 5 s. The 2nd solves were 0.33–0.52 s median, with a
   max of 1.04 s. Debug device sessions on the Lenovo always see "timed out" on the first style tap
   (`SolutionStyles.kt:229` falls back to *Shortest*), and a second tap succeeds.
5. **SolveCube and PlanRecovery** are far below 3 s whether cold or warm. Their worst cases are 456 ms and
   977 ms. Nothing here supports tiering them.
6. **Galaxy Tab S7 FE, quiet:** about 2× faster than the Lenovo on a first method solve (0.47–0.51 s median,
   max 0.60 s) and on par warm. The debug first solve is 1.8–2.2 s median (max 2.66 s): under 3 s, so the
   5 s debug deadline matters on the Lenovo only.
7. **Galaxy Tab S7 FE, busy:** with the tablet busy with other work, on battery and in power save, the
   release-equivalent first method solve was 2.7–2.9 s median and **22 of 75 went over 3 s** (max 3.49 s; one
   PlanRecovery 3rd solve took 4.4 s). The 2nd solve stayed under 0.9 s. So a mid-tier device can time out
   the first style tap in release when it is busy. This is again one-time warm-up, which B removes; a device
   tier would not help, because the same device was 6× faster a few minutes later.

### 2.2 Device-tier detection, checked on the minimum device

| Signal | Value on the Lenovo TB128XU | Reliability on budget devices |
| --- | --- | --- |
| `Build.VERSION.MEDIA_PERFORMANCE_CLASS` | **0** (`ro.odm.build.media_performance_class` unset) | 0 means "not declared", not "slow". Mostly mid/flagship OEM builds on Android 12+ declare it, and many fast devices report 0. It cannot tell the Lenovo from a fast phone, and it measures media capability, not ART/JIT speed. |
| `ActivityManager.isLowRamDevice()` | **false** (`ro.config.low_ram` unset, 4 GB) | Only Android Go-class (≤ 2 GB) devices set it. It misses every 3–4 GB budget device, including the minimum reference device. |
| CPU or core heuristics (`availableProcessors`, `cpuinfo_max_freq`) | 8 cores, 4 × 1.9 + 4 × 2.4 GHz: looks "mid-range" | Core count and clock do not predict JIT-cold time. Sysfs paths vary by vendor and may be unreadable. Brittle lists need upkeep. |
| Measured warm-up (time one throwaway solve after preparation) | ~1.1 s release, ~3.7 s debug | This measures the real thing on the real device, but it is noisy under load and costs about 1 s of CPU once per process. Once you run a warm-up, the cold penalty is gone, so the tier it would choose is no longer needed. |
| Build type (`ApplicationInfo.FLAG_DEBUGGABLE`) | separates the only case measured over 3 s | Deterministic, but it only helps developers and diverges debug behaviour from release. |

## 3. Options and recommended approach

| Option | What changes | Effect on the measured cases | Planning edits | Effort / risk |
| --- | --- | --- | --- | --- |
| **A. No change** (recommended) | nothing | release: 0 timeouts idle, 1/60 under full saturation; debug: first style tap always times out | none | none / none |
| **B. Warm-up outside the deadline** (recommended, optional story) | after the solver/content preparation (or when the style chips appear), run one throwaway `MethodSolve` off-main, untimed and cancellable. The real solve keeps 3 s. | the first real solve behaves like a 2nd solve: release ≤ 0.36 s idle (0.77 s saturated), debug ≤ 1.04 s | none required (AD-8/AD-21 already put preparation outside the deadline); optional one-line AD-21 clarification (4.3) | ~0.5 day; ~1 s of background CPU once per process; needs a test that the warm-up never reaches the session |
| **C. Tier SolveByMethod only** (3 s default, 5 s low tier) | a `SolveDeadlinePolicy` chosen once per process in `:app`, injected into `SolveByMethod` | release: no visible change; debug: fixed only if debug counts as "low tier", because none of the signals in 2.2 classify the Lenovo as low-end | AD-21 (4.1) | ~1 day plus a detection decision; risk of mis-tiering, and a longer wait before "timed out" on a hang |
| **D. Tier all three use cases** | as C, plus `SolveCube`, `PlanRecovery` and `TwoPhaseCubeSolver`'s internal cap | none measured: those use cases peak at 0.98 s | AD-8, AR-12, SolveCube AC, Deferred, `docs/ARCHITECTURE.md` (4.2) | ~1.5 days; touches the NFR-3 measurement boundary and the Solver's own cap |
| E. Longer deadline on debuggable builds only | e.g. 10 s when `FLAG_DEBUGGABLE` | debug fixed; release unchanged | none in the PRD; one sentence in AD-21 if the PO wants it recorded | small; debug no longer exercises the release timeout path |

**Decision (PO, 2026-11-27): A + B, with E folded into the wording fix.** The options table above is
kept as the record of what was weighed.

- *No tier:* the minimum reference device, cold and with no AOT, has more than 1.7 s of margin, and none of
  the cheap tier signals classify it as low-end (2.2). The only release-equivalent over-3 s cases (the
  saturated Lenovo BEGINNER outlier and the busy Galaxy set) are one-time warm-up, and the same Galaxy was
  6× faster minutes later, so a tier chosen by device class would not match them.
- *B (Story 15.4):* warming the method-solve path once after preparation, outside the deadline, makes the
  first real solve behave like a 2nd solve: ≤ 0.36 s on the idle Lenovo, ≤ 0.87 s on the busy Galaxy and
  ≤ 1.04 s on the Lenovo debug build. It is consistent with the existing rule that preparation runs outside
  the deadline (AD-8 pruning tables, Story 3.8). With the Galaxy result it is no longer optional polish: it
  covers a release case.
- *E (folded in):* debuggable builds use a 5 s use-case deadline, so PO device sessions on a debug build do
  not time out on the first style tap even before the warm-up lands (Lenovo debug first solve max 4.5 s).
  Release and benchmark builds keep 3 s, so the release timeout path is still what the benchmark build
  exercises. The Solver's own search cap (`TwoPhaseCubeSolver.kt:39,43`) is unchanged.
- *Wording:* AD-8 and AR-12 stop naming `PlanRecovery` as a Solver caller.
- **NFR-3 stays as it is.** It is a corpus target (95 % within 3 s on the minimum device), and it is met:
  10,101/10,101 for Kociemba, and 75/75 cold plus 600/600 warm for the method solves measured here.

## 4. Detailed change proposals (OLD → NEW), approved and applied

Line numbers are at `49044baf` (unchanged from `5a459c34` for these lines). Each line is replaced whole.

### 4.1 Architecture spine, AD-8 Callers (`ARCHITECTURE-SPINE.md:238`): 3 s release deadline, 5 s on debuggable builds, PlanRecovery wording

OLD:
```
  - **Callers:** only the `SolveCube` and `PlanRecovery` use cases call it, always as an Effect with a timeout of 3 s.
```
NEW:
```
  - **Callers:** only the `SolveCube` use case calls it, always as an Effect with a timeout of 3 s, or 5 s in debuggable builds (release and benchmark builds keep 3 s; the value is chosen once per process in `:app`, and the Solver's own search cap is unchanged). `PlanRecovery` (`RecoveryPlanner`) and `SolveByMethod` (`MethodSolve`, AD-21) never call it, but use the same Effect-with-timeout shape and the same deadline. *(SCP 2026-11-27 solver-deadline-tier)*
```

### 4.2 Architecture spine, AD-21 (`ARCHITECTURE-SPINE.md:430`): the deadline value and the warm-up outside it

OLD:
```
  - It runs as an Effect with a timeout, like `SolveCube`, and every result is re-verified by applying it to its input before it is shown.
```
NEW:
```
  - It runs as an Effect with a timeout, like `SolveCube` (AD-8: 3 s; 5 s in debuggable builds), and every result is re-verified by applying it to its input before it is shown. After solver preparation, one throwaway `MethodSolve` warm-up runs off-main, untimed and cancellable, outside the deadline; its result is discarded and never reaches a session. *(SCP 2026-11-27 solver-deadline-tier)*
```

### 4.3 Architecture spine, Deferred (`ARCHITECTURE-SPINE.md:575`)

OLD:
```
- **Solver internals** (coordinate encoding, the search depth limit): decided in the solver epic, within AD-8's 3 s timeout and NFR-11.
```
NEW:
```
- **Solver internals** (coordinate encoding, the search depth limit): decided in the solver epic, within AD-8's 3 s timeout (5 s in debuggable builds only) and NFR-11.
```
(The rest of that line, from "Pruning-table delivery is decided by Story 3.8" onward, is unchanged.)

### 4.4 Architecture spine `.memlog.md`

One decision line appended with `uv run _bmad/scripts/memlog.py append --type decision --by PO`
(the log is append-only).

### 4.5 Epics, AR-12 (`epics.md:100`)

OLD:
```
... called only by `SolveCube` and `PlanRecovery` as Effects with a 3 s timeout; ...
```
NEW:
```
... called only by `SolveCube`, as an Effect with a 3 s timeout (5 s in debuggable builds; `PlanRecovery` and `SolveByMethod` never call it but use the same timeout, SCP 2026-11-27 solver-deadline-tier); ...
```

### 4.6 Epics, Story 3.4 SolveCube AC (`epics.md:824`)

OLD:
```
**When** `SolveCube` runs as an Effect with a 3 s timeout
```
NEW:
```
**When** `SolveCube` runs as an Effect with a 3 s timeout (5 s in debuggable builds) *(SCP 2026-11-27 solver-deadline-tier)*
```

### 4.7 Epics, Epic 15 order in the Epic List (`epics.md:273`)

OLD:
```
**Order:** 15.1 → 15.2 → 15.3. **v1.0 scope** (PO, 2026-11-20): runs before release Stories 12.3, 12.4 and 12.5.
```
NEW:
```
**Order:** 15.1 → 15.2 → 15.3 → 15.4. **v1.0 scope** (PO, 2026-11-20): runs before release Stories 12.3, 12.4 and 12.5. *(SCP 2026-11-27 solver-deadline-tier: Story 15.4 added, a method-solve warm-up outside the deadline and a 5 s deadline on debuggable builds.)*
```

### 4.8 Epics, new Story 15.4 (appended to Epic 15 after Story 15.3, `epics.md:4353`)

OLD: (none)

NEW:
````
### Story 15.4: Warm the method solver once, and give debug builds a 5 s solve deadline

As a Learner (and the PO testing on a debug build),
I want my first solution-style tap to return a method solution instead of "timed out",
So that the first method solution behaves like every later one.

**Refs:** FR-30, FR-15 · AD-8, AD-21 · NFR-3 · *(SCP 2026-11-27 solver-deadline-tier, PO decision 2026-11-27: A + B, with E folded into the wording fix)*

**Acceptance Criteria:**

**Given** the method-solve content and pruning tables are prepared in the app process
**When** preparation finishes
**Then** one throwaway `MethodSolve` runs off-main, untimed and cancellable, outside every deadline; its result is discarded and never dispatched to a session, and a failure or cancellation is silent (logged in debug builds only)

**Given** a Learner taps a solution style before the warm-up has finished
**When** `SolveByMethod` runs
**Then** the real solve runs with its own deadline and is not queued behind the warm-up

**Given** a release or benchmark build
**When** `SolveCube`, `SolveByMethod` or `PlanRecovery` runs
**Then** its deadline is 3 s; in a debuggable build (`ApplicationInfo.FLAG_DEBUGGABLE`) it is 5 s. The value is one constant chosen once per process in `:app` and passed to the use cases; `TwoPhaseCubeSolver`'s own search cap and NFR-3 are unchanged, and unit tests cover both values

**Given** the Lenovo TB128XU (minimum reference device) and `MethodSolveBenchmarkActivity` running the production warm-up after preparation and before the first timed solve
**When** it re-runs the SCP protocol: 25 cold first solves per method (BEGINNER, TWO_LOOK, FULL_OLL_PLL) on the benchmark build and 20 per method on the debug build
**Then** every benchmark-build cold first solve is under 3 s, and every debug-build cold first solve is under 3 s as well, or at worst within 5 s (0 of 60 over 5 s); the same benchmark-build run on the Galaxy Tab S7 FE, the mid-tier comparison, shows every cold first solve under 3 s; the results are added next to the "before" rows in `docs/benchmarks.md` § Method-solve deadline

**Given** the Lenovo debug build after a cold start
**When** the PO taps a method style in Solution Playback for the first time (5 cold starts)
**Then** a method solution plays and "Couldn't build that solution in time" does not appear

**Given** the change
**When** `./gradlew spotlessCheck build` runs
**Then** it is green, including a JVM test that the warm-up result never reaches a session and cancels cleanly
````

### 4.9 Sprint status

`15-4-warm-the-method-solver-once-and-give-debug-builds-a-5-s-solv: backlog` added, and `epic-15` set
from `done` to `in-progress`, by
`uv run .claude/skills/bmad-sprint-planning/scripts/sprint_plan.py generate ... --set epic-15=in-progress`,
then `sprint_plan.py validate` (valid, no problems). The generator also normalised the hand-written
Story 10.37 key from `10-37-continuous-quick-scan-detection-lattice-fitting-and-tracki` to
`10-37-continuous-quick-scan-detection-lattice-fitting-and-tracking` (the slug of its `epics.md`
heading). Its `ready-for-dev` status was kept with `--set`; its spec file
`spec-10-37-continuous-quick-scan-detection-lattice-fitting-and-tracki.md` keeps its old name. The
generator also re-wrapped long `action_items` strings and normalised `last_updated`; no value changed.

### 4.10 Unchanged

- PRD NFR-3 (`prd.md:597`) and FR-15 (`prd.md:376`).
- Story 15.1's AC (`epics.md:4270`, "with a timeout", no value) and Story 15.3's timeout copy.
- The Story 3.3 / 3.8 / 12.2 corpus ACs (`epics.md:805`, `:961`, `:3473`), which measure "within 3 s"
  as NFR-3 does.
- `docs/ARCHITECTURE.md:202` ("within the 3 s timeout"): still true for release.
- UX copy (`msg.playback.style.timed-out`, `msg.solve.timed-out`).

## 5. Impact analysis

- **PRD:** none.
- **Architecture:** AD-8 (Callers wording and the debug-build value), AD-21 (deadline value and the warm-up)
  and the Deferred line. No new AD, port or module.
- **Epics / ACs:** AR-12 and the Story 3.4 SolveCube AC (wording), the Epic 15 order, and new Story 15.4.
  Epic 15 reopens (`in-progress`) for one story.
- **UX:** none. On debug builds a Learner-facing "timed out" can take up to 5 s to appear; release is
  unchanged.
- **Code (Story 15.4, not in this change):** a warm-up call after method-solver preparation (off-main,
  cancellable, never dispatched), and one deadline value chosen in `:app` from
  `ApplicationInfo.FLAG_DEBUGGABLE` and passed to `SolveCube`, `SolveByMethod` (`AppShell.kt:176`) and
  `PlanRecovery` (`LessonScreen.kt:165`); `SolveCube` needs a deadline constructor parameter.
  `TwoPhaseCubeSolver`'s cap is unchanged.
- **Tests:** a JVM test that the warm-up result never reaches a session and cancels cleanly, and deadline
  tests for both values; the existing `DEADLINE_MILLIS` assertions stay at 3 s for release.
- **Benchmark tooling:** `MethodSolveBenchmarkActivity` stays (debug and benchmark manifests only) and is
  documented in `docs/benchmarks.md` § Method-solve deadline. Story 15.4 adds a mode that runs the
  production warm-up before the first timed solve.

## 6. PO decisions (2026-11-27)

1. Deadline approach: **A + B**, with **E** folded into the wording fix.
2. Tier detection: not needed (no tier). The only build-type split is debuggable vs not.
3. Values: **3 s** release and benchmark; **5 s** debuggable.
4. NFR-3: **unchanged.**
5. AD-8 / AR-12 wording fix: **applied** in this change, with the debug-build 5 s wording.
6. Mid-tier comparison: **measured** on the Galaxy Tab S7 FE (section 2).
7. Harness: **kept**, with a `docs/benchmarks.md` section.

## 7. Implementation handoff

- **Scope:** Minor. Developer: Story 15.4 (about half a day), through `bmad-build` / `bmad-orchestrate`.
- **Success criteria:** Story 15.4's ACs. In short: on the Lenovo TB128XU, 25 cold first solves per method
  on the benchmark build all under 3 s, and 20 per method on the debug build all under 3 s or at worst within
  5 s; the Galaxy Tab S7 FE benchmark build all under 3 s; the first style tap on the Lenovo debug build
  never shows "timed out"; no warm-up result reaches a session; `./gradlew spotlessCheck build` green.
