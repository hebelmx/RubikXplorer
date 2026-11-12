---
title: 'Story 7.8: Ship a pilot build to closed testing'
type: 'chore'
created: '2026-11-12'
baseline_revision: '08c96666c4c7d0eea1be846375d48148fdb6c913'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/docs/pruning-tables.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The release-gate instrumentation runs the debug build; no R8/resource-shrunk release APK is exercised on a device before upload.
    evidence: |-
      .github/scripts/run-instrumentation.sh runs connectedDebugAndroidTest and the debug Key Flow APKs. Release differs by R8, shrinking, release DebugOptions and flag levels. Pre-existing gap: no release-variant instrumentation existed before 7.8; the release-equivalent benchmark build is exercised only by Macrobenchmark on devices. A release-APK install-and-launch smoke test (or testBuildType = benchmark) would close it.
    location: >-
      .github/scripts/run-instrumentation.sh
    severity: medium
---

<intent-contract>

## Intent

**Problem:** There is no release pipeline: no signing config, `versionCode` is hard-coded 1, no instrumentation run, no NFR-11 size check, no Play upload, and no written pilot protocol, so no signed Cubentia build can reach the ~8 pilot beginners (OQ-5, PRD §7 gate 4). TEST_ME and ADAPTIVE_ASSISTANCE are still OFF in release, and the spike source sets (spike3d, spikeScan) are compiled by nothing in CI (epic-1 retro item 59).

**Approach:** A `vX.Y.Z` tag runs a new `release.yml`: instrumentation tests on a CI emulator, then a signed R8 AAB (upload keystore from secrets, `versionCode` = run number, `versionName` from the tag), the installed-size check, and an upload to the Play internal track (status configurable, default `draft`) with the R8 mapping. Build-logic owns signing inputs, version overrides and the size check. Docs give the release runbook (first-upload manual steps, closed-testing promotion) and the pilot session protocol recorded outside the app.

## Boundaries & Constraints

**Always:**
- Secrets used exactly as named in `keys/README.md`: `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`. Keystore is PKCS12. Package `com.exxerpro.cubentia`.
- Local `./gradlew build` and `assembleRelease` keep working with no signing inputs (release stays unsigned); a partial signing set fails the build with a clear message.
- Release levels: `TEST_ME=GA`, `ADAPTIVE_ASSISTANCE=GA`; every camera flag (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`) stays OFF. `benchmark` keeps carrying the release levels.
- Play upload: track `internal`; status from `workflow_dispatch` input or repo variable `PLAY_RELEASE_STATUS`, default `draft`; only `draft` or `completed` accepted.
- Size check reports an installed-size estimate (not just file size) against 30,000,000 B and fails above it; runs on every PR (AD-14) and in the release workflow.
- Instrumentation runs on a GitHub-hosted emulator, never a physical device; `./gradlew build` still never runs `connected*` (existing CI guard).
- Keep spike3d and spikeScan; CI compiles both and runs the spike3d unit tests with their `-P` flags.

**Never:** in-app telemetry, analytics, network or any new permission (NFR-8, AD-13); committing keys or secrets; touching planning artifacts, `_bmad/`, `.claude/skills/`; making `build` depend on detekt, bundle, size check or a device; a Gradle Play-publisher plugin (upload is a workflow step).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Local build | no `cubentia.signing.*` props | release AAB/APK unsigned, build green | No error |
| CI release | all 4 signing props + `cubentia.versionCode=<run>` + `cubentia.versionName=1.2.3` | signed AAB, versionCode = run number, versionName 1.2.3 | No error |
| Partial signing | some but not all 4 props | configuration fails naming the missing props | Build error |
| Bad version | `cubentia.versionCode` not a positive int | configuration fails | Build error |
| Size under budget | estimate ≤ 30,000,000 B | report line + file with bytes, budget, headroom; task passes | No error |
| Size over budget | estimate > 30,000,000 B | report printed, task fails | Build error |
| Bad tag / status | tag not `vX.Y.Z`, or status not draft/completed | release job fails before upload | Workflow error |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/ReleaseConventionPlugin.kt` -- release build type (R8, shrink, keep rules), `benchmark` copy; add signing config, variant version overrides (`onVariants` → `variant.outputs[*].versionCode/versionName`), and register `checkReleaseSize` from `SingleArtifact.APK_FROM_BUNDLE` + `SingleArtifact.BUNDLE` of the release variant.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt` -- `releaseLevels` all OFF today; raise TEST_ME and ADAPTIVE_ASSISTANCE to GA.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevelsTest.kt:17` -- `release turns every flag OFF`; update expectation and keep camera-flags-OFF assertion.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/` -- pure policy objects with JUnit tests in `build-logic/src/test/.../policy/` (pattern: `ManifestPolicy`, `SdkDenylistPolicy`).
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/` -- task pattern (`CheckMergedManifestTask` etc.).
- `app/build.gradle.kts:20` -- `versionCode = 1`, `versionName = "0.1.0"` defaults (overridden per variant by the plugin); `:93-119` spike3d / spikeScan opt-in blocks (verified locally: both compile; `SpikeCubeModelTest` passes).
- `.github/workflows/ci.yml` -- single `build` job (JDK 17, gradle/actions v6, dry-run gate guard forbidding `connected`); add size-check step and `spikes` job.
- `app/src/androidTest/kotlin/dev/cubentia/app/` -- existing instrumentation suite (KeyFlowRotationTest, SessionSnapshotRestartTest, Cube3DRenderSyncTest, EndToEndSyncVerificationTest) run by `:app:connectedDebugAndroidTest`.
- `keys/README.md` -- secret names; extend with pointer to `docs/release.md`.
- `docs/pruning-tables.md:100-130` -- 3.8 installed-size evidence: APK 4,399,962 B → speed-profile total 7,802,989 B; app data 153,600 B. Basis for the estimate model.
- Local measurement now: universal APK 3,059,060 B, `classes.dex` 2,737,352 B uncompressed, AAB 5,564,448 B (`:app:packageReleaseUniversalApk` works).
- `_bmad-output/implementation-artifacts/sprint-status.yaml:578` -- action item 59, mark done with the decision.

## Tasks & Acceptance

**Execution:**
- `build-logic/.../policy/FeatureFlagLevels.kt` + test -- release TEST_ME/ADAPTIVE_ASSISTANCE GA, camera flags OFF.
- `build-logic/.../policy/ReleaseInputs.kt` + `ReleaseInputsTest.kt` -- pure parsing: signing set (none/complete/partial→error), versionCode (positive int ≤ 2,100,000,000), versionName (`X.Y.Z`).
- `build-logic/.../policy/AppSizePolicy.kt` + test -- budget 30,000,000 B; estimate = APK bytes + 3 × uncompressed dex bytes (vdex copy + compiled odex/art bound) + 153,600 B app-data allowance; verdict + report text.
- `build-logic/.../tasks/CheckReleaseSizeTask.kt` -- reads universal APK (dex entry sizes via `java.util.zip`) and AAB size, writes `build/reports/cubentia/release-size.txt`, logs it, fails over budget.
- `ReleaseConventionPlugin.kt` -- wire signing (`storeType = "pkcs12"`), version overrides, `checkReleaseSize`.
- `.github/workflows/ci.yml` -- `:app:checkReleaseSize` step in `build`; new `spikes` job.
- `.github/workflows/release.yml` -- tag `v*.*.*` + `workflow_dispatch` (input `release_status`); job `instrumentation` (KVM, `reactivecircus/android-emulator-runner`, API 34 x86_64, `:app:connectedDebugAndroidTest`, upload reports); job `release` needs it: validate tag/status, decode keystore to `$RUNNER_TEMP`, `spotlessCheck build :app:bundleRelease :app:checkReleaseSize` with `ORG_GRADLE_PROJECT_cubentia.*` props, upload artifacts (AAB, mapping, size report), `r0adkll/upload-google-play` to `internal` with `mappingFile` and status; `permissions: contents: read`.
- `docs/release.md` -- runbook: secrets, tagging, what the workflow does, first upload (draft → PO accepts Play App Signing and rolls out by hand, then set `PLAY_RELEASE_STATUS=completed`), closed-testing promotion with a Google Group + opt-in link, size check method.
- `docs/pilot-session-protocol.md` -- consent, setup, task list (Course stages, deliberate mistake, physical cube), observation sheet recording task completion, hint use, points of confusion, severity 1–4; storage outside the app; no telemetry (NFR-8).
- `keys/README.md`, `sprint-status.yaml` item 59 -- pointer; status done.

**Acceptance Criteria:**
- Given a `vX.Y.Z` tag, when `release.yml` runs, then it runs `connectedDebugAndroidTest` on an emulator, builds a signed R8 + resource-shrunk AAB with `versionCode` = `github.run_number`, uploads the mapping, and uploads to the internal track with status `draft` unless configured `completed`.
- Given the release AAB, when `:app:checkReleaseSize` runs (every PR and every release), then it reports the installed-size estimate, AAB size and headroom against 30,000,000 B and fails above it.
- Given the internal build, when the PO follows `docs/release.md`, then it can be promoted to closed testing for the pilot group, and `docs/pilot-session-protocol.md` records task completion, hint use and points of confusion outside the app. (Pilot install itself is a human before-merge check.)
- Given a PR, when CI runs, then a `spikes` job compiles `-Pcubentia.spike3d=true` (with its unit tests) and `-Pcubentia.spikeScan=true`.

## Spec Change Log

## Review Triage Log

### 2026-11-12 — Review pass
- verdicts: 17 findings — high 1, medium 4, low 8, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: release.yml accepts any vX.Y.Z tag without checking it is on main — real, but the PO requires the first pilot build before the 7.8 PR merges, so a main-ancestry guard would block the intended flow; tags are an operator action.
  - `[low]` `[patch]` Blind: release workflow omits `./gradlew -p build-logic test` — added a `build-logic policy tests` step to the release job.
  - `[medium]` `[defer]` Blind: instrumentation exercises only debug APKs, not the R8 release build — pre-existing gap (no release instrumentation existed); deferred in frontmatter.
  - `[low]` `[patch]` Blind: Key Flow methods are hard-coded in tools/run-keyflow-rotation.sh, so a new @Test could be silently skipped — run-instrumentation.sh now fails unless the run count equals the class's @Test count.
  - `[low]` `[patch]` Blind: pilot protocol omits the 2-look LL mini-Course needed for SM-9 — added task 6 with separate completion and hint recording.
  - `[low]` `[patch]` Blind: observation sheet cannot establish SM-5/6/7 baselines and SM-8 is not marked N/A — added session/time, attempt #, incorrect-Move columns and mandatory numbering; SM-8 stated as not applicable to v1.0.
  - `[medium]` `[patch]` Blind: docs/release.md tags "the result on main" but requires the pilot install before merge (circular) — first pilot tag is cut from the 7.8 branch head after green checks; later tags from main; install noted in the PR.
  - `[medium]` `[patch]` Verification gap: CheckReleaseSizeTask over-budget throw untested at task level — added CheckReleaseSizeTaskTest (ProjectBuilder, synthetic APK, under/over budget).
  - `[low]` `[reject]` Intent: the release workflow itself (trigger, decode, Play upload) is not exercised by any test — inherent to a CI workflow; the first tag run is the integration check and is a PO before-merge step.
  - `[high]` `[patch]` Intent: the `ORG_GRADLE_PROJECT_cubentia.*` env path was untested — verified locally that Gradle ignores dotted ORG_GRADLE_PROJECT_ names (versionCode stayed 1, a bad value did not fail), so CI would have uploaded an unsigned versionCode-1 bundle; release.yml now passes `-P` flags from step env vars (path verified: signed AAB, versionCode 77, `jarsigner` verified).
  - `[low]` `[reject]` Intent: ReleaseSigningFunctionalTest checks signing config but never signs — signing itself is AGP behaviour; signed output verified manually with a throwaway PKCS12 and `jarsigner -verify`.
  - `[medium]` `[defer]` Intent: instrumentation runs against debug, not the shipped R8 artifact — same root cause as the Blind finding above; shares its deferral.
  - `[low]` `[patch]` Intent: on a dispatch from a branch the emulator job runs before tag validation — new `validate` job runs first; instrumentation and release need it.
  - `[false]` `[reject]` Intent: size check measures a universal APK, gates instead of only reporting, and runs on every PR — AD-14 requires an AAB size check on every PR and the epic context rejects AAB/APK file size as installed-size evidence; the AAB size is still reported.
  - `[false]` `[reject]` Intent: the size number is an estimate, not a device measurement — the report and docs label it an estimate and point to the Story 3.8 device method; NFR-11 depends on the build, not the device.
  - `[false]` `[reject]` Intent: secret names are not confirmed against GitHub — they match keys/README.md and the PO's list exactly.
  - `[false]` `[reject]` Intent: a separate UPLOAD_KEY_PASSWORD secret is read although key pass = store pass — the PO listed four secrets including the key password; reading it is correct.

### 2026-11-12 — Review pass
- verdicts: 18 findings — high 0, medium 2, low 7, false 6, maybe-false 0 (edge-case layer reported none; follow-up pass)
- findings:
  - `[medium]` `[defer]` Blind: device tests exercise only debug builds, not the release artifact — carried: same claim as the logged deferral; still `run-instrumentation.sh` runs `connectedDebugAndroidTest`; existing `deferred` item covers it.
  - `[low]` `[reject]` Blind: the 3 × dex multiplier is backed by one device measurement — real, but it is already conservative against that measurement (3 × 2,737,352 B vs ~3.4 MB observed ART output) and the estimate sits at 11.4 MB against 30 MB; validating more compiler configurations means new device work, not a direct fix.
  - `[low]` `[reject]` Blind: the data allowance omits the 4,031,725 B fallback table file (docs/pruning-tables.md:123) — real, but that state needs an invalid bundled asset, and with 18.5 MB headroom no build in sight comes near; the fix changes the spec's estimate formula rather than correcting a slip.
  - `[medium]` `[patch]` Blind: pilot task 4 never asks for an independent fresh-Scramble solve after the Course, so SM-2 has no data — task 4 now asks for one solve without step-by-step guidance, recorded as independent / with hints / with facilitator help.
  - `[medium]` `[patch]` Blind: task 1 checks the first instruction but not a Lesson's Objective, so SM-4 (and gate 4) lacks its Objective check — task 1 now asks for the Objective in the participant's words, recorded separately.
  - `[low]` `[patch]` Blind: observation rows have no Lesson identifier, so SM-7 per-Lesson error rates cannot be computed — column is now "Task, Stage and Lesson, attempt #" and the notes require the Lesson on every Course row.
  - `[low]` `[patch]` Blind: task 4 asks for one practice attempt, but SM-5 needs successive attempts at the same Stage — task 4 now asks for at least three numbered attempts with assistance setting and hints recorded.
  - `[low]` `[reject]` Blind: Design Notes and the release.yml task line still describe dotted `ORG_GRADLE_PROJECT_cubentia.*` env vars — real (stale spec text; code and docs/release.md use `-P`), but the fix edits this build's spec.
  - `[low]` `[reject]` Blind: ReleaseSigningFunctionalTest is named "sign release" but never signs — carried: same claim as the logged reject; signing is AGP behaviour, verified manually with `jarsigner -verify`.
  - `[false]` `[reject]` Verification gap: BuildConfigFeatureFlagsTest still expects every release flag OFF and blocks `build` — `:app:testReleaseUnitTest` does not exist (Gradle: "task 'testReleaseUnitTest' not found in project ':app'"); only debug unit tests run, and `./gradlew spotlessCheck build` passes. The `"release"` branch is dead.
  - `[low]` `[reject]` Intent: the tag run, Play upload, closed-testing promotion and pilot are not exercised by the diff — carried: same claim as the logged reject of the untested workflow; the first tag run and the pilot install are PO before-merge checks.
  - `[medium]` `[defer]` Intent: instrumentation surface is debug, not the shipped R8 artifact — carried: shares the existing deferral.
  - `[low]` `[reject]` Intent: the size estimate is calibrated against one Story 3.8 data point — same reasoning as the Blind multiplier row above.
  - `[false]` `[reject]` Intent: the spec's Design Notes describe the `ORG_GRADLE_PROJECT_` route — stale spec text only; code and docs agree on `-P`, so no build behaves wrongly; a fix would edit this spec.
  - `[false]` `[reject]` Intent: version overrides reach debug and benchmark too — they apply only when `cubentia.versionCode/versionName` are passed, which only the release job does; the debug APK built in that run is not shipped, so no wrong artifact results.
  - `[false]` `[reject]` Intent: validation is stricter than the matrix (`X.Y.Z` versionName, blank signing values treated as missing) — the tag guard already requires `vX.Y.Z`, and failing on an unset secret is the matrix's partial-signing row; no bad outcome.
  - `[false]` `[reject]` Intent: bad tag/status fails in a validate job before the emulator job — that is "fails before upload", only earlier; no bad outcome.
  - `[false]` `[reject]` Intent: the diff rewrites epic-7-context.md and sprint-status.yaml — both live under implementation-artifacts, not planning-artifacts; the intent forbids only planning artifacts, `_bmad/` and `.claude/skills/`.

## Design Notes

Size estimate: the APK is stored as-is on install (assets stay deflated, 3.8 evidence), so installed ≈ APK + ART artifacts + data. ART writes a vdex (≈ uncompressed dex) and odex/art whose size under `speed-profile` was ~3.4 MB on the 3.8 build; 3 × dex bounds both conservatively. Current: 3,059,060 + 3 × 2,737,352 + 153,600 ≈ 11.4 MB, well under budget. The report names it an estimate and points to the 3.8 device method for measured evidence.

Signing/version inputs flow as Gradle properties (`ORG_GRADLE_PROJECT_cubentia.signing.storeFile` …) so build-logic reads only `providers.gradleProperty`, never env directly.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- green.
- `./gradlew -p build-logic test` -- green incl. new policy tests.
- `./gradlew :app:checkReleaseSize` -- prints report under budget.
- `./gradlew -Pcubentia.versionCode=42 -Pcubentia.versionName=1.0.0 -Pcubentia.signing.storeFile=<tmp p12> ... :app:bundleRelease` -- signed AAB (`jarsigner -verify`), versionCode 42.
- `./gradlew -Pcubentia.spike3d=true :app:testDebugUnitTest --tests 'dev.cubentia.app.spike3d.*'` and `-Pcubentia.spikeScan=true :app:compileDebugKotlin` -- green.
- `actionlint` on workflows if available; else YAML parse.

**Manual checks:**
- `:app:connectedDebugAndroidTest` on a local API 34 emulator passes (proxy for the CI emulator job).

## Auto Run Result

Status: done

**Summary:** A `vX.Y.Z` tag (or a dispatch on one) now runs `.github/workflows/release.yml`: validate tag/status → emulator instrumentation (API 34; all androidTest classes plus the Key Flow rotation suite from cleared installs) → build-logic tests, `spotlessCheck build`, signed R8 AAB with `versionCode` = run number and `versionName` from the tag, installed-size check, artifacts, and Play internal upload with the mapping (status `draft` by default, `completed` via `PLAY_RELEASE_STATUS` or dispatch input). Release flags: TEST_ME and ADAPTIVE_ASSISTANCE GA, camera flags OFF. PR CI gains the size check and a `spikes` job. Docs add the release runbook and the pilot session protocol.

**Files changed:**
- `.github/workflows/release.yml` -- new release pipeline (validate, instrumentation, release jobs).
- `.github/workflows/ci.yml` -- `:app:checkReleaseSize` step; `spikes` job (spike3d tests, spikeScan compile).
- `.github/scripts/validate-release-inputs.sh` -- tag/status guard used by the workflow.
- `.github/scripts/run-instrumentation.sh` -- emulator suite: Gradle pass without KeyFlowRotationTest, then the per-method cleared-install Key Flow tool, with a method-count check.
- `build-logic/.../ReleaseConventionPlugin.kt` -- PKCS12 upload signing from `cubentia.signing.*`, variant version overrides, `checkReleaseSize` task.
- `build-logic/.../policy/ReleaseInputs.kt`, `AppSizePolicy.kt`, `FeatureFlagLevels.kt` -- input validation, installed-size estimate, release flag levels.
- `build-logic/.../tasks/CheckReleaseSizeTask.kt` -- reads the bundle-derived universal APK, writes the report, fails above 30,000,000 B.
- Tests: `ReleaseInputsTest`, `AppSizePolicyTest`, `FeatureFlagLevelsTest`, `CheckReleaseSizeTaskTest`, `ReleaseSigningFunctionalTest` (TestKit), `ValidateReleaseInputsScriptTest`.
- `docs/release.md`, `docs/pilot-session-protocol.md`, `keys/README.md` -- runbook, pilot protocol, pointer.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- action item 59 done (keep both spikes, CI compiles them).

**Review:** 17 findings; patched 1 high, 2 medium, 5 low; deferred 1 medium (2 findings: release-variant instrumentation); rejected 3 low and 4 false, with reasons in the triage log.

**Follow-up review recommended: true** -- one high was patched: the release job's signing/version inputs now travel as `-P` flags. That path was verified locally with Gradle, but the workflow step itself (secret expansion, keystore decode, Play upload with `draft`) runs only on a real tag.

**Verification:**
- `./gradlew spotlessCheck build :app:checkReleaseSize` -- pass; estimate 11,424,692 B, headroom 18,575,308 B.
- `./gradlew -p build-logic test` -- pass, including all new suites (no failures/errors in JUnit XML).
- Signed bundle with a throwaway PKCS12, `-Pcubentia.versionCode=77 -Pcubentia.versionName=1.0.0` -- `jarsigner -verify` OK; merged manifest versionCode 77, versionName 1.0.0. Partial signing set fails naming the missing keys.
- `-Pcubentia.spike3d=true :app:testDebugUnitTest --tests 'dev.cubentia.app.spike3d.*'` and `-Pcubentia.spikeScan=true :app:compileDebugKotlin` -- pass.
- Local emulator (GalaxyTabS7FE_API34, `ANDROID_SERIAL=emulator-5554`): `connectedDebugAndroidTest` excluding KeyFlowRotationTest -- 6/6 pass; `tools/run-keyflow-rotation.sh` -- 7/7 flows pass. A plain `connectedDebugAndroidTest` fails 6 Key Flow methods by design (they need a cleared install per method), which is why the wrapper exists.
- Workflow YAML parses; `actionlint` not available.

**Residual risks:**
- The release workflow has never run on GitHub; the first tag is its integration test (hosted emulator speed, `r0adkll/upload-google-play` with `draft` on a never-published app).
- Hosted-emulator Key Flow runs may be slower than local (~2.5 min per flow locally); the job timeout is 120 min.
- AC2 pilot install and sessions remain the PO's human before-merge check.

### Follow-up review pass (2026-11-12)

**Change:** `docs/pilot-session-protocol.md` now collects data for SM-2 (an independent fresh-Scramble solve after the Course), SM-4 (the Lesson Objective in the participant's own words), SM-5 (at least three numbered practice attempts at one Stage) and SM-7 (Lesson named on every Course row). No code changed.

**Review:** 18 findings. Patched 2 medium and 2 low (all in the pilot protocol). The 2 debug-instrumentation findings are carried under the existing deferral. Rejected 6 low and 6 false; the triage log gives each reason.

**Follow-up review recommended: false.** This follow-up pass patched no high finding.

**Verification:** `./gradlew spotlessCheck build :app:checkReleaseSize` passed (estimate 11,424,692 B, headroom 18,575,308 B). `./gradlew -p build-logic test` passed. `:app:testReleaseUnitTest` does not exist, which disproves the release unit-test finding. The signing, spike and emulator checks were not re-run: this pass changed only one doc.

**Residual risks:** Unchanged from the first pass. The release workflow has still never run on GitHub, release-variant instrumentation is still deferred, and the pilot install is the PO's check.
