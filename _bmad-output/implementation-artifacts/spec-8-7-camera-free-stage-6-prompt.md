---
title: 'Story 8.7: Camera-free Stage 6 prompt'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: '374889b8bd6367136ecceec3a060aca479848301'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      The per-variant merged-manifest task wiring (QualityConventionPlugin -> CheckMergedManifestTask.buildType) has no test.
    evidence: |-
      ManifestPolicyTest calls the pure policy only; no TestKit/functional test asserts checkReleaseMergedManifest receives "release" and fails on CAMERA. Today's release manifest has no permissions, so a mis-wiring would pass silently.
    location: >-
      build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/QualityConventionPlugin.kt:29
    severity: medium
  - summary: >-
      No AppShell-level test proves CAMERA_SCAN is passed from FeatureFlags into LessonScreen.
    evidence: |-
      The 8.7 tests pass cameraScan to LessonScreen directly; deleting the AppShell argument would fall back to OFF with no failing test. Matters only once CAMERA_SCAN rises above OFF in a shipping build (v1.1 camera story).
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt:181
    severity: medium
  - summary: >-
      AGENTS.md no longer tells agents that camera code belongs in debug-only source sets such as app/src/spikeScan/.
    evidence: |-
      The removed Policy line (removal required by AC 4) carried that pointer; it is now enforced only by the release manifest check at build time. Agent-context edits route to bmad-project-context.
    location: >-
      AGENTS.md
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 8.6 shipped one end-of-Stage-6 physical prompt, "Check it with the camera, or tell me if it is.", but v1.0 is camera-free, so the release app promises a camera check that does not exist. The AD-13 manifest gate also still allows `CAMERA` in every variant, so nothing stops a runtime permission reaching the v1.0 release manifest.

**Approach:** Keep two content-string variants and pick one from the `CAMERA_SCAN` flag level (OFF → "Compare it with the picture" copy; BETA/GA → camera copy), threaded from `AppShell` into `LessonScreen`. Make `ManifestPolicy` take the allowed runtime permissions per build type: none for release (and benchmark, which carries release levels), `CAMERA` only for debug; then drop the now-enforced `AGENTS.md` Policy line.

## Boundaries & Constraints

**Always:**
- Both prompts are content strings in `core/domain/src/main/resources/content/strings/en.json` (NFR-10), resolved via `strings.resolve(TextKey(...))`; exact copy:
  - OFF: "Your cube should be solved now. Compare it with the picture, then tell me if it is."
  - BETA/GA: "Your cube should be solved now. Check it with the camera, or tell me if it is."
- The flag is read only through the `FeatureFlags` port (`flags.level(FeatureFlag.CAMERA_SCAN)`), never `BuildConfig`; `LessonScreen` defaults to `FlagLevel.OFF`.
- 8.6 milestone gating (`physicalFirstSolveConfirmed`, `physicalSolveUnconfirmed`, *Yes, it's solved*, *I did something else*) stays byte-for-byte unchanged in behavior.
- Release and benchmark merged manifests fail the check on any runtime permission, `CAMERA` included; debug may declare `CAMERA` (spikeScan source set) and nothing else runtime.

**Never:**
- No Quick Check UI, camera entry, scan code, or new flag; do not change `FeatureFlagLevels` levels.
- Do not edit planning artifacts, `_bmad/`, `.claude/skills/`.
- Do not change the non-Stage-6 physical copy (`msg.physical.objective-met`) or Solution Playback copy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| v1.0 release | `CAMERA_SCAN=OFF`, physical, `lesson-06-03` objective met | Panel shows the "Compare it with the picture" copy, *Yes, it's solved* and *I did something else*; no node text contains "camera" or "Quick Check" | None |
| Camera build | `CAMERA_SCAN=BETA` or `GA`, same state | Panel shows the "Check it with the camera" copy | None |
| Release manifest with CAMERA | merged manifest of `release`/`benchmark` declares `android.permission.CAMERA` | `ManifestPolicy` returns a violation naming it; the `check<Variant>MergedManifest` task fails | GradleException |
| Debug manifest with CAMERA | `debug` merged manifest declares `CAMERA` | No violation | None |
| Debug manifest with other runtime permission | `debug` declares e.g. `RECORD_AUDIO` | Violation naming it | GradleException |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json:37` -- `msg.physical.first-solve-prompt` currently holds the camera copy; becomes the camera-free copy, add `msg.physical.first-solve-prompt-camera` with the camera copy.
- `app/src/main/res/values/strings.xml:50` -- `physical_first_solve_prompt` duplicate, unused by any Kotlin (`grep R.string.physical_first_solve_prompt` finds nothing); remove it.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- `LessonScreen(...)` (line ~139, add `cameraScan: FlagLevel = FlagLevel.OFF`, document in KDoc), `LessonBody(...)` (line ~448, add a `camera: Boolean` param, passed at the call ~319 as `cameraScan != FlagLevel.OFF`), `stepCard(...)` (line ~775, add param; both branches at ~832 and ~923 choose the key for `lesson-06-03`). Reuse one small private helper for the key choice to avoid duplicating the branch.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt:173,210,255` -- three `LessonScreen(` call sites; pass `cameraScan = flags.level(FeatureFlag.CAMERA_SCAN)` (pattern: `adaptiveAssistance = flags.level(FeatureFlag.ADAPTIVE_ASSISTANCE)` at line 180).
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt:71,350-383` -- `setLessonContent` helper (add optional `cameraScan` param) and the 8.6 AC1 test that asserts the camera copy; switch it to the OFF copy plus no-"camera" assertion, add a BETA/GA variant test.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt` -- add a test pinning both prompt keys' exact text in `bundle.strings` (msg.* keys are exempt from the voice guide).
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/ManifestPolicy.kt` -- `allowedRuntimePermissions` is a single global set `{CAMERA}`; replace with a per-build-type lookup (debug → `{CAMERA}`, everything else → empty) and make `check(manifestXml, allowedRuntimePermissions)` take the set; update class KDoc.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/CheckMergedManifestTask.kt` -- add `@get:Input buildType: Property<String>` (or the allowed set as input) and pass it to the policy.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/QualityConventionPlugin.kt:25` -- set the task's build type from `variant.buildType`.
- `build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/ManifestPolicyTest.kt` -- existing tests assume CAMERA always allowed; update and add release/debug cases.
- `AGENTS.md` -- Policy bullet "Never add a runtime permission to the release manifest in v1.0, `CAMERA` included ..." is removed (AC 4).
- Read-only: `build-logic/.../FeatureFlagLevels.kt` (release OFF, debug GA), `core/application/.../port/FeatureFlags.kt`, `app/build.gradle.kts:94-97` (spikeScan adds CAMERA to the debug manifest only).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- set `msg.physical.first-solve-prompt` to the camera-free copy; add `msg.physical.first-solve-prompt-camera` with the camera copy -- AC 1-3.
- `app/src/main/res/values/strings.xml` -- delete `physical_first_solve_prompt` -- AC 3 duplicate removal.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- thread `cameraScan` level to `stepCard`, choose the key by flag -- AC 1-2.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- pass `flags.level(FeatureFlag.CAMERA_SCAN)` at all three call sites -- AC 1-2.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt` -- OFF test (copy, both buttons, no "camera"/"Quick Check" text, milestone only after *Yes, it's solved*) and a BETA and GA camera-copy test -- AC 1-3.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt` -- pin both variants -- AC 3.
- `build-logic/.../ManifestPolicy.kt`, `CheckMergedManifestTask.kt`, `QualityConventionPlugin.kt`, `ManifestPolicyTest.kt` -- per-build-type allowed runtime permissions and tests -- AC 4.
- `AGENTS.md` -- remove the v1.0 runtime-permission Policy line -- AC 4.

**Acceptance Criteria:**
- Given `CAMERA_SCAN` is OFF, when Stage 6's Objective is met in Physical-cube Mode, then the panel reads "Your cube should be solved now. Compare it with the picture, then tell me if it is." and offers *Yes, it's solved* and *I did something else*; no camera wording or Quick Check entry appears.
- Given `CAMERA_SCAN` is BETA or GA, when the same happens, then "Your cube should be solved now. Check it with the camera, or tell me if it is." shows.
- Given the two variants, when content tests run, then both are content keys, the `strings.xml` duplicate is gone, `ShippedContentTest` covers both, and 8.6 milestone gating is unchanged.
- Given `ManifestPolicy`, when the release manifest declares any runtime permission, `CAMERA` included, then the check fails; debug may declare `CAMERA`; the `AGENTS.md` Policy line is removed.

## Spec Change Log

## Review Triage Log

### 2026-11-08 — Review pass
- verdicts: 21 findings — high 0, medium 5, low 12, false 4, maybe-false 0
- findings:
  - `[false]` `[reject]` (edge) Debug builds (CAMERA_SCAN=GA) show camera wording with no camera check — AC 2 mandates the camera copy whenever CAMERA_SCAN is BETA/GA; debug=GA is pre-existing AD-15 config in FeatureFlagLevels, not this change.
  - `[false]` `[reject]` (blind) Debug builds still promise a camera check — same refutation as above.
  - `[low]` `[reject]` (blind) No test cross-checks FeatureFlagLevels against ManifestPolicy — no current harm (release CAMERA_SCAN=OFF, allowed set empty); fix adds new test machinery.
  - `[low]` `[reject]` (blind) No bundle-wide "no camera wording" guard — grep shows the camera key is the only camera/scan wording in en.json and strings.xml; guard would be new machinery for a hypothetical.
  - `[medium]` `[defer]` (blind) Build-type wiring of the manifest task untested — real gap; no TestKit in build-logic; deferred.
  - `[low]` `[reject]` (blind) BETA/GA tests do not tap *Yes, it's solved* — milestone gating does not read the camera flag; the OFF test covers the full flow.
  - `[low]` `[reject]` (blind) Practice branch prompt choice untested — both branches call the same helper `physicalObjectiveMetCopy`.
  - `[low]` `[reject]` (blind) Other 8.6 test still duplicates setup — cosmetic test refactor.
  - `[low]` `[patch]` (blind) Renamed 8.6 test lost its story8_6 traceability — renamed to `story8_6_ac1_story8_7_ac1_cameraOff_stage6PhysicalObjectiveMet_showsCameraFreePrompt_milestoneOnlyAfterYesItsSolved`.
  - `[low]` `[reject]` (blind) "lesson-06-03" literal repeated — pre-existing duplication, not introduced by this change.
  - `[low]` `[patch]` (blind) `LessonBody` `camera` default hides a missed argument — default removed; parameter is now required like `beta`.
  - `[low]` `[reject]` (blind) Violation message does not name the build type — needs an extra parameter; developers rarely meet it and the task error already names the variant.
  - `[low]` `[defer]` (blind) AGENTS.md lost the spikeScan location pointer — agent-context file edit; deferred.
  - `[medium]` `[defer]` (verification-gap) Merged-manifest task never tested with the variant's real build type — grouped with the blind wiring finding; deferred.
  - `[medium]` `[defer]` (verification-gap) No test that AppShell passes CAMERA_SCAN to LessonScreen — only matters when the flag rises above OFF in a shipping build; deferred.
  - `[false]` `[reject]` (verification-gap other) Camera prompt offers a nonexistent check in debug — AC 2 requires it at GA; pre-existing debug flag levels.
  - `[medium]` `[defer]` (intent) AppShell -> LessonScreen flag wiring untested — grouped with verification-gap AppShell finding.
  - `[medium]` `[defer]` (intent) AC 4 "CI fails" exercised only at the policy unit surface — grouped with the manifest wiring finding.
  - `[low]` `[reject]` (intent) BETA/GA milestone flow not tested — grouped with blind BETA/GA finding.
  - `[low]` `[patch]` (intent) 8.6-labelled test no longer exists — grouped with the rename patch.
  - `[false]` `[reject]` (intent) Debug may declare CAMERA without spikeScan active — debug's merged manifest only gains CAMERA via the opt-in spikeScan source set (app/build.gradle.kts:97); AC 4 explicitly allows debug CAMERA.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (includes `checkReleaseMergedManifest`, `checkDebugMergedManifest`, app Robolectric tests, `:core:domain` tests)

## Auto Run Result

**Summary:** The end-of-Stage-6 physical prompt now picks its copy from `CAMERA_SCAN`: OFF (v1.0 release) shows "Your cube should be solved now. Compare it with the picture, then tell me if it is."; BETA/GA show the camera copy. The AD-13 manifest gate now allows no runtime permission for release/benchmark and only `CAMERA` for debug, and the now-enforced AGENTS.md Policy line is removed.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- camera-free `msg.physical.first-solve-prompt`, new `msg.physical.first-solve-prompt-camera`.
- `app/src/main/res/values/strings.xml` -- removed unused duplicate `physical_first_solve_prompt`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt` -- `cameraScan` param threaded to `stepCard`; `physicalObjectiveMetCopy` helper picks the key.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt` -- passes `flags.level(FeatureFlag.CAMERA_SCAN)` at all three call sites.
- `build-logic/.../policy/ManifestPolicy.kt` -- per-build-type `allowedRuntimePermissions(buildType)`; `check` takes the allowed set.
- `build-logic/.../tasks/CheckMergedManifestTask.kt`, `QualityConventionPlugin.kt` -- `buildType` task input from `variant.buildType`.
- `app/src/test/.../PhysicalLessonScreenTest.kt`, `core/domain/src/test/.../ShippedContentTest.kt`, `build-logic/src/test/.../ManifestPolicyTest.kt` -- OFF/BETA/GA prompt tests, both keys pinned, release/benchmark/debug policy cases.
- `AGENTS.md` -- removed the v1.0 runtime-permission Policy line (AC 4).

**Review findings:** 21 findings. 3 patched (low): test rename for 8.6 traceability (grouped with intent finding), `camera` param made required. 6 deferred (3 entries): manifest-task wiring test (medium), AppShell flag wiring test (medium), AGENTS.md spikeScan pointer (low). Rejected: 4 false (debug camera wording x3, debug CAMERA without spikeScan) and 8 low (flag/policy cross-check, bundle-wide wording guard, BETA/GA milestone tap x2, practice branch test, duplicated test setup, repeated literal, message wording) with reasons in the triage log.

**Follow-up review recommendation:** false — patched entries: high 0, medium 0, low 2 (3 rows).

**Verification:** `./gradlew -p build-logic test` -- BUILD SUCCESSFUL (ManifestPolicyTest 18 tests). `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL after patches (PhysicalLessonScreenTest 20/20, ShippedContentTest 16/16; check{Debug,Release,Benchmark}MergedManifest passed). Matrix audit: rows 1-2 covered by PhysicalLessonScreenTest OFF/BETA/GA tests; rows 3-5 by ManifestPolicyTest.

**Residual risks:** the Gradle task wiring and the AppShell flag wiring are covered only by inspection (deferred); debug builds show the camera wording because debug sets every flag to GA.
