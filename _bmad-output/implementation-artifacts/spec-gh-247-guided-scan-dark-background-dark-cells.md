---
title: 'GH-247 Guided Scan: dark background no longer rejects good frames as DARK_CELLS'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: 'caec143bd7dc292d068c3ef0af314deed429105c'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred:
  - summary: >-
      When dark-cell ranking promotes an acceptable in-tolerance lattice, a higher-scoring acceptable fit outside the tolerance overrides it, so a correctly framed face may be rejected with move coaching.
    evidence: |-
      Reviewers (blind-hunter, edge-case-hunter) could not show it happens; the override exists to stop a partial in-tolerance fit (one reading adjacent stickers twice) from capturing. Settle with recorded black-background device frames: count promoted-and-overridden frames whose inside read matched truth.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt:199-207
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** On the Lenovo TB128XU, a black background under the cube makes the Guided 6-face Scan reject good frames as `DARK_CELLS` (median 10 vs 1 per scan) and scan ~40 % slower (35.2 s vs 25.1 s); rejections track the grid's mean luma (rho = -0.95), i.e. the background, not the face. Three inputs to the decision still depend on what lies around the face: the face locator ranks candidate lattices with no regard for near-black cells, so on a black background (where a black-body cube's outline no longer contrasts) its few ranked reads can all straddle background and fail `DARK_CELLS`; a drawn grid made dark by background returns `LOW_LIGHT` before the face is located at all; and AE/AF/AWB meter the whole drawn grid square, background included.

**Approach:** Make the Guided Scan's dark-cell, light and metering decisions follow the located face lattice: the locator prefers candidates with no near-black cell (judged with the same `FrameQuality.DARK_CELL_MAX_L` / `DARK_CELL_MAX_CHROMA` thresholds) before spending its ranked reads, a fixed grid that is `LOW_LIGHT` is also located, and the Guided re-meter targets the accepted located lattice instead of the whole drawn grid.

## Boundaries & Constraints

**Always:** Keep `FrameQuality` thresholds, check order and the `DARK_CELLS` rule itself unchanged (a near-black cell inside the located face still means the grid is off the face). A face that fills the drawn grid exactly still reads exactly as today (no search). When no candidate is free of near-black cells, ranking falls back to today's score order. Calibration keeps sampling the drawn grid unless a lattice was accepted (`FrameReading.samplingSquare`). Corner (Quick Scan) metering and its `meteringGrid` centre fallback stay as they are. Camera code stays in `app/src/camera/`; no pixels are kept.

**Never:** No change to sticker classification, `CellReader`, `SelfCalibration`, `ScanAssembler` or the evaluation reader (`core/domain/src/evaluation`). No new thresholds tuned to beat the device experiment, no new feature flags, no change to `sprint-status.yaml` (action item `epic-10-deferred-10.8-lenovo-stka-guided-time` keeps its status), no planning-artifact edits. Do not retune `RANKED`, scale or offset tolerances.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Stickered black-body face on black background, smaller than and off the drawn grid | synthetic frame: face ~0.75-0.85 of grid, offset up to 0.5 cell, background = body color | `FrameReader.locate` returns an ACCEPTABLE located lattice with the face's colors; same face on a light background also ACCEPTABLE | No error expected |
| Candidate lattices that put a cell on near-black background | `FaceLocator.rank` on such a map | every returned candidate with a dark cell ranks after all candidates without one | If all candidates have a dark cell, returns today's score order |
| Drawn grid dark only because of background | fixed grid mean luma < `MIN_MEAN_LUMA`, located face bright | locate returns the located ACCEPTABLE lattice, not `LIGHT` | If no lattice is ACCEPTABLE, the fixed grid's `LIGHT` stats stand (stuck re-meter unchanged) |
| Truly dim face filling the grid | every cell dim | still fails `LIGHT` | as today |
| Guided re-meter after a located face | `ScanFrameSource` read a frame whose located lattice is ACCEPTABLE, then `meter()` | metering region is the located lattice's square (centre and size), not the drawn grid's | No accepted lattice: drawn grid, as today |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/FaceLocator.kt` -- `FaceLocator.rank` (line ~150) keeps the `RANKED`=4 best by `score`; `score` (~167) sums cell deviation, seam dips, outline contrast; `LumaMap` (~231) gives box `meanY`, `deviation`, `contrast` (add a box mean-U/V accessor as needed). Dark test belongs here, on each cell's central `FrameReader.CELL_ROI_FRACTION` box.
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- `locate(frame, grid, rotation)` (~178): returns the fixed grid when `assess != MISALIGNED` (so `LOW_LIGHT` short-circuits the search); `labOf(y,u,v)` (~651, private) converts YUV->Lab — reuse (make `internal`) for the map's mean cell color; `FrameReading.samplingSquare` (~56) = accepted lattice square else grid.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `CameraXFrameSource.read` (~538) sets `meteringGrid = MeteringGrid(w, h, grid)` before the corner branch; Guided path calls `FrameReader.locate` (~578). `meterOnGrid`/`meterOn` (~591-635) use `centreMeteringRegion(grid.square, ...)` (~722). Guided metering should use the located square; corner path unchanged.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `FrameQuality.inspect` (DARK_CELLS rule, `DARK_CELL_MAX_L`=25, `DARK_CELL_MAX_CHROMA`=15, `MIN_MEAN_LUMA`=40); `GuidedScanController.onFrame`/`remeterIfStuck` consume located `FrameStats` only — read-only, no change needed (it already depends only on what `FrameReader` locates).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/FaceLocatorTest.kt` -- synthetic `face(scale, dx, dy, sticker, body, background)` builder, `BODY`, `BACKGROUND`, `LIGHT_BACKGROUND`, `DARK`, `assertCaptures`, `assertColors`; fixture-frames-on-dark-background test (~244) must keep passing.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt` -- Robolectric `CameraXFrameSource.read` with `FakeImage`, `dimFace()`, `offsetFace()`; `deliversLocatedStatsAndLogsTheLatticeForAReadableDimFace` expects `LIGHT` for a uniformly dim face (must still hold).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt`, `FrameReaderTest.kt` -- existing policy / reader tests; extend where natural.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/scan/ScanAccuracyTest.kt`, `core/domain/build.gradle.kts` `evaluateScanAccuracy` -- offline fixture accuracy (fixed-grid evaluation reader, untouched by this change); must stay 432/432, 0 confidently wrong; `docs/scan-accuracy.md` must come out unchanged.
- Evidence: `Fixtures/matrix/experiment*_lenovo-tb128xu_*.log` (DARK_CELLS lines show the fixed grid's dark cells and best-ranked lattice); session C notes in commit `db95b550` `docs/evidence/session-c/notes.md`.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/FaceLocator.kt` -- in `rank`, mark a candidate dark when any of its nine cells' central-ROI mean color (Y, U, V from `LumaMap`, converted with `FrameReader`'s YUV->Lab) is at most `FrameQuality.DARK_CELL_MAX_L` and at most `DARK_CELL_MAX_CHROMA`; return the best non-dark candidates first, filling with dark ones by score only when fewer than `count` are non-dark. Expose the flag on `Lattice` only if diagnostics need it. Update the KDoc. -- the ranked reads go to lattices that can pass `DARK_CELLS`, so background no longer crowds out the face.
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- in `locate`, run the lattice search when the fixed grid's quality is `MISALIGNED` or `LOW_LIGHT`; keep the fixed-grid stats (and framing search) when none is accepted; update KDoc; make `labOf` reachable to `FaceLocator`. -- light is judged on the located face, not the background around it.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- in the Guided (non-corner) branch of `read`, set `meteringGrid` to the frame's `reading.samplingSquare(grid)` after locating; leave the corner branch's `meteringGrid` as the drawn grid; update the `meterOnGrid` KDoc. Add a small pure helper if that is needed for a JVM test. -- AE/AF/AWB meter the located face, not the background inside the drawn grid.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/FaceLocatorTest.kt` (and/or `FrameReaderTest.kt`) -- tests for the I/O matrix rows 1-4: black-body face on black background at several scales/offsets ACCEPTABLE with its colors (and on light background); `rank` orders dark-cell candidates after clean ones and falls back to score order when all are dark; background-darkened fixed grid located instead of `LIGHT`; uniformly dim face still `LIGHT`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt` -- test for matrix row 5: after `read` of an off-grid face whose lattice is accepted, the metering square equals the located lattice's square (via a `@VisibleForTesting` accessor or helper); with no accepted lattice it is the drawn grid.

**Acceptance Criteria:**
- Given the existing app and domain unit tests, when `./gradlew spotlessCheck build` runs, then everything passes, including the unchanged fixture-frame locator test and the dim-face LIGHT test.
- Given the committed live fixtures, when `ScanAccuracyTest` and `./gradlew :core:domain:evaluateScanAccuracy` run, then the Kotlin reader still reports 432/432 stickers, 8/8 scans Full and 0 confidently wrong across stla, stlb, stka and stkb, and `docs/scan-accuracy.md` has no diff.
- Given a face that fills the drawn grid exactly and reads ACCEPTABLE, when it is read, then no lattice search runs and its stats are identical to before.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 22 findings — high 0, medium 3, low 12, false 5, maybe-false 2
- findings:
  - `[medium]` `[patch]` (blind) LOW_LIGHT drawn grid with a well-lit face outside the tolerance coaches "low light", not the move — `GuidedScanController.coachingFor` now honors framing for LOW_LIGHT too; GuidedScanTest and FaceLocatorTest cases added.
  - `[low]` `[reject]` (blind) `promoted` misses an exact score tie between an earlier dark and a later clean candidate at the cutoff — needs an exact floating-point tie; fix needs stable-membership tracking (new state); unlikely in use.
  - `[false]` `[reject]` (blind) box-mean dark test vs `readAt` body removal demotes a dim sticker bordered by black plastic — the dark test uses the same central 60 % ROI as `readAt`; on an aligned candidate (search steps 0.05 scale / 0.1 cell) that box lies inside the sticker (border 7.5 % a side), so no body enters the mean.
  - `[maybe-false]` `[defer]` (blind) wide search can discard an acceptable promoted in-tolerance face when an outside fit scores higher — settle with recorded black-background frames; deferred as medium (unverified).
  - `[low]` `[reject]` (blind) metering race: a rejected frame between an accepted frame and the queued main-thread meter resets the square to the drawn grid — window is the main-thread post latency (ms) against a ~2 fps analyzer; the drawn-grid fallback is the specified behavior; a fix needs request-bound state.
  - `[low]` `[reject]` (blind) dark frames pay the nine-box Lab dark check on every candidate — patch attempted: the only ranking-preserving skip already exists (any further skip needs the dark test itself); worst case re-measured 7–8 ms on the JVM; no direct fix.
  - `[low]` `[reject]` (edge) metering race (same as above) — same reason.
  - `[medium]` `[patch]` (edge) LOW_LIGHT + outside face shows low-light coaching (same root cause as the first row) — same fix.
  - `[low]` `[reject]` (edge) `promoted` tie at the cutoff (same as above) — same reason.
  - `[low]` `[reject]` (edge) wide shortlist truncated before the score filter can leave a stronger wide fit unread — the outcome is capturing the promoted in-tolerance face instead of coaching; rare; fix needs a filter parameter in `rank`.
  - `[low]` `[reject]` (edge) `rank` with a negative count throws — no caller passes one (default `RANKED` = 4 and fixed test counts).
  - `[false]` `[reject]` (edge) black borders demote a readable sticker (same claim as the box-mean row) — same refutation.
  - `[maybe-false]` `[defer]` (edge) a correctly located, well-lit face can still be reported LOW_LIGHT via the promoted override (same root cause as the deferred row) — same deferral.
  - `[low]` `[patch]` (verification-gap) dark-cell chroma condition untested — added a dim saturated blue (L* ≈ 16, C*ab ≈ 55) ranking test that fails with the chroma condition disabled.
  - `[low]` `[patch]` (verification-gap) the "outside fit must score strictly higher" rule untested — added a promoted inside-fit test (`paleScrambled`, offset (-0.5, -0.7) on body) that fails without the comparison.
  - `[low]` `[patch]` (verification-gap) Guided-to-corner transition restoring the drawn-grid metering square untested — extended `deliversTheLocatedLatticeReadingWhenTheFaceIsOffTheDrawnGrid`.
  - `[medium]` `[patch]` (verification-gap, other) LOW_LIGHT coaching after locating an outside face (same root cause as the first row) — same fix.
  - `[false]` `[reject]` (intent) exposure effect is not measured — the intent puts the device measurement out of this build.
  - `[false]` `[reject]` (intent) DARK_CELLS rule and `GuidedScanController` untouched, so the rule depends on the region only indirectly — the rule already judges only the read cells; the diff changes which region is read, judged for light and metered, and now `coachingFor` too; the auditor's "no GuidedScanController exists" is wrong (it is in `GuidedScan.kt`).
  - `[false]` `[reject]` (intent) no fixture-accuracy evidence — `ScanAccuracyTest` 14/14 passed and `evaluateScanAccuracy` regenerated `docs/scan-accuracy.md` with no content diff (432/432, 0 confidently wrong).
  - `[low]` `[patch]` (intent) measured cost figures dropped from the `FaceLocator` KDoc — restored with re-measured figures (7–8 ms JVM, ~35–80 ms estimated ARM).
  - `[low]` `[patch]` (intent) `docs/scan-accuracy.md` left modified (line endings only) by the evaluator run — restored with `git checkout`.

## Design Notes

The device logs show `DARK_CELLS` reported from the fixed grid's reading whenever no ranked lattice was accepted; on a black background a black-body cube's outline has no contrast, so the outline term that anchors the correct lattice vanishes and uniform black background cells score as "uniform", letting off-face candidates fill the four ranked reads. Preferring dark-free candidates spends those reads on the face. A stable partition (clean first, then dark by score) means a frame can only gain candidates, never lose today's best when all are dark. Use the central CELL_ROI box (0.6), not `UNIFORM_ROI` (0.8), for the dark test so black seams/body around a sticker do not darken a real sticker cell. Device acceptance (Lenovo black vs light background within ~10 % median) is out of this build and runs before merge.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-247 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.*'` -- expected: all pass
- `... ./gradlew :core:domain:test --tests '*ScanAccuracyTest*' :core:domain:evaluateScanAccuracy && git diff --exit-code docs/scan-accuracy.md` -- expected: pass, 432/432, no diff
- `... ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** Guided Scan's dark-cell, light and metering decisions now follow the located face lattice. `FaceLocator.rank` spends its four ranked reads on candidates with no near-black cell first (same `DARK_CELL_MAX_L`/`DARK_CELL_MAX_CHROMA` thresholds on each cell's central 60 % mean color), falling back to score order. `FrameReader.locate` also searches a LOW_LIGHT drawn grid; a promoted in-tolerance fit yields to a higher-scoring accepted outside fit (keeps move coaching). Guided metering targets the accepted lattice square (drawn grid otherwise; corner mode unchanged). `GuidedScanController.coachingFor` coaches the move for LOW_LIGHT frames whose outside face was found.

**Files changed:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/FaceLocator.kt` -- dark-cell-aware ranking, `Lattice.promoted`, `LumaMap.isNearBlack`, refreshed cost KDoc.
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- search on LOW_LIGHT, promoted-fit check against wide fits, `labOf` internal (takes raw U/V).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- Guided metering square = `reading.samplingSquare(grid)`; `meteringSquare` test accessor.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `coachingFor` honors framing for LOW_LIGHT.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/FaceLocatorTest.kt`, `CameraXFrameSourceTest.kt`, `core/domain/src/test/.../GuidedScanTest.kt` -- tests for every I/O matrix row plus review gaps.

**Review:** 22 findings. Patched: 1 medium entry (LOW_LIGHT move coaching, 3 rows), 5 low (3 test gaps, cost KDoc, stray docs change). Deferred: 1 (promoted-fit override, medium unverified). Rejected: 5 false and 7 low, reasons in the triage log. Follow-up review: false (patched counts: high 0, medium 1, low 5).

**Verification:** `:app:testDebugUnitTest --tests 'dev.cubentia.app.camera.*'` passed (FaceLocatorTest 15/15, CameraXFrameSourceTest 5/5); `:core:domain:test --tests '*ScanAccuracyTest*' :core:domain:evaluateScanAccuracy` passed (ScanAccuracyTest 14/14) with no content diff in `docs/scan-accuracy.md` (432/432, 0 confidently wrong, 8/8 Full on stla, stlb, stka, stkb); GuidedScanTest 47/47; `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** The fix is validated on synthetic frames only; the black- vs light-background timing and rejection acceptance on the Lenovo TB128XU must run on the device before merge. The deferred promoted-fit override and the per-candidate Lab cost on dark frames (est. 35–80 ms on ARM) are unmeasured on device. Sprint-status action item `epic-10-deferred-10.8-lenovo-stka-guided-time` left unchanged.
