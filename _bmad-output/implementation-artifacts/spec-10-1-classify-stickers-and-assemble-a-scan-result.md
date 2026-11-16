---
title: 'Classify stickers and assemble a Scan Result'
type: 'feature'
created: '2026-11-15'
status: 'done'
baseline_revision: '398123e193376ee72dcb5c04c6ec8faa9dc2e6d5'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
  - '{project-root}/docs/spikes/oq-7-recognition-approach.md'
warnings: ['oversized']
deferred:
  - summary: >-
      SelfCalibration accepts self-calibrated or derived centers that sit within the close-call margin of each other (pastel/stickerless cube, derived WHITE near YELLOW, outliers dominating the 8 farthest stickers).
    evidence: |-
      blind + edge reviewers; SelfCalibration.from returns Calibrated without checking pairwise center distance. No fixture triggers it (all three derived WHITE centers read 8/8 non-center whites), so the rule would be unmeasured; belongs with the 10.4 calibration flow.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SelfCalibration.kt
    severity: medium
  - summary: >-
      No test asserts that the committed docs/scan-accuracy.md span equals a fresh render.
    evidence: |-
      Byte equality of javax.imageio JPEG decode across JDK 17 (CI) and JDK 25 (local) is unverified, so a drift test could fail only in CI; the scored totals are pinned by ScanAccuracyTest instead.
    location: >-
      core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/scan/ScanAccuracyTest.kt
    severity: low
  - summary: >-
      The orientationDegrees term in ScanAccuracy truthNet is unobservable on the committed fixtures (all 72 records are 0).
    evidence: |-
      verification-gap reviewer; ScanAssemblerTest covers orientation on the product side only. Revisit when Story 10.2 records rotated frames.
    location: >-
      core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/scan/ScanAccuracy.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** There is no product sticker classifier or scan assembler: `:core:domain` only has the Story 1.9 `NearestCenterClassifier` (single center pixel, operator calibration, no confidence, no assembly). OQ-7 decision K (PO 2026-11-15) fixes the method, and the spike's 432/432 for that method was measured with OpenCV doing JPEG decode, Lab conversion and the histogram, so it has never run in pure Kotlin.

**Approach:** Add pure-Kotlin product logic to `dev.cubentia.domain.recognition` in `:core:domain` main: a per-cell histogram-mode `CellReader`, a `StickerClassifier` (nearest calibrated center, calibration as a parameter, ≥ 10 ΔE close-call rule → categorical `StickerConfidence`), a `SelfCalibration` from the six prompted face centers with the < 60 % mode-share logo guard, and a `ScanAssembler` that rotates samples into URFDLB `Facelets` from their center and up-neighbor colors and returns `ScanResult.Full` or `Partial`. An `evaluation`-source-set tool re-runs the committed session A/A2 fixtures through this all-Kotlin path (JDK ImageIO JPEG decode, no OpenCV) and generates a per-device accuracy report.

## Boundaries & Constraints

**Always:**
- Scope choice (orchestrator clarification a): 10.1 is pure JVM logic in `:core:domain` only. The YUV→Lab conversion and cell geometry on camera frames stay in the 10.2 adapter; the calibration UI, persistence and plain-sticker prompt stay in 10.4; review prefill UI stays in 10.3.
- Extend, don't duplicate: `FaceSample` gains nullable, default-null fields `centerColor: Color?` (the color the face prompt asked to face the camera), `upNeighborColor: Color?` (the center color the prompt asked to be on top) and `centerPlain: Boolean?` (the center cell's mode share ≥ 60 %; null = not measured). Committed fixture JSON (schema 1/2 records, inner sample schema 1) must still decode unchanged, and `LiveFixtureJson` output for existing records must not change shape except for these defaulted fields. `CalibrationCenters` is the calibration type everywhere. `ScanResult.Partial` wraps `PhysicalObservation.Partial` (faces keyed by `Face`, colors in net order) rather than a parallel map type.
- Public API exposes only categorical confidence: `enum class StickerConfidence { CONFIDENT, UNCERTAIN }`. ΔE margins stay `internal`/private. `CellReading` may expose its measured mode color and a `plain: Boolean`; no numeric confidence or margin is public.
- Confidence rule: a sticker is CONFIDENT iff (ΔE to second-nearest center) − (ΔE to nearest) ≥ 10.0 (CIE76); ties for nearest resolve in `Color.entries` order. This constant is the single source the Story 10.3 prefill will use (`StickerClassifier.CLOSE_CALL_DELTA_E`, public const is fine — it is a rule, not a confidence value).
- `CellReader.read(pixels: List<Lab>)`: mask pixels with L ≥ 98; 3-D histogram L 10 bins over [0,100], a and b 16 bins over [−128,128] (out-of-range pixels unbinned); peak bin ties → first in (L, a, b) order; value = mean of unmasked pixels in the peak bin and its 26 neighbours; share = that count / unmasked count; `plain` = share ≥ 0.60. All-masked input falls back to all pixels; empty input → sealed failure, never an exception. Behaviour must equal the spike's `oq7.LabHistogram.peak` + `feature` on the same pixels (test it).
- `SelfCalibration.from(samples, saved: CalibrationCenters? = null)` returns a sealed result: `Calibrated(calibration, guarded: Set<Color>)` or `NeedsCalibration(colors: Set<Color>)`. Each color's center = `stickers[4]` of the sample whose `centerColor` is that color. A center with `centerPlain == false` is guarded (not used): replaced by `saved`'s value for that color when `saved` is given; else, when exactly one color is guarded, derived as the mean of the 8 non-center stickers (across all six samples) farthest from their nearest trusted center (ties by sample order then index); two or more guarded without `saved` → `NeedsCalibration(guarded)`. A missing or duplicated `centerColor`, or a sample not ACCEPTABLE, makes that color missing → filled from `saved` if given, else `NeedsCalibration`.
- `ScanAssembler.assemble(samples, calibration: CalibrationCenters): ScanResult`. Slot = the `Face` whose home color (`Color.home`) is `centerColor`. Rotation: pixel order → viewfinder by `orientationDegrees / 90` clockwise quarter turns, then viewfinder → Kociemba net by `d` clockwise quarter turns, where `d` is the net direction (0 top, 1 right, 2 bottom, 3 left) of the up-neighbor's face around that slot in the `Facelets` net (U: B,R,F,L; D: F,R,B,L; R: U,B,D,F; F: U,R,D,L; L: U,F,D,B; B: U,L,D,R). Colors and confidences rotate together. The center sticker's color is the prompted `centerColor`; its confidence is CONFIDENT only if the classifier reads that color confidently. Six placed faces → `Full(facelets, confidence: List<StickerConfidence>)` (54, URFDLB); otherwise `Partial(observation, confidence: Map<Face, List<StickerConfidence>>, gaps: Map<Face, ScanGap>)`. `Full` is not validated (FR-23: 54 colors never count as valid); validation stays with `CubeValidator` and 10.3.
- Accuracy report: new `docs/scan-accuracy.md` with a hand-written intro/limitations and a generated span between `<!-- scan-accuracy:start -->` / `<!-- scan-accuracy:end -->`, regenerated by new on-demand task `./gradlew :core:domain:evaluateScanAccuracy` (JavaExec on the `evaluation` runtime classpath, not wired into build/check/test). Inputs: (1) **Kotlin reader** — A2 frames decoded with `javax.imageio`, sRGB → Lab (D65, same formula as the recorder), cells by the spike's `FrameGrid`, `CellReader` per cell; (2) **center pixel** — the record JSON samples (sessions A and A2; `centerPlain` null). Each fixture face gets `centerColor = truth[4]` (the prompt) and the up-neighbor color recovered from truth: per group, the per-face quarter turns under which the six truth faces assemble into a `CubeValidator`-valid cube (unique for 11 of 12 groups; when none exists, quarter turns 0 and the group is flagged "truth is not a valid cube"). Pipeline per group: `SelfCalibration.from` (no saved) → `ScanAssembler` → compare against identically assembled truth.
- Report tables (deterministic output on one machine/JDK): per device × input: scans, Full, per-scan accuracy (Full and all 54 correct), Full-and-valid count, stickers, correct, per-sticker accuracy, confidently wrong, CW rate vs ≤ 0.1 % (with the rule-of-three caveat), uncertain; rows Lenovo Tab M10 Plus 3rd Gen (`Lenovo TB128XU`), Galaxy S24 FE (`SM-S721U1`), Galaxy Tab S7 FE ("no fixtures"), Total. Plus a per-group table, a misread list, and the logo-guard table (guarded centers, how each was replaced, WHITE stickers correct/CW/uncertain). The intro states why session A has no Kotlin-reader row (schema-1 records carry no frames, only center-pixel samples).

**Never:**
- No Android, CameraX, OpenCV or ML imports in `core/domain/src/main`; no OpenCV in the new evaluation code; no new dependency.
- No change to `app/` (the recorder keeps its own copy of the close-call constant and its `NearestCenterClassifier` use), `Fixtures/`, the `oq7` spike code or `docs/spikes/oq-7-recognition-approach.md`, `NearestCenterClassifier`, build-logic gates, flags, manifests.
- No numeric confidence in any public signature; never fill a missing face from an expected state; no `SessionEvent`, reducer, persistence or UI work.
- No planning-artifact, `_bmad/` or `.claude/skills/` edits.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clear read | nearest 5 ΔE, second 30 ΔE | nearest color, CONFIDENT | — |
| Close call | margin 9.99 / exactly 10.0 | UNCERTAIN / CONFIDENT | — |
| Rotated faces | scrambled cube, each face given with random quarter turn and matching `upNeighborColor`, any `orientationDegrees` in {0,90,180,270} | `Full` facelets equal the cube's facelets | — |
| Missing face | five samples | `Partial` with five faces; gap `MISSING` for the sixth | — |
| Bad capture | one sample quality ≠ ACCEPTABLE, or wrong sticker count/invalid Lab | that face absent; gap `POOR_QUALITY` / `MALFORMED` | — |
| Bad pose | `centerColor` or `upNeighborColor` null, up = same/opposite face, or orientation not a multiple of 90 | gap `NO_POSE` | — |
| Duplicate center | two samples with the same `centerColor` | neither placed; gap `DUPLICATE` | — |
| Bad calibration | `CalibrationCenters` missing a color or invalid Lab | `Partial` with no faces, every face gap `UNCALIBRATED` | — |
| Logo center | one `centerPlain == false`, no saved | `Calibrated`, that color derived from farthest 8 stickers, `guarded = {color}` | — |
| Two logo centers | two `centerPlain == false`, no saved | `NeedsCalibration` naming both | — |
| Empty cell | `CellReader.read(emptyList())` | sealed failure | no throw |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` -- `Lab`, `CaptureQuality`, `FaceSample` (extend), `CalibrationCenters`, Story 1.9 `NearestCenterClassifier` (leave as is; recorder + `LiveBaselineEvaluator` use it).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/PhysicalObservation.kt` -- `PhysicalObservation.Partial(faces, orientationHint)` to wrap.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `LiveFixtureRecord` / `LiveFixtureJson` (`encodeDefaults = true`): new FaceSample fields will serialize as null; decoding of committed fixtures must keep working.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/{Color,Face,Facelets,CubeValidator,CubeState}.kt` -- `Color.home(face)`, URFDLB net layout and adjacency (comment in `Facelets.kt`), `CubeValidator.validate`, `CubeState.SOLVED.apply(...).toFacelets()` for tests.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/oq7/LabHistogram.kt` -- spike histogram (`FrameGrid`, `LabHistogram`, constants) to match and reuse `FrameGrid` from; `NearestCenter.kt` -- spike close-call rule to match.
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/LiveFixtureLoader.kt` -- loads `Fixtures/live` groups (`FixtureGroup`); reuse. `oq7/Oq7Runner.kt`, `oq7/Oq7Markdown.kt` -- marker-replace pattern to mirror.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt:319` -- recorder sRGB→Lab (D65) formula to replicate in evaluation; samples are in pixel order with `orientationDegrees = rotationDegrees` (all 72 committed records are 0).
- `core/domain/build.gradle.kts` -- `evaluation` source set and `evaluateLiveBaseline` JavaExec pattern for the new task.
- `Fixtures/live/` -- 12 groups: session A (4, schema 1, JSON only), A2 (8, schema 2, `<FACE>.jpg` 480-ish px). Faces named under the blue-front hold; placement must use center color, not `face`. `sm-s721u1_stkb_studio-a` truth has 10 WHITE / 8 RED (not a valid cube).
- `Fixtures/README.md` -- add a short pointer to the new task/report.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` -- add the three defaulted `FaceSample` fields with KDoc -- prompt colors and center plainness feed assembly and self-calibration.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CellReader.kt` -- new `CellReader`, `CellReading(value: Lab, plain: Boolean)`, sealed read result -- histogram-mode reader of decision K.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/StickerClassifier.kt` -- new `StickerConfidence`, `StickerReading(color, confidence)`, `StickerClassifier.classify(sticker, calibration)` + calibration-validity check -- nearest center with close-call rule.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SelfCalibration.kt` -- new self-calibration with logo guard per Boundaries.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- new `ScanResult` (`Full`, `Partial`), `ScanGap` enum, `ScanAssembler.assemble` -- URFDLB assembly.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/` -- `CellReaderTest`, `StickerClassifierTest`, `SelfCalibrationTest`, `ScanAssemblerTest` (every I/O-matrix row; a Kotest property test of random scramble + random per-face turns/orientation round-tripping to `Full`), plus a fixture test that decodes every committed record (schema still decodes, new fields null) and runs the report builder on `Fixtures/live` asserting structure (12 groups, 648 stickers, 48 framed faces, Tab S7 FE "no fixtures").
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/scan/` -- `FrameReader` (ImageIO + sRGB→Lab + `FrameGrid` + `CellReader`), `TruthPose` (recover per-face turns from truth), `ScanAccuracy` (pipeline + metrics), `ScanAccuracyMarkdown` (render + marker replace, failing without touching the file when a marker is missing), `ScanAccuracyMain`.
- `core/domain/build.gradle.kts` -- register `evaluateScanAccuracy` (JavaExec, evaluation runtime classpath, args `Fixtures/live` and `docs/scan-accuracy.md`).
- `docs/scan-accuracy.md` -- new report: method, limitations (small set, one light, A2 truth mostly prefilled, JPEG not YUV, up-neighbor recovered from truth because the recorder did not prompt orientation, session A has no frames), generated span; run the task and commit its output. Then summarize the measured result in the intro.
- `Fixtures/README.md` -- one paragraph pointing to the task and report.

**Acceptance Criteria:**
- Given the live fixtures and the synthetic `Fixtures/labeled/face-samples.json`, when `SelfCalibration`/`StickerClassifier` and `ScanAssembler` run in JVM tests, then every complete fixture group assembles to `ScanResult.Full` in URFDLB order and the synthetic sample classifies to its truth, using the extended `FaceSample`/`CalibrationCenters`/`PhysicalObservation` types (no duplicates).
- Given `./gradlew :core:domain:evaluateScanAccuracy`, when it runs twice, then `docs/scan-accuracy.md`'s span is regenerated byte-identically and shows per-sticker accuracy, per-scan accuracy and confidently-wrong rate per device, with Galaxy Tab S7 FE as "no fixtures" and session A explained as center-pixel only.
- Given the public API of `dev.cubentia.domain.recognition`, when inspected, then no new public signature carries a numeric confidence or margin.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes, including the ≥ 80 % Kover gate on `:core:domain`.

## Spec Change Log

## Review Triage Log

### 2026-11-15 — Review pass
- verdicts: 32 findings — high 0, medium 9, low 14, false 9, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) Invalid-truth group `sm-s721u1_stkb_studio-a` (10 WHITE/8 RED) scores 54/54 and the intro's headline 432/432, 8/8 hides it — intro and limitations now say its read matches wrong truth and fails CubeValidator (7/8 correct-and-valid); span regenerated.
  - `[medium]` `[patch]` (blind) Centers counted correct by construction inflate accuracy — intro/limitations state 48 of 432 and 72 of 648 are prompt-fixed; logo-guard table counts only non-center stickers (8/8).
  - `[false]` `[reject]` (blind) Patch edits epic-10-context.md against the spec's Never list — it is the workflow's own step-01 regeneration of a stale cached context under implementation-artifacts, not a planning artifact.
  - `[low]` `[reject]` (blind) Spec contradicts itself on `Fixtures/` (Never vs README task) — fix is a spec edit.
  - `[medium]` `[patch]` (blind) Pose-less sample slotted by `face` turns a good face into DUPLICATE — only centerColor-bearing samples claim slots; pose-less samples report NO_POSE only on an unclaimed face; collision test added.
  - `[low]` `[patch]` (blind) All-glare center always non-plain while KDoc says all pixels are used — behavior matches the spike and spec's spike-equality rule; KDoc corrected.
  - `[medium]` `[defer]` (blind) No sanity check that self-calibrated centers are distinct (pastel cube, derived WHITE near YELLOW) — new unmeasured rule beyond the spec; deferred to 10.4 calibration flow.
  - `[low]` `[reject]` (blind) `ScanResult` lacks `init` invariants — only ScanAssembler constructs it today; adding guards is added complexity for an unlikely misuse.
  - `[low]` `[patch]` (blind) Rule-of-three numbers and "8 farthest stickers" text hardcoded — now computed from scored totals and from how each center was replaced.
  - `[low]` `[defer]` (blind) No test that the committed span equals a fresh render — byte equality of ImageIO decode across JDK 17 (CI) and 25 is unverified; totals are now pinned instead.
  - `[low]` `[reject]` (blind) Net-neighbor/rotation tables duplicated in TruthPose — evaluation source set cannot see main `internal`; making them public adds API surface; test copy is intentional.
  - `[low]` `[reject]` (blind) NeedsCalibration path untested in the evaluation — no fixture reaches it; unit-tested in SelfCalibrationTest.
  - `[false]` `[reject]` (blind) Spec unfinished (status in-progress, empty logs) — that is its state mid-review by design; JAVA_HOME is the project's documented local env.
  - `[medium]` `[patch]` (edge) Same as the pose-less DUPLICATE finding — fixed with it.
  - `[low]` `[reject]` (edge) Saved calibration lacking the guarded color returns NeedsCalibration instead of deriving — rare (saved calibrations hold six colors); extra branch not worth it.
  - `[medium]` `[defer]` (edge) Derived center near a trusted center / outliers dominating the farthest 8 — same as the deferred distinct-centers check.
  - `[low]` `[reject]` (edge) Finite out-of-range pixels give an invalid Lab that surfaces as MALFORMED later — still an honest, non-guessed outcome.
  - `[false]` `[reject]` (edge) One bad frame aborts the evaluation report — a loud failure in an offline tool is correct behavior.
  - `[false]` `[reject]` (edge) Truth size ≠ 9 crashes evaluation — loud failure on malformed fixtures is correct.
  - `[false]` `[reject]` (edge) Group mixing device models — `LiveFixtureLoader` requires each directory to match its records' device model.
  - `[low]` `[patch]` (edge) `meetsTarget` says yes with zero stickers scored — now requires stickers > 0; unit test added.
  - `[medium]` `[patch]` (edge) Guarded-color count includes the center — fixed with the centers finding.
  - `[medium]` `[patch]` (verification-gap) Kotlin-reader 432/432 and center-pixel 646/648 results not pinned — `scored totals are pinned` test plus synthetic `ScanMetrics.of` test added.
  - `[low]` `[patch]` (verification-gap) `SelfCalibration.derive` exclusions (unchosen samples, center index) unpinned — two SelfCalibrationTest cases added.
  - `[low]` `[defer]` (verification-gap) `orientationDegrees` term in `truthNet` unobservable on fixtures (all 0) — revisit when 10.2 records rotated frames.
  - `[medium]` `[patch]` (verification-gap other) ScanAccuracyTest took 214 s in CI because the report was rebuilt per test — report built once in a companion lazy; suite ~9 s.
  - `[false]` `[reject]` (intent) Pose on live fixtures is recovered from truth, so placement is circular — stated in the spec and report limitations; the recorder never captured orientation; synthetic property test covers rotation independently.
  - `[false]` `[reject]` (intent) Accuracy only in a generated doc, not asserted — now asserted (patched above).
  - `[low]` `[reject]` (intent) Report mixes center-pixel rows with the product Kotlin-reader rows — rows are labeled by input; center-pixel rows are the session-A answer required by clarification (b).
  - `[low]` `[reject]` (intent) Synthetic `face-samples.json` only goes through `StickerClassifier`, not assembly — it is one face with no pose, so assembly does not apply.
  - `[false]` `[reject]` (intent) FaceSample KDoc says pixel order while spine says viewfinder order — the recorder emits pixel order plus `rotationDegrees`; ScanAssembler is the only rotator, consistent with AD-9.
  - `[false]` `[reject]` (intent) epic-10-context rewrite out of AC — workflow regeneration (see above).

## Design Notes

Rotation helper (clockwise quarter turn of a row-major 3×3): `new = [old[6], old[3], old[0], old[7], old[4], old[1], old[8], old[5], old[2]]`. If the face's up neighbour sits at net direction `d`, the viewfinder shows the net turned `d` quarter turns counter-clockwise, so `d` clockwise turns restore it.

Fixture up-neighbor recovery is an evaluation stand-in for the 10.2 prompt: the recorder never asked the operator which side was up, so the only honest pose source is the unique valid assembly of the truth. It is applied identically to samples and truth, so it never favours the classifier.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test` -- expected: all tests pass
- `./gradlew :core:domain:evaluateScanAccuracy && git diff --stat docs/scan-accuracy.md` then run again -- expected: second run changes nothing
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** Added the OQ-7 decision K product path as pure JVM logic in `:core:domain` (`dev.cubentia.domain.recognition`): `CellReader` (per-cell histogram mode, `plain` = mode share ≥ 60%), `StickerClassifier` (nearest calibrated center, calibration as a parameter, ≥ 10 ΔE close-call → categorical `StickerConfidence`), `SelfCalibration` (six prompted face centers, logo guard: saved value, else derived from the 8 farthest stickers, else `NeedsCalibration`), `ScanAssembler` (center + up-neighbor colors → URFDLB `ScanResult.Full`/`Partial` wrapping `PhysicalObservation.Partial`, with `ScanGap`). `FaceSample` gained nullable `centerColor`, `upNeighborColor`, `centerPlain`. Scope choice: camera YUV→Lab and grid geometry stay in 10.2, calibration UI/persistence in 10.4, prefill UI in 10.3. An on-demand `:core:domain:evaluateScanAccuracy` task re-runs the fixtures through the all-Kotlin reader (ImageIO decode, no OpenCV) into `docs/scan-accuracy.md`.

**Measured:** Kotlin reader on A2 frames 432/432, 0 confidently wrong, 3 uncertain (centers prompt-fixed: 48 of 432); 7 of 8 scans correct and valid (`sm-s721u1_stkb_studio-a` truth itself is not a valid cube). Center-pixel JSON (A + A2) 646/648, 2 confidently wrong. Session A has no Kotlin-reader row: schema-1 records carry no frames. Per device: Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE reported; Galaxy Tab S7 FE "no fixtures". The guard fired on the three WHITE logo centers (Lenovo stka, Lenovo stla, S24 FE stla); each derived WHITE read 8/8 non-center whites. The sample is too small to show ≤ 0.1% (rule of three).

**Files:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` — three defaulted FaceSample fields.
- `.../recognition/CellReader.kt`, `StickerClassifier.kt`, `SelfCalibration.kt`, `ScanAssembler.kt` — new product logic.
- `core/domain/src/evaluation/.../evaluation/scan/` (FrameReader, TruthPose, ScanAccuracy, ScanAccuracyMarkdown, ScanAccuracyMain) — all-Kotlin re-measure and report.
- `core/domain/build.gradle.kts` — `evaluateScanAccuracy` task (not in build).
- `core/domain/src/test/.../recognition/` — CellReaderTest, StickerClassifierTest, SelfCalibrationTest, ScanAssemblerTest, RecognitionFixtures, evaluation/scan/ScanAccuracyTest.
- `docs/scan-accuracy.md` — new report; `Fixtures/README.md` — pointer.
- `_bmad-output/implementation-artifacts/epic-10-context.md` — regenerated (was stale vs. planning artifacts).

**Review:** 32 findings (medium 9, low 14, false 9). Patched: pose-less sample DUPLICATE collision, CellReader KDoc, guard table non-center counts and replacement text, computed rule of three, `meetsTarget` with zero stickers, report intro qualifications (prompt-fixed centers, invalid stkb truth), shared fixture report (CI test time 214 s → ~9 s) with pinned totals and ScanMetrics test, two SelfCalibration derivation tests. Deferred: distinct-centers sanity check (10.4), committed-span drift test (JDK decode equality unverified), orientation term unobservable on fixtures. Rejected with reasons in the triage log.

**Follow-up review recommendation:** false — no high patched; medium patches were report wording, a narrow assembler slot rule and tests, all verified by the full build.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (9m47s, includes Kover gate); `spotlessCheck :core:domain:test :core:domain:koverVerify :core:domain:evaluateScanAccuracy` passed before patches; `evaluateScanAccuracy` re-run after patches leaves `docs/scan-accuracy.md` byte-identical.

**Residual risks:** pose on live fixtures is recovered from truth (recorder never captured orientation); JPEG not live YUV (session B re-measure); one light, four cubes; derived-center rule measured only on WHITE.
