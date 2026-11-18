# OQ-8 recognition test matrix

Device session C measures Guided 6-face Scan, Quick Scan, Quick Check, Case recognition and Placement against the §4.8 targets. Use both cubes in each of four lighting conditions (daylight, warm indoor, cool indoor and low light) on three physical devices. The Lenovo Tab M10 Plus 3rd Gen (`lenovo-tb128xu`) and Galaxy S24 FE (`sm-s721u1`) contribute verdicts. The third device is reported for comparison.

## Capture and score

1. Use the main debug app and the real entry point for the feature. Name each cell `<device>_<cube>_<light>_<feature>`, using hyphens within device and cube tokens. Use `daylight`, `warm`, `cool` or `low` and `guided`, `quickscan`, `quickcheck`, `recognize` or `placement`. Use consistent cube tokens for the stickered and stickerless cubes.
2. Create `Fixtures/matrix`. Before each cell, run `adb logcat -c`, then run `adb logcat -s CubentiaScanDiag > Fixtures/matrix/<cell>.log`. Stop the capture after the cell. Keep one log per cell and retain failed, cancelled and editor-fallback runs. A scan used to enter Placement can also emit a Guided record. The evaluator reads the feature named by the file.
3. Record the scramble and independent ground truth for each run in `Fixtures/matrix/truth.csv` in the matching feature's run order. A run is one logged `<feature> run` line: a Placement scan cancelled or sent to the editor before a suggestion logs no `placement run` line (its `guidedScan run` line is in the same file), and a Quick Check result superseded by a newer check logs none, so number truth rows by logged runs, not by attempts. Use one-based indices or `*` for a cell default. A numbered row overrides the default. Lines beginning with `#` are comments. Use cube Notation for Guided and Quick Scan (an empty scramble is solved), `match` or `mismatch` for Quick Check, a Case ID, `LL_SOLVED` or `NOT_AT_LL` for Recognize, and a stage ID or `SOLVED` for Placement.

Example CSV rows for format only

```csv
cell,run,truth
sm-s721u1_stickered_warm_guided,1,R U R' U'
sm-s721u1_stickered_warm_quickcheck,*,match
sm-s721u1_stickered_warm_recognize,1,case-oll-27
sm-s721u1_stickered_warm_placement,1,stage-02-first-cross
```

Timing: Guided and Quick Scan `elapsedMs` runs from the scan's first analyzed camera frame (after permission and camera start-up) to Accept, Close or *Enter colors*; Quick Check from its first frame to the capture that produced its result; Recognize from the final corner capture to the result; Placement from the accepted scan to the suggestion (the Learner's choice is logged but not timed). For the Guided "repeat user" target, the operator does one untimed warm-up scan per device before the cell's logged runs. Runs on a non-gating device show their values with the verdict *reported only*.

Run `./gradlew :core:domain:evaluateMatrix` from the repository root. For different inputs, use `./gradlew :core:domain:evaluateMatrix --args="<logs dir> <truth file> [report]"`. The report path is optional. A third argument selects an existing report with the same markers.

## Targets

| Flow | §4.8 targets | Additional measurements |
| --- | --- | --- |
| Guided 6-face Scan | ≥98% accepted and valid with ≤3 corrections, accepted median <45 s, ≥99.5% sticker accuracy in warm and cool indoor light, ≤0.1% confidently wrong | Sticker accuracy in every light, initial-read accuracy, accepted but wrong |
| Quick Scan | ≥95% accepted and valid with ≤3 corrections, accepted median <15 s, ≤0.1% confidently wrong | Sticker accuracy, initial-read accuracy, accepted but wrong |
| Quick Check | Zero false VERIFIED, ≥98% of true matches confirmed, ≤2 captures on every run, ≤0.1% confidently wrong stickers on Partial true matches | — |
| Case recognition | ≤1% wrong single Case, every result <1,000 ms | SEVERAL results missing the true Case |
| Placement | — | Suggestions matching truth, Acceptance differing from the suggestion (including leaving or rescanning) |

Verdicts use the gating devices and the applicable lighting. Sticker rates exclude the fixed centers. Accepted-sticker accuracy uses the final accepted state, while initial-read accuracy counts uncertain stickers as unread. Missing truth and inapplicable observations have no rate. An em dash marks a metric reported without a target. For zero observed events, the report gives the approximate 95% upper bound of 3/n, capped at 100%.

<!-- recognition-matrix:start -->

### Per cell

| Scope | Feature | Runs | Without truth | Metric | Result | Verdict |
| --- | --- | ---: | ---: | --- | --- | --- |
| lenovo-tb128xu_stka_warm_guided | guided | 4 | 2 | Valid with ≤ 3 corrections | 3/4 (75.000%) | MISSED |
| lenovo-tb128xu_stka_warm_guided | guided | 4 | 2 | Accepted median time | 76934.0 ms (3/3 timed) | MISSED |
| lenovo-tb128xu_stka_warm_guided | guided | 4 | 2 | Sticker accuracy | 96/96 (100.000%) | MET |
| lenovo-tb128xu_stka_warm_guided | guided | 4 | 2 | Initial-read accuracy | 90/96 (93.750%) | — |
| lenovo-tb128xu_stka_warm_guided | guided | 4 | 2 | Confidently wrong | 0/96 (0.000%); 95% upper bound ≈ 3.125% | MET |
| lenovo-tb128xu_stka_warm_guided | guided | 4 | 2 | Accepted but wrong | 0/2 (0.000%); 95% upper bound ≈ 100.000% | — |
| lenovo-tb128xu_stka_warm_quickscan | quickscan | 1 | 0 | Valid with ≤ 3 corrections | 0/1 (0.000%); 95% upper bound ≈ 100.000% | MISSED |
| lenovo-tb128xu_stka_warm_quickscan | quickscan | 1 | 0 | Accepted median time | — (0/0 timed) | NO DATA |
| lenovo-tb128xu_stka_warm_quickscan | quickscan | 1 | 0 | Sticker accuracy | no truth or no eligible observations (n=0) | — |
| lenovo-tb128xu_stka_warm_quickscan | quickscan | 1 | 0 | Initial-read accuracy | 41/48 (85.417%) | — |
| lenovo-tb128xu_stka_warm_quickscan | quickscan | 1 | 0 | Confidently wrong | 2/48 (4.167%) | MISSED |
| lenovo-tb128xu_stka_warm_quickscan | quickscan | 1 | 0 | Accepted but wrong | no truth or no eligible observations (n=0) | — |
| lenovo-tb128xu_stla_warm_guided | guided | 1 | 0 | Valid with ≤ 3 corrections | 1/1 (100.000%) | MET |
| lenovo-tb128xu_stla_warm_guided | guided | 1 | 0 | Accepted median time | 23502.0 ms (1/1 timed) | MET |
| lenovo-tb128xu_stla_warm_guided | guided | 1 | 0 | Sticker accuracy | 48/48 (100.000%) | MET |
| lenovo-tb128xu_stla_warm_guided | guided | 1 | 0 | Initial-read accuracy | 48/48 (100.000%) | — |
| lenovo-tb128xu_stla_warm_guided | guided | 1 | 0 | Confidently wrong | 0/48 (0.000%); 95% upper bound ≈ 6.250% | MET |
| lenovo-tb128xu_stla_warm_guided | guided | 1 | 0 | Accepted but wrong | 0/1 (0.000%); 95% upper bound ≈ 100.000% | — |
| sm-s721u1_stka_warm_guided | guided | 1 | 0 | Valid with ≤ 3 corrections | 1/1 (100.000%) | MET |
| sm-s721u1_stka_warm_guided | guided | 1 | 0 | Accepted median time | 31308.0 ms (1/1 timed) | MET |
| sm-s721u1_stka_warm_guided | guided | 1 | 0 | Sticker accuracy | 48/48 (100.000%) | MET |
| sm-s721u1_stka_warm_guided | guided | 1 | 0 | Initial-read accuracy | 48/48 (100.000%) | — |
| sm-s721u1_stka_warm_guided | guided | 1 | 0 | Confidently wrong | 0/48 (0.000%); 95% upper bound ≈ 6.250% | MET |
| sm-s721u1_stka_warm_guided | guided | 1 | 0 | Accepted but wrong | 0/1 (0.000%); 95% upper bound ≈ 100.000% | — |
| sm-s721u1_stka_warm_quickcheck | quickcheck | 1 | 0 | False VERIFIED | 0/1 (0.000%); 95% upper bound ≈ 100.000% | MET |
| sm-s721u1_stka_warm_quickcheck | quickcheck | 1 | 0 | Match confirmed | 1/1 (100.000%) | MET |
| sm-s721u1_stka_warm_quickcheck | quickcheck | 1 | 0 | Captures | max 6 (n=1/1) | MISSED |
| sm-s721u1_stka_warm_quickcheck | quickcheck | 1 | 0 | Confidently wrong (Partial matches) | no truth or no eligible observations (n=0) | NO DATA |
| sm-s721u1_stla_warm_guided | guided | 3 | 2 | Valid with ≤ 3 corrections | 2/3 (66.667%) | MISSED |
| sm-s721u1_stla_warm_guided | guided | 3 | 2 | Accepted median time | 21277.0 ms (2/2 timed) | MET |
| sm-s721u1_stla_warm_guided | guided | 3 | 2 | Sticker accuracy | 48/48 (100.000%) | MET |
| sm-s721u1_stla_warm_guided | guided | 3 | 2 | Initial-read accuracy | 48/48 (100.000%) | — |
| sm-s721u1_stla_warm_guided | guided | 3 | 2 | Confidently wrong | 0/48 (0.000%); 95% upper bound ≈ 6.250% | MET |
| sm-s721u1_stla_warm_guided | guided | 3 | 2 | Accepted but wrong | 0/1 (0.000%); 95% upper bound ≈ 100.000% | — |
| sm-s721u1_stla_warm_quickcheck | quickcheck | 4 | 0 | False VERIFIED | 0/4 (0.000%); 95% upper bound ≈ 75.000% | MET |
| sm-s721u1_stla_warm_quickcheck | quickcheck | 4 | 0 | Match confirmed | 2/2 (100.000%) | MET |
| sm-s721u1_stla_warm_quickcheck | quickcheck | 4 | 0 | Captures | max 10 (n=4/4) | MISSED |
| sm-s721u1_stla_warm_quickcheck | quickcheck | 4 | 0 | Confidently wrong (Partial matches) | no truth or no eligible observations (n=0) | NO DATA |

### Per device

| Scope | Feature | Runs | Without truth | Metric | Result | Verdict |
| --- | --- | ---: | ---: | --- | --- | --- |
| lenovo-tb128xu | guided | 5 | 2 | Valid with ≤ 3 corrections | 4/5 (80.000%) | MISSED |
| lenovo-tb128xu | guided | 5 | 2 | Accepted median time | 74621.0 ms (4/4 timed) | MISSED |
| lenovo-tb128xu | guided | 5 | 2 | Sticker accuracy | 144/144 (100.000%) | MET |
| lenovo-tb128xu | guided | 5 | 2 | Initial-read accuracy | 138/144 (95.833%) | — |
| lenovo-tb128xu | guided | 5 | 2 | Confidently wrong | 0/144 (0.000%); 95% upper bound ≈ 2.083% | MET |
| lenovo-tb128xu | guided | 5 | 2 | Accepted but wrong | 0/3 (0.000%); 95% upper bound ≈ 100.000% | — |
| lenovo-tb128xu | quickscan | 1 | 0 | Valid with ≤ 3 corrections | 0/1 (0.000%); 95% upper bound ≈ 100.000% | MISSED |
| lenovo-tb128xu | quickscan | 1 | 0 | Accepted median time | — (0/0 timed) | NO DATA |
| lenovo-tb128xu | quickscan | 1 | 0 | Sticker accuracy | no truth or no eligible observations (n=0) | — |
| lenovo-tb128xu | quickscan | 1 | 0 | Initial-read accuracy | 41/48 (85.417%) | — |
| lenovo-tb128xu | quickscan | 1 | 0 | Confidently wrong | 2/48 (4.167%) | MISSED |
| lenovo-tb128xu | quickscan | 1 | 0 | Accepted but wrong | no truth or no eligible observations (n=0) | — |
| sm-s721u1 | guided | 4 | 2 | Valid with ≤ 3 corrections | 3/4 (75.000%) | MISSED |
| sm-s721u1 | guided | 4 | 2 | Accepted median time | 22199.0 ms (3/3 timed) | MET |
| sm-s721u1 | guided | 4 | 2 | Sticker accuracy | 96/96 (100.000%) | MET |
| sm-s721u1 | guided | 4 | 2 | Initial-read accuracy | 96/96 (100.000%) | — |
| sm-s721u1 | guided | 4 | 2 | Confidently wrong | 0/96 (0.000%); 95% upper bound ≈ 3.125% | MET |
| sm-s721u1 | guided | 4 | 2 | Accepted but wrong | 0/2 (0.000%); 95% upper bound ≈ 100.000% | — |
| sm-s721u1 | quickcheck | 5 | 0 | False VERIFIED | 0/5 (0.000%); 95% upper bound ≈ 60.000% | MET |
| sm-s721u1 | quickcheck | 5 | 0 | Match confirmed | 3/3 (100.000%) | MET |
| sm-s721u1 | quickcheck | 5 | 0 | Captures | max 10 (n=5/5) | MISSED |
| sm-s721u1 | quickcheck | 5 | 0 | Confidently wrong (Partial matches) | no truth or no eligible observations (n=0) | NO DATA |

### Matrix coverage

- guided: 4/24 cells present. Devices lenovo-tb128xu, sm-s721u1. Cubes stka, stla. Lights warm.
- quickscan: 1/24 cells present. Devices lenovo-tb128xu. Cubes stka. Lights warm.
- quickcheck: 2/24 cells present. Devices sm-s721u1. Cubes stka, stla. Lights warm.
- recognize: 0/24 cells present. Devices —. Cubes —. Lights —.
- placement: 0/24 cells present. Devices —. Cubes —. Lights —.

### Per feature

| Scope | Feature | Runs | Without truth | Metric | Result | Verdict |
| --- | --- | ---: | ---: | --- | --- | --- |
| All devices | guided | 9 | 4 | Valid with ≤ 3 corrections | 7/9 (77.778%) | MISSED |
| All devices | guided | 9 | 4 | Accepted median time | 31308.0 ms (7/7 timed) | MET |
| All devices | guided | 9 | 4 | Sticker accuracy | 240/240 (100.000%) | MET |
| All devices | guided | 9 | 4 | Initial-read accuracy | 234/240 (97.500%) | — |
| All devices | guided | 9 | 4 | Confidently wrong | 0/240 (0.000%); 95% upper bound ≈ 1.250% | MET |
| All devices | guided | 9 | 4 | Accepted but wrong | 0/5 (0.000%); 95% upper bound ≈ 60.000% | — |
| All devices | quickscan | 1 | 0 | Valid with ≤ 3 corrections | 0/1 (0.000%); 95% upper bound ≈ 100.000% | MISSED |
| All devices | quickscan | 1 | 0 | Accepted median time | — (0/0 timed) | NO DATA |
| All devices | quickscan | 1 | 0 | Sticker accuracy | no truth or no eligible observations (n=0) | — |
| All devices | quickscan | 1 | 0 | Initial-read accuracy | 41/48 (85.417%) | — |
| All devices | quickscan | 1 | 0 | Confidently wrong | 2/48 (4.167%) | MISSED |
| All devices | quickscan | 1 | 0 | Accepted but wrong | no truth or no eligible observations (n=0) | — |
| All devices | quickcheck | 5 | 0 | False VERIFIED | 0/5 (0.000%); 95% upper bound ≈ 60.000% | MET |
| All devices | quickcheck | 5 | 0 | Match confirmed | 3/3 (100.000%) | MET |
| All devices | quickcheck | 5 | 0 | Captures | max 10 (n=5/5) | MISSED |
| All devices | quickcheck | 5 | 0 | Confidently wrong (Partial matches) | no truth or no eligible observations (n=0) | NO DATA |
| All devices | recognize | 0 | 0 | Wrong single Case | no truth or no eligible observations (n=0) | NO DATA |
| All devices | recognize | 0 | 0 | SEVERAL missing truth candidate | no truth or no eligible observations (n=0) | NO DATA |
| All devices | recognize | 0 | 0 | Result time | n=0/0 | NO DATA |
| All devices | recognize | 0 | 0 | Outcome matches truth | no truth or no eligible observations (n=0) | NO DATA |
| All devices | placement | 0 | 0 | Suggestion matches truth | no truth or no eligible observations (n=0) | NO DATA |
| All devices | placement | 0 | 0 | Accepted differs from suggestion | n=0 | NO DATA |

### Skipped files

None.

### Malformed lines

None.

<!-- recognition-matrix:end -->

## Story 10.6 residual risk

`QuickCheckScenarioTest.sequences that leave the observed faces unchanged` reported “Observed-face-preserving sequences: 44 of 123500; residual aliases of a match: 1”. The seeded JVM scenario found one alias among all 123,500 sequences (0.00081%), or 1 of 44 among sequences that changed the cube while preserving the observed faces. The alias was `R U2 R'` with plan faces {B, D}: a match outside the candidate model (length > 2) that the Partial check reports as VERIFIED. Regenerated on 2026-11-18 with `./gradlew :core:domain:test --tests '*MatrixEvaluationTest' --tests '*QuickScanMeasurementTest' --tests '*QuickCheckScenarioTest'`. This is a scenario result with a fixed seed and sequence depth. [QuickCheckScenarioTest](../../core/domain/src/test/kotlin/dev/cubentia/domain/tutor/QuickCheckScenarioTest.kt)

Recommendation (PO, 2026-11-18): accepted for release. The alias needs a constructed face-preserving sequence and only affects partial checks; the scenario test stays as the guard and is re-run whenever the Quick Check rules change.

## Fixture accuracy

The product Kotlin pipeline scored 432/432 sticker reads on the A2 fixtures, with zero confidently wrong and three uncertain. Seven of eight reads were both correct and valid because one fixture's truth is invalid. The center-pixel input scored 646/648 with two confidently wrong. The OQ-4 prototype live-baseline block reports 645/648 overall and 237/240 for independently entered truth. These fixture samples cover studio lighting and the published counts include fixed centers. [Product pipeline accuracy](../scan-accuracy.md) · [OQ-4 live baseline](oq-4-guided-scan.md#live-baseline)

## Release recommendations

PO decisions from device session C (2026-11-18), limited to warm indoor light, Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE, and cubes stka and stla.

| Flow | OFF / BETA / GA |
| --- | --- |
| Guided 6-face Scan | **GA** — 240/240 accepted stickers correct, 0 confidently wrong, 7/7 accepted scans valid. |
| Quick Scan | **OFF** — 0/1 valid. |
| Quick Check | Follows `CAMERA_SCAN` (**GA**) — 3/3 matching cubes VERIFIED (full checks), 0 false VERIFIED. |
| Placement | **BETA** — runs on the Guided Scan. Manual fallback verified end to end on the S24 FE. No scanned end-to-end Placement run yet. |
| Case recognition | **OFF** — not measured. Shares the Quick Scan corner reader and guide and is blocked by the same issues. |

- Guided 6-face Scan took 20–31 s on the S24 FE. The Lenovo took 23.5 s on stla and 72–83 s on stka. First runs include calibration.
- The Quick Scan isometric corner guide cannot fit a real perspective view (#195), a re-asked face merged rotated 180° (#194), detection fails on dark glossy backgrounds (#196), and there is no capture feedback (#197). Try a Kotlin-only perspective-correct guide and unwarp, then have the PO test it hands-on to decide between staying in Kotlin, OpenCV, or a future small model. Measure systematically after that fix round.
- A partial Quick Check mismatch reported LIKELY and offered **Scan all 6 faces** as designed. A full scan after a single wrong move on the highly symmetric checkerboard stayed LIKELY, consistent with several candidate moves. Re-check on an ordinary scramble next session.

## Manual fallback

- [x] Guided 6-face Scan — verified on Galaxy S24 FE 2026-11-18. **Enter colors instead** → editor → **Accept** reached Solve with a correct plan (checkerboard, `U2 D2 R2 L2 F2 B2`). Entry takes ~2 taps per sticker (#204).
- [x] Quick Scan — verified on Lenovo 2026-11-18. An invalid result after rescans handed off to **Enter colors** (logged `FELL_BACK_TO_EDITOR`). Feature ships OFF.
- [x] Quick Check — verified on Galaxy S24 FE. A partial mismatch returned LIKELY with **Scan all 6 faces**, which opened the full scan. **Use my cube as it is now** was offered.
- [x] Placement — verified on Galaxy S24 FE. Welcome → **Start from my cube** → **Enter colors instead** → solved cube → “Your cube is solved — scramble it and scan again, or start Stage 1.” with **Scan again** and **Start Stage 1**. Outcome screen theme tracked in #205.
- [ ] Case recognition — not verified on device. Feature ships OFF.

Session issues: #194–#205.
