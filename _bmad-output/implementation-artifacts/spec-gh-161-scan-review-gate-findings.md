---
title: 'GH-161: Story 10.3 scan review gate follow-ups'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: 'b5661e12a662c02d7befdaeda82a6f21f481ac4b'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [multiple-goals, oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Story 10.3 acceptance gate (#161) left five gaps in the scan review: its 200% font test runs on legacy font metrics, the blank sticker's "?" grows with font scale and can clip in ~50 dp net cells, the Activity's camera unbind and rotation in Review are untested, no test drives a close yellow/white sample through `ScanReview.fromSamples`, and a corner with three stickers of one color reads "shows two <color>s".

**Approach:** Add `@GraphicsMode(NATIVE)` to `ScanReviewScreenTest`; size the "?" in dp capped to the sticker exactly like `StickerGlyph` (shared sizing); add Robolectric tests for camera stop on entering Review and rotation while in Review; add an end-to-end `fromSamples` close-call test; give the `piece-repeats` messages a `count` argument so content strings say "three" for a corner of one color.

## Boundaries & Constraints

**Always:** Learner text only in `core/domain/src/main/resources/content/strings/en.json` via `Text(key, args)` (ICU `MessageFormat`). Keep the existing message keys. Two repeats still read exactly "This {piece} shows two <color>s." Keep "?" coming from `msg.scan.blank-label`.

**Never:** The issue's Device item (Lenovo/S24 FE check). No re-test of what `ScanRouteTest` already covers (Enter, Accept, Back-Cancel). No change to classifier thresholds, review layout or the net's cell sizing. No edits to planning artifacts, `_bmad/`, `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Corner, two repeats | corner stickers W, W, R | `piece-repeats.white`, args `piece=corner, count=2` → "This corner shows two whites." | No error expected |
| Corner, all one color | corner stickers W, W, W | `piece-repeats.white`, args `piece=corner, count=3` → "This corner shows three whites." | No error expected |
| Edge, both one color | edge stickers B, B | args `piece=edge, count=2` → "This edge shows two blues." | No error expected |
| Close yellow/white | solved-cube samples, one non-center sticker at Lab(88, -2, 38) | `fromSamples` review: that one index blank and selected, `accepted` null; painting its solved color accepts the solved cube | No error expected |

</intent-contract>

## Code Map

- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt:68-69` -- class annotations; no `@GraphicsMode`. 200% test at ~198-219; blank "?" test at ~390-400. Class qualifier `w640dp-h960dp` gives the full 12-column net with ~50 dp cells.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/DynamicType200Test.kt:11` -- reference: `@GraphicsMode(GraphicsMode.Mode.NATIVE)` import `org.robolectric.annotation.GraphicsMode`.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` -- `fontScale200 {}`, `assertNoTextOverflow`, `assertNoMidWordBreaks` to reuse.
- `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` -- `StickerGlyph(appearance, cell)`: `glyphDp = min(max(16, cell*0.55), inner)`, font size `glyphDp / fontScale` sp, PlexSans SemiBold, `softWrap = false`. Reuse point for the "?" sizing.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` (~line 395, `if (appearance == null) Text(... "msg.scan.blank-label" ..., color = ink)`) -- the uncapped blank label (default `bodyLarge`).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `LaunchedEffect(phase::class, ready) { frameSource.stop(); cameraStarted = false; if (cameraPhase()) ... startCamera() }` is the unbind on entering Review; `GuidedScanViewModel.phase` holds the Review across recreation; `onDestroy` calls `frameSource.stop()`.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- Hilt Robolectric harness: `FakeFrames` (`starts`, `receiver`, `feed`), `CameraTestModule`, `labs`, `closeCall`; the local `frames(logo, misread)` helper inside `scanCalibrationRescanSolvesWithCorrectedStickers` drives all six faces into Review.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt:23-34` -- builds `Text(key, mapOf("piece" to kind.id))` with `repeated.size` available.
- `core/domain/src/main/resources/content/strings/en.json:708-713` -- six `msg.validation.piece-repeats.<color>` strings ("shows two <color>s").
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/PieceCheckTest.kt` -- first test fills a whole piece with one color and asserts `args == mapOf("piece" to kind)`; must now include `count`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanReviewTest.kt` -- `sample pipeline calibrates then assembles confident solved faces` builds samples from `GuidedScanPlan.steps` + `RecognitionFixtures.sample/LABS`; base for the close-call case. `ScanAssemblerTest` uses Lab(88, -2, 38) as the WHITE/YELLOW close call.
- `app/src/test/kotlin/dev/cubentia/app/content/ContentStringsTest.kt` -- ICU resolution tests (Android ICU under Robolectric).
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt` -- Text-call argument check skips computed keys (PieceCheck's key is computed), so adding `count` needs no exemption.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` -- extract the dp-capped sizing into an internal composable (e.g. `StickerLabel(text, color, cell)`) that `StickerGlyph` calls -- one sizing rule for glyph and "?".
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- render the blank "?" through that shared sizing with `ink` -- capped to the cell at any font scale.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt` -- add `"count" to repeated.size` to the repeats `Text` args -- the message knows two vs three.
- `core/domain/src/main/resources/content/strings/en.json` -- each `piece-repeats.<color>` becomes ICU with `{count, plural, =3 {three <color>s} other {two <color>s}}` (e.g. "This {piece} shows {count, plural, =3 {three whites} other {two whites}}.") -- three for an all-one-color corner.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/PieceCheckTest.kt` -- update args assertion (corner count 3, edge count 2) and add a two-of-three corner case asserting count 2 -- covers the matrix rows.
- `app/src/test/kotlin/dev/cubentia/app/content/ContentStringsTest.kt` (or ScanReviewScreenTest) -- resolve the shipped `piece-repeats.white` with count 2 and 3 to "This corner shows two whites." / "This corner shows three whites." -- proves the content strings, not just args.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- add `@GraphicsMode(GraphicsMode.Mode.NATIVE)`; add a 200% test at ≥608 dp (class default w640dp) with blanks in the full net asserting each "?" text layout does not overflow and its bounds stay inside its `sticker:<i>` node; fix any existing test NATIVE mode breaks only if the break is a real metric difference (note it in the spec).
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanReviewTest.kt` -- add the close yellow/white `fromSamples` case from the matrix.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- give `FakeFrames` a `stops` count (or `running` flag); hoist the six-face frame driver to a reusable helper; add one test launching `GuidedScanActivity` directly, driving it to Review, asserting the frame source was stopped (no receiver) on entering Review, then rotating (`RuntimeEnvironment.setQualifiers("+land")` + controller `recreate()`) and asserting the phase is still the same Review, the review screen shows, and the camera was not started again.

**Acceptance Criteria:**
- Given `ScanReviewScreenTest`, when it runs, then it uses `@GraphicsMode(NATIVE)` and all its tests pass.
- Given the full review net at ≥608 dp with blank stickers, when shown at 200% font, then every "?" fits inside its sticker without overflow.
- Given a guided scan in the camera, when the last face is captured and Review opens, then the camera frame source is stopped; and when the device rotates while in Review, then the same Review shows and the camera stays stopped.
- Given a corner whose three stickers share one color, when the review lists problems, then the line reads "This corner shows three <color>s." from content strings.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 14 findings — high 0, medium 0, low 8, false 6, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) `StickerGlyph` converts dp to sp by dividing by `fontScale`, so under Android 14+ nonlinear font scaling the glyph and "?" render smaller than their dp target — verified against the nonlinear 2.0 table (never larger, so no clipping, but a few dp under target). Fixed: `glyphDp.dp.toSp()` through `LocalDensity`.
  - `[low]` `[patch]` (blind) The label-size test multiplies sp by `fontScale`, bypassing the density's converter — fixed together with the next row: the assertion converts with `fontSize.toDp()` from the node's density.
  - `[low]` `[patch]` (blind) The label test covers only the 640 dp default, not the 608 dp full-net boundary — added a `w608dp-h960dp` case through a shared assertion helper.
  - `[low]` `[patch]` (blind) The rotation test rotates an untouched review, so a regression that rebuilt the review and dropped corrections could pass — the test now paints a blank before rotating and asserts the edited Review (color, `edited`) survives with the camera stopped.
  - `[low]` `[patch]` (blind) `ContentStringsTest` resolves only the white message though six patterns changed — grouped with the verification-gap row below; replaced by an end-to-end test resolving real `PieceCheck` output for every color (corner three, corner two, edge two).
  - `[low]` `[patch]` (edge) Nonlinear font scaling resizes the "?" — same root and fix as the first row.
  - `[false]` `[reject]` (edge) The test's dp claim bypasses the nonlinear converter — same root as the second row; with the `toDp()` assertion the claim now holds, so nothing further remains.
  - `[low]` `[patch]` (verification-gap) Repeated-color messages verified only for white — fixed by the per-color end-to-end `ContentStringsTest` (pre-verified gap).
  - `[low]` `[patch]` (verification-gap) Compact one-face view leaves blank-label sizing unverified — added a `w360dp-h640dp` 200% case asserting size from the 3-column cell and bounds (pre-verified gap).
  - `[false]` `[reject]` (intent) Item 1 "passes" not shown in the diff — `ScanReviewScreenTest` ran under NATIVE: 28 tests, 0 failures (JUnit XML, 2026-11-19).
  - `[false]` `[reject]` (intent) Item 2 test hard-codes 0.55/0.04 and checks semantic proxies — the gap fraction now references `STICKER_GAP_FRACTION`; `GLYPH_FRACTION` is private and a drift makes the test fail loudly, which is the intended guard; a mutation check (old label) fails it (expected 27.87 dp, was 32.0).
  - `[false]` `[reject]` (intent) Item 3 tests `ScanFrameSource.stop()` on a fake, not CameraX unbind — the Activity's unbind duty is calling `stop()`; `CameraXFrameSource.stop()` calls `cameraProvider?.unbindAll()` (ScanFrameSource.kt), and CameraX cannot bind under Robolectric; the intent asked for an Activity-level test.
  - `[false]` `[reject]` (intent) Item 5 never resolves PieceCheck output end to end — no longer true after the per-color `ContentStringsTest` patch above.
  - `[false]` `[reject]` (intent) Plural `other` maps every count but 3 to "two" — PieceCheck emits only 2 (edge or corner pair) or 3 (corner), so no other count reaches the message.

## Design Notes

ICU `plural` with `=3` keeps one key per color and lets other locales phrase counts their own way; the domain only supplies a number. The "?" must not scale with font size, matching the AD-17 glyph rule already in `StickerGlyph` (≥16 dp, never larger than the sticker).

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test --tests '*PieceCheckTest' --tests '*ScanReviewTest' --tests '*ShippedContentTest'` -- expected: pass
- `./gradlew :app:testDebugUnitTest --tests '*ScanReviewScreenTest' --tests '*ScanRouteTest' --tests '*ContentStringsTest' --tests '*DynamicType200Test'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: pass

## Auto Run Result

Status: done

**Summary:** All five #161 items are in. `ScanReviewScreenTest` runs under `@GraphicsMode(NATIVE)`. The blank "?" shares `StickerGlyph`'s dp-capped sizing through a new `StickerLabel`, and that sizing now converts dp to sp through the density. Repeat messages take a `count`, so a one-color corner reads "three". There are new Activity-level tests for camera stop on entering Review and for rotation with an edited Review, and a domain test feeds a close yellow/white sample through `fromSamples`.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` -- `StickerLabel(text, color, cell)` holds the glyph sizing rule; dp→sp via `toSp()`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- the blank "?" renders through `StickerLabel`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt` -- adds `count` to the repeat `Text` args.
- `core/domain/src/main/resources/content/strings/en.json` -- six `piece-repeats.*` strings use ICU `{count, plural, =3 {three …} other {two …}}`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/PieceCheckTest.kt` -- count assertions, plus a two-of-three corner case.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanReviewTest.kt` -- close yellow/white `fromSamples` case.
- `app/src/test/kotlin/dev/cubentia/app/content/ContentStringsTest.kt` -- resolves real `PieceCheck` output against shipped strings for every color.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- NATIVE graphics; 200% "?" size/bounds checks at 640 dp, 608 dp and the compact one-face view.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` -- `FakeFrames.running`; hoisted `captureFaces`; new `cameraStopsInReviewAndStaysStoppedAfterRotation`.

**Review findings:** 14 in total. 8 low rows were patched, with the dp→sp rows and the white-only rows sharing fixes. 0 were deferred. 6 were rejected as false, and each one’s reason is in the Review Triage Log above.

**Follow-up review recommendation:** false. Patched counts: high 0, medium 0, low 8.

**Verification:** Codex implemented the change. Claude re-ran the spec's three commands after review, and all passed: `:core:domain:test` (PieceCheck, ScanReview, ShippedContent), `:app:testDebugUnitTest` (ScanReviewScreenTest 28/0, ScanRouteTest 4/0, ContentStringsTest 5/0, DynamicType200Test 6/0), and `spotlessCheck build` (BUILD SUCCESSFUL). As a mutation check, the old blank label fails the new size test: it expected 27.87 dp and got 32.0.

**Residual risks:** CameraX `unbindAll` itself is not exercised; it is covered only through the `ScanFrameSource.stop()` contract. In this harness, at 640 dp, the old label did not visibly clip. The fix makes the label bounded by construction. The device check (Lenovo/S24 FE) is still open and out of scope.
