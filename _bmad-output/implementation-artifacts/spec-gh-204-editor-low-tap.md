---
title: 'GH #204: Manual Cube Editor auto-advance (low-tap review interaction)'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: '33415df0618451a90b933b0acaf0ec00f7177ab1'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred:
  - summary: >-
      Two polite live regions (the new editor:selected line and the problem lines) can change on one palette tap; TalkBack ordering or dropping of one announcement is unverified.
    evidence: |-
      maybe-false. ManualCubeEditor.kt places SelectedStickerLine and the problems Column, both LiveRegionMode.Polite; ScanReviewScreen already ships the same multi-region pattern. Settle it with a TalkBack device check painting a sticker that creates or clears a problem.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** In the Manual Cube Editor, `ManualCubeEditorState.paint()` keeps the selection put, so entering a cube takes about two taps per sticker (sticker, then color). EXPERIENCE.md's "Sticker review net" row (Scan review and Editor) specifies auto-advance: after a color is set, the selection moves on, so a face needs about one touch per sticker.

**Approach:** After a palette color is set, the Editor moves the selection to the next editable sticker in URFDLB row-major (Kociemba) index order, skipping centers and crossing faces, using one shared "next sticker" helper extracted from `ScanReview.paint()`'s existing auto-advance loop. A polite live-region line names the selected sticker so TalkBack announces it.

## Boundaries & Constraints

**Always:**
- Order = sticker index order 0..53 (U, R, F, D, L, B; row-major per face), centers (index % 9 == 4) skipped. This is the order Scan review already advances in and the face selector's order.
- Advance happens after every palette tap on a selected sticker, including when the color equals the current one (a confirming touch still advances; the color stays unchanged).
- End of order: after setting the last editable sticker (index 53, B face position 9) the selection stays on 53; no wrap to U. Nothing is lost: every painted color is kept.
- In one-face mode (compact class or narrow-width fallback) the shown face follows the selection (as `select()` already does), so crossing a face boundary switches the shown face.
- Tapping any editable sticker still selects it for a fix; the next color paints it and advances from it.
- The announcement uses content strings (`ContentText` via `strings.resolve`) and the existing `R.string.editor_sticker` description, in a `LiveRegionMode.Polite` node.
- `ScanReview` behavior is unchanged (wrap-around to the next blank; suspect jump after the last blank); its existing tests keep passing unchanged.

**Never:**
- No sticky-color mode, no clear/blank mode, no change to how the Editor starts (initial facelets, no pre-selection: `selected` stays null until the first tap).
- No edits under `_bmad-output/planning-artifacts`, `_bmad/`, `.claude/skills/`. No new Learner text in `strings.xml`. No session/reducer involvement (the Editor is not a session).
- No pulse, coach mark, instruction line or other Scan-review extras in the Editor.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Advance within a face | selected 0, paint RED | sticker 0 = RED, selected 1, face 0 | none |
| Skip center | selected 3, paint any | selected 5 | none |
| Cross faces | selected 8 (U9), paint any | selected 9 (R1), face 1 | none |
| Same color | selected 0 holding WHITE, paint WHITE | facelets unchanged, selected 1 | none |
| End of order | selected 53, paint BLUE | sticker 53 = BLUE, selected stays 53, face 5 | no wrap |
| Nothing selected | selected null, paint RED | no change, selected null | ignored |
| Tap to fix | after advancing to 10, tap 2, paint GREEN | sticker 2 = GREEN, selected 3 | none |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- `paint()` line `val next = (1..Facelets.SIZE).map { (index + it) % Facelets.SIZE }.firstOrNull { painted[it] == null }` is the existing auto-advance; refactor it to call the shared helper with wrap = true and predicate `painted[it] == null` (identical result: wrap scans index+1..index+54 mod 54).
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/` -- home of `Facelets` (SIZE = 54); new `StickerOrder` helper goes here so both `:app` editor and `recognition` use it.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- `paint()` (keeps selection, early-returns on same color) → paint if different, then `select(next ?: index)`; KDoc on class/`paint`/`selected` updated. `select()` already sets `face`. Saver unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- swatch onClick captures `index` before `state.paint`, so cue/haptic stay on the painted sticker; add the selected-sticker live-region line (tag `editor:selected`) between the net and the palette, or below the palette; KDoc mentions auto-advance.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` ~L443 `SelectedStickerLine` -- reference pattern: `stringResource(R.string.editor_sticker, colorName, FACE_NAMES[index/9], index%9+1)` wrapped in `ContentText("msg.scan.selected", mapOf("sticker" to …))`, `liveRegion = Polite`. Read-only; copy the pattern (do not refactor the scan screen).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- `FACE_NAMES` (L176), `colorName()` (L570); sticker nodes tagged `sticker:$index`. Read-only.
- `core/domain/src/main/resources/content/strings/en.json` -- add `"msg.editor.selected": "Selected: {sticker}"` next to `msg.scan.selected` (L736) so the Editor's key is its own; follow whatever key-registration tests the content module has (grep for `msg.scan.selected` in tests).
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorStateTest.kt` -- L22 test "selection stays" must become the advance test; L87 saver test and others that paint twice on one selection must re-select between paints.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt` (expanded full net, L112 "keeps it selected") and `ManualCubeEditorCompactTest.kt` (one-face; L167, L218, L230) -- Robolectric Compose tests; `EditorFixtures.kt` holds helpers.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/ScanReviewTest.kt` -- must pass unchanged.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/StickerOrder.kt` -- add `object StickerOrder { fun next(after: Int, wrap: Boolean = false, where: (Int) -> Boolean = { true }): Int? }`: first index after `after` in 0..53 order that is not a center and satisfies `where`; with `wrap` it scans `after+1 .. after+54` mod 54 (may return `after` itself if it qualifies, matching today's loop); without `wrap` it stops at 53 and returns null -- one implementation of the review net's advance order.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/StickerOrderTest.kt` -- unit-test center skip, face crossing, end-without-wrap null, wrap, predicate filtering -- Kover gate on `:core:domain`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- replace the inline loop with `StickerOrder.next(index, wrap = true) { painted[it] == null }` -- reuse, behavior unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- auto-advance in `paint()` per the matrix -- core change.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.editor.selected` -- announcement copy as a content string.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- add `SelectedStickerLine`-style polite live region (tag `editor:selected`) reading "Selected: {color} sticker, {face} face, position {n}" for `state.selected`; nothing rendered while null -- TalkBack announcement.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorStateTest.kt` -- cover every matrix row; fix tests relying on selection staying put.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt` -- expanded full net: tap sticker 0 then a color → sticker 1 is selected and `editor:selected` (live region Polite) names it; tap-to-fix; end of order stays on 53; update L112 test.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorCompactTest.kt` -- one-face: select U9 (index 8), tap a color → face R shown (`editor:face:1` selected, sticker 9 displayed and selected) and the announcement names R position 1; tap-to-fix on the shown face; fix tests that assumed no advance.

**Acceptance Criteria:**
- Given the Editor in the expanded full-net layout with sticker 3 selected, when the Learner taps a palette color, then sticker 3 takes it, sticker 5 is selected, and the polite `editor:selected` line names sticker 5.
- Given the compact one-face layout showing U with sticker 8 selected, when the Learner taps a color, then the R face is shown with sticker 9 selected and announced.
- Given selection auto-advanced away, when the Learner taps an earlier sticker and a color, then that sticker is fixed and the selection advances from it.
- Given sticker 53 selected, when a color is tapped, then 53 takes it and stays selected; all earlier colors are unchanged.
- Given the change, when `./gradlew spotlessCheck build` runs, then it succeeds.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 17 findings — high 0, medium 0, low 8, false 7, maybe-false 2
- findings:
  - `[low]` `[reject]` (edge) In one-face mode, painting a face's last sticker switches the shown face, so the update cue plays on a hidden sticker — the intent requires the shown face to follow the selection immediately; the haptic tick still confirms the set; Scan review behaves the same.
  - `[false]` `[reject]` (edge) StickerOrder.next with a negative `after` and wrap yields negative indices — unreachable: both callers pass a validated `selected` index in 0..53.
  - `[maybe-false]` `[defer]` (edge) Two polite live regions change on one tap; TalkBack may drop one — needs a device TalkBack check; recorded in `deferred` as medium (unverified).
  - `[low]` `[reject]` (blind) Cue lands on a hidden sticker after a face switch — same as the edge finding above; same reason.
  - `[low]` `[patch]` (blind) No test checks the cue stays on the painted sticker after auto-advance — added ManualCubeEditorTest `the update cue stays on the painted sticker while the selection advances` (sticker:0:cue exists, sticker:1:cue absent, 1 selected).
  - `[low]` `[reject]` (blind) Repainting sticker 53 with its own color gives no spoken feedback — the cue and haptic still fire; end-of-order behavior is settled by the spec; rare and fixing needs new copy.
  - `[maybe-false]` `[defer]` (blind) Two polite live regions change on the same tap — grouped with the edge finding; deferred.
  - `[false]` `[reject]` (blind) The selected line may not be announced on first insertion — the intent asks for the announcement after auto-advance; by then the node exists and only its text changes; the first manual tap is spoken by the sticker's own focus/selection.
  - `[low]` `[reject]` (blind) PER_FACE / center rule constants duplicated across files — fixed cube geometry, no caller can diverge; refactor is wider than this change.
  - `[low]` `[reject]` (blind) SelectedStickerLine duplicates Scan review's composable — the spec deliberately left the scan screen untouched; small, no behavioral harm.
  - `[false]` `[reject]` (blind) StickerOrder.next does not validate `after` — unreachable out-of-range input from both callers.
  - `[low]` `[patch]` (blind) StickerOrder KDoc overstates its use by Scan review — KDoc reworded: it drives only the next-sticker / next-blank step.
  - `[false]` `[reject]` (blind) End-of-order test differs from the matrix row — ManualCubeEditorStateTest `the last sticker keeps the selection without wrapping` asserts the color set on 53, selected 53, face 5; the exact color is immaterial.
  - `[false]` `[reject]` (blind) New content key not registered in a key-coverage test — no such registry/test exists for `msg.scan.selected` either; the UI tests resolve the key's English text.
  - `[false]` `[reject]` (intent) Index order on the expanded net jumps U9→R1 rather than to F — the intent asked to settle the order from the existing Scan review auto-advance, which is index order.
  - `[low]` `[reject]` (intent) The announcement is verified via the semantics tree, not a real TalkBack — Robolectric is the intent's named test surface; device checks are outside the build gate.
  - `[false]` `[reject]` (intent) The visible selected line approaches the spec's "no instruction line" ban — the ban targets Scan review's state-driven instruction line; this line is the announcement the intent requires, mirroring Scan review's SelectedStickerLine.

## Design Notes

Why no wrap at the end: the Editor starts fully filled (no blanks to hunt for, unlike Scan review), so a walk U1→B9 is a complete pass; wrapping back to U1 would silently move the Learner's place to a face they finished. Staying on 53 keeps the last edit visible and fixable. Scan review keeps its wrap because it hunts blanks.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** The Manual Cube Editor now auto-advances: after a palette color is set (even the same color), the selection moves to the next editable sticker in URFDLB row-major order, skipping centers and crossing faces; at sticker 53 it stays put (no wrap). One-face mode follows the selection to the next face. A polite live region (`editor:selected`, content key `msg.editor.selected`) names the selected sticker for TalkBack. The next-sticker order is one shared helper, `StickerOrder`, now also used by `ScanReview.paint()` (behavior unchanged).

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/StickerOrder.kt` — new shared next-sticker order helper.
- `core/domain/src/test/kotlin/dev/cubentia/domain/cube/StickerOrderTest.kt` — unit tests for the helper.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` — uses the helper (wrap, next blank).
- `core/domain/src/main/resources/content/strings/en.json` — `msg.editor.selected`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` — `paint()` auto-advances.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` — polite selected-sticker line; KDoc.
- `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorStateTest.kt`, `ManualCubeEditorTest.kt`, `ManualCubeEditorCompactTest.kt` — auto-advance, face switch, tap-to-fix, end of order, announcement, cue target.

**Review:** 17 findings. Patches applied: 2 (low: cue-target test; low: StickerOrder KDoc). Deferred: 1 entry (two polite live regions, medium unverified). Rejected: 14, reasons in the Review Triage Log.

**Follow-up review recommendation:** false — patched entries: high 0, medium 0, low 2.

**Verification:** `./gradlew spotlessCheck build` — BUILD SUCCESSFUL after implementation and again after the review patches. The verification-gap layer confirmed passing JUnit reports: ManualCubeEditorStateTest 18/18, ManualCubeEditorTest 16/16 (after patch), ManualCubeEditorCompactTest 14/14, StickerOrderTest 7/7, ScanReviewTest 12/12. Every I/O matrix row is covered in ManualCubeEditorStateTest.

**Residual risks:** TalkBack speaking order with two polite regions is unverified on a device (deferred). In one-face mode, the update cue for a face's last sticker plays on the face just left (the haptic still fires).
