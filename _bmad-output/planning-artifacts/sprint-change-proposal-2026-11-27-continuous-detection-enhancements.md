---
title: 'Sprint Change Proposal: continuous Quick Scan detection, lattice fitting, and tracking enhancements (Story 10.37)'
date: 2026-11-27
status: proposed
mode: batch
trigger: PO request on 2026-11-27 following the continuous Quick Scan pipeline audit and adversarial review
scope: Moderate
---

# Sprint Change Proposal: continuous Quick Scan detection, lattice fitting, and tracking enhancements (Story 10.37)

**Status: Proposed — awaiting Product Owner approval before applying edits to `epics.md`, `sprint-status.yaml`, and creating the execution-ready story spec.**

---

## 1. Issue Summary

On 2026-11-27, an end-to-end code audit and independent adversarial review of the continuous Quick Scan pipeline (`docs/evidence/continuous-quick-scan-audit-2026-11-27/README.md`, baseline `f240010b`) identified:

1. **Eight discrete bugs and omissions (B1–B8)** not duplicated in existing GitHub issues, now tracked individually as **#431–#438**:
   - **#431 (B1):** Bright white/yellow stickers penalized as glare (`ContinuousFaceSampler.kt:388-389`) and trapped permanently `NotReady` below `qFloor = 0.12`.
   - **#432 (B2):** 42-unit discontinuous cliff in `whiteReferencedHueDistance` at `cx/cp = 6.0` stalling tinted white stickers and risking balanced-solver misreads.
   - **#433 (B3):** `master.tracked` clears on a single unmatched non-empty frame (zero grace period), forcing unnecessary pending regrip merges.
   - **#434 (B4):** Foreshortened side faces dropped by coupled scale/aspect gates (`FaceLatticeFitter.kt:60-86, 138, 143, 176` and `filterPlausibleQuads` `0.28 * maxA`).
   - **#435 (B5):** `decodePeaks` emits duplicate peaks on quantized heatmap plateaus (`nScore > score + PEAK_EPS`).
   - **#436 (B6):** `ContinuousFaceSampler.sample` drops the entire face if one outer cell touches the crop border.
   - **#437 (B7):** `sharedEdge` and `sharesEdgeWithAny` penalize foreshortened faces via 4-edge mean length; `attachBySharedEdge` lacks 3-face consistency.
   - **#438 (B8):** Residual omissions (unguarded analyzer exceptions outside `detect()`/`sampler.sample()`, 0.5 px coordinate drift, center ROI border clipping on stickered cubes).

2. **Net-new algorithmic detection, fitting, and tracking enhancements (E4, E5-prior, E6, E9, E11)** that go beyond single-issue bug fixes and warrant a dedicated, replay-gated story in Epic 10:
   - **E4 (Score-aware two-tier peak decoding & lattice fitting):** Decode peaks down to `0.25–0.30` (evaluating `MAX_PEAKS` 36 → 48–64), compute `nn1` scale and lattice seeds **only** from `>= 0.45` peaks, and allow `0.25–0.45` peaks as reprojection inliers with score-weighted candidate ranking.
   - **E5 (Shared-edge guided 2nd/3rd face fitting):** Extend the #434/#437 bug fixes by using Face 1's fitted outer edges as geometric priors when searching for Faces 2 and 3.
   - **E6 (Guarded temporal quad priors):** Feed the previous frame's tracked quads into `FaceLatticeFitter` as initial homography seeds, with strict safety guards (`FULL_SPAN_MASK = 7`, displacement/distortion bounds, and `< 6`-inlier prior fits treated as tracking-only with no `addObs`).
   - **E9 (Saved-calibration anchor fallback in aligned Lab space):** When a saved `ColorCalibration` exists, use it as a secondary 3–5 center anchor reference if `REF_CENTER_LAB` fails (comparing in aligned Lab space via `standardLab` or Lab → sRGB → `shadingInvariantLab`), still self-calibrating once all 6 centers are seen.
   - **E11 (Runtime tuning):** Benchmark 2 vs 3 vs 4 XNNPACK threads on Lenovo TB128XU and Galaxy S24 FE using `#406` timings (and optionally benchmark direct `ByteBuffer.put` vs `ByteArray` + bulk copy).

*(Note: **E1, E2, E3, E7, E8** are directly covered by bug issues **#431, #432, #433, #436, #438** and are routed as issue fixes rather than duplicated inside Story 10.37. **E10** — real-domain `sticker_centers_v3_int8.tflite` fine-tuning — remains out of scope without a separate PO model-change decision, matching Story 10.36 AC3.)*

---

## 2. Impact Analysis

| Checklist area | Status | Finding |
| --- | --- | --- |
| 1. Trigger and context | [x] | Technical audit + adversarial review (2026-11-27) + PO request to promote enhancements to a story sequenced after 10.34 and prerequisite bug fixes. |
| 2. Epic impact | [x] | **Epic 10** gains **Story 10.37**. No other epic is modified or invalidated. |
| 3.1 PRD | [N/A] | FR-22, recognition NFRs (`≤ 0.1%` confidently wrong, zero silent wrong commits), NFR-8 (privacy), NFR-11 (`< 30 MB`), and Gate 6 remain unchanged. |
| 3.2 Architecture | [N/A] | All changes stay inside `:core:domain` (`recognition/continuous/`) and `app/src/camera/continuous/` behind `StickerCenterDetector` / `ContinuousScanEngine` (AD-1, AD-9, AD-13, AD-15, AD-18 unchanged). |
| 3.3 UX | [N/A] | No UI, copy, layout, or accessibility changes; existing cues and net behavior are preserved. |
| 3.4 Other artifacts | [x] | `epics.md` and `sprint-status.yaml` are updated by this proposal. `MapperParityReplayTest` (`#411`, PR #427) requires any deliberate mapper/color-model deviations to be catalogued in `Fixtures/continuous/mapper-parity/expected-differences.json` and `ContinuousFaceletMapper` KDoc. |
| 4. Path forward | [x] | **Direct Adjustment** — add Story 10.37 to Epic 10, sequenced after Story 10.34 (already `done`), the prerequisite bug fixes (#431–#437), and the diagnostic/clip enablers (#406, #413). No rollback, no MVP cut. |

### Sequencing & Dependencies

- **Story 10.34** (session recorder & replay) and **Story 10.35** (retire old recorder) are already **`done`** on `main`.
- **Story 10.37** depends on:
  1. **Story 10.34** (`done`) for `frames.jsonl` deterministic state/peak replay (gating **E5, E6, E9**).
  2. **Prerequisite bug fixes #431–#437** (specifically `#434` B4, `#435` B5, `#437` B7 which touch `FaceLatticeFitter`, `decodePeaks`, and `ProjectiveGeometry` — fixing these first avoids merge conflicts and establishes a clean baseline).
  3. **#406** (per-stage timings) and **#413** (full-resolution phone clips) for measuring **E4** (sub-`0.45` peak decoding) and **E11** (thread-count tuning), since 10.34's `frames.jsonl` stores post-`0.45` peaks rather than raw YUV frames.
- **Relationship to Story 10.36 and Story 10.11:**
  - Story 10.37 feeds directly into **Story 10.36** (recorded scramble corpus analysis & improvement) and **Story 10.33** (device matrix), so the corpus and matrix measure the enhanced pipeline.
  - Like Story 10.36, Story 10.37 **does not hard-block Story 10.11**: if Story 10.33 already records `QUICK_SCAN` BETA, the PO may proceed with 10.11 without waiting for 10.37.

---

## 3. Recommended Approach

**Direct Adjustment (Recommended):**
1. Fix the discrete bugs **#431–#438** through the normal issue-fix flow (starting with `#431`, `#432`, `#433`, `#435`, then `#434`, `#436`, `#437`, `#438`).
2. Add **Story 10.37** to Epic 10 for the cohesive algorithmic enhancements (**E4, E5-prior, E6, E9, E11**), gated by whole-corpus replay, S1–S7 stress tests (54/54, 0 wrong), `MapperParityReplayTest` (`#411`), and `#406` Lenovo TB128XU `p50 <= 50 ms` latency budget.
3. Keep **E10** (v3 model retrain) out of Story 10.37 so model identity, SHA-256 contract, and APK size stay untouched unless `#413`/Story 10.36 evidence later justifies a model-retrain story.

- **Effort:** Medium (pure-JVM + `app/src/camera/continuous/` algorithmic changes, plus replay/benchmark verification).
- **Risk:** Low–Medium. Main risk is introducing false-positive faces or shifted-subgrid observations via lower-threshold peaks (**E4**) or temporal priors (**E6**). Mitigated by strict guardrails (`>= 0.45`-only `nn1` scale and seeds, `FULL_SPAN_MASK = 7`, tracking-only `< 6`-inlier prior fits, and mandatory 0-wrong-sticker regression gate across S1–S7 and the recorded corpus).

---

## 4. Detailed Change Proposals

### 4.1 `_bmad-output/planning-artifacts/epics.md` — Epic 10 Order line

**Location:** Epic 10 header, `**Order:**` line (line 238)

**OLD (tail of line 238):**
> *(SCP 2026-11-26 retire-recorder: the separate fixture recorder is retired. The order is now 10.32 → 10.33, and 10.35 → 10.34 → 10.36 → 10.11. 10.35 is device-free and runs first; 10.34 records from the debug build's Quick Scan.)*

**NEW (append):**
> *(SCP 2026-11-27 continuous-detection-enhancements: Story 10.37 (continuous Quick Scan detection, lattice fitting, and tracking enhancements) is added after Story 10.34 and prerequisite bug fixes #431–#437 (with #406 timings and #413 full-resolution clips for E4/E11), feeding into Story 10.36. Like 10.36, 10.37 does not hard-block Story 10.11.)*

**Rationale:** Records Story 10.37's exact sequencing relative to 10.34, #431–#437, 10.36, and 10.11.

### 4.2 `_bmad-output/planning-artifacts/epics.md` — New Story 10.37 (inserted after Story 10.36)

**NEW:**
```markdown
### Story 10.37: Continuous Quick Scan detection, lattice fitting, and tracking enhancements

As a Learner scanning a physical cube with continuous Quick Scan,
I want the detector, lattice fitter, and tracker to recover foreshortened side faces and stay locked through brief motion or challenging lighting,
So that continuous Quick Scan reaches 54/54 faster with fewer regrip stalls and zero wrong stickers.

**Refs:** FR-22 · §4.8 recognition NFRs (Confidence calibration), NFR-11 · AD-1, AD-9, AD-13, AD-15, AD-18 · Stories 10.30–10.34, 10.36 · GH #406, #411, #413, #431–#438 · `docs/evidence/continuous-quick-scan-audit-2026-11-27/README.md` · `sprint-change-proposal-2026-11-27-continuous-detection-enhancements.md`

**Depends on** Story 10.34 (done) and prerequisite bug fixes #431–#437 (B1–B7, especially #434, #435, #437); #406 (per-stage timings) and #413 (full-resolution phone clips) gate the E4 and E11 measurements. **Does not hard-block Story 10.11** (follows Story 10.36's rule). **Out of scope:** retraining or replacing `sticker_centers_v2_int8.tflite` (E10 requires a separate PO model-change decision).

**Acceptance Criteria:**

**Given** `StickerCenterModelTransform.decodePeaks`, `FaceLatticeFitter`, and `ContinuousScanPipeline` (Enhancement E4)
**When** peaks are decoded with a secondary support threshold (`0.25–0.30`, with `MAX_PEAKS` evaluated at 48–64) and passed with their scores to `FaceLatticeFitter`
**Then** `nn1` scale estimation (`dMed`, `p25`, `p75`) and lattice seeds use only `>= 0.45` peaks, secondary peaks (`0.25–0.45`) act only as reprojection inliers, candidate ranking weights inliers by peak score, and JVM tests plus `#413` full-resolution clip replay show improved foreshortened side-face recall with zero false-positive face fits on cube-free negative frames

**Given** `FaceLatticeFitter` with Face 1 already fitted (Enhancement E5)
**When** searching for Faces 2 and 3
**Then** Face 1's fitted outer edges are used as geometric priors alongside the decoupled foreshortened-axis search (#434, #437), recovering adjacent side faces with fewer peaks without regressing 1-face or 3-face accuracy

**Given** `ContinuousScanPipeline` and `FaceLatticeFitter` with active tracked quads from the previous frame (Enhancement E6)
**When** the next frame arrives with brief motion blur or partial occlusion
**Then** previous tracked quads seed initial homography refinement subject to `FULL_SPAN_MASK = 7` and bounded prior-to-refined displacement/distortion, any `< 6`-inlier prior fit is marked tracking-only (no `addObs` written to cell history), and JVM tests plus Story 10.34 `frames.jsonl` replay confirm fewer tracking breaks with zero wrong stickers or false twists

**Given** a device with a saved `ColorCalibration` (`CalibrationCenters`) and a cube whose 3–5 observed centers fail to anchor against `REF_CENTER_LAB` (Enhancement E9)
**When** `ContinuousFaceletMapper` attempts early 3–5 center anchoring
**Then** it falls back to the saved calibration in an aligned CIELAB space (comparing against `standardLab` or converting saved centers via Lab → sRGB → `shadingInvariantLab` with gamut-clipping guards), still self-calibrating from the cube's own 6 centers once observed, with JVM tests on pastel/off-palette cubes verifying earlier anchoring and zero mis-anchoring

**Given** `LiteRtStickerCenterDetector` and `#406` per-stage timings on Lenovo TB128XU and Galaxy S24 FE (Enhancement E11)
**When** XNNPACK thread counts (2 vs 3 vs 4) and the input-buffer copy hypothesis are benchmarked
**Then** the chosen configuration is recorded in `docs/evidence/10-37/benchmarks.md`, Lenovo TB128XU p50 stays within the 50 ms budget, and `:app:checkCameraSize` + release builds remain green

**Given** the complete Story 10.37 change set
**When** verification runs
**Then** all S1–S7 stress scenarios reach 54/54 with 0 wrong stickers, Story 10.34 `frames.jsonl` replay shows no regressions on passing sessions, any deliberate mapper/color-model deviations are catalogued in `Fixtures/continuous/mapper-parity/expected-differences.json` and `ContinuousFaceletMapper` KDoc (`MapperParityReplayTest` passes, `#411`), and `./gradlew spotlessCheck build` passes
```

### 4.3 `_bmad-output/implementation-artifacts/sprint-status.yaml`

**Location:** Under `epic-10:`, directly after `10-36-record-new-scrambles-on-the-continuous-recorder-analyse-them: backlog`

**NEW:**
```yaml
  10-37-continuous-quick-scan-detection-lattice-fitting-and-tracki: backlog
```
*(Promoted to `ready-for-dev` once the story spec `spec-10-37-...md` is created after PO approval of this SCP.)*

---

## 5. Implementation Handoff

- **Scope:** Moderate (backlog addition + sequencing within Epic 10).
- **PO:** Reviews and approves this Sprint Change Proposal.
- **Dev / BMAD Spec:** Once approved, applies §4 to `epics.md` and `sprint-status.yaml`, authors `spec-10-37-continuous-quick-scan-detection-lattice-fitting-and-tracki.md`, and sets status to `ready-for-dev`.
- **Execution order:** Fix `#431–#438` (and `#406`/`#413`) → execute Story `10.37` → feed into Story `10.36` corpus analysis and `QUICK_SCAN` BETA call.
