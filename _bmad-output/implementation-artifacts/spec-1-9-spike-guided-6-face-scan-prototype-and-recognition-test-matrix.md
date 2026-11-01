---
title: 'Story 1.9: Guided 6-face scan prototype and recognition test matrix'
type: 'feature'
ticket: ''
created: '2026-10-31'
status: 'built'
baseline_revision: '2f5236a1946da1d7b810cd6ef267a02d1c9cfb1e'
route: 'full'
route_source: 'auto'
risk: 'medium'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      The BETA recommendation requires a PRD memlog append when the recognition target is missed; this append is outside this run's authorized scope.
    evidence: |-
      The spike documentation recommends BETA because no physical accuracy evidence is available. The orchestrator explicitly prohibited editing planning artifacts, including the PRD memlog, so the append must be classified by the independent gate.
    location: >-
      docs/spikes/oq-4-guided-scan.md:3
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Camera recognition accuracy and method are unproven, so later scan features need measured evidence and an honest release level.

**Approach:** Build a disposable CameraX fixed-grid prototype and nearest calibrated-center CIELAB classifier; define reproducible, ground-truthed fixture and lighting/device matrix, report measured results, then record the GA/BETA decision and Quick Scan recommendation.

## Boundaries & Constraints

**Always:** Keep camera/pixel handling in `:app`; keep classification inputs neutral and domain code Android-free; process frames in memory; preserve existing photos as unlabeled source data; report missing evidence without claiming accuracy.

**Never:** Change planning artifacts, PRD, architecture spine, generated BMAD files, or feature release policy beyond this spike; infer ground truth from fixture folder names; persist or export live frames in release builds.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Labeled captured face | Camera frame plus orientation and six-center calibration | Nine ordered CIELAB samples, center/up-neighbor, quality metadata; classifier emits per-sticker color and accuracy against explicit truth | Reject malformed/missing calibration or truth; count as unavailable, never guessed |
| Incomplete or poor-quality capture | Blurred, glare/low-light, misaligned, or incomplete grid | Specific quality outcome and recapture; no face accepted | Keep capture transient and expose reason |
| Matrix row without labeled ground truth | Existing JPEG source fixture | Listed as source-only, excluded from accuracy denominator | Report gap and retain live-test gate |

</intent-contract>

## Code Map

- `app/build.gradle.kts`, `gradle/libs.versions.toml` -- Android app dependencies/build variants; no CameraX currently.
- `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/rubikxplorer/app/MainActivity.kt` -- app permissions and Compose activity entry.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Color.kt` -- canonical cube colors; pure JVM module boundary.
- `Fixtures/README.md`, `Fixtures/state-assignment.csv`, `Fixtures/*` -- 64 source images across 2 cubes, 2 devices and S1/S2; no verified facelet ground truth and no four-condition lighting coverage.
- `_bmad-output/planning-artifacts/epics.md` Story 1.9 -- sole implementation acceptance scope.

## Tasks & Acceptance

**Execution:**
- [x] `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml` -- add a debug-only camera prototype path and CameraX wiring, keeping camera permission use scoped to prototype.
- [x] `app/src/spikeScan/` -- fixed 3×3 viewfinder, transient frame sampling, center calibration, nearest-center CIELAB classification and accuracy output; no app-session coupling.
- [x] `Fixtures/` and `docs/spikes/oq-4-guided-scan.md` -- define auditable FaceSample JSON plus truth labels, matrix (cubes × four lighting conditions × both reference devices), accuracy method, Quick Scan recommendation and size cost; identify source photos as unlabeled.
- [x] `Fixtures/` -- add deterministic labeled fixture examples and a runnable evaluation entrypoint for the prototype classifier.

**Acceptance Criteria:**
- Given a prototype fixed-grid viewfinder and calibrated centers, when a face is scanned, then it emits a FaceSample and nearest-center classification, and accuracy is computed against recorded ground truth.
- Given live captures on both reference devices and both cube types in normal indoor light, when the criterion of at least 99% correct stickers is measured, then GA is recommended; otherwise the documented outcome is BETA, Manual Cube Editor primary, and PRD memlog update via the approved memlog workflow.
- Given OQ-8, when the spike closes, then the four-lighting/device/cube matrix, JSON fixture contract, Quick Scan method recommendation and size cost are recorded; any unrun physical measurements remain explicit before-merge gates.

## Implementation Notes


## Plan Change Log


## Review Triage Log

| Finding | Verdict | Route | Evidence and resolution |
| --- | --- | --- | --- |
| Live scan had no explicit truth entry, so it could not compute accuracy for camera captures. | medium | patch | Added per-sticker truth controls; capture now reports correct/total and percent only when all nine labels are explicit. |
| Live scan could accept blur, misalignment, or incomplete grids without a specific recapture outcome. | medium | patch | Added quality override controls; those statuses return an unavailable classification with a recapture reason. |

### 2026-10-31 — Review pass
- verdicts: 2 findings — high 0, medium 2, low 0, false 0, maybe-false 0
- findings:
  - `[medium]` `[defer]` BETA recommendation requires PRD memlog append — Reviewer verified no PRD memlog append exists; this run's orchestrator instruction forbids planning-artifact edits, so the required append is left for the independent gate.
  - `[medium]` `[patch]` Analysis samples could diverge from the visible camera grid — Updated sampling to use `ImageProxy.cropRect` and reject an empty crop; opt-in debug compilation passed.


## Design Notes

Build the prototype as an isolated app build variant so CameraX and capture UI do not leak into release. Domain classification and fixture evaluation remain JVM-testable; the camera adapter translates pixels into neutral samples. Separate source images from labeled fixture rows so no folder convention is mistaken for color truth. Result reporting must distinguish measured, fixture-only and not-yet-run cells.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :core:domain:test` -- expected: classifier and fixture evaluation checks pass.
- `./gradlew assembleDebug assembleRelease` -- expected: both variants build and prototype is absent from release.
- `./gradlew spotlessCheck` -- expected: formatting passes.

**Manual checks:** Connect Galaxy S24 FE and Galaxy Tab S7 FE 5G, capture each cube under normal indoor light, confirm the face grid and orientation, and review accuracy against independently entered ground truth before making the merge decision.


## Auto Run Result

Status: built. Implemented an opt-in debug CameraX fixed-grid capture prototype with in-memory `FaceSample` emission, calibrated CIELAB nearest-center classification, explicit truth scoring, deterministic synthetic fixture evaluation, the OQ-8 measurement matrix, Quick Scan recommendation, and measured APK size impact. The adapter now samples against CameraX `cropRect` and marks empty crops unavailable.

Files changed: `Fixtures/README.md` (fixture limits and evaluation command); `Fixtures/labeled/face-samples.json` (synthetic labeled data); `app/build.gradle.kts`, `app/src/spikeScan/AndroidManifest.xml`, `app/src/spikeScan/kotlin/dev/rubikxplorer/app/spikeScan/GuidedScanActivity.kt` (opt-in camera prototype); `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Color.kt`, `Face.kt`, `core/domain/src/main/kotlin/dev/rubikxplorer/domain/recognition/FaceSample.kt` (neutral sample and classifier); `core/domain/src/test/kotlin/dev/rubikxplorer/domain/recognition/ScanFixtureEvaluationTest.kt` (fixture evaluation); `gradle/libs.versions.toml` (CameraX); `docs/spikes/oq-4-guided-scan.md` (matrix, BETA recommendation, method and size results).

Review: 1 medium patch applied; 1 medium item deferred due to the orchestrator's no-planning-artifacts constraint; no findings rejected. Follow-up review recommendation: false (patched count: medium 1, high 0). Verification passed: `./gradlew :app:testDebugUnitTest :core:domain:test assembleDebug assembleRelease spotlessCheck`; `./gradlew -Prubikxplorer.spikeScan=true :app:compileDebugKotlin assembleDebug assembleRelease`. Merged manifests show CAMERA and the prototype activity in opt-in debug, absent from release. One ad hoc check used a nonexistent task name (`:app:checkMergedManifestPolicy`) and failed; direct merged-manifest inspection supplied the intended evidence. Residual risks: live physical accuracy remains unmeasured and must be evaluated before merge; blur, alignment and completeness are operator-marked, not automatically detected; the required PRD memlog append awaits independent gate disposition.
