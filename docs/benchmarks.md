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

An unknown flag or invalid value prints a message naming the flag and exits non-zero.

The report gives case count, median, p95 (nearest-rank), max, the share of cases within the 3 s
NFR-3 budget, failure count and rate (a `false` result or an exception), and peak heap (sum of the
JVM heap pools' peaks during the timed run).

Code: `core/solver/src/benchmark/kotlin/` (its own source set, so it never ships and is outside the
Kover gate). The workload calls the real `TwoPhaseCubeSolver` through `CubeSolver`. Every returned
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
remains before-merge acceptance evidence. Story 12.2 must confirm the corpus on physical reference
devices. Story 3.8 still owns production table delivery, cold/warm physical loading and installed size.

Mandatory CI corpus (`./gradlew :core:solver:test`, JDK 17 worker with Kover), seed 20261031:

| Cases | Median | p95 | Max | Successful within 3 s | Failures | Peak heap |
| --- | --- | --- | --- | --- | --- | --- |
| 10,101 | 3.637 ms | 21.348 ms | 229.863 ms | 10,101 (100%) | 0 (0%) | 334.2 MiB |

The corpus test took 69.745 s including generation and warmup. Its result is printed in
`core/solver/build/test-results/test/TEST-dev.rubikxplorer.solver.TwoPhaseCubeSolverTest.xml`.
These figures measure the actual host profile above and do not establish reference-device equivalence.



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
- Full on-device solve performance check: Story 12.2; table production integration: Story 3.8.
