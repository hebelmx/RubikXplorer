---
status: blocked
---

# BMad Build Auto Result

Status: blocked
Blocking condition: intent gap — PO decisions needed before code (Story 10.21, planning step)

## Unanswered questions

1. **Light-clear steadiness (action item `epic-10-deferred-10.19-light-clear-steadiness`, owner PO).** Story 10.21 AC5 says the light-clear window's steadiness rule "is settled with the PO and matches the AC", and SCP 2026-11-21 quick-scan-side-face-accuracy (PO actions) says the PO settles it in the 10.21 spec. Today `CornerHold.kt` clears the light hold (since 10.22: hides the coaching line) after 500 ms of passing frames with no steadiness check (session fix `f7b0573f`); 10.19 AC1 says "pass about 0.5 s while steady". Which rule should the hysteresis refit ship: (a) movement is ignored in the light-clear window (keep today's behavior and update nothing else), or (b) restore the restart when two cells of one face move (`46005a11` behavior)? The release device-session checklist item 7.7 for this decision is still unchecked.
2. **Calibration screen torch toggled mid-calibration (AC2).** The calibration screen confirms the six centre swatches one at a time, then saves one calibration with one torch state. If the Learner toggles the torch after confirming some swatches, what happens: (a) the swatches taken so far are cleared and calibration restarts from the first centre (the 10.20 recorder's rule), (b) the toggle is disabled once the first swatch is confirmed, or (c) something else? The story, EXPERIENCE.md and the SCP do not say.
3. **Calibration torch default when opened from the Quick Scan corner view (AC2).** AC2 says the calibration torch is "on by default". When calibration is opened from the corner view's *Calibrate colors* offer while the Learner has the Quick Scan torch off, should the calibration screen (a) start with the torch on (then the saved calibration is torch-on and, back in Quick Scan with the torch off, it mismatches and the offer returns), or (b) start with the corner view's current torch choice (on by default only when opened from Settings or the review)?

## Evidence gathered

- Story text: `_bmad-output/planning-artifacts/epics.md` Story 10.21 (AC2 calibration torch, AC5 light check refit).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` L1702-1711: light-clear steadiness item, owner PO, open.
- `_bmad-output/implementation-artifacts/checklist-release-device-session.md` L193 (item 7.7, unchecked).
- `docs/evidence/10-19/light-check-and-replay.md` L220-221 (commits `46005a11`, `f7b0573f`).
- `_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-21-quick-scan-side-face-accuracy.md` L224-226, L244.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` L221-227, L880 (one `torchOn` state per activity, default true; calibration from the corner view is a phase of the same activity).
- Fixtures for tuning exist: `Fixtures/corner/` has 23 groups and `groups.json`; Story 10.27's `label-exclusions.json` does not exist yet (10.27 in backlog), so the tuning would run without the filter and be re-run when it lands, as the story allows.

No spec was written; no code changed.
