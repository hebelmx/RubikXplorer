---
title: 'Calibrate colors for my cube'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_revision: '5e293985565323ce56fad9fc04a84d5115f91082'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Pastel cubes may never pass ColorPlausibility, whose chroma floors (20–45) were set from standard-cube fixtures only.
    evidence: |-
      Blind-hunter finding, unverified: no pastel cube is in Fixtures/live. FR-24 says pastel cubes work after calibration. Settle it by recording a pastel cube in a device session and running ColorCalibrationTest's fixture-plausibility check on it.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ColorCalibration.kt (ColorPlausibility.plausible)
    severity: medium (unverified)
  - summary: >-
      GuidedScanActivity calibration wiring (saved-calibration load at start, review → calibration → Save → rescan, preview rebind, Close back to review) and the MainActivity Settings wiring have no Activity-level test.
    evidence: |-
      Verification-gap and intent-alignment layers: replacing the startup load with `scan.saved = null` keeps every test green; only GuidedScanFlow, the ViewModel save path and the screens are tested. Same untested Activity-glue layer deferred since Story 10.2 (needs Hilt + CameraX under Robolectric); check on device in session B or add an Activity test harness.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt (onCreate load, Calibration phase, preview factory); app/src/main/kotlin/dev/cubentia/app/MainActivity.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** A scan self-calibrates from the six face centers, but a logo or printed center cannot be trusted (session A: a logo white gave 53/54), and nothing lets the Learner teach the app their cube's colors, save them, or reset them. Two logo centers leave the review all blank.

**Approach:** A Color Calibration phase in the Guided Scan Activity asks for each of the six colors in turn, samples the ring around the center cell's middle (logo left out), refuses an uneven or implausible swatch with a plain-sticker re-prompt, lets the Learner keep or retake each swatch, and *Save* calls `UpdateCalibration`. It opens from a "Colors look off — calibrate?" offer on the review and from Settings → Color calibration; Settings also has a confirmed *Reset calibration*. The calibration is a Room table (schema 8) behind `CalibrationRepository`, and every scan passes it to `SelfCalibration.from(samples, saved)`.

## Boundaries & Constraints

**Always:**
- Pure rules in `:core:domain` (Kover-covered): `CalibrationSession` (order = `GuidedScanPlan.steps` colors: green, red, blue, orange, white, yellow), `ColorPlausibility`, `CalibrationOffer`. UI only renders and forwards taps.
- Swatch rule for the color being asked: no ring reading or ring not `plain` → re-prompt; value outside the color's plausible region, or within `StickerClassifier.CLOSE_CALL_DELTA_E` of a swatch already kept → re-prompt; else the swatch is shown with *Keep* / *Try again*. Once a color was re-prompted, its prompt stays the plain-sticker text until a swatch is kept.
- Plausible regions (CIELAB; h = atan2(b, a) in degrees, C = √(a²+b²)): WHITE L ≥ 60, C ≤ 30; YELLOW L ≥ 55, C ≥ 45, h 80–125; ORANGE L ≥ 40, C ≥ 35, h 25–90; RED 20 ≤ L ≤ 75, C ≥ 20, h ≥ 340 or ≤ 50; GREEN L ≥ 30, C ≥ 20, h 115–185; BLUE L ≥ 15, C ≥ 20, h 215–315. Provisional (device session B); every non-center sticker in `Fixtures/live` must be plausible for its truth color.
- Ring = the center cell's samples outside its middle: a 12×12 lattice over the cell's central 80 %, minus the points inside its central 50 % square, body samples dropped as `stickerSamples` does, read by `CellReader`. Read on the face lattice `FrameReader.locate` finds; quality gate = `FrameQuality.assessShutter` (not ACCEPTABLE → existing coaching line, no swatch).
- Offer rule (`CalibrationOffer`), decided when review opens: `SelfCalibration.from(samples, saved)` is `NeedsCalibration`; or it guarded a center and `saved == null` (first scan of a logo/stickerless center); or the review opens with ≥ 6 blank stickers (`MANY_UNCERTAIN`, provisional). The offer is a row apart from net and palette; tapping it asks a confirm; confirming opens calibration; *Save* then restarts the scan (fresh controller, viewfinder); *Close* in calibration returns to the same review.
- `CalibrationRepository` (port in `:core:application`) is the only owner; `UpdateCalibration` (rejects, without writing, anything not six valid, plausible, pairwise ≥ 10 ΔE centers) and `ResetCalibration` are its only writers. Room table `color_calibration`, one row id = 1, 18 REAL columns; `AutoMigration(7, 8)`, exported `8.json`.
- Both the re-prompt decision and the review assembly use the loaded saved calibration (`defaultReprompt(samples, saved)`, `ScanReview.fromSamples(samples, saved)`); a failed load is treated as none.
- Settings rows *Color calibration* and *Reset calibration* show only when `CAMERA_SCAN` is above OFF and a `ScanLauncher` is bound; reset asks a confirm naming what is erased; a failed reset keeps the dialog with an error line.
- All new Learner text in `core/domain/src/main/resources/content/strings/en.json` via `ContentStrings`; no new `strings.xml` entries. Camera code only in `app/src/camera/`.

**Never:**
- No calibration in `SessionState`, `SessionEvent`, a snapshot or a Checkpoint; no session reference in scan/calibration code.
- No change to `StickerClassifier`, `ScanAssembler`, the close-call threshold, `SelfCalibration` semantics, `ResetProgress`, release flags, or the release classpath/manifest gates.
- No new Activity, no frame or pixel persisted, no numeric confidence shown to the Learner.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Plain plausible swatch | asking WHITE, ring plain L88 a-6 b-16 | swatch shown, *Keep* → next color (red after green… per plan) | — |
| Logo ring | ring not plain | prompt "Put a plain white sticker in the middle square.", no swatch | never an error |
| Bad white | asking WHITE, ring L76 a20 b71 | re-prompt (implausible) | — |
| Duplicate | asking ORANGE, ring within 10 ΔE of kept RED | re-prompt | — |
| Unreadable / poor frame | ring null, or shutter quality not ACCEPTABLE | re-prompt (null) / coaching line (quality) | — |
| Retake | swatch shown, *Try again* | swatch cleared, same color | — |
| Six kept | all colors kept | *Save* enabled → `UpdateCalibration` → Saved | save fails → error line, stays |
| Two logo centers | scan → `NeedsCalibration` | review opens with offer row | — |
| First guarded scan | one guarded center, no saved | offer row; with saved → no offer unless ≥ 6 blanks | — |
| Rescan with saved | saved present, center guarded | guarded color taken from saved | — |
| Reset | Settings *Reset calibration* → confirm | `ResetCalibration` deletes only `color_calibration` rows | failure → error line |
| Flag OFF | `CAMERA_SCAN` OFF or no binding | no calibration rows in Settings | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SelfCalibration.kt` -- `from(samples, saved)` already uses saved for guarded/missing centers (built for 10.4); `Calibrated.guarded`.
- `core/domain/.../recognition/{ScanReview,GuidedScan,StickerClassifier,CellReader,FaceSample}.kt` -- `ScanReview.fromSamples` (L72, no saved yet); `GuidedScanController(reprompt)` + `defaultReprompt(samples)` (L553); `GuidedScanPlan.steps`; `CLOSE_CALL_DELTA_E`, internal `deltaE`; `CellReader.read`, `CellReading.plain`; `CalibrationCenters`, `Lab.isValid`.
- `core/application/src/main/kotlin/dev/cubentia/application/{port/PreferencesRepository.kt,usecase/SetAlgorithmStatus.kt}` -- port + sealed-result + use-case pattern to mirror.
- `app/src/main/kotlin/dev/cubentia/app/data/{RubikDatabase,PreferencesEntity,PreferencesDao,RoomPreferencesRepository}.kt` -- single-row REPLACE entity and try/catch → sealed result repository to mirror; DB version 7 with AutoMigrations.
- `app/src/test/kotlin/dev/cubentia/app/data/SessionDatabaseMigrationTest.kt` -- `MigrationTestHelper` pattern (L209 6→7); `app/schemas/.../7.json` for every table's columns.
- `app/src/main/kotlin/dev/cubentia/app/di/PersistenceModule.kt` -- provide repository + use cases.
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt` + `ui/shell/AppShell.kt` (L196 Settings call; `content`/`ready.strings`) + `MainActivity.kt` -- add calibration rows; MainActivity injects `Optional<ScanLauncher>` and `ResetCalibration`.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanLauncher.kt`, `app/src/camera/.../CameraScanLauncher.kt` -- add `calibrationIntent(context)` (GuidedScanActivity + mode extra).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `GuidedScanViewModel` (controller val → resettable), `startCamera`/`cameraStarted`, `bind()` checks `phase !is Viewfinder`, review unbind `LaunchedEffect`, `analyze`, `handle`, `publish/endFreeze`. Activity glue has no test harness (10.2/10.3 deferral): keep decisions in `GuidedScanFlow`/ViewModel/domain.
- `app/src/camera/.../{GuidedScanFlow,GuidedScanScreen,FrameReader}.kt` -- `resultOf`, `GuidedScanPhase` sealed type, `Viewfinder` layout composable for reuse, `FrameReader.readAt` (lattice sampling, `stickerSamples`, `CELL_ROI_FRACTION`).
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- review UI; add optional offer row + confirm.
- Tests: `core/domain/src/test/.../recognition/{SelfCalibrationTest,ScanReviewTest,GuidedScanTest}.kt`, `ScanAccuracyTest` (reads `Fixtures/live`), `app/src/test/.../ui/settings/SettingsScreenTest.kt`, `app/src/test/.../ui/scan/ScanReviewScreenTest.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/{GuidedScanFlowTest,GuidedScanScreenTest,GuidedScanContractTest}.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../recognition/ColorCalibration.kt` -- `ColorPlausibility.plausible(color, lab)`, `CalibrationSession` (immutable: `current`, `kept`, `pending`, `plainSticker`, `offer(CellReading?)`, `keep()`, `retake()`, `calibration: CalibrationCenters?` when six kept), `CalibrationOffer.offered(samples, saved, review)` + `MANY_UNCERTAIN = 6`.
- `core/domain/.../recognition/{ScanReview,GuidedScan}.kt` -- `fromSamples(samples, saved = null)`; `defaultReprompt(samples, saved = null)`.
- `core/domain/src/test/.../recognition/ColorCalibrationTest.kt` -- every I/O row for session and offer; plausibility: every `Fixtures/live` non-center sticker plausible for its truth; bad-white and logo values refused; saved used for a guarded center in `fromSamples`.
- `core/application/.../port/CalibrationRepository.kt`, `usecase/{UpdateCalibration,ResetCalibration}.kt` + tests with a fake repository -- load/save/reset sealed results; Update rejects invalid/implausible/close centers without writing.
- `app/src/main/.../data/{ColorCalibrationEntity,CalibrationDao,RoomCalibrationRepository}.kt`, `RubikDatabase.kt` (v8, entity, dao, `AutoMigration(7, 8)`), `app/schemas/.../8.json` (generated by the build) -- persistence.
- `app/src/test/.../data/{SessionDatabaseMigrationTest,RoomCalibrationRepositoryTest}.kt` -- 7→8 keeps a row in every v7 table and creates an empty `color_calibration`; save/load round trip, replace, reset leaves preferences/progress rows.
- `app/src/main/.../di/PersistenceModule.kt` -- provide `CalibrationRepository`, `UpdateCalibration`, `ResetCalibration`.
- `app/src/main/.../scan/ScanLauncher.kt`, `app/src/camera/.../CameraScanLauncher.kt` -- `calibrationIntent`.
- `app/src/camera/.../FrameReader.kt` -- `centerRing(frame, square): CellReading?` (+ ImageProxy overload).
- `app/src/camera/.../{GuidedScanFlow,GuidedScanScreen,GuidedScanActivity}.kt` -- `GuidedScanPhase.Calibration(session, coaching, saveFailed, fromReview: Review?)`, `Review(review, offerCalibration)`; calibration layout reusing the viewfinder pane (prompt, progress "Color {n} of 6", swatch, *Capture*, *Keep*/*Try again*, *Save*, *Close*); mode extra; load saved calibration into the ViewModel at start; save via injected `UpdateCalibration`; rescan after Save from review; rebind camera when leaving review.
- `app/src/main/.../ui/scan/ScanReviewScreen.kt` -- `onCalibrate: (() -> Unit)? = null` → offer row + confirm dialog, tags `review:calibrate*`.
- `app/src/main/.../ui/settings/SettingsScreen.kt`, `ui/shell/AppShell.kt`, `MainActivity.kt` -- `CalibrationSettings(onCalibrate, onReset)` param (null hides rows), rows + reset confirm, tags `settings:calibration*`.
- `core/domain/src/main/resources/content/strings/en.json` -- calibration prompts/plain-sticker/progress/swatch/keep/try-again/save/save-failed/done, offer text/button/confirm, Settings row labels, reset confirm/description/failed.
- `app/src/testDebug/.../camera/` + `app/src/test/.../ui/{settings,scan}/` -- Robolectric: calibration screen per session state (prompt, plain-sticker prompt, swatch + keep, Save enabled only when six kept, save-failed line); flow: offer computed with saved, `resultOf` uses saved; review offer row + confirm; Settings rows hidden when null, reset confirm calls reset once and shows error on failure.

**Acceptance Criteria:**
- Given many uncertain stickers, a first scan with a guarded center, or Settings, when "Colors look off — calibrate?" or Settings → Color calibration is chosen, then the six colors are asked in turn from the center ring, an uneven or implausible swatch gets the plain-sticker re-prompt, each swatch is kept by the Learner, and *Save* calls `UpdateCalibration`.
- Given a saved calibration, when a rescan runs, then `SelfCalibration.from` receives it, and `grep -rnE "Calibration" core/application/src/main/kotlin/dev/cubentia/application/session` finds nothing.
- Given *Reset calibration* confirmed, when it runs, then only `color_calibration` rows are deleted.
- Given Room v7, when the app builds, then the DB is v8 with `app/schemas/dev.cubentia.app.data.RubikDatabase/8.json`, the 7→8 migration test keeps every v7 table's rows, and Settings shows no calibration row while `CAMERA_SCAN` is OFF.
- Given `./gradlew spotlessCheck build`, when run, then it passes (Kover ≥ 80 % on `:core:*`; release classpath/manifest gates unchanged).

## Spec Change Log

## Review Triage Log

### 2026-11-16 — Review pass
- verdicts: 20 findings — high 0, medium 7, low 8, false 4, maybe-false 1
- findings:
  - `[false]` `[reject]` (blind) Saved calibration is only a fallback; readable centers override it — intended: the spine's decision K self-calibrates from the scan's centers and the OQ-7 guard sends only untrusted centers to the saved swatch (Design Notes); changing it means editing this spec.
  - `[maybe-false]` `[defer]` (blind) Pastel colors cannot pass the chroma floors — no pastel fixture exists to tell; deferred as medium (unverified), settled by recording a pastel cube.
  - `[false]` `[reject]` (blind) A stickerless scan with plain centers and < 6 blanks gets no offer — the spec's reading (Flow 9 shows the stickerless first scan as many uncertain stickers; the stickerless test cube's white center is guarded); a plain-center stickerless scan self-calibrates fine (216/216 in session A).
  - `[medium]` `[patch]` (blind) `centerRing` passed compacted indices to `stickerSamples`, so body removal judged the wrong lattice places — now passes each sample's lattice position; test `center ring drops a black border by lattice place and keeps a clipped white plain`.
  - `[medium]` `[patch]` (blind) Calibration panel did not scroll; Keep/Save could fall off a short screen at large type — panel column now scrolls vertically.
  - `[low]` `[patch]` (blind) Analyzer read `phase` twice (check, then cast) and could throw during a phase change — one safe-cast snapshot.
  - `[low]` `[patch]` (blind) A queued capture result could land on a later step or a reopened calibration — the result applies only when the current session is the one that asked for it.
  - `[low]` `[reject]` (blind) A failed calibration load silently scans without the saved colors — rare database failure; the scan still works and the offer reappears; a retry path adds UI for a case never shown reachable.
  - `[low]` `[patch]` (blind) The swatch had no accessible name — swatch now carries the color name as its content description.
  - `[medium]` `[defer]` (blind) No integration test of Review → Calibration → Save → restarted camera — Activity glue, same root as the 10.2/10.3 deferral; added to `deferred`.
  - `[medium]` `[patch]` (edge) An overexposed white ring (all pixels masked) read as not plain, so a plain white sticker was refused forever — `centerRing` now applies `readAt`'s clipped-white rule; covered by the same FrameReader test.
  - `[medium]` `[patch]` (edge) Ring body removal used renumbered indices — same root as the blind `centerRing` row; same fix.
  - `[low]` `[patch]` (edge) Analyzer type-check then cast race — same root as the blind cast row; same fix.
  - `[low]` `[patch]` (edge) Stale capture result replaces a retaken swatch — same root as the blind stale-capture row; same fix.
  - `[medium]` `[defer]` (verification-gap) Startup load of the saved calibration untested — Activity glue (Hilt + CameraX under Robolectric); same deferral entry.
  - `[low]` `[patch]` (verification-gap) Settings calibration intent's mode extra untested — `GuidedScanContractTest` now asserts the calibration intent sets `CALIBRATION_MODE` and the scan intent does not.
  - `[false]` `[reject]` (intent) Rescan uses saved centers only for untrusted ones (reading 3b, not 3a) — same as the blind fallback row: the spec's documented choice from decision K.
  - `[false]` `[reject]` (intent) No stickerless detector; trigger is symptom-based — same as the blind stickerless row.
  - `[medium]` `[defer]` (intent) GuidedScanActivity and MainActivity calibration wiring untested at screen level — same Activity-glue deferral.
  - `[low]` `[reject]` (intent) The AppShell flag check that hides the rows is untested — a one-line `takeIf`; the screen test covers hidden rows when nothing is passed, and MainActivity checks the flag too.

## Design Notes

**Hosting:** calibration is a phase of `GuidedScanActivity`, not a new Activity, so permission, CameraX binding and metering are reused; Settings starts the same Activity in calibration mode, which finishes after *Save* or *Close*.

**Saved vs self-calibration:** the spine's decision K self-calibrates from the scan's own centers (robust to light); the OQ-7 guard sends an untrusted center to "a plain sticker (the 10.4 calibration flow)". So the saved calibration replaces only centers the scan cannot trust, through the existing `SelfCalibration.from(samples, saved)`. That is why the first-scan offer fires on a guarded center: that is where a saved swatch changes the read.

**"First stickerless scan":** no detector tells stickerless from stickered; Flow 9 shows it as many uncertain stickers, and the stickerless test cube's white center carries a logo (guarded). The offer rule covers both.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `ls app/schemas/dev.cubentia.app.data.RubikDatabase/8.json` -- expected: exists.
- `grep -rnE "SessionEvent|SessionHost|dev\.cubentia\.app\.session" app/src/camera app/src/main/kotlin/dev/cubentia/app/scan app/src/main/kotlin/dev/cubentia/app/ui/scan` -- expected: no output.

**Manual checks (if no CLI):**
- Device session B: stickerless cube first scan shows the offer; calibration with the logo white re-prompts for a plain sticker; rescan reads confidently.

## Auto Run Result

**Summary:** Color Calibration is a phase of the Guided Scan Activity. It asks for the six colors in plan order, reads the ring around the center cell's middle (logo left out, black body dropped, clipped white kept plain), refuses an uneven, implausible or duplicate swatch with "Put a plain {color} sticker in the middle square.", and needs an explicit *Keep* per swatch; *Save* calls `UpdateCalibration`. It opens from a confirmed "Colors look off — calibrate?" row on the review (offered on `NeedsCalibration`, a guarded center with no saved calibration, or ≥ 6 blanks; Save restarts the scan) and from Settings → Color calibration. Settings also has a confirmed *Reset calibration* (`ResetCalibration`). The calibration lives in Room table `color_calibration` (schema 8, AutoMigration 7→8) behind `CalibrationRepository`, and every scan passes it to `SelfCalibration.from(samples, saved)`.

**Files changed:**
- `core/domain/.../recognition/ColorCalibration.kt` -- `ColorPlausibility`, `CalibrationSession`, `CalibrationOffer`.
- `core/domain/.../recognition/{ScanReview,GuidedScan}.kt` -- saved calibration passed to `fromSamples` / `defaultReprompt`.
- `core/domain/src/main/resources/content/strings/en.json` -- calibration, offer and Settings copy.
- `core/application/.../port/CalibrationRepository.kt`, `usecase/{UpdateCalibration,ResetCalibration}.kt` -- port and single writers.
- `app/src/main/.../data/{ColorCalibrationEntity,CalibrationDao,RoomCalibrationRepository,RubikDatabase}.kt`, `app/schemas/.../8.json` -- Room v8.
- `app/src/main/.../di/PersistenceModule.kt`, `MainActivity.kt`, `ui/shell/AppShell.kt`, `ui/settings/SettingsScreen.kt` -- Settings rows and reset.
- `app/src/main/.../scan/ScanLauncher.kt`, `app/src/camera/.../CameraScanLauncher.kt` -- `calibrationIntent`.
- `app/src/main/.../ui/scan/ScanReviewScreen.kt` -- offer row + confirm.
- `app/src/camera/.../{FrameReader,GuidedScanActivity,GuidedScanFlow,GuidedScanScreen}.kt` -- center-ring reader, calibration phase, saved load, rescan.
- Tests: `ColorCalibrationTest`, `CalibrationTest`, `RoomCalibrationRepositoryTest`, `SessionDatabaseMigrationTest` (7→8), `ScanReviewScreenTest`, `SettingsScreenTest`, `CalibrationViewModelTest`, `ColorCalibrationScreenTest`, `FrameReaderTest`, `GuidedScanFlowTest`, `GuidedScanContractTest`.

**Review findings:** 20 findings. Patched: ring body removal by lattice place (medium), clipped white ring kept plain (medium), scrolling calibration panel (medium), analyzer phase snapshot (low), stale capture guard (low), swatch accessible name (low), calibration-intent test (low). Deferred: pastel plausibility (medium, unverified); Activity-level calibration wiring untested (medium, same root as the 10.2/10.3 deferral). Rejected: saved-as-fallback and symptom-based offer (both the spec's documented readings of decision K and Flow 9), load-failure retry (low, rare), AppShell flag check test (low, one-line).

**Follow-up review recommendation:** true -- three medium entries were patched on this first pass (patched counts: high 0, medium 3, low 4). Unverified risk: the center-ring reader's body removal and clipped-white rule are checked only on synthetic YUV frames, and the Activity calibration wiring (saved load, rescan, preview rebind) has no automated test.

**Verification:** `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL before and after patches (Kover gates passed). `app/schemas/dev.cubentia.app.data.RubikDatabase/8.json` exists. Session-isolation grep -- no output. Targeted after patches: `FrameReaderTest`, `GuidedScanContractTest`, `ColorCalibrationScreenTest` green.

**Residual risks:** Device session B not run (stickerless-cube first-scan offer, logo-white re-prompt, rescan confidence). `MANY_UNCERTAIN = 6` and the plausibility regions are provisional. A rescan with all-plain centers ignores the saved calibration by design (decision K).

