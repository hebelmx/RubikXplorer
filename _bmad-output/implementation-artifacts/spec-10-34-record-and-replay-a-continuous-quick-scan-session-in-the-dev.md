---
title: 'Story 10.34: Record and replay a continuous Quick Scan session in the developer build''s Quick Scan'
type: 'feature'
created: '2026-11-26'
status: 'done'
baseline_revision: '79704f22fb32137afd007a308aa11f06cde3a485'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-26-retire-recorder.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A Quick Scan failure on a device cannot be reproduced offline. The PR #396 recorder drove the camera with its own `ImageAnalysis` (480x640 frame, 206x640 crop, letterbox 0.4), so the model saw a strip with half the cube cut off, and its capture raised analyzer p50 from 40.74 ms to 50-61 ms (every session defective).

**Approach:** Add a debug-only recording mode to the real Solve-tab Quick Scan (`GuidedScanActivity` quick mode): a Rec control, recording indicator, session sheet, saved-session notice and labeller in the debug-only package `dev.cubentia.app.camera.session`, reached only through optional bindings from the debug camera source set. Cherry-pick from PR #396 (ref `origin/feat/10-34-record-and-replay-a-continuous-quick-scan-session-in-the-dev`, tip `4be002d98a075f901478e736612444d115a8c8eb`) only the frame tap and neutral ports, the writer (moved package), state and image replay, `:checkFixtureSize`, the writer guards and the synthetic golden sessions.

## Boundaries & Constraints

**Binding ADs (from the story Refs, verbatim):** AD-9 (Frames, amended SCP 2026-11-25 and SCP 2026-11-26), AD-15, AD-18.

**Always:**
- Frames come from the same `ScanFrameSource` (`CameraXFrameSource`) configuration, detector, lattice fitter, sampler and mapper the Learner uses; recorded crop and model-input geometry are taken from the frame Quick Scan actually processed, and a `testDebug` test proves they equal Quick Scan's for a given frame size.
- Analyzer-thread capture work is only a copy into the bounded 8-frame queue (no allocation per frame beyond a preallocated pool; pool exhaustion counts as a `captureSkips` skip, never a silent drop). Serialization, PNG encode and file I/O run on the writer executor.
- Writer, Rec control, sheet, labeller and receiver are reachable only through `@BindsOptionalOf` ports bound in `app/src/camera/debug/`; `FixtureWriterPolicy.CLASS_PACKAGE = "dev/cubentia/app/camera/session/"`, `CameraSizePolicy.debugOnlyClassGlobs` names the same package, and a planted class fails every non-debuggable variant.
- Labels go through `StickerTruthCheck` (`core/domain/src/main/.../recognition/StickerTruthCheck.kt`, HEAD version kept, not the PR copy).
- Recording is debug tooling (AD-15): no Learner text, no `en.json` keys, no release-visible change.

**Never:** cherry-pick the `ContinuousRecorder*` UI, `FixtureRecorderActivity` wiring, `RecorderRules` or the PR #396 spec; name any activity `*Recorder*` (`DebugManifestTest`); add device work (the PO runs the Lenovo gate); wire `replayContinuousSession` into `check`; edit planning artifacts.

</intent-contract>

## Settled UI decisions (PO answers, 2026-11-26, all recommendations accepted)

1. **Indicator:** a static red dot with "REC", the frame count and the skip count, in the camera area. It does not blink.
2. **Rec control:** in the bottom-start corner of the camera area. It reads "Rec" when idle and "Stop" while recording. It is hidden on the detector-unavailable card and while the sheet is open.
3. **Rec (or adb start) clears the map** and restarts the frame index, the same way Reset does.
4. **Scan completes while recording:** the recording stops by itself with outcome `complete`. The sheet opens before the Scan Review hand-off, and the hand-off continues after Save or Discard.
5. **Reset while recording:** the session stops and the sheet opens. The map is cleared after Save or Discard.
6. **Leaving the screen** stops the session first. This covers Close/Back, Scan face by face, Enter colors instead, Review and Correct, the app going to the background, and the detector becoming unavailable. If the screen is left with the sheet unanswered, the temp directory is discarded. After Review and Correct or backgrounding, the screen comes back to the sheet.
7. **Cap reached** (600 frames or 40 MB): the recording stops by itself and the sheet opens, showing "Stopped: frame cap" or "Stopped: size cap".
8. **Baseline from the sheet:** the pending session is kept. A 30 s no-capture timing run runs on the live screen, with the indicator showing "Baseline". It stores a per-device p50/p95 and returns to the sheet, which then shows the value. A session saved with no baseline gets `latencyBaselineP50Ms = null` and is judged defective on skips only; its sheet says "No baseline yet".
9. **Saved-session notice:** "Saved <sessionId>" in the camera area, with "· defective: <reasons>" added when it applies, and a **Label** action. It stays until it is dismissed or the next Rec.
10. **The sheet cannot be dismissed** by swipe, an outside tap or Back. It closes only through Save or Discard…, and Discard… asks "Discard this recording?".
11. **Cube and lighting** are free text, pre-filled with the last values, plus quick chips: `stla`/`stlb`/`stka`/`stkb` and warm/low/cool/backlit. Partition defaults to `UNASSIGNED`.
12. **adb:** `--es cube/lighting/partition` extras, defaulting to `unknown`/`unknown`/`UNASSIGNED`. An adb stop opens the same sheet; an adb save or discard closes it.

**Round 2 (PO answers, 2026-11-26):**

13. **Labelling a complete session (Option A):** for a session stopped by completion, Save shows the saved-session notice and holds the Scan Review hand-off. Scan Review opens when the operator dismisses the notice or closes the labeller. Discard still continues the hand-off at once.
14. **Baseline run:** it uses a fresh engine with no capture, timed exactly like a recording, and is shown live like a new scan (net, count, cue and outlines grow from empty; the indicator reads "Baseline"). Reset, Scan face by face, Review and Correct and Enter colors instead are disabled during it. Close/Back, backgrounding or a detector failure stop the run without storing a value and then follow answer 6. When the 30 s end, the pending session's map, count, cue and completion hold come back as they were, together with the sheet.
15. **Labeller:** the PR #396 content (six face chips, net, palette, pre-fill from the session net, Save labels locked until every pre-filled sticker is tapped, a refused count offers Override… with a non-empty reason or Keep as refused) as a full-screen dialog over Quick Scan. The live scan holds its analysis while the dialog is open, as it does for the discard question, so no hand-off can start behind it. Closing it (Save labels, Keep as refused, or Back) returns to the notice.
16. **adb mirrors the on-screen controls:** a command whose control is unavailable in the current state is ignored and logged under the tag `QuickScanSession`. A save or discard sent while recording stops first, then saves or discards.

**Settled by worker under PO delegation (2026-11-26)** (debug-only Rec mode, sheet, notice, labeller and adb trigger only):

- W1. **Indicator text:** recording reads "● REC <frames> · <skips> skips" (red dot, white text on a translucent dark pill, top-centre of the camera area so it never covers Close, torch or Rec); the baseline run reads "● Baseline <seconds left> s". Counts refresh at about 4 Hz.
- W2. **Stop reasons:** `SessionStopReason` gains `COMPLETE` and `RESET` beside `OPERATOR`, `FRAME_CAP`, `SIZE_CAP` (an adb stop is `OPERATOR`). Leaving the screen (answer 6) is not a stop reason: the session is stopped and, if the screen is really left, discarded. The sheet's first line reads "Stopped: operator" / "Stopped: complete" / "Stopped: reset" / "Stopped: frame cap" / "Stopped: size cap".
- W3. **Outcome is derived, not chosen:** `SessionOutcome.derive(export, last)` (PR #396) sets complete/partial/failed/lost tracking; the sheet shows it read-only ("Outcome: partial").
- W4. **Sheet content, top to bottom:** stop line; "<frames> frames · <skips> skips · <MB> MB"; "Capture p50 <x> ms · p95 <y> ms"; "Baseline p50 <b> ms" or "No baseline yet"; "Will be marked defective: <reasons>" when it already applies; cube field + chips; lighting field + chips; partition chips (TUNING / HELDOUT / UNASSIGNED); **Run baseline** (30 s); **Save**; **Discard…**.
- W5. **Sheet presentation:** a non-dismissable modal bottom sheet (`ModalBottomSheet` with `confirmValueChange` rejecting Hidden, `shouldDismissOnBackPress = false`/a consuming `BackHandler`), shown over the live screen. Analysis is held while the sheet is open (same hold as the discard question).
- W6. **Session survives rotation:** the session controller lives in `GuidedScanViewModel` scope; a configuration change keeps recording, the sheet, the notice and the labeller; a geometry change is appended to `geometryChanges`.
- W7. **Return from Review and Correct / foreground with a stopped session:** the sheet reopens as it was (fields kept). Returning from the background while *recording* is impossible (backgrounding stopped it), so the sheet shows with reason "Stopped: operator".
- W8. **adb `label` extra:** an optional free-text tag that becomes part of the session id (`<yyyyMMdd-HHmmss>-<device>-<label>`, sanitised to `[a-z0-9-]`), as in PR #396. adb `start` while the notice is shown dismisses it (like Rec). adb `save` uses the sheet's current field values; the `--es cube/lighting/partition` extras on `save` override them, and on `start` pre-fill them.
- W9. **Notice placement:** bottom-centre of the camera area, above the Rec control's row, with **Label** and **Dismiss** text buttons; the Rec control is shown beside it so a new Rec can start (which dismisses the notice).
- W10. **Labeller unknowns:** the palette has six colours plus **Unknown**; an unknown sticker is stored as `null` colour (explicit), counted as "· N unknown" in `truthRefusal`.


## Code Map

**Quick Scan host (HEAD):**
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- quick-mode host. Engine created at 144-147 (`continuousFactory...create()`); actions 183-257; torch 335-336; completion `LaunchedEffect` 327-333; `analyze()` 624-656 on the analyzer thread posts `scan.continuous = before.next(...)`; `GuidedScanViewModel` 872+ (`analyzerExecutor` 873, `resetContinuous` 907, `completeContinuous` 937, `reviewAndCorrect` ~955, `switchToFaceByFace` ~997). Hook point for the Rec port, the session lifecycle and analyzer timing.
- `app/src/camera/kotlin/.../ContinuousQuickScanScreen.kt:60-177` -- camera `Box` (`scanViewfinder`) at 69-101: Close at TopStart, torch at TopEnd, bottom corners free. The Rec control, indicator and notice slot go here through an optional composable port.
- `app/src/camera/kotlin/.../ContinuousQuickScanFlow.kt:36-158` -- screen reducer (`reviewAtMs` = now + 1000 on Complete).
- `app/src/camera/kotlin/.../ScanFrameSource.kt` -- `analysis()` 345-370 (4:3 `ResolutionSelector`, `analysisTarget` 523-531 gives 640x480 or 1280x960, `UseCaseGroup` viewport 304-315, no output rotation). `read(image)` 386-397 calls `engine.processFrame(image.asYuvFrame(), rotation, viewW, viewH)`. Planes are valid only during the call (`FrameReader.kt:155-159`).
- `app/src/camera/continuous/kotlin/.../StickerCenterModelTransform.kt` -- `uprightDimensions` 44-51 and `letterbox` 54-66 are the geometry the new geometry test must compare against.
- `app/src/camera/continuous/kotlin/.../ContinuousScanPipeline.kt:42-116`, `ContinuousScanEngineModule.kt:13-44`, `LiteRtStickerCenterDetector.kt:82-186`, `app/src/camera/kotlin/.../ContinuousScanEngine.kt:21-39`, `CameraModule.kt:10-47` -- take the PR #396 tap hunks (all apply cleanly). Fix the PR's `skippedFrame` tap bug: it records a stale `lastSnapshot` while replay calls `processFrame(idx, [])`.
- `app/src/camera/debug/` -- today only an empty `AndroidManifest.xml`; `ReleaseConventionPlugin.kt:146-148,164-166` composes it for debuggable types only.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/DebugManifestTest.kt` -- forbids `*Recorder*` activities.

**PR #396 cherry-picks** (`git show <ref>:<path>`):
- Take as-is: `Fixtures/continuous/synthetic-{complete,partial}-v1/*`, `core/domain/src/main/.../continuous/session/ContinuousSession.kt`, the test files `ContinuousSessionJsonTest.kt`, `SyntheticSessionGenerator.kt` and `ContinuousSessionReplayTest.kt`, `core/domain/src/evaluation/.../continuous/ContinuousSessionReplay{,Main}.kt`, `app/src/camera/kotlin/.../ContinuousSessionWriterFactory.kt` (then reshaped: `frameDone` with primitives on `ContinuousFrameTap`, see Design Notes), `testDebug/.../continuous/ContinuousFrameTapTest.kt`, `androidTestDebug/.../ContinuousImageReplayDeviceTest.kt`, and in build-logic `FixtureSizePolicy`, `CheckFixtureSizeTask`, `FixtureConventionPlugin`, `GenerateBuildInfoTask` with their tests.
- Move the package from `recorder/session/` to `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/`: `ContinuousSessionWriter.kt`, `ContinuousSessionWriterModule.kt`, `RgbPng.kt`, `SessionLatency.kt`, `ContinuousSessionWriterTest.kt`, `ContinuousSessionWriterBindingTest.kt`. Change `FixtureWriterPolicy` (`CLASS_PACKAGE`), `CheckFixtureWriterTask`, `FixtureWriterPolicyTest` and `FixtureWriterCompositionFunctionalTest` to the same package.
- Take hunks only: `.github/workflows/ci.yml`, `build-logic/build.gradle.kts`, `QualityConventionPlugin.kt`, `ReleaseConventionPlugin.kt`, `CiWorkflowTest.kt`, root `build.gradle.kts`, `core/domain/build.gradle.kts`, `KotlinStickerReads.kt` (KDoc), `libs.versions.toml`, and the `LiteRtStickerCenterDetectorDeviceTest.kt` `copyModelInput` test.
- Redo by hand: `tools/cube-session.sh` (conflicts; add only the `session` subcommand and the continuous pull); `Fixtures/README.md` and `Fixtures/continuous/README.md` operator procedure.
- Drop: the `ContinuousRecorder*` files and their tests, `FixtureRecorder*`, `RecorderRules`, the old spec, sprint-status, epic context, and `docs/evidence/10-34/legacy-replay.md` (append to `docs/evidence/10-35/legacy-replay.md` instead).

**Latency fix targets in the PR writer:**
- Lazy `borrow()` pool allocation on the analyzer thread, with a silent drop when the pool is exhausted.
- A `Pending` object, an `executor.execute` and a writer wakeup every frame.
- `ContinuousCaptureStatus` allocated twice per frame.
- On the writer thread: a new `Deflater` per PNG, and a `toByteArray()` used only to measure size.

**Rebuild in `dev.cubentia.app.camera.session`:**
- The labeller.
- `colorLabel` (name, capitalised).
- `truthRefusal` ("Not saved: White 10 (over) · Blue 8 (under)", "· N unknown", "· N pre-filled not checked").
- The pre-fill lock.
- `TruthRefusalTest`, without the `ColorCountCheck` equivalence.

**adb receiver:** PR #396 registered it `RECEIVER_EXPORTED` with no sender check. The rebuild restricts senders with a receiver permission the shell holds and third-party apps cannot get (for example `android.permission.DUMP`). It is registered only from the debug binding while Quick Scan is open.


**Additional anchors (HEAD `79704f22`, verified 2026-11-26):**
- `GuidedScanActivity.kt`: engine creation L146; Back handler L286-300; completion hold L326-333 (gate the hold on "no recording sheet open"); `onStop` L451-458 (background stop); `startCamera` / `continuousMode` gate L611-621; `analyze()` Continuous branch L628-657 (`analysisHeld` L646); `closeOrConfirm` L508-540; ViewModel `resetContinuous` L907, `exportContinuous` L922, `completeContinuous` L937, `reviewAndCorrect` L958, `switchToFaceByFace` L996.
- `ScanFrameSource.kt`: `continuousMode` L67/L129 (`@Volatile continuous`, `analyzing`); `read` Continuous branch L386-397.
- `ContinuousScanEngineModule.kt` (HEAD: SAM `ContinuousScanEngineFactory { PipelineEngine(...) }`; `PipelineEngine.frameIndex`); `ContinuousScanPipeline.kt` `skippedFrame` L91-116.
- Test fakes: `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` L130-160 (SAM factory, `camera.feed(ScanFrame(...))`); `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt` L182-260 (fake `ImageProxy`, reuse it for the geometry test that runs `read()` through the real `ContinuousScanEngineModule.factory`).
- PR #396 distilled facts: format `ContinuousSessionFormat` (`SCHEMA_VERSION=1`, `QUEUE_FRAMES=8`, `FRAME_CAP=600`, `SIZE_CAP_BYTES=40_000_000`, `IMAGE_EVERY=3`, `LATENCY_DEFECT_FACTOR=1.10`); the PR `StickerTruthCheck.kt` / `StickerTruthCheckTest.kt` have HEAD's API (add/add conflict: keep HEAD); `GenerateBuildInfoTask` writes the asset `cubentia-build-info.properties` (`commit`, `litert`) for debuggable variants; replay CLI `[root] [--session <dir>] [--report <file>] [--refresh-golden]`; `generateSyntheticSessions` uses the test runtime classpath.

**Port design (new, shipped neutral code in `app/src/camera/kotlin/dev/cubentia/app/camera/`):**
- `QuickScanSessionPort.kt` (new) -- interfaces/data only, no Android I/O: `QuickScanSessionFactory` (`@BindsOptionalOf` in `CameraModule.kt`, bound only by `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionModule.kt`) creating a `QuickScanSession` per `GuidedScanViewModel`, given a `QuickScanSessionHost` the activity/VM implements. Host offers: start a capture (clears the map like `resetContinuous()` and calls `engine.record(tap)` on the analyzer executor), start/end a baseline engine, export, release a held hand-off, leave. Session offers: `holdsAnalysis` / `holdsHandOff` (read by `analyze()`, the completion `LaunchedEffect` and `startCamera`'s analyzing lambda), `onCompleted()`, `onReset()`, `onLeaving(kind)`, `onBackground()`, `onDetectorUnavailable()` (each returns whether it intercepted), and `Overlay(BoxScope)` composable drawn inside the `scanViewfinder` Box. `ContinuousQuickScanScreen` gets one nullable `sessionOverlay: (@Composable BoxScope.() -> Unit)?` parameter (null in every non-debuggable build, so no Rec node exists there).
- `ContinuousScanEngine.kt` -- add `ContinuousFrameTap`, `ContinuousModelIdentity`, `record(tap)` (default `reset()`), `frameDone(...)` (default no-op) and the factory's defaulted `modelIdentity`; `create()` stays the SAM method.
- `ContinuousSessionWriterFactory.kt` (PR) -- keep as the writer port; `ContinuousSessionCapture.frameDone` takes primitives (no `ContinuousFrameGeometry` allocation per frame), `status` is read by the UI poller only.
- `ScanFrameSource.kt` `read()` Continuous branch -- time `engine.processFrame` with `System.nanoTime()`, then `engine.frameDone(sensorTimestampNs, analyzerNs, rotation, image.width, image.height, crop.left, crop.top, crop.right, crop.bottom)`; no allocation added.
- `GuidedScanActivity.kt` -- inject `Optional<QuickScanSessionFactory>`; create the session in the VM when `quickScanMode`; route Close/Back (`closeOrConfirm` L508-540), Reset, Scan face by face, Enter colors instead, Review and Correct, `onStop` and the detector-unavailable branch through the session hooks before acting; gate the completion hold L326-333 and `analyze()` L646 on `session.holdsAnalysis`/`holdsHandOff`.

**Debug package `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/` (new):** `ContinuousSessionWriter.kt`, `ContinuousSessionWriterModule.kt`, `RgbPng.kt`, `SessionLatency.kt` (moved from PR `recorder/session/`, latency fixes applied); `QuickScanSessionController.kt` (state machine Idle → Recording → Stopped(sheet) → Baseline → Saved(notice) → Labelling, adb command handling, receiver lifecycle); `QuickScanSessionUi.kt` (indicator, Rec, sheet, discard dialog, notice); `SessionLabeller.kt` (full-screen dialog, `colorLabel`, `truthRefusal`, pre-fill lock); `QuickScanSessionModule.kt` (binds `QuickScanSessionFactory` and `ContinuousSessionWriterFactory`). Tests in `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/`.


## Tasks & Acceptance

**Execution:**
- `Fixtures/continuous/synthetic-{complete,partial}-v1/*`, `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/session/ContinuousSession.kt`, `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/session/{ContinuousSessionJsonTest,SyntheticSessionGenerator}.kt` -- take from PR #396 (`git checkout <ref> -- <path>`); add `geometryChanges: List<SessionGeometryChange> = emptyList()` to the manifest (schema stays 1), `skipped: Boolean = false` to `ContinuousSessionFrame`, and `COMPLETE`/`RESET` to `SessionStopReason`; then regenerate the synthetic sessions with `:core:domain:generateSyntheticSessions` -- the session format and its golden data.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplay{,Main}.kt`, `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayTest.kt`, `core/domain/build.gradle.kts` (hunks: test input dir, `replayContinuousSession`, `generateSyntheticSessions`), `KotlinStickerReads.kt` KDoc hunk -- take from PR; replay skips the mapper for `skipped` frames and does not count them as gaps; the report names the starting stage, observed coverage and label mismatches; the golden test fails on any snapshot difference unless `--refresh-golden` was used -- state replay.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/{FixtureConventionPlugin,policy/FixtureSizePolicy,tasks/CheckFixtureSizeTask,tasks/GenerateBuildInfoTask}.kt` + tests, `build-logic/build.gradle.kts`, `gradle/libs.versions.toml`, root `build.gradle.kts` -- take from PR (`:checkFixtureSize` 15 MB/session, 60 MB for `Fixtures/continuous/`, wired into `check`) -- fixture size gate and build-info asset.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/{policy/FixtureWriterPolicy,tasks/CheckFixtureWriterTask}.kt`, `QualityConventionPlugin.kt` hunk, `ReleaseConventionPlugin.kt` hunk, `policy/CameraSizePolicy.kt`, tests `FixtureWriterPolicyTest`, `FixtureWriterCompositionFunctionalTest`, `CameraSizePolicyTest` L86, `ShadowReaderPolicyTest` L38, `CiWorkflowTest`, `.github/workflows/ci.yml` hunk -- take from PR with `CLASS_PACKAGE = "dev/cubentia/app/camera/session/"` and `debugOnlyClassGlobs = listOf("dev/cubentia/app/camera/sticker/**", "dev/cubentia/app/camera/session/**")`; the functional test plants a class in that package and fails release, benchmark, cornerR8 and release011 -- writer guards.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{ContinuousScanEngine,StickerCenterDetector,CameraModule,ContinuousSessionWriterFactory}.kt`, `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/{ContinuousScanEngineModule,ContinuousScanPipeline,LiteRtStickerCenterDetector}.kt` -- apply the PR tap hunks reshaped per Design Notes (SAM `create()` kept, `record(tap)`, `frameDone`, preallocated model-input fill, `skipped` frames recorded without a stale snapshot); add `QuickScanSessionFactory` `@BindsOptionalOf` -- neutral ports.
- `app/src/camera/kotlin/dev/cubentia/app/camera/QuickScanSessionPort.kt` (new), `ScanFrameSource.kt`, `GuidedScanActivity.kt`, `ContinuousQuickScanScreen.kt`, `GuidedScanScreen.kt` L319 -- add the session port and host hooks (Code Map "Port design"); timing + geometry hand-off in `read()`; no behaviour change when the optional is empty -- the Quick Scan integration.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/*` (new) -- writer (moved, with the zero-allocation analyzer path: 8 preallocated slots, `ArrayBlockingQueue`, one long-lived writer thread, reused `Deflater`, counted bytes), `SessionLatency`, controller state machine implementing answers 1-16 and W1-W10, UI, labeller, receiver (`ContextCompat.registerReceiver(context, receiver, IntentFilter("dev.cubentia.debug.RECORDER_SESSION"), "android.permission.DUMP", null, RECEIVER_EXPORTED)`, registered on session create, unregistered on VM clear), Hilt module -- the debug tooling.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/{ContinuousSessionWriterTest,ContinuousSessionWriterBindingTest,QuickScanSessionControllerTest,QuickScanSessionReceiverTest,TruthRefusalTest,QuickScanRecGeometryTest}.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousFrameTapTest.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreenTest.kt` (Rec absent with a null overlay) -- tests for the ACs below.
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/ContinuousImageReplayDeviceTest.kt`, `LiteRtStickerCenterDetectorDeviceTest.kt` (`copyModelInput` test hunk) -- take from PR; compile only here (no device run).
- `tools/cube-session.sh` -- add only the `session` subcommand (start/stop/save/discard/baseline with `--es cube/lighting/partition/label`, applicationId `com.exxerpro.cubentia`) and the pull of `files/continuous-sessions/`; keep HEAD's other subcommands.
- `Fixtures/README.md`, `Fixtures/continuous/README.md` -- operator procedure (Rec, sheet, baseline, notice, labeller, adb), fixture format, replay and golden-refresh commands, `replayContinuousSession` as the replacement for the Quick Scan use of `exportKotlinStickerReads`, partition rule.
- `docs/evidence/10-34/latency.md` (new template: device, build commit, baseline p50/p95, capture p50/p95, skips, verdict, filled by the PO) and `docs/evidence/10-35/legacy-replay.md` (append the continuous-session replay row with this run's result).

**Acceptance Criteria:**
- Given the debug Quick Scan with a session bound, when Rec is tapped, then the map clears, the indicator shows "● REC n · k skips", Rec reads "Stop", and every frame `engine.processFrame` returns is either written or counted in `captureSkips` (`ContinuousFrameTapTest`, `ContinuousSessionWriterTest`).
- Given an `ImageProxy` of 640x480 with a full-frame crop and rotation 90, and one of 1280x960 with a viewport crop, when it goes through `CameraXFrameSource.read()` into the real `ContinuousScanEngineModule.factory` engine with a recording tap and a detector stub that computes its params with `StickerCenterModelTransform.uprightDimensions`/`letterbox`, then the recorded geometry (crop, upright size, scale, pads) equals the crop the engine received and the params the detector used for that frame, and for 640x480 rotation 90 full crop the upright size is 480x640 with scale 0.4, padLeft 32, padTop 0 (`QuickScanRecGeometryTest`).
- Given the analyzer thread, when a frame is captured, then no object is allocated per frame on that thread beyond the slot reuse (test counts slot allocations = 8 for 600 frames) and a full queue increments `captureSkips` (`ContinuousSessionWriterTest`).
- Given a stopped session, when the sheet is shown, then swipe, outside tap and Back do not close it, Discard… asks "Discard this recording?", Save writes the group and shows "Saved <id>" (with "· defective: <reasons>" when skips > 0 or capture p50 > 1.10 × baseline p50), and a missing baseline writes `latencyBaselineP50Ms = null` with the sheet reading "No baseline yet" (`QuickScanSessionControllerTest`).
- Given recording, when the scan completes, the cap is hit, Reset is tapped, or the screen is left (Close/Back, Scan face by face, Enter colors instead, Review and Correct, background, detector unavailable), then the controller follows answers 4-7, 13 and 14 exactly (one test per path in `QuickScanSessionControllerTest`), including: complete → sheet before hand-off, Save holds hand-off until notice dismissed or labeller closed, Discard continues at once; leaving with the sheet unanswered deletes the `.tmp-*` directory.
- Given the baseline run, when it ends after 30 s, then a per-device p50/p95 is stored, the pending session's map, count, cue and hold return with the sheet showing the value; Reset/Scan face by face/Review and Correct/Enter colors instead are disabled during it; Close/Back/background/detector failure store nothing (`QuickScanSessionControllerTest`).
- Given adb broadcasts, when a command arrives in a state whose control is hidden, then it is ignored and logged under `QuickScanSession`; save/discard while recording stop first; an adb `start` shows the same indicator as Rec; the receiver requires `android.permission.DUMP`, so a sender without it is not delivered (`QuickScanSessionReceiverTest`, Robolectric).
- Given a save, when the group is written, then files stream to `files/continuous-sessions/.tmp-<id>/` and become `<id>/` only by a final rename after the manifest; an interrupted write (writer killed before rename) leaves only `.tmp-*` and is never listed as a session; the manifest carries every AC field, `imageEvery = 3` and a 256x256 lossless PNG for every third frame (`ContinuousSessionWriterTest`).
- Given the labeller, when labels are saved, then they go to `labels.json` beside (not inside) predictions, unknowns are `null`, `StickerTruthCheck` decides PASSED / OVERRIDDEN (non-empty reason) / REFUSED, pre-filled stickers count only once tapped, and `truthRefusal` reads e.g. "Not saved: White 10 (over) · Blue 8 (under) · 2 unknown" (`TruthRefusalTest`).
- Given a non-debuggable variant, when `check<Variant>FixtureWriter` runs, then a planted class in `dev/cubentia/app/camera/session/` fails it, and `ContinuousQuickScanScreen` with a null overlay has no node tagged `quickScan:rec` (`FixtureWriterCompositionFunctionalTest`, `ContinuousQuickScanScreenTest`).
- Given the checked-in synthetic sessions, when `:core:domain:test` runs, then state replay reproduces every recorded snapshot exactly, the complete session yields its net in canonical face order compared against `labels.json`, and the partial one yields its last confirmed net without filling unknowns (`ContinuousSessionReplayTest`).

## Spec Change Log

## Review Triage Log

### 2026-11-26 — Review pass
- verdicts: 50 findings — high 0, medium 17, low 25, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) A failed Save cannot be retried or discarded: the writer's `command` stays set after a failed command, so the next save/discard throws and `.tmp-*` leaks — confirmed at `ContinuousSessionWriter.kt` run() L317-329 and runWriter() L357-364; fixed: a failed command is cleared so Save retries / Discard runs inline.
  - `[low]` `[reject]` (blind) Session ids are per-second and can collide in an adb start/stop/save loop within one second — real but needs two sessions saved within the same second; with the failed-save fix above a collision is recoverable by Discard; the fix adds a uniquifier branch.
  - `[medium]` `[patch]` (blind) The held hand-off is never released after Discard when the capture's first stop reason was a cap/failure — confirmed: `answered()` L735-739 keys on `stopped.reason`, `stopTo()` L661 stores the writer's first reason; fixed: release/Reset keyed on `handOffHeld` and a reset-requested flag.
  - `[false]` `[reject]` (blind) Stop reasons too coarse (background, leaving, write failure all `OPERATOR`) — this is the spec's settled W2/W7 choice; changing it means editing this build's spec.
  - `[medium]` `[patch]` (blind) Orphaned `.tmp-*` directories after process death are never removed — confirmed (only `release()`/discard delete them); a Studio re-run with the sheet open leaves up to 40 MB each; fixed: one `.tmp-*` sweep when the factory is first created in a process.
  - `[low]` `[reject]` (blind) The latency baseline is keyed by device model only, so it can go stale across builds — real, but the operator re-runs the 30 s baseline from the sheet; storing and matching build identities adds new state and branches.
  - `[low]` `[reject]` (blind) Build provenance has no dirty-tree marker (synthetic manifests stamp the baseline commit) — cosmetic provenance; the synthetic sessions say `synthetic: true` and the fix adds a describe/dirty path.
  - `[low]` `[patch]` (blind) Replay CLI: `--session` with no value throws, unknown flags become the root, zero sessions exit 0 — confirmed in `ContinuousSessionReplayMain.kt` L17-25; fixed: usage error and non-zero exit for each.
  - `[medium]` `[patch]` (blind) `--refresh-golden` accepts a non-exactly-replayable session and overwrites the recorded outcome — confirmed `ContinuousSessionReplay.kt` L184-200; fixed: refuses such a session and keeps the recorded outcome.
  - `[low]` `[reject]` (blind) Snapshot diffs paired by position (`zip`) instead of `frameIndex` — skipped frames write no snapshot line and replay emits none for them, so the lists stay aligned for any session the writer produces; only a hand-corrupted file misaligns.
  - `[medium]` `[patch]` (blind) The real `GuidedScanViewModel` host implementation is untested (all tests use a fake host) — same root as the verification-gap finding below; fixed by the route tests.
  - `[false]` `[reject]` (blind) No replay checks the lattice fitter and sampler against recorded observations — the story's ACs define state replay from the mapper and image replay of detector outputs only; the report names the starting stage.
  - `[low]` `[reject]` (blind) A stopped capture's tap stays attached to the engine until the next Rec/Reset — the stopped writer returns null buffers and drops frames without I/O; detaching needs `record(null)`, which would reset the map the sheet keeps.
  - `[low]` `[reject]` (blind) `MODEL_IDENTITY.delegate` is hardcoded `XNNPACK` — PR #396 content; the detector builds its interpreter with that delegate configuration; a runtime fallback probe would add surface.
  - `[low]` `[reject]` (blind) `:checkFixtureSize` lets `.tmp-*`/manifest-less directories escape the per-session limit and measures the working tree — curated sessions are complete groups copied by hand; the 60 MB total still bounds the folder.
  - `[medium]` `[patch]` (verification-gap) Activity/view-model session wiring (hooks, holds, baseline engine swap) is never run with a recording session — pre-verified; fixed: route tests in `ContinuousQuickScanRouteTest` with the real debug session.
  - `[medium]` `[patch]` (verification-gap) The real detector's `copyModelInput`/`inputValid` and letterbox fields are covered only by a never-run device test — pre-verified; fixed: JVM cases in `LiteRtStickerCenterDetectorFailureTest`.
  - `[medium]` `[patch]` (verification-gap) The "8 slots for 600 frames" test cannot detect per-frame allocation — pre-verified; fixed: with the writer blocked, the 9th frame gets no buffer and is counted in `captureSkips`.
  - `[medium]` `[patch]` (verification-gap) Sheet non-dismissal by Back/outside tap is not tested — pre-verified; fixed: Compose test presses Back and taps outside.
  - `[low]` `[patch]` (verification-gap) Review and Correct hidden during the baseline run is not asserted — pre-verified (filed defer); a one-line assertion, so patched.
  - `[medium]` `[patch]` (verification-gap, other) Close during a baseline run judges the baseline's map — confirmed: `endBaseline` restores `keptFlow` inside the coroutine while `closeOrConfirm` reads `scan.continuous` at once; fixed: the flow is restored synchronously on main.
  - `[medium]` `[patch]` (edge) Completion after a cap/failure: Discard never releases the hand-off — same root as the blind hand-off finding; fixed there.
  - `[medium]` `[patch]` (edge) Reset after a cap already stopped the writer is lost — same root (`answered()` keyed on the writer's first reason); fixed there.
  - `[low]` `[patch]` (edge) `discardQuietly()` after `release()` shut `io` down throws `RejectedExecutionException` on main — confirmed (`QuickScanSessionModule` onRelease calls `io.shutdown()`); a crash, so the direct guard (inline discard) is applied.
  - `[low]` `[reject]` (edge) `release()` while `Starting` after `startCapture` attached the tap leaks the writer — needs the view model cleared inside the few-ms window between `startCapture` and its callback; the fix adds a new pending-capture state.
  - `[low]` `[reject]` (edge) Backgrounded while `Starting` starts recording in the background — same few-ms window; `onStop` then a resume shows the indicator; the fix adds cancellation state.
  - `[low]` `[patch]` (edge) View model cleared during `Busy(SAVING)` before the export callback leaves `.tmp-*` and a live writer thread — confirmed `release()` L344; Save-then-Back is plausible; fixed with a save-started flag.
  - `[medium]` `[patch]` (edge) Save retry throws "already saved or discarded" — same root as the blind failed-save finding; fixed there.
  - `[low]` `[reject]` (edge) Same-second session ids collide — same as the blind row; rejected for the same reason.
  - `[low]` `[reject]` (edge) Writer thread dying between `isAlive` and `command` assignment blocks forever — the thread only exits after running a command or on interrupt (test-only `kill()`), and `command` is checked first; not reachable in use.
  - `[low]` `[reject]` (edge) Engine changes between stop and Save alter the derived outcome — analysis is held while the sheet is open; only a calibration saved from Review and Correct restarts the engine, a rare debug path.
  - `[low]` `[reject]` (edge) Frames the mapper saw after the last recorded frame (cap noticed up to 250 ms late) are not in the session — only the manifest `outcome` can differ, and the replay report prints recorded vs replayed outcome; holding analysis from the writer thread would add a new cross-thread signal.
  - `[medium]` `[patch]` (edge) `--refresh-golden` on a non-exact session — same as the blind row; fixed there.
  - `[low]` `[patch]` (edge) `--session`/`--report` without a value throws — same as the blind CLI row; fixed there.
  - `[low]` `[reject]` (edge) `cellRgbs`/net length not validated — only hand-edited fixtures can trip it; the writer always writes full arrays.
  - `[low]` `[reject]` (edge) A newer-schema manifest gives a generic `SerializationException` — schema is 1 with no newer writer; cosmetic message.
  - `[low]` `[reject]` (edge) A 30 s baseline with very few frames stores an unreliable p50 — a live camera at 30 fps gives ~900 frames; a minimum-frames rule adds a new failure path.
  - `[low]` `[reject]` (edge) `:checkFixtureSize` per-session limit skips `.tmp-*`/manifest-less directories — same as the blind row.
  - `[low]` `[reject]` (edge) `GenerateBuildInfoTask` fails configuration when git is not installed — the repo and CI always build from a git checkout; `isIgnoreExitValue` covers non-git directories.
  - `[low]` `[reject]` (edge) `tools/cube-session.sh` does not reject shell metacharacters in `--cube`/`--lighting` — operator's own input on their own device; quoting hardening is beyond a direct correction.
  - `[medium]` `[patch]` (edge, claim) Frames queued when `dropRest` is set are neither written nor counted — confirmed `runWriter()` L350; fixed: each dropped slot counts in `captureSkips`.
  - `[medium]` `[patch]` (edge, claim) Discard of a completed scan stays held after an earlier writer stop reason — same root as the hand-off row; fixed there.
  - `[low]` `[patch]` (edge, claim) Leaving during `Busy(SAVING)` can orphan the temp directory — same root as the save-started row; fixed there.
  - `[false]` `[reject]` (intent) Geometry test supplies frame sizes/crops instead of exercising `analysisTarget`/`ViewPort` — the intent requires geometry "taken from the frame Quick Scan actually processed"; the recording reads `cropRect` in the same `CameraXFrameSource.read()` call the Learner path uses, which the test drives with real `read()` and the real engine factory.
  - `[medium]` `[patch]` (intent) Integration with the real screen (`GuidedScanActivity` quick mode) is exercised only through fake hosts — same root as the verification-gap wiring finding; fixed by the route tests.
  - `[false]` `[reject]` (intent) No test inspects a non-debug Hilt graph for `Optional.empty()` — `@BindsOptionalOf` with no binding is empty by construction, and any class in the session package fails `check<Variant>FixtureWriter` for every non-debuggable variant.
  - `[false]` `[reject]` (intent) `frameDone` timing in shipped `read()` may allocate per frame — it passes only primitives and `System.nanoTime()`; nothing is allocated.
  - `[false]` `[reject]` (intent) The planted-class failure is shown on a synthetic Gradle fixture, not the real app — that is the established `ShadowReader` guard pattern; CI runs the real `check*FixtureWriter` tasks for all four variants.
  - `[false]` `[reject]` (intent) Instrumented tests added / golden replay runs under `check` (stricter readings) — the intent lists image replay among the cherry-picks and only forbids wiring the `replayContinuousSession` task into `check`; the golden JVM test is required by the ACs.
  - `[false]` `[reject]` (intent) Shipped files change (neutral ports and null-guarded hooks) — the intent requires optional bindings and neutral ports in shipped code; with the optional empty every hook is skipped.

## Design Notes

- **Tap seam.** `ContinuousScanEngine` gains `record(tap: ContinuousFrameTap?)`. It defaults to `reset()`. `PipelineEngine` overrides it to set the tap, zero the frame index and reset the pipeline. The factory stays a `fun interface` with `create()`; the PR's `create(tap)` is not taken. It gains a defaulted `modelIdentity`. This keeps the SAM fake in `ContinuousQuickScanRouteTest.kt:130` compiling.
- **Zero allocation on the analyzer thread.** The tap hands the pipeline a preallocated model-input buffer to fill directly, so there is one copy instead of the PR's two. The frame is staged into one of 8 preallocated mutable slots. Each slot holds references to the frame's immutable observations, snapshot and peaks, plus primitives. The slot is offered to an `ArrayBlockingQueue`; there is no `executor.execute` per frame. A long-running writer thread `take()`s slots. If the slots or the queue run out, the frame is counted in `captureSkips`. Counters are atomics/volatiles that the UI polls at about 4 Hz. On the writer thread, one `Deflater` is reused and bytes are counted without `toByteArray()`.
- **Timing and geometry.** `ScanFrameSource.read` times `engine.processFrame` with `System.nanoTime`. It passes the sensor timestamp, analyzer time, rotation, image size and `cropRect` to the active tap as primitives through `ContinuousScanEngine.frameDone(...)`, which defaults to a no-op. The letterbox comes from the `StickerCenterDetection` (`inputScale`/`inputPadLeft`/`inputPadTop`, PR hunk), that is, from the params the detector actually used. A frame whose geometry differs from the session's is appended to a new manifest list `geometryChanges: [{frameIndex, geometry}]` (default empty, schema stays 1). This can happen after a rotation, since the activity is not orientation-locked. The synthetic golden manifests are then regenerated.
- **Frames the pipeline skips** (invalid crop, or the detector unavailable). The mapper is untouched for these. The tap writes a `frames.jsonl` line with `skipped: true` and no observations, and no snapshot line. Replay does not call the mapper for such a line and does not count it as a gap. This fixes the PR bug where a stale `lastSnapshot` was recorded while replay called `processFrame(idx, [])`.
- **Receiver.** `ContextCompat.registerReceiver(..., "android.permission.DUMP", null, RECEIVER_EXPORTED)` registers it from the debug binding while Quick Scan is open. `adb shell` holds DUMP; a third-party app cannot get it. The action stays `dev.cubentia.debug.RECORDER_SESSION` with extra `command` and optional `label`, plus the extras from answer 12.
- **Package and guards.** Everything debug-only lives in `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/`. `FixtureWriterPolicy.CLASS_PACKAGE` is `"dev/cubentia/app/camera/session/"`. `CameraSizePolicy.debugOnlyClassGlobs` becomes `listOf("dev/cubentia/app/camera/sticker/**", "dev/cubentia/app/camera/session/**")`, and `CameraSizePolicyTest.kt:86` and `ShadowReaderPolicyTest.kt:38` are updated with it.


- **`frameDone` lives on `ContinuousFrameTap`** (primitives only: `sensorTimestampNs: Long, analyzerNs: Long, rotation: Int, imageWidth: Int, imageHeight: Int, cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int`); `ContinuousSessionCapture : ContinuousFrameTap`. The engine forwards `frameDone` to its active tap only; with no tap it does nothing.
- **Baseline engine:** `ContinuousScanEngineFactory.create()` gives the fresh no-capture engine for the 30 s run; the host swaps it in as `scan.engine`/`frameSource.continuousMode` and swaps the pending session's engine back afterwards (the pending engine is not reset, so its export, map and hold survive). Baseline timing uses the same `frameDone` path with a timing-only tap that records `analyzerNs` into a preallocated `LongArray`.
- **Holds:** `session.holdsAnalysis` is true while the sheet, discard dialog, labeller or a held hand-off is up; `analyze()` returns early on it (like `confirmDiscard`), the completion `LaunchedEffect` waits on `holdsHandOff`, and the `continuousMode` analysing lambda includes it.
- **Debug copy:** the debug UI's text is developer tooling (AD-15): plain Kotlin literals in the debug package, never `en.json` keys or `strings.xml`.
- **Existing files use CRLF** (e.g. `ContinuousScanEngine.kt`); keep each file's line endings.
- **The "evaluation-source-set JVM test"** is `core/domain/src/test/.../ContinuousSessionReplayTest.kt`: `:core:domain` tests compile against `evaluation.output` (`core/domain/build.gradle.kts:30`).
- **Binding ADs (verbatim):** AD-9 (Frames, amended SCP 2026-11-25 and SCP 2026-11-26), AD-15, AD-18. No known deviation.

## Verification

**Commands** (env per AGENTS.md: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`):
- `./gradlew spotlessApply` then `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (includes `:checkFixtureSize`, `checkReleaseFixtureWriter`, `checkBenchmarkFixtureWriter`, testDebug, Kover).
- `./gradlew -p build-logic test` -- expected: all pass, including `FixtureWriterPolicyTest`, `FixtureWriterCompositionFunctionalTest`, `CheckFixtureSizeTaskTest`, `CiWorkflowTest`.
- `./gradlew -Pcubentia.cornerR8=true :app:checkCornerR8FixtureWriter` and `./gradlew -Pcubentia.release011=true :app:checkRelease011FixtureWriter` -- expected: pass.
- `./gradlew :core:domain:replayContinuousSession` -- expected: both synthetic sessions replay with 0 differences; record the result in `docs/evidence/10-35/legacy-replay.md` beside one old fixture replay (e.g. `:core:domain:evaluateQuickScan`).
- `./gradlew :app:compileDebugAndroidTestKotlin` -- expected: compiles (device tests are not run here).
- `git diff --stat 79704f22fb32137afd007a308aa11f06cde3a485 -- app/src/main` -- expected: empty (no camera code in `app/src/main`); `git diff 79704f22fb32137afd007a308aa11f06cde3a485 -- core/domain/src/main/resources/content/strings/en.json` -- expected: empty.

## Auto Run Result

Planning history: round 1 and round 2 halted `blocked` on UI questions (Q1-Q16); all answered by the PO on 2026-11-26 and recorded above. Planning resumed 2026-11-26.

Status: done

**Summary.** The debug build's Solve-tab Quick Scan gains a debug-only Rec mode (indicator, Rec/Stop, a sheet that cannot be dismissed, a no-capture baseline run, a saved-session notice, a full-screen labeller and an adb receiver that requires `android.permission.DUMP`) in `dev.cubentia.app.camera.session`, reached only through `@BindsOptionalOf` ports (`QuickScanSessionFactory`, `ContinuousSessionWriterFactory`). Frames come from the same `CameraXFrameSource.read()` and engine the Learner uses, through a neutral `ContinuousFrameTap` (`record(tap)`, `frameDone` with primitives); the analyzer only copies into 8 preallocated slots and a bounded queue. Cherry-picked from PR #396 (ref tip 4be002d98a075f901478e736612444d115a8c8eb): session format and synthetic golden sessions (regenerated), state and image replay, `:checkFixtureSize`, build-info asset, writer guards (now naming `dev/cubentia/app/camera/session/`) and CI hunks. No device work was done; `docs/evidence/10-34/latency.md` is a template for the PO's Lenovo TB128XU run.

**Settled by worker under PO delegation (2026-11-26):** W1-W10 above (indicator text, stop reasons COMPLETE/RESET, derived outcome, sheet content, sheet presentation, session survives rotation, return-to-sheet, adb label/extras, notice placement, labeller Unknown). Further implementer calls in the same delegation: the sheet's cube/lighting "last values" are kept per app process; Close/Back etc. stop and the session is discarded only when the screen really closes (a cancelled "Discard this scan?" returns to the sheet); `ContinuousSessionWriterModule` folded into `QuickScanSessionModule`; `ContinuousQuickScanScreen` gained a defaulted `actionsEnabled` parameter to disable the four actions during the baseline run; orphaned `.tmp-*` folders are swept on the first `create()` of a process.

**Files changed** (all relative to the repo root):
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/ContinuousImageReplayDeviceTest.kt`
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtStickerCenterDetectorDeviceTest.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/ContinuousSessionWriter.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionController.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionModule.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionUi.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/RgbPng.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/SessionLabeller.kt`
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/SessionLatency.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/CameraModule.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousSessionWriterFactory.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/QuickScanSessionPort.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousFrameTapTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreenTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/ContinuousSessionWriterBindingTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/ContinuousSessionWriterTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/QuickScanRecGeometryTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionControllerTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionReceiverTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/QuickScanSessionUiTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/session/TruthRefusalTest.kt`
- `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt`
- `build.gradle.kts`
- `build-logic/build.gradle.kts`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/FixtureConventionPlugin.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/CameraSizePolicy.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FixtureSizePolicy.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FixtureWriterPolicy.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/QualityConventionPlugin.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/ReleaseConventionPlugin.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckFixtureSizeTask.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckFixtureWriterTask.kt`
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/GenerateBuildInfoTask.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CiWorkflowTest.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/FixtureWriterCompositionFunctionalTest.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/CameraSizePolicyTest.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/FixtureSizePolicyTest.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/FixtureWriterPolicyTest.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/ShadowReaderPolicyTest.kt`
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/tasks/CheckFixtureSizeTaskTest.kt`
- `core/domain/build.gradle.kts`
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplay.kt`
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayMain.kt`
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/sticker/KotlinStickerReads.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/session/ContinuousSession.kt`
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/session/ContinuousSessionJsonTest.kt`
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/session/SyntheticSessionGenerator.kt`
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayTest.kt`
- `docs/evidence/10-34/latency.md`
- `docs/evidence/10-35/legacy-replay.md`
- `Fixtures/continuous/README.md`
- `Fixtures/continuous/synthetic-complete-v1/frames.jsonl`
- `Fixtures/continuous/synthetic-complete-v1/labels.json`
- `Fixtures/continuous/synthetic-complete-v1/manifest.json`
- `Fixtures/continuous/synthetic-complete-v1/snapshots.jsonl`
- `Fixtures/continuous/synthetic-partial-v1/frames.jsonl`
- `Fixtures/continuous/synthetic-partial-v1/labels.json`
- `Fixtures/continuous/synthetic-partial-v1/manifest.json`
- `Fixtures/continuous/synthetic-partial-v1/snapshots.jsonl`
- `Fixtures/README.md`
- `.github/workflows/ci.yml`
- `gradle/libs.versions.toml`
- `tools/cube-session.sh`

Key ones: `app/src/camera/kotlin/dev/cubentia/app/camera/QuickScanSessionPort.kt` (session port), `ContinuousScanEngine.kt` (tap seam), `ScanFrameSource.kt` (timing + geometry to the tap), `GuidedScanActivity.kt` (hooks, holds, host), `app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/*` (writer, controller, UI, labeller, module), `build-logic/.../FixtureWriterPolicy.kt` + `CameraSizePolicy.kt` (guards), `core/domain/.../session/ContinuousSession.kt` and `evaluation/continuous/ContinuousSessionReplay*.kt` (format and replay).

**Review findings:** 50 (blind 15, edge 22, verification gap 6, intent alignment 7) — high 0, medium 17, low 25, false 8.
- Patched (14 fixes covering 23 rows): hand-off/Reset keyed on the writer's first stop reason; failed Save not retryable; frames dropped at a cap not counted in `captureSkips`; `discardQuietly` after `io` shutdown; leaving during Save before export; orphaned `.tmp-*` sweep; Close during baseline judged the wrong map; `--refresh-golden` on non-exact sessions; replay CLI arg errors; route tests with the real debug session; JVM detector `copyModelInput`/letterbox test; non-vacuous slot test; sheet Back/outside-tap test; Review and Correct hidden during baseline.
- Deferred: none.
- Rejected (27), each with its reason in the Review Triage Log: per-second session id collision; coarse stop reasons (spec W2); baseline keyed by model only; no dirty-tree marker; snapshot diff by position; no fitter/sampler replay (false); tap left attached after stop; hardcoded XNNPACK identity; size gate ignores `.tmp-*` dirs (x2); `Starting`-window leaks (x2); writer-thread race; engine change between stop and Save; tail frames after a late-noticed cap; fixture field validation; newer-schema message; few-frame baseline; git missing at configuration; shell metacharacters in `cube-session.sh`; and the intent-alignment divergences judged false (geometry pass-through, empty optional graph, no allocation in `frameDone`, synthetic-fixture guard test, stricter readings, neutral shipped ports).

**Follow-up review recommendation:** true. Patched by verdict: medium 10 entries, low 4 entries (first pass; two or more medium patched). Named unverified risk: the `GuidedScanActivity`/`GuidedScanViewModel` session wiring (engine swap for the baseline run, holds, completion gate) is now covered by Robolectric route tests written during the patch round and has not been reviewed again; on-device Back on the sheet, latency and capture skips stay unverified until the PO's Lenovo gate.

**Verification (after patches, 2026-11-26):**
- `./gradlew spotlessCheck build` — BUILD SUCCESSFUL (5 m 2 s), includes `:checkFixtureSize`, release/benchmark fixture-writer checks and testDebug.
- `./gradlew -p build-logic test` — BUILD SUCCESSFUL.
- `./gradlew -Pcubentia.cornerR8=true :app:checkCornerR8FixtureWriter`, `./gradlew -Pcubentia.release011=true :app:checkRelease011FixtureWriter` — BUILD SUCCESSFUL.
- `./gradlew :core:domain:replayContinuousSession` — both synthetic sessions, 0 snapshot/cue diffs.
- `./gradlew :app:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL (device tests not run).
- `git diff --stat 79704f22fb32137afd007a308aa11f06cde3a485 -- app/src/main core/domain/src/main/resources/content/strings/en.json` — empty.
- JUnit XML checked: every named test suite ran with 0 skipped and 0 failures.

**Residual risks:** the human device gate (Lenovo TB128XU session, latency with and without capture, one complete and one incomplete example) is still to run; the "no allocation on the analyzer thread" claim is proven by slot counting, not by an allocation profiler; the review diff left out generated golden data (`Fixtures/continuous/*/{frames,snapshots}.jsonl`, `labels.json`).
