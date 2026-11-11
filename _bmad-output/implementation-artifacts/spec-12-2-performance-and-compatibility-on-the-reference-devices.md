---
title: 'Story 12.2: Performance and compatibility on the reference devices'
type: 'feature'
created: '2026-11-11'
status: 'in-progress'
baseline_revision: 'b583bae1c3c1ea302f68f24eca2b628cd3018a9b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/docs/benchmarks.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** NFR-1/2/3/13 have never been measured on the shipping code: the only GL frame numbers (Story 1.11) come from the spike renderer via a log, the 2D-net Macrobenchmark no longer sees any frames (#106), the device solve corpus ran only on a debuggable build (Story 3.8), and no test rotates the real activity through the Key Flows or asserts layout per window class.

**Approach:** Make the production `Cube3DRenderer` measurable by Macrobenchmark (trace sections + a custom trace metric for GL frame rate and input-to-frame latency), run it for face turns and View Rotation on both physical reference devices; run the Story 3.3 corpus on the minimum device on the release-equivalent `benchmark` build; add an in-process instrumented Key Flow rotation suite run on both physical devices and the AVDs covering API 29–36. Record only real measured results in `docs/benchmarks.md` with raw evidence in `docs/evidence/12-2/`.

## Boundaries & Constraints

**Always:** physical-device numbers only for AC1/AC2 (serials `R52T50ELHWK` minimum, `R5CX83A5J1J` mid; always `adb -s <serial>`, adb at `/home/abelbriones/Android/Sdk/platform-tools/adb`); AVDs (`spike3d_api29`, `GalaxyTabS7FE_API34`, `GalaxyS24FE_API35`, `GalaxyS24FE_API36`) only for AC3 layout/rotation, launched headless and shut down afterwards; every measurement records device/Android identity, full git revision, build type, run conditions (battery/charging, thermal, display refresh rate); thresholds stay exactly as written (≥ 30 FPS minimum, 60 FPS mid, ≤ 100 ms input, ≥ 95% within 3 s); a missed threshold is reported with its numbers and listed as a product-owner question; link Story 3.8 evidence (`docs/benchmarks.md` 3.8 section, `docs/evidence/3-8/`, k = 16.94) and the accepted issues #104, #105, #107, #106 instead of re-deriving them; rotation only through the app/test harness (`Activity.requestedOrientation`), restored to `SCREEN_ORIENTATION_UNSPECIFIED`; if any `settings put system user_rotation/accelerometer_rotation` is used, restore the start values (Tab S7 FE: accelerometer_rotation=1, user_rotation=3; S24 FE: accelerometer_rotation=1, user_rotation=0); `./gradlew spotlessCheck build` stays green and never runs connected/benchmark tasks.

**Never:** change device settings beyond installing this app's APKs/test APKs (and the restored rotation above); use emulator numbers for performance; lower thresholds, reword criteria or invent results; edit `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `prd.md`, `ARCHITECTURE-SPINE.md` or a `.memlog.md` by hand; ship the corpus runner in `release`; add `INTERNET`, analytics or new production dependencies; do solver performance work (#105 owns it); push or open PRs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Threshold met | measured FPS/latency/corpus within limits | result row with numbers and "met" | — |
| Threshold missed | e.g. mid-device FPS < 60 | numbers recorded with "not met", PO question in Auto Run Result | never retuned to pass |
| Trace lacks a slice | device emits no input or GL slice in an iteration | metric throws naming the missing slice; iteration not silently counted | investigate, report |
| Rotation mid-flow | any Key Flow surface, portrait ↔ landscape | `SessionHost.state.value` equal before/after; layout matches window class | test fails with the flow/surface name |
| Emulator unavailable | AVD fails to boot | that API row marked "not run" with reason | not substituted |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubeGlRenderer.kt:onDrawFrame` -- GL-thread draw; wrap in `android.os.Trace` section `Cube3D.drawFrame` (no new dependency; release-safe, near-zero cost when not tracing).
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- Compose-driven presentation loop (`LaunchedEffect(this, view)`, `withFrameNanos { queue.onFrame; publish() }`), `publish()` sets `presented`; emit async section `Cube3D.turn` from when a new `PresentationItem.Animate` becomes active until it finishes. Turn length `PresentationQueue.TURN_MILLIS` = 200 ms. View Rotation by drag (off-cube on the Solve tab) updates `orbit` per pointer event → `LaunchedEffect(orbit) { requestRender() }`; menu rotations (`viewRotate:*`, 45° steps) jump, so drag is the continuous View Rotation measured.
- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Net2DFrameTimingBenchmark.kt` -- pattern for Solve-tab setup (Welcome → *I'm new* → Solve), Move keys by content description ("R: right face clockwise" …). Keep it (#106); do not repurpose.
- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/LessonProcessDeathTest.kt` -- runs `pm clear`; always filter runs by class.
- `macrobenchmark/build.gradle.kts`, `gradle/libs.versions.toml:22` -- benchmark-macro 1.5.0 (has `TraceMetric`/`TraceSectionMetric`, `@ExperimentalMetricApi`, `TraceProcessor`).
- `app/src/debug/kotlin/dev/cubentia/app/solver/SolveCorpusBenchmarkActivity.kt` + `app/src/debug/AndroidManifest.xml` -- Story 3.8 corpus runner (logcat tag `SolveCorpusBenchmark`, `raw,` line); debug only today.
- `app/build.gradle.kts:39` -- debug source set adds `../core/solver/src/benchmarkShared/kotlin`; benchmark build type is created in `build-logic/.../ReleaseConventionPlugin.kt` (copy of release, R8, debug-signed, `src/release/kotlin` shared); `app/src/benchmark/AndroidManifest.xml` (profileable only).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/WindowWidthClass.kt` -- COMPACT < 600dp ≤ MEDIUM < 840dp ≤ EXPANDED; `AppShell.kt:612-648` tags `nav:bar` / `nav:rail`, `nav:learn|solve|library|you`; `LessonScreen.kt:837-860` compact Column vs Row, `EXPANDED_PANEL_MAX` (560dp), tags `lesson`, `lesson:stage`, `lesson:physicalModeSwitch`; `LibraryScreen.kt:129-131` 2/3/4 columns, `library:grid`; `ManualCubeEditor.kt:100-106` compact = one face at a time; Welcome tags `welcome:imNew`, `welcome:canSolve`; `freeCube:stage`, `stage:viewMenu`.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt:69-70,341-351` -- `@Singleton` session owner, `state` StateFlow (compare before/after rotation). `MainActivity` has no `configChanges`, so rotation recreates it.
- `app/src/androidTest/kotlin/dev/cubentia/app/` -- existing plain `AndroidJUnit4` tests, runner `androidx.test.runner.AndroidJUnitRunner`; no Hilt test rule. Compose UI test junit4 is already an androidTest dependency.
- Key Flows: `_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md:205-291` (read-only). v1.0 flows: 1 (camera-free path), 2, 3 (Enter colors path), 6, 7, 8, 9 step 4; flows 4, 5 and 9 steps 1–3 are v1.1 (camera) and out of scope.
- Story 1.11 reference: `docs/spikes/oq-3-3d-rendering.md` (spike p90 20.48/20.14 ms Tab S7 FE, 23.64/20.57 ms S24 FE), `docs/spikes/story-1-11-device-evidence/`.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/render/gl/CubeGlRenderer.kt`, `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- add the `Cube3D.drawFrame` section and `Cube3D.turn` async section (unique cookie per item, always ended, including on `finishCurrent`/`snapNow`/dispose) -- makes the production GL renderer visible to Macrobenchmark.
- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/GlFrameMetric.kt` (new) -- `@OptIn(ExperimentalMetricApi::class)` `TraceMetric` that queries the trace for the target process: `Cube3D.drawFrame` slices, `Cube3D.turn` windows and the main-thread input slices (`deliverInputEvent…`; use the `eventTimeNano` in the slice name when present, else slice start). Per iteration reports `glFrameCount`, `glFps` (frame intervals ÷ window span, s), interval p50/p90/p99/max ms, `glFramesOver33ms`, and `inputToFrameMs` p50/max. Face-turn windows = each `Cube3D.turn`; latency = input UP of the tap → end of the first `drawFrame` that starts after the turn's activation frame (first frame with progress > 0). View-Rotation window = first to last drag MOVE input; latency = first MOVE → end of the first `drawFrame` after it. Fails loudly when a required slice is missing. Interval/percentile math (nearest rank, as `docs/spikes/story-1-11-device-evidence/spike3d_stats.py`) is one small pure function; cross-check one iteration by hand against its raw trace and record that check in the evidence.
- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Cube3DFrameBenchmark.kt` (new) -- two tests on the `benchmark` build, `CompilationMode.Full()`, WARM, 10 iterations: `faceTurns` (Solve tab, tap R U R' U' with ≥ 400 ms between taps so each 200 ms turn completes) and `viewRotation` (drag from a stage point off the cube, ~600 ms, several swipes alternating direction) with `GlFrameMetric`; also log display refresh rate (`dumpsys display` / `Display.refreshRate`).
- `app/build.gradle.kts`, `app/src/benchmark/AndroidManifest.xml`, corpus runner source -- also compile `benchmarkShared` and `SolveCorpusBenchmarkActivity` into the `benchmark` build type (move the activity to a source dir shared by `debug` and `benchmark`, e.g. `app/src/corpusRunner/kotlin`; declare it exported in the benchmark manifest; nothing added to `release`); add R8 keep rules if logging/reflection needs them -- release-equivalent production corpus run.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt` (new) + helpers -- `createAndroidComposeRule<MainActivity>()`; one test per v1.0 Key Flow (1, 2, 3, 6, 7, 8, 9-step-4) driving the flow through the UI from a cleared install; at each surface of the flow rotate portrait → landscape → portrait via `activity.requestedOrientation` (wait for recreation), assert `SessionHost.state.value` (via a Hilt `@EntryPoint`) and the visible cube/step are unchanged, and assert layout for the current window class (computed from the window width in dp with `WindowWidthClass.of`): `nav:bar` iff COMPACT else `nav:rail`; Lesson stage above panel (COMPACT) vs left of it (MEDIUM/EXPANDED), panel ≤ 560dp at EXPANDED; Library columns 2/3/4; editor one-face vs net. Flow 2 and Flow 7 preconditions are reached through the UI where possible (Flow 8 passed by tapping the Move keys of a solution computed in-process for the skip-check state), otherwise seeded only through the app's own use cases (single writers), never by writing Room directly. Restore `SCREEN_ORIENTATION_UNSPECIFIED` in `@After`. Data is cleared by the host (`adb -s <serial> shell pm clear com.exxerpro.cubentia`) before each method run.
- `tools/run-keyflow-rotation.sh` (new) -- runs each `KeyFlowRotationTest` method on one serial: `pm clear`, `am instrument -w -e class …#method`, collects results to `docs/evidence/12-2/`.
- Device runs -- AC1 on both physical devices (`ANDROID_SERIAL=<serial> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Cube3DFrameBenchmark`); AC2 on `R52T50ELHWK` with the benchmark build (`--ei cases 10000 --el seed 20261031 --ei warmup 200`); AC3 on both physical devices plus the four AVDs (one at a time, headless, shut down after).
- `docs/benchmarks.md` -- new "Story 12.2" sections: production GL frame rate + input latency table per device/test with threshold verdicts and comparison to Story 1.11; production corpus result (cases, median, p95, max, ≤ 3 s share, failures and rate, peak memory, wall time, prepare time) beside the linked Story 3.8 debug/calibrated results and #104/#105; compatibility matrix (device/AVD × API × form factor × orientation × window class × Key Flow → pass/fail); how to reproduce each. Update the "Out of scope" bullets that name Story 12.2.
- `docs/evidence/12-2/` -- raw benchmark JSON, corpus logcat, instrumentation outputs, device identity/condition dumps.

**Acceptance Criteria:**
- Given Macrobenchmarks on the minimum and mid reference devices, when face turns and View Rotation run, then GL FPS and input-to-frame latency of the production renderer are recorded per device with verdicts against ≥ 30 FPS (minimum), 60 FPS (mid) and ≤ 100 ms, from real runs.
- Given the production solve corpus on the minimum device, with Story 3.8 calibration and calibrated corpus evidence linked, when run on the `benchmark` build, then the share within 3 s against 95%, median, p95, memory and failure rate are reported as measured.
- Given phone and tablet in portrait and landscape on API 29 through targetSdk 36 (physical API 31 tablet and API 37 phone, AVDs API 29, 34, 35, 36), when every v1.0 Key Flow runs, then layouts follow their window class and rotation never changes session state, with a per-target result matrix.
- Given the change, when `./gradlew spotlessCheck build` runs, then it passes and no connected/benchmark task is in the `build` plan.

## Spec Change Log

- 2026-11-11, PO decision: AC3 failed on the first run (`655833e`): rotation re-dealt the skip-ahead check and Stage practice Scramble (Flows 2, 7, 8 on every target). The PO decided to fix it inside this story; the fix in `AppShell.kt` saves the dealt Scramble with `rememberSaveable` (commit `0ec0d6b`, Robolectric regressions in `AppShellTest`). The PO also accepted the S24 FE's 60 FPS on the median as AC1 met, and the API 30 gap. The physical S24 FE `R5CX83A5J1J` was returned before the rerun, so the AC3 API 37 rerun used the `GalaxyS24FE_API37` AVD; the first-run physical results stay as superseded evidence.
- AC3 status (rerun at `0ec0d6b`): **met**: rotation changes no session state and layouts follow the window class on all six targets; 41 of 42 target × flow runs pass. Open: Flow 2 on `GalaxyTabS7FE_API34` stops after 3/3 passing surfaces, as in the first run. Diagnosed as an app bug, not a harness bug: in a 555 × 716 dp COMPACT window (letterboxed portrait), expanding *more* pushes the wide and slice Move-key rows past the fixed-height stage, under the panel, so `f` cannot be pressed. Recorded as a finding in `docs/benchmarks.md`, not fixed here. Its later surfaces pass on the other five targets.

## Review Triage Log

## Design Notes

FPS definition: over each measurement window, `glFps = (frames − 1) / (lastFrameStart − firstFrameStart)`. On a 60 Hz panel the maximum is ~60, so the report shows the measured value with one decimal and the verdict for "60 FPS" uses the measured value as is (no rounding up); if the mid device reads e.g. 59.4, it is recorded as not met and raised as a PO question (including whether the panel's refresh mode matters). Interval percentiles accompany FPS so jank is visible.

Latency honesty: the activation frame of a turn draws progress 0 (no visible change), so acknowledgement is the first frame after it — conservative by one vsync.

The Key Flow suite is in-process (not the black-box `:macrobenchmark` module) so it can compare the exact `SessionState`, seed Flow 2/7 preconditions through use cases, and rotate via `requestedOrientation` without touching device settings.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `ANDROID_SERIAL=<serial> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Cube3DFrameBenchmark` -- expected: both tests pass on each physical device; JSON copied to `docs/evidence/12-2/`.
- `tools/run-keyflow-rotation.sh <serial>` on each physical device and AVD -- expected: every method passes or its failure is recorded.

**Manual checks (if no CLI):**
- `docs/benchmarks.md` Story 12.2 numbers match the raw files in `docs/evidence/12-2/` exactly; every missed threshold appears as a PO question.
