---
title: 'GH #402 bound ContinuousFaceletMapper per-frame cost and memory'
type: 'bugfix'
created: '2026-11-27'
status: 'done'
baseline_revision: '34892cd530963c51146f77c4c0e813c859981846'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** `ContinuousFaceletMapper` appends one `CellObs` per visible cell every frame and never trims, and `CubeFragment.faceRobustLab` copies and sorts a cell's whole list on each of ~100 calls per frame, so per-frame time and heap grow linearly with scan length (GitHub issue #402).

**Approach:** Cap every per-cell observation list at a constant `K` (evicting the lowest-quality observation, the newest among equal-quality ties, when an add would exceed `K`), and cache each cell's robust value per fragment, invalidated only when that cell's list changes. `K` is chosen above the longest test/golden scenario so their results are bit-identical.

## Boundaries & Constraints

**Always:** Every mutation of a per-cell list (frame update and pending-merge copy) goes through one capped-add path that also invalidates that cell's cache. The cached value must be exactly what the uncached `faceRobustLab` would return for the same list and arguments (both `includeStandardLab` variants). `K` >= the longest observed per-cell list in S1-S7, the hand-off tests and the golden sessions (measured: 86); planned `K = 256`. KDoc on the cap states it is a deliberate deviation from `pc_spike/mapper.py` (which keeps unbounded lists) and why. Test hooks are `internal` (module-visible only), not public API.

**Never:** No change to any threshold, quality floor, top-K fraction or snapshot/handoff shape. No `--refresh-golden`, no edit to `Fixtures/continuous/`, stress, hand-off or replay tests' expectations. No timing assertion in `build` (timing may only be logged). No edits to `pc_spike/`, planning artifacts, `_bmad/`, `.claude/skills/`, sprint-status.yaml.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Short scan | list length <= K | Same robust values, snapshots, handoff as before (no eviction) | No error expected |
| Long still scan | 10,000+ identical frames, 1 face and 3 faces in view | Every per-cell list <= K; robust recomputations per frame identical between an early and the last 1,000-frame window | No error expected |
| Pending merge into a full master cell | merge copies up to K obs into a target list | target list stays <= K; cache invalidated | No error expected |
| Repeated lookups in one frame | same cell queried many times, no new obs | one recomputation, then cache hits | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- the only production change. `CubeFragment` (~line 79): `obs` array (line 82), `obsList` (89), `faceRobustLab` (94-151, pure function of the list + `topKFrac` + `includeStandardLab`; no caller passes `topKFrac`), `confirmedFaces` (153). Mutations: frame append in `updateFragmentObservations` (~748, `frag.obsList(fname, ci).add(CellObs(...))`) and the pending merge copy in `tryMergePending` (~898-905, `target.add(...)`). Read-only size use: `p.obsList(pf, 4).size >= 2` (~890) and `obsList.isEmpty()` / iteration (~894). `CellObs.rgbAwb` is already AWB-applied, so the robust value does not depend on fragment `logAwb`. Fragments are replaced wholesale on promote/reset (`newFragment`, `promotePendingToMaster` ~934, `reset` ~191), so a per-fragment cache needs no extra invalidation there.
- `_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/pc_spike/mapper.py` -- READ-ONLY reference; unbounded appends at lines 660 and 783, `face_robust_lab` at 348.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- add the long-run structural test here (helpers for synthetic `QuadObservation` frames exist in this file and in `SyntheticCubeStream`; `app/.../ContinuousQuickScanFlowTest.kt` `solvedCornerView()` shows a 3-face corner view built from `QuadObservation`s).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousStressScenariosTest.kt`, `ContinuousHandoffTest.kt`, `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayTest.kt` -- must pass unchanged (regression guard).
- `docs/evidence/continuous-quick-scan-review-2026-11-26/probes/ReviewProbe*Test.kt.txt` -- the issue's JVM probes; reuse their still-frame construction idea.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- add companion constant `MAX_OBS_PER_CELL = 256` with KDoc (deviation from pc_spike, eviction rule, why K >= measured 86); route both list mutations through one `CubeFragment.addObs(face, cellIdx, obs)` that evicts when the list exceeds the cap and invalidates that cell's cache; cache `faceRobustLab` results per cell for both `includeStandardLab` variants; expose `internal` test hooks: the max per-cell list size across live fragments and a cumulative robust-recomputation counter -- bounds cost and memory without changing short-scan results.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- add a test feeding 10,000+ identical still frames for a 1-face and a 3-face view: asserts max list size <= cap at the end, and recomputations in the frames 1,001-2,000 window equal those in the last 1,000 frames; asserts steady-state recomputations per frame <= 2 x 9 x faces in view (cache hits for repeated lookups); logs (println) ms/frame of the first and last 1,000 frames without asserting them. Add a second test where master and pending fragments each accumulate > cap observations before a regrip merge (reuse the existing regrip-merge test setup in this file, lengthened) and assert \`reappearanceMergeCount >= 1\` and max list size <= cap afterwards -- structural proof of bounded per-frame cost on both mutation paths.

**Acceptance Criteria:**
- Given a mapper fed 10,000+ frames, when the run ends, then every per-cell observation list in every live fragment is <= `MAX_OBS_PER_CELL`, and the robust-recomputation count per frame is the same in an early and the last 1,000-frame window.
- Given the existing S1-S7 stress, hand-off and golden replay tests, when run with no `--refresh-golden` and no test/fixture edits, then they pass with zero diffs.
- Given `./gradlew spotlessCheck build`, when run, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 23 findings — high 0, medium 4, low 8, false 4, maybe-false 0 (7 rows are duplicates of another layer's finding and share its verdict: counted once per row below, totals by row = medium 9, low 9, false 5)
- findings:
  - `[low]` `[patch]` (blind) KDoc claims capped robust value equals the uncapped stable top-K — true only while lists <= cap (k, count, qualitySum use retained length). Reworded MAX_OBS_PER_CELL and addObs KDoc: identical only <= cap, deliberate approximation beyond.
  - `[medium]` `[patch]` (blind) Still-scan recompute-flatness assertion is 0 == 0 (tied qualities rejected once full). Added an 11,000-frame strictly-rising-quality test: every add evicts, recomputes per block 9000 == 9000 (> 0), retained = newest 256 frames.
  - `[low]` `[patch]` (blind) Eviction can freeze a cell on early high-quality observations; trade-off undocumented. Documented in KDoc (new obs enters a full list only if strictly above the retained minimum); policy itself is the approved best-K-by-quality design.
  - `[low]` `[patch]` (blind) Test hand-copies the 256 cap. MAX_OBS_PER_CELL is now a file-level `internal const`; the test references it.
  - `[medium]` `[patch]` (blind) No test pins which observation survives eviction. Added a 320-frame mixed/tied-quality test asserting retained == first 256 of the stable descending-quality order; tie-rule mutation fails it.
  - `[medium]` `[patch]` (blind) Cache hit / includeStandardLab variant untested. Covered by the new export-twice test (std-cache invalidation mutation fails it) and the rising-quality recompute counts.
  - `[low]` `[patch]` (blind) Merge test does not assert R reappears in reversed frames. Added assertion that Face.R is visible in one of frames 79..66.
  - `[low]` `[reject]` (blind) Timing in test has no JIT warm-up and adds build time. Timing is log-only by PO decision; the run costs ~1-2 s; not worth extra complexity.
  - `[low]` `[patch]` (blind) "Latest on ties" means insertion order, not frameIndex (merge appends pending after master). KDoc now says insertion position and notes the merge ordering.
  - `[low]` `[reject]` (blind) Four parallel cache arrays are redundant bookkeeping. Validity flags are needed because null is a legitimate cached result; refactor is not a direct correction and no named harm.
  - `[low]` `[patch]` (edge) Cell frozen on early observations after saturation. Same root cause as the blind freeze row; documented trade-off.
  - `[low]` `[patch]` (edge) Merged pending obs dropped when master cell is full of higher-quality obs. Same root cause (best-K-by-quality policy); documented in KDoc ("including a merged pending one").
  - `[false]` `[reject]` (edge) NaN qualities break min comparison. filterPlausibleQuads (ContinuousFaceletMapper.kt ~496-501) drops any detection whose cellQualities are not all finite, so NaN never reaches addObs.
  - `[medium]` `[patch]` (edge) Recompute assertion trivially passes with identical frames. Same as blind row; rising-quality test added.
  - `[low]` `[patch]` (edge) Hand-mirrored cap constant. Same as blind row; constant made internal.
  - `[low]` `[patch]` (edge) MAX_OBS_PER_CELL KDoc overclaims equivalence. Same as blind row; reworded.
  - `[low]` `[patch]` (edge) addObs KDoc membership equivalence mistaken for result equivalence. Same as blind row; reworded.
  - `[medium]` `[patch]` (verification-gap) Eviction policy not pinned by any test. Same fix as blind eviction row (stable-order retained-set test).
  - `[medium]` `[patch]` (verification-gap) Standard-Lab cache never checked across export, more frames, export. Added test: export mid-S1, feed rest, export again == fresh mapper single export (whole handoff and export()).
  - `[medium]` `[patch]` (intent) Counter measured as cache misses hits 0 at steady state. Same root cause as the trivial-assertion rows; rising-quality test shows a non-zero constant rate.
  - `[false]` `[reject]` (intent) Cache is wider than "nothing more". Done-when (1) requires a counter of robust-value recomputations per frame, and the issue's possible fix names the per-cell cache; the spec approved it and all replays are unchanged.
  - `[false]` `[reject]` (intent) K >= longest scenario is only stated, not asserted. The observable requirement (zero-diff replays with no --refresh-golden, unchanged S1-S7/hand-off) is met and verified; the 86 measurement was taken by instrumenting the mapper during planning.
  - `[false]` `[reject]` (intent) KDoc overstates equivalence / test surfaces differ. KDoc row patched above; the test-surface note is descriptive with no bad outcome (scan-level surfaces are covered by the unchanged suites).

## Design Notes

Eviction keeps the retained list equal to the first K entries of the stable descending-quality order the uncapped list would have (drop the element that sorts last: minimum quality, latest index on ties), so the highest-quality observations and `maxQ` survive. Counts and quality sums saturate at the cap, far above the >=5 / >=2.2 thresholds the mapper checks.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-402 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:test` -- expected: all pass, including the new long-run test.
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-402 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `git status --short Fixtures/` -- expected: empty (no golden refresh).

## Auto Run Result

Status: done

**Summary:** Each per-cell observation list in `ContinuousFaceletMapper` is capped at `MAX_OBS_PER_CELL = 256` through a single `CubeFragment.addObs` path (drop the lowest quality, newest by insertion on ties), and `faceRobustLab` results are cached per cell for both `includeStandardLab` variants, invalidated only when that cell's list changes. Internal test hooks expose the max list size, a cumulative recompute counter and one master cell's retained observations. The cap's KDoc records it as a deliberate deviation from `pc_spike/mapper.py`.

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- cap constant + KDoc, capped addObs, per-cell robust cache, internal test hooks.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- five new tests: 10k-frame still scans (1 and 3 faces), full-fragment regrip merge, 11k-frame rising-quality eviction, stable-order eviction rule, export-twice cache equality.

**Review findings:** 23 rows; patched: 4 medium entries (trivial recompute assertion, eviction rule unpinned, std-Lab cache unverified, cache-hit coverage) and low KDoc/test-constant/merge-assertion fixes; deferred: none; rejected: timing-noise (log-only by PO decision), cache-array refactor (no named harm), NaN quality (filtered upstream), cache-scope, K-assertion and test-surface notes (requirements met as specified).

**Follow-up review recommended:** true -- 4 medium entries patched on the first pass. Named unverified risk: the patched tests (rising-quality eviction, stable-order retained set, export-twice) were written in the fix round and reviewed only through the parent's diff read, not by an independent layer; and the best-K-by-quality freeze trade-off beyond 256 observations is documented but not field-validated on a real long scan.

**Verification:** `./gradlew :core:domain:test` BUILD SUCCESSFUL (ContinuousFaceletMapperTest 18/18, ContinuousStressScenariosTest 7/7, ContinuousHandoffTest 12/12, ContinuousSessionReplayTest 13/13, 0 skipped); `./gradlew spotlessCheck build` BUILD SUCCESSFUL; `git status --short Fixtures/` empty (no golden refresh). Logged: 3-face still scan 0.053 -> 0.033 ms/frame over 10k frames (issue measured 0.73 -> 2.67+ ms before the fix).

**Residual risks:** Past 256 observations per cell the robust value is an approximation of the uncapped one (by design); once full, a cell accepts only strictly better observations, so slow lighting change in a stalled scan is not tracked by lower-quality frames.
