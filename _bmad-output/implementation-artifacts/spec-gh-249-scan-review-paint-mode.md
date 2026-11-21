---
title: 'GH-249 Sticker review net: paint mode (long-press a color to fix many stickers quickly)'
type: 'feature'
created: '2026-11-20'
status: 'done'
baseline_revision: 'd2bfe4bb3e2b3b394bddc3fd0d7d080406cc3ca6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-20-v1-1-promotion.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/DESIGN.md'
warnings: ['oversized']
deferred:
  - summary: >-
      TalkBack speech of the paint-mode announcer and device acceptance are unverified.
    evidence: |-
      Tests prove only the polite live-region node and its text for about 1.5 s. Whether TalkBack speaks "Painting {color}" / "Painting off" from the visually empty 1dp node, and whether the lock action appears twice in the actions menu (onLongClickLabel plus a custom action), needs real TalkBack. Settle in the PO device session (Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE, main app build, Solve tab).
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/editor/PaintMode.kt (PaintAnnouncer); ManualCubeEditor.kt (Swatch)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Fixing a whole face on Scan review (and in the Manual Cube Editor) takes about 16 touches, because each sticker needs tap-sticker-then-color.

**Approach:** Meet the #249 amendment ACs of Story 10.3 (epics.md): a palette swatch long-pressed (or its *Lock this color* accessibility action) locks that color into paint mode, shared by Scan review and the Editor; each sticker tap then sets the locked color directly. Exit, visuals, copy and TalkBack exactly per EXPERIENCE.md "Sticker review net" (Paint mode) and DESIGN.md "Sticker review net" / "Color palette".

## Boundaries & Constraints

**Always:** Outside paint mode, tap-sticker-then-color, auto-advance, the pulse, the coach mark and every existing test behave exactly as today. Learner text only in `en.json` via `Text(key,args)` (`checkLearnerText`); color names lower-case mid-sentence ("Painting red"), as `SetStickerLine` does. Each paint-mode sticker tap plays the update cue (`StickerCue`) and the haptic tick (`LocalHaptics` gate) and counts as a change (Scan review `changed`, Editor `onChange`). Centers stay non-editable. Paint state survives recreation (`rememberSaveable`). TalkBack announcements via a polite live region. Editor stays a non-session (AD-3).

**Never:** Painting a sticker on a plain swatch tap while paint mode is on; changing `ScanReview` (domain) semantics; Quick Scan review retake (Story 10.17); new strings in `strings.xml`; deprecated `View.announceForAccessibility`; any change beyond paint mode.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Start | long-press Red swatch (or its *Lock this color* action) | Red swatch: 3dp `accent` outline + ✓ badge; instruction "Painting red. Tap stickers to set them. Tap red again to stop."; announce "Painting red"; net shows no selected ring/pulse; selected-sticker line hidden | none |
| Paint 9 | paint mode Red, tap 9 editable stickers | each becomes red with cue + haptic; selection does not move; no face-done line; 1 long-press + 9 taps total | center tap ignored |
| Same color | paint mode Red, tap a red sticker | still cue + haptic; Editor marks it set | none |
| Stop by locked swatch | plain tap, long-press, or *Stop painting* action on Red | paint mode off; announce "Painting off"; instruction, ring/pulse, selected line back as before | none |
| Other color | paint mode Red, plain tap Blue | paint mode off, "Painting off"; no sticker painted by this tap; next plain taps behave as before | none |
| Switch lock | paint mode Red, long-press Blue | paint mode Blue, "Painting blue" | none |
| Retake | Scan review paint mode, tap *Retake faces* | paint mode off, "Painting off" (announced also in retake mode), retake mode as today; on *Cancel* review returns not painting | none |
| Editor turn fit | Editor paint mode, a paint completes a uniquely resolvable cube | same turn/redraw/`turned` note as a plain paint | none |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- `Swatch` (L537-, internal, shared by both screens: `clickable` + `contentDescription` + `testTag("swatch:COLOR")`), editor palette `FlowRow` (~L207-222), `instruction(...)` line `editor:instruction` (polite), `SelectedStickerLine` `editor:selected`, `FaceDoneLine` (live-region + spoken `contentDescription` pattern to copy), `StickerReviewNet` call (~L183).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- `paint(color)`: sets selected sticker, `set`, `turned`, `cueIndex`, advances selection, records `faceDone`, applies `fit` turn (moves `set`/`selected`/`cueIndex`). Needs a sibling that paints a given index without advancing or `faceDone`, sharing the fit logic. `isEditable`, `Saver`.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- `ScanReviewScreen` switches `RetakeMode`/`ReviewMode` (L108-147; ReviewMode state is dropped in retake, so paint state and the announcer must live in `ScanReviewScreen`); palette `FlowRow` L315-339 (paint path: `review.paint`, `changed`, `focusedIndex`, `cue`, `lastSet`, haptic); `instruction(...)` L504 for `review:instruction`; `SelectedStickerLine` `review:selected`; net call L292 (`selected`, `onStickerClick`).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanReview.kt` -- read-only: `select(i)`, `paint(color)` (auto-advances), public constructor `ScanReview(stickers, selected, evidence)`; a paint-mode tap can be `review.select(i).paint(c)` re-wrapped with the previous `selected`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- read-only reuse: `RetakeFace` ✓ badge (accent dot, `onAccent` ✓, `CHECK_SIZE_FRACTION`, size-fixed font) and `RETAKE_OUTLINE_DP` = 3 to mirror on the locked swatch; `colorName(color)`; passing `selected = null` hides ring and pulse.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/MoveKeyBar.kt` L181 -- `combinedClickable(onLongClick, onLongClickLabel)` precedent. `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` L640 -- `customActions` precedent.
- `core/domain/src/main/resources/content/strings/en.json` -- Learner copy (keys `msg.scan.*`, `msg.editor.*`, `btn.*`).
- Tests: `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt`, `ManualCubeEditorCompactTest.kt`, `ManualCubeEditorStateTest.kt`, `EditorFixtures.kt` (Robolectric Compose patterns).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add keys for: instruction "Painting {color}. Tap stickers to set them. Tap {color} again to stop.", announcements "Painting {color}" and "Painting off", action labels "Lock this color" and "Stop painting" -- copy from EXPERIENCE.md.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/PaintMode.kt` (new) -- shared paint-mode palette: the six swatches (W Y R O G B) with `locked: CubeColor?`, plain tap / long-press / custom-action handling per the matrix, emitting lock/stop and plain-color events; a visually empty polite live-region announcer (`testTag("paint:announce")`, `contentDescription` = latest announcement); the paint instruction text with a small decorative glyph chip of the locked color (hidden from TalkBack) -- built once for both screens (SCP Q5).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- `Swatch`: optional `locked` (3dp `accent` outline + ✓ badge, `selected` semantics), `onLongClick` via `combinedClickable`, `customActions` *Lock this color* / *Stop painting*; default arguments keep today's look and click. Editor: saveable paint color; palette from PaintMode; in paint mode sticker taps paint directly, net `selected = null`, selected line hidden, instruction replaced, `FaceDoneLine` gets no new event.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- add paint-at-index (no selection advance, no `faceDone`, same set/cue/turn-fit handling); refactor `paint` to share it without behavior change.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- hoist saveable paint color + announcer to `ScanReviewScreen`; ReviewMode uses PaintMode palette; paint-mode tap = paint index keeping previous selection, `changed = true`, cue, `lastSet`, haptic, `focusedIndex` as a plain paint; `onRetakeFaces` ends paint mode first.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorTest.kt`, `ManualCubeEditorStateTest.kt` -- cover every matrix row on both screens (long-press via `performTouchInput { longClick() }`, custom actions by label), including the 1 long-press + 9 taps face fix, outline/✓ semantics, announcer text, and plain-tap regression.

**Acceptance Criteria:**
- Given the Scan review or Editor palette, when the Learner long-presses a color or uses *Lock this color*, then paint mode starts: locked outline and ✓, the paint instruction line, TalkBack "Painting {color}", and auto-advance and the next-sticker pulse pause.
- Given paint mode, when the Learner taps stickers, then each tap sets the locked color with the update cue and haptic tick, so 9 stickers of one color take 1 long-press + 9 taps.
- Given paint mode, when the Learner taps or long-presses the locked color, plain-taps another color, or enters retake mode, then paint mode ends, TalkBack announces "Painting off", and tap-sticker-then-color behaves exactly as before.
- Given no paint mode, when the existing Scan review and Editor test suites run, then they pass unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 28 findings — high 0, medium 1, low 13, false 10, maybe-false 4
- findings:
  - `[maybe-false]` `[defer]` (intent) Acceptance lives at device/main-app level (Lenovo, S24 FE, Solve tab); diff proves only component level — settled by the PO device session, a human gate outside this build; deferred with I2.
  - `[maybe-false]` `[defer]` (intent) TalkBack actually speaking the 1dp live-region announcer is unproven — settle with real TalkBack on a device; deferred (medium, unverified).
  - `[false]` `[reject]` (intent) Plain tap on another color: only-ends vs ends-and-paints — EXPERIENCE.md lists it purely as an exit and says "Plain tap behavior is unchanged outside paint mode"; with the selection hidden, painting it would paint a hidden sticker. Settled as only-ends.
  - `[false]` `[reject]` (intent) Pause hides more than auto-advance and pulse — the ring is the pulse (DESIGN.md "next-sticker pulse is hidden"); selected/suspect lines name that hidden selection; instruction replacement is in the AC.
  - `[false]` `[reject]` (intent) "9 stickers" not literally one face — Editor test does 1 long-press + 9 taps; the AC is a touch count.
  - `[false]` `[reject]` (intent) Quick Scan review retake not exercised — arrives with Story 10.17, excluded by the intent.
  - `[low]` `[patch]` (blind) Same-color paint-mode tap set `changed`, so Close asked to discard — now only on a real sticker change, as the plain path; test added.
  - `[false]` `[reject]` (blind) focusedIndex from stroke.selected — focusing the counts line after the last blank is the intended #174 behavior; the selection is paused on purpose; now tested with evidence.
  - `[medium]` `[patch]` (blind) Paused selection stale after painting it, so "Tap the flashing one" points at a filled sticker — painting the paused selected sticker keeps paint's own next target; test added.
  - `[low]` `[reject]` (blind) System Back does not end paint mode — the intent lists the exits exhaustively ("do exactly this"); adding one needs a new handler.
  - `[maybe-false]` `[reject]` (blind) Lock action listed twice (onLongClickLabel + custom action) — settle with TalkBack's actions menu; if true only low (cosmetic), and removing either weakens the required action.
  - `[low]` `[patch]` (blind) Announcer is a permanent invisible swipe stop — now shows each announcement ~1.5 s then clears (FaceDoneLine pattern), per-event serial; tests added.
  - `[false]` `[reject]` (blind) Lower-casing translated color names — English-only content; same pattern as existing SetStickerLine.
  - `[low]` `[patch]` (blind) Restored announcement re-spoken after recreation; double speak — Saver now persists only the locked color (grouped with the announcer fix); selected-state speech on a focused swatch is TalkBack standard.
  - `[false]` `[reject]` (blind) Saver ordinals may crash on enum change — saved instance state does not survive an app update; same pattern as ManualCubeEditorState.Saver.
  - `[low]` `[reject]` (blind) Scan review paint state outlives a new review — paint mode stays visible (outline, instruction) and one tap stops it; retake already stops it.
  - `[low]` `[reject]` (blind) Compact one-face paint mode untested — sticker taps only reach the shown face, so no guard is missing; test-only gap, rare.
  - `[low]` `[reject]` (blind) No 200% ✓ badge / chip-hidden tests — badge font is sized to the badge, not the font scale; cosmetic.
  - `[false]` `[reject]` (blind) SetStickerLine speaks per paint-mode tap — intended per-tap confirmation, the same as a plain paint.
  - `[low]` `[patch]` (vgap) Haptics-off untested for paint mode — tests added on both screens (long-press without reset + paint tap → 0 ticks).
  - `[low]` `[patch]` (vgap) Paint mode never tested with scan evidence — test added: painted Labs dropped, suspect removed, counts line focused.
  - `[low]` `[reject]` (edge) Editor full-net face tabs move the hidden selection while painting — rare; behavior after Stop is a valid selection; a fix adds a branch.
  - `[false]` `[reject]` (edge) One-face editor switching face clears selection — identical to plain-mode showFace, so behavior "exactly as before".
  - `[false]` `[reject]` (edge) One-face Scan review tab moves the selection while painting — needed: the plain palette paints only on the shown face, so after Stop it works.
  - `[maybe-false]` `[reject]` (edge) Duplicate TalkBack action — same as the blind finding above.
  - `[low]` `[patch]` (edge) Editor paint state not keyed on initial/fresh — `rememberPaintMode(initial, fresh)`; test added.
  - `[low]` `[reject]` (edge) Scan review paint state unkeyed on a new review — same as the blind finding: visible and one tap stops it; keying on review would reset on every paint.
  - `[low]` `[reject]` (edge) Back while painting — same as the blind Back finding.

## Design Notes

- Plain tap on another color while painting only ends paint mode: in paint mode no sticker is shown selected (pulse hidden, auto-advance paused), and painting a hidden selection would break the "palette never paints a hidden sticker" rule (`ManualCubeEditorState.showFace`). After stopping, the selection is the one held before paint mode started (paused, not moved).
- Announcer pattern (golden):
  ```kotlin
  Box(Modifier.size(1.dp).testTag("paint:announce").semantics {
      liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
  ```
  Change the text on every on/off event (re-announce on a repeat by alternating an invisible marker or keying on a serial).

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply :app:testDebugUnitTest --tests '*ScanReviewScreenTest*' --tests '*ManualCubeEditor*'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** Paint mode (#249) in the shared sticker review palette, used by Scan review and the Manual Cube Editor. A long-press or *Lock this color* locks a swatch (3dp accent outline and ✓). Each sticker tap then sets that color with the cue and haptic tick, while the selection, pulse and auto-advance pause. A tap, long-press or *Stop painting* on the locked swatch, a plain tap on another color, or *Retake faces* ends it. A polite live region announces "Painting {color}" / "Painting off".

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/PaintMode.kt` (new): paint state, palette, timed announcer, instruction line with a glyph chip.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt`: Swatch lock visuals, long-press and accessibility action; Editor paint mode.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt`: `paintAt` (no advance, no face-done, same turn fit); `paint` refactored to share it.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt`: paint state hoisted above retake mode; paint-mode taps; retake ends paint mode.
- `core/domain/src/main/resources/content/strings/en.json`: five copy keys.
- Tests: `ScanReviewScreenTest.kt`, `ManualCubeEditorTest.kt`, `ManualCubeEditorStateTest.kt`.

**Review:** 28 findings. Patched 8, all low except one medium: same-color no-op change, stale paused selection (medium), timed announcer, unsaved announcement, editor keying, haptics-off tests, evidence test. Deferred: on-device TalkBack and device acceptance (unverified). Every rejected finding and its reason is in the Review Triage Log.

**Follow-up review:** false. Patched counts: high 0, medium 1, low 7.

**Verification:** `./gradlew spotlessApply :app:testDebugUnitTest --tests '*ScanReviewScreenTest*' --tests '*ManualCubeEditor*'` passed: 175 tests (72 + 43 + 35 + 25), 0 failures. `./gradlew spotlessCheck build` gave BUILD SUCCESSFUL. Every matrix row is covered by a passing test.

**Residual risks:** Real TalkBack speech of the 1dp timed live region, and a possible duplicate long-press action in TalkBack's menu, are unverified until the device session on the Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE.
