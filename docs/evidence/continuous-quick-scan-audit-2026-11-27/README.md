# Continuous Quick Scan Pipeline Audit — Bugs, Omissions, and Detection Enhancements (2026-11-27)

**Baseline:** `origin/main` at `f240010b` (after PR #427 / GH #411 mapper parity golden replay; Story 10.34 session recorder in progress).  
**Scope:** End-to-end continuous Quick Scan pipeline across camera capture, LiteRT sticker-center detection, lattice fitting, projective face sampling, color modeling, tracking/mapping, and flow integration:
- `app/src/camera/ScanFrameSource.kt`
- `app/src/camera/continuous/StickerCenterModelContract.kt`
- `app/src/camera/continuous/StickerCenterModelTransform.kt`
- `app/src/camera/continuous/LiteRtStickerCenterDetector.kt`
- `app/src/camera/continuous/ContinuousScanPipeline.kt`
- `app/src/camera/continuous/ContinuousFaceSampler.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/FaceLatticeFitter.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ProjectiveGeometry.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousColorModel.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt`
- `app/src/camera/ContinuousQuickScanFlow.kt` & `GuidedScanActivity.kt`

**Purpose:** Identify net-new bugs and omissions not already tracked in GitHub issues, and prepare concrete, A/B-testable detection and pipeline enhancements ready to evaluate once the Story 10.34 continuous session recorder lands.

---

## 1. Deduplication Against Existing GitHub Issues

The following topics are **already tracked** (or recently closed) and are intentionally **not** re-filed here; new findings below only reference them where they interact:

| Issue | State | Summary |
|---|---|---|
| #248 | Open | Lenovo TB128XU frequent out-of-focus frames during scanning |
| #391 | Open | 10.32 hands-on quality findings (slow fill, 6–8 unread stall, S24 FE 22 unread, no cube metering, calibration check) |
| #392 | Open | 6-face net too small on tablets |
| #405 | Open | 8-per-color balanced assignment runs only at exactly 48 ready cells; full illegal net does not reopen (unread-slot diagnostics landed in PR #425) |
| #406 | Open | Debug per-stage timings (detector, fit, sample, map) on device |
| #407 | Open | Crop detector input to tracked cube + "move closer" cue |
| #408 | Open | Live YUV letterbox resize (no antialiasing) & YUV→RGB vs PC input |
| #409 | Open | Debug overlay of detected sticker centers and fitted lattice |
| #410 | Open | Center-square retry for tall/wide camera crops (superseded for tablet portrait by #415) |
| #411 | Open | Four mapper deviations vs `mapper.py` + golden replay (golden replay test merged in PR #427; part b review open) |
| #412 | Open | Tracking displacement and streak limits tuned at 30 fps webcam rate vs lower Android analyzed FPS |
| #413 | Open | Run PC spike and Android app on identical phone recordings (port vs phone-input A/B) |
| #414 | Open | Debug thumbnail of exact 256×256 model input |
| #415 | Open | Stack tablet portrait layout + run detector on full camera frame with "move into frame" cue |
| #389, #390, #397, #401, #402, #403, #404 | Closed | Analyzer crashes, unavailable detector state, sampler short-frame crash, edge-row thumb false twist, unbounded obs list, skipped-frame cube-lost, post-`close()` state |

---

## 2. New Bugs & Omissions Found in Code

### B1. Bright white stickers are penalized as severe glare and can become permanently `NotReady` (High)
- **Where:**
  - `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt:353-355, 386-394`
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt:203-206`
- **Mechanism:**
  1. In `ContinuousFaceSampler.summarizeWarp`, `clippedMin` counts pixels with `min(R, G, B) > 238.0`. Whenever `clippedMin > 0.15`, `glarePen` jumps to `min(0.95, 0.60 + max(clippedMax, clippedMin) * 1.2 + ...)` and sets `anyGlare = true`.
  2. A clean, glare-free **white sticker** under bright room light, phone auto-exposure over a dark cube body, or with the torch ON routinely has `min(R, G, B) > 238` across 30–100% of its ROI, producing `glarePen = 0.95` and `q = qAngle * qSharp * 0.05 <= 0.05`.
  3. In `ContinuousFaceletMapper.computeFaceRobustLab` (`line 205-206`):
     ```kotlin
     val qFloor = max(0.12, 0.45 * maxQ)
     val clean = ordered.filter { it.quality >= qFloor }.ifEmpty { listOf(ordered[0]) }
     ```
     Because every observation has `q <= 0.05 < 0.12`, `clean` falls back to **`listOf(ordered[0])` (size 1)** forever — regardless of how many frames are observed.
  4. Since readiness requires `count >= 2` and `qualitySum >= 0.55` (`UnreadSlot.MIN_READY_COUNT`, `MIN_READY_QUALITY_SUM`), **the white sticker can never become ready**. If the **white center** itself is bright, `confirmedFaces()` (`count >= 2`, `qualitySum >= 0.40`) and `solveGlobalColorsAndNet()` (`qualitySum >= 0.40`) never confirm the white face.
  5. Side effects: `anyGlare = true` fires continuously, disabling the layer-twist check (`ContinuousFaceletMapper.kt:829`) and showing a false *"Tilt away from light"* cue.
- **Note:** Guided Scan already hit and fixed this exact defect on device (2026-11-16, see `FrameReader.kt:71-78, 316-327`, which distinguishes a localized specular spot inside a saturated sticker from a uniform white sticker). Continuous Quick Scan never received that fix.
- **Relevance to #391:** Plausible direct contributor to both the Galaxy S24 FE unread-sticker count (22/54 unread) and Lenovo 6–8 unread stalls, especially with torch on or bright exposure.

---

### B2. 42-unit discontinuous cliff in `whiteReferencedHueDistance` at `cx = 6.0` stalls slightly tinted white stickers (High)
- **Where:** `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousColorModel.kt:94-123`
- **Mechanism:**
  1. For non-white prototypes (`isWhiteProto == false`), when a cell's white-referenced chroma `cx = hypot(lab.a - whiteA, lab.b - whiteB)` is `< 6.0`, the function falls back to `sqrt(0.35 * dL^2 + da^2 + db^2)`.
  2. The moment `cx >= 6.0`, it switches abruptly to `sqrt(0.25 * dL^2 + (1.80 * dHue)^2 + (0.22 * dRad)^2)`, where radial chroma difference `dRad = |cx - cp|` is weighted by only **`0.22`**.
  3. **Concrete example:** Let `whiteProto = (90, 0, 0)` and `yellowProto = (85, -5, 60)` (`cp = 60.2`). Consider a slightly warm-tinted white sticker at `lab = (89, -0.6, 7.0)` (`cx = 7.03`, hue aligned with yellow):
     - `dist(WHITE)` = `sqrt(0.35 * 1 + 0.36 + 49.0)` ≈ **`7.05`**.
     - `dist(YELLOW)` at `cx = 5.99` ≈ **`54.3`**; at `cx = 6.01` it drops discontinuously to **`12.1`**; at `cx = 7.03` it is **`11.87`** (`dHue ≈ 0`, `0.22 * dRad = 11.7`).
     - Margin = `11.87 - 7.05 = 4.82 < 8.0` (`UnreadSlot.MIN_PER_CELL_MARGIN`).
  4. Result: In the per-cell branch (`readyCells.size < 48`), a clean white sticker with a tiny warm (toward yellow/orange) or cool (toward blue) tint fails the margin check and stays unread (`BelowMargin`), even though its chroma is 7 and the competing prototype's chroma is 60.
- **Fix:** Replace the hard `cx < 6.0` branch with a smooth chroma blend (e.g., weight `dRad` higher when `min(cx, cp)` is small, or blend smoothly from Euclidean `(a, b)` distance to hue-weighted distance over `cx in [6.0, 20.0]`).

---

### B3. `master.tracked` clears on a SINGLE unmatched non-empty frame (zero grace period) (High)
- **Where:** `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt:381-392`
- **Mechanism:**
  - Empty frames (`plausible.isEmpty()`) grant `master.tracked` a **3-frame grace period** (`emptyStreak >= 3`, line 355).
  - Pending fragments grant **3 frames** of grace once confirmed (`maxMiss = 3`, line 407).
  - **However**, when `plausible` is **non-empty** and `matchToTracked(currentMaster, plausible)` returns empty (e.g., one motion-blurred frame, one fast turn step exceeding `0.42 * edgeLen`, or a single spurious background quad during a 1-frame detector miss on the cube), line 388 immediately executes `currentMaster.tracked.clear()` with **zero grace**.
  - On that very same frame, line 392 creates a new `pending` fragment from the unmatched quad. Even if the cube reappears in the same position 1 frame later, direct master tracking is already destroyed and the scan is forced into `TiltAdjacent` / `KeepTurningRealign` until a full 24-SO(3) regrip merge succeeds.
- **Distinct from #412:** `#412` covers tuning `0.42 * edgeLen` and existing streak limits for lower Android FPS. Even at 30 fps, having 0 frames of grace for `master.tracked` on a single unmatched non-empty frame is an asymmetry bug that amplifies any 1-frame detector/fitter glitch into a full tracking reset.

---

### B4. `FaceLatticeFitter` uses a single global `scD` across all faces and caps neighbors at 5, dropping foreshortened side faces (Medium–High)
- **Where:** `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/FaceLatticeFitter.kt:60-86, 134-145`
- **Mechanism:**
  1. **No scale recomputation after claiming Face 1:** `fit()` iterates `faceIter in 0 until maxFaces` passing the **same** `scD` to `fitOneFace` even after Face 1's 9 points are removed from `avail`. If Face 1 is front-facing (`d = 40 px`) and Face 2 is a foreshortened side face (`d_short = 16 px`, `d_long = 40 px`), `0.48 * 40 = 19.2 px` rejects Face 2's short-axis neighbors (`line 138`) even though `isValidFaceQuad` allows `minLen >= 1.15 * dMed` (`~15.3 px`/cell) and aspect up to `3.2`.
  2. **Multi-scale loop cannot mix scales across faces:** When `p75 > 1.6 * p25`, `fit()` tries `scD = p75` for *all* faces and `scD = p25` for *all* faces, then picks the single scale with the most faces. Neither single scale can fit both a 40 px front face and a 20 px side face simultaneously.
  3. **5-neighbor cap (`line 143`):** A 3×3 grid point has 4 axial + 4 diagonal neighbors inside `[0.48 * dMed, 1.65 * dMed]`. On a foreshortened side face, 2-step neighbors along the short axis and cross-edge neighbors on the adjacent face are closer than the true long-axis neighbor, pushing the long-axis basis vector out of the top 5.
- **Relevance to #391:** Directly explains weak side-face fitting even when the detector finds the side face's sticker centers.

---

### B5. `decodePeaks` emits duplicate peaks on quantized heatmap plateaus and does not clamp `(dx, dy)` offsets (Medium)
- **Where:** `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelTransform.kt:489-494, 508-512`
- **Mechanism:**
  1. **Plateau duplicates (`line 490`):** `if (nScore.isFinite() && nScore > score + PEAK_EPS)` leaves `isMax = true` for **both** adjacent heatmap cells whenever two neighbors share an equal score (e.g., saturated near 1.0 or quantized to the same value). Both cells are emitted as separate peaks ~1 heatmap cell (`4 / scale` px) apart, consuming `maxPeaks = 36` slots and injecting artificially small distances into `FaceLatticeFitter`'s `nn1` distribution (`dMed`, `p25`).
  2. **Unclamped sub-cell offsets (`lines 508-512`):** `dx` and `dy` are trained to represent sub-cell offsets in `[-0.5, 0.5]`, but are only checked for `isFinite()`. Out-of-distribution inputs (motion blur, glare, background texture) can produce `|dx| > 1.0`, shifting a peak by multiple heatmap cells and corrupting lattice fits.

---

### B6. `ContinuousFaceSampler.sample` drops the entire face if even one outer cell touches the crop border (Medium)
- **Where:** `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt:65, 91-112`
- **Mechanism:**
  - `sampledCellsInside` checks all 4 outer ROI corners (`[ROI_MIN=8, ROI_MAX=87]`). If a single corner sticker extends 1 px past the crop boundary (common on the 34%-height phone camera strip), `sample()` returns `null` and drops the **entire face**.
  - Dropping the whole face breaks tracking (`B3`), breaks shared-edge attachment to adjacent fully-visible faces, and discards clean observations of the other 6–8 in-bounds stickers.

---

### B7. `sharedEdge` penalizes foreshortened faces via mean edge length, and `attachBySharedEdge` is greedy without 3-face corner consistency (Medium)
- **Where:**
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ProjectiveGeometry.kt:235-260`
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt:696-734`
- **Mechanism:**
  1. `sharedEdge` computes `tol = 0.14 * min(lenA, lenB)` where `lenA` and `lenB` are the **4-edge mean** of each quad. A foreshortened side face has a much smaller mean edge length than the actual shared edge, shrinking `tol` precisely on the faces whose extrapolated corners have the largest homography error.
  2. `attachBySharedEdge` attaches to the **first** matching face in `assignedSnapshot` rather than the smallest-error edge, and when 2 tracked faces are visible alongside a 3rd face (corner view), it never cross-checks that the 3rd face's second shared edge agrees on the same `(face, rotB)`.

---

### B8. Residual omissions (Low)
1. **Uncaught analyzer exceptions outside `detect()` and `sampler.sample()` (`ScanFrameSource.kt:362-367, 395`):** `#397` wrapped `sampler.sample()` in `ContinuousScanPipeline`, and `#389` wrapped `detect()`, but `image.asYuvFrame()`, `FaceLatticeFitter.fit()`, `mapper.processFrame()`, and `uprightToViewFraction()` still have no top-level guard in `ScanFrameSource.read()`. An unexpected `IllegalStateException` from a recycled/malformed `ImageProxy` plane in `asYuvFrame()` would still crash the analyzer thread.
2. **Half-pixel coordinate convention drift between `decodePeaks` and `ContinuousFaceSampler.sample` (`StickerCenterModelTransform.kt:511-514`, `ContinuousFaceSampler.kt:51-63`):** `decodePeaks` outputs continuous edge-coordinates (`(gx + 0.5 + dx) * stride`, where pixel `0` center is at `0.5`), whereas `ContinuousFaceSampler.sample` treats `face.quad.corners` as pixel-index coordinates (`lastX - c.x`, `rawX.toInt()`). This introduces a 0.5 px sampling offset (negligible at 640×480, worth aligning when touching `StickerCenterModelTransform` for `#408`/`#415`).
3. **Logo-tolerant center ROI `[0.16..0.84]` clips black plastic borders on stickered cubes (`ContinuousFaceSampler.kt:326-331, 432-501`):** A 2–3 px homography error on a 32 px cell pushes 2 of the 4 corner patches onto the black sticker border, so dropping 1 outlier corner still leaves border pixels in `rScratch`, inflating `stdRgb`/`occPen` and depressing center quality.
4. **Extra 196 KB per-frame copy (`LiteRtStickerCenterDetector.kt:71, 207-208`):** `fillYuvInput` writes into heap `inputBytes` and `runInference` copies it into direct `inputBuffer` on every frame, even when no Story 10.34 recording tap is active.

---

## 3. Detection & Pipeline Enhancements Ready to A/B-Test After Story 10.34

Once the Story 10.34 continuous session recorder produces real phone/tablet clips, each enhancement below can be evaluated offline with deterministic replay (measuring: faces fitted/frame, tracking breaks, pending merges, time to 54/54, wrong-sticker rate = 0, and per-stage latency from `#406`).

### Tier 1 — Highest expected impact on recognition quality (no model retrain required)

| ID | Enhancement | Targets | Hypothesis & Verification |
|---|---|---|---|
| **E1** | **Specular-only glare penalty + remove `qFloor = 0.12` deadlock** (fix for **B1**) | `ContinuousFaceSampler`, `ContinuousFaceletMapper` | Port Guided Scan's uniform-white vs localized-specular distinction (`FrameReader.specularShare`), and allow consistent low-quality observations to accumulate `count >= 2`. Expect immediate drop in unread white stickers and false *"Tilt away from light"* cues (especially S24 FE and torch-on). |
| **E2** | **Smooth low-chroma transition in `whiteReferencedHueDistance`** (fix for **B2**) | `ContinuousColorModel` | Replace the hard `cx < 6.0` cliff with a smooth blend over `cx in [6, 20]` so tinted white stickers keep a healthy margin against yellow/blue. Verify 0 wrong stickers on S1–S7 + pastel + warm-light clips. |
| **E3** | **Grace window for `master.tracked` on unmatched non-empty frames** (fix for **B3**) | `ContinuousFaceletMapper` | Keep `master.tracked` alive for 2–3 unmatched frames (matching `emptyStreak`) before forcing a `pending` reset, or run tentative `pending` alongside live `master.tracked` and cancel `pending` if `master` re-matches. Expect sharp reduction in spurious regrip merges during hand-held turning. |
| **E4** | **Score-aware two-tier peak decoding & lattice fitting** | `StickerCenterModelTransform`, `FaceLatticeFitter`, `ContinuousScanPipeline` | Decode peaks down to `0.25–0.30` (with deterministic NMS tie-break and clamped `dx, dy` from **B5**), pass `(Point2D, score)` to `FaceLatticeFitter`: seed only from `>= 0.45` peaks, but allow `0.25–0.45` peaks as reprojection inliers, and weight candidate ranking by peak score. Recovers foreshortened/glared side faces that currently fall to 4–5 peaks at `0.45`. |
| **E5** | **Per-face local scale + 8-neighbor search + shared-edge guided 2nd/3rd face fit** (fix for **B4** & **B7**) | `FaceLatticeFitter`, `ProjectiveGeometry`, `ContinuousFaceletMapper` | Recompute `dMed` from remaining unclaimed peaks for faces 2–3, raise neighbor cap 5 → 8, lower min neighbor ratio 0.48 → 0.35, and use Face 1's fitted outer edges as geometric priors for adjacent faces. In `sharedEdge`, scale tolerance by the matched edge's own length and check 3-face corner consistency. |

### Tier 2 — Strong secondary gains (tracking stability & edge-of-frame robustness)

| ID | Enhancement | Targets | Hypothesis & Verification |
|---|---|---|---|
| **E6** | **Temporal quad prior in `FaceLatticeFitter`** | `ContinuousScanPipeline`, `FaceLatticeFitter` | Feed previous frame's tracked quads into `FaceLatticeFitter` as initial homography seeds to refine against current peaks. Maintains tracking through brief motion blur or partial occlusion (4–5 peaks) without spawning `pending` fragments. |
| **E7** | **Per-cell bounds masking instead of all-or-nothing face drop** (fix for **B6**) | `ContinuousFaceSampler`, `ContinuousFaceletMapper` | When 1–3 outer cells of a fitted quad fall outside the crop, sample the in-bounds cells and mark out-of-bounds cells with `quality = 0.0` (skipped by `addObs`) instead of dropping the whole face. Keeps tracking and shared-edge graph intact near frame borders. |
| **E8** | **Tighter center ROI / black-body exclusion for stickered cubes** (fix for **B8.3**) | `ContinuousFaceSampler` | Tighten center corner patches from `[0.16..0.84]` to `[0.20..0.80]` or exclude near-black body pixels (as in `FrameReader.stickerSamples`) so center confirmation is unaffected by 2–3 px fit jitter on black-body cubes. |
| **E9** | **Optional saved-calibration reference for 3–5 center anchoring** | `ContinuousFaceletMapper`, `ContinuousScanPipeline` | When a saved `ColorCalibration` exists, use it as a secondary anchor reference if `REF_CENTER_LAB` fails to anchor on 3–5 centers (still self-calibrating once all 6 centers are seen). Directly tests `#391`'s calibration question on pastel/off-palette cubes. |

### Tier 3 — Post-recording model & runtime work (after `#406` timings & `#413` A/B)

| ID | Enhancement | Targets | Hypothesis & Verification |
|---|---|---|---|
| **E10** | **Fine-tune `sticker_centers_v3_int8.tflite` on 10.34 hard frames** | `tools/center-model/` | `v2` was trained on 12k synthetic + 9 studio live groups (`model-card.json`), with no hand-held motion blur, torch glare, or corner-angle clips. Hard-example mining from 10.34 recordings (strictly respecting holdout disjointness) should directly improve side-face and motion-blur peak recall. |
| **E11** | **Thread-count tuning & direct-buffer input** (fix for **B8.4**) | `LiteRtStickerCenterDetector` | Benchmark 2 vs 3 vs 4 XNNPACK threads on S24 FE and Lenovo using `#406` timings, and write directly into direct `inputBuffer` when no recording tap is active. |

---

## 4. Recommended Sequencing (Post-10.34)

1. **Immediate pure-JVM / sampler bug fixes (safe to validate right away on S1–S7 + golden replay, then verify on 10.34 clips):**
   - **B1 / E1** (white-sticker glare false positive + `qFloor` deadlock)
   - **B2 / E2** (`whiteReferencedHueDistance` `cx = 6.0` cliff)
   - **B3 / E3** (`master.tracked` grace window on unmatched non-empty frame)
   - **B5** (deterministic NMS tie-break + offset clamping)
2. **After `#406` (timings), `#409`/`#414` (debug overlays), and `#413` (PC vs app A/B on 10.34 clips):**
   - **E4** (score-aware two-tier peaks) & **E5** (per-face local scale + shared-edge guided fitting)
   - **E6** (temporal quad prior) & **E7** (per-cell bounds masking)
   - **E10** (v3 model fine-tuning if `#413` shows detector recall is still the bottleneck on phone footage)
