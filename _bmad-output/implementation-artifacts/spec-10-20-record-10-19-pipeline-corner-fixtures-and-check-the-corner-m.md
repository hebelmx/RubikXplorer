---
title: 'Story 10.20: Record 10.19-pipeline corner fixtures and check the corner model (code part)'
type: 'feature'
created: '2026-11-23'
status: 'done'
baseline_revision: 'cc1a0b547f17cf98bb11c70ae96cef97a7ab9799'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      epic-10-context.md lists 10.11 among the done stories though sprint-status has it in backlog.
    evidence: |-
      Blind hunter (follow-up pass): "Remaining order" says 10.1-10.19 done; sprint-status.yaml has 10-11 backlog. Agent-context cache; regenerate or correct it.
    location: >-
      _bmad-output/implementation-artifacts/epic-10-context.md
    severity: low
  - summary: >-
      epic-10-context.md regeneration dropped some constraints still in force (LiteRT 16 KB alignment, shadow-reader guardrails, Quick Scan torch carry rules).
    evidence: |-
      Blind-hunter and intent-alignment reviewers compared the regenerated file with its previous version; it is an agent-context cache, so its fix is deferred.
    location: >-
      _bmad-output/implementation-artifacts/epic-10-context.md
    severity: low
  - summary: >-
      Recorder Activity settle/lock/applied-torch wiring has no JVM test (needs CameraX).
    evidence: |-
      Only the pure rules are unit-tested; the device session must confirm settle.outcome, lock and torch appear correctly in recorded corner-N.json and group.json.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 10.21 must fit the keep rule, agreement gate and light cuts on real corner captures recorded through the current (10.19 lighting + 10.22 exposure) pipeline, per device, light and torch state, with a held-out split. Today the debug fixture recorder tags groups `"pipeline": "10.19"`, meters without 10.22's settle-and-lock, samples its calibration with the torch off, stores no per-hold light/settle diagnostics, and its burst lines carry no light check; the replay, evaluator and calibration check have no held-out split, no 10.22 groups and no baseline report.

**Approach:** Build only the code part now (PO clarification 2026-11-23): the recorder writes complete 10.22 group manifests and per-hold diagnostics, meters with 10.22's settle-and-lock, offers Quick Scan's torch toggle (calibration taken with the chosen torch state), and the burst carries the light check. Add a group index (held-out/tuning split) and make the replay test, `evaluate_model.py`, the training script and the calibration plausibility check consume it, so the later ACs run as soon as fixtures exist. Recording itself is a before-merge human gate on the Lenovo TB128XU and Galaxy S24 FE.

**Refs:** FR-22, FR-24 · recognition NFRs (Quick Scan, Confidence calibration) · AD-9, AD-15 · OQ-8 · Stories 10.4, 10.10, 10.12, 10.18, 10.19 · #274, #166

## Boundaries & Constraints

**Always:**
- AD-9: pixels and camera control (metering, exposure locks, torch) stay in `app/src/camera/`; domain types carry numbers only.
- AD-15: the recorder, burst and all new recorder UI stay in debug-only source sets (`app/src/camera/debug/`, `app/src/debug/`); release and benchmark builds gain nothing. Quick Scan's own behavior is unchanged.
- Old fixture files stay readable: schema-3/4 corner records and existing manifests decode unchanged; new fields are optional with defaults.
- The 10.22 baseline in `QuickScanSideFaceReplayTest` is reported, never enforced (Story 10.21 enforces it). Existing assertions on legacy and 10.19 groups keep their current behavior.
- Every new check that needs fixtures passes, and says so in its output, when the fixtures or the group index do not exist yet.

**Never:**
- No fabricated captures, fixture groups, group index entries or evidence files; nothing new under `Fixtures/corner/` or `docs/evidence/10-20/` in this change.
- No change to `SideLight` cuts, `ColorCalibration.kt` chroma floors, the keep rule or the agreement gate (tuning belongs to 10.21 / the pastel result).
- No new Learner text in `en.json`; no new permission, dependency or SDK.
- No edits to planning artifacts, `_bmad/` or `.claude/skills/`.

**Pending-device (deferred to the recording session, before merge; not residual risks):**
- AC2: `Fixtures/corner/` contents per device (stickered, stickerless, glossy on S24 FE, ≥ 2 front lights + 1 backlit, torch on/off on S24 FE) and the group index `Fixtures/corner/groups.json` naming the held-out and tuning splits.
- AC3: held-out corner-model metrics (recall ≥ 92%, rejection ≥ 96%, mean corner error ≤ 3%) per device, misses recorded and routed to the PO.
- AC4: the pastel stickerless cube's plausibility result (and any floor tuning) written to `docs/evidence/10-20/`.
- AC5: the 10.22 baseline report and backlit light scores written to `docs/evidence/10-20/`.

## Settled UI behavior (debug recorder only)

- **Torch toggle:** on a camera with a flash unit, the recorder in corner mode shows Quick Scan's torch control (the same `TorchButton` composable: bolt icon + state label "Torch on"/"Torch off", same en.json strings, top-end of the camera preview, tag `recorder:torch`), on by default. No torch control or torch copy without a flash, and none in face (live) mode. The Settings dialog's "Torch on" checkbox stays and edits the same state.
- **Torch timing:** in corner mode the physical torch follows the operator's choice from the moment corner mode is set, *including while calibrating*, so swatches are sampled with the chosen torch state. Face mode and leaving the recorder turn it off (as today).
- **Toggling clears calibration:** a torch change is a new capture condition (existing 10.19 rule, `FixtureRecorderScreen.kt` ~L783-806): it starts a new group and clears the six calibrated colors with the existing status "Cube or light changed: calibrate all six again." — same path as the Settings dialog, no extra confirmation.
- **Capture while metering settles:** in corner mode a Capture press while 10.22 metering is still settling waits (status "Waiting for exposure to settle…") until settled or the settle cap, then captures — as Quick Scan queues a manual press. The settle outcome is recorded in the hold's diagnostics.
- **Light direction:** the Settings picker gains "Side (light from one side)" → `"side"` beside Front and Back.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| New group, flash device | corner mode, torch on, calibrated, Hold 1 saved | `group.json`: `pipeline "10.22"`, device, cube, light, lightDirection, `torch`, `calibration` (the six recorder centres), `holds:[{hold:1,torch:true}]` | manifest rewrite failure → status error, capture not lost |
| Hold 2 saved | same group | `holds` gains `{hold:2,torch:<state at capture>}`; Hold 1 entry unchanged | — |
| No-flash device (Lenovo) | corner mode | no torch control; manifest and holds `torch: null` | — |
| Hold diagnostics | a hold is saved | `corner-N.json` has `diagnostics`: top/left/right levels, `lightScore`, `lightPassed`, `lightMode` (`calibrated`/`uncalibrated`/`torch` as ScanDiagnostics), uncalibrated score, torch, settle (`settleMs`, `outcome` settled/timeout, `lock`, `futureMs`) | a face not measured → its level and score `null` |
| Burst line | adb burst, recorder calibrated or not | each line adds `light`: `{top,left,right,score,mode,passed}` from `SideLight.check(faces, calibration-or-null)`; no corner → `light: null` | — |
| Old fixtures | schema-3 records, no manifest | decode as before; replay results for legacy groups unchanged | — |
| 10.22 groups in replay | manifests with `"10.22"` | baseline report per device × direction × light name × torch (see Tasks); no assertion fails on their numbers | — |
| No group index | `groups.json` absent | replay/plausibility/index tests pass and print "no group index"; `evaluate_model.py` evaluates all fixtures and prints a notice | — |
| Index present | `groups.json` | evaluator defaults to held-out groups only (`--split all` overrides); training excludes held-out groups; index test enforces AC2 coverage | malformed index or unknown group → test/script fails with the group name |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `LightDirection` (FRONT/BACK only), `CornerFixtureRecord` (schema 4, optional `torch`), `CornerGroupManifest` (pipeline, lightDirection, device, cube, light, torch), `CornerFixturePaths` (`MANIFEST`, `PIPELINE_10_19`, `KEYPOINTS_DIR`), `CornerFixtureJson` (no `ignoreUnknownKeys`). Extension point for SIDE, `PIPELINE_10_22`, manifest calibration/holds, hold diagnostics, group index.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SideLight.kt` -- `check(faces, saved)`, `checkSamples`, `Verdict(passed, top/left/rightLevel, mode).score`. Read-only.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScanClassification.kt` L90-156 -- `report()` gives per-face `grid`, `closeCalls`, `disagreements`, `uncalibrated`, `quality`, `correctionRejected`. Read-only; the replay must call it for keep reasons.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ColorCalibration.kt` L8-36 -- `ColorPlausibility.plausible`/`valid`. Read-only here.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanSideFaceReplayTest.kt` -- `groups()` L110-137 walks `../../Fixtures/corner`, `scored = manifest?.pipeline == PIPELINE_10_19` L124; `Group` lacks torch; `tally()`; `report()` L231-270 stdout only; `violations()` / `missing()`; zero-wrong-kept test enforces on every group (must skip 10.22 groups); `LEGACY_DIRECTION` L425.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ColorCalibrationTest.kt` L94-112 -- live-fixture plausibility walk (`Fixtures/live`); add the corner pastel path beside it.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/LiveFixtureTest.kt` L175-229 -- manifest round-trips using `PIPELINE_10_19`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` L478-498 -- `groupManifest()` hardcodes `PIPELINE_10_19`; `recordedTorch(hasFlash, torchOn)`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderBurst.kt` -- `companion line(...)` (org.json), `add(...)`; no light fields; no test.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- `RecorderCamera` ~L84-117 (no settle/lock/calibration API); `remeterCorner` L196-200; `meterOnCube` L249-268 (future ignored, no lock); `applyTorch` L271-275 (`torchWanted && cornerMode`, cornerMode set only after calibration); `observeExposure` L356-375 (AE state source for a SettleTracker); `analyze` L380+ (burst add L412, capture hand-off ~L414-428, `CapturedFrame` ~L72-81).
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- `torchOn` L161, `recordedTorch` L163, `camera.cornerMode/torch` effects L189-190, `groupDirectory()` L200-217 (writes manifest once), `writeKeypoints` L220-245, `captureCorner` L250-290, `confirmCornerFace` L320-370 (builds `CornerFixtureRecord`, writes JSON+JPEG), re-meter effect L408-410, settings save L783-810, settings dialog L1210-1280 (direction radios L1251-1252, torch checkbox L1266-1269).
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- 10.22 settle/lock: `SettleTracker` L874, `ExposureLocks` L814, `Settle` L805, companion `settleOutcome` L748, `SETTLE_CAP_MS` L661, `LOCK_UNSUPPORTED` L677; private `applyLocks` L384-403, `lockSupport` L375, `moveLocks` L347-365, `settled()` L312-327, `observeAeState` L535-557. Reuse; extract the private apply/support pieces to internal helpers with no behavior change.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` L541-585 -- `TorchControl`, private `TorchButton` (tag `quickScan:torch`, en.json `btn.quick-scan.torch-on/off`).
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `cornerFrameLine` L68-110 (lightMode rule), `onMeteringSettled` L219. Recorder diagnostics must use the same `lightMode` rule.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` (JUnit5; `groupManifest` L352-364 asserts "10.19") and `FixtureExportTest.kt` (JUnit5, `@TempDir`). org.json needs JUnit4 `@RunWith(RobolectricTestRunner::class)` (pattern: `ScanDiagnosticsRunLineTest.kt`, `ScanFrameSourcePolicyTest.kt`).
- `tools/corner-model/train_corner_model.py` L118-210 -- `fixture_kind` (manifest = has `pipeline`+`lightDirection`; unknown kind → SystemExit), `load_corner_keypoint_fixtures` (rglob `*.json`). `tools/corner-model/evaluate_model.py` L37-43 thresholds, L126-159 `evaluate_real_corner_fixtures` (per device, `MIN_DEVICE_*` L205-209, `REQUIRED_DEVICES` L190). `tools/corner-model/README.md`. No Python tests; CI runs no Python.
- `tools/sticker-model/holdout.json` -- 10.23's `corner/<group>` naming; the 10.20 index uses the same folder names. Read-only.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../recognition/LiveFixture.kt` -- add `LightDirection.SIDE` (`"side"`); `CornerFixturePaths.PIPELINE_10_22 = "10.22"` and `GROUP_INDEX = "groups.json"`; `CornerGroupManifest` optional `calibration: CalibrationCenters? = null` and `holds: List<CornerHoldManifest> = emptyList()` (`hold`, `torch: Boolean?`); `CornerFixtureRecord` optional `diagnostics: CornerHoldDiagnostics? = null` (levels, scores, passed, mode, torch, settle ms/outcome/lock/futureMs); `CornerGroupIndex` (`kind "corner-group-index"`, `schemaVersion 1`, `groups: [{group, split: heldout|tuning, pastel=false}]`) with encode/decode validating split values and unique groups. -- one source for the fixture formats.
- `app/src/camera/kotlin/.../ScanFrameSource.kt` -- extract the exposure-lock apply and lock-support code into internal helpers usable by the recorder; Quick Scan behavior unchanged (existing `ScanFrameSourcePolicyTest` stays green). -- recorder reuses 10.22's settle-and-lock (AD-9).
- `app/src/camera/debug/.../recorder/FixtureRecorderActivity.kt` -- corner metering uses `SettleTracker` + `ExposureLocks` as Quick Scan does (meter on cube, AWB on Hold 1 only, settle signal, lock on settle, release after a capture), exposes the settle state and lock label per capture, and accepts the recorder calibration for the burst; torch applied whenever corner mode is on and wanted (independent of calibration); `CapturedFrame` carries the settle info and the applied torch state.
- `app/src/camera/debug/.../recorder/RecorderRules.kt` -- `groupManifest` writes `PIPELINE_10_22`, calibration and holds; pure `withHold(manifest, hold, torch)`; pure `holdDiagnostics(faces, calibration, torch, settle)` using `SideLight` and the ScanDiagnostics `lightMode` rule; pure `recorderTorchOn(cornerMode, hasFlash, torchOn)`.
- `app/src/camera/debug/.../recorder/FixtureRecorderScreen.kt` -- per "Settled UI behavior": torch control, torch during calibration, toggle → existing new-group/recalibrate path, Side direction option, capture waits for settle; on each hold save write `diagnostics` into `corner-N.json` and rewrite `group.json` with the hold's torch (manifest created with the calibration when the group starts).
- `app/src/camera/kotlin/.../GuidedScanScreen.kt` -- make `TorchButton`/`TorchControl` reusable by the recorder (internal, test tag parameter); Quick Scan rendering and tag unchanged.
- `app/src/camera/debug/.../recorder/RecorderBurst.kt` -- `line(...)` takes the light verdict and writes `light: {top,left,right,score,mode,passed}` (null when no corner); stays debug-only.
- `app/src/testDebug/.../recorder/RecorderBurstTest.kt` (new, Robolectric JUnit4) -- `RecorderBurst.line`: existing fields, light block with values, null corner. `RecorderRulesTest.kt` -- manifest 10.22 + calibration + holds, `withHold`, `holdDiagnostics` (calibrated, uncalibrated, torch, unmeasured face), `recorderTorchOn` (on while calibrating in corner mode, off in face mode, null/no flash). Manifest + record round-trip on disk via `@TempDir` in `FixtureExportTest.kt` or a new test.
- `core/domain/src/test/.../LiveFixtureTest.kt` -- SIDE, new optional fields round-trip, old manifests/records without them still decode, group index decode/validation.
- `core/domain/src/test/.../QuickScanSideFaceReplayTest.kt` -- take the fixtures root as a parameter; carry pipeline, torch, light name and split per group; 10.22 groups get a non-enforced **baseline** section per device × direction × light name × torch, scenarios NONE and MATCHING: side stickers correct/uncertain/wrong, side faces kept, per face the keep/grid reason (`closeCall`/`disagree` counts from `QuickScanClassification.report`), confidently wrong among all CONFIDENT stickers per group on kept and discarded faces separately, and per backlit group the `SideLight` scores per hold (flagging any backlit hold that passes); print it and write it to `core/domain/build/reports/quick-scan/baseline-10-20.md`. 10.22 groups are excluded from every enforcing assertion. A test builds a temp root by copying an existing legacy group with a 10.22 manifest and checks the baseline section appears with its counts and no assertion fails.
- `core/domain/src/test/.../CornerGroupIndexTest.kt` (new) -- when `Fixtures/corner/groups.json` exists: every listed folder exists with a 10.22 manifest, every 10.22 group is listed once, and AC2 coverage holds (per device: held-out ≥ 20 positive and ≥ 10 negative keypoint fixtures; for each front-lit (device, light name, torch) condition ≥ 1 held-out and ≥ 1 tuning group; a backlit group per device); absent → passes with a notice. Coverage logic tested on temp indexes.
- `core/domain/src/test/.../ColorCalibrationTest.kt` -- pastel path: for index groups with `pastel: true`, the recorder calibration passes `ColorPlausibility.valid` and every non-centre labeled sticker is `plausible` for its truth colour; absent → passes with a notice; helper tested on a temp group.
- `tools/corner-model/corner_index.py` (new, stdlib only) + `test_corner_index.py` (unittest) -- read/validate the index, map a fixture path to its group and split. `evaluate_model.py` -- `--split {heldout,tuning,all}` (default heldout when the index exists, else all with a notice), report per device as now plus the split used. `train_corner_model.py` -- `fixture_kind` skips the index; held-out groups never enter training. `README.md` -- the index format and the AC3 command.

**Acceptance Criteria:**
- Given the debug fixture recorder in corner mode, when an operator records a capture group, then `group.json` records `"pipeline": "10.22"`, device model, cube id, light direction (front/back/side), light name, the torch state per hold and the recorder calibration; each `corner-N.json` carries the light levels, light score, light mode and metering settle next to `corner-N.jpg`.
- Given a device with a flash, when corner mode is open, then the torch control is shown and on by default, and the calibration is sampled with the chosen torch state; given no flash, no torch control appears.
- Given an adb recorder burst, when it writes `frames.jsonl`, then each line carries the light score and the three face levels; `RecorderBurst.line` has a JVM test; the burst remains in debug-only source sets.
- Given `./gradlew spotlessCheck build` with today's fixtures (no 10.22 groups, no index), then it passes and the replay, index and pastel checks report that their inputs are absent.
- Given 10.22 groups and an index (temp roots in tests), then the replay writes the baseline without enforcing it, the index test enforces AC2 coverage, and `evaluate_model.py` scores only held-out groups by default.

## Spec Change Log

## Review Triage Log

### 2026-11-23 — Review pass
- verdicts: 40 findings — high 0, medium 6, low 24, false 10, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent-alignment) UI behaviors (torch shown/default, toggle clears calibration, capture waits) and Activity wiring only tested through pure helpers — the torch-clear rule is now extracted and tested (see VG3 patch); the rest needs CameraX and is exercised in the before-merge device session.
  - `[false]` `[reject]` (intent-alignment) AD-15: release-compiled ScanFrameSource.kt / GuidedScanScreen.kt touched — only visibility of two behavior-identical helpers and a defaulted testTag parameter changed; no recorder code or debug tool reaches release, Quick Scan tags/behavior unchanged and its tests pass.
  - `[low]` `[reject]` (intent-alignment) CornerGroupIndexTest does not check the cube mix, two front lights or S24 torch on/off — AC2 contents are verified by hand at the device session (pending-device); adding those rules adds branches for a check that runs once.
  - `[low]` `[reject]` (intent-alignment) coverage keyed on group-level torch, not per-hold — a torch change always starts a new group, so per-hold torch equals the group torch by construction.
  - `[false]` `[reject]` (intent-alignment) evaluate_model.py exits non-zero on a miss though a miss "does not block 10.21" — the exit code is the report's verdict; nothing gates 10.21 on it, and the AC routes misses to the PO.
  - `[false]` `[reject]` (intent-alignment) AC5 report goes to build/reports, not docs/evidence/10-20 — evidence files are pending-device per the intent ("do not fabricate evidence"); the session copies the generated report.
  - `[low]` `[defer]` (intent-alignment + blind) epic-10-context.md rewrite (workflow-generated, step-01) drops some constraints still in force (LiteRT 16 KB alignment, shadow-reader guardrails, torch carry rules) — agent-context file; deferred.
  - `[false]` `[reject]` (blind) 10.22 groups escape the zero-confidently-wrong-on-kept assertion — the intent's AC5 says the 10.22 results are reported "without enforcing the bar"; 10.21 enforces it.
  - `[low]` `[reject]` (blind) baseline "all" rows pool held-out and tuning groups — each group row prints its split; 10.21 builds its own tuning tooling on the tuning split; per-split aggregation adds branches to a report-only test.
  - `[medium]` `[patch]` (blind + edge) an unlisted 10.22 group (or any 10.22 group before groups.json exists) is used for corner-model training — patched: corner_index.training_allowed refuses held-out groups and 10.22 groups not listed as tuning; tested.
  - `[low]` `[reject]` (blind + edge) index group names with path separators / nested group folders read differently by Python and Kotlin — the recorder only creates top-level folders and the index is hand-written in-repo; guard adds validation for a case nobody meets.
  - `[low]` `[reject]` (blind) pastel check passes when no group is marked pastel; coverage ignores SIDE groups and cube types — the pastel cube and AC2 mix are pending-device acceptance items checked at the session.
  - `[low]` `[reject]` (blind) applied torch read across threads / stale after a failed enableTorch — a torch change starts a new group and forces six new swatches, so no capture can land in the window; a failed enable keeps the state the camera actually has.
  - `[low]` `[reject]` (blind + edge) recorder moveLocks evaluates change twice — same pattern as Quick Scan's existing moveLocks; the lambda's only impure read (cornerMode) changes only on mode switch, which clears locks anyway.
  - `[low]` `[reject]` (blind) a press past the settle cap + slack captures with outcome "settling" without telling the operator — the cap makes SettleTracker report TIMEOUT first, so this needs a stuck tracker; the outcome is recorded in the hold's diagnostics.
  - `[false]` `[reject]` (blind) torch toggle shows "Cube or light changed" and clears without confirmation — both are the spec's settled UI behavior (reuse of the existing path and text).
  - `[low]` `[reject]` (blind) lightMode / lock are unchecked free-form strings — written only by the recorder's own constants; a validator adds surface for no observed harm.
  - `[medium]` `[patch]` (edge) second capture before re-detection reports the previous metering as settled and records stale diagnostics with AE unlocked — patched: a corner capture resets the settle state so the next capture reports no metering until re-metered.
  - `[low]` `[patch]` (edge) settleAtCapture may return null when SETTLING flips to settled between the analyzer's check and the capture — patched with the race below.
  - `[low]` `[patch]` (edge) settledNow CAS publishes settleReported before lastSettle — patched: settledNow/settleAtCapture synchronized, settleAtCapture calls settledNow first.
  - `[low]` `[patch]` (edge) a capture while settling records lock "unsupported" — patched: records "none" while settling.
  - `[low]` `[reject]` (edge) duplicate of moveLocks finding above.
  - `[low]` `[reject]` (edge) duplicate of unlisted-group training finding above (shared patch).
  - `[low]` `[reject]` (edge) duplicate of nested-group naming finding above.
  - `[low]` `[reject]` (edge) Kotlin index defaults kind/schema while Python may require them — a hand-written index missing them fails loudly in Python; no silent divergence.
  - `[low]` `[reject]` (edge) --split heldout with a missing directory gives a misleading error — the run still stops loudly; cosmetic.
  - `[false]` `[reject]` (edge) pastelProblems IndexOutOfBounds on short sticker lists — CornerFixtureJson.decode requires 9 valid stickers per sample before the check runs.
  - `[low]` `[reject]` (edge) a pastel entry with a missing folder is reported as an implausible null calibration — it still fails, and CornerGroupIndexTest names the missing folder.
  - `[low]` `[reject]` (edge) groups with an unknown pipeline tag are no longer light-checked as legacy — no such tag exists; the recorder writes only 10.22.
  - `[medium]` `[patch]` (edge) a face→corner mode switch keeps colours calibrated with the torch off, so a torch-on group carries a calibration from a different torch state — patched: recalibrates() also fires on a mode change that changes the lit torch; tested.
  - `[low]` `[reject]` (verification-gap 1) index and pastel checks pass vacuously when 10.22 groups exist but groups.json is missing — the intent-contract's Always rule passes on an absent index; AC2 is a before-merge human-gated item.
  - `[medium]` `[patch]` (verification-gap 2) Python split wiring (training exclusion, index skip in fixture_kind) untested — patched: decisions moved into corner_index and unit-tested on temp dirs; Python still runs outside CI (repo convention).
  - `[medium]` `[patch]` (verification-gap 3) screen torch rules (recalibrate on torch change, hold torch) inline and untested — patched: recalibrates()/holdTorch() extracted to RecorderRules with tests.
  - `[low]` `[patch]` (verification-gap 4) RecorderBurst.add calibration pass-through untested — patched: test through add() and frames.jsonl.
  - `[medium]` `[patch]` (verification-gap 5) baseline counters checked only against themselves — patched: independent invariant assertions.
  - `[low]` `[defer]` (verification-gap 6) recorder Activity settle/lock/torch wiring has no JVM test — needs CameraX; checked at the device session.
  - `[false]` `[reject]` (verification-gap other) newCondition duplicates resetEntry — same behavior, no harm named.
  - `[false]` `[reject]` (intent-alignment) reading R2/R3 classification — descriptive, no defect.
  - `[false]` `[reject]` (intent-alignment) pipeline-tag "10.19" title vs "10.22" AC — the AC and PO clarification both say 10.22, which the diff writes.
  - `[false]` `[reject]` (intent-alignment) AC4 surface matches — descriptive, no defect.

### 2026-11-23 — Review pass (follow-up)
- verdicts: 47 findings — high 0, medium 2, low 37, false 8, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent-alignment) UI behaviors and Activity wiring only tested through pure helpers — carried: needs CameraX; exercised in the before-merge device session.
  - `[low]` `[reject]` (intent-alignment) replay prints "no 10.22 groups recorded yet" rather than "no group index", and skips if Fixtures/corner is missing — the replay is report-only and Fixtures/corner is committed; wording difference with no harm.
  - `[low]` `[reject]` (intent-alignment) replay labels an unlisted group "unsplit" instead of failing; Kotlin bad-split error may not name the group — CornerGroupIndexTest fails on unlisted/unknown groups by name; the replay is report-only.
  - `[low]` `[reject]` (intent-alignment) AC2 coverage narrower than the full cube/light list — carried: pending-device, checked at the session.
  - `[false]` `[reject]` (intent-alignment) recalibration on a mode switch goes beyond the intent — it implements the settled rule that swatches are sampled with the chosen torch state.
  - `[low]` `[reject]` (intent-alignment) extra settle values `settling` / lock `none` — they record a real state (capture during settle) the intent did not name; no harm.
  - `[false]` `[reject]` (intent-alignment) training also excludes unlisted 10.22 groups — carried from the earlier patch; it is the held-out-leak guard.
  - `[false]` `[reject]` (intent-alignment) release source set touched — carried: visibility/defaulted parameter only, behavior unchanged.
  - `[low]` `[patch]` (intent-alignment) Settings footer shows torch copy on a no-flash device — patched: footer says "cube or light" when there is no flash.
  - `[low]` `[defer]` (intent-alignment) epic-10-context.md rewrite in the feature commit — carried: already deferred (agent-context file).
  - `[false]` `[reject]` (intent-alignment) AC5 report in build/reports — carried: evidence is pending-device.
  - `[low]` `[reject]` (verification-gap 1) evaluate/train call sites of corner_index untested — the call sites are one-line delegations to the tested corner_index functions; a test must import the TensorFlow scripts, which the stdlib test command cannot; outputs print the split and counts at the session.
  - `[low]` `[reject]` (verification-gap 2) test_corner_index.py not run by CI — repo convention: CI runs Gradle only; the tooling is run by hand at the device session (README command).
  - `[low]` `[defer]` (verification-gap 3) recorder settle/lock/torch wiring has no JVM test — carried: already deferred, device session.
  - `[low]` `[patch]` (verification-gap other) README fine-tuning line states only the held-out exclusion — patched: it now names the unlisted-10.22 exclusion too.
  - `[low]` `[defer]` (blind) epic-10-context.md says 10.1–10.19 are done though 10.11 is backlog — agent-context file; deferred.
  - `[low]` `[reject]` (blind) burst lines lack torch/settle/lock and use SideLight's mode — the spec's matrix defines the burst light block from SideLight.check; holds carry torch and settle.
  - `[low]` `[reject]` (blind) nothing reads the per-hold settle diagnostics — the data is recorded for 10.21; adding report branches to a report-only test is not needed now.
  - `[low]` `[patch]` (blind) manifest calibration and record calibration never compared — same root cause as the mid-group swatch re-save below; patched there, so they cannot diverge.
  - `[low]` `[reject]` (blind) one-hold partial groups counted — a one-hold group is still valid capture data; rare (condition change after hold 1).
  - `[low]` `[patch]` (blind) a damaged 10.22 group.json is trained on as an older group — patched: corner_index.pipeline_of raises on an unreadable manifest, training stops with SystemExit naming it; tested.
  - `[low]` `[reject]` (blind) sticker-model holdout.json not synced with groups.json — sticker-model tooling (10.23) and its deferred 10.20-groups evaluation item; outside this intent.
  - `[low]` `[patch]` (blind) default evaluator drops the 10.19 D5 fixtures once the index exists — patched: README says D5 reruns with `--split all`.
  - `[false]` `[reject]` (blind) pastel check does not test classification — the intent asks for the plausibility check path.
  - `[low]` `[reject]` (blind) light names split by case — recorder sessions are run by one operator from the same Settings value; normalising adds code for no observed case.
  - `[low]` `[reject]` (blind) --json-out lacks per-group lists — the evidence can quote groups.json; extra output not required.
  - `[false]` `[reject]` (blind) settle null looks like an old record — new records carry a `diagnostics` object; old ones have none.
  - `[low]` `[reject]` (blind) diff carries the 10.24–10.26 planning context — part of the already-deferred epic-context rewrite.
  - `[low]` `[reject]` (edge) capture before metering is requested skips the wait (settle null) — needs a press within one frame of re-detection or with no cube detected (then nothing to meter); recorded as null settle.
  - `[medium]` `[patch]` (edge) after the hold-1 capture the next detected frame re-meters and relocks white balance before hold 2 (cornerMeterAwb stays true until the save) — patched: a corner capture sets cornerMeterAwb = false; a new group's hold 1 sets it again.
  - `[low]` `[reject]` (edge) appliedTorch stale on enableTorch failure — carried.
  - `[low]` `[reject]` (edge) moveLocks evaluates change twice — carried.
  - `[false]` `[reject]` (edge) lockExposure outside corner mode records "unsupported" — settledNow runs only in corner mode (analyzer guard, corner capture path).
  - `[low]` `[patch]` (edge) a swatch re-saved mid-group leaves group.json with the old calibration — patched: saveSwatch starts a new group when one is open.
  - `[low]` `[reject]` (edge) partial corner-N files on a write failure — rare IO failure; the status reports it; atomic writes add code.
  - `[low]` `[reject]` (edge) torch control hidden when shipped strings fail to load — bundled content; same gate as Quick Scan's TorchControl.
  - `[low]` `[reject]` (edge) Kotlin/Python unknown-key and kind/schema rules differ — carried.
  - `[low]` `[reject]` (edge) schemaVersion true/1.0 accepted in Python — hand-written index; no realistic case.
  - `[low]` `[reject]` (edge) path separators in group names — carried.
  - `[low]` `[reject]` (edge) symlinked group folder escapes the split — the recorder writes real folders; no case.
  - `[false]` `[reject]` (edge) a root-level non-group folder treated as a group — Fixtures/corner holds only group folders; keypoints live inside each group.
  - `[low]` `[reject]` (edge) --split with a missing directory names the wrong cause — carried.
  - `[low]` `[reject]` (edge) burst light null when a detected corner has != 3 faces — a detected corner always has three faces in CornerReader; cosmetic.
  - `[medium]` `[patch]` (edge claim) AWB at Hold 1 only — same root cause as the AWB re-meter above; same patch.
  - `[low]` `[reject]` (edge claim) every hold carries a settle — duplicate of the capture-before-metering row.
  - `[low]` `[patch]` (edge claim) manifest calibration is the one recorded with — duplicate of the swatch re-save row; same patch.
  - `[low]` `[reject]` (edge claim) torch control shown whenever corner mode is open — duplicate of the strings-load row.

## Design Notes

- Diagnostics live inside `corner-N.json` (an optional field) rather than a sibling file, so the replay's `corner-\d+\.json` discovery and the Python `rglob("*.json")` loader are not confused by a new file kind; the index carries `kind: "corner-group-index"` for the same reason.
- `pipeline` names the newest pipeline the group was recorded with; "10.22" implies the 10.19 lighting (light check, cube metering, torch) plus 10.22 settle-and-lock, which is why the recorder must use 10.22's metering before it may write the tag.
- The `pastel` flag sits in the index (written after the session with the split) so no recorder UI is needed for it.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-20 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-20 && python3 -m unittest discover -s tools/corner-model -p 'test_*.py'` -- expected: OK.
- `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*' --rerun` -- expected: pass; output shows legacy report unchanged and "no 10.22 groups".

## Auto Run Result

Status: done (code part; the recording session on the Lenovo TB128XU and Galaxy S24 FE is a before-merge human gate).

**Summary:** The debug fixture recorder now records 10.22-pipeline corner groups: `group.json` carries pipeline "10.22", device, cube, light direction (front/back/side), light name, recorder calibration and torch per hold; each `corner-N.json` carries the hold's light check and metering-settle diagnostics; corner metering uses 10.22's settle-and-lock; Quick Scan's torch button appears on flash devices (on by default, lit while calibrating, a change recalibrates); burst lines carry the light check. A group index (`Fixtures/corner/groups.json`, held-out/tuning, pastel flag) is defined and consumed by the replay baseline (report-only), a coverage test, the pastel plausibility check, `evaluate_model.py --split` and training exclusion. No fixtures or evidence were created.

**Files changed:**
- `core/domain/.../recognition/LiveFixture.kt` -- SIDE, PIPELINE_10_22, manifest calibration/holds, hold diagnostics, group index types.
- `app/src/camera/kotlin/.../ScanFrameSource.kt` -- lock helpers shared (no behavior change).
- `app/src/camera/kotlin/.../GuidedScanScreen.kt` -- TorchButton internal with a test tag parameter.
- `app/src/camera/debug/.../recorder/FixtureRecorderActivity.kt` -- settle-and-lock metering, torch while calibrating, settle/torch per capture, burst calibration.
- `app/src/camera/debug/.../recorder/FixtureRecorderScreen.kt` -- torch control, side direction, settle wait, diagnostics and manifest per hold.
- `app/src/camera/debug/.../recorder/RecorderRules.kt`, `FixtureExport.kt`, `RecorderBurst.kt` -- pure rules, writers, burst light block.
- Tests: `RecorderBurstTest.kt` (new), `RecorderRulesTest.kt`, `FixtureExportTest.kt`, `LiveFixtureTest.kt`, `QuickScanSideFaceReplayTest.kt`, `CornerGroupIndexTest.kt` (new), `ColorCalibrationTest.kt`.
- `tools/corner-model/corner_index.py` + `test_corner_index.py` (new), `evaluate_model.py`, `train_corner_model.py`, `README.md`.
- `_bmad-output/implementation-artifacts/epic-10-context.md` -- regenerated by step 01 (stale cache).

**Review:** 40 findings. Patched 13 rows (6 medium, 7 low entries): stale settle after capture, settle races, "none" lock word while settling, face→corner mode recalibration under the torch, torch rules extracted and tested, training leak of unlisted/held-out 10.22 groups, Python wiring tests, burst add() test, baseline counter invariants. Deferred 2 (epic-context constraints, Activity wiring without JVM test). All other findings rejected with reasons in the triage log.

**Follow-up review recommended:** true -- 6 medium entries patched (0 high). Unverified risk: the patched recorder settle/lock synchronization and torch/mode recalibration live in the Activity/screen and are only exercised on a device.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (4m 45s); `python3 -m unittest discover -s tools/corner-model -p 'test_*.py'` OK (8 tests); `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*' --rerun` pass. Matrix rows audited against the tests.

**Pending-device (before merge):** AC2 fixtures and `groups.json`, AC3 held-out metrics, AC4 pastel result, AC5 baseline and backlit light scores under `docs/evidence/10-20/`.

**Residual risks:** none beyond the follow-up note.

### Follow-up review pass (2026-11-23)

**Patched:** 1 medium entry (a corner capture no longer lets the next metering re-lock white balance before hold 2 — `FixtureRecorderActivity.kt`), 5 low (mid-group swatch re-save starts a new group so `group.json` calibration matches the records — `FixtureRecorderScreen.kt`; no torch copy in Settings without a flash; `corner_index.pipeline_of` refuses an unreadable manifest so a damaged 10.22 group cannot be trained on, with a test, and `train_corner_model.py` stops with its name; README fine-tuning rule and D5 `--split all` note).
**Deferred:** 1 new (epic-10-context.md marks 10.11 done); 2 carried.
**Rejected:** 33, reasons in the triage log.
**Follow-up review recommended:** false — follow-up pass with no high patched; the work has converged.
**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL; `python3 -m unittest discover -s tools/corner-model -p 'test_*.py'` OK (9 tests); `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*' --rerun` pass.
**Pending-device (before merge):** AC2–AC5 unchanged; the session also confirms the hold-2 white balance carries from hold 1.
**Residual risks:** none.
