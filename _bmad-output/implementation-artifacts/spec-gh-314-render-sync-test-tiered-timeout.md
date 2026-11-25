---
title: 'GitHub #314: device-tiered timeout for the 10,000-step render sync test'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: '116a0af8dbf1af152b465dddfd1a8c91dac7daa3'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Release docs still describe the fixed 300 s limit and the temporary 15-minute workaround for #314.
    evidence: |-
      docs/release-gates-v1.0.md gate 3 row says the committed 300 s limit is too tight (#314, open);
      docs/evidence/release-device-session/README.md:69 describes only the temporary 15-minute limit.
      This build was limited to test code by the intent; update after the PO device run confirms the tiers.
    location: >-
      docs/release-gates-v1.0.md; docs/evidence/release-device-session/README.md:69
    severity: low
---

<intent-contract>

## Intent

**Problem:** `tenThousandRandomStepsKeepTheCubeEqualToTheVirtualState` has a fixed `@Test(timeout = 300_000)`. The Galaxy S24 FE passes in 183 s, but the minimum reference device (Lenovo TB128XU, 4 GB RAM) needs 464.6 s, so the test always fails there at 300 s (GitHub #314).

**Approach:** Choose the timeout from three device speed tiers (MINIMUM / MID / HIGH) keyed on the device's total RAM. A pure tier rule lives in the shared test source set so a JVM test covers it; the instrumented test applies the chosen limit through a JUnit rule scoped to that one test method.

## Boundaries & Constraints

**Always:** At most three tiers. The MINIMUM tier is 900 s (about 1.9x the Lenovo's 464.6 s). Any device the rule cannot place faster (including unknown or non-positive RAM, and emulators with little RAM) gets MINIMUM. The timeout still runs the test body on a separate thread inside the compose rule, as `@Test(timeout)` did. The test's steps, seed and assertions are unchanged.

**Never:** No production (`app/src/main`) code. No change to the other tests in `Cube3DRenderSyncTest`, including the flaky `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence`, and no timeout added to them. No connected test run in this build. No change to step count or test logic to make it faster.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Minimum device | total RAM < 6 GiB (Lenovo ~3.7e9 B) | MINIMUM, 900,000 ms | No error expected |
| Mid device | 6 GiB <= RAM < 10 GiB (S24 FE ~7.3e9 B) | MID, 600,000 ms | No error expected |
| High device | RAM >= 10 GiB | HIGH, 300,000 ms | No error expected |
| Unknown | RAM <= 0 | MINIMUM, 900,000 ms | Falls to the safest tier |
| Boundaries | exactly 6 GiB / exactly 10 GiB | MID / HIGH | No error expected |

</intent-contract>

## Code Map

- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt:77` -- `@Test(timeout = 300_000)` on the 10,000-step test; compose rule `rule` at :54-55 (default rule order -1).
- `app/src/sharedTest/kotlin/` -- source set compiled into both `test` and `androidTest` (`app/build.gradle.kts:31-32`); home for the pure tier rule.
- `app/src/test/kotlin/dev/cubentia/app/render/` -- existing JVM render tests (JUnit 4); home for the tier rule test.
- Lenovo TB128XU = minimum reference device, Snapdragon 680, 4 GB RAM (SCP 2026-11-12, read-only). S24 FE = mid-range reference device, 8 GB.

## Tasks & Acceptance

**Execution:**
- `app/src/sharedTest/kotlin/dev/cubentia/app/render/DeviceSpeedTier.kt` -- add `enum class DeviceSpeedTier(val renderSyncTimeoutMillis: Long)` with MINIMUM 900_000, MID 600_000, HIGH 300_000 and `fromTotalMemoryBytes(bytes: Long)` per the matrix; KDoc cites #314 and the measured times -- pure, JVM-testable rule.
- `app/src/test/kotlin/dev/cubentia/app/render/DeviceSpeedTierTest.kt` -- JUnit 4 tests for every matrix row, plus that MINIMUM covers 465 s with at least 1.5x margin and that there are at most three tiers.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/DeviceTieredTimeout.kt` -- a method annotation `@DeviceTieredTimeout` and a `TestRule` that, only for annotated methods, reads `ActivityManager.MemoryInfo.totalMem` from the instrumentation target context, picks the tier, logs tier and limit, and wraps the statement in `org.junit.rules.Timeout`; unannotated methods pass through unchanged.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt` -- replace `@Test(timeout = 300_000)` with `@Test @DeviceTieredTimeout`; add the rule as `@get:Rule(order = 1)` so it is inside the compose rule.

**Acceptance Criteria:**
- Given the Lenovo TB128XU (4 GB), when the connected 10,000-step test runs, then its limit is 900 s and a 464.6 s run passes (PO device run after the PR).
- Given the other three tests in the class, when they run, then no timeout applies to them, as before.
- Given `./gradlew :app:testDebugUnitTest --tests '*DeviceSpeedTierTest*' :app:compileDebugAndroidTestKotlin`, when run, then the tier tests pass and the instrumented test compiles.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 21 findings — high 0, medium 0, low 12, false 9, maybe-false 0
- findings:
  - `[low]` `[reject]` (verification-gap) DeviceTieredTimeoutRule wiring has no runtime test; dropping the annotation or rule silently removes the timeout — real, but reaching the rule needs a connected test or restructuring into sharedTest; the PO Lenovo run checks the `DeviceTieredTimeout` log line (tier=MINIMUM limit=900000 ms); the KDoc patch below names the dependency.
  - `[false]` `[reject]` (intent) Lenovo/S24 FE totalMem never read from the devices — totalMem cannot exceed physical RAM, so the 4 GB Lenovo is below 6 GiB; an 8 GB S24 FE reports ~7.3-7.6e9 B, above 6.44e9 B.
  - `[low]` `[reject]` (intent) HIGH tier (>= 10 GiB, 300 s) unmeasured; a slow high-RAM device would get the old limit — no reference device or reference AVD (max hw.ramSize 6144, docs/emulators.md:68) falls in HIGH; fixing means redesigning the tiers.
  - `[false]` `[reject]` (intent) criterion (3) note not in the reviewed diff — the note is in this spec's Design Notes, committed with the change.
  - `[low]` `[reject]` (intent) unrequested Log.i of tier and limit — harmless and is the PO run's only way to see the chosen tier.
  - `[false]` `[reject]` (edge) getSystemService(ACTIVITY_SERVICE) may be null — ActivityManager is a core system service always present on Android; no reachable path.
  - `[low]` `[reject]` (edge) slow device or emulator with >= 10 GiB gets 300 s — same root as the HIGH-tier row above; same reason.
  - `[false]` `[reject]` (edge) a < 6 GiB device more than 1.9x slower than the Lenovo can time out — the Lenovo is the minimum reference device (SCP 2026-11-12); slower devices are below the supported floor.
  - `[low]` `[patch]` (edge) annotation without the rule silently does nothing — added a KDoc sentence on @DeviceTieredTimeout that the class must declare DeviceTieredTimeoutRule inside the compose rule.
  - `[low]` `[patch]` (edge) removing or reordering the rule loses the timeout or its thread placement — grouped with the row above (KDoc) and the explicit order patch below.
  - `[low]` `[reject]` (blind) HIGH tier has no measurement behind it — same root as the HIGH-tier row; same reason.
  - `[false]` `[reject]` (blind) CI emulator now gets 900 s instead of 300 s, hiding a slowdown — the timeout is a hang guard for the NFR-4 sync check, never a performance gate; performance gates are the Macrobenchmarks.
  - `[low]` `[reject]` (blind) tier only goes to logcat, not JUnit XML — wrapping TestTimedOutException adds a branch and new surface; unlikely to matter in everyday use.
  - `[false]` `[reject]` (blind) missing ActivityManager crashes the rule — same refutation as the edge row.
  - `[low]` `[patch]` (blind) annotation does nothing without the rule — grouped with the KDoc patch.
  - `[low]` `[patch]` (blind) rule order relies on the unstated default (-1) of the compose rule — compose rule now `@get:Rule(order = 0)`, timeout rule stays `order = 1`.
  - `[low]` `[patch]` (blind) margin test checks 1.5x of a rounded 465 s while docs claim about 1.9x of 464.6 s — test now uses 464_600 ms and asserts >= 1.9x (renamed theMinimumTierGivesTheLenovoRunAtLeastOnePointNineTimesMargin).
  - `[false]` `[reject]` (blind) tier ordering untested — the exact-value assertions (900_000 / 600_000 / 300_000 per tier) fail on any swap.
  - `[false]` `[reject]` (blind) thereAreAtMostThreeTiers passes if a tier is deleted — deleting a tier breaks compilation of the per-tier tests; the intent's rule is "at most" three.
  - `[low]` `[defer]` (blind) docs/release-gates-v1.0.md gate 3 row and docs/evidence/release-device-session/README.md:69 still describe the 300 s limit / temporary 15-minute workaround — the intent limits this build to test code; update them after the PO device run.
  - `[low]` `[reject]` (blind) Timeout built without withLookingForStuckThread — the previous @Test(timeout) did not have it either; an enhancement, not a defect.

## Design Notes

RAM is the tier signal because it separates the two reference devices cleanly (about 3.7e9 vs 7.3e9 bytes reported) and needs no API-level checks; `Build.VERSION.MEDIA_PERFORMANCE_CLASS` is not reliably declared on either device. Erring low (a slow 6 GB phone gets MID, 600 s) still leaves 1.3x over the Lenovo's time.

**Noted, not changed (#314):** in the first Lenovo run `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence` failed once with "No compose hierarchies found" and passed when re-run alone. It is left as is; watch it for flakiness in the PO device run.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.render.DeviceSpeedTierTest' :app:compileDebugAndroidTestKotlin` -- expected: BUILD SUCCESSFUL, tests pass.
- `./gradlew spotlessCheck` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** `tenThousandRandomStepsKeepTheCubeEqualToTheVirtualState` no longer has a fixed 300 s limit. Its timeout now comes from three RAM-keyed device tiers: MINIMUM 900 s (below 6 GiB or unknown; the Lenovo TB128XU, about 1.94x its 464.6 s run), MID 600 s (6 to 10 GiB; the S24 FE), HIGH 300 s (10 GiB and up). The pure tier rule is JVM-tested; the instrumented test applies it through a rule scoped to that one method.

**Files changed:**
- `app/src/sharedTest/kotlin/dev/cubentia/app/render/DeviceSpeedTier.kt` -- new pure tier enum and `fromTotalMemoryBytes` rule.
- `app/src/test/kotlin/dev/cubentia/app/render/DeviceSpeedTierTest.kt` -- new JVM tests for every matrix row, boundaries, 1.9x Lenovo margin, at most three tiers.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/DeviceTieredTimeout.kt` -- new `@DeviceTieredTimeout` annotation and `DeviceTieredTimeoutRule` (reads totalMem, logs tier, wraps in JUnit `Timeout`).
- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt` -- `@Test(timeout = 300_000)` replaced by `@Test @DeviceTieredTimeout`; compose rule order 0, timeout rule order 1.

**Review findings:** 21 findings. Patched 3 entries (all low): annotation KDoc naming the required rule, explicit rule order, tightened margin test. Deferred 1 (low): release docs still describe the old limit. Rejected the rest with the reasons in the Review Triage Log above.

**Follow-up review recommendation:** false. Patched counts: high 0, medium 0, low 3.

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.render.DeviceSpeedTierTest' :app:compileDebugAndroidTestKotlin spotlessCheck` -- exit 0; DeviceSpeedTierTest 7 tests, 0 failures, 0 skipped. No connected tests run (no device).

**Residual risks:** The rule wiring and the real totalMem readings are only checked by the PO device run on the Lenovo TB128XU and S24 FE (look for the `DeviceTieredTimeout` log line: Lenovo tier=MINIMUM limit=900000 ms, S24 FE tier=MID). HIGH (300 s) has no measured device. Watch `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence` for the "No compose hierarchies found" flake in that run (unchanged here).
