# Sprint Change Proposal: Corner fixture label cleansing and pastel plausibility (2026-11-23)

- **Trigger:** Story 10.20 acceptance run, 2026-11-23 ~14:35 CST: `ColorCalibrationTest` pastel check fails on `lenovo-tb128xu_stlb_bar-warm` hold 2 `L[8]`: BLUE is not plausible for Lab(56.2, -20.1, -13.4)
- **Mode:** Batch
- **Scope:** Moderate (one new device-free story in Epic 10 that carries a label-cleansing pipeline and a domain check, one PO action item (done), two annotations, an interim expected-failure marker)
- **Product owner decisions:** hebelmx, 2026-11-23 (section 3)
- **Revision 4 (2026-11-23):** addresses an adversarial review of revision 3 (section 8): stale revision-2 text replaced, scope re-rated, interim rule for the failing pastel test, committed exclusion file with a CI check, guards on exclusions and on the fit, 10.21 records its label set.
- **Revision 3 (2026-11-23):** after a label-cleansing spike and the PO's review (section 7), Story 10.27 becomes the label-cleansing pipeline plus the pastel check; Q1 decided (10.21 does not wait); Q2 superseded.
- **Revision 2 (2026-11-23):** addresses an adversarial review (4 gaps: an untracked 10.21 gate, no fitting method, an undefined wrong-label test, no treatment of unresolved suspects); section 6

## 1. Issue summary

Story 10.20 AC4 (SCP 2026-11-22, #166) asked that a pastel stickerless cube pass `ColorPlausibility` or that the chroma floors in `ColorCalibration.kt` be tuned. The recording used `stlb` as the pastel cube (Lenovo, bar-warm; calibration chroma Blue 40 and Orange 45, against 62 and 87 for GAN `stla` in the same light). One sticker failed: a real, pale teal-blue at hue 213.6°, just outside the fixed Blue window (215–315°). Its own calibrated Blue centre sits at 235°, so measured against the cube's own colours it is an ordinary blue.

Fixed windows are a poor oracle for pastel cubes: widening them for one cube loosens them for every cube. FR-24 already says stickers are classified against the cube's own centres; the plausibility oracle should follow the same rule.

Where the fixed windows act today: in production only as the calibration swatch gate (`CalibrationSession.offer`, `ColorPlausibility.valid`), which all pastel centres pass; in tests, as the label oracle for `Fixtures/live` and the pastel corner groups.

### What "the label check" is

Every fixture sticker carries a **label**: the true colour, entered by a person in the recorder's face review. Story 10.21 tunes the keep rule, agreement gate and light cuts by counting how often the app's read matches these labels, so a wrong label teaches the tuning the wrong thing. The label check compares each label with what the camera measured for that sticker, judged against that cube's own calibrated colours, and lists the stickers that don't fit. A person then looks at each listed sticker in its photo and decides: the label was wrong (**relabel**), the sample missed the sticker (**exclude**), or the label is right and the rule is too strict (**keep**).

### Evidence (SCP-time analysis, 1,680 labelled, calibrated stickers)

**Pastel spread** against each sticker's own calibrated centre:

| Colour | Pastel `stlb` (32 each): max hue gap / min chroma ratio | Other cubes: median hue gap |
|---|---|---|
| Blue | 25.1° / 0.61 | 5.3° |
| Green | 15.6° / 0.55 | 2.7° |
| Orange | 14.7° / 0.77 | 4.6° |
| Red | 26.0° / 0.57 | 4.1° |
| Yellow | 21.4° / 0.70 | 3.4° |

**Suspects:** 27 stickers in 7 non-pastel corner groups (none in `Fixtures/live`): 14 labelled with a colour more than 40° from that colour's centre at chroma ≥ 20 (several near 170°, the opposite hue) and 13 labelled chromatic but nearly grey (chroma < 20). Candidate causes: label errors (#327, face review selects sticker 0) or samples off the sticker (#315, default keypoints).

**Fitting trade-off (why the fitting set matters):**

| Fitted on | Pastel `stlb_bar-warm` passes | 10.20 held-out, non-suspect, passes | Wrong-label refusal |
|---|---|---|---|
| Live fixtures + `stlb_cool` (frontal faces; **proposed**) | 46 / 48 | 307 / 367 | strong: 100% for most pairs; weak pairs Orange→Red 14%, Red→Orange 75%, Yellow→Green 84%, Blue→White 90% |
| Live + all corner tuning groups (angled side faces) | 48 / 48 | 360 / 367 | collapses: X→White 4–28%, Red→Orange 4% |

Angled, shaded side faces drift far from the frontal calibration (the 10.19 addendum's finding), so a rule wide enough for every side-face sticker can no longer tell a colour from White. The proposal therefore fits on frontal data, keeps the oracle strict, and routes the side-face stickers it flags to the label review rather than widening the rule. With that fit, about 100 corner stickers (60 held-out, 42 tuning, plus the 27 suspects, overlapping) would be listed for review.

## 2. Impact analysis

| # | Item | Status | Note |
|---|---|---|---|
| 1 | Trigger understood | [x] | 10.20 evidence README, "Results after recording" item 3 and "PO decisions after recording" |
| 2 | Epic impact | [x] | Epic 10 gains Story 10.27. No epic added, removed or resequenced |
| 3 | Story impact | [!] | 10.20 AC4 annotated (result routed to 10.27). 10.21 Depends-on: does not wait for 10.27 (Q1); starts after `docs/device-session-2026-11` (with the corrected labels) is merged; uses 10.27's shared fixture filter once it exists; records the label-set commit and exclusion-file hash with each run. Existing evidence: 10.23 H3 already used the corrected labels; the 10.20 evidence predates them and is annotated as pre-correction |
| 4 | PRD | [N/A] | FR-24 already requires classification against the cube's own centres |
| 5 | Architecture | [N/A] | Domain-only change in `dev.cubentia.domain.recognition` (AD-9) |
| 6 | UX documents | [N/A] | No Learner-facing change |
| 7 | Code | [!] | `ColorCalibration.kt` (`plausibleFor`), `ColorCalibrationTest.kt` (interim `KNOWN_PASTEL_PROBLEMS`, removed by 10.27), `tools/label-audit/`, committed `Fixtures/corner/label-exclusions.json` read by a shared fixture filter (`QuickScanSideFaceReplayTest`, 10.21 runs), a CI label-count test, `TruthSource.REVIEWED` |
| 8 | Sprint status | [!] | `10-27-clean-the-corner-fixture-labels-and-judge-pastel-stickers: backlog`; action item `scp-2026-11-23-label-review` (PO, done 2026-11-23; the corrections reach `main` with the `docs/device-session-2026-11` PR) |
| 9 | Path forward | [x] | Direct adjustment |

## 3. Recommended approach

**Direct adjustment: a new device-free Story 10.27**, plus a tracked PO label review.

PO decisions, 2026-11-23:

1. Pastel plausibility becomes its own story, not a fixed-window tweak and not part of 10.21.
2. Scope is the domain check and the test oracle; the calibration swatch gate keeps its fixed windows, and the story records the pastel centres' margin to them.
3. No new recordings: reuse the earlier `stlb` runs (corner `lenovo-tb128xu_stlb_bar-warm` and `lenovo-tb128xu_stlb_cool`; live `lenovo-tb128xu_stlb_studio-a` and `sm-s721u1_stlb_studio-a`).

4. **Q1:** Story 10.21 does not wait for Story 10.27; it tunes on the labels corrected in the PO review (section 7).
5. **Q2 (superseded):** the manual label list is replaced by an automated pipeline (9-of-each and piece checks, per-group photo reads, review page); the plausibility fit stays on frontal data.
6. Cleansing is automated (PO, 2026-11-23: a manual list is cumbersome and error-prone).

Effort: medium to large (audit CLI and CI count test, photo-read pipeline, generated review page and write-back, domain function, fit and substitution test, shared fixture filter). Risk: low; no production behaviour change. Start: once `docs/device-session-2026-11` (fixtures, `groups.json`, corrected labels) is merged.

**Interim rule until 10.27 lands:** `ColorCalibrationTest`'s pastel check lists `lenovo-tb128xu_stlb_bar-warm` hold 2 `L[8]` in `KNOWN_PASTEL_PROBLEMS`. Any other problem still fails, and the test also fails if a listed problem stops being reported, so the entry cannot linger silently. Story 10.27 removes the list.

## 4. Detailed change proposals (applied on branch `docs/scp-2026-11-23-pastel-plausibility`)

### 4.1 epics.md

- **New Story 10.27** "Clean the corner fixture labels and judge pastel stickers against the cube's own colours" (revision 3), after Story 10.26: (1) `audit.py` count and piece checks per group, centres included, non-zero exit on failure; (2) per-group photo reads with corrected outlines, corner-model placement error reported to #315; (3) quarantine plus a script-generated review page and write-back (label fields only, logged), optional DINOv2 off-sticker flag; (4) `plausibleFor` fitted on frontal data, the whole `stlb_bar-warm` group held out, exclusions limited and reported, `KNOWN_PASTEL_PROBLEMS` removed, swatch gate unchanged; (5) substitution test with per-pair bars, `plausibleFor`-only rate reported; (6) a shared fixture filter over a committed exclusion file, used by `QuickScanSideFaceReplayTest`; no threshold changes. Revision 4 also adds: a CI test of the count and piece rules, `truthSources` = `reviewed`, a placement limit for the photo read.
- **Story 10.20, AC4:** annotation routing the pastel result to 10.27.
- **Story 10.21, Depends on:** does not wait for 10.27 (Q1); starts after `docs/device-session-2026-11` is merged; tunes on the corrected labels; once 10.27's shared fixture filter exists its runs use it; every tuning and held-out result records the label-set commit and the exclusion-file hash, and the baseline is re-run when 10.27's exclusions land.
- Front matter `updated:` 2026-11-20 → 2026-11-23.

### 4.2 sprint-status.yaml

- `10-27-clean-the-corner-fixture-labels-and-judge-pastel-stickers: backlog` after 10-26.
- Action item `scp-2026-11-23-label-review` (owner PO, done 2026-11-23).
- At 10.20 close-out (device-session branch), the pastel part of `epic-10-deferred-10.20-recording-session` is owned by 10.27.

## 5. Implementation handoff

- **Scope:** Moderate. Developer agent: `/bmad-orchestrate story 10-27-clean-the-corner-fixture-labels-and-judge-pastel-stickers` once `docs/device-session-2026-11` is merged.
- **PO:** the label review (`scp-2026-11-23-label-review`, done); decisions on the stickers 10.27's pipeline quarantines.
- **Success criteria (Story 10.27 ACs 1–6):** every corner group passes the count and piece checks, in CI too; placement error reported to #315; quarantined stickers decided on the generated review page and written back as `reviewed`; fitted constants committed before the validation run, the whole `stlb_bar-warm` group passes with at most the PO-approved exclusions (never hold 2 `L[8]`), `KNOWN_PASTEL_PROBLEMS` removed; substitution bars met, or a PO acceptance recorded before the run; replay and 10.21 skip through the shared filter; no production behaviour change.

## 6. Review response (revision 2)

*Historical: AC numbers and `label-review.json` refer to revision 2; revision 3 replaced them (see sections 4.1 and 5 for the current ACs).*

| Gap | Resolution |
|---|---|
| 10.21 gate untracked, no outcome | Action item `scp-2026-11-23-label-review`; undecided rows are skipped by default, so 10.21 can never tune on them and is never blocked indefinitely; Q1 records whether it also waits |
| No fitting method, fit = acceptance data | Fitting set, rule and margins stated in AC1; numbers committed before validation; validation group held out from fitting |
| Wrong-label test undefined | AC3 defines the substitutions (every sticker × 5 colours), the refusal rule (including nearest-centre for Red/Orange), per-pair bars and SCP-time baselines |
| Suspects' treatment unspecified | AC4–5: each row adjudicated against its photo with decision, rationale and date; `exclude` and undecided rows are skipped by replay and tuning |

## 7. Label-cleansing spike and PO review (revision 3)

**Spike** (`tools/label-audit/`, branch `spike/label-cleanse`): each corner photo's faces located with `corner_keypoints.tflite`, inner-60% median CIELAB per cell, six clusters per capture group seeded from its own centres, and the count rule (two holds = whole cube = 9 of each colour).

**Findings:**

| | Result |
|---|---|
| Groups whose labels did not give 9 of each colour | 10 of 23 before review; 0 after |
| Labels corrected by the PO | 20 (12 files), 11 of them WHITE that is really BLUE |
| PO review steps | sticker cards (168), face cards with draggable outline (51), whole-face pass (55 faces), full-cube pass (23 cubes) |
| Corner-model face placement | 22 faces off by 7–26 px on ~200 px photos (PO-corrected outlines kept as test data, #315) |
| App read vs spike read on the 168 hard stickers | 93 vs 36 right; but the app read caught 2 of 18 errors and the spike 11 (labels were pre-filled from the app read, so it cannot see its own misreads) |
| False positives | of 47 flagged stickers on confirmed faces, 5 were wrong labels; 0 errors on 14 unflagged faces |

Conclusions carried into Story 10.27: the count rule is the strongest signal; a piece check pinpoints the sticker when counts are off (Red↔Orange cases the photo cannot resolve); an independent photo read is needed but its face placement must improve; centres must be editable (a centre label can be wrong); the review pages must be generated by a script so a round can be rerun.

Evidence: `docs/evidence/10-27/label-corrections-2026-11-23.json`, `docs/evidence/10-27/label-review-decisions-2026-11-23.json` (branch `spike/label-cleanse`, commit `c9c9f5cc`).

Correction to the table above (re-derived from the evidence for revision 4): the PO moved **40** face outlines in 14 groups; 32 moved 7 px or more (max 26.3 px), the rest are nudges; some were moved only for visibility, so 10.27 uses them after a PO pass marks each as a correction or not. Of the 20 corrected labels, 18 were pre-filled (12 WHITE→BLUE) and 2 entered.

## 8. Review response (revision 4)

| Finding | Resolution |
|---|---|
| SCP and sprint-status disagree on the label-review item | Item done in both; the merge that carries the labels is the device-session PR, a 10.21 start condition |
| Revision-2 text left in sections 2, 4, 5 | Rewritten to the revision-3/4 ACs; section 6 marked historical |
| Scope and effort understated | Moderate; medium to large |
| Failing pastel test has no interim rule | `KNOWN_PASTEL_PROBLEMS`, strict both ways; 10.27 AC4 removes it |
| Audit runs outside CI; skip list not committed | AC1 commits `Fixtures/corner/label-exclusions.json` and adds a Kotlin CI test of the count and piece rules |
| 10.21 baseline moves when 10.27 lands | 10.21 records the label-set commit and exclusion hash per run and re-runs its baseline |
| 10.27 AC over 10.21's code | AC6 limited to a shared filter and the replay test; 10.21 adopts the filter |
| Exclusions could make AC4 pass by fiat | Exclusions in the validation group only by PO decision with photo evidence, at most 2 per group, never hold 2 `L[8]`, count reported |
| Substitution AC cannot fail | `plausibleFor`-only rate reported separately; PO acceptance recorded before the run |
| Max/min fit set by one sticker; White undefined | The sticker that sets each bound is reported and PO-confirmed; White limits defined |
| Fit/validation order unchecked; one held-out sticker | Constants in their own commit, SHA cited; whole `stlb_bar-warm` group held out |
| Corrected labels indistinguishable from recorder entries | `truthSources` = `reviewed` |
| Photo read on misplaced faces | Faces off by more than 10% of the face side use a corrected outline or are unreadable |
| Not applied: piece-check behaviour for blank labels, schemes and wrong centres | Left to the story spec |
