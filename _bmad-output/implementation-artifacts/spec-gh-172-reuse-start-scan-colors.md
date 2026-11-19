---
title: 'GH #172: Quick Check without calibration reuses the run start-scan colors for 2-face checks'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: '9224e195d8e965410b6350e1815139317cbd6cc9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** On a device with no saved Color Calibration every Quick Check asks for all 6 faces (`QuickCheckContract.createIntent` falls back to the full scan when `!hasCalibration`; `GuidedScanActivity` only sets `quickFaces` when `scan.saved != null`). The Guided 6-face Scan that started the physical run already measured this cube's six centers in this light, and that is thrown away.

**Approach:** Option (b), PO 2026-11-17: when an accepted Guided 6-face Scan starts a physical run, keep its six measured centers (`SelfCalibration.from(samples)` with no saved calibration) in memory, owned by `SessionHost` beside the session (not in `SessionState`), and use them as the reference for 2-face Quick Checks in that same run when no saved calibration exists. A lighting guard makes faces doubtful when the captured centers drifted from that reference. No start scan, or a saved calibration: behavior unchanged.

## Boundaries & Constraints

**Always:**
- Run-scan colors live only in memory: a private field of the `@Singleton SessionHost`, set and cleared only inside `dispatch` on the confined loop, from the pure expiry rule below. They cross Activities only as Intent extras (Activity result of the start scan; Quick Check launch intent).
- They are set only when the start-scan event is dispatched with them and its transition applied (`transition.state !== before`, session active, Sync State not `PHYSICAL_AWAITING_CONFIRM`). Start-scan events: `EditorEntered` from the lesson's full confirm scan (MainActivity `effectId == -1L` path) and `SolvePlaybackStarted(fromAcceptedScan = true)`.
- They expire (cleared in the same `dispatch`) when: the next state is IDLE / not active; the event starts or restores a session (`LessonStarted`, `PracticeStarted`, `SolvePlaybackStarted`, `SessionRestored`, `CheckpointRestored`, `SessionEnded`, `SolvePlaybackEnded`), unless this very dispatch sets new ones; the next Sync State is `PHYSICAL_AWAITING_CONFIRM`; or the Sync State goes from non-`VIRTUAL` to `VIRTUAL` (switch back to virtual). Process death loses them (6 faces again).
- A saved calibration always wins: when `CalibrationRepository.load()` returns one, run-scan colors are neither sent nor used, and today's code path runs.
- Only a Guided 6-face Scan (`scan.isGuidedRun`, which includes the full Quick Check used as the lesson's confirm scan) returns colors, and only when `SelfCalibration.from(doneSamples)` (no saved) is `Calibrated`; Quick Scan (corner views), case capture and calibration mode return none.
- Lighting guard (run-scan reference only): the light is "unchanged" only when at least one captured Quick Check sample has a plain center (`centerPlain != false`) and every plain captured center is within `StickerClassifier.CLOSE_CALL_DELTA_E` (ΔE76) of the reference center of its `centerColor`. Otherwise every captured face is doubtful: `quickCheckOf` keeps no face and `quickCheckRetakes` lists each captured face (asked once more, existing copy and cap).
- Certainty stays produced only by `tutor/Assess`; a Partial is never completed from expected (AD-5).

**Never:**
- Never store run-scan colors in `SessionState`, `SessionSnapshot`, `Checkpoint`, Room, `CalibrationRepository`, `onSaveInstanceState`, SharedPreferences or a file; never call `UpdateCalibration`.
- No new `SessionEvent`, port, module, persisted entity, or Learner-visible text (no "Calibrate colors for quicker checks" hint). No change to the `ScanLauncher` port signature. No planning-artifact edits. No device checks.
- Do not change behavior when a saved calibration exists, nor the 6-face path when no start scan exists.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Run-scan 2-face check | No saved calibration; physical run started from an accepted Guided 6-face Scan; Quick Check for {U,R} | Subset Quick Check intent with run-scan extras; faces classified against run-scan centers | No error expected |
| No start scan | No saved calibration; physical run confirmed by moves/Editor without scan | Full 6-face Quick Check (unchanged) | — |
| Saved calibration | Calibration row exists, with or without run-scan colors | Subset intent without run-scan extras; `saved` used (unchanged) | — |
| Back to virtual | `ModeSwitched(virtual)` after the start scan, then physical again | Colors cleared; next Quick Check needs 6 faces | — |
| Run ends | `SessionEnded`, new lesson/practice/playback, restore, Try again (CheckpointRestored) | Colors cleared | — |
| Light changed | Quick Check plain centers ≥ 10 ΔE from run-scan centers | Every captured face doubtful: retaken once, then no evidence; never VERIFIED | Empty Partial → existing UNKNOWN / retry path |
| Logo-only capture | Only captured center is a logo (`centerPlain == false`) | Doubtful (no light probe) | Same as above |
| Unusable start scan | `SelfCalibration.from` is `NeedsCalibration` | No colors returned; 6 faces | — |
| Malformed extras | Run-scan extra missing / wrong length / invalid Lab | Decoded as `null` → today's behavior | `runCatching` → null |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- `dispatch` (≈L645) is the one confined place state changes; add the private `runScanColors` field, a `dispatch(runScanColors: CalibrationCenters?, event)` overload (or default parameter keeping all current call sites compiling) and a public read `runScanColors(): CalibrationCenters?`. Also holds `sessionId` regeneration on start events (≈L677) as precedent for non-`SessionState` host bookkeeping.
- New `app/src/main/kotlin/dev/cubentia/app/session/RunScanColors.kt` -- pure `internal fun keepsRunScanColors(before, event, after): Boolean` and set-eligibility check (expiry rule above), unit-testable without coroutines.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanFlowContract.kt` + `ScanOutcome.kt` -- `ScanOutcome.Accepted(state, runScanColors: CalibrationCenters? = null)`; `encode` writes an extra (18 doubles in `Color.entries` order) when non-null, `decode` reads it leniently (invalid → null). Put the Lab (de)serialization helpers here (or a small `RunScanColorsExtra` object in `app/src/main/.../scan/`) shared with `QuickCheckContract`.
- `app/src/main/kotlin/dev/cubentia/app/scan/QuickCheckCapture.kt` -- `QuickCheckInput` gains `runScanColors: CalibrationCenters? = null`; `createIntent` goes subset when `hasCalibration || runScanColors != null` (still full for `fullScan` / 6 faces) and adds `EXTRA_RUN_SCAN_COLORS` only when `!hasCalibration`. `QuickCheckOutcome.Observed` gains `runScanColors: CalibrationCenters? = null`; `decode` of the full path carries it from `ScanFlowContract.decode`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- QuickCheck result handler (≈L185–205): the `effectId == -1L` full start scan dispatches `EditorEntered` with the outcome's run-scan colors. `QuickCheckCapture` launcher (≈L270): pass `sessionHost.runScanColors()` into `QuickCheckInput`. Never put colors into `onSaveInstanceState`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- keep `scannedColors` beside `scanned` (set on `Accepted`, cleared wherever `scanned` is cleared); expose `acceptedScanColors()`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `solveOnSolved` (≈L322): dispatch `SolvePlaybackStarted` with `solveFlow.acceptedScanColors()` when `scanned`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `onCreate` load (≈L131): when `scan.saved == null`, read run-scan colors from the intent into `scan.runScan`; set `quickFaces` when `saved ?: runScan` exists. `handle` (≈L530): on a guided run's `Done`, keep `SelfCalibration.from(done.samples)` (no saved) centers when `Calibrated` and `scan.saved == null` in a VM field; `quickCheckOf` uses the reference and `runScan` flag. `newController` reprompt (≈L667) likewise. `finishWith` (≈L386): `Accepted` carries the kept centers when `scan.isGuidedRun`. Never pass `runScan` to `saveCalibration`, `CalibrationOffer`, case capture or Quick Scan.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `quickCheckOf` / `quickCheckRetakes` (L31–71) gain `runScan: Boolean = false`; add the private lighting guard (uses `StickerClassifier.deltaE` is internal to `:core:domain` → compute ΔE76 locally or via a public helper; do not widen domain API unless needed).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SelfCalibration.kt` -- read-only; `from(samples, saved = null)` gives the start-scan centers.
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/QuickCheckScenarioTest.kt` -- read-only template for the random scenario generator (seeded, perturbations by `scenario % 6`, `Coverage.touched`, `QuickCheck.faces`, false-VERIFIED counting).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt` -- existing Quick Check sample helpers (`GuidedScanPlan.reprompt(face)` gives `centerColor`/`upNeighborColor`; `deviceCheck` builds `Assess.check`).
- `app/src/test/kotlin/dev/cubentia/app/scan/QuickCheckContractTest.kt`, `app/src/test/kotlin/dev/cubentia/app/session/SessionHostTest.kt` -- Robolectric / host test patterns to extend.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/session/RunScanColors.kt` -- add the pure expiry/eligibility rule -- one testable owner rule.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- hold, set and clear run-scan colors inside `dispatch`; public read -- single in-memory owner on the confined loop.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanOutcome.kt`, `ScanFlowContract.kt`, `QuickCheckCapture.kt` -- carry colors in result/launch extras; subset routing with run-scan colors -- transport.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt`, `ui/solve/SolveFlowViewModel.kt`, `ui/shell/AppShell.kt` -- pass start-scan colors on the start events; pass held colors to Quick Check launch -- wiring.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt`, `GuidedScanFlow.kt` -- return start-scan centers; use run-scan reference with the lighting guard -- camera side.
- Tests: `app/src/test/.../session/RunScanColorsTest.kt` + `SessionHostTest` cases (set on applied start event; not set when dispatch ignored; cleared on each expiry trigger; kept across `PhysicalConfirmed`/`QuickCheckObserved`/`Undo`; host never writes them to snapshot/checkpoint repositories); `QuickCheckContractTest` (routing table incl. run-scan; extras round-trip; malformed → null; `ScanFlowContract` Accepted round-trip with colors); `app/src/testDebug/.../camera/RunScanQuickCheckScenarioTest.kt` (random suite + lighting-change test, below).

**Acceptance Criteria:**
- Given no saved calibration and a physical run started by an accepted Guided 6-face Scan, when a Quick Check of fewer than 6 faces is requested in that run, then the subset Quick Check intent is launched carrying the start-scan centers and its faces are classified against them.
- Given no start scan, or a saved calibration, when a Quick Check is requested, then the launched intent is exactly today's (full when uncalibrated; subset with no run-scan extra when calibrated).
- Given run-scan colors are held, when the run ends, a session starts/restores, Try again restores a checkpoint, or the Learner switches back to virtual, then `SessionHost.runScanColors()` is null and the next Quick Check is a 6-face scan.
- Given a seeded random suite (≥ 2,000 scenarios, generator as in `QuickCheckScenarioTest`) where the start scan's Lab samples (a fixed palette plus seeded noise ≤ 3 ΔE per sticker) produce the run-scan colors via `SelfCalibration.from`, and Quick Check samples of the actual cube's plan faces under the same light run through `GuidedScanFlow.quickCheckOf(samples, runScan colors, runScan = true)` then `Assess.check`, then false VERIFIED is 0, at least one matching plan verifies, and a noise-free synthesis round-trips each face exactly (precondition).
- Given the same scenarios with a lighting change applied to the Quick Check samples only (e.g. L −15 and b +20 on every sticker), then every captured face is doubtful (`quickCheckOf` returns no faces; `quickCheckRetakes` lists every captured face) and no check is VERIFIED.
- Given the change, when `./gradlew spotlessCheck build` runs, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 27 findings — high 0, medium 1, low 15, false 10, maybe-false 1
- findings:
  - `[low]` `[reject]` Blind: a wrong face held fails the light guard and drops the good face too — real, but the outcome stays safe (no evidence, never VERIFIED) and a 2-face plan needs both faces for coverage anyway; changing it needs a new guard branch against the spec's Always rule.
  - `[low]` `[reject]` Blind: changed light has no way out (subset checks stay doubtful; mid-run full scan never refreshes colors) — this is the intent's chosen outcome ("a lighting change must make faces read as doubtful"); the Learner can still take the existing 6-face scan; refreshing or expiring colors adds state the intent did not ask for.
  - `[low]` `[patch]` Blind: ignored events (`after === before`) still cleared held colors — fixed: `keepsRunScanColors` keeps on an ignored event; RunScanColorsTest case added and expiry test corrected to applied events only.
  - `[maybe-false]` `[patch]` Blind: light guard untested just under its 10 ΔE threshold — settled by a new scenario test (L −5, b +7 ≈ 8.6 ΔE + noise): 2,156 scenarios, 1,353 passed the guard, 0 false VERIFIED.
  - `[low]` `[patch]` Blind: no diagnostics for the run-scan reference (Learner hint part excluded by intent: no new copy) — fixed the debug `onAssembly` reference to `saved ?: runScan`.
  - `[low]` `[reject]` Blind: no activity-level test that a saved calibration ignores the extra / malformed extra on a subset intent — the contract never sends the extra when calibrated (tested), malformed decodes to null (tested), and the activity reads it only when `saved == null`; extra test adds little.
  - `[low]` `[reject]` Blind: `SessionRestored` expiry untested — same `startsOrRestores` branch as the tested restore events; constructing a snapshot fixture for it is more than a direct correction.
  - `[low]` `[reject]` Blind: duplicate ΔE76 helper — the domain one is `internal`; widening the domain API adds public surface for a 5-line formula.
  - `[false]` `[reject]` Blind: colors lost on process death — intended by the spec (memory only; "Process death loses them (6 faces again)").
  - `[false]` `[reject]` Blind: Quick Scan mode gets no run colors — intended: only a Guided 6-face Scan returns colors; Quick Scan never runs a 2-face Quick Check.
  - `[false]` `[reject]` Blind: spec not part of the diff — the spec exists at this path and is the claims file.
  - `[false]` `[reject]` Blind: `RunScanColorsExtra.encode` can throw on a missing color — every encoded value comes from `SelfCalibration.Calibrated` (built with `Color.entries.associateWith`) or a decoded 6-color extra; no partial `CalibrationCenters` reaches it.
  - `[low]` `[reject]` Blind: `EXTRA_RUN_SCAN_COLORS` alias on QuickCheckContract — cosmetic; no named harm.
  - `[low]` `[reject]` Edge: one drifting face voids the others — same root as the wrong-face row; safe outcome, see above.
  - `[low]` `[reject]` Edge: retakes under the same changed light fail again — intent-sanctioned doubtful outcome; at most 2 retakes (existing cap).
  - `[low]` `[reject]` Edge: held colors never refreshed when light changes mid-run — see changed-light row.
  - `[low]` `[reject]` Edge: a mid-run full Quick Check's measured centers are ignored — intent names only the start scan; a run without self-calibrating start scan keeping 6 faces is the specified behavior.
  - `[low]` `[patch]` Edge: `current.value` published before `heldRunScanColors` updated — fixed: colors assigned before the state is published.
  - `[low]` `[patch]` Edge: `onAssembly` logs a null reference for run-scan checks — same fix as the diagnostics row.
  - `[low]` `[reject]` Edge: a non-ACCEPTABLE probe counts as a light probe — the result is only more doubt (never false confidence); filtering would let a blurred face skip the probe.
  - `[medium]` `[patch]` VG: GuidedScanActivity's run-scan flag and retake wiring untested under changed light — added `uncalibratedRunQuickCheckUnderChangedLightRetakesEveryFaceAndNeverVerifies` route test through the real Activity (retakes asked, empty evidence, never VERIFIED).
  - `[low]` `[reject]` Intent: the new suite differs from the domain suite (no 98% bar, plan-only observations) — the intent asks for 0 false VERIFIED with run-scan colors, which the suite asserts; the domain suite is unchanged and still runs.
  - `[low]` `[reject]` Intent: sub-threshold light change untested — same root as the threshold row; now covered.
  - `[false]` `[reject]` Intent: Solve-tab playback as a second start trigger — that playback's physical run starts from an accepted 6-face scan baseline, within the intent's wording.
  - `[false]` `[reject]` Intent: Intent extras as a store — extras are in-memory transport, none of the four named stores.
  - `[false]` `[reject]` Intent: host field beside `reduce` — the intent let the spec settle the owner; it is set only inside the confined `dispatch` from (before, event, after), and colors must not be in SessionState.
  - `[false]` `[reject]` Intent: no evidence the build passed — `./gradlew spotlessCheck build` ran green (verification below).

## Design Notes

Owner choice (AD-3, AD-9, Ownership Matrix): the colors must not be in `SessionState` (issue), so `reduce()` cannot own them; but their lifetime is defined by session transitions. `SessionHost.dispatch` already sees `before`, the stamped event and `after` on the one confined loop and already keeps host bookkeeping beside the state (`sessionId`, `attemptId`), so it is the race-free owner: set and expire in the same step that applies the start/end event. No async result is involved (the colors arrive with the scan outcome the caller already dispatches), so no revision echo is needed; a stale start scan is already rejected by the existing `revision`/`AWAITING_CONFIRM` check before `EditorEntered`. They are not Color Calibration (never persisted, never written by `UpdateCalibration`), the same transient kind as the per-scan `SelfCalibration` the Activity already computes, extended to one run.

Lighting guard rationale: a quick check's own captured center already self-calibrates its color (`calibrationFor`), but the other five colors still come from the start scan; a light change shifts all of them, so a probe on the captured plain centers decides whether the start-scan reference still holds. Threshold = the close-call ΔE (10), the margin the classifier already requires.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest --tests '*RunScan*' --tests '*QuickCheckContract*' --tests '*SessionHostTest*'` -- expected: pass, scenario test prints 0 false verified

## Auto Run Result

Status: done

**Summary:** With no saved Color Calibration, an accepted Guided 6-face Scan that starts a physical run (lesson confirm scan via `EditorEntered`, or a Solve playback from an accepted scan) now returns its self-calibrated six centers. `SessionHost` holds them in memory only, sets and expires them inside `dispatch`, and Quick Checks in that run launch as 2-face subset checks classified against them. A lighting guard makes every captured face doubtful when a captured plain center drifted ≥ 10 ΔE from the start-scan reference. With a saved calibration or no start scan, behavior is unchanged. No Learner-visible copy, no new SessionEvent, port or store.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/session/RunScanColors.kt` (new) — pure set/expire rule.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` — in-memory holder, `dispatch(runScanColors, event)`, `runScanColors()`.
- `app/src/main/kotlin/dev/cubentia/app/scan/RunScanColorsExtra.kt` (new) — Intent extra encode/decode (lenient).
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanOutcome.kt`, `ScanFlowContract.kt`, `QuickCheckCapture.kt` — carry colors; subset routing with run-scan colors.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt`, `ui/solve/SolveFlowViewModel.kt`, `ui/shell/AppShell.kt` — wiring of start-scan colors and Quick Check launch.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt`, `GuidedScanFlow.kt` — return measured centers (`runScanColorsOf`), use run-scan reference, lighting guard.
- Tests: `RunScanColorsTest` (new), `RunScanQuickCheckScenarioTest` (new), `QuickCheckContractTest`, `QuickCheckRouteTest`, `SolveFromScanRouteTest`.

**Review findings:** 27 findings (high 0, medium 1, low 15, false 10, maybe-false 1). Patches applied: 5 entries (ignored-event keep rule; colors assigned before state publish; diagnostics reference; Activity-level changed-light route test; sub-threshold light scenario test). Deferred: none. Rejected: 10 false and 12 low/intent-sanctioned rows, each with its reason in the Review Triage Log.

**Follow-up review recommendation:** false — first pass patched 1 medium and no high.

**Verification:** `./gradlew spotlessCheck build` — BUILD SUCCESSFUL (after patches). Run-scan scenario suite: 2,120 scenarios, 0 false VERIFIED, matching plans verified 458/473; sub-threshold light (≈8.6 ΔE): 2,156 scenarios, 1,353 passed the guard, 0 false VERIFIED; large light change: every captured face doubtful, never VERIFIED. RunScanColorsTest 7/7, RunScanQuickCheckScenarioTest 5/5, QuickCheckRouteTest 7/7, QuickCheckContractTest 6/6, SolveFromScanRouteTest 8/8.

**Residual risks:** the 10 ΔE light guard is unvalidated on devices (device checks out of scope); too strict means more retakes and no-evidence results. Once the light changes mid-run, subset Quick Checks stay doubtful for the rest of the run (intent-sanctioned; the 6-face scan remains available). A wrong face held voids the whole run-scan capture rather than only that face (safe, never falsely confident).
