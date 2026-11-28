# Task Specification: Deterministic Cubie-Invariant Color Resolution for Quick Scan

- **Status:** Parked / Deferred (2026-11-27) — see [continuous-cubie-resolver-spike-results.md](continuous-cubie-resolver-spike-results.md). Spike code (`CubieInvariantResolver` + replay tests) is preserved on remote branch `spike/cubie-invariant-resolver-main` (`5cdd1063`) for re-evaluation once physical Android device recordings land in `Fixtures/continuous/` (Story 10.36). Note: the 2-corner `QuickScanController` wiring described in §4 was superseded by Continuous Quick Scan (`ContinuousFaceletMapper`, SCP 2026-11-24); only the pure-domain algebraic solver (`CubieInvariantResolver.resolveNet`) applies to Continuous Quick Scan.
- **Target module:** `:core:domain` (`dev.cubentia.domain.recognition`)
- **Related decisions:** AD-2 (cube state & `CubeValidator` gate), AD-5 (categorical `StickerConfidence`), AD-9 (recognition: neutral samples in, validated observations out; `ScanAssembler` as sole rotator; never guesses), Story 10.19 (Quick Scan side-face agreement gate), Story 10.20 (10.22-pipeline baseline), Story 10.21 (Quick Scan tuning)

---

## 1. Context & Problem Statement

In **Quick Scan** (two 3-face corner holds capturing all 6 faces with named-top poses), angled side faces and uneven lighting cause a subset of stickers to fail either the `10.0 ΔE` close-call rule (`StickerClassifier.CLOSE_CALL_DELTA_E`) or the two-read agreement gate (`QuickScanClassification.read`).

Today:
1. `QuickScanController.take()` (`core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt`) hands over every face that has even **one** `UNCERTAIN` judged sticker to the Guided 6-face grid (`QuickScanEffect.Fallback`).
2. Even on clean front-lit captures with **zero** confident misreads (e.g., `sm-s721u1_stla_bar-cool-2`, `lenovo-tb128xu_stka_bar-cool`, `lenovo-tb128xu_stka_bar-warm` in `docs/evidence/10-20/replay-baseline.md`), 2–6 uncertain stickers force 1–4 faces into Guided fallback.

Because a physical 3×3 Rubik's Cube obeys strict mathematical invariants — exact per-piece color budgets (4 corners + 4 edges + 1 center per color), opposite-color exclusion, clockwise corner chirality, unique 20-piece cubie inventory, corner twist sum `≡ 0 (mod 3)`, edge flip sum `≡ 0 (mod 2)`, and permutation parity — we can deterministically resolve small-to-moderate sets of `UNCERTAIN` stickers using the **gated** readings and measured `Lab` values already captured by Quick Scan, **without guessing** and **without weakening the Story 10.19 safety bar (0 confidently wrong stickers on kept faces)**.

### Design Principles (Less Risk, Greater Gain)
- **Never bypass the two-read agreement gate:** The resolver consumes **gated** confidences from `QuickScanClassification.read()`, never ungated `ScanAssembler` confidences.
- **Separate algebraic uniqueness from Lab plausibility:** Prove uniqueness (`validCount == 1`) over **all algebraically legal colors** (without Lab window pruning) so a genuinely ambiguous cube can never be made to look falsely unique by pruning a competing valid state. Apply Lab plausibility **only as a post-uniqueness rejection filter** on the single valid state.
- **Strict uniqueness only (AD-9):** Promote uncertain stickers **only** when `validCount == 1` across the complete algebraic search. Zero ΔE-margin tie-breaking between 2+ valid states; hitting the search node budget always aborts (`SEARCH_BUDGET_EXCEEDED`), even if 1 valid state was already seen.
- **Whole-cube validation anchor:** Never promote an individual face in isolation without validating the entire 54-sticker cube through `CubeValidator.validate()`. Whole-cube validation is what catches upstream `CONFIDENT` misreads.
- **Piece-typed color budgets + iterative propagation + forward-checked MRV backtracking:** Tracking separate `remainingCorner[c] = 4 - confidentCorner[c]` and `remainingEdge[c] = 4 - confidentEdge[c]` budgets alongside chirality and seen-cubie propagation catches confident misreads earlier and lets the algebraic solver safely handle up to `12` uncertain stickers in `< 1 ms`.
- **Measurement-first two-phase rollout with split hygiene:** Tune/verify constants exclusively on `tuning` (and legacy) corner fixtures, report `heldout` untouched as the safety gate (0 confidently wrong stickers across all 4 calibration scenarios), and require an SCP updating AD-9 before wiring Phase 2 production code.

---

## 2. Architectural Constraints

1. **Pure Domain Logic (AD-1, AD-9):** All new code lives inside `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/`. Zero Android, CameraX, Compose, or pixel dependencies.
2. **Sole Sample Rotator (AD-9):** Only `ScanAssembler` in `:core:domain` rotates `FaceSample`s into URFDLB `Facelets` order (`ARCHITECTURE-SPINE.md`, AD-9). `CubieInvariantResolver` must delegate pose validation and net-order rotation to an `internal` posed-placement helper inside `ScanAssembler` rather than calling `rotateClockwise`/`netNeighbors` directly.
3. **Categorical Confidence Only (AD-5):** No numeric confidence or ΔE score crosses a module boundary. `CubieInvariantResolver` returns only `Facelets`, net-ordered `Lab`s (internal/review evidence), sticker indices, and a categorical `Reason` enum.
4. **Single Validation Gate (AD-2):** Every `Resolved` outcome must pass `CubeValidator.validate(facelets) is ValidationResult.Valid`.
5. **Preserve Existing Semantics:** `StickerClassifier`, `SelfCalibration`, `QuickScanClassification`, and Guided Scan's `ScanAssembler.search()` (unposed turn enumeration) remain unchanged.
6. **Posed 6-Face Scope Only:** Quick Scan captures all 6 faces with named-top poses (`upNeighborColor != null`). Unposed Guided Scan faces are explicitly out of scope to avoid interaction with turn-enumeration ambiguity.
7. **AD-9 Acceptance Governance (Phase 2 Gate):** Promoting deduced `UNCERTAIN` stickers to `CONFIDENT` in `ScanReview` requires an approved SCP amending AD-9's acceptance rule (*"every `UNCERTAIN` sticker has been corrected or confirmed by the Learner"*) before Phase 2 ships.

---

## 3. Specification: `CubieInvariantResolver.kt`

Create `object CubieInvariantResolver` in `dev.cubentia.domain.recognition`.

### 3.1 Public API

```kotlin
package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult

sealed interface ResolutionOutcome {
    /**
     * Exactly one physically valid cube state satisfied all algebraic cubie invariants and
     * passed the post-uniqueness Lab plausibility guard.
     * [facelets] holds all 54 colors in URFDLB net order; [labs] holds the corresponding 54
     * net-ordered measured CIELAB values (from `ScanAssembler` posed placement) for `ScanReview`
     * evidence; [resolvedIndices] lists the non-center net indices that were UNCERTAIN and
     * deterministically resolved.
     */
    data class Resolved(
        val facelets: Facelets,
        val labs: List<Lab>,
        val resolvedIndices: Set<Int>,
    ) : ResolutionOutcome

    /**
     * Resolution did not apply or could not prove a single unique valid state.
     * Callers must keep the original gated confidences unchanged.
     */
    data class Unchanged(
        val reason: Reason,
    ) : ResolutionOutcome

    enum class Reason {
        UNCALIBRATED,
        INVALID_INPUT,
        NO_UNCERTAIN,
        TOO_MANY_UNCERTAIN,
        CONFIDENT_INVARIANT_VIOLATION,
        PLAUSIBILITY_REJECTED,
        NO_VALID_STATE,
        AMBIGUOUS,
        SEARCH_BUDGET_EXCEEDED,
    }
}
```

#### Entry Points & Pose Placement
- **Helper in `ScanAssembler` (internal):**
  - Add an `internal` posed-placement helper in `ScanAssembler` that takes 6 `FaceSample`s, validates that:
    - all 6 distinct `Color.entries` appear exactly once as `sample.centerColor`,
    - every sample is `CaptureQuality.ACCEPTABLE`, has 9 valid `Lab`s, has `orientationDegrees % 90 == 0`, and has an `upNeighborColor` whose home face is a valid orthogonal neighbor in `NET_NEIGHBORS[slot]` (`direction >= 0`).
  - Returns the 6 placed faces in URFDLB order with their net-ordered raw `labs: List<Lab>` and the exact net-rotation mapping (`turns`) reused from `ScanAssembler.place()` so any per-face sticker list (such as gated `StickerReading`s or `corrected` `Lab`s) can be rotated via `ScanAssembler` without duplicating pose math.
- **`CubieInvariantResolver.resolve(samples: List<FaceSample>, saved: CalibrationCenters?): ResolutionOutcome`**
  1. Run `SelfCalibration.from(samples, saved)`. If `NeedsCalibration` → return `Unchanged(UNCALIBRATED)`.
  2. Call `ScanAssembler`'s internal posed-placement helper. If any face is missing, duplicated, malformed, poor quality, or has an invalid/missing pose → return `Unchanged(INVALID_INPUT)`.
  3. For each placed face, compute its **gated** readings via `QuickScanClassification.read(sample, samples, saved)` and rotate them into URFDLB net order using `ScanAssembler`'s placement mapping.
  4. Build the per-face plausibility comparison pair (see Stage 3 below) — respecting the actual types of `QuickScanClassification.corrected` and `QuickScanClassification.normalized`:
     - If `saved != null && StickerClassifier.isValid(saved)` **and** `QuickScanClassification.corrected(sample, saved)` actually applied scaling (`sample.centerPlain != false && sample.center != null && sample.center.isValid() && sample.center.l > 0.0`): rotate `corrected(sample, saved)` (`List<Lab>`) into net order and compare against `saved.centers` (`Map<Color, Lab>`).
     - Otherwise (no valid `saved`, or `corrected` fell back to raw stickers on a logo center `sample.centerPlain == false` / invalid center): use raw net-ordered `labs` for that face and compare against `QuickScanClassification.normalized(sample, samples, selfCal.centers)` (`Map<Color, Lab>`, keyed by `Face`, never rotated as a 3×3 grid).
  5. Delegate to the internal net-order solver `resolveNet(...)` (also directly unit-testable).

### 3.2 Constants (Initial Values — Tuned/Verified on `tuning` Split Only in Phase 1)
- `MAX_UNCERTAIN_STICKERS = 12` — hard cap on non-center uncertain stickers before propagation.
- `MAX_SEARCH_NODES = 1024` — DFS node cap for Stage 2 backtracking.
- `CANDIDATE_WINDOW_DELTA_E = 22.0` — post-uniqueness plausibility check: each deduced sticker's distance to its deduced color must be within `minDist + CANDIDATE_WINDOW_DELTA_E` (never used to prune competing valid states during search).
- `MAX_PLAUSIBILITY_DELTA_E = 38.0` — post-uniqueness hard upper bound on weighted ΔE for any deduced sticker.
- `L_WEIGHT` — lightness weight in `weightedDeltaE(a, b) = sqrt(L_WEIGHT * dl² + da² + db²)`. **Evaluate `1.0` (standard CIE76, since `corrected`/`normalized` already compensate `L*`) vs `0.5` on the `tuning` split** before locking a value.

### 3.3 Resolution Pipeline (4 Stages)

#### Stage 0 — Pre-Checks, Plain-Center Gate & Confident-State Integrity Guard
1. Verify all 54 net colors, gated confidences, measured `Lab`s, and per-face plausibility pairs are valid.
2. **Plain-Center Agreement Guard:**
   - For every face `f` (`centerIndex = f.ordinal * 9 + 4`):
     - If `sample.centerPlain != false` (plain center): require the gated center reading at `centerIndex` to be `CONFIDENT` **and** equal `Color.home(f)`. If it disagrees or is `UNCERTAIN` → abort with `Unchanged(CONFIDENT_INVARIANT_VIOLATION)`.
     - Only centers with `sample.centerPlain == false` (logo centers) are fixed to `Color.home(f)` without requiring a confident center read.
3. Collect `uncertain = (0 until 54).filter { it % 9 != 4 && netConfidence[it] != StickerConfidence.CONFIDENT }`.
   - If `uncertain.isEmpty()` → `Unchanged(NO_UNCERTAIN)`.
   - If `uncertain.size > MAX_UNCERTAIN_STICKERS` → `Unchanged(TOO_MANY_UNCERTAIN)`.
4. **Piece-Typed Confident Integrity Guard (early abort against confident misreads):**
   - Count `CONFIDENT` corner stickers and `CONFIDENT` edge stickers per color separately:
     - `remainingCorner[c] = 4 - confidentCornerCount[c]`
     - `remainingEdge[c] = 4 - confidentEdgeCount[c]`
   - If any `remainingCorner[c] < 0` or `remainingEdge[c] < 0` → abort with `Unchanged(CONFIDENT_INVARIANT_VIOLATION)`.
   - Inspect every corner in `FaceletTables.CORNER_FACELETS` and every edge in `FaceletTables.EDGE_FACELETS`:
     - If all facets of a piece are `CONFIDENT`: verify it decodes to a legal cubie (Western scheme) and is not duplicated by another fully-`CONFIDENT` piece; otherwise abort with `Unchanged(CONFIDENT_INVARIANT_VIOLATION)`.
     - If 2 facets of a corner are `CONFIDENT`: verify they are neither identical nor opposite colors (`oppositeColor(c)` helper mapping `W↔Y`, `G↔B`, `R↔O`); otherwise abort with `Unchanged(CONFIDENT_INVARIANT_VIOLATION)`.

#### Stage 1 — Deterministic Algebraic Fixed-Point Propagation
Initialize working state over **algebraically legal** candidates (no Lab window pruning):
- For each unassigned corner index `u`: `candidates[u] = { c in Color.entries | remainingCorner[c] > 0 }`.
- For each unassigned edge index `u`: `candidates[u] = { c in Color.entries | remainingEdge[c] > 0 }`.
- Track `assigned: Array<Color?>`, `remainingCorner: IntArray(6)`, `remainingEdge: IntArray(6)`, and `seenCorners` / `seenEdges` bitsets.

Route **every** assignment in Stage 1 through one helper `assign(u, c)` that validates:
- `remaining[pieceType][c] > 0`,
- `c` is neither equal nor opposite (`oppositeColor`) to any already-assigned sticker on `u`'s piece,
- if assigning `u` completes a corner: legal clockwise chirality and cubie not already in `seenCorners`,
- if assigning `u` completes an edge: legal edge and cubie not already in `seenEdges`.
Any violation immediately aborts with `Unchanged(NO_VALID_STATE)`.

Repeat until a full pass makes zero changes:
1. **Corner Chirality Completion:**
   - Precompute static `CORNER_CLOCKWISE_3RD: Map<Pair<Color, Color>, Color>` from `FaceletTables.CORNER_FACES` (verified in `Facelets.kt` to run clockwise from the U/D facet: `[c0, c1, c2]` yields `(c0, c1) -> c2`, `(c1, c2) -> c0`, `(c2, c0) -> c1`).
   - For any corner `[i0, i1, i2]` with 2 assigned facets and 1 unassigned facet `u`: look up `c = CORNER_CLOCKWISE_3RD[pair]` in the matching cyclic order. If `c == null` → abort `NO_VALID_STATE`; else call `assign(u, c)`.
2. **Algebraic Domain Pruning:**
   - For every unassigned index `u`:
     - Remove any `c` whose piece-type budget (`remainingCorner[c]` or `remainingEdge[c]`) is `0`.
     - Remove `a` and `oppositeColor(a)` for every assigned sticker `a` sharing a corner or edge with `u`.
     - If `u` is the last unassigned sticker on a corner or edge, remove any `c` that would complete a cubie already in `seenCorners` / `seenEdges` (or violate corner chirality).
3. **Forced-Singleton Deduction:**
   - If any unassigned `u` has `candidates[u].isEmpty()` → abort `Unchanged(NO_VALID_STATE)`.
   - If any unassigned `u` has `candidates[u].size == 1` → call `assign(u, candidates[u].single())`.
   - *(No separate bulk-assignment loop is needed: when only one color `c` retains budget `> 0`, step 2 reduces every unassigned sticker of that piece type to `{c}` or `{}`, and `assign(u, c)` enforces all piece invariants cleanly.)*

#### Stage 2 — Bounded Forward-Checked MRV Backtracking + `CubeValidator`
1. If no unassigned stickers remain after Stage 1:
   - Validate with `CubeValidator.validate(Facelets(assigned.map { it!! }))`.
   - If not `Valid` → return `Unchanged(NO_VALID_STATE)`.
   - Otherwise proceed to Stage 3 with `onlyValidFacelets`.
2. Otherwise, run recursive DFS backtracking over all remaining algebraically legal candidates:
   - **Node budget (strict abort):** Increment `nodesVisited`; if `nodesVisited > MAX_SEARCH_NODES` → **immediately terminate search and return `Unchanged(SEARCH_BUDGET_EXCEEDED)` regardless of whether `validCount == 1` was already found.**
   - **MRV variable selection:** Pick the unassigned sticker `u` with the fewest currently-legal algebraic candidates (tie-break by piece with fewest unassigned facets, then lowest net index for determinism).
   - **Branching order (heuristic only, never pruning):** Order `u`'s legal candidates ascending by `weightedDeltaE` so the most likely valid state is visited first, while still exploring every algebraically legal branch to prove uniqueness.
   - **In-search forward-checking:** When assigning `u = c`:
     - Enforce `remaining[pieceType][c] > 0`, opposite/same-color exclusion, and completed-piece cubie/chirality legality & uniqueness.
     - If `u` is a corner sticker and leaves exactly 1 unassigned facet `v` on that corner, immediately deduce `v`'s forced chirality color `c3` and check that `c3` is legal (`remainingCorner[c3] > 0` and cubie not in `seenCorners`); backtrack immediately if not.
     - Check that no remaining unassigned sticker has 0 legal colors left; backtrack immediately if so.
   - **Leaf validation & early stop on ambiguity:**
     - When all 54 stickers are assigned, call `CubeValidator.validate(Facelets(grid))`.
     - If `ValidationResult.Valid`:
       - If `validCount == 0`, store `onlyValidFacelets`.
       - If `validCount == 1` (a 2nd distinct valid state is found), **terminate search immediately and return `Unchanged(AMBIGUOUS)`.**
3. After DFS finishes without exceeding `MAX_SEARCH_NODES`:
   - `validCount == 0` → return `Unchanged(NO_VALID_STATE)`.
   - `validCount == 1` → proceed to Stage 3 with `onlyValidFacelets`.

#### Stage 3 — Post-Uniqueness Lab Plausibility Guard
Now that `onlyValidFacelets` is proven to be the **unique** algebraically valid cube state consistent with all `CONFIDENT` stickers:
- For every `u` in `uncertain` (on face `f = Face.entries[u / 9]`):
  - Using face `f`'s plausibility comparison pair `(plausibilityLab[u], plausibilityCenters[f])`, compute `d(c) = weightedDeltaE(plausibilityLab[u], plausibilityCenters[f][c])` for all 6 colors and `minDist = min(d(c))`.
  - Let `chosen = onlyValidFacelets.colors[u]`.
  - Require `d(chosen) <= minDist + CANDIDATE_WINDOW_DELTA_E` **AND** `d(chosen) <= MAX_PLAUSIBILITY_DELTA_E`.
  - If any deduced sticker fails → return `Unchanged(PLAUSIBILITY_REJECTED)`.
- All deduced stickers pass → return `ResolutionOutcome.Resolved(onlyValidFacelets, netLabs, uncertain.toSet())`.

---

## 4. Two-Phase Rollout Plan (Spike → `bmad-correct-course`)

### Phase 1 — Spike: Offline Evaluation & Safety Gate (No Production Wiring)
*Goal:* Produce reproducible empirical evidence on `Fixtures/corner/` without changing any production Quick Scan or Guided Scan behavior.

1. Implement `ScanAssembler`'s internal posed-placement helper and `CubieInvariantResolver.kt` in `:core:domain` (spike code, not wired into `QuickScanController` or `ScanReview`).
2. Add unit tests in `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/CubieInvariantResolverTest.kt`:
   - Corner chirality rescue (1 masked sticker with Red/Orange close-call).
   - Multi-sticker propagation + piece-typed color budgets (8–10 uncertain stickers collapsing to 1 unique valid state).
   - Edge + corner backtracking rescue where parity/twist/flip leaves 1 valid state.
   - **Plain-center disagreement guard:** Plain center read `UNCERTAIN` or wrong color → `Unchanged(CONFIDENT_INVARIANT_VIOLATION)`; logo center (`centerPlain == false`) still allowed to resolve.
   - **Piece-typed count overflow:** 5 confident corner stickers of one color (total ≤ 9) → `Unchanged(CONFIDENT_INVARIANT_VIOLATION)`.
   - **Confident misread safety:** 1–2 `CONFIDENT` wrong stickers + 2 `UNCERTAIN` stickers → `Unchanged` (`CONFIDENT_INVARIANT_VIOLATION`, `NO_VALID_STATE`, or `PLAUSIBILITY_REJECTED`), never a wrong `Resolved` state.
   - **Ambiguity safety (no false uniqueness from Lab window):** 2 algebraically valid states where the 2nd valid state is `> 25 ΔE` away → must still return `Unchanged(AMBIGUOUS)`.
   - **Plausibility guard:** Unique valid state requiring an impossible Lab distance (`> MAX_PLAUSIBILITY_DELTA_E`) → `Unchanged(PLAUSIBILITY_REJECTED)`.
   - **Search budget safety:** Truncated search after finding 1 valid state → `Unchanged(SEARCH_BUDGET_EXCEEDED)`, never `Resolved`.
3. Add an offline replay measurement test (`CubieInvariantReplayTest.kt` alongside `QuickScanSideFaceReplayTest.kt`) over `Fixtures/corner/` across all 4 calibration scenarios (`NONE`, `MATCHING`, `OTHER_LIGHT`, `OTHER_CUBE`), **enforcing `Fixtures/corner/groups.json` split hygiene**:
   - **Tuning / constant verification:** Evaluate `L_WEIGHT` (`1.0` vs `0.5`) and plausibility thresholds exclusively on `split == TUNING` (and legacy pre-10.19 groups).
   - **Held-out gate:** Report `split == HELDOUT` separately without tuning on it.
   - **Mandatory safety bar:** **0** confidently wrong stickers introduced on kept/resolved faces across *every* group (`TUNING`, `HELDOUT`, and legacy) and *every* calibration scenario.
   - **Gain report:** Print before/after `sideFacesKept`, count of full 6-face Quick Scans completing with `0` Guided fallback faces, and histogram of `ResolutionOutcome.Reason`.

### Phase 2 — Formal Planning via `bmad-correct-course` (Out of Scope for Spike)
If Phase 1 meets the **0 false-positive bar** on `TUNING` and `HELDOUT` and demonstrates meaningful fallback reduction, do **not** wire production directly from this spike. Instead, run `bmad-correct-course` to produce a Sprint Change Proposal (SCP) amending AD-9 and authoring formal Epic 10 stories covering:

1. **Story A (Domain & `QuickScanController` wiring):**
   - After hold 2 completes (`!inCorner`), when `captured.size == Face.entries.size && fallbackFaces.isNotEmpty() && SelfCalibration.from(captured, saved) is SelfCalibration.Calibrated`:
   - **Handling `backOut()` (`keptSet` non-empty):** Pass all 6 `captured` samples to `CubieInvariantResolver.resolve(captured, saved)` (re-evaluating gated confidences conservatively under the full 6-face calibration).
   - If `ResolutionOutcome.Resolved(facelets, labs, resolvedIndices)`: clear `fallbackFaces` and `repromptFaces`, and emit `QuickScanEffect.Done(captured, resolved = facelets, resolvedLabs = labs)`.
2. **Story B (`GuidedScanFlow` / `ScanReview` handoff & Diagnostics):**
   - Add a dedicated `GuidedScanFlow.reviewOf(done: QuickScanEffect.Done, saved: CalibrationCenters?)` called from `GuidedScanActivity.quickReview()` preserving `ScanEvidence` (`labs`), `ScanReview.retaken`, and `CalibrationOffer.offered(done.samples, saved, review)`.
   - Keep `ScanAssembler.search()` completely unchanged for Guided Scan.
   - Record `resolver=<Resolved(n)|Unchanged(reason)>` in `QuickScanController` / `ScanDiagnostics` debug output (counts and reason enum only, never pixels or numeric ΔE per AD-5/AD-9).

---

## 5. Verification Checklist

- [ ] Phase 1: `./gradlew :core:domain:test` passes including `CubieInvariantResolverTest` and `CubieInvariantReplayTest`.
- [ ] Phase 1: Replay report shows **0** confidently wrong stickers on resolved/kept faces across all groups (`TUNING`, `HELDOUT`, legacy) × all 4 calibration scenarios, with constants selected on `TUNING` only.
- [ ] Phase 1: Kover coverage on `:core:domain` stays `>= 80%`.
- [ ] Phase 2 gate: SCP updating AD-9 approved before wiring `QuickScanController` / `GuidedScanFlow`.
- [ ] Phase 2: `./gradlew spotlessCheck build` passes with zero regressions in `QuickScanControllerTest`, `QuickScanSideFaceReplayTest`, `GuidedScanFlowTest`, and `ScanReviewTest`.

---

## 6. Adversarial Review Revisions Applied

This revision incorporates 14 findings from an independent adversarial review against the codebase and `ARCHITECTURE-SPINE.md`:
1. **Separated uniqueness from Lab plausibility (§1, §3.3):** Removed Lab window pruning before Stage 2 search so pruning a valid state can never manufacture false uniqueness.
2. **Fixed `corrected` vs `normalized` types (§3.1, §3.3):** Handled `corrected(sample, saved): List<Lab>` (scaled stickers vs `saved.centers`) separately from `normalized(...): Map<Color, Lab>` (scaled centers vs raw stickers), with fallback to `normalized` on logo centers where `corrected` returns unscaled stickers.
3. **Added plain-center agreement guard (§3.3 Stage 0):** Aborts if a plain center (`centerPlain != false`) disagrees or is `UNCERTAIN`; only logo centers are exempt.
4. **Enforced AD-9 sole-rotator rule (§2, §3.1):** Routed pose validation and URFDLB rotation through an `internal` helper in `ScanAssembler`.
5. **Completed Phase 2 `ScanReview` handoff (§3.1, §4):** Carried net-ordered `labs` in `ResolutionOutcome.Resolved` / `QuickScanEffect.Done` and specified `GuidedScanFlow.reviewOf(QuickScanEffect.Done, ...)` preserving `ScanEvidence` and `CalibrationOffer`.
6. **Clarified `QuickScanController.take()` & `backOut()` guard (§4):** Replaced the ambiguous `keptSet.isEmpty()` parenthetical with exact `captured.size == 6` checks and explicit `keptSet` handling.
7. **Added AD-9 SCP governance gate (§2, §4, §5):** Required an SCP amending AD-9's acceptance rule before Phase 2 ships.
8. **Split color budgets by piece type (§1, §3.3):** Tracked `remainingCorner[c] = 4 - confidentCorner[c]` and `remainingEdge[c] = 4 - confidentEdge[c]` separately.
9. **Fixed non-existent `Color.opposite` (§3.3):** Specified private `oppositeColor(color: Color)` helper.
10. **Unified Stage 1 assignment invariants (§3.3):** Routed all propagation assignments through one `assign(u, c)` helper and removed the redundant bulk-assign loop.
11. **Completed entry-point validation (§3.1):** Explicitly handled `NeedsCalibration` (`UNCALIBRATED`), invalid `upNeighborColor`, and duplicate `centerColor`.
12. **Hardened DFS node-budget abort & forward-checking (§3.3):** Guaranteed `MAX_SEARCH_NODES` always returns `SEARCH_BUDGET_EXCEEDED` (never falls through to `Resolved`) and added in-search chirality/zero-candidate forward-checking.
13. **Enforced `tuning` vs `heldout` split hygiene (§1, §3.2, §4, §5):** Restricted constant verification to `tuning` and kept `heldout` untouched.
14. **Deferred `L_WEIGHT` lock to `tuning` evaluation (§3.2, §4):** Compared `L_WEIGHT = 1.0` vs `0.5` on `tuning` to avoid double-discounting already-normalized lightness.
