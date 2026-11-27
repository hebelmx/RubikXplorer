---
title: 'Story 10.37: Continuous Quick Scan detection, lattice fitting, and tracking enhancements'
type: 'feature'
created: '2026-11-27'
status: 'ready-for-dev'
baseline_revision: 'f240010b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-27-continuous-detection-enhancements.md'
  - '{project-root}/docs/evidence/continuous-quick-scan-audit-2026-11-27/README.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Even after fixing the discrete bugs found in the 2026-11-27 continuous Quick Scan pipeline audit (GH #431–#438), four algorithmic limitations remain:
1. `ContinuousScanPipeline` discards `StickerCenterPeak.score` and applies a hard `0.45` cutoff, so foreshortened or slightly glared side faces with 4–5 strong peaks plus 2–3 `0.25–0.44` peaks fail to fit.
2. `FaceLatticeFitter` fits Faces 2 and 3 from scratch without using Face 1's already-fitted outer edges as geometric priors.
3. Every frame fits lattices from scratch with zero temporal memory of the previous frame's tracked quads, causing brief motion blur or partial occlusion to drop tracking and trigger costly regrip merges.
4. `ContinuousFaceletMapper` ignores any saved `ColorCalibration` during 3–5 center anchoring, delaying anchoring on pastel or off-palette cubes until all 6 centers are seen; and `LiteRtStickerCenterDetector` uses an unbenchmarked 2-thread XNNPACK default across devices.

**Approach:** Implement five bounded, replay-gated algorithmic enhancements (**E4, E5, E6, E9, E11**) across `app/src/camera/continuous/` and `:core:domain` (`recognition/continuous/`), keeping every change pure-JVM testable, 0-wrong-sticker safe across S1–S7 and Story 10.34 `frames.jsonl` replays, and within the 50 ms Lenovo TB128XU latency budget.

## Boundaries & Constraints

**Binding ADs:** AD-1 (pure-JVM `:core:*`, Android/LiteRT in `app/src/camera/continuous/` only), AD-9 (neutral types across camera → domain), AD-13 (no frame storage/export, numbers only in diagnostics), AD-15, AD-18 (release `QUICK_SCAN` stays OFF).

**Prerequisites before execution starts:**
- **Story 10.34** (`done`) — deterministic `frames.jsonl` replay (`:core:domain:replayContinuousSession`).
- **GH #431–#437** (B1–B7 bug fixes, especially `#434` B4, `#435` B5, `#437` B7 touching `FaceLatticeFitter`, `decodePeaks`, and `ProjectiveGeometry`).
- **GH #406** (per-stage timings) and **GH #413** (full-resolution phone clips) for measuring **E4** (sub-`0.45` peak decoding) and **E11** (XNNPACK thread-count / buffer-copy benchmarks).

**Always:**
- Keep `:core:domain` 100% pure JVM (no Android, Compose, or LiteRT imports) and `>= 80%` Kover coverage.
- Catalogue any deliberate mapper or color-model deviation in `Fixtures/continuous/mapper-parity/expected-differences.json` and `ContinuousFaceletMapper` KDoc so `MapperParityReplayTest` (`#411`, PR #427) stays green.
- Preserve `0` wrong stickers across all S1–S7 synthetic stress scenarios and zero regressions on passing Story 10.34 recorded sessions.
- Preserve Lenovo TB128XU `p50 <= 50 ms` and `:app:checkCameraSize` / minified `0.11.0`-profile `< 30 MB`.

**Never:**
- Retrain or replace `sticker_centers_v2_int8.tflite` (E10 is out of scope without a separate PO model-change decision).
- Write `< 6`-inlier temporal-prior fits into cell observation histories (`addObs`).
- Add Learner-facing UI copy or change release feature-flag levels.

</intent-contract>

---

## Code Map

- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelContract.kt` — `DEFAULT_THRESHOLD = 0.45f`, `MAX_PEAKS = 36`, `NUM_THREADS = 2`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/StickerCenterModelTransform.kt` — `decodePeaks` (L450–533), `fillYuvInput` (L123–327).
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` — `openLiteRtSession` (L274–293), `inputBytes` / `inputBuffer` (L71–83, L205–216).
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` — `processFrame` (L47–112), passes peaks + previous tracked quads + optional saved calibration into fitter/mapper.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` & `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` — optional `CalibrationCenters?` wiring into `ContinuousScanEngine` (if not already threaded).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousTypes.kt` — add neutral `ScoredPeak(point: Point2D, score: Double)` (or overload) and `QuadObservation.trackingOnly: Boolean = false`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/FaceLatticeFitter.kt` — `fit()` (L34–97), `fitOneFace()` (L115–340).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ProjectiveGeometry.kt` — `findHomography`, `sharedEdge`, `sharesEdgeWithAny`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` — `updateFragmentObservations` (L745–915), `solveGlobalColorsAndNet` (L1127–1216).
- `Fixtures/continuous/mapper-parity/expected-differences.json` — parity registry for `MapperParityReplayTest` (`#411`).

---

## Design Notes & Guardrails

### 1. Enhancement E4 — Score-Aware Two-Tier Peak Decoding & Lattice Fitting
- **Decode (`StickerCenterModelTransform.decodePeaks`):**
  - Introduce `SUPPORT_THRESHOLD = 0.25f` (tunable in `[0.25f, 0.30f]`) alongside `SEED_THRESHOLD = 0.45f` (`DEFAULT_THRESHOLD`).
  - Evaluate raising `MAX_PEAKS` from `36` to `48` or `64` so low-score background peaks cannot crowd out weak side-face peaks.
  - Relies on `#435` (B5 deterministic 3×3 NMS tie-break) so quantized plateaus never emit duplicate peaks.
- **Fit (`FaceLatticeFitter`):**
  - Add a score-aware overload `fitScored(peaks: List<ScoredPeak>, ...)` (keeping `fit(centers: List<Point2D>, ...)` delegating with `score = 1.0` for backward compatibility).
  - **Guardrail 1 (Scale immunity):** Compute `nn1` (`dMed`, `p25`, `p75`) **strictly** over seed peaks (`score >= 0.45`), never over `0.25–0.45` support peaks.
  - **Guardrail 2 (Seed immunity):** Generate `(i, j, k)` lattice seed triples **strictly** from `score >= 0.45` peaks, so background clutter never spawns fake seeds.
  - **Support inliers & ranking:** During reprojection matching (`tolFinal`), allow `0.25–0.45` support peaks as inliers (requiring at least e.g. 4 seed peaks + total `>= minInliers = 6` and `FULL_SPAN_MASK = 7`), and rank candidates by `(primaryScore, scoreSum, -meanErr)` so high-confidence faces beat noisy ones.
- **Verification:** JVM unit tests (foreshortened side face with 4 seed peaks + 3 support peaks fits; cube-free negative frames with 15 support peaks emit 0 faces) + `#413` full-resolution clip replay.

### 2. Enhancement E5 — Shared-Edge Guided 2nd/3rd Face Fitting
- Builds on `#434` (B4 decoupled foreshortened-axis bounds & `2.3` → `3.0` aspect gate) and `#437` (B7 matched-edge-length tolerances in `sharedEdge`/`sharesEdgeWithAny` & 3-face consistency).
- Once Face 1 is fitted, extract its 4 outer edges (`c[e] -> c[(e+1)%4]`) and their 3 edge-adjacent sticker centers (`0.5` cell inward).
- When searching for Faces 2 and 3, before or alongside unconstrained seed triples, generate guided hypotheses anchored on Face 1's outer edges (each edge fixes 1 basis direction and scale along the shared edge; search unclaimed peaks along the outward side of that edge for the second basis vector).
- Enforce `isValidFaceQuad`, `overlapsExisting`, `FULL_SPAN_MASK = 7`, and `meanErr <= 0.12 * dLoc` on every guided candidate.
- **Verification:** JVM tests in `FaceLatticeFitterTest` (front face + steeply foreshortened side face with 6 peaks) + Story 10.34 `frames.jsonl` peak replay.

### 3. Enhancement E6 — Guarded Temporal Quad Priors
- Pass `priorQuads: List<Quad2D>` (the previous frame's active `trackedQuads` from `ContinuousScanSnapshot`, in upright image coordinates) into `FaceLatticeFitter`.
- For each prior quad, project its 9 `GRID_UV` centers, match against current peaks within `tolInit`, and refine the homography via `ProjectiveGeometry.findHomography` (DLT + LM).
- **Mandatory safety guards:**
  1. **Full-span check:** Retain `FULL_SPAN_MASK = 7` (all 3 rows and 3 columns spanned) so a 1-sticker-shifted subgrid cannot masquerade as a full face.
  2. **Displacement & distortion bound:** Reject any refined quad whose corner displacement from the prior exceeds `0.35 * edgeLen` or fails `isValidFaceQuad`.
  3. **Tracking-only below 6 inliers:** If a prior-refined quad has `4..5` inliers (below `minInliers = 6`) and passes full-span + displacement guards, emit it with `trackingOnly = true`. In `ContinuousFaceletMapper.updateFragmentObservations`, a `trackingOnly` observation updates `frag.tracked` (keeping tracking continuity and preventing spurious `pending` splits) **without** calling `frag.addObs` or running the layer-twist check.
- **Verification:** JVM tests (1–2 motion-blur frames with 4–5 peaks keep master tracking unbroken without writing observations; shifted-subgrid peaks are rejected and never poison cell history) + Story 10.34 `frames.jsonl` replay + S1–S7 (54/54, 0 wrong).

### 4. Enhancement E9 — Saved-Calibration Fallback for Early 3–5 Center Anchoring
- Thread optional `savedCalibration: CalibrationCenters? = null` into `ContinuousFaceletMapper` (default `null`, preserving all existing constructors/tests).
- In `solveGlobalColorsAndNet()` (`ContinuousFaceletMapper.kt:1190-1214`), when `globalRotIdx == null && centerLabs.size in 3..5` and the primary `REF_CENTER_LAB` match fails (`bestCost >= 20.0 || secondCost - bestCost < 8.0`), attempt a secondary anchor using `savedCalibration` if present and valid (`StickerClassifier.isValid`).
- **Lab-space alignment (mandatory):** `CalibrationCenters` stores standard D65 CIELAB, whereas `centerLabs` are L2-normalized `shadingInvariantLab`. Compare either:
  - Option A: Compare `savedCalibration.centers` against `m.faceRobustLab(f, 4, includeStandardLab = true)?.standardLab` (same D65 CIELAB space), or
  - Option B: Convert `savedCalibration.centers` via Lab → linear sRGB → sRGB (rejecting fallback if any channel gamut-clips outside `[0, 255]`) → `ContinuousColorModel.shadingInvariantLab`.
  *(Prefer Option A for simplicity and zero gamut-clipping error, using the same `bestCost < 20.0 && margin >= 8.0` gate.)*
- Once all 6 centers are observed (`centerLabs.size == 6`), the existing self-calibration z-score anchor runs unchanged and takes precedence.
- **Verification:** JVM tests on pastel / off-palette cubes (3–4 centers fail `REF_CENTER_LAB`, anchor immediately with matching `savedCalibration`, refuse wrong/mismatched calibration, and self-calibrate identically at 6 centers).

### 5. Enhancement E11 — Runtime Thread-Count & Buffer-Copy Benchmarking
- Using `#406` per-stage timings on **Lenovo TB128XU** and **Galaxy S24 FE**:
  1. Benchmark `StickerCenterModelContract.NUM_THREADS` at `2`, `3`, and `4` (recording inference p50/p95 and thermal stability over 60 s).
  2. Benchmark per-pixel direct `ByteBuffer.put` vs the current heap `inputBytes: ByteArray` + bulk `inputBuffer.put(inputBytes)` (`LiteRtStickerCenterDetector.kt:71, 208`); adopt direct writes **only** if measurably faster on both devices and without complicating Story 10.34's `copyModelInput`.
- Record results in `docs/evidence/10-37/benchmarks.md` and keep Lenovo TB128XU `p50 <= 50 ms`.

---

## Verification & Regression Gates

1. **Pure-JVM unit & stress suite:** `./gradlew :core:domain:test` — new unit tests for E4, E5, E6, E9; all S1–S7 stress scenarios at 54/54 with 0 wrong stickers; `>= 80%` Kover coverage.
2. **Cross-language parity (`#411`):** `MapperParityReplayTest` green with any deliberate mapper/color-model deviations documented in `Fixtures/continuous/mapper-parity/expected-differences.json` and `ContinuousFaceletMapper` KDoc.
3. **Offline replay regression:**
   - Story 10.34 `:core:domain:replayContinuousSession` on recorded `frames.jsonl` corpus (E5, E6, E9): zero regressions on passing sessions, measurable reduction in tracking breaks and/or time to 54/54.
   - `#413` full-resolution clip replay (E4): improved foreshortened side-face recall, 0 false positives on negative frames.
4. **Device benchmark (E11):** `docs/evidence/10-37/benchmarks.md` confirms Lenovo TB128XU `p50 <= 50 ms`.
5. **Full CI gate:** `./gradlew spotlessCheck build`, `:app:checkCameraSize`, and release build checks green with `QUICK_SCAN = OFF` in release.
