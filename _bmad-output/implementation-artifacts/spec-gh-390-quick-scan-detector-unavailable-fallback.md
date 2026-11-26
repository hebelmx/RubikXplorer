---
title: 'Continuous Quick Scan: show the manual-fallback card when the sticker detector is unavailable (#390)'
type: 'bugfix'
created: '2026-11-26'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: ['{project-root}/AGENTS.md']
warnings: ['oversized']
deferred:
  - summary: >-
      A camera whose every frame fails input conversion or peak decoding never latches the detector unavailable, so Quick Scan shows "Bring the cube into view." for ever on it and the fallback card never appears.
    evidence: |-
      LiteRtStickerCenterDetector.detect() counts a frame whose fillYuvInput or decodePeaks throws and returns EMPTY (the #389 guard); nothing escalates a run of consecutive failures. Present since #389 and outside #390, whose approved text says one bad frame never counts as unavailable and names three latch causes only. A threshold (how many consecutive failed frames mean "cannot run") is a product decision. No device is known to produce such frames. Reported by the blind and edge-case review layers.
    location: >-
      app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt:117
    severity: medium
  - summary: >-
      Whether TalkBack really speaks the detector-unavailable card's text when the card appears (once, and not twice) is checked only as a semantics property, not on a device.
    evidence: |-
      GuidedScanScreen sets paneTitle = the message on the card's Column, which also holds the same message as a Text child; ContinuousQuickScanScreenTest asserts SemanticsProperties.PaneTitle on first composition and on a change of phase. Spoken output is device behaviour and no JVM test can show it. What would settle it: a TalkBack pass on a physical device with the detector forced unavailable (for example a debug build whose model asset is removed or corrupt; there is no debug switch for it), listening for one announcement when the card replaces the live scan and when Quick Scan is opened again. Reported by the blind and intent-alignment review layers.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt:322
    severity: medium (unverified)
baseline_revision: '74cb1edd710cfe93eeee7140de6bc41353f0a1b6'
---

<intent-contract>

## Intent

**Problem:** The `StickerCenterDetector` seam cannot say "I cannot run". Once `LiteRtStickerCenterDetector` has latched itself disabled (model load failed, the model did not match the tensor contract, or an inference failed) it returns `StickerCenterDetection.EMPTY` for every frame, so the continuous Quick Scan screen says "Bring the cube into view." for ever (GitHub issue #390; the "detector unavailable" acceptance criterion of Story 10.32; tracker `epic-10-deferred-10.31-detector-unavailable-looks-empty`).

**Approach:** The seam gets an unavailable state of its own that can be read without a frame, and the `ContinuousScanEngine` port carries it to `GuidedScanActivity`; all of it stays inside `app/src/camera/`. On that state Quick Scan leaves the live scan and shows a card in the existing scan card pattern, with one new `en.json` string and two actions, *Scan face by face* and *Enter colors instead*, which run the existing hand-over rules.

## Boundaries & Constraints

**Always:**
- The unavailable state is true from the moment the detector latches disabled (model load threw an `Exception` or a `LinkageError`, the session was not opened because of a tensor contract mismatch, or an inference threw) and stays true for the rest of the app process. The model load is attempted at most once; nothing retries it.
- The state is safe to read from any thread and needs no frame.
- The card's text is exactly `Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand.` and comes from a new `en.json` key, `msg.quick-scan.unavailable`.
- The card follows the existing scan card pattern of `GuidedScanScreen` (the *Close* row above, the centred message, the first action filled and the second outlined). Its actions are exactly two, in this order: *Scan face by face* (`btn.quick-scan.face-by-face`) and *Enter colors instead* (`btn.scan.enter-colors-instead`).
- Both actions run the same code path as the live screen's buttons: a fresh engine export, then `ContinuousQuickScanFlow.keptForGuided` or `fallback`. The shown map (`GuidedScanViewModel.continuous`) and the engine's mapped state are not changed while the card shows.
- *Close* and system Back on the card use the Quick Scan rule: leave at once (`ScanOutcome.Cancelled`) at 0 mapped sides, ask "Discard this scan?" (`DiscardKind.Scan`) at 1 or more.
- When the card shows, the torch is off (`torchOn = false`, no torch control) and the camera stops analysing.
- While the detector is unavailable, every way back to Quick Scan's own screen lands on the card: opening Quick Scan again, a camera grant, Back from the Guided hand-over, Back from an early review, and a calibration saved from a review.
- The card never replaces an open "Discard this scan?" question and never interrupts an export in flight; it shows on the next analysed frame, or on the return to Quick Scan.
- TalkBack announces the card's text when the card appears, also when the card is the first thing Quick Scan shows.
- Work only inside the worktree `/mnt/e/Dynamic/IndFun/RubikXplorer-issue-390`: start every shell command with `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-390 &&`, never write to, check out in or clean another checkout or worktree of this repository, and never invoke `bmad-orchestrate` or `bmad-orchestrate-issues`. Every commit subject carries `#390`.

**Never:**
- No Kotlin change under `core/` (the only file changed there is `en.json`); no change to `ContinuousFaceletMapper`; the unavailable signal never enters a `:core:domain` type.
- No change to the `@BindsOptionalOf` bindings in `CameraModule.kt` or to `StickerCenterDetectorModule`; a variant with `QUICK_SCAN` OFF still compiles and shows no Quick Scan.
- A frame skipped by the #389 guard (input fill or peak decoding threw), an empty or degenerate crop, a frame with no cube, and an explicit `close()` (tests only) never count as unavailable.
- No retry, no reload, no Retry action, no eager model load when the screen opens.
- No net, count, cue line, preview, outlines, *Reset*, torch control or *Review and Correct* on the card.
- No change to the camera-unavailable card (`GuidedScanPhase.Unavailable`), to Guided 6-face Scan or to Case capture. No assertion of an existing test is changed or removed (a test fake may gain a member the changed interface needs).
- Do not edit `_bmad-output/planning-artifacts/`, `_bmad-output/implementation-artifacts/sprint-status.yaml`, `_bmad/`, `.claude/skills/`, `prd.md` or `ARCHITECTURE-SPINE.md`. Do not push.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Healthy detector | good frames | unavailable is false before the first frame and after every frame | No error expected |
| Model load fails | `loadModel` throws `FileNotFoundException`, or `UnsatisfiedLinkError` | every `detect` returns no peaks; unavailable is true after the first `detect` and stays true; one load attempt | latched |
| Tensor contract mismatch | `openSession` returns null | same as above | latched |
| Inference fails | `session.run` throws an `Exception` or a `LinkageError` | same as above; the session is closed once | latched |
| One bad frame (#389) | a too-short plane buffer, then a good frame | unavailable is false after both; the good frame runs inference | skipped, not latched |
| Degenerate crop, explicit `close()` | zero-size crop; `close()` on a healthy detector | unavailable is false; behavior as today | — |
| Engine carries the state | an engine from `ContinuousScanEngineModule.factory` over a detector that is unavailable | the engine reports unavailable; a second engine from the same factory reports it before any frame; a frame processed while unavailable does not advance the mapper | — |
| Latch during a scan, 0 sides | Quick Scan open; a frame arrives while the engine reports unavailable | the card shows with the exact text and its two actions; the live scan (`quickScan:continuous`), "Bring the cube into view.", the count, *Reset*, the torch control and *Review and Correct* are not on screen; *Close* and Back each end the scan Cancelled at once | — |
| Latch during a scan, 1 or more sides, torch on | six sides mapped, three fully read, torch on | the card shows; the camera is told torch off; *Close* and Back ask "Discard this scan?"; *Cancel* keeps the card; *Discard* ends Cancelled | — |
| *Scan face by face* on the card | same map | the Guided hand-over asks only for the faces the live button would ask for; Back returns to the card; the map is intact (the same hand-over and the same *Enter colors instead* stickers as before) | — |
| *Enter colors instead* on the card | anchored map with 1 or more faces; or 0 sides | `FellBackToEditor` with the confirmed stickers; or `FellBackToEditor(null)` | — |
| Reopen in the same process | a new Quick Scan activity while the engine already reports unavailable | the card shows at once, with no frame fed; the live scan is never composed and continuous analysis never starts | — |
| Discard question open | the question is open on the live scan when the unavailable frame is handled | the question stays; after *Cancel* the next frame shows the card | — |
| `QUICK_SCAN` OFF | direct Quick Scan intent | as today: the camera-unavailable card, no Quick Scan UI | — |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- the seam (base camera code, compiled with `QUICK_SCAN` OFF too). Add the unavailable state here as a read-only query on `StickerCenterDetector`; update the KDoc. `StickerCenterDetection` and `detect`'s signature stay.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `@Singleton`. Latch points: `ensureSession()` (~L191-205: `openSession(loadModel())` returns null on a contract mismatch, catches `Exception` and `LinkageError`; `initAttempted` already stops a retry) and `latchDisabled()` (~L184, called from `runInference`'s two catches). Not latch points: the #389 frame catch in `detect()` (~L110), the crop guard (~L86), `close()` (~L207; used by tests and device tests only). State is guarded by `lock`; the new flag must be readable without it (`@Volatile`).
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `processFrame` calls `detector.detect` then fitter, sampler, `mapper.processFrame`. The invalid-crop branch (~L47-66) already returns the last snapshot without advancing the mapper: the model for an unavailable frame.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- `PipelineEngine` implements the port; one engine per scan over the singleton detector.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- the port `GuidedScanActivity` sees (`ContinuousFrame`, `ContinuousScanEngine`, `ContinuousScanEngineFactory`). The unavailable state crosses here. Fakes: `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt:124`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt:41`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanPhase` (~L135 `Continuous`, ~L154 `Unavailable`); the *Close* row for every phase but `Viewfinder` and `Continuous` (~L285); the phase `when` (~L306) with the `Rationale`, `Denied` and `Unavailable` cards; private `Card(message, tag, buttons)` (~L389); `EnterColorsButton` (~L1014); `DiscardKind` (~L979).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- exhaustive `when (phase)` in `fallbackStickers` (~L197); `torchControl` / `torchLit` (~L260-307) are true only for `Continuous` and `Calibration`, so a new phase gets no torch.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `onCreate` creates the engine (~L146); `onEnterColors` (~L204) and `onFaceByFace` (~L246) actions; Back handler (~L286); `closeOrConfirm()` (~L508); `cameraPhase()` and `startCamera()` (~L607-621); `analyze()` continuous branch (~L628-644: analyzer thread, posts to main under `phase == Continuous && revision`). `GuidedScanViewModel`: `exportContinuous` (~L909, requires `phase == Continuous`), `switchToFaceByFace` (~L978), `backOutOfEarlyReview` (~L958), `backOutOfHandOver` (~L1205), `capturePhase()` (~L1013, `quickScanMode` branch ~L1024), `saveCalibration` (~L1117 restarts through `capturePhase()`), the `phase` setter (~L1132: a change of phase class dismisses the discard question).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt` -- the live screen the card replaces; its comment at ~L106 records that TalkBack does not speak a live region on a node that was just added.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanFlow.kt` -- read-only: `confirmClose`, `fallback`, `keptForGuided`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.quick-scan.*` keys at ~L22-28; `msg.scan.camera-unavailable` at L748 is the camera card's copy (not reused).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt` -- plain JUnit 5; has the load-failure, `LinkageError`, inference-failure, bad-frame and `close()` cases plus `makeBlankYuv` and `CountingStickerCenterSession`. `ContinuousScanEngineModuleTest.kt` beside it drives the production engine over a fake detector.
- `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` -- Robolectric + Hilt route test from the Solve tab: fake engine factory (~L121), `show(...)`, `quickScanActivity { scan, model -> }`, `settle`, `waitForCamera`, `FakeFrames` (`torchCalls`, `continuous`). `quickScanActivity` waits for the continuous camera, so the reopen case needs its own opening.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreenTest.kt` -- Robolectric screen test through `GuidedScanScreen(phase, strings, actions, ...)` with shipped strings.
- `docs/ARCHITECTURE.md` L165, L200 -- name the seam and its optional binding only; they do not describe its shape or a failure state, so no edit is needed.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.quick-scan.unavailable` with the exact text, beside the other `msg.quick-scan.*` / `btn.quick-scan.*` keys -- the card's copy is content.
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- add the unavailable query to the seam -- a state of its own, never an empty detection.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- set the state at the three latch points only; update the class KDoc -- the detector reports why it returns nothing.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt`, `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt`, `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- carry the state through the pipeline and the engine port; a frame processed while the detector is unavailable does not run the fitter, the sampler or the mapper -- the activity can read the state with or without a frame, and the map stays as it was.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt`, `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- add a phase for the card (`GuidedScanPhase.QuickScanUnavailable`), draw it with `Card` (tag `quickScanUnavailable`) and the two actions wired to `actions.onFaceByFace` and `actions.onEnterColors`, and make its text speak when the card appears -- the Learner-facing state.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- enter the card phase from the analyzer when the engine reports unavailable (guarded as in Boundaries) and from every return to Quick Scan's own screen; let the export, *Scan face by face*, *Enter colors instead* and the Close rule work from the card phase; turn the torch off -- the route.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModuleTest.kt` -- tests for the detector and engine rows of the matrix -- load failure and inference failure each reach the state; a bad frame does not.
- `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` -- route tests for the "Latch during a scan" (both), *Scan face by face*, *Enter colors instead*, "Reopen" and "Discard question open" rows -- the card, not the bring-into-view cue.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreenTest.kt` -- screen test of the card phase: exact text, the two actions call their callbacks, *Close* calls `onClose`, none of the live-scan nodes exist, and the announcement semantics -- the card alone.

**Acceptance Criteria:**
- Given the card phase on `GuidedScanScreen`, when it is composed (as a change from the live scan and as the first content), then its message carries semantics that TalkBack speaks for a node that was just added, and a test asserts them.
- Given the change, when `./gradlew :app:checkLearnerText` runs, then it passes and no Learner-facing literal was added to Kotlin or `strings.xml`.
- Given `QUICK_SCAN` OFF (the default `release` variant) and the 0.11.0 profile (`-Pcubentia.release011=true`), when each is compiled, then both compile.
- Given the existing Guided 6-face Scan, Case capture and Quick Scan tests, when the suite runs, then they pass with no assertion changed.
- Given the change, when `git diff` against the baseline is read, then no Kotlin file under `core/`, no file under `_bmad-output/planning-artifacts/` and not `sprint-status.yaml` changed.

## Spec Change Log

## Review Triage Log

### 2026-11-26 — Review pass
- verdicts: 31 findings — high 0, medium 2, low 21, false 6, maybe-false 2
- findings:
  - `[low]` `[reject]` (intent) No test runs a failing detector end to end to the card: the route test fakes the engine, the screen test sets the phase, and the detector → pipeline → engine link is tested apart — the intent asks for detector tests plus "a screen or route test", which is what exists; the one untested link in between, that the Hilt graph gives every scan the same detector, is now pinned (see the verification-gap row). A Robolectric run of the real LiteRT detector through the camera is far more than a direct correction.
  - `[low]` `[reject]` (intent) In a fresh process with a broken model the live scan and its bring-into-view cue show until the first analysed frame latches the detector — the approved text speaks of the state "once the detector has latched disabled" and says the screen "leaves the live scan"; "at once" is asked only for a reopen, which holds. The model loads on the first frame today; loading it earlier is a new behaviour, not a correction.
  - `[false]` `[reject]` (intent) Three behaviours were chosen, not raised as questions: the card waits behind an open discard question or an export in flight, Back from an early review returns to the card, and a calibration saved from a review returns to the card — none is an open choice. The first two only arise when the latching frame and a tap land in the same few milliseconds (no frame is analysed while the question is open or an export runs), and follow the existing rule that a discard question holds a pending change (`theDiscardQuestionHoldsACompletedScanUntilItIsCancelled`); every return to Quick Scan shows the card by PO decision 3 (no retry), the rule the Sprint Change Proposal itself applies to the hand-over.
  - `[maybe-false]` `[defer]` (intent) TalkBack: the card sets a pane title that repeats its Text child, and only the semantics property is asserted — whether TalkBack speaks it once, twice or not at all cannot be told from code; a device pass with the detector forced unavailable would settle it. Recorded in `deferred` (medium, unverified).
  - `[false]` `[reject]` (intent) Torch off is asserted on the fake camera's last torch call and a null receiver — that is the outermost surface a JVM test has; the route test also asserts `torchOn` false and that no torch control is on screen. No divergence.
  - `[low]` `[patch]` (intent) One production edit landed in shared Guided code with no test: the phase reset after the calibration load skipped a hand-over viewfinder — removed; that statement is back at the baseline, so Guided code is untouched there.
  - `[false]` `[reject]` (intent) The pipeline does more than expose a state: it stops calling `detect` once unavailable, returns the last snapshot for the latching frame, and the invalid-crop branch moved into `skippedFrame` — this is what "never as an empty detection" and "faces already mapped are kept" need; the invalid-crop behaviour is unchanged and its existing tests pass unedited.
  - `[false]` `[reject]` (intent) The diff does not show that the build, `checkLearnerText` and a `QUICK_SCAN`-OFF compile pass, and the proposal's front matter still says `drafted` — all four verification commands were run here and pass (see Auto Run Result); the proposal file is a planning artifact outside this change, and the intent states its approval.
  - `[low]` `[reject]` (blind) The sibling reset in `onCreate` (~L157) has no hand-over exclusion, so a hand-over opened before the calibration is read is lost on a rotation — with the L177 condition removed the two sites agree again. What remains is the baseline's own race (the live screen's *Scan face by face* has it too): it needs a tap or a rotation inside the few milliseconds of the calibration read, and closing it adds guards to shared Guided code.
  - `[low]` `[reject]` (blind) A calibration saved from a review resets the map and then lands on the card with nothing mapped — the reset is the existing rule for any calibration saved from a review, and the Sprint Change Proposal (section 4.4) leaves it out of this change on purpose as an open product question with its own tracker items; landing on the card is PO decision 3. The test pins the card only, so it does not freeze the open question.
  - `[low]` `[reject]` (blind) The debug `quickScan run` line cannot tell a run that ended on the card from any other — true, debug only. Marking it needs a new argument through both `ScanDiagnostics` twins, which the intent ("exactly this and nothing more") does not ask for; a failed detector already logs its own warning.
  - `[low]` `[reject]` (blind) The copy says "right now" although nothing retries until the app restarts, and one transient inference error disables Quick Scan for the process — the exact copy, no retry action and "no retry until the app process restarts" are binding PO decisions (2026-11-26); the latch on a failed inference is the existing behaviour.
  - `[low]` `[reject]` (blind) The state is found only by the first analysed frame, so a fresh process asks for the camera and shows the live screen first — same finding as the intent layer's timing row, same reason.
  - `[false]` `[reject]` (blind) A `close()`d detector returns EMPTY for ever with `unavailable` false — `close()` is called only by unit and device tests (searched `app/src`); nothing in the app closes the singleton.
  - `[false]` `[reject]` (blind) An `Error` that is not a `LinkageError` from the model load leaves the detector neither loaded nor unavailable — it is not caught: it leaves `ensureSession` and `detect` and ends the analyzer thread, as before this change (#389 keeps an `Error` from being swallowed), so the app never goes on in that state.
  - `[medium]` `[defer]` (blind) Frames that fail conversion on every frame only raise `failedFrames`; nothing escalates them, and the cue shows for ever — real and present since #389; the approved text says one bad frame never counts and names three latch causes, so a threshold is a product decision. Recorded in `deferred`.
  - `[low]` `[reject]` (blind) "Preserves the last map" is asserted against an empty map — the test's snapshot equality includes `frameIndex`, so it fails when the mapper runs on the latching frame (shown by a mutation run), and only the mapper holds the map. A populated-map fixture needs fitted faces through the real mapper, far more than a direct correction.
  - `[low]` `[patch]` (blind) No test rotates the device on the card — added `rotationKeepsTheCardTheMapAndTheEngineAndStartsNoCamera`.
  - `[low]` `[reject]` (blind) No test drives the `revision` check on the card's route (a Reset between the analyzer and main) — the check is the frame-update path's own, the state is level-based so the next frame shows the card, and a test needs a Reset injected between two threads.
  - `[low]` `[patch]` (blind) *Scan face by face* is never tapped on a reopened Quick Scan's card, where the camera has not started — added `scanFaceByFaceOnTheCardOfAReopenedQuickScanStartsTheGuidedCameraAndBackReturnsToTheCard`.
  - `[maybe-false]` `[defer]` (blind) TalkBack is asserted as a property only, and no debug hook can force the card on a device — same root cause as the intent layer's TalkBack row; shares its `deferred` entry.
  - `[low]` `[patch]` (blind) The card at 200% font on the non-scrolling `Card` is unverified — added `theDetectorUnavailableCardFitsOnTheTabletAtDoubleFont` (Lenovo 600 dp, font scale 2.0): *Close*, the message and both actions are shown unclipped, in order; the card needed no change.
  - `[low]` `[reject]` (blind) `GuidedScanPhase.Unavailable` (camera) now sits beside `QuickScanUnavailable` (detector); rename the old one — a rename touches the camera-unavailable card's code and tests, which the intent keeps unchanged.
  - `[low]` `[reject]` (blind) The card's *Scan face by face* button has no test tag — none of the scan cards' buttons has one; their tests find them by the shipped text, which also pins the copy.
  - `[low]` `[reject]` (edge) A hand-over opened from the card before the calibration is read is replaced by the card on a recreate — same finding and reason as the blind layer's first row.
  - `[low]` `[reject]` (edge) A *Reset* or torch tap landing after the card phase is set and before the next frame clears the map or sets `torchOn` behind the card — a one-frame window; the Learner asked for the Reset, and the torch cannot light on the card (`torchControl` is null there) and is set off again by the hand-over. Guards on both callbacks are more than a direct correction.
  - `[medium]` `[defer]` (edge) Every frame failing conversion never latches unavailable — same root cause as the blind layer's row; shares its `deferred` entry.
  - `[low]` `[patch]` (edge) Widening `exportContinuous` to the card phase let a stale *Review and Correct* tap (and `completeContinuous`) open a review from the card — both now return unless the phase is the live scan; added `reviewAndCorrectAndCompletionNeverRunFromTheCard` (it fails with the guard removed).
  - `[low]` `[patch]` (gap) The hand-over exclusion added to the phase reset after the calibration load has no test — the exclusion is removed instead (see the intent layer's row): it was beyond the spec, and the statement is back at the baseline.
  - `[low]` `[patch]` (gap) Nothing asserts that the real Hilt graph gives every scan the same detector, on which "for the rest of the process" and "reopen shows the card at once" rest — added `debugGraphHandsEveryScanTheSameStickerCenterDetector` to `CornerDetectorBindingTest`.
  - `[low]` `[reject]` (gap) The `onCreate` reset at ~L157 has no hand-over exclusion — same finding and reason as the blind layer's first row.

## Design Notes

**Why a query and not a field on `StickerCenterDetection`.** Issue #390 allows either. Reopening Quick Scan must show the card at once, before the camera has delivered a frame, and the detector is a process singleton, so the state must be readable without calling `detect`. One query on the seam (for example `val unavailable: Boolean`), passed on by the pipeline and by one member of the engine port (for example `val detectorUnavailable: Boolean`), serves both the reopen check on the main thread and the per-frame check on the analyzer thread. Because the state is level-based (true on every frame once latched), a frame update that is dropped (discard question open, export in flight, older revision) loses nothing.

**Why a phase and not a flag on the live screen.** The approved text says the card stands "in place of the live scan" and "reuses the existing scan card pattern" (SCP 2026-11-26, section 2, item 3.3). In `GuidedScanScreen` that pattern is `Card`: the *Close* row, a centred message and the actions, with the first action filled (`Rationale`, `Denied`). A phase of its own also takes the camera out of `cameraPhase()` and the torch out of `torchControl` with no extra rule.

**TalkBack.** The other scan cards announce nothing, and a polite live region on a just-added node is not spoken (see the comment in `ContinuousQuickScanScreen.kt`). Use a mechanism that speaks on appearance, such as pane-title semantics on the card carrying the message, and assert that property in the screen test.

**One helper for "Quick Scan's own phase".** `capturePhase()`, `backOutOfHandOver()` and `backOutOfEarlyReview()` each name `GuidedScanPhase.Continuous` today. One function that answers camera-unavailable (no engine), the card (engine reports unavailable) or `Continuous` keeps the "no retry" rule in one place.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-390 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- expected: set before every Gradle command.
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.*' --tests 'dev.cubentia.app.ContinuousQuickScanRouteTest' --tests 'dev.cubentia.app.ScanRouteTest' --tests 'dev.cubentia.app.GuidedScanCloseRouteTest' --tests 'dev.cubentia.app.CaseRecognitionRouteTest'` -- expected: BUILD SUCCESSFUL; the new tests ran and passed.
- `./gradlew :app:checkLearnerText` -- expected: BUILD SUCCESSFUL.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- expected: BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (fix formatting with `./gradlew spotlessApply`).

## Auto Run Result

Status: done

**Summary:** The `StickerCenterDetector` seam now has an `unavailable` state of its own. `LiteRtStickerCenterDetector` sets it when the model load fails, when the model does not match the tensor contract and when an inference fails, and it stays set for the app process; a skipped bad frame (#389), a degenerate crop and `close()` leave it false. The pipeline and the `ContinuousScanEngine` port pass it on (`detectorUnavailable`), and a frame processed while it is set does not run the fitter, the sampler or the mapper. `GuidedScanActivity` then leaves the live scan for a new phase, `GuidedScanPhase.QuickScanUnavailable`: a card in the existing scan card pattern with the new `en.json` string `msg.quick-scan.unavailable` and two actions, *Scan face by face* and *Enter colors instead*, which run the existing export and hand-over rules. The torch goes off, the camera stops, *Close* and Back keep the Quick Scan rule, the card's text is a pane title for TalkBack, and every return to Quick Scan (reopen, camera grant, Back from the hand-over or an early review, a calibration saved from a review) lands on the card.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- new string `msg.quick-scan.unavailable` (the only change under `core/`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/StickerCenterDetector.kt` -- `val unavailable: Boolean` on the seam, with its contract in KDoc.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt` -- `@Volatile` latch set in `ensureSession()` (failed load, contract mismatch) and `latchDisabled()` (failed inference).
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `detectorUnavailable`; an unavailable frame returns the last snapshot through `skippedFrame` (shared with the invalid-crop branch) and leaves the mapper alone.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt`, `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousScanEngine.kt` -- `detectorUnavailable` on the engine port and its production engine.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- sealed `GuidedScanPhase.QuickScan` (`Continuous`, `QuickScanUnavailable`); the card (tag `quickScanUnavailable`); `Card` takes a modifier for the pane title.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `fallbackStickers` covers the new phase.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- the analyzer's route to the card (never over an open discard question or an export in flight); `quickScanPhase()` for every return to Quick Scan; export, *Enter colors instead* and the Close rule work from either Quick Scan phase; `reviewAndCorrect` and `completeContinuous` run from the live scan only.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetectorFailureTest.kt`, `ContinuousScanEngineModuleTest.kt` -- the detector and engine rows of the matrix.
- `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` -- 13 route tests for the card (latch at 0 and at 1 or more sides, both actions, reopen, camera grant, calibration saved from a review, discard question, export in flight, rotation, stale *Review and Correct*).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreenTest.kt`, `ContinuousQuickScanLargeFontTest.kt` -- the card alone: text, actions, pane title, no live-scan node; fit at 200% font on the Lenovo.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerDetectorBindingTest.kt` -- the debug Hilt graph gives every scan the same detector.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/continuous/ContinuousFaceSamplerTest.kt` -- test fakes gained the new interface member only.

**Implementation:** Codex started the change and stopped on its usage limit (`build-implement.result`: `claude (codex fallback: Codex usage limit reached mid-run; turn failed)`). A Claude subagent completed it in place, and the same subagent applied the review patches. All four review layers ran as Claude subagents (Codex was off for the rest of the run).

**Review findings:** 31 total from four layers (intent-alignment 8, blind 16, edge-case 4, verification-gap 3).
- Patches applied: 6 entries, all low (7 rows): removed an untested hand-over condition the implementation had added to shared Guided code (the statement is back at the baseline); `reviewAndCorrect` and `completeContinuous` no longer run from the card, with a test; four more tests (rotation on the card, *Scan face by face* on a reopened card, the card at 200% font, the singleton detector in the Hilt graph).
- Deferred: 2 entries (4 rows): frames that fail conversion on every frame never latch unavailable (medium, present since #389); TalkBack speech for the card is unverified on a device (medium, unverified).
- Rejected: 20 rows (14 low, 6 false); each reason is in the Review Triage Log. In short: the card shows only after the first frame latches the detector in a fresh process (the approved text speaks of the latched state and asks "at once" only for a reopen); the race that resets a hand-over opened within the calibration read exists at the baseline; the map reset after a calibration saved from a review is the existing rule the proposal left out on purpose; the debug run line, the copy, a rename and a test tag are outside "exactly this"; four test-shape findings cost more than they guard; the six false rows are refuted in the log.

**Follow-up review recommendation:** false -- patched entries: high 0, medium 0, low 6.

**Verification** (after the review patches):
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.*' --tests 'dev.cubentia.app.ContinuousQuickScanRouteTest' --tests 'dev.cubentia.app.ScanRouteTest' --tests 'dev.cubentia.app.GuidedScanCloseRouteTest' --tests 'dev.cubentia.app.CaseRecognitionRouteTest'` -- BUILD SUCCESSFUL.
- `./gradlew :app:checkLearnerText` -- BUILD SUCCESSFUL.
- `./gradlew -Pcubentia.release011=true :app:compileRelease011Kotlin` -- BUILD SUCCESSFUL.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL; JUnit XML of `:app:testDebugUnitTest`: 153 suites, 1551 tests, 0 failures, 0 errors, 0 skipped (`ContinuousQuickScanRouteTest` 47, `LiteRtStickerCenterDetectorFailureTest` 12, `ContinuousQuickScanScreenTest` 11, `ContinuousQuickScanLargeFontTest` 9, `CornerDetectorBindingTest` 4, `ContinuousScanEngineModuleTest` 3).
- Every I/O matrix row has a test that ran and passed. The implementer broke four guards on purpose, one at a time (the discard/export guard, `quickScanPhase()`, the pipeline's skip after a latching frame, the `reviewAndCorrect` phase guard); the new tests failed each time and the guards were restored.
- `git diff` against the baseline: no Kotlin under `core/`, nothing under `_bmad-output/planning-artifacts/`, not `sprint-status.yaml`.

**Residual risks:**
- Not run on a physical device. TalkBack speech for the card is asserted as a semantics property only, and there is no debug switch to force the detector unavailable on a device (deferred entry 2).
- In a fresh app process with a broken model, the live scan and "Bring the cube into view." show until the first analysed frame latches the detector; the card shows at once only from the second opening on.
- A camera whose every frame fails conversion still looks like "no cube in view" (deferred entry 1).
- A hand-over opened within the few milliseconds of the calibration read after Quick Scan opens is reset to Quick Scan's own screen (now possibly the card). The baseline has the same race on the live screen.
- A calibration saved from a review restarts Quick Scan with an empty map, as before; while the detector is unavailable that lands on the card with nothing mapped.
- During the implementation one `spotlessCheck build` run failed in `lintAnalyzeDebugUnitTest` / `lintAnalyzeDebugAndroidTest` with a `FileNotFoundException` on a Hilt-generated source and passed on an unchanged re-run; the two later full builds passed. It looks like a task-ordering race in the build, not this change.
