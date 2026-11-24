---
title: 'GH-323 Scan review: likely misreads suggest only close colours (proximity rule)'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: 'fb046d7fd32a10b90829d27b84d445f042670755'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-gh-174-point-at-misread-stickers.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** On *Review your scan*, when the colour counts are off, `SuspectRanking` always names up to 3 over-count stickers as "This sticker may be {color} — check it.", even when every candidate reads nowhere near the missing colour (release device session 2026-11-23: after painting Up 3 orange → blue, and Up 9 white → blue, the genuine blue Up 2 was suggested as "may be orange" / "may be white"). The PO decided (2026-11-20, value 2026-11-24) that clearly different colours are never suggested.

**Approach:** Add a relative proximity rule in `SuspectRanking` (Story 10.3 amendment for #323, EXPERIENCE.md *Invalid entered state* "Likely misreads"): a candidate qualifies for an under-counted colour only when `ΔE(lab, center[under]) − ΔE(lab, center[readColor]) ≤ SUSPECT_MARGIN_DELTA_E` (20.0, CIE76; 2 × `StickerClassifier.CLOSE_CALL_DELTA_E`). It is suggested as the nearest under-counted colour it qualifies for; the list holds at most 3 qualifying stickers ordered by that margin (smallest first, then sticker index) and is never padded. With none qualifying the list is empty, so the existing code already shows the plain count line with no suggestion and no count-suspect marker.

## Boundaries & Constraints

**Always:** Pure Kotlin in `:core:domain`. Candidates stay as today: scanned, unedited (Lab non-null), non-centre stickers of an over-counted colour; under-counted colours need a calibration centre. Document `SUSPECT_MARGIN_DELTA_E = 20.0` in KDoc (PO 2026-11-24, relative margin, CIE76) as a public `const` on `SuspectRanking`. Piece problems (PieceCheck outlines, numbered piece markers, face-tab dots), the count line and the disabled *Accept* are unchanged. Existing tests whose expectations relied on padding are updated to the new rule (they assert the old, now-wrong behaviour); never weaken unrelated assertions.

**Never:** No change to the Manual Cube Editor's markers, `CubeValidator`, `PieceCheck`, `StickerClassifier`, `ScanAssembler`, content strings, or the screen's UI mechanism. Do not change the constant's value: if the #174 replay (`orangeishYellow` Lab(77, 15, 74), margin ≈ 33.5 − 22.4 = 11.2) does not qualify, stop and report it. No edits under `_bmad-output/planning-artifacts`, `_bmad/`, `.claude/`.

## I/O & Edge-Case Matrix

Centres are the test fixtures' `LABS` (W 90,0,0; Y 85,-5,80; G 55,-60,40; B 40,10,-50; R 45,65,45; O 65,45,65). Margin = ΔE to under centre − ΔE to read centre.

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Release case A | a valid cube in which Up 2 (index 1) reads blue and Up 3 (index 2) orange, all stickers measuring their `LABS` value; Learner paints index 2 blue (Lab dropped): blue 10, orange 8 | `suspects` empty (each blue's margin ΔE(B,O) ≈ 122.8 > 20); no suspect names index 1; count problem has `positions = piece = []`; the impossible corner holding index 2 is still a problem and outlined | — |
| Release case B | same cube with Up 9 (index 8) white painted blue: blue 10, white 8 | `suspects` empty (ΔE(B,W) ≈ 71.4); counts problem plain; impossible corner still outlined | — |
| #174 replay | `issueSamples()` (10 Y, 8 O; one yellow measures `orangeishYellow`) | exactly `[Suspect(misread, ORANGE)]` (margin ≈ 11.2); the other yellows (margin ΔE(Y,O) ≈ 55.9) are not listed; it is still pre-selected | if it does not qualify: stop, do not change the constant |
| Two under-counted | +1 Y (38 at Lab 70,30,70), +1 R (46 at Lab 42,20,-30), −1 O, −1 B | `[Suspect(46, BLUE), Suspect(38, ORANGE)]` (margins ≈ −65.1, −22.8); exact reds (margin ≈ 34.6 to O) and yellows not listed | — |
| Qualifies for two under colours | a candidate within 20 of both under centres | suggested as the nearer under colour | — |
| Boundary | margin exactly 20.0 | qualifies (≤) | — |
| Nothing qualifies, counts off | e.g. the old tie fixture (every yellow exactly yellow, 10 Y / 8 O) | `suspects` empty; count line plain, no `review:suggestion`, no count-suspect outline; pre-selection falls back to #199's first linked problem / null | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SuspectRanking.kt:22-55` -- `rank`: today scores min ΔE to any under centre, sorts, `take(3)`. Change: per candidate compute `own = deltaE(lab, centers[stickers[index]])` (read colour's centre; if missing, the candidate cannot qualify), keep under colours with `deltaE − own ≤ SUSPECT_MARGIN_DELTA_E`, pick min; sort by margin then index; take 3. Update the KDoc.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/StickerClassifier.kt:32` -- `CLOSE_CALL_DELTA_E = 10.0`, `deltaE` (CIE76); reuse, do not edit.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt:34-63,111-123` -- `suspects`, count problem positions, `paint`/pre-selection all derive from `rank`; no change needed.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt:538,591-599,701` -- suggestion and "suspects" reason appear only when the count problem has positions; no change needed.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeState.kt:24,67` -- `apply(moves)` to build a valid scrambled cube for the release cases (search a short sequence where index 1 is BLUE, index 2 ORANGE and index 8 WHITE, or use two cubes).
- Tests to update/add: `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/SuspectRankingTest.kt` (issue replay expects 1 suspect; tie test, edited test, corrected test, multi-colour test and painting-last-blank test rely on padding `27, 28, 29` / `9`); `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt:441-470,1230-1243` (`suspectReview` now yields `[50, 39]`; top suggestion "may be blue"; TalkBack list order); `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt:35-67` (38 still qualifies; check). Grep `suspects` in `core/domain/src/test` and `app/src/test*` for any other padding-dependent expectation.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SuspectRanking.kt` -- add `const val SUSPECT_MARGIN_DELTA_E = 20.0` with KDoc; implement the qualifying filter, nearest qualifying colour, margin ordering, no padding -- the #323 rule.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/SuspectRankingTest.kt` -- add tests for every matrix row (release A and B through `ScanReview` with evidence, then `paint`, asserting no suspect, plain count problem and the corner problem outlined; #174 replay single suspect; two under-counted ordering; nearest of two qualifying; boundary 20.0; nothing qualifies → plain count line and pre-selection fallback); update padding-dependent expectations; comment each case's margin -- coverage.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- update the #174 suspect tests to the new order/count; add one Robolectric test: counts off with no qualifying candidate → count line unnumbered, no `review:suggestion`, piece problem still outlined -- screen surface.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt` -- adjust only if it breaks.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- change only the `status` of action item `issue-174-deferred-suspect-distance-threshold` from `open` to `done` -- closes the deferral.

**Acceptance Criteria:**
- Given the review screen with counts off and a qualifying candidate, when it shows, then only qualifying stickers are numbered/outlined by the count line, in margin order, and `review:suggestion` names the nearest qualifying under-counted colour.
- Given the review screen with counts off and no qualifying candidate, when it shows, then the count line is plain, no `review:suggestion` exists, and piece problems still outline their stickers.
- Given the Manual Cube Editor, then its markers are unchanged (no Editor file in the diff).
- `./gradlew spotlessCheck build` passes.

## Design Notes

Margin uses the sticker's own read colour centre so a sticker measured near the boundary between two colours qualifies, while an exact read of a clearly different colour never does. Because the read colour was nearest at classification time, a margin below 0 only occurs in synthetic fixtures or after recalibration; it simply ranks first.

## Auto Run Result

**Summary:** `SuspectRanking` (Scan review only) now applies the #323 proximity rule. A scanned, unedited, non-centre sticker of an over-counted colour qualifies for an under-counted colour only when ΔE to that colour's centre minus ΔE to its read colour's centre is at most `SUSPECT_MARGIN_DELTA_E` (20 ΔE = 2 × `CLOSE_CALL_DELTA_E`, CIE76). It is suggested as the nearest qualifying colour. At most 3 are listed, ordered by margin then index, never padded. With none qualifying, the count line stays plain, with no "may be X" suggestion and no count-suspect marker; piece problems still show. The Editor is untouched. Margins: release case A (Up 3 orange → blue) ≈ 122.8 and case B (Up 9 white → blue) ≈ 71.4, so neither suggests anything; the #174 replay is ≈ 11.2 and still suggests "may be orange", so no HALT was needed; the two-under-counted case gives 46 → blue (≈ −65.1), then 38 → orange (≈ −22.8).

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SuspectRanking.kt` — the margin constant, the qualifying filter, margin ordering, KDoc.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/SuspectRankingTest.kt` — tests for release cases A/B (scramble `R' U' R`), the #174 replay, two under-counted colours, nearest-of-two, the boundary, nothing qualifying, and the cap and tie-break; padding-based expectations updated.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` — #174 screen tests updated to `[50, 39]`; new test that counts off with no close candidate leaves a plain line.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt` — asserts the full suspect list.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `issue-174-deferred-suspect-distance-threshold` status `open` → `done`, the only change in that file.

**Review findings:** 21 findings (0 high, 0 medium, 15 low, 6 false). Nine low patches were applied, all tests or documentation except the constant expression. Nothing was deferred. Every rejected finding and its reason is in the Review Triage Log.

**Follow-up review recommendation:** false. The patches were 0 high, 0 medium and 9 low (entries: constant/KDoc, cap test, screen assertions, case-B precondition, flow-test assertion).

**Verification:** `./gradlew spotlessCheck build`: BUILD SUCCESSFUL after the patches. JUnit: SuspectRankingTest 16/16, ScanReviewScreenTest 78/78, GuidedScanFlowTest 46/46, 0 skipped.

**Residual risks:** not checked on a device. A real misread that measures almost exactly the wrong colour is no longer suggested; the PO chose that trade-off, and the piece problems still point at it.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test :app:testDebugUnitTest --tests '*ScanReview*' --tests '*Suspect*' --tests '*GuidedScanFlow*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
