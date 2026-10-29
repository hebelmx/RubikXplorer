---
title: 'Story 1.1: Scaffold the gated multi-module project'
type: 'chore'
created: '2026-10-29'
status: 'done'
baseline_revision: '45192a871135894a27e2267b354fbeee8f146065'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      Append the KSP 2.3.12 vs Kotlin 2.4.21 result to the architecture memlog.
    evidence: |-
      AC 1 asks for it to be recorded in the architecture memlog; this run is forbidden to edit planning artifacts, so it is recorded in Design Notes and in the libs.versions.toml comment only. Append with `uv run _bmad/scripts/memlog.py append`.
    location: >-
      _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/.memlog.md
    severity: medium
  - summary: >-
      Replace the AGENTS.md TODO(scaffold) with the real ./gradlew invocations via bmad-project-context refresh after merge.
    evidence: |-
      AC 5 is post-merge and AGENTS.md is an agent-context file; still holds the TODO.
    location: >-
      AGENTS.md
    severity: low
  - summary: >-
      Make the CI workflow a required status check on main so the gates actually block merges.
    evidence: |-
      AC 3 says the gates "must pass" for a PR to main; branch protection is a GitHub repository setting the diff cannot contain.
    location: >-
      GitHub repository settings (branch protection for main)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The repo has no code. Every later story needs a Gradle multi-module build whose gates (AD-1 boundaries, AD-13 privacy, AD-14 quality, AD-18 flags) are enforced by the build and CI from the first commit.

**Approach:** Create the Gradle wrapper, version catalog, `build-logic` convention plugins (`jvm-core`, `android-app`, `quality`, `release`), the four modules with the AD-1 dependency edges, the `FeatureFlags` port, a minimal Hilt + Compose `:app`, and a GitHub Actions workflow that runs the gates.

## Boundaries & Constraints

**Always:**
- Versions only in `gradle/libs.versions.toml`, exactly the spine Stack: Kotlin 2.4.21, AGP 9.5.0, Gradle 9.8.2 wrapper, JDK toolchain 17, compileSdk 37 / targetSdk 36 / minSdk 29, Compose BOM 2026.09.00, activity-compose 1.13.0, Hilt 2.60.1, KSP 2.3.12, Spotless 8.10.4 + ktlint 1.8.0, Kover 0.9.11, JUnit Jupiter 6.1.3, detekt `dev.detekt` 2.0.0-alpha.6.
- KGP 2.4.21 is on the root build classpath: root `build.gradle.kts` declares the Kotlin/AGP/KSP/Hilt plugins `apply false`; `build-logic` depends on them `compileOnly`.
- `:core:domain`, `:core:solver`, `:core:application` are `kotlin("jvm")` via the `jvm-core` convention. Edges per AD-1 only: app → application, solver, domain; application → domain; solver → application, domain.
- Package root `dev.rubikxplorer.<module>`; applicationId `dev.rubikxplorer`.
- Policy logic (dependency boundaries, manifest rules, SDK denylist) lives as pure functions in `build-logic` with unit tests; the Gradle tasks only call them.
- The KSP 2.3.12 vs Kotlin 2.4.21 result (works, or the version pinned instead) is recorded in this spec's Design Notes and as a comment next to the `ksp` version in the catalog.

**Never:**
- `kotlin("android")`, `kotlin-android`, kapt, or any `INTERNET` permission, backup, analytics/ads/crash SDK.
- Editing anything under `_bmad-output/planning-artifacts/` (including `.memlog.md`), `_bmad/`, `.claude/skills/`, `AGENTS.md`, `README.md`.
- Out of scope: AAB size check, signing, release workflow, R8 mapping upload, versionCode-from-CI, StrictMode, fixture recorder, Room, CameraX, any domain code, the post-merge `bmad-project-context` refresh.
- Placeholder business code in `:core:*` beyond the `FeatureFlags` port. `BuildConfig` read anywhere except the flags adapter.
- detekt failing or being required by `./gradlew build`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Core boundary clean | `:core:*` with only Kotlin/JVM deps | build passes | none |
| Core boundary violated | a `:core:*` module declares a dep in group `androidx.*`, `com.android.*`, `com.google.android.*`, `com.google.dagger*` (Hilt), `androidx.room*`, `androidx.camera*`, `androidx.compose*`, `org.jetbrains.compose*`, or applies an Android plugin | `./gradlew build` fails naming the module and dependency | build failure |
| Manifest clean | merged manifest: no `INTERNET`, no runtime permission but `CAMERA`, `allowBackup="false"` | check passes | none |
| Manifest `INTERNET` | merged manifest declares `android.permission.INTERNET` | check fails naming it | build failure |
| Other runtime permission | e.g. `ACCESS_FINE_LOCATION`, `RECORD_AUDIO` (any Android dangerous permission except `CAMERA`) | check fails | build failure |
| Normal permission | e.g. `<appId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, `WAKE_LOCK` | passes | none |
| Backup | `allowBackup` missing or `true` | check fails | build failure |
| Denylisted SDK | runtime classpath contains e.g. `com.google.firebase:*`, `com.google.android.gms:play-services-ads*/-analytics*/-measurement*`, `com.crashlytics*`, `io.sentry*`, `com.bugsnag*`, `com.flurry*`, `com.amplitude*`, `com.mixpanel*`, `com.segment*`, `com.appsflyer*`, `com.adjust*`, `com.facebook.android*`, `com.applovin*`, `com.unity3d.ads*`, `com.ironsource*`, `io.branch*`, `com.datadoghq*`, `com.newrelic*`, `com.instabug*`, `ly.count*` | check fails naming the coordinate | build failure |
| Flags debug | debug build | every `FeatureFlag` = `GA` | none |
| Flags release | release build | every `FeatureFlag` = `OFF` | none |
| Flags malformed | levels string missing a flag or with unknown level | adapter throws `IllegalStateException` at construction | programmer error |
| BuildConfig leak | `BuildConfig` referenced in `app/src/main` outside the flags adapter | `:app` unit test fails naming the file | test failure |

</intent-contract>

## Code Map

- Repo is greenfield: only `.gitignore`, `README.md`, `AGENTS.md`, `CLAUDE.md`, `docs/`, `_bmad*/`. `.gitignore` already ignores `build/`, `.gradle/`, `local.properties`.
- Local toolchain (outside repo, not committed): Android SDK at `~/Android/Sdk` (platforms `android-37.0`, build-tools 37.0.0), JDK 17 at `~/.jdks/jdk-17.0.21+9`, system JDK 25. No system Gradle: bootstrap the wrapper by downloading Gradle 9.8.2 to the scratch area. Create an uncommitted `local.properties` with `sdk.dir`.
- Spine AD-1, AD-13, AD-14, AD-15, AD-18 and Stack are distilled in `epic-1-context.md` (Technical Decisions).

## Tasks & Acceptance

**Execution:**
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*` -- Gradle 9.8.2 wrapper -- reproducible build.
- `gradle/libs.versions.toml` -- all versions, libraries, plugins -- single source of versions.
- `settings.gradle.kts` -- `pluginManagement { includeBuild("build-logic") }`, foojay-resolver-convention 1.0.0, `FAIL_ON_PROJECT_REPOS` with google()/mavenCentral(), include the four modules -- module graph.
- `build.gradle.kts` (root) -- plugin aliases `apply false` (pins KGP 2.4.21), Spotless with ktlint 1.8.0 over `**/*.kt` and `**/*.kts` (excluding `build/`) -- formatting gate.
- `gradle.properties` -- JVM args, `android.useAndroidX=true` -- standard build props.
- `build-logic/` (`settings.gradle.kts`, `build.gradle.kts`, `src/main/kotlin/**`) -- plugins `rubikxplorer.jvm-core` (kotlin jvm, toolchain 17, JUnit Platform, Kover verify ≥ 80% line wired into `check`, AD-1 boundary check task wired into `check`), `rubikxplorer.android-app` (AGP app, SDK levels, toolchain 17, `buildConfig = true`, JUnit Platform for unit tests), `rubikxplorer.quality` (per-variant merged-manifest and SDK-denylist check tasks wired into `check`; detekt applied but non-blocking and not in `check`), `rubikxplorer.release` (flag levels per build type as one `BuildConfig` string field from a single map, release `isMinifyEnabled`/`isShrinkResources` with build-logic-owned keep rules) -- the gates.
- `build-logic/src/test/kotlin/**` -- unit tests for each policy function covering every I/O-matrix row of boundary, manifest and denylist -- prove the rules.
- `core/domain`, `core/solver`, `core/application` `build.gradle.kts` -- apply `rubikxplorer.jvm-core` and AD-1 project deps -- modules.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/FeatureFlags.kt` -- `enum class FeatureFlag { QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, CAMERA_SCAN, ADAPTIVE_ASSISTANCE }`, `enum class FlagLevel { OFF, BETA, GA }`, `interface FeatureFlags { fun level(flag: FeatureFlag): FlagLevel }` plus `isEnabled` default (level != OFF) -- AD-18 port; with a JVM test keeping Kover ≥ 80%.
- `app/build.gradle.kts` -- apply android-app, quality, release, compose compiler, KSP, Hilt; deps on the three core modules -- shell.
- `app/src/main/AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `res/values/strings.xml` -- `allowBackup="false"`, exclude-all extraction/backup rules, no permissions -- AD-13.
- `app/src/main/kotlin/dev/rubikxplorer/app/` -- `RubikXplorerApp` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`, Compose text), `flags/BuildConfigFeatureFlags.kt` (only `BuildConfig` reader), `di/FlagsModule.kt` (binds the port) -- exercises KSP + Hilt.
- `app/src/test/kotlin/dev/rubikxplorer/app/` -- adapter test (levels per `BuildConfig.BUILD_TYPE`, malformed input) and a source-scan test for `BuildConfig` -- AD-18 tests.
- `.github/workflows/ci.yml` -- on `pull_request` to `main` and `push` to `main`: wrapper validation, JDK 17, setup-gradle, then `./gradlew spotlessCheck build` and `./gradlew -p build-logic test`; separate `detekt` step with `continue-on-error: true` -- AD-14.

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew build` runs, then all four modules compile, `buildEnvironment` shows `kotlin-gradle-plugin:2.4.21` and no `kotlin-android`/kapt appears in any build script.
- Given `implementation("androidx.core:core:1.17.0")` added to `core/domain/build.gradle.kts`, when `./gradlew build` runs, then it fails via the build-logic boundary check.
- Given debug and release, when `assembleDebug assembleRelease` run, then both succeed and each gets its flag levels from build-logic.
- Given a PR to `main`, when CI runs, then build, Spotless, Android lint, JVM tests, Kover and the AD-13 checks gate it and detekt cannot fail it.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass
- verdicts: 43 findings — high 0, medium 6, low 31, false 6, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) `:app` boundary never checked — patched: shared `CheckModuleBoundariesTask.register` now used by android-app; `:app:checkModuleBoundaries` runs in `build`.
  - `[low]` `[patch]` (blind) `com.android.*` plugin prefix rule unreachable — patched: prefix branch, doc sentence and test case removed.
  - `[low]` `[reject]` (blind) boundary check sees only declared deps (transitive/file deps) — matches the spec matrix; pure-JVM libs pulling androidx are unlikely and resolving core classpaths adds complexity.
  - `[low]` `[reject]` (blind) manifest gate does not require `dataExtractionRules` — AC asks only for `allowBackup`; the attribute is present; adding the rule is a new branch for an unlikely edit.
  - `[medium]` `[patch]` (blind) device-protected domains not excluded from backup/transfer — patched: device_root/file/database/sharedpref excluded in both XML files.
  - `[low]` `[reject]` (blind) AD_ID/c2dm/network-state permissions unchecked — ad-id SDKs are already caught by the `play-services-ads*`/`measurement*` denylist; AC scopes INTERNET and runtime permissions.
  - `[low]` `[patch]` (blind) dangerous-permission list has no source — patched: KDoc names `Manifest.permission` dangerous entries as of API 37 and the refresh rule.
  - `[low]` `[reject]` (blind) denylist misses some SDKs and raw prefixes over-match — list is the spec's example set; over-match on names like `com.adjustable` is unlikely and segment anchoring adds logic.
  - `[medium]` `[patch]` (blind) no functional tests for gate wiring — patched with V1/V4: codec round-trip test plus CI dry-run step asserting gate tasks present and detekt absent; boundary and manifest failures verified manually.
  - `[low]` `[reject]` (blind) Kover 80% rule vacuous on empty modules — intended: spec forbids placeholder core code; the rule bites when code lands.
  - `[low]` `[patch]` (blind) `BuildConfigUsageTest` scans only `src/main` — patched: scans every non-test source set under `app/src`.
  - `[false]` `[reject]` (blind) flag-name mismatch surfaces only as a launch crash — `BuildConfigFeatureFlagsTest` constructs the adapter from BuildConfig in `build`, so a mismatch fails CI first.
  - `[low]` `[reject]` (blind) CI does not upload reports / actions not SHA-pinned — developer convenience; adds steps with no gate effect.
  - `[low]` `[patch]` (blind) `.kotlin/` not ignored — patched: added to `.gitignore`.
  - `[low]` `[reject]` (blind) no edge-to-edge handling in placeholder activity — placeholder screen replaced by later UI stories.
  - `[low]` `[reject]` (blind) no release signingConfig — signing is AD-15/release work outside this story's ACs.
  - `[low]` `[patch]` (edge) unlisted `com.android.*` plugin never collected — grouped with prefix-rule patch.
  - `[low]` `[reject]` (edge) `files(...)` dependencies dropped by boundary check — unlikely in core; adding file handling is new logic.
  - `[low]` `[reject]` (edge) transitive androidx into core — same as blind row 3.
  - `[low]` `[patch]` (edge) `:app` edge rule unenforced — grouped with `:app` boundary patch.
  - `[false]` `[reject]` (edge) malformed encoded dependency gives IndexOutOfBounds — only `collect()` produced the strings; now a codec that throws IllegalArgumentException with a message.
  - `[false]` `[reject]` (edge) detekt filter may leave detekt in `check` — `./gradlew build --dry-run` shows no detekt task; CI now guards it.
  - `[low]` `[reject]` (edge) deps added after afterEvaluate unchecked — build scripts declare deps before afterEvaluate; lazy collection adds complexity.
  - `[low]` `[reject]` (edge) denylist prefix false positives — same as blind row 8.
  - `[false]` `[reject]` (edge) unresolved denylisted coordinate omitted — an unresolved dependency fails compilation/packaging anyway.
  - `[false]` `[reject]` (edge) extra build type fails configuration — deliberate loud failure with a message telling the developer to add the map entry.
  - `[low]` `[patch]` (edge) `cancel-in-progress` cancels push-to-main runs — patched: only for pull_request events.
  - `[low]` `[patch]` (edge, claim) Android plugin claim unreachable for unlisted ids — grouped with prefix-rule patch.
  - `[false]` `[reject]` (edge, claim) detekt can still run inside build — dry-run task graph has no detekt task.
  - `[medium]` `[patch]` (verification-gap) `|` encode/decode untested — patched: `DeclaredDependencyCodec` with round-trip and malformed-input tests.
  - `[low]` `[patch]` (verification-gap) prefix-only plugins never reach the policy — grouped with prefix-rule patch.
  - `[low]` `[reject]` (verification-gap) transitive walk in `CheckSdkDenylistTask.coordinates()` untested — needs a TestKit fixture; the walk is a plain resolution-result traversal unlikely to regress before the first third-party dependency story.
  - `[medium]` `[patch]` (verification-gap) nothing asserts `build` runs the gates and excludes detekt — patched: CI "Verify build wires the gates" dry-run step.
  - `[low]` `[patch]` (verification-gap) `:app` edges defined but not applied — grouped with `:app` boundary patch.
  - `[low]` `[patch]` (verification-gap, other) `isForbiddenPlugin` doc/code mismatch — grouped with prefix-rule patch.
  - `[medium]` `[defer]` (intent) KSP result not in architecture memlog — run forbidden to edit planning artifacts; deferred to orchestrator.
  - `[low]` `[defer]` (intent) AGENTS.md TODO not replaced — AC 5 is post-merge, agent-context file; deferred.
  - `[medium]` `[patch]` (intent) failure paths not shown end to end — grouped with gate-wiring patch; manual negative runs recorded under Auto Run Result.
  - `[low]` `[defer]` (intent) required status check is a repo setting — outside the diff; deferred.
  - `[low]` `[reject]` (intent) Kover vacuous on empty modules — same as blind row 10.
  - `[low]` `[patch]` (intent) BuildConfig scan limited to `:app/src/main` — grouped with BuildConfig scan patch.
  - `[low]` `[reject]` (intent) release unit tests do not run the release branch — release levels covered by `FeatureFlagLevelsTest` and checked in generated BuildConfig.
  - `[low]` `[reject]` (intent) no automated kotlin-android/kapt check for `:app` — verified absent by the Verification grep; AC requires absence, not a gate.

### 2026-10-29 — Review pass (follow-up)
- verdicts: 35 findings — high 0, medium 3, low 26, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` (verification-gap) gate input collection (`collect`, `coordinates`, quality wiring) has no functional test — carried: same claim as the earlier "no functional tests for gate wiring" row; the dry-run guard and manual negative runs still stand; not patched again.
  - `[low]` `[patch]` (verification-gap) CI dry-run guard greps bare task names, so one module's task stands in for another's — patched: guard now lists every fully qualified path (`:app:checkModuleBoundaries`, `:core:{domain,solver,application}:checkModuleBoundaries`/`:koverVerify`, `:app:*` manifest/denylist/lint) and matches `^<path> ` at line start.
  - `[medium]` `[patch]` (intent) boundary/manifest failures shown only on pure policies, not a red `build` — carried: same as the earlier "failure paths not shown end to end" row; manual negative runs recorded.
  - `[low]` `[reject]` (intent) boundary sees declared, not transitive, groups — carried: earlier reject still holds.
  - `[low]` `[reject]` (intent) dangerous permissions judged by a fixed list, not library-declared `<permission protectionLevel=dangerous>` — a custom dangerous permission the app also uses is unlikely; detecting it adds a new branch.
  - `[low]` `[reject]` (intent) `CheckSdkDenylistTask.coordinates` walk untested — carried: earlier reject still holds.
  - `[false]` `[reject]` (intent) malformed levels reach build-logic's `IllegalArgumentException` first — the adapter still throws `IllegalStateException` at construction (tested); an earlier, louder failure in build-logic is not a defect.
  - `[low]` `[reject]` (intent) BuildConfig scan is text-based and would flag comments — a false positive fails loudly and is fixed by rewording; a parser-based scan adds complexity.
  - `[low]` `[reject]` (intent) `foojay-resolver-convention` 1.0.0 pinned in `settings.gradle.kts` outside the catalog — the settings `plugins {}` block cannot read the version catalog; the version is the one the spec names.
  - `[false]` `[reject]` (intent) KSP result not in Design Notes — the Design Notes entry exists in this spec; the reviewed diff excluded the spec file.
  - `[low]` `[reject]` (blind) backup gate does not require `dataExtractionRules` — carried: earlier reject still holds; both attributes are present.
  - `[low]` `[reject]` (blind) denylist misses datatransport/appcenter/onesignal etc. and `AD_ID` — carried: earlier rejects for the denylist set and AD_ID still hold.
  - `[low]` `[reject]` (blind) raw-text denylist prefixes over-match — carried: earlier reject still holds.
  - `[low]` `[reject]` (blind) Gradle plugin ids (google-services, crashlytics) not denylisted — those plugins require their runtime SDK, which the runtime-classpath denylist catches.
  - `[low]` `[reject]` (blind) boundary check misses file deps, transitives and Android libs outside the groups — carried: earlier rejects still hold.
  - `[low]` `[reject]` (blind) dependency snapshot depends on `afterEvaluate` order — carried: earlier reject still holds.
  - `[medium]` `[patch]` (blind) no end-to-end tests of the gate wiring — carried: same as the earlier gate-wiring row; not patched again.
  - `[low]` `[patch]` (blind) dry-run guard substring match and no unit-test tasks in it — grouped with the fully-qualified-path patch; unit-test tasks left out because `check` always pulls `test`, and the guard targets the custom gates.
  - `[low]` `[reject]` (blind) CI uploads no reports on failure — carried: earlier reject still holds.
  - `[false]` `[reject]` (blind) detekt decoupling fragile / `continue-on-error` redundant — carried: dry-run shows no detekt task and CI guards it; `continue-on-error` deliberately keeps a detekt tool crash advisory.
  - `[low]` `[reject]` (blind) no edge-to-edge insets or launcher icon in the placeholder activity — carried for edge-to-edge; icon is cosmetic on a placeholder replaced by later UI stories.
  - `[low]` `[reject]` (blind) lint warnings never fail the build — AGP default `abortOnError` fails on errors; making warnings fatal is a policy choice the intent does not make.
  - `[low]` `[reject]` (blind) configuration cache not enabled or tested — not enabled, so nothing breaks today; enabling it is new work.
  - `[false]` `[reject]` (blind) flag names synced by hand — carried: `BuildConfigFeatureFlagsTest` constructs the adapter from real BuildConfig in `build`, so drift fails CI.
  - `[false]` `[reject]` (blind) extra build type fails configuration — carried: deliberate loud failure with a message naming the fix.
  - `[low]` `[reject]` (blind) BuildConfigUsageTest misfires on comments / misses generated dirs — same reasoning as the intent BuildConfig row; generated sources are not hand-written reads.
  - `[low]` `[reject]` (edge) merged manifest without `dataExtractionRules` passes — carried: earlier reject still holds.
  - `[low]` `[reject]` (edge) custom/library dangerous permissions pass — same as the intent dangerous-permission row.
  - `[low]` `[reject]` (edge) transitive androidx/dagger into `:core:*` — carried: earlier reject still holds.
  - `[low]` `[reject]` (edge) `files(...)` dependencies dropped by boundary check — carried: earlier reject still holds.
  - `[low]` `[reject]` (edge) denylisted SDK as a local jar/aar is invisible — no file dependencies exist; reporting non-module artifacts adds a new branch for an unlikely edit.
  - `[low]` `[reject]` (edge) `org.jetbrains.androidx`, `com.google.mlkit`, `com.google.firebase` not forbidden in `:core:*` — outside the matrix's group list; firebase is caught by the app denylist and an ML Kit dep in pure-JVM core would not compile against it unnoticed.
  - `[low]` `[reject]` (edge) late-added dependencies missed by `afterEvaluate` snapshot — carried: earlier reject still holds.
  - `[false]` `[reject]` (edge) quality plugin ignores `com.android.library` modules — only `:app` applies it and no library module exists; nothing reaches the missing path.
  - `[low]` `[patch]` (edge) per-module gate regression passes the substring grep — grouped with the fully-qualified-path patch.

## Design Notes

- Flag levels: debug = all `GA` (AD-15 "debug includes every feature flag"); release = all `OFF` until each feature story raises its own. Encoded as `FEATURE_FLAG_LEVELS = "QUICK_SCAN=GA,PLACEMENT=GA,..."`.
- "Runtime permission" = Android dangerous permission; build-logic holds the dangerous-permission list. Normal and signature permissions pass.
- The architecture memlog may not be edited by this run. The KSP result goes here and in the catalog comment; appending it to the memlog is deferred to the orchestrator.
- KSP result (2026-10-29): KSP 2.3.12 works with Kotlin 2.4.21 and AGP 9.5.0 built-in Kotlin. `:app:kspDebugKotlin`, `:app:kspReleaseKotlin` and `:app:kspDebugUnitTestKotlin` succeed with Hilt 2.60.1 (`hilt-compiler` via `ksp`), so no fallback pin is needed. `buildEnvironment` resolves AGP's `kotlin-gradle-plugin:2.2.10` request to 2.4.21. Still to do (orchestrator): append this to the architecture memlog.

## Verification

**Commands:** (with `JAVA_HOME` = JDK 17 or 25, `ANDROID_HOME=~/Android/Sdk`)
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (includes lint, unit tests, Kover verify, boundary and AD-13 checks).
- `./gradlew -p build-logic test` -- expected: all policy tests pass.
- `./gradlew assembleDebug assembleRelease` -- expected: success.
- `./gradlew buildEnvironment | grep kotlin-gradle-plugin` -- expected: 2.4.21 only.
- Add `implementation("androidx.core:core:1.17.0")` to `core/domain/build.gradle.kts`, run `./gradlew :core:domain:check`, expect failure naming the dep; revert.
- `grep -rnE 'kotlin\("android"\)|kotlin-android|kapt' --include=*.kts --include=*.toml .` -- expected: no match.
- `./gradlew detekt || true` -- expected: runs or fails without affecting `build`.

## Auto Run Result

Status: done

**Summary:** Follow-up review pass over the Story 1.1 scaffold (Gradle 9.8.2 multi-module build with `build-logic` gates for AD-1, AD-13, AD-14, AD-18 and a CI workflow). One low patch applied: the CI "Verify build wires the gates" guard now checks fully qualified task paths per module instead of bare-name substrings.

**Files changed (this pass):**
- `.github/workflows/ci.yml` -- dry-run guard lists `:app:` and each `:core:*` gate task path and matches it at line start.
- this spec -- follow-up triage log and result.

**Review findings:** 35 findings (medium 3, low 26, false 6). Patched: 1 entry (low; CI guard per-module paths, grouping 3 findings). Carried from the first pass without re-patching: 3 medium gate-wiring rows. Deferred: none new (the 3 earlier deferrals stand). All rejections and reasons are in the follow-up Review Triage Log.

**Follow-up review recommended:** false -- follow-up pass patched no `high` (patched counts: high 0, medium 0, low 1).

**Verification:** patched guard run locally under bash against `./gradlew build --dry-run`: all 12 paths found, no detekt task (rc 0); negative probe `^:app:koverVerify ` correctly not matched. `./gradlew spotlessCheck build`, `./gradlew -p build-logic test`, `./gradlew assembleDebug assembleRelease` all exit 0; `buildEnvironment` resolves `kotlin-gradle-plugin:2.2.10 -> 2.4.21`; kotlin-android/kapt grep has no matches.

**Residual risks:** CI still has never run on GitHub Actions; gate input collection (`collect`, `coordinates`) is covered only by the dry-run guard and manual negative runs, not TestKit; boundary check sees declared, not transitive, dependencies; Kover gate vacuous on empty `:core:domain`/`:core:solver`.
