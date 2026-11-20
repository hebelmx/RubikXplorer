---
title: 'ai-b4: Copy and layout polish (playback header padding, scan review copy and focus, Move strip and Settings)'
type: 'bugfix'
created: '2026-11-20'
status: 'done'
baseline_revision: 'ab2311a6333c815a4a6a582cd1d0fb0bea5e7070'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['multiple-goals', 'oversized']
deferred:
  - summary: 'PO device check: the wrapped "<N> moves" header on Solution Playback no longer touches the right edge (large font scale, narrow phone).'
    evidence: 'Layout bounds are asserted in Robolectric; how the wrap looks on a real screen is a visual call.'
    severity: low
  - summary: 'PO device check: on the full review net with color-blind glyphs on, the problem number tag and the sticker glyph read clearly side by side (smallest full-net cell).'
    evidence: 'Non-overlap is asserted in Robolectric; legibility is a visual call.'
    severity: low
  - summary: 'PO device check: TalkBack in a Lesson Demonstration reads the generated MoveNote after each Move; the scrubber active track shows ink-secondary.'
    evidence: 'Semantics and colors are asserted in Robolectric; spoken output and on-screen color are device checks.'
    severity: low
  - summary: >-
      On a camera scan with suspects the instruction line still ends "Check the outlined piece." although the outlined suspects are stickers on several faces, not one piece.
    evidence: |-
      Pre-existing suffix from msg.scan.review-hint-invalid; item 5 only covers the count line reason and its TalkBack text. Raised by the blind hunter in the ai-b4 review.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt instruction()
    severity: low
---

<intent-contract>

## Intent

**Problem:** Ten open low-severity action items from #201, #199, #174 and 12.15 leave small copy, focus and layout defects in Solution Playback, Scan review, the shared playback transport and Settings.

**Approach:** Fix each item exactly as its sprint-status action says, each with a JVM/Robolectric test that asserts the fixed behavior. No other behavior changes.

Items (ids from `sprint-status.yaml` `action_items`):
1. `issue-201-deferred-header-length-end-padding` -- the Solution Playback "<N> moves" header text gets `Spacing.s4` end padding.
2. `issue-199-deferred-instruction-hint-global-problem` -- when the first problem has no piece and nothing is outlined, the review instruction is the problem text alone (no "Check the outlined piece.").
3. `issue-199-deferred-problem-tap-positions-first` -- the problem-line tap target is `positions.firstOrNull() ?: piece.first()`.
4. `issue-199-deferred-problem-tag-glyph-overlap` -- on a sticker that shows a problem tag, the color-blind glyph is inset (and shrunk only as needed) so it never overlaps the tag.
5. `issue-174-deferred-counts-line-wording-on-scan` -- a wrong-counts problem that lists suspects (camera scan with evidence) reads a scan-specific reason, not "Check the colors you entered.", and its TalkBack description names the stickers as likely misreads, best first, not "Stickers:".
6. `issue-174-deferred-focus-after-last-blank` -- painting the last blank that leaves wrong counts beside an earlier piece problem focuses the counts line led by the selected (top) suspect.
7. `epic-12-deferred-12.15-demonstration-movenote` -- Lesson Demonstrations pass `MoveNote.of(currentMove)` as the transport `note` (UX-DR14: MoveNote line under the current Move on every shared transport).
8. `epic-12-deferred-12.15-scrubber-track-ink-secondary` -- scrubber active track `ink-secondary`, thumb stays `ink-primary`, inactive stays `hairline` (DESIGN.md Playback transport).
9. `epic-12-deferred-12.15-move-strip-state-keyed-on-moves` -- the Move strip `LazyListState` is keyed on the Move list.
10. `epic-12-deferred-12.15-settings-switch-missing-string` -- the Move pictures switch is hidden only while content loads (no strings yet); once strings are given it always shows, even if the key were missing.

## Boundaries & Constraints

**Always:** Learner text only in `core/domain/src/main/resources/content/strings/en.json` via `Text(key, args)`; plain, short Cubentia copy in the existing tone; keep the 16dp glyph floor (AD-17) wherever the cell allows; existing tests keep passing (update only ones that encode the old defect).

**Never:** edit `sprint-status.yaml`, planning artifacts, `_bmad/` or `.claude/skills/`; change the domain reason key `msg.validation.wrong-color-counts` or `CubeValidator`/`ScanReview` problem lists; change behavior for non-suspect wrong counts, for paints other than the last blank, or for Solution Playback / Algorithm detail notes; add libraries.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Global first problem | no blanks, first problem piece empty (e.g. counts with no evidence, parity), nothing outlined | instruction = resolved problem text only | -- |
| Piece first problem | first problem has a piece | instruction unchanged ("{problem} Check the outlined piece.") | -- |
| Counts with suspects | camera scan, counts off, suspects present | line shows new scan reason; TalkBack "Problem 1: ... Likely misreads, most likely first: <stickers>." | -- |
| Counts without evidence | `ScanReview(miscounted, 39)` | old reason text, unnumbered plain line (unchanged) | -- |
| Last blank painted | one blank, piece problem + counts off after paint, evidence present | selection = top suspect, counts line selected, piece line not | -- |
| Strip new list | strip scrolled far in list A, then list B with `currentIndex` null | B shows from its first chip | -- |
| Settings strings | `strings = null` / `ContentStrings(emptyMap())` / shipped | hidden / shown (label is the key) / shown "Move pictures" | -- |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt:69-74` -- header `Text` tag `solvePlayback:length`, `Modifier.weight(1f)`; add `.padding(end = Spacing.s4)` after `weight(1f)` and before `testTag`, so the tagged node excludes the padding and the test can measure its right edge.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- `ReviewMode` (225-397): `focusedIndex` init 231-242 (counts-line-led-by-selected rule; reuse it), swatch handler 320-333 sets `focusedIndex = null` on change; problem tap 338-342 (`problem.positions.first()`); `ProblemLine` 444-490 (description keys `msg.scan.problem-description-stickers` / `-description`); `instruction()` 497-510. Extract the tap target into an `internal` pure function so a JVM test can feed a `ReviewProblem` with empty positions.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt:38-45,55-64` -- read-only: counts problem positions = suspects (rank order), `piece = positions`; `paint` selects the top suspect after the last blank.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt:423-446` -- glyph (`StickerGlyph(appearance, cell)`, centered) and `ProblemTag` (`TopStart`, diameter `max(cell*0.4, 16dp)`). Full net cells are >= `Spacing.touchMin` (ScanReviewScreen `oneFace` rule), so an inset glyph region of ~0.52 cell stays >= 16dp.
- `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` -- `StickerLabel` sizing (`GLYPH_FRACTION` 0.55, `MIN_GLYPH_DP` 16, capped by inner); may gain an optional max-size parameter; other callers unchanged.
- `core/domain/src/main/resources/content/strings/en.json:81,715-723` -- add two keys (scan counts reason with suspects; suspects description). Reason must not say "Check the colors you entered."
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt:132-141` -- pass `note = state.currentMove?.let { strings.resolve(MoveNote.of(it)) }` like `AlgorithmDetailScreen.kt:134`.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt:353-357` -- `activeTrackColor = tokens.inkSecondary`; `playback:note` 321-332.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/MoveStrip.kt:52` -- `rememberLazyListState`; replace with `rememberSaveable(moves, saver = LazyListState.Saver) { LazyListState(currentIndex ?: 0) }` (or equivalent keyed form).
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt:57-59,103-107,134` + `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:272-274` -- make `strings: ContentStrings?` default `null`; AppShell passes `(content as? CatalogState.Ready)?.strings`; switch shown iff strings non-null; calibration rows need non-null strings.
- Tests: `app/src/test/.../ui/scan/ScanReviewScreenTest.kt` (fixtures `miscounted`, `suspectReview()`, `labs`, test at 451 builds piece+counts), `.../ui/settings/SettingsScreenTest.kt:228-250`, `.../ui/playback/MoveStripTest.kt`, `.../ui/playback/PlaybackTransportScrubTest.kt`, `.../ui/lesson/DemonstrationPlayerTest.kt`, `.../ui/solve/SolutionPlaybackTest.kt` or a SolvePlaybackScreen test for `solvePlayback:length`.

## Tasks & Acceptance

**Execution:**
- `SolvePlaybackScreen.kt` -- end padding `Spacing.s4` on the length text -- item 1.
- `ScanReviewScreen.kt` + `en.json` -- shown-reason helper (counts key + non-empty positions -> new scan key) used by `ProblemLine` and `instruction()`; suspects description key; instruction drops the suffix for a pieceless first problem when `review.outlined` is empty; tap target helper; last-blank paint sets `focusedIndex` to the counts line led by `painted.selected` -- items 2, 3, 5, 6.
- `StickerReviewNet.kt` (+ `StickerGlyph.kt` if needed) -- when `problemMark != null` and glyphs on, lay the glyph out in the area not covered by the tag (e.g. padded start/top by the tag diameter), sized to fit, floor 16dp; give the glyph a test tag `sticker:$index:glyph` -- item 4.
- `DemonstrationPlayer.kt` -- pass the MoveNote -- item 7.
- `PlaybackTransport.kt` -- active track `inkSecondary` -- item 8.
- `MoveStrip.kt` -- key list state on `moves` -- item 9.
- `SettingsScreen.kt`, `AppShell.kt` -- nullable strings -- item 10.
- Tests (Robolectric/JVM) for every item, per the ACs below.

**Acceptance Criteria:**
- Given Solution Playback at w320dp with a long header (large font scale), when laid out, then `solvePlayback:length` text right edge is <= root width - 16dp.
- Given the I/O matrix rows, when the review renders, then instruction, line text, TalkBack description and selected line match each row.
- Given a `ReviewProblem` with a piece and empty positions, when the tap target is computed, then it is `piece.first()` (no exception); with positions, `positions.first()`.
- Given the full review net at the smallest full-net cell (>= 48dp) with glyphs on and a problem mark, then the `sticker:i:glyph` bounds do not intersect `sticker:i:tag` bounds and the glyph is >= 16dp; without a mark the glyph stays centered at its old size.
- Given a Demonstration stepped one Move, then `playback:note` exists with the resolved MoveNote of the shown Move in a polite live region.
- Given the scrubber, then its active track pixels/colors are `ink-secondary` and the thumb `ink-primary` (pixel check via `captureToImage` or an asserted `SliderColors`).
- Given the strip-new-list matrix row, then the first visible chip of list B is index 0.
- Given the Settings matrix rows, then the `settings:movePictures` switch is hidden / shown / shown as stated.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 22 findings — high 0, medium 0, low 15, false 7, maybe-false 0
- findings:
  - `[low]` `[defer]` (blind) Counts-with-suspects instruction still says "Check the outlined piece." — pre-existing suffix, outside item 5's count-line wording; deferred in frontmatter.
  - `[low]` `[reject]` (blind) Glyph inset on both axes shrinks it more than needed — 26.4dp to ~25dp at 48dp, still above the 16dp floor; visual check is a deferred PO item.
  - `[low]` `[reject]` (blind) `maxSize` has no lower bound — only cells under ~35dp go below 16dp; full-net cells are >= 48dp (`oneFace` rule) and retake mode passes no problem marks; a guard adds complexity for an unreachable case.
  - `[low]` `[reject]` (blind) Non-overlap test covers one case, no RTL — the net forces LTR (`StickerReviewNet` `LocalLayoutDirection provides Ltr`); one extra case adds little.
  - `[false]` `[reject]` (blind) Last-blank focus indexes `painted.problems` not the rendered list — the screen only re-renders from the review handed back via `onReview(painted)`, so the index refers to the list that is rendered next.
  - `[low]` `[reject]` (blind) Suspects check matches a literal key — pre-existing pattern (ScanReviewScreen.kt:235 at baseline); a domain constant is a refactor outside the items.
  - `[false]` `[reject]` (blind) Matrix rows untested — "piece first problem" is asserted at ScanReviewScreenTest.kt:681; the other cases named are not matrix rows (and non-last-blank paints keep the baseline behavior).
  - `[low]` `[patch]` (blind) Scrubber test comment "Halfway along" does not match 1/8 and 7/8 samples — comment reworded.
  - `[low]` `[reject]` (blind) Demonstration MoveNote test only covers the stepped state; live-region backlog during autoplay — same note wiring as Solution Playback and Algorithm detail (AlgorithmDetailScreen.kt:134); TalkBack behavior is a deferred PO check.
  - `[false]` `[reject]` (blind) Settings shows a raw key when the string is missing — the shipped key is asserted by SettingsScreenTest `movePicturesFollowsGlyphsAndResetRestoresIt`; the empty-map case is the item's required "not hidden" behavior.
  - `[low]` `[reject]` (blind) Calibration rows now need strings, untested with null — AppShell passes calibration only when content is Ready, so no reachable change.
  - `[low]` `[patch]` (blind) New key filed among review-hint keys — moved next to `msg.scan.problem-description-suspects`.
  - `[low]` `[reject]` (blind) Visible counts line does not name the suspects — the net tags show them; the item asks only for TalkBack naming.
  - `[low]` `[reject]` (blind) Spec sections incomplete / Code Map wording — fix would edit this build's spec.
  - `[false]` `[reject]` (edge) First problem pieceless while a later problem is outlined — unreachable: `CubeValidator.analyze` returns wrong counts alone (CubeValidator.kt:104) and PieceCheck problems, which come first, always carry a piece.
  - `[low]` `[reject]` (edge) Zero/negative glyph font when cell < ~17dp — unreachable (full-net cells >= 48dp, retake has no marks).
  - `[low]` `[reject]` (edge) Glyph below 16dp floor for marked cells < ~35dp — unreachable for the same reason.
  - `[false]` `[reject]` (intent) Item 7 should HALT as a product decision — UX-DR14 / DESIGN.md:252 put the MoveNote line under the current Move on the shared transport used by Demonstrations; 12.15 only scoped it out of its own ACs.
  - `[false]` `[reject]` (intent) Item 10 could mean guaranteeing the key in the bundle — the shipped bundle key is already asserted (SettingsScreenTest); the action's defect is hiding on a missing key, which the change removes.
  - `[false]` `[reject]` (intent) `countsLineLedBy` rewrites the initial-focus path — `piece.isNotEmpty()` over all indices is exactly `linkedIndices`; behavior identical (existing top-suspect focus test passes).
  - `[low]` `[reject]` (intent) Item 2 tested only with counts-without-evidence — parity/twist/flip carry positions on their pieces; the pieceless case is the one the item targets.
  - `[low]` `[reject]` (intent) Item 3 tested via helper, item 8 light theme only — the helper is the exact tap code path; token swap is theme-independent.

## Design Notes

Item 5 wording (new keys, final copy may be polished but must keep the meaning): `msg.scan.wrong-color-counts-suspects`: "Each color should appear on exactly nine stickers. The camera may have misread a sticker."; `msg.scan.problem-description-suspects`: "Problem {number}: {problem} Likely misreads, most likely first: {stickers}." The domain keeps `msg.validation.wrong-color-counts` so `focusedIndex`'s key check and domain tests stay valid; only the screen chooses the shown text. Item 7 matches UX-DR14 / DESIGN.md (MoveNote line under the current Move on the shared transport), so it does not conflict with 12.15's out-of-scope note. Item 10 keeps 12.15's "hidden while content loads" precedent.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew :app:testDebugUnitTest --tests '*ScanReviewScreenTest' --tests '*SettingsScreenTest' --tests '*MoveStripTest' --tests '*PlaybackTransportScrubTest' --tests '*DemonstrationPlayerTest' --tests '*SolutionPlaybackTest'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** All ten ai-b4 action items fixed with Robolectric/JVM tests; none was already fixed on main. Header end padding on Solution Playback; scan review instruction drops "Check the outlined piece." for a pieceless first problem with nothing outlined; problem tap target falls back to the piece; color-blind glyph inset beside a problem tag; camera-scan counts line reads a scan reason and TalkBack names likely misreads best first; painting the last blank focuses the counts line led by the top suspect; Demonstrations pass the MoveNote; scrubber active track ink-secondary; Move strip list state keyed on the Move list; Settings Move pictures switch hidden only while content loads.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` — end padding on the length header.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` — `shownReason`, `problemTapTarget`, `countsLineLedBy`, instruction rule, last-blank focus, suspects description.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt`, `app/src/main/kotlin/dev/cubentia/app/render/StickerGlyph.kt` — glyph inset/max size beside a tag, glyph test tag.
- `core/domain/src/main/resources/content/strings/en.json` — two new keys.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` — MoveNote note.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` — ink-secondary active track.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/MoveStrip.kt` — list state keyed on moves.
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt`, `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` — nullable strings.
- Tests: `ScanReviewScreenTest`, `SolutionPlaybackTest`, `DemonstrationPlayerTest`, `PlaybackTransportScrubTest`, `MoveStripTest`, `SettingsScreenTest`.

**Review:** 22 findings — 2 patched (low: test comment, key placement), 1 deferred (instruction suffix on suspects, pre-existing), 19 rejected (7 false, 12 low; reasons in the Review Triage Log). Patched by verdict: high 0, medium 0, low 2. Follow-up review recommended: false.

**Verification:** `./gradlew spotlessCheck build` — BUILD SUCCESSFUL after patches; targeted suites passed (ScanReviewScreenTest 56, SettingsScreenTest 9, MoveStripTest 3, PlaybackTransportScrubTest 4, DemonstrationPlayerTest 23, SolutionPlaybackTest 40; 0 failures).

**Residual risks:** device-only PO checks in frontmatter `deferred` (header wrap, glyph/tag legibility, Lesson TalkBack MoveNote and scrubber color on screen).
