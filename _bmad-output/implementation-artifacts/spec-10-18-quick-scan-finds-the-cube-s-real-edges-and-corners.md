---
title: "Story 10.18: Quick Scan finds the cube's real edges and corners"
type: 'feature'
created: '2026-11-20'
status: 'done'
baseline_revision: '859e7f5a3d061baeef8aa41e7da667a67ba5f698'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-20-quick-scan-litert-escalation.md'
  - '{project-root}/docs/evidence/10-18/quick-scan-litert-escalation.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Device gate D1: re-run LiteRtCornerDetectorDeviceTest on the Lenovo TB128XU (HA1S28MM) to confirm the < 40 ms median and the strengthened accuracy/rejection asserts.
    evidence: |-
      No device run was required in this session. An earlier run reported a 13.99 ms median (local app/build androidTest XML + logcat, not committed). The test has since gained real-palette frames, 100% negative rejection and an asserted end-to-end CornerReader check, and has only been compiled. CI only compiles androidTest (ci.yml refuses connected tasks), so model-in-the-loop inference is not checked in CI.
    location: >-
      app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtCornerDetectorDeviceTest.kt
    severity: medium
  - summary: >-
      Device gate D2: on-device R/L side-face check and real-frame detection with stickered/stickerless cubes on the Lenovo and S24 FE, including whether Hold 2 without FLAG_AWB actually stops AWB drift.
    evidence: |-
      All R/L evidence is synthetic (JVM CornerLocatorTest with ideal quads; ΔE ~23-27). The 0.48 ROI could not be shown to change the end-to-end outcome on synthetic data; only a deterministic ROI-geometry test pins it. No Fixtures/corner captures exist. Whether AWB drifts without FLAG_AWB is unverified (CameraX AWB stays continuous auto).
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt, ScanFrameSource.kt
    severity: medium
  - summary: >-
      Device gate D3: install an R8-minified build with corner flags raised and confirm LiteRT inference runs.
    evidence: |-
      A minified benchmark build with CAMERA_SCAN/QUICK_SCAN raised built and kept NativeInterpreterWrapper/TensorImpl in the mapping (flags reverted afterwards), but inference on a minified APK was not run.
    location: >-
      build-logic/src/main/resources/cubentia-keep-rules.pro
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Pure-Kotlin geometric/blob heuristics (`CornerLocator` + `StickerBlobDetector`) fail on the minimum reference device (Lenovo TB128XU, Snapdragon 680: 0/91 detections on a scrambled stickered cube at ~950 ms/frame, inconsistent stickerless locks, and forced `R`/`L` 2D fallbacks with `unc=9` on shaded 3D side faces). Two capture-cue regressions also remain in Quick Scan UI (`quickScan:captureFlash` tag overwritten; capture 2 skips the 400 ms cue when fallback triggers).

**Approach:** Activate the pre-approved AD-9 / SCP 2026-11-20 LiteRT fallback specifically for Quick Scan 7-keypoint 3D corner detection: build a reproducible synthetic 3D + real-palette training pipeline producing a tiny quantized `.tflite` model (~150–400 KB) predicting `[T, UL, LL, B, LR, UR, C]` + presence, wire `LiteRtCornerDetector` in `app/src/camera/corner/` behind `CornerDetector` with corner-flag-gated LiteRT composition, enforce 16 KB ELF alignment, R8 inference, and `< 30 MB` NFR-11 size gates, fix 3D side-face Red/Orange readout (`R`/`L`) and both capture-cue bugs, and verify `< 40 ms` median inference on Lenovo TB128XU.

## Boundaries & Constraints

**Always:**
- AD-9 & AD-18: `com.google.ai.edge.litert` and `corner_keypoints.tflite` live strictly inside `app/src/camera/corner/` and compose ONLY when `QUICK_SCAN` or `CASE_RECOGNITION` is above `OFF`. Release camera flags stay `OFF` in `FeatureFlagLevels.byBuildType` (Story 10.11 raises them).
- Guided 6-face Scan and all sticker color classification stay pure Kotlin (`FrameReader`, `CellReader`, `SelfCalibration`, `StickerClassifier`, `ScanAssembler`).
- Quick Scan unwarps all three visible faces from detected 7 corners via `CornerGuide.quads(points)` + `unwarp()`, never from fixed isometric `CornerGuide.quads`; post-process with `CornerLocator.isValidCubeGeometry` and `CornerOverlaySmoother`.
- Inference runs off the main thread (`analyzerExecutor`) in `< 40 ms` median per frame on Lenovo TB128XU; never fall back to the ~950 ms pure-Kotlin `CornerLocator` on a LiteRT miss.
- Release installed size stays `< 30 MB` (NFR-11, single-ABI `arm64-v8a` accounting including transitive `litert` + `litert-api` + `.tflite`); `libLiteRt.so` passes 16 KB ELF page alignment (`targetSdk 36`); R8 minified builds run inference cleanly.
- No `INTERNET` permission, no frame persistence, no ML Kit / OpenCV / `org.tensorflow` Maven artifacts (AD-13). All Learner copy lives in `core/domain/src/main/resources/content/strings/en.json`.
- Keep `CornerReader`, `unwarp`, `isValidCubeGeometry`, `CornerOverlaySmoother`, and crop/decode math covered by deterministic JVM tests via `CornerDetector`.

**Never:**
- Never add OpenCV, ML Kit, or `org.tensorflow:tensorflow-lite` Maven dependencies.
- Never place camera or LiteRT code in `app/src/main/` or `:core:*`.
- Never draw a perspective-correct hexagon guide sketch (keep only the minimal 3-line Y anchor + smoothed detected outline).
- Never rely on haptics alone for capture feedback; never edit `_bmad-output/planning-artifacts/` or `_bmad/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Scrambled/solved stickered or stickerless cube in framing zone | 3D corner `YuvFrame` at sensor rotation 0°/90°/180°/270°, pitch 15–55°, yaw ±28°, roll ±30° | `LiteRtCornerDetector` extracts upright 128×128 crop, predicts presence ≥ threshold and 7 keypoints `[T,UL,LL,B,LR,UR,C]`, maps back to frame pixels, passes `isValidCubeGeometry`, returns `Location(quads, score)` | Invalid geometry or low presence returns `null` (no heuristic fallback) |
| Dark/glossy surface or empty/hand-only frame (#196) | Frame with no cube or only dark-table reflection | Detector returns `null` in `< 40 ms`; after ~3 s `CornerHold` emits `Coaching.PLAIN_LIGHT_SURFACE` ("Try a plain, light surface.") | Never locks onto reflection alone |
| Uncompressed `.tflite` missing/unreadable | Asset open failure or interpreter init failure | Detector degrades safely to returning `null` without crashing analyzer thread | Logged via debug diagnostics / caught cleanly |
| 3D oblique side faces `R` (Red) vs `L` (Orange) | Hold 1 `[U,F,R]` + Hold 2 `[D,L,B]` with directional shading / bevel glare | Tighter 3D unwarped cell ROI (`0.48`) + no Hold-2 AWB re-drift preserves Red/Orange separation (`ΔE ≥ 10`), avoiding forced `R`/`L` 2D fallback | Genuine close calls still stay `UNCERTAIN` per AD-9 |
| Capture 1 and Capture 2 cues (#197) | Hold 1 capture → Hold 2; Hold 2 capture → `Review` OR Guided `Viewfinder` fallback | Both captures show subtle flash (`quickScan:captureFlash`), thumbnail (`quickScan:thumbnail`), enlarged counter (`quickScan:counter`), 400 ms freeze, and play `ShutterSound`; fallback transition waits for `endFreeze()` | Never drops cue on Capture 2 fallback |
| Corner flags OFF vs ON build composition | `release` (all OFF); `CAMERA_SCAN=BETA, QUICK_SCAN=OFF`; `debug` (`QUICK_SCAN=BETA`) | `litert` + `corner_keypoints.tflite` + `app/src/camera/corner/` present ONLY when `QUICK_SCAN` or `CASE_RECOGNITION` > `OFF`; zero LiteRT bytes otherwise | Build gates (`check*CameraClasspath`, `checkElfAlignment`, `checkCameraSize`, `checkReleaseSize`) fail on violation |

</intent-contract>

## Code Map

- `tools/corner-model/` (new) -- reproducible Python (`uv`) synthetic 3D cube renderer + real-palette/real-fixture training & INT8/FP16 `.tflite` quantization pipeline (`pyproject.toml`, `render_cube.py`, `train_corner_model.py`, `evaluate_model.py`, `README.md`).
- `app/src/camera/corner/assets/corner_keypoints.tflite` (new) -- quantized 7-keypoint LiteRT model (~150–400 KB), packaged uncompressed (`noCompress`) only when `isCornerModelEnabled` is true.
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/LiteRtCornerDetector.kt` (new) -- `CornerDetector` implementation using `org.tensorflow.lite.Interpreter` (from `com.google.ai.edge.litert:litert:2.3.2`), lazy-initialized off-main with 4 threads + XNNPACK, pre-allocated direct `ByteBuffer` input, upright 128×128 YUV→RGB crop, and `CornerLocator.isValidCubeGeometry` guard.
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/CornerModelTransform.kt` (new) -- pure JVM-testable upright crop sampling math, 0/90/180/270 rotation mapping (frame ↔ upright crop), and output tensor decoding (`decode(output, guide, cropHalf)`).
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/CornerDetectorModule.kt` (new) -- Hilt `@Module @InstallIn(SingletonComponent::class)` binding `LiteRtCornerDetector` to `CornerDetector` in the corner source set.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CameraModule.kt` -- add `@BindsOptionalOf internal abstract fun cornerDetector(): CornerDetector` so camera builds with corner flags OFF still compile cleanly without referencing `LiteRtCornerDetector`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- inject `Optional<CornerDetector>` (with default `Optional.empty()` for `CameraXFrameSourceTest` compatibility) into `CameraXFrameSource`; pass detector to `CornerReader.read(...)` (L264); add `includeAwb: Boolean = true` parameter to `meterOnGrid()` (L298–319) so Hold 2 avoids AWB shift on no-white `Y/O/B`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt` -- `CornerReader.read(...)` (L466–504) uses tighter 3D cell ROI (`CORNER_CELL_ROI_FRACTION = 0.48`) via `FrameReader.readAt(..., roiFraction = ...)`; keep `CornerDetector`, `CornerLocator`, `isValidCubeGeometry`, `unwarp`, `CornerOverlaySmoother` intact.
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- `readAt(frame, square, roiFraction = CELL_ROI_FRACTION)` (L246–261) adds default-valued `roiFraction` parameter; 2D Guided Scan callers unchanged.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `captureCorner()` (L519–540) holds fallback `next` phase in `GuidedScanViewModel.held` (generalized from `Review?` to `GuidedScanPhase?`) until `endFreeze()` so Capture 2 plays its full 400 ms cue; pass `includeAwb = (quick.hold == 1)` to `meterOnGrid()` on Quick Scan `Remeter`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `CameraArea()` (L444–466) draws `captureHighlight` as a child overlay `Box` with subtle translucent tint + 4 dp primary border and `.testTag("quickScan:captureFlash")`, fixing the tag overwrite by `.testTag("scanViewfinder")`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- inject `Optional<CornerDetector>` and pass to `CornerReader.read(...)` (L209) so debug corner recording uses the active LiteRT detector.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/ReleaseConventionPlugin.kt` -- inside `isCornerModelEnabled(levels)` (L91–94) add `dependencies.add("${buildType.name}Implementation", libs.library("litert"))`; set `androidResources.noCompress += "tflite"`; register `checkElfAlignment` task for variants with native libs.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/ElfAlignmentPolicy.kt` & `tasks/CheckElfAlignmentTask.kt` (new) -- pure-Kotlin ELF64 `PT_LOAD` alignment verifier (`p_align >= 16384` / `0x4000` for all `arm64-v8a`/`x86_64` `.so` entries in APKs/AARs); wired into `QualityConventionPlugin` `check`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckCameraSizeTask.kt` -- fix unminified debug CameraX jar over-counting (SCP 2026-11-20 §4.4) by accounting for R8-minified CameraX/LiteRT class footprint (or deduplicating/compressing jar estimates cleanly against `AppSizePolicy.BUDGET` while counting full single-ABI `.so` + `.tflite` assets uncompressed/compressed honestly).
- `build-logic/src/main/resources/cubentia-keep-rules.pro` -- verify `litert` + `litert-api` consumer ProGuard rules and add any required LiteRT JNI keep rules for R8 full mode.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/` -- update `AndroidFixture.kt` (add `litert` to synthetic catalog + `local-repo`), extend `CameraCompositionFunctionalTest.kt`, and add `ElfAlignmentPolicyTest.kt`.
- Tests & evidence: `app/src/testDebug/kotlin/dev/cubentia/app/camera/{CornerModelTransformTest,CornerLocatorTest,QuickScanScreenTest}.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt`, `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtCornerDetectorDeviceTest.kt` (on-device Lenovo TB128XU latency + accuracy test), and `docs/evidence/10-18/litert-model-evaluation.md`.

## Tasks & Acceptance

**Execution:**
- `tools/corner-model/{pyproject.toml,render_cube.py,train_corner_model.py,evaluate_model.py,README.md}` -- create reproducible synthetic 3D cube renderer (stickered + stickerless, solved + scrambled, real `Fixtures/live/` Lenovo/S24FE palettes, hands, cluttered desks, dark glossy reflections #196, negative frames, optional `Fixtures/corner/` fine-tune hook) and compact depthwise-separable CNN trainer exporting quantized ~150–400 KB `.tflite` to `app/src/camera/corner/assets/corner_keypoints.tflite` -- satisfies AC 2 model provenance, size, and #196 generalization.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/{ReleaseConventionPlugin.kt,QualityConventionPlugin.kt,policy/ElfAlignmentPolicy.kt,tasks/CheckElfAlignmentTask.kt,tasks/CheckCameraSizeTask.kt}` & `build-logic/src/main/resources/cubentia-keep-rules.pro` -- wire `litert` strictly under `isCornerModelEnabled`, configure `noCompress += "tflite"`, add 16 KB ELF alignment gate, adjust `CheckCameraSizeTask` unminified-jar accounting per SCP §4.4, and verify R8 keep rules -- satisfies AD-9, AD-13, AD-18, NFR-11, and `targetSdk 36` alignment.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/{functional/AndroidFixture.kt,functional/CameraCompositionFunctionalTest.kt,policy/ElfAlignmentPolicyTest.kt}` -- add `litert` to TestKit fixture and add unit/functional tests for corner-flag-gated LiteRT composition and 16 KB ELF alignment -- ensures `./gradlew -p build-logic test` enforces all new gates.
- `app/src/camera/corner/kotlin/dev/cubentia/app/camera/corner/{CornerModelTransform.kt,LiteRtCornerDetector.kt,CornerDetectorModule.kt}` -- implement upright 128×128 YUV→RGB crop transform, lazy off-main `org.tensorflow.lite.Interpreter` wrapper behind `CornerDetector` with `isValidCubeGeometry` guard and zero heuristic fallback, and Hilt binding -- satisfies AC 1 and AC 2.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{CameraModule.kt,ScanFrameSource.kt,FrameReader.kt,CornerLocator.kt}` & `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- add `@BindsOptionalOf CornerDetector`, inject `Optional<CornerDetector>` into `CameraXFrameSource` and `FixtureRecorderActivity`, add optional `roiFraction` (`0.48` for 3D `CornerReader`, default `0.6` for 2D), and add `includeAwb` control to `meterOnGrid()` -- wires LiteRT cleanly without breaking OFF-corner builds and fixes 3D side-face `R`/`L` readout.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{GuidedScanActivity.kt,GuidedScanScreen.kt}` -- fix `quickScan:captureFlash` overlay + tag overwrite, hold Capture 2 fallback transition in `GuidedScanViewModel.held` until `endFreeze()`, and skip Hold-2 AWB re-drift during Quick Scan `Remeter` -- satisfies AC 3 and AC 5 (#197).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/{CornerModelTransformTest.kt,CornerLocatorTest.kt,QuickScanScreenTest.kt}` & `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- add JVM tests for every I/O matrix row (0/90/180/270 crop round-trip, geometry guard, low-presence rejection, 3D shaded `R`/`L` Red/Orange separation on real Lenovo/S24FE palettes completing 2-corner Quick Scan with 0 `R`/`L` fallback, `quickScan:captureFlash` + Capture 2 fallback cue hold) -- covers AC 1, AC 3, AC 4, AC 5.
- `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtCornerDetectorDeviceTest.kt` & `docs/evidence/10-18/litert-model-evaluation.md` -- run on connected Lenovo TB128XU (`HA1S28MM`) against the main app debug build to verify real on-device median latency `< 40 ms`, pixel accuracy on held-out synthetic + warped real-palette frames, negative rejection, and flag any missing real `Fixtures/corner/` captures for PO device session -- satisfies AC 2 and AC 3.

**Acceptance Criteria:**
- Given a cube held by a corner in the framing zone at 0°/90°/180°/270° rotation, when a frame is analysed on the main app debug build, then `LiteRtCornerDetector` detects the seven visible corners `[T, UL, LL, B, LR, UR, C]` via `corner_keypoints.tflite`, guards them with `isValidCubeGeometry` + `CornerOverlaySmoother`, and `CornerReader` unwarps all three visible faces from those detected corners (never fixed `CornerGuide.quads`), with only the minimal 3-line Y anchor and smoothed detected outline drawn.
- Given the built app and CI gates, when `./gradlew -p build-logic test`, `./gradlew spotlessCheck build`, `./gradlew checkReleaseSize`, and `./gradlew checkCameraSize` run, then release (`OFF`) carries zero LiteRT classes/libs/assets, debug (`QUICK_SCAN=BETA`) carries `litert` + uncompressed `corner_keypoints.tflite`, `libLiteRt.so` passes 16 KB ELF page alignment, single-ABI installed size stays `< 30 MB` (NFR-11), and an R8-minified build with corner flags enabled compiles and runs inference cleanly.
- Given the connected Lenovo TB128XU (`HA1S28MM`) running the main app debug build, when `LiteRtCornerDetector` runs over ≥ 50 frames off the main thread, then median inference latency is `< 40 ms` per frame and held-out synthetic + real-palette warped cubes achieve reported pixel accuracy within tolerance while dark/glossy reflection-only and empty frames return `null`.
- Given synthetic 3D perspective-warped cubes rendered with real Lenovo and S24 FE `stka`/`stkb`/`stla`/`stlb` palettes under directional side-face shading, when evaluated through `CornerReader` → `SelfCalibration` → `QuickScanController`, then shaded Red (`R`) and Orange (`L`) separate cleanly without forced `R`/`L` 2D fallback, and the absence of committed `Fixtures/corner/` captures is explicitly documented for the PO device session.
- Given a dark or glossy background with no detected cube for ~3 s, when Quick Scan runs, then inline coaching displays `"Try a plain, light surface."` (from `en.json`).
- Given Quick Scan Capture 1 and Capture 2 (including when Capture 2 triggers Guided fallback), when each capture occurs, then a subtle flash (`quickScan:captureFlash`), captured corner thumbnail (`quickScan:thumbnail`), enlarged counter (`quickScan:counter`), and the same `ShutterSound` play on both captures without relying on haptics alone.

## Auto Run Result

Status: done

**Summary:** Quick Scan corner detection now runs a 396.6 KB FP16 LiteRT 7-keypoint model (`corner_keypoints.tflite`, SHA-256 4875896238d96564175912e9bfdb1d2a0b7a341705e98aad525a9bf83c73720d) behind `CornerDetector`, composed only when `QUICK_SCAN`/`CASE_RECOGNITION` is above OFF. It adds a reproducible synthetic-render training pipeline, a 16 KB ELF alignment gate, single-ABI size accounting, R8 keep rules, a tighter 3D cell ROI plus Hold-2 no-AWB metering for Red/Orange side faces, and both capture-cue fixes (#197). This run resumed the interrupted implementation, kept it, fixed one stale build-logic test, and added the missing-model matrix test.

**Files changed:**
- `tools/corner-model/` — renderer, trainer, evaluator, README, uv project (`.venv`, `artifacts/` incl. `*.weights.h5` git-ignored).
- `app/src/camera/corner/` — `LiteRtCornerDetector`, `CornerModelTransform`, `CornerDetectorModule`, `assets/corner_keypoints.tflite`.
- `app/src/camera/kotlin/.../{CameraModule,ScanFrameSource,FrameReader,CornerLocator,GuidedScanActivity,GuidedScanScreen}.kt` and debug `FixtureRecorderActivity.kt` — optional detector wiring, ROI 0.48, metering flags, flash overlay tag, Capture-2 fallback hold.
- `build-logic/` — LiteRT composition under the corner flag, `noCompress tflite`, `ElfAlignmentPolicy`/`CheckElfAlignmentTask`, `CheckCameraSizeTask` accounting, keep rules, tests.
- Tests — `CornerModelTransformTest`, `LiteRtCornerDetectorFailureTest`, `CornerDetectorBindingTest`, `CornerLocatorTest`, `CameraXFrameSourceTest`, `QuickScanScreenTest`, `QuickScanRouteTest`, instrumented `LiteRtCornerDetectorDeviceTest`.
- `docs/evidence/10-18/litert-model-evaluation.md` — model evaluation, provenance, device-gate notes.

**Review findings:** 41 findings from 4 Claude layers. Patches applied for 19 rows (13 distinct fixes; medium and low), see the triage log. Deferred: 3 device gates (D1 latency/accuracy re-run, D2 on-device R/L and real frames incl. AWB drift, D3 R8 inference), all medium. Rejected: 19 rows (6 false, 13 low/duplicate), each with its reason in the triage log.

**Follow-up review recommendation:** true — more than two medium entries were patched (patched by verdict: medium 12 rows, low 7 rows). Named unverified risk: the strengthened device test (real palettes, 100% negative rejection) and the 0.48 ROI change have no hardware or end-to-end evidence yet; the ROI change could not be shown to matter on synthetic data.

**Verification:**
- `evaluate_model.py`: 396.6 KB (< 450 KB); precision 99.84%, recall 98.28%, negative rejection 99.79%; dark/glossy reflection and empty frames 100% rejected; mean error ~1.4 px.
- `./gradlew -p build-logic test`: BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build checkReleaseSize checkCameraSize :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL; release estimate 12.41 MB, projected camera size 26.73 MB (< 30 MB).
- `:app:connectedDebugAndroidTest` (LiteRtCornerDetectorDeviceTest): not run this session (no device required); deferred as D1.
- Matrix audit: every I/O matrix row has a passing JVM/functional test; the < 40 ms device part of row 2 is the deferred device gate.

**Residual risks:** the model is trained on synthetic renders plus real palettes only (no `Fixtures/corner/` captures); real-camera generalization is the main risk until the PO device session.

### Follow-up review pass (2026-11-20)

- Four Claude review layers (blind, edge-case, verification-gap, intent) re-reviewed the full diff since `859e7f5a`: 42 findings, 0 high.
- Patches applied (medium 2, low 5): CameraXFrameSource injected-detector test; emulator guard on the device test's latency assertion; device test poses widened to the trained range; AWB KDoc corrected (no lock); dataset chunking fixed at 8 independent of core count; evidence doc states the in-distribution nature of the eval and recipe-not-SHA reproducibility; stale training comment fixed.
- Deferred: nothing new; carried findings fold into device gates D1-D3.
- Files changed: app/src/testDebug/.../CameraXFrameSourceTest.kt (new test), app/src/androidTestDebug/.../LiteRtCornerDetectorDeviceTest.kt (emulator guard, poses), app/src/camera/.../ScanFrameSource.kt (KDoc), tools/corner-model/render_cube.py (fixed chunks), tools/corner-model/train_corner_model.py (comment), docs/evidence/10-18/litert-model-evaluation.md (wording).
- Verification: evaluate_model.py (396.6 KB, precision 99.84%, recall 98.28%, unchanged); `./gradlew -p build-logic test` BUILD SUCCESSFUL; `./gradlew spotlessCheck build checkReleaseSize checkCameraSize` BUILD SUCCESSFUL (projected camera 26,727,904 B < 30,000,000); `:app:compileDebugAndroidTestKotlin` OK. The connected Lenovo run was not performed (D1).
- Follow-up review recommended: false (follow-up pass patched no high finding).
- Residual risks: D1-D3 device gates. The device test's widened poses and the emulator latency skip have been compiled but not run.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 41 findings — high 0, medium 18, low 16, false 6, maybe-false 1
- findings:
  - `[medium]` `[defer]` (blind) Device results in the evidence doc vs the spec's no-device Resume Notes; raw device report/logcat not committed — the 13.99 ms median comes from a real earlier run (app/build androidTest XML + logcat, verified by the implementer) but is not reproducible from the repo; recorded as device gate D1 for a PO re-run.
  - `[false]` `[reject]` (blind) epic-10-context claims fine-tuning on real Lenovo/S24 FE frames — the compiled context restates the planned architecture decision from planning artifacts, not a claim of completed work; the evidence doc states no Fixtures/corner captures exist.
  - `[medium]` `[defer]` (blind) No evaluation on real camera frames — real, but no Fixtures/corner captures exist and the intent requires documenting that absence; recorded as device gate D2.
  - `[medium]` `[patch]` (blind) Fine-tune hook trains on whole-frame resizes with frame-normalized keypoints and silently swallows bad files — fixed: hook now requires guide-centred square crops labeled positive/negative with crop-normalized keypoints, fails loudly on malformed files; contract in README.
  - `[medium]` `[defer]` (blind) No automated R8 inference check; extra nnapi keep rule — minified corner-flag build was verified locally (spec allows local verification) but inference on a minified APK needs a device; recorded as D3. The nnapi keep line is harmless.
  - `[medium]` `[patch]` (blind) ELF gate can pass vacuously with 0 inspected libs and only reads input AARs — fixed: CheckElfAlignmentTask fails when a litert archive is present but no libLiteRt*.so was checked (new ElfAlignmentPolicyTest case). APK zip-alignment part rejected: AGP aligns uncompressed libs by default.
  - `[low]` `[reject]` (blind) 40% R8 jar retention factor is arbitrary — the spec-sanctioned (SCP §4.4) estimate is conservative against the one measured minified build (26.72 MB estimate vs 25.90 MB measured); calibrating it would add machinery for little gain.
  - `[medium]` `[patch]` (blind) R/L side-face test does not pin the 0.48 ROI or exercise ΔE — fixed: ΔE >= 10 asserted for all 8 real palettes (measured ~23-27); added a deterministic ROI test that fails at 0.6 and passes at 0.48. End-to-end 0.6 vs 0.48 difference could not be shown on synthetic data (noted).
  - `[low]` `[reject]` (blind) Default `meter(includeAwb) = meter()` silently drops the flag for non-overriding implementations — only CameraXFrameSource and test fakes implement it and all override; making it abstract would touch every fake.
  - `[low]` `[patch]` (blind) Model load failure leaves detection off for the process; close() leaves initAttempted set — close() now resets initAttempted. Exposing an 'unavailable' state with Guided hand-over rejected: the intent's matrix specifies degrading to null, and adding a state is new surface.
  - `[low]` `[reject]` (blind) Python geometry guard duplicates the Kotlin one; PIL bilinear vs nearest-neighbour sampling — parity drift is possible but the device benchmark runs the real Kotlin path; a parity harness is more than a direct fix.
  - `[low]` `[patch]` (blind) Training pipeline reproducibility (and-cuda extra, no artifact hash) — fixed: evidence doc records the .tflite SHA-256 and TF 2.19.1/converter versions; README documents the Linux+CUDA requirement and the CPU swap.
  - `[false]` `[reject]` (blind) Spec drift and epic-context constraints lost — spec edits are rejected by rule; the two epic constraints are still present in reworded form (release flags OFF on merge; privacy-facts regenerated when flags are raised).
  - `[medium]` `[patch]` (gap) Production Hilt wiring of LiteRtCornerDetector into Optional<CornerDetector> untested — fixed: CornerDetectorBindingTest (Robolectric Hilt) asserts the Optional is present and is LiteRtCornerDetector.
  - `[medium]` `[patch]` (gap) includeAwb -> FocusMeteringAction flags untested — fixed: extracted CameraXFrameSource.meteringFlags(includeAwb), tested both values in CameraXFrameSourceTest.
  - `[medium]` `[patch]` (gap) R/L test does not pin CORNER_CELL_ROI_FRACTION — same fix as the blind R/L row (shared root cause).
  - `[medium]` `[defer]` (gap) Real LiteRT inference runs only in an instrumented test CI never executes — needs a device; folded into device gate D1.
  - `[low]` `[patch]` (gap) close() leaves initAttempted = true — same fix as the blind close() row.
  - `[low]` `[patch]` (gap) FixtureRecorderActivity isInitialized guard masks Hilt injection failure — removed the guard.
  - `[low]` `[reject]` (edge) Held Guided fallback can overwrite Unavailable if the camera fails inside the 400 ms freeze — needs a camera failure inside a 400 ms window; the fix adds a guard branch.
  - `[low]` `[patch]` (edge) fillRgbInput outside the try can throw onto the analyzer thread — moved inside the try with run().
  - `[low]` `[reject]` (edge) Per-frame Log.w flooding on repeated inference failure — needs a model that loads but always fails; the fix adds state.
  - `[low]` `[patch]` (edge) Device test end-to-end CornerReader check silently skipped when frame 0 is missed — now runs on the first detected positive and asserts it ran.
  - `[low]` `[patch]` (edge) evaluate_model.py silently falls back to training crops for the held-out slice — now exits naming the missing groups.
  - `[medium]` `[patch]` (edge) Fixtures/corner JSON without keypoints becomes a negative — same fix as the blind fine-tune hook row.
  - `[medium]` `[patch]` (edge) Fine-tune fixture coordinate frame mismatch — same fix as the blind fine-tune hook row.
  - `[low]` `[reject]` (edge) 24000-sample dataset memory peak — training already completed on the dev machine; streaming the dataset is a refactor, not a direct fix.
  - `[low]` `[reject]` (edge) Odd img_size breaks chroma subsampling reshape — default and only used size is 128; the fix adds a guard.
  - `[medium]` `[patch]` (edge) ELF gate passes with 'Checked 0' — same fix as the blind ELF row.
  - `[low]` `[reject]` (edge) Corrupt archive gives an opaque ZipException — the task still fails loudly, which is correct behavior.
  - `[false]` `[reject]` (edge) readAt roiFraction out of range — the only callers pass the 0.48 constant or the 0.6 default.
  - `[maybe-false]` `[defer]` (edge) Hold 2 without FLAG_AWB may not stop AWB drift — needs on-device colour capture to settle; folded into device gate D2 as medium (unverified).
  - `[medium]` `[patch]` (edge) Device test accepts >= 90% negative rejection although AC 3 requires null — now requires 100%.
  - `[medium]` `[patch]` (edge) Device test measures only the flat test palette — added 24 real Lenovo/S24 FE palette frames (from Fixtures/live U.json calibrated centres), scored separately; compiled, not run.
  - `[false]` `[reject]` (intent) orElse(CornerLocator) still runs the heuristic when the Optional is empty — the Optional is empty only when QUICK_SCAN and CASE_RECOGNITION are both OFF, and then MainActivity/AppShell hide Quick Scan so corner mode is unreachable.
  - `[low]` `[reject]` (intent) No committed gate measures a minified flags-ON release under 30 MB — same root cause as the blind 40% row; rejected for the same reason.
  - `[medium]` `[defer]` (intent) R8 inference never run — same root cause as the blind R8 row; device gate D3.
  - `[medium]` `[defer]` (intent) Device-number contradiction between evidence doc and Resume Notes — same root cause as the blind device-results row; D1.
  - `[medium]` `[patch]` (intent) ΔE >= 10 never asserted — same fix as the blind R/L row.
  - `[false]` `[reject]` (intent) ShutterSound not asserted on both captures — captureCorner calls shutterSound.play() on the same `capture != null` branch for both captures, and ShutterSoundTest covers the player.
  - `[false]` `[reject]` (intent) Dark-surface coaching timing with LiteRT nulls untested — the coaching depends only on null detections, covered by QuickScanControllerTest (PLAIN_LIGHT_SURFACE) and QuickScanScreenTest (copy).

### 2026-11-20 — Review pass (follow-up)
- verdicts: 42 findings — high 0, medium 9, low 14, false 16, maybe-false 3
- findings:
  - `[low]` `[patch]` (intent) Device test poses (pitch 22-44, yaw ±16, roll ±16) are narrower than the intent's input range — widened to the full trained range (pitch 16-52, yaw ±26, roll ±28); compiled, not run (D1).
  - `[medium]` `[defer]` (intent) Evidence-doc logcat predates the current device test (no realPalette fields) — carried: same as the device-results row; device gate D1.
  - `[false]` `[reject]` (intent) `orElse(CornerLocator)` falls back when the Optional is absent — carried: the Optional is empty only when both corner flags are OFF, and then Quick Scan is unreachable.
  - `[medium]` `[defer]` (intent) R/L check uses an exact detector on synthetic faces; AWB fix verified only as flags — carried: device gate D2.
  - `[false]` `[reject]` (intent) ShutterSound/thumbnail not asserted for Capture 2 on the route — carried: captureCorner plays the sound on the same `capture != null` branch for both captures.
  - `[medium]` `[defer]` (intent) Size projection uses the 40% R8 estimate and R8 inference never ran — carried: 40% row rejected (low), R8 inference is device gate D3.
  - `[medium]` `[reject]` (intent) ELF gate scans classpath AARs, not the APK — carried: the ELF row's APK part was rejected (AGP aligns uncompressed libs; no first-party .so exists).
  - `[false]` `[reject]` (intent) Dark-surface coaching with LiteRT nulls untested — carried: covered by QuickScanControllerTest and QuickScanScreenTest.
  - `[false]` `[reject]` (intent) Diff edits epic-10-context.md despite the planning-artifacts ban — the file is under implementation-artifacts, which the intent does not forbid.
  - `[low]` `[patch]` (intent) Held-out figures are mostly in-distribution synthetic — evidence doc §2 now states that only heldout_real_crop_3d withholds data and the figures are not live-camera accuracy (same root cause as blind row 9).
  - `[false]` `[reject]` (intent) 406,100-byte FP16 model misses "tiny quantized ~150-400 KB" — 396.6 KiB is within the approximate range and FP16 weight quantization is quantization; the spec's budget is < 450 KB.
  - `[medium]` `[patch]` (gap) CameraXFrameSource's use of the injected detector is unverified — added CameraXFrameSourceTest.cornerFramesUseTheInjectedDetectorInsteadOfTheHeuristic (Optional.of(recording detector), corner mode, asserts it was called with the frame's rotation and its null result reached ScanFrame.corner); passes.
  - `[medium]` `[patch]` (gap) Device test's < 40 ms assertion runs on the release workflow's CI emulator — latency assertion now skipped on emulators (ranchu/goldfish/generic/sdk); accuracy and rejection still asserted there.
  - `[medium]` `[defer]` (gap) LiteRT keep rules not checked by any automated minified build — carried: device gate D3.
  - `[medium]` `[defer]` (blind) Device evidence out of date vs the committed test — carried: D1.
  - `[false]` `[reject]` (blind) Epic context claims real Lenovo/S24 FE fine-tuning; S24 FE not benchmarked — carried: the context restates the planned decision; real-frame validation is D2.
  - `[maybe-false]` `[defer]` (blind) Skipping AWB on Hold 2 does not keep Hold 1's white balance (no lock; auto-cancel after 3 s) — carried: D2. The KDoc's "keep Hold 1's white balance" was inaccurate and now says it only avoids re-metering on a no-white grid and is no lock.
  - `[low]` `[reject]` (blind) Model load failure disables detection for the process — carried: close() reset patched earlier; an unavailable state is new surface.
  - `[low]` `[reject]` (blind) JVM failure test hits only the LinkageError branch, not a native rejection of a corrupt model — the IllegalArgumentException path is a generic `catch (e: Exception)`; reaching it needs an instrumented test, more than a direct fix.
  - `[low]` `[reject]` (blind) No build check ties the .tflite to its I/O shape, size and SHA — a mismatched model fails the device test's detection asserts; a JVM flatbuffer check is new machinery.
  - `[low]` `[patch]` (blind) Dataset depends on the machine's core count — generate_dataset now always uses 8 chunks (pool size still follows cores); the evidence doc says the committed artifact predates this and a GPU retrain reproduces the recipe, not the SHA. evaluate_model.py output unchanged (its slices use the single-chunk path).
  - `[low]` `[reject]` (blind) Python geometry guard is an untested mirror of Kotlin — carried.
  - `[low]` `[patch]` (blind) Held-out headline is in-distribution — same fix as intent row 10.
  - `[low]` `[reject]` (blind) Nearest-neighbour app crop vs bilinear training — carried.
  - `[low]` `[reject]` (blind) `meter(includeAwb)` default drops the flag — carried.
  - `[medium]` `[reject]` (blind) ELF gate checks only dependency AARs, not APK zip alignment — carried (ELF row's APK part).
  - `[false]` `[reject]` (blind) `noCompress += "tflite"` is global and appended per build type — the .tflite exists only in the corner source set, so corner-OFF variants have nothing to leave uncompressed; a duplicate extension entry changes nothing.
  - `[false]` `[reject]` (blind) Silent CornerLocator fallback — carried false.
  - `[false]` `[reject]` (blind) .tflite size accounting not in code — CheckCameraSizeTask.check sums cameraAssets (wired in ReleaseConventionPlugin) into CameraSizePolicy.evaluate as assetBytes; the report prints "Camera model/runtime assets".
  - `[low]` `[patch]` (blind) Contradictory hold-out comments in train_corner_model.py; no SHA print — comments replaced with one accurate line; printing the SHA rejected (new feature, the doc records it).
  - `[low]` `[reject]` (edge) run() can throw an Error (UnsatisfiedLinkError/OOM) onto the analyzer thread — the native library is already loaded once the interpreter exists, and OOM should not be swallowed; the fix adds a catch branch.
  - `[false]` `[reject]` (edge) Rotation not a multiple of 90 — CameraX ImageInfo.rotationDegrees is always 0/90/180/270.
  - `[low]` `[reject]` (edge) Held Guided fallback overwrites Unavailable/back during the freeze — carried.
  - `[maybe-false]` `[reject]` (edge) A Guided capture lands between the analyzer's freeze end and main-thread endFreeze — would need a stable Guided capture within milliseconds of the fallback; settling it needs a timing trace; if true it is low (a missed cue on one capture).
  - `[false]` `[reject]` (edge) Capture-2 fallback `next` and a review both non-null — phaseOf(quick) returns null once Done (not in corner, no fallback), so they never coexist.
  - `[false]` `[reject]` (edge) ELF phoff overflow crashes the task — a malformed ELF makes the gate fail loudly, which is correct.
  - `[low]` `[reject]` (edge) Corrupt archive gives a raw ZipException — carried.
  - `[false]` `[reject]` (edge) First-party .so escapes the ELF gate — no jniLibs or .so exist in app/ or core/.
  - `[false]` `[reject]` (edge) readAt roiFraction out of range — carried.
  - `[maybe-false]` `[defer]` (edge) Hold 2 without FLAG_AWB leaves AWB drifting — carried: D2 (KDoc corrected, see blind row 3).
  - `[false]` `[reject]` (edge) 0.48 ROI changes recorder exports vs fixtures recorded at 0.60 — no Fixtures/corner captures exist.
  - `[false]` `[reject]` (edge) Model size claim exceeded — same refutation as intent row 11.

## Design Notes

- **Upright crop coordinate contract (`CornerModelTransform`):**
  Let `(cx, cy)` be `guide.vertices().last()`, `half = guide.radius * 1.65`, and `rot = Math.floorMod(guide.rotationDegrees, 360)`. An upright normalized crop point `(u, v) in [0, 1]^2` maps to upright offset `dx = (u - 0.5) * 2 * half`, `dy = (v - 0.5) * 2 * half`, and to frame offset via the exact inverse of `ViewfinderGrid.frameToViewFraction`:
  - `rot == 0`: `(fx, fy) = (dx, dy)`
  - `rot == 90`: `(fx, fy) = (dy, -dx)`
  - `rot == 180`: `(fx, fy) = (-dx, -dy)`
  - `rot == 270`: `(fx, fy) = (-dy, dx)`
  Frame pixel is `(cx + fx, cy + fy)`. This guarantees the CNN always sees an upright Y-corner regardless of camera sensor orientation (portrait 90°, landscape 0°/180°, reverse 270°).
- **Model architecture & size target:**
  Input `[1, 128, 128, 3]` `float32` in `[0, 1]` (or `uint8` `[0, 255]`, matched between trainer and `LiteRtCornerDetector`), 5–6 depthwise-separable inverted-residual stages (channels `16 → 24 → 40 → 64 → 96 → 128`), spatial pooling / `Conv2D(1x1)` + linear head outputting `[1, 15]` (`[presence_logit, x0, y0, ..., x6, y6]` with `sigmoid` applied in model or Kotlin). FP16 or INT8 weight quantization yields ~150–350 KB `.tflite` and ~5–15 ms on Snapdragon 680 CPU with XNNPACK (4 threads).
- **LiteRT Maven coordinate vs Java package:**
  `com.google.ai.edge.litert:litert:2.3.2` (with transitive `litert-api:2.3.2`) packages `org.tensorflow.lite.Interpreter` + `jni/*/libLiteRt.so` (verified `PT_LOAD Align 0x4000` on all ABIs). `CameraClasspathPolicy` checks Maven coordinates (`com.google.ai.edge.litert` allowed iff `cornerModelEnabled`; `org.tensorflow` Maven group stays forbidden).
- **R8 verification strategy:**
  Because release keeps camera flags `OFF` on `main`, R8 verification of `LiteRtCornerDetector` runs via a dedicated build-logic functional check / local minified verification (temporarily testing `QUICK_SCAN = BETA` in a release-minified build check or inspecting R8 mapping + running `assembleRelease` with corner flags in a temporary test check without leaving release flags raised in git) AND confirming consumer `proguard.txt` + `cubentia-keep-rules.pro` retain `org.tensorflow.lite.*` JNI bridges.

## Resume Notes (PO clarifications, 2026-11-20)

- This run is a RESUME. The worktree already holds an uncommitted implementation from an interrupted earlier run (tools/corner-model/ with a trained checkpoint and eval.json, app/src/camera/corner/ with LiteRtCornerDetector + assets/corner_keypoints.tflite, ElfAlignmentPolicy/CheckElfAlignmentTask, CornerModelTransformTest, docs/evidence/10-18/litert-model-evaluation.md, app/src/androidTestDebug/, and edits to tracked files). Inspect it with `git status` / `git diff`, keep what is correct, and finish only what is missing or wrong. Do not retrain the model unless the committed artifact is broken or out of the size budget.
- Never commit `tools/corner-model/.venv`, `__pycache__`, or large training checkpoints (`*.weights.h5`); `.gitignore` must cover them. The `.tflite` is committed only if within the `< 450 KB` budget.
- No physical Lenovo TB128XU is available in this session. Build the instrumented benchmark/test (`LiteRtCornerDetectorDeviceTest`) so it compiles (`:app:assembleDebugAndroidTest` / `compileDebugAndroidTestKotlin`), but do not run it and do not fake device numbers. The `< 40 ms` median measurement and the on-device R/L side-face check are deferred device gates (severity medium) for the PO.
- Use only Claude for any delegated work (no Codex, no agy/Gemini).

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-18 && uv run --project tools/corner-model python tools/corner-model/evaluate_model.py` -- expected: reports pixel error, presence precision/recall, dark-glossy rejection, and `.tflite` size `< 450 KB`
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-18 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-18 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build checkReleaseSize checkCameraSize` -- expected: BUILD SUCCESSFUL, all size/classpath/ELF/manifest/denylist/Kover gates green
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-18 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.LiteRtCornerDetectorDeviceTest` (on Lenovo TB128XU `HA1S28MM`) -- expected: PASS with median latency `< 40 ms`

**Manual checks (if no CLI):**
- PO fast hands-on BETA evaluation on Lenovo TB128XU and Galaxy S24 FE (both stickered scrambled/solved and stickerless cubes, plain and dark/glossy surfaces) runs after PR opens.
