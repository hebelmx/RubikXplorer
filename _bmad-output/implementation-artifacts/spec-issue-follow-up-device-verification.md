---
title: "Verification Story: Review merged fixes for seven GitHub issues"
type: 'verification'
created: '2026-11-21'
status: 'draft'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-18-quick-scan-finds-the-cube-s-real-edges-and-corners.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-17-switch-from-quick-scan-to-the-guided-scan-and-keep-the-captured-faces.md'
  - 'GitHub issues #195, #196, #197, #198, #249, #251, #253'
deferred:
  - summary: >-
      Run this session against the final Story 10.19 build before evaluating #196;
      if the session happens earlier, mark #196 provisional and repeat its checks.
    evidence: >-
      Story 10.19 is still in progress at draft time and changes Quick Scan lighting,
      metering, calibration, and torch behavior relevant to the dark-background case.
    location: 'GitHub issue #196 and Story 10.19'
    severity: medium
---

## Intent

Several product issues were promoted into stories and the stories merged, but the issues still need a deliberate review against the original report. This story provides one focused verification session on the real app and reference devices, then records a separate evidence-based disposition for every issue.

**Story:** As the product owner, I want to replay each issue's original failure and acceptance expectations on the shipped app paths, so that I can close only the issues the merged stories fully resolved and keep any partial or failed issue open with a concrete next step.

This is a verification and issue-disposition story. It does not assume that merge means resolution. If a reproducible defect remains, capture the failure and its scope; do not mark that issue resolved by this story.

## Linked issues and merged work

| Issue | Original concern | Story / merged PR | Verification needed |
|---|---|---|---|
| [#195](https://github.com/hebelmx/RubikXplorer/issues/195) | The isometric corner guide does not fit a real camera view; corners/faces need to follow the cube's perspective. | Story 10.18, [PR #266](https://github.com/hebelmx/RubikXplorer/pull/266), developed and merged 2026-11-21. | Live camera fit and face alignment on both reference devices. |
| [#196](https://github.com/hebelmx/RubikXplorer/issues/196) | Corner detection fails on dark glossy surfaces/reflections; detection or clear coaching is expected. | Story 10.18, [PR #266](https://github.com/hebelmx/RubikXplorer/pull/266), developed and merged 2026-11-21; Story 10.19 adds related lighting behavior. | Dark-glossy failure/recovery on-device against the final 10.19 build. |
| [#197](https://github.com/hebelmx/RubikXplorer/issues/197) | Capture feedback was unclear, especially on the Lenovo without a vibration motor. | Story 10.18, [PR #266](https://github.com/hebelmx/RubikXplorer/pull/266), developed and merged 2026-11-21. | Live visual/audio feedback for both corner captures and fallback. |
| [#198](https://github.com/hebelmx/RubikXplorer/issues/198) | Switch from Quick Scan to Guided Scan while retaining captured faces. | Story 10.17, [PR #269](https://github.com/hebelmx/RubikXplorer/pull/269), developed and merged 2026-11-21. | Repeat the full handoff and return paths; S24 FE was not checked in PR #269. |
| [#249](https://github.com/hebelmx/RubikXplorer/issues/249) | Correct a face efficiently with paint mode and accessible state announcements. | Scan review paint-mode story, [PR #263](https://github.com/hebelmx/RubikXplorer/pull/263), developed and merged 2026-11-21. | Touch and TalkBack behavior on-device; review was not done on a reference device. |
| [#251](https://github.com/hebelmx/RubikXplorer/issues/251) | Explain what Placement read and why it recommends a Stage, including edge cases. | Placement explanation story, [PR #265](https://github.com/hebelmx/RubikXplorer/pull/265), developed and merged 2026-11-21. | Main-app flow, outcome copy, and TalkBack on reference devices; PR #265 did not include a physical-device check. |
| [#253](https://github.com/hebelmx/RubikXplorer/issues/253) | Accept uniquely resolvable face rotations; explain ambiguous inputs and avoid premature errors. | Cube-editor orientation story, [PR #258](https://github.com/hebelmx/RubikXplorer/pull/258), developed and merged 2026-11-21. | Device smoke for rotated input, editor guidance, TalkBack, and large text; algorithmic breadth remains covered by tests. |

## Boundaries and constraints

- Use the main Cubentia app build and its real learner entry points. Never use a debug-only launcher as the acceptance path.
- Run the Quick Scan cases with the camera feature flags enabled only in the existing debug configuration. Do not change release flags or build gates.
- Run camera cases on the Lenovo TB128XU (HA1S28MM) and Galaxy S24 FE. If a device is unavailable, record that issue as not fully verified.
- Use both stickered cube `stka` and stickerless cube `stla` where a camera case calls for a cube. Record surface, lighting, distance, cube, device, app revision, and feature-flag state for every run.
- Do not store or send camera frames through the app. Keep only the minimum session notes, test results, screenshots of UI states when needed, and diagnostic logs that contain no personal data.
- Keep each GitHub issue open unless its original report and all applicable follow-up comments are fully verified. A story merge, passing JVM tests, or one passing device run is not sufficient by itself.
- If an issue is only partly resolved, leave it open and write the remaining behavior and evidence in its disposition. Do not silently widen this verification story into unrelated redesign work.

## Acceptance criteria

1. **Issue-by-issue evidence:** Every linked issue has a dated result for each relevant device and test case, with the build revision, setup, observed result, and evidence location.
2. **Reproducible pass/fail:** A pass is recorded only when the original issue behavior is exercised and its expected outcome is observed. A failed, skipped, or unavailable check is not reported as a pass.
3. **Closure is selective:** Close only an issue whose original problem and material follow-up comments are fully addressed by static evidence and/or the required live checks below. Keep all partial, failed, or untested issues open with a precise next action.
4. **Static evidence is cited:** For claims that can be settled without hardware, link the relevant merged PR tests or implementation evidence in the issue disposition. Device results supplement rather than replace those checks.
5. **No planning edits:** Record session evidence in the issue threads or a dated evidence note; do not change the final PRD, architecture spine, or epics document as part of this story.

## Session playbook

### 0. Prepare the session

1. Wait until the candidate build contains merged Stories 10.17, 10.18, and 10.19. Story 10.19 must be present before the final #196 result because it changes lighting guidance and metering. If #10.19 is not yet included, test #196 only as provisional and repeat it later.
2. Install the current main-app debug build on the Lenovo TB128XU and Galaxy S24 FE. Record app commit SHA, Android version, device model, and whether each feature flag is on.
3. Confirm entry through the regular app: Solve → Quick Scan for scan checks, and the regular Enter colors / Start from my cube route for editor and Placement checks. Stop and record blocked if any target flow is reachable only from a debug launcher.
4. Prepare `stka` and `stla`, one plain light matte surface, and one dark glossy surface with a visible reflection. Test in stable indoor light. The Lenovo has no flash; on the S24 FE also record whether the torch control appears and can be used.
5. Enable TalkBack for the accessibility passes. Have a known solved state, a fully mixed state, a one-turn-from-solved state, a two-turn-from-solved state, a non-white-cross/F2L state, and a last-layer state ready. Prefer the deterministic states and move sequences already used by the merged Placement tests; record the move sequence used for each physical cube state.
6. Use the checklist below in order. Record outcomes immediately, including unexpected reads, corrections, timeouts, and any fallback. Do not infer a pass from a screen that merely looks plausible.

### 1. #195 — Perspective fit and corner alignment

On each device, open Quick Scan from Solve and place `stka`, then `stla`, at roughly 20–40 cm on the plain matte surface.

1. Hold the cube at normal use distance and vary pitch, yaw, and roll within comfortable hand positions; include a near view and a more oblique view.
2. Watch the detected outline against the actual cube silhouette. Confirm it tracks the real visible corners instead of presenting a fixed symmetric isometric hexagon that the cube cannot fit.
3. Hold until capture, then inspect the review for face alignment: sticker grids should follow the three visible faces, with no systematic shift caused by a mismatch between guide geometry and unwarp geometry.
4. Repeat enough holds to distinguish a stable fit from a lucky frame. Record each fallback, uncertain face, rescan, or outline jump.

**Pass:** The real-corner outline tracks the cube across the tested orientations, and the same detected geometry yields aligned face reads without repeated perspective-driven fallback. Save representative UI screenshots and diagnostic counts, not camera frames.

**Close #195 only if:** Both reference devices pass, or any device-specific limitation is explicitly shown not to reproduce the issue and accepted by the PO. Otherwise leave open with the failing device/orientation and evidence.

### 2. #196 — Dark glossy surface and recovery

Run after Story 10.19 is in the build. On each device, compare the same cube and distance on the plain matte surface and dark glossy reflective surface.

1. On the matte surface, confirm the camera can find the cube and reach a normal capture.
2. Move to the dark glossy surface with its reflection visible. Hold the cube aligned and watch whether corner detection locks onto the cube, locks onto the reflection, or withholds capture.
3. If the app withholds capture, wait for its lighting/background coaching. Confirm the message is clear and does not imply the cube was captured. Follow the coaching by moving to the plain matte surface and confirm the same scan can recover without restarting or losing unrelated accepted faces.
4. Repeat with the second cube and, on the S24 FE, exercise the available torch path and its glare recovery if the final build exposes it. On the Lenovo, record torch as unavailable rather than treating absence as failure.

**Pass:** The reflection is never accepted as a cube; the app either detects the cube robustly or gives the promised actionable coaching, and the learner can recover by changing the surface. Record lock/fallback outcomes and coaching delay for every run.

**Close #196 only if:** Both devices meet the detection-or-coaching expectation on the tested cube/surface combinations, including recovery. If the issue only passes through coaching on some cases, record that accurately and ask whether that satisfies the issue's expected behavior before closing.

### 3. #197 — Feedback on both corner captures

On both devices, run Quick Scan from the main app with sound enabled. On the Lenovo, do not count vibration as the capture signal.

1. Capture corner 1. Confirm an immediate visible flash, thumbnail/capture indication, and counter change. Confirm the shutter sound plays.
2. Capture corner 2. Confirm the same visible and audible signals; this includes the path where the second capture immediately hands over to Guided fallback.
3. Repeat once with TalkBack enabled and confirm capture progress is announced clearly without relying on the thumbnail alone. If the device has no vibration motor, the visual and sound signals must still make the capture unmistakable.

**Pass:** Both captures are unmistakable on both devices, including capture 2 on the fallback path. Record each signal independently (flash, thumbnail, counter, sound, announcement); a pass on one signal does not stand in for missing others.

**Close #197 only if:** The reported uncertainty about whether a capture happened is eliminated on both devices, especially the Lenovo.

### 4. #198 — Switch to Guided and retain captured faces

On each device, exercise the manual and automatic handoffs.

1. Start Quick Scan and use *Scan face by face* before the first corner is captured. Confirm Guided starts at the first face, Back returns to the original corner hold, and no face is falsely marked captured.
2. Capture the first corner and switch at hold 2. Confirm the corner faces judged as kept remain visible on the mini net and Guided asks only for the remaining faces in fixed order with `Face n of N` progress.
3. Capture the second corner and exercise the automatic handoff. Confirm it uses the same Guided flow and keeps the accepted corner faces.
4. In each handoff, capture at least one Guided face. Back out and confirm the return target is the hold where the handoff began (hold 2 after both corners), the kept corner faces remain, and newly captured Guided faces are discarded as specified.
5. Re-enter Guided and complete a combined scan. Confirm re-capturing a corner fills only faces not already kept; kept faces do not change. In combined review, retake one face and confirm the retake is reflected.
6. Close or press Back with captured faces present. Confirm *Discard this scan?* appears; Cancel preserves the scan, while Discard exits. Accept a valid completed scan and confirm the app continues to the expected result flow.

**Pass:** The full sequence works with no lost kept face, no silently retained Guided face after Back, and no path that discards captures without confirmation. Record each entry path and hold number separately.

**Close #198 only if:** Both devices pass the handoff, Back, fill-only retake, discard/cancel, and combined-review paths. PR #269's Lenovo-only session does not cover the S24 FE.

### 5. #249 — Paint mode on scan review

On each device, enter Scan review with a cube state that exposes a face with multiple incorrect or blank stickers. Keep TalkBack enabled for at least one full pass per device.

1. Long-press a palette color. Confirm a visible locked state (outline/check or equivalent) and a clear `Painting {color}` announcement.
2. Tap all nine positions on the target face, including the center position. Confirm editable stickers take the locked color, the fixed center remains correct, and the update cue is visible; the long-press plus nine taps should complete the face correction.
3. Tap the locked swatch and repeat with a second color, ending paint mode with the documented exit action. Confirm TalkBack announces `Painting off` and the lock indicator clears.
4. With paint mode off, tap a different palette color and confirm the original one-sticker-at-a-time behavior remains unchanged.
5. Repeat with large text (200%) on the compact phone layout and tablet layout. Confirm the locked-state badge/check and color-blind glyph remain distinguishable and the controls remain reachable.

**Pass:** Paint mode is discoverable, the locked color is obvious, corrections land on the intended stickers, TalkBack announces entry/exit, normal taps remain unchanged, and the screen remains usable at large text.

**Close #249 only if:** The one-long-press + nine-tap path and touch/TalkBack behavior pass on both device classes. If a specific lower-priority deferred layout or duplicate action issue remains, leave the issue open or split it only after recording the residual.

### 6. #251 — Placement reading, reason, suggestion, and TalkBack order

Use the main-app *Start from my cube* / Placement route. When scan recognition would make a state unreliable, enter the exact prepared state through the supported Enter colors path and continue to Placement; record which input path was used.

Exercise and record these distinct states:

1. Fully mixed cube → reading says nothing is solved yet and the suggested starting Stage has a reason.
2. One and two turns from solved (include a half turn) → names the turn(s) in understandable order and offers no misleading Stage start when the cube is almost solved.
3. Non-white cross and F2L solved from yellow → gives credit for the solved work, explains the course's white-cross convention, and offers *Show me the last layer anyway* when eligible.
4. Partial first layer with misplaced pieces → explains the reading and count/reason for the next Stage.
5. Last-layer state with a uniquely known Case → names the Case/next action where supported and explains the suggested Stage.
6. Solved cube → states that the cube is solved and does not recommend an unnecessary Stage.

For each outcome, listen with TalkBack from the beginning of the result screen. Confirm the sequence is cube reading → reason → suggestion/action → set-up line, with no contradictory or generic-only output. Confirm the learner can activate the intended action and can choose another Stage without losing the reading.

**Pass:** Every prepared state gets the expected reading and a reason consistent with the suggested Stage; TalkBack reads all content in the intended order; actions match the visible suggestion.

**Close #251 only if:** All six cases pass on the main app, including the one/two-turn and non-white cases raised in the issue comments, and TalkBack works on both reference devices. A JVM/Robolectric pass alone is insufficient for this issue's outstanding device gate.

### 7. #253 — Editor orientation, incomplete state, and guidance

Use the main-app Enter colors / Manual Cube Editor path on both devices. Use solved and known scrambled cube states; enable TalkBack for one full pass and test 200% text on the phone and tablet.

1. Open a fresh editor. Confirm incomplete faces are described as remaining input, not as an invalid cube; count of faces left is accurate and Accept is unavailable until the entry is complete.
2. Enter a uniquely resolvable cube with one face turned 180 degrees from the conventional orientation. Confirm the editor resolves it without forcing a manual orientation question and yields the correct cube. Repeat 90° and 270° using the prepared rotated-face test state; static tests remain the exhaustive check for all-face combinations.
3. Enter the known ambiguous and unresolvable test fixtures used by `ScanAssemblerTest` / editor tests. Confirm the UI identifies the involved face, shows the neighbor-center hold bands/orientation guidance, and TalkBack announces the hint. If a fixture cannot be entered practically on-device, record that UI case as blocked and cite the passing deterministic test; do not claim a live pass.
4. Enter a wrong-color-count and an impossible-piece case. Confirm the error says which colors/pieces are wrong, marks the relevant face/tab, and does not collapse to a generic message.
5. Enter a face in an out-of-order sequence. Confirm the next-sticker cursor and face-complete cue are visible, and TalkBack announces auto-advance and face completion.
6. At 200% text, verify compact phone and tablet layouts keep face tabs, warning state, neighbor-color guide, and acceptance controls visible and usable.

**Pass:** Unique rotations are accepted correctly; only unresolved/ambiguous states ask for orientation; no premature errors obscure fresh input; errors and advance cues are specific and accessible; large-text layouts remain usable.

**Close #253 only if:** The physical UI/TalkBack smoke passes and the existing deterministic rotation/ambiguity tests cover the broader state space. Leave open if any issue-thread item (orientation, premature error, generic error, auto-advance cue, or accessibility announcement) remains.

## Session record and issue disposition

Create a dated evidence note under `docs/evidence/issue-follow-up/` with one section per issue and this record for each device/case:

```text
Issue:
Device / Android:
App revision / build variant / flags:
Cube / state or move sequence:
Surface / lighting / distance (camera cases):
TalkBack / text scale:
Steps completed:
Expected:
Observed:
Result: PASS | FAIL | BLOCKED | NOT RUN
Evidence: screenshot/log/test reference (no retained camera frames)
Issue disposition: CLOSE | KEEP OPEN | FOLLOW-UP NEEDED
Reason and remaining work:
```

After the session, add a concise evidence-backed comment to each issue. Close only issues marked `CLOSE` after the PO reviews the recorded evidence. For `KEEP OPEN` or `FOLLOW-UP NEEDED`, state the exact failed criterion and link the evidence or next story. Do not close an issue if any required device, accessibility, or scenario check is missing.

## Verification sources (static)

The device session complements these existing merged checks; it does not need to recreate their full state-space coverage:

- #195–#197: Story 10.18 / PR #266 tests and recorded Lenovo D1/D3 device evidence; #196 side-face performance bar is explicitly assigned to Story 10.19.
- #198: Story 10.17 / PR #269 unit, Robolectric, route, large-font, and Lenovo device checks; S24 FE remains to verify.
- #249: PR #263 paint-mode state, review, editor, TalkBack/a11y, and large-text tests; device TalkBack remains.
- #251: PR #265 Placement/JVM/Robolectric scenarios; the PR explicitly says physical-device TalkBack verification was not run.
- #253: PR #258 orientation, incomplete-entry, error-detail, auto-advance, TalkBack, and layout tests.

## Completion rule

The story is done when every applicable test has a dated result, every linked issue has an evidence-backed disposition, and each fully solved issue is closed only after PO review. A failed or blocked case does not prevent completing the verification record, but it prevents closing that issue and requires a clear follow-up.
