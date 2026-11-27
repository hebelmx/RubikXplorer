---
title: 'GH-401: a finger over one edge row must not latch a false layer twist'
type: 'bugfix'
created: '2026-11-27'
status: 'done'
baseline_revision: '412b6b5e7c9593cc26fd37c3b8055a6e031e43cb'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [oversized]
deferred:
  - summary: 'Light or brown skin tones within dE 14 of WHITE or ORANGE still pass the colour-only finger guard and can still latch a false twist.'
    evidence: 'Attempt-1 review (edge-case-hunter, intent-alignment): in shading-invariant Lab a very light skin tone sits about dE 11-12 from WHITE and a brown tone about dE 12-14 from ORANGE, both under STICKER_COLOUR_MAX_DE = 14. The synthetic finger sits about dE 18 from the nearest sticker. Follow-up: a structural guard, where the new colours of a flipped row must match a neighbouring face edge.'
    location: 'core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt (isStickerColour)'
    severity: medium
  - summary: >-
      Before anchoring, a finger resting on a centre long enough for its robust history to learn skin can make that centre look off-reference and switch the guard off for a stickered cube.
    evidence: |-
      Blind-hunter review 2026-11-27 (also raised in attempt 1). Not reproduced: the 20-seed 90x40 S1 test stays 0/20. Settle with a stream where a large finger covers one centre with qualitySum >= 0.40 before anchoring, counting false twists.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt (twistStickerPrototypes, pre-anchor branch)
    severity: medium (unverified)
  - summary: >-
      Before anchoring, Option A switches the guard off only from observed centres (qualitySum >= 0.40); a cube whose off-reference colour sits on a face not yet observed keeps the guard on, so a real turn bringing that colour in is not counted.
    evidence: |-
      Edge-case-hunter review 2026-11-27. Follows from the PO's Option A wording ("when the observed centre colours do not match the reference colours"), which this issue implements exactly; a fully pastel cube switches off on its first observed centre (pastel R 10/10, F 7/10 held). Partially off-palette cubes need either the structural guard or a per-colour rule; settle with a real-cube session on a cube with one off-reference colour.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt (twistStickerPrototypes)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The continuous Quick Scan mapper's layer-twist check (`ContinuousFaceletMapper.updateFragmentObservations`) counts a cell as "flipped" whenever its colour moves more than ΔE 33 from its history. A finger is uniformly coloured and keeps a high sample quality, so a large finger (about 90x40 px, one edge row of a ~194 px face) over one edge row for 5 frames latches a permanent `twistDetected`; through `SyntheticCubeStream` scenario 1 this happens on 20/20 seeds and no scan completes.

**Approach:** A layer turn swaps one sticker colour for another. Count a cell as flipped only when both its history colour and its new colour lie within ΔE 14 of a sticker prototype. After global anchoring the prototypes are the six anchored centres plus `ContinuousColorModel.REF_CENTER_LAB`. Before anchoring they are the fragment's observed centres plus REF — but (PO decision, Option A) when any observed centre is more than ΔE 14 from every REF colour (pastel or off-palette cube), the guard is switched off so real turns are detected exactly as today.

## Boundaries & Constraints

**Always:** Keep the `de > 33` flip threshold, the 5-frame latch, the `>= 2` flipped / `>= 4` stable counts, the `quality >= 0.60` gate and `OUTER_LINES` unchanged (epics.md:2915). The guard applies before and after global anchoring. S1-S7 keep 54/54 with 0 wrong; S5, S7 and the existing twist tests pass unchanged. The default `SyntheticCubeStream` finger stays 36x22 px so existing scenarios render bit-identically. Reuse the saved Option A patch (`.git/bmad-orchestrate/issue-401-attempt1/spec-gh-401-attempt-1-option-a.patch` in the main checkout; it applies cleanly to the baseline).

**Never:** No change to the latch's permanence or Reset behaviour, to AWB, observation appending, merging or colour solving; no new mapper output fields or cues; no structural (neighbour-edge) guard in this issue; no edits to planning artifacts, `sprint-status.yaml`, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Large finger, stickered S1 | `SyntheticCubeStream` S1, finger 90x40 px on 10 of every 24 frames, no drops/clutter/blur, no twist, 160 frames, seeds 1..20 | No frame reports `twistDetected`; final snapshot is `Complete` and equals the true state, on 20/20 seeds | No error expected |
| Finger on one row, before anchoring | Single yellow face, 7 clean frames, then top row skin (quality 0.9) for 10 frames | No twist latched; never anchored | No error expected |
| Finger lifts, before anchoring | Single green face whose top row was skin for 7 frames, then the true colour for 10 | No twist latched | No error expected |
| Finger on one row, after anchoring | S1 stream until `globalAnchored`, then the largest face's top row replaced with skin for 10 frames | No twist latched; anchored throughout | No error expected |
| Real twist, pastel, before anchoring | S7 config with `palette = PASTEL`, `twistMove` R then F, seeds 1..10 (not anchored at frame 55) | R: twist on 10/10 seeds; F: twist on the 7 baseline seeds {1,4,5,6,7,9,10} (count >= 7) | No error expected |
| Real twist, before anchoring | Existing minimal-history test (green face, top row turns red) | `TwistDetected` on the 5th twisted frame, logAwb frozen | No error expected |
| Real twist, after anchoring / stickered | Existing S7 (U turn at frame 55) | `TwistDetected`, 0 wrong, twistFrame >= 55 | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- `updateFragmentObservations` section "2. Nonrigid layer-twist check" (~line 761): the `deAll > 33.0` branch filling `flippedCells`. `centerPrototypes`/`globalRotIdx` set by `solveGlobalColorsAndNet`; `CubeFragment.faceRobustLab(face, 4)` with `qualitySum >= 0.40` is the centre gate; `OUTER_LINES` in the private companion. Per-frame cost is bounded (#402), so prototypes are built lazily, only when a candidate flip exists.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousColorModel.kt` -- read-only: `REF_CENTER_LAB`, `shadingInvariantLab`. Pastel centres sit ΔE 25-39 from REF, which is what switches the guard off for them.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/SyntheticCubeStream.kt` -- `ScenarioConfig`; finger ellipse hard-coded `rx = 36.0, ry = 22.0` in `generateSequence`; `buildScenarios()[0]` = S1, `[6]` = S7.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousStressScenariosTest.kt` -- S1-S7 stress tests.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- existing glare-washout and minimal-history twist tests.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayTest.kt` -- Story 10.34 golden replay; must stay green without `--refresh-golden`.
- Planning probe (this run, baseline 412b6b5e7c95): pastel S7-copy seeds 1..10 gives U 10/10, R 10/10, F 7/10 (seeds 1,4,5,6,7,9,10), never anchored at frame 55. Attempt 1 measured the same with Option A applied.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- apply Option A: `twistStickerPrototypes(frag): List<LabVec>?` (anchored six + REF; else observed + REF, or null when an observed centre is off-REF), companion `STICKER_COLOUR_MAX_DE = 14.0` and `isStickerColour` (null prototypes = true); require both `labs[ci]` and `h.lab` to be sticker colours in the `deAll > 33.0` branch. KDoc must not claim every finger is rejected (residual skin-tone limit).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/SyntheticCubeStream.kt` -- add `ScenarioConfig.fingerRadiiPx: Pair<Double, Double> = 36.0 to 22.0` and use it for the ellipse.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousStressScenariosTest.kt` -- add the 20-seed large-finger test (matrix row 1) and the pastel pre-anchor R/F twist test (matrix row 5, asserting not anchored at frame 55).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- add mapper tests for matrix rows 2-4.

**Acceptance Criteria:**
- Given the existing S1-S7, minimal-history twist, glare-washout and Story 10.34 golden replay tests, when they run unchanged, then all pass (S1-S7 54/54, 0 wrong); no `--refresh-golden` is needed — if one were, it is listed here with its per-frame diff and reason.
- Given the change, when `./gradlew spotlessCheck build` runs, then it succeeds.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 24 findings — high 0, medium 6, low 8, false 5, maybe-false 5
- findings:
  - `[maybe-false]` `[defer]` (blind-hunter) A finger on a centre before anchoring can switch the guard off — not reproduced (20-seed large-finger test 0/20); settle with a finger-on-centre stream; added to deferred[] as medium (unverified).
  - `[low]` `[reject]` (blind-hunter) Finger on an anchored pastel cube may pass via pastel ORANGE; REF widens prototypes after anchoring — no regression vs. baseline (issue evidence: pastel S2 0/20 false twists even with no guard); a fix adds a new estimator.
  - `[low]` `[patch]` (blind-hunter) Pastel R/F test did not check when the twist latched — fixed: a seed counts only when the first `twistDetected` frame is >= 55.
  - `[low]` `[reject]` (blind-hunter) F asserted as count >= 7, not the exact seed set — the matrix row states "count >= 7"; baseline count is what the PO asked to hold.
  - `[false]` `[reject]` (blind-hunter) "90x40" mislabels the finger (radii -> 180x80) — the issue table itself labels the column "radii, px" and its 20/20 evidence used exactly these radii.
  - `[false]` `[reject]` (blind-hunter) New mapper tests lack a positive control and may pass vacuously — with `isStickerColour` forced true, all four new finger tests failed (4/4 FAILED), so the guard is what makes them pass.
  - `[low]` `[reject]` (blind-hunter) KDoc "same tolerance as stable" not enforced by a shared constant — the numbers match today; the stable literal is a frozen rule number, coupling them is churn.
  - `[low]` `[reject]` (blind-hunter) REF prototypes rebuilt per call — built lazily at most once per frame and only when a candidate flip exists; negligible.
  - `[medium]` `[patch]` (blind-hunter) Partial anchoring (3-5 centres) fell into the pre-anchor path and could switch the guard off after anchoring — fixed: anchored with < 6 centres returns anchored + observed + REF, never null.
  - `[false]` `[reject]` (blind-hunter) Spec gaps as a durable artifact — fix would edit this build's spec.
  - `[low]` `[reject]` (blind-hunter) No test pins the residual skin-tone limit — tracked in deferred[]; an expected-to-latch test adds surface for no protection.
  - `[medium]` `[defer]` (edge-case-hunter) Pre-anchor off-REF colour on an unobserved face keeps the guard on and rejects a real turn — follows from the PO's Option A wording (observed centres); added to deferred[] (medium).
  - `[medium]` `[patch]` (edge-case-hunter) Partial anchoring can return null — same root and fix as the blind-hunter partial-anchoring row.
  - `[medium]` `[defer]` (edge-case-hunter) Centres with qualitySum < 0.40 / unobserved faces escape the switch-off — same root and deferral as the unobserved-face row.
  - `[medium]` `[patch]` (edge-case-hunter, claim) Guard missing after partial anchoring contradicts "all cubes after it" — same root and fix as partial-anchoring row.
  - `[maybe-false]` `[defer]` (edge-case-hunter, claim) Off-palette cubes with on-REF centres seen first lose pre-anchor twists — same deferral as the unobserved-face row.
  - `[medium]` `[patch]` (verification-gap) No test of a real twist on a pastel cube after anchoring — added `pastel real U and R twists after global anchoring are detected` (S2, seeds 1..5, twist at first anchored frame + 3). Probe on seeds 1..10: U 10/10, R 10/10 with and without the guard; F 0/10 both ways (baseline), so left out.
  - `[false]` `[reject]` (verification-gap, other) Guard switches on for pastel at anchoring — intended by Done-when (5); the post-anchor test above shows real pastel turns still latch.
  - `[maybe-false]` `[reject]` (intent-alignment) Evidence is synthetic skin only — the real-skin part is already the deferred skin-tone item; Done-when (1) names SyntheticCubeStream.
  - `[low]` `[reject]` (intent-alignment) "One edge row" geometry assumed, not asserted — same placement as the issue's own probe.
  - `[low]` `[reject]` (intent-alignment) 20-seed test is S1 with 160 frames, S2/S5 not looped — matches the issue probe; issue shows 0/20 on S2/S5 even without a guard.
  - `[maybe-false]` `[reject]` (intent-alignment) After-anchoring finger case only at mapper level — the 20-seed stream covers anchored frames incidentally; rendering-level variant would only be low.
  - `[maybe-false]` `[patch]` (intent-alignment) No pastel post-anchor twist/finger test — twist part grouped with the verification-gap patch above.
  - `[false]` `[reject]` (intent-alignment) Extra constant and history-end check beyond "nothing more" — both are the guard itself; rule numbers unchanged.

## Design Notes

Why both ends: with only a "new colour" guard the large finger still latched on 20/20 seeds, because the finger is on screen from frame 0, so history learned skin and the true sticker returning read as the flip. Why Option A: attempt 1 (guard always on before anchoring) dropped pastel R twists 10/10 -> 0/10 and F 7/10 -> 0/10, since pastel S2 stays unanchored until frame 107 of 120; the PO chose to switch the guard off for off-reference cubes before anchoring. The issue evidence showed 0/20 false twists on pastel even without a guard.

## Verification

**Commands:**
- `./gradlew :core:domain:test --tests '*recognition.continuous*' --tests '*ContinuousSessionReplayTest*'` -- expected: all pass, including the new tests.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** The layer-twist check now counts a flipped cell only when both its history colour and its new colour lie within ΔE 14 of a sticker prototype (anchored centres + REF after anchoring; observed centres + REF before). Option A: before anchoring, an observed centre off every reference colour switches the guard off. Review added a partial-anchoring fix (the guard never switches off after anchoring) and two test hardenings.

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- sticker-colour guard (`twistStickerPrototypes`, `STICKER_COLOUR_MAX_DE`, `isStickerColour`) in the flip branch.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/SyntheticCubeStream.kt` -- `ScenarioConfig.fingerRadiiPx` (default 36x22).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousStressScenariosTest.kt` -- 20-seed 90x40 finger test; pastel pre-anchor R/F test; pastel post-anchor U/R test.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- finger before/lifting/after anchoring tests.

**Review findings:** 24 findings; patched 3 entries (2 medium: partial anchoring, pastel post-anchor twist test; 1 low: twist-frame assertion); deferred 2 entries (finger-on-centre switch-off, medium unverified; unobserved off-REF colour pre-anchor, medium) plus the PO-required skin-tone limit; all other findings rejected with reasons in the triage log.

**Follow-up review recommended: true** -- two medium entries were patched; the partial-anchoring branch (anchored on 3-5 centres) has no test that demonstrably reaches it.

**Verification:**
- Baseline probe (pastel S7-copy, seeds 1..10): U 10/10, R 10/10, F 7/10; held with the guard (new test).
- Positive control: with the guard forced off, the four new finger tests all fail.
- `./gradlew :core:domain:test --tests '*recognition.continuous*' --tests '*ContinuousSessionReplayTest*'` -- BUILD SUCCESSFUL, 0 failures; no `--refresh-golden` needed (Story 10.34 golden replay unchanged).
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL.

**Residual risks:** colour-only guard limits (deferred[]): light/brown skin within ΔE 14 of WHITE/ORANGE; finger-polluted centre before anchoring; partially off-palette cube before anchoring.
