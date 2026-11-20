---
title: 'ai-b3: CI hardening (androidTest compile in PR CI, LF guard for shell scripts, offline Robolectric artifacts, Compose clock stabilization)'
type: 'chore'
created: '2026-11-20'
status: 'done'
baseline_revision: '35ddbb27be69027ed509c5eab1d92414313dfb65'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Four open sprint-status action items leave CI blind or flaky: PR CI never compiles `androidTest` (a KeyFlowHarness break shows only at release; `issue-119-deferred-ci-compile-androidtest`); nothing checks that `*.sh` files keep the LF rule, and ubuntu CI never shows a CRLF regression (`issue-119-deferred-ci-guard-sh-lf`); Robolectric fetches `android-all-instrumented` from Maven inside the test JVM, so a network blip fails every Robolectric test with `initializationError` (`epic-5-retro-item-23-provide-robolectric-android-all-artifact`); and Compose UI tests still contain timing waits that can time out under CI load (`epic-6-retro-item-2-investigate-compose-clock-stabilization`).

**Approach:** Add `:app:compileDebugAndroidTestKotlin` and a shell-script LF guard script to the PR CI job; resolve the `android-all-instrumented` jar through Gradle (cached by `setup-gradle`) and run every `:app` unit-test task with `robolectric.offline=true` pointing at it; finish the Compose clock investigation by giving the remaining default-timeout (1 s) `waitUntil` calls the suite's 5 s timeout and guarding that rule with a JVM source test. Each item gets an automated JVM/Robolectric test.

## Boundaries & Constraints

**Always:** versions live only in `gradle/libs.versions.toml`; `build` keeps every existing gate and never depends on detekt or a connected/benchmark task; `.sh` files stay LF in the working tree; new tests run inside `./gradlew spotlessCheck build` or `./gradlew -p build-logic test` (both already in PR CI).

**Never:** edit `sprint-status.yaml`, planning artifacts, `_bmad/` or `.claude/skills/`; add the `INTERNET` permission or any SDK; make `build` depend on `compileDebugAndroidTestKotlin` (scope is PR CI); change production (`app/src/main`) code; rewrite tests beyond the timing waits named here.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| LF guard, clean repo | every tracked `*.sh` has `eol=lf` and LF in index | script exits 0 | No error expected |
| LF guard, attribute lost | a tracked `*.sh` whose `git check-attr eol` is not `lf` | exits non-zero, names the file | `::error::` line |
| LF guard, CRLF committed | a tracked `*.sh` stored with CRLF in the index (`i/crlf` or `i/mixed`) | exits non-zero, names the file | `::error::` line |
| Robolectric offline | `:app:testDebugUnitTest` with no network | android-all jar read from the Gradle-resolved dir; no Maven fetch | missing jar fails loudly in Robolectric, not silently |

</intent-contract>

## Code Map

- `.github/workflows/ci.yml` -- PR CI; job `build` step "Spotless and build" runs `./gradlew spotlessCheck build`; no androidTest compile, no eol check.
- `.gitattributes:9` -- `*.sh text eol=lf` (the rule to guard). Tracked `*.sh` today: `.claude/hooks/protect-paths.sh`, `.github/scripts/run-instrumentation.sh`, `.github/scripts/validate-release-inputs.sh`, `tools/run-keyflow-rotation.sh` (all `i/lf w/lf attr/text eol=lf`).
- `.github/scripts/validate-release-inputs.sh` + `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/ValidateReleaseInputsScriptTest.kt` -- precedent: CI bash script tested from build-logic via `ProcessBuilder("bash", "../.github/scripts/...")`.
- `app/build.gradle.kts:90-95` -- Robolectric test deps; `app/src/test/resources/robolectric.properties` sets `sdk=34`; `@Config(sdk = [34])` in two milestone tests. Robolectric 4.17 (`gradle/libs.versions.toml:19`) uses `org.robolectric:android-all-instrumented:14-robolectric-10818077-i7` for SDK 34 (present in `~/.m2` from prior live fetches).
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellTest.kt:143,169,183` -- the only `rule.waitUntil { ... }` calls with the 1 s default timeout; every other `waitUntil` in `app/src/test` passes `5_000`.
- Already fixed on main (evidence for item epic-6-2): `16a663a6` and `87744375` (2026-11-07, after the 2026-11-06 retro) made `LessonScreenTest` wait for the panel/mode nodes, idle before pausing the clock, and restore `autoAdvance` in `tearDown` (`LessonScreenTest.kt` `tearDown`, `switchTo`); 3D timing tests `Cube3DRendererTest.kt:108-109,129-130` and `Cube3DEmphasisTest.kt` call `waitForIdle()` before `mainClock.autoAdvance = false`.

## Tasks & Acceptance

**Execution:**
- `.github/scripts/check-sh-eol.sh` -- new bash script (LF, executable): optional arg = repo dir (default cwd); for each `git ls-files '*.sh'` fail if `git check-attr eol` is not `lf` or `git ls-files --eol` index column is not `i/lf`; print `::error::<file> ...`; exit 1 on any failure -- item issue-119-deferred-ci-guard-sh-lf.
- `.github/workflows/ci.yml` -- in job `build`: add a step "Shell scripts keep LF" running `bash .github/scripts/check-sh-eol.sh`, and change "Spotless and build" to `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` (comment: androidTest sources compile on every PR, #119) -- items issue-119-deferred-ci-compile-androidtest and ci-guard-sh-lf.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckShEolScriptTest.kt` -- new: script passes on this repo; fails on a temp git repo whose `.gitattributes` lacks the `*.sh` rule with a CRLF `.sh` committed (`core.autocrlf=false`); fails when the attribute resolves to `crlf`.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CiWorkflowTest.kt` -- new: reads `../.github/workflows/ci.yml`; asserts it triggers on `pull_request` and that a step's `run:` invokes `:app:compileDebugAndroidTestKotlin` and `check-sh-eol.sh`.
- `gradle/libs.versions.toml` -- add version `robolectric-android-all = "14-robolectric-10818077-i7"` and library `robolectric-android-all-instrumented`.
- `app/build.gradle.kts` -- a resolvable, non-transitive configuration holding that library; a `Sync` task copying it to `build/robolectric-android-all`; `tasks.withType<Test>().configureEach`: depend on the sync, `systemProperty("robolectric.offline", "true")`, `systemProperty("robolectric.dependency.dir", <dir>)` -- item epic-5-23.
- `app/src/test/kotlin/dev/cubentia/app/RobolectricOfflineTest.kt` -- new Robolectric test: `robolectric.offline` is `"true"`, `robolectric.dependency.dir` exists and holds `android-all-instrumented-*.jar` for the running SDK (`Build.VERSION.SDK_INT` 34 → `14-` prefix).
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellTest.kt` -- pass `5_000` to the three default-timeout `waitUntil` calls -- item epic-6-2.
- `app/src/test/kotlin/dev/cubentia/app/ComposeTestTimingGuardTest.kt` -- new JVM test scanning `app/src/test` Kotlin sources: no `waitUntil {` / `waitUntil{` without a timeout argument; and every `mainClock.autoAdvance = false` in `LessonScreenTest.kt` is reset in its `@After` (asserts `tearDown` contains `autoAdvance = true`).

**Acceptance Criteria:**
- Given a pull request to `main`, when CI job `build` runs, then it compiles `:app:compileDebugAndroidTestKotlin` and runs `check-sh-eol.sh` (asserted by `CiWorkflowTest`), and `./gradlew :app:compileDebugAndroidTestKotlin` succeeds locally on this branch.
- Given `:app` unit tests, when they run, then Robolectric is in offline mode reading the Gradle-resolved jar (asserted by `RobolectricOfflineTest`) and the whole `:app:testDebugUnitTest` suite passes in that mode.
- Given `app/src/test`, when `ComposeTestTimingGuardTest` runs, then no `waitUntil` relies on the 1 s default.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 31 findings — high 0, medium 8, low 19, false 4, maybe-false 0
- findings:
  - `low` `patch` (blind) Index branch of check-sh-eol.sh never shown failing alone — added fixture: CRLF committed, LF rule added without renormalizing; asserts only the `stored as i/crlf` error.
  - `low` `patch` (blind) Guard reads only `eol`, so `*.sh -text`/`binary` passes — script now fails when `git check-attr text` is not `set`; fixture `*.sh -text eol=lf` added.
  - `low` `patch` (blind) `i/` (empty file) accepted silently — documented in a script comment.
  - `medium` `patch` (blind) Fixture git processes inherit caller GIT_* env/global config (e.g. under a git hook could touch the real repo) — `exec` strips GIT_* and sets GIT_CONFIG_GLOBAL=/dev/null, GIT_CONFIG_NOSYSTEM=1.
  - `low` `patch` (blind) Repo test outside a git checkout — script now exits 2 with `::error::git ls-files failed` (same fix as edge E1), so the test fails loudly instead of passing vacuously.
  - `low` `patch` (blind) ComposeTestTimingGuardTest skips `src/testDebug` — now scans src/test, src/testDebug, src/sharedTest.
  - `medium` `patch` (blind) Timeout regex misses `conditionDescription`/`waitUntil*` forms and explicit 1 s waits — guard flags those forms without `timeoutMillis`; LessonScreenTest 2_000→5_000, 1_000→2_500 (bound is the assertion, < 3 s deadline).
  - `false` `reject` (blind) @After check fragile; other tests pause the clock with no reset — createComposeRule is per test, so a paused clock cannot leak to the next test; the regex matches the real LessonScreenTest tearDown (test passes).
  - `low` `reject` (blind) No check that `build` never depends on compileDebugAndroidTestKotlin — a dependency would not harm anyone (only slower local build); a new dry-run guard is added complexity for a self-imposed scope note.
  - `low` `patch` (blind) CiWorkflowTest passes with `continue-on-error`/`if:` on the new steps — new test asserts the two steps carry neither.
  - `low` `reject` (blind) android-all pin in two places; spec text stale — a wrong pin fails every Robolectric test loudly (missing jar); spec edits are excluded fixes.
  - `medium` `patch` (edge) git ls-files failure in process substitution ignored → vacuous exit 0 — list captured first; git failure exits 2 with `::error::`; fixture on a plain dir.
  - `low` `reject` (edge) Zero tracked .sh passes vacuously — repo tracks 5 scripts; reaching zero is not everyday and the fix adds a branch.
  - `low` `reject` (edge) `i/` could be a symlink — no symlinked .sh exists; acceptance now documented.
  - `low` `reject` (edge) readText before waitFor can hang — same pattern as existing ValidateReleaseInputsScriptTest; git/bash here do not keep stdout open.
  - `medium` `patch` (edge) No-.git checkout passes the repo test — same root and fix as E1 (exit 2).
  - `low` `patch` (edge) `waitUntil(conditionDescription = ...)` / multi-line forms — grouped with blind B7 fix.
  - `low` `patch` (edge) waitUntilAtLeastOneExists/ExactlyOneExists/DoesNotExist/NodeCount defaults — grouped with B7 fix.
  - `low` `patch` (edge) testDebug Compose waits unguarded — grouped with B6 fix.
  - `low` `reject` (edge) Multiple @After or comment-only `autoAdvance = true` — LessonScreenTest has one @After whose code resets the clock; hypothetical.
  - `medium` `patch` (edge) KDoc claims "5 s or more" while 2_000/1_000 waits remain — KDoc reworded; waits raised (see B7).
  - `medium` `patch` (edge) AC/Code Map claim "every other waitUntil passes 5_000" false — same root as E10; code now matches except the documented 2_500 assertion bound.
  - `low` `patch` (edge) Spec says fail when index is not i/lf but script accepts i/ — grouped with B3 comment.
  - `medium` `patch` (gap) Attribute branch untested for a missing `*.sh` rule with LF content — fixture added, asserts `resolves to eol=unspecified` and no index error.
  - `low` `patch` (gap) Guard scope misses src/testDebug and src/sharedTest — grouped with B6 fix.
  - `low` `reject` (intent) androidTest compile checked only as ci.yml text — the intent limits tests to JVM; the real compile ran locally (`./gradlew ... :app:compileDebugAndroidTestKotlin` passed) and runs on every PR.
  - `false` `reject` (intent) Robolectric jar still downloaded (via Gradle) — the action asks for "cache or offline"; with robolectric.offline=true MavenArtifactFetcher is never used and setup-gradle caches the Gradle module cache.
  - `false` `reject` (intent) AppShellTest change is outside the named tests — the item's goal is eliminating intermittent Compose timeouts in CI; AppShellTest is a Robolectric Compose test in that suite; no change falls outside the four items.
  - `false` `reject` (intent) 3D timing tests rest only on file:line — the intent explicitly allows file:line proof for already-fixed parts (Cube3DRendererTest.kt:108-109,129-130).
  - `medium` `patch` (intent) Explicit 1 s / 2 s waits contradict the documented convention — grouped with E10 fix.
  - `low` `reject` (intent) Flake elimination under CI load not exercised at runtime — CI-load timing cannot be asserted deterministically in a JVM test; the guard plus a green full suite is the proxy the intent allows.

### 2026-11-20 — Review pass (follow-up)
- verdicts: 35 findings — high 0, medium 4, low 27, false 4, maybe-false 0
- findings:
  - `low` `reject` (blind) LF guard does not cover `gradlew` — intent scopes the guard to `*.sh`; a CRLF `gradlew` already fails loudly at the first `./gradlew` step on ubuntu, so the guard would add nothing there.
  - `low` `reject` (blind) Exit 2 covers only the first git call; in-loop `check-attr`/`ls-files --eol` failure exits with git's code — needs git to fail after `ls-files` succeeded on the same repo (not everyday); it still exits non-zero, so CI fails loudly; the fix adds per-call branches.
  - `low` `patch` (blind) Tests assert only non-zero exit, not 1/2 — `CheckShEolScriptTest` now asserts exit 1 for every rule violation and 2 for the not-a-repo case.
  - `low` `patch` (blind) Empty-file and `:(literal)` pathspec branches untested — added `an empty script under the LF rule passes` and `a script whose name has spaces and glob brackets is checked literally`; the empty-file test showed git reports `i/none`, not `i/`, so an empty or newline-free `.sh` failed the guard: script now accepts `i/none` and the comment is corrected.
  - `low` `patch` (blind) Not-a-repo test depends on where @TempDir lives — `exec` sets `GIT_CEILING_DIRECTORIES` to the temp dir's parent.
  - `medium` `patch` (blind) Timing guard is line-based: misses multi-line/`condition =` default waits, flags positional `waitUntilAtLeastOneExists(matcher, 5_000)` and later-line `timeoutMillis` — guard now scans whole files, reads each call's balanced argument list, accepts `timeoutMillis =` or the positional timeout slot per variant; mutation probe (multi-line `conditionDescription`, positional Exists, multi-line `timeoutMillis`) flagged only the default call.
  - `low` `patch` (blind) Guard skips `src/spike3dTest` (added to `test` with `-Pcubentia.spike3d=true`); empty-sources message names one dir — dir added to the scan list; message lists all dirs. androidTest is out of the unit-test guard (its waits all pass explicit timeouts).
  - `false` `reject` (blind) Clock check covers only LessonScreenTest, fragile regex — carried: createComposeRule is per test, so a paused clock cannot leak to the next test; the regex matches the real LessonScreenTest tearDown (test passes).
  - `low` `reject` (blind) `waitUntil(2_500) { stopped.get() }` is still a wall-clock bound — the spin loop polls `go()` continuously, so 2.5 s is ample under load; replacing it with a behaviour assertion is a test rewrite the intent excludes ("rewrite tests beyond the timing waits named here").
  - `low` `reject` (blind) android-all pin covers only SDK 34; no check against Robolectric's SDK table — every `@Config` and `robolectric.properties` use 34; a new SDK or Robolectric bump fails every Robolectric test loudly (missing jar); an SDK-table check is added machinery.
  - `low` `patch` (blind) `compileDebugAndroidTestKotlin` is not `assembleDebugAndroidTest`; ci.yml comment "PR CI scope only" is wrong (also runs on push to main) — the task choice is fixed by the intent's Approach; comment corrected to "CI scope only".
  - `low` `reject` (blind) CiWorkflowTest ignores job-level `if:`/`continue-on-error`, step order and multi-line `run: |` — nobody edits the build job's header in everyday work, and the step-level check covers the realistic edit; extra parsing adds complexity.
  - `low` `reject` (edge) Tracked `.sh` path containing a newline is split — no such path exists or is plausible; the fix restructures the loop.
  - `low` `reject` (edge) In-loop git failure exits with git's code, no `::error::` — same as blind row 2.
  - `low` `reject` (edge) `text=auto` reported as failure — only reachable by weakening the documented `*.sh text eol=lf` rule; failing loudly on that is the guard's job.
  - `low` `reject` (edge) Zero tracked .sh passes vacuously — carried: repo tracks 5 scripts; reaching zero is not everyday and the fix adds a branch.
  - `low` `reject` (edge) readText before waitFor can hang — carried: same pattern as existing ValidateReleaseInputsScriptTest; git/bash here do not keep stdout open.
  - `low` `patch` (edge) @TempDir inside a git work tree makes the not-a-repo test fail — grouped with blind row 5 (`GIT_CEILING_DIRECTORIES`).
  - `false` `reject` (edge) CiWorkflowTest `on:` parsing passes vacuously for quoted/inline `on:` — with an inline `on: [..]` the `^  pull_request:` regex matches nothing and the test fails; with a quoted key the fallback searches the whole file, where the only two-space `pull_request:` is the real trigger. No vacuous pass.
  - `low` `reject` (edge) Job-level `if:`/`continue-on-error` on `build` bypasses the gates — same as blind row 12.
  - `medium` `patch` (edge) Multi-line default-timeout calls get past the guard — grouped with blind row 6.
  - `low` `patch` (edge) Positional timeout wrongly flagged — grouped with blind row 6.
  - `low` `patch` (edge) LessonScreenTest comment claims the 2.5 s bound proves stop-before-deadline, but the 3 s deadline starts at the click — comment reworded: the bound is a timing bound that usually separates the two cases, not proof of the cause.
  - `medium` `patch` (edge) Spec claim "no waitUntil relies on the 1 s default" not fully enforced by a line-based guard — grouped with blind row 6.
  - `low` `patch` (gap) Exit-code contract 1/2 not pinned — grouped with blind row 3.
  - `low` `patch` (gap) Empty-file acceptance branch has no test — grouped with blind row 4 (test added; exposed `i/none`, script fixed).
  - `medium` `patch` (gap, other) Guard misses `waitUntil(condition = {...})` and split `conditionDescription` calls — grouped with blind row 6.
  - `low` `patch` (gap, other) 2_500 comment may overstate what the bound proves — grouped with edge row 11.
  - `low` `reject` (intent) The "build never depends on compileDebugAndroidTestKotlin" clause and the androidTest compile are checked only as ci.yml text — carried: the intent limits tests to JVM; the real compile runs locally (passed again) and on every PR.
  - `low` `reject` (intent) Working-tree `w/` EOL never checked; `i/mixed` untested — CI checks a fresh checkout whose `w/` follows the index and attributes the guard already checks; `i/mixed` takes the same "not i/lf" branch the `i/crlf` test covers.
  - `false` `reject` (intent) Offline Robolectric only checked as configuration, not runtime — every Robolectric test, `RobolectricOfflineTest` included, runs with `robolectric.offline=true`; Robolectric then loads only from `robolectric.dependency.dir`, so the passing suite is the runtime proof.
  - `low` `reject` (intent) 5 s is not the suite norm (route tests use 10_000) — the intent itself fixes the 5 s value.
  - `low` `reject` (intent) LessonScreenTest explicit waits edited beyond the default-timeout calls — they are timing waits of the same item, raised by the previous pass's triage; no other test code changed.
  - `low` `patch` (intent) Guard enforces no minimum and is line-based — the multi-line part is fixed with blind row 6; a minimum is not enforced on purpose (the 2_500 assertion bound is deliberate and visible in review).
  - `false` `reject` (intent) "Compose UI tests" could include androidTest — every androidTest `waitUntil` already passes an explicit timeout, so no call is left on the default.

## Design Notes

Offline Robolectric: with `robolectric.offline=true` Robolectric never calls `MavenArtifactFetcher`; it reads `<dependency.dir>/android-all-instrumented-<version>.jar`, which is exactly the Gradle cache file name. Gradle resolves it with its own retries, and `gradle/actions/setup-gradle` caches it between CI runs, satisfying "cache or offline". A Robolectric version bump that changes the android-all version fails every Robolectric test loudly (missing jar), so the pin cannot drift silently.

Compose clock item: the investigation found the retro's `LessonScreenTest` flake pattern (pausing the clock before UI readiness) already fixed by `16a663a6`/`87744375`, and the 3D timing tests already idle before pausing. The remaining CI-load timeout risk is the three 1 s default `waitUntil` calls; the guard test keeps the 5 s convention.

Device-only parts: none. Residual (not device): the new CI steps are first exercised on GitHub's ubuntu runner when the PR opens.

## Verification

**Commands:**
- `./gradlew -p build-logic test` -- expected: pass, including `CheckShEolScriptTest` and `CiWorkflowTest`.
- `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` -- expected: pass, Robolectric tests in offline mode.
- `bash .github/scripts/check-sh-eol.sh` -- expected: exit 0.

## Auto Run Result

Status: done

**Summary:** PR CI now compiles `:app:compileDebugAndroidTestKotlin` and runs a shell-script LF guard; Robolectric runs with `robolectric.offline=true` from a Gradle-resolved `android-all-instrumented` jar (no MavenArtifactFetcher); the Compose clock item was found mostly fixed on main (`16a663a6`, `87744375`; `Cube3DRendererTest.kt:108-109,129-130`), and the remaining short/default `waitUntil` timeouts were raised and are now guarded by a JVM source test.

**Files changed:**
- `.github/scripts/check-sh-eol.sh` -- new guard: every tracked `*.sh` must resolve to `text` set, `eol=lf`, and be `i/lf` in the index; git failure exits 2.
- `.github/workflows/ci.yml` -- "Shell scripts keep LF" step; "Spotless and build" also compiles androidTest.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckShEolScriptTest.kt` -- 7 fixture tests for the guard.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CiWorkflowTest.kt` -- 4 tests: PR trigger, both steps present, not skippable.
- `gradle/libs.versions.toml` -- `robolectric-android-all` pin and library.
- `app/build.gradle.kts` -- resolvable config + `syncRobolectricAndroidAll`; Test tasks offline, jar tracked as input.
- `app/src/test/kotlin/dev/cubentia/app/RobolectricOfflineTest.kt` -- offline mode and jar presence.
- `app/src/test/kotlin/dev/cubentia/app/ComposeTestTimingGuardTest.kt` -- no default-timeout waits; LessonScreenTest restores the clock.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt` -- explicit/raised waitUntil timeouts.

**Review findings:** 31 total; 18 patched (8 medium, 10 low, grouped into 9 root causes), 0 deferred, 13 rejected (4 false, 9 low) with reasons in the Review Triage Log.

**Follow-up review recommended:** true -- 2+ medium entries were patched (git-environment isolation of the fixture tests and the guard's git-failure path); their behaviour on GitHub's ubuntu runner is unverified until the PR runs. Patched counts by verdict: high 0, medium 8, low 10.

**Verification:** `bash .github/scripts/check-sh-eol.sh` exit 0; `./gradlew -p build-logic test --rerun` pass (CheckShEolScriptTest 7/7, CiWorkflowTest 4/4); `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL (RobolectricOfflineTest 1/1, ComposeTestTimingGuardTest 2/2, LessonScreenTest 94/94, AppShellTest 49/49, whole Robolectric suite in offline mode).

**Device-only (PO) items:** none.

**Residual risks:** new CI steps first run on GitHub's runner when the PR opens; the android-all pin must move with Robolectric upgrades (fails loudly if not); ComposeTestTimingGuardTest reads one line per call, so a `timeoutMillis` on a later line would be a false positive.

### Follow-up review pass (2026-11-20)

**Summary:** Follow-up review of the ai-b3 change. Four review layers (all Claude; Codex guard off) reported 35 findings; 15 were patched in 6 root-cause groups, none deferred, 20 rejected.

**Files changed in this pass:**
- `.github/scripts/check-sh-eol.sh` -- accepts `i/none` (empty or newline-free file), not the `i/` value git never prints.
- `.github/workflows/ci.yml` -- comment "PR CI scope only" corrected to "CI scope only".
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CheckShEolScriptTest.kt` -- exact exit codes (1 violation, 2 git failure), `GIT_CEILING_DIRECTORIES`, empty-file and literal-name fixtures (9 tests).
- `app/src/test/kotlin/dev/cubentia/app/ComposeTestTimingGuardTest.kt` -- whole-file, argument-aware scan for default-timeout waits; also scans `src/spike3dTest`.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt` -- comment on the 2_500 bound no longer claims proof of the cause.

**Review findings:** 35 total; patched 15 (medium 4, low 11) in 6 groups; deferred 0; rejected 20 (4 false, 16 low), each with its reason in the Review Triage Log.

**Follow-up review recommended:** false -- follow-up pass patched no `high` entry. Patched counts by verdict: high 0, medium 4, low 11.

**Verification:** `bash .github/scripts/check-sh-eol.sh` exit 0; `./gradlew -p build-logic test --rerun` pass (CheckShEolScriptTest 9/9, CiWorkflowTest 4/4); mutation probe confirmed the guard flags a multi-line `waitUntil(conditionDescription = ...)` and passes positional/multi-line explicit timeouts (probe removed); `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL (ComposeTestTimingGuardTest 2/2, RobolectricOfflineTest 1/1, LessonScreenTest 94/94, AppShellTest 49/49).

**Residual risks:** the new CI steps first run on GitHub's runner when the PR opens; the guard's paren matcher ignores parens inside string literals (a string with an unbalanced paren inside a wait call could mis-scope one call); the android-all pin must move with Robolectric upgrades (fails loudly if not).
