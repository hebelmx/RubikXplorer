---
title: 'Offline S3 calibrated S4 photo color evaluation'
type: 'feature'
ticket: ''
created: '2026-10-31'
status: 'built'
baseline_revision: '654e20637204c0e5bebc2b94070214ccf053d19c'
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
deferred: []
---

<intent-contract>

## Intent

**Problem:** The existing JPEG fixtures have no image-level sticker truth, so classifier accuracy on real source pixels cannot be stated. The user authorizes visually transcribed S4 truth and S3 calibration images for both cubes and devices.

**Approach:** Check in explicit per-image row-major labels and reproducible sticker-center sampling, derive six Lab centers per cube/device from the six visually monochrome S3 faces, and score the six visually single-face S4 images per group. Keep results limited to offline image color classification; face identity, orientation, full-cube validity, lighting-condition names, and CameraX/live accuracy are outside the evidence.

## Boundaries & Constraints

**Always:** Preserve original JPEGs; record exact source paths and exclude multi-face frames based on visual review, not directory names alone. Use the current D65 sRGB-to-CIELAB and nearest-center method, with explicit nine-sticker truth. Report all coverage, unavailability, accuracy, and ambiguities.

**Never:** Infer labels from filenames or scramble/state assignment; claim this evaluates the live CameraX pipeline or solves face orientation. Do not edit PRD, epics, architecture spine, UX docs, or memlogs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Labeled face photo | JPEG, explicit sticker-center locations, nine visual labels | Deterministic Lab samples and per-sticker classification score | Missing file, malformed coordinate, or invalid labels fail with image-specific message |
| Calibration image | Six visually monochrome S3 face photos | Per-color/device/cube centers derived from sampled pixels | Ambiguous color/center is documented and excluded, never inferred from folder |
| Multi-face/unclear image | Visually reviewed candidate source | Excluded with filename and reason | No partial truth or guessed grid |

</intent-contract>

## Code Map

- `core/domain/.../recognition/FaceSample.kt` -- `NearestCenterClassifier` already implements explicit-truth scoring of nine CIELAB samples against six calibrated centers; preserve API.
- `app/src/spikeScan/.../GuidedScanActivity.kt` -- existing D65 sRGB-to-Lab conversion to match; do not claim its YUV camera path was exercised.
- `Fixtures/` -- S3/S4 originals, grouped by cube/device/state; visual selection/transcriptions belong in a separate checked-in label manifest.
- `Fixtures/labeled/face-samples.json` and `ScanFixtureEvaluationTest.kt` -- synthetic contract fixture; keep it distinct from photo results.
- `tools/evaluate_fixture_photos.py`, `tools/test_evaluate_fixture_photos.py` -- deterministic image sampling, D65 Lab conversion and manifest validation.
- `docs/spikes/oq-4-guided-scan.md`, `Fixtures/README.md` -- record measured offline score, method, exact input inventory and limitations.

## Tasks & Acceptance

**Execution:**
- [ ] `Fixtures/labeled/photo-evaluation.json` -- record exact S3 calibration and S4 scored JPEGs, sticker coordinates, per-sticker truth, and visual exclusions for all four cube/device groups.
- [ ] `tools/evaluate_fixture_photos.py` -- deterministically sample JPEG sticker centers, convert sRGB to Lab identically, calculate per-class centers and nearest-center scores, and emit machine-readable output.
- [ ] `Fixtures/labeled/photo-evaluation-results.json` -- checked-in reproducible output with counts/accuracy by group and aggregate.
- [ ] `docs/spikes/oq-4-guided-scan.md`, `Fixtures/README.md` -- describe method, exact S3/S4 set, observed limitations, and distinguish photo score from live gate.
- [ ] `tools/test_evaluate_fixture_photos.py` -- validate complete labels, normalized sample geometry, calibration coverage, and source availability.

**Acceptance Criteria:**
- Given the checked-in JPEGs, labels, and script, when evaluation runs offline, then it regenerates identical per-sticker counts and aggregate accuracy for all four groups.
- Given each selected image, when labels and sample points are inspected against the source image, then all nine labels are visually grounded and ambiguous samples are called out.
- Given the reported score, when evidence is reviewed, then it is explicitly scoped to RGB JPEG pixel color classification and does not represent live CameraX accuracy or face identity/orientation.

## Implementation Notes

Implemented the 24-image S4 color-only evaluation, using six S3 calibration photos and six S4 score photos per cube/device pair. The normalized grid rectangles and per-image row-major labels are visually transcribed in `Fixtures/labeled/photo-evaluation.json`; overlay contact sheets are checked in for S3 calibration patch points and S4 score/patch points. The result is 135/216 = 62.5000% (Rubik S24 FE 34/54, GAN S24 FE 39/54, Rubik Tab 26/54, GAN Tab 36/54), with no unavailable selected images. `python3 tools/evaluate_fixture_photos.py --check`, `python3 -m unittest discover -s tools -p 'test_evaluate_fixture_photos.py'`, the focused `ScanFixtureEvaluationTest`, and `./gradlew spotlessCheck build` passed. Source groups are recorded as S3 and S4 only; no scramble identity or named light condition is claimed. Remaining human gate: live CameraX/YUV captures on both physical devices and both cubes in controlled normal indoor light, with the 99% sticker criterion.

## Plan Change Log

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 1 finding — high 0, medium 1, low 0, false 0, maybe-false 0
- findings:
  - `[medium]` `[patch]` Python Lab conversion differed from the CameraX prototype constants — Updated Y coefficients and Lab threshold to exact Kotlin values; regenerated JSON and overlays, then deterministic check, unit tests, classifier test, and full build passed.

## Design Notes

S3 and S4 are treated as distinct capture groups per user direction; no named illumination or cube-state correspondence is inferred. Use S3 per-group color calibration and S4 per-group scoring, so the evaluation measures transfer between those captured groups. Logo stickers remain labeled by the visible underlying surface color and should be flagged if the logo contaminates sampling.

## Auto Run Result

Status: built. Added an offline, reproducible RGB JPEG classifier evaluation with six S3 calibration photos and six S4 scored faces for each cube/device pairing. The 24 S4 images contain 216 explicit sticker labels; 135 classifications matched (62.5000%). Group scores: Rubik stickered/Galaxy S24 FE 34/54, GAN stickerless/Galaxy S24 FE 39/54, Rubik stickered/Galaxy Tab 26/54, GAN stickerless/Galaxy Tab 36/54. No selected image was unavailable. All exact S3/S4 source paths, visual labels, normalized S4 grids, and exclusions are in `Fixtures/labeled/photo-evaluation.json`; QA overlays show S3 sampling patches and S4 cells/patches. Lighting is recorded only as source groups S3 and S4, with no named illumination or scramble/state mapping inferred. This is offline color classification only, not a live CameraX/YUV test; the 99% normal indoor live gate remains before-merge human work.

Changed files: `Fixtures/labeled/photo-evaluation.json` (manual visual labels and geometry); `Fixtures/labeled/photo-evaluation-results.json` (predictions and exact scores); `Fixtures/labeled/photo-evaluation-overlay.jpg`, `photo-calibration-overlay.jpg` (sample QA); `tools/evaluate_fixture_photos.py` (deterministic extractor/scorer); `tools/test_evaluate_fixture_photos.py` (manifest validation tests); `tools/requirements-photo-eval.txt` (Pillow pin); `Fixtures/README.md`, `docs/spikes/oq-4-guided-scan.md` (method, coverage and gate); plan file (workflow evidence).

Review: one medium patch finding fixed; no deferred or rejected findings. Follow-up review recommendation: false. Verification passed: Python manifest tests (5), deterministic result check, focused `ScanFixtureEvaluationTest`, and `./gradlew spotlessCheck build`.

## Verification

**Commands:**
- `python3 -m unittest discover -s tools -p 'test_evaluate_fixture_photos.py'` -- expected: valid and malformed manifest cases pass.
- `python3 tools/evaluate_fixture_photos.py --check` -- expected: output exactly matches checked-in result JSON.
- `./gradlew :core:domain:test` -- expected: existing classifier fixture tests pass.
- `./gradlew spotlessCheck build` -- expected: project gates pass.

**Manual checks:** Open each listed image and verify center points and row-major labels; inspect generated result and confirm multi-face images are excluded.
