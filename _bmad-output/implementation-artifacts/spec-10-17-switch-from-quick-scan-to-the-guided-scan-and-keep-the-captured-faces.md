---
title: 'Story 10.17: Switch from Quick Scan to the Guided Scan and keep the captured faces'
type: 'feature'
created: '2026-11-21'
status: 'done'
baseline_revision: '03c9c235a3c95a2996d45c4514dcd7376701e881'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md'
warnings: ['oversized']
deferred:
  - summary: >-
      No test taps Scan face by face during the 400 ms corner capture freeze.
    evidence: |-
      switchToFaceByFace() uses scan.holdTransition(next) so the hand-over waits for endFreeze; every route tap happens after the freeze ended. holdTransition/endFreeze publishing is covered by captureTwoFallbackPlaysCaptureFlashAndCounterCueBeforeEnteringGuidedGrid. Check it on device in the 10.17 session.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt (switchToFaceByFace)
    severity: low
---

<intent-contract>

## Intent

**Problem:** In Quick Scan (`QUICK_SCAN` BETA/GA) one bad corner photo costs the faces already read: there is no *Scan face by face* switch, Close/Back in the corner or fallback viewfinder finishes `Cancelled` and loses every captured face, Quick Scan's own uncertain-face hand-over re-asks faces with a named top and a second re-prompt round, its final assembly re-classifies every face (`ScanReview.fromSamples`), and retake is disabled in the Quick Scan review.

**Approach:** One hand-over path for both entries (the *Scan face by face* tap and the automatic post-capture hand-over): keep the captured faces that pass the hand-over rule, open a Guided viewfinder run (like the Story 10.16 retake run) for only the other faces in `GuidedScanPlan.steps` order with the any-rotation prompt, then assemble kept + new in `ScanAssembler` with the kept poses held (one quarter-turn search per new face, best fit to review, no re-prompt), and open the Story 10.3 review with retake available.

## Boundaries & Constraints

**Binding ADs (from the story's Refs, verbatim):** AD-3, AD-9, AD-13, AD-18.

**Always:**
- AD-9: `ScanAssembler` stays the only place samples are rotated into `Facelets` order; kept faces are placed at their captured (named-top) pose and classified exactly once, never re-classified or turned; each new face tries its four quarter turns.
- AD-3: the scan sub-flow returns `ScanOutcome` through `ScanFlowContract` and never touches session state; *Accept* in the combined review returns the same `ScanOutcome.Accepted` as today.
- AD-13: frames stay in memory only; no frame persisted, logged in release or sent; no new permission or SDK.
- AD-18: release flags unchanged (all camera flags OFF in release in `FeatureFlagLevels`); debug keeps `QUICK_SCAN` raised and Quick Scan reachable from the Solve tab (`solve:quickScan`) on the main app build.
- Kept face = a captured corner face that the existing `needGrid` rule does not send to the grid (present, ACCEPTABLE, and every judged reading CONFIDENT when a calibration exists) — the same rule for the switch and the automatic hand-over, matching EXPERIENCE "captured with confidence" and AC "all nine stickers read" (a corner face is stored only with 9 cells).
- Automatic hand-over (Story 11.1 AC2) after two captures: faces = `needGrid`, else the faces `repromptFaces` names for an invalid/ambiguous merge, else straight to review; it runs the shared path once; no round-2 re-prompt and no named-top prompt.
- All new Learner copy in `core/domain/src/main/resources/content/strings/en.json` (NFR-10); fits a 411 dp portrait phone and 200 % font without scrolling or clipping.
- *Enter colors instead* stays available on every viewfinder state.

**Never:**
- Never re-prompt after the combined assembly (ambiguous / no valid state → review with best fit, tied differing stickers blank, as Story 10.16).
- Never put camera code in `app/src/main` or rotation code outside `ScanAssembler`; never edit planning artifacts.
- Torch, light gate and side-face light coaching (Story 10.19) are out of scope.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Switch before capture 1 | Corner view hold 1, nothing captured | Guided viewfinder asks all six faces (G,R,B,O,W,Y), any rotation; mini net empty | — |
| Switch between captures | Hold 1 captured (W,G,R kept) | Guided viewfinder asks only B,O,Y in fixed order; mini net shows W,G,R done | — |
| Automatic hand-over | Both captures, R uncertain | Guided viewfinder asks only R, any rotation, no "Again" counter | — |
| Combined assembly, one fit | kept + new samples | `ScanReview` Full from `ScanAssembler`; kept poses held; review opens | — |
| Combined assembly, ambiguous / no fit | new face fits 0 or ≥2 turns | review with best fit, differing stickers blank, no re-prompt | Validation messages as Story 10.3 |
| Combined review | review after either entry | *Retake faces* (Story 10.16) available; *Accept* → `ScanOutcome.Accepted` | — |
| Close with kept faces | Close in corner view or switched Guided viewfinder with ≥1 kept face | asks "Discard this scan?" (existing dialog copy); confirm → `Cancelled` | Cancel keeps the scan |
| Close with nothing kept | no face kept | finishes `Cancelled` at once (unchanged) | — |
| Back from Guided before all remaining faces | Hand-over viewfinder; started by the switch at hold h (1 or 2), or after both corner captures (automatic hand-over or switch after capture 2) | Quick Scan corner view at hold h, or at hold 2 when both corners were captured; kept faces intact on the mini net; faces captured in the Guided run are dropped (Story 10.16 all-or-nothing) (PO Q1) | — |
| Re-capture after resume | Corner capture at the resumed hold | New reads fill only faces not yet kept; kept faces stay as they are (PO Q2) | Review retake (Story 10.16) fixes a kept face |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` -- `QuickScanEffect.Fallback(faces, round)` (L20); `QuickScanController` (L54): `samples` (L57), `gridFaces`, `previous` (turned-over guard), `hold`, `fallback`, `fallbackFaces`, `repromptFaces`; `onShutter` (L97, stores a face only with 9 cells, then `needGrid` → `startFallback(…,1)` or `validate()`), `onGuidedEffects` (L127, round-2 trigger), `oriented` (L140), `involvedFaces`/`validate` (L150-159), `startFallback` (L161: named-top `GuidedScanPlan::reprompt` plan, `allowAdditionalReprompts`, `known`, `initialDelayMs = 2500` for round 1, re-prompt callback), `needGrid` (L188, the kept-face rule), `finish` (L239, `oriented`).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `GuidedScanPlan.steps` (L24-26, fixed G,R,B,O,W,Y, no named top = any rotation); `GuidedScanController` internal ctor (L345: `plan`, `allowAdditionalReprompts`, `reprompt`, `known` = faces read elsewhere a capture must not repeat, `initialDelayMs`); `planSize`, `stepIndex`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- `assemble` (L291) → `analyze` (L386) → `place` (L463: a sample naming `upNeighborColor` is *posed*, one fixed option, classified once) → `search` (L504: posed faces fixed, unposed faces 4 quarter turns; not-resolved → stickers of differing faces UNCERTAIN; result is always the first valid / best-fit `ScanResult.Full`, the branch only feeds `repromptFaces`). Kept corner samples are posed (orientation 0, named top), Guided any-rotation samples are unposed, so `assemble` on kept + new already holds kept poses and searches each new face — the combined assembly. No new assembler entry is needed; `repromptFaces`/`trace` are no longer called by Quick Scan.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- `fromSamples` (L177): one `SelfCalibration.from(samples, saved)` + one `ScanAssembler.assemble`; `from` (L67) blanks non-CONFIDENT stickers. Reused unchanged for the combined review.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `phaseOf(quick)` (L220: today `retake = true` and an "Again" `RepromptProgress`), `reviewOf` (L235), retake helpers (L250-290) and `RetakeRun` (L317) as the pattern for the hand-over viewfinder.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `GuidedScanActions` built in `onCreate` (L162-233: `onClose` handles retake back-out, else `finishWith(Cancelled)`); `BackHandler`s (L240-247); review `onRetake` gated by `scan.isGuidedRun && !isQuickCheck` (L314-315); `finishWith` (L376, Quick Scan diagnostics use `fallbackFaces`/`repromptFaces` sizes); `analyze` (L433: `quick.inCorner` → `captureCorner`); `captureCorner` (L523: freeze, `holdTransition` to a non-corner phase); `quickReview` (L554); `handle` (L569: `quick.onGuidedEffects`, publishes `phaseOf(quick)` when `fromReview == null`); `GuidedScanViewModel` (L687): `quickScan`, `isGuidedRun` (L713), `holdTransition`, `publish`, `backOutOfRetake` (L887: clears `held`/`frozen`), `endFreeze`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanPhase.CornerViewfinder` (L116) / `Viewfinder` (L136, `kept`, `fromReview`); `GuidedScanActions` (L166); top bar Close `scanClose` (L219); `Viewfinder` composable (L372: portrait column = MiniNet weight(1f), `CaptureButtons`, `EnterColorsButton`); `progressText` (L576); `MiniNet` (L708, kept + captured shown as done); `EnterColorsButton` (L799, OutlinedButton, 48 dp, tag `scanEnterColors`).
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- discard `AlertDialog` (L470-495: `msg.scan.discard-title`, `btn.scan.discard`, `btn.scan.cancel`) is the pattern to mirror; read-only.
- `core/domain/src/main/resources/content/strings/en.json` -- Quick Scan keys L21-24; scan keys L704, L750-757.
- `build-logic/.../policy/FeatureFlagLevels.kt` (L57 `QUICK_SCAN` BETA in debug) and `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` (L78 `solve:quickScan`) -- already satisfy AD-18; read-only.
- Tests: `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/{QuickScanControllerTest,ScanAssemblerTest,ScanReviewTest}.kt`; `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` (L222-230: expects "Again: face 1 of 1" and no `review:retakeFaces` — both flip), `app/src/testDebug/kotlin/dev/cubentia/app/camera/{QuickScanScreenTest,GuidedScanFlowTest,GuidedScanScreenTest}.kt` (Robolectric + Hilt, `w411dp-h891dp`).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` -- Replace the two-round fallback with one hand-over: (a) `keptFaces: Set<Face>` — the faces a hand-over kept; once kept a face stays kept until the scan ends (empty before the first hand-over); (b) `switchToFaceByFace(): QuickScanEffect?` — valid while in the corner view (hold 1 or 2, also during the capture freeze); keeps `captured` faces that `needGrid` passes (plus already kept faces), drops the other corner samples, records `resumeHold = hold`, and starts the hand-over; returns null (no-op) when a hand-over is already running or the scan is done; (c) automatic hand-over in `onShutter` after hold 2: faces = `needGrid(captured, Face.entries - keptFaces)`, else `ScanAssembler.repromptFaces(captured, calibration, repromptPosed = true)` minus `keptFaces` (faces kept by an earlier hand-over are never re-asked), else `Done(captured)`; every captured face not handed over becomes kept; it records `resumeHold = 2`; (d) the hand-over: `GuidedScanController(plan = GuidedScanPlan.steps.filter { it.face !in kept }, allowAdditionalReprompts = false, reprompt = { emptyList() }, known = kept samples, initialDelayMs = 2500)` for both entries; Guided captures go to a separate `added` map; its Done → `QuickScanEffect.Done(kept samples + added samples)` (no `oriented`, no re-prompt); expose `kept: List<FaceSample>` and `added: List<FaceSample>`; (e) `backOut()`: while a hand-over is active or just done but not yet reviewed, drop `fallback` and `added`, set `hold = resumeHold`, `corner.reset()`, keep `previous` and kept samples; (f) `onShutter` fill-only: a face already in `keptFaces` is never overwritten. Remove `oriented`, the round-2 path and `Fallback.round`; keep `fallbackFaces`/`repromptFaces` populated for diagnostics. -- AC1, AC3, AC4, PO Q1/Q2.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `phaseOf(quick)`: corner → `CornerViewfinder(hold, captured, coaching, faceByFace = true)`; hand-over → `phaseOf(grid).copy(kept = quick.kept, captured = quick.added, again = null, retake = false, handOver = true)` (progress "Face n of N" over the asked faces). -- AC1.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- Add `faceByFace: Boolean = false` to `CornerViewfinder` and `handOver: Boolean = false` to `Viewfinder`; add `onFaceByFace` to `GuidedScanActions`; when `faceByFace`, render an `OutlinedButton` "Scan face by face" (`fillMaxWidth`, `heightIn(min = 48.dp)`, tag `quickScan:faceByFace`) directly above *Enter colors instead*, in portrait and wide layouts; add a `confirmDiscard: Boolean` screen parameter plus `onDiscardConfirm`/`onDiscardCancel` actions rendering an `AlertDialog` (title `msg.scan.discard-title`, confirm `btn.scan.discard` tag `scan:discard:confirm`, dismiss `btn.scan.cancel` tag `scan:discard:cancel`, dialog tag `scan:discard`). -- AC1, AC4, AC6.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `onFaceByFace`: on `analyzerExecutor`, `quick.switchToFaceByFace()`; on main, move to `phaseOf(quick)` via `holdTransition` when frozen (the held phase replaces a held corner phase); Back (system `BackHandler`) in a `handOver` viewfinder → `scan.backOutOfHandOver()` (new VM method: clears `held`/`frozen`, runs `quick.backOut()` on the analyzer thread, then sets `phaseOf(quick)`); Close in the corner view or a `handOver` viewfinder, and system Back in the corner view → when `quick.kept + quick.added` (or captured corner faces) is non-empty set `confirmDiscard` (VM state), else `finishWith(Cancelled)`; confirm → `finishWith(Cancelled)`, cancel → dismiss; review `onRetake` enabled when `(scan.isGuidedRun || scan.quickScanMode) && !isQuickCheck`. -- AC4, AC5.
- `core/domain/src/main/resources/content/strings/en.json` -- add `"btn.quick-scan.face-by-face": "Scan face by face"` next to the Quick Scan keys. -- AC6, NFR-10.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanControllerTest.kt` -- cover: switch at hold 1 (nothing captured → all six asked in G,R,B,O,W,Y, no named top); switch at hold 2 (kept W,G,R → B,O,Y); automatic hand-over (uncertain face → only it, no round 2, any rotation); invalid-merge hand-over via `repromptFaces`; Done = kept + added with kept samples unchanged (pose and stickers); `backOut` at hold 1, hold 2 and after both captures (→ hold 2, kept intact, added dropped); fill-only re-capture; switch no-op once the hand-over runs. Replace the tests that assert the round-2 re-prompt (old L189, L270) with the single-hand-over behavior. -- I/O matrix.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanReviewTest.kt` (or `ScanAssemblerTest.kt`) -- combined assembly via `ScanReview.fromSamples(kept + added)`: one fit → full review, kept faces' colors equal their posed placement; a new face fitting 2 turns → its differing stickers blank, kept faces unchanged; no fit → best fit, no exception. -- AC2.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/QuickScanScreenTest.kt` and `GuidedScanFlowTest.kt` -- *Scan face by face* shown at hold 1 and hold 2 (not on the Guided viewfinder or Case capture), hand-over viewfinder progress and mini net kept faces, Back → corner hold, Close dialog with and without kept faces, button + Enter colors visible without scrolling at `w411dp-h891dp` and font scale 2.0. -- I/O matrix, AC6.
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- uncertain path now shows "Face 1 of 1"; `review:retakeFaces` exists in the Quick Scan review; add a route from `solve:quickScan`: capture hold 1, tap `quickScan:faceByFace`, capture the 3 remaining faces, *Accept* → `ScanOutcome.Accepted`. -- AC1, AC5.

**Acceptance Criteria:**
- Given `QUICK_SCAN` raised in debug and Quick Scan opened from `solve:quickScan`, when the corner view shows hold 1, hold 2 or a capture freeze, then *Scan face by face* is visible; tapping it opens the Guided viewfinder asking only the non-kept faces in `GuidedScanPlan.steps` order with "Show the <color> center." prompts and "Face n of N" progress, the mini net showing kept faces filled.
- Given the hand-over finishes, when the review renders, then it is the Story 10.3 review from one `ScanAssembler.assemble` over kept + new samples (kept poses fixed, each new face searched over four turns), with *Retake faces* available and *Accept* returning `ScanOutcome.Accepted` to the Solve flow.
- Given two corner captures with an uncertain face, when Quick Scan hands over, then the same hand-over viewfinder opens (no "Again" counter, no named top, no second round).
- Given the release build, when it is assembled, then `FeatureFlagLevels` still keeps every camera flag OFF and the gates stay green.

## Spec Change Log

- 2026-11-21 (planning resume): PO answered Q1 and Q2. The intent-contract matrix row "Back from Guided before all remaining faces" (was UNSETTLED) now carries the PO's answer, and a "Re-capture after resume" row records Q2 (fill only). Avoids: an unready spec with a placeholder row. KEEP: every other intent-contract line verbatim from the draft.

## Review Triage Log

### 2026-11-21 — Review pass
- verdicts: 31 findings — high 0, medium 1, low 13, false 11, maybe-false 0 (plus 6 duplicate coverage rows grouped below)
- findings:
  - Intent alignment
    - `[false]` `[reject]` Kept faces are classified again by `ScanReview.fromSamples` against the merged calibration (AD-9 "never reclassifies") — the keep decision (`needGrid`) yields no colors; each kept face's colors are produced once, by `ScanAssembler.place` in the one combined `assemble`, at its captured pose (spec Design Notes). No kept-face reading is ever replaced. With no saved calibration at hold 1 there is nothing to classify at keep time, so the other reading cannot be applied there.
    - `[low]` `[patch]` Close inside the hand-over viewfinder is not exercised at the activity surface (same entry as the verification-gap Close finding) — route test added.
    - `[low]` `[patch]` No retake runs from a Quick Scan review (same entry as the verification-gap retake finding) — route test added.
    - `[low]` `[defer]` The switch during a capture freeze is untested (same entry as the verification-gap freeze finding).
    - `[low]` `[reject]` The invalid-merge hand-over and Back after both captures are tested only in the domain — the activity wiring is the same path the uncertain-face route test and the hold-1 back-out route test exercise; adding more route tests is more than a direct fix.
    - `[low]` `[patch]` The large-font test checks only the hold-1 corner view — a hand-over viewfinder case was added.
    - `[low]` `[reject]` The "switch before capture 1" case is tested only in the domain — the phase mapping is covered by `GuidedScanFlowTest`; everyday risk is low.
  - Verification gap
    - `[low]` `[patch]` Close in the hand-over viewfinder is never driven through `closeOrConfirm` — `QuickScanRouteTest` now taps `scanClose` in the hand-over, asserts `scan:discard` shows and the activity stays open, and asserts Cancel keeps "Face 1 of 3".
    - `[low]` `[patch]` *Retake faces* in the Quick Scan review is only asserted to exist — the route test now retakes F from the combined review and Accept still decodes to `Accepted(SOLVED)`.
    - `[low]` `[defer]` A *Scan face by face* tap during the 400 ms corner freeze is untested — deferred, as the layer filed it: `holdTransition`/`endFreeze` are already covered by `captureTwoFallbackPlaysCaptureFlashAndCounterCueBeforeEnteringGuidedGrid`; recheck at the device session.
  - Blind Hunter
    - `[low]` `[reject]` The switch changes the controller before the main-thread `CornerViewfinder` guard, so it could get stuck — that needs the phase to leave the corner view within one executor hop. The analyzer is serial, so a capture cannot race the switch; only camera Unavailable or finishing can, and both end the scan. Fixing it adds a guard branch.
    - `[medium]` `[patch]` The discard dialog does not pause capture, and `confirmDiscard` is never cleared on a phase change, so it can reappear later — capture decisions are now skipped while the dialog shows, and the `phase` setter clears `confirmDiscard` when the phase class changes.
    - `[low]` `[patch]` `resumeHold` is never cleared after review, and `handingOver` is unused — `handingOver` deleted. `backOut` is only reached from a hand-over viewfinder (review Back and retake Back take other branches), so the stale `resumeHold` causes no harm.
    - `[false]` `[reject]` `backOutOfHandOver` clears `held`/`frozen` before `backOut` is confirmed — it is only called from a hand-over viewfinder, where `resumeHold` is set and `inCorner` is false, so `backOut` returns true. A double Back clears already-cleared state.
    - `[low]` `[reject]` Back after both captures resumes hold 2 even when the asked face belongs to hold 1 — the PO set this in Q1 ("Back returns to the hold-2 capture view"). *Scan face by face* there goes straight back to the hand-over.
    - `[low]` `[reject]` The spec's AC numbering and Code Map line numbers are stale or mismatched — the fix would edit this build's spec.
    - `[low]` `[patch]` Stale `repromptFaces` in the diagnostics — it is now reset in `switchToFaceByFace` and before the automatic hand-over recomputes it.
    - `[false]` `[reject]` Kept faces are no longer re-checked once calibration exists — intended: AC2 "never reclassifies a kept face" and PO Q2 (kept faces stay; review retake fixes them).
    - `[false]` `[reject]` `closeAsksDiscardThisScanAndCancelKeepsTheScan` does not test its name — it asserts that the dialog shows, that Confirm invokes `onDiscardConfirm` once, and that Cancel removes the dialog. Closing the activity is the activity's job, covered in the route test.
    - `[low]` `[patch]` The large-font test compares px with 48 and covers only one state — it now uses `48.dp.toPx()` and adds the hand-over viewfinder.
    - `[low]` `[patch]` No retake test from a Quick Scan review — grouped with the verification-gap retake entry.
    - `[low]` `[defer]` The freeze cases are untested — grouped with the verification-gap freeze entry.
    - `[false]` `[reject]` The dead `plan.isEmpty()` branch in `handOver` — it is unreachable and causes no named harm; it is a defensive return.
    - `[false]` `[reject]` The button shows but does nothing during the post-capture-2 freeze — the spec's Design Notes settle this: "a tap that lands once the hand-over or Done has started changes nothing".
  - Edge Case Hunter
    - `[medium]` `[patch]` `confirmDiscard` survives a phase change — grouped with the Blind Hunter dialog entry.
    - `[medium]` `[patch]` Capture continues under the dialog — grouped with the Blind Hunter dialog entry.
    - `[low]` `[reject]` The switch's main runnable bails after the analyzer has switched — same as the Blind Hunter row; rejected on the same evidence.
    - `[false]` `[reject]` The button is visible but a no-op at the hold-2 freeze — the spec settles it, as in the Blind Hunter row.
    - `[low]` `[patch]` Stale `repromptFaces` — grouped with the Blind Hunter diagnostics entry.
    - `[false]` `[reject]` `held`/`frozen` cleared before `backOut` is confirmed — same refutation as the Blind Hunter row.
    - `[false]` `[reject]` A later `backOut` caller could drop added faces from a reviewed scan — no such caller exists; Back in a review or a retake takes other branches.

## Design Notes

Settled UI choices (from the ACs, EXPERIENCE.md "Quick Scan capture" / "Sticker review net", the Story 10.16 retake run and the PO's Q1/Q2):
- *Scan face by face* is a full-width secondary (outlined) action stacked directly above *Enter colors instead* in the Quick Scan corner view at hold 1 and hold 2, including during the 400 ms capture freeze ("before, between and after the corner captures"); stacked, not side by side, so both fit at 200 % font. Not shown in the Guided viewfinder (already face by face), in Case capture, or in review (*Retake faces* covers it). After capture 2 the automatic path is the same path, so a tap that lands once the hand-over or Done has started changes nothing.
- The hand-over viewfinder uses the Story 10.14 any-rotation prompt and the retake run's "Face n of N" progress over the asked faces; no "Again" counter, no named top. Auto-capture keeps the existing 2.5 s start delay of Quick Scan's hand-over for both entries (same path); no new animation — the existing capture freeze and shrink into the mini net apply.
- Back from the hand-over (PO Q1): to the hold where *Scan face by face* was tapped, or to hold 2 once both corners were captured; kept faces intact; faces captured in the Guided run are dropped (all or nothing, as Story 10.16's retake back-out). A held combined review (last face's freeze) is dropped too.
- Re-capture after resume (PO Q2): fill only; kept faces never change until review, where *Retake faces* fixes them.
- Close (and system Back in the corner view, which today equals Close) asks "Discard this scan?" whenever it would discard at least one captured face (kept or captured in the hand-over); with none it closes at once. Discard → `Cancelled`; Cancel keeps the scan exactly.
- Combined assembly needs no new `ScanAssembler` entry: `assemble` places posed kept samples as captured and searches unposed new ones; `search` always returns the first valid or best-fit `Full` with ambiguous stickers UNCERTAIN (blank in review). Quick Scan simply stops calling `repromptFaces` after the hand-over.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (Kover ≥ 80 % on `:core:*`, manifest/denylist/classpath gates green)

**Manual checks (device session, run by the orchestrator with the PO after the PR opens):**
- Lenovo TB128XU and Galaxy S24 FE, main app debug build, Solve tab → Quick Scan (`QUICK_SCAN` raised in debug): *Scan face by face* before capture 1, between captures and after capture 2; mini net shows kept faces done; only remaining faces asked, any rotation; Back resumes Quick Scan at the right hold with kept faces; re-capture fills only missing faces; Close asks "Discard this scan?"; combined review offers *Retake faces*; *Accept* opens Solution Playback; copy fits portrait without scrolling.

## Auto Run Result

Status: done

**Summary:** Quick Scan has one hand-over path for both the new *Scan face by face* action (corner view, hold 1 and hold 2, including the freeze) and the automatic hand-over after two corners. It keeps the confident corner faces and opens a Guided viewfinder for only the other faces (fixed order, any rotation, "Face n of N", kept faces filled on the mini net). The combined scan is assembled once by `ScanAssembler` with the kept poses held, with no re-prompt. Back returns to the hold it started from (hold 2 once both corners were captured) with kept faces intact (PO Q1). A re-capture fills only faces not yet kept (PO Q2). Close (and Back in the corner view) asks "Discard this scan?" when it would lose a captured face. *Retake faces* now works in the Quick Scan review.

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` — single hand-over, `switchToFaceByFace`, `backOut`, `keptFaces`/`kept`/`added`, fill-only re-capture; the round-2 path and `oriented` removed.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` — the corner phase offers the switch; hand-over viewfinder phase.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` — *Scan face by face* button (`quickScan:faceByFace`) and the discard dialog (`scan:discard`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` — switch, hand-over back-out, Close confirm, capture paused under the dialog, retake enabled for Quick Scan.
- `core/domain/src/main/resources/content/strings/en.json` — `btn.quick-scan.face-by-face`.
- Tests: `QuickScanControllerTest`, `ScanAssemblerTest`, `QuickScanRouteTest`, `QuickScanScreenTest`, `GuidedScanFlowTest`, new `QuickScanLargeFontTest`.

**Review:** 31 findings. 9 rows patched as 6 fixes (1 medium: the discard dialog's state and capture under it; the rest low: `handingOver` deleted, diagnostics reset, the large-font test, a Close-in-hand-over route test, a retake-from-Quick-Scan route test). 3 rows deferred as 1 item (the freeze tap test). Rejected: 11 false and 8 low, each with its reason in the Review Triage Log.

**Follow-up review recommended:** false. Patched entries: 1 medium, 5 low; no high.

**Verification:** `./gradlew spotlessCheck build` succeeded after the patches (4m25s). The matrix audit found every I/O row covered by tests that ran and passed (QuickScanControllerTest 22, QuickScanRouteTest 10, QuickScanScreenTest 6, QuickScanLargeFontTest 2, GuidedScanFlowTest 42, ScanAssemblerTest).

**Residual risks / device checks still to run (orchestrator + PO, Lenovo TB128XU and Galaxy S24 FE, main app debug build, Solve tab → Quick Scan):**
- *Scan face by face* at hold 1, hold 2 and during the freeze.
- Kept faces shown done on the mini net, and only the remaining faces asked.
- Back resumes the right hold.
- Re-capture fills only missing faces.
- Close dialog.
- Retake in the combined review.
- Accept leads to Solution Playback.
- The layout at 200 % font on a real device.

A kept face is classified once, in the combined assembly, against the merged self-calibration, which may differ slightly from the calibration used when the face was kept. A face that drops below CONFIDENT shows blank stickers in review, where the Learner can fix it.
