---
title: 'ai-b1 Learner-visible fixes: BETA label helper, Guided Scan safety, retake flash, editor draft'
type: 'bugfix'
created: '2026-11-20'
status: 'done'
baseline_revision: '38ef414ff81cd9fa9d76ed7cb6091fe0dcdc952e'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md'
warnings: ['multiple-goals', 'oversized']
deferred:
  - summary: >-
      PO device item: confirm on a reference device that a camera failure during the last face's capture never leaves Guided Scan stuck (the Activity wiring of GuidedScanViewModel.onCaptured in handle() is not tested at route level).
    evidence: |-
      GuidedScanFlowTest covers onCaptured and publish on the ViewModel; ScanRouteTest never sets Unavailable between the analyzer submit and the main-looper idle, so reverting GuidedScanActivity.kt:583 would not fail a test. The real race timing is device-only; batch with the combined human-verification session (#106). FakeFrames.feed could simulate it later.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt:583
    severity: medium
  - summary: >-
      PO device item: confirm a real MediaActionSound load failure leaves the scan running silently (the onCreate call to ShutterSound.loadOrSkip is not tested where it runs).
    evidence: |-
      ShutterSoundTest proves loadOrSkip swallows a throwing Player.load, but GuidedScanActivity builds ShutterSound() with the real MediaActionPlayer, so no route test can make the load throw; reverting GuidedScanActivity.kt:118 fails no test. Device-only; batch with #106.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt:118
    severity: medium
  - summary: >-
      PO device item: on hardware, tap Retake N faces and confirm the review controls never flash before the retake viewfinder opens.
    evidence: |-
      ScanReviewScreenTest proves the inert launching state with a fake onRetake, and ScanRouteTest proves the review comes back in review mode after back-out or Close; the real analyzer-thread hop timing is device-only. Batch with #106.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt:113
    severity: low
---

<intent-contract>

## Intent

**Problem:** Nine open sprint-status action items (batch ai-b1): camera entry points show the BETA label only for their own flag, not when `CAMERA_SCAN` is BETA (EXPERIENCE.md:137); Guided Scan can hold its result forever when the camera goes Unavailable between the last capture and its main-thread callback; a `MediaActionSound.load()` throw crashes the scan; the calibration prompt key may break under a Turkish locale; *Retake N faces* flashes the review controls for one thread hop; unaccepted Manual Cube Editor edits are lost on a Solve tab switch.

**Approach:** One pure helper `FeatureFlags.showsBeta(flag)` used at every camera entry; a phase guard on Guided Scan capture feedback; a never-throwing shutter load; a launching state in retake mode; the Solve editor draft kept in `SolveFlowViewModel`. The locale item is checked first and proven by test if already correct.

Items: epic-11-retro-item-102-one-beta-label-helper-feature-flag-beta, epic-11-deferred-11.1-solve-beta-label-camera-scan, epic-11-deferred-11.2-camera-scan-beta-label, epic-11-deferred-11.3-recognize-beta-label-camera-scan, issue-158-deferred-freeze-callback-phase-race, issue-158-deferred-shutter-load-runcatching, issue-158-deferred-calibration-locale-root, epic-10-deferred-10.16-retake-launch-controls-flash, epic-3-deferred-3-7-editor-drafts-lost-on-tab-switch.

## Boundaries & Constraints

**Always:** Every fixed behavior gets a JVM or Robolectric test. Camera code stays in `app/src/camera/`. Learner text only via content keys. Existing tests that assert the old (wrong) BETA behavior are updated to the EXPERIENCE.md rule, not deleted.

**Never:** Edit sprint-status.yaml, planning artifacts, `_bmad/`, `.claude/skills/`. Change flag levels, release policy, copy, or anything outside these nine items. Persist the editor draft to disk (it lives only as long as the ViewModel).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Own flag BETA | flag BETA | `showsBeta` true | - |
| Camera feature GA, CAMERA_SCAN BETA | QUICK_SCAN/PLACEMENT/CASE_RECOGNITION GA | true | - |
| Camera feature GA, CAMERA_SCAN GA | | false | - |
| Non-camera flag GA, CAMERA_SCAN BETA | TEST_ME or ADAPTIVE_ASSISTANCE GA | false | - |
| Flag OFF | any flag OFF | false | - |
| Last-face capture after Unavailable | phase Unavailable when the main callback runs, Done in effects | no freeze, no shutter; review published at once | - |
| Capture in viewfinder | phase Viewfinder | freeze + shutter as today; review held until freeze ends | - |
| Shutter load throws | Player.load throws | no crash; play is a no-op | swallowed |
| Retake start | tap *Retake N faces* | onRetake once; review controls never shown; Start/Cancel disabled until the screen leaves Review | - |
| Editor tab switch | paint stickers, leave Solve, return | editor shows the painted stickers | - |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/cubentia/application/port/FeatureFlags.kt` -- `FeatureFlags` port; add the `showsBeta` extension here (pure JVM). Test beside `core/application/src/test/.../port/FeatureFlagsTest.kt`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- :725 `placementBeta` (Recognize *Start from my cube*), :756 `recognizeBeta` (Library), :841-842 `quickScanBeta`/`scanBeta` (Solve): switch all to `flags.showsBeta(...)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/welcome/WelcomeScreen.kt:130` -- `betaStrings` gated on PLACEMENT==BETA; use `flags.showsBeta(FeatureFlag.PLACEMENT)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/recognize/RecognizeScreen.kt:129`, `library/LibraryScreen.kt`, `solve/SolveScreen.kt` -- take booleans; no change expected.
- `app/src/test/.../ui/shell/AppShellSolveTest.kt:224-228` -- asserts no Quick Scan BETA label at CAMERA_SCAN BETA + QUICK_SCAN GA: the old behavior; flip it. `AppShellRecognizeTest.kt` (`launch(...)`, FeatureFlags fake at :186), `welcome/WelcomeScreenTest.kt` (fakes at :62, :89) -- add CAMERA_SCAN-BETA cases.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `handle()` main-thread block (~:580-592) sets `vm.frozen` and plays the shutter with no phase check; `GuidedScanViewModel.publish` (:825) holds the result while `frozen != null`; `endFreeze` (:854) only runs from `FrozenOverlay` (GuidedScanScreen.kt:465), which is composed only in the viewfinder, so a freeze set after Unavailable is never ended. Move the capture feedback into a `GuidedScanViewModel` main-thread method that freezes (and lets the caller play the shutter) only while `phase is GuidedScanPhase.Viewfinder`, so `publish` then sets the phase directly. Same file :118 `lifecycleScope.launch(Dispatchers.IO) { shutterSound.load() }`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ShutterSound.kt` -- `load()` rethrows a Player failure (ShutterSoundTest :55); add a non-throwing load (runCatching) used by the IO coroutine. Tests: `app/src/testDebug/kotlin/dev/cubentia/app/camera/ShutterSoundTest.kt`; new VM test in `app/src/testDebug/.../camera/`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt:763` -- `color.name.lowercase()`. Kotlin's no-arg `String.lowercase()` is locale-invariant (Locale.ROOT), so this is likely already correct: prove with a test under `Locale.setDefault(tr)` (e.g. a Robolectric calibration prompt test in `ColorCalibrationScreenTest.kt`/`GuidedScanScreenTest.kt` asserting the white prompt resolves), and change code only if the test fails.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt:108-124` -- `onStart` sets `retaking = false` before the async phase hop (GuidedScanActivity `startRetake` :599), so `ReviewMode` renders for one hop. Tests: `app/src/test/.../ui/scan/ScanReviewScreenTest.kt` (`launchRetake` :897).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `SolvePhase.Editing(initial)`; `leaveSolveTab()` keeps Editing; `enterColors`/`closeEditor`/`confirm`/`accept`/`cancel` reset it. `ui/editor/ManualCubeEditor.kt:115` keeps the state in `rememberSaveable(initial)`, lost when the Solve destination leaves composition (AppShell `when (tab)`). `ui/solve/SolveScreen.kt` Editing branch passes `initial = current.initial`. Tests: `app/src/test/.../ui/solve/SolveFlowViewModelTest.kt`, `ui/shell/AppShellSolveTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/application/.../port/FeatureFlags.kt` -- add `fun FeatureFlags.showsBeta(flag: FeatureFlag): Boolean` = enabled and (own level BETA, or flag in {CAMERA_SCAN, QUICK_SCAN, PLACEMENT, CASE_RECOGNITION} and CAMERA_SCAN is BETA); KDoc cites EXPERIENCE *Feature flag BETA* -- one rule for every site (retro item 102).
- `core/application/src/test/.../port/FeatureFlagsTest.kt` -- cover every I/O matrix row for `showsBeta`.
- `AppShell.kt`, `WelcomeScreen.kt` -- use `showsBeta` at the four sites -- closes 11.1, 11.2, 11.3 and the Recognize Placement-link site.
- `AppShellSolveTest.kt`, `AppShellRecognizeTest.kt`, `WelcomeScreenTest.kt` -- Robolectric: with CAMERA_SCAN BETA and the feature flag GA, the BETA label (and TalkBack "<feature>, BETA") shows on Solve Quick Scan, Library Recognize case, Recognize *Start from my cube*, Welcome *Start from my cube*; flip the old Solve assertion.
- `GuidedScanActivity.kt` -- guard capture feedback on Viewfinder (VM method) in `handle()`; also route `handleRetake`'s identical block through it if it is the same race (its existing `fromReview` guard already requires Viewfinder, so leave it unchanged otherwise); use the non-throwing shutter load at :118.
- `ShutterSound.kt` -- non-throwing load wrapper.
- `app/src/testDebug/.../camera/` -- JVM tests: VM capture feedback while Unavailable does not freeze and `publish` sets the Review phase; while Viewfinder it freezes and holds until `endFreeze`; throwing Player load does not throw.
- `GuidedScanScreen.kt` locale -- test first under Turkish default locale; record result in Design Notes.
- `ScanReviewScreen.kt` -- after *Retake N faces*, stay in retake mode in a launching state (Start, Cancel and face toggles disabled) instead of returning to review mode; the launching state clears when `review` changes.
- `ScanReviewScreenTest.kt` -- tap Start: onRetake called exactly once, review-mode controls (e.g. `review:retakeFaces`, Accept) do not exist, a second Start tap does nothing.
- `SolveFlowViewModel.kt`, `ManualCubeEditor.kt`, `SolveScreen.kt` -- editor reports edits (optional `onChange: (Facelets) -> Unit`); the VM keeps the draft and `leaveSolveTab()` swaps it into `Editing.initial` (keeping note/error); the draft clears wherever Editing is replaced.
- `SolveFlowViewModelTest.kt`, `AppShellSolveTest.kt` -- JVM + Robolectric: paint a sticker, switch to Learn and back, the sticker keeps its color; Close then Enter colors does not resurrect a discarded draft.

**Acceptance Criteria:**
- Given CAMERA_SCAN BETA and QUICK_SCAN, PLACEMENT, CASE_RECOGNITION GA, when the Learner sees Solve, Welcome, Library and a Recognize not-at-last-layer result, then each camera entry shows the BETA label and reads "<feature>, BETA".
- Given every camera flag GA, when the same screens show, then no BETA label shows.
- Given the analyzer posts the last face's capture after the phase became Unavailable, when the main callback runs, then the shutter does not play, nothing freezes, and the review is published.
- Given unaccepted editor edits, when the Learner leaves the Solve tab and returns, then the edits are still there.
- Given every change, when `./gradlew spotlessCheck build` runs, then it passes.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 28 findings — high 0, medium 5, low 11, false 12, maybe-false 0
- findings:
  - `[medium]` `[defer]` (intent) Freeze-race fix is tested on the ViewModel, not through GuidedScanActivity.handle() — grouped with the gap-layer handle() finding; deferred as a PO device item (real race timing is device-only).
  - `[false]` `[reject]` (intent) Retake screen stays inert if the phase never leaves Review — during Review nothing but startRetake's hop changes the phase, and the hop runs on the ViewModel-held analyzer executor, which only shuts down in onCleared.
  - `[false]` `[reject]` (intent) Locale item closed without a Locale.ROOT edit — the intent allows proof instead of change; Kotlin's no-arg lowercase() is locale-invariant and ColorCalibrationScreenTest.whitePromptResolvesUnderATurkishDefaultLocale guards it (Design Notes).
  - `[low]` `[reject]` (intent) Editor draft keeps stickers but not the selected sticker or compact face — rare annoyance; keeping them needs new editor parameters (added complexity).
  - `[low]` `[reject]` (intent) Welcome BETA tested on WelcomeScreen, not through AppShell — WelcomeScreen reads the flags itself; the shell passes them through unchanged.
  - `[medium]` `[defer]` (intent) Device-only parts were prose, not in the deferred list as PO items — recorded as three PO deferred entries in frontmatter.
  - `[false]` `[reject]` (blind) A freeze set in the Viewfinder then Unavailable holds the review forever — FrozenOverlay's DisposableEffect onDispose ends the freeze when the viewfinder leaves composition (GuidedScanScreen.kt:465-479), publishing the held review.
  - `[false]` `[reject]` (blind) handleRetake keeps its own unguarded freeze — its callback returns unless the phase is the retake Viewfinder (fromReview === run.from), so it cannot freeze after Unavailable.
  - `[low]` `[reject]` (blind) loadOrSkip's runCatching also catches CancellationException/Error and logs nothing — the coroutine ends right after, so a swallowed cancellation changes nothing; logging adds surface not asked for.
  - `[low]` `[reject]` (blind) ShutterSound.play() throw is not guarded — not reported on device and outside the listed item (load only); the intent says nothing more.
  - `[false]` `[reject]` (blind) Launching state has no way out if the hop never arrives / after process death — the hop always completes while the ViewModel lives; after process death the new ViewModel starts at Rationale, so the review screen is not shown.
  - `[low]` `[reject]` (blind) Launching state gives no progress feedback — lasts one analyzer-thread hop (milliseconds).
  - `[low]` `[reject]` (blind) Lesson TEST_ME/ADAPTIVE_ASSISTANCE sites and Lesson Quick Check do not use showsBeta — for non-camera flags showsBeta equals level == BETA; Lesson Quick Check is not one of the listed sites (the intent names Solve, Welcome, Library, Recognize).
  - `[low]` `[reject]` (blind) cameraFeatures is hard-coded; a camera feature BETA with CAMERA_SCAN OFF is untested — build-logic FeatureFlagLevels rejects a camera feature above OFF while CAMERA_SCAN is OFF; a new flag is a planned change that would revisit this.
  - `[low]` `[reject]` (blind) Draft restore loses the selected sticker and face — same as the intent-layer row; added parameters for a minor reset.
  - `[false]` `[reject]` (blind) Editing-to-Editing changes on the same tab drop an unaccepted edit — TimedOut/Failed reopen on `accepted`, the stickers the Learner just submitted, so nothing is lost.
  - `[false]` `[reject]` (blind) A late onEdit could plant a stale draft after enterColors — onEdit only fires from palette taps in a composed editor; enterColors runs from Home, where no editor exists, and show() clears the draft.
  - `[false]` `[reject]` (blind) Turkish test proves less than claimed and is not recorded — the item is the key path at GuidedScanScreen.kt:763, which the test exercises; the result is in Design Notes; promptColorName uses the UI locale on purpose.
  - `[low]` `[reject]` (blind) Test hygiene: fully qualified names and duplicated setup in tests — cosmetic.
  - `[low]` `[defer]` (blind) No test through handle(); no direct onChange contract test — handle() part grouped with the deferred route-level entry; onChange is exercised by AppShellSolveTest.unacceptedEditorStickersSurviveATabSwitch.
  - `[false]` `[reject]` (edge) Earlier freeze then Unavailable keeps held — same refutation as the blind row (onDispose ends the freeze).
  - `[false]` `[reject]` (edge) startRetake skips the hop while the review stays equal — no path changes the phase during the inert launching state except the hop itself.
  - `[false]` `[reject]` (edge) onRetake throws after launching = true — analyzerExecutor.execute rejects only after shutdown in onCleared, when the Activity is gone.
  - `[false]` `[reject]` (edge) Back consumed while launching leaves no way out — Back is ignored only for the one hop before the viewfinder opens.
  - `[low]` `[reject]` (edge) Selected sticker and compact face reset after a tab switch — same as above; added parameters for a minor reset.
  - `[medium]` `[patch]` (gap) ScanRouteTest back-out tests only check `review:screen`, which RetakeMode also carries — added `review:accept` exists and `review:retake:start` does not exist after back-out and Close (ScanRouteTest 11/11 pass).
  - `[medium]` `[defer]` (gap) The handle() switch to vm.onCaptured is not tested where it is wired — deferred as PO device item with route-test hint.
  - `[medium]` `[defer]` (gap) The onCreate switch to loadOrSkip() is not tested — needs a Player seam in the Activity; deferred as PO device item.

## Design Notes

Locale (issue-158-deferred-calibration-locale-root): `ColorCalibrationScreenTest.whitePromptResolvesUnderATurkishDefaultLocale` passes under `Locale.setDefault(tr-TR)` with no code change: Kotlin's no-arg `String.lowercase()` is locale-invariant, so `GuidedScanScreen.kt` stays as is and the test guards it.

Device-only parts (PO, deferred): the real Unavailable race timing, a real `MediaActionSound` failure, and the retake hop on hardware are not reproducible in JVM tests; batch with the combined human-verification session (#106).

## Verification

**Commands:**
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*FeatureFlags*' --tests '*AppShellSolve*' --tests '*AppShellRecognize*' --tests '*WelcomeScreen*' --tests '*ShutterSound*' --tests '*GuidedScan*' --tests '*ScanReviewScreen*' --tests '*SolveFlowViewModel*' --tests '*ColorCalibration*'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** All nine ai-b1 action items are resolved. A single `FeatureFlags.showsBeta` rule now drives the BETA label at Solve (Quick Scan, Scan), Welcome and Recognize *Start from my cube*, and Library *Recognize case*: a camera feature also shows BETA while `CAMERA_SCAN` is BETA (EXPERIENCE.md:137). Guided Scan freezes a capture only in the viewfinder, so a last face after Unavailable publishes the review at once. The shutter load never throws. Retake mode stays inert after *Retake N faces*, so the review controls do not flash. The Solve editor keeps unaccepted stickers across tab switches. The locale item was already correct: Kotlin's no-arg `lowercase()` is locale-invariant. It is proven by `ColorCalibrationScreenTest.whitePromptResolvesUnderATurkishDefaultLocale` with no code change.

**Files changed:**
- `core/application/.../port/FeatureFlags.kt`: `showsBeta` extension.
- `app/.../ui/shell/AppShell.kt`, `app/.../ui/welcome/WelcomeScreen.kt`: use `showsBeta` at the five BETA sites.
- `app/src/camera/.../GuidedScanActivity.kt`: `GuidedScanViewModel.onCaptured` phase guard; `loadOrSkip` in onCreate.
- `app/src/camera/.../ShutterSound.kt`: `loadOrSkip()`.
- `app/.../ui/scan/ScanReviewScreen.kt`: inert launching state in retake mode.
- `app/.../ui/solve/SolveFlowViewModel.kt`, `ui/solve/SolveScreen.kt`, `ui/editor/ManualCubeEditor.kt`: in-memory editor draft via `onChange`/`onEdit`, restored on `leaveSolveTab`.
- Tests: `FeatureFlagsTest`, `AppShellSolveTest` (old Quick Scan BETA assertion flipped), `AppShellRecognizeTest`, `WelcomeScreenTest`, `ShutterSoundTest`, `GuidedScanFlowTest`, `ScanReviewScreenTest`, `SolveFlowViewModelTest`, `ColorCalibrationScreenTest`, `ScanRouteTest` (review-pass patch).

**Review findings:** 28 findings (high 0, medium 5, low 11, false 12). 1 patch applied (medium: ScanRouteTest back-out assertions). 3 PO device items deferred (route-level Unavailable race, real MediaActionSound failure, retake hop on hardware). Every rejected finding and its reason is in the Review Triage Log.

**Follow-up review recommended:** false. Patched by verdict: high 0, medium 1, low 0.

**Verification:** The targeted test command passed (BUILD SUCCESSFUL; 0 failed, 0 skipped in every affected suite). After the patch, `./gradlew :app:testDebugUnitTest --tests '*ScanRouteTest*'` passed 11/11 and `./gradlew spotlessCheck build` passed (BUILD SUCCESSFUL).

**Residual risks:** After a tab switch the editor restores the stickers but not the selected sticker or the compact face. The Activity-level wiring of the race guard and of `loadOrSkip` is covered only by ViewModel and unit tests, plus the PO device items.
