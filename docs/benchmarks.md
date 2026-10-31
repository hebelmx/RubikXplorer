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
| `--cases N` | 1000 | Seeded Scrambles in the corpus (N > 0); the solved state is always added, so the report covers N + 1 cases. |
| `--seed N` | 20261031 | Corpus seed; the same seed always gives the same corpus. |
| `--warmup N` | 200 | Untimed attempts before the timed run (N ≥ 0). |

An unknown flag or invalid value prints a message naming the flag and exits non-zero.

The report gives case count, median, p95 (nearest-rank), max, the share of cases within the 3 s
NFR-3 budget, failure count and rate (a `false` result or an exception), and peak heap (sum of the
JVM heap pools' peaks during the timed run).

Code: `core/solver/src/benchmark/kotlin/` (its own source set, so it never ships and is outside the
Kover gate). Until the solver exists the workload is a placeholder: each case applies a Scramble's
inverse and checks the cube is solved. Story 3.3 replaces it with `CubeSolver.solve` over a
representative random-legal-state corpus.

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
- The real solve corpus: Story 3.3. Full on-device performance check: Story 12.2.
