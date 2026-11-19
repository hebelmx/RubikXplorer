---
title: 'GH-199 Scan review: link each error message to its piece and stickers on the net'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: 'b3e57954a55d1da956baeed054b0933ba30d8384'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/DESIGN.md'
warnings: [oversized]
deferred:
  - summary: >-
      The instruction line appends "Check the outlined piece." even when the first problem is a global one (parity, twist, flip, color counts) with nothing outlined.
    evidence: |-
      ScanReviewScreen.instruction() uses review.problems.firstOrNull() with msg.scan.review-hint-invalid; unchanged by this story (pre-existing). Raised by blind-hunter review pass 1.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt instruction()
    severity: low
---

<intent-contract>

## Intent

**Problem:** ScanReviewScreen lists validation errors ("This corner shows two oranges.") as plain text; nothing on the net shows which piece or which stickers each message means, and today only the conflicting stickers are outlined, with no tie to a message. EXPERIENCE.md requires "Validation messages point at the specific stickers" (Sticker review net) and "The offending pieces are outlined" (Invalid entered state).

**Approach:** Each `ReviewProblem` also carries the full sticker set of its piece. The review screen numbers every problem that has a piece, shows that number as a tag on the message line and on the problem's conflicting stickers, outlines all the piece's stickers, and makes each numbered line a button that selects the piece's first conflicting sticker and focuses that problem (the one-face view follows the selection to that face).

## Boundaries & Constraints

**Always:** All Learner text through content strings in `core/domain/src/main/resources/content/strings/en.json` (face names may keep using the existing `R.string.move_face_*`). Highlights follow the cube-highlight rule (never `status-invalid` red on the cube body). Problem order and the existing reason texts stay unchanged. Messages with no piece (wrong color counts, twisted corner, flipped edge, parity) stay plain lines: no number, not clickable. Domain stays pure JVM.

**Never:** No edits under `_bmad-output/planning-artifacts`, `_bmad/`, `.claude/skills/`. No suspect-sticker ranking (#174). No changes to ManualCubeEditor's look or behavior (the shared `StickerReviewNet` gets an optional, default-off parameter only). No new `SessionEvent`, no persistence.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Repeated color on a corner | solved, sticker 9 painted WHITE | problem 1 "This corner shows two whites.": `positions=[8,9]`, `piece=[8,9,20]`; stickers 8, 9, 20 outlined; 8 and 9 carry tag "1"; line 1 carries tag "1" | — |
| Opposite colors on an edge | edge shows red+orange | `positions` = the two stickers, `piece` = the edge's two stickers | — |
| Two problems on one piece | corner W,W,Y | two problems, same `piece`; numbers 1 and 2; a sticker in both announces both | — |
| Impossible piece (validator) | complete net with impossible corners | `piece = positions` = all their stickers; linked and numbered | — |
| Global problem | twist / flip / parity / wrong counts | `piece = positions = []`; unnumbered plain line, no click action | — |
| Tap line N (wide) | any linked problem | `review.selected` = first of its `positions`; problem N focused | — |
| Tap line N (one-face) | piece not on shown face | face switches to `selected / 9`, a face of that piece | — |
| Paint changes stickers | focus on problem 2 | focus resets to the first linked problem | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt` -- `ReviewProblem(reason, positions)`; add `piece: List<Int>` (all stickers of the offending piece(s), sorted). `PieceCheck.problems` sets `piece = positions.sorted()` of the table entry for both repeat and opposite problems.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Facelets.kt:40` -- `FaceletTables.CORNER_FACELETS` / `EDGE_FACELETS` (internal), the piece sticker tables.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeValidator.kt:30-60` -- `Invalid.positions`: `ImpossiblePiece` carries all its stickers; the others are empty.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- `problems` maps validator results to `ReviewProblem(it.reason, it.positions, piece = it.positions)`; `outlined` becomes the union of every problem's `piece`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/PieceCheckTest.kt`, `.../recognition/ScanReviewTest.kt` -- existing assertions on `positions` stay; `ScanReviewTest:143` `outlined` expectation becomes `{8, 9, 20}`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- shared net (also used by `ManualCubeEditor.kt:127`). `ReviewSticker` draws `drawRing` for invalid stickers and sets `stickerInvalid` / `stickerOutlineColor` / `stateDescription = R.string.editor_sticker_invalid`. Add an optional `problemMarks: Map<Int, StickerProblemMark>? = null` (index → problem numbers, whether conflicting, whether focused) that draws the tag and overrides the state description; null keeps today's behavior exactly.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- problem lines at the `review.problems.forEachIndexed` block (tags `review:problem:$index`); `FaceSelector` marks faces from `review.outlined + review.blanks`; `LaunchedEffect(review.selected)` already moves the one-face view to the selected sticker's face; the swatch handler sets `changed = true` when stickers change (reset focus there). `stickerArgs` shows the face/position pattern.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- Robolectric harness (`launch`, `screen`, `w640dp-h960dp`; one-face tests use `@Config(qualifiers = "w360dp-h640dp")`). Test at :198 asserts `sticker:20` not invalid — becomes invalid (same piece).
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.scan.*` keys near line 715; ICU MessageFormat (plural supported) via `app/.../content/ContentStrings.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../cube/PieceCheck.kt` -- add `piece` to `ReviewProblem`; fill it in `PieceCheck.problems` -- the problem carries both its piece and its conflicting stickers.
- `core/domain/.../recognition/ScanReview.kt` -- map `piece` for validator problems; `outlined` = union of `piece` -- whole offending piece outlined.
- `core/domain/src/test/.../PieceCheckTest.kt`, `ScanReviewTest.kt` -- assert `piece` for every corner and edge repeat/opposite case (piece = sorted table entry, positions ⊆ piece), two problems on one piece, `ImpossiblePiece` piece = positions, global problems empty, `outlined` union.
- `core/domain/src/main/resources/content/strings/en.json` -- add keys: `msg.scan.problem-description-stickers` ("Problem {number}: {problem} Stickers: {stickers}."), `msg.scan.problem-description` ("Problem {number}: {problem} Its piece: {piece}. Conflicting stickers: {stickers}."), `msg.scan.sticker-ref` ("{face} face, position {position}"), `msg.scan.problem-action` ("Select it on the net"), `msg.scan.sticker-in-problem` (plural on count: "Part of problem {numbers}" / "Part of problems {numbers}"), `msg.scan.sticker-conflict-in-problem` (plural: "Conflicting sticker in problem {numbers}" / "…in problems {numbers}") -- TalkBack text as content strings.
- `app/.../ui/editor/StickerReviewNet.kt` -- optional `problemMarks`; draw tag; semantics: new key `StickerProblemsKey` (List<Int> numbers), `StickerProblemFocusedKey` (Boolean), stateDescription from the two new sticker keys -- linked highlight without touching the editor.
- `app/.../ui/scan/ScanReviewScreen.kt` -- number linked problems; `ProblemTag` composable; clickable lines with `onClickLabel`, `contentDescription` from `msg.scan.problem-description`, `selected` when focused; saved focus index with fallback to first linked problem; reset on paint; pass `problemMarks`; FaceSelector uses `outlined` (now piece-wide).
- `app/src/test/.../ScanReviewScreenTest.kt` -- tests per Acceptance Criteria below; update :198 expectation; one Robolectric test with a validator impossible-piece fixture (e.g. swap stickers 9↔36 and 5↔37 of a solved cube) asserting the row is numbered, its stickers carry the tag, and a tap selects its first conflicting sticker; a `captureToImage` pixel assertion (pattern: `app/src/test/kotlin/dev/cubentia/app/ui/library/LibraryScreenTest.kt:131`) that a tag's fill swaps between focused and unfocused.

**Acceptance Criteria:**
- Given a review with sticker 9 painted white, when the screen shows, then line `review:problem:0` shows tag "1" (`review:problem:0:tag`), stickers 8, 9 and 20 are invalid-outlined, stickers 8, 9 and 20 carry `StickerProblemsKey = [1]` and a visible tag "1" (`sticker:<i>:tag`), circle-shaped on 8 and 9 and square-shaped on 20 (exposed through a semantics key, e.g. `StickerProblemTagShapeKey`).
- Given two linked problems, when the Learner taps `review:problem:1`, then `review.selected` is that problem's first conflicting sticker, problem 2's tags (line and stickers) report focused and problem 1's do not, and the line has `selected = true`.
- Given the one-face view (w360dp) showing face D, when the Learner taps a linked line whose first conflicting sticker is on face U, then the net shows face U (that sticker is displayed and selected, and the net is scrolled into view without a further scroll call) and the face selector marks the piece's faces.
- Given TalkBack, then each linked line's content description reads e.g. "Problem 1: This corner shows two whites. Its piece: Up face, position 9; Right face, position 1; Front face, position 3. Conflicting stickers: Up face, position 9; Right face, position 1." and its click action label is "Select it on the net"; sticker 9's state description is "Conflicting sticker in problem 1"; sticker 20's is "Part of problem 1"; a global problem line has no click action.
- Given ManualCubeEditor, then its existing tests pass unchanged.

## Spec Change Log

### 2026-11-19 — Review pass 1 loopback (bad_spec)
- **Trigger:** intent-alignment audit: a piece sticker that is not conflicting (e.g. sticker 20) had no visible link to its message, and all problems' outlines looked the same, so "the stickers of that piece are highlighted distinctly from other outlines" held only for conflicting stickers.
- **Amended:** Design Notes "Label (tag)" — the number tag goes on every piece sticker, circle = conflicting, rounded square = rest of piece; added "Tap brings the net into view" and the `msg.scan.problem-description-stickers` wording; AC 1 and the one-face AC updated; test task gains an impossible-piece screen test and a focus pixel assertion.
- **Known-bad state avoided:** numbered lines whose piece shows unnumbered outlines indistinguishable from other problems'; a tapped selection left scrolled off screen; impossible-piece descriptions reading the sticker list twice.
- **KEEP:** attempt 1 is saved at `spec-gh-199-attempt-1.patch` (this folder). Keep its domain changes (`ReviewProblem.piece`, `PieceCheck`, `ScanReview.outlined`) and domain tests, the five content keys, `StickerProblemMark` / `problemMarks` default-null design (editor untouched), `StickerProblemsKey` / `StickerProblemFocusedKey`, focus state (saved index, fallback to first linked, reset on paint), `ProblemLine` click/selected/onClickLabel semantics, and the four screen tests (adjusting only what the amendment changes). Starting from `git apply` of that patch is fine.

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 16 findings — high 0, medium 3, low 5, false 6, maybe-false 2
- findings:
  - `[false]` `[reject]` blind: impossible-center rows are actionable but centers are locked — ScanReview forces every center to its home color (`ScanReview.kt` stickers init), so `ImpossiblePiece(CENTER)` can never be produced in a review.
  - `[medium]` `[bad_spec]` blind: tapping a line leaves the net scrolled out of view — the net sits above the problem list in a vertical scroll; amended Design Notes "Tap brings the net into view" and the one-face AC.
  - `[low]` `[defer]` blind: "Check the outlined piece." appended to global failures — pre-existing `instruction()` behavior, unchanged here; deferred.
  - `[low]` `[bad_spec]` blind: impossible-piece TalkBack line reads the same sticker list twice — real when piece = positions; amended with `msg.scan.problem-description-stickers`.
  - `[maybe-false]` `[reject]` blind: row exposes contentDescription and child text twice — TalkBack speaks contentDescription in place of text for a merged node; would settle with an on-device TalkBack check; if true only low, and hiding the child text breaks the visible-text assertion; rejected.
  - `[false]` `[reject]` blind: focus-reset test paints sticker 6 red — the test still proves focus resets to problem 1 after a paint; fixture choice is not a defect.
  - `[false]` `[reject]` edge: requireNotNull(strings) with marks and no strings — the only caller passes both; a loud failure on an unreachable misuse is correct.
  - `[medium]` `[bad_spec]` edge: tapped sticker stays off screen — same root cause as the blind scroll finding; same amendment.
  - `[low]` `[reject]` edge: overlapping problems can leave a line with no visible tag on shared stickers — needs a problem whose stickers are all shared with a lower-numbered one (rare, needs valid counts); fix adds per-sticker multi-number layout; the amended all-piece-sticker tag reduces it further.
  - `[false]` `[reject]` edge: repainting the same color keeps focus — problems are unchanged, so the kept focus is still valid; no bad outcome.
  - `[low]` `[bad_spec]` vgap: no screen test for an impossible-piece row (pre-verified gap) — added to the amended test task.
  - `[medium]` `[bad_spec]` vgap: no rendered assertion of the focused/unfocused tag fill (pre-verified gap) — added a `captureToImage` assertion to the test task. Grouped with intent: semantics-only tests.
  - `[medium]` `[bad_spec]` intent: non-conflicting piece stickers carry no visible link and outlines look alike across problems — root cause of the loopback; Design Notes "Label (tag)" amended.
  - `[low]` `[bad_spec]` intent: tests check semantics keys, not rendering — grouped with the vgap pixel finding.
  - `[false]` `[reject]` intent: compact view does not follow the default focus on first show (reading C1) — "each message" cannot all switch the one view at once, so the switch is the link's activation (tap), which the diff implements.
  - `[maybe-false]` `[reject]` intent: global problems have no link (reading A1) — EXPERIENCE.md Invalid entered state gives non-piece errors "their own lines"; the intent cites that pattern; nothing to link to.

### 2026-11-19 — Review pass (loop 1)
- verdicts: 19 findings — high 0, medium 1, low 6, false 8, maybe-false 4
- findings:
  - `[medium]` `[patch]` blind: a white-white-yellow corner (two problems on one piece) showed only problem 1's number on all three stickers — tag choice now prefers a problem the sticker conflicts in (`StickerReviewNet.kt` tagNumber), so sticker 20 shows "2"; test extended. Deviates from the Design Notes sentence "shows the focused problem's number if it is one of them, else the lowest" only for stickers that conflict in a different problem.
  - `[low]` `[defer]` blind: "Check the outlined piece." on global failures — carried: pre-existing `instruction()` wording, already deferred.
  - `[maybe-false]` `[reject]` blind: TalkBack focus stays on the row after activation — the selection is announced by the polite live region `review:selected`; whether TalkBack users must swipe back needs an on-device check; if true only low, and Compose has no direct API to move accessibility focus.
  - `[low]` `[reject]` blind: tag covers part of the rings on small cells — cosmetic; the tag sits only in the top-start corner and the rings stay visible on the rest of the perimeter.
  - `[low]` `[patch]` blind: short-window scroll branch untested — added `in a window shorter than the net a linked line scrolls the selected row into view` (w360dp-h240dp). Grouped with vgap 1.
  - `[low]` `[reject]` blind: shape not pixel-checked — the shape and its semantics key come from the same `isConflict` value in `ProblemTag`; a pixel shape test is more than a direct fix for a regression that would have to edit that one line.
  - `[low]` `[reject]` blind: `review.problems` recomputed several times per composition — negligible cost (20 pieces, 54 stickers) on user-paced recompositions.
  - `[false]` `[reject]` edge: requireNotNull(strings) with marks and no strings — carried: the only caller passes both; loud failure on unreachable misuse.
  - `[false]` `[reject]` edge: empty `numbers` list throws — marks are built only for stickers inside some linked piece, so `numbers` is never empty.
  - `[false]` `[reject]` edge: repainting the same color keeps focus — carried: problems unchanged, focus still valid.
  - `[low]` `[patch]` vgap: short-viewport scroll target untested (pre-verified) — same new test as above.
  - `[low]` `[patch]` vgap: mixed-role sticker announcement untested (pre-verified) — overlapping-problems test now asserts sticker 20 reads "Conflicting sticker in problem 2. Part of problem 1".
  - `[maybe-false]` `[reject]` intent: global problems unlinked (A1) — carried: EXPERIENCE.md gives non-piece errors their own lines; nothing to link.
  - `[false]` `[reject]` intent: a shared sticker shows one number — grouped with the blind medium finding and patched there; the remaining single-number display is the focused/conflict number by design.
  - `[false]` `[reject]` intent: compact view does not follow default focus on first show (C1) — carried: the switch is the link's activation.
  - `[maybe-false]` `[reject]` intent: TalkBack may double-read row description and child text (E2) — carried: needs an on-device check; if true low, and hiding the text breaks the visible-text assertion.
  - `[false]` `[reject]` intent: face names come from `R.string`, not content strings (F1) — reuses the existing `FACE_NAMES` resources already used by `stickerArgs` on this screen; no new strings.xml text, which is what `checkLearnerText` guards.
  - `[maybe-false]` `[reject]` intent: tests sit mostly at semantics level — one pixel check covers the focus fill; ring and shape rendering would settle only with screenshot tests; if a gap, low.
  - `[false]` `[reject]` intent: z-order change draws content after rings when marks exist — intended so the tag sits on top of the rings; editor (no marks) unchanged.

## Design Notes

Visual choices the UX docs leave open, settled from DESIGN.md tokens and the existing styles:
- **Piece outline:** every sticker of a linked problem's piece gets the existing invalid-piece outline (3dp `sticker-highlight` + 1dp `cubie-body` inner, `drawRing`) — DESIGN.md "invalid piece … plus its label".
- **Label (tag):** the problem number, about 40% of the cell (min 16dp), at the sticker's top-start corner, drawn on **every** sticker of the piece (DESIGN.md "plus its label"), so each piece is told apart from other problems' outlines by its number. Shape says the sticker's role: a **circle** on a conflicting sticker, a **rounded square** (`Radii.xs`) on a sticker that is only part of the piece. Focused: `sticker-highlight` fill, numeral in `cubie-body` (~7:1, the cube-highlight contrast). Not focused: `cubie-body` fill, 1dp `sticker-highlight` edge and numeral. The message line's tag is the circle. A sticker in several problems shows the focused problem's number if it is one of them, else the lowest; its shape follows its role in the shown problem.
- **Tap brings the net into view:** after a line tap the scrolling column brings the net (the selected sticker) into view (`BringIntoViewRequester` or equivalent), so the selection is visible without the Learner scrolling back up.
- **TalkBack wording when piece = conflicts** (validator impossible pieces): the line uses `msg.scan.problem-description-stickers` ("Problem {number}: {problem} Stickers: {stickers}.") so the same sticker list is not read twice.
- **Focus:** the default is the first linked problem (the one the instruction line names, "Check the outlined piece."); tapping a line focuses it and selects its first conflicting sticker, which also gets the normal dashed `accent` selected ring. No dimming, no new colors.
- Message lines keep `status-invalid` text color; a linked line is a full-width row at least `touch-min` tall.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test :app:testDebugUnitTest --tests '*ScanReview*' --tests '*PieceCheck*' --tests '*ManualCubeEditor*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** Scan review now links every piece-specific validation message to the net. Each `ReviewProblem` carries its whole `piece` plus its conflicting `positions`; the screen numbers linked problems, tags every piece sticker with the number (circle = conflicting, rounded square = rest of the piece; filled when focused), outlines whole pieces, and makes each numbered line a button ("Select it on the net") that focuses the problem, selects its first conflicting sticker, switches the one-face view and scrolls the net into view. TalkBack: line descriptions name the piece and conflicting stickers; stickers announce "Conflicting sticker in problem N" / "Part of problem N". Global problems stay plain lines.

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt` — `ReviewProblem.piece`, filled for repeat/opposite problems.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` — validator problems' piece = positions; `outlined` = union of pieces.
- `core/domain/src/main/resources/content/strings/en.json` — six TalkBack keys.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` — optional `problemMarks`, `ProblemTag`, problem semantics keys (editor unchanged).
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` — numbering, focus state, `ProblemLine`, bring-into-view.
- Tests: `PieceCheckTest.kt`, `ScanReviewTest.kt`, `ScanReviewScreenTest.kt` (38 tests).

**Review findings:** pass 1 looped back once (bad_spec: piece stickers lacked a visible link; spec amended, code re-derived). Pass 2 (loop 1): 3 patch entries applied (medium: shared-piece tag number; low: short-window scroll test, mixed-role announcement test); 1 deferred (global-error instruction wording, pre-existing); all rejected findings and reasons are in the Review Triage Log.

**Follow-up review recommendation:** false — this pass patched 1 medium and 2 low entries (0 high), below the first-pass threshold; patched counts by verdict: high 0, medium 1, low 2.

**Verification:** `./gradlew :core:domain:test :app:testDebugUnitTest --tests '*ScanReview*' --tests '*PieceCheck*' --tests '*ManualCubeEditor*'` — pass (ScanReviewScreenTest 38/38). `./gradlew spotlessCheck build` — BUILD SUCCESSFUL.

**Residual risks:** Not tried on a device; TalkBack reading order/double-read and accessibility focus after activating a line are unverified on hardware. The tag rule for a sticker in several problems now prefers a conflicting problem's number (refines the Design Notes sentence). Global-error instruction wording deferred.

