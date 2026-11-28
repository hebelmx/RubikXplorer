# Sprint Change Proposal: Deterministic Cubie-Invariant Resolution for Story 10.26 (2026-11-27)

- **Trigger:** Phase 1 spike on cubie-invariant color resolution for Quick Scan (`docs/spikes/cubie-invariant-color-resolution-spec.md`, `docs/spikes/cubie-invariant-color-resolution-spike-results.md`, docs commit `ae61b332` on `docs/cubie-invariant-resolver-spec`, cherry-pickable code commit `49f26258` preserved on branch `spike/cubie-invariant-resolver`) + adversarial reviews against `epics.md` (Story 10.26), `prd.md` (FR-22), `ARCHITECTURE-SPINE.md` (AD-9), and `:core:domain` / `:app` scan code.
- **Mode:** Batch
- **Scope:** Moderate (refining existing backlog Story 10.26 in place, updating FR-22 and AD-9 via SCP, zero new epics or story resequencing; cherry-pickable domain baseline `49f26258` preserved on `spike/cubie-invariant-resolver` until Story 10.26 starts after Story 10.21).
- **Status:** Approved (PO, 2026-11-27; Revision 2 after adversarial review — all 13 findings incorporated).

---

## 1. Issue Summary

During Quick Scan exploration, we prototyped and adversarially reviewed a pure-domain **Cubie-Invariant Color Resolver** (`CubieInvariantResolver`) intended to prevent 1–12 uncertain stickers from forcing whole faces into Guided 6-face fallback.

Comparing the hardened spike against existing planning artifacts revealed that **Story 10.26** (*Quick Scan fills a few unread stickers from the cube's rules*, currently `backlog`) already covers this capability — but its current acceptance criteria contain concrete technical and contract gaps:

1. **Pre-search Lab pruning creates false uniqueness:** Story 10.26 currently specifies pruning per-sticker Lab candidates *before* searching valid cube combinations. Adversarial review proved this can eliminate a competing physically valid state under shaded lighting or drifted calibration, making an ambiguous cube look falsely unique.
2. **Ungated `ScanAssembler` bypasses Story 10.19's agreement gate:** Story 10.26 places the search inside `ScanAssembler` (which reads ungated `StickerClassifier.read`), bypassing `QuickScanClassification.read()`'s two-read agreement gate and exposing Quick Scan to `self`'s higher confident-error rate.
3. **Under-specified invariants & cap:** Pooled 9-of-each counting misses piece-typed overflows (e.g., 5 confident corner stickers of one color while total ≤ 9) and plain-center disagreements; meanwhile, algebraic fixed-point propagation + forward-checked MRV backtracking safely handles up to **12** uncertain stickers (not just a handful).
4. **Incomplete `ScanReview` / `CalibrationOffer` / `GuidedScanFlow` contract:** `ScanReview` currently tracks only `stickers: List<Color?>` (no `filled` set or filled-sticker pre-selection/visual marker), `CalibrationOffer.offered` counts `review.blanks.size >= 6` (which drops to `0` when stickers are filled), and `QuickScanEffect.Done` needs optional resolved output + net-ordered `labs` routed into a dedicated `GuidedScanFlow.reviewOf(QuickScanEffect.Done, saved)` so `#174` `ScanEvidence`, `ScanReview.retaken`, and `CalibrationOffer` stay intact without `:app` rotating `Lab`s.

### Supporting Empirical Evidence (Phase 1 Spike — `49f26258` on `spike/cubie-invariant-resolver`)
Replayed across **22 of 23 recorded corner groups** in `Fixtures/corner/` × **4 calibration scenarios** (`NONE`, `MATCHING`, `OTHER_LIGHT`, `OTHER_CUBE`) = **277 runs**, with strict `TUNING` / `HELDOUT` / `LEGACY` split hygiene (`Fixtures/corner/groups.json`). The 7th `TUNING` group (`lenovo-tb128xu_stlb_bar-warm`, pastel) has an `ORANGE` face with `quality = BLURRED` and was rejected by `placeAllPosed` (Story 10.26's replay test must explicitly count non-`ACCEPTABLE` groups as `INVALID_INPUT` rather than silently skipping them). Note: Phase 1 ran on the PO-reviewed labels merged in PR #334, before Story 10.27's automated `Fixtures/corner/label-exclusions.json` lands.

- **Safety bar:** **0** confidently wrong stickers promoted across all 277 runs (all splits, all 4 calibration scenarios).
- **Lift on `TUNING` (no saved calibration):** Full 0-fallback Quick Scans rose **0/6 → 4/6** of `ACCEPTABLE` groups (**4/7 = 57.1%** of all `TUNING` groups); side faces kept rose **9/24 (37.5%) → 17/24 (70.8%)**; rescued 2–11 uncertain stickers per cube (including an 11-sticker / 6-face rescue on `sm-s721u1_stka_bar-warm`).
- **Lift on `TUNING` (`matching`):** Full 0-fallback **0/6 → 2/6** of `ACCEPTABLE` groups (**2/7** overall); side faces kept **5/24 → 9/24**.
- **`L_WEIGHT` verification (`TUNING` only):** `1.0` (standard CIE76 on light-adjusted pairs) and `0.5` produced identical rescues (`12` runs, `0` errors); `1.0` is locked as strictly simpler.
- **Untouched `HELDOUT` gate (91 runs):** `0` wrong stickers, `0` rescues today because current `HELDOUT` failures are upstream confident misreads / plain-center disagreements (`CONFIDENT_INVARIANT_VIOLATION`), backlit `ceilback` (`TOO_MANY_UNCERTAIN`), or `UNCALIBRATED=1` — confirming Story 10.26 remains **additive to Story 10.21** (and `#315`) and must keep its dependency on Story 10.21.

---

## 2. Impact Analysis (Change Analysis Checklist)

| # | Checklist Area | Status | Findings & Impact |
| --- | --- | --- | --- |
| 1 | Trigger & Context (1.1–1.3) | `[x]` | Triggered by Phase 1 Quick Scan cubie-resolution spike (`ae61b332`, `49f26258`) + adversarial reviews. Full empirical evidence recorded in `docs/spikes/cubie-invariant-color-resolution-spike-results.md`. |
| 2 | Epic Impact (2.1–2.5) | `[x]` | Epic 10 remains on track. No new epic, no removed epic, no epic resequencing. Story 10.26 stays in `backlog`, keeping its dependency on Stories 10.20 and 10.21. |
| 3 | PRD Impact (3.1) | `[!]` | **FR-22** (`prd.md` L492 and L494) updated together so both consequences stay consistent: heuristic suggestions and guessing remain forbidden, while algebraically unique, plausibility-checked fills arrive in review marked as filled and pre-selected for Learner confirmation. MVP scope unchanged. |
| 4 | Architecture Impact (3.2) | `[!]` | **AD-9** (`ARCHITECTURE-SPINE.md` L258–265) updated in both **Assembly and classification** and **Acceptance**: `CubieInvariantResolver` runs over gated `QuickScanClassification` readings and delegates posed URFDLB rotation to `ScanAssembler` (sole rotator preserved); uniqueness is proved algebraically before Lab plausibility; filled stickers arrive marked and pre-selected in `ScanReview` for Learner confirmation before acceptance. |
| 5 | UX / UI Impact (3.3) | `[!]` | `ScanReviewScreen.kt` has no existing visual marking, TalkBack description, or `en.json` copy for cubie-filled stickers. Story 10.26 adds a distinct filled-sticker visual indicator, TalkBack semantics, and Learner-facing text in `core/domain/src/main/resources/content/strings/en.json` (never `strings.xml`, per `checkLearnerText`), plus pre-selecting filled stickers in `ScanReview`. |
| 6 | Other Artifacts / Code (3.4) | `[!]` | Cherry-pickable domain spike commit `49f26258` is isolated on branch `spike/cubie-invariant-resolver` (never merged via the docs PR). Story 10.26 also updates `ScanReview` (`filled` set + lifecycle on `paint`/`corrected`/`retaken`), `CalibrationOffer.offered`, `QuickScanController.lastResolution`, and both debug/release `ScanDiagnostics` stubs. Guided Scan's unposed `ScanAssembler.search()` is untouched. |
| 7 | Path Forward (4.1–4.4) | `[x]` | **Option 1: Direct Adjustment** (refine Story 10.26 in place + FR-22/AD-9 SCP updates). Rollback is N/A (no production code shipped); MVP review is N/A (MVP unaffected). |

---

## 3. Recommended Approach

**Direct Adjustment — Refine Story 10.26 in place (with targeted FR-22 and AD-9 SCP updates).**

- **Rationale:** Story 10.26 already owns this exact user value, dependency (`10.20` + `10.21` BETA call), and review-confirmation UX (*"marked as filled and pre-selected for checking"*). Updating its acceptance criteria eliminates the pre-search false-uniqueness bug, locks in the Story 10.19 agreement-gate integrity, defines the exact `ScanReview` / `CalibrationOffer` / `ScanDiagnostics` contracts, and lets the implementing developer cherry-pick the Spotless-verified spike commit `49f26258` from `spike/cubie-invariant-resolver`.
- **Effort:** Low–Medium (domain core & replay harness already built in `49f26258`; remaining Story 10.26 work is `QuickScanController.take()`, `GuidedScanFlow.reviewOf()`, `ScanReview` + `ScanReviewScreen` filled-sticker marking/pre-selection, `CalibrationOffer`, `ScanDiagnostics` debug/release parity, and the two-device human gate).
- **Risk:** Low — algebraic uniqueness before Lab plausibility + piece-typed budgets + plain-center guard achieved **0** false positives across 277 replay runs, and Guided Scan is untouched.
- **Timeline / Sequencing Impact:** None. Story 10.26 remains gated behind Story 10.21's BETA call.

---

## 4. Detailed Change Proposals

### 4.1 Epics (`_bmad-output/planning-artifacts/epics.md`)
- **Front-matter:** `updated: 2026-11-23` → `updated: 2026-11-27`.
- **Story:** `Story 10.26: Quick Scan fills a few unread stickers from the cube's rules` (lines 2638–2659) refined in place to specify:
  - Spike refs (`docs/spikes/cubie-invariant-color-resolution-spec.md`, `docs/spikes/cubie-invariant-color-resolution-spike-results.md`, cherry-pickable commit `49f26258` on `spike/cubie-invariant-resolver`).
  - **AC1 (Domain resolver & invariants):** Gated `QuickScanClassification.read` input + `ScanAssembler` internal posed-placement helper (sole rotator, AD-9); Stage 0 plain-center agreement guard, piece-typed budgets (`4` corners / `4` edges), confident piece integrity, `MAX_UNCERTAIN_STICKERS = 12`; Stages 1–2 algebraic uniqueness over all algebraically legal colors (`MAX_SEARCH_NODES = 1024`, strict abort on `AMBIGUOUS` or `SEARCH_BUDGET_EXCEEDED`); Stage 3 post-uniqueness per-face light-adjusted Lab plausibility (`corrected` vs `saved.centers` when scaling applied, else raw `labs` vs `normalized`, `L_WEIGHT = 1.0`).
  - **AC2 (Controller, Review, `CalibrationOffer`, UI & Diagnostics):** `QuickScanController.take()` after hold 2 (`captured.size == 6`, `fallbackFaces.isNotEmpty()`, valid `SelfCalibration`, including after `backOut()` with non-empty `keptSet`, surfacing any resolved indices on kept faces); `QuickScanEffect.Done(samples, resolved: ResolutionOutcome.Resolved? = null)` leaving non-resolved `Done` call sites unchanged; `GuidedScanFlow.reviewOf(QuickScanEffect.Done, saved)` populating `ScanEvidence` from `SelfCalibration.from(done.samples, saved)` + net-ordered `labs`; `ScanReview(filled: Set<Int> = emptySet())` pre-selecting `blanks` → `filled` → `suspects` → `problems`, clearing `filled` on `paint()`/`corrected()` and on retaken faces in `retaken()` while keeping `accepted` enabled for whole-cube confirmation; `CalibrationOffer.offered` counting `(review.blanks.size + review.filled.size) >= MANY_UNCERTAIN`; `ScanReviewScreen` distinct filled-sticker visual + TalkBack marker using `en.json` copy (never `strings.xml`); `QuickScanController.lastResolution` logged via `ScanDiagnostics.onQuickScanRun(...)` in both `app/src/debug/` and `app/src/release/` (`resolver=<Resolved(n)|Unchanged(reason)>`).
  - **AC3 (Replay & Verification):** Split-hygiene offline replay (`TUNING` vs untouched `HELDOUT`, counting non-`ACCEPTABLE` groups as `INVALID_INPUT`, respecting Story 10.27's `Fixtures/corner/label-exclusions.json` once landed) across all 4 calibration scenarios with **0** confidently wrong stickers on resolved/kept faces; `CubieInvariantResolverTest` adversarial edge cases, `:core:domain` Kover `>= 80%`, and green `QuickScanControllerTest`, `GuidedScanFlowTest`, and `ScanReviewTest`; Guided Scan's `ScanAssembler.search()` unchanged.

### 4.2 PRD (`_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md`)
- **Front-matter:** `updated: 2026-11-20` → `updated: 2026-11-27`.
- **FR-22 Consequences (line 492):**
  - **OLD:** `If perspective, glare or occlusion leaves stickers uncertain or the state invalid, the app asks for only the faces it is missing, using the Guided 6-face Scan. It never produces a guessed state.`
  - **NEW:** `If perspective, glare or occlusion leaves stickers uncertain (beyond a uniquely determined, plausibility-checked cubie-invariant resolution) or the state invalid, the app asks for only the faces it is missing, using the Guided 6-face Scan. It never produces a guessed state.`
- **FR-22 Consequences (line 494):**
  - **OLD:** `Quick Scan helps the Learner light the cube: when the light is behind the cube it says so and waits, it meters on the cube, it uses the Learner's saved color calibration when one exists, and it turns on the torch where the device has one. It never turns an uncertain sticker into a suggested color. *(SCP 2026-11-21, Story 10.19)*`
  - **NEW:** `Quick Scan helps the Learner light the cube: when the light is behind the cube it says so and waits, it meters on the cube, it uses the Learner's saved color calibration when one exists, and it turns on the torch where the device has one. It never turns an uncertain sticker into a heuristic suggested color. *(SCP 2026-11-21, Story 10.19)* When all six posed faces are captured and 3×3 cube invariants yield a single unique valid cube whose deduced stickers also pass per-face light-adjusted plausibility, Quick Scan may pre-fill those uniquely determined stickers and present them in review marked as filled and pre-selected for Learner confirmation; it never guesses between multiple valid cubes. *(SCP 2026-11-27, Story 10.26)*`

### 4.3 Architecture Spine (`_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md`)
- **Front-matter:** `updated: 2026-11-22` → `updated: 2026-11-27`.
- **AD-9 — Assembly and classification (addition after line 259):** Records `CubieInvariantResolver`'s gated input, `ScanAssembler` sole-rotator posed-placement helper, algebraic-uniqueness-before-plausibility rule, and `GuidedScanFlow` / `ScanReview` handoff preserving `ScanEvidence` and `CalibrationOffer`, with Guided Scan's `ScanAssembler.search()` unchanged.
- **AD-9 — Acceptance (line 264):**
  - **OLD:** `every UNCERTAIN sticker has been corrected or confirmed by the Learner;`
  - **NEW:** `every UNCERTAIN sticker has been corrected or confirmed by the Learner, and any cubie-resolved sticker from Quick Scan arrives in ScanReview pre-filled, visually marked as filled, and pre-selected for Learner confirmation before acceptance *(SCP 2026-11-27, Story 10.26)*;`

### 4.4 Sprint Status (`_bmad-output/implementation-artifacts/sprint-status.yaml`)
- **No structural change required:** `10-26-quick-scan-fills-a-few-unread-stickers-from-the-cube-s-rules` remains `backlog` in Epic 10, gated behind `10-21-quick-scan-keeps-the-side-faces-it-reads-well`.

---

## 5. Implementation Handoff

- **Change Scope:** **Moderate** (docs-only SCP + planning updates merged cleanly via `docs/cubie-invariant-resolver-spec`; Developer implementation of Story 10.26 after Story 10.21 BETA call).
- **Branch Isolation:**
  - `docs/cubie-invariant-resolver-spec` is **docs-only** (`ae61b332` + this SCP + `epics.md`, `prd.md`, `ARCHITECTURE-SPINE.md`) and safe to merge into `main` via PR.
  - `spike/cubie-invariant-resolver` holds the unmerged spike code commit `49f26258` strictly for future cherry-picking in Story 10.26 (never merge `spike/cubie-invariant-resolver` directly into `main`).
- **Developer Agent (Story 10.26, after Story 10.21 BETA call):**
  1. Branch from `main` after Story 10.21 merges and `git cherry-pick 49f26258` from `spike/cubie-invariant-resolver`.
  2. Update `CubieInvariantReplayTest` to count non-`ACCEPTABLE` groups as `INVALID_INPUT` (not silently skipped) and respect Story 10.27's `Fixtures/corner/label-exclusions.json` once landed; re-run against Story 10.21's tuned baseline to verify **0** wrong resolved stickers.
  3. Wire `QuickScanController.take()` (`lastResolution` + `QuickScanEffect.Done`), `GuidedScanFlow.reviewOf(QuickScanEffect.Done, saved)`, `ScanReview` (`filled` set + lifecycle), `CalibrationOffer.offered` (`blanks + filled`), `ScanReviewScreen` filled-sticker indicator/TalkBack (`en.json`), and `ScanDiagnostics` (both debug and release stubs).
  4. Run `./gradlew spotlessCheck build` (including `:core:domain` Kover `>= 80%`) and complete the two-device human gate.
