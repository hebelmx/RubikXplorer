# Continuous Quick Scan Pipeline Audit — Bugs, Omissions, and Detection Enhancements (2026-11-27)

**Baseline:** `origin/main` at `f240010b` (after PR #427 / GH #411 mapper parity golden replay; Story 10.34 session recorder in progress).  
**Scope:** End-to-end continuous Quick Scan pipeline across camera capture, LiteRT sticker-center detection, lattice fitting, projective face sampling, color modeling, tracking/mapping, and flow integration:
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelContract.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelTransform.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt`
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/FaceLatticeFitter.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ProjectiveGeometry.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousColorModel.kt`
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt`
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanFlow.kt` & `GuidedScanActivity.kt`

**Purpose:** Identify net-new bugs and omissions not already tracked in GitHub issues, and prepare concrete, A/B-testable detection and pipeline enhancements ready to evaluate once the Story 10.34 continuous session recorder and `#413` full-frame clips are available. This document incorporates all corrections from an adversarial review pass (Section 5).

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

### B1. Bright white (and bright yellow) stickers are penalized as severe glare and can become permanently `NotReady` (High)
- **Where:**
  - `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt:353-355, 386-394`
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt:203-206`
- **Mechanism:**
  1. In `ContinuousFaceSampler.summarizeWarp`, two glare clauses fire on clean bright stickers:
     - **Line 388:** `clippedMin > 0.15` (`min(R, G, B) > 238.0`). A clean, glare-free **white sticker** under bright room light, phone auto-exposure over a dark cube body, or with the torch ON routinely has `min(R, G, B) > 238` across 30–100% of its ROI, producing `glarePen = min(0.95, 0.60 + max(clippedMax, clippedMin) * 1.2 + ...) = 0.95` and `q = qAngle * qSharp * 0.05 <= 0.05`.
     - **Line 389:** `clippedMax > 0.12 && minMed > 115.0` (`max(R, G, B) > 248.0`). Even when `clippedMin == 0`, a warm- or cool-tinted white sticker or a bright yellow/pastel sticker with one channel `> 248` and `min(medR, medG, medB) > 115` still hits `glarePen >= 0.74`.
  2. In `ContinuousFaceletMapper.computeFaceRobustLab` (`lines 205-206`):
     ```kotlin
     val qFloor = max(0.12, 0.45 * maxQ)
     val clean = ordered.filter { it.quality >= qFloor }.ifEmpty { listOf(ordered[0]) }
     ```
     When every observation has `q <= 0.05 < 0.12`, `clean` falls back to **`listOf(ordered[0])` (size 1)** forever — regardless of how many frames are observed.
  3. Since readiness requires `count >= 2` and `qualitySum >= 0.55` (`UnreadSlot.MIN_READY_COUNT`, `MIN_READY_QUALITY_SUM`), **the bright white sticker can never become ready**. If the **white center** itself is bright, `confirmedFaces()` (`count >= 2`, `qualitySum >= 0.40`) and `solveGlobalColorsAndNet()` (`qualitySum >= 0.40`) never confirm the white face.
  4. Side effects: `anyGlare = true` fires continuously, disabling the layer-twist check (`ContinuousFaceletMapper.kt:829`) and showing a false *"Tilt away from light"* cue.
- **Note:** Guided Scan already hit and fixed this exact defect on device (2026-11-16, `FrameReader.kt:71-78, 316-327`), distinguishing a localized specular spot inside a saturated sticker from a uniform white sticker. Continuous Quick Scan never received that fix.
- **Relevance to #391:** Plausible direct contributor to both the Galaxy S24 FE unread-sticker count (22/54 unread) and Lenovo 6–8 unread stalls, especially with torch on or bright exposure.

---

### B2. 42-unit discontinuous cliff in `whiteReferencedHueDistance` at `cx = 6.0` (and `cp = 6.0`) stalls tinted white stickers and risks balanced-solver misreads (High)
- **Where:** `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousColorModel.kt:94-123`
- **Mechanism:**
  1. For non-white prototypes (`isWhiteProto == false`), when `cx < 6.0 || cp < 6.0` (`line 113`), the function falls back to `sqrt(0.35 * dL^2 + da^2 + db^2)`.
  2. The moment `cx >= 6.0 && cp >= 6.0`, it switches abruptly to `sqrt(0.25 * dL^2 + (1.80 * dHue)^2 + (0.22 * dRad)^2)`, where radial chroma difference `dRad = |cx - cp|` is weighted by only **`0.22`**.
  3. **Concrete example:** Let `whiteProto = (90, 0, 0)` and `yellowProto = (85, -5, 60)` (`cp = 60.2`). Consider a slightly warm-tinted white sticker at `lab = (89, -0.6, 7.0)` (`cx = 7.03`, hue aligned with yellow):
     - `dist(WHITE)` = `sqrt(0.35 * 1 + 0.36 + 49.0)` ≈ **`7.05`**.
     - `dist(YELLOW)` at `cx = 5.99` ≈ **`54.3`**; at `cx = 6.01` it drops discontinuously to **`12.1`**; at `cx = 7.03` it is **`11.87`** (`dHue ≈ 0`, `0.22 * dRad = 11.7`).
     - Margin = `11.87 - 7.05 = 4.82 < 8.0` (`UnreadSlot.MIN_PER_CELL_MARGIN`).
  4. **Two failure modes:**
     - **Per-cell branch (`readyCells.size < 48`):** A clean white sticker with a tiny warm (toward yellow/orange) or cool (toward blue) tint fails the margin check and stays unread (`BelowMargin`), even though its chroma is 7 and the competing prototype's chroma is 60.
     - **48-cell balanced solver (`ContinuousFaceletMapper.kt:1273`, cost `d[k]^2`):** Because `dist(YELLOW)` is artificially compressed to `11.87` (`cost = 141` vs `dist(WHITE)^2 = 50`, a tiny delta of `91`), the balanced solver can reassign a tinted white sticker to a saturated color at minimal cost penalty — a wrong-sticker hazard, not just a stall.
- **Fix:** Replace the hard `cx < 6.0 || cp < 6.0` branch with a smooth blend keyed on `min(cx, cp)` over `[6.0, 20.0]`, transitioning continuously from Euclidean `(a, b)` distance to hue-weighted distance.

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

### B4. Foreshortened side faces are dropped by coupled scale/aspect gates across `FaceLatticeFitter` and `filterPlausibleQuads` (Medium–High)
- **Where:**
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/FaceLatticeFitter.kt:60-86, 134-145, 176`
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt:576`
- **Mechanism:**
  1. **Single global `scD` across all faces (`FaceLatticeFitter.kt:60-86`):** `fit()` passes one fixed `scD` to `fitOneFace` across all `maxFaces = 3` iterations. If Face 1 is front-facing (`d = 40 px`) and Face 2 is a foreshortened side face (`d_short = 16 px`, `d_long = 40 px`), `0.48 * 40 = 19.2 px` rejects Face 2's short-axis neighbors (`line 138`) even though `isValidFaceQuad` allows `minLen >= 1.15 * dMed` (`~15.3 px`/cell) and aspect up to `3.2`. Conversely, the multi-scale loop (`p75`, `p25`) tries each scale for *all* faces, so `p25` cannot fit Face 1 and `p75` cannot fit Face 2.
  2. **Tighter basis-vector aspect gate (`FaceLatticeFitter.kt:176`):** `max(l1, l2) / min(l1, l2) > 2.3` rejects a `40 / 16 = 2.5` foreshortened face even before `isValidFaceQuad`'s `3.2` aspect check runs.
  3. **5-neighbor cap (`FaceLatticeFitter.kt:143`):** A 3×3 grid point has 4 axial + 4 diagonal neighbors inside `[0.48 * dMed, 1.65 * dMed]`. On a foreshortened side face, 2-step short-axis neighbors and cross-edge neighbors on the adjacent face are closer than the true long-axis neighbor, pushing the long-axis basis vector out of the top 5.
  4. **Downstream relative-area filter (`ContinuousFaceletMapper.kt:576`):** `filterPlausibleQuads` rejects any quad with `a < 0.28 * maxA` (and `a < 2800.0` px²; the 2026-11-26 review noted `2800.0` was unconfirmed at normal holding distance, but `0.28 * maxA` independently discards steeply foreshortened side faces whose projected area is `< 28%` of the front face even after `FaceLatticeFitter` fits them).
- **Relevance to #391:** Directly explains weak side-face fitting and tracking even when the detector finds the side face's sticker centers.

---

### B5. `decodePeaks` emits duplicate peaks on quantized heatmap plateaus (Medium)
- **Where:** `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelTransform.kt:489-494`
- **Mechanism:**
  - `if (nScore.isFinite() && nScore > score + PEAK_EPS)` leaves `isMax = true` for **both** adjacent 64×64 heatmap cells whenever two neighbors share an equal score (e.g., saturated near 1.0 or quantized to the same value).
  - Both cells are emitted as separate peaks ~1 heatmap cell (`4 / scale` px) apart, consuming `maxPeaks = 36` slots and injecting artificially small distances into `FaceLatticeFitter`'s `nn1` distribution (`dMed`, `p25`).
  - *(Note: `(dx, dy)` sub-cell offsets are architecturally bounded to `[-0.55, 0.55]` by `Activation("tanh") * 0.55` in `tools/center-model/train_center_model.py:99`, so unclamped offsets are not a runtime bug on `v2`; any future defensive clamp for untrusted models should use `[-0.55, 0.55]` to preserve exact parity with `eval_litert_center.py`.)*

---

### B6. `ContinuousFaceSampler.sample` drops the entire face if even one outer cell touches the crop border (Medium)
- **Where:** `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSampler.kt:65, 91-112`
- **Mechanism:**
  - `sampledCellsInside` checks all 4 outer ROI corners (`[ROI_MIN=8, ROI_MAX=87]`). If a single corner sticker extends 1 px past the crop boundary (common on the 34%-height phone camera strip), `sample()` returns `null` and drops the **entire face**.
  - Dropping the whole face breaks tracking (`B3`), breaks shared-edge attachment to adjacent fully-visible faces, and discards clean observations of the other 6–8 in-bounds stickers.

---

### B7. `sharedEdge` and `sharesEdgeWithAny` penalize foreshortened faces via 4-edge mean length, and `attachBySharedEdge` is greedy without 3-face consistency (Medium)
- **Where:**
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ProjectiveGeometry.kt:235-286`
  - `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt:696-734`
- **Mechanism:**
  1. Both `sharedEdge` (`tol = 0.14 * min(lenA, lenB)`) and `sharesEdgeWithAny` (`tol = 0.16 * min(meanL, meanEdgeLength(cb))`, which drives `FaceLatticeFitter.kt:302`'s `+2` `edgeBonus`) scale tolerance by each quad's **4-edge mean**. A foreshortened side face has a much smaller mean edge length than the actual shared edge, shrinking `tol` precisely on the faces whose extrapolated corners have the largest homography error.
  2. `attachBySharedEdge` attaches to the **first** matching face in `assignedSnapshot` rather than the smallest-error edge, and when 2 tracked faces are visible alongside a 3rd face (corner view), it never cross-checks that the 3rd face's second shared edge agrees on the same `(face, rotB)`.

---

### B8. Residual omissions (Low)
1. **Uncaught analyzer exceptions outside `detect()` and `sampler.sample()` (`ScanFrameSource.kt:362-367, 395`):** `#397` wrapped `sampler.sample()` in `ContinuousScanPipeline`, and `#389` wrapped `detect()`, but `image.asYuvFrame()`, `FaceLatticeFitter.fit()`, `mapper.processFrame()`, and `uprightToViewFraction()` still have no top-level guard in `ScanFrameSource.read()`. An unexpected `IllegalStateException` from a recycled/malformed `ImageProxy` plane in `asYuvFrame()` would still crash the analyzer thread.
2. **Half-pixel coordinate convention drift between `decodePeaks` and `ContinuousFaceSampler.sample` (`StickerCenterModelTransform.kt:511-514`, `ContinuousFaceSampler.kt:51-63`):** `decodePeaks` outputs continuous edge-coordinates (`(gx + 0.5 + dx) * stride`, where pixel `0` center is at `0.5`), whereas `ContinuousFaceSampler.sample` treats `face.quad.corners` as pixel-index coordinates (`lastX - c.x`, `rawX.toInt()`). This introduces a 0.5 px sampling offset (negligible at 640×480, worth aligning when touching `StickerCenterModelTransform` for `#408`/`#415`).
3. **Logo-tolerant center ROI `[0.16..0.84]` clips black plastic borders on stickered cubes (`ContinuousFaceSampler.kt:326-331, 432-501`):** A 2–3 px homography error on a 32 px cell pushes 2 of the 4 corner patches onto the black sticker border, so dropping 1 outlier corner still leaves border pixels in `rScratch`, inflating `stdRgb`/`occPen` and depressing center quality.

---

## 3. Detection & Pipeline Enhancements Ready to A/B-Test

**Replay artifact boundary (important):** Story 10.34's `ContinuousSessionWriter` (`app/src/camera/debug/kotlin/dev/cubentia/app/camera/session/ContinuousSessionWriter.kt:194, 205-223`) records post-detector `frames.jsonl` (`QuadObservation`s and `0.45`-thresholded peaks) plus `imageEvery`-subsampled 256×256 model-input PNGs — **not** full-resolution raw YUV frames or ground-truth center labels. Therefore:
- **Mapper & fitter changes (`E2`, `E3`, `E5`, `E6`, `E9`)** can be A/B-tested directly on Story 10.34 `frames.jsonl` replays.
- **Sampler & sub-threshold detector changes (`E1`, `E4`, `E7`, `E8`)** require full-resolution video/frame clips (as collected for `#413`) or still/YUV fixtures, not `frames.jsonl` alone.
- **Model fine-tuning (`E10`)** additionally requires ground-truth center annotation on extracted failure frames.

Every change must preserve 0 wrong stickers on S1–S7 and respect `#406` per-stage latency budgets.

### Tier 1 — Highest expected impact on recognition quality (no model retrain required)

| ID | Enhancement | Targets | Verification Input | Design & Guardrails |
|---|---|---|---|---|
| **E1** | **Patch-local specular-only glare check (fix for B1)** | `ContinuousFaceSampler` | Full-res clips (`#413`) + YUV/RGB still fixtures | Fix **both** lines 388 (`clippedMin > 0.15`) and 389 (`clippedMax > 0.12 && minMed > 115.0`): treat uniform bright low-gradient patches (`grad <= 20`, low `stdRgb`) as clean white/bright stickers, not glare. Design a patch-local chroma/contrast check that works on both `gatherRect` and `gatherLogoTolerantCenter`'s 3 disjoint corner patches (do **not** naively port `FrameReader.specularShare`'s contiguous-grid `onOuterRing`). **Keep** `qFloor = 0.12` in `computeFaceRobustLab` so genuinely glared/occluded `q = 0.05` frames cannot accumulate to `qualitySum >= 0.55` and commit wrong stickers (once uniform white is not penalized, clean white has `q >= 0.5`). |
| **E2** | **Smooth low-chroma blend in `whiteReferencedHueDistance` (fix for B2)** | `ContinuousColorModel` | 10.34 `frames.jsonl` + S1–S7 + pastel fixtures | Replace the hard `cx < 6.0 || cp < 6.0` cliff with a smooth transition keyed on `min(cx, cp)` over `[6.0, 20.0]`, eliminating both the per-cell `BelowMargin` stall and the 48-cell balanced-solver wrong-sticker hazard for tinted white and pastel prototypes. |
| **E3** | **Grace window for `master.tracked` on unmatched non-empty frames (fix for B3)** | `ContinuousFaceletMapper` | 10.34 `frames.jsonl` + S1–S7 | Keep `master.tracked` alive for 2–3 unmatched frames (matching `emptyStreak`) before forcing a `pending` reset, or run tentative `pending` alongside live `master.tracked` and cancel `pending` if `master` re-matches. |
| **E4** | **Score-aware two-tier peak decoding & lattice fitting (includes B5)** | `StickerCenterModelTransform`, `FaceLatticeFitter`, `ContinuousScanPipeline` | Full-res clips (`#413`) + tuning/held-out stills | Add deterministic 3×3 NMS tie-break to remove plateau duplicates (`B5`). Decode peaks down to `0.25–0.30` and evaluate raising `MAX_PEAKS` (e.g., 36 → 48–64) so background clutter does not crowd out weak side-face peaks. Compute `nn1` scale (`dMed`, `p25`, `p75`) and lattice seeds **only** from `>= 0.45` peaks; allow `0.25–0.45` peaks only as reprojection inliers and rank candidates by score-weighted inliers. |
| **E5** | **Decoupled foreshortened-axis lattice fit + edge-length-scaled attachment (fix for B4 & B7)** | `FaceLatticeFitter`, `ProjectiveGeometry`, `ContinuousFaceletMapper` | 10.34 `frames.jsonl` (peaks) + S1–S7 | Do **not** replace `dMed` with the remaining points' `nn1` median (which shrinks `dMed` to `d_short` and rejects `d_long`). Instead: keep the front-face scale as the upper bound while lowering the min-neighbor ratio (`0.48` → `0.35`), raise neighbor cap `5` → `8`, relax basis-vector aspect `2.3` → `3.0` (`FaceLatticeFitter.kt:176`, aligned with `isValidFaceQuad`'s `3.2`), and use Face 1's fitted outer edges as geometric priors for Faces 2–3. Scale tolerance by the **matched edge's own length** in both `sharedEdge` and `sharesEdgeWithAny`, check 3-face corner consistency in `attachBySharedEdge`, and revisit `filterPlausibleQuads`'s `0.28 * maxA` cutoff so recovered side faces are not dropped downstream. |

### Tier 2 — Strong secondary gains (tracking stability & edge-of-frame robustness)

| ID | Enhancement | Targets | Verification Input | Design & Guardrails |
|---|---|---|---|---|
| **E6** | **Temporal quad prior in `FaceLatticeFitter`** | `ContinuousScanPipeline`, `FaceLatticeFitter` | 10.34 `frames.jsonl` (peaks) + S1–S7 | Feed previous frame's tracked quads as initial homography seeds to refine against current peaks. **Safety guards required:** retain `FULL_SPAN_MASK = 7`, bound prior-to-refined displacement/distortion, and treat `< 6`-inlier prior fits as **tracking-only** (no `addObs`) so a 1-sticker-shifted subgrid cannot poison cell histories or trigger false twists. |
| **E7** | **Per-cell bounds masking instead of all-or-nothing face drop (fix for B6)** | `ContinuousFaceSampler`, `ContinuousFaceletMapper` | Full-res clips (`#413`) + JVM unit tests | When 1–3 outer cells fall outside the crop, sample in-bounds cells and mark out-of-bounds cells with `quality = 0.0`. **Required mapper/sampler changes:** explicitly skip `quality <= 0.0` cells in `ContinuousFaceletMapper.updateFragmentObservations` **before** `.coerceAtLeast(0.01)` (`line 758`) and `frag.addObs` (`line 894`), handle out-of-bounds center cells in `matchToTracked` / `attachBySharedEdge`, and exclude out-of-bounds warp pixels from `laplacianVariance` (`ContinuousFaceSampler.kt:312`). |
| **E8** | **Tighter center ROI / black-body exclusion for stickered cubes (fix for B8.3)** | `ContinuousFaceSampler` | Full-res clips (`#413`) + stickered cube stills | Tighten center corner patches from `[0.16..0.84]` to `[0.20..0.80]` or exclude near-black body pixels (as in `FrameReader.stickerSamples`) so center confirmation is unaffected by 2–3 px fit jitter on black-body cubes. |
| **E9** | **Optional saved-calibration reference for 3–5 center anchoring** | `ContinuousFaceletMapper`, `ContinuousScanPipeline` | 10.34 `frames.jsonl` + calibrated pastel/off-palette clips | When a saved `ColorCalibration` exists, use it as a secondary anchor reference if `REF_CENTER_LAB` fails on 3–5 centers (still self-calibrating once all 6 centers are seen). **Lab-space alignment required:** `CalibrationCenters` stores standard D65 CIELAB while the mapper anchors in L2-normalized `shadingInvariantLab` — either compare against `faceRobustLab(..., includeStandardLab = true).standardLab` or convert saved centers via Lab → sRGB → `shadingInvariantLab` with gamut-clipping guards. |

### Tier 3 — Post-recording model & runtime work (after `#406` timings & `#413` A/B)

| ID | Enhancement | Targets | Verification Input | Design & Guardrails |
|---|---|---|---|---|
| **E10** | **Fine-tune `sticker_centers_v3_int8.tflite` on real hard frames** | `tools/center-model/` | Annotated failure frames from `#413` / 10.34 recordings | `v2` already includes synthetic 3-face corner views and glare (35% of 12,000 frames in `synth_heatmap_data.py`), but has **zero real corner live groups** (`trainingCornerGroups: []` in `model-card.json:31`) and no real hand-held motion blur or torch-on phone footage. Target that real-domain gap with hard-example mining (strictly preserving holdout disjointness). |
| **E11** | **Thread-count tuning & benchmarked input-buffer copy hypothesis** | `LiteRtStickerCenterDetector` | Device `#406` timings (S24 FE & Lenovo) | Benchmark 2 vs 3 vs 4 XNNPACK threads on S24 FE and Lenovo. As a benchmarked hypothesis (not an assumed win), compare per-pixel direct `ByteBuffer.put` against the current heap `inputBytes` + bulk `inputBuffer.put(inputBytes)` (`LiteRtStickerCenterDetector.kt:71, 208`), since 196,608 per-byte direct calls can be slower on ART than a bulk native `memcpy`. |

---

## 4. Recommended Sequencing (Post-10.34)

**CI / Parity Prerequisite (`#411`):** Since PR #427 (`f240010b`), `MapperParityReplayTest` asserts exact `(session, frame, field)` parity against `pc_spike/mapper.py` via `Fixtures/continuous/mapper-parity/expected-differences.json`. Any PR modifying `ContinuousFaceletMapper` or `ContinuousColorModel` (`B2`, `B3`, `B7`, `E2`, `E3`, `E5`, `E6`, `E9`) must catalogue new deliberate deviations in `expected-differences.json` and the mapper KDoc (or align with `#411` part b).

1. **Immediate bug fixes (high value, low risk):**
   - **B1 / E1** (patch-local white/bright-sticker glare fix in `ContinuousFaceSampler` — does not break `MapperParityReplayTest`)
   - **B5** (deterministic 3×3 NMS tie-break in `StickerCenterModelTransform` — verify still parity)
   - **B2 / E2** (`whiteReferencedHueDistance` smooth `min(cx, cp)` blend — catalogue under `#411`)
   - **B3 / E3** (`master.tracked` grace window on unmatched non-empty frame — catalogue under `#411`)
2. **After `#406` (timings), `#409`/`#414` (debug overlays), 10.34 `frames.jsonl` replays, and `#413` (full-frame PC vs app A/B):**
   - **E4** (score-aware two-tier peaks) & **E5** (decoupled foreshortened-axis fitting + edge-length-scaled `sharedEdge`/`sharesEdgeWithAny` + `0.28 * maxA` review)
   - **E6** (guarded temporal quad prior) & **E7** (per-cell bounds masking with `quality <= 0.0` skip)
   - **E10** (v3 real-domain fine-tuning if `#413` confirms detector recall remains the bottleneck on phone footage)

---

## 5. Adversarial Review Summary

An independent adversarial review (`bmad-review` `adversarial` lens) scrutinized the first draft of this audit against the repository source code and caught 14 concrete issues, all resolved above:
- **Disproved claim removed:** B5 originally claimed unclamped `(dx, dy)` could exceed `|dx| > 1.0`; `tools/center-model/train_center_model.py:99` applies `tanh * 0.55`, architecturally bounding outputs to `[-0.55, 0.55]`.
- **Broken/incomplete fix proposals corrected:**
  - **B4/E5:** Recomputing `dMed` from remaining points would shrink `dMed` to `d_short` and reject `d_long`; fixed to decouple short/long bounds, relax `FaceLatticeFitter.kt:176` (`2.3` → `3.0`), and include `filterPlausibleQuads`'s `0.28 * maxA` gate (`ContinuousFaceletMapper.kt:576`).
  - **B1/E1:** Added missed line 389 (`clippedMax > 0.12 && minMed > 115.0`), kept `qFloor = 0.12` to prevent glared frames from committing wrong stickers, and dropped naive `onOuterRing` porting for disjoint center patches.
  - **B2/E2:** Added the 48-cell balanced-solver wrong-sticker risk and symmetric `min(cx, cp)` blending.
  - **B7/E5:** Added `ProjectiveGeometry.sharesEdgeWithAny` (`lines 265-286`) alongside `sharedEdge`.
  - **E4, E6, E7, E9:** Added `>= 0.45`-only `nn1` scale + `MAX_PEAKS` note (`E4`), `FULL_SPAN_MASK` + tracking-only guard for `< 6`-inlier prior fits (`E6`), explicit `quality <= 0.0` skip before `coerceAtLeast(0.01)`/`addObs` + `laplacianVariance` border exclusion (`E7`), and `shadingInvariantLab` vs standard D65 Lab alignment (`E9`).
- **Process & accuracy fixes:** Partitioned Section 3 verification by artifact (10.34 `frames.jsonl` vs `#413` full-res clips), added the `MapperParityReplayTest` (`#411`) prerequisite in Section 4, reframed direct `ByteBuffer.put` in E11 as a benchmarked hypothesis, fixed Scope file paths, and clarified `v2`'s synthetic vs real-domain gap in E10.
