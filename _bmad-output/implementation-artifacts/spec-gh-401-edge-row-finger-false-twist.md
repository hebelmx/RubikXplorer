---
title: 'GH-401: a finger over one edge row must not latch a false layer twist'
type: 'bugfix'
created: '2026-11-27'
status: 'done'
baseline_revision: '412b6b5e7c9593cc26fd37c3b8055a6e031e43cb'
review_loop_iteration: 0
followup_review_recommended: false
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
  - summary: >-
      Before anchoring, a standard cube whose centre reads off-reference (warm light, weak AWB, or a logo centre) switches the guard off silently, so a finger can again latch a false twist.
    evidence: |-
      Follow-up review 2026-11-27 (blind-hunter), not reproduced. twistStickerPrototypes returns null when any observed centre is ΔE >= 14 from every REF colour, and no diag line shows the guard's mode. Settle with S3 (logo centre) and S1 with awbDrift = true, each with the 90x40 finger over 20 seeds, counting false twists, plus a device session.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt (twistStickerPrototypes, pre-anchor switch-off)
    severity: medium (unverified)
  - summary: >-
      After anchoring on 3-5 centres, a real turn that brings in an off-reference colour from a face not yet observed or anchored is not counted as a flip.
    evidence: |-
      Follow-up review 2026-11-27 (edge-case-hunter), not reproduced. In the partial-anchoring branch the prototypes are anchored + observed + REF, with the guard always on. Only a partly off-palette cube reaches it: anchoring on 3-5 centres needs average centre cost < 20 against REF, so a fully pastel cube (ΔE 25-39 off) never does. Settle with a synthetic palette that has one off-REF colour, anchored on 3-5 centres, then a turn that brings that colour in.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt (twistStickerPrototypes, partial-anchoring branch)
    severity: medium (unverified)
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

### 2026-11-27 — Review pass (follow-up)
- verdicts: 27 findings — high 0, medium 2, low 15, false 5, maybe-false 5
- findings:
  - `[low]` `[reject]` (blind-hunter) Before anchoring, a finger on a pastel cube still latches, and the docs do not say so. The KDoc already says the guard is switched off before anchoring for an off-palette cube. Issue evidence: pastel S2 had 0/20 false twists even with no guard. Not worth more docs or a test that expects a latch.
  - `[maybe-false]` `[defer]` (blind-hunter) A standard cube under warm light, or with a logo centre, can switch the guard off before anchoring with no diag. Not reproduced, and a diag field would be new mapper output (Never). Added to deferred[] as medium (unverified), with the S3/awbDrift finger probe that would settle it.
  - `[medium]` `[defer]` (blind-hunter) A pre-anchor real twist is rejected when its colour is off-reference and its centre has not been seen. carried: same claim and code as the unobserved-face row of the first pass; already in deferred[].
  - `[maybe-false]` `[defer]` (blind-hunter) A finger on a centre gets into the prototypes, with no count check. carried: same centre gate (`qualitySum >= 0.40`) and claim as the first pass's finger-on-centre row; already in deferred[]. The partial-anchoring case has the same root.
  - `[low]` `[reject]` (blind-hunter) The KDoc's "same tolerance" is two separate literals. carried: first-pass reject.
  - `[low]` `[patch]` (blind-hunter) The pastel R/F test hard-coded frame 55. Fixed: it now reads `template.twistAtFrame` (`requireNotNull`).
  - `[low]` `[patch]` (blind-hunter) The pastel R/F test silently dropped a seed that latched before the turn. Fixed: a twist latched before `twistAt` now fails the test, and the test still passes on every seed.
  - `[low]` `[reject]` (blind-hunter) "Exactly as before" is not asserted against the baseline per-seed result. carried: the first pass's F count >= 7 reject (the matrix row says "count >= 7").
  - `[low]` `[reject]` (blind-hunter) No finger test on a pastel cube after anchoring. carried: the first-pass reject "Finger on an anchored pastel cube may pass via pastel ORANGE" (no regression against baseline).
  - `[low]` `[reject]` (blind-hunter) The after-anchoring finger test is too easy: frozen detections, rows only, no lift-off. The guard is per cell and does not depend on line shape (OUTER_LINES is unchanged), and lift-off is covered before anchoring. More variants add test surface but protect nothing new.
  - `[low]` `[reject]` (blind-hunter) The large-finger test turns off noise and its "10 of every 24" comment depends on private scheduling. carried: the first-pass reject "20-seed test is S1 with 160 frames" (it matches the issue probe).
  - `[low]` `[reject]` (blind-hunter) The REF prototypes are rebuilt per frame. carried: first-pass reject (built lazily, at most once per frame).
  - `[maybe-false]` `[defer]` (edge-case-hunter) A centre history briefly holding skin, shadow or glare turns the guard off before anchoring. carried: the finger-on-centre row; already in deferred[].
  - `[maybe-false]` `[defer]` (edge-case-hunter) After anchoring on 3-5 centres, an off-reference colour from an unobserved face is never counted as flipped. Not reproduced. Reaching this branch needs a partly off-palette cube: average centre cost must be < 20 against REF, and pastel sits ΔE 25-39 off. Added to deferred[] as medium (unverified).
  - `[medium]` `[defer]` (edge-case-hunter) Before anchoring, all observed centres are near REF but an unobserved colour is off-REF, so a real turn is missed. carried: the unobserved-face row; already in deferred[].
  - `[low]` `[reject]` (edge-case-hunter) A cell between ΔE 14 and 33 counts as neither stable nor flipped, and finger samples now reach the cell history and AWB. At baseline a finger that did not cover a row was appended the same way, and the old line-twist exclusion ended in a permanent latch that froze every update. Measured: the 20-seed 90x40 S1 test completes correctly on 20/20 seeds. A skip set would add a new branch for no measured harm.
  - `[low]` `[reject]` (edge-case-hunter, deletion) The guard removes the old exclusion of finger-occluded faces from addObs and logAwb. Same root and evidence as the row above.
  - `[false]` `[reject]` (edge-case-hunter, claim) The third (partial-anchoring) branch contradicts the spec. The intent says the guard applies before and after anchoring, and Option A is worded for the before-anchoring case only. The KDoc and the first pass's patch row document the branch.
  - `[low]` `[reject]` (edge-case-hunter, claim) AWB and history behaviour under occlusion changed without the spec saying so. Same root as the history/AWB row above. The AWB and appending code is untouched, and recording the change would mean editing this build's spec.
  - `[low]` `[reject]` (edge-case-hunter, claim) F is asserted as count >= 7, not the exact seed set. carried: first-pass reject.
  - `[low]` `[reject]` (verification-gap) No test pins the partial-anchoring branch. The gap is real: deleting the line fails no test. It cannot be pinned cheaply, though. The 3-5-centre anchor needs average cost < 20 against REF, so S2 pastel (ΔE 25-39 off) never reaches it, and a test would need a new partly off-palette synthetic palette plus tuning. A developer is unlikely to revert that line in everyday work. The related behaviour risk is in deferred[].
  - `[low]` `[reject]` (intent-alignment) The after-anchoring tests do not tell the six-centre branch from the partial branch. Same root and reason as the verification-gap row.
  - `[false]` `[reject]` (intent-alignment) 90x40 as radii, not full size. carried: first-pass false (the issue's table gives radii).
  - `[false]` `[reject]` (intent-alignment) No test of a pastel cube with a finger before anchoring. The intent's Option A deliberately switches the guard off there, so that behaviour is intended, not a gap.
  - `[maybe-false]` `[reject]` (intent-alignment) Real camera skin tones are untested. carried: the first-pass "synthetic skin only" reject; the skin-tone limit is already in deferred[].
  - `[false]` `[reject]` (intent-alignment) The new code does not test the regression guarantees. The unchanged S1-S7, S5, S7, twist and golden-replay tests carry them, and they pass in this pass's verification run.
  - `[false]` `[reject]` (intent-alignment) An uncommitted spec status edit. That edit is this workflow's own in-review marker, and it is committed at finalisation.

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

### Follow-up review pass (2026-11-27)

**Summary:** Follow-up review of the sticker-colour twist guard (`twistStickerPrototypes`, `STICKER_COLOUR_MAX_DE`, `isStickerColour`). Production code is unchanged in this pass. Two test hardenings were applied to the pastel pre-anchor R/F test.

**Files changed (this pass):**
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousStressScenariosTest.kt`: the pastel R/F test reads the twist frame from `template.twistAtFrame`, and it now fails on a twist latched before the turn instead of skipping that seed.

**Review findings:** 27 findings (high 0, medium 2, low 15, false 5, maybe-false 5).
- **Patched:** 2 low entries (hard-coded frame 55; early latch hidden).
- **Deferred:** 2 new entries, both medium (unverified): a standard cube read off-reference switching the guard off before anchoring; an unobserved off-REF colour after anchoring on 3-5 centres. The medium rows were carried and are already deferred.
- **Rejected:** everything else, each with its reason in the triage log. That includes the verification-gap row: the partial-anchoring branch cannot be reached by a pastel stream (it needs average centre cost < 20 against REF), so pinning it would need a new synthetic palette.

**Follow-up review recommended: false.** This is a follow-up pass and it patched no high entry. Patched counts by verdict: high 0, medium 0, low 2.

**Verification (this pass):**
- `./gradlew :core:domain:test --tests '*recognition.continuous*' --tests '*ContinuousSessionReplayTest*'`: BUILD SUCCESSFUL. The patched pastel test passes on every seed, with no twist latched before the turn. No `--refresh-golden` was needed.
- `./gradlew spotlessCheck build`: BUILD SUCCESSFUL.

**Residual risks:** the items in deferred[]. These are the skin-tone limit, the finger-polluted centre, unobserved off-REF colours before anchoring and after anchoring on 3-5 centres, and a standard cube read off-reference before anchoring. The partial-anchoring branch has no test.
