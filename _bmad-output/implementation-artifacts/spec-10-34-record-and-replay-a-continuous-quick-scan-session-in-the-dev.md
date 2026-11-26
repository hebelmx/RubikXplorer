---
title: 'Story 10.34: Record and replay a continuous Quick Scan session in the developer fixture recorder'
type: 'feature'
created: '2026-11-26'
status: 'done'
baseline_revision: '62e9565945a57063e36a32108f8566ab9e99f95e'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Destroying FixtureRecorderActivity mid-recording (rotation, Back) orphans the continuous session's .tmp dir and writer thread.
    evidence: |-
      FixtureRecorderActivity.onDestroy shuts the analyzer and sessionIo executors but neither saves nor discards an active capture; the debug manifest declares no orientation lock. The other recorder modes already lose their remember state on recreation (pre-existing pattern), and the .tmp dir is never read as complete.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt:onDestroy
    severity: medium
  - summary: >-
      LiteRtStickerCenterDetector's inputScale/inputPadLeft/inputPadTop and copyModelInput are verified only by ContinuousImageReplayDeviceTest, which skips without pushed sessions.
    evidence: |-
      Unit tests use fakes (ContinuousFrameTapTest, ContinuousSessionWriterTest); LiteRT is Android-only. An instrumented case in androidTestDebug (detectRgb on a non-square RGB, assert letterbox and copied bytes) would settle it at the device gate.
    location: >-
      app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt
    severity: medium
  - summary: >-
      The legacy reader results written in docs/evidence/10-34/legacy-replay.md were produced by the implementer and not re-run during triage.
    evidence: |-
      Settle by re-running each command listed there (evaluateScanAccuracy, evaluateLiveBaseline, evaluateRecognitionApproaches, exportKotlinStickerReads, evaluateMatrix, LiveFixture decoding tests) and comparing with the recorded result; the doc already notes pre-existing output drift of exportKotlinStickerReads and evaluateMatrix.
    location: >-
      docs/evidence/10-34/legacy-replay.md
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The debug fixture recorder can only save two-hold corner groups, per-face live fixtures and two-corner bursts; none runs the continuous detector → lattice fitter → sampler → stateful mapper, so a continuous Quick Scan failure (face order, regrip, conflict across frames) cannot be reproduced offline or used to test a new detector on the same pixels.

**Approach:** Add a third recorder mode, *Continuous session*, that runs the Solve-tab `ContinuousScanEngine` pipeline and, through a neutral frame tap reached by an optional Hilt binding from the debug camera source set, streams every frame's observations and snapshot (and every Nth model input as PNG) to a bounded writer queue that finishes a versioned session group by atomic rename. Replay it two ways: state replay (`:core:domain:replayContinuousSession`, evaluation source set) and image replay (`ContinuousImageReplayDeviceTest`, instrumented debug). Guard the writer out of every non-debuggable variant (`FixtureWriterPolicy`), cap checked-in fixture size (`:checkFixtureSize`), and extract the Story 10.28 count/override rule into `StickerTruthCheck` in `:core:domain` main.

**Refs (verbatim from the story):** FR-22 · recognition NFRs (Confidence calibration), NFR-11 · AD-9 (Frames, amended SCP 2026-11-25), AD-15, AD-18 · Stories 10.12, 10.20, 10.28, 10.30–10.33 · `sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md`. Binding ADs: **AD-9** (Frames, amended SCP 2026-11-25), **AD-15**, **AD-18**. Start gate cleared: architecture `.memlog.md` records the architect/PO approval of the AD-9 Frames amendment and replay placement (2026-11-25). PO pulled 10.34 forward before 10.33 (allowed by the epic).

## Boundaries & Constraints

**Always:**
- AD-9: only `app/src/camera/**` touches pixels or LiteRT; only neutral data (observations, snapshots, peaks, geometry) enters `:core:domain`. The writer lives only in the debug camera source set (`app/src/camera/debug/kotlin`) in package `dev.cubentia.app.camera.recorder.session`; shipped `camera/kotlin` and `camera/continuous` code gains only a neutral `ContinuousFrameTap` interface + invocation, never file I/O, PNG encoding or a queue.
- AD-9 replay placement: state replay in `:core:domain` evaluation source set on neutral observations; image replay only as an instrumented debug test.
- AD-15: debug-only tool; not release scope; no Learner text (recorder copy stays inline in the debug source set as today; nothing in `en.json`/`strings.xml`).
- AD-18: continuous mode exists only when `QUICK_SCAN` composes `camera/continuous` (debug). Release flag levels unchanged.
- Continuous mode uses exactly the injected `ContinuousScanEngineFactory` pipeline (same `LiteRtStickerCenterDetector`, `FaceLatticeFitter`, `ContinuousFaceSampler`, `ContinuousFaceletMapper`) and never calls the `CornerDetector` provider (`Optional<CornerDetector>.get/orElse`) nor `CornerLocator`.
- Budgets (bytes are decimal, like NFR-11): writer queue 8 frames; frame cap 600; on-device session cap 40,000,000 B; PNG every N = 3 frames (N recorded); stored image = detector model input 256×256 RGB lossless PNG; checked-in limit 15,000,000 B per session dir and 60,000,000 B for `Fixtures/continuous/`. Defective = `captureSkips > 0` OR (a no-capture baseline exists for this device model AND capture p50 > 1.10 × baseline p50).
- Writes stream into `<externalFiles>/Fixtures/continuous/.tmp-<sessionId>/`; the group becomes complete only by renaming to `<externalFiles>/Fixtures/continuous/<sessionId>/` after the manifest is written last. Readers (replay, golden test, checkFixtureSize) ignore `.tmp-*` dirs and any dir without `manifest.json`.
- Truth is separate from predictions: labels go to `labels.json` (54 entries URFDLB row-major, each `color|null` + `source` + `reviewed`), predictions stay in `snapshots.jsonl`; `null` = explicit unknown.
- A session dir carries everything derived from it (labels, replay reports); `manifest.partition` is `TUNING | HELDOUT | UNASSIGNED` and applies to the whole dir.
- Story 10.28 behaviour of the two-hold recorder (refusal text, override dialog, `CornerCountCheck` results) is unchanged after delegating to `StickerTruthCheck`.
- Every commit subject carries "10.34".

**Never:**
- No change to `ContinuousFaceletMapper`/`FaceLatticeFitter` behaviour, release `ScanDiagnostics`, `app/src/main`, feature flag levels, Learner UI, or the Guided/Corner recorder modes beyond the `StickerTruthCheck` delegation.
- No writer, PNG, queue or file I/O in `camera/kotlin` or `camera/continuous`; no frame retention in shipped code.
- No invented device data: `docs/evidence/10-34/latency.md` Lenovo numbers and the curated real sessions are PO steps, marked as such.
- No Git LFS; no wiring `replayContinuousSession` into `check`; no edits to planning artifacts, `_bmad/`, `.claude/skills/`; no removal of legacy code (Story 10.35).
- No retrained/replaced model.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normal save | session stopped, operator taps Save | `.tmp-<id>` renamed to `<id>`; manifest outcome from final export | — |
| Discard | Discard (confirm dialog; adb `discard` no dialog) | temp dir deleted; nothing under `<id>` | delete failure logged; dir stays `.tmp-*` (never read as complete) |
| Interrupted write | process killed mid-session | only `.tmp-<id>` exists | ignored by all readers |
| Queue full | 9th pending frame | frame not captured, `captureSkips++`, session `defective` | — |
| Frame cap / size cap | 600 frames or 40 MB reached | capture stops automatically; `stopReason = FRAME_CAP | SIZE_CAP`; awaits Save/Discard | — |
| No observations | fitter finds no face, or invalid crop | entry kept with `observations: []`; snapshot = what the pipeline returned | — |
| Outcome | final `export()` + last snapshot | `COMPLETE` (Export.Complete), `FAILED` (TwistDetected), `LOST_TRACKING` (Partial and last snapshot has no tracked quads), else `PARTIAL` | — |
| Labels count fail | not 9 of each / unknowns | refused with existing refusal text; override only with non-empty trimmed reason → `OVERRIDDEN` + reason stored | blank reason refused |
| Replay drift | mapper changed at HEAD | per-frame diff lines (snapshot field + cue) and non-zero exit for the golden test | golden-refresh command is the only acceptance |
| Replay partial | PARTIAL/FAILED/LOST session | last confirmed net with `null` unknowns + last cue/conflict; unknowns never filled | — |
| Fixture too big | session dir > 15,000,000 B or root > 60,000,000 B | `:checkFixtureSize` fails naming the dir and bytes | — |
| Writer leaks to release | writer-package class in non-debuggable variant | `check<Variant>FixtureWriter` fails | — |

</intent-contract>

## Code Map

Paths under `/mnt/e/Dynamic/IndFun/RubikXplorer-10-34/`. `K/` = `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/`, `C/` = `app/src/camera/kotlin/dev/cubentia/app/camera/`, `DBG/` = `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/`, `D/` = `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/`, `E/` = `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/`, `BL/` = `build-logic/src/main/kotlin/dev/cubentia/buildlogic/`.

- `K/ContinuousScanPipeline.kt` -- `processFrame(frameIndex, frame, rotation, vw, vh)` L39–80: detect → `FaceLatticeFitter.fit` → `sampler.sample` → `mapper.processFrame(frameIndex, observations)`; local `observations` is the capture point; invalid-crop early return gives the empty entry. Shipped: add tap invocation only.
- `K/ContinuousScanEngineModule.kt` L15–42 -- factory `{ PipelineEngine(ContinuousScanPipeline(detector)) }`; private `PipelineEngine` owns `frameIndex++`.
- `C/ContinuousScanEngine.kt` -- base port `ContinuousScanEngine`/`ContinuousScanEngineFactory.create()`; place `ContinuousFrameTap` here (neutral).
- `C/CameraModule.kt` ~L20–60 -- `@BindsOptionalOf` modules pattern (`StickerCenterDetectorOptionalModule`, `ContinuousScanEngineOptionalModule`); add `ContinuousSessionWriterOptionalModule`.
- `C/StickerCenterDetector.kt` -- `StickerCenterPeak(x,y,score,viewX,viewY)` in upright px (model-input coords = `x*scale+padLeft`, `y*scale+padTop` via `K/StickerCenterModelTransform.letterbox` L54).
- `K/LiteRtStickerCenterDetector.kt` -- private `inputBytes` L58 (256×256×3); `detectRgb` L112 (image replay); `internal constructor(loadModel)` L42 (candidate model); XNNPACK 2 threads.
- `K/StickerCenterModelContract.kt` -- `MODEL_ASSET`, `MODEL_SHA256`, `INPUT_SIZE=256`, `NUM_THREADS=2`. LiteRT version only in `gradle/libs.versions.toml` L41 (`litert = "2.3.2"`); no runtime build commit today.
- `K/ContinuousFaceSampler.kt` -- `sampleRgb(rgb,w,h,face)` L74 for image-replay colour check.
- `DBG/FixtureRecorderActivity.kt` -- `@Inject Optional<CornerDetector>` L152; `analyzerExecutor` L154; `startCamera` L477 (`setOutputImageRotationEnabled(true)` → rotation 0 reaches pipeline; record it); `analyze` ~L561–643; burst receiver L200–206/L259–277 (`ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`).
- `DBG/FixtureRecorderScreen.kt` -- `FixtureRecorderScreen(camera: RecorderCamera)` L160; corner mode, `OverrideDialog` L1579, `saveOverride` L533, `recordCountCheck` callers L484/L539; `TruthEntry` editor model (tap sticker → palette, auto-advance; UX decision 2026-11-15).
- `DBG/RecorderRules.kt` -- `STICKERS_PER_COLOR` L774, `ColorCountCheck` L788, `colorCount` L797, `countRefusal` L804, `overrideReason` L842, `withCountCheck` L851–862 → move the format-neutral core to `StickerTruthCheck`.
- `DBG/RecorderBurst.kt` -- `ACTION = "dev.cubentia.debug.RECORDER_BURST"`, adb broadcast pattern to reuse; writes on analyzer thread (do not copy that).
- `DBG/FixtureExport.kt` -- direct-write helpers (no temp/rename; leave as is).
- `D/continuous/ContinuousTypes.kt` -- `QuadObservation` L62, `ContinuousScanCue` L103 (sealed, objects + data classes), `ContinuousScanSnapshot` L160 (`Scanning`/`TwistDetected`/`Complete` with `CubeState`), `ContinuousScanExport` L293. None `@Serializable`; `Face` is.
- `D/continuous/ContinuousFaceletMapper.kt` -- `processFrame` L210, deterministic, no-arg ctor.
- `D/LiveFixture.kt` -- `LiveFixtureJson`/`CornerFixtureJson` (versioned schema, private `Json{}`) — precedent for `ContinuousSessionJson`; `CornerCountCheck`, `CornerGroupManifest`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/SyntheticCubeStream.kt` -- source for the synthetic session fixture.
- `core/domain/build.gradle.kts` L26–113 -- `evaluation` source set + JavaExec template; `tasks.test` extra `inputs.files` precedent; evaluation code is tested from `src/test` via `testImplementation(evaluation.output)`.
- `E/sticker/KotlinStickerReads.kt` -- old Quick Scan exporter (document replacement).
- `BL/policy/ShadowReaderPolicy.kt`, `BL/tasks/CheckShadowReaderTask.kt`, `BL/QualityConventionPlugin.kt` L77–94 (`if (!variant.debuggable)` registration + `check` wiring) -- template for FixtureWriter; `BL/policy/CameraSizePolicy.kt` L8 `debugOnlyClassGlobs` (recorder/** already covers the new package).
- `BL/ReleaseConventionPlugin.kt` L112–150 -- source-set composition; `src/camera/debug/kotlin` only when debuggable.
- `build-logic/src/test/.../policy/ShadowReaderPolicyTest.kt`, `.../functional/ShadowReaderCompositionFunctionalTest.kt` (plants classes, `buildAndFail`), `.../functional/CiWorkflowTest.kt` L37–58 (CI markers).
- `.github/workflows/ci.yml` -- L43–75 dry-run gate list (L57), L80–81 build, L85–86 cornerR8 step; **no release011 step exists**.
- `build.gradle.kts` (root) -- plugins + spotless only; no `check` task.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerDetectorBindingTest.kt` (Hilt Robolectric binding test pattern); `.../recorder/RecorderRulesTest.kt`, `RecorderBurstTest.kt`.
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtStickerCenterDetectorDeviceTest.kt` -- pushed-dir + `assumeTrue` pattern (`/data/local/tmp/...`).
- `Fixtures/README.md`; `tools/sticker-model/holdout.json`; `docs/evidence/10-32/camera-size.md` §3 (retired burst replay: revision `52b66aefa63ab02f8ae6b08cc6a6e5d769756be8`, `QuickScanBurstReplayTest`); `tools/cube-session.sh` (recorder/pull helpers).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/StickerTruthCheck.kt` -- add format-neutral check: input 54 `StickerTruth(color: Color?, source: TruthSource, reviewed: Boolean)` (or the 10.28 hold labels flattened); output counts per colour, over/under, unknown count, `passed`; `decide(reason: String?)` → PASSED / OVERRIDDEN(trimmed non-empty reason) / REFUSED; refusal text identical to today's `countRefusal` -- shared rule both labellers call; JVM test `core/domain/src/test/.../StickerTruthCheckTest.kt` (54 stickers, 9 each, unknowns, over/under, provenance, blank/whitespace reason refused).
- `DBG/RecorderRules.kt` -- make `colorCount`/`countRefusal`/`overrideReason`/`withCountCheck` delegate to `StickerTruthCheck`; behaviour unchanged (existing `RecorderRulesTest` stays green).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/session/ContinuousSession.kt` (+ `ContinuousSessionJson`) -- versioned (`schemaVersion = 1`) serializable DTOs and codec: `ContinuousSessionManifest` (sessionId, device model/manufacturer, cube, lighting, partition, modelFile + modelSha256, litertVersion, delegate "XNNPACK", threads 2, buildCommit (full hash), frameCount, framesWithObservations, imageEvery N, imageFrames, frame timing summary + per-frame timestamps/analyzer ms in frames file, rotationDegrees, analysis size, crop/letterbox params, captureSkips, droppedFrames, stopReason, outcome, latency p50/p95, latencyBaselineP50 nullable, defective + defectReasons, bytes, goldenRevision nullable, synthetic flag); `ContinuousSessionFrame` (frameIndex, timestampMs, analyzerMs, observations, peaks in model-input coords, image file name nullable); snapshot/cue DTO round-trip with `ContinuousScanSnapshot`/`ContinuousScanCue` (incl. `Complete.state` via facelets string); `ContinuousSessionLabels` (54 entries, unknown = null, countCheck + overrideReason) -- one neutral format for writer, replay and tests; round-trip JVM test keeps Kover ≥ 80%.
- `C/ContinuousScanEngine.kt`, `K/ContinuousScanPipeline.kt`, `K/ContinuousScanEngineModule.kt`, `C/StickerCenterDetector.kt`, `K/LiteRtStickerCenterDetector.kt` -- add neutral `ContinuousFrameTap` (`wantsModelInput(frameIndex): Boolean`, `onFrame(frameIndex, observations, snapshot, detection, modelInput: ByteArray?)`) and `ContinuousScanEngineFactory.create(tap: ContinuousFrameTap? = null)`; detector gains a default-`false` `copyModelInput(into: ByteArray): Boolean` implemented by copying `inputBytes` -- capture seam with no writer in shipped code; Solve-tab callers unchanged (null tap).
- `C/CameraModule.kt` + `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/session/ContinuousSessionWriterModule.kt` -- `@BindsOptionalOf ContinuousSessionWriterFactory` in base; `@Binds` in debug -- AD-9 Frames optional binding.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/session/` (`ContinuousSessionWriter.kt`, `SessionLatency.kt`) -- implements `ContinuousFrameTap`: copies frame data on the analyzer thread into a bounded queue (capacity 8, `offer` non-blocking, full → skip counted), single writer executor streams `frames.jsonl`, `snapshots.jsonl`, `images/frame-<idx>.png` (256×256 RGB lossless) into `.tmp-<id>`, enforces 600 frames / 40,000,000 B (auto-stop with `stopReason`), `save()` drains, writes `manifest.json` last, renames to `<id>`; `discard()` deletes; computes p50/p95 and defective; reads/writes a per-device no-capture baseline (`Fixtures/continuous/.baseline-<deviceModel>.json`, outside any session dir) -- the capture writer.
- `DBG/FixtureRecorderActivity.kt`, `DBG/FixtureRecorderScreen.kt`, new `DBG/ContinuousRecorderAnalyzer.kt` -- add *Continuous* mode beside per-face and corner modes: inject `Optional<ContinuousScanEngineFactory>` + `Optional<ContinuousSessionWriterFactory>`; extract the per-frame branch into a testable `ContinuousRecorderAnalyzer` that in continuous mode calls only the engine (never the CornerDetector provider/CornerLocator); in continuous mode the analyzer measures per-frame analyzer ms; mode hidden when the engine optional is absent. UI per Design Notes. adb: new action `dev.cubentia.debug.RECORDER_SESSION` with `--es command start|stop|save|discard|baseline` (+ optional `--es label`), registered like the burst receiver -- operator entry points.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/ContinuousRecorderAnalyzerTest.kt` (Robolectric) -- continuous frames go through a fake engine; a `CornerDetector` fake that fails on any call is never touched; per-face and corner-mode branches still call their paths (CornerReader with the provider) unchanged.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/session/ContinuousSessionWriterTest.kt` (JVM/Robolectric) -- manifest fields, empty-observation entries kept, PNG every 3rd frame decodes to 256×256, queue full → skip + defective, frame cap 600 + byte cap, interrupted write leaves only `.tmp-*` (never complete), discard deletes, rename makes complete, latency defective rule (>10% over baseline); `app/src/testDebug/.../camera/ContinuousSessionWriterBindingTest.kt` -- Hilt optional present in debug.
- `BL/policy/FixtureWriterPolicy.kt`, `BL/tasks/CheckFixtureWriterTask.kt`, `BL/QualityConventionPlugin.kt` -- `CLASS_PACKAGE = "dev/cubentia/app/camera/recorder/session/"`, `violations(classes)`; register `check<Variant>FixtureWriter` in the `!variant.debuggable` block and wire into `check` -- AD-9 Frames gate; `build-logic/src/test/.../policy/FixtureWriterPolicyTest.kt` (beside `ShadowReaderPolicyTest`) + functional test cases (copy `ShadowReaderCompositionFunctionalTest`: release/benchmark/release011/cornerR8 pass; planted `src/release/java/dev/cubentia/app/camera/recorder/session/Planted.java` fails).
- `BL/policy/FixtureSizePolicy.kt`, `BL/tasks/CheckFixtureSizeTask.kt`, root `build.gradle.kts` -- root `:checkFixtureSize` (apply `base` at root so `./gradlew check`/`build` run it): fail when any session dir under `Fixtures/continuous/` > 15,000,000 B or the root > 60,000,000 B (missing root passes) -- CI size gate without LFS; `FixtureSizePolicyTest`.
- `.github/workflows/ci.yml`, `build-logic/src/test/.../functional/CiWorkflowTest.kt` -- add `:app:checkReleaseFixtureWriter :app:checkBenchmarkFixtureWriter :checkFixtureSize` to the dry-run gate list, `:app:checkCornerR8FixtureWriter` to the cornerR8 step, and a new ungated step `./gradlew -Pcubentia.release011=true :app:checkRelease011FixtureWriter`; markers asserted (no `if:`/`continue-on-error`).
- `E/continuous/ContinuousSessionReplay.kt` + `ContinuousSessionReplayMain.kt`, `core/domain/build.gradle.kts` -- `replayContinuousSession` JavaExec (group verification, evaluation classpath, `workingDir = rootDir`, default arg `Fixtures/continuous`, `--session <dir>`, `--report <file>`, `--refresh-golden`), not wired into `check`: fresh mapper per session; per-frame diffs of snapshot fields and cue stream vs recorded; report names starting stage (`mapper (recorded observations)`), observed coverage (frames with observations, sides mapped, committed stickers), outcome, final/last-confirmed net in canonical URFDLB order with unknowns as `null`, last cue/conflict, label mismatches per sticker vs `labels.json` (unknown labels reported, not compared); `--refresh-golden` rewrites `snapshots.jsonl` from HEAD and sets `goldenRevision` = full HEAD hash; add golden fixture to `tasks.test` inputs.
- `Fixtures/continuous/synthetic-complete-v1/`, `Fixtures/continuous/synthetic-partial-v1/` + generator `core/domain/src/test/kotlin/.../continuous/session/SyntheticSessionGenerator.kt` -- **synthetic, honestly labelled** sessions (`synthetic: true`, device `"synthetic (SyntheticCubeStream)"`, no images, partition `UNASSIGNED`, labels from the generator's known state) generated from `SyntheticCubeStream`; generation command documented -- stand-in for the curated device session until the PO human gate.
- `core/domain/src/test/kotlin/.../evaluation/continuous/ContinuousSessionReplayTest.kt` -- golden: replay of the checked-in session reproduces recorded snapshots and cue stream exactly; complete → recorded net == labels in canonical order; partial → last confirmed net, unknowns stay null, cue/conflict reported; tampered snapshot → reported diff; temp/manifestless dirs ignored.
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/ContinuousImageReplayDeviceTest.kt` -- reads sessions pushed to `/data/local/tmp/cubentia-10-34-sessions/` (`assumeTrue` skip when none); for each PNG frame runs `detectRgb` with the recorded model (SHA must match manifest) and asserts same center count, each within 2 px (model-input coords) after nearest matching, same per-sticker colour; then runs a candidate model (`/data/local/tmp/cubentia-10-34-candidate.tflite` via `internal constructor(loadModel)`, skipped if absent) and logs/reports differences without failing.
- `Fixtures/continuous/README.md`, `Fixtures/README.md`, `E/sticker/KotlinStickerReads.kt` KDoc, `tools/cube-session.sh` -- operator procedure (start recorder, baseline run, adb trigger commands, save/discard, label, pull `files/Fixtures/continuous`, curate into `Fixtures/continuous/`, partition), fixture format, replay commands, golden-refresh step; `replayContinuousSession` documented as the replacement for the Quick Scan use of `exportKotlinStickerReads`.
- `docs/evidence/10-34/legacy-replay.md` -- run and record each legacy reader with result: `evaluateScanAccuracy`, `evaluateLiveBaseline`, `evaluateRecognitionApproaches`, `LiveFixture` decoding tests, `exportKotlinStickerReads` (`Fixtures/corner` + holdout.json), `evaluateMatrix`, `Fixtures/labeled` + photo-group readers (identify their tests/tasks); archived revision + command per legacy score incl. the retired burst replay (`52b66aef…`, `QuickScanBurstReplayTest`); one old fixture and one new continuous (synthetic) session replayed successfully; mark the real-device session replay as a PO step.
- `docs/evidence/10-34/latency.md` -- method (`baseline` vs capture session, same device, p50/p95 from manifests), table with **PO step: pending Lenovo TB128XU** placeholders (no numbers invented), and the human-gate checklist (one Lenovo session; PO picks one complete + one incomplete; copy to `Fixtures/continuous/`, run `:checkFixtureSize`, golden refresh, image replay).

**Acceptance Criteria:**
- Given the debug recorder in Continuous mode, when frames arrive, then they go through the injected `ContinuousScanEngineFactory` pipeline and the `CornerDetector` provider is never called, while per-face and corner modes behave as before (`ContinuousRecorderAnalyzerTest`, `testDebug`).
- Given a session in progress, when a frame is captured, then data is copied into the 8-frame queue drained by a separate writer executor, a full queue skips and counts `captureSkips`, and skip or >10% p50 over the device baseline marks `defective` (`ContinuousSessionWriterTest`).
- Given a saved session, when the group is written, then the manifest and per-frame files contain every field in the story AC, empty-observation frames are kept, every 3rd frame has a 256×256 PNG, caps 600 frames / 40,000,000 B hold, and only the final rename makes the group complete (`ContinuousSessionWriterTest`, `ContinuousSessionJsonTest`).
- Given `release`, `benchmark`, `cornerR8`, `release011`, when `check<Variant>FixtureWriter` runs, then it passes on the tree and fails on a planted writer-package class; it is in `check` for every non-debuggable variant and CI runs it for cornerR8 and release011 (`FixtureWriterPolicyTest`, functional test, `CiWorkflowTest`).
- Given labels, when confirmed, then `labels.json` holds truth separate from predictions with explicit unknowns, `StickerTruthCheck` (`:core:domain` main, JVM-tested) decides for both labellers, and refused/overridden/partial sessions stay on disk with their outcome and partition.
- Given `Fixtures/continuous/`, when `./gradlew check` runs, then `:checkFixtureSize` enforces 15,000,000 B per session and 60,000,000 B total.
- Given a recorded session, when `:core:domain:replayContinuousSession` runs, then it reports per-frame snapshot/cue diffs, starting stage, coverage, net (complete) or last confirmed net + cue/conflict (partial/failed), and label mismatches; it is not in `check`.
- Given the checked-in synthetic session, when `:core:domain:test` runs, then state replay reproduces its snapshots exactly and only `--refresh-golden` updates them.
- Given an image session on a device/emulator, when `ContinuousImageReplayDeviceTest` runs, then recorded-model replay matches count, ≤ 2 px and colour, and a candidate model's differences are reported (compiles in CI via `:app:compileDebugAndroidTestKotlin`; run is a PO step).
- Given the existing fixtures, when this story merges, then every legacy reader still runs and `docs/evidence/10-34/legacy-replay.md` lists commands, results and archived revisions.

## Spec Change Log

## Review Triage Log

### 2026-11-26 — Review pass
- verdicts: 48 findings — high 1, medium 13, low 22, false 11, maybe-false 1
- note: review diff excluded the generated synthetic data files (`Fixtures/continuous/*/*.jsonl`, `labels.json`); a test pins them to the generator output.
- findings:
  - `[medium]` `[patch]` (blind) Human-gate docs ran `--refresh-golden` before any plain replay, hiding device-vs-JVM drift — docs now require a plain replay first, archived as `docs/evidence/10-34/replay-<id>.md`, then refresh.
  - `[medium]` `[patch]` (blind) Sessions with skipped/missing frames replay with spurious diffs — Report gains `frameGaps`/`missingFrames`/`exactlyReplayable`; markdown prints "Not exactly replayable"; test added.
  - `[medium]` `[patch]` (blind) Size cap leaves holes in `frames.jsonl` — after SIZE_CAP every remaining queued frame is dropped; writer test added.
  - `[low]` `[patch]` (blind) Model-input pool (queue+1) too small for 8 queued + staged + encoding — pool sized queue+2.
  - `[medium]` `[patch]` (blind) Writer executor shutdown/leak gaps (rejected execute, shutdown only on success) — try/finally shutdown, rejected execute treated as not captured; onDestroy part deferred (see deferred).
  - `[low]` `[patch]` (blind) Manifest geometry fixed from a first EMPTY frame — geometry replaced when a frame with inputScale > 0 arrives; test added. Mixed peak spaces only occur with EMPTY detections (no peaks).
  - `[low]` `[reject]` (blind) `droppedFrames` underestimated under KEEP_ONLY_LATEST — estimate only; the exact sensor interval is unavailable and the fix needs new measurement plumbing; skips are counted exactly.
  - `[low]` `[patch]` (blind) No guidance from a 40 MB device session to the 15 MB checked-in limit — README/latency.md now say record about 200 frames or fewer.
  - `[low]` `[patch]` (blind) `bytes` computed differently by generator vs writer — generator counts like the writer; fixtures regenerated.
  - `[low]` `[reject]` (blind) Synthetic labels recorded as ENTERED — the manifest's `synthetic: true` marks the whole dir and every reader loads the manifest; a new TruthSource value would add public surface.
  - `[medium]` `[patch]` (blind) `synthetic-partial-v1` committed only 5 centres — regenerated from scenario `4_mid_solve_f2l_symmetric_regrip` cut at 96 frames (46/54 committed, 8 unknown); test asserts non-centre commits.
  - `[false]` `[reject]` (blind) Partial labels compared in an unanchored frame — prefill and comparison both use the same last snapshot net, so positions agree.
  - `[low]` `[reject]` (blind) Labelling only for the just-saved session, main-thread label I/O, labels after manifest, non-atomic refresh — labelling after Save is the spec's settled design; the rest are debug-tool niceties whose fixes add structure.
  - `[false]` `[reject]` (blind) Fixture-writer guard narrow — the AC defines the writer package; `recorder/` is only compiled for debuggable builds (ReleaseConventionPlugin), so the controller never reaches a non-debuggable variant.
  - `[false]` `[reject]` (blind) Session-id one-second collisions — the id stamps the session's start; two sessions cannot start in the same second because Start needs the previous one saved or discarded.
  - `[medium]` `[patch]` (edge) Skipped frame makes replay differ — grouped with the replay-gap patch above.
  - `[low]` `[reject]` (edge) Invalid-crop path feeds the tap without running the mapper — CameraX crop rects are never negative or empty in practice; the fix needs a format field.
  - `[medium]` `[patch]` (edge) Size cap writes later smaller frames — grouped with the size-cap patch.
  - `[medium]` `[patch]` (edge) Outcome derived from the last staged, not the last written, snapshot — outcome now from the last written frame (writer thread); test added.
  - `[medium]` `[patch]` (edge) IOException in `drainOne` crashes the writer thread — caught; session stops with defect `WRITE_FAILED: <msg>`; test added.
  - `[low]` `[patch]` (edge) `save` leaks the executor when `finish` throws — try/finally shutdown.
  - `[low]` `[reject]` (edge) Save failure strands the `.tmp` dir with no UI retry — rare debug-tool failure; the dir is never read as complete; retry flow adds states.
  - `[false]` `[reject]` (edge) Same-second session-id collision — see the matching blind row.
  - `[medium]` `[defer]` (edge) Activity destroyed mid-recording (rotation) orphans the session and its writer thread — the recorder's other modes already lose state on recreation (pre-existing pattern); deferred.
  - `[low]` `[patch]` (edge) `inputValid` set before inference succeeds — set only after `runInference` succeeds in both paths.
  - `[low]` `[reject]` (edge) No 54-length check on snapshot net strings — only hand-edited files trigger it; adds a guard.
  - `[low]` `[reject]` (edge) No size check on serialized observation RGB lists — hand-edited files only; adds a guard.
  - `[low]` `[reject]` (edge) `--session`/`--report` without a value crash the replay main — developer CLI misuse; adds a guard.
  - `[low]` `[reject]` (edge) `--refresh-golden` outside git stamps `unknown` — golden refresh is documented to run in the repo; adds a guard.
  - `[high]` `[patch]` (edge) Complete-session labels prefilled from the mapper-frame snapshot net while replay compares against the HOME-reoriented export (`ContinuousFaceletMapper.export` reorients to `Orientation.HOME`) — controller now prefills a Complete session from `export.facelets`; `ContinuousRecorderControllerTest` asserts it.
  - `[low]` `[patch]` (edge) `writeLabels` deletes the old labels before renaming — rename first, delete+rename only as fallback.
  - `[low]` `[patch]` (edge) Image replay silently skips a missing PNG — now fails with session id and image name.
  - `[low]` `[reject]` (edge) Debug build fails without a `git` binary — CI and dev checkouts always have git; the fix adds error handling.
  - `[low]` `[patch]` (edge) `cube-session.sh` passes the label unquoted — labels outside `[A-Za-z0-9-]` are rejected.
  - `[medium]` `[defer]` (verification-gap) Real detector letterbox fields and `copyModelInput` are checked only by the device test that skips without pushed sessions — LiteRT is Android-only; an instrumented case belongs with the device steps.
  - `[medium]` `[patch]` (verification-gap) `ContinuousRecorderController` untested — `ContinuousRecorderControllerTest` added (start gating, closed-screen commands, save→SAVED with canonical prefill, discard, writeLabels replace).
  - `[medium]` `[patch]` (verification-gap) Replay label scoring never exercised with wrong/unknown labels — replay test with one wrong and one null label added.
  - `[low]` `[patch]` (verification-gap) Build-commit test accepted `unknown` — binding test requires 40-hex inside a git checkout.
  - `[low]` `[patch]` (verification-gap, other) Geometry from a first EMPTY frame — grouped with the geometry patch.
  - `[low]` `[patch]` (intent) AC1 names a Robolectric test but `ContinuousRecorderAnalyzerTest` was plain JUnit5 — now runs under Robolectric. The recorder's camera configuration differing from `ScanFrameSource` is out of the AC's components and recorded in manifest geometry.
  - `[false]` `[reject]` (intent) Latency defective rule only with a stored baseline — the AC compares against "the no-capture run on the same device"; without that run there is nothing to compare, as the spec states.
  - `[false]` `[reject]` (intent) Writer port interfaces in shipped `camera/kotlin` — `@BindsOptionalOf` needs the port type in the base source set; they hold no I/O, and AD-9 Frames forbids a writer in shipped `continuous/` code, which has none.
  - `[false]` `[reject]` (intent) `StickerTruthCheck` ignores provenance — the 10.28 decision (`withCountCheck`) never used provenance either; provenance is carried in `StickerTruth` and stored in `labels.json`.
  - `[false]` `[reject]` (intent) "Starting stage" read as a pipeline stage — the spec settles this reading.
  - `[false]` `[reject]` (intent) Golden test in `src/test` on a synthetic session — evaluation code is tested via `testImplementation(evaluation.output)` (spec Code Map); synthetic stand-in per the PO clarification.
  - `[false]` `[reject]` (intent) Image replay only compiles — running it is a PO device step per the PO clarification.
  - `[maybe-false]` `[defer]` (intent) `legacy-replay.md` results not re-verified by triage — settle by re-running each listed command; see deferred.
  - `[false]` `[reject]` (intent) `epic-10-context.md` rewritten — it is this workflow's step-01 compiled context, regenerated because the planning files were newer.

## Design Notes

**Recorder UI (debug-only; settled from existing recorder conventions + UX memlog 2026-11-15 decisions):**
- Mode picker gains *Continuous* (shown only when the engine optional is present). Before Start the operator fills the existing device/cube/light fields and a partition chip (default `UNASSIGNED`).
- Buttons: *Start* → recording; *Stop* → stopped; then *Save* (primary) and *Discard* (set apart, confirm dialog "Discard this session?", per the UX rule that destructive recorder controls sit apart and confirm). *Baseline run* (no capture, 30 s or until Stop) stores the device's no-capture p50/p95; it writes no session.
- Status line while recording (plain, no animation): `Recording · frame N/600 · X.X MB · skips K · sides M/6`; after auto-stop it appends the stop reason (`frame cap` / `size cap`). After Save: `Saved <sessionId> · <outcome>[ · DEFECTIVE: reasons]`. No auto-play, no live net beyond the sides count.
- Labelling after Save (optional; skipping leaves no `labels.json`): a 54-sticker URFDLB net using the existing `TruthEntry` editor model (tap sticker → palette, auto-advance), prefilled from the session's last net with source `PREFILLED`, `reviewed=false`, exactly as the 10.28 corner flow does; blank stays an explicit unknown. *Save labels* runs `StickerTruthCheck`; refusal shows the existing refusal text and the existing `OverrideDialog` (reason required). Labels are written `labels.json.tmp` → rename.
- adb trigger reuses the burst receiver mechanism: `adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_SESSION --es command start` (`stop|save|discard|baseline`); requires Continuous mode open; adb `discard` skips the dialog.

**Outcome derivation** is automatic (no operator choice): see I/O matrix. A session the operator deliberately leaves invalid keeps its derived outcome plus labels `countCheck = OVERRIDDEN/REFUSED`.

**Capture cost:** the analyzer thread only copies (observations are small; model input 196,608 B memcpy into a pooled buffer when `frameIndex % N == 0`) and `offer`s; JSON/PNG encoding happens on the writer executor. Queue memory ≤ 8 × ~200 KB ≈ 1.6 MB.

**Image replay colour check:** for a PNG frame, fit the lattice on the replayed peaks and on the recorded peaks, `sampleRgb` the PNG at both, classify each cell to the nearest of that frame's centre-cell colours; colours must match. This compares like with like (same image) and avoids depending on full-resolution pixels that are not stored.

**Synthetic fixture:** no device session can be recorded in this build (human gate after code lands), so the golden test uses `synthetic-*-v1` sessions generated from `SyntheticCubeStream`, flagged `synthetic: true`. The PO's curated Lenovo sessions are added at the human gate via the documented golden-refresh step; synthetic sessions stay as the deterministic regression floor.

**Commit/LiteRT at runtime:** expose full `git rev-parse HEAD` and the catalog LiteRT version to the debug variant only (debug `BuildConfig` field or generated debug asset, configuration-cache safe via `providers.exec`); fall back to `"unknown"` outside git.

## Verification

**Commands:** (prefix `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-34 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk &&`)
- `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` -- expected: success (includes `:checkFixtureSize`, `check{Release,Benchmark}FixtureWriter`, Kover ≥ 80%).
- `./gradlew -p build-logic test` -- expected: success (FixtureWriterPolicyTest, FixtureSizePolicyTest, functional, CiWorkflowTest).
- `./gradlew -Pcubentia.cornerR8=true :app:checkCornerR8FixtureWriter :app:checkCornerR8ShadowReader` and `./gradlew -Pcubentia.release011=true :app:checkRelease011FixtureWriter` -- expected: success.
- `./gradlew :app:testDebugUnitTest --tests '*ContinuousRecorderAnalyzerTest' --tests '*ContinuousSessionWriter*' --tests '*RecorderRulesTest'` -- expected: pass.
- `./gradlew :core:domain:test --tests '*StickerTruthCheck*' --tests '*ContinuousSession*'` -- expected: pass.
- `./gradlew :core:domain:replayContinuousSession` -- expected: report for both synthetic sessions, zero diffs.
- `./gradlew :app:checkCameraSize` -- expected: pass (writer is debug-only).
- Legacy commands listed in `docs/evidence/10-34/legacy-replay.md` -- expected: each runs as before.

**Manual checks (PO steps, after code lands, before merge):**
- Lenovo TB128XU: baseline run, then one recorded session; fill `docs/evidence/10-34/latency.md`; PO picks one complete + one incomplete session, copies to `Fixtures/continuous/`, labels, runs golden refresh and `ContinuousImageReplayDeviceTest`.

## Auto Run Result

Status: done

**Summary:** The debug fixture recorder has a third mode, *Continuous session*. It runs the Solve-tab `ContinuousScanEngine` pipeline and never calls the `CornerDetector` provider. A neutral `ContinuousFrameTap` in shipped code feeds a debug-only writer (`dev.cubentia.app.camera.recorder.session`), reached through an optional Hilt binding (AD-9 Frames, amended SCP 2026-11-25; AD-15; AD-18). The writer uses an 8-frame bounded queue on its own executor. It counts skips and marks a session defective on a skip or on latency more than 10% over the device baseline. Caps are 600 frames and 40,000,000 B, with a 256×256 PNG every 3rd frame, and a session folder becomes complete only by a final rename after `manifest.json` is written. Sessions replay two ways: `:core:domain:replayContinuousSession` (state replay, evaluation source set, not in `check`) and `ContinuousImageReplayDeviceTest` (instrumented). The 10.28 nine-of-each-colour rule is now `StickerTruthCheck` in `:core:domain` main, called by both labellers. New gates: `FixtureWriterPolicy`/`check<Variant>FixtureWriter` in `check` for every non-debuggable variant (CI runs cornerR8 and a new release011 step), and a root `:checkFixtureSize` (15,000,000 B per session, 60,000,000 B total).

**Synthetic fixture (honestly labelled):** no device session could be recorded before the human gate. `Fixtures/continuous/synthetic-complete-v1` and `synthetic-partial-v1` are generated from `SyntheticCubeStream` (`synthetic: true`, synthetic device, no images) and are used by the golden state-replay test.

**PO steps (device-only, not done):** on the Lenovo TB128XU, a baseline run and one recorded session; fill in the numbers in `docs/evidence/10-34/latency.md`; choose one complete and one incomplete session and curate them into `Fixtures/continuous/`; run a plain replay and archive it before any golden refresh; run `ContinuousImageReplayDeviceTest`.

**Files changed (main):**
- `core/domain/.../recognition/StickerTruthCheck.kt` -- shared 54-sticker count/override rule.
- `core/domain/.../continuous/session/ContinuousSession.kt` -- versioned session format (manifest, frames, snapshots, labels, outcome derivation).
- `core/domain/src/evaluation/.../continuous/ContinuousSessionReplay{,Main}.kt` and `core/domain/build.gradle.kts` -- state replay plus the `replayContinuousSession` and `generateSyntheticSessions` tasks.
- `app/src/camera/kotlin/.../ContinuousScanEngine.kt`, `ContinuousSessionWriterFactory.kt`, `CameraModule.kt`, `StickerCenterDetector.kt` -- neutral tap, writer port and optional binding.
- `app/src/camera/continuous/.../ContinuousScanPipeline.kt`, `ContinuousScanEngineModule.kt`, `LiteRtStickerCenterDetector.kt` -- tap invocation, model identity, `copyModelInput` and letterbox fields.
- `app/src/camera/debug/.../recorder/session/*` -- the writer, PNG encoder, latency stats and Hilt module.
- `app/src/camera/debug/.../recorder/ContinuousRecorder{Analyzer,Controller,Screen}.kt`, `FixtureRecorderActivity.kt`, `FixtureRecorderScreen.kt`, `RecorderRules.kt` -- Continuous mode, the adb `RECORDER_SESSION` command and the delegation to `StickerTruthCheck`.
- `build-logic/.../policy/Fixture{Writer,Size}Policy.kt`, `tasks/Check{FixtureWriter,FixtureSize}Task.kt`, `tasks/GenerateBuildInfoTask.kt`, `FixtureConventionPlugin.kt`, `QualityConventionPlugin.kt`, `ReleaseConventionPlugin.kt`, root `build.gradle.kts` and `gradle/libs.versions.toml` -- gates and the debug build-info asset.
- `.github/workflows/ci.yml` and `CiWorkflowTest.kt` -- CI wiring.
- Tests: `StickerTruthCheckTest`, `ContinuousSessionJsonTest`, `ContinuousSessionReplayTest`, `ContinuousSessionWriterTest`, `ContinuousRecorderAnalyzerTest` (Robolectric), `ContinuousRecorderControllerTest`, `ContinuousFrameTapTest`, `ContinuousSessionWriterBindingTest`, `TruthRefusalTest`, `FixtureWriterPolicyTest`, `FixtureSizePolicyTest`, `CheckFixtureSizeTaskTest`, `FixtureWriterCompositionFunctionalTest`, `ContinuousImageReplayDeviceTest`.
- Docs: `Fixtures/continuous/README.md`, `Fixtures/README.md`, `KotlinStickerReads.kt` KDoc, `tools/cube-session.sh`, `docs/evidence/10-34/legacy-replay.md`, `docs/evidence/10-34/latency.md`.
- `_bmad-output/implementation-artifacts/epic-10-context.md` -- regenerated by step 1 because the planning files were newer.

**Departures recorded by the implementer:**
- The refusal text stays in debug `RecorderRules.truthRefusal()`, because `checkLearnerText` rejects text literals in `:core:domain`.
- The build commit and LiteRT version come from a generated debug-only asset, because `BuildConfigUsageTest` forbids `BuildConfig` (this was the spec's alternative).
- `StickerTruth.source` is nullable for stickers never set.

**Review findings:** 48 findings (high 1, medium 13, low 22, false 11, maybe-false 1). Patched: 24 rows (1 high, 11 medium, 12 low; rows that share a root cause are listed separately in the triage log). Deferred: 3 (recorder destroyed mid-session, device-only letterbox/`copyModelInput` coverage, legacy results not re-run during triage). Rejected: 21, each with its reason in the triage log.

**Follow-up review recommended: true.** This pass patched a high finding: labels for a Complete session are now prefilled from the HOME-reoriented export. It also changed the writer's concurrency and shutdown paths (dropping frames after the cap, rejected execute, write-failure handling). Both are unit-tested but not exercised on a device.

**Verification (after patches):** `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` passed. `./gradlew -p build-logic test` passed. The cornerR8 `checkCornerR8FixtureWriter` and `checkCornerR8ShadowReader` checks passed, as did the release011 `checkRelease011FixtureWriter` check. The targeted app and domain tests passed. `:core:domain:replayContinuousSession` gave 0 diffs for both synthetic sessions. `:app:checkCameraSize` passed. Matrix audit: every I/O row is covered by a test that ran and passed (JUnit XML checked).

**Residual risks:**
- The UI, the adb broadcast, the real latency numbers and image replay are all unrun on hardware.
- The recorder's camera configuration (output rotation enabled, default resolution) differs from `ScanFrameSource`; it is recorded in the manifest geometry.
- The pre-existing output drift of `exportKotlinStickerReads` and `evaluateMatrix` is documented in `legacy-replay.md`.
