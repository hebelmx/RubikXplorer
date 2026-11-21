---
title: 'Story 10.19: Quick Scan reads the side faces in good light'
type: 'feature'
created: '2026-11-21'
status: 'done'
baseline_revision: '4144b65f8ac1a011ee79ec5d1fb230e5cd8d045e'
review_loop_iteration: 2
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-21-quick-scan-front-light.md'
  - '{project-root}/docs/evidence/10-18/d2-side-face-replay.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Both light-check cuts (uncalibrated 27 L*, calibrated 1.34) were fitted and checked on the same 16 Lenovo holds; they are provisional.
    evidence: |-
      MAX_SIDE_DARKER_L and MAX_TOP_OVER_SIDES_RATIO separate the pre-10.19 captures with about 4 L* and 0.05 margins. No held-out data, one device, legacy light labels. Settled by D1 counts and D4 captures.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SideLight.kt
    severity: medium (unverified)
  - summary: >-
      Light levels measured live (FrameStats cell L*) may differ from the recorded FaceSample stickers the cuts were derived on.
    evidence: |-
      The thresholds come from SideLight.checkSamples on recorded samples; production calls SideLight.check on live cells. Compare the logged lightScore with recorded values for the same hold in D1.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt (onFrame)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** On the minimum device (Lenovo TB128XU, no flash) Quick Scan's shaded 3D side faces lose color separation (Red vs Orange, Yellow, Orange worst), so 29 of 32 recorded side faces go to the Guided grid. The cause is light, not corner detection: with the light behind the cube the sides are far darker, metering is on the drawn grid centre (not the cube), the saved calibration never overrides shaded side-face centres, and no torch is used where one exists.

**Approach:** Combine four levers, all approved in SCP 2026-11-21: (1) a light check that is part of "aligned and steady", with inline coaching; (2) metering AE/AF (and AWB on Hold 1 only) on the detected cube region with a support check, centre fallback and a settle signal; (3) torch on by default during corner capture on devices with a flash, with a toggle and torch-glare coaching; (4) Quick-Scan-only classification with the saved calibration plus a per-face correction against each face's own known centre, gated so a mismatched calibration ends in uncertainty, never a confident wrong sticker. Enforce the bar per device and light in `QuickScanSideFaceReplayTest`, fix the debug recorder's corner mode and `evaluate_model.py --fixtures-corner` so the before-merge device session can produce the remaining evidence.

**Refs (verbatim from the story):** FR-22, FR-23, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10, NFR-11 · AD-9, AD-13, AD-18 · OQ-8 · UX-DR15 · Stories 10.4, 10.8, 10.17, 10.18, 11.1 · #195, #196

## Boundaries & Constraints

**Always:**
- AD-9: pixels, metering, exposure-state reads and the torch live only in `app/src/camera/` (never `app/src/main`, never `:core:*`); the domain receives numbers (luma/L*, booleans, timestamps) only. No frame is stored or logged.
- AD-13: no new permission (the torch needs none), no INTERNET, no new SDK; release `ScanDiagnostics` stays a no-op with matching signatures, and release logs never carry scan samples.
- AD-18: flag levels in `FeatureFlagLevels` unchanged (QUICK_SCAN already BETA in debug, OFF in release); no LiteRT/corner-model composition change.
- Guided 6-face Scan behavior unchanged: do not change the semantics of `SelfCalibration.from`, `StickerClassifier`, `ScanAssembler`, the 10 ΔE close-call rule, or `GuidedScanController`. New classification logic lives in Quick-Scan-only code (`QuickScanReadout` / `QuickScanController` or a new Quick-Scan-only file).
- Confidence calibration NFR: in every replay scenario 0 confidently wrong stickers on kept faces (faces not sent to the Guided grid); a wrong sticker on a face sent to the Guided grid counts as fallback, which is safe (PO 2026-11-21 C). Never turn an uncertain sticker into a suggestion; no side-face calibration step (PO decision).
- Learner text only in `core/domain/src/main/resources/content/strings/en.json`; exact strings: `msg.scan.coach.sides-dark` = "Turn so the light shines on the cube.", `msg.scan.coach.torch-glare` = "Glare from the torch — turn the torch off.", `btn.quick-scan.torch-on` = "Torch on", `btn.quick-scan.torch-off` = "Torch off". Existing `msg.scan.coach.glare` stays for glare with the torch off or without a flash.
- Light-check thresholds are constants derived from the recorded captures in `Fixtures/corner/`, with the per-group values written to `docs/evidence/10-19/light-check-and-replay.md`. The light check compares each side face against the top face only (PO 2026-11-21 D); no background measurement.
- The replay bar is never weakened, skipped, made an expected failure, or env-gated to pass; groups are never relabelled to dodge it.
- `:core:*` keeps ≥ 80% Kover line coverage; 200% font on 600 dp and the portrait phone viewfinder still fit without scrolling.

**Never:**
- Never edit `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`; never push.
- Never use haptics alone as capture feedback; never add a perspective hexagon sketch.
- Never retrain or replace `corner_keypoints.tflite` in this story.
- Never let the replay's mismatched-calibration scenarios produce a CONFIDENT wrong sticker to raise the correct-rate.

## PO decisions after the replay measurements (2026-11-21)

- **A — replay bar scope:** build the light check, metering, torch and per-face centre correction now. `QuickScanSideFaceReplayTest` enforces the per-device-and-light bar only on fixture groups recorded in the 10.19 device session with the new metering, identified by a group manifest (`group.json` in the group folder with `"pipeline": "10.19"` and `"lightDirection": "front"|"back"`, written by the recorder). Pre-10.19 groups (no manifest) are reported per group, not scored. If new captures miss the bar later, the story stays unmerged.
- **B — no-calibration path:** "never confidently wrong" applies there too: Quick Scan's self-calibration path gets the same agreement gate, so disagreeing stickers become UNCERTAIN (their face goes to the Guided grid). The Guided 6-face Scan stays unchanged.
- **C — safe outcome:** a confidently wrong sticker on a face sent to the Guided grid is fallback (safe); 0 confidently wrong is required on kept faces only.
- **D — light check:** side faces against the top face only; no background measurement.
- **E — light signal (PO 2026-11-21, after the attempt-1 review):** with a saved colour calibration, compare each face's brightness against the calibration's per-colour lightness for the colours seen on it (so a red or blue side under a white top in good front light is not held), with the cut re-derived from `Fixtures/corner/`. Without a saved calibration, fall back to the 75th-percentile sticker L* check (darker side face vs top, cut about 27 L*), accepting possible false holds on solved or nearly solved cubes; the device session measures false holds and missed blocks per device and per mode.
- **F — calibrated signal (PO 2026-11-21, option 3, chosen by search):** with a saved calibration, a face's *relative brightness* is the median over its nine stickers of L* ÷ the saved calibration's L* for that sticker's nearest colour (matched on a*b* only). The sides are too dark when the top's relative brightness ÷ the **mean** of the two side faces' relative brightness is above `MAX_TOP_OVER_SIDES_RATIO` = 1.34. Averaging both sides keeps a side-lit hold (one side dark, the other bright, stla warm hold 1 = 0.991) from being held. Without a calibration, the uncalibrated mode of decision E is unchanged.

## Settled UI behavior (PO clarifications 2026-11-21 plus the ACs)

- **Light hold:** the hold engages on the first detected frame whose light check fails; while held, neither auto-capture nor the manual shutter takes a photo (a press is ignored, no extra feedback). The coaching line "Turn so the light shines on the cube." appears after ~1 s of continuous failing with the cube detected. It outranks every other corner coaching (order: sides-dark > LOW_LIGHT > torch-glare/glare > BLURRED > PLAIN_LIGHT_SURFACE). The hold clears after the check passes for ~0.5 s while steady; the line stays up through that window and clears exactly when the hold clears; the steady count for auto-capture then restarts from zero. If the corners are lost while held, the line clears at once and the normal no-cube coaching takes over; the hold itself stays until a ~0.5 s pass-and-steady window after the cube is found again; the ~1 s failing timer restarts when the cube is found again. *Scan face by face* stays visible throughout and keeps any corner already captured (Story 10.17 path, unchanged).
- **Settle:** after any metering change (first detection in an episode, hold change, torch toggle) a capture waits for the settle signal. Auto-capture does not count steady time before it; a manual press during the wait is held and fires once settled, unless the light hold engages or detection is lost first (then it is dropped).
- **Torch (device with a flash unit only):** a flash-icon button in the corner viewfinder's top corner with a visible text label; tapping toggles the torch; label and TalkBack text show the current state: "Torch on" / "Torch off". Default on. The Learner's choice carries from Hold 1 to Hold 2 and is kept on return to the corner view (back from Guided Scan, or back from background). The torch is physically off on *Scan face by face* and any hand-over to the Guided grid, on leaving the capture view, and when the app goes to the background. With the torch on, glare coaching reads "Glare from the torch — turn the torch off."; with it off (or no flash), "Too much glare — tilt the cube". Without a flash unit: no torch control, no torch copy, torch-glare coaching never shown.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Front-lit steady | detected, check passes, settled, steady ≥ STEADY_MS | auto-capture fires; manual shutter works | — |
| Backlit | detected, a side face much darker than the top face | no auto or manual capture; after ~1 s coaching sides-dark | Scan face by face available |
| Light corrected | held, then pass ~0.5 s while steady | coaching clears with the hold; steady count restarts; capture follows | — |
| Pass < 0.5 s then fail | held, short pass | still held, line still shown | — |
| Cube lost while held | held, detection lost | line clears at once; no-cube coaching after 3 s; hold persists; 1 s timer restarts on re-detection | — |
| Backlight + low light | check fails and a face is LOW_LIGHT | sides-dark shown, not low-light | — |
| Torch glare | torch on, glare detected | torch-glare coaching; capture blocked as today for glare | turning torch off → plain glare logic; clears when glare gone |
| Glare, no torch | torch off or no flash | existing glare coaching | — |
| Metering unsupported | `isFocusMeteringSupported(action)` false | centre metering (current grid-centre point) used; logged | — |
| Settle timeout | no convergence within cap | capture proceeds after cap; diagnostics log timeout | — |
| Manual press before settle | press while settling | photo taken once settled | dropped if hold engages / detection lost |
| No saved calibration | Quick Scan | self-calibration from face centres plus the agreement gate | disagreement → UNCERTAIN, face to grid |
| Matching saved calibration | saved from same cube/light | saved centres + per-face centre correction | disagreement → UNCERTAIN |
| Mismatched calibration | other light / other cube / frontal on angled sides | 0 confident wrong on kept faces; wrong stickers only on faces sent to the grid | — |
| No flash device | `hasFlashUnit()` false | no torch control, no torch copy | — |
| Hand-over / background | Scan face by face, auto hand-over, onStop, finish | torch off; on return to corner view torch follows last choice | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CornerHold.kt` -- shared corner hold. `onFrame` L18-71: coaching ladder L41-48 (insert sides-dark first, torch-glare variant of GLARE); auto gate L60 (`SETTLE_MS` 600, `STEADY_MS` 1000, `UNDETECTED_COACHING_MS` 3000 at L82-86); `Remeter` on first detected frame L55-59. Light hold state machine and settle wait belong here.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` -- `QuickScanController(saved)` L76; `onFrame` L111-132 (signature gains light inputs, settle signal, torch state); `onShutter` L134-169 (only gate L135 today: add light-hold and settle gates; queued manual press); `needGrid` L247-269 and `involvedFaces` L237 (switch to the new Quick-Scan classification); `QuickScanReadout` L37-64 (`judgedAgainst`, `read`, `judged` — extension point for per-face centre correction); `switchToFaceByFace` L176, `backOut` L202, `handOver` L214.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `FrameStats` L55-63 (per-face `meanLuma`, cell Lab values); `FrameQuality` L82-230 (`assessShutter` L146, `MIN_MEAN_LUMA`); `enum Coaching` L283-295 (add `SIDES_DARK`, `TORCH_GLARE`); `defaultReprompt` L583-590 uses `SelfCalibration.from` — read-only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SelfCalibration.kt`, `StickerClassifier.kt` (`CLOSE_CALL_DELTA_E` L32, `read` L57, `deltaE` L79), `FaceSample.kt` (`CalibrationCenters` L57, `CENTER_INDEX`) -- read-only shared with Guided; reuse, do not change semantics.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `CornerFixtureRecord` L102, `CornerFixtureJson` (`CURRENT_SCHEMA = 3` L127, `decode` L137-153). Extend (schema 4, still decoding 3) for optional manual keypoints, detector keypoints, positive/negative label and light direction; keep schema-3 files readable.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanSideFaceReplayTest.kt` -- `replay()` L68 reads `../../Fixtures/corner`; uses recorder calibration as saved L82; `tally` L100; `toGrid` L106 (classification-only copy of `needGrid`); pooled `violations` L154; expected-failure bar L179 and `CUBENTIA_D2_ENFORCE` L197 — both removed.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanControllerTest.kt` -- JUnit 5, explicit `nowMs`; helpers `face` L35 (meanLuma 120), `hold` L48; coaching tests L217, L460.
- `Fixtures/corner/` -- 8 Lenovo groups `lenovo-tb128xu_<cube>_<light>`: stka {cool, cool-front, warm}, stla {cool, cool-front, warm}, stkb {cool}, stlb {cool}; each `corner-1|2.json/.jpg` (200×200 upright centre crop), schema 3, no keypoints. Light direction (d2 doc): `cool` = backlit, `warm` and `cool-front` = front-lit. Cube ids: stla/stlb stickerless, stka/stkb stickered.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `CameraXFrameSource` L74; `bindWhenLaidOut` L189-233 (`bindToLifecycle` L211, camera handle for `cameraInfo.hasFlashUnit()`, `cameraControl.enableTorch`); `stop` L134-142; `read` L267-301 (corner branch: has YuvFrame + quads); `meterOnGrid` L309-330 (`SurfaceOrientedMeteringPointFactory`, grid-centre point, future ignored); `meteringFlags` L339-344 (tested in `ScanFrameSourcePolicyTest.kt`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt` -- `CornerFrame(detected, faces, score, normalizedQuads, frameQuads)` L458-464; `CornerReader.read` L470-506; `darkSurroundings` L130-135 pattern (ring at `guide.vertices(scale = 1.13)`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- calibration load L138, `QuickScanController(scan.saved)` L147/L876; `capture()` L385-401; camera restart per phase `LaunchedEffect` L264-272; `onDestroy` L357, `onResume` L363 (add `onStop` torch off); `analyze` Quick Scan branch L548-559; `captureCorner` L586-615 (`includeAwb = quick.hold == 1` L595, Remeter → `meterOnGrid` L612); `backOutOfHandOver` L971-985; view-model holder fields ~L753/L903 (add `torchOn`, default true).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `phaseOf(quick)` L224-228 builds `CornerViewfinder` (add torch UI state).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanPhase.CornerViewfinder` L117-125; top bar L238-270; `CameraArea` L454-503; `CoachingLine` L507-522; exhaustive `coachingKey` L577-590; `FaceByFaceButton` L817-828; `GuidedScanActions` (add `onToggleTorch`).
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` (tag `CubentiaScanDiag`, `onCornerFrame` L41-52, `onQuickScanRun` L54-77) and `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` (no-op mirror) -- diagnostics for the device session.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/` -- `QuickScanScreenTest.kt` (Robolectric, `w360dp-h640dp-port`, L153 coaching pattern), `QuickScanLargeFontTest.kt` (`assertFits`, 200%), `GuidedScanScreenTest.kt` L372 (every `Coaching` has a key), `GuidedScanFlowTest.kt` L819, `CornerFixtureHarnessTest.kt` (reads fixture JPEGs through `CornerReader`), `ScanFrameSourcePolicyTest.kt`, `ScanDiagnosticsRunLineTest.kt`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/` -- `FixtureRecorderActivity.kt` (`analyze` L210-229 detects only on request; `encodeFrame(corner=true)` L264; exposure helpers L145-160; `startCamera` L180), `FixtureRecorderScreen.kt` (permission launcher L112-114; `captureCorner` L154/L160 drops misses; writes L207-210 unconditionally; `CornerOutline` without detected quads L678; re-record L555; meta reset L593-595), `RecorderRules.kt` (`cornerSamples` L332-337 returns null on miss). Tests `RecorderRulesTest.kt`, `FixtureExportTest.kt` (L81 live-mode replace is intentional, keep).
- `tools/corner-model/evaluate_model.py` (`--fixtures-corner` L125, `evaluate_real_corner_fixtures` L100, `evaluate_slice` L53, `MIN_RECALL`/`MIN_NEG_REJECTION` L38-39) and `tools/corner-model/train_corner_model.py` `load_optional_corner_fixtures` L114-172 (`rglob("*.json")`, expects `label`, `keypoints` 7×[u,v] normalised to the model crop, half-size `guide.radius*1.65`). uv project `tools/corner-model/pyproject.toml`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt` -- read-only (QUICK_SCAN BETA in debug L57).

## Tasks & Acceptance

**Execution:**
- `_bmad-output/implementation-artifacts/spec-10-19-attempt-2.patch` -- start by applying it (`git apply` from the repository root); it is attempt 2 of this spec. It already contains attempt 1, every review-loop-1 amendment and every triage `patch` row, and builds green except for the calibrated light-check test. Then replace the calibrated light signal with decision F (see the SideLight task), update its tests and the evidence doc, and keep everything else.
- `core/domain/.../recognition/SideLight.kt` -- pure light check over per-face sticker L*, two modes. **Calibrated** (a saved calibration exists, decision F): relative brightness per face = median of sticker L* / saved L* of the nearest saved colour on a*b*; fail when top ÷ mean(two sides) > `MAX_TOP_OVER_SIDES_RATIO` (1.34). On the recorded captures (each group against its own recorder calibration): front-lit holds 0.991–1.286, backlit holds 1.337–1.553, every backlit group's worse hold ≥ 1.392; 7 of 8 backlit holds trip (stka cool hold 2 at 1.337 does not). **Uncalibrated** (decision E): 75th-percentile L*, fail when the darker side is more than `MAX_SIDE_DARKER_L` (27) below the top. Each mode must pass all 8 front-lit holds and trip at least one hold in every backlit group (test both modes on the fixtures, assert the per-hold values above within 0.005). Synthetic test: a solved cube in uniform front light passes in calibrated mode. The verdict is computed in the domain (the controller has `saved`); diagnostics log the mode and the score.
- `core/domain/.../recognition/CornerHold.kt`, `QuickScan.kt`, `GuidedScan.kt` -- add `Coaching.SIDES_DARK` and `Coaching.TORCH_GLARE`; implement the light hold, 1 s coaching timer, 0.5 s pass-and-steady clear, coaching order, cube-lost behavior, settle wait (external `settled` signal with a cap, replacing the fixed `SETTLE_MS` only for Quick Scan corners) and the queued manual press, exactly per "Settled UI behavior"; Remeter also on hold change and on torch toggle; `QuickScanController` gets torch state (for coaching) and light inputs via `onFrame`; `onShutter` honours the light hold and settle.
- `core/domain/.../recognition/QuickScan.kt` (or new `QuickScanClassification.kt`) -- Quick-Scan-only classification with an agreement gate on both paths. Saved calibration: per-face correction mapping the face's own centre sticker to the saved centre of that color (L* gain or the variant the replay shows safest), applied to that face's stickers, classified against the saved centres with the 10 ΔE rule; CONFIDENT only when it agrees with the (confident) self-calibration read. No saved calibration: the self-calibration read (`SelfCalibration.from` centres) must agree with a second read against the same centres after per-face light normalization (each reference centre's L* scaled by the ratio of this face's light level to the light level of the face that centre came from, light level as in `SideLight`); CONFIDENT only when both reads are CONFIDENT and agree. Otherwise UNCERTAIN (face goes to the grid). Use it in `needGrid` and `involvedFaces`. `SelfCalibration`, `StickerClassifier` and all Guided paths untouched.
- `core/domain/src/test/.../QuickScanSideFaceReplayTest.kt` -- rewrite: group by `device.model` × light condition; a group is **scored** only when its `group.json` manifest says `"pipeline": "10.19"` (light direction from the manifest); pre-10.19 groups are reported only (their direction from the legacy table `warm`/`cool-front` → front, `cool` → back; an unknown legacy label is reported as unknown, never scored); use the production `needGrid` decision (quality + classification) for "sent to the Guided grid"; scenarios: no saved, matching (group's recorder calibration), different light (same cube's other-light group calibration), different cube (another cube's calibration), frontal calibration applied to angled side faces (the recorder calibration is frontal, so name it and report it), each reported per color (Yellow and Orange called out). Bar enforced by default (no env gate, no expected-failure) for every scored front-lit device×light group present: in the matching and no-saved scenarios 0 confidently wrong side stickers on kept faces, 0 Red↔Orange confusions on kept faces, ≥ 90% side stickers correct, ≥ 3 of 4 side faces not sent to the grid; in every scenario 0 confidently wrong stickers on kept faces. Scored backlit groups are reported, not scored for the bar, and each must trip `SideLight`. Pre-10.19 groups: full report, no assertions except that the legacy backlit groups trip `SideLight` and front-lit ones pass (AC1 threshold evidence). Print a report and list required-but-missing scored groups (Lenovo and S24 FE; ≥ 1 stickered and ≥ 1 stickerless cube; ≥ 2 front lights per device) without failing for absence; with zero scored groups the test passes and says so. Do not stop if pre-10.19 groups miss the bar.
- `core/domain/src/test/.../QuickScanControllerTest.kt` (+ new `SideLightTest.kt`, classification tests) -- unit-test every I/O-matrix domain row (timings with explicit `nowMs`), the coaching order, torch-glare vs glare, and that Guided self-calibration results are unchanged.
- `app/src/camera/kotlin/.../ScanFrameSource.kt` -- metering on the detected outline (AF+AE both holds, AWB Hold 1 only) using `frameQuads`; check `cameraInfo.isFocusMeteringSupported(action)` and fall back to today's centre point; settle signal = the `startFocusAndMetering` future completes and, via Camera2Interop capture results, `CONTROL_AE_STATE` is CONVERGED (or LOCKED/FLASH_REQUIRED), capped at 1500 ms; expose `hasFlashUnit` and `setTorch(on)`; report supported/settle ms/timeout to diagnostics. Keep `meteringFlags` behavior.
- `app/src/camera/kotlin/.../GuidedScanActivity.kt`, `GuidedScanFlow.kt` -- wire light inputs, settle callback, torch state (`torchOn` default true, kept across holds, hand-over/back and background), torch physically off on Guided phases, hand-over, `onStop`, finish; enable on returning to the corner view per choice; Remeter on toggle.
- `app/src/camera/kotlin/.../GuidedScanScreen.kt` + `en.json` -- torch button (flash icon + visible label, top corner of the corner viewfinder, tag `quickScan:torch`, state-dependent label = TalkBack text, toggle), only when the device has a flash; `coachingKey` for the two new values; the four en.json keys with the exact strings.
- `app/src/testDebug/kotlin/.../camera/` -- `QuickScanScreenTest`: sides-dark and torch-glare lines, torch label on/off, no torch control without a flash; `QuickScanLargeFontTest`: corner view with torch button fits at 200%; `GuidedScanFlowTest`: torch state through phases; `ScanFrameSourcePolicyTest`: metering point from quads, unsupported → centre fallback, settle cap; `CornerFixtureHarnessTest` unchanged unless the new `CornerFrame` fields require it.
- `app/src/debug/.../ScanDiagnostics.kt` + release mirror -- log per corner frame (rate-limited): per-face light level, side-vs-top difference, check verdict, hold state; events `lightHold start` / `lightHold clear` with durations; metering `supported=`, `settleMs=`, `timeout=`; torch `hasFlash=`, `torch=`; glare with torch state. No frames, no Cube States. Test in `ScanDiagnosticsRunLineTest` style.
- `app/src/camera/debug/kotlin/.../recorder/` -- corner mode: per-frame detection with the live detected outline over the Y anchor; freeze-frame manual placement of the 7 corners `[T, UL, LL, B, LR, UR, C]` (detector points saved separately as diagnostics, never as labels); save positives even when the detector misses (color samples optional then); a negative (no-cube) capture action; bind the camera immediately when permission is granted; never silently overwrite a saved group (confirm, or write a new numbered group); write the model-input crop + keypoint JSON the evaluator reads, and the group's `group.json` manifest (`pipeline: "10.19"`, `lightDirection`, device, cube, light label) chosen by the operator. `RecorderRules` logic unit-tested.
- `tools/corner-model/evaluate_model.py` (+ loader in `train_corner_model.py` if shared) -- read only keypoint fixtures (ignore schema-3 colour records), group by device, require ≥ 20 positives and ≥ 10 negatives per device (report missing), compute recall ≥ 92%, rejection ≥ 96%, mean corner error ≤ 3% of the cube outline diagonal; all real captures are held-out because the model is not retrained here; update the tool README section.
- `docs/evidence/10-19/light-check-and-replay.md` (new) -- derived thresholds with per-group values, replay report per device×light×scenario×color, and an empty device-session checklist (items below).

**Acceptance Criteria:**
- Given the main app debug build with QUICK_SCAN BETA, when a corner view sees a backlit cube, then no capture happens and the sides-dark line appears after ~1 s, and after correcting the light it captures (domain + Robolectric tests prove the logic; device proof is a session item).
- Given `./gradlew spotlessCheck build`, when CI runs, then `QuickScanSideFaceReplayTest` enforces the bar per device×light for every front-lit group present, reports backlit groups and missing required groups, and passes.
- Given a release build, then ScanDiagnostics is a no-op, no new permission appears and the flag levels are unchanged.
- **Device-session items (before-merge human gate, orchestrator over adb; not claimed by this build):** (D1) ≥ 10 front-lit and ≥ 10 backlit attempts per device on Lenovo TB128XU and Galaxy S24 FE: false-block and missed-block counts from `lightHold` diagnostics; (D2) metering support and settle times on both devices; (D3) S24 FE torch: default on, toggle, carry-over, off on face-by-face/leave/background, glossy cube both holds torch on/off, torch glare detected and cleared by turning the torch off; Lenovo shows no torch UI; (D4) new 10.19-pipeline corner fixtures (with `group.json`) on both devices, stickered, stickerless and glossy cubes, front and back light, then the replay bar on them (story stays unmerged if missed); (D5) `evaluate_model.py --fixtures-corner` on ≥ 20 positives and ≥ 10 negatives per device; (D6) PO two-corner completion before/after on the Lenovo and the Quick Scan BETA call (release level stays with Stories 10.8/10.11).

## Spec Change Log

### 2026-11-21: review loop 2 (calibrated light signal, PO option 3)
- **Trigger:** attempt 2 failed verification. The median-dL-vs-top calibrated signal cannot separate the recorded captures (front max 24.2, backlit group min 23.3). The PO declined both cut-based options and asked for a different signal.
- **Search (each group against its own recorder calibration; 8 front-lit and 8 backlit holds):**

| Candidate (score; held when above the cut) | Front-lit max | Backlit, lowest group max | Gap | Gap / range | Backlit holds above front max |
|---|---|---|---|---|---|
| P mean side median ratio / top | 1.286 | 1.392 | 0.106 | 0.188 | 8/8 |
| R uncalibrated 75th pct (fallback mode) | 24.996 | 29.135 | 4.139 | 0.183 | 7/8 |
| F mean of sides vs top (median dL) | 17.692 | 19.586 | 1.893 | 0.064 | 8/8 |
| E both sides: top − brighter side (median dL) | 13.53 | 16.189 | 2.659 | 0.061 | 8/8 |
| A median dL, top−worst side (attempt 2) | 24.192 | 23.346 | -0.846 | -0.028 | 3/8 |
| N p25 ratio, top / worst side | 1.706 | 1.56 | -0.146 | -0.1 | 3/8 |
| C ratio: top medR / worst side medR | 1.523 | 1.416 | -0.108 | -0.169 | 2/8 |
| G centre sticker dL, top − worst side | 23.523 | 15.718 | -7.806 | -0.288 | 2/8 |
| B worst side median dL alone (−) | 27.014 | 16.26 | -10.754 | -0.348 | 2/8 |
| K chroma loss worst side (1−medCR) | 0.422 | 0.178 | -0.244 | -0.454 | 0/8 |
| L chroma loss both sides (1−max medCR) | 0.283 | -0.122 | -0.405 | -0.744 | 1/8 |

- **Amended:** the calibrated mode becomes candidate P (decision F), cut 1.34, the midpoint of 1.286 and 1.392 (margin about 0.05 on each side). Candidate P is the only calibrated signal that separates the sets with a margin; F and E separate too, but by under 2 L* and 2.7 L*. The uncalibrated mode stays unchanged.
- **Known-bad state avoided:** a side-lit front hold being held, and missed backlit groups.
- **KEEP:** all of attempt 2 ([spec-10-19-attempt-2.patch](spec-10-19-attempt-2.patch)). Change only the calibrated signal, its constant, its tests and the evidence doc numbers.

### 2026-11-21: review loop 1 (bad_spec and intent_gap from the attempt-1 review)
- **Triggers:**
  - B3/B4/B5/I3 (intent_gap, now answered by PO decision E): the colour-dependent light signal.
  - B12/E5: the torch toggle re-meters before the physical torch changes.
  - B13/E6: the settle signal accepts a stale or absent AE state.
  - B19/E9: `normalized()` finds each centre's source face by exact Lab equality.
  - E12: `involvedFaces` uses the ungated read.
- **Amended:**
  1. SideLight runs in two modes per decision E, computed in the domain.
  2. Torch: a toggle re-meters only after the physical torch change is applied (the `enableTorch` future completed, or the next frame after the apply when no future is available); the settle window starts then; a manual press in between is queued.
  3. Settle: `meterCube` resets the AE state to unknown, and only capture results produced after the metering request count. Settled = the metering future completed and the post-request AE state is CONVERGED, LOCKED or FLASH_REQUIRED. An AE state still unknown counts as settled only at the 1500 ms cap (logged as a timeout). Define the cap once and share it with the domain.
  4. `normalized()`: a centre's source face is the captured sample whose `centerColor` is that colour. A centre with no captured source (filled from saved or derived) is explicitly unscaled and tested.
  5. `involvedFaces` uses the same gated Quick-Scan classification as `needGrid`.
- **Known-bad state avoided:**
  - solved cubes held in front light when a calibration exists;
  - captures taken on pre-torch or pre-metering exposure;
  - a normalization that silently does nothing;
  - reprompt faces chosen by an ungated read.
- **KEEP:** everything else in attempt 1:
  - the light-hold state machine and its PO-settled timings and coaching order;
  - the torch UI, its state rules and the strings;
  - the cube-region metering with centre fallback;
  - the agreement gate on both paths;
  - the replay rewrite (scoring only `group.json` 10.19 groups, legacy groups reported);
  - schema 4, the manifest and the recorder corner-mode features;
  - the per-device rules in `evaluate_model.py`;
  - the diagnostics;
  - the evidence doc (update it for both modes).

## Review Triage Log

### 2026-11-21 — Review pass
- verdicts: 46 findings — high 1, medium 24, low 11, false 9, maybe-false 1
- layers: blind-hunter (B), edge-case-hunter (E), verification-gap (V), intent-alignment (I); all Claude (Codex review guard failed: no codex-review.on)
- outcome: intent_gap (B3, B4, B5, I3), so the lower entries are moot. The attempted change is saved as [spec-10-19-attempt-1.patch](spec-10-19-attempt-1.patch) (eff37c49..5088b8bb), and its code is reverted.
- findings:
  - `false` `reject` B1: Replay bar passes with zero scored groups. Intended by PO decision A and the spec: only 10.19-pipeline groups are scored, missing groups are reported, and D4 keeps the story unmerged.
  - `medium` `reject` B2: Gate keeps almost no side faces on the pre-10.19 captures. Real on legacy data (0/4 in most scenarios), but the intent (PO A) puts the coverage bar on 10.19 captures only, so it is out of scope by intent; the replay reports it.
  - `high` `intent_gap` B3: Light check holds a solved or nearly solved cube in any light. Verified: SideLight.level is the 75th-percentile L* of the face's own stickers, so a red or blue side under a white or yellow top is over 27 L* darker in front light (the evidence doc calls this out). It breaks AC2 ("front-lit never blocks"), and fixing it needs a colour-independent signal and a new cut, which PO decision D (about 27 L* on this measure) does not settle.
  - `medium` `intent_gap` B4: 27 L* cut has a thin margin from 16 Lenovo holds. Real (2 L* above the highest front-lit value, 1.6 below the lowest failing backlit value); same root cause as B3, the choice of signal and threshold.
  - `maybe-false` `intent_gap` B5: Cut derived on recorded FaceSample stickers, applied to live FrameStats cells. Settled by comparing live and recorded levels for one hold in D1; grouped with B3 (signal definition).
  - `false` `reject` B6: Light hold stays with no coaching after the cube is lost. PO clarification (4) settles this: the line clears when the corners are lost and the hold stays until a pass window; Scan face by face is the exit. In production a null verdict only comes with an undetected frame.
  - `false` `reject` B7: Manual press ignored for the first second of a hold with no feedback. Settled UI behavior (spec, from AC1): a press while held is ignored with no extra feedback, and the coaching appears after about 1 s.
  - `low` `patch` B8: SETTLE_CAP_MS is defined twice (CornerHold and CameraXFrameSource). Verified, both 1500L. Moot because the run halts on the intent gap.
  - `low` `patch` B9: Recorder MODEL_CROP_RADIUS_MULTIPLIER duplicates CornerModelTransform. Real drift risk; moot (intent gap).
  - `medium` `patch` B10: Recorder meters differently from Quick Scan (no clamp, auto-cancel disabled, different fallback, stale outline). Real: fixtures tagged pipeline 10.19 would not reflect production metering, against PO A. Fix is to reuse cubeMeteringRegion/chooseMetering. Moot (intent gap).
  - `medium` `patch` B11: Group manifest and keypoint fixtures do not record the torch state. Real: D3/D4 cannot tell torch-on groups from torch-off groups. Moot (intent gap).
  - `medium` `bad_spec` B12: Torch toggle re-meters before the physical torch changes. Verified: setTorch calls corner.remeter() on the analysis thread, while enableTorch runs later from a main-thread LaunchedEffect, so the settle can complete on pre-torch exposure. The spec did not order torch changes and metering. Moot (intent gap).
  - `medium` `bad_spec` B13: Settle signal can use a stale AE state. Verified: meterCube resets meteringDone but never aeState (only line 379 sets it), and aeState == null counts as settled. The spec's settle definition did not require AE convergence after the request. Moot (intent gap).
  - `low` `patch` B14: Torch toggle exposes Role.Switch with no toggleable state. Verified: semantics set only role = Role.Switch on a clickable Surface. Moot (intent gap).
  - `false` `reject` B15: New coaching strings end with a period, unlike their neighbours. The PO fixed these exact strings, including the periods.
  - `false` `reject` B16: No light guidance or torch in the Guided grid or hand-over. The intent scopes the light check and torch to the corner capture view, and the torch turns off on Scan face by face.
  - `low` `patch` B17: README says nothing is skipped silently; the loader skips JSON without kind or label. Real; moot (intent gap).
  - `low` `patch` B18: evaluate_model.py puts fixtures without device.model in an "unknown" group that fails the minimums, and writes Infinity into JSON. Real; moot (intent gap).
  - `medium` `bad_spec` B19: normalized() finds each centre's source face by exact Lab equality, so saved or derived centres are never normalized; no test covers this. Verified in QuickScanClassification.normalized (merged.singleOrNull { centerColor == color && center == center }). The spec did not define the source face per colour. Torch-drift tests are also missing. Moot (intent gap).
  - `medium` `patch` E1: Pass frames reset lightFailingSince, so a flickering verdict holds the capture with no coaching. Verified in updateLight: lightFailingSince = null on every pass frame while still held. Moot (intent gap).
  - `false` `reject` E2: Light held and cube lost for good: shutter ignored, coaching cleared. PO clarification (4) settles this; Scan face by face stays available.
  - `medium` `patch` E3: A press after setTorch, before the next detected frame re-meters, is taken at once. Real: remeter() sets meteredAt = null, and press() checks only lightHeld or settling. Moot (intent gap).
  - `low` `reject` E4: Torch requested but not lit still gives torch-glare coaching. Rare (enableTorch failure on a device with a flash), and the fix needs new TorchState plumbing.
  - `medium` `bad_spec` E5: Torch toggle and settle are out of order (same as B12). Same root cause as B12. Moot (intent gap).
  - `medium` `bad_spec` E6: Stale aeState is accepted as settled (same as B13). Same root cause as B13. Moot (intent gap).
  - `medium` `patch` E7: meterCube now runs outside the CornerViewfinder phase check. Verified at GuidedScanActivity.kt:643, after the main-thread phase block; it can meter a Guided session. Moot (intent gap).
  - `medium` `patch` E8: Face-by-face or hand-over reset during a light hold logs no lightHold clear. Real: corner.reset() drops the hold without the diagnostic, which skews D1 counts. Moot (intent gap).
  - `medium` `bad_spec` E9: normalized() source lookup (same as B19). Same root cause as B19. Moot (intent gap).
  - `low` `patch` E10: evaluate_model.py "unknown" device group (same as B18). Same root cause as B18. Moot (intent gap).
  - `low` `patch` E11: Keypoint loader skips malformed fixtures silently (same as B17). Same root cause as B17. Moot (intent gap).
  - `medium` `bad_spec` E12: involvedFaces uses the ungated SelfCalibration read, not the gated classification the spec names. Verified at QuickScan.kt:284-286 (ScanAssembler.repromptFaces with plain calibration). This deviates from the spec task. Moot (intent gap).
  - `low` `reject` E13: The frame source names the API torch(on), not setTorch(on). Naming only; no behavior is affected.
  - `medium` `patch` V1: Light verdict wiring from CornerReader to the controller is untested. Pre-verified gap (route tests feed light = null). Moot (intent gap).
  - `medium` `patch` V2: Torch wiring in GuidedScanActivity is untested. Pre-verified gap. Moot (intent gap).
  - `medium` `patch` V3: Neither light compensation in the second read is pinned by a test. Pre-verified gap. Moot (intent gap).
  - `medium` `patch` V4: Legacy groups do not assert 0 confident wrong on kept faces. Pre-verified gap. Fix: add the safety assertion for every group and scenario. Moot (intent gap).
  - `medium` `patch` V5: Recorder has its own copy of the metering (same as B10). Same root cause as B10. Moot (intent gap).
  - `medium` `reject` V6: Gate keeps almost no side faces on legacy groups (same as B2). Same as B2: out of scope by PO decision A.
  - `medium` `patch` V7: meterCube outside the phase check (same as E7). Same root cause as E7. Moot (intent gap).
  - `low` `reject` V8: QuickScanReadout no longer used in production. Dead code with no user or developer harm named; cleanup only.
  - `low` `patch` V9: Test name claims a per-hold light-hold reset that it never exercises. Real; moot (intent gap).
  - `false` `reject` I1: Per-face correction only vetoes; it does not change the read "before classification". When both reads agree the colour is the same, so taking it from either read gives the same outcome; the gate is what the spec asked for.
  - `false` `reject` I2: "Frontal calibration on angled side faces" is covered by renaming the matching scenario. The spec's Design Notes define it: the recorder calibration is frontal, so the matching scenario is the frontal one.
  - `medium` `intent_gap` I3: The stla backlit hold 1 passes the light check (17.2 L*), a missed block the group-level assertion accepts. Real against AC2 ("backlit attempts always block"). Same root cause as B3: the colour-dependent signal and cut.
  - `false` `reject` I4: Torch glare is inferred from the torch state, not measured as coming from the torch. PO clarification (2) defines the torch-glare copy by torch state.
  - `medium` `patch` I5: Activity-level wiring for light, settle and torch is untested (same as V1 and V2). Same root cause as V1 and V2. Moot (intent gap).

### 2026-11-21 — Review pass (review loop 2)
- verdicts: 36 findings — high 0, medium 10, low 16, false 8, maybe-false 2
- layers: blind-hunter (B-), edge-case-hunter (E-), verification-gap (V-), intent-alignment (I-), all Claude (no codex-review.on). Patches were applied by re-engaging the implementation subagent, then `./gradlew spotlessCheck build` passed.
- findings:
  - `false` `reject` B-1: Bar test passes with zero scored groups. carried B1: PO decision A; missing groups are reported, and D4 keeps the story unmerged.
  - `medium` `reject` B-2: Safety on kept faces is near-vacuous because almost no side faces are kept on legacy captures; no before/after baseline. carried B2: coverage is out of scope by PO decision A, and the replay reports it.
  - `false` `reject` B-3: Uncalibrated hold can block solved cubes forever with no escalation. PO decision E accepts false holds without a calibration; Scan face by face stays visible.
  - `false` `reject` B-4: Light hold engages on a single failing frame. The settled UI behavior says the hold engages on the first detected failing frame.
  - `low` `reject` B-5: AE frames in flight before the region applied could read as CONVERGED. settleOutcome also requires the metering future, which completes after the 3A scan, so this mainly matters if the future completes early. Rare, and the fix (a SEARCHING-to-CONVERGED transition) adds state.
  - `low` `patch` B-6: meteringQuads never cleared despite its KDoc. Cleared on undetected frames and in stop().
  - `low` `patch` B-7: Verdict KDoc and sideDiff log misleading in calibrated mode. KDoc names the cut per mode; calibrated mode logs lightScore and lightMode only.
  - `maybe-false` `defer` B-8: Cuts fitted and validated on the same 16 Lenovo holds. Doc now marks both cuts provisional; settled by D1/D4 on new captures.
  - `low` `patch` B-9: Evidence doc misdescribes the 75th percentile. Now "7th in ascending L*, the 3rd lightest".
  - `low` `reject` B-10: No-saved normalization is colour-dependent and can demote correct stickers. That only lowers confidence (sends faces to the grid), never makes a confident wrong; coverage is measured in D4.
  - `medium` `patch` B-11: Schema-4 positive with no samples and no keypoints is accepted and skips calibration validation. decode now requires keypoints for a sample-less positive and validates any calibration; tests added.
  - `medium` `patch` B-12: Manual placement seeded from clamped detector points, so an untouched point saves the detector's guess as a label. initialKeypoints() always seeds from the guide geometry; detector points stay in their own diagnostics field.
  - `low` `reject` B-13: Recorder colour samples use the detector quads while the manual corners go in the same record. The operator confirms each face's colours on the confirm step; keypoint and colour fixtures are scored separately.
  - `low` `reject` B-14: "All held out" in evaluate_model.py is not enforced against a training run. This story does not retrain the model; nothing trains on these fixtures today.
  - `low` `patch` B-15: Torch state read twice by TalkBack. Dropped toggleableState; Role.Button with the state label as the spoken text.
  - `false` `reject` B-16: Coaching copy ends with a period; sides-dark never offers the torch. carried B15: the PO fixed the exact strings.
  - `low` `patch` B-17: Shared CornerHold depends on a QuickScanController constant. The cap now lives in public CornerSettle.CAP_MS, referenced by CornerHold, QuickScanController and CameraXFrameSource.
  - `medium` `patch` E-1: SettleTracker frame numbers survive a camera reopen, so every settle waits the cap. Verified (lastFrameNumber is a running max). reset() runs on bind and stop; test added.
  - `low` `patch` E-2: request() clears futureDone before bumping the generation (race with an old future). The generation is bumped first; test added.
  - `low` `reject` E-3: A late enableTorch listener after stop() marks the torch applied. Rare; the consequence is one skipped torch wait, and the fix needs session counters.
  - `medium` `patch` E-4: involvedFaces returns only kept faces, which the caller filters out, so the scan ends Done with no reprompt. Verified in QuickScanClassification.involvedFaces. Kept faces are dropped first, with a fallback to the ScanAssembler reprompt faces; test added.
  - `medium` `patch` E-5: A null-score verdict (unreadable face) counts as a pass and can clear the hold. Verified (SideLight returns the verdict unchanged when score is null). While held, a null score resets the pass window; test added.
  - `low` `patch` E-6: A frozen frame logs a stale light verdict. captureCorner returns whether onFrame ran; light is logged only then.
  - `low` `reject` E-7: A 10000th keypoint fixture breaks the 4-digit index regex. Unrealistic count for a manual recorder.
  - `low` `patch` E-8: QuickScanReadout KDoc says the replay measures the old path. KDoc points at QuickScanClassification; the object is kept for a controller test.
  - `medium` `patch` V-1: No-saved agreement-gate test passes with or without the gate. New QuickScanClassificationTest case: self read CONFIDENT Blue, normalized read CONFIDENT White, gated read UNCERTAIN, and F is in needGrid.
  - `medium` `patch` V-2: Activity torch and settle wiring never exercised. FakeCornerFrames gains a flash unit, torch/suspend logs and a settled() switch; new route test.
  - `medium` `patch` V-3: Recorder ModelCrop copies CornerModelTransform with no equivalence check. Equality asserted at 0/90/180/270.
  - `low` `reject` V-4: CornerLocatorTest no longer pins the R/L fallback. Superseded by PO decision B (the gate may send faces to the grid); the safety asserts remain.
  - `false` `reject` I-1: Backlit holds that pass (stka cool h2 calibrated, stla cool h1 uncalibrated) read as per-hold misses (R3b). PO decision F and the spec use the per-group rule (at least one hold per backlit group); D1 counts per attempt.
  - `maybe-false` `defer` I-2: Cut derived on recorded FaceSample stickers but applied to live FrameStats cells (R6). carried B5 (maybe-false); settled by logging live lightScore in D1 against the recorded values.
  - `medium` `reject` I-3: Bar enforced on no data; legacy coverage low (R2). carried B2/B1: PO decision A.
  - `false` `reject` I-4: Correction used as a veto (R4). carried I1.
  - `false` `reject` I-5: Frontal calibration scenario merged with matching. carried I2.
  - `false` `reject` I-6: Torch glare chosen by torch state. carried I4.
  - `low` `reject` I-7: Cubes pooled inside device x light (R7a). AC6 says per device and lighting condition; the cube coverage minimums are reported separately.

## Design Notes

- PO decisions A–D above supersede the earlier blocked run's stop conditions; the measurements are in the earlier Auto Run Result below.
- Calibration readings for AC5: "matching" = the group's own recorder calibration; that calibration is frontal (taken flat), so it also serves as "frontal calibration on angled side faces". The full bar applies to the matching scenario (the shipped configuration for a Learner with a saved calibration); every scenario, including no saved calibration, must have 0 confidently wrong stickers on kept faces.
- Safety gate idea for the saved path: corrected-against-saved read and self-calibration read must agree to be CONFIDENT. It keeps a wrong calibration from producing confident errors; if it costs too much correct-rate, measure alternatives in the replay, but never trade a confident wrong for coverage.
- Truth labels: 345 of 432 fixture stickers are recorder prefill the operator left unchanged; treat them as truth as 10.18 did.
- The d2 doc's best earlier candidate (per-face luminance gain to the saved centre) gave 3 Red↔Orange wrongs on backlit data; backlit groups are not scored now, but the front-lit groups must still reach 0 wrong.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:test --tests '*QuickScan*' --tests '*SideLight*' --tests '*SelfCalibration*' --tests '*GuidedScan*'` -- expected: pass, replay report printed
- `./gradlew :app:testDebugUnitTest --tests '*QuickScan*' --tests '*GuidedScan*' --tests '*ScanFrameSource*' --tests '*CornerFixture*' --tests '*ScanDiagnostics*' --tests '*Recorder*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: success (lint, Kover, boundary/manifest/denylist checks, debug and release APKs)
- `uv run --project tools/corner-model python -c "import ast,sys; ast.parse(open('tools/corner-model/evaluate_model.py').read())"` -- expected: parses (full evaluation is device-session D5)

## Auto Run Result

### Earlier run (2026-11-21, superseded by PO decisions A–D)

Status: blocked
Blocking condition: implementation verification failed — the enforced replay bar (AC6, QuickScanSideFaceReplayTest per device×light) cannot be met on the existing front-lit Lenovo captures in Fixtures/corner/ by classification alone without weakening it; the implementer stopped at the spec's stop condition before changing code.

Evidence (implementation subagent, production path replicated; baseline reproduces docs/evidence/10-18/d2-side-face-replay.md exactly). Side stickers correct/uncertain/wrong of 32, side faces to grid of 4; bar = 0 wrong, ≥ 29/32 correct, ≤ 1/4 to grid:

| group | no saved calibration (today) | matching calibration + per-face centre correction + agreement gate |
|---|---|---|
| stka cool-front | 24/7/1, 4/4 | 18/14/0, 4/4 |
| stka warm | 23/9/0, 4/4 | 21/11/0, 4/4 |
| stla cool-front | 27/5/0, 2/4 | 27/5/0, 2/4 |
| stla warm | 17/5/10 (1 R↔O), 3/4 | 14/14/4, 4/4 |

- Six correction variants (none, L gain, L shift, full Lab shift, L gain + chroma gain, L gain + ab shift), each with and without the agreement gate: none meets the bar on all four groups. Without the gate, confident wrongs incl. R↔O appear.
- stla warm cannot reach 90% with any classifier (oracle nearest-color max 28/32): four truth stickers measure as another color (e.g. "Yellow" at Lab 99,-4,11; "Green" at 89,1,2 and 62,42,68; "White" at 86,-14,80) — likely glare or misaligned sampling in the recording.
- The no-saved-calibration path (kept unchanged by the spec) already reads 10 stla warm side stickers confidently wrong, 3 on a face not sent to the grid, so "0 confidently wrong in every scenario" also fails there.
- With mismatched calibrations (other light / other cube) and the gate: up to 5 confident wrongs (stla warm), all on faces that go to the grid; whether a wrong sticker on a face sent to the grid counts as "uncertainty or fallback" is unspecified.
- Light check is feasible: 75th-percentile sticker L*, darker side vs top: front-lit holds 13.8–25.0, backlit holds 28.6–36.3 except stla backlit hold 1 at 17.2; a cut at 27 passes all 8 front-lit holds and trips ≥ 1 hold in every backlit group (7/8 backlit holds). Background lightness cannot be measured on the 200×200 fixture crops.

No code was changed. Only this spec was written (committed so the tree stays clean).

### Run 2 (2026-11-21): implemented, then blocked at review

Status: blocked
Blocking condition: intent gap. The light check's signal (75th-percentile L* of each face's own stickers, cut 27 L*, PO decision D) depends on sticker colour. Two consequences:
- In front light, a solved or nearly solved cube is held: a red side under a white top, or blue under yellow, is more than 27 L* darker. That breaks AC2's "front-lit never blocks".
- The recorded stla backlit hold 1 passes at 17.2 L*, a missed block against AC2's "backlit always blocks".

Fixing either needs a different signal and a new cut, which the intent does not settle.

Question for the PO: which light signal should the check use?
- (a) Keep the 75th-percentile signal and accept false blocks on solved cubes (D1 measures them).
- (b) Compare only stickers of the same colour on the top face and on a side face, with no hold when no colour is shared.
- (c) Compare each face against the saved calibration's lightness per colour when a calibration exists, and fall back to (a) or (b) without one.
For (b) and (c) the cut is re-derived from Fixtures/corner/.

Attempted change: [spec-10-19-attempt-1.patch](spec-10-19-attempt-1.patch) (commit 5088b8bb, reverted on the branch).
- Verification before review: `./gradlew spotlessCheck build` passed; QuickScanSideFaceReplayTest (2 tests) and SideLightTest (4 tests) ran, with 0 skipped.
- Bad-spec findings to fold in when the code is re-derived: B12/E5 (torch toggle and settle out of order), B13/E6 (stale AE state), B19/E9 (normalized centre source lookup), E12 (involvedFaces is not gated).
- Patch findings to carry: B8, B9, B10/V5, B11, B14, B17/E11, B18/E10, E1, E3, E7/V7, E8, V1, V2, V3, V4, V9.

### Run 3 (2026-11-21): implemented per decision E, blocked at verification

Status: blocked
Blocking condition: implementation verification failed. No cut on the calibrated signal from decision E separates the recorded captures, so the test "both light check modes trip every legacy backlit group and pass every legacy front-lit hold" fails, and `./gradlew spotlessCheck build` is red on that one test. The signal is the median, over a face's stickers, of (sticker L* − the saved calibration's L* for its nearest colour on a*b*), each group measured against its own recorder calibration.
- Worst front-lit hold: stla warm hold 1 at 24.2 (the light comes from the side: top −2.8, one side −27.0, the other +16.2).
- Highest backlit group values: stka cool peaks at 24.0, stkb cool at 23.3. A cut would have to be ≥ 24.2 and < 23.3 at once.
- The 25th, 75th and 90th percentiles of the shading, compared against the top face and on their own, do not separate the two sets either; the median against the top comes closest, 0.9 L* short.
- A provisional cut of 23.0 trips every backlit group, including stla cool hold 1 (27.1), which the uncalibrated mode misses, but it holds stla warm hold 1.

Attempt 2 (attempt 1 plus all Spec Change Log amendments and every triage `patch` row, with the provisional 23.0 cut) is saved as [spec-10-19-attempt-2.patch](spec-10-19-attempt-2.patch), and the working tree is reset. With only that one assertion switched off, the full `spotlessCheck build` passed.

Question for the PO:
- (1) Treat stla warm hold 1 (side-lit) as correctly held, and set the test expectation for it to "held", keeping the cut at about 23.
- (2) Accept missed blocks on stka cool and stkb cool, with the cut at 24.2 or more.
- (3) Define a different calibrated signal.

### Run 4 (2026-11-21): done

**Summary:** Story 10.19 is implemented from attempt 2 plus decision F, the search-derived calibrated light signal. The work covers:
- **Light check:** two modes (calibrated: the top's relative brightness ÷ the mean of the two sides' > 1.34; uncalibrated: 75th-percentile L* difference > 27); a light hold with the PO-settled coaching and timings; sides-dark and torch-glare coaching.
- **Metering:** cube-region metering (AWB on Hold 1 only) with a centre fallback, and a settle signal from the future plus post-request AE state, capped at 1500 ms.
- **Torch:** default-on toggle ("Torch on"/"Torch off") on flash devices only, off outside the corner view and in the background.
- **Classification:** Quick-Scan-only agreement gate on both calibration paths, with a per-face centre correction.
- **Replay:** scores only 10.19-pipeline groups (group.json); legacy groups are reported.
- **Recorder, evaluator and evidence:** fixture schema 4 with keypoint and negative fixtures and the recorder corner-mode features; per-device acceptance in evaluate_model.py; diagnostics for D1–D3; evidence doc docs/evidence/10-19/light-check-and-replay.md.

**Files changed:** the 35 files of attempt 2 (domain recognition: SideLight, CornerHold, QuickScan, QuickScanClassification, LiveFixture, GuidedScan; camera adapter: ScanFrameSource, CornerLocator, GuidedScanActivity, GuidedScanFlow, GuidedScanScreen, CornerModelTransform; debug recorder; ScanDiagnostics debug and release; en.json; tests; tools/corner-model; evidence doc).

**Review (loop 2):** 36 findings.
- 18 patches applied: B-6, B-7, B-9, B-11, B-12, B-15, B-17, E-1, E-2, E-4, E-5, E-6, E-8, V-1, V-2, V-3, plus the two doc fixes in the B-9 row.
- 2 deferred: B-8 and I-2 (both cuts provisional; live vs recorded L*).
- 16 rejected, each with its reason in the triage log.

**Follow-up review recommended: true.** This pass patched 0 high and 9 medium entries (B-11, B-12, E-1, E-4, E-5, V-1, V-2, V-3, plus E-2 and B-6 at low). The specific unverified risk is the patched settle/torch reset logic in SettleTracker and GuidedScanActivity, which is only exercised in Robolectric and JVM tests, not on a camera.

**Verification:** `./gradlew spotlessCheck build` passed (BUILD SUCCESSFUL, 54 tasks executed after the patches). The replay and SideLight suites ran with 0 skipped.

**Residual risks:**
- Nothing in CI scores the side-face bar until 10.19-pipeline captures exist (D4). The story must stay unmerged until D1–D6 pass.
- Without a calibration, solved cubes are held (accepted under PO decision E).
- One backlit hold per mode passes on the legacy data (stka cool hold 2 calibrated, stla cool hold 1 uncalibrated).
