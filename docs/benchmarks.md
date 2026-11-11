# Benchmarks

Two harnesses measure P0 performance on demand (Story 1.10). `./gradlew build` compiles both and
builds their APKs, but never runs them: CI fails if the `build` plan contains a `connected*` task or
`solveCorpusBenchmark`.

## Solve corpus (NFR-3), JVM

```sh
./gradlew :core:solver:solveCorpusBenchmark
./gradlew :core:solver:solveCorpusBenchmark --args="--cases 50 --seed 7 --warmup 0"
```

| Flag | Default | Meaning |
|------|---------|---------|
| `--cases N` | 10000 | Uniform random legal cubie states (N > 0), plus solved and 100 deep 100-turn Scrambles: N + 101 cases. |
| `--seed N` | 20261031 | Corpus seed; the same seed always gives the same corpus. |
| `--warmup N` | 200 | Untimed attempts before the timed run (N ≥ 0). |
| `--time-scale K` | 1 | Calibrated reference-device-equivalent profile (Story 3.8): every measured host time is multiplied by K before median, p95, max and the 3 s budget are computed. 1 = uncalibrated host. |

An unknown flag or invalid value (including a non-positive or non-finite `--time-scale`) prints a
message naming the flag and exits non-zero.

The report gives case count, median, p95 (nearest-rank), max, the share of cases within the 3 s
NFR-3 budget, failure count and rate (a `false` result or an exception), and peak heap (sum of the
JVM heap pools' peaks during the timed run), and names the profile (uncalibrated host, or calibrated
with its factor).

Code: `core/solver/src/benchmarkShared/kotlin/` (workload, harness, statistics; no
`java.lang.management`) plus `core/solver/src/benchmark/kotlin/` (JVM entry point and heap-pool
sampler). Both are in the `benchmark` source set, so they never ship and are outside the Kover gate.
The shared directory is also compiled into the app's `debug` and (since Story 12.2) `benchmark` source sets for the device runner, never into `release`. The workload calls the real `TwoPhaseCubeSolver` through `CubeSolver`. Every returned
Solution is checked by applying its Moves to the exact input through public `CubeState.apply`.
Failures never count as within budget. Uniform cases independently shuffle corner/edge permutations,
repair parity with a fixed edge transposition, and sample independent orientations with the final
twist/flip constrained. Sticker assembly passes through `CubeValidator`. Generating Scrambles are
not retained. The pinned seed reproduces the same corpus.

Tables and move transitions are prepared before timed attempts; 200 warmups precede measurement.
The deadline covers active search (three seconds); search runs on `Dispatchers.Default`, with
cooperative cancellation and deterministic phase-one/phase-two bounds of 12/18 face turns (30 total).
The mandatory JVM test executes the full default corpus, requires zero failures and at least 95%
successful attempts within three seconds, and prints median/p95/memory/failures.

### Story 3.3 host evidence (2026-11-02)

Measured profile: Linux x86_64, Intel Core i7-12800H (20 logical CPUs), OpenJDK 25 local Gradle runtime and JDK 17.0.21 test worker (512 MiB max heap). CI uses ubuntu-latest with Temurin 17. Neither environment has a measured reference-device
equivalence calibration. Host results below are JVM proxy evidence only; the missing calibration
is deferred by the approved 2026-11-02 course correction to mandatory Story 3.8 acceptance.
Story 3.8 must establish a measured reference-device-equivalent JVM profile and rerun the full
corpus with zero failures and at least 95% successful solves within 3 s, reporting median, p95,
memory and failure rate. This blocks production Story 3.7 acceptance and Epic 3/P0.S closure.
Outcome (2026-11-10): the Story 3.8 result (1 device timeout, 1 calibrated failure) is a PO-accepted waiver deferred to #105.
Story 12.2 still confirms the production corpus on physical reference devices. Story 3.8 also
owns production table delivery, cold/warm physical loading and installed size.
See [the approved calibration deferral](../_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-02-search-calibration-deferral.md).
Story 3.8 has since measured the calibrated profile; see the section that follows this table.

Mandatory CI corpus (`./gradlew :core:solver:test`, JDK 17 worker with Kover), seed 20261031:

| Cases | Median | p95 | Max | Successful within 3 s | Failures | Peak heap |
| --- | --- | --- | --- | --- | --- | --- |
| 10,101 | 3.637 ms | 21.348 ms | 229.863 ms | 10,101 (100%) | 0 (0%) | 334.2 MiB |

The corpus test took 69.745 s including generation and warmup. Its result is printed in
`core/solver/build/test-results/test/TEST-dev.cubentia.solver.TwoPhaseCubeSolverTest.xml`.
These figures measure the actual host profile above and do not establish reference-device equivalence.


### Story 3.8 calibrated reference-device-equivalent profile (2026-11-10)

Revisions cited here are attempt-1 revisions where the measurements were taken; see the
[revision mapping](pruning-tables.md#story-38-evidence-2026-11-10) to the cherry-picked commits.

Paired runs of the same corpus code (`benchmarkShared`), seed 20261031, 10,000 uniform cases + solved +
100 deep Scrambles (10,101), warmup 200 untimed attempts. On both sides the table-readiness boundary
is the same: tables are generated before any warmup or timed attempt and are outside all timings.
Every Solution is verified by applying it to the exact input.

**Device side.** `SolveCorpusBenchmarkActivity` (debug build only in Story 3.8; Story 12.2 also compiles it into the release-equivalent `benchmark` build, never `release`) runs
on a dedicated thread:
`adb -s R52T50ELHWK shell am start -n com.exxerpro.cubentia/dev.cubentia.app.solver.SolveCorpusBenchmarkActivity --ei cases 10000 --el seed 20261031 --ei warmup 200`,
output under logcat tag `SolveCorpusBenchmark`. Peak memory there is the largest `Runtime` used heap
sampled after each attempt (`RuntimeHeapSampler`), because `java.lang.management` does not exist on
Android. The host keeps the Story 3.3 heap-pool method. The debug build is debuggable, which slows
ART, so the device times (and k) are conservative relative to a release build.

| Run | Identity | Median | p95 | Max | ≤ 3 s | Failures | Peak memory (device: maximum sampled used heap, sampled after each attempt; host: heap-pool peaks) | Raw |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Device | Galaxy Tab S7 FE 5G `R52T50ELHWK`, Android 12 / API 31, fingerprint `…T738USQU3BVG7:user/release-keys`, 8 CPUs; revision `79e6611`, debug build; USB charging 82 → 80 %, thermal status 1, stay-awake on, MemAvailable 564,872 kB at start; wall 1,055.9 s | 53.795 ms | 338.075 ms | 3,000.916 ms | 10,100 / 10,101 (99.99%) | **1** (0.0099%, timed out) | 27.1 MiB maximum sampled used heap, sampled after each attempt (limit 512 MiB) | [log](evidence/3-8/solve-corpus-tab-s7-fe.log) |
| Host | i7-12800H, 20 logical CPUs, Linux 7.1.5 x86_64, OpenJDK 17.0.21 (JavaExec toolchain; Gradle on 25.0.5); revision `ed490da` | 3.262 ms | 19.962 ms | 223.370 ms | 10,101 (100%) | 0 | 604.6 MiB heap pools | [log](evidence/3-8/solve-corpus-host.log) |

Calibration: k = max(device/host median, device/host p95) = max(53.795 / 3.262 = 16.49,
338.075 / 19.962 = 16.94) = **16.94**. The calibrated profile is this host JVM with `--time-scale 16.94`:

```sh
./gradlew :core:solver:solveCorpusBenchmark --args="--cases 10000 --seed 20261031 --warmup 200 --time-scale 16.94"
```

Calibrated runs on the same host, both with k = 16.94. Since `77251c1` the harness counts a case whose
scaled time exceeds 3 s as a failure, because the solver would have timed out on the device. The
earlier run kept the host's success flag.

| Run | Revision | Cases | Median | p95 | Max | Successful within 3 s | Failures | Peak heap | Raw |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pairing-era run (old harness, superseded) | `ef9c6df` | 10,101 | 45.355 ms | 277.384 ms | 3,068.382 ms | 10,100 (99.99%) | 0 reported (the 3,068 ms case is really a timeout) | 605.4 MiB | [log](evidence/3-8/solve-corpus-calibrated.log) |
| **Calibrated rerun (fixed harness)** | `77251c1` | 10,101 | **46.750 ms** | **291.891 ms** | 3,303.584 ms | **10,100 (99.99%)** | **1 (0.0099%)** | 604.4 MiB | [log](evidence/3-8/solve-corpus-calibrated-rerun.log) |

Result on the calibrated profile: every returned Solution was verified, 99.99% (≥ 95%) of cases
solved within 3 s, and there was **1 failure**: one case scaled to 3,303.6 ms. The rerun's harness
prints the share as "100.0%" because it rounds to one decimal; the count is 10,100/10,101. Its median
and p95 are below the device's (53.795 / 338.075 ms) because host speed varied between the pairing
run and the rerun. The direct device run also had **1 timeout** (3,000.9 ms) in 10,101 cases.

**Zero-failure criterion: PO-accepted waiver (2026-11-10), deferred to v1.1 as #105.** The device
timeout and the calibrated failure are accepted for v1.0. #105 also covers recalibrating k against a
release-equivalent (non-debuggable) device build. k stays at 16.94 here; it was not recalibrated, and
no solver performance work was done in Story 3.8. Story 12.2 still owns final physical corpus
confirmation on a release build.

## 2D net frame timing (NFR-2), Macrobenchmark

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

Needs a connected device or emulator (API 29+; an emulator gives only indicative numbers).
`Net2DFrameTimingBenchmark` opens the Solve tab and taps R U R' U' on the Move key bar while
`FrameTimingMetric` records frame durations, 5 warm iterations with full AOT compilation. Results
land in `macrobenchmark/build/outputs/connected_android_test_additional_output/`.

It measures the `:app` `benchmark` build type: a copy of release (R8, resource shrinking, keep
rules, release feature-flag levels, release `DebugOptions`), debug-signed so it installs, and
`profileable` from shell through `app/src/benchmark/AndroidManifest.xml` only. The AD-13 manifest
and SDK-denylist checks run on it like any variant.

A fresh install opens on the Welcome screen, which has no tabs, so the setup taps *I'm new* when it
is shown and then opens the Solve tab (fixed in Story 1.11; before that the benchmark failed with
`No "Solve" tab` on any fresh install).

To pick one of several connected devices and run only this benchmark (the module's
`LessonProcessDeathTest` runs `pm clear`):

```sh
ANDROID_SERIAL=<serial> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Net2DFrameTimingBenchmark
```

### Reference-device baselines (Story 1.11)

Run on 2026-11-10 at commit `4befc1f` plus the Welcome-screen fix above, `benchmark` build type,
`CompilationMode.Full`, 5 WARM iterations, CPU clocks not locked (`cpuLocked: false`).

**No 2D net baseline could be measured on either device.** The product owner waived this baseline
(Story 1.11 AC1) for v1.0 in [issue #106](https://github.com/hebelmx/RubikXplorer/issues/106), so AC1
is met by that v1.0 waiver; retargeting the benchmark is tracked there. The Solve
tab's free cube is no longer drawn by `Net2DRenderer`: since Epic 5, `FreeCubeViewModel` uses
`Cube3DRenderer` on a `GLSurfaceView`. The face-key taps now animate GL frames, which
`FrameTimingMetric` cannot see, and the Compose UI around the cube draws almost nothing. The perfetto
trace of an S24 FE iteration shows one app `DrawFrames` and one actual/expected frame-timeline slice
for the app, against 22 GL `QueuePresentKHR` on `GLThread`. `Net2DRenderer` is still used by the
Solution playback net (`SolutionPlayback.kt`), which this benchmark does not open. Retargeting the
benchmark at a screen that draws the 2D net is follow-up work.

| Device | OS | Display | Result |
|---|---|---|---|
| Galaxy Tab S7 FE 5G (SM-T738U, minimum) | Android 12, SDK 31 | 60 Hz | **Not measured.** The run failed in `FrameTimingMetric`: `IllegalStateException: Observed no expect/actual slices in trace`, consistent with the app drawing no Compose frame in the measured block. |
| Galaxy S24 FE (SM-S721U1, mid) | Android 17, SDK 37 | 60 Hz (`renderFrameRate 60.0`; panel supports 120 Hz) | **Not a 2D net baseline.** The run passed but measured `frameCount` = 1 in each of the 5 iterations (see raw output below). |

Raw `FrameTimingMetric` output on the S24 FE, copied from
`macrobenchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/SM-S721U1 - 17/dev.cubentia.macrobenchmark-benchmarkData.json`.
Five frames in total (one per iteration), so the percentiles describe five frames, not the 2D net:

| Metric | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| `frameDurationCpuMs` | 8.09 | 8.82 | 8.97 | 9.09 |
| `frameOverrunMs` | -6.96 | -5.68 | -5.58 | -5.51 |

`frameCount`: 1, 1, 1, 1, 1 (min = median = max = 1).

Raw results are kept in [`spikes/story-1-11-device-evidence/`](spikes/story-1-11-device-evidence/):
the S24 FE benchmark JSON and the Tab S7 FE 5G JUnit result with the failure message.

GL frame timing for the reference devices (face turns and View Rotation) of the spike renderer,
measured with the in-app `onDrawFrame` log, is in
[the OQ-3 spike report](spikes/oq-3-3d-rendering.md#reference-device-re-measurement-story-111); the
production renderer is measured by `Cube3DFrameBenchmark` (Story 12.2, below).

## Story 12.2: production 3D renderer frame rate and input latency (NFR-1, NFR-2), Macrobenchmark

```sh
ANDROID_SERIAL=<serial> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Cube3DFrameBenchmark
```

**Method.** The production `Cube3DRenderer` emits three trace sections (`android.os.Trace`, no new
dependency, near-zero cost when not tracing): `Cube3D.drawFrame` around every GL-thread
`onDrawFrame`; the async `Cube3D.turn` from a turn becoming active until it finishes (one cookie per
turn, always closed, also on `finishCurrent`, `snapNow` and dispose); and `Cube3D.turnVisible` at the
first publish that draws the turn partway. `GlFrameMetric` (a `TraceMetric`) reads them with the
target process's main-thread `deliverInputEvent` slices:

- **Face turns** (`faceTurns`): Solve tab, tap R U R' U' on the Move key bar 450 ms apart (a turn is
  200 ms). Window = each `Cube3D.turn`; its frames are the `drawFrame` slices that start inside it.
  Latency = the tap's input UP (the last input slice before the turn) → end of the first `drawFrame`
  that starts after `Cube3D.turnVisible`. The activation frame draws progress 0 (no visible change), so
  this is conservative by one vsync.
- **View Rotation** (`viewRotation`): 4 drags of 600 ms across the stage's top row (off the cube),
  alternating direction, injected at 125 Hz (a MOVE every 8 ms) through `UiAutomation`. Window = first
  to last MOVE of each drag. Latency = first MOVE → end of the first `drawFrame` that starts after it.
- Input times are the slice's `eventTimeNano` mapped from CLOCK_MONOTONIC to the trace clock through
  the trace's clock snapshot (`inputTimeFromSliceStart` counts fallbacks to the slice start: 0 in
  every run below).
- `glFps` = (frames − 1) ÷ (last − first frame start) over the windows of one iteration (pooled);
  `glFpsWorstWindow` is the lowest single window. Interval percentiles are nearest-rank, as in
  `spike3d_stats.py`. The metric throws, naming the slice, when a required slice is missing.
- `benchmark` build type (release code, R8, profileable), `CompilationMode.Full()`, WARM, 10
  iterations, CPU clocks not locked (`cpuLocked: false`). Numbers are physical-device only.

The first S24 FE run used `UiDevice.swipe` for the drags; it delivered a MOVE only every ~33 ms, so
View Rotation read 30.0 FPS because of the input rate, not the renderer. It is kept, superseded, in
[`evidence/12-2/superseded/`](evidence/12-2/superseded/) with the input-slice excerpt; the drag was
then replaced by the 125 Hz injection (`eee28c4`) and both devices were measured with it.

**Cross-check.** S24 FE `faceTurns` iteration 0 was recomputed independently from its raw trace with
[`glframe_crosscheck.py`](evidence/12-2/glframe_crosscheck.py); every value matches the JSON
([record](evidence/12-2/glframe-crosscheck-s24-fe-faceTurns-iter0.txt)).

**Runs (2026-11-11).** Revision `eee28c47b760fa0f8815b66360db4bd5daff9054` (app code identical to
`a4b3ae1`). Both panels at 60 Hz (`refreshRate=60.000004` on the Tab, `renderFrameRate 60.0`,
`peakRefreshRate=60.0` on the S24 FE, whose panel also supports 120 Hz). Thermal status 0 at start and
end on both.

| Device | Conditions |
| --- | --- |
| Galaxy Tab S7 FE 5G `R52T50ELHWK` (SM-T738U, minimum), Android 12 / API 31, `…T738USQU3BVG7:user/release-keys` | USB powered, battery 100 %, 30.6 → 31.7 °C, stay-awake on, 07:05–07:10 |
| Galaxy S24 FE `R5CX83A5J1J` (SM-S721U1, mid), Android 17 / API 37, `…S721U1UEUEEZIF:user/release-keys` | AC powered, battery 89 %, 29.4 → 30.9 °C, stay-awake on, 06:58–07:01 |

Values are the median across the 10 iterations with (min–max); interval and latency percentiles
pool every sample of the 10 iterations; maxima are the highest of any iteration.

| Device | Test | glFps median (min–max) | Worst window | Frames / iteration | Interval p50 / p90 / p99 (ms) | Max interval (ms) | Intervals > 33.3 ms | Input → frame p50 / p90 (ms) | Input → frame max (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Tab S7 FE 5G (min) | face turns | 60.61 (59.67–63.89) | 58.69 | 48–50 | 16.72 / 17.49 / 19.75 | 24.91 | 0 | 39.31 / 43.95 | 47.30 |
| Tab S7 FE 5G (min) | View Rotation | 59.86 (59.78–60.15) | 59.59 | 182–190 | 16.69 / 17.67 / 18.67 | 23.91 | 0 | 10.72 / 12.54 | 22.31 |
| S24 FE (mid) | face turns | 60.32 (60.05–61.16) | 59.51 | 48–52 | 16.66 / 17.11 / 18.09 | 20.58 | 0 | 27.53 / 35.73 | 45.62 |
| S24 FE (mid) | View Rotation | 60.09 (59.96–60.16) | 59.86 | 168–187 | 16.63 / 17.62 / 18.96 | 23.11 | 0 | 12.28 / 15.02 | 20.47 |

Raw: [Tab JSON](evidence/12-2/cube3d-macrobenchmark-tab-s7-fe.json),
[Tab summary](evidence/12-2/cube3d-macrobenchmark-tab-s7-fe.txt),
[S24 JSON](evidence/12-2/cube3d-macrobenchmark-s24-fe.json),
[S24 summary](evidence/12-2/cube3d-macrobenchmark-s24-fe.txt), JUnit results, display logs and
condition dumps in [`evidence/12-2/`](evidence/12-2/). Perfetto traces (5–12 MB each) are not committed.

**Verdicts.** The FPS verdict uses the median `glFps` of the 10 iterations as measured (no rounding).

| Criterion | Tab S7 FE 5G (minimum) | S24 FE (mid) |
| --- | --- | --- |
| Face turns ≥ 30 FPS (minimum) / 60 FPS (mid) | **met**: 60.61 FPS | **met**: 60.32 FPS |
| View Rotation ≥ 30 FPS (minimum) / 60 FPS (mid) | **met**: 59.86 FPS | **met**: 60.09 FPS |
| Face-turn input acknowledged ≤ 100 ms | **met**: max 47.30 ms | **met**: max 45.62 ms |
| View Rotation input → frame ≤ 100 ms | **met**: max 22.31 ms | **met**: max 20.47 ms |

On the mid device some single readings fall just under 60: the lowest View Rotation iteration is
59.96 FPS, and the lowest single face-turn window is 59.51 FPS (View Rotation 59.86). A 60 Hz panel
caps the rate at about 60, and one 200 ms window of 12 frames moves by about ±1 FPS with one frame's
start jitter (the Tab's best face-turn iteration reads 63.89 for the same reason). No interval exceeded
33.3 ms on either device. This is raised as a product-owner question (below), not retuned.

**Against Story 1.11.** The spike renderer's worst-turn p90 was 20.48 ms (Tab) and 23.64 ms (S24),
View Rotation 20.14 / 20.57 ms, from the in-app `onDrawFrame` log
([OQ-3 report](spikes/oq-3-3d-rendering.md#reference-device-re-measurement-story-111)). The production
renderer's pooled p90 is 17.49 / 17.11 ms for face turns and 17.67 / 17.62 ms for View Rotation, with
no interval over 33.3 ms, so it paces at least as well as the spike. The methods differ (trace slices
over 10 Macrobenchmark iterations instead of 40 logged turns), so this is a comparison of pacing, not
of identical statistics.

**Product-owner questions.**
1. Is the mid-device 60 FPS criterion met by the median of the iterations on a 60 Hz panel (60.32 /
   60.09), given single windows and one iteration read 59.51–59.96? Should the S24 FE's 120 Hz mode
   (off in these runs, `peakRefreshRate=60.0`) be part of the criterion?

## Story 12.2: production solve corpus on the minimum device (NFR-3)

The Story 3.8 corpus runner, compiled into the release-equivalent `benchmark` build (R8, not
debuggable: the run logs `build,debuggable=false`), same corpus code, seed and boundary as the JVM
harness and the Story 3.8 device run:

```sh
./gradlew :app:assembleBenchmark
adb -s R52T50ELHWK install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb -s R52T50ELHWK shell am start -n com.exxerpro.cubentia/dev.cubentia.app.solver.SolveCorpusBenchmarkActivity \
  --ei cases 10000 --el seed 20261031 --ei warmup 200
adb -s R52T50ELHWK logcat -s SolveCorpusBenchmark   # until "done"
```

Run on 2026-11-11 06:48–06:53 at revision `a4b3ae170b355b732d5e423be1b29b5bf0fb7ade`: Galaxy Tab
S7 FE 5G `R52T50ELHWK`, Android 12 / API 31, fingerprint `…T738USQU3BVG7:user/release-keys`, 8 CPUs;
USB powered, battery 100 % at start and end, thermal status 0 at start and end, power save off,
stay-awake on, 60 Hz; Free RAM 1,186,547 kB of 3,518,216 kB at start.
Raw: [log](evidence/12-2/solve-corpus-benchmark-build-tab-s7-fe.log),
[conditions](evidence/12-2/corpus-conditions-tab-s7-fe.txt).

| Run | Build | Cases | Median | p95 | Max | Within 3 s | Failures (rate) | Peak memory (max sampled used heap) | Timed run wall | Table preparation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Story 12.2, device** | `benchmark` (release-equivalent) | 10,101 | **17.012 ms** | **101.730 ms** | 1,074.199 ms | **10,101 / 10,101 (100%)** | **0 (0%)** | **28.9 MiB** (30,352,624 B; limit 512 MiB) | 326.8 s | 3.13 s |
| Story 3.8, device ([log](evidence/3-8/solve-corpus-tab-s7-fe.log)) | `debug` (debuggable) | 10,101 | 53.795 ms | 338.075 ms | 3,000.916 ms | 10,100 / 10,101 (99.99%) | 1 (0.0099%, timed out) | 27.1 MiB | 1,055.9 s | — |
| Story 3.8, calibrated host k = 16.94 ([log](evidence/3-8/solve-corpus-calibrated-rerun.log)) | host JVM × 16.94 | 10,101 | 46.750 ms | 291.891 ms | 3,303.584 ms | 10,100 / 10,101 (99.99%) | 1 (0.0099%) | 604.4 MiB heap pools | — | — |

"Table preparation" is the runner's `SolverWorkload.preparedTables` before the timed run (tables
generated in-process, as in Story 3.8, not the app's bundled-asset path); it is outside every timing.
The app's own ~8 s per-process preparation (#104) is a separate path and was not re-measured here.

**Verdict: met.** 100% of the 10,101 cases (≥ 95%) solved within 3 s on the minimum device, with
0 failures. Median 17.012 ms, p95 101.730 ms, peak 28.9 MiB. On this build the release-equivalent
device run is about 3.2× faster than the debuggable Story 3.8 run (median 53.795 → 17.012 ms, p95
338.075 → 101.730 ms), so the debug-derived k = 16.94 is conservative, as Story 3.8 expected. The
recalibration of k against this build stays with #105, and the accepted waivers (#104 preparation,
#105 zero-failure and recalibration) are unchanged; this run had no failure to waive.

## Story 12.2: compatibility and rotation (NFR-13, AD-17), Key Flow suite

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
tools/run-keyflow-rotation.sh <serial> [label] [method ...]   # results in docs/evidence/12-2/keyflow/<label>/
```

`KeyFlowRotationTest` (`app/src/androidTest/.../compat/`) runs in-process on the real `MainActivity`
(debug build; this is a layout and state check, not a performance measurement). One method per v1.0
Key Flow of EXPERIENCE.md; the camera steps (Flows 4, 5, 9 steps 1–3) are v1.1. Each method starts
from a cleared install (`pm clear` by the script) and drives the flow through the UI; preconditions
are reached through the UI too (Flow 2 passes the skip-ahead check first, Flow 7 completes Stage 1).
Skip checks and practice Scrambles are solved by tapping the Move keys of a Solution computed
in-process by the app's production solver. At each surface the test rotates portrait → landscape →
portrait with `Activity.requestedOrientation` (never device settings; restored to
`SCREEN_ORIENTATION_UNSPECIFIED`) and checks, after each rotation:

- `SessionHost.state.value` is equal before and after (read from the activity's injected
  `@Singleton`; a test-source Hilt `@EntryPoint` is not aggregated into the app's component without
  `HiltTestApplication`, so the injected field is used instead — the same instance);
- the cube's spoken summary/state and the step counter are unchanged;
- the layout follows the window class from the window width in dp (`WindowWidthClass.of`): `nav:bar`
  iff COMPACT, else `nav:rail`; Lesson panel below the stage (COMPACT) or right of it, ≤ 560 dp at
  EXPANDED; Library 2/3/4 columns; Manual Cube Editor one face at a time when COMPACT and the full net
  where the width allows it.

A failed check is collected and the flow continues, so one broken surface does not hide the rest; the
method then fails listing every problem. The rotation settings are read before and after each run as
a check (unchanged on every target: Tab S7 FE `accelerometer_rotation=1 user_rotation=3`, S24 FE
`1 / 0`).

The flows: **1** Welcome → Stage 1 Lessons on the virtual cube → Stage 2 in Physical-cube Mode
(Confirm your cube, Done, *My cube doesn't match* ladder) → the rest of the Course → the finale in
Physical-cube Mode, *Yes, it's solved*, first-solve milestone. **2** skip-ahead check → *Faster last
layer* → 2-look OLL Lesson → *Add to Learning* → Library filtered by *Learning* → LL practice.
**3** Solve → *Enter colors* (an R U scramble) → Accept → playback followed on my cube → learn offer.
**6** gestures and View Rotation on the free cube → Library → OLL detail, scrubbed → Enter colors and
solve. **7** Stage 2 practice (hint once, practice until the milestone) → You trend. **8** *I can
already solve it* → skip-ahead check solved → Stages Skipped, *Faster last layer* unlocked.
**9 step 4** Settings → *Reset progress* ends the live Lesson and clears *Continue*.

Revision `655833ef816e8f619a911b697cec7800eeb3e111`, 2026-11-11. AVDs ran one at a time, headless
(`-no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect`; with the default host GPU the
API 35 emulator's QEMU crashed twice mid-run, so every AVD was run with SwiftShader), and were shut
down afterwards. Cells give surfaces passed / surfaces
checked; window classes are those seen in each orientation.

| Target | API | Form factor | Portrait | Landscape | Flow 1 | Flow 2 | Flow 3 | Flow 6 | Flow 7 | Flow 8 | Flow 9 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| tab-s7-fe-api31 (SM-T738U) | 31 | tablet | MEDIUM (753 dp) | EXPANDED (1205 dp) | pass (11/11) | **fail** (5/6; skipCheck) | pass (6/6) | pass (8/8) | **fail** (2/5; practiceDealt, practiceHint, stageMasteredMilestone) | **fail** (1/2; skipCheck) | pass (5/5) |
| s24-fe-api37 (SM-S721U1) | 37 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | **fail** (5/6; skipCheck) | pass (6/6) | pass (8/8) | **fail** (2/5; practiceDealt, practiceHint, stageMasteredMilestone) | **fail** (1/2; skipCheck) | pass (5/5) |
| avd-spike3d_api29 (Android SDK built for x86_64, emulator) | 29 | phone | COMPACT (393 dp) | MEDIUM (781 dp) | pass (11/11) | **fail** (6/7; skipCheck) | pass (6/6) | pass (9/9) | **fail** (3/6; practiceDealt, practiceHint, stageMasteredMilestone) | **fail** (2/3; skipCheck) | pass (6/6) |
| avd-GalaxyTabS7FE_API34 (sdk_gphone64_x86_64, emulator) | 34 | tablet | COMPACT (555 dp) | EXPANDED (1280 dp) | pass (11/11) | **fail** (2/3; skipCheck), then **incomplete**: the harness timed out waiting for the wide Move `f` to commit in the 2-look Lesson (not a rotation result) | pass (6/6) | pass (8/8) | **fail** (2/5; practiceDealt, practiceHint, stageMasteredMilestone) | **fail** (1/2; skipCheck) | pass (5/5) |
| avd-GalaxyS24FE_API35 (sdk_gphone64_x86_64, emulator) | 35 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | **fail** (5/6; skipCheck) | pass (6/6) | pass (8/8) | **fail** (2/5; practiceDealt, practiceHint, stageMasteredMilestone) | **fail** (1/2; skipCheck) | pass (5/5) |
| avd-GalaxyS24FE_API36 (sdk_gphone64_x86_64, emulator) | 36 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | **fail** (5/6; skipCheck) | pass (6/6) | pass (8/8) | **fail** (2/5; practiceDealt, practiceHint, stageMasteredMilestone) | **fail** (1/2; skipCheck) | pass (5/5) |

Targets: physical Galaxy Tab S7 FE 5G `R52T50ELHWK` (Android 12) and Galaxy S24 FE `R5CX83A5J1J`
(Android 17); AVDs `spike3d_api29` (Pixel 4 profile, Android 10), `GalaxyTabS7FE_API34` (2560×1600
@ 320 dpi, Android 14), `GalaxyS24FE_API35`, `GalaxyS24FE_API36` (1080×2340 @ 450 dpi). The Tab AVD's
portrait width (555 dp) is COMPACT where the physical Tab (753 dp) is MEDIUM, so between them the
tablet covers all three window classes. Raw results per target: `instrument`, `logcat` (one
`layout,` line per surface and orientation with the width and checks, one `surface,` line per
surface) and `device.txt` in [`evidence/12-2/keyflow/`](evidence/12-2/keyflow/).

**Result: not met.** Layout followed the window class on every surface of every target, and Flows 1,
3, 6 and 9 pass everywhere. Flow 2 on the Tab AVD stopped after its third surface on a harness
timeout (a wide Move key tap that did not commit), so its later surfaces were not checked there; they
passed on the five other targets. On every target, rotation **changes the session** on three surfaces:

- **Skip-ahead check** (Flows 2 and 8): each rotation deals a new Scramble (`lesson.entryState`,
  `history`, `revision` change; the cube shown changes).
- **Stage practice** (Flow 7): each rotation deals a new practice Scramble, also after the hint, and
  rotating on the *First cross mastered* milestone replaces it with a fresh practice (the milestone is
  lost; the mastery itself was already recorded).

Cause (read from the code, not changed here): in `AppShell.kt` `skipCheckEntry` and `practiceEntry`
are built with `remember { stagePractice.courseSkipCheckLesson() / skipCheckLesson(id) /
stagePractice(stageId) }`, which deals a random Scramble again when the activity is recreated;
`LessonScreen` then sees a different `entryState` and dispatches a new `PracticeStarted`. The LL
practice avoids this by keeping its Scramble in `rememberSaveable` (`twoLookScramble`). Fixing it is
product work outside this story's scope; it is listed as a product-owner question.

Also found and fixed in the test setup: Compose UI test pulls espresso-core 3.5.0, whose idling
reflects on `InputManager.getInstance`, removed in Android 17, so every test failed on the S24 FE until
`androidTestImplementation` pinned espresso-core 3.7.0 (test-only).

**Product-owner questions.**
2. Rotation re-deals the skip-ahead check and Stage practice (above). Fix before release (a story
   that saves the dealt Scramble like the LL practice does), or accept for v1.0?
3. API 30 is not in the target set (AVDs cover 29, 34, 35, 36; physical 31 and 37), as the story
   lists. Is that acceptable for "API 29 through targetSdk 36"?

## Out of scope

- 2D net reference-device baselines: attempted in Story 1.11 and not measured (see above); waived
  for v1.0 by the product owner in [issue #106](https://github.com/hebelmx/RubikXplorer/issues/106).
  `Net2DFrameTimingBenchmark` is kept unchanged for that retargeting.
- Solver performance work and a release-equivalent recalibration of k: v1.1, #105. The ~8 s
  per-process preparation: v1.1, #104. Story 12.2 measured both as they are and did not tune them.
- Camera, scan and Quick Check flows (Key Flows 4, 5 and 9 steps 1–3): v1.1.
