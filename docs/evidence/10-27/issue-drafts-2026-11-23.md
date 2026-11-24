# Issue drafts (not posted; PO review first), 2026-11-23

Revised after an adversarial review (2026-11-23): counts re-derived from the evidence files, the existing close-call gate and italic marking described, acceptance criteria added. Post after the `docs/device-session-2026-11` PR merges, with evidence links as permalinks to `main`.

## 1. Recorder: confidently wrong pre-fills become fixture labels (WHITE for BLUE); check 9 of each before saving a group

**What happens.** In the fixture recorder's corner review, each face opens pre-filled from the nearest calibrated centre (`prefill` in `RecorderRules.kt`). The pre-fill already leaves a sticker blank when its nearest centre beats the runner-up by less than `CLOSE_CALL_DELTA_E` (10 ΔE), and pre-filled stickers are shown italic, entered ones bold. Neither stopped the errors below: the wrong reads were not close calls, and the italic glyph is easy to confirm past on a 3×3 net.

The 2026-11-23 label review checked all 1,242 labels in the 46 corner records (23 groups × 2 holds × 27 stickers) against the count rule (two holds show the whole cube, so 9 of each colour) and corrected **20 (1.6%)**: 12 WHITE→BLUE, 3 ORANGE→RED, 2 RED→ORANGE, 1 YELLOW→WHITE, 1 GREEN→BLUE, 1 ORANGE→YELLOW. **18 of the 20 were pre-filled** (`truthSources` at `add0c79e`), including all 12 WHITE→BLUE; the 2 entered ones were ORANGE→RED. The WHITE→BLUE errors sit on the side faces of the corner hold (8 on face index 1, 4 on face index 2), which are angled away from the light and read pale.

**Why it matters.** A pre-filled label is the app's own read, so evaluating the app against it hides exactly these errors: the spike's independent photo read (`tools/label-audit/label_spike.py`) flagged 11 of the 18 pre-filled errors. Story 10.21 tunes on these labels and Story 10.23 evaluates against them.

**Expected.**
- Before a group is saved, the recorder counts the labels of both holds: if any colour is not exactly 9, it names the colours that are over and under, highlights the pre-filled stickers of the over-counted colour, and does not save until the counts are right or the operator overrides with a reason that is written to the manifest.
- Pre-filled stickers on the two side faces of a corner hold need an explicit tap (confirm or change) before the face can be confirmed.

**Acceptance.**
- A `RecorderRulesTest` case: a group whose labels count 10 WHITE / 8 BLUE is refused with both colours named; 9 of each is accepted; an override saves the reason.
- Replaying the pre-correction labels (`add0c79e`) of the 10 groups that had corrections through the count check refuses all 10 (each was off by 1–3 per colour). A wrong label swapped with another wrong label of the same pair would pass the count; the piece check in Story 10.27 covers that case.
- A face with an untouched pre-filled side-face sticker cannot be confirmed (UI rule test).

**Out of scope here.** Re-checking the existing corner fixtures is done (all 23 groups count 9 of each). Re-running 10.23 on the corrected labels is done (`docs/evidence/10-23/evaluation-10-20.md`); the 10.21 tuning has not run yet and uses the corrected labels. `Fixtures/live` was recorded without the corner hold and is not affected by the count rule; Story 10.27 audits it.

**Evidence.** `docs/evidence/10-27/label-corrections-2026-11-23.json`; `truthSources` in `Fixtures/corner/<group>/corner-N.json` at `add0c79e` (before correction). Related: #327 (face review selects sticker 0), issue 2 below (10 of the 20 corrected stickers sit on a face whose outline the PO also corrected, so some misreads may be placement, not colour), Story 10.27.

## 2. Corner model places faces up to 26 px off on corner fixture photos (measured against PO-corrected outlines)

**What happens.** During the 2026-11-23 label review the PO dragged the face outline found by `corner_keypoints.tflite` onto the real face for 40 faces in 14 groups. Placement error, measured as the mean Euclidean distance over the four corners matched by index, in the saved corner photo's pixel frame (~200 px across):

| | Faces |
|---|---|
| ≥ 7 px | 32 (10 groups), max 26.3 px (`sm-s721u1_stla_bar-warm` corner-2 face 1) |
| < 7 px | 8 (nudges, min 1.2 px) |

At 7 px and more the sampled cell can include the neighbouring sticker or the cube body. Errors concentrate on the GAN `stla` cube (27 of the 32) and on the Galaxy `sm-s721u1` (22 of the 32; 20 are both).

**Caveat.** Some outlines were moved only to see a sticker's colour under the review page's magenta overlay; the evidence does not mark which. The first task is a PO pass that marks each `quad:*` entry as `correction` or `visibility` and writes the cleaned file.

**Expected.**
- A device-free evaluation (tool under `tools/`, output under `docs/evidence/`) that scores `corner_keypoints.tflite` against the cleaned outlines plus #315's relabelled corners (where both cover a face, #315 wins; report the conflicts). Metric as above, also as a fraction of the corrected face's side length.
- A table per device × light × cube (count, median, p90, max).
- Report only, no bar in this issue; a bar, if any, is set in #315 from this baseline.
- Re-run the spike's photo read with the corrected outlines on the 10 corrected stickers that sit on a corrected face, to separate placement errors from colour errors (feeds issue 1).

**Evidence.** `docs/evidence/10-27/label-review-decisions-2026-11-23.json` (`quad:<group>|<hold>|<face>` entries hold `original` and corrected `quad` points). Related: #315, Story 10.27, issue 1.
