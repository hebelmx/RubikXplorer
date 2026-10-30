---
title: 'Story 2.4: Watch an explained Demonstration'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'a9084a1276cad4b5d9df44cbdeb4b9038b5022c5'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Lesson.demonstration is never checked against the concatenated steps[].moves that playback uses.
    evidence: |-
      Demonstration.of plays steps[].moves and ignores Lesson.demonstration; ContentValidator (Story 2.1) parses both but never compares them, so an author can ship a demonstration string that disagrees with the steps. Pre-existing content-validation gap; settle by adding a validator rule (or dropping the field) when Story 2.7 authors Stages 1-3.
    location: >-
      core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentValidator.kt
    severity: low
  - summary: >-
      The dim overlay and highlight outline are only verified through semantics flags, not pixels.
    evidence: |-
      DemonstrationPlayerTest reads StickerDimmedKey/StickerHighlightedKey, set from the same booleans as the drawWithContent block; deleting the draw calls would keep every test green. The repo has no screenshot/image-capture setup (no roborazzi/paparazzi/captureToImage). Settle by adding image capture when Epic 5 (3D highlights) or the UX-DR contrast story lands.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** A Lesson carries a Demonstration (explained steps of Moves), but nothing can play a Move sequence, and the 2D net cannot dim or highlight pieces, so a Learner cannot watch what changes before trying it (FR-6, AR-20).

**Approach:** Add one pure, immutable `PlaybackController` over `List<Move>` in `:core:domain/playback` that emits positions and presentation items, plus a pure per-step focus (Keep / target / involved pieces) derived from the Lesson's existing predicates. In `:app`, add a `DemonstrationPlayer` composable: the 2D net with dimming and the violet target outline, and the shared playback transport (|◀ ◀ ▶/❚❚ ▶ ▶|, speed, step counter, current Move). Under reduce motion it steps (Snap) instead of animating.

## Boundaries & Constraints

**Always:**
- `PlaybackController` is pure Kotlin, immutable, never reads a clock: time arrives as `advance(elapsedMillis)`. Every operation returns `PlaybackUpdate(controller, items)`. Items: `PlaybackItem.Animate(move, position, target: CubeState)` / `PlaybackItem.Snap(position, target)`; `target == start.apply(moves.take(position))`.
- Operations: `play()`, `pause()`, `stepForward()`, `stepBack()`, `replay()`, `withSpeed(PlaybackSpeed)`, `scrubTo(fraction: Double)`, `seek(position: Int)`, `advance(elapsedMillis: Long)`. `PlaybackSpeed` = `HALF(0.5)`, `NORMAL(1.0)`, `DOUBLE(2.0)`; one Move every `STEP_MILLIS / factor` ms (`STEP_MILLIS = 600`).
- `reduceMotion = true` turns every would-be `Animate` into `Snap` at the same position and cadence.
- Never checkpointed: the controller is not `@Serializable`, and no `SessionState` / `SessionEvent` / `Effect` property holds a playback type (test proves it).
- Focus per Demonstration step, from the step's start state `s0` and end state `s1`: Keep = `lesson.preserve.preservedPieces(s0)`; target = `lesson.completion.preservedPieces(s1) − lesson.completion.preservedPieces(s0)`; involved = pieces whose sticker positions differ from `s0` at any Move of the step. Lit = centers ∪ Keep ∪ involved ∪ target; every other sticker is dimmed; target stickers get the highlight outline. Stickers are never recolored.
- The Demonstration's Move list is the concatenation of `lesson.steps[].moves` from `lesson.entryState`. The focused step is the step that contains Move index `max(position − 1, 0)`; the transport's "current Move" is that Move. Step counter shows `position / moves.size` (Moves played).
- Highlight = 3dp `sticker-highlight` outline with a 1dp `cubie-body` inner stroke; dim = `sticker-dimmed-overlay` (#00000099) drawn over the sticker. Add the dim token to `CubeTokens`.
- All visible chrome text (transport labels, speed labels, counter format) lives in `strings.xml`; no multi-word literals in main sources (LearnerTextPolicy).
- Domain failures are sealed results: building a Demonstration from a Lesson returns `DemonstrationResult.Ready | InvalidNotation | InvalidEntryState`.

**Never:**
- No Lesson screen, navigation, step card (Goal/Keep/Watch/Notice text), Teaching Modes, Hint, or `SessionHost` dispatch — Story 2.5.
- No content schema change (no new Step/Lesson fields), no move-arrow overlay, no MoveNote, no Settings reduce-motion override.
- Playback never dispatches `SessionEvent`s and never mutates `SessionState`; the renderer never mutates state.
- No changes to `Net2DRenderer` queue/drain semantics; emphasis is an optional draw parameter defaulting to none (free cube unchanged).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Play | pos 0 of 3, paused, NORMAL | `play()` → playing, emits `Animate(m0, 1)` now; `advance(600)` → `Animate(m1, 2)`; `advance(1200)` → `Animate(m2, 3)`, then paused at end | No error expected |
| Speed | playing, HALF / DOUBLE | next Move after 1200 ms / 300 ms of `advance`; change mid-play keeps accumulated time | No error expected |
| Pause | playing | `pause()` → no items; later `advance` emits nothing | No error expected |
| Step forward / back | pos 1, playing | `stepForward()` → paused, `Animate(m1, 2)`; `stepBack()` → paused, `Animate(m1.inverse(), 1)` | at end / at 0: no-op, empty items |
| Replay | pos 3 (end) | `replay()` → pos 0, playing, `Snap(0, start)` then `Animate(m0, 1)` | No error expected |
| Play at end | pos == size | `play()` behaves as `replay()` | No error expected |
| Scrub | 7 Moves, `scrubTo(0.4)` | snaps to pos 3 (`round(0.4 × 7)`), paused, one `Snap(3, state3)` | fraction clamped to 0..1; `seek` clamped to 0..size |
| Reduce motion | `reduceMotion = true` | every item above that would be `Animate` is `Snap` at the same position | No error expected |
| Empty list | 0 Moves | every op returns empty items; counter 0 / 0; transport controls disabled | No error expected |
| Bad lesson | unparsable step moves / entry state | `DemonstrationResult.InvalidNotation` / `InvalidEntryState` | sealed result, no throw |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/{CubeState,Move,Notation,Facelets}.kt` -- `CubeState.apply(moves)`, `toFacelets()`, `Move.inverse()`, `Notation.parse(text)` → `NotationResult.Parsed(moves)|Invalid`, `Notation.format(move)`. `FaceletTables` (internal, `Facelets.kt:40`) has `CORNER_FACELETS`/`EDGE_FACELETS` sticker-index triples/pairs and `center(face)` — usable only inside `:core:domain`, so the sticker→piece lookup must live there.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,Piece,WhiteLayer,StateReached}.kt` -- `preservedPieces(state): Set<Piece>`; `Piece(colors: Set<Color>)` is orientation-invariant. Add a public lookup (e.g. `Piece.at(facelets, index): Piece?`, null for centers) for the renderer.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/{Model,FaceletString,ContentValidator}.kt` -- `Lesson.entryState`, `steps[].moves`, `preserve`, `completion`; decode entry like `ContentValidator.entryStateProblem` (lines 78–90): `FaceletString.decode` → `FaceletStringResult.Decoded` → `CubeValidator.validate` → `ValidationResult.Valid.state`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/` -- new package (spine lists `playback/` in `:core:domain`).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/{SessionState,SessionEvent,Effect}.kt` -- read-only; the never-checkpointed test reflects over them.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/{Net2DRenderer,PresentationItem,CubeRenderer}.kt` -- `submit(items)`, `Content(style, modifier)`, `renderedFacelets()`, sticker semantics keys (`StickerColorKey`). Extend `Content` with an optional emphasis param (lit pieces + highlighted pieces, mapped through `rendered` at draw time so the outline follows the moving piece) and add `StickerDimmedKey` / `StickerHighlightedKey` semantics. `PresentationItem.revision` must increase per submitted item — keep a local counter in the player.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/{Tokens,Type}.kt` -- `CubeTokens.cubieBody`, `stickerHighlight`; add `stickerDimmedOverlay`. `LocalRubikTextStyles.notation` / `.data` for the current Move and counter.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmation.kt` -- package and test-tag style to mirror.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/TestLessons.kt` -- `whiteCrossLesson()` (entry SOLVED+F, step `F'`): Keep = 3 correct cross edges, target = white-green edge, involved = F-layer pieces; dimmed = pieces neither on the F layer nor cross edges (e.g. the DB edge, the DBL corner).
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmationTest.kt:41-70` -- Robolectric + `createComposeRule` + `RubikXplorerTheme` template; `rule.mainClock.autoAdvance = false` + `advanceTimeBy` for timing.
- `build-logic/.../policy/LearnerTextPolicy.kt` -- multi-word literals in main sources fail the build.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/{PlaybackController,PlaybackSpeed,PlaybackItem}.kt` -- immutable controller per Always rules -- AC 1, AC 3.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/Demonstration.kt` -- `Demonstration.of(lesson): DemonstrationResult`; `start`, `moves`, `steps` with `StepFocus(keep, target, involved)`, `stepIndexAt(moveIndex)` -- AC 2.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/Piece.kt` -- sticker→piece lookup -- AC 2.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/playback/{PlaybackControllerTest,DemonstrationTest}.kt` -- every I/O matrix row; focus for an SOLVED+F / `F'` lesson and a two-step lesson; lookup for all 54 stickers -- AC 1–3.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/PlaybackNotCheckpointedTest.kt` -- reflect over `SessionState`/`SessionEvent`/`Effect` subclasses: no property type from `dev.rubikxplorer.domain.playback`; controller class has no generated `serializer` -- AC 1.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt` + `ui/theme/Tokens.kt` -- emphasis drawing + semantics, dim token -- AC 2.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/{DemonstrationPlayer,ReduceMotion}.kt` + `res/values/strings.xml` -- state holder (`rememberDemonstrationPlayerState(demonstration, reduceMotion)` exposing its `renderer`), frame loop calling `advance`, transport, counter, current Move; `rememberReduceMotion()` reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` -- AC 2, AC 3.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayerTest.kt` -- dim/lit/highlight semantics per sticker, counter + current Move text after play/step, reduce-motion snap vs animate timing -- AC 2, AC 3.

**Acceptance Criteria:**
- Given a pure `PlaybackController` over `List<Move>`, when play, pause, step forward, step back, replay and speed (0.5×/1×/2×) are invoked, then it emits the expected position and presentation items, scrubbing snaps to Move boundaries, and a test proves it is never part of session state.
- Given `DemonstrationPlayer` showing `whiteCrossLesson()` on the 2D net, when its step plays, then stickers of pieces outside the step are dimmed, the Keep (cross edges) and centers stay lit, the white-green edge's stickers carry the highlight outline, and the transport shows the counter (`0 / 1` → `1 / 1`) and the current Move `F'`.
- Given reduce motion on, when the Demonstration plays, then the renderer receives `Snap` items and `renderedFacelets()` equals the step's target within one frame (no 200 ms flip), while with reduce motion off it does not.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 34 findings — high 0, medium 8, low 23, false 3, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) a Learner cannot reach the player in the running app — the epic's story list gives the navigation shell and Lesson screen to Story 2.5; the AC surfaces (controller, net on a Demonstration, reduce motion) are all exercised.
  - `[low]` `[defer]` (intent) `Lesson.demonstration` unused; Moves come from `steps[].moves` — grouped with blind #7; pre-existing validator gap (deferred list).
  - `[low]` `[reject]` (intent) no scrub control in the transport — AC 1 asks that the controller snap scrubbing to Move boundaries, which it does and is tested; a slider adds UI no AC requires.
  - `[false]` `[reject]` (intent) no in-app reduce-motion override — the Settings override is its own later story (epics Settings story); "reduce motion is on" via the system setting meets AC 3.
  - `[false]` `[reject]` (intent) "never checkpointed" only tested structurally — no Checkpoint store exists until Epic 6; nothing can persist playback, and the structural test proves no session type carries it.
  - `[medium]` `[patch]` (intent) the one-step, one-Move UI fixture cannot tell step vs Move readings apart — grouped with verification-gap #2; added a two-step player test.
  - `[low]` `[reject]` (blind) play and step forward share the `▶` glyph — the glyph set is DESIGN.md's literal transport (|◀ ◀ ▶/❚❚ ▶ ▶|) and each button has a distinct spoken description.
  - `[low]` `[reject]` (blind) "current Move" shows the first Move both before and after it plays — the spec defines it as `moves[max(position − 1, 0)]`; the counter disambiguates; fix would edit the spec.
  - `[medium]` `[patch]` (blind) frame loop passes an uncapped delta, so a stall bursts several Moves — grouped with edge #3; delta now capped at `speed.stepMillis`.
  - `[medium]` `[patch]` (blind) no UI test runs the frame loop — grouped with verification-gap #1; added 3-Move cadence tests at 1× and 2×.
  - `[low]` `[patch]` (blind) restart test cannot fail — same group; restart from the end now asserts the counter resets to `1 / 3` and the net is back at the start.
  - `[low]` `[reject]` (blind) never-checkpointed scan is one level deep — no session field type today holds nested app state of that kind; recursive scanning adds machinery for a hypothetical; the AC is met for the current types.
  - `[low]` `[defer]` (blind) `lesson.demonstration` never checked against steps — pre-existing content-validator gap; deferred list.
  - `[low]` `[reject]` (blind) reduce motion read once; a change rebuilds the player — changing the system animator scale mid-Demonstration is rare; a ContentObserver adds machinery.
  - `[low]` `[reject]` (blind) configuration change resets playback to 0 — playback is ephemeral by design (never checkpointed); restoring position adds saver state with no AC.
  - `[low]` `[reject]` (blind) emphasis invisible to TalkBack — the cube semantics summary (UX-DR29) is a separate accessibility story; this AC's surface is visual.
  - `[low]` `[patch]` (blind) speed selector lacks radio semantics — added `Role.RadioButton` per button and `selectableGroup()` on the row.
  - `[low]` `[reject]` (blind) focus switches up to 200 ms before the flip reaches the next step — cosmetic lag; renderer-driven focus would add a callback API.
  - `[low]` `[reject]` (blind) `Piece.at` linear search per sticker — 54 × ≤20 comparisons per composition; no measurable cost shown.
  - `[low]` `[reject]` (blind) private `apply` shadows the scope function; `pause()` always copies — no caller is confused today; an extra recomposition on pause is negligible.
  - `[low]` `[reject]` (blind) `scrubTo` public with no UI — kept for Story 2.5 / Solution Playback reuse (AD-20); exercised by controller tests.
  - `[low]` `[patch]` (blind) loose details — glyph/speed/counter strings marked `translatable="false"`; the matrix-row wording would edit the spec (rejected); the empty triage log is filled by this pass (false).
  - `[medium]` `[patch]` (verification-gap) frame loop never run by an app test — added 3-Move cadence tests (1×, 2×, 2× selected before play); 15 player tests green.
  - `[medium]` `[patch]` (verification-gap) multi-step focus never tested in the player — added a two-step lesson test asserting each step's target outline on the drawn facelets.
  - `[medium]` `[patch]` (verification-gap) `rememberReduceMotion` tested only at 0f; default wiring unused — added the 1f → false case and a player test that omits `reduceMotion`; `@After` resets the scale.
  - `[medium]` `[defer]` (verification-gap) overlay/outline drawing not pixel-checked — no image-capture infrastructure exists in the repo (pre-existing); deferred list.
  - `[low]` `[reject]` (edge) `scrubTo(NaN)` throws in `roundToInt` — no caller passes a fraction yet; loud failure on a programmer error is acceptable; guard for an undemonstrated input.
  - `[low]` `[reject]` (edge) switching HALF → DOUBLE with accumulated time emits up to 3 Moves on the next advance — the spec requires speed changes to keep accumulated time; clamping would edit the spec.
  - `[medium]` `[patch]` (edge) frame stall bursts the remaining Moves — same group as blind #3; delta capped.
  - `[low]` `[reject]` (edge) highlight inner stroke gets a negative size on stickers under 7dp — net cells are ~30dp on the narrowest phone; not reachable in layout.
  - `[low]` `[patch]` (edge) steps with no Moves still dim the net — `emphasis` now null when `size == 0`; test added.
  - `[low]` `[reject]` (edge) scan misses wildcard/array generic types — no session field uses them; same reasoning as blind #6.
  - `[low]` `[reject]` (edge) scan does not recurse into field types — same group as blind #6.
  - `[low]` `[reject]` (edge, claim) scan uses `declaredClasses`, missing top-level subtypes — all current variants are nested; same group as blind #6.

### 2026-10-30 — Review pass
- verdicts: 32 findings — high 0, medium 2, low 22, false 7, maybe-false 1
- findings:
  - `[medium]` `[patch]` (verification-gap) the frame-delta cap has no test; removing it keeps every player test green — extracted `internal fun frameDelta(now, last, speed)` in `DemonstrationPlayer.kt` and added a test that a 5 000 ms stall yields one step (600 / 300 ms) while a 16 ms frame passes through.
  - `[false]` `[reject]` (blind) step Goal/Keep/Watch/Notice text not shown — the intent's Never list gives the step card to Story 2.5.
  - `[false]` `[reject]` (blind) player not wired into any screen — carried: the Lesson screen and navigation are Story 2.5.
  - `[low]` `[reject]` (blind) emphasis can run ahead of the flip at a step boundary — carried: cosmetic lag; renderer-driven focus would add a callback API.
  - `[low]` `[reject]` (blind) next step's focus appears only after its first Move; Move label same before and after — carried: the spec defines the focus and current Move as `max(position − 1, 0)`; fix would edit the spec.
  - `[low]` `[reject]` (blind) TalkBack reads glyph and description; no live announcements; emphasis not spoken — a node with `contentDescription` is spoken by its description, not its text; live/emphasis announcements carried to the cube-semantics accessibility story (UX-DR29).
  - `[maybe-false]` `[reject]` (blind) `◀`/`▶` may render as emoji; play and step forward share `▶` — U+25B6/U+25C0 are `Emoji_Presentation=No` (text by default); would need on-device rendering on an emoji-forward font to settle; low if true. Shared glyph carried (DESIGN.md transport).
  - `[low]` `[reject]` (blind) reduce motion read once; rotation/config change resets playback — carried: ephemeral by design, rare setting change.
  - `[low]` `[reject]` (blind) not-checkpointed scan one level deep; count assertion brittle — carried for depth; the assertion is `>=`, so adding events cannot break it.
  - `[low]` `[defer]` (blind) `Lesson.demonstration` never checked against steps — carried (already in deferred list).
  - `[low]` `[reject]` (blind) a Cube Rotation or slice step lights the whole cube — real for `x/y/z`, but it is exactly the spec's "involved" definition (every sticker moves); changing it would edit the spec.
  - `[low]` `[reject]` (blind) no scrub control in the transport — carried: controller snapping is the AC surface.
  - `[low]` `[reject]` (blind) one-Move restart test cannot fail — the three-Move restart test (counter back to `1 / 3`, net at start) already pins replay.
  - `[low]` `[patch]` (blind) every DemonstrationTest uses the same predicate for preserve and completion, so a keep/target swap goes unnoticed — added `Keep comes from preserve and the target from completion` (preserve = cross, completion = corners on SOLVED+F / `F'`).
  - `[low]` `[reject]` (blind) `Piece.at` recomputed per sticker — carried: negligible cost.
  - `[low]` `[patch]` (blind) `DemonstrationPlayer` KDoc says only Keep and centers are lit — reworded to Keep, involved, target and centers.
  - `[low]` `[reject]` (edge) HALF → DOUBLE with accumulated time bursts Moves — carried: spec keeps accumulated time on a speed change.
  - `[low]` `[reject]` (edge) `scrubTo(NaN)` throws — carried: no caller; loud failure on a programmer error.
  - `[low]` `[reject]` (edge) `advance(Long.MAX_VALUE)` overflows `elapsed` — the only caller passes a delta capped at one step; a guard covers an undemonstrated input.
  - `[low]` `[reject]` (edge) `Demonstration` identity equality resets the player if a caller rebuilds it every composition — no caller exists; remembering derived objects is the standard Compose contract for Story 2.5's host.
  - `[low]` `[reject]` (edge) a reduce-motion flip recreates the state — carried.
  - `[low]` `[reject]` (edge) highlight strokes go negative under 7dp stickers — carried: unreachable at net sizes.
  - `[medium]` `[defer]` (intent) dim overlay / outline only checked through semantics flags — carried (already in deferred list).
  - `[false]` `[reject]` (intent) `|◀` restarts and plays instead of a paused start — the intent's Replay row defines replay as pos 0 and playing; the diff matches it.
  - `[false]` `[reject]` (intent) Learner cannot reach the player — carried: Story 2.5.
  - `[false]` `[reject]` (intent) `advance` could be read as cumulative time — the Speed row ("next Move after 1200 ms of advance") and "time arrives as advance(elapsedMillis)" fix the delta reading.
  - `[false]` `[reject]` (intent) `pause()` drops accumulated time — the intent only requires keeping it across a speed change; no bad outcome named.
  - `[low]` `[reject]` (intent) never-checkpointed proof is direct, not transitive — carried.
  - `[low]` `[defer]` (intent) `lesson.demonstration` vs steps — carried (already in deferred list).
  - `[low]` `[reject]` (intent) "free cube unchanged" not tested by this diff — with `emphasis = null` the draw block only calls `drawContent()` and no emphasis semantics are set; the existing freecube/render suites ran green.
  - `[false]` `[reject]` (intent) no emphasis for an empty Demonstration — matches the matrix (empty list: nothing to focus); no bad outcome.
  - `[low]` `[reject]` (intent) current Move at position 0 shows m0 before it plays — carried: spec definition.

## Design Notes

- **Why derive focus instead of a content field:** the epic fixes the Step shape (`goal, preserve, watch, notice, moves`, all required). The predicates already name what matters: `preserve` gives Keep, and the pieces a step newly brings into the `completion` set are exactly "the target" (the mockup's Stage 3 target corner). A step that places nothing has no outline — still correct.
- **Pieces, not indices, cross into the renderer:** the target corner moves during the step; mapping pieces to stickers through the currently rendered facelets keeps the outline on the moving piece.
- **Frame loop sketch:** `LaunchedEffect(state.playing) { var last = withFrameMillis { it }; while (state.playing) { withFrameMillis { now -> state.advance(now - last); last = now } } }`, where `state.advance` swaps the controller and submits mapped `PresentationItem`s.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test` -- expected: green, new playback tests included.
- `./gradlew :app:testDebugUnitTest --tests '*lesson*' --tests '*render*' --tests '*freecube*'` -- expected: green; free cube tests unchanged.
- `./gradlew spotlessCheck build` -- expected: exit 0 (Kover ≥ 80% on `:core:*`, Learner-text and boundary gates).

## Auto Run Result

Status: done

**Summary:** Added a pure, immutable `PlaybackController` over `List<Move>` in `:core:domain/playback` (play, pause, step forward/back, replay, speed 0.5×/1×/2×, scrub/seek snapping to Move boundaries, reduce-motion Snap mode) and `Demonstration.of(lesson)`, which derives each step's Keep / target / involved pieces from the Lesson's `preserve` and `completion` predicates. In `:app`, `Net2DRenderer.Content` takes an optional `NetEmphasis` (dim overlay, 3dp violet outline with 1dp cubie-body inner stroke, never recoloring), and a new `DemonstrationPlayer` shows the net plus the shared transport, speed, counter and current Move; under system reduce motion it steps instead of animating.

**Files changed:**
- `core/domain/.../playback/{PlaybackController,PlaybackItem,PlaybackSpeed,Demonstration}.kt` — controller, items, speeds, Demonstration focus.
- `core/domain/.../predicate/Piece.kt` — `Piece.at(facelets, index)` lookup.
- `core/domain/src/test/.../playback/{PlaybackControllerTest,DemonstrationTest}.kt` — every I/O matrix row, focus, lookup.
- `core/application/src/test/.../session/PlaybackNotCheckpointedTest.kt` — no session type holds playback; no serializer.
- `app/.../render/Net2DRenderer.kt`, `ui/theme/Tokens.kt` — emphasis drawing + semantics, dim token.
- `app/.../ui/lesson/{DemonstrationPlayer,ReduceMotion}.kt`, `res/values/strings.xml` — player, transport, reduce-motion read, chrome strings.
- `app/src/test/.../ui/lesson/DemonstrationPlayerTest.kt` — 15 Robolectric tests.

**Review findings:** 34 (high 0, medium 8, low 23, false 3). Patches applied: 7 entries — medium 4 (frame-delta cap; frame-loop cadence/restart tests; multi-step focus test; reduce-motion false case + default wiring), low 3 (empty-Demonstration emphasis, radio semantics, `translatable="false"`). Deferred: 2 (demonstration-vs-steps validator gap, low; pixel verification of overlay/outline, medium). Rejected with reasons in the Review Triage Log (Lesson screen is 2.5, scrub UI, Settings override, structural checkpoint proof, glyphs per DESIGN, current-Move definition, scan depth, config-change reset, TalkBack emphasis, focus lag, lookup cost, naming, NaN scrub, speed-change burst per spec, tiny-sticker stroke).

**Follow-up review recommended:** true — four medium entries were patched on a first pass. Unverified risk: the new timing-based player tests depend on Robolectric's frame clock and the frame-delta cap; a speed change from 0.5× to 2× mid-play can still emit up to three Moves on the next frame (spec-mandated kept time).

**Verification:** `./gradlew :core:domain:test :core:application:test` exit 0 (PlaybackControllerTest 16, DemonstrationTest 7, PlaybackNotCheckpointedTest 3); `./gradlew :app:testDebugUnitTest --tests '*lesson*' --tests '*freecube*'` exit 0 (DemonstrationPlayerTest 15); `./gradlew spotlessCheck build` exit 0 (Kover, Learner-text, boundary, lint gates). Every I/O matrix row is covered by a passing test.

**Residual risks:** nothing in the shipped app hosts `DemonstrationPlayer` until Story 2.5; overlay/outline visuals are checked only through semantics (deferred); `Lesson.demonstration` may diverge from the steps (deferred).

### Follow-up review pass (2026-10-30)

**Summary:** Fresh four-layer review of the shipped Story 2.4 diff. No high findings; three small patches applied, nothing new deferred.

**Files changed this pass:**
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayer.kt` — frame-delta cap extracted to `internal fun frameDelta`; KDoc corrected.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayerTest.kt` — stalled-frame cap test.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/playback/DemonstrationTest.kt` — distinct preserve/completion test.

**Review findings:** 32 (high 0, medium 2, low 22, false 7, maybe-false 1). Patched: 3 entries — medium 1 (frame-delta cap test), low 2 (keep/target swap test, KDoc). Deferred: none new (2 carried). Rejected with reasons in the triage log.

**Follow-up review recommended:** false — follow-up pass patched no high finding; the work has converged.

**Verification:** `./gradlew :core:domain:test :core:application:test` exit 0; `./gradlew :app:testDebugUnitTest --tests '*lesson*' --tests '*render*' --tests '*freecube*'` exit 0; `./gradlew spotlessCheck build` exit 0 (after `spotlessApply` on the new tests).

**Residual risks:** unchanged — no host for the player until Story 2.5; visuals verified via semantics only (deferred); `Lesson.demonstration` drift (deferred).
