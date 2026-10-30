---
title: 'Story 1.8: Spike — choose the 3D rendering approach (OQ-3)'
type: 'chore'
created: '2026-10-30'
status: 'done'
baseline_revision: '8b66dd54285f1eb0c4e46589ac4367b37c015c2e'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Spike readback/driver tests (SpikeCubeModelTest) only run with -Prubikxplorer.spike3d=true, so CI never runs them.
    evidence: |-
      ci.yml runs spotlessCheck build without the property; the test dir is added to the test source set only when it is set. Epic 5 must bring the model into main with its tests (or CI adds a flag-on step if the spike code is kept).
    location: >-
      app/build.gradle.kts:140
    severity: low
  - summary: >-
      Frame-time percentile math (SpikeTurnDriver.percentile, p50/p90/p99/over33) has no test.
    evidence: |-
      The only driver test asserts facelets, fast-forward flags and elapsed time, never the percentile fields. Add an assertion if Story 1.10 reuses the Spike3d frame log for reference-device re-measurement.
    location: >-
      app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SpikeTurnDriver.kt
    severity: low
  - summary: >-
      The SceneView rejection rests on criterion 3 by a small margin for the per-ABI size (about +10.68 MB arm64-only, derived not built, vs an assumed 10 MB limit); asset trimming and an AAB/abiFilters build were not tried, and the size measure NFR-11 applies to is not pinned.
    evidence: |-
      Universal release APK delta is +30,390,600 B, which fails the spec's rule as written. Whether SceneView would pass on the measure Play actually installs is unverified; settle it with an arm64 abiFilters (or AAB split) release build with unused SceneView assets excluded, and state whether NFR-11 means download, install or APK size. Story 1.10 is the natural place.
    location: >-
      docs/spikes/oq-3-3d-rendering.md
    severity: medium (unverified)
  - summary: >-
      Root spotless target("**/*.kt") walks build/ before excluding it, so spotlessCheck can fail with "Could not read path .../app/build/intermediates/..." when it runs alongside tasks that rewrite build outputs.
    evidence: |-
      Seen twice during verification (once with assembleRelease in the same invocation, once on spotlessCheck build); reruns passed. Pre-existing in build.gradle.kts:18-19, not caused by this story.
    location: >-
      build.gradle.kts:18
    severity: low
---

<intent-contract>

## Intent

**Problem:** The spine names SceneView (Filament) as the 3D engine only as an `[ASSUMPTION]` pending the OQ-3 spike; Epic 5 must build the 3D cube once, on an engine chosen from evidence rather than guesswork.

**Approach:** Build two throwaway `CubeRenderer` implementations (SceneView and custom OpenGL ES 3.0), each rendering a 27-cubie cube and one animated quarter turn, compiled only behind an opt-in Gradle property; measure frame time, release APK size delta, animation control and `renderedFacelets()` feasibility; write the evidence to a spike report and append the decision to the architecture memlog.

## Boundaries & Constraints

**Always:**
- Spike code compiles only when the Gradle property `rubikxplorer.spike3d=true` is set. Without it, `./gradlew spotlessCheck build` and both APKs are unchanged from baseline (no new dependency, source, manifest entry, or task in the default build).
- Both prototypes implement the existing `dev.rubikxplorer.app.render.CubeRenderer` port unchanged, consume `PresentationItem`s, and draw 27 cubies with 54 stickers colored from `StickerAppearance` fills, in the Kociemba home frame (WHITE U, GREEN F, RED R).
- `renderedFacelets()` in each prototype is read back from the prototype's own cubie geometry (positions/orientations of the 27 cubies), not echoed from the last `PresentationItem.target`.
- Quarter turn duration is 250 ms (Epic 5 budget), driven by code: start, per-frame progress, completion, and a faster speed (fast-forward) must each be demonstrated or recorded as impossible.
- Use the latest stable SceneView 3D artifact (`io.github.sceneview:sceneview`, not `arsceneview`) verified on Maven Central at implementation time; record the exact version. Verify its API against the downloaded artifact/sources — do not trust remembered APIs.
- Both release-mode measurement APKs are R8-minified like `release`; the APK size delta is measured as (release APK with spike) − (release APK without spike) for each engine, in bytes, using `apkanalyzer` or file size.
- Frame time is measured on every device `adb devices` lists. If none is attached, install the Android emulator and one x86_64 system image (KVM is available) via `~/Android/Sdk/cmdline-tools/*/bin/sdkmanager`, measure on that AVD, and label the numbers emulator-only (not reference-device evidence). If no emulator can boot either, record frame time as "not measured" with the reason.
- Record the decision with `uv run _bmad/scripts/memlog.py append --path _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md --type decision --by build --text "..."` — this script call is the only permitted write under `_bmad-output/planning-artifacts/`.
- Decision rule: confirm SceneView unless it fails a hard criterion that the OpenGL ES prototype passes. Hard criteria: (1) the quarter turn is code-controllable per frame with completion; (2) `renderedFacelets()` is derivable from geometry; (3) release APK delta ≤ 10 MB [ASSUMPTION: one-third of the NFR-11 30 MB budget]; (4) measured frame-time p90 ≤ 33.3 ms (NFR-2, 30 FPS) during the turn on each measured device. Tie-breaks (implementation effort for Epic 5, glyph/highlight/arrow support) are judged and written down.

**Never:**
- Never edit `ARCHITECTURE-SPINE.md`, `prd.md`, `epics.md`, any other file under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, or `build-logic/` gates.
- Never add the `INTERNET` permission or any denylisted SDK; never add `arsceneview`/ARCore.
- Never wire either prototype into `FreeCubeScreen`, `MainActivity`, Hilt modules, or the default build; never change `CubeRenderer`, `PresentationItem`, `RenderStyle`, or `:core:*`.
- No gestures, View Rotation, glyphs, Demonstration arrows, or production polish — those are Epic 5.
- Never log Cube States or facelets; frame-time logs only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solved readback | Shared cubie model, no Moves | `facelets()` equals `CubeState.SOLVED.toFacelets()` | No error expected |
| Every quarter turn | Model after committing each of `U R F D L B` and inverses (12 Moves) from solved | `facelets()` equals `CubeState.SOLVED.apply(move).toFacelets()` | No error expected |
| Mid-animation | A turn at progress 0.5 | `facelets()` still equals the pre-turn facelets (not committed until progress 1) | No error expected |
| Random sequence | 25 random face quarter/half turns, pinned seed | `facelets()` equals `CubeState.SOLVED.apply(moves).toFacelets()` | No error expected |
| Default build | No `rubikxplorer.spike3d` property | Spike sources, deps and manifest entries absent; `./gradlew spotlessCheck build` passes | Build fails → fix the property gating, not the gates |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/rubikxplorer/app/render/CubeRenderer.kt` -- the port both prototypes implement (`submit(List<PresentationItem>)`, `renderedFacelets(): Facelets`). Read-only.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/PresentationItem.kt` -- `Animate(move, revision, target)` / `Snap(revision, target)`. Read-only.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt` -- reference adapter: FIFO `Channel` queue, fast-forward after 3 queued, draining on the frame clock. Reuse its queue semantics, not its drawing.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/` -- `CubeState.SOLVED`, `apply(move)`, `apply(moves)`, `toFacelets()`; `Facelets` (54, URFDLB, Kociemba net row-major); `Move` subtypes `FaceTurn(face, turn)` etc. Oracle for readback tests. Read-only.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearance.kt` -- sticker fill per `Color`. Read-only.
- `app/build.gradle.kts` -- only file of the default build to change: a `providers.gradleProperty("rubikxplorer.spike3d")` block that, when `true`, adds spike source dirs, the spike manifest overlay (e.g. via the `debug`/`release` source-set manifests, which are unused today), and the SceneView dependency. Nothing else changes.
- `gradle/libs.versions.toml` -- add `sceneview` version + library alias (an unused catalog entry does not affect the default build).
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/QualityConventionPlugin.kt` -- per-variant manifest + SDK-denylist checks apply automatically to spike builds too; they must pass with the flag on. Read-only.
- `build-logic/.../ReleaseConventionPlugin.kt`, `policy/FeatureFlagLevels.kt` -- `forBuildType` throws for unknown build types, which is why the spike uses the existing `debug`/`release` types rather than a new build type. Read-only.
- `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md` -- append only via `memlog.py` (see Always). Spine line 419 holds `SceneView 4.62.0 (Filament 1.72.x) [ASSUMPTION: OQ-3 spike]`; line 503 holds the OQ-3 deferral.
- `~/Android/Sdk` -- has platform-tools, build-tools, cmdline-tools (`sdkmanager`, `avdmanager`, `apkanalyzer`); no emulator or system image installed; `/dev/kvm` is user-accessible. `adb devices` listed no devices at planning time.

## Tasks & Acceptance

**Execution:**
- `gradle/libs.versions.toml` -- add the verified SceneView version and `sceneview` library alias -- pin in the single version catalog.
- `app/build.gradle.kts` -- add the `rubikxplorer.spike3d` property block (sources `app/src/spike3d/kotlin`, tests `app/src/spike3dTest/kotlin`, manifest overlay `app/src/spike3d/AndroidManifest.xml`, `implementation(libs.sceneview)`) -- keeps the default build untouched.
- `app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SpikeCubeModel.kt` -- pure Kotlin 27-cubie model (integer positions + 3×3 rotation per cubie, sticker colors on local faces), `beginTurn(FaceTurn)`, `setProgress(Float)` giving per-cubie animation transforms, `commit()`, and `facelets(): Facelets` read from geometry -- shared by both prototypes and proves readback feasibility on the JVM.
- `app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SceneViewCubeRenderer.kt` -- throwaway `CubeRenderer` drawing the model with SceneView (27 cube nodes with sticker quads or vertex colors), animating a 250 ms quarter turn by grouping the layer under a pivot node, with frame-interval timing.
- `app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/GlesCubeRenderer.kt` -- throwaway `CubeRenderer` on `GLSurfaceView` + GLES 3.0 (one VBO, per-cubie model matrix), same animation and timing.
- `app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/Spike3dActivity.kt` + `app/src/spike3d/AndroidManifest.xml` -- separate launcher activity: engine toggle, "R" and "R'" buttons submitting `PresentationItem.Animate`, a "×4 queued" button for fast-forward, and a frame-time logger (tag `Spike3d`: p50/p90/p99/max ms and frames > 33.3 ms per turn).
- `app/src/spike3dTest/kotlin/dev/rubikxplorer/app/spike3d/SpikeCubeModelTest.kt` -- JVM tests covering every I/O matrix row except "Default build".
- `docs/spikes/oq-3-3d-rendering.md` -- spike report: time box (start/end dates vs the 3-day assumption), environment and devices, a measurement table per engine (frame time, APK delta bytes, animation control findings, readback feasibility), how to reproduce, decision with rationale against the decision rule, and follow-ups for Story 1.10 (reference-device re-measure if only emulator numbers exist) and Epic 5.
- Architecture memlog -- append one `decision` entry: OQ-3 outcome, engine + exact version, key numbers, and either "spine SceneView [ASSUMPTION] confirmed; bmad-architecture update needed only to drop the tag and set version" or "bmad-architecture update requested: replace SceneView with custom OpenGL ES" -- satisfies the second AC.

**Acceptance Criteria:**
- Given the spike build (`-Prubikxplorer.spike3d=true`), when `Spike3dActivity` runs either engine and a quarter turn is triggered, then 27 cubies render and the turned layer animates over 250 ms, and the frame-time numbers for each engine on each measured device are in the spike report.
- Given both engines, when the spike ends, then the report records release APK size delta in bytes, animation-control findings (start, progress, completion, fast-forward) and `renderedFacelets()` feasibility for each engine.
- Given the measurements, when the spike ends, then the architecture memlog's last entry is the OQ-3 decision appended by `memlog.py`, and it states whether the spine's SceneView `[ASSUMPTION]` is confirmed or a `bmad-architecture` update is requested.
- Given no `rubikxplorer.spike3d` property, when CI's commands run, then they pass and the default APKs contain no SceneView/Filament classes or spike activity.

## Design Notes

Readback golden idea: each sticker stores its color and a local normal; world normal = cubie rotation × local normal picks the face (U/R/F/D/L/B); the cubie's world position picks row/column on that face's Kociemba net, e.g. on U (B edge on top) row = z+1 from back (z = −1 → row 0), col = x+1. Animation never touches the committed rotation matrices — it applies a temporary angle about the turn axis to the layer's cubies; `commit()` snaps rotations by an exact integer 90° matrix, so readback is exact.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: passes, default build unchanged.
- `./gradlew -p build-logic test` -- expected: passes (untouched, sanity).
- `./gradlew -Prubikxplorer.spike3d=true spotlessCheck :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.spike3d.*'` -- expected: all model tests pass.
- `./gradlew -Prubikxplorer.spike3d=true :app:assembleRelease :app:assembleDebug` -- expected: builds; manifest and SDK-denylist checks pass.
- `uv run _bmad/scripts/memlog.py append ...` -- expected: one JSON line echoed.

**Manual checks (if no CLI):**
- `git diff --stat` shows no change under `_bmad-output/planning-artifacts/` except the `.memlog.md` append, and none under `build-logic/`, `_bmad/`, `.claude/skills/`.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 32 findings — high 0, medium 0, low 23, false 6, maybe-false 3
- findings:
  - `[low]` `[defer]` (verification-gap) Spike tests never run in CI — throwaway code; filed disposition defer; recorded in `deferred` for Epic 5.
  - `[low]` `[defer]` (verification-gap) Percentile/frame-stat fields untested — filed disposition defer; recorded for Story 1.10 reuse.
  - `[low]` `[patch]` (verification-gap other) Criterion-4 row ambiguous about swiftshader — report now excludes swiftshader from "measured devices"; both engines Pass on emulator host GPU; swiftshader kept as a stress note.
  - `[false]` `[reject]` (intent-alignment) renderedFacelets() feasibility answered at shared-model level, engine-independent — the spec (Always) deliberately required readback from the adapter's own cubie geometry, which is the `CubeRenderer` contract Epic 5 needs; the report says both engines do it the same way.
  - `[false]` `[reject]` (intent-alignment) Animation control shared, not engine-native — both engines were driven per frame from code as the AC asks; engine-specific findings are in the report; not a defect.
  - `[maybe-false]` `[defer]` (intent-alignment) Decision rests on a spec-added 10 MB threshold missed by a small derived margin — grouped with blind #1/#2; deferred medium (unverified) with what would settle it.
  - `[low]` `[patch]` (intent-alignment) Frame time is emulator-only callback timing — grouped with blind #3; report now has a Limitations note; emulator-only labelling and Story 1.10 re-measure were already present.
  - `[false]` `[reject]` (intent-alignment) Architecture update requested, not run — the AC says "a bmad-architecture update is requested"; the spine is off-limits to hand edits; memlog entry records the request.
  - `[false]` `[reject]` (intent-alignment) Default-build isolation not test-asserted — verified directly: flag-off release APK 2,126,832 B, identical to a release built from the baseline build script; no SceneView/Filament/spike dex packages or spike activity (apkanalyzer).
  - `[maybe-false]` `[defer]` (blind) Small margin under an assumed threshold; asset trimming untried — grouped deferral above.
  - `[maybe-false]` `[defer]` (blind) Size metric NFR-11 applies to not pinned (universal APK vs per-ABI split) — grouped deferral above.
  - `[low]` `[patch]` (blind) Frame-time method measures vsync pacing, not cost — Limitations note added to the report.
  - `[low]` `[patch]` (blind) Engines did different work (lit PBR/IBL vs simple-lit) — stated in the Limitations note.
  - `[low]` `[patch]` (blind) Criterion 4 inconsistent about swiftshader — same fix as verification-gap other.
  - `[low]` `[patch]` (blind) KDoc "more than" vs code `>=` for fast-forward — KDoc and report now say "at least / 3 or more queued behind, as in Net2DRenderer".
  - `[low]` `[reject]` (blind) No screenshot/recording of the render — numbers and manual emulator runs are the spike evidence; committing media adds bulk for no decision value.
  - `[low]` `[patch]` (blind) Flag-on gate checks and default-APK cleanliness not evidenced in the report — new "Default build isolation" section with byte-identical SHA-256 and passing debug/release MergedManifest + SdkDenylist checks with the flag on.
  - `[low]` `[reject]` (blind) Spec drifted from implementation (extra source sets, second property, extra files) — the fix is editing this build's spec; rejected per triage rule; deviations are listed in Auto Run Result.
  - `[low]` `[patch]` (blind) GL lifecycle not exercised yet follow-up claims context loss handled "as the spike does" — report corrected: lifecycle and context loss not exercised, Epic 5 must build both.
  - `[low]` `[reject]` (blind) Driver paths untested (non-FaceTurn Animate, Snap mid-turn, submit race) — throwaway spike; unlikely to be met; adding tests is more than a direct correction.
  - `[low]` `[patch]` (blind) Reproduction steps lose the baseline APK; only gles shown; no time-box end — baseline copied aside, both engines and swiftshader steps shown, end time recorded.
  - `[low]` `[patch]` (blind) Autorun re-runs on engine switch — grouped with edge #2; once-per-activity rememberSaveable flag.
  - `[low]` `[patch]` (edge) readbackOk logged before the last turn finishes when burst=false — autorun now always waits while renderer.isBusy() before logging.
  - `[low]` `[patch]` (edge) Autorun repeats on engine switch/recreation — same fix as blind autorun finding.
  - `[low]` `[reject]` (edge) Burst wait hangs if the render loop stops — only in attended manual measurement runs; adding a timeout branch is more than a direct correction.
  - `[low]` `[patch]` (edge) GLSurfaceView onPause/onResume never forwarded — grouped with blind lifecycle finding; report corrected (spike scope excludes lifecycle).
  - `[false]` `[reject]` (edge) Stalled intervals enter p90/max — counting a stall is correct: a long frame is jank the criterion must see.
  - `[low]` `[patch]` (edge) Fast-forward 62 ms vs reported 62.5 ms — report now says 62 ms.
  - `[low]` `[reject]` (edge) A backlog building mid-turn does not speed up the running turn — same per-item decision as Net2DRenderer; fine for the spike.
  - `[low]` `[reject]` (edge) Property only accepts exact "true" — documented spelling; negligible.
  - `[false]` `[reject]` (edge) "27 cubies render" literally false (center skipped) — the model holds 27 cubies; the stickerless center is invisible, so the rendered cube is the 27-cubie cube.
  - `[low]` `[patch]` (edge) SceneView pivot-node grouping not used — report now states per-cubie transforms were written and pivot/hierarchy animation was not exercised.

## Auto Run Result

Status: done

**Summary:** Built two throwaway `CubeRenderer` prototypes (custom OpenGL ES 3.0 and SceneView 4.63.0 / Filament 1.72.1) sharing a pure 27-cubie model with geometry readback and a 250 ms turn driver, compiled only with `-Prubikxplorer.spike3d=true`. Measured on an API 29 x86_64 emulator (no physical device was attached): both pass turn control, readback and frame-time p90 (17.93 ms GLES, 18.40 ms SceneView, host GPU); SceneView fails the size criterion (+30,390,600 B universal release APK, about +10.68 MB arm64-only, vs ≤ 10 MB) while GLES adds 65,672 B. Decision: custom OpenGL ES 3.0; appended to the architecture memlog via `memlog.py` with a `bmad-architecture` update requested to replace the spine's SceneView `[ASSUMPTION]` row.

**Files changed:**
- `app/build.gradle.kts` — property-gated spike block (sources, test dir, manifest overlay, SceneView dep; `rubikxplorer.spike3d.sceneview=false` builds GLES-only).
- `gradle/libs.versions.toml` — `sceneview` 4.63.0 catalog entry.
- `app/src/spike3d/` — `SpikeCubeModel`, `SpikeTurnDriver`, `SpikeRenderer`, `GlesCubeRenderer`, `Spike3dActivity`, manifest overlay (removes SceneView's INTERNET/VIBRATE).
- `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/` — SceneView renderer and engine list per build.
- `app/src/spike3dTest/.../SpikeCubeModelTest.kt` — 18 JVM tests (all I/O matrix rows except "Default build").
- `docs/spikes/oq-3-3d-rendering.md` — spike report.
- architecture `.memlog.md` — one appended decision entry (script also bumped `updated:`).

**Review:** 32 findings; 15 patched (all low), 5 deferred findings in 3 `deferred` entries (2 low; 3 maybe-false grouped as medium unverified on the SceneView size margin) plus a 4th entry for a pre-existing spotless race found during verification, 12 rejected (6 false, 6 low) with reasons in the triage log. Follow-up review recommended: false (patched: high 0, medium 0, low 15).

**Deviations from spec:** extra source sets and a second property to measure GLES-only size; SceneView renderer writes per-cubie transforms instead of a pivot node and uses the TextureView surface; `android.util.Log` used directly (no `Logger` port yet, timing only).

**Verification:** `./gradlew spotlessCheck build` pass; `./gradlew -p build-logic test` pass; flag-on `spotlessCheck` pass; flag-on `:app:testDebugUnitTest --tests 'dev.rubikxplorer.app.spike3d.*' :app:assembleRelease :app:assembleDebug` pass (18/18); flag-on debug/release MergedManifest + SdkDenylist checks pass; flag-off release APK byte-identical size (2,126,832 B) to a release built with the baseline build script. Matrix audit: all rows covered by passing tests; "Default build" row covered by the default build run.

**Residual risks:** frame time is emulator-only — Story 1.10 must re-measure GLES on reference devices (p90 > 33.3 ms reopens the decision); the SceneView size failure is close on a per-ABI measure (see deferred); emulator measurements were not re-run after the autorun timing fix (affects only when autorun starts/logs). An x86_64 API 29 system image, the emulator and AVD `spike3d_api29` were installed under `~/Android/Sdk`. Spotless can flake when it walks `build/` concurrently (pre-existing).
