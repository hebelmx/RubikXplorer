---
title: 'Guided 6-face Scan viewfinder'
type: 'feature'
created: '2026-11-15'
status: 'blocked'
baseline_revision: '4a672229e567a753a526acd3a1a42a7df692dba4'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Prompt and coaching overlays may cover the grid's top and bottom rows on a 360x640 dp phone.
    evidence: |-
      Blind reviewer geometry estimate (viewfinder ~260 dp, grid ~208 dp, ~26 dp margins, ~48 dp prompt); settle with a screenshot on the S24 FE in device session B.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt (attempt-1 patch)
    severity: medium (unverified)
  - summary: >-
      Grid-wide glare fraction (Y >= 250 over 15%) may flag a white or yellow face as GLARE permanently.
    evidence: |-
      Clipped white stickers count as glare pixels; settle with live frames of the white face in device session B.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt FrameQuality (attempt-1 patch)
    severity: medium (unverified)
  - summary: >-
      FrameReader.read(ImageProxy) plane and crop mapping is not unit-tested.
    evidence: |-
      Tests build YuvFrame directly; a U/V swap would pass. Check colors on device in session B.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt (attempt-1 patch)
    severity: low
  - summary: >-
      Medium/expanded (tablet) viewfinder layout has no test.
    evidence: |-
      Only w360dp-h640dp-port is tested; the Lenovo tablet layout is checked in session B.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt (attempt-1 patch)
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 10.1 gave `:core:domain` a classifier and assembler, but there is no Learner-facing camera flow: the only CameraX code is the debug fixture recorder, and the `ScanLauncher` port can only answer `hasCamera`. Nothing guides a Learner through six faces, asks for the camera permission, coaches bad captures or captures automatically.

**Approach:** Add a Guided 6-face Scan viewfinder in the camera source set (`app/src/camera/kotlin/dev/cubentia/app/camera/`): a full-screen Activity launched through a new `ScanLauncher` intent and a typed `ActivityResultContract` in `app/src/main`, that shows the camera rationale/denied cards, runs CameraX with a per-cell histogram reader (10.1 `CellReader`), re-meters on the grid, auto-captures each face after ~600 ms aligned and steady (manual shutter too), coaches glare/blur/low light/misalignment/wrong face, advances a mini net, and returns the six `FaceSample`s to its caller. The pure decisions (face plan, quality rules, capture controller) live in `:core:domain` `recognition` so they are JVM-tested under Kover.

## Boundaries & Constraints

**Always:**
- Scope choices (no Learner entry point exists yet; 10.3 owns review and `ScanOutcome`, 10.5 the Solve-tab entry): the viewfinder returns `GuidedScanCapture` = `Captured(samples: List<FaceSample>)` (six, in plan order) | `Cancelled` | `FellBackToEditor`, decoded by `GuidedScanContract` in `app/src/main/.../scan/`. 10.3 will map it into `ScanOutcome`; 10.2 adds no `SessionEvent`, reducer change, review UI, Solve-tab button or calibration persistence. For device session B a **debug-only** launcher activity in `app/src/camera/debug/` starts the contract and shows a plain summary of the result (`SelfCalibration.from` → `ScanAssembler.assemble`: Full/Partial, uncertain count, gaps); it is not Learner-facing.
- `ScanLauncher` (main) gains `fun guidedScanIntent(context: Context): Intent`; `CameraScanLauncher` returns the explicit intent for `GuidedScanActivity`. `GuidedScanContract(launcher)` is the only way main code starts the scan; result data is the kotlinx-serialized `List<FaceSample>` (`LiveFixtureJson`'s `Json` config or a plain `Json`), never pixels.
- `GuidedScanPlan.steps` (`:core:domain`): six `ScanStep(centerColor, upNeighborColor)` in this order: GREEN/WHITE, RED/WHITE, BLUE/WHITE, ORANGE/WHITE, WHITE/BLUE, YELLOW/GREEN. Each captured sample carries the step's `centerColor`, `upNeighborColor`, `face = Face` whose home color is `centerColor`, `centerPlain` from the center `CellReading.plain`, `orientationDegrees` = the analysis image's rotation, quality ACCEPTABLE. A test proves six synthetic samples of a scrambled cube shown per the plan assemble via `ScanAssembler` (with a calibration) to `ScanResult.Full` equal to that cube.
- `FrameQuality.assess(stats)` (`:core:domain`, pure): input `FrameStats(cells: List<CellReading?>, meanLuma: Double, glareFraction: Double, sharpness: Double)`; precedence LOW_LIGHT (meanLuma < 40 on 0–255) → GLARE (glareFraction ≥ 0.15 of grid pixels with Y ≥ 250) → BLURRED (sharpness < 60, mean absolute Laplacian-of-Y variance unit as computed by the adapter) → MISALIGNED (any cell null or fewer than 7 of 9 cells `plain`) → ACCEPTABLE. Thresholds are named, provisional constants (KDoc: tuned in device session B).
- `GuidedScanController` (`:core:domain`, pure, time injected as `nowMs`): state = step index, captured samples, alignment/steady timers, coaching. `onFrame(stats, sample, nowMs)` returns effects: `Remeter` when alignment (quality ACCEPTABLE) is first found after being lost or at a new step; auto-`Capture` only after ≥ 600 ms of ACCEPTABLE frames whose 9 cell values each move < 5 ΔE (CIE76) from the reference, counted from the last `Remeter` (+ 300 ms settle). `onShutter` captures the latest ACCEPTABLE frame immediately; with a non-ACCEPTABLE latest frame it only coaches. Wrong face: a capture whose center value is < 10 ΔE from an already-captured center is rejected with `Coaching.WRONG_FACE` and not stored. A capture stores the sample, advances the step and emits `Remeter`; the sixth capture emits `Done(samples)`. Coaching is categorical (`GLARE, BLURRED, LOW_LIGHT, MISALIGNED, WRONG_FACE` or none) — never an error.
- Content strings (`core/domain/src/main/resources/content/strings/en.json`, keys under `msg.scan.*` / `btn.scan.*`): rationale `msg.scan.camera-rationale` = "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent."; `btn.scan.continue` "Continue"; `btn.scan.enter-colors-instead` "Enter colors instead"; `msg.scan.camera-denied`, `msg.scan.camera-denied-permanently` (+ `btn.scan.open-settings` "Open settings"); `msg.scan.camera-unavailable`; prompt `msg.scan.prompt` with `{center}`/`{up}` args ("Show the {center} center, with {up} on top."); coaching `msg.scan.coach.glare` "Too much glare — tilt the cube", `.blur`, `.low-light`, `.misaligned`, `.wrong-face`; `btn.scan.capture`; progress `msg.scan.progress` ("Face {n} of 6"). Color names come from the existing `R.string.color_*` resources. No Learner text literal in Kotlin.
- Permission flow: granted → viewfinder. Not granted → rationale card first (*Continue* → system dialog; *Enter colors instead* → finish `FellBackToEditor`). Denied → explanation card with *Enter colors instead*; when `shouldShowRequestPermissionRationale` is false after a denial (permanent), also *Open settings* (`ACTION_APPLICATION_DETAILS_SETTINGS` for this package). Back / top-bar close → `Cancelled`.
- CameraX: `ProcessCameraProvider` is obtained and awaited off the main thread (coroutine on `Dispatchers.Default`, provider configured with `CameraXConfig` limited to the back camera via `setAvailableCamerasLimiter`); analysis runs on a dedicated single-thread executor; only `bindToLifecycle` (a main-thread-only API) runs on main. Bind failure (no back camera, `IllegalArgumentException`/`CameraInfoUnavailableException`) shows the `msg.scan.camera-unavailable` card with *Enter colors instead*, never a crash.
- Adapter per frame: grid = centered square 80 % of the shorter crop side (same as recorder); per cell, sample a regular 12×12 lattice over the central 60 % of the cell, YUV→sRGB→Lab (recorder formula), `CellReader.read`; `meanLuma`, `glareFraction` and `sharpness` from the Y plane inside the grid. Remeter = `startFocusAndMetering` with AF|AE|AWB on a metering point at the grid center sized to the grid fraction.
- `CameraScanLauncher.hasCamera` returns true only when the package has `android.hardware.camera.any` and `CameraManager` lists a camera with `LENS_FACING_BACK` (any exception → false), so `ScanPortGate` hides every scan entry point on a device with no usable camera; the camera manifest keeps `uses-feature android.hardware.camera.any required="false"`.
- Layout (never scrolls): compact width portrait → viewfinder top ~45 % height with the grid overlay, face prompt (center + up glyph swatches with `StickerAppearance` glyphs) and coaching line on it; below it the 6-face mini net (captured faces filled with their read colors, current face outlined) and the primary *Capture* button. Medium/expanded → viewfinder left, prompt/net/action right. TalkBack: coaching in a polite live region.
- Privacy: frames are only read inside the analyzer and dropped; the viewfinder writes no file, keeps no `Bitmap`, sends nothing; `app/src/camera/kotlin` contains no `Log.`/`println`.

**Never:**
- No camera/CameraX/Android import in `core/domain`; no CameraX in `app/src/main`; no new dependency; no build-logic gate, flag level or release manifest change; no `INTERNET`.
- No review/correction UI, no `ScanOutcome`, no `SessionEvent`, no calibration persistence, no Solve/Physical/Welcome entry point (10.3–10.5, 10.7).
- No change to the fixture recorder's behavior or `Fixtures/`; no planning-artifact, `_bmad/` or `.claude/skills/` edit.
- Story 1.9 `app/src/spikeScan/` and the `cubentia.spikeScan` switch were already deleted by 10.10 — do not reintroduce; only verify absence.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Auto-capture | ACCEPTABLE, steady frames ≥ 600 ms after remeter settle | `Capture`, step advances, `Remeter` | — |
| Not steady | a cell moves ≥ 5 ΔE at 500 ms | timer restarts, no capture | — |
| Alignment found | MISALIGNED → ACCEPTABLE | `Remeter` once, timer starts after settle | — |
| Manual shutter | latest ACCEPTABLE | immediate capture | non-ACCEPTABLE → coaching only |
| Glare / blur / dark / misaligned | stats over thresholds | matching `Coaching`, no capture | not an error |
| Wrong face | center < 10 ΔE from a captured center | `WRONG_FACE`, not stored, same step | — |
| Sixth face | capture at step 6 | `Done(six samples)` → `Captured` result | — |
| Permission denied / permanent | dialog result | denied card / + *Open settings* | — |
| No back camera | `hasCamera` false | scan port gate reads camera flags OFF | exceptions → false |
| Bind failure | CameraX throws | camera-unavailable card + *Enter colors instead* | no crash |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/{CellReader,StickerClassifier,SelfCalibration,ScanAssembler,FaceSample}.kt` -- 10.1 types to reuse (`CellReader.read(List<Lab>)` → sealed reading with `value`, `plain`; `ScanAssembler.assemble`; `FaceSample` pose fields); `deltaE` helper may be internal in `StickerClassifier` — reuse or add a small internal one.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/{Color,Face}.kt` -- `Color.home(face)` for the sample `face`.
- `app/src/main/kotlin/dev/cubentia/app/scan/{ScanLauncher,ScanPortGate}.kt`, `app/src/main/kotlin/dev/cubentia/app/di/{ScanPortModule,FlagsModule}.kt` -- port (extend with `guidedScanIntent`), gate already hides entry points when `hasCamera` is false.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{CameraScanLauncher,CameraModule}.kt`, `app/src/camera/AndroidManifest.xml` -- binding; declare `GuidedScanActivity` (not exported, portrait not locked) in this manifest.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- CameraX reference: `startCamera` (ViewPort + UseCaseGroup), `measure` YUV→RGB→Lab (`rgbToLab`), `meterOnGrid`; `RecorderRules.kt` `RemeterTrigger` thresholds (15/5 ΔE, 600 ms, settle) to mirror in the controller. Do not modify; copy the conversion into a shared non-debug `camera/FrameReader.kt` (recorder may keep its own copy).
- `app/src/camera/debug/AndroidManifest.xml` -- add the debug launcher activity for session B.
- `app/src/main/kotlin/dev/cubentia/app/content/{ContentStrings,LessonCatalog}.kt` -- `LessonCatalog.state` (`CatalogState.Ready.strings`) gives content strings to the Activity (Hilt `@AndroidEntryPoint`).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- `colorName(color)` (internal, `R.string.color_*`); `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` + `domain/appearance/StickerAppearance` for glyph swatches; `ui/theme/CubentiaTheme`.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorCompactTest.kt` -- Robolectric Compose test pattern (`@Config(qualifiers = "w320dp-h640dp")`, shipped `ContentStrings`).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/` -- debug unit tests compile against the camera source set.
- `app/src/debug/kotlin/dev/cubentia/app/DebugStrictMode.kt` -- StrictMode policy used for the device-session check.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `ScanStep`, `GuidedScanPlan`, `FrameStats`, `FrameQuality`, `Coaching`, `GuidedScanController` + effects -- pure decisions, Kover-covered.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/GuidedScanTest.kt` -- every I/O-matrix controller/quality row; plan round-trip to `ScanResult.Full` for a scrambled cube.
- `core/domain/src/main/resources/content/strings/en.json` -- new `msg.scan.*` / `btn.scan.*` keys.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanLauncher.kt`, `GuidedScanContract.kt` (new) -- port intent + typed contract and `GuidedScanCapture`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/CameraScanLauncher.kt` -- back-camera availability + intent.
- `app/src/camera/kotlin/dev/cubentia/app/camera/FrameReader.kt` -- YUV plane access → `FrameStats` + nine-cell `Lab`/`CellReading` (pure part testable with synthetic planes).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` + `GuidedScanScreen.kt` -- permission flow, off-main provider, bind/remeter, controller wiring, layout, result.
- `app/src/camera/AndroidManifest.xml` -- declare `GuidedScanActivity`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/scan/GuidedScanDebugActivity.kt` + `app/src/camera/debug/AndroidManifest.xml` -- session-B launcher showing the assembled summary.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/` -- Robolectric tests: screen at `w360dp-h640dp` portrait shows viewfinder area, prompt, net and *Capture* fully on screen with no scrollable node; rationale card text/actions; denied card with and without *Open settings*; contract round-trip of six samples and of `Cancelled`/`FellBackToEditor`; `hasCamera` false with no back camera (ShadowCameraManager); `FrameReader` stats on synthetic planes.

**Acceptance Criteria:**
- Given a first scan without the camera permission, when the viewfinder opens, then the rationale card shows the approved copy from content strings before any system dialog, and a denial shows the explanation card with *Enter colors instead* (plus *Open settings* when permanently denied).
- Given the viewfinder in a debug build, when a face is held aligned and steady, then grid re-metering fires on alignment and on each face change, the face is captured automatically after ~600 ms (or by *Capture*), the prompt names center and up colors with glyph swatches, and the mini net advances; six faces return `GuidedScanCapture.Captured` to the caller.
- Given glare, blur, low light, misalignment or a repeated face, when detected, then specific coaching text shows and no capture happens.
- Given a device with no usable back camera, when scan entry points would render, then camera flags read OFF through `ScanPortGate`.
- Given `./gradlew spotlessCheck build`, when run, then it passes (Kover ≥ 80 % on `:core:domain`, release classpath/manifest gates unchanged); `grep -rn "spikeScan" app .github` finds nothing.

## Spec Change Log

## Review Triage Log

### 2026-11-15 — Review pass
- verdicts: 39 findings — high 0, medium 10, low 22, false 5, maybe-false 2
- attempted change saved as [spec-10-2-attempt-1.patch](spec-10-2-attempt-1.patch) (applies cleanly on baseline 4a672229); code reverted.
- findings:
  - `[medium]` `[intent_gap]` (intent) Activity wiring (permission callback, Done→result, bind failure) untested — real; moot under intent gap, would patch by extracting pure mappings and testing them.
  - `[low]` `[intent_gap]` (intent) Remeter waits a fixed 300 ms settle rather than metering completion — real but provisional; moot.
  - `[medium]` `[intent_gap]` (intent) "Wrong orientation" coached only as a repeated center — the controller never checks the shown face against the prompt; a different uncaptured face or a wrong up color is stored under the step's labels. Spec (intent-contract) defines WRONG_FACE as repeat-only; the intent does not settle how a not-yet-captured wrong face or wrong up side is detected.
  - `[false]` `[reject]` (intent) No palette on the viewfinder — palette is sticker correction, owned by 10.3 review; AC3 lists viewfinder + face prompt.
  - `[low]` `[intent_gap]` (intent) Mini-net advance not asserted — real test gap; moot.
  - `[false]` `[reject]` (intent) Rationale shows whenever permission is missing, not only on the first scan — EXPERIENCE.md state pattern shows it before the system dialog; showing it again before a re-ask is the same reading.
  - `[medium]` `[intent_gap]` (intent) A runtime CameraX bind failure shows an in-activity card but does not hide scan entry points, while AC6 says hardware "that cannot be bound" hides them as if OFF. Spec chose up-front back-camera detection; whether a bind failure must be remembered (and where, given no persistence) is not settled.
  - `[low]` `[reject]` (intent) No automated privacy test — enforced by the spec's grep and by having no write/send code; a test would only re-grep.
  - `[false]` `[reject]` (intent) Device session B / StrictMode not run — human gate by the story's own definition.
  - `[false]` `[reject]` (intent) No Learner entry point — Solve entry is Story 10.5; the scan sub-flow returns to its caller.
  - `[medium]` `[intent_gap]` (blind) No check that the shown face is the prompted one — same root as the wrong-orientation finding; also deadlocks: a blue face captured at the red step makes the real blue step reject blue as "already scanned".
  - `[medium]` `[intent_gap]` (blind) False WRONG_FACE has no way out when two centers read within 10 ΔE (red/orange in poor light) — same root (wrong-face rule design). The "logo center null" half is false: `stickers[4]` always exists.
  - `[medium]` `[intent_gap]` (blind) "Already scanned" coaching about 900 ms after a good capture while the old face is still held — same root (repeat-only rule, no post-capture pause).
  - `[low]` `[intent_gap]` (blind) Capture gives no TalkBack/haptic signal — real; moot.
  - `[low]` `[reject]` (blind) First denial has no "try again" — closing and reopening re-asks; adds an action for a rare path.
  - `[low]` `[reject]` (blind) Loading shows a blank screen; Failed content shows raw keys — shipped bundle loads in milliseconds and Failed is a corrupted-APK path; fallback adds branches.
  - `[maybe-false]` `[defer]` (blind) Prompt and coaching overlays cover the grid's top/bottom rows on a 360×640 dp phone — needs a device or screenshot check in session B.
  - `[low]` `[reject]` (blind) Color names lowercased with default locale; "of 6" literal — app is English-only (en.json); no locale issue today.
  - `[maybe-false]` `[defer]` (blind) Global glare fraction may flag the white face permanently — needs live frames; thresholds are provisional for session B.
  - `[low]` `[reject]` (blind) Metering point factory not tied to the viewport crop — center point is still the grid center; same approach as the session-A recorder.
  - `[low]` `[intent_gap]` (blind) `GuidedScanContract.decode` accepts any list size — trivial `takeIf` fix; moot.
  - `[false]` `[reject]` (blind) `hasCamera` uncached binder calls — called once, in the `@Singleton` `FlagsModule` provider.
  - `[low]` `[intent_gap]` (blind) *Open settings* `startActivity` unguarded — trivial runCatching; moot.
  - `[medium]` `[intent_gap]` (blind) Activity has no tests — same as the intent-layer wiring finding.
  - `[low]` `[intent_gap]` (blind) Filler assertion in `coaching maps every capture quality`; `uprightNet` duplicates `rotateClockwise` — real; moot.
  - `[low]` `[reject]` (blind) Queued analyzer tasks retain a destroyed Activity briefly after rotation — transient; bounded by the single queued frame.
  - `[medium]` `[intent_gap]` (verification-gap) Permission/result/bind wiring not exercised — same as the activity-tests finding.
  - `[low]` `[intent_gap]` (verification-gap) `uprightNet` rotation untested — real; moot.
  - `[low]` `[defer]` (verification-gap) `ImageProxy`→plane mapping untested — device session B.
  - `[low]` `[defer]` (verification-gap) Wide layout untested — tablet checked in session B.
  - `[low]` `[reject]` (verification-gap other) Failed content shows raw keys — same as the blind loading finding.
  - `[low]` `[intent_gap]` (edge) Done posted to an Activity destroyed by rotation leaves the new one stuck at step 6 — real; moot.
  - `[low]` `[reject]` (edge) A queued Done overwrites a Cancel result — needs a sixth capture within one main-loop tick of a tap.
  - `[medium]` `[intent_gap]` (edge) On Android 11+ dismissing the first dialog reads as permanent denial and offers *Open settings* — real; small ViewModel flag fix; moot.
  - `[low]` `[reject]` (edge) Failed catalog shows raw keys — same as above.
  - `[low]` `[intent_gap]` (edge) Decode accepts a non-six list — same as blind decode finding.
  - `[medium]` `[intent_gap]` (edge) A different not-yet-captured face is stored under the wrong Face — same root as wrong-orientation.
  - `[low]` `[reject]` (edge) WRONG_FACE coaching lingers up to 600 ms while the right face steadies — cleared on capture; cosmetic.
  - `[low]` `[reject]` (edge) Remeter fires at capture time on the old face; no remeter if the new face aligns without a misaligned frame — turning the cube passes through misaligned frames in practice.

## Design Notes

Plan check: for a GREEN-center face with WHITE up, `ScanAssembler` direction `d` for F is U = 0, so no turn; for WHITE (U) with BLUE up, U's net neighbors are B,R,F,L → B is top (d = 0); for YELLOW (D) with GREEN up, D's neighbors F,R,B,L → F top (d = 0). Every step therefore shows its face exactly as the Kociemba net draws it, which keeps prompts intuitive (hold white on top, rotate around; then tip).

Session B (human gate, not runnable here): Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE acceptance, threshold tuning for `FrameQuality`, and a debug StrictMode run adding no app-frame main-thread disk violation beyond #107's platform list.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test :app:testDebugUnitTest` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `grep -rn "Log\.\|println" app/src/camera/kotlin` -- expected: no matches

## Auto Run Result

Status: blocked
Blocking condition: intent gap

**Summary:** Implemented the Guided 6-face Scan viewfinder (domain plan, frame quality, capture controller; content strings; `ScanLauncher.guidedScanIntent` + `GuidedScanContract`; CameraX `GuidedScanActivity`/screen, `FrameReader`, back-camera `hasCamera`; debug session-B launcher; tests). `./gradlew spotlessCheck build` passed. Review found two places where the intent-contract does not settle what the story's ACs require, so the attempt is saved as [spec-10-2-attempt-1.patch](spec-10-2-attempt-1.patch) and the code reverted.

**Unresolved questions:**
1. AC4 "wrong orientation … re-prompted": how must the viewfinder detect a face that is not the prompted one but has not been captured yet, or the wrong side on top? Options: (a) a nominal per-color plausibility check on the center against the prompt (risks stickerless/pastel cubes, the reason for self-calibration); (b) only repeated centers live, and rely on 10.3 validation ("This corner shows two whites.") for the rest; (c) something else. Also: should a repeated-center rejection be overridable (red/orange can read within 10 ΔE in poor light), and should a face just captured be ignored until the cube moves?
2. AC6 "camera hardware that cannot be bound … hidden as if its flag were OFF": is the up-front check (feature + a listed back camera) enough, with a runtime bind failure shown as an in-flow card with *Enter colors instead*, or must a bind failure also hide entry points afterwards (for the process lifetime, or persisted)?

**Patch-ready fixes for the next attempt** (from triage, not applied): decode requires six samples in plan order; Android 11+ first-dismissal is not permanent (track a prior denial in the ViewModel); guard *Open settings*; keep a pending Done across rotation; announce captures; test `uprightNet`, pure permission/effect mappings, and the filler assertion.

**Deferred:** overlays vs grid rows on phones, white-face glare threshold, ImageProxy plane mapping, wide layout — device session B.
