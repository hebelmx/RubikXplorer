---
title: 'Story 10.23: A shadow sticker reader runs beside the Kotlin reads'
type: 'feature'
created: '2026-11-22'
status: 'done'
baseline_commit: '0861996fd0f14bfa1e2ed5274640b80487f309ef'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-22-quick-scan-sticker-reader.md'
  - '{project-root}/tools/corner-model/README.md'
  - '{project-root}/docs/evidence/10-18/litert-model-evaluation.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The tools/sticker-model Python unittests (assembly determinism, crop orientation, tau choice, size limit) do not run in CI.
    evidence: |-
      ci.yml has no Python step; they need TensorFlow and the corner model. Only the Kotlin StickerModelSplitTest and StickerModelContractTest run in CI.
    location: >-
      tools/sticker-model/test_*.py
    severity: medium
  - summary: >-
      No test checks that the Python training preprocessing matches the Kotlin StickerModelContract.fillInput tensor for the same face crop.
    evidence: |-
      Training unwarps faces in Python from the saved JPEGs; on the device the YUV crop goes through fillInput. The replay test uses a fake interpreter. A mismatch would only degrade shadow reads, never a scan; the H2 device session logs will show it.
    location: >-
      app/src/camera/debug/sticker/kotlin/dev/cubentia/app/camera/sticker/StickerModelContract.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Nobody knows whether a learned reader reads shaded, backlit and glossy Quick Scan faces better than the Kotlin classifier. No labelled comparison exists, and the corner-only LiteRT limit used to rule one out (lifted by SCP 2026-11-22 quick-scan-sticker-reader).

**Approach:** Build a seeded data pipeline, a tiny image-only sticker model, and a held-out evaluation under `tools/sticker-model/`. Then add a debug-only shadow reader that reads each captured Quick Scan face off the main thread and logs its read beside both Kotlin reads. A runtime debug toggle switches it on and off, and the reader decides nothing.

**Binding decisions (copied verbatim from the story's Refs):** AD-9, AD-13, AD-18. Also FR-22, FR-24, NFR-8, NFR-11, OQ-7, CAP-3 (failure modes F7, F8, F9, F10). No known deviation from these ADs.

## Boundaries & Constraints

**Always:**
- **AD-9:**
  - The Kotlin classifier stays the reader of record.
  - The model reads the unwarped face image only. It never takes a Kotlin reading, a centre or a calibration as input.
  - Only categorical results (a `Color` and a `StickerConfidence`) cross the reader seam and appear in the log. Raw probabilities never leave the reader.
- **AD-13:**
  - Face crops stay in memory, are never written, and are discarded when the scan ends.
  - Nothing new reaches release logs.
  - No new permission and no new runtime dependency (LiteRT is already in debug builds through the corner model).
- **AD-18 / AD-15:**
  - The reader, its toggle receiver and `sticker_reader.tflite` live only in a new folder of the debug camera source set, `app/src/camera/debug/sticker/{kotlin,assets,AndroidManifest.xml}`.
  - `build-logic` composes that folder only for a build type that is debuggable **and** has a corner-model flag above OFF (the reader needs LiteRT).
  - The seam the capture path calls is a `@BindsOptionalOf` interface in `app/src/camera/kotlin`, as `CornerDetector` is. Builds with no binding behave exactly as today.
- **Pipeline:**
  - Seeded and reproducible: dataset assembly is byte-identical across runs.
  - Synthetic faces go only into the training side.
  - Whole capture groups are split. Train, tune and held-out are pairwise disjoint by group and by image SHA-256.
- The model's hash, recipe and tool versions are recorded as for the corner model (Story 10.18). The `.tflite` file is under 500 KB.
- Steps only a person with the devices can do are recorded in `docs/evidence/10-23/` with empty tables to fill. They are not done here:
  - **H1:** extra fixture captures where the inventory is thin.
  - **H2:** the device session (10 scans with the reader on and 10 off, per device).
  - **H3:** the evaluation on Story 10.20's held-out corner groups, which do not exist yet.

**Never:**
- No change to how any Kotlin read, the agreement gate, the keep rule, `ScanAssembler` or the review decides. The only allowed change in `:core:domain` is additive: per-sticker reads exposed in `QuickScanClassification.FaceReport` for logging.
- No Learner-facing UI, text or `en.json` key. The toggle is an adb broadcast, like `RECORDER_BURST`.
- No model bytes, reader class or receiver in release, benchmark or `cornerR8`. No change to the release size report.
- No invented data. Where the inventory is too thin to support a claim, the inventory and the report say so.
- No bar is enforced on the evaluation.
- No later mode (second read or rescue). Those need a further SCP.
- No GPU or NNAPI delegate, so the reader matches the corner detector.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Reader on, corner captured | Debug build, toggle on (the default), model present | The 3 crops of the capturing frame are read once on the reader's own thread. One `CubentiaScanDiag` line per face: hold, face, model's 9 colors and confidences, both Kotlin reads (`self=`, `second=` with source `cal`/`norm`) and `inferMs` | N/A |
| Reader off | Toggle off by broadcast | No inference and no shadow lines. The scan start line says `state=off` | N/A |
| Toggle flipped mid-scan | Broadcast between hold 1 and hold 2 | Takes effect at the next capture | N/A |
| Model missing | Asset absent or load fails | One `shadowReader error=model-missing` line per scan. Capture, review and `ScanResult` unchanged | Never throws to the caller. Tries the load once per process, as `LiteRtCornerDetector` does |
| Inference error | Interpreter throws | One `error=inference` line for that capture. `ScanResult` unchanged | Caught, including `LinkageError` |
| Face crop missing | Quad not unwarped (null crop) | That face is logged `model=none`. The other faces are read | N/A |
| Scan ends with reads pending | Activity finishes or is destroyed | Queued crops are dropped and the executor stops. No crop reference survives the scan | N/A |
| Release, benchmark or `cornerR8` | Non-debuggable build | No reader class, receiver or sticker asset. The new build-logic check passes | The check fails, naming the offending entry, if one appears |
| Group on both sides of the split | Crafted manifest | The split test fails, naming the group or the image hash | N/A |

</intent-contract>

## Code Map

- `tools/corner-model/` -- the pattern to follow: `pyproject.toml` and `uv.lock` (Python 3.12, TF 2.19.1 `[and-cuda]`, NumPy 2.1.3, Pillow 11.3.0), `train_corner_model.py` (seeding L222-232, `export_tflite` L208-216, fp16), and `render_cube.py`:
  - `_render_face_texture` L224-308 renders one 9-sticker face with gradient shading, glare, logo and body color, but does not return its sticker colors.
  - `_apply_camera_effects` L435 simulates YUV 4:2:0.
  - `corner_keypoints.tflite` (7 keypoints `[T,UL,LL,B,LR,UR,C]` on a 128×128 crop) can locate the quads in the corner-fixture crops.
- `docs/evidence/10-18/litert-model-evaluation.md` L5-17 -- the provenance bullet format (SHA-256, bytes, seed, quant, versions, a note that GPU training is not bit-exact).
- **Real labelled sources (all with nine-sticker `truth`):**
  - `Fixtures/live/<device>_<cube>_studio-a/{U,F,R,D,L,B}.{json,jpg}` -- schema 2, 48 faces with a frame. The geometry is the centred square of 80% of the short edge, split 3×3 (`Fixtures/README.md`). Lab is in `labeledSample.sample.stickers` and the group calibration in `calibration.centers`. `sm-s721u1_stkb_studio-a` has invalid truth (`docs/scan-accuracy.md`): exclude it.
  - `Fixtures/live/*_studio` -- schema 1, 24 faces as Lab only.
  - `Fixtures/corner/lenovo-tb128xu_<cube>_<light>/corner-{1,2}.{json,jpg}` -- 48 faces, Lenovo only. The 200×200 corner crop has 3 oblique faces per hold and `labeledSamples[3]`. `cool` is backlit. `stla warm` hold 1 is side-shaded (`docs/evidence/10-19/light-check-and-replay.md:44-58`, `docs/evidence/10-18/d2-side-face-replay.md:1-9`).
  - `Fixtures/labeled/photo-evaluation.json` -- S4 photos (S24 FE and Galaxy Tab SM-T738U): 24 faces as `rect` + `truth`, without Lab. Read by `tools/evaluate_fixture_photos.py` (`face_centers` L51-57).
- **Listed but not usable** -- bursts `docs/evidence/quick-scan-2026-11-22/bursts/*.jsonl` (no truth), `Fixtures/matrix/*.log` (no images), the unlabelled `Fixtures/*_S{1,2}` photos, git deletion `4ced73f6` (WhatsApp copies of the S1/S2 originals), and the uncommitted local `captures/` folders.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt`:
  - `unwarp(frame, quad, side=96)` L429-462 returns a fresh 96×96 `YuvFrame` (copied arrays).
  - `CornerFrame` L464 is where optional `faceCrops` are attached.
  - `CornerReader.read` L472-515 drops the crops after `FrameReader.readAt`. Quad order there = face order of `CornerFrame.faces`.
  - `CornerDetector` L89 is the seam pattern.
- `app/src/camera/corner/kotlin/.../LiteRtCornerDetector.kt` (lazy interpreter L59-79, catches `Exception`/`LinkageError`, mmap asset load L94-108, `loadModel` test seam), `CornerDetectorModule.kt`, `CameraModule.kt:26` (`@BindsOptionalOf`), `CornerModelTransform.fillRgbInput` L69 (the YUV→RGB formula to reuse).
- `app/src/camera/kotlin/.../ScanFrameSource.kt` L558-594 -- `read(image)` on the analyzer thread, `image.close()` in `finally`; `stop()` L213-241.
- `app/src/camera/kotlin/.../GuidedScanActivity.kt` -- Quick Scan analysis branch L593-620; `captureCorner` L652-698 (on `QuickScanEffect.CornerCaptured` → `ScanDiagnostics.onCornerCapture(quick.lastCapture)`); `viewEntered` L534-538 (scan start); `finishWith` L475-522; `onDestroy` L377-381; `analyzerExecutor` L834; `onCleared` L1090.
- `core/domain/.../recognition/QuickScanClassification.kt` -- `read()` L31-51 (`first` = self-calibrated, `second` = saved calibration or `normalized`, combined by `gate`), `report()` L87-125, `FaceReport` L127. `QuickScan.kt`: `take()` L381-437, `CornerCaptureReport` L651.
- `app/src/debug/.../ScanDiagnostics.kt` (`TAG` L452, `cornerCaptureLine` L146) and the release no-op twin -- unchanged. The reader writes its own lines with `ScanDiagnostics.TAG`.
- `app/src/camera/debug/kotlin/.../recorder/RecorderBurst.kt:62`, `FixtureRecorderActivity.kt:159,208` -- the adb broadcast precedent.
- **Build logic:**
  - `build-logic/.../ReleaseConventionPlugin.kt` -- source-set composition in `finalizeDsl` L97-133 and manifests in `onVariants` L134-144; `checkReleaseSize` L169-174; `checkCameraSize` L175-211.
  - `policy/CameraSizePolicy.kt` excludes `camera/recorder/**` and must also exclude `camera/sticker/**`.
  - `QualityConventionPlugin.kt` registers per-variant checks wired into `check` (L104).
  - `tasks/CheckCameraClasspathTask.kt` is the task pattern.
  - `build-logic/src/test/.../functional/CameraCompositionFunctionalTest.kt` (L53, L113) is the TestKit pattern.
- `.github/workflows/ci.yml` -- the "Verify build wires the gates" dry-run list must name the new check.
- **Tests to mirror:**
  - `core/domain/src/test/.../QuickScanSideFaceReplayTest.kt` (reads `../../Fixtures/corner`; `Scenario`s; the Kotlin reads per sticker).
  - `app/src/testDebug/.../camera/CornerFixtureHarnessTest.kt` (fixture JPEG → `CornerReader.read` → `ScanAssembler`).
  - `app/src/testDebug/.../QuickScanRouteTest.kt` (Robolectric + Hilt, `FakeCornerFrames` L828, `ShadowLog`).
  - `LiteRtCornerDetectorFailureTest`.
- `core/domain/build.gradle.kts:37` -- the `evaluateLiveBaseline` JavaExec pattern for a Kotlin-reads export task.

## Tasks & Acceptance

**Execution:**
- [ ] `tools/sticker-model/{pyproject.toml,uv.lock,README.md}` -- a uv project mirroring the corner-model pins, plus the README contract (input, output, color order, confidence rule, commands) -- AC3.
- [ ] `tools/sticker-model/assemble.py` -- seeded assembly into `tools/sticker-model/artifacts/` (gitignored):
  - reads every source in the Code Map;
  - writes upright 96×96 RGB face crops in Kotlin cell order:
    - A2 frames: the grid square;
    - photos: `rect`;
    - corner crops: quads from `corner_keypoints.tflite`, unwarped the way `unwarp()` orders corners;
  - checks alignment per face: the mean Lab of each cell must sit near the JSON's measured sticker, else the face is excluded and listed with the reason;
  - writes a deterministic `manifest.json` (item → source path, group, device, cube, light, label, SHA-256) and the inventory data -- AC1.
- [ ] `tools/sticker-model/synth_faces.py` -- a seeded single-face renderer that returns labels and targets shaded side faces, glare, red against orange and white against yellow, with YUV 4:2:0 and camera effects. It may reuse `render_cube.py` helpers. Training only -- AC1.
- [ ] `tools/sticker-model/holdout.json` (committed) -- names the held-out groups:
  - `live/sm-s721u1_stla_studio-a` (S24 FE, stickerless);
  - `live/sm-s721u1_stka_studio-a` (S24 FE, stickered);
  - `corner/lenovo-tb128xu_stla_cool` (Lenovo, stickerless, backlit);
  - `corner/lenovo-tb128xu_stka_cool` (Lenovo, stickered, backlit).

  It also names the tune groups: `live/lenovo-tb128xu_stkb_studio-a`, `corner/lenovo-tb128xu_stka_cool-front`, `photo/RubikStickered_GalaxyTab_S4`. Every other usable real group trains -- AC2.
- [ ] `tools/sticker-model/split.py` + committed `tools/sticker-model/split.json` -- the split manifest (group → side; item → image SHA-256 and side). Assembly refuses to train when any check fails -- AC2.
- [ ] `tools/sticker-model/train_sticker_model.py` -- a seeded small CNN.
  - Model contract:
    - input `[1,96,96,3]` float32 RGB in [0,1];
    - output `[1,9,6]` softmax in Kotlin cell order;
    - color order WHITE, YELLOW, RED, ORANGE, BLUE, GREEN.
  - Export: fp16 to `app/src/camera/debug/sticker/assets/sticker_reader.tflite`. Fail if the file is 500 KB or more.
  - Confidence rule: CONFIDENT when top-1 ≥ τ. τ is the smallest value on a 0.50–0.99 grid giving 0 confidently wrong on the tune split (0.99 if none does).
  - Writes `model-card.json` with τ, color order, input size, SHA-256, bytes, seed, quant and tool versions -- AC3.
- [ ] `tools/sticker-model/evaluate.py` -- on the held-out groups, per device × cube × light, for the model and for the Kotlin `self`, `second` and gated reads:
  - stickers correct, uncertain and confidently wrong;
  - model-versus-Kotlin agreement and disagreement;
  - Red/Orange and White/Yellow confusions;
  - a learning curve: retrain on 25/50/75/100% of the real training groups (seeded subsets, synthetic held constant).

  Writes `docs/evidence/10-23/evaluation.{md,json}`. Takes `--groups <file>` so H3 can re-run it on Story 10.20 groups -- AC4, AC9.
- [ ] `core/domain/build.gradle.kts` + an exporter following `evaluateLiveBaseline` -- the `exportKotlinStickerReads` task writes `docs/evidence/10-23/kotlin-reads.json`, the per-sticker Kotlin reads for every item in `holdout.json`:
  - `self` from the group's own face centres;
  - `second` from the record's `calibration.centers`;
  - the gated read, through `QuickScanClassification`'s existing functions -- AC4.
- [ ] `tools/sticker-model/test_*.py` (unittest) -- assembly determinism (twice → identical manifest bytes), split disjointness, synthetic only on train, crop orientation on a fixture with known truth, the size limit -- AC1–AC3.
- [ ] `app/src/testDebug/.../camera/sticker/StickerModelSplitTest.kt` -- runs in CI.
  - Reads `holdout.json` and `split.json` and recomputes each committed real image's SHA-256.
  - Fails when a group or an image hash appears on two sides, when a synthetic item is outside train, or when a `holdout.json` group is missing from the held-out side.
  - Includes a crafted-manifest case proving that it fails -- AC2.
- [ ] `core/domain/.../QuickScanClassification.kt`, `QuickScan.kt` -- additive: `FaceReport` gains the per-sticker `self` and `second` reads and the second-read source. No decision changes, and existing tests stay green -- AC5.
- [ ] `app/src/camera/kotlin/.../{CornerLocator.kt,ScanFrameSource.kt,CameraModule.kt}`, plus a new `CapturedFaceObserver` seam there:
  - `CornerFrame` carries the frame's three unwarped crops, only when an observer is bound;
  - `@BindsOptionalOf CapturedFaceObserver` with `onScanStarted()`, `onCornerCaptured(hold, crops, reports)` and `onScanEnded()`;
  - returns nothing -- AC5.
- [ ] `app/src/camera/kotlin/.../GuidedScanActivity.kt` -- calls the observer at Quick Scan view entry for hold 1, on `CornerCaptured` with the capturing frame's crops and `lastCapture` faces, and at scan end (finish or destroy) -- AC5, AC6.
- [ ] `app/src/camera/debug/sticker/kotlin/dev/cubentia/app/camera/sticker/` -- the debug-only reader:
  - `ShadowStickerReader`: lazy LiteRT, 4 threads, XNNPACK, its own single-thread executor, YUV→RGB input as `fillRgbInput`, probabilities → `StickerReading` with τ;
  - logs lines `quickScan shadow hold= face= center= inferMs= model= self= second=<cal|norm>:…`, colors as letters and `C`/`U`;
  - `ShadowReaderToggle`: debug `SharedPreferences`, default **on**, set by a manifest-registered exported receiver for `dev.cubentia.debug.SHADOW_READER --ez on <bool>`, read at each capture;
  - a Hilt module binding the seam;
  - a `StickerModelContract` with τ, color order and input size;
  - a test asserting that the contract equals `model-card.json` -- AC3, AC5, AC6.
- [ ] `app/src/testDebug/.../camera/sticker/ShadowReaderReplayTest.kt` (Robolectric + Hilt, route level):
  - replays recorded `Fixtures/corner` captures (CornerFrames from the records' measured cells, crops from the matching JPEGs) through the real activity;
  - runs five ways: unbound, toggle off, on with a fake interpreter, model missing, inference throwing;
  - asserts an identical review `ScanResult` and `FaceSample`s every time;
  - asserts that shadow lines appear only in the on run, and that the error lines appear.

  Plus unit tests for every other matrix row (mid-scan toggle, null crop, scan-end discard, receiver) -- AC5, AC6.
- [ ] `build-logic/.../ReleaseConventionPlugin.kt`, `policy/CameraSizePolicy.kt`, a new `tasks/CheckShadowReaderTask.kt` + policy, `QualityConventionPlugin.kt`, `CameraCompositionFunctionalTest.kt` (or a sibling):
  - composes the sticker folder and manifest only when debuggable and corner on;
  - excludes `camera/sticker/**` from the camera-size class sum;
  - `check<Variant>ShadowReader` runs on every non-debuggable variant and fails on any `sticker_reader*.tflite` in the merged assets or any `dev/cubentia/app/camera/sticker/` class in the compiled classes;
  - TestKit tests cover:
    - debug composes the folder;
    - release and debuggable-without-corner do not;
    - the check fails on a planted release asset and on a planted release class -- AC7.
- [ ] `.github/workflows/ci.yml` -- the new check is added to the gate-wiring dry-run list -- AC7.
- [ ] `docs/evidence/10-23/` -- the files this story writes:
  - `inventory.md`: every source, image versus Lab-only, labels, and coverage per device × cube × light (thin = fewer than 2 groups or 12 image-backed faces), plus the exclusions;
  - `evaluation.{md,json}`;
  - `kotlin-reads.json`;
  - `model-provenance.md`;
  - `release-size.md`: the release size report before and after;
  - `human-gate.md`: H1–H3 with empty tables -- AC1, AC3, AC4, AC7–AC9.
- [ ] `.gitignore` -- `tools/sticker-model/{.venv,artifacts}/`.

**Acceptance Criteria:**
- AC1 -- inventory and assembly:
  - **Given** the `Fixtures/` tree and the earlier capture evidence, including git history,
  - **when** `assemble.py` runs twice with the same seed,
  - **then** the manifests are byte-identical, and `inventory.md` lists every source with what it holds, its labels and its device × cube × light coverage, names the thin cells as H1, and contains no synthetic item outside train.
- AC2 -- split:
  - **Given** `split.json`,
  - **when** `StickerModelSplitTest` runs in CI,
  - **then** it passes, and it fails on a manifest with a group or an image hash on two sides.
  - The held-out groups cover both devices and both a stickered and a stickerless cube, and two of them are backlit.
- AC3 -- exported model:
  - **Given** the exported model,
  - **when** it is inspected,
  - **then** it takes one 96×96 face image and returns 9 colors with a confidence mapped by τ to `StickerConfidence`.
  - It is under 500 KB, and `model-provenance.md` records its SHA-256, bytes, seed, quant, recipe and tool versions, matching `model-card.json` and `StickerModelContract`.
- AC4 -- evaluation report:
  - **Given** the held-out groups,
  - **when** `evaluate.py` runs,
  - **then** `evaluation.md` reports, per device × cube × light, for the model and for the Kotlin reads: correct, uncertain and confidently wrong stickers; agreement; Red/Orange and White/Yellow confusions; and the learning curve. It is marked "no bar; routed to the PO".
- AC5 -- shadow reads and result safety:
  - **Given** a debug Quick Scan with the reader on,
  - **when** a corner is captured,
  - **then** one shadow line per face is logged off the main thread, and the route replay gives the same `ScanResult` with the reader unbound, off, on, model-missing and failing.
- AC6 -- runtime toggle:
  - **Given** a debug build,
  - **when** `adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.SHADOW_READER --ez on false` is sent,
  - **then** the next capture is not read, and each scan's first line states `shadowReader state=on|off`.
- AC7 -- release isolation:
  - **Given** the release, benchmark and `cornerR8` builds,
  - **when** `check` runs,
  - **then** `check<Variant>ShadowReader` passes, the TestKit cases prove that it fails on a planted asset or class, and `release-size.md` shows identical `release-size.txt` before and after.
- AC8 (H2, human gate, not met here): 10 scans with the reader on and 10 with it off per device, recorded in `human-gate.md`.
- AC9 (H3, human gate, not met here): `evaluate.py --groups` re-run on Story 10.20's held-out corner groups, added to `docs/evidence/10-23/`.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 45 findings — high 0, medium 6, low 21, false 13, maybe-false 0 (5 intent-audit observations included; 0 not graded beyond these)
- note: the diff given to reviewers left out generated data (uv.lock, split.json, evaluation.json, kotlin-reads.json, the .tflite and this spec) to keep it readable; those files are in the worktree and in the commit.
- findings:
  - `[medium]` `[patch]` (verification-gap 1) Nothing tested the debug binding of CapturedFaceObserver or that CameraXFrameSource attaches faceCrops only when an observer is bound — added `debugGraphBindsTheShadowStickerReader` and `cornerFramesCarryFaceCropsOnlyWithABoundObserver`.
  - `[medium]` `[patch]` (verification-gap 2) The activity's endShadowScan never reached the observer in any test — the replay test now counts onScanEnded after destroy and asserts lastCornerCrops is null.
  - `[medium]` `[defer]` (verification-gap 3) The sticker-model Python unittests do not run in CI — they need TF and the corner model; deferred.
  - `[low]` `[patch]` (verification-gap other) KotlinStickerReads had no test and wrote "norm" for a null secondRead — now writes "none" (grouped with blind 6).
  - `[false]` `[reject]` (blind 1) The diff leaves out files — they were left out on purpose for review size; all are present and committed.
  - `[low]` `[patch]` (blind 2) τ sits at the 0.99 grid ceiling while the wording says "τ would need to rise" — the wording now says the ceiling was reached and the fallback applied. The grid rule itself is the spec's.
  - `[low]` `[patch]` (blind 3) The toggle receiver turned the reader on for a non-boolean extra — it now accepts only a Boolean and logs anything else as ignored, with a test.
  - `[false]` `[reject]` (blind 4) Kept faces get no Kotlin reads in the shadow log — `keptSet` holds only faces kept in an earlier round, which this capture does not re-sample (QuickScan.kt:392,410). Every face judged at this capture has a report.
  - `[low]` `[patch]` (blind 5) `evaluate.py --groups` accepted tune groups — it now refuses train and tune groups.
  - `[medium]` `[patch]` (blind 6) The Kotlin baseline merged both holds and took the first record's calibration — it is now per hold (hold 1 alone, hold 2 merged) with firstNotNullOfOrNull, and kotlin-reads.json and evaluation were regenerated.
  - `[low]` `[reject]` (blind 7) CheckShadowReaderTask does not inspect the manifest — the receiver class lives in `camera/sticker`, which the class scan covers, and the manifest comes only from the composed folder. A manifest scan would add a new branch.
  - `[low]` `[reject]` (blind 8) The functional tests skip the real manifest and cornerR8 — CI runs the real `:app:checkCornerR8ShadowReader`, and the debug lint/build merges the real manifest.
  - `[medium]` `[defer]` (blind 9) Python tests not in CI — same root as verification-gap 3.
  - `[false]` `[reject]` (blind 10) The sticky load flag reports model-missing for every load failure — this is what the spec matrix says ("Asset absent or load fails → model-missing; tries the load once per process").
  - `[medium]` `[defer]` (blind 11) No test checks that Python training inputs match the Kotlin `fillInput` tensors for the same crop — this needs a cross-language fixture. The H2 device session will show it; recorded as deferred.
  - `[low]` `[patch]` (blind 12) onScanStarted repeats on hold-1 re-entry — added a per-scan active flag (grouped with edge 1/2).
  - `[low]` `[reject]` (blind 13) The held-out `stka_cool` and the tune `stka_cool-front` may share a session — they are different light groups named by the spec, and the inventory states how thin the data is.
  - `[low]` `[reject]` (blind 14) H2 measures too little (queue wait, prefs read on the analyzer thread) — the AC asks for inference time and capture times, and the table records both.
  - `[medium]` `[patch]` (edge 1) A capture after onScanEnded recreated the executor — the reader ignores captures outside an active scan.
  - `[low]` `[patch]` (edge 2) Hold-1 re-entry duplicated the state line and reset missingLogged — same fix as edge 1.
  - `[low]` `[patch]` (edge 3) Toggle extra type — same as blind 3.
  - `[false]` `[reject]` (edge 4) Load failure labelled model-missing — per spec, same as blind 10.
  - `[low]` `[reject]` (edge 5) An empty tune side gives τ 0.50 — unlikely (holdout.json is committed), and the fix adds a guard.
  - `[false]` `[reject]` (edge 6) `evaluate.py` with empty groups crashes — a loud failure on bad input is correct.
  - `[low]` `[patch]` (edge 7) Tune groups accepted by --groups — same as blind 5.
  - `[low]` `[patch]` (edge 8) Groups missing from kotlin-reads.json gave n/a silently — the script now refuses them by name.
  - `[low]` `[patch]` (edge 9) assemble.py wrote split.json and the inventory before refusing — checks now run before any write.
  - `[false]` `[reject]` (edge 10) A None crop from unwarp crashes assembly — a loud failure, not a silent wrong result.
  - `[false]` `[reject]` (edge 11) A degenerate photo rect or unknown key crashes assembly — loud failure.
  - `[false]` `[reject]` (edge 12) A new fixture dir with an unexpected name crashes assembly — loud failure.
  - `[false]` `[reject]` (edge 13) A sticker list not of length 9 — loud failure.
  - `[low]` `[reject]` (edge 14) split.py skips coverage checks with no held-out groups — unlikely, and StickerModelSplitTest catches it in CI.
  - `[low]` `[reject]` (edge 15) Duplicate face in a group with associateBy — no fixture has one; the fix adds a guard.
  - `[low]` `[patch]` (edge 16) Calibration taken from the first record only — fixed with blind 6.
  - `[low]` `[patch]` (edge 17) The inventory coverage sentence was hard-coded — it is now derived from the rows.
  - `[low]` `[patch]` (edge 18, claim) The alignment rule differed from "each cell" — README and inventory now state the rule as implemented (median ≤ 15 over 9 cells, max ≤ 30 over 8 non-centre cells).
  - `[low]` `[patch]` (edge 19, claim) The 100% learning-curve point is the exported model — stated in evaluation.md.
  - `[medium]` `[patch]` (intent 1) The route test bypasses the real keepCrops wiring — same root as verification-gap 1.
  - `[false]` `[reject]` (intent 2) Faces logged `self=-` — same as blind 4.
  - `[false]` `[reject]` (intent 3) The gated Kotlin read is not logged per sticker — the AC asks for both Kotlin reads (self and second), which are logged; gated is in the offline evaluation.
  - `[low]` `[reject]` (intent 4) H2 records inferMs per face, not per capture — the per-hold table has the three face columns, so summing them is trivial.
  - `[false]` `[reject]` (intent 5) The shaded group is in train — the spec names the held-out groups. Two backlit groups satisfy "shaded or backlit", and the limits are stated.
  - `[false]` `[reject]` (intent 6) AD-15 is grouped with AD-18 although it is not in Refs — AD-9, AD-13 and AD-18 are copied verbatim. AD-15 is cited only as context for the debug source-set rule, and nothing deviates from it.
  - `[false]` `[reject]` (intent 7) The replay uses one training group — the AC requires equality across reader modes, not held-out data. This is the route-level proof, and the reader never changes the result.
  - `[false]` `[reject]` (intent 8) No new real captures — this is the H1 human gate by orchestrator instruction.

## Design Notes

- **Data limits:**
  - Coverage holds only when the held-out groups are taken together: the backlit held-out groups are Lenovo-only, and the S24 FE held-out groups are studio front light only.
  - No S24 FE Quick Scan corner capture, no shaded or backlit S24 FE face and no torch-on capture exist in the repository, so the evaluation cannot speak to those conditions. H1 asks for them.
  - Held-out cells hold about 6 faces (54 stickers) each, so the report is indicative.
- **Pixel caveat:** corner-fixture crops are unwarped in Python from the 200×200 saved crop, while the Kotlin Lab values were measured on the device's analysis frame. The pixels differ, and the report says so.
- **Default on:** the toggle defaults to on so that comparison data builds up from the Story 10.20 session onward (SCP alternatives). It is not Learner UI. It persists across restarts and is cleared on uninstall.
- **Threading:** the reader gets its own executor, so inference never runs on the analyzer thread and cannot delay the next frame.
- **Crop retention:** the crops are the arrays `unwarp()` already allocates. They are retained for one frame only when an observer is bound.

## Verification

**Commands:**
- `uv run --project tools/sticker-model python -m unittest discover -s tools/sticker-model` -- expected: pass
- `uv run --project tools/sticker-model python tools/sticker-model/assemble.py && uv run --project tools/sticker-model python tools/sticker-model/train_sticker_model.py && uv run --project tools/sticker-model python tools/sticker-model/evaluate.py` -- expected: the model is under 500 KB, and the evidence files are written
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:exportKotlinStickerReads :core:domain:test :app:testDebugUnitTest --tests '*sticker*' --tests '*QuickScan*' --tests '*CornerFixture*'` -- expected: pass
- `./gradlew -p build-logic test` -- expected: pass
- `./gradlew spotlessCheck build :app:checkReleaseSize` -- expected: success, with `release-size.txt` identical to the baseline
- `unzip -l app/build/outputs/apk/debug/*.apk | grep sticker_reader` (present) and `unzip -l app/build/outputs/apk/release/*.apk | grep sticker_reader` (absent)

**Manual checks:**
- H1–H3 in `docs/evidence/10-23/human-gate.md` stay open for the PO with the devices.

## Auto Run Result

Status: done (code complete; the human-gate items H1–H3 stay open for the PO).

**Summary:**
- `tools/sticker-model/` is a seeded, reproducible pipeline: inventory, a split by whole capture groups (`holdout.json`, `split.json`), synthetic faces for training only, a fp16 `sticker_reader.tflite` of 107,848 bytes, and the held-out evaluation with a learning curve.
- A debug-only `ShadowStickerReader` reads the three crops of each Quick Scan capture on its own thread and logs them beside the Kotlin `self` and `second` reads. It is toggled by adb broadcast (`dev.cubentia.debug.SHADOW_READER`) and is on by default.
- A `check<Variant>ShadowReader` build-logic gate keeps the reader out of release, benchmark and cornerR8.

**Held-out result (216 stickers; no bar, routed to the PO):**

| Reader | Correct | Uncertain | Confidently wrong |
| --- | --- | --- | --- |
| Model | 206 | 6 | 4 |
| Kotlin gated | 166 | 49 | 1 |

τ = 0.99 is the grid ceiling.

**Files changed:**
- `tools/sticker-model/*` -- the pipeline, tests and README.
- `app/src/camera/debug/sticker/*` -- the reader, toggle, contract, Hilt module, manifest and model.
- `app/src/camera/kotlin/.../{CapturedFaceObserver,CornerLocator,ScanFrameSource,CameraModule,GuidedScanActivity}.kt` -- the seam and the crop hand-off.
- `core/domain/.../QuickScanClassification.kt` -- additive per-sticker reads in `FaceReport`.
- `core/domain/src/evaluation/.../KotlinStickerReads.kt` and `build.gradle.kts` -- the `exportKotlinStickerReads` task.
- `build-logic/...` -- composition, the size-policy exclusion, the new check and its tests.
- `.github/workflows/ci.yml` -- the gate list and the cornerR8 check.
- `docs/evidence/10-23/*` -- inventory, evaluation, kotlin reads, provenance, release size and human gate.
- `app/src/testDebug/.../camera/sticker/*` -- the split, contract, reader and replay tests.

**Review:**
- 45 findings in total:
  - 22 patched (6 medium, 16 low);
  - 3 deferred: Python tests not in CI (raised twice) and the cross-language input-parity test;
  - 20 rejected, with reasons in the triage log.
- Patched entries at entry verdict: 4 medium (crop wiring test, scan-end test, per-hold Kotlin baseline, post-scan executor), the rest low.
- Follow-up review recommended: true. Specific unverified risk: the reader-lifecycle and toggle patches were checked only by unit tests, and the real device behaviour of the DUMP-guarded receiver is unverified.

**Verification:**
- Python unittest: 17 OK.
- `:core:domain:test` and the app sticker, QuickScan, CornerFixture, CameraXFrameSource and binding tests: pass.
- `-p build-logic test`: pass.
- `spotlessCheck build :app:checkReleaseSize`: pass, and `release-size.txt` is unchanged (AAB 6,323,728).
- `sticker_reader.tflite` is present in the debug APK and absent from release.
- Training was not re-run in verification: it is not bit-exact on a GPU, and `StickerModelContractTest` pins the committed SHA-256.

**Residual risks:**
- The data is thin: all 19 device × cube × light cells have one group.
- The backlit held-out groups are Lenovo only, and the S24 FE has no corner, shaded or backlit data.
- The model has 4 confidently wrong stickers.
- The Python and Kotlin readers saw different pixels.
- H1–H3 are open in `docs/evidence/10-23/human-gate.md`.
