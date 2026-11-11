---
title: 'Story 1.11: Reference-device baselines and SceneView spike removal'
type: 'chore'
created: '2026-11-10'
status: 'done'
baseline_revision: '4befc1fd0b74a6a85791eba98a091bd599076307'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Net2DFrameTimingBenchmark no longer measures the 2D net: since Epic 5 the Solve tab free cube is drawn by Cube3DRenderer on a GLSurfaceView, so FrameTimingMetric sees almost no frames and Story 1.11 AC1 (2D net baseline per reference device) stays open.
    evidence: |-
      Tab S7 FE 5G run failed with "Observed no expect/actual slices in trace"; S24 FE run passed with frameCount = 1 per iteration (1 app DrawFrames vs 22 GL presents in the perfetto trace). Net2DRenderer now animates only in SolutionPlayback (app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt:82). Settle by deciding which screen NFR-2's 2D net baseline should measure (e.g. the Solution playback net), retargeting the benchmark there, adding a minimum-frame-count check so an irrelevant trace cannot pass, and rerunning on both reference devices.
    location: >-
      macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Net2DFrameTimingBenchmark.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The 2D net has no frame-timing numbers from real hardware, the OpenGL ES choice (OQ-3) rests on emulator-only frame times, and the rejected SceneView prototype still sits in the repo behind a build switch with a catalog entry.

**Approach:** Run the Story 1.10 Macrobenchmark (`Net2DFrameTimingBenchmark`) on each physical reference device and record its frame-timing baseline; re-measure the Story 1.8 OpenGL ES spike renderer on both devices with its in-app `onDrawFrame` (`Spike3d`) log, append the result to the architecture memlog; if p90 ≤ 33.3 ms on both, delete the SceneView spike.

## Boundaries & Constraints

**Always:**
- Devices (connected now; always `adb -s <serial>`; adb at `/home/abelbriones/Android/Sdk/platform-tools/adb`): minimum = `R52T50ELHWK` (Galaxy Tab S7 FE 5G, SM-T738U, Android 12 / SDK 31, 60 Hz); mid = `R5CX83A5J1J` (Galaxy S24 FE, SM-S721U1). Gradle `connected*` tasks: select one device with `ANDROID_SERIAL=<serial>`.
- Record only real measured numbers, with date, device, build type and commit; a measurement that cannot be obtained is reported as "not measured" with the reason and its AC stays open. No estimates, no emulator substitutes.
- 2D baseline per device = `FrameTimingMetric` output (`frameDurationCpuMs` and `frameOverrunMs` P50/P90/P95/P99, `frameCount`) from the `:app` `benchmark` build, copied from the JSON in `macrobenchmark/build/outputs/connected_android_test_additional_output/`.
- GL re-measure: release spike build (`-Pcubentia.spike3d=true`, R8 like release) signed with the debug key, `Spike3dActivity` autorun `--es engine gles --ei turns 40 --ez burst true`, `Spike3d` log lines collected per device. Criterion reading = worst single-turn p90 (strictest, as in Story 1.8); also report median-turn p90, frames, frames > 33.3 ms, max, `readbackOk`.
- Decision: worst-turn p90 ≤ 33.3 ms on both devices confirms OpenGL ES; append one `decision` entry with `uv run _bmad/scripts/memlog.py append --path _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md --type decision --by build --text "..."` (the only permitted write under planning-artifacts).
- Spike deletion only when confirmed: remove `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/`, the `cubentia.spike3d.sceneview` switch (renamed from `rubikxplorer.spike3d.sceneview` by the Cubentia rename) in `app/build.gradle.kts`, and the `sceneview` version + library in `gradle/libs.versions.toml`. The GLES spike (`app/src/spike3d/`, `spike3dTest/`, the `cubentia.spike3d` property) stays and must still build and test.
- Installing/uninstalling this app's builds (`com.exxerpro.cubentia`) and the macrobenchmark test APK is allowed; a signature mismatch is resolved by `adb uninstall com.exxerpro.cubentia`.

**Never:**
- No device settings changes (no `settings put`, no power/doze/animation scale changes), no factory reset, no account changes, no touching other packages (including the old `dev.rubikxplorer` on the S24 FE).
- No edit under `_bmad-output/planning-artifacts/` (except the memlog script append), `_bmad/`, `.claude/skills/`; no spine/PRD edits.
- No change to `:app` main sources, `build-logic/` gates, CI, or the production `Cube3DRenderer`/`CubeGlRenderer`; no new module, dependency or permission.
- Nothing depending on Story 3.8 (pruning tables, solve corpus on device, installed size) is measured or claimed here.
- If the p90 criterion fails on either device: do not delete the spike; record the reopen in the memlog and HALT the AC as failed rather than adjusting the threshold.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Percentile, single | `[5.0].percentile(0.9)` | 5.0 | No error expected |
| Percentile, nearest rank | `[1..10].percentile(0.9)`; `.percentile(0.5)`; `.percentile(0.99)` | 9.0; 5.0; 10.0 | No error expected |
| Percentile, empty | `emptyList().percentile(0.9)` | 0.0 | No error expected |
| Benchmark fails on a device | Macrobenchmark error (e.g. device state check) | Rerun once after fixing a cause inside allowed actions; else report "not measured" + error text | AC1 stays open for that device |
| p90 over budget | worst-turn p90 > 33.3 ms on a device | Memlog `decision` records reopen of AD-4/OQ-3; spike not deleted | Story ends with AC2/AC3 open |

</intent-contract>

## Code Map

- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Net2DFrameTimingBenchmark.kt` -- the NFR-2 2D benchmark (Solve tab, taps R U R' U' by content description, 5 WARM iterations, `CompilationMode.Full`). KDoc says baselines are Story 1.11; update to point at `docs/benchmarks.md`. `LessonProcessDeathTest.kt` in the same module does `pm clear`; filter it out with `-Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Net2DFrameTimingBenchmark`.
- `docs/benchmarks.md` -- "2D net frame timing" and "Out of scope" sections; add a "Reference-device baselines (Story 1.11)" section; out-of-scope bullets naming 1.11 become results.
- `app/build.gradle.kts:75-91` -- spike block; `withSceneView` switch picks `spike3dSceneView` vs `spike3dNoSceneView` and adds `libs.sceneview`.
- `app/src/spike3dNoSceneView/kotlin/dev/cubentia/app/spike3d/SpikeEngines.kt` -- GLES-only engine list; move it into `app/src/spike3d/kotlin/...` (KDoc: GLES only) before deleting the folder.
- `app/src/spike3d/AndroidManifest.xml` -- removes SceneView's `INTERNET`/`VIBRATE`; with SceneView gone drop those removals and the comment (flag-on manifest + denylist checks must still pass).
- `app/src/spike3d/kotlin/dev/cubentia/app/spike3d/Spike3dActivity.kt` -- autorun extras; KDoc example uses `--es engine sceneview` → change to `gles`.
- `app/src/spike3d/kotlin/dev/cubentia/app/spike3d/SpikeTurnDriver.kt` -- `percentile` (nearest rank) in companion; untested (Story 1.8 deferred item).
- `app/src/spike3d/kotlin/dev/cubentia/app/spike3d/SpikeRenderer.kt:33` -- `Spike3d` log line format `turn n=.. engine=.. fast=.. elapsedMs=.. frames=.. p50=.. p90=.. p99=.. max=.. over33=.. readbackOk=..`.
- `app/src/spike3dTest/kotlin/dev/cubentia/app/spike3d/SpikeCubeModelTest.kt` -- add percentile tests here.
- `gradle/libs.versions.toml:21,53` -- `sceneview` version and library.
- `docs/spikes/oq-3-3d-rendering.md` -- reproduce steps reference `-Pcubentia.spike3d.sceneview=false` and engine `sceneview`; add a "Reference-device re-measurement (Story 1.11)" section and note SceneView code was removed (historical numbers stay).
- Architecture memlog `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md` -- append only via script.

## Tasks & Acceptance

**Execution:**
- Device run 1 (per serial): `ANDROID_SERIAL=<serial> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Net2DFrameTimingBenchmark`; save the result JSON per device into the scratchpad and extract metrics.
- Device run 2 (per serial): build `-Pcubentia.spike3d=true -Pcubentia.spike3d.sceneview=false :app:assembleRelease`, sign with `apksigner` (debug keystore), install, `am force-stop`, `logcat -c`, `am start -W ... Spike3dActivity --es engine gles --ei turns 40 --ez burst true`, wait for `autorun done`, collect `logcat -d -s Spike3d:I`; compute median/worst p90.
- `docs/benchmarks.md` -- baseline tables per device (date, commit, device/OS, refresh rate, build type, metrics) and how to reproduce with `ANDROID_SERIAL`.
- `docs/spikes/oq-3-3d-rendering.md` -- re-measurement table per device and the verdict; reproduce steps updated to GLES only.
- Architecture memlog -- one `decision` append with both devices' worst/median p90 and the verdict.
- If confirmed: delete the two SceneView folders; move `SpikeEngines` into `src/spike3d`; simplify the `app/build.gradle.kts` spike block (no switch, no dependency); remove `sceneview` from the catalog; clean the manifest overlay and `Spike3dActivity` KDoc.
- `SpikeCubeModelTest.kt` -- percentile tests per I/O matrix.
- `Net2DFrameTimingBenchmark.kt` -- KDoc points to recorded baselines in `docs/benchmarks.md`.

**Acceptance Criteria:**
- Given both reference devices and the Story 1.10 Macrobenchmark, when it runs on each, then `docs/benchmarks.md` holds a measured 2D net frame-timing baseline per device (or an explicit "not measured" with reason, leaving that AC open).
- Given the Story 1.8 OpenGL ES renderer, when re-measured on both devices with the `Spike3d` `onDrawFrame` log, then the per-device p90 results and verdict are in the spike report and the last architecture memlog entry is the `memlog.py` decision.
- Given the choice is confirmed, when the story closes, then `git ls-files app/src/spike3dSceneView app/src/spike3dNoSceneView` is empty, `grep -rn sceneview app/build.gradle.kts gradle/libs.versions.toml` finds nothing, and `-Pcubentia.spike3d=true` still assembles, passes its manifest/denylist checks and its tests.
- Given no spike property, when `./gradlew spotlessCheck build` runs, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 14 findings — high 0, medium 3, low 5, false 6, maybe-false 0 (edge-case-hunter and verification-gap layers reported none)
- findings:
  - `[medium]` `[defer]` (blind) Benchmark still targets the Solve tab, now GL; AC1 deliverable unfinished — root cause predates this story (Epic 5 moved the free cube to Cube3DRenderer); the PO intent says an unobtainable measurement is reported open, which the diff does; recorded in `deferred` with what would settle it.
  - `[medium]` `[defer]` (blind) No check that the benchmark captured the intended workload (S24 passed with 1 frame/iteration) — same root cause as above; folded into the same deferred entry.
  - `[low]` `[patch]` (blind) View Rotation not measured — the Story 1.8 renderer has face turns only; added a "Not covered" note in the spike report's re-measurement section.
  - `[false]` `[reject]` (blind) Median turn 251.9/258.4 ms exceeds the 250 ms budget — the driver finishes on the first frame with progress >= 1, so elapsed is the 250 ms duration quantized up to the next vsync (< one 16.7 ms frame), as the Story 1.8 report already explains; not a budget miss.
  - `[low]` `[patch]` (blind) 60 FPS mid-range target not assessed — added a note that the S24 FE median-turn p90 19.73 ms does not show the 60 FPS target; the AC threshold is 33.3 ms.
  - `[low]` `[patch]` (blind) Raw evidence lived only in a temp scratchpad — copied the per-device Spike3d logs, stats script, S24 FE benchmark JSON and Tab S7 FE JUnit failure into `docs/spikes/story-1-11-device-evidence/`, linked from both docs; re-ran the script and it reproduces both table rows.
  - `[low]` `[reject]` (blind) Spec Verification records no outcomes — the fix is editing this build's spec; outcomes are recorded in Auto Run Result.
  - `[false]` `[reject]` (blind) epic-1-context.md still says to delete the SceneView folders — it is planning-derived epic context regenerated by this workflow, describing the story, not a status record.
  - `[medium]` `[defer]` (intent-alignment) AC1 reading 1b unmet: the harness reaches the screen but the screen is GL — same root cause as the first deferred finding; folded into the same deferred entry.
  - `[false]` `[reject]` (intent-alignment) AC2 measured the spike, not production CubeGlRenderer — the AC names "the OpenGL ES renderer from Story 1.8" and its in-app onDrawFrame log, which exists only in the spike (production onDrawFrame has no interval log); the docs state production was not measured.
  - `[false]` `[reject]` (intent-alignment) Spike deleted while AC1 is open — AC3's precondition is "the choice is confirmed" (AC2), which holds; SceneView code plays no part in AC1.
  - `[false]` `[reject]` (intent-alignment) epic-1-context.md rewrite drops lines — step-01 requires recompiling the stale cached epic context because planning artifacts were newer; it is a workflow artifact, not story scope.
  - `[false]` `[reject]` (intent-alignment) Welcome-screen benchmark fix is beyond the ACs — without it the AC1 benchmark cannot start on a fresh install; the spec's failure rule allows a fix within allowed actions and a rerun.
  - `[low]` `[reject]` (intent-alignment) Emulator setup commands dropped from the repro block — the AVD flags remain in prose and docs/emulators.md documents AVD creation; restoring them adds no measurement value.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass.
- `./gradlew -Pcubentia.spike3d=true spotlessCheck :app:testDebugUnitTest --tests 'dev.cubentia.app.spike3d.*' :app:assembleRelease :app:checkReleaseMergedManifest :app:checkReleaseSdkDenylist :app:checkDebugMergedManifest :app:checkDebugSdkDenylist` -- expected: pass.
- `git diff --stat 4befc1fd0b74a6a85791eba98a091bd599076307 -- _bmad-output/planning-artifacts/` -- expected: only the architecture `.memlog.md`.

## Auto Run Result

Status: done (AC1 open — see below)

**Summary:** Ran the Story 1.10 Macrobenchmark and the Story 1.8 OpenGL ES spike (`Spike3d` onDrawFrame log) on both physical reference devices on 2026-11-10 at `4befc1fd0b74a6a85791eba98a091bd599076307`. GL worst-turn p90: Tab S7 FE 5G 20.48 ms, S24 FE 23.64 ms (median-turn 18.34 / 19.73 ms, 654 / 670 frames, 0 frames > 33.3 ms, readbackOk 44/44 each) — OpenGL ES confirmed, decision appended to the architecture memlog with `memlog.py`, and the SceneView spike deleted. AC1 is **open**: no 2D net baseline could be measured, because since Epic 5 the Solve tab draws the free cube on GL; Tab S7 FE run failed ("Observed no expect/actual slices in trace"), S24 FE run recorded frameCount = 1 per iteration (reported raw, labelled not a baseline). No acceptance criterion of this story depends on Story 3.8; nothing was measured or claimed for it.

**AC status:** AC1 open (not measured, reason recorded); AC2 met; AC3 met; default build passes.

**Files changed:**
- `app/build.gradle.kts` — spike block GLES-only; `cubentia.spike3d.sceneview` switch and SceneView dependency removed.
- `gradle/libs.versions.toml` — `sceneview` version and library removed.
- `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/` — deleted; `SpikeEngines.kt` moved into `app/src/spike3d/kotlin/...`.
- `app/src/spike3d/AndroidManifest.xml` — SceneView-only INTERNET/VIBRATE removals dropped.
- `app/src/spike3d/.../Spike3dActivity.kt` — KDoc example uses `--es engine gles`.
- `app/src/spike3dTest/.../SpikeCubeModelTest.kt` — 3 nearest-rank percentile tests (closes Story 1.8 deferred item).
- `macrobenchmark/.../Net2DFrameTimingBenchmark.kt` — taps "I'm new" on the Welcome screen of a fresh install; KDoc points to docs.
- `docs/benchmarks.md` — per-device run instructions (`ANDROID_SERIAL`), Story 1.11 results and AC1-open explanation.
- `docs/spikes/oq-3-3d-rendering.md` — reference-device re-measurement table, verdict, limitations, GLES-only repro.
- `docs/spikes/story-1-11-device-evidence/` — raw Spike3d logs, stats script, S24 FE benchmark JSON, Tab S7 FE JUnit result.
- Architecture `.memlog.md` — one `decision` entry via `memlog.py` (script also bumped `updated:`).
- `_bmad-output/implementation-artifacts/epic-1-context.md` — recompiled (stale cache).

**Review:** 14 findings; 3 patched (all low: View Rotation note, 60 FPS note, raw evidence committed); 3 rows deferred as one medium entry (benchmark no longer targets the 2D net); 8 rejected (6 false, 2 low: the "spec Verification outcomes" fix would edit this spec; restoring emulator commands adds no value; reasons for each in the triage log). Follow-up review recommended: false (patched: high 0, medium 0, low 3).

**Verification:** `./gradlew spotlessCheck build` pass; `-Pcubentia.spike3d=true spotlessCheck :app:testDebugUnitTest --tests 'dev.cubentia.app.spike3d.*' :app:assembleRelease` + debug/release MergedManifest and SdkDenylist checks pass (SpikeCubeModelTest 21/21); `git ls-files app/src/spike3dSceneView app/src/spike3dNoSceneView` empty; no `sceneview` in build script or catalog; planning-artifacts diff = memlog only. Matrix audit: three percentile rows covered by passing tests; "benchmark fails" and "p90 over budget" rows are run-procedure rows, exercised by the device runs (Tab failure reported open; p90 under budget, so no reopen).

**Residual risks:** GL numbers are 60 Hz frame-callback intervals of the spike renderer, not per-frame GPU cost and not the production `Cube3DRenderer`; View Rotation unmeasured; CPU clocks not locked. `com.exxerpro.cubentia` was uninstalled from both devices after the runs; the old `dev.rubikxplorer` package on the S24 FE was not touched; no device settings changed.

