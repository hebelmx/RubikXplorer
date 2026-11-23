# Issue drafts (not posted; PO review first), 2026-11-23

## 1. Recorder: pre-filled sticker colours turn the app's misreads into fixture labels (WHITE for BLUE)

**What happens.** In the fixture recorder's corner review, each face opens with every sticker pre-filled from the app's own read (`truthSources: prefilled`). The operator confirms the face, so an app misread becomes the ground-truth label. The 2026-11-23 label review corrected 20 labels in the corner fixtures; **11 were WHITE that is really BLUE**, all pre-filled, mostly on the shaded side face.

**Why it matters.** Pre-filled labels share the app's mistakes, so evaluating the app against them hides its errors: on the reviewed stickers the app's read agreed with 16 of the 18 wrong labels at the time (it caught 2), while an independent photo read caught 11. Story 10.21 tunes on these labels.

**Expected.** Pre-filled stickers whose read was UNCERTAIN or a close call are shown as `?` (not pre-filled), or are visibly marked for confirmation; the recorder never saves a pre-filled colour the classifier was unsure of as if the operator chose it.

**Evidence.** `docs/evidence/10-27/label-corrections-2026-11-23.json` (branch `spike/label-cleanse`, `c9c9f5cc`). Related: #327 (face review selects sticker 0), Story 10.27.

## 2. Corner model places faces 7–26 px off on corner fixture photos (measured against PO-corrected outlines)

**What happens.** During the 2026-11-23 label review the PO dragged the face outline found by `corner_keypoints.tflite` onto the real face for 22 faces in 13 groups. The model's outline was off by 7–26 px (mean corner distance) on the ~200 px saved corner photos, enough to sample the neighbouring sticker or the cube body (e.g. `sm-s721u1_stla_bar-warm` corner-2, `sm-s721u1_stla_bar-warm-2`).

**Note.** Some outlines were moved only to see a sticker's colour under the review page's magenta overlay, so the set should be checked once before it is used as test data.

**Expected.** Use the corrected outlines as labelled test data for the corner model's placement error (with #315's relabelled corners), and report placement error per device and light.

**Evidence.** `docs/evidence/10-27/label-review-decisions-2026-11-23.json` (`quad:*` entries hold the corrected and original outlines). Related: #315, Story 10.27.
