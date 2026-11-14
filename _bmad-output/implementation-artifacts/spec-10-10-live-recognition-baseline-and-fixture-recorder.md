---
title: 'Live recognition baseline and fixture recorder'
type: 'feature'
created: '2026-11-14'
status: 'done'
baseline_revision: '33a35567e022fe6ef2bacf0219a4c511c0ecf6bf'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      AC2 measured numbers: run the recorder in device session A (Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE, both cubes, normal indoor light, six faces each), commit Fixtures/live/, run evaluateLiveBaseline and commit the regenerated § Live baseline.
    evidence: |-
      PO clarification 2026-11-14: this run covers the code half only; the device session happens later with the PO. Not blocking.
    location: docs/spikes/oq-4-guided-scan.md § Live baseline
    severity: low
  - summary: >-
      AC3: the PO records go / go-as-BETA-only / re-plan in the PRD memlog and confirms or revises OQ-7's Kotlin-first Quick Scan recommendation with its size cost.
    evidence: |-
      PO clarification 2026-11-14: AC3 is the PO's decision after device session A; never fabricated by this build. Not blocking.
    location: _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/.memlog.md
    severity: low
---

<intent-contract>

## Intent

**Problem:** Epic 10 has only an offline JPEG estimate (62.5%) and one synthetic `FaceSample`; the Story 1.9 CameraX prototype lives in an opt-in `spikeScan` source set that adds CameraX outside the Story 10.9 composition and cannot save what it measures, so no live baseline can be recorded or scored.

**Approach:** Move `GuidedScanActivity` into a debug-only part of the camera source set as a fixture recorder that saves self-contained `FaceSample` + truth + device/cube/light JSON records to `Fixtures/live/<device>_<cube>_<light>/`, compose it only into debuggable camera-enabled build types, delete the `spikeScan` switch, and add a non-shipping `:core:domain` evaluation tool that scores recorded fixtures with `NearestCenterClassifier` and rewrites `docs/spikes/oq-4-guided-scan.md` § Live baseline. Live numbers and the PO decision (AC2 numbers, AC3) are deferred to device session A.

## Boundaries & Constraints

**Always:**
- Recorder source: `app/src/camera/debug/kotlin` + `app/src/camera/debug/AndroidManifest.xml`; `build-logic` adds them only to a build type that is camera-enabled **and** debuggable (DSL `isDebuggable` in `finalizeDsl`, `variant.debuggable` in `onVariants`). A release/benchmark build type with a raised camera flag still gets no recorder.
- Truth is entered by the operator before capture (all nine cells), never derived from the prediction; capture is disabled until nine truth labels, six calibration centers, a cube and a light description exist. Only CIELAB numbers and metadata are written; no image or frame bytes are ever persisted.
- Records are self-contained: schema version, device model + manufacturer, OS build (release + `Build.DISPLAY`), SDK int, cube, light, capture time (ISO-8601 string), calibration centers, `LabeledFaceSample`. One file per face, `<FACE>.json`, recapture overwrites.
- Directory name `<device>_<cube>_<light>`: each part slugged to lowercase `[a-z0-9-]`, other runs collapsed to `-`, trimmed; a blank part is rejected.
- Evaluation uses the prototype `NearestCenterClassifier` unchanged. The prototype has no uncertainty output, so every misread sticker of a classified face counts as confidently wrong; this rule is stated in the generated section.
- Gate: per-sticker accuracy over all classified stickers ≥ 0.99 → MET, < 0.99 → MISSED; INCOMPLETE (no verdict) when there are no groups or any group lacks a classified record for one of the six faces.
- The evaluator never writes the doc when there is no fixture; § Live baseline stays "Pending device session A".

**Never:**
- No recorder or CameraX import in `app/src/main`; no change to flag levels, `docs/privacy-facts.json`, planning artifacts, `_bmad/`, `.claude/skills/`.
- No fabricated live numbers, gate verdict, go/no-go, memlog entry or OQ-7 revision.
- No `StickerClassifier`/`ScanAssembler`/confidence model (Story 10.1); no viewfinder UX (10.2).
- The evaluation code does not ship: it lives in a separate source set, not `:core:domain` main.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| All correct | 1 group, 6 faces, all stickers match truth | 54/54, 6/6 faces, 0 confidently wrong, gate MET | none |
| Misreads | 1 group, 6 faces, 2 wrong stickers on one face | 52/54, 5/6 faces, 2 confidently wrong, gate MISSED | none |
| Unavailable capture | record with quality GLARE or calibration missing a color | excluded from accuracy, counted as unavailable; group lacks that face → INCOMPLETE | none |
| Missing face | group with 5 face files | INCOMPLETE, missing face named | none |
| No fixtures | `Fixtures/live` absent or no `*.json` | tool exits non-zero, doc untouched | message names the directory |
| Malformed JSON | unparseable record | tool exits non-zero, doc untouched | message names the file |
| Markers missing | doc without `<!-- live-baseline:start -->`/`end` | tool exits non-zero | message names the markers |
| Slug | device `SM-X306B`, cube `GAN Stickerless!`, light `Normal indoor` | `sm-x306b_gan-stickerless_normal-indoor` | blank part rejected |

</intent-contract>

## Code Map

- `app/src/spikeScan/kotlin/dev/cubentia/app/spikeScan/GuidedScanActivity.kt` -- Story 1.9 prototype to move: YUV→CIELAB `measure`, `rgbToLab`, calibration/truth UI, `FixedGrid`; reuse, add metadata inputs + export, delete the old file.
- `app/src/spikeScan/AndroidManifest.xml` -- launcher activity + CAMERA; delete (camera manifest already declares CAMERA).
- `app/build.gradle.kts:96-108` -- `cubentia.spikeScan` opt-in block adding debug CameraX; delete.
- `.github/workflows/ci.yml:99` -- `-Pcubentia.spikeScan=true :app:compileDebugKotlin`; remove (debug now always compiles the recorder).
- `build-logic/.../ReleaseConventionPlugin.kt:78-100` -- camera composition in `finalizeDsl`/`onVariants`; add the debuggable-only recorder dirs here.
- `build-logic/.../FeatureFlagLevelsExtension.kt` -- `isCameraEnabled(buildType)` used by `onVariants`.
- `build-logic/src/test/.../functional/CameraCompositionFunctionalTest.kt`, `AndroidFixture.kt` -- extend with recorder manifest fixture and assertions.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` -- `FaceSample`, `LabeledFaceSample`, `CalibrationCenters`, `NearestCenterClassifier` (reuse; do not change behavior).
- `core/domain/build.gradle.kts` -- add `evaluation` source set + `evaluateLiveBaseline` JavaExec, mirroring `core/solver/build.gradle.kts` (`benchmark` source set, `testImplementation(benchmark.output)`).
- `core/domain/src/test/.../recognition/ScanFixtureEvaluationTest.kt` -- existing synthetic fixture test; keep passing.
- `docs/spikes/oq-4-guided-scan.md` -- add § Live baseline (markers), how to record/run; update "Prototype and size cost" (no more `spikeScan`).
- `Fixtures/README.md` -- "Guided scan spike fixtures" section; add `Fixtures/live/` layout and pull command.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `@Serializable LiveFixtureRecord` (+ `DeviceInfo`), `LiveFixturePaths.directoryName(device, cube, light)`/`fileName(face)` (sealed result or `require` with message for blanks), `LiveFixtureJson.encode/decode` -- shared contract for recorder and evaluator.
- `core/domain/src/test/.../recognition/LiveFixtureTest.kt` -- slug rules, round-trip JSON, blank rejection.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/` -- `LiveBaselineEvaluator` (groups → per-group/per-device/total sticker + face accuracy, confidently wrong, unavailable, missing faces, gate), `LiveBaselineMarkdown` (render + replace between markers), `LiveFixtureLoader` (read `<root>/*/*.json`), `LiveBaselineMain` (args: fixtures root, doc path) -- AC2 tooling.
- `core/domain/build.gradle.kts` -- `evaluation` source set, `testImplementation(evaluation.output)`, `assemble` compiles it, `evaluateLiveBaseline` JavaExec with repo-relative `Fixtures/live` and `docs/spikes/oq-4-guided-scan.md`.
- `core/domain/src/test/.../recognition/evaluation/LiveBaselineEvaluatorTest.kt` -- synthetic fixtures only; covers every I/O row (temp dirs for loader/main).
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- moved prototype + cube/light text fields, capture gating, writes `getExternalFilesDir(null)/Fixtures/live/<dir>/<FACE>.json`, shows saved path then prediction/accuracy.
- `app/src/camera/debug/AndroidManifest.xml` -- launcher activity "Cubentia fixture recorder".
- `build-logic/.../ReleaseConventionPlugin.kt` -- compose recorder dirs for camera-enabled debuggable build types.
- `build-logic/src/test/.../functional/` -- fixture writes `src/camera/debug/AndroidManifest.xml`; assert debug merged manifest has the recorder, release/benchmark don't, and raised release (`CAMERA_SCAN=BETA`) still doesn't.
- Delete `app/src/spikeScan/**`, the `app/build.gradle.kts` spikeScan block, the CI spikeScan line.
- `docs/spikes/oq-4-guided-scan.md`, `Fixtures/README.md` -- docs as in Code Map; § Live baseline reads pending.

**Acceptance Criteria:**
- Given today's levels, when `./gradlew spotlessCheck build` runs, then the debug merged manifest declares `FixtureRecorderActivity`, release and benchmark merged manifests do not, and `releaseRuntimeClasspath` still has no camera/ML coordinate.
- Given a raised release flag in a TestKit fixture, when the release manifest merges, then it contains CAMERA but no recorder activity.
- Given synthetic fixtures, when `evaluateLiveBaseline`'s main runs in tests, then the doc section between the markers is replaced with per-group, per-device and total numbers and the gate verdict, and the rest of the doc is byte-identical.
- Given the repository with no `Fixtures/live`, when `./gradlew :core:domain:evaluateLiveBaseline` runs, then it fails naming the missing directory and `git diff docs/` is empty.

## Spec Change Log

## Review Triage Log

### 2026-11-14 — Review pass
- verdicts: 21 findings — high 0, medium 0, low 15, false 6, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Gate can read MET for a single complete group, short of both gating devices × both cubes — the section now prints a coverage line (groups, devices, cubes) and says the Story 1.9 gate needs both gating devices × both cubes; the verdict rule itself stays as the intent-contract defines it, and per-group/device rows show coverage.
  - `[low]` `[patch]` (blind) A directory can mix records from different devices/cubes/lights into one "complete" group — loader now rejects a record whose `directoryName(model, cube, light)` differs from its directory, naming the file.
  - `[low]` `[patch]` (blind) Device/total "Missing classified faces" is a union that hides per-group gaps — aggregate rows now read "see groups"; only group rows list missing faces.
  - `[low]` `[patch]` (blind) Recorder keeps calibration centers after the cube or light changes — editing cube or light now clears the centers.
  - `[low]` `[patch]` (blind) Recorder keeps the nine truth labels after the cube or light changes — editing cube or light now clears the truth labels (same reset as above).
  - `[low]` `[reject]` (blind) Non-atomic `writeText` can truncate an earlier face file — needs process death mid-write of a ~3 KB file in a debug tool; recapture fixes it and the loader fails loudly naming the file; a temp-and-rename path is added machinery.
  - `[low]` `[reject]` (blind) No capture-session id, so a later partial session can mix with an earlier one — recapture-overwrites is the specified behavior, each record carries `capturedAt`, and the session is operator-run; a session id adds schema surface.
  - `[low]` `[reject]` (blind) TestKit fixture compiles no recorder Kotlin, so `src/camera/debug/kotlin` wiring is unproven there — the real `:app` debug build compiles it (FixtureExportTest runs against it) and release/benchmark merged manifests have 0 recorder entries; a fixture compile test adds complexity for an unlikely gap (same call as Story 10.9).
  - `[false]` `[reject]` (blind) Live baseline, PO decision and OQ-7 confirmation missing — deliberately deferred to device session A per the PO clarification and recorded in `deferred`.
  - `[low]` `[patch]` (edge) Empty capture directory vanished, allowing MET with six faces missing — loader keeps every directory as a group (empty → six missing faces → INCOMPLETE) and still fails when no directory holds a record; new test `empty capture directory counts as six missing faces`.
  - `[low]` `[patch]` (edge) Mixed device/cube/light metadata in one directory — same root and fix as the blind finding above; test `record in another device cube light directory is rejected`.
  - `[low]` `[reject]` (edge) Interrupted recapture write destroys the earlier file — same root as the non-atomic write finding; rejected for the same reason.
  - `[low]` `[patch]` (verification-gap) Recorder capture-to-file path untested — extracted `fixtureRecord`/`writeFixture` into `app/src/camera/debug/.../FixtureExport.kt`; `app/src/testDebug/.../FixtureExportTest` proves operator truth (not the prediction), sample, calibration, metadata and path are saved and that recapture replaces the face.
  - `[low]` `[patch]` (verification-gap) Rendered percentages not asserted — new test `rendered rows carry sticker and face percentages` asserts 96.2963% / 83.3333% rows for the two-misread case.
  - `[low]` `[patch]` (intent) Verdict not tied to the story's full device × cube precondition — grouped with the first blind finding; coverage line added.
  - `[low]` `[patch]` (intent) AC1 tests stop at build and JVM, not the activity — grouped with the verification-gap recorder finding (FixtureExportTest); on-device capture is device session A.
  - `[false]` `[reject]` (intent) Files land on device storage and need `adb pull` instead of `Fixtures/live/` directly — a phone cannot write the repository; the on-device tree mirrors `Fixtures/live/<device>_<cube>_<light>/` and the pull is documented.
  - `[false]` `[reject]` (intent) Truth entry stricter than asked — consistent with "independently entered"; not a defect.
  - `[false]` `[reject]` (intent) Confidently-wrong equals every misread sticker — the prototype classifier has no uncertainty output; the rule is stated in the generated section and Story 10.1 owns categorical confidence.
  - `[false]` `[reject]` (intent) `epic-10-context.md` rewritten beyond the ACs — step-01 regenerated the cache because the architecture spine changed after it; workflow-mandated, not story code.
  - `[false]` `[reject]` (intent) Spec not in the reviewed diff — excluded by design; the spec is the claims file, with AC2 numbers and AC3 in `deferred`.

## Design Notes

Self-contained records (calibration inside each face file) let the evaluator score any directory without a separate calibration file and keep recaptures independent. Recorder composition reuses the 10.9 hook: same loop, extra `isDebuggable` condition, so a future release raise (Story 10.11) gets the scanner code but never the recorder.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:domain:test` -- expected: BUILD SUCCESSFUL (LiveFixtureTest, LiveBaselineEvaluatorTest, ScanFixtureEvaluationTest)
- `grep -c FixtureRecorderActivity app/build/intermediates/merged_manifest/{debug,release,benchmark}/*/AndroidManifest.xml` -- expected: debug ≥ 1, release 0, benchmark 0
- `./gradlew :core:domain:evaluateLiveBaseline` -- expected: fails naming `Fixtures/live`; `git diff --exit-code docs/` exit 0
- `git diff --exit-code docs/privacy-facts.json` -- expected: exit 0

## Auto Run Result

**Summary:** Code half of Story 10.10. The Story 1.9 `GuidedScanActivity` is now `FixtureRecorderActivity` in `app/src/camera/debug/`, composed by `build-logic` only into build types that are camera-enabled and debuggable (today: debug), so release and benchmark never contain it, even with a raised camera flag. It saves one self-contained JSON record per face (`FaceSample`, independently entered nine-sticker truth, calibration, device model/manufacturer, OS release/build, SDK, cube, light, time) under `Fixtures/live/<device>_<cube>_<light>/<FACE>.json` in app storage, pulled with `adb pull`. The `cubentia.spikeScan` switch and its CI step are gone, so CameraX enters debug only via the 10.9 composition. A non-shipping `evaluation` source set in `:core:domain` (`./gradlew :core:domain:evaluateLiveBaseline`) scores recorded fixtures with the unchanged `NearestCenterClassifier` and rewrites `docs/spikes/oq-4-guided-scan.md` § Live baseline between markers (per-group/device/total sticker and face accuracy, confidently wrong, unavailable, missing faces, coverage, ≥ 99% gate). § Live baseline reads "Pending device session A"; AC2 numbers and AC3 are deferred (frontmatter `deferred`).

**Files changed:**
- `app/src/camera/debug/AndroidManifest.xml`, `.../recorder/FixtureRecorderActivity.kt`, `.../recorder/FixtureExport.kt` — recorder (moved from `app/src/spikeScan/`, deleted) and its testable export.
- `app/src/testDebug/.../recorder/FixtureExportTest.kt` — saved record content, path, recapture.
- `app/build.gradle.kts`, `.github/workflows/ci.yml` — spikeScan switch and CI step removed.
- `build-logic/.../ReleaseConventionPlugin.kt` — recorder dirs for camera-enabled debuggable build types.
- `build-logic/.../JvmCoreConventionPlugin.kt` — Kover excludes the `evaluation` source set.
- `build-logic/src/test/.../AndroidFixture.kt`, `CameraCompositionFunctionalTest.kt` — recorder in debug only, not in raised release, not in debug with flags OFF.
- `core/domain/src/main/.../recognition/LiveFixture.kt` — `LiveFixtureRecord`, `DeviceInfo`, `LiveFixturePaths`, `LiveFixtureJson`.
- `core/domain/build.gradle.kts`, `core/domain/src/evaluation/.../evaluation/*` — evaluator, loader, markdown, main, `evaluateLiveBaseline` task.
- `core/domain/src/test/.../LiveFixtureTest.kt`, `evaluation/LiveBaselineEvaluatorTest.kt` — synthetic fixtures only.
- `docs/spikes/oq-4-guided-scan.md`, `Fixtures/README.md` — § Live baseline (pending), how to record, pull and run.
- `_bmad-output/implementation-artifacts/epic-10-context.md` — regenerated (stale cache).

**Review findings:** 21 findings; 11 rows patched (all low: gate coverage line, directory/metadata consistency, aggregate missing-face column, recorder resets calibration and truth on cube/light change, empty directories count as missing faces, recorder export test, percentage assertions); 0 newly deferred (AC2 numbers and AC3 were deferred at planning by PO clarification); rejected: 4 low (non-atomic write ×2 — debug tool, loud failure on truncation; no session id — overwrite is specified, `capturedAt` recorded; TestKit compiles no recorder Kotlin — real debug build compiles and tests it) and 6 false (deferred items intended; adb pull necessary; stricter truth entry; confidently-wrong rule documented; context regeneration workflow-mandated; spec excluded by design).

**Follow-up review recommended:** false. Patched: high 0, medium 0, low 11.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches); `./gradlew -p build-logic test` BUILD SUCCESSFUL (CameraCompositionFunctionalTest 5/5); `LiveBaselineEvaluatorTest` 10/10, `LiveFixtureTest` 2/2, `FixtureExportTest` 2/2; merged manifests `FixtureRecorder` count debug 1, release 0, benchmark 0; `./gradlew :core:domain:evaluateLiveBaseline` fails with "No live fixtures in .../Fixtures/live" and leaves docs unchanged; `git diff --exit-code docs/privacy-facts.json` exit 0.

**Residual risks:** the recorder's CameraX capture and on-device export path are exercised only in device session A; the gate verdict line counts whatever groups exist, so the PO must check the coverage line and rows before relying on MET; recorder writes are not atomic.
