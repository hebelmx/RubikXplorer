# Scan accuracy (Story 10.1)

This report runs the committed live fixtures through the product scan pipeline of OQ-7 decision K, all in Kotlin, with no OpenCV. The OQ-7 spike (`docs/spikes/oq-7-recognition-approach.md`) measured `O-self` at 432/432, but OpenCV did the JPEG decode, the Lab conversion and the histogram there. This is the re-run that the decision required.

**Result.** The all-Kotlin reader read all 432 A2 stickers correctly: 8 of 8 scans were `Full` with every sticker right, 0 were confidently wrong and 3 were uncertain. That matches the spike's `O-self` (432/432, 0 confidently wrong, 3 uncertain). Two qualifications apply. First, centers are correct by construction: the prompt fixes each center's color, so 48 of the 432 Kotlin-reader stickers and 72 of the 648 center-pixel stickers cannot be misread. Second, `sm-s721u1_stkb_studio-a`'s truth is not a valid cube (10 WHITE, 8 RED). Its 54/54 matches a truth that is itself wrong, and its read fails `CubeValidator`, so 7 of the 8 Kotlin-reader scans are both correct and valid. The logo guard flagged the same three WHITE logo centers as the spike's histogram test (Lenovo `stka`, Lenovo `stla`, S24 FE `stla`). For each, the derived WHITE center read all 8 non-center WHITE stickers correctly and confidently. The center-pixel input (the recorder's JSON samples, sessions A and A2) reads 646/648 with 2 confidently wrong. That is the spike's `K-self` result, reached through the product `SelfCalibration` and `ScanAssembler`. The sample is far too small to show the ≤ 0.1% confidently-wrong target. Story 10.8's test matrix stays the gate.

## Method

- **Pipeline (`:core:domain` main, `dev.cubentia.domain.recognition`):** `CellReader` (per-cell histogram mode, glare mask L ≥ 98, plain = mode share ≥ 60%), then `SelfCalibration.from` (no saved calibration; each color's center is the face whose prompt named it; a non-plain center is guarded and derived from the 8 non-center stickers farthest from the trusted centers), then `ScanAssembler.assemble`. The assembler uses `StickerClassifier`: nearest center, CONFIDENT only when the second-nearest center is ≥ 10 ΔE (CIE76) farther.
- **Kotlin reader input:** each A2 frame (`<FACE>.jpg`) is decoded with `javax.imageio`, converted from sRGB to CIELAB per pixel (D65, the recorder's formula), and cut by the recorder's grid (centered square of 80% of the short edge, 3×3, each cell's central 60%). Each cell goes through `CellReader`.
- **Center-pixel input:** the record's JSON samples, the recorder's YUV → RGB → Lab reading at each cell. These carry no mode share, so `centerPlain` is null and the logo guard never fires.
- **Pose:** each face gets `centerColor = truth[4]`, as a Guided Scan prompt would give it. The recorder never asked which side was up, so the up-neighbor color is recovered from truth. Per group, it is the per-face quarter turns under which the six truth faces assemble into a `CubeValidator`-valid cube. When no turns do, the turns are 0 and the group is flagged. The same pose is applied to the samples and to the truth, so it never favors the classifier.
- **Scoring:** every sticker of a placed face is compared with the truth assembled the same way. A scan is correct when it is `Full` and all 54 stickers are right. *Full and valid* means `CubeValidator` accepts the read. Regenerate with `./gradlew :core:domain:evaluateScanAccuracy`. On one machine and JDK, repeated runs give byte-identical output.

## Limitations

- **Small set:** 12 groups, 648 stickers (432 with frames), 4 cubes, two devices and one light (studio). One sticker is 0.15–0.23%, so 0 confidently wrong only means none were observed. By the rule of three, 0 of 432 is consistent with up to about 0.69%.
- **A2 truth is mostly prefilled:** the recorder prefilled each sticker from its own close-call-gated read, and the operator corrected misreads. A misread the operator missed would favor the center-pixel input.
- **JPEG, not YUV:** the Kotlin reader reads the recorder's downscaled JPEG through ImageIO, not live YUV frames. Session B must re-measure on the camera adapter's YUV → Lab path (Story 10.2).
- **Centers are free:** the prompt fixes each center's color, so the 6 centers of every scan (48 of 432 Kotlin-reader stickers, 72 of 648 center-pixel stickers) are correct by construction. Only their confidence is measured.
- **Pose recovered from truth:** the up-neighbor color is an evaluation stand-in for the 10.2 prompt. `sm-s721u1_stkb_studio-a`'s truth is not a valid cube (10 WHITE, 8 RED), so it uses quarter turns 0. Its stickers still score against that wrong truth, so its 54/54 Kotlin-reader read (also 10 WHITE, 8 RED) is a match to bad truth, and the read fails `CubeValidator`.
- **Session A has no Kotlin-reader row:** its schema-1 records carry no frames, only the recorder's center-pixel samples. Session A is therefore scored on the center-pixel input only.
- **Logo guard on three centers:** only the three A2 WHITE logo centers trip it, and only one guarded color per group is derived from stickers. Saved-calibration replacement (10.4) is unit-tested, not measured here. The Galaxy Tab S7 FE has no fixtures.

## Results

<!-- scan-accuracy:start -->
_Generated by `./gradlew :core:domain:evaluateScanAccuracy`; do not edit by hand._

### Data set

| Session | Groups | Faces | Stickers | Faces with a frame |
|---|---:|---:|---:|---:|
| Session A | 4 | 24 | 216 | 0 |
| Session A2 | 8 | 48 | 432 | 48 |

Pose recovered from truth (per-face quarter turns that make the six truth faces a valid cube):

- valid cube: 11 (`lenovo-tb128xu_stka_studio`, `lenovo-tb128xu_stka_studio-a`, `lenovo-tb128xu_stkb_studio-a`, `lenovo-tb128xu_stla_studio`, `lenovo-tb128xu_stla_studio-a`, `lenovo-tb128xu_stlb_studio-a`, `sm-s721u1_stka_studio`, `sm-s721u1_stka_studio-a`, `sm-s721u1_stla_studio`, `sm-s721u1_stla_studio-a`, `sm-s721u1_stlb_studio-a`)
- truth is not a valid cube: 1 (`sm-s721u1_stkb_studio-a`)

### Accuracy per device

A scan is a group's six faces. Per-scan accuracy = `Full` with all 54 stickers correct; Full and valid = `CubeValidator` accepts the read. Confidently wrong = misread and CONFIDENT (second-nearest center ≥ 10 ΔE farther), against the ≤ 0.1% target.

| Device | Input | Scans | Full | Per-scan accuracy | Full and valid | Stickers | Correct | Per-sticker accuracy | Confidently wrong | CW rate | Observed ≤ 0.1% | Uncertain |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|
| Lenovo Tab M10 Plus 3rd Gen (`Lenovo TB128XU`) | Kotlin reader | 4 | 4 | 4/4 (100.00%) | 4 | 216 | 216 | 100.00% | 0 | 0.00% | yes | 1 |
| Lenovo Tab M10 Plus 3rd Gen (`Lenovo TB128XU`) | Center pixel | 6 | 6 | 6/6 (100.00%) | 6 | 324 | 324 | 100.00% | 0 | 0.00% | yes | 5 |
| Galaxy S24 FE (`SM-S721U1`) | Kotlin reader | 4 | 4 | 4/4 (100.00%) | 3 | 216 | 216 | 100.00% | 0 | 0.00% | yes | 2 |
| Galaxy S24 FE (`SM-S721U1`) | Center pixel | 6 | 6 | 4/6 (66.67%) | 4 | 324 | 322 | 99.38% | 2 | 0.62% | no | 3 |
| Galaxy Tab S7 FE (`SM-T738U`) | — | no fixtures | — | — | — | — | — | — | — | — | — | — |
| **Total** | Kotlin reader | 8 | 8 | 8/8 (100.00%) | 7 | 432 | 432 | 100.00% | 0 | 0.00% | yes | 3 |
| **Total** | Center pixel | 12 | 12 | 10/12 (83.33%) | 10 | 648 | 646 | 99.69% | 2 | 0.31% | no | 8 |

Zero confidently wrong observed does not show the ≤ 0.1% target is met: by the rule of three, 0 of N is consistent with a true rate up to about 3/N at 95% (Kotlin reader: 0.69% for 432 stickers, Center pixel: 0.46% for 648 stickers).

### Per group

| Group | Session | Truth | Input | Calibration | Result | Correct | Confidently wrong | Uncertain | Valid |
|---|---|---|---|---|---|---:|---:|---:|---|
| lenovo-tb128xu_stka_studio | Session A | valid cube | Kotlin reader | — | no frames | — | — | — | — |
| lenovo-tb128xu_stka_studio | Session A | valid cube | Center pixel | self | Full | 54/54 | 0 | 0 | yes |
| lenovo-tb128xu_stka_studio-a | Session A2 | valid cube | Kotlin reader | self, WHITE guarded | Full | 54/54 | 0 | 0 | yes |
| lenovo-tb128xu_stka_studio-a | Session A2 | valid cube | Center pixel | self | Full | 54/54 | 0 | 1 | yes |
| lenovo-tb128xu_stkb_studio-a | Session A2 | valid cube | Kotlin reader | self | Full | 54/54 | 0 | 0 | yes |
| lenovo-tb128xu_stkb_studio-a | Session A2 | valid cube | Center pixel | self | Full | 54/54 | 0 | 0 | yes |
| lenovo-tb128xu_stla_studio | Session A | valid cube | Kotlin reader | — | no frames | — | — | — | — |
| lenovo-tb128xu_stla_studio | Session A | valid cube | Center pixel | self | Full | 54/54 | 0 | 3 | yes |
| lenovo-tb128xu_stla_studio-a | Session A2 | valid cube | Kotlin reader | self, WHITE guarded | Full | 54/54 | 0 | 1 | yes |
| lenovo-tb128xu_stla_studio-a | Session A2 | valid cube | Center pixel | self | Full | 54/54 | 0 | 1 | yes |
| lenovo-tb128xu_stlb_studio-a | Session A2 | valid cube | Kotlin reader | self | Full | 54/54 | 0 | 0 | yes |
| lenovo-tb128xu_stlb_studio-a | Session A2 | valid cube | Center pixel | self | Full | 54/54 | 0 | 0 | yes |
| sm-s721u1_stka_studio | Session A | valid cube | Kotlin reader | — | no frames | — | — | — | — |
| sm-s721u1_stka_studio | Session A | valid cube | Center pixel | self | Full | 54/54 | 0 | 0 | yes |
| sm-s721u1_stka_studio-a | Session A2 | valid cube | Kotlin reader | self | Full | 54/54 | 0 | 0 | yes |
| sm-s721u1_stka_studio-a | Session A2 | valid cube | Center pixel | self | Full | 53/54 | 1 | 0 | no |
| sm-s721u1_stkb_studio-a | Session A2 | truth is not a valid cube | Kotlin reader | self | Full | 54/54 | 0 | 1 | no |
| sm-s721u1_stkb_studio-a | Session A2 | truth is not a valid cube | Center pixel | self | Full | 53/54 | 1 | 1 | no |
| sm-s721u1_stla_studio | Session A | valid cube | Kotlin reader | — | no frames | — | — | — | — |
| sm-s721u1_stla_studio | Session A | valid cube | Center pixel | self | Full | 54/54 | 0 | 0 | yes |
| sm-s721u1_stla_studio-a | Session A2 | valid cube | Kotlin reader | self, WHITE guarded | Full | 54/54 | 0 | 1 | yes |
| sm-s721u1_stla_studio-a | Session A2 | valid cube | Center pixel | self | Full | 54/54 | 0 | 2 | yes |
| sm-s721u1_stlb_studio-a | Session A2 | valid cube | Kotlin reader | self | Full | 54/54 | 0 | 0 | yes |
| sm-s721u1_stlb_studio-a | Session A2 | valid cube | Center pixel | self | Full | 54/54 | 0 | 0 | yes |

### Misreads

- **Kotlin reader**: none.
- **Center pixel**: sm-s721u1_stka_studio-a R[0] GREEN→WHITE (confident); sm-s721u1_stkb_studio-a R[2] RED→WHITE (confident).

Indices are net positions (row-major per the `Facelets` net), after the pose turned each face.

### Logo-center guard

A center whose histogram mode holds < 60% of its cell is not used as a calibration center. With no saved calibration and one guarded color, that color is the mean of the 8 non-center stickers farthest from their nearest trusted center. Center-pixel samples carry no mode share, so the guard applies to the Kotlin reader only.

| Group | Input | Guarded center | Replaced by | Non-center stickers of that color correct | Confidently wrong | Uncertain |
|---|---|---|---|---:|---:|---:|
| lenovo-tb128xu_stka_studio-a | Kotlin reader | WHITE | 8 farthest stickers | 8/8 | 0 | 0 |
| lenovo-tb128xu_stla_studio-a | Kotlin reader | WHITE | 8 farthest stickers | 8/8 | 0 | 0 |
| sm-s721u1_stla_studio-a | Kotlin reader | WHITE | 8 farthest stickers | 8/8 | 0 | 0 |
<!-- scan-accuracy:end -->
