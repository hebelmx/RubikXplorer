---
title: "Export the app's privacy facts from the build"
type: 'feature'
created: '2026-11-13'
status: 'done'
baseline_revision: '425dd05cf5a797a1d1a8a364732411d77bb4c003'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The public privacy policy (site repo `hebelmx/cubentia-site`, Story 13.4) must state only what the app really does, but today the app's privacy facts exist only implicitly in `ManifestPolicy` and the SDK denylist. There is no machine-readable file the site's CI can check the page against.

**Approach:** Add a `build-logic` policy that derives privacy facts from the **release** merged manifest and release runtime classpath (denylist result), plus fixed NFR-8 facts. `:app:writePrivacyFacts` writes the committed `docs/privacy-facts.json`; `:app:checkPrivacyFacts`, wired into `check` (so `build`), fails when the committed file differs from what the build derives.

## Boundaries & Constraints

**Always:**
- Facts file at `<rootProject>/docs/privacy-facts.json`, JSON keys in this order: `schemaVersion` (1), `applicationId`, `internetPermission`, `backupAllowed`, `runtimePermissions`, `denylistedSdks`, `account`, `ads`, `analytics`, `crashReporting`, `dataLeavesDevice`, `storage`. Output is deterministic (2-space indent, sorted arrays, LF, trailing newline) so the check compares exact text.
- Derived facts come from the release variant only: `internetPermission` = manifest declares `android.permission.INTERNET`; `backupAllowed` = `<application android:allowBackup>` is not `"false"` (missing counts as allowed, the Android default); `runtimePermissions` = sorted distinct permissions where `ManifestPolicy.isRuntimePermission` is true; `denylistedSdks` = sorted release runtime coordinates (`group:name:version`) matched by `SdkDenylistPolicy.match`. `applicationId` = release `variant.applicationId`.
- Fixed facts (NFR-8), constants in the policy, changeable only by editing build-logic in a reviewed PR: `account`, `ads`, `analytics`, `crashReporting`, `dataLeavesDevice` all `false`; `storage` = `"app-private"`.
- The `checkPrivacyFacts` failure message names the task path of `writePrivacyFacts` (e.g. `:app:writePrivacyFacts`) and the site repository's privacy page (`https://hebelmx.github.io/cubentia-site/privacy/`, repo `hebelmx/cubentia-site`), and shows the expected vs committed content. A missing committed file fails with the same guidance, not a Gradle validation error.
- Gates live in `build-logic/src/main/kotlin/dev/cubentia/buildlogic/` (AGENTS.md); wired by `QualityConventionPlugin`.

**Never:**
- No app code or app manifest changes; no `app/build.gradle.kts` changes.
- Do not make `build` depend on `writePrivacyFacts` (the build must check, never regenerate).
- No new dependencies (hand-write the JSON; no JSON library).
- Do not change `ManifestPolicy` / `SdkDenylistPolicy` rules (reuse their helpers only).
- No site-repo work (Story 13.4) and no AGENTS.md refresh (post-merge task).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| v1.0 app | real release manifest, clean classpath | `writePrivacyFacts` writes file with `false`, `false`, `[]`, `[]`; `checkPrivacyFacts` passes | none |
| INTERNET added | fixture manifest adds INTERNET | derived `internetPermission: true`; check against v1.0 file fails | message names writePrivacyFacts + site privacy page |
| backup allowed | `allowBackup="true"` | `backupAllowed: true`; check fails | same message |
| runtime permission | e.g. CAMERA in main manifest | `runtimePermissions: ["android.permission.CAMERA"]`; check fails | same message |
| missing file | no `docs/privacy-facts.json` | check fails | same guidance, says file is missing |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/QualityConventionPlugin.kt` -- `onVariants` registers `check<Variant>MergedManifest` / `check<Variant>SdkDenylist` from `SingleArtifact.MERGED_MANIFEST` and `variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(CheckSdkDenylistTask::coordinates)`. Add the two privacy tasks here for `variant.name == "release"` only, and `check.dependsOn(checkPrivacyFacts)`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/ManifestPolicy.kt` -- reuse `INTERNET`, `isRuntimePermission`; its XML parsing (secure DocumentBuilderFactory, `childElements`) is private — replicate the same safe parsing settings in the new policy.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/SdkDenylistPolicy.kt` -- reuse `match(coordinate)`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckMergedManifestTask.kt`, `CheckSdkDenylistTask.kt` -- task style to mirror (abstract properties, `@CacheableTask`, report file, `GradleException`).
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AndroidFixture.kt` -- TestKit fixture; `manifest()` hardcodes `allowBackup="false"` and there is no `applicationId`; overwrite the manifest with `write(...)` and add an optional `defaultConfig` extra-lines parameter so a fixture can set `applicationId = "com.exxerpro.cubentia"`.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckMergedManifestFunctionalTest.kt` -- functional test pattern. Tests run from `build-logic/`, so the committed file is `../docs/privacy-facts.json`.
- `.github/workflows/ci.yml:42-52` -- "Verify build wires the gates" task list; add `:app:checkPrivacyFacts`.
- `app/build.gradle.kts` -- read-only; applies `cubentia.quality`; applicationId `com.exxerpro.cubentia`.
- `gradle.properties` -- configuration cache is not enabled; still avoid `project` access inside task actions.

## Tasks & Acceptance

**Execution:**
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicy.kt` -- new: `PrivacyFacts` data + `derive(applicationId, manifestXml, runtimeCoordinates)` + deterministic `toJson()` + fixed-fact constants + site URL constant -- single source for derivation and format.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/WritePrivacyFactsTask.kt` -- new: inputs applicationId, merged manifest, runtime coordinates; `@OutputFile` facts file; writes `toJson()` -- regenerates the committed file.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckPrivacyFactsTask.kt` -- new: same inputs plus committed file (`@InputFiles`, tolerates missing), `writeTaskPath` input, report `@OutputFile`; throws `GradleException` with the guidance message on mismatch/missing -- the stale-file gate.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/QualityConventionPlugin.kt` -- register `writePrivacyFacts` and `checkPrivacyFacts` for the release variant; `checkPrivacyFacts.mustRunAfter(writePrivacyFacts)`; `check` depends on `checkPrivacyFacts`.
- `docs/privacy-facts.json` -- generate with `./gradlew :app:writePrivacyFacts` and commit.
- `.github/workflows/ci.yml` -- add `:app:checkPrivacyFacts` to the gate list.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicyTest.kt` -- new unit tests: v1.0 manifest → `false,false,[],[]`; INTERNET, `allowBackup="true"`, missing allowBackup, runtime permission, denylisted coordinate each change the right fact; JSON exact shape/ordering.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AndroidFixture.kt` -- add optional `defaultConfig` lines parameter.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/PrivacyFactsFunctionalTest.kt` -- new TestKit tests: clean fixture with app's applicationId writes exactly the committed `../docs/privacy-facts.json` and `checkPrivacyFacts` passes; fixtures adding INTERNET, `allowBackup="true"`, or a runtime permission, with the v1.0 file copied into `docs/`, fail `checkPrivacyFacts` with the message naming `:writePrivacyFacts` and the site privacy URL, and `writePrivacyFacts` output differs from the v1.0 file; missing file fails.

**Acceptance Criteria:**
- Given the release merged manifest and release SDK-denylist result, when `:app:writePrivacyFacts` runs, then `docs/privacy-facts.json` contains all twelve keys with derived values `false`, `false`, `[]`, `[]` for v1.0.
- Given the committed file, when `./gradlew build` runs, then `:app:checkPrivacyFacts` runs and passes; when the file is stale it fails with a message naming `:app:writePrivacyFacts` and the site's privacy page; and `ci.yml`'s "Verify build wires the gates" lists `:app:checkPrivacyFacts`.
- Given TestKit fixtures, when `./gradlew -p build-logic test` runs, then INTERNET, `allowBackup="true"` and a runtime permission each produce facts that differ from the v1.0 file and the check fails on them.

## Spec Change Log

## Review Triage Log

### 2026-11-13 — Review pass
- verdicts: 12 findings — high 0, medium 3, low 4, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind-hunter) `docs/privacy-facts.json` has no LF rule; with `core.autocrlf=true` a CRLF checkout fails the exact-text check on an unchanged file — real: `* text=auto` lets Git convert to CRLF on Windows, and this repo lives on a Windows drive. Fixed: `.gitattributes` adds `docs/privacy-facts.json text eol=lf`.
  - `[low]` `[reject]` (blind-hunter) CI's wiring step does not assert `writePrivacyFacts` is absent from `build` — real but unlikely (someone would have to add `dependsOn` deliberately), and the fix adds a new guard branch to CI; the TestKit test already asserts the plugin never wires the writer into `build`.
  - `[medium]` `[patch]` (blind-hunter) no functional test changes release runtime dependencies, so the classpath wiring for `denylistedSdks` is untested — grouped with the verification-gap finding below (same missing test). Fixed: new TestKit test publishes a denylisted module to a fixture-local Maven repo as `implementation` and a second one as `debugImplementation`; asserts the exact release coordinate is exported, the debug one is not, and the baseline file fails the check.
  - `[low]` `[patch]` (blind-hunter) every fixture uses the committed applicationId, so a hard-coded id would pass — real but cheap to close. Fixed: the dependency fixture uses `applicationId = "dev.cubentia.other"` and asserts it in the generated file.
  - `[false]` `[reject]` (blind-hunter) up-to-date/cache could hide drift after a passing run — the check declares the manifest, the coordinates, the applicationId and the committed file (`@InputFiles`) as inputs, so any change re-runs it; a failed run is never cached, so a cache hit always means these exact inputs passed.
  - `[medium]` `[patch]` (verification-gap) the release dependency wiring at the privacy-task boundary is unverified; replacing the coordinates provider with an empty list would pass every test — pre-verified gap. Fixed by the same new TestKit test (shared root cause with the blind-hunter row above).
  - `[false]` `[reject]` (intent-alignment a) the real app's release build is not exercised, only fixtures — `./gradlew build` runs `:app:checkPrivacyFacts` on the real app (report: "Privacy facts match."), and CI runs the same build and lists the task.
  - `[low]` `[reject]` (intent-alignment b) `denylistedSdks` re-evaluates `SdkDenylistPolicy.match` on the same coordinates instead of reading the `checkReleaseSdkDenylist` report — same policy and same input provider, so they cannot disagree; rewiring to parse the gate's report adds coupling for no observable change.
  - `[low]` `[reject]` (intent-alignment c) the fixed-fact encoding (`false` booleans, `storage: "app-private"`) is not checked against the site's expectations — the site check (Story 13.4) is defined as matching `data-value` to whatever this file holds, so the file is the source; nothing to fix in this story.
  - `[false]` `[reject]` (intent-alignment d) the failure message's privacy URL may not exist yet — it is the permanent URL fixed by Epic 13 and verified in Story 13.1; Story 13.4 publishes the page there.
  - `[false]` `[reject]` (intent-alignment e) the fixtures replace the manifest rather than add to it — equivalent on a fixture whose baseline manifest is the same minimal manifest; the auditor says so itself.
  - `[false]` `[reject]` (intent-alignment f) extra surfaces (report file, `defaultConfig` fixture hook) and a `git status` entry for `validate-release-inputs.sh` — the report mirrors the other gates; the script has no content diff (stale stat only) and is not part of the change.

### 2026-11-13 — Review pass (follow-up)
- verdicts: 15 findings — high 0, medium 0, low 6, false 9, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind-hunter) CI's wiring step does not reject `writePrivacyFacts` in the build plan — carried: same claim as the first pass; the TestKit test still asserts the writer is absent from the `build` plan.
  - `[false]` `[reject]` (blind-hunter) no pass → edit/delete → fail sequence, so up-to-date/cache could hide drift — carried: the committed file is an `@InputFiles` input, so any edit or deletion re-runs the check, and failed runs are never cached.
  - `[false]` `[reject]` (blind-hunter) fixtures apply the plugin at the root, so the `:app` layout (root `docs/`, `:app:writePrivacyFacts`) is untested — the real `./gradlew :app:checkPrivacyFacts` ran this pass against the root `docs/privacy-facts.json` and reported "Privacy facts match."; the subproject path is `"$path:writePrivacyFacts"`, and CI's gate list names `:app:checkPrivacyFacts`.
  - `[low]` `[reject]` (blind-hunter) `assertGuidance` checks only the `Expected:`/`Committed:` headings, not their contents — the message is three visible lines that interpolate `expected` and `committed` directly; a swap is unlikely, and stronger asserts only harden tests for an improbable regression.
  - `[false]` `[reject]` (blind-hunter) no transitive denylisted dependency fixture, so full-graph coverage is unprotected — `CheckSdkDenylistTask.coordinates` (pre-existing, shared with the denylist gate) walks every `ResolvedDependencyResult` breadth-first, so transitive SDKs are included.
  - `[low]` `[reject]` (blind-hunter) manifest fixtures assert only that the regenerated file differs, not the exact field — each fixture changes one manifest fact, and `PrivacyFactsPolicyTest` asserts the exact value of each field; extra asserts add little.
  - `[false]` `[reject]` (blind-hunter) the dependency fixture changes applicationId and deps together, so a checker ignoring deps would be masked — writer and checker share the same `coordinates` provider and the same `toJson()`, and the writer output asserts the exact coordinate, so the checker cannot ignore dependencies separately.
  - `[false]` `[reject]` (edge-case-hunter) unresolved release runtime dependencies are silently dropped from the facts — release manifest merging resolves the release runtime classpath's artifacts, so an unresolved dependency fails `processReleaseMainManifest` (the merged-manifest input) before either privacy task runs, and `build` fails at `assemble` too; the helper is pre-existing and shared with the denylist gate.
  - `[false]` `[reject]` (intent-alignment) tests never run the real `:app` — carried: the real `:app:checkPrivacyFacts` runs under `build` and passed again this pass.
  - `[low]` `[reject]` (intent-alignment) the `:app:`-prefixed writer path in the message is never exercised by a test — it is a one-line string template; covering it would need a new multi-project fixture, which is more than a direct correction for an unlikely defect.
  - `[low]` `[reject]` (intent-alignment) no test asserts `repo hebelmx/cubentia-site` in the message — it is a literal in the message string next to the asserted `SITE_URL`; an unlikely regression.
  - `[false]` `[reject]` (intent-alignment) the diff does not show the site can consume the file — the intent excludes site-repo work (Story 13.4).
  - `[false]` `[reject]` (intent-alignment) fixed facts are not checked against derived facts (e.g. `dataLeavesDevice: false` with `internetPermission: true`) — the intent defines them as constants changeable only by a reviewed build-logic PR; this is the specified behavior.
  - `[false]` `[reject]` (intent-alignment) `<uses-permission-sdk-23>` is also counted — that element declares a permission too, so counting it is what "manifest declares" means; not a defect.
  - `[low]` `[reject]` (intent-alignment) INTERNET/backup/CAMERA functional cases assert only a difference — same claim as the blind-hunter row above, same reason.

## Verification

**Commands:**
- `./gradlew -p build-logic test` -- expected: all tests pass, including the new unit and functional tests.
- `./gradlew :app:writePrivacyFacts && git diff --exit-code docs/privacy-facts.json` -- expected: no diff after commit.
- `./gradlew spotlessCheck build` -- expected: success; `./gradlew build --dry-run | grep '^:app:checkPrivacyFacts '` prints the task.

## Auto Run Result

Status: done

**Summary.** `build-logic` now derives Cubentia's privacy facts from the release merged manifest and the release runtime classpath (through `SdkDenylistPolicy`), plus fixed NFR-8 facts. `:app:writePrivacyFacts` writes the committed `docs/privacy-facts.json` (v1.0: `internetPermission: false`, `backupAllowed: false`, `runtimePermissions: []`, `denylistedSdks: []`). `:app:checkPrivacyFacts` runs under `check`/`build`. When the file is stale or missing it fails with a message that names `:app:writePrivacyFacts`, `https://hebelmx.github.io/cubentia-site/privacy/` and `hebelmx/cubentia-site`, and shows the expected and committed content. CI's "Verify build wires the gates" lists the task.

**Files changed.**
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicy.kt`: new. Derivation, fixed facts and deterministic JSON.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/WritePrivacyFactsTask.kt`: new. Writes the facts file.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckPrivacyFactsTask.kt`: new. The stale-file gate.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/QualityConventionPlugin.kt`: registers both tasks for the release variant and wires the check into `check`.
- `docs/privacy-facts.json`: new, generated and committed.
- `.github/workflows/ci.yml`: adds `:app:checkPrivacyFacts` to the gate list.
- `.gitattributes`: keeps the facts file LF on every checkout (review patch).
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicyTest.kt`: new unit tests.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/PrivacyFactsFunctionalTest.kt`: new TestKit tests. They cover the clean build, INTERNET, backup, CAMERA, a missing file, and release vs debug dependencies with a custom applicationId.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AndroidFixture.kt`: adds an optional `defaultConfig` hook.

**Review.** 12 findings. Three were patched as two entries: the LF rule (medium), and one new TestKit test that covers the release dependency wiring (medium, a blind-hunter finding plus a verification-gap finding) and the custom applicationId (low). Nothing was deferred. Rejected:
- CI does not assert that the writer stays out of `build`. Low: unlikely, the fix would add a guard, and the TestKit test already covers it.
- Re-evaluating the denylist instead of reading the gate's report. Low: same policy, same input.
- Fixed-fact encoding not checked against the site. Low: the file is the source the site check reads.
- Five false findings: cache hiding drift, real app untested, URL not yet live, add vs replace manifest, and extra surfaces.

**Follow-up review recommended: true.** Patched counts: high 0, medium 2, low 1. Unverified risk: the `.gitattributes` LF rule and the CRLF scenario it guards were verified only on Linux/WSL. No Windows `core.autocrlf=true` checkout was exercised.

**Verification.**
- `./gradlew -p build-logic test --rerun-tasks`: success, 132 test cases, 0 failures; `PrivacyFactsFunctionalTest` 4/4 and `PrivacyFactsPolicyTest` 4/4.
- `./gradlew :app:writePrivacyFacts`: success, and the file is byte-identical to the committed one.
- `./gradlew spotlessCheck build`: success, and the app report reads "Privacy facts match."
- `./gradlew build --dry-run`: the plan lists `:app:checkPrivacyFacts` and not `:app:writePrivacyFacts`.

**Residual risks.** The site-side check (Story 13.4) has not consumed this file yet. The AGENTS.md pointer to the facts file is a post-merge `bmad-project-context` refresh.

### Follow-up review pass (2026-11-13)

Four review layers ran again on the full diff since `425dd05cf5a797a1d1a8a364732411d77bb4c003`. They reported 15 findings: 6 low and 9 false. Nothing was patched or deferred, and no code changed. Rejected:
- Low: CI does not assert the writer is absent (carried), guidance asserts only headings, manifest fixtures assert only a difference (reported twice), the `:app:` path untested, the repo name unasserted. All are unlikely regressions; fixing them only hardens tests or needs a new fixture.
- False: cache hiding drift (carried), root-only fixtures, transitive SDKs, the shared dependency/applicationId fixture, unresolved dependencies dropped, real app untested (carried), site consumption (out of scope), fixed vs derived facts (specified), `uses-permission-sdk-23` counted.

**Follow-up review recommended: false.** Patched counts: high 0, medium 0, low 0. The work has converged.

**Verification (this pass).** `./gradlew :app:writePrivacyFacts :app:checkPrivacyFacts`: success, `git diff --exit-code docs/privacy-facts.json` clean, report "Privacy facts match."

**Residual risks.** Unchanged: the Windows `core.autocrlf=true` checkout is still not exercised, and Story 13.4 has not consumed the file yet.
