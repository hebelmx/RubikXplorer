---
title: 'Keep camera code out of a release whose camera flags are OFF'
type: 'chore'
created: '2026-11-14'
status: 'done'
baseline_revision: '537bc460a178e4494ef2a634d7504e999928451a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Record the scan port's final name (ScanLauncher) in spine AD-9 through a bmad-architecture update.
    evidence: |-
      ARCHITECTURE-SPINE.md:538 lists the port name as open and says Story 10.9 fixes it; planning artifacts are not editable by this build.
    location: >-
      _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md:538
    severity: low
---

<intent-contract>

## Intent

**Problem:** v1.1 camera work must merge to `main`, but nothing today stops CameraX code, the `CAMERA` permission or an ML runtime from reaching a release whose camera flags are all OFF; the only gate (`ManifestPolicy`) rejects `CAMERA` on every non-debuggable variant, so a release could never legitimately raise a camera flag either.

**Approach:** Make `FeatureFlagLevels.byBuildType` compose the build: build-logic adds the `app/src/camera/` source set (Kotlin + manifest) and CameraX dependencies only to build types with a camera flag above OFF, validates the `CAMERA_SCAN` dependency rule, lets `ManifestPolicy` allow `CAMERA` exactly when that build type's camera flag is above OFF, and adds a per-variant camera-classpath check. `:app` main reaches the camera only through a `ScanLauncher` port with an optional Hilt binding the camera source set provides.

## Boundaries & Constraints

**Always:**
- Camera flags are `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`; "camera-enabled build type" = at least one above OFF.
- Production levels come only from `FeatureFlagLevels.byBuildType`. The build-logic extension that carries levels defaults to it and exists only as a TestKit seam; `:app` never sets it.
- `ManifestPolicy` keeps keying on `variant.debuggable`; debuggable variants still allow `CAMERA`. Non-debuggable + camera-enabled allows `CAMERA` and nothing else; non-debuggable + all OFF allows nothing (Story 12.10 unchanged).
- A camera `uses-feature` (`android.hardware.camera*`) is a manifest violation wherever `CAMERA` is not allowed.
- With all camera flags OFF, a variant's runtime classpath must hold no `androidx.camera:*`, `com.google.mlkit:*`, `com.google.ai.edge.litert*`, `org.tensorflow*`, `org.opencv*`.
- `docs/privacy-facts.json` stays byte-identical; release keeps today's levels.
- Gates live in `build-logic`; app build script is not given gate logic.

**Never:**
- No CameraX import or camera code in `app/src/main`; no `INTERNET`; no change to release/benchmark flag levels.
- Do not move the Story 1.9 `spikeScan` prototype (Story 10.10 does) or build scan UI (10.2+).
- Do not use `BuildConfig` outside `BuildConfigFeatureFlags` for the debug fail-fast; key it on the app's debuggable flag.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Today's levels | debug GA, release/benchmark camera OFF | debug composes camera source set + CameraX; release/benchmark manifests and classpaths camera-free; all checks pass | No error |
| OFF + stray CAMERA | release all OFF, `CAMERA` in release manifest | `checkReleaseMergedManifest` fails | names `CAMERA` |
| OFF + stray CameraX | release all OFF, `androidx.camera:*` on release classpath | `checkReleaseCameraClasspath` fails | names coordinate |
| Raised flag | release `CAMERA_SCAN=BETA` | camera manifest composed into release; manifest check passes; privacy facts `runtimePermissions: ["android.permission.CAMERA"]` | No error |
| Dependent without camera | `PLACEMENT` (or `QUICK_SCAN`/`CASE_RECOGNITION`) above OFF, `CAMERA_SCAN` OFF | configuration fails | message names the flag and `CAMERA_SCAN` |
| No binding, debuggable | camera flag above OFF, no `ScanLauncher` binding | `FeatureFlags` construction throws at startup | `IllegalStateException` naming the flags |
| No binding, not debuggable | camera flag above OFF, no binding | camera flags read OFF, so every scan entry point is hidden | No error |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt` -- `byBuildType` (debug all GA, release/benchmark `releaseLevels`); add camera-flag sets, `isCameraEnabled(levels)` and dependent-flag validation.
- `build-logic/.../ReleaseConventionPlugin.kt` -- sets `FEATURE_FLAG_LEVELS` per build type in `buildTypes.configureEach` (line ~52) and creates `benchmark`; composition belongs here, read in `androidComponents.finalizeDsl` so fixture overrides apply.
- `build-logic/.../QualityConventionPlugin.kt` -- registers per-variant `check<V>MergedManifest`, `check<V>SdkDenylist`, release-only `writePrivacyFacts`/`checkPrivacyFacts`; add `check<V>CameraClasspath` and pass camera-enabled to the manifest check.
- `build-logic/.../policy/ManifestPolicy.kt` -- `allowedRuntimePermissions(debuggable)`; `check()` parses `uses-permission*`; extend for camera flag + `uses-feature`.
- `build-logic/.../tasks/CheckMergedManifestTask.kt`, `tasks/CheckSdkDenylistTask.kt` -- task patterns to mirror; `CheckSdkDenylistTask.coordinates()` reusable for runtime coordinates.
- `build-logic/.../policy/SdkDenylistPolicy.kt` -- glob style to mirror for the camera/ML patterns; AD-13 also lists ML Kit and `play-services-mlkit*` on the denylist (missing in code).
- `build-logic/.../ProjectExtensions.kt` -- `libs` catalog helpers (`library(alias)`); CameraX aliases `androidx-camera-camera2|lifecycle|view` in `gradle/libs.versions.toml`.
- `build-logic/src/test/.../functional/AndroidFixture.kt` -- TestKit fixture; no version catalog today, so composition needs a fixture catalog + fake local camera modules (see `PrivacyFactsFunctionalTest.publishModule`).
- `app/build.gradle.kts` -- existing per-build-type `sourceSets.getByName(bt).kotlin.directories +=` pattern; `spikeScan` opt-in block stays untouched.
- `app/src/main/kotlin/dev/cubentia/app/di/FlagsModule.kt` -- `@Binds BuildConfigFeatureFlags → FeatureFlags` (Singleton); becomes a provider that applies the scan-port gate.
- `app/src/main/.../ui/shell/AppShell.kt`, `ui/welcome/WelcomeScreen.kt`, `ui/lesson/LessonScreen.kt` -- current scan entry points (`CAMERA_SCAN` finale prompt, `PLACEMENT` action) all read `FeatureFlags`, so gating the port hides them.
- `.github/workflows/ci.yml:38-68` -- "Verify build wires the gates" task list.

## Tasks & Acceptance

**Execution:**
- `build-logic/.../policy/FeatureFlagLevels.kt` -- add `cameraFlags`, `cameraDependentFlags`, `isCameraEnabled(levels)`, `cameraViolations(buildType, levels)` (each dependent flag above OFF with `CAMERA_SCAN` OFF → message naming it) -- one rule shared by plugin and tests.
- `build-logic/.../FeatureFlagLevelsExtension.kt` (new) + helper in `ProjectExtensions.kt` -- `cubentiaFeatureFlags { byBuildType }` defaulting to `FeatureFlagLevels.byBuildType`, documented as a TestKit seam -- one per-project levels source for both plugins.
- `build-logic/.../ReleaseConventionPlugin.kt` -- in `finalizeDsl`: validate every build type (fail naming the flag), set `FEATURE_FLAG_LEVELS` from the extension, and for camera-enabled build types add `src/camera/kotlin` to that source set plus `<bt>Implementation` CameraX camera2/lifecycle/view from the catalog; in `onVariants` add `src/camera/AndroidManifest.xml` via `variant.sources.manifests.addStaticManifestFile` for camera-enabled build types -- AC1.
- `build-logic/.../policy/ManifestPolicy.kt` + `tasks/CheckMergedManifestTask.kt` -- `allowedRuntimePermissions(debuggable, cameraEnabled)`; camera `uses-feature` violation when `CAMERA` not allowed; task gets `cameraEnabled` input -- AC3.
- `build-logic/.../policy/CameraClasspathPolicy.kt`, `tasks/CheckCameraClasspathTask.kt` (new) + `QualityConventionPlugin.kt` -- per-variant `check<V>CameraClasspath` in `check`, failing on camera/ML/CV coordinates when the build type is not camera-enabled (unknown build types count as all OFF) -- AC2.
- `build-logic/.../policy/SdkDenylistPolicy.kt` -- add `com.google.mlkit:*`, `com.google.android.gms:play-services-mlkit*` (AD-13).
- `build-logic/src/test/...` -- unit tests for the new/changed policies and levels rule; `AndroidFixture` gains a version catalog with fake local camera modules; new `CameraCompositionFunctionalTest` covering AC4 (a)–(d) plus "today's levels: debug composes, release does not" -- AC4.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanLauncher.kt`, `scan/ScanPortGate.kt`(or similar) and `di/ScanPortModule.kt` -- port interface (`hasCamera(context)`), `@BindsOptionalOf`, and a `FeatureFlags` wrapper that throws when debuggable and masks camera flags to OFF otherwise when no binding; `FlagsModule` provides it -- AC5.
- `app/src/camera/kotlin/dev/cubentia/app/camera/` (new) -- CameraX-backed `ScanLauncher` + Hilt `@Binds` module; `app/src/camera/AndroidManifest.xml` with `CAMERA` and `uses-feature android.hardware.camera.any required="false"`.
- `app/src/test/kotlin/dev/cubentia/app/scan/...Test.kt` -- proves the I/O matrix rows for the port gate, incl. debug fail-at-startup.
- `.github/workflows/ci.yml` -- add `:app:checkDebugCameraClasspath :app:checkReleaseCameraClasspath :app:checkBenchmarkCameraClasspath`.

**Acceptance Criteria:**
- Given today's levels, when `./gradlew spotlessCheck build` runs, then it passes, the release and benchmark merged manifests have no `CAMERA`/camera `uses-feature`, `releaseRuntimeClasspath` has no camera/ML/CV coordinate, and `git diff docs/privacy-facts.json` is empty.
- Given the debug variant, when it builds, then its merged manifest declares `CAMERA` and the Hilt graph contains the camera source set's `ScanLauncher` binding.
- Given `./gradlew -p build-logic test`, when it runs, then AC4 (a)–(d) fixtures pass.

## Spec Change Log

## Review Triage Log

### 2026-11-14 — Review pass
- verdicts: 18 findings — high 0, medium 1, low 9, false 8, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) `CameraScanLauncher` implements `CameraXConfig.Provider`, which CameraX reads only from the Application — removed the supertype, `getCameraXConfig()` and imports.
  - `[low]` `[reject]` (blind) Fixtures compile no camera Kotlin — the real `:app` debug build compiles `src/camera/kotlin` (Hilt generated `CameraScanLauncher_Factory`), and release would fail compile or the classpath check if composed; an extra fixture compile test is complexity for an unlikely gap.
  - `[medium]` `[patch]` (blind) Startup failure only proven on `ScanPortGate` directly, not the production provider — added Robolectric `FlagsModuleTest` driving the real `FlagsModule.featureFlags` (debuggable + no binding throws).
  - `[low]` `[reject]` (blind) Camera-classpath check sees only external coordinates — camera code in `src/main` cannot compile in release without CameraX on its classpath; local jars/AARs are not used in this repo; fix adds a new gate.
  - `[low]` `[reject]` (blind) Camera `uses-feature required="true"` not rejected when camera is enabled — only `app/src/camera/AndroidManifest.xml` declares it (`required="false"`); a new guard for a hypothetical edit.
  - `[low]` `[reject]` (blind) `cubentiaFeatureFlags` override could be set by a production script — documented as a TestKit seam; any use would be a visible `app/build.gradle.kts` edit and `FeatureFlagLevelsTest` pins release levels; enforcement adds a guard.
  - `[false]` `[reject]` (blind) Release/benchmark parity not enforced — production parity is pinned by `FeatureFlagLevelsTest` "benchmark carries the release levels"; the divergence exists only in the fixture override.
  - `[false]` `[reject]` (edge) Debuggable variant with all camera flags OFF accepts `CAMERA` — intended: AC3 keeps the rule keyed on `variant.debuggable`, and the Story 12.10 test "a custom debuggable build type passes with CAMERA" pins it.
  - `[low]` `[patch]` (verification-gap) Provider's `hasCamera` mapping untested — covered by the new `FlagsModuleTest` cases (3) and (4).
  - `[false]` `[reject]` (intent) AC2 proven only in fixtures — verification ran the real `./gradlew spotlessCheck build`, release classpath grep (0), merged manifests (release/benchmark 0 CAMERA, debug 1) and `git diff --exit-code docs/privacy-facts.json`.
  - `[low]` `[reject]` (intent) ML/CV absence on the real release classpath tested only via policy unit test — real `releaseRuntimeClasspath` grep returned nothing and the check task runs in `build`.
  - `[false]` `[reject]` (intent) `QUICK_SCAN` in the dependency rule exceeds AC4(d) — SCP §6 Q6 and AD-18 name it; AC4(d) cites Q6.
  - `[false]` `[reject]` (intent) ML Kit always denylisted exceeds AC2 — AD-13 (spine) already lists it on the denylist; code now matches.
  - `[low]` `[reject]` (intent) Hilt optional binding / `CubentiaApp` injection not exercised — `FlagsModuleTest` now drives the provider; a full Hilt test needs a variant without the camera binding, which debug cannot provide.
  - `[false]` `[reject]` (intent) No scan entry point observed hidden at UI level — existing `AppShellCameraScanTest`/`WelcomeScreenTest` prove entry points hide when the flag reads OFF, which is what the gate produces.
  - `[low]` `[defer]` (intent) AD-9 not updated with the fixed port name `ScanLauncher` — the spine may only change through `bmad-architecture`; outside this build's write scope.
  - `[low]` `[reject]` (intent) Opt-in `spikeScan` path still adds debug CameraX outside composition — opt-in, debug-only, and Story 10.10 moves it; release unaffected.
  - `[false]` `[reject]` (intent) `hasCamera` masking beyond the readings — UX-DR19 (epic context) requires hiding entry points on devices with no bindable camera.

## Design Notes

Composition runs in `finalizeDsl` (after the build script, before variants) so a fixture's `cubentiaFeatureFlags { }` and production share one code path; DSL source-set edits there flow into variant sources that KSP/Hilt read. Manifest overlay uses the variant API because `debug` and `benchmark` already own a source-set manifest.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -E "androidx.camera|mlkit|litert|tensorflow|opencv"` -- expected: no output
- `grep -c CAMERA app/build/intermediates/merged_manifest/release/*/AndroidManifest.xml` -- expected: 0 (and same for benchmark); debug > 0
- `git diff --exit-code docs/privacy-facts.json` -- expected: exit 0

## Auto Run Result

**Summary:** Flag levels now compose the build. `cubentia.release` adds `app/src/camera/` (Kotlin, manifest with `CAMERA` + optional `camera.any`) and CameraX only to build types with a camera flag above OFF, and fails configuration when `PLACEMENT`/`QUICK_SCAN`/`CASE_RECOGNITION` is above OFF without `CAMERA_SCAN`. `ManifestPolicy` allows `CAMERA` on a non-debuggable variant only when its build type is camera-enabled and rejects camera `uses-feature` wherever `CAMERA` is not allowed. A new per-variant `check<V>CameraClasspath` gate rejects camera/ML/CV runtimes when flags are OFF. `:app` reaches the camera only through the `ScanLauncher` port (optional Hilt binding from the camera source set); `ScanPortGate` throws at startup in a debuggable app with a camera flag on and no binding, and otherwise reads camera flags OFF.

**Files changed:**
- `.github/workflows/ci.yml` — lists the three `check<V>CameraClasspath` tasks in "Verify build wires the gates".
- `build-logic/.../FeatureFlagLevelsExtension.kt`, `ProjectExtensions.kt` — per-project levels (TestKit seam, defaults to `FeatureFlagLevels.byBuildType`).
- `build-logic/.../ReleaseConventionPlugin.kt` — camera composition, dependency-rule validation, BuildConfig levels in `finalizeDsl`.
- `build-logic/.../QualityConventionPlugin.kt` — camera-aware manifest check and new camera-classpath check.
- `build-logic/.../policy/FeatureFlagLevels.kt` — camera flag sets, `isCameraEnabled`, `cameraViolations`.
- `build-logic/.../policy/ManifestPolicy.kt`, `tasks/CheckMergedManifestTask.kt` — `cameraEnabled` input and camera `uses-feature` rule.
- `build-logic/.../policy/CameraClasspathPolicy.kt`, `tasks/CheckCameraClasspathTask.kt` — new gate.
- `build-logic/.../policy/SdkDenylistPolicy.kt` — ML Kit and `play-services-mlkit*` (AD-13).
- `build-logic/src/test/...` — `AndroidFixture` (fake CameraX catalog + camera manifest), `CameraCompositionFunctionalTest` (AC4 a–d + today's levels), `CameraPolicyTest`.
- `app/src/camera/**` — `CameraScanLauncher`, `CameraModule`, camera manifest.
- `app/src/main/.../scan/ScanLauncher.kt`, `scan/ScanPortGate.kt`, `di/ScanPortModule.kt`, `di/FlagsModule.kt`, `CubentiaApp.kt` — port, gate, optional binding, startup injection.
- `app/src/test/.../scan/ScanPortGateTest.kt`, `di/FlagsModuleTest.kt` — gate and production-provider tests.

**Review findings:** 18 findings; 3 patched (1 medium: production provider untested at startup → `FlagsModuleTest`; 2 low: dead `CameraXConfig.Provider` removed, `hasCamera` mapping tested); 1 deferred (AD-9 port name needs a `bmad-architecture` update); 14 rejected with reasons recorded in the Review Triage Log (8 false, 6 low not worth added guards).

**Follow-up review recommended:** false. Patched: high 0, medium 1, low 2.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches); `./gradlew -p build-logic test` BUILD SUCCESSFUL (CameraCompositionFunctionalTest 4/4, CameraPolicyTest 4/4); `FlagsModuleTest` 4/4, `ScanPortGateTest` 4/4; `releaseRuntimeClasspath` has no camera/ML/CV coordinate; merged manifests `CAMERA` count release 0, benchmark 0, debug 1; `git diff --exit-code docs/privacy-facts.json` exit 0.

**Residual risks:** the `cubentiaFeatureFlags` override is a documented TestKit seam, not enforced against production use; the opt-in `spikeScan` debug path still adds CameraX outside the composition until Story 10.10 moves it.
