---
title: 'Recognition diagrams derived from state'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: 'ce7a742562c14a060488ac7ba94885d041fcfa88'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      Compose tests observe sticker fills and the tile surface through semantics, never through pixels.
    evidence: |-
      stickerColor is set from the same value passed to background, so deleting the background call would pass every test. The app's Compose tests have no captureToImage or screenshot path, and the same semantics-mirror convention is used by Net2DRenderer and the editor nets. Adding a pixel path is broader than this story.
    location: >-
      app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmTileTest.kt
    severity: low
  - summary: >-
      Net2DRenderer and StickerReviewNet place stickers with Modifier.offset, so an RTL layout direction mirrors those nets.
    evidence: |-
      Both call Modifier.offset(cell * column, cell * row) inside a default-aligned Box, the manifest sets supportsRtl=true, and nothing under app/src/main forces LTR. Pre-existing and outside this story; found while fixing the same defect in RecognitionDiagramView. Not exercised under RTL.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/render/Net2DRenderer.kt:165
    severity: medium
---

<intent-contract>

## Intent

**Problem:** A Case tile needs a top-layer picture, and a hand-drawn or stored one can disagree with the verified Algorithm (FR-18, AD-19). Epic 9's Library, detail and 2-look screens all need the same picture, and the cut-order fallback has no tile to fall back in yet.

**Approach:** Derive a U-face-plus-side-bands diagram in `:core:domain` from the `LastLayerMatcher` Case state and `LastLayerView`, render it through `StickerAppearance` in a Compose component, and add the `AlgorithmTile` whose diagram is switched by a build constant.

## Boundaries & Constraints

**Always:** The diagram is computed from `Notation.parse` through `LastLayerMatcher.caseState` and `LastLayerView` at render time, never stored in content (AD-19). Every sticker uses `StickerAppearance.of` for fill and `StickerGlyph` for the glyph, shown only when `RenderStyle.colorBlindGlyphs` is on (AD-17, UX-DR21). Library diagrams show a YELLOW last layer. Domain failures are sealed results. Case name in the `label` type role, Notation in `notation`, tile on `surface-raised` with `rounded.md`. New Learner-facing text is a content string.

**Never:** No drawable, vector or image asset for a diagram. No new `FeatureFlags` entry and no `BuildConfig` check (AR-23: a plain Kotlin `const` is the switch). No Library screen, navigation, filters, status label or tap action (9.4–9.6). No `LastLayerMatcher` pattern change: existing `caseState(algorithm)` behavior and the golden signatures stay as they are. No gray-out or other recoloring of stickers. No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Every shipped Case | Each of the 78 primary Algorithms | 5×5 diagram: U 3×3 in the middle, one band of 3 per side, four empty corners, cells equal to the yellow-framed Case state's facelets; the view matches exactly its own Case | No error expected |
| Hand-worked Case | `alg-pll-t-a` | Yellow U; F band green, green, orange; R band top to bottom green, red, blue; B band left to right blue, blue, orange; L band top to bottom red, orange, red | No error expected |
| Glyphs on | Tile with `colorBlindGlyphs = true` | Each drawn sticker has its `StickerAppearance` fill and glyph ink | No error expected |
| Glyphs off | Tile with `colorBlindGlyphs = false` | Fills only, no glyph text | No error expected |
| Diagrams cut | `showDiagram = false` | Case name and Notation only; no diagram node and no placeholder | No error expected |
| Bad Notation | Unparseable Notation | Sealed `BadNotation`; tile shows Case name and Notation with no diagram | Sealed result |
| F2L broken | Notation that disturbs the first two layers | Sealed `FirstTwoLayersUnsolved`; tile shows Case name and Notation with no diagram | Sealed result |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt` -- `caseState` (home frame, WHITE LL), `CaseStateResult`, `CASE_LAST_LAYER`, `candidates`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerView.kt` -- 20 stickers, blocks F, R, B, L of (U edge, U corner clockwise of it, side top row); private `homeFaceOf`, `fixedFront`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeState.kt` -- face Moves act through `orientation`; `copy` is module-internal, so `SOLVED.copy(orientation = ...)` holds a solved cube with another color on U.
- `core/domain/src/main/kotlin/dev/cubentia/domain/appearance/StickerAppearance.kt` -- fill, glyph, ink table.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcherTest.kt` -- how a test builds the matcher from the shipped bundle.
- `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` -- `StickerGlyph(appearance, cell)`, `STICKER_GAP_FRACTION`; both `internal` to `:app`.
- `app/src/main/kotlin/dev/cubentia/app/render/Net2DRenderer.kt:50-55` -- `stickerColor` and `stickerGlyphInk` semantics, and the `Sticker` drawing pattern to mirror.
- `app/src/main/kotlin/dev/cubentia/app/preferences/LocalPreferences.kt` -- `LocalRenderStyle`.
- `app/src/main/kotlin/dev/cubentia/app/content/ContentStrings.kt` -- `resolve(Text)`, `resolve(TextKey)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/MoveKeyBar.kt:82,155` -- `RubikTheme.textStyles.notation` and `tokens.surfaceRaised` usage.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Shape.kt` -- `Radii.md`.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorCompactTest.kt` -- Robolectric Compose test setup and shipped `ContentStrings`.
- `core/domain/src/main/resources/content/strings/en.json` -- content strings; Case names are `case.<id>.name`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerView.kt` -- expose an `internal` canonical frame for an LL color and use it in `of` -- the one place that decides the held frame.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt` -- add an optional `lastLayer` parameter to `caseState`, defaulting to `CASE_LAST_LAYER`, starting from solved held in that frame -- yields the same Case with a YELLOW last layer.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagram.kt` -- add `RecognitionDiagram` (`cells`: 25 `Color?` row-major), `of(view)`, `derive(algorithm, lastLayer = YELLOW)` and sealed `RecognitionDiagramResult` (`Derived`, `BadNotation`, `FirstTwoLayersUnsolved`) -- pure derivation under the Kover gate.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagramTest.kt` -- cover the domain rows of the I/O matrix, comparing all 78 against a facelet grid written out in the test -- proves the sticker layout independently of the implementation.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.library.diagram.description` with a `{caseName}` argument -- screen-reader label for the diagram.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/RecognitionDiagramView.kt` -- add the composable: stickers via `StickerAppearance` and `StickerGlyph`, `stickerColor` / `stickerGlyphInk` semantics, default 20dp cell so a 16dp glyph fits, one merged content description -- the diagram component.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmTile.kt` -- add `RECOGNITION_DIAGRAMS_ENABLED` and `AlgorithmTile(case, algorithm, strings, modifier, showDiagram = RECOGNITION_DIAGRAMS_ENABLED, style = LocalRenderStyle.current)` -- the tile and its cut fallback.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmTileTest.kt` -- Robolectric tests for the tile rows of the I/O matrix -- the observable surface of both ACs.

**Acceptance Criteria:**
- Given a Case state derived via `LastLayerMatcher`, when the diagram component renders it, then it shows the U face plus side bands using `StickerAppearance`, with glyphs when on and no hand-drawn assets.
- Given recognition diagrams are cut through the build constant, when tiles render, then they show Case name and Notation with no placeholder art.
- Given `caseState(algorithm)` called without a last-layer color, when the existing verification gate runs, then it behaves as before and every golden signature still matches.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 29 findings — high 0, medium 2, low 25, false 1, maybe-false 1
- findings:
  - `[medium]` `[patch]` Blind: the diagram mirrors under RTL — confirmed: `offset` and TopStart are direction-aware and the manifest sets `supportsRtl`; the grid now sits under `LocalLayoutDirection provides Ltr`, pinned by an RTL test.
  - `[low]` `[patch]` Blind: `the last-layer color can be chosen` cannot fail on a wrong frame — it read only the center; now compares every cell for WHITE against the facelet grid.
  - `[maybe-false]` `[reject]` Blind: glyph letters reach the merged node beside the description — TalkBack normally prefers a content description over merged text; settling it needs a device run, and if true it would be low.
  - `[low]` `[reject]` Blind: the tile reads the Case name twice and Notation as raw characters — no screen hosts the tile yet, and raw Notation reading is app-wide; a spoken form is new design, not a correction.
  - `[low]` `[reject]` Blind: yellow stickers have no backing on the light tile — true at about 1.4:1, but the mockup draws stickers directly on `surface-raised` with no plate (`.tdiag` has no background), so a plate would be a design change.
  - `[low]` `[reject]` Blind: side bands are not separated from the U face — the mockup's band-to-face gap equals its sticker gap; its only cue is thinner bands, which cannot hold the 16dp glyph.
  - `[low]` `[reject]` Blind: `requiredSize` overflows slots under 124dp — no caller exists and Story 9.4 sets the grid; a shrink rule guards a state not demonstrated.
  - `[low]` `[reject]` Blind: the position test measures relative positions only — the facelet-grid test pins placement in the domain and no absolute-shift defect is shown.
  - `[low]` `[reject]` Blind: the type-role test checks one field per role — those fields distinguish the roles used; the surface half is covered by the deferred pixel entry.
  - `[low]` `[reject]` Blind: only the all-null partial view is tested — no v1.0 caller builds a partial diagram; Case recognition is v1.1.
  - `[low]` `[patch]` Blind: the `CASE_LAST_LAYER` KDoc is stale — reworded as the default color for pattern derivation.
  - `[low]` `[reject]` Blind: a mismatched Case and Algorithm are accepted — no caller exists yet, 9.4 pairs them by `caseId`, and a `require` guards a state not demonstrated.
  - `[low]` `[reject]` Blind: derivation repeats on scroll — a parse and at most a few dozen Moves per tile; no measured cost.
  - `[medium]` `[patch]` Edge: RTL mirrors the diagram — same root cause and fix as the Blind RTL row.
  - `[low]` `[reject]` Edge: the fixed 100dp diagram overflows narrow tiles — same as the Blind `requiredSize` row.
  - `[low]` `[reject]` Edge: `caseId` mismatch — same as the Blind mismatch row.
  - `[low]` `[reject]` Edge: a non-primary Algorithm may show another AUF — all 78 shipped Algorithms are primaries and alternatives are out of Epic 9's scope.
  - `[low]` `[patch]` Verification gap: the YELLOW default is unpinned — added `the default last-layer color is YELLOW` and a hard-coded yellow fill check on sticker 12.
  - `[low]` `[patch]` Verification gap: "announced once" does not observe merging — the test now asserts no merged-tree children with glyphs on.
  - `[low]` `[patch]` Verification gap: the no-placeholder check sees only tags — `assertNameAndNotationOnly` now asserts the Case name sits at the top padding.
  - `[low]` `[defer]` Verification gap: painted fills and the tile surface are not observed — pre-existing test convention; recorded in `deferred`.
  - `[false]` `[reject]` Intent: no screen reaches the tile — the ACs live at "the diagram component" and "tiles render", and Story 9.4 owns the Library screen.
  - `[low]` `[defer]` Intent: AC 1 is observed through semantics, not the drawn picture — same deferred entry as the painted-fills row.
  - `[low]` `[reject]` Intent: "no hand-drawn assets" has no guard — the diff adds no drawable; a scan is new machinery for a property held by construction.
  - `[low]` `[reject]` Intent: the constant-off path runs only through `showDiagram = false` — the constant is that parameter's default, the default path has a test, and both reach the same branch.
  - `[low]` `[patch]` Intent: "no placeholder art" is checked by tags only — same fix as the Verification gap placeholder row.
  - `[low]` `[reject]` Intent: the picture binds to an Algorithm and is re-derived in the yellow frame — every one of the 78 yellow views is tested to match exactly its own Case; the pairing is the Blind mismatch row.
  - `[low]` `[reject]` Intent: the output differs from the mockup's gray and thin bands — the AC requires `StickerAppearance`, and the glyph minimum rules out thin bands.
  - `[low]` `[reject]` Intent: additions beyond the literal ACs — each traces to the spec or the story's refs (sealed-result policy, NFR-7, the yellow frame), and none adds behavior a Learner can reach beyond the tile.

## Design Notes

**Why yellow.** `caseState` defaults to WHITE because Notation assumes the home frame, but the UX mockup specifies "derived recognition diagrams (yellow last layer)" and the Course teaches a yellow LL. Holding the solved cube with YELLOW on U and GREEN on F, then applying the inverse, gives the same Case seen as the Learner holds it.

**Colors, not gray.** The mockup draws unoriented stickers in gray, but the AC and AD-17 require `StickerAppearance`, which has no gray. The AC governs.

**Diagram grid**, as indices into the 20 view stickers (`C` is the LL center, `.` is empty):

```
 .  14 13 12  .
17  16 10 11  9
18  15  C  5  8
19   1  0  6  7
 .   2  3  4  .
```

Physical adjacency check: R band top is the UBR sticker next to U2, and L band top is next to U0. B reads right to left because its net row is seen from behind.

**The tile exists here** because the cut AC is about tiles and Story 9.4 only arranges them. Status text and tap handling are added by later stories.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `RecognitionDiagramTest`, `AlgorithmTileTest` and the existing verification gate.

## Auto Run Result

**Summary.** Recognition diagrams are now computed from each Algorithm's Notation through `LastLayerMatcher.caseState` and `LastLayerView`, and drawn through `StickerAppearance` with glyphs when on. `AlgorithmTile` shows the diagram plus Case name and Notation, and falls back to name and Notation with no placeholder when `RECOGNITION_DIAGRAMS_ENABLED` is false or no diagram can be derived. No screen hosts the tile yet; Story 9.4 adds that.

**Files changed.**
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagram.kt` -- the 5×5 derivation and its sealed result.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt` -- `caseState` takes an optional last-layer color.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerView.kt` -- shared `canonicalFrame`.
- `core/domain/src/main/resources/content/strings/en.json` -- diagram description string.
- `core/domain/src/test/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagramTest.kt` -- all 78 Cases, the hand-worked T perm, sealed failures.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/RecognitionDiagramView.kt` -- the diagram composable.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmTile.kt` -- the tile and the build constant.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmTileTest.kt` -- glyphs, layout, RTL, labels and fallbacks.

**Review.** 29 findings. Patched entries: 6 (medium 1, low 5). Deferred: 2 entries, the missing pixel-level checks (low) and RTL mirroring in the existing nets (medium). Rejected: 16, each with its reason in the Review Triage Log.

**Follow-up review: not recommended.** Patched by verdict: high 0, medium 1, low 5.

**Spec adjustment during verification.** The planned key `library.diagram.description` failed `ShippedContentTest`, which exempts only `msg.` and `btn.` keys. The key became `msg.library.diagram.description`, the existing convention for UI text, and the implementer's added exemption was reverted, so that test is unchanged.

**Verification.** `./gradlew spotlessCheck build` was BUILD SUCCESSFUL after the patches. JUnit reports: `RecognitionDiagramTest` 9, `AlgorithmTileTest` 11, `LastLayerMatcherTest` 20, `ShippedAlgorithmVerificationTest` 1, `ShippedContentTest` 21, no failures or skips. No instrumented or device runs, so TalkBack behavior and the drawn appearance are unverified.

**Residual risks.** Library diagrams use full sticker colors at uniform size, unlike the mockup's gray stickers and thin bands, as the AC requires. The 100dp diagram needs a tile at least 124dp wide, which Story 9.4 must respect. Yellow on the white light-theme tile is low contrast, as in the mockup.
