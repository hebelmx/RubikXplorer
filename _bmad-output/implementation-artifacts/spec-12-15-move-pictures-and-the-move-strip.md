---
title: 'Move pictures and the Move strip'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f0fed8ec9f3d26b3451d1820b989c6687ee4131a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-14-solution-playback-in-3d-or-on-the-2d-net.md'
warnings: [oversized]
deferred:
  - summary: >-
      Lesson Demonstrations pass no MoveNote to the shared transport, so TalkBack hears the notation but no generated note there.
    evidence: |-
      Pre-existing: DemonstrationPlayer never passed `note` before this story (only Solution Playback and Algorithm detail do). Raised by blind-hunter and edge-case-hunter; adding it changes Lesson TalkBack output (a polite live region on every Move) and is outside 12.15's ACs.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt:131
    severity: low
---

<intent-contract>

## Intent

**Problem:** Beginners read notation slowly (GitHub #176). The shared playback transport shows only the current Move's notation and MoveNote; there is no Move strip, Solution Playback has no visible scrubber (UX-DR14 deferred from Story 3.6, `epic-3-deferred-3-6-ux-dr14-move-strip`), and no Move picture exists.

**Approach:** A pure move-to-picture function in a `:core:*` module returns an ink-only drawing description for every Move; `:app` only draws it. The shared transport (AD-20) gains the Move strip and visible scrubber, and shows the picture next to the current Move label and on each chip while the new *Move pictures* preference (default on) is on.

**Refs (binding, verbatim from the story):** FR-16, FR-19 · NFR-7 · AD-17, AD-20 · UX-DR14, UX-DR21, UX-DR29, UX-DR32 · Story 3.6 deferral (`epic-3-deferred-3-6-ux-dr14-move-strip`) · GitHub #176 · `sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md`.

## Boundaries & Constraints

**Always:**
- AD-20: the strip, scrubber and pictures live in the shared `PlaybackTransport`, so Demonstrations, Solution Playback and Algorithm detail all get them (SCP C3); notes come only from `MoveNote`.
- AD-4: a chip tap or a scrub is `PlaybackController.seek` (pauses, `Snap`, no animation). The existing `currentMove` is "the Move last played" (`focusIndex = max(position - 1, 0)`), so tapping chip *i* seeks to position *i + 1* and the outline lands on the tapped chip.
- AD-17: the picture is decorative (no content description; TalkBack speaks the MoveNote); every chip is a control with a label from `en.json`.
- Picture designs exactly as DESIGN.md *Move picture* / SCP §4.4 U4: ink-only (`ink-primary` lines, layer shaded `ink-secondary` ~35%); R/L/U/D curved arrow (R up, L down, U left, D right); F/B round arrow seen from the FRONT — F clockwise on the front grid, B drawn behind the grid reading counter-clockwise (PO decision 4; B's MoveNote unchanged); M down on middle column, E right on middle row, S on the Up face from above with a "top" tick; prime = plain picture with the arrow reversed; half turn = superscript 2; wide = two layers shaded, Fw/Bw doubled round arrow; x/y/z = R/U/F arrow across the face with a doubled outline, no shading.
- Physical-cube Mode: strip read-only (no chip action), scrubber hidden (FR-16, SCP C2).
- *Move pictures*: `Preferences.movePictures: Boolean = true` written only through `UpdatePreference` (`Preference.MovePictures`), Room column with default `1`, version 9 → 10 `AutoMigration` and exported `10.json`, reset by `PreferenceStore.reset()`; Settings toggle next to color-blind glyphs, label from `en.json`; applies at once. Pattern: Story 12.14 `playbackView`.

**Never:** a new port, module, persisted entity, feature flag or `SessionEvent`; Learner text in `strings.xml`; drawing pictures from sticker colors; a new screenshot library without PO approval (see Auto Run Result); editing planning artifacts or `sprint-status.yaml` action items.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Every Move | each of R L U D F B, M E S, wide, x y z × plain/prime/half | a picture per DESIGN.md | — |
| Prime | `X'` | `picture(X)` with arrow reversed | — |
| F / B | `F`, `B` | front grid; F clockwise, B behind grid counter-clockwise from front | — |
| Chip tap (virtual) | playing, tap chip *i* | paused, position *i + 1*, cube snapped, chip *i* outlined and in view | — |
| Scrub | drag to boundary *k* | paused, position *k*, snapped | — |
| Physical | Physical-cube Mode | strip shown read-only, no scrubber, *Done* only advances | — |
| Pictures off | `movePictures = false` | no picture by the label or on chips | — |
| Reset prefs | off, *Reset preferences* | back on | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Move.kt` -- sealed `Move`: `FaceTurn(face, turn)`, `WideTurn`, `SliceTurn(Slice.M/E/S)`, `CubeRotation(Axis.X/Y/Z)`, `Turn.CLOCKWISE/HALF/COUNTERCLOCKWISE`; `Face` U R F D L B. Input of the picture function.
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/MoveNote.kt` -- sibling: put `MovePicture` in the same package; `MoveNoteTest.kt` is the test pattern.
- `core/domain/.../playback/PlaybackController.kt:75-83` -- `scrubTo(fraction)` = `seek(round(fraction*size))`: pauses, one `Snap`. Chip taps reuse it.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- `PlaybackTransportState` (interface), `NetPlayback` (`moves`, `focusIndex = max(position-1,0)`, `currentMove`, `scrubTo`, `scrubNow(fraction, show)`), `PlaybackTransport(state, modifier, note, onScrub)` with the optional Slider `playback:scrub` (hidden when `onScrub == null`) and the `playback:counter` / `playback:move` row and `playback:note`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState` (`currentMove` = `moves[position]` in physical, `playback.currentMove` in virtual; `renderer` is `Cube3DRenderer` or `Net2DRenderer`; `alignToExpected` shows `scrubNow { replaceRenderer }`); virtual branch line ~420 calls `PlaybackTransport` without `onScrub`; private `PhysicalPlaybackControls` has its own counter + `playback:move` label + note.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` -- `DemonstrationPlayerState` (`presentedIndex`/`shownMoveIndex`, `currentMove` = presented Move, `scrubTo` = `scrubNow(fraction, renderer::snapNow)`); `DemonstrationPlayer` calls `PlaybackTransport` without `note`/`onScrub`, has `strings`.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt:130` -- already passes `onScrub = state::scrubTo`; its `algorithmDetail:strip` card (FR-18 Notation + recognition diagram) stays unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt:547` `NotationChip` -- chip look to copy (surface-stage, `shapes.small`, 1dp hairline / 1.5dp ink-primary border, `textStyles.notation`).
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt:203` `snapNow`; `Net2DRenderer` has none (a queued `Snap` waits behind an in-flight turn).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/ReduceMotion.kt` `rememberReduceMotion()`.
- Preference pattern (12.14 `playbackView`, commit fbed9c4): `core/application/.../preference/Preferences.kt` (+`PreferencesTest`, `UpdatePreferenceTest`), `app/.../data/PreferencesEntity.kt`, `app/.../data/RubikDatabase.kt` (version 9, AutoMigrations), `app/schemas/dev.cubentia.app.data.RubikDatabase/` (build-generated), `app/src/test/.../data/SessionDatabaseMigrationTest.kt`, `RoomPreferencesRepositoryTest.kt`, `PreferenceStoreTest.kt`, `app/.../preferences/LocalPreferences.kt` (`LocalPreferenceStore`).
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt:97` -- `SettingSwitch(R.string.settings_glyphs, "glyphs", …)`; takes `strings: ContentStrings` (empty until content is ready; `AppShell.kt:232`); calibration rows already resolve `en.json` keys.
- `core/domain/src/main/resources/content/strings/en.json` -- Learner text (`btn.playback.*`, `msg.playback.*`, `btn.settings.*`).
- Robolectric pixel pattern: `app/src/test/.../ui/library/LibraryScreenTest.kt:133` (`captureToImage().toPixelMap()`), `ui/solve/SolutionPlaybackTest.kt`; `CubentiaTheme(darkTheme = …)`; tests: `ui/playback/PlaybackTransportScrubTest.kt`, `NetPlaybackTest.kt`, `ui/settings/SettingsScreenTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/MovePicture.kt` -- pure `MovePicture.of(move): MovePicture` value: view (`FRONT`, or `TOP` with top tick for S), shaded cells (row/col of the 3×3), arrow (`CURVED`/`ROUND`/`STRAIGHT`, span, direction or CW/CCW, `behind`, `doubled`), `doubledOutline`, `superscriptTwo`; plus `reversed()`. Rules per Boundaries + Design Notes -- AD-1 pure core, JVM-tested.
- `core/domain/src/test/.../playback/MovePictureTest.kt` -- every Move × 3 turns has the specified picture; prime == plain `.reversed()` for every face, slice, wide and rotation; half == plain + superscript; F/B front view (B behind, CCW); S top view.
- `core/application/.../preference/Preferences.kt` + tests -- `movePictures: Boolean = true`, `Preference.MovePictures(value)` -- FR-19 single writer.
- `app/.../data/PreferencesEntity.kt`, `RubikDatabase.kt` (version 10, `AutoMigration(9, 10)`), generated `10.json`, migration + repository + store tests -- column `move_pictures` `@ColumnInfo(defaultValue = "1")`; 9→10 keeps the row with `move_pictures = 1`; `reset()` returns it to on.
- `core/domain/.../en.json` -- `btn.settings.move-pictures` "Move pictures"; `msg.playback.move-chip` "Move {index} of {total}, {notation}"; `msg.playback.move-chip-current` "Current move".
- `app/.../ui/playback/MovePictureView.kt` -- `MovePictureView(picture, modifier)`: Canvas drawing only (`inkPrimary` lines, shaded cells `inkSecondary` at 35% alpha, B arrow drawn before the grid, superscript "2"), `clearAndSetSemantics {}` so it has no description.
- `app/.../ui/playback/MoveStrip.kt` -- `MoveStrip(moves, currentIndex, pictures, strings, onSelect: ((Int) -> Unit)?)`: LTR `LazyRow` tag `playback:strip`, chip tag `playback:chip:$i` (picture ≥ 24dp above notation when `pictures`), current chip 1.5dp `inkPrimary` outline; each chip `clearAndSetSemantics` with the `msg.playback.move-chip` description, `stateDescription` = current text on the current chip, `Role.Button` + click only when `onSelect != null`; keeps the current chip in view (Design Notes).
- `app/.../ui/playback/PlaybackTransport.kt` -- interface gains `moves: List<Move>` and `currentIndex: Int?` (`NetPlayback`: null when empty else `focusIndex`); `PlaybackTransport` gains required `strings: ContentStrings`; reads `movePictures` from `LocalPreferenceStore`; picture tag `playback:picture` before `playback:move` (height = notation font size); after the note: `MoveStrip` (select chip *i* → `onScrub((i + 1) / size)`, read-only when `onScrub == null`), then the scrubber (moved below the strip).
- `app/.../ui/lesson/DemonstrationPlayer.kt` -- override `currentIndex` = `presentedIndex` (null when empty); pass `strings` and `onScrub = state::scrubTo`.
- `app/.../ui/library/AlgorithmDetailScreen.kt` -- pass `strings`.
- `app/.../ui/solve/SolutionPlayback.kt` -- `currentIndex` override (physical: `position` while `< size`, else null; virtual: playback's); `seekTo(fraction)`: `scrubNow` showing via `Cube3DRenderer.snapNow`, or `replaceRenderer` for the net; virtual transport gets `strings` + `onScrub = state::seekTo`; `PhysicalPlaybackControls` gets the picture before its `playback:move` label and a read-only `MoveStrip` (no scrubber).
- `app/.../ui/settings/SettingsScreen.kt` -- `settings:movePictures` switch row right after `settings:glyphs`, label `strings.resolve(btn.settings.move-pictures)`, writes `Preference.MovePictures`; shown only once content strings resolve the key (as the calibration rows wait for content).
- `app/src/test/.../ui/playback/MoveStripTest.kt` (+ update `PlaybackTransportScrubTest`, `SolutionPlaybackTest`, `SettingsScreenTest`, other callers) -- I/O matrix, chip semantics, read-only physical strip, no scrubber in physical, scroll-into-view, toggle on/off.
- `app/src/test/.../ui/playback/MovePictureScreenshotTest.kt` -- Robolectric `@GraphicsMode(NATIVE)` `captureToImage()` renders of R, R', R2, F, B, M, Rw, x in light and dark, and the transport with strip and pictures on and off, asserted by pixels/structure (ink color present; R shading in the right third only; R vs R' arrowhead at top vs bottom; R2 extra ink at the superscript; B ink under the grid lines; pictures-off has no picture nodes); no golden files, no new library.

**Acceptance Criteria:**
- Given `MovePicture.of` for every Move type and turn, then each picture matches DESIGN.md *Move picture* and the I/O matrix, and `MovePicture.of(m.inverse()) == MovePicture.of(m).reversed()` for every quarter-turn Move.
- Given any shared transport (Demonstration, Algorithm detail, virtual Solution Playback), when it shows, then `playback:strip` has one chip per Move with the current chip outlined in `inkPrimary` and in view, `playback:scrub` is visible below it, and a chip tap or a scrub pauses and snaps (no animation, AD-4) with the tapped chip current.
- Given Physical-cube Mode, then the strip shows read-only (chips have no click action), there is no `playback:scrub`, the current chip is the step to perform (none at the end), and only *Done* advances.
- Given *Move pictures* on, then `playback:picture` sits before the current Move label (transport and physical controls) and each chip has a picture; no picture node has a content description; each chip reads "Move 3 of 12, R" and the current chip adds "Current move"; the note still is the live MoveNote.
- Given Settings, then `settings:movePictures` follows `settings:glyphs`, is on by default, toggling writes `Preference.MovePictures` and hides/shows pictures at once, survives a restart, and *Reset preferences* turns it back on.
- Given `./gradlew spotlessCheck build`, then it passes, including the AC7 screenshot tests.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 24 findings — high 0, medium 0, low 17, false 7, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Room v10 schema `10.json` missing from the diff — the review diff excluded generated schemas on purpose; the file exists and is committed with the story.
  - `[low]` `[defer]` (blind) Lesson Demonstrations show no MoveNote — pre-existing (Demonstrations never had a note); deferred, see frontmatter.
  - `[low]` `[reject]` (blind) Scrub that rounds to the current boundary while a manual step is still animating is ignored — the turn ends on the same committed state; rare and the fix adds a renderer-state guard.
  - `[low]` `[reject]` (blind) Strip does not recheck visibility after a viewport/size change — chip width is fixed (min touch size), visibility is rechecked on every Move; rare (resize) and fix adds layout tracking.
  - `[low]` `[reject]` (blind) B arrow crossings with the grid are not gapped — B is dashed and drawn under the grid; visual detail left to the phase 5 device acceptance (PO reviews glyphs on device).
  - `[false]` `[reject]` (blind) x/y arrow drawn down the middle lane, not across the face — the arrow runs the full face length (4.5→19.5) with the deepest bow, plus the doubled outline; that is the specified "arrow across the face".
  - `[low]` `[patch]` (blind) Half-turn 2 overlaps the grid for U2/L2/D2 — fixed: the 2 now sits in the clear right margin (x = 23) level with the arrowhead; screenshot test asserts U2 adds ink only in the margin.
  - `[low]` `[reject]` (blind) Controls height cap ignores column spacing — non-weighted siblings are measured before the controls, the reserve only keeps a minimum stage; the scrubber stays reachable by scrolling.
  - `[low]` `[reject]` (blind) Snap tests seek before a turn starts — test-strength only; `scrubNow` discards outstanding items by construction (3D `snapNow`, net rebuilt).
  - `[low]` `[reject]` (blind) F/B screenshot asserts do not check arrow direction — direction is covered by `MovePictureTest`; the Canvas maps CW to a positive (clockwise) sweep; extra pixel geometry asserts not worth it.
  - `[low]` `[reject]` (edge) Strip visibility not rechecked on viewport change — same as the blind row above.
  - `[low]` `[reject]` (edge) Scrub to current boundary during a manual turn — same as the blind row above.
  - `[low]` `[defer]` (edge) Demonstration transport has no MoveNote — same as the blind row above.
  - `[false]` `[reject]` (edge) `10.json` omitted — same refutation as above.
  - `[low]` `[patch]` (verification-gap) Demonstration chip tap and scrub untested — added `DemonstrationPlayerTest` "a chip tap or a scrub pauses and snaps…" (playing → chip 1 → paused at 2, facelets snapped, chip current; scrub to 1).
  - `[low]` `[patch]` (verification-gap) Physical pictures-off untested — added `SolutionPlaybackTest.physicalModeFollowsTheMovePicturesPreference` (off: no label/chip pictures, strip and notation kept; on again: pictures back).
  - `[low]` `[patch]` (verification-gap) S top tick unrendered in tests — S (and U, U2) added to the light/dark screenshot samples with a top-tick pixel assert.
  - `[false]` `[reject]` (verification-gap, other) `10.json` omitted — same refutation as above.
  - `[low]` `[patch]` (intent) Chip/scrub behaviour tested only on Solution Playback — grouped with the Demonstration test patch above (Algorithm detail uses the same `DemonstrationPlayerState.scrubTo`).
  - `[low]` `[reject]` (intent) Drawings checked only by point probes on the AC7 samples — AC7's sample list is met with pixel asserts; exact visuals are phase 5 device acceptance.
  - `[false]` `[reject]` (intent) Fw/Bw shading reading — settled in Design Notes (Fw = F shading + doubled arrow, Bw = B), matching DESIGN.md ("second layer hidden from the front").
  - `[false]` `[reject]` (intent) Physical current chip = step to do — settled in Design Notes, matching the physical Move label.
  - `[false]` `[reject]` (intent) Settings row hidden until content loads — settled in Design Notes (calibration-row precedent).
  - `[low]` `[reject]` (intent) `playbackControlsScroll` not named in the spec — needed so the taller transport never crowds out the stage; covered indirectly by the `performScrollTo` test updates.

## Design Notes

**PO answers (2026-11-17), binding for this spec:** pictures default ON; scope includes the UX-DR14 strip and scrubber, closing `epic-3-deferred-3-6-ux-dr14-move-strip` (sprint-status update is the orchestrator's). F/B drawn from the front per the reference image. SCP §7 designs (S, wide, x/y/z) kept as written. Strip and pictures on every shared transport (AD-20). AC7 = Robolectric `captureToImage` with pixel/structure asserts, no golden files, no new library. Keep-in-view: animated scroll to the current chip, instant under reduce motion. Chip TalkBack: "Move 3 of 12, R" + current announced, from `en.json`; MoveNote stays on the main label; pictures have no description. Physical: picture next to the `PhysicalPlaybackControls` Move label and on the read-only chips.

**Settled UI choices (derived, no further PO input needed):**
- Chip tap *i* seeks to position *i + 1*: the transport's current Move is the Move last played, so the tapped chip becomes current and the label, note and picture show it. Physical current chip = `currentMove` (`moves[position]`), none at the end, matching the label.
- Keep in view: when `currentIndex` changes and that chip is not fully visible, `animateScrollToItem(index)` (or `scrollToItem` under `rememberReduceMotion()`); on first composition jump instantly.
- Order under the transport buttons/speed/counter/label/note: strip, then scrubber (DESIGN.md: strip under the transport, scrubber under the strip). Empty Move list: no strip, no scrubber.
- Pictures off: chips show notation only; no `playback:picture`.
- Solution Playback seek must not animate: the 3D view uses `snapNow`; the net (no `snapNow`) is rebuilt at the target (`replaceRenderer`), as `alignToExpected` already does.
- Algorithm detail keeps its existing FR-18 Notation card; the transport strip is the playback strip.

**Picture rules (front view, U on top, cells (row, col)):** R col 2 curved up; L col 0 down; U row 0 left; D row 2 right; F all 9 shaded + round CW over grid; B no shading + round CCW `behind`; M col 1 straight down; E row 1 right; S `TOP` view (top tick on far edge), row 1 shaded, right. Wide: Rw cols 1–2, Lw 0–1, Uw rows 0–1, Dw 1–2 with the face arrow; Fw = F with doubled round arrow; Bw = B with doubled arrow. x/y/z: R/U/F arrow spanning the whole face, no shading, `doubledOutline`. Prime = `reversed()` (direction flipped, CW↔CCW). Half = plain + `superscriptTwo` (same for any half token).

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test` -- MovePicture and preference tests pass.
- `./gradlew :app:testDebugUnitTest --tests '*Playback*' --tests '*MoveStrip*' --tests '*MovePicture*' --tests '*Settings*' --tests '*Preference*' --tests '*Migration*' --tests '*Demonstration*' --tests '*AlgorithmDetail*'` -- pass.
- `./gradlew spotlessCheck build` -- passes (schema `10.json` generated and committed).

## Auto Run Result

Status: done

**Summary:** Pure `MovePicture.of(move)` in `:core:domain` describes every Move's ink-only picture (prime = reversed, half = superscript 2, F/B from the front with B behind, S from above, wide and x/y/z designs). The shared `PlaybackTransport` (Demonstrations, Algorithm detail, virtual Solution Playback) now has the picture before the Move label, a Move strip (chips with pictures, current chip outlined in ink-primary and scrolled into view, animated or instant under reduce motion, TalkBack "Move 3 of 12, R" + "Current move") and the scrubber below it; a chip tap or scrub pauses and snaps. Physical-cube Mode shows the picture by its Move label and a read-only strip, no scrubber. *Move pictures* preference (default on, Room v10 column default 1, AutoMigration 9→10, reset to on) with a Settings switch after color-blind glyphs, label from `en.json`.

**Files changed:**
- `core/domain/.../playback/MovePicture.kt` (+ `MovePictureTest.kt`) — pure picture description.
- `app/.../ui/playback/MovePictureView.kt` — Canvas drawing, no semantics.
- `app/.../ui/playback/MoveStrip.kt` — the strip and chips.
- `app/.../ui/playback/PlaybackTransport.kt` — `moves`/`currentIndex`, `strings`, picture label, strip, scrubber, controls scroll cap.
- `app/.../ui/lesson/DemonstrationPlayer.kt`, `ui/library/AlgorithmDetailScreen.kt`, `ui/solve/SolutionPlayback.kt` — hosts wired (scrub/seek, physical read-only strip).
- `app/.../ui/settings/SettingsScreen.kt` — *Move pictures* switch.
- `core/application/.../preference/Preferences.kt`, `app/.../data/PreferencesEntity.kt`, `RubikDatabase.kt`, `app/schemas/.../10.json` — preference and migration.
- `core/domain/.../content/strings/en.json` — three keys.
- Tests: `MoveStripTest`, `MovePictureScreenshotTest` (Robolectric NATIVE, light/dark, no goldens), `SolutionPlaybackTest`, `DemonstrationPlayerTest`, `SettingsScreenTest`, preference/migration/store tests, layout-driven `performScrollTo` updates in Algorithm detail and shell tests.

**Review:** 24 findings — 4 patched (all low: half-turn 2 placement; Demonstration seek test; physical pictures-off test; S/U/U2 screenshot samples), 1 deferred (Demonstration MoveNote, pre-existing), 19 rejected (7 false, 12 low; reasons in the Review Triage Log). The implementer's unrequested MoveNote on Demonstrations was removed before review (out of scope).

**Follow-up review recommended:** false — no high and no medium patched (patched: high 0, medium 0, low 4).

**Verification:** `./gradlew :core:domain:test :core:application:test spotlessCheck build --no-parallel --max-workers=2` — BUILD SUCCESSFUL (all JVM tests, lint, Kover, APKs) after the patches; targeted `MovePictureScreenshotTest`, `DemonstrationPlayerTest`, `SolutionPlaybackTest` pass. I/O matrix rows each covered by a passing test.

**Residual risks:** picture drawings (S, wide, x/y/z, B's behind-the-grid look) and TalkBack wording await PO visual acceptance in the phase 5 device session; sprint-status action item `epic-3-deferred-3-6-ux-dr14-move-strip` to be closed by the orchestrator.
