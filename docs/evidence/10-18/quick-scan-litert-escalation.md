# Quick Scan Escalation Decision: Pure-Kotlin Heuristics → Tiny 7-Keypoint Model (LiteRT)

- **Date:** 2026-11-20
- **Decision owner:** Product Owner (`hebelmx`)
- **Branch / build tested:** `fix/10-18-outline-viewport-mapping` at commit `6cd18ab1` (`assembleDebug`)
- **Device:** Lenovo Tab M10 Plus 3rd Gen (`Lenovo TB128XU`, Snapdragon 680, serial `HA1S28MM` — minimum camera spec)
- **Related issues / stories:** #195, #196, Story 10.18, Story 11.1, OQ-7 (`docs/spikes/oq-7-recognition-approach.md`), AD-9
- **Raw log:** [`lenovo-quickscan-2026-11-20-6cd18ab1.log`](lenovo-quickscan-2026-11-20-6cd18ab1.log)

---

## 1. Decision

**Escalate Quick Scan 3D corner detection from pure-Kotlin geometric/blob heuristics (`CornerLocator` + `StickerBlobDetector`) to a tiny on-device 7-keypoint model running on LiteRT**, activating the pre-approved AD-9 / OQ-7 fallback:

> *"LiteRT is the fallback if field use shows this is not enough; OpenCV is rejected on size (NFR-11)."* — `ARCHITECTURE-SPINE.md` (AD-9)

### Key constraints preserved
- **Guided 6-face Scan stays pure Kotlin** (no change to `FrameReader`, `FaceLocator`, `CellReader`, `SelfCalibration`, or `StickerClassifier` for 2D faces).
- **OpenCV remains rejected** on installed-size grounds (~37.6 MB projected vs. 30 MB NFR-11 budget; standard classic CV primitives also share the same stickerless same-color merge and cluttered-background failure modes).
- **LiteRT fits NFR-11 (30 MB):** measured in `docs/spikes/oq-7-recognition-approach.md` at ~5.7 MB (`litert` arm64 `.so` + classes) + ~150–400 KB quantized 7-keypoint weights → **~17.4–17.6 MB projected** (~12.4 MB headroom).
- **AD-9 / AD-13 / AD-18 isolation:** `com.google.ai.edge.litert` and the `.tflite` model asset live strictly inside the `app/src/camera/` source set (already permitted by `CameraClasspathPolicy` when a camera flag is above `OFF`, forbidden when all camera flags are `OFF`). Zero network access, zero frame persistence.
- **Formal spine/epic update:** `ARCHITECTURE-SPINE.md` and `epics.md` (Story 10.18) are NOT hand-edited on this worktree; they will be updated via `bmad-correct-course` / `bmad-architecture` when coordinating merges to `main`.

---

## 2. Device Evidence (`Lenovo TB128XU`, `6cd18ab1`)

After fixing the `CornerLocator` outward normal sign, removing duplicate `StickerBlobDetector` execution, correcting `estRadius` scaling (`2.85×`) and top-face diamond aspect/fill filters, and adding EMA overlay smoothing, hands-on testing on the Lenovo minimum reference device across both cube types still failed the usability bar:

| Cube & State | Detection Behavior | Fallback / Corrections | Evidence |
| --- | --- | --- | --- |
| **Stickerless cube (scrambled)** | Detects faces, outline locks inconsistently | Almost always falls back to 2D Guided scan; still requires manual corrections on Review | PO live session prior to logcat ring wrap (`< 21:21:07`) |
| **Stickered cube (scrambled)** | **Not detected at all** — 0 locks across ~87 s (91 logged frames, `21:21:30`–`21:22:57`, every frame `detected=false quality=-,-,- score=0.0`) | Cannot complete Quick Scan | `lenovo-quickscan-2026-11-20-6cd18ab1.log` lines 33–123 |
| **Stickered cube (solved — edge case)** | Detected both corners (`score=5.8`), non-representative best case | Still fell back to 2D Guided on **2 faces (`R`, `L`)**; 0 manual corrections after 2D fallback (`elapsedMs=175199`) | `lenovo-quickscan-2026-11-20-6cd18ab1.log` lines 124–142 |

### Notable findings in the raw log

1. **Severe frame latency on Snapdragon 680 (~1 FPS on undetected frames):**
   - `21:21:30.899` → `21:21:31.802` → `21:21:32.769` → `21:21:33.682` → `21:21:34.593` (**~920–1,050 ms per undetected frame**).
   - Even during locked frames (`21:21:19.517` → `21:21:20.177` → `21:21:20.843`), analysis intervals are ~660 ms on the tablet's 1280×960 stream.
2. **Complete detection failure on scrambled stickered cubes:**
   - 91 consecutive logged frames over ~87 seconds (`21:21:30`–`21:22:57`) returned `detected=false score=0.0` despite plain white paper underneath.
3. **Companion 3D shading / color-separation issue (`R` vs `L`):**
   - Even on the **solved** stickered cube where both corners locked cleanly (`score=5.8`, `ACCEPTABLE` on all 6 faces), line 134 logged:
     ```
     quickScan fallback=[R, L] round=1 cal=Calibrated(guarded=[])
     faces=[U:ACCEPTABLE/plain=true/unc=0 F:ACCEPTABLE/plain=true/unc=0
            R:ACCEPTABLE/plain=true/unc=9 D:ACCEPTABLE/plain=true/unc=0
            L:ACCEPTABLE/plain=true/unc=9 B:ACCEPTABLE/plain=true/unc=0]
     ```
   - All 9 stickers on `R` (Red) and all 9 stickers on `L` (Orange) read `UNCERTAIN` simultaneously—indicating that under 3D corner lighting, the shaded side-face Red and Orange centers sit within `< 10 ΔE` of each other in unweighted CIE76.
   - **Action for the LiteRT work:** once the 7-keypoint LiteRT detector supplies accurate corner quads, also inspect Red/Orange separation and cell sampling inset on unwarped 3D faces so clean corner detection translates into 0-fallback Quick Scans.

---

## 3. Target Architecture for the Tiny 7-Keypoint LiteRT Escalation

1. **Model contract (replaces `CornerLocator.locate` + `StickerBlobDetector`):**
   - **Input:** downscaled RGB frame (e.g., `128×128` or `160×160` centered framing crop from `ImageProxy` YUV).
   - **Output:** presence confidence + 7 normalized 2D corner coordinates `[T, UL, LL, B, LR, UR, C]` (plus optional per-keypoint visibility / heatmap peak).
   - **Architecture:** compact depthwise-separable CNN (e.g., MobileNetV3-Small / Blaze-style backbone with coordinate-regression or low-res heatmap head), INT8/FP16 quantized (~150–400 KB `.tflite`).
   - **Runtime:** LiteRT (`com.google.ai.edge.litert`) inside `app/src/camera/` only, running on the existing analyzer thread (`~15–30 ms` target on Snapdragon 680).
2. **Downstream pipeline unchanged:**
   - Detected 7 corners → `CornerGuide.quads(...)` → existing `unwarp()` → `FrameReader.readAt()` → `FaceSample`s → `:core:domain` (`SelfCalibration`, `StickerClassifier`, `ScanAssembler`, `CubeValidator`).
   - Existing `CornerLocator` geometric sanity checks (`isValidCubeGeometry`) and `CornerOverlaySmoother` stay as lightweight post-processing guards.
3. **Training data plan:**
   - Synthetic 3D rendered dataset (tens of thousands of randomized perspective poses, FOVs, stickered + stickerless cubes, solved + scrambled states, warm/cool indoor lighting, shadows/glare, cluttered desks, hands, and dark glossy reflections for #196).
   - Fine-tune & validate against real device frames captured on Lenovo TB128XU and Galaxy S24 FE (`Fixtures/corner/`).

---

## 4. Next Steps (Coordinated With Concurrent Work)

1. Keep this worktree (`fix/10-18-outline-viewport-mapping`) unmerged while concurrent repo jobs coordinate merges to `main`.
2. Run `bmad-correct-course` (or `bmad-architecture` update) to formally record the AD-9 LiteRT fallback activation for Quick Scan corner detection and update Story 10.18 / add the LiteRT training + integration story.
3. Verify `QUICK_SCAN` release flag policy under AD-18 cut order (Quick Scan stays `BETA` or `OFF` if v1.0 ships before LiteRT lands, never blocking Guided 6-face Scan or the rest of v1.0).
