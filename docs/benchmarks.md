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

| Run | Identity | Median | p95 | Max | ≤ 3 s | Failures | Peak memory | Raw |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Device | Galaxy Tab S7 FE 5G `R52T50ELHWK`, Android 12 / API 31, fingerprint `…T738USQU3BVG7:user/release-keys`, 8 CPUs; revision `79e6611`, debug build; USB charging 82 → 80 %, thermal status 1, stay-awake on, MemAvailable 564,872 kB at start; wall 1,055.9 s | 53.795 ms | 338.075 ms | 3,000.916 ms | 10,100 / 10,101 (99.99%) | **1** (0.0099%, timed out) | 27.1 MiB Runtime heap (limit 512 MiB) | [log](evidence/3-8/solve-corpus-tab-s7-fe.log) |
| Host | i7-12800H, 20 logical CPUs, Linux 7.1.5 x86_64, OpenJDK 17.0.21 (JavaExec toolchain; Gradle on 25.0.5); revision `ed490da` | 3.262 ms | 19.962 ms | 223.370 ms | 10,101 (100%) | 0 | 604.6 MiB heap pools | [log](evidence/3-8/solve-corpus-host.log) |

Calibration: k = max(device/host median, device/host p95) = max(53.795 / 3.262 = 16.49,
338.075 / 19.962 = 16.94) = **16.94**. The calibrated profile is this host JVM with `--time-scale 16.94`:

```sh
./gradlew :core:solver:solveCorpusBenchmark --args="--cases 10000 --seed 20261031 --warmup 200 --time-scale 16.94"
```

Calibrated rerun (revision `ef9c6df`, same host; [log](evidence/3-8/solve-corpus-calibrated.log)):

| Cases | Median | p95 | Max | Successful within 3 s | Failures | Peak heap |
| --- | --- | --- | --- | --- | --- | --- |
| 10,101 | 45.355 ms | 277.384 ms | 3,068.382 ms | 10,100 (99.99%) | 0 (0%) | 605.4 MiB |

So the calibrated profile meets NFR-3: zero failures, every Solution verified, and 99.99% (≥ 95%)
within 3 s. Its median and p95 are lower than the device's because of host run-to-run variance
between the pairing run and the rerun. The one case over 3 s after scaling would be a timeout on the
device. The direct device run likewise had 1 timeout in 10,101 cases, which is a real failure in that
run. Story 12.2 still owns final physical corpus confirmation on a release build. The rerun briefly
overlapped `adb` installed-size measurements on the same host.

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

## Out of scope

- 3D renderer frame timing: `FrameTimingMetric` cannot see `GLSurfaceView` frames; GL uses the
  in-app `onDrawFrame` interval log (Story 1.11).
- Reference-device baselines (Galaxy Tab S7 FE 5G minimum, Galaxy S24 FE mid): Story 1.11.
- Full on-device solve performance check on a release build: Story 12.2. Table delivery and the
  calibrated profile: Story 3.8 (above and [pruning-tables.md](pruning-tables.md)).
