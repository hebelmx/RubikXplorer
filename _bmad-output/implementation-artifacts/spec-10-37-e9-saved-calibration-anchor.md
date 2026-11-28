---
title: 'Story 10.37 E9 (split): saved-calibration fallback for early Quick Scan anchoring'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_commit: 'ffc9aaab16c4e5e7e1a3823d2a1dd34cb46f03ae'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Continuous Quick Scan anchors cube orientation from 3–5 centres only against the built-in `REF_CENTER_LAB`, so pastel or off-palette cubes wait until all six centres are seen, even when the Learner has saved a Color Calibration.

**Approach:** When the built-in 3–5 centre anchor fails, try the saved calibration (compared in CIELAB `standardLab`); six-centre self-calibration stays the authority.

**PO decision (2026-11-27):** E9 is split out of Story 10.37 and runs now, without waiting for #431–#437 or #413. Story 10.37 stays not done; `epics.md` is not edited.

**Decisions (PO, 2026-11-27):**
- The torch rule applies to E9 and is recorded here; putting it back into AD-9 is routed as an action item (SCP 2026-11-24 removed it; Story 10.21 states it).
- Centres match when, for the best of the 24 orientations, the average ΔE is < 20 with margin >= 8, and every observed centre reads CONFIDENT as its colour and lies within `MAX_CENTRE_DELTA_E`.
- Once centre evidence exists, any change of the applied torch turns the calibration off until Reset and revokes an anchor it made. A toggle before any evidence does not count.
- With six centres seen, a calibration-made anchor is dropped unless six-centre self-calibration is decisive; the calibration cannot anchor that fragment again.
- (Renegotiated during implementation) The #401 twist guard ignores a calibration-made anchor and behaves as it does before anchoring.
- (Renegotiated during implementation) Any ambiguous six-centre solve bars the calibration from that fragment, whether or not its anchor is live at that moment.

## Boundaries & Constraints

**Always:**
- Otherwise self-calibrate exactly as today. With no saved calibration, behaviour is identical (S1–S7 at 54/54 with 0 wrong; `MapperParityReplayTest` green).
- A calibration anchor is never latched: it holds only while the current frame's evidence still passes every rule. Built-in and six-centre anchors latch as today.
- `:core:domain` stays pure JVM; only neutral types cross from the camera source set. Only the persisted saved calibration is used, never the run's start-scan colours.

**Never:** E4, E5, E6, E11; changes to the model, the built-in anchor thresholds, `MAX_CENTRE_DELTA_E`, release flags, the session format or Learner copy; `epics.md` or architecture edits; device benchmarks (Story 15.4 holds the devices); marking Story 10.37 done; `sprint_plan generate`; merging the PR.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Match | Off-palette cube, 3–5 centres fail built-in; saved calibration matches, torch tag equal | Anchors correctly before the sixth centre; 54/54, 0 wrong | N/A |
| Built-in passes | Built-in anchor succeeds | Calibration not consulted | N/A |
| No / invalid calibration | null, or `!StickerClassifier.isValid` | Today's behaviour | Silently ignored |
| Wrong calibration | Another cube or light | No calibration anchor | Self-calibrate |
| Torch tag differs | Tagged on, torch off, and reverse; untagged with torch on | No calibration anchor | Self-calibrate |
| Later mismatch | Anchored, then a new centre fails the match | Anchor revoked | Self-calibrate |
| Toggle before evidence | Toggle, then cube first seen | Eligible under the new state | N/A |
| Toggle after evidence | Toggle, and toggle back | Anchor revoked; off until Reset, then eligible again | Self-calibrate |
| Six, ambiguous | Calibration-anchored, margin < 0.35 | Unanchored until decisive, even if a centre later drops out | N/A |
| Six, decisive | Margin >= 0.35 | Self-calibration anchor; a later toggle keeps it | N/A |
| Centre unreadable | No `standardLab` for an observed centre | No calibration anchor | Self-calibrate |

</frozen-after-approval>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- `solveGlobalColorsAndNet` L1150-1240 (3–5 branch L1213; 6-centre override only at margin >= 0.35, L1210); `processFrame` L358; `reset` L334; `faceRobustLab(includeStandardLab = true)` L205-260; anchor also cleared at promotion L1135; `CellObs` L98-103 has no torch tag; deviation ids catalogued in KDoc L61-72.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt` -- `match` L117 and `MAX_CENTRE_DELTA_E` L101; needs known centre colours, so restate per rotation.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/StickerClassifier.kt` -- `isValid`, `read`, `deltaE` (same module).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `appliedTorch` L148/L248; continuous `processFrame` call L397 passes no torch.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- engine created L149 before `scan.saved` loads L167; calibration can change mid-screen L1155-1188; torch toggle/reset/background L261, L950, L466.
- `ContinuousScanEngine.kt` L74 and `app/src/camera/continuous/.../ContinuousScanPipeline.kt` L82 -- thread calibration and torch per frame on the analyzer thread.
- `core/domain/src/test/.../continuous/ContinuousStressScenariosTest.kt`, `.../evaluation/continuous/MapperParityReplayTest.kt` -- regression gates; leave fixtures' calibration-less replay unchanged.

## Tasks & Acceptance

**Execution:**
- [x] `core/domain/.../continuous/ContinuousFaceletMapper.kt` -- add defaulted `savedCalibration` and `torchLit` inputs to `processFrame`; track the scan's torch state and anchor provenance; clear a calibration anchor before re-solving and on a torch change; add the gated fallback after the built-in 3–5 branch; clear the new state in `reset` and promotion; add KDoc id **I1037**.
- [x] `core/domain/.../continuous/` (new file if the mapper grows much) -- pure per-orientation match using `StickerClassifier` and `QuickScanClassification.MAX_CENTRE_DELTA_E` -- testable alone.
- [x] `app/src/camera/.../ScanFrameSource.kt`, `ContinuousScanEngine.kt`, `GuidedScanActivity.kt`, `app/src/camera/continuous/.../ContinuousScanPipeline.kt` -- deliver the applied torch state per frame and the current saved calibration (late load, mid-screen save, engine swap) to the analyzer thread.
- [x] `core/domain/src/test/.../continuous/ContinuousSavedCalibrationTest.kt` -- every matrix row, plus pastel and off-palette cubes, and a layer twist still detected after a calibration anchor.
- [x] `app/src/test*/...` -- prove torch state and saved calibration reach the mapper.
- [x] `Fixtures/continuous/mapper-parity/expected-differences.json` -- catalogue I1037 (no differences expected).
- [x] `_bmad-output/implementation-artifacts/sprint-status.yaml` -- hand-add one open action item: restore the torch rule in AD-9. No other edit.
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- recorder stores no torch or calibration; exported `CalibrationCenters` has no torch tag.

**Acceptance Criteria:**
- Given a matching saved calibration and an off-palette cube, when 3–5 centres are seen, then the scan anchors earlier than without it and never mis-anchors.
- Given the existing suites with no calibration, when they run, then results are unchanged.
- Given an off-palette cube anchored by calibration, when a layer is turned, then every twist the calibration-less run detects is also detected, with no more wrong stickers than that run.

## Implementation Notes

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Evidence | Route |
|---|---|---|---|
| EC1 promotion suppressed by a calibration anchor | medium | confirmed: both `tryMergePending` conditions read `globalRotIdx == null`; reachable since `confirmedFaces` needs count >= 2 while the anchor needs only quality | patch P1 |
| EC2 weak centres reach the match; six observed centres | low | real veto is fail-safe and follows the frozen "every observed centre" rule; "six seen" is `centerLabs`, and six observed must all pass the strict gate | reject |
| EC3 stale anchor on empty or twist frames after calibration change | false | a mid-screen calibration change always calls `engine.reset()` (`saveCalibration`); empty frames add no centre evidence; torch changes are handled first; a twist is latched (E1) | reject |
| EC4 anchor flicker: repeated ticks, Close during a dip discards unconfirmed | low | real, but never worse than the calibration-less scan, which shows 0 until six centres; hysteresis would break the approved never-latched rule | reject |
| EC5 appliedTorch stale during async toggle disables calibration | low | only a camera rebind with the torch on does it (see BH1); fail-safe | reject |
| EC6 appliedTorch null or stale after reopen | low | brief window; the strict centre gate still applies and a later change turns the calibration off | reject |
| EC7 toggle-and-revert unseen while analysis is held | false | no UI path changes the torch while analysis is held (review forces it off; dialogs cover the toggle) | reject |
| EC8 twist parity not guaranteed after a regrip | medium | same root as EC1 | patch P1 |
| BH1 rotation with torch on disables calibration until Reset | low | confirmed (new source passes unlit until re-applied); matches the literal "any change of the applied torch" rule, fails safe, torch is off by default; fix needs a tri-state input | reject |
| BH2 torch value is not capture-time; KDoc overstates | low | confirmed wording; behaviour self-heals because any change turns the calibration off | patch P2 (wording) |
| BH3 calibration anchor steers promotion | medium | same root as EC1 | patch P1 |
| BH4 anchor not re-validated on empty or changed-calibration frames | false | see EC3 | reject |
| BH5 shown anchored differs from exported anchored | low | one-frame window; the unanchored result equals today\u2019s | reject |
| BH6 weak centre or six observed | low | see EC2 | reject |
| BH7 observed face without centre blocks fallback | false | specified: matrix row "Centre unreadable" for an observed centre | reject |
| BH8 logo centres not guarded | maybe-false | needs a device standardLab of a logo centre against `MAX_CENTRE_DELTA_E`; medium if true (fails safe) | defer D1 |
| BH9 orientation accepts 1-2 or 6 centres | false | the only caller requires 3-5 `centerLabs`; internal object | reject |
| BH10 thresholds barely tested | medium | see VG6 | patch P5 |
| BH11 no test uses a real-way calibration | maybe-false | needs a calibration saved on a device plus a recording of the same cube; medium if the gate then never passes | defer D2 |
| BH12 vacuous assertion | low | confirmed: the expression is implied by the code | patch P5 |
| BH13 activity and adapter wiring untested | medium | see VG1, VG2 | patch P3 |
| BH14 no diagnostics | low | developer-only; adds code and surface the spec did not ask for | reject |
| BH15 debug getters disagree after a twist | low | confirmed; one-line move | patch P2 |
| BH16 wrong calibrationBarred comment | low | confirmed | patch P2 |
| BH17 parity docs omit why I1037 has no verdict | low | confirmed in README and KDoc | patch P6 |
| BH18 deferred entry names a save path that does not exist | low | confirmed: the export calibration is never persisted | patch P6 |
| VG1 activity supplier untested | medium | pre-verified | patch P3 |
| VG2 PipelineEngine forwarding untested | medium | pre-verified | patch P3 |
| VG3 untimed pipeline branch untested | medium | pre-verified | patch P4 |
| VG4 torch change with evidence but no anchor, or pending only | medium | pre-verified | patch P5 |
| VG5 bar cleared on reset and promotion untested | medium | pre-verified | patch P5 |
| VG6 margin, mean and CONFIDENT rules removable | medium | pre-verified | patch P5 |
| VG7 promotion reads a calibration anchor as anchored | medium | verified, same root as EC1 | patch P1 |

Pass 1 diff excluded the spec and the regenerated `epic-10-context.md`.

Patches P1-P6 applied. No test fails without P1: 320 synthetic regrip setups showed no divergence, so only a parity guard covers it. The pending-only evidence case is unreachable (pending needs a master with evidence), and barred-then-promotion cannot be produced synthetically. `./gradlew --no-daemon spotlessCheck build` passed after the patches.

## Design Notes

- Calibration and torch arrive per frame: the engine exists before `scan.saved` loads and the calibration can change mid-screen.
- Dropping the calibration anchor before each solve makes revocation on mismatch follow naturally. `globalAnchored` can therefore turn false again, hiding *Sides mapped* and Review and Correct.
- `standardLab` is AWB-scaled integer BT.601; saved centres are unscaled float BT.601: same space only approximately.
- A new parity deviation needs an id that does not collide with E1–E8.
- A recording anchored by calibration may not replay identically (deferred).

## Verification

**Commands:** (another session is building in a second worktree: always pass `--no-daemon`, never run `./gradlew --stop`)
- `./gradlew --no-daemon :core:domain:test` -- expected: new tests, S1–S7 and parity green.
- `./gradlew --no-daemon spotlessCheck build` -- expected: success, including S1–S7 and `MapperParityReplayTest`.
