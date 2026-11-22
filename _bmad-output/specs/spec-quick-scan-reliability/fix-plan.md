# Fix plan

Revised after the 2026-11-22 review. Kotlin-side fixes come first: reading, gates and diagnostics stay in Kotlin, and
each fix also serves a later evaluation of any color reader. Failure modes are in [failure-modes.md](failure-modes.md).
Nothing here is implemented yet, and no pipeline code changes before the sprint change proposal is approved.

## Now: Kotlin fixes that need no new labelled data

| Step | Change | Addresses | Serves | Check |
|---|---|---|---|---|
| 0 | Diagnostics and a re-measured baseline | F13, F14 | CAP-2 | A bench run and a hand-held Quick Scan run, each with a full per-hold timeline |
| 1 | Lock exposure for the hold | F1, F2, F4 | CAP-4 | Burst and Quick Scan log show one exposure level per hold; still burst 1 replays as "not settled" during its swing |
| 2 | Steady by outline movement | F6 | CAP-4 | Zero restarts on both still bursts; windows complete on two hand-held bursts; a fast-moving burst restarts |
| 3 | Turn check not anchored on a logo centre | F15, F10 | CAP-4 | Still burst 1 replayed as hold 2 does not pass the pose gate |
| 4 | Small fixes | F11 | — | Unit test for back-out then re-capture |

0. **Diagnostics first.** Log every hold state transition unthrottled with an elapsed clock: viewfinder entry, first
   frame, detection lost and found, pose rejected, metering requested and settled, light hold start and clear,
   steady restart, capture and whether it was automatic or manual. Log the light score and the three face levels in
   recorder bursts. Report hold times net of undetected time. Then record the baseline again, still and hand-held,
   through the Quick Scan flow. The 2026-11-22 baseline has a probable manual capture and a lost first minute.
1. **Lock exposure.** After metering on the cube converges, apply an exposure lock (and carry white balance) until
   capture; leave focus continuous, which is why the 3-second auto-cancel exists (PO 2026-11-16). Judge "settled" on
   the product of exposure time and ISO staying within a tolerance over a window, not on the AE state and not on a
   few unchanged frames. Keep re-meter triggers: cube lost, torch change, outline leaving the metered region, and a
   large change in face brightness since metering (the learner turned toward the light). Do not re-meter merely
   because a light hold cleared.
2. **Steady by outline.** Define the measure precisely (largest corner displacement from the reference frame, divided
   by a stated outline dimension) and derive the threshold on the two still bursts and at least two hand-held bursts.
   The earlier 5% figure came from one burst and was not reproduced. Do this after step 1 is verified on the device:
   the color rule is today the only gate that reacts to an exposure shift.
3. **Turn check.** Do not anchor the turn and pose checks on a centre that is not plain; use the ring around a logo
   (`FrameReader.centerRing`) or the two side centres, and do not clear the previous hold on a single frame.
4. **Small fixes.** Clear `previous` in `backOut`.

## Later: needs labelled captures (Story 10.20) before design

| Topic | Addresses | Serves | What must exist first |
|---|---|---|---|
| Light check: advisory or block | F3, F5 | CAP-4 | Labelled captures taken at failing scores, scored per sticker; cuts re-derived under the step-1 exposure policy on both devices; the cause of single-face level jumps |
| Keep stickers, not faces | F7, F8 | CAP-5 | A per-sticker safety assertion in the side-face replay, run on the existing groups first; it will fail today and that sizes the work |
| Calibrate on the cube in hand | F9, F10 | CAP-6 | The calibration origin for the 2026-11-22 captures; a defined independent second read |

- **Light check.** Reversing the 2026-11-21 decision is a product call. On present evidence neither side is
  measured: nothing shows the hold improves readings, and nothing shows readings taken in the light it blocks. Any
  engage and clear levels must come from data; the earlier 1.40 and 1.30 would miss five of the eight backlit fixture
  holds.
- **Keep stickers.** This is a new search over per-sticker color candidates with its own pruning, a cap and a "too
  many unknowns, ask the learner" branch. `ScanAssembler` today only enumerates quarter-turns of unposed faces.
  Capture 2 had 17 uncertain stickers. A cube may finish without learner confirmation only when the true color is
  known to lie within the carried candidates.
- **Calibration.** Dropping the saved calibration removes the gate's only independent read. A change here must keep
  two independent reads and must not raise the per-sticker wrong count in any replay scenario. "Capture 1's green
  face kept" is not a valid check: it is true by construction once the veto is removed.

## Candidate ideas, not yet planned

- **Gather several views during one corner hold.** A small tilt can move glare off a sticker or bring a side into
  better light. Keep a short burst in session memory, align each visible face to a common 3×3 grid, and retain clear
  views across angles. A one-cell alignment error could assign convincing readings to the wrong sticker, so start a
  fresh group when tracking loses the pose. The hand-held burst supports this more than a plain median: readings
  change with tilt.
- **Keep the faces that read well and show it.** In the net, distinguish faces already seen, colors still uncertain
  and the corner currently requested. Prompt for a slight tilt when one side stays ambiguous.
- **Multi-frame median.** Measured: no gain on a still cube at steady exposure, small gain hand-held. Optional.
- **Hybrid per-face sticker reader (CAP-3).** As a bounded ML spike, evaluate a **hybrid per-face sticker reader** on the nine cells of already detected and unwarped faces. Give the model the face image and the existing Kotlin classifier's per-sticker candidate colors and certainty, plus prior-capture readings and known center colors where available. Prior evidence can come from earlier captures in the current scan. A bounded recent-frame buffer in memory could also provide appearance context; discard its images when the scan ends. The project currently says release and benchmark builds never persist camera frames. Existing saved color calibration is a possible cross-session color reference, but the repository does not establish a same-cube identity/profile mechanism. Persisting cube-specific model outputs or reference images would therefore be a separate privacy and architecture decision, not implied by this experiment.
  Treat the model inputs as evidence: the model should be able to use the image to correct a misleading Kotlin result, while cube-level consistency can rank or reject implausible combined readings. The standard cube scheme gives six known center colors and nine stickers of each color across the full cube, and previous captures add constraints, but these facts do not in general identify every unsampled sticker by themselves. Keep visual evidence and an explicit uncertain outcome so global constraints cannot force an unsupported confident label.
  Use manually labeled real face crops across supported devices, cubes, lighting and relevant torch/calibration conditions. Keep whole capture groups held out to avoid near-duplicate frames leaking across splits. Measure results as labeled data accumulates (a learning curve) to determine whether a useful model is possible with a practical dataset; the required data volume is not yet known. Compare hybrid and Kotlin-only results on correct colors, uncertain readings, confidently wrong labels, latency, and packaged model size. Make no production replacement decision until the held-out comparison is reviewed.
- **Judge the learner's whole scan.** Compare time to a correct reviewed cube, faces requested again, abandoned scans
  and incorrect cubes accepted, on known cubes, hand-held.

## Relation to planned stories

- **Story 10.20** (record 10.19-pipeline fixtures): now a prerequisite for the three "later" topics and for CAP-3. The
  recorder burst (commit 763f4496) belongs with it. The `lenovo-tb128xu_stla_cool-2` group is a start.
- **Story 10.21** (keep the side faces it reads well): the "keep stickers" and "calibration" topics are its content,
  with the safety conditions above.
- **Story 10.11** (raise the release flag): unchanged; follows 10.21's BETA call.
- Steps 0–4 are not covered by any planned story.
