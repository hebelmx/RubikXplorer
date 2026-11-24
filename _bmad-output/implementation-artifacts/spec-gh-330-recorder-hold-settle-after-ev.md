---
title: 'GH-330: recorder corner holds always record how they were exposed (settle outcome + EV index)'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: '9ea3274df8bd05d71933a75a99074a595faa3f86'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred:
  - summary: >-
      No test exercises the Activity/screen wiring that carries evIndex (and the settle) from the capture frame into the saved corner-N.json; the null defaults on holdDiagnostics(evIndex) and CapturedFrame.evIndex would hide a dropped argument.
    evidence: |-
      Verification-gap layer: every holdDiagnostics( call except FixtureRecorderScreen.kt:423 is in tests that pass values directly; no JVM test runs FixtureRecorderActivity or FixtureRecorderScreen. Cheap guard: drop the = null default so the compiler forces callers (touches FixtureExportTest, which other sessions edit).
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt:423
    severity: medium
  - summary: >-
      evIndex is recorded without the camera's EV step, so the index alone does not give the EV offset across devices.
    evidence: |-
      exposureState.exposureCompensationStep differs per camera (1/3 vs 1/2 EV); the intent asked for the index only.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt
    severity: low
  - summary: >-
      The already-recorded 10.20 holds with settle null (group 3 sm-s721u1_stka_bar-warm-2, group 9 sm-s721u1_stla_ceilback hold 2) need a decision on how the 10.21 refit treats them, or a re-record.
    evidence: |-
      Fixtures are not edited by this change; the null settles stay in the evidence.
    severity: low
---

<intent-contract>

## Intent

**Problem:** In the debug fixture recorder's corner mode, a hold captured when no 10.22 cube metering ran since the last reset (after a capture's `forgetSettle()` or a camera rebind; seen on S24 FE groups where the operator used EV+) saves `diagnostics.settle: null`, so the replay/baseline report and the 10.21 refit cannot tell how that hold was exposed. The EV compensation index used is not recorded at all.

**Approach:** Every saved corner hold records a non-null settle: the metering's result when one ran (unchanged), else an explicit new outcome `manual-ev` (no cube metering; the camera's own AE plus the recorded EV index). Each hold's `diagnostics` also records the EV compensation index read at the capture frame. The decision lives in a pure, JVM-tested rule in `RecorderRules.kt`.

## Boundaries & Constraints

**Always:** Backward compatible fixture JSON: the new `CornerHoldDiagnostics.evIndex` is nullable with default `null` so existing `corner-N.json` records (with or without `settle`) still decode; existing outcomes (`settled`, `timeout`, `settling`) and the settled/settling paths keep their current values. `evIndex` is null on a camera without exposure compensation. Keep the diff minimal (other sessions edit `FixtureRecorderActivity.kt`/`FixtureExport.kt` for torch work).

**Never:** No change to Quick Scan (`ScanFrameSource.kt`, `CornerHold.kt`), the metering/locking behaviour, `group.json` manifest shape, schema version, or the replay tests' scoring. No edits to existing fixture files. No change to `FixtureExport.kt`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Metering settled | settled = true, last settle reported | that settle (outcome settled/timeout, its lock) | none |
| Still settling | settled = false | outcome `settling`, lock `none`, elapsed + futureMs | none |
| No metering since reset (e.g. after EV step) | settled = null | outcome `manual-ev`, lock `none`, settleMs null, futureMs null | none |
| Settled but nothing reported (defensive) | settled = true, last = null | outcome `manual-ev` (never null) | none |
| EV index | camera supports EV comp, index 2 | `diagnostics.evIndex = 2` | unsupported camera -> null |
| Old record | JSON without `evIndex` | decodes, evIndex null | none |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `SettleOutcome` (~l.155, add `MANUAL_EV` `@SerialName("manual-ev")`), `CornerSettleDiagnostics` kdoc (~l.167), `CornerHoldDiagnostics` (~l.186, add `val evIndex: Int? = null` last). `CornerFixtureJson` uses default Json (ignoreUnknownKeys false, encodeDefaults true): defaults keep old records decodable.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- `holdDiagnostics` (~l.604): add `evIndex: Int? = null` param passed to the data class; add pure `captureSettle(settled: Boolean?, last: CornerSettleDiagnostics?, settlingMs: Long?, futureMs: Long?): CornerSettleDiagnostics` per the matrix, plus `internal const val SETTLE_LOCK_NONE = "none"`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- `CapturedFrame` (~l.79): add `evIndex: Int? = null`; `settleAtCapture()` (~l.380) returns non-null via `captureSettle`; capture site (~l.605) also fills `evIndex` from `camera?.cameraInfo?.exposureState` (only when `isExposureCompensationSupported`); `LOCK_NONE` (~l.846) replaced by `SETTLE_LOCK_NONE`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- l.423: pass `shot.evIndex` to `holdDiagnostics`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- existing `holdDiagnostics` test ~l.501; add new test here. `SettleTracker` (`app/src/camera/kotlin/.../ScanFrameSource.kt` ~l.871) is internal and reachable from testDebug: a reset tracker's `outcome(now)` is null.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/LiveFixtureTest.kt` -- ~l.282 diagnostics round-trip; add backward-compat/round-trip assertions for `manual-ev` and `evIndex`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../LiveFixture.kt` -- add `MANUAL_EV` outcome and `evIndex` field with kdoc -- schema for the explicit outcome and EV index.
- `app/src/camera/debug/.../RecorderRules.kt` -- add `captureSettle`, `SETTLE_LOCK_NONE`, `holdDiagnostics(evIndex)` -- testable, never-null decision.
- `app/src/camera/debug/.../FixtureRecorderActivity.kt` -- `settleAtCapture` non-null via `captureSettle`; `CapturedFrame.evIndex` filled at capture -- wire the rule.
- `app/src/camera/debug/.../FixtureRecorderScreen.kt` -- pass `shot.evIndex` -- record it per hold.
- `app/src/testDebug/.../RecorderRulesTest.kt` -- test: tracker reset (as after a capture), EV step, no metering request -> `captureSettle` gives `manual-ev` (not null) and `holdDiagnostics(..., evIndex = 2)` carries both; also the settled/settling rows.
- `core/domain/src/test/.../LiveFixtureTest.kt` -- round-trip `manual-ev` + `evIndex`; a record JSON without `evIndex` decodes with null.

**Acceptance Criteria:**
- Given corner mode and no cube metering requested since the last capture, when the operator steps EV and captures, then the saved hold's `diagnostics.settle.outcome` is `manual-ev` and `diagnostics.evIndex` is the index in use.
- Given a metering that settled before the capture, when the hold is saved, then `diagnostics.settle` is unchanged from today's behaviour and `evIndex` is recorded too.
- Given an existing fixture `corner-N.json`, when it is decoded, then it decodes as before.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 23 findings — high 0, medium 3, low 17, false 3, maybe-false 0
- findings:
  - `[low]` `[patch]` (intent) manual-ev is written for every no-metering capture, even at evIndex 0 — MANUAL_EV KDoc now says it only means no cube metering ran; evIndex 0 = no compensation.
  - `[medium]` `[defer]` (intent) tests exercise the pure helper and JSON, not the Activity/camera path — grouped with the verification-gap wiring finding; deferred.
  - `[low]` `[patch]` (intent) evIndex may be the requested rather than applied index — KDoc reworded to "the index the camera was set to at the capture (the requested index)".
  - `[false]` `[reject]` (intent) old readers cannot decode manual-ev — the intent requires existing fixtures to stay decodable, which is tested; forward compatibility of old code is not asked.
  - `[false]` `[reject]` (intent) core/domain edited outside app/src/camera/debug — the diagnostics schema lives only in LiveFixture.kt; recording a new outcome and field is impossible without it.
  - `[low]` `[reject]` (intent) LOCK_NONE moved to RecorderRules.kt — needed by the pure rule; a two-line move with no named harm.
  - `[medium]` `[patch]` (blind) manual-ev hold 2 records lock "none" while the hold-1 AWB lock is held (onCaptured keeps awb) — captureSettle takes required awbLocked; manual-ev lock word is "awb" then; test and KDoc added.
  - `[low]` `[patch]` (blind) captureSettle KDoc says an EV step resets the settle — reworded to "after a capture or a camera rebind".
  - `[low]` `[patch]` (blind) manual-ev name suggests an EV action that may not have happened — KDoc clarified (same fix as the intent row).
  - `[low]` `[patch]` (blind) evIndex KDoc claims the frame's index — reworded (requested index).
  - `[low]` `[defer]` (blind) EV step size not recorded — the intent asks for the index only; deferred.
  - `[low]` `[patch]` (blind) RecorderRulesTest comment claims an EV step is simulated — comment now describes the reset after the previous capture and that an EV step requests no metering.
  - `[low]` `[patch]` (blind) near-vacuous evIndex assertion in LiveFixtureTest — deleted; the strip-and-decode checks stay.
  - `[false]` `[reject]` (blind) settled=true with last=null collapses into manual-ev — unreachable: settledNow() sets lastSettle under the same @Synchronized lock the first time it reports, and requestCubeMetering clears both together; the branch is only a never-null guard.
  - `[low]` `[patch]` (blind) tools/corner-model/README.md:89 omits evIndex and manual-ev — sentence extended.
  - `[low]` `[defer]` (blind) legacy null-settle holds in the 10.20 evidence have no follow-up — fixtures are out of scope; deferred.
  - `[low]` `[reject]` (blind) SETTLE_LOCK_NONE lives apart from the Quick Scan lock words — no caller diverges; the recorder-only words stay with the recorder rule.
  - `[low]` `[patch]` (edge) evIndex is the requested target right after an EV tap — KDoc reworded (same fix).
  - `[low]` `[reject]` (edge) non-volatile `camera` read on the analyzer thread — pre-existing pattern (lockExposure already reads it there from settledNow); a capture frame exists only with a bound camera.
  - `[low]` `[reject]` (edge) cornerMode flipping between the Capture press and the analyzer frame — the operator cannot switch mode within one frame of a capture; the fix would add plumbing to the capture request.
  - `[low]` `[reject]` (edge) unbound camera and unsupported EV both give evIndex null — analyzer frames only arrive from a bound camera, so the unbound case does not occur at capture.
  - `[low]` `[patch]` (edge, claim) spec/KDoc say "index at the capture frame" — KDoc reworded (same fix).
  - `[medium]` `[defer]` (verification-gap) evIndex wiring Activity -> CapturedFrame -> holdDiagnostics is untested and null defaults hide an omission — filed disposition defer; the guard touches FixtureExportTest, which other sessions edit.

## Design Notes

`manual-ev` names the case the issue asked for: no 10.22 metering ran, so exposure was the camera's continuous AE with the operator's EV compensation (recorded in `evIndex`, which may be 0). Lock word `none`: with no metering since the last reset, the AE lock was already released (`onCaptured` / `cleared`).

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-330 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply :core:domain:test :app:testDebugUnitTest --tests '*RecorderRulesTest*' --tests '*FixtureExportTest*'` -- expected: pass
- `... ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** Every corner hold the debug recorder saves now has a non-null `diagnostics.settle`: the metering's settle when one ran (unchanged), `settling` while it settles (unchanged), or the new outcome `manual-ev` (lock `awb` while the hold-1 white balance stays locked, else `none`) when no cube metering ran since the last capture or rebind. Each hold's `diagnostics.evIndex` records the EV compensation index the camera was set to (null without exposure compensation). Old records decode unchanged.

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `SettleOutcome.MANUAL_EV` ("manual-ev"), `CornerHoldDiagnostics.evIndex` (default null), KDoc for the `awb` lock word.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- pure `captureSettle(...)` (never null), `SETTLE_LOCK_NONE`/`SETTLE_LOCK_AWB`, `holdDiagnostics(evIndex)`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- `settleAtCapture()` uses `captureSettle`; `CapturedFrame.evIndex` from `evIndexNow()`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- passes `shot.evIndex`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- capture with no metering after a reset gives manual-ev (none/awb) and carries evIndex; settled/settling rows.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/LiveFixtureTest.kt` -- manual-ev + evIndex round-trip; record without evIndex decodes.
- `tools/corner-model/README.md` -- diagnostics sentence lists manual-ev and evIndex.

**Review findings:** 23 findings; 7 patch entries applied (1 medium: AWB lock word; low: KDoc wording x3 groups, test comment, vacuous assertion, README). Deferred 3 (medium: untested Activity/screen evIndex wiring; low: EV step size; low: legacy null-settle holds). Rejected 9 with reasons in the triage log (3 false, 6 low).

**Follow-up review recommended:** false -- patched counts: high 0, medium 1, low 6.

**Verification:** `./gradlew spotlessApply :core:domain:test :app:testDebugUnitTest --tests '*RecorderRulesTest*' --tests '*FixtureExportTest*'` exit 0 (RecorderRulesTest 43/0 failures, LiveFixtureTest 9/0); `./gradlew spotlessCheck build` BUILD SUCCESSFUL after the patches.

**Residual risks:** no on-device check of the EV+ case; non-Kotlin report tooling reading `settle.outcome`/`lock` will now see `manual-ev` and `awb`; `FixtureRecorderActivity.kt` is also edited by the #317/#328 torch sessions (small overlap).
