# Epic 10 Context: Scan my cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let a Learner scan a cube face by face, correct and calibrate the read, solve from the scan, and verify physical Moves with a Quick Check, all on-device and offline. Camera recognition is v1.1 and flag-gated: every story merges to `main` while every camera flag stays OFF in release, so tester-fix releases remain exactly as camera-free as v1.0. Camera features reach Learners only when the PO raises a flag at a release, backed by test-matrix evidence (GA if accuracy targets are met, otherwise BETA or OFF).

## Stories

- Story 10.9: Keep camera code out of a release whose camera flags are OFF (runs first)
- Story 10.10: Live recognition baseline and fixture recorder (runs second)
- Story 10.1: Classify stickers and assemble a Scan Result
- Story 10.2: Guided 6-face Scan viewfinder
- Story 10.3: Review and correct a Scan Result
- Story 10.4: Calibrate colors for my cube
- Story 10.5: Scan to solve
- Story 10.6: Quick Check evidence and Certainty
- Story 10.7: Quick Check in Physical-cube Mode and playback
- Story 10.8: Recognition test matrix and GA/BETA decision
- Story 10.11: Raise a camera feature above OFF for a release

## Requirements & Constraints

- **Offline / permissions:** no `INTERNET` permission ever. v1.0 declares no runtime permission; from v1.1 the camera is the only one, and a release declares it only when a camera feature's flag is above OFF.
- **Privacy:** no account, no analytics/ads, nothing leaves the device; Play Data safety stays "No data collected". Camera frames are processed in memory only: never persisted, saved as photos, sent, or logged (release logs contain no samples or Cube States). ML Kit and Play-services ML are denylisted because they can send usage data.
- **Size:** install < 30 MB (`:app:checkReleaseSize` < 30,000,000 bytes); CameraX DEX counts only once a release flag is raised. Debug grows ~16 MB with CameraX; acceptable.
- **Release safety while all camera flags are OFF (proven by CI, not review):** release and benchmark merged manifests have no `CAMERA` and no `uses-feature android.hardware.camera*`; `releaseRuntimeClasspath` has no `androidx.camera:*`, `com.google.mlkit:*`, `com.google.ai.edge.litert*`, `org.tensorflow*`, `org.opencv*`; `docs/privacy-facts.json` is unchanged (`runtimePermissions: []`); the existing release-gate stories' "no runtime permission" and "gate 6 N/A" claims stay true word for word.
- **When a flag is raised:** `CAMERA` plus `uses-feature android.hardware.camera.any required="false"` appear; `:app:checkPrivacyFacts` fails until `:app:writePrivacyFacts` regenerates facts with `runtimePermissions: ["android.permission.CAMERA"]`, which in turn forces the public site policy to match. The privacy chain is build-enforced.
- **Accuracy:** confidently-wrong rate ≤ 0.1%; only categorical confidence leaves the recognition module. Live baseline go/no-go: below 90% per-sticker on the Lenovo means re-plan before 10.2; 90–99% means BETA-only.
- **Reference devices:** Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (mid, gating), Galaxy Tab S7 FE (reported only). Emulator cameras are never acceptance evidence.
- Every scan flow must have a working manual fallback (*Enter colors instead*); no unvalidated Scan Result is ever used.

## Technical Decisions

- **Flags compose the build:** `FeatureFlagLevels.byBuildType` (in `build-logic`) is the only source of flag levels. If a build type has any of `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` above OFF, `build-logic` adds the camera source set (`app/src/camera/kotlin`, `app/src/camera/AndroidManifest.xml` with `CAMERA` + optional camera `uses-feature`) and CameraX dependencies to that build type only; otherwise neither. Today: debug GA; release and benchmark OFF.
- `PLACEMENT`, `QUICK_SCAN`, `CASE_RECOGNITION` may be above OFF only when `CAMERA_SCAN` is above OFF in the same build type; `build-logic` fails the build naming the offending flag.
- **ManifestPolicy:** keeps keying on `variant.debuggable`. A non-debuggable variant may declare `CAMERA` (and nothing else) only when its build type has a camera flag above OFF; with all OFF it still allows no runtime permission (existing behavior unchanged).
- Build-logic behavior is proven with Gradle TestKit fixtures (`./gradlew -p build-logic test`); CI's "Verify build wires the gates" step must list any new check. Fallback if per-build-type AGP 9 wiring proves fragile: release `tools:node="remove"` on the manifest, but that does not keep CameraX off the classpath, so it is not sufficient alone.
- **Camera port:** only the CameraX adapter touches pixels, and it lives only in `app/src/camera/`, never `app/src/main`. `:app` main reaches the camera through the `ScanLauncher` port (spine AD-9) with an optional Hilt binding provided by the camera source set. No binding means every scan entry point is hidden; a flag above OFF without a binding must fail at startup in debug.
- Adapter emits neutral `FaceSample`s (center color, up-neighbor color, 9 CIELAB samples in viewfinder order, quality flags, pose). Pure-JVM recognition (`StickerClassifier` with calibration as a parameter, `ScanAssembler` as the sole rotator into URFDLB `Facelets`, `ScanResult.Full|Partial`) lives in `:core:domain` (`dev.cubentia.domain.recognition`), extending existing types; it may ship unused in release (no permission, no CameraX, no ML).
- Reducer rejects `ScanAccepted` unless the Full result is valid, every UNCERTAIN sticker is corrected/confirmed, and `basedOnRevision` is current. Scan is a sub-flow returning `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` via typed navigation result; only the caller dispatches; the flow never touches a session.
- Color Calibration is owned solely by `CalibrationRepository`, written only by `UpdateCalibration`/`ResetCalibration`, never in `SessionState` or Checkpoints; Room 7 → 8 with exported schema and migration test.
- The debug-only fixture recorder (former `spikeScan` activity) lives in the debug camera source set and writes `Fixtures/live/`; no release or benchmark build contains it. The Story 1.9 `spikeScan` prototype is deleted by 10.2.
- No feature checks `BuildConfig` directly; BETA labeling uses the single shared BETA label component (introduced for *Test me*). CameraX binds off the main thread; no new app-frame StrictMode violations (#107 is the baseline).
- Every PR must pass build, Spotless/ktlint, lint, JVM tests, Kover ≥ 80% on `:core:*`, the offline/privacy checks and the AAB size check.

## UX & Interaction Patterns

- A flag that is OFF means no entry point, string, chip, rung or permission rationale visible (UX-DR19); same for devices with no bindable camera. With `CAMERA_SCAN` OFF, Solve shows only *Enter colors*, exactly as today.
- Before the first permission request, show the rationale from content strings: "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." with *Continue* and *Enter colors instead*. Denied: explanation card with *Enter colors instead* (plus Settings deep-link if permanently denied).
- At BETA, *Enter colors* is primary; scan carries the shared BETA label and tooltip "May misread colors in poor light. Manual entry always works."
- Coaching re-prompts (glare, blur, orientation, low light) instead of errors; Quick Check is always optional.

## Cross-Story Dependencies

- 10.9 must merge before any CameraX code lands on `main`; 10.10 depends on 10.9; 10.1 needs 10.10's live fixtures; 10.2 needs 10.9 + 10.1; 10.3/10.6 need 10.1; 10.4 needs 10.1 + 10.3; 10.5 needs 10.2 + 10.3; 10.7 needs 10.6 + 10.2 and follows #139.
- 10.11 needs 10.8's per-feature recommendation and must not run before Stories 12.3, 12.4, 12.5 and 13.6 are done; its flag-raise PR merges last before the tag, with the `hebelmx/cubentia-site` privacy PR right after.
- Tester-fix PRs always merge first; camera branches rebase. Shared hot files: `AppShell.kt`, `LessonScreen.kt`, `strings/en.json`, `RubikDatabase.kt` (Room version goes to whoever merges first).
- Epic 11 (Quick Scan, Placement, Case recognition) builds on this epic's pipeline and flag rules.
