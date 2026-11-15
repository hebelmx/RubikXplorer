---
title: 'Choose the recognition approach (OQ-7)'
type: 'feature'
created: '2026-11-15'
status: 'done'
baseline_revision: '21555727cf8b628fe8d46f8115185c3ab8c93d6f'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Spike isolation (recognitionSpike never compiled/resolved by build/check) and the committed oq7 results span are not guarded by any automated check.
    evidence: |-
      verification-gap: no TestKit or CI check asserts build does not resolve recognitionSpikeRuntimeClasspath / recognitionRuntimeAars, or that the committed span equals a fresh evaluateRecognitionApproaches run; the existing evaluation source set has no isolation test either. Local build was observed not to run any recognitionSpike task.
    location: >-
      core/domain/build.gradle.kts recognitionSpike / recognitionRuntimeAars
    severity: low
---

<intent-contract>

## Intent

**Problem:** OQ-7 is re-opened: the live baseline (Story 10.10/10.12, 645/648) met its gate only with careful operator calibration, a bad WHITE swatch (L76 a20 b71) cost a sticker and collapsed margins, and logo centers (`stka`, `stla` WHITE) read far from their color. Story 10.1's product classifier must follow a decision between the current Kotlin nearest-center rule, an OpenCV pipeline and a bundled on-device ML model, and no measured comparison exists.

**Approach:** In non-shipping source sets of `:core:domain`, run all three approaches (plus the PO's center-cell histogram test from issue #153) on the committed session A and A2 fixtures (JSON samples; A2 JPEG frames), and generate the measured tables of a new `docs/spikes/oq-7-recognition-approach.md`: per-sticker accuracy, confidently-wrong rate, bad-white sensitivity, logo-center handling and added release size against NFR-11's 30 MB. The doc ends with a hand-written recommendation and trade-offs for the PO gate.

## Boundaries & Constraints

**Always:**
- Pure, CI-tested logic (fixture-to-observation mapping, nearest-center variants, confidence rule, self-calibration, bad-white injection and sweep, tiny ML model, held-out split, metrics, AAR size parsing, Markdown rendering and marker replacement) lives in the existing `evaluation` source set, package `dev.cubentia.domain.recognition.evaluation.oq7`, unit-tested in `core/domain/src/test` (JUnit 5).
- OpenCV code lives only in a new `recognitionSpike` source set of `:core:domain` (classpath = main + evaluation outputs and their classpaths + `org.openpnp:opencv`), compiled and run only by the on-demand `JavaExec` task `:core:domain:evaluateRecognitionApproaches`; it is NOT wired into `assemble`, `build`, `check` or `test` (the JVM OpenCV jar is ~110 MB). New dependency versions go in `gradle/libs.versions.toml`.
- Approaches and variant ids (used in every table):
  - `K-cal`: current `NearestCenterClassifier` rule on JSON samples with the record's calibration (sessions A and A2).
  - `K-self`: same rule on JSON samples, calibration = each group's six face-center samples (`stickers[4]`, color = `truth[4]`) — no calibration step (A and A2).
  - `O-cal` / `O-self`: OpenCV on A2 frames — `imread`, BGR→float32 [0,1] → `COLOR_BGR2Lab`; grid = centered square of 80% of the short edge, 3×3, row-major (same as the recorder, no rotation: frames and samples share pixel orientation); per cell the central 60% ROI, pixels with L ≥ 98 masked; cell color = histogram mode (3-D `calcHist` over L 10 bins [0,100], a and b 16 bins [−128,128]; mode color = mean of pixels in the peak bin and its 26 neighbors; share = that pixel count / unmasked ROI pixels). `O-cal` uses the record's calibration; `O-self` uses the group's six center-cell modes as calibration. Session A (no frames) is reported as "no frames", never as wrong.
  - `M-loco`: tiny MLP (3 inputs: L/100, a/128, b/128 of the JSON sample → 12 tanh → 6 softmax), no calibration input, trained in pure Kotlin with a fixed seed (`java.util.Random(13)`), fixed full-batch gradient-descent schedule, leave-one-cube-out over all A+A2 stickers (4 folds by cube id; the held-out cube's stickers never enter training). Inference is plain Kotlin over the trained weights; report the weight byte count.
- Confidence: nearest-center variants are CONFIDENT when the second-nearest center is ≥ 10 ΔE (CIE76) farther than the nearest (`CLOSE_CALL_DELTA_E`, same rule as the recorder prefill); `M-loco` is CONFIDENT when its top probability ≥ 0.90. Confidently wrong = misread AND CONFIDENT; rate = count / classified stickers, compared with the ≤ 0.1% NFR target. Also report the UNCERTAIN count.
- Calibration sensitivity (bad-white case): for `K-cal` and `O-cal`, replace every group's WHITE center with L76 a20 b71 and report accuracy and confidently wrong; also a sweep WHITE(t) = (1−t)·recorded + t·bad for t ∈ {0, 0.25, 0.5, 0.75, 1}. Self-calibrated and ML variants are reported as not using a calibration step.
- Logo centers: per A2 group, the U (WHITE-center) face: JSON center sample, O cell mean, O histogram mode and mode share, each with its ΔE to the mean of the same group's non-center WHITE-truth cells measured the same way (JSON samples for the JSON value, O mode for O values), and whether each variant reads the center correctly. Known logo centers: `stka` and `stla` WHITE.
- PO histogram test (issue #153): "plain center" = mode share ≥ 0.60. Report the share of all 48 A2 centers, which centers fail, whether the test separates the known logo centers, and whether calibration from histogram modes (`O-self`) reads logo centers within the close-call margin.
- Release size: an on-demand detached configuration resolves `org.opencv:opencv:4.12.0@aar` and `com.google.ai.edge.litert:litert:2.3.2@aar`; the tool reports each AAR's `jni/arm64-v8a/*.so` uncompressed and compressed bytes and `classes.jar` bytes, and Kotlin variants as 0 added runtime bytes (+ MLP weight bytes). The narrative records the current `./gradlew :app:checkReleaseSize` estimate and the projected estimate per approach (current + arm64 .so uncompressed + 3 × classes.jar as a dex proxy) against 30,000,000 bytes.
- The generated part sits between `<!-- oq7-results:start -->` and `<!-- oq7-results:end -->`; the tool rewrites only that span, fails (naming the file, leaving it untouched) when either marker is missing, and output is byte-identical across runs. Narrative outside the markers: data set and method, limitations (small held-out set, A2 truth mostly prefilled, JPEG vs YUV pipeline offset, LiteRT not executable on the JVM so the MLP runs in Kotlin and LiteRT is costed not run, studio light only), and a final `## Recommendation` with trade-offs (accuracy, confidently wrong, calibration robustness, logo handling, effort, size, and the release-classpath consequence of each option) — written from the measured numbers.

**Never:**
- No change to `app/`, release/benchmark classpaths, manifests, flags, `docs/privacy-facts.json`, `Fixtures/`, `NearestCenterClassifier`, `core/domain/src/main`, build-logic gates, or the `evaluateLiveBaseline` output.
- No ML Kit or Play-services ML; no pretrained-weight download; no Python tooling.
- No planning-artifact edits (PRD memlog, PRD OQ-7 line, spine Quick Scan line — the PO does these at the gate), no `_bmad/`, `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clear win | nearest 5 ΔE, second 30 ΔE | predicted nearest, CONFIDENT | none |
| Close call | nearest 20, second 28 ΔE | predicted nearest, UNCERTAIN | none |
| Confidently wrong | misread + CONFIDENT / misread + UNCERTAIN | counted / not counted | none |
| Bad white | recorded calibration | WHITE = L76 a20 b71, other five unchanged | none |
| Sweep midpoint | WHITE L88 a-6 b-16, t = 0.5 | L82 a7 b27.5 | none |
| Self-calibration | group with six faces | centers keyed by each face's `truth[4]` from `stickers[4]` | group missing a face or two faces with the same center color → group excluded with a reason, not scored |
| Held-out split | 4 cubes | each fold trains on 3 cubes, tests on the 4th; no overlap | none |
| ML determinism | same data, same seed, twice | identical predictions | none |
| No frame | schema-1 record | O variants report "no frames" for the group | none |
| AAR size | zip with `jni/arm64-v8a/libx.so` (1,000 bytes) and `classes.jar` | arm64 .so 1,000 uncompressed; classes.jar size reported | missing AAR → fails naming it |
| Markers missing | doc without end marker | error naming the file | doc unchanged |

</intent-contract>

## Code Map

- `core/domain/build.gradle.kts` -- defines the `evaluation` source set (L27–31), `testImplementation(evaluation.output)`, `assemble` dependsOn evaluation classes, and `evaluateLiveBaseline` JavaExec (L32–38). Add `recognitionSpike` source set, `recognitionSpikeImplementation(libs.opencv.openpnp)`, a `recognitionRuntimeAars` configuration (non-transitive, `@aar`), and `evaluateRecognitionApproaches` (args: `Fixtures/live`, `docs/spikes/oq-7-recognition-approach.md`, OpenCV AAR path, LiteRT AAR path). Repositories come from `settings.gradle.kts` (google, mavenCentral; FAIL_ON_PROJECT_REPOS).
- `gradle/libs.versions.toml` -- add `opencv-openpnp` (`org.openpnp:opencv:4.9.0-0`), `opencv-android` (`org.opencv:opencv:4.12.0`), `litert` (`com.google.ai.edge.litert:litert:2.3.2`).
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/LiveFixtureLoader.kt` -- `load(root): List<FixtureGroup>`; reuse for records (each `FixtureGroup` holds `LiveFixtureRecord`s; frame file = record `frame` sibling).
- `.../evaluation/LiveBaselineMarkdown.kt` L71 `replace(...)` -- marker-replacement pattern to mirror (different markers).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` -- `Lab`, `CalibrationCenters`, `NearestCenterClassifier.predict` (read-only; `K-cal` must equal its predictions).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `LiveFixtureRecord` (schema 1/2, `truthSources`, `frame`).
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` L235–316 -- read-only evidence for frame geometry (80% centered square, 3×3, center pixel per cell) and the YUV→RGB→Lab conversion the JSON samples used.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/CameraClasspathPolicy.kt` -- release gate forbids `org.opencv`, `org.tensorflow`, `com.google.ai.edge.litert` on the app runtime classpath; unaffected because only `:core:domain` non-main configurations gain them. `ModuleBoundaryPolicy` forbids only androidx/com.android/com.google.android/dagger/compose groups in `:core:*`.
- `Fixtures/live/*_studio/` (session A, 4 groups, schema 1, no frames) and `*_studio-a/` (A2, 8 groups, schema 2, `<FACE>.jpg`; Lenovo 200×640, S24 FE 480×422). Face U is the WHITE center (blue-front table); logo cubes `stka`, `stla`.
- `docs/spikes/oq-4-guided-scan.md` § Device session A/A2 notes -- source of the bad-white values and logo observations to cite.

## Tasks & Acceptance

**Execution:**
- `gradle/libs.versions.toml`, `core/domain/build.gradle.kts` -- source set, configurations and task per Code Map; nothing added to `main`, `test` runtime beyond what tests need (tests must not need OpenCV).
- `core/domain/src/evaluation/kotlin/.../evaluation/oq7/` (new files) -- observations from records (+ optional frame features), nearest-center variants with margin/confidence, self-calibration, bad-white injection and sweep, `TinyMlp` + leave-one-cube-out, metrics per variant × scope (session A, A2, each device, total), logo-center and histogram-test tables, AAR size reader, Markdown renderer and marker replacement.
- `core/domain/src/test/kotlin/.../evaluation/oq7/` (new) -- one test per I/O matrix row, plus `K-cal` equals `NearestCenterClassifier.predict` on a fixture record.
- `core/domain/src/recognitionSpike/kotlin/.../evaluation/oq7/` (new) -- OpenCV frame features (load natives via `nu.pattern.OpenCV.loadLocally()`) and `main(args)` that assembles everything and rewrites the doc span.
- `docs/spikes/oq-7-recognition-approach.md` (new) -- narrative, markers, generated results, recommendation; run `./gradlew :core:domain:evaluateRecognitionApproaches` to fill the span.

**Acceptance Criteria:**
- Given the committed session A and A2 fixtures, when `./gradlew :core:domain:evaluateRecognitionApproaches` runs, then the doc's generated span reports, for `K-cal`, `K-self`, `O-cal`, `O-self` and `M-loco`, sticker accuracy, confidently-wrong count and rate, uncertain count, bad-white results and sweep, logo-center and histogram-test tables, and release-size rows; a second run leaves `git diff` of the doc empty.
- Given the finished doc, when read, then it ends with a `## Recommendation` naming one approach, its trade-offs against the others, the projected install size versus 30 MB, and whether adopting it would change the Story 10.9 release-classpath gate.
- Given this branch, when the app release runtime classpath is listed, then it is identical to the baseline revision's, and `./gradlew spotlessCheck build` succeeds without downloading or compiling OpenCV.

## Spec Change Log

## Review Triage Log

### 2026-11-15 — Review pass
- verdicts: 40 findings — high 0, medium 0, low 31, false 9, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Recommended reader (Kotlin histogram, self-calibrated) never run end to end; O-self used OpenCV decode/Lab — Recommendation and Limitations now say so explicitly and that 10.1 must re-run the fixtures through the all-Kotlin reader.
  - `[low]` `[patch]` (blind) Recommendation compared O-self 432/432 (A2) with K-cal 645/648 (A+A2) — accuracy bullet now compares on A2 only.
  - `[low]` `[patch]` (blind) "≤ 0.1%" column said yes on zero observations — renamed "Observed ≤ 0.1%" with a rule-of-three line.
  - `[low]` `[patch]` (blind) Circular truth-bias sentence — removed.
  - `[low]` `[reject]` (blind) Logo guard and 60% threshold unmeasured — the doc states the guard is unmeasured; a fallback simulation and threshold sweep add new analysis beyond AC1 for a proposal the PO gate decides.
  - `[low]` `[patch]` (blind) "Separates: no" vs Recommendation wording — generated line now "Fully separates … no — 3 of 4 flagged, 0 false alarms on 44"; Recommendation matches.
  - `[low]` `[patch]` (blind) toml comment misstated CameraClasspathPolicy — now "forbidden whenever every camera flag is OFF".
  - `[low]` `[patch]` (blind) LiteRT size non-transitive (POM depends on litert-api) — doc calls the LiteRT figure a lower bound.
  - `[low]` `[patch]` (blind) openpnp 4.9.0 measured vs Android 4.12.0 costed undisclosed — disclosed in Limitations.
  - `[low]` `[patch]` (blind) Hand-written size table will drift — marked as a 2026-11-15 snapshot on this branch.
  - `[false]` `[reject]` (blind) Frame orientation/EXIF unchecked — all 72 records have orientationDegrees 0 and the recorder JPEGs carry no EXIF (checked), so cells cannot be shuffled on these fixtures.
  - `[low]` `[patch]` (blind) "Byte-identical" claim unscoped — scoped to the same machine and JDK.
  - `[false]` `[reject]` (blind) Close-call constant copied, confidence parity untested — the recorder constant lives in app debug code `:core:domain` cannot reference; the confidence rule is pinned by the clear-win/close-call matrix tests.
  - `[low]` `[patch]` (blind) O cell mean ΔE measured against the mode reference — now measured against the plain WHITE cells' means (`frameReference`).
  - `[low]` `[reject]` (blind) Mats never released, global counters — one-shot JavaExec over 48 small frames in a fresh JVM per run; JNI lifetime is a product concern, not this spike's.
  - `[low]` `[patch]` (blind) `Session.of` maps any schema ≠ 1 to A2 — now 1→A, 2→A2, else fails naming the version.
  - `[false]` `[reject]` (edge) Tiny frame gives empty ROIs — committed frames are ≥ 200 px on the short edge; unreachable on the evaluated data.
  - `[low]` `[reject]` (edge) Fully glared cell becomes the mode — no such cell in the 432 frame cells; handling adds a branch for an unseen case.
  - `[low]` `[reject]` (edge) AAR without classes.jar/arm64 libs reports 0 silently — both pinned AARs have both (rows show non-zero); a guard protects no reachable state.
  - `[low]` `[patch]` (edge) Unknown schema counted as A2 — same fix as the blind `Session.of` row.
  - `[low]` `[reject]` (edge) Partially framed group reported as "no frames" — every A2 face has a frame; adds branching for an unseen state.
  - `[low]` `[reject]` (edge) K-cal scores records the shipped classifier would refuse — all fixtures are ACCEPTABLE with valid Lab (live baseline: 0 unavailable); parity test on a real record passes.
  - `[low]` `[reject]` (edge) LOCO with a single cube aborts — fixtures have 4 cubes; the failure is loud.
  - `[low]` `[reject]` (edge) Native Mats never released — same as the blind row.
  - `[false]` `[reject]` (edge) Counters overstate across runs in one JVM — each JavaExec run is a fresh JVM, so counters start at zero.
  - `[false]` `[reject]` (edge) Orientation ≠ 0 shuffles cells — no record has orientation ≠ 0 (72/72 are 0).
  - `[low]` `[reject]` (edge) CRLF checkout gives mixed endings — `.gitattributes` text=auto and the doc is generated on Linux; the existing live-baseline replacer behaves the same.
  - `[low]` `[reject]` (edge) Non-atomic doc write — same call as Story 10.10/10.12: a dev tool, rerun regenerates.
  - `[low]` `[patch]` (verification-gap) LOCO leakage unobservable by the test — added a test with differing cubes: held-out predictions equal a model trained without it and differ from one trained with it.
  - `[low]` `[patch]` (verification-gap) oCal/oSelf feature choice untested — added a test with mean ≠ mode, asserting oCal, oSelf (cell-4 mode calibration) and bad-white oCal predictions.
  - `[low]` `[patch]` (verification-gap) Logo/separation verdict untested — added a stka logo report test asserting the separation line and the logo rows.
  - `[low]` `[patch]` (verification-gap) Histogram decode order and neighbor radius untested — added an asymmetric (a40 b−40) test with adjacent and two-away bins asserting peak and share.
  - `[low]` `[defer]` (verification-gap) Spike isolation and committed span not checked automatically — deferred as filed; local `spotlessCheck build` ran no recognitionSpike task.
  - `[low]` `[patch]` (verification-gap) Doc said nothing is on the test classpath — corrected: oq7 classes are on `:core:domain` test; OpenCV spike code is not.
  - `[low]` `[patch]` (verification-gap) `Session.of` — same fix as the blind row.
  - `[false]` `[reject]` (intent) ML runs as Kotlin, LiteRT only costed — the invocation's fallback explicitly allows the smallest reproducible model with the limitation documented, which Limitations does.
  - `[low]` `[patch]` (intent) Measured OpenCV differs from the costed artifact — same disclosure as the blind version-gap row.
  - `[low]` `[patch]` (intent) Recommended configuration not run end to end — same fix as the first blind row.
  - `[false]` `[reject]` (intent) Report numbers not CI-checked — AC1 asks for the report; the numbers come from the committed on-demand tool and are reproducible; isolation guard is the deferred row.
  - `[false]` `[reject]` (intent) Size is a projection, no APK built — the spec defines the projection method and the doc labels it an estimate, like `checkReleaseSize` itself.

## Design Notes

LiteRT ships only as an Android AAR, so a `.tflite` cannot be executed in a JVM source set, and producing one needs TensorFlow tooling and downloads. Per the orchestrator's fallback, the ML arm is the smallest reproducible model — a tiny MLP trained in Kotlin from the fixtures with a held-out cube — and LiteRT is costed from its AAR, not run. The doc must say so.

`O-self` / `K-self` model what a product scan can actually do (the face prompt fixes each center's color, the user cannot move a plain sticker into the center), so they are the fair calibration-free baselines; `*-cal` variants show the operator-calibrated ceiling.

## Verification

**Commands:**
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q > /tmp/oq7-release-before.txt` (run at baseline, before edits) and again after into `/tmp/oq7-release-after.txt`; `diff` -- expected: no difference
- `./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.evaluation.oq7.*'` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:domain:evaluateRecognitionApproaches && ./gradlew :core:domain:evaluateRecognitionApproaches && git diff --stat docs/spikes/oq-7-recognition-approach.md` -- expected: success; the second run changes nothing
- `./gradlew :app:checkReleaseSize` -- expected: success; estimate recorded in the doc
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `git diff --exit-code 21555727cf8b628fe8d46f8115185c3ab8c93d6f -- app Fixtures docs/privacy-facts.json docs/spikes/oq-4-guided-scan.md core/domain/src/main build-logic` -- expected: exit 0

## Auto Run Result

**Summary:** Story 10.13 AC1 (the OQ-7 report). `:core:domain` gains pure evaluation logic (`evaluation/.../oq7/`, CI-tested) and an on-demand OpenCV `recognitionSpike` source set run by `./gradlew :core:domain:evaluateRecognitionApproaches`. The tool runs five variants on the session A and A2 fixtures: `K-cal` (the current rule), `K-self` (self-calibrated from face centers), `O-cal` and `O-self` (OpenCV histogram-mode cells on the A2 frames), and `M-loco` (a 126-parameter Kotlin MLP, leave-one-cube-out). It generates `docs/spikes/oq-7-recognition-approach.md`, covering accuracy and confidently wrong, bad-white sensitivity with a sweep, logo centers, the issue #153 histogram test, and release size from the OpenCV 4.12.0 and LiteRT 2.3.2 AARs. The A2 results are: O-self 432/432 with 0 confidently wrong, O-cal 431, K-self 430, K-cal 429, M-loco 426. Bad white drops K-cal to 84.10%. The histogram test flags 3 of 4 logo centers with 0 false alarms. OpenCV would take the projected size to 37.6 MB (over 30 MB) and LiteRT to 17.2 MB or more. The recommendation is K: the Kotlin nearest-center rule fed by a Kotlin histogram-mode reader and self-calibrated, with no classpath or gate change. AC2 (PRD memlog, OQ-7 line, spine Quick Scan line) is left for the PO gate.

**Files changed:**
- `gradle/libs.versions.toml`: openpnp OpenCV, Android OpenCV AAR and LiteRT AAR versions (spike only).
- `core/domain/build.gradle.kts`: `recognitionSpike` source set (Kover-excluded), `recognitionRuntimeAars` configuration and the `evaluateRecognitionApproaches` task.
- `core/domain/src/evaluation/.../oq7/*.kt` (new): observations, nearest-center and confidence, self-calibration, bad white and sweep, TinyMlp with LOCO, histogram, AAR size, report and Markdown.
- `core/domain/src/recognitionSpike/.../oq7/*.kt` (new): OpenCV frame features and the main entry point.
- `core/domain/src/test/.../oq7/Oq7Test.kt` (new): 19 tests.
- `docs/spikes/oq-7-recognition-approach.md` (new): method, limitations, generated results, size projection and recommendation.

**Review findings:** 40 findings (low 31, false 9). Patched 21 rows: Session.of schema check, the "Observed ≤ 0.1%" column with rule of three, the separation wording, the O-mean reference, the toml comment, 8 doc narrative fixes and 4 new tests (LOCO leakage, O feature choice, logo report, histogram bin). Deferred 1: no automated guard for spike isolation or for the committed span. Rejected 18: 9 false (EXIF and orientation ×2, constant parity, counter reset, tiny frames, ML fallback sanctioned, report not in CI, size projection) and 9 low (logo-guard simulation, Mat release ×2, full-glare cell, AAR guard, partial frames, K-cal unavailable parity, single-cube LOCO, CRLF, non-atomic write), each unreachable on the committed data or fixable only by adding guards.

**Follow-up review recommended:** false. Patched: high 0, medium 0, low 21.

**Verification:**
- `./gradlew spotlessCheck build` passed after the patches, and no recognitionSpike task ran.
- Oq7Test: 19/19 passed, 0 skipped.
- `evaluateRecognitionApproaches` reran with the same sha256 (c6e22e50…).
- The release runtime classpath is identical to the baseline (`/tmp/oq7-release-before.txt`).
- `git diff` against the baseline is empty for app, Fixtures, privacy facts, oq-4, core/domain/src/main and build-logic.
- `./gradlew :app:checkReleaseSize` estimates 11,491,896 bytes.

**Residual risks:**
- The recommended all-Kotlin reader was not run end to end. Only the OpenCV-to-Kotlin peak bin was cross-checked (432/432), so 10.1 and Session B must re-measure it on YUV.
- The sample of 648 stickers cannot show the ≤ 0.1% target.
- The logo guard is unmeasured.
- The OpenCV jar download on a clean CI cache was not observed.
- `SolveFlowViewModelTest` failed once in `:app` with a Dispatchers.Main race, outside this change.
