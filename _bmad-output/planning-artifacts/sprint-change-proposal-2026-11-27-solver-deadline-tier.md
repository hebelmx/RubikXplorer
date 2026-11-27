---
title: 'Sprint Change Proposal: solver deadline by device tier (3 s default, 5 s low-end) — measured, and not needed for release'
date: 2026-11-27
status: proposed
mode: batch
trigger: 'PR #429: SolveByMethodTest cold TWO_LOOK solve timed out at 3 s on a loaded CI runner (run 46007677912 attempt 1); PO asks for a deadline tiered by device processor class'
scope: Minor
---

# Sprint Change Proposal: solver deadline by device tier

**Bottom line.** On the minimum reference device (Lenovo TB128XU), with the release-equivalent build,
no solve came close to the 3 s deadline. The slowest JIT-cold method solve took **1.31 s**, and there were
0 of 75 over 3 s. All 600 warm method solves were under 0.27 s. SolveCube and PlanRecovery peaked at
0.34 s and 0.98 s. With all 8 cores saturated, 1 of 60 cold method solves reached **3.00 s**.
So a device tier is **not needed** for what ships, and the recommendation is **no change** to the PRD,
the architecture or the ACs.

The evidence does show one real problem: the **debug** build. There, **every** JIT-cold first method solve
is over 3 s (60/60, 3.5–4.5 s, all under 5 s). The first style tap in a debug device session therefore
always falls back to *Shortest* with "timed out", and a second tap works. That comes from the build type,
not the device class. The fix that matches it is a small, optional Developer story: warm the method-solve
path once, outside the deadline (section 3, option B). A device tier is kept as option C if the PO still
wants one.

- **Trigger:** PR #429 (merged `5a459c34`). PO request (hebelmx, 2026-11-27): tier the solve deadline
  by device processor class, for example 3 s by default and 5 s for low-end devices.
- **Mode:** Batch, non-interactive. Every PO decision is a question in section 6.
- **Scope:** Minor. With the recommended option A (plus optional B), no planning artifact changes.
  Options C and D list the exact OLD → NEW edits in section 4. They are applied only after PO approval.
- **Status:** proposed. The PRD, architecture spine, epics and sprint-status are **not** edited by this
  proposal.
- **Evidence:** `docs/evidence/scp-2026-11-27-solver-deadline/` (README with commands, revision, build,
  fingerprint, battery and thermal state; raw logs; `samples.csv`). Harness:
  `app/src/corpusRunner/kotlin/dev/cubentia/app/solver/MethodSolveBenchmarkActivity.kt` (only in
  `debug` and `benchmark`, never in `release`).

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
`RecoveryPlanner`. AD-8's "Callers" line and AR-12 still name it as a Solver caller. Section 4.4 offers
an optional wording fix.

## 2. Evidence (measured 2026-11-27)

Device: Lenovo Tab M10 Plus 3rd Gen `TB128XU` (`HA1S28MM`), the minimum reference device.
Fingerprint `Lenovo/TB128XU_MXTC/TB128XU:13/TKQ1.221220.001/S101018_250611_ROW:user/release-keys`,
Snapdragon 680 (SM6225), 8 CPUs, 3.6 GiB RAM. USB powered, battery 100 %, battery temperature
26.4–31.7 °C, thermal status 0 throughout, power save off. Revision `5a459c34` plus the harness.
`benchmark` build: release-equivalent (R8, non-debuggable), installed by `adb install` with dexopt
`verify` (no AOT or profile, the worst case for a first solve). `debug` build: debuggable. No Samsung
Galaxy was connected (`adb devices -l` checked before, between and after the runs), so there is no
mid-tier row.

"Cold" means a new process (`am force-stop`), with content and pruning tables prepared before the
timer starts, as the app does. The 1st solve is the JIT-cold one, and the 2nd is the next solve in the same
process. "Warm" means 200 solves after 20 untimed ones. SolveByMethod and PlanRecovery ran **without a
deadline**, so a slow solve shows its real time. "> 3 s" counts solves that would have returned
`TimedOut` today. Every solve returned a verified solution, and none failed. "Saturated" means 8
busy-loop shell processes holding all 8 cores (`top` 800 % cpu, 9 % idle).

| Use case, build, sample (Lenovo TB128XU) | n | Median ms | p95 ms | Max ms | > 3 s | > 5 s |
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

**Recommendation: A, plus B as a small optional Developer story.**

- *Why not a tier:* the device class the tier is meant to protect is the one that was measured: the minimum
  reference device, cold, with no AOT. It has more than 1.7 s of margin. None of the cheap tier signals
  even classify it as low-end (2.2). A tier would add a policy, detection code and test matrix for a case
  that was not observed in release.
- *Why B:* it removes the only measured over-3 s cases, the debug first solve and the saturated BEGINNER
  outlier, at their cause, the one-time warm-up. It keeps a single 3 s rule. It is consistent with the
  existing principle that preparation runs outside the deadline (AD-8 pruning tables, Story 3.8 move-table
  fix). It also makes PO device sessions on debug builds match release behaviour.
- *Which use cases if a tier is chosen anyway:* **SolveByMethod only** (option C). SolveCube and PlanRecovery
  have 3–30× margin, and tiering SolveCube would also change the Solver's internal cap
  (`TwoPhaseCubeSolver.kt:39,43`) and the NFR-3 measurement boundary.
- *Values if tiered:* 3 s default and 5 s low tier. 5 s covers the worst debug first solve (4.5 s), and
  nothing in release needs more.
- *Detection if tiered:* a measured warm-up, or build type. Not `MEDIA_PERFORMANCE_CLASS` and not
  `isLowRamDevice`: on the minimum reference device they report "not declared" and "false".
- **NFR-3 stays as it is** under every option. It is a corpus target (95 % within 3 s on the minimum
  device), and it is met: 10,101/10,101 for Kociemba, and 75/75 cold plus 600/600 warm for the method solves
  measured here.

## 4. Detailed change proposals (OLD → NEW)

Option A needs **no** planning edits, and neither does option B. The edits below apply only if the PO
picks C, D or the optional clarifications. Line numbers are at `5a459c34`.

### 4.1 Option C — Architecture spine, AD-21 (`ARCHITECTURE-SPINE.md:430`)

OLD:
```
  - It runs as an Effect with a timeout, like `SolveCube`, and every result is re-verified by applying it to its input before it is shown.
```
NEW:
```
  - It runs as an Effect with a timeout, and every result is re-verified by applying it to its input before it is shown. The timeout is 3 s, or 5 s in the low device tier; the tier is chosen once per process in `:app` (`SolveDeadlinePolicy`) and only the value is passed to `:core:application`. AD-8's 3 s Solver timeout is unchanged. *(SCP 2026-11-27 solver-deadline-tier)*
```
Rationale: `MethodSolve` never calls `CubeSolver`, so its deadline can differ from AD-8's without touching
the Solver rule or NFR-3.

No PRD, epics or UX edit is needed for C. Story 15.1's AC says "with a timeout" without a value.
Optional story-level AC for the implementing story (new, Epic 15): "**Given** the low device tier **When**
`SolveByMethod` runs **Then** its deadline is 5 s, and 3 s otherwise; the tier is decided once per process
and the decision is logged in debug."

### 4.2 Option D — all three use cases (in addition to 4.1)

Spine AD-8 (`ARCHITECTURE-SPINE.md:238`)

OLD:
```
  - **Callers:** only the `SolveCube` and `PlanRecovery` use cases call it, always as an Effect with a timeout of 3 s.
```
NEW:
```
  - **Callers:** only the `SolveCube` and `PlanRecovery` use cases call it, always as an Effect with a timeout of 3 s, or 5 s in the low device tier (`SolveDeadlinePolicy`, chosen once per process in `:app`). *(SCP 2026-11-27 solver-deadline-tier)*
```

Spine Deferred (`ARCHITECTURE-SPINE.md:575`)

OLD:
```
- **Solver internals** (coordinate encoding, the search depth limit): decided in the solver epic, within AD-8's 3 s timeout and NFR-11.
```
NEW:
```
- **Solver internals** (coordinate encoding, the search depth limit): decided in the solver epic, within AD-8's tiered timeout (3 s; 5 s in the low device tier) and NFR-11.
```
(The rest of that line, from "Pruning-table delivery is decided by Story 3.8" onward, is unchanged.)

Epics AR-12 (`epics.md:100`)

OLD:
```
... called only by `SolveCube` and `PlanRecovery` as Effects with a 3 s timeout; ...
```
NEW:
```
... called only by `SolveCube` and `PlanRecovery` as Effects with a 3 s timeout (5 s in the low device tier, SCP 2026-11-27 solver-deadline-tier); ...
```

Epics SolveCube AC (`epics.md:824`)

OLD:
```
**When** `SolveCube` runs as an Effect with a 3 s timeout
```
NEW:
```
**When** `SolveCube` runs as an Effect with a 3 s timeout (5 s in the low device tier) *(SCP 2026-11-27 solver-deadline-tier)*
```

`docs/ARCHITECTURE.md:202` (reader-facing, not a planning artifact; aligned in the same PR)

OLD: `| Solver internals (bundled or generated pruning tables) | solver epic, within the 3 s timeout and 30 MB app size |`
NEW: `| Solver internals (bundled or generated pruning tables) | solver epic, within the tiered timeout (3 s; 5 s low tier) and 30 MB app size |`

Unchanged under D: PRD NFR-3 (`prd.md:597`) and FR-15 (`prd.md:376`); the Story 3.3/3.8/12.2 corpus ACs
(`epics.md:805`, `:961`, `:3473`), which measure "within 3 s" as NFR-3 does; and the dated annotations
(`epics.md:811`, `:3479`).

### 4.3 Option B (optional clarification only) — AD-21 (`ARCHITECTURE-SPINE.md:430`)

OLD:
```
  - It runs as an Effect with a timeout, like `SolveCube`, and every result is re-verified by applying it to its input before it is shown.
```
NEW:
```
  - It runs as an Effect with a timeout, like `SolveCube`, and every result is re-verified by applying it to its input before it is shown. A one-time warm-up solve may run off-main after preparation, outside the timeout; its result is discarded and never reaches a session. *(SCP 2026-11-27 solver-deadline-tier)*
```

### 4.4 Optional wording fix (any option) — AD-8 and AR-12 name `PlanRecovery` as a Solver caller

`PlanRecovery` runs `RecoveryPlanner`, not `CubeSolver` (`PlanRecovery.kt`: "Never calls the Solver").
If the PO wants the record exact, then in `ARCHITECTURE-SPINE.md:238` and `epics.md:100` replace "only the
`SolveCube` and `PlanRecovery` use cases call it" / "called only by `SolveCube` and `PlanRecovery`" with
"only the `SolveCube` use case calls it; `PlanRecovery` (`RecoveryPlanner`) and `SolveByMethod`
(`MethodSolve`) never call it but use the same Effect-with-timeout shape". This is not required by this
change.

## 5. Impact analysis

- **PRD:** none under any option. NFR-3 stays, and so does FR-15's explicit-error rule.
- **Architecture:** A and B: none (B optionally 4.3). C: AD-21 only. D: AD-8, AD-21 and Deferred. No new
  AD, port or module. The tier policy is an `:app` value passed into the existing constructor parameters
  (`SolveByMethod(deadlineMillis)` and `PlanRecovery(deadlineMillis)` already exist; `SolveCube` and
  `TwoPhaseCubeSolver` would need one).
- **Epics / ACs:** A and B: none. C: none in existing ACs, plus one new story AC (4.1). D: AR-12 and the
  SolveCube AC (4.2).
- **UX:** none. The "timed out" copy (`msg.playback.style.timed-out`, `msg.solve.timed-out`) is unchanged.
  Under C and D a Learner on a low-tier device waits up to 5 s before it appears.
- **Code:** A: none. B: a warm-up call in the method-solver factory or style ViewModel (off-main,
  cancellable, never dispatched). C: `SolveDeadlinePolicy` in `:app`, used in `SolverModule` and
  `AppShell.kt:176`. D: also `SolveCube`, `PlanRecovery` (`LessonScreen.kt:165`) and
  `TwoPhaseCubeSolver`.
- **Tests:** A: none (PR #429 already made the CI tests deadline-independent). B: a JVM test that the
  warm-up result is discarded and cancels cleanly, plus one device check that the first style tap on the
  Lenovo debug build no longer times out. C and D: policy unit tests for each tier and the existing
  `DEADLINE_MILLIS` assertions updated.
- **Benchmark tooling (this branch):** `MethodSolveBenchmarkActivity` (debug and benchmark manifests only)
  is how the evidence was measured. It is kept for re-measurement on the Galaxy devices or after solver
  changes. A `docs/benchmarks.md` section can follow approval.

## 6. PO decisions

1. **Deadline approach?** Options: A no change · A + B warm-up story · C tier SolveByMethod only ·
   D tier all three · E longer deadline on debuggable builds only. **Recommended: A + B.**
2. **If a tier is chosen (C/D): how is the tier detected?** Options: `MEDIA_PERFORMANCE_CLASS` ·
   `isLowRamDevice` · CPU or core heuristic · measured warm-up · build type. **Recommended: measured
   warm-up, or build type for a debug-only fix.** The first two report the minimum device as not low-end.
3. **If a tier is chosen: values?** Options: 3 s / 5 s · 3 s / 6 s · 3 s / 10 s (debug). **Recommended:
   3 s / 5 s** (covers the 4.5 s debug maximum, and nothing in release needs more).
4. **NFR-3?** Options: keep as is · restate per tier. **Recommended: keep as is.** It is a corpus target and
   it is met.
5. **Optional AD-8/AR-12 wording fix (4.4)?** Options: apply now · leave. **Recommended: apply with the
   next spine edit**, or leave it if no other edit happens.
6. **Mid-tier comparison?** No Galaxy was connected. Options: measure the Galaxy S24 FE / Tab S7 FE with
   the same harness before deciding · skip. **Recommended: skip unless C or D is chosen**, because the
   minimum device already clears the deadline.
7. **Harness:** merge `MethodSolveBenchmarkActivity` as benchmark tooling, with a `docs/benchmarks.md`
   section? Options: merge · drop after the decision. **Recommended: merge.**

## 7. Implementation handoff

- **Scope:** Minor. A: nothing to implement. B: one Developer story in Epic 15, about half a day. C or D:
  Developer, after the planning edits in section 4 are applied through `bmad-correct-course` /
  `bmad-architecture` update.
- **Success criteria (B):** on the Lenovo TB128XU debug build, 20 cold first style taps all return a
  method solution within 3 s. The release-equivalent cold first method solve is ≤ 0.5 s median. No
  warm-up result reaches a session. `./gradlew spotlessCheck build` is green.
- **Success criteria (C/D):** the tier decision is logged once per process in debug. Each tier's deadline
  is covered by unit tests. A Lenovo re-measurement with this harness shows 0 timeouts in 20 cold runs per
  method.
