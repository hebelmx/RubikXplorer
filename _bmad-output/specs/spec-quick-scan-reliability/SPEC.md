---
id: SPEC-quick-scan-reliability
companions:
  - concerns-and-evidence.md
sources:
  - ../../planning-artifacts/sprint-change-proposal-2026-11-21-quick-scan-front-light.md
  - ../../planning-artifacts/sprint-change-proposal-2026-11-21-quick-scan-side-face-accuracy.md
---

# Quick Scan Reliability and Diagnosis

## Why

Quick Scan users report long and unpredictable waits after positioning a cube, a detected-corner grid that moves, uncertainty about whether the static aiming guide matches the cube, and sticker readings that vary with lighting. The current per-frame model benchmark does not explain the end-to-end wait, and current device evidence does not settle the visual or color concerns.

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

## Constraints

- Keep recognition offline and on-device; do not add internet permission, analytics, or crash-reporting SDKs.
- Keep camera and LiteRT code behind existing feature flags. Preserve the current Kotlin classifier until comparative evaluation supports a change.
- Camera images used as model context remain in a bounded in-memory recent-frame buffer and are discarded when the scan ends. Persistent cube-specific references or images require separate privacy and architecture review.

## Non-goals

- This spec does not prescribe a fix for the guide or overlay before device evidence identifies the cause.
- This spec does not commit to replacing Kotlin color classification with LiteRT.
- This spec does not authorize saving camera images or adding a persistent cube profile.
- Cube color counts, center colors, and prior captures may constrain or rank candidate readings; they must not be treated as proof that a sticker's color is uniquely determined without sufficient visual evidence.
- This spec does not redesign the complete Quick Scan user flow or train a new corner detector.

## Success signal

A reference-device session can explain the user-visible wait from viewfinder entry to capture or fallback, show whether guide alignment and detected-grid motion are acceptable, and provide a held-out comparison that supports a clear keep-or-adopt decision for LiteRT color classification.

## Assumptions

- The user's reported 3–4 second to 30+ second interval begins when the cube is positioned within the static three-axis guide and ends when the app captures; this is not necessarily the same interval as viewfinder-entry-to-capture.
- The user’s terms “guide” and “grid” refer respectively to the static three-axis aiming graphic and the moving detected-corner overlay.
- “Prior captures” means current-scan captures or a bounded in-memory recent-frame history by default; using cross-session cube references is a separate option that may require a product/privacy decision.

## Open Questions

- Which device, cube, lighting, torch, and saved-calibration conditions reproduce the longest waits and color variation?
- Does a recorded viewfinder session show preview-to-analysis coordinate mismatch, model miss/loss, overlay movement, or a downstream capture gate?
- What held-out thresholds for accuracy, uncertain reads, confidently wrong reads, latency, and model size justify adding LiteRT color classification?
- Is existing saved color calibration sufficient as a persistent same-cube color reference, or would a separate cube identity/profile be needed—and is that privacy/storage change acceptable?
