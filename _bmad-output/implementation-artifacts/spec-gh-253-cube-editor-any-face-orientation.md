---
title: 'GH-253 Cube editor: accept any face orientation; ask only when it cannot resolve'
type: 'feature'
created: '2026-11-20'
status: done
baseline_revision: '0e21d5f51c3145abbbc79fb38c3e4e5e0065dc57'
review_loop_iteration: 3
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-20-v1-1-promotion.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/DESIGN.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Problem dot on editor face tabs is not asserted to be sticker-highlight (never red).
    evidence: |-
      EditorFaceTab draws the dot with tokens.stickerHighlight but its semantics are cleared, so no test can observe the color; a regression to statusInvalid would pass.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt (EditorFaceTab)
    severity: low
  - summary: >-
      Turn search and repeated validation run on the main thread per paint.
    evidence: |-
      Unverified. ScanAssembler.orient enumerates up to 4096 grids when counts are right and the entry is invalid, plus several CubeValidator calls per paint. Settle by timing a paint on the Lenovo Tab M10 Plus 3rd Gen.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt (fit / paint)
    severity: medium (unverified)
  - summary: >-
      The update-cue ring is verified only through StickerCueRingKey semantics, not the drawn border.
    evidence: |-
      The semantics value is set independently of .border(CUE_RING, ink, CircleShape); deleting the border or passing another color keeps ManualCubeEditorCompactTest and ThemeTokensTest green. Pinning it needs a screenshot or pixel test, which the repo lacks for these cues.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt (ReviewSticker cue dot)
    severity: low
---

<intent-contract>

## Intent

**Problem:** The Manual Cube Editor demands one fixed, unseen hold per face; a face entered at 90/180/270° makes the cube unacceptable with a generic message. Errors show before faces are entered, messages are generic, auto-advance is invisible, and the update-cue ✓ vanishes on green.

**Approach:** Meet the #253 amendment ACs of Story 3.5 (epics.md): resolve per-face turns through a new colour-only `ScanAssembler` entry point, accept silently when unique (redraw + `meta` note), otherwise name the face and its hold; gate errors behind "{n} faces left to enter" on a fresh entry; specific count/piece messages with numbered markers and tab warning dots; face-done ✓ and line; pulsing cursor; update-cue ring.

## Boundaries & Constraints

**Always:** Editor stays a non-session (AD-3); `CubeState` only via `CubeValidator`; Learner text in `en.json` via `Text(key,args)`; auto-advance behaviour unchanged; reduce-motion honoured; TalkBack via polite live regions.

**Never:** Paint mode (#249), Scan review changes beyond shared-net parameters, Placement result changes (#251/#252), new strings in `strings.xml`, auto-closing the editor on resolve.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Unique turn | all faces entered, Up entered 180° | Up redrawn turned, meta "We turned the Up face to match the others." (TalkBack "Up face turned to match"), Accept enabled | none |
| As-entered valid | valid in editor hold | Accept enabled, nothing turned (base AC 3 kept even if another turn is also valid) | none |
| Ambiguous / none | counts OK, 0 or ≥2 valid turnings | instruction line "Check the Up face: hold green toward you and look at the top.", tab dot, Accept disabled | names first involved face |
| Fresh, faces untouched | default solved, Right/Back/Down untouched | "3 faces left to enter: Right, Back, Down.", no piece/count lines, Accept disabled | none |
| Wrong counts | all entered | "Red: 10 (one too many) · Green: 8 (one missing)" | Accept disabled |
| Impossible piece | all entered | "The corner between Up, Front and Right shows two greens." + numbered markers + tab dot | Accept disabled |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- AD-9 home of per-face turns. Reuse private `enumerate` (option grids per face → distinct valid states + best-legal candidates), `legal`/`illegalFaces`, `gridOf`, `rotateClockwise` (clockwise quarter turns of a row-major 3×3), `sameFace`, and `NET_NEIGHBORS` (top, right, bottom, left neighbour of each net slot). `analyze`/`assembleRetake` show how options are built.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeValidator.kt` -- `problems()`; `WrongColorCounts.counts` (all six colors), `ImpossiblePiece.positions`, `TwistedCorner`/`FlippedEdge`/`ParityViolation` (global, keep their existing lines).
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/PieceCheck.kt` -- local repeat/opposite checks returning `ReviewProblem(reason, positions, piece)`; source for named piece lines. `Facelets.kt` `FaceletTables.CORNER_FACELETS`/`EDGE_FACELETS` give each piece's sticker indices (sticker / 9 = URFDLB face).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` L38-45 -- how Scan review merges PieceCheck + validator problems (pattern only; do not change).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- editor state: `facelets`, `selected`, `face`, derived `problems`/`outlined`/`facesWithProblems`/`validState`, `paint` (auto-advance via `StickerOrder.next`), `Saver` (listSaver of ints).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- composable: `FaceSelector`/`FaceTab` (today a red "!" via `R.string.editor_face_with_problems`), problem lines (`editor:problem:$i`, polite live region), `SelectedStickerLine`, Accept (`editor:accept`), `onChange(Facelets)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- shared net. Selected ring already pulses (`stickerPulsing`, static under reduce motion); update-cue ✓ dot at L463-474 (`sticker:$index:cue`, `accent` dot, `on-accent` ✓) needs the ring; `problemMarks`/`StickerProblemMark`/`ProblemTag` already render numbered markers.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` L236-249, L342, L460-495 -- marker numbering (`linkedIndices`, `problemNumbers`, `marks`) and `ProblemLine` with its number tag: the pattern to copy for the editor.
- `core/domain/src/main/kotlin/dev/cubentia/domain/appearance/StickerAppearance.kt` -- `glyphInk` per color (STICKER_WHITE on RED/BLUE, CUBIE_BODY on others): the update-cue ring color (PO 2026-11-20).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `SolvePhase.Editing(initial, error, note)`, `accepted` (default solved until first accept), `draft`/`onEdit`/`leaveSolveTab`, `enterColors`, `onScanOutcome(FellBackToEditor)`, `scanDenied`, `cancel`.
- Callers: `ui/solve/SolveScreen.kt` L118, `ui/placement/PlacementScreen.kt` L78, `ui/recognize/RecognizeScreen.kt` L63, `ui/lesson/LessonScreen.kt` L982 (caller's cube: `session.expected`).
- `core/domain/src/main/resources/content/strings/en.json` -- Learner text (ICU plurals supported, e.g. `msg.validation.piece-repeats.*`). Existing `strings.xml` names stay usable as args: `editor_face_u..b` ("Up", "Right", …), `color_*` ("Red", …).
- Tests: `core/domain/src/test/.../recognition/ScanAssemblerTest.kt`, `app/src/test/.../ui/editor/ManualCubeEditorStateTest.kt`, `ManualCubeEditorTest.kt`, `ManualCubeEditorCompactTest.kt`, `EditorFixtures.kt`, `app/src/test/.../ui/theme/ThemeTokensTest.kt` L102-115 (contrast helper `assertAtLeast`), `app/src/test/.../content/ContentStringsTest.kt`, `app/src/test/.../ui/solve/SolveFlowViewModelTest.kt`; a11y audits `ui/a11y/ScreenAudit.kt` L109, `StateAudit.kt` L315.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- Add public `fun orient(facelets: Facelets): FaceTurnFit` and `fun netNeighbors(face: Face): List<Face>` (top, right, bottom, left, from `NET_NEIGHBORS`). `FaceTurnFit` is a sealed result: `Resolved(facelets: Facelets, turns: Map<Face, Int>)` (clockwise quarter turns, 1..3, of each face whose colors changed, taken from the chosen assignment, never re-derived from colors; a `turned: List<Face>` convenience = its keys in URFDLB order) and `Unresolved(valid: Int, involved: List<Face>)`. Rules: as-entered valid → `Resolved(facelets, emptyList())`; otherwise each face's 9 colors (center fixed) get 4 clockwise options via `rotateClockwise`, run through `enumerate`; exactly one distinct valid state → `Resolved` with that state's facelets and `turns` for the faces whose colors differ from the input; else `Unresolved` with `valid` = distinct valid count and `involved` = (valid ≥ 2: differing faces; valid 0: union of `illegalFaces` over the best candidates, falling back to differing faces; never padded to all six, so `involved` may be empty, e.g. a twisted corner, flipped edge or parity error with every piece legal) in URFDLB order. Pure, no exceptions -- one AD-9 entry point for colour-only input.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanAssemblerTest.kt` -- Tests: valid input returns itself with no turned faces; a solvable cube with U turned 90/180/270 and with two faces turned resolves to the original with `turns` exact (including a face whose colors are 180°-symmetric, where the turn count must come from the assignment); a symmetric-face case where another turning is also valid still keeps the as-entered state when it is valid; an input with ≥2 distinct valid turnings is `Unresolved` with `valid ≥ 2` and the differing faces; a non-resolvable input (e.g. two swapped stickers) is `Unresolved(0, …)` with non-empty involved faces; `netNeighbors` matches the table.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- Constructor gains `fresh: Boolean = false` and `initialSet: Set<Int> = emptySet()`. Track `set: Set<Int>` (stickers painted in this editor, including a same-color tap). `entered(face)` = not fresh, or any sticker of that face in `set`; `unentered: List<Int>` faces in Learner order U, F, R, B, L, D (the matrix's "Right, Back, Down"); `complete(face)` = all 8 non-center stickers in `set`. While `unentered` is non-empty: `problems`/`outlined`/marks/tab dots are empty and `validState` is null. After each `paint`, when every face is entered, the stickers are invalid and every color appears exactly 9 times (a turn never changes counts, so wrong counts never call `orient`), call `ScanAssembler.orient`; on `Resolved` with turns replace `facelets` with the turned ones, and turn each turned face's `set` flags, the `selected` sticker and the just-painted sticker (exposed as `cueIndex` for the update cue) by that face's exact turn count from `turns`; expose `turned: List<Int>` (an event, cleared by the next paint; may be dropped on recreation). `checkFace: Int?` is derived from the current stickers, not stored: non-null only when every face is entered, the stickers are invalid, counts are right and `orient` is `Unresolved`, then the first involved face in the same U, F, R, B, L, D order (so it survives recreation and a Solve tab switch); null when `involved` is empty, so a twist, flip or parity error with no illegal piece names no face and its own line explains it. `paint` also records a `faceDone: FaceDone?` event (`face`, `next`, a serial) when this paint makes `complete(face)` turn true and auto-advance moves the selection to another face. On a fresh entry select the first editable sticker in `StickerOrder` that is not in `set` (index 0 on a new entry; null when every sticker is set) so the pulsing cursor shows at once and a restored draft never lands on an entered sticker. Saver persists colors, selected, face, fresh, set and turned (backward compatible restore order is not required). Auto-advance order is unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- Params gain `fresh: Boolean = false`, `initialSet: Set<Int> = emptySet()`; `onChange` becomes `(Facelets, Set<Int>) -> Unit` (stickers and the set stickers); the update cue plays on the state's `cueIndex` (the painted sticker, moved with its face when a turn applied); `rememberSaveable` keyed on `initial` and `fresh`. Add, under the net, an **instruction line** (`editor:instruction`, polite live region): fresh with unentered faces → `msg.editor.faces-left`; else `checkFace` set → the face's hold line; otherwise absent. Add the **turned note** (`editor:turned`, `meta` style = `bodySmall` in `ink-secondary`, polite live region) with `msg.editor.turned`. Problem lines become specific (see Design Notes) and numbered like Scan review; stickers of numbered problems get `problemMarks` (focusedNumber null) and stay outlined. **Face-done line** (`editor:faceDone`, `meta`): shows `msg.editor.face-done` for 1.5 s per `FaceDone` serial in a slot whose height is always reserved (no layout shift); its semantics are a polite live region whose contentDescription is `msg.editor.face-done-spoken`. **Face tabs** (both layouts; in the full-net layout the same row sits above the net as a status strip, see Design Notes): ✓ in `ink-secondary` when complete (stateDescription `msg.editor.face-complete`); a small `sticker-highlight` dot (never red; replaces the "!" text) when a numbered problem marks a sticker of that face or it is `checkFace` (stateDescription keeps `R.string.editor_face_invalid`); a turned face's tab adds stateDescription `msg.editor.face-turned`. Test tags `editor:face:$f:done`, `editor:face:$f:dot`. **Hold bands** (one-face layout only): a 6dp band beside each edge of the shown face in the neighbour center's sticker color (`StickerAppearance` fill, glyph when color-blind glyphs are on), from `ScanAssembler.netNeighbors`, tagged `editor:hold:top|right|bottom|left` with `stickerColor` semantics, hidden from TalkBack. Accept stays enabled only while `validState` is non-null.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` + `ManualCubeEditorState.kt` (full-net status strip, PO 2026-11-20) -- In the full-net layout render `EditorFaceSelector` above the net too (same tabs, tags, ✓, dot and stateDescriptions). Its current tab is the face of the selected sticker (none when nothing is selected). Tapping a tab calls a new `ManualCubeEditorState.selectFace(face)` that selects that face's first editable sticker (`face * 9`), so the pulsing cursor moves there; in one-face mode tabs keep calling `showFace`. No hold bands in the full-net layout.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- Update-cue dot gets a 1.5dp ring in the sticker's `StickerAppearance.glyphInk`; expose it as a new semantics key `StickerCueRingKey` (Color) on `sticker:$index:cue`. No other behavior change for Scan review.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `SolvePhase.Editing` gains `fresh: Boolean = false` and `set: Set<Int> = emptySet()`. `fresh` is true for scan fall-back, camera denial, and `enterColors`/`cancel` while no cube has been accepted in this ViewModel; false after an accept/confirm. `onEdit(facelets, set)` (both parameters required, no default) keeps both in the draft; `leaveSolveTab` restores `initial` and `set` (fresh unchanged).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt`, `placement/PlacementScreen.kt`, `recognize/RecognizeScreen.kt`, `lesson/LessonScreen.kt` -- pass `fresh` (Solve: phase value and `initialSet = current.set`; Placement, Recognize: `true`; Lesson: `false`).
- `core/domain/src/main/resources/content/strings/en.json` -- add the keys in Design Notes; no new `strings.xml` entries (remove `editor_face_with_problems` if it becomes unused).
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorStateTest.kt` -- every I/O matrix row at state level, plus set/complete tracking, face-done event rule, preselect on fresh (first unset sticker after a restored draft), turned set-flag, selection and cueIndex rotation (including a 180°-symmetric face turned 270°), wrong counts never set `checkFace`, the Down face to check, `checkFace` present right after construction from a restored invalid draft, Saver round trip.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt` and `ManualCubeEditorCompactTest.kt` -- Robolectric UI: matrix rows at the composable surface (instruction text, turned note text, counts line text, named piece line text, numbered marker tags, Accept enabled/disabled); compact: tab ✓/dot tags and stateDescriptions, hold band colors for U (top B blue, right R red, bottom F green, left L orange), D (top F green, right R red, bottom B blue, left L orange) and B (top U white, right L orange, bottom D yellow, left R red), the Down check line text, no instruction line with wrong counts, face-done line text and spoken contentDescription after completing Up's 8th sticker and its disappearance after 1.5 s (`mainClock`), and the cue ring color per color. Update existing assertions on the "!" tab and the generic messages to the new behavior; never delete coverage.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt` (full-net strip) -- at a medium/expanded window: all six `editor:face:$f` tabs render above the net; a completed face shows `editor:face:$f:done`; a check-face entry shows `editor:face:$f:dot` on the face the instruction line names; a unique turn gives that tab the `msg.editor.face-turned` stateDescription; tapping the Back tab selects sticker Back 1 (`editor:selected` names it); no `editor:hold:*` node exists. State test for `selectFace`.
- `app/src/test/kotlin/dev/cubentia/app/ui/theme/ThemeTokensTest.kt` -- assert, for each of the six sticker colors, contrast(fill, glyphInk ring) ≥ 3.0.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModelTest.kt` -- fresh flag per entry path, draft keeps set stickers across `leaveSolveTab`.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt`, `AppShellPlacementTest.kt`, `AppShellRecognizeTest.kt` -- at each real entry point (Solve → Enter colors, scan fallback/camera denial, Placement fallback, Recognize fallback) assert the editor opens with `editor:instruction` "6 faces left to enter: Up, Front, Right, Back, Left, Down." and Accept disabled before any face is entered; in the Solve tab-switch test assert "5 faces left to enter: Front, Right, Back, Left, Down." before and after the switch once one Up sticker is painted.
- `app/src/test/kotlin/dev/cubentia/app/content/ContentStringsTest.kt` -- resolve every new `msg.editor.*` key, including `msg.editor.list-more`.
- Extra UI coverage (`ManualCubeEditorTest` / `ManualCubeEditorCompactTest`): the update cue plays on the moved sticker when the painted sticker's own face is turned (old index has no cue node); rendered text of an edge line (two face names) and of a turned note naming two faces; a rendered `msg.editor.piece-impossible` line; hold bands under `LayoutDirection.Rtl` keep top/right/bottom/left colors and sides; a twisted-corner entry (counts right, every piece legal) shows its twist line and no instruction line; the Down check line text above.

**Acceptance Criteria:**
- Given a compact window (one-face layout) and a fresh Solve entry, when the editor opens, then sticker Up 1 is selected and pulsing, the instruction line reads "6 faces left to enter: Up, Front, Right, Back, Left, Down.", no problem line shows, and Accept is disabled.
- Given every face entered with Up entered 180° from the editor's hold on an otherwise valid cube, when the last sticker is painted, then the Up face shows the turned colors, the turned note reads "We turned the Up face to match the others.", and Accept returns the original cube's `CubeState`.
- Given counts are right but the entry fits no valid turning, when validation runs, then the instruction line names the first involved face with its hold, that face's tab shows the dot, and the numbered problem lines and markers show.
- Given any sticker color, when its update cue shows, then the ✓ dot carries a 1.5dp ring in that sticker's glyph ink, with contrast ≥ 3:1 against the sticker fill.
- Given a medium/expanded window (full-net layout, e.g. Lenovo Tab M10 Plus 3rd Gen), when the editor shows, then a face-tab status strip sits above the net with the same ✓, warning dot and "Turned to match" state as the compact tabs, tapping a tab selects that face's first sticker, "Check the … face" marks that face's tab with the dot, and no hold bands are drawn.
- Given a caller's cube (Lesson physical mode), when the editor opens, then validation shows at once as before (no faces-left gating) and nothing is preselected.

## Spec Change Log

### 2026-11-20 — Review loop 1 (bad_spec)
- **Triggers:** wrong counts still ran the turn search and named a "Check the … face" line (edge-case hunter, intent audit: matrix row says "counts OK"); set flags re-derived the turn from colors, wrong for 180°-symmetric faces (blind hunter, edge-case hunter); a turn did not move the selection or the update cue (blind hunter, edge-case hunter); `checkFace` was an event lost on recreation / Solve tab switch (blind hunter); a restored fresh draft re-selected Up 1 (blind hunter, edge-case hunter); entry points not verified at shell level, Down line and list-more untested (verification gap); spec copy for faces-left/turned unusable with the arg checker.
- **Amended:** ScanAssembler task (`Resolved.turns` map), State task (counts gate, exact-turn remap of set/selected/cueIndex, derived `checkFace`, first-unset preselect), Editor task (cue on `cueIndex`), SolveFlowViewModel (`onEdit` set required), test tasks (shell entry points, tab switch, Down, D/B bands, symmetric turn, list-more), Design Notes (counts gate; working ICU copy; `editor_face_with_problems` kept).
- **Known-bad state avoided:** a hold instruction shown for an entry no turn can fix; set/✓ flags or cursor pointing at the wrong sticker after an auto-turn; "Check the … face" vanishing after a tab switch; gating of fresh entry points not covered by tests.
- **KEEP:** everything else from attempt 1 worked and passed `spotlessCheck build`: `ScanAssembler.orient`/`netNeighbors` reusing `enumerate`/`illegalFaces`; `EditorProblems.kt` (counts line, PieceCheck-named pieces with U/D→F/B→R/L face order, impossible pieces not already named, global lines); numbered marks via `StickerProblemMark` with focusedNumber null; `EditorFaceSelector`/`EditorFaceTab` (✓ in ink-secondary, sticker-highlight dot, stateDescriptions); `HoldBands` inside a padded box; `FaceDoneLine` with a reserved slot and spoken contentDescription; cue ring via `StickerCueRingKey`; `EditorFixtures.enterEveryFace()` for shell tests; `SolveFlowViewModel` `hasAccepted`/`draftSet`. A reference patch of attempt 1 is at `/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/4e69ffcd-c332-4b0e-a143-2c4e3edd307d/scratchpad/gh253-attempt1-reference.patch` (apply with `git apply`, then make the amendments above).

### 2026-11-20 — Review loop 2 (bad_spec)
- **Triggers:** `orient` padded `involved` to all six faces, so twist/flip/parity entries showed "Check the Up face: hold green toward you…" though no turn can fix them (blind hunter, edge-case hunter); the Down hold line "hold green toward you and look at the bottom" reads 180° off when peeking underneath, against the net (F above D) (blind hunter); untested UI paths: cue on a turned sticker, two-name and four-name lists, impossible-piece line, RTL bands (verification gap, intent audit).
- **Amended:** ScanAssembler task (no all-six fallback; `involved` may be empty), State task (`checkFace` null when nothing is involved), Design Notes (Down uses `check-face-side` with green on top and yellow toward you; `check-face-bottom` dropped), test tasks (extra UI coverage list).
- **Known-bad state avoided:** a hold instruction naming an arbitrary face for errors no turn can fix; a Down hold that leads the Learner to enter Down upside down.
- **KEEP:** all of attempt 2 (it passed `spotlessCheck build`): exact per-face `turns`, counts gate, derived `checkFace`, `cueIndex` and selection remap, first-unset preselect, required `onEdit` set, shell entry-point tests, `checkFaceText` helper. Also: in `problemText` use the stickers-per-color constant (9, `PER_COLOR`), not `PER_FACE`; drop the dead `if (counts != null) return@forEach` branch in `EditorProblems.of` (the validator never reports an impossible piece alongside wrong counts). Transient reference patch of attempt 2: `/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/4e69ffcd-c332-4b0e-a143-2c4e3edd307d/scratchpad/gh253-attempt2-reference.patch` (apply with `git apply`, then make the loop-2 amendments).

### 2026-11-20 — Review loop 3 (bad_spec)
- **Triggers:** `msg.editor.piece-impossible` ("shows colors no real {piece} has") is false for a duplicated real piece, which `CubeValidator.faults` also reports as `ImpossiblePiece` (edge-case hunter); `ManualCubeEditorState.fit` counts colors against `PER_FACE` (blind hunter).
- **Amended:** Design Notes copy of `msg.editor.piece-impossible` ("The {piece} between {faces} has colors that don't fit the rest of the cube."); Design Notes `PER_COLOR` rule extended to the counts gate in `fit`.
- **Known-bad state avoided:** an editor line telling the Learner a corner or edge has colors no real piece has when its colors are real but duplicated.
- **KEEP:** all of attempt 4 (it passed `spotlessCheck build` and the targeted tests): attempt 3 in full plus the full-net status strip (`EditorFaceSelector(state, current, columns, strings, onSelect)` above the net in both layouts, current = face of the selected sticker in the full net, `ManualCubeEditorState.selectFace`, no hold bands in the full net, five full-net tests in `ManualCubeEditorTest`, the three updated `ManualCubeEditorCompactTest` wide-window tests, the `selectFace` state test). Only change the `piece-impossible` copy in en.json and any test asserting it, and use `PER_COLOR` in `fit`. Transient reference patch of attempt 4: `/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/4e69ffcd-c332-4b0e-a143-2c4e3edd307d/scratchpad/gh253-attempt4-reference.patch` (apply with `git apply`, then make the loop-3 amendments).

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 35 findings — high 0, medium 13, low 18, false 3, maybe-false 1
- findings:
  - `medium` `bad_spec` BH: auto-turn leaves the cursor on the wrong sticker — real: `turnedSet` rotated flags only; spec amended (exact-turn remap of `selected`).
  - `low` `reject` BH: face can be auto-rotated mid-entry / on caller cubes — with the new counts gate a mid-entry turn needs the default stickers to give exact counts and yield the only valid cube; a unique fit is by definition the Learner's cube. Not worth extra gating.
  - `medium` `bad_spec` BH: `turnedSet` re-derives the turn from colors, wrong for 180°-symmetric faces — real (smallest matching turn wins); spec amended to carry `turns` from the assignment.
  - `medium` `bad_spec` BH: turn search on the UI thread on every paint, also with wrong counts — wrong-count runs are pointless; spec amended with the counts gate (4096 grids only on counts-OK invalid input).
  - `medium` `bad_spec` BH: turned note and face-to-check line lost on Solve tab switch — `checkFace` lost is real; spec amended to derive `checkFace`; the turned note is an event (accepted loss).
  - `medium` `bad_spec` BH: restored fresh editor re-selects Up 1 — real (`FIRST_EDITABLE` always); spec amended to first unset sticker.
  - `false` `reject` BH: impossible piece can end with no line/outline — `ImpossiblePiece.positions` holds every sticker of each bad slot, so `piece.all { in positions }` holds per bad slot; counts-wrong never yields ImpossiblePiece (validator stops); centers are fixed in the editor.
  - `low` `reject` BH: separators, `.lowercase()` and piece ids built in Kotlin — English output correct; piece-id args are existing precedent (`msg.validation.impossible-piece`); moving separators to content adds keys for no user-visible gain.
  - `low` `bad_spec` BH: shipped faces-left/turned strings differ from spec copy — output identical; spec Design Notes amended to the working ICU form.
  - `low` `reject` BH: 6sp glyph on 6dp hold bands is illegible — DESIGN.md mandates a 6dp band with its glyph; band color is the primary cue and the instruction line carries the hold in text.
  - `low` `reject` BH: no contrast test for the tab dot — `sticker-highlight` is the existing highlight token already contrast-tested on cubie-body; tab surface check is cosmetic hardening.
  - `maybe-false` `reject` BH: several polite live regions may announce together — would need TalkBack device check; if true only low (polite regions queue).
  - `low` `bad_spec` BH: `onEdit(facelets, set = emptySet())` default silently clears the set — spec amended: parameter required.
  - `false` `reject` BH: Saver restore crashes on a 56-int state from the previous build — saved instance state does not survive an app update (process and task are reset), so an old-format bundle is never restored.
  - `low` `bad_spec` BH: test gaps (D/B bands, StateAudit, Lesson ✓) — spec amended with D/B band and Down tests; StateAudit runs unchanged (non-fresh default); Lesson ✓ only after painting is the defined behavior.
  - `medium` `bad_spec` ECH: wrong counts still name a face to check — real; spec amended (counts gate).
  - `medium` `bad_spec` ECH: 4^6 enumeration on UI thread each paint — same root as counts gate; amended.
  - `low` `reject` ECH: fresh sequential entry runs problems/turns once Back's first sticker is set — AC gates only untouched faces ("faces not yet touched"); with the counts gate no turn or face question appears while counts are off.
  - `medium` `bad_spec` ECH: turned face holding the selection leaves the cursor on a moved color — same root as BH cursor; amended.
  - `medium` `bad_spec` ECH: symmetric face flags rotated the wrong way — same root as BH turn re-derivation; amended.
  - `low` `bad_spec` ECH: update cue plays on the painted index after its face turned — real; amended (`cueIndex`).
  - `medium` `bad_spec` ECH: restored fresh draft re-selects Up 1 — same root as BH; amended.
  - `medium` `bad_spec` VG: no shell-level check that Placement/Recognize/Solve open the editor fresh — pre-verified; spec amended with AppShell assertions.
  - `medium` `bad_spec` VG: Solve tab switch does not verify set stickers survive at screen level — pre-verified; spec amended.
  - `low` `bad_spec` VG: Down check line untested — pre-verified; spec amended.
  - `low` `bad_spec` VG: `msg.editor.list-more` untested — pre-verified (filed defer); folded into the ContentStringsTest task since the spec is being amended.
  - `low` `reject` VG other: Lesson caller cube runs orient mid-correction — a unique fit is the only valid cube, and with the counts gate a single wrong sticker never triggers it.
  - `low` `reject` IA1: gate lifts after one sticker per face (reading A1) — chosen from the AC's "faces not yet touched" and the matrix's "untouched"; counts gate removes the noisy face question.
  - `medium` `bad_spec` IA2: instruction line with wrong counts despite matrix "counts OK" — same root as ECH counts gate; amended.
  - `false` `reject` IA3: matrix rows list one line while UI shows several — matrix rows name the expected line, not an exhaustive screen; problem lines alongside the instruction line follow AC 4.
  - `low` `reject` IA4: "Up face turned to match" only on the tab state — documented reconciliation in Design Notes; full-net layout reads the AC sentence.
  - `low` `reject` IA5: tab dot and hold bands only in one-face layout — tabs exist only there; full net shows the hold layout; documented in Design Notes.
  - `low` `reject` IA6: Learner text fragments outside en.json — same as BH separators.
  - `low` `reject` IA7: cue ring also on Scan review; `rotateClockwise` public — the ring is in the shared net by intent ("legible on every sticker color"); public helper is harmless.
  - `low` `bad_spec` IA8: tests reach rows through fixtures, not real routing — same root as VG shell checks; amended.
- Route: bad_spec → spec amended (Spec Change Log, loop 1), code reverted, attempt-1 reference patch kept at `/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/4e69ffcd-c332-4b0e-a143-2c4e3edd307d/scratchpad/gh253-attempt1-reference.patch`.

### 2026-11-20 — Review pass 2
- verdicts: 30 findings — high 0, medium 4, low 19, false 5, maybe-false 2
- findings:
  - `low` `reject` IA: touched-gate lifts on unchanged default via enterEveryFace — carried (IA1, pass 1): AC wording "faces not yet touched".
  - `false` `reject` IA: unique fit not applied on opening — resolution runs inside `paint` before `onChange`, so a saved draft is already turned; a Lesson caller cube opens on the valid expected state.
  - `low` `reject` IA: TalkBack sentence only as tab metadata — carried (IA4, pass 1).
  - `low` `reject` IA: tab dot only in compact layout — carried (IA5, pass 1).
  - `false` `reject` IA: wrong-counts screen shows more than the row's line — carried (IA3, pass 1).
  - `low` `bad_spec` IA: validator-only `piece-impossible` template never rendered in a UI test — folded into loop-2 test list.
  - `low` `reject` IA: cue ring also changes Scan review — carried (IA7, pass 1).
  - `false` `reject` IA: fresh preselect breaks "auto-advance unchanged" — the advance order is unchanged; the preselect is the spec'd pulsing cursor from the AC.
  - `medium` `bad_spec` VG: cue on a turned sticker never checked in the UI — pre-verified; folded into loop-2 test list.
  - `low` `bad_spec` VG: two-name and four-name lists only tested as raw templates — pre-verified; folded into loop-2 test list.
  - `low` `defer` VG: "dot never red" not asserted — pre-verified, filed defer; needs a new semantics key for one token choice.
  - `medium` `bad_spec` VG: hold bands under RTL untested — pre-verified; folded into loop-2 test list.
  - `low` `bad_spec` VG other: dead `counts != null` branch for ImpossiblePiece — validator returns counts alone (CubeValidator.kt:104); KEEP note asks to drop it.
  - `false` `reject` BH: faces-left order differs from auto-advance order — the matrix fixes "Right, Back, Down" (Learner order); auto-advance order is bound unchanged by the contract.
  - `low` `reject` BH: gate opens mid-last-face — carried (ECH3/IA1, pass 1).
  - `medium` `bad_spec` BH: `checkFace` falls back to Up for twist/flip/parity — real: `orient` padded to all six; amended (no fallback).
  - `medium` `bad_spec` BH: Down hold text reads 180° off — real against the net (F above D); amended to "hold green on top and turn yellow toward you".
  - `low` `reject` BH: no face-done line for the last face or out-of-order completion — the spec'd rule follows the AC's "→ Right" advance; the tab ✓ and the validation/Accept state mark completion; new copy would be unsettled.
  - `low` `reject` BH: TalkBack users never get the hold before an error — the AC requires TalkBack to read the instruction line, which carries the hold when it matters; bands are decorative per DESIGN.md.
  - `maybe-false` `defer` BH: orient/validate several times per paint on the UI thread — would need timing on the Lenovo minimum device; if true medium.
  - `low` `reject` BH: key rewrite by string replace — PieceCheck emits exactly the 7 mapped keys, all resolved in ContentStringsTest; a new key would be a deliberate change.
  - `low` `reject` BH: spec says `emptyList()` for a Map and keeps a temp path — fix is to edit this build's spec (rejected by rule); the path is labelled transient.
  - `low` `bad_spec` BH: `PER_FACE` used for stickers per color — both 9; KEEP note asks for `PER_COLOR`.
  - `low` `reject` BH: "3 missing" mixes digits with words — spec copy uses words only for one and two; counts off by 3+ are rare.
  - `low` `reject` BH: assorted test gaps (Lesson shell fresh=false, ring vs accent, numbered-row TalkBack, StateAudit) — covered at composable level or cosmetic.
  - `low` `reject` ECH: fresh gate lifts with partly set faces — carried (ECH3, pass 1).
  - `low` `reject` ECH: unique fit may turn a face the Learner never painted — the result is the only valid cube reachable by face turns; caller cubes count every face as entered by spec.
  - `low` `reject` ECH: face-done never fires for sticker 53 or out-of-order completion — same as BH last-face finding.
  - `maybe-false` `defer` ECH: 4^6 enumeration on main thread — same as BH perf finding; unmeasured.
  - `false` `reject` ECH: twist/flip/parity show no markers — non-local failures have no suspect stickers; base AC gives them their own lines.
- Route: bad_spec → spec amended (Spec Change Log, loop 2), code reverted; defers recorded in frontmatter.

### 2026-11-20 — Review pass 3
- verdicts: 26 findings — high 0, medium 3, low 21, false 1, maybe-false 1
- findings:
  - `medium` `intent_gap` IA: on the full-net layout (Lenovo Tab M10 Plus 3rd Gen, ~800dp wide portrait, an AC acceptance device) there are no face tabs, so the tab ✓, the warning dot, the "that face's tab is marked" signal and the hold bands never render — EXPERIENCE.md/DESIGN.md describe them only on face tabs and "around the face being edited"; how the full net should show them is not settled.
  - `low` `reject` IA: touched gate lets one tap per face lift the gate — carried (IA1, pass 1): AC and EXPERIENCE.md L137 say "faces not yet touched".
  - `low` `reject` IA: "Front done ✓ → Right" cannot appear with URFDLB auto-advance — the AC example is illustrative; the contract keeps auto-advance unchanged (Up → Right appears).
  - `low` `reject` IA: TalkBack phrase only on the compact tab state — carried (IA4, pass 1).
  - `low` `reject` IA: twist/flip/parity show no instruction line — loop-2 decision: no face can be blamed, the rule's own line explains.
  - `low` `reject` IA: resolution only on paint, not on open — drafts are saved after resolution inside `paint`; Lesson opens on the valid expected state.
  - `low` `reject` IA: cue ring changes Scan review without opt-in — carried (IA7, pass 1).
  - `low` `reject` IA: joiners outside en.json — carried (BH, pass 1).
  - `false` `reject` IA: spec sign-offs recorded as rejects — descriptive, no defect.
  - `low` `reject` ECH: Accept enables after six same-color taps on the default cube — carried (touched gate per AC).
  - `low` `reject` ECH: orient from default colors while Back is partly entered — carried (ECH3, pass 1).
  - `low` `reject` ECH: caller-cube face under repair auto-rotated — carried (ECH, pass 2).
  - `low` `patch` VG: restore of a shown face different from the selected face untested — pre-verified; moot (intent gap reverts code).
  - `medium` `patch` VG: 200% audit never reaches faces-left, turned, check-face or face-done states — pre-verified; moot (intent gap reverts code).
  - `low` `reject` BH: specific messages missing from Scan review — the contract's Never excludes Scan review changes beyond shared-net parameters; Scan review already has its own specific lines (#174).
  - `medium` `patch` BH: 200% audit does not cover new editor UI — same as VG; moot.
  - `low` `patch` BH: instruction line sits below palette, spec says under the net — real deviation; moot.
  - `low` `reject` BH: face-done slot always reserves a blank row — the spec requires no layout shift.
  - `low` `reject` BH: resolvable cube opened without paint gets no guidance — same as IA resolution-on-open.
  - `low` `patch` BH: `countsRight` uses `PER_FACE` — both 9; moot.
  - `low` `reject` BH: missing repeat count silently renders "two" — PieceCheck always sets an Int count; defensive change only.
  - `maybe-false` `defer` BH: validator runs several times per paint — carried (deferred perf item, pass 2).
  - `low` `reject` BH: spec stale content and temp paths — fix is to edit this build's spec (rejected by rule).
  - `low` `reject` BH: literal ✓, separators, pass-through counts key — carried.
  - `low` `reject` BH: `StickerCueRingKey` lacks a property accessor — style only.
  - `low` `reject` BH: fully qualified names in tests and small test gaps — style / cosmetic.
- Route: intent_gap → attempted change saved as [spec-gh-253-attempt3.patch](spec-gh-253-attempt3.patch) (passed `spotlessCheck build`), code reverted, HALT blocked.

### 2026-11-20 — Review pass 4
- verdicts: 34 findings — high 0, medium 1, low 24, false 7, maybe-false 2
- findings:
  - `low` `reject` IA: unique turn never reached through a shell flow, only at state/composable level — the turn is a pure state rule covered by state and Robolectric tests; shell tests cover the entry gating; a shell replay of 48 taps adds cost for no new path.
  - `low` `reject` IA: TalkBack "Up face turned to match" only as tab state — carried (IA4, pass 1).
  - `false` `reject` IA: "meta" style not linked to a token — Design Notes define meta as `bodySmall` in `ink-secondary`, which the note uses.
  - `low` `reject` IA: no reduce-motion test for the new cues — the cursor reuses the existing `stickerPulsing` path (static under reduce motion); the face-done line and ring do not animate.
  - `low` `reject` IA: Placement editor now gated as fresh — Design Notes: Placement opens on the default solved cube, epics.md AC wins; result path unchanged.
  - `low` `reject` IA: cue ring also in Scan review, untested there — carried (IA7, pass 1).
  - `low` `reject` IA: twist/flip/parity name no face — carried (loop-2 decision).
  - `low` `reject` IA: unique fit not applied on restore without a paint — carried (resolution-on-open, pass 2/3).
  - `low` `reject` BH: auto-turn in non-fresh editors — carried (ECH, pass 2).
  - `false` `reject` BH: auto-turn cannot be declined — the intent requires accepting without asking when one cube fits; redraw plus note is the AC.
  - `maybe-false` `defer` BH: orient on the main thread per paint — carried (deferred perf item, pass 2).
  - `false` `reject` BH: an impossible-piece failure can vanish from the lines — carried (pass 1 refutation).
  - `false` `reject` BH: old 56-int bundle crashes on restore — carried (pass 1 refutation).
  - `false` `reject` BH: faces-left order differs from fill order — carried (pass 2).
  - `low` `reject` BH: no face-done cue for the last face — carried (pass 2).
  - `low` `reject` BH: auto-advance does not skip set stickers — the contract keeps auto-advance unchanged.
  - `low` `reject` BH: separators, joins and lowercase outside en.json — carried (pass 1).
  - `low` `reject` BH: counts line drops the nine-per-color rule — the matrix fixes the exact text "Red: 10 (one too many) · Green: 8 (one missing)".
  - `low` `reject` BH: editor keys by string replace — carried (pass 2).
  - `low` `reject` BH: TalkBack never hears the hold — carried (pass 2).
  - `low` `reject` BH: full-net strip may have no selected tab and taps move the cursor — both are the PO's 2026-11-20 strip behavior (tap selects the face's first sticker; current = selected sticker's face); each tab still reads its name and state.
  - `low` `reject` BH: ✓ only for painted faces on a caller cube — carried (Lesson ✓ only after painting is defined, pass 1).
  - `low` `reject` BH: Scan review's FaceSelector still uses the red badge — the contract's Never excludes Scan review changes beyond shared-net parameters.
  - `low` `bad_spec` BH: `fit` counts colors against `PER_FACE` — both 9; amended Design Notes (`PER_COLOR` in `fit`), loop 3.
  - `maybe-false` `defer` BH: repeated validation per paint — carried (deferred perf item).
  - `low` `reject` BH: `rotateClockwise` public — carried (IA7, pass 1).
  - `low` `reject` BH: Lesson passes the default `fresh = false` — explicit per the spec task.
  - `low` `defer` VG: cue ring verified only through semantics, not the drawn border — pre-verified; filed disposition defer (no pixel test infrastructure); added to `deferred`.
  - `medium` `bad_spec` ECH: piece-impossible says "colors no real corner has" for a duplicated real piece — real: `CubeValidator.faults` flags duplicate cubies as ImpossiblePiece; amended copy in Design Notes, loop 3.
  - `low` `reject` ECH: fresh gate lifts with one sticker per face — carried (ECH3, pass 1).
  - `low` `reject` ECH: non-fresh caller cube silently turned — carried (ECH, pass 2).
  - `low` `reject` ECH: no face-done for Down or out-of-order completion — carried (pass 2).
  - `false` `reject` ECH: cursor lands on a set sticker after a turn — set flags rotate with the same turn as the selection, so the moved selected sticker keeps its unset flag.
  - `false` `reject` ECH: old-format bundle crash — carried (pass 1 refutation).
- Route: bad_spec → spec amended (Spec Change Log, loop 3), code reverted, attempt-4 reference patch at `/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/4e69ffcd-c332-4b0e-a143-2c4e3edd307d/scratchpad/gh253-attempt4-reference.patch`; VG ring item deferred.

### 2026-11-20 — Review pass 5
- verdicts: 29 findings — high 0, medium 1, low 18, false 7, maybe-false 3
- findings:
  - `low` `reject` IA: unique fit applied only on paint, not on open/restore — carried (resolution-on-open, pass 2).
  - `low` `reject` IA: "Up face turned to match" only as tab state, live region reads the AC sentence — carried (IA4, pass 1).
  - `false` `reject` IA: ambiguous/wrong-counts rows show more lines than listed — carried (IA3, pass 1).
  - `low` `reject` IA: no real TalkBack, visual or reduce-motion check — carried (pass 4): Robolectric semantics is the repo standard; the new cues add no animation of their own.
  - `low` `reject` IA: fresh gate covers Placement/Recognize but not Lesson or Solve after accept — carried (Design Notes, pass 4).
  - `low` `reject` IA: twist/flip/parity name no face — carried (loop-2 decision).
  - `false` `reject` IA: matrix does not say what Accept returns — no divergence: Accept returns the state validated from the stickers shown, as the base AC says.
  - `low` `reject` BH: six one-sticker taps lift the gate on the default cube — carried (IA1, pass 1).
  - `maybe-false` `defer` BH: orient and repeated validation on the main thread — carried (deferred perf item).
  - `low` `reject` BH: auto-turn on non-fresh caller cubes — carried (ECH, pass 2).
  - `false` `reject` BH: a turn moves the cursor onto a set sticker — carried (pass 4 refutation: set flags turn with the selection).
  - `false` `reject` BH: an impossible piece can lose its line — carried (pass 1 refutation).
  - `false` `reject` BH: EditorProblems.of KDoc says "when counts are right" but code does not check — CubeValidator returns WrongColorCounts alone (no ImpossiblePiece alongside), so the comment holds.
  - `low` `reject` BH: separators and joins in Kotlin — carried (pass 1).
  - `low` `reject` BH: color names lowercased in code — carried (pass 1 separators/lowercase).
  - `false` `reject` BH: old-layout bundle crash — carried (pass 1 refutation).
  - `maybe-false` `reject` BH: too many polite live regions — carried (pass 1: needs a TalkBack device check; if true only low).
  - `low` `patch` BH: HoldBands uses deprecated invisibleToUser() — replaced with hideFromAccessibility(); ManualCubeEditorCompactTest asserts HideFromAccessibility.
  - `low` `reject` BH: tab state text mixes strings.xml and en.json — the spec keeps `R.string.editor_face_invalid`; fixing it means editing this build's spec.
  - `low` `reject` BH: face-done "→" not mirrored in RTL — the copy is spec'd en.json text and the app ships English only; a mirrored variant adds keys for no current user.
  - `low` `reject` BH: small test gaps (R/L bands, repaint after turn, face-done via turn, rememberSaveable key) — cosmetic; the rules are covered at state level.
  - `low` `reject` BH: rotateClockwise public, Lesson passes default fresh — carried (pass 4).
  - `medium` `patch` VG: 200% audit never renders the face-done line, ✓ tab or turned note — pre-verified (pass 3 row was moot after revert, never applied); StateAudit.auditEditorStates gained "Manual editor face done" and "Manual editor turned to match" checks, run by DynamicType200Test compact and expanded.
  - `low` `reject` ECH: non-fresh caller cube turned silently — carried (ECH, pass 2).
  - `low` `reject` ECH: gate lifts with one sticker per face — carried (ECH3, pass 1).
  - `low` `reject` ECH: no face-done for Down or out-of-order completion — carried (pass 2).
  - `maybe-false` `defer` ECH: 4^6 enumeration on the main thread — carried (deferred perf item).
  - `low` `reject` ECH: 180°-symmetric face with turns 1 and 3 equal maps set flags of a partly set face to mirrored cells — the colors cannot tell the two apart; only ✓ and the cursor on a partly set face are affected, and a complete face is unaffected.
  - `false` `reject` ECH: cursor jumps onto a set sticker after a turn — carried (pass 4 refutation).
- Route: patches applied (VG audit, BH deprecation); `spotlessCheck build` re-run green; no new defers.

## Design Notes

- Fresh entry = editor opened on the default solved cube with no caller cube (Solve first entry or re-entry before any accept, scan fallback, camera denial, Placement, Recognize). epics.md AC ("opened on the default solved cube") is the Done-when and wins over EXPERIENCE.md's "(physical mode, Placement fallback)" parenthetical, since Placement opens on the default solved cube today. Caller's cube = Lesson physical mode and Solve re-entry after an accept.
- A face is "entered" once any of its stickers is set; its tab ✓ once all 8 are set. The face-done line shows when a paint turns a face's ✓ on and auto-advance moves to another face.
- As-entered valid always wins (base AC 3): turning is tried only when the entry is invalid as entered and every color count is 9 (the matrix's "counts OK"). With wrong counts no face is named and no instruction line shows: the counts line explains, since no turn can fix counts.
- Face tabs show in both layouts (PO 2026-11-20, settling review pass 3's intent gap): one-face mode uses them to pick the shown face; the full-net layout shows the same row above the net as a status strip (✓, warning dot, "Turned to match"), where a tap selects that face's first sticker and "Check the … face" points at that tab. Hold bands are drawn only in one-face mode; the full cross net already shows each face's neighbours.
- TalkBack: the turned note is a polite live region reading the AC sentence; the matrix's "Up face turned to match" is the turned face's tab stateDescription ("Up face, turned to match").
- Update-cue ring: per PO 2026-11-20 it uses each sticker's glyph ink (sticker-white on red and blue, cubie-body otherwise), superseding DESIGN.md's "cubie-body ring" (do not edit DESIGN.md). Measured: cubie-body vs blue would be 2.54:1.
- Face names in messages use `R.string.editor_face_*` ("Up", "Front", …); colors use `R.string.color_*`. A piece's faces are listed U/D first, then F/B, then R/L, joined with `msg.editor.list-two` / `msg.editor.list-three`.
- Stickers per color: use a `PER_COLOR` (9) constant wherever a color count is meant, including the counts gate in `ManualCubeEditorState.fit` (not `PER_FACE`).
- Problem lines (all faces entered), in order: wrong counts → one `msg.editor.counts` line built from `msg.editor.count-over`/`count-under` parts for each off color in palette order W Y R O G B, joined with " · " (no stickers marked); PieceCheck problems → `msg.editor.piece-repeats.{color}` / `msg.editor.piece-opposite` naming the piece's faces (numbered, marked); when counts are right, each `ImpossiblePiece` piece not already named by PieceCheck → `msg.editor.piece-impossible` (numbered, marked); twist/flip/parity keep their existing `msg.validation.*` lines.
- New en.json keys (exact copy):
  - `msg.editor.faces-left`: "{count, plural, one {# face} other {# faces}} left to enter: {faces}." ({faces} comma-joined, as in the matrix: "Right, Back, Down"; the content checker reads only top-level arguments, so arguments stay outside plural branches)
  - `msg.editor.check-face-top`: "Check the {face} face: hold {front} toward you and look at the top." (Up; front = green)
  - `msg.editor.check-face-side`: "Check the {face} face: hold {up} on top and turn {center} toward you." (F, R, B, L with up = white; Down with up = green, the net's top neighbour of D, and center = yellow: "Check the Down face: hold green on top and turn yellow toward you." — "hold green toward you and look at the bottom" would be read 180° off when peeking underneath)
  - `msg.editor.turned`: "We turned the {faces} {count, plural, one {face} other {faces}} to match the others."
  - `msg.editor.face-turned`: "Turned to match"; `msg.editor.face-complete`: "All stickers set"
  - `msg.editor.face-done`: "{face} done ✓ → {next}"; `msg.editor.face-done-spoken`: "{face} face done. {next} face next."
  - `msg.editor.count-over`: "{color}: {count} ({diff, plural, =1 {one too many} =2 {two too many} other {# too many}})"; `msg.editor.count-under`: "{color}: {count} ({diff, plural, =1 {one missing} =2 {two missing} other {# missing}})"; `msg.editor.counts`: "{parts}"
  - `msg.editor.piece-repeats.{white|yellow|red|orange|green|blue}`: "The {piece} between {faces} shows {count, plural, =3 {three greens} other {two greens}}." (color word per key)
  - `msg.editor.piece-opposite`: "The {piece} between {faces} shows opposite colors."; `msg.editor.piece-impossible`: "The {piece} between {faces} has colors that don't fit the rest of the cube." (true both for a piece no real cube has and for a real piece that also sits in another slot, which `CubeValidator.faults` reports the same way; loop 3)
  - `msg.editor.list-two`: "{a} and {b}"; `msg.editor.list-three`: "{a}, {b} and {c}"; `msg.editor.list-more`: "{rest} and {last}" (rest comma-joined; for piece faces and the turned list)
- `R.string.editor_face_with_problems` stays: Scan review's `FaceSelector` still uses it; the editor gets its own tab composables.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew :core:domain:test --tests '*ScanAssemblerTest*' :app:testDebugUnitTest --tests '*editor*' --tests '*ThemeTokensTest*' --tests '*SolveFlowViewModelTest*' --tests '*ContentStringsTest*' --tests '*a11y*'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (lint, Kover, checkLearnerText, boundary checks)

## Auto Run Result

Status: done

**Summary:** The Manual Cube Editor accepts faces entered at any rotation: `ScanAssembler.orient` resolves per-face quarter turns, a unique fit is redrawn with a meta note, otherwise "Check the … face" names the face and its hold. A fresh entry shows "{n} faces left to enter" instead of errors; messages name per-color counts and pieces with numbered markers; face tabs carry ✓ / warning dot / "Turned to match" (also as a status strip above the full net on medium/expanded windows, PO 2026-11-20); pulsing cursor, face-done line with TalkBack announcement, and an update-cue ring in each sticker's glyph ink.

**Files changed:**
- `core/domain/.../recognition/ScanAssembler.kt` -- `orient` / `FaceTurnFit` / `netNeighbors`.
- `core/domain/.../cube/PieceCheck.kt` -- piece lists exposed for named lines.
- `core/domain/.../content/strings/en.json` -- `msg.editor.*` copy.
- `app/.../ui/editor/EditorProblems.kt` (new) -- counts, named-piece and global lines.
- `app/.../ui/editor/ManualCubeEditorState.kt` -- fresh gate, set stickers, turn apply, checkFace, faceDone, selectFace, Saver.
- `app/.../ui/editor/ManualCubeEditor.kt` -- instruction / turned / face-done lines, numbered problems, face tabs in both layouts, hold bands (one-face only).
- `app/.../ui/editor/StickerReviewNet.kt` -- update-cue ring (`StickerCueRingKey`).
- `app/.../ui/solve/SolveFlowViewModel.kt`, `SolveScreen.kt`, `placement/PlacementScreen.kt`, `recognize/RecognizeScreen.kt`, `lesson/LessonScreen.kt` -- fresh / set plumbing.
- Tests: ScanAssemblerTest, ManualCubeEditorStateTest, ManualCubeEditorTest, ManualCubeEditorCompactTest, EditorFixtures, ThemeTokensTest, SolveFlowViewModelTest, ContentStringsTest, AppShell Solve/Placement/Recognize, ScreenAudit, StateAudit.

**Review findings (pass 5):** 2 patches applied (medium: 200% audit of face-done/turned states; low: deprecated invisibleToUser). Defers: none new (perf item and ring-drawing item stay in `deferred`). All rejected findings and reasons are in the pass-5 triage log. Earlier passes: loop 3 bad_spec (piece-impossible copy, PER_COLOR) re-derived.

**Follow-up review recommendation:** false (patched this pass: 1 medium, 1 low; no high).

**Verification:** `./gradlew spotlessApply` clean; targeted tests (ScanAssembler, editor, ThemeTokens, SolveFlowViewModel, ContentStrings, a11y, AppShell) BUILD SUCCESSFUL; `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** turn search and validation on the main thread per paint, untimed on the Lenovo Tab M10 Plus 3rd Gen; cue ring verified via semantics only; TalkBack / live-region behavior checked only under Robolectric. DESIGN.md "cubie-body ring" wording still to be updated via correct-course (PO 2026-11-20).
