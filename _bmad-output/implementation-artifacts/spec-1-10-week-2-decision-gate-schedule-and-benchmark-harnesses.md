---
title: 'Story 1.10: Week-2 decision gate: schedule and benchmark harnesses'
type: 'chore'
created: '2026-10-31'
status: 'done'
baseline_revision: '3d0aa16c1fc2b260fc4c5e16c56d590546f1bd6e'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** P0 needs performance measurable on demand before the solver (Epic 3) and the reference-device baselines (Story 1.11) land, but the repo has no Macrobenchmark module for 2D net frame timing (NFR-2) and no JVM harness for the solve corpus (NFR-3). The week-2 decisions (OQ-9, ARCH-OQ-1) were already recorded by merged PR #26 and only need confirming.

**Approach:** Add one Android `com.android.test` module `:macrobenchmark` (FrameTimingMetric over the Solve tab's 2D net while face-turn keys are tapped) targeting a new non-debuggable `benchmark` build type of `:app`, and a `benchmark` source set in pure-JVM `:core:solver` with a reusable corpus harness plus a placeholder workload (Scramble then inverse, until Story 3.3 plugs in `CubeSolver`). Both compile in `./gradlew spotlessCheck build`; both run only on demand.

## Boundaries & Constraints

**Always:**
- AC1/AC2 are verification only (already true): PRD memlog line with `OQ-9 closed by PO 2026-10-30 ... reopens OQ-9`, PRD §9 item 9; architecture memlog `ARCH-OQ-1 closed by PO 2026-10-30` and spine Reference devices row naming Galaxy Tab S7 FE 5G (min) and Galaxy S24 FE (mid). Record the evidence in the Auto Run Result.
- Versions only in `gradle/libs.versions.toml`, verified on Google Maven 2026-10-31: `androidx.benchmark:benchmark-macro-junit4` 1.5.0, `androidx.test.ext:junit` 1.3.0, `androidx.test.uiautomator:uiautomator` 2.4.0 (latest stables).
- Android build config (SDK levels, JVM target, build types, variant filters) lives in `build-logic/` convention plugins; module scripts only apply plugins, set namespace and declare dependencies.
- The `:app` `benchmark` build type mirrors what ships: `initWith(release)` (R8 + shrink + build-logic keep rules), release feature-flag levels, release `DebugOptions`, debug signing so it installs, `<profileable android:shell="true"/>` only in its own manifest. AD-13 manifest and SDK-denylist checks run on it like any variant.
- AD-1: `:macrobenchmark` is added to `ModuleBoundaryPolicy.allowedEdges` with no project edges and gets the boundary check; the JVM harness only uses `:core:domain`/`:core:application`, which `:core:solver` already may use.
- Harness metrics: case count, median, p95 (nearest-rank), max, share within the 3 s NFR-3 budget, failures count and rate, peak heap used.

**Never:**
- No edits under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`; no memlog appends (nothing is re-recorded).
- `build`/`check`/`assemble` never run a benchmark: no `connected*` task and no harness `JavaExec` in `./gradlew build --dry-run`.
- No GL/3D frame timing (spine: GL uses the in-app `onDrawFrame` log; Story 1.11), no device baselines, no `androidx.baselineprofile` plugin, no `profileinstaller` in `:app`, no `INTERNET` or other new permission, no new module besides `:macrobenchmark`, no change to `:app` main sources.
- Harness code never ships: it lives outside `:core:solver`'s `main` source set.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Default run | `./gradlew :core:solver:solveCorpusBenchmark` | Runs solved state + 1,000 seeded Scrambles through the placeholder; prints the metric report; exit 0 | No error expected |
| Custom args | `--args="--cases 50 --seed 7 --warmup 0"` | Report over 51 cases; same seed gives same corpus | Unknown flag or non-positive/invalid number → message naming the flag, non-zero exit |
| Failing attempts | workload returns failure or throws on some cases | Counted as failures, rate = failures / cases; timing still recorded | Exception does not abort the run |
| Stats edge | 1 sample; even count; all over budget | median of 1 = that sample; even count = mean of middle two; within-budget 0% | Empty sample list rejected (`require`) |
| No device | `./gradlew spotlessCheck build` without device | `:macrobenchmark` test APK and `:app` benchmark APK assemble; no benchmark runs | No error expected |

</intent-contract>

## Code Map

- `settings.gradle.kts` -- add `include(":macrobenchmark")`.
- `gradle/libs.versions.toml` -- add versions/libraries above; plugin alias `android-test` (`com.android.test`, `agp`) and `rubikxplorer-android-macrobenchmark` convention id.
- `build.gradle.kts` -- root `plugins {}` puts every plugin on the classpath with `apply false`; add `android.test`.
- `build-logic/build.gradle.kts` -- `gradlePlugin { plugins { register(...) } }` pattern for the new convention plugin.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/AndroidAppConventionPlugin.kt` -- SDK/JVM config pattern (`libs.version("compile-sdk")`, `jdkVersion`, `KotlinAndroidProjectExtension.jvmToolchain`) to mirror for `com.android.test`.
- `build-logic/.../ReleaseConventionPlugin.kt` -- build-type policy; keep rules added only via `onVariants(selector().withBuildType("release"))` (extend to `benchmark`).
- `build-logic/.../policy/FeatureFlagLevels.kt` -- `forBuildType` throws for an unknown build type; `byBuildType` needs `benchmark` = release levels.
- `build-logic/.../policy/ModuleBoundaryPolicy.kt` -- `allowedEdges` map; unknown module = violation.
- `build-logic/.../tasks/CheckModuleBoundariesTask.kt` -- `register(project)` wires the check into `check`.
- `build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/{FeatureFlagLevelsTest,ModuleBoundaryPolicyTest}.kt` -- extend.
- `app/src/release/kotlin/dev/rubikxplorer/app/DebugOptions.kt` -- per-build-type object; benchmark source set must reuse the release dir.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- tabs Learn/Solve (`R.string.nav_solve` = "Solve"); Solve hosts the free cube with `Net2DRenderer`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/MoveKeyBar.kt` -- face keys have content description = plain Move name (`movePlainName`), label = notation token (`R`, `U'`...); no `testTagsAsResourceId`, so UiAutomator finds by text. Read-only.
- `core/solver/build.gradle.kts` -- `:core:solver` has no sources yet; jvm-core plugin (Kover ≥ 80% counts `main` only).
- `core/application/.../usecase/NewScramble.kt` + `port/RandomSource.kt` -- seeded corpus source (25 face turns); `CubeState.SOLVED.apply(moves)`, `Move.inverse()`, `isSolved` in `core/domain/.../cube/`.
- `.github/workflows/ci.yml` -- "Verify build wires the gates" dry-run guard.
- AGP 9.5 API (verified in `gradle-api-9.5.0-sources.jar`): `TestExtension.targetProjectPath`, `CommonExtension.experimentalProperties`, `HasHostTestsBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]`.

## Tasks & Acceptance

**Execution:**
- `gradle/libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts` -- catalog entries, root `apply false`, include module.
- `build-logic/.../policy/FeatureFlagLevels.kt` + test -- `benchmark` maps to release levels (test asserts equality with release).
- `build-logic/.../policy/ModuleBoundaryPolicy.kt` + test -- `":macrobenchmark" to emptySet()`; test it passes with only external deps and fails on a project edge.
- `build-logic/.../ReleaseConventionPlugin.kt` -- create `benchmark` build type (`initWith(release)`, debug signing config, `matchingFallbacks += "release"`), add `src/release/kotlin` to its source set, keep rules for `release` and `benchmark`, disable the benchmark variant's unit-test host test (no triple Robolectric run).
- `app/src/benchmark/AndroidManifest.xml` -- `<profileable android:shell="true" tools:targetApi="29"/>` inside `<application>`.
- `build-logic/.../AndroidMacrobenchmarkConventionPlugin.kt` + registration -- apply `com.android.test`; SDKs/JVM from catalog; `targetProjectPath = ":app"`; `experimentalProperties["android.experimental.self-instrumenting"] = true`; `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`; `benchmark` build type (debuggable, debug signing, fallback release); only the `benchmark` variant enabled; AD-1 boundary check registered.
- `macrobenchmark/build.gradle.kts` + `src/main/AndroidManifest.xml` -- apply convention plugin, namespace `dev.rubikxplorer.macrobenchmark`, deps.
- `macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/Net2DFrameTimingBenchmark.kt` -- `MacrobenchmarkRule.measureRepeated(packageName = "dev.rubikxplorer", metrics = listOf(FrameTimingMetric()), compilationMode = CompilationMode.Full(), startupMode = StartupMode.WARM, iterations = 5, setupBlock = { start activity, tap "Solve" })` and a measure block tapping face keys (e.g. R U R' U') with waits; KDoc states NFR-2 2D only, GL via `onDrawFrame` log, baselines in Story 1.11.
- `core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/` -- `CorpusStats.kt` (pure stats + report formatting), `SolveCorpusHarness.kt` (warmup, timing with `System.nanoTime`, exceptions as failures, peak heap via heap `MemoryPoolMXBean.resetPeakUsage`/`peakUsage`), `PlaceholderWorkload.kt` (seeded `NewScramble` corpus + solved state; attempt = apply reversed inverses, success = `isSolved`; KDoc: Story 3.3 replaces with `CubeSolver.solve`), `SolveCorpusBenchmarkMain.kt` (arg parsing, prints report).
- `core/solver/build.gradle.kts` -- `benchmark` source set (deps `:core:application`, `:core:domain`), `benchmarkClasses` wired into `assemble`, test source set sees benchmark output, `solveCorpusBenchmark` `JavaExec` (group `benchmark`, not in any lifecycle task).
- `core/solver/src/test/kotlin/dev/rubikxplorer/solver/benchmark/` -- tests for every I/O matrix row except "No device" (stats edges, failure counting incl. exceptions, arg parsing errors, placeholder succeeds on a small corpus).
- `.github/workflows/ci.yml` -- guard also requires `:macrobenchmark:checkModuleBoundaries`, `:macrobenchmark:assembleBenchmark`, `:app:checkBenchmarkMergedManifest`, `:app:checkBenchmarkSdkDenylist`, `:core:solver:compileBenchmarkKotlin`; fails if the plan contains `connected` or `solveCorpusBenchmark`.
- `docs/benchmarks.md` -- how to run each on demand (`./gradlew :core:solver:solveCorpusBenchmark [--args=...]`; `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest` with a device/emulator), what each measures, what is out of scope (GL, baselines → 1.11).

**Acceptance Criteria:**
- Given the PRD and architecture memlogs, PRD §9 and the spine, when the gate is checked, then the OQ-9 decision (with the Story 1.9 reopen trigger) and ARCH-OQ-1 (both devices named) are present and no planning file changed (`git diff --stat` empty under `_bmad-output/planning-artifacts/`).
- Given no device, when `./gradlew spotlessCheck build` runs, then it passes, the `:macrobenchmark` benchmark APK and `:app` benchmark APK are built, and `--dry-run` lists no `connected*` or `solveCorpusBenchmark` task.
- Given `./gradlew :core:solver:solveCorpusBenchmark`, when it runs, then it prints cases, median, p95, max, within-3 s share, failure count/rate and peak heap, and exits 0.
- Given `./gradlew :macrobenchmark:tasks --all`, when listed, then `connectedBenchmarkAndroidTest` exists (on-demand device run).

## Spec Change Log

## Review Triage Log

## Design Notes

The `benchmark` app build type is required because Macrobenchmark rejects a debuggable target and the release APK is unsigned; reusing release config keeps the measured app equal to what ships. Placing the JVM harness in a `:core:solver` source set avoids a module the spine does not have, keeps the harness next to the solver Story 3.3 will plug in, and keeps it out of the shipped jar and the Kover denominator.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass.
- `./gradlew -p build-logic test` -- expected: pass.
- `./gradlew build --dry-run | grep -E 'connected|solveCorpusBenchmark'` -- expected: no output.
- `./gradlew :core:solver:solveCorpusBenchmark` and `... --args="--cases 20 --seed 7 --warmup 0"` -- expected: report printed, exit 0; `--args="--cases x"` exits non-zero with a message.
- `./gradlew :macrobenchmark:tasks --all | grep connectedBenchmarkAndroidTest` -- expected: one line.

## Auto Run Result

**AC1 (verification only, no planning file changed):**
- PRD memlog line 41: `OQ-9 closed by PO 2026-10-30: keep P0 schedule ~16 weeks, scan uses stay in P0 ... a spike result below >= 99% per-sticker accuracy reopens OQ-9`.
- PRD §9 item 9: `OQ-9 (closed 2026-10-30): ... Reopen it if the Story 1.9 recognition spike misses ≥ 99% per-sticker accuracy.`
- Architecture memlog line 59: `ARCH-OQ-1 closed by PO 2026-10-30: minimum = Samsung Galaxy Tab S7 FE 5G, mid = Samsung Galaxy S24 FE`.
- Spine line 438, Reference devices row: minimum Samsung Galaxy Tab S7 FE 5G, mid Samsung Galaxy S24 FE.
- `git diff --stat` under `_bmad-output/planning-artifacts/`: empty.

**Deviation:** AGP's `com.android.test` wires `targetProjectPath` as a `:app` project edge on the `testedApks` configuration, which failed `:macrobenchmark:checkModuleBoundaries`. `allowedEdges` keeps `":macrobenchmark" to emptySet()`; `ModuleBoundaryPolicy.testedTargets` allows exactly `:macrobenchmark -> :app` on `testedApks` and nothing else (tested). Kover 0.9 counted the `benchmark` source set, so `JvmCoreConventionPlugin` excludes it explicitly.
