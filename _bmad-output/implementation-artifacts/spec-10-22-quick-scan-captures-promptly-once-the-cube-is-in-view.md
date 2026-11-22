---
title: 'Story 10.22: Quick Scan captures promptly once the cube is in view'
type: 'feature'
created: '2026-11-22'
status: 'in-progress'
baseline_commit: 'eee7bbcf58d9855f09b1357f507b6f521203c1a4'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-22-quick-scan-reliability.md'
  - '{project-root}/_bmad-output/specs/spec-quick-scan-reliability/failure-modes.md'
  - '{project-root}/AGENTS.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A Quick Scan corner takes 19 s to 2 min to capture on a still bench (floor about 3 s). Exposure moves during the hold, the steady rule compares colors and restarts on every exposure change, a cleared light hold re-meters, the light check blocks, and the log cannot say what a hold waited for.

**Approach:** Lock exposure once metering on the cube has really settled, judge steadiness by outline movement, make the light check advisory, harden the turn check against logo centres, clear stale state on back-out, and log a complete per-hold timeline. Every hold must auto-capture within 10 s of the cube being detected (PO 2026-11-22).

## Boundaries & Constraints

**Always:**
- Exposure, lock and settle live in the camera adapter (`app/src/camera`); hold logic stays pure Kotlin in `:core:domain`. Focus stays continuous (PO 2026-11-16).
- Every threshold (settle tolerance and window, outline steady fraction, re-meter bounds) is derived from recorded bursts and written with its replay to `docs/evidence/10-22/`.
- The light coaching line keeps its 10.19 text and about 1 s delay; the calibration offer keeps its 10.19 timing.
- Diagnostics are numbers only (AD-9), debug builds only; the release `ScanDiagnostics` stays a no-op mirror.
- Case capture (`CaseCaptureController`) and the Guided scan behave as before.

**Never:**
- No change to `QuickScanClassification`, the keep rule, the agreement gate, `SideLight` cuts or the calibration torch (Story 10.21).
- No recorder-burst format change (Story 10.20). No new dependency, permission or Learner text. No frame persistence outside the debug recorder.
- Never settle on the AE state alone. Never clear the previous hold on a single frame.

**Decisions (PO 2026-11-22):** one more hand-held burst and one fast-movement burst are recorded on the Lenovo before implementation and committed beside the three existing bursts; the outline threshold is fitted once on all five. The spec is kept whole (about 3,100 tokens) and not split.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Still cube, steady exposure | Detected, outline drift below the steady fraction | Meter, settle, lock; capture after one 1 s steady window | N/A |
| Exposure swings after metering | Exposure time × ISO series of still burst 1 | Settle signal reports "not settled" throughout the swing | Settle cap (1.5 s) still releases the wait, logged `timeout=true` |
| Light check fails | Verdict failing for 1 s or more | Coaching line shows; auto-capture and manual shutter work; capture line logs score and `light=fail` | N/A |
| Light check passes again | Was failing | Line hides; no re-meter, steady window not restarted | N/A |
| Cube lost for `LOST_MS` | Undetected 300 ms or more | Unlock, re-meter on next detection, settle and steady restart; undetected time counted | N/A |
| Outline leaves the metered region, or face brightness changes beyond the bound | Locked exposure | One re-meter | N/A |
| Hand-held | Outline moves below the steady fraction | Window completes | Fast movement restarts it |
| Hold 2, cube not turned, logo top centre flickering | Logo-centre readings of still burst 1 | No capture | N/A |
| Back-out from hand-over | `backOut()` then a new corner | Captures again | N/A |
| Camera cannot lock exposure | AE lock unavailable | Behaves as today, logs `lock=unsupported` | No crash |

</frozen-after-approval>

## Code Map

- `core/domain/.../recognition/CornerHold.kt` -- `onFrame` L96-205: `lightCleared` re-meters (L168), `lightHeld` blocks (L177) and drops a queued press (L157); `press()` returns `Ignored` when held (L215); `steadyMove` L343 (ΔE 5 on two cells) is the steady rule; `updateLight` L250. `faceSteady = true` only for Quick Scan; the case capture passes neither light nor settle and must not change.
- `core/domain/.../recognition/QuickScan.kt` -- `QuickScanController.onFrame` (pose lambda: `CenterCheck`, `turnedOver`, clears `previous` on one frame), `onShutter`, `take()` (sets `previous`, `holdCenters`, `lastCapture`), `backOut()` (does not clear `previous`), `turnedOver` (top centre, ΔE `FACE_CHANGE_DELTA_E`), `CenterCheck` (`POSE_MATCH_DELTA_E` 11), `calibrationOffered` (reads `lightHeldSince`), `CornerCaptureReport`.
- `core/domain/.../recognition/GuidedScan.kt` -- `FrameStats` (`cells: CellReading(value, plain)`, `meanLuma`); read-only.
- `app/src/camera/.../ScanFrameSource.kt` -- `meterCube` L263, `settled()` L269, `observeAeState` L403 (Camera2 interop capture callback: add `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`), `meterOn` L468, `meteringAction` (3 s auto-cancel, keep), `settleOutcome` L581 and `SettleTracker` L652 (AE state only), `meteringFlags`, `CameraXConfig.Builder` L308 (logging level). `ScanFrameSource` interface L61-104 for new defaults.
- `app/src/camera/.../CornerLocator.kt` -- `CornerFrame.frameQuads` (analysis-frame pixels, three quads): the outline to pass to the domain.
- `app/src/camera/.../GuidedScanActivity.kt` -- Quick Scan branch of `analyze` L578-609, `captureCorner` L639-678 (`includeAwb = quick.hold == 1`, `meterCube`), `logLightHold` L681, shutter path, `backOutOfHandOver` L1056. `GuidedScanFlow.kt`: `phaseOf(quick)`, `lightHoldChange`.
- `app/src/debug/.../ScanDiagnostics.kt` and `app/src/release/.../ScanDiagnostics.kt` -- `onSteadyRestart` and `onCenterBlock` are throttled by `INTERVAL_MS`; `cornerCaptureLine`; keep both files' signatures in step.
- Tests: `core/domain/src/test/.../QuickScanControllerTest.kt` (helpers `face`, `hold`, explicit `nowMs`; many tests assert light-hold blocking and must be rewritten to advisory), `app/src/testDebug/.../camera/ScanFrameSourcePolicyTest.kt` (`settleOutcome`, `SettleTracker`), `ScanDiagnosticsRunLineTest.kt`, `GuidedScanFlowTest.kt`.
- `docs/evidence/quick-scan-2026-11-22/bursts/*.jsonl` -- five bursts (two still, `handheld`, `handheld-2`, `fast`; the last two recorded 2026-11-22 15:55 for this story). One JSON line per frame: `tMs`, `quads` (3×4 points), `faces[].cells` (`[L,a,b,plain]`), `faces[].meanLuma`, `exposure{ae,exposureNs,iso}`. Measured for planning: still burst 1 alternates 20 ms × ISO 125–147 and 30 ms × ISO 100–116 between 1.5 s and 3.3 s; outline displacement over bounding-box diagonal against a reference frame restarts at 0.06 on still burst 1 and never at 0.07; `handheld` never restarts at 0.08; `handheld-2` (frame-to-frame median 0.026, max 0.080) restarts twice at 0.08–0.15 and completes 7 windows; `fast` has the cube detected in only 56 of 120 frames, so its replay must handle undetected frames (a lost cube also restarts the window).

## Tasks & Acceptance

**Execution:**
- [ ] `core/domain/.../recognition/CornerHold.kt` -- outline-based steady rule (largest corner displacement from the reference frame over the outline's bounding-box diagonal; colors only to detect a different face); light check advisory (coaching only, never blocks, never drops a press, clearing does not re-meter); re-meter triggers (lost, torch, outline outside the metered region, face brightness beyond a bound since metering); per-hold event list with elapsed ms -- AC2–AC4.
- [ ] `core/domain/.../recognition/QuickScan.kt` -- pass the outline through `onFrame`; turn and pose checks skip non-plain centres and need the turn confirmed over several frames; `backOut` clears `previous` and the resumed hold's centres; capture report gains trigger, hold total ms, undetected ms, light score and pass/fail -- AC1, AC5, AC6.
- [ ] `app/src/camera/.../ScanFrameSource.kt` -- settle on exposure time × ISO within a tolerance over a window after the metering future; AE lock from settle until capture or re-meter, AWB lock carried from hold 1 to hold 2; unlock on re-meter and on leaving the corner view; `lock=unsupported` path; CameraX logging limited to errors -- AC2.
- [ ] `app/src/camera/.../GuidedScanActivity.kt`, `GuidedScanFlow.kt` -- wire outline, lock and events; log viewfinder entry and first frame; manual shutter marks `trigger=manual` -- AC1, AC4.
- [ ] `app/src/debug/.../ScanDiagnostics.kt`, `app/src/release/.../ScanDiagnostics.kt` -- unthrottled state-change lines with `hold=` and `t=` ms; extended capture line -- AC1.
- [ ] Tests -- rewrite light-hold tests to advisory; unit-test every matrix row; replay tests over the committed bursts (settle on still burst 1's exposure series; steady rule on all bursts; logo-centre hold 2; back-out then capture) -- all ACs.
- [ ] `docs/evidence/10-22/README.md` -- threshold derivations with replay tables, the 2026-11-22 baseline, and an empty device-session table for the human gate.
- [ ] `_bmad-output/implementation-artifacts/sprint-status.yaml` -- story status per the workflow.

**Acceptance Criteria:**
- AC1: Given a corner hold in a debug build, when it runs, then `CubentiaScanDiag` logs each state change as it happens with ms since the hold began (viewfinder entry, first frame, cube lost and found, pose rejected, metering requested and settled, light fail start and clear, steady restart, capture with `trigger=auto|manual`), each capture line carries the hold's total time, its undetected time, the light score and `light=pass|fail`, and steady-restart lines are not rate-limited.
- AC2: Given metering on the cube has settled, when the hold continues, then exposure stays locked until capture or a re-meter trigger, and a test replaying still burst 1's exposure series reports "not settled" throughout its swing.
- AC3: Given a settled hold, when the steady window runs, then replay shows no restart on either still burst, completed windows on every hand-held burst and a restart on a fast-movement burst.
- AC4: Given a failing light check, when frames are analysed, then coaching shows and hides as in 10.19 while auto-capture and the manual shutter work as when it passes.
- AC5: Given hold 2 with the cube unturned and still burst 1's logo-centre readings, when frames are analysed, then no capture happens.
- AC6: Given a back-out from the hand-over, when the corner view resumes, then the next corner captures.
- AC7 (human gate, not met by this implementation alone): 10 still and 10 hand-held holds per device on the Lenovo TB128XU and Galaxy S24 FE, every hold auto-capturing within 10 s, recorded in `docs/evidence/10-22/`.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

- The domain cannot see pixels or camera types: the outline crosses as plain coordinates (the twelve quad points), and the adapter owns lock state. The domain only says "re-meter"; the adapter unlocks, meters, and locks again once settled.
- The settle cap stays 1.5 s so a camera that never stabilises cannot block capture; at the cap the adapter locks whatever exposure it has and logs the timeout.
- Still burst 1's exposure × ISO differs by about 14% between its two levels and by about 5% inside one level; the tolerance must sit between them, and the window must be long enough that the nine constant frames before the swing do not count as settled on their own unless the lock then holds the level.
- Light check after this story: `lightHeld` no longer means "blocked". Keep one name for "light failing, coached" and remove the blocking paths, so `calibrationOffered` keeps working from the same timer.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:test --tests '*QuickScan*' --tests '*CornerHold*' --tests '*SideLight*'` -- expected: pass
- `./gradlew :app:testDebugUnitTest --tests '*QuickScan*' --tests '*GuidedScan*' --tests '*ScanFrameSource*' --tests '*ScanDiagnostics*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: success

**Manual checks (if no CLI):**
- Device session (AC7) on both reference devices; a recorder burst per device showing one exposure level per hold.
