---
title: 'epic-2-retro-item-4: Gradle TestKit test proving checkLearnerText fails on a violating source tree'
type: 'chore'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f30fbbc7684cdb18c86a0a639606ee0fee4bd087'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `checkLearnerText` is only proven by `CheckLearnerTextTaskTest`, which calls the task action through `ProjectBuilder`; no real Gradle build has ever been shown to fail on a violating source tree (Epic 2 retro finding 9, action item 4).

**Approach:** Add one Gradle TestKit (`GradleRunner`) functional test that applies the real `cubentia.android-app` convention plugin to an `AndroidFixture`, runs `checkLearnerText` once on a clean tree (SUCCESS) and once after adding a string resource absent from the real `app/learner-text-baseline.txt` (FAILED, message names the resource).

## Boundaries & Constraints

**Always:** Reuse `AndroidFixture` and the `CheckMergedManifestFunctionalTest` assert style. Copy the repository's real `gradle/libs.versions.toml` and `app/learner-text-baseline.txt` into the fixture (tests run from `build-logic/`), as `AccessibilityLintFunctionalTest` does for the catalog. Assert on the task outcome (`result.task(":checkLearnerText")?.outcome`) and on output text, passing `result.output` as the failure message.

**Never:** Change `CheckLearnerTextTask`, `LearnerTextPolicy`, any convention plugin, `app/learner-text-baseline.txt`, `AndroidFixture` behavior used by other tests, CI workflows, or `sprint-status.yaml`. No new Gradle source set or test task.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean tree | `src/main/res/values/strings.xml` declares only `app_name` (in the baseline) | `checkLearnerText` outcome `SUCCESS` (not `NO_SOURCE`/`SKIPPED`) | No error expected |
| Violating tree | same fixture, plus a `<string name="learner_text_violation">` not in the baseline | build fails; `:checkLearnerText` outcome `FAILED`; output contains `Learner-facing string literals found`, `src/main/res/values/strings.xml:`, and `learner_text_violation` | `buildAndFail` |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckLearnerTextTask.kt` -- read-only. `register()` scans `src/{main,debug,release}/res/values*/*.xml` only when `learner-text-baseline.txt` exists in the project dir; both inputs are `@SkipWhenEmpty`, so the clean case needs a resource present to reach `SUCCESS`. Failure message prefix: `Learner-facing string literals found (NFR-10, AD-12)`; each violation renders as `file:line: string resource outside baseline; ...: "name"` (`LearnerTextPolicy.Violation.toString`).
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/AndroidAppConventionPlugin.kt:50` -- read-only; the real plugin (`cubentia.android-app`) that registers the task. Reads the `libs` catalog (`compile-sdk`, `min-sdk`, `target-sdk`, `jdk`, junit aliases).
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AndroidFixture.kt` -- reuse: `AndroidFixture(name, plugins = ...)`, `write`, `build`, `buildAndFail`. `projectDir` is under `build/functional-fixtures/<name>`.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AccessibilityLintFunctionalTest.kt` -- precedent for `plugins = listOf("cubentia.android-app")` plus copying `../gradle/libs.versions.toml`.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckMergedManifestFunctionalTest.kt` -- assertion pattern to mirror.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/tasks/CheckLearnerTextTaskTest.kt` -- read-only; existing ProjectBuilder test, kept.
- `app/learner-text-baseline.txt` -- read-only; real baseline, contains `app_name`.
- `.github/workflows/ci.yml:77` -- read-only; CI already runs `./gradlew -p build-logic test`, which includes every class under `build-logic/src/test`.

## Tasks & Acceptance

**Execution:**
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckLearnerTextFunctionalTest.kt` -- create a JUnit 5 class with one or two `@Test`s covering both matrix rows on a single fixture (clean build first, then add the violation and `buildAndFail`) -- proves the real plugin + real task in a real Gradle build both pass and fail.

**Acceptance Criteria:**
- Given the fixture applying `cubentia.android-app` with only baseline-listed string resources, when `checkLearnerText` runs via `GradleRunner`, then its outcome is `SUCCESS`.
- Given the same fixture with a new string resource not in the real learner-text baseline, when `checkLearnerText` runs via `GradleRunner`, then the build fails, the task outcome is `FAILED`, and the output names the offending resource and file.
- Given the change, when `./gradlew -p build-logic test` runs, then the new test executes inside the existing `test` task and the whole suite passes.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 8 findings — high 0, medium 0, low 6, false 2, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind-hunter) Test runs `checkLearnerText` directly, so it would not catch the task being unhooked from `check` — out of scope: the intent says "runs checkLearnerText" and "do exactly this and nothing more"; `check` wiring is not part of the ask.
  - `[low]` `[patch]` (blind-hunter) Path and resource name asserted independently, without the line number — replaced with one assertion requiring a single output line containing `src/main/res/values/strings.xml:3:` and `"learner_text_violation"`.
  - `[low]` `[patch]` (edge-case-hunter) Same root cause: resource could be attributed to the wrong file and still pass — fixed by the same single-line assertion.
  - `[false]` `[reject]` (verification-gap) "No verification gaps found" — not a defect; nothing to act on.
  - `[low]` `[reject]` (intent-alignment R2) No Kotlin-literal violation exercised — the intent names the string-resource violation explicitly ("as the existing ProjectBuilder test uses").
  - `[low]` `[patch]` (intent-alignment R3) Line number not asserted — covered by the single-line `:3:` assertion above; `src/debug/res` parity is not requested by the intent.
  - `[low]` `[reject]` (intent-alignment R4) Clean case depends on the real baseline still listing `app_name` — deliberate: the intent asks for "the learner-text baseline"; `app_name` is the app's required resource, and a removal would fail loudly, not silently.
  - `[false]` `[reject]` (intent-alignment) Done-when (3) unverified — refuted: `./gradlew -p build-logic test` ran green after the patch; JUnit XML shows the new test executed with 0 skips/failures.

## Verification

**Commands:**
- `./gradlew -p build-logic test --tests 'dev.cubentia.buildlogic.functional.CheckLearnerTextFunctionalTest'` -- expected: tests pass (and the report in `build-logic/build/test-results/test/` lists them as executed, not skipped).
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Added a Gradle TestKit functional test that applies the real `cubentia.android-app` convention plugin to an `AndroidFixture` carrying the real version catalog and `app/learner-text-baseline.txt`. `checkLearnerText` succeeds when `strings.xml` holds only `app_name`, then fails on the same fixture after a non-baseline `learner_text_violation` resource is added, with the violation line naming file, line and resource.

**Files changed:**
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckLearnerTextFunctionalTest.kt` -- new GradleRunner test (pass then fail on one fixture).

**Review findings:** 8 total. Patched 3 low (one root cause: tie file, line and resource name to one diagnostic line). Deferred 0. Rejected: `check` lifecycle wiring (out of intent scope); Kotlin-literal case (intent names the resource violation); real-baseline dependency (deliberate per intent); "suite unverified" (false, suite ran green). Verification-gap layer: no gaps.

**Follow-up review recommendation:** false (patched: high 0, medium 0, low 3).

**Verification:** `./gradlew -p build-logic test` -- BUILD SUCCESSFUL before and after the patch; `TEST-dev.cubentia.buildlogic.functional.CheckLearnerTextFunctionalTest.xml` tests=1 skipped=0 failures=0; 21 suites, 0 failures, 0 skips. `./gradlew spotlessCheck` -- passed. No production code, baseline, CI workflow or `sprint-status.yaml` changed; the test runs in the existing `build-logic` `test` task that CI calls (`.github/workflows/ci.yml:77`).

**Residual risks:** the clean case relies on `app_name` staying in the real baseline; the fixture needs an Android SDK, like every other functional test here.
