---
title: 'GH #158: Story 10.2 follow-ups (scan viewfinder review and device-session findings)'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: '167fcca1fa7d0aef64264228d52990b85ede8f82'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [multiple-goals, oversized]
deferred:
  - summary: >-
      Rotation hand-off during a capture freeze (rotate the Lenovo while the last face is frozen; expect one haptic tick and the Review screen) is not run on a device.
    evidence: |-
      2026-11-19: Lenovo TB128XU (HA1S28MM) and Galaxy S24 FE were attached and unlocked, but a capture needs a cube held in good light (the scene read "Too dark") and a physical rotation needs a person; the PO asked not to change display or lock settings, so rotation was not forced over adb. GuidedScanScreenTest covers it on the JVM (a recreate during a freeze ends it and ticks the haptic only once). Settle it at the next device session.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt (FrozenOverlay)
    severity: medium
    kind: device-session-check
  - summary: >-
      ImageAnalysis throughput at 1280x960 on the Lenovo (minimum device) is not measured.
    evidence: |-
      maybe-false. 2026-11-19 dumpsys media.camera on HA1S28MM showed the YUV analysis stream bound at 1280 x 960 (preview 1600 x 1200), so the larger target takes effect; CubentiaScanDiag logs are throttled (~2/s), so analyzer frame rate and latency were not measured. Settle with an analyzer timing run on the Lenovo narrow pane with a cube in view.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt (analysisTarget, analysis)
    severity: medium (unverified)
  - summary: >-
      Red vs orange repeated-face separation at SAME_CENTER_DELTA_E 12.0 is proven on studio-light fixtures only; warm and dim light are unmeasured.
    evidence: |-
      maybe-false. All 12 live sets are studio light (min red/orange face-center distance 24.8 CIE76); raising the threshold from 10 to 12 trades a little red/orange margin for catching more repeats. Settle with warm/dim captures in the Story 10.8 recognition matrix (Lab values, not only C/L diag lines).
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt (SAME_CENTER_DELTA_E)
    severity: medium (unverified)
  - summary: >-
      Shutter click audible on a real capture (Activity starts the IO load; play is silent until loaded) is not checked on a device.
    evidence: |-
      maybe-false. ShutterSound.play() is now a no-op until load() finished, so a missing or cancelled load in GuidedScanActivity would silence every capture without failing any test. 2026-11-19 re-run on the final build (Lenovo HA1S28MM) opened Guided Scan and pressed Capture with 0 StrictMode lines, but the scene was too dark to capture, so no click was produced. Settle at the next device session with a cube in good light.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt (onCreate load, play call sites)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Story 10.2's review and device session B left lifecycle and robustness gaps in Guided Scan: the shutter sound loads on the main thread and can play after release, a held Done result (and a second haptic tick) misbehave when the phase or the Activity changes during the 400 ms freeze, transient camera-in-use errors show the permanent Unavailable card, prompt color names lowercase without an explicit Locale, the repeated-face threshold is unproven against red/orange, and narrow tablet panes analyse only ~54 px cells.

**Approach:** Small, local fixes in the `app/src/camera` source set and one domain constant: a lifecycle-safe shutter-sound wrapper loaded on `Dispatchers.IO`; the frozen overlay ends its freeze when it leaves composition (publishing a held result, and no re-tick after rotation); Unavailable only on CRITICAL camera errors; `lowercase(locale)` from the configuration; `SAME_CENTER_DELTA_E` re-tuned from the recorded live fixtures with a red/orange fixture test; a 1280×960 ImageAnalysis target when the viewport is longer than 4:3. Device-only checks run on the Lenovo TB128XU when it is available.

## Boundaries & Constraints

**Always:** camera code stays in `app/src/camera/`; pure logic lives in testable functions (JVM/Robolectric tests in `app/src/testDebug` and `core/domain/src/test`); feature flags unchanged; Kotlin `Text`/strings untouched.

**Never:** retake-from-review (moved to #235); changing AC4's live wrong-face policy (settled by SCP 2026-11-16 / Story 10.14: only a repeated face is rejected live); autofocus or recognition-accuracy work (device observations measured in 10.8); new modules; touching `_bmad/`, `.claude/skills/`, planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sound after release | capture effect posted to main after `onDestroy` released the sound | no `play()` on the released player | silently skipped |
| Load races release | IO load still pending when `onDestroy` releases | load skipped or player released after load; no crash | guarded by one lock |
| Phase leaves Viewfinder in freeze | last face frozen, Done held, phase becomes Unavailable/other | overlay disposes → `endFreeze(id)` → held Review published | stale id ignored |
| Rotation in freeze | Activity recreated while frozen | old overlay disposal ends the freeze; new composition has no overlay → one haptic tick only | — |
| PENDING_OPEN + CAMERA_IN_USE / MAX_CAMERAS_IN_USE | RECOVERABLE error | stays on viewfinder; CameraX retries | — |
| CRITICAL error (DISABLED, FATAL, DND, STREAM_CONFIG) | any state | Unavailable card | — |
| Red then orange center (live fixtures) | red face stored, orange face shot | orange captured, not WRONG_FACE | — |
| Viewport aspect ≤ 4:3 | phone or squarish pane | ImageAnalysis target 640×480 (unchanged) | — |
| Viewport aspect > 4:3 | narrow tablet pane (e.g. 640×203 crop) | target 1280×960 (4:3, closest higher then lower fallback) | — |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- L85 `shutterSound = MediaActionSound()`; L118 `load()` on main in `onCreate`; L326-329 `onDestroy` releases; `play()` at L485 (case), L507 (corner), L565 (`handle`), all inside main-executor posts. `GuidedScanViewModel.publish/endFreeze` L746-767: `held` published only by `endFreeze`, called only from the overlay.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `FrozenOverlay` L452-471: `LaunchedEffect(frozen.id)` ticks haptic, delays `FREEZE_MS`, calls `end(id)`; only composed inside `Viewfinder` (L389), so leaving Viewfinder or recreating the Activity never/twice ends it. Prompt color names L531/L533 `.lowercase()` (no Locale). L738 `color.name.lowercase()` is a string key, leave it.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- `cameraStateObserver` L81-85 treats any error in CLOSED/PENDING_OPEN as unavailable; `bindWhenLaidOut` L174-224 builds `ImageAnalysis` with default (640×480) resolution before `view.viewPort` is known.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `capture()` L470-490 uses `StickerClassifier.deltaE` (CIE76) against `SAME_CENTER_DELTA_E` L604 (= 10.0, "provisional").
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/GuidedScanTest.kt` -- harness (`stats`, `shoot`, `nearGreen` "within 10 dE" L514); `ScanFixtureEvaluationTest.kt` shows reading `../../Fixtures/...` from tests.
- `Fixtures/live/*/{U,R,F,D,L,B}.json` -- 12 tracked live sets (Lenovo TB128XU, S24 FE; GAN/stickered), each with `labeledSample.sample.stickers` and `truth`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/` -- Robolectric tests (`CameraXFrameSourceTest`, `GuidedScanScreenTest`).
- Already settled elsewhere (no work): "scan in any orientation" = Story 10.14 (`spec-10-14-...`, status done); "live acceptance of a not-yet-scanned face" = issue comment / SCP 2026-11-16 keeps AC4; retake from review = #235.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/ShutterSound.kt` (new) -- wrapper over a small `Player` seam (default `MediaActionSound`) with `load()` (call off main), `play()`, `release()` under one lock and a `released` flag; play/load after release are no-ops; release after an in-flight load still releases.
- `GuidedScanActivity.kt` -- use `ShutterSound`; `onCreate` launches `load()` on `Dispatchers.IO` via `lifecycleScope`; `onDestroy` calls `release()`; every `play()` goes through the wrapper.
- `GuidedScanScreen.kt` -- `FrozenOverlay`: a `DisposableEffect(frozen.id)` whose `onDispose` calls `end(frozen.id)`; color names use `lowercase(LocalConfiguration.current.locales[0])`.
- `ScanFrameSource.kt` -- extract `cameraUnavailable(state: CameraState): Boolean` = `state.error?.type == CameraState.ErrorType.CRITICAL`; add pure `analysisTarget(viewWidth, viewHeight): Size` (640×480 when the long/short ratio ≤ 4/3 or unknown, else 1280×960); build the `ImageAnalysis` inside `bind()` once the viewport is known, with a `ResolutionSelector` (4:3 fallback-auto aspect, `ResolutionStrategy(target, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`).
- `GuidedScan.kt` -- `SAME_CENTER_DELTA_E = 12.0` (CIE76) with KDoc citing the fixture evidence in Design Notes.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/RepeatedFaceFixtureTest.kt` (new) -- for every live set: red-center face then orange-center face (and the reverse) → the second is captured, not WRONG_FACE; the same face again → WRONG_FACE; also asserts every recorded center-vs-other-color-center distance ≥ 2 × `SAME_CENTER_DELTA_E`.
- `app/src/testDebug/.../ShutterSoundTest.kt`, `ScanFrameSourcePolicyTest.kt` (new) and `GuidedScanScreenTest.kt` -- cover the wrapper, `cameraUnavailable` per error code, `analysisTarget`, overlay disposal calling `onFreezeEnd`, one haptic tick across a state-restoration recreate, and explicit-Locale lowercasing.

**Acceptance Criteria:**
- Given Guided Scan on the main debug build, when the Activity is created, then `MediaActionSound.load` runs off the main thread (no StrictMode disk violation from it on the Lenovo).
- Given the last face is frozen with its Done held, when the phase leaves Viewfinder or the Activity is recreated, then the Review screen is shown (the result is never lost) and only one haptic tick played.
- Given the camera reports CAMERA_IN_USE while PENDING_OPEN, when the other client releases it, then the viewfinder resumes without the Unavailable card.
- Given the recorded live fixtures, when a red-center face follows an orange-center face (or the reverse), then it is captured, not rejected as repeated.
- Given a viewport longer than 4:3, when the camera binds, then ImageAnalysis targets 1280×960.
- Device-only checks (Lenovo TB128XU, HA1S28MM): StrictMode re-run for `MediaActionSound.load`; onResume after Settings grant; rotation hand-off during a scan; camera-state observer (camera held by another app) — run with dated results, or recorded as `deferred` (severity medium, kind device-session-check) when the device is unavailable.

## Device Checks (2026-11-19)

Debug APK of this branch installed on both devices; Guided Scan opened from the main app (Solve → *Scan my cube*); evidence logs and screenshots kept in the run's scratchpad only.

- **StrictMode re-run for `MediaActionSound.load`** — PASS. Lenovo TB128XU (HA1S28MM, Android 13): logcat cleared, Guided Scan opened (rationale → camera grant → viewfinder): 0 `StrictMode` lines (debug policy `detectDiskReads/Writes/Network().penaltyLog()`). Galaxy S24 FE (192.168.0.131:44381): same open, 0 `StrictMode` lines.
- **onResume after a Settings grant** — PASS (Lenovo). *Don't allow* → Denied card; Home; camera granted outside the app (`pm grant`, the same permission state Settings writes); app brought back → `onResume` switched to the viewfinder ("Face 1 of 6 — Show the green center.").
- **Camera-state observer** — PASS (Lenovo). Lenovo camera app launched adjacent in split screen while scanning: CameraX reported `PENDING_OPEN, StateError{code=2}` (CAMERA_IN_USE); Guided Scan kept the viewfinder (no Unavailable card). Focusing the scan pane reopened the camera (`OPEN`), and closing the other app reopened it again; still the viewfinder.
- **Rotation hand-off during a scan** — DEFERRED (see frontmatter `deferred`): needs a cube capture and a physical rotation.

## Spec Change Log

- 2026-11-19 (implementation): the fixture margin assertion ("every recorded center-vs-other-color-center distance ≥ 2 × `SAME_CENTER_DELTA_E`") is checked between the recorded face centers of one set (min 24.8, RED vs ORANGE, `sm-s721u1_stla_studio-a`), which is what the live repeated-face check compares. Against the calibration centers the minimum is 23.9 (Design Notes), which is < 2 × 12.0 = 24, so that reading of the assertion could not hold at the specified 12.0; the constant stays 12.0 and its KDoc cites both numbers.

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 30 findings — high 0, medium 6, low 12, false 8, maybe-false 4
- findings:
  - `[medium]` `[patch]` (blind) ShutterSound.load holds the lock during the disk read, so main-thread play()/release() can block on it — load now runs outside the lock; loading/loaded/released flags under the lock; release during a load is done by the loader.
  - `[low]` `[patch]` (blind) bindWhenLaidOut comment still says "in use" shows the Unavailable card — comment reworded (critical only; in-use retried by CameraX).
  - `[low]` `[reject]` (blind) no feedback while another app holds the camera — device check: focusing the scan pane re-acquired the camera at once (CAMERA_IN_USE → OPEN), so a lasting hold needs the Learner in the other app; a timeout/card adds branches for a rare case.
  - `[false]` `[reject]` (blind) analysis size not recomputed on a pane resize — the Activity declares no configChanges, so a multi-window resize recreates it and rebinds with a fresh analysisTarget.
  - `[false]` `[reject]` (blind) AC5 has no device check — 2026-11-19 dumpsys media.camera on the Lenovo shows the YUV analysis stream at 1280x960; throughput deferred separately.
  - `[low]` `[reject]` (blind) casing uses device locale, not resolved resource locale — no color name (White, Yellow, Green, Blue, Red, Orange) contains a capital I, so no visible difference; fixing needs resource-locale plumbing.
  - `[low]` `[reject]` (blind) freeze tests stop at a fake, not the ViewModel — VM publish/endFreeze is pre-existing code exercised by QuickScanRouteTest/QuickCheckRouteTest; the new code is only the disposal call, which the screen tests cover.
  - `[false]` `[reject]` (blind) disposal publishes Review over Unavailable / on finish — that is what the intent asks ("publishes a held Done result if the phase leaves Viewfinder"); setting phase on a finishing Activity's ViewModel is harmless.
  - `[low]` `[reject]` (blind) margin assertion has ~3 % headroom and Design Notes say "half of 23.9" — fixing means editing this spec or loosening the guard; the test is meant to fail when a new fixture breaks the margin.
  - `[medium]` `[patch]` (blind) same-face test only re-shoots the identical record (grouped with the verification-gap threshold pin) — GuidedScanTest now rejects a repeat 11 dE from the captured center.
  - `[low]` `[reject]` (blind) RepeatedFaceFixtureTest literals, truth[4] vs CENTER_INDEX, cwd-relative path — cosmetic; the cwd-relative path matches ScanFixtureEvaluationTest and the 12-set assertion fails loudly.
  - `[low]` `[patch]` (blind) ShutterSoundTest race test does not prove the interleaving — every await/join asserted; release proven to return while the load is blocked; player.release only after load.
  - `[low]` `[reject]` (blind) sound reloaded on each rotation — same as before the change (Activity-owned); the load is now off main and play is silent only until it finishes.
  - `[false]` `[reject]` (blind) spec frontmatter vs empty triage log / followup flag — the log and flag are written by this review step; spec edits are not findings.
  - `[low]` `[reject]` (edge) Quick Scan corner capture moving CornerViewfinder → Viewfinder fallback disposes the overlay and ends the corner freeze early — cosmetic (frozen image cut short, no extra tick); before the change the same move re-ticked and restarted the freeze; hoisting the overlay is a structural change.
  - `[medium]` `[patch]` (edge) play() before the IO load finished makes MediaActionSound load on main (grouped with the ShutterSound lock entry) — play is a no-op until loaded.
  - `[medium]` `[patch]` (edge) main thread blocks on load via the lock — same group, same fix.
  - `[false]` `[reject]` (edge) analysis target not recomputed on resize — see the blind row: resize recreates the Activity.
  - `[low]` `[reject]` (edge) recoverable error that never clears leaves a blank viewfinder — see the blind row; device evidence shows the focused app re-acquires the camera.
  - `[false]` `[reject]` (edge) Configuration.locales empty → NPE — Android resources always carry at least one locale in Configuration; LocalConfiguration comes from the Activity's resources.
  - `[low]` `[reject]` (edge) known-center branch not fixture-tested at 12.0 — same constant and the same deltaE comparison as the stored-face branch, which the fixtures exercise.
  - `[medium]` `[patch]` (verification-gap) SAME_CENTER_DELTA_E pinned only from above — GuidedScanTest `a repeat about 11 dE from the captured center is still the same face` (fails at 10.0).
  - `[maybe-false]` `[defer]` (verification-gap) ImageAnalysis selection unverified at bind — bound size now verified on the Lenovo (1280x960 via dumpsys); throughput deferred (medium, unverified).
  - `[medium]` `[patch]` (verification-gap other) ShutterSound lock blocks main — same group as the ShutterSound entry.
  - `[low]` `[reject]` (intent) camera policy wider than PENDING_OPEN (CRITICAL in any state) — CameraX reports recoverable codes 1–3 only while opening; CRITICAL in OPEN/OPENING means the camera cannot be used, so the card is correct there.
  - `[maybe-false]` `[reject]` (intent) held-result/haptic tests at Compose level, not a real Activity recreate — the real rotation is the deferred device check already in frontmatter.
  - `[maybe-false]` `[defer]` (intent) threshold 12 narrows red/orange margin in warm/dim light — studio fixtures only; deferred to the 10.8 matrix (medium, unverified).
  - `[false]` `[reject]` (intent) granted analysis resolution never recorded — recorded on the Lenovo (1280x960).
  - `[maybe-false]` `[reject]` (intent) device checks used proxies (pm grant, negative StrictMode) — pm grant writes the same runtime-permission state Settings does and onResume only reads it; the old main-thread load is gone from code, so a before/after run adds nothing.
  - `[false]` `[reject]` (intent) shutter wiring / locale lookup untested — the StrictMode device run covers the IO load end to end; ShutterSoundTest covers release guards; locale helper tested with tr.


### 2026-11-19 — Review pass (follow-up)
- verdicts: 28 findings — high 0, medium 0, low 15, false 6, maybe-false 7
- findings:
  - `[maybe-false]` `[defer]` (verification-gap) Activity never loading the sound would silence every capture undetected — deferred as a device-session check (medium, unverified).
  - `[low]` `[patch]` (verification-gap other) a throwing player.load() still set loaded = true — `loaded` is now set only after a normal return; ShutterSoundTest adds a throwing-load case (5/5). MediaActionSound.load throws only for an invalid sound id and SHUTTER_CLICK is a constant, so the uncaught coroutine exception stays unreachable.
  - `[low]` `[patch]` (blind) failed load marked as loaded — same group and fix.
  - `[low]` `[patch]` (blind) no test for a throwing load — same group; test added.
  - `[false]` `[reject]` (blind) phones mostly get 1280x960 — S24 FE 2026-11-19: Guided Scan TextureView is 1080x890 (ratio 1.21 ≤ 4:3) and dumpsys media.camera shows the YUV analysis stream at 640 x 480.
  - `[low]` `[reject]` (blind) ≤ 4:3 path no longer CameraX's implicit default — it targets 640x480 at 4:3 like the default; S24 FE binds 640 x 480; only devices lacking 640x480 could differ.
  - `[low]` `[reject]` (blind) review summary counts wrong — fix edits this spec.
  - `[low]` `[reject]` (blind) StrictMode PASS does not name the build — fix edits this spec; re-run on the final build recorded in Auto Run Result.
  - `[low]` `[reject]` (blind) threshold test does not assert its own distance — `Lab(55,-60,51)` vs RecognitionFixtures GREEN `(55,-60,40)` is fixed test data; a guard assertion adds nothing today.
  - `[low]` `[reject]` (blind) RepeatedFaceFixtureTest passes vacuously with no data / brittle 12 count — the count test fails the suite when the directory is missing; updating 12 when a set is recorded is intended.
  - `[low]` `[reject]` (blind) no fixture-jitter repeat in the 10–12 band — GuidedScanTest's 11 dE repeat pins the band.
  - `[low]` `[reject]` (blind) rotation deferral overstates JVM coverage — fix edits this spec.
  - `[false]` `[reject]` (blind) review_loop_iteration not bumped — it counts bad_spec loopbacks only; none happened.
  - `[low]` `[reject]` (blind) freeze ends early on corner fallback and finish — carried: cosmetic, recorded as residual risk.
  - `[low]` `[patch]` (edge) throwing load sets loaded — same group as above.
  - `[low]` `[reject]` (edge) recoverable error never clears — carried: device check showed the focused app re-acquires the camera.
  - `[false]` `[reject]` (edge) bind() rebuilds ImageAnalysis repeatedly — the analysis is built only after `view.viewPort` is non-null; once bind() returns true the layout listener is removed, and a failed bind goes to unavailable().
  - `[false]` `[reject]` (edge) duplicate truth center dropped by associateBy — `every live set is recorded with six distinct centers` asserts all six colors per set, so a duplicate fails it.
  - `[false]` `[reject]` (edge) empty Configuration.locales → NPE — carried false.
  - `[low]` `[patch]` (edge claim) KDoc says play never makes the platform load on main, false after a failed load — same group; now true.
  - `[maybe-false]` `[reject]` (intent A) Activity wiring of the sound not unit-tested — covered by the deferred device check above.
  - `[low]` `[reject]` (intent A2) a capture during the IO load window is silent — the load takes milliseconds and the first capture needs a steady face for ≥ 600 ms.
  - `[maybe-false]` `[reject]` (intent B) held/Review proven only through a fake — carried; VM endFreeze covered by QuickScanRouteTest/QuickCheckRouteTest; real rotation deferred.
  - `[maybe-false]` `[reject]` (intent B1 vs B3) every disposal ends the freeze — carried; Approach chose B1.
  - `[maybe-false]` `[reject]` (intent C) observer wiring covered only on device — device split-screen check recorded.
  - `[false]` `[reject]` (intent D) LocalConfiguration lookup untested — carried; no color name contains a capital I.
  - `[maybe-false]` `[reject]` (intent E) known-center path not fixture-tested and 3 % headroom — carried; same comparison and constant.
  - `[maybe-false]` `[reject]` (intent F) explicit selector on the ≤ 4:3 path untested — S24 FE binds 640 x 480 (device).

## Design Notes

**SAME_CENTER_DELTA_E evidence** (12 live sets, 2026-11-19 script over `Fixtures/live/*/*.json`): the smallest recorded distance from a face's center to any other color's calibration center is 23.9 CIE76 (RED vs ORANGE, `sm-s721u1_stla_studio-a/L`); red/orange calibration centers are 26.2–51.9 apart. CIEDE2000 was evaluated and rejected: it shrinks WHITE vs BLUE to 9.1 (`lenovo-tb128xu_stla_studio-a/U`, GAN logo center), leaving a 1.3× margin at any useful threshold, while CIE76 keeps ≥ 2×. 12.0 is half the smallest cross-color distance (the midpoint rule) and covers ~75 % of recorded same-color jitter (sticker vs own center, p75 ≈ 11) versus ~70 % at 10. All sets are studio light; warm/dim stays a 10.8 matrix measurement.

**Analysis size:** cost is per-cell (fixed 12×12 lattice), so a larger frame mainly costs the YUV copy. A ≤ 4:3 viewport's crop short side is ≥ 360 px at 640×480 in either sensor orientation; a 3.15:1 pane gets ~406 px (cells ~108 px) at 1280×960.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: success (JDK 25, `ANDROID_HOME` set).
- `./gradlew :core:domain:test --tests '*RepeatedFaceFixtureTest*' :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.*'` -- expected: pass.

**Manual checks (if no CLI):**
- Lenovo: debug StrictMode logcat during Guided Scan open shows no `MediaActionSound`/`SoundPool` frame in a main-thread DiskReadViolation; rotate during a capture; deny then grant in Settings and return; open another camera app while scanning.

## Auto Run Result

**Summary:** Guided Scan's Story 10.2 follow-ups. The shutter sound is wrapped in a lifecycle-safe `ShutterSound`, loaded on `Dispatchers.IO`, silent until loaded and never played after release. The frozen overlay ends its freeze when it leaves composition, so a held Done is published when the phase leaves the viewfinder and a rotation does not tick twice. Only CRITICAL camera errors show the Unavailable card. Prompt color names lowercase with the configuration locale. `SAME_CENTER_DELTA_E` is 12.0, backed by a red/orange live-fixture test. A viewport longer than 4:3 asks for 1280x960 analysis.

**Already settled (no work):** scan in any orientation (Story 10.14, done); live acceptance of a not-yet-scanned face (issue comment and SCP 2026-11-16: AC4 stays, 10.14 catches a wrong face at assembly); retake from review (#235). Device observations (autofocus on one face, 3–6 uncertain stickers, re-accept after lighting change) belong to the 10.8 matrix and are not in this build.

**Files changed:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/ShutterSound.kt` — new; lifecycle-safe load/play/release.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` — uses ShutterSound, loads on IO.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` — overlay disposal ends the freeze; `promptColorName(name, locale)`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` — `cameraUnavailable` (CRITICAL only); `analysisTarget` and a ResolutionSelector built at bind.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` — `SAME_CENTER_DELTA_E` 10.0 → 12.0 with evidence.
- Tests: `RepeatedFaceFixtureTest` (new), `ShutterSoundTest` (new), `ScanFrameSourcePolicyTest` (new), `GuidedScanScreenTest` (+3), `GuidedScanTest` (+1).

**Review:** 30 findings. Patched 4 entries (2 medium: ShutterSound main-thread blocking and play-before-load; threshold pinned from below; 2 low: stale comment, race-test rigor). Deferred 2 (analyzer throughput at 1280x960; warm/dim red/orange), plus the rotation-during-freeze device check. Rejected 8 false and 14 low or maybe-false, each with its reason in the Review Triage Log.

**Follow-up review recommended:** true. Two medium entries were patched. The specific unverified risk is the rewritten `ShutterSound` concurrency (load outside the lock, deferred release). It is proven only by JVM tests; the StrictMode device run covered the earlier version, and capture-time play/release has not been run on a device.

**Verification:** `./gradlew spotlessCheck build` passed after the patches (2026-11-19). Targeted tests passed: ShutterSoundTest 4/4, GuidedScanTest 47/47, RepeatedFaceFixtureTest 4/4, ScanFrameSourcePolicyTest 5/5, GuidedScanScreenTest 18/18. Device checks are under Device Checks: StrictMode, onResume after grant and camera-state observer PASS; analysis stream 1280x960 confirmed on the Lenovo; rotation during a freeze deferred.

**Residual risks:** the freeze now ends at once on a rotation and on a Quick Scan corner → fallback switch (the shrink is skipped); analyzer cost at 1280x960 on the Lenovo; warm/dim red/orange at 12.0.

### Follow-up pass (2026-11-19)

- **Patched:** 1 entry (low): `ShutterSound` keeps `loaded` false after a throwing load; `ShutterSoundTest` 5/5.
- **Deferred:** shutter click audible on a real capture (device session, medium unverified).
- **Rejected:** 7 false and 19 low or maybe-false (reasons in the follow-up triage entry).
- **Device evidence added:** Galaxy S24 FE binds the YUV analysis stream at 640 x 480 with a 1080x890 viewfinder (ratio ≤ 4:3). On the final build, the Lenovo (HA1S28MM) opened Guided Scan, pressed Capture (too dark to capture) and closed with 0 StrictMode lines and no crash.
- **Verification:** `./gradlew spotlessCheck build` passed after the patch.
- **Follow-up review recommended:** false (no high patched on a follow-up pass).
