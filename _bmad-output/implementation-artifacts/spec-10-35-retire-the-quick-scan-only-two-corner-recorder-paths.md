---
title: 'Story 10.35: Retire the Quick-Scan-only two-corner recorder paths'
type: 'refactor'
created: '2026-11-26'
status: 'done'
baseline_revision: 'd5662d8f614948fee3639c917c6257012e6af8f5'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-26-retire-recorder.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Two tracked reader outputs are stale against their own commands on main: evaluateMatrix rewrites docs/spikes/oq-8-recognition-matrix.md and exportKotlinStickerReads rewrites docs/evidence/10-23/kotlin-reads.json.
    evidence: |-
      Story 10.35's legacy replay ran both commands on the removal tree and on an untouched export of the baseline d5662d8f614948fee3639c917c6257012e6af8f5; both produced the same output, which differs from the committed files, so the drift predates this story. Both files were restored, not refreshed.
    location: >-
      docs/spikes/oq-8-recognition-matrix.md; docs/evidence/10-23/kotlin-reads.json
    severity: low
---

<intent-contract>

## Intent

**Problem:** The separate debug fixture recorder (`FixtureRecorderActivity`, all modes) drove the camera differently from the app and failed the Story 10.34 gate; it and the Quick-Scan-only two-corner helpers it alone used still sit in the tree, so the camera code carries two capture UIs and dead lock/settle code.

**Approach:** Take a caller inventory, then delete the recorder package, its manifest entry, tests, script commands and every helper left with no production caller, keep everything Case recognition and the historical fixture readers use (with a named owner), move the Story 10.28 nine-of-each-colour rule to `:core:domain` main as `StickerTruthCheck` (copied from PR #396), update build-logic so no policy or test names a recorder class, and record the legacy-replay and class-byte evidence.

Refs: FR-22, FR-28 · AD-9, AD-15, AD-18 (binding; no deviation is known: the removal keeps AD-9's frames-in-memory rule and only deletes AD-15 debug-only tooling; no feature flag level changes, AD-18) · Stories 10.10, 10.12, 10.20, 10.28, 10.32 · `sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md`, `sprint-change-proposal-2026-11-26-retire-recorder.md`.

## Boundaries & Constraints

**Always:**
- Device-free, not release scope. No Learner-visible change: no UI behaviour choice is open (no animation, auto-play, clearing or state-dependent copy changes); Learner strings, flag levels (`FeatureFlagLevels.kt`), manifests of non-debug variants and `app/src/main` behaviour stay as they are.
- Keep, and keep green, Case recognition: `CaseCaptureController`, `CornerHold` (as `CaseCapture.kt` uses it), `CornerDetector`, `CornerLocator`, `CornerReader`, `CornerGuide`, `CornerFrame`, `CornerPoint`, `LiteRtCornerDetector`, `GuidedScanPhase.CornerViewfinder` (and `CornerOutline`, which it draws), `QuickScanPlan` (`CornerViewfinder.totalHolds`, `CornerFixtureJson.decode`), `CornerSettle.CAP_MS` (owner: Case recognition, `:core:domain`; kept even though its adapter alias `SETTLE_CAP_MS` goes).
- Keep the historical readers, owner `:core:domain` evaluation, all staying in `core/domain/src/main` (moving them to the evaluation source set is out of scope): `QuickScanClassification`, `GridReason`, `SideLight` (minus the one recorder-only overload below), the whole fixture format in `LiveFixture.kt` (records, manifests, index, keypoint fixtures, `TruthSource`, `DeviceInfo`, diagnostics types and both encode and decode: the encoders are the format's own serializer, round-trip tested, and the reader tests build synthetic fixtures through them), `exportKotlinStickerReads`, `evaluateScanAccuracy`, `evaluateLiveBaseline`, `evaluateRecognitionApproaches`, `evaluateMatrix`, `evaluateQuickScan`, `ScanDiagnostics.onQuickScanRun` (debug and release).
- A disabled release flag alone never counts as evidence of dead code: "no production caller" means no caller in any `src/main`, `src/camera/**`, `src/debug`, `src/release` or `core/*/src/evaluation` source after the recorder is gone; test-only callers do not keep a helper alive unless it is listed as kept above.
- Every commit subject carries "10.35". Keep `app/src/camera/debug/AndroidManifest.xml` (build-logic adds it to every debuggable camera variant as a static manifest file) but with no `<activity>`; Story 10.34 will add to it.

**Never:**
- Never edit `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `prd.md` or `ARCHITECTURE-SPINE.md`; never push; never check out the PR #396 branch (read it with `git show 4be002d98a075f901478e736612444d115a8c8eb:<path>`).
- In `_bmad-output/implementation-artifacts/sprint-status.yaml` change only the `status:` lines named in the disposition table; never its keys, `action`, `owner` or `ref` text, and never add items.
- Never weaken, skip or delete a test that covers a kept item; delete only tests of removed code.
- Do not add `dev/cubentia/app/camera/session/**`, the Rec control, writer or labeller (Story 10.34). Do not delete historical evidence under `docs/evidence/`, `Fixtures/` data, or historical prose in spike reports.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Debug manifest clean | merged debug manifest after removal | no declared activity is in `dev.cubentia.app.camera.recorder` or has "Recorder" in its class name | the new test fails if one is planted (proved once against the baseline tree) |
| Debug camera set composed | build-logic fixture with a debug-only marker in `src/camera/debug/AndroidManifest.xml` | marker present in the debug merged manifest only; absent in release, benchmark and the camera-OFF debug variant | test fails if the debug camera manifest is not merged |
| Release guard planted violation | ShadowReaderPolicy with a `camera/sticker/` class and `sticker_reader*.tflite` asset (no recorder sample path) | both violations named; continuous and corner classes ignored | unchanged guard |
| Truth check pass | 54 stickers, nine of each colour, all reviewed | `StickerTruthDecision.Passed` | none |
| Truth check off-count | one colour 10, one 8 | `Refused` without reason; `Overridden(trimmed)` with a non-blank reason; blank reason refused | none |
| Truth check unreviewed pre-fill | a `PREFILLED` sticker with `reviewed = false` | `Refused` whatever the reason | none |
| Truth check wrong size | 53 stickers | `IllegalArgumentException` | require |

</intent-contract>

## Code Map

Removed (whole files):
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/` -- `FixtureRecorderActivity.kt` (859 lines), `FixtureRecorderScreen.kt` (1681), `RecorderRules.kt` (878; holds the recorder's two-hold helpers, `colorLabel`, `FACE_CENTER`, the 10.28 count/refusal logic), `RecorderBurst.kt`, `FixtureExport.kt`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/` (`FixtureExportTest`, `RecorderBurstTest`, `RecorderRulesTest`) and `app/src/testDebug/resources/recorder/`.
- `app/src/camera/debug/AndroidManifest.xml` -- remove the `FixtureRecorderActivity` `<activity>` and its adb comment; leave `<manifest><application/></manifest>`-equivalent.

Removed (members):
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CornerCapturePlan.kt` -- delete `CenterCheck` (no caller at all); keep `QuickScanPlan`.
- `core/domain/.../recognition/SideLight.kt` -- delete only `check(faces: List<FrameStats?>, saved)` (callers: `RecorderBurst` + `SideLightTest`); keep `level`, `nearestOnChroma`, `relativeBrightness`, `calibrated`, `check(top,left,right)`, `checkLab`, `checkSamples` (used by `QuickScanClassification` and the Fixtures/corner replays `QuickScanSideFaceReplayTest`/`QuickScanGateTuningTest`), `Mode`, `Verdict`, constants. Drop the matching `SideLightTest` cases only.
- `app/src/camera/kotlin/dev/cubentia/app/camera/ScanFrameSource.kt` -- delete `ExposureLocks` (~681), `SettleTracker` (~741), `lockSupport` (~514), `applyLocks` (~526), `LOCK_UNSUPPORTED` (~506-507, the recorder constant), `Settle` enum (~672), `settleOutcome` (~615), `SETTLE_CAP_MS`/`SETTLE_TOLERANCE`/`SETTLE_WINDOW_MS` (~491-504), `cubeMeteringRegion` (~554) and `chooseMetering` (~599): their only non-test callers were the recorder or each other. Keep `centreMeteringRegion`, `meteringAction`, `meteringFlags`, `MeteringRegion`, `METERING_LOCK_SECONDS`, `torchLit` (all used by `meterOnGrid`/torch code). Fix KDoc that says "shared with the debug fixture recorder" (~511, ~523, ~583, `meteringFlags` ~626-629). Drop now-unused imports (`Camera2CameraInfo`, `Camera2CameraControl`, `CaptureRequestOptions`, `CameraCharacteristics`, `CaptureRequest`, `CaptureResult`, `ExperimentalCamera2Interop` if unused).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/ScanFrameSourcePolicyTest.kt` -- delete the cases for the members above (metering-region ~75-95, settle ~100-271, locks ~299 and any `ExposureLocks` case); keep torch (`torchLit` ~353) and `meteringFlags` (~367) cases.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt:583-592` -- `TorchButton`'s `testTag` parameter was passed only by the recorder: inline the `"quickScan:torch"` tag and drop the Story 10.20 KDoc sentence.
- `tools/cube-session.sh` -- delete the `recorder` and `pull` commands and their two usage lines; the usage printers `sed -n '2,9p'` become `sed -n '2,7p'` (both occurrences).
- Comments naming the recorder as a live tool: `CornerLocator.kt:27`, `FrameReader.kt:66,671`, `CornerHold.kt:98-104` (`CornerSettle` KDoc: now "the cap Case capture's corner settle shares"; value unchanged). Historical comments about recorded data (LiveFixture.kt, evaluation, tests) stay.

Build-logic:
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/CameraSizePolicy.kt:4-8` -- `debugOnlyClassGlobs = listOf("dev/cubentia/app/camera/sticker/**")`; KDoc no longer names the recorder.
- `build-logic/.../tasks/CheckCameraSizeTask.kt:71` -- report line "Debug camera classes excluding debug-only tools, sticker reader, and legacy corner" (check `CheckCameraSizeTaskTest` for an asserted string and update it with it).
- `build-logic/src/test/.../policy/ShadowReaderPolicyTest.kt:23,38` and `CameraSizePolicyTest.kt:85` -- drop the recorder sample class / expected glob.
- `build-logic/src/test/.../functional/AndroidFixture.kt:72-77` -- fixture debug camera manifest declares a marker instead of an activity: `<application><meta-data android:name="dev.cubentia.fixture.DEBUG_CAMERA_MARKER" android:value="debug-camera"/></application>`.
- `build-logic/src/test/.../functional/CameraCompositionFunctionalTest.kt:56,71-87,121` -- assert `DEBUG_CAMERA_MARKER` in manifest == (variant == "debug"); rename the test `debuggable camera OFF has no debug camera tools` and assert the marker is absent; the raised-release test asserts the marker absent.

Kept (read-only evidence): `core/domain/src/main/.../CaseCapture.kt` (uses `CornerHold`), `GuidedScanActivity.kt:588` (`onQuickScanRun`), `core/domain/src/evaluation/.../sticker/KotlinStickerReads.kt` (`QuickScanClassification`, `CornerFixtureJson`), `core/domain/build.gradle.kts:32-116` (the six evaluation tasks), `ReleaseConventionPlugin.kt:146-166` (debug camera source set and manifest wiring).

Added:
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/StickerTruthCheck.kt` and `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/StickerTruthCheckTest.kt` -- copied from `4be002d98a075f901478e736612444d115a8c8eb` (introduced by its commits `2e0f4b9d`, `0fa2f0de`); `TruthSource` already exists on main in `LiveFixture.kt`. Rewrite only the KDoc lines that name `RecorderRules.kt`/`truthRefusal`/"the two-hold corner recorder": the rule's caller is Story 10.34's continuous-session labeller, and the refusal copy is debug labeller text (AD-15). Code and tests unchanged.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/DebugManifestTest.kt` -- Robolectric (`unitTests.isIncludeAndroidResources = true` already) over the merged debug manifest: `packageManager.getPackageInfo(packageName, GET_ACTIVITIES).activities` has no name starting `dev.cubentia.app.camera.recorder.` or containing "Recorder" (see `CameraScanLauncherTest.kt` for the Robolectric set-up used in this source set).
- `docs/evidence/10-35/inventory.md`, `legacy-replay.md`, `class-bytes.md`.

## Tasks & Acceptance

**Execution:**
- `docs/evidence/10-35/class-bytes.md` -- FIRST, on the untouched tree, run `./gradlew :app:checkCameraSize` and `./gradlew :app:assembleDebug` and record the `build/reports/cubentia/camera-size.txt` lines and the debug APK's `classes*.dex` total (`unzip -l`); repeat after the removal and record both with the delta -- the AC's class-byte record needs a before value.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/DebugManifestTest.kt` -- write it before deleting, run `./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.camera.DebugManifestTest` and confirm it FAILS on the baseline (planted violation = today's recorder activity), note that in `inventory.md`, then delete.
- `docs/evidence/10-35/inventory.md` -- one table per AC group (recorder package, each named symbol, the two-hold helpers in `RecorderRules.kt`, the four lock/settle helpers, plus every other helper this spec removes): caller file:line by category (Android, domain, evaluation, Gradle, build-logic, script, fixture decoder, test), disposition (removed / kept), owner for kept items, and for kept readers "stays in `core/domain/src/main`". Take it with `git grep -n -w` on the baseline tree (`d5662d8f614948fee3639c917c6257012e6af8f5`), before deleting.
- Removals and build-logic edits -- as the Code Map lists; then `./gradlew spotlessApply`.
- `core/domain/.../StickerTruthCheck.kt` + test -- copy as the Code Map says.
- `docs/evidence/10-35/legacy-replay.md` -- the single legacy-replay record (Story 10.34 appends to it): each command below with its exit result and a one-line outcome; state that tracked outputs it rewrites were byte-identical (`git diff --stat` empty) or show and restore any non-deterministic diff; name `af2a6d7a93d6caa737ac09ac5cc227eedbe2f0dd` (origin/main before this story) as the last revision that contains the recorder, with `git worktree add ../cubentia-recorder af2a6d7a93d6caa737ac09ac5cc227eedbe2f0dd` to check it out; add a section header "Story 10.34 continuous-session replay" left for 10.34.
- `Fixtures/README.md`, `tools/corner-model/README.md` -- where they tell an operator to record with the recorder or `cube-session.sh recorder|pull`, add a one-line note that the recorder was retired by Story 10.35 and point to `docs/evidence/10-35/legacy-replay.md`; keep the historical description.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- `status:` only, per the disposition table in Design Notes.
- `_bmad-output/implementation-artifacts/epic-10-context.md` -- recompile: read and follow `{project-root}/_bmad/render/bmad-build-auto/rubikxplorer-10-35-f75124f8ad8c/146e8f836e174cdfc70f/compile-epic-context.md` with epic 10, `_bmad-output/planning-artifacts/epics.md`, the planning-artifacts folder and this output path. The result has no live-tool mention of the recorder (Story titles such as 10.10/10.12/10.28 may stay as titles; the "Fixtures" rule says the 10.28 rule lives on in `StickerTruthCheck`; the "Two-corner code removal" bullet no longer says the recorder uses Corner* code) and covers Stories 10.34-10.36.

**Acceptance Criteria:**
- Given the baseline tree, when the inventory is taken, then `docs/evidence/10-35/inventory.md` lists every caller of the recorder package, `QuickScanPlan`, `CenterCheck`, `GridReason`, `SideLight`, `QuickScanClassification`, `CornerHold`, `RecorderBurst`, the two-hold helpers and `ExposureLocks`/`SettleTracker`/`lockSupport`/`applyLocks`, each marked removed or kept with an owner, kept readers marked "stays in core/domain/src/main".
- Given the removal, when `git grep -n -i "camera.recorder\|FixtureRecorder\|RecorderBurst\|RecorderRules\|ExposureLocks\|SettleTracker\|lockSupport\|applyLocks\|CenterCheck\|LOCK_UNSUPPORTED"` runs over `app build-logic core tools/cube-session.sh`, then nothing matches except historical prose comments about recorded data.
- Given Case recognition, when `./gradlew :core:domain:test --tests '*CaseCaptureControllerTest*'` and `:app:testDebugUnitTest --tests '*CornerLocatorTest*' --tests '*GuidedScanFlowTest*' --tests '*CornerModelTransformTest*' --tests '*CornerFixtureHarnessTest*'` run, then they pass, and every kept item in Boundaries still exists.
- Given the historical readers, when the legacy-replay commands run, then each succeeds and `legacy-replay.md` records it with the recorder's last revision and checkout command.
- Given build-logic, when `./gradlew -p build-logic test` runs, then no policy or test names a recorder class, the debug-camera marker test and the ShadowReader planted violations pass.
- Given the CI build job commands, when they run, then all pass and `class-bytes.md` shows before/after.
- Given the §4.5 action items, when the story closes, then their `status:` matches the table, and the table (for the PR body) is in this spec.

## Spec Change Log

## Review Triage Log

### 2026-11-26 — Review pass
- verdicts: 37 findings — high 0, medium 0, low 22, false 15, maybe-false 0
- findings:
  - `[low]` `[patch]` (verification-gap) StickerTruthCheckTest never decides a set with an unknown sticker and never pins that only unreviewed PREFILLED stickers count — added a case: one unknown, `unreviewedPrefills == 0`, `decide("logo") == Overridden("logo")`, `decide(null) == Refused`, and an unreviewed ENTERED sticker not counted.
  - `[low]` `[patch]` (verification-gap, other) `CornerSettle.CAP_MS` has no caller left and its new KDoc claims Case capture shares it — KDoc now says it is kept for Case recognition with no current caller; inventory row corrected (the AC keeps the constant, so it is not deleted).
  - `[low]` `[reject]` (edge) `StickerTruthCheck(emptyMap())` passes — reachable only by direct construction; every caller goes through `of`/`ofColors`, which always fill every Color; a guard adds complexity to code copied verbatim from PR #396.
  - `[false]` `[reject]` (edge) `ofColors` accepts any length — documented "over any list of colours" by design; the 54-sticker size check lives in `of`, which the labeller uses.
  - `[false]` `[reject]` (edge) DebugManifestTest's "Recorder" match would fail Story 10.34 — SCP 2026-11-26 §4.1 drops the `ContinuousRecorder*` UI and activity wiring; 10.34 adds no activity, and a recorder activity is what the guard must catch.
  - `[low]` `[patch]` (edge) `CornerSettle.CAP_MS` dead after `SETTLE_CAP_MS` went — same root cause as the CAP_MS KDoc row; KDoc fixed, constant kept per AC.
  - `[low]` `[reject]` (edge) `pre-correction-labels` resource and its RecorderRulesTest case lost — the story AC removes the recorder's tests and test resources; the rule keeps synthetic coverage in StickerTruthCheckTest.
  - `[false]` `[reject]` (edge) dropping the recorder glob counts 10.34's classes — SCP §4.1 has 10.34 add `dev/cubentia/app/camera/session/` to `debugOnlyClassGlobs` with `FixtureWriterPolicy`; this AC requires the recorder glob gone.
  - `[low]` `[patch]` (edge) CAP_MS KDoc names callers it does not have — grouped with the CAP_MS row; fixed there.
  - `[false]` `[reject]` (edge) CornerSettle kept despite zero callers violates the no-caller rule — the AC names `CornerSettle.CAP_MS` among the items to keep.
  - `[false]` `[reject]` (edge) SideLight `check`/`checkLab`/`checkSamples` are test-only in main — the AC keeps `SideLight` as a historical reader; those members run the Fixtures/corner replays (`QuickScanSideFaceReplayTest`, `QuickScanGateTuningTest`).
  - `[low]` `[patch]` (intent) CAP_MS kept with a false KDoc — grouped with the CAP_MS row.
  - `[low]` `[reject]` (intent) LiveFixture encoders now have test-only callers — they are the fixture format's own serializer (one-line `encodeToString` calls), round-trip tested, and the reader tests write synthetic fixtures through them; moving them to test sources adds complexity for no user or developer harm.
  - `[false]` `[reject]` (intent) the debug-camera marker lives only in the build-logic fixture — the AC asks exactly that of `CameraCompositionFunctionalTest`; the real app's merged debug manifest is covered by `DebugManifestTest`.
  - `[false]` `[reject]` (intent) action-item text stays stale and context-drift is done although the 10.29 bullet remains — the orchestrator allows only `status:` edits; 10.29 is a done Epic 10 story, so its bullet is in scope; dispositions are listed in Design Notes for the PR.
  - `[low]` `[defer]` (intent) two tracked reader outputs (`docs/spikes/oq-8-recognition-matrix.md`, `docs/evidence/10-23/kotlin-reads.json`) are stale against their own commands — pre-existing: a baseline export reproduced the same output; deferred.
  - `[false]` `[reject]` (intent) CI passes are not in the evidence docs — the AC requires only the class-byte record in `docs/evidence/10-35/`; the passes are recorded in this spec's Auto Run Result.
  - `[false]` `[reject]` (intent) the context was edited, not fully regenerated — the AC is on the result (no live-tool recorder mention), which holds.
  - `[low]` `[patch]` (blind) CAP_MS unused with false KDoc — grouped with the CAP_MS row.
  - `[low]` `[patch]` (blind) `meteringFlags` KDoc still describes Quick Scan Hold 2, and `includeAwb = false` has no production caller once the recorder is gone (every `GuidedScanActivity.meterOnGrid()` call uses `true`) — removed the `includeAwb` parameter and the `meter(includeAwb)` overload (metering stays AF+AE+AWB), with only the tests of the removed branch.
  - `[low]` `[reject]` (blind) the `QuickScanPlan.kt` move loses `git log --follow` — ktlint's file-name rule forced the rename once `CenterCheck` left; history stays reachable through the baseline revision; the KDoc ("the order the adapter returns them") still holds for Case capture's corner reader.
  - `[low]` `[patch]` (blind) DebugManifestTest would pass vacuously if the camera manifests were not merged — added a positive control: `GuidedScanActivity` is present.
  - `[low]` `[reject]` (blind) the package literal is assembled to dodge the removal grep — the grep is this spec's AC; changing it means editing the spec.
  - `[low]` `[patch]` (blind) StickerTruthCheck test names say "(two-hold recorder)" and "in colour order in colour order", an odd wrap, and the `of()` KDoc names corner groups — renamed, unwrapped, KDoc trimmed; assertions unchanged.
  - `[low]` `[patch]` (blind) no test that unknowns with a reason decide `Overridden` — grouped with the first StickerTruthCheckTest row.
  - `[low]` `[reject]` (blind) `StickerTruth(color, source = null, reviewed = false)` skips the review gate — no producer creates that state (a set colour always carries a source); a `require` adds a guard for an undemonstrated state.
  - `[false]` `[reject]` (blind) StickerTruthCheck has no production caller and the context speaks of it as enforced — the AC moves it to main precisely so Story 10.34 calls it; the context states requirements.
  - `[low]` `[patch]` (blind) `docs/spikes/oq-4-guided-scan.md` still gives live recorder launch instructions — added a one-line retirement note pointing to `legacy-replay.md`; historical text kept.
  - `[low]` `[patch]` (blind) `legacy-replay.md` gives no way to run the old recorder — added the install and launch commands for the snapshot.
  - `[low]` `[defer]` (blind) stale committed outputs found but not tracked — same root cause as the deferred stale-outputs row.
  - `[low]` `[patch]` (blind) "byte-identical (`git diff --stat` empty)" sits next to a line-ending rewrite — reworded to name which outputs matched after line-ending normalisation and which two differ on the baseline too.
  - `[false]` `[reject]` (blind) `epic-10-deferred-10.20-epic-10-context-refresh` left open — it is not in SCP §4.5, and only the listed items may change.
  - `[false]` `[reject]` (blind) PO-owned `10.28-recorder-ui-device-check` closed without PO note — the PO-approved SCP §4.5 closes it as obsolete.
  - `[false]` `[reject]` (blind) the launcher item needs a PR flag — Design Notes already records its disposition (stays open, other debug launchers remain) for the PR body.
  - `[low]` `[patch]` (blind) inventory labels `androidTestDebug` callers of `QuickScanPlan` as "Android" — relabelled "test".
  - `[false]` `[reject]` (blind) no handoff for 10.34's session glob — SCP §4.1 assigns that glob change to Story 10.34.
  - `[false]` `[reject]` (blind) the release build is "unchanged" only by inference — the camera report's "Release estimate" line is read from `release-size.txt` (`releaseReport` input of `checkCameraSize`), before and after.

## Design Notes

Action-item dispositions (SCP 2026-11-26 §4.5; only `status:` may change):

| Item | New status | Disposition (for the PR list) |
| --- | --- | --- |
| `issue-318-deferred-missing-jpeg-says-no-cube`, `-missed-hold-untested`, `-missed-hold-reset-race`, `-status-retake-clause-cut` | done | closed as obsolete: recorder UI behaviour (SCP 2026-11-26) |
| `issue-329-deferred-glare-gets-ev-plus-hint`, `-no-jpeg-says-detector-missed` | done | closed as obsolete: recorder UI |
| `issue-329-deferred-evidence-floor-constant-drift` | open | kept: `CornerReader`/`CornerLocator.kt` Case code, owner Dev |
| `issue-330-deferred-evindex-wiring-untested`, `-evindex-without-ev-step`, `-settle-null-holds-refit` | done | closed as obsolete: recorder hold JSON / no further corner recording |
| `epic-10-deferred-10.28-recorder-screen-wiring-tests`, `-count-check-write-retry`, `-recorder-ui-device-check` | done | closed as obsolete: recorder retired; the rule lives on in `StickerTruthCheck` |
| `epic-10-deferred-10.28-epic-10-context-drift` | done | re-pointed to 10.35, which recompiles `epic-10-context.md` (resolved here) |
| `epic-10-deferred-10.20-coverage-check-strength`, `-missing-groups-json` | open | kept: `Fixtures/corner` index and `evaluate_model.py` stay, owner Dev |
| `epic-10-deferred-10.32-retire-kept-2-corner-code` | open | closed in part (`CenterCheck`, `SideLight.check(FrameStats)` removed); the rest stays open: `QuickScanClassification` is an evaluation reader (owner `:core:domain` evaluation), moving it to the evaluation source set is optional clean-up. Its text cannot be re-worded here (status-only rule): flag in the PR |
| `scp-2026-11-16-delete-debug-only-launchers` | open | re-pointed: 10.35 deletes the recorder launcher; other debug-only launchers remain (`app/src/debug` benchmark activities, `spike3d`), so it stays open |

Legacy-replay commands (env per AGENTS.md):
- `./gradlew :core:domain:evaluateLiveBaseline` (Fixtures/live), `:core:domain:evaluateScanAccuracy` (Fixtures/live), `:core:domain:evaluateRecognitionApproaches` (Fixtures/live; OpenCV is in the Gradle cache), `:core:domain:evaluateMatrix` (Fixtures/matrix), `:core:domain:evaluateQuickScan --args="$PWD/Fixtures/matrix/lenovo-tb128xu_stka_warm_quickscan.log"` (its `argumentProviders` appends the report path `core/domain/build/reports/quick-scan-measurement.md`; the task runs in `core/domain`, so the log path is absolute), `:core:domain:exportKotlinStickerReads` (Fixtures/corner + `tools/sticker-model/holdout.json`), `:core:domain:test --tests '*QuickScanSideFaceReplayTest*' --tests '*QuickScanGateTuningTest*' --tests '*CornerGroupIndexTest*' --tests '*LiveFixtureTest*'` (Fixtures/corner decoding), `uv run --with pillow python tools/evaluate_fixture_photos.py` or the invocation its header documents (Fixtures/labeled photo groups).

## Verification

**Commands:** (prefix: `cd {project-root} && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk &&`)
- `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` -- expected: BUILD SUCCESSFUL
- `./gradlew -Pcubentia.cornerR8=true :app:assembleCornerR8 :app:assembleCornerR8AndroidTest :app:checkCornerR8ShadowReader` -- expected: BUILD SUCCESSFUL
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:checkCameraSize :app:checkReleaseSize` -- expected: pass; numbers in `class-bytes.md`
- `./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.camera.DebugManifestTest` and `:core:domain:test --tests '*StickerTruthCheckTest*'` -- expected: pass
- the legacy-replay commands in Design Notes -- expected: each succeeds; tracked outputs unchanged
- the `git grep` of the second AC -- expected: no live-code match

## Auto Run Result

Status: done

**Summary.** Retired the separate debug fixture recorder (all modes) and the Quick-Scan-only two-corner helpers it alone used, kept Case recognition and the historical fixture readers with named owners, moved the Story 10.28 nine-of-each-colour rule to `:core:domain` main as `StickerTruthCheck` (copied from PR #396 head `4be002d98a075f901478e736612444d115a8c8eb`), made build-logic name no recorder class, and recorded inventory, legacy-replay and class-byte evidence in `docs/evidence/10-35/`. No Learner-visible change; no flag level changed (AD-18); release estimate unchanged.

**Files changed**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/*` (5 files), `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/*` (3), `app/src/testDebug/resources/recorder/` -- deleted.
- `app/src/camera/debug/AndroidManifest.xml` -- recorder activity removed; empty `<application />` kept for the build-logic wiring.
- `app/src/camera/kotlin/.../ScanFrameSource.kt` -- lock/settle helpers, `LOCK_UNSUPPORTED`, `cubeMeteringRegion`, `chooseMetering` and (review) the `includeAwb` parameter / `meter(includeAwb)` overload removed; metering is always AF+AE+AWB as every production caller already asked.
- `GuidedScanActivity.kt`, `GuidedScanScreen.kt`, `CornerLocator.kt`, `FrameReader.kt` -- `meterOnGrid()` call, `TorchButton` tag inlined, recorder comments reworded.
- `core/domain/.../CornerCapturePlan.kt` -> `QuickScanPlan.kt` (`CenterCheck` removed; ktlint file-name rule), `SideLight.kt` (FrameStats `check` overload removed), `CornerHold.kt` (`CornerSettle` KDoc), `StickerTruthCheck.kt` + test (new).
- Tests: `ScanFrameSourcePolicyTest`, `CameraXFrameSourceTest`, `ContinuousQuickScanRouteTest`, `SideLightTest` (only cases of removed code), `DebugManifestTest` (new, failed on baseline, positive control on `GuidedScanActivity`).
- Build-logic: `CameraSizePolicy`, `CheckCameraSizeTask` report line, `ShadowReaderPolicyTest`, `CameraSizePolicyTest`, `AndroidFixture` (`DEBUG_CAMERA_MARKER`), `CameraCompositionFunctionalTest`.
- `tools/cube-session.sh` -- `recorder` and `pull` commands and usage lines removed.
- Docs: `docs/evidence/10-35/{inventory,legacy-replay,class-bytes}.md` (new); retirement notes in `Fixtures/README.md`, `tools/corner-model/README.md`, `docs/spikes/oq-4-guided-scan.md`.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- 13 `status:` lines open -> done, per Design Notes; `epic-10-context.md` recompiled (Stories 10.34-10.36, no live-tool recorder mention).

**Action items for the PR body** (SCP 2026-11-26 §4.5): see the Design Notes table. Closed as obsolete (done): the four `issue-318-*`, `issue-329-deferred-glare-gets-ev-plus-hint`, `-no-jpeg-says-detector-missed`, the three `issue-330-*`, the three recorder `epic-10-deferred-10.28-*`; `epic-10-deferred-10.28-epic-10-context-drift` resolved by this recompile (done). Kept open: `issue-329-deferred-evidence-floor-constant-drift`, `epic-10-deferred-10.20-coverage-check-strength`, `-missing-groups-json`. Flag in the PR: `epic-10-deferred-10.32-retire-kept-2-corner-code` is closed in part (`CenterCheck`, the SideLight FrameStats overload) but stays open with its old wording (status-only rule); `scp-2026-11-16-delete-debug-only-launchers` stays open (benchmark activities and spike3d remain) and its text still lists the fixture recorder.

**Review findings.** 37 findings (high 0, medium 0, low 22, false 15). Patched 9 entries (all low): StickerTruthCheck unknown/provenance test, StickerTruthCheck test names and KDoc, `CornerSettle.CAP_MS` KDoc, dead `includeAwb` path, DebugManifestTest positive control, oq-4 spike retirement note, legacy-replay run commands, legacy-replay identical-output wording, inventory test labels. Deferred 1: stale committed outputs of `evaluateMatrix` and `exportKotlinStickerReads` (pre-existing). Rejected (with reasons in the triage log): empty-counts constructor, `ofColors` any length, DebugManifestTest name match, pre-correction resource loss, recorder glob handoff, CAP_MS deletion, SideLight test-only members, LiveFixture encoders, fixture-only marker, action-item wording, CI passes not in evidence, partial recompile, QuickScanPlan history, obfuscated literal, null-source truth state, rule without caller, three sprint-status claims, release before/after inference.

**Follow-up review recommendation:** false -- this pass patched 0 high and 0 medium entries (9 low).

**Verification** (JDK 25, `ANDROID_HOME` set), on the final tree:
- `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` -- exit 0.
- `./gradlew -Pcubentia.cornerR8=true :app:assembleCornerR8 :app:assembleCornerR8AndroidTest :app:checkCornerR8ShadowReader` -- exit 0.
- `./gradlew -p build-logic test` -- exit 0 (CameraCompositionFunctionalTest 9/9, ShadowReaderPolicyTest 2/2).
- `./gradlew :app:checkCameraSize :app:checkReleaseSize` -- exit 0; camera classes 1,187,172 -> 1,161,682, projected 28,662,766 -> 28,586,296, release 13,025,179 unchanged.
- `DebugManifestTest`, `StickerTruthCheckTest` (8), `CaseCaptureControllerTest` -- pass; implementer also ran `CornerLocatorTest`, `GuidedScanFlowTest`, `CornerModelTransformTest`, `CornerFixtureHarnessTest`, `ScanFrameSourcePolicyTest`, `CameraXFrameSourceTest`, `ContinuousQuickScanRouteTest` -- pass.
- Legacy replay (`docs/evidence/10-35/legacy-replay.md`): all commands exit 0; the removal grep finds no live-code match.

**Residual risks.** Two committed reader outputs are stale on main (deferred, pre-existing). Story 10.34 must add its `camera/session/**` glob to `debugOnlyClassGlobs` (SCP §4.1). `CornerSettle.CAP_MS` is kept by the AC with no caller.
