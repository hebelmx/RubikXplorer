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
33.3 ms on either device. This was raised as a product-owner question, not retuned; see the decision below.

**Against Story 1.11.** The spike renderer's worst-turn p90 was 20.48 ms (Tab) and 23.64 ms (S24),
View Rotation 20.14 / 20.57 ms, from the in-app `onDrawFrame` log
([OQ-3 report](spikes/oq-3-3d-rendering.md#reference-device-re-measurement-story-111)). The production
renderer's pooled p90 is 17.49 / 17.11 ms for face turns and 17.67 / 17.62 ms for View Rotation, with
no interval over 33.3 ms, so it paces at least as well as the spike. The methods differ (trace slices
over 10 Macrobenchmark iterations instead of 40 logged turns), so this is a comparison of pacing, not
of identical statistics.

**PO decision 2026-11-11.** The question was whether the mid-device 60 FPS criterion is met by the
median of the iterations on a 60 Hz panel (60.32 / 60.09), given single windows and one iteration read
59.51–59.96, and whether the S24 FE's 120 Hz mode (off in these runs, `peakRefreshRate=60.0`) should
be part of the criterion. Decision: the S24 FE's 60 FPS on the median counts as **AC1 met**.

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

Two revisions, 2026-11-11. The physical Tab S7 FE, the physical S24 FE and the Tab AVD ran the full
suite at `dd56e34b2e0573707b0e6566a94df3f51c06e78a`, after the Move-key fix below changed the stage.
The other AVDs ran at `0ec0d6baa77328a108e7cd92cca7778abf3b9656`, the rotation fix below. AVDs ran one
at a time, headless (`-no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect`). In the first
run the API 35 emulator's QEMU crashed twice with the default host GPU, so every AVD runs with
SwiftShader. Each AVD was shut down afterwards. Cells give surfaces passed / surfaces checked; window
classes are those seen in each orientation.

| Target | Revision | API | Form factor | Portrait | Landscape | Flow 1 | Flow 2 | Flow 3 | Flow 6 | Flow 7 | Flow 8 | Flow 9 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| tab-s7-fe-api31 (SM-T738U, physical) | `dd56e34` | 31 | tablet | MEDIUM (753 dp) | EXPANDED (1205 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| s24-fe-api37 (SM-S721U1, physical) | `dd56e34` | 37 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| avd-GalaxyTabS7FE_API34 (sdk_gphone64_x86_64, emulator) | `dd56e34` | 34 | tablet | COMPACT (555 dp) | EXPANDED (1280 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| avd-spike3d_api29 (Android SDK built for x86_64, emulator) | `0ec0d6b` | 29 | phone | COMPACT (393 dp) | MEDIUM (781 dp) | pass (11/11) | pass (8/8) | pass (6/6) | pass (9/9) | pass (6/6) | pass (3/3) | pass (6/6) |
| avd-GalaxyS24FE_API35 (sdk_gphone64_x86_64, emulator) | `0ec0d6b` | 35 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| avd-GalaxyS24FE_API36 (sdk_gphone64_x86_64, emulator) | `0ec0d6b` | 36 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| avd-GalaxyS24FE_API37 (sdk_gphone64_x86_64, emulator) | `0ec0d6b` | 37 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |

Targets: physical Galaxy Tab S7 FE 5G `R52T50ELHWK` (Android 12) and Galaxy S24 FE `R5CX83A5J1J`
(Android 17); AVDs `spike3d_api29` (Pixel 4 profile, Android 10), `GalaxyTabS7FE_API34` (2560×1600
@ 320 dpi, Android 14), `GalaxyS24FE_API35`, `GalaxyS24FE_API36`, `GalaxyS24FE_API37` (1080×2340 @
450 dpi). The S24 FE was away for the `0ec0d6b` rerun, so API 37 was first rerun on the
`GalaxyS24FE_API37` AVD; the S24 FE then ran the full suite at `dd56e34`. The Tab AVD's portrait window
(555 dp) is COMPACT where the physical Tab (753 dp) is MEDIUM, so between them the tablet covers all
three window classes. Raw results per target are in [`evidence/12-2/keyflow/`](evidence/12-2/keyflow/):
`instrument`, `logcat` and `device.txt`. Each `logcat` has one `layout,` line per surface and
orientation (with the width and checks) and one `surface,` line per surface. The rotation settings
were unchanged on every target (physical Tab `accelerometer_rotation=1 user_rotation=3`, S24 FE
`1 / 0`). The `0ec0d6b` runs of the physical Tab and the Tab AVD are kept in
[`evidence/12-2/superseded/keyflow-0ec0d6b/`](evidence/12-2/superseded/keyflow-0ec0d6b/).

**Result: met.** All 49 target × flow runs pass. Rotation changes no session state on any surface, and
layout follows the window class on every surface of every target.

**Finding, fixed: wide and slice Move keys hidden in a short COMPACT window.** At `0ec0d6b`, Flow 2 on
the Tab AVD stopped after three passing surfaces, as it did in the first run: a tap on the wide Move key
`f` did not commit. The AVD's forced-portrait window is letterboxed on its landscape display at
**555 × 716 dp**. There the COMPACT stage leaves the Move key area about 105 dp, about two key rows.
The area already scrolls (Story 12.1: `CubeAndKeys` keeps the cube at 96 dp and scrolls the keys).
Tapping *more* added the wide and slice rows below that fold, with only a 9 dp sliver of the wide row
showing:

- the stage ended at 345 dp, and `moveKey:f` spanned 336–384 dp;
- the uiautomator dump showed neither row;
- nothing told the Learner that the rows had appeared.

Two problems combined:

1. **App:** the new rows opened out of sight.
2. **Harness:** `performScrollTo` scrolls only the nearest scroll parent, which is the key row's
   horizontal scroll. It never moved the vertical key area, so the harness tapped a clipped key, and the
   tap landed on the panel.

An earlier note in this section called the keys unreachable. That was wrong: a probe without a scroll
missed them, and a drag on the key area does reach them and commit.

The fix (`dd56e34`) has two parts:

- **App:** `MoveKeyBar` scrolls the wide and slice rows into view (`BringIntoViewRequester`) when the
  Learner opens *more*. Every key keeps its 48 dp target, and the layout is unchanged where the rows
  already fit.
- **Harness:** before a tap, the harness drags the key area with real touch input until the key is
  fully inside it.

Regression tests: in `AppShellTest`, at 555 × 716 dp and at 360 × 640 dp, *more*, then `f`, `M`, `S2`
and `U`. Each key is reached by a drag, fully shown, at least 48 dp, and commits its Move. At 555 dp,
`f` must show with no drag; that check fails without the fix. Diagnosis evidence:
[`flow2-stall-finding.txt`](evidence/12-2/superseded/keyflow-0ec0d6b/avd-GalaxyTabS7FE_API34/flow2-stall-finding.txt),
[screenshot](evidence/12-2/superseded/keyflow-0ec0d6b/avd-GalaxyTabS7FE_API34/flow2-stall-screenshot.png),
[uiautomator dump](evidence/12-2/superseded/keyflow-0ec0d6b/avd-GalaxyTabS7FE_API34/flow2-stall-uiautomator.xml). The screenshot also shows the system's letterbox
education dialog, which was not involved.

**First run, superseded** (revision `655833ef816e8f619a911b697cec7800eeb3e111`, raw files in
[`evidence/12-2/superseded/keyflow-655833e/`](evidence/12-2/superseded/keyflow-655833e/), with the
physical S24 FE `s24-fe-api37`). Flows 1, 3, 6 and 9 passed everywhere, but on every target rotation
**changed the session** on three surfaces, so Flows 2, 7 and 8 failed:

- **Skip-ahead check** (Flows 2 and 8): each rotation dealt a new Scramble (`lesson.entryState`,
  `history`, `revision` changed; the cube shown changed).
- **Stage practice** (Flow 7): each rotation dealt a new practice Scramble, also after the hint, and
  rotating on the *First cross mastered* milestone replaced it with a fresh practice (the milestone was
  lost; the mastery itself was already recorded).

**Cause and fix.** In `AppShell.kt`, `skipCheckEntry` and `practiceEntry` were built with
`remember { stagePractice.courseSkipCheckLesson() / skipCheckLesson(id) / stagePractice(stageId) }`.
A recreated activity dealt a random Scramble again, so `LessonScreen` saw a different `entryState` and
dispatched a new `PracticeStarted`. That new session also cleared `objectiveMet`, which hid the
milestone. The fix (`0ec0d6b`) saves the dealt Scramble in `rememberSaveable`, keyed by the open
practice or check and its epoch, as the LL practice already does with `twoLookScramble`. Recreation
rebuilds the same Lesson, so the session, and with it the milestone, survives. A new practice or check
bumps the epoch and still deals a new Scramble. Regression tests: `AppShellTest` *Stage practice keeps
its scramble and attempt across recreation* and *a Stage skip-ahead check keeps its scramble and
attempt across recreation* (`StateRestorationTester`). Both fail without the fix.

Also found and fixed in the test setup in the first run: Compose UI test pulls espresso-core 3.5.0,
whose idling reflects on `InputManager.getInstance`, removed in Android 17, so every test failed on the
S24 FE until `androidTestImplementation` pinned espresso-core 3.7.0 (test-only).

**PO decision 2026-11-11.**
2. Rotation re-dealing the skip-ahead check and Stage practice: **fix now, inside Story 12.2** (done
   above).
3. API 30 is not in the target set (AVDs cover 29, 34, 35, 36, 37; physical 31 and 37): the API 30 gap is
   **accepted** for "API 29 through targetSdk 36".

## Additional device: Lenovo Tab M10 Plus 3rd Gen (TB128XU), 2026-11-11

**Not a reference device.** The reference devices stay the Galaxy Tab S7 FE 5G (minimum) and the
Galaxy S24 FE (mid). These runs repeat their methods on a third, lower-end tablet so the product owner
can decide, through `bmad-correct-course`, whether to add it. No criterion, waiver or verdict above
changes because of these numbers. The pass column applies the thresholds used for the reference
devices. Where the minimum and the mid thresholds differ, it applies the minimum one and states the
mid one as well.

| Identity | Value |
| --- | --- |
| Device | Lenovo Tab M10 Plus 3rd Gen, model TB128XU, serial `HA1S28MM`, Snapdragon 680 (SM6225, `bengal`), 8 CPUs, MemTotal 3,756,260 kB, arm64-v8a |
| Android | 13 (API 33), fingerprint `Lenovo/TB128XU_MXTC/TB128XU:13/TKQ1.221220.001/S101018_250611_ROW:user/release-keys` |
| Display | 1200 × 2000 px at 240 dpi (800 dp portrait, 1333 dp landscape), 60 Hz only (`refreshRate=60.000004`, one display mode) |
| Conditions | USB powered, battery 95–100 %, battery 27.1–31.0 °C, thermal status 0 throughout, power save off, stay-awake on (`stay_on_while_plugged_in` 0 → 15 for the run, restored to 0) |
| Revision | `95d708f67e4395beae0b98a4152a6af5ee472d22` for every run (app code identical to `2fda65a`, after #112) |
| Java heap limit | 256 MiB (`javaHeapMaxBytes=268435456`); the Tab S7 FE has 512 MiB |

Raw evidence: [`evidence/lenovo-tb128xu/`](evidence/lenovo-tb128xu/), device settings before and
after in [`device-settings-lenovo-tb128xu.txt`](evidence/lenovo-tb128xu/device-settings-lenovo-tb128xu.txt).

### Story 3.8 methods: table delivery, preparation and StrictMode

Same commands as [pruning-tables.md](pruning-tables.md#reproduce): five cold runs and five warm runs
of the 3.2 harness, five `--ez bundledOnly true` runs, debug build. Release-equivalent preparation was
timed coarsely as in Story 3.8: tap Accept, then poll `uiautomator` until the Solution note appears,
three fresh processes. A dump takes 2–3 s on this tablet, so these are upper bounds.

| Measurement | Lenovo TB128XU min / median / max | Tab S7 FE 5G min / median / max |
| --- | --- | --- |
| Generation (move tables + 4 BFS projections), debug, n = 5 | 14,120.8 / 14,297.1 / 14,373.9 ms | 9,724.7 / 9,845.8 / 10,258.7 ms |
| Cache write (atomic, fsync), n = 5 | 30.1 / 53.1 / 57.4 ms | 159.1 / 174.3 / 242.3 ms |
| **G** = generation + write, n = 5 | 14,167.8 / **14,350.2** / 14,407.8 ms | 9,883.8 / **10,014.0** / 10,432.9 ms |
| Warm private-file read + SHA-256, new process, n = 5 | 9.2 / 10.7 / 14.7 ms | 8.0 / 8.9 / 10.9 ms |
| Bundled asset read + inflate + SHA-256, n = 5 | 32.1 / 33.9 / 34.7 ms | 23.7 / 24.4 / 26.1 ms |
| **Move-table build per process** (`moveTableNanos`), bundled, n = 5 | 12,517.6 / **12,555.3** / 12,654.6 ms | 8,214.4 / **8,259.2** / 8,542.5 ms |
| Total bundled preparation per process, debug, n = 5 | 12,551.5 / 12,587.4 / 12,689.2 ms | 8,238.9 / 8,283.0 / 8,566.8 ms |
| Accept → Solution note, `benchmark` build, fresh process (coarse) | 10.46, 10.46, 10.49 s | 7.61, 7.78, 7.63 s |
| Corpus runner `prepareNanos`, `benchmark` build (12.2 run below) | 5.18 s | 3.13 s |

- Every cold run reported `first,source=GENERATED`, every warm run `first,source=CACHE` and every
  bundled run `source=BUNDLED`; payload 4,031,685 B, cache file 4,031,725 B. No `failed` or
  `measurement-incomplete` line. The warm runs also rebuilt the move tables (12,540.5–12,626.3 ms).
- Memory: MemAvailable before the runs 982,912–1,161,460 kB; PSS after bundled preparation
  92.8–107.0 MB; Java heap in use after preparation 10.1–23.7 MiB of 256 MiB.
- **#104 data.** The per-process move-table build is about 12.6 s on the debug build here, against
  8.3 s on the Tab S7 FE (×1.52). The release-equivalent Accept → Solution upper bound is about 10.5 s
  (Tab 7.6–7.8 s). The #112 copy ("This can take a few seconds the first time after the app starts.")
  was shown on every fresh-process Accept. A second solve in the same process went straight to
  playback.
- Delivery rule (first-run needs G ≤ 5 s): G = 14.35 s also fails here, so bundled would be selected
  on this device too.

Raw: [`pruning-tables-lenovo-tb128xu.log`](evidence/lenovo-tb128xu/pruning-tables-lenovo-tb128xu.log),
[`pruning-tables-bundled-lenovo-tb128xu.log`](evidence/lenovo-tb128xu/pruning-tables-bundled-lenovo-tb128xu.log),
[`production-release-timing-lenovo-tb128xu.log`](evidence/lenovo-tb128xu/production-release-timing-lenovo-tb128xu.log).

**Production StrictMode and Lesson independence (debug build, #107 re-check on a non-Samsung OEM).**
Same scripted steps as [`production-strictmode-tab-s7-fe.log`](evidence/3-8/production-strictmode-tab-s7-fe.log),
from a fresh install (uninstall + install): a Lesson before any solve (U, "Pattern reached", "Objective
met"); Enter colors, the cube after U, Accept, preparing line with Cancel; during preparation, Learn,
reopen the Lesson and turn U (the Lesson responds; leaving Solve cancels the solve and the editor keeps
the colors); retry Accept, `U'` playback; a second Accept in the same process goes straight to
playback; a new process (force-stop) shows the preparing line again, then `U'`; `files/` holds only
`profileInstalled` (no fallback table file).
Raw: [`production-strictmode-lenovo-tb128xu.log`](evidence/lenovo-tb128xu/production-strictmode-lenovo-tb128xu.log).

| StrictMode | Lenovo TB128XU (Android 13) | Tab S7 FE 5G (Android 12) |
| --- | --- | --- |
| Violations, first process after install | **0** | 6 (4 Samsung FlipFont + 2 `getProfileSizeOfApp`) |
| Violations, second process | **0** | 4 (Samsung FlipFont) |
| App-originated / table loading or solving | 0 / 0 | 0 / 0 |
| Harness `mainThreadDiskViolations` (15 runs) | 0 on all 15 | 0 on 9, 2 on cold run 1 |

The StrictMode criterion ("no main-thread disk access is reported") is **met with no waiver** on this
device. That fits #107: the waived violations come from Samsung's platform (FlipFont in
`Application.onCreate`, `ActivityThread.getProfileSizeOfApp`), not from app code.

### Story 12.2 AC1: frame rate and input latency (Macrobenchmark)

Same command, `Cube3DFrameBenchmark`, `benchmark` build, `CompilationMode.Full()`, WARM, 10
iterations, run 20:05–20:10 in landscape (`user_rotation=1`, as the Tab S7 FE ran in landscape), 60 Hz,
battery 29.8 → 30.4 °C, thermal status 0 at start and end. Values as in the tables above: median of
the 10 iterations (min–max), pooled linear-interpolated percentiles, maxima over all iterations.

| Device | Test | glFps median (min–max) | Worst window | Frames / iteration | Interval p50 / p90 / p99 (ms) | Max interval (ms) | Intervals > 33.3 ms | Input → frame p50 / p90 (ms) | Input → frame max (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Lenovo TB128XU** | face turns | **61.96** (60.15–63.16) | 58.87 | 48–51 | 16.64 / 18.35 / 23.74 | 28.95 | 0 | 41.30 / 53.39 | **57.52** |
| **Lenovo TB128XU** | View Rotation | **59.69** (59.00–59.95) | 57.87 | 195–213 | 16.70 / 19.66 / 27.68 | 40.20 | **5** (of 1,990) | 14.08 / 20.76 | **30.94** |
| Tab S7 FE 5G (min) | face turns | 60.61 (59.67–63.89) | 58.69 | 48–50 | 16.72 / 17.49 / 19.75 | 24.91 | 0 | 39.31 / 43.95 | 47.30 |
| Tab S7 FE 5G (min) | View Rotation | 59.86 (59.78–60.15) | 59.59 | 182–190 | 16.69 / 17.67 / 18.67 | 23.91 | 0 | 10.72 / 12.54 | 22.31 |
| S24 FE (mid) | face turns | 60.32 (60.05–61.16) | 59.51 | 48–52 | 16.66 / 17.11 / 18.09 | 20.58 | 0 | 27.53 / 35.73 | 45.62 |
| S24 FE (mid) | View Rotation | 60.09 (59.96–60.16) | 59.86 | 168–187 | 16.63 / 17.62 / 18.96 | 23.11 | 0 | 12.28 / 15.02 | 20.47 |

`inputTimeFromSliceStart` was 0 in every iteration. Macrobenchmark reported `cpuLocked: true` here
(false on both reference devices). Nothing locked the clocks: after the run `scaling_min_freq` was
below `scaling_max_freq` on every cluster (691.2 MHz / 1.9 GHz and 1.056 / 2.4 GHz). Treat the flag as
the library's reading of this kernel, not as a different condition. Raw:
[JSON](evidence/lenovo-tb128xu/cube3d-macrobenchmark-lenovo-tb128xu.json),
[summary](evidence/lenovo-tb128xu/cube3d-macrobenchmark-lenovo-tb128xu.txt),
[JUnit](evidence/lenovo-tb128xu/cube3d-macrobenchmark-lenovo-tb128xu-junit.xml),
[display log](evidence/lenovo-tb128xu/cube3d-macrobenchmark-lenovo-tb128xu-display.log),
[conditions](evidence/lenovo-tb128xu/macrobenchmark-conditions-lenovo-tb128xu.txt). Perfetto traces are
not committed.

The renderer holds the panel rate, but with less headroom than on the reference devices. View Rotation
p90 is 19.7 ms against 17.6–17.7 ms, and 5 of 1,990 intervals exceed 33.3 ms (max 40.2 ms), where both
reference devices had none. View Rotation's median is 59.69 FPS: at or above the minimum threshold, but
below 60 (the PO's 60-on-the-median reading for the mid device). This matters only if the tablet were
classed as mid-tier.

### Story 12.2 AC3: rotation and compatibility (Key Flow suite)

`tools/run-keyflow-rotation.sh HA1S28MM lenovo-tb128xu-api33`, debug build, the same seven methods.
Two findings came before the valid run:

1. **Script line endings (local checkout).** The working copy of `tools/run-keyflow-rotation.sh` has
   CRLF endings (`core.autocrlf=true` here, and `.gitattributes` forces LF only for `gradlew`). The
   index copy is LF. `env: 'bash\r'` failed, so the script ran unchanged through
   `bash -c "$(tr -d '\r' < tools/run-keyflow-rotation.sh)" tools/run-keyflow-rotation.sh …`.
2. **This tablet ignores app orientation requests.** Android 13 on this large-screen device ships
   `wm get-ignore-orientation-request` = `true`. The harness rotates only through
   `Activity.requestedOrientation`, and that never changed the configuration. All 7 methods failed in
   setup ("timed out waiting for rotation to portrait") before any check ran. This is a harness
   limitation on this device, not an app result. Raw:
   [`keyflow/as-is-ignore-orientation-request-true/`](evidence/lenovo-tb128xu/keyflow/as-is-ignore-orientation-request-true/).
   A Learner on this tablet rotates with the system (auto-rotate or the rotation button), which the
   harness does not model.

The suite was then rerun unchanged with `wm set-ignore-orientation-request false` (a window-manager
override, not a persisted setting, restored to `true` afterwards). Rotation settings were unchanged
before and after (`accelerometer_rotation=0`, `user_rotation=1`). Raw:
[`keyflow/ignore-orientation-request-false/`](evidence/lenovo-tb128xu/keyflow/ignore-orientation-request-false/).

| Target | Revision | API | Form factor | Portrait | Landscape | Flow 1 | Flow 2 | Flow 3 | Flow 6 | Flow 7 | Flow 8 | Flow 9 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| lenovo-tb128xu-api33 (TB128XU, physical, orientation requests honored) | `95d708f` | 33 | tablet | MEDIUM (800 dp) | EXPANDED (1333 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| tab-s7-fe-api31 (reference) | `dd56e34` | 31 | tablet | MEDIUM (753 dp) | EXPANDED (1205 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |
| s24-fe-api37 (reference) | `dd56e34` | 37 | phone | COMPACT (384 dp) | MEDIUM (832 dp) | pass (11/11) | pass (6/6) | pass (6/6) | pass (8/8) | pass (5/5) | pass (2/2) | pass (5/5) |

All 7 flows pass and all 43 surfaces pass. Rotation changes no session state, and the layout follows
the window class. The device adds API 33, which no earlier target covered. With orientation requests
ignored, no letterbox was observed, because the configuration never changed. The letterbox case
stays the one recorded for the Tab AVD above.

### Story 12.2 AC2: solve corpus (NFR-3)

Same runner, `benchmark` build (`build,debuggable=false`), `--ei cases 10000 --el seed 20261031 --ei
warmup 200`, 3 s budget, run 20:27–20:36, USB powered, battery 100 %, 30.6 → 31.0 °C, thermal status
0 at start and end, power save off, stay-awake on, 60 Hz, Free RAM 1,484,879 kB of 3,756,260 kB at
start. Raw: [log](evidence/lenovo-tb128xu/solve-corpus-benchmark-build-lenovo-tb128xu.log),
[conditions](evidence/lenovo-tb128xu/corpus-conditions-lenovo-tb128xu.txt).

| Device | Build | Cases | Median | p95 | Max | Within 3 s | Failures | Peak memory (max sampled used heap) | Timed run wall | Table preparation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Lenovo TB128XU** | `benchmark` | 10,101 | **26.999 ms** | **138.710 ms** | 1,561.215 ms | **10,101 / 10,101 (100%)** | **0** | **32.7 MiB** (34,287,936 B; limit 256 MiB) | 476.5 s | 5.18 s |
| Tab S7 FE 5G (12.2) | `benchmark` | 10,101 | 17.012 ms | 101.730 ms | 1,074.199 ms | 10,101 / 10,101 (100%) | 0 | 28.9 MiB (limit 512 MiB) | 326.8 s | 3.13 s |

The S24 FE was not run on the corpus in Story 12.2, so it has no column. Compared with the minimum
device: median ×1.59, p95 ×1.36, max ×1.45, wall ×1.46. The slowest case (1.56 s) still has 1.44 s of
margin to the 3 s timeout.

### Summary against the reference thresholds

| Criterion (threshold as for the reference devices) | Lenovo TB128XU | Tab S7 FE 5G (min) | S24 FE (mid) | Lenovo pass |
| --- | --- | --- | --- | --- |
| NFR-1/AC1 face turns ≥ 30 FPS (min) / 60 FPS (mid), median | 61.96 | 60.61 | 60.32 | **yes** (both) |
| NFR-1/AC1 View Rotation ≥ 30 FPS (min) / 60 FPS (mid), median | 59.69 | 59.86 | 60.09 | **yes** at ≥ 30; below 60 |
| NFR-2/AC1 face-turn input acknowledged ≤ 100 ms, max | 57.52 ms | 47.30 ms | 45.62 ms | **yes** |
| NFR-2/AC1 View Rotation input → frame ≤ 100 ms, max | 30.94 ms | 22.31 ms | 20.47 ms | **yes** |
| NFR-3/AC2 ≥ 95 % of corpus within 3 s | 100 % (0 failures) | 100 % (0 failures) | not run | **yes** |
| NFR-13/AC3 rotation keeps state and layout follows window class | 7/7 flows with orientation requests honored; 0/7 as shipped (harness cannot rotate) | 7/7 | 7/7 | **yes** with the override; harness finding as shipped |
| 3.8 StrictMode: no main-thread disk access | 0 violations | 10 platform-only (waived, #107) | — | **yes**, no waiver needed |
| 3.8 first-run delivery rule G ≤ 5 s (decides delivery, not a pass criterion) | 14.35 s | 10.01 s | — | fails, so bundled (as on the Tab) |
| #104 per-process preparation (accepted for v1.0, no threshold) | 12.6 s debug; ≤ 10.5 s release (coarse) | 8.3 s; ≤ 7.6–7.8 s | — | n/a, about 1.5× the Tab |

Questions for the product owner before adding the device through `bmad-correct-course`:

- Tier: as a minimum-tier device it meets every threshold. Its View Rotation median of 59.69 FPS would
  miss the mid device's 60 FPS reading.
- AC3 method: should the Key Flow suite on this tablet run with `ignore-orientation-request` off (as
  here)? The alternative is to add a rotation path that works when the system ignores orientation
  requests.
- #104: about 10.5 s release-equivalent preparation per process. This is the longest wait measured so
  far behind the #112 copy.

## Out of scope

- 2D net reference-device baselines: attempted in Story 1.11 and not measured (see above); waived
  for v1.0 by the product owner in [issue #106](https://github.com/hebelmx/RubikXplorer/issues/106).
  `Net2DFrameTimingBenchmark` is kept unchanged for that retargeting.
- Solver performance work and a release-equivalent recalibration of k: v1.1, #105. The ~8 s
  per-process preparation: v1.1, #104. Story 12.2 measured both as they are and did not tune them.
- Camera, scan and Quick Check flows (Key Flows 4, 5 and 9 steps 1–3): v1.1.
