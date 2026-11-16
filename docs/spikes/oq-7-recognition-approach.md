# OQ-7 spike: recognition approach (Story 10.13)

OQ-7 asks which approach Story 10.1's product sticker classifier uses: the current Kotlin nearest-center rule (K), an OpenCV pipeline (O), or a bundled on-device ML model (M). The live baseline (Stories 10.10 and 10.12, 645/648) met its gate only with careful operator calibration. A bad WHITE swatch cost a sticker and collapsed margins, and logo centers (`stka`, `stla` WHITE) read far from their color (see `oq-4-guided-scan.md`, *Device session A notes* and *Device session A2 notes*). This spike measures all three approaches, plus the PO's center-cell histogram test from issue #153, on the same committed fixtures. It ends with a recommendation for the PO gate.

## Data set and method

- **Fixtures:** `Fixtures/live/`. Session A has 4 groups (Lenovo TB128XU and Galaxy S24 FE × `stka`, `stla`; schema 1; JSON samples only). Session A2 has 8 groups (both devices × `stla`, `stka`, `stlb`, `stkb`; schema 2; JSON samples plus one `<FACE>.jpg` per face). That is 72 faces and 648 stickers, 432 of them with frames. Faces follow the blue-front hold, so face U is the WHITE center.
- **Tooling:** the pure logic lives in the `evaluation` source set (`dev.cubentia.domain.recognition.evaluation.oq7`) and is unit-tested in CI. OpenCV runs only in the on-demand `recognitionSpike` source set. `./gradlew :core:domain:evaluateRecognitionApproaches` regenerates everything between the markers below, and repeated runs on the same machine and JDK give byte-identical output. The `oq7` evaluation classes are on `:core:domain`'s test classpath (that is how CI tests them), but not on `main` or any `:app` classpath. The OpenCV `recognitionSpike` code stays off every classpath except its own task's.
- **Variants:**
  - `K-cal`: the `NearestCenterClassifier` rule on the JSON samples, using each record's operator calibration. This is the shipped baseline.
  - `K-self`: the same rule, calibrated from the group's six face-center samples (`stickers[4]`, color = `truth[4]`). There is no calibration step, so it models a product scan where the face prompt fixes each center's color.
  - `O-cal` / `O-self`: the OpenCV pipeline on the A2 frames. Each frame goes `imread` → float32 → `COLOR_BGR2Lab`, then the recorder's grid is applied (centered square of 80% of the short edge, 3×3). In each cell's central 60% ROI, pixels with L ≥ 98 are masked as glare. The cell color is the 3-D `calcHist` mode (L 10 bins, a and b 16 bins each; mean of the peak bin and its 26 neighbors). `O-cal` uses the record's calibration. `O-self` calibrates from the six center-cell modes.
  - `M-loco`: a tiny MLP (3 → 12 tanh → 6 softmax, 126 parameters, 504 bytes as float32) on the JSON samples with no calibration input. It is trained in Kotlin (seed 13, fixed full-batch schedule) and scored leave-one-cube-out: 4 folds, and a held-out cube's stickers never enter its training.
- **Confidence:** a nearest-center read is CONFIDENT when the second-nearest center is ≥ 10 ΔE (CIE76) farther away, which is the recorder's close-call prefill rule. `M-loco` is CONFIDENT when its top probability is ≥ 0.90. *Confidently wrong* means misread and CONFIDENT, measured against the ≤ 0.1% NFR target.
- **Bad white:** every group's WHITE center is replaced with L76 a20 b71, the session A non-white patch. A sweep then blends the recorded WHITE toward it.

## Limitations

- **Small held-out set:** 648 stickers on 4 cubes, one light (studio), two devices. At this size one sticker is 0.15–0.23%, so the ≤ 0.1% confidently-wrong target cannot be shown to be met. Zero confidently wrong here only means none were observed.
- **A2 truth is mostly prefilled:** the recorder prefilled each sticker from the `K-cal` read when its margin was ≥ 10 ΔE, and the operator corrected misreads (entered-only 237/240). A misread that the operator missed would favor K-cal.
- **JPEG vs YUV pipeline offset:** O variants read the downscaled JPEG (Android `Bitmap` RGB, JPEG-compressed, OpenCV's Lab), while the JSON samples and the recorded calibration come from the recorder's YUV → RGB → Lab center pixel. `O-cal` therefore mixes the two pipelines. `O-self` does not, because its calibration and its samples come from the same frame.
- **Frames only for A2:** session A has no frames, so O variants report it as "no frames" and score 432 stickers, not 648.
- **LiteRT is costed, not run:** LiteRT ships only as an Android AAR, so a `.tflite` cannot execute in a JVM source set, and producing one needs TensorFlow tooling and downloads. The ML arm is therefore the smallest reproducible model, a Kotlin MLP trained from the fixtures, and LiteRT appears only as a size row.
- **Logo handling is studied on four centers** (`stka`, `stla` WHITE × 2 devices).
- **The recommended reader was not run end to end in pure Kotlin:** O-self used OpenCV for JPEG decoding, the float32 Lab conversion and `calcHist`. Only the peak bin was cross-checked against the pure-Kotlin `LabHistogram` (432 of 432 cells). The mode mean and share come from the same Kotlin code in both cases, but a Kotlin YUV → Lab conversion of camera pixels was not measured.
- **Two OpenCV builds:** the O variants ran on the JVM with `org.openpnp:opencv:4.9.0-0`, while the size row costs the Android `org.opencv:opencv:4.12.0` AAR.
- **LiteRT size is a lower bound:** the size row counts only `litert-2.3.2.aar`, resolved non-transitively. Its POM also depends on `litert-api`, which is not counted.

## Results

<!-- oq7-results:start -->
_Generated by `./gradlew :core:domain:evaluateRecognitionApproaches`; do not edit by hand._

### Data set

| Session | Groups | Faces | Stickers | Faces with a frame |
|---|---:|---:|---:|---:|
| Session A | 4 | 24 | 216 | 0 |
| Session A2 | 8 | 48 | 432 | 48 |

`M-loco` folds (tiny MLP 3→12 tanh→6 softmax, seed 13, 3000 full-batch epochs at learning rate 0.50, 126 parameters = 504 bytes as float32):

| Held-out cube | Training cubes | Training stickers | Held-out stickers |
|---|---|---:|---:|
| `stka` | `stkb`, `stla`, `stlb` | 432 | 216 |
| `stkb` | `stka`, `stla`, `stlb` | 540 | 108 |
| `stla` | `stka`, `stkb`, `stlb` | 432 | 216 |
| `stlb` | `stka`, `stkb`, `stla` | 540 | 108 |

### Accuracy and confidently wrong

Confident: nearest-center variants when the second-nearest center is ≥ 10 ΔE farther; `M-loco` when its top probability ≥ 0.90. Confidently wrong = misread and confident; the rate is over classified stickers, against the ≤ 0.1% target.

| Variant | Scope | Classified | Correct | Accuracy | Confidently wrong | CW rate | Observed ≤ 0.1% | Uncertain | Not scored |
|---|---|---:|---:|---:|---:|---:|---|---:|---|
| K-cal | Session A | 216 | 216 | 100.00% | 0 | 0.00% | yes | 1 | — |
| K-cal | Session A2 | 432 | 429 | 99.31% | 3 | 0.69% | no | 4 | — |
| K-cal | Device: LENOVO Lenovo TB128XU | 324 | 323 | 99.69% | 1 | 0.31% | no | 2 | — |
| K-cal | Device: samsung SM-S721U1 | 324 | 322 | 99.38% | 2 | 0.62% | no | 3 | — |
| K-cal | Total | 648 | 645 | 99.54% | 3 | 0.46% | no | 5 | — |
| K-self | Session A | 216 | 216 | 100.00% | 0 | 0.00% | yes | 3 | — |
| K-self | Session A2 | 432 | 430 | 99.54% | 2 | 0.46% | no | 5 | — |
| K-self | Device: LENOVO Lenovo TB128XU | 324 | 324 | 100.00% | 0 | 0.00% | yes | 5 | — |
| K-self | Device: samsung SM-S721U1 | 324 | 322 | 99.38% | 2 | 0.62% | no | 3 | — |
| K-self | Total | 648 | 646 | 99.69% | 2 | 0.31% | no | 8 | — |
| O-cal | Session A | 0 | — | — | — | — | — | — | 4 groups: no frames |
| O-cal | Session A2 | 432 | 431 | 99.77% | 1 | 0.23% | no | 2 | — |
| O-cal | Device: LENOVO Lenovo TB128XU | 216 | 215 | 99.54% | 1 | 0.46% | no | 0 | 2 groups: no frames |
| O-cal | Device: samsung SM-S721U1 | 216 | 216 | 100.00% | 0 | 0.00% | yes | 2 | 2 groups: no frames |
| O-cal | Total | 432 | 431 | 99.77% | 1 | 0.23% | no | 2 | 4 groups: no frames |
| O-self | Session A | 0 | — | — | — | — | — | — | 4 groups: no frames |
| O-self | Session A2 | 432 | 432 | 100.00% | 0 | 0.00% | yes | 3 | — |
| O-self | Device: LENOVO Lenovo TB128XU | 216 | 216 | 100.00% | 0 | 0.00% | yes | 1 | 2 groups: no frames |
| O-self | Device: samsung SM-S721U1 | 216 | 216 | 100.00% | 0 | 0.00% | yes | 2 | 2 groups: no frames |
| O-self | Total | 432 | 432 | 100.00% | 0 | 0.00% | yes | 3 | 4 groups: no frames |
| M-loco | Session A | 216 | 215 | 99.54% | 0 | 0.00% | yes | 20 | — |
| M-loco | Session A2 | 432 | 426 | 98.61% | 2 | 0.46% | no | 24 | — |
| M-loco | Device: LENOVO Lenovo TB128XU | 324 | 322 | 99.38% | 2 | 0.62% | no | 11 | — |
| M-loco | Device: samsung SM-S721U1 | 324 | 319 | 98.46% | 0 | 0.00% | yes | 33 | — |
| M-loco | Total | 648 | 641 | 98.92% | 2 | 0.31% | no | 44 | — |

Zero confidently wrong observed does not show the ≤ 0.1% target is met: by the rule of three, 0 of N is consistent with a true rate up to about 3/N at 95% (0.69% for 432 stickers, 0.46% for 648).

### Per group

Correct / classified (confidently wrong, uncertain).

| Group | Session | K-cal | K-self | O-cal | O-self | M-loco |
|---|---|---|---|---|---|---|
| lenovo-tb128xu_stka_studio | Session A | 54/54 (0, 0) | 54/54 (0, 0) | no frames | no frames | 54/54 (0, 2) |
| lenovo-tb128xu_stka_studio-a | Session A2 | 54/54 (0, 0) | 54/54 (0, 1) | 54/54 (0, 0) | 54/54 (0, 0) | 53/54 (1, 6) |
| lenovo-tb128xu_stkb_studio-a | Session A2 | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 0) |
| lenovo-tb128xu_stla_studio | Session A | 54/54 (0, 1) | 54/54 (0, 3) | no frames | no frames | 54/54 (0, 2) |
| lenovo-tb128xu_stla_studio-a | Session A2 | 53/54 (1, 0) | 54/54 (0, 1) | 53/54 (1, 0) | 54/54 (0, 1) | 53/54 (1, 0) |
| lenovo-tb128xu_stlb_studio-a | Session A2 | 54/54 (0, 1) | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 1) |
| sm-s721u1_stka_studio | Session A | 54/54 (0, 0) | 54/54 (0, 0) | no frames | no frames | 53/54 (0, 7) |
| sm-s721u1_stka_studio-a | Session A2 | 53/54 (1, 0) | 53/54 (1, 0) | 54/54 (0, 0) | 54/54 (0, 0) | 52/54 (0, 7) |
| sm-s721u1_stkb_studio-a | Session A2 | 53/54 (1, 1) | 53/54 (1, 1) | 54/54 (0, 1) | 54/54 (0, 1) | 52/54 (0, 8) |
| sm-s721u1_stla_studio | Session A | 54/54 (0, 0) | 54/54 (0, 0) | no frames | no frames | 54/54 (0, 9) |
| sm-s721u1_stla_studio-a | Session A2 | 54/54 (0, 1) | 54/54 (0, 2) | 54/54 (0, 1) | 54/54 (0, 1) | 54/54 (0, 1) |
| sm-s721u1_stlb_studio-a | Session A2 | 54/54 (0, 1) | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 0) | 54/54 (0, 1) |

### Misreads

- **K-cal**: lenovo-tb128xu_stla_studio-a U[4] WHITE→BLUE (confident); sm-s721u1_stka_studio-a L[2] GREEN→WHITE (confident); sm-s721u1_stkb_studio-a L[2] RED→WHITE (confident).
- **K-self**: sm-s721u1_stka_studio-a L[2] GREEN→WHITE (confident); sm-s721u1_stkb_studio-a L[2] RED→WHITE (confident).
- **O-cal**: lenovo-tb128xu_stla_studio-a U[4] WHITE→BLUE (confident).
- **O-self**: none.
- **M-loco**: lenovo-tb128xu_stka_studio-a U[4] WHITE→BLUE (confident); lenovo-tb128xu_stla_studio-a U[4] WHITE→BLUE (confident); sm-s721u1_stka_studio U[4] WHITE→BLUE (uncertain); sm-s721u1_stka_studio-a R[0] BLUE→RED (uncertain); sm-s721u1_stka_studio-a R[3] BLUE→RED (uncertain); sm-s721u1_stkb_studio-a L[2] RED→WHITE (uncertain); sm-s721u1_stkb_studio-a B[3] ORANGE→RED (uncertain).

### Calibration sensitivity (bad white)

Every group's WHITE center replaced by L76.0 a20.0 b71.0 (the session A non-white patch); the other five centers unchanged. `K-self`, `O-self` and `M-loco` have no calibration step, so this case does not apply to them.

| Variant | Calibration | Classified | Accuracy | Confidently wrong | CW rate | Uncertain |
|---|---|---:|---:|---:|---:|---:|
| K-cal | recorded | 648 | 99.54% | 3 | 0.46% | 5 |
| K-cal | bad white | 648 | 84.10% | 48 | 7.41% | 84 |
| O-cal | recorded | 432 | 99.77% | 1 | 0.23% | 2 |
| O-cal | bad white | 432 | 83.80% | 26 | 6.02% | 58 |

Sweep: WHITE(t) = (1 − t)·recorded + t·bad, per record.

| t | K-cal accuracy | K-cal confidently wrong | K-cal uncertain | O-cal accuracy | O-cal confidently wrong | O-cal uncertain |
|---:|---:|---:|---:|---:|---:|---:|
| 0.00 | 99.54% | 3 | 5 | 99.77% | 1 | 2 |
| 0.25 | 99.38% | 3 | 9 | 99.77% | 1 | 4 |
| 0.50 | 97.07% | 9 | 25 | 97.69% | 3 | 10 |
| 0.75 | 93.67% | 29 | 42 | 95.14% | 17 | 31 |
| 1.00 | 84.10% | 48 | 84 | 83.80% | 26 | 58 |

### Logo centers (A2, WHITE-center face)

ΔE is to the mean of the same group's non-center WHITE-truth cells measured the same way (JSON samples for the JSON value, O cell means for the O cell mean, O histogram modes for the O mode). Readings: ok / misread color, with c = confident, u = uncertain.

| Group | Known logo | JSON center | ΔE | O cell mean | ΔE | O mode | ΔE | Mode share | K-cal | K-self | O-cal | O-self | M-loco |
|---|---|---|---:|---|---:|---|---:|---:|---|---|---|---|---|
| lenovo-tb128xu_stka_studio-a | yes | L41.8 a-12.0 b-23.7 | 59.5 | L72.5 a-10.4 b15.4 | 31.0 | L93.2 a-6.7 b-0.8 | 7.6 | 32% | ok c | ok c | ok c | ok c | BLUE c |
| lenovo-tb128xu_stkb_studio-a | no | L95.5 a-8.9 b5.1 | 1.6 | L95.2 a-7.9 b4.2 | 2.0 | L95.2 a-7.9 b4.2 | 2.0 | 100% | ok c | ok c | ok c | ok c | ok c |
| lenovo-tb128xu_stla_studio-a | yes | L43.6 a-3.3 b-38.7 | 54.7 | L67.9 a-5.6 b-19.2 | 24.4 | L47.6 a-6.9 b-32.7 | 47.6 | 41% | BLUE c | ok c | BLUE c | ok c | BLUE c |
| lenovo-tb128xu_stlb_studio-a | no | L60.5 a-2.2 b-0.8 | 25.7 | L83.5 a-1.3 b-0.7 | 2.8 | L89.5 a-1.3 b-0.6 | 3.5 | 73% | ok u | ok c | ok c | ok c | ok c |
| sm-s721u1_stka_studio-a | yes | L82.3 a-3.2 b-7.3 | 5.3 | L73.2 a-0.5 b3.3 | 14.5 | L86.9 a-1.8 b-3.8 | 4.2 | 63% | ok c | ok c | ok c | ok c | ok c |
| sm-s721u1_stkb_studio-a | no | L81.3 a-5.5 b1.5 | 2.2 | L81.7 a-4.6 b1.7 | 2.7 | L81.7 a-4.6 b1.7 | 1.4 | 100% | ok c | ok c | ok c | ok c | ok c |
| sm-s721u1_stla_studio-a | yes | L78.5 a-5.0 b-10.8 | 8.7 | L63.8 a-3.6 b-24.6 | 25.8 | L80.8 a-5.2 b-11.3 | 9.8 | 51% | ok c | ok c | ok c | ok c | ok c |
| sm-s721u1_stlb_studio-a | no | L81.2 a-3.4 b-4.0 | 5.8 | L75.4 a-2.2 b-2.5 | 2.1 | L80.9 a-2.2 b-2.5 | 5.6 | 75% | ok c | ok c | ok c | ok c | ok c |

`K-self` and `O-self` take the WHITE center from this very cell, so their reading of it is correct by construction; their cost shows on the other WHITE stickers (see the histogram test below).

### Center-cell histogram test (issue #153)

Plain center = histogram mode share ≥ 60%. 45 of 48 A2 centers pass (93.75%).

| Group | U | R | F | D | L | B |
|---|---:|---:|---:|---:|---:|---:|
| lenovo-tb128xu_stka_studio-a | **32% (logo)** | 100% | 100% | 100% | 100% | 100% |
| lenovo-tb128xu_stkb_studio-a | 100% | 100% | 100% | 100% | 100% | 100% |
| lenovo-tb128xu_stla_studio-a | **41% (logo)** | 100% | 100% | 100% | 100% | 100% |
| lenovo-tb128xu_stlb_studio-a | 73% | 100% | 100% | 100% | 100% | 100% |
| sm-s721u1_stka_studio-a | 63% (logo) | 100% | 100% | 100% | 100% | 100% |
| sm-s721u1_stkb_studio-a | 100% | 100% | 100% | 100% | 100% | 100% |
| sm-s721u1_stla_studio-a | **51% (logo)** | 100% | 100% | 100% | 100% | 100% |
| sm-s721u1_stlb_studio-a | 75% | 100% | 100% | 100% | 100% | 100% |

Failing centers (bold above): lenovo-tb128xu_stka_studio-a U (WHITE, 32%); lenovo-tb128xu_stla_studio-a U (WHITE, 41%); sm-s721u1_stla_studio-a U (WHITE, 51%).

Fully separates the known logo centers (`stka`, `stla` WHITE): **no** — 3 of 4 flagged, 0 false alarms on 44 other centers.

`O-self` calibrates WHITE from the center cell's mode. Known logo centers, against the group's plain WHITE modes (within the close-call margin = ΔE < 10):

| Group | Mode share | ΔE O mode to plain white | Within margin | O-self WHITE stickers correct | Confidently wrong | Uncertain |
|---|---:|---:|---|---:|---:|---:|
| lenovo-tb128xu_stka_studio-a | 32% | 7.6 | yes | 9/9 | 0 | 0 |
| lenovo-tb128xu_stla_studio-a | 41% | 47.6 | no | 9/9 | 0 | 0 |
| sm-s721u1_stka_studio-a | 63% | 4.2 | yes | 9/9 | 0 | 0 |
| sm-s721u1_stla_studio-a | 51% | 9.8 | yes | 9/9 | 0 | 0 |

### Release size

Added runtime bytes per approach. AAR rows: arm64-v8a `.so` uncompressed and compressed (as stored in the AAR), classes.jar, and the projection used against NFR-11 (arm64 `.so` uncompressed + 3 × classes.jar as a dex proxy).

| Approach | Runtime | arm64 .so uncompressed | arm64 .so compressed | classes.jar | Projected added |
|---|---|---:|---:|---:|---:|
| K (`K-cal`, `K-self`) | Kotlin in `:core:domain` | 0 | 0 | 0 | 0 |
| M (`M-loco`, Kotlin inference) | Kotlin + 504 weight bytes | 0 | 0 | 0 | 504 |
| O (`O-cal`, `O-self`) | `opencv-4.12.0.aar` | 24,757,992 | 9,746,373 | 435,072 | 26,063,208 |
| M via LiteRT (+ 504 weight bytes) | `litert-2.3.2.aar` | 5,629,696 | 2,337,938 | 25,390 | 5,705,866 |

- `opencv-4.12.0.aar` arm64-v8a libraries: `libc++_shared.so`, `libopencv_java4.so`.
- `litert-2.3.2.aar` arm64-v8a libraries: `libLiteRt.so`.

### Cross-checks

- Pure-Kotlin histogram port: `LabHistogram.peak` equals the OpenCV `calcHist` peak bin in 432 of 432 frame cells.
<!-- oq7-results:end -->

Hand-written snapshot, taken 2026-11-15 on this branch and not regenerated by the tool. Current release estimate (`./gradlew :app:checkReleaseSize`): **11,491,896 bytes** (APK 3,075,444 + 3 × dex 2,754,284 + data allowance 153,600) against the 30,000,000-byte budget. Projected estimates (current + arm64 `.so` uncompressed + 3 × classes.jar):

| Approach | Projected installed estimate | vs 30 MB |
|---|---:|---|
| K (Kotlin nearest-center, incl. a Kotlin histogram-mode cell reader) | 11,491,896 | 18.5 MB headroom |
| M, Kotlin inference (504 weight bytes) | 11,492,400 | 18.5 MB headroom |
| M via LiteRT (+ 504 weight bytes) | 17,198,266 | 12.8 MB headroom |
| O (OpenCV 4.12.0 AAR, arm64 only) | 37,555,104 | **over by 7.6 MB** |

## Recommendation

**Choose K: the Kotlin nearest-center rule, fed by a per-cell histogram mode and self-calibrated from the six face centers.** In other words, `O-self`'s measurement method without OpenCV. The cell reader (central 60% ROI, glare mask, 3-D Lab histogram, mode of the peak bin and its neighbors) is a few dozen lines of Kotlin. The spike's pure-Kotlin `LabHistogram` already finds the same peak bin as OpenCV `calcHist` in 432 of 432 frame cells. This reader was not run end to end in pure Kotlin, though: O-self's numbers used OpenCV for JPEG decoding and the Lab conversion, and only the peak bin was cross-checked. Story 10.1 must re-run the fixtures through the all-Kotlin reader before relying on these results. It belongs in `:core:domain` as pure logic, with the camera adapter converting YUV pixels to Lab, as the recorder already does. Keep the ≥ 10 ΔE close-call rule as Story 10.1's confidence threshold. Nothing in this data argues for retuning it.

Trade-offs against the alternatives:

- **Accuracy and confidently wrong (A2 only, 432 stickers, the one scope every variant shares):**
  - `O-self` read 432/432, with 0 confidently wrong and 3 uncertain.
  - `O-cal` read 431/432. Its one miss is the Lenovo `stla` logo center read as BLUE.
  - `K-self` read 430/432, with 2 confidently wrong. Both misreads are S24 FE `L[2]` center-pixel reads that the histogram mode gets right.
  - `K-cal` (today's rule on a single center pixel) read 429/432, with 3 confidently wrong.
  - `M-loco` read 426/432, with 2 confidently wrong (logo centers read BLUE). It needs labeled training data per cube family and still generalizes worse to a held-out cube than the 6-center self-calibration.
  - With session A added, `K-cal` reads 645/648, `K-self` 646/648 and `M-loco` 641/648. O variants have no session A frames.
  - The sample is too small to show ≤ 0.1% for any variant: 0 of 432 is consistent with up to about 0.69% at 95% (rule of three). Story 10.8's test matrix remains the gate.
- **Calibration robustness:** a single bad WHITE swatch drops `K-cal` to 84.10% (48 confidently wrong) and `O-cal` to 83.80% (26 confidently wrong). The damage starts by t = 0.5. Self-calibrated variants have no operator swatch to get wrong, which removes the failure mode session A hit. The trade is that the WHITE center comes from a logo cell.
- **Logo handling:**
  - The histogram mode makes logo centers usable. Three of the four logo modes sit within 10 ΔE of the group's plain white (7.6, 4.2, 9.8), where the single center pixel sat 5–60 ΔE away.
  - The exception is the Lenovo `stla` center, whose mode is the logo itself (47.6 ΔE, 41% share). `O-self` still read all 9 of that group's WHITE stickers correctly, but only because the plain whites still sit nearer that bluish-grey center than any other one. That is luck, not a robust margin.
  - The PO's histogram test (mode share ≥ 60%) flags 3 of the 4 logo centers with no false alarm on 44 plain centers, so it does not fully separate them: the S24 FE `stka` logo passes at 63% and is harmless there (4.2 ΔE).
  - Recommended guard for 10.1/10.4: when a center's mode share is < 60%, do not use it as a calibration center. Instead, prompt for a plain sticker (the 10.4 calibration flow), or calibrate that color from the stickers the other five centers classify. This guard is not measured here.
- **Effort:** K is the smallest change. It is a pure-Kotlin cell reader plus self-calibration in `:core:domain` under the ≥ 80% coverage gate, testable on the committed frames. O adds a native dependency, an Android-only API surface and JNI lifetime handling. M adds a training pipeline, labeled data per cube family, and (with LiteRT) a native runtime and model-conversion tooling.
- **Size:** K adds 0 runtime bytes (projected 11.49 MB). LiteRT would add about 5.7 MB (projected 17.2 MB, within budget). OpenCV's arm64 runtime alone adds about 26.1 MB (projected 37.6 MB), which breaks NFR-11's 30 MB unless a custom slim OpenCV build is made. That build was not measured.
- **Release-classpath consequence (Story 10.9 gate):**
  - Adopting K changes nothing. No new artifact enters any classpath, and `CameraClasspathPolicy` stays as is.
  - Adopting O or LiteRT-based M would put `org.opencv` or `com.google.ai.edge.litert` on the release runtime classpath whenever a camera flag is above OFF. The gate already forbids both groups when all camera flags are OFF and allows them otherwise, so its rule would not change. The dependency would, however, have to live only in the `app/src/camera/` source set (not `:core:domain`, which must stay pure Kotlin), and the size gate would then measure the native runtime.

Open for the PO: record the OQ-7 decision (PRD memlog, OQ-7 line, spine Quick Scan line). Session B should re-measure the K-with-histogram reader on live YUV frames, because this spike read JPEGs.
