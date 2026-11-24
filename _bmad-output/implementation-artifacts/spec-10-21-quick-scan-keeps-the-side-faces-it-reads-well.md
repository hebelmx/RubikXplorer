---
title: 'Story 10.21: Quick Scan keeps the side faces it reads well'
type: 'feature'
created: '2026-11-23'
status: 'done'
baseline_revision: 'fd2eff4e4025c6b0161f6bdfeaaed24448f672f5'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The pastel tuning group lenovo-tb128xu_stlb_bar-warm still keeps a confidently wrong sticker (D2, Orange read as Red) on a kept face, as under the 10.19 gate.
    evidence: |-
      The plain-group fit leaves it unchanged; fitting on it would force a Red margin of 44.1 and keep 0 of 192 tuning faces. Routed to Story 10.27 (pastel judged against the cube's own calibrated colours) and the PO's BETA call; see docs/evidence/10-21/tuning-and-replay.md.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt AGREEMENT_MARGIN
    severity: medium
  - summary: >-
      QuickScanController.onFrame now runs matchAll plus three decide() calls (each re-running SelfCalibration over merged) on every settled detected frame on the analyzer thread.
    evidence: |-
      Not measured. Settle with a frame-time trace on the S24 FE and the Lenovo during the device session (the run sheet's live wait check), or by caching the self-calibration per frame.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt onFrame
    severity: medium (unverified)
  - summary: >-
      Lighting the torch in the calibration phase (ScanFrameSource.applyTorch with calibrating) is only checked against a fake camera.
    evidence: |-
      CameraXFrameSourceTest has no torch assertions and the route tests' fake camera never runs applyTorch. Settle with the device-session run sheet's physical torch check on the S24 FE and the Lenovo.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt applyTorch / calibrationMode
    severity: medium
  - summary: >-
      The Orange agreement margin 21.3 is driven by one tuning sticker (sm-s721u1_stla_bar-warm-2 h1 F2, labelled Red, read Orange by both reads at about 21 ΔE) that may be a mislabel.
    evidence: |-
      It costs 145 correct tuning stickers and 25 kept faces. Checking or excluding that label is Story 10.27's label quarantine; refit once label-exclusions.json lands.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt AGREEMENT_MARGIN
    severity: medium
  - summary: >-
      The calibrated light hysteresis band (1.33 to 1.35) was never tested against frame-to-frame jitter.
    evidence: |-
      Unverified. Settle with flip counts of old single cut versus new band on recorded bursts, or with the run sheet's light-clear steadiness check on the S24 FE.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SideLight.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Quick Scan keeps a side face only when all 9 stickers are CONFIDENT, its agreement gate fails on shaded faces, the saved calibration ignores the torch, the light cuts (27 L*, 1.34) have no margin and flip on ordinary S24 FE light, and auto-capture can fire on holds where almost nothing reads CONFIDENT. Two corner photos rarely finish a scan (10.20 baseline: S24 FE kept 0–1 of 8 side faces with a matching calibration).

**Approach:** With a calibration that matches (same torch state, every captured centre CONFIDENT as its own colour), keep a face with at most 1 uncertain sticker, blank and pre-selected in review. Tag calibrations with their torch state (Room migration, calibration-screen torch). Tune a per-colour agreement gate, a confident-count capture wait N and hysteresis light cuts on the 10.20 tuning split only; enforce the replay bar on the held-out split in CI; write a device-session run sheet for the device/PO ACs.

**Refs (binding, verbatim):** FR-22, FR-23, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10, NFR-11 · AD-9, AD-18 · OQ-8 · UX-DR15 · Stories 10.4, 10.17, 10.19, 10.20 · #274. Binding ADs: **AD-9**, **AD-18**.

## Boundaries & Constraints

**Always:**
- AD-9: pixels and torch control only in `app/src/camera/`; results cross as colour + `StickerConfidence`; all deciding logic (keep rule, match check, gate, wait, light hysteresis) is pure Kotlin in `:core:domain`. AD-9 clause: a calibration is used only when its torch state matches the current (actually lit) torch and the captured centres read as their own colours; otherwise self-calibrate.
- AD-18: `QUICK_SCAN` stays OFF in release; no flag level changes in this story.
- Never guess: an uncertain sticker stays blank; a kept face never carries a confidently wrong sticker, under any calibration (none, matching, other cube, other light).
- Tune only on `CornerSplit.TUNING` groups; held-out groups are never read by fitting code. Every tuning/held-out result records the label-set commit (`git rev-parse HEAD` of `Fixtures/corner`) and the SHA-256 of `Fixtures/corner/label-exclusions.json`, or `absent` while 10.27 has not landed.
- Learner text only in `en.json`; no new copy keys (toggle reuses `btn.quick-scan.torch-on/off`, offer reuses `btn.calibration.offer`).
- Debug diagnostics through `ScanDiagnostics` (debug) with the release no-op kept in signature step.

**Never:** change the Guided 6-face Scan's use of calibration; change `ColorPlausibility.plausible`, `StickerClassifier.CLOSE_CALL_DELTA_E`, `ScanAssembler` rotation; make the light check block capture again (backlit blocking is a PO decision at the gate); add Story 10.24/10.25/10.26/10.27 behaviour; raise any feature flag; edit planning artifacts.

## Settled UI behaviour (PO clarifications 2026-11-23 and story text)

- **Calibration torch toggle:** on a flash device the calibration phase shows the same `TorchButton` (top-end, "Torch on"/"Torch off") as the corner view; the torch is physically lit in the calibration phase when on. No flash → no control, torch state off.
- **Default:** opened from Settings or from the review → on. Opened from the corner view's *Calibrate colors* offer → starts with the corner view's current torch choice; it is one shared choice, so a change made on the calibration screen carries back to the corner view (consistent with EXPERIENCE.md "the choice carries"; it also keeps the saved calibration matching on return).
- **Locked:** the toggle is disabled (standard disabled button, still visible) while any centre swatch is kept, i.e. from the first *Keep* until the calibration is saved or left (PO clarification 2).
- **Saved tag:** the calibration records the actually-lit torch state when it was saved (no flash → false).
- **Confident-count wait:** while waiting, no coaching line (coaching = null), so the hold's prompt shows as usual; manual shutter still captures.
- **Offer on mismatch:** a torch or centre mismatch makes Quick Scan behave "as when no calibration exists", including the unchanged 10.19 offer trigger (light coaching held 5 s, torch not lit).
- **Light coaching line:** shows when a hold fails above the hold cut (after the existing 1 s); hides only after ~500 ms of frames below the lower clear cut, the window restarting when 2 or more cells of one face move by ≥ `GuidedScanController.STEADY_DELTA_E` (rule of commit 46005a11; PO clarification 1).
- **Return from calibration** opened from the corner view (save, Close or Back) clears the light coaching hold and restarts the offer timer.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Match, 1 uncertain | torch equal, all captured centres CONFIDENT own colour; face 8 CONFIDENT + 1 uncertain | face kept; that sticker blank and pre-selected in Quick Scan review | — |
| Match, 2 uncertain | as above, 2 uncertain | face to Guided grid | — |
| Hard disagreement | both reads CONFIDENT, different colours | face to Guided grid | — |
| Torch mismatch | saved torch=false, capture torch lit | all-9 rule; self-calibrate; log `calibration=mismatch torch` | — |
| Centre mismatch | one captured centre not CONFIDENT own colour vs saved | all-9 rule; log `calibration=mismatch centre` | — |
| Legacy row | row saved before migration (torch null) | treated as torch-off | — |
| No flash | hasFlash=false | no torch UI; torch state off; torch-off calibrations match | — |
| Torch requested, not lit | enableTorch failed/pending | light check runs; calibration match uses "off" | — |
| Few confident | < N of 27 hold stickers CONFIDENT | no auto-capture; `poseRejected reason=few-confident amount=<count>`; prompt shown; shutter captures | — |
| Light near cut | score between clear and hold cuts | coaching state unchanged (no flip) | — |

</intent-contract>

## Code Map

Domain (`D` = `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/`):
- `D/FaceSample.kt:57` `CalibrationCenters(centers)` -- add `torchOn: Boolean? = null` (fixture JSON stays decodable; null = torch-off).
- `D/QuickScanClassification.kt` -- `needGrid` L72-87 (all-9 rule), `judged` L61-66, `secondRead` L48-58, `gate` L163-174 (CONFIDENT only if both reads CONFIDENT and agree), `corrected` L210-224, `normalized` L227-241, `report` L95-137 (`closeCalls` vs `disagreements`). Home of the match check, the ≤1-blank rule and the per-colour gate.
- `D/QuickScan.kt` `QuickScanController` -- ctor L76-79 (`saved`), `calibrationOffered` L162-167, `onFrame` L200-282 (`pose()`/`allowAuto` L248-270, reasons L580-581, light skip L244 `light.takeUnless { torchOn }`), `syncTorch` L338-353 (sets `torchOn = requested`; `lastApplied` L329 unused for skip), `onShutter` L360, `take` L381-437 (`CornerCaptureReport`, `sinceDetectedMs`), `switchToFaceByFace` L444-453, `handOver` L488-510, wrappers L512-524, `CALIBRATION_OFFER_MS` L571.
- `D/CornerHold.kt` -- `onFrame` L122, `updateLight` L326-379 (`passedMs`, `LIGHT_CLEAR_MS` 500 L437, no movement check since f7b0573f), `clearLightHold` L385, `steadyMove` L512-522, `CornerHoldEventKind` L594 (`POSE_REJECTED` L601).
- `D/SideLight.kt` -- `MAX_SIDE_DARKER_L` 27.0 L30, `MAX_TOP_OVER_SIDES_RATIO` 1.34 L38, `Mode` L43, `calibrated` L124, `verdict` L166-175. Add hold + clear cut per mode.
- `D/ColorCalibration.kt:39` `CalibrationSession` (`kept`, `keep()` L63) -- "any swatch kept" drives the toggle lock.
- `D/ScanReview.kt` `fromSamples` L172-182, `from`, `preselected` L112-125 -- review re-runs the ungated rule; must also blank gated-uncertain stickers of kept faces.
- `D/LiveFixture.kt` -- `CornerSplit` L228, group `torch` L221, hold torch L203, `GROUP_INDEX` L309.
- `core/application/.../usecase/UpdateCalibration.kt`, `.../port/CalibrationRepository.kt` -- carry the torch tag through save/load.

App:
- `app/src/main/kotlin/dev/cubentia/app/data/ColorCalibrationEntity.kt` -- add nullable `torchOn` column; `RubikDatabase.kt` version 10 → 11 with `AutoMigration(10, 11)`; export `app/schemas/dev.cubentia.app.data.RubikDatabase/11.json`; `RoomCalibrationRepository.kt`, `CalibrationDao.kt`.
- `app/src/test/kotlin/dev/cubentia/app/data/SessionDatabaseMigrationTest.kt` -- pattern: `versionNineToTenKeepsPreferencesAndDefaultsMovePicturesOn` L324.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `torchOn` L880, `hasFlash` L883, toggle L221, offer `onCalibrate` L223-227, `torchLit`/`LaunchedEffect` L288-289, review calibration L323-335, Close L168-179, Back L247-252, `syncTorch` per frame L618-621, `saveCalibration` L983-1008 (new controller L1004), `capturePhase` L913-931 (Settings `CALIBRATION_MODE` L849).
- `app/src/camera/.../GuidedScanFlow.kt` -- `torchLit`, `torchControl` L245 (corner only today), `syncTorch`, `phaseOf` L226, `reviewOf` L281-290, `CalibrationHold` L400.
- `app/src/camera/.../GuidedScanScreen.kt` -- `Calibration` phase L112, `TorchControl` L541, `TorchButton` L549 (testTag `quickScan:torch`), `CalibrationViewfinder` ~L1007.
- `app/src/camera/.../ScanFrameSource.kt` -- `applyTorch` L265-293 (`appliedTorch` set only on success), `torchApplied()`, `cornerMode` L491 (torch only in corner mode today).
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `holdEventLine` L122, `cornerFrameLine` L52-110, `TAG` L452; mirror `app/src/release/.../ScanDiagnostics.kt`.

Tests and data:
- `core/domain/src/test/.../QuickScanSideFaceReplayTest.kt` -- scenarios NONE/MATCHING/OTHER_LIGHT/OTHER_CUBE, `tally` ~L150-220, `enforced` L421 (drops all 10.22 groups today), `baseline`, bar constants L619-620; report to `core/domain/build/reports/quick-scan/`.
- `QuickScanControllerTest.kt` (light/torch tests ~L564-1272), `SideLightTest.kt`, `QuickScanClassificationTest.kt`, `QuickScanBurstReplayTest.kt` + `RecordedBursts.kt` (Lenovo bursts `docs/evidence/quick-scan-2026-11-22/bursts/*.jsonl`), `app/src/testDebug/.../camera/GuidedScanFlowTest.kt` (torch ~L856-934), `ScanDiagnosticsRunLineTest.kt`, `CalibrationViewModelTest.kt`, `ColorCalibrationScreenTest.kt`.
- `Fixtures/corner/groups.json` -- 7 tuning, 8 held-out 10.22-pipeline groups; held-out backlit: `*_stla_ceilback` on both devices. 8 legacy groups (no manifest) stay report-only. Recompute light scores (groups 7–9 have wrong `lightMode`, #328).

## Tasks & Acceptance

**Execution:**
1. `D/FaceSample.kt`, `core/application/.../CalibrationRepository.kt`, `UpdateCalibration.kt` -- torch tag on `CalibrationCenters` through save/load -- AC2.
2. `ColorCalibrationEntity.kt`, `RubikDatabase.kt` (v11 + AutoMigration), `RoomCalibrationRepository.kt`, `app/schemas/.../11.json`, `SessionDatabaseMigrationTest.kt` -- nullable torch column; 10→11 test: old row reads torch-off, new row round-trips -- AC2.
3. `D/QuickScanClassification.kt` -- `CalibrationMatch` (`Match` | `Mismatch(TORCH|CENTRE)`) from saved, capture torch and captured centres; keep rule: matching → keep when non-CONFIDENT judged stickers ≤ 1 and no hard disagreement (both reads CONFIDENT, different colours); otherwise all-9; expose the blank positions; per-colour agreement-gate tolerance fitted (step 9) -- AC1, AC3.
4. `D/QuickScan.kt` -- record lit torch per capture (`syncTorch` → `torchLit = requested && applied`); light skip, clear-on-torch and offer use lit state; match per face at its capture torch; mismatch → `saved` ignored for that face's reads and `uncalibrated` for the offer; report match/mismatch on `CornerCaptureReport`; few-confident gate in `allowAuto` (`POSE_FEW_CONFIDENT = "few-confident"`, count of CONFIDENT among the hold's 27 stickers via `QuickScanClassification.read` with captured + hold faces, counted in `sinceDetectedMs`); method to clear the light hold and restart the offer timer on return from calibration; `QuickScanEffect.Done` carries kept-face blanks -- AC1, AC2, AC4, AC5.
5. `D/CornerHold.kt`, `D/SideLight.kt` -- per-mode hold and clear cuts; hold starts above hold cut, clears after 500 ms below clear cut with the 2-cells-moved restart -- AC5.
6. `D/ScanReview.kt` -- Quick Scan review blanks and pre-selects the kept faces' gated-uncertain stickers -- AC1.
7. `app/src/camera/...` (`GuidedScanActivity`, `GuidedScanFlow`, `GuidedScanScreen`, `ScanFrameSource`) -- torch in calibration phase per "Settled UI behaviour"; pass lit state; save tag; clear light hold on return; Quick Scan review uses carried blanks -- AC2, AC5.
8. `app/src/debug|release/.../ScanDiagnostics.kt` -- `calibration=match|mismatch torch|mismatch centre` on corner capture; `poseRejected reason=few-confident` with integer count; light lines that tag `far-above-cut`, `flip` (state change within the hysteresis band) and per-face level change between frames with per-sticker nearest-colour matches -- AC1, AC4, AC5.
9. `QuickScanSideFaceReplayTest.kt` (+ a tuning helper test in the same package, tuning split only) -- fit and record: per-colour gate (Yellow/Orange/Red vs Blue), N (start 6; largest N ≤ 6 at which every positive tuning hold's captured frame reaches N under the scan-time calibration; the Lenovo bursts replay the wait over time and report delays), light hold/clear cuts per mode from tuning holds (hold cut above the tuning front-lit maximum, clear cut between); before/after numbers -- AC3, AC4, AC5.
10. `QuickScanSideFaceReplayTest.kt` -- per the AC6-split PO decision (Design Notes): enforce the safety half on every held-out group and scenario; report the coverage half per condition. Original wording, for the report columns: on held-out 10.22 groups per device × light × torch (except the PO-unscored off-face groups `sm-s721u1_stla_bar-warm`, `lenovo-tb128xu_stla_bar-cool`, `lenovo-tb128xu_stla_bar-warm` and any added under the standing rule, see Design Notes): 0 confidently wrong, 0 Red↔Orange on kept faces, ≥ 90% side correct, ≥ 3/4 side faces kept (1-blank face counts kept, blank not correct); backlit reported and must trip the refit light check; OTHER_CUBE and OTHER_LIGHT on held-out with 0 confidently wrong kept; report holds delayed by the wait; record label commit + exclusions hash -- AC6.
11. Tests: `QuickScanClassificationTest`, `QuickScanControllerTest`, `SideLightTest`, `GuidedScanFlowTest`, `ScanDiagnosticsRunLineTest`, calibration screen/view-model tests -- cover every I/O matrix row, hysteresis no-flip, steadiness restart, lit-vs-requested skip, toggle lock and defaults, return-from-calibration clear.
12. `docs/evidence/10-21/` -- `tuning-and-replay.md` (fitted constants, before/after, held-out results, label commit, exclusions hash) plus `issue-draft-coverage-shortfall.md` (S24 FE Blue read as White, recorder calibrations failing the centre check, the ceilings above; a draft only, not filed), and `device-session-run-sheet.md` (AC7 10+10 scans per device with false/missed-block counts, glossy torch captures, faces kept per scan, live 10 s wait check from `sinceDetectedMs`, backlit blocking decision and light-clear steadiness confirmation for the PO, AC8 BETA call template).

**Acceptance Criteria:**
- Given the replay with a matching calibration, when a face has ≤ 1 non-CONFIDENT sticker and no hard disagreement, then it is kept and the sticker is blank and pre-selected in Quick Scan review; without a match the all-9 rule holds.
- Given AC2 devices, when calibration saves, then the torch tag persists through Room v11; legacy rows read torch-off; mismatch logs `calibration=mismatch torch`.
- Given held-out groups, when `QuickScanSideFaceReplayTest` runs in CI, then on every group and scenario (other-cube and other-light included) kept faces carry 0 confidently wrong stickers and 0 Red↔Orange mix-ups, backlit groups trip the light check, and the coverage numbers are reported per device, light and torch against the bar (PO decision, not enforced).
- Given any tuning or held-out group, when the tuned gate replaces the old, then no sticker turns from uncertain into confident-wrong.
- Given the device-only ACs (7, 8 and the PO light decisions), then `docs/evidence/10-21/device-session-run-sheet.md` lists each with its steps and record fields.

## Spec Change Log

## Review Triage Log

### 2026-11-23 — Review pass
- verdicts: 36 findings — high 0, medium 7, low 23, false 4, maybe-false 2
- findings:
  - `[false]` `[reject]` (intent) Coverage half of the replay bar is reported, not enforced — the PO's AC6-split decision (a) in Design Notes and the AC text settle this.
  - `[low]` `[patch]` (intent) Off-face held-out groups are exempt from the safety assert in some scenarios — kept per the PO's standing rule (safety asserted where the code achieves it, reported otherwise); the evidence doc heading now says exactly which scenarios are excluded.
  - `[medium]` `[defer]` (intent) Pastel tuning group still keeps a confidently wrong sticker — same as the 10.19 gate (pre-existing); fitting on it makes Red never CONFIDENT. Deferred to Story 10.27 and the BETA call.
  - `[false]` `[reject]` (intent) N = 6 is set by the cap, not the data — the spec says "start 6; largest N <= 6"; the cap is the spec's.
  - `[low]` `[patch]` (intent) Calibrated light cut is not validated on backlight — no matched backlit calibration exists; stated in the new "Limits of this evidence" section.
  - `[low]` `[reject]` (intent) Label-set commit is the repository HEAD — the spec prescribes `git rev-parse HEAD` of Fixtures/corner; changing it would edit the spec.
  - `[low]` `[reject]` (intent) Calibrations opened from Settings now default to torch on, so the Guided 6-face scan gets torch-lit calibrations — the default is PO-settled in the intent contract; Guided's use of the calibration is unchanged. Noted as a residual risk.
  - `[medium]` `[patch]` (blind) matchAll returns Match on an empty merged list, skipping the torch rule at hold 1 — fixed: empty list returns Mismatch(CENTRE); test added.
  - `[low]` `[patch]` (blind) An uncertain plain centre can be the allowed blank but review never shows it — fixed: an uncertain plain centre sends the face to the grid; blanks name non-centre cells only; test added.
  - `[low]` `[reject]` (blind) Scan-wide (matchAll) and per-face (matchFor) match can disagree when the torch differs between holds — real only for a mid-scan torch switch; the fix needs a per-face light check and log, more than a direct correction.
  - `[false]` `[reject]` (blind) AC4 test can never fail — it fails as soon as any AGREEMENT_MARGIN falls below the 10.19 margin, which is the regression it guards.
  - `[low]` `[reject]` (blind) Label-set commit does not identify the label set — spec-prescribed value (same as the intent row).
  - `[low]` `[reject]` (blind) Opening calibration from the review overrides a torch-off choice — "opened from the review → on" is settled in the intent contract.
  - `[low]` `[reject]` (blind) Torch tag records the torch at Save, not per swatch — the intent contract says the tag is the actually-lit state when saved; per-swatch tracking adds new state.
  - `[low]` `[patch]` (blind) Debug light trace never reset and drops changes inside the rate limit — fixed: reset on run start and on viewfinder/capture events; test added.
  - `[medium]` `[patch]` (blind) QuickScanRouteTest weakened to 1 <= asked <= 6 — fixed: asserts the exact asked faces F, B, U, D, U still fed upside down.
  - `[low]` `[patch]` (blind) Fitted values have no evidence they constrain anything — the evidence doc now states N is the cap, the calibrated band is unvalidated, and the 1-blank rule never applies on a scored held-out group.
  - `[low]` `[patch]` (blind) Dead readAll and judged() — removed.
  - `[low]` `[patch]` (blind) returnFromCalibration's calibration parameter unused in production — removed.
  - `[low]` `[patch]` (blind) New Room column torchOn breaks the snake_case convention — renamed to torch_on; 11.json regenerated, migration test updated.
  - `[maybe-false]` `[defer]` (blind) Per-frame classification cost on the analyzer thread — not measured; deferred (medium, unverified) with a frame-time trace as the settling check.
  - `[low]` `[patch]` (blind) Evidence heading claims safety enforced on every held-out group — reworded to name the excluded off-face scenarios.
  - `[medium]` `[patch]` (gap) Activity wiring of the calibration torch and return to the corner view untested — added two QuickScanRouteTest cases with a flash camera (corner choice carries, lock after Keep, Save tag from torchApplied, Back/Close clear the light hold and offer, review opens torch-on).
  - `[medium]` `[patch]` (gap) Blanks through a hand-over Done untested — added a controller test asserting Done.blanks after the grid finishes.
  - `[medium]` `[patch]` (gap) Re-asked-face route test no longer pins the asked faces — same fix as the blind row above.
  - `[low]` `[patch]` (edge) Match passes vacuously when every centre is guarded — fixed: at least one plain centre must be checked; test added.
  - `[low]` `[patch]` (edge) Centre blank not shown in review — same fix as the blind centre row.
  - `[low]` `[reject]` (edge) Review overrides the corner torch choice — intent-settled default (same as blind row).
  - `[low]` `[reject]` (edge) Swatches could be sampled under a different torch than the saved tag — intent defines the tag at save; locking Keep on applied state adds new guards.
  - `[low]` `[reject]` (edge) fewConfident reads false while poseRejected stays few-confident on frames failing another check — harmless: the failing check's own coaching shows; the flag is re-evaluated on the next settled frame.
  - `[maybe-false]` `[defer]` (edge) Heavy per-frame work may drop frames — grouped with the blind perf row; deferred.
  - `[low]` `[reject]` (edge) Band frames do not reset the clear window, so it counts non-consecutive below-clear time — consistent with the existing neutral-frame rule (an unreadable frame also neither advances nor resets); the matrix says the band changes nothing.
  - `[low]` `[patch]` (edge) A clear by clearLightHold logs a false flip — fixed: a LIGHT_CLEAR with a reason sets the trace to not-held; test added.
  - `[low]` `[patch]` (edge) Light trace is a process-wide singleton — same fix as the blind trace row.
  - `[medium]` `[patch]` (edge) Quick Scan review used the saved calibration after a mismatch — fixed: QuickScanEffect.Done carries the calibration to use (null on mismatch) and review reads it via GuidedScanFlow.quickReviewOf; controller and flow tests added.
  - `[false]` `[reject]` (edge) A torch mismatch can be logged as a centre mismatch — matchFor checks the torch first per face, so with one torch state throughout every face reports TORCH; CENTRE only wins with a mid-scan torch switch (the blind mixed-torch row).

### 2026-11-23 — Review pass (follow-up, Claude layers; Codex unavailable)
- verdicts: 35 findings — high 0, medium 7, low 17, false 9, maybe-false 2
- findings:
  - `[false]` `[reject]` (intent) Coverage half of the replay bar is reported, not enforced — carried: the PO's AC6-split decision (a) in Design Notes settles this.
  - `[false]` `[reject]` (intent) `OFF_FACE_UNSAFE` exempts `lenovo-tb128xu_stla_bar-cool` and `sm-s721u1_stla_bar-warm` from the safety assert — PO decision 2026-11-23 (off-face safety, Design Notes): both are fixture defects, report-only, routed to 10.27/#338 and the BETA call.
  - `[medium]` `[defer]` (intent) Pastel tuning group still keeps a confidently wrong sticker — carried: already in `deferred` (10.27, BETA call).
  - `[false]` `[reject]` (intent) The 1-blank rule never applies on a scored held-out group — that is the reported coverage shortfall the AC6 split accepts; the rule is exercised by unit tests and tuning groups.
  - `[false]` `[reject]` (intent) N = 6 is the cap, not the data — carried: the spec says "start 6; largest N <= 6".
  - `[low]` `[patch]` (intent) Calibrated light band not validated on backlight — carried: stated in the evidence's "Limits of this evidence"; no new patch.
  - `[medium]` `[patch]` (intent) Settled UI behaviour checked only through pure helpers (Settings default, torch lit in calibration, Close/Back) — grouped with the gap Settings row: new route test `aCalibrationFromSettingsShowsTheTorchOnLightsItAndTagsTheSave`; Close/Back already covered by `theCalibrationTorchFollowsTheCornerChoiceLocksOnKeepAndTagsTheSave`; the physical lighting part is deferred with the gap torch row.
  - `[false]` `[reject]` (intent) Only the first blank is pre-selected — `MAX_KEPT_BLANKS = 1`, so a kept face has at most one blank; the first is the only one.
  - `[low]` `[reject]` (intent) Label-set commit is the repository HEAD — carried: spec-prescribed value.
  - `[low]` `[reject]` (intent) A Quick Scan face-by-face from the start hands the raw `saved` to review — pre-existing Guided-grid use of calibration, which the Never list keeps unchanged; the comment in `done()` states it.
  - `[medium]` `[patch]` (gap) A retake from a Quick Scan review rereads faces with the saved calibration the scan rejected — fixed: `GuidedScanPhase.Review.calibration` carries the calibration the review was read with (`Done.calibration` for Quick Scan, `saved` for Guided); `reviewAfterRetake` defaults to it and `handleRetake` no longer passes `vm.saved`; `GuidedScanFlowTest` case added.
  - `[medium]` `[defer]` (gap) Lighting the torch in the calibration phase (`applyTorch` with `calibrating`) is only checked against a fake camera — CameraX torch state is not drivable in Robolectric; the run sheet's physical check settles it.
  - `[medium]` `[patch]` (gap) A calibration opened from Settings depends on untested flash detection — fixed: route test opens `calibrationIntent` with a flash camera and asserts "Torch on", last `torch(true)` and a saved `torchOn == true`.
  - `[low]` `[reject]` (gap, other) Face-by-face from the start keeps `saved` — same as the intent row above.
  - `[medium]` `[patch]` (edge) Retake after a mismatch uses the rejected calibration — same root cause and fix as the gap retake row.
  - `[low]` `[reject]` (edge) `done()` keeps blanks of matched faces when a later face mismatches — needs a mid-scan torch switch, and a blank is safe (the Learner fills it); clearing them adds a branch.
  - `[low]` `[reject]` (edge) Swatches sampled before the torch applies but saved under one tag — carried: intent defines the tag at save.
  - `[low]` `[reject]` (edge) Calibration from review sets the shared torch on and never restores it — carried: "opened from the review → on" and one shared choice are intent-settled.
  - `[low]` `[reject]` (edge) A pending manual press captures while the pose is still marked few-confident, with no POSE_ACCEPTED — debug timeline only; manual shutter capturing is settled behaviour; the fix adds an event branch.
  - `[low]` `[reject]` (edge) Band frames do not reset the clear window — carried: consistent with the existing neutral-frame rule.
  - `[low]` `[reject]` (edge) Cells turning unread never count as moved in the clear window — such frames are neutral under the existing rule; counting null changes as moves risks a window that never clears; unlikely in use.
  - `[false]` `[reject]` (edge) `gate` throws on a margin map missing a colour — `AGREEMENT_MARGIN` maps all six colours and is the only map production passes.
  - `[low]` `[reject]` (edge) Light check uses scan-wide `matchAll` while faces match per capture torch — carried: real only for a mid-scan torch switch.
  - `[false]` `[reject]` (edge) The few-confident gate is asked after settle/quality and a pending press bypasses it — nothing auto-captures while settle or quality fail, and "manual shutter still captures" is settled.
  - `[low]` `[reject]` (blind) Final-run review counts do not add up — the fix edits this build's spec; this pass's Auto Run Result records its own counts.
  - `[low]` `[reject]` (blind) Spec status and the halt headline contradict — the fix edits this build's spec; finalization sets `status: done`.
  - `[medium]` `[defer]` (blind) Orange margin 21.3 rests on one tuning sticker that may be a mislabel — checking or excluding labels is Story 10.27's quarantine, which the Never list keeps out of this story.
  - `[low]` `[patch]` (blind) Docs never say plainly that coverage regresses against the 10.19 gate — fixed: one sentence each in tuning-and-replay.md, issue-draft-coverage-shortfall.md and a BETA-template row in device-session-run-sheet.md (stka_bar-cool-3 23→20, stka_bar-warm-2 16→13, tuning faces kept 43→18 of 156).
  - `[low]` `[reject]` (blind) Backlit trip margins weaker and no per-hold before/after — hold cut fitted by the spec's rule (above the tuning front-lit maximum), the replay asserts the trip; backlit blocking is the PO's device decision; per-hold reporting is added scope.
  - `[maybe-false]` `[defer]` (blind) The 0.02-wide calibrated band was never tested against frame jitter — would be medium; settle with flip counts on bursts or the run sheet's light-clear steadiness check.
  - `[false]` `[reject]` (blind) The wait counts centres and was never tested on blocking holds — the spec defines the count over the hold's 27 stickers; the live wait is a run-sheet check.
  - `[low]` `[reject]` (blind) Settings calibrations default torch-on and Guided ignores the tag — carried: intent-settled default, residual risk recorded.
  - `[low]` `[reject]` (blind) `calibration=mismatch centre` does not name the failing centre — adds fields to `Mismatch` and the log; the device session can read the per-sticker lines.
  - `[maybe-false]` `[defer]` (blind) Duplicated per-frame match/self-calibration work — carried: already in `deferred` (unverified).
  - `[false]` `[reject]` (blind) Migration test bypasses Room for the legacy row — `RoomCalibrationRepositoryTest` round-trips `torchOn = null` through the DAO, the migration test shows the null column, and `matchFor` treats null as off (unit test).

## Design Notes

- "Uncertain" for the keep rule = any judged sticker not CONFIDENT after the gate (close call or soft disagreement); a hard disagreement (two CONFIDENT reads naming different colours) is "beyond the gate" and sends the face to the grid.
- Fixture MATCHING calibration takes the group's `torch` as its tag; OTHER_LIGHT/OTHER_CUBE take their source group's.
- Fixtures hold only the captured frame per hold, so "held back > 10 s" is checked in CI as "the captured frame reaches N" plus burst replay timing (Lenovo only); live timing goes to the run sheet. Backlit groups are held-out only, so the hold cut is fitted from tuning front-lit holds and validated on held-out backlit.
- If the held-out bar cannot be met by any rule the ACs allow, HALT blocked (the bar is an AC; never relax it silently).
- **PO scope decision (2026-11-23, answer (c) to the first blocked run):** the AC6 bar is enforced on every held-out device × light × torch condition except S24 FE warm front-lit torch-on, whose only held-out group `Fixtures/corner/sm-s721u1_stla_bar-warm` is reported **unscored**. The report and `docs/evidence/10-21/tuning-and-replay.md` name the bad sample (hold 2 `L` face sampled off the face: centre reads blue Lab(43, 9, -38), like the `B` centre) and say it is carried to Story 10.27's quarantine and the PO's BETA call. On that group the replay still asserts whatever safety the code achieves (no kept face with a confidently wrong sticker, under every calibration scenario); if the code cannot achieve it, the replay reports it instead, and nothing is loosened to make it pass.
- **PO scope decision, extension (2026-11-23, second answer):** `lenovo-tb128xu_stla_bar-cool` and `lenovo-tb128xu_stla_bar-warm` get the same treatment: reported **unscored**, each naming its bad hold-2 `L` sample, with safety asserted where the code achieves it and reported otherwise. PO note on the cause, verbatim: "some of the faces where recorded withot using real corners because was only a recorder sesion that is maybe the problem". All three groups are routed to Story 10.27 (fixture quarantine), #315 (corner-model face placement), #338 and the PO's BETA call. Accepted consequence: the Lenovo TB128XU may have no enforced held-out front-light condition in this story. **Standing rule:** any other held-out group found with the same off-face defect (a face sample whose reads contradict its labels across several cells because the face was sampled off the face) gets the same treatment and is listed here: _none so far_. A different kind of problem still halts.
- **PO scope decision, AC6 split (2026-11-23, third answer, option (a)):** no scored held-out condition can meet the coverage bar. The ceilings on current reads are: `sm-s721u1_stka_bar-warm-2` 28/32 correct and 1/4 faces kept; `sm-s721u1_stla_bar-cool` 27/32 and 1/4; `sm-s721u1_stka_bar-cool-3` 24/32 and 0/4. Every S24 FE recorder calibration fails the centre check, mostly because Blue reads as White. So the story is built in full, and in CI `QuickScanSideFaceReplayTest` **enforces the safety half of AC6** on **every** held-out group (the off-face groups included) and in every scenario (NONE, MATCHING, OTHER_LIGHT, OTHER_CUBE):
  - 0 confidently wrong stickers on kept faces;
  - 0 Red↔Orange mix-ups on kept faces;
  - backlit groups trip the light check.

  The **coverage half** (≥ 90% side stickers correct, ≥ 3/4 side faces kept) is **reported per device × light × torch against the bar, not enforced**. The shortfall goes to the PO's BETA call and to a new issue, drafted in `docs/evidence/10-21/issue-draft-coverage-shortfall.md` and not created by this story. Tuning stays on the tuning split only, and no safety rule is loosened to pass. This decision replaces the off-face exclusions for the coverage half; the off-face groups stay named in the report.
- **PO decision, off-face safety (2026-11-23, follow-up review):** the off-face held-out groups `sm-s721u1_stla_bar-warm` and `lenovo-tb128xu_stla_bar-cool`, whose kept faces still carry confidently wrong stickers (bad hold-2 `L` samples), are accepted as **fixture defects**. They are **report-only** for the safety half too: the replay reports their confidently wrong kept stickers instead of asserting them, and no rule is changed to make them safe. They are routed to Story 10.27 (fixture quarantine), #338 and the PO's BETA call. Every other held-out group stays under the enforced safety half. Reviews must not treat their unsafe kept faces as a defect of this story.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-21 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (incl. replay bar, migration test, Kover).
- `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*'` -- expected: pass; report lists per-condition held-out results with label commit and exclusions hash.

## Auto Run Result

Status: blocked
Blocking condition: implementation verification failed — the held-out replay bar (AC6) cannot be met on the only held-out Galaxy S24 FE warm front-lit torch-on group, `Fixtures/corner/sm-s721u1_stla_bar-warm`, by any rule the ACs allow.

Evidence (implementation run on Codex, checked by hand; details in [tuning-and-replay.md](../../docs/evidence/10-21/tuning-and-replay.md)):
- Hold 2's `L` face sample is not on the orange face: its centre reads Lab(43, 9, -38), which is blue, the same as the `B` face centre Lab(42, 6, -33). Its nine reads (yellow ×4, blue ×2, orange, white-ish, green) do not match its labels `WH YE BL YE OR WH RE BL OR`. The face outline was most likely placed off the face. The labels themselves look right for the real face.
- Because of that bad sample, self-calibration has no valid orange reference. Orange stickers on hold-1 `F` and `R` and on hold-2 `B` then read confidently as red, and both reads agree. The group's recorder calibration also fails the centre check (orange centre reads white, blue centre reads confident white), so the all-9 rule applies.
- So every side face either keeps a confidently wrong sticker or goes to the grid. Safe retention is at most 0 of 4 side faces, against a bar of 3 of 4. No other held-out group covers S24 FE + warm front light + torch on.
- Held-out data was used only for this feasibility check. No constants were fitted and no production code was changed. The run sheet draft is at `docs/evidence/10-21/device-session-run-sheet.md`.

Question for the PO: how should the AC6 bar treat this held-out condition?
- (a) Wait for Story 10.27 to mark the hold-2 `L` face unreadable (its placement-error check) or exclude the group in `label-exclusions.json`, then score the condition without it.
- (b) Record a replacement held-out group for S24 FE warm front-lit torch-on (a device session).
- (c) Enforce the bar on every other condition and report this condition only, recording it for the BETA call.

Resolution (2026-11-23): the PO chose (c); recorded as a PO scope decision in Design Notes. Run resumed at step 3.

### Second halt (2026-11-23)

Status: blocked
Blocking condition: implementation verification failed. The AC6 bar cannot be met on the scored Lenovo TB128XU front-lit conditions, because both of them show the same defect as the PO-unscored S24 group.

Evidence (implementation run on Codex, plus a hand sweep of every held-out group, nearest recorder-calibration colour against the labels):
- `lenovo-tb128xu_stla_bar-cool` is the only held-out group for Lenovo cool front light. Its hold-2 `L` face has 5 non-centre stickers that both reads name wrong and CONFIDENT: cell 1 White→Yellow, 2 Yellow→Orange, 3 Blue→Yellow, 6 White→Blue, 9 Orange→White. So side accuracy can reach at most 27 of 32 (84.4%), below 90%. Keeping that face safely would need 5 blanks, and the rule allows 1.
- `lenovo-tb128xu_stla_bar-warm` is the only held-out group for Lenovo warm front light. Its hold-2 `L` face shows the same pattern: 5 mismatches (1 White→Yellow, 2 Yellow→Blue, 4 Yellow→Orange, 8 Blue→Orange, 9 Orange→White).
- All `stla` groups carry the same `L` labels (same scramble). On `lenovo-tb128xu_stla_ceilback` the reads match those labels exactly. No rotation or mirror of the labels matches the bar-warm or bar-cool reads, and those two reads also differ from each other. This points to the hold-2 `L` face being sampled off the face (as in `sm-s721u1_stla_bar-warm`), not to wrong labels. That is Story 10.27's placement-error and quarantine check.
- If these two groups go unscored as well, the Lenovo has no scored held-out condition left (its third held-out group, ceilback, is backlit and reported only).
- No constants were fitted and no production code was changed.

Question for the PO: extend the 2026-11-23 scope decision (c) to `lenovo-tb128xu_stla_bar-cool` and `lenovo-tb128xu_stla_bar-warm`?
- (a) Yes: report both unscored, naming the hold-2 `L` samples, and carry them to 10.27 and the BETA call. Lenovo then has no enforced held-out condition in this story.
- (b) Score those groups with the hold-2 `L` face excluded as unreadable (the bar is computed over the remaining side faces).
- (c) Wait for 10.27 or new recordings before 10.21 continues.

Resolution (2026-11-23): the PO extended decision (c) to both Lenovo stla front-light groups and set a standing rule for the same defect; this is recorded in Design Notes. The run resumed at step 3.

### Third halt (2026-11-23)

Status: blocked
Blocking condition: implementation verification failed. The AC6 held-out bar cannot be met on **any** scored condition, and this is not the off-face defect. With the three off-face groups unscored, only three scored held-out conditions remain, all on the Galaxy S24 FE (Lenovo has none). Codex checked their feasibility with the current reads; nothing was fitted on held-out data. Details are in [tuning-and-replay.md](../../docs/evidence/10-21/tuning-and-replay.md).

| Group (condition) | Recorder calibration | Side-sticker accuracy ceiling | Safe faces-kept ceiling | Cells neither read gets right |
|---|---|---:|---:|---|
| `sm-s721u1_stka_bar-warm-2` (warm, torch off) | torch matches; centre check fails on both holds (hold-1 red centre UNCERTAIN; hold-2 blue centre reads White CONFIDENT) | 28/32 (87.5%) | 1/4 | h1 F1, F6 (Blue→White); h2 L2 (Blue→White); h2 B4 (Orange→Red) |
| `sm-s721u1_stla_bar-cool` (cool, torch on) | torch matches; centre check fails on both holds | 27/32 (84.4%) | 1/4 | h1 F8, F9, R1; h2 L7, L9 |
| `sm-s721u1_stka_bar-cool-3` (cool, torch off) | torch matches; centre check fails on both holds | 24/32 (75%) | 0/4 | h1 F1, F6, F9, R6, R7, R8; h2 L3, B8 |

The bars are at least 90% of side stickers correct and 3 of 4 side faces kept.

- On every scored group the recorder calibration fails the AC1 centre check, even with a matching torch, mostly because Blue reads as White on the S24 FE. So the ≤1-blank rule never applies, and every face needs all 9 stickers CONFIDENT.
- The tunable parts this story allows (per-colour agreement gate, N, light cuts) can only leave a wrong read uncertain or make it confident. They cannot supply the right colour. So no allowed rule reaches the bar, and loosening a rule would create confidently wrong stickers.
- This matches the risk in SCP 2026-11-21 ("10.21 may still miss the bar on the S24 FE").

Question for the PO: given that no scored held-out condition can meet the AC6 bar, how should 10.21 proceed?
- (a) **[Recommended]** Build the whole story. `QuickScanSideFaceReplayTest` enforces the safety half of AC6 in CI on every held-out group and scenario: 0 confidently wrong side stickers and 0 Red↔Orange on kept faces, other-cube and other-light runs included, and backlit groups trip the light check. The coverage half (≥90% correct, ≥3/4 faces kept) is reported per condition against the bar, not enforced. The shortfall goes to the BETA call (Quick Scan likely stays OFF) and to a new issue.
- (b) Enforce the full bar as written, so CI stays red until new fixtures or a recognition change (for example, Blue vs White separation on the S24 FE) makes it pass. The story cannot merge until then.
- (c) Pause 10.21 for a recognition change or new recordings first.

Resolution (2026-11-23): the PO chose (a), splitting AC6 into enforced safety and reported coverage. This is recorded in Design Notes, and the run resumed at step 3.

### Final run (2026-11-23)

Status: done

**Summary.** Story 10.21 built in full on Claude (Codex unavailable). A saved calibration is used only when its torch tag equals the physically lit torch and every captured plain centre reads CONFIDENT as its own colour; with a match a face is kept with at most 1 uncertain non-centre sticker (blank and pre-selected in review) and no hard disagreement, otherwise the all-9 rule holds. Calibrations carry a torch tag (Room v11, `torch_on`), the calibration phase shows the shared torch toggle (locked from the first Keep), a confident-count wait (N = 6) holds auto-capture, and the light check has hold/clear hysteresis cuts with the 2-cell steadiness restart. Constants were fitted on the tuning split only (`QuickScanGateTuningTest`). The replay enforces the AC6 safety half and reports coverage, which is below the bar on every scored condition (draft issue in docs/evidence/10-21/issue-draft-coverage-shortfall.md, not filed). The earlier run's scratch ZzExploreTest.kt and ZzExplore3Test.kt were exploration only and are gone.

**Files changed.**
- `core/domain/.../QuickScanClassification.kt` — CalibrationMatch, match/matchFor/matchAll, decide (1-blank rule, hard disagreement), per-colour AGREEMENT_MARGIN.
- `core/domain/.../QuickScan.kt` — torchLit vs requested, per-face capture torch, few-confident wait, returnFromCalibration, Done.blanks and Done.calibration, capture report match.
- `core/domain/.../CornerHold.kt`, `SideLight.kt` — hold/clear cuts, band neutral, clear-window restart on movement.
- `core/domain/.../FaceSample.kt`, `ScanAssembler.kt`, `ScanReview.kt` — torch tag on CalibrationCenters; blanks placed and pre-selected in review.
- `app/src/main/.../ColorCalibrationEntity.kt`, `RubikDatabase.kt`, `app/schemas/.../11.json` — nullable torch_on column, AutoMigration 10→11.
- `app/src/camera/...` (GuidedScanActivity, GuidedScanFlow, GuidedScanScreen, ScanFrameSource) — calibration-phase torch, defaults, lock, saved tag, return-to-corner clear, quick review calibration.
- `app/src/debug|release/.../ScanDiagnostics.kt` — calibration match, few-confident, far-above-cut/flip/level-change lines.
- Tests: QuickScanClassificationTest, QuickScanControllerTest, SideLightTest, QuickScanSideFaceReplayTest, QuickScanBurstReplayTest, new QuickScanGateTuningTest, GuidedScanFlowTest, QuickScanRouteTest, ScanDiagnosticsRunLineTest, CalibrationViewModelTest, ColorCalibrationScreenTest, SessionDatabaseMigrationTest, RoomCalibrationRepositoryTest, CalibrationTest; CornerFixtureHarnessTest carries the fix already on main (#336) so the baseline builds.
- `docs/evidence/10-21/` — tuning-and-replay.md, issue-draft-coverage-shortfall.md (draft only), device-session-run-sheet.md.

**Review.** 36 findings: 17 patch rows (5 medium entries, the rest low) applied; 2 deferred (pastel tuning group, per-frame cost unverified); 17 rejected (4 false, 13 low with the reasons in the triage log). Patched entries by verdict: medium 5, low 9.

**Follow-up review recommended: true.** Five medium entries were patched in one pass, including a new public field (QuickScanEffect.Done.calibration) that changes which calibration the Quick Scan review reads, and new activity-level route tests written in the same pass; neither has had an independent review.

**Verification.** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (incl. Kover, migration test, replay); `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*'` BUILD SUCCESSFUL. Matrix audit: every I/O row has a passing test (classification, controller, migration, flow, diagnostics).

**Residual risks.**
- Coverage: no scored held-out S24 FE condition keeps a side face (best 62.5% correct); every S24 FE recorder calibration fails the centre check (Blue reads White), so the 1-blank rule never applies there. Quick Scan likely stays OFF at the BETA call.
- Off-face groups `sm-s721u1_stla_bar-warm` and `lenovo-tb128xu_stla_bar-cool` still keep confidently wrong stickers (bad hold-2 `L` sample); reported per the standing rule, routed to 10.27, #315, #338.
- The fit used plain tuning groups only; the implementer disclosed one held-out replay run with the pastel-inclusive fit before committing the plain fit (choice rests on the epic pastel rule).
- Calibrated light cut unvalidated on backlight; calibrations opened from Settings now default to torch-lit, which the Guided 6-face scan will also use.
- Device ACs (7, 8, PO light decisions) are on the run sheet, not yet run.

### Follow-up review pass (2026-11-23)

Status: done

**Summary.** Follow-up review of the built story on Claude layers (Codex unavailable). PO decision recorded in Design Notes: the off-face held-out groups `sm-s721u1_stla_bar-warm` and `lenovo-tb128xu_stla_bar-cool` are fixture defects, report-only for safety too, routed to Story 10.27, #338 and the BETA call. Three patches applied: a retake from a Quick Scan review now uses the calibration the review was read with (null after a mismatch) instead of the rejected saved one; a route test covers a Settings-opened calibration on a flash camera; the evidence docs state the coverage regression against the 10.19 gate.

**Files changed this pass.**
- `app/src/camera/.../GuidedScanScreen.kt` — `GuidedScanPhase.Review.calibration` (the calibration the review was read with).
- `app/src/camera/.../GuidedScanFlow.kt` — `reviewOf` fills it; `reviewAfterRetake` defaults to it.
- `app/src/camera/.../GuidedScanActivity.kt` — `handleRetake` no longer passes `vm.saved`.
- `app/src/testDebug/.../camera/GuidedScanFlowTest.kt` — mismatched-calibration retake case.
- `app/src/testDebug/.../QuickScanRouteTest.kt` — `aCalibrationFromSettingsShowsTheTorchOnLightsItAndTagsTheSave`.
- `docs/evidence/10-21/tuning-and-replay.md`, `issue-draft-coverage-shortfall.md`, `device-session-run-sheet.md` — coverage regression vs the 10.19 gate stated; BETA-template row.
- This spec — PO off-face decision, triage log, three new deferred items.

**Review.** 35 findings (high 0, medium 7, low 17, false 9, maybe-false 2). Patched entries: medium 2 (retake calibration; Settings-calibration route test), low 1 (docs); one carried low patch row needed no new change. Deferred: calibration-phase torch lighting on real hardware, the single-sticker Orange margin (10.27), the jitter test of the light band (unverified); two carried deferrals unchanged. Rejected: 9 false and 15 low, each with its reason in the triage log.

**Follow-up review recommended: false.** Follow-up pass and no `high` entry was patched.

**Verification.** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (incl. Kover, migration test, replay); `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*'` BUILD SUCCESSFUL. The patch worker ran QuickScanRouteTest (19) and GuidedScanFlowTest (51), all passing.

**Residual risks.** As in the final run, plus: the retake test checks which calibration is carried, not a visible sticker difference (self-calibration overrides the saved centres on the synthetic samples); torch lighting in calibration and the light-band jitter rest on the device session; the off-face groups stay unsafe by PO decision.
