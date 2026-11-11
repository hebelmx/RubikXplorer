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
The shared directory is also compiled into the app's `debug` source set for the device runner. The workload calls the real `TwoPhaseCubeSolver` through `CubeSolver`. Every returned
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

**Device side.** `SolveCorpusBenchmarkActivity` (debug build only, never in release/benchmark) runs
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

GL frame timing for the reference devices (face turns and View Rotation), measured with the in-app
`onDrawFrame` log, is in
[the OQ-3 spike report](spikes/oq-3-3d-rendering.md#reference-device-re-measurement-story-111).

## Out of scope

- 3D renderer frame timing: `FrameTimingMetric` cannot see `GLSurfaceView` frames; GL uses the
  in-app `onDrawFrame` interval log. Story 1.11 re-measured the OpenGL ES spike renderer on both
  reference devices with it (worst-turn p90 20.48 ms on the Tab S7 FE 5G, 23.64 ms on the S24 FE),
  and View Rotation with the same method (worst p90 20.14 ms and 20.57 ms; no frame over 33.3 ms).
- 2D net reference-device baselines: attempted in Story 1.11 and not measured (see above); waived
  for v1.0 by the product owner in [issue #106](https://github.com/hebelmx/RubikXplorer/issues/106).
- Full on-device solve performance check on a release build: Story 12.2. Table delivery and the
  calibrated profile: Story 3.8 (above and [pruning-tables.md](pruning-tables.md)).
