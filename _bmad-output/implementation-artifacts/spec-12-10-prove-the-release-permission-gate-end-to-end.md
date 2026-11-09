---
title: 'Prove the release-permission gate end to end'
type: 'chore'
created: '2026-11-09'
status: 'done'
baseline_revision: '589e490074db0196cb542feeb9422a0b95b10980'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The AD-13 rule "a release never declares a runtime permission" is proven only at the pure-policy unit: nothing shows `checkReleaseMergedManifest` runs and fails on `CAMERA`, nothing shows `AppShell` hands `CAMERA_SCAN` to `LessonScreen`, and the policy keys on the build-type name `"debug"` (Epic 8 retro item 10).

**Approach:** Key the policy on `variant.debuggable` instead of the build-type name, prove the real task wiring with a Gradle TestKit Android fixture run from `build-logic` tests, and add an `AppShell` Robolectric test that drives the shipped Stage 6 finale in physical mode at `CAMERA_SCAN` BETA.

## Boundaries & Constraints

**Always:** Gate logic stays in `build-logic/` (AD-14). The task reads a required `debuggable` input with no default, so an unset value fails the build. CAMERA stays the only runtime permission a debuggable variant may declare. The TestKit fixture applies the real `com.android.application` and `cubentia.quality` plugins and runs the real `check<Variant>MergedManifest` tasks. The fixture setup is a reusable helper so `epic-2-retro-item-4` can build on it. A missing Android SDK fails the TestKit test loudly; it never skips.

**Never:** Add `CAMERA`, `INTERNET` or any runtime permission to the app manifest. Change `FeatureFlagLevels` or release flag levels. Consult the build-type name in the policy. Implement `epic-2-retro-item-4` itself (the `checkLearnerText` test). Change Learner copy, `LessonScreen` behavior or `AppShell` production code. Edit planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Release with CAMERA | Fixture manifest declares `CAMERA`; `checkReleaseMergedManifest` | Task FAILED, output names `android.permission.CAMERA` | Build fails |
| Debug with CAMERA | Same manifest; `checkDebugMergedManifest` | Task SUCCESS | No error expected |
| Custom non-debuggable type | `staging` build type, `isDebuggable = false` | Task FAILED naming CAMERA | Build fails |
| Custom debuggable type | `qa` build type, `isDebuggable = true` | Task SUCCESS | No error expected |
| `debug` name, not debuggable | `debug { isDebuggable = false }` | Task FAILED: the name grants nothing | Build fails |
| Debuggable unset on the task | `debuggable` property has no value | Task fails instead of defaulting | Gradle missing-value error |
| CAMERA_SCAN BETA in AppShell | Stage 6 finale, physical mode, Objective met | Camera prompt shown, camera-free prompt absent | No error expected |
| CAMERA_SCAN OFF in AppShell | Same path | Camera-free prompt shown, camera prompt absent | No error expected |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/ManifestPolicy.kt` -- l.22-25 `DEBUG_BUILD_TYPE`, `allowedRuntimePermissions(buildType: String?)`: the name key to replace with `debuggable: Boolean`; KDoc l.9-14 states the rule.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckMergedManifestTask.kt` -- `buildType` `@Input` l.20-22, read at l.36.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/QualityConventionPlugin.kt` -- l.29 `buildType.set(variant.buildType.orEmpty())`; `variant` is an `ApplicationVariant`, which exposes `debuggable`.
- `build-logic/src/test/.../policy/ManifestPolicyTest.kt` -- l.24-25 and l.28-33, l.42-47 call the name-keyed function.
- `build-logic/build.gradle.kts` -- AGP, KGP and detekt are `compileOnly`, so `pluginUnderTestMetadata` lacks them and the fixture cannot load `cubentia.quality`; the plugin classpath needs them added, without touching the included build's runtime classpath.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/ProjectExtensions.kt` -- `applyAdvisoryDetekt` makes `cubentia.quality` require `dev.detekt` at apply time.
- `.github/workflows/ci.yml` -- `./gradlew -p build-logic test` already runs after `build`, with the SDK set up, so the fixture runs in CI with no workflow change.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- read-only: l.173-181 the Lesson call passing `cameraScan`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- read-only: l.316 `camera = cameraScan != OFF`, l.838-850 prompt choice, finale is `lesson-06-03`.
- `app/src/sharedTest/kotlin/dev/cubentia/app/session/TestLessons.kt` -- l.152-166 `testFlags`: every flag GA, needs a `cameraScan` parameter.
- `app/src/test/.../ui/shell/AppShellTest.kt` -- l.754-778 pattern for seeding stored `LessonCompleted` records through a `ProgressRepository`; tags `learn:stage:<id>`, `learn:lesson:<id>`.
- `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` -- l.144-175 `meetStage6ObjectiveInPhysicalMode`, the driving sequence to reuse.

## Tasks & Acceptance

**Execution:**
- `build-logic/.../policy/ManifestPolicy.kt` -- `allowedRuntimePermissions(debuggable: Boolean)`, drop the name constant, update KDoc -- AC 3.
- `build-logic/.../tasks/CheckMergedManifestTask.kt` -- replace `buildType` with required `@Input debuggable: Property<Boolean>` -- AC 3.
- `build-logic/.../QualityConventionPlugin.kt` -- `debuggable.set(variant.debuggable)` -- AC 3.
- `build-logic/build.gradle.kts` -- add a resolvable runtime configuration holding AGP, KGP and detekt and feed it to `pluginUnderTestMetadata` -- AC 1.
- `build-logic/src/test/.../functional/AndroidFixture.kt` (new) -- reusable TestKit fixture: writes settings, `build.gradle.kts`, manifest; resolves SDK from `ANDROID_HOME` or `../local.properties`; runs Gradle -- AC 1, `epic-2-retro-item-4` reuse.
- `build-logic/src/test/.../functional/CheckMergedManifestFunctionalTest.kt` (new) -- matrix rows 1-5 -- AC 1, AC 3.
- `build-logic/src/test/.../policy/ManifestPolicyTest.kt` -- move to `debuggable` booleans -- AC 3.
- `build-logic/src/test/.../tasks/CheckMergedManifestTaskTest.kt` (new) -- `ProjectBuilder`: unset `debuggable` fails the action -- matrix row 6.
- `app/src/sharedTest/.../session/TestLessons.kt` -- `testFlags(cameraScan: FlagLevel = FlagLevel.GA)` -- AC 2.
- `app/src/test/.../ui/shell/AppShellCameraScanTest.kt` (new) -- matrix rows 7-8 on shipped content through `AppShell` -- AC 2.

**Acceptance Criteria:**
- Given a Gradle TestKit fixture whose manifest declares `CAMERA`, when `checkReleaseMergedManifest` runs, then it fails, and `checkDebugMergedManifest` on the same fixture passes.
- Given `CAMERA_SCAN` at BETA, when `AppShell` opens the Stage 6 lesson in physical mode and its Objective is met, then the camera wording appears.
- Given an unknown or null build type, when the manifest policy runs, then it decides from `variant.debuggable` alone and an unset value fails the task, so no build type can bypass the release rule.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 24 findings — high 0, medium 8, low 10, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: `release { isDebuggable = true }` passes the gate and nothing tests it — real; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it. An override in `app/build.gradle.kts` stays uncovered (Residual risks).
  - `[low]` `[patch]` Blind: every variant's merged manifest is identical, so wrong-variant wiring goes unseen — fixed: `RECORD_AUDIO` only in `src/debug`, release SUCCESS, debug FAILED naming it.
  - `[medium]` `[patch]` Blind: `benchmark` lost its only direct coverage — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[low]` `[patch]` Blind: `assertRejectsCamera` searches the whole log — fixed: asserts the policy's "Manifest declares runtime permission" message. Reading the report file was not added: the task outcome and message already identify the failure.
  - `[low]` `[reject]` Blind: AppShell driving sequence copied, two-step finale hard-coded — the existing helper is private and tied to `LessonScreen` setup; a shared helper is more than a correction for a flow that rarely changes.
  - `[low]` `[reject]` Blind: the test pins exact Learner copy — the AC's observable is the wording, 8.7 tests pin the same strings, and copy edits are rare.
  - `[low]` `[reject]` Blind: TestKit home uncached, no per-test timeout — first run costs about 30 s, no hang has occurred, and the job has a 45-minute limit.
  - `[low]` `[reject]` Blind: fixture cannot run the JVM half of `checkLearnerText` — `epic-2-retro-item-4` is a separate open item; the shared runner and plugin classpath hold, and adding Kover is a one-liner when that item is built.
  - `[medium]` `[patch]` Edge: `release` debuggable with CAMERA passes — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[false]` `[reject]` Edge: GA is never driven, so a BETA-only regression passes — `PhysicalLessonScreenTest` story8_7 covers GA at the prompt, and `AppShell` forwards the level unmodified.
  - `[low]` `[reject]` Edge: `deleteRecursively` result ignored — names are unique per test and the directory is under `build/`; a guard protects a state not seen.
  - `[low]` `[reject]` Edge: `ANDROID_SDK_ROOT` or a relative `sdk.dir` unsupported — AGENTS.md prescribes `ANDROID_HOME`; the failure is a loud named error.
  - `[medium]` `[patch]` Edge: `benchmark` contract deleted — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[medium]` `[patch]` Edge: AC 3 claim exceeds what `debuggable` enforces — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[medium]` `[patch]` Verification: nothing pins the real `release` and `benchmark` variants as non-debuggable — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[medium]` `[patch]` Verification other: `release` debuggable plus CAMERA passes — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[false]` `[reject]` Verification note: no `testReleaseUnitTest` report for `AppShellCameraScanTest` — `./gradlew build --dry-run` plans only `:app:testDebugUnitTest`, so no such report is expected.
  - `[false]` `[reject]` Intent: the `check` dependency edge is unexercised — `ci.yml` asserts the tasks are in the `build` plan, and this diff does not change that wiring.
  - `[medium]` `[patch]` Intent: "a release can never ship" versus a rule keyed only on `debuggable` — same entry as the first row; fixed: new functional test applies the real `cubentia.release` and asserts `checkReleaseMergedManifest` and `checkBenchmarkMergedManifest` fail naming CAMERA; a mutation setting `isDebuggable = true` in `ReleaseConventionPlugin` failed it.
  - `[low]` `[reject]` Intent: TestKit proves CAMERA only — the other permissions go through the same task and policy path, and `ManifestPolicyTest` covers them; the new `RECORD_AUDIO` row also reaches the task.
  - `[false]` `[reject]` Intent: wording appears later than "opens" — `physicalObjectiveMetCopy` shows this copy only at the finale's Objective-met, so no other observation point exists.
  - `[low]` `[reject]` Intent: Practice and skip-check call sites unexercised — the AC names the Stage 6 Lesson; those sites never show the finale prompt.
  - `[false]` `[reject]` Intent: null build type is tested by a substitute — `variant.debuggable` is a non-null Boolean and the name no longer reaches the policy, so no null path exists to test.
  - `[false]` `[reject]` Intent: `checkLearnerText` test not delivered — the AC requires only shared setup, and `epic-2-retro-item-4` stays a separate open item.

## Design Notes

`variant.debuggable` is a non-null Boolean on `ApplicationVariant`, so a null build type can no longer reach the policy. Play rejects debuggable artifacts at upload, which covers a `release { isDebuggable = true }` misconfiguration; the debuggable key is what the story names, so the policy does not add a name check back.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL, functional tests executed.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** The AD-13 manifest gate now decides from `variant.debuggable`, with a required task input and no build-type name. TestKit tests run the real `check<Variant>MergedManifest` tasks on real merged manifests, including the real `cubentia.release` configuration, and an `AppShell` test proves `CAMERA_SCAN` reaches the Stage 6 finale prompt.

**Files changed:**
- `build-logic/.../policy/ManifestPolicy.kt`, `tasks/CheckMergedManifestTask.kt`, `QualityConventionPlugin.kt` -- key on `debuggable`.
- `build-logic/build.gradle.kts` -- plugin classpath for TestKit only; SDK levels passed to tests.
- `build-logic/src/test/.../functional/AndroidFixture.kt`, `CheckMergedManifestFunctionalTest.kt` -- reusable fixture, 6 gate tests.
- `build-logic/src/test/.../tasks/CheckMergedManifestTaskTest.kt` -- unset `debuggable` fails.
- `build-logic/src/test/.../policy/ManifestPolicyTest.kt` -- moved to booleans.
- `app/src/sharedTest/.../session/TestLessons.kt` -- `testFlags(cameraScan)`.
- `app/src/test/.../ui/shell/AppShellCameraScanTest.kt` -- BETA and OFF through `AppShell`.

**Review findings:** 24 total, high 0, medium 8, low 10, false 6. Patched 12 findings in 3 entries. Deferred 0. Rejected 12, each with its reason in the triage log: copied driving sequence, exact copy, TestKit home, JVM fixture reuse, GA coverage, `deleteRecursively`, SDK env, release unit-test report, `check` edge, CAMERA-only TestKit, "opens" timing, other call sites, null substitute, `checkLearnerText`.

**Follow-up review:** `false`. Patched entries by verdict: high 0, medium 1, low 2.

**Verification:** `./gradlew -p build-logic test --rerun-tasks` -- BUILD SUCCESSFUL; 6 functional, 17 policy, 1 task test, 0 failures, 0 skipped. `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL; 404 app debug tests including `AppShellCameraScanTest` (2), 0 failures, 0 skipped. Mutation checks: `isDebuggable = true` on the real release type failed the release/benchmark functional test; earlier, always allowing CAMERA and removing the `AppShell` argument each failed their tests. All reverted. No device or emulator run.

**Residual risks:** A `release { isDebuggable = true }` override placed in `app/build.gradle.kts`, together with a declared `CAMERA`, would still pass the gate, because the story keys the rule on `debuggable`; the new test covers only the convention plugin, and Play's rejection of debuggable uploads is the remaining stop. `Cube3DEmphasisTest` failed once on a frame-clock assertion in the implementer's first full build and passed on rerun; this story does not touch that code and the cause is unknown. The TestKit tests have run only locally, not yet in CI. `epic-2-retro-item-4` stays open.
