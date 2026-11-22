# Fix plan

Revised after the 2026-11-22 review. Kotlin-side fixes come first: reading, gates and diagnostics stay in Kotlin, and
each fix also serves a later evaluation of any color reader. Failure modes are in [failure-modes.md](failure-modes.md).
Nothing here is implemented yet. The sprint change proposal of 2026-11-22 (`sprint-change-proposal-2026-11-22-quick-scan-reliability.md`) plans steps 0–4 and the advisory light check as Story 10.22.

## Now: Kotlin fixes that need no new labelled data

| Step | Change | Addresses | Serves | Check |
|---|---|---|---|---|
| 0 | Diagnostics and a re-measured baseline | F13, F14 | CAP-2 | A bench run and a hand-held Quick Scan run, each with a full per-hold timeline |
| 1 | Lock exposure for the hold | F1, F2, F4 | CAP-4 | Burst and Quick Scan log show one exposure level per hold; still burst 1 replays as "not settled" during its swing |
| 2 | Steady by outline movement | F6 | CAP-4 | Zero restarts on both still bursts; windows complete on two hand-held bursts; a fast-moving burst restarts |
| 3 | Turn check not anchored on a logo centre | F15, F10 | CAP-4 | Still burst 1 replayed as hold 2 does not pass the pose gate |
| 4 | Small fixes | F11 | — | Unit test for back-out then re-capture |
| 5 | Light check advisory (PO 2026-11-22) | F3, F4, F5 | CAP-4 | A hold with the check failing still captures; the coaching line shows; the capture line logs the light score |

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
5. **Light check advisory.** The coaching line stays; capture and the manual shutter are no longer held. The keep rule
   is unchanged, so a face read in poor light still goes to the Guided grid unless all nine stickers are CONFIDENT.

## Later: needs labelled captures (Story 10.20) before design

| Topic | Addresses | Serves | What must exist first |
|---|---|---|---|
| Light check: when to coach, whether to block again | F3 | CAP-4 | Labelled captures taken at failing scores, scored per sticker; cuts re-derived under the step-1 exposure policy on both devices; the cause of single-face level jumps |
| Keep stickers, not faces | F7, F8 | CAP-5 | A per-sticker safety assertion in the side-face replay, run on the existing groups first; it will fail today and that sizes the work |
| Calibrate on the cube in hand | F9, F10 | CAP-6 | The calibration origin for the 2026-11-22 captures; a defined independent second read |

- **Light check.** The product owner made it advisory on 2026-11-22. Still unmeasured: whether a hold improves
  readings, and what readings look like in the light it flagged. Any levels for the coaching line, or for a restored
  block, must come from data; the earlier 1.40 and 1.30 would miss five of the eight backlit fixture
  holds.
- **Keep stickers.** This is a new search over per-sticker color candidates with its own pruning, a cap and a "too
  many unknowns, ask the learner" branch. `ScanAssembler` today only enumerates quarter-turns of unposed faces.
  Capture 2 had 17 uncertain stickers. A cube may finish without learner confirmation only when the true color is
  known to lie within the carried candidates.
- **Calibration.** Dropping the saved calibration removes the gate's only independent read. A change here must keep
  two independent reads and must not raise the per-sticker wrong count in any replay scenario. "Capture 1's green
  face kept" is not a valid check: it is true by construction once the veto is removed.

## Planned: shadow sticker reader (Story 10.23)

Sprint change proposal `sprint-change-proposal-2026-11-22-quick-scan-sticker-reader.md`. Runs after Story 10.22,
alongside 10.20; blocks nothing.

- **Shadow-mode sticker reader (CAP-3, first step of the hybrid reader).** Run a small LiteRT per-face sticker reader
  beside the Kotlin reads without letting it decide anything. The PO lifted "LiteRT stays in corner detection only"
  for this on 2026-11-22.
  - *What it does.* In debug builds, once per capture, it reads the nine cells of each unwarped face and logs its
    color and certainty per sticker next to both Kotlin reads in `CubentiaScanDiag`. It has no input to
    `QuickScanClassification.gate`, the keep rule, `ScanAssembler` or the review, so scan results are identical with
    it on or off.
  - *Where it plugs in.* The camera side keeps the three unwarped face crops in memory at capture and passes the
    model's result through a new port, following the `CornerDetector` / `LiteRtCornerDetector` pattern in
    `app/src/camera/corner`. The domain receives only per-sticker results; crops are discarded when the scan ends.
  - *Switching.* Flags are build-time today; a debug-only runtime toggle turns the reader on and off on the device.
    Release builds do not run it.
  - *What it answers.* On labelled captures (Story 10.20) and on live debug scans: where the model and Kotlin agree,
    where each is confidently wrong, and how many UNCERTAIN stickers the model would settle, per device, cube and
    light. It runs once per capture, so inference time does not affect capture time.
  - *Later modes, each a separate decision on the per-sticker wrong count.* (a) Second read: the model replaces the
    saved calibration as the second read in the agreement gate; two independent reads must still agree (bears on
    CAP-6). (b) Rescue: the model decides stickers Kotlin left UNCERTAIN; this ends the two-reads-agree rule and needs
    its own safety evidence.
  - *Why now.* The comparison data should accumulate from the 10.20 session onward. Judgement of 2026-11-22,
    unmeasured: Story 10.22 is probably enough for capture time; the keep-rule work of Story 10.21 alone
    probably does not reach CAP-5 on shaded faces.
  - *Training data (PO 2026-11-22).* Existing `Fixtures/` and capture evidence from earlier commits; additional real
    captures if edge-case coverage is insufficient; synthetic faces targeting shaded faces, glare, and red/orange and
    white/yellow separation. Whole capture groups are held out for validation. The story starts with an inventory:
    some sources hold cell measurements without images.
  - *Model input.* The first model reads the face image only, so its read is independent of both Kotlin reads; the
    hybrid inputs of CAP-3 (Kotlin candidates, earlier captures, centers) remain a later variant.

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
- **Fine-tune the corner model on real frames.** The model was trained on synthetic renders and its real-frame check
  never ran. After Story 10.22 its outline feeds the steady gate; the outline drifted up to 14.5 px on a still cube and
  capture 1 lost the cube in 48 of 228 frames (F14). Do this only if the 10.22 per-hold timelines show cube-lost or
  steady restarts as the blocking gate. No model is proposed for the exposure, settle, steady or light gates.
- **Judge the learner's whole scan.** Compare time to a correct reviewed cube, faces requested again, abandoned scans
  and incorrect cubes accepted, on known cubes, hand-held.

## Relation to planned stories

- **Story 10.20** (record fixtures, now on the 10.22 pipeline): a prerequisite for the three "later" topics and for CAP-3. The
  recorder burst (commit 763f4496) belongs with it. The `lenovo-tb128xu_stla_cool-2` group is a start.
- **Story 10.21** (keep the side faces it reads well): the "keep stickers" and "calibration" topics are its content,
  with the safety conditions above.
- **Story 10.11** (raise the release flag): unchanged; follows 10.21's BETA call.
- **Story 10.22** (new, before 10.20): steps 0–5, with a 10-second bar per hold.
- **Story 10.23** (new, after 10.22, alongside 10.20): the shadow sticker reader above. Second-read and rescue modes
  need its held-out report and a further sprint change proposal.
