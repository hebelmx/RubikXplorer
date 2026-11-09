---
title: 'Algorithm detail with Demonstration'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: '2b91f28dd6074997ff6db1541175b08441ed2e21'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Library tiles do nothing when tapped, so a Learner cannot watch an Algorithm's Demonstration, and the shared playback transport has no scrub control (FR-18, UX-DR14).

**Approach:** Tapping a tile opens an Algorithm detail inside the Library tab, with the navigation bar or rail kept. It shows the Case diagram, the Notation as a Move strip, and a Demonstration from the Case state on the 3D cube, using the one `PlaybackController`, the shared transport with a new optional scrub slider, and drag View Rotation.

## Boundaries & Constraints

**Always:** The Demonstration starts paused at the Case state, which is `LastLayerMatcher.caseState` with YELLOW on U, the same last layer the diagram uses, and it plays the Algorithm's own Notation parsed by `Notation.parse`. Playback is the existing `NetPlayback`/`PlaybackController` through `Cube3DRenderer`. The scrub slider has one stop per Move boundary (0..size), pauses playback and snaps through `PlaybackController.scrubTo`. The stage shows the VIRTUAL frame label, and the current Move is marked in the Move strip with an `ink-primary` outline, never violet. The current Move's `MoveNote` line shows under it. Drag orbits the view from anywhere on the stage, double-tap resets it, and the existing view controls remain the non-gesture alternative; no gesture or control here ever turns a layer. Back (system or on-screen) returns to the Library list with its filter and grid position unchanged. The open Algorithm survives a tab switch and state restoration. All new Learner text is a content string or Android resource, and all text sets an ink color. Portrait and landscape both keep the cube and the transport reachable.

**Never:** No `SessionEvent`, session, checkpoint or persistence of playback (AD-20). No Algorithm Status control, label, "Not in your set yet." line or `SetAlgorithmStatus` (9.6). No 2-look Lesson or cross-link (9.7), no *Recognize case* entry (v1.1). No alternative Algorithms: a tile opens its primary. No scrub slider in Lessons or Solution Playback. No change to the presentation queue's FIFO order. No Certainty value invented for the frame label. No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open from tile | Tap a Library tile at compact width | Detail shows Case name, family heading, diagram, Move strip and the transport; the navigation bar is still there; counter `0 / n` | No error expected |
| Open at rail width | Tap a tile at `w1000dp` | Detail beside the rail | No error expected |
| Start state | Any of the 78 Algorithms | The cube shows the Case state; the shown top layer is the diagram's Case | No error expected |
| Every Algorithm plays | Each of the 78, seek to every boundary | Domain: state after k Moves equals the start with the first k Moves applied; the end state has the last layer solved up to AUF | No error expected |
| Scrub back and forth | Slider set to boundaries forward and back, including 0 and n | Playback paused; counter equals the boundary; settled rendered facelets equal that boundary's state; no rendered state between boundaries once settled | No error expected |
| Scrub during animation | Playing, a turn in flight, slider set | Once the in-flight turn finishes, rendered facelets equal the boundary | No error expected |
| Transport | Play, pause, step forward, step back, speed 0.5×/1×/2× | Same behavior as other Demonstrations, driven by the shared transport | No error expected |
| Current Move | Position 3 of an Algorithm with a repeated Move | Only the chip at index 2 is marked current; the note is that Move's | No error expected |
| View Rotation | Drag on the cube, then double-tap | Orbit changes then returns to default; position and rendered facelets unchanged; no Move is played | No error expected |
| Back from detail | System back or the on-screen back | Library list, same filter and same grid position; Learn is not selected | No error expected |
| Tab switch | Open detail, go to You, return to Library | Same Algorithm's detail; Demonstration back at its start | No error expected |
| Restoration | Detail open, state restored | Same Algorithm's detail | No error expected |
| Diagrams cut | `showDiagram = false` | Name and Notation only, no placeholder art | No error expected |
| Bad Notation | Algorithm whose Notation does not parse | `DemonstrationResult.InvalidNotation`; the screen shows the load-failure message and the back control, no player | Sealed result |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/Demonstration.kt:47-97` -- `Demonstration.of(lesson)`, private `involved`; add the Case factory beside it.
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/PlaybackController.kt:74-82` -- `scrubTo`/`seek` emit a `Snap` and pause; reuse, do not change.
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/MoveNote.kt` -- `MoveNote.of(move)`; all 54 Moves have strings.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/LastLayerMatcher.kt:83` -- `caseState(algorithm, lastLayer)`; its default is WHITE, so pass the Library color explicitly.
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagram.kt:47` -- `derive(algorithm, lastLayer = YELLOW)`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/CubeState.kt:24-37` -- Moves act through `orientation`; `toFacelets()` is the Learner's view.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt:42-89,108-151` -- `DemonstrationPlayerState` (`presentedIndex` private, `scrubTo` exists), the player layout.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt:482` -- `NotationChip(move)`, tag `moveChip:$token`; `:475` `FlowRow` use.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:1251` -- `FrameLabel(strings, certainty, syncState)`; a null certainty is allowed.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/` -- `rememberReduceMotion()`.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt:196` -- `PlaybackTransport(state, modifier, note)`; `:179` `PlaybackFrameLoop`.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt:161-169` -- `Content` parameters; `:375` the gesture block exists only when `onMoveIntent != null`, and `:383-386` uses `onCube` to choose orbit or turn; `:158` `renderedFacelets()`; `:94` `orbit`.
- `app/src/main/kotlin/dev/cubentia/app/render/gl/PresentationQueue.kt` -- FIFO, pinned by `PresentationQueueTest.burstKeepsEveryTargetAndSnapWaitsForItsFrame`; read-only.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- grid items at the `AlgorithmTile` call; the heading text to share.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmTile.kt` -- `RECOGNITION_DIAGRAMS_ENABLED`, diagram description key.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/RecognitionDiagramView.kt` -- `RecognitionDiagramView`, `DIAGRAM_TAG`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `libraryFilter` hoisting, `BackHandler`, `Tab.LIBRARY` branch of `destination`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/LearnScreen.kt:423-431` -- back `IconButton` pattern with `ic_close`.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayerTest.kt` -- `mainClock` patterns, `RenderedArrowKey`.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt:40` -- `launch` harness; `app/src/test/kotlin/dev/cubentia/app/ui/lesson/LessonScreenTest.kt:366` -- `performTouchInput` on `cube3d`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagram.kt` -- add `val LIBRARY_LAST_LAYER: Color = Color.YELLOW` in the companion and use it as `derive`'s default -- one source for diagram and Demonstration.
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/Demonstration.kt` -- add `Demonstration.ofCase(algorithm: Algorithm, lastLayer: Color = RecognitionDiagram.LIBRARY_LAST_LAYER): DemonstrationResult`: `InvalidNotation(0, error)` for bad Notation, otherwise one step over all Moves from `LastLayerMatcher.caseState`, with `StepFocus(keep = emptySet(), target = emptySet(), involved = involved(...))` -- the Demonstration from the Case state.
- `core/domain/src/test/kotlin/dev/cubentia/domain/playback/DemonstrationCaseTest.kt` -- for all 78 shipped Algorithms: start matches `LastLayerMatcher` for its Case and the diagram derived from the Algorithm, every boundary state is correct, the end state has the last layer solved up to AUF; bad Notation returns `InvalidNotation` -- domain rows.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- add `freeViewRotation: Boolean = false` to `Content`: the gesture block also runs when true, keyed on it, and treats every drag as a view drag (`onCube` false), with no Move committed -- drag orbit without layer turns.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- add `onScrub: ((Double) -> Unit)? = null`: when set and `size > 0`, a neutral M3 `Slider` (tag `playback:scrub`, `valueRange` 0..size, `steps = size - 1`, thumb and active track `inkPrimary`, inactive `hairline`, content description from `playback_scrub_description`, state description the counter) that calls `onScrub(boundary / size)` only when the rounded boundary differs from `position` -- the shared scrub control.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` -- extract `ColumnScope.DemonstrationStage(state, strings, style, summary, certainty, showFrameLabel = certainty != null, freeViewRotation = false)` holding the existing label/controls row and cube, with `DemonstrationPlayer` calling it unchanged; expose `internal val shownMoveIndex: Int` from `presentedIndex` -- reuse without a second player.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt` -- add `current: Boolean = false` to `NotationChip`, using a 1.5dp `inkPrimary` border when true -- the current-Move outline.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- add last parameter `onOpenAlgorithm: (AlgorithmId) -> Unit = {}`, make each tile clip to `shapes.medium` and clickable with `Role.Button` and `onClickLabel`, and extract `familyHeading(strings, set, family)` -- the entry point.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- add `AlgorithmDetailScreen(case, algorithm, strings, onBack, modifier, showDiagram = RECOGNITION_DIAGRAMS_ENABLED)`, per Design Notes: `surfaceBase` and safe-drawing handled by the caller's modifier; tags `algorithmDetail:back`, `algorithmDetail:title`, `algorithmDetail:strip`, `algorithmDetail:move:<index>` with `selected` semantics -- the detail surface.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- hoist `openAlgorithm` (`rememberSaveable` String id), show the detail for it inside `Tab.LIBRARY` when it resolves to a Case and Algorithm in the ready bundle, otherwise the list; `BackHandler` closes it before leaving the tab -- bottom nav kept.
- `app/src/main/res/values/strings.xml` -- add `playback_scrub_description`, `library_open_detail`, `library_detail_back` -- chrome strings.
- `app/src/test/kotlin/dev/cubentia/app/ui/playback/PlaybackTransportScrubTest.kt` -- slider absent without `onScrub`; present with `size + 1` boundaries; `SetProgress` calls `onScrub` with the boundary fraction -- control contract.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreenTest.kt` -- detail rows of the matrix on shipped Algorithms: start facelets, scrub forward and back settle on boundaries, scrub during a turn, transport, current chip with a repeated Move, note text, VIRTUAL label, drag orbit and double-tap with no Move, diagrams cut, bad Notation, no status text -- the observable surface.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt` -- through `AppShell`: tile opens detail with nav bar at phone width and rail at `w1000dp`; back and on-screen back keep filter and grid position; tab switch and restoration keep the open Algorithm -- proves the wiring.

**Acceptance Criteria:**
- Given an Algorithm tile, when tapped, then detail opens (bottom nav kept) with the Case diagram, Notation, and a Demonstration from the Case state using the shared transport (play, pause, step, speed, scrub) and free View Rotation.
- Given the Demonstration, when it is scrubbed back and forth, then the rendered cube always matches the Move boundary it snapped to.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 21 findings — high 0, medium 0, low 18, false 3, maybe-false 0
- findings:
  - `[low]` `[patch]` Blind: scrubbing to the current boundary while playing does not pause — `PlaybackTransport` now also calls `onScrub` when `isPlaying`.
  - `[low]` `[patch]` Blind: the merged card reports a misleading `selected` from its chips to TalkBack — replaced by the private `CurrentMoveKey` semantics; the transport's Move label and note announce the current Move.
  - `[low]` `[reject]` Blind: the five-button transport can overflow a very narrow landscape split window — only windows wider than tall and under about 600dp wide; a fix needs a width threshold and a second layout, not a direct correction.
  - `[low]` `[patch]` Blind: the notation strip mirrors under an RTL locale — strip forced to LTR; `strip stays left to right under an RTL locale` pins it.
  - `[false]` `[reject]` Blind: the family-heading `Text` has no ink color — `AlgorithmDetailScreen` passes `color = tokens.inkSecondary` on that `Text`.
  - `[low]` `[patch]` Blind: the back test never scrolls the grid — added `back from detail keeps a scrolled grid position`, through both back paths.
  - `[low]` `[patch]` Blind: the two-pointer path in free view is untested — added `two finger twist in free view orbits and commits nothing`.
  - `[low]` `[patch]` Blind: the mid-turn scrub test never checks that playback paused — asserts the Play control and a held boundary after 3 s.
  - `[low]` `[patch]` Edge: scrubbing the current boundary during playback does not pause — same root cause and fix as the first row.
  - `[low]` `[patch]` Verification gap: nothing proves the detail screen's drag rotates the camera — added `RenderedOrbitKey` to the cube's semantics and `dragging the detail cube orbits and double tap resets it` on the real screen.
  - `[low]` `[patch]` Intent: AC1 orbit is asserted only on a standalone renderer — same fix as the verification-gap row.
  - `[low]` `[reject]` Intent: a `Snap` waits behind a turn in flight, so "always" holds only once settled — a lag of at most one 200 ms turn on the first scrub tick; preempting needs new queue semantics against `PresentationQueueTest.burstKeepsEveryTargetAndSnapWaitsForItsFrame`, and every state shown is a Move boundary.
  - `[low]` `[patch]` Intent: scrubs are exact `SetProgress` calls only — added `rapid scrubs and a touch drag settle on the last boundary`.
  - `[low]` `[reject]` Intent: on-screen scrub checks use one Algorithm — the screen has no per-Algorithm branch and `DemonstrationCaseTest` proves every boundary of all 78.
  - `[low]` `[reject]` Intent: no shell-level assertion of the Case state — the shell renders the same `AlgorithmDetailScreen` that the screen tests cover.
  - `[low]` `[patch]` Intent: pause is never pressed on detail — added `pause holds the boundary`; speed timing stays covered by the shared transport's tests.
  - `[false]` `[reject]` Intent: the strip marks the first Move before any has played — `PlaybackTransportState.currentMove` is documented as "the first Move before any has played" for every transport.
  - `[low]` `[reject]` Intent: scrub is added only to detail — the AC names this screen; Lessons and Solution Playback tie position to session behavior.
  - `[low]` `[reject]` Intent: `ViewOrbit` keeps its 85-degree pitch limit — the app-wide camera rule from FR-2, unchanged and shared with the free cube.
  - `[low]` `[reject]` Intent: FR-6 per-step explanations are absent — Algorithms have no such content fields; `MoveNote` is the specified text for Move-by-Move playback.
  - `[false]` `[reject]` Intent: behavior beyond the AC text — the back, restoration, strip and note behavior come from the story's cited UX-DR14 and Flow 6 and from EXPERIENCE.md.

## Design Notes

**Layout.** `BoxWithConstraints` on the screen. Height ≥ width: a column of back/title row, a `Column(Modifier.weight(1f))` holding the stage, then `Column(Modifier.heightIn(max = maxHeight * 0.55f).verticalScroll(...))` holding the transport and the card. Width > height: a row of the back/title row above the stage on the left half, and the transport and card in a `verticalScroll` column on the right half. The card is `surface-raised`, `shapes.medium`: the diagram beside a `FlowRow` of `NotationChip`s, merged for TalkBack. This follows `mockups/key-library.html`. Do not place the stage in a scrolling column, because its cube uses `weight` and a drag on it must orbit.

**Scrub lives in the shared transport as an option.** The AC adds scrub to the transport for this screen; Lessons and Solution Playback have physical-mode and step-card behavior tied to position and keep their current controls.

**Why the queue stays FIFO.** A `Snap` waits behind a turn in flight, pinned by an existing test. The cube can show the previous turn for at most one turn's duration, and it always settles on the boundary, which is what the AC's test observes.

**Dimming.** No Keep or target exists for a Library Algorithm, so `involved` is the lit set. Pieces that never move dim, and centers stay lit, as in other Demonstrations.

**Starts paused.** The opening view must show the Case that the diagram names. Flow 5's "opens with its Demonstration playing" belongs to v1.1 *Recognize case*.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `DemonstrationCaseTest`, `PlaybackTransportScrubTest`, `AlgorithmDetailScreenTest`, `AppShellLibraryTest`, and the existing Demonstration, Lesson and queue tests unchanged.

## Auto Run Result

**Summary.** Tapping a Library tile opens Algorithm detail inside the Library tab, with the navigation bar or rail kept. It shows the Case diagram, the Notation as a Move strip with the current Move outlined, and a Demonstration from the Case state on the 3D cube. The shared transport gained an optional scrub slider that snaps to Move boundaries, and drag orbits the view with no layer turns.

**Files changed.**
- `core/domain/src/main/kotlin/dev/cubentia/domain/lastlayer/RecognitionDiagram.kt` -- `LIBRARY_LAST_LAYER`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/Demonstration.kt` -- `Demonstration.ofCase`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/playback/DemonstrationCaseTest.kt` -- all 78 Algorithms at every boundary.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- `freeViewRotation`, `RenderedOrbitKey`.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- optional scrub slider.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` -- `DemonstrationStage`, `shownMoveIndex`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt` -- current-Move outline on `NotationChip`.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- the detail surface.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- tappable tiles, `familyHeading`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- hoisted open Algorithm and back handling.
- `app/src/main/res/values/strings.xml` -- three chrome strings.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreenTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/playback/PlaybackTransportScrubTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt` -- the observable surface.

**Review.** 21 findings. Patched: 11 rows, all low. Deferred: none. Rejected: 10 (low 7, false 3), each with its reason in the Review Triage Log.

**Follow-up review: not recommended.** Patched by verdict: high 0, medium 0, low 11 rows.

**Verification.** `./gradlew spotlessCheck build` was BUILD SUCCESSFUL after the patches: 1449 tests, 0 failed, 0 skipped. All 14 matrix rows have a covering test that ran. No instrumented or device runs, so the drawn appearance, TalkBack and real-device gesture feel are unverified.

**Residual risks.** `Cube3DEmphasisTest.arrow draws in on frame clock...` failed once in a full build and passed in three isolated runs and the next full build; it predates this story, and the file is unchanged since 2026-11-08. A `Snap` waits behind a turn in flight for at most one turn, by design. Very narrow landscape split windows may clip the transport row.
