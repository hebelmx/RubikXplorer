---
id: SPEC-quick-scan-reliability
companions:
  - concerns-and-evidence.md
  - pipeline-audit.md
  - failure-modes.md
  - fix-plan.md
sources:
  - ../../planning-artifacts/sprint-change-proposal-2026-11-21-quick-scan-front-light.md
  - ../../planning-artifacts/sprint-change-proposal-2026-11-21-quick-scan-side-face-accuracy.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Quick Scan Reliability

## Why

Quick Scan does not work well enough to use. With tablet, cube and lights all still, a corner capture takes 3–4 seconds at best, often 20 seconds, and up to 2 minutes; when both corners are captured the scan usually keeps no face and sends the learner to face-by-face capture. Bench captures and frame bursts on 2026-11-22 identified several causes in the capture gates and in the keep rule; a review the same day showed which of them are established and which are not. The owner wants it fixed now, not deferred to a later version.

## Capabilities

- **CAP-1**
  - **intent:** Learner and developer can distinguish the static aiming guide from the detected-corner grid and determine whether the detected grid tracks the cube consistently.
  - **success:** On reference-device recordings, the guide and camera crop are shown to align, and detection losses and grid movement can be correlated with the visible camera frame.
- **CAP-2**
  - **intent:** Learner and developer can tell what is delaying or preventing each Quick Scan capture.
  - **success:** A device run records elapsed time from entering the corner viewfinder to capture or fallback, with time and transitions attributed to corner detection, metering, lighting checks, sticker stability, and hold-turn checks.
- **CAP-3**
  - **intent:** The team can determine whether a hybrid LiteRT sticker reader improves Quick Scan readout by combining face-image evidence with the existing Kotlin classifier's candidate labels and certainty, earlier captures, and known center colors.
  - **success:** A held-out, labeled device-and-lighting evaluation compares the hybrid with Kotlin alone on correct classifications, uncertain readings, confidently wrong readings, inference latency, model size, and performance as training data grows before an integration decision.
- **CAP-4**
  - **intent:** A cube in view, resting or hand-held, is captured within a bounded time.
  - **success:** On recorded reference-device runs, every hold auto-captures within 10 seconds of the cube being detected, still or hand-held.
- **CAP-5**
  - **intent:** Two corner captures usually finish the scan.
  - **success:** On labelled Lenovo captures, most scans reach review with at most one sticker left for the learner, and no accepted cube carries a confidently wrong sticker.
- **CAP-6**
  - **intent:** Sticker colors are judged against the cube in hand under the current light.
  - **success:** On labelled captures, a saved calibration from another cube or light does not lower the number of stickers kept.

## Constraints

- Keep recognition offline and on-device; do not add internet permission, analytics, or crash-reporting SDKs.
- Keep camera and LiteRT code behind existing feature flags. Preserve the current Kotlin classifier until comparative evaluation supports a change.
- Camera images used as model context remain in a bounded in-memory recent-frame buffer and are discarded when the scan ends. Persistent cube-specific references or images require separate privacy and architecture review.
- Frame bursts and stored frames exist only in the debug fixture recorder. Release and benchmark builds never persist camera frames.
- Safety is per sticker: no sticker accepted without learner confirmation may be wrong in the side-face replay. Today that holds only because a face with any uncertain sticker is discarded whole.
- Kotlin-side fixes come first. Sticker reading, classification, gates and diagnostics stay in Kotlin; LiteRT stays in corner detection only.
- Work is committed locally on `audit/quick-scan` and not pushed while another agent's CI runs on the repository.

## Non-goals

- This spec does not commit to replacing Kotlin color classification with LiteRT.
- This spec does not authorize saving camera images or adding a persistent cube profile.
- Cube color counts, center colors, and prior captures may constrain or rank candidate readings; they must not be treated as proof that a sticker's color is uniquely determined without sufficient visual evidence.
- This spec does not redesign the complete Quick Scan user flow or train a new corner detector.
- This spec does not raise the release `QUICK_SCAN` flag; that stays with Stories 10.21 and 10.11.

## Success signal

On the Lenovo bench used on 2026-11-22, with everything still, a recorded Quick Scan of a scrambled cube auto-captures both corners in under 15 seconds in total and reaches review without face-by-face capture. A hand-held run of the same build meets CAP-4. The work is planned as Story 10.22, then Stories 10.20 and 10.21.

## Assumptions

- The user's reported 3–4 second to 30+ second interval begins when the cube is positioned within the static three-axis guide and ends when the app captures; this is not necessarily the same interval as viewfinder-entry-to-capture.
- The user's terms "guide" and "grid" refer respectively to the static three-axis aiming graphic and the moving detected-corner overlay.
- "Prior captures" means current-scan captures or a bounded in-memory recent-frame history by default; using cross-session cube references is a separate option that may require a product/privacy decision.
- The CAP-5 threshold (at most one sticker left) is provisional. The CAP-4 bar of 10 seconds was set by the product owner on 2026-11-22; no recorded hold has yet finished under 19 seconds, and no hand-held Quick Scan run exists.

## Open Questions

- After Story 10.21's backlit captures, should the light check, advisory since the 2026-11-22 decision, go back to blocking?
- Was hold 1 of capture 2 (11:19:49) ended by a manual Capture press?
- What changed on the bench at 11:09:56 in capture 1, when one face's light level jumped and the 52.9-second hold cleared?
- What error rates are acceptable for release?
- Were the cubes in the two 2026-11-22 recordings `stla`, the cube the saved calibration (`stla`, `cool`, front-lit) was made on?
- Do the exposure and steady findings hold on the Galaxy S24 FE? Only the Lenovo was measured.
- Inside Quick Scan itself, hand-held, which gate dominates the wait? The hand-held burst measured the recorder, not the Quick Scan flow.
- What held-out thresholds for accuracy, uncertain reads, confidently wrong reads, latency, and model size justify adding LiteRT color classification?
- Is existing saved color calibration sufficient as a persistent same-cube color reference, or would a separate cube identity/profile be needed—and is that privacy/storage change acceptable?
