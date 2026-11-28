- source_spec: `_bmad-output/implementation-artifacts/spec-10-22-quick-scan-captures-promptly-once-the-cube-is-in-view.md`
  summary: Applying the exposure and white-balance lock options to the camera is executed by no test.
  evidence: Needs a bound camera; verify in the Story 10.22 device session with a recorder burst showing one exposure level per hold and the observed-lock diagnostics line.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-22-quick-scan-captures-promptly-once-the-cube-is-in-view.md`
  summary: A locked exposure that leaves the faces unreadable has no re-meter trigger (unverified, would be medium).
  evidence: Settle it with a device hold: `steadyRestart reason=quality` repeating under `lock=on` until the cube is lost means the hold stalled.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-22-quick-scan-captures-promptly-once-the-cube-is-in-view.md`
  summary: White balance is locked on a settle signal that follows exposure only (unverified, would be medium).
  evidence: Settle it by logging the AWB state at lock time on both devices; an unconverged state at lock confirms it.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-22-quick-scan-captures-promptly-once-the-cube-is-in-view.md`
  summary: Hand-held capture needs all three faces ACCEPTABLE; in burst handheld-2 one face is readable in 21 of 120 frames, so no steady window completes as recorded.
  evidence: Pre-existing quality gate, outside Story 10.22; it is the likely blocker for the 10 s hand-held target and needs a PO decision.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-22-quick-scan-captures-promptly-once-the-cube-is-in-view.md`
  summary: The hold-2 turn check has no recorded evidence of a really turned logo cube capturing (unverified, would be medium).
  evidence: Settle it with hold-2 timelines (poseRejected / poseAccepted / capture) from the device session on the stickered Rubik's cube.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-22-quick-scan-captures-promptly-once-the-cube-is-in-view.md`
  summary: The steady, settle and re-meter thresholds are fitted and checked on five bursts from one Lenovo.
  evidence: Re-check on Galaxy S24 FE bursts during the device session; neither re-meter bound has a recorded case that should trigger it.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-37-e9-saved-calibration-anchor.md`
  summary: The continuous session recorder stores neither the torch state nor the saved calibration of each frame, so a scan anchored by the saved calibration does not replay identically (replayContinuousSession runs the calibration-less path).
  evidence: `frames.jsonl` has no torch or calibration field; changing the session format is out of scope for E9. Add both if a calibration-anchored recording must replay.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-37-e9-saved-calibration-anchor.md`
  summary: The exported `CalibrationCenters` of a continuous scan (`ContinuousScanHandoff.calibration`) carries no torch tag. Today it is only read in memory, by the review's calibration and the Guided hand-over, and no path persists it.
  evidence: `exportHandoff` builds `CalibrationCenters` without `torchOn`. Risk only for a future save path: a calibration saved from it under a lit torch would count as torch-off and never match with the torch on.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-37-e9-saved-calibration-anchor.md`
  summary: A printed logo on a centre sticker may make the saved-calibration match fail, because every observed centre is judged and none is skipped as guarded.
  evidence: Unverified (medium if true; it fails safe). Settle with a device recording of a logo cube, comparing the logo centre's standard Lab with its saved centre against `MAX_CENTRE_DELTA_E`.
- source_spec: `_bmad-output/implementation-artifacts/spec-10-37-e9-saved-calibration-anchor.md`
  summary: No test or measurement shows that a calibration saved by the calibration screen passes the continuous scan's match, whose Lab comes from a different sampling path.
  evidence: Unverified (medium if it never passes). The tests build calibrations from the mapper's own Lab. Settle with a calibration saved on the Lenovo and a recording of the same cube under the same light.
