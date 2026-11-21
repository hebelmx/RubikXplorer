# Sprint Change Proposal: Escalate Quick Scan 3D Corner Detection to Tiny 7-Keypoint LiteRT Model (2026-11-20)

**Status: Approved by the product owner (`hebelmx`) on 2026-11-20. Section 4 planning edits are applied on branch `fix/10-18-outline-viewport-mapping` (merge to `main` held pending concurrent repo merge coordination).**

- **Trigger:** Story 10.18 (*Quick Scan finds the cube's real edges and corners*, #195, #196) — Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, minimum camera spec) device check on 2026-11-20 (`fix/10-18-outline-viewport-mapping`, build `6cd18ab1`).
- **Mode:** Batch
- **Scope:** **Moderate** — activates the pre-approved AD-9 / OQ-7 LiteRT fallback specifically for Quick Scan 7-keypoint 3D corner detection and refines Story 10.18 acceptance criteria. No new epic, AD, Gradle module, or UX flow.
- **Evidence:** [`docs/evidence/10-18/quick-scan-litert-escalation.md`](../../docs/evidence/10-18/quick-scan-litert-escalation.md) and [`docs/evidence/10-18/lenovo-quickscan-2026-11-20-6cd18ab1.log`](../../docs/evidence/10-18/lenovo-quickscan-2026-11-20-6cd18ab1.log); architecture `.memlog.md` entry #93 (PO decision 2026-11-20).

---

## 1. Issue Summary

Story 10.18 attempted to solve Quick Scan's 3D perspective detection (#195) and background/glossy robustness (#196) using pure-Kotlin geometric and sticker-blob heuristics (`CornerLocator` + `StickerBlobDetector`) under OQ-7 decision K (no ML/CV runtime).

After fixing viewport mapping (`FILL_CENTER` + sensor rotation), outward normal orientation, blob radius scaling (`2.85×`), top-face diamond geometry filters, duplicate search passes, and overlay smoothing (commits `5ef59c2d` and `6cd18ab1`), live device testing on the minimum reference device (**Lenovo Tab M10 Plus 3rd Gen, Snapdragon 680**) confirmed that pure-Kotlin heuristics hit a hard ceiling across both cube types:

| Test Case (Lenovo TB128XU, `6cd18ab1`) | Result | Raw Evidence |
| --- | --- | --- |
| **Scrambled stickered cube** | **0 detections** across ~87 s (91 consecutive logged frames `detected=false quality=-,-,- score=0.0`) | `lenovo-quickscan-2026-11-20-6cd18ab1.log` L33–123 |
| **Frame latency (Snapdragon 680)** | **~920–1,050 ms/frame** (~1 FPS) when undetected; ~660 ms/frame when locked on the 1280×960 tablet stream | `lenovo-quickscan-2026-11-20-6cd18ab1.log` L2–133 |
| **Scrambled stickerless cube** | Detects faces inconsistently; almost always falls back to 2D Guided scan and still requires manual corrections on Review | PO device session (`< 21:21:07`) |
| **Solved stickered cube (edge case)** | Both corners locked (`score=5.8`, all 6 faces `ACCEPTABLE`), yet still fell back on **`R` and `L`** (`unc=9` on both; shaded 3D Red vs. Orange compressed within `< 10 ΔE`); 0 corrections after 2D fallback (`elapsedMs=175199`) | `lenovo-quickscan-2026-11-20-6cd18ab1.log` L124–142 |

**Problem classification:** Failed approach requiring a different technical solution (activating AD-9's pre-approved LiteRT fallback), plus a companion 3D side-face color-separation finding (`R` Red vs. `L` Orange).

---

## 2. Impact Analysis

### Change Navigation Checklist

| # | Item | Status | Finding |
| --- | --- | --- | --- |
| 1.1 | Trigger story | `[x]` | Story 10.18 (#195, #196) — pure-Kotlin 3D corner detection failed Lenovo device check (`6cd18ab1`). |
| 1.2 | Core problem | `[x]` | Classic Kotlin heuristics cannot reliably localize scrambled stickered/stickerless cubes at interactive FPS on Snapdragon 680; shaded 3D side faces also collapse Red/Orange separation (`R`/`L` `unc=9`). |
| 1.3 | Supporting evidence | `[x]` | Raw Lenovo log (`lenovo-quickscan-2026-11-20-6cd18ab1.log`), session screenshots, and PO live verification recorded in `docs/evidence/10-18/quick-scan-litert-escalation.md` and architecture `.memlog.md`. |
| 2.1 | Current epic (Epic 10) | `[!]` | Epic 10 remains completable; Story 10.18's approach and ACs must be updated to use a tiny 7-keypoint LiteRT model and validate 3D Red/Orange separation. Guided 6-face Scan (Stories 10.1–10.16) is untouched. |
| 2.2 | Epic-level changes | `[x]` | No new epic needed; Story 10.18 is amended in place. Story 10.17 (#198) stays unchanged and sequenced after 10.18. |
| 2.3 | Remaining planned epics | `[x]` | Epics 11 (done), 13 (done), 14 (backlog), and 15 (backlog) are unaffected. Release Stories 12.4/12.5 remain gated by Story 10.11. |
| 2.4 | Obsolete / new epics | `[N/A]` | Nothing becomes obsolete; no new epic required. |
| 2.5 | Epic order / cut-order valve | `[x]` | Sequencing unchanged (`10.18 → 10.17 → 10.11 → 12.4/12.5`). If LiteRT model training slips, the existing PRD §6.2 / AD-18 cut order lets v1.0 ship `CAMERA_SCAN` + `PLACEMENT` with `QUICK_SCAN = OFF` and zero LiteRT bytes in release. |
| 3.1 | PRD (`prd.md`) | `[!]` | No FR behavior changes; update OQ-7 and §10 Decisions to record LiteRT fallback activation for Quick Scan 3D corner detection (2D Guided Scan remains pure Kotlin; OpenCV remains rejected). |
| 3.2 | Architecture (`ARCHITECTURE-SPINE.md`) | `[!]` | Amend AD-9 (Detection method) and Stack / AD-18 build-composition note: LiteRT (`com.google.ai.edge.litert`) + quantized 7-keypoint `.tflite` live only in `app/src/camera/corner/` behind `CornerDetector`, gated on `QUICK_SCAN` / `CASE_RECOGNITION` above `OFF`. OpenCV remains rejected on NFR-11. No new AD or module. |
| 3.3 | UI/UX (`EXPERIENCE.md`, `DESIGN.md`) | `[N/A]` | No UX or visual flow changes (`UX-DR15` Y-guide, detected outline, capture cues, and 3 s plain-light-surface coaching remain valid). |
| 3.4 | Build / CI / Testing (`build-logic/`, `:app`) | `[x]` | Build-logic LiteRT gates, single-ABI native size accounting, `src/camera/corner/` wiring, and the JVM-testable `CornerDetector` seam are already implemented and verified green in commit `403c4456`. Note for 10.18 PR: `checkCameraSize` currently adds unminified debug CameraX jars (~10.09 MB) on top of minified release (~12.38 MB), leaving ~5.26 MB headroom before LiteRT (~5.7 MB); when LiteRT lands, `checkCameraSize` should measure minified camera delta or evaluate against the R8 release build (`checkReleaseSize` ~19–20 MB vs. 30 MB NFR-11). |
| 4.1–4.4 | Path forward | `[x]` | **Option 1 (Direct Adjustment)** selected; see Section 3. |
| 6.4 | `sprint-status.yaml` | `[N/A]` | Story `10-18-quick-scan-finds-the-cube-s-real-edges-and-corners` already exists (`backlog`); no new story key required unless PO prefers splitting model training into a separate spike story. |

---

## 3. Recommended Approach

**Option 1: Direct Adjustment** — amend Story 10.18 and AD-9 to activate the pre-approved LiteRT fallback for Quick Scan **7-keypoint 3D corner detection** (`[T, UL, LL, B, LR, UR, C]` + presence score), keeping 2D Guided Scan color classification in pure Kotlin and preserving the AD-18 cut order (`QUICK_SCAN = OFF` fallback) so v1.0 is never blocked.

### Alternatives evaluated
- **Continue tuning pure-Kotlin heuristics:** Rejected based on `6cd18ab1` Lenovo evidence (~1 FPS, 0/91 locks on scrambled stickered cube, repeated fallbacks on stickerless cube).
- **Introduce OpenCV (`org.opencv:opencv`):** Rejected. Standard AAR breaks NFR-11 (~37.6 MB vs. 30 MB); a custom slim NDK build adds heavy C++/CMake maintenance while classic OpenCV primitives (`Canny`, `HoughLines`, `findContours`) still struggle with stickerless same-color merges and cluttered/glossy reflections.
- **Option 2 (Rollback) / Option 3 (Drop Quick Scan permanently):** Not needed—Quick Scan's downstream pipeline (`CornerReader`, `unwarp`, `QuickScanController`, `ScanAssembler`, `CornerOverlaySmoother`, `CornerDetector` seam) is solid and reusable; only the 7-keypoint corner localization engine needs upgrading.

### Effort, risk, and timeline
- **Effort:** Medium–High (reproducible synthetic 3D render pipeline + real `Fixtures/corner/` frames, tiny quantized CNN training ~150–400 KB, `LiteRtCornerDetector` integration behind `CornerDetector`, 3D Red/Orange side-face separation fix, Lenovo + S24 FE device verification).
- **Risk:** Medium (sim-to-real generalization across warm/cool indoor light, glossy reflections #196, and hands holding the cube; plus R8 keep rules, 16 KB ELF page alignment on `libLiteRt.so` for `targetSdk 36`, and `checkCameraSize` unminified-jar accounting).
- **Timeline protection:** Because `build-logic` (`403c4456`) now gates LiteRT strictly on corner-model flags (`QUICK_SCAN`, `CASE_RECOGNITION`), v1.0 can ship `CAMERA_SCAN` + `PLACEMENT` with zero LiteRT bytes if Story 10.18 needs extra training iterations.

---

## 4. Detailed Change Proposals

### 4.1 Architecture: `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md`

**Section:** `AD-9 — Recognition: neutral samples in, validated observations out` (line 255)

- **OLD:**
  > **Detection method** (OQ-7 decision K, PO 2026-11-15, Story 10.13, `docs/spikes/oq-7-recognition-approach.md`): Kotlin nearest-center classification fed by a per-cell histogram-mode reader, self-calibrated from the six face centers; no new runtime, so the Story 10.9 classpath gate is unchanged. LiteRT is the fallback if field use shows this is not enough; OpenCV is rejected on size (NFR-11). Story 11.1 builds Quick Scan's detection within this decision.
- **NEW:**
  > **Detection method** (OQ-7 decision K, PO 2026-11-15, Story 10.13; Quick Scan fallback activated PO 2026-11-20, SCP 2026-11-20): Guided 6-face Scan and all sticker color classification stay pure Kotlin (per-cell histogram-mode reader + self-calibration from the six face centers + nearest-center classifier in `:core:domain`). For **Quick Scan 3D corner detection**, the pre-approved **LiteRT** fallback is activated (`docs/evidence/10-18/quick-scan-litert-escalation.md`): a tiny quantized 7-keypoint `.tflite` model (`com.google.ai.edge.litert`) predicts `[T, UL, LL, B, LR, UR, C]` inside `app/src/camera/corner/` behind `CornerDetector`, feeding the existing `unwarp()` and `FrameReader` pipeline. `build-logic` gates the LiteRT dependency and corner assets strictly on corner-model flags (`QUICK_SCAN`, `CASE_RECOGNITION`) above `OFF`, so builds with those flags `OFF` carry zero LiteRT runtime or model bytes. OpenCV remains rejected on size (NFR-11). Native `.so` size gates count the largest single Play delivery ABI (`arm64-v8a`) and enforce NFR-11 (< 30 MB) plus 16 KB ELF page alignment (`targetSdk 36`).
- **Rationale:** Aligns AD-9 with the 2026-11-20 Lenovo evidence and scopes LiteRT narrowly to 3D corner localization without affecting 2D Guided Scan or `OFF`-flag builds.

### 4.2 PRD: `_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md`

**Section:** `§9 Open Questions` — item 7 (`OQ-7`, line 737) & `§10 Decisions Taken During PRD Creation` (line 764)

- **OLD (OQ-7 tail):**
  > Decided (PO, 2026-11-15, Story 10.13): Kotlin nearest-center classification fed by a per-cell histogram-mode reader, self-calibrated from the six face centers; no new runtime. LiteRT is the fallback if field use shows this is not enough; OpenCV rejected on size (NFR-11).
- **NEW (OQ-7 tail + §10 bullet):**
  > Decided (PO, 2026-11-15, Story 10.13): Kotlin nearest-center classification fed by a per-cell histogram-mode reader, self-calibrated from the six face centers; no new runtime. LiteRT is the fallback if field use shows this is not enough; OpenCV rejected on size (NFR-11). **Updated 2026-11-20 (SCP 2026-11-20, Story 10.18):** Lenovo minimum-device testing (`6cd18ab1`) showed pure-Kotlin 3D corner heuristics are insufficient for Quick Scan; the pre-approved LiteRT fallback is activated specifically for Quick Scan 7-keypoint corner detection (isolated in the camera corner source set, within NFR-11 < 30 MB). Guided 6-face Scan stays pure Kotlin; OpenCV remains rejected.
- **Rationale:** Preserves OQ-7 traceability without changing any functional requirement (FR-21–FR-28).

### 4.3 Epics: `_bmad-output/planning-artifacts/epics.md`

**Section:** `Story 10.18: Quick Scan finds the cube's real edges and corners` (lines 2410–2433)

- **OLD (Approach):**
  > **Approach (PO approved 2026-11-20):** detect the cube's real edges and corners in the camera frame (within a loose framing zone) and unwarp each face from them, within the OQ-7 decision K (Kotlin, no new runtime) and NFR-11. A perspective-correct guide sketch is not used.
- **NEW (Approach):**
  > **Approach (amended PO 2026-11-20, SCP 2026-11-20):** detect the cube's seven visible corners `[T, UL, LL, B, LR, UR, C]` in the camera frame using a tiny in-house quantized 7-keypoint `.tflite` model on LiteRT (`com.google.ai.edge.litert`, AD-9 pre-approved fallback, isolated in `app/src/camera/corner/` behind `CornerDetector`, gated on corner-model flags above `OFF`) and unwarp each face from those detected corners. Guided 6-face Scan stays pure Kotlin; OpenCV stays rejected; no perspective-correct hexagon guide sketch is drawn (minimal 3-line Y framing anchor + smoothed detected outline).
- **NEW / Amended Acceptance Criteria (added to existing 10.18 ACs):**
  1. **Model provenance, size & runtime gates:** The 7-keypoint `.tflite` model is trained from reproducible synthetic 3D cube renders (covering scrambled & solved stickered cubes, stickerless cubes, hands, cluttered backgrounds, and dark glossy reflections for #196) fine-tuned/validated on real Lenovo & S24 FE frames; release installed size stays `< 30 MB` (NFR-11, single-ABI `arm64-v8a` accounting, including transitive `litert` + `litert-api` + `.tflite`), `libLiteRt.so` passes 16 KB ELF page alignment (`targetSdk 36`), R8 release builds run inference cleanly, and builds with corner flags `OFF` contain zero LiteRT classes, native libs, or model assets.
  2. **Minimum-device latency & JVM seam:** On Lenovo Tab M10 Plus 3rd Gen (TB128XU), corner inference runs off the main thread in `< 40 ms` median per frame (vs. ~950 ms heuristic baseline), while `CornerReader`, `unwarp`, `isValidCubeGeometry`, and `CornerOverlaySmoother` remain covered by deterministic JVM tests via the `CornerDetector` interface.
  3. **3D side-face readout validation:** Post-unwarp cell sampling and 3D side-face color separation (specifically Red `R` vs. Orange `L` under directional corner lighting, observed as `unc=9` in `lenovo-quickscan-2026-11-20-6cd18ab1.log`) are evaluated on real `Fixtures/corner/` captures so clean corner detections complete without forced `R`/`L` 2D fallbacks.

### 4.4 Secondary artifacts
- **`AGENTS.md`:** Refresh the `bmad:context` block via `bmad-project-context` after this SCP and the `fix/10-18-outline-viewport-mapping` branch merge to `main`.
- **`build-logic/CheckCameraSizeTask.kt`:** When `com.google.ai.edge.litert` is added in Story 10.18, adjust `checkCameraSize` so it does not double-penalize unminified debug CameraX jars (~10.09 MB) alongside LiteRT (~5.7 MB); validate the actual minified release AAB/single-ABI APK via `checkReleaseSize`.

---

## 5. Implementation Handoff

- **Change Scope:** **Moderate**
- **Handoff & Responsibilities:**
  1. **PO (`hebelmx`):** Approve/revise this SCP; confirm whether v1.0 waits for LiteRT Quick Scan or uses the AD-18 cut order (`QUICK_SCAN = OFF`) if training/validation extends past v1.0 release readiness.
  2. **Architect / Planning update (`bmad-architecture` / `bmad-prd` / `epics.md`):** Apply Section 4 edits to `ARCHITECTURE-SPINE.md`, `prd.md`, and `epics.md` once approved and merges are coordinated on `main`.
  3. **Developer (Story 10.18):**
     - Build the reproducible synthetic 3D + real-device dataset and train/quantize the 7-keypoint `.tflite` model (`~150–400 KB`).
     - Wire `com.google.ai.edge.litert` (corner-flag gated) + `LiteRtCornerDetector` in `app/src/camera/corner/` behind `CornerDetector`.
     - Verify transitive size (`< 30 MB` NFR-11), 16 KB ELF alignment, R8 keep rules, `< 40 ms` Lenovo latency, and 3D Red/Orange side-face readout on both test cubes (`stka`, `stla`) across plain and dark/glossy surfaces (#195, #196).
- **Success Criteria:** Both stickered (scrambled & solved) and stickerless cubes lock reliably at interactive FPS on Lenovo TB128XU and Galaxy S24 FE, complete 2-corner Quick Scan with rare/no 2D fallback, pass all AD-13/AD-14/NFR-11 CI gates, and receive PO BETA sign-off.
