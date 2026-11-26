# Story 10.35 caller inventory

Taken on the baseline tree `d5662d8f614948fee3639c917c6257012e6af8f5` (branch `feat/10-35-retire-the-quick-scan-only-two-corner-recorder-paths`, before any deletion) with
`git grep -n -w <symbol> d5662d8f614948fee3639c917c6257012e6af8f5` over the whole repository (planning artefacts under `_bmad*`, `.claude/` and the
historical prose under `docs/` excluded; `docs/evidence/` and spike reports are history and stay). Callers are grouped by file;
categories are Android (`app/src/main`, `app/src/camera/**`), Android (debug recorder) (`app/src/camera/debug`), domain
(`core/*/src/main`), evaluation (`core/domain/src/evaluation`), fixture decoder (`LiveFixture.kt`), Gradle (`*.gradle.kts`),
build-logic, script (`tools/`) and test (`src/test*`, `src/androidTest*`, `build-logic/src/test`).

"No production caller" means no caller in any `src/main`, `src/camera/**`, `src/debug`, `src/release` or
`core/*/src/evaluation` source once the recorder is gone; test-only callers keep nothing alive unless the spec lists it as
kept. A disabled release flag alone never counts as evidence of dead code.

## Planted-violation check (DebugManifestTest)

`app/src/testDebug/kotlin/dev/cubentia/app/camera/DebugManifestTest.kt` was written first and run on the baseline tree:
`./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.camera.DebugManifestTest` **failed** as intended (BUILD FAILED,
1 test, 1 failed) with `Recorder activities in the debug manifest: [dev.cubentia.app.camera.recorder.FixtureRecorderActivity]`:
today's recorder activity is the planted violation. After the removal the same command passes.

## 1. The recorder package

Baseline files (all removed):

| File | Lines |
| --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 118 |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 859 |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 1681 |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurst.kt` | 153 |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 878 |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 211 |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurstTest.kt` | 152 |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 1041 |
| `app/src/testDebug/resources/recorder/pre-correction-labels-add0c79e.json` | test resource |
| `app/src/camera/debug/AndroidManifest.xml` | kept, `<activity>` removed (build-logic merges it into every debuggable camera variant) |

### `dev.cubentia.app.camera.recorder` package (all modes)

Disposition: **Removed** (whole package, its tests and test resources). Owner: none (retired; last revision with it: `af2a6d7a`).

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/AndroidManifest.xml` | 3, 5 | Android (debug recorder) | activity entry removed |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 1 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 1 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 1 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurst.kt` | 1 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 1 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 1 | test | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurstTest.kt` | 1 | test | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 1 | test | removed (file deleted) |
| `tools/cube-session.sh` | 60 | script | `recorder` command removed |

### `dev/cubentia/app/camera/recorder/**` class glob (build-logic)

Disposition: **Removed** from `debugOnlyClassGlobs` and the policy tests. Owner: build-logic.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/CameraSizePolicy.kt` | 8 | build-logic | glob dropped (sticker glob kept) |
| `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/CameraSizePolicyTest.kt` | 85 | build-logic (test) | glob dropped (sticker glob kept) |
| `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/ShadowReaderPolicyTest.kt` | 23, 38 | build-logic (test) | glob dropped (sticker glob kept) |

### `FixtureRecorderActivity`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/AndroidManifest.xml` | 3, 5 | Android (debug recorder) | activity entry removed |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 96, 150, 212, 245, 253, 512 | Android (debug recorder) | removed (file deleted) |
| `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AndroidFixture.kt` | 75 | build-logic (test) | replaced by the `DEBUG_CAMERA_MARKER` meta-data marker |
| `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CameraCompositionFunctionalTest.kt` | 56, 87, 121 | build-logic (test) | replaced by the `DEBUG_CAMERA_MARKER` meta-data marker |
| `tools/cube-session.sh` | 60 | script | `recorder` command removed |

### `FixtureRecorderScreen`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 258 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 160 | Android (debug recorder) | removed (file deleted) |

### `RecorderCamera` (recorder camera seam)

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 97, 209 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 160 | Android (debug recorder) | removed (file deleted) |

### `RecorderRules.kt` (file-level rules; members below)

Disposition: **Removed** (file deleted). Owner: none; the 10.28 rule moves to `StickerTruthCheck`.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 22 | test | removed (file deleted) |

### `FixtureExport.kt` (`fixtureRecord`, `writeFixture`, `ensureGroupManifest`, `writeCornerHold`, `recordHold`, `recordCountCheck`)

Disposition: **Removed** (file deleted). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 34 | test | removed (file deleted) |

### `RecorderBurst` (+ `ExposureState`)

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 195, 196, 259, 264, 275, 276, 532 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurst.kt` | 34 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurstTest.kt` | 44, 82, 103, 112, 113, 120, 140 | test | removed (file deleted) |
| `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/ShadowReaderPolicyTest.kt` | 23 | build-logic (test) | sample class dropped from the planted violation |

### `tools/cube-session.sh` commands

Disposition: **Removed**: `recorder` (opened `FixtureRecorderActivity`, line 59-60) and `pull` (copied recorder output into
`Fixtures/{live,corner}`, lines 61-66), with their usage lines 5-6; the usage printers `sed -n '2,9p'` (lines 37, 69)
become `sed -n '2,7p'`. Owner: none. Other commands (`cell`, `shot`, `deny-camera`) kept, owner Dev.

## 2. Named domain symbols

### `QuickScanPlan`

Disposition: **Kept**; its file `CornerCapturePlan.kt` is renamed `QuickScanPlan.kt` (ktlint's filename rule once `CenterCheck` left it as the single declaration). Owner: Case recognition (`CornerViewfinder.totalHolds` in `GuidedScanScreen.kt`) and `:core:domain` evaluation (`CornerFixtureJson.decode` in `LiveFixture.kt`); stays in `core/domain/src/main`.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtCornerDetectorDeviceTest.kt` | 16, 318 | test | kept |
| `app/src/androidTestDebug/kotlin/dev/cubentia/app/camera/LiteRtStickerCenterDetectorDeviceTest.kt` | 21, 427 | test | kept |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 99, 807 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 356 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` | 91, 153 | Android | kept |
| `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` | 67 | test | kept |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerLocatorTest.kt` | 11, 43, 83, 175, 218, 285, 323, 379 | test | kept |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 802, 836 | test | removed (file deleted) |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CornerCapturePlan.kt` | 6 | domain | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` | 485 | fixture decoder | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ColorCalibrationTest.kt` | 178 | test | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/LiveFixtureTest.kt` | 113, 155, 411 | test | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanClassificationTest.kt` | 26 | test | kept |

### `CenterCheck`

Disposition: **Removed** (no caller at all outside its own file, in `CornerCapturePlan.kt`; production, evaluation and test callers: none). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CornerCapturePlan.kt` | 14, 36, 39 | domain | removed (class deleted) |

### `GridReason`

Disposition: **Kept** (historical reader). Owner: `:core:domain` evaluation; stays in `core/domain/src/main`.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GridReason.kt` | 4 | domain | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt` | 197, 229, 233, 237, 241, 245 | domain | kept |

### `SideLight`

Disposition: **Kept** (historical reader) minus the `check(faces: List<FrameStats?>, saved)` overload. Owner: `:core:domain` evaluation; stays in `core/domain/src/main`.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurst.kt` | 7, 29, 79, 90 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 640, 655, 658, 722 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurstTest.kt` | 11, 43 | test | removed (file deleted) |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` | 196 | fixture decoder | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt` | 55, 550, 552 | domain | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SideLight.kt` | 24 | domain | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanGateTuningTest.kt` | 17, 286, 451, 452, 453, 454 | test | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanSideFaceReplayTest.kt` | 30, 131, 134, 141, 315, 316, 317, 324, 376, 426, 430, 445, 449, 621, 622, 871, 872 | test | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/SideLightTest.kt` | 33, 34, 39, 40, 43, 44, 52, 53, 57, 58, 64, 67, 76, 81, 90, 93, 98, 100, 101, 102, 103, 109, 111, 113, 116, 121, 125, 131, 132, 141, 142 | test | kept; only the FrameStats-overload assertions (125, 131, 132) changed |

### `SideLight.check(faces: List<FrameStats?>, saved)` overload

Disposition: **Removed** (callers: `RecorderBurst`, `RecorderRules.holdDiagnostics`, `SideLightTest`). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurst.kt` | 79 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 656, 659 | Android (debug recorder) | removed (file deleted) |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/SideLightTest.kt` | 109, 113, 116, 121, 125, 131, 132 | test | 125, 131, 132 (FrameStats overload) removed; 125 re-pointed to `checkSamples(listOf(null, null, null))`; 109-121 (3-list `check(top, left, right)`) kept |

### `QuickScanClassification`

Disposition: **Kept** (historical reader). Owner: `:core:domain` evaluation; stays in `core/domain/src/main` (moving it to the evaluation source set is out of scope).

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerLocatorTest.kt` | 426 | test | kept |
| `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/sticker/KotlinStickerReads.kt` | 8, 23, 28, 86, 97, 98, 102, 132 | evaluation | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GridReason.kt` | 3, 14 | domain | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt` | 10, 65 | domain | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanClassificationTest.kt` | 11, 43, 49, 58, 62, 71, 72, 79, 88, 92, 97, 98, 101, 138, 141, 144, 146, 159, 163, 166, 168, 176, 180, 189, 191, 204, 208, 236, 237, 238, 241, 242, 243, 244, 248, 252, 255, 256, 263, 268, 269, 270, 272, 275, 282, 283, 287, 294, 298, 299, 301, 302, 308, 311, 321, 322, 325, 332, 336, 338, 339, 343, 348, 352 | test | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanGateTuningTest.kt` | 16, 17, 146, 159, 182, 184, 204, 224, 239, 245, 262, 325, 372, 383, 401, 423, 447, 448, 450 | test | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanSideFaceReplayTest.kt` | 14, 15, 234, 244, 312, 520, 533, 555, 586, 587, 631, 755, 845, 868, 869, 923, 941, 943, 971, 976, 1010, 1013 | test | kept |

### `CornerHold`

Disposition: **Kept** (as `CaseCapture.kt` uses it). Owner: Case recognition (`:core:domain`).

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CaseCapture.kt` | 37, 56, 57, 58 | domain | kept |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CornerHold.kt` | 8 | domain | kept |
| `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/CaseCaptureControllerTest.kt` | 81 | test | kept |

### `CornerSettle.CAP_MS`

Disposition: **Kept** (value unchanged, KDoc re-worded) as the corner-capture settle cap for Case recognition; its only reader, the adapter alias `SETTLE_CAP_MS`, is removed, so it has **no caller** after Story 10.35 (kept because the spec keeps it). Owner: Case recognition (`:core:domain`).

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 91, 354 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 491 | Android | alias `SETTLE_CAP_MS` removed |
| `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CornerHold.kt` | 99 | domain | kept |

## 3. The recorder's two-hold helpers (`RecorderRules.kt`)

Every two-hold helper lived in `RecorderRules.kt` (or `FixtureExport.kt`) and had no caller outside the recorder package
and its tests; all are removed with the files. Only the 10.28 rule survives, as `StickerTruthCheck` (copied from PR #396
at `4be002d98a075f901478e736612444d115a8c8eb`).

### `CORNER_HOLDS`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 777, 798, 799, 845 | Android (debug recorder) | removed (file deleted) |

### `nextCornerHold`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 497 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 845 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 898, 899, 900, 901 | test | removed (file deleted) |

### `colorCount`

Disposition: **Removed**; the Story 10.28 nine-of-each-colour rule lives on in `StickerTruthCheck` (`:core:domain` main). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 481 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 797 | Android (debug recorder) | removed (file deleted) |
| `app/src/test/kotlin/dev/cubentia/app/render/ViewRotatedScreenshotTest.kt` | 117, 128, 139 | test | unrelated local `colorLabel` (a different symbol); unchanged |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 843, 853, 884, 885, 886, 891, 894, 1032, 1038 | test | removed (file deleted) |

### `ColorCountCheck`

Disposition: **Removed**; the Story 10.28 nine-of-each-colour rule lives on in `StickerTruthCheck` (`:core:domain` main). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 111 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 133 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 788, 797, 800, 804, 853 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 183, 206 | test | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 914, 925 | test | removed (file deleted) |

### `CornerHoldLabels`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 200, 463 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 780, 797, 828 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 800, 803, 813, 1009 | test | removed (file deleted) |

### `withHold`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 91, 100 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 622 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 458, 466, 485, 912, 940 | test | removed (file deleted) |

### `withCountCheck`

Disposition: **Removed**; the Story 10.28 nine-of-each-colour rule lives on in `StickerTruthCheck` (`:core:domain` main). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 106, 115 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 851 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 926, 931, 936 | test | removed (file deleted) |

### `holdDiagnostics`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 459 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 646 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 135 | test | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 509, 535, 544, 548, 554, 574, 577 | test | removed (file deleted) |

### `captureSettle`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 378, 386 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 683 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 567, 579, 585, 588, 594 | test | removed (file deleted) |

### `cornerFaceLocks`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 426, 651 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 871 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 952, 953, 954, 958, 964, 968, 977, 978 | test | removed (file deleted) |

### `overCountedPrefills`

Disposition: **Removed**; the Story 10.28 nine-of-each-colour rule lives on in `StickerTruthCheck` (`:core:domain` main). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 503 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 827 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 858, 869, 878 | test | removed (file deleted) |

### `countRefusal`

Disposition: **Removed**; the Story 10.28 nine-of-each-colour rule lives on in `StickerTruthCheck` (`:core:domain` main). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 872, 1057 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 804 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 857, 1038 | test | removed (file deleted) |

### `STICKERS_PER_COLOR`

Disposition: **Removed**; the Story 10.28 nine-of-each-colour rule lives on in `StickerTruthCheck` (`:core:domain` main). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 774, 791, 792, 810, 811 | Android (debug recorder) | removed (file deleted) |

### `colorLabel`

Disposition: **Removed** with `RecorderRules.kt` (Story 10.34 rebuilds `colorLabel` in `dev.cubentia.app.camera.session`). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 429, 630, 643, 647, 679, 693, 808, 810, 812, 908, 1322, 1445, 1473 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 72, 75, 81, 810, 811 | Android (debug recorder) | removed (file deleted) |

### `FACE_CENTER`

Disposition: **Removed** with `RecorderRules.kt` (Story 10.34 rebuilds `colorLabel` in `dev.cubentia.app.camera.session`). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 613, 1120 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` | 61, 75, 81, 99 | Android (debug recorder) | removed (file deleted) |

### `fixtureRecord`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 23 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 726 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 52, 111 | test | removed (file deleted) |

### `writeFixture`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 51 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 746 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 72, 91, 92, 100, 101, 108, 109 | test | removed (file deleted) |

### `ensureGroupManifest`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 71 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 296 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 129, 131, 177 | test | removed (file deleted) |

### `writeCornerHold`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 81 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 468 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 157 | test | removed (file deleted) |

### `recordHold`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 94, 107 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 474 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 163, 164, 171, 178, 179, 205 | test | removed (file deleted) |

### `recordCountCheck`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` | 109 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 484, 539 | Android (debug recorder) | removed (file deleted) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` | 194, 198, 206, 209 | test | removed (file deleted) |

## 4. `ScanFrameSource` lock and settle helpers

### `ExposureLocks`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 45, 180, 218, 234, 313, 314, 414, 415, 421, 422, 434, 511, 625 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 528, 629, 681, 690, 694, 700, 706, 710, 713, 716, 719, 722 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 276, 278, 279, 280, 282, 284, 285, 286, 287, 289, 290, 293, 294, 295, 297, 299, 300, 305, 308, 309, 310, 311, 313, 314, 317, 319, 320, 322, 323, 325, 326 | test | test cases removed |

### `SettleTracker`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 48, 179 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 741 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 103, 126, 157, 165, 176, 207, 240, 260, 331 | test | test cases removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 561 | test | removed (file deleted) |

### `CameraXFrameSource.lockSupport`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 411 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 514 | Android | removed |

### `CameraXFrameSource.applyLocks`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 434 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 526 | Android | removed |

## 5. Every other helper this story removes

### `CameraXFrameSource.LOCK_UNSUPPORTED` (recorder lock word, `ScanFrameSource.kt:506-507`)

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 410 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 507, 724 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 299 | test | test case removed |

### `Settle` enum

Disposition: **Removed** (only `settleOutcome`/`SettleTracker` and the recorder used it). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 364, 369, 374 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 619, 621, 622, 623, 672, 839 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 113, 114, 115, 117, 118, 119, 120, 121, 136, 138, 145, 148, 150, 161, 162, 173, 183, 211, 250, 255, 267, 271 | test | test cases removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` | 565 | test | removed (file deleted) |

### `CameraXFrameSource.settleOutcome`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 615, 839 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 113, 114, 115, 117, 118, 119, 120, 121, 211 | test | test cases removed |

### `CameraXFrameSource.SETTLE_CAP_MS`

Disposition: **Removed** (the domain constant `CornerSettle.CAP_MS` stays). Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 491, 613, 622 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 118, 119, 120, 121, 161, 162 | test | test cases removed |

### `CameraXFrameSource.SETTLE_TOLERANCE`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 498, 612, 736, 803 | Android | removed |

### `CameraXFrameSource.SETTLE_WINDOW_MS`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 504, 612, 621, 738 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 100 | test | test cases removed |

### `CameraXFrameSource.cubeMeteringRegion`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 341 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 554 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 75, 82, 85, 86 | test | test cases removed |

### `CameraXFrameSource.chooseMetering`

Disposition: **Removed**. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 344 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 599 | Android | removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 93, 94, 95 | test | test cases removed |

### `TorchButton(testTag)` parameter

Disposition: **Removed** (only the recorder passed it; `"quickScan:torch"` inlined). Owner: `TorchButton` itself kept (Quick Scan, Guided calibration).

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 1114 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ContinuousQuickScanScreen.kt` | 98 | Android | kept (call unchanged; it used the default tag) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` | 571, 586, 589, 592 | Android | 571 call unchanged; 586 KDoc sentence and 592 parameter removed, `"quickScan:torch"` inlined in the modifier |

### `includeAwb` metering parameter (`ScanFrameSource.meter(includeAwb)`, `meterOnGrid`, `meteringAction`, `meteringFlags`)

Disposition: **Removed**. Only the recorder's corner hold 2 ever metered without AWB (`remeterCorner(includeAwb = cornerHold == 1)`); every `GuidedScanActivity` call used the default `true`. Metering is now always AF + AE + AWB, which is unchanged behaviour for every remaining caller. Owner: none.

| Caller (baseline file) | Lines | Category | After 10.35 |
| --- | --- | --- | --- |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` | 136, 140, 247, 248, 305, 313, 314, 333, 345, 347 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` | 590 | Android (debug recorder) | removed (file deleted) |
| `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` | 857 | Android | `meterOnGrid()` calls `meter()`; every caller already used the default `true` |
| `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` | 88, 220, 222, 457, 465, 584, 589, 594, 627, 631, 632, 692, 695, 696, 701, 702, 707, 710 | Android | parameter, `meter(includeAwb)` overload and the no-AWB branch removed (`ExposureLocks` lines were already removed with it) |
| `app/src/testDebug/kotlin/dev/cubentia/app/ContinuousQuickScanRouteTest.kt` | 1573, 1574 | test | fake `meter(includeAwb)` override removed |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/CameraXFrameSourceTest.kt` | 83, 84 | test | `includeAwb = false` assertion removed; AF+AE+AWB assertion kept (`meteringFlags()`) |
| `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` | 278, 282, 286, 309, 310, 314, 317, 322, 326, 367, 368 | test | removed with the `ExposureLocks` and `meteringFlags(false)` cases |

Kept beside them in `CameraXFrameSource` (owner: Quick Scan / Guided camera adapter): `centreMeteringRegion`,
`meteringAction`, `meteringFlags`, `MeteringRegion`, `METERING_LOCK_SECONDS`, `torchLit` (all used by `meterOnGrid` and the
torch code).

## 6. Kept items and owners (summary)

| Item | Owner | Location |
| --- | --- | --- |
| `CaseCaptureController`, `CornerHold` (as `CaseCapture.kt` uses it), `CornerSettle.CAP_MS` (no caller left; kept by the spec) | Case recognition | `core/domain/src/main` |
| `CornerDetector`, `CornerLocator`, `CornerReader`, `CornerGuide`, `CornerFrame`, `CornerPoint`, `CornerOutline`, `GuidedScanPhase.CornerViewfinder` | Case recognition | `app/src/camera` |
| `LiteRtCornerDetector` | Case recognition | `app/src/camera/corner` |
| `QuickScanPlan` (`CornerViewfinder.totalHolds`, `CornerFixtureJson.decode`) | Case recognition + `:core:domain` evaluation | stays in `core/domain/src/main` |
| `QuickScanClassification`, `GridReason`, `SideLight` (minus the FrameStats overload) | `:core:domain` evaluation | stays in `core/domain/src/main` |
| Fixture format in `LiveFixture.kt` (records, manifests, index, keypoint fixtures, `TruthSource`, `DeviceInfo`, diagnostics types, encode + decode) | `:core:domain` evaluation | stays in `core/domain/src/main` |
| `exportKotlinStickerReads`, `evaluateScanAccuracy`, `evaluateLiveBaseline`, `evaluateRecognitionApproaches`, `evaluateMatrix`, `evaluateQuickScan` (`core/domain/build.gradle.kts:32-116`) | `:core:domain` evaluation | `core/domain/src/evaluation` (Gradle tasks unchanged) |
| `ScanDiagnostics.onQuickScanRun` (debug and release; caller `GuidedScanActivity.kt:588`) | `:core:domain` evaluation | `app/src/debug`, `app/src/release` (unchanged) |
| `StickerTruthCheck` (new, copied from PR #396) | Story 10.34 continuous-session labeller | `core/domain/src/main` |
