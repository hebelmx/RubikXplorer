# Continuous facelet scanning proposal

Quick Scan fell short of the learner experience we wanted and is deferred for this release. Explore whether a learner can turn a scrambled 3×3 cube freely in front of a camera, see the sticker map grow immediately, and finish by correcting only the gaps. This worktree tracks that investigation.

## What the learner sees

1. Bring the cube into the viewfinder at a comfortable position and angle. A guide can suggest a pose. Clear stickers on part of a face can already count.
2. Watch a six-face map fill with well-supported observations, including stickers on adjacent faces. While orientation is ambiguous, show local detections in the preview and wait before placing them on the map.
3. Turn the whole cube in the hand. Repeated views should confirm earlier readings, identify disagreements, and let the learner continue after briefly losing sight of a face. The first experiment assumes the learner keeps the layers fixed.
4. Offer a short, specific cue when progress stalls. Glare across a sticker calls for a tilt. A missing side calls for a turn. A view producing new evidence needs no prompt.
5. Once little remains, offer another look or manual entry for the missing and conflicting cells. Preserve the rest of the map. Validate the reviewed cube before using it.

## Proposed experiment

A small model consumes sampled video frames and proposes individual sticker locations or visible face quads, keeping geometry decoupled from final color decisions. Geometry and tracking associate observations with physical squares. Oblique adjacent views establish face adjacency and quarter-turn orientation (topological stitching), while viewing-angle and glare checks gate final sticker color commitments toward near-frontal, well-lit views. A checker applies immediate local physical invariants (impossible same/opposite colors on a shared cubie, duplicate cubies across stitched faces, logo-tolerant centers) as well as full-cube validation to reject an inconsistent placement, reopen an uncertain cell, or request another view. Keep the observation and its source so that a bad association can be reversed. Unobserved stickers stay blank. Feeding a check back into view selection is a first experiment. Updating model weights during a scan would be a separate research question.

**PC-first gate:** Prove the full registration, color-fusion, and regrip-recovery algorithm working end-to-end on PC replay before scoping or building new Android stories. A desktop reference and a portable candidate can share recordings, labels and scoring even if the implementations differ. Only after the PC algorithm reliably assembles exact 54-cell nets with zero wrong committed stickers on held-out clips should we run the physical-device pass to answer live capture, usability, and runtime questions. A tracker that fails with accurate hand-labelled observations needs work on association before a faster phone could help.

## Decision to make

Which implementation deserves a PC video spike, and what device evidence would justify proceeding on phones? Compare the Lenovo Tab M10 Plus (3rd Gen) and Galaxy Tab S7 FE 5G with the available Galaxy S24 FE. Consider an exact Galaxy S24 variant as a possible minimum for this feature. A change to the minimum device for the wider app would need its own product decision.

[Research and sources](research.md) · [Experiment tracker](tracker.md)
