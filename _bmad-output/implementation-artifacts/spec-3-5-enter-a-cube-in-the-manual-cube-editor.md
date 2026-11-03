---
title: 'Enter a cube in the Manual Cube Editor'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: e2b1163c8cbd0d0b3042ee2aa2cf3661415f5f31
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Drawn status-invalid outline and accent selection ring are verified only through semantics flags set in parallel, not by pixels or drawn color.
    evidence: |-
      Removing the drawRing call or swapping its color leaves every editor test green; the repo has no screenshot tooling (no roborazzi/paparazzi), matching how Net2DRenderer is tested. Closing it needs screenshot infrastructure or drawn-color semantics.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/StickerReviewNet.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** A Learner without a camera has no way to enter a physical cube's 54 stickers; the validator exists (Epic 1) but nothing lets them paint stickers, see exactly what is wrong, and hand a validated `CubeState` to a caller (FR-14, UX-DR16/21/25).

**Approach:** Add a reusable sticker review net component and a Manual Cube Editor composable in `:app` driven by a plain state holder: tap a sticker, then a palette color; centers stay at home-frame colors; validation runs live through `CubeValidator`; offending pieces get a `status-invalid` outline, every problem gets its own plain-language line, and **Accept** is enabled only for a `Valid` result and returns that `CubeState` through a callback.

## Boundaries & Constraints

**Always:** AD-2 home frame (WHITE U, GREEN F) and URFDLB indexing; `CubeValidator` remains the only route to `CubeState` (no new public construction). Sticker fill/glyph/ink come only from `StickerAppearance` (AD-17); glyphs controlled by the caller-supplied `RenderStyle.colorBlindGlyphs` and drawn on net stickers and palette swatches alike (≥16dp, same rule as `Net2DRenderer`). Learner text comes from `en.json` `Text` keys resolved by `ContentStrings` or from `strings.xml` (LearnerTextPolicy). The editor is not a session (AD-3/AD-20): no `SessionEvent`, reducer, effect or persistence. Non-local copy follows EXPERIENCE.md State Patterns verbatim in meaning.

**Never:** Wire the editor into `AppShell`/Solve tab, call `SolveCube`, add camera/scan "uncertain sticker" behavior, add a persisted Settings toggle, change planning artifacts, `_bmad/` or `.claude/skills/`, or change `CubeValidator.validate` results/order. Scan review, Solve integration and settings belong to later stories (3.7, Epic 4/10).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Paint | Sticker 0 selected, tap RED swatch | Sticker 0 drawn RED; validation reruns | No error |
| Center tap | Tap any center (4,13,22,31,40,49) | Not selectable; color unchanged; centers always home colors even if caller's initial facelets differ | Ignore silently |
| Color before sticker | No sticker selected, tap swatch | Nothing changes | Ignore |
| Glyphs on/off | `colorBlindGlyphs` true / false | Every net sticker and swatch shows/hides its `W Y R O G B` glyph in its `glyphInk` | — |
| Wrong counts | One non-center sticker repainted | Count line shown (no piece outline), Accept disabled | — |
| Impossible piece | Corner showing two whites with counts fixed | Exactly that piece's stickers outlined `status-invalid`; piece line shown; Accept disabled | — |
| Twisted corner + flipped edge | Legal pieces, corner twisted and edge flipped | No outlines; one line "One corner is twisted…" and another "One edge is flipped." | All co-occurring non-local errors listed |
| Swapped pieces | Two edges swapped | Line "Two pieces are swapped…"; Accept disabled | — |
| Valid | Solved or legally scrambled stickers | No problem lines; Accept enabled; tap returns the exact validated `CubeState` (`toFacelets()` equals entry) once per tap | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeValidator.kt` -- `validate` returns first failure only (counts → pieces → twist → flip → parity). Add `problems(facelets): List<ValidationResult.Invalid>` sharing internals: empty iff `validate` is `Valid`, first element equals `validate`'s failure, and when pieces decode it lists every applicable non-local failure (twist, flip, parity) together. `ImpossiblePiece.positions` are learner sticker indices to outline.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Facelets.kt` -- index layout; `FaceletTables.CENTERS`/`center(face)` internal (app cannot see; use `index % 9 == 4`).
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.validation.*` keys exist; reword twisted/flipped/parity to EXPERIENCE copy: "One corner is twisted — check the corners you changed last.", "One edge is flipped.", "Two pieces are swapped — this can’t happen on a real cube." (typographic apostrophe: ICU MessageFormat quoting).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/CubeValidatorTest.kt` -- 205 lines; fixture helpers to extend for `problems`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearance.kt` -- `of(color)` fill/glyph/glyphInk.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt` -- reuse `netCell(index)` (internal companion), glyph sizing rule (`MIN_GLYPH_DP`, `GLYPH_FRACTION`, `GAP_FRACTION`), semantics keys `StickerColorKey`/`StickerGlyphInkKey`, `sticker:$index` tag convention. Extract shared glyph/sticker drawing rather than duplicate if clean; keep renderer behavior unchanged.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/RenderStyle.kt` -- glyph flag input.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Tokens.kt` -- `RubikTheme.tokens.statusInvalid`, `accent` (selection ring).
- `app/src/main/kotlin/dev/rubikxplorer/app/content/ContentStrings.kt` -- resolves `ValidationResult.Invalid.reason`.
- `app/src/main/res/values/strings.xml` -- add Accept label, palette color names/content descriptions, net/selection descriptions.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` -- Robolectric compose test pattern (`createComposeRule`, `RubikXplorerTheme`, semantics key reads).

## Tasks & Acceptance

**Execution:**
- [x] `core/domain/.../cube/CubeValidator.kt` + `CubeValidatorTest.kt` -- add `problems` with tests for the invariants above and co-occurring twist+flip(+parity) -- editor needs every non-local line.
- [x] `core/domain/src/main/resources/content/strings/en.json` -- update three non-local messages to EXPERIENCE copy -- AC wording.
- [x] `app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditorState.kt` -- plain state holder: facelets (centers forced home), selected index, `select`, `paint(color)`, derived `problems`, `outlined` indices, `validState: CubeState?` -- testable logic outside composables.
- [x] `app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/StickerReviewNet.kt` -- tappable cross net from `Facelets` with `StickerAppearance` fill/glyphs, accent selection ring, `status-invalid` outline for given indices, semantics for color/glyph/selected/invalid -- reusable for later scan review.
- [x] `app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditor.kt` -- composable(initial facelets default solved, `RenderStyle`, `ContentStrings`, `onAccept: (CubeState) -> Unit`): net, six-color palette, one text line per problem, Accept enabled only when valid.
- [x] `app/src/main/res/values/strings.xml` -- new labels.
- [x] `app/src/test/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditorStateTest.kt`, `ManualCubeEditorTest.kt` -- cover every matrix row at the composable surface (tags, semantics, enabled state, callback value).

**Acceptance Criteria:**
- Given the sticker review net in the editor, when the Learner taps a non-center sticker then a color, then that sticker is drawn in the color, centers remain home colors and are not editable, and glyphs appear on stickers only while color-blind display is on.
- Given an invalid entry, when validation runs, then offending pieces are outlined in `status-invalid`, a resolved plain explanation is shown, each non-local error has its own line with the EXPERIENCE copy, and Accept is disabled.
- Given a valid entry, when Accept is tapped, then `onAccept` receives the `CubeState` produced by `CubeValidator` for exactly the entered stickers, and no session/reducer/effect is involved.

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 29 findings — high 0, medium 2, low 22, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: editor state lost on rotation/process death (`remember`) — added internal listSaver (54 ordinals + selection) with `rememberSaveable`; round-trip state test added.
  - `[low]` `[patch]` Blind: impossible-piece copy says "One {piece}" while validator outlines every faulty piece (usually two) — reworded count-neutral "Some {piece}s show colors that no real {piece} has. Check the outlined stickers."; test expectation updated.
  - `[low]` `[reject]` Blind: twisted-corner advice "check the corners you changed last" may not fit preloaded entries and shared keys — copy is EXPERIENCE.md State Patterns verbatim, quoted by the story AC; changing it would contradict the intent.
  - `[low]` `[patch]` Blind: sticker tap target shrunk by padding placed before clickable — clickable now applied to the full cell.
  - `[low]` `[patch]` Blind: all stickers of one color share the same content description — description now "<color> sticker, <face> face, position N" via existing move_face_* strings; asserted in tests.
  - `[low]` `[patch]` Blind: problem lines not announced — problem column is a polite live region.
  - `[low]` `[patch]` Blind: selection and invalid rings differ only by color — selection ring now dashed, invalid ring solid.
  - `[low]` `[reject]` Blind: validation runs twice per edit — negligible cost on 54 stickers; both derive from one shared analyze so they cannot disagree; restructuring adds API for no user-visible gain.
  - `[low]` `[patch]` Blind: CubeValidator/ValidationResult KDoc still says first failure only — KDoc now documents validate first-failure and problems all-failures.
  - `[low]` `[reject]` Blind: problemLines test helper caps at 6 tags — validator can emit at most 3 lines; no realistic regression hides there; harmless.
  - `[low]` `[reject]` Blind: SingleGateTest generic return-type string match is brittle — passes on the pinned Kotlin 2.4.21 toolchain; a breakage would fail loudly, not silently.
  - `[low]` `[reject]` Blind: no onCancel/dismiss — AC requires only returning a validated state; caller navigation belongs to Story 3.7, which can add it.
  - `[false]` `[reject]` Blind: swatch glyph uses net gap sizing — at 48dp the rule yields 26.4dp, well inside the swatch; no spill or illegibility occurs.
  - `[low]` `[patch]` Verification: outlined union across two positioned problems untested — added state test with corner+edge faults asserting two problems and union {5,7,8,9,10,12,19,20,23}.
  - `[low]` `[patch]` Verification: content/state descriptions never asserted — tests now assert all six swatch labels, sticker labels and "Check this piece" only on outlined stickers.
  - `[low]` `[defer]` Verification: drawn ring checked only via parallel semantics flags — no screenshot tooling in repo; recorded in frontmatter deferred.
  - `[low]` `[patch]` Verification (other): sticker touch target below cell — same root cause and fix as the Blind tap-target row.
  - `[medium]` `[patch]` Edge: rotation/process death loses entries — same root cause and fix as Blind rotation row.
  - `[false]` `[reject]` Edge: valid non-home-orientation initial facelets get centers overwritten — AC requires "centers are fixed by the home frame"; forcing home centers is the specified behavior.
  - `[low]` `[reject]` Edge: unbounded constraints crash StickerReviewNet — only caller bounds it with fillMaxWidth+aspectRatio, same pattern as Net2DRenderer; misuse only, guard adds branches.
  - `[low]` `[reject]` Edge: rings invert on cells under ~17dp when selected+invalid — needs a net narrower than ~200dp; phone widths give ≥25dp cells.
  - `[false]` `[reject]` Edge: double-tap Accept fires onAccept twice — matrix specifies once per tap; deduplicating navigation is the caller's (3.7) responsibility.
  - `[low]` `[reject]` Edge: shared twisted-corner copy affects other validation callers — same as Blind copy row; AC-mandated wording.
  - `[low]` `[reject]` Intent: editor not reachable from the app shell — AC is stated at the component ("Given the sticker review net component"); Solve-tab wiring is Story 3.7.
  - `[low]` `[reject]` Intent: outline color/pixels not tested — same root cause as deferred verification row (routed there).
  - `[false]` `[reject]` Intent: glyphs follow RenderStyle, not a Settings preference — no Settings preference exists yet; AC conditions on display being on, which the caller-supplied style models.
  - `[low]` `[reject]` Intent: no visible per-piece label (UX-DR16 "with labels") — AC requires outline plus plain explanation, both present; outlined stickers also carry an accessible "Check this piece" state.
  - `[false]` `[reject]` Intent: shared copy edit reaches beyond editor — the keys are the validator's reasons the AC quotes; no other screen displays them today.
  - `[low]` `[reject]` Intent: AD-3 "not a session" not enforced by a test — design-level guarantee (local state + callback, no reducer imports); an architecture test would be disproportionate.

## Design Notes

Initial facelets are caller-supplied (default `CubeState.SOLVED.toFacelets()`) so a later caller can reopen with preserved colors; centers are overwritten with home colors. Selection stays on the painted sticker. Wrong-color-counts has no positions, so it is a line without outlines. `problems` must never make `Valid` reachable by another path; it only reports failures.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test :app:testDebugUnitTest` -- expected: new and existing tests pass.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: all CI gates pass (Kover, LearnerTextPolicy, boundaries, lint, APKs).

## Auto Run Result

Status: done. Added the Manual Cube Editor (`ManualCubeEditor`), a reusable tappable `StickerReviewNet` and a saveable `ManualCubeEditorState`: tap a non-center sticker then a palette color; centers forced to home colors; glyphs from `StickerAppearance` follow `RenderStyle.colorBlindGlyphs`; live validation outlines offending pieces in `status-invalid` and lists every problem on its own line; Accept is enabled only for a `Valid` result and passes that `CubeState` to `onAccept`. No session, reducer, effect or app wiring (Story 3.7).

Files changed:
- `core/domain/.../cube/CubeValidator.kt` — `problems()` reporting all failures via shared analysis; `validate` unchanged in results/order.
- `core/domain/src/main/resources/content/strings/en.json` — EXPERIENCE copy for twist/flip/parity; count-neutral impossible-piece copy.
- `core/domain/src/test/.../CubeValidatorTest.kt`, `SingleGateTest.kt` — 7 problems tests; gate test allows failure-only `problems`.
- `app/.../render/StickerGlyph.kt`, `Net2DRenderer.kt` — shared glyph drawing extracted; renderer behavior unchanged.
- `app/.../ui/editor/ManualCubeEditorState.kt`, `StickerReviewNet.kt`, `ManualCubeEditor.kt` — editor state, net and screen.
- `app/src/main/res/values/strings.xml` — Accept, color names, sticker description, invalid state.
- `app/src/test/.../ui/editor/*` — 11 state tests, 11 Robolectric composable tests, fixtures.

Review: four layers, 29 findings triaged. Patched 13 rows in 9 groups (medium 1, low 8 groups); deferred 1 (drawn ring verified only by semantics; no screenshot tooling). Rejected rows with reasons in the Review Triage Log (AC-mandated copy, specified home-center behavior, 3.7-owned wiring/cancel/double-tap handling, negligible double validation, unreachable small-cell/unbounded layouts, test-helper nits). Follow-up review recommended: false — one medium patched, no high.

Verification: `./gradlew :core:domain:test :app:testDebugUnitTest spotlessCheck build` BUILD SUCCESSFUL before review (13s, incremental) and after patches (2m23s). Editor suites 11+11 tests, 0 skipped/failed; CubeValidatorTest 18 and SingleGateTest 3 green. Matrix audit: all nine rows covered by executed tests.

Residual risks: visual ring rendering unverified by pixels; editor not yet reachable in-app until Story 3.7; not checked on a physical device.

