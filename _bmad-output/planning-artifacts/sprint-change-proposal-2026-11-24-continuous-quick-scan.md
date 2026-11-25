---
title: 'Sprint Change Proposal: continuous facelet Quick Scan (LiteRT v2 INT8 Android port)'
date: 2026-11-24
status: approved
mode: batch
trigger: PO request after PR #363 (PC continuous-scan spike), PR #366 (3-device Android R8 benchmark), and PR #367 (0.11.0 camera release timeline)
scope: Moderate
resolves:
  - scp-2026-11-24-quick-scan-port-stories
updates:
  - scp-2026-11-24-quick-scan-raise-preconditions
---

# Sprint Change Proposal: continuous facelet Quick Scan

## Change Analysis Checklist Summary

| Section | Item | Status | Finding |
| --- | --- | :---: | --- |
| 1. Trigger & Context | 1.1 Triggering story | `[x]` | Story 10.21 PO BETA call (2026-11-24) left `QUICK_SCAN` OFF on the 2-corner pipeline (`docs/evidence/10-21/device-session-notes.md`), routing the rethink to the continuous facelet scan spike (PR #363, PR #366) and PR #367 (`sprint-change-proposal-2026-11-24-camera-release-timeline.md`). |
| 1. Trigger & Context | 1.2 Core problem | `[x]` | **Failed approach requiring different solution:** rigid 2-corner holds suffer near-zero side-face coverage and fragile calibration-center checks on real devices; continuous multi-view facelet scanning with a clean-room `256×256 → 64×64` INT8 sticker-center detector resolves all three failure modes within NFR-11. |
| 1. Trigger & Context | 1.3 Evidence | `[x]` | PR #363 (7/7 end-to-end LiteRT stress scenarios `54/54`, 90/90 = 100% real `Fixtures/corner` faces, 8× held-out webcam 2-face jump, `54/54` untouched held-out scan, 0/13 false twists) + PR #366 (R8 `cornerR8` on all 3 physical devices: Lenovo TB128XU `33.74 ms` / ~30 FPS, Galaxy Tab S7 FE 5G `16.46 ms` / ~60 FPS, Galaxy S24 FE `8.81 ms` / ~113 FPS; live `:app:checkCameraSize` `28.00 MB`, `2.00 MB` NFR-11 headroom). |
| 2. Epic Impact | 2.1–2.5 Epics 10, 11, 12 | `[x]` | Epic 10 drops superseded 2-corner stories `10.24–10.27` (routing Story 10.27's non-corner-model `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` `#166` to a tracked non-blocking follow-up action item) and adds 4 focused continuous-Quick-Scan stories (`10.30–10.33`) ahead of Story 10.11's `0.11.0` run. Epic 11 (`11.2` Placement uses Guided Scan only, unchanged; `11.3` Case recognition stays untouched on `app/src/camera/corner/` and OFF in 0.11.0/v1.0.0). Epic 12 (`12.5`) is unchanged. |
| 3. Artifact Conflicts | 3.1 PRD | `[x]` | §4.8 summary bullet (`prd.md:477`), FR-22 (`prd.md:487-496`), §4.8 Out of Scope (`prd.md:564-566`), §6.2 Not planned (`prd.md:652`), §9 OQ-7 (`prd.md:738`), §10 Decisions (`prd.md:767`), and PRD `.memlog.md`. No NFR regression (NFR-8, NFR-11, NFR-12 all met). |
| 3. Artifact Conflicts | 3.2 Architecture | `[x]` | AD-9 (`ARCHITECTURE-SPINE.md:254-259`, including sole-rotator clarification), AD-18 (`:391`), Stack table (`:470`), Structural Seed (`:493, :512`), Architecture `.memlog.md`, `docs/ARCHITECTURE.md` (updated in Story 10.31), and `build-logic` policies (`FeatureFlagLevels`, `ReleaseConventionPlugin`, `CameraSizePolicy`, `CheckCameraSizeTask`, `CameraClasspathPolicy`, `ShadowReaderPolicy`, `GenerateKeepRulesTask`). |
| 3. Artifact Conflicts | 3.3 UI/UX | `[x]` | `EXPERIENCE.md:102` Quick Scan capture row updated verbatim from rigid 2-corner holds to live growing-net continuous scanning, preserving torch rules (Story 10.19), Story 10.17 Guided handover + back-out, Story 10.15 `#321` seeded Editor fallback, `"Discard this scan?"` Close confirmation, TalkBack, and color-blind glyphs (AD-17). |
| 3. Artifact Conflicts | 3.4 Secondary artifacts | `[x]` | `sprint-status.yaml` (drop `10-24..10-27`, add `10-30..10-33`, close obsolete 2-corner `11.1`/`10.21` action items including `epic-11-retro-item-104`, track `#166` `KNOWN_PASTEL_PROBLEMS`, update PR #367 action items), `tools/center-model/` reproducible recipe + `model-card.json` (Story 10.31), `docs/evidence/10-33/`. |
| 4. Path Forward | 4.1–4.4 Evaluation | `[x]` | **Option 1 (Direct Adjustment) + clean source-set split** selected (Medium effort, Low risk): split `QUICK_SCAN` onto `app/src/camera/continuous/` (`sticker_centers_v2_int8.tflite`, 369.4 KB), keep `CASE_RECOGNITION` untouched on `app/src/camera/corner/` (OFF in 0.11.0/v1.0.0), retire debug `sticker_reader.tflite` + `CapturedFaceObserver` plumbing, remove obsolete 2-corner Quick Scan code in Story 10.32, ship via `10.30 → 10.31 → 10.32 → 10.33`. |
| 5. SCP Components | 5.1–5.5 | `[x]` | Complete proposal with verbatim `OLD → NEW` edits and handoff plan below. |
| 6. Final Review | 6.1–6.5 | `[x]` | Adversarial review (16 findings) applied (§6); explicitly approved by PO (BMad) on 2026-11-24 and applied to all planning artifacts. |

---

## 1. Issue Summary

**Problem statement.** At Story 10.21 (2026-11-24), the PO judged the rigid 2-corner Quick Scan pipeline non-viable for BETA (`QUICK_SCAN` stayed `OFF`) because real-device side-face coverage was near zero and calibration center checks failed even immediately after calibrating (`docs/evidence/10-21/device-session-notes.md`). PR #367 (`sprint-change-proposal-2026-11-24-camera-release-timeline.md`) aligned the release timeline around shipping **0.11.0** to closed testing with `CAMERA_SCAN` GA, `PLACEMENT` BETA, and the **new Quick Scan as BETA** (then `v1.0.0` to production), explicitly gating `QUICK_SCAN` on this SCP amending AD-9, AD-18, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22, and PRD §4.8, replacing `10.24–10.27` with concrete port stories, benchmarking all three devices with the Lenovo TB128XU as minimum floor, and verifying `:app:checkCameraSize` / `:app:checkReleaseSize`.

**Proven evidence (PR #363 and PR #366):**
- **Clean-room LiteRT v2 INT8 model (`sticker_centers_v2_int8.tflite`, `378,288 B` / `369.4 KB`, `UINT8[1,256,256,3] → FLOAT32[1,64,64,3]`, full SHA-256 `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`):** trained strictly on synthetic 1–3 visible-face renders + non-held-out `Fixtures/live` + verified cube-free tuning backgrounds (`0` real `Fixtures/corner/` photos or held-out video clips in training; `0` unlicensed/AGPL weights).
- **Still fixtures (`Fixtures/corner`, canonical holdout untouched):** `98.4%` tuning / `92.6%` held-out sticker hit, `94.5%` negative rejection (`n=73`), **90/90 (100%) 3-face recovery & 90/90 (100%) shared edges**.
- **End-to-end stress suite (`pixels → LiteRT → lattice_fit → mapper`):** **7/7 PASS** (`54/54`, `0` wrong, `0` transient wrong on Scenarios 1–6; mid-scan layer twist caught cleanly on Scenario 7).
- **15 real handheld webcam sessions (10 tuning + 5 strictly untouched held-out):** held-out 2-face detection jumped **8×** over v1 (`3.5% → 27.8%` INT8 / `28.1%` FP16), hitting **54/54 (`0` conflicts)** on untouched `stlb_scramble_high-cool` and **52/54** on dim `stlb_low-cool`, with **0/13 false twists**.
- **Physical Android R8 (`cornerR8`) benchmark across all 3 tiers (`640×480` steady-state, 2 XNNPACK CPU threads, bit-for-bit identical peak parity `stka=30 / stlb=28 / stla=28`):**
  - **Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, Snapdragon 680, API 33, gating minimum):** `30.62 ms` infer / **`33.74 ms` total `p50` (`33.88 ms` `p95`, ~30 FPS)** — **no Galaxy S24 floor needed**.
  - **Galaxy Tab S7 FE 5G (`SM-T738U`, Snapdragon 750G, API 31):** `13.52 ms` infer / **`16.46 ms` total `p50` (`16.60 ms` `p95`, ~60 FPS)**.
  - **Galaxy S24 FE (`SM-S721U1`, Exynos 2400e, API 37):** `7.62 ms` infer / **`8.81 ms` total `p50` (`9.23 ms` `p95`, ~113 FPS)**.
- **Live `:app:checkCameraSize` & `:app:checkReleaseSize` (on `80907e19`):** release estimate `13,018,831 B` (`13.02 MB`); current camera projection `28,025,769 B` (`28.03 MB` with `406,100 B` `corner_keypoints.tflite`) → **`27,997,957 B` (`28.00 MB`, `2.00 MB` NFR-11 headroom, `-27.8 KB` net, `0` extra `.so` bytes)** when `QUICK_SCAN` ships only `sticker_centers_v2_int8.tflite` (`378,288 B`).

---

## 2. Impact Analysis

- **Epic impact:**
  - **Epic 10:** Superseded 2-corner stories `10.24`, `10.25`, `10.26`, and `10.27` are formally dropped (never started). Story 10.27's non-corner-model item (`ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` under `#166` / FR-24 pastel plausibility for Guided Scan) is routed to a dedicated non-blocking action item in `sprint-status.yaml` so it is never silently orphaned. Four clean port stories (`10.30 → 10.31 → 10.32 → 10.33`) are added ahead of Story `10.11`'s `0.11.0` run.
  - **Epic 11:** Story `11.1` stays `done` as historical 2-corner baseline; Story `11.2` (Placement) uses Guided 6-face Scan only (`MainActivity.kt:155-167`, `quick = false`) and is **unchanged** — Quick Scan's main-app entry point remains the Solve tab (`SolveScreen` → `onQuickScan`); Story `11.3` (Case recognition) stays untouched on `app/src/camera/corner/` and stays `OFF` in `0.11.0` / `v1.0.0`.
  - **Epic 12:** Unchanged (`12.5` still promotes `v1.0.0` after `10.11`).
- **Artifact conflicts:**
  - **PRD (`prd.md` + `.memlog.md`):** §4.8 summary bullet (`:477`), FR-22 (`:487-496`), §4.8 Out of Scope (`:564-566`), §6.2 Not planned (`:652`), §9 OQ-7 (`:738`), §10 Decisions (`:767`).
  - **Architecture Spine (`ARCHITECTURE-SPINE.md` + `.memlog.md`):** AD-9 (`:254-259`), AD-18 (`:391`), Stack table (`:470`), Structural Seed (`:493, :512`), plus `docs/ARCHITECTURE.md` updated in Story 10.31.
  - **Build policies (`build-logic` — implemented in Story 10.31):** `FeatureFlagLevels.kt`, `ReleaseConventionPlugin.kt`, `CameraSizePolicy.kt`, `CheckCameraSizeTask.kt`, `CameraClasspathPolicy.kt`, `ShadowReaderPolicy.kt`, `GenerateKeepRulesTask.kt`, plus TestKit updates (`ShadowReaderCompositionFunctionalTest.kt`, `ShadowReaderPolicyTest.kt`, `CheckCameraSizeTaskTest.kt`, `FeatureFlagLevelsTest.kt`).
  - **UX (`EXPERIENCE.md`):** Quick Scan capture row (`:102`).
  - **Sprint tracking (`sprint-status.yaml`):** drop `10-24..10-27`, add `10-30..10-33`, close obsolete 2-corner `11.1`/`10.21` deferred action items (including `epic-11-retro-item-104` which otherwise blocks `10.11`), add follow-up item for `KNOWN_PASTEL_PROBLEMS` (`#166`), and update PR #367 action items.
- **Technical impact:**
  - **Zero new Gradle modules (AD-1 compliant):** Pure-JVM `FaceLatticeFitter` and `ContinuousFaceletMapper` live in `:core:domain` (`dev.cubentia.domain.recognition.continuous.*`) with ≥ 80% Kover coverage and zero Android/OpenCV imports.
  - **Clean source-set split (PO decision 2026-11-24):**
    - `FeatureFlagLevels` separates `isContinuousModelEnabled` (`QUICK_SCAN`), `isCornerModelEnabled` (`CASE_RECOGNITION`), and `isLiteRtEnabled` (`QUICK_SCAN || CASE_RECOGNITION`).
    - `app/src/camera/continuous/` (`sticker_centers_v2_int8.tflite` `378,288 B`, `LiteRtStickerCenterDetector`, `ContinuousQuickScanController/Screen`) composes when `QUICK_SCAN` is above `OFF`.
    - Legacy `app/src/camera/corner/` (`corner_keypoints.tflite` `406,100 B`, `LiteRtCornerDetector`) composes **only** when `CASE_RECOGNITION` is above `OFF` (so `0.11.0`/`v1.0.0` release ships **only** `sticker_centers_v2_int8.tflite`).
    - `CheckCameraSizeTask` excludes `dev/cubentia/app/camera/corner/**` classes and `src/camera/corner/assets` when projecting the `0.11.0` `CASE_RECOGNITION=OFF` camera release (and does not double-count once Story 10.11 raises release flags above `OFF`).
  - **Retire Story 10.23 debug shadow sticker reader (PO decision 2026-11-24):** In Story 10.31, delete `app/src/camera/debug/sticker/`, remove `ShadowReaderPolicy.composes(...)` wiring and manifest inclusion from `ReleaseConventionPlugin.kt`, remove `CapturedFaceObserver` / `CapturedFaceObserverOptionalModule` / `GuidedScanActivity` / `ScanFrameSource` shadow plumbing and the 4 `app/src/testDebug/.../sticker/` tests, update `ShadowReaderCompositionFunctionalTest.kt` to assert only the non-debuggable release guard, and keep `tools/sticker-model/` archived as historical Story 10.23 evidence.
  - **Remove obsolete 2-corner Quick Scan code in Story 10.32:** Delete Quick-Scan-only 2-corner classes/tests (`QuickScan.kt`, `QuickScanClassification.kt`, `CornerHold.kt`, `QuickScanDiagnosis.kt`, `SideLight.kt`, and 2-corner branches in `GuidedScanActivity`/`GuidedScanScreen`) not referenced by `CASE_RECOGNITION` or `FixtureRecorderActivity`, reclaiming `3× classBytes` under `CheckCameraSizeTask`.
  - **R8 & CI compatibility:** Keep `cornerR8` composing both model sets so `LiteRtCornerDetectorDeviceTest` and `CiWorkflowTest` stay green, add `LiteRtStickerCenterDetectorDeviceTest` to `cornerR8`, and verify `QUICK_SCAN=BETA, CASE_RECOGNITION=OFF` asset/class isolation in `build-logic` TestKit plus a real minified `0.11.0`-profile size check.

---

## 3. Recommended Approach

**Selected approach:** **Direct Adjustment (Option 1) with clean source-set split and 4 staged stories (`10.30 → 10.31 → 10.32 → 10.33`).**

| Story | Scope | Gate / Verification | Effort |
| --- | --- | --- | --- |
| **10.30** | Pure-JVM `FaceLatticeFitter` + `ContinuousFaceletMapper` in `:core:domain` (canonical `Orientation.HOME` output, typed Story 10.17/10.16/`#321`/`#323` handoff payload, committed `Fixtures/corner/` peak parity fixture) | Pure JVM unit + parity tests (7/7 scenarios, 90/90 `Fixtures/corner` faces, ≥ 80% Kover) | Medium (1–2 days) |
| **10.31** | `app/src/camera/continuous/` (`sticker_centers_v2_int8.tflite` + `tools/center-model/` reproducible recipe & `model-card.json`, 2 XNNPACK threads, direct YUV→RGB with `rotationDegrees` & preview-overlay inverse), `build-logic` source-set split & size/classpath policies, retire `app/src/camera/debug/sticker/` + `CapturedFaceObserver`, update `docs/ARCHITECTURE.md` | `./gradlew spotlessCheck build`, `./gradlew -p build-logic test`, `:app:checkCameraSize` (`≤ 28.2 MB`), real minified `0.11.0`-profile size check (`< 30 MB`), R8 `cornerR8` on Lenovo TB128XU & S24 FE (`640×480` + `1280×960`, full end-to-end `≤ 50 ms` Lenovo / `≤ 20 ms` S24 FE) | Medium (1–2 days) |
| **10.32** | Continuous Quick Scan live viewfinder UI from Solve tab (growing net, cues, reset, optional torch, Guided fallback keeping confirmed faces + Back-out, Story 10.3 Review + Story 10.16 retake + `#321` seeded Editor, TalkBack & color-blind glyphs) + cleanup of obsolete 2-corner Quick Scan code | Robolectric/Compose UI + accessibility tests, `:app:checkCameraSize` re-verified after cleanup | Medium (1–2 days) |
| **10.33** | Physical-device acceptance matrix (`≥ 20` full scans / `≥ 1,080` stickers across cubes & lights including warm, cool, low, backlit/glare) & PO BETA call on Lenovo TB128XU (gating), Galaxy S24 FE (gating), and Galaxy Tab S7 FE 5G (compatibility) + BETA-badge check | `docs/evidence/10-33/continuous-quick-scan-beta.md` + PRD `.memlog.md` BETA call feeding Story 10.11 (`0.11.0`) | Small (0.5–1 day) |

**Risks & mitigations:**
- **Schedule risk to `0.11.0`:** Preserves PR #367's explicit fallback — if `10.30–10.33` slip, Story 10.11 can still cut `0.11.0` with `CAMERA_SCAN` GA + `PLACEMENT` BETA and add `QUICK_SCAN` in `0.11.1` (`10.11-2`).
- **Port parity drift between Python and Kotlin:** Mitigated by Story 10.30's committed golden parity fixture and 7-scenario JVM test suite.
- **Full-pipeline latency on Lenovo Snapdragon 680:** Mitigated by Story 10.31 benchmarking the *entire* per-frame pipeline (`YUV→RGB + LiteRT + decode + FaceLatticeFitter + ContinuousFaceletMapper`) at both `640×480` and `1280×960` under R8 (`≤ 50 ms` `p50` Lenovo gate).
- **NFR-11 size surprise at Story 10.11:** Mitigated by requiring an actual minified size measurement of the `0.11.0` flag profile (`CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA, CASE_RECOGNITION=OFF`) in Story 10.31 and re-verifying after 2-corner cleanup in Story 10.32 / 10.33.

---

## 4. Detailed Change Proposals

Every `OLD` block below quotes the target file verbatim.

### 4.1 PRD (`_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md` and `.memlog.md`)

**PRD §4.8 Quick Scan summary bullet (`prd.md:477`)**

OLD:
> - **Quick Scan** (fewer captures). The Learner holds the cube by a corner, so each capture shows three faces, and two or three captures cover all six. If the result is not confident and valid, the app falls back to the Guided 6-face Scan for the missing or uncertain faces only.

NEW:
> - **Quick Scan** (continuous facelet scan). *(SCP 2026-11-24 continuous-quick-scan)* The Learner turns the cube continuously in front of the camera (1 to 3 visible faces per frame) while the app stitches faces across shared edges into a growing 6-face map without fixed corner holds or a drawn guide. At any point the Learner can fall back to the Guided 6-face Scan for the missing faces only.

**PRD FR-22 (`prd.md:487-496`)**

OLD:
> #### FR-22: Quick Scan (corner views)
> The Learner can capture the cube in 2–3 corner-angle photos, each showing three faces.
>
> **Consequences (testable):**
> - Two captures from opposite corners yield all 54 stickers.
> - If perspective, glare or occlusion leaves stickers uncertain or the state invalid, the app asks for only the faces it is missing, using the Guided 6-face Scan. It never produces a guessed state.
> - Quick Scan detects the cube's real edges and corners in the camera frame and unwarps each face from them; the Learner never has to line the cube up with a drawn guide, and no perspective-correct guide sketch is used (PO, 2026-11-20, Story 10.18, #195). *(SCP 2026-11-20)*
> - Quick Scan helps the Learner light the cube: when the light is behind the cube it says so and waits, it meters on the cube, it uses the Learner's saved color calibration when one exists, and it turns on the torch where the device has one. It never turns an uncertain sticker into a suggested color. *(SCP 2026-11-21, Story 10.19)*
> - The Learner can also switch from Quick Scan to the Guided 6-face Scan at any time. Faces already captured are kept, the Guided Scan asks only for the others, and all six faces are assembled together. *(SCP 2026-11-20, #198)*
> - Quick Scan is labeled "beta" unless it meets its own targets (§4.8 NFRs).

NEW:
> #### FR-22: Quick Scan (continuous facelet scan)
> *(SCP 2026-11-24 continuous-quick-scan: replaces the parked 2-corner hold pipeline.)* The Learner can turn the cube continuously in front of the camera — showing 1 to 3 faces per frame from any starting side — while the app tracks visible faces, stitches them across shared edges into a growing 6-face map, and completes all 54 stickers without rigid pose holds or a drawn guide sketch.
>
> **Consequences (testable):**
> - Turning the cube steadily through all six sides yields a verified 54-sticker Scan Result without requiring two fixed opposite-corner poses or a drawn perspective guide.
> - The scanner self-calibrates shading-invariant colors across all six observed centers (working on stickered, stickerless, and pastel cubes, with or without saved Color Calibration), recovers across regrips only when orientation is unambiguous (never locking a symmetric single face), and detects mid-scan layer twists by pausing and asking the Learner to hold layers fixed and rescan.
> - Immediate physical cube invariants (piece legality, color counts, corner chirality) reopen low-evidence conflicting stickers and coach the Learner to show the affected face clearly; it never produces a guessed or invalid state.
> - The Learner can reset the scan or switch to *Scan face by face* (Guided 6-face Scan) at any time: every already-confirmed face is kept, the Guided Scan asks only for the missing faces, and all six faces are assembled together. *(SCP 2026-11-20, #198; preserved)*
> - Quick Scan is labeled "beta" unless it meets its own targets (§4.8 NFRs).

**PRD §4.8 Out of Scope (`prd.md:564-566`)**

OLD:
> **Out of Scope:**
> - Importing photos from the gallery.
> - Tracking Moves continuously from live video.

NEW:
> **Out of Scope:**
> - Importing photos from the gallery.
> - Tracking cube **Moves** continuously from live video during solving *(SCP 2026-11-24 continuous-quick-scan: fixed-layer continuous facelet scanning in Quick Scan (FR-22) is in scope and detects/rejects mid-scan layer twists)*.

**PRD §6.2 Not planned (`prd.md:652`)**

OLD:
> - **Not planned:** importing photos from the gallery; tracking Moves from live video.

NEW:
> - **Not planned:** importing photos from the gallery; tracking cube Moves from live video during solving *(fixed-layer continuous facelet scanning in FR-22 is in scope, SCP 2026-11-24 continuous-quick-scan)*.

**PRD §9 Open Questions — OQ-7 (`prd.md:738`)**

OLD:
> the Kotlin classifier stays the reader of record, and giving the model a say in a result needs a further decision on held-out evidence.

NEW:
> the Kotlin classifier stays the reader of record, and giving the model a say in a result needs a further decision on held-out evidence. **Updated 2026-11-24 (SCP 2026-11-24 continuous-quick-scan, Stories 10.30–10.33):** following Story 10.21's OFF call on the 2-corner pipeline and PC/3-device R8 proof (PR #363, PR #366), Quick Scan switches to continuous facelet scanning using a clean-room LiteRT v2 INT8 sticker-center detector (`sticker_centers_v2_int8.tflite`, 369.4 KB, 2 XNNPACK threads, isolated in `app/src/camera/continuous/` gated on `QUICK_SCAN`) paired with a pure-JVM `FaceLatticeFitter` + `ContinuousFaceletMapper` in `:core:domain`. Story 10.23's debug shadow sticker reader (`sticker_reader.tflite`) is retired; Guided 6-face Scan stays pure Kotlin; `CASE_RECOGNITION` keeps legacy `app/src/camera/corner/` gated solely on `CASE_RECOGNITION` (OFF in 0.11.0/v1.0.0).

**PRD §10 Decisions Taken During PRD Creation (append after `prd.md:767`)**

OLD:
> - 2026-11-22: LiteRT is allowed for per-face sticker color classification in Quick Scan, lifting "LiteRT stays in corner detection only". Story 10.23 adds a shadow reader in debug builds with a runtime toggle; it changes no scan result and adds no bytes to release. The first model trains on existing fixtures and earlier capture evidence, extra real captures where coverage is thin, and synthetic data, with whole capture groups held out (PO, SCP 2026-11-22 quick-scan-sticker-reader).

NEW:
> - 2026-11-22: LiteRT is allowed for per-face sticker color classification in Quick Scan, lifting "LiteRT stays in corner detection only". Story 10.23 adds a shadow reader in debug builds with a runtime toggle; it changes no scan result and adds no bytes to release. The first model trains on existing fixtures and earlier capture evidence, extra real captures where coverage is thin, and synthetic data, with whole capture groups held out (PO, SCP 2026-11-22 quick-scan-sticker-reader).
> - 2026-11-24: Quick Scan replaces the parked 2-corner pipeline with continuous facelet scanning using Cubentia's clean-room LiteRT v2 INT8 sticker-center model (`sticker_centers_v2_int8.tflite`, 369.4 KB, 2 XNNPACK threads, in `app/src/camera/continuous/` gated on `QUICK_SCAN`) and pure-JVM `FaceLatticeFitter` + `ContinuousFaceletMapper` in `:core:domain`. Story 10.23's debug shadow sticker reader is retired; `CASE_RECOGNITION` keeps `app/src/camera/corner/` gated solely on `CASE_RECOGNITION` (OFF in 0.11.0/v1.0.0); Lenovo TB128XU stays the gating minimum (`33.74 ms` R8 ~30 FPS, S7 FE `16.46 ms`, S24 FE `8.81 ms`; `:app:checkCameraSize` `28.00 MB`) (PO, SCP 2026-11-24 continuous-quick-scan, Stories 10.30–10.33).

**PRD `.memlog.md` — append via `memlog.py` upon approval:**
- `(decision by user)` SCP 2026-11-24 continuous-quick-scan: replace parked 2-corner Quick Scan with continuous facelet scan (`sticker_centers_v2_int8.tflite` 369.4 KB, SHA-256 `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`, 2 XNNPACK threads, pure-JVM `FaceLatticeFitter` + `ContinuousFaceletMapper`); split `app/src/camera/continuous/` (`QUICK_SCAN`) from `app/src/camera/corner/` (`CASE_RECOGNITION`, OFF in 0.11.0/v1.0.0); retire Story 10.23 debug shadow sticker reader; drop superseded Stories 10.24–10.27 in favor of Stories 10.30–10.33; keep Lenovo TB128XU as gating minimum (`33.74 ms` R8 ~30 FPS, S7 FE `16.46 ms` ~60 FPS, S24 FE `8.81 ms` ~113 FPS, `:app:checkCameraSize` `28.00 MB` / `2.00 MB` headroom). Quick Scan's release BETA level itself is recorded only by Story 10.33's PO BETA call.
- `(change)` PRD updated (SCP 2026-11-24 continuous-quick-scan): §4.8 summary bullet, FR-22, §4.8 Out of Scope, §6.2 Not planned, §9 OQ-7, and §10 Decisions updated.

---

### 4.2 Architecture Spine (`_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md` and `.memlog.md`)

**AD-9 — Camera adapter / Detection method / Quick Scan sticker reading / Assembly (`ARCHITECTURE-SPINE.md:254-259`)**

OLD:
>     - Quick Scan's detection and perspective correction live inside the adapter and emit the same type.
>     - Quick Scan metering on the detected cube and the torch are camera-adapter concerns in `app/src/camera/`; the torch needs no permission and is used only when the back camera reports a flash unit. Quick Scan classifies side faces with the Learner's saved calibration when one exists (SCP 2026-11-21, Story 10.19). The saved calibration records the torch state it was taken under, and Quick Scan uses it only when that matches the current torch and the captured face centres read as their own colours against it; otherwise it self-calibrates (SCP 2026-11-21 quick-scan-side-face-accuracy, Story 10.21).
>     - **Detection method** (OQ-7 decision K, PO 2026-11-15, Story 10.13, `docs/spikes/oq-7-recognition-approach.md`; Quick Scan fallback activated PO 2026-11-20, SCP 2026-11-20): Guided 6-face Scan stays pure Kotlin, and the Kotlin sticker classifier (per-cell histogram-mode reader + self-calibration from the six face centers + nearest-center classifier in `:core:domain`) stays the reader of record for every scan. For **Quick Scan 3D corner detection**, the pre-approved **LiteRT** fallback is activated (`docs/evidence/10-18/quick-scan-litert-escalation.md`): a tiny quantized 7-keypoint `.tflite` model (`com.google.ai.edge.litert`) predicts `[T, UL, LL, B, LR, UR, C]` inside `app/src/camera/corner/` behind `CornerDetector`, feeding the existing `unwarp()` and `FrameReader` pipeline. `build-logic` gates the LiteRT dependency and corner assets strictly on corner-model flags (`QUICK_SCAN`, `CASE_RECOGNITION`) above `OFF`, so builds with those flags `OFF` carry zero LiteRT runtime or model bytes. OpenCV remains rejected on size (NFR-11). Native `.so` size gates count the largest single Play delivery ABI (`arm64-v8a`) and enforce NFR-11 (< 30 MB) plus 16 KB ELF page alignment (`targetSdk 36`).
>     - **Quick Scan sticker reading** (PO 2026-11-22, SCP 2026-11-22 quick-scan-sticker-reader, Story 10.23): LiteRT is also allowed for per-face sticker color classification on the unwarped Quick Scan faces. The model sits behind a sticker-reader seam in the camera source set and returns only categorical per-sticker results (a color and a `StickerConfidence`); face crops stay in memory and are discarded when the scan ends. Story 10.23 adds it in **shadow mode only**: the reader and its `.tflite` model live in the debug camera source set, run once per capture, log beside the Kotlin reads and have no input to classification, the keep rule, assembly or review, and a debug-only control switches it on and off. Release and benchmark builds carry no sticker model bytes and no reader class (`build-logic` check). Letting the model take part in a result needs a further SCP with held-out per-sticker evidence and the NFR-11 size check. Training and evaluation hold out whole capture groups.
>   - **Assembly and classification:**
>     - Only `ScanAssembler` in `:core:domain` rotates samples into `Facelets` order. The Manual Cube Editor's per-face rotation (SCP 2026-11-20, #253) and the Quick Scan → Guided Scan hand-over that keeps captured faces (#198) use the same `ScanAssembler` entry on face colors; neither rotates faces itself. A sample with no `upNeighborColor` gets the quarter turn that makes the six faces a state `CubeValidator` accepts; when no turn or more than one distinct state fits, it reports the faces involved for a re-prompt with a named top and never guesses (Story 10.14, SCP 2026-11-16). With no valid state it reports them only when they are at most `MAX_REPROMPT_FACES` (2); more go to review with the best fit and no re-prompt (SCP 2026-11-19, #164).

NEW:
>     - Quick Scan's pixel detection and cell sampling live inside the adapter (`app/src/camera/continuous/`); only neutral 2D sticker-center coordinates, per-cell sampled RGB/CIELAB statistics, and sharpness/glare quality scalars cross into `:core:domain` (never raw frames or Android types).
>     - Camera metering and the optional torch stay camera-adapter concerns in `app/src/camera/`; the torch needs no permission and is shown only when the back camera reports a flash unit.
>     - **Detection method & reader of record** *(amended SCP 2026-11-24 continuous-quick-scan, Stories 10.30–10.33; supersedes 2-corner Quick Scan escalation of SCP 2026-11-20)*:
>       - **Guided 6-face Scan** stays pure Kotlin (`FaceLocator` + `StickerClassifier` + `ScanAssembler` in `:core:domain`), unchanged.
>       - **Quick Scan (continuous facelet scan):** uses Cubentia's clean-room LiteRT v2 INT8 sticker-center heatmap model (`sticker_centers_v2_int8.tflite`, `378,288 B` / `369.4 KB`, `UINT8[1,256,256,3] → FLOAT32[1,64,64,3]`, XNNPACK CPU **2 threads**, off-main `analyzerExecutor`) inside **`app/src/camera/continuous/`** behind an optional `@BindsOptionalOf` `StickerCenterDetector` seam. All `org.tensorflow.lite.Interpreter` calls live inside `app/src/camera/` so shipped R8 keep rules hold. Its pure-JVM reader of record in `:core:domain` is **`FaceLatticeFitter` + `ContinuousFaceletMapper`** (intrinsics-free 2D shared-edge face graph, 24-SO(3) regrip merger with symmetry + historical center-color lock + outlier guards, bounded log-RGB AWB drift, shading-invariant CIELAB + WHITE-referenced hue distance + exact 8-per-color balanced refinement, immediate local cubie invariants with evidence-weighted conflict reopening, and per-fragment mid-scan layer-twist detection).
>       - **Case recognition (legacy 2-corner):** `app/src/camera/corner/` (`corner_keypoints.tflite`, `406,100 B`, `LiteRtCornerDetector`) stays gated solely on `CASE_RECOGNITION` above `OFF` (which is `OFF` in 0.11.0 and v1.0.0, so release ships zero `corner_keypoints.tflite` bytes).
>       - OpenCV remains rejected on size (NFR-11). Native `.so` size gates count the largest single Play delivery ABI (`arm64-v8a`) and enforce NFR-11 (`< 30 MB`) plus 16 KB ELF page alignment (`targetSdk 36`).
>     - **Retired Story 10.23 shadow sticker reader** *(SCP 2026-11-24 continuous-quick-scan, Story 10.31)*: `app/src/camera/debug/sticker/` (`sticker_reader.tflite`, toggle, `CapturedFaceObserver` hook) is removed because it failed Story 10.23's safety bar and continuous Quick Scan replaces it with the pure-JVM `ContinuousFaceletMapper`. `ShadowReaderPolicy` remains in `build-logic` as a non-debuggable release guard rejecting any `sticker_reader*.tflite` asset or `dev/cubentia/app/camera/sticker/` class.
>   - **Assembly and classification:**
>     - In `:core:domain`, `ScanAssembler` stays the sole sample rotator for Guided 6-face Scan, the Manual Cube Editor's per-face rotation (SCP 2026-11-20, #253), Story 10.16 per-face retake, and the Quick Scan → Guided Scan hand-over that keeps captured faces (#198). For a completed continuous Quick Scan, `ContinuousFaceletMapper` in `:core:domain` resolves mutual 6-face orientation across shared edges and emits canonical `Orientation.HOME` (`WHITE` on U, `GREEN` on F) `Facelets` + typed per-face evidence directly into `ScanReview` (and exports confirmed faces in `ScanAssembler`'s kept-face format for Story 10.17 Guided handover without reclassifying kept faces) *(SCP 2026-11-24 continuous-quick-scan)*. A sample with no `upNeighborColor` gets the quarter turn that makes the six faces a state `CubeValidator` accepts; when no turn or more than one distinct state fits, it reports the faces involved for a re-prompt with a named top and never guesses (Story 10.14, SCP 2026-11-16). With no valid state it reports them only when they are at most `MAX_REPROMPT_FACES` (2); more go to review with the best fit and no re-prompt (SCP 2026-11-19, #164).

**AD-18 — Feature flags compose the build (`ARCHITECTURE-SPINE.md:391`)**

OLD:
>   - **Flags compose the build (SCP 2026-11-14; corner-model split SCP 2026-11-20):** when a build type has `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` or `CASE_RECOGNITION` above OFF, `build-logic` adds `app/src/camera/` (`kotlin/`, `assets/`, manifest) and CameraX dependencies to that build type; otherwise neither is added. The corner-model source set (`app/src/camera/corner/` Kotlin + `.tflite` assets) and LiteRT dependency are composed only when a corner-model flag (`QUICK_SCAN` or `CASE_RECOGNITION`) is above OFF (`CameraClasspathPolicy` and `CameraSizePolicy` enforce this in CI).

NEW:
>   - **Flags compose the build (SCP 2026-11-14; corner-model split SCP 2026-11-20; continuous split SCP 2026-11-24 continuous-quick-scan):** when a build type has `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` or `CASE_RECOGNITION` above OFF, `build-logic` adds `app/src/camera/` (`kotlin/`, `assets/`, manifest) and CameraX dependencies to that build type; otherwise neither is added. LiteRT (`com.google.ai.edge.litert:litert`, excluding `ai-delivery`) is added whenever `isLiteRtEnabled` (`QUICK_SCAN` or `CASE_RECOGNITION` above OFF), with two separate model source sets:
>     - **`QUICK_SCAN` above OFF** (`isContinuousModelEnabled`) composes **`app/src/camera/continuous/`** (`kotlin/`, `assets/sticker_centers_v2_int8.tflite` `378,288 B`, manifest).
>     - **`CASE_RECOGNITION` above OFF** (`isCornerModelEnabled`) composes **`app/src/camera/corner/`** (`kotlin/`, `assets/corner_keypoints.tflite` `406,100 B`, manifest).
>     - Thus in `0.11.0` and `v1.0.0` (`CASE_RECOGNITION = OFF`), release ships **only** `sticker_centers_v2_int8.tflite` (`369.4 KB`, `0` `corner_keypoints.tflite` bytes). `CameraClasspathPolicy`, `CameraSizePolicy` / `CheckCameraSizeTask` (projecting the shipped `0.11.0` camera set without double-counting `OFF` `corner` assets or classes), and `ShadowReaderPolicy` enforce this in CI.

**Stack table (`ARCHITECTURE-SPINE.md:470`)**

OLD:
> | LiteRT | `com.google.ai.edge.litert:litert` 2.3.2 (camera corner source set, gated on `QUICK_SCAN` / `CASE_RECOGNITION` above OFF; 7-keypoint `.tflite` model, SCP 2026-11-20. Debug camera source set only: shadow sticker-reader model, SCP 2026-11-22 quick-scan-sticker-reader) |

NEW:
> | LiteRT | `com.google.ai.edge.litert:litert` 2.3.2 (`app/src/camera/continuous/` gated on `QUICK_SCAN` above OFF: clean-room `sticker_centers_v2_int8.tflite` `369.4 KB` INT8, 2 XNNPACK threads, SCP 2026-11-24 continuous-quick-scan; legacy `app/src/camera/corner/` gated solely on `CASE_RECOGNITION` above OFF: `corner_keypoints.tflite`, OFF in 0.11.0/v1.0.0; Story 10.23 debug shadow sticker reader retired) |

**Structural Seed (`ARCHITECTURE-SPINE.md:493, 512`)**

OLD (`:493`):
>       recognition/                     # FaceSample, ScanAssembler, StickerClassifier, ScanResult

NEW (`:493`):
>       recognition/                     # FaceSample, ScanAssembler, StickerClassifier, ScanResult, continuous/ (FaceLatticeFitter, ContinuousFaceletMapper)

OLD (`:512`):
>     corner/{kotlin,assets}/            # LiteRT 7-keypoint corner detector + .tflite model, only when QUICK_SCAN or CASE_RECOGNITION is above OFF (AD-9, AD-18)

NEW (`:512`):
>     continuous/{kotlin,assets}/        # LiteRT v2 INT8 sticker-center detector + sticker_centers_v2_int8.tflite, only when QUICK_SCAN is above OFF (AD-9, AD-18)
>     corner/{kotlin,assets}/            # legacy LiteRT 7-keypoint corner detector + corner_keypoints.tflite, only when CASE_RECOGNITION is above OFF (AD-9, AD-18)

*(Reader-facing `docs/ARCHITECTURE.md` lines 165, 194, 200 are updated alongside code in Story 10.31.)*

**Architecture `.memlog.md` — append via `memlog.py` upon approval.**

---

### 4.3 UX Design (`_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md`)

**`Quick Scan capture` row (`EXPERIENCE.md:102`)**

OLD:
> | Quick Scan capture | Scan sub-flow | Hold the cube by a corner and capture twice (the counter shows "1 / 2"). Any face that can't be read hands off to the Guided Scan grid for that face only. **Switch (SCP 2026-11-20, #198):** a *Scan face by face* action is always visible. It keeps every face already captured with confidence and opens the Guided Scan for only the remaining faces (the mini net shows the kept faces as done); all six are then assembled together and reviewed as usual. **Light (SCP 2026-11-21, Story 10.19):** when the cube's side faces are much darker than its top or the background, neither auto-capture nor the shutter takes a photo; after about 1 s inline coaching reads "Turn so the light shines on the cube.", and the hold clears once the faces are lit and steady. *Scan face by face* stays available and keeps any corner already captured. On a device with a flash, the torch is on during corner capture with a control to turn it off; the choice carries to the second corner, and the torch goes off when the Learner switches to face by face or leaves. On a device without a flash, nothing about the torch appears. **Kept faces (SCP 2026-11-21 quick-scan-side-face-accuracy, Story 10.21):** with a saved calibration that matches the scan, a face read with at most one uncertain sticker is kept, and that sticker arrives blank and pre-selected in the review; without one, a face is kept only when every sticker is confident. **Any corner first (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-a, Story 10.25):** the first photo takes whichever corner the Learner shows; the prompt reads "Hold the cube with a corner toward you, so three faces show." The app names the three faces from their centres, never from the prompt. The second prompt names the faces still missing ("Now turn the cube halfway, so yellow, blue and orange face you."); when the centres could not name the first corner, it reads "Now turn the cube halfway, so the other three faces show." Nothing blocks a first photo for showing "the wrong corner". **Same corner (#302, Story 10.25):** when the second hold still shows the faces of the first photo, after about 2 s inline coaching reads "That's the same corner as the first photo. Turn the cube halfway, or retake corner 1.", and *Retake corner 1* is highlighted. **Retake a corner (#299, Story 10.24):** while it waits for corner 2, the corner view shows an outlined *Retake corner 1* beside *Scan face by face*, and the system Back does the same; both return to corner 1 with the counter at "1 / 2" (Back at corner 1 leaves Quick Scan, as before). The face-by-face viewfinder of a hand-over also offers *Retake corner 1* (and *Retake corner 2* once taken); it drops the faces captured in the hand-over, as backing out does. In the Quick Scan review, retake mode offers *Retake corner 1* and *Retake corner 2* beside the face selection; each opens the corner view for that corner only and then returns to review. A retake keeps the better read face by face: a face read well enough to keep beats one that is not, then the read with more confident stickers wins, and a tie takes the new read. A face that keeps its earlier read keeps the Learner's fixes; a face that takes the new read shows only its new capture. Backing out of a retake before it captures changes nothing. TalkBack reads each control by its label and announces the new prompt. |

NEW:
> | Quick Scan capture | Scan sub-flow | *(SCP 2026-11-24 continuous-quick-scan; supersedes 2-corner holds of 10.18–10.25)* Opened from the Solve tab while `QUICK_SCAN` is BETA or GA (carrying the shared BETA label while BETA). Turn the cube smoothly in front of the camera from any starting side — no fixed corner pose or drawn guide sketch. The live preview outlines tracked faces while a compact growing 6-face `URFDLB` net fills in (`Sides mapped: N/6`, using shared `StickerAppearance` color-blind glyphs, AD-17). One inline cue line from `en.json` guides the next motion ("Bring cube into view", "Turn cube to show remaining {n} sides", "Tilt slightly to show an adjacent face", "Tilt slightly away from light", "Show {face} clearly to resolve conflict", "Layer turned mid-scan — hold layers fixed and rescan", "All 54 stickers verified!"). Regrips pause safely and re-align automatically once an adjacent face is shown (never locking a single symmetric face). **Torch:** on a device with a flash, an optional torch toggle (OFF by default to avoid harsh specular glare on close scans) turns off automatically on *Scan face by face*, leaving, or backgrounding; on a device without flash (e.g. Lenovo TB128XU), no torch control or copy appears. **Controls & fallback:** *Reset* clears the current map in place; *Scan face by face* stays always visible, keeping every already-confirmed face and handing only the remaining faces to Guided 6-face Scan (Story 10.17 — backing out of the Guided handover returns to Quick Scan with the confirmed faces intact); *Enter colors instead* stays visible and seeds the Manual Cube Editor on the Solve tab with confirmed stickers when ≥ 1 face was mapped (Story 10.15, `#321`). **Close & Back:** leave immediately at 0 mapped faces, and ask "Discard this scan?" once ≥ 1 face is mapped (matching Quick Scan's existing discard rule). Once 54/54 legal stickers are verified, Quick Scan opens Story 10.3 Scan Review (with Story 10.16 per-face retake and Paint mode). Fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp; TalkBack announces side-count milestones, cues, conflict face, and completion. |

---

### 4.4 Epics (`_bmad-output/planning-artifacts/epics.md`)

**Epic 10 `FRs covered` (`epics.md:235`)**

OLD:
> **FRs covered:** FR-21, FR-23 – FR-26, plus the Quick Check rung of FR-9 and FR-16, and the calibration reset of FR-19. **Also:** recognition NFRs, NFR-5, NFR-8, NFR-11, NFR-12, AD-9, AD-13, AD-18, AR-13, UX-DR15, UX-DR16, UX-DR19 – UX-DR22, UX-DR25.

NEW:
> **FRs covered:** FR-21 – FR-26, plus the Quick Check rung of FR-9 and FR-16, and the calibration reset of FR-19. **Also:** recognition NFRs, NFR-5, NFR-8, NFR-11, NFR-12, AD-9, AD-13, AD-18, AR-13, UX-DR15, UX-DR16, UX-DR19 – UX-DR22, UX-DR25.

**Epic 10 Order line (`epics.md:236`) — append cleanly after the closing `)*` of the `camera-release-timeline` note**

OLD:
> 12.5 then promotes a new v1.0.0 tag with the same levels.)*; all v1.0 scope (SCP 2026-11-20)

NEW:
> 12.5 then promotes a new v1.0.0 tag with the same levels.)* *(SCP 2026-11-24 continuous-quick-scan: 10.24–10.27 are dropped; the continuous Quick Scan port chain **10.30 → 10.31 → 10.32 → 10.33** replaces them and runs before the Story 10.11 run that raises `QUICK_SCAN`.)*; all v1.0 scope (SCP 2026-11-20)

**SCP 2026-11-20 v1.1 promotion paragraph (`epics.md:269`)**

OLD:
> *(SCP 2026-11-21 quick-scan-front-light: `QUICK_SCAN` reaches 10.11 only through Stories 10.17 and 10.19 and Story 10.8's matrix; if it stays OFF, 10.11 raises `CAMERA_SCAN` and `PLACEMENT` without it.)*

NEW:
> *(SCP 2026-11-21 quick-scan-front-light: `QUICK_SCAN` reaches 10.11 only through Stories 10.17 and 10.19 and Story 10.8's matrix; if it stays OFF, 10.11 raises `CAMERA_SCAN` and `PLACEMENT` without it.)* *(SCP 2026-11-24 continuous-quick-scan: superseded — `QUICK_SCAN` now reaches 10.11 through Stories 10.30–10.33 and Story 10.33's PO BETA call; if it stays OFF, 10.11 raises `CAMERA_SCAN` and `PLACEMENT` without it.)*

**Story 10.11 Depends (`epics.md:2245`)**

OLD:
> A run that raises `QUICK_SCAN` also depends on: the new Quick Scan port stories being done with their BETA call recorded, the Lenovo TB128XU staying the gating minimum unless a separate PO decision changes it; the spike session's SCP amending the architecture spine and PRD (AD-9 reader of record, AD-18 composition, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22 and §4.8's out-of-scope "continuous video"); and `:app:checkCameraSize` and `:app:checkReleaseSize` headroom measured with the new models in (the research estimate is about 2.0–2.5 MB).)*

NEW:
> A run that raises `QUICK_SCAN` also depends on: the new Quick Scan port stories being done with their BETA call recorded, the Lenovo TB128XU staying the gating minimum unless a separate PO decision changes it; the spike session's SCP amending the architecture spine and PRD (AD-9 reader of record, AD-18 composition, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22 and §4.8's out-of-scope "continuous video"); and `:app:checkCameraSize` and `:app:checkReleaseSize` headroom measured with the new models in (the research estimate is about 2.0–2.5 MB).)* *(SCP 2026-11-24 continuous-quick-scan: the spine/PRD/UX amendments are applied and the 3-device R8 benchmark is closed in PR #366 with Lenovo TB128XU kept as gating minimum (`33.74 ms`, ~30 FPS). Raising `QUICK_SCAN` now depends specifically on Stories **10.30–10.33** being done, including Story 10.31/10.33's minified `0.11.0`-profile size check (`< 30 MB`) and Story 10.33's PO BETA call recorded in the PRD memlog.)*

**Story 10.11 Annotation (`epics.md:2247`)**

OLD:
> and `QUICK_SCAN` to BETA only when the `QUICK_SCAN` preconditions under Depends hold. Otherwise it raises `CAMERA_SCAN` and `PLACEMENT` only, and `QUICK_SCAN` follows in a 10.11-n (0.11.x); the PO decides at 10.11 start. `CASE_RECOGNITION` stays OFF in 0.11.0.)*

NEW:
> and `QUICK_SCAN` to BETA only when the `QUICK_SCAN` preconditions under Depends hold (Stories 10.30–10.33 done and 10.33 BETA call recorded, SCP 2026-11-24 continuous-quick-scan). Otherwise it raises `CAMERA_SCAN` and `PLACEMENT` only, and `QUICK_SCAN` follows in a 10.11-n (0.11.x); the PO decides at 10.11 start. `CASE_RECOGNITION` stays OFF in 0.11.0.)*

**Stories 10.24, 10.25, 10.26, 10.27 superseded notes (`epics.md:2671, 2709, 2747, 2755, 2777`)**

OLD (all four headers):
> *(SCP 2026-11-24 camera-release-timeline: superseded by the continuous facelet scan approach (PR #363). Parked until the spike session's SCP replaces or drops it. Do not start.)*

NEW (all four headers):
> *(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: superseded and **dropped**; replaced by Stories 10.30–10.33. Do not start. For 10.27's non-corner-model `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` `#166`, see tracked follow-up action item `scp-2026-11-24-pastel-plausibility-guided-followup`.)*

OLD (`epics.md:2755`):
> *(SCP 2026-11-24 camera-release-timeline: that call (2026-11-24) left `QUICK_SCAN` OFF, so this story does not start; it is superseded with 10.24, 10.25 and 10.27.)*

NEW (`epics.md:2755`):
> *(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: dropped with 10.24, 10.25 and 10.27; replaced by Stories 10.30–10.33.)*

**New Stories 10.30–10.33 (inserted right after Story 10.29 in `epics.md`, before `## Epic 11:`):**

### Story 10.30: Port continuous facelet lattice fitter and cube mapper to pure JVM

As the developer,
I want the continuous facelet lattice fitter and cube mapper ported to pure-JVM Kotlin in `:core:domain`,
So that Quick Scan's geometry stitching, color anchoring, invariants, and twist detection are 100% deterministic, Android-free, and verified to parity with the PC spike.

**Refs:** FR-22, FR-23, FR-24 · NFR-4, NFR-12 · AD-1, AD-2, AD-9, AD-12, AD-14 · PR #363 (`pc_spike/lattice_fit.py`, `pc_spike/mapper.py`)

**Depends on** Story 10.1 (domain `Facelets`, `CubeValidator`, `ScanAssembler`). Device-free (pure JVM).

**Acceptance Criteria:**

**Given** unordered 2D sticker-center points `(x, y)` from 1 to 3 visible cube faces
**When** `FaceLatticeFitter.fit(centers)` runs in `:core:domain` (`dev.cubentia.domain.recognition.continuous`, zero Android/OpenCV imports)
**Then** it recovers up to 3 clockwise projective face quads using bimodal multi-scale neighborhood clustering, exact 4-point DLT homography solving, and tight geometry checks (`opp <= 1.55`, `mean_err <= 0.12 * d`), reproducing **90/90 (100%) faces and 90/90 (100%) shared edges** against a committed deterministic `Fixtures/corner/` peak parity JSON fixture under `core/domain/src/test/resources/fixtures/continuous/`

**Given** a stream of neutral per-frame quad observations (2D quad vertices + 9 sampled linear/sRGB patches + sharpness/glare quality scalars)
**When** `ContinuousFaceletMapper.processFrame(...)` runs
**Then** it maintains an intrinsics-free 2D shared-edge face graph with historical center-color tracking lock (`center_de <= 26`), 24-SO(3) regrip merger with symmetry + per-center (`<= 26`) + outlier (`< 2` cells `> 35`) guards, zero-mean bounded log-RGB AWB drift (`±0.36`), shading-invariant CIELAB + WHITE-referenced hue distance + exact 8-per-color balanced solver, local cubie invariants with evidence-weighted conflict reopening, and per-fragment mid-scan layer-twist detection (`de > 33` on an outer line for 5 frames on `master`), returning sealed domain snapshots and cue enums/keys (AD-12)

**Given** a completed or partial continuous scan (including starting on Yellow/Orange/Blue without White)
**When** the mapper exports its result
**Then** a completed 54-sticker state is canonicalized to `Orientation.HOME` (`WHITE` on U, `GREEN` on F, AD-2) so `ScanReview` never corrupts non-HOME scans; confirmed faces also export as a typed domain handoff payload (face colors + mutual orientation + self-calibrated center Labs / per-cell confidence) accepted by `ScanAssembler` for Story 10.17 Guided handover without reclassifying kept faces, by `ScanReview` for Story 10.16 *Retake faces* and `#323` suspect ranking, and by the Solve tab's `#321` seeded Editor fallback; saved Color Calibration (FR-24) is optional and never required because the mapper self-calibrates from the cube's own 6 centers

**Given** the 7 end-to-end stress scenarios ported as JVM parity tests (regrip, pastel + AWB drift, logo center + overhead glare, mid-solve F2L symmetric regrip, finger occlusion + clutter, starting without White, mid-scan layer twist)
**When** `./gradlew :core:domain:test :core:domain:koverVerify :core:domain:checkModuleBoundaries` runs
**Then** **7/7** pass (**54/54**, `0` wrong, `0` transient wrong on Scenarios 1–6; twist caught cleanly on Scenario 7), `:core:domain` stays ≥ 80% Kover line coverage, and module boundaries pass

### Story 10.31: Wire LiteRT v2 INT8 sticker-center detector, split `continuous` source set, and retire shadow reader

As the developer,
I want Quick Scan's clean-room LiteRT v2 INT8 detector bundled in its own build-time source set with reproducible provenance and the failed debug shadow reader retired,
So that release `0.11.0` ships only the 369.4 KB INT8 model within NFR-11 while leaving `CASE_RECOGNITION` untouched and OFF.

**Refs:** FR-22 · NFR-5, NFR-8, NFR-11 · AD-9, AD-13, AD-14, AD-15, AD-18 · OQ-7 · PR #363, PR #366

**Depends on** Story 10.30. **Human gate:** R8 `cornerR8` device benchmark on **Lenovo TB128XU** (minimum gating) and **Galaxy S24 FE** (mid gating).

**Acceptance Criteria:**

**Given** `build-logic` (`FeatureFlagLevels`, `ReleaseConventionPlugin`, `CameraSizePolicy`, `CheckCameraSizeTask`, `CameraClasspathPolicy`, `ShadowReaderPolicy`, `GenerateKeepRulesTask`)
**When** build variants are configured
**Then** `FeatureFlagLevels` splits `isContinuousModelEnabled` (`QUICK_SCAN`), `isCornerModelEnabled` (`CASE_RECOGNITION`), and `isLiteRtEnabled` (`QUICK_SCAN || CASE_RECOGNITION`); `QUICK_SCAN` above `OFF` composes `app/src/camera/continuous/` (`kotlin/`, `assets/sticker_centers_v2_int8.tflite` `378,288 B` SHA-256 `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`, manifest) + `litert:2.3.2` (no `ai-delivery`, `noCompress += "tflite"`); `CASE_RECOGNITION` above `OFF` composes legacy `app/src/camera/corner/` (`corner_keypoints.tflite`); `CheckCameraSizeTask` projects the `0.11.0` `CASE_RECOGNITION=OFF` camera release without counting `dev/cubentia/app/camera/corner/**` classes or `src/camera/corner/assets` (and avoids double-counting once release carries camera flags in Story 10.11); and `./gradlew -p build-logic test` verifies all flag combinations including `QUICK_SCAN=BETA, CASE_RECOGNITION=OFF` shipping zero `corner_keypoints.tflite` and zero `sticker_reader*.tflite` bytes

**Given** Story 10.23's debug shadow sticker reader
**When** this story merges
**Then** `app/src/camera/debug/sticker/` (`sticker_reader.tflite`, `ShadowReaderToggle`, `ShadowStickerReader`), `ReleaseConventionPlugin`'s `ShadowReaderPolicy.composes(...)` hooks & manifest inclusion, `CapturedFaceObserver` / `CapturedFaceObserverOptionalModule`, the `lastCornerCrops`/`shadowScanId` plumbing in `GuidedScanActivity` and `ScanFrameSource`, and the 4 `app/src/testDebug/kotlin/dev/cubentia/app/camera/sticker/*` tests are cleanly removed; `ShadowReaderPolicy` and `ShadowReaderCompositionFunctionalTest` keep enforcing the non-debuggable release guard against any `sticker_reader*.tflite` asset or `dev/cubentia/app/camera/sticker/` class; `tools/sticker-model/` stays archived as historical Story 10.23 evidence; and `docs/ARCHITECTURE.md` is updated to match AD-9/AD-18

**Given** clean-room provenance for `sticker_centers_v2_int8.tflite`
**When** the model is committed into `app/src/camera/continuous/assets/`
**Then** the reproducible training and evaluation recipe is promoted into `tools/center-model/` (`pyproject.toml`, `README.md`, `model-card.json` with full 64-hex SHA-256, tool versions, dataset/holdout manifest, and a holdout-disjointness test)

**Given** `LiteRtStickerCenterDetector` inside `app/src/camera/continuous/` behind an optional `@BindsOptionalOf` `StickerCenterDetector` seam (keeping `QUICK_SCAN = BETA` in `debug`)
**When** it analyses a `YuvFrame` off the main thread (`analyzerExecutor`, XNNPACK CPU **2 threads**, direct YUV→`UINT8[1,256,256,3]` downsampling honoring `rotationDegrees` & `cropRect`, `FLOAT32[1,64,64,3]` peak decode, inverse mapping back to `PreviewView` coordinates via `ViewfinderGrid.frameToViewFraction`, all `Interpreter` calls inside `app/src/camera/`)
**Then** it feeds `FaceLatticeFitter` + `ContinuousFaceletMapper` without persisting or transmitting any frame (AD-13)

**Given** `cornerR8` (kept composing both model sets so `LiteRtCornerDetectorDeviceTest` and `CiWorkflowTest` stay green, plus new `LiteRtStickerCenterDetectorDeviceTest`) and size gates
**When** `./gradlew spotlessCheck build`, `:app:checkCameraSize`, a minified `0.11.0`-profile size check (`CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA, CASE_RECOGNITION=OFF`), and `-Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest` run
**Then** `:app:checkCameraSize` and the real minified `0.11.0`-profile build both stay `< 30,000,000 B` (NFR-11), 16 KB ELF alignment passes, and on-device R8 benchmarks across both `640×480` and `1280×960` inputs achieve exact PC-to-Android peak parity on the 3 tuning `Fixtures/corner/` stills and full per-frame end-to-end latency (`YUV→RGB + LiteRT + decode + FaceLatticeFitter + ContinuousFaceletMapper`) `≤ 50 ms` `p50` on Lenovo TB128XU and `≤ 20 ms` `p50` on Galaxy S24 FE

### Story 10.32: Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off

As a Learner using Quick Scan,
I want to turn my cube in front of the camera and watch the 6-face map fill in with clear guidance,
So that scanning feels effortless and I can fall back to face-by-face capture at any moment without losing progress.

**Refs:** FR-22, FR-23, FR-25 · NFR-7, NFR-10, NFR-11 · AD-3, AD-9, AD-13, AD-17, AD-18 · UX-DR15, UX-DR16, UX-DR19 · Stories 10.3, 10.15, 10.16, 10.17

**Depends on** Story 10.31. **Human gate:** hands-on main-app sanity pass from the Solve tab on Lenovo TB128XU and Galaxy S24 FE (full matrix & BETA call in Story 10.33).

**Acceptance Criteria:**

**Given** `QUICK_SCAN` is `BETA` or `GA` (wired in `debug` at `BETA`; `OFF` in `release` until Story 10.11)
**When** the Learner opens Quick Scan from the Solve tab (`SolveScreen` → `onQuickScan`)
**Then** `ContinuousQuickScanScreen` shows the live CameraX preview with smoothed detected face quads (correctly mapped across portrait phones and landscape tablets), a compact live growing 6-face `URFDLB` net (using shared `StickerAppearance` color-blind glyphs, AD-17), `Sides mapped: N/6`, and one inline cue line from `en.json` covering all mapper states (bring into view, turn to show remaining N sides, tilt for adjacent face, tilt away from light, conflict face, layer twist detected, all 54 stickers verified)

**Given** a scan in progress
**When** the Learner taps *Reset*, *Scan face by face*, *Enter colors instead*, *Torch* (OFF by default; shown only on flash-equipped devices and turned off on handover/leave/background), or *Close* / system Back
**Then** *Reset* clears the mapper state in place; *Scan face by face* keeps every confirmed face and opens Guided 6-face Scan for only the missing faces assembled via `ScanAssembler`, with Back returning to Quick Scan with confirmed faces intact (Story 10.17); *Enter colors instead* seeds the Manual Cube Editor on the Solve tab with confirmed stickers when ≥ 1 face was mapped (Story 10.15 `#321`); *Close* / Back exits immediately at 0 mapped faces and asks "Discard this scan?" at ≥ 1 mapped face; zero frames are persisted or sent

**Given** all 54 stickers verified (`0` conflicts, `Orientation.HOME`, `CubeValidator` valid)
**When** the mapper completes
**Then** Quick Scan opens Story 10.3 Scan Review (with Story 10.16 per-face retake, `#323` suspect ranking, and Paint mode) and returns standard `ScanOutcome` to the Solve tab (Solution Playback or seeded Editor fallback)

**Given** obsolete 2-corner Quick Scan code no longer used by `CASE_RECOGNITION` or `FixtureRecorderActivity`
**When** this story merges
**Then** Quick-Scan-only 2-corner domain/UI code and tests (`QuickScan.kt`, `QuickScanClassification.kt`, `CornerHold.kt`, `QuickScanDiagnosis.kt`, `SideLight.kt`, and obsolete 2-corner branches in `GuidedScanActivity`/`GuidedScanScreen`) are cleanly removed while keeping `CornerLocator`/`CornerReader`/`CornerGuide` intact for `CASE_RECOGNITION` and `FixtureRecorderActivity`, and `:app:checkCameraSize` confirms reclaimed class-byte headroom

**Given** Robolectric Compose UI, route, accessibility, and layout tests
**When** `./gradlew spotlessCheck build` runs
**Then** all copy comes from `en.json` (`checkLearnerText` passes), the screen fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp, TalkBack announces side milestones/cues/conflict/completion, and `QUICK_SCAN = OFF` hides all Quick Scan UI cleanly

### Story 10.33: Continuous Quick Scan physical-device matrix and PO BETA call

As the product owner,
I want continuous Quick Scan measured on our physical reference devices across cubes and lighting conditions,
So that I can make an informed BETA call for Story 10.11's `0.11.0` camera release.

**Refs:** FR-22 · §4.8 recognition NFRs, NFR-11 · AD-18 · PRD §7 gate 6 · Stories 10.8, 10.11, 10.32

**Depends on** Story 10.32. **Human gate:** physical-device session on the main app build (from the Solve tab) across **Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, gating minimum)**, **Galaxy S24 FE (`SM-S721U1`, mid gating)**, and **Galaxy Tab S7 FE 5G (`SM-T738U`, compatibility)**.

**Acceptance Criteria:**

**Given** the main app build with `QUICK_SCAN` wired and stickered (`stka`), stickerless (`stla`), and pastel (`stlb`) cubes across warm, cool, low indoor, and backlit/glare lighting (noting daylight explicitly if excluded from the indoor BETA call, mirroring Story 10.8)
**When** the PO runs live scans from the Solve tab on Lenovo TB128XU, Galaxy S24 FE, and Galaxy Tab S7 FE 5G — at least **20 full scans (`≥ 1,080` evaluated stickers)** across the matrix covering scrambled, checkerboard, near-solved/F2L, regrip recovery, mid-scan layer twist rejection, *Scan face by face* handover keeping faces, and manual Editor fallback
**Then** `docs/evidence/10-33/continuous-quick-scan-beta.md` records per device, cube, and light: completion rate, time to 54/54, sticker corrections in review, confidently-wrong rate (target `≤ 0.1%`, zero silent wrong commits), sustained frame latency (`p50`/`p95`), thermal stability, and live `:app:checkCameraSize` + minified `0.11.0`-profile size measurements (`< 30 MB`)

**Given** the main app Solve tab with `CAMERA_SCAN = GA` and `QUICK_SCAN = BETA`
**When** verified during the device session
**Then** Quick Scan renders the shared BETA badge and tooltip ("May misread colors in poor light. Manual entry always works.") while Guided 6-face Scan carries no beta label (matching Story 10.11 AC4)

**Given** the evidence report (with Lenovo TB128XU as gating minimum)
**When** the PO makes the release-level decision
**Then** the PO records the `QUICK_SCAN` decision (`BETA` for Story 10.11's `0.11.0` release, or `OFF` with routed issues) in the PRD `.memlog.md`, closing `scp-2026-11-24-quick-scan-raise-preconditions`

---

### 4.5 Sprint Status (`_bmad-output/implementation-artifacts/sprint-status.yaml`)

- Remove dropped 2-corner backlog keys `10-24..10-27` (with a comment noting dropped by SCP 2026-11-24 continuous-quick-scan) and add:
  ```yaml
  10-30-port-continuous-facelet-lattice-fitter-and-cube-mapper-to-p: backlog
  10-31-wire-litert-v2-int8-sticker-center-detector-split-continuou: backlog
  10-32-continuous-quick-scan-live-viewfinder-growing-net-guided-fa: backlog
  10-33-continuous-quick-scan-physical-device-matrix-and-po-beta-ca: backlog
  ```
- Mark `scp-2026-11-24-quick-scan-port-stories` → `status: done` (Stories `10.30–10.33` authored, `10.24–10.27` dropped, 3-device R8 benchmark closed in PR #366).
- Rewrite `scp-2026-11-24-quick-scan-raise-preconditions` (stays `open`) so it tracks only the remaining gates before Story `10.11` raises `QUICK_SCAN`: Stories `10.30–10.33` done, minified `0.11.0`-profile size `< 30 MB` confirmed, and `10.33` PO BETA call recorded in PRD `.memlog.md`.
- Mark obsolete 2-corner deferred action items `done` (superseded by SCP 2026-11-24 continuous-quick-scan): `epic-11-retro-item-104-make-epic-11-deferred-11-1-device-sessio`, `epic-11-deferred-11.1-wrong-corner-self-calibration`, `epic-11-deferred-11.1-device-session-c` (if present), and obsolete 2-corner `epic-10-deferred-10-21-*` items that apply solely to the retired 2-corner pipeline (`cal-centre-check-fails-after-calibrating`, `light-check-front-lit-false-blocks`, `face-by-face-first-face-ref-none`, `pastel-bar-warm-keeps-wrong-sticker`, `per-frame-match-cost-unmeasured`, `orange-margin-one-sticker-mislabel`, `light-hysteresis-jitter-untested`, `calibrated-light-cut-backlit-unfit`, `plain-only-fit-choice-disclosure`, `retake-test-no-visible-difference`).
- Add one tracked non-blocking follow-up action item `scp-2026-11-24-pastel-plausibility-guided-followup` owning `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` (`#166` / FR-24 pastel plausibility for Guided Scan) after dropping Story 10.27.

---

## 5. Implementation Handoff

**Scope: Moderate** (PRD/Architecture/UX amendments + Epic 10 backlog replacement of `10.24–10.27` with `10.30–10.33`, aligned directly with PR #367's `0.11.0 → 1.0.0` release timeline).

| Role | Responsibility |
| --- | --- |
| **Correct-course (this branch, after PO approval)** | Apply §4.1–§4.5 to `prd.md`, PRD `.memlog.md`, `ARCHITECTURE-SPINE.md`, Architecture `.memlog.md`, `EXPERIENCE.md`, `epics.md`, and `sprint-status.yaml`, and merge via PR to `main`. |
| **Developer (`bmad-build` / `bmad-orchestrate`)** | Execute Stories **10.30 → 10.31 → 10.32** in order as three clean PRs (each passing `./gradlew spotlessCheck build` and, for `10.31`, `./gradlew -p build-logic test`, `:app:checkCameraSize`, minified `0.11.0`-profile size check, and `cornerR8` on Lenovo TB128XU & S24 FE). |
| **PO (BMad)** | Run Story **10.33** physical-device session on Lenovo TB128XU (gating), Galaxy S24 FE (gating), and Galaxy Tab S7 FE 5G (compatibility) and record the `QUICK_SCAN` BETA call in PRD `.memlog.md`, then execute Story **10.11** for release `0.11.0` (`CAMERA_SCAN` GA, `PLACEMENT` BETA, `QUICK_SCAN` BETA) and Story **12.5** for production `v1.0.0`. |

**Success criteria:**
- Every precondition listed in PR #367 (`AD-9`, `AD-18`, `ShadowReaderPolicy`, `CameraSizePolicy`, `FR-22`, `PRD §4.8`, `10.24–10.27` replacement, 3-device benchmark with Lenovo gating, size checks) is cleanly traced to an artifact edit or story AC.
- Release `0.11.0` / `v1.0.0` ships with **only** `sticker_centers_v2_int8.tflite` (369.4 KB, zero `corner_keypoints.tflite` bytes, zero `sticker_reader.tflite` bytes, `< 30 MB` NFR-11 budget, Lenovo TB128XU at `≤ 50 ms` full end-to-end `p50`).
- Guided 6-face Scan (GA), Placement (BETA), Case recognition (`OFF`), and manual fallbacks remain intact and unaffected.

---

## 6. Adversarial Review (2026-11-24)

Independent adversarial review against the live codebase (`MainActivity.kt`, `ReleaseConventionPlugin.kt`, `FeatureFlagLevels.kt`, `ShadowReaderCompositionFunctionalTest.kt`, `CiWorkflowTest.kt`, `ScanReview.kt`, `ColorCalibrationTest.kt`, `prd.md`, `ARCHITECTURE-SPINE.md`, `EXPERIENCE.md`, `epics.md`, `sprint-status.yaml`) surfaced **16 concrete findings** — all 16 were important/impactful and applied above:

1. **Placement entry point (`MainActivity.kt:155-167`):** Removed Placement from Stories `10.32` & `10.33` Quick Scan paths (Placement invokes Guided 6-face Scan only; Quick Scan enters from the Solve tab).
2. **`CheckCameraSizeTask` double-counting:** Specified `isContinuousModelEnabled` / `isCornerModelEnabled` / `isLiteRtEnabled` split and excluding `corner/**` classes & assets from the `0.11.0` `CASE_RECOGNITION=OFF` projection (plus no double-counting once release carries camera flags).
3. **`cornerR8` & `CiWorkflowTest` breakage:** Kept `cornerR8` composing both model sets for CI/device R8 tests while adding `LiteRtStickerCenterDetectorDeviceTest` and enforcing `QUICK_SCAN=BETA, CASE_RECOGNITION=OFF` isolation in `build-logic` TestKit.
4. **Shadow reader retirement couplings:** Enumerated all 4 couplings in Story 10.31 AC2 (`ReleaseConventionPlugin` manifest/hooks, `ShadowReaderCompositionFunctionalTest`, `CapturedFaceObserver` + `GuidedScanActivity`/`ScanFrameSource` plumbing + 4 sticker tests, archiving `tools/sticker-model/`).
5. **Spine sole-rotator, Stack table, Structural Seed, `docs/ARCHITECTURE.md`:** Added verbatim edits for `ARCHITECTURE-SPINE.md:259, 470, 493, 512` and scoped `docs/ARCHITECTURE.md` update into Story 10.31.
6. **PRD §4.8 `:477`, §6.2 `:652`, §9 OQ-7, §10 Decisions:** Fixed section citations and added verbatim edits so `prd.md` has zero internal contradictions.
7. **`EXPERIENCE.md:102` verbatim anchor & preserved UX rules:** Replaced truncated OLD anchor with full verbatim row; explicitly preserved torch OFF-by-default & auto-off rules, `#321` seeded Editor fallback, Story 10.17 Back-from-handover, and Quick Scan's `"Discard this scan?"` Close confirmation.
8. **Orphaned `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` & stale `11.1`/`10.21` action items:** Routed `#166` pastel plausibility to `scp-2026-11-24-pastel-plausibility-guided-followup` and closed superseded 2-corner action items including `epic-11-retro-item-104`.
9. **`epics.md` formatting & verbatim anchors (`:235, :236, :269, :2245, :2247, :2755`):** Fixed nested-italic Markdown on `:236`, changed Stories `10.30–10.33` from `####` to `###`, and added verbatim OLD/NEW blocks everywhere.
10. **Model provenance & JVM parity fixture:** Added full 64-hex SHA-256, committed `Fixtures/corner/` peak parity JSON in Story 10.30, and `tools/center-model/` reproducible recipe + `model-card.json` in Story 10.31.
11. **Obsolete 2-corner Quick Scan cleanup & `debug` flag:** Clarified `QUICK_SCAN` is already `BETA` in `debug`, added `@BindsOptionalOf StickerCenterDetector`, and added explicit Story 10.32 cleanup AC to reclaim `3× classBytes`.
12. **`Orientation.HOME` & `ScanReview`/`ScanAssembler`/`#321`/`#323` handoff contracts:** Added explicit Story 10.30 AC guaranteeing `Orientation.HOME` canonicalization and typed handoff payload for Story 10.17 handover, Story 10.16 retake, `#323` suspects, `#321` seeded Editor, and optional FR-24 calibration.
13. **Sensor rotation, `1280×960` analysis, and full end-to-end Lenovo latency:** Added `rotationDegrees`/`ViewfinderGrid.frameToViewFraction` inverse mapping, both `640×480` and `1280×960` benchmarks, and full end-to-end `≤ 50 ms` Lenovo / `≤ 20 ms` S24 FE gate in Story 10.31.
14. **Real minified `0.11.0`-profile size check:** Added actual minified `CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA, CASE_RECOGNITION=OFF` size verification (`< 30 MB`) to Stories 10.31 and 10.33.
15. **Frontmatter `resolves:` accuracy:** Moved `scp-2026-11-24-quick-scan-raise-preconditions` from `resolves:` to `updates:` so it stays open until `10.30–10.33` complete.
16. **Story 10.33 matrix rigor & BETA badge check:** Added `≥ 20` scans (`≥ 1,080` stickers) covering warm, cool, low, and backlit/glare (explicit daylight note) plus explicit check that Quick Scan shows the BETA badge while Guided Scan does not.
