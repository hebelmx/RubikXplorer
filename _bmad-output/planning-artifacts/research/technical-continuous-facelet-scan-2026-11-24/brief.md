# Continuous facelet scanning proposal

Quick Scan fell short of the learner experience we wanted and is deferred for this release. Explore whether a learner can turn a scrambled 3×3 cube freely in front of a camera, see the sticker map grow immediately, and finish by correcting only the gaps. This worktree tracks that investigation.

## What the learner sees

1. Bring the cube into the viewfinder at a comfortable position and angle. A guide can suggest a pose. Clear stickers on part of a face can already count.
2. Watch a six-face map fill with well-supported observations, including stickers on adjacent faces. While orientation is ambiguous, show local detections in the preview and wait before placing them on the map.
3. Turn the whole cube in the hand. Repeated views should confirm earlier readings, identify disagreements, and let the learner continue after briefly losing sight of a face. The first experiment assumes the learner keeps the layers fixed.
4. Offer a short, specific cue when progress stalls — distinguishing a steep viewing angle (*"tilt face closer to camera"*) from frontal specular glare (*"tilt slightly away from the light"*) and a missing side (*"turn to show another side"*). A view producing new evidence needs no prompt.
5. Once little remains, offer another look or manual entry for the missing and conflicting cells. Preserve the rest of the map. Validate the reviewed cube before using it.

## Proposed experiment

A small model consumes sampled video frames and proposes individual sticker locations or visible face quads, keeping geometry decoupled from final color decisions. Geometry and tracking associate observations with physical squares. Retain raw per-sticker color samples (with frame viewing-angle, glare, and sharpness metadata) and solve global 6-cluster color assignment once enough centers/faces are observed, rather than greedily naming colors from the first uncalibrated frame. Oblique adjacent views establish face adjacency and quarter-turn orientation (topological stitching), while a graded per-sticker observation-quality score (foreshortening, specular clipping, sharpness) weights color commitments without deadlocking under overhead light or desk-tablet angles. On solved, mid-solve, or rotationally symmetric faces, never lock quarter-turn rotation from a single face's sticker colors alone — wait for two adjacent centers with a shared edge or tracked overlap to an asymmetric anchor. A checker applies immediate local physical invariants (impossible same/opposite colors on a shared cubie, valid corner cyclic chirality, per-color `≤ 9` cap, duplicate-free cubies, logo-tolerant centers) with evidence-weighted conflict reopening (reopening only the lower-support observation) as well as full-cube validation. Keep the observation and its source so that a bad association can be reversed. Unobserved stickers stay blank. Feeding a check back into view selection is a first experiment. Updating model weights during a scan would be a separate research question.

**PC-first gate:** Prove the full registration, color-fusion, and regrip-recovery algorithm working end-to-end on PC replay before scoping or building new Android stories. A desktop reference and a portable candidate can share recordings, labels and scoring even if the implementations differ. Only after the PC algorithm reliably assembles exact 54-cell nets with zero wrong committed stickers on held-out clips should we run the physical-device pass to answer live capture, usability, and runtime questions. A tracker that fails with accurate hand-labelled observations needs work on association before a faster phone could help.

## Decision to make

Which implementation deserves a PC video spike, and what device evidence would justify proceeding on phones? Compare the Lenovo Tab M10 Plus (3rd Gen) and Galaxy Tab S7 FE 5G with the available Galaxy S24 FE. Consider an exact Galaxy S24 variant as a possible minimum for this feature. A change to the minimum device for the wider app would need its own product decision.

[Research and sources](research.md) · [Experiment tracker](tracker.md)
