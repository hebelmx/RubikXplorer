---
title: 'Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off'
type: 'feature'
created: '2026-11-25'
status: 'draft'
baseline_revision: '52b66aefa63ab02f8ae6b08cc6a6e5d769756be8'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A Color Calibration saved from a Quick Scan review restarts Quick Scan with an empty map, although the continuous scan calibrates itself and gains nothing from the saved calibration.
    evidence: |-
      GuidedScanViewModel.saveCalibration applies the existing Scan Review rule (a calibration saved from a review restarts the scan) to Quick Scan mode: it resets the engine and returns to the Continuous phase. The offer only appears on a review reached through the Guided hand-over (CalibrationOffer.offered over the Guided samples). The rule predates this story; whether the Learner should instead return to the same review re-read with the new centres is a product decision.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt (GuidedScanViewModel.saveCalibration)
    severity: medium
  - summary: >-
      A sticker-centre detector disabled by a failed model load or inference still reads as "no cube in view", so Quick Scan shows "Bring the cube into view." instead of an unavailable state.
    evidence: |-
      Open tracker item epic-10-deferred-10.31-detector-unavailable-looks-empty in sprint-status.yaml (from Story 10.31): StickerCenterDetector has no unavailable signal. Fixing it adds a state to the seam and a Learner-facing behaviour that Story 10.32's acceptance criteria do not define. "Scan face by face" stays one tap away in that state.
    location: >-
      app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt
    severity: medium
  - summary: >-
      On a landscape phone (about 640×360 dp) at 200% font, the Quick Scan panel does not scroll, so *Enter colors instead* may be pushed below the screen edge.
    evidence: |-
      Reported by the edge-case layer of the follow-up review and not verified: ContinuousQuickScanLargeFontTest covers 640×360 landscape only at font scale 1.0, and 200% font only in portrait (411 dp, 600 dp). What would settle it: run the test's fits(landscape = true) at qualifiers w640dp-h360dp-land with fontScale = 2.0f. The intent's fit rule names portrait phones, 200% font at 600 dp width and landscape tablets; a landscape phone at 200% font is not named.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt:96-141
    severity: medium (unverified)
  - summary: >-
      The continuous Quick Scan never meters focus or exposure on the cube; the cube-outline metering the two-corner Quick Scan used was removed with it, so the camera's default metering applies.
    evidence: |-
      Reported by the edge-case layer of the follow-up review (deletion check, low confidence) and not verified: the continuous branch of CameraXFrameSource.read calls no meter(), and GuidedScanActivity meters only on Guided, calibration and Case-capture effects. Whether the default continuous AF/AE reads the cube well enough is a device observation. What would settle it: the pending hands-on pass on the Lenovo TB128XU and Galaxy S24 FE (cube on a dark and on a bright background, torch on and off).
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt (continuous branch of read)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Story 10.32 (Epic 10, "Scan my cube"). The continuous facelet mapper (Story 10.30) and the LiteRT sticker-centre detector (Story 10.31) exist, but Quick Scan on the Solve tab still opens the parked 2-corner screen, so a Learner cannot turn the cube in front of the camera and watch the 6-face map fill in.

**Approach:** Replace the 2-corner Quick Scan phase of `GuidedScanActivity` with a `ContinuousQuickScanScreen` driven by the continuous pipeline through an optional base-side port, reuse the existing Guided Scan, Scan Review, per-face retake and `ScanOutcome` paths for every exit, and remove the Quick-Scan-only 2-corner code within the scope the product owner (PO) fixed.

Refs (binding, copied from the story): FR-22, FR-23, FR-25 · NFR-7, NFR-10, NFR-11 · AD-3, AD-9, AD-13, AD-17, AD-18 · UX-DR15, UX-DR16, UX-DR19 · Stories 10.3, 10.15, 10.16, 10.17. No deviation from AD-3, AD-9, AD-13, AD-17 or AD-18 is planned or known; if implementation finds one, it is a blocker to raise, never a residual risk.

## Boundaries & Constraints

**Always:**
- AD-3: the scan flow only returns a `ScanOutcome`; the Solve tab's caller dispatches it. No ViewModel or composable mutates session state.
- AD-9 / AD-18: all camera code stays in `app/src/camera/`. Base camera code (`app/src/camera/kotlin`) never references `dev.cubentia.app.camera.continuous.*`; it reaches the pipeline only through an optional Hilt port (`@BindsOptionalOf`), so a variant with `QUICK_SCAN` OFF and another camera flag on still compiles. `QUICK_SCAN` stays `BETA` in debug and `OFF` in release; with it OFF no Quick Scan UI shows.
- AD-13: frames stay in memory on the analyzer thread; nothing is stored, logged or sent. No new permission.
- AD-17: stickers in the net use the shared `StickerAppearance` glyphs; the screen fits a phone in portrait without scrolling and at 200% font on 600 dp width; landscape tablets put the preview left and the panel right.
- All Learner text comes from `en.json` through `ContentStrings.resolve(Text(key, args))`.
- The mapper and pipeline are single-threaded: every `processFrame`, `reset` and `export` call runs on the analyzer executor.
- Never guess: only stickers the mapper committed are shown, kept or handed on; blanks stay blank.
- **Settled UI behaviour (PO clarifications Q1–Q7 plus items already fixed by the story and UX spec):**
  - *Count:* "Sides mapped: N/6". N is the snapshot's `mappedFacesCount` once `globalAnchored` is true (6 on `Complete`), and 0 before anchoring (Q5 i). Every "mapped faces" rule below uses this displayed N.
  - *Cue line:* exactly the one cue the mapper emits (`ContinuousScanCue.toText()`, the ten shipped `msg.quick-scan.continuous.*` keys); no cue copy or priority is changed.
  - *Net (Q4 i–iii):* compact URFDLB net; a committed sticker appears at once with no animation; an unread sticker is the blank dark well the Scan Review uses, without the "?" glyph; conflicted stickers get the shared `sticker-highlight` outline (never red), on the net only.
  - *Face outlines (Q4 iv):* one `sticker-highlight` stroke per tracked face, no cell grid; each corner eases half the distance to the newest detection per frame; an outline is removed when its face has not been tracked for 3 frames.
  - *Haptics (Q4 v):* a light tick each time N goes up; a confirm pulse when all 54 stickers are verified; a distinct (reject-style) pulse when the cue becomes a conflict face or a layer twist. Each fires once per transition, and only when the app's haptics setting is on (`LocalHaptics`). No sound, no first-use hint.
  - *TalkBack (Q6):* one polite live region for the cue line, re-announced only when the cue text changes; "Sides mapped: N/6" announced politely each time N increases and never when it falls; nothing assertive. The net is not read sticker by sticker.
  - *Completion (Q1 b):* on the first `Complete` snapshot the analysis stops, the completed net and "All 54 stickers verified." stay for 1000 ms (controls still work), then Scan Review opens automatically. *Reset* during that second cancels the hand-off.
  - *Reset (Q2):* clears the map at once with no confirmation, also with N ≥ 1; the torch stays as the Learner set it; an open twist latch is cleared.
  - *Layer twist (Q3 a):* the net is kept as it was, the twist cue shows, and the screen waits for *Reset*, drawn as the emphasised (filled) action while the twist is latched. *Scan face by face* and *Enter colors instead* still carry the faces confirmed before the twist.
  - *Torch:* shown only when the camera reports a flash unit; OFF when the screen opens; turned off on hand-over to Guided or Review, on leaving and in the background; it stays off on return until the Learner turns it on again.
  - *Close / system Back:* leaves at once (`ScanOutcome.Cancelled`) at N = 0; at N ≥ 1 shows the existing dialog (`msg.scan.discard-title`, `btn.scan.discard`, `btn.scan.cancel`).
  - *Enter colors instead:* never asks; at N ≥ 1 returns `FellBackToEditor(handoff.toFallbackStickers())`; at N = 0 returns `FellBackToEditor(null)` (blank Editor with `msg.solve.scan-fell-back`).
  - *Scan face by face (Q5):* always visible and enabled. Guided asks for (1) every face absent from the exported hand-off — all six before anchoring; and (2) when all six faces are mapped but the scan is not complete, only the face(s) holding conflicted or still-unread stickers (when none is unread, the face the conflict cue names). Kept faces are never reclassified. Back from that Guided viewfinder returns to Quick Scan with the map intact (faces captured in Guided are dropped, as in Story 10.17); *Close* there asks "Discard this scan?".
  - *Review:* the existing Scan Review with per-face retake, suspect ranking and Paint mode; its existing close, accept and calibration-offer rules are unchanged.
  - *BETA label:* only on the Solve tab's Quick Scan button, none inside the screen. *Rotation:* no orientation lock; the map survives a rotation.
- 2-corner removal scope (Q7): delete `QuickScan.kt` and `QuickScanDiagnosis.kt`; move `QuickScanPlan`, `CenterCheck` and whatever the kept code needs from them (at least `GridReason`) to neutral files in the same package; keep `CornerHold.kt` and `SideLight.kt` trimmed to what Case capture and the fixture recorder use; KEEP `QuickScanClassification.kt`, `exportKotlinStickerReads` and `evaluateQuickScan` with their tests and the 2-corner code they need; keep `CornerLocator` / `CornerReader` / `CornerGuide`.
- Legacy consumers (handoff note, SCP 2026-11-25 continuous-fixture-recorder): the story's cleanup guard names only Case recognition and `FixtureRecorderActivity`, so also treat the evaluation source set, `RecorderBurst` and both `ScanDiagnostics` as consumers. Q7 already keeps the evaluation tools and the recorder's `SideLight` use. For any legacy replay this story retires (`QuickScanBurstReplayTest`, `RecordedBursts` and the stale burst test input in `core/domain/build.gradle.kts`), record the last revision and the command that runs it in `docs/evidence/10-32/camera-size.md`, so the historical result stays reproducible.

**Never:**
- No edit under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/skills/`; no change to `FeatureFlagLevels` release levels; no new module, permission, SDK or model asset.
- No change to `ContinuousFaceletMapper`, `FaceLatticeFitter` or the detector's behaviour (PC-parity code from Stories 10.30 / 10.31); wire them, do not retune them.
- No debug-only launcher as the acceptance path; no Learner text literal in Kotlin; no numeric confidence on screen.
- No change to Case capture or fixture recorder behaviour, and no change to the recorded fixture schema.
- Do not claim the human gate (hands-on pass on Lenovo TB128XU and Galaxy S24 FE) or Story 10.33's device matrix; record them as pending.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open | Solve tab → Quick Scan, flag BETA/GA | Preview, empty net, "Sides mapped: 0/6", cue "Bring the cube into view.", torch off | Permission and no-camera paths as the Guided Scan today |
| Not anchored | Snapshot `globalAnchored=false`, mapper count 1 | Count shows 0/6; Close leaves at once; Enter colors opens the blank Editor | — |
| Mapping | Anchored snapshots, count 2 → 3 | Net fills; count 3/6; one tick; TalkBack says "Sides mapped: 3/6" once | — |
| Count falls | Count 3 → 2 | Count shows 2/6; no haptic, no announcement | — |
| Conflict | Cue becomes `ResolveConflict` | Cue line names the face; conflicted cells outlined on the net; one distinct pulse | — |
| Twist | `TwistDetected` snapshot | Net kept; twist cue; *Reset* emphasised; one distinct pulse; no outlines | Only *Reset* resumes scanning |
| Reset | Tap *Reset*, N = 4, torch on | Net empty, 0/6, cue from next frame, torch still on, no dialog | — |
| Complete | First `Complete` snapshot | Confirm pulse; cue "All 54 stickers verified."; after 1000 ms Review opens with 54 stickers; torch off | `export()` not `Complete` at hand-off → stay on the screen and keep scanning |
| Accept in Review | Valid review, *Accept* | `ScanOutcome.Accepted(state)` in `Orientation.HOME` → Solution Playback | Existing review rules |
| Face by face, partial | Anchored, U F R mapped | Guided asks D, L, B only; mini net shows U F R as captured; finishing opens Review via `ScanAssembler.assembleHandover` | `ScanResult.Partial` → Review with blanks (existing rule) |
| Face by face, unanchored | `globalAnchored=false` | Guided asks all six | — |
| Face by face, 6/6 conflict | 6 mapped, conflicted slots on F | Guided asks F only; other five kept | — |
| Back from Guided | Hand-over viewfinder, Back | Quick Scan again with the same net and count; torch off | — |
| Close with faces | N ≥ 1, Close or Back | "Discard this scan?"; Discard → `Cancelled`; Cancel → scan continues | — |
| Background | App stopped then resumed | Torch off and stays off; map intact | — |
| No flash | `hasFlashUnit() == false` | No torch control or copy | — |
| No pipeline binding | Quick intent, engine factory absent | Existing `Unavailable` phase (unreachable in composed variants) | — |
| Flag OFF | `QUICK_SCAN = OFF` | No Quick Scan button, no Quick Scan screen reachable | — |

</intent-contract>

## Code Map

Abbreviations: `C/` = `app/src/camera/kotlin/dev/cubentia/app/camera/`, `K/` = `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/`, `D/` = `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/`, `T/` = `app/src/testDebug/kotlin/dev/cubentia/app/`.

**Entry and result (read-only unless noted)**
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` L134-155 -- `guidedScanAction(quick = true, seedsEditor = true)`, gated on `FeatureFlag.QUICK_SCAN && scanLauncher.isPresent`.
- `app/src/main/kotlin/dev/cubentia/app/scan/{ScanFlowContract,ScanLauncher,ScanOutcome}.kt` -- `Accepted(state, runScanColors)`, `Cancelled`, `FellBackToEditor(stickers)`; encode/decode unchanged.
- `C/CameraScanLauncher.kt` -- `quickScanIntent` adds `GuidedScanActivity.EXTRA_QUICK_SCAN`; keep.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` L73-91, `ui/shell/AppShell.kt` L1125-1126 -- button `solve:quickScan` and BETA label; unchanged.

**Continuous pipeline (Stories 10.30 / 10.31, consume only)**
- `K/ContinuousScanPipeline.kt` -- `processFrame(frameIndex, frame: YuvFrame, rotationDegrees, viewWidth, viewHeight): ContinuousPipelineFrame` (`snapshot`, `viewOverlays` as [0..1] PreviewView fractions), `reset()`, `exportHandoff()`, `export()`. Internal to the continuous source set.
- `K/StickerCenterDetectorModule.kt`, `C/StickerCenterDetector.kt`, `C/CameraModule.kt` L20-40 -- the optional-binding pattern to copy (`@BindsOptionalOf` in its own module so `@UninstallModules(CameraModule::class)` tests still resolve).
- `D/continuous/ContinuousTypes.kt` -- `ContinuousScanCue` + `toText()` L103-157; `ContinuousScanSnapshot` (`Scanning` / `TwistDetected` / `Complete(state)`) L160-215 with `net`, `conflictedSlots`, `globalAnchored`, `mappedFacesCount`, `cue`; `ContinuousScanHandoff` L246 (`faces`, `calibration`, `globalAnchored`, `toFallbackStickers()`); `ContinuousScanExport` L293.
- `D/continuous/ContinuousFaceletMapper.kt` -- `reset` L191, twist latch L214, `exportHandoff` L324 (unanchored → no usable faces; anchored → HOME faces, unread or conflicted cells null), `export` L392, `computeCue` L1153, `buildSnapshot` L1213. "Mapped" means the centre is confirmed; a mapped face may still hold null stickers.
- `D/ScanAssembler.kt` L713 `assembleHandover(handoff, added, saved): ScanResult`; `D/ScanReview.kt` `from(ScanResult, centers)` L85, `from(handoff)` L118, `retaken(before, retaken, samples, saved)` L189 (falls back to `before.evidence` centres when fewer than 6 samples); `D/SuspectRanking.kt` L43.
- `D/GuidedScan.kt` -- `GuidedScanPlan.steps`, `GuidedScanController(plan, allowAdditionalReprompts, reprompt, known, initialDelayMs)` L351.

**Camera host (edit)**
- `C/ScanFrameSource.kt` -- `ScanFrame` L60 (no `YuvFrame` today), interface L67, `read(image)` L540-585 (corner branch shows how a `YuvFrame` is built and quads mapped), `applyTorch` L270-296 (lit only in corner or calibration mode), `stop()` L227.
- `C/GuidedScanActivity.kt` -- Quick-only parts to replace: L128, L136, L150, L175-176, L221, L226-234, L257, L268-269, L373-377, L440-448, L467-469, L487-502, L511, L540-553, L573-581, L642-674, L703-768, L788-795, L816, L913-916, L932-935, L966-968, L991-992, L1045-1051, L1145-1162. Review rendering L304-365 (`onRetake` allowed when `isGuidedRun || quickScanMode`, L353); `startRetake` L840, `handleRetake` L865; torch effect L294; `onStop` torch suspend L407-410; `analyzerExecutor` L907.
- `C/GuidedScanFlow.kt` -- Quick-only: L188-200, L265-277, L285-293, L319, L339-345, L351-355, L371, L391-392; keep `retakePlan` L395 / `retakeRun` L401.
- `C/GuidedScanScreen.kt` -- phases L100-174 (`CornerViewfinder`, `CornerPrompt`, `CornerOutline` composable L825 and `captureCase` are shared with Case capture: keep them, drop only the `faceByFace` / `offerCalibration` / `fromQuickScan` Quick-only fields and L269-304, L355-370 Quick arms, `FaceByFaceButton` L994-1006, `CalibrationOfferLine` L1008-1050); `Viewfinder.kept: List<FaceSample>` L168 and `handOver` L172 (the hand-over mini net needs kept colours from the hand-off, which carries no `FaceSample`); `TorchButton` (tag `quickScan:torch`, also used by the recorder); `DiscardDialog` L1066 with `DiscardKind.Scan`; `EnterColorsButton` (tag `scanEnterColors`); live-region pattern L293; haptics pattern L663-669.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` L218, L303 and `render/StickerGlyph.kt` L28 -- blank-well and `sticker-highlight` drawing to reuse for the net.
- `app/src/{debug,release}/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- signatures use `QuickScanController`, `CornerCaptureReport`, `CornerHoldEvent`, `QuickScanDiagnosis.*`; trim both in step.

**Removal inventory (Q7)**
- `D/QuickScan.kt` -- delete; move `QuickScanPlan` (L7; used by recorder, `LiveFixture.kt` L485, tests, two device tests) and `CenterCheck` (L873; used by `CornerHold.kt` L248/L574). `QuickScanController.MIN_CONFIDENT` is read by `QuickScanGateTuningTest` L450 and `QuickScanSideFaceReplayTest` L869/970/974.
- `D/QuickScanDiagnosis.kt` -- delete; `QuickScanClassification.kt` L195, L227-243 needs `GridReason`; its `diagnose()` L257-298 is only called by `QuickScan.kt`.
- `D/CornerHold.kt` -- keep what `D/CaseCapture.kt` L37, L56-58 uses (`CornerHold()`, `onFrame(..., clearWhenUnlocated)`, `Decision`, `changed`, `values`, `reset`, `latest`, `coaching`) plus `CornerSettle` L701 (recorder, `ScanFrameSource` L644); Quick-only members and `CornerSteadyRestart`, `OutlinePoint`, `CornerHoldEvent*`, `CornerHoldMark` go when unused.
- `D/SideLight.kt` -- keep `check`, `Verdict`, `level` and what `RecorderBurst.kt` L79-90, `RecorderRules.kt` L655-658/L722 and `QuickScanClassification.kt` L606-608 use.
- Tests: delete `core/domain/src/test/.../recognition/{QuickScanControllerTest,QuickScanBurstReplayTest,RecordedBursts}.kt`, `T/QuickScanRouteTest.kt`, `T/camera/{QuickScanScreenTest,QuickScanLargeFontTest}.kt`; trim `T/camera/{GuidedScanFlowTest,CornerLocatorTest}.kt`, `T/ScanDiagnosticsRunLineTest.kt`, `QuickScanGateTuningTest`, `QuickScanSideFaceReplayTest`, `SideLightTest`. `core/domain/build.gradle.kts` L36-40 test input for the burst replay becomes stale; L46 and L105 register the two kept tools.
- `core/domain/src/main/resources/content/strings/en.json` -- dead after removal: `msg.quick-scan.hold-first`, `msg.quick-scan.hold-second`, `msg.quick-scan.counter` (verify no other user first). Reuse `btn.quick-scan.face-by-face`, `btn.quick-scan.torch-on/off`, `btn.scan.enter-colors-instead`, `btn.scan.close`. Missing: a "Sides mapped" key and a *Reset* key.
- Gates: Kover 80% line minimum on `:core:domain` (`build-logic/.../JvmCoreConventionPlugin.kt` L33-41) -- code left in trimmed files must stay covered; `:app:checkCameraSize` counts `dev/cubentia/app/camera/**` debug class bytes three times; baseline `docs/evidence/10-31/litert-v2-int8-benchmark.md` L13 (28,282,576 B projected, classes 1,060,442 B).

**Test patterns to copy**
- `T/camera/QuickScanScreenTest.kt` (Robolectric Compose, `w360dp-h640dp-port`, `ContentStrings` from shipped content), `T/camera/QuickScanLargeFontTest.kt` (`w411dp-h891dp-port` and `w600dp-h960dp-port` at `fontScale = 2.0f`, `assertFits` L110), `T/camera/GuidedScanMediumScreenTest.kt` (landscape qualifiers), `T/QuickScanRouteTest.kt` (Hilt route from `MainActivity`, fake `ScanFrameSource` via `@BindValue`, `feed(frame)` L1014-1090; hand-over and Back L440-598).

## Tasks & Acceptance

**Execution:**
- [x] `C/ContinuousScanEngine.kt` (new), `C/CameraModule.kt`, `K/ContinuousScanEngineModule.kt` (new) -- add a base-side port (`processFrame` returning the snapshot plus view-fraction overlay quads, `reset`, `export`) and factory with an `@BindsOptionalOf` module; bind an implementation wrapping `ContinuousScanPipeline` in the continuous source set -- AD-9 / AD-18 split.
- [x] `C/ScanFrameSource.kt` -- add a continuous mode that hands each frame's `YuvFrame`, rotation and view size to the engine on the analyzer thread and delivers the result on `ScanFrame`; let the torch light in this mode; keep no frame after the call -- gives the screen snapshots without exposing pixels (AD-13).
- [x] `C/ContinuousQuickScanFlow.kt` (new) -- pure, JVM-testable screen logic: displayed N, cue text, net cells and conflicted cells, outline smoothing and 3-frame expiry, one-shot haptic and announcement events, the 1000 ms completion hold, the Guided face set for *Scan face by face*, fallback stickers, close-or-confirm -- keeps every settled rule testable off-device.
- [x] `C/ContinuousQuickScanScreen.kt` (new) -- the composable: preview with outlines, net, count, cue live region, *Reset*, *Scan face by face*, *Enter colors instead*, torch, *Close*; compact and medium/expanded layouts -- AC 1, AC 5.
- [x] `C/GuidedScanScreen.kt`, `C/GuidedScanFlow.kt`, `C/GuidedScanActivity.kt` -- add the continuous phase, route `EXTRA_QUICK_SCAN` to it, wire Reset / hand-over / Back / Close / Enter colors / completion → Review / retake, and remove the 2-corner Quick Scan branches while keeping Case capture intact -- AC 2, AC 3, AC 4.
- [x] `core/domain/src/main/resources/content/strings/en.json` -- add the "Sides mapped: {n}/6" and *Reset* strings; remove strings left without a user -- AC 5 copy rule.
- [x] `D/QuickScan.kt`, `D/QuickScanDiagnosis.kt` (delete), neutral file(s) in `D/` (new), `D/CornerHold.kt`, `D/SideLight.kt`, `D/QuickScanClassification.kt`, `app/src/{debug,release}/kotlin/dev/cubentia/app/ScanDiagnostics.kt`, `core/domain/build.gradle.kts` -- apply the Q7 removal scope from the inventory above -- AC 4.
- [x] Tests listed in the removal inventory -- delete or trim so no test references removed code and `:core:domain` stays above the Kover gate -- AC 4.
- [x] `T/camera/ContinuousQuickScanFlowTest.kt`, `T/camera/ContinuousQuickScanScreenTest.kt`, `T/camera/ContinuousQuickScanLargeFontTest.kt`, `T/ContinuousQuickScanRouteTest.kt` (new), `T/camera/{CameraXFrameSourceTest,CornerDetectorBindingTest}.kt` -- cover every I/O matrix row: flow rules, UI and semantics (live regions, announce-on-increase only), layout at the three size/font configurations and landscape, the route from the Solve tab through each exit, the optional binding, and flag OFF -- AC 5.
- [x] `docs/evidence/10-32/camera-size.md` (new), `docs/ARCHITECTURE.md` if it describes the 2-corner Quick Scan -- record `:app:checkCameraSize` before/after class bytes and the pending human gate -- AC 4.

**Acceptance Criteria:**
- Given `QUICK_SCAN` is BETA or GA, when the Learner opens Quick Scan from the Solve tab, then `ContinuousQuickScanScreen` shows the live preview with smoothed face outlines mapped correctly in portrait and landscape, the growing URFDLB net with `StickerAppearance` glyphs, "Sides mapped: N/6", and one cue line from `en.json` for every mapper cue.
- Given a scan in progress, when the Learner uses *Reset*, *Scan face by face*, *Enter colors instead*, *Torch*, or *Close* / Back, then each behaves as settled under Boundaries, and no frame is persisted or sent.
- Given all 54 stickers are verified, when the mapper completes, then after the 1000 ms hold Scan Review opens with per-face retake, suspect ranking and Paint mode, and *Accept* returns `ScanOutcome.Accepted` to the Solve tab (the Editor fallback returns `FellBackToEditor`).
- Given the Q7 scope, when the build runs, then `QuickScan.kt` and `QuickScanDiagnosis.kt` are gone, Case capture, the fixture recorder, `exportKotlinStickerReads` and `evaluateQuickScan` still build and pass their tests, and `:app:checkCameraSize` passes with camera class bytes no higher than the 10.31 baseline unless the evidence file explains the difference.
- Given the new Robolectric tests, when `./gradlew spotlessCheck build` runs, then it passes, `checkLearnerText` passes, the screen fits without scrolling at `w360dp-h640dp`, `w411dp-h891dp` at 200% and `w600dp-h960dp` at 200%, TalkBack semantics match Q6, and with `QUICK_SCAN = OFF` no Quick Scan UI is reachable.

## Spec Change Log

- 2026-11-25: added the legacy-consumer handoff note from SCP 2026-11-25 continuous-fixture-recorder (Boundaries, after the Q7 scope). No change to acceptance or Q7.

## Review Triage Log

### 2026-11-25 — Review pass
- verdicts: 55 findings — high 0, medium 20, low 25, false 9, maybe-false 1
- layers: the three Codex layers fell back to Claude subagents (Codex out of credits); blind-hunter, edge-case-hunter, verification-gap and intent-alignment all reported.
- findings:
  - `[low]` `[reject]` (intent-alignment) Tests stop at the engine port; no test runs the real detector → mapper chain from a frame — true by design: Stories 10.30/10.31 own that chain (device parity test), and this story's device surface is its human gate, recorded as pending. No defect at a cited location.
  - `[low]` `[reject]` (intent-alignment) Outline-to-preview registration in portrait/landscape is only tested as layout plus pure easing — the view-fraction mapping is Story 10.31's tested transform; on-cube registration is the pending hands-on gate.
  - `[medium]` `[patch]` (intent-alignment) Haptics, torch gate and net visuals asserted one level below the Learner — haptic delivery moved into `ContinuousQuickScanHaptics` with a fake-`HapticFeedback` test; torch gate extracted to `CameraXFrameSource.torchLit` and unit-tested.
  - `[medium]` `[patch]` (intent-alignment) AC 4 "confirms reclaimed class-byte headroom": camera class bytes rose — remaining dead 2-corner code removed and the gate re-measured (1,151,016 B, still +90,574 B over the 10.31 baseline because the new screen is counted and most removed code lived in `:core:domain`); evidence file reworded to say so plainly; minified 0.11.0 profile re-measured (26,261,058 B). Kept as a residual risk in the Auto Run Result.
  - `[low]` `[reject]` (intent-alignment) At 6/6 the spec also retakes faces with unread, non-conflicted stickers — Q5 (ii)(a) applied to the same state (6/6, not complete); the PO rejected "Review with blanks" and "disable" for it. Recorded in Design Notes and flagged in the run summary.
  - `[low]` `[reject]` (intent-alignment) *Scan face by face* opens Review directly when no face needs a retake — only reachable while the snapshot is already Complete (the 1 s hold) or under a twist with six full faces; Review is where the map is validated before *Accept*.
  - `[medium]` `[patch]` (intent-alignment) Close in the hand-over always asked "Discard this scan?", also with no face kept or captured — now asks only when the hand-off holds a face or a Guided face was captured; two route tests added.
  - `[false]` `[reject]` (intent-alignment) Controls live during the completion hold, Reset cancels — this is the text of the PO's chosen option Q1 (b) ("analysis stopped, controls still working").
  - `[false]` `[reject]` (intent-alignment) Torch stays off after returning from the hand-over — the story turns it off on hand-over; nothing turns it back on, so no choice was made.
  - `[medium]` `[defer]` (intent-alignment) A calibration saved from a Quick Scan review restarts Quick Scan with an empty map — the existing Scan Review rule (a calibration saved from a review restarts the scan), applied unchanged; see deferred item 1.
  - `[low]` `[patch]` (intent-alignment) `ScanAssembler.assembleHandover(preserveUnread)` tested only from `:app` — `:core:domain` test added in `ContinuousHandoffTest`.
  - `[medium]` `[patch]` (intent-alignment) Quick Scan run diagnostics removed with no replacement — `ScanDiagnostics.onQuickScanRun` restored (debug only), logged once from `finishWith` in the format `evaluateQuickScan` parses.
  - `[false]` `[reject]` (intent-alignment) `CornerPrompt` fallback to `msg.recognize.hold-*` may change Case capture or the recorder — its only callers are the two Case-capture sites in `GuidedScanScreen.kt`, which always pass the case prompt; the recorder does not call it.
  - `[low]` `[patch]` (intent-alignment) `docs/ARCHITECTURE.md` untouched — one sentence on the `ContinuousScanEngineFactory` port added.
  - `[medium]` `[patch]` (verification-gap) Rewritten `CornerHold` coaching and lost-cube paths that Case capture uses had no test — four baseline-derived cases added to `CaseCaptureControllerTest`; the baseline Case path was compared line by line and no behavioural difference found.
  - `[medium]` `[patch]` (verification-gap) *Enter colors instead* from the hand-over viewfinder not exercised — route tests added with zero and with one Guided capture.
  - `[medium]` `[patch]` (verification-gap) Torch gate for continuous mode in `CameraXFrameSource` unverified — filed as defer; patched instead because extracting the predicate (`torchLit`) was trivial; tested in `ScanFrameSourcePolicyTest`.
  - `[medium]` `[patch]` (verification-gap) Rotation test pinned neither the torch choice nor the engine instance — new route test: recreate with the torch on, same engine, factory called once.
  - `[medium]` `[patch]` (verification-gap) Haptic delivery and the haptics-setting gate not observed — covered by the new haptics composable test (once per transition, nothing with the setting off).
  - `[medium]` `[patch]` (verification-gap) Saving a calibration from a Quick Scan review not exercised — route test continued through save: Continuous, 0/6, engine reset, torch off, frames analysed again.
  - `[low]` `[reject]` (verification-gap, other) `CenterCheck` moved but unused — the PO's Q7 names it explicitly ("moving QuickScanPlan and CenterCheck to a neutral file"); kept as instructed.
  - `[false]` `[reject]` (verification-gap, other) `CornerPrompt` fallback / `msg.quick-scan.counter` — the note itself confirms the key is still used and was correctly kept; no bad outcome.
  - `[low]` `[patch]` (verification-gap, other) Stale 2-corner wording in kept code and tests — comments corrected.
  - `[medium]` `[patch]` (blind-hunter) Hand-over Guided controller lost `known`, `allowAdditionalReprompts = false` and the 2500 ms initial delay — `GuidedScanController.handOver(plan, kept)` added in the domain (kept samples via `ScanAssembler.keptSamples`); `GuidedScanHandOverTest` added.
  - `[low]` `[patch]` (blind-hunter) Reset race between the busy check and the revision read — `analyze()` reads the revision first.
  - `[low]` `[patch]` (blind-hunter) Export and UI state can disagree by a frame — `fallback()` and `keptForGuided()` decide from the export.
  - `[medium]` `[patch]` (blind-hunter) Story 10.31 tracker items addressed to this story untouched — debug-only main-thread check added to `LiteRtStickerCenterDetector.detect()`; the "detector unavailable looks empty" item stays open (deferred item 2); the frame-edge item is a device observation for the pending human gate.
  - `[medium]` `[patch]` (blind-hunter) No diagnostics for the new flow — same fix as the run-line row above.
  - `[low]` `[patch]` (blind-hunter) Confirm/Reject pulses silent below API 30 — `hapticFor(pulse, sdk)` maps both to LongPress below API 30; unit-tested.
  - `[low]` `[reject]` (blind-hunter) Tick and Confirm fire together on a 5 → 6 completion frame; Reject fires again for a new conflict face — both follow the PO's wording (a tick each time the count goes up, a confirm on completion, a pulse on a conflict face); suppressing one would add a rule the PO did not ask for.
  - `[low]` `[patch]` (blind-hunter) Stale count on the announcement node after the count falls — the node carries a description only while its count equals the shown count.
  - `[low]` `[patch]` (blind-hunter) Outlines freeze during the completion hold — cleared on a Complete snapshot.
  - `[false]` `[reject]` (blind-hunter) Net does not reuse the shared blank well — `StickerReviewNet.kt:323` paints its blank well with the same `DarkTokens.surfaceStage`; the cell-size concern is the next row.
  - `[low]` `[patch]` (blind-hunter) Large-font test did not guard net legibility — asserts a cell of at least 8 dp in six configurations; passed without a layout change.
  - `[low]` `[reject]` (blind-hunter) Taps dropped while `busy`; Reset swaps Button/OutlinedButton — `busy` lasts one analyzer hop (milliseconds), and the swap only matters if TalkBack focus sits on Reset at the instant a twist latches; fixes add state for cases a Learner is unlikely to meet.
  - `[medium]` `[patch]` (blind-hunter) Dead 2-corner code left behind — removed after checking Case capture, recorder and evaluation users: `CornerOverlaySmoother`, the `detectedCornerQuads` chain, `meterCube`/`settled`/`releaseExposure` and their adapter code, their three diagnostics hooks; stale KDoc fixed.
  - `[medium]` `[defer]` (blind-hunter) Calibration from a Quick Scan review throws the scan away — same root as the intent-alignment row; deferred item 1.
  - `[low]` `[patch]` (blind-hunter) Missing I/O-matrix tests — partly refuted (the discard dialog at N ≥ 1 is covered in `ContinuousQuickScanRouteTest` at the `scan:discard` assertions; "no torch without flash" and "announce on rise only" are in `ContinuousQuickScanScreenTest`); the rotation and `preserveUnread` gaps were patched as above.
  - `[low]` `[patch]` (blind-hunter) Spec and docs bookkeeping — tasks ticked, logs written, ARCHITECTURE sentence added, minified 0.11.0 size re-measured and recorded.
  - `[false]` `[reject]` (edge-case) *Scan face by face* under a latched twist keeps six filled faces and opens Review — PO Q3 (a) says the exits still carry the faces confirmed before the twist; Review validates before *Accept*, so no unvalidated state is accepted.
  - `[false]` `[reject]` (edge-case) Six full faces, not Complete, no conflict cue → Review without a Guided face — Review is the correction surface and *Accept* stays disabled until the cube validates; nothing is guessed.
  - `[false]` `[reject]` (edge-case) *Enter colors instead* under a twist seeds the Editor — mandated by PO Q3 (a).
  - `[low]` `[patch]` (edge-case) Reset race — same fix as the blind-hunter row.
  - `[low]` `[patch]` (edge-case) `hasFlash` reset to null by an in-flight frame — assigned only when non-null.
  - `[false]` `[reject]` (edge-case) `engine.reset()`/`export()` throwing leaves `busy` set — the mapper is pure code with no demonstrated throwing path; an exception there would crash loudly in `viewModelScope`, which is correct for a bug.
  - `[low]` `[reject]` (edge-case) Taps dropped while busy — same as the blind-hunter row.
  - `[low]` `[patch]` (edge-case) Hand-off calibration used without `StickerClassifier.isValid` — guarded in `handoverCenters` and both Review constructions.
  - `[low]` `[patch]` (edge-case) Complete export with null calibration leaves Review without centres — falls back to the saved calibration.
  - `[maybe-false]` `[reject]` (edge-case) Snapshot Complete but export not Complete loops every second — both use `validateCompleteNet()` and the same 54 committed stickers; it would take a mapper state where `handoff.isComplete` differs from the snapshot, which I could not construct; if true it would be low (the Learner still has Reset and every exit).
  - `[low]` `[patch]` (edge-case) Landscape narrower than 600 dp used the portrait Column — Row whenever width exceeds height; 640×360 test added.
  - `[low]` `[patch]` (edge-case) Unanchored outline lingers after anchoring — dropped when the anchored outline is added.
  - `[medium]` `[patch]` (edge-case) Close in hand-over asks with no face — same fix as the intent-alignment row.
  - `[medium]` `[patch]` (edge-case) Dead `meterCube`/`settled`/`releaseExposure`, `CornerOverlaySmoother`, `detectedCornerQuads` — same fix as the blind-hunter dead-code row.
  - `[medium]` `[patch]` (edge-case) `Coaching.SIDES_DARK`/`TORCH_GLARE` unreachable — enum entries and screen mappings removed (dead-code group).
  - `[medium]` `[patch]` (edge-case) `msg.scan.coach.sides-dark`/`torch-glare` strings left without a user — removed from `en.json` (dead-code group).
- post-patch fix by the runner: the detector's debug check first used `BuildConfig.DEBUG`, which `BuildConfigUsageTest` forbids outside the flags adapter; replaced with the application's `FLAG_DEBUGGABLE`.

### 2026-11-25 — Review pass (follow-up)
- verdicts: 50 findings — high 0, medium 11, low 25, false 12, maybe-false 2
- layers: follow-up pass on the `done` spec. The Codex scope guard failed (no `codex-review.on` marker in this worktree's state dir), so blind-hunter, edge-case-hunter and verification-gap ran as context-free Claude subagents; intent-alignment ran as the stock Claude subagent. All four reported. The step-03 implementation subagent of the first pass could not be re-engaged, so a fresh Claude subagent applied the patches.
- findings:
  - `[low]` `[reject]` (intent-alignment) carried — Every app test feeds hand-built snapshots; no test runs the real detector → mapper chain into the screen — Stories 10.30/10.31 own that chain and the device surface is this story's pending human gate. (The engine adapter itself is now executed by a test; see the verification-gap row.)
  - `[low]` `[reject]` (intent-alignment) *Accept* is asserted as `SolvePhase.AlreadySolved` because the route fixture is the solved cube, not "→ Solution Playback" — the scan flow's contract is the returned `ScanOutcome.Accepted(state)`, which the test asserts; dispatch of a scrambled state to Playback is the Solve tab's existing, separately tested code. A scrambled 54-sticker fixture would add test machinery for no new coverage of this story's code.
  - `[false]` `[reject]` (intent-alignment) TalkBack is asserted as semantics (polite live region, re-keyed count node), not as speech — the intent records the spoken behaviour as part of the pending hands-on gate and forbids claiming it; no divergence in the code.
  - `[medium]` `[patch]` (intent-alignment) carried — Haptics, outline stroke, torch and rotation asserted one level below the Learner — patched in the first pass (`ContinuousQuickScanHaptics` test, `torchLit`); this pass adds the activity-level haptic test in the verification-gap row.
  - `[low]` `[patch]` (intent-alignment) carried — Row layout applies to any landscape, not only tablets — the first pass's fix for the narrow-landscape Column finding; unchanged.
  - `[false]` `[reject]` (intent-alignment) No recorded build of a variant with `QUICK_SCAN` OFF and another camera flag on — the property that makes it compile holds: no file under `app/src/camera/kotlin` references `dev.cubentia.app.camera.continuous` (the auditor's own grep), and the default release build with the flag OFF compiles in `./gradlew build`.
  - `[medium]` `[patch]` (intent-alignment) The retired replays (`QuickScanBurstReplayTest`, `RecordedBursts`, the burst test input) have no last revision or command recorded, which the intent requires — `docs/evidence/10-32/camera-size.md` gains section 3 with the last revision (`52b66aefa63ab02f8ae6b08cc6a6e5d769756be8`), the command, a plain note that it was not re-run, and the remaining consumers of the recorded bursts.
  - `[medium]` `[patch]` (intent-alignment) carried — Counted camera class bytes rose instead of showing reclaimed headroom — handled in the first pass (dead code removed, evidence reworded, residual risk recorded); not patched again.
  - `[low]` `[reject]` (intent-alignment) carried — *Scan face by face* with nothing to ask opens Review directly, skipping the rest of the hold — same row as the first pass.
  - `[low]` `[reject]` (intent-alignment) `keptForGuided` mixes the shown snapshot's `conflictedSlots` with the fresh export — the export already carries conflicted and unread cells as null, which alone requests their faces; the snapshot's set can at worst ask for one face more for a frame-old conflict, never keep a bad one. Removing it would change a rule the first pass tested.
  - `[false]` `[reject]` (intent-alignment) *Enter colors instead* in the hand-over after a Guided capture returns kept plus captured stickers — confirmed and captured stickers only, nothing guessed; covered by the first pass's route tests.
  - `[medium]` `[defer]` (intent-alignment) carried — A calibration saved from a Quick Scan review restarts Quick Scan with an empty map — deferred item 1; not deferred again.
  - `[false]` `[reject]` (intent-alignment) `assembleHandover(preserveUnread)` and `GuidedScanController.handOver` are new domain API — internal additions with domain tests; no bad outcome named.
  - `[medium]` `[patch]` (intent-alignment) carried — Removals beyond the files Q7 names (`Coaching.SIDES_DARK`/`TORCH_GLARE`, four strings, `diagnose()`, `SideLight.Verdict` properties, cube-outline metering, most of `ScanDiagnostics`) — the first pass's dead-code patch group; the metering part is the new deferred item 4.
  - `[false]` `[reject]` (intent-alignment) `QuickScanControllerTest` and the old route and screen tests are deleted — they tested the deleted 2-corner code; Q7 fixes that removal.
  - `[false]` `[reject]` (intent-alignment) No test covers the Case capture screen or the recorder after the trim — `CaseRecognitionRouteTest` (drives the Case capture viewfinder, `quickScan:outline`) and `RecorderRulesTest` exist and pass in the build below.
  - `[false]` `[reject]` (intent-alignment) carried — `CornerPrompt` fallback branch not reached — same row as the first pass.
  - `[medium]` `[patch]` (intent-alignment) carried — The debug-only main-thread check edits `LiteRtStickerCenterDetector` although the intent says no change to the detector's behaviour — the first pass's patch for the Story 10.31 tracker item; recognition output is unchanged. Its test is the verification-gap row below.
  - `[medium]` `[patch]` (verification-gap) The production engine adapter `PipelineEngine` is never executed by a test — new `ContinuousScanEngineModuleTest`: engine built through `ContinuousScanEngineModule.factory(fakeDetector).create()`; frame index 0, 1, 2; `reset()` restarts it at 0; `export()` after reset is a `Partial` with no faces and unanchored.
  - `[low]` `[patch]` (verification-gap) The activity's `quickScan run` line lost its route-level assertion — `ContinuousQuickScanRouteTest` asserts exactly one line with `fallbackFaces=3` / `outcome=ACCEPTED` after the hand-over accept, and one with `fallbackFaces=0` / `outcome=CANCELLED` on close.
  - `[medium]` `[patch]` (verification-gap) Haptic pulses not verified through the activity wiring — new route test `aNewlyMappedSidePlaysOneHapticThroughTheActivity`: a frame raising the count from 0 to 1 makes exactly one view report `KEYBOARD_TAP` (Robolectric `lastHapticFeedbackPerformed()`); no production surface added.
  - `[medium]` `[patch]` (verification-gap) The completion hold's discard-question guard has no test — new route test `theDiscardQuestionHoldsACompletedScanUntilItIsCancelled`: the hold elapses with the question open and no review; after *Cancel* and another hold the review opens.
  - `[low]` `[patch]` (verification-gap) The debug-only main-thread check in `detect` is not exercised — new Robolectric `LiteRtStickerCenterDetectorThreadGuardTest`: throws on main when debuggable, returns `EMPTY` for a degenerate frame off main, does not throw on main when not debuggable.
  - `[low]` `[patch]` (verification-gap, other) Unused `ShadowLog` import in `ContinuousQuickScanRouteTest` — now used by the run-line assertions.
  - `[low]` `[reject]` (verification-gap, other) `FakeFrames.continuousMode` discards the `analyzing` predicate — the activity re-checks `analysisHeld`/`busy`/`confirmDiscard` in `analyze`, so a wrong predicate only costs inference during a hold; evaluating it needs a redesigned fake.
  - `[medium]` `[patch]` (blind-hunter) carried — The size criterion's "reclaimed headroom" wording is not met — same as the first pass's AC 4 row (residual risk; a PO call, not a code fix).
  - `[false]` `[reject]` (blind-hunter) No frame-latency evidence — the evidence file records it as not measured and the intent assigns device measurements to the pending human gate and Story 10.33; nothing is claimed.
  - `[low]` `[reject]` (blind-hunter) `elapsedMs` in the run line now starts at `onCreate` (includes permission, camera bind and the 1 s hold) — debug-only line whose KDoc defines it as "since the screen opened"; moving the start point is a measurement-definition choice for Story 10.33, and a start stamped on the first frame would log no time for a run cancelled before it.
  - `[low]` `[patch]` (blind-hunter) `guidedFacesAsked` stays set after a calibration saved from review restarts the scan — reset to 0 in that branch. The "revision not bumped" half is refuted: no continuous frame is posted while the phase is Review or Calibration.
  - `[low]` `[reject]` (blind-hunter) A camera failure during Quick Scan moves to `Unavailable`, where *Enter colors instead* opens a blank Editor — real, but a mid-scan camera failure is rare and nothing is guessed; carrying the map would need a new export path out of the `Unavailable` phase.
  - `[false]` `[reject]` (blind-hunter) A missing engine shows the camera-unavailable card — the I/O matrix row "No pipeline binding → existing `Unavailable` phase" asks for exactly this.
  - `[false]` `[reject]` (blind-hunter) carried — `keptForGuided` under a twist with six full faces opens Review — same as the first pass's edge-case row (PO Q3 (a); Review validates before *Accept*).
  - `[low]` `[reject]` (blind-hunter) Leftovers in `:core:domain` (`CenterCheck` — carried; `GridReason.MISSING`/`REPROMPT`, `FaceDecision.gridReason`, `MIN_CONFIDENT`, the `QuickScanPlan` name) — Q7 keeps `QuickScanClassification.kt` and moves `QuickScanPlan`, `CenterCheck` and `GridReason` as they are; trimming them changes kept evaluation code.
  - `[low]` `[patch]` (blind-hunter) Stale references to removed code — `tools/corner-model/README.md` no longer names `CornerOverlaySmoother`; the bursts' remaining consumers are recorded in the evidence file. Not changed: the `quickScan:outline` test tags (used by `CaseRecognitionRouteTest`), the `ScanDiagnostics` class KDoc (the function carries its own), and the `KotlinStickerReads` sentence (reads correctly).
  - `[low]` `[reject]` (blind-hunter) TalkBack meets the count twice (visible text and the 1 dp announcement node); cue changes are not rate-limited; the net is not read — the last two are PO Q6 as written; the double count is the Design Notes' announce-on-increase mechanism and needs the spoken check of the pending device gate before any change.
  - `[low]` `[reject]` (blind-hunter) The pulse channel is unbounded and may play late after a rotation — at most a few pulses can queue in a rotation gap; a conflated channel would drop the tick of a 5 → 6 completion frame.
  - `[low]` `[patch]` (blind-hunter) The detector's main-thread guard is untested — same test as the verification-gap row. The "fragile" half is refuted: the detector is a Hilt singleton (safely published) and `FLAG_DEBUGGABLE` is the build-type signal `BuildConfigUsageTest` allows here.
  - `[false]` `[reject]` (blind-hunter) The engine has no teardown — `PipelineEngine` holds only the pure mapper and is collected with the ViewModel; the LiteRT session belongs to the singleton detector by Story 10.31's design.
  - `[medium]` `[patch]` (blind-hunter) Test gaps for new behaviour — the discard question during the hold is patched (verification-gap row). Refuted or rejected parts: the calibration-save restart is covered by the first pass's route test; *Reset* during an export is dropped by `busy` (first-pass row); the outline-key collision is the edge-case row below.
  - `[low]` `[reject]` (blind-hunter) `docs/ARCHITECTURE.md` has one sentence and no flow description — the port sentence is what the architecture section needs; the flow and threading rules are in this spec's Design Notes and the code's KDoc.
  - `[false]` `[reject]` (edge-case) carried — Six full faces, cue not `ResolveConflict` (e.g. a twist): *Scan face by face* opens Review with no Guided capture — same as the first pass's rows.
  - `[low]` `[reject]` (edge-case) Camera `Unavailable` during Continuous or the hand-over, then *Enter colors* opens an empty Editor — same as the blind-hunter row.
  - `[low]` `[patch]` (edge-case) `backOutOfHandOver` nulls `handoff` before `handoverController`, so the analyzer can read no hand-off but a live controller and publish a Guided-only review — order swapped (controller first) there and in `saveCalibration`.
  - `[low]` `[patch]` (edge-case) `guidedFacesAsked` never reset on the calibration restart — same fix as the blind-hunter row.
  - `[low]` `[reject]` (edge-case) `OutlineKey` can collide across fragments, easing an outline between two unrelated quads — cosmetic for a few frames (each frame halves the error); the fix needs a fragment id on the overlay quad from the pipeline.
  - `[maybe-false]` `[defer]` (edge-case) Landscape phone at 200% font: the non-scrolling panel may push *Enter colors instead* off screen — not verified; what would settle it: `fits(landscape = true)` at `w640dp-h360dp-land`, `fontScale = 2.0f`. Deferred item 3 (medium, unverified).
  - `[low]` `[patch]` (edge-case) A retake from a Quick Scan review shows the continuous-kept faces as not captured in the mini net — `GuidedScanFlow.retakePhase` fills `keptColors` from the review's stickers for the faces not retaken; unit test added in `GuidedScanFlowTest`.
  - `[maybe-false]` `[defer]` (edge-case) The continuous Quick Scan never meters on the cube after the cube-outline metering was removed — not verified; a device observation for the pending hands-on pass. Deferred item 4 (medium, unverified).
  - `[low]` `[reject]` (edge-case) `SettleTracker`, `ExposureLocks` and the metering-region helpers now serve only the debug fixture recorder — the intent keeps what the recorder uses and forbids changing the recorder; moving them to the debug source set is a refactor, and R8 strips them from release.
  - `[low]` `[patch]` (edge-case) Imports with no remaining use — removed from `GuidedScanActivity.kt`, `ScanFrameSource.kt` and both `ScanDiagnostics.kt`. The claimed consequence is refuted: `spotlessCheck` passed before and after.

## Design Notes

- **Why a port and not a new Activity:** Guided hand-over with Back, Review, per-face retake and the `ScanOutcome` encoding all live in `GuidedScanActivity` and its ViewModel. Hosting the continuous phase there reuses them unchanged; the engine port is the only thing that crosses into the `continuous/` source set.
- **Threading:** UI actions that touch the engine (*Reset*, export for hand-over, Enter colors, completion) are posted to the analyzer executor and publish their result back to the ViewModel, as `switchToFaceByFace` does today.
- **Hand-over without `FaceSample`s:** the kept faces are a `ContinuousScanHandoff` (with any face Guided must retake removed from `faces`); Guided's `added` samples plus that hand-off go to `ScanAssembler.assembleHandover`. A retake from a Review that has fewer than six samples relies on `ScanReview.retaken` using the review's evidence centres; cover that path with a test.
- **6/6 but incomplete:** Q5 (ii)(a) retakes the faces holding conflicted stickers. A mapped face can also hold unread, non-conflicted stickers; the same answer is applied to them (the PO rejected "open Review with blanks" and "disable" for this state).
- **Announce on increase only:** the visible count is not a live region; a separate polite node carries the count text and changes only when N rises.

## Verification

**Commands** (each prefixed with `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-32 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk &&`):
- `./gradlew spotlessCheck build` -- expected: success (lint, JVM tests, Kover, `checkLearnerText`, boundary and manifest checks, debug and release APKs).
- `./gradlew :app:checkCameraSize` -- expected: success, projected size < 30,000,000 B; record class bytes in `docs/evidence/10-32/camera-size.md`.
- `./gradlew :core:domain:exportKotlinStickerReads --dry-run :core:domain:evaluateQuickScan --dry-run` and `./gradlew :core:domain:compileEvaluationKotlin` -- expected: both tools still registered and compiling.
- `./gradlew -p build-logic test` -- only if `build-logic` is touched.
- `rg -n "QuickScanController|QuickScanDiagnosis\\b|QuickScanEffect" app core --type kotlin` -- expected: no code reference left.

**Manual checks (if no CLI):**
- Human gate, pending and not claimed by this run: hands-on pass from the Solve tab on Lenovo TB128XU and Galaxy S24 FE (debug build, `QUICK_SCAN` BETA).

## Auto Run Result

Status: done

**Summary.** Quick Scan on the Solve tab now opens the continuous scan screen (`ContinuousQuickScanScreen`, hosted as a new phase of `GuidedScanActivity`): live preview with eased face outlines, a growing URFDLB net, "Sides mapped: N/6", one cue line, *Reset*, *Scan face by face*, *Enter colors instead*, torch and *Close*. The base camera code reaches the Story 10.31 pipeline only through the optional `ContinuousScanEngineFactory` port. Completion holds for 1 s and opens the existing Scan Review; the Guided hand-over keeps the mapped faces through `ScanAssembler.assembleHandover`. The Quick-Scan-only 2-corner code is removed within the PO's Q7 scope.

**Implementation route.** Codex started the implementation and ran out of credits after about 9 minutes ("Turn failed"); a Claude subagent finished it from the partial tree (`claude (codex fallback)`). The three Codex review layers ran as Claude subagents for the same reason.

**Files changed** (50 files changed, 2746 insertions(+), 7862 deletions(-)):
- `app/src/camera/kotlin/.../ContinuousScanEngine.kt` (new), `CameraModule.kt`, `app/src/camera/continuous/.../ContinuousScanEngineModule.kt` (new), `ContinuousScanPipeline.kt` -- optional engine port and its binding.
- `app/src/camera/kotlin/.../ContinuousQuickScanFlow.kt`, `ContinuousQuickScanScreen.kt` (new) -- screen rules (count, outlines, pulses, completion hold, hand-over set, hand-over review) and the composable with its haptics collector.
- `app/src/camera/kotlin/.../GuidedScanActivity.kt`, `GuidedScanFlow.kt`, `GuidedScanScreen.kt`, `ScanFrameSource.kt`, `CornerLocator.kt` -- continuous phase and wiring; 2-corner Quick Scan branches, corner overlay smoother and cube-outline metering/exposure-lock code removed; torch gate extracted.
- `app/src/camera/continuous/.../LiteRtStickerCenterDetector.kt` -- debug-only main-thread check (Story 10.31 tracker item).
- `app/src/{debug,release}/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- 2-corner hooks removed; `onQuickScanRun` kept for the continuous run line.
- `core/domain/.../recognition/`: `QuickScan.kt`, `QuickScanDiagnosis.kt` deleted; `CornerCapturePlan.kt` (`QuickScanPlan`, `CenterCheck`) and `GridReason.kt` new; `CornerHold.kt`, `SideLight.kt` trimmed; `QuickScanClassification.kt` kept (gains `MIN_CONFIDENT`, loses `diagnose()`); `GuidedScan.kt` gains `GuidedScanController.handOver`; `ScanAssembler.kt` gains `keptSamples` and `assembleHandover(preserveUnread)`.
- `core/domain/src/main/resources/content/strings/en.json` -- adds `msg.quick-scan.sides-mapped`, `btn.quick-scan.reset`; removes `msg.quick-scan.hold-first/-second`, `msg.scan.coach.sides-dark`, `msg.scan.coach.torch-glare`.
- Tests: new `ContinuousQuickScanRouteTest`, `ContinuousQuickScanFlowTest`, `ContinuousQuickScanScreenTest`, `ContinuousQuickScanLargeFontTest`, haptics test, `GuidedScanHandOverTest`; extended `CaseCaptureControllerTest`, `ContinuousHandoffTest`, `ScanFrameSourcePolicyTest`, `CameraXFrameSourceTest`, `CornerDetectorBindingTest`, `ScanDiagnosticsRunLineTest`; deleted the 2-corner `QuickScanRouteTest`, `QuickScanScreenTest`, `QuickScanLargeFontTest`, `QuickScanControllerTest`, `QuickScanBurstReplayTest`, `RecordedBursts`; trimmed `GuidedScanFlowTest`, `CornerLocatorTest`, `QuickScanGateTuningTest`, `QuickScanSideFaceReplayTest`, `SideLightTest`.
- `core/domain/build.gradle.kts` -- stale burst-replay test input removed. `docs/ARCHITECTURE.md` -- one sentence on the engine port. `docs/evidence/10-32/camera-size.md` -- size evidence and pending gates.

**Review findings.** 55 findings from four layers: 35 patched (18 medium, 17 low), 2 deferred (medium; the same root cause reported by two layers — deferred item 1; deferred item 2 is the still-open Story 10.31 tracker item noted in a patched row), 9 refuted as false, 9 rejected (8 low, 1 maybe-false). Every rejected finding and its reason is in the Review Triage Log above. Patched counts by verdict: high 0, medium 18, low 17.

**Follow-up review recommended: true.** Two or more medium entries were patched. The specific unverified risk: the review patches themselves were checked by tests only, not by a second review pass — in particular the removal of the cube-outline metering and exposure-lock code from `CameraXFrameSource` (shared file with the fixture recorder and Case capture), the new `GuidedScanController.handOver` factory, and the haptics collector.

**Verification performed.**
- `./gradlew spotlessCheck build :app:checkCameraSize :core:domain:compileEvaluationKotlin`: BUILD SUCCESSFUL after the review patches (lint, JVM and Robolectric tests, Kover, `checkLearnerText`, boundary/manifest/denylist checks, debug and release APKs). One earlier run failed `BuildConfigUsageTest` (the detector check used `BuildConfig`); fixed and re-run green.
- `:app:checkCameraSize`: pass, projected 28,554,298 B of 30,000,000 B; camera class bytes 1,151,016 B.
- `-Pcubentia.release011=true :app:checkRelease011Size :app:checkRelease011ElfAlignment :app:checkRelease011MergedManifest :app:checkRelease011CameraClasspath :app:checkRelease011ShadowReader`: pass, 26,261,058 B installed estimate.
- `:core:domain:exportKotlinStickerReads --dry-run :core:domain:evaluateQuickScan --dry-run`: both tools registered; the evaluation source set compiles.
- `rg "QuickScanController|QuickScanDiagnosis\b|QuickScanEffect" app core`: no match.
- `build-logic` untouched, so `./gradlew -p build-logic test` was not needed.
- I/O matrix audit: every row has a covering test in the new suites, all run and passing in the build above.

**Residual risks.**
- **AC 4 size wording.** The story says `:app:checkCameraSize` "confirms reclaimed class-byte headroom". The gate passes, but counted camera class bytes are 90,574 B above the Story 10.31 baseline: the removal reclaimed about 38,500 B in the counted package, the new screen adds about 129,000 B, and most removed 2-corner code lived in `:core:domain`, which the gate does not count. See `docs/evidence/10-32/camera-size.md`.
- **One rule derived, not answered.** At 6/6 mapped but not complete, *Scan face by face* also retakes faces holding unread (non-conflicted) stickers; the PO's Q5 (ii)(a) names conflicted stickers only.
- **Human gate pending, not claimed.** No hands-on pass on the Lenovo TB128XU or Galaxy S24 FE was done: outline registration on the real cube, felt haptics, spoken TalkBack, the lit torch and the frame-edge behaviour noted in the 10.31 tracker are unverified on device. No latency was measured in this story.
- **Glyphs follow the colour-blind setting**, as in every other net in the app; they are not forced on in this screen.
- `CenterCheck` is kept as the PO instructed but has no caller left.

### Follow-up review pass — 2026-11-25

Status: done. This section supersedes the first pass's follow-up recommendation above; the first-pass text is kept as the record of that run.

**Summary of the implemented change.** Unchanged in scope: the continuous Quick Scan screen, its Guided hand-over and Review hand-off, and the 2-corner removal, as described above. This pass re-reviewed the whole change since the baseline with four layers and applied small fixes and missing tests.

**Files changed in this pass** (12 files besides this spec):
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `guidedFacesAsked` reset on the calibration restart; hand-over controller nulled before the hand-off (analyzer read order); four unused imports removed.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `retakePhase` carries the kept faces' colours from the review, so a retake from a Quick Scan review shows them in the mini net.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt`, `app/src/{debug,release}/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- unused imports removed.
- `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` -- run-line assertions (accept after hand-over, cancel), one haptic through the activity, the discard question during the completion hold.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt` -- retake mini-net colours from a Quick Scan review.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModuleTest.kt` (new) -- the production engine adapter: frame index, reset, export.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorThreadGuardTest.kt` (new) -- the debug-only main-thread check.
- `docs/evidence/10-32/camera-size.md` -- section 3: last revision and command for the retired burst replay; size figures after this pass.
- `tools/corner-model/README.md` -- no longer names the removed `CornerOverlaySmoother`.

**Review findings.** 50 findings from four layers. 12 rows repeat first-pass findings and keep their verdict and route (marked `carried`; none was patched or deferred again). Of the 38 new rows: 15 patched rows in 11 entries (4 medium: retired-replay record, engine adapter test, haptic route test, discard-during-hold test; 7 low: run-line assertion, detector guard test, `guidedFacesAsked` reset, hand-over null order, retake mini net, unused imports, stale README), 2 deferred (both `maybe-false`, medium if true: landscape phone at 200% font; no cube metering in the continuous scan — deferred items 3 and 4), and 21 rejected (`false` on their refutation, `low` with the reason recorded). Every rejected finding and its reason is in the "Review pass (follow-up)" entry of the Review Triage Log. Patched counts by verdict this pass: high 0, medium 4, low 7.

**Follow-up review recommended: false.** This was a follow-up pass and it patched no `high` finding; the production changes are a counter reset, a two-line order swap, one `copy` argument and import deletions, each covered by a test or by the full build.

**Verification performed (this pass).**
- `./gradlew spotlessCheck build :app:checkCameraSize :core:domain:compileEvaluationKotlin`: BUILD SUCCESSFUL (lint, JVM and Robolectric tests, Kover, `checkLearnerText`, boundary/manifest checks, debug and release APKs).
- `:app:checkCameraSize`: pass, projected 28,557,040 B of 30,000,000 B; camera class bytes 1,151,930 B (+914 B over the first pass, from `retakePhase`).
- `./gradlew :core:domain:exportKotlinStickerReads --dry-run :core:domain:evaluateQuickScan --dry-run`: both tools registered.
- `rg "QuickScanController|QuickScanDiagnosis\b|QuickScanEffect" app core --type kotlin`: no match.
- `build-logic` untouched. The `-Pcubentia.release011=true` size checks were not re-run in this pass (their last result is the first pass's 26,261,058 B). The retired replay command recorded in the evidence file was not executed.
- The new haptic and discard-hold route tests were not mutation-checked (the guarded production lines were not deleted to see them fail).

**Residual risks.** The first pass's list stands (AC 4 size wording, the derived 6/6 rule, the pending human gate, glyphs, unused `CenterCheck`). Added by this pass:
- Two unverified items deferred to the device pass: the panel on a landscape phone at 200% font, and focus/exposure without cube metering.
- A camera failure in the middle of a Quick Scan opens the unavailable card, and *Enter colors instead* from there starts with a blank Editor (rejected as rare; recorded in the triage log).
- The debug run line's `elapsedMs` counts from the screen opening, so it includes a permission prompt and the 1 s completion hold; `evaluateQuickScan`'s 15 s success threshold should be read with that in mind in Story 10.33.
